package com.scholary.mp3.handler.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for transcription-related beans.
 *
 * <p>Enables the TranscriptionProperties to be loaded from application.yml.
 */
@Configuration
@EnableConfigurationProperties(TranscriptionProperties.class)
public class TranscriptionConfig {}
