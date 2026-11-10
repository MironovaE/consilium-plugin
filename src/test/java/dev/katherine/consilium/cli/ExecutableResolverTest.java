package dev.katherine.consilium.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledOnOs(OS.WINDOWS)
class ExecutableResolverTest {
    // A name nobody has in ~/.local/bin, which the resolver checks before PATH.
    private static final String NAME = "consilium-test-cli-7f3a";

    @TempDir
    Path dir;

    private Path executable(Path directory) throws IOException {
        Path file = Files.createDirectories(directory).resolve(NAME);
        Files.writeString(file, "#!/bin/sh\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    @Test
    void resolvesToAnAbsolutePathOutsideTheProject() throws IOException {
        Path project = Files.createDirectories(dir.resolve("project"));
        executable(project.resolve("bin"));
        Path trusted = executable(dir.resolve("usr/bin"));
        String path = String.join(":", ".", "", "bin", project.resolve("bin").toString(), dir.resolve("usr/bin").toString());

        Path resolved = ExecutableResolver.resolve(NAME, project, Map.of("PATH", path));
        assertTrue(resolved.isAbsolute());
        assertEquals(trusted, resolved);
    }

    @Test
    void refusesAnExecutableThatOnlyExistsInsideTheProject() throws IOException {
        Path project = Files.createDirectories(dir.resolve("project"));
        executable(project.resolve("node_modules/.bin"));

        IOException error = assertThrows(IOException.class, () -> ExecutableResolver.resolve(NAME, project,
                Map.of("PATH", project.resolve("node_modules/.bin").toString())));
        assertTrue(error.getMessage().contains(NAME), error.getMessage());
    }

    @Test
    void reportsAMissingExecutable() {
        assertThrows(IOException.class, () -> ExecutableResolver.resolve(NAME, dir, Map.of("PATH", "/usr/bin")));
        assertThrows(IOException.class, () -> ExecutableResolver.resolve(NAME, dir, Map.of()));
    }

    @Test
    void lineReaderCapsLongLines() throws IOException {
        BoundedLineReader reader = new BoundedLineReader(new StringReader("short\r\n" + "y".repeat(50) + "\nlast"), 10);
        assertEquals("short", reader.readLine());
        assertFalse(reader.lastLineTruncated());
        assertEquals("y".repeat(10), reader.readLine());
        assertTrue(reader.lastLineTruncated());
        assertEquals("last", reader.readLine());
        assertFalse(reader.lastLineTruncated());
        assertNull(reader.readLine());
    }
}
