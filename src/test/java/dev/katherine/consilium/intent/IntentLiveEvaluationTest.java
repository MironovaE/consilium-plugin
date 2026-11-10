package dev.katherine.consilium.intent;

import dev.katherine.consilium.cli.ClaudeOneShot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the real {@code claude} over the labeled set and reports accuracy, every miss and latency
 * per model. A developer step, not a unit test: it costs API calls and minutes, so it runs only with
 * {@code CONSILIUM_LIVE_EVAL=1}. {@code CONSILIUM_EVAL_MODELS} picks the models (comma-separated,
 * "default" for the user's own model; defaults to "default,sonnet,haiku"). The report goes to stdout and
 * to {@code build/reports/intent-eval.txt}.
 *
 * <pre>CONSILIUM_LIVE_EVAL=1 ./gradlew test --tests '*IntentLiveEvaluationTest' --rerun</pre>
 */
@EnabledIfEnvironmentVariable(named = "CONSILIUM_LIVE_EVAL", matches = "1")
class IntentLiveEvaluationTest {
    // An empty working directory: nothing of this repository can reach the call.
    @TempDir
    Path emptyProject;

    private record Outcome(LabeledMessages.Case labeled, Intent actual, long millis) {
    }

    @Test
    void evaluate() throws Exception {
        List<LabeledMessages.Case> cases = LabeledMessages.load();
        String models = System.getenv().getOrDefault("CONSILIUM_EVAL_MODELS", "default,sonnet,haiku");
        StringBuilder report = new StringBuilder();
        int dangerousTotal = 0;
        for (String name : models.split(",")) {
            String model = name.strip().equals("default") ? null : name.strip();
            ClaudeIntentClassifier classifier = new ClaudeIntentClassifier(
                    new ClaudeOneShot(), model, ClaudeIntentClassifier.DEFAULT_TIMEOUT);
            List<Outcome> outcomes = new ArrayList<>();
            // One at a time, so latency is what a person waiting for one message would see.
            for (LabeledMessages.Case labeled : cases) {
                long started = System.nanoTime();
                Intent actual = classifier.classify(labeled.message(), emptyProject).toCompletableFuture().get(60, TimeUnit.SECONDS);
                outcomes.add(new Outcome(labeled, actual, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            }
            dangerousTotal += describe(name.strip(), outcomes, report);
        }
        System.out.println(report);
        Path file = Path.of("build", "reports", "intent-eval.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, report, StandardCharsets.UTF_8);
        assertEquals(0, dangerousTotal, "discussion read as work:\n" + report);
    }

    /** Appends the model's section and returns how many dangerous misreads it made. */
    private static int describe(String model, List<Outcome> outcomes, StringBuilder report) {
        int exact = 0;
        int kindRight = 0;
        List<String> misses = new ArrayList<>();
        int dangerous = 0;
        for (Outcome outcome : outcomes) {
            Intent expected = outcome.labeled().expected();
            boolean sameKind = expected.kind() == outcome.actual().kind();
            if (sameKind) kindRight++;
            if (expected.equals(outcome.actual())) {
                exact++;
                continue;
            }
            boolean danger = LabeledMessages.isDangerous(expected, outcome.actual());
            if (danger) dangerous++;
            misses.add((danger ? "  DANGEROUS " : "  ") + "\"" + outcome.labeled().message() + "\": expected "
                    + format(expected) + ", got " + format(outcome.actual()));
        }
        long[] latencies = outcomes.stream().mapToLong(Outcome::millis).sorted().toArray();
        int n = outcomes.size();
        report.append("Model ").append(model).append(": ").append(n).append(" messages\n")
                .append("  exact (kind and agents): ").append(exact).append('/').append(n)
                .append(String.format(" (%.0f%%)%n", 100.0 * exact / n))
                .append("  kind only: ").append(kindRight).append('/').append(n).append('\n')
                .append("  dangerous (discussion read as work): ").append(dangerous).append('\n')
                .append("  latency ms: p50 ").append(percentile(latencies, 50))
                .append(", p95 ").append(percentile(latencies, 95))
                .append(", max ").append(latencies[n - 1]).append('\n')
                .append("  misses:\n");
        misses.forEach(miss -> report.append(miss).append('\n'));
        report.append('\n');
        return dangerous;
    }

    private static String format(Intent intent) {
        return intent.kind() + " " + Arrays.toString(intent.agents().stream().map(Enum::name).sorted().toArray());
    }

    private static long percentile(long[] sorted, int percent) {
        int index = (int) Math.ceil(percent / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
