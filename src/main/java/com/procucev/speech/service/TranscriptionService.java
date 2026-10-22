package com.procucev.speech.service;

import com.procucev.speech.domain.Transcription;
import com.procucev.speech.repo.TranscriptionRepository;
import com.procucev.speech.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TranscriptionService {

    private final TranscriptionRepository repo;

    public TranscriptionService(TranscriptionRepository repo) {
        this.repo = repo;
    }

    public PageResponse<Transcription> list(String q, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int p = Math.max(page, 0);
        long total = repo.count(q);
        int totalPages = (int) Math.ceil(total / (double) s);
        return new PageResponse<>(repo.findPage(q, s, p * s), p, totalPages, total);
    }

    public Transcription get(Long id) {
        return repo.findById(id).orElseThrow(() -> notFound(id));
    }

    public Transcription create(String title, String text, String language, Integer durationSeconds) {
        return get(repo.insert(title, text, language, durationSeconds));
    }

    public Transcription update(Long id, String title, String text, String language) {
        if (repo.update(id, title, text, language) == 0) throw notFound(id);
        return get(id);
    }

    public void delete(Long id) {
        if (repo.delete(id) == 0) throw notFound(id);
    }

    private static ResponseStatusException notFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription " + id + " not found");
    }
}
