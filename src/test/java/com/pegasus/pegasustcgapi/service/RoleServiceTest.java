package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.repository.RoleRepository;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoleService")
class RoleServiceTest {

    private static final long USER_ID = 42L;
    private static final long ADMIN_ID = 9L;
    private static final short BUYER_ROLE_ID = 1;
    private static final short SELLER_ROLE_ID = 2;
    private static final short ADMIN_ROLE_ID = 3;

    @Mock
    private RoleRepository roles;

    @Mock
    private UserRepository users;

    @InjectMocks
    private RoleService service;

    @Test
    @DisplayName("grants every requested role, unattributed at sign-up")
    void grantsRequestedRoles() {
        given(roles.findIdByCode(RoleCode.BUYER)).willReturn(Optional.of(BUYER_ROLE_ID));
        given(roles.findIdByCode(RoleCode.SELLER)).willReturn(Optional.of(SELLER_ROLE_ID));

        service.grant(USER_ID, Set.of(RoleCode.BUYER, RoleCode.SELLER), null);

        verify(roles).grant(USER_ID, BUYER_ROLE_ID, null);
        verify(roles).grant(USER_ID, SELLER_ROLE_ID, null);
    }

    @Test
    @DisplayName("reports a role the database has not been migrated for")
    void rejectsUnconfiguredRole() {
        given(roles.findIdByCode(RoleCode.SELLER)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.grant(USER_ID, Set.of(RoleCode.SELLER), null))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ROLE_NOT_FOUND))
                .hasMessageContaining("SELLER");

        verify(roles, never()).grant(anyLong(), anyShort(), any());
    }

    @Test
    @DisplayName("records which admin granted a role and returns the new set")
    void grantsAsAdmin() {
        given(users.findById(USER_ID)).willReturn(Optional.of(anActiveUser().withId(USER_ID).build()));
        given(roles.findIdByCode(RoleCode.ADMIN)).willReturn(Optional.of(ADMIN_ROLE_ID));
        given(roles.findCodesByUserId(USER_ID)).willReturn(Set.of(RoleCode.BUYER, RoleCode.ADMIN));

        Set<RoleCode> result = service.grantAsAdmin(USER_ID, RoleCode.ADMIN, ADMIN_ID);

        verify(roles).grant(USER_ID, ADMIN_ROLE_ID, ADMIN_ID);
        assertThat(result).containsExactlyInAnyOrder(RoleCode.BUYER, RoleCode.ADMIN);
    }

    @Test
    @DisplayName("refuses to grant a role to an account that does not exist")
    void rejectsGrantForMissingUser() {
        given(users.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantAsAdmin(USER_ID, RoleCode.SELLER, ADMIN_ID))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));

        verify(roles, never()).grant(anyLong(), anyShort(), any());
    }

    @Test
    @DisplayName("revokes a role from another account")
    void revokesAsAdmin() {
        given(users.findById(USER_ID)).willReturn(Optional.of(anActiveUser().withId(USER_ID).build()));
        given(roles.findIdByCode(RoleCode.SELLER)).willReturn(Optional.of(SELLER_ROLE_ID));
        given(roles.findCodesByUserId(USER_ID)).willReturn(Set.of(RoleCode.BUYER));

        Set<RoleCode> result = service.revokeAsAdmin(USER_ID, RoleCode.SELLER, ADMIN_ID);

        verify(roles).revoke(USER_ID, SELLER_ROLE_ID);
        assertThat(result).containsExactly(RoleCode.BUYER);
    }

    @Test
    @DisplayName("stops an admin from revoking their own ADMIN role")
    void rejectsSelfDemotion() {
        given(users.findById(ADMIN_ID)).willReturn(Optional.of(anActiveUser().withId(ADMIN_ID).build()));

        assertThatThrownBy(() -> service.revokeAsAdmin(ADMIN_ID, RoleCode.ADMIN, ADMIN_ID))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));

        verify(roles, never()).revoke(anyLong(), anyShort());
    }

    @Test
    @DisplayName("lets an admin revoke ADMIN from someone else")
    void allowsRevokingAnotherAdmin() {
        given(users.findById(USER_ID)).willReturn(Optional.of(anActiveUser().withId(USER_ID).build()));
        given(roles.findIdByCode(RoleCode.ADMIN)).willReturn(Optional.of(ADMIN_ROLE_ID));
        given(roles.findCodesByUserId(USER_ID)).willReturn(Set.of(RoleCode.BUYER));

        service.revokeAsAdmin(USER_ID, RoleCode.ADMIN, ADMIN_ID);

        verify(roles).revoke(USER_ID, ADMIN_ROLE_ID);
    }

    @Test
    @DisplayName("reads the roles of a user straight through")
    void readsRoles() {
        given(roles.findCodesByUserId(USER_ID)).willReturn(Set.of(RoleCode.BUYER));

        assertThat(service.rolesOf(USER_ID)).containsExactly(RoleCode.BUYER);
    }
}
