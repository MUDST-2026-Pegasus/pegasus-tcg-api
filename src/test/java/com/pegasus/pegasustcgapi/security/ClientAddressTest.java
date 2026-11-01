package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressTest {

    @Test
    void behindAnInternalProxyTheLastForwardedHopIsTheCaller() {
        assertThat(ClientAddress.of(request("10.42.0.7", "1.2.3.4, 203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void aDirectCallerFromAPublicAddressIsCountedByItsConnection() {
        assertThat(ClientAddress.of(request("198.51.100.20", "203.0.113.7"))).isEqualTo("198.51.100.20");
    }

    @Test
    void withoutAForwardedHeaderTheConnectionIsTheCaller() {
        assertThat(ClientAddress.of(request("10.42.0.7", null))).isEqualTo("10.42.0.7");
        assertThat(ClientAddress.of(request("10.42.0.7", " , "))).isEqualTo("10.42.0.7");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.1.1", "100.64.0.1", "100.127.255.254", "::1", "fd00::1", "fe80::1"})
    void internalAddresses(String address) {
        assertThat(ClientAddress.isInternal(address)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "172.32.0.1", "100.128.0.1", "203.0.113.7", "2001:db8::1",
            "localhost", "example.com", ""})
    void publicAddressesAndNamesAreNotInternal(String address) {
        assertThat(ClientAddress.isInternal(address)).isFalse();
    }

    private static MockHttpServletRequest request(String peer, String forwarded) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        r.setRemoteAddr(peer);
        if (forwarded != null) r.addHeader("X-Forwarded-For", forwarded);
        return r;
    }
}
