package tech.wenisch.smtp2x.repository;
import java.util.*; import org.springframework.data.jpa.repository.JpaRepository; import tech.wenisch.smtp2x.domain.AppUser;
public interface AppUserRepository extends JpaRepository<AppUser,UUID>{Optional<AppUser> findByEmailIgnoreCase(String email);}
