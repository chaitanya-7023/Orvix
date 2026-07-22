package com.devflow.controller;

import com.devflow.service.ExecutionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/projects")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @GetMapping(value = "/{name}/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runProject(@PathVariable String name) {
        // SSE timeout of 10 minutes for running projects
        SseEmitter emitter = new SseEmitter(600_000L);
        executionService.runProject(name, emitter);
        return emitter;
    }

    @PostMapping("/{name}/stop")
    public ResponseEntity<Void> stopProject(@PathVariable String name) {
        executionService.stopProject(name);
        return ResponseEntity.ok().build();
    }
}
