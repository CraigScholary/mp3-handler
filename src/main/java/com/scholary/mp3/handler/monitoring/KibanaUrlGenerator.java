package com.scholary.mp3.handler.monitoring;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Generates Kibana dashboard URLs for job monitoring.
 *
 * <p>Creates direct links to Kibana Discover with pre-filtered queries for specific jobs.
 */
@Component
public class KibanaUrlGenerator {

  private final String kibanaBaseUrl;
  private final String indexPattern;

  public KibanaUrlGenerator(
      @Value("${kibana.baseUrl:http://localhost:5601}") String kibanaBaseUrl,
      @Value("${kibana.indexPattern:mp3handler-logs-*}") String indexPattern) {
    this.kibanaBaseUrl = kibanaBaseUrl;
    this.indexPattern = indexPattern;
  }

  /**
   * Generate Kibana Discover URL for a specific job.
   *
   * @param jobId the job ID to filter by
   * @return Kibana URL with pre-filtered query
   */
  public String generateJobUrl(String jobId) {
    try {
      // Build Kibana query: jobId:"abc-123"
      String query = String.format("jobId:\"%s\"", jobId);
      String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());

      // Build Kibana Discover URL with query
      // Format: /app/discover#/?_a=(query:(language:kuery,query:'jobId:"abc-123"'))
      return String.format(
          "%s/app/discover#/?_a=(index:'%s',query:(language:kuery,query:'%s'))",
          kibanaBaseUrl, indexPattern, encodedQuery);

    } catch (UnsupportedEncodingException e) {
      // Should never happen with UTF-8
      throw new RuntimeException("Failed to encode Kibana URL", e);
    }
  }

  /**
   * Generate Kibana Dashboard URL for job monitoring.
   *
   * @param jobId the job ID
   * @return Kibana dashboard URL (if dashboard exists)
   */
  public String generateDashboardUrl(String jobId) {
    // TODO: Create custom dashboard and return URL
    // For now, return Discover URL
    return generateJobUrl(jobId);
  }

  /**
   * Generate Kibana URL for all recent jobs.
   *
   * @return Kibana URL showing all recent transcription jobs
   */
  public String generateAllJobsUrl() {
    try {
      String query = "event_type:*";
      String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());

      return String.format(
          "%s/app/discover#/?_a=(index:'%s',query:(language:kuery,query:'%s'))",
          kibanaBaseUrl, indexPattern, encodedQuery);

    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException("Failed to encode Kibana URL", e);
    }
  }
}
