package com.predatorfx.ytdlpweb.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCredentialTest {

    // Made up for this test. The real admin credential never appears anywhere in the repo.
    private static final String USER = "TestAdmin";
    private static final String PASS = "S3cret-pass!";
    private static final byte[] SALT = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    /** Printed by the python one-liner in the admin docs for USER/PASS with SALT and 1000 iterations. */
    private static final String PYTHON_HASH =
            "pbkdf2-sha256$1000$AAECAwQFBgcICQoLDA0ODw==$qKL6v25LIKaeMqK085jq/7ywj5yWduUhyJJ8M8bqrjM=";

    private static AdminCredential cred() {
        return AdminCredential.parse(PYTHON_HASH).orElseThrow();
    }

    @Test
    void matchesExactCredentials() {
        assertTrue(cred().matches(USER, PASS));
    }

    @Test
    void rejectsOtherCaseUsername() {
        assertFalse(cred().matches("testadmin", PASS));
        assertFalse(cred().matches("TESTADMIN", PASS));
    }

    @Test
    void rejectsOtherCasePassword() {
        assertFalse(cred().matches(USER, "s3cret-pass!"));
        assertFalse(cred().matches(USER, "S3CRET-PASS!"));
    }

    @Test
    void rejectsTrailingSpace() {
        assertFalse(cred().matches(USER + " ", PASS));
        assertFalse(cred().matches(USER, PASS + " "));
    }

    @Test
    void rejectsNulls() {
        assertFalse(cred().matches(null, PASS));
        assertFalse(cred().matches(USER, null));
    }

    /** Java and the documented python command must agree byte for byte, or a hash the owner
     *  generates for a new password would never match. */
    @Test
    void hashesExactlyLikeThePythonCommand() {
        assertEquals(PYTHON_HASH, AdminCredential.hash(USER, PASS, SALT, 1000));
    }

    @Test
    void blankOrMalformedIsEmpty() {
        assertTrue(AdminCredential.parse(null).isEmpty());
        assertTrue(AdminCredential.parse("").isEmpty());
        assertTrue(AdminCredential.parse("   ").isEmpty());
        assertTrue(AdminCredential.parse("x").isEmpty());
        assertTrue(AdminCredential.parse("pbkdf2-sha256$abc$$").isEmpty());
        assertTrue(AdminCredential.parse("md5$1000$AAECAwQFBgcICQoLDA0ODw==$qKL6v25LIKaeMqK085jq/7ywj5yWduUhyJJ8M8bqrjM=").isEmpty());
        assertTrue(AdminCredential.parse("pbkdf2-sha256$0$AAECAwQFBgcICQoLDA0ODw==$qKL6v25LIKaeMqK085jq/7ywj5yWduUhyJJ8M8bqrjM=").isEmpty());
        assertTrue(AdminCredential.parse("pbkdf2-sha256$1000$not base64!$qKL6v25LIKaeMqK085jq/7ywj5yWduUhyJJ8M8bqrjM=").isEmpty());
    }
}
