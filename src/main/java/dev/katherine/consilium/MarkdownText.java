package dev.katherine.consilium;

import com.intellij.markdown.utils.MarkdownToHtmlConverterKt;

/**
 * Ответы агентов приходят в Markdown; показываем их отрисованными, как в чате, а не со
 * звёздочками и обратными кавычками. Разбор — платформенный конвертер IDEA (GFM).
 */
final class MarkdownText {
    private MarkdownText() {
    }

    static String toHtml(String markdown) {
        return MarkdownToHtmlConverterKt.convertMarkdownToHtml(escapeRawHtml(markdown == null ? "" : markdown));
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
        boolean inFence = false;
        for (String line : markdown.split("\n", -1)) {
            if (!out.isEmpty()) out.append('\n');
            if (line.stripLeading().startsWith("```")) {
                inFence = !inFence;
                out.append(line);
            } else if (inFence) {
                out.append(line);
            } else {
                out.append(escapeOutsideCodeSpans(line));
            }
        }
        return out.toString();
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
            out.append(c == '<' ? "&lt;" : String.valueOf(c));
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
