package org.example.reviewer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

public class SlidingWindowEngineTest {

    @Test
    void microFileFitsInSingleWindowWithZeroOverlap() {
        SlidingWindowEngine.AdaptiveConfig config = SlidingWindowEngine.calculateAdaptiveWindow(10);
        assertEquals(10, config.size(), "Window size for 10-line file should equal 10");
        assertEquals(0, config.overlap(), "Overlap for micro-file should be strictly 0");
    }

    @Test
    void smallFileScalesProportionally() {
        SlidingWindowEngine.AdaptiveConfig config = SlidingWindowEngine.calculateAdaptiveWindow(50);
        assertEquals(44, config.size(), "Window size for 50-line file should be 44");
        assertEquals(7, config.overlap(), "Overlap for 44-line window should be 7");
    }

    @Test
    void enterpriseMonolithCalculatesTargetWindow() {
        SlidingWindowEngine.AdaptiveConfig config = SlidingWindowEngine.calculateAdaptiveWindow(294);
        assertEquals(80, config.size(), "Window size for 294-line monolith should be 80");
        assertEquals(14, config.overlap(), "Overlap for 80-line window should be 14");
    }

    @Test
    void largeMonolithIsClampedToMaxWindow() {
        SlidingWindowEngine.AdaptiveConfig config = SlidingWindowEngine.calculateAdaptiveWindow(1000);
        assertEquals(100, config.size(), "Window size should be clamped to W_MAX (100)");
        assertEquals(18, config.overlap(), "Overlap for 100-line window should be 18");
    }

    @Test
    void extremeScaleRemainsBounded() {
        SlidingWindowEngine.AdaptiveConfig config = SlidingWindowEngine.calculateAdaptiveWindow(10000);
        assertEquals(100, config.size(), "Extreme file size should remain bounded at 100");
        assertEquals(18, config.overlap(), "Overlap should remain bounded at 18");
    }

    @Test
    void zeroOrNegativeLinesHandledSafely() {
        SlidingWindowEngine.AdaptiveConfig zeroConfig = SlidingWindowEngine.calculateAdaptiveWindow(0);
        assertEquals(20, zeroConfig.size());
        assertEquals(0, zeroConfig.overlap());

        SlidingWindowEngine.AdaptiveConfig negConfig = SlidingWindowEngine.calculateAdaptiveWindow(-50);
        assertEquals(20, negConfig.size());
        assertEquals(0, negConfig.overlap());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 25, 50, 100, 294, 500, 1000, 5000})
    void stepSizeIsAlwaysPositive(int totalLines) {
        SlidingWindowEngine.AdaptiveConfig config = SlidingWindowEngine.calculateAdaptiveWindow(totalLines);
        int step = config.size() - config.overlap();
        assertTrue(step > 0, "Step size (size - overlap) must always be positive to prevent infinite loops");
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 100, 200, 500, 1000, 3000})
    void overlapNeverExceedsTwentyPercent(int totalLines) {
        SlidingWindowEngine.AdaptiveConfig config = SlidingWindowEngine.calculateAdaptiveWindow(totalLines);
        if (config.overlap() > 0) {
            double ratio = (double) config.overlap() / config.size();
            assertTrue(ratio <= 0.20, "Overlap ratio should not exceed 20% to bound token overhead");
        }
    }
}
