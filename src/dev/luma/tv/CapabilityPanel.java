package dev.luma.tv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class CapabilityPanel {
    enum Section {
        ACTIONS("Actions"), ASK("Ask"), LAYOUTS("Layouts"), SCENES("Scenes"), HEALTH("Health"), MODE("Mode");
        final String label;
        Section(String label) { this.label = label; }
    }

    static List<Section> sections(boolean house, Profiles profile) {
        if (!profile.allows(Profiles.Content.SETTINGS)) return Collections.singletonList(Section.MODE);
        ArrayList<Section> sections = new ArrayList<>();
        for (Section section : Section.values()) if (house || section != Section.SCENES) sections.add(section);
        return sections;
    }

    /** What the TV calls a layout's parts. app and scene return null for a part the TV lacks. */
    interface Names {
        String app(String pkg);
        String widget(String id);
        String scene(String id);
        String wallpaper(int index);
        boolean house();
    }

    static String layout(HomePresets.Proposal proposal, Names names) {
        HomePresets.Layout layout = proposal.layout;
        ArrayList<String> widgets = new ArrayList<>(), apps = new ArrayList<>(), scenes = new ArrayList<>(), missing = new ArrayList<>();
        for (String id : layout.widgets) widgets.add(names.widget(id));
        for (String pkg : layout.apps) apps.add(names.app(pkg) == null ? pkg : names.app(pkg));
        for (String id : layout.scenes) scenes.add(names.scene(id) == null ? id : names.scene(id));
        for (HomePresets.Missing part : proposal.missing) {
            switch (part.part) {
                case APP: missing.add(part.id + " is not installed."); break;
                case WIDGET: missing.add(part.id.equals("house") ? "Smart Home needs House Control TV." : part.id.equals("inputs")
                    ? "Inputs needs a TV with HDMI inputs." : names.widget(part.id) + " is not in this version of Luma."); break;
                default: missing.add(names.house() ? "The " + part.id + " scene is unavailable in Luma's House Control data." : "The " + part.id + " scene needs House Control TV.");
            }
        }
        StringBuilder text = new StringBuilder("Widgets. ").append(widgets.isEmpty() ? "None" : String.join(", ", widgets))
            .append("\nApps. ").append(apps.isEmpty() ? "None" : String.join(", ", apps.subList(0, Math.min(6, apps.size()))))
            .append(apps.size() > 6 ? ", and " + (apps.size() - 6) + " more" : "");
        if (!scenes.isEmpty()) text.append("\nScenes. ").append(String.join(", ", scenes));
        text.append("\nWallpaper. ").append(names.wallpaper(layout.wallpaper));
        if (!missing.isEmpty()) text.append("\nUnavailable. ").append(String.join(" ", missing));
        return text.toString();
    }

    static String mode(Profiles.Mode mode, List<String> approved) {
        switch (mode) {
            case GUEST: return "Hides Up Next, Now Playing, your name and Settings. Keeps the apps on Home, Weather, Smart Home and Inputs.\n\nThe mode button on Home returns to Normal. It needs no PIN.";
            case CHILD: return "Shows Weather and only the apps approved for Child mode. Hides Settings, Smart Home, Inputs and personal media.\n\nApproved. "
                + (approved.isEmpty() ? "None yet, so Child Home shows no apps." : String.join(", ", approved)) + "\n\nThe mode button on Home returns to Normal. It needs no PIN.";
            default: return "Shows everything. Your Home, Up Next, Now Playing, Settings and every app.";
        }
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
