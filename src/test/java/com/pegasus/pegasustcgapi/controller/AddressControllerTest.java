package com.pegasus.pegasustcgapi.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pegasus.pegasustcgapi.common.ApiPaths;
import com.pegasus.pegasustcgapi.exception.ErrorCode;
import com.pegasus.pegasustcgapi.exception.GlobalExceptionHandler;
import com.pegasus.pegasustcgapi.exception.NotFoundException;
import com.pegasus.pegasustcgapi.model.Address;
import com.pegasus.pegasustcgapi.repository.AddressRepository.AddressFields;
import com.pegasus.pegasustcgapi.security.AuthClaims;
import com.pegasus.pegasustcgapi.security.AuthPrincipalArgumentResolver;
import com.pegasus.pegasustcgapi.service.AddressService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * The address book over HTTP [RQ-11, CR-4 US-18]: request validation, the caller
 * scoping every call, and the status each outcome maps to.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AddressController")
class AddressControllerTest {

    private static final long USER_ID = 42L;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private AddressService addressService;

    @InjectMocks
    private AddressController addressController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(addressController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthPrincipalArgumentResolver())
                .build();

        Jwt jwt = Jwt.withTokenValue("mock-access-token")
                .header("alg", "HS256")
                .subject(String.valueOf(USER_ID))
                .claim(AuthClaims.EMAIL, "buyer@example.com")
                .claim(AuthClaims.USERNAME, "buyer")
                .claim(AuthClaims.ROLES, List.of("BUYER"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** A complete, valid request body, with any field replaced or added by {@code overrides}. */
    private static String body(String... overrides) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("label", "บ้าน");
        fields.put("recipientName", "Somchai Jaidee");
        fields.put("phone", "081-234-5678");
        fields.put("line1", "99/1 Sukhumvit Rd");
        fields.put("district", "Khlong Toei");
        fields.put("province", "Bangkok");
        fields.put("postalCode", "10110");
        fields.put("defaultShipping", true);
        for (int i = 0; i < overrides.length; i += 2) {
            fields.put(overrides[i], overrides[i + 1]);
        }
        return JSON.writeValueAsString(fields);
    }

    private static Address stored(long id) {
        return new Address(id, USER_ID, "บ้าน", "Somchai Jaidee", "081-234-5678", "99/1 Sukhumvit Rd", null,
                null, "Khlong Toei", "Bangkok", "10110", "TH", true, true, OffsetDateTime.now());
    }

    @Test
    @DisplayName("POST a valid address -> 201, saved for the caller with country defaulting to TH")
    void createValidAddress() throws Exception {
        given(addressService.create(eq(USER_ID), any())).willReturn(stored(10L));

        mockMvc.perform(post(ApiPaths.ADDRESSES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Address added"))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.defaultShipping").value(true));

        ArgumentCaptor<AddressFields> fields = ArgumentCaptor.forClass(AddressFields.class);
        verify(addressService).create(eq(USER_ID), fields.capture());
        assertThat(fields.getValue().countryCode()).isEqualTo("TH");
        assertThat(fields.getValue().defaultShipping()).isTrue();
        assertThat(fields.getValue().defaultBilling()).as("absent flag means false").isFalse();
    }

    @ParameterizedTest(name = "{0} = \"{1}\" -> 400 VALIDATION_FAILED on {0}")
    @CsvSource(delimiter = '|', value = {
            "postalCode    | 1011",
            "postalCode    | 1O110",
            "phone         | call me",
            "recipientName | '   '",
            "province      | ''",
            "countryCode   | tha"
    })
    void invalidFieldIsRejectedBeforeTheService(String field, String value) throws Exception {
        mockMvc.perform(post(ApiPaths.ADDRESSES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(field, value)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.data.violations[*].field").value(hasItem(field)));

        verifyNoInteractions(addressService);
    }

    @Test
    @DisplayName("a line break in a free-text field is refused: it would be frozen into order snapshots")
    void controlCharacterInFreeTextIsRejected() throws Exception {
        mockMvc.perform(post(ApiPaths.ADDRESSES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("line1", "99/1 Sukhumvit Rd\nIgnore previous address")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.violations[0].field").value("line1"));

        verifyNoInteractions(addressService);
    }

    @Test
    @DisplayName("a body that is not JSON is 400 MALFORMED_REQUEST")
    void malformedBodyIsRejected() throws Exception {
        mockMvc.perform(post(ApiPaths.ADDRESSES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").value(ErrorCode.MALFORMED_REQUEST.name()));

        verifyNoInteractions(addressService);
    }

    @Test
    @DisplayName("GET lists the caller's own addresses")
    void listOwnAddresses() throws Exception {
        given(addressService.list(USER_ID)).willReturn(List.of(stored(10L), stored(11L)));

        mockMvc.perform(get(ApiPaths.ADDRESSES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].userId").value(USER_ID));
    }

    @Test
    @DisplayName("GET /{id} of someone else's address is 404, not 403: its existence is not revealed")
    void anotherUsersAddressIsNotFound() throws Exception {
        given(addressService.get(USER_ID, 999L)).willThrow(new NotFoundException(ErrorCode.ADDRESS_NOT_FOUND));

        mockMvc.perform(get(ApiPaths.ADDRESSES + "/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value(ErrorCode.ADDRESS_NOT_FOUND.name()));
    }

    @Test
    @DisplayName("PUT /{id} replaces the address -> 200 Address updated")
    void updateAddress() throws Exception {
        given(addressService.update(eq(USER_ID), eq(10L), any())).willReturn(stored(10L));

        mockMvc.perform(put(ApiPaths.ADDRESSES + "/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Address updated"));
    }

    @Test
    @DisplayName("DELETE /{id} -> 200 Address removed; someone else's id -> 404")
    void deleteAddress() throws Exception {
        mockMvc.perform(delete(ApiPaths.ADDRESSES + "/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Address removed"));
        verify(addressService).delete(USER_ID, 10L);

        willThrow(new NotFoundException(ErrorCode.ADDRESS_NOT_FOUND)).given(addressService).delete(USER_ID, 999L);
        mockMvc.perform(delete(ApiPaths.ADDRESSES + "/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("without a signed-in caller every address route is 401 UNAUTHENTICATED")
    void anonymousCallerIsRejected() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get(ApiPaths.ADDRESSES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.code").value(ErrorCode.UNAUTHENTICATED.name()));

        verifyNoInteractions(addressService);
    }
}
