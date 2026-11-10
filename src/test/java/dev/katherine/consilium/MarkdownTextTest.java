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
}
