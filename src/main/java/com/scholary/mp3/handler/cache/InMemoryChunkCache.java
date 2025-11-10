package com.scholary.mp3.handler.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.scholary.mp3.handler.transcript.ChunkTranscript;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory implementation of ChunkCache using Caffeine.
 *
 * <p>Chunks are cached for a configurable duration (default: 24 hours) to allow resume within a
 * reasonable timeframe while not consuming memory indefinitely.
 *
 * <p>Cache size is limited to prevent memory exhaustion. Oldest entries are evicted when the limit
 * is reached.
 */
@Component
public class InMemoryChunkCache implements ChunkCache {

  private static final Logger LOGGER = LoggerFactory.getLogger(InMemoryChunkCache.class);

  private final Cache<String, ChunkTranscript> cache;

  public InMemoryChunkCache(
      @Value("${transcription.cache.maxSize:1000}") int maxSize,
      @Value("${transcription.cache.ttlHours:24}") int ttlHours) {

    this.cache =
        Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(Duration.ofHours(ttlHours))
            .recordStats()
            .build();

    LOGGER.info("Initialized chunk cache: maxSize={}, ttlHours={}", maxSize, ttlHours);
  }

  @Override
  public void put(String cacheKey, ChunkTranscript transcript) {
    cache.put(cacheKey, transcript);
    LOGGER.debug("Cached chunk: key={}, segments={}", cacheKey, transcript.segments().size());
  }

  @Override
  public Optional<ChunkTranscript> get(String cacheKey) {
    ChunkTranscript transcript = cache.getIfPresent(cacheKey);
    if (transcript != null) {
      LOGGER.debug("Cache hit: key={}", cacheKey);
      return Optional.of(transcript);
    } else {
      LOGGER.debug("Cache miss: key={}", cacheKey);
      return Optional.empty();
    }
  }

  @Override
  public void evict(String cacheKey) {
    cache.invalidate(cacheKey);
    LOGGER.debug("Evicted chunk: key={}", cacheKey);
  }

  @Override
  public void evictFile(String bucket, String key) {
    String prefix = ChunkCache.generateFilePrefix(bucket, key);
    long evicted =
        cache.asMap().keySet().stream()
            .filter(k -> k.startsWith(prefix))
            .peek(cache::invalidate)
            .count();
    LOGGER.info("Evicted {} chunks for file: bucket={}, key={}", evicted, bucket, key);
  }

  /**
   * Get cache statistics for monitoring.
   *
   * @return cache stats
   */
  public String getStats() {
    var stats = cache.stats();
    return String.format(
        "ChunkCache[size=%d, hitRate=%.2f%%, evictions=%d]",
        cache.estimatedSize(), stats.hitRate() * 100, stats.evictionCount());
  }
}
