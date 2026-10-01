package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.subethamail.smtp.client.SMTPClient;
import org.subethamail.smtp.server.SMTPServer;
import tech.wenisch.smtp2x.config.Smtp2xProperties;
import tech.wenisch.smtp2x.repository.SmtpCredentialRepository;

class SmtpServerServiceTest {
  @Test
  void disabledAuthenticationAcceptsAnonymousMailFromAndDoesNotAdvertiseAuth() throws Exception {
    SMTPServer server = service(Smtp2xProperties.Mode.DISABLED).createServer();
    assertThat(server.getRequireAuth()).isFalse();
    assertThat(server.getAuthenticationHandlerFactory()).isEmpty();

    server.start();
    try (SMTPClient client = SMTPClient.createAndConnect("127.0.0.1",
        server.getPortAllocated())) {
      assertThat(client.receiveAndCheck().getCode()).isEqualTo(220);
      var ehlo = client.sendReceive("EHLO smtp-client");
      assertThat(ehlo.getCode()).isEqualTo(250);
      assertThat(ehlo.getMessage()).doesNotContain("AUTH");

      var mailFrom = client.sendReceive("MAIL FROM:<sender@example.com>");
      assertThat(mailFrom.getCode()).isEqualTo(250);
      assertThat(mailFrom.getMessage()).doesNotContain("Authentication required");
    } finally {
      server.stop();
    }
  }

  @Test
  void requiredAuthenticationStillRejectsAnonymousMailFrom() throws Exception {
    SMTPServer server = service(Smtp2xProperties.Mode.REQUIRED).createServer();
    assertThat(server.getRequireAuth()).isTrue();
    assertThat(server.getAuthenticationHandlerFactory()).isPresent();

    server.start();
    try (SMTPClient client = SMTPClient.createAndConnect("127.0.0.1",
        server.getPortAllocated())) {
      assertThat(client.receiveAndCheck().getCode()).isEqualTo(220);
      assertThat(client.sendReceive("EHLO smtp-client").getCode()).isEqualTo(250);
      var mailFrom = client.sendReceive("MAIL FROM:<sender@example.com>");
      assertThat(mailFrom.getCode()).isEqualTo(530);
      assertThat(mailFrom.getMessage()).contains("Authentication required");
    } finally {
      server.stop();
    }
  }

  private SmtpServerService service(Smtp2xProperties.Mode authentication) {
    var smtp = new Smtp2xProperties.Smtp(true, 0, 1024 * 1024, 10, 5,
        authentication, Smtp2xProperties.Mode.DISABLED, List.of(), null, null,
        null, null, null, null);
    var properties = new Smtp2xProperties(".", null, null, smtp, null);
    return new SmtpServerService(properties, mock(MessageIngestionService.class),
        mock(SmtpCredentialRepository.class), mock(PasswordEncoder.class),
        mock(ApplicationMetrics.class));
  }
}
