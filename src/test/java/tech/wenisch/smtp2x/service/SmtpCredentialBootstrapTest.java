package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import tech.wenisch.smtp2x.config.Smtp2xProperties;
import tech.wenisch.smtp2x.domain.SmtpCredential;
import tech.wenisch.smtp2x.repository.SmtpCredentialRepository;

class SmtpCredentialBootstrapTest {
  @Test void createsHashedCredentialFromEnvironmentValues() throws Exception {
    var repository = mock(SmtpCredentialRepository.class);
    var audit = mock(AuditService.class);
    var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    when(repository.findByUsername("monitoring-app")).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var smtp = new Smtp2xProperties.Smtp(true, 2525, 1024, 10, 10,
        Smtp2xProperties.Mode.REQUIRED, Smtp2xProperties.Mode.DISABLED, List.of(), "", "", "", "",
        "monitoring-app", "smtp-secret");
    var properties = new Smtp2xProperties("/tmp", null, null, smtp, null);

    new SmtpCredentialBootstrap(properties, repository, encoder, audit).run();

    var saved = org.mockito.ArgumentCaptor.forClass(SmtpCredential.class);
    verify(repository).save(saved.capture());
    assertThat(saved.getValue().getUsername()).isEqualTo("monitoring-app");
    assertThat(encoder.matches("smtp-secret", saved.getValue().getPasswordHash())).isTrue();
  }
}
