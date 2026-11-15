package com.rally;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "app.images-in-database", havingValue = "true")
public class DatabaseImageController {
    private final JdbcTemplate jdbc;

    public DatabaseImageController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/images/{name}")
    public ResponseEntity<byte[]> image(@PathVariable("name") String name) {
        var images = jdbc.query("SELECT media_type, contents FROM question_images WHERE name = ?",
            (row, index) -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(row.getString("media_type")))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(row.getBytes("contents")), name);
        return images.isEmpty() ? ResponseEntity.notFound().build() : images.getFirst();
    }
}
