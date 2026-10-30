package dev.luma.tv;

/** Converts optional speech input into the same data as text requests. */
final class VoiceRequests {
    static final float MIN_CONFIDENCE = 0.8f;
    /** ON_DEVICE must come from an adapter that verified a local recognizer, not an offline preference. */
    enum Availability { ON_DEVICE, UNAVAILABLE, NETWORK_ONLY, PERMISSION_DENIED }
    enum Status { READY, REJECTED, CORRECTION_REQUIRED, UNAVAILABLE, CANCELLED }

    static final class Result {
        final Status status;
        final String transcript;
        final String message;
        final Commands.Result interpretation;
        private Result(Status status, String transcript, String message, Commands.Result interpretation) {
            this.status = status;
            this.transcript = transcript;
            this.message = message;
            this.interpretation = interpretation;
        }
    }

    static Result interpret(Availability availability, String transcript, Float confidence, Commands.Registry registry) {
        String text = transcript == null ? "" : transcript;
        if (availability != Availability.ON_DEVICE)
            return new Result(Status.UNAVAILABLE, text, "Local voice recognition is unavailable. Use text or the remote.", null);
        if (text.trim().isEmpty() || confidence == null || Float.isNaN(confidence)
                || confidence < MIN_CONFIDENCE || confidence > 1f)
            return new Result(Status.CORRECTION_REQUIRED, text, "Check or correct the request before continuing.", null);
        return resolve(text, registry);
    }

    static Result correct(String request, Commands.Registry registry) {
        return resolve(request == null ? "" : request, registry);
    }

    static Result cancel() {
        return new Result(Status.CANCELLED, "", "Voice request cancelled.", null);
    }

    private static Result resolve(String text, Commands.Registry registry) {
        Commands.Result interpretation = Commands.interpret(text, registry);
        if (interpretation.status == Commands.Status.AMBIGUOUS)
            return new Result(Status.CORRECTION_REQUIRED, text, interpretation.message, null);
        Status status = interpretation.status == Commands.Status.READY ? Status.READY : Status.REJECTED;
        return new Result(status, text, interpretation.message, interpretation);
    }
}
