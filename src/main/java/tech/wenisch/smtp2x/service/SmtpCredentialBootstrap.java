package tech.wenisch.smtp2x.service;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tech.wenisch.smtp2x.config.Smtp2xProperties;
import tech.wenisch.smtp2x.domain.SmtpCredential;
import tech.wenisch.smtp2x.repository.SmtpCredentialRepository;

/** Creates the environment-provided SMTP account once; later credential changes remain explicit. */
@Component
@Order(5)
public class SmtpCredentialBootstrap implements CommandLineRunner {
  private final Smtp2xProperties properties;
  private final SmtpCredentialRepository credentials;
  private final PasswordEncoder passwords;
  private final AuditService audit;

  public SmtpCredentialBootstrap(Smtp2xProperties properties, SmtpCredentialRepository credentials,
      PasswordEncoder passwords, AuditService audit) {
    this.properties = properties;
    this.credentials = credentials;
    this.passwords = passwords;
    this.audit = audit;
  }

  @Override public void run(String... args) {
    String username = properties.smtp().username();
    String password = properties.smtp().password();
    if (blank(username) && blank(password)) return;
    if (blank(username) || blank(password))
      throw new IllegalStateException("SMTP2X_SMTP_USERNAME and SMTP2X_SMTP_PASSWORD must be set together");
    if (credentials.findByUsername(username).isEmpty()) {
      SmtpCredential credential = credentials.save(new SmtpCredential(username, passwords.encode(password)));
      audit.record("system", "SMTP_CREDENTIAL_CREATED", "smtpCredential", credential.getId().toString(), username);
    }
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
