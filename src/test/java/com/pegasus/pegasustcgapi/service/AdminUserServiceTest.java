package com.pegasus.pegasustcgapi.service;

import static com.pegasus.pegasustcgapi.support.AuthUserBuilder.anActiveUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.common.PageResponse;
import com.pegasus.pegasustcgapi.dto.UserResponse;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.ForbiddenException;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.RoleCode;
import com.pegasus.pegasustcgapi.model.TokenPurpose;
import com.pegasus.pegasustcgapi.model.UserStatus;
import com.pegasus.pegasustcgapi.repository.RoleRepository;
import com.pegasus.pegasustcgapi.repository.UserRepository;
import com.pegasus.pegasustcgapi.storage.StorageService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserService")
class AdminUserServiceTest {

    private static final long USER_ID = 42L;
    private static final long ADMIN_ID = 9L;

    @Mock
    private UserRepository users;

    @Mock
    private RoleRepository roles;

    @Mock
    private AuthTokenService authTokens;

    @Mock
    private StorageService storage;

    @InjectMocks
    private AdminUserService service;

    @Test
    @DisplayName("suspending revokes every refresh token the user holds")
    void suspendRevokesRefreshTokens() {
        givenUser(UserStatus.ACTIVE);

        UserResponse response = service.changeStatus(USER_ID, UserStatus.SUSPENDED, ADMIN_ID);

        verify(users).updateStatus(USER_ID, UserStatus.SUSPENDED);
        verify(authTokens).revokeAll(USER_ID, TokenPurpose.REFRESH);
        assertThat(response.id()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("closing an account revokes its tokens too")
    void deactivateRevokesRefreshTokens() {
        givenUser(UserStatus.ACTIVE);

        service.changeStatus(USER_ID, UserStatus.DEACTIVATED, ADMIN_ID);

        verify(users).updateStatus(USER_ID, UserStatus.DEACTIVATED);
        verify(authTokens).revokeAll(USER_ID, TokenPurpose.REFRESH);
    }

    @Test
    @DisplayName("restoring an account leaves tokens alone")
    void restoreDoesNotRevoke() {
        givenUser(UserStatus.SUSPENDED);

        service.changeStatus(USER_ID, UserStatus.ACTIVE, ADMIN_ID);

        verify(users).updateStatus(USER_ID, UserStatus.ACTIVE);
        verify(authTokens, never()).revokeAll(anyLong(), any());
    }

    @Test
    @DisplayName("setting the status an account already has changes nothing")
    void sameStatusIsNoOp() {
        givenUser(UserStatus.SUSPENDED);

        service.changeStatus(USER_ID, UserStatus.SUSPENDED, ADMIN_ID);

        verify(users, never()).updateStatus(anyLong(), any());
        verify(authTokens, never()).revokeAll(anyLong(), any());
    }

    @Test
    @DisplayName("an admin cannot change their own status")
    void cannotChangeOwnStatus() {
        assertThatThrownBy(() -> service.changeStatus(ADMIN_ID, UserStatus.SUSPENDED, ADMIN_ID))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));

        verify(users, never()).updateStatus(anyLong(), any());
        verify(authTokens, never()).revokeAll(anyLong(), any());
    }

    @Test
    @DisplayName("reports an unknown user as not found")
    void unknownUser() {
        given(users.findById(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(USER_ID, UserStatus.SUSPENDED, ADMIN_ID))
                .isInstanceOfSatisfying(NotFoundException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("search attaches each user's roles from a single batch lookup")
    void searchAttachesRoles() {
        given(users.search("ploy", RoleCode.SELLER, UserStatus.ACTIVE, 10, 20))
                .willReturn(List.of(anActiveUser().withId(1L).build(), anActiveUser().withId(2L).build()));
        given(roles.findCodesByUserIds(List.of(1L, 2L)))
                .willReturn(Map.of(1L, Set.of(RoleCode.BUYER, RoleCode.SELLER)));
        given(users.countSearch("ploy", RoleCode.SELLER, UserStatus.ACTIVE)).willReturn(31L);

        PageResponse<UserResponse> page = service.search("ploy", RoleCode.SELLER, UserStatus.ACTIVE, 2, 10);

        assertThat(page.items()).extracting(UserResponse::roles)
                .containsExactly(Set.of(RoleCode.BUYER, RoleCode.SELLER), Set.of());
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalItems()).isEqualTo(31L);
        assertThat(page.totalPages()).isEqualTo(4);
    }

    private void givenUser(UserStatus status) {
        given(users.findById(USER_ID)).willReturn(Optional.of(anActiveUser().withId(USER_ID).withStatus(status).build()));
        given(roles.findCodesByUserId(USER_ID)).willReturn(Set.of(RoleCode.BUYER));
    }
}
