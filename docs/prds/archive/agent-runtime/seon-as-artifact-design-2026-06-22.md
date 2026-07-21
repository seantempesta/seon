---
type: prd
status: draft
tags: [prd, agent, database]
---

# Seon as a consumable artifact — clean packaging + extension model

## TL;DR

Today there is **no published Seon artifact**: the only consumable unit is a
full `git clone --recurse-submodules` of this repo plus two cold toolchains
(tools.deps/JVM and shadow-cljs/Node) run from source. The embeddings feature
makes this worst-case — it pulls four `:git/sha` forks pinned to *mutable
branch tips*, a `reference-code/datahike` **git submodule** whose `src-secondary`
subdir is bolted on via `:extra-paths`, a hand-written `konserve-shim`
`META-INF/maven/.../pom.properties`, and a cold `deps/prep` Java compile. A
third party cannot run, brand, or extend Seon without editing files under
`src/`.

The fix is two moves, neither novel — every reference exemplar already does
them:

1. **Package the runtimes as artifacts.** Build a **wire-server uberjar** off
   the `:writer` basis (bakes the forks + `src-secondary` + the shim + proximum
   + google-genai into one jar — kills the submodule, prep, shim, and SHA-GC
   risk for the consumer *in one move*, per the prior research). Ship the **pod
   as a CLJS source jar** (`raw .cljs + deps.cljs + externs/`) the downstream
   overlays with its own thin `:node-script` build. Tag the forks; lib-ize the
   datahike-proximum integration as a **companion artifact** (à la
   `konserve-lmdb`) and deploy the konserve fork with a real version so the shim
   disappears.

2. **Open the seams as a registration API, not a source edit.** Seon already
   has the right *archetypes* internally — and several seams **already work
   no-fork today**: `schema/register!` is PUBLIC (a downstream adds an attr AND
   a custom malli type from its own ns, schema.cljc:400), `wire/handle-op` is an
   open defmulti (a downstream jar adds a wire op), and a ctx section is a
   DB-transacted entity with a late-bound symbol (a downstream adds an agent
   context surface). The genuine **fork-forcing gaps** are narrower: the
   **embedding provider** is hardwired to Gemini with no registry (C6); the
   **chat provider** is a closed enum + hardcoded case (C7); the
   **tx-augmenter** is a single-slot atom that silently clobbers embed-on-write
   (C8); the **transit codec** carries no `:handlers` (C9); the pod's
   socket/store paths are **compile-time constants** with no env read (C11); and
   the sci trust/expose allowlist is a hardcoded prefix regex that blocks an
   `acme.*` HOST capability (not a ctx section — that works) (§3.3). Close each
   by mirroring the exemplar archetype (sci `:allow`-as-data, integrant
   `init-key`/`derive`, datahike `register-index-type!`, nippy `extend-freeze`,
   cider-nrepl `set-descriptor!`, hyperlith `start-app`). The schema-registry
   "third layer + `register-type!`" originally scoped here is **dropped** — a
   verifier proved it is unnecessary (see C10).

The falsification harness is **`/Users/sean/src/seon/acme`** — a fresh project
depending *only* on the published artifacts that brands, adds one feature (a
wire op + a ctx section), adds a custom embedding provider, registers a custom
embeddable attr, boots pod + wire-server against consumer-chosen sockets/store,
and runs a loud `--preflight`. **If acme cannot do any step without touching
`src/`, that step is a coupling bug** — §4 enumerates exactly which steps fail
today and which P-phase fixes them.

---

## 1. Problem & coupling registry

Every coupling below forces a source checkout *today*. Severity is the cost to a
third party: **blocker** = cannot run/extend at all without editing Seon;
**high** = forces a full checkout + submodule + cold build; **medium** = a
fork-forcing edit for a specific extension; **low** = a documented friction.

| # | Coupling | Where | Severity | Why it forces a checkout | Fix (phase) |
|---|----------|-------|----------|--------------------------|-------------|
| C1 | **No published artifact** — only a full git checkout + submodules is consumable | `build.clj` builds the wrong basis (`b/create-basis {:project "deps.edn"}` + `:main 'seon.core`, build.clj:7,35 = the PAUSED JVM app, no embeddings classpath); no root `pom.xml`; two unrelated tags only | blocker | Consumer must clone the tree and run two toolchains from source | Wire-server uberjar off `:writer` (P0); pod source jar (P3) |
| C2 | **datahike `src-secondary` is a git submodule on `:extra-paths`** | `deps.edn:167-168` adds `reference-code/datahike/src-secondary`; `.gitmodules` pins the submodule to `5f62d57f`; the fork's own `:paths` **excludes** src-secondary (datahike `deps.edn:25`) | high | `:proximum` source reaches the classpath only via the repo path + submodule; an in-fork alias can't fix it (tools.deps git/local deps use only default `:paths`) | Bake into uberjar (P0); `seon-datahike-proximum` companion artifact (P1). **NOTE:** the companion artifact kills the submodule + `:extra-paths`, but the `:proximum` ns must still be **explicitly required** — `create-index` auto-requires ONLY namespace-qualified type keywords (secondary.cljc:312-320) and Seon registers the UNqualified `:proximum` (proximum.clj:233-234), which is exactly why boot.clj:42 carries an explicit `(:require [datahike.index.secondary.proximum])` with a comment stating it is mandatory to avoid "Unknown secondary index type: :proximum". The uberjar path is fine (boot.clj transitively requires it). Do NOT claim "auto-required on first use". |
| C3 | **konserve-shim fake `pom.properties`** | `dev-resources/konserve-shim/META-INF/maven/org.replikativ/konserve/pom.properties` (`version=0.9.347`) on `:writer` + `:cljs` `:extra-paths` (deps.edn:167,291) | high | The pomless `:git/sha` konserve makes `datahike.tools/get-version` return nil → `version-check` raises "newer konserve version"; the shim is a synthetic file on a repo path | Baked into uberjar (P0); deploy konserve fork as real mvn version → shim deleted (P1) |
| C4 | **Cold `deps/prep` Java compile** | datahike fork `:deps/prep-lib {:ensure "target/classes" :fn compile-java}` (datahike deps.edn:27-29); `bin/seon` reinvents an entire auto-prep subsystem (fingerprints, mkdir-mutex, `maybe_prep_deps`, `cmd_prep`, bin/seon:340-448) | high | Every fresh `:writer`/`:cljs` consumer must `clojure -X:deps prep` before boot; multi-minute compile inside the boot window | Uberjar bakes prep output (P0); document one-time prep for the source consumer path (P1) |
| C5 | **Four forks pinned by `:git/sha` on mutable branch tips** | datahike `5f62d57f`, konserve `32e3c598`, superv.async `3e6ed755`, partial-cps `c0d941d4` — duplicated across `:writer`/`:cljs`/`:replica-probe-jvm`/`:replica-peer-jvm`; two noted "no pushable remote" (deps.edn:196-199) | high | A force-push GCs the SHA and breaks every `:git/url` consumer; no checksummed jar boundary | Uberjar retires the risk for the consumer (P0); **immutable git tags** on all four (P1) |
| C6 | **Embedding provider hardwired to Gemini** | `seon.embed` imports `com.google.genai.Client` (embed.clj:105), `embedding-model "gemini-embedding-2"` (`^:const`, embed.clj:599), `embedding-dim 1536` (embed.clj:113), builds the client inline (embed.clj:617), `embed-texts` calls Gemini directly (embed.clj:661-680). **No provider registry** — `register-embeddable!` (the trigger seam, embed.clj:495) stops at *what* gets embedded, not *who* embeds it | blocker (for "add a provider") | A custom embedding provider requires forking embed.clj | `register-embed-provider!` registry (P2) |
| C7 | **Chat LLM provider is a closed enum + hardcoded dispatch** | `::provider [:enum :deepseek :anthropic :openai-compat]` (ai.cljs:109); `current-llm-fn` is a closed `case` (client.cljs:1547) | medium | A genuinely new provider adapter cannot be wired without editing `src/` (the `:openai-compat` gateway is the only escape hatch) | `register-llm-adapter!` registry; `::provider` → `:qualified-keyword` (P2) |
| C8 | **tx-augmenter is a single-slot atom (last-writer-wins)** | `!tx-augmenter` `(atom (fn [_db tx-data] tx-data))` (wire.clj:215); `register-tx-augmenter!` `reset!`s it (wire.clj:217-223) | medium | A downstream augmenter **silently replaces** seon.embed's embed-on-write — a real bug the moment there are two | Composable ordered registry, mirroring `register-on-ensure-db-hook!` (registry.clj:244) (P2) |
| C9 | **transit codec carries no `:handlers`** | `seon.server.transit/write-str`/`read-str` build `(t/writer out :json)`/`(t/reader in :json)` with no handler maps (transit.clj:26-41) | medium | A downstream custom value type can't ride the wire — closed nippy-without-`extend-freeze` | `register-transit-handler!` with namespaced tags (P2) |
| C10 | **Schema custom-type survival across a self-host malli reload** (NOT a publicity gap) | `register!` is **PUBLIC** (schema.cljc:400) — a downstream CAN today `(register! :acme.kb/body :string)` AND `(register! :acme/t (m/-simple-schema {:type :acme/t :pred p}))` over the same `*schemas` atom, no fork. The REAL defect: `relink-registry!` (schema.cljc:63-66) must re-assert after any self-host `(require 'malli.core)` or downstream types vanish (the 2026-06-10 stomp) — `seon.eval` already triggers relink, so it survives *because `*schemas` is the same `defonce` atom across the relink* | low | Custom type *can* be added today; the only risk is the relink-after-self-host invariant + the CLJS-only single-segment-ns assertion (schema.cljc:434) possibly rejecting a single-segment `:acme/x` | **No new machinery.** Verify `:acme`-prefixed keys pass `assert-multi-segment-namespace!`; acme asserts a downstream type still validates *after* an agent eval that requires malli.core (P2 — test only) |
| C11 | **Pod socket/store paths are compile-time constants (no env read)** | `default-req-sock "tmp/seon-cluster-default-req.sock"` (wire_node.cljs:50, full path `src/seon/store/internal/wire_node.cljs`); `default-store-path "data/clusters/default/store"` (wire.cljs:62) — **no `process.env`**. The pod has the access pattern elsewhere (ai.cljs:185, platform.cljs:93 do `(.. js/globalThis -process (.-env) (aget v))`); store/wire just never adopted it. **Wire-server is NOT affected** — it is already relocatable via `--req-sock`/`--pub-sock`/`--path` CLI args (boot.clj `-main`, bin/seon passes them from env); the wire-server reads CLI ARGS, not env. This is a **pod-only** gap | high (for pod relocatability) | A consumer who relocates sockets/store gets a POD that still connects to the baked default + reads an empty store; they line up *only* when the consumer's chosen paths equal the constants | Read `process.env` at BOTH pod sites (`wire_node.cljs:50`, `wire.cljs:62`) + make `store-id` hash the relocated basename (P0/P3); C12 is the teeth (silent-empty vs loud) |
| C12 | **Store identity is socket-basename-derived and unvalidated** | `store.wire/store-id` md5-hashes `:seon.server/<sock-basename>` (wire.cljs:67-86), replicating `server.store/name->uuid`; nothing asserts the two agreed | medium | A basename mismatch yields a silently-empty pod store, not an error | Echo store `:id` on `ping`; pod asserts agreement at boot (P3) |
| C13 | **Branding/names/paths leak as hardcoded defaults** | provider defaults `:deepseek` (ai.cljs:321), key-env probes `ANTHROPIC_API_KEY`/`DEEPSEEK_API_KEY`/`GEMINI_API_KEY` scattered; cluster name / store / sock baked in wire `-main` parse-args | medium | No single config table; a consumer rebrands by hunting many sites | One provider-capability table + `seon-config.edn` resource (P2) |
| C14 | **Extension is build-time recompile, not library-consume** | `SEON_EXTRA_SRC`/`SEON_EXTRA_PRELOAD` inject a `:local/root` sibling project + a `:preloads` slot into the seon checkout's shadow build via `bin/seon` `--config-merge` (bin/seon:141-157); the reserved-prefix guard `assert-extra-vars-unreserved!` (client.cljs:942) and `!extra-core-vars` reset (client.cljs:912) work, but only from *inside* the seon tree | high | Every seam today assumes the downstream edits/compiles within Seon's source tree; there is no "depend on seon.jar, register from my own deps" path | Promote to artifact-mode overlay (P3) |
| C15 | **`--preflight` flag does not exist — would CRASH with `exit 2`, not gate** | `seon.server.wire/parse-args` (wire.clj:34-48) has no `--preflight` case; the default case does `(println "Unknown arg:" …) (System/exit 2)`. There is NO preflight ns anywhere (`find src -iname '*preflight*'` = empty). `boot.clj:251-257` just `(apply wire/-main args)` — it does not intercept `--preflight` either. The entire acme harness leans on this flag | blocker | The "loud gate" converting the four silent embedding-failure modes into a non-zero exit is 100% aspirational; `java -jar … --preflight` exits 2 *before* any embedding check, indistinguishable from a real failure | Add a `--preflight` case to `parse-args` + a `seon.embed.preflight` ns + branch in `boot/-main` with DISTINCT non-zero exit codes per failure (P0) |
| C16 | **Pod's OWN `SEON_EMBED` master gate is never exercised by acme as written** | The pod has a second master switch: `seon.agent/embed-retrieval-on?` reads `(.. js/process -env -SEON_EMBED)` (agent.cljs:922) and `prefetch-and-render-prompt!` short-circuits to plain `render-prompt` — NO knn-search wire call — when unset. acme step 3's pod env omits `SEON_EMBED` | high (for falsification validity) | A fully-embedding wire-server + a pod that never queries it; "embeddings work end-to-end" is unfalsified — acme would "pass" while proving nothing about retrieval | Export `SEON_EMBED=1` in the pod env too (two-process master switch); acme's success criterion must be a POD-observed KNN hit, not just a wire-server self-test. Pod needs NO `GEMINI_API_KEY` (it sends query TEXT over the wire; the wire-server embeds) (P0 — acme wiring) |
| C17 | **`specced-fn-vars` is a `defmacro` (CLJ, compile-time), not a runtime fn** | `seon.indexing/specced-fn-vars` is a `defmacro` (indexing.clj:86); client.cljs pulls it via `:require-macros [seon.indexing :refer [specced-fn-vars]]` (client.cljs:173) | medium | The acme `pod.cljs` snippet calls `(ix/specced-fn-vars)` as a runtime fn — won't compile; the consumer hits a compile error on their first extension file. Also: a CLJS source jar missing its `.clj` macro namespaces breaks every downstream `:require-macros` | Fix the acme snippet to `:require-macros`; ensure every `.clj`/`.cljc` macro ns reachable from `:require-macros` in `src/seon/**.cljs` (indexing.clj at minimum) ships in `seon-pod`/`seon-core` (P3 — jar contents) |
| C18 | **Custom-provider acme step is internally inconsistent with the standalone uberjar** | The standalone uberjar's classpath has NO `acme.embed-provider`; `register-embed-provider!` runs only when `acme.embed-provider` is loaded by an `acme.main` entry. acme §4 runs `java -jar seon-wire-server-standalone.jar` with `SEON_EMBED_PROVIDER=acme/local` — which finds no registered provider | high | The marquee falsification (custom provider returns hits) is unreachable as written: the standalone uberjar is Gemini-only; a custom provider REQUIRES the lib-jar + `acme.main` (or a `-cp` side-load) | Make §4 explicit: standalone uberjar = run-as-is (Gemini only); custom-provider test = `java -cp acme.jar:… -m acme.main` (lib-jar path) (P2) |
| C19 | **`build.clj` global state collides with a multi-artifact build** | `build.clj` binds one `lib 'seon/seon`, one whole-project `basis` (line 7), one `uber-file`/`jar-file`, an uber that does `:main 'seon.core` (line 35), and a `clean` that deletes ALL of `target/` (line 12). The 5-artifact plan needs distinct basis/lib/version/output per artifact | low | "Just ADD a `writer-uber` fn" hides that the existing top-level globals + shared `clean` will race/interfere across artifacts | Refactor `build.clj` so each artifact fn computes its OWN basis/class-dir/output (per-artifact class-dirs like `target/wire-classes`, `target/pod-jar`); shared `clean` must not race the multi-artifact build (P0/P3) |
| C20 | **Lib-jar path INHERITS datahike's `:deps/prep-lib` + mutable SHA (C4/C5 persist)** | The fork's `:paths` includes `target/classes` (datahike deps.edn:25); compiled Java reaches the classpath only via prepped `target/`. The `seon-wire-server` LIB jar does NOT bake datahike's prep output — a lib-jar consumer still pulls datahike as a git-dep, must `clojure -X:deps prep` cold, and references the mutable SHA | medium | Only the UBERJAR retires C2/C3/C4/C5 for the consumer pre-P1; the lib-jar inherits the git-dep coupling until P1 ships datahike/konserve as real mvn | Sequence acme's lib-jar/AOT (custom-provider) test AFTER P1; state the uberjar-vs-lib-jar coupling difference explicitly (P1) |

**Two toolchains never unified** underlies C1: tools.deps/JVM (`clojure -M:writer`
+ `build.clj`) and shadow-cljs/Node (`clj -M:cljs` → `shadow.cljs.devtools.cli`)
build independently; the pod is the `:client` shadow build (`out/client/main.js`)
with no npm-publishable artifact.

---

## 2. Artifact strategy

The publishable units are three jars + one config resource. A consumer pulls
coordinates; they never see `reference-code/`, the submodule, the shim, or a
prep step.

### (a) Wire-server — uberjar to RUN, lib jar to EMBED

**The single highest-leverage artifact: `seon-wire-server-standalone.jar`,
built off the `:writer` basis.** Per the prior research (embeddings-packaging-
2026-06-21.md §1, ranked #1) this one `tools.build` move resolves four
classpath-assembly problems **simultaneously** because `tools.build` resolves
them at *Sean's* machine, once, at build time:

**Build.clj must be refactored, not just appended to (C19).** The existing
`build.clj` binds ONE global `lib`/`basis`/`uber-file`/`jar-file` and an uber
that does `:main 'seon.core` (the paused JVM app), with a `clean` that wipes all
of `target/`. The 5-artifact plan needs per-artifact local bindings (own basis,
own `class-dir` like `target/wire-classes`, own output) and a `clean` that does
not race the multi-artifact build. The snippet below shows the wire-server
artifact fn with LOCAL bindings:

```clojure
;; build.clj — a self-contained artifact fn (own basis + class-dir, NOT the globals)
(defn writer-uber [_]
  (let [writer-basis (b/create-basis {:project "deps.edn" :aliases [:writer]})
        class-dir    "target/wire-classes"]
    (b/delete {:path class-dir})
    (b/copy-dir {:src-dirs ["src" "resources"
                            "reference-code/datahike/src-secondary"  ; → jar (kills C2)
                            "dev-resources/konserve-shim"]           ; META-INF → jar (kills C3)
                 :target-dir class-dir})
    (b/compile-clj {:basis writer-basis
                    :src-dirs ["src" "reference-code/datahike/src-secondary"]
                    :class-dir class-dir
                    ;; AOT precompiles bytecode at BUILD time. It does NOT register
                    ;; :proximum — that swap! runs when boot.clj LOADS the proximum ns
                    ;; at RUNTIME. AOT only (a) avoids a cold first-call compile and
                    ;; (b) surfaces missing optional deps at build time.
                    :ns-compile '[datahike.index.secondary.proximum
                                  seon.embed seon.server.boot]})
    (b/uber {:class-dir class-dir
             :uber-file "target/seon-wire-server-standalone.jar"
             :basis writer-basis
             :main 'seon.server.boot})))   ; boot.clj has (:gen-class) (boot.clj:58) — OK
```

- **C2 (submodule):** `:writer` basis includes the `src-secondary` `:extra-paths`;
  `b/compile-clj` AOTs `proximum.clj`/`stratum.clj`/`scriptum.clj` into the jar.
  The consumer never sees a submodule. `:proximum` registration happens at RUNTIME
  via boot.clj's explicit require (NOT via AOT, NOT via auto-require — see C2 note).
- **C3 (shim):** `b/copy-dir` carries the shim's `META-INF/maven/.../pom.properties`
  into the jar; `datahike.tools/get-version` reads it via `io/resource` identically
  from inside a jar (tools.cljc:116-126). There is NO competing real konserve
  `pom.properties` (the fork is git-only in `~/.gitlibs` with no META-INF;
  konserve-jdbc excludes konserve), so no uber-merge CONFLICT exists — but whether
  a hand-authored `META-INF/maven/.../pom.properties` survives `b/uber`'s default
  merge is **UNVERIFIED — needs a build spike** (see Verification status §6).
- **C4 (prep):** `tools.build` runs the fork's `prep-lib` as part of git-dep basis
  prep (the `:writer` git-dep at `5f62d57f` already has a prepped `target/` in
  `~/.gitlibs`, confirming prep runs for `:deps/prep-lib` git-deps); the compiled
  `DatahikeGenerated.java` lands in the jar. Belt-and-suspenders: `clojure -X:deps
  prep :aliases [:writer]` once before the build. **Caveat:** this retires C4 for
  the UBERJAR consumer only — a `seon-wire-server` LIB-jar consumer still pulls
  datahike as a git-dep and must prep cold (C20).
- **C5 (SHA-GC):** the jar has *already resolved + compiled* the four SHAs; the
  consumer pulls a jar, not a SHA. (Risk remains for *rebuilding* — mitigated by
  tagging in P1; and for the LIB-jar consumer who still references the SHA — C20.)

Ship with the **loud preflight** (research §E, the single highest-value addition):
a `--preflight` flag on the uberjar that exits non-zero on Java < 22 / no
`jdk.incubator.vector`, `SEON_EMBED` unset, `GEMINI_API_KEY` blank **or** a failed
real `embed-text` round-trip (assert length 1536), and a one-row `install!` + KNN
top-1 self-test. This converts the **four current silent-degrade-to-no-hits modes**
(embed.clj:163 master-gate inert, embed.clj:617 lazy-nil client, embed.clj:670
no-key throw only if a trigger fires, backfill no-ops) into one explicit gate —
essential because acme must *prove* embeddings booted.

Run contract (consumer supplies; the jar can't bake these):

```bash
export SEON_EMBED=1
export GEMINI_API_KEY=…           # or their custom provider's key (P2)
export SEON_CLUSTER_DIR=/var/acme/cluster
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
     -XX:+UseG1GC -Xmx2g \
     -jar seon-wire-server-standalone.jar \
     --backend file --path "$SEON_CLUSTER_DIR/store" \
     --req-sock "$SEON_CLUSTER_DIR/req.sock" --pub-sock "$SEON_CLUSTER_DIR/pub.sock" \
     --preflight
```

**Lib jar (for EMBED, optional sibling):** a thin `seon-wire-server` *library*
jar (`b/write-pom` + `b/jar` off the `:writer` deps, no `:dev`/`:test` extras) is
the dependency a downstream pulls **when it wants to add wire ops / providers in
its own JVM process and AOT its own `acme.main`** (the hyperlith model: framework
is an ordinary dep, consumer AOTs only its tiny entrypoint, build.clj 22 lines).
The uberjar is the *run-as-is* artifact; the lib jar is the *build-on* artifact.
Both are off the same basis. **The custom-embed-provider acme test REQUIRES this
lib-jar path** — the standalone uberjar has no `acme.embed-provider` on its
classpath, so `SEON_EMBED_PROVIDER=acme/local` finds no registered provider (C18).
**Coupling caveat (C20):** the lib jar does NOT bake datahike's prep output or pin
a real version — it inherits datahike as a git-dep (the fork's `:paths` is
`target/classes`), so a lib-jar consumer still must `clojure -X:deps prep` cold and
references the mutable SHA, **until P1 deploys datahike/konserve as real mvn**.
Only the UBERJAR retires C2/C3/C4/C5 for the consumer pre-P1.

**How to kill the submodule permanently (P1) — companion artifact, not fold-in.**
The datahike exemplar is decisive: konserve ships `:s3`/`:lmdb`/`:redis` backends
as **separate maven artifacts** (`konserve-lmdb`, `konserve-s3`) whose namespaces
install their own `defmethod`s on load; datahike core never changes. The
secondary-index registry is the same shape: `datahike.index.secondary/
register-index-type!` (secondary.cljc:291) + `create-index` (secondary.cljc:312-320).
**CAVEAT (verifier-corrected):** `create-index`'s auto-require fires ONLY when the
type keyword is `(qualified-keyword? …)`. proximum.clj:233-234 registers the
**UNqualified** `:proximum`, and Seon's schema declares `:db.secondary/type
:proximum` — so `create-index` will NOT auto-require it; it would throw "Unknown
secondary index type: :proximum". This is precisely why boot.clj:42 AND embed.clj:82
carry an explicit `(:require [datahike.index.secondary.proximum])`, with boot.clj's
comment (lines 36-41) stating the require is mandatory on store restore. So:

> **Ship `seon-datahike-proximum` — a thin jar containing ONLY
> `datahike.index.secondary.proximum` (+ stratum/scriptum), depending on
> `org.replikativ/proximum` + the datahike fork.** A consumer adds it to deps,
> and **seon code (boot.clj/embed.clj) or the consumer MUST still explicitly
> `(require 'datahike.index.secondary.proximum)`** to run the `register-index-type!`
> form (the uberjar does this transitively via boot.clj). This **deletes the
> `reference-code/datahike` submodule and the `:extra-paths` entry entirely** —
> the integration becomes a normal dependency, but it is NOT "add it to deps and
> it just works". Optional future cleanup: change the registered key + schema to a
> qualified keyword (e.g. `:datahike.index.secondary.proximum/proximum`) to
> actually enable auto-require and drop the explicit require.

Do **not** fold `src-secondary` into the fork's `:paths`: the datahike author
deliberately excluded it (deps.edn:25) because `proximum.clj` does `(:require
[proximum.core])` and would force the proximum/genai maven deps onto *every*
datahike consumer, and it won't even AOT without them. The companion-artifact
form keeps proximum optional (correct) while killing the submodule.

**Kill the shim (P1):** consume konserve as a **real mvn artifact** — deploy the
fork's header-fix as `org.replikativ/konserve 0.9.347` (its own comment: "DELETE
this shim when the fix ships as a real mvn artifact"). A real jar carries
`META-INF/maven/.../pom.properties` → `version-check` passes naturally → the
`:override-deps` `:git/sha` becomes `:mvn/version` and `dev-resources/konserve-shim`
is deleted. Interim stopgap: `mvn install` the fork jar locally rather than
`:local/root` + a fake pom.

**Fork-SHA-on-mutable-branch (P1):** tag all four forks with **immutable git
tags** (and ideally Clojars-deploy datahike + konserve with real versions). This
protects the *rebuild* (the consumer already has the jar). Tags make the uberjar
rebuild reproducible even if branches move/force-push. **Comment-rot hazard to
scrub:** `deps.edn:142-151,184` reference datahike `@1ae35696` as "the SAME fork
sha", but every actual pin (lines 171,194,220,319) and the submodule
(`git rev-parse` = `5f62d57f`) are `5f62d57f` — the stale `1ae35696` comments give
a future bumper conflicting guidance. The `:writer` comment (156-158) also warns
the submodule SHA and dep SHA must be hand-aligned on every bump — a dual-pin-by-
hand burden the companion artifact eliminates, which is a primary argument FOR it.
Flag the stale comments for deletion when the submodule is removed (P1).

### (b) Pod — a CLJS source jar the downstream overlays

The shadow exemplar is decisive on the *shape*: **`:node-library`/`:npm-module`
are the wrong target** — they build a UMD/per-ns bundle to be imported *by* other
JS (node_library.clj wraps exports in a UMD wrapper; npm_module.clj emits per-ns
`.js`). **The pod is an APPLICATION** (a long-running `:node-script` with `:main
seon.client/-main`). The right publishable shape is:

> **Ship the pod as a SOURCE jar: raw `src/seon/**.cljs` + `.cljc` + a `deps.cljs`
> declaring the npm deps the pod's namespaces require + an `externs/` dir.** The
> downstream owns a thin `shadow-cljs.edn` whose `:node-script` build sets `:main`
> to its own entry ns that `(:require seon.client)`.

This works because shadow scans **every classpath jar** for `.cljs`/`.cljc`
resources (`find-jar-resources*`, classpath.clj:402-451) and enumerates **every
`deps.cljs`** for `:npm-deps`/`:foreign-libs`/`:externs` (`get-deps-from-classpath`,
npm_deps.clj:144-162). A published jar carrying raw sources + a `deps.cljs` is a
complete shadow library — the consumer's own build re-compiles Seon's namespaces
against the downstream classpath and auto-pulls the npm deps. This is *exactly*
how Seon gets datahike/malli CLJS off the `:cljs` alias today.

The **overlay/override seam is built into shadow** and needs no Seon change:
`index-rc-merge-js` (classpath.clj:777-794) lets a **filesystem source path
override a same-named jarred resource silently** — the warn fires only on
jar-vs-jar (line 785-790). So a downstream drops `src/seon/render/live_tile.cljs`
on its own source path and shadows Seon's jarred copy — namespace-level overlay
without forking.

Two contracts the artifact must document:

- **Ship dev-compiled (`:optimizations :none`), NOT `:advanced`.** The override/
  late-binding capability (downstream fns *and* agent redefines flowing to
  already-compiled callers) survives only at `:none`/`:simple` and **silently
  breaks at `:advanced`** (whole-program DCE/inlining). Surface this as a build
  hook that throws if `:optimizations` is `:advanced`.
- **Ship a NEW seon-root `deps.cljs` declaring the externs** (`:externs
  ["externs/node_fs.js"]` + any `:npm-deps`). There is NO `deps.cljs` at the seon
  root today; `externs/node_fs.js` is Seon-owned and is wired **per-build** in
  `shadow-cljs.edn` (6 build entries, lines 77-205), which is the duplication
  shadow-cljs.edn:74 notes. (The earlier-draft claim that "datahike's deps.cljs
  points at the wrong path" was wrong — datahike's `deps.cljs` is
  `{:externs ["datahike/externs.js"]}`, its own externs.) Shipping a seon-root
  `deps.cljs` lets consumers inherit the externs via shadow's `deps.cljs`
  classpath scan (`npm_deps.clj`).

For ESM consumers, ship `out/client/main.js` (CJS) + a one-line `.mjs` shim
(shadow-cljs.edn:50-57 already notes the wrap).

### (c) The dependency a downstream pulls

| Artifact | Form | Consumer pulls when | Contents |
|----------|------|---------------------|----------|
| `seon-wire-server-standalone.jar` | uberjar, `:main seon.server.boot` | runs the writer as-is | forks + src-secondary + shim + proximum + google-genai baked |
| `seon-wire-server` | lib jar (`b/jar`) | adds wire ops / providers in its OWN JVM, AOTs `acme.main` | `seon.server.*` + `seon.embed` + base `:deps` |
| `seon-datahike-proximum` | companion lib jar | wants vectors (auto-required by `create-index`) | only `datahike.index.secondary.{proximum,stratum,scriptum}` |
| `seon-pod` | CLJS **source** jar | builds the pod with its own `:node-script` overlay | raw `src/**.cljs` + `.cljc` + `deps.cljs` + `externs/` |
| `seon-core` (optional, P2) | lib jar | shares the genuinely-portable `.cljc` | `seon.schema`, `seon.instrument`, registered shared shapes |

A thin **`seon-core`** lib jar is worth it as the one coordinate carrying the
portable `.cljc` (`seon.schema`, `seon.instrument`) that BOTH the JVM wire-server
build and a downstream depend on — so a third party adds ONE coordinate instead
of cloning the tree. Don't over-split (the datastar exemplar warns: a lib per
provider multiplies coordinates + version-lockstep; provider plurality belongs
behind a *protocol within* `seon-embed`, not N jars). The natural seams are
**core / wire-server / proximum / pod**, not finer.

**Versioning + pinning.** Use `(format "0.1.%s" (b/git-count-revs nil))` (already
in build.clj:5) as the artifact version; deploy to Clojars (or a private mvn
repo). A consumer pins `org.seon/seon-wire-server {:mvn/version "0.1.N"}` and
`org.seon/seon-pod {:mvn/version "0.1.N"}` — immutable coordinates, no `:git/sha`.
The forks become real `:mvn/version` (P1) so nothing in the consumer's tree
references a mutable SHA. The wire-server uberjar version IS the cluster's store-
compat anchor (it carries the konserve `pom.properties` that stamps the store).

---

## 3. Extension model — the concrete seams

Each seam: the exemplar archetype, the real Seon surface, and **what a downstream
WRITES in its own repo (no fork)**. The unifying principle (Seon's own
code-as-data-runtime + reactive-context): an extension is **a registered fn
referenced by data (a DB entity or a config map)**, never a new mechanism and
never a `src/` edit.

### 3.1 Open schema registry — `schema/register!` + `register-embeddable!`

- **Archetype:** malli `composite-registry` + `mutable-registry` over an atom
  (registry.cljc:54-65); the blessed recipe is `(mr/set-default-registry!
  (mr/composite-registry (m/default-schemas) (mr/mutable-registry *atom*)))`
  (reusable-schemas.md:60-67). Custom *types* via `m/-simple-schema {:type ::t
  :pred p}` / the `IntoSchema` protocol (core.cljc:756,23-28).
- **Seon surface:** `seon.schema/register!` is *already* this exact pattern AND
  is **PUBLIC** (schema.cljc:400) — its body is `(swap! *schemas assoc k …)` over
  the same `defonce` atom that `relink-registry!` (schema.cljc:63-66) wraps in
  `mutable-registry`. It also registers its own `:inst`/`:seon.flow/dynamic` types
  via `-simple-schema` (schema.cljc:75,85) by reaching that identical atom.
- **Downstream writes (NO FORK TODAY):** `(seon.schema/register! :acme.kb/body
  :string)` adds an embeddable attr; `(seon.schema/register! :acme/t
  (m/-simple-schema {:type :acme/t :pred p}))` adds a **custom malli type** — both
  reach `*schemas`, no source edit. `register-embeddable!` (embed.clj:495) makes
  it embedded-on-write: `(register-embeddable! {:seon.embed/trigger-attr
  :acme.kb/body :seon.embed/compose-fn (fn [{:acme.kb/keys [title body]}] …)})`.
  The `my.kb` template (embed.clj:578) is the copy-paste source. **No new
  machinery needed** — the originally-proposed third `*downstream-schemas*` layer
  + public `register-type!` are **dropped** (a verifier proved they are wasted
  work; `register!` already reaches the atom). The ONE real risk (C10): a
  downstream type must survive a self-host `(require 'malli.core)` — it WILL,
  because `relink-registry!` re-asserts over the *same* `*schemas` atom (the
  2026-06-10 stomp fix, schema.cljc:41-60), and `seon.eval` already triggers the
  relink. acme must assert a downstream type still validates *after* an agent eval
  that requires malli.core, and verify `:acme/x` single-segment keys pass
  `assert-multi-segment-namespace!` (CLJS-only, schema.cljc:434).

### 3.2 Integrant components — config-as-data feature add + derive-swap (OPTIONAL/future)

> **Scoping note (verifier-corrected):** the active wire-server has NO integrant
> system — it boots by `require`-for-side-effect (boot.clj:33-45). The acme
> harness already proves no-fork feature-add via plain ns-load side effects (the
> hyperlith `acme.main` require chain: `acme.feature` loads → `defmethod
> handle-op`; `acme.embed-provider` loads → register; `acme.kb` loads →
> `register!`). That is the REAL, sound, simpler seam. The integrant /
> `wire-server.edn` / `derive`-swap story below is **NEW work, demoted to
> optional** — build it only if a consumer needs config-driven component swap.
> The portable integrant lesson is "config-as-data + open dispatch", which
> `handle-op` already satisfies.


- **Archetype:** integrant `init-key`/`halt-key!` open multimethod with `:default`
  → `find-var` (core.cljc:472-494); `#ig/ref` + `derive` + `find-derived` swap an
  impl by data (core.cljc:61-103,167-175); classpath-resource discovery merges
  every `integrant/hierarchy.edn` (core.cljc:271-292) so a third-party jar
  contributes components by *shipping a resource*; `expand-key` modules splat one
  config entry into many components.
- **Seon surface:** the JVM main-app is a textbook integrant system
  (`system.clj` + `resources/system.edn` + `resources/integrant/hierarchy.edn`
  with `ig/assert-key :seon/component` Malli-validating every config) — but it's
  the PAUSED track. The **active wire-server boots by `require`-for-side-effect**
  (boot.clj requires `datahike.index.secondary.proximum`, `seon.embed`,
  `seon.server.reactive` purely to trigger their top-level registration forms).
- **Downstream writes (after fix):** lift the main-app pattern to the wire-server
  — a `wire-server.edn` listing `:seon.server/wire`, `:seon.server/reactive`,
  `:seon.embed/index` as keys with `#ig/ref`s. A consumer ADDS `:acme/feature {…}`
  as one config entry + one `(defmethod ig/init-key :acme/feature …)` in its own
  ns; OVERRIDES the embedding provider with `(derive :acme/local-embedder
  :seon.embed/provider)` + a `#ig/ref :seon.embed/provider`; rebrands cluster
  name/store/sock via `#ig/var`+`bind` or aero `#env` so the values hardcoded in
  wire `-main` parse-args become config. For the JVM uberjar consumer, ship a
  `seon/wire-hierarchy.edn` so a third-party jar on the classpath contributes
  components by resource (the artifact-friendly path). **Caveat:** the pod is CLJS
  — `find-var`/classpath-resource scan don't exist there; the integrant lesson
  that carries to the pod is config-as-data + open-multimethod *dispatch*
  (`handle-op` already does this), not classpath scanning. Keep per-conn scoping
  (Seon's `register-on-ensure-db-hook!` already scopes per-conn; a naive integrant
  port would lose it).

### 3.3 sci-style capability injection — downstream registers tools/fns/sections

- **Archetype:** sci `init` takes ONE opts map (`:namespaces` symbol→fn registry,
  `:classes`, `:load-fn` on-demand resolver, `:allow`/`:deny` allowlist,
  `:interrupt-fn` policy hook); `merge-opts` layers a second opts map onto a live
  ctx (opts.cljc:204); `add-namespace!`/`add-class!` register one capability
  post-init (core.cljc:520-588). clojure-mcp: a tool is a `tool-config` map
  dispatched by a `:tool-type` multimethod set; a consumer passes its own
  `:make-tools-fn` into `build-and-start-mcp-server` (core.clj:541) + EDN
  `:enable`/`:disable` filters.
- **Seon surface:** `seon.render.sci/invoke-bounded` already calls `sci/init` with
  a `:namespaces` map reconstructed from the `:seon.fn` DB index + `:ns-aliases`,
  `:classes {'js js/globalThis}`, and a `:interrupt-fn` (the per-render
  deadline). `seon.ctx/core-default-ctx` (ctx.cljs:1577) is a **registry of
  section maps** whose `:seon.render/ai` slot is a qualified symbol late-resolved
  per render via `seon.eval/lookup-value` (eval.cljs:288) — structurally
  identical to sci's `:namespaces`. `merge-sections` (ctx.cljs:1696) unions core
  defaults with the agent's own `:seon.agent/ctx` vector, **override-by-name**.
  `seon.agent/add-section!` (agent.cljs:1616) is the imperative registrar
  (sci's `add-namespace!`). `seon.eval/guarded-load` (eval.cljs:486-559) is
  Seon's `:load-fn` — on a `require` miss it reconstitutes agent-authored ns
  source from the DB. **Every sci mechanism already exists**; the gap is they read
  *hardcoded sources* (`core-default-ctx` is a literal vector; the
  trust/expose allowlist `agent-authored-sym?`/`exposable-ns?` hardcodes
  `^(seon|clojure|cljs|sci|goog)`, sci.cljs:92-110) and there is **no boot-time
  opts map analogous to sci's `init` arg**.
- **Downstream writes — TWO distinct needs, only one works no-fork today:**
  - **(a) ctx section = WORKS NO-FORK TODAY.** A ctx section is a DB entity + a
    `:seon.render/ai` late-bound symbol, merged override-by-name (ctx.cljs:1696)
    — needs no fork, no recompile, self-heals (inline error line if the fn is
    missing). A consumer adds/overrides an agent context section or UI panel by
    **transacting a `:seon.ctx/section` entity** into `:seon.agent/ctx` pointing
    `:seon.render/ai` at its own `acme.*` fn symbol. The fn routes through the
    SCI-bounded fast path (`agent-authored-sym?` returns true for `acme.*`), which
    is fine for a section. **acme proves this.**
  - **(b) `acme.*` HOST capability = FORKS sci.cljs TODAY.** Registering an
    exposed COMPILED var the agent calls through SCI `:classes`/`:namespaces` (a
    host tool/capability) requires the ns to pass `exposable-ns?`/`agent-authored-sym?`,
    which hardcode the prefix regex `^(seon|clojure|cljs|sci|goog)\.` (sci.cljs:103,110).
    An `acme.*` host capability is NOT exposable without editing sci.cljs.
    **Fix to ship:** make the allowlist a **config atom** (sci's `:allow`-as-data)
    seeded from a downstream-root-prefix env/config, + add a public
    `register-capability!`/`register-tool!` (the `add-namespace!` analog), so
    `acme.*` registers as trusted without editing the regex. Generalize
    `core-default-ctx` to `(concat <base literal> (config extra-sections))` —
    exactly sci's `(merge-with merge defaults host-namespaces)`. The §1 table's
    "capability injection = working archetype" applies to (a), NOT (b).

### 3.4 Provider/transport seam — the embeddings provider (the missing seam)

- **Archetype:** datahike `register-index-type!` registry (secondary.cljc:291);
  konserve `:backend` multimethod (store.cljc:79-117); SDK `ClientOptions` where
  model/dim/baseURL/apiKey are env-defaulted *request params*, not consts
  (anthropic client.ts:302, js-genai `embedContent({model, config:
  {outputDimensionality}})` — **dim is a per-call field, the single source of
  truth flowing request→response**); AnthropicVertex overrides ONLY
  `backendMiddleware()` and reuses every Resource (vertex-sdk/client.ts); nippy
  `extend-freeze`/`extend-thaw` (nippy.clj:1937,1969).
- **Seon surface:** `embed-texts` (embed.clj:661) **already has the right
  map-in/map-out boundary** (`:seon.embed/embed-texts-request {:seon.embed/texts
  [text]}` → `:seon.embed/embed-texts-response {:seon.embed/vectors [vector]}`) —
  the ONLY defect is the body calls `gemini-client` + `embedding-model` directly
  and reads the `1536` const inline (C6). `register-embeddable!` is the *trigger*
  registry to mirror on the *provider* side.
- **Downstream writes (after fix):** add `register-embed-provider!` mirroring
  `register-embeddable!` — a registry keyed by a `SEON_EMBED_PROVIDER` keyword,
  each provider ONE `embed-texts`-shaped fn `(fn [{:seon.embed/keys [texts model
  dim]}] -> {:seon.embed/vectors […]})` carrying its own `:dim`/`:model`. Ship
  `:seon.embed/gemini` as the built-in default (today's body). A consumer
  registers `:acme/local-embedder` from THEIR jar/ns at load — **the acme "custom
  embedding provider" test passes by registration, not fork**. Keep the Proximum
  index, L2-normalization, content-hash datoms, and DB plumbing in core — the
  provider fn is the *only* swappable piece (the AnthropicVertex "one narrow
  override" lesson). `dim` is the single source of truth: pass it through the
  request like js-genai `outputDimensionality`; the index dim must equal the
  provider's output dim (a dim-changing swap requires a reindex). **Blast radius
  of `embedding-dim` being `^:const` (embed.clj:113):** it is baked not only into
  `embed-texts` but ALSO into the Proximum index `:dim` and the `:seon/embedding`
  tuple schema — so a provider with a different dim is NOT just a registration; it
  forces a reindex AND a schema change. De-`const` `embedding-dim` and resolve it
  from the selected provider's `:dim` at index-build + schema-register time, not a
  literal. **Validate at registration:** instrument the registered provider fn
  against
  `:seon.embed/embed-texts-request`/`-response` so a third-party provider returning
  wrong-dim vectors fails LOUDLY at the seam, not deep in the HNSW insert (a
  wrong-dim vector silently corrupts the index). Mirror the same opening for the
  **chat provider** (C7): `register-llm-adapter!` keyed by provider keyword,
  `::provider` → `:qualified-keyword`, consistent with the already-open
  `:seon.agent/llm-fn` injection (agent.cljs:640).

### 3.5 Middleware — wrap wire-server ops / pod turn pipeline

- **Archetype:** nREPL `set-descriptor!` declares `:handles`/`:requires`/`:expects`
  on a middleware var; the server topo-sorts the supplied vector by those
  descriptors (middleware.clj:28-41,189-194); the `describe` op enumerates active
  ops (machine-readable discovery). cider-nrepl ships ops as its OWN jar that the
  host adds to `:middleware`; the `def-wrapper` delay decouples startup cost.
- **Seon surface:** `wire/handle-op` is **already** the open op multimethod
  (`(defmulti handle-op (fn [_conn req] (get req "op")))`, wire.clj:256) — the
  nREPL `:handles` seam, minus descriptors/ordering/`describe`. `boot.clj` adds
  `subscribe-tx`/`register-subscription`, `seon.embed` adds `knn-search`
  (embed.clj:1046), each from a separate ns; **wire.clj requires none of them**.
  The `register-on-ensure-db-hook!` registry (registry.clj:244) is already a
  keyed, idempotent, ordered per-conn listener seam. **The bug (C8):**
  `register-tx-augmenter!` is a SINGLE atom (last-writer-wins) — not composable.
- **Downstream writes (after fix):** add a feature = drop a jar on the `:writer`
  classpath that `(defmethod wire/handle-op "acme-op" …)` in its own ns + (thin
  descriptor) — NOT a wire.clj edit (the hyperlith `defview` self-registration
  pattern, the sanctioned "add a feature" path in acme). **Promote the tx-augmenter
  to a composable ordered registry (mirror `register-on-ensure-db-hook!`
  registry.clj:244 EXACTLY — keyed, idempotent-by-key, first-registration order,
  threaded left-to-right) BEFORE acme can co-exist embed + a downstream
  augmenter.** This is a CORRECTNESS TRAP the acme harness actually hits: the
  moment acme registers its own augmenter, the single `reset!` (wire.clj:217)
  SILENTLY REPLACES seon.embed's embed-on-write and embeddings stop with zero
  error — acme's "custom provider returns hits" and "add a tx-augmenter feature"
  tests are in direct conflict on the same single slot. The exemplar is already
  in-tree (registry.clj), so this is a low-risk port, not new design. Add a thin
  `describe` op + public `register-op!` for discovery. For the pod, wrap
  `run-turn!`/`ask-and-eval!` (agent.cljs) as a registry of `(fn [turn-ctx next]
  …)` interceptors a downstream registers, rather than only the per-agent
  `:seon.agent/llm-fn` injection. **Don't over-engineer:** the descriptor/ordering
  complexity is only warranted once there is >1 augmenter; the cider `def-wrapper`
  delay is only worth it if an op pulls a heavy dep (seon.embed is already lazy via
  the `SEON_EMBED` gate).

### 3.6 Transit codec — carry downstream value types over the wire

- **Archetype:** nippy `extend-freeze`/`extend-thaw` open the serializer with a
  self-describing id + a reader in `*custom-readers*` (nippy.clj:392,1937,1969);
  transit write/read handler maps.
- **Seon surface:** `seon.server.transit` builds bare `:json` writer/reader with
  no `:handlers` (C9, transit.clj:26-41) — closed.
- **Downstream writes (after fix):** add a process-global, **namespaced** transit
  `:handlers` registry threaded through BOTH `write-str` and `read-str` (transit
  write + read handler maps are SEPARATE), exposed as `register-transit-handler!`.
  A consumer calls it at load to carry its own value types. **Namespace the tag
  strings** — but note the failure mode is transit-specific (two downstreams
  choosing the same tag string silently shadow each other, last-writer-wins in the
  handler map), NOT nippy's compile-time id-collision throw. Don't borrow nippy's
  justification; the transit risk is silent shadowing, hence the namespaced-tag
  hygiene.

### 3.7 Config/branding — names/model/paths/SOUL/AGENTS as config, not constants

- **Archetype:** hyperlith `start-app` capability map `{:port :ctx-start :ctx-stop
  :csrf-secret …}` (core.clj:124) + `.env.edn` read as a classpath **resource**
  (env.clj:6) — a downstream supplies its own `.env.edn` on its classpath; SDK
  `ClientOptions` env-defaulted; integrant `#env`/aero readers.
- **Seon surface:** branding is already config-as-data — `seon.web.brand`
  singleton row seeded from `SEON_BRAND_NAME`/`TAGLINE`/`THEME`/`CSS` at boot
  (env owns across boots, retract-on-unset), read fresh at render. `seon.ai`
  seeds a `:seon.ai/config` row from `SEON_AI_*` (env seeds, DB owns). SOUL.md/
  AGENTS.md are **read live, never compiled** — `my.soul/system-prompt-text`
  supplies identity (ai.cljs `effective-system-prompt` reads fresh each call,
  fallback only when absent). `my.soul`/`my.kb` are agent-authored `:seon.ns`
  rows reconstituted by `guarded-load` — the running DB IS the program.
- **Downstream writes:** brand the UI via `SEON_BRAND_*` env + a `SEON_BRAND_CSS`
  file; supply identity via SOUL.md/AGENTS.md files (no recompile). **Fix to ship
  (C13):** collapse the scattered defaults into ONE provider-capability table
  `{provider-key {:key-env-var :base-url :adapter}}` consumed by both runtimes
  (replacing `ANTHROPIC_API_KEY`/`DEEPSEEK_API_KEY`/`GEMINI_API_KEY` probes +
  hardcoded `:deepseek`/`:anthropic` defaults), and read a `seon-config.edn`
  classpath **resource** the consumer overrides from its own `resources/` (the
  hyperlith pattern) — but keep Seon's **lazy, absence-tolerant** runtime reads
  (do NOT adopt hyperlith's compile-time `env` macro that fails on a missing key;
  `GEMINI_API_KEY` must boot ABSENT and silently no-op). Turn `embedding-model`,
  `embedding-dim`, the HNSW params, and any base-URL into fields of one
  `:seon.embed/config` map resolved from env with defaults — killing the
  hardcoded-provider/name/path coupling. **Audit that every internal read honors
  the injected value** (the hyperlith `throw-if-port-in-use! 8080`-ignores-`:port`
  bug, core.clj:116/129, is the cautionary tale: config-as-data only works if
  EVERY read uses the config, not just the top-level boot).

**Promote `bin/seon` to an artifact launcher.** `bin/seon` is already an
env-parameterized supervisor (`SEON_CLUSTER_DIR`/`REQ_SOCK`/`PUB_SOCK`/`PORT`/
`RUNTIME_ROOT`/`EXTRA_*`, bin/seon:96-113,141-157; header: "A downstream consumer
shells out to bin/seon with env instead of forking the script"). Once the uberjar
exists, make `process_command` emit `java -jar seon-wire-server-standalone.jar`
for the wire-server instead of `clojure -M:writer` — so a downstream supervisor
boots both runtimes against artifacts, never resolving source/forks/prep. Either
ship a published `bin/seon` or move its flag-injection logic into documented config
the consumer controls (else the consumer stays coupled to Seon's scripts — C14).

---

## 4. The ACME clean-room test (the falsification harness)

A fresh project at **`/Users/sean/src/seon/acme`** (gitignored, or a sibling repo)
that depends ONLY on published artifacts. If acme needs to touch Seon's `src/` for
any step, that step is a coupling bug.

> **HONESTY NOTE — acme as written below CANNOT run today.** Three concrete
> defects must land before any of it executes: (1) `--preflight` does not exist
> and `parse-args` exits 2 on it (C15); (2) the standalone uberjar has no
> `acme.embed-provider` on its classpath, so `SEON_EMBED_PROVIDER=acme/local`
> finds no provider — the custom-provider test MUST use the lib-jar + `acme.main`
> (C18); (3) the pod's `SEON_EMBED` master gate (agent.cljs:922) must be exported
> in the pod env or the pod never queries (C16). The commands and snippets below
> are the TARGET state; the §4 table marks which rows are RED today.

### Files acme ships (entirely in its own tree)

```
acme/
  deps.edn
  shadow-cljs.edn
  package.json
  resources/
    seon-config.edn          ; branding + provider defaults (P2)
    SOUL.md  AGENTS.md        ; identity, read live
    acme-brand.css            ; SEON_BRAND_CSS target
  src/
    acme/main.clj            ; wire-server entry: AOT'd, calls start-wire-server
    acme/feature.clj         ; (defmethod wire/handle-op "acme-stats" …) — the new wire op
    acme/embed_provider.clj  ; (register-embed-provider! :acme/local …) — custom provider
    acme/kb.clj              ; (schema/register! :acme.kb/body …) + (register-embeddable! …)
    acme/pod.cljs            ; pod preload: resets !extra-core-vars; a ctx-section fn
```

`acme/deps.edn` (artifact-mode — NO `reference-code/`, NO submodule, NO shim path,
NO `:git/sha`):

```clojure
{:deps {org.seon/seon-wire-server      {:mvn/version "0.1.N"}
        org.seon/seon-datahike-proximum {:mvn/version "0.1.N"}
        org.seon/seon-core             {:mvn/version "0.1.N"}}
 :aliases
 {:writer {:jvm-opts ["--add-modules" "jdk.incubator.vector"
                      "--enable-native-access=ALL-UNNAMED" "-XX:+UseG1GC" "-Xmx2g"]
           :main-opts ["-m" "acme.main"]}
  :cljs   {:extra-deps {org.seon/seon-pod {:mvn/version "0.1.N"}
                        thheller/shadow-cljs {:mvn/version "3.4.10"}
                        org.clojure/clojurescript {:mvn/version "1.12.145"}}}
  :build  {:deps {io.github.clojure/tools.build {:mvn/version "0.10.5"}}
           :ns-default build}}}
```

`acme/main.clj` (~12 lines, the hyperlith model):

```clojure
(ns acme.main
  (:require [seon.server.boot :as boot]
            [acme.feature]          ; loads → defmethod wire/handle-op "acme-stats"
            [acme.embed-provider]   ; loads → register-embed-provider! :acme/local
            [acme.kb]))             ; loads → schema/register! + register-embeddable!
(defn -main [& args] (apply boot/-main args))
```

`acme/embed_provider.clj` (the custom provider — registration, not fork):

```clojure
(ns acme.embed-provider
  (:require [seon.embed :as embed]))
(embed/register-embed-provider!
  {:seon.embed/provider :acme/local
   :seon.embed/dim 1536
   :seon.embed/embed-texts                       ; one embed-texts-shaped fn
   (fn [{:seon.embed/keys [texts]}]
     {:seon.embed/vectors (mapv my-local-model/embed texts)})})
```

`acme/pod.cljs` (preload — branding/feature via the existing overlay seam):

```clojure
(ns acme.pod
  (:require [seon.client :as client])
  (:require-macros [seon.indexing :refer [specced-fn-vars]]))  ; MACRO, not a runtime fn (C17)
(reset! client/!extra-core-vars
        (filterv #(re-find #"^acme\." (str %)) (specced-fn-vars)))
;; acme.feature/ctx-section is a specced fn referenced by a :seon.ctx/section the
;; consumer transacts onto :seon.agent/ctx with :seon.render/ai 'acme.feature/ctx-section
;; NOTE: seon-pod (or seon-core) MUST ship the .clj macro ns seon.indexing for this
;; :require-macros to resolve from a published jar (C17).
```

### Exact commands

```bash
# 1a. wire-server, GEMINI provider, run-as-is from the standalone uberjar
#     (the uberjar has NO acme.embed-provider on its classpath — Gemini only, C18)
export SEON_EMBED=1 GEMINI_API_KEY=…
export SEON_CLUSTER_DIR=/var/acme/cluster
export SEON_REQ_SOCK="$SEON_CLUSTER_DIR/req.sock" SEON_PUB_SOCK="$SEON_CLUSTER_DIR/pub.sock"
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
     -XX:+UseG1GC -Xmx2g -jar seon-wire-server-standalone.jar \
     --backend file --path "$SEON_CLUSTER_DIR/store" \
     --req-sock "$SEON_REQ_SOCK" --pub-sock "$SEON_PUB_SOCK" --preflight   # LOUD gate (C15: must exist)

# 1b. wire-server, CUSTOM provider — REQUIRES the lib-jar + acme.main (NOT the
#     standalone uberjar): acme.main's require chain loads acme.embed-provider so
#     its register-embed-provider! runs at load. (C18)
export SEON_EMBED=1 SEON_EMBED_PROVIDER=acme/local
java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED \
     -XX:+UseG1GC -Xmx2g -cp "$(clojure -A:writer -Spath):acme.jar" -m acme.main \
     --backend file --path "$SEON_CLUSTER_DIR/store" \
     --req-sock "$SEON_REQ_SOCK" --pub-sock "$SEON_PUB_SOCK" --preflight

# 2. build the pod (acme owns the shadow build; seon-pod is a dep)
clj -M:cljs release acme-pod          # :node-script, :main acme.pod, :optimizations :none/:simple

# 3. pod — SEON_EMBED is a TWO-process master switch: it MUST be exported here too,
#    or the pod's embed-retrieval-on? (agent.cljs:922) short-circuits and never
#    queries KNN (C16). The pod needs NO GEMINI_API_KEY (it sends query TEXT over
#    the wire; the wire-server embeds). Drop SEON_PUB_SOCK — the pod consumes only
#    the REQ socket + store-path. Relocation via env is RED until C11 lands.
export SEON_EMBED=1
export SEON_BRAND_NAME=Acme SEON_BRAND_CSS=$PWD/resources/acme-brand.css
export SEON_AI_PROVIDER=deepseek SEON_REQ_SOCK SEON_CLUSTER_DIR
node out/acme-pod/main.js
```

### Steps that would currently FAIL (the coupling bugs acme exposes)

| acme step | Status today | Fails because | Phase that fixes |
|-----------|--------------|---------------|------------------|
| **Boot wire-server from a jar** | RED | no uberjar exists; consumer would need the checkout + submodule + shim + prep | C1/C2/C3/C4 → **P0** |
| **`deps.edn` with no `:git/sha`/submodule/shim** | RED | datahike-proximum only reachable via submodule + `:extra-paths`; konserve needs the shim | C2/C3/C5 → **P1** |
| **`--preflight` exits 0/non-zero (proves embeddings booted)** | RED | flag does NOT exist; `parse-args` exits 2 on it (C15); no preflight ns; four silent degrade-to-no-hits modes uncaught | C15 → **P0** (ship with the uberjar) |
| **`register-embed-provider! :acme/local`** | RED | **fn does not exist** — Gemini is hardwired in `embed-texts`; a custom provider requires forking embed.clj | C6 → **P2** |
| **Custom provider serves KNN from the standalone uberjar** | RED (impossible) | the standalone uberjar has no `acme.embed-provider` on its classpath — custom provider MUST use the lib-jar + `acme.main` (C18) | C18 → **P2** (lib-jar path) |
| **Relocate sockets/store via env on the POD** | RED | pod paths are compile-time constants (`wire_node.cljs:50`, `wire.cljs:62`); pod connects to baked default, reads empty store; store-id hashes the wrong basename (C12) | C11 → **P0/P3** (pod env read) |
| **Pod actually queries KNN end-to-end** | RED (as written) | pod's `SEON_EMBED` master gate (agent.cljs:922) not exported in acme step 3 → pod short-circuits, never queries (C16) | C16 → **P0** (acme env wiring) |
| **`pod.cljs` calls `specced-fn-vars`** | RED (as written) | it is a `defmacro` (indexing.clj:86), needs `:require-macros`; snippet won't compile (C17) | C17 → **P3** (jar carries the macro ns) |
| **`(defmethod wire/handle-op "acme-stats")` from acme jar** | GREEN (the op) / RED (coexist) | the op WORKS (handle-op is open); but a 2nd tx-augmenter SILENTLY CLOBBERS embed's (single-slot atom, wire.clj:215) — embed + acme-augmenter conflict (C8); no `describe`/`register-op!` discovery | C8 → **P2** (augmenter stack) |
| **`schema/register! :acme.kb/body` + custom malli type** | GREEN | `register!` is PUBLIC (schema.cljc:400) — both attr AND custom type work TODAY, no fork. Only risk: survives a self-host malli reload (relink re-asserts the same atom — it does) | C10 → **P2** (test-only assertion) |
| **`acme.*` ctx section (agent context surface)** | GREEN | a `:seon.ctx/section` DB entity + late-bound `:seon.render/ai` symbol works no-fork; routes the SCI fast path (§3.3a) | — (works) |
| **`acme.*` HOST capability (exposed compiled var)** | RED | `exposable-ns?`/`agent-authored-sym?` hardcode `^(seon\|clojure\|cljs\|sci\|goog)\.` (sci.cljs:103,110); an `acme.*` host capability forks sci.cljs (§3.3b) | §3.3 → **P2** (config-driven allowlist) |
| **Pod overlay from a published jar (not `:local/root`)** | RED | extension is build-time recompile inside the seon tree; no artifact-mode overlay | C14 → **P3** |
| **Brand via `seon-config.edn` resource** | PARTIAL | `SEON_BRAND_*` env + SOUL.md/AGENTS.md work TODAY (config-as-data); but no `seon-config.edn` resource; provider/model/path defaults scattered, some hardcoded | C13 → **P2** |
| **Java<22 / no `jdk.incubator.vector` caught at boot** | RED | no runtime version/module check (embed.clj has none); proximum fails deep in native SIMD at first index op, not at boot | C15/preflight → **P0** |
| **Extensions survive a wire-server restart** | GREEN (via require-chain) | registries are in-process atoms (die with process); acme.main re-runs registration on every boot via the require chain → durable. Anything registered only at runtime is lost | open-question → **P3** (persist as DB entities?) |

acme is green when every row above passes **without an edit under
`/Users/sean/src/seon/src`** and acme's `deps.edn` contains **zero** of:
`reference-code/`, `:git/sha`, `dev-resources/konserve-shim`,
`-X:deps prep`.

**Smallest-acme that still proves the thesis (run this FIRST — the big acme
bundles 6 unproven seams, so one failure is ambiguous).** Several rows above are
already GREEN by reading source (handle-op op, `schema/register!` attr+type, ctx
section) — those need a unit assertion, not the full harness. The minimal
falsifier is THREE steps that each isolate a distinct coupling class:

1. **`acme/deps.edn` with ZERO of `{reference-code/, :git/sha, konserve-shim,
   -X:deps prep}` resolves on a CLEAN checkout** — falsifies C1–C5 (artifact
   existence). Pure dependency-resolution test, no boot.
2. **`java -jar seon-wire-server-standalone.jar --preflight` exits 0 with a real
   Gemini round-trip + a one-row `install!` + KNN top-1**; AND a NEGATIVE case
   (boot WITHOUT `--add-modules` → asserts non-zero with a distinct code) —
   falsifies the four silent modes (C15) and proves C2/C3/C4 baked.
3. **Pod boots against a RELOCATED req-sock+store via env and observes ONE
   pod-side KNN hit** (e.g. the `:relevant-source` ctx section renders a hit) —
   falsifies C11 + C16 + proves both runtimes + embeddings end-to-end.

The custom embed PROVIDER (`register-embed-provider!`, C6/C18) is the only NEW
mechanism that strictly needs acme to prove — gate it behind P2 with the lib-jar
path, not the P0/P1 falsifier. Brand/feature/schema-type are confirmable by
reading source (most already GREEN), so they ride as P2 add-ons.

---

## 5. Phased roadmap (easiest-first)

### P0 — wire-server uberjar + loud preflight (testable THIS week)

- **Sean changes:** add a self-contained `writer-uber` artifact fn to `build.clj`
  off the `:writer` basis with `:main 'seon.server.boot`, using LOCAL basis +
  class-dir (refactor the existing globals — C19; build.clj currently does the
  wrong `:main 'seon.core` and shares one basis/`clean`); add the `--preflight`
  PLUMBING that does NOT exist today (C15): a `--preflight` case in
  `seon.server.wire/parse-args` (wire.clj:34-48 currently `(System/exit 2)` on
  unknown), a `seon.embed.preflight` ns, and a branch in `seon.server.boot/-main`
  (boot.clj:251-257) that runs preflight with DISTINCT non-zero exit codes per
  failure (3=Java<22/no `jdk.incubator.vector`, 4=`SEON_EMBED` unset,
  5=`GEMINI_API_KEY` blank, 6=embed round-trip wrong-dim, 7=KNN self-test miss);
  read `process.env` in the POD at BOTH sites — `wire_node.cljs:50`
  `default-req-sock` ← `SEON_REQ_SOCK`, `wire.cljs:62` `default-store-path` ←
  `<SEON_CLUSTER_DIR>/store` — and make `store-id` (wire.cljs:67) hash the
  RELOCATED basename (C11; wire-server is already relocatable via CLI args — pod
  only). Wire `SEON_EMBED` into the acme pod env (C16 — two-process master switch).
- **acme proves (smallest-acme steps 2+3):** `java -jar … --preflight` exits 0
  against a consumer `SEON_CLUSTER_DIR` AND non-zero (distinct code) without
  `--add-modules`; a one-row install + KNN top-1 self-test passes; the pod
  connects to a RELOCATED socket and observes one pod-side KNN hit. (The P2
  provider seam isn't required yet — preflight uses Gemini.) **Three owed build
  checks, all UNVERIFIED until the jar is built (see §6):** (1) `clojure -X:deps
  prep :aliases [:writer]` lands `DatahikeGenerated.java`; (2) the shim
  `META-INF/maven/.../pom.properties` survives `b/uber` and `get-version` reads
  `0.9.347` from inside the jar; (3) boot.clj's require chain loads
  `datahike.index.secondary.proximum` at jar RUNTIME so `register-index-type!`
  runs (NOT "AOT registers it" — AOT only precompiles + surfaces missing deps).

### P1 — lib-ization: companion artifact + tags + shim kill

- **Sean changes:** publish `seon-datahike-proximum` (thin jar, only the secondary
  namespaces, deps datahike-fork + proximum) → **delete the `reference-code/
  datahike` submodule + the `src-secondary` `:extra-paths`** (C2); deploy the
  konserve fork as `org.replikativ/konserve 0.9.347` (real mvn) → **delete
  `dev-resources/konserve-shim`**, flip `:override-deps` to `:mvn/version` (C3);
  **tag** all four fork SHAs immutably and Clojars-deploy datahike + konserve so
  `:git/sha` → `:mvn/version` (C5); document `clojure -X:deps prep` for the
  source-level (non-uberjar) consumer path (C4); scrub the stale `1ae35696`
  comments in `deps.edn:142-151,184` when the submodule is deleted (the actual
  pins + submodule are all `5f62d57f`). **Lib-jar coupling (C20):** state
  explicitly that ONLY the uberjar retires C2/C3/C4/C5 for the consumer pre-P1 —
  the `seon-wire-server` LIB jar inherits datahike's git-dep + `:deps/prep-lib`
  (the fork's `:paths` is `target/classes`), so prep + the mutable SHA persist for
  lib-jar consumers until THIS phase ships datahike/konserve as real mvn. Sequence
  the acme lib-jar / custom-provider test AFTER P1.
- **acme proves:** `acme/deps.edn` resolves with **no** `reference-code/`, **no**
  submodule, **no** shim path, **no** `:git/sha`; a clean machine builds without
  `--recurse-submodules`.

### P2 — the extension API (registry / integrant / provider / capability seams)

- **Sean changes:** `register-embed-provider!` (C6, the acme-critical seam) with
  registration-time Malli validation, `:seon.embed/gemini` as the default impl,
  and `embedding-dim` **de-`const`ed** so `dim` flows from the selected provider
  to the index `:dim` + the `:seon/embedding` tuple schema (a dim change = reindex
  + schema change); `register-llm-adapter!` + `::provider` → `:qualified-keyword`
  (C7); promote `register-tx-augmenter!` to a composable ordered registry (mirror
  `register-on-ensure-db-hook!` registry.clj:244 — **must land before acme can
  coexist embed + a downstream augmenter**, C8) + add a `describe` op + public
  `register-op!`; open `seon.server.transit` with a namespaced `:handlers` registry
  threaded through BOTH write/read (C9); collapse provider/name/path defaults into
  one capability table + a `seon-config.edn` resource, keeping lazy/absence-tolerant
  reads (C13); make the sci trust/expose allowlist config-driven (sci `:allow`-as-
  data) + add `register-capability!` so an `acme.*` HOST capability registers
  without forking the prefix regex (§3.3b). **DROPPED from this phase (verifier-
  corrected):** the schema third-layer + `register-type!` (C10 — `register!` is
  already public; nothing to build); the wire-server `wire-server.edn` integrant
  system (§3.2 — demoted to optional; ns-load side effects already work). The
  custom-provider acme proof runs on the LIB-jar + `acme.main` path, NOT the
  standalone uberjar (C18), and is sequenced after P1's mvn deploy (C20).
- **acme proves:** `register-embed-provider! :acme/local` + `SEON_EMBED_PROVIDER=
  acme/local` (lib-jar + `acme.main`) → KNN returns hits from acme's vectors (no
  Gemini); a wrong-dim provider fails LOUDLY at registration; `(defmethod
  wire/handle-op "acme-stats")` is discoverable via `describe` and coexists with
  embed's augmenter (no clobber); `schema/register! :acme.kb/body` + a custom type
  survive an agent eval that requires malli.core (test-only, already works); an
  `acme.*` host capability is callable from a bounded agent eval; branding comes
  from `seon-config.edn` + env.

### P3 — pod artifact + full acme green

- **Sean changes:** publish `seon-pod` as a CLJS **source jar** (raw `.cljs` +
  `.cljc` + a NEW seon-root `deps.cljs` declaring `:externs ["externs/node_fs.js"]`
  + the `externs/` dir + **the `.clj`/`.cljc` macro namespaces that `src/seon/**.cljs`
  pull via `:require-macros`** — `seon.indexing` at minimum, audited across all
  `:require-macros`, else the consumer's build breaks on `specced-fn-vars`, C17),
  + an `.mjs` shim (§2b); add a build hook that throws on `:optimizations
  :advanced`; echo the store `:id` on `ping` so the pod asserts store agreement at
  boot (C12); promote `SEON_EXTRA_SRC`/`PRELOAD` to artifact-mode (the downstream
  depends on `seon-pod` + adds its own src path; the filesystem-overrides-jar rule
  already supports add + override) and move `bin/seon`'s flag-injection into
  documented config (C14); `bin/seon` `process_command` emits `java -jar` for the
  wire-server (§3.7).
- **acme proves:** acme owns its `shadow-cljs.edn` `:node-script` build with
  `seon-pod` as a dep; `acme.pod` overrides a Seon ns AND adds `acme.feature`;
  branding (name/CSS/SOUL) lands; both runtimes boot against consumer-chosen
  sockets/store; the §4 table is all-green with zero edits under `src/`.

---

## 6. Verification status

What is grounded in actual source vs. what still needs a live build/boot spike.
Three adversarial verifiers (artifact-buildability, extension-without-fork,
acme-falsification) attacked the draft; this section records the residue.

### Proven by source (read + cited)

- **`register!` is PUBLIC** (schema.cljc:400) — a downstream adds an attr AND a
  custom malli type with no fork. (Corrected C10; dropped the third-layer machinery.)
- **`wire/handle-op` is an open defmulti** (wire.clj:256) — a downstream jar adds a
  wire op with no wire.clj edit.
- **A ctx section works no-fork** — DB entity + late-bound `:seon.render/ai` symbol,
  merged override-by-name (ctx.cljs:1696, eval.cljs:288).
- **`embed-texts` has a clean map-in/map-out boundary** (embed.clj:661) but the body
  hardwires Gemini + the `1536` `^:const` (embed.clj:113) — no provider registry exists.
- **`register-tx-augmenter!` is a single-slot `reset!` atom** (wire.clj:215-217) —
  a 2nd augmenter silently clobbers embed-on-write (the acme correctness trap, C8).
- **`--preflight` does not exist** — `parse-args` `(System/exit 2)` on unknown args
  (wire.clj:34-48); no preflight ns anywhere (C15).
- **Pod paths are compile-time constants** — `wire_node.cljs:50`, `wire.cljs:62`, no
  `process.env`; wire-server reads CLI args (relocatable already), pod does not (C11).
- **Pod has its OWN `SEON_EMBED` gate** — `embed-retrieval-on?` (agent.cljs:922)
  short-circuits KNN when unset; acme must export it on the pod too (C16).
- **`specced-fn-vars` is a `defmacro`** (indexing.clj:86), pulled via `:require-macros`
  (client.cljs:173) — the acme `pod.cljs` snippet was wrong (C17).
- **`:proximum` is registered UNqualified** (proximum.clj:233-234) so `create-index`
  does NOT auto-require it (secondary.cljc:312-320 gates on `qualified-keyword?`);
  boot.clj:42 + embed.clj:82 carry the mandatory explicit require (corrected C2).
- **`build.clj` builds the wrong basis + `:main 'seon.core`** (build.clj:7,35), one
  global basis/`clean` — needs a per-artifact refactor (C19).
- **The standalone uberjar has no `acme.*` on its classpath** — a custom provider
  needs the lib-jar + `acme.main`, not the standalone jar (C18).
- **konserve fork is git-only in `~/.gitlibs`, no competing real pom.properties** —
  so the shim META-INF won't CONFLICT on uber-merge; `:writer` git-dep at `5f62d57f`
  already has a prepped `target/` (C4 prep DOES run for `:deps/prep-lib` git-deps).
- **`boot.clj` has `(:gen-class)`** (boot.clj:58) — `:main 'seon.server.boot` for the
  uber is valid.

### UNVERIFIED — needs a build spike (do NOT ship the P0 jar without running these)

1. **Does `b/uber`'s default merge keep the hand-authored shim
   `META-INF/maven/org.replikativ/konserve/pom.properties`?** Run:
   `unzip -p target/seon-wire-server-standalone.jar META-INF/maven/org.replikativ/konserve/pom.properties`
   AND boot the jar and confirm `datahike.tools/get-version` returns `0.9.347` from
   inside the jar (tools.cljc:117-126 `io/resource` path). The shim → version-check
   pass (C3) load-bears on this and it is not provable in-repo.
2. **Does `clojure -X:deps prep :aliases [:writer]` actually run the fork's
   `:deps/prep-lib` and land `DatahikeGenerated.java` on the uber classpath?** The
   gitlib has a prepped `target/`, but confirm tools.build's basis prep triggers it
   for the uber build specifically (C4).
3. **Does boot.clj's require chain load `datahike.index.secondary.proximum` at jar
   RUNTIME so `register-index-type! :proximum` runs?** (NOT "AOT registers it" —
   AOT only precompiles + surfaces missing optional deps at build time; registration
   is a load-time top-level form. Verify it is not DCE'd and runs from `-main`.) (C2)
4. **Does `seon-core`/`seon-pod` carry every `.clj`/`.cljc` macro ns reached by
   `:require-macros` in `src/seon/**.cljs`?** Grep all `:require-macros`, confirm each
   backing ns ships in the jar; a missing one breaks the consumer build (C17).
5. **Does the LIB-jar consumer path actually inherit datahike's prep + SHA (C20)?**
   Build the lib jar, consume it from a clean machine, confirm whether `clojure
   -X:deps prep` is still required and whether the mutable SHA is still referenced.

These five are the gate between "design" and "P0 done". The first three are the
"three owed checks" named throughout; all are a few minutes of live build/boot.

---

## Open questions

- **`seon-core` boundary precision.** Which `.cljc` are *genuinely* portable vs.
  have a live `.clj`/`.cljs` sibling that shouldn't be in a shared jar? (CLAUDE.md
  lane discipline: promote to `.cljc` only when both tracks converge.) `seon.schema`
  + `seon.instrument` are named as portable; audit the rest before drawing the jar.
- **Konserve fork deploy remote.** The fork is noted "no pushable remote"
  (deps.edn:196-199). Where does `org.replikativ/konserve 0.9.347` deploy from —
  a private mvn repo, a Clojars account, or upstream? P1's shim-kill depends on this.
- **Multi-provider / multi-dim on one wire-server.** The embed config is
  process-global; one wire-server serves one provider/dim at a time. Multiple
  providers/dims would need either multiple Proximum indexes or a per-attr provider
  key — a real design cost to weigh if acme (or a real consumer) ever mixes dims.
- **Pod CLJS extension durability.** Registries are in-process atoms that die with
  the process; only the schema registry has a DB tee (`set-tee-fn!`, schema.cljc:390).
  Should `register-embed-provider!`/`register-op!`/`register-llm-adapter!` persist
  as DB entities (generalizing the tee) so "extensions as DB entities" is real and a
  consumer's extensions boot from the store, or stay re-run-at-ns-load? The
  code-as-data-runtime principle argues for the former; weigh against churn.
- **`:advanced` ever?** The override/late-binding contract forbids `:advanced` for
  the pod. Is there a future release path (a frozen, non-agent-redefinable pod) that
  *could* ship `:advanced`, or is `:none`/`:simple` permanent for the agent runtime?
