package com.scholary.mp3.handler.chunking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for ffmpeg operations.
 *
 * <p>These control how we detect silence and handle audio encoding. The defaults are tuned for
 * typical speech audio.
 */
@ConfigurationProperties(prefix = "ffmpeg")
@Validated
public record FfmpegProperties(
    @NotBlank String silenceNoiseThreshold,
    @Positive double silenceMinDuration,
    @Positive double maxNudgeDistance,
    @Positive int audioQuality) {}
