package com.saburi.travel;

import com.saburi.travel.service.AuthService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class AuthServicePasswordHashTest {
    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = AuthService.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    @Test void newPasswordsUseSaltedPbkdf2AndRejectWrongPassword() throws Exception {
        String one = (String) invoke("hash", new Class<?>[]{String.class}, "correct-horse-battery");
        String two = (String) invoke("hash", new Class<?>[]{String.class}, "correct-horse-battery");
        assertTrue(one.startsWith("pbkdf2$"));
        assertNotEquals(one, two, "each password hash must use a random salt");
        assertTrue((Boolean) invoke("passwordMatches", new Class<?>[]{String.class, String.class}, one, "correct-horse-battery"));
        assertFalse((Boolean) invoke("passwordMatches", new Class<?>[]{String.class, String.class}, one, "wrong-password"));
    }

    @Test void legacySha256PasswordCanBeVerifiedForMigration() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest("legacy-password".getBytes(StandardCharsets.UTF_8));
        String legacy = HexFormat.of().formatHex(digest);
        assertTrue((Boolean) invoke("passwordMatches", new Class<?>[]{String.class, String.class}, legacy, "legacy-password"));
        assertFalse((Boolean) invoke("passwordMatches", new Class<?>[]{String.class, String.class}, legacy, "incorrect"));
    }
}
