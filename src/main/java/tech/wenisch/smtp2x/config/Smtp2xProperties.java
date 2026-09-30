package tech.wenisch.smtp2x.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "smtp2x")
public record Smtp2xProperties(String dataDirectory, Security security, Crypto crypto, Smtp smtp, Retention retention) {
  public record Security(String initialAdminEmail, String initialAdminPassword, Oidc oidc) {}
  public record Oidc(boolean enabled, boolean roleMappingEnabled) {}
  public record Crypto(String key) {}
  public record Smtp(boolean enabled, int port, int maxMessageBytes, int maxRecipients, int maxConnections,
      Mode authentication, Mode starttls, List<String> allowedCidrs, String certificatePath, String privateKeyPath,
      String tlsKeystorePath, String tlsKeystorePassword) {}
  public enum Mode { DISABLED, OPTIONAL, REQUIRED }
  public record Retention(int contentDays, int auditDays) {}
}
