package tech.wenisch.smtp2x.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity @Table(name = "app_user")
public class AppUser {
  @Id private UUID id;
  @Column(nullable = false, unique = true, length = 320) private String email;
  @Column(name = "password_hash", nullable = false, length = 200) private String passwordHash;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private UserRole role;
  @Column(nullable = false) private boolean enabled = true;
  @Column(name = "password_change_required", nullable = false) private boolean passwordChangeRequired;
  @Column(name = "oidc_only", nullable = false) private boolean oidcOnly;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  protected AppUser() {}
  public AppUser(String email, String passwordHash, UserRole role, boolean passwordChangeRequired, boolean oidcOnly) {
    id = UUID.randomUUID(); this.email = normalize(email); this.passwordHash = passwordHash; this.role = role;
    this.passwordChangeRequired = passwordChangeRequired; this.oidcOnly = oidcOnly;
  }
  public UUID getId(){return id;} public String getEmail(){return email;} public String getPasswordHash(){return passwordHash;}
  public UserRole getRole(){return role;} public boolean isEnabled(){return enabled;} public boolean isPasswordChangeRequired(){return passwordChangeRequired;} public boolean isOidcOnly(){return oidcOnly;}
  public void role(UserRole value){role=value;} public void enabled(boolean value){enabled=value;}
  public void changePassword(String value){passwordHash=value; passwordChangeRequired=false;}
  public static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
}
