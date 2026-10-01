package tech.wenisch.smtp2x.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import tech.wenisch.smtp2x.domain.AppUser;
import tech.wenisch.smtp2x.domain.UserRole;
import tech.wenisch.smtp2x.repository.AppUserRepository;
import tech.wenisch.smtp2x.service.AuditService;

class OidcUserServiceTest {
  @Test
  void recognizesPrefixedRolesAndCommonClaimShapes() {
    assertThat(OidcUserService.mappedRole(Map.of(
        "roles", List.of("ROLE_SMTP2X_ADMIN"))))
      .isEqualTo(UserRole.ADMIN);
    assertThat(OidcUserService.mappedRole(Map.of(
        "realm_access", Map.of("roles", "ROLE_SMTP2X_VIEWER"))))
      .isEqualTo(UserRole.VIEWER);
    assertThat(OidcUserService.mappedRole(Map.of(
        "resource_access", Map.of("smtp2x", Map.of("roles", List.of("smtp2x_viewer"))))))
      .isEqualTo(UserRole.VIEWER);
  }

  @Test
  void adminWinsAndUnmappedUsersRemainPending() {
    assertThat(OidcUserService.mappedRole(Map.of(
        "roles", "ROLE_SMTP2X_VIEWER, ROLE_SMTP2X_ADMIN")))
      .isEqualTo(UserRole.ADMIN);
    assertThat(OidcUserService.mappedRole(Map.of(
        "roles", List.of("ROLE_OTHER_ADMIN", "SMTP2X_AUDITOR"))))
      .isEqualTo(UserRole.PENDING);
    assertThat(OidcUserService.mappedRole(Map.of()))
      .isEqualTo(UserRole.PENDING);
  }

  @Test
  void mappedAdminIsPersistedAndAuthenticatedAsLiveAdmin() {
    AppUserRepository users = mock(AppUserRepository.class);
    AuditService audit = mock(AuditService.class);
    Smtp2xProperties properties = new Smtp2xProperties(".",
        new Smtp2xProperties.Security("", "",
            new Smtp2xProperties.Oidc(true, true, false)),
        null, null, null);
    OidcUserService service = new OidcUserService(users, properties, audit);
    when(users.findByEmailIgnoreCase("admin@example.com")).thenReturn(Optional.empty());

    Instant now = Instant.now();
    OidcIdToken token = new OidcIdToken("token", now, now.plusSeconds(300), Map.of(
        "sub", "admin-subject",
        "email", "admin@example.com",
        "roles", List.of("ROLE_SMTP2X_ADMIN")));
    OidcUserRequest request = mock(OidcUserRequest.class);
    when(request.getIdToken()).thenReturn(token);

    var principal = service.loadUser(request);

    ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getRole()).isEqualTo(UserRole.ADMIN);
    assertThat(principal.getAuthorities()).extracting("authority")
      .contains("ROLE_ADMIN");
  }
}
