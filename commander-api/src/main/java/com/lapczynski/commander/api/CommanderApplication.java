package com.lapczynski.commander.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Incident Commander.
 *
 * <p>Component scanning is rooted one package up so the adapter modules (adk, persistence, aws,
 * simulator) are discovered, while the framework-free domain package contributes no beans.
 */
@SpringBootApplication(scanBasePackages = "com.lapczynski.commander")
public class CommanderApplication {

  public static void main(String[] args) {
    SpringApplication.run(CommanderApplication.class, args);
  }
}
