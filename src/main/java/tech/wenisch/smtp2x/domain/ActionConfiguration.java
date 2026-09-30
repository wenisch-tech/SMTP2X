package tech.wenisch.smtp2x.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "action_configuration")
public class ActionConfiguration {
  @Id private UUID id = UUID.randomUUID();
  @Column(nullable=false, unique=true, length=120) private String name;
  @Enumerated(EnumType.STRING) @Column(nullable=false, length=32) private ActionType type;
  @Column(nullable=false) private boolean enabled = true;
  @Lob @Column(name="configuration_json", nullable=false) private String configurationJson = "{}";
  @Column(name="created_at", nullable=false) private Instant createdAt=Instant.now();
  @Column(name="updated_at", nullable=false) private Instant updatedAt=Instant.now();
  protected ActionConfiguration() {}
  public ActionConfiguration(String name, ActionType type, String config) { this.name=name; this.type=type; this.configurationJson=config; }
  public UUID getId(){return id;} public String getName(){return name;} public ActionType getType(){return type;} public boolean isEnabled(){return enabled;} public String getConfigurationJson(){return configurationJson;}
  public void update(String name, boolean enabled, String config){this.name=name;this.enabled=enabled;this.configurationJson=config;this.updatedAt=Instant.now();}
}
