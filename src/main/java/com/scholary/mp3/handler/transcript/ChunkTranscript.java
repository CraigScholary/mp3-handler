package com.scholary.mp3.handler.transcript;

import com.scholary.mp3.handler.whisper.TranscriptSegment;
import java.util.List;

/**
 * Transcript for a single chunk with metadata.
 *
 * <p>Tracks the chunk's offset in the original file so we can adjust timestamps during merging.
 */
public record ChunkTranscript(
    int chunkIndex, 
    double startTime, 
    double endTime, 
    List<TranscriptSegment> segments, 
    String language) {}
