package tech.wenisch.smtp2x.service;

import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.RejectException;
import org.subethamail.smtp.TooMuchDataException;
import org.subethamail.smtp.auth.EasyAuthenticationHandlerFactory;
import org.subethamail.smtp.auth.LoginFailedException;
import org.subethamail.smtp.server.SMTPServer;
import tech.wenisch.smtp2x.config.Smtp2xProperties;
import tech.wenisch.smtp2x.repository.SmtpCredentialRepository;

@Service
public class SmtpServerService {
  private static final Logger log = LoggerFactory.getLogger(SmtpServerService.class);
  private final Smtp2xProperties properties;
  private final MessageIngestionService ingestion;
  private final SmtpCredentialRepository credentials;
  private final PasswordEncoder passwords;
  private final ApplicationMetrics metrics;
  private volatile SMTPServer server;

  public SmtpServerService(Smtp2xProperties properties, MessageIngestionService ingestion,
      SmtpCredentialRepository credentials, PasswordEncoder passwords, ApplicationMetrics metrics) {
    this.properties = properties;
    this.ingestion = ingestion;
    this.credentials = credentials;
    this.passwords = passwords;
    this.metrics = metrics;
  }

  @Bean
  @Order(10)
  ApplicationRunner smtpRunner() {
    return args -> {
      if (properties.smtp().enabled()) start();
      else log.info("SMTP listener disabled; set SMTP2X_SMTP_ENABLED=true after configuring routing rules");
    };
  }

  public synchronized void start() {
    if (server != null && server.isRunning()) return;
    SMTPServer.Builder builder = SMTPServer.port(properties.smtp().port())
        .maxConnections(properties.smtp().maxConnections())
        .maxRecipients(properties.smtp().maxRecipients())
        .maxMessageSize(properties.smtp().maxMessageBytes())
        .connectionTimeout(60, TimeUnit.SECONDS)
        .messageHandlerFactory(TransactionHandler::new);
    if (properties.smtp().authentication() != Smtp2xProperties.Mode.DISABLED) {
      builder.authenticationHandlerFactory(new EasyAuthenticationHandlerFactory(
          (username, password, context) -> {
            var credential = credentials.findByUsername(username)
                .filter(value -> value.isEnabled()
                    && passwords.matches(password, value.getPasswordHash()));
            if (credential.isEmpty()) throw new LoginFailedException();
          }));
      if (properties.smtp().authentication() == Smtp2xProperties.Mode.REQUIRED) {
        builder.requireAuth();
      }
    }
    if (properties.smtp().starttls() != Smtp2xProperties.Mode.DISABLED) {
      SSLContext tls = tlsContext();
      if (tls == null) {
        log.warn("STARTTLS is not advertised because no PKCS#12 keystore is configured.");
        if (properties.smtp().starttls() == Smtp2xProperties.Mode.REQUIRED) {
          throw new IllegalStateException(
              "Required STARTTLS needs SMTP2X_SMTP_TLS_KEYSTORE_PATH and SMTP2X_SMTP_TLS_KEYSTORE_PASSWORD");
        }
      } else {
        builder.enableTLS().startTlsSocketFactory(tls);
        if (properties.smtp().starttls() == Smtp2xProperties.Mode.REQUIRED) builder.requireTLS();
      }
    }
    server = builder.build();
    server.start();
    log.info("SMTP2X SMTP listener started on port {}", server.getPortAllocated());
  }

  private SSLContext tlsContext() {
    String path = properties.smtp().tlsKeystorePath();
    String password = properties.smtp().tlsKeystorePassword();
    if (path == null || path.isBlank() || password == null || password.isBlank()) return null;
    try (var input = Files.newInputStream(Path.of(path))) {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(input, password.toCharArray());
      KeyManagerFactory managers = KeyManagerFactory.getInstance(
          KeyManagerFactory.getDefaultAlgorithm());
      managers.init(store, password.toCharArray());
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(managers.getKeyManagers(), null, null);
      return context;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load SMTP STARTTLS PKCS#12 keystore", e);
    }
  }

  private void receive(MessageContext context, String from, List<String> to, byte[] data)
      throws RejectException {
    if (!allowed(context)) {
      metrics.mailRejected(ApplicationMetrics.RejectionReason.NOT_ALLOWED);
      throw new RejectException(550, "Client IP is not allowed");
    }
    try {
      ingestion.accept(from, to, data);
    } catch (IllegalArgumentException e) {
      metrics.mailRejected(ApplicationMetrics.RejectionReason.INVALID);
      throw new RejectException(550, e.getMessage());
    } catch (Exception e) {
      metrics.mailRejected(ApplicationMetrics.RejectionReason.PROCESSING_ERROR);
      log.warn("Could not durably accept SMTP message", e);
      throw new RejectException(451, "SMTP2X could not accept the message; please retry");
    }
  }

  private boolean allowed(MessageContext context) {
    List<String> cidrs = properties.smtp().allowedCidrs();
    if (cidrs == null || cidrs.stream().allMatch(value -> value == null || value.isBlank())) {
      return true;
    }
    if (!(context.getRemoteAddress() instanceof InetSocketAddress remote)) return false;
    return cidrs.stream().anyMatch(cidr -> inCidr(remote.getAddress(), cidr));
  }

  private boolean inCidr(InetAddress address, String cidr) {
    try {
      String[] values = cidr.trim().split("/", 2);
      InetAddress network = InetAddress.getByName(values[0]);
      if (network.getAddress().length != address.getAddress().length) return false;
      int prefix = values.length == 1 ? network.getAddress().length * 8
          : Integer.parseInt(values[1]);
      if (prefix < 0 || prefix > network.getAddress().length * 8) return false;
      byte[] target = address.getAddress();
      byte[] base = network.getAddress();
      for (int bit = 0; bit < prefix; bit++) {
        if (((target[bit / 8] >> (7 - bit % 8)) & 1)
            != ((base[bit / 8] >> (7 - bit % 8)) & 1)) return false;
      }
      return true;
    } catch (Exception e) {
      log.warn("Ignoring invalid SMTP allowlist CIDR {}", cidr);
      return false;
    }
  }

  private final class TransactionHandler implements MessageHandler {
    private final MessageContext context;
    private String from;
    private final List<String> recipients = new ArrayList<>();

    private TransactionHandler(MessageContext context) {
      this.context = context;
    }

    @Override
    public void from(String value) {
      from = value;
    }

    @Override
    public void recipient(String value) {
      recipients.add(value);
    }

    @Override
    public String data(java.io.InputStream stream)
        throws RejectException, TooMuchDataException, java.io.IOException {
      byte[] bytes = stream.readNBytes(properties.smtp().maxMessageBytes() + 1);
      if (bytes.length > properties.smtp().maxMessageBytes()) {
        metrics.mailRejected(ApplicationMetrics.RejectionReason.TOO_LARGE);
        throw new TooMuchDataException("Message exceeds configured size");
      }
      if (from == null || recipients.isEmpty()) {
        metrics.mailRejected(ApplicationMetrics.RejectionReason.INVALID);
        throw new RejectException("Sender and recipient are required");
      }
      receive(context, from, List.copyOf(recipients), bytes);
      return null;
    }

    @Override
    public void done() {}
  }

  @PreDestroy
  public synchronized void stop() {
    if (server != null) server.stop();
  }
}
