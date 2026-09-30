package tech.wenisch.smtp2x.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity @Table(name="routing_rule")
public class RoutingRule {
  @Id private UUID id=UUID.randomUUID(); @Column(nullable=false,unique=true,length=120) private String name;
  @Column(name="global_rule",nullable=false) private boolean globalRule;
  @Column(nullable=false) private boolean enabled=true;
  @Column(name="recipient_pattern",length=320) private String recipientPattern;
  @Column(name="sender_pattern",length=320) private String senderPattern;
  @Column(name="subject_filter",length=1000) private String subjectFilter;
  @Enumerated(EnumType.STRING) @Column(name="subject_mode",length=16) private SubjectMode subjectMode=SubjectMode.CONTAINS;
  @ElementCollection @CollectionTable(name="routing_rule_action", joinColumns=@JoinColumn(name="rule_id")) @Column(name="action_id", nullable=false) private List<UUID> actionIds=new ArrayList<>();
  @Column(name="created_at",nullable=false) private Instant createdAt=Instant.now();
  protected RoutingRule(){} public RoutingRule(String name,boolean globalRule,String recipientPattern,String senderPattern,String subjectFilter,SubjectMode subjectMode,List<UUID> actions){this.name=name;this.globalRule=globalRule;this.recipientPattern=recipientPattern;this.senderPattern=senderPattern;this.subjectFilter=subjectFilter;this.subjectMode=subjectMode;this.actionIds.addAll(actions);}
  public UUID getId(){return id;} public String getName(){return name;} public boolean isGlobalRule(){return globalRule;} public boolean isEnabled(){return enabled;} public String getRecipientPattern(){return recipientPattern;} public String getSenderPattern(){return senderPattern;} public String getSubjectFilter(){return subjectFilter;} public SubjectMode getSubjectMode(){return subjectMode;} public List<UUID> getActionIds(){return List.copyOf(actionIds);}
  public void update(String name,boolean globalRule,boolean enabled,String recipient,String sender,String subject,SubjectMode mode,List<UUID> ids){this.name=name;this.globalRule=globalRule;this.enabled=enabled;this.recipientPattern=recipient;this.senderPattern=sender;this.subjectFilter=subject;this.subjectMode=mode;this.actionIds.clear();this.actionIds.addAll(ids);}
  public enum SubjectMode { CONTAINS, EQUALS }
}
