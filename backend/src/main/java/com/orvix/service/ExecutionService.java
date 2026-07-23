package com.orvix.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ExecutionService {

    private final FilesystemService filesystemService;
    private final StaticAnalysisService staticAnalysisService;
    private final AIService aiService;
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();

    public ExecutionService(FilesystemService filesystemService, StaticAnalysisService staticAnalysisService, AIService aiService) {
        this.filesystemService = filesystemService;
        this.staticAnalysisService = staticAnalysisService;
        this.aiService = aiService;
    }

    public void runProject(String projectName, SseEmitter emitter) {
        File projectDir = filesystemService.getProjectDirectory(projectName);
        if (!projectDir.exists()) {
            sendEvent(emitter, "system", "Error: Project directory not found.");
            emitter.complete();
            return;
        }

        // Stop any running instance first
        stopProject(projectName);

        CompletableFuture.runAsync(() -> {
            try {
                ProjectSummary summary = staticAnalysisService.analyze(projectDir);

                List<String> javaFilesExist;
                try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
                    javaFilesExist = walk.filter(Files::isRegularFile)
                            .map(Path::toString)
                            .filter(s -> s.endsWith(".java"))
                            .collect(Collectors.toList());
                }

                if (javaFilesExist.isEmpty()) {
                    sendEvent(emitter, "system", "Error: No Java files found to compile.");
                    emitter.complete();
                    return;
                }

                sendEvent(emitter, "system", "Detected build tool: " + summary.buildTool());
                sendEvent(emitter, "system", "Framework: " + summary.framework());
                sendEvent(emitter, "system", "Entry Point: " + summary.entryPoint());

                boolean compileSuccess = false;
                List<String> runCommand = new ArrayList<>();
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                File buildDir = projectDir;

                File mvnw = new File(projectDir, "mvnw.cmd");
                File mvnwNonCmd = new File(projectDir, "mvnw");

                if (!mvnw.exists() && !mvnwNonCmd.exists()) {
                    File[] children = projectDir.listFiles();
                    if (children != null) {
                        for (File child : children) {
                            if (child.isDirectory()) {
                                if (new File(child, "mvnw.cmd").exists()) {
                                    mvnw = new File(child, "mvnw.cmd");
                                    buildDir = child;
                                    break;
                                } else if (new File(child, "mvnw").exists()) {
                                    mvnw = new File(child, "mvnw");
                                    buildDir = child;
                                    break;
                                }
                            }
                        }
                    }
                } else {
                    mvnw = mvnw.exists() ? mvnw : mvnwNonCmd;
                }

                File gradlew = new File(projectDir, "gradlew.bat");
                File gradlewNonBat = new File(projectDir, "gradlew");

                if (!gradlew.exists() && !gradlewNonBat.exists() && buildDir.equals(projectDir)) {
                    File[] children = projectDir.listFiles();
                    if (children != null) {
                        for (File child : children) {
                            if (child.isDirectory()) {
                                if (new File(child, "gradlew.bat").exists()) {
                                    gradlew = new File(child, "gradlew.bat");
                                    buildDir = child;
                                    break;
                                } else if (new File(child, "gradlew").exists()) {
                                    gradlew = new File(child, "gradlew");
                                    buildDir = child;
                                    break;
                                }
                            }
                        }
                    }
                } else if (!buildDir.equals(projectDir)) {
                    // Maven was already located in subfolder
                } else {
                    gradlew = gradlew.exists() ? gradlew : gradlewNonBat;
                }

                if (mvnw.exists()) {
                    sendEvent(emitter, "system", "Compiling using Maven Wrapper...");
                    List<String> compileCmd = new ArrayList<>();
                    List<String> runCmd = new ArrayList<>();
                    if (isWindows) {
                        String execName = mvnw.getName().endsWith(".cmd") ? mvnw.getName() : "mvnw.cmd";
                        compileCmd.addAll(List.of("cmd.exe", "/c", execName, "compile"));
                        runCmd.addAll(List.of("cmd.exe", "/c", execName, "spring-boot:run"));
                    } else {
                        compileCmd.addAll(List.of("/bin/sh", "-c", "./" + mvnw.getName() + " compile"));
                        runCmd.addAll(List.of("/bin/sh", "-c", "./" + mvnw.getName() + " spring-boot:run"));
                    }
                    compileSuccess = runBuildProcess(buildDir, compileCmd, emitter);
                    if (compileSuccess) {
                        runCommand.addAll(runCmd);
                    }
                } else if (gradlew.exists()) {
                    sendEvent(emitter, "system", "Compiling using Gradle Wrapper...");
                    List<String> compileCmd = new ArrayList<>();
                    List<String> runCmd = new ArrayList<>();
                    if (isWindows) {
                        String execName = gradlew.getName().endsWith(".bat") ? gradlew.getName() : "gradlew.bat";
                        compileCmd.addAll(List.of("cmd.exe", "/c", execName, "classes"));
                        runCmd.addAll(List.of("cmd.exe", "/c", execName, "bootRun"));
                    } else {
                        compileCmd.addAll(List.of("/bin/sh", "-c", "./" + gradlew.getName() + " classes"));
                        runCmd.addAll(List.of("/bin/sh", "-c", "./" + gradlew.getName() + " bootRun"));
                    }
                    compileSuccess = runBuildProcess(buildDir, compileCmd, emitter);
                    if (compileSuccess) {
                        runCommand.addAll(runCmd);
                    }
                } else {
                    // Fallback compile: Java Compiler API or manual javac
                    sendEvent(emitter, "system", "No wrapper found. Using standard JDK javac compilation...");
                    File classesDir = new File(projectDir, "target/classes");
                    if (!classesDir.exists()) {
                        classesDir.mkdirs();
                    }

                    List<String> javaFiles;
                    try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
                        javaFiles = walk.filter(Files::isRegularFile)
                                .map(Path::toString)
                                .filter(s -> s.endsWith(".java"))
                                .collect(Collectors.toList());
                    }

                    if (javaFiles.isEmpty()) {
                        sendEvent(emitter, "system", "Error: No Java files found to compile.");
                        emitter.complete();
                        return;
                    }

                    List<String> javacCmd = new ArrayList<>();
                    javacCmd.add("javac");
                    javacCmd.add("-d");
                    javacCmd.add("target/classes");
                    javacCmd.addAll(javaFiles);

                    compileSuccess = runBuildProcess(projectDir, javacCmd, emitter);
                    
                    if (compileSuccess) {
                        // Extract class name from entry point
                        String entryClass = summary.entryPoint();
                        if (entryClass.contains(" ")) {
                            entryClass = entryClass.substring(0, entryClass.indexOf(" "));
                        }
                        if (entryClass.equals("Not Found")) {
                            sendEvent(emitter, "system", "Error: Main entry point class not found.");
                            compileSuccess = false;
                        } else {
                            runCommand.add("java");
                            runCommand.add("-cp");
                            runCommand.add("target/classes");
                            runCommand.add(entryClass);
                        }
                    }
                }

                if (!compileSuccess) {
                    sendEvent(emitter, "system", "Compilation failed.");
                    emitter.complete();
                    return;
                }

                sendEvent(emitter, "system", "Compilation successful! Starting application process...");
                ProcessBuilder pb = new ProcessBuilder(runCommand);
                pb.directory(buildDir);
                pb.redirectErrorStream(true); // combine stdout and stderr

                Process process = pb.start();
                activeProcesses.put(projectName, process);

                // Stream the output
                boolean hasException = false;
                List<String> runLogs = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        runLogs.add(line);
                        sendEvent(emitter, "console", line);
                        if (line.contains("Exception") || line.contains("Error") || line.contains("\tat ") || line.contains("Failed to")) {
                            hasException = true;
                        }
                    }
                }

                int exitCode = process.waitFor();
                activeProcesses.remove(projectName);
                sendEvent(emitter, "system", "Process exited with code: " + exitCode);

                if (exitCode != 0 || hasException) {
                    sendEvent(emitter, "system", "Analyzing execution failure with Gemini...");
                    String analysis = aiService.analyzeRuntimeException(runLogs, projectDir);
                    sendEvent(emitter, "runtime-error-analysis", analysis);
                }
                emitter.complete();

            } catch (Exception e) {
                sendEvent(emitter, "system", "Error running project: " + e.getMessage());
                emitter.complete();
            }
        });
    }

    public void stopProject(String projectName) {
        Process p = activeProcesses.remove(projectName);
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                // Wait for process to clean up or force kill it
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
            }
        }
    }

    private boolean runBuildProcess(File projectDir, List<String> command, SseEmitter emitter) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sendEvent(emitter, "console", "[Build] " + line);
            }
        }

        return process.waitFor() == 0;
    }

    private void sendEvent(SseEmitter emitter, String type, String text) {
        try {
            emitter.send(SseEmitter.event()
                    .name(type)
                    .data(text));
        } catch (Exception e) {
            // client disconnected
        }
    }
}

