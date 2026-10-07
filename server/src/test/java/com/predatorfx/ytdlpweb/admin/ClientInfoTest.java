package com.predatorfx.ytdlpweb.admin;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientInfoTest {

    @Test
    void tunnelRequestUsesCfConnectingIp() {
        assertEquals(new ClientInfo("49.37.10.20", "internet"),
                ClientInfo.resolve("127.0.0.1", "49.37.10.20", null));
    }

    @Test
    void ipv6LoopbackAlsoTrusted() {
        assertEquals(new ClientInfo("49.37.10.20", "internet"),
                ClientInfo.resolve("0:0:0:0:0:0:0:1", "49.37.10.20", null));
        assertEquals("2401:4900:1c5a::1", ClientInfo.resolve("::1", "2401:4900:1c5a::1", null).ip());
    }

    @Test
    void lanClientCannotSpoofHeader() {
        assertEquals(new ClientInfo("192.168.1.20", "lan"),
                ClientInfo.resolve("192.168.1.20", "8.8.8.8", "8.8.8.8"));
    }

    @Test
    void localBrowserIsLocal() {
        assertEquals(new ClientInfo("127.0.0.1", "local"), ClientInfo.resolve("127.0.0.1", null, null));
    }

    @Test
    void nonIpHeaderIgnored() {
        assertEquals(new ClientInfo("127.0.0.1", "local"), ClientInfo.resolve("127.0.0.1", "evil.example", null));
    }

    @Test
    void forwardedForFirstEntry() {
        assertEquals(new ClientInfo("49.37.10.20", "internet"),
                ClientInfo.resolve("127.0.0.1", null, "49.37.10.20, 172.70.1.1"));
    }

    @Test
    void publicSocketWithoutProxyIsInternet() {
        assertEquals(new ClientInfo("49.37.10.20", "internet"), ClientInfo.resolve("49.37.10.20", null, null));
    }

    @Test
    void ipv4MappedAddressIsUnwrapped() {
        assertEquals(new ClientInfo("192.168.1.20", "lan"), ClientInfo.resolve("::ffff:192.168.1.20", null, null));
    }

    @Test
    void privateRanges() {
        for (String ip : List.of("10.0.0.1", "172.20.1.1", "192.168.1.10", "100.64.0.1", "169.254.1.1",
                "127.0.0.1", "::1", "fd00::1", "fe80::1")) {
            assertTrue(ClientInfo.isPrivate(ip), ip);
        }
        for (String ip : List.of("8.8.8.8", "2001:4860:4860::8888", "172.32.0.1", "100.128.0.1")) {
            assertFalse(ClientInfo.isPrivate(ip), ip);
        }
    }

    @Test
    void ipLiteralCheckNeverAcceptsNames() {
        assertTrue(ClientInfo.isIpLiteral("49.37.10.20"));
        assertTrue(ClientInfo.isIpLiteral("2001:db8::1"));
        assertFalse(ClientInfo.isIpLiteral("localhost"));
        assertFalse(ClientInfo.isIpLiteral("evil.example"));
        assertFalse(ClientInfo.isIpLiteral("999.1.1.1"));
        assertFalse(ClientInfo.isIpLiteral(""));
        assertFalse(ClientInfo.isIpLiteral(null));
    }

    @Test
    void ofReadsTheServletRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("CF-Connecting-IP", "49.37.10.20");
        assertEquals(new ClientInfo("49.37.10.20", "internet"), ClientInfo.of(req));
    }
}
