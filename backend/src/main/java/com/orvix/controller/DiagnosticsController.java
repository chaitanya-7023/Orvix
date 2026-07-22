package com.orvix.controller;

import com.orvix.service.DiagnosticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class DiagnosticsController {

    private final DiagnosticsService diagnosticsService;

    public DiagnosticsController(DiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @PostMapping("/{name}/diagnose")
    public ResponseEntity<List<DiagnosticsService.DiagnosticMarker>> diagnoseFile(
            @PathVariable String name,
            @RequestParam String path,
            @RequestBody String content) {
        return ResponseEntity.ok(diagnosticsService.diagnoseFile(name, path, content));
    }
}

