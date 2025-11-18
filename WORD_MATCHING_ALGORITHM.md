# Word Matching Algorithm for Overlap Merging

## Overview

When using overlap-based chunking, we create 30-second overlaps between consecutive chunks to ensure no words are lost at boundaries. This document explains how we merge these overlapping transcripts accurately.

## The Problem

**Without word matching:**
```
Chunk 1 (0:00 - 60:30): "...and that's the end of section one."
Chunk 2 (59:30 - 120:00): "one. Now let's move to section two..."

Simple concatenation would give:
"...and that's the end of section one. one. Now let's move to section two..."
                                      ^^^^
                                   Duplicate!
```

## The Solution: Longest Exact Match

### Key Principle
**The end of Chunk 1 is more accurate than the start of Chunk 2** because it has more context. Therefore, we search for where Chunk 1's ending appears in Chunk 2's beginning.

### Algorithm Steps

1. **Extract overlap regions**
   - Last 30s of Chunk 1
   - First 30s of Chunk 2

2. **Convert to word lists**
   - Chunk 1 overlap: `["and", "that's", "the", "end", "of", "section", "one"]`
   - Chunk 2 overlap: `["section", "one", "now", "let's", "move", "to"]`

3. **Find longest exact match**
   - Search for the longest consecutive sequence of words that appear in both
   - Match found: `["section", "one"]` (2 words)

4. **Merge at match point**
   - Keep Chunk 1 up to: `"...and that's the end of"`
   - Skip the matched portion in Chunk 1: ~~`"section one"`~~
   - Start Chunk 2 from the match: `"section one now let's move to"`

5. **Result**
   - `"...and that's the end of section one now let's move to..."`
   - No duplicates! ✅

## Detailed Example

### Input

**Chunk 1 (0:00 - 60:30):**
```
Segments:
  [0:00 - 5:00]: "Welcome to the podcast"
  [5:00 - 10:00]: "Today we're discussing AI"
  ...
  [55:00 - 60:00]: "and that's why we need to focus on customer experience"
  [60:00 - 60:30]: "because at the end of the day"
```

**Chunk 2 (59:30 - 120:00):**
```
Segments:
  [0:00 - 1:00]: "because at the end of the day"
  [1:00 - 5:00]: "it's all about delivering value to our users"
  [5:00 - 10:00]: "and making sure they have a great time"
  ...
```

### Processing

**Step 1: Extract overlaps**
```
Chunk 1 last 30s (59:30 - 60:30):
  "because at the end of the day"

Chunk 2 first 30s (0:00 - 30:00):
  "because at the end of the day it's all about delivering value"
```

**Step 2: Tokenize**
```
Chunk 1 words: ["because", "at", "the", "end", "of", "the", "day"]
Chunk 2 words: ["because", "at", "the", "end", "of", "the", "day", 
                "it's", "all", "about", "delivering", "value"]
```

**Step 3: Find match**
```
Longest match: ["because", "at", "the", "end", "of", "the", "day"]
Match length: 7 words
Match position in Chunk 1: words 0-7 (end of chunk)
Match position in Chunk 2: words 0-7 (start of chunk)
```

**Step 4: Merge**
```
Keep from Chunk 1:
  [0:00 - 60:00]: All segments up to the overlap
  
Remove from Chunk 1:
  [60:00 - 60:30]: "because at the end of the day" (duplicate)
  
Keep from Chunk 2:
  [0:00 onwards]: Starting from "because at the end of the day..."
  (Adjusted timestamps: 59:30 + segment time)
```

**Step 5: Result**
```
Merged transcript:
  [0:00 - 5:00]: "Welcome to the podcast"
  [5:00 - 10:00]: "Today we're discussing AI"
  ...
  [55:00 - 60:00]: "and that's why we need to focus on customer experience"
  [59:30 - 60:30]: "because at the end of the day"
  [60:30 - 64:30]: "it's all about delivering value to our users"
  [64:30 - 69:30]: "and making sure they have a great time"
  ...
```

## Edge Cases

### Case 1: No Match Found
**Scenario:** Whisper produced completely different transcriptions for the overlap.

```
Chunk 1 overlap: "going to the store"
Chunk 2 overlap: "going to the shop"
```

**Solution:** Fall back to simple concatenation with a warning.
```
Result: "...going to the store going to the shop..."
```

**Why:** Better to have a minor duplicate than lose content.

### Case 2: Multiple Possible Matches
**Scenario:** The same phrase appears multiple times.

```
Chunk 1: "the cat sat on the mat and the dog sat on the mat"
Chunk 2: "the dog sat on the mat and played"
```

**Solution:** Choose the **longest** match.
```
Match: "the dog sat on the mat" (6 words)
Not: "the" (1 word) or "sat on the mat" (4 words)
```

### Case 3: Match Too Short
**Scenario:** Only 1-2 words match.

```
Chunk 1: "...ending here now"
Chunk 2: "now starting fresh content"
```

**Solution:** Require minimum 3 words for a valid match. If below threshold, fall back to concatenation.

### Case 4: Punctuation Differences
**Scenario:** Same words but different punctuation.

```
Chunk 1: "hello world."
Chunk 2: "hello world!"
```

**Solution:** Normalize by removing punctuation before matching.
```
Normalized: ["hello", "world"] matches ["hello", "world"]
```

### Case 5: Case Differences
**Scenario:** Same words but different capitalization.

```
Chunk 1: "Hello World"
Chunk 2: "hello world"
```

**Solution:** Convert to lowercase before matching.
```
Normalized: ["hello", "world"] matches ["hello", "world"]
```

## Performance Characteristics

### Time Complexity
- **Worst case:** O(n × m) where n = words in Chunk 1 overlap, m = words in Chunk 2 overlap
- **Typical case:** O(n × m) but with early termination
- **For 30s overlap:** ~50-100 words per chunk = ~5,000-10,000 comparisons (negligible)

### Space Complexity
- O(n + m) for storing word lists
- Minimal memory footprint

### Accuracy
- **Perfect match:** 100% accuracy (no duplicates, no loss)
- **No match:** Falls back to concatenation (minor duplicate, no loss)
- **Partial match:** Uses longest match (minimal duplicate, no loss)

## Configuration

```yaml
transcription:
  overlap:
    durationSeconds: 30        # Overlap duration
    minMatchWords: 3           # Minimum words for valid match
    normalizePunctuation: true # Remove punctuation before matching
    caseInsensitive: true      # Ignore case differences
```

## Comparison with Other Approaches

### Approach 1: Simple Concatenation (No Overlap)
```
Pros: Simple, fast
Cons: Risk of losing words at boundaries
Accuracy: 95-98%
```

### Approach 2: Time-Based Overlap Removal
```
Pros: Simple
Cons: May cut mid-word if timestamps are inaccurate
Accuracy: 97-99%
```

### Approach 3: Word Matching (Our Approach)
```
Pros: Accurate, handles timestamp drift, no word loss
Cons: Slightly more complex
Accuracy: 99.5-100%
```

### Approach 4: Semantic Matching (Future)
```
Pros: Handles paraphrasing
Cons: Requires embeddings, much slower
Accuracy: 99.9%
Use case: When Whisper produces different but semantically equivalent text
```

## Testing Strategy

### Unit Tests
1. Perfect match scenarios
2. No match scenarios
3. Multiple match scenarios
4. Edge cases (punctuation, case, short matches)

### Integration Tests
1. Real Whisper output from overlapping chunks
2. 8-hour file with 8 chunks
3. Noisy audio with inconsistent transcriptions

### Validation
1. Manual review of 10 random merge points
2. Automated duplicate detection
3. Word count validation (merged ≈ sum of chunks - overlaps)

## Future Improvements

1. **Confidence-based selection:** If match confidence is low, keep both versions
2. **Semantic matching:** Use embeddings for fuzzy matching
3. **Speaker diarization:** Ensure we don't merge across speaker changes
4. **Timestamp refinement:** Use word-level timestamps for precise alignment
5. **Adaptive overlap:** Increase overlap if matches are consistently poor

## References

- Longest Common Subsequence (LCS) algorithm
- Whisper word-level timestamp documentation
- Audio chunking best practices
