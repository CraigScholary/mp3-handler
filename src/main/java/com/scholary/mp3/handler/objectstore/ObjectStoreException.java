package com.scholary.mp3.handler.objectstore;

/**
 * Exception thrown when object storage operations fail.
 *
 * <p>This is a runtime exception because storage failures are typically unrecoverable at the
 * application level - we can retry, but if the bucket doesn't exist or credentials are wrong,
 * there's not much the caller can do.
 */
public class ObjectStoreException extends RuntimeException {

  public ObjectStoreException(String message) {
    super(message);
  }

  public ObjectStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
