package tech.wenisch.smtp2x.repository;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import tech.wenisch.smtp2x.domain.RoutingRule;
public interface RoutingRuleRepository extends JpaRepository<RoutingRule,UUID>{
 @Override @org.springframework.data.jpa.repository.EntityGraph(attributePaths="actionIds")
 List<RoutingRule> findAll();
}
