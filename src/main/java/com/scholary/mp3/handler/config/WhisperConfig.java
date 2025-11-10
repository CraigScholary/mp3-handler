package com.scholary.mp3.handler.config;

import com.scholary.mp3.handler.whisper.WhisperProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Whisper client.
 *
 * <p>Enables the WhisperProperties to be loaded from application.yml.
 */
@Configuration
@EnableConfigurationProperties(WhisperProperties.class)
public class WhisperConfig {}
