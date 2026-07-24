package com.orvix.controller;

import com.orvix.service.FilesystemService;
import com.orvix.service.StaticAnalysisService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final FilesystemService filesystemService;
    private final StaticAnalysisService staticAnalysisService;

    @Value("${orvix.projects-root}")
    private String projectsRoot;

    @Value("${GEMINI_API_KEY:}")
    private String geminiApiKeyVal;

    private File resolvedProjectsRoot;

    @jakarta.annotation.PostConstruct
    public void init() {
        String path = projectsRoot;
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (!isWindows && (path.startsWith("C:") || path.startsWith("C/"))) {
            resolvedProjectsRoot = new File("./projects");
        } else {
            resolvedProjectsRoot = new File(path);
        }
        if (!resolvedProjectsRoot.exists()) {
            resolvedProjectsRoot.mkdirs();
        }
    }

    public ProjectController(FilesystemService filesystemService, StaticAnalysisService staticAnalysisService) {
        this.filesystemService = filesystemService;
        this.staticAnalysisService = staticAnalysisService;
    }

    @GetMapping
    public ResponseEntity<List<String>> listProjects() {
        File root = resolvedProjectsRoot;
        if (!root.exists()) {
            root.mkdirs();
        }
        File[] files = root.listFiles();
        List<String> projects = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory() && !f.getName().equals(".git")) {
                    projects.add(f.getName());
                }
            }
        }
        return ResponseEntity.ok(projects);
    }

    @GetMapping("/{name}/tree")
    public ResponseEntity<FilesystemService.FileNode> getProjectTree(@PathVariable String name) {
        try {
            FilesystemService.FileNode tree = filesystemService.getProjectTree(name);
            try {
                String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tree);
                org.slf4j.LoggerFactory.getLogger(ProjectController.class)
                    .info("INFO: Explorer API response size: {} bytes", json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            } catch (Exception ex) {
                // ignore
            }
            return ResponseEntity.ok(tree);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{name}/files")
    public ResponseEntity<String> readFile(@PathVariable String name, @RequestParam String path) {
        try {
            return ResponseEntity.ok(filesystemService.readFile(name, path));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{name}/files")
    public ResponseEntity<Void> writeFile(
            @PathVariable String name, 
            @RequestParam String path, 
            @RequestBody String content) {
        try {
            filesystemService.writeFile(name, path, content);
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{name}/create")
    public ResponseEntity<Void> createNode(
            @PathVariable String name, 
            @RequestParam String path, 
            @RequestParam boolean isFolder) {
        try {
            filesystemService.createNode(name, path, isFolder);
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{name}/delete")
    public ResponseEntity<Void> deleteNode(@PathVariable String name, @RequestParam String path) {
        try {
            filesystemService.deleteNode(name, path);
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{name}/rename")
    public ResponseEntity<Void> renameNode(
            @PathVariable String name, 
            @RequestParam String path, 
            @RequestParam String newName) {
        try {
            filesystemService.renameNode(name, path, newName);
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{name}/move")
    public ResponseEntity<Void> moveNode(
            @PathVariable String name,
            @RequestParam String path,
            @RequestParam String targetDir) {
        try {
            filesystemService.moveNode(name, path, targetDir);
            return ResponseEntity.ok().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{name}/metadata")
    public ResponseEntity<FilesystemService.FileMetadata> getFileMetadata(
            @PathVariable String name,
            @RequestParam String path) {
        try {
            return ResponseEntity.ok(filesystemService.getFileMetadata(name, path));
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{name}/summary")
    public ResponseEntity<String> getProjectSummary(@PathVariable String name) {
        File projectDir = filesystemService.getProjectDirectory(name);
        if (!projectDir.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(staticAnalysisService.readAiSummary(projectDir));
    }

    @GetMapping("/gemini-check")
    public ResponseEntity<String> checkGemini() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== GEMINI DIAGNOSTICS ===\n");
        
        // 1. System.getenv
        String envKey = System.getenv("GEMINI_API_KEY");
        boolean envConfigured = envKey != null && !envKey.isBlank();
        sb.append("System.getenv(\"GEMINI_API_KEY\"):\n");
        sb.append("  Configured = ").append(envConfigured).append("\n");
        sb.append("  Length = ").append(envConfigured ? envKey.length() : 0).append("\n\n");
        
        // 2 & 3. Spring Boot reading env
        boolean springConfigured = geminiApiKeyVal != null && !geminiApiKeyVal.isBlank();
        sb.append("Spring Boot property bind (geminiApiKeyVal):\n");
        sb.append("  Configured = ").append(springConfigured).append("\n");
        sb.append("  Length = ").append(springConfigured ? geminiApiKeyVal.length() : 0).append("\n\n");
        
        // 4 & 5. AIService details
        String resolvedKey = (envConfigured ? envKey : (springConfigured ? geminiApiKeyVal : ""));
        boolean hasKey = !resolvedKey.isBlank();
        sb.append("AIService Resolution:\n");
        sb.append("  Using Env/Property variable = ").append(hasKey).append("\n");
        sb.append("  Model name = gemini-2.5-flash\n");
        sb.append("  API endpoint = https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent\n");
        sb.append("  Initialization success = ").append(hasKey).append("\n\n");
        
        // 6 & 7. Test request
        if (!hasKey) {
            sb.append("Skipping simple test request because API key is missing.\n\n");
            sb.append("=== Render Environment Setup Instructions ===\n");
            sb.append("Render is missing the environment variable 'GEMINI_API_KEY'.\n");
            sb.append("To add it:\n");
            sb.append("1. Go to your Render Dashboard (https://dashboard.render.com).\n");
            sb.append("2. Select your Web Service 'orvix-u1r4'.\n");
            sb.append("3. Click on the 'Environment' tab in the left sidebar.\n");
            sb.append("4. Click 'Add Environment Variable'.\n");
            sb.append("5. Set Key = GEMINI_API_KEY and Value = [Your actual Gemini API Key from Google AI Studio].\n");
            sb.append("6. Click 'Save Changes'. Render will automatically redeploy with the key active.\n");
        } else {
            sb.append("Attempting simple test request to Gemini API...\n");
            try {
                String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + resolvedKey;
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode textNode = mapper.createObjectNode().put("text", "Hello");
                com.fasterxml.jackson.databind.node.ObjectNode partNode = mapper.createObjectNode();
                partNode.set("parts", mapper.createArrayNode().add(textNode));
                com.fasterxml.jackson.databind.node.ObjectNode contentNode = mapper.createObjectNode();
                contentNode.set("contents", mapper.createArrayNode().add(partNode));
                String payload = mapper.writeValueAsString(contentNode);
                
                sb.append("  HTTP Request Status: Sending POST payload...\n");
                
                java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                
                java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                sb.append("  HTTP Response Status: ").append(response.statusCode()).append("\n");
                sb.append("  Response Body: ").append(response.body().replaceAll("\\r?\\n", " ")).append("\n");
            } catch (Exception e) {
                sb.append("  HTTP Request failed with exception:\n");
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                sb.append(sw.toString()).append("\n");
            }
        }
        
        return ResponseEntity.ok(sb.toString());
    }
}

