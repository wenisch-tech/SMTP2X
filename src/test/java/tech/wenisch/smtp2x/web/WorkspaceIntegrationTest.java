package tech.wenisch.smtp2x.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tech.wenisch.smtp2x.domain.*;
import tech.wenisch.smtp2x.repository.*;
import tech.wenisch.smtp2x.service.SecretCipher;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:workspace;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "smtp2x.smtp.enabled=false", "smtp2x.data-directory=target/workspace-test-data", "smtp2x.delivery.poll-ms=3600000", "smtp2x.cleanup.poll-ms=3600000"})
class WorkspaceIntegrationTest {
  @Autowired WebApplicationContext context;
  @Autowired ActionConfigurationRepository actions;
  @Autowired RoutingRuleRepository rules;
  @Autowired InboundMessageRepository messages;
  @Autowired DeliveryJobRepository deliveries;
  @Autowired ExternalCleanupJobRepository cleanups;
  @Autowired AppUserRepository users;
  @Autowired ObjectMapper json;
  @Autowired SecretCipher secrets;
  MockMvc mvc;
  final String admin="admin@smtp2x.local", viewer="viewer@example.com";

  @BeforeEach void setup() {
    mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    cleanups.deleteAll();deliveries.deleteAll();messages.deleteAll();rules.deleteAll();actions.deleteAll();
    if(users.findByEmailIgnoreCase(viewer).isEmpty())users.save(new AppUser(viewer,"unused",UserRole.VIEWER,false,false));
  }
  ActionConfiguration action(String name) {return actions.save(new ActionConfiguration(name,ActionType.WEBHOOK,"{\"url\":\"https://user:secret@hooks.example.com/private-token?key=secret#secret\",\"bearerToken\":\"hidden-secret\"}"));}
  String ruleRequest(List<UUID> ids, boolean enabled) throws Exception {
    return json.writeValueAsString(new ApiController.RuleRequest("Infrastructure alerts",false,enabled,"alerts@example.com","*@example.com","production",RoutingRule.SubjectMode.EQUALS,ids));
  }
  @Test void ruleAssociationsSurviveCreateUpdateAndSerialization() throws Exception {
    var a=action("Ops"); var b=action("Chat");
    var result=mvc.perform(post("/api/v1/rules").with(user(admin).roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ruleRequest(List.of(a.getId(),b.getId()),false)))
      .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.actionIds.length()").value(2)).andReturn();
    String id=json.readTree(result.getResponse().getContentAsString()).path("id").asText();
    mvc.perform(get("/api/v1/rules").with(user(viewer).roles("VIEWER")))
      .andExpect(status().isOk()).andExpect(jsonPath("$[0].actionIds.length()").value(2));
    mvc.perform(put("/api/v1/rules/"+id).with(user(admin).roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ruleRequest(List.of(b.getId()),true)))
      .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.subjectMode").value("EQUALS")).andExpect(jsonPath("$.actionIds[0]").value(b.getId().toString()));
  }
  @Test void invalidSelectionsAndNamesHaveActionableErrors() throws Exception {
    for(var ids:List.of(List.<UUID>of(),List.of(UUID.randomUUID())))
      mvc.perform(post("/api/v1/rules").with(user(admin).roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ruleRequest(ids,true))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
    var a=action("Ops");
    mvc.perform(post("/api/v1/rules").with(user(admin).roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ruleRequest(List.of(a.getId()),true).replace("Infrastructure alerts",""))).andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/rules").with(user(admin).roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ruleRequest(List.of(a.getId()),true).replace("alerts@example.com",""))).andExpect(status().isBadRequest());
  }
  @Test void authorizationAndCsrfRemainEnforced() throws Exception {
    mvc.perform(get("/api/v1/dashboard")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/api/v1/dashboard").with(user(viewer).roles("VIEWER"))).andExpect(status().isOk());
    mvc.perform(post("/api/v1/rules").with(user(viewer).roles("VIEWER")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ruleRequest(List.of(UUID.randomUUID()),true))).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/actions").with(user(admin).roles("ADMIN")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    mvc.perform(get("/administration").with(user(viewer).roles("VIEWER"))).andExpect(status().isForbidden());
    mvc.perform(get("/rules").with(user(viewer).roles("VIEWER"))).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"rule-form\""))));
  }
  @Test void prometheusMetricsArePublic() throws Exception {
    mvc.perform(get("/actuator/prometheus"))
      .andExpect(status().isOk())
      .andExpect(content().string(org.hamcrest.Matchers.containsString("smtp2x_mail_received_total")))
      .andExpect(content().string(org.hamcrest.Matchers.containsString("smtp2x_delivery_jobs")));
  }
  @Test void actionCreationHonorsDisabledState() throws Exception {
    mvc.perform(post("/api/v1/actions").with(user(admin).roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Paused\",\"type\":\"WEBHOOK\",\"enabled\":false,\"configuration\":{\"url\":\"https://example.com/hook\"}}"))
      .andExpect(status().isCreated()).andExpect(jsonPath("$.enabled").value(false));
  }
  @Test void mattermostWebhookUrlIsEncryptedAndRedacted() throws Exception {
    var configuration=json.createObjectNode();
    configuration.put("webhookUrl","https://mattermost.example.com/hooks/super-secret-token");
    configuration.put("textTemplate","{{subject}}");
    var request=json.createObjectNode();
    request.put("name","Mattermost on-call");request.put("type","MATTERMOST_MESSAGE");request.put("enabled",true);
    request.set("configuration",configuration);
    mvc.perform(post("/api/v1/actions").with(user(admin).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.configuration.webhookUrl").value(""))
      .andExpect(jsonPath("$.configuration.webhookUrlConfigured").value(true));
    assertThat(actions.findAll().getFirst().getConfigurationJson())
      .contains("enc:").doesNotContain("super-secret-token");
    var dashboard=mvc.perform(get("/api/v1/dashboard").with(user(viewer).roles("VIEWER")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.actions[0].destination").value("Mattermost incoming webhook"))
      .andReturn().getResponse().getContentAsString();
    assertThat(dashboard).doesNotContain("super-secret-token","webhookUrl","enc:");
  }
  @Test void actionUpdatesPreserveRedactedSecretsUntilTheyAreReplaced() throws Exception {
    var configuration=json.createObjectNode();
    configuration.put("baseUrl","https://api.github.com");
    configuration.put("repository","acme/alerts");
    configuration.put("accessToken","original-secret");
    configuration.put("titleTemplate","{{subject}}");
    configuration.put("bodyTemplate","{{body}}");
    var request=json.createObjectNode();
    request.put("name","GitHub alerts");request.put("type","GITHUB_ISSUE");request.put("enabled",true);
    request.set("configuration",configuration);
    var created=mvc.perform(post("/api/v1/actions").with(user(admin).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
      .andExpect(status().isCreated()).andReturn();
    UUID id=UUID.fromString(json.readTree(created.getResponse().getContentAsString()).path("id").asText());
    String originalCiphertext=json.readTree(actions.findById(id).orElseThrow()
      .getConfigurationJson()).path("accessToken").asText();

    configuration.put("repository","acme/updated-alerts");
    configuration.put("accessToken","");
    configuration.put("accessTokenConfigured",true);
    request.put("name","Updated GitHub alerts");request.put("enabled",false);
    String response=mvc.perform(put("/api/v1/actions/"+id).with(user(admin).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.name").value("Updated GitHub alerts"))
      .andExpect(jsonPath("$.enabled").value(false))
      .andExpect(jsonPath("$.configuration.repository").value("acme/updated-alerts"))
      .andExpect(jsonPath("$.configuration.accessToken").value(""))
      .andExpect(jsonPath("$.configuration.accessTokenConfigured").value(true))
      .andReturn().getResponse().getContentAsString();
    var preserved=json.readTree(actions.findById(id).orElseThrow().getConfigurationJson());
    assertThat(preserved.path("accessToken").asText()).isEqualTo(originalCiphertext);
    assertThat(preserved.has("accessTokenConfigured")).isFalse();
    assertThat(response).doesNotContain("original-secret",originalCiphertext,"enc:");

    configuration.put("accessToken","replacement-secret");
    configuration.remove("accessTokenConfigured");
    mvc.perform(put("/api/v1/actions/"+id).with(user(admin).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.configuration.accessToken").value(""));
    String replacement=json.readTree(actions.findById(id).orElseThrow()
      .getConfigurationJson()).path("accessToken").asText();
    assertThat(replacement).isNotEqualTo(originalCiphertext);
    assertThat(secrets.decrypt(replacement)).isEqualTo("replacement-secret");
  }
  @Test void issueActionsValidateRepositoryAndRequiredCredentials() throws Exception {
    var configuration=json.createObjectNode();
    configuration.put("baseUrl","https://api.github.com");
    configuration.put("repository","missing-owner-separator");
    configuration.put("accessToken","secret");
    var request=json.createObjectNode();
    request.put("name","GitHub");request.put("type","GITHUB_ISSUE");request.put("enabled",true);
    request.set("configuration",configuration);
    mvc.perform(post("/api/v1/actions").with(user(admin).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error").value("GitHub repository must use owner/repository"));

    configuration.put("baseUrl","https://gitlab.example.com");
    configuration.put("project","acme/alerts");
    configuration.put("autoDeleteAfter","next week");
    request.put("name","GitLab");request.put("type","GITLAB_ISSUE");
    mvc.perform(post("/api/v1/actions").with(user(admin).roles("ADMIN")).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("5m, 10h, or 30d")));
  }
  @Test void dashboardAggregatesAreBoundedAndExcludeSensitiveData() throws Exception {
    var a=action("Ops");
    rules.save(new RoutingRule("Alerts",true,null,null,null,RoutingRule.SubjectMode.CONTAINS,List.of(a.getId())));
    for(int i=0;i<7;i++) {
      var m=messages.save(new InboundMessage("robot@example.com","[]","Alert "+i,"private body","private html","private/path"));
      var job=new DeliveryJob(m.getId(),a.getId(),"private configuration snapshot");
      if(i<6)job.fail("secret diagnostic",false,java.time.Instant.now());else job.success("https://example.com","secret result","");
      deliveries.save(job);
    }
    var queuedMessage=messages.save(new InboundMessage("queue@example.com","[]","Queued",null,null,"fixture"));
    var queued=new DeliveryJob(queuedMessage.getId(),a.getId(),"private snapshot");
    queued.fail("retry",true,java.time.Instant.parse("2100-01-01T00:00:00Z"));deliveries.save(queued);
    var runningMessage=messages.save(new InboundMessage("queue@example.com","[]","Running",null,null,"fixture"));
    var running=new DeliveryJob(runningMessage.getId(),a.getId(),"private snapshot");running.claim();deliveries.save(running);
    var result=mvc.perform(get("/api/v1/dashboard").with(user(viewer).roles("VIEWER")))
      .andExpect(status().isOk()).andExpect(jsonPath("$.counts.messages").value(9)).andExpect(jsonPath("$.counts.failed").value(6)).andExpect(jsonPath("$.counts.succeeded").value(1)).andExpect(jsonPath("$.counts.active").value(2))
      .andExpect(jsonPath("$.recentMessages.length()").value(5)).andExpect(jsonPath("$.recentFailures.length()").value(5))
      .andExpect(jsonPath("$.actions[0].destination").value("hooks.example.com")).andExpect(jsonPath("$.rules[0].actionIds[0]").value(a.getId().toString())).andReturn();
    assertThat(result.getResponse().getContentAsString()).doesNotContain("secret","private","configurationSnapshot","textBody","htmlBody","bearerToken");
  }
  @Test void cleanupApiExposesStatusWithoutConfigurationSnapshot() throws Exception {
    cleanups.save(new ExternalCleanupJob(UUID.randomUUID(),ActionType.GITLAB_ISSUE,
        "{\"accessToken\":\"enc:cleanup-secret\"}","42",java.time.Instant.now().plusSeconds(300)));
    String result=mvc.perform(get("/api/v1/cleanups").with(user(viewer).roles("VIEWER")))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].actionType").value("GITLAB_ISSUE"))
      .andExpect(jsonPath("$[0].status").value("PENDING"))
      .andExpect(jsonPath("$[0].dueAt").isNotEmpty())
      .andReturn().getResponse().getContentAsString();
    assertThat(result).doesNotContain("cleanup-secret","configurationSnapshot","resourceReference");
  }
}
