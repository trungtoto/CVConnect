package com.cvconnect.service.impl;

import com.cvconnect.common.RestTemplateClient;
import com.cvconnect.dto.attachFile.AttachFileDto;
import com.cvconnect.dto.candidateInfoApply.CandidateInfoApplyProjection;
import com.cvconnect.dto.career.CareerDto;
import com.cvconnect.dto.dashboard.DateRange;
import com.cvconnect.dto.dashboard.admin.*;
import com.cvconnect.dto.dashboard.org.*;
import com.cvconnect.dto.enums.EliminateReasonEnumDto;
import com.cvconnect.dto.internal.response.UserDto;
import com.cvconnect.dto.jobAd.JobAdDto;
import com.cvconnect.dto.jobAdCandidate.JobAdCandidateProjection;
import com.cvconnect.dto.level.LevelDto;
import com.cvconnect.dto.org.OrgDto;
import com.cvconnect.entity.JobAd;
import com.cvconnect.entity.Organization;
import com.cvconnect.enums.EliminateReasonEnum;
import com.cvconnect.repository.DashboardRepository;
import com.cvconnect.service.AttachFileService;
import com.cvconnect.service.DashboardService;
import nmquan.commonlib.constant.CommonConstants;
import nmquan.commonlib.dto.response.FilterResponse;
import nmquan.commonlib.utils.PageUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DashboardServiceImpl implements DashboardService {
    @Autowired
    private DashboardRepository dashboardRepository;
    @Autowired
    private RestTemplateClient restTemplateClient;
    @Autowired
    private AttachFileService attachFileService;

    @Override
    public DashboardOverviewDto getSystemAdminDashboardOverview(DashboardFilter filter) {
        Long numberOfApplications = dashboardRepository.numberOfApplications(filter);
        Long numberOfNewCandidate = restTemplateClient.numberOfNewCandidate(filter);
        Long numberOfNewOrgs = dashboardRepository.numberOfNewOrgs(filter);
        Long numberOfOnboard = dashboardRepository.numberOfOnboard(filter);
        Long numberOfJobAds = dashboardRepository.numberOfJobAds(filter);

        return DashboardOverviewDto.builder()
                .numberOfApplications(numberOfApplications)
                .numberOfNewCandidates(numberOfNewCandidate)
                .numberOfNewOrgs(numberOfNewOrgs)
                .numberOfOnboard(numberOfOnboard)
                .numberOfJobAds(numberOfJobAds)
                .build();
    }

    @Override
    public List<DashboardPercentPassedDto> getPercentPassed(DashboardFilter filter) {
        Map<String, DateRange> dateRangeMap = splitByMonth(filter.getStartTime(), filter.getEndTime());
        List<JobAdCandidateProjection> applyDateData = dashboardRepository.getByApplyDate(filter);
        List<JobAdCandidateProjection> onboardDateData = dashboardRepository.getByOnboard(filter);

        List<DashboardPercentPassedDto> response = new ArrayList<>();
        for(Map.Entry<String, DateRange> entry : dateRangeMap.entrySet()) {
            String key = entry.getKey();
            DateRange dateRange = entry.getValue();

            long applyCount = 0L;
            Long onboardCount = 0L;
            for(JobAdCandidateProjection projection : applyDateData) {
                if(!projection.getApplyDate().isBefore(dateRange.getStartTime()) &&
                   !projection.getApplyDate().isAfter(dateRange.getEndTime())) {
                    applyCount++;
                }
            }
            for(JobAdCandidateProjection projection : onboardDateData) {
                if(!projection.getApplyDate().isBefore(dateRange.getStartTime()) &&
                   !projection.getApplyDate().isAfter(dateRange.getEndTime())) {
                    onboardCount++;
                }
            }
            double percentPassed = applyCount == 0 ? 0.0 : (onboardCount.doubleValue() / (double) applyCount) * 100.0;
            response.add(DashboardPercentPassedDto.builder()
                    .label(key)
                    .numberOfApplications(applyCount)
                    .numberOfPassed(onboardCount)
                    .percent(Math.round(percentPassed * 100.0) / 100.0)
                    .build());
        }
        return response;
    }

    @Override
    public List<DashboardApplyMostDto> getCandidateApplyMost(DashboardFilter filter) {
        List<CandidateInfoApplyProjection> candidateApplies = dashboardRepository.getCandidateApplyMost(filter);

        List<Long> candidateIds = candidateApplies.stream()
                .map(CandidateInfoApplyProjection::getCandidateId)
                .toList();
        Map<Long, UserDto> candidateIdNameMap = restTemplateClient.getUsersByIds(candidateIds);

        List<DashboardApplyMostDto> response = new ArrayList<>();
        for(CandidateInfoApplyProjection projection : candidateApplies) {
            Long candidateId = projection.getCandidateId();
            response.add(DashboardApplyMostDto.builder()
                    .candidateId(candidateId)
                    .candidateName(candidateIdNameMap.get(candidateId) != null ?
                            candidateIdNameMap.get(candidateId).getFullName() : "N/A")
                    .numberOfApplications(projection.getNumOfApply())
                    .build());
        }
        return response;
    }

    @Override
    public List<DashboardEliminatedReasonDto> getPercentEliminatedReason(DashboardFilter filter) {
        List<JobAdCandidateProjection> eliminatedData = dashboardRepository.getEliminatedReasonData(filter)
                .stream()
                .filter(p -> p.getEliminateReasonType() != null)
                .toList();

        int numOfReasons = 0;
        List<DashboardEliminatedReasonDto> response = new ArrayList<>();
        for(JobAdCandidateProjection projection : eliminatedData) {
            EliminateReasonEnumDto reasonEnum = EliminateReasonEnum.getEliminateReasonEnumDto(projection.getEliminateReasonType());
            response.add(DashboardEliminatedReasonDto.builder()
                    .eliminateReason(reasonEnum)
                    .numberOfEliminated(projection.getNumOfApply())
                    .build());
            numOfReasons++;
            if(numOfReasons == 7){
                DashboardEliminatedReasonDto other = new DashboardEliminatedReasonDto();
                other.setEliminateReason(EliminateReasonEnumDto.builder()
                                .description("Các lý do khác")
                        .build());
                other.setNumberOfEliminated(0L);
                for(int i = 7; i < eliminatedData.size(); i++) {
                    projection = eliminatedData.get(i);
                    other.setNumberOfEliminated(other.getNumberOfEliminated() + projection.getNumOfApply());
                }
                if(other.getNumberOfEliminated() > 0) {
                    response.add(other);
                }
                break;
            }
        }
        return response;
    }

    @Override
    public List<DashboardJobAdByTimeDto> getJobAdByTime(DashboardFilter filter) {
        Map<String, DateRange> dateRangeMap = splitByMonth(filter.getStartTime(), filter.getEndTime());
        List<JobAd> jobAds = dashboardRepository.getJobAdByTime(filter);

        List<DashboardJobAdByTimeDto> response = new ArrayList<>();
        for(Map.Entry<String, DateRange> entry : dateRangeMap.entrySet()) {
            String key = entry.getKey();
            DateRange dateRange = entry.getValue();

            Long jobAdCount = 0L;
            for(JobAd jobAd : jobAds) {
                if(!jobAd.getCreatedAt().isBefore(dateRange.getStartTime()) &&
                   !jobAd.getCreatedAt().isAfter(dateRange.getEndTime())) {
                    jobAdCount++;
                }
            }
            response.add(DashboardJobAdByTimeDto.builder()
                    .label(key)
                    .numberOfJobAds(jobAdCount)
                    .build());
        }
        return response;
    }

    @Override
    public List<DashboardJobAdByLevelDto> getJobAdByLevel(DashboardFilter filter) {
        List<Object[]> jobAdByLevelData = dashboardRepository.getJobAdByLevel(filter);
        return jobAdByLevelData.stream()
                .map(p -> {
                    Long levelId = (Long) p[0];
                    String levelName = (String) p[1];
                    Long numOfJobAds = (Long) p[2];
                    return DashboardJobAdByLevelDto.builder()
                            .level(LevelDto.builder()
                                    .id(levelId)
                                    .name(levelName)
                                    .build())
                            .numberOfJobAds(numOfJobAds)
                            .build();
                }).toList();
    }

    @Override
    public List<DashboardJobAdByCareerDto> getJobAdByCareer(DashboardFilter filter) {
        List<Object[]> jobAdByCareerData = dashboardRepository.getJobAdByCareer(filter);
        return jobAdByCareerData.stream()
                .map(p -> {
                    Long careerId = (Long) p[0];
                    String careerName = (String) p[1];
                    Long numOfJobAds = (Long) p[2];
                    double avgSalary = p[3] != null ? ((BigDecimal) p[3]).doubleValue() : 0D;
                    String avgSalaryStr;
                    if(avgSalary == 0) {
                        avgSalaryStr = "N/A";
                    } else {
                        avgSalary = Math.round((avgSalary/1000000) * 10.0) / 10.0;
                        if (avgSalary == 0){
                           avgSalaryStr = "N/A";
                        } else {
                            if(avgSalary % 1 == 0) {
                                avgSalaryStr = String.valueOf((long) avgSalary) + " triệu";
                            } else {
                                avgSalaryStr = avgSalary + " triệu";
                            }
                        }
                    }
                    return DashboardJobAdByCareerDto.builder()
                            .career(CareerDto.builder()
                                    .id(careerId)
                                    .name(careerName)
                                    .build())
                            .numberOfJobAds(numOfJobAds)
                            .avgSalaryStr(avgSalaryStr)
                            .avgSalary(avgSalary)
                            .build();
                }).toList();
    }

    @Override
    public List<JobAdDto> getJobAdFeatured(DashboardFilter filter) {
        filter.setPageIndex(0);
        filter.setPageSize(10);
        if (Objects.equals(filter.getSortBy(), CommonConstants.DEFAULT_SORT_BY) ||
                Objects.equals(filter.getSortBy(), CommonConstants.FALLBACK_SORT_BY)) {
            filter.setSortBy("numberOfApplications");
        }
        Page<Object[]> jobAdFeaturedData = dashboardRepository.getJobAdFeatured(filter, filter.getPageable());
        return jobAdFeaturedData.stream()
                .map(p -> {
                    Long id = ((Number) p[0]).longValue();
                    String title = (String) p[1];
                    Long orgId = ((Number) p[2]).longValue();
                    String orgName = (String) p[3];
                    Long numberOfViews = ((Number) p[4]).longValue();
                    Long numberOfApplications = ((Number) p[5]).longValue();
                    return JobAdDto.builder()
                            .id(id)
                            .title(title)
                            .orgId(orgId)
                            .org(OrgDto.builder()
                                    .id(orgId)
                                    .name(orgName)
                                    .build())
                            .numberOfViews(numberOfViews)
                            .numberOfApplications(numberOfApplications)
                            .build();
                }).collect(Collectors.toList());
    }

    @Override
    public List<DashboardNewOrgByTimeDto> getNewOrgByTime(DashboardFilter filter) {
        Map<String, DateRange> dateRangeMap = splitByMonth(filter.getStartTime(), filter.getEndTime());
        List<Organization> organizations = dashboardRepository.getNewOrgByTime(filter);

        List<DashboardNewOrgByTimeDto> response = new ArrayList<>();
        for(Map.Entry<String, DateRange> entry : dateRangeMap.entrySet()) {
            String key = entry.getKey();
            DateRange dateRange = entry.getValue();

            long numberOfOrgs = 0L;
            for(Organization org : organizations) {
                if(!org.getCreatedAt().isBefore(dateRange.getStartTime()) &&
                        !org.getCreatedAt().isAfter(dateRange.getEndTime())) {
                    numberOfOrgs++;
                }
            }
            response.add(DashboardNewOrgByTimeDto.builder()
                    .label(key)
                    .numberOfOrgs(numberOfOrgs)
                    .build());
        }
        return response;
    }

    @Override
    public List<DashboardOrgStaffSizeDto> getOrgStaffSize() {
        List<Object[]> orgStaffSizeData = dashboardRepository.getOrgStaffSize();
        if (ObjectUtils.isEmpty(orgStaffSizeData)) {
            return Collections.emptyList();
        }
        return orgStaffSizeData.stream()
                .map(p -> {
                    String staffSize = (String) p[0];
                    Long numberOfOrgs = (Long) p[1];
                    return DashboardOrgStaffSizeDto.builder()
                            .staffSize(staffSize)
                            .numberOfOrgs(numberOfOrgs)
                            .build();
                }).toList();
    }

    @Override
    public FilterResponse<DashboardOrgFeaturedDto> getOrgFeatured(DashboardFilter filter) {
        filter.setPageIndex(0);
        filter.setPageSize(10);
        if (Objects.equals(filter.getSortBy(), CommonConstants.DEFAULT_SORT_BY) ||
                Objects.equals(filter.getSortBy(), CommonConstants.FALLBACK_SORT_BY)) {
            filter.setSortBy("numberOfJobAds");
        }
        Page<Object[]> orgFeaturedData = dashboardRepository.getOrgFeatured(filter, filter.getPageable());
        List<DashboardOrgFeaturedDto> orgFeaturedDtos = orgFeaturedData.stream()
                .map(p -> {
                    Long orgId = ((Number) p[0]).longValue();
                    String orgName = (String) p[1];
                    Long orgLogoId = p[2] == null ? null : ((Number) p[2]).longValue();
                    Long numberOfJobAds = p[3] == null ? 0L : ((Number) p[3]).longValue();
                    Long numberOfApplications = p[4] == null ? 0L : ((Number) p[4]).longValue();
                    Long numberOfOnboarded = p[5] == null ? 0L : ((Number) p[5]).longValue();
                    List<AttachFileDto> attachFiles = orgLogoId == null
                            ? Collections.emptyList()
                            : attachFileService.getAttachFiles(List.of(orgLogoId));
                    String orgLogo = null;
                    if(!ObjectUtils.isEmpty(attachFiles)) {
                        if(!ObjectUtils.isEmpty(attachFiles.get(0))) {
                            orgLogo = attachFiles.get(0).getSecureUrl();
                        }
                    }
                    return DashboardOrgFeaturedDto.builder()
                            .orgId(orgId)
                            .orgName(orgName)
                            .orgLogo(orgLogo)
                            .numberOfJobAds(numberOfJobAds)
                            .numberOfApplications(numberOfApplications)
                            .numberOfOnboarded(numberOfOnboarded)
                            .build();
                }).toList();
        return PageUtils.toFilterResponse(orgFeaturedData, orgFeaturedDtos);
    }

    @Override
    public OrgAdminDashboardOverviewDto getOrgAdminDashboardOverview(OrgAdminDashboardFilter filter) {
        Long orgId = restTemplateClient.validOrgMember();
        filter.setOrgId(orgId);

        OrgAdminDashboardOverviewDto response = new OrgAdminDashboardOverviewDto();

        Long totalJobAds = dashboardRepository.numberOfJobAds(filter);
        response.setTotalJobAds(totalJobAds);
        Long totalOpenJobAds = dashboardRepository.numberOfOpenJobAds(filter);
        response.setTotalOpenJobAds(totalOpenJobAds);
        Long totalApplications = dashboardRepository.numberOfApplications(filter);
        response.setTotalApplications(totalApplications);
        Long totalCandidateInProcess = dashboardRepository.numberOfCandidateInProcess(filter);
        response.setTotalCandidateInProcess(totalCandidateInProcess);
        Long numberOfOnboard = dashboardRepository.numberOfOnboard(filter);
        double percentPassed = totalApplications == 0 ? 0.0 : (numberOfOnboard.doubleValue() / (double) totalApplications) * 100.0;
        response.setPercentPassed(Math.round(percentPassed * 100.0) / 100.0);

        // tinh thang truoc neu startTime la ngay dau tien cua thang hien tai
        ZoneId zone = CommonConstants.ZONE.UTC;
        LocalDate startDate = filter.getStartTime().atZone(zone).toLocalDate();
        LocalDate firstDayOfCurrentMonth = LocalDate.now(zone).withDayOfMonth(1);
        boolean isStartOfCurrentMonth = startDate.isEqual(firstDayOfCurrentMonth);
        if (isStartOfCurrentMonth) {
            LocalDate prevStartDate = firstDayOfCurrentMonth.minusMonths(1);
            LocalDate prevEndDate = firstDayOfCurrentMonth.minusDays(1);
            Instant prevStartInstant = prevStartDate.atStartOfDay(zone).toInstant();
            Instant prevEndInstant = prevEndDate.atTime(23, 59, 59).atZone(zone).toInstant();
            filter.setStartTime(prevStartInstant);
            filter.setEndTime(prevEndInstant);

            Long prevTotalJobAds = dashboardRepository.numberOfJobAds(filter);
            response.setIndeTotalJobAds(this.formatInDexChange(totalJobAds, prevTotalJobAds));
            Long prevTotalApplications = dashboardRepository.numberOfApplications(filter);
            response.setIndeTotalApplications(this.formatInDexChange(totalApplications, prevTotalApplications));
            Long prevnNumberOfOnboard = dashboardRepository.numberOfOnboard(filter);
            double prevPercentPassed = prevTotalApplications == 0 ? 0.0 : (prevnNumberOfOnboard.doubleValue() / (double) prevTotalApplications) * 100.0;
            response.setIndePercentPassed(this.formatInDexChange(percentPassed, Math.round(prevPercentPassed * 100.0) / 100.0));
        }

        return response;
    }

    @Override
    public List<DashboardPercentPassedDto> getOrgAdminPercentPassed(OrgAdminDashboardFilter filter) {
        Long orgId = restTemplateClient.validOrgMember();
        filter.setOrgId(orgId);
        Map<String, DateRange> dateRangeMap = splitByMonth(filter.getStartTime(), filter.getEndTime());
        List<JobAdCandidateProjection> applyDateData = dashboardRepository.getByApplyDate(filter);
        List<JobAdCandidateProjection> onboardDateData = dashboardRepository.getByOnboard(filter);

        List<DashboardPercentPassedDto> response = new ArrayList<>();
        for(Map.Entry<String, DateRange> entry : dateRangeMap.entrySet()) {
            String key = entry.getKey();
            DateRange dateRange = entry.getValue();

            long applyCount = 0L;
            Long onboardCount = 0L;
            for(JobAdCandidateProjection projection : applyDateData) {
                if(!projection.getApplyDate().isBefore(dateRange.getStartTime()) &&
                        !projection.getApplyDate().isAfter(dateRange.getEndTime())) {
                    applyCount++;
                }
            }
            for(JobAdCandidateProjection projection : onboardDateData) {
                if(!projection.getApplyDate().isBefore(dateRange.getStartTime()) &&
                        !projection.getApplyDate().isAfter(dateRange.getEndTime())) {
                    onboardCount++;
                }
            }
            double percentPassed = applyCount == 0 ? 0.0 : (onboardCount.doubleValue() / (double) applyCount) * 100.0;
            response.add(DashboardPercentPassedDto.builder()
                    .label(key)
                    .numberOfApplications(applyCount)
                    .numberOfPassed(onboardCount)
                    .percent(Math.round(percentPassed * 100.0) / 100.0)
                    .build());
        }
        return response;
    }

    @Override
    public List<OrgAdminDashboardJobAdByHrDto> getOrgAdminJobAdByHr(OrgAdminDashboardFilter filter) {
        Long orgId = restTemplateClient.validOrgMember();
        filter.setOrgId(orgId);
        List<Object[]> jobAdByHrData = dashboardRepository.getOrgAdminJobAdByHr(filter);
        List<Long> hrIds = jobAdByHrData.stream()
                .map(p -> ((Number) p[0]).longValue())
                .toList();
        Map<Long, UserDto> hrIdNameMap = restTemplateClient.getUsersByIds(hrIds);
        return jobAdByHrData.stream()
                .map(p -> {
                    Long hrId = ((Number) p[0]).longValue();
                    Long jobAdCount = ((Number) p[1]).longValue();
                    Long totalApplications = ((Number) p[2]).longValue();
                    Long totalOnboarded = ((Number) p[3]).longValue();
                    return OrgAdminDashboardJobAdByHrDto.builder()
                            .hrName(hrIdNameMap.get(hrId) != null ?
                                    hrIdNameMap.get(hrId).getFullName() : "N/A")
                            .jobAdCount(jobAdCount)
                            .totalApplications(totalApplications)
                            .totalOnboarded(totalOnboarded)
                            .build();
                }).toList();
    }

    @Override
    public List<OrgAdminDashboardJobAdByDepartmentDto> getOrgAdminJobAdByDepartment(OrgAdminDashboardFilter filter) {
        Long orgId = restTemplateClient.validOrgMember();
        filter.setOrgId(orgId);
        List<Object[]> jobAdByDepartmentData = dashboardRepository.getOrgAdminJobAdByDepartment(filter);
        return jobAdByDepartmentData.stream()
                .map(p -> {
                    String departmentCode = (String) p[0];
                    String departmentName = (String) p[1];
                    Long jobAdCount = ((Number) p[2]).longValue();
                    Long totalOnboarded = ((Number) p[3]).longValue();
                    return OrgAdminDashboardJobAdByDepartmentDto.builder()
                            .departmentCode(departmentCode)
                            .departmentName(departmentName)
                            .jobAdCount(jobAdCount)
                            .totalOnboarded(totalOnboarded)
                            .build();
                }).toList();
    }

    @Override
    public List<OrgAdminDashboardPassByLevelDto> getOrgAdminPassByLevel(OrgAdminDashboardFilter filter) {
        Long orgId = restTemplateClient.validOrgMember();
        filter.setOrgId(orgId);

        List<Object[]> passByLevelData = dashboardRepository.getOrgAdminPassByLevel(filter);
        return passByLevelData.stream()
                .map(p -> {
                    String levelName = (String) p[0];
                    Long totalOnboarded = ((Number) p[1]).longValue();
                    return OrgAdminDashboardPassByLevelDto.builder()
                            .level(levelName)
                            .totalOnboarded(totalOnboarded)
                            .build();
                }).toList();
    }

    @Override
    public List<DashboardEliminatedReasonDto> getOrgAdminPercentEliminatedReason(OrgAdminDashboardFilter filter) {
        Long orgId = restTemplateClient.validOrgMember();
        filter.setOrgId(orgId);
        List<JobAdCandidateProjection> eliminatedData = dashboardRepository.getEliminatedReasonData(filter)
                .stream()
                .filter(p -> p.getEliminateReasonType() != null)
                .toList();

        int numOfReasons = 0;
        List<DashboardEliminatedReasonDto> response = new ArrayList<>();
        for(JobAdCandidateProjection projection : eliminatedData) {
            EliminateReasonEnumDto reasonEnum = EliminateReasonEnum.getEliminateReasonEnumDto(projection.getEliminateReasonType());
            response.add(DashboardEliminatedReasonDto.builder()
                    .eliminateReason(reasonEnum)
                    .numberOfEliminated(projection.getNumOfApply())
                    .build());
            numOfReasons++;
            if(numOfReasons == 7){
                DashboardEliminatedReasonDto other = new DashboardEliminatedReasonDto();
                other.setEliminateReason(EliminateReasonEnumDto.builder()
                        .description("Các lý do khác")
                        .build());
                other.setNumberOfEliminated(0L);
                for(int i = 7; i < eliminatedData.size(); i++) {
                    projection = eliminatedData.get(i);
                    other.setNumberOfEliminated(other.getNumberOfEliminated() + projection.getNumOfApply());
                }
                if(other.getNumberOfEliminated() > 0) {
                    response.add(other);
                }
                break;
            }
        }
        return response;
    }

    @Override
    public List<JobAdDto> getOrgAdminJobAdFeatured(OrgAdminDashboardFilter filter) {
        Long orgId = restTemplateClient.validOrgMember();
        filter.setOrgId(orgId);
        filter.setPageIndex(0);
        filter.setPageSize(10);
        if (Objects.equals(filter.getSortBy(), CommonConstants.DEFAULT_SORT_BY) ||
                Objects.equals(filter.getSortBy(), CommonConstants.FALLBACK_SORT_BY)) {
            filter.setSortBy("numberOfApplications");
        }
        Page<Object[]> jobAdFeaturedData = dashboardRepository.getJobAdFeatured(filter, filter.getPageable());
        return jobAdFeaturedData.stream()
                .map(p -> {
                    Long id = ((Number) p[0]).longValue();
                    String title = (String) p[1];
                    Long numberOfViews = ((Number) p[2]).longValue();
                    Long numberOfApplications = ((Number) p[3]).longValue();
                    return JobAdDto.builder()
                            .id(id)
                            .title(title)
                            .numberOfViews(numberOfViews)
                            .numberOfApplications(numberOfApplications)
                            .build();
                }).collect(Collectors.toList());
    }

    public Map<String, DateRange> splitByMonth(Instant startTime, Instant endTime) {
        Map<String, DateRange> result = new LinkedHashMap<>();

        ZonedDateTime start = startTime.atZone(CommonConstants.ZONE.UTC);
        ZonedDateTime end = endTime.atZone(CommonConstants.ZONE.UTC);
        ZonedDateTime pointer = start.withDayOfMonth(1);
        while (!pointer.isAfter(end)) {
            ZonedDateTime monthStart = pointer;
            ZonedDateTime monthEnd = pointer.plusMonths(1).minusSeconds(1);

            Instant blockStart = monthStart.toInstant().isBefore(startTime) ? startTime : monthStart.toInstant();
            Instant blockEnd   = monthEnd.toInstant().isAfter(endTime) ? endTime : monthEnd.toInstant();

            String key = "T" + pointer.getMonthValue() + "-" + pointer.getYear();

            result.put(key, new DateRange(blockStart, blockEnd));

            pointer = pointer.plusMonths(1);
        }
        return result;
    }

    private String formatInDexChange(Number current, Number previous) {
        if (previous == null || previous.doubleValue() == 0) {
            return null;
        }
        double change = ((current.doubleValue() - previous.doubleValue()) / previous.doubleValue()) * 100.0;
        change = Math.round(change * 100.0) / 100.0;
        return (change >= 0 ? "+" : "") + change + "% so vá»›i thÃ¡ng trÆ°á»›c";
    }

}

