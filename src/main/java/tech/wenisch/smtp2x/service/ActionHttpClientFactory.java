package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ActionHttpClientFactory {
  private final RestClient strictClient = RestClient.builder()
      .requestFactory(new SimpleClientHttpRequestFactory())
      .build();
  private final RestClient insecureClient = RestClient.builder()
      .requestFactory(new InsecureTlsRequestFactory())
      .build();
  private final RestClient strictPatchClient = RestClient.builder()
      .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient()))
      .build();
  private final RestClient insecurePatchClient = RestClient.builder()
      .requestFactory(insecurePatchRequestFactory())
      .build();

  public RestClient forConfiguration(JsonNode configuration) {
    return configuration.path("ignoreTlsErrors").asBoolean(false)
        ? insecureClient
        : strictClient;
  }

  RestClient forPatchConfiguration(JsonNode configuration) {
    return configuration.path("ignoreTlsErrors").asBoolean(false)
        ? insecurePatchClient
        : strictPatchClient;
  }

  private static HttpComponentsClientHttpRequestFactory insecurePatchRequestFactory() {
    try {
      var socketFactory = SSLConnectionSocketFactoryBuilder.create()
          .setSslContext(insecureContext())
          .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
          .build();
      var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
          .setSSLSocketFactory(socketFactory)
          .build();
      var client = HttpClients.custom().setConnectionManager(connectionManager).build();
      return new HttpComponentsClientHttpRequestFactory(client);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Could not initialize the action TLS client", e);
    }
  }

  private static SSLContext insecureContext() throws GeneralSecurityException {
    TrustManager[] trustManagers = {new X509TrustManager() {
      @Override public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }

      @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}

      @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
    }};
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, trustManagers, new SecureRandom());
    return context;
  }

  private static final class InsecureTlsRequestFactory extends SimpleClientHttpRequestFactory {
    private static final HostnameVerifier ACCEPT_ANY_HOSTNAME = (hostname, session) -> true;
    private final SSLSocketFactory socketFactory;

    private InsecureTlsRequestFactory() {
      try {
        socketFactory = insecureContext().getSocketFactory();
      } catch (GeneralSecurityException e) {
        throw new IllegalStateException("Could not initialize the action TLS client", e);
      }
    }

    @Override
    protected void prepareConnection(HttpURLConnection connection, String httpMethod)
        throws IOException {
      if (connection instanceof HttpsURLConnection secureConnection) {
        secureConnection.setSSLSocketFactory(socketFactory);
        secureConnection.setHostnameVerifier(ACCEPT_ANY_HOSTNAME);
      }
      super.prepareConnection(connection, httpMethod);
    }
  }
}
