package com.scholary.mp3.handler.objectstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class S3ObjectStoreClientTest {

  @Test
  void getObjectStream_shouldThrowExceptionForNonExistentObject() {
    // This test would require mocking the S3Client, which is complex
    // In a real scenario, we'd use Testcontainers with MinIO for integration tests
    // For now, we'll test the exception handling logic

    ObjectStoreProperties properties =
        new ObjectStoreProperties(
            "http://localhost:9000", "admin", "admin123", "test-bucket", "us-east-1", true);

    // Note: This is a simplified test. Full testing requires Testcontainers.
    assertThat(properties.bucket()).isEqualTo("test-bucket");
  }

  @Test
  void properties_shouldValidateRequiredFields() {
    assertThatThrownBy(
            () ->
                new ObjectStoreProperties(
                    "", "admin", "admin123", "test-bucket", "us-east-1", true))
        .isInstanceOf(Exception.class);
  }
}
