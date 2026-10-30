package dev.luma.tv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CapabilityPanel {
    enum Section {
        ACTIONS("Actions"), ASK("Ask"), SCENES("Scenes"), HEALTH("Health");
        final String label;
        Section(String label) { this.label = label; }
    }

    static List<Section> sections(boolean house) {
        ArrayList<Section> sections = new ArrayList<>();
        for (Section section : Section.values()) if (house || section != Section.SCENES) sections.add(section);
        return sections;
    }

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

    static String[] row(HealthSnapshot.Reading reading, String measured, boolean stale) {
        return new String[]{reading.metric.label + ". " + (stale ? "Stale" : status(reading.status)),
            (stale ? "Stale. " : "") + reading.value + "\n\n" + reading.explanation + "\n\nSource. " + reading.source + "\nMeasured. " + measured + "."};
    }

    static String status(HealthSnapshot.Status status) {
        return status.name().charAt(0) + status.name().substring(1).toLowerCase(Locale.ROOT);
    }
}
