package io.github.celine1337.moduleaudit;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.FindException;
import java.lang.module.InvalidModuleDescriptorException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ModuleAudit {
    private static final String PREFIX = "META-INF/services/";

    private ModuleAudit() {
    }

    private static final class FoundModule {
        final String name;
        final ModuleDescriptor descriptor;
        final Set<String> resources;

        FoundModule(String name, ModuleDescriptor descriptor, Set<String> resources) {
            this.name = name;
            this.descriptor = descriptor;
            this.resources = resources;
        }
    }

    public static ModuleReport audit(List<Path> paths) {
        List<Diagnostic> diags = new ArrayList<>();
        List<FoundModule> modules = new ArrayList<>();
        Map<String, List<String>> nameSources = new LinkedHashMap<>();
        for (Path p : paths) {
            List<FoundModule> here = new ArrayList<>();
            discover(p, here, diags);
            for (FoundModule m : here) {
                nameSources.computeIfAbsent(m.name, k -> new ArrayList<>()).add(p.toString());
                modules.add(m);
            }
        }
        for (Map.Entry<String, List<String>> e : nameSources.entrySet()) {
            if (e.getValue().size() > 1) {
                diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M001",
                        "module '" + e.getKey() + "' appears in " + e.getValue().size() + " entries (shadowing): " + e.getValue(), e.getKey()));
            }
        }
        Set<String> allNames = nameSources.keySet();

        Map<String, Set<String>> packagesByModule = new HashMap<>();
        Map<String, Set<String>> resourcesByModule = new HashMap<>();
        Map<String, ModuleDescriptor> descByName = new LinkedHashMap<>();
        Set<String> providesServices = new HashSet<>();
        Map<String, Set<String>> requiresGraph = new HashMap<>();

        for (FoundModule m : modules) {
            descByName.put(m.name, m.descriptor);
            resourcesByModule.put(m.name, m.resources);
            Set<String> pkgs = m.resources.stream()
                    .filter(r -> r.endsWith(".class") && r.contains("/") && !r.startsWith("META-INF/"))
                    .map(r -> r.substring(0, r.lastIndexOf('/')).replace('/', '.'))
                    .collect(Collectors.toSet());
            packagesByModule.put(m.name, pkgs);
            for (ModuleDescriptor.Provides p : m.descriptor.provides()) {
                providesServices.add(p.service());
            }
            Set<String> req = new HashSet<>();
            for (ModuleDescriptor.Requires r : m.descriptor.requires()) {
                if (allNames.contains(r.name())) {
                    req.add(r.name());
                }
            }
            requiresGraph.put(m.name, req);
        }

        Map<String, Set<String>> pkgOwners = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : packagesByModule.entrySet()) {
            for (String pkg : e.getValue()) {
                pkgOwners.computeIfAbsent(pkg, k -> new HashSet<>()).add(e.getKey());
            }
        }
        for (Map.Entry<String, Set<String>> e : pkgOwners.entrySet()) {
            if (e.getValue().size() > 1) {
                diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M002",
                        "split package '" + e.getKey() + "' across modules " + e.getValue(), e.getKey()));
            }
        }

        Set<String> metaServices = scanMetaInfServices(paths);

        for (Map.Entry<String, ModuleDescriptor> e : descByName.entrySet()) {
            String name = e.getKey();
            ModuleDescriptor d = e.getValue();
            Set<String> pkgs = packagesByModule.getOrDefault(name, Set.of());
            Set<String> resources = resourcesByModule.getOrDefault(name, Set.of());
            for (ModuleDescriptor.Exports ex : d.exports()) {
                if (!pkgs.contains(ex.source())) {
                    diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M003", "exports package '" + ex.source() + "' is not present in the module", name));
                }
                if (ex.isQualified()) {
                    for (String t : ex.targets()) {
                        if (!allNames.contains(t)) {
                            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "M004", "qualified exports target '" + t + "' is not on the module path", name));
                        }
                    }
                }
            }
            for (ModuleDescriptor.Opens op : d.opens()) {
                if (!pkgs.contains(op.source())) {
                    diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M003", "opens package '" + op.source() + "' is not present in the module", name));
                }
                if (op.isQualified()) {
                    for (String t : op.targets()) {
                        if (!allNames.contains(t)) {
                            diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "M004", "qualified opens target '" + t + "' is not on the module path", name));
                        }
                    }
                }
            }
            for (ModuleDescriptor.Requires r : d.requires()) {
                if (!allNames.contains(r.name()) && !r.name().startsWith("java.")) {
                    diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "M005", "requires module '" + r.name() + "' is not on the module path", name));
                }
            }
            for (ModuleDescriptor.Provides p : d.provides()) {
                for (String prov : p.providers()) {
                    if (!resources.contains(prov.replace('.', '/') + ".class")) {
                        diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M007", "provides provider '" + prov + "' has no class file in the module", name));
                    }
                }
            }
            for (String u : d.uses()) {
                if (!providesServices.contains(u) && !metaServices.contains(u)) {
                    diags.add(new Diagnostic(Diagnostic.Severity.WARNING, "M008", "uses service '" + u + "' but no provider exists on the path", name));
                }
            }
        }

        detectCycles(requiresGraph, diags);
        return new ModuleReport(new ArrayList<>(allNames), diags);
    }

    private static void discover(Path p, List<FoundModule> out, List<Diagnostic> diags) {
        try {
            for (ModuleReference r : ModuleFinder.of(p).findAll()) {
                try (ModuleReader reader = r.open()) {
                    Set<String> res = reader.list().collect(Collectors.toSet());
                    out.add(new FoundModule(r.descriptor().name(), r.descriptor(), res));
                } catch (IOException e) {
                    diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M000", "cannot read module: " + e.getMessage(), r.descriptor().name()));
                }
            }
            return;
        } catch (FindException | IllegalArgumentException e) {
            // the JDK refuses this entry; fall back to reading module-info.class raw
            fallback(p, out, diags, rootMessage(e));
        }
    }

    private static void fallback(Path p, List<FoundModule> out, List<Diagnostic> diags, String rejection) {
        if (Files.isDirectory(p)) {
            if (Files.isRegularFile(p.resolve("module-info.class"))) {
                rawExploded(p, out, diags, rejection);
                return;
            }
            List<Path> jars;
            try (Stream<Path> list = Files.list(p)) {
                jars = list.filter(f -> f.toString().endsWith(".jar") && Files.isRegularFile(f))
                        .sorted().collect(Collectors.toList());
            } catch (IOException e) {
                diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M000", "cannot list directory: " + e.getMessage(), p.toString()));
                return;
            }
            if (jars.isEmpty()) {
                diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M000", "module path not readable: " + rejection, p.toString()));
                return;
            }
            for (Path j : jars) {
                discover(j, out, diags);
            }
        } else if (p.toString().endsWith(".jar") && Files.isRegularFile(p)) {
            rawJar(p, out, diags, rejection);
        } else {
            diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M000", "module path not readable: " + p, p.toString()));
        }
    }

    private static void rawExploded(Path dir, List<FoundModule> out, List<Diagnostic> diags, String rejection) {
        ModuleDescriptor d;
        try (InputStream in = Files.newInputStream(dir.resolve("module-info.class"))) {
            d = ModuleDescriptor.read(in);
        } catch (IOException | InvalidModuleDescriptorException e) {
            diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M009", "module-info.class is not a valid descriptor: " + e.getMessage(), dir.toString()));
            return;
        }
        Set<String> res = new HashSet<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .forEach(f -> res.add(dir.relativize(f).toString().replace(File.separatorChar, '/')));
        } catch (IOException e) {
            diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M000", "cannot list module files: " + e.getMessage(), dir.toString()));
            return;
        }
        if (rejection != null) {
            diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M009", "module rejected by the JDK: " + rejection, d.name()));
        }
        out.add(new FoundModule(d.name(), d, res));
    }

    private static void rawJar(Path jar, List<FoundModule> out, List<Diagnostic> diags, String rejection) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry mi = jf.getJarEntry("module-info.class");
            if (mi == null) {
                diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M000", "jar has no module-info.class: " + jar, jar.toString()));
                return;
            }
            ModuleDescriptor d;
            try (InputStream in = jf.getInputStream(mi)) {
                d = ModuleDescriptor.read(in);
            } catch (InvalidModuleDescriptorException e) {
                diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M009", "module-info.class is not a valid descriptor: " + e.getMessage(), jar.toString()));
                return;
            }
            Set<String> res = new HashSet<>();
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                res.add(en.nextElement().getName());
            }
            if (rejection != null) {
                diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M009", "module rejected by the JDK: " + rejection, d.name()));
            }
            out.add(new FoundModule(d.name(), d, res));
        } catch (IOException e) {
            diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M000", "cannot read jar: " + e.getMessage(), jar.toString()));
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.toString();
    }

    private static void detectCycles(Map<String, Set<String>> graph, List<Diagnostic> diags) {
        Set<String> done = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        List<String> stack = new ArrayList<>();
        for (String node : graph.keySet()) {
            dfs(node, graph, done, onStack, stack, diags);
        }
    }

    private static void dfs(String node, Map<String, Set<String>> graph, Set<String> done, Set<String> onStack, List<String> stack, List<Diagnostic> diags) {
        if (done.contains(node)) {
            return;
        }
        if (onStack.contains(node)) {
            int i = stack.indexOf(node);
            List<String> cycle = new ArrayList<>(stack.subList(i, stack.size()));
            cycle.add(node);
            diags.add(new Diagnostic(Diagnostic.Severity.ERROR, "M006", "requires cycle: " + String.join(" -> ", cycle), node));
            return;
        }
        onStack.add(node);
        stack.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            dfs(next, graph, done, onStack, stack, diags);
        }
        stack.remove(stack.size() - 1);
        onStack.remove(node);
        done.add(node);
    }

    private static Set<String> scanMetaInfServices(List<Path> paths) {
        Set<String> out = new HashSet<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                Path svc = p.resolve("META-INF/services");
                if (Files.isDirectory(svc)) {
                    try (Stream<Path> list = Files.list(svc)) {
                        list.forEach(f -> out.add(f.getFileName().toString()));
                    } catch (IOException ignored) {
                        // unreadable services dir
                    }
                }
            } else if (p.toString().endsWith(".jar") && Files.isRegularFile(p)) {
                try (JarFile jf = new JarFile(p.toFile())) {
                    Enumeration<JarEntry> en = jf.entries();
                    while (en.hasMoreElements()) {
                        String n = en.nextElement().getName();
                        if (n.startsWith(PREFIX) && n.length() > PREFIX.length() && n.indexOf('/', PREFIX.length()) < 0) {
                            out.add(n.substring(PREFIX.length()));
                        }
                    }
                } catch (IOException ignored) {
                    // unreadable jar
                }
            }
        }
        return out;
    }
}
