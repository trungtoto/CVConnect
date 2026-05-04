package com.cvconnect.service.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.cvconnect.dto.attachFile.AttachFileDto;
import com.cvconnect.enums.CoreErrorCode;
import com.cvconnect.service.CloudinaryService;
import nmquan.commonlib.exception.AppException;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CloudinaryServiceImpl implements CloudinaryService {
    @Autowired
    private Cloudinary cloudinary;

    private static final int MAX_FILE_QUANTITY = 5;
    private static final String FOLDER_BASE = "cv-connect/";
    private static final String SUPPORTED_FILE_TYPES = "jpg, png, pdf, doc, docx";
    private static final int MAX_FILE_SIZE_MB = 5;

    @Override
    public List<AttachFileDto> uploadFile(MultipartFile[] files, String folder) {
        try {
            if(files.length > MAX_FILE_QUANTITY){
                throw new AppException(CoreErrorCode.UPLOAD_FILE_QUANTITY_EXCEED_LIMIT, MAX_FILE_QUANTITY);
            }
            for(MultipartFile file : files){
                if(!isAllowedFile(file.getContentType())){
                    throw new AppException(CoreErrorCode.FILE_FORMAT_NOT_SUPPORTED, SUPPORTED_FILE_TYPES);
                }
                if(file.getSize() > MAX_FILE_SIZE_MB * 1024 * 1024) {
                    throw new AppException(CoreErrorCode.FILE_TOO_LARGE, MAX_FILE_SIZE_MB);
                }
            }
            if(folder == null || folder.isEmpty()){
                folder = "";
            }

            List<AttachFileDto> attachFileDtos = new ArrayList<>();
            for (MultipartFile file : files) {
                String originalFilename = file.getOriginalFilename();
                String baseName = FilenameUtils.getBaseName(originalFilename);
                String extension = FilenameUtils.getExtension(originalFilename).toLowerCase();

                String newFileName = sanitizePublicId(baseName) + "_" + System.currentTimeMillis();
                if (extension.matches("doc|docx")) {
                    newFileName = newFileName + "." + extension;
                }
                String resourceType = "auto";
                if (extension.matches("pdf|doc|docx")) {
                    resourceType = "raw";
                }

                Map map = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                    "folder", FOLDER_BASE + folder,
                    "resource_type", resourceType,
                    "type", "upload",
                    "public_id", newFileName
                ));
                AttachFileDto attachFileDto = AttachFileDto.builder()
                    .originalFilename(originalFilename)
                    .baseFilename(baseName)
                    .extension(extension)
                    .filename(newFileName)
                    .format(map.get("format") != null ? map.get("format").toString() : null)
                    .resourceType(map.get("resource_type") != null ? map.get("resource_type").toString() : null)
                    .secureUrl(map.get("secure_url") != null ? map.get("secure_url").toString() : null)
                    .type(map.get("type") != null ? map.get("type").toString() : null)
                    .url(map.get("url") != null ? map.get("url").toString() : null)
                    .publicId(map.get("public_id") != null ? map.get("public_id").toString() : null)
                    .folder(map.get("folder") != null ? map.get("folder").toString() : null)
                    .build();
                attachFileDtos.add(attachFileDto);
            }
            return attachFileDtos;
        } catch (IOException e) {
            throw new AppException(CoreErrorCode.UPLOAD_FILE_ERROR);
        }
    }

    @Override
    public void deleteByPublicIds(List<String> publicIds) {
        publicIds.forEach(publicId -> {
            try {
                cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
            } catch (IOException ignored) {

            }
        });
    }

    private boolean isAllowedFile(String contentType) {
        return contentType != null && (
                contentType.equals("image/jpeg") ||     // .jpg, .jpeg
                        contentType.equals("image/png") ||      // .png
                        contentType.equals("application/pdf") ||    // .pdf
                        contentType.equals("application/msword") ||   // .doc
                        contentType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")   // .docx
        );
    }

    private String sanitizePublicId(String input) {
        if (input == null) {
            return "file";
        }

        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKD);
        String ascii = normalized.replaceAll("[^\\p{ASCII}]", "");
        String safe = ascii.replaceAll("[^A-Za-z0-9_-]+", "_");
        safe = safe.replaceAll("_+", "_").replaceAll("^_+|_+$", "");

        return safe.isBlank() ? "file" : safe;
    }

}
