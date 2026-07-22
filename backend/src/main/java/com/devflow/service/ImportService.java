package com.devflow.service;

import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImportService {

    @Value("${devflow.projects-root}")
    private String projectsRoot;

    private final StaticAnalysisService staticAnalysisService;
    private final AIService aiService;

    public ImportService(StaticAnalysisService staticAnalysisService, AIService aiService) {
        this.staticAnalysisService = staticAnalysisService;
        this.aiService = aiService;
    }

    public void importRepository(String repoUrl, SseEmitter emitter) {
        try {
            // 1. Validate URL
            sendProgress(emitter, "URL Validation", "Validating repository URL format...");
            if (!isValidGithubUrl(repoUrl)) {
                sendError(emitter, "Invalid GitHub repository URL. Must be a public github.com repository.");
                return;
            }

            String repoName = extractRepoName(repoUrl);
            File targetDir = new File(projectsRoot, repoName);

            // If directory exists, clean it up first
            if (targetDir.exists()) {
                sendProgress(emitter, "Cloning Repository", "Cleaning up existing directory...");
                deleteDirectory(targetDir.toPath());
            }

            // 2. Clone Repository
            sendProgress(emitter, "Cloning Repository", "Cloning " + repoUrl + " via JGit...");
            try (Git git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(targetDir)
                    .setCloneAllBranches(false)
                    .call()) {
                // clone complete
            }

            // 3. Analyzing Repository
            sendProgress(emitter, "Analyzing Repository", "Analyzing project files, build configs, and frameworks...");
            var projectSummary = staticAnalysisService.analyze(targetDir);

            // 4. Building Project Structure
            sendProgress(emitter, "Building Project Structure", "Indexing workspace directory tree...");
            // Structure is scanned when the workspace opens

            // 5. Loading Files
            sendProgress(emitter, "Loading Files", "Scanning file system and preparing Monaco editor cache...");

            // 6. Generating AI Insights
            sendProgress(emitter, "Generating AI Insights", "Invoking Gemini API for architectural summary...");
            String aiInsights = aiService.generateRepositorySummary(projectSummary, targetDir);
            staticAnalysisService.saveAiSummary(targetDir, aiInsights);

            // 7. Opening Workspace
            sendProgress(emitter, "Opening Workspace", "Project loaded! Opening workspace...");
            
            // Send completion message with repoName
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(repoName));
            emitter.complete();

        } catch (Exception e) {
            sendError(emitter, "Import failed: " + e.getMessage());
        }
    }

    private boolean isValidGithubUrl(String url) {
        if (url == null) return false;
        Pattern pattern = Pattern.compile("https://(www\\.)?github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?");
        Matcher matcher = pattern.matcher(url);
        return matcher.matches();
    }

    private String extractRepoName(String url) {
        String cleaned = url.replaceAll("/$", "");
        String name = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    private void sendProgress(SseEmitter emitter, String stage, String details) throws IOException {
        emitter.send(SseEmitter.event()
                .name("progress")
                .data(new ProgressEvent(stage, details)));
    }

    private void sendError(SseEmitter emitter, String errorMessage) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(errorMessage));
            emitter.completeWithError(new RuntimeException(errorMessage));
        } catch (IOException e) {
            // ignore
        }
    }

    private void deleteDirectory(Path path) throws IOException {
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    public static record ProgressEvent(String stage, String details) {}
}
