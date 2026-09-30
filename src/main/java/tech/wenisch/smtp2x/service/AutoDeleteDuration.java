package tech.wenisch.smtp2x.service;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AutoDeleteDuration {
  private static final Pattern VALUE = Pattern.compile("^([1-9][0-9]*)([mhd])$");

  private AutoDeleteDuration() {
  }

  public static Optional<Duration> parse(String value) {
    if (value == null || value.isBlank()) return Optional.empty();
    Matcher match = VALUE.matcher(value.trim().toLowerCase(Locale.ROOT));
    if (!match.matches())
      throw new IllegalArgumentException(
          "Auto-delete duration must use a positive number followed by m, h, or d, such as 5m, 10h, or 30d");
    try {
      long amount = Long.parseLong(match.group(1));
      return Optional.of(switch (match.group(2)) {
        case "m" -> Duration.ofMinutes(amount);
        case "h" -> Duration.ofHours(amount);
        default -> Duration.ofDays(amount);
      });
    } catch (ArithmeticException | NumberFormatException e) {
      throw new IllegalArgumentException("Auto-delete duration is too large", e);
    }
  }
}
