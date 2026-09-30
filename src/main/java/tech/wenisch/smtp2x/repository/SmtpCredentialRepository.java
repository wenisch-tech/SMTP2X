package tech.wenisch.smtp2x.repository;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import tech.wenisch.smtp2x.domain.SmtpCredential;
public interface SmtpCredentialRepository extends JpaRepository<SmtpCredential,UUID>{Optional<SmtpCredential> findByUsername(String username);}
