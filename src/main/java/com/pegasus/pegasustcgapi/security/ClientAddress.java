package com.pegasus.pegasustcgapi.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The address a rate limit counts a request against.
 *
 * <p>Behind our own proxy the caller is the last {@code X-Forwarded-For} hop — the
 * one that proxy added — so a client cannot pick a fresh one per request by sending
 * its own header. That only holds when the request really did come through the
 * proxy, though. A caller that reaches the app directly would write that last hop
 * itself, so the header is read only when the connection comes from a private or
 * loopback address, which is where an in-cluster proxy or ingress connects from.
 * From anywhere else the connection's own address is the answer.
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    public static String of(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !isInternal(peer)) {
            return peer;
        }
        String[] hops = forwarded.split(",");
        String last = hops[hops.length - 1].trim();
        return last.isEmpty() ? peer : last;
    }

    /** Loopback, RFC 1918, link-local, carrier-grade NAT and IPv6 unique-local addresses. */
    static boolean isInternal(String address) {
        if (address == null || address.isBlank() || !isIpLiteral(address)) {
            return false;
        }
        try {
            InetAddress ip = InetAddress.getByName(address);
            if (ip.isLoopbackAddress() || ip.isSiteLocalAddress() || ip.isLinkLocalAddress()) {
                return true;
            }
            byte[] bytes = ip.getAddress();
            if (bytes.length == 4) {
                // 100.64.0.0/10
                return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 0x40;
            }
            // fc00::/7
            return (bytes[0] & 0xFE) == 0xFC;
        } catch (UnknownHostException notAnAddress) {
            return false;
        }
    }

    /** Never resolve a host name here: only a literal address is ever judged. */
    private static boolean isIpLiteral(String value) {
        return value.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == '.' || c == ':');
    }
}
