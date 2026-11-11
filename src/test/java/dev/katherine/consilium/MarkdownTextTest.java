package dev.katherine.consilium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTextTest {
    private static final Pattern ATTRIBUTE = Pattern.compile("<([a-z0-9]+)((?:\\s+[a-z-]+=\"[^\"]*\")*)\\s*/?>");
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("\\s+([a-z-]+)=\"([^\"]*)\"");

    /**
     * The security invariant: the only attribute that can carry a URL is {@code href} on
     * {@code a}, with an allowed scheme. Every other tag is attribute-free, except the two
     * harmless layout attributes the sanitizer validates.
     */
    private static void assertSafe(String html) {
        String lower = html.toLowerCase();
        for (String tag : new String[]{"<img", "<link", "<base", "<iframe", "<frame", "<object", "<embed",
                "<script", "<style", "<input", "<form", "<meta", "<svg", "<video", "<audio"}) {
            assertFalse(lower.contains(tag), () -> "contains " + tag + ": " + html);
        }
        Matcher tag = ATTRIBUTE.matcher(html);
        while (tag.find()) {
            Matcher attribute = ATTRIBUTE_NAME.matcher(tag.group(2));
            while (attribute.find()) {
                String name = tag.group(1) + "@" + attribute.group(1);
                switch (name) {
                    case "a@href" -> assertTrue(MarkdownText.isOpenableLink(attribute.group(2)), html);
                    case "ol@start", "th@align", "td@align" -> {
                    }
                    default -> throw new AssertionError("unexpected attribute " + name + ": " + html);
                }
            }
        }
    }

    @Test
    void rendersBoldAndInlineCode() {
        String html = MarkdownText.toHtml("**После выпуска.** Хэш у сканера (`ImageScanner`).");
        assertTrue(html.contains("<strong>После выпуска.</strong>"), html);
        assertTrue(html.contains("<code>ImageScanner</code>"), html);
    }

    @Test
    void genericsStayVisibleInProseAndCode() {
        String html = MarkdownText.toHtml("Тип List<String> и `Map<K, V>`\n\n```java\nList<String> xs;\n```");
        assertTrue(html.contains("List&lt;String&gt; и"), html);
        assertTrue(html.contains("<code>Map&lt;K, V&gt;</code>"), html);
        assertTrue(html.contains("List&lt;String&gt; xs;"), html);
        assertSafe(html);
    }

    @Test
    void strikethroughAndTablesSurviveSanitizing() {
        String html = MarkdownText.toHtml("~~старое~~ новое\n\n| a | b |\n|:-|-:|\n| 1 | 2 |");
        assertTrue(html.contains("<del>старое</del>"), html);
        assertTrue(html.contains("<td align=\"right\">2</td>"), html);
        assertSafe(html);
    }

    @Test
    void indentedCodeIsShownVerbatim() {
        String html = MarkdownText.toHtml("Пример:\n\n    if (a < b && c > d) {}\n");
        assertTrue(html.contains("<pre><code>if (a &lt; b &amp;&amp; c &gt; d) {}"), html);
        assertFalse(html.contains("&amp;lt;"), html);
    }

    @Test
    void autolinksStayLinks() {
        String html = MarkdownText.toHtml("см. <https://example.com/docs> и [доку](https://example.com/a)");
        assertTrue(html.contains("href=\"https://example.com/docs\""), html);
        assertTrue(html.contains("<a href=\"https://example.com/a\">доку</a>"), html);
        assertSafe(html);
    }

    // Reference links resolve only if the generator is handed the document's link map.
    @Test
    void referenceLinksResolveThroughTheLinkMap() {
        String html = MarkdownText.toHtml("см. [доку][docs]\n\n[docs]: https://example.com/ref");
        assertTrue(html.contains("<a href=\"https://example.com/ref\">доку</a>"), html);
        assertFalse(html.contains("[docs]"), html);
        assertSafe(html);
    }

    @Test
    void orderedListsKeepTheirStartAndTaskListsTheirText() {
        String html = MarkdownText.toHtml("3. три\n4. четыре\n\n- [ ] сделать\n- [x] сделано");
        assertTrue(html.contains("<ol start=\"3\">"), html);
        assertTrue(html.contains("сделать"), html);
        assertTrue(html.contains("сделано"), html);
        assertFalse(html.contains("[x]"), html);
        assertSafe(html);
    }

    @Test
    void markdownImagesBecomeAltText() {
        String html = MarkdownText.toHtml("Схема: ![diagram](https://example.com/a.png) и ![ref][1]\n\n[1]: https://example.com/b.png");
        assertTrue(html.contains("diagram"), html);
        assertTrue(html.contains("ref"), html);
        assertFalse(html.contains("example.com"), html);
        assertSafe(html);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<img src=\"http://evil.example/x.png\">",
            "<link rel=stylesheet href=\"http://evil.example/s.css\">",
            "<p style=\"background: url(http://evil.example/x)\">hi</p>",
            "<table background=\"http://evil.example/x\"><tr><td>hi</td></tr></table>",
            "<base href=\"http://evil.example/\">",
            "<iframe src=\"http://evil.example/\"></iframe>",
            "<frame src=\"http://evil.example/\">",
            "<object data=\"http://evil.example/\"></object>",
            "<input type=image src=\"http://evil.example/x\">",
            "<script>alert(1)</script>",
            "text <b style=\"background:url(http://evil.example/x)\">inline</b> raw",
            "<a href=\"javascript:alert(1)\">x</a>",
            "<a href=\"file:///etc/passwd\">x</a>",
    })
    void rawHtmlIsShownAsTextAndNeverFetches(String hostile) {
        String html = MarkdownText.toHtml("Ответ:\n\n" + hostile + "\n\nдальше");
        assertSafe(html);
        assertTrue(html.contains("&lt;"), html);
        assertTrue(html.contains("дальше"), html);
    }

    @Test
    void indentedFenceDoesNotOpenARawHtmlWindow() {
        String html = MarkdownText.toHtml("Текст\n\n     ```\n<img src=\"http://evil.example/x.png\">\n```\n");
        assertSafe(html);
    }

    @Test
    void backtickInInfoStringDoesNotOpenARawHtmlWindow() {
        String html = MarkdownText.toHtml("```a`b\n<img src=\"http://evil.example/x.png\">\n<link rel=stylesheet href=\"http://evil.example/s.css\">\n```\n");
        assertSafe(html);
    }

    @Test
    void markdownLinksWithOtherSchemesAreInert() {
        String html = MarkdownText.toHtml("[a](javascript:alert(1)) [b](file:///etc/passwd) [c](idea://open?x) [d](mailto:me@example.com)");
        assertSafe(html);
        assertFalse(html.contains("javascript"), html);
        assertFalse(html.contains("file:"), html);
        assertFalse(html.contains("idea:"), html);
        assertTrue(html.contains("href=\"mailto:me@example.com\""), html);
        assertTrue(html.contains("a") && html.contains("b") && html.contains("c"), html);
    }

    @Test
    void sanitizerStripsUrlCarryingAttributesFromAnyHtml() {
        String html = MarkdownText.sanitize("<p style=\"x:url(http://e/)\" background=\"http://e/\">t</p>"
                + "<img src=\"http://e/\" alt=\"a\"><a href=\"https://ok.example\" title=\"t\" style=\"u\">ok</a>"
                + "<a href=\"file:///x\">no</a><ol start=\"3\"><li>x</li></ol><ol start=\"x\"><li>y</li></ol>");
        assertSafe(html);
        assertTrue(html.contains("<p>t</p>"), html);
        assertTrue(html.contains("<a href=\"https://ok.example\">ok</a>"), html);
        assertTrue(html.contains("no") && !html.contains("file:"), html);
        assertTrue(html.contains("<ol start=\"3\">"), html);
        assertTrue(html.contains("<ol><li>y</li></ol>"), html);
    }

    @Test
    void openableLinksAreWebAndMailOnly() {
        assertTrue(MarkdownText.isOpenableLink("https://example.com"));
        assertTrue(MarkdownText.isOpenableLink("HTTP://example.com"));
        assertTrue(MarkdownText.isOpenableLink("mailto:me@example.com"));
        assertFalse(MarkdownText.isOpenableLink("javascript:alert(1)"));
        assertFalse(MarkdownText.isOpenableLink("file:///etc/passwd"));
        assertFalse(MarkdownText.isOpenableLink("/relative"));
        assertFalse(MarkdownText.isOpenableLink(null));
    }

    @Test
    void largeAnswerRendersQuickly() {
        StringBuilder markdown = new StringBuilder();
        int section = 0;
        while (markdown.length() < 200_000) {
            section++;
            markdown.append("## Раздел ").append(section).append("\n\n")
                    .append("Текст с **жирным**, `List<String>` и [ссылкой](https://example.com/").append(section).append(").\n\n")
                    .append("- пункт <b>один</b>\n- пункт два\n\n")
                    .append("```java\nMap<String, List<Integer>> m = new HashMap<>();\n```\n\n")
                    .append("    indented < code\n\n");
        }
        String html = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> MarkdownText.toHtml(markdown.toString()));
        assertTrue(html.contains("Раздел " + section), "last section rendered");
        assertSafe(html);
    }
}
