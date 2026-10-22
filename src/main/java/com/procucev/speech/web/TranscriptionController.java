package com.procucev.speech.web;

import com.procucev.speech.domain.Transcription;
import com.procucev.speech.service.TranscriptionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transcriptions")
public class TranscriptionController {

    private final TranscriptionService service;

    public TranscriptionController(TranscriptionService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<Transcription> list(@RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return service.list(q, page, size);
    }

    @GetMapping("/{id}")
    public Transcription get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Transcription create(@Valid @RequestBody TranscriptionRequest r) {
        return service.create(r.title(), r.text(), r.language(), null);
    }

    @PutMapping("/{id}")
    public Transcription update(@PathVariable Long id, @Valid @RequestBody TranscriptionRequest r) {
        return service.update(id, r.title(), r.text(), r.language());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
