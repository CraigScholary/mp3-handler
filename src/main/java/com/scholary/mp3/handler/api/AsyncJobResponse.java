package com.scholary.mp3.handler.api;

/**
 * Response for async transcription request.
 *
 * <p>Returns a job ID that can be used to poll for status and a Kibana URL for monitoring.
 */
public record AsyncJobResponse(String jobId, String kibanaUrl) {}
