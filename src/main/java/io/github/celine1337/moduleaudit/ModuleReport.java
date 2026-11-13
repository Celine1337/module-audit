package io.github.celine1337.moduleaudit;

import java.util.List;

public final class ModuleReport {
    public final List<String> moduleNames;
    public final List<Diagnostic> diagnostics;

    public ModuleReport(List<String> moduleNames, List<Diagnostic> diagnostics) {
        this.moduleNames = moduleNames;
        this.diagnostics = diagnostics;
    }

    public long errors() {
        return diagnostics.stream().filter(d -> d.severity == Diagnostic.Severity.ERROR).count();
    }

    public long warnings() {
        return diagnostics.stream().filter(d -> d.severity == Diagnostic.Severity.WARNING).count();
    }
}
