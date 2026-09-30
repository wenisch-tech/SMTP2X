package tech.wenisch.smtp2x;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Smtp2xApplication {
  public static void main(String[] args) { SpringApplication.run(Smtp2xApplication.class, args); }
}
