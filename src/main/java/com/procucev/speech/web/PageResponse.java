package com.procucev.speech.web;

import java.util.List;

public record PageResponse<T>(List<T> content, int page, int totalPages, long totalElements) {
}
