package dev.katherine.consilium.intent;

import dev.katherine.consilium.model.Speaker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The labeled evaluation set in {@code src/test/resources/intent/labeled.tsv}. */
final class LabeledMessages {
    record Case(String message, Intent expected) {
        /** The answer the model should give for this case, as the schema spells it. */
        String answerJson() {
            Set<Speaker> agents = expected.agents();
            String addressee = agents.size() == 2 ? "BOTH" : agents.isEmpty() ? "UNSPECIFIED" : agents.iterator().next().name();
            return "{\"intent\":\"" + expected.kind().name() + "\",\"addressee\":\"" + addressee + "\"}";
        }
    }

    private LabeledMessages() {
    }

    static List<Case> load() throws IOException {
        try (InputStream stream = LabeledMessages.class.getResourceAsStream("/intent/labeled.tsv")) {
            if (stream == null) throw new IOException("labeled.tsv is missing from the test resources");
            List<Case> cases = new ArrayList<>();
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab < 0) throw new IOException("No tab in: " + line);
                String[] label = line.substring(0, tab).trim().split(" ");
                if (label.length != 2) throw new IOException("Bad label in: " + line);
                cases.add(new Case(line.substring(tab + 1), new Intent(Intent.Kind.valueOf(label[0]), agents(label[1]))));
            }
            return cases;
        }
    }

    private static Set<Speaker> agents(String label) {
        return switch (label) {
            case "CLAUDE" -> Set.of(Speaker.CLAUDE);
            case "CODEX" -> Set.of(Speaker.CODEX);
            case "BOTH" -> Set.of(Speaker.CLAUDE, Speaker.CODEX);
            case "NONE" -> Set.of();
            default -> throw new IllegalArgumentException("Unknown agents label: " + label);
        };
    }

    /** Agents were asked to change the project although the person didn't ask for it. */
    static boolean isDangerous(Intent expected, Intent actual) {
        return grantsWrites(actual) && !grantsWrites(expected);
    }

    private static boolean grantsWrites(Intent intent) {
        return intent.kind() == Intent.Kind.WORK || intent.kind() == Intent.Kind.INTEGRATE;
    }
}
