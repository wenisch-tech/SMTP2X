package tech.wenisch.smtp2x.service;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.smtp2x.domain.RoutingRule;
import tech.wenisch.smtp2x.repository.*;
import tech.wenisch.smtp2x.web.ApiController.RuleRequest;

@Service
public class RuleConfigurationService {
  private final RoutingRuleRepository rules;
  private final ActionConfigurationRepository actions;
  public RuleConfigurationService(RoutingRuleRepository rules, ActionConfigurationRepository actions) {
    this.rules = rules; this.actions = actions;
  }
  public record RuleView(UUID id, String name, boolean globalRule, boolean enabled,
      String recipientPattern, String senderPattern, String subjectFilter,
      RoutingRule.SubjectMode subjectMode, List<UUID> actionIds) {
    public static RuleView of(RoutingRule r) {
      return new RuleView(r.getId(), r.getName(), r.isGlobalRule(), r.isEnabled(),
          r.getRecipientPattern(), r.getSenderPattern(), r.getSubjectFilter(), r.getSubjectMode(), r.getActionIds());
    }
  }
  @Transactional(readOnly = true)
  public List<RuleView> list() { return rules.findAll().stream().map(RuleView::of).toList(); }

  @Transactional
  public RuleView save(UUID id, RuleRequest r) {
    if (r.name() == null || r.name().isBlank() || r.name().trim().length() > 120)
      throw new IllegalArgumentException("Enter a rule name of at most 120 characters");
    if (!r.globalRule() && (r.recipientPattern() == null || r.recipientPattern().isBlank()))
      throw new IllegalArgumentException("Enter a recipient pattern or choose a global rule");
    if (r.actionIds() == null || r.actionIds().isEmpty() || r.actionIds().stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Select at least one action");
    var ids = new ArrayList<>(new LinkedHashSet<>(r.actionIds()));
    if (actions.findAllById(ids).size() != ids.size())
      throw new IllegalArgumentException("A selected action no longer exists. Refresh the actions and try again");
    var mode = r.subjectMode() == null ? RoutingRule.SubjectMode.CONTAINS : r.subjectMode();
    RoutingRule rule = id == null
        ? new RoutingRule(r.name().trim(), r.globalRule(), r.recipientPattern(), r.senderPattern(), r.subjectFilter(), mode, ids)
        : rules.findById(id).orElseThrow();
    rule.update(r.name().trim(), r.globalRule(), r.enabled(), r.recipientPattern(), r.senderPattern(), r.subjectFilter(), mode, ids);
    return RuleView.of(rules.saveAndFlush(rule));
  }
}
