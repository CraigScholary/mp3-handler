package com.scholary.mp3.handler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class Mp3HandlerApplication {

  public static void main(String[] args) {
    SpringApplication.run(Mp3HandlerApplication.class, args);
  }
}
