package dev.katherine.consilium;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.NamedColorUtil;
import dev.katherine.consilium.cli.ClaudeCliAgent;
import dev.katherine.consilium.cli.CliAgent;
import dev.katherine.consilium.cli.CodexCliAgent;
import dev.katherine.consilium.model.ChatMessage;
import dev.katherine.consilium.model.Speaker;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;

/** The tool window: layout and message cards. Turn logic lives in {@link ChatSession}. */
final class ConsiliumPanel implements Disposable, ChatSession.View {
    // Opening a project with a long transcript builds every card, and each parses Markdown.
    // Cards are added a batch per event so the IDE stays responsive while they load.
    private static final int HISTORY_BATCH = 25;

    private record MessageCard(JBLabel name, MessageBody body) {
    }

    private final Project project;
    private final ChatSession session;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JPanel messageList = new JPanel();
    // Stored cards go here, above the live ones, so a message sent while the history is still
    // loading lands below it, in order.
    private final JPanel history = new JPanel();
    private final JBScrollPane transcriptScroll;
    private final JBTextArea input = new JBTextArea(4, 20);
    private final ComboBox<TurnMode> mode = new ComboBox<>(TurnMode.values());
    private final JBCheckBox includeEditorContext = new JBCheckBox(ConsiliumBundle.message("checkbox.editor.context"), true);
    private final JButton send = new JButton(ConsiliumBundle.message("button.send"));
    private final JButton stop = new JButton(ConsiliumBundle.message("button.stop"));
    private final JButton clear = new JButton(ConsiliumBundle.message("button.clear"));
    private final JBLabel status = new JBLabel();
    private final boolean hasProjectRoot;
    // Bumped by Clear, so batches of a history that was cleared stop adding cards.
    private int historyGeneration;
    private volatile boolean disposed;

    ConsiliumPanel(Project project) {
        this.project = project;
        String basePath = project.getBasePath();
        hasProjectRoot = basePath != null;
        // Without a base path (the default project) agents would run in the IDE's own working
        // directory, which may be "/". Such a panel stays read-only and says why.
        Path rootPath = hasProjectRoot ? Path.of(basePath) : Path.of(System.getProperty("user.home"));
        session = new ChatSession(new ConsiliumStateStore(project), ConsiliumPanel::agentFor, rootPath, this::onUi, this);

        messageList.setLayout(new BoxLayout(messageList, BoxLayout.Y_AXIS));
        history.setLayout(new BoxLayout(history, BoxLayout.Y_AXIS));
        history.setAlignmentX(Component.LEFT_ALIGNMENT);
        history.setOpaque(false);
        JPanel transcript = new WidthTrackingPanel();
        transcript.add(messageList, BorderLayout.NORTH);
        transcript.setBorder(JBUI.Borders.empty(10));
        transcriptScroll = new JBScrollPane(transcript);
        transcriptScroll.setBorder(JBUI.Borders.empty());
        buildUi();
        showHistory(session.messages());
        stateChanged(session.state());
    }

    JComponent component() {
        return root;
    }

    private static CliAgent agentFor(Speaker speaker) {
        return switch (speaker) {
            case CLAUDE -> new ClaudeCliAgent();
            case CODEX -> new CodexCliAgent();
            case USER -> throw new IllegalArgumentException("The user is not an agent");
        };
    }

    private void buildUi() {
        // One row on a shared baseline: title, version, status and the button text sit on one line
        // instead of each centering on its own height. FlowLayout would add a leading gap and push
        // the header away from the cards' edge.
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
        addOnBaseline(actions, mode, 10, false);
        addOnBaseline(actions, includeEditorContext, 10, false);
        addOnBaseline(actions, send, 6, false);
        addOnBaseline(actions, stop, 0, false);
        addOnBaseline(actions, Box.createHorizontalGlue(), 0, true);

        JPanel composer = new JPanel(new BorderLayout(0, JBUI.scale(7)));
        composer.setBorder(JBUI.Borders.empty(8, 10, 10, 10));
        composer.add(inputScroll, BorderLayout.CENTER);
        composer.add(actions, BorderLayout.SOUTH);

        root.add(header, BorderLayout.NORTH);
        root.add(transcriptScroll, BorderLayout.CENTER);
        root.add(composer, BorderLayout.SOUTH);

        send.addActionListener(event -> send());
        stop.addActionListener(event -> session.stop());
        clear.addActionListener(event -> session.clear());

        // Enter sends and Shift+Enter breaks the line, as in most chat apps; Cmd+Enter keeps
        // working for those who learned it. Pasted multi-line text is unaffected.
        input.getEmptyText().setText(ConsiliumBundle.message("input.hint"));
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        InputMap keys = input.getInputMap();
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "consilium-send");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, shortcut), "consilium-send");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                javax.swing.text.DefaultEditorKit.insertBreakAction);
        input.getActionMap().put("consilium-send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
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

    // The version comes from the installed plugin, so the window shows which build is running.
    private static String pluginVersion() {
        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId("dev.katherine.consilium"));
        return plugin == null ? "" : "v" + plugin.getVersion();
    }

    private void send() {
        if (!hasProjectRoot) return;
        TurnMode selected = (TurnMode) mode.getSelectedItem();
        String context = includeEditorContext.isSelected() ? editorContext() : "";
        if (session.send(input.getText(), selected == null ? TurnMode.BOTH : selected, context)) {
            input.setText("");
        }
    }

    // ChatSession.View

    @Override
    public void messageAdded(ChatMessage message) {
        if (isWelcomeShown()) resetTranscript();
        addCard(messageList, message);
        refreshTranscript();
    }

    @Override
    public ChatSession.AnswerView answerStarted(Speaker speaker) {
        MessageCard card = newCard(messageList, speaker, ConsiliumBundle.message("card.thinking", speaker.label()));
        refreshTranscript();
        return new ChatSession.AnswerView() {
            @Override
            public void preview(String markdown) {
                card.body().setMarkdown(markdown);
                scrollToBottom();
            }

            @Override
            public void completed(String markdown) {
                card.name().setText(speaker.label());
                card.body().setMarkdown(markdown);
                scrollToBottom();
            }

            @Override
            public void failed(String detail) {
                showFailed(card, speaker, detail);
                scrollToBottom();
            }

            @Override
            public void stopped(String partial) {
                showStopped(card, speaker, partial);
            }
        };
    }

    @Override
    public void stateChanged(ChatSession.State state) {
        boolean ready = hasProjectRoot && state == ChatSession.State.READY;
        send.setEnabled(ready);
        clear.setEnabled(ready);
        mode.setEnabled(ready);
        stop.setEnabled(state != ChatSession.State.READY);
        status.setText(ConsiliumBundle.message(switch (state) {
            case READY -> hasProjectRoot ? "status.ready" : "status.no.project";
            case WORKING -> "status.working";
            case FINISHING -> "status.finishing";
        }));
    }

    @Override
    public void transcriptCleared() {
        historyGeneration++;
        resetTranscript();
        addWelcome();
        refreshTranscript();
    }

    // Cards

    private void showHistory(List<ChatMessage> messages) {
        resetTranscript();
        if (messages.isEmpty()) {
            addWelcome();
            return;
        }
        int generation = historyGeneration;
        addHistoryBatch(messages, 0, generation);
    }

    private void addHistoryBatch(List<ChatMessage> messages, int from, int generation) {
        if (disposed || generation != historyGeneration) return;
        int to = Math.min(messages.size(), from + HISTORY_BATCH);
        for (int index = from; index < to; index++) {
            addCard(history, messages.get(index));
        }
        refreshTranscript();
        if (to < messages.size()) {
            SwingUtilities.invokeLater(() -> addHistoryBatch(messages, to, generation));
        }
    }

    private void addCard(JPanel list, ChatMessage message) {
        Speaker speaker = message.speakerValue();
        MessageCard card = newCard(list, speaker, speaker.label());
        switch (message.statusValue()) {
            case ANSWER -> card.body().setMarkdown(message.textValue());
            case STOPPED -> showStopped(card, speaker, message.textValue());
            case ERROR -> showFailed(card, speaker, message.textValue());
        }
    }

    private static void showStopped(MessageCard card, Speaker speaker, String partial) {
        card.name().setText(ConsiliumBundle.message("card.stopped", speaker.label()));
        String mark = "*" + ConsiliumBundle.message("answer.stopped.mark") + "*";
        card.body().setMarkdown(partial.isBlank() ? mark : partial + "\n\n" + mark);
    }

    private static void showFailed(MessageCard card, Speaker speaker, String detail) {
        card.name().setText(ConsiliumBundle.message("card.error", speaker.label()));
        card.body().showError(ConsiliumBundle.message("error.answer", detail), NamedColorUtil.getErrorForeground());
    }

    private MessageCard newCard(JPanel list, Speaker speaker, String title) {
        JPanel card = new MessageSurface(speaker);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(JBUI.scale(840), Integer.MAX_VALUE));

        JBLabel name = new JBLabel(title);
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        name.setForeground(accent(speaker));
        MessageBody body = new MessageBody();
        card.add(name, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        list.add(card);
        list.add(Box.createVerticalStrut(JBUI.scale(10)));
        return new MessageCard(name, body);
    }

    private void resetTranscript() {
        messageList.removeAll();
        history.removeAll();
        messageList.add(history);
    }

    private boolean isWelcomeShown() {
        return history.getComponentCount() == 1 && history.getComponent(0) instanceof WelcomeCard;
    }

    private void addWelcome() {
        history.add(new WelcomeCard());
    }

    private void refreshTranscript() {
        messageList.revalidate();
        messageList.repaint();
        scrollToBottom();
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = transcriptScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
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

    private void onUi(Runnable action) {
        if (disposed) return;
        SwingUtilities.invokeLater(() -> {
            if (!disposed) action.run();
        });
    }

    @Override
    public void dispose() {
        disposed = true;
        session.dispose();
    }

    private static Color accent(Speaker speaker) {
        return switch (speaker) {
            case USER -> new JBColor(new Color(56, 111, 216), new Color(138, 174, 255));
            case CLAUDE -> new JBColor(new Color(165, 92, 58), new Color(224, 145, 105));
            case CODEX -> new JBColor(new Color(15, 118, 110), new Color(101, 205, 187));
        };
    }

    private static final class WelcomeCard extends JPanel {
        WelcomeCard() {
            super(new BorderLayout());
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setBorder(JBUI.Borders.empty(18, 12));
            setMaximumSize(new Dimension(JBUI.scale(840), Integer.MAX_VALUE));
            setOpaque(false);
            JBTextArea text = new JBTextArea(ConsiliumBundle.message("welcome"));
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setOpaque(false);
            text.setBorder(JBUI.Borders.empty());
            // JTextArea defaults to a monospaced font; next to the card titles that's two typefaces.
            text.setFont(JBUI.Fonts.label());
            text.setForeground(JBColor.GRAY);
            add(text, BorderLayout.CENTER);
        }
    }

    /** A card with a soft speaker-tinted fill and a rounded accent bar, like the IDE's own AI chat. */
    private static final class MessageSurface extends JPanel {
        private final Speaker speaker;

        MessageSurface(Speaker speaker) {
            super(new BorderLayout(0, JBUI.scale(5)));
            this.speaker = speaker;
            setOpaque(false);
            setBorder(JBUI.Borders.empty(11, 16, 11, 14));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color color = accent(speaker);
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 20));
                int radius = JBUI.scale(12);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
                g.setColor(color);
                g.fillRoundRect(0, JBUI.scale(8), JBUI.scale(3), getHeight() - JBUI.scale(16),
                        JBUI.scale(3), JBUI.scale(3));
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    // The transcript follows the window width instead of growing to its longest line; otherwise
    // narrowing the window pushes the cards under a horizontal scroll bar.
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
}
