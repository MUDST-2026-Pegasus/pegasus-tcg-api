package com.pegasus.pegasustcgapi.service.user;

import com.pegasus.pegasustcgapi.model.audit.AuditAction;
import com.pegasus.pegasustcgapi.service.audit.AuditLogService;
import com.pegasus.pegasustcgapi.service.auth.AuthTokenService;

import com.pegasus.pegasustcgapi.common.EmailMask;
import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.common.Paging;
import com.pegasus.pegasustcgapi.common.csv.CsvExport;
import com.pegasus.pegasustcgapi.dto.user.UserResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.auth.AuthUser;
import com.pegasus.pegasustcgapi.model.user.RoleCode;
import com.pegasus.pegasustcgapi.model.auth.TokenPurpose;
import com.pegasus.pegasustcgapi.model.user.UserStatus;
import com.pegasus.pegasustcgapi.repository.user.RoleRepository;
import com.pegasus.pegasustcgapi.repository.user.UserRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin view of accounts: find them, read one, and suspend or restore it. */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] EXPORT_COLUMNS = {
            "user_id", "username", "display_name", "masked_email", "roles", "status", "created_at"};

    private final UserRepository users;
    private final RoleRepository roles;
    private final AuthTokenService authTokens;
    private final StorageService storage;
    private final AuditLogService audit;
    private final Clock clock;

    public AdminUserService(
            UserRepository users, RoleRepository roles, AuthTokenService authTokens, StorageService storage,
            AuditLogService audit, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.authTokens = authTokens;
        this.storage = storage;
        this.audit = audit;
        this.clock = clock;
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

    /**
     * Every account {@link #search} would show for the same filters, newest first, as
     * one CSV. Only what the admin table shows leaves the server: the email is masked,
     * and phone, addresses and bank accounts are never read.
     *
     * @throws com.pegasus.pegasustcgapi.exception.BadRequestException EXPORT_TOO_LARGE above
     *         {@link CsvExport#MAX_ROWS} rows, before any row is read
     */
    @Transactional(readOnly = true)
    public CsvExport export(String text, RoleCode role, UserStatus status, long adminId) {
        long rows = users.countSearch(text, role, status);
        CsvExport.checkRowCount(rows);

        List<AuthUser> found = users.search(text, role, status, CsvExport.MAX_ROWS, 0);
        Map<Long, Set<RoleCode>> rolesById = roles.findCodesByUserIds(found.stream().map(AuthUser::id).toList());

        CsvExport.Builder csv = CsvExport.builder(EXPORT_COLUMNS);
        for (AuthUser user : found) {
            String roleNames = rolesById.getOrDefault(user.id(), Set.of()).stream()
                    .map(RoleCode::name).sorted().collect(Collectors.joining(";"));
            csv.row(user.id(), user.username(), user.displayName(), EmailMask.mask(user.email()),
                    roleNames, user.status().name(), exportTime(user.createdAt()));
        }
        // The search text can be an email or a name, so only whether there was one is logged.
        log.info("Admin {} exported {} users (role={}, status={}, search={})",
                adminId, rows, role, status, text != null && !text.isBlank());

        return csv.build("admin-users-" + LocalDate.now(clock.withZone(ZONE)) + ".csv");
    }

    private static String exportTime(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(ZONE).format(EXPORT_TIME);
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
            audit.record(actingAdminId, AuditAction.USER_STATUS_CHANGED, userId,
                    "Set user #" + userId + " to " + status,
                    AuditLogService.change(user.status().name(), status.name()));
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
        String banner = user.bannerUrl() == null ? null : storage.presignDownload(user.bannerUrl());
        return UserResponse.from(user, avatar, banner);
    }
}
