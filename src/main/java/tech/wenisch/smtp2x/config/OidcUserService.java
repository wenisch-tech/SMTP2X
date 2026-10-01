package tech.wenisch.smtp2x.config;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.smtp2x.domain.AppUser;
import tech.wenisch.smtp2x.domain.UserRole;
import tech.wenisch.smtp2x.repository.AppUserRepository;
import tech.wenisch.smtp2x.service.AuditService;

@Service
public class OidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
  private final AppUserRepository users;
  private final Smtp2xProperties properties;
  private final AuditService audit;

  public OidcUserService(AppUserRepository users, Smtp2xProperties properties,
      AuditService audit) {
    this.users = users;
    this.properties = properties;
    this.audit = audit;
  }

  @Override
  @Transactional
  public OidcUser loadUser(OidcUserRequest request) {
    Map<String, Object> claims = new LinkedHashMap<>(request.getIdToken().getClaims());
    String value = string(claims, "email");
    if (value == null) value = string(claims, "preferred_username");
    if (value == null)
      throw new IllegalArgumentException("OIDC token requires email or preferred_username");

    final String identity = value;
    AppUser user = users.findByEmailIgnoreCase(identity)
        .orElseGet(() -> new AppUser(identity, "{noop}oidc", UserRole.PENDING, false, true));
    if (properties.security().oidc().roleMappingEnabled()) user.role(mappedRole(claims));
    users.save(user);
    audit.record(identity, "OIDC_LOGIN", "user", user.getId().toString(),
        "role=" + user.getRole());

    claims.put("email", identity);
    Set<GrantedAuthority> authorities = new LinkedHashSet<>();
    authorities.add(new OAuth2UserAuthority(claims));
    authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
    return new DefaultOidcUser(authorities, request.getIdToken(), new OidcUserInfo(claims),
        "email");
  }

  static UserRole mappedRole(Map<String, Object> claims) {
    Set<String> roles = new HashSet<>();
    add(roles, claims.get("roles"));
    if (claims.get("realm_access") instanceof Map<?, ?> realm)
      add(roles, realm.get("roles"));
    if (claims.get("resource_access") instanceof Map<?, ?> resources)
      resources.values().forEach(value -> {
        if (value instanceof Map<?, ?> resource) add(roles, resource.get("roles"));
      });

    Set<UserRole> mapped = new HashSet<>();
    roles.stream().map(OidcUserService::mapRole).forEach(mapped::add);
    if (mapped.contains(UserRole.ADMIN)) return UserRole.ADMIN;
    if (mapped.contains(UserRole.VIEWER)) return UserRole.VIEWER;
    return UserRole.PENDING;
  }

  private static UserRole mapRole(String role) {
    String normalized = role.trim().toUpperCase(Locale.ROOT);
    if (normalized.startsWith("ROLE_")) normalized = normalized.substring("ROLE_".length());
    return switch (normalized) {
      case "SMTP2X_ADMIN" -> UserRole.ADMIN;
      case "SMTP2X_VIEWER" -> UserRole.VIEWER;
      default -> UserRole.PENDING;
    };
  }

  private static void add(Set<String> roles, Object value) {
    if (value instanceof String role) {
      for (String part : role.split("[,\\s]+"))
        if (!part.isBlank()) roles.add(part);
    } else if (value instanceof Collection<?> collection) {
      collection.forEach(item -> add(roles, item));
    }
  }

  private String string(Map<String, Object> claims, String key) {
    Object value = claims.get(key);
    return value instanceof String text && !text.isBlank() ? text.trim() : null;
  }
}
