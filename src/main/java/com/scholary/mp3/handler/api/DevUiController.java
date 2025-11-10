package com.scholary.mp3.handler.api;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller for serving the development UI.
 *
 * <p>Only active in dev profile. Provides a simple web interface for testing transcription,
 * previewing chunks, and viewing logs.
 */
@Controller
@Profile("dev")
public class DevUiController {

  @GetMapping("/dev")
  public String devUi() {
    return "forward:/dev.html";
  }
}
