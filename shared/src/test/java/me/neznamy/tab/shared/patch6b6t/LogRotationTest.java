package me.neznamy.tab.shared.patch6b6t;

import me.neznamy.tab.shared.ErrorManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [6b6t patch] Log rotation (fix 6) and settings parsing.
 */
class LogRotationTest {

    @TempDir
    File dir;

    @Test
    void rotatesKeepsNewestAndNeverTouchesHandMadeFiles() throws Exception {
        File handMade = new File(dir, "errors-full-until-20260815.log");
        Files.write(handMade.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        File other = new File(dir, "placeholder-errors-20260101T000000Z.log"); // other base, must stay
        Files.write(other.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        File log = new File(dir, "errors.log");
        for (int i = 0; i < 5; i++) {
            Files.write(log.toPath(), ("content " + i).getBytes(StandardCharsets.UTF_8));
            assertTrue(ErrorManager.rotate(log, 3));
            assertFalse(log.exists());
        }
        String[] rotated = dir.list((d, n) -> n.matches("errors-\\d{8}T\\d{6}Z(-\\d{1,2})?\\.log"));
        assertNotNull(rotated);
        assertEquals(3, rotated.length, Arrays.toString(rotated));
        assertTrue(handMade.exists());
        assertTrue(other.exists());
        // Newest content kept
        String all = Arrays.stream(rotated).map(n -> {
            try {
                return new String(Files.readAllBytes(new File(dir, n).toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).sorted().collect(Collectors.joining(","));
        assertEquals("content 2,content 3,content 4", all);
    }

    @Test
    void rotateOfMissingFileFails() {
        assertFalse(ErrorManager.rotate(new File(dir, "nothing.log"), 3));
    }

    @Test
    void settingsDefaultsAndBadValues() {
        PatchSettings.setForTests(new Properties());
        PatchSettings s = PatchSettings.get();
        assertTrue(s.staleGuard);
        assertTrue(s.entryRestore);
        assertTrue(s.auditEnabled);
        assertTrue(s.ghostGc);
        assertTrue(s.logRotation);
        assertEquals(3, s.rotationKeep);
        assertEquals(120_000, s.tombstoneTtlMillis);
        assertEquals(5, s.statsIntervalMinutes);

        Properties p = new Properties();
        p.setProperty("stale-guard", "false");
        p.setProperty("rotation-keep", "abc");
        p.setProperty("audit-slice-ms", "999");
        PatchSettings.setForTests(p);
        s = PatchSettings.get();
        assertFalse(s.staleGuard);
        assertEquals(3, s.rotationKeep);
        assertEquals(5_000_000L, s.auditSliceNanos);
        PatchSettings.setForTests(new Properties());
    }

    @Test
    void settingsFileIsOptional() throws Exception {
        assertTrue(PatchSettings.load(dir).isEmpty());
        Files.write(new File(dir, PatchSettings.FILE_NAME).toPath(), "ghost-gc=false\nrotation-keep=7\nbad=1\naudit-enabled=maybe\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, PatchSettings.load(dir).size()); // audit-enabled=maybe
        assertFalse(PatchSettings.get().ghostGc);
        assertEquals(7, PatchSettings.get().rotationKeep);
        assertTrue(PatchSettings.get().auditEnabled);
        PatchSettings.setForTests(new Properties());
    }

    @Test
    void statsLineHasAllCountersAndDeltas() {
        PatchStats.deltaLine(5, 0, 0);
        PatchStats.modifyMissing.addAndGet(3);
        String line = PatchStats.deltaLine(5, 10, 20);
        assertTrue(line.startsWith("[TAB-6b6t] 5m: modify-missing=3 "), line);
        assertTrue(line.endsWith(" local=10 remote=20"), line);
        String next = PatchStats.deltaLine(5, 10, 20);
        assertTrue(next.contains("modify-missing=0"), next);
    }
}
