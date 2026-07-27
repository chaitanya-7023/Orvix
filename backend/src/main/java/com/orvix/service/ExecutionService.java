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
    private final Map<String, Integer> activePorts = new ConcurrentHashMap<>();

    public ExecutionService(FilesystemService filesystemService, StaticAnalysisService staticAnalysisService, AIService aiService) {
        this.filesystemService = filesystemService;
        this.staticAnalysisService = staticAnalysisService;
        this.aiService = aiService;
    }

    public Integer getActivePort(String projectName) {
        return activePorts.get(projectName);
    }

    private boolean isPortOccupied(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private int findFreePort() {
        for (int port = 8081; port <= 9000; port++) {
            try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                // port occupied, try next
            }
        }
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 8081; // absolute fallback
        }
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
                int targetPort = 8080;
                if (isPortOccupied(8080)) {
                    targetPort = findFreePort();
                }
                ProjectSummary summary = staticAnalysisService.analyze(projectDir);

                List<String> javaFilesExist;
                try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
                    javaFilesExist = walk.filter(Files::isRegularFile)
                            .map(Path::toString)
                            .filter(s -> s.endsWith(".java"))
                            .collect(Collectors.toList());
                }

                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

                // Check if Node project or Static Web project
                boolean isNodeProject = new File(projectDir, "package.json").exists();
                boolean hasIndexHtml = false;
                try (Stream<Path> walk = Files.walk(projectDir.toPath())) {
                    hasIndexHtml = walk.filter(Files::isRegularFile)
                            .anyMatch(p -> p.getFileName().toString().equalsIgnoreCase("index.html"));
                } catch (IOException e) {}

                if (javaFilesExist.isEmpty()) {
                    if (isNodeProject) {
                        sendEvent(emitter, "system", "Detected Node.js project. Preparing execution...");
                        runNodeProject(projectDir, targetPort, projectName, emitter, isWindows);
                        return;
                    } else if (hasIndexHtml) {
                        sendEvent(emitter, "system", "Detected Static Web project. Launching embedded web server...");
                        startStaticFileServer(projectDir, targetPort, projectName, emitter);
                        return;
                    } else {
                        sendEvent(emitter, "system", "Error: No Java files, Node.js packages, or HTML entry points found to run.");
                        emitter.complete();
                        return;
                    }
                }
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
                                    runCommand.addAll(List.of("cmd.exe", "/c", mvnCommand, "spring-boot:run", "-Dspring-boot.run.arguments=--server.port=" + targetPort));
                                } else {
                                    runCommand.addAll(List.of(mvnCommand, "spring-boot:run", "-Dspring-boot.run.arguments=--server.port=" + targetPort));
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
                                    runCommand.addAll(List.of("cmd.exe", "/c", gradleCommand, "bootRun", "--args=--server.port=" + targetPort));
                                } else {
                                    runCommand.addAll(List.of(gradleCommand, "bootRun", "--args=--server.port=" + targetPort));
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
                            runCommand.addAll(List.of("java", "-Dserver.port=" + targetPort, "-cp", "target/classes", entryClass, "--server.port=" + targetPort));
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
                pb.environment().put("PORT", String.valueOf(targetPort));
                pb.environment().put("SERVER_PORT", String.valueOf(targetPort));

                Process process = pb.start();
                activeProcesses.put(projectName, process);
                activePorts.put(projectName, targetPort);

                // Stream the output
                boolean hasException = false;
                boolean hasStarted = false;
                List<String> runLogs = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        runLogs.add(line);
                        sendEvent(emitter, "console", line);
                        if (line.contains("Exception") || line.contains("Error") || line.contains("\tat ") || line.contains("Failed to")) {
                            hasException = true;
                        }
                        if (!hasStarted && (line.contains("Tomcat started on port") || line.contains("Started Application") || line.contains("Tomcat initialized with port") || line.contains("Tomcat started on port(s)"))) {
                            hasStarted = true;
                            sendEvent(emitter, "started", String.valueOf(targetPort));
                            sendEvent(emitter, "console", "✔ Application Started");
                            sendEvent(emitter, "console", "Running Port: " + targetPort);
                            sendEvent(emitter, "console", "URL: http://localhost:" + targetPort);
                        }
                    }
                }

                int exitCode = process.waitFor();
                activeProcesses.remove(projectName);
                activePorts.remove(projectName);
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
        activePorts.remove(projectName);
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

    private void runNodeProject(File projectDir, int port, String projectName, SseEmitter emitter, boolean isWindows) {
        CompletableFuture.runAsync(() -> {
            try {
                // 1. Run npm install
                sendEvent(emitter, "system", "Running 'npm install' to resolve dependencies...");
                List<String> installCmd = isWindows ? List.of("cmd.exe", "/c", "npm", "install") : List.of("npm", "install");
                boolean installSuccess = runBuildProcess(projectDir, installCmd, emitter);
                if (!installSuccess) {
                    sendEvent(emitter, "system", "npm install failed.");
                    emitter.complete();
                    return;
                }

                // 2. Determine start command
                List<String> runCmd = isWindows ? List.of("cmd.exe", "/c", "npm", "start") : List.of("npm", "start");
                File packageJson = new File(projectDir, "package.json");
                if (packageJson.exists()) {
                    String content = Files.readString(packageJson.toPath());
                    if (content.contains("\"dev\"")) {
                        runCmd = isWindows ? List.of("cmd.exe", "/c", "npm", "run", "dev") : List.of("npm", "run", "dev");
                    }
                }

                sendEvent(emitter, "system", "Starting Node.js application process...");
                ProcessBuilder pb = new ProcessBuilder(runCmd);
                pb.directory(projectDir);
                pb.redirectErrorStream(true);
                pb.environment().put("PORT", String.valueOf(port));

                Process process = pb.start();
                activeProcesses.put(projectName, process);
                activePorts.put(projectName, port);

                sendEvent(emitter, "started", String.valueOf(port));
                sendEvent(emitter, "console", "✔ Node.js Application Started");
                sendEvent(emitter, "console", "Running Port: " + port);
                sendEvent(emitter, "console", "URL: http://localhost:" + port);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sendEvent(emitter, "console", line);
                    }
                }

                int exitCode = process.waitFor();
                activeProcesses.remove(projectName);
                activePorts.remove(projectName);
                sendEvent(emitter, "system", "Node process exited with code: " + exitCode);
                emitter.complete();

            } catch (Exception e) {
                sendEvent(emitter, "system", "Error running Node project: " + e.getMessage());
                emitter.complete();
            }
        });
    }

    private void startStaticFileServer(File projectDir, int port, String projectName, SseEmitter emitter) {
        try {
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/")) {
                    path += "index.html";
                }
                File file = new File(projectDir, path.substring(1));
                if (!file.exists() || file.isDirectory()) {
                    file = new File(projectDir, "index.html");
                }
                if (file.exists() && !file.isDirectory()) {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    String contentType = "text/plain";
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".html")) contentType = "text/html";
                    else if (name.endsWith(".css")) contentType = "text/css";
                    else if (name.endsWith(".js")) contentType = "application/javascript";
                    else if (name.endsWith(".png")) contentType = "image/png";
                    else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) contentType = "image/jpeg";
                    else if (name.endsWith(".svg")) contentType = "image/svg+xml";
                    else if (name.endsWith(".json")) contentType = "application/json";

                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (java.io.OutputStream os = exchange.getResponseBody()) {
                        os.write(bytes);
                    }
                } else {
                    String msg = "404 Not Found";
                    exchange.sendResponseHeaders(404, msg.length());
                    try (java.io.OutputStream os = exchange.getResponseBody()) {
                        os.write(msg.getBytes());
                    }
                }
            });
            server.setExecutor(null);
            server.start();

            Process mockProcess = new Process() {
                private boolean alive = true;
                @Override
                public java.io.OutputStream getOutputStream() { return new java.io.ByteArrayOutputStream(); }
                @Override
                public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
                @Override
                public java.io.InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
                @Override
                public int waitFor() throws InterruptedException {
                    while (alive) { Thread.sleep(1000); }
                    return 0;
                }
                @Override
                public int exitValue() { return alive ? 0 : 1; }
                @Override
                public void destroy() {
                    alive = false;
                    server.stop(0);
                }
            };
            activeProcesses.put(projectName, mockProcess);
            activePorts.put(projectName, port);

            sendEvent(emitter, "started", String.valueOf(port));
            sendEvent(emitter, "console", "✔ Static Web Server Started");
            sendEvent(emitter, "console", "Running Port: " + port);
            sendEvent(emitter, "console", "URL: http://localhost:" + port);

        } catch (IOException e) {
            sendEvent(emitter, "system", "Error starting static file server: " + e.getMessage());
            emitter.complete();
        }
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

