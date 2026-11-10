package dev.katherine.consilium;

import com.intellij.markdown.utils.MarkdownToHtmlConverter;
import org.intellij.markdown.IElementType;
import org.intellij.markdown.MarkdownElementTypes;
import org.intellij.markdown.MarkdownTokenTypes;
import org.intellij.markdown.ast.ASTNode;
import org.intellij.markdown.ast.ASTUtilKt;
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor;
import org.intellij.markdown.html.GeneratingProvider;
import org.intellij.markdown.html.HtmlGenerator;
import org.intellij.markdown.parser.LinkMap;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Agent answers arrive as Markdown; they are shown rendered, as in a chat, rather than with
 * asterisks and backticks.
 *
 * <p>Rendering happens in two layers:
 * <ol>
 *   <li><b>Fidelity.</b> The platform's GFM parser decides what is code, so indented blocks,
 *   indented fences and fences with odd info strings follow CommonMark exactly. Raw HTML that the
 *   parser recognizes ({@code List<String>} in prose, {@code <img src=…>}) is emitted as
 *   escaped text instead of being passed through, and Markdown images become their alt text.</li>
 *   <li><b>Security.</b> The final HTML goes through jsoup's {@link Cleaner} with a strict
 *   allowlist: only tags Markdown itself produces, and no attribute that can carry a URL except
 *   {@code href} on {@code a}, limited to http, https and mailto. Swing's HTML kit fetches
 *   images, {@code <link>} stylesheets, {@code background} and {@code style url(…)} on render,
 *   so this layer is what guarantees a hostile answer can't make the IDE load anything.</li>
 * </ol>
 * The first layer is not trusted for safety; the second is not relied on for fidelity.
 * jsoup ships with the IntelliJ Platform ({@code intellij.libraries.jsoup}), so no HTML parser of
 * our own is needed. The platform's {@code DocMarkdownToHtmlConverter} was checked and doesn't
 * fit: it needs a running IDE and lets {@code <img>} through.
 */
final class MarkdownText {
    private MarkdownText() {
    }

    /** Link schemes that open on click; anything else renders as plain text. */
    static final Set<String> LINK_PROTOCOLS = Set.of("http", "https", "mailto");

    private static final MarkdownToHtmlConverter CONVERTER = new MarkdownToHtmlConverter(new TextOnlyRawHtmlFlavour());

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "strong", "b", "em", "i", "code", "pre", "ul", "ol", "li", "blockquote",
                    "h1", "h2", "h3", "h4", "h5", "h6", "a", "hr",
                    "table", "thead", "tbody", "tr", "th", "td", "del", "s")
            .addAttributes("a", "href")
            .addProtocols("a", "href", LINK_PROTOCOLS.toArray(String[]::new))
            // Neither can hold a URL; both are validated below so Swing never sees junk.
            .addAttributes("ol", "start")
            .addAttributes("th", "align")
            .addAttributes("td", "align");

    static String toHtml(String markdown) {
        return sanitize(CONVERTER.convertMarkdownToHtml(markdown == null ? "" : markdown, null));
    }

    /**
     * Keeps only the allowlisted tags and attributes. Disallowed elements are unwrapped (their
     * text stays visible), {@code script}/{@code style} contents are dropped, links with other
     * schemes lose their {@code href} and are unwrapped into plain text. Linear in the input:
     * one jsoup parse plus one tree walk.
     */
    static String sanitize(String html) {
        Document dirty = Jsoup.parseBodyFragment(html);
        // GFM renders ~~text~~ as <span class="user-del">; Swing knows <del>, and the allowlist
        // has no reason to admit span or class.
        dirty.body().select("span.user-del").tagName("del");
        Document clean = new Cleaner(SAFELIST).clean(dirty);
        for (Element element : clean.body().getAllElements()) {
            switch (element.normalName()) {
                case "a" -> {
                    if (!element.hasAttr("href")) element.unwrap();
                }
                case "ol" -> {
                    if (element.hasAttr("start") && !element.attr("start").matches("\\d{1,9}")) {
                        element.removeAttr("start");
                    }
                }
                case "th", "td" -> {
                    if (element.hasAttr("align") && !element.attr("align").matches("left|center|right")) {
                        element.removeAttr("align");
                    }
                }
                default -> {
                }
            }
        }
        // Swing's HTML reader is HTML 3.2-era: base entities only (&nbsp; rather than &#xa0;),
        // and no pretty printing, which would add whitespace inside <pre>.
        clean.outputSettings().prettyPrint(false).escapeMode(Entities.EscapeMode.base);
        return clean.body().html();
    }

    /** True for a link the panel may hand to the browser. */
    static boolean isOpenableLink(String url) {
        if (url == null) return false;
        int colon = url.indexOf(':');
        return colon > 0 && LINK_PROTOCOLS.contains(url.substring(0, colon).toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * GFM as the platform renders it, except that raw HTML is shown as text and images as their
     * alt text. Swapping the generating providers keeps the decision "is this code?" with the
     * parser instead of guessing it from the Markdown source with regular expressions.
     */
    private static final class TextOnlyRawHtmlFlavour extends GFMFlavourDescriptor {
        TextOnlyRawHtmlFlavour() {
            super(true, false, false);
        }

        @Override
        public Map<IElementType, GeneratingProvider> createHtmlGeneratingProviders(LinkMap linkMap, URI baseUri) {
            Map<IElementType, GeneratingProvider> providers = new HashMap<>(super.createHtmlGeneratingProviders(linkMap, baseUri));
            providers.put(MarkdownTokenTypes.HTML_TAG, (visitor, text, node) ->
                    visitor.consumeHtml(Entities.escape(ASTUtilKt.getTextInNode(node, text).toString())));
            providers.put(MarkdownElementTypes.HTML_BLOCK, MarkdownText::renderHtmlBlockAsText);
            providers.put(MarkdownElementTypes.IMAGE, (visitor, text, node) ->
                    visitor.consumeHtml(Entities.escape(altText(node, text))));
            return providers;
        }
    }

    // An HTML block is a run of lines; showing them as one paragraph with line breaks keeps the
    // shape the agent typed without interpreting any of it.
    private static void renderHtmlBlockAsText(HtmlGenerator.HtmlGeneratingVisitor visitor, String text, ASTNode node) {
        visitor.consumeHtml("<p>");
        for (ASTNode child : node.getChildren()) {
            if (child.getType() == MarkdownTokenTypes.HTML_BLOCK_CONTENT) {
                visitor.consumeHtml(Entities.escape(ASTUtilKt.getTextInNode(child, text).toString()));
            } else if (child.getType() == MarkdownTokenTypes.EOL) {
                visitor.consumeHtml("<br>");
            }
        }
        visitor.consumeHtml("</p>\n");
    }

    private static String altText(ASTNode image, String text) {
        ASTNode label = findLabel(image);
        if (label == null) return "";
        String raw = ASTUtilKt.getTextInNode(label, text).toString();
        return raw.length() >= 2 && raw.startsWith("[") && raw.endsWith("]") ? raw.substring(1, raw.length() - 1) : raw;
    }

    private static ASTNode findLabel(ASTNode node) {
        if (node.getType() == MarkdownElementTypes.LINK_TEXT || node.getType() == MarkdownElementTypes.LINK_LABEL) {
            return node;
        }
        for (ASTNode child : node.getChildren()) {
            ASTNode found = findLabel(child);
            if (found != null) return found;
        }
        return null;
    }
}
