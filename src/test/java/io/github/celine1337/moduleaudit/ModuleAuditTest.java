package io.github.celine1337.moduleaudit;

import java.nio.file.Path;
import java.util.List;

public final class ModuleAuditTest {
    private static int checks;

    public static void main(String[] args) {
        Path f = Path.of(args[0]);

        ModuleReport clean = ModuleAudit.audit(List.of(f.resolve("clean")));
        expect(clean.moduleNames.equals(List.of("com.example.clean")), "clean: module name");
        expect(clean.diagnostics.isEmpty(), "clean: no diagnostics, got " + clean.diagnostics);

        ModuleReport jar = ModuleAudit.audit(List.of(f.resolve("clean.jar")));
        expect(jar.moduleNames.equals(List.of("com.example.clean")), "jar: module name");
        expect(jar.diagnostics.isEmpty(), "jar: no diagnostics, got " + jar.diagnostics);

        ModuleReport cycle = ModuleAudit.audit(List.of(f.resolve("ca"), f.resolve("cb")));
        expect(has(cycle, "M006"), "cycle: M006 reported");
        expect(cycle.errors() == 1, "cycle: exactly one error, got " + cycle.errors());

        ModuleReport split = ModuleAudit.audit(List.of(f.resolve("sa"), f.resolve("sb")));
        expect(has(split, "M002"), "split: M002 reported");
        expect(!has(split, "M001"), "split: no shadowing false positive");

        ModuleReport shadow = ModuleAudit.audit(List.of(f.resolve("dup-a"), f.resolve("dup-b")));
        expect(has(shadow, "M001"), "shadow: M001 reported");

        ModuleReport pruned = ModuleAudit.audit(List.of(f.resolve("pa")));
        for (String code : new String[] {"M003", "M004", "M005", "M007", "M008", "M009"}) {
            expect(has(pruned, code), "pruned: " + code + " reported");
        }

        ModuleReport healed = ModuleAudit.audit(List.of(f.resolve("pa"), f.resolve("pstub")));
        expect(!has(healed, "M004"), "healed: qualified target resolved");
        expect(!has(healed, "M005"), "healed: requires resolved");
        expect(has(healed, "M003") && has(healed, "M007") && has(healed, "M008"), "healed: pruning still detected");

        System.out.println("ALL TESTS PASSED (" + checks + " checks)");
    }

    private static boolean has(ModuleReport r, String code) {
        return r.diagnostics.stream().anyMatch(d -> d.code.equals(code));
    }

    private static void expect(boolean ok, String what) {
        checks++;
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
    }
}