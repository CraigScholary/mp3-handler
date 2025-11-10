package com.scholary.mp3.handler.job;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * In-memory repository for transcription jobs.
 *
 * <p>Uses Caffeine cache for automatic eviction of old jobs. This keeps memory usage bounded and
 * ensures we don't accumulate completed jobs forever.
 *
 * <p>Why Caffeine? It's fast, thread-safe, and has excellent eviction policies. For a production
 * system, you'd want persistent storage (Redis, database), but for this use case, in-memory is
 * fine.
 */
@Repository
public class JobRepository {

  private final Cache<String, TranscriptionJob> cache;

  public JobRepository(
      @Value("${jobstore.maxSize}") int maxSize,
      @Value("${jobstore.expireAfterMinutes}") int expireAfterMinutes) {

    this.cache =
        Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(Duration.ofMinutes(expireAfterMinutes))
            .build();
  }

  public void save(TranscriptionJob job) {
    cache.put(job.getJobId(), job);
  }

  public Optional<TranscriptionJob> findById(String jobId) {
    return Optional.ofNullable(cache.getIfPresent(jobId));
  }

  public void delete(String jobId) {
    cache.invalidate(jobId);
  }
}
