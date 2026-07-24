package com.orvix.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class AIService {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AIService.class);

    @Value("${GEMINI_API_KEY:}")
    private String apiKeyEnv;

    @Value("${orvix.mock-mode.enabled:false}")
    private boolean mockModeEnabled;

    @Value("${GEMINI_MODEL:gemini-3.6-flash}")
    private String modelEnv;

    private static final java.util.List<String> FALLBACK_MODELS = java.util.List.of(
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.5-flash-lite"
    );

    public String getModelName() {
        String envModel = System.getenv("GEMINI_MODEL");
        if (envModel != null && !envModel.isBlank()) {
            return envModel;
        }
        if (modelEnv != null && !modelEnv.isBlank()) {
            return modelEnv;
        }
        return "gemini-3.6-flash";
    }

    @jakarta.annotation.PostConstruct
    public void logDiagnostics() {
        String apiKey = getApiKey();
        String model = getModelName();
        boolean hasKey = !apiKey.isEmpty();
        
        System.out.println("================================");
        System.out.println("Gemini Initialization");
        System.out.println("API Key Present: " + hasKey);
        System.out.println("Model:");
        System.out.println(model);
        System.out.println("SDK Version:");
        System.out.println("N/A (Direct HTTP REST Client)");
        System.out.println("Initialization:");
        System.out.println(hasKey ? "SUCCESS" : "FAILED");
        System.out.println("================================");
        
        logger.info("INFO: Gemini Initialization - API Key Present: {}, Model: {}, Initialization: {}", 
            hasKey, model, hasKey ? "SUCCESS" : "FAILED");
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String getApiKey() {
        if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
            return apiKeyEnv;
        }
        String envKey = System.getenv("GEMINI_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }
        return "";
    }

    public String generateRepositorySummary(ProjectSummary summary, File projectDir) {
        String apiKey = getApiKey();
        logger.info("INFO: generateRepositorySummary - GEMINI_API_KEY is present: {}", !apiKey.isEmpty());
        if (apiKey.isEmpty()) {
            if (mockModeEnabled) {
                logger.info("INFO: Falling back to Mock Repository Summary (Mock Mode enabled).");
                return getMockRepositorySummary(summary);
            }
            logger.error("ERROR: GEMINI_API_KEY is not configured.");
            return "Error: GEMINI_API_KEY is not configured. Please set the GEMINI_API_KEY environment variable in your deployment environment.";
        }

        String prompt = String.format("""
                You are Orvix's Repository Understanding Engine. You have analyzed a project with metadata:
                Name: %s
                Language: %s
                Build Tool: %s
                Framework: %s
                Entry Point: %s
                Key files: %s
                
                Please generate a structured, professional, and visually appealing markdown summary of this repository. Include:
                1. Project Overview & Architecture Style
                2. Entry Point & Component Walkthrough (describe how control flows from the entry point)
                3. Key Technologies & Dependencies
                Make it look premium and tailored to this specific repository context. Keep it concise.
                """,
                summary.name(), summary.language(), summary.buildTool(), summary.framework(), summary.entryPoint(), summary.importantFiles().toString());

        return callGemini(prompt);
    }

    public String chatWithRepository(String message, String currentFileContent, String fileContext, String chatHistory) {
        String apiKey = getApiKey();
        logger.info("INFO: chatWithRepository - GEMINI_API_KEY is present: {}", !apiKey.isEmpty());
        if (apiKey.isEmpty()) {
            if (mockModeEnabled) {
                logger.info("INFO: Falling back to Mock Chat response (Mock Mode enabled).");
                return "🤖 **[MOCK MODE]** Set `GEMINI_API_KEY` to enable real Gemini responses.\n\n" +
                       "I received your question: \"" + message + "\"\n\n" +
                       "Current File Context length: " + (currentFileContent != null ? currentFileContent.length() : 0) + " chars.";
            }
            logger.error("ERROR: GEMINI_API_KEY is not configured.");
            return "Error: GEMINI_API_KEY is not configured. Please set the GEMINI_API_KEY environment variable in your deployment environment.";
        }

        String systemPrompt = """
                You are the Orvix AI Assistant. You are embedded in a browser-based IDE and help developers understand, run, and debug their code.
                You are grounded in the repository context provided. Keep answers concise, highly technical, and practical.
                Always supply direct code examples when relevant.
                """;

        String prompt = String.format("""
                %s
                
                ---
                Context details:
                Current Open File Content:
                ```
                %s
                ```
                
                Relevant File/Structure Context:
                %s
                
                ---
                Previous Chat History:
                %s
                
                User Message: %s
                """, systemPrompt, currentFileContent, fileContext, chatHistory, message);

        return callGemini(prompt);
    }

    public String explainDiagnostics(String filePath, String errorCodeLine, String errorDetail) {
        String apiKey = getApiKey();
        logger.info("INFO: explainDiagnostics - GEMINI_API_KEY is present: {}", !apiKey.isEmpty());
        if (apiKey.isEmpty()) {
            if (mockModeEnabled) {
                logger.info("INFO: Falling back to Mock Diagnostics Explanation (Mock Mode enabled).");
                return "🤖 **[MOCK MODE]** Set `GEMINI_API_KEY` to explain diagnostics.\n\n" +
                       "**Error at:** `" + filePath + "`\n" +
                       "**Code Line:** `" + errorCodeLine + "`\n" +
                       "**Detail:** `" + errorDetail + "`\n\n" +
                       "**Root Cause:** This is a mock explanation. Make sure your variables are initialized and types match.";
            }
            logger.error("ERROR: GEMINI_API_KEY is not configured.");
            return "Error: GEMINI_API_KEY is not configured. Please set the GEMINI_API_KEY environment variable in your deployment environment.";
        }

        String prompt = String.format("""
                Explain the following compiler or diagnostics error in a friendly, helpful developer-focused way:
                File: %s
                Line with Error: %s
                Diagnostic message: %s
                
                Include the root cause, why this happened, and suggest a conceptual fix. Keep it short.
                """, filePath, errorCodeLine, errorDetail);

        return callGemini(prompt);
    }

    public String generateFix(String filePath, String fileContent, String errorDetail, int errorLine) {
        String apiKey = getApiKey();
        logger.info("INFO: generateFix - GEMINI_API_KEY is present: {}", !apiKey.isEmpty());
        if (apiKey.isEmpty()) {
            if (mockModeEnabled) {
                logger.info("INFO: Falling back to Mock Fix (Mock Mode enabled).");
                try {
                    ObjectNode mockJson = objectMapper.createObjectNode();
                    mockJson.put("explanation", "Mock Fix: Checked for null validation on the error line.");
                    mockJson.put("originalCode", "// Error line\n" + (fileContent.lines().skip(Math.max(0, errorLine - 2)).limit(3).reduce("", (a, b) -> a + "\n" + b)));
                    mockJson.put("proposedCode", "// Fixed line with null validation\n// Code replaced successfully in mock mode");
                    return objectMapper.writeValueAsString(mockJson);
                } catch (Exception e) {
                    return "{}";
                }
            }
            logger.error("ERROR: GEMINI_API_KEY is not configured.");
            return "{\"error\": \"GEMINI_API_KEY is not configured. Please set the GEMINI_API_KEY environment variable in your deployment environment.\"}";
        }

        String prompt = String.format("""
                You are the Orvix AI Fix Engine. An error occurred in the file %s:
                Error details: %s
                Error line number: %d
                
                Full file content:
                ```java
                %s
                ```
                
                Suggest a fix for this error. Respond ONLY with a JSON object. No markdown wrapping.
                JSON structure:
                {
                  "explanation": "Brief explanation of the error and proposed fix",
                  "originalCode": "The exact block of code that contains the error (keep it to 3-10 lines to show context)",
                  "proposedCode": "The exact replacement block of code that resolves the error"
                }
                """, filePath, errorDetail, errorLine, fileContent);

        return callGemini(prompt);
    }

    public String analyzeRuntimeException(java.util.List<String> logs, File projectDir) {
        String apiKey = getApiKey();
        logger.info("INFO: analyzeRuntimeException - GEMINI_API_KEY is present: {}", !apiKey.isEmpty());
        if (apiKey.isEmpty()) {
            if (mockModeEnabled) {
                logger.info("INFO: Falling back to Mock Runtime Exception Analysis (Mock Mode enabled).");
                try {
                    ObjectNode mockJson = objectMapper.createObjectNode();
                    mockJson.put("rootCause", "Mock Exception: NullPointerException encountered.");
                    mockJson.put("affectedFile", "src/main/java/com/orvix/OrvixApplication.java");
                    mockJson.put("line", 11);
                    mockJson.put("impact", "Application crashed immediately during initialization.");
                    mockJson.put("fixRecommendation", "Check if environment configurations or dependency beans are correctly mapped.");
                    mockJson.put("confidenceScore", 95);
                    return objectMapper.writeValueAsString(mockJson);
                } catch (Exception e) {
                    return "{}";
                }
            }
            logger.error("ERROR: GEMINI_API_KEY is not configured.");
            return "{\"error\": \"GEMINI_API_KEY is not configured. Please set the GEMINI_API_KEY environment variable in your deployment environment.\"}";
        }

        String prompt = String.format("""
                You are Orvix's Runtime Exception Analyzer. A Java application failed during execution.
                Analyze the following logs and tracebacks:
                ```
                %s
                ```
                
                Identify the root cause of the crash. Respond ONLY with a JSON object. No markdown wrapping.
                JSON structure:
                {
                  "rootCause": "Clear explanation of why the application crashed",
                  "affectedFile": "Path to the source file where the exception originated (e.g. src/main/java/com/example/App.java)",
                  "line": 87, // line number where the exception occurred (integer)
                  "impact": "Brief impact description of the crash on the application state",
                  "fixRecommendation": "Direct recommendation of how to fix this exception",
                  "confidenceScore": 95 // confidence percentage (integer)
                }
                """, String.join("\n", logs.subList(Math.max(0, logs.size() - 50), logs.size())));

        return callGemini(prompt);
    }

    private HttpResponse<String> sendGeminiRequest(String prompt, String model) {
        try {
            String apiKey = getApiKey();
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            logger.info("INFO: Sending request to Gemini API. Model: {}, Prompt length: {} characters", model, prompt.length());

            ObjectNode textNode = objectMapper.createObjectNode().put("text", prompt);
            ObjectNode partNode = objectMapper.createObjectNode();
            partNode.set("parts", objectMapper.createArrayNode().add(textNode));
            ObjectNode contentNode = objectMapper.createObjectNode();
            contentNode.set("contents", objectMapper.createArrayNode().add(partNode));

            String payload = objectMapper.writeValueAsString(contentNode);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            logger.error("ERROR: Gemini API request exception for model " + model, e);
            return null;
        }
    }

    private String parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode textResult = candidates.get(0)
                        .path("content")
                        .path("parts")
                        .get(0)
                        .path("text");
                return textResult.asText();
            }
        } catch (Exception e) {
            logger.error("ERROR: Failed to parse Gemini response: " + responseBody, e);
        }
        return "⚠️ Failed to parse Gemini response: " + responseBody;
    }

    private String callGemini(String prompt) {
        String primaryModel = getModelName();
        HttpResponse<String> response = sendGeminiRequest(prompt, primaryModel);
        
        if (response != null && response.statusCode() == 404) {
            String warningMsg = "The configured Gemini model is unavailable. Retrying with a supported model...";
            logger.warn("WARN: {}", warningMsg);
            
            // Loop through fallback models
            for (String fallbackModel : FALLBACK_MODELS) {
                if (fallbackModel.equals(primaryModel)) {
                    continue; // Skip the one that just failed
                }
                
                logger.info("INFO: Primary model failed ({}) fallback model used: {}", primaryModel, fallbackModel);
                HttpResponse<String> fallbackResponse = sendGeminiRequest(prompt, fallbackModel);
                if (fallbackResponse != null) {
                    if (fallbackResponse.statusCode() == 200) {
                        return parseResponse(fallbackResponse.body());
                    } else {
                        logger.error("ERROR: Fallback model {} failed with status: {}", fallbackModel, fallbackResponse.statusCode());
                    }
                }
            }
            
            return "⚠️ The configured Gemini model is unavailable. Retrying with a supported model... Fallback failed.";
        }
        
        if (response != null) {
            if (response.statusCode() == 200) {
                return parseResponse(response.body());
            } else {
                logger.error("ERROR: Gemini API error response (Status: {}): {}", response.statusCode(), response.body());
                return "⚠️ Gemini API Error (Status: " + response.statusCode() + "): " + response.body();
            }
        }
        
        return "⚠️ Gemini API Request Exception: Empty response";
    }

    private String getMockRepositorySummary(ProjectSummary summary) {
        return String.format("""
                # Project Overview: %s
                
                This repository is recognized as a **%s** using **%s** as its build manager.
                
                ## Architecture Style
                - **Style**: Layered Architecture or Spring MVC pattern.
                - **Source layout**: Matches standard Maven directory conventions (`src/main/java`).
                
                ## Entry Point
                - **Main Class**: `%s`
                
                ## Tech Stack & Core Libraries
                - **Language**: Java 21+
                - **Build System**: %s
                - **Framework**: %s
                
                > [!NOTE]
                > To enable custom, real-time Gemini project explanations, set the `GEMINI_API_KEY` environment variable in your backend console environment.
                """,
                summary.name(), summary.framework(), summary.buildTool(), summary.entryPoint(), summary.buildTool(), summary.framework());
    }
}

