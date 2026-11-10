package dev.katherine.consilium;

import com.intellij.ui.BrowserHyperlinkListener;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

/**
 * Текст сообщения в карточке: Markdown, отрисованный в HTML. Исходник храним отдельно —
 * его сохраняет транскрипт и отдаёт «Стоп», а {@code getText()} у HTML-панели вернул бы разметку.
 */
final class MessageBody extends JEditorPane {
    private String markdown = "";

    MessageBody() {
        setEditorKit(new HTMLEditorKitBuilder().withWordWrapViewFactory().build());
        setEditable(false);
        setOpaque(false);
        setBorder(JBUI.Borders.empty());
        // Шрифт и цвет — как у остальной панели, а не дефолтный Serif у HTML.
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        setFont(JBUI.Fonts.label());
        addHyperlinkListener(new BrowserHyperlinkListener());
        // Текст можно выделять и копировать, но мигающий курсор в прочитанном сообщении не нужен.
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

    // Стили собираем при каждой отрисовке: цвет фона кода зависит от темы, а тема может
    // смениться, пока окно открыто. Поля по умолчанию у абзацев и списков рассчитаны на
    // страницу и дают в карточке пустую строку сверху.
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

    // BasicTextUI сам считает высоту HTML по текущей ширине, но ширину просит под самую длинную
    // строку без переносов — карточка распёрла бы ленту вбок. Ширину отдаём ту, что дали;
    // до первой раскладки — скромную, а когда ширина известна, просим раскладку заново.
    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        int width = getWidth() > 0 ? getWidth() : Math.min(preferred.width, JBUI.scale(INITIAL_WIDTH));
        return new Dimension(width, preferred.height);
    }

    private static final int INITIAL_WIDTH = 320;
}
