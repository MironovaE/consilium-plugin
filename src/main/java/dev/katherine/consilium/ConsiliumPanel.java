package dev.katherine.consilium;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.extensions.PluginId;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ConsiliumPanel implements Disposable {
    private record MessageCard(JBLabel name, MessageBody body) {
    }

    private enum Target {
        BOTH("target.both"),
        CLAUDE("target.claude"),
        CODEX("target.codex"),
        CLAUDE_WRITES("target.claude.writes"),
        CODEX_WRITES("target.codex.writes");

        private final String key;

        Target(String key) {
            this.key = key;
        }

        @Override
        public String toString() {
            return ConsiliumBundle.message(key);
        }
    }

    private final Project project;
    private final ConsiliumStateStore store;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel transcript = new WidthTrackingPanel();
    private final JPanel messageList = new JPanel();
    private final JBScrollPane transcriptScroll;
    private final JBTextArea input = new JBTextArea(4, 20);
    private final ComboBox<Target> target = new ComboBox<>(Target.values());
    private final JBCheckBox includeEditorContext = new JBCheckBox(ConsiliumBundle.message("checkbox.editor.context"), true);
    private final JButton send = new JButton(ConsiliumBundle.message("button.send"));
    private final JButton stop = new JButton(ConsiliumBundle.message("button.stop"));
    private final JButton clear = new JButton(ConsiliumBundle.message("button.clear"));
    private final JBLabel status = new JBLabel(ConsiliumBundle.message("status.ready"));
    // Держим и запрос, и карточку: Стоп должен честно пометить карточку
    // «остановлен», а не оставить её «думает…» навсегда.
    // settled — кто первым закрыл ход: ответ дошёл до конца или нажали «Стоп». Ответ, пришедший
    // в момент нажатия, иначе сохранился бы дважды — целиком и обрезком с пометкой.
    private record ActiveRun(RunningRequest request, MessageCard card, AtomicBoolean settled) {
    }

    static final String STOPPED_MARK = ConsiliumBundle.message("answer.stopped.mark");

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
        // Один ряд по базовой линии: заголовок, версия, статус и текст кнопки стоят на одной
        // строке, а не центрируются каждый по своей высоте. FlowLayout тут не годится — он
        // добавляет зазор и слева, и шапка уезжает от края карточек.
        JPanel header = new JPanel(new GridBagLayout());
        header.setBorder(JBUI.Borders.empty(8, 10));
        JBLabel title = new JBLabel("Consilium");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1));
        status.setForeground(JBColor.GRAY);
        JBLabel version = new JBLabel(pluginVersion());
        version.setForeground(JBColor.GRAY);
        addOnBaseline(header, title, 6, false);
        addOnBaseline(header, version, 12, false);
        addOnBaseline(header, status, 8, true);
        addOnBaseline(header, clear, 0, false);

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setBorder(JBUI.Borders.empty(8));
        input.setFont(JBUI.Fonts.label());
        JBScrollPane inputScroll = new JBScrollPane(input);
        inputScroll.setPreferredSize(new Dimension(100, JBUI.scale(76)));

        JPanel actions = new JPanel(new GridBagLayout());
        addOnBaseline(actions, new JBLabel(ConsiliumBundle.message("label.mode")), 6, false);
        addOnBaseline(actions, target, 10, false);
        addOnBaseline(actions, includeEditorContext, 10, false);
        addOnBaseline(actions, send, 6, false);
        addOnBaseline(actions, stop, 0, false);
        addOnBaseline(actions, Box.createHorizontalGlue(), 0, true);
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

    private static void addOnBaseline(JPanel row, Component component, int gapAfter, boolean grow) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = row.getComponentCount();
        c.gridy = 0;
        c.anchor = GridBagConstraints.BASELINE_LEADING;
        c.weightx = grow ? 1 : 0;
        c.fill = grow ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        c.insets = JBUI.insetsRight(gapAfter);
        row.add(component, c);
    }

    // Версию берём из установленного плагина, чтобы в окне было видно, какая сборка стоит.
    private static String pluginVersion() {
        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.katherine.consilium"));
        return plugin == null ? "" : "v" + plugin.getVersion();
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
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setBorder(JBUI.Borders.empty(18, 12));
        card.setMaximumSize(new Dimension(JBUI.scale(840), Integer.MAX_VALUE));
        JBTextArea text = messageArea(ConsiliumBundle.message("welcome"));
        text.setForeground(JBColor.GRAY);
        card.add(text, BorderLayout.CENTER);
        messageList.add(card);
    }

    private void send() {
        if (!running.isEmpty()) return;
        String text = input.getText().trim();
        if (text.isEmpty()) return;

        if (store.messages().isEmpty()) messageList.removeAll();
        store.add(Speaker.USER, text);
        addMessageCard(Speaker.USER, text, false);
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
        AtomicBoolean settled = new AtomicBoolean();
        // Дельты приходят каждые несколько токенов, а разбор Markdown и пересборка HTML — дорогие.
        // На EDT уходит одна перерисовка за раз с самым свежим текстом, а не очередь из сотен.
        AtomicReference<String> latest = new AtomicReference<>();

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
                if (latest.getAndSet(text.toString()) != null) return;
                onUi(() -> {
                    String rendered = latest.getAndSet(null);
                    if (rendered == null || settled.get()) return;
                    response.body().setMarkdown(rendered);
                    scrollToBottom();
                });
            }

            @Override
            public void onCompleted() {
                if (!settled.compareAndSet(false, true)) return;
                String answer = text.toString().trim();
                if (answer.isEmpty()) answer = ConsiliumBundle.message("answer.empty");
                String finalAnswer = answer;
                store.add(speaker, finalAnswer);
                if (access == AgentAccess.READ_ONLY) {
                    store.updateSession(speaker, sessionId.get(), snapshotSize);
                }
                onUi(() -> {
                    response.body().setMarkdown(finalAnswer);
                    response.name().setText(speaker.label());
                    finish(speaker);
                });
            }

            @Override
            public void onError(String message) {
                if (!settled.compareAndSet(false, true)) return;
                String error = ConsiliumBundle.message("error.answer", message);
                store.add(speaker, error);
                onUi(() -> {
                    response.body().showError(error, JBColor.RED);
                    response.name().setText(ConsiliumBundle.message("card.error", speaker.label()));
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
        running.put(speaker, new ActiveRun(request, response, settled));
    }

    private MessageCard addMessageCard(Speaker speaker, String initialText, boolean pending) {
        JPanel card = new JPanel(new BorderLayout(0, JBUI.scale(5)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(JBUI.scale(840), Integer.MAX_VALUE));
        card.setBorder(cardBorder(speaker));
        card.setOpaque(false);

        JBLabel name = new JBLabel(pending ? ConsiliumBundle.message("card.thinking", speaker.label()) : speaker.label());
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        name.setForeground(accent(speaker));
        MessageBody body = new MessageBody();
        body.setMarkdown(initialText);
        card.add(name, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        messageList.add(card);
        messageList.add(Box.createVerticalStrut(JBUI.scale(8)));
        messageList.revalidate();
        messageList.repaint();
        scrollToBottom();
        return new MessageCard(name, body);
    }

    // Лента подстраивается под ширину окна, а не растягивается под самую длинную
    // строку: иначе при сужении окна карточки уезжают под горизонтальную прокрутку.
    private static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel() {
            super(new BorderLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return JBUI.scale(16);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static JBTextArea messageArea(String value) {
        JBTextArea area = new JBTextArea(value == null ? "" : value);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(JBUI.Borders.empty());
        // JTextArea по умолчанию моноширинная — в карточке рядом с именем это две гарнитуры.
        area.setFont(JBUI.Fonts.label());
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

    private static Color accent(Speaker speaker) {
        return switch (speaker) {
            case USER -> new JBColor(new Color(177, 122, 16), new Color(231, 176, 70));
            case CLAUDE -> new JBColor(new Color(165, 92, 58), new Color(224, 145, 105));
            case CODEX -> new JBColor(new Color(15, 118, 110), new Color(101, 205, 187));
        };
    }

    private String editorContext() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return "";
        String path = editor.getVirtualFile() == null ? ConsiliumBundle.message("context.current.editor") : editor.getVirtualFile().getPath();
        String selectedText = editor.getSelectionModel().getSelectedText();
        if (selectedText == null || selectedText.isBlank()) {
            return ConsiliumBundle.message("context.file.no.selection", path);
        }
        return ConsiliumBundle.message("context.file", path) + "\n```\n" + selectedText + "\n```";
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
        status.setText(ConsiliumBundle.message(busy ? "status.working" : "status.ready"));
    }

    private void cancelAll() {
        for (Map.Entry<Speaker, ActiveRun> entry : new ArrayList<>(running.entrySet())) {
            entry.getValue().request().cancel();
            if (!entry.getValue().settled().compareAndSet(false, true)) continue;
            // Партиал оставляем видимым, но карточка перестаёт врать «думает…»:
            // отменённый агент listener'а уже не позовёт.
            MessageCard card = entry.getValue().card();
            card.name().setText(ConsiliumBundle.message("card.stopped", entry.getKey().label()));
            // И сохраняем его с пометкой, иначе после перезапуска IDEA он пропадёт.
            String partial = card.body().markdown().trim();
            if (!partial.isEmpty()) {
                store.add(entry.getKey(), partial + "\n\n" + STOPPED_MARK);
            }
        }
        running.clear();
        updateControls();
        status.setText(ConsiliumBundle.message("status.stopped"));
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
