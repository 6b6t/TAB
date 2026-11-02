package me.neznamy.tab.shared.features.globalplayerlist;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HardMuteVisibilityTest {

    @AfterEach
    void clearSharedStatus() {
        HardMuteStatus.snapshot().forEach(id -> HardMuteStatus.setMuted(id, false));
    }

    @Test
    void mutedTargetIsHiddenFromOtherViewersButRemainsVisibleToSelf() {
        UUID muted = UUID.randomUUID();
        UUID observer = UUID.randomUUID();
        HardMuteStatus.setMuted(muted, true);

        assertFalse(HardMuteStatus.shouldShow(observer, muted));
        assertTrue(HardMuteStatus.shouldShow(muted, muted));
        assertTrue(HardMuteStatus.shouldShow(observer, UUID.randomUUID()));
    }

    @Test
    void statusSnapshotCanBeRefreshedForMuteAndUnmute() {
        UUID player = UUID.randomUUID();
        HardMuteStatus.setMuted(player, true);
        assertTrue(HardMuteStatus.isMuted(player));
        HardMuteStatus.setMuted(player, false);
        assertFalse(HardMuteStatus.isMuted(player));
    }
}
