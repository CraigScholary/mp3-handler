package com.scholary.mp3.handler.whisper;

import java.nio.file.Path;

/**
 * Interface for transcription services.
 *
 * <p>This abstraction allows us to swap transcription providers (Whisper, AssemblyAI, etc.) without
 * changing business logic.
 */
public interface WhisperService {

  /**
   * Transcribe an audio chunk.
   *
   * @param audioFile the audio file to transcribe
   * @param chunkDurationSeconds the duration of the chunk in seconds
   * @param chunkIndex the index of this chunk in the full audio
   * @return the transcription response
   * @throws WhisperException if transcription fails
   */
  WhisperResponse transcribe(Path audioFile, double chunkDurationSeconds, int chunkIndex);
}
