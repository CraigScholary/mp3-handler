package com.scholary.mp3.handler.strategy;

/**
 * Context for chunking strategies.
 *
 * <p>Contains all information needed to plan chunks.
 */
public record ChunkingContext(
    String bucket,
    String key,
    long fileSize,
    double estimatedDuration,
    ChunkingConfig config) {}
