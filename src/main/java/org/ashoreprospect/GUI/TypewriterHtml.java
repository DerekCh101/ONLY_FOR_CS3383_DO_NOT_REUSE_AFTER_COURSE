/** Unwrap JavaFX HTMLEditor documents. Persist inner markup, never the editor shell. */
package org.ashoreprospect.GUI;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TypewriterHtml {

    private static final Pattern UNICODE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static final Pattern BARE_U00 = Pattern.compile("u00([0-9a-fA-F]{2})");
    private static final Pattern MARKUP = Pattern.compile(
            "(?is).*<\\s*(p|div|br|b|i|u|em|strong|span|font|h[1-6]|ul|ol|li|blockquote)\\b.*");
    private static final Pattern DROP_TAGS = Pattern.compile(
            "(?is)</?(?:script|iframe|object|embed|link|meta|base|form|input|textarea|button|svg|math|video|audio|source|applet|style|template)(?:\\s[^>]*)?>");
    private static final Pattern ON_ATTR = Pattern.compile(
            "(?i)\\s+on[a-z]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern JS_URL = Pattern.compile(
            "(?i)\\s(?:href|src|xlink:href)\\s*=\\s*(['\"]?)\\s*javascript:[^'\"\\s>]*");

    private TypewriterHtml() {}

    static boolean looksLikeEditorDump(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String s = raw;
        return s.contains("contenteditable")
                || s.contains("ashore-ln")
                || s.contains("ashore-tw-theme")
                || UNICODE.matcher(s).find() && (s.contains("u003chtml") || s.contains("u003cbody"))
                || BARE_U00.matcher(s).find() && (s.contains("u003chtml") || s.contains("u003cbody"))
                || s.toLowerCase(Locale.ROOT).contains("<html")
                || s.toLowerCase(Locale.ROOT).contains("<body");
    }

    /** Persist this: inner writing (alignment / bold / color kept), never the HTMLEditor shell. */
    static String storedText(String raw) {
        String s = unwrapInner(raw);
        if (s.isBlank()) return "";
        return dropDanger(s).trim();
    }

    /** Word / line counts. Strips tags after unwrap. */
    static String plainText(String raw) {
        return htmlToPlain(unwrapInner(raw)).trim();
    }

    static String htmlForEditor(String stored) {
        String inner = storedText(stored);
        if (inner.isBlank()) return "<p><br></p>";
        if (looksLikeMarkup(inner)) return inner;
        String[] lines = inner.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) sb.append("<p><br></p>");
            else sb.append("<p>").append(escape(line)).append("</p>");
        }
        return sb.toString();
    }

    static boolean looksLikeMarkup(String s) {
        return s != null && s.indexOf('<') >= 0 && MARKUP.matcher(s).matches();
    }

    static String unwrapInner(String raw) {
        String s = decodeEscapes(raw);
        if (s == null || s.isBlank()) return "";
        s = stripEditorChrome(s);
        for (int i = 0; i < 8; i++) {
            String inner = bodyInnerOnce(s);
            if (inner.equals(s)) break;
            s = inner;
        }
        return stripEditorChrome(s).trim();
    }

    static String stripEditorChrome(String html) {
        if (html == null) return "";
        return html
                .replaceAll("(?is)<div[^>]*\\bid\\s*=\\s*(['\"]?)ashore-ln\\1[^>]*>[\\s\\S]*?</div>", "")
                .replaceAll("(?is)<style[^>]*\\bid\\s*=\\s*(['\"]?)ashore-tw-theme\\1[^>]*>[\\s\\S]*?</style>", "");
    }

    static String htmlToPlain(String html) {
        if (html == null || html.isBlank()) return "";
        String s = stripEditorChrome(html)
                .replaceAll("(?i)<script[\\s\\S]*?</script>", "")
                .replaceAll("(?i)<style[\\s\\S]*?</style>", "")
                .replaceAll("(?i)<head[\\s\\S]*?</head>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("(?i)</div>", "\n")
                .replaceAll("(?i)</li>", "\n")
                .replaceAll("(?i)</h[1-6]>", "\n")
                .replaceAll("(?i)</tr>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("&[a-zA-Z]+;", " ");
        return s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").replaceAll("\\n+$", "");
    }

    private static String dropDanger(String html) {
        String s = DROP_TAGS.matcher(html).replaceAll("");
        s = ON_ATTR.matcher(s).replaceAll("");
        s = JS_URL.matcher(s).replaceAll("");
        return s;
    }

    private static String decodeEscapes(String raw) {
        if (raw == null) return "";
        String s = raw;
        Matcher uni = UNICODE.matcher(s);
        StringBuffer out = new StringBuffer();
        while (uni.find()) {
            uni.appendReplacement(out, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(uni.group(1), 16))));
        }
        uni.appendTail(out);
        s = out.toString();
        if (s.contains("u003chtml") || s.contains("u003cbody") || s.contains("u003cp")) {
            Matcher bare = BARE_U00.matcher(s);
            StringBuffer b = new StringBuffer();
            while (bare.find()) {
                bare.appendReplacement(b, Matcher.quoteReplacement(
                        String.valueOf((char) Integer.parseInt(bare.group(1), 16))));
            }
            bare.appendTail(b);
            s = b.toString();
        }
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
    }

    private static String bodyInnerOnce(String html) {
        String s = html == null ? "" : html.trim();
        if (s.isBlank()) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        int bodyAt = lower.indexOf("<body");
        if (bodyAt >= 0) {
            int gt = s.indexOf('>', bodyAt);
            int end = lower.lastIndexOf("</body>");
            if (gt >= 0 && end > gt) return s.substring(gt + 1, end).trim();
        }
        int htmlAt = lower.indexOf("<html");
        if (htmlAt >= 0) {
            int gt = s.indexOf('>', htmlAt);
            int end = lower.lastIndexOf("</html>");
            if (gt >= 0 && end > gt) return s.substring(gt + 1, end).trim();
        }
        return s;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
