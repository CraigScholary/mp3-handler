package com.scholary.mp3.handler.config;

import com.scholary.mp3.handler.objectstore.ObjectStoreClient;
import com.scholary.mp3.handler.objectstore.ObjectStoreProperties;
import com.scholary.mp3.handler.objectstore.S3ObjectStoreClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for object storage.
 *
 * <p>This wires up the ObjectStoreClient bean using properties from application.yml. Spring will
 * inject the properties and create the client at startup.
 */
@Configuration
@EnableConfigurationProperties(ObjectStoreProperties.class)
public class ObjectStoreConfig {

  @Bean
  public ObjectStoreClient objectStoreClient(ObjectStoreProperties properties) {
    return new S3ObjectStoreClient(properties);
  }
}
