package tech.wenisch.smtp2x.config;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Map;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class OidcTlsConfiguration {
  private static final Logger log = LoggerFactory.getLogger(OidcTlsConfiguration.class);

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "smtp2x.security.oidc.ignore-tls", havingValue = "true")
  CloseableHttpClient insecureOidcHttpClient() {
    try {
      var sslContext = SSLContexts.custom()
          .loadTrustMaterial((certificateChain, authType) -> true)
          .build();
      var tlsStrategy = ClientTlsStrategyBuilder.create()
          .setSslContext(sslContext)
          .setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
          .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
          .buildClassic();
      var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
          .setTlsSocketStrategy(tlsStrategy)
          .build();
      return HttpClients.custom().setConnectionManager(connectionManager).build();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to configure OIDC TLS verification bypass", exception);
    }
  }

  @Bean
  RestClientAuthorizationCodeTokenResponseClient oidcTokenResponseClient(Smtp2xProperties properties,
      ObjectProvider<CloseableHttpClient> insecureClients) {
    var client = new RestClientAuthorizationCodeTokenResponseClient();
    if (properties.security().oidc().ignoreTls()) {
      client.setRestClient(RestClient.builder()
          .requestFactory(insecureRequestFactory(insecureClients.getObject()))
          .configureMessageConverters(converters -> converters
              .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
          .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
          .build());
    }
    return client;
  }

  @Bean
  @ConditionalOnProperty(name = "smtp2x.security.oidc.ignore-tls", havingValue = "true")
  ClientRegistrationRepository insecureClientRegistrationRepository(OAuth2ClientProperties properties,
      CloseableHttpClient insecureClient) {
    log.warn("OIDC_IGNORE_TLS is enabled: OIDC TLS certificate and hostname verification are disabled. Use only for temporary troubleshooting.");
    var registrations = new ArrayList<ClientRegistration>();
    properties.getRegistration().forEach((registrationId, registration) ->
        registrations.add(insecureClientRegistration(registrationId, registration, properties, insecureClient)));
    return new InMemoryClientRegistrationRepository(registrations);
  }

  @Bean
  @ConditionalOnProperty(name = "smtp2x.security.oidc.ignore-tls", havingValue = "true")
  JwtDecoderFactory<ClientRegistration> insecureIdTokenDecoderFactory(CloseableHttpClient insecureClient) {
    return registration -> {
      var decoder = NimbusJwtDecoder.withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
          .restOperations(insecureRestTemplate(insecureClient))
          .build();
      decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
      decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
          JwtValidators.createDefault(), new OidcIdTokenValidator(registration)));
      return decoder;
    };
  }

  private ClientRegistration insecureClientRegistration(String registrationId,
      OAuth2ClientProperties.Registration registration, OAuth2ClientProperties properties,
      CloseableHttpClient insecureClient) {
    String providerId = StringUtils.hasText(registration.getProvider())
        ? registration.getProvider() : registrationId;
    OAuth2ClientProperties.Provider provider = properties.getProvider().get(providerId);
    if (provider == null || !StringUtils.hasText(provider.getIssuerUri())) {
      throw new IllegalStateException("OIDC_IGNORE_TLS requires an issuer-uri for provider " + providerId);
    }

    Map<String, Object> metadata = discover(provider.getIssuerUri(), insecureClient);
    ClientRegistration.Builder builder = ClientRegistrations.fromOidcConfiguration(metadata)
        .registrationId(registrationId)
        .clientId(registration.getClientId())
        .clientSecret(registration.getClientSecret())
        .clientAuthenticationMethod(StringUtils.hasText(registration.getClientAuthenticationMethod())
            ? new ClientAuthenticationMethod(registration.getClientAuthenticationMethod())
            : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(StringUtils.hasText(registration.getAuthorizationGrantType())
            ? new AuthorizationGrantType(registration.getAuthorizationGrantType())
            : AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri(StringUtils.hasText(registration.getRedirectUri())
            ? registration.getRedirectUri() : "{baseUrl}/login/oauth2/code/{registrationId}")
        .clientName(StringUtils.hasText(registration.getClientName())
            ? registration.getClientName() : registrationId);
    if (registration.getScope() != null && !registration.getScope().isEmpty()) {
      builder.scope(registration.getScope());
    }
    return builder.build();
  }

  private Map<String, Object> discover(String issuerUri, CloseableHttpClient insecureClient) {
    String metadataUrl = (issuerUri.endsWith("/") ? issuerUri : issuerUri + "/")
        + ".well-known/openid-configuration";
    Map<String, Object> metadata = RestClient.builder()
        .requestFactory(insecureRequestFactory(insecureClient))
        .build()
        .get()
        .uri(metadataUrl)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
    if (metadata == null) {
      throw new IllegalStateException("OIDC discovery document at " + metadataUrl + " was empty");
    }
    return metadata;
  }

  private RestTemplate insecureRestTemplate(CloseableHttpClient insecureClient) {
    var restTemplate = new RestTemplate(insecureRequestFactory(insecureClient));
    restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
    return restTemplate;
  }

  private HttpComponentsClientHttpRequestFactory insecureRequestFactory(CloseableHttpClient client) {
    return new HttpComponentsClientHttpRequestFactory(client);
  }
}
