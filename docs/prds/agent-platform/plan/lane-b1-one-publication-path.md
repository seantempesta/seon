---
type: plan
status: first pass (Fable, 2026-09-21) for astra review; clean write follows
created: 2026-09-21
tags: [agent-platform, lane-b1, publication, adoption, operator, clj-kondo, datahike]
---

# Lane B1 — one publication path, no mirrors

Grounding read end to end: the writer brief, data pack B1, the publication
audit, the synthesis, the goals note (§2b, R2/R3/§1s/C6), the preserved draft
patch, the measurement script, data packs C1 §2 / B4 §8 / A1 (admission seam),
the instructions audit §3, and the vendored seams named in §3 below. Three
read-only evaluations against `default` were taken (§1); nothing else was run.
Every `fn.clj`/`cluster.clj` line is a WORKING-TREE line (the draft patch is
applied there, as the pack states).

## 0. For the owner: what was dumb, and the simpler way

**What the code does today.** Editing one file publishes it through seven
entry points that all reach `seon.cluster/refresh-source!`. That function
re-lists the whole Git tree twice, hashes every toolchain file and gitlink,
reads the published rows back out of Datahike into a second copy called a
"manifest", diffs manifests, runs clj-kondo (after sweeping clj-kondo's own
cache directory file by file, sometimes running the analysis twice), forks a
scratch Datahike branch, transacts the declarations, runs a second analysis
over callers and a second transaction, transacts a "seal" row holding two
aggregate digests, moves the branch head, retires the scratch, re-derives the
Malli projection from the new commit, traverses namespace dependents twice,
reloads, and re-arms by walking every namespace in the JVM. Nine different
progress mechanisms narrate this; one of them re-parses the JVM's stdout to
recover a phase name; eighteen declared bounds guard it. The operator that
sends the request keeps process records, advertisement files, claim files,
phase logs and a repair pass to reconcile its own copies of facts the OS
process table and the running JVM already hold. A reset compiles 35,773
entity maps into a hand-built tempid table (1.6 M `find` calls) and hands
Datahike 107,049 operations that it then validates entity by entity.

**Why that is the wrong shape.** Every layer is a mirror kept beside the
authority: the manifest beside the rows, the seal digest beside the commit
id, the scratch branch beside Datahike's own atomic transaction, the cache
sweep beside clj-kondo's cache, the process records beside `ProcessHandle`,
the advertisement truth beside the prepl socket, the hook's digest walk beside
the `:seon.fn.file/digest` rows. Each mirror needs machinery to stay current,
and that machinery is proportional to the program instead of to the edit.

**The simpler way, as data flow.** One request enters the running JVM over
its prepl: the changed paths. The JVM hashes only those bytes and compares
them with the digest rows it already stores (a seek per path). clj-kondo
lints only the changed files, with its own cache supplying every other
namespace. The new rows are diffed against the published database VALUE (an
immutable commit); the diff itself says which contracts changed, so callers
are selected from it (median 2 files, live) and linted in the same pass. ONE
Datahike transaction on `current-src` carries declarations and findings; its
report names the changed identities; exactly those namespaces reload; only
their Vars re-arm. The commit id is the publication's identity — nothing else
is written to say "published". A cold start pays one JVM boot and one complete
analysis; a reset writes rows whose tempid is the row's own identity string.
Every cost is proportional to the changed declarations and their callers.

## 1. Goal and the numbers that prove it

Live reads this session (`eval_clj`, `jvm`, `read_only`, cluster `default`):

| Form (abbreviated) | Value | Decides |
|---|---|---|
| `(frequencies (map :a (d/datoms db :eavt)))`, top attributes | 481,655 datoms; `:seon.fn/calls` 76,192 · `/call-arities` 65,764 · `/keywords` 44,282 · `/references` 32,475 (45 % of all datoms are four observation sets); `:keep-history? true`, `:attribute-refs? false` | the reset transaction's work is index insertion of ~480 K datoms with history, not the tempid map (§2b) |
| caller-file fan-out per contracted function (`:seon.fn/spec` → `:seon.fn/calls` → caller file) | 1,555 contracted; 1,416 with callers; files per callee median **2**, p90 16, p99 56, max 176; 126 over 20 | caller lint after a contract change is bounded by the callee's fan-out, not the program (§2 step 5) |
| stored `:seon.ns/requires` graph, Kahn removal | 407 ns rows, 398 nodes, 2,535 edges, **0 cycles**, 0 self-edges; 4 macro namespaces, 20 direct dependents | the ordering fallback never fires today; make it a refusal, keep our 25 lines (§2 step 7) |

Targets (measured by the committed script rows, §8; "landed" = row moved AND
platform tier green):

| Case | Now (latest recorded) | Target | Explained by |
|---|---|---|---|
| no change (`adopt-nochange`) | 264 ms | ≤ 100 ms | hash one path, seek one row, compare two commit ids |
| docstring edit, non-core (`adopt-noncore`) | 2,723 ms | ≤ 700 ms | one clj-kondo file lint with cache (~150–300 ms), diff of ~10 rows, one transaction, one `require :reload` |
| docstring edit, core (`adopt-core`, `seon.id`) | open | ≤ 1,500 ms | as above plus Clojure's own compile of the reloaded namespace |
| fork | 0.34 s | < 1 s (unchanged) | `registry/branch!` |
| first adoption after fork (`adopt-first`) | O(program) `published-index-rows` | ≤ 100 ms | commit-id equality is the answer |
| from zero (`init-zero`) | 178.8 s | ≤ 60 s | §2b: 15 s analysis (probe `:parallel`), ~1 s row compile, transaction ≤ 10 s after the validator narrows (A2), no seal/readback/activation phases |
| boot | 12.8–16.2 s | measured, paid once | — |

## 2. The data flow

### 2a. One edit, end to end (the centerpiece)

Request: `(seon.cluster/refresh-source! {:seon.boot/root R :seon.source/changed-paths P :seon.boot/cluster-name "default" :seon.source/progress! f :seon.source/bounds B})`
sent over the JVM's io-prepl by whoever asks (hook, `bin/seon`, an agent's
declared request — C6). `P` may be empty: "re-observe every stored path".

| # | Step | Data in → out | Where carried | Seam (file:line) | Proportional to |
|---|---|---|---|---|---|
| 1 | serialize | takes the publication monitor (`ReentrantLock`, bound from `B`) | JVM | `cluster.clj:1571` `with-source-refresh-monitor!` (kept; its bound becomes `B`'s `:request`) | 1 |
| 2 | observe | `P` → `{path digest}` by reading only those files; empty `P` → every path with a `:seon.fn.file/relative-path` row (791 live), ~100 ms | value | `source.clj:109` `path-digests` (drop the gitlink branch) | \|P\| |
| 3 | compare | seek each path's stored digest on the published VALUE `(source/database store commit)`; `changed` = paths whose digest differs or is absent; equal ⇒ return the current commit id, done | value | `source.clj:125` `stored-path-digests`; `source.clj:139` `current` | \|P\| seeks |
| 4 | lint | clj-kondo over `changed` with `:cache-dir` the project cache; `to-cache` rewrites the changed namespaces' entries, `sync-cache*` loads every used namespace from disk; blocking findings refuse before any write | analysis map | `analyzer.clj:260` `invoke-kondo` (minus the sweep); `impl/cache.clj:65` `to-cache`, `:171` `sync-cache*`; `fn.clj:1093` `assert-clean-analysis!` | \|changed files\| + used namespaces read from cache |
| 5 | rows | analysis → program rows for those files (`artifact` minus manifest fields), each row with its definition digest (§2g) | vector of rows | `fn.clj:1292` `artifact` → rows only; `fn.clj:600-680` row construction | rows in changed files |
| 6 | diff | rows vs the published value: per row `normalized-index-row`, `changed-attributes`, exact replacement tx; removed identities = the file's previous identities not in the new rows → `:db/retractEntity`. Output: `tx-data` PLUS `changed-contracts` = identities whose changed attributes include `:seon.fn/spec` or `:seon.schema/form` | values | `fn.clj:3047` `reconcile-tx-in` (returns the pair); `program.cljc:993` `changed-attributes`, `:1027` `exact-replacement-tx-in` | rows in changed files |
| 7 | callers | `changed-contracts` → schema parents by `:seon.schema/references` fixpoint → functions whose arity refs name them → `:seon.fn/calls` referrers → caller files, minus `changed`; empty on a docstring edit | set of paths | draft `fn.clj:2274-2306` `caller-files`, re-signatured to take `(database changed-contracts)` instead of a report | callee fan-out (median 2 files) |
| 8 | lint callers | step 4 again over caller files (cache now holds the new arglists); only `:seon.lint/*` rows are kept; diffed against the callers' stored lint rows → more `tx-data` | value | draft `fn.clj:3441-3456` minus its transaction | \|caller files\| |
| 9 | transact | ONE `db/transact!` on the `current-src` connection: declarations + findings; the writer validates the touched entities; a refusal leaves the head unmoved | report | `db.clj:4578` `transact!`; `writer.cljc:385` | touched entities |
| 10 | identities | `report-identities` over `:tx-data` (pull of touched entities before and after) | set | `fn.clj:3129` | touched entities |
| 11 | adopt | cluster row's `:seon.source/commit-id` = new commit? done. Else namespaces = `(development-namespaces db-after identities)` ∪ namespaces of retracted identities; macro/protocol roots expand to dependents once (visited set) | set | `cluster.clj:2001` (draft) minus the union at `:2096` | changed namespaces (+ dependents for the 4 macro namespaces) |
| 12 | reload | `reload-order` over the stored requires among the set; `require :reload` each; `ns-unmap` retracted identities | JVM | `cluster.clj:1922`, `:1971`, `:2101` | \|namespaces\| |
| 13 | arm | `instrument/apply!` receives the reloaded namespace set and arms only their interns (A1 owns the change; B1 hands the argument) | JVM | `instrument.clj:927`, `:844` `current-wrapper?` | Vars in reloaded namespaces |
| 14 | record | one transaction on the cluster: `:seon.source/commit-id` + adoption identities/inputs | cluster branch | `cluster.clj:2138-2148` (kept) | 1 |

Why step 6's pre-read is legitimate: the published database is an immutable
COMMIT value (`d/commit-as-db`, `versioning.cljc:469`); nothing can change it
between the diff and step 9, and the monitor serializes publishers. Why one
transaction suffices: Datahike's transaction is atomic on the branch
(`transaction.cljc:1218` `transact-tx-data` builds one report; the writer
commits or fails it whole), so the scratch branch, `force-branch!`, the
"another publisher created current-src first" refusal and `retire-branch!`
(`source.clj:413-521`) exist only to make four transactions look like one.
The draft's second transaction (`fn.clj:3459-3465`) becomes rows appended to
the same `tx-data` because the contract change is known from the diff, not
from the report. What never happens any more: `git ls-files` (twice),
toolchain hashing, the manifest read-back (`published-index-rows` 21.9 ms +
`database-manifest`), the second digest re-observation (`cluster.clj:1895`),
the projection re-derivation (248 ms; A1 carries it on the value), the
`(all-ns)` arming walk, the seal row, the head readback.

### 2b. The reset's cold path

| Phase (measured) | ms | What Datahike/clj-kondo does | Decision |
|---|---:|---|---|
| preparation | 7,760 | loads publication inputs / roots | delete with the snapshot and toolchain layers; expect < 500 |
| analysis, 381 files | 15,368 | clj-kondo `run!` serial | the tool's cost; probe `:parallel true` (`core.clj:92`); the cache directory is then complete for every later edit |
| schema population | 2,454 | attribute declarations + canonical schema rows | A1/A2 own the projection; B1 keeps one transaction |
| program rows + contract rows (11,428) | 4,613 + 24,988 | `program/contract-facts` compiles each Malli contract | A1's compiled-registry seam; B1 hands the carried projection |
| "population compiled" | 13,226 | `index-tempids` `fn.clj:2873`: `tree-seq` over every nested map × 53 identity attributes ≈ 1.6 M `find`s, `pr-str`-keyed sort; `compile-index-transaction` rewrites refs to string tempids | tempid = `(pr-str (program/row-identity row))` — a pure function of the row, no table; refs to rows are the same string; `:seon.fn/keywords` stays a set inside the entity map (43,934 separate `:db/add` ops die); expect < 1 s |
| transaction, 107,049 ops | 36,068 | `entity-map->op-vec` → `upsert-eid` (one AVET seek per identity attribute per map, `transaction.cljc:641-715`), `explode`, then per datom `transact-add` → `with-datom-upsert` (unique check, history datom, index insert; `:530-560`); then OUR report validator pulls every touched entity (`db.clj:4123`) | 35,773 maps × (seek + explode) is milliseconds-per-thousand; the datoms (~480 K with history) are the floor Datahike charges; the validator is A2's narrowing. Probe (§6): `d/load-entities` (`writer.cljc:411`, `core.cljc:142` `transact-entities-directly`) with eids allocated as `max-eid + i` skips upsert resolution entirely |
| issue indexing | 6,124 | `seon.issue/index!` over notes (`issue.clj:511`) | B3's file; 16 ms/note is a finding for B3 |
| activation seal | 3,440 | the sealed activation closure (`cluster.clj:1316-1353`, span unverified) | ruled deleted (goals §5, "sealed activation-closure roster") |
| branch-head readback | 4,875 | `unresolved-report!` (`source.clj:501`) = `fn/unresolved-callers` `:1534`, a not-join over 76 K call datoms | answer from the `:seon.fn/unresolved-references` file rows the indexer already writes (`fn.clj:1299-1301`): one query over 791 file rows |

Can identity upsert replace the tempid map? Yes for resolution — Datahike
resolves a string tempid consistently within one transaction (`:1306-1316`)
and nested maps by identity (`upsert-eid`) — but a FRESH branch has no
identity to upsert against, so the string tempid is required for forward
refs, and the cheap way to mint it is the identity's own print. The
`retry-with-tempid` restart (`:844-853`, `:1291`) fires only when a tempid
was allocated before its identity map arrived; ordering identity maps before
referrers (namespaces, files, schemas, then functions) avoids every restart.

### 2c. The operator after the cut

| File | Today | Survives (≈ lines) | Derived from the OS / the JVM instead |
|---|---:|---:|---|
| `bin/seon` | 26 | 26 | — |
| `script/seon/fresh_operator.clj` | 3,727 | ~600: argv parsing, `prepl-eval!` `:1820` / `read-prepl-reply` `:1166` / `prepl-value!` `:1182`, `launch!` `:2129` (the one child JVM), a ~20-line readiness wait for the child (file appears, one prepl round trip, under the boot bound), `reset!`, `help!` | process records (`:144-243`) → `ProcessHandle` (`state.clj:1199`); advertisement truth/repair (`:757-770`, `:1323-1769`, `:3351-3392`) → the prepl answer; offline readers (`:881-1082`) → "no JVM running"; phase logs (`:3008-3074`) → re-run reset; `await-advertisement!` `:2266-2402`; `source-preflight!` `:330-443` (clj-kondo in the JVM refuses); `init-form` `:2680-2802` → one `pr-str`'d request; `publication-output!` `:2804` |
| `src/seon/operator.clj` | 1,219 | ~500: `start!`/`stop!`/`restart!`, `connection`, `status`, `banner`, `clusters`, `cleanup-cluster!`, `collect!`, `refork!`, `rotate-logs!`, footprint | `publish!` `:551` (entry 5), `reap-dead-roots!` `:373-509`, `census-processes!`, `claim-root!`, `existence`, `lifecycle-lock-bound-ms` `:65`, `projected-delete-ms-per-file` `:919` |
| `src/seon/operator/state.clj` | 1,627 | ~700: `run-process!` `:76`, the root lifecycle `flock` `:417-800`, `read-advertisement` `:1150`, `observed-property-processes` `:1199`, footprint `:1457-1522`, destructive-path admission `:1541-1627` | claims `:801-1149`; census/truth `:1159-1456` except the two kept; `lifecycle-lock-timeout-ms` 900000 `:413`; `subprocess-cleanup-ms` `:22`; `responsive-advertisement?` `:1262` (a request either answers or its bound names the phase) |

Process identity is `(pid, start-instant)` from `ProcessHandle`
(`state.clj:224`, `cluster.clj:2284`); the generation UUID
(`-Dseon.operator.generation`) is deleted; `-Dseon.operator.root` stays so a
cold `down` can find the JVM when the file is stale. **One file per root**:
`<root>/prepl.edn` `{host port pid start-instant}`, written by the JVM when
its prepl binds (today `cluster-directory/<name>/prepl.edn`,
`fresh_operator.clj:127`); it has one job — telling a cold dialer which port
to dial — and is never consulted for health. Commands after the cut: `start`
(no JVM: `launch!`; JVM: `(seon.operator/start! …)`), `init`, `init NAME`,
`init --dev`, `status`, `open`, `stop`, `down`, `reset --force`, `logs`,
`config apply`, `export` (dies with B4's base store) — all but `start`-cold
and `reset` are one prepl request; `bin/test-check` (53 lines) is the model.

### 2d. Seven entry points, nine progress mechanisms, eighteen bounds

| Today | After |
|---|---|
| entries 1–4 (`bin/seon init` variants, hook) | one prepl request to `refresh-source!` |
| 5 `operator/publish!` | deleted (the request calls `seon.cluster` directly) |
| 6 `test.cache/prepare-base!` + `publication-base!` `cluster.clj:2230` | deleted with B4's base store (B1 deletes `publication-base!` once `test/cache.clj:393-395` and `bin/test:1034` stop calling it — seam) |
| 7 `bootstrap_drive.clj:450` | the same request |
| hook `bin/seon-hook:1486-1665` (200 lines: result files, pending queue, worker pid file, detached worker, `bin/seon` child, stdout re-parse) | ~30 lines: read `prepl.edn`, `prepl-eval!` the request with the edited paths, print `:out` events as they arrive, print the reply; coalescing is the JVM monitor's; `.codex/hooks.json` wiring (26 lines) and the transit-cache diagnostic (`:318-456`, ~130) deleted; the shell-write digest walk (`:1688-1819`, ~90) replaced by sending the request with NO paths (step 2, ~100 ms) |
| 9 progress mechanisms (pack §3c) | ONE `:seon.source/progress!` argument on the request, threaded through `index!`; the prepl client prints `:out`; `*source-progress!*`, `*boot-progress!*`, `report-index-progress!`, the phase-clock atom, `publication-output!`, `SOURCE_PROGRESS`, `bin/test: SOURCE` all deleted |
| 18 bound declarations (pack §3d) | one declared config fact `:seon.config.source/phase-bounds-ms` `{:observe :analysis :transaction :reload :arm}` carried on the request as `B`; each phase fails with a typed error naming the phase, the bound and what was in flight; the socket read timeout is their sum; the lifecycle `flock` keeps its own declared acquisition bound (a different resource) |

Hook publication is re-enabled (`.claude/seon-hook.edn` `:current-source
{:enabled true}`) in the commit that lands the `adopt-noncore ≤ 700 ms` row;
the config's 2026-09-20 comment block is deleted then.

### 2e. The clj-kondo fork change and the agent-evaluation probe

| Change | Where | Effect |
|---|---|---|
| `from-cache-1`: after reading a `:disk` entry, return nil when `(:filename entry)` is a string, not `"<stdin>"`, not a `.jar:` entry, and `(.exists (io/file filename))` is false | `impl/cache.clj:23-36` (~4 lines) | a renamed or deleted source no longer answers from the cache; deletes `discard-obsolete-cache-entries!` `analyzer.clj:223-259`, the second `run!` `:282-286`, `forget-namespaces!` `:288` and the hook diagnostic |
| `skip-write?` returns true for `"<stdin>"` | `impl/cache.clj:38-53` (1 line; today the `when-not` yields nil for stdin so stdin lints DO write) | an agent form linted from stdin never replaces a namespace's cache entry — the "poisoning" the hook comment at `bin/seon-hook:256-259` describes and the sweep's `"<stdin>"` clause at `analyzer.clj:252` |

Both land on `origin/seon` of `seantempesta/clj-kondo` (gitlink `57252e07`)
before the deletion commit. Agent evaluations: `analyze-forms`
(`analyzer.clj:657`) synthesizes a stub prelude for every referenced program
function and lints with `:cache false`. With the cache on, `sync-cache*`
(`cache.clj:171`) supplies file-indexed namespaces; an agent's SCI-only
definitions have no entry and `load-when-missing` (`:127`) no-ops silently,
so they would lint `unresolved-var`. Decision: cache ON for `:core` rows,
prelude restricted to rows with `:seon.schema.admission/source :agent` in the
referenced namespaces (a small vector), stdin never written (fork change 2).
The probe (§6) measures per-evaluation cost of `sync-cache*`'s transit reads
for a form using `seon.db`; if that read exceeds the stub synthesis, the
prelude stays and the answer is recorded.

### 2f. Reload ordering

`reload-order` (`cluster.clj:1922-1945`) and clj-reload's `topo-sort`
(`parse.clj:163-176`) are the same Kahn ordering. Live: 0 cycles. Keep ours
(clj-reload is vendored for reading only, not in `deps.edn`; adding a
dependency for 25 lines is a new edge), and delete the `(first remaining)`
fallback at `:1944`: an unsatisfiable set becomes a typed refusal naming the
remaining namespaces — today it reads absence of an order as an order.
`development-namespaces` keeps the draft's macro/protocol expansion (4 macro
namespaces, 20 direct dependents live) and runs ONCE on `db-after` from the
report's identities; retracted identities contribute their namespace by name.

### 2g. The per-definition content digest (D9; C1, B4 and §1s consume it)

| Fact | Decision |
|---|---|
| attribute | `:seon.program/definition-digest` `[:string {:min 64 :max 64}]`, REQUIRED on every function, test, namespace and schema row (replaces `:seon.program/analyzed-source-digest`, `seon.fn.edn:116`, `seon.test.edn:88`, whose value is the FILE digest, `fn.clj:659-660`; C1 §2). RESET NEEDED |
| derivation | `(id/digest 64 [source aliases])`: the row's own exact text (`:seon.fn/source` / `:seon.test/source` / `:seon.ns/source`, or `:seon.schema/form`) and the sorted `{alias target-ns}` map of its namespace — the resolver context that makes the same text mean different things; written where the row is built (`fn.clj:600-680`, `:1007-1014` for agent forms). Presence still records "analyzed" (G4) |
| deletes | the manifest's `declaration-digests` (`fn.clj:1260-1290`, which hashes eleven attributes but not the body); `changed-schema-keys` `:2761` (the diff's `changed-attributes` answers); B4's `definition-digests` `test.clj:646-666` (recomputed from eleven attributes at selection time — a mirror) reads the stored fact; `:seon.fn.file/declaration-digests` schema entries |
| consumers | §1s acquisition: `sci/eval.clj:912` decides `:jvm` vs interpret by admission; the ruling is digest equality with the core row (B2 converts; B1 supplies the fact). C1: samples keyed `(symbol, definition-digest, branch)`. B4: `changed-definition-symbols` and reach digests compare stored digests |

Also dropped as aggregates: `:seon.source/digest` and `:seon.source/test-input-digest`
seal row (`source.clj:458-471`; the commit id is the identity; B4's
`runner/program-digest` `runner.clj:2372-2389` and the four `test.clj` reads
move to per-path `:seon.fn.file/digest` rows and the commit id — B1 stops
writing, B4 stops reading; the deletion of the schema keys lands after both).

### 2h. Size target

| File | Before | Floor (audit) | Target | Reasoning for the gap |
|---|---:|---:|---:|---|
| `src/seon/fn.clj` | 3,494 | −660 caller-less | 2,000 | plus the manifest family (~370), `index-tempids`/`compile-index-transaction` (~130 → 40), `build-manifest`/`database-manifest`, progress, `sha-256`, `backfill-contract-facts!`, `changed-schema-keys`, `declaration-digests` |
| `src/seon/fn/analyzer.clj` | 719 | −42, −55 | 600 | sweep, `forget-namespaces!`, stub prelude narrowed to agent rows |
| `src/seon/program.cljc` | 1,092 | — | 1,000 | `deletion-row`/tombstone remnants only; the row model is right |
| `src/seon/cluster.clj` publication sections (`:999-1525`, `:1526-2279`) | 1,279 | — | 550 | snapshot/toolchain (`source-snapshot`, `full-source-refresh!` ~110), artifact trio (34), currentness trio (67), `publication-base!` (49), `require-publication-resources!` (17), two progress mechanisms, `retrying-source-change` (the digest race is gone with the second observation) |
| `src/seon/cluster/source.clj` | 575 | — | 250 | scratch branch, `force-branch!`, seal, `publication-input-digest!`, `upsert!`/`populate-upserts!` (no callers outside the file), `changed-identities`' history fallback stays |
| `src/seon/operator.clj` | 1,219 | ~500 survive | 500 | as §2c |
| `src/seon/operator/state.clj` | 1,627 | ~700 | 700 | as §2c |
| `script/seon/fresh_operator.clj` | 3,727 | ~600 | 600 | as §2c |
| `bin/seon-hook` | 1,987 | −130, −90, −180 | 1,450 | lint/markdown/docstring/review stay; publication ≈ 30 lines; Codex wiring 8 lines |
| `src/seon/test/cache.clj` (B1's part: `input-paths`, `gitlink-digests`, `toolchain-dependencies`, `test-input-digest`, `input-roots`) | ~120 | −60 | 0 | the rest is B4's base store |
| `src/seon/id.clj` | 73 | — | 73 | three `sha-256` copies call it |
| **total** | **15,839** | ≈ 7,600 | **7,700** | the floor listed layers; the target dissolves mechanisms (manifest, scratch, seal, tempid table, snapshot) |

## 3. Reading list (read before editing)

| Seam | Lines | Guarantee to build on |
|---|---|---|
| clj-kondo `run!` | `core.clj:67-107`, `:143`, `:242-262` | `:cache-dir` selects the directory; `sync-cache` runs before the unresolved-var lints; `:parallel` exists |
| clj-kondo cache | `impl/cache.clj:20-36`, `:38-53`, `:65-81`, `:83-109`, `:127-144`, `:146-200` | one transit file per (lang, ns); `to-cache` overwrites unconditionally; `with-cache` is a file lock with backoff; `load-when-missing` no-ops with no entry |
| clj-kondo `reg-var!` | `impl/analysis.clj:87-116` | the per-var row: `:private :macro :fixed-arities :varargs-min-arity :doc :defined-by :arglist-strs :row :col :end-row :end-col` |
| clj-kondo unresolved vars | `impl/linters.clj:1078-1100` | findings come from `:unresolved-vars` per namespace after `sync-cache` |
| clj-reload ordering | `parse.clj:122-176` | same Kahn; default `on-cycle` throws — read to confirm ours is equivalent, then do not depend on it |
| Datahike transaction | `db/transaction.cljc:641-715` `upsert-eid`, `:786` `transact-add`, `:844-853` `retry-with-tempid`, `:946-972` `entity-map->op-vec`, `:1153` `:db.fn/call`, `:1218-1330` `transact-tx-data` | identity upsert per map; string tempids resolved within one transaction; a conflicting tempid restarts the transaction; one report per transaction |
| Datahike per-datom cost | `db/transaction.cljc:530-560` | unique check, history datom, index insert per datom |
| Datahike versioning | `versioning.cljc:212` `branch!`, `:457` `commit-id`, `:469` `commit-as-db`, `:490` `release-materialized-db` | a branch is a pointer; a commit value is immutable; release what you materialize |
| Datahike writer | `writer.cljc:385-409`, `:411-419` `load-entities`; `core.cljc:142` `load-entities-with` | serial writer per connection; `load-entities` bypasses entity-map processing (probe) |
| our writer seam | `db.clj:4578` `transact!`, `:4305-4319` (report validator attached), `:4123` `write-report-validator`, `:1219` `carried-projection` | the validator is A2's narrowing; the projection rides the value |
| our diff | `fn.clj:3047-3113` `reconcile-tx-in`, `:2992-3045` `normalized-index-row`, `:3129` `report-identities`; `program.cljc:323` `row-identity`, `:993` `changed-attributes`, `:1027` `exact-replacement-tx-in` | the per-row exact replacement already exists; only its call site and outputs change |
| our rows | `fn.clj:185-225` exact source/span, `:600-680` row construction, `:1292-1335` `artifact` | rows carry `:seon.fn/source`, `:seon.fn/form-span`, `:seon.fn/file` |
| adoption | `cluster.clj:2037-2155`, `:1922-1979`, `:2001-2035`; `instrument.clj:844-896`, `:898-908` `collect-contracts!` (walks `(all-ns)`) | the reload and re-arm seams; `current-wrapper?` preserves identity |
| the prepl client | `fresh_operator.clj:1166-1191`, `:1820-1909`; `bin/test-check:1-53` | one form, one terminal value, `:out` events observable, silence bound |
| process facts | `state.clj:224` `process-start-instant`, `:1199-1223` `observed-property-processes`, `:1150` `read-advertisement` | pid + start instant from the OS; the property names the root |
| MCP tool | `script/seon/dev/mcp.clj:847-860` | `eval_clj` `jvm` mode binds no custody: `(seon.operator/connection "default")` |

## 4. REPL protocol

Codex reaches the REPL through `.codex/config.toml` → `bin/mcp-server` →
`eval_clj` (`jvm`, `read_only` for reads) against `default`, or its scratch
cluster `bin/seon --root tmp/b1-root start b1` (directory created first,
downed and deleted after). Before each form: `(require '[datahike.api :as d])`
and `(def conn (seon.operator/connection "default"))`.

| When | Form | Expect |
|---|---|---|
| before | the three §1 forms | the recorded values |
| before | `(time (seon.cluster/refresh-source! "." ["src/my/note.clj"] "default"))` after a docstring edit | ~2.7 s; phases in the `:out` lines |
| before | `(time (clj-kondo.core/run! {:lint ["src/my/note.clj"] :cache-dir ".clj-kondo/.cache" :config-dir ".clj-kondo"}))` | the tool's one-file cost with cache |
| probe | `(time (clj-kondo.core/run! {:lint ["src"] :parallel true …}))` on the scratch root | vs 15.4 s serial |
| probe | `d/load-entities` of the compiled population on a scratch branch, eids `max-eid + i` | vs 36 s |
| probe | agent form lint: prelude of agent rows + cache on vs today's stub prelude | ms per evaluation |
| after | `(time (seon.cluster/refresh-source! {…}))` docstring edit | ≤ 700 ms, phases printed once |
| after | `(count (filter #(str/starts-with? (str %) "seon.fn.index/") …))` | no string tempid table exists; tempids are identity prints |
| after | `(d/q '[:find (count ?e) . :where [?e :seon.program/definition-digest]] (d/db conn))` | equals the number of fn + test + ns + schema rows |
| after | `(seon.cluster.source/current store)` vs cluster row `:seon.source/commit-id` | equal after adoption; no `:seon.source/digest` datom exists |

## 5. The work, ordered as commits (each leaves HEAD loadable)

| # | Commit | Net | Notes |
|---|---|---:|---|
| 1 | clj-kondo fork: `from-cache-1` skips a vanished `:disk` file; `skip-write?` true for `"<stdin>"`; push `origin/seon`; bump gitlink | +5/−0 | prove: lint a file after deleting a sibling; no stale entry |
| 2 | delete `discard-obsolete-cache-entries!`, second `run!`, `forget-namespaces!`; hook diagnostic `:318-456` | −180 | `clojure -M -e "(require 'seon.fn.analyzer)"` |
| 3 | delete the caller-less `fn.clj` vars (pack §4) and their `fn_test.clj` sections; `sha-256` copies → `seon.id/sha-256` | ≈ −1,400 | HEAD loads: `(require 'seon.fn 'seon.test.cache 'seon.schema)` |
| 4 | `reconcile-tx-in` returns `{tx-data changed-contracts}`; `caller-files` takes `(database changed-contracts)`; caller lint rows appended; `index!` incremental branch = one `transact!`; delete `db.fn/call` wrapper and the second transaction | −80 | regression: docstring edit lints zero callers; contract edit lints exactly the direct callers; both in ONE report |
| 5 | `:seon.program/definition-digest` declared and written; `analyzed-source-digest` retired; `declaration-digests`, `changed-schema-keys`, `:seon.fn.file/declaration-digests` deleted; B4's five `test.clj` reads renamed (presence only) | −60 | **RESET NEEDED**; B4 seam named in the landing note |
| 6 | manifest dissolved: `build-manifest`/`database-manifest`/`manifest-data`/`artifact-by-path`/`manifest-function-symbols` → rows from analysis; `full-source-refresh!` = observe → compare → lint → rows | ≈ −600 | `adopt-nochange` row measured |
| 7 | `source/publish!` = one transaction on `current-src`; scratch branch, `force-branch!`, seal row, `publication-input-digest!`, `upsert!`/`populate-upserts!`, `unresolved-report!` readback deleted; unresolved report reads `:seon.fn/unresolved-references` | ≈ −300 | B4 seam: `program-digest`; **RESET NEEDED** (schema keys retired after B4) |
| 8 | snapshot/toolchain dissolved: `source-snapshot`, `require-publication-resources!`, `test.cache` gitlink/toolchain/test-input-digest, `retrying-source-change`; per-path rows are the inventory | ≈ −250 | `adopt-noncore` row measured |
| 9 | adoption: commit-id compare first; one `development-namespaces` traversal on `db-after`; `reload-order` refuses on an unsatisfiable set; `instrument/apply!` receives the namespace set (A1 lands the arming change; until then pass and ignore) | −40 | `adopt-first` row measured |
| 10 | reset cold path: tempid = identity print; keywords inside the map; `index-tempids` deleted; activation seal deleted; `population compiled` measured | −120 | `init-zero` row measured; `load-entities` probe result recorded either way |
| 11 | one progress argument; one `:seon.config.source/phase-bounds-ms` fact; the 18 declarations and 9 mechanisms deleted; typed phase refusals | ≈ −200 | every phase fails on its bound in a regression with a 1 ms bound |
| 12 | operator: process records, advertisement truth/repair, offline readers, phase logs, `await-advertisement!`, `source-preflight!`, `init-form` codegen, `publish!`, `reap-dead-roots!`, claims → `ProcessHandle` + one `prepl.edn` per root; every command but cold `start`/`reset` is one request | ≈ −4,800 | `fresh_operator_test`/`operator_test` process-machinery sections deleted; cold start, stop, down, reset drills kept |
| 13 | hook: publication = ~30 lines over `prepl-eval!`; queue/worker/result files, digest walk, Codex wiring deleted; `:current-source {:enabled true}` | ≈ −480 | live proof: edit → hook → adopted in `default`, browser observed separately |
| 14 | `publication-base!` + `test.cache` base-store callers (after B4 lands) | −49 | B4 seam |

## 6. Better than the floor — probes that decide

| Candidate | Probe | Decides |
|---|---|---|
| `d/load-entities` for the reset population (skips `upsert-eid`/`explode`/tempids; eids minted `max-eid + i`; our row validation runs BEFORE as Malli on the rows) | time both on a scratch branch of the same store | transaction 36 s → target; if the writer's report validator is the bulk, the answer names A2 |
| clj-kondo `:parallel true` for the complete analysis | `run!` over `src` both ways | 15.4 s → ? |
| no scratch branch at all (transact on `current-src`; a refused transaction leaves the head) | count transactions per publication before/after; concurrent publisher regression | deletes `source.clj:413-521` |
| the hook's path-less request (JVM re-hashes 791 stored paths) vs the bb digest walk | time `path-digests` over every stored path | ≤ 150 ms keeps it; else the walk stays as one function |
| schema declarations and rows in ONE transaction on reset (Datahike updates `rschema` as schema datoms are added, `transaction.cljc:557-560`) | transact `[attr-decl {row using attr}]` on a scratch branch | collapses the cold path's ordered transactions to one |

## 7. Tests

| Test | Disposition |
|---|---|
| `fn_test.clj` sections for `build-artifact`, `rows`, `reconcile-tx`, `plan-file-change`, `artifact-by-path`, `manifest-function-symbols`, `output-path-report`, `backfill-contract-facts!` | die with the vars (≈ 800 lines) |
| 13 single-`deftest` `publication_*`/`source_*`/`fn/publication_*` namespaces | collapse to `cluster/publication_test.clj` (edit → transaction → adoption, one class each) and `fn/publication_test.clj` (analysis/rows/diff), sharing ONE small fixture program (three files under a fixture root, never `src/`) published once per namespace |
| the 11 × 600,000 ms, 900,000, 1,200,000 escapes (`publication_{adoption,reuse,export,facet,cache,host}`, `source_nochange`, `fn/publication_test`, `test/publication_test`) | fixture defect: the small fixture removes the bound; `publication_host_test` (real boot) declares 60,000 with the measured 12.8–16.2 s boot as its reason |
| `boot_test.clj` (6), `cohost_boot_test.clj` (1) | genuinely long: 60,000 each with the measured boot; `fresh_operator_test.clj` (41 tests, 2,206 lines) and `operator_test.clj` (1,441): process-record/advertisement/claim/reap/phase-log drills die with the mechanism; cold start, stop, down, reset, flock drills stay |
| `publication_toolchain_test.clj` | dies with the toolchain digest |
| new, one per class | contract change lints exactly its direct callers in the same report; docstring edit lints none; no-change returns the commit id with zero transactions; unsatisfiable reload set refuses; each phase bound fires as a typed error; a vanished source file answers no cache entry (fork) |
| the lane runs | only tests reaching its change, in-process via `seon.test/check` over `run-owned`; never a suite |

## 8. Done, landing note, stop rules

**Done** when: HEAD loads (`clojure -M -e "(require 'seon.fn 'seon.fn.analyzer 'seon.cluster 'seon.cluster.source 'seon.operator 'seon.operator.state)"`);
the measurement script (re-homed to
`docs/prds/agent-platform/research/measure-publication-path.sh`, same rows)
prints `adopt-nochange ≤ 100`, `adopt-noncore ≤ 700`, `adopt-core ≤ 1500`,
`adopt-first ≤ 100`, `fork < 1000`, `init-zero ≤ 60000` ms; the platform
tier is green; the diff is net-negative by ≥ 8,000 lines; hook publication is
enabled and one edit is observed adopted in `default`.

**Landing note**: `docs/prds/agent-platform/landing/lane-b1.md` — every §4
form with its value before and after, the script rows, the fork commits, the
probe answers (§6, either way), the RESET NEEDED commits, and the seams
handed to A1 (`instrument/apply!` namespace argument; carried projection),
A2 (validator narrowing; `load-entities` finding), B2 (acquisition by
`definition-digest`), B3 (`issue/index!` 16 ms/note), B4 (`program-digest`,
the five digest reads, `publication-base!`).

**Stop** at: a held file (`git status` first); the seal/aggregate deletion
before B4 has converted its reads (leave the keys, stop writing); the
`instrument/apply!` change (A1's file — pass the argument, do not edit);
an unsettled design — three options in the note: (1) scratch branch kept vs
direct transaction on `current-src` if the concurrency regression fails;
(2) `load-entities` vs entity maps if the validator is not the bulk;
(3) prelude vs cache for agent forms if the transit read cost exceeds stub
synthesis.
