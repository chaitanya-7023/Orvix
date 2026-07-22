package com.orvix.service;

import org.springframework.stereotype.Service;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
public class DiagnosticsService {

    private final FilesystemService filesystemService;

    public DiagnosticsService(FilesystemService filesystemService) {
        this.filesystemService = filesystemService;
    }

    public List<DiagnosticMarker> diagnoseFile(String projectName, String relativePath, String content) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return Collections.singletonList(new DiagnosticMarker(
                    1, 1, "WARNING", "Warning", 100, "System Java Compiler not available. Running on JRE?", "SYS_WARN"
            ));
        }

        File projectDir = filesystemService.getProjectDirectory(projectName);
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("orvix_diagnostics_");
        } catch (IOException e) {
            return Collections.emptyList();
        }

        try {
            String fileName = new File(relativePath).getName();
            Path tempFile = tempDir.resolve(fileName);
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ENGLISH, StandardCharsets.UTF_8);

            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromPaths(List.of(tempFile));
            
            List<String> options = new ArrayList<>();
            File classesDir = new File(projectDir, "target/classes");
            if (classesDir.exists()) {
                options.add("-classpath");
                options.add(classesDir.getAbsolutePath());
            }

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    compilationUnits
            );

            task.call();

            List<DiagnosticMarker> markers = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                long lineNumber = diagnostic.getLineNumber();
                long columnNumber = diagnostic.getColumnNumber();
                String severity = diagnostic.getKind().name();
                String message = diagnostic.getMessage(Locale.ENGLISH);
                String code = diagnostic.getCode() != null ? diagnostic.getCode() : "COMPILER_ERROR";

                String severityLabel = "Low";
                int confidence = 90;
                if (severity.equals("ERROR")) {
                    severityLabel = "High";
                    confidence = 100;
                } else if (severity.equals("WARNING")) {
                    severityLabel = "Medium";
                    confidence = 95;
                }

                markers.add(new DiagnosticMarker(
                        (int) lineNumber,
                        (int) columnNumber,
                        severity,
                        severityLabel,
                        confidence,
                        message,
                        code
                ));
            }

            // Run additional semantic linter checks
            runLinter(content, markers);

            fileManager.close();
            return markers;

        } catch (Exception e) {
            return Collections.singletonList(new DiagnosticMarker(
                    1, 1, "ERROR", "High", 100, "Diagnostics evaluation error: " + e.getMessage(), "EVAL_ERR"
            ));
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    private void runLinter(String content, List<DiagnosticMarker> markers) {
        String[] lines = content.split("\\r?\\n");
        
        // 1. Check for unused imports
        List<String> importClassNames = new ArrayList<>();
        List<Integer> importLines = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("import ") && line.endsWith(";")) {
                String fullImport = line.substring(7, line.length() - 1);
                String className = fullImport.substring(fullImport.lastIndexOf('.') + 1);
                if (!className.equals("*")) {
                    importClassNames.add(className);
                    importLines.add(i + 1);
                }
            }
        }

        for (int idx = 0; idx < importClassNames.size(); idx++) {
            String className = importClassNames.get(idx);
            int lineNum = importLines.get(idx);
            
            int occurrences = 0;
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b" + className + "\\b");
            java.util.regex.Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                occurrences++;
            }
            if (occurrences <= 1) {
                markers.add(new DiagnosticMarker(
                        lineNum, 1, "WARNING", "Low", 95,
                        "Unused import statement: '" + className + "'", "UNUSED_IMPORT"
                ));
            }
        }

        // 2. Check for unused local variables
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.matches("(int|double|boolean|String|long|char)\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s*=.*;")) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String varName = parts[1];
                    if (varName.contains("=")) {
                        varName = varName.substring(0, varName.indexOf("=")).trim();
                    }
                    varName = varName.replaceAll("[^a-zA-Z0-9_]", "");
                    
                    int occurrences = 0;
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b" + varName + "\\b");
                    java.util.regex.Matcher matcher = pattern.matcher(content);
                    while (matcher.find()) {
                        occurrences++;
                    }
                    if (occurrences <= 1) {
                        markers.add(new DiagnosticMarker(
                                i + 1, line.indexOf(varName) + 1, "WARNING", "Low", 85,
                                "Unused local variable: '" + varName + "'", "UNUSED_VAR"
                        ));
                    }
                }
            }
        }

        // 3. Potential null pointer risks
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.matches(".*[a-zA-Z0-9_]+\\s*=\\s*null\\s*;.*")) {
                int eqIdx = line.indexOf('=');
                String sub = line.substring(0, eqIdx).trim();
                String[] parts = sub.split("\\s+");
                String varName = parts[parts.length - 1];
                
                for (int j = i + 1; j < Math.min(lines.length, i + 15); j++) {
                    String nextLine = lines[j].trim();
                    if (nextLine.contains(varName + ".")) {
                        if (!nextLine.contains("!= null") && !nextLine.contains("null !=") && !nextLine.contains("Objects.requireNonNull")) {
                            markers.add(new DiagnosticMarker(
                                    j + 1, lines[j].indexOf(varName + ".") + 1, "WARNING", "Medium", 98,
                                    "Potential NullPointerException: '" + varName + "' is dereferenced here but was assigned null on line " + (i + 1), "POTENTIAL_NPE"
                            ));
                        }
                    }
                    if (nextLine.matches(".*\\b" + varName + "\\s*=\\s*[^=;]+;.*") && !nextLine.contains("null")) {
                        break;
                    }
                }
            }
        }
    }

    private void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    public static record DiagnosticMarker(
            int line,
            int column,
            String severity,
            String severityLabel,
            int confidence,
            String message,
            String code
    ) {}
}

