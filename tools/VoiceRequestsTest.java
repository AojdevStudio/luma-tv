package dev.luma.tv;

public final class VoiceRequestsTest {
    static int failures;
    static void expect(boolean ok, String message) {
        if (!ok) failures++;
        System.out.println((ok ? "ok    " : "FAIL  ") + message);
    }
    static void noCommand(VoiceRequests.Result result, VoiceRequests.Status status, String message) {
        expect(result.status == status && result.interpretation == null && !result.message.isEmpty(), message);
    }
    public static void main(String[] args) {
        Commands.Registry registry = CommandsTest.registry();
        VoiceRequests.Result accepted = VoiceRequests.interpret(VoiceRequests.Availability.ON_DEVICE,
            "open Plex", 0.95f, registry);
        expect(accepted.status == VoiceRequests.Status.READY && accepted.interpretation != null
            && accepted.interpretation.status == Commands.Status.READY
            && accepted.interpretation.command.verb == Commands.Verb.LAUNCH_APP
            && "com.plexapp.android".equals(accepted.interpretation.command.target.id),
            "confident local speech returns the shared typed Plex command");
        noCommand(VoiceRequests.interpret(VoiceRequests.Availability.ON_DEVICE, "open Plex", null, registry),
            VoiceRequests.Status.CORRECTION_REQUIRED, "missing confidence requires correction");
        noCommand(VoiceRequests.interpret(VoiceRequests.Availability.ON_DEVICE, "open Plex", 0.79f, registry),
            VoiceRequests.Status.CORRECTION_REQUIRED, "low confidence requires correction");
        expect("open Plex".equals(VoiceRequests.interpret(VoiceRequests.Availability.ON_DEVICE,
            "open Plex", 0.79f, registry).transcript), "low confidence preserves the transcript for correction");
        expect(VoiceRequests.interpret(VoiceRequests.Availability.ON_DEVICE, "open Plex", 0.8f, registry).status
            == VoiceRequests.Status.READY, "the confidence threshold accepts its lower bound");
        for (float confidence : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1f, 1.01f}) {
            noCommand(VoiceRequests.interpret(VoiceRequests.Availability.ON_DEVICE, "open Plex", confidence, registry),
                VoiceRequests.Status.CORRECTION_REQUIRED, "invalid confidence requires correction " + confidence);
        }
        noCommand(VoiceRequests.interpret(VoiceRequests.Availability.ON_DEVICE, "", 1f, registry),
            VoiceRequests.Status.CORRECTION_REQUIRED, "empty recognition requires correction");
        for (VoiceRequests.Availability availability : new VoiceRequests.Availability[]{
                VoiceRequests.Availability.UNAVAILABLE, VoiceRequests.Availability.NETWORK_ONLY,
                VoiceRequests.Availability.PERMISSION_DENIED}) {
            noCommand(VoiceRequests.interpret(availability, "open Plex", 1f, registry),
                VoiceRequests.Status.UNAVAILABLE, availability + " leaves voice unavailable");
        }
        VoiceRequests.Result rejected = VoiceRequests.interpret(VoiceRequests.Availability.ON_DEVICE,
            "open Plex; reboot", 1f, registry);
        expect(rejected.status == VoiceRequests.Status.REJECTED && rejected.interpretation != null
            && rejected.interpretation.status == Commands.Status.UNSAFE
            && rejected.interpretation.command == null, "voice preserves the shared unsafe rejection");
        VoiceRequests.Result corrected = VoiceRequests.correct("show Weather", registry);
        expect(corrected.status == VoiceRequests.Status.READY && corrected.interpretation != null
            && corrected.interpretation.command.verb == Commands.Verb.SHOW_WIDGET
            && "weather".equals(corrected.interpretation.command.target.id),
            "corrected text returns the shared widget command");
        VoiceRequests.Result invalidCorrection = VoiceRequests.correct("open Unknown Player", registry);
        expect(invalidCorrection.status == VoiceRequests.Status.REJECTED && invalidCorrection.interpretation != null
            && invalidCorrection.interpretation.status == Commands.Status.UNINSTALLED
            && invalidCorrection.interpretation.command == null, "correction still checks the app registry");
        Commands.Registry ambiguous = new Commands.Registry(java.util.Arrays.asList(
            CommandsTest.target(Commands.Kind.SCENE, "one", "Evening", true),
            CommandsTest.target(Commands.Kind.SCENE, "two", "Evening", true)));
        noCommand(VoiceRequests.interpret(VoiceRequests.Availability.ON_DEVICE, "start Evening", 1f, ambiguous),
            VoiceRequests.Status.CORRECTION_REQUIRED, "ambiguous speech requires correction");
        noCommand(VoiceRequests.cancel(), VoiceRequests.Status.CANCELLED, "cancellation returns no interpretation");
        expect(Commands.interpret("open Plex", registry).status == Commands.Status.READY,
            "text remains usable when voice is unavailable or cancelled");
        System.out.println(failures == 0 ? "\nAll passed." : "\n" + failures + " failed.");
        System.exit(failures == 0 ? 0 : 1);
    }
}
