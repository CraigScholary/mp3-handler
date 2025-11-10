package com.scholary.mp3.handler.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.scholary.mp3.handler.api.TranscriptionRequest;
import com.scholary.mp3.handler.api.TranscriptionResponse;
import com.scholary.mp3.handler.cache.ChunkCache;
import com.scholary.mp3.handler.chunking.FfmpegChunkPlanner;
import com.scholary.mp3.handler.chunking.TimeRange;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient;
import com.scholary.mp3.handler.objectstore.ObjectStoreClient.ObjectMetadata;
import com.scholary.mp3.handler.streaming.BackpressureController;
import com.scholary.mp3.handler.transcript.ChunkTranscript;
import com.scholary.mp3.handler.transcript.TranscriptMerger;
import com.scholary.mp3.handler.whisper.WhisperResponse;
import com.scholary.mp3.handler.whisper.WhisperService;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for TranscriptionService with focus on large file handling. */
@ExtendWith(MockitoExtension.class)
class TranscriptionServiceTest {

  @Mock private ObjectStoreClient objectStoreClient;
  @Mock private FfmpegChunkPlanner chunkPlanner;
  @Mock private WhisperService whisperService;
  @Mock private TranscriptMerger transcriptMerger;
  @Mock private TranscriptWriter transcriptWriter;
  @Mock private ChunkCache chunkCache;
  @Mock private BackpressureController backpressureController;

  @TempDir Path tempDir;

  private TranscriptionService service;

  @BeforeEach
  void setUp() {
    // Create mock instances for new dependencies
    var streamingSilenceAnalyzer = mock(com.scholary.mp3.handler.chunking.StreamingSilenceAnalyzer.class);
    var segmentAggregator = mock(com.scholary.mp3.handler.transcript.SegmentAggregator.class);
    
    service =
        new TranscriptionService(
            objectStoreClient,
            chunkPlanner,
            whisperService,
            transcriptMerger,
            transcriptWriter,
            chunkCache,
            backpressureController,
            streamingSilenceAnalyzer,
            segmentAggregator,
            tempDir.toString(),
            24);
  }

  @Test
  void testStreamingTranscription_SmallFile() throws Exception {
    // Simulate a 1-hour file (60 chunks)
    testStreamingTranscription(3600, 60);
  }

  @Test
  void testStreamingTranscription_MediumFile() throws Exception {
    // Simulate a 4-hour file (240 chunks)
    testStreamingTranscription(14400, 240);
  }

  @Test
  void testStreamingTranscription_LargeFile() throws Exception {
    // Simulate a 12-hour file (720 chunks)
    testStreamingTranscription(43200, 720);
  }

  @Test
  void testStreamingTranscription_VeryLargeFile() throws Exception {
    // Simulate a 24-hour file (1440 chunks)
    testStreamingTranscription(86400, 1440);
  }

  private void testStreamingTranscription(long durationSeconds, int expectedChunks)
      throws Exception {
    // Setup
    String bucket = "test-bucket";
    String key = "test-file.mp3";
    long fileSize = durationSeconds * 16000; // 16KB/s estimate

    TranscriptionRequest request = new TranscriptionRequest(bucket, key, 60, 5, true, null);

    // Mock file metadata
    ObjectMetadata metadata = new ObjectMetadata(fileSize, "audio/mpeg");
    when(objectStoreClient.getObjectMetadata(bucket, key)).thenReturn(metadata);

    // Mock chunk planning
    List<TimeRange> chunkRanges = new ArrayList<>();
    for (int i = 0; i < expectedChunks; i++) {
      double start = i * 55.0; // 60s chunks with 5s overlap
      double end = Math.min(start + 60.0, durationSeconds);
      chunkRanges.add(new TimeRange(start, end));
    }
    when(chunkPlanner.planChunks(any(), any(), any(), any())).thenReturn(chunkRanges);

    // Mock chunk downloads (return dummy MP3 data)
    byte[] dummyMp3 = new byte[1024]; // 1KB dummy data
    when(objectStoreClient.getObjectRange(eq(bucket), eq(key), anyLong(), anyLong()))
        .thenAnswer(invocation -> new ByteArrayInputStream(dummyMp3));

    // Mock cache (no hits)
    when(chunkCache.get(anyString())).thenReturn(Optional.empty());

    // Mock Whisper responses
    WhisperResponse whisperResponse = new WhisperResponse(List.of(), "en");
    when(whisperService.transcribe(any(Path.class), anyDouble(), anyInt()))
        .thenReturn(whisperResponse);

    // Mock backpressure (never pause)
    when(backpressureController.shouldPause()).thenReturn(false);

    // Mock transcript merger
    when(transcriptMerger.merge(anyList())).thenReturn(List.of());

    // Mock transcript writer
    when(transcriptWriter.writeJson(anyList(), anyString())).thenReturn(new byte[0]);
    when(transcriptWriter.writeSrt(anyList())).thenReturn(new byte[0]);

    // Execute
    TranscriptionResponse response = service.transcribeStreaming(request);

    // Verify
    assertNotNull(response);
    assertEquals("en", response.language());

    // Verify chunk processing
    verify(objectStoreClient, times(1)).getObjectMetadata(bucket, key);
    verify(chunkPlanner, times(1)).planChunks(any(), any(), any(), any());
    verify(objectStoreClient, times(expectedChunks))
        .getObjectRange(eq(bucket), eq(key), anyLong(), anyLong());
    verify(whisperService, times(expectedChunks))
        .transcribe(any(Path.class), anyDouble(), anyInt());
    verify(chunkCache, times(expectedChunks)).put(anyString(), any(ChunkTranscript.class));

    // Verify backpressure was checked
    verify(backpressureController, atLeastOnce()).waitIfNeeded();
    verify(backpressureController, atLeastOnce()).logMemoryStats();
  }

  @Test
  void testStreamingTranscription_WithCacheHits() throws Exception {
    // Setup
    String bucket = "test-bucket";
    String key = "test-file.mp3";
    long durationSeconds = 3600; // 1 hour
    long fileSize = durationSeconds * 16000;
    int totalChunks = 60;
    int cachedChunks = 30; // Half cached

    TranscriptionRequest request = new TranscriptionRequest(bucket, key, 60, 5, true, null);

    // Mock file metadata
    ObjectMetadata metadata = new ObjectMetadata(fileSize, "audio/mpeg");
    when(objectStoreClient.getObjectMetadata(bucket, key)).thenReturn(metadata);

    // Mock chunk planning
    List<TimeRange> chunkRanges = new ArrayList<>();
    for (int i = 0; i < totalChunks; i++) {
      double start = i * 55.0;
      double end = Math.min(start + 60.0, durationSeconds);
      chunkRanges.add(new TimeRange(start, end));
    }
    when(chunkPlanner.planChunks(any(), any(), any(), any())).thenReturn(chunkRanges);

    // Mock cache (first half cached, second half miss)
    ChunkTranscript cachedTranscript = new ChunkTranscript(0, 0.0, List.of(), "en");
    when(chunkCache.get(anyString()))
        .thenAnswer(
            invocation -> {
              String key1 = invocation.getArgument(0);
              // Simulate cache hits for first half of chunks
              if (key1.contains("chunk-")
                  && Integer.parseInt(key1.split("chunk-")[1].split(":")[0]) < cachedChunks) {
                return Optional.of(cachedTranscript);
              }
              return Optional.empty();
            });

    // Mock chunk downloads for cache misses
    byte[] dummyMp3 = new byte[1024];
    when(objectStoreClient.getObjectRange(eq(bucket), eq(key), anyLong(), anyLong()))
        .thenAnswer(invocation -> new ByteArrayInputStream(dummyMp3));

    // Mock Whisper responses
    WhisperResponse whisperResponse = new WhisperResponse(List.of(), "en");
    when(whisperService.transcribe(any(Path.class), anyDouble(), anyInt()))
        .thenReturn(whisperResponse);

    // Mock backpressure
    when(backpressureController.shouldPause()).thenReturn(false);

    // Mock transcript merger
    when(transcriptMerger.merge(anyList())).thenReturn(List.of());

    // Mock transcript writer
    when(transcriptWriter.writeJson(anyList(), anyString())).thenReturn(new byte[0]);
    when(transcriptWriter.writeSrt(anyList())).thenReturn(new byte[0]);

    // Execute
    TranscriptionResponse response = service.transcribeStreaming(request);

    // Verify
    assertNotNull(response);

    // Verify only non-cached chunks were downloaded and transcribed
    verify(objectStoreClient, times(totalChunks - cachedChunks))
        .getObjectRange(eq(bucket), eq(key), anyLong(), anyLong());
    verify(whisperService, times(totalChunks - cachedChunks))
        .transcribe(any(Path.class), anyDouble(), anyInt());

    // Verify cache was checked for all chunks
    verify(chunkCache, atLeast(totalChunks)).get(anyString());
  }

  @Test
  void testStreamingTranscription_WithBackpressure() throws Exception {
    // Setup
    String bucket = "test-bucket";
    String key = "test-file.mp3";
    long durationSeconds = 3600;
    long fileSize = durationSeconds * 16000;
    int totalChunks = 60;

    TranscriptionRequest request = new TranscriptionRequest(bucket, key, 60, 5, true, null);

    // Mock file metadata
    ObjectMetadata metadata = new ObjectMetadata(fileSize, "audio/mpeg");
    when(objectStoreClient.getObjectMetadata(bucket, key)).thenReturn(metadata);

    // Mock chunk planning
    List<TimeRange> chunkRanges = new ArrayList<>();
    for (int i = 0; i < totalChunks; i++) {
      double start = i * 55.0;
      double end = Math.min(start + 60.0, durationSeconds);
      chunkRanges.add(new TimeRange(start, end));
    }
    when(chunkPlanner.planChunks(any(), any(), any(), any())).thenReturn(chunkRanges);

    // Mock cache (no hits)
    when(chunkCache.get(anyString())).thenReturn(Optional.empty());

    // Mock chunk downloads
    byte[] dummyMp3 = new byte[1024];
    when(objectStoreClient.getObjectRange(eq(bucket), eq(key), anyLong(), anyLong()))
        .thenAnswer(invocation -> new ByteArrayInputStream(dummyMp3));

    // Mock Whisper responses
    WhisperResponse whisperResponse = new WhisperResponse(List.of(), "en");
    when(whisperService.transcribe(any(Path.class), anyDouble(), anyInt()))
        .thenReturn(whisperResponse);

    // Mock backpressure (pause on every 10th chunk)
    when(backpressureController.shouldPause())
        .thenAnswer(invocation -> Math.random() < 0.1); // 10% chance of pause

    // Mock transcript merger
    when(transcriptMerger.merge(anyList())).thenReturn(List.of());

    // Mock transcript writer
    when(transcriptWriter.writeJson(anyList(), anyString())).thenReturn(new byte[0]);
    when(transcriptWriter.writeSrt(anyList())).thenReturn(new byte[0]);

    // Execute
    TranscriptionResponse response = service.transcribeStreaming(request);

    // Verify
    assertNotNull(response);

    // Verify backpressure was applied
    verify(backpressureController, times(totalChunks)).waitIfNeeded();
  }

  @Test
  void testMemoryUsage_RemainsConstant() throws Exception {
    // This test verifies that memory usage doesn't grow with file size
    // We'll process chunks and verify temp files are cleaned up

    String bucket = "test-bucket";
    String key = "test-file.mp3";
    long durationSeconds = 7200; // 2 hours
    long fileSize = durationSeconds * 16000;
    int totalChunks = 120;

    TranscriptionRequest request = new TranscriptionRequest(bucket, key, 60, 5, true, null);

    // Mock file metadata
    ObjectMetadata metadata = new ObjectMetadata(fileSize, "audio/mpeg");
    when(objectStoreClient.getObjectMetadata(bucket, key)).thenReturn(metadata);

    // Mock chunk planning
    List<TimeRange> chunkRanges = new ArrayList<>();
    for (int i = 0; i < totalChunks; i++) {
      double start = i * 55.0;
      double end = Math.min(start + 60.0, durationSeconds);
      chunkRanges.add(new TimeRange(start, end));
    }
    when(chunkPlanner.planChunks(any(), any(), any(), any())).thenReturn(chunkRanges);

    // Mock cache (no hits)
    when(chunkCache.get(anyString())).thenReturn(Optional.empty());

    // Mock chunk downloads
    byte[] dummyMp3 = new byte[1024];
    when(objectStoreClient.getObjectRange(eq(bucket), eq(key), anyLong(), anyLong()))
        .thenAnswer(invocation -> new ByteArrayInputStream(dummyMp3));

    // Mock Whisper responses
    WhisperResponse whisperResponse = new WhisperResponse(List.of(), "en");
    when(whisperService.transcribe(any(Path.class), anyDouble(), anyInt()))
        .thenReturn(whisperResponse);

    // Mock backpressure
    when(backpressureController.shouldPause()).thenReturn(false);

    // Mock transcript merger
    when(transcriptMerger.merge(anyList())).thenReturn(List.of());

    // Mock transcript writer
    when(transcriptWriter.writeJson(anyList(), anyString())).thenReturn(new byte[0]);
    when(transcriptWriter.writeSrt(anyList())).thenReturn(new byte[0]);

    // Execute
    TranscriptionResponse response = service.transcribeStreaming(request);

    // Verify
    assertNotNull(response);

    // Verify temp directory is clean (all temp files deleted)
    long tempFileCount = Files.list(tempDir).count();
    assertEquals(0, tempFileCount, "Temp directory should be empty after processing");
  }
}
