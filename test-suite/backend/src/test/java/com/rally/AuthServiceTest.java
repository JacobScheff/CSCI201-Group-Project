package com.rally;

import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

import static com.rally.Models.*;
import static org.junit.jupiter.api.Assertions.*;

/** Registration, login, input validation, and JWT handling. */
class AuthServiceTest extends SpringTestSupport {

    @Autowired Validator validator;

    boolean invalid(Register request, String field) {
        return validator.validate(request).stream().anyMatch(v -> v.getPropertyPath().toString().equals(field));
    }

    /** Builds a token with the given claims, signed by the given service's secret. */
    String craftToken(Map<String, Object> claims, AuthService signer) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(json.write(claims).getBytes(StandardCharsets.UTF_8));
        String unsigned = header + "." + payload;
        return unsigned + "." + encoder.encodeToString(signer.sign(unsigned));
    }

    Map<String, Object> claims(User user, long expiresInSeconds, String issuer) {
        long now = Instant.now().getEpochSecond();
        return Map.of("sub", Long.toString(user.id()), "iat", now, "exp", now + expiresInSeconds, "iss", issuer);
    }

    @Test
    @DisplayName("AUTH-01: registration lowercases the account, hashes the password with BCrypt, and returns a token")
    void registerHashesPassword() {
        String name = uniqueName("Tommy_Trojan");
        Map<String, Object> session = auth.register(new Register(name, name + "@USC.edu", "FightOn2026"));
        User user = (User) session.get("user");
        assertEquals(name.toLowerCase(Locale.ROOT), user.username());
        assertEquals((name + "@usc.edu").toLowerCase(Locale.ROOT), user.email());
        assertFalse(((String) session.get("token")).isBlank());

        String hash = store.db.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, user.id());
        assertTrue(hash.startsWith("$2a$12$"), hash);
        assertFalse(hash.contains("FightOn2026"));
    }

    @Test
    @DisplayName("AUTH-02: usernames must be 3 to 30 letters, digits, or underscores")
    void usernameRules() {
        assertTrue(invalid(new Register("ab", "a@example.com", PASSWORD), "username"));
        assertFalse(invalid(new Register("abc", "a@example.com", PASSWORD), "username"));
        assertFalse(invalid(new Register("a".repeat(30), "a@example.com", PASSWORD), "username"));
        assertTrue(invalid(new Register("a".repeat(31), "a@example.com", PASSWORD), "username"));
        assertTrue(invalid(new Register("tommy trojan", "a@example.com", PASSWORD), "username"));
    }

    @Test
    @DisplayName("AUTH-03: passwords must be 8 to 72 characters")
    void passwordRules() {
        assertTrue(invalid(new Register("tommy", "a@example.com", "x".repeat(7)), "password"));
        assertFalse(invalid(new Register("tommy", "a@example.com", "x".repeat(8)), "password"));
        assertFalse(invalid(new Register("tommy", "a@example.com", "x".repeat(72)), "password"));
        assertTrue(invalid(new Register("tommy", "a@example.com", "x".repeat(73)), "password"));
    }

    @Test
    @DisplayName("AUTH-04: emails must be well formed and present")
    void emailRules() {
        assertTrue(invalid(new Register("tommy", "not-an-email", PASSWORD), "email"));
        assertTrue(invalid(new Register("tommy", "", PASSWORD), "email"));
        assertFalse(invalid(new Register("tommy", "tommy@usc.edu", PASSWORD), "email"));
    }

    @Test
    @DisplayName("AUTH-05: usernames and emails are unique regardless of case")
    void duplicatesRejected() {
        String name = uniqueName("dup");
        auth.register(new Register(name, name + "@example.com", PASSWORD));
        assertThrows(DataIntegrityViolationException.class,
            () -> auth.register(new Register(name.toUpperCase(Locale.ROOT), uniqueName("other") + "@example.com", PASSWORD)));
        assertThrows(DataIntegrityViolationException.class,
            () -> auth.register(new Register(uniqueName("other"), (name + "@EXAMPLE.com").toUpperCase(Locale.ROOT), PASSWORD)));
    }

    @Test
    @DisplayName("AUTH-06: users can sign in with their username or email in any case")
    void loginByUsernameOrEmail() {
        String name = uniqueName("login");
        auth.register(new Register(name, name + "@example.com", PASSWORD));
        assertNotNull(auth.login(new Login(name.toUpperCase(Locale.ROOT), PASSWORD)).get("token"));
        assertNotNull(auth.login(new Login(name + "@example.com", PASSWORD)).get("token"));
    }

    @Test
    @DisplayName("AUTH-07: a wrong password and an unknown user get the same 401 message")
    void badCredentialsLookIdentical() {
        String name = uniqueName("secret");
        auth.register(new Register(name, name + "@example.com", PASSWORD));
        ApiException wrongPassword = assertThrows(ApiException.class, () -> auth.login(new Login(name, "Wrong-Password-1")));
        ApiException unknownUser = assertThrows(ApiException.class, () -> auth.login(new Login("nobody_" + name, PASSWORD)));
        for (ApiException error : List.of(wrongPassword, unknownUser)) {
            assertEquals(401, error.status);
            assertEquals("BAD_CREDENTIALS", error.code);
        }
        assertEquals(wrongPassword.getMessage(), unknownUser.getMessage());
    }

    @Test
    @DisplayName("AUTH-08: a login password over 72 UTF-8 bytes is rejected")
    void overlongLoginPassword() {
        String name = uniqueName("long");
        auth.register(new Register(name, name + "@example.com", PASSWORD));
        ApiException error = assertThrows(ApiException.class, () -> auth.login(new Login(name, "é".repeat(37))));
        assertEquals(401, error.status);
        assertEquals("BAD_CREDENTIALS", error.code);
    }

    @Test
    @DisplayName("AUTH-09: a freshly issued token identifies its user")
    void tokenRoundTrip() {
        User user = register("token");
        String token = (String) auth.session(user).get("token");
        assertEquals(user, auth.token(token));
        assertEquals(user, auth.token(craftToken(claims(user, 3600, "rally"), auth)), "the crafted control token is valid");
    }

    @Test
    @DisplayName("AUTH-10: an expired token is rejected")
    void expiredToken() {
        User user = register("expired");
        String expired = craftToken(claims(user, -1, "rally"), auth);
        assertEquals(401, assertThrows(ApiException.class, () -> auth.token(expired)).status);
    }

    @Test
    @DisplayName("AUTH-11: tampered, foreign, and malformed tokens are rejected")
    void badTokensRejected() {
        User user = register("tamper");
        String token = (String) auth.session(user).get("token");
        int signatureStart = token.lastIndexOf('.') + 1;
        char flipped = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart) + flipped + token.substring(signatureStart + 1);

        AuthService otherServer = new AuthService(store, json, "a-completely-different-secret-of-40-bytes");
        String foreign = (String) otherServer.session(user).get("token");
        String wrongIssuer = craftToken(claims(user, 3600, "evil"), auth);

        List<String> bad = Arrays.asList(tampered, foreign, wrongIssuer, "abc", null, "a".repeat(4097));
        for (String candidate : bad) {
            ApiException error = assertThrows(ApiException.class, () -> auth.token(candidate), String.valueOf(candidate));
            assertEquals(401, error.status);
            assertEquals("UNAUTHORIZED", error.code);
        }
    }

    @Test
    @DisplayName("AUTH-13: the server refuses to start with a JWT secret shorter than 32 bytes")
    void shortSecretRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AuthService(store, json, "x".repeat(31)));
        assertDoesNotThrow(() -> new AuthService(store, json, "x".repeat(32)));
    }
}
