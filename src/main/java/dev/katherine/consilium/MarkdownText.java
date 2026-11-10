package dev.katherine.consilium;

import com.intellij.markdown.utils.MarkdownToHtmlConverterKt;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ответы агентов приходят в Markdown; показываем их отрисованными, как в чате, а не со
 * звёздочками и обратными кавычками. Разбор — платформенный конвертер IDEA (GFM).
 */
final class MarkdownText {
    private MarkdownText() {
    }

    static String toHtml(String markdown) {
        String html = MarkdownToHtmlConverterKt.convertMarkdownToHtml(escapeRawHtml(markdown == null ? "" : markdown));
        return dropImages(html);
    }

    private static final Pattern IMAGE = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT = Pattern.compile("\\balt=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTOLINK = Pattern.compile("<(?:https?|mailto):[^\\s<>]*>", Pattern.CASE_INSENSITIVE);

    /**
     * Markdown-картинку {@code ![](https://…)} конвертер превращает в {@code <img>}, а Swing грузит
     * её из сети. Сырой {@code <} к этому моменту уже экранирован, так что здесь остаются только
     * настоящие теги картинок — вместо них показываем подпись.
     */
    static String dropImages(String html) {
        Matcher image = IMAGE.matcher(html);
        StringBuilder out = new StringBuilder();
        while (image.find()) {
            Matcher alt = ALT.matcher(image.group());
            image.appendReplacement(out, Matcher.quoteReplacement(alt.find() ? alt.group(1) : ""));
        }
        image.appendTail(out);
        return out.toString();
    }

    /**
     * Конвертер пропускает сырой HTML как есть, а Swing его исполняет: {@code List<String>} вне
     * кода пропадает как неизвестный тег, а {@code <img src=http…>} полез бы в сеть. Поэтому
     * {@code <} вне кода превращаем в сущность. Внутри кода конвертер экранирует сам, а наша
     * сущность показалась бы буквально — код не трогаем. Санитайзер платформы
     * ({@code DocMarkdownToHtmlConverter}) не подходит: требует запущенную IDE и пропускает {@code <img>}.
     * Блоки кода с отступом в четыре пробела не распознаём — агенты пишут код в ```.
     */
    static String escapeRawHtml(String markdown) {
        StringBuilder out = new StringBuilder(markdown.length());
        String fence = null;
        for (String line : markdown.split("\n", -1)) {
            if (!out.isEmpty()) out.append('\n');
            String opening = fenceOf(line.stripLeading());
            if (fence == null && opening != null) {
                fence = opening;
                out.append(line);
            } else if (fence != null) {
                // Закрывает блок забор того же символа не короче открывающего и без хвоста,
                // как в CommonMark: ```` с примером ``` внутри не закрывается раньше времени.
                String trimmed = line.strip();
                if (opening != null && opening.charAt(0) == fence.charAt(0)
                        && opening.length() >= fence.length() && trimmed.length() == opening.length()) {
                    fence = null;
                }
                out.append(line);
            } else {
                out.append(escapeOutsideCodeSpans(line));
            }
        }
        return out.toString();
    }

    private static String fenceOf(String line) {
        if (line.isEmpty() || (line.charAt(0) != '`' && line.charAt(0) != '~')) return null;
        int length = 0;
        while (length < line.length() && line.charAt(length) == line.charAt(0)) length++;
        return length >= 3 ? line.substring(0, length) : null;
    }

    private static String escapeOutsideCodeSpans(String line) {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '`') {
                int run = runLength(line, i);
                int close = findClosingRun(line, i + run, run);
                if (close >= 0) {
                    out.append(line, i, close + run);
                    i = close + run;
                    continue;
                }
                out.append(line, i, i + run);
                i += run;
                continue;
            }
            if (c == '<') {
                // Автоссылка <https://…> — не HTML, конвертер сделает из неё ссылку.
                Matcher link = AUTOLINK.matcher(line).region(i, line.length());
                if (link.lookingAt()) {
                    out.append(link.group());
                    i = link.end();
                    continue;
                }
                out.append("&lt;");
            } else {
                out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    private static int runLength(String line, int start) {
        int end = start;
        while (end < line.length() && line.charAt(end) == '`') end++;
        return end - start;
    }

    private static int findClosingRun(String line, int from, int run) {
        int i = from;
        while (i < line.length()) {
            if (line.charAt(i) == '`') {
                int length = runLength(line, i);
                if (length == run) return i;
                i += length;
            } else {
                i++;
            }
        }
        return -1;
    }
}
