package com.pegasus.pegasustcgapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.Address;
import com.pegasus.pegasustcgapi.repository.AddressRepository;
import com.pegasus.pegasustcgapi.repository.AddressRepository.AddressFields;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The buyer's address book [RQ-11, CR-4 US-18]: every call is scoped to one user,
 * the first address becomes the default, and marking a new default clears the old one.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AddressService")
class AddressServiceTest {

    private static final long USER_ID = 42L;
    private static final long OTHER_USER_ID = 7L;

    @Mock
    private AddressRepository addresses;

    @InjectMocks
    private AddressService addressService;

    private static AddressFields fields(boolean defaultShipping, boolean defaultBilling) {
        return new AddressFields("บ้าน", "Somchai Jaidee", "0812345678", "99/1 Sukhumvit Rd", null,
                "Khlong Toei", "Khlong Toei", "Bangkok", "10110", "TH", defaultShipping, defaultBilling);
    }

    private static Address address(long id, long userId, boolean defaultShipping, boolean defaultBilling) {
        return new Address(id, userId, "บ้าน", "Somchai Jaidee", "0812345678", "99/1 Sukhumvit Rd", null,
                "Khlong Toei", "Khlong Toei", "Bangkok", "10110", "TH",
                defaultShipping, defaultBilling, OffsetDateTime.now());
    }

    @Nested
    @DisplayName("Reading")
    class Reading {

        @Test
        @DisplayName("list returns only the caller's addresses")
        void listReturnsCallersAddresses() {
            given(addresses.findByUserId(USER_ID)).willReturn(List.of(address(1L, USER_ID, true, true)));

            List<Address> result = addressService.list(USER_ID);

            assertThat(result).singleElement().satisfies(a -> assertThat(a.userId()).isEqualTo(USER_ID));
        }

        @Test
        @DisplayName("get of another user's address id reads as ADDRESS_NOT_FOUND")
        void getOfAnotherUsersAddressIsNotFound() {
            given(addresses.findByIdAndUserId(5L, OTHER_USER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> addressService.get(OTHER_USER_ID, 5L))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ADDRESS_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("Creating")
    class Creating {

        @Test
        @DisplayName("the first address is saved as default shipping and billing even when not asked")
        void firstAddressBecomesDefault() {
            given(addresses.hasAny(USER_ID)).willReturn(false);
            given(addresses.insert(eq(USER_ID), any())).willReturn(10L);
            given(addresses.findByIdAndUserId(10L, USER_ID)).willReturn(Optional.of(address(10L, USER_ID, true, true)));

            Address created = addressService.create(USER_ID, fields(false, false));

            ArgumentCaptor<AddressFields> saved = ArgumentCaptor.forClass(AddressFields.class);
            verify(addresses).insert(eq(USER_ID), saved.capture());
            assertThat(saved.getValue().defaultShipping()).isTrue();
            assertThat(saved.getValue().defaultBilling()).isTrue();
            verify(addresses).clearDefaultShipping(USER_ID, 10L);
            verify(addresses).clearDefaultBilling(USER_ID, 10L);
            assertThat(created.id()).isEqualTo(10L);
        }

        @Test
        @DisplayName("a later non-default address is saved as typed and leaves the current default alone")
        void laterNonDefaultAddressKeepsExistingDefault() {
            given(addresses.hasAny(USER_ID)).willReturn(true);
            given(addresses.insert(eq(USER_ID), any())).willReturn(11L);
            given(addresses.findByIdAndUserId(11L, USER_ID)).willReturn(Optional.of(address(11L, USER_ID, false, false)));

            addressService.create(USER_ID, fields(false, false));

            ArgumentCaptor<AddressFields> saved = ArgumentCaptor.forClass(AddressFields.class);
            verify(addresses).insert(eq(USER_ID), saved.capture());
            assertThat(saved.getValue().defaultShipping()).isFalse();
            assertThat(saved.getValue().defaultBilling()).isFalse();
            verify(addresses, never()).clearDefaultShipping(anyLong(), anyLong());
            verify(addresses, never()).clearDefaultBilling(anyLong(), anyLong());
        }

        @Test
        @DisplayName("a new default shipping address unmarks the previous one, but not the billing default")
        void newDefaultShippingClearsPreviousDefault() {
            given(addresses.hasAny(USER_ID)).willReturn(true);
            given(addresses.insert(eq(USER_ID), any())).willReturn(12L);
            given(addresses.findByIdAndUserId(12L, USER_ID)).willReturn(Optional.of(address(12L, USER_ID, true, false)));

            Address created = addressService.create(USER_ID, fields(true, false));

            verify(addresses).clearDefaultShipping(USER_ID, 12L);
            verify(addresses, never()).clearDefaultBilling(anyLong(), anyLong());
            assertThat(created.defaultShipping()).isTrue();
        }
    }

    @Nested
    @DisplayName("Updating and deleting")
    class UpdatingAndDeleting {

        @Test
        @DisplayName("update of an owned address applies the new default flag and returns the stored row")
        void updateOwnedAddress() {
            given(addresses.update(eq(3L), eq(USER_ID), any())).willReturn(true);
            given(addresses.findByIdAndUserId(3L, USER_ID)).willReturn(Optional.of(address(3L, USER_ID, false, true)));

            Address updated = addressService.update(USER_ID, 3L, fields(false, true));

            verify(addresses).clearDefaultBilling(USER_ID, 3L);
            verify(addresses, never()).clearDefaultShipping(anyLong(), anyLong());
            assertThat(updated.defaultBilling()).isTrue();
        }

        @Test
        @DisplayName("update of someone else's address is ADDRESS_NOT_FOUND and touches no defaults")
        void updateOfAnotherUsersAddressIsNotFound() {
            given(addresses.update(eq(3L), eq(OTHER_USER_ID), any())).willReturn(false);

            assertThatThrownBy(() -> addressService.update(OTHER_USER_ID, 3L, fields(true, true)))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ADDRESS_NOT_FOUND));
            verify(addresses, never()).clearDefaultShipping(anyLong(), anyLong());
            verify(addresses, never()).clearDefaultBilling(anyLong(), anyLong());
        }

        @Test
        @DisplayName("delete is a soft delete scoped to the owner")
        void deleteOwnedAddressSoftDeletes() {
            given(addresses.softDelete(3L, USER_ID)).willReturn(true);

            addressService.delete(USER_ID, 3L);

            verify(addresses).softDelete(3L, USER_ID);
        }

        @Test
        @DisplayName("delete of someone else's address is ADDRESS_NOT_FOUND")
        void deleteOfAnotherUsersAddressIsNotFound() {
            given(addresses.softDelete(3L, OTHER_USER_ID)).willReturn(false);

            assertThatThrownBy(() -> addressService.delete(OTHER_USER_ID, 3L))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ADDRESS_NOT_FOUND));
        }
    }
}
