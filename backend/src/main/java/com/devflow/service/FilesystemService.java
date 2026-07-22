package com.devflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class FilesystemService {

    @Value("${devflow.projects-root}")
    private String projectsRoot;

    public FileNode getProjectTree(String projectName) throws IOException {
        File projectDir = getProjectDirectory(projectName);
        if (!projectDir.exists()) {
            throw new IllegalArgumentException("Project directory does not exist: " + projectName);
        }
        return buildNode(projectDir, projectDir.toPath());
    }

    private FileNode buildNode(File file, Path rootPath) {
        String name = file.getName();
        String relativePath = rootPath.relativize(file.toPath()).toString().replace('\\', '/');
        
        // Root folder has empty relative path, assign its name
        if (relativePath.isEmpty()) {
            relativePath = "";
        }

        boolean isDirectory = file.isDirectory();
        long size = isDirectory ? 0 : file.length();
        List<FileNode> children = null;

        if (isDirectory) {
            children = new ArrayList<>();
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    // Ignore VCS and IDE config folders to keep the tree clean
                    if (child.getName().equals(".git") || child.getName().equals(".idea") || child.getName().equals(".settings") || child.getName().equals("target") || child.getName().equals("bin")) {
                        continue;
                    }
                    children.add(buildNode(child, rootPath));
                }
            }
            // Sort directories first, then alphabetically
            children.sort((a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.name().compareToIgnoreCase(b.name());
            });
        }

        return new FileNode(name, relativePath, isDirectory, size, children);
    }

    public String readFile(String projectName, String relativePath) throws IOException {
        File targetFile = new File(getProjectDirectory(projectName), relativePath);
        if (!targetFile.exists() || targetFile.isDirectory()) {
            throw new IllegalArgumentException("File not found: " + relativePath);
        }
        return Files.readString(targetFile.toPath());
    }

    public void writeFile(String projectName, String relativePath, String content) throws IOException {
        File targetFile = new File(getProjectDirectory(projectName), relativePath);
        if (targetFile.isDirectory()) {
            throw new IllegalArgumentException("Target is a directory: " + relativePath);
        }
        // Ensure parent directories exist
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Files.writeString(targetFile.toPath(), content);
    }

    public void createNode(String projectName, String relativePath, boolean isFolder) throws IOException {
        File target = new File(getProjectDirectory(projectName), relativePath);
        if (target.exists()) {
            throw new IllegalArgumentException("Path already exists: " + relativePath);
        }
        if (isFolder) {
            target.mkdirs();
        } else {
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            target.createNewFile();
        }
    }

    public void deleteNode(String projectName, String relativePath) throws IOException {
        File target = new File(getProjectDirectory(projectName), relativePath);
        if (!target.exists()) {
            throw new IllegalArgumentException("Path does not exist: " + relativePath);
        }
        if (target.isDirectory()) {
            deleteRecursively(target);
        } else {
            target.delete();
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

    public void renameNode(String projectName, String relativePath, String newName) throws IOException {
        File target = new File(getProjectDirectory(projectName), relativePath);
        if (!target.exists()) {
            throw new IllegalArgumentException("Path does not exist: " + relativePath);
        }
        File newTarget = new File(target.getParentFile(), newName);
        if (newTarget.exists()) {
            throw new IllegalArgumentException("Destination already exists: " + newName);
        }
        Files.move(target.toPath(), newTarget.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public void moveNode(String projectName, String sourcePath, String targetDirectoryPath) throws IOException {
        File projectDir = getProjectDirectory(projectName);
        File sourceFile = new File(projectDir, sourcePath);
        if (!sourceFile.exists()) {
            throw new IllegalArgumentException("Source file does not exist: " + sourcePath);
        }

        File targetDir = new File(projectDir, targetDirectoryPath);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        } else if (!targetDir.isDirectory()) {
            throw new IllegalArgumentException("Target is not a directory: " + targetDirectoryPath);
        }

        File destination = new File(targetDir, sourceFile.getName());
        if (destination.exists()) {
            throw new IllegalArgumentException("Destination file already exists: " + destination.getName());
        }

        Files.move(sourceFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public FileMetadata getFileMetadata(String projectName, String relativePath) throws IOException {
        File projectDir = getProjectDirectory(projectName);
        File targetFile = new File(projectDir, relativePath);
        if (!targetFile.exists()) {
            throw new IllegalArgumentException("File does not exist: " + relativePath);
        }

        String name = targetFile.getName();
        String type = name.contains(".") ? name.substring(name.lastIndexOf(".") + 1) : "unknown";
        long size = targetFile.length();
        long lineCount = 0;
        List<String> dependencies = new ArrayList<>();
        List<String> references = new ArrayList<>();

        if (targetFile.isFile()) {
            List<String> lines = Files.readAllLines(targetFile.toPath());
            lineCount = lines.size();
            for (String line : lines) {
                if (line.trim().startsWith("import ") && line.trim().endsWith(";")) {
                    String dep = line.trim().substring(7, line.trim().length() - 1);
                    dependencies.add(dep);
                }
            }

            String className = name.contains(".") ? name.substring(0, name.lastIndexOf(".")) : name;
            if (type.equals("java")) {
                try (var walk = Files.walk(projectDir.toPath())) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !p.toFile().equals(targetFile))
                        .forEach(p -> {
                            try {
                                String content = Files.readString(p);
                                if (content.matches("(?s).*\\b" + className + "\\b.*")) {
                                    String rel = projectDir.toPath().relativize(p).toString().replace('\\', '/');
                                    references.add(rel);
                                }
                            } catch (IOException e) {
                                // ignore
                            }
                        });
                }
            }
        }

        long lastModified = targetFile.lastModified();
        return new FileMetadata(name, type, size, lineCount, lastModified, dependencies, references);
    }

    public File getProjectDirectory(String projectName) {
        // Enforce basic path traversal protection
        if (projectName.contains("..") || projectName.contains("/") || projectName.contains("\\")) {
            throw new SecurityException("Invalid project name");
        }
        return new File(projectsRoot, projectName);
    }

    public static record FileNode(
        String name,
        String path,
        boolean isDirectory,
        long size,
        List<FileNode> children
    ) {}

    public static record FileMetadata(
        String name,
        String type,
        long size,
        long lineCount,
        long lastModified,
        List<String> dependencies,
        List<String> references
    ) {}
}
