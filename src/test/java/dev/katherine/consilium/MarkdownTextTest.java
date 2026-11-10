package dev.katherine.consilium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTextTest {
    @Test
    void rendersBoldAndInlineCode() {
        String html = MarkdownText.toHtml("**После выпуска.** Хэш у сканера (`ImageScanner`).");
        assertTrue(html.contains("<strong>После выпуска.</strong>"), html);
        assertTrue(html.contains("<code>ImageScanner</code>"), html);
    }

    @Test
    void rawHtmlOutsideCodeIsShownAsText() {
        String html = MarkdownText.toHtml("Тип List<String> и <img src=http://example.com/a.png>");
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("List&lt;String"), html);
    }

    @Test
    void codeIsLeftForTheConverterToEscape() {
        assertEquals("`List<String>` и &lt;b>", MarkdownText.escapeRawHtml("`List<String>` и <b>"));
        assertEquals("``a ` <b>`` &lt;i>", MarkdownText.escapeRawHtml("``a ` <b>`` <i>"));
        assertEquals("```\nList<String>\n```\n&lt;b>", MarkdownText.escapeRawHtml("```\nList<String>\n```\n<b>"));
    }

    @Test
    void unclosedBacktickDoesNotSwallowTheRest() {
        assertEquals("` &lt;b>", MarkdownText.escapeRawHtml("` <b>"));
    }

    @Test
    void markdownImagesNeverReachTheNetwork() {
        String html = MarkdownText.toHtml("Схема: ![diagram](https://example.com/a.png) и [ref][1]\n\n[1]: https://example.com");
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("diagram"), html);
    }

    @Test
    void tildeAndLongerFencesKeepCodeUnescaped() {
        assertEquals("~~~\nList<String>\n~~~\n&lt;b>", MarkdownText.escapeRawHtml("~~~\nList<String>\n~~~\n<b>"));
        assertEquals("````\n```\nList<String>\n````\n&lt;b>",
                MarkdownText.escapeRawHtml("````\n```\nList<String>\n````\n<b>"));
    }

    @Test
    void autolinksStayLinks() {
        String html = MarkdownText.toHtml("см. <https://example.com/docs>");
        assertTrue(html.contains("href=\"https://example.com/docs\""), html);
    }
}
