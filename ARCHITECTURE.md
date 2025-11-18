# MP3 Handler - System Architecture

## Quick Links
- [System Flowcharts](./system-flowchart.md) - Detailed mermaid diagrams
- [API Documentation](http://localhost:8080/swagger-ui.html) - When running locally

## Overview

MP3 Handler is a Spring Boot service for transcribing large audio files (up to 24 hours) using Whisper AI. It uses a streaming architecture to maintain constant memory usage (~20MB) regardless of file size.

## Key Features

- ✅ **Streaming Architecture**: Downloads only needed chunks via HTTP range requests
- ✅ **Silence-Aware Chunking**: Splits at natural pauses to avoid cutting words
- ✅ **Chunk Overlap**: 5-10s overlap ensures no content is lost at boundaries
- ✅ **Memory Efficient**: Constant ~20MB usage regardless of file size
- ✅ **Resumable**: Chunk caching allows resume after failures
- ✅ **Async Processing**: Non-blocking job processing with status polling
- ✅ **Backpressure Control**: Prevents memory exhaustion during processing

## Architecture Components

### 1. API Layer
- **TranscriptionController**: REST endpoints for transcription and job status
- **DevUiController**: Development UI for testing

### 2. Service Layer
- **TranscriptionService**: Orchestrates the entire transcription pipeline
- **WhisperService**: Interface to Whisper transcription API

### 3. Chunking Layer
- **StreamingSilenceAnalyzer**: Detects silence boundaries using greedy streaming
- **FfmpegChunkPlanner**: Plans chunk boundaries and analyzes audio

### 4. Storage Layer
- **ObjectStoreClient**: S3/MinIO abstraction for streaming file access
- **ChunkCache**: In-memory cache for chunk transcripts

### 5. Transcript Processing
- **TranscriptMerger**: Merges overlapping chunk transcripts
- **SegmentAggregator**: Aggregates segments by chunk boundaries

### 6. Infrastructure
- **BackpressureController**: Monitors memory and throttles processing
- **JobRepository**: Tracks async job status
- **StructuredLogger**: JSON logging for Kibana

## Processing Flow

```
1. Client submits transcription request
   ↓
2. Create async job, return job ID immediately
   ↓
3. Get file metadata (size) without downloading
   ↓
4. Estimate duration from file size
   ↓
5. Stream file in segments to detect silence
   ↓
6. Plan chunks at silence points (max 1 hour each)
   ↓
7. For each chunk:
   - Check cache
   - Check memory (backpressure)
   - Download byte range
   - Transcribe with Whisper
   - Cache result
   - Delete temp file
   ↓
8. Merge transcripts with overlap deduplication
   ↓
9. Aggregate segments by chunk boundaries
   ↓
10. Save to object store (optional)
    ↓
11. Update job status to COMPLETED
```

## Memory Management

The system maintains constant memory usage through:

1. **HTTP Range Requests**: Download only needed bytes
2. **Immediate Cleanup**: Delete temp files after each chunk
3. **Backpressure Control**: Pause when memory > 90%
4. **Streaming Processing**: Never load entire file into memory

**Typical Memory Usage:**
- Base application: ~50MB
- Per active chunk: ~2-4MB
- Total with 4 concurrent jobs: ~70-100MB

## Silence-Aware Chunking

The greedy streaming algorithm:

1. Stream up to 1 hour of audio
2. Detect silence using FFmpeg
3. Find longest silence in last 10 minutes (lookback window)
4. Cut chunk at that silence
5. Continue from that point

**Benefits:**
- Single-pass processing (fast)
- Natural boundaries (better transcription quality)
- Parallel processing (Whisper transcribes chunk N while analyzing N+1)

## Configuration

Key settings in `application.yml`:

```yaml
transcription:
  defaultChunkSeconds: 3600        # 1 hour max
  defaultOverlapSeconds: 5         # 5s overlap
  defaultSilenceAware: true        # Use silence detection
  maxFileDurationHours: 24         # Support up to 24 hours
  
  silence:
    maxChunkDuration: 3600         # Hard limit: 1 hour
    lookbackSeconds: 600           # 10 min lookback
```

## API Endpoints

### POST /transcribe
Submit async transcription job
- Returns: Job ID immediately
- Status: 202 Accepted

### GET /jobs/{id}
Check job status
- Returns: Job status, progress, result (if complete)

### POST /chunks/preview
Preview chunk boundaries without transcribing
- Returns: Planned chunk boundaries

## Monitoring

- **Prometheus Metrics**: `/actuator/prometheus`
- **Health Check**: `/actuator/health`
- **Kibana Logs**: Structured JSON logging with correlation IDs

## For 8-Hour Files

Recommended configuration:
- Chunk duration: 3600s (1 hour)
- Overlap: 10s (increased for safety)
- Silence-aware: enabled
- Expected chunks: ~8 chunks
- Processing time: ~2-4 hours (depends on Whisper speed)
- Memory usage: ~70-100MB constant

## See Also

- [System Flowcharts](./system-flowchart.md) - Visual diagrams of all flows
- [API Documentation](http://localhost:8080/swagger-ui.html) - Interactive API docs
