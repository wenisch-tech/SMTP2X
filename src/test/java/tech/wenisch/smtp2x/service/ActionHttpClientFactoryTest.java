package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActionHttpClientFactoryTest {
  private static final char[] STORE_PASSWORD = "changeit".toCharArray();
  @TempDir
  Path temporaryDirectory;
  private HttpsServer server;
  private String url;

  @BeforeEach
  void startSelfSignedServer() throws Exception {
    server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext()));
    server.createContext("/health", exchange -> {
      byte[] response = "ready".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    url = "https://localhost:" + server.getAddress().getPort() + "/health";
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void tlsErrorsAreOnlyIgnoredWhenExplicitlyEnabled() {
    var factory = new ActionHttpClientFactory();
    var json = new ObjectMapper();

    assertThatThrownBy(() -> factory.forConfiguration(json.createObjectNode())
        .get().uri(url).retrieve().body(String.class))
        .hasCauseInstanceOf(SSLHandshakeException.class);

    var configuration = json.createObjectNode().put("ignoreTlsErrors", true);
    assertThat(factory.forConfiguration(configuration).get().uri(url).retrieve().body(String.class))
        .isEqualTo("ready");
    assertThat(factory.forPatchConfiguration(configuration).patch().uri(url).body("")
        .retrieve().body(String.class)).isEqualTo("ready");
  }

  private SSLContext serverContext() throws Exception {
    Path keyStorePath = temporaryDirectory.resolve("self-signed.p12");
    String executable = System.getProperty("os.name").toLowerCase().contains("win")
        ? "keytool.exe"
        : "keytool";
    Process process = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", executable).toString(),
        "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-storetype", "PKCS12",
        "-keystore", keyStorePath.toString(), "-storepass", String.valueOf(STORE_PASSWORD),
        "-keypass", String.valueOf(STORE_PASSWORD), "-dname", "CN=wrong-host.invalid",
        "-validity", "1", "-ext", "SAN=dns:wrong-host.invalid")
        .redirectErrorStream(true)
        .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output).isZero();

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(keyStorePath)) {
      keyStore.load(input, STORE_PASSWORD);
    }
    KeyManagerFactory keyManagers = KeyManagerFactory
        .getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, STORE_PASSWORD);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers.getKeyManagers(), null, null);
    return context;
  }
}
