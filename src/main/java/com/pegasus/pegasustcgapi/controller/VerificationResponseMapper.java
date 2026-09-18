package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.dto.VerificationResponse;
import com.pegasus.pegasustcgapi.model.SellerVerification;
import com.pegasus.pegasustcgapi.storage.StorageService;
import org.springframework.stereotype.Component;

@Component
public class VerificationResponseMapper {
    private final StorageService storageService;

    public VerificationResponseMapper(StorageService storageService) {
        this.storageService = storageService;
    }

    public VerificationResponse toResponse(SellerVerification v) {
        String url = (v.bankBookImageKey() == null || v.bankBookImageKey().isEmpty()) 
                ? null 
                : storageService.presignDownload(v.bankBookImageKey());
        return VerificationResponse.from(v, url);
    }
}
