package com.orvix.service;

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

    @Value("${orvix.projects-root}")
    private String projectsRoot;

    private final StaticAnalysisService staticAnalysisService;
    private final AIService aiService;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ImportService.class);

    public ImportService(StaticAnalysisService staticAnalysisService, AIService aiService) {
        this.staticAnalysisService = staticAnalysisService;
        this.aiService = aiService;
    }

    public void importRepository(String repoUrl, SseEmitter emitter) {
        try {
            logger.info("INFO: Starting repository import");
            sendProgress(emitter, "URL Validation", "Validating repository URL format...");
            logger.info("INFO: Validating GitHub URL: {}", repoUrl);
            if (!isValidGithubUrl(repoUrl)) {
                logger.error("ERROR: Invalid repository URL format: {}", repoUrl);
                sendError(emitter, "Invalid GitHub repository URL. Must be a public github.com repository.");
                return;
            }

            String repoName = extractRepoName(repoUrl);
            File targetDir = new File(projectsRoot, repoName);

            // If directory exists, clean it up first
            if (targetDir.exists()) {
                logger.info("INFO: Cleaning up existing directory: {}", targetDir.getAbsolutePath());
                sendProgress(emitter, "Cloning Repository", "Cleaning up existing directory...");
                try {
                    deleteDirectory(targetDir.toPath());
                } catch (Exception e) {
                    logger.warn("WARNING: Deletion failed. Attempting rename fallback for Windows lock: {}", targetDir.getAbsolutePath());
                    File backupDir = new File(projectsRoot, repoName + "_deleted_" + System.currentTimeMillis());
                    if (targetDir.renameTo(backupDir)) {
                        new Thread(() -> {
                            try {
                                deleteDirectory(backupDir.toPath());
                                logger.info("INFO: Successfully deleted renamed directory: {}", backupDir.getAbsolutePath());
                            } catch (Exception ex) {
                                logger.error("ERROR: Failed to delete renamed directory: {}", backupDir.getAbsolutePath(), ex);
                            }
                        }).start();
                    } else {
                        logger.error("ERROR: Cleanup failed completely. Target directory is locked.");
                        throw new IOException("Failed to clean up existing directory. The directory is locked by another process.", e);
                    }
                }
            }

            // 2. Clone Repository
            logger.info("INFO: Starting clone for: {}", repoUrl);
            logger.info("INFO: Repository clone location: {}", targetDir.getAbsolutePath());
            sendProgress(emitter, "Cloning Repository", "Cloning " + repoUrl + " via JGit...");

            org.eclipse.jgit.lib.ProgressMonitor monitor = new org.eclipse.jgit.lib.ProgressMonitor() {
                private String taskTitle = "";
                private int totalWork = 0;
                private int completedWork = 0;

                @Override
                public void start(int totalTasks) {}

                @Override
                public void beginTask(String title, int totalWork) {
                    this.taskTitle = title;
                    this.totalWork = totalWork;
                    this.completedWork = 0;
                    try {
                        logger.info("INFO: JGit Task - {}", title);
                        sendProgress(emitter, "Cloning Repository", title + "...");
                    } catch (IOException e) {
                        logger.warn("WARNING: Emitter closed during JGit task start: {}", title);
                    }
                }

                @Override
                public void update(int completed) {
                    this.completedWork += completed;
                    if (totalWork > 0) {
                        int percent = (int) (((double) completedWork / totalWork) * 100);
                        if (percent % 10 == 0) {
                            try {
                                logger.info("INFO: JGit Clone Progress - {}: {}%", taskTitle, percent);
                                sendProgress(emitter, "Cloning Repository", taskTitle + ": " + percent + "%");
                            } catch (IOException e) {
                                logger.warn("WARNING: Emitter closed during progress update");
                            }
                        }
                    }
                }

                @Override
                public void endTask() {}

                @Override
                public boolean isCancelled() {
                    return false;
                }

                @Override
                public void showDuration(boolean show) {}
            };

            try (Git git = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(targetDir)
                    .setCloneAllBranches(false)
                    .setTimeout(60) // 60 seconds network timeout
                    .setProgressMonitor(monitor)
                    .call()) {
                logger.info("INFO: Clone completed successfully for: {}", repoUrl);
            }

            // 3. Analyzing Repository
            logger.info("INFO: Static analysis started for: {}", repoName);
            sendProgress(emitter, "Analyzing Repository", "Analyzing project files, build configs, and frameworks...");
            var projectSummary = staticAnalysisService.analyze(targetDir);
            logger.info("INFO: Static analysis completed for: {}", repoName);

            // 4. Building Project Structure
            logger.info("INFO: Repository indexed: {}", repoName);
            sendProgress(emitter, "Building Project Structure", "Indexing workspace directory tree...");

            // 5. Loading Files
            sendProgress(emitter, "Loading Files", "Scanning file system and preparing Monaco editor cache...");

            // 6. Generating AI Insights
            logger.info("INFO: Generating AI insights via Gemini API for: {}", repoName);
            sendProgress(emitter, "Generating AI Insights", "Invoking Gemini API for architectural summary...");
            String aiInsights = aiService.generateRepositorySummary(projectSummary, targetDir);
            staticAnalysisService.saveAiSummary(targetDir, aiInsights);
            logger.info("INFO: AI insights generated and saved");

            // 7. Opening Workspace
            logger.info("INFO: Workspace created for: {}", repoName);
            sendProgress(emitter, "Opening Workspace", "Project loaded! Opening workspace...");
            
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(repoName));
            emitter.complete();
            logger.info("INFO: Import completed successfully for: {}", repoName);

        } catch (Exception e) {
            logger.error("ERROR: Repository import failed", e);
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
            emitter.complete();
        } catch (Exception e) {
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

