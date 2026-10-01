package tech.wenisch.smtp2x.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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

  public RestClient forConfiguration(JsonNode configuration) {
    return configuration.path("ignoreTlsErrors").asBoolean(false)
        ? insecureClient
        : strictClient;
  }

  private static final class InsecureTlsRequestFactory extends SimpleClientHttpRequestFactory {
    private static final HostnameVerifier ACCEPT_ANY_HOSTNAME = (hostname, session) -> true;
    private final SSLSocketFactory socketFactory;

    private InsecureTlsRequestFactory() {
      try {
        TrustManager[] trustManagers = {new X509TrustManager() {
          @Override public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }

          @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {
          }

          @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {
          }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers, new SecureRandom());
        socketFactory = context.getSocketFactory();
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
