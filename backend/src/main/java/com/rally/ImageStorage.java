package com.rally;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ImageStorage {
    private final JdbcTemplate jdbc;
    private final Path directory;
    private final boolean inDatabase;

    public ImageStorage(JdbcTemplate jdbc, @Value("${app.images}") String directory,
                        @Value("${app.images-in-database}") boolean inDatabase) {
        this.jdbc = jdbc;
        this.directory = Path.of(directory);
        this.inDatabase = inDatabase;
    }

    public void save(String name, String mediaType, byte[] contents) throws IOException {
        if (inDatabase) {
            jdbc.update("INSERT INTO question_images (name, media_type, contents) VALUES (?, ?, ?)",
                name, mediaType, contents);
        } else {
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), contents, StandardOpenOption.CREATE_NEW);
        }
    }
}
