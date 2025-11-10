package com.scholary.mp3.handler.objectstore;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

/**
 * Abstraction for object storage operations.
 *
 * <p>This interface decouples our application from specific storage implementations (S3, MinIO,
 * GCS, etc.). It provides the essential operations we need: reading objects, writing objects, and
 * generating presigned URLs.
 *
 * <p>Why an abstraction? Because we want to be able to swap storage backends without rewriting
 * business logic. Also makes testing easier - we can mock this interface.
 */
public interface ObjectStoreClient {

  /**
   * Retrieve an object as a stream.
   *
   * <p>Streaming is critical for large files - we don't want to load a 2GB MP3 into memory. The
   * caller is responsible for closing the stream.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @return an input stream for reading the object
   * @throws ObjectStoreException if the object doesn't exist or retrieval fails
   */
  InputStream getObjectStream(String bucket, String key);

  /**
   * Store an object from a stream.
   *
   * <p>Again, streaming to avoid memory issues. The caller should provide accurate content length
   * and type for optimal performance.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @param data the input stream containing object data
   * @param contentLength the size of the object in bytes
   * @param contentType the MIME type of the object
   * @throws ObjectStoreException if the upload fails
   */
  void putObject(
      String bucket, String key, InputStream data, long contentLength, String contentType);

  /**
   * Generate a presigned URL for temporary access to an object.
   *
   * <p>Useful for sharing files without exposing credentials. The URL expires after the specified
   * TTL.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @param ttl time-to-live for the URL
   * @return a presigned URL
   * @throws ObjectStoreException if URL generation fails
   */
  URL presignGet(String bucket, String key, Duration ttl);

  /**
   * Get object metadata without downloading the content.
   *
   * <p>Useful for getting file size and content type before downloading.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @return object metadata
   * @throws ObjectStoreException if the object doesn't exist or retrieval fails
   */
  ObjectMetadata getObjectMetadata(String bucket, String key);

  /**
   * Retrieve a byte range from an object.
   *
   * <p>This is critical for streaming large files - we can download only the bytes we need for each
   * chunk instead of the entire file.
   *
   * @param bucket the bucket name
   * @param key the object key
   * @param startByte the starting byte position (inclusive)
   * @param endByte the ending byte position (inclusive)
   * @return an input stream for reading the byte range
   * @throws ObjectStoreException if the object doesn't exist or retrieval fails
   */
  InputStream getObjectRange(String bucket, String key, long startByte, long endByte);

  /** Object metadata returned by getObjectMetadata. */
  record ObjectMetadata(long contentLength, String contentType) {}
}
