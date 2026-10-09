# Mathematical Formulation: Adaptive Sub-Linear Sliding Window Engine

This document outlines the mathematical foundation, parameter derivations, algorithmic rationale, and architectural proofs behind the dynamic sliding-window chunking engine used in the AI Code Reviewer.

---

## 0. Quick Plain-Text Formula (Read at a Glance)

```text
Window_Size  = Min( N,  Clamp( 20 + 3.5 * sqrt(N),  Min = 20,  Max = 100 ) )

Overlap_Size = (Window_Size >= N) ? 0 : Max( 3,  Floor( Window_Size * 0.18 ) )
```

* **N**: Total lines in the file.
* **sqrt(N)**: Square root of line count (smooth sub-linear scaling).
* **20**: Baseline minimum window for small files.
* **3.5**: Growth speed factor.
* **100**: Maximum safety ceiling (prevents LLM prompt overload).
* **0.18**: 18% continuous overlap between adjacent windows.

---

## 1. The Core Mathematical Model

For any source file containing $N$ lines of code ($N \in \mathbb{N}^+$), the segmentation engine determines the dynamic window size $W(N)$ and proportional overlap $\text{Overlap}(N)$ using a continuous sub-linear scaling function:

$$
\begin{aligned}
W(N) &= \min\Big(N, \; \text{clamp}\big(\lfloor W_{\text{base}} + \alpha \sqrt{N}\rfloor, \; W_{\min}, \; W_{\max}\big)\Big) \\[12pt]
\text{Overlap}(N) &= \begin{cases} 
0, & \text{if } W(N) \ge N \\[8pt]
\max\big(3, \; \lfloor W(N) \cdot \rho \rfloor\big), & \text{if } W(N) < N 
\end{cases}
\end{aligned}
$$

### Bounding Function (Clamping)
The clamping operator enforces strict operational boundaries against semantic fragmentation and context dilution:

$$
\text{clamp}(x, W_{\min}, W_{\max}) = \max\Big(W_{\min}, \; \min\big(x, W_{\max}\big)\Big)
$$

---

## 2. Parameter Reference & Empirical Constants

| Symbol | Constant | Technical Name | Algorithmic Definition & Role |
| :---: | :---: | :--- | :--- |
| **$N$** | Variable | Total Lines of Code (LOC) | Input dimension of the target source file. |
| **$\alpha$** | `3.5` | **Growth Scaling Factor** | Controls the expansion rate of the window relative to $\sqrt{N}$. Calibrated to match typical Java method scope boundaries (~15-30 LOC). |
| **$\rho$** | `0.18` ($18\%$) | **Proportional Overlap Ratio** | Determines the percentage of prior window lines retained in the subsequent window to preserve syntactical continuity. |
| **$W_{\text{base}}$** | `20` lines | **Baseline Cognitive Window** | The minimum syntactic envelope needed to capture class signature, package structure, and method declarations. |
| **$W_{\min}$** | `20` lines | **Lower Floor Boundary** | Prevents micro-fragmentation where windows are too small to understand variable scopes. |
| **$W_{\max}$** | `100` lines | **Upper Ceiling Boundary** | Prevents LLM attention dilution ("Lost-in-the-Middle") and API Token-Per-Minute (TPM) rate limit exhaustion. |

---

## 3. Deep Dive: What is $\alpha$ (Alpha) and Why $3.5$?

$\alpha$ is the **sub-linear growth coefficient**. It determines how quickly the window size adapts as files grow from small scripts to multi-hundred-line services.

$$\Delta W \propto \alpha \cdot \frac{1}{2\sqrt{N}} \, \Delta N$$

### Why $3.5$?
- **If $\alpha$ was too small ($\alpha = 1.0$):**
  For a 200-line service:
  $$W = 20 + 1.0 \times \sqrt{200} = 20 + 14.1 = 34 \text{ lines}$$
  A 34-line window is too small for a 200-line service. It forces **9 separate API calls**, multiplying prompt overhead and RAG retrieval latency.
- **If $\alpha$ was too large ($\alpha = 8.0$):**
  For a 100-line class:
  $$W = 20 + 8.0 \times \sqrt{100} = 20 + 80 = 100 \text{ lines}$$
  The window immediately jumps to the maximum ceiling, defeating the purpose of adaptive chunking for medium-sized files.
- **Why $\alpha = 3.5$ is optimal:**
  It ensures standard enterprise business services ($80 - 350$ lines) scale smoothly between **$50$ and $85$ lines per window**, which matches the average size of 2 to 3 cohesive Java methods plus surrounding fields.

---

## 4. Deep Dive: What is $\rho$ (Rho) and Why $0.18$ ($18\%$)?

$\rho$ is the **boundary margin overlap ratio**. It dictates how many lines overlap between consecutive windows:

$$\text{Step Size } S(N) = W(N) - \text{Overlap}(N) = W(N) \cdot (1 - \rho)$$

### Why Do We Need Overlap?
Code is not independent text. If a window boundary cuts through:
1. A multi-line method signature with annotations (`@Transactional`, `@Override`)
2. A `try-catch-finally` block
3. A builder pattern or chained stream operation (`.filter().map().collect()`)

Without overlap, the opening statement would be in Window $K$ and the closing block in Window $K+1$. Both windows would produce false-positive syntax errors or miss security vulnerabilities.

### Why $18\%$ specifically?
- **Why not a fixed number like 5 lines?**
  A 5-line overlap on an 80-line window is only $6\%$, which is too shallow to catch multi-line annotations, javadocs, and try-with-resources blocks.
- **Why not 50% overlap ($\rho = 0.5$)?**
  A 50% overlap means half of every single window is duplicated. This doubles the total tokens billed and completely destroys token optimization.
- **Why $\rho = 0.18$ ($18\%$):**
  At 80 lines, $18\%$ yields **14 lines of overlap**. In Java, 14 lines is precisely enough to preserve:
  - Full method signatures + annotations + opening braces
  - Complete exception handler blocks (`catch (Exception e) { ... }`)
  - Continuous variable scoping
  While keeping duplicate token overhead under **$20\%$**.

---

## 5. Why This Formula Specifically (vs. Alternative Models)?

| Mathematical Model | Formula | Flaw in Production Code Review | Verdict |
| :--- | :--- | :--- | :---: |
| **Fixed Windows** | $W(N) = K$ (e.g. 50) | Inflexible. Over-fragments 15-line snippets and causes excessive calls on large files. | Rejected |
| **Linear Scaling** | $W(N) = c \cdot N$ | For a 3,000-line monolith, $W = 750$ lines. Blows past LLM token limits, causes attention dilution, and risks 429 TPM exhaustion. | Rejected |
| **Logarithmic Scaling** | $W(N) = W_{\text{base}} + c \cdot \ln(N)$ | $\ln(N)$ grows too slowly. $\ln(50) \approx 3.9$ while $\ln(500) \approx 6.2$. The window barely changes, causing massive fragmentation for real code. | Rejected |
| **Hardcoded if-else Ladders** | `if (N <= 35) ... else if (N <= 120)` | Brittle cliff-edges. An arbitrary jump at line 121 creates discontinuous jumps and fails on novel file scales. | Rejected |
| **Sub-Linear Square-Root (Our Model)** | $W(N) \propto \sqrt{N}$ | **Smooth continuous growth.** Grows quickly enough for small files ($20 \to 70$), tapers off naturally for large files, and bounds safely via asymptotic clamping. | **Selected** |

---

## 6. Why Do We Clamp the Output?

The raw unconstrained expression is:

$$W_{\text{raw}}(N) = \lfloor W_{\text{base}} + \alpha \sqrt{N} \rfloor$$

We clamp $W_{\text{raw}}$ using three mathematical constraints:

### 1. The File Ceiling: $\min(N, \dots)$
- If a file has $N = 12$ lines, $W_{\text{raw}} = 20 + 3.5\sqrt{12} \approx 32$.
- You cannot read 32 lines from a 12-line file.
- The outer $\min(N, \dots)$ ensures $W(12) = 12$.
- When $W(N) \ge N$, $\text{Overlap}(N) = 0$. The file is analyzed in **exactly 1 single window with 0 duplicate tokens**.

### 2. The Lower Floor: $W_{\min} = 20$
- An LLM analyzing 5 lines of code has zero contextual awareness of class-level imports, injected Spring dependencies, or logger declarations.
- $W_{\min} = 20$ guarantees the minimum syntactic envelope required for meaningful static analysis.

### 3. The Upper Ceiling: $W_{\max} = 100$
- **The "Lost-in-the-Middle" Effect:** Extensive NLP research (Liu et al., Stanford/Berkeley) demonstrates that LLM reasoning accuracy sharply declines when prompt context windows contain too much unstructured code.
- **Rate Limit & TPM Safety:** Models running on high-throughput enterprise inference (such as Groq's 8,000 TPM limit) cannot accept 500-line code prompts without triggering `429 Rate Limit Exceeded`. Clamping at 100 lines guarantees each chunk stays under $\approx 1,000$ tokens (including system prompt + RAG rules).

---

## 7. Numerical Verification Across Common Code Scales

| File Profile | LOC ($N$) | Raw Calculation ($20 + 3.5\sqrt{N}$) | Clamped Window $W(N)$ | Overlap $\text{Overlap}(N)$ | Step Size | Total Windows | Duplicate Token Overhead |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Micro snippet** | `10` | $31.06$ | **10** (snapped to $N$) | **0** | `10` | **1** | **0%** |
| **Small Utility** | `50` | $44.74$ | **44** | **7** | `37` | **2** | **15.9%** |
| **Controller / Service** | `150` | $62.86$ | **62** | **11** | `51` | **3** | **17.7%** |
| **Enterprise Monolith** | `294` | $80.01$ | **80** | **14** | `66` | **5** | **17.5%** |
| **Large Complex Service** | `600` | $105.7 \to \text{clamped}$ | **100** | **18** | `82` | **8** | **18.0%** |
| **Monolithic Legacy Class** | `5,000` | $267.5 \to \text{clamped}$ | **100** | **18** | `82` | **61** | **18.0%** |

---

## 8. Java 21 Implementation in `SlidingWindowEngine.java`

```java
package org.example.reviewer;

public class SlidingWindowEngine {

    public record AdaptiveConfig(int size, int overlap) {}

    /**
     * Continuous Sub-Linear Adaptive Window Sizing Formula:
     *   W(N) = min(N, clamp(floor(20 + 3.5 * sqrt(N)), 20, 100))
     *   Overlap(N) = (W >= N) ? 0 : max(3, floor(W * 0.18))
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

        // Zero overlap when entire file fits in one window
        int effectiveOverlap = (effectiveWindow >= totalLines)
                ? 0
                : Math.max(3, (int) Math.floor(effectiveWindow * RHO_OVERLAP));

        return new AdaptiveConfig(effectiveWindow, effectiveOverlap);
    }
}
```
