package com.scholary.mp3.handler.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.scholary.mp3.handler.api.ChunkingMode;
import com.scholary.mp3.handler.api.TranscriptionRequestV2;
import com.scholary.mp3.handler.api.TranscriptionResponse;
import com.scholary.mp3.handler.cache.ChunkCache;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient.ObjectMetadata;
import com.scholary.mp3.handler.service.TranscriptionOrchestrator;
import com.scholary.mp3.handler.service.TranscriptWriter;
import com.scholary.mp3.handler.strategy.OverlapChunkingStrategy;
import com.scholary.mp3.handler.strategy.SilenceAwareChunkingStrategy;
import com.scholary.mp3.handler.transcript.ConcatenationMerger;
import com.scholary.mp3.handler.transcript.WordMatchingMerger;
import com.scholary.mp3.handler.whisper.TranscriptSegment;
import com.scholary.mp3.handler.whisper.WhisperResponse;
import com.scholary.mp3.handler.whisper.WhisperService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration test for the complete transcription flow.
 *
 * <p>Tests both OVERLAP and SILENCE_AWARE modes end-to-end.
 */
@ExtendWith(MockitoExtension.class)
class TranscriptionOrchestratorIntegrationTest {

  @Mock private ObjectStoreClient objectStoreClient;
  @Mock private WhisperService whisperService;
  @Mock private TranscriptWriter transcriptWriter;
  @Mock private ChunkCache chunkCache;
  @Mock private SilenceAwareChunkingStrategy silenceStrategy;

  @TempDir Path tempDir;

  private TranscriptionOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    OverlapChunkingStrategy overlapStrategy = new OverlapChunkingStrategy();
    WordMatchingMerger wordMatchingMerger = new WordMatchingMerger();
    ConcatenationMerger concatenationMerger = new ConcatenationMerger();

    orchestrator =
        new TranscriptionOrchestrator(
            objectStoreClient,
            whisperService,
            transcriptWriter,
            chunkCache,
            overlapStrategy,
            silenceStrategy,
            wordMatchingMerger,
            concatenationMerger,
            tempDir.toString(),
            24);
  }

  @Test
  void transcribe_overlapMode_success() throws IOException {
    // Setup: 2-hour file (7200 seconds)
    long fileSize = 7200 * 16000L; // 115.2 MB
    when(objectStoreClient.getObjectMetadata(anyString(), anyString()))
        .thenReturn(new ObjectMetadata(fileSize, "audio/mpeg"));

    // Mock chunk downloads
    when(objectStoreClient.getObjectRange(anyString(), anyString(), anyLong(), anyLong()))
        .thenReturn(new ByteArrayInputStream(new byte[1024]));

    // Mock Whisper responses with overlapping content
    when(whisperService.transcribe(any(Path.class), anyDouble(), anyInt()))
        .thenAnswer(
            invocation -> {
              int chunkIndex = invocation.getArgument(2);
              if (chunkIndex == 0) {
                return new WhisperResponse(
                    List.of(
                        new TranscriptSegment(0.0, 5.0, "First chunk content"),
                        new TranscriptSegment(
                            3595.0, 3600.0, "ending with the quick brown fox")),
                    "en");
              } else {
                return new WhisperResponse(
                    List.of(
                        new TranscriptSegment(0.0, 5.0, "the quick brown fox jumps over"),
                        new TranscriptSegment(5.0, 10.0, "Second chunk content")),
                    "en");
              }
            });

    // Mock cache (no cached chunks)
    when(chunkCache.get(anyString())).thenReturn(java.util.Optional.empty());

    // Execute
    TranscriptionRequestV2 request =
        TranscriptionRequestV2.forOverlapMode("test-bucket", "test.mp3", 3600, 30, false);

    TranscriptionResponse response = orchestrator.transcribe(request);

    // Verify
    assertThat(response).isNotNull();
    assertThat(response.segments()).isNotEmpty();
    assertThat(response.language()).isEqualTo("en");
    assertThat(response.diagnostics().totalChunks()).isEqualTo(2);

    // Verify overlap was handled (should not have duplicate "the quick brown fox")
    String fullText =
        response.segments().stream()
            .map(seg -> seg.text())
            .reduce("", (a, b) -> a + " " + b);

    int count = countOccurrences(fullText, "the quick brown fox");
    assertThat(count).as("Overlap phrase should appear once").isLessThanOrEqualTo(1);
  }

  @Test
  void transcribe_silenceAwareMode_success() throws IOException {
    // Setup: 2-hour file
    long fileSize = 7200 * 16000L;
    when(objectStoreClient.getObjectMetadata(anyString(), anyString()))
        .thenReturn(new ObjectMetadata(fileSize, "audio/mpeg"));

    // Mock silence strategy to return 2 chunks
    when(silenceStrategy.planChunks(any()))
        .thenReturn(
            List.of(
                new com.scholary.mp3.handler.strategy.ChunkPlan(0, 0.0, 3500.0),
                new com.scholary.mp3.handler.strategy.ChunkPlan(1, 3500.0, 7200.0)));

    // Mock chunk downloads
    when(objectStoreClient.getObjectRange(anyString(), anyString(), anyLong(), anyLong()))
        .thenReturn(new ByteArrayInputStream(new byte[1024]));

    // Mock Whisper responses (no overlap)
    when(whisperService.transcribe(any(Path.class), anyDouble(), anyInt()))
        .thenAnswer(
            invocation -> {
              int chunkIndex = invocation.getArgument(2);
              if (chunkIndex == 0) {
                return new WhisperResponse(
                    List.of(new TranscriptSegment(0.0, 5.0, "First chunk at silence boundary")),
                    "en");
              } else {
                return new WhisperResponse(
                    List.of(new TranscriptSegment(0.0, 5.0, "Second chunk after silence")), "en");
              }
            });

    // Mock cache
    when(chunkCache.get(anyString())).thenReturn(java.util.Optional.empty());

    // Execute
    TranscriptionRequestV2 request =
        TranscriptionRequestV2.forSilenceAwareMode(
            "test-bucket", "test.mp3", 3600, "-35dB", 1.0, 600, false);

    TranscriptionResponse response = orchestrator.transcribe(request);

    // Verify
    assertThat(response).isNotNull();
    assertThat(response.segments()).hasSize(2);
    assertThat(response.language()).isEqualTo("en");
    assertThat(response.diagnostics().totalChunks()).isEqualTo(2);

    // Verify simple concatenation (no overlap handling needed)
    assertThat(response.segments().get(0).text()).isEqualTo("First chunk at silence boundary");
    assertThat(response.segments().get(1).text()).isEqualTo("Second chunk after silence");
  }

  @Test
  void transcribe_withCache_resumesFromCache() throws IOException {
    // Setup
    long fileSize = 7200 * 16000L;
    when(objectStoreClient.getObjectMetadata(anyString(), anyString()))
        .thenReturn(new ObjectMetadata(fileSize, "audio/mpeg"));

    // Mock first chunk is cached
    com.scholary.mp3.handler.transcript.ChunkTranscript cachedChunk =
        new com.scholary.mp3.handler.transcript.ChunkTranscript(
            0, 0.0, List.of(new TranscriptSegment(0.0, 5.0, "Cached chunk")), "en");

    when(chunkCache.get(anyString()))
        .thenReturn(java.util.Optional.of(cachedChunk))
        .thenReturn(java.util.Optional.empty());

    // Mock second chunk download and transcription
    when(objectStoreClient.getObjectRange(anyString(), anyString(), anyLong(), anyLong()))
        .thenReturn(new ByteArrayInputStream(new byte[1024]));

    when(whisperService.transcribe(any(Path.class), anyDouble(), anyInt()))
        .thenReturn(
            new WhisperResponse(
                List.of(new TranscriptSegment(0.0, 5.0, "New chunk")), "en"));

    // Execute
    TranscriptionRequestV2 request =
        TranscriptionRequestV2.forOverlapMode("test-bucket", "test.mp3", 3600, 30, false);

    TranscriptionResponse response = orchestrator.transcribe(request);

    // Verify
    assertThat(response).isNotNull();
    assertThat(response.segments()).hasSize(2);
    assertThat(response.segments().get(0).text()).isEqualTo("Cached chunk");
    assertThat(response.segments().get(1).text()).contains("New chunk");
  }

  private int countOccurrences(String text, String phrase) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(phrase, index)) != -1) {
      count++;
      index += phrase.length();
    }
    return count;
  }
}
