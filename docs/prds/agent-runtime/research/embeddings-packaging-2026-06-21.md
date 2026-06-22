---
type: research
status: active
tags: [research, agent, database]
---

# Embeddings packaging for third-party stand-up

How to hand a third party (under their own branding/packaging layer) a
reliable, easy way to run Seon's embeddings feature — one easy thing to test
THIS WEEK, then iterate.

## TL;DR

- The embeddings engine runs only on the JVM **wire-server** (`:writer` alias).
  Everything that makes it hard to "just pull from GitHub" is **classpath
  assembly**, not code: a git submodule (`src-secondary`), a `:local`/repo-path
  shim (`konserve-shim`), a cold `prep` step, and SHA-pinned forks reachable only
  from mutable branch tips.
- **Build a wire-server uberjar from the `:writer` alias** (`b/create-basis
  {:aliases [:writer]}` + `b/uber`). This single move bakes the git-dep forks,
  the `src-secondary` Proximum source, the `konserve-shim` `META-INF`, and all
  maven deps (proximum, google-genai) into ONE runnable jar. It **simultaneously
  eliminates** the submodule, the prep step, the shim hack, AND the
  SHA-GC durability risk — because nothing is resolved at the consumer's machine
  anymore; it's all compiled in. The only thing the third party supplies is
  Java 22+, the JVM vector flags, `SEON_EMBED=1`, and `GEMINI_API_KEY`.
- This is the recommended #1 because it is the LEAST work for the third party
  (download a jar, run `java …`) AND it is robust (reproducible, no live GitHub
  fetch). The "fix the deps.edn" options (A/B/C below) are real long-term
  cleanups but each only removes ONE friction point and still leaves the third
  party assembling a classpath from mutable SHAs.

## Ranked options

| # | Option | 3rd-party effort to test | Robustness | What it removes | When to pick |
|---|--------|--------------------------|------------|-----------------|--------------|
| 1 | **Wire-server uberjar** (`:writer` basis → `b/uber`) | Lowest — download jar, `java -jar` with flags + 2 env vars | High — no live fetch, reproducible, SHA-GC-proof | Submodule, prep, shim, SHA-GC, Java-dep resolution all at once | **Test this week.** Recommended. |
| 2 | **Tagged release of all 4 forks + fold `src-secondary` into the datahike fork's `:paths`** (still a deps.edn consumer) | Medium — copy a `:writer` alias, run `prep` | Medium-High — tags immutable; no submodule | Submodule + SHA-GC (not prep, not shim unless also tagged konserve) | Iteration target after #1 proves the feature |
| 3 | **datahike-proximum companion artifact** (thin repo/jar that ships datahike + src-secondary + proximum wiring) | Medium — add one mvn/git dep | Medium-High | Submodule + the "secondary not on default :paths" problem | If you want a clean public datahike-with-vectors dep |
| 4 | **Keep submodule, make init bulletproof** (preflight script + `prep` wrapper) | High — clone with `--recurse-submodules`, run prep, set env | Low-Medium — still SHA-GC-exposed | Nothing structural; only the silent-failure modes | Stopgap only; do not ship to a 3rd party |
| 5 | **Clojars/Maven deploy of the datahike fork as a versioned artifact** | Medium | High | SHA-GC + prep (jar ships `target/classes`) | Long-term, if you want datahike-fork reuse beyond Seon |

## #1 in detail — the wire-server uberjar (recommended, opinionated)

### Why it dissolves every problem at once

Each established friction point is a *classpath-assembly* problem that
`tools.build` resolves at YOUR machine, once, at build time:

- **Submodule / `src-secondary` coupling.** The `:writer` alias adds
  `reference-code/datahike/src-secondary` as a repo-local `:extra-paths`
  (`deps.edn:167-168`). `b/create-basis {:aliases [:writer]}` includes that path;
  `b/compile-clj`/`b/uber` compile those three namespaces
  (`datahike/index/secondary/proximum.clj`, `…/stratum.clj`, `…/scriptum.clj`)
  straight into `target/classes` → the jar. The consumer never sees a submodule.
- **`prep-lib`.** The datahike fork declares
  `:deps/prep-lib {:ensure "target/classes" :alias :build :fn compile-java}`
  (`reference-code/datahike/deps.edn:27-29`). `tools.build` runs the prep step
  for git/local deps as part of basis creation/compile; the compiled
  `DatahikeGenerated.java` + Java API end up in the uberjar. No consumer `prep`.
- **`konserve-shim`.** It is just a `META-INF/maven/org.replikativ/konserve/pom.properties`
  on the classpath (`dev-resources/konserve-shim/META-INF/maven/org.replikativ/konserve/pom.properties`,
  `version=0.9.347`). The `:writer` alias puts it on `:extra-paths`
  (`deps.edn:167`); `b/uber`/`b/copy-dir` carries that `META-INF` into the jar.
  At runtime `datahike.tools/get-version` reads it via
  `io/resource "META-INF/maven/.../pom.properties"` (`tools.cljc:117-126`) — works
  identically from inside a jar. Shim travels with the artifact; no repo path.
- **SHA-GC durability risk.** The forks are referenced by `:git/sha` on mutable
  branch tips; a force-push GCs them and breaks a `:git/url` consumer. An uberjar
  has already resolved + compiled those SHAs — the consumer pulls a jar, not a
  SHA. The risk is retired for the consumer entirely (it remains a risk for
  *rebuilding* the jar, which is YOUR concern, mitigated by tagging — see below).

### What stays the consumer's responsibility (and must fail LOUD)

The uberjar cannot bake in: Java 22+, the JVM module flags, and the two env
vars. These are exactly today's silent-failure modes:

- `--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED`
  (`deps.edn:164-165`) — Proximum SIMD kernels need them; missing flags fail at
  index time, not boot.
- `SEON_EMBED` master switch — `embed-feature-enabled?` is
  `(some? (System/getenv "SEON_EMBED"))` (`embed.clj:163`). Unset ⇒ inert.
- `GEMINI_API_KEY` — `gemini-client` is lazy and returns `nil` when unset
  (`embed.clj:611-619`); the write path and backfill silently no-op
  (`embed.clj:669-671`, `:837`, `:919`).

**Mandatory preflight (E):** ship a `--preflight` flag (or a tiny `seon.embed.preflight`
ns) the third party runs once after deploy. It must, and LOUDLY fail if not:
(1) assert Java ≥ 22 and `jdk.incubator.vector` present; (2) assert `SEON_EMBED`
set; (3) assert `GEMINI_API_KEY` non-blank AND do one real `embed-text` round
trip, asserting a 1536-length vector comes back; (4) `install!` + a one-row KNN
self-test returning top-1 == the seeded row. This converts the four silent
"degrade to no embeddings" modes into one explicit pass/fail. Per the
reactive-context norm this can also be a derived health section, but for a 3rd
party a `java -jar … --preflight` that exits non-zero is the right loud gate.

### Concrete steps Sean changes / 3rd party runs

The repo already has a `build.clj` and `:build` alias, but the current `uber`
target builds the **JVM-track main app** off the DEFAULT basis (`build.clj:7`
`(b/create-basis {:project "deps.edn"})`, `:main 'seon.core`) — it does NOT
include the `:writer` alias, so it has no `src-secondary`, no shim, no proximum.
A new wire-server target is needed (do NOT fork the existing one's `:main`).

1. **Add a `writer-uber` fn to `build.clj`** that builds the basis with the
   `:writer` alias so the forks + extra-paths come in:

   ```clojure
   (def writer-basis (b/create-basis {:project "deps.edn" :aliases [:writer]}))
   (def writer-uber-file "target/seon-wire-server-standalone.jar")

   (defn writer-uber [_]
     (b/delete {:path "target/classes"})
     ;; copy src + the writer extra-paths (src-secondary + konserve-shim META-INF)
     (b/copy-dir {:src-dirs ["src" "resources"
                             "reference-code/datahike/src-secondary"
                             "dev-resources/konserve-shim"]
                  :target-dir "target/classes"})
     (b/compile-clj {:basis writer-basis
                     :src-dirs ["src" "reference-code/datahike/src-secondary"]
                     :class-dir "target/classes"
                     ;; AOT the secondary impl + boot so :proximum registers
                     :ns-compile '[datahike.index.secondary.proximum
                                   seon.embed seon.server.boot]})
     (b/uber {:class-dir "target/classes"
              :uber-file writer-uber-file
              :basis writer-basis
              :main 'seon.server.boot}))   ; the wire-server entry, deps.edn:180
   ```

   Verify (do not assume): that `b/create-basis` with `:aliases [:writer]` runs
   the datahike fork's `prep-lib` (it should, since `prep` is part of git-dep
   basis prep) — if `target/classes` for datahike is empty, run
   `clojure -X:deps prep` once before the build as a belt-and-suspenders step.
   Also confirm the shim's `META-INF` lands in the jar and is NOT shadowed by a
   real konserve jar (there is none — konserve is the git-dep fork with no pom,
   which is exactly why the shim exists).

2. **Tag the four fork SHAs** (`seantempesta/datahike@5f62d57f`,
   `konserve@32e3c598`, `superv.async@3e6ed755`, `partial-cps@c0d941d4`) with
   immutable git tags so YOUR rebuild is reproducible even if branches move.
   This protects the build, not the consumer (the consumer has the jar).

3. **Hand the third party:** the `seon-wire-server-standalone.jar` + a 6-line
   run note:

   ```bash
   export SEON_EMBED=1
   export GEMINI_API_KEY=…           # their key
   java --add-modules jdk.incubator.vector \
        --enable-native-access=ALL-UNNAMED \
        -XX:+UseG1GC -Xmx2g \
        -jar seon-wire-server-standalone.jar --preflight   # loud check, then serve
   ```

   They supply Java 22+, the key, a store dir (`SEON_CLUSTER_DIR`). The pod
   (their read-only CLJS client) is unchanged — it only needs `SEON_EMBED` set
   to send query text over the UDS.

This is the "easy thing to test this week": one jar, one command. Iterate on the
deps.edn cleanups (A/B/C) afterward if they want a source-level integration.

---

## A. Eliminating the submodule / `src-secondary` coupling

**Root cause (verified).** The datahike fork's own deps.edn is
`:paths ["src" "target/classes" "resources"]` — `src-secondary` is NOT in it
(`reference-code/datahike/deps.edn:25`). The Proximum implementation lives at
`reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj`
(plus `stratum.clj`, `scriptum.clj`). It reaches the classpath ONLY because the
`:writer` alias adds the repo path as `:extra-paths`
(`deps.edn:168`), backed by the submodule pinned to the same SHA. The fork's own
`:test` alias also lists `src-secondary` in `:extra-paths`
(`reference-code/datahike/deps.edn:69`) — confirming it is deliberately a
TEST/OPTIONAL path, segregated because `proximum`/`scriptum`/`stratum` are
optional deps you don't want forced on every datahike consumer.

The loading model is a **runtime registry**, not a static link:
`datahike.index.secondary/register-index-type!` (`secondary.cljc:291`) — "Anyone
can register their own index type." Requiring
`datahike.index.secondary.proximum` registers `:proximum`. Seon does this in
`seon.embed` (`embed.clj:82`) and `seon.server.boot` (`boot.clj:42`). datahike
core (main `src/`) never references proximum — so moving the source does not
create a hard core→proximum dependency; it only changes WHERE the optional impl
ships.

Options:

- **Move `src-secondary` into the fork's `:paths`.** Feasible, and it makes
  `:git/sha` alone expose the namespaces — BUT it forces the proximum/scriptum/
  stratum *Java/maven deps* onto EVERY datahike consumer (the namespaces `(:require
  [proximum.core])` etc., so they won't even compile/AOT without those deps on
  the classpath). That is why they're segregated. Acceptable for a Seon-specific
  fork; wrong for upstream.
- **Add an alias in the fork's deps.edn that adds `src-secondary` + the
  proximum dep, consumed by the writer.** NOT viable for a git dep:
  **tools.deps git/local deps use only their default `:paths`; a consumer cannot
  activate an alias inside a dependency's deps.edn.** (Established tools.deps
  behavior — aliases are project-local, not transitive; confirm against the
  official `clojure.org/reference/deps_edn` guide.) So an in-fork alias would do
  nothing for a `:git/sha` consumer. This rules out the "clean alias" approach
  and is precisely why today's setup reaches around it with a repo-path
  `:extra-paths` + submodule.
- **Thin `datahike-proximum` companion artifact** (option 3). A small repo/jar
  whose `:paths` IS the three secondary namespaces and whose `:deps` are
  datahike-fork + proximum. A consumer adds one dep and gets vectors. Cleanest
  source-level story; more release machinery than the uberjar.
- **Keep submodule, bulletproof init** (option 4). Only addresses the
  silent-failure modes, not the structural coupling. Stopgap.

## B. Killing the `konserve-shim` hack

**Why nil.** `datahike.tools/get-version` reads
`META-INF/maven/<group>/<artifact>/pom.properties` off the classpath and returns
the `version` property (`tools.cljc:116-126`). A `:git/url`/`:local/root` konserve
has NO `META-INF/maven/.../pom.properties` (that file is generated at maven
package time), so `konserve-version` is `nil` (`tools.cljc:136`). Then
`version-check` runs `(>= (compare ksv-now ksv-stored) 0)` — `compare` of `nil`
against the stored `"0.9.346"` throws/fails, so datahike `log/raise`s "Database
was written with newer konserve version" (`connector.cljc:119-124`). The shim
supplies a synthetic `pom.properties` with `version=0.9.347` so the compare
admits stores stamped by the previously-deployed 0.9.346
(`dev-resources/konserve-shim/.../pom.properties`).

Cleanest fixes, in order:

1. **Tag/release the konserve fork with a real version** ≥ the stored stamp.
   A real artifact carries its own `META-INF/maven/.../pom.properties` → the shim
   disappears and the `:override-deps` `:git/sha` can become `:mvn/version`. This
   is the *correct* fix; the shim's own comment says "DELETE this shim when the
   fix ships as a real mvn artifact."
2. **For the uberjar path, the shim is a non-issue** — it's baked into the jar's
   `META-INF` and travels with it (no repo path, no `:local/root`). So the
   uberjar lets you ignore B entirely for the 3rd party, and you fix B at leisure.
3. (Not recommended) loosen the version-check to treat `nil` konserve-version as
   "DEVELOPMENT"/skip — touches datahike core semantics for a packaging problem.

## C. The `prep-lib` step

The fork declares `:deps/prep-lib {:ensure "target/classes" :alias :build :fn
compile-java}` (`reference-code/datahike/deps.edn:27-29`); `compile-java`
javac-compiles the hand-written + checked-in generated Java API
(`reference-code/datahike/build.clj:9-27`). A cold `:git/sha` consumer must run
`clojure -X:deps prep` once.

- **Uberjar (option 1) avoids it for the consumer** — the build runs prep (or you
  run it once before the build) and the compiled classes land in the jar.
- **For a deps.edn consumer (options 2/3)** the cleanest answer is to **document
  `clojure -X:deps prep`** as a one-time step — OR ship a maven/clojars artifact
  whose jar already contains `target/classes` (option 5), which removes prep
  because the classes are pre-baked. Note the fork already checks in
  `DatahikeGenerated.java` specifically so cold `:git/url` prep succeeds without
  the codegen bootstrap (`build.clj:10-21`), so prep is at least reliable today.

## D. Durability — git tags vs maven/clojars vs uberjar

- **Bare `:git/sha`:** least reliable. SHAs reachable only from mutable branch
  tips; force-push GCs them (the stated risk). Avoid handing this to a 3rd party.
- **Git tags:** immutable refs → reproducible `:git/tag` + `:git/sha` pulls. Good
  for options 2/3, and you should tag regardless to protect YOUR uberjar rebuild.
- **Maven/clojars deploy:** most reliable for a *source-level* dep; immutable
  coordinates, ships `target/classes` (kills prep). More release machinery
  (the fork would need a working deploy remote; current `:writer`/probe comments
  note the fork "has no pushable remote" historically — `deps.edn:196-199`).
- **Uberjar (tools.build `b/uber`):** **the actually-easy answer.** Reading
  datahike's `build.clj` (javac via `b/javac`) and Seon's existing `build.clj`
  (`b/compile-clj` + `b/uber` already present, just pointed at the wrong basis),
  an uberjar built off the `:writer` basis bakes EVERYTHING — fork SHAs,
  src-secondary, shim META-INF, proximum/google-genai jars, prep output — into
  one file. It sidesteps submodule + prep + shim + SHA-GC for the consumer in a
  single artifact. Least 3rd-party work, high reproducibility. This is why it is
  ranked #1.

## E. Provider lock-in / silent failure (flagged, not designed)

Gemini is hardcoded: model `gemini-embedding-2` (`embedding-model`, `embed.clj:602`),
`outputDimensionality 1536` (`embed.clj:633`), L2-normalized for cosine HNSW
(`embed.clj:636-642`), client built directly from `com.google.genai.Client`
(`embed.clj:105`, `:619`). Dim 1536 is also baked into the Proximum index config
(`embed.clj:303`) and the `:seon/embedding` tuple schema.

**Minimal provider seam (don't build now, just note):** extract a single
`embed-texts`-shaped protocol/multimethod (`texts -> vectors`, fixed dim) so the
Gemini `Client` call (`embed.clj:661-680`) becomes one impl; a provider keyword
(`SEON_EMBED_PROVIDER`) selects it. The index dim must match the provider's
output dim — so a provider swap that changes dim requires a reindex; keep dim a
single source of truth (today it's `embedding-dim`, reuse it for the index +
schema + provider config). That's the whole seam — one fn, one config var.

**Loud preflight (the part to ship with #1):** a `--preflight` that asserts
Java/vector-module, `SEON_EMBED`, a real `embed-text` returning a 1536-vector,
and a one-row KNN self-test — exit non-zero on any failure. This is the single
highest-value addition because today every misconfiguration (no key, blocked
egress, half-set env, wrong Java) degrades to "no hits" with no error
(`embed.clj:669-671`, `:837`, `:919`).

## Evidence index (files read)

- `deps.edn:164-180` — `:writer` alias: jvm-opts, extra-paths (shim + src-secondary), git-dep forks, proximum/google-genai, konserve override, `-m seon.server.boot`.
- `reference-code/datahike/deps.edn:25` (`:paths` excludes src-secondary), `:27-29` (`:deps/prep-lib`), `:69` (`:test` lists src-secondary as extra-path).
- `reference-code/datahike/build.clj:9-27` — `compile-java` prep fn; checked-in generated Java so cold `:git/url` prep works.
- `reference-code/datahike/src/datahike/tools.cljc:116-136` — `get-version` reads `META-INF/maven/.../pom.properties`; `konserve-version` nil for pom-less dep.
- `reference-code/datahike/src/datahike/connector.cljc:89-124` — `version-check`; konserve compare raises on `nil`.
- `reference-code/datahike/src/datahike/index/secondary.cljc:291` — `register-index-type!` runtime registry.
- `reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:1-23` — the optional impl, `(:require [proximum.core])`.
- `dev-resources/konserve-shim/META-INF/maven/org.replikativ/konserve/pom.properties` — `version=0.9.347` shim + its own "delete me" note.
- `src/seon/embed.clj:37-44` (`SEON_EMBED` switch), `:82` (require proximum ns), `:105-106` (genai imports), `:163` (`embed-feature-enabled?`), `:303` (dim 1536 index), `:602/:633/:636-642` (model/dim/normalize), `:611-619` (lazy nil client), `:661-680` (embed-texts).
- `src/seon/server/boot.clj:42,51` — wire-server requires proximum ns + seon.embed; entry for `:writer`.
- `build.clj` (repo root) — existing tools.build with `b/compile-clj` + `b/uber`, currently on DEFAULT basis + `:main 'seon.core` (NOT the writer); the seam to add `writer-uber`.

## Verification still owed (do before shipping the jar)

1. Confirm `b/create-basis {:aliases [:writer]}` triggers the datahike fork's
   `prep-lib` (else run `clojure -X:deps prep` first).
2. Confirm the shim `META-INF/maven/.../pom.properties` lands in the uberjar and
   `get-version` reads it from inside the jar (`io/resource` works in jars — high
   confidence, but verify the path isn't stripped by uber merge rules).
3. Confirm AOT of `datahike.index.secondary.proximum` + `seon.server.boot`
   registers `:proximum` at jar runtime (boot a fresh store from the jar, install
   the index, do a KNN — the existing P2 harness top-1 check is the oracle).
