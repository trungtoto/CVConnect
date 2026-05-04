package com.cvconnect.service;

import com.cvconnect.dto.attachFile.AttachFileDto;
import com.cvconnect.dto.attachFile.DownloadFileDto;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AttachFileService {
    List<Long> uploadFile(MultipartFile[] files);
    List<AttachFileDto> getAttachFiles(List<Long> ids);
    String getDownloadUrl(Long id);
    DownloadFileDto download(Long id);
    void deleteByIds(List<Long> ids);
}
