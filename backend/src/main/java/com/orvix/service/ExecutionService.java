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

                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                boolean compileSuccess = false;
                List<String> runCommand = new ArrayList<>();
                File runDir = projectDir;
                File buildDir = projectDir;

                // 1. Detect Maven projects
                List<Path> pomPaths = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
                    pomPaths = walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals("pom.xml"))
                            .collect(Collectors.toList());
                } catch (IOException e) {
                    // ignore
                }

                // 2. Detect Gradle projects
                List<Path> gradlePaths = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
                    gradlePaths = walk.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals("build.gradle") || p.getFileName().toString().equals("build.gradle.kts"))
                            .collect(Collectors.toList());
                } catch (IOException e) {
                    // ignore
                }

                if (!pomPaths.isEmpty()) {
                    // Maven Project Execution Flow
                    String buildTool = "Maven";
                    String framework = summary.framework();
                    File rootPom = new File(projectDir, "pom.xml");
                    String rootPomLocation = rootPom.exists() ? rootPom.getAbsolutePath() : "None (Multi-module subfolders)";
                    
                    List<String> modules = new ArrayList<>();
                    for (Path p : pomPaths) {
                        File parentFile = p.getParent().toFile();
                        if (!parentFile.equals(projectDir)) {
                            modules.add(parentFile.getName());
                        }
                    }
                    String moduleList = modules.isEmpty() ? "[]" : modules.toString();

                    // Find Maven Wrapper (mvnw / mvnw.cmd) recursively
                    File mvnwExec = null;
                    try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
                        Path found = walk.filter(p -> p.getFileName().toString().equals(isWindows ? "mvnw.cmd" : "mvnw"))
                                .findFirst()
                                .orElse(null);
                        if (found != null) {
                            mvnwExec = found.toFile();
                        }
                    } catch (Exception e) {
                        // ignore
                    }

                    String mvnCommand;
                    boolean mvnAvailable = false;
                    if (mvnwExec != null) {
                        mvnCommand = mvnwExec.getAbsolutePath();
                        mvnAvailable = true;
                        buildDir = mvnwExec.getParentFile();
                    } else {
                        mvnCommand = isWindows ? "mvn.cmd" : "mvn";
                        try {
                            Process checkProc = new ProcessBuilder(mvnCommand, "-version").start();
                            checkProc.waitFor();
                            mvnAvailable = true;
                        } catch (Exception e) {
                            if (isWindows) {
                                File localMvn = new File("C:\\Users\\venka\\.gemini\\antigravity\\scratch\\devflow\\backend\\maven\\apache-maven-3.9.6\\bin\\mvn.cmd");
                                if (localMvn.exists()) {
                                    mvnCommand = localMvn.getAbsolutePath();
                                    mvnAvailable = true;
                                }
                            }
                        }
                    }

                    if (!mvnAvailable) {
                        sendEvent(emitter, "system", "Error: Maven build tool detected but Maven is not installed or available in the system path. Cannot compile.");
                        emitter.complete();
                        return;
                    }

                    String buildCommandUsed = mvnCommand + " clean package -U -DskipTests";
                    
                    sendEvent(emitter, "system", "Build Tool: " + buildTool);
                    sendEvent(emitter, "system", "Framework: " + framework);
                    sendEvent(emitter, "system", "Root pom.xml location: " + rootPomLocation);
                    sendEvent(emitter, "system", "Module list: " + moduleList);
                    sendEvent(emitter, "system", "Build command used: " + buildCommandUsed);

                    // Compile modules
                    if (rootPom.exists()) {
                        sendEvent(emitter, "system", "Compiling Maven project...");
                        List<String> compileCmd = List.of(mvnCommand, "clean", "package", "-U", "-DskipTests");
                        compileSuccess = runBuildProcess(projectDir, compileCmd, emitter);
                    } else {
                        compileSuccess = true;
                        for (Path p : pomPaths) {
                            File modDir = p.getParent().toFile();
                            sendEvent(emitter, "system", "Compiling module " + modDir.getName() + "...");
                            List<String> compileCmd = List.of(mvnCommand, "clean", "package", "-U", "-DskipTests");
                            boolean success = runBuildProcess(modDir, compileCmd, emitter);
                            if (!success) {
                                compileSuccess = false;
                                break;
                            }
                        }
                    }

                    if (compileSuccess) {
                        // Resolve Entry Point module
                        String entryClass = summary.entryPoint();
                        if (entryClass != null && !entryClass.equals("Not Found")) {
                            if (entryClass.contains(" ")) {
                                entryClass = entryClass.substring(0, entryClass.indexOf(" "));
                            }
                            String classRelativePath = entryClass.replace('.', '/') + ".java";
                            try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
                                Path classPath = walk.filter(p -> p.toString().replace('\\', '/').endsWith(classRelativePath))
                                        .findFirst()
                                        .orElse(null);
                                if (classPath != null) {
                                    Path parent = classPath.getParent();
                                    while (parent != null && !parent.equals(projectDir.toPath())) {
                                        if (Files.exists(parent.resolve("pom.xml"))) {
                                            runDir = parent.toFile();
                                            break;
                                        }
                                        parent = parent.getParent();
                                    }
                                }
                            } catch (Exception e) {
                                // ignore
                            }

                            if (framework.equals("Spring Boot Application")) {
                                if (isWindows) {
                                    runCommand.addAll(List.of("cmd.exe", "/c", mvnCommand, "spring-boot:run"));
                                } else {
                                    runCommand.addAll(List.of(mvnCommand, "spring-boot:run"));
                                }
                            } else {
                                if (isWindows) {
                                    runCommand.addAll(List.of("cmd.exe", "/c", mvnCommand, "exec:java", "-Dexec.mainClass=" + entryClass));
                                } else {
                                    runCommand.addAll(List.of(mvnCommand, "exec:java", "-Dexec.mainClass=" + entryClass));
                                }
                            }
                        } else {
                            sendEvent(emitter, "system", "Error: Entry Point not found. Cannot determine run command.");
                            compileSuccess = false;
                        }
                    }

                } else if (!gradlePaths.isEmpty()) {
                    // Gradle Project Execution Flow
                    String buildTool = "Gradle";
                    String framework = summary.framework();
                    
                    File gradlewExec = null;
                    try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
                        Path found = walk.filter(p -> p.getFileName().toString().equals(isWindows ? "gradlew.bat" : "gradlew"))
                                .findFirst()
                                .orElse(null);
                        if (found != null) {
                            gradlewExec = found.toFile();
                        }
                    } catch (Exception e) {
                        // ignore
                    }

                    String gradleCommand;
                    boolean gradleAvailable = false;
                    if (gradlewExec != null) {
                        gradleCommand = gradlewExec.getAbsolutePath();
                        gradleAvailable = true;
                        buildDir = gradlewExec.getParentFile();
                    } else {
                        gradleCommand = isWindows ? "gradle.bat" : "gradle";
                        try {
                            Process checkProc = new ProcessBuilder(gradleCommand, "-v").start();
                            checkProc.waitFor();
                            gradleAvailable = true;
                        } catch (Exception e) {
                            // ignore
                        }
                    }

                    if (!gradleAvailable) {
                        sendEvent(emitter, "system", "Error: Gradle build tool detected but Gradle is not installed or available in the system path. Cannot compile.");
                        emitter.complete();
                        return;
                    }

                    String buildCommandUsed = gradleCommand + " clean classes";
                    sendEvent(emitter, "system", "Build Tool: " + buildTool);
                    sendEvent(emitter, "system", "Framework: " + framework);
                    sendEvent(emitter, "system", "Build command used: " + buildCommandUsed);

                    sendEvent(emitter, "system", "Compiling Gradle project...");
                    List<String> compileCmd = List.of(gradleCommand, "clean", "classes");
                    compileSuccess = runBuildProcess(buildDir, compileCmd, emitter);

                    if (compileSuccess) {
                        String entryClass = summary.entryPoint();
                        if (entryClass != null && !entryClass.equals("Not Found")) {
                            if (entryClass.contains(" ")) {
                                entryClass = entryClass.substring(0, entryClass.indexOf(" "));
                            }
                            if (framework.equals("Spring Boot Application")) {
                                if (isWindows) {
                                    runCommand.addAll(List.of("cmd.exe", "/c", gradleCommand, "bootRun"));
                                } else {
                                    runCommand.addAll(List.of(gradleCommand, "bootRun"));
                                }
                            } else {
                                if (isWindows) {
                                    runCommand.addAll(List.of("cmd.exe", "/c", gradleCommand, "run"));
                                } else {
                                    runCommand.addAll(List.of(gradleCommand, "run"));
                                }
                            }
                        } else {
                            sendEvent(emitter, "system", "Error: Entry Point not found. Cannot determine run command.");
                            compileSuccess = false;
                        }
                    }

                } else {
                    // Plain Java Fallback Flow
                    sendEvent(emitter, "system", "No wrapper or build files found. Using standard JDK javac compilation...");
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

                    File targetDir = new File(projectDir, "target");
                    if (!targetDir.exists()) {
                        targetDir.mkdirs();
                    }
                    File sourcesFile = new File(targetDir, "sources.txt");
                    Files.write(sourcesFile.toPath(), javaFiles);

                    List<String> javacCmd = List.of("javac", "-d", "target/classes", "@" + sourcesFile.getAbsolutePath());
                    compileSuccess = runBuildProcess(projectDir, javacCmd, emitter);
                    
                    if (compileSuccess) {
                        String entryClass = summary.entryPoint();
                        if (entryClass != null && !entryClass.equals("Not Found")) {
                            if (entryClass.contains(" ")) {
                                entryClass = entryClass.substring(0, entryClass.indexOf(" "));
                            }
                            runCommand.addAll(List.of("java", "-cp", "target/classes", entryClass));
                        } else {
                            sendEvent(emitter, "system", "Error: Main entry point class not found.");
                            compileSuccess = false;
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
                pb.directory(runDir);
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
        sendEvent(emitter, "system", "=== PROCESS EXECUTION DIAGNOSTICS ===");
        sendEvent(emitter, "system", "Working Directory (projectDir): " + projectDir.getAbsolutePath());
        sendEvent(emitter, "system", "  Exists: " + projectDir.exists());
        sendEvent(emitter, "system", "  Is Directory: " + projectDir.isDirectory());
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir);
        pb.redirectErrorStream(true);
        
        sendEvent(emitter, "system", "ProcessBuilder.directory(): " + (pb.directory() != null ? pb.directory().getAbsolutePath() : "null"));
        sendEvent(emitter, "system", "Command list: " + pb.command().toString());
        
        String javaHome = System.getenv("JAVA_HOME");
        String path = System.getenv("PATH");
        String currentOs = System.getProperty("os.name");
        String currentUser = System.getProperty("user.name");
        
        sendEvent(emitter, "system", "Environment Variables:");
        sendEvent(emitter, "system", "  JAVA_HOME: " + javaHome);
        sendEvent(emitter, "system", "  PATH: " + path);
        sendEvent(emitter, "system", "  Current OS: " + currentOs);
        sendEvent(emitter, "system", "  Current User: " + currentUser);
        
        sendEvent(emitter, "system", "Verifying javac and java versions...");
        try {
            Process whichJavac = new ProcessBuilder(currentOs.toLowerCase().contains("win") ? List.of("cmd.exe", "/c", "where javac") : List.of("which", "javac")).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(whichJavac.getInputStream()))) {
                String line = r.readLine();
                sendEvent(emitter, "system", "  which javac: " + (line != null ? line : "Not found"));
            }
        } catch (Exception ex) {
            sendEvent(emitter, "system", "  which javac check failed: " + ex.getMessage());
        }
        
        try {
            Process javaVer = new ProcessBuilder("java", "-version").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(javaVer.getInputStream()))) {
                String line = r.readLine();
                sendEvent(emitter, "system", "  java -version: " + (line != null ? line : "Unknown"));
            }
        } catch (Exception ex) {
            sendEvent(emitter, "system", "  java -version check failed: " + ex.getMessage());
        }

        try {
            Process javacVer = new ProcessBuilder("javac", "-version").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(javacVer.getInputStream()))) {
                String line = r.readLine();
                sendEvent(emitter, "system", "  javac -version: " + (line != null ? line : "Unknown"));
            }
        } catch (Exception ex) {
            sendEvent(emitter, "system", "  javac -version check failed: " + ex.getMessage());
        }
        
        sendEvent(emitter, "system", "=======================================");
        
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            sendEvent(emitter, "system", "Exec failed, error message: " + e.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            sendEvent(emitter, "system", "Exception stack trace:\n" + sw.toString());
            throw e;
        }

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

