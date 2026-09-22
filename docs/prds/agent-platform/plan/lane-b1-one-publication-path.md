---
type: plan
status: implementation specification; acceptance proofs pending
created: 2026-09-21
tags: [agent-platform, lane-b1, publication, adoption, operator, clj-kondo, datahike]
---

# Lane B1 — one publication path, no mirrors

Owned paths: `src/seon/fn.clj`, `src/seon/fn/*`, `src/seon/program.cljc`, the
publication sections of `src/seon/cluster.clj` (`:999-1525`, `:1526-2279`),
`src/seon/cluster/source.clj`, `src/seon/operator.clj`,
`src/seon/operator/state.clj`, `script/seon/fresh_operator.clj`, `bin/seon`,
`bin/seon-hook`, `src/seon/id.clj`, `script/seon/dev/mcp.clj`, `bin/mcp-server`, the Seon registration in `.codex/config.toml`, and the input readers of
`src/seon/test/cache.clj` (`input-paths :45`, `input-roots :154`,
`gitlink-digests :190`, `toolchain-dependencies :217`). `bin/codex-agent` and
the lane launcher are NOT in scope (orchestrator ruling: the plan is
implemented by astra lanes). Source citations use the recorded `209a6652a` working-tree snapshot; dependency lines use clj-kondo `57252e07` and Datahike `006e634a`. `K/` means `reference-code/clj-kondo/src/clj_kondo/`; `D/` means `reference-code/datahike/src/datahike/`. Historical §1 reads are baseline evidence, not fresh runtime or implementation proof.

Evidence: [B1 data pack](../research/data-pack-b1-publication-2026-09-21.md) and [durable rulings](../research/durable-goals-and-rulings-2026-09-21.md) supply the historical measurements and ruled publication sequence. The implementation contract is complete below.

## 0. For the owner: what was dumb, and the simpler way

**What the code does today.** Editing one file reaches
`seon.cluster/refresh-source!` (`cluster.clj:2174`) through seven entry points.
It lists the Git tree twice and hashes every toolchain file and gitlink
(`source-snapshot :1619`, re-observed at `:1832`); reads the published rows
back out of Datahike into a second copy called a manifest
(`published-index-rows fn.clj:3149`, `database-manifest :3269`) and diffs
manifests; sweeps clj-kondo's own cache directory entry by entry and sometimes
runs the analysis twice (`analyzer.clj:223-286`); forks a scratch branch,
transacts declarations, lints callers, transacts findings, then transacts a
seal row holding two aggregate digests (`source.clj:458-471`) and reads the
head back; re-derives the compiled Malli projection from the new commit
(`source.clj:151-162`, 248 ms for 1,560 contracts); reloads; re-arms by
walking every namespace in the JVM (`instrument.clj:898-908`). Nine progress
mechanisms narrate it and eighteen declared bounds guard it (pack §3c–3d). The
operator that sends the request keeps process records, advertisement truth,
claim files, phase logs and a repair pass over copies of facts the OS process
table and the running JVM already hold (`fresh_operator.clj:144-243`,
`:757-770`, `:1323-1769`, `:3351-3392`; `state.clj:801-1149`). A reset
compiles 35,773 entity maps through a hand-built tempid table (≈1.6 M `find`
calls, `fn.clj:2873`) and hands Datahike 107,049 operations.

**Why that is the wrong shape.** Each layer is a mirror kept beside an
authority that already answers the question: the manifest beside the rows,
the seal digest beside the commit id, the cache sweep beside clj-kondo's cache
plus our own stored namespace-per-file facts, process records beside
`ProcessHandle`, advertisement "truth" beside the prepl socket, the hook's
digest walk beside the `:seon.fn.file/digest` rows. Every mirror needs
machinery to stay current, and that machinery does work proportional to the
program instead of to the edit. The repeat docstring edit measures 2,723 ms,
of which 553 ms is re-listing and re-hashing the tree and 248 ms re-deriving
a projection an immutable value already carries (pack §5).

**The simpler way, as data flow.** One request enters the running JVM over its
prepl: the changed paths. The JVM reads only those bytes, hashes them and
seeks each path's stored digest in the published commit value (a pull per
path). clj-kondo lints only the changed files; its own cache supplies every
other namespace, and the namespaces those files DECLARED (stored facts) are
the only entries invalidated. The rows for those files are reconciled INSIDE
Datahike's serial writer on a scratch branch (a branch is a pointer:
milliseconds), so the diff is decided by the authority, not by a pre-read.
The writer's transaction report names what changed; callers are selected
from that report (median 2 files per contracted function, §1), linted with
the cache now holding the new signatures, and reconciled in a second
transaction on the same scratch branch; then the head of `current-src` moves
once. Adoption compares two commit ids, copies exactly the report's
identities into the development branch through the same writer, reloads
exactly the affected namespaces, arms exactly the replaced Vars and the
functions whose contracts reference a changed schema, and records the commit.
Nothing else is written to say "published"; the commit id is the identity.
Ordinary edit work follows selected files, their declarations and affected dependencies. Pathless discovery and analyzer-configuration changes inspect their declared input population; reset pays initial analysis and population construction. These separate costs remain visible in measurements.

## 1. Goal and the numbers that prove it

Historical reads dated 2026-09-21 (`eval_clj`, `jvm`, `read_only`, cluster `default`, commit
`6ab15cfb-6177-50d4-9ccd-91ef300b2084`; the first three rows are historical reads on the same commit):

| Form (abbreviated) | Value | Decides |
|---|---|---|
| `(frequencies (map :a (d/datoms db :eavt)))` | 481,655 datoms; `:seon.fn/calls` 76,192 · `/call-arities` 65,764 · `/keywords` 44,282 · `/references` 32,475 | this current population census does not measure the cold transaction’s input or index cost; §2b measures those separately |
| caller files per contracted function (`:seon.fn/spec` → `:seon.fn/calls` → caller file) | 1,555 contracted; files per callee median **2**, p90 16, p99 56, max 176 | caller lint is bounded by the callee's fan-out (§2a step 8) |
| stored `:seon.ns/requires`, Kahn removal | 398 nodes, 2,535 edges, **0 cycles**; 4 macro namespaces, 20 direct dependents | `reload-order`'s cycle fallback never fires; make it a refusal (§2f) |
| recorded sample, 20 ms: file rows, fn rows, resolver facts, shared source | **791** file rows · 4,600 fn rows (p50 **6** per file, max 193) · 407 ns rows: 390 with aliases, 284 with refers, 108 with imports, 0 with renames · **23 groups / 49 symbols** share byte-identical `:seon.fn/source` across different symbols (e.g. three `reverse-attribute`s) | pathless discovery examines current declared inputs plus stored paths; a per-file lint reconciles ~6 rows; source text ALONE is not a definition identity (§2g) |

Targets. "Landed" = the committed script's row moves AND the platform tier is
green. The script is re-homed to
`docs/prds/agent-platform/research/measure-publication-path.sh` and its
`grep` of `init phase=lifecycle elapsed-ms=` rewritten for the one progress
argument (§2d) in the same commit that deletes the parsed format. Each row
reports end-to-end request completion THROUGH adoption, with per-phase times
and work counts (files, rows, datoms, namespaces, Vars).

| Case | Latest recorded (pack §5) | Target | Explained by |
|---|---|---|---|
| explicit paths, no change (`adopt-nochange`) | 264 ms | ≤ 100 ms | read one file, one pull, two commit ids compared; zero transactions |
| pathless discovery, no change | not measured | ≤ 300 ms | 791 stored rows read + hook-walk-class scan (112 ms / 553 files measured, `.claude/seon-hook.edn:34`) |
| docstring edit, non-core (`adopt-noncore`) | 2,723 ms | ≤ 700 ms | one clj-kondo lint with cache (475 ms today incl. sweep), two writer transactions of ~6 rows, adoption copy of the same rows, one `require :reload`; zero caller lint |
| docstring edit, core (`adopt-core`, `seon.id`) | open | ≤ 1,500 ms | as above plus Clojure's compile of the reloaded namespace |
| contract edit | not measured | record phase times and returned caller-file count; no fixed per-file allowance | caller lint proportional to fan-out (median 2) |
| fork | 0.34 s (landed) | < 1 s | `registry/branch!` (`registry.clj:178`) |
| first adoption after fork (`adopt-first`) | O(program) | ≤ 100 ms | commit-id equality |
| from zero (`init-zero`) | 178.8 s | ≤ 60 s | §2b, measured phase by phase before any prediction |
| boot | 12.8–16.2 s | measured, paid once | — |

## 2. The data flow

### 2a. One edit, end to end

Request: `(seon.cluster/refresh-source! {:seon.boot/root R :seon.source/changed-paths P :seon.boot/cluster-name "default" :seon.source/progress! f :seon.config.source/phase-bounds-ms B})`
over the JVM's io-prepl from the hook, `bin/seon`, or an agent's declared
request (C6). `P` absent means pathless discovery (step 2b).

| # | Step | Data in → out | Carried | Seam (file:line) | Proportional to |
|---|---|---|---|---|---|
| 1 | serialize | the publication monitor (`ReentrantLock`); its acquisition bound is `B`'s `:request` | JVM | `cluster.clj:1571` `with-source-refresh-monitor!` (kept) | 1 |
| 2a | capture (explicit `P`) | read each path's bytes ONCE → `{path {bytes digest}}`; directories are gitlinks → pinned commit | value | `source.clj:109` `path-digests` (keeps its directory case) | \|P\| |
| 2b | discover (no `P`) | declared input roots walked → current path set (`input-roots`, `input-paths` moved into `source.clj`) vs stored `:seon.fn.file/relative-path` rows: added, removed, differing digest | value | `test/cache.clj:45`, `:154` (moved); `source.clj:125` `stored-path-digests` | \|inputs\| ≈ 791 |
| 3 | compare | seek each captured path's stored digest on the published commit VALUE; `changed` = differing/absent; `removed` = stored, not on disk. Empty ⇒ skip to step 11 with the current commit id | value | `source.clj:125`, `:139` `current`; `versioning.cljc:469` `commit-as-db` | \|P\| pulls |
| 4 | classify inputs | a changed path under `.clj-kondo/` (config: `K/impl/core.clj:148-156` resolves it outside the namespace cache) ⇒ analysis of EVERY source path; `deps.edn` or a gitlink ⇒ typed refusal `RESET NEEDED` (a loaded dependency cannot reload); otherwise `changed` only | value | `fn.clj:2365-2376` (today's `changed` derivation, minus manifests) | 1 |
| 5 | invalidate | `forget-namespaces!` for the namespaces the changed and removed files DECLARED (stored `:seon.ns/name` rows by `:seon.fn/file`), all three languages | cache dir | `analyzer.clj:288` (kept); `fn.clj:2388-2395` | changed files |
| 6 | lint | clj-kondo over the captured bytes through the private mirror (same bytes as the digest and the rows); `sync-cache*` supplies every other namespace from disk; blocking findings refuse BEFORE any write | analysis | `analyzer.clj:403-466` `analyze` (`::sources`); `K/core.clj:242-262`; `K/impl/cache.clj:171` ; `fn.clj:1093` `assert-clean-analysis!` | changed files + used namespaces read from cache |
| 7 | rows | analysis → program rows for those files, each carrying `:seon.program/definition-digest` (§2g); removed files → their stored identities for retraction | vector | `fn.clj:600-680` `var-row`; `:1292` `artifact` (rows only, no manifest fields) | rows in changed files (p50 6/file) |
| 8 | transaction 1 | `registry/branch!` scratch from the published commit; `db/transact!` with `[:db.fn/call reconcile]`: the writer hands its CURRENT db to `reconcile-tx-in`, which emits per-row exact replacements and `:db/retractEntity` for removed identities; the final-report validator refuses severed connections | report | `source.clj:229`, `:371` `publish!`; `fn.clj:3413-3431`, `:3047` `reconcile-tx-in`; `program.cljc:993`, `:1027`; `transaction.cljc:1153` (`:db.fn/call`), `:1206-1216` (validator); `db.clj:4123` | touched entities |
| 9 | callers | from `report :tx-data`: datoms on `:seon.fn/spec`, `:seon.schema/form`, `:seon.fn/arglists`, `:seon.fn/private?`, `:seon.fn/macro?`, namespace binding facts, and retracted `:seon.fn/sym` → schema parents by `:seon.schema/references` fixpoint → functions whose arity refs name them; include namespace resolver changes and before/after call/reference edges for macro, inline, protocol and removal effects → affected caller/reference files minus `changed`. Reconcile final dependency facts, preserving ordinary direct-caller selection where that is the complete affected set. Docstring edit ⇒ empty | set | `fn.clj:2274-2306` `caller-files` (widened attribute set) | callee fan-out |
| 10 | transaction 2 | caller files captured and linted (cache now holds the new signatures); their COMPLETE rows (calls, call-arities, lint) reconciled through the same `:db.fn/call` on the scratch connection | report | `fn.clj:3436-3467` minus the `:seon.lint/id` filter | caller files |
| 11 | head | `force-branch!` moves `current-src` to the scratch commit with `:expected-current-commit` set to the prior head and immutable parents; retire scratch only after release. The publisher must hold exclusive write custody for `current-src`; the dependency’s guard is not a cross-writer CAS (`D/versioning.cljc:323-335`). The commit id is the publication | store | `source.clj:495-521`; `versioning.cljc:323` | 1 |
| 12 | adopt? | cluster row `:seon.source/commit-id` = published commit ⇒ done (this check runs even when step 3 skipped) | cluster branch | `cluster.clj:2049-2051` | 1 |
| 13 | adoption copy | identities = the two reports' identities (or `changed-identities` by history when the cluster is behind by more than one publication); schema declarations, program rows (`seon.fn/index!` with `:seon.reconcile/adopt-identities`) and issue rows copied into the development branch through ITS writer, agent facts preserved | cluster branch | `cluster.clj:2063-2091`; `source.clj:164` `changed-identities`; `issue.clj:1006` `adopt!` | touched identities |
| 14 | reload set | roots from BOTH `previous-database` and `db-after` (retracted macros count); macro/protocol roots expand to dependents once; retracted `:seon.fn/sym`s `ns-unmap`ped | set | `cluster.clj:2001-2035` `development-namespaces`; union at `:2096-2097` (kept) | changed namespaces (+ dependents of the 4 macro namespaces) |
| 15 | reload | `reload-order` over stored requires; `require :reload` each; then verify each reloaded file's on-disk digest equals the published digest, else typed refusal `source-changed-during-adoption` (retry once, `cluster.clj:696`) | JVM | `cluster.clj:1922`, `:1971` | \|namespaces\| |
| 16 | arm | `instrument/apply!` receives `:seon.instrument/changed-identities` = Vars of reloaded namespaces ∪ the step-9 schema-referrer set; unrelated wrappers keep identity. A1 owns the selection change; before that paired slice the existing broad arming remains; passing an ignored key is not implementation of bounded selection | JVM | `instrument.clj:927` `apply!`, `:844` `current-wrapper?` | changed Vars |
| 17 | record | ONE transaction on the cluster: `:seon.source/commit-id`, `:seon.test/adoption-identities`, `:seon.test/adoption-inputs`; written only after 13–16 succeed; a failure leaves the prior record | cluster branch | `cluster.clj:2135-2147` (kept) | 1 |

**Why two transactions and a scratch branch, answered at the writer.**
Datahike's writer is one serial loop per connection that threads its
evolving `old` value through every operation (`writer.cljc:130-135,
145-147`); `transact!` dispatches ONE `arg-map` and delivers ONE report
(`:390-397`); `transact-tx-data` builds that report from one `tx-data`
vector and runs the final-report validator once (`transaction.cljc:1218`,
`:1206-1216`, `:1276`). A `:db.fn/call` function receives the writer's
current db (`:1153`), so a diff computed inside it is decided by the
authority — so the existing `reconcile-tx-in` at `fn.clj:3413-3431` remains the decision seam. Immutability of a pre-read value does not prove equality with the writer’s head. The caller set can only be selected from the REPORT of
transaction 1, and clj-kondo cannot run inside a transaction function:
`retry-with-tempid` restarts the whole transaction from `initial-es`
(`:844-853`, `:1291`), so a lint inside the writer could run twice and
would block the serial writer for hundreds of milliseconds. Therefore the
ruled shape stands (goals `:282`): transaction 1 → report → caller lint →
transaction 2 → move the head. The scratch branch is Datahike's own
pointer (`versioning.cljc:212`), not a mirror; it is what makes a refused
transaction 2 leave `current-src` unmoved. What IS deleted: the seal row and
its readback, the manifest and `published-index-rows`, the second
observation (`cluster.clj:1832`), the projection re-derivation once A1 lands
(`source.clj:151-162`; until then the 248 ms stays and is counted), the
`(all-ns)` arming walk once A1 lands, `upsert!`/`populate-upserts!`
(`source.clj:540-565`, no callers outside the file), and
`publication-input-digest!` (`:352`).

### 2b. The reset's cold path

| Phase (last measured, pack §5) | ms | What runs | Decision |
|---|---:|---|---|
| preparation | 7,760 | snapshot/toolchain layers | remove repeated aggregate work through §2a steps 2–4; measure remaining discovery and input capture |
| analysis, 381 files | 15,368 | clj-kondo serial | the tool's cost; `:parallel` is NOT a lever as called today: `parallel-analyze` groups by `:group-id`, which `sources-from-dir` sets to the directory argument (`K/impl/core.clj:367`, `:375-376`), so `:lint ["src"]` is one sequential group; the adapter records a parallel call-record corruption (`analyzer.clj:433-436`). Stays serial unless a probe with per-file groups proves EQUAL normalized definitions, calls, arities and findings |
| schema population | 2,454 | attribute declarations + schema rows | one transaction; A1/A2 own the projection |
| program + contract rows (11,428) | 4,613 + 24,988 | `program/contract-facts` (`program.cljc:733`) compiles each contract | A1's compiled registry; B1 hands the carried projection |
| "population compiled" | 13,226 | `index-tempids` (`fn.clj:2873`, `tree-seq` × 53 identity attributes, `pr-str` sort) + `compile-index-transaction` (`:2899`) | tempid = `(pr-str (program/row-identity row))` for SUBMITTED identified rows only; refs to rows outside the submission stay lookup refs; anonymous component maps stay nested (Datahike allocates them); `:seon.fn/keywords` stays a set in the map. Measure construction independently of the transaction |
| transaction, 107,049 ops | 36,068 | `entity-map->op-vec` → `upsert-eid` (one AVET seek per identity attribute, `transaction.cljc:641-715`) → per datom `transact-add` (`:786`) with history; then our validator (`db.clj:4123`) | UNATTRIBUTED today. Land nothing on a prediction: the first probe times construction, `upsert`/`explode`, index work, final-report validation and durable commit separately on ONE reset and records counts (ops, effective datoms, owning roots, compiler calls). At reset every row is new, so "narrow to touched rows" gives no speedup by itself; A2 owns removing repeated derivation inside the validator |
| issue indexing | 6,124 | `seon.issue/index!` (`issue.clj:511`) | B3's file; 16 ms/note recorded as a finding for B3 |
| "activation seal" | 3,440 | `cluster.clj:1316-1353` is `:seon.config/initialization` readiness, not a roster | NOT a B1 cut; the ruled roster deletion (goals `:387`) has no code here |
| branch-head readback | 4,875 | `unresolved-report!` (`source.clj:194`) = `fn/unresolved-callers` (`fn.clj:1534`), a not-join over 76 K call datoms | kept as the authority (analysis-time `:seon.fn/unresolved-references` file rows are observations, not "no current definition"); runs on the cold path only — incremental publications are guarded by the writer's severed-connection refusal |

`d/load-entities` (`writer.cljc:411`, `core.cljc:142`,
`transaction.cljc:1337` `transact-entities-directly`) imports raw
`[e a v t added?]` datoms, remaps identifiers and skips the final-report
validator: it is a diagnostic LOWER BOUND for the probe, labelled
non-equivalent, never a production switch.

### 2c. The operator and boot rewrite

The integrated implementation contract is [B1b — one operator request, one boot
sequence](lane-b1b-operator-and-boot-rewrite.md). It replaces the previous inventory
and owns command/reply maps, REPL-first boot, one-JVM reset under the store lock,
exact process identity, caller/tool conversion, eight destructive drills and recovery.

The owner permits temporary MCP, REPL and boot breakage inside this rewrite and
changes to the tools to fit the design. Restore them at the completed slice boundary;
do not preserve old machinery to keep intermediate edits runnable. Reset exclusion
covers delete → republish → boot, not the replacement gap; a competing winner makes
the reset replacement refuse without deletion or killing. The lock file survives.
Line counts are estimates, not constraints. B1b §0 records guarantees kept and dropped.

### 2d. Entry points, progress, bounds, hook

| Today | After |
|---|---|
| entries 1–4 (`bin/seon init` variants, hook) | one prepl request to `refresh-source!` |
| 5 `operator/publish!` `:551` | deleted; the request calls `seon.cluster` directly |
| 6 `test.cache/prepare-base!` `:379` + `publication-base!` `cluster.clj:2230` | deleted with B4's base store (`test/cache.clj:393-395`, `bin/test:1034` are B4's callers — seam) |
| 7 `bootstrap_drive.clj:450` | the same request |
| 9 progress mechanisms (pack §3c) | ONE `:seon.source/progress!` argument threaded through `publish!` and `index!`; the prepl client prints `:out` events; `*source-progress!*` (`cluster.clj:90`), `*boot-progress!*` `:247`, `report-index-progress!` `fn.clj:34`, the phase clock `fresh_operator.clj:2755-2790`, `publication-output!`, `SOURCE_PROGRESS` deleted. Foreign reader to convert in the same slice: `test/runner.clj:60` (B4's file, one line) |
| 18 bound declarations (pack §3d) | publication phases: one config fact `:seon.config.source/phase-bounds-ms` `{:request :observe :analysis :transaction :reload :arm}` carried on the request; each phase fails with a typed error naming the phase, the bound and the work in flight. NOT collapsed (different resources, enforced at their own seams): the lifecycle `flock` bound (`state.clj:413`), child-exit (`:22`), boot readiness, and the prepl socket silence bound (`fresh_operator.clj:81`, `:1820-1853`) — a socket timeout does not cancel admitted work, so the JVM-side phase bound is the one that stops it |
| hook publication `bin/seon-hook:1486-1665` (result files, pending queue, worker pid file, detached worker, `bin/seon` child, stdout re-parse) | ~40 lines: read the advertisement, `prepl-eval!` the request with the edited paths, print `:out` as it arrives, print the reply. Coalescing: the monitor SERIALIZES; it does not merge paths — so the hook sends each event's paths and an unchanged path costs one pull (step 3). `.codex/hooks.json` (26 lines) STAYS: it is the only Codex trigger, for the lint hooks too. The transit-cache diagnostic `:318-456` (~130) dies with the sweep; the shell-write digest walk `:1688-1819` (~130, 112 ms measured) stays until the pathless request (step 2b) measures ≤ 150 ms on the JVM, then becomes that request |
| the hook's OWN lint (`run-clj-kondo` `:250-292` with `--cache false`, `validate-clojure-edit`/`validate-schema-edit`/`findings-feedback`/`clj-kondo-feedback` `:779-960`, `docstring-feedback` `:1046`, `run-schema-admission` `:483-537` with its child-JVM fallback) — a second analysis path in Babashka beside the JVM's | **one prospective-edit request** (deep review win 4): the hook sends `{path prospective-bytes}` to the live JVM, which lints through `analyzer/analyze` over `::sources` with the project cache, admits schema resources through A1-9's pure `admit`, and checks docstrings from program facts; the hook parses the event, sends, prints. `bin/seon-hook` ≈ 150 lines, plus `reconstruct-patched-file` `:538-760` (220) only while an `apply_patch` payload lacks the resulting file, plus the asynchronous Gemini review batch `:1166-1462` (≈ 300, unchanged, counted separately). No live JVM ⇒ the hook refuses with the exact `bin/seon start` command (**ruled 2026-09-21**; no Babashka fallback, one lint path). Proof: a syntax error refused by the JVM's findings; a `cat >` write caught by the pathless request |
| `.claude/seon-hook.edn` `:current-source {:enabled false}` | enabled ONLY after: the owner-coordinated reset that batches §2g; consumer conversion loaded; one live adoption observed in `default`; the `adopt-noncore ≤ 700 ms` row recorded. Then one real hook event (a named-file edit AND a shell-created new file) is observed adopted, and the 2026-09-20 comment block is deleted. The orchestrator ruling "re-enable only when the measured edit is cheap" is this row |

### 2e. clj-kondo cache correctness

The cache is clj-kondo's and describes ANALYZED SOURCE, never admitted branch
facts: `sync-cache` writes entries (`K/impl/cache.clj:171`, `to-cache :65`)
BEFORE the lints run and before our refusal (`K/core.clj:242-262`), so a
refused publication has already rewritten the changed namespaces' entries.
A rejected candidate must not later supply the resolver context for an admitted branch. Database unresolved-name checks do not prove cached arities or metadata are correct. Before the sweep is removed, prove retries and edits to other files after refusal against cache-disabled branch analysis; any correction belongs at clj-kondo’s existing source-ownership/cache-write seam. Cached signature keys are
`var-def-keys` (`K/impl/core.clj:618-624`: `:arities :fixed-arities
:varargs-min-arity :private :macro …`), not `:arglist-strs` or `:doc` — a
docstring edit changes no cached signature, which is why it selects no
caller. Concurrent `run!`s: publications are serialized by the monitor; the
hook's prospective lints run `--cache false` (`bin/seon-hook:250-262`).

| Case | Mechanism (all existing) | Regression |
|---|---|---|
| deleted file | its stored `:seon.ns/name` rows → `forget-namespaces!` (step 5) | lint a caller after deleting the callee file: `unresolved-namespace` |
| namespace renamed in place | old name from stored rows of that path → forget; new name written by the lint | old name no longer answers |
| `.clj` ↔ `.cljc` replacement | `forget-namespaces!` deletes all three languages (`analyzer.clj:288-299`) | one entry survives |
| mirror-backed entry | the mirror keeps the source's absolute path as its tail; `analyzed-source-path` reads it back (`analyzer.clj:318-337`, `:347`) | entry names the checkout path, not `tmp/` |
| stale `<stdin>` / agent form | agent forms lint with `{:cache false}` and the branch-derived prelude (`analyzer.clj:690-698`) — the adapter's filename is `my/agent.clj`, so `skip-write?` would not protect it (dated 2026-09-21 cache predicate probe) | no entry written by an evaluation |
| superseded file still on disk | the removed path's rows are retracted (step 7) and its namespaces forgotten | resolution follows the database |

`discard-obsolete-cache-entries!` (`analyzer.clj:223-259`, O(all entries))
and the second `run!` (`:282-286`) leave only after the six cases pass, including legacy entries with no admitted file row. Stable source ownership must cover a cleaned-up captured-source mirror and a still-existing superseded file; if targeted invalidation is insufficient, repair the dependency seam before deleting the sweep. Agent evaluations keep the prelude and
`:cache false` until a probe proves EQUAL findings with the cache for: an
older sovereign branch, a namespace mixing `:core` and `:agent` rows
(`load-when-missing` `K/impl/cache.clj:127-145` loads a whole namespace or
nothing), and stdin with an explicit filename.

### 2f. Reload ordering and arming selection

`reload-order` (`cluster.clj:1922-1946`) is clj-reload's `topo-sort`
(`parse.clj:163-176`) in 25 lines; live: 0 cycles. Keep ours; delete the
`(first remaining)` fallback at `:1944` — an unsatisfiable set becomes a typed
refusal naming the remaining namespaces (today it reads absence of an order as
an order). Roots come from BOTH databases (a retracted macro or a macro→defn
change is invisible on the after side alone). Arming selection follows
schema-reference facts independently of reload: a function whose contract
references a changed schema key is re-armed even though its namespace did not
reload (step 16, the step-9 query).

### 2g. The definition identity (B2, B4, C1, D1 consume it)

| Fact | Decision |
|---|---|
| attribute | `:seon.program/definition-digest` `[:string {:min 64 :max 64}]`, REQUIRED on every function, test, namespace and schema row. A NEW key: `:seon.program/analyzed-source-digest` (`seon.program.edn:1-3`, `seon.fn.edn:116`, `seon.test.edn:88`) means "the analyzed input file", and changing a key's meaning is breakage; it is retired at the reset. Presence still records "analyzed" (G4). **RESET NEEDED** |
| what it hashes | `(seon.id/digest 64 [(seon.schema/canonical-data-string parts)])`, where `parts` carries declaration identity (including namespace), exact definition source, normalized resolver context, and effective declaration metadata. Resolver context uses the existing namespace facts: requires by namespace identity; aliases as local→target namespace; refers/renames as local→target namespace/name; imports as local→target class. Sort unordered collections canonically and exclude branch-local eids. Function/test metadata includes effective contracts, macro/inline/protocol/private/dynamic semantics and inherited test markers (platform, fixtures, observation, long, long-ms, subject) wherever they affect acquisition or execution. Namespace rows include their source and normalized bindings; schema rows include their key and canonical authored form. The shared constructor owns this encoding; consumers never reconstruct it. |
| equivalence | Equal source, identity, resolver context and effective metadata produce equal digests across file/agent construction and branches. A body edit changes its declaration’s digest, not an unrelated declaration’s. A namespace resolver change may legitimately change every declaration using that context. Exact source conservatively distinguishes doc/format changes; the digest does not claim semantic equivalence. Resolved call/reference observations remain program facts, not a substitute for complete resolver context. |
| what it excludes | authorship (`:seon.schema.admission/source`), file position, unrelated file bytes and branch-local ids. Macro/inline/protocol dependency content and current dependency reach remain separate acquisition/reload/test obligations; equal declaration digests alone cannot certify an indirect host call under a changed dependency. |
| where computed | ONE function `program/definition-digest` beside `row-identity` (`program.cljc:323`), called from `var-row` (`fn.clj:600-680`) and `analyzed-form` (`:1007-1014`) — file and agent rows through the same derivation. Namespace and schema row constructors invoke it too; all four declaration families land with the required attribute |
| acquisition (B2) | B2 verifies the digest and dependency context of the actual installed callable; an adopted-commit fact alone is insufficient after partial reload or agent writes. Retained installation identity distinguishes old callable roots from newer rows. Share a JVM callable only when that equivalence and context/contract isolation are proven; otherwise interpret the supplied definition or report the typed unavailable implementation. Probe indirect JVM caller→changed callee, old/new clusters, candidate arming and concurrent adoption |
| profiling (C1) | `:seon.profile/digest` = this value, captured when the callable is installed, supplied on the arming request ONLY for changed identities (step 16); an old invocation finishing after redefinition keeps its old key |
| test selection (B4) | `changed` selects assertion/retraction history for this attribute and compares basis/final identity digests; edit→revert is unchanged, deletion remains an obligation, missing identity evidence is unknown; `definition-digests` (`test.clj:646-666`, eleven attributes recomputed at selection time) reads the stored fact instead; reach, schema and input evidence remain separate inputs |
| deletes | `declaration-digests` (`fn.clj:1260-1290`), `changed-schema-keys` `:2761`, `:seon.fn.file/declaration-digests` and the manifest keys (`seon.fn.file.edn:20-28`, `seon.fn.manifest.edn:7-13`); the aggregates `:seon.source/digest` and `:seon.source/test-input-digest` (`seon.source.edn:29-35`) once B4 converts `test.clj:900,916,1365,2161`, `test/runner.clj:3639`, `test/fast.clj:30` — B1 stops writing them only in the commit that deletes those reads |

### 2h. Size

| File | Before | Target | Reasoning for the gap between audit floor and target |
|---|---:|---:|---|
| `src/seon/fn.clj` | 3,494 | 2,300 | caller-less vars (−660, pack §4), manifest family (~370), `index-tempids` → 40 lines, progress, `sha-256`, `backfill-contract-facts!`, `declaration-digests`, `changed-schema-keys`; `index!`'s two transactions and `reconcile-tx-in` stay |
| `src/seon/fn/analyzer.clj` | 719 | 620 | sweep and second run (−63); prelude stays |
| `src/seon/program.cljc` | 1,092 | 1,050 | + `definition-digest`; `deletion-row` remnants out |
| `src/seon/cluster.clj` publication sections | 1,279 | 700 | snapshot/toolchain (~110), artifact trio (34), currentness trio (67), `publication-base!` (49), `require-publication-resources!` (17), two progress mechanisms; adoption stays whole |
| `src/seon/cluster/source.clj` | 575 | 350 | seal, `publication-input-digest!`, `upsert!`/`populate-upserts!`, aggregate digests; + the ~90 moved input readers |
| `bin/seon` | 26 | ≈ 80 | B1b §1: argv, root validation and the one client |
| `script/seon/operator.clj` | 6,573 retired across the three old operator files | ≈ 400 | B1b §1: replaces `fresh_operator.clj` (3,727), `operator.clj` (1,219) and `operator/state.clj` (1,627); no surviving old-file targets |
| `src/seon/cluster/boot.clj` | 464 relocated/replaced from `cluster.clj:3103–3566` | ≈ 420 | B1b §1: REPL-first sequence and reverse release; these lines are separate from the publication sections above |
| `bin/seon-hook` | 1,987 | ≈ 670 | one request (≈ 150) + patch reconstruction (220, until payloads carry the file) + the review batch (≈ 300); publication body ~40 inside the 150 |
| `src/seon/test/cache.clj` (B1's readers, estimated) | ~120 | 0 | moved (~90) or deleted |
| `src/seon/id.clj` | 73 | 73 | three `sha-256` copies (`schema.clj:767` already delegates; `test/cache.clj:39`) call it |
| **total** | **≈ 16,402** | **≈ 6,663** | Sum of the scoped rows: prior 15,912 plus the 26-line launcher and 464-line boot span previously omitted. The three replacement files target ≈900; record actual `wc -l` per new file and explain overruns. The right design wins over the count (README §7). |

Maintenance/filesystem moves are charged to the owners where they land, not counted
as net deletions here; tool conversions and other existing-owner changes are measured
separately under B1b §1. Recount the scoped totals at landing; each line is charged once.

Tests: `fn_test.clj` 2,967 (≈800 die with the vars); `operator_test.clj`
1,441; `dev/fresh_operator_test.clj` 2,206 (41 tests); `dev/fresh_operator_reset_test.clj`
752; 22 `publication_*`/`source_*` namespaces, 13 holding one `deftest`.

## 3. Reading list (read before editing)

| Seam | Lines | Guarantee to build on |
|---|---|---|
| Datahike writer | `D/writer.cljc:120-160` loop, `:385-409` `transact!`, `:411-419` `load-entities` | one serial loop per connection threading `old`; one report per `transact!`; import bypasses entity maps AND the validator |
| Datahike transaction | `D/db/transaction.cljc:641-715` `upsert-eid`, `:786` `transact-add`, `:844-853` `retry-with-tempid`, `:1153` `:db.fn/call`, `:1206-1216` `validate-report`, `:1218-1330` `transact-tx-data`, `:1337` `transact-entities-directly` | identity upsert per map; string tempids resolved within one transaction; a conflicting tempid RESTARTS the transaction; the validator runs once on the final report |
| Datahike versioning | `D/versioning.cljc:212` `branch!`, `:323` `force-branch!`, `:457` `commit-id`, `:469` `commit-as-db`, `:490` `release-materialized-db`, `:734` `merge!` | a branch is a pointer; a commit value is immutable; release what you materialize |
| clj-kondo run | `K/core.clj:67-107`, `:242-270` | `sync-cache` before the lints; cache writes precede findings |
| clj-kondo cache | `K/impl/cache.clj:20-36`, `:38-53`, `:65-81`, `:83-109`, `:127-145`, `:146-200`, `:210-215` | one transit file per (lang, ns); `to-cache` overwrites; `with-cache` is a file lock; `load-when-missing` loads a whole namespace or silently nothing |
| clj-kondo signatures and grouping | `K/impl/core.clj:148-156` config, `:367`, `:375-399` `parallel-analyze`, `:521-527` stdin, `:618-632` `var-def-keys` | config resolved outside the cache; one group per directory argument; cached keys exclude `:doc`/`:arglist-strs` |
| clj-kondo `reg-var!` | `K/impl/analysis.clj:87-116` | the per-var analysis row |
| clj-reload | `parse.clj:122-176` | the same Kahn ordering; default `on-cycle` throws |
| our writer seam | `db.clj:4578` `transact!`, `:4123` `write-report-validator`, `:4100-4118` arity admission, `:1219` `carried-projection` | the validator is A2's narrowing; the projection rides the value |
| our diff and rows | `fn.clj:3047-3113` `reconcile-tx-in`, `:2992` `normalized-index-row`, `:3129` `report-identities`, `:3413-3467` `index!`; `program.cljc:323`, `:993`, `:1027` | per-row exact replacement inside the writer already exists |
| analyzer | `analyzer.clj:403-466` `analyze`, `:288` `forget-namespaces!`, `:505-568` `require-specs`/`namespace-prelude`, `:657` `analyze-forms` | captured bytes through the mirror; branch-derived prelude for agent forms |
| adoption | `cluster.clj:2037-2156`, `:1922-1979`, `:2001-2035`, `:696`; `instrument.clj:844-896`, `:898-908`, `:927` | reload, changed-source retry, re-arm |
| the prepl client | `fresh_operator.clj:1166-1191`, `:1609`, `:1820-1910`; `bin/test-check:1-53` | one form, one terminal value, `:out` events, silence bound |
| process facts | `process.clj:13`, `state.clj:224`, `:1150`, `:1199-1223`, `:1262` | pid + start instant from the OS; the property names the root; the socket answers health |
| MCP | `script/seon/dev/mcp.clj:847-860`; `.codex/config.toml:2-3` | `eval_clj` `jvm` binds no custody: `(seon.operator/connection "default")` |

### 3a. Development MCP is the first implementation slice

Before attributing a startup failure, record the actual host and toolchain after the owner’s macOS 27 upgrade: `sw_vers`, `uname -m`, `java -version`, `bb --version`, executable resolution and bounded bridge startup output. Compare those observations with the configured MCP command. A stale or stopped JVM is not evidence of an OS regression. This preflight and any repair belong to implementation; the documentation revision launches no tools, JVMs or operator processes.

Repair discovery/registration and total status projection through `script/seon/dev/mcp.clj`, `bin/mcp-server` and `.codex/config.toml` before using missing tools as proof of a stopped runtime. The existing bridge deliberately stays source-independent and discovers endpoints per call (`script/seon/dev/mcp.clj:11-15`); its `execute-runtime-status` at `:764-791` must report a missing/degraded observation explicitly. Keep that boundary while the operator removes redundant process records.

The open evidence is `docs/seon/issues/runtime-status-throws-on-a-map-entry.md`, `seon-mcp-tools-absent-in-codex-lane-again.md`, and `the-supported-mcp-runtime-tools-were-absent-from-a-bounded-lane.md`. Reproduce the map-entry projection through the actual status route, name the throwing owner, and fix it there; do not infer that `enrich-collection-tail-elisions` (`:511-529`) caused the recorded `dissoc` exception. Coordinate B3’s flat-error conversion and B2’s value rendering at their existing owners.

Acceptance is a fresh bounded lane exposing both tools, receiving complete `runtime_status` and `(let [c (seon.operator/connection "default")] {:value (+ 1 1) :basis (seon.db/basis-t (seon.db/db c))})` envelopes, and reconnecting after the owner replaces the JVM. Cover an ordinary map entry, absent cluster, timed-out observation and ambiguous selection. Tool registration cannot be proven by the server’s own tool list. No hand-written replacement transport is introduced. These tool files add an explicitly unpriced repair to §2h; count their net change separately until measured.

## 4. REPL protocol

Historical abbreviated rows in §1 are observations, not executable forms or promised fresh values. At implementation, retain the exact request/envelope for each measurement. This standalone read provides explicit custody and missing-projection evidence without changing runtime state:

```clojure
(let [conn (seon.operator/connection "default")
      db (seon.db/db conn)
      p (seon.db/carried-projection db)]
  {:basis (seon.db/basis-t db)
   :commit (datahike.api/commit-id db)
   :projection-present? (some? p)
   :file-rows (seon.db/q '[:find (count ?e) . :where [?e :seon.fn.file/relative-path]] db)
   :function-rows (seon.db/q '[:find (count ?e) . :where [?e :seon.fn/sym]] db)})
```

The following change probes are scenarios for the implementation harness; placeholders are not executable evidence. Before running one, write its complete fixture-bound request and assert the named terminal facts. Historical forms remain attributed, never recast as newly executed measurements.

Codex reaches the REPL through `.codex/config.toml` → `bin/mcp-server` →
`eval_clj` (`jvm`, `read_only` for reads) against the owner’s live `default`, or its scratch
cluster `bin/seon --root tmp/b1-root start b1` (directory created first; a
fresh cluster seeds agent `root`; downed and deleted after). Bind any aliases and connection locally in the actual submitted form; do not depend on session `def`s. Read-only measurements use `read_only true`; publication, cache-write and fixture mutation probes do not.

| When | Form | Expect |
|---|---|---|
| before | the four §1 forms | the recorded values |
| before | `(time (seon.cluster/refresh-source! "." ["src/my/note.clj"] "default"))` after a docstring edit | ~2.7 s; phases in `:out` |
| before | `(time (clj-kondo.core/run! {:lint ["src/my/note.clj"] :cache-dir ".clj-kondo/.cache" :config-dir ".clj-kondo"}))` | the tool's one-file cost with cache |
| probe | reset population on a scratch branch: time construction, `transact!`, validator, commit separately; then `d/load-entities` of the same datoms labelled non-equivalent | the §2b attribution |
| probe | `run!` over the explicit 381-file list with per-file `:group-id` (needs the fork) vs serial: diff normalized definitions/calls/arities/findings first | fidelity, then time |
| probe | `path-digests` over every stored path vs the hook's bb walk | ≤ 150 ms retires the walk |
| probe | agent form: prelude + `:cache false` vs cache on, for a form using `seon.db`, on `default` and on an older fork | equal findings or the prelude stays |
| after | `(time (seon.cluster/refresh-source! {…}))` docstring edit | ≤ 700 ms; zero caller files; two transactions |
| after | contract edit on a function with 2 caller files | exactly those 2 files linted; their rows in report 2 |
| after | `(d/q '[:find (count ?e) . :where [?e :seon.program/definition-digest]] (d/db conn))` | = fn + test + ns + schema rows; `analyzed-source-digest` absent |
| after | agent evaluates a byte-identical `defn` of a core function; read its row digest and the adopted row's | equal; acquisition binds the JVM root |
| after | `(seon.cluster.source/current store)` vs the cluster row's `:seon.source/commit-id`; `(d/q '[:find ?d . :where [_ :seon.source/digest ?d]] …)` | equal after adoption; no seal datom |

## 5. The work, ordered as commits

Each commit: HEAD loads (`clojure -M -e "(require …)"` for touched
namespaces), `require :reload` of them on `default` succeeds, and the named
probe answers. Recovery when it breaks anyway: `bin/seon reset --force`,
which discards all database facts/history in that store and live private/result objects. Preserve required evidence before recovery; disposability permits loss, it does not imply that no durable data existed. A lane never resets `default`;
RESET NEEDED items are batched by the orchestrator with hook publication
paused. Every retirement converts ALL callers, schema consumers and tests in
the same commit.

| # | Commit | Net | Probe / seam |
|---|---|---:|---|
| 0 | repair the MCP registration/status boundary in §3a; retain source-independent discovery through operator changes | measured | fresh-lane tool availability, complete status and bounded evaluation |
| 1 | after §2e’s ownership/refusal probes pass, delete `discard-obsolete-cache-entries!`, the second `run!`, the hook transit diagnostic `:318-456`; regression for the six §2e cases through the real captured-source adapter | −190 | `(require 'seon.fn.analyzer)`; case table green, including rejected-candidate retry and competing analysis |
| 2 | manifest dissolved: `build-manifest`, `database-manifest`, `manifest-data`, `artifact-by-path`, `manifest-function-symbols`, `replace-manifest-artifacts`, `published-index-rows` → rows from analysis; `full-source-refresh!` = capture → compare → classify → lint → rows; manifest schema keys deleted with their last reader | ≈ −600 | `adopt-nochange` row; `runtime_status` |
| 3 | caller-less `fn.clj` vars (pack §4) and their `fn_test.clj` sections; three `sha-256` copies → `seon.id/sha-256` (`[bytes]` argument shape at each caller) | ≈ −1,400 | `(require 'seon.fn 'seon.test.cache 'seon.schema)` |
| 4 | `caller-files` widened to the step-9 attribute set; transaction 2 reconciles complete caller rows; `index!` returns both reports | −20 | regression: docstring edit lints zero callers; contract edit lints exactly the direct callers; arglist/privacy change too |
| 5 | `:seon.program/definition-digest` declared and written by `program/definition-digest`; `analyzed-source-digest` retired with `fn.clj:1545,1558,2710,2717,2820`, `test.clj:683,1018,1030,1527,1545` (B4 file: presence reads renamed, `definition-digests` deleted), `declaration-digests`, `changed-schema-keys` | −70 | **RESET NEEDED**; digest-equality probe (§4) |
| 6 | `publish!`: seal row, `publication-input-digest!`, `upsert!`/`populate-upserts!`, aggregate `:seon.source/digest` deleted; `test-input-digest` producers stop only with B4's reads (`test.clj:900,916,1365,2161`, `runner.clj:3639`, `fast.clj:30`) converted in the same commit — B4 seam, stop if held | ≈ −250 | **RESET NEEDED** (schema keys) |
| 7 | snapshot/toolchain: `source-snapshot`, `current-source-snapshot`, `require-publication-resources!`, the second observation; `input-roots`/`input-paths`/`gitlink-digests`/`toolchain-dependencies` moved into `source.clj`; step-4 classification with its typed refusals | ≈ −200 | `adopt-noncore` row |
| 8 | adoption: commit-id compare on every request; roots from both databases; `reload-order` refuses; post-reload digest verification replaces the blind retry; `instrument/apply!` receives `:seon.instrument/changed-identities` (A1 owns `apply!`; the producer and A1 consumer land together; existing broad arming remains beforehand) | −40 | `adopt-first` row; a forced reload refusal leaves the prior record |
| 9 | reset cold path: tempid = identity print for submitted rows, keywords inside the map, `index-tempids` deleted; the §2b attribution probe recorded FIRST | −120 | `init-zero` row and the phase table |
| 10 | one progress argument; `:seon.config.source/phase-bounds-ms`; the nine mechanisms and the publication-phase bounds deleted; `test/runner.clj:60` converted; script re-homed and its grep rewritten | ≈ −200 | each phase fires as a typed error under a 1 ms bound |
| 11 | [B1b — operator and boot rewrite](lane-b1b-operator-and-boot-rewrite.md): replacement client/boot/store admission, tool and surviving-maintenance caller conversion, old files and superseded tests removed in one slice; temporary tool/boot breakage permitted inside the slice | measure deletions, moves and new code separately; ~900 is not a ceiling | B1b’s eight scratch-root drills; tools restored; orchestrator restarts `default` once |
| 12 | hook: publication = ~40 lines over `prepl-eval!`; queue/worker/result files deleted; `.codex/hooks.json` untouched | ≈ −350 | live: named-file edit → adopted in `default` (browser observed separately) |
| 13 | after the reset and the §2d conditions: `:current-source {:enabled true}`, comment block deleted; shell-created file observed | −12 | one real hook event |
| 14 | `publication-base!` and the `test.cache` base callers (after B4 lands) | −49 | B4 seam |

## Graph fidelity before selective test execution

The declaration graph must separate actual calls, callable references and symbols
stored as descriptive data. A schema permitting a symbol does not prove its consumer
invokes that symbol. The [graph-fidelity issue](../../../seon/issues/call-graph-fidelity-selection-awaits-adopted-proof.md)
records source-matched examples where keyword joins add non-lexical call edges;
static-plus-declared reach is not measured execution reach.

Actual dispatch owners declare `:seon.fn/invokes`, a set of qualified attribute
keywords whose function values they invoke. Canonical file and agent declaration
analysis retain that metadata, and the shared dependency query follows those
declarations. Merely reading a function-valued attribute, recording an operation
symbol, or mentioning a renderer key creates no invocation dependency. Exact
replacement must remove a withdrawn declaration as well as install a new one.
Keep lexical calls, references and declared dispatch distinguishable when explaining
selection. This declaration does not claim to solve arbitrary higher-order flow.

Before B4 narrows selection, prove both sides with the real analyzer and canonical
publication: a genuinely dispatched configured handler remains a dependency, while
an error operation symbol creates no invocation edge merely because another function
mentions that attribute. Renderer declarations participate through the consumer that
actually dispatches them. A selected test must have an explainable dependency path
and edge provenance. Preserve conservative treatment of genuinely unresolved dispatch;
never remove real edges merely to obtain a smaller count. Re-measure representative
selection sets after this proof; no reduction is promised from the existing counts.

## 6. Better than the floor — probes that decide

| Candidate | Probe | Decides |
|---|---|---|
| reload from the CAPTURED bytes (`clojure.lang.Compiler/load` with a `StringReader`, `*file*` bound) instead of `require :reload` re-reading disk | adopt after editing the file between capture and reload | deletes the post-reload digest check and the retry at `cluster.clj:696` |
| schema declarations and rows in ONE transaction on reset (`rschema` updates as schema datoms are added, `transaction.cljc:539-615`) | transact `[attr-decl {row}]` on a scratch branch | collapses the cold path's ordered transactions |
| the pathless request replacing the hook's bb walk | `path-digests` over 791 paths | ≤ 150 ms retires `bin/seon-hook:1688-1819` |
| clj-kondo `:parallel` with per-file groups (fork: `group-id` per explicit file) | fidelity diff, then time | 15.4 s → ? only with equal output |

## 7. Tests

| Test | Disposition |
|---|---|
| `fn_test.clj` sections for the caller-less vars | die with the vars (≈ 800 lines) |
| 13 single-`deftest` `publication_*`/`source_*`/`fn/publication_*` namespaces | collapse to `cluster/publication_test.clj` (edit → two reports → head → adoption, one class each) and `fn/publication_test.clj` (capture/lint/rows/diff), sharing the canonical base plus ONE small fixture program (three files under a fixture root, never `src/`) published once per namespace |
| the 600,000 / 900,000 / 1,200,000 ms escapes (five `publication_*` namespaces, `source_nochange`, `test/publication_test`) | fixture defect (a complete publication per fixture); the shared base removes the bound; `publication_host_test` (real boot) declares 60,000 with the measured 12.8–16.2 s boot as its reason |
| `boot_test.clj` (4 × 600,000, 2 × 90,000), `cohost_boot_test.clj` (180,000) | genuinely long: 60,000 each with the measured boot |
| `dev/fresh_operator_test.clj` (41), `dev/fresh_operator_reset_test.clj` (14), `operator_test.clj` (34) | process-record/advertisement/claim/reap/phase-log drills die with the mechanism; cold start, stop, exact down, reset and flock drills stay, moved in the same slice |
| `fn/publication_toolchain_test.clj` | dies with the aggregate; the config-change → complete-analysis case takes its place |
| new, one per class | the §2e six cases; contract change lints exactly its direct callers in report 2; docstring edit lints none; explicit no-change returns the commit id with zero transactions; pathless discovery admits an added file and retracts a removed one; unsatisfiable reload refuses; each phase bound fires typed; a reload refusal leaves the adoption record; byte-identical agent redefinition hashes equal to the core row |
| the lane runs | only tests reaching its change, in-process through B4’s final `seon.test/run` on the exact supplied program; use currently admitted entry points only until their complete caller-conversion slice; never a suite |

## 8. Done, landing note, stop rules

**Done** when: HEAD loads (`clojure -M -e "(require 'seon.fn 'seon.fn.analyzer 'seon.program 'seon.cluster 'seon.cluster.source 'seon.operator 'seon.operator.state)"`);
the re-homed script prints `adopt-nochange ≤ 100`, `adopt-noncore ≤ 700`,
`adopt-core ≤ 1500`, `adopt-first ≤ 100`, `fork < 1000`, `init-zero ≤ 60000`
ms with phase times and work counts; the platform tier is green (orchestrator);
the disjoint source/script/hook ledger reports progress against the approximate 8,900-line target without dropping a guarantee;
one live hook adoption is observed in `default` after the coordinated reset.

**Landing note**: `docs/prds/agent-platform/landing/lane-b1.md` — every §4
form with its value before and after, the script rows, the §2b attribution
table, the probe answers (§6, either way), the RESET NEEDED commits, and the
seams handed to A1 (`:seon.instrument/changed-identities`; `source/database`
carried projection), A2 (validator share of the reset transaction), B2
(acquisition by canonical definition identity against the actual installed callable and dependency context), B3
(`issue/index!` 16 ms/note; the digest D1's conflict identity hashes), B4
(`definition-digests` deletion, the six aggregate reads, `runner.clj:60`,
`publication-base!`).

**Stop** at a held file; aggregate retirement while B4 still reads it; A1’s unlanded arming consumer; or an unproven cache, loaded-callable or publication-custody guarantee. Preserve the existing mechanism while independent reductions continue. Raw import is diagnostic only and cannot replace admitted writes because it is faster. Captured-byte reload and agent-cache substitution are decided by the equivalence probes, not by a line target.
