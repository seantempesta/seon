---
type: research
status: point-in-time audit at HEAD 995b1de40 plus the shared working tree (2026-09-23, lane fork-audit-others, Opus 5.5); re-derive fork state before acting
created: 2026-09-23
tags: [agent-platform, forks, dependencies, sci, konserve, malli, clj-kondo, http-kit, simple-made-easy]
owner: orchestrator (removal slices); holders per tmp/orchestrator/file-ownership.md
---

# Fork audit — every non-Datahike fork, 2026-09-23

Lane fork-audit-others (Opus 5.5), read-only for code. Owner's instruction for this audit
(2026-09-23): "look for more custom shit we introduced and analyze if it's stupid and if it is
rip it out and fix the seam on our end." Follow-up: "Konserve has had some legit bugs we've
solved, but I think agents have been lazy and just hacking their own easy solutions rather than
doing things properly."

The test applied to every commit:

- **KEEP**: a real upstream bug, or a real gap in the library, fixed where it originates. The
  row shows the bug, and says whether it could go upstream as a PR (the owner decides).
- **RIP**: a workaround for one Seon caller, a change that bends the library's contract,
  something the library already does, or dead code. The row names the right fix: the
  library's intended API, or a Seon-side change with its `file:line`.
- **UPSTREAMED**: upstream has since fixed it; use theirs.

Datahike is covered by the fork-audit-datahike lane. superv.async is a new fork held by lane
store-damage, so it is not audited here.

## Method and upstream refs

For each submodule I fetched upstream read-only into `refs/remotes/audit-upstream/*`. Nothing
was pushed, no PR was opened, and no working tree was touched. I then took
`git merge-base HEAD audit-upstream/<branch>` and listed our commits. Seon callers were found
with `rg` over `src/ test/ script/`. "Live" facts come from the default JVM through MCP
`eval_clj` in JVM mode, using a private session and read-only disposable `sci/init` contexts.
No default Var was redefined.

| fork | upstream | merge-base | our commits | upstream commits since | clean merge onto upstream? |
|---|---|---|---|---|---|
| sci (`seon-env-hook`) | babashka/sci master 387d1162 | be4021d3 (2026-07-17) | 17 | 31 | no: analyzer.cljc, namespaces.cljc conflict |
| konserve (`main`) | replikativ/konserve main 485c233 | 060b7bb (2026-07-13) | 9 (incl. revert 5b39fdd) | 39 | no: 10 files conflict |
| malli (`seon-ref-scope`) | metosin/malli master fd5fe067 | 80138076 (2025-12-15) | 5 | 85 | yes |
| clj-kondo | clj-kondo master 703a44cc | 794a508d (2026.07.24) | 2 | 28 | yes |
| babashka-process (`seon-aot-guard`) | babashka/process master 43bdd65 | 16a84e0 (v0.6.25) | 1 | 1 (docstring) | yes |
| http-kit | http-kit master 7f8c701 | 70432d3 (v2.9.0-beta2) | 1 | 37 (incl. 2026-07-30 `[sec]` series) | no: AsyncChannel, HttpServer, test suite conflict |
| core.async.flow-monitor | clojure main 421d56c | 376d6ec (v0.1.5) | 1 | 1 (README) | yes |
| proximum (git dep, `deps.edn:47-50`) | replikativ/proximum d802a8e | c1235796 | 1 (9846d3e) | — | not vendored, and not loaded (see below) |

## BROKEN NOW (read first)

1. **HIGH, durability: default still runs the reverted konserve multi-key filestore.** Live
   probe: `(str (io/resource "konserve/gc.cljc"))` gives
   `…/.gitlibs/libs/org.replikativ/konserve/8cd9144…/src/konserve/gc.cljc`, and
   `(satisfies? konserve.impl.storage-layout/PMultiWriteBackingStore (konserve.filestore/map->BackingFilestore {}))`
   gives `true`. The pin `8cd9144` contains 737697d
   (`git merge-base --is-ancestor 737697d 8cd9144` succeeds). The revert 5b39fdd is on
   `origin/main` of the fork, but nothing on the classpath uses it. Lane store-damage has an
   **uncommitted** `deps.edn` change to `5b39fdd…`. Once that is committed, it only takes
   effect after `bin/seon start --head` (the orchestrator's action). Until then, every
   Datahike commit on default takes the multi-key path that broke upstream's atomicity
   invariant. Upstream f1d1be9 (#173), "Say that multi-key means ATOMIC", is the invariant
   our 737697d rewrote: its `core.cljc` docstring change was "Atomically retrieves" →
   "Retrieves … in one backend operation".
2. **HIGH, durability and error handling: our konserve fork is missing 39 upstream commits.**
   Among them:
   - 9508fb4 (#190): directory sync required by default.
   - 72cc22d (#192): durable-parent barriers.
   - 88eaa80 (#186): "stop returning exceptions as values". This is the same class as today's
     superv.async Error finding.
   - 2937a87 (#187): close the target before the move.
   - 596b5ec (#156): monotonic GC write stamps.
   - b812eb2 (#159): the store owns the GC safe point.
3. **HIGH, security: our http-kit is on v2.9.0-beta2 plus one commit.** It misses upstream's
   2026-07-30 server hardening: `[sec]` eb60ccf, 7876af2, 3c6f1bd, 7046202; `[fix]` c4a6ff4,
   4a37a56; and more.

## Per-commit verdicts

### sci — 17 commits: 5 KEEP, 12 RIP

| commit | what it changes | problem / Seon caller | upstream since | verdict |
|---|---|---|---|---|
| 8fac6e88 | `resolve.cljc:329-334`: "Unable to resolve symbol" ex-data gains `:sci.impl/symbol` | `kernel.clj:577-581` reads it for `:seon.error/offending`. Without it Seon would have to parse the message (a regex). Live: `(ex-data …)` → `{:phase "analysis" :sci.impl/symbol no-such-sym}` | upstream still `{:phase "analysis"}` only (`resolve.cljc` at 387d1162) | **KEEP**, upstream-PR candidate. Bug: an analysis error names its offending symbol only in prose. |
| 2217449d | `core.cljc`: `namespace-bindings` / `install-namespace-bindings!` (read and install aliases and refers as symbols) | `eval.clj:489` reads an evaluated `require` back into namespace facts (`binding-rows`, `eval.clj:551`). `eval.clj:736,987,2092,2119` install program rows into a context without loading | no public equivalent upstream (`sci/add-namespace!` installs interns only) | **KEEP** as an embedder API (a gap, not a bug). The install side resolves SCI Var objects and the class table; that belongs next to SCI's structures. PR candidate. The alternative, one `sci/eval-form` of `(alias …)` / `(refer …)` per binding, analyzes every binding on every install. |
| 98457e88 | `load.cljc:103-110`: every `require` records `:required-namespaces` in the namespace map; `clean-ns` strips it | feeds `:seon.ns/requires` (`eval.clj:556`) | none | **RIP**. Two derivations of one fact: `fn.clj:246-277` (`namespace-context`) already derives `:requires` from the source form. Seon fix: derive an evaluated form's requires with the same clause logic, or read SCI's own per-context `*loaded-libs*` (`load.cljc:150` upstream). |
| 1ed03a69 | `core.cljc`: `namespace-interns` (keys minus resolver structure) | `eval.clj:365,2900,3430`; tests | none | **RIP**. It is a filter over `(:namespaces @(:env ctx))`, and Seon already reads `@(:env ctx)` (`eval.clj:381`). Move it into a 3-line private Seon fn. |
| d84a0106 | `core.cljc`: `namespace-state` = `(:namespaces @(:env ctx))`; `install-namespace-state!` | reader at `eval.clj:351,379,2216,2235,2267`, `test/runner.clj:442`; the installer has **0 callers** | none | **RIP**. One-liner plus dead code. |
| 1305a90a | `namespace-bindings` / install gain `:imports` (with class-table validation) | row-bindings `eval.clj:587-591` | none | **KEEP**, together with 2217449d (same API). |
| 937d392a | `namespaces.cljc`: +181 lines re-implementing 1.11 `abs parse-* update-keys update-vals iteration NaN? infinite? random-uuid` | agents calling 1.11 core fns | upstream targets 1.10, so they are absent | **RIP**. The intended way is the embedder's `:namespaces` overlay with `sci/copy-var` of the host fns. That is exactly what babashka does (`reference-code/babashka/src/babashka/impl/clojure/core.clj:215-224`), and Seon already copies host Vars this way (`eval.clj:195-216`, `copy-var*`). The Seon change is about 11 symbols in the injected set. Seon runs Clojure 1.12.5 (`deps.edn:13`), so the host fns exist. |
| 47f6c8b5 | `analyzer.cljc`: `observe-host-interop!` at every interop analysis site | `kernel.clj:84-86` increments a counter, stored as `:seon.eval/host-interop-count` (`kernel.clj:204`, `seon.eval.edn:4`, `:seon.wake/context-inert`) | none | **RIP**. It is a write-only metric that nothing decides from, and it counts analysis sites, not executed interop. Seon fix: delete the observer and retire the attribute (1.3e: writer `kernel.clj:204`, `seon.sci.admit.edn:41`, test fixtures). |
| a27e2c0e | `analyzer.cljc` `return-call`: wraps every built-in call node with an observer | `kernel.clj:88-90`, `eval.clj:3272-3357`. The consumer `bindings` takes `_observed-built-in-calls` and **ignores** it (`eval.clj:460`) | none | **RIP**. Dead: what it observes is never read. |
| 6de15683 | marks protocol and dynamic stock Vars `:sci/built-in` (`namespaces.cljc:849+`, `utils.cljc:319`) | exempts shared stock Vars from 72150fd4's copy-on-write | none | **KEEP**, with 72150fd4. |
| 72150fd4 | copy-on-write Vars under `fork`: a generation in env and Var meta; `utils/bind-root!`; `eval-def` copies an inherited Var; `sci/bind-root!`; `sci-alter-var-root` | isolated agents, tests-as-branches, candidate contexts (`eval.clj:701,2106`; `accretion_test.clj`). Live: parent `x`=1, child redefines `x`=2 → parent **1**, child **2** | upstream `fork` = `(atom @env)` (`core.cljc:318-323`), and `eval-def` does `(vars/bindRoot prev init)` on the **shared** Var (`evaluator.cljc:34-40`), so a child's redefinition mutates the parent | **KEEP, HIGH (isolation)**. Upstream documents only that "new vars" are private. PR candidate. Note: the `:sci/generation` gensym on Var meta is doing a stamp's job; `turn-interns` (`eval.clj:369-395`) uses it for authorship. |
| 2db3358c | `alter-meta!` / `reset-meta!` refuse a `clojure.lang.Var` from SCI | protects compiled JVM Var metadata from agent code | none | **RIP, HIGH (security)**. The intended API is the embedder's `:namespaces {'clojure.core {'alter-meta! … 'reset-meta! …}}` override, which Seon already uses for `clojure.core` (`eval.clj:240+`). It belongs beside `native-call-refusal` (`my/program.clj:632`). |
| af8a5fb9 | the built-in observer is read from the runtime ctx | follows a27e2c0e | — | **RIP** (with a27e2c0e). |
| 40fcaabd | `:call-preparation-hook`, called on **every** direct Var call (`analyzer.cljc` return-call) | `call_preparation.clj` (1,537 lines) supplied defaults; `my.program/native-call-refusal` | none | **RIP** (design; see slice S9). SCI's intended interception is installing the wrapped function. Seon already wraps every contracted function at install (`eval.clj:692-712`, `instrument/wrap-interpreted`), and native refusals belong in the `:namespaces` override. Today the hook costs one connection deref, one basis comparison, one string and one set lookup **per call** (`call_preparation.clj:1500-1504`). That is work per call where once per install would do. |
| f934044d | `opts.cljc`: `sci/init` / `merge-opts` refuse unknown option keys | a misspelled option went unnoticed | upstream ignores unknown keys (open map) | **RIP**. It breaks the library's open-map contract (and Seon's own law: "Maps are open"). It forced malli ac1d51e0, a fork patch in a second library. Live: `(sci/init {:preset :termination-safe})` → "Unsupported option passed to sci/init: [:preset]". Seon fix: close **Seon's** own options map with its contract at the one `sci/init` call (`eval.clj:221`). |
| 6ee57c9c | the hook also fires for host Vars | same as 40fcaabd | — | **RIP** (with 40fcaabd). |
| fcbd8862 | analyzer `with-root-data`: **every** closure creation does `vary-meta` with `{:sci.root/form … :sci.root/captures …}`; `var-root-data`, `install-var-roots!` | `install-var-roots!` has **0 callers**; `var-root-data` appears only in `test/seon/test/accretion_test.clj:220,246` | none | **RIP, HIGH (retention and hot path)**. Live: `(sci/eval-string* ctx "(let [y 41] (fn [x] (+ x y)))")` → class `clojure.lang.AFunction$1` (meta-wrapper indirection on every call), meta carries the form and `[["y" 41]]`. Every SCI closure keeps its source form and a second reference to its captures. Seon fix: the test compares `(identical? before @parent-var)`. |

### konserve — 9 commits: 1 KEEP, 6 RIP, 2 UPSTREAMED (incl. the local revert)

| commit | what it changes | problem / Seon caller | upstream since | verdict |
|---|---|---|---|---|
| fbdccc9 | `storage_layout.cljc:62-84`: legacy CLJS one-byte meta-size heuristic moved into the **JVM** `parse-header` too | old CLJS stores | upstream kept the heuristic CLJS-only | **RIP, HIGH (durability)**. Seon is JVM-only and has no CLJS stores. On the JVM, a legitimate meta-size that is a multiple of 16 MiB with zero low bytes would be misread. |
| b5c99bc | `node_filestore.cljs` idempotent delete | CLJS pod (deleted) | upstream 4b0bf2f fixes node sync bget | **RIP**. Dead to Seon. |
| 737697d | ordered filestore multi-key ops; `core.cljc` docstrings "Atomically" → "in one backend operation" | Datahike's multi-key commit path | upstream f1d1be9 (#173) restates that multi-key means ATOMIC | **RIP, HIGH**. Reverted in 5b39fdd, but **still live in default** (see "BROKEN NOW" item 1). |
| 403503f | `bget-range` protocol, core fn and filestore impl; `nio_helpers.clj:29` `^String`→`^File` | `blob.clj:374` `read-chunk` | the nio hint is fixed upstream (`nio_helpers.clj:32-34`); upstream b2b567e (#157) streams binary reads on demand (`filestore.clj:435-457,517-528`) | **RIP / UPSTREAMED**. The hint is upstreamed. The range API duplicates streaming bget. Seon fix: `read-chunk` calls `k/bget` with a callback that `.skip`s `offset` and reads `length` from the streamed `:input-stream`, bounded the same way. |
| 3ae14f6 | streaming sync binary reads | same | upstream b2b567e | **UPSTREAMED** (and reverted by 89795ae). |
| 89795ae | reverts 3ae14f6 | — | — | **RIP**. It is a net-zero pair; drop both on rebase. |
| 07377c2 | `:konserve.gc/batch-issued` callback inside `sweep!` and the default `-multi-dissoc` ("to falsifiers") | only `test/seon/blob_publication_test.clj:89,146` | none | **RIP**. It is a test hook placed in the library, and it reads a gc-namespaced key from every multi-dissoc caller's opts. Seon fix: pause in `:datahike.gc/reachable-extension`. It runs inside `gc-storage!` after the sweep permit is taken and before `sweep!` (`reference-code/datahike/src/datahike/gc.cljc:147-167`), which is the same race window. |
| 8cd9144 | `sweep!` `:konserve.gc/dry-run?` returns the candidates without deleting | `registry.clj:468-484` (operator collect dry-run) | none (upstream `sweep!` has opts, `gc.cljc:62+`, but no dry-run) | **KEEP** (library feature, not a bug), PR candidate. Recomputing the selection in Seon would duplicate `sweep!`'s filter and cutoff. |
| 5b39fdd | the revert of 737697d | — | — | transitional; superseded by returning to upstream. |

**Is the fork needed? Only for 8cd9144.** Recommendation: rebase onto upstream `main` 485c233,
keeping 8cd9144 alone, and send it upstream. Dependency: Datahike's GC guard versus upstream
konserve's `konserve.gc-guard` (b812eb2). The fork-audit-datahike lane must confirm our Datahike
fork builds against upstream konserve before this pin moves.

### malli — 5 commits: 1 KEEP, 4 RIP

| commit | what it changes | problem / Seon caller | upstream since | verdict |
|---|---|---|---|---|
| ac1d51e0 | `-default-sci-options` drops `:preset :termination-safe` (`core.cljc:2881`) | our SCI refuses unknown keys (f934044d) | upstream still passes it (`core.cljc:2898`) | **RIP**. Only needed because of SCI f934044d; drop the two together. |
| 3517a3cd | `m/eval` of a qualified symbol returns the **loaded JVM Var's value** before SCI (`core.cljc:2889-2909`) | `[:fn ns/pred?]` / `:gen/gen` symbols in EDN schemas | upstream evaluates in Malli's SCI context | **RIP, HIGH (security)**. It bypasses Malli's sandbox JVM-wide for every Malli consumer. `[:fn clojure.core/eval]` would call host `eval` on validated data. Seon already resolves at its own seam: `schema.clj:298-303` (`:gen/gen`) and `schema.clj:314-322` (`[:fn sym]` → the Var via `loaded-predicate-var`, `schema.clj:167`). Malli's intended ways are handing a Var or fn, or `::m/sci-options`. |
| 606083c5 | `-identify-ref-schema` scope = the resolving registry instance, instead of `(mr/-schemas registry)` (`core.cljc:1943-1949`) | projection compile stall (`4806aad03`); `:ref` validators paid registry-size hashing per ref | still `(-> schema -options -registry mr/-schemas)` upstream (`core.cljc:1958`), under upstream's own TODO "mr/-schemas doesn't seem right" | **KEEP**, PR candidate. Bug: every `:ref` enumerates and hashes the whole registry, so cost grows with registry size per ref instead of per lookup. |
| 25710a67 | `-create-from-gen` resolves a symbol `:gen/gen` via `m/eval` and fails `::no-generator` for a non-generator | accretion generatability (`a4e66fa10`) | upstream `:gen/gen` must be a generator value (`generator.cljc:483-487`) | **RIP**. Symbol `:gen/gen` is a Seon extension, and Seon's seam resolves it (`schema.clj:298-303`). Seon fix: route the path that bypassed the seam through it (see the S8 probe). |
| 8725a8cb | 3517a3cd returns a function **Var**, not its value | a re-evaluated defn stays live | — | **RIP** (with 3517a3cd). `loaded-predicate-var` already hands Malli the Var, so liveness holds without the fork. |

**Needed at all?** Only for 606083c5. After S8 the fork is upstream plus one commit (PR it).
If the PR lands, the fork goes. The fork is held by a1-arming (A1-8b G2), so coordinate there.

### clj-kondo — 2 commits: 2 KEEP

| commit | what it changes | problem / Seon caller | upstream since | verdict |
|---|---|---|---|---|
| 0fc2f636 | `metadata.clj:9-15` + parser `keyword.clj`: `::k` and `::alias/k` in analysis `:meta` resolve in the **analyzed** namespace | `src/seon/fn/analyzer.clj:36-39` (`:var-definitions {:meta true}`) reads `:malli/schema` contracts written with `::` keywords | upstream `KeywordNode/sexpr` uses `(ns-name *ns*)`, the **linter process's** namespace | **KEEP**, PR candidate. Bug: analysis output reports `::request` under the wrong namespace (the test at `analysis_test.clj:1454` is the minimal repro). |
| 57252e07 | the same for defn attr-maps (`analyzer.clj:984,989`, `namespace.clj:538`) | same | same | **KEEP**, PR with 0fc2f636. Note: `namespace.clj:538` keeps upstream's pre-existing `(catch Exception _ nil)`, a swallow that is upstream's, not ours. |

### babashka-process — 1 commit: RIP; the fork can go entirely

e83ec5c (`process.cljc:717-722`) skips the pprint-extension require under `*compile-files*`.

The real cause is Seon's `dev_cache.clj:103-106`: it records each namespace **after** its
`load` returns, which is post-order. So `babashka.process.pprint` is compiled before
`babashka.process`. Compiling it in a fresh child JVM loads `babashka.process`, whose tail
requires `babashka.process.pprint`. That file is still pending, because the `ns` macro adds a
lib to `*loaded-libs*` only after its requires (`clojure/core.clj:5956`), so `load` throws
"Cyclic load dependency" (`core.clj:6145`).

Compiling in **start** order avoids it: once `babashka.process`'s `ns` form has run, it is in
`*loaded-libs*`, and the pprint namespace's require is a no-op.

- **RIP.** Seon fix: move the `swap!` before `original-load#` (`dev_cache.clj:103-106`, a
  one-line move), then return to upstream v0.6.25+ (`.gitmodules`, `deps.edn:86-89` and the
  `:dev-cache` alias).
- Latent upstream quirk, not needed for this: with `clojure.pprint` preloaded,
  `(require 'babashka.process.pprint)` as the first require cycles the same way.
- Live: `babashka.process.pprint` is loaded in default (`(contains? (loaded-libs) 'babashka.process.pprint)` → true).

### http-kit — 1 commit: KEEP, rebase needed (HIGH, security)

238a85c adds per-channel `WriteState`: pending bytes plus a drain-or-close `CompletableFuture`
(`ServerAtta.java:37-64`, `server.clj:321-326`). The caller is `src/seon/render/web.clj:2778-2822`
(`write-package!` parks the SSE writer until the socket drains).

Bug: http-kit's `toWrites` queue is unbounded, and `send!` returns `false` only when the
channel is closed. Upstream issues #180 (closed 2015) and #474 (closed 2023) were both closed
without an API: upstream master has no pending-write accessor (`git grep` of `server/`).

- **KEEP**, PR candidate.
- The fork's base misses upstream's 2026-07-30 server security series. Rebase 238a85c onto
  7f8c701 (3 conflicting files).

### core.async.flow-monitor — 1 commit: RIP; the fork can go entirely

fbff842 stores `:port` in the state atom. Upstream already stores `:server`, an `HttpServer`
because `:legacy-return-value? false` (`flow_monitor.clj:157-162`).

- **RIP.** Seon fix: `test/seon/flow_test.clj:1664` reads
  `(org.httpkit.server/server-port (:server @@monitor))`. Return to upstream 376d6ec / v0.1.5
  (test alias `deps.edn:~145`).

### proximum — RIP the dependency entirely

It is not vendored. `deps.edn:47-50` puts `seantempesta/proximum@9846d3e` (one commit, "Add
crash-safe guarded branch force", +636/−81 on upstream c1235796) on Seon's classpath. Datahike
declares it only in a test alias (`reference-code/datahike/deps.edn:102`).

Live: `(filterv #(str/starts-with? (str %) "proximum") (loaded-libs))` → `[]`. The resource is
on the classpath (`…/gitlibs/…/proximum/9846d3e…/src/proximum/core.clj`), but nothing loads it.
`rg proximum src script test` finds no caller.

The JVM flags `--add-modules jdk.incubator.vector` (`deps.edn:132,155`) are commented as
"Proximum's SIMD requirements". No other source references `jdk.incubator.vector`.

- **RIP.** Delete the dependency and the `--add-modules` pair. Keep `--enable-native-access`
  until whatever else might use it is checked.

## HIGH rows

| fork | commit | class | one line |
|---|---|---|---|
| konserve | 737697d (via pin 8cd9144) | durability | reverted, but default still loads it; atomicity invariant (upstream #173) |
| konserve | (missing upstream) | durability, error | #190 dir sync, #192 parent barriers, #186 exceptions-as-values, #156 write stamps |
| konserve | fbdccc9 | durability | CLJS size heuristic applied to JVM header reads |
| http-kit | (missing upstream) | security | 2026-07-30 `[sec]` server series absent |
| sci | 72150fd4 (+6de15683) | isolation | KEEP: without it a fork's `def` mutates the parent |
| sci | 2db3358c | security | RIP to Seon's `:namespaces` override |
| sci | fcbd8862 | retention, hot path | every closure wraps itself and retains form and captures; dead API |
| malli | 3517a3cd | security | Malli's SCI sandbox bypassed JVM-wide |

## Forks that can go entirely

- babashka-process
- core.async.flow-monitor
- proximum (the dependency)

After their slices:

- konserve is upstream plus 8cd9144.
- malli is upstream plus 606083c5.
- clj-kondo is upstream plus 2 (PR both; gone when merged).
- http-kit is upstream plus 1.
- sci is upstream plus 5 (8fac6e88, 2217449d, 1305a90a, 6de15683, 72150fd4).

## Braids (Simple made easy)

- f934044d braided SCI's option map to Malli's defaults. One library's strictness forced a
  patch in another library, ac1d51e0.
- The call-preparation hook braids **every call** with plan lookup and database basis
  comparison. Installing the wrapped function separates "what this function needs" (decided
  once) from "calling it" (does nothing extra).
- fcbd8862 braids closure creation with a serialization format nobody reads.
- 98457e88 gives one fact (a namespace's requires) two producers: SCI's load and `fn.clj`'s
  form analysis.

## Removal plan — ordered slices

Each slice is one revert or pin change plus the Seon seam fix, adds about 100 Seon src lines
or fewer, and comes with a regression. Holders are per `tmp/orchestrator/file-ownership.md` at
2026-09-23 about 14:40Z. `deps.edn` is held by store-damage for "these lines only". Any other
`deps.edn` hunk goes to store-damage as a follow-up, or waits for release.

| # | slice | files (holder) | Seon src lines | regression |
|---|---|---|---|---|
| S0 | make the konserve revert live: commit the `deps.edn` pin 5b39fdd (already in store-damage's working tree), then the orchestrator runs `start --head` | deps.edn (store-damage) | 0 | live probe: `PMultiWriteBackingStore` satisfied → **false**; `io/resource "konserve/gc.cljc"` names 5b39fdd |
| S1 | flow-monitor to upstream | test/seon/flow_test.clj (m4-n1), deps.edn test alias (store-damage), .gitmodules (free), submodule | 0 src, 1 test | `flow-monitor-attaches-and-publishes-the-render-graph` stays green |
| S2 | babashka-process to upstream: move the `swap!` before `original-load#` in `dev_cache.clj:103-106` | dev_cache.clj (free), .gitmodules (free), deps.edn:86-89 + `:dev-cache` (store-damage), submodule | ≈1 | `test/seon/dev/dependency_cache_test.clj`: in the discovered rows `babashka.process` precedes `babashka.process.pprint`; the class-cache build is platform tier (`bin/test --platform`) |
| S3 | drop proximum and `--add-modules jdk.incubator.vector` | deps.edn:47-50,132,155 (store-damage) | 0 | platform boot (`bin/test --platform`), because the classpath changes |
| S4 | SCI dead and trivial API: revert fcbd8862, d84a0106, 1ed03a69; Seon private `namespace-state` / `namespace-interns` over `(:namespaces @(:env ctx))`; `accretion_test.clj:220,246` uses `identical?` on the root | reference-code/sci (free), src/seon/sci/eval.clj (**leak-fix-2**), test/seon/test/accretion_test.clj (free) | ≈10 | accretion candidate isolation test; `custody_stability_test` interns |
| S5 | SCI observers: revert 47f6c8b5, a27e2c0e, af8a5fb9; delete the kernel observers and retire `:seon.eval/host-interop-count` (1.3e) | reference-code/sci, src/seon/sci/kernel.clj (free), src/seon/sci/eval.clj (leak-fix-2), seon.eval.edn, seon.sci.admit.edn (free), tests naming the attribute | net negative | the retirement is refused while `kernel.clj:204` still writes it; then an evaluation record without the attribute validates |
| S6 | SCI 1.11 core fns: revert 937d392a; add the 11 host fns to Seon's injected `clojure.core` overlay | reference-code/sci, src/seon/program.cljc or sci/eval.clj (leak-fix-2) | ≈12 | an agent-mode `(update-keys {:a 1} name)`, `(parse-long "7")` and `(abs -1)` evaluate |
| S7 | SCI option strictness and malli preset: revert f934044d and malli ac1d51e0 together; close Seon's own `sci/init` options with its contract | reference-code/sci, reference-code/malli (**a1-arming**), src/seon/sci/eval.clj (leak-fix-2) | ≈5 | Seon's contract refuses a misspelled key at `eval.clj:221`; `(sci/init {:preset …})` is accepted |
| S8 | SCI metadata guard: revert 2db3358c; `'alter-meta!` / `'reset-meta!` overrides in Seon's `:namespaces` overlay, beside `native-call-refusal` | reference-code/sci, src/seon/sci/eval.clj (leak-fix-2), src/my/program.clj (free) | ≈15 | agent `(alter-meta! #'clojure.core/map assoc ::x 1)` refuses with a declared error; an SCI-local Var's meta still changes |
| S9 | Malli eval: first, probe which compiled schemas reach `m/eval` with a qualified symbol (count hits during one packaged projection build, in a throwaway namespace, by the malli holder); route them through `schema.clj:298-322`; then revert 3517a3cd, 8725a8cb, 25710a67 | reference-code/malli (a1-arming), src/seon/schema.clj (free) | ≈20 | a re-evaluated `defn` stays live in a compiled `[:fn]` (schema_test); packaged schemas generate (accretion_test) |
| S10 | konserve rebase onto upstream `main` + 8cd9144 only; `blob.clj:374` reads a range via streaming `k/bget`; `blob_publication_test.clj` pauses in `:datahike.gc/reachable-extension` | reference-code/konserve + pin (store-damage), src/seon/blob.clj (free), test/seon/blob_publication_test.clj (free); **depends on** fork-audit-datahike confirming Datahike versus upstream `konserve.gc-guard` | ≈15 | `read-chunk` allocates `length`, not the stored size (a 64 MiB blob with a 4 KiB chunk); both blob-publication races keep their assertions |
| S11 | http-kit rebase 238a85c onto 7f8c701 | reference-code/http-kit (free) | 0 | `WriteStateTest`; the web delivery test through `write-package!` |
| S12 | call-preparation hook (40fcaabd, 6ee57c9c): **stop-rule decision**, likely more than 100 lines. Options: **(a, recommended)** supplied defaults applied inside the per-install wrapper `instrument/wrap-interpreted` (`eval.clj:692-712`) plus host-Var wrapping at `copy-var*` (`eval.clj:202`) for prepared symbols only, and native refusals as `:namespaces` overrides. Guarantee: the same behaviour, cost per install. Cost: a PRD plus 2–3 slices. Gives up: nothing observable. **(b)** keep the hook but return at once for Vars outside `prepared-symbols`, decided at analysis: small, still a fork, still a per-call node. **(c)** keep as is. | src/seon/call_preparation.clj (free), src/seon/sci/eval.clj (leak-fix-2), src/my/program.clj | PRD first | the existing `call_preparation_test` behaviour classes |
| S13 | SCI 98457e88: derive the requires of an evaluated form with `fn.clj`'s clause logic, then revert | src/seon/fn.clj (b1-adoption), src/seon/sci/eval.clj (leak-fix-2) | ≈15 | an agent `(require '[x :as y])` produces the same `:seon.ns/requires` row |
| — | upstream PRs (owner decides): sci 8fac6e88, 72150fd4+6de15683, 2217449d+1305a90a; malli 606083c5; clj-kondo 0fc2f636+57252e07; http-kit 238a85c; konserve 8cd9144 | — | — | — |

The `.gitmodules` and pin changes for S1 and S2 are platform-tier inputs (the dependency class
cache). Their proof is `bin/test --platform` on the orchestrator's cadence, not a lane-local JVM.

## Verification limits

- No upstream JVM was run; the owner's rule allows no probe JVM. Upstream behaviour is cited
  from source at the fetched refs. Fork behaviour was observed live in default:
  - SCI copy-on-write
  - SCI option refusal
  - SCI unresolved-symbol data
  - closure root-data
  - konserve pin and the multi-key protocol
  - proximum not loaded
- The S9 claim that no packaged schema needs Malli's symbol path is **unverified**. The probe
  is specified; it needs the malli holder.
- Whether our Datahike fork runs against upstream konserve is not checked here
  (fork-audit-datahike).
- superv.async is not audited (a new store-damage fork).

## Timings

| operation | wall | why > 1 s |
|---|---|---|
| 7 parallel `git fetch` of upstreams | 1 s | network round trip to GitHub; not a Seon operation |
| bare clone of proximum + upstream fetch | 2 s | network transfer of a whole repository; not a Seon operation |
| 7 `gh search issues` + 2 `gh issue view` | 8 s + about 1 s | sequential GitHub API calls, about 1 s each; a one-off lookup, not a Seon operation (serial by mistake; parallel would be about 1 s) |
| MCP JVM probes (5) | 3–100 ms | — |
| 1 M calls of an SCI closure; 100 k closure creations (fork build) | 70 ms; 24 ms | — |
