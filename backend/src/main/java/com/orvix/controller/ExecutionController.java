package com.orvix.controller;

import com.orvix.service.ExecutionService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/projects")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @GetMapping(value = "/{name}/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter runProject(@PathVariable String name) {
        // SSE timeout of 10 minutes for running projects
        SseEmitter emitter = new SseEmitter(600_000L);
        executionService.runProject(name, emitter);
        return emitter;
    }

    @PostMapping("/{name}/stop")
    public ResponseEntity<Void> stopProject(@PathVariable String name) {
        executionService.stopProject(name);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{name}/port")
    public ResponseEntity<Integer> getProjectPort(@PathVariable String name) {
        Integer port = executionService.getActivePort(name);
        if (port == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(port);
    }

    @GetMapping("/{name}/health")
    public ResponseEntity<Map<String, Object>> checkHealth(@PathVariable String name) {
        Integer port = executionService.getActivePort(name);
        if (port == null) {
            return ResponseEntity.notFound().build();
        }
        
        // 1. Try Actuator first
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + port + "/actuator/health").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            int code = conn.getResponseCode();
            if (code == 200) {
                return ResponseEntity.ok(Map.of("status", "UP", "statusCode", code, "message", "Actuator health check passed"));
            }
        } catch (Exception e) {
            // ignore and fallback to root check
        }

        // 2. Fallback to root endpoint check
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL("http://localhost:" + port + "/").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            int code = conn.getResponseCode();
            // Any response code from the port (e.g. 200, 302, 404) means the server is actively listening!
            return ResponseEntity.ok(Map.of("status", "UP", "statusCode", code, "message", "Root endpoint responded"));
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of("status", "OFFLINE", "error", ex.getMessage()));
        }
    }

    @RequestMapping(value = "/{name}/proxy/**")
    public ResponseEntity<byte[]> proxyRequest(
            @PathVariable String name,
            jakarta.servlet.http.HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {
        
        Integer port = executionService.getActivePort(name);
        if (port == null) {
            return ResponseEntity.status(404).body("Project is not running.".getBytes());
        }

        String uri = request.getRequestURI();
        String searchStr = "/proxy";
        int idx = uri.indexOf(searchStr);
        String subPath = "";
        if (idx != -1) {
            subPath = uri.substring(idx + searchStr.length());
        }
        if (request.getQueryString() != null) {
            subPath += "?" + request.getQueryString();
        }

        try {
            String targetUrl = "http://localhost:" + port + subPath;
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(targetUrl).openConnection();
            conn.setRequestMethod(request.getMethod());
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            
            // Copy request headers
            java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (headerName.equalsIgnoreCase("host") || headerName.equalsIgnoreCase("connection")) {
                    continue;
                }
                conn.setRequestProperty(headerName, request.getHeader(headerName));
            }

            // Copy body if present
            if (body != null && body.length > 0) {
                conn.setDoOutput(true);
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }

            int statusCode = conn.getResponseCode();
            
            // Read response
            java.io.InputStream is;
            if (statusCode >= 400) {
                is = conn.getErrorStream();
            } else {
                is = conn.getInputStream();
            }

            byte[] responseBytes = new byte[0];
            if (is != null) {
                responseBytes = is.readAllBytes();
            }

            // Copy response headers
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            conn.getHeaderFields().forEach((k, v) -> {
                if (k != null && !k.equalsIgnoreCase("transfer-encoding") && !k.equalsIgnoreCase("connection")) {
                    headers.put(k, v);
                }
            });

            return new ResponseEntity<>(responseBytes, headers, org.springframework.http.HttpStatus.valueOf(statusCode));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(("Proxy Error: " + e.getMessage()).getBytes());
        }
    }
}
