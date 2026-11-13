package io.github.celine1337.moduleaudit;

public final class Diagnostic {
    public enum Severity { ERROR, WARNING, INFO }

    public final Severity severity;
    public final String code;
    public final String message;
    public final String location;

    public Diagnostic(Severity severity, String code, String message, String location) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.location = location;
    }

    @Override
    public String toString() {
        return severity + " [" + code + "] " + location + ": " + message;
    }
}
