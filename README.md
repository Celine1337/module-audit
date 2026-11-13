<p align="center">
  <img src="assets/banner.svg" width="830" alt="module-audit banner">
</p>
<p align="center">
  <a href="https://github.com/Celine1337/module-audit/releases"><img src="assets/nav-releases.svg" alt="Releases"></a>
  <a href="https://docs.oracle.com/javase/specs/jls/se17/html/jls-7.html#jls-7.7"><img src="assets/nav-spec.svg" alt="JPMS §7.7"></a>
  <a href="LICENSE"><img src="assets/nav-mit.svg" alt="MIT"></a>
</p>

<img src="assets/section-about.svg" width="830" alt="About">

**module-audit** is a static auditor for **JPMS module paths** — jars and exploded modules — that reports *every* structural problem in one pass, before `java` dies during boot-layer initialization with a single cryptic `ResolutionException`. It reads each `module-info.class`, lists the actual contents, and cross-checks the descriptor against reality: split packages, `requires` cycles, name shadowing, exported packages that no longer exist, qualified targets and `requires` that are not on the path, `provides` whose provider class is missing, and `uses` with no provider anywhere.

The unusual part: **it keeps auditing artifacts the JDK refuses to read.** `ModuleFinder` rejects an exploded module whose exported package was pruned (a classic shade/repackage bug) with `InvalidModuleDescriptorException` — and takes the whole scan down with it. module-audit catches that, falls back to parsing `module-info.class` directly, reports the JDK rejection as a finding (M009), and still checks everything else inside the broken artifact.

What it is **not**: a resolver. `jdeps --check` and `java --validate-modules` verify that dependencies *resolve*; module-audit is the structural lint around them — whole-path, all-findings, tolerant of broken artifacts, with checks resolution never performs (provider class presence, `uses`/`provides` drift, qualified-target presence, shadowing). Zero dependencies, JDK 17+.

<img src="assets/section-features.svg" width="830" alt="Features">

- **Whole-path audit** — pass any mix of modular jars, exploded modules, and directories of jars; every finding is reported, not just the first fatal one.
- **Broken-artifact fallback** — when `ModuleFinder` rejects a module, the raw descriptor is still parsed and audited; the rejection itself becomes a diagnostic.
- **Split packages & shadowing** — the same package in two modules, or the same module name in two path entries.
- **Requires graph** — cycle detection with the full cycle printed (`a -> b -> a`).
- **Descriptor vs. contents** — exports/opens of absent packages, `provides` without the provider `.class`, stale qualified targets.
- **Service drift** — `uses` with no `provides` and no `META-INF/services` provider on the path.
- **Cross-validated** — the test suite diffs the audit against the real JDK boot layer: `java -m` must accept the clean fixture and reject the cycle, split, and pruned ones.

<img src="assets/section-stack.svg" width="830" alt="Tech stack">

<img src="assets/section-install.svg" width="830" alt="Install">

Build from a checkout (JDK 17+, no build tool needed):

```bash
git clone https://github.com/Celine1337/module-audit.git
cd module-audit
./build.sh        # produces out/module-audit.jar
```

As a library, add `src/main/java` to your source path or jar it yourself; the API is `ModuleAudit.audit(List<Path>)`.

<img src="assets/section-usage.svg" width="830" alt="Usage">

CLI:

```bash
java -jar module-audit.jar lib/a.jar lib/b.jar modules/app
java -jar module-audit.jar --json lib/*.jar modules/app
```

```text
ERROR [M009] com.example.pa: module rejected by the JDK: java.lang.module.InvalidModuleDescriptorException: Package com.example.gone not found in module
ERROR [M003] com.example.pa: exports package 'com.example.gone' is not present in the module
WARNING [M004] com.example.pa: qualified exports target 'com.example.stub' is not on the module path
WARNING [M005] com.example.pa: requires module 'com.example.stub' is not on the module path
ERROR [M007] com.example.pa: provides provider 'com.example.pa.Impl' has no class file in the module
WARNING [M008] com.example.pa: uses service 'com.example.pa.NoProv' but no provider exists on the path
1 module(s), 3 error(s), 3 warning(s)
```

Exit codes: `0` clean (warnings allowed), `1` errors found, `2` bad usage.

Library:

```java
ModuleReport report = ModuleAudit.audit(List.of(Path.of("lib/a.jar"), Path.of("modules/app")));
for (Diagnostic d : report.diagnostics) System.out.println(d);
System.exit(report.errors() > 0 ? 1 : 0);
```

Rules:

| Code | Severity | Meaning |
| --- | --- | --- |
| M000 | error | path entry / jar / module not readable |
| M001 | error | module name appears in several path entries (shadowing) |
| M002 | error | split package across modules |
| M003 | error | `exports`/`opens` package not present in the module |
| M004 | warning | qualified `exports`/`opens` target not on the path |
| M005 | warning | `requires` module not on the path |
| M006 | error | `requires` cycle (full cycle printed) |
| M007 | error | `provides` provider has no class file |
| M008 | warning | `uses` service with no provider on the path |
| M009 | error | descriptor rejected by the JDK (audited via raw fallback) |

Run the tests (builds fixtures, then diffs against the real JDK boot layer):

```bash
./test.sh
```

The fixtures are honest about how hard these cases are to produce: the cycle is compiled against requires-free stubs of each other (javac rejects real cycles), the split package is compiled as two separate modules, and the pruned artifact is compiled whole and then stripped — exactly how a broken shade output looks in the wild.

Known boundaries, stated plainly:

- Checks are **structural**, not a full resolution pass: readability, service binding, and `requires transitive` re-export chains are not re-simulated.
- Automatic modules are discovered via `ModuleFinder`, but their implicit "exports everything" semantics is not simulated.
- `uses`/provider matching also counts `META-INF/services` files, so mixed classpath/module paths are handled; per-service matching semantics are out of scope.

<img src="assets/section-license.svg" width="830" alt="License">

MIT © 2026 Celine1337 — see [LICENSE](LICENSE).