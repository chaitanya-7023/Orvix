package com.orvix.controller;

import com.orvix.service.AIService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class AIController {

    private final AIService aiService;

    public AIController(AIService aiService) {
        this.aiService = aiService;
    }

    @PostMapping("/{name}/chat")
    public ResponseEntity<String> chat(
            @PathVariable String name,
            @RequestBody ChatRequest request) {
        
        String context = String.format("Project: %%s, Current File: %%s, Selected Code: '%%s' at Line %%d Col %%d", 
                name, request.currentFilePath(),
                request.selectedText() != null ? request.selectedText() : "None",
                request.cursorLine() != null ? request.cursorLine() : 1,
                request.cursorCol() != null ? request.cursorCol() : 1);
        String response = aiService.chatWithRepository(
                request.message(),
                request.currentFileContent(),
                context,
                request.chatHistory()
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{name}/explain-error")
    public ResponseEntity<String> explainError(
            @PathVariable String name,
            @RequestParam String path,
            @RequestParam String codeLine,
            @RequestParam String error) {
        
        String explanation = aiService.explainDiagnostics(path, codeLine, error);
        return ResponseEntity.ok(explanation);
    }

    @PostMapping("/{name}/fix-error")
    public ResponseEntity<String> fixError(
            @PathVariable String name,
            @RequestParam String path,
            @RequestParam int line,
            @RequestParam String error,
            @RequestBody String fileContent) {
        
        String responseJson = aiService.generateFix(path, fileContent, error, line);
        return ResponseEntity.ok(responseJson);
    }

    public static record ChatRequest(
            String message,
            String currentFilePath,
            String currentFileContent,
            String chatHistory,
            String selectedText,
            Integer cursorLine,
            Integer cursorCol
    ) {}
}

