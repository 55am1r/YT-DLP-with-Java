package com.predatorfx.ytdlpweb.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FooterTest {

    @Test
    void customFooterOnlyWhenTheUsernameContainsTheMatch() {
        assertEquals("for the crew", AuthController.footerFor("crew9876x", "9876", "for the crew"));
        assertEquals("for the crew", AuthController.footerFor("9876", "9876", "for the crew"));
        assertNull(AuthController.footerFor("crew", "9876", "for the crew"));
        assertNull(AuthController.footerFor(null, "9876", "for the crew"));
    }

    @Test
    void nothingWhenNotConfigured() {
        assertNull(AuthController.footerFor("crew9876", "", "for the crew"));
        assertNull(AuthController.footerFor("crew9876", "  ", "for the crew"));
        assertNull(AuthController.footerFor("crew9876", null, "for the crew"));
        assertNull(AuthController.footerFor("crew9876", "9876", ""));
        assertNull(AuthController.footerFor("crew9876", "9876", null));
    }
}
