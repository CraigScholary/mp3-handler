# MP3 Handler System Flowchart

## High-Level Architecture Flow

```mermaid
graph TB
    Start([Client Request]) --> API[TranscriptionController<br/>POST /transcribe]
    API --> CreateJob[Create Job<br/>Generate Job ID]
    CreateJob --> ReturnJob[Return Job ID<br/>HTTP 202 Accepted]
    ReturnJob --> Client([Client Polls<br/>GET /jobs/:id])
    
    CreateJob --> Async[Async Processing<br/>@Async Thread Pool]
    
    Async --> GetMeta[Get File Metadata<br/>ObjectStoreClient.getObjectMetadata]
    GetMeta --> EstDuration[Estimate Duration<br/>fileSize / 16KB/s]
    
    EstDuration --> SilenceCheck{Silence-Aware<br/>Enabled?}
    
    SilenceCheck -->|Yes| StreamAnalyze[Stream File in Segments<br/>StreamingSilenceAnalyzer]
    SilenceCheck -->|No| FixedChunks[Plan Fixed Chunks<br/>FfmpegChunkPlanner]
    
    StreamAnalyze --> DetectSilence[Detect Silence<br/>FFmpeg silencedetect]
    DetectSilence --> GreedyBreak[Greedy Breakpoint Selection<br/>Max 1hr chunks, 10min lookback]
    
    GreedyBreak --> ChunkPlan[Chunk Plan Ready]
    FixedChunks --> ChunkPlan
    
    ChunkPlan --> LoopStart{More Chunks?}
    
    LoopStart -->|Yes| CheckCache{Chunk in<br/>Cache?}
    CheckCache -->|Yes| LoadCache[Load from Cache]
    CheckCache -->|No| Backpressure[Check Memory<br/>BackpressureController]
    
    Backpressure --> MemCheck{Memory<br/>> 90%?}
    MemCheck -->|Yes| Wait[Wait & GC]
    Wait --> MemCheck
    MemCheck -->|No| Download[Download Byte Range<br/>HTTP Range Request]
    
    Download --> SaveTemp[Save to Temp File<br/>/tmp/chunk_N.mp3]
    SaveTemp --> Transcribe[Transcribe Chunk<br/>WhisperService]
    Transcribe --> CacheResult[Cache Result<br/>ChunkCache]
    CacheResult --> Cleanup[Delete Temp File]
    
    LoadCache --> NextChunk[Next Chunk]
    Cleanup --> NextChunk
    NextChunk --> LoopStart
    
    LoopStart -->|No| Merge[Merge Transcripts<br/>TranscriptMerger]
    Merge --> Dedup[Deduplicate Overlaps<br/>Text Similarity]
    Dedup --> Aggregate{Silence-Aware?}
    
    Aggregate -->|Yes| AggSegs[Aggregate Segments<br/>by Chunk Boundaries]
    Aggregate -->|No| FinalSegs[Final Segments]
    AggSegs --> FinalSegs
    
    FinalSegs --> SaveCheck{Save to<br/>Storage?}
    SaveCheck -->|Yes| SaveTranscript[Save Transcript<br/>ObjectStoreClient]
    SaveCheck -->|No| Complete
    SaveTranscript --> Complete[Job Complete<br/>Status: COMPLETED]
    
    Complete --> UpdateJob[Update Job Repository]
    UpdateJob --> End([Client Gets Result])
    
    style Start fill:#e1f5e1
    style End fill:#e1f5e1
    style API fill:#e3f2fd
    style Async fill:#fff3e0
    style StreamAnalyze fill:#f3e5f5
    style Download fill:#fce4ec
    style Transcribe fill:#ffebee
    style Merge fill:#e8f5e9
    style Complete fill:#c8e6c9
```

## Detailed Streaming Silence Analysis Flow

```mermaid
graph TB
    Start([Start Greedy Analysis]) --> Init[Initialize<br/>currentPosition = 0<br/>breakpoints = []]
    
    Init --> Loop{currentPosition<br/>< totalDuration?}
    
    Loop -->|Yes| CalcSegment[Calculate Segment<br/>analyzeUntil = min(pos + 1hr, end)]
    CalcSegment --> CalcBytes[Calculate Byte Range<br/>startByte = pos * 16KB/s<br/>endByte = analyzeUntil * 16KB/s]
    
    CalcBytes --> DownloadSeg[Download Segment<br/>HTTP Range Request]
    DownloadSeg --> FFmpeg[Run FFmpeg silencedetect<br/>on Segment]
    
    FFmpeg --> AdjustTime[Adjust Timestamps<br/>to Absolute Position]
    AdjustTime --> FilterSilence[Filter Silences<br/>duration >= 1.0s]
    
    FilterSilence --> CalcLookback[Calculate Lookback Window<br/>start = analyzeUntil - 10min<br/>end = analyzeUntil]
    
    CalcLookback --> FindBest{Find Longest<br/>Silence in Window?}
    
    FindBest -->|Found| UseSilence[Use Silence Midpoint<br/>as Breakpoint]
    FindBest -->|Not Found| UseMax[Use Max Duration<br/>as Breakpoint]
    
    UseSilence --> AddBreak[Add to breakpoints]
    UseMax --> AddBreak
    
    AddBreak --> UpdatePos[currentPosition = breakpoint]
    UpdatePos --> DeleteTemp[Delete Temp Segment]
    DeleteTemp --> Loop
    
    Loop -->|No| Return([Return Breakpoints])
    
    style Start fill:#e1f5e1
    style Return fill:#e1f5e1
    style FFmpeg fill:#ffebee
    style FindBest fill:#fff3e0
```

## Chunk Processing Detail

```mermaid
graph TB
    Start([Process Chunk N]) --> CheckCache{Chunk in<br/>Cache?}
    
    CheckCache -->|Yes| Return1[Return Cached<br/>Transcript]
    CheckCache -->|No| CalcRange[Calculate Byte Range<br/>start = chunkStart * 16KB/s<br/>end = chunkEnd * 16KB/s]
    
    CalcRange --> AddBuffer[Add Buffer<br/>±1s for MP3 frames]
    AddBuffer --> Range[HTTP Range Request<br/>GET bytes=start-end]
    
    Range --> Stream[Stream to Temp File<br/>/tmp/chunk_N_uuid.mp3]
    Stream --> Whisper[POST to Whisper API<br/>multipart/form-data]
    
    Whisper --> Parse[Parse Response<br/>segments + language]
    Parse --> CreateTrans[Create ChunkTranscript<br/>chunkIndex, startOffset, segments]
    
    CreateTrans --> Cache[Cache Result<br/>key = bucket:key:index:start:end]
    Cache --> Delete[Delete Temp File]
    Delete --> Return2[Return Transcript]
    
    Return1 --> End([End])
    Return2 --> End
    
    style Start fill:#e1f5e1
    style End fill:#e1f5e1
    style Whisper fill:#ffebee
    style Cache fill:#e3f2fd
```

## Transcript Merging Flow

```mermaid
graph TB
    Start([Merge Chunks]) --> Init[Initialize<br/>merged = []<br/>lastEndTime = 0]
    
    Init --> Loop{More Chunks?}
    
    Loop -->|Yes| GetChunk[Get Next Chunk]
    GetChunk --> SegLoop{More Segments<br/>in Chunk?}
    
    SegLoop -->|Yes| GetSeg[Get Next Segment]
    GetSeg --> AdjustTime[Adjust Timestamps<br/>absoluteStart = chunkOffset + segStart<br/>absoluteEnd = chunkOffset + segEnd]
    
    AdjustTime --> CheckOverlap{absoluteStart<br/>< lastEndTime?}
    
    CheckOverlap -->|Yes| CheckDup{Is Duplicate?<br/>Similar timing<br/>+ text?}
    CheckOverlap -->|No| AddSeg[Add to Merged]
    
    CheckDup -->|Yes| Skip[Skip Segment<br/>Log Duplicate]
    CheckDup -->|No| AddSeg
    
    AddSeg --> UpdateLast[lastEndTime = max(last, absEnd)]
    Skip --> SegLoop
    UpdateLast --> SegLoop
    
    SegLoop -->|No| Loop
    Loop -->|No| Return([Return Merged Segments])
    
    style Start fill:#e1f5e1
    style Return fill:#e1f5e1
    style CheckDup fill:#fff3e0
    style Skip fill:#ffcdd2
```

## Memory Management & Backpressure

```mermaid
graph TB
    Start([Before Processing Chunk]) --> GetMem[Get Memory Usage<br/>used / max heap]
    
    GetMem --> Check90{Usage<br/>> 90%?}
    
    Check90 -->|Yes| Pause[Pause Processing<br/>Log Warning]
    Pause --> Wait[Sleep 1 second]
    Wait --> GetMem
    
    Check90 -->|No| Check85{Usage<br/>> 85%?}
    
    Check85 -->|Yes| SuggestGC[Suggest GC<br/>System.gc()]
    Check85 -->|No| Check75{Usage<br/>> 75%?}
    
    Check75 -->|Yes| LogWarn[Log Warning]
    Check75 -->|No| LogInfo[Log Info]
    
    SuggestGC --> Proceed
    LogWarn --> Proceed
    LogInfo --> Proceed
    
    Proceed([Proceed with Chunk])
    
    style Start fill:#e1f5e1
    style Proceed fill:#c8e6c9
    style Pause fill:#ffcdd2
    style SuggestGC fill:#fff3e0
```

## Error Handling & Retry

```mermaid
graph TB
    Start([Transcribe Chunk]) --> Try[Try Transcription]
    
    Try --> Success{Success?}
    
    Success -->|Yes| Return[Return Result]
    Success -->|No| CheckRetry{Attempts<br/>< maxRetries?}
    
    CheckRetry -->|Yes| Backoff[Exponential Backoff<br/>2^attempt * 1000ms]
    Backoff --> Wait[Sleep]
    Wait --> Try
    
    CheckRetry -->|No| LogError[Log Error<br/>Mark Chunk Failed]
    LogError --> Continue{Continue with<br/>Other Chunks?}
    
    Continue -->|Yes| PartialResult[Return Partial Result<br/>Flag Failed Chunks]
    Continue -->|No| FailJob[Fail Entire Job]
    
    Return --> End([End])
    PartialResult --> End
    FailJob --> End
    
    style Start fill:#e1f5e1
    style End fill:#e1f5e1
    style Return fill:#c8e6c9
    style FailJob fill:#ffcdd2
    style Backoff fill:#fff3e0
```

## Data Flow Summary

```mermaid
graph LR
    A[Object Store<br/>8hr MP3 File] -->|HTTP Range| B[Streaming<br/>Silence Analyzer]
    B -->|Breakpoints| C[Chunk Planner]
    C -->|Chunk Plan| D[Download<br/>Manager]
    
    D -->|Byte Ranges| A
    D -->|Temp Files| E[Whisper<br/>Service]
    E -->|Transcripts| F[Chunk Cache]
    F -->|Cached Results| G[Transcript<br/>Merger]
    
    G -->|Merged Segments| H[Segment<br/>Aggregator]
    H -->|Final Transcript| I[Object Store<br/>Result]
    
    J[Backpressure<br/>Controller] -.->|Monitor| D
    J -.->|Monitor| E
    
    style A fill:#e3f2fd
    style I fill:#c8e6c9
    style E fill:#ffebee
    style J fill:#fff3e0
```

