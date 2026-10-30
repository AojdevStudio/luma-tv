package dev.luma.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The rules behind Settings, then Capabilities: which sections it shows and when a Health check may land. No Android
 *  classes, so tools/CapabilityPanelTest.java checks it on a computer. MainActivity.CapabilityCenter draws it. */
final class CapabilityPanel {
    enum Section {
        ACTIONS("Actions"), ASK("Ask"), SCENES("Scenes"), HEALTH("Health");
        final String label;
        Section(String label) { this.label = label; }
    }

    /** Scenes come only from House Control, so its section is absent without it. */
    static List<Section> sections(boolean house) {
        ArrayList<Section> sections = new ArrayList<>();
        for (Section section : Section.values()) if (house || section != Section.SCENES) sections.add(section);
        return sections;
    }

    /** The Health report on screen. A refresh marks it stale at once. Only the newest refresh may replace it, and
     *  nothing lands after the panel closes, so a slow or late check never shows as current. */
    static final class Health {
        private int requested;
        private boolean closed;
        private HealthSnapshot shown;
        private boolean stale;
        int refresh() { stale = true; return ++requested; }
        boolean accept(int request, HealthSnapshot snapshot) {
            if (closed || request != requested) return false;
            shown = snapshot;
            stale = false;
            return true;
        }
        void close() { closed = true; }
        HealthSnapshot shown() { return shown; }
        boolean stale() { return stale; }
    }

    /** One Health line pair: the metric, status and value, then the explanation, source and measurement time. */
    static String[] row(HealthSnapshot.Reading reading, String measured, boolean stale) {
        String status = reading.status.name().charAt(0) + reading.status.name().substring(1).toLowerCase(Locale.ROOT);
        return new String[]{reading.metric.label + ". " + status + ". " + reading.value,
            (stale ? "Stale. " : "") + reading.explanation + " " + reading.source + ", measured " + measured + "."};
    }
}
