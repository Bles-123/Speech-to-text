package com.procucev.speech.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TranscriptionRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank String text,
        @Size(max = 20) String language) {
}
