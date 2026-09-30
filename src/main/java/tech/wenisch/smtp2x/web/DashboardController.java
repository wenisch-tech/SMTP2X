package tech.wenisch.smtp2x.web;

import java.net.URI;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tech.wenisch.smtp2x.domain.*;
import tech.wenisch.smtp2x.repository.*;
import tech.wenisch.smtp2x.service.RuleConfigurationService;

@RestController
public class DashboardController {
  private final InboundMessageRepository messages;
  private final DeliveryJobRepository deliveries;
  private final ActionConfigurationRepository actions;
  private final RuleConfigurationService rules;
  private final ObjectMapper json;
  public DashboardController(InboundMessageRepository m, DeliveryJobRepository d,
      ActionConfigurationRepository a, RuleConfigurationService r, ObjectMapper j) {
    messages=m; deliveries=d; actions=a; rules=r; json=j;
  }
  public record ActionNode(UUID id, String name, ActionType type, boolean enabled, String destination) {}
  public record Counts(long messages, long active, long succeeded, long failed) {}
  public record Failure(UUID id, UUID messageId, UUID actionId, String actionName, int attempts, Instant updatedAt) {}
  public record Dashboard(Instant updatedAt, Counts counts, List<RuleConfigurationService.RuleView> rules,
      List<ActionNode> actions, List<InboundMessageRepository.Summary> recentMessages, List<Failure> recentFailures) {}

  @GetMapping("/api/v1/dashboard")
  @PreAuthorize("hasAnyRole('ADMIN','VIEWER')")
  @Transactional(readOnly=true)
  public Dashboard dashboard() {
    var totals = new EnumMap<DeliveryStatus,Long>(DeliveryStatus.class);
    deliveries.countStatuses().forEach(c -> totals.put(c.getStatus(),c.getTotal()));
    var nodes=actions.findAll().stream().map(a -> new ActionNode(a.getId(),a.getName(),a.getType(),a.isEnabled(),destination(a))).toList();
    var names=new HashMap<UUID,String>(); nodes.forEach(a -> names.put(a.id(),a.name()));
    var failed=deliveries.recentFailures(PageRequest.of(0,5)).stream().map(f -> new Failure(f.getId(),f.getMessageId(),f.getActionId(),names.getOrDefault(f.getActionId(),"Unavailable action"),f.getAttempts(),f.getUpdatedAt())).toList();
    return new Dashboard(Instant.now(), new Counts(messages.count(),totals.getOrDefault(DeliveryStatus.PENDING,0L)+totals.getOrDefault(DeliveryStatus.RUNNING,0L),totals.getOrDefault(DeliveryStatus.SUCCEEDED,0L),totals.getOrDefault(DeliveryStatus.FAILED,0L)),rules.list(),nodes,messages.recent(PageRequest.of(0,5)),failed);
  }
  private String destination(ActionConfiguration a) {
    try {
      var c=json.readTree(a.getConfigurationJson());
      if(a.getType()==ActionType.MATTERMOST_MESSAGE) return "Mattermost incoming webhook";
      String urlField=switch(a.getType()){
        case GITLAB_ISSUE,GITHUB_ISSUE,FORGEJO_ISSUE -> "baseUrl";
        case WEBHOOK -> "url";
        case MATTERMOST_MESSAGE -> throw new IllegalStateException();
      };
      var uri=URI.create(c.path(urlField).asText());
      if(uri.getHost()==null || !("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))) return "Destination configured";
      // Only the host and non-secret project/repository name are exposed.
      String target=switch(a.getType()){
        case GITLAB_ISSUE -> c.path("project").asText();
        case GITHUB_ISSUE,FORGEJO_ISSUE -> c.path("repository").asText();
        default -> "";
      };
      return uri.getHost()+(target.isBlank()?"":" · "+target);
    } catch(Exception e) { return "Destination configured"; }
  }
}
