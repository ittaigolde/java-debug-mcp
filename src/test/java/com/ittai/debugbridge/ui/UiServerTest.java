package com.ittai.debugbridge.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UiServerTest {

    @Test
    void loopbackDoesNotRequireToken() {
        assertNull(UiServer.resolveTokenForTest("127.0.0.1", null));
        assertNull(UiServer.resolveTokenForTest("localhost", null));
        assertNull(UiServer.resolveTokenForTest("::1", null));
    }

    @Test
    void remoteHostRequiresToken() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> UiServer.resolveTokenForTest("0.0.0.0", null));
        assertTrue(ex.getMessage().contains("ui token is required"));
    }

    @Test
    void autoGeneratesTokenForRemoteHost() {
        String token = UiServer.resolveTokenForTest("0.0.0.0", "auto");
        assertNotNull(token);
        assertTrue(token.length() >= 24);
    }
}
