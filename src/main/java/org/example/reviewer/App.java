package org.example.reviewer;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class App {

    public static void main(String[] args) {
        System.out.println("=======================================================");
        System.out.println(" 🛡️  AI Code Reviewer - Enterprise Compliance Agent");
        System.out.println("=======================================================");

        // 1. Resolve API Keys (Env Vars > application.properties)
        String googleKey = ConfigLoader.get("GOOGLE_API_KEY", "google.api.key", "");
        String groqKey = ConfigLoader.get("GROQ_API_KEY", "groq.api.key", "");

        if (isKeyMissing(googleKey) || isKeyMissing(groqKey)) {
            printMissingKeyInstructions();
            return;
        }

        // 2. Resolve Target Source Code File (CLI Arg > Default BadCode.java)
        Path targetFile = resolveTargetFile(args);
        if (targetFile == null) {
            return;
        }

        // 3. Resolve Standards / Rules File (CLI Arg > Config > Default Standards.txt)
        String standardsPath = resolveStandardsPath(args);

        // 4. Initialize AI Agent & Sliding Window Engine
        try {
            var reviewer = AgentFactory.createReviewer(googleKey, groqKey, standardsPath);
            var engine = new SlidingWindowEngine(reviewer);

            engine.analyzeFile(targetFile);
        } catch (Exception e) {
            System.err.println("❌ Critical Error during execution: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static boolean isKeyMissing(String key) {
        return key == null || key.isBlank() || key.contains("GEMINI_API_KEY") || key.contains("GROQ_API_KEY");
    }

    private static Path resolveTargetFile(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            Path cliPath = Paths.get(args[0]);
            if (Files.exists(cliPath)) {
                return cliPath;
            } else {
                System.err.println("⚠️ Specified target file not found: " + cliPath.toAbsolutePath());
                System.err.println("Falling back to bundled sample: BadCode.java\n");
            }
        }

        URL codeUrl = App.class.getClassLoader().getResource("BadCode.java");
        if (codeUrl == null) {
            System.err.println("❌ Error: Sample BadCode.java not found in resources!");
            return null;
        }
        try {
            return Paths.get(codeUrl.toURI());
        } catch (Exception e) {
            throw new RuntimeException("Failed to locate default sample code: " + e.getMessage(), e);
        }
    }

    private static String resolveStandardsPath(String[] args) {
        if (args.length > 1 && !args[1].isBlank()) {
            File customRules = new File(args[1]);
            if (customRules.exists()) {
                return customRules.getAbsolutePath();
            }
        }

        String rulesResource = ConfigLoader.get("RULES_FILE", "default.rules.file", "Standards.txt");
        URL resourceUrl = AgentFactory.class.getClassLoader().getResource(rulesResource);
        if (resourceUrl != null) {
            try {
                return Paths.get(resourceUrl.toURI()).toString();
            } catch (Exception ignored) {}
        }
        return rulesResource;
    }

    private static void printMissingKeyInstructions() {
        System.err.println("""
            ❌ Missing API Keys!
            -------------------------------------------------------
            Please provide valid API keys via one of these two ways:
            
            1. System Environment Variables:
               • GOOGLE_API_KEY (from https://aistudio.google.com/)
               • GROQ_API_KEY   (from https://console.groq.com/)
               
            2. Local Configuration File:
               Edit: src/main/resources/application.properties
               google.api.key=YOUR_KEY_HERE
               groq.api.key=YOUR_KEY_HERE
            -------------------------------------------------------
            """);
    }
}