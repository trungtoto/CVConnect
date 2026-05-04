package com.cvconnect.service.impl;

import com.cvconnect.constant.Constants;
import com.cvconnect.dto.attachFile.AttachFileDto;
import com.cvconnect.dto.attachFile.DownloadFileDto;
import com.cvconnect.entity.AttachFile;
import com.cvconnect.enums.CoreErrorCode;
import com.cvconnect.repository.AttachFileRepository;
import com.cvconnect.service.AttachFileService;
import com.cvconnect.service.CloudinaryService;
import com.cloudinary.Cloudinary;
import nmquan.commonlib.constant.CommonConstants;
import nmquan.commonlib.exception.AppException;
import nmquan.commonlib.model.BaseEntity;
import nmquan.commonlib.utils.ObjectMapperUtils;
import nmquan.commonlib.utils.WebUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AttachFileServiceImpl implements AttachFileService {
    @Autowired
    private AttachFileRepository attachFileRepository;
    @Autowired
    private CloudinaryService cloudinaryService;
    @Autowired
    private Cloudinary cloudinary;

    @Qualifier(CommonConstants.EXTERNAL)
    @Autowired
    private RestTemplate restTemplate;

    @Override
    public List<Long> uploadFile(MultipartFile[] files) {
        String username = WebUtils.getCurrentUsername();
        if(username == null) {
            username = Constants.RoleCode.ANONYMOUS;
        }
        List<AttachFileDto> attachFileDtos = cloudinaryService.uploadFile(files, username);
        List<AttachFile> attachFiles = attachFileDtos.stream()
                .map(dto -> ObjectMapperUtils.convertToObject(dto, AttachFile.class))
                .toList();
        attachFileRepository.saveAll(attachFiles);
        return attachFiles.stream()
                .map(BaseEntity::getId)
                .toList();
    }

    @Override
    public List<AttachFileDto> getAttachFiles(List<Long> ids) {
        List<AttachFile> attachFiles = attachFileRepository.findAllById(ids);
        if(ids.size() != attachFiles.size()){
            throw new AppException(CoreErrorCode.ATTACH_FILE_NOT_FOUND);
        }
        return attachFiles.stream()
                .map(attachFile -> ObjectMapperUtils.convertToObject(attachFile, AttachFileDto.class))
                .toList();
    }

    @Override
    public String getDownloadUrl(Long id) {
        AttachFile attachFile = attachFileRepository.findById(id)
                .orElseThrow(() -> new AppException(CoreErrorCode.ATTACH_FILE_NOT_FOUND));

        List<String> urls = buildCandidateUrls(attachFile);
        for (String url : urls) {
            if (StringUtils.hasText(url)) {
                return url;
            }
        }

        String privateUrl = fetchPrivateDownloadUrl(attachFile);
        if (StringUtils.hasText(privateUrl)) {
            return privateUrl;
        }

        throw new AppException(CoreErrorCode.DOWNLOAD_FILE_FAILED);
    }

    @Override
    public DownloadFileDto download(Long id) {
        AttachFile attachFile = attachFileRepository.findById(id)
                .orElseThrow(() -> new AppException(CoreErrorCode.ATTACH_FILE_NOT_FOUND));
        try {
            byte[] data = fetchFirstAvailable(attachFile);
            if (data == null) {
                throw new AppException(CoreErrorCode.DOWNLOAD_FILE_FAILED);
            }

            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
            String encodedFileName = URLEncoder.encode(attachFile.getOriginalFilename(), StandardCharsets.UTF_8);
            return DownloadFileDto.builder()
                    .attachFileId(attachFile.getId())
                    .filename(encodedFileName)
                    .byteArrayInputStream(byteArrayInputStream)
                    .contentType(resolveContentType(attachFile))
                    .build();
        } catch (RestClientException | IllegalArgumentException ex) {
            throw new AppException(CoreErrorCode.DOWNLOAD_FILE_FAILED);
        }
    }

    private byte[] fetchFirstAvailable(AttachFile attachFile) {
        List<String> urls = buildCandidateUrls(attachFile);
        for (String url : urls) {
            if (!StringUtils.hasText(url)) {
                continue;
            }
            try {
                URI uri = URI.create(url);
                ResponseEntity<byte[]> responseEntity = restTemplate.exchange(uri, HttpMethod.GET, null, byte[].class);
                if (responseEntity.getStatusCode() == HttpStatus.OK && responseEntity.getBody() != null) {
                    return responseEntity.getBody();
                }
            } catch (RestClientException | IllegalArgumentException ignored) {
                // Try next candidate URL.
            }
        }

        String privateUrl = fetchPrivateDownloadUrl(attachFile);
        if (StringUtils.hasText(privateUrl)) {
            try {
                URI uri = URI.create(privateUrl);
                ResponseEntity<byte[]> responseEntity = restTemplate.exchange(uri, HttpMethod.GET, null, byte[].class);
                if (responseEntity.getStatusCode() == HttpStatus.OK && responseEntity.getBody() != null) {
                    return responseEntity.getBody();
                }
            } catch (RestClientException | IllegalArgumentException ignored) {
                // Fall through.
            }
        }

        return null;
    }

    private List<String> buildCandidateUrls(AttachFile attachFile) {
        Set<String> urls = new LinkedHashSet<>();
        if (StringUtils.hasText(attachFile.getPublicId())) {
            String format = StringUtils.hasText(attachFile.getFormat())
                ? attachFile.getFormat()
                : attachFile.getExtension();

            String storedResourceType = StringUtils.hasText(attachFile.getResourceType())
                ? attachFile.getResourceType()
                : null;
            String storedDeliveryType = StringUtils.hasText(attachFile.getType())
                ? attachFile.getType()
                : null;

            if (storedResourceType != null || storedDeliveryType != null) {
            urls.add(buildSignedUrl(attachFile.getPublicId(), storedResourceType, storedDeliveryType, format));
            }

            String guessedResourceType = guessResourceType(attachFile);
            urls.add(buildSignedUrl(attachFile.getPublicId(), guessedResourceType, "upload", format));
            urls.add(buildSignedUrl(attachFile.getPublicId(), guessedResourceType, "authenticated", format));
            urls.add(buildSignedUrl(attachFile.getPublicId(), guessedResourceType, "private", format));
        }

        if (StringUtils.hasText(attachFile.getSecureUrl()) && isDocument(attachFile)) {
            urls.add(attachFile.getSecureUrl().replace("/image/upload/", "/raw/upload/"));
        }

        if (StringUtils.hasText(attachFile.getSecureUrl())) {
            urls.add(attachFile.getSecureUrl());
        }

        return new ArrayList<>(urls);
    }

    private String fetchPrivateDownloadUrl(AttachFile attachFile) {
        if (!StringUtils.hasText(attachFile.getPublicId())) {
            return null;
        }

        String cloudName = cloudinary.config.cloudName;
        String apiKey = cloudinary.config.apiKey;
        String apiSecret = cloudinary.config.apiSecret;
        if (!StringUtils.hasText(cloudName) || !StringUtils.hasText(apiKey) || !StringUtils.hasText(apiSecret)) {
            return null;
        }

        String deliveryType = StringUtils.hasText(attachFile.getType())
                ? attachFile.getType()
                : "upload";

        String storedResourceType = StringUtils.hasText(attachFile.getResourceType())
                ? attachFile.getResourceType()
                : null;
        String guessedResourceType = guessResourceType(attachFile);

        List<String> resourceTypes = storedResourceType == null
                ? List.of(guessedResourceType)
                : List.of(guessedResourceType, storedResourceType);

        List<String> endpoints = List.of("download", "private_download");
        for (String resourceType : resourceTypes) {
            for (String endpoint : endpoints) {
                String apiUrl = String.format(
                        "https://api.cloudinary.com/v1_1/%s/%s/%s",
                        cloudName,
                        resourceType,
                        endpoint
                );

                HttpHeaders headers = new HttpHeaders();
                headers.setBasicAuth(apiKey, apiSecret);
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("public_id", attachFile.getPublicId());
                form.add("type", deliveryType);

                try {
                    ResponseEntity<Map> response = restTemplate.exchange(
                            apiUrl,
                            HttpMethod.POST,
                            new HttpEntity<>(form, headers),
                            Map.class
                    );
                    if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                        Object url = response.getBody().get("url");
                        if (url == null) {
                            url = response.getBody().get("download_url");
                        }
                        if (url != null) {
                            return url.toString();
                        }
                    }
                } catch (RestClientException ignored) {
                    // Try next endpoint.
                }
            }
        }

        return null;
    }

    private String buildSignedUrl(String publicId, String resourceType, String deliveryType, String format) {
        if (!StringUtils.hasText(publicId)) {
            return null;
        }

        String finalResourceType = StringUtils.hasText(resourceType) ? resourceType : "raw";
        String finalDeliveryType = StringUtils.hasText(deliveryType) ? deliveryType : "upload";

        var urlBuilder = cloudinary.url()
                .resourceType(finalResourceType)
                .type(finalDeliveryType)
                .secure(true)
                .signed(true);

        if (StringUtils.hasText(format)) {
            urlBuilder = urlBuilder.format(format);
        }

        return urlBuilder.generate(publicId);
    }

    private String guessResourceType(AttachFile attachFile) {
        if (StringUtils.hasText(attachFile.getResourceType())) {
            return attachFile.getResourceType();
        }

        String extension = attachFile.getExtension();
        if (extension == null) {
            return "raw";
        }

        String ext = extension.toLowerCase();
        if (ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("gif")) {
            return "image";
        }

        return "raw";
    }

    private String resolveContentType(AttachFile attachFile) {
        String extension = attachFile.getExtension();
        if (extension == null) {
            return "application/octet-stream";
        }

        switch (extension.toLowerCase()) {
            case "pdf":
                return "application/pdf";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            default:
                return "application/octet-stream";
        }
    }

    private boolean isDocument(AttachFile attachFile) {
        String extension = attachFile.getExtension();
        if (extension == null) {
            return false;
        }
        String ext = extension.toLowerCase();
        return ext.equals("pdf") || ext.equals("doc") || ext.equals("docx");
    }

    @Override
    public void deleteByIds(List<Long> ids) {
        List<AttachFile> attachFiles = attachFileRepository.findAllById(ids);
        if(ids.size() != attachFiles.size()){
            throw new AppException(CoreErrorCode.ATTACH_FILE_NOT_FOUND);
        }
        attachFileRepository.deleteAll(attachFiles);
        cloudinaryService.deleteByPublicIds(
                attachFiles.stream()
                        .map(AttachFile::getPublicId)
                        .toList()
        );
    }
}
