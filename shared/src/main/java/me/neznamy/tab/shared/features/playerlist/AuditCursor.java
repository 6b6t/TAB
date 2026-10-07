package me.neznamy.tab.shared.features.playerlist;

/** Resumes at the next viewer/target pair, including pauses inside one viewer. */
public final class AuditCursor {
    private int viewer, target;
    public int viewer() { return viewer; }
    public int target() { return target; }
    public void nextTarget() { target++; }
    public void nextViewer() { viewer++; target = 0; }
    public void reset() { viewer = 0; target = 0; }
}
