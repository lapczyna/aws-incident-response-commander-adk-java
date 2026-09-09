package com.lapczynski.demotarget;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the fault-injectable demo target service.
 *
 * <p>This is the service the Commander investigates. It is intentionally separate from the
 * Commander so that an incident involves two processes, as a real one would.
 */
@SpringBootApplication
public class DemoTargetServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(DemoTargetServiceApplication.class, args);
  }
}
