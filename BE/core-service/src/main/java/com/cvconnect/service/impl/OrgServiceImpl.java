package com.cvconnect.service.impl;

import com.cvconnect.common.RestTemplateClient;
import com.cvconnect.constant.Constants;
import com.cvconnect.dto.attachFile.AttachFileDto;
import com.cvconnect.dto.common.NotificationDto;
import com.cvconnect.dto.failedRollback.FailedRollbackDto;
import com.cvconnect.dto.failedRollback.FailedRollbackUpdateAccountStatus;
import com.cvconnect.dto.industry.IndustryDto;
import com.cvconnect.dto.internal.response.UserDto;
import com.cvconnect.dto.jobAd.JobAdDto;
import com.cvconnect.dto.org.*;
import com.cvconnect.entity.Organization;
import com.cvconnect.enums.*;
import com.cvconnect.repository.OrgRepository;
import com.cvconnect.service.*;
import com.cvconnect.utils.CoreServiceUtils;
import nmquan.commonlib.constant.CommonConstants;
import nmquan.commonlib.dto.BaseDto;
import nmquan.commonlib.dto.request.ChangeStatusActiveRequest;
import nmquan.commonlib.dto.response.FilterResponse;
import nmquan.commonlib.dto.response.IDResponse;
import nmquan.commonlib.exception.AppException;
import nmquan.commonlib.utils.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrgServiceImpl implements OrgService {
    @Autowired
    private OrgRepository orgRepository;
    @Autowired
    private OrgAddressService orgAddressService;
    @Autowired
    private OrgIndustryService orgIndustryService;
    @Autowired
    private IndustryService industryService;
    @Autowired
    private AttachFileService attachFileService;
    @Autowired
    private RestTemplateClient restTemplateClient;
    @Lazy
    @Autowired
    private JobAdService jobAdService;
    @Autowired
    private KafkaUtils kafkaUtils;
    @Autowired
    private FailedRollbackService failedRollbackService;

    @Override
    @Transactional
    public IDResponse<Long> createOrg(OrganizationRequest request, MultipartFile[] files) {
        for(MultipartFile file : files) {
            CoreServiceUtils.validateImageFileInput(file);
        }
        List<Long> attachFileIds = attachFileService.uploadFile(files);

        Organization org = new Organization();
        org.setName(request.getName());
        org.setDescription(request.getDescription());
        org.setWebsite(request.getWebsite());
        org.setStaffCountFrom(request.getStaffCountFrom());
        org.setStaffCountTo(request.getStaffCountTo());
        org.setLogoId(attachFileIds.get(0));
        org.setCoverPhotoId(attachFileIds.size() > 1 ? attachFileIds.get(1) : null);
        orgRepository.save(org);

        if(request.getCreatedBy() != null) {
            orgRepository.updateCreatedBy(org.getId(), request.getCreatedBy());
        }

        if(request.getIndustryIds() != null && !request.getIndustryIds().isEmpty()) {
            if(request.getIndustryIds().size() > Constants.MAX_INDUSTRY_PER_ORG) {
                throw new AppException(CoreErrorCode.INDUSTRY_EXCEED_LIMIT, Constants.MAX_INDUSTRY_PER_ORG);
            }
            List<IndustryDto> industryDtos = industryService.findByIds(request.getIndustryIds()).stream()
                    .filter(BaseDto::getIsActive)
                    .toList();
            if(request.getIndustryIds().size() != industryDtos.size()) {
                throw new AppException(CoreErrorCode.INDUSTRY_NOT_FOUND);
            }
            List<OrgIndustryDto> industries = request.getIndustryIds().stream()
                            .map(id -> OrgIndustryDto.builder()
                                    .orgId(org.getId())
                                    .industryId(id)
                                    .build()
                            ).collect(Collectors.toList());
            orgIndustryService.createIndustries(industries);
        }

        if(request.getAddresses() != null && !request.getAddresses().isEmpty()) {
            List<OrgAddressDto> addresses = request.getAddresses().stream()
                            .map(address -> OrgAddressDto.builder()
                                    .orgId(org.getId())
                                    .isHeadquarter(address.isHeadquarter())
                                    .province(address.getProvince())
                                    .district(address.getDistrict())
                                    .ward(address.getWard())
                                    .detailAddress(address.getDetailAddress())
                                    .build()
                            ).collect(Collectors.toList());
            orgAddressService.createAddresses(addresses);
        }

        return IDResponse.<Long>builder()
                .id(org.getId())
                .build();
    }

    @Override
    public OrgDto findById(Long orgId) {
        Organization org = orgRepository.findById(orgId).orElse(null);
        if(ObjectUtils.isEmpty(org)) {
            return null;
        }
        return ObjectMapperUtils.convertToObject(org, OrgDto.class);
    }

    @Override
    public OrgDto getOrgInfo() {
        Long orgId = restTemplateClient.validOrgMember();
        Organization org = orgRepository.findById(orgId).orElse(null);
        if(ObjectUtils.isEmpty(org)) {
            throw new AppException(CoreErrorCode.ORG_NOT_FOUND);
        }

        OrgDto orgDto = ObjectMapperUtils.convertToObject(org, OrgDto.class);
        if(org.getLogoId() != null) {
            AttachFileDto logoInfo = attachFileService.getAttachFiles(List.of(org.getLogoId())).get(0);
            orgDto.setLogoUrl(logoInfo.getSecureUrl());
        }
        if(org.getCoverPhotoId() != null) {
            AttachFileDto coverPhotoInfo = attachFileService.getAttachFiles(List.of(org.getCoverPhotoId())).get(0);
            orgDto.setCoverPhotoUrl(coverPhotoInfo.getSecureUrl());
        }

        List<IndustryDto> industryDtos = industryService.getIndustriesByOrgId(orgId);
        orgDto.setIndustryList(industryDtos);

        return orgDto;
    }

    @Override
    @Transactional
    public IDResponse<Long> updateOrgInfo(OrganizationRequest request) {
        Long orgId = restTemplateClient.validOrgMember();
        request.setAddresses(null);

        Organization org = orgRepository.findById(orgId).orElse(null);
        if(ObjectUtils.isEmpty(org)) {
            throw new AppException(CoreErrorCode.ORG_NOT_FOUND);
        }
        org.setName(request.getName());
        org.setDescription(request.getDescription());
        org.setWebsite(request.getWebsite());
        org.setStaffCountFrom(request.getStaffCountFrom());
        org.setStaffCountTo(request.getStaffCountTo());
        orgRepository.save(org);

        List<Long> idsInReq = request.getIndustryIds();
        if (idsInReq == null || idsInReq.isEmpty()) {
            orgIndustryService.deleteByOrgId(orgId);
        } else {
            if(idsInReq.size() > Constants.MAX_INDUSTRY_PER_ORG) {
                throw new AppException(CoreErrorCode.INDUSTRY_EXCEED_LIMIT, Constants.MAX_INDUSTRY_PER_ORG);
            }

            List<IndustryDto> industryDtos = industryService.getIndustriesByOrgId(orgId);
            List<Long> idsInDb = industryDtos.stream()
                    .map(BaseDto::getId)
                    .toList();

            // delete
            List<Long> deleteIds = idsInDb.stream()
                    .filter(id -> !idsInReq.contains(id))
                    .toList();
            orgIndustryService.deleteByIndustryIdsAndOrgId(deleteIds, orgId);

            // add new
            List<Long> newIds = idsInReq.stream()
                    .filter(id -> !idsInDb.contains(id))
                    .toList();
            if(!newIds.isEmpty()) {
                List<OrgIndustryDto> industries = newIds.stream()
                        .map(id -> OrgIndustryDto.builder()
                                .orgId(orgId)
                                .industryId(id)
                                .build()
                        ).collect(Collectors.toList());
                orgIndustryService.createIndustries(industries);
            }
        }

        return IDResponse.<Long>builder()
                .id(orgId)
                .build();
    }

    @Override
    @Transactional
    public IDResponse<Long> updateOrgLogo(MultipartFile file) {
        CoreServiceUtils.validateImageFileInput(file);
        Long orgId = restTemplateClient.validOrgMember();
        Organization org = orgRepository.findById(orgId).orElse(null);
        if(ObjectUtils.isEmpty(org)) {
            throw new AppException(CoreErrorCode.ORG_NOT_FOUND);
        }
        Long oldLogoId = org.getLogoId();
        Long fileId = attachFileService.uploadFile(new MultipartFile[]{file}).get(0);
        org.setLogoId(fileId);
        orgRepository.save(org);
        if(oldLogoId != null) {
            attachFileService.deleteByIds(List.of(oldLogoId));
        }
        return IDResponse.<Long>builder()
                .id(orgId)
                .build();
    }

    @Override
    @Transactional
    public IDResponse<Long> updateOrgCoverPhoto(MultipartFile file) {
        CoreServiceUtils.validateImageFileInput(file);
        Long orgId = restTemplateClient.validOrgMember();
        Organization org = orgRepository.findById(orgId).orElse(null);
        if(ObjectUtils.isEmpty(org)) {
            throw new AppException(CoreErrorCode.ORG_NOT_FOUND);
        }
        Long oldCoverPhotoId = org.getCoverPhotoId();
        Long fileId = attachFileService.uploadFile(new MultipartFile[]{file}).get(0);
        org.setCoverPhotoId(fileId);
        orgRepository.save(org);
        if(oldCoverPhotoId != null) {
            attachFileService.deleteByIds(List.of(oldCoverPhotoId));
        }
        return IDResponse.<Long>builder()
                .id(orgId)
                .build();
    }

    @Override
    public Map<Long, OrgDto> getOrgMapByIds(List<Long> orgIds) {
        List<Organization> orgs = orgRepository.findAllById(orgIds);
        if(ObjectUtils.isEmpty(orgs)) {
            return Map.of();
        }
        List<OrgDto> orgDtos = ObjectMapperUtils.convertToList(orgs, OrgDto.class);
        for(OrgDto orgDto : orgDtos) {
            if(orgDto.getLogoId() != null) {
                AttachFileDto logoInfo = attachFileService.getAttachFiles(List.of(orgDto.getLogoId())).get(0);
                orgDto.setLogoUrl(logoInfo.getSecureUrl());
            }
        }
        return orgDtos.stream()
                .collect(Collectors.toMap(OrgDto::getId, dto -> dto));
    }

    @Override
    public OrgDto getOrgInfoOutside(Long orgId) {
        Organization org = orgRepository.findById(orgId).orElse(null);
        if(ObjectUtils.isEmpty(org)) {
            throw new AppException(CoreErrorCode.ORG_NOT_FOUND);
        }

        OrgDto orgDto = ObjectMapperUtils.convertToObject(org, OrgDto.class);
        if(org.getLogoId() != null) {
            AttachFileDto logoInfo = attachFileService.getAttachFiles(List.of(org.getLogoId())).get(0);
            orgDto.setLogoUrl(logoInfo.getSecureUrl());
        }
        if(org.getCoverPhotoId() != null) {
            AttachFileDto coverPhotoInfo = attachFileService.getAttachFiles(List.of(org.getCoverPhotoId())).get(0);
            orgDto.setCoverPhotoUrl(coverPhotoInfo.getSecureUrl());
        }

        List<IndustryDto> industryDtos = industryService.getIndustriesByOrgId(orgId);
        orgDto.setIndustryList(industryDtos);

        List<OrgAddressDto> addressDtos = orgAddressService.getByOrgId(orgId);
        orgDto.setAddresses(addressDtos);
        return orgDto;
    }

    @Override
    public List<OrgDto> getFeaturedOrgOutside() {
        List<OrgProjection> orgs = orgRepository.findFeaturedOrgs().stream()
                .limit(10)
                .toList();
        if(ObjectUtils.isEmpty(orgs)) {
            return new ArrayList<>();
        }
        List<OrgDto> orgDtos = ObjectMapperUtils.convertToList(orgs, OrgDto.class);
        // todo: need optimize number of calls
        for(OrgDto orgDto : orgDtos) {
            if(orgDto.getLogoId() != null) {
                AttachFileDto logoInfo = attachFileService.getAttachFiles(List.of(orgDto.getLogoId())).get(0);
                orgDto.setLogoUrl(logoInfo.getSecureUrl());
            }

            List<IndustryDto> industryDtos = industryService.getIndustriesByOrgId(orgDto.getId());
            orgDto.setIndustryList(industryDtos);

            List<OrgAddressDto> addressDtos = orgAddressService.getByOrgId(orgDto.getId());
            orgDto.setAddresses(addressDtos);
        }
        return orgDtos;
    }

    @Override
    public OrgDto getOrgByJobAd(Long jobAdId) {
        JobAdDto jobAdDto = jobAdService.findById(jobAdId);
        if(ObjectUtils.isEmpty(jobAdDto)) {
            throw new AppException(CoreErrorCode.JOB_AD_NOT_FOUND);
        }
        Long orgId = jobAdDto.getOrgId();
        Organization org = orgRepository.findById(orgId).orElse(null);
        if(ObjectUtils.isEmpty(org)) {
            throw new AppException(CoreErrorCode.ORG_NOT_FOUND);
        }

        OrgDto orgDto = ObjectMapperUtils.convertToObject(org, OrgDto.class);
        if(org.getLogoId() != null) {
            AttachFileDto logoInfo = attachFileService.getAttachFiles(List.of(org.getLogoId())).get(0);
            orgDto.setLogoUrl(logoInfo.getSecureUrl());
        }
        if(jobAdDto.getHrContactId() != null) {
            Long hrContactId = jobAdDto.getHrContactId();
            UserDto hrContact = restTemplateClient.getUser(hrContactId);
            orgDto.setHrContact(hrContact);
        }

        return orgDto;
    }

    @Override
    public FilterResponse<OrgDto> filterOrgs(OrgFilterRequest request) {
        if(request.getCreatedAtEnd() != null) {
            request.setCreatedAtEnd(DateUtils.endOfDay(request.getCreatedAtEnd(), CommonConstants.ZONE.UTC));
        }
        if(request.getUpdatedAtEnd() != null) {
            request.setUpdatedAtEnd(DateUtils.endOfDay(request.getUpdatedAtEnd(), CommonConstants.ZONE.UTC));
        }

        Page<OrgProjection> orgPage = orgRepository.filterOrgs(request, request.getPageable());

        List<Long> orgIds = orgPage.getContent().stream()
                .map(OrgProjection::getId)
                .toList();
        Map<Long, List<OrgAddressDto>> addressMap = orgAddressService.getMapOrgAddressByOrgIds(orgIds);
        Map<Long, List<IndustryDto>> industryMap = industryService.getMapIndustriesByOrgIds(orgIds);

        List<OrgDto> orgDtos = ObjectMapperUtils.convertToList(orgPage.getContent(), OrgDto.class);
        if (orgDtos != null) {
            for(OrgDto orgDto : orgDtos) {
                orgDto.setAddresses(addressMap.getOrDefault(orgDto.getId(), null));
                orgDto.setIndustryList(industryMap.getOrDefault(orgDto.getId(), null));
            }
        }

        return PageUtils.toFilterResponse(orgPage, orgDtos);
    }

    @Override
    public InputStreamResource exportOrg(OrgFilterRequest request) {
        request.setPageIndex(0);
        request.setPageSize(Integer.MAX_VALUE);
        FilterResponse<OrgDto> filter = filterOrgs(request);
        List<OrgDto> orgDtos = filter.getData();
        orgDtos.forEach(orgDto -> {
            String staffCountStr = "";
            if(orgDto.getStaffCountFrom() != null && orgDto.getStaffCountTo() != null) {
                staffCountStr = orgDto.getStaffCountFrom() + " - " + orgDto.getStaffCountTo();
            } else if(orgDto.getStaffCountFrom() != null) {
                staffCountStr = "Từ " + orgDto.getStaffCountFrom();
            } else if(orgDto.getStaffCountTo() != null) {
                staffCountStr = "Dưới " + orgDto.getStaffCountTo();
            }
            orgDto.setNumberOfEmployees(staffCountStr);

            // address
            if(!ObjectUtils.isEmpty(orgDto.getAddresses())) {
                String addressStr = orgDto.getAddresses().stream()
                        .map(OrgAddressDto::getDisplayAddress)
                        .collect(Collectors.joining("; "));
                orgDto.setAddressStr(addressStr);
            }

            // industry
            if(!ObjectUtils.isEmpty(orgDto.getIndustryList())) {
                String industryStr = orgDto.getIndustryList().stream()
                        .map(IndustryDto::getName)
                        .collect(Collectors.joining("; "));
                orgDto.setIndustryStr(industryStr);
            }

            // active str
            orgDto.setActiveStr(orgDto.getIsActive() ? "Đang hoạt động" : "Ngừng hoạt động");
            // created at str
            orgDto.setCreatedAtStr(DateUtils.instantToString_HCM(orgDto.getCreatedAt(), CommonConstants.DATE_TIME.DD_MM_YYYY_HH_MM_SS));
            // updated at str
            orgDto.setUpdatedAtStr(DateUtils.instantToString_HCM(orgDto.getUpdatedAt(), CommonConstants.DATE_TIME.DD_MM_YYYY_HH_MM_SS));
        });

        Map<String, Object> params = new HashMap<>();
        params.put("data", orgDtos);
        params.put("date", DateUtils.instantToString_HCM(Instant.now(), CommonConstants.DATE_TIME.DD_MM_YYYY_HH_MM));
        ByteArrayOutputStream bytes = ExportUtils.genXlsxFromMap(params, TemplateExport.ORG_EXPORT_TEMPLATE.getPath());
        return ExportUtils.toInputStreamResource(bytes);
    }

    @Override
    @Transactional
    public void changeStatusActive(ChangeStatusActiveRequest request) {
        boolean isUpdateAccount = false;
        Instant updatedAt = ZonedDateTime.now(CommonConstants.ZONE.HCM).toInstant();
        try{
            orgRepository.updateStatus(request.getIds(), request.getActive());

            // update job ads status
            jobAdService.updateJobAdStatusByOrgIds(request.getIds(), request.getActive());

            // update accounts status
            restTemplateClient.updateAccountStatusByOrgIds(request);
            isUpdateAccount = true;

            // send notification to org admin
            Map<Long, Organization> orgMap = orgRepository.findAllById(request.getIds()).stream()
                    .collect(Collectors.toMap(Organization::getId, Function.identity()));

            NotifyTemplate template = request.getActive()
                    ? NotifyTemplate.ORG_ACTIVATE_NOTIFICATION
                    : NotifyTemplate.ORG_DEACTIVATE_NOTIFICATION;
            String redirectUrl = request.getActive()
                    ? Constants.Path.HOME_ORG
                    : Constants.Path.HOME;
            Long senderId = WebUtils.getCurrentUserId();

            for (Organization org : orgMap.values()) {
                List<UserDto> orgAdmins = restTemplateClient
                        .getUserByRoleCodeOrg(Constants.RoleCode.ORG_ADMIN, org.getId());
                if (CollectionUtils.isEmpty(orgAdmins)) {
                    continue;
                }
                NotificationDto notifyDto = NotificationDto.builder()
                        .title(template.getTitle())
                        .message(String.format(template.getMessage(), org.getName()))
                        .type(Constants.NotificationType.USER)
                        .redirectUrl(redirectUrl)
                        .senderId(senderId)
                        .receiverIds(orgAdmins.stream().map(UserDto::getId).toList())
                        .receiverType(MemberType.ORGANIZATION.getName())
                        .build();
                kafkaUtils.sendWithJson(Constants.KafkaTopic.NOTIFICATION, notifyDto);
            }
        } catch (Exception exception){
            FailedRollbackUpdateAccountStatus payload = FailedRollbackUpdateAccountStatus.builder()
                    .orgIds(request.getIds())
                    .active(!request.getActive())
                    .updatedAt(updatedAt)
                    .build();
            try{
                if(isUpdateAccount){
                    restTemplateClient.rollbackUpdateAccountStatusByOrgIds(payload);
                }
            } catch (Exception e){
                failedRollbackService.save(
                        FailedRollbackDto.builder()
                                .type(FailedRollbackType.UPDATE_ACCOUNT_STATUS.getType())
                                .payload(ObjectMapperUtils.convertToJson(payload))
                                .errorMessage(e.getMessage())
                                .status(false)
                                .retryCount(0)
                                .build()
                );
            } finally {
                throw exception;
            }
        }
    }

    @Override
    public OrgDto getOrgDetails(Long orgId) {
        return this.getOrgInfoOutside(orgId);
    }

    @Override
    @Transactional
    public void deleteOrg(FailedRollbackOrgCreation payload) {
        Organization org = orgRepository.findById(payload.getOrgId()).orElse(null);
        if(ObjectUtils.isEmpty(org)) {
            return;
        }
        orgRepository.delete(org);

        // delete attach files
        List<Long> attachFileIds = new ArrayList<>();
        if(org.getLogoId() != null) {
            attachFileIds.add(org.getLogoId());
        }
        if(org.getCoverPhotoId() != null) {
            attachFileIds.add(org.getCoverPhotoId());
        }
        if(!attachFileIds.isEmpty()) {
            attachFileService.deleteByIds(attachFileIds);
        }

        // delete org industries -- on delete cascade in db
        // delete org addresses -- on delete cascade in db
    }
}
