package tech.wenisch.smtp2x.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.wenisch.smtp2x.domain.InboundAttachment;

final class AttachmentMarkdown {
  private AttachmentMarkdown() {}

  static String render(String body, List<InboundAttachment> attachments,
      Map<UUID, String> uploadedUrls) {
    String result = body == null ? "" : body;
    List<String> appendix = new ArrayList<>();
    for (InboundAttachment attachment : attachments) {
      String url = uploadedUrls.get(attachment.getId());
      String contentId = normalizeContentId(attachment.getContentId());
      boolean referenced = !contentId.isBlank() && containsCid(result, contentId);
      if (url != null && !url.isBlank()) {
        if (referenced) result = replaceCid(result, contentId, url);
        else appendix.add(link(attachment, url));
      } else {
        String note = "*Attachment “" + attachment.getFilename() + "” was not transferred.*";
        if (referenced) result = replaceCidReference(result, contentId, note);
        else appendix.add(note);
      }
    }
    if (!appendix.isEmpty()) {
      result = result.stripTrailing() + "\n\n### Attachments\n\n" + String.join("\n\n", appendix);
    }
    return result.strip();
  }

  static String normalizeContentId(String value) {
    if (value == null) return "";
    String result = value.trim();
    if (result.startsWith("<") && result.endsWith(">") && result.length() > 1) {
      result = result.substring(1, result.length() - 1);
    }
    return result;
  }

  private static boolean containsCid(String body, String contentId) {
    return body.toLowerCase(Locale.ROOT).contains("cid:" + contentId.toLowerCase(Locale.ROOT));
  }

  private static String replaceCid(String body, String contentId, String url) {
    return Pattern.compile("cid:" + Pattern.quote(contentId), Pattern.CASE_INSENSITIVE)
        .matcher(body).replaceAll(Matcher.quoteReplacement(url));
  }

  private static String replaceCidReference(String body, String contentId, String note) {
    Pattern markdown = Pattern.compile(
        "!?\\[[^\\]]*]\\(\\s*cid:" + Pattern.quote(contentId) + "\\s*\\)",
        Pattern.CASE_INSENSITIVE);
    String result = markdown.matcher(body).replaceAll(Matcher.quoteReplacement(note));
    return Pattern.compile("cid:" + Pattern.quote(contentId), Pattern.CASE_INSENSITIVE)
        .matcher(result).replaceAll("");
  }

  private static String link(InboundAttachment attachment, String url) {
    String label = attachment.getFilename().replace("[", "\\[").replace("]", "\\]");
    String contentType = attachment.getContentType() == null ? "" : attachment.getContentType();
    return contentType.toLowerCase(Locale.ROOT).startsWith("image/")
        ? "![" + label + "](" + url + ")"
        : "[" + label + "](" + url + ")";
  }
}
