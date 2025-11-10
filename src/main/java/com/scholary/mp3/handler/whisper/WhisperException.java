package com.scholary.mp3.handler.whisper;

/**
 * Exception thrown when Whisper API calls fail.
 *
 * <p>This could be due to network issues, service unavailability, or invalid responses.
 */
public class WhisperException extends RuntimeException {

  public WhisperException(String message) {
    super(message);
  }

  public WhisperException(String message, Throwable cause) {
    super(message, cause);
  }
}
