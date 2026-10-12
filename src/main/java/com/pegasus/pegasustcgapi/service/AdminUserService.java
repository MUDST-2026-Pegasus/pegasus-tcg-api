package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.AuthUser;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.TokenPurpose;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.RoleRepository;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin view of accounts: find them, read one, and suspend or restore it. */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final AuthTokenService authTokens;
    private final StorageService storage;

    public AdminUserService(
            UserRepository users, RoleRepository roles, AuthTokenService authTokens, StorageService storage) {
        this.users = users;
        this.roles = roles;
        this.authTokens = authTokens;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(
            String text, RoleCode role, UserStatus status, int page, int size) {

        Paging paging = Paging.of(page, size);
        List<AuthUser> found = users.search(text, role, status, paging.size(), paging.offset());
        Map<Long, Set<RoleCode>> rolesById = roles.findCodesByUserIds(found.stream().map(AuthUser::id).toList());

        List<UserResponse> items = found.stream()
                .map(user -> toResponse(user.withRoles(rolesById.getOrDefault(user.id(), Set.of()))))
                .toList();
        return PageResponse.of(items, paging.page(), paging.size(), users.countSearch(text, role, status));
    }

    @Transactional(readOnly = true)
    public UserResponse get(long userId) {
        return toResponse(load(userId));
    }

    /**
     * Moves an account to {@code status}. Anything other than ACTIVE also revokes
     * every refresh token the user holds, in the same transaction: otherwise a
     * suspended user keeps minting access tokens until the refresh token expires.
     * Their current access token still runs out on its own (a few minutes).
     */
    @Transactional
    public UserResponse changeStatus(long userId, UserStatus status, long actingAdminId) {
        // Stops an admin locking themselves out of the only endpoints that can undo it.
        if (userId == actingAdminId) {
            throw new ForbiddenException(ErrorCode.ACCESS_DENIED, "An admin cannot change their own status");
        }
        AuthUser user = load(userId);

        if (user.status() != status) {
            users.updateStatus(userId, status);
            if (status != UserStatus.ACTIVE) {
                int revoked = authTokens.revokeAll(userId, TokenPurpose.REFRESH);
                log.info("Admin {} set user {} to {}; revoked {} refresh token(s)",
                        actingAdminId, userId, status, revoked);
            }
        }
        return toResponse(load(userId));
    }

    private AuthUser load(long userId) {
        AuthUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));
        return user.withRoles(roles.findCodesByUserId(userId));
    }

    private UserResponse toResponse(AuthUser user) {
        String avatar = user.avatarUrl() == null ? null : storage.presignDownload(user.avatarUrl());
        return UserResponse.from(user, avatar);
    }
}
