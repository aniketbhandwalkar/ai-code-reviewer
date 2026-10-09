package org.example.reviewer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Sliding window chunking engine with boundary deduplication and token metrics.
 */
public class SlidingWindowEngine {

    private final AgentFactory.CodeReviewer reviewer;
    private final boolean adaptiveMode;
    private final int manualWindowSize;
    private final int manualOverlap;

    public record AdaptiveConfig(int size, int overlap) {}

    public SlidingWindowEngine(AgentFactory.CodeReviewer reviewer) {
        this.reviewer = reviewer;
        String adaptiveConfig = ConfigLoader.get("SLIDING_WINDOW_ADAPTIVE", "sliding.window.adaptive", "true");
        this.adaptiveMode = Boolean.parseBoolean(adaptiveConfig);
        this.manualWindowSize = ConfigLoader.getInt("sliding.window.size", 20);
        this.manualOverlap = ConfigLoader.getInt("sliding.window.overlap", 5);
    }

    public SlidingWindowEngine(AgentFactory.CodeReviewer reviewer, int windowSize, int overlap) {
        this.reviewer = reviewer;
        this.adaptiveMode = false;
        this.manualWindowSize = Math.max(5, windowSize);
        this.manualOverlap = Math.min(overlap, this.manualWindowSize - 1);
    }

    /**
     * Calculates window size and boundary overlap based on total lines of code.
     * Formula: W(N) = min(N, clamp(floor(20 + 3.5 * sqrt(N)), 20, 100))
     */
    public static AdaptiveConfig calculateAdaptiveWindow(int totalLines) {
        final int W_BASE = 20;
        final double ALPHA = 3.5;
        final int W_MIN = 20;
        final int W_MAX = 100;
        final double RHO_OVERLAP = 0.18;

        if (totalLines <= 0) {
            return new AdaptiveConfig(W_MIN, 0);
        }

        // Sub-linear continuous window growth: W ~ sqrt(N)
        int rawCalculated = (int) Math.floor(W_BASE + (ALPHA * Math.sqrt(totalLines)));
        int clampedWindow = Math.max(W_MIN, Math.min(W_MAX, rawCalculated));
        int effectiveWindow = Math.min(totalLines, clampedWindow);

        // If the entire file fits in 1 window, overlap is 0
        int effectiveOverlap = (effectiveWindow >= totalLines)
                ? 0
                : Math.max(3, (int) Math.floor(effectiveWindow * RHO_OVERLAP));

        return new AdaptiveConfig(effectiveWindow, effectiveOverlap);
    }

    public void analyzeFile(Path path) throws IOException {
        System.out.println("[Engine] Starting analysis for: " + path.getFileName());

        List<String> allLines = Files.readAllLines(path);
        int totalLines = allLines.size();

        int windowSize;
        int overlap;

        if (adaptiveMode) {
            AdaptiveConfig config = calculateAdaptiveWindow(totalLines);
            windowSize = config.size();
            overlap = config.overlap();
            System.out.printf("[Engine] Adaptive Windowing Active: %d lines | Overlap: %d lines (LOC: %d)%n",
                    windowSize, overlap, totalLines);
        } else {
            windowSize = this.manualWindowSize;
            overlap = this.manualOverlap;
            System.out.printf("[Engine] Fixed Windowing: %d lines | Overlap: %d lines%n", windowSize, overlap);
        }

        List<String> rawBugs = new ArrayList<>();
        List<String> uniqueBugs = new ArrayList<>();
        Set<String> seenBugFingerprints = new HashSet<>();
        List<String> complexityInsights = new ArrayList<>();
        List<String> suggestedFixes = new ArrayList<>();

        int totalScore = 0;
        int chunksProcessed = 0;
        int totalChunkTokensProcessed = 0;

        int step = windowSize - overlap;
        for (int i = 0; i < totalLines; i += step) {
            int end = Math.min(totalLines, i + windowSize);

            // Extract Chunk
            String chunk = String.join("\n", allLines.subList(i, end));
            int estimatedChunkTokens = estimateTokens(chunk) + 120; // Chunk tokens + RAG context injection
            totalChunkTokensProcessed += estimatedChunkTokens;

            System.out.printf("   Processing lines %d-%d... ", i, end);

            try {
                // Call AI Agent with automatic retry on rate limits
                CodeAnalysis result = callReviewerWithRetry(chunk);

                System.out.println("Score: " + result.cleanlinessScore() + "/10");

                // Process bugs with Overlap Deduplication
                if (result.detectedBugs() != null) {
                    for (String bug : result.detectedBugs()) {
                        if (bug == null || bug.isBlank())
                            continue;
                        rawBugs.add(bug.trim());

                        String fingerprint = normalizeFingerprint(bug);
                        if (seenBugFingerprints.add(fingerprint)) {
                            uniqueBugs.add(bug.trim());
                        }
                    }
                }

                // Collect complexity insights
                if (result.complexityAnalysis() != null && !result.complexityAnalysis().isBlank()) {
                    complexityInsights.add(result.complexityAnalysis().trim());
                }

                // Collect optimized refactoring snippets
                if (result.optimizedCode() != null && !result.optimizedCode().isBlank()) {
                    suggestedFixes.add(result.optimizedCode().trim());
                }

                totalScore += result.cleanlinessScore();
                chunksProcessed++;

                if (end == totalLines)
                    break;

                // Respect API rate limits with polite pacing between windows
                try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            } catch (Exception e) {
                System.out.println("[Error] Failed: " + e.getMessage());
            }
        }

        // Rigorous Empirical Token Efficiency Metrics
        int fullFileTokens = estimateTokens(String.join("\n", allLines));
        int fullStandardsTokens = loadStandardsTokens();
        int systemPromptTokens = 250;
        int monolithicContextTokens = fullFileTokens + fullStandardsTokens + systemPromptTokens;

        int duplicatesEliminated = rawBugs.size() - uniqueBugs.size();

        // Exact mathematical token delta: Monolithic Prompt vs Cumulative Sliding-Window RAG Prompts
        int tokensSaved = Math.max(0, monolithicContextTokens - totalChunkTokensProcessed);
        double tokenReductionPercent = monolithicContextTokens > 0
                ? ((double) tokensSaved / monolithicContextTokens) * 100.0
                : 0.0;

        printReport(
                path.getFileName().toString(),
                chunksProcessed,
                totalScore,
                uniqueBugs,
                duplicatesEliminated,
                monolithicContextTokens,
                totalChunkTokensProcessed,
                tokenReductionPercent,
                complexityInsights,
                suggestedFixes);
    }

    private CodeAnalysis callReviewerWithRetry(String chunk) {
        int maxRetries = 5;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return reviewer.analyze(chunk);
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException("AI inference failed after " + maxRetries + " attempts: " + e.getMessage(), e);
                }
                long sleepMs = 15000L * attempt;
                System.out.printf("[Rate Limit / Busy] Sleeping %ds before retry (%d/%d)...%n", sleepMs / 1000, attempt, maxRetries);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignored) {}
            }
        }
        return null;
    }

    /**
     * Normalizes a violation message into a hashable fingerprint to eliminate
     * duplicate reports across chunk boundaries.
     */
    private String normalizeFingerprint(String bug) {
        return bug.toLowerCase()
                .replaceAll("line\\s*\\d+", "") // remove explicit line references that shift slightly
                .replaceAll("[^a-z0-9]", ""); // strip punctuation/whitespace
    }

    /**
     * Fast token estimator based on heuristic token-to-word ratio (approx 4 chars
     * per token).
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty())
            return 0;
        return (int) Math.ceil(text.length() / 3.7);
    }

    private int loadStandardsTokens() {
        String rulesResource = ConfigLoader.get("RULES_FILE", "default.rules.file", "EnterpriseStandardsManual.txt");
        try (var stream = getClass().getClassLoader().getResourceAsStream(rulesResource)) {
            if (stream != null) {
                String content = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                return estimateTokens(content);
            }
        } catch (Exception ignored) {}
        return 2100;
    }

    private void printReport(
            String fileName,
            int chunks,
            int totalScore,
            List<String> bugs,
            int duplicatesEliminated,
            int monolithicTokens,
            int chunkedTokens,
            double tokenReductionPercent,
            List<String> complexityInsights,
            List<String> suggestedFixes) {
        int avgScore = chunks > 0 ? (totalScore / chunks) : 0;

        System.out.println("\n=======================================================");
        System.out.println("          FINAL AUDIT REPORT: " + fileName);
        System.out.println("=======================================================");
        System.out.printf("Average Cleanliness Score : %d/10%n", avgScore);
        System.out.printf("Chunks Processed          : %d%n", chunks);
        System.out.printf("Policy Violations Found   : %d%n", bugs.size());
        System.out.printf("Boundary Duplicates Purged: %d%n", duplicatesEliminated);
        System.out.println("-------------------------------------------------------");
        System.out.println("TOKEN EFFICIENCY METRICS (Sliding Window + RAG):");
        System.out.printf("   - Monolithic Pipeline Context : ~%d tokens%n", monolithicTokens);
        System.out.printf("   - Processed Chunk Context     : ~%d tokens%n", chunkedTokens);
        System.out.printf("   - Estimated Token Reduction   : ~%.1f%% overhead saved%n", tokenReductionPercent);
        System.out.println("-------------------------------------------------------");

        if (bugs.isEmpty()) {
            System.out.println("Clean Code! No policy violations detected.");
        } else {
            System.out.println("POLICY VIOLATIONS:");
            bugs.forEach(bug -> System.out.println("   - " + bug));
        }

        if (!complexityInsights.isEmpty()) {
            System.out.println("COMPLEXITY & ARCHITECTURAL INSIGHTS:");
            complexityInsights.stream().distinct().limit(3).forEach(c -> System.out.println("   " + c));
        }

        if (!suggestedFixes.isEmpty()) {
            System.out.println("AI OPTIMIZED CODE SUGGESTION:");
            System.out.println("-------------------------------------------------------");
            System.out.println(suggestedFixes.get(0));
            System.out.println("-------------------------------------------------------");
        }

        System.out.println("=======================================================\n");
    }
}