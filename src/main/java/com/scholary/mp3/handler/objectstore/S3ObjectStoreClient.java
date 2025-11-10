package com.scholary.mp3.handler.objectstore;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * S3/MinIO implementation of ObjectStoreClient.
 *
 * <p>This uses AWS SDK v2, which works with both real S3 and S3-compatible services like MinIO. The
 * key difference is the endpoint and path-style access configuration.
 *
 * <p>Retry logic: The AWS SDK has built-in retries for transient failures (network issues, 500
 * errors, throttling). We configure exponential backoff with jitter to avoid thundering herd
 * problems. For non-retryable errors (404, 403), we fail fast.
 */
public class S3ObjectStoreClient implements ObjectStoreClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(S3ObjectStoreClient.class);

  private final S3Client s3Client;
  private final S3Presigner s3Presigner;

  public S3ObjectStoreClient(ObjectStoreProperties properties) {
    LOGGER.info(
        "Initializing S3 client: endpoint={}, bucket={}, pathStyleAccess={}",
        properties.endpoint(),
        properties.bucket(),
        properties.pathStyleAccess());

    // Build credentials provider
    AwsBasicCredentials credentials =
        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey());
    StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(credentials);

    // Determine region (use provided or default)
    Region region =
        properties.region() != null && !properties.region().isEmpty()
            ? Region.of(properties.region())
            : Region.US_EAST_1;

    // Build S3 client with custom endpoint for MinIO
    this.s3Client =
        S3Client.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .endpointOverride(URI.create(properties.endpoint()))
            .forcePathStyle(properties.pathStyleAccess()) // Required for MinIO
            .build();

    // Build presigner with same configuration
    this.s3Presigner =
        S3Presigner.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .endpointOverride(URI.create(properties.endpoint()))
            .build();

    LOGGER.info("S3 client initialized successfully");
  }

  @Override
  public InputStream getObjectStream(String bucket, String key) {
    LOGGER.debug("Fetching object: bucket={}, key={}", bucket, key);

    try {
      GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();

      // The SDK handles retries automatically for transient failures
      // We just need to handle the response
      InputStream stream = s3Client.getObject(request);

      LOGGER.info("Successfully retrieved object: bucket={}, key={}", bucket, key);
      return stream;

    } catch (NoSuchKeyException e) {
      // Object doesn't exist - this is not retryable
      String message = String.format("Object not found: bucket=%s, key=%s", bucket, key);
      LOGGER.error(message);
      throw new ObjectStoreException(message, e);

    } catch (S3Exception e) {
      // Other S3 errors (permissions, service issues, etc.)
      String message =
          String.format(
              "Failed to retrieve object: bucket=%s, key=%s, statusCode=%s",
              bucket, key, e.statusCode());
      LOGGER.error(message, e);
      throw new ObjectStoreException(message, e);

    } catch (Exception e) {
      // Unexpected errors
      String message =
          String.format("Unexpected error retrieving object: bucket=%s, key=%s", bucket, key);
      LOGGER.error(message, e);
      throw new ObjectStoreException(message, e);
    }
  }

  @Override
  public void putObject(
      String bucket, String key, InputStream data, long contentLength, String contentType) {
    LOGGER.debug(
        "Uploading object: bucket={}, key={}, contentLength={}, contentType={}",
        bucket,
        key,
        contentLength,
        contentType);

    try {
      PutObjectRequest request =
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType(contentType)
              .contentLength(contentLength)
              .build();

      // Upload with automatic retries
      s3Client.putObject(request, RequestBody.fromInputStream(data, contentLength));

      LOGGER.info("Successfully uploaded object: bucket={}, key={}", bucket, key);

    } catch (S3Exception e) {
      String message =
          String.format(
              "Failed to upload object: bucket=%s, key=%s, statusCode=%s",
              bucket, key, e.statusCode());
      LOGGER.error(message, e);
      throw new ObjectStoreException(message, e);

    } catch (Exception e) {
      String message =
          String.format("Unexpected error uploading object: bucket=%s, key=%s", bucket, key);
      LOGGER.error(message, e);
      throw new ObjectStoreException(message, e);
    }
  }

  @Override
  public URL presignGet(String bucket, String key, Duration ttl) {
    LOGGER.debug("Generating presigned URL: bucket={}, key={}, ttl={}", bucket, key, ttl);

    try {
      GetObjectRequest getObjectRequest =
          GetObjectRequest.builder().bucket(bucket).key(key).build();

      GetObjectPresignRequest presignRequest =
          GetObjectPresignRequest.builder()
              .signatureDuration(ttl)
              .getObjectRequest(getObjectRequest)
              .build();

      PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
      URL url = presignedRequest.url();

      LOGGER.info("Generated presigned URL: bucket={}, key={}, url={}", bucket, key, url);
      return url;

    } catch (S3Exception e) {
      String message =
          String.format(
              "Failed to generate presigned URL: bucket=%s, key=%s, statusCode=%s",
              bucket, key, e.statusCode());
      LOGGER.error(message, e);
      throw new ObjectStoreException(message, e);

    } catch (Exception e) {
      String message =
          String.format(
              "Unexpected error generating presigned URL: bucket=%s, key=%s", bucket, key);
      LOGGER.error(message, e);
      throw new ObjectStoreException(message, e);
    }
  }

  @Override
  public ObjectMetadata getObjectMetadata(String bucket, String key) {
    LOGGER.debug("Getting metadata for object: bucket={}, key={}", bucket, key);

    try {
      software.amazon.awssdk.services.s3.model.HeadObjectRequest request =
          software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .build();

      software.amazon.awssdk.services.s3.model.HeadObjectResponse response =
          s3Client.headObject(request);

      LOGGER.info(
          "Retrieved metadata: bucket={}, key={}, size={} bytes, contentType={}",
          bucket,
          key,
          response.contentLength(),
          response.contentType());

      return new ObjectMetadata(response.contentLength(), response.contentType());

    } catch (NoSuchKeyException e) {
      String message = String.format("Object not found: bucket=%s, key=%s", bucket, key);
      LOGGER.error(message);
      throw new ObjectStoreException(message, e);

    } catch (S3Exception e) {
      String message =
          String.format(
              "Failed to get metadata: bucket=%s, key=%s, statusCode=%s",
              bucket, key, e.statusCode());
      LOGGER.error(message, e);
      throw new ObjectStoreException(message, e);

    } catch (Exception e) {
      String message =
          String.format("Unexpected error getting metadata: bucket=%s, key=%s", bucket, key);
      LOGGER.error(message, e);
      throw new ObjectStoreException(message, e);
    }
  }

  @Override
  public InputStream getObjectRange(String bucket, String key, long startByte, long endByte) {
    LOGGER.debug(
        "Fetching byte range: bucket={}, key={}, range={}-{}", bucket, key, startByte, endByte);

    try {
      GetObjectRequest request =
          GetObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .range(String.format("bytes=%d-%d", startByte, endByte))
              .build();

      InputStream stream = s3Client.getObject(request);

      LOGGER.info(
          "Successfully retrieved byte range: bucket={}, key={}, bytes={}-{}",
          bucket,
          key,
          startByte,
          endByte);
      return stream;

    } catch (NoSuchKeyException e) {
      String message = String.format("Object not found: bucket=%s, key=%s", bucket, key);
      LOGGER.error(message);
      throw new ObjectStoreException(message, e);

    } catch (S3Exception e) {
      String message =
          String.format(
              "Failed to retrieve byte range: bucket=%s, key=%s, range=%d-%d, statusCode=%s",
              bucket, key, startByte, endByte, e.statusCode());
      LOGGER.error(message, e);
      throw new ObjectStoreException(message, e);

    } catch (Exception e) {
      String message =
          String.format(
              "Unexpected error retrieving byte range: bucket=%s, key=%s, range=%d-%d",
              bucket, key, startByte, endByte);
      LOGGER.error(message, e);
      throw new ObjectStoreException(message, e);
    }
  }

  /**
   * Clean up resources when the client is no longer needed.
   *
   * <p>This should be called when the application shuts down to release connections and threads.
   */
  public void close() {
    LOGGER.info("Closing S3 client and presigner");
    s3Client.close();
    s3Presigner.close();
  }
}
