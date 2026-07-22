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

    public ProjectController(FilesystemService filesystemService, StaticAnalysisService staticAnalysisService) {
        this.filesystemService = filesystemService;
        this.staticAnalysisService = staticAnalysisService;
    }

    @GetMapping
    public ResponseEntity<List<String>> listProjects() {
        File root = new File(projectsRoot);
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
            return ResponseEntity.ok(filesystemService.getProjectTree(name));
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
}

