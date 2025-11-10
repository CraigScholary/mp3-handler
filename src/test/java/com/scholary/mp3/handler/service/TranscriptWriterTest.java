package com.scholary.mp3.handler.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scholary.mp3.handler.transcript.MergedSegment;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranscriptWriterTest {

  private TranscriptWriter writer;

  @BeforeEach
  void setUp() {
    writer = new TranscriptWriter(new ObjectMapper());
  }

  @Test
  void writeJson_shouldProduceValidJson() throws IOException {
    List<MergedSegment> segments =
        List.of(
            new MergedSegment(0.0, 5.0, "Hello world"),
            new MergedSegment(5.0, 10.0, "This is a test"));

    byte[] json = writer.writeJson(segments, "en");
    String jsonStr = new String(json);

    assertThat(jsonStr).contains("\"language\"");
    assertThat(jsonStr).contains("\"segments\"");
    assertThat(jsonStr).contains("Hello world");
    assertThat(jsonStr).contains("This is a test");
  }

  @Test
  void writeSrt_shouldProduceValidSrtFormat() {
    List<MergedSegment> segments =
        List.of(
            new MergedSegment(0.0, 5.2, "Hello world"),
            new MergedSegment(5.2, 10.5, "This is a test"));

    byte[] srt = writer.writeSrt(segments);
    String srtStr = new String(srt);

    // Check for sequence numbers
    assertThat(srtStr).contains("1\n");
    assertThat(srtStr).contains("2\n");

    // Check for timecodes
    assertThat(srtStr).contains("00:00:00,000 --> 00:00:05,200");
    assertThat(srtStr).contains("00:00:05,200 --> 00:00:10,500");

    // Check for text
    assertThat(srtStr).contains("Hello world");
    assertThat(srtStr).contains("This is a test");
  }

  @Test
  void writeSrt_shouldFormatTimesCorrectly() {
    List<MergedSegment> segments = List.of(new MergedSegment(3661.5, 3665.75, "Test"));

    byte[] srt = writer.writeSrt(segments);
    String srtStr = new String(srt);

    // 3661.5 seconds = 1 hour, 1 minute, 1.5 seconds
    assertThat(srtStr).contains("01:01:01,500");

    // 3665.75 seconds = 1 hour, 1 minute, 5.75 seconds
    assertThat(srtStr).contains("01:01:05,750");
  }

  @Test
  void writeSrt_shouldHandleEmptySegments() {
    byte[] srt = writer.writeSrt(List.of());
    String srtStr = new String(srt);

    assertThat(srtStr).isEmpty();
  }
}
