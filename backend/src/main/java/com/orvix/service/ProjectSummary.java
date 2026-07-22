package com.orvix.service;

import java.util.List;

public record ProjectSummary(
    String name,
    String language,
    String buildTool,
    String framework,
    String entryPoint,
    List<String> importantFiles,
    String path
) {}

