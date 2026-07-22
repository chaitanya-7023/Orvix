package com.devflow.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Service
public class StaticAnalysisService {

    public ProjectSummary analyze(File projectDir) {
        String name = projectDir.getName();
        String buildTool = "Unknown";
        String framework = "Plain Java";
        String language = "Java";
        String entryPoint = "Not Found";
        List<String> importantFiles = new ArrayList<>();

        if (new File(projectDir, "pom.xml").exists()) {
            buildTool = "Maven";
            importantFiles.add("pom.xml");
            
            try {
                String pomContent = Files.readString(new File(projectDir, "pom.xml").toPath());
                if (pomContent.contains("spring-boot")) {
                    framework = "Spring Boot Application";
                }
            } catch (IOException e) {
                // ignore
            }
        } else if (new File(projectDir, "build.gradle").exists() || new File(projectDir, "build.gradle.kts").exists()) {
            buildTool = "Gradle";
            importantFiles.add(new File(projectDir, "build.gradle").exists() ? "build.gradle" : "build.gradle.kts");
            
            try {
                Path gradlePath = new File(projectDir, "build.gradle").exists() ? 
                        new File(projectDir, "build.gradle").toPath() : new File(projectDir, "build.gradle.kts").toPath();
                String gradleContent = Files.readString(gradlePath);
                if (gradleContent.contains("org.springframework.boot")) {
                    framework = "Spring Boot Application";
                }
            } catch (IOException e) {
                // ignore
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
                        // Extract package and class name if possible
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
            File dotDevFlow = new File(projectDir, ".devflow");
            if (!dotDevFlow.exists()) {
                dotDevFlow.mkdirs();
            }
            Files.writeString(new File(dotDevFlow, "summary.md").toPath(), summaryText);
        } catch (IOException e) {
            // ignore
        }
    }

    public String readAiSummary(File projectDir) {
        try {
            File summaryFile = new File(projectDir, ".devflow/summary.md");
            if (summaryFile.exists()) {
                return Files.readString(summaryFile.toPath());
            }
        } catch (IOException e) {
            // ignore
        }
        return "No AI summary available. Re-run import or static analysis.";
    }
}
