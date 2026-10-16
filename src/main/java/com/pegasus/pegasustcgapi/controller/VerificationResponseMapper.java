package com.pegasus.pegasustcgapi.controller;

import com.pegasus.pegasustcgapi.dto.VerificationResponse;
import com.pegasus.pegasustcgapi.model.SellerVerification;
import com.pegasus.pegasustcgapi.repository.SellerProfileRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class VerificationResponseMapper {
    private final StorageService storageService;
    private final SellerProfileRepository sellerProfiles;

    public VerificationResponseMapper(StorageService storageService, SellerProfileRepository sellerProfiles) {
        this.storageService = storageService;
        this.sellerProfiles = sellerProfiles;
    }

    public VerificationResponse toResponse(SellerVerification v) {
        return toResponse(v, sellerProfiles.avatarKeys(List.of(v.sellerProfileId())));
    }

    /** One avatar lookup for the whole page instead of one per row. */
    public List<VerificationResponse> toResponses(List<SellerVerification> verifications) {
        Map<Long, String> avatars = sellerProfiles.avatarKeys(
                verifications.stream().map(SellerVerification::sellerProfileId).distinct().toList());
        return verifications.stream().map(v -> toResponse(v, avatars)).toList();
    }

    private VerificationResponse toResponse(SellerVerification v, Map<Long, String> avatarKeys) {
        String url = (v.bankBookImageKey() == null || v.bankBookImageKey().isEmpty()) 
                ? null 
                : storageService.presignDownload(v.bankBookImageKey());
        String avatarKey = avatarKeys.get(v.sellerProfileId());
        String avatarUrl = avatarKey == null ? null : storageService.presignDownload(avatarKey);
        return VerificationResponse.from(v, url, avatarUrl);
    }
}
