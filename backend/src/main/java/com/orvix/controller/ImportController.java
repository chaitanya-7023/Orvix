package com.orvix.controller;

import com.orvix.service.ImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

@RestController
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @GetMapping(value = "/api/projects/import", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter importRepository(@RequestParam String url) {
        // Create an emitter with a 5-minute timeout
        SseEmitter emitter = new SseEmitter(300_000L);

        CompletableFuture.runAsync(() -> {
            importService.importRepository(url, emitter);
        });

        return emitter;
    }
}

