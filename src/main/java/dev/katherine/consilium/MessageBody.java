package dev.katherine.consilium;

import com.intellij.ide.BrowserUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * The text of a message card: Markdown rendered to HTML. The source is kept separately because the
 * transcript saves it and "Stop" hands it back, while {@code getText()} would return the markup.
 */
final class MessageBody extends JEditorPane {
    private String markdown = "";

    MessageBody() {
        setEditorKit(new HTMLEditorKitBuilder().withWordWrapViewFactory().build());
        setEditable(false);
        setOpaque(false);
        setBorder(JBUI.Borders.empty());
        // Font and color match the rest of the panel instead of HTML's default serif.
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        setFont(JBUI.Fonts.label());
        addHyperlinkListener(this::onHyperlink);
        // Text can be selected and copied, but a blinking caret in a read message is noise.
        setCaret(new DefaultCaret() {
            @Override
            public boolean isVisible() {
                return false;
            }
        });
        addComponentListener(new ComponentAdapter() {
            private int laidOutWidth = -1;

            @Override
            public void componentResized(ComponentEvent event) {
                if (getWidth() == laidOutWidth) return;
                laidOutWidth = getWidth();
                revalidate();
            }
        });
    }

    String markdown() {
        return markdown;
    }

    void setMarkdown(String value) {
        markdown = value == null ? "" : value;
        setText("<html><head><style>" + css() + "</style></head><body>" + MarkdownText.toHtml(markdown) + "</body></html>");
    }

    void showError(String message, Color color) {
        setForeground(color);
        setMarkdown(message);
    }

    // MarkdownText already strips links with other schemes; checking again here keeps a click
    // from ever opening file:, javascript: or an app's custom scheme if that layer regresses.
    // The tooltip shows where a link leads before it is clicked.
    private void onHyperlink(HyperlinkEvent event) {
        String url = event.getDescription();
        if (event.getEventType() == HyperlinkEvent.EventType.ENTERED) {
            setToolTipText(MarkdownText.isOpenableLink(url) ? url : null);
        } else if (event.getEventType() == HyperlinkEvent.EventType.EXITED) {
            setToolTipText(null);
        } else if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && MarkdownText.isOpenableLink(url)) {
            BrowserUtil.browse(url);
        }
    }

    // Styles are built on every render: the code background depends on the theme, and the theme
    // can change while the window is open. Default paragraph and list margins are meant for a
    // page and would leave an empty line at the top of a card. No url(...) here: the stylesheet
    // must not make Swing fetch anything either.
    private static String css() {
        int gap = JBUI.scale(6);
        String codeBackground = ColorUtil.toHtmlColor(new JBColor(new Color(0xF0, 0xF1, 0xF3), new Color(0x2B, 0x2D, 0x30)));
        String mono = "font-family: " + Font.MONOSPACED + ";";
        return "body { margin: 0; }"
                + "p, ul, ol, pre, table { margin-top: 0; margin-bottom: " + gap + "px; }"
                + "ul { list-style-type: disc; margin-left: " + JBUI.scale(18) + "px; }"
                + "ol { margin-left: " + JBUI.scale(22) + "px; }"
                + "strong, b { font-weight: bold; }"
                + "code { " + mono + " }"
                + "pre { " + mono + " padding: " + gap + "px; background-color: " + codeBackground + "; }";
    }

    // BasicTextUI computes the HTML height for the current width, but asks for the width of the
    // longest unwrapped line, which would push the feed sideways. Report the width we were given;
    // before the first layout, a modest one, and re-layout once the real width is known.
    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        int width = getWidth() > 0 ? getWidth() : Math.min(preferred.width, JBUI.scale(INITIAL_WIDTH));
        return new Dimension(width, preferred.height);
    }

    private static final int INITIAL_WIDTH = 320;
}
