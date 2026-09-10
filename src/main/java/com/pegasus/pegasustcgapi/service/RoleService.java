package com.pegasus.pegasustcgapi.service;

import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.repository.RoleRepository;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes what a user is allowed to do. Registration grants the
 * self-service roles through here; ADMIN and SUPPORT only ever arrive via
 * {@link #grantAsAdmin}.
 */
@Service
public class RoleService {

    private final RoleRepository roles;
    private final UserRepository users;

    public RoleService(RoleRepository roles, UserRepository users) {
        this.roles = roles;
        this.users = users;
    }

    public Set<RoleCode> rolesOf(long userId) {
        return roles.findCodesByUserId(userId);
    }

    /** @param grantedBy the acting admin, or {@code null} when the user picked the role at sign-up. */
    public void grant(long userId, Set<RoleCode> codes, Long grantedBy) {
        for (RoleCode code : codes) {
            roles.grant(userId, roleId(code), grantedBy);
        }
    }

    @Transactional
    public Set<RoleCode> grantAsAdmin(long userId, RoleCode code, long actingAdminId) {
        requireExistingUser(userId);
        roles.grant(userId, roleId(code), actingAdminId);
        return rolesOf(userId);
    }

    @Transactional
    public Set<RoleCode> revokeAsAdmin(long userId, RoleCode code, long actingAdminId) {
        requireExistingUser(userId);
        // Stops an admin locking themselves out of the only endpoints that can undo it.
        if (code == RoleCode.ADMIN && userId == actingAdminId) {
            throw new ForbiddenException(
                    ErrorCode.ACCESS_DENIED, "An admin cannot revoke their own ADMIN role");
        }
        roles.revoke(userId, roleId(code));
        return rolesOf(userId);
    }

    private void requireExistingUser(long userId) {
        if (users.findById(userId).isEmpty()) {
            throw new NotFoundException(ErrorCode.USER_NOT_FOUND);
        }
    }

    /** Roles are seeded by migration, so a miss means the database is behind the code. */
    private short roleId(RoleCode code) {
        return roles.findIdByCode(code)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.ROLE_NOT_FOUND, "Role " + code + " is not configured"));
    }
}
