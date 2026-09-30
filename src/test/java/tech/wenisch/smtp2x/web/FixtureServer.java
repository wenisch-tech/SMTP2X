package tech.wenisch.smtp2x.web;

import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tech.wenisch.smtp2x.Smtp2xApplication;
import tech.wenisch.smtp2x.domain.*;
import tech.wenisch.smtp2x.repository.*;

/** Isolated browser-test fixture. Never packaged in the application JAR. */
public class FixtureServer {
  public static void main(String[] args) throws Exception {
    var temp=Files.createTempDirectory("smtp2x-ui-");
    var app=new SpringApplication(Smtp2xApplication.class);
    var context=app.run("--server.address=127.0.0.1", "--server.port=18080",
        "--spring.datasource.url=jdbc:h2:mem:ui;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "--smtp2x.smtp.enabled=false", "--smtp2x.data-directory="+temp,
        "--smtp2x.security.initial-admin-email=admin@smtp2x.local",
        "--smtp2x.security.initial-admin-password=fixture-password", "--smtp2x.delivery.poll-ms=86400000");
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx->{
      var actions=context.getBean(ActionConfigurationRepository.class);
      var rules=context.getBean(RoutingRuleRepository.class);
      var messages=context.getBean(InboundMessageRepository.class);
      var deliveries=context.getBean(DeliveryJobRepository.class);
      var users=context.getBean(AppUserRepository.class);
      var encoder=context.getBean(PasswordEncoder.class);
      // Set explicit test credentials regardless of deployment environment overrides.
      var admin=users.findByEmailIgnoreCase("admin@smtp2x.local").orElseThrow();admin.changePassword(encoder.encode("fixture-password"));users.save(admin);
      users.save(new AppUser("viewer@example.com",encoder.encode("fixture-password"),UserRole.VIEWER,false,false));
      var gitlab=actions.save(new ActionConfiguration("Platform issue tracker",ActionType.GITLAB_ISSUE,"{\"baseUrl\":\"https://gitlab.example.com\",\"project\":\"platform/operations\"}"));
      var webhook=actions.save(new ActionConfiguration("Team notifications",ActionType.WEBHOOK,"{\"url\":\"https://hooks.example.com/team\"}"));
      var support=actions.save(new ActionConfiguration("Customer support",ActionType.GITLAB_ISSUE,"{\"baseUrl\":\"https://gitlab.example.com\",\"project\":\"support/inbox\"}"));
      var github=actions.save(new ActionConfiguration("GitHub engineering backlog",ActionType.GITHUB_ISSUE,"{\"baseUrl\":\"https://api.github.com\",\"repository\":\"acme/engineering\",\"accessToken\":\"fixture-only\"}"));
      var forgejo=actions.save(new ActionConfiguration("Forgejo operations",ActionType.FORGEJO_ISSUE,"{\"baseUrl\":\"https://code.example.com\",\"repository\":\"operations/incidents\",\"accessToken\":\"fixture-only\"}"));
      var mattermost=actions.save(new ActionConfiguration("Mattermost on-call",ActionType.MATTERMOST_MESSAGE,"{\"webhookUrl\":\"fixture-only\",\"textTemplate\":\"{{subject}}\"}"));
      var archive=new ActionConfiguration("Legacy incident feed",ActionType.WEBHOOK,"{\"url\":\"https://legacy.example.com/events\"}");archive.update(archive.getName(),false,archive.getConfigurationJson());archive=actions.save(archive);
      rules.save(new RoutingRule("Infrastructure alerts",false,"alerts@example.com","*@monitoring.example.com","production",RoutingRule.SubjectMode.CONTAINS,List.of(gitlab.getId(),webhook.getId())));
      rules.save(new RoutingRule("Customer requests",false,"support@example.com",null,null,RoutingRule.SubjectMode.CONTAINS,List.of(support.getId(),webhook.getId())));
      rules.save(new RoutingRule("Engineering incidents",false,"engineering@example.com",null,"incident",RoutingRule.SubjectMode.CONTAINS,List.of(github.getId(),forgejo.getId(),mattermost.getId())));
      var paused=new RoutingRule("Legacy monitoring",false,"legacy@example.com",null,null,RoutingRule.SubjectMode.CONTAINS,List.of(archive.getId()));paused.update(paused.getName(),false,false,paused.getRecipientPattern(),null,null,RoutingRule.SubjectMode.CONTAINS,paused.getActionIds());rules.save(paused);
      var now=Instant.parse("2026-09-30T08:42:00Z");
      String[] subjects={"Production API · latency above threshold","New support request · workspace access","Production database · backup completed","New support request · delivery setup","Production worker · queue recovered"};
      for(int i=0;i<36;i++) {
        var m=new InboundMessage(i%2==0?"alerts@monitoring.example.com":"helpdesk@example.com","[\"alerts@example.com\"]",subjects[i%subjects.length],"Fictional notification for documentation.",null,"fixture-only");
        ReflectionTestUtils.setField(m,"receivedAt",now.minusSeconds(i*180L));messages.save(m);
        var a=i%2==0?gitlab:support;
        var job=new DeliveryJob(m.getId(),a.getId(),"{}");
        if(i==0||i==4)job.fail("Fictional destination unavailable",false,now);
        else if(i==1||i==2)job.fail("Scheduled for demonstration",true,Instant.parse("2100-01-01T00:00:00Z"));
        else if(i==3)job.claim();
        else job.success(null,"Delivered successfully","");
        ReflectionTestUtils.setField(job,"updatedAt",now.minusSeconds(i*180L));deliveries.save(job);
      }
    });
    Files.writeString(temp.resolve("ready"),"ready");
    System.out.println("SMTP2X_UI_FIXTURES_READY");
    Runtime.getRuntime().addShutdownHook(new Thread(()->{
      try(var paths=Files.walk(temp)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}catch(Exception ignored){}
    }));
  }
}
