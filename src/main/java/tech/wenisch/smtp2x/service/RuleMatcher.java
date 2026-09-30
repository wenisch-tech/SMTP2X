package tech.wenisch.smtp2x.service;
import java.util.*; import java.util.regex.Pattern; import org.springframework.stereotype.Service; import tech.wenisch.smtp2x.domain.RoutingRule;
@Service public class RuleMatcher {
 public boolean matches(RoutingRule rule,String sender,List<String> recipients,String subject){if(!rule.isEnabled())return false; if(!blank(rule.getSenderPattern())&&!addressMatches(rule.getSenderPattern(),sender))return false; if(!blank(rule.getSubjectFilter())){boolean hit=rule.getSubjectMode()==RoutingRule.SubjectMode.EQUALS?rule.getSubjectFilter().equals(subject):subject!=null&&subject.toLowerCase(Locale.ROOT).contains(rule.getSubjectFilter().toLowerCase(Locale.ROOT));if(!hit)return false;} return rule.isGlobalRule()||recipients.stream().anyMatch(r->addressMatches(rule.getRecipientPattern(),r));}
 public boolean addressMatches(String pattern,String address){if(blank(pattern))return false;String regex="^"+Pattern.quote(pattern.trim().toLowerCase(Locale.ROOT)).replace("*", "\\E.*\\Q")+"$";return address!=null&&address.toLowerCase(Locale.ROOT).matches(regex);}
 private boolean blank(String s){return s==null||s.isBlank();}
}
