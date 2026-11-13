package io.github.celine1337.moduleaudit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        boolean json = false;
        List<Path> paths = new ArrayList<>();
        for (String a : args) {
            switch (a) {
                case "--json" -> json = true;
                case "-h", "--help" -> {
                    System.out.println("usage: module-audit [--json] PATH...\n\nAudits JPMS module descriptors of prebuilt jars and exploded modules.");
                    return;
                }
                default -> {
                    if (a.startsWith("-")) {
                        usage();
                    }
                    paths.add(Path.of(a));
                }
            }
        }
        if (paths.isEmpty()) {
            usage();
        }
        ModuleReport report = ModuleAudit.audit(paths);
        if (json) {
            System.out.println(toJson(report));
        } else {
            for (Diagnostic d : report.diagnostics) {
                System.out.println(d);
            }
            System.out.println(report.moduleNames.size() + " module(s), " + report.errors() + " error(s), " + report.warnings() + " warning(s)");
        }
        System.exit(report.errors() > 0 ? 1 : 0);
    }

    private static void usage() {
        System.err.println("usage: module-audit [--json] PATH...");
        System.exit(2);
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String toJson(ModuleReport r) {
        StringBuilder sb = new StringBuilder("{\"modules\":[");
        for (int i = 0; i < r.moduleNames.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(esc(r.moduleNames.get(i))).append('"');
        }
        sb.append("],\"diagnostics\":[");
        for (int i = 0; i < r.diagnostics.size(); i++) {
            Diagnostic d = r.diagnostics.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"severity\":\"").append(d.severity).append("\",\"code\":\"").append(d.code)
                    .append("\",\"message\":\"").append(esc(d.message)).append("\",\"location\":\"").append(esc(d.location)).append("\"}");
        }
        sb.append("],\"errors\":").append(r.errors()).append(",\"warnings\":").append(r.warnings()).append('}');
        return sb.toString();
    }
}
