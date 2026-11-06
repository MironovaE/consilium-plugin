package dev.katherine.consilium;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import dev.katherine.consilium.cli.AgentListener;
import dev.katherine.consilium.cli.AgentAccess;
import dev.katherine.consilium.cli.ClaudeCliAgent;
import dev.katherine.consilium.cli.CliAgent;
import dev.katherine.consilium.cli.CodexCliAgent;
import dev.katherine.consilium.cli.RunningRequest;
import dev.katherine.consilium.model.AgentSession;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.Speaker;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

final class ConsiliumPanel implements Disposable {
    private record MessageCard(JBLabel name, JBTextArea body) {
    }

    private enum Target {
        BOTH("Оба обсуждают"),
        CLAUDE("Claude отвечает"),
        CODEX("Codex отвечает"),
        CLAUDE_WRITES("Claude делает"),
        CODEX_WRITES("Codex делает");

        private final String label;

        Target(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final Project project;
    private final ConsiliumStateStore store;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel transcript = new JPanel(new BorderLayout());
    private final JPanel messageList = new JPanel();
    private final JBScrollPane transcriptScroll;
    private final JBTextArea input = new JBTextArea(4, 20);
    private final ComboBox<Target> target = new ComboBox<>(Target.values());
    private final JBCheckBox includeEditorContext = new JBCheckBox("контекст редактора", true);
    private final JButton send = new JButton("Отправить");
    private final JButton stop = new JButton("Стоп");
    private final JButton clear = new JButton("Очистить");
    private final JBLabel status = new JBLabel("Готов к обсуждению или работе");
    // Держим и запрос, и карточку: Стоп должен честно пометить карточку
    // «остановлен», а не оставить её «думает…» навсегда.
    private record ActiveRun(RunningRequest request, MessageCard card) {
    }

    static final String STOPPED_MARK = "[остановлено до конца ответа]";

    private final Map<Speaker, ActiveRun> running = new EnumMap<>(Speaker.class);
    private volatile boolean disposed;

    ConsiliumPanel(Project project) {
        this.project = project;
        this.store = new ConsiliumStateStore(project);
        messageList.setLayout(new BoxLayout(messageList, BoxLayout.Y_AXIS));
        transcript.add(messageList, BorderLayout.NORTH);
        transcript.setBorder(JBUI.Borders.empty(10));
        transcriptScroll = new JBScrollPane(transcript);
        transcriptScroll.setBorder(JBUI.Borders.empty());
        buildUi();
        renderStoredMessages();
    }

    JComponent component() {
        return root;
    }

    private void buildUi() {
        JPanel header = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        header.setBorder(JBUI.Borders.empty(8, 10));
        JBLabel title = new JBLabel("Consilium");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1));
        status.setForeground(JBColor.GRAY);
        header.add(title, BorderLayout.WEST);
        header.add(status, BorderLayout.CENTER);
        header.add(clear, BorderLayout.EAST);

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setBorder(JBUI.Borders.empty(8));
        JBScrollPane inputScroll = new JBScrollPane(input);
        inputScroll.setPreferredSize(new Dimension(100, JBUI.scale(94)));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        actions.add(new JBLabel("Режим:"));
        actions.add(target);
        actions.add(includeEditorContext);
        actions.add(send);
        actions.add(stop);
        stop.setEnabled(false);

        JPanel composer = new JPanel(new BorderLayout(0, JBUI.scale(7)));
        composer.setBorder(JBUI.Borders.empty(8, 10, 10, 10));
        composer.add(inputScroll, BorderLayout.CENTER);
        composer.add(actions, BorderLayout.SOUTH);

        root.add(header, BorderLayout.NORTH);
        root.add(transcriptScroll, BorderLayout.CENTER);
        root.add(composer, BorderLayout.SOUTH);

        send.addActionListener(event -> send());
        stop.addActionListener(event -> cancelAll());
        clear.addActionListener(event -> clear());

        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        KeyStroke sendStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, shortcut);
        input.getInputMap().put(sendStroke, "consilium-send");
        input.getActionMap().put("consilium-send", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                send();
            }
        });
    }

    private void renderStoredMessages() {
        for (ChatMessage message : store.messages()) {
            addMessageCard(message.speakerValue(), message.text, false);
        }
        if (store.messages().isEmpty()) {
            addWelcome();
        }
    }

    private void addWelcome() {
        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(JBUI.Borders.empty(18, 12));
        JBTextArea text = messageArea("Одна комната для Katherine, Claude и Codex.\nНапиши сообщение — оба получат один независимый снимок разговора.");
        text.setForeground(JBColor.GRAY);
        card.add(text, BorderLayout.CENTER);
        messageList.add(card);
    }

    private void send() {
        if (!running.isEmpty()) return;
        String text = input.getText().trim();
        if (text.isEmpty()) return;

        if (store.messages().isEmpty()) messageList.removeAll();
        store.add(Speaker.KATHERINE, text);
        addMessageCard(Speaker.KATHERINE, text, false);
        input.setText("");

        List<ChatMessage> snapshot = store.messages();
        int snapshotSize = snapshot.size();
        String editorContext = includeEditorContext.isSelected() ? editorContext() : "";
        Target selected = (Target) target.getSelectedItem();
        if (selected == null) selected = Target.BOTH;

        switch (selected) {
            case BOTH -> {
                startAgent(new ClaudeCliAgent(), snapshot, snapshotSize, editorContext, AgentAccess.READ_ONLY);
                startAgent(new CodexCliAgent(), snapshot, snapshotSize, editorContext, AgentAccess.READ_ONLY);
            }
            case CLAUDE -> startAgent(new ClaudeCliAgent(), snapshot, snapshotSize, editorContext, AgentAccess.READ_ONLY);
            case CODEX -> startAgent(new CodexCliAgent(), snapshot, snapshotSize, editorContext, AgentAccess.READ_ONLY);
            case CLAUDE_WRITES -> startAgent(new ClaudeCliAgent(), snapshot, snapshotSize, editorContext, AgentAccess.WRITE);
            case CODEX_WRITES -> startAgent(new CodexCliAgent(), snapshot, snapshotSize, editorContext, AgentAccess.WRITE);
        }
        updateControls();
    }

    private void startAgent(CliAgent agent, List<ChatMessage> snapshot, int snapshotSize, String editorContext, AgentAccess access) {
        Speaker speaker = agent.speaker();
        AgentSession session = store.session(speaker);
        boolean fresh = access == AgentAccess.WRITE || session.sessionId == null || session.sessionId.isBlank();
        String prompt = ConversationPromptBuilder.build(snapshot, speaker, session.seenThrough, fresh, editorContext, access);
        MessageCard response = addMessageCard(speaker, "", true);
        StringBuilder text = new StringBuilder();
        AtomicReference<String> sessionId = new AtomicReference<>(session.sessionId);

        AgentListener listener = new AgentListener() {
            @Override
            public void onSessionId(String value) {
                if (value != null && !value.isBlank()) sessionId.set(value);
            }

            @Override
            public void onTextDelta(String delta) {
                text.append(delta);
                // Снимок строки берём ЗДЕСЬ (фоновый тред агента): StringBuilder не
                // потокобезопасен, toString() на EDT гонялся бы с append() здесь.
                String rendered = text.toString();
                onUi(() -> {
                    response.body().setText(rendered);
                    scrollToBottom();
                });
            }

            @Override
            public void onCompleted() {
                String answer = text.toString().trim();
                if (answer.isEmpty()) answer = "(пустой ответ)";
                String finalAnswer = answer;
                store.add(speaker, finalAnswer);
                if (access == AgentAccess.READ_ONLY) {
                    store.updateSession(speaker, sessionId.get(), snapshotSize);
                }
                onUi(() -> {
                    response.body().setText(finalAnswer);
                    response.name().setText(speaker.label());
                    finish(speaker);
                });
            }

            @Override
            public void onError(String message) {
                String error = "Не получилось ответить: " + message;
                store.add(speaker, error);
                onUi(() -> {
                    response.body().setText(error);
                    response.body().setForeground(JBColor.RED);
                    response.name().setText(speaker.label() + " · ошибка");
                    finish(speaker);
                });
            }
        };

        Path rootPath = Path.of(Objects.requireNonNullElse(project.getBasePath(), System.getProperty("user.dir")));
        // Ход «делает» всегда стартует новой сессией с полным транскриптом: чистый
        // контекст задачи, а права задают явные флаги, а не унаследованная сессия
        // обсуждения (у codex resume песочницу флагом не задать).
        String resumableSession = access == AgentAccess.READ_ONLY ? session.sessionId : null;
        RunningRequest request = agent.start(rootPath, prompt, resumableSession, access, listener);
        running.put(speaker, new ActiveRun(request, response));
    }

    private MessageCard addMessageCard(Speaker speaker, String initialText, boolean pending) {
        JPanel card = new JPanel(new BorderLayout(0, JBUI.scale(5)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(cardBorder(speaker));
        card.setOpaque(true);
        card.setBackground(cardBackground(speaker));

        JBLabel name = new JBLabel(pending ? speaker.label() + " · думает…" : speaker.label());
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        name.setForeground(accent(speaker));
        JBTextArea body = messageArea(initialText);
        card.add(name, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        messageList.add(card);
        messageList.add(Box.createVerticalStrut(JBUI.scale(8)));
        messageList.revalidate();
        messageList.repaint();
        scrollToBottom();
        return new MessageCard(name, body);
    }

    private static JBTextArea messageArea(String value) {
        JBTextArea area = new JBTextArea(value == null ? "" : value);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(JBUI.Borders.empty());
        return area;
    }

    private static Border cardBorder(Speaker speaker) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new JBColor(new Color(220, 220, 220), new Color(72, 75, 82))),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, accent(speaker)),
                        BorderFactory.createEmptyBorder(JBUI.scale(10), JBUI.scale(12), JBUI.scale(10), JBUI.scale(12))
                )
        );
    }

    private static Color cardBackground(Speaker speaker) {
        return switch (speaker) {
            case KATHERINE -> new JBColor(new Color(255, 249, 231), new Color(67, 56, 35));
            case CLAUDE -> new JBColor(new Color(248, 244, 238), new Color(57, 49, 44));
            case CODEX -> new JBColor(new Color(241, 247, 252), new Color(40, 51, 63));
        };
    }

    private static Color accent(Speaker speaker) {
        return switch (speaker) {
            case KATHERINE -> new JBColor(new Color(177, 122, 16), new Color(231, 176, 70));
            case CLAUDE -> new JBColor(new Color(165, 92, 58), new Color(224, 145, 105));
            case CODEX -> new JBColor(new Color(42, 100, 145), new Color(108, 170, 220));
        };
    }

    private String editorContext() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return "";
        String path = editor.getVirtualFile() == null ? "текущий редактор" : editor.getVirtualFile().getPath();
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isBlank()) {
            return "Открыт файл: " + path + " (текст не прикреплён — выделения нет).";
        }
        return "Файл: " + path + "\n```\n" + selectedText + "\n```";
    }

    private void finish(Speaker speaker) {
        running.remove(speaker);
        updateControls();
        scrollToBottom();
    }

    private void updateControls() {
        boolean busy = !running.isEmpty();
        send.setEnabled(!busy);
        clear.setEnabled(!busy);
        stop.setEnabled(busy);
        target.setEnabled(!busy);
        status.setText(busy ? "Consilium работает…" : "Готов к обсуждению или работе");
    }

    private void cancelAll() {
        for (Map.Entry<Speaker, ActiveRun> entry : new ArrayList<>(running.entrySet())) {
            entry.getValue().request().cancel();
            // Партиал оставляем видимым, но карточка перестаёт врать «думает…»:
            // отменённый агент listener'а уже не позовёт.
            MessageCard card = entry.getValue().card();
            card.name().setText(entry.getKey().label() + " · остановлен");
            // И сохраняем его с пометкой, иначе после перезапуска IDEA он пропадёт.
            String partial = card.body().getText().trim();
            if (!partial.isEmpty()) {
                store.add(entry.getKey(), partial + "\n\n" + STOPPED_MARK);
            }
        }
        running.clear();
        updateControls();
        status.setText("Остановлено");
    }

    private void clear() {
        if (!running.isEmpty()) return;
        store.clear();
        messageList.removeAll();
        addWelcome();
        messageList.revalidate();
        messageList.repaint();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = transcriptScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void onUi(@NotNull Runnable action) {
        if (disposed) return;
        SwingUtilities.invokeLater(() -> {
            if (!disposed) action.run();
        });
    }

    @Override
    public void dispose() {
        disposed = true;
        cancelAll();
    }
}
