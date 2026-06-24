---
type: research
status: active
tags: [research, agent, database]
---

# Datahike-fork packaging — current state (2026-06-24)

Status note. The AUTHORITATIVE design + roadmap is
`docs/prds/agent-runtime/seon-as-artifact-design-2026-06-22.md` (C1–C20 couplings,
P0–P3). This note records where it actually stands + two items it doesn't track.

## TL;DR

- **P0 = DONE** (commit `d9a0505`, Jun 22): a self-contained `target/seon-wire-server-standalone.jar`
  (108 MB, built by `build.clj`'s `writer-uber` off the `:writer` basis) + a loud
  `--preflight` gate (`src/seon/embed/preflight.clj`, wired at `boot.clj` `-main`,
  flag at `wire.clj`). Verified live from the jar on JDK 22. The PRD's §6
  "UNVERIFIED" snapshot is STALE.
- **Consumer "run" story already better:** one `java --add-modules jdk.incubator.vector
  --enable-native-access=ALL-UNNAMED -XX:+UseG1GC -Xmx2g -jar … --preflight`. Dev
  still reads local source (`:writer` git-dep + `src-secondary` extra-path).
- **NOT done:** P1 (lib-ize the forks → kill submodule + shim + git-shas), P2/P3,
  AND two items outside the PRD: the **alias-sprawl dedup** + the **`:test`/`:dev`
  maven bug**.

## The two forks + shim (consumed via git, 5× duplicated)

- **datahike fork** `seantempesta/datahike@7ef2b5de` (submodule `reference-code/datahike`,
  branch `sync-upstream`). Our patches over upstream: proximum/secondary-index
  retain+restore+adapter fixes, query fixes (get-else, multi-group-join), a
  selective-Promise CLJS datahike.api, a cold-compile-java fix for git consumers.
- **proximum integration** lives in `reference-code/datahike/src-secondary/datahike/index/secondary/`
  (proximum.clj/stratum.clj/scriptum.clj); the HNSW backend is `org.replikativ/proximum 0.1.25`
  (maven). KEY: the fork's own `:paths` EXCLUDES `src-secondary`, so a git/local dep
  can never see it via normal resolution — Seon reaches it via a repo-relative
  `:extra-paths`. This is the structural reason a companion lib jar (P1) is needed.
- **konserve fork** `seantempesta/konserve@32e3c598` (header fix not yet on maven),
  via `:override-deps`. Plus `dev-resources/konserve-shim` — a fake `pom.properties`
  that exists ONLY because konserve is a non-jar git-dep (datahike's version-check
  reads nil otherwise). Jarring konserve deletes this shim entirely.

## Alias × datahike source (the sprawl)

| Alias | datahike | src-secondary | proximum | konserve | SIMD jvm-opts |
|---|---|---|---|---|---|
| `:dev` | **mvn 0.8.1671** ⚠ | — | — | base | — |
| `:test` | **mvn 0.8.1671** ⚠ BUG | — | — | base | — |
| `:writer` | fork git 7ef2b5de | yes | yes | fork git 32e3c598 | yes |
| `:server-wire-test` | fork git | yes | yes | fork git | yes |
| `:replica-probe-jvm` | fork git (replace) | — | — | fork git | — |
| `:replica-peer-jvm` | fork git (replace) | — | — | fork git | — |
| `:cljs` (pod) | fork git (override) | — | — (wire-only) | fork git | n/a |

The `{datahike 7ef2b5de + konserve 32e3c598}` pair is hand-copied across **5** aliases;
the SIMD jvm-opts block across **2**; the konserve-shim path across **4**.

**`:test`/`:dev` maven bug:** both pin upstream mvn `0.8.1671` (no fork query-fixes, no
proximum) → tests run against DIFFERENT code than production. This is the directive's
"maven upstream should appear nowhere" violation + a real correctness gap.

## Native/SIMD launch (cannot live in a jar)

JDK 22+ (proximum ArrayBitSet is class v66) + `--add-modules jdk.incubator.vector` +
`--enable-native-access=ALL-UNNAMED` + `-XX:+UseG1GC -Xmx2g`. These are `java` launcher
flags, NOT manifest entries — packaging must surface them via a launcher script or the
documented run command. `--preflight` turns any missing piece into a loud distinct exit.

## acme today

`bin/acme` is pure env-composition over `bin/seon`; `bin/seon` emits `clojure -M:writer …`
— acme builds the fork FROM SOURCE exactly like dev (needs the checkout, submodule, shim,
cold prep). "acme depends on the jar" = a one-line `bin/seon` switch emitting
`java … -jar seon-wire-server-standalone.jar …` (the jar already accepts the CLI args) —
NOT yet wired (PRD P3). `grep "java -jar" bin/seon` = empty.

## Recommendation (layered a+b, not c)

- Keep the **uberjar** (P0) as THE "run" artifact for consumers.
- **P1:** ship `seon-datahike-proximum` companion lib jar (the 3 secondary nses) + deploy
  the datahike + konserve forks as real **mvn versions** → every `:git/sha` collapses to
  `:mvn/version`, the **submodule + src-secondary extra-path + konserve-shim all delete**.
  BLOCKER: the konserve fork has "no pushable remote" — must pick a deploy target first.
- Reject `:local/root` as the CONSUMER path (forces checkout + prep); keep it as a DEV
  convenience (`:dev-fork {:override-deps {datahike {:local/root "reference-code/datahike"}}}`
  for edit-and-run without SHA bumps).
- **Dedup:** extract shared `:fork-deps` (the pair) + `:simd` (jvm-opts + shim) aliases;
  every consumer composes them. (`:replica-*` use `:replace-deps` for probe isolation —
  may stay self-contained by design.)
- **Fix `:test`/`:dev`:** point them at the fork (interim via `:fork-deps`; final via the
  mvn version) so upstream maven `0.8.1671` is deleted from the file.

## Migration sequence (each step leaves `clj -M:writer` runnable)

1. **CHEAP/INDEPENDENT NOW:** fix `:test`+`:dev` → fork; extract `:fork-deps` + `:simd`
   shared aliases; rewrite `:writer`/`:server-wire-test`/`:cljs` to compose them. Kills the
   5× sprawl + the test-correctness bug. No deploy target needed.
2. **DECISION-GATED (P1):** pick a fork deploy target (private mvn repo simplest; resolves
   konserve "no remote"); deploy konserve → flip override to mvn, delete shim everywhere
   (incl. build.clj copy-dir); rebuild uberjar + `--preflight`.
3. **P1 cont:** publish `seon-datahike-proximum` + deploy datahike fork mvn; replace all
   `:git/sha` with `:mvn/version`; DELETE the submodule + `src-secondary` extra-paths; add
   the real immutable git tag (the deps.edn `seon-pin-2026-06-22` comment is aspirational —
   no such tag exists locally).
4. **P3 prereq:** `SEON_WIRE_JAR` env switch in `bin/seon` → emits `java … -jar …` for the
   wire-server (acme then genuinely consumes the artifact); falls back to `clojure -M:writer`
   for dev.
5. **P3:** pod source jar (independent — pod is wire-only, no datahike).

## Decisions the owner must make

- **Deploy target for the forks** (Clojars / private mvn repo / GitHub Packages) — gates
  all of P1. The konserve fork's "no pushable remote" must be resolved here.
- **Sequencing:** cheap wins (step 1) now vs. full P1 push vs. back to the render keystone.
