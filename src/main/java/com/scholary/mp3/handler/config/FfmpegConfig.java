package com.scholary.mp3.handler.config;

import com.scholary.mp3.handler.chunking.FfmpegProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for ffmpeg-related beans.
 *
 * <p>Enables the FfmpegProperties to be loaded from application.yml.
 */
@Configuration
@EnableConfigurationProperties(FfmpegProperties.class)
public class FfmpegConfig {}
