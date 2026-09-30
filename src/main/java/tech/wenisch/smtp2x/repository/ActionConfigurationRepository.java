package tech.wenisch.smtp2x.repository;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import tech.wenisch.smtp2x.domain.ActionConfiguration;
public interface ActionConfigurationRepository extends JpaRepository<ActionConfiguration,UUID>{}
