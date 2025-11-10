package com.scholary.mp3.handler.whisper;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HTTP client for calling the faster-whisper transcription API.
 *
 * <p>This handles the low-level HTTP communication: building multipart requests, sending files,
 * parsing responses, and retrying on transient failures.
 *
 * <p>Why not use RestTemplate or WebClient? Because we need fine-grained control over multipart
 * encoding and retry logic. The Java 11+ HttpClient gives us that with less overhead.
 *
 * <p>IMPORTANT: Returns raw byte[] responses without manually setting Content-Length to avoid
 * byte/char encoding mismatches that cause EOFException.
 */
@Component
public class WhisperClient implements WhisperService {

  private static final Logger LOGGER = LoggerFactory.getLogger(WhisperClient.class);

  private final HttpClient httpClient;
  private final WhisperProperties properties;
  private final ObjectMapper objectMapper;

  public WhisperClient(WhisperProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;

    // Build HTTP client with configured timeouts
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.connectTimeout()))
            .build();

    LOGGER.info("Initialized Whisper client: baseUrl={}", properties.baseUrl());
  }

  /**
   * Transcribe an audio chunk.
   *
   * <p>Sends the audio file to the Whisper API as multipart/form-data and returns the parsed
   * response. Includes retry logic for transient failures.
   *
   * @param audioFile the audio file to transcribe
   * @param chunkDurationSeconds the duration of the chunk
   * @param chunkIndex the index of this chunk
   * @return the transcription response
   * @throws WhisperException if transcription fails after retries
   */
  @Override
  public WhisperResponse transcribe(Path audioFile, double chunkDurationSeconds, int chunkIndex) {
    LOGGER.info(
        "Transcribing chunk: file={}, duration={}s, index={}",
        audioFile.getFileName(),
        chunkDurationSeconds,
        chunkIndex);

    int attempt = 0;
    Exception lastException = null;

    while (attempt < properties.maxRetries()) {
      try {
        return attemptTranscribe(audioFile, chunkDurationSeconds, chunkIndex);
      } catch (IOException | InterruptedException e) {
        lastException = e;
        attempt++;
        if (attempt < properties.maxRetries()) {
          // Exponential backoff with jitter
          long backoffMs = (long) (Math.pow(2, attempt) * 1000 + Math.random() * 1000);
          LOGGER.warn(
              "Transcription attempt {} failed, retrying in {}ms: {}",
              attempt,
              backoffMs,
              e.getMessage());
          try {
            Thread.sleep(backoffMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new WhisperException("Transcription interrupted", ie);
          }
        }
      }
    }

    throw new WhisperException(
        String.format("Transcription failed after %d attempts", properties.maxRetries()),
        lastException);
  }

  /**
   * Attempt a single transcription request.
   *
   * @throws IOException if the request fails
   * @throws InterruptedException if the request is interrupted
   */
  private WhisperResponse attemptTranscribe(
      Path audioFile, double chunkDurationSeconds, int chunkIndex)
      throws IOException, InterruptedException {

    // Build multipart request
    String boundary = UUID.randomUUID().toString();
    BodyPublisher bodyPublisher =
        buildMultipartBody(audioFile, chunkDurationSeconds, chunkIndex, boundary);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(properties.baseUrl() + "/api/v1/transcribe"))
            .timeout(Duration.ofSeconds(properties.readTimeout()))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(bodyPublisher)
            .build();

    LOGGER.debug("Sending transcription request to {}", request.uri());

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IOException(
          String.format(
              "Whisper API returned status %d: %s", response.statusCode(), response.body()));
    }

    // Parse response
    WhisperResponse whisperResponse =
        objectMapper.readValue(response.body(), WhisperResponse.class);

    LOGGER.info(
        "Transcription successful: {} segments, language={}",
        whisperResponse.segments().size(),
        whisperResponse.language());

    return whisperResponse;
  }

  /**
   * Build a multipart/form-data body for the transcription request.
   *
   * <p>This manually constructs the multipart format because Java's HttpClient doesn't have
   * built-in multipart support. The format is:
   *
   * <pre>
   * --boundary
   * Content-Disposition: form-data; name="file"; filename="chunk.mp3"
   * Content-Type: audio/mpeg
   *
   * [binary data]
   * --boundary
   * Content-Disposition: form-data; name="chunkDurationSeconds"
   *
   * 60.0
   * --boundary
   * Content-Disposition: form-data; name="chunkIndex"
   *
   * 0
   * --boundary--
   * </pre>
   */
  private BodyPublisher buildMultipartBody(
      Path audioFile, double chunkDurationSeconds, int chunkIndex, String boundary)
      throws IOException {

    String filename = audioFile.getFileName().toString();
    byte[] fileBytes = Files.readAllBytes(audioFile);

    StringBuilder sb = new StringBuilder();

    // File part
    sb.append("--").append(boundary).append("\r\n");
    sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
        .append(filename)
        .append("\"\r\n");
    sb.append("Content-Type: audio/mpeg\r\n\r\n");

    byte[] prefix = sb.toString().getBytes();

    sb = new StringBuilder();
    sb.append("\r\n");

    // chunkDurationSeconds part
    sb.append("--").append(boundary).append("\r\n");
    sb.append("Content-Disposition: form-data; name=\"chunkDurationSeconds\"\r\n\r\n");
    sb.append(chunkDurationSeconds).append("\r\n");

    // chunkIndex part
    sb.append("--").append(boundary).append("\r\n");
    sb.append("Content-Disposition: form-data; name=\"chunkIndex\"\r\n\r\n");
    sb.append(chunkIndex).append("\r\n");

    // End boundary
    sb.append("--").append(boundary).append("--\r\n");

    byte[] suffix = sb.toString().getBytes();

    // Combine all parts
    byte[] body = new byte[prefix.length + fileBytes.length + suffix.length];
    System.arraycopy(prefix, 0, body, 0, prefix.length);
    System.arraycopy(fileBytes, 0, body, prefix.length, fileBytes.length);
    System.arraycopy(suffix, 0, body, prefix.length + fileBytes.length, suffix.length);

    return BodyPublishers.ofByteArray(body);
  }
}
