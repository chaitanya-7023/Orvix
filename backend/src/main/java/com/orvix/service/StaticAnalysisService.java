package com.orvix.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class StaticAnalysisService {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(StaticAnalysisService.class);

    public ProjectSummary analyze(File projectDir) {
        String name = projectDir.getName();
        logger.info("INFO: Scanning project directory for build tool in: {}", projectDir.getAbsolutePath());
        String buildTool = "Unknown";
        String framework = "Plain Java";
        String language = "Java";
        String entryPoint = "Not Found";
        List<String> importantFiles = new ArrayList<>();

        List<Path> pomPaths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
            pomPaths = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            // ignore
        }

        File pomFile = null;
        if (!pomPaths.isEmpty()) {
            buildTool = "Maven";
            Path rootPomPath = projectDir.toPath().resolve("pom.xml");
            if (Files.exists(rootPomPath)) {
                pomFile = rootPomPath.toFile();
                importantFiles.add("pom.xml");
            } else {
                pomFile = pomPaths.get(0).toFile();
            }
            
            for (Path p : pomPaths) {
                String relative = projectDir.toPath().relativize(p).toString().replace('\\', '/');
                if (!importantFiles.contains(relative)) {
                    importantFiles.add(relative);
                }
            }

            for (Path p : pomPaths) {
                try {
                    String content = Files.readString(p);
                    if (content.contains("spring-boot")) {
                        framework = "Spring Boot Application";
                        break;
                    }
                } catch (IOException e) {
                    // ignore
                }
            }
        }

        if (buildTool.equals("Unknown")) {
            List<Path> gradlePaths = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
                gradlePaths = walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equals("build.gradle") || p.getFileName().toString().equals("build.gradle.kts"))
                        .collect(Collectors.toList());
            } catch (IOException e) {
                // ignore
            }

            if (!gradlePaths.isEmpty()) {
                buildTool = "Gradle";
                for (Path p : gradlePaths) {
                    String relative = projectDir.toPath().relativize(p).toString().replace('\\', '/');
                    if (!importantFiles.contains(relative)) {
                        importantFiles.add(relative);
                    }
                    try {
                        String content = Files.readString(p);
                        if (content.contains("org.springframework.boot")) {
                            framework = "Spring Boot Application";
                        }
                    } catch (IOException e) {
                        // ignore
                    }
                }
            }
        }

        // Find main entry point
        try (Stream<Path> paths = Files.walk(projectDir.toPath())) {
            entryPoint = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String content = Files.readString(p);
                            return content.contains("public static void main(");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> {
                        String relative = projectDir.toPath().relativize(p).toString().replace('\\', '/');
                        importantFiles.add(relative);
                        try {
                            String content = Files.readString(p);
                            String packageName = "";
                            var packageMatcher = java.util.regex.Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;").matcher(content);
                            if (packageMatcher.find()) {
                                packageName = packageMatcher.group(1) + ".";
                            }
                            String className = p.getFileName().toString().replace(".java", "");
                            return packageName + className + " (" + relative + ")";
                        } catch (IOException e) {
                            return relative;
                        }
                    })
                    .findFirst()
                    .orElse("Not Found");
        } catch (IOException e) {
            // ignore
        }

        logger.info("INFO: Build Tool: {}", buildTool);
        logger.info("INFO: Entry Point: {}", entryPoint);

        return new ProjectSummary(
                name,
                language,
                buildTool,
                framework,
                entryPoint,
                importantFiles,
                projectDir.getAbsolutePath().replace('\\', '/')
        );
    }

    public void saveAiSummary(File projectDir, String summaryText) {
        try {
            File dotOrvix = new File(projectDir, ".orvix");
            if (!dotOrvix.exists()) {
                dotOrvix.mkdirs();
            }
            Files.writeString(new File(dotOrvix, "summary.md").toPath(), summaryText);
        } catch (IOException e) {
            // ignore
        }
    }

    public String readAiSummary(File projectDir) {
        try {
            File summaryFile = new File(projectDir, ".orvix/summary.md");
            if (summaryFile.exists()) {
                String content = Files.readString(summaryFile.toPath());
                if (content.contains("⚠️ Gemini API Request Exception") || content.contains("⚠️ Gemini API Error")) {
                    return "⚠️ AI Summary Generation Failed:\n\n" + content + "\n\nSet the `GEMINI_API_KEY` env variable correctly and re-import.";
                }
                return content;
            }
        } catch (IOException e) {
            // ignore
        }
        return "⚠️ AI Summary Generation Failed. No summary file was found. Please ensure the repository was imported successfully.";
    }
}

