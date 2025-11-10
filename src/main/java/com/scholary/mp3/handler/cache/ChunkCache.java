package com.scholary.mp3.handler.cache;

import com.scholary.mp3.handler.transcript.ChunkTranscript;
import java.util.Optional;

/**
 * Cache for storing chunk transcripts to enable resume functionality.
 *
 * <p>When a large transcription job is interrupted, this cache allows resuming from the last
 * completed chunk instead of starting over.
 *
 * <p>Cache keys are based on: bucket + key + chunk index + chunk parameters (start/end time)
 */
public interface ChunkCache {

  /**
   * Store a chunk transcript in the cache.
   *
   * @param cacheKey unique key for this chunk
   * @param transcript the chunk transcript to cache
   */
  void put(String cacheKey, ChunkTranscript transcript);

  /**
   * Retrieve a cached chunk transcript.
   *
   * @param cacheKey unique key for this chunk
   * @return the cached transcript, or empty if not found
   */
  Optional<ChunkTranscript> get(String cacheKey);

  /**
   * Remove a chunk from the cache.
   *
   * @param cacheKey unique key for this chunk
   */
  void evict(String cacheKey);

  /**
   * Clear all cached chunks for a specific file.
   *
   * @param bucket the S3 bucket
   * @param key the S3 key
   */
  void evictFile(String bucket, String key);

  /**
   * Generate a cache key for a chunk.
   *
   * @param bucket the S3 bucket
   * @param key the S3 key
   * @param chunkIndex the chunk index
   * @param startTime the chunk start time
   * @param endTime the chunk end time
   * @return a unique cache key
   */
  static String generateKey(
      String bucket, String key, int chunkIndex, double startTime, double endTime) {
    return String.format("%s:%s:chunk-%d:%.2f-%.2f", bucket, key, chunkIndex, startTime, endTime);
  }

  /**
   * Generate a file prefix for evicting all chunks of a file.
   *
   * @param bucket the S3 bucket
   * @param key the S3 key
   * @return a prefix matching all chunks of this file
   */
  static String generateFilePrefix(String bucket, String key) {
    return String.format("%s:%s:", bucket, key);
  }
}
