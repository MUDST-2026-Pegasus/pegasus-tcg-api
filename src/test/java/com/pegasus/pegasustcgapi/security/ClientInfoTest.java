package com.pegasus.pegasustcgapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("ClientInfo")
class ClientInfoTest {

    @Test
    @DisplayName("takes the user agent and the socket address of a direct call")
    void readsDirectRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "Mozilla/5.0");
        request.setRemoteAddr("198.51.100.9");

        ClientInfo info = ClientInfo.from(request);

        assertThat(info.userAgent()).isEqualTo("Mozilla/5.0");
        assertThat(info.ip()).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("takes the first hop of X-Forwarded-For behind a proxy")
    void prefersFirstForwardedHop() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", " 203.0.113.7 , 70.41.3.18, 150.172.238.178");
        request.setRemoteAddr("10.0.0.1");

        assertThat(ClientInfo.from(request).ip()).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("falls back to the socket address when the proxy header is blank")
    void ignoresBlankForwardedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "   ");
        request.setRemoteAddr("198.51.100.9");

        assertThat(ClientInfo.from(request).ip()).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("stores null rather than an empty string for a missing user agent")
    void nullsMissingUserAgent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");

        assertThat(ClientInfo.from(request).userAgent()).isNull();
    }

    @Test
    @DisplayName("truncates values to what the columns hold")
    void truncatesOversizedValues() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "u".repeat(300));
        request.addHeader("X-Forwarded-For", "1".repeat(60));

        ClientInfo info = ClientInfo.from(request);

        assertThat(info.userAgent()).hasSize(255);
        assertThat(info.ip()).hasSize(45);
    }

    @Test
    @DisplayName("has an empty form for calls that come from no client at all")
    void unknownHasNoValues() {
        assertThat(ClientInfo.unknown().userAgent()).isNull();
        assertThat(ClientInfo.unknown().ip()).isNull();
    }
}
