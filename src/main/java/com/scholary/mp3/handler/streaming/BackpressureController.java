package com.scholary.mp3.handler.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Controls backpressure during streaming transcription to prevent memory exhaustion.
 *
 * <p>Monitors system memory and applies throttling when memory usage is high. This prevents the
 * system from downloading chunks faster than they can be processed.
 *
 * <p>Backpressure strategies:
 *
 * <ul>
 *   <li>Monitor heap memory usage
 *   <li>Pause chunk downloads when memory is above threshold
 *   <li>Force garbage collection if memory is critically high
 *   <li>Log memory statistics for monitoring
 * </ul>
 */
@Component
public class BackpressureController {

  private static final Logger LOGGER = LoggerFactory.getLogger(BackpressureController.class);

  // Memory thresholds (percentage of max heap)
  private static final double WARNING_THRESHOLD = 0.75; // 75%
  private static final double CRITICAL_THRESHOLD = 0.85; // 85%
  private static final double PAUSE_THRESHOLD = 0.90; // 90%

  // Pause duration when memory is high
  private static final long PAUSE_MS = 1000; // 1 second

  /**
   * Check if processing should pause due to memory pressure.
   *
   * @return true if processing should pause
   */
  public boolean shouldPause() {
    double memoryUsage = getMemoryUsageRatio();

    if (memoryUsage >= PAUSE_THRESHOLD) {
      LOGGER.warn(
          "Memory usage critical ({}%), pausing chunk processing",
          String.format("%.1f", memoryUsage * 100));
      return true;
    }

    if (memoryUsage >= CRITICAL_THRESHOLD) {
      LOGGER.warn(
          "Memory usage high ({}%), suggesting GC", String.format("%.1f", memoryUsage * 100));
      System.gc(); // Suggest GC (not guaranteed to run)
    } else if (memoryUsage >= WARNING_THRESHOLD) {
      LOGGER.info("Memory usage elevated ({}%)", String.format("%.1f", memoryUsage * 100));
    }

    return false;
  }

  /**
   * Wait if memory pressure is high.
   *
   * <p>This method blocks until memory usage drops below the pause threshold.
   *
   * @throws InterruptedException if interrupted while waiting
   */
  public void waitIfNeeded() throws InterruptedException {
    int attempts = 0;
    int maxAttempts = 30; // Max 30 seconds wait

    while (shouldPause() && attempts < maxAttempts) {
      LOGGER.info(
          "Waiting for memory pressure to decrease (attempt {}/{})", attempts + 1, maxAttempts);
      Thread.sleep(PAUSE_MS);
      attempts++;
    }

    if (attempts >= maxAttempts) {
      LOGGER.error(
          "Memory pressure did not decrease after {} seconds, continuing anyway", maxAttempts);
    }
  }

  /**
   * Get current memory usage ratio (0.0 to 1.0).
   *
   * @return memory usage ratio
   */
  public double getMemoryUsageRatio() {
    Runtime runtime = Runtime.getRuntime();
    long maxMemory = runtime.maxMemory();
    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;

    return (double) usedMemory / maxMemory;
  }

  /**
   * Get memory statistics for logging.
   *
   * @return formatted memory statistics
   */
  public String getMemoryStats() {
    Runtime runtime = Runtime.getRuntime();
    long maxMemory = runtime.maxMemory();
    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;

    return String.format(
        "Memory[used=%dMB, total=%dMB, max=%dMB, usage=%.1f%%]",
        usedMemory / 1024 / 1024,
        totalMemory / 1024 / 1024,
        maxMemory / 1024 / 1024,
        (double) usedMemory / maxMemory * 100);
  }

  /** Log current memory statistics. */
  public void logMemoryStats() {
    LOGGER.info(getMemoryStats());
  }
}
