package com.rally;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class Models {
    private Models() {}
    public record User(long id, String username, String email) {}
    public record Option(Long id, @NotBlank @Size(max=100) String text, boolean correct) {}
    public record Question(Long id, @NotBlank @Size(max=300) String text,
        @NotNull @Pattern(regexp="SINGLE|MULTI") String type,
        @Min(5) @Max(120) int timeLimitSec, @Size(max=255) String imageUrl,
        @NotNull @Size(min=2,max=4) List<@NotNull @Valid Option> options) {}
    public record Quiz(Long id, @NotBlank @Size(max=100) String title,
        @Size(max=2000) String description, boolean example,
        @NotNull @Size(min=1,max=50) List<@NotNull @Valid Question> questions) {}
    public record Register(@NotBlank @Pattern(regexp="[a-zA-Z0-9_]{3,30}") String username,
        @NotBlank @Email @Size(max=255) String email, @NotNull @Size(min=8,max=72) String password) {}
    public record Login(@NotBlank @Size(max=255) String identifier, @NotBlank @Size(max=72) String password) {}
    public record HostRequest(@NotNull Long quizId) {}
}
