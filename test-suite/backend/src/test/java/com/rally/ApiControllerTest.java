package com.rally;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Black-box tests over real HTTP: status codes, the JSON error format, and image upload and serving. */
@SuppressWarnings({"rawtypes", "unchecked"})
class ApiControllerTest extends SpringTestSupport {

    /** A valid 1 x 1 PNG image. */
    static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aE9sAAAAASUVORK5CYII=");
    static final byte[] JPEG = padded(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
    static final byte[] GIF = padded("GIF89a".getBytes(StandardCharsets.ISO_8859_1));
    static final byte[] WEBP = padded("RIFF\0\0\0\0WEBP".getBytes(StandardCharsets.ISO_8859_1));

    @Autowired TestRestTemplate rest;
    @Autowired ApiController controller;

    static byte[] padded(byte[] header) {
        return Arrays.copyOf(header, 64);
    }

    String token() {
        String name = uniqueName("api");
        Map body = rest.postForObject("/api/auth/register",
            Map.of("username", name, "email", name + "@example.com", "password", PASSWORD), Map.class);
        return (String) body.get("token");
    }

    ResponseEntity<Map> get(String path, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) headers.set(HttpHeaders.AUTHORIZATION, authorization);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    ResponseEntity<Map> postJson(String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    ResponseEntity<Map> upload(byte[] bytes, String filename, String contentType, String token) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new HttpEntity<>(resource, partHeaders));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange("/api/images", HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
    }

    static void assertError(ResponseEntity<Map> response, int status, String code) {
        assertEquals(status, response.getStatusCode().value(), String.valueOf(response.getBody()));
        assertEquals(code, response.getBody().get("code"));
        assertFalse(String.valueOf(response.getBody().get("message")).isBlank());
    }

    // ---------- Authentication and error format ----------

    @Test
    @DisplayName("AUTH-12: protected endpoints return 401 without a valid bearer token")
    void protectedEndpointsNeedToken() {
        assertError(get("/api/quizzes", null), 401, "UNAUTHORIZED");
        assertError(get("/api/quizzes", "Bearer abc"), 401, "UNAUTHORIZED");
        assertError(get("/api/quizzes", "Token xyz"), 401, "UNAUTHORIZED");
        assertEquals(200, get("/api/quizzes", "Bearer " + token()).getStatusCode().value());
    }

    @Test
    @DisplayName("EX-02: example quizzes require signing in")
    void examplesNeedToken() {
        assertError(get("/api/examples", null), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("AUTH-02 and AUTH-05 over HTTP: invalid input is 400 and duplicates are 409")
    void registrationErrorsOverHttp() {
        assertError(postJson("/api/auth/register", Map.of("username", "ab", "email", "ab@example.com", "password", PASSWORD), null),
            400, "INVALID_INPUT");
        String name = uniqueName("twice");
        Map<String, String> body = Map.of("username", name, "email", name + "@example.com", "password", PASSWORD);
        assertEquals(200, postJson("/api/auth/register", body, null).getStatusCode().value());
        assertError(postJson("/api/auth/register", body, null), 409, "CONFLICT");
    }

    @Test
    @DisplayName("QUIZ-03 over HTTP: a 4-second time limit returns 400 INVALID_INPUT naming the field")
    void quizValidationOverHttp() {
        Map<String, Object> question = Map.of("text", "Too fast?", "type", "SINGLE", "timeLimitSec", 4,
            "options", List.of(Map.of("text", "Yes", "correct", true), Map.of("text", "No", "correct", false)));
        ResponseEntity<Map> response = postJson("/api/quizzes", Map.of("title", "Fast", "questions", List.of(question)), token());
        assertError(response, 400, "INVALID_INPUT");
        assertTrue(response.getBody().get("message").toString().contains("timeLimitSec"));
    }

    @Test
    @DisplayName("Malformed JSON returns 400 INVALID_INPUT")
    void malformedJson() {
        assertError(postJson("/api/auth/login", "{not json", null), 400, "INVALID_INPUT");
    }

    @Test
    @DisplayName("CODE-01 over HTTP: hosting an owned quiz returns a six-digit code")
    void hostGameOverHttp() {
        String token = token();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        Map example = (Map) rest.exchange("/api/examples", HttpMethod.GET, new HttpEntity<>(headers), List.class).getBody().get(0);
        Map copy = postJson("/api/examples/" + example.get("id") + "/clone", null, token).getBody();

        ResponseEntity<Map> response = postJson("/api/games", Map.of("quizId", copy.get("id")), token);
        assertEquals(200, response.getStatusCode().value());
        String code = (String) response.getBody().get("code");
        assertTrue(code.matches("\\d{6}"), code);
        games.games.remove(code);
    }

    // ---------- Images ----------

    @Test
    @DisplayName("IMG-01 and IMG-08: a PNG upload returns a UUID URL that serves the same bytes")
    void uploadAndServePng() throws Exception {
        ResponseEntity<Map> response = upload(PNG, "pixel.png", "image/png", token());
        assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
        String url = (String) response.getBody().get("url");
        assertTrue(url.matches("/images/[0-9a-f-]{36}\\.png"), url);
        assertTrue(Files.exists(Path.of("./target/test-images").resolve(url.substring("/images/".length()))));

        ResponseEntity<byte[]> served = rest.getForEntity(url, byte[].class);
        assertEquals(200, served.getStatusCode().value());
        assertArrayEquals(PNG, served.getBody());
    }

    @Test
    @DisplayName("IMG-02: JPEG, GIF, and WebP uploads get matching extensions")
    void otherImageFormats() {
        String token = token();
        assertTrue(((String) upload(JPEG, "a.jpg", "image/jpeg", token).getBody().get("url")).endsWith(".jpg"));
        assertTrue(((String) upload(GIF, "a.gif", "image/gif", token).getBody().get("url")).endsWith(".gif"));
        assertTrue(((String) upload(WEBP, "a.webp", "image/webp", token).getBody().get("url")).endsWith(".webp"));
    }

    @Test
    @DisplayName("IMG-03 to IMG-05: mismatched types, fake images, and tiny files are rejected")
    void invalidImagesRejected() {
        String token = token();
        assertError(upload(PNG, "pixel.jpg", "image/jpeg", token), 400, "INVALID_INPUT");
        assertError(upload("not a real image, just text".getBytes(StandardCharsets.UTF_8), "photo.png", "image/png", token), 400, "INVALID_INPUT");
        assertError(upload(Arrays.copyOf(PNG, 11), "tiny.png", "image/png", token), 400, "INVALID_INPUT");
        assertError(upload(new byte[0], "empty.png", "image/png", token), 400, "INVALID_INPUT");
    }

    @Test
    @DisplayName("IMG-06: exactly 5 MB is accepted and one byte more is rejected")
    void imageSizeLimit() throws Exception {
        String bearer = "Bearer " + token();
        byte[] fiveMb = Arrays.copyOf(PNG, 5 * 1024 * 1024);
        Map accepted = (Map) controller.upload(new MockMultipartFile("file", "max.png", "image/png", fiveMb), bearer);
        assertTrue(accepted.get("url").toString().endsWith(".png"));

        byte[] tooBig = Arrays.copyOf(PNG, 5 * 1024 * 1024 + 1);
        ApiException error = assertThrows(ApiException.class,
            () -> controller.upload(new MockMultipartFile("file", "big.png", "image/png", tooBig), bearer));
        assertEquals(400, error.status);
    }

    @Test
    @DisplayName("IMG-07: uploading requires signing in")
    void uploadNeedsToken() {
        assertError(upload(PNG, "pixel.png", "image/png", null), 401, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("IMG-09: missing images return 404 and path traversal reads nothing outside the folder")
    void missingAndTraversal() {
        assertEquals(404, rest.getForEntity("/images/missing.png", String.class).getStatusCode().value());
        ResponseEntity<String> traversal = rest.getForEntity(
            URI.create(rest.getRootUri() + "/images/..%2Fapplication.properties"), String.class);
        assertTrue(traversal.getStatusCode().is4xxClientError(), traversal.getStatusCode().toString());
        assertFalse(String.valueOf(traversal.getBody()).contains("spring.datasource"));
    }
}
