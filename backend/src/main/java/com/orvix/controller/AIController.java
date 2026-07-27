package com.orvix.controller;

import com.orvix.service.AIService;
import com.orvix.service.FilesystemService;
import com.orvix.service.StaticAnalysisService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;

@RestController
@RequestMapping("/api/projects")
public class AIController {

    private final AIService aiService;
    private final FilesystemService filesystemService;
    private final StaticAnalysisService staticAnalysisService;

    public AIController(AIService aiService, FilesystemService filesystemService, StaticAnalysisService staticAnalysisService) {
        this.aiService = aiService;
        this.filesystemService = filesystemService;
        this.staticAnalysisService = staticAnalysisService;
    }

    private void appendFileTree(File dir, String prefix, StringBuilder sb, int maxDepth, int[] count) {
        if (maxDepth < 0 || count[0] > 60) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().equals(".git") || f.getName().equals("target") || f.getName().equals("node_modules")) {
                continue;
            }
            count[0]++;
            sb.append(prefix).append(f.isDirectory() ? "/" : "").append(f.getName()).append("\n");
            if (f.isDirectory()) {
                appendFileTree(f, prefix + "  ", sb, maxDepth - 1, count);
            }
        }
    }

    @PostMapping("/{name}/chat")
    public ResponseEntity<String> chat(
            @PathVariable String name,
            @RequestBody ChatRequest request) {
        
        File projectDir = filesystemService.getProjectDirectory(name);
        String summaryText = "";
        StringBuilder treeBuilder = new StringBuilder();
        if (projectDir.exists()) {
            summaryText = staticAnalysisService.readAiSummary(projectDir);
            treeBuilder.append("Directory Structure:\n");
            appendFileTree(projectDir, "  ", treeBuilder, 3, new int[]{0});
        }

        String context = String.format("Project: %s, Current File: %s, Selected Code: '%s' at Line %d Col %d\n\nProject Summary:\n%s\n\n%s", 
                name, request.currentFilePath(),
                request.selectedText() != null ? request.selectedText() : "None",
                request.cursorLine() != null ? request.cursorLine() : 1,
                request.cursorCol() != null ? request.cursorCol() : 1,
                summaryText,
                treeBuilder.toString());

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

