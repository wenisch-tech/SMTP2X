package tech.wenisch.smtp2x.service;

import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import tech.wenisch.smtp2x.domain.InboundMessage;

final class MessageBody {
  private static final Set<String> BLOCKS = Set.of(
      "address", "article", "aside", "div", "footer", "header", "main", "nav", "p",
      "section", "table", "tbody", "td", "th", "thead", "tr");

  private MessageBody() {}

  static String markdown(InboundMessage message) {
    return fromParts(message.getTextBody(), message.getHtmlBody());
  }

  static String fromParts(String plainText, String html) {
    if (plainText != null && !plainText.isBlank()) return plainText.strip();
    if (html == null || html.isBlank()) return "";
    StringBuilder output = new StringBuilder();
    for (Node child : Jsoup.parse(html).body().childNodes()) render(child, output);
    return clean(output.toString());
  }

  private static void render(Node node, StringBuilder output) {
    if (node instanceof TextNode text) {
      appendText(output, text.getWholeText());
      return;
    }
    if (!(node instanceof Element element)) return;
    String tag = element.normalName().toLowerCase(Locale.ROOT);
    if (Set.of("head", "script", "style", "template").contains(tag)) return;
    if (tag.equals("br")) {
      newline(output);
      return;
    }
    if (tag.equals("hr")) {
      blankLine(output);
      output.append("---");
      blankLine(output);
      return;
    }
    if (tag.matches("h[1-6]")) {
      blankLine(output);
      output.append("#".repeat(Integer.parseInt(tag.substring(1)))).append(' ');
      renderChildren(element, output);
      blankLine(output);
      return;
    }
    if (tag.equals("img")) {
      String source = safeTarget(element.attr("src"));
      if (!source.isBlank()) {
        String alt = escapeLabel(element.attr("alt").isBlank() ? "image" : element.attr("alt"));
        output.append("![").append(alt).append("](").append(source).append(')');
      }
      return;
    }
    if (tag.equals("a")) {
      StringBuilder label = new StringBuilder();
      renderChildren(element, label);
      String target = safeTarget(element.attr("href"));
      if (target.isBlank() || label.toString().isBlank()) output.append(label);
      else output.append('[').append(label.toString().strip()).append("](").append(target).append(')');
      return;
    }
    if (tag.equals("pre")) {
      blankLine(output);
      output.append("```\n").append(element.wholeText().stripTrailing()).append("\n```");
      blankLine(output);
      return;
    }
    if (tag.equals("code")) {
      output.append('`').append(element.text().replace("`", "\\`")).append('`');
      return;
    }
    if (tag.equals("strong") || tag.equals("b")) {
      output.append("**");
      renderChildren(element, output);
      output.append("**");
      return;
    }
    if (tag.equals("em") || tag.equals("i")) {
      output.append('*');
      renderChildren(element, output);
      output.append('*');
      return;
    }
    if (tag.equals("blockquote")) {
      StringBuilder quote = new StringBuilder();
      renderChildren(element, quote);
      blankLine(output);
      output.append(quote.toString().strip().replaceAll("(?m)^", "> "));
      blankLine(output);
      return;
    }
    if (tag.equals("ul") || tag.equals("ol")) {
      blankLine(output);
      int index = 1;
      for (Element child : element.children()) {
        if (!child.normalName().equals("li")) continue;
        output.append(tag.equals("ol") ? index++ + ". " : "- ");
        renderChildren(child, output);
        newline(output);
      }
      blankLine(output);
      return;
    }
    boolean block = BLOCKS.contains(tag);
    if (block) blankLine(output);
    renderChildren(element, output);
    if (block) blankLine(output);
  }

  private static void renderChildren(Element element, StringBuilder output) {
    for (Node child : element.childNodes()) render(child, output);
  }

  private static void appendText(StringBuilder output, String value) {
    String normalized = value.replaceAll("\\s+", " ");
    if (normalized.isBlank()) {
      if (!output.isEmpty() && !Character.isWhitespace(output.charAt(output.length() - 1))) {
        output.append(' ');
      }
      return;
    }
    if (!output.isEmpty() && !Character.isWhitespace(output.charAt(output.length() - 1))
        && "*_`[()]".indexOf(output.charAt(output.length() - 1)) < 0
        && !normalized.startsWith(" ")) output.append(' ');
    output.append(normalized);
  }

  private static String safeTarget(String value) {
    String target = value == null ? "" : value.trim();
    String lower = target.toLowerCase(Locale.ROOT);
    return lower.startsWith("http://") || lower.startsWith("https://")
        || lower.startsWith("mailto:") || lower.startsWith("cid:") ? target : "";
  }

  private static String escapeLabel(String value) {
    return value.replace("[", "\\[").replace("]", "\\]");
  }

  private static void newline(StringBuilder output) {
    while (!output.isEmpty() && output.charAt(output.length() - 1) == ' ') {
      output.setLength(output.length() - 1);
    }
    if (output.isEmpty() || output.charAt(output.length() - 1) != '\n') output.append('\n');
  }

  private static void blankLine(StringBuilder output) {
    newline(output);
    if (output.length() < 2 || output.charAt(output.length() - 2) != '\n') output.append('\n');
  }

  private static String clean(String value) {
    return value.replaceAll("(?m)[ \\t]+$", "")
        .replaceAll("\\n{3,}", "\n\n")
        .strip();
  }
}
