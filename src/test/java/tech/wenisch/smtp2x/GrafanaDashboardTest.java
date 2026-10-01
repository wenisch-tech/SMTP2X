package tech.wenisch.smtp2x;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class GrafanaDashboardTest {
  private static final Pattern APPLICATION_METRIC = Pattern.compile("smtp2x_[a-z0-9_]+");
  private static final Set<String> EXPORTED_METRICS = Set.of(
      "smtp2x_mail_received_total",
      "smtp2x_mail_rejected_total",
      "smtp2x_mail_size_bytes_sum",
      "smtp2x_mail_size_bytes_count",
      "smtp2x_mail_attachments_total",
      "smtp2x_action_triggered_total",
      "smtp2x_action_delivery_attempts_total",
      "smtp2x_action_delivery_duration_seconds_sum",
      "smtp2x_action_delivery_duration_seconds_count",
      "smtp2x_delivery_jobs",
      "smtp2x_cleanup_attempts_total",
      "smtp2x_cleanup_jobs");

  @Test
  void dashboardUsesSelectableDatasourceAndImplementedMetrics() throws Exception {
    JsonNode dashboard = new ObjectMapper().readTree(
        Files.readString(Path.of("docs/smtp2x-grafana-dashboard.json")));
    JsonNode variables = dashboard.path("templating").path("list");
    assertThat(variables.get(0).path("name").asText()).isEqualTo("DS_PROMETHEUS");
    assertThat(variables.get(0).path("type").asText()).isEqualTo("datasource");

    Set<String> referencedMetrics = new HashSet<>();
    for (JsonNode panel : dashboard.path("panels")) {
      assertThat(panel.path("datasource").path("uid").asText())
          .isEqualTo("${DS_PROMETHEUS}");
      for (JsonNode target : panel.path("targets")) {
        var matcher = APPLICATION_METRIC.matcher(target.path("expr").asText());
        while (matcher.find()) referencedMetrics.add(matcher.group());
      }
    }

    assertThat(referencedMetrics).isNotEmpty().isSubsetOf(EXPORTED_METRICS);
    assertThat(referencedMetrics).contains(
        "smtp2x_mail_received_total",
        "smtp2x_action_delivery_attempts_total",
        "smtp2x_delivery_jobs",
        "smtp2x_cleanup_jobs");
  }
}
