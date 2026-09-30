package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.smtp2x.config.Smtp2xProperties;

class SecretCipherTest {
  @TempDir Path data;

  @Test void createsAndReusesKeyWhenNoEnvironmentKeyIsConfigured() throws Exception {
    var properties = new Smtp2xProperties(data.toString(), null, new Smtp2xProperties.Crypto(""), null, null);
    var first = new SecretCipher(properties);
    String encrypted = first.encrypt("gitlab-token");

    var second = new SecretCipher(properties);

    assertThat(Files.readString(data.resolve("encryption.key"))).isNotBlank();
    assertThat(second.decrypt(encrypted)).isEqualTo("gitlab-token");
  }
}
