package dev.katherine.consilium.cli;

import dev.katherine.consilium.ConsiliumBundle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Codex starts every configured MCP server for a turn, and those servers run outside its sandbox,
 * so a read-only turn has to switch them off one by one. There is no single flag for it:
 * {@code -c mcp_servers={}} merges into the configured table instead of replacing it, and
 * {@code --ignore-user-config} would also drop the user's model and profile. The list comes from
 * {@code codex mcp list --json}, run with the turn's own executable, directory (project config can
 * add servers) and environment.
 *
 * <p>Everything here fails closed: if the servers can't be listed or a name can't be expressed as a
 * {@code -c} override, the read-only turn is refused rather than run with servers enabled.
 */
final class CodexMcpServers {
    static final Duration LIST_TIMEOUT = Duration.ofSeconds(10);
    // Codex splits -c paths on dots and does not unquote TOML quoted keys (with codex-cli 0.167.0,
    // `mcp_servers."a".enabled` creates a new server literally named "a" in quotes), so only bare
    // keys can be addressed.
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");
    private static final int MAX_LISTING_BYTES = 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 4 * 1024;

    private CodexMcpServers() {
    }

    /** The {@code -c} overrides that disable every enabled server, two arguments per server. */
    static List<String> disableArguments(List<String> serverNames) {
        List<String> arguments = new ArrayList<>();
        for (String name : serverNames) {
            arguments.add("-c");
            arguments.add("mcp_servers." + name + ".enabled=false");
        }
        return arguments;
    }

    /** Names of the servers that would start for a turn in {@code projectRoot}. */
    static List<String> enabled(Path binary, Path projectRoot, Map<String, String> environment, Duration timeout,
                                BooleanSupplier cancelled) throws TurnRefusedException {
        return parse(list(binary, projectRoot, environment, timeout, cancelled));
    }

    static List<String> parse(String listing) throws TurnRefusedException {
        JsonElement root;
        try {
            root = JsonParser.parseString(listing);
        } catch (JsonParseException exception) {
            throw refused("unreadable output of `codex mcp list --json`");
        }
        if (root == null || !root.isJsonArray()) throw refused("`codex mcp list --json` did not print a list");
        List<String> enabled = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            JsonObject server = element.isJsonObject() ? element.getAsJsonObject() : null;
            String name = Json.string(server, "name");
            JsonElement flag = server == null ? null : server.get("enabled");
            if (name == null || flag == null || !flag.isJsonPrimitive() || !flag.getAsJsonPrimitive().isBoolean()) {
                throw refused("unexpected entry in `codex mcp list --json`");
            }
            if (!flag.getAsBoolean()) continue;
            if (!BARE_KEY.matcher(name).matches()) {
                throw refused("server name '" + name.replaceAll("\\p{Cntrl}", "?") + "' can't be switched off with -c");
            }
            enabled.add(name);
        }
        return enabled;
    }

    private static String list(Path binary, Path projectRoot, Map<String, String> environment, Duration timeout,
                               BooleanSupplier cancelled) throws TurnRefusedException {
        Process process;
        try {
            process = new GeneralCommandLine(binary.toString(), "mcp", "list", "--json")
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                    .withWorkingDirectory(projectRoot)
                    .withCharset(StandardCharsets.UTF_8)
                    .createProcess();
        } catch (Exception exception) {
            throw refused(String.valueOf(exception.getMessage()));
        }
        try {
            process.getOutputStream().close();
            // Both streams are read concurrently so neither pipe can fill up and stall the CLI.
            CompletableFuture<byte[]> stdout = readAsync(process.getInputStream(), MAX_LISTING_BYTES);
            CompletableFuture<byte[]> stderr = readAsync(process.getErrorStream(), MAX_STDERR_BYTES);
            // Polled rather than one long wait, so Stop doesn't keep the room busy until the timeout.
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) throw refused("stopped");
                if (System.nanoTime() > deadline) {
                    throw refused("`codex mcp list --json` did not finish in " + timeout.toSeconds() + " s");
                }
            }
            String output = new String(stdout.get(timeout.toMillis(), TimeUnit.MILLISECONDS), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                String error = new String(stderr.get(timeout.toMillis(), TimeUnit.MILLISECONDS), StandardCharsets.UTF_8).strip();
                throw refused(error.isEmpty() ? "`codex mcp list --json` exited with code " + process.exitValue() : error);
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw refused("interrupted");
        } catch (IOException | ExecutionException | TimeoutException exception) {
            throw refused(String.valueOf(exception.getMessage()));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    /** Keeps the first {@code limit} bytes and drains the rest, so the writer never blocks. */
    private static CompletableFuture<byte[]> readAsync(InputStream stream, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                byte[] head = stream.readNBytes(limit);
                stream.transferTo(OutputStream.nullOutputStream());
                return head;
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }, AppExecutorUtil.getAppExecutorService());
    }

    private static TurnRefusedException refused(String reason) {
        return new TurnRefusedException(ConsiliumBundle.message("error.mcp.list", reason));
    }
}
