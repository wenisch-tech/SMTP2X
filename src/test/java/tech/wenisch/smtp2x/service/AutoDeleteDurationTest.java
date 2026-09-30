package tech.wenisch.smtp2x.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AutoDeleteDurationTest {
  @Test
  void parsesMinutesHoursAndDays() {
    assertThat(AutoDeleteDuration.parse("5m")).contains(Duration.ofMinutes(5));
    assertThat(AutoDeleteDuration.parse("10H")).contains(Duration.ofHours(10));
    assertThat(AutoDeleteDuration.parse(" 30d ")).contains(Duration.ofDays(30));
    assertThat(AutoDeleteDuration.parse("")).isEmpty();
  }

  @Test
  void rejectsInvalidOrNonPositiveValues() {
    for (String value : new String[] {"0m", "5", "1w", "-2h", "tomorrow"})
      assertThatThrownBy(() -> AutoDeleteDuration.parse(value))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("5m, 10h, or 30d");
  }
}
