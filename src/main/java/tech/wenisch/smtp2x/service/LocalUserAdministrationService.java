package tech.wenisch.smtp2x.service;

import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.smtp2x.domain.AppUser;
import tech.wenisch.smtp2x.domain.UserRole;
import tech.wenisch.smtp2x.repository.AppUserRepository;

@Service
public class LocalUserAdministrationService {
  private final AppUserRepository users;
  private final PasswordEncoder passwords;
  private final AuditService audit;

  public LocalUserAdministrationService(AppUserRepository users, PasswordEncoder passwords, AuditService audit) {
    this.users = users;
    this.passwords = passwords;
    this.audit = audit;
  }

  @Transactional public AppUser create(String email, String password, UserRole role, String actor) {
    validatePassword(password);
    String normalized = AppUser.normalize(email);
    if (normalized.isBlank()) throw new IllegalArgumentException("Email is required");
    if (users.findByEmailIgnoreCase(normalized).isPresent()) throw new IllegalArgumentException("A user with this email already exists");
    AppUser user = users.save(new AppUser(normalized, passwords.encode(password), role, false, false));
    audit.record(actor, "LOCAL_USER_CREATED", "user", user.getId().toString(), user.getEmail());
    return user;
  }

  @Transactional public AppUser changePassword(UUID id, String password, String actor) {
    validatePassword(password);
    AppUser user = users.findById(id).orElseThrow();
    if (user.isOidcOnly()) throw new IllegalArgumentException("OIDC-only users do not have a local password");
    user.changePassword(passwords.encode(password));
    user = users.save(user);
    audit.record(actor, "LOCAL_USER_PASSWORD_CHANGED", "user", id.toString(), user.getEmail());
    return user;
  }

  private static void validatePassword(String password) {
    if (password == null || password.length() < 8)
      throw new IllegalArgumentException("Password must contain at least 8 characters");
  }
}
