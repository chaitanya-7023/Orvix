package com.orvix.controller;

import com.orvix.service.ExecutionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

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

    @GetMapping("/{name}/port")
    public ResponseEntity<Integer> getProjectPort(@PathVariable String name) {
        Integer port = executionService.getActivePort(name);
        if (port == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(port);
    }

    @GetMapping("/{name}/health")
    public ResponseEntity<Map<String, Object>> checkHealth(@PathVariable String name) {
        Integer port = executionService.getActivePort(name);
        if (port == null) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + port + "/actuator/health").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            if (code == 200) {
                return ResponseEntity.ok(Map.of("status", "UP", "statusCode", code));
            } else {
                return ResponseEntity.ok(Map.of("status", "DOWN", "statusCode", code));
            }
        } catch (Exception e) {
            // Actuator might not be present, check root URL
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + port + "/").openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                return ResponseEntity.ok(Map.of("status", "UP", "statusCode", code, "message", "Root endpoint responded"));
            } catch (Exception ex) {
                return ResponseEntity.ok(Map.of("status", "OFFLINE", "error", ex.getMessage()));
            }
        }
    }
}
