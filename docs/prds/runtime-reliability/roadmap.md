---
type: prd
status: active
tags: [prd, database, flow, agent, web]
---

# Runtime reliability refactor roadmap

## Outcome

Turn the proven Seon prototype into one small, explicit system that can be
started, understood, repaired, and extended without knowing its archaeological
layers:

- one authoritative JVM database/heavy-compute server;
- one canonical CLJS agent and web UI implementation;
- one versioned local writer protocol with a clean future remote-transport seam;
- one database-derived block/render/surface model;
- one robust development operator;
- one tiered behavioral test system; and
- no paused application, compatibility path, duplicate reactive channel, or
  stale vocabulary left in active code.

The refactor succeeds by deleting overlap. It does not add an authorization
system, a second renderer, a Seon-specific cloud object layout, a second event
bus, a local authoritative browser writer, or prose-heavy context intended to
compensate for unclear functions.

## Current position

**Current phase: branch graduation and architecture reconciliation.**
The permanent JVM database server, canonical CLJS runtime, Babashka operator,
database protocol, shared Datastar feed, database-authoritative program/schema
projection, and focused test doors are already active. Work from the original
phases 3–5 landed out of sequence while bugs were being removed, so the old
“phase 3 of 6” label no longer described reality. The execution ledger below is
retained as implementation evidence, but the architecture audit's branch-sized
PRDs now own the remaining order; this branch must not remain the container for
every local ambition. Remote replication, cloud topology, browser replicas,
offline mutation, mobile packaging, and the full paid Inspect AI battery remain
explicit follow-on work rather than completion gates.

### Program task ledger

This is the high-level program ledger. Each pending unit gets its own
`docs/prds/<chunk>/` folder before detailed research or implementation; its
roadmap then owns the exact files, dependency ledger, evidence, and commits.
Research may run in parallel across the independent domains named below, but
implementation follows the dependency edges and never creates competing
database, renderer, runtime, operator, or packaging mechanisms.

| Order | Unit | State | Depends on | Measurable exit |
|---|---|---|---|---|
| 0 | Current branch graduation | **IN PROGRESS** | none | Reconciled docs and successor PRDs; clean full pod/writer/operator/Inspect-offline gates; destructive default reset; live CLJ+CLJS MCP, browser, gzip SSE, database read-back, restart, and retained-result/query-budget proof; legacy lane evidence classified before cleanup. |
| 1 | `database-lifecycle-recovery` | **CARVED + GROUNDED** | 0 | Fresh, converged, config-free reopen, clean restart, crash recovery, canonical coordinates, as-of/fork/restore/undo, and multi-form transitions pass without replay or duplicate registries. |
| 2 | `reactive-render-units` | **CARVED + GROUNDED** | 1 | One runtime-observed unit engine serves root, agent, canvas, debug, and data views; helper-indirected reads update; unrelated writes do no work; equivalent tabs share bounded plain-data/output reuse. |
| 3 | `database-browser` | **CARVED + GROUNDED** | 1, unit contract from 2 | Entity, refs, transactions, provenance, and history are navigable through bounded Datahike index cursors; closed details construct no expensive body; no global scan or second feed exists. |
| 4 | `root-workspace-sessions` | **CARVED + GROUNDED** | 2 | Root has its distinct system layout and concise context; ordinary-agent cards use the same derived focus; database-backed per-tab locations prove two tabs do not fight. |
| 5 | `agent-canvas-interaction` | **CARVED + GROUNDED** | 2, 4 | The one `my.canvas` path proves every control, validation/error result, focus/pin/clear transition, reactive update, and narrow/wide layout in a real browser. |
| 6 | `agent-runtime-correctness` | **CARVED + GROUNDED** | 1 | Raw model replies are preserved; every complete form is attempted; async contracts, plan authority/evidence, retries, errors-as-values, and measured process containment cannot wedge or fabricate agent evidence. |
| 7 | `inspect-autocomplete-evidence` | **CARVED + GROUNDED** | 0, 6 | Inspect source is content-pinned; preserved lane evidence is classified; the reviewed ACME tool-refinement results land through canonical `my.*` schemas/functions; large-planner/small-executor and simpler-model tool-use trials have reproducible task/scorer/provenance evidence. |
| 8 | `independent-downstream-distribution` | **CARVED + AUDITED** | 0, stable runtime/package contracts from 1 and 6 | A clean ACME checkout builds, customizes, starts, MCP-evaluates, restarts, and reads back from released Seon SDK/runtime/writer artifacts while the Seon source checkout is unavailable. |
| 9 | `local-performance-graduation` | **CARVED; FINAL** | 1–8 | Destructive acceptance matrix and real-browser journey pass; explicit cold/warm latency, idle CPU, event-loop, heap/RSS, feed/render, and grown-database budgets are green; superseded worktrees/processes are safely retired. |

Parallel work is deliberately bounded:

- while unit 0 runs, research may independently ground database lifecycle,
  reactive/Datahike cursor behavior, runtime containment, Inspect/model
  evaluation, and release packaging in their exact `reference-code/` sources;
- units 2 and 3 may share a read-only dependency audit, but unit 3 consumes the
  settled unit contract instead of inventing its own transition/feed path;
- units 4 and 6 may be implemented in parallel after their database/runtime
  prerequisites because they own separate UI-session and agent-loop domains;
- the separately owned ACME tool-refinement lane remains isolated until its
  commits and evidence are handed back for unit 7 review; and
- unit 9 is the only final graduation gate and cannot be parallel-claimed from
  partial subsystem evidence.

Immediate unit-0 queue:

1. **In progress:** the documentation hierarchy and practiced REPL-driven
   workflow are corrected. The 51-report localization census in
   [[research/research-localization-classification-2026-07-14]] assigns 23
   reports to one successor, retains 18 as graduation evidence, and keeps ten
   cross-owner reports as link-only shared input. Execute those moves in five
   backlink-checked groups.
2. **In progress:** reconcile this ledger with the generated open-issue index
   and archive only findings that have committed behavioral and live proof.
3. **Complete:** run one non-overlapping complete default checkpoint: operator,
   writer, pod, and offline Inspect.
4. **Complete for the default cluster:** destructively reset/rebuild, then
   prove routes, browser/static console state, server-side gzip feeds, database
   read-back, restart, and both MCP runtimes.
5. **Complete audit; cleanup not authorized:**
   [[research/legacy-lane-retirement-audit-2026-07-14]] classifies every
   retained old-lane commit, ignored database/blob, worktree, and process.
   Detached `seon-plan-fix` is the sole checkout eligible for later
   user-authorized removal; all others retain explicit evidence gates. Do not
   touch the active ACME agent worktree.
6. **Complete:** all nine successor PRDs are carved with dependency edges and
   falsifiable acceptance matrices. Database lifecycle, reactive render units,
   database browser, root workspace sessions, canvas interaction, and agent
   runtime correctness, and Inspect/autocomplete evidence have current-source
   dependency audits; downstream distribution has a no-source consumer audit.
   Ground local performance before implementation reaches the final unit.

The 2026-07-14 unit-0 checkpoint passes operator 100 tests/592 assertions,
writer 50/308, pod 1,307/6,182, and offline Inspect 311 passed/eight expected
environment skips. The complete pod run first exposed two AI environment-
fixture failures; REPL evidence showed ambient `SEON_AI_EXTRA_BODY` leakage,
the fixture was corrected at its owner, and the repeated complete gate is the
green count above.

A destructive public reset rebuilt writer, client, bootstrap, and CSS, then
returned watcher, writer, and pod ready. `/`, `/data`, the root gzip feed, and
the data gzip feed served; both feeds emitted immediate
`datastar-patch-elements` frames. A real in-app browser rendered the root shell
and database page with no console warnings/errors. Its root long-lived feed
remained on the loading shim, matching the documented browser-bridge SSE
limitation rather than contradicting the server-side gzip proof.

Unified raw MCP calls reached both current tool names. Before restart, the JVM
and replica read basis `536870926`; the JVM additionally exposed branch `:db`
and commit id, proving the replica's missing commit field recorded by the
database-lifecycle audit. After public restart both runtimes re-resolved and
read basis `536870929`. A bounded query failed as
`:datahike/budget-exceeded` data at observed two/allowed one and the next normal
query returned both agents. Retention admission rejected a 300,000-weight value
against the 262,144 cap and immediately admitted `42`.

### Resume checkpoint — 2026-07-14

Completed and committed on this branch:

- one maintained `AGENTS.md` authority per directory, with `CLAUDE.md` symlinks;
- one client-neutral `docs/seon/issues/` authority, generated/validated index,
  parent-agent handoff rule, and bounded startup triage;
- one automatic CLJ/CLJS/CLJC changed-test decision over the existing operator,
  database-server, and pod runners, with token-bounded summaries and complete
  retained evidence;
- the reviewed autosuggest/plan/Inspect behavior, without its duplicate context
  path; and
- a green complete CLJS checkpoint of 1,305 tests/6,175 assertions plus focused
  live hook proofs on all three Clojure file types.

The issue authority's generated index is the live count. Startup triage's
process-safety blocker is now implemented pending final live proof: maintained
Datahike commits `1e78cb9c` and `6f90b339` add synchronous query/pull work,
result-node, and
shallow-weight budgets plus safe query-cache admission; `seon.db` clamps hard
defaults; and `result/<id>` admits bounded immutable values before transcript
recording or retention. Focused library proof is 117 JVM tests/309 assertions,
105 CLJS tests/825 assertions, and the exact nested find-pull budget probe at
three tests/21 assertions. The latter commit also fixes planned scalar and
collection `:in` find-pull projections with a portable three-test/12-assertion
JVM regression. Seon proof is query/pull clamp and recovery 1/7,
database 50/346, read observation 8/76, eval memory 13/40, result slots 8/29,
record/retry 28/130, writer 50/308, and operator 84/539.

The fresh default cluster rebuilt and returned ready with watcher, writer, and
pod alive. Live MCP evaluation reached both CLJ and CLJS runtimes. A query with
`:seon.db/max-results 1` failed as structured `:datahike/budget-exceeded` data
after observing two rows; 100 repeated exhausted queries returned control and a
normal query still returned all three agent rows. A 300 KB string was replaced
by the 256 KiB retained-result descriptor and the next eval returned `42`.
The complete post-change CLJS checkpoint is 1,305 tests/6,175 assertions with
zero failures and errors. The dependency-aware changed-test path now reaches
the same complete immutable artifact without narrowing selectors or dropping
the canonical test manifest: the mixed `deps.edn`/`src/seon/db.cljs` proof
passes operator 84/539, writer 50/308, and pod 1,305/6,175.

After pinning `6f90b339`, the complete local checkpoint remains green at
operator 89/562, writer 50/308, and pod 1,305/6,175. A default-cluster restart
built and published the version-2 default artifact, reconciled fresh watcher,
writer, and pod processes, and returned ready. Live MCP evaluation of the exact
scalar-input find-pull shape returned `[[{:seon.agent/id "root"}]]`, proving
the maintained fix through Seon's running CLJS database boundary.

The cross-lane audit in
[[research/inspect-autocomplete-lane-integration-audit-2026-07-14]] confirms
that the five stable behavior commits are integrated or patch-equivalent and
that no old lane commit is a safe new cherry-pick. Four display-v3 ideas remain
to be reimplemented through one structured database-derived export; ignored
database, scorer, and continuation evidence must be preserved before worktree
removal. Active Inspect callers no longer invoke retired per-pod or ACME
lifecycle commands or assume ports 7980/7981; lease-dependent modes now stop
before subprocess/model work until the operator exposes ownership-fenced
transitions. The current offline Inspect gate is 311 passed/eight expected
skips.

The dependency/Shadow/ACME audit in
[[research/dependency-shadow-mcp-acme-audit-2026-07-14]] confirms the current
`deps.edn` split and unified dynamic-port MCP boundary. The operator now has one
explicit default/ACME artifact-flavor record that selects build id, isolated
Shadow cache, output, and version-2 manifest; the complete operator checkpoint
passes 94 tests/581 assertions. The flavor also owns the managed watcher build,
cache, and readiness. The process graph now makes the default watcher the sole
owner of the canonical `:test` build and publisher: default watches `client`
plus `test`, while ACME watches only `acme-client`; command construction,
readiness, and build-failure detection derive from that one flavor-owned build
vector. The affected operator gate passes 16 tests/55 assertions; concurrent
live artifact proof remains before the collision issue closes. `bin/acme` now
delegates only semantic target operations;
structured status publishes cluster/database/artifact/process identity and
dynamic web/CLJ/CLJS endpoints; foreign listeners are explicit ownership
conflicts; and both `up` and reset refuse to create a fresh `db/` beside a
preserved legacy `store/`. Read-only probes detect writer and pod conflicts on
both preserved port pairs while the default target remains ready. This closes
the wrapper/watcher/status safety slice but does not make ACME safe to start:
archive/drain/reopen/read-back and browser proof remain. Active ACME source and
config now resolve `steps-surface-html` and the error card, and use card CSS;
the exact isolated `acme-client` compile exposed and removed the stale renderer
symbol, then completed with zero warnings. The generated tracked entry bundle
is removed because the flavor manifest/build graph, not Git, owns artifacts.
Inspect's per-sample owner/token, isolated coordinate allocation, frozen
artifact selection, and token-fenced create/restart/release lease also remain.

The preservation manifest in
[[research/worktree-evidence-preservation-manifest-2026-07-14]] inventories all
nine worktrees, dirty patches, ignored databases/blobs, stable continuation
evidence, display-v3 scorer/report artifacts, and four live orphan processes on
ports 7980–7983. No worktree or database cleanup is authorized until closed
archives and read-back proofs exist for the 44 MB stable and 4.2 GB display-v3
databases.

The current checkout's separate 208 MB legacy ACME database has crossed the
read-back gate: content-addressed package `38409f97…` verifies 11,791 files,
and historical network-denied read-back recovered basis `536871171`, 220 schema
attributes, three agents, 44 evals, 14 plans, and all 38 referenced blobs with
no copy mutation. Its source bytes remain preserved because internal staging
is not durable promotion. A current ACME boot on alternate port 7994 then
proved the complete current path. Protocol errors now preserve their original
stale-basis kind, so fresh declarative seed reconciliation retries and the pod
reaches ready. Default remains ready on 7890 while ACME is ready on 7994 with
separate process ownership, database, Shadow cache, client build, output, and
dynamic CLJ/CLJS endpoints.

The post-integration audit in
[[research/dependency-shadow-mcp-acme-post-integration-audit-2026-07-14]] then
found and closed two remaining cross-flavor ownership defects. Default alone
owns `client` plus the canonical `test` artifact; ACME owns only `acme-client`.
An ACME restart left the complete `out/test` tree byte-identical at
`8d822f86…`. The one MCP adapter now discovers both flavor-owned Shadow port
files, evaluates both cluster-qualified CLJS roots and both CLJ writers, and
rejects bare `root` as ambiguous. Focused MCP proof passes 12 tests/44
assertions and the complete operator checkpoint passes 94 tests/581
assertions. Both live CLJS classpaths resolve maintained Datahike `6f90b339…`,
Konserve `df6818d4…`, superv.async `3e6ed755…`, and partial-cps `1e119b03…`;
both writer artifacts use the same root `:writer` Datahike/Konserve basis.

Inspect remains deliberately on its previously proven installed framework
build while the mutable source checkout is newer and dirty. Content-pinning
that source dependency and recording it in run provenance is an open
reproducibility issue; it does not block simultaneous default/ACME runtime
experiments.

The independent distribution audit in
[[../independent-downstream-distribution/research/independent-acme-distribution-audit-2026-07-14]] establishes the
next ACME boundary: ACME is the representative downstream product and must
build, run, and customize a released Seon without a Seon source checkout. This
is not implemented. The writer uberjar and source-checkout customization seams
exist, but `bin/acme`, `acme/deps.edn`, the `acme-client` Shadow definition,
base config include, operator, bootstrap/source/assets, and dependency bases
still come from this checkout. The current client entry is a development loader
into a checkout-local Shadow runtime, and the nominal packaged process graph
still starts a compiler watcher.

Carve the no-source downstream release into one focused successor PRD, ordered
after this branch's local graduation evidence. The implementation order is:

1. define a versioned compatibility manifest covering Seon/source, database
   protocol, config/SDK ABI, Java/Node requirements, writer/runtime/bootstrap/
   source/assets, maintained fork identities, npm lock, and license/SBOM data;
2. publish the maintained dependencies and public CLJS source/macros as an
   immutable downstream SDK coordinate;
3. produce a relocatable, devtools-free Node runtime package with bootstrap,
   bounded program-source corpus, static assets, and production npm closure;
4. project development as watcher + writer + pod and packaged operation as
   writer + pod from the one operator process graph;
5. replace the hard-coded ACME flavor with a validated consumer descriptor for
   source/preload, deps/npm, config, brand, package, and cluster defaults;
6. add one source-repository release command that builds/tests and assembles
   the versioned writer uberjar, runtime, SDK, manifest, hashes, SBOM/notices,
   and license/source metadata; and
7. prove one clean ACME checkout can build, start, customize, MCP-evaluate,
   restart, and read back its database while the Seon checkout is inaccessible.

The verified current defect is tracked by
[[../../seon/issues/downstream-runtime-package-is-not-self-contained]]. The
standalone writer jar remains only the database-process artifact; it cannot
replace the CLJS pod/runtime SDK where agents, eval, rendering, and web UI live.

Before implementation resumes, reconcile the target architecture from
[[research/architecture-target-drift-audit-2026-07-14]] in its recommended
order, then carve the remaining work into the audit's focused PRDs. The already
completed developer-feedback/operator work does not need a second branch. The
first implementation unit after documentation reconciliation is the smallest
owner for eval/query materialization bounds; broad live-agent drives wait until
that blocker is falsified.

## Active execution ledger

Exactly one slice is `IN PROGRESS`: Slice 0 closes this branch by reconciling
the architecture and carving the remaining scopes into focused PRDs. Slice 4
landed early and is complete. The other former slices are retained below as
evidence but are explicitly carved out; do not mark another one in progress on
this branch. Each successor PRD closes only after code, focused tests, live
proof, architecture update, roadmap update, and bounded commits land.

### Live browser baseline — 2026-07-14

The first public-control journey ran against the unchanged default cluster
before source implementation. It established these concrete failures:

- `/` is the ordinary agent layout around `system-view`: it shows “agent root,”
  “← all agents,” a canvas pin, the plan/transcript rail, and a recursive root
  card. Ordinary-agent card bodies can be blank even when the same agent page
  has a valid welcome canvas and purpose.
- Creating an agent updated the root card grid, and sending a message updated
  the shared/agent headers, proving the browser feed and Datastar morph path are
  connected. The root system canvas remained internally stale: the header
  showed three agents with one running while the canvas showed three idle, zero
  turns/evals, and no activity.
- On the ordinary agent page, submitting a planning request updated the headers
  to running while the already-open plan and transcript surfaces remained “no
  plan yet” and “no events yet.” A fresh gzip feed over the same database
  rendered the submitted message and plan facts correctly. The defect is
  incremental invalidation, not database state, renderer output, or idiomorph.
- Source trace confirms the cause: `seon.ui.agent-view/transition` consults exact
  captured reads only after a changed-attribute gate derived from the renderer
  function's own analyzer keyword literals. That set is intentionally
  non-transitive, so helper-indirected reads in `system-view`, plan, and
  transcript never reach replay. Runtime observations must be correctness
  authority; declared attrs cannot veto them.
- One open root debug view rerendered on routine message/run/blob/turn commits at
  roughly 409–1,135 ms per broadcast. The ordinary/root agent feed transitions
  observed in the same journey were roughly 63–189 ms. Closed debug units and
  unchanged blocks still need query/SCI/serialization attribution before a fix.
- The root turn opened with roughly 19,000–21,000 prompt tokens; the ordinary
  agent's later planning turns approached 25,000. A request for one brief root
  reply consumed ten turns. A request to create a three-step plan, make the
  first step active, and stop before doing the work reached thirteen turns and
  continued executing. Both probes required the existing stop endpoint.
- The normal agent page exposes no visible stop/resume control, so a human
  cannot easily interrupt this behavior through the UI.

These observations are baseline evidence, not accepted target behavior. Each
must gain an owning behavioral regression plus a repeated real-browser and
server-side gzip-feed proof before its slice closes.

### Source-grounded implementation decisions — 2026-07-14

The Slice 0 library and runner audits close two design questions before source
implementation:

- Reactive correctness and active reuse require no cache dependency. One
  normalized active render unit retains its current plain inputs, renderer
  digest, exact observed reads/results, and last serialized element until its
  final consumer closes. Only a measured reopen/cross-subscription hit rate may
  justify a recent-output LRU. If that gate passes in the Node renderer,
  `lru-cache` 11.5.2 is the proven candidate behind the existing view-unit
  owner; no JVM cache or renderer-facing cache API is added.
- Focused CLJS test latency is repeated Shadow/JVM startup: measured current
  bundles ran representative focused namespaces in under two seconds after
  roughly ten-second one-shot compiles. Preserve Shadow and fresh Node
  isolation, but let the managed Shadow JVM maintain the complete test artifact
  and compiler graph. Namespace dependency selection is sound today;
  automatic function-to-test selection is not, because complete call edges do
  not exist.
- Automatic edit feedback uses the trusted synchronous `PostToolUse` hook to
  return `additionalContext` to the active agent. The Babashka hook now
  normalizes Codex `apply_patch` and Claude `Edit`/`Write` and calls the same
  `bin/seon test changed --path PATH` operation a human calls. The managed
  Shadow process watches `client` and `test`, publishes a bounded immutable
  complete-runtime artifact plus graph manifest, and a fresh Node process runs
  the selected reverse-transitive namespace closure. A direct real-path proof
  selected one namespace and passed 4 tests/16 assertions in 4.5 seconds
  without a compile. Test failures are advisory and never gate edits. Shadow
  remains compilation and dependency authority; do not add
  autorun, a test daemon, a second registry, database notification facts, or a
  polling requirement. The Codex hook definitions are currently discovered but
  untrusted, so live proof requires one user review after their implementation
  is final.
- Development evaluation now has one repository-owned Babashka MCP server with
  explicit `eval_cljs` and `eval_clj` tools. The CLJS side retains Shadow's
  bencode/nREPL sessions and cluster-qualified database-agent routing; the
  writer exposes Clojure 1.12 `io-prepl` on loopback port zero. Both sides
  discover actual checkout/cluster port files, bound tool output and deadlines,
  and report process restarts explicitly. Claude's `seon_cljs` server name is a
  compatibility registration of this same implementation; the removed JVM MCP
  entry is gone. This is a development probe boundary, not a new test runner or
  typed database administration protocol. ACME artifact flavors and Inspect
  live-caller migration remain outside this unit and therefore remain open.
  Closing a timed-out `io-prepl` socket bounds the MCP caller but does not
  forcibly interrupt an arbitrary CLJ form already executing in the writer;
  stronger interruption/isolation remains the separately measured decision
  identified by the MCP audit.
  The post-integration verifier also closed per-form `io-prepl` queue leakage,
  Shadow's `:repl/exception!`/`:repl/print-error!` success misclassification,
  the writer port-file override gap, temporary nREPL session leaks, and
  pre-display response accumulation. The canonical container no longer passes
  development REPL arguments, so production retains only the typed database
  protocol. Focused MCP/writer gates pass 9 tests/39 assertions and 1 test/4
  assertions; live JSON-RPC proved rejected multi-form input preserves CLJ
  state and a thrown CLJS form is a tool error.
- Dated diagnostic evidence from earlier on 2026-07-14 found a process-global
  Malli projection leak, duplicate async completion, and incomplete-run
  misclassification. The repair preserved the distinction between the live
  declaration candidate and activated immutable projection, validated first
  writes against that candidate, and classified a cold bundle load only when
  zero test namespaces started. The focused schema/database gate passed 56
  tests/391 assertions and the original contaminating order passed 52
  tests/361 assertions. This is retained repair evidence, not a current gap:
  the latest complete CLJS checkpoint is green at 1,305 tests/6,175
  assertions.

The completed autosuggest lane is now integrated as five bounded commits. The
active Inspect SWE-bench arm derives restricted egress from the selected model
provider, standard openai-compatible Inspect mode declares its `openai`
dependency, and long-term planning is a first-class Inspect task over the
existing restart driver. Its offline good/bad arms score 1.000/0.000. The
Inspect suite passes 314 tests with eight environment-gated skips after fixing
one stale pre-refactor registry assertion. In `my.plan`, a repeated open
same-title `plan!` is refused instead of duplicating a forest, while
`reconcile!` now has one EDN-tree update format and no markdown parser. Main's
related-step preflights and surface vocabulary were preserved; the focused plan
gate passes 40 tests and 241 assertions. A paid/live planning drive remains
later acceptance work, not evidence implied by these offline checks.

The initial 2026-07-14 post-integration complete CLJS gate compiled
successfully, then ran 1,299 tests and 6,117 assertions in 234 seconds with 99
failures and 10 errors. The first failures occur in database/schema state and
cascade into later state, render, reactive-call, and router fixtures through
missing or diverged registry facts. This established the falsification baseline
at that point: do not interpret a focused green gate as a complete checkpoint,
and do not optimize by hiding the cross-test state leak.

After candidate validation and exact collector/projection restoration, the
next complete gate ran 1,300 tests and 6,123 assertions with 34 failures and 5
errors. `seon.db-test` is green inside the complete process; the remaining
clusters begin at missing-agent rendering, state scratch-schema ownership, and
warning fixtures that still lose schema/program projections. The default
runner stream is now compact progress plus counts/failure index, while the
complete expected-negative-path transcript remains in its bounded log;
`--verbose` opts back into live raw output.

The missing-agent failure was a public database-contract mismatch: Datahike's
pull path resolves lookup refs strictly and throws when the target is absent,
while `seon.db/pull` and `entity` promise nil. The single database boundary now
resolves an existing eid first, preserving malformed/non-unique-ref errors but
returning nil for valid absence. The combined database/render gate passes 75
tests and 411 assertions; canvas rendering again omits a missing agent.

The state/warning cascade had two causes. `extra-core-test` restored the active
projection but discarded declaration candidates loaded after that projection;
cleanup now restores both distinct states and asserts exact equality. The
unmarked-entity warning also still searched canonical schema properties for a
retired derived id-attr copy; it now consumes the active projection's entity
catalog. The contaminating replay/state/warning order passes 30 tests and 111
assertions, including core identity entities and accepted-schema self-healing.

The next complete checkpoint (1,301 tests/6,126 assertions) proved two more
test-harness leaks. The SCI helper fixture captured a registry baseline during
module loading, before later test namespaces registered, then restored that
stale partial state after each example. It now captures and restores projection
plus collector at test execution time; the SCI/state/warning order passes 34
tests and 124 assertions. The reactive-call harness also replayed an unseeded
isolated database, making an intentionally incomplete schema authoritative and
leaking it forward. It now runs the real boot seed before replay and restores
state per example; reactive call plus live router passes 6 tests and 34
assertions. The subsequent complete checkpoint passes 1,301 tests and 6,159
assertions with zero failures and zero errors. Test output is now
pay-for-what-you-use: the terminal shows progress and a bounded verdict, while
each invocation retains an unabridged timestamped log plus a stable namespaced
EDN report pointing to it. The compiler and database processes share the Java
26 resolver, and the resolver canonicalizes Homebrew paths instead of hiding
overwrite warnings.

The durable evidence, pinned sources, executable probes, and acceptance gates
are [[../reactive-render-units/research/clj-cljs-bounded-cache-library-audit-2026-07-14]],
[[research/test-impact-selection-and-runner-audit-2026-07-14]], and
[[research/automatic-test-feedback-infrastructure-audit-2026-07-14]]. The
cross-platform continuation is
[[research/unified-clj-cljs-cljc-test-feedback-2026-07-14]].

Repository instructions now use one maintained authority per directory:
`AGENTS.md`, with same-directory `CLAUDE.md -> AGENTS.md` links for Claude
compatibility. The verified inventory, semantic drift, client loading
differences, Codex capacity defect, platform risks, and atomic conversion gate
are [[research/agents-claude-instruction-unification-2026-07-14]]. The reviewed
autosuggest commits are integrated above; the concurrently owned untracked
autosuggest research file remains preserved.

The architecture target has now been re-audited as a separate authority from
this implementation ledger. Exact per-file drift, evidence, target-only edits,
and branch-sized follow-on chunks are
[[research/architecture-target-drift-audit-2026-07-14]]. Architecture stays
intended present tense; implementation status and migration order stay here.

The durable issue authority and startup-triage design has also been audited.
The exact move/archive/split map, one-note contract, bounded session-start
triage, and mechanical index checks are
[[research/issue-authority-and-startup-triage-audit-2026-07-14]]. The target is
now implemented as one client-neutral `docs/seon/issues/` tree. The obsolete
orchestrator-doc directory and manual current-work indexes are gone; dated
audits moved into this PRD's research, the private dual-path registry was split
and archived. At that initial implementation checkpoint, 14 open plus 86
archived notes passed `bin/issues-index --check`; the current count is the
23 open plus 87 archived notes recorded above. Root and role instructions now
require durable parent handoff and one bounded startup triage rather than a
chat-only finding or second backlog.

The first automatic-feedback implementation is deliberately namespace-level
and conservative. `seon.dev.changed-test` remains the one public decision:
Shadow supplies the CLJS graph, bounded host-only clj-kondo analysis supplies
CLJ namespace facts when available, and CLJC unions both decisions. The
operation delegates only to the existing pod, writer, and operator runners;
missing or ambiguous facts widen to the full relevant gate. It recomputes the
small host graph per request, attempts every selected boundary sequentially,
retains one EDN report plus full logs, and never gates an edit on test results.
No daemon, database projection, hardcoded test enumeration, speculative
function call graph, or fourth runner is part of this slice.

This automatic-feedback unit is implemented. Writer and operator roots are
discovered from their runners; clj-kondo derives the host namespace graph in
about 0.4 seconds; CLJ macro namespaces seed Shadow's existing graph; and CLJC
unions both platforms. Missing host facts widen, while a missing exact Shadow
manifest waits three seconds then calls the existing full `bin/test-cljs`
one-shot gate instead of running stale code or returning a 30-second dead end.
All selected boundaries run sequentially and retain commands, complete logs,
and one atomic EDN report; the hook summary uses the canonical token clipper.

Live Codex edit proofs observed: a CLJ operator edit selected two namespaces
and passed 13 tests/31 assertions; a CLJS edit selected one pod namespace and
passed 4/16; and a CLJC edit selected `seon.db.id-test` on both writer (12/75)
and pod (11/68). Hook-adapter tests cover Claude prospective parse blocking,
Codex multi-file parse-all, repository containment, advisory continuation, and
direct checker loading. Tests remain advisory; malformed source skips them.

### Slice 0 — reconcile and baseline — IN PROGRESS

The architecture documentation correction pass completed on 2026-07-14 without
closing Slice 0. Architecture now contains aspirational target truth only;
localized source instructions describe current source behavior and link target
debt here. The repository Markdown validator accepted all 20 architecture and
roadmap files with zero violations; 34 active architecture source pointers
resolve with zero missing paths; explicit date/status/phase/lane/evidence and
stale-vocabulary searches returned no target-prose hits; and
`git diff --check` passed. Slice 0 remains in progress for its broader baseline
and successor-PRD work.

Cross-lane research was reconciled without importing its implementation diary.
The reviewed repl-autosuggest/plan/Inspect changes already on this branch remain
the source baseline; standard Inspect tasks measure a model while pod-backed
tasks measure Seon through the production one-shot door. The protected
`shared-schema-section-2026-07-13.md` report found only about eight percent
namespace-block savings, so shared schema placement remains profiling-gated and
does not create a second context section now. Runtime-observed invalidation,
dedicated root/session/canvas behavior, database lifecycle, and blob policy stay
owned by their named successor PRDs. The protected untracked report remains
unchanged.

One explicit source-to-target gap remains at the batch reply boundary:
`seon.agent.turn` currently calls `ctx/strip-result-claims` before persistence
and evaluation, while [[docs/seon/architecture/context]] forbids output
rewriting in the target. Remove that filter only in the owning runtime unit,
preserve raw replies, attempt every complete parsed form, and prove that only
real execution results become evidence. Until that cut lands, localized source
instructions describe the current filter honestly.

- Reconcile every remaining claim below with active source, tests, routes,
  process/classpath inspection, and the running default cluster. Delete or
  correct stale roadmap claims rather than treating prose as evidence.
- Record the exact open files/functions/tests for slices 1–6. Search for stale
  vocabulary, forwarding aliases, compatibility branches, duplicate mutable
  authority, whole-history scans, retained database values, and unbounded
  collections.
- Preserve the concurrently owned untracked repl-autosuggest research file.
- Baseline cold/warm boot, focused gates, routes/feeds, instrumentation coverage,
  CPU, heap, RSS, and representative agent/canvas/database-browser behavior.
- Establish the browser-journey baseline below from the public UI. Record the
  visible failure, request/response, database fact, feed patch, console/log
  evidence, and affected render units rather than treating a successful HTTP
  status or static screenshot as proof of a working interaction.
- Integrate the completed root/reactive-unit, cache-library, and test-impact
  audits into the exact source/test inventory. Commit their evidence before
  source implementation and carry their falsification cases into the owning
  slice exits.

Exit: one clean, reproducible default-cluster baseline and a source-grounded
file/test inventory for every later slice. The operator gate, database boundary,
critical eval/context gates, and live instrumentation census pass.

### Slice 1 — database lifecycle and reconstruction — CARVED OUT

- Finish candidate installation of missing native Datahike attributes and make
  accepted program/schema publication fail closed: if post-commit runtime
  publication cannot complete, stop admission and reconstruct from committed
  facts through the existing projection path.
- Freeze supervisor intent so config remains operation-scoped and optional.
  Prove native backend reopen without hidden manifest/runtime state.
- Make clean restart quiesce at turn boundaries; retain the existing
  idle-and-notify recovery transition only for unexpected interruption.
- Finish one canonical `{database-id, branch, commit-id, t}` coordinate through
  reads, receipts, feeds, turns, errors, caches, and bookmarks.
- Complete read-only as-of, writable same-database branches, quiesced
  restore/undo, branch-local blob behavior, and non-autonomous forensic reads
  through the maintained Datahike lifecycle. Do not create a Seon-specific
  physical-copy implementation where Datahike already owns the primitive.
- Prove that multi-form batches attempt every complete form and persist every
  real result in order.

Exit: the full fresh/converged/config-free/reopen/restart/crash/as-of/fork/
restore/undo/batch transition matrix passes without arbitrary eval replay,
parallel registries, or compatibility paths.

### Slice 2 — lazy reactive units and database browser — CARVED OUT

- Complete `seon.db.browser` projections for entities, outbound/reverse refs,
  transactions, provenance, and history using bounded Datahike index cursors.
- Add the general maintained-Datahike `count-datoms` primitive and expose it
  only through the specified `seon.db` boundary.
- Give data details, debug panes, and root/card details stable fully namespaced
  render-unit coordinates. Closed details construct no body, source, token
  breakdown, Hiccup, or SCI work.
- Replace page-specific transition logic with one general render-unit engine
  used by root, agent, canvas/context surfaces, debug, and `/data`. Initial
  render captures nested `seon.db` reads automatically; their runtime requests
  derive attribute/entity/index/broad dependency descriptors and one reverse
  candidate index. Declared source keywords remain focus/recency hints only and
  can never gate correctness.
- Give every active unit automatic single-entry read/result/output reuse and
  normalized cross-tab sharing. Add one bounded LRU for recently reusable unit
  outputs only where profiling proves cross-subscription value; key it by unit
  coordinate, renderer/source digest, and small normalized plain data, with
  entry-count and estimated-output-token bounds. Never key by or retain a
  database/entity value, and never require core or agent-authored functions to
  call a cache API.
- Suppress identical serialized output and delete any whole-page or secondary
  feed path made redundant by the unit contract.

Exit: opening one detail pays for and updates only that detail; unrelated
transactions invoke zero corresponding queries/renderers/SCI work; `/data` can
inspect all required database facts without a global scan. A helper-indirected
read updates an already-open unit; unknown reads are conservative; equivalent
tabs share work; eviction changes latency but never output; no page owns a
second transition algorithm.

### Slice 3 — root, sessions, canvas, focus, and layout — CARVED OUT

- Reduce root's oversized namespace context by fixing selection and ownership,
  not by adding prose caps or a second root-context mechanism. Keep one concise
  role block and move operational depth into discoverable namespaces.
- Give `/` a distinct root system layout over the existing block/render-unit/
  route/feed machinery. It must not render the ordinary-agent heading, context
  rail, canvas pin, or a recursive card for root. Its primary surface is a
  responsive grid of ordinary-agent work-session cards plus calm system health
  and recovery affordances.
- Each root card previews the same database-derived focused surface used by the
  corresponding agent page. Overlay a concise derived work description in this
  order: active plan goal/title and current active-or-ready step; explicit agent
  purpose; then a bounded recent-conversation fallback. Do not persist a second
  summary projection solely for display.
- Finish bounded lazy fleet-card detail and the database-backed per-tab browser
  session model. Root redirects only the originating browser tab through the
  normal location fact/feed mechanism; a browser session and an agent work
  session remain distinct concepts in code and data.
- Complete deliberate focus: agent canvas/domain updates select their surface;
  accepted human messages and agent replies select transcript; a manual
  selection yields to later recency unless explicitly pinned; a missing pinned
  surface heals normally.
- Prove the single `my.canvas` API for buttons, inputs, selects, toggles, forms,
  state, save, show, pin, and clear under success, validation failure, handler
  rejection, rapid input, and throws. Feedback must be structured and visible
  to the agent without repairing the agent's demo for it.
- Finish the full-height responsive canvas/right rail, bounded fonts/code,
  compact plan disclosures, transcript bottom anchoring, independent scrolling,
  and focused-surface de-duplication. Keep the unused live bar hidden.
- Persist imported skill bodies through the one importer while keeping the
  default skills context block absent.

Exit: root and ordinary agents are understandable and responsive in narrow and
wide real-browser proofs; two tabs do not fight; plan, purpose, focus, message,
lifecycle, recovery, and canvas changes update only the affected root units;
every canvas control produces a fast, observable reactive result through one
route/feed/database path.

### Slice 4 — tests, operator, callers, and dead material — COMPLETED

- Audit test value by behavior and edge coverage. Remove disabled suites,
  obsolete artifacts, duplicated fixtures, context-wording assertions, and
  expected-failure log floods. Keep focused pure/database/runtime/browser tiers
  with one bounded terminal each.
- Keep one code-test runner per runtime boundary and make affected-test
  selection part of those existing doors, never a new harness. The dependable
  first stage maps changed source/test/config files to owning test namespaces
  and transitive namespace dependents; every selection prints why each test was
  included and which conservative fallback widened the run.
- Investigate function-level selection against the existing analyzer/database
  program graph. Adopt it only for edges the graph proves complete (declared
  tests, owning namespaces, requires, schema refs, and any verified call edges);
  an unknown dynamic edge, macro/build/config change, runner change, deletion,
  or incomplete graph widens to the owning namespace/tier. Never trade a quiet
  false negative for speed.
- Reuse a warm compiler/test artifact or watch process when isolation and exact
  source fingerprints prove it contains the change. Avoid repeated full
  compilation for a single var, but do not run overlapping suites in the live
  pod or let `--no-build` execute stale code.
- Measure edit-to-result latency for one pure function, one async database
  function, one namespace, changed-file impact, writer boundary, and full
  checkpoint. Set budgets from the measured baseline and keep the selection
  decision machine-readable for later profiling.
- Finish the packaged artifact manifest and typed database administration
  surface without restoring nREPL administration or a second launch path.
- Finish active Inspect caller migration and run only the bounded basic smoke.
  Coordinate ACME after the default cluster proves the no-alias cut; do not edit
  its concurrently owned lane prematurely.
- Re-run active searches for old JVM, gym, inventory, store, inspector, world,
  tile/live-tile, duplicate planner, duplicate feed, and forwarding API paths;
  delete active remnants rather than document them as deprecated.

Exit: one operator, one runner per tier, no disabled graveyard or duplicate
harness, and default/Inspect/ACME callers use the same current contracts. A
normal source edit reaches the smallest sound affected test set without a full
compile/run, an individual test remains directly selectable, and the complete
checkpoint still proves the selector itself. The final commands, fallback
rules, and cadence are recorded concisely in root `AGENTS.md` (Claude reads the
same authority through its symlink) plus the testing skill.

### Slice 5 — profiling and bug-driven simplification — CARVED OUT

- Profile cold/warm boot, five agent births, writer/receipt/replay latency,
  event-loop delay, queries, dirty-unit renders, SCI setup/body, serialization,
  gzip/drain, heap, GC, RSS, and idle CPU on small and grown databases.
- Reproduce the historical large-transcript HTML cost and 1.4–2.5 GB RSS
  sawtooth. Fix unnecessary work at its owning unit/query/cache boundary; do not
  raise budgets or hide the symptom.
- Establish explicit local budgets and mechanized failure signals. Repeat the
  profile after every material optimization and retain comparable evidence.

Exit: unchanged/open feeds remain idle, work scales with opened/changed units,
memory returns to a stable band, and the system pays only for features in use.

### Slice 6 — acceptance and graduation — CARVED OUT

- Run the complete transition/failure matrix from a destructive default reset.
- Browser-drive `/`, ordinary first-run routing, root, agent, debug, canvas
  controls, focus/pin/scroll, `/data`, two-tab navigation, reconnect, as-of, and
  responsive layouts. Verify gzip feeds server-side.
- Run complete active CLJS/writer/operator gates once, then the bounded Inspect
  smoke. Prove default first and coordinate ACME second.
- Update the one architecture, skills, runbooks, and operator help to describe
  only observed behavior. Active source/classpath/process/vocabulary searches
  must find no superseded local mechanism.

Exit: fast, stable, responsive agents; one writer, one CLJS runtime/UI, one
protocol, one operator, one reactive unit/feed mechanism, one database/program
authority, and no known local duplicate or compatibility path.

## Bug and code-smell handling during every slice

- Observe and reproduce before editing. A test or live proof must describe how
  the defect fails; source shape alone is not completion evidence.
- Fix a defect in the namespace/mechanism that owns it. Never create `v2`,
  forwarding aliases, temporary compatibility namespaces, duplicated context,
  or a second reactive/database path to avoid repairing the original.
- If a discovered bug threatens data correctness, process safety, agent-loop
  liveness, or invalidates the current slice's evidence, it interrupts the slice
  and is fixed immediately. Otherwise add its reproduction, owner, and exit
  proof to the most relevant pending slice before continuing.
- Treat unbounded collections, whole-history scans, database-retaining caches,
  source reparsing of persisted facts, mutable duplicate authority, bare keys,
  unexplained coercions, stale vocabulary, and test-only production seams as
  bugs until disproven.
- Prefer deletion and reuse. Read the current implementation and vendored
  library source before adding an abstraction; upstream maintained-library
  fixes where the behavior belongs.
- Commit each coherent gain with its focused tests. Do not accumulate unrelated
  edits. After runtime/config changes, rebuild/reset the authorized default
  cluster and prove the live path. Update architecture plus this ledger in the
  same slice.
- Keep progress visible: concise commentary after each diagnosis, commit, live
  proof, newly discovered issue, and slice transition.

## Browser journey discipline

Browser proof is continuous implementation evidence, not a final polish pass.
Every slice that changes a human-visible or user-triggered path runs the
smallest relevant journeys below before its commit and repeats the complete
matrix in slice 6. A journey uses the public UI controls a normal user sees;
direct database transactions may prepare a fixture but cannot stand in for the
interaction under test.

- Open `/` from a cold cluster. Confirm the root system layout, fleet health,
  ordinary-agent cards, useful empty state, and absence of the ordinary-agent
  rail/header/pin and recursive root card.
- Create an agent with the visible control and follow the redirect. Verify the
  new database facts, agent page, root card, and feed patch without a reload.
- Send a human message, observe the accepted-message state and agent reply,
  confirm transcript bottom anchoring and focus selection, and see the root
  card description/preview update.
- Create and advance a durable plan through the agent-facing public operations.
  Confirm the root card shows the high-level goal plus current step, survives a
  restart, and changes reactively when the active step changes. A request to
  plan and stop must not execute the planned work or consume the whole turn
  allowance; the resulting plan must contain the requested dependency shape.
- Build a canvas with a button, text input, select, toggle, and form. Exercise
  successful writes, validation rejection, handler error, rapid repeated input,
  pin/unpin, and clear. Verify visible feedback, database facts, affected-unit
  morphs, and no stale or duplicated primary/rail surface.
- Stop, resume, and recover an agent; open debug and `/data`; select context
  surfaces; navigate back to root. Stop/resume must be visible and reachable on
  the agent page. Confirm every state transition is legible and no closed
  debug/data detail performs body/SCI work.
- Run two browser tabs with independent manual focus and navigation. A root
  redirect moves only its originating tab; both tabs still receive shared
  database changes.
- Repeat the layout journeys at narrow and wide viewports. Confirm bounded
  typography/code, full-height independently scrolling panels, reachable
  controls, and no overlap with the chat bar.
- Inspect browser console errors and request failures. Because the automation
  bridge cannot prove long-lived gzip event streams, pair browser interaction
  with a server-side gzip SSE client and pod feed/broadcast logs. Capture which
  stable element ids were patched and assert unrelated units were absent.
- For every discovered defect, add a behavioral regression at the narrowest
  owning boundary and retain a real-browser reproduction. Do not add tests for
  exact context wording, generated HTML blobs, or incidental CSS class order.

## Test-selection design gate

The program graph makes affected-test selection plausible, not automatically
sound. Before implementation, the dated test-impact research must inventory the
actual `:seon.ns`, `:seon.fn`, `:seon.schema`, and `:seon.test` facts and compare
them with the compiler/analyzer dependency data and the vendored runners. The
design must answer with evidence:

- which edges are complete at edit time: namespace requires, function ownership,
  test ownership, schema references, macro dependencies, dynamic symbol lookup,
  routes/render symbols, configuration, generated sources, and JVM/CLJS wire
  contracts;
- whether individual `cljs.test` vars can run from an already-current artifact
  without rebuilding or loading unrelated namespaces;
- which process/compiler state can remain warm without sharing mutable database
  fixtures or contaminating the live pod;
- how deleted/renamed files, git staged/unstaged changes, a dirty worktree, and
  another agent's edits affect selection;
- the exact fallback ladder from changed function → owning namespace → affected
  namespace closure → runtime tier → full checkpoint;
- which existing scripts, disabled suites, artifacts, and harness remnants can
  be deleted once the one runner owns selection.

The research produces a plan and measurements, not a competing runner. The
implementation replaces the current selection logic in place, proves false-
negative defenses with behavioral fixtures, then updates the permanent root
instructions only with commands and rules that actually work.

The shared ACME/plan/REPL work is checkpointed at `3e0e0bff`; the directly
affected schema, plan, and AI dispatch CLJS namespaces pass their focused tests,
and `runtime-reliability-pre-refactor-2026-07-13` anchors the complete
`b4efd4f5` handoff. The Phase 1 baseline is
[[research/phase-1-baseline-2026-07-13]]. Since that capture, `writer-uber` and
source launch have converged on one complete `:writer` basis. The writer closure
is down from 188 libraries/194 classpath roots to 111/117, resolves the exact
maintained Datahike/Konserve SHAs, and has one SLF4J provider. `bin/test-writer`
runs only the retained writer suites. The unused query-subscription engine,
second in-process subscriber bus, dead writer operations, and alternate backend
routing are deleted, leaving raw committed-transaction fanout plus bounded
replay. The evaluator's global timeout and duplicate result-membership registry
are gone; timeout ownership follows the value and result membership is derived
from the runtime namespace. The web host now has one normalized feed registry,
database-fact-driven route invalidation, and one explicitly owned replica-feed
attachment lifecycle. The focused Datastar gate covers 38 behavioral tests
with 182 assertions. Fifteen replica tests cover 87 assertions, and 5 route tests cover
74 assertions. Writer database initialization, transaction transformation, KNN,
and publication now enter through one immutable boot-composed runtime; the
load-order callback registries are deleted, and initialization failure can no
longer publish a half-initialized connection. The live web channel now also has
one lifecycle-owned, lossless bounded coalescer: Datahike's stable listener key
is the installation authority, a coalesced window retains its complete database
evidence, and continuous structural commits cannot postpone a render past 500
ms. The atomic database-protocol cut is now implemented: keyword operations and
fully namespaced maps live once in `seon.db.protocol`; the JVM writer/server,
CLJS replica, backend adapter, connection registry, and UDS transports have
single responsibilities; legacy server/store namespaces are deleted; and the
managed database leaf is `/db`. Fifteen replica tests (87 assertions) and the
eleven-namespace writer gate (47 tests/295 assertions) cover retry/recovery,
replay/live overlap, explicit routing, generated identities, durable receipt
encapsulation, bounded publication, and lifecycle. Typed administration, cold
live transition proof, and a published artifact manifest remain outstanding.

The archival cut is now committed. `38a4dbe8` removes the atom-backed agent
membership registry and derives MCP addressing from database agent facts;
`294d47a1` removes the obsolete Rust/WASM and old Datahike prototype trees; and
`6c1079c8` removes the paused Integrant/core.async application, its entrypoints,
resources, dependency aliases, and obsolete tests. The surviving writer gate
passes 47 tests/295 assertions, direct Markdown tooling passes 22/340, and the
runtime-addressing gate passes 4/16. The large Bash supervisor is now replaced
in place by a seven-line launcher over one Babashka process graph. Kernel file
locking, exact process identity, bounded readiness-log reads, relevant-
environment digests, artifact manifests, scoped reset, and fail-closed process-
group ownership pass 10 focused tests/29 assertions. Phase 3 remains open for
active caller and test-door migration; the default-cluster cold live proof now
passes, so ACME and Inspect can follow.

The latest 2026-07-13 cold reset rebuilt a fresh default database and returned
READY. A subsequent config-free status independently reported the watcher,
writer, and pod alive and ready; operation-scoped `SEON_CONFIG` no longer poisons
permanent process identity. The pod attached its replay/live feed, replayed 2/2
forms, instrumented 767 definitions with zero bad specs, and created `root` plus
`mighty-spoons-clap`. `/` and `/data` returned HTTP 200, while the retired
`GET /agents` correctly redirects to `/`. The database-defined `POST /agents`
created readable-word agents in both direct HTTP and real-browser button proofs.
The new agent view rendered its canvas, plan, and transcript surfaces without a
browser console error; its gzip feed delivered an immediate Datastar patch. A
single-process mutation proof observed a 307 ms POST-to-patch interval, including
a 68 ms targeted render. All three long-lived processes returned to 0% sampled
CPU after agent work stopped. The cross-agent invalidation gap found by that
proof is now closed through the existing database-read observer: each rendered
surface and header owns immutable query/pull/entity observations, the normalized
subscription learns them on its shared first paint, and later candidate changes
replay results before entering Hiccup or SCI. A behavioral test proves the same
attribute changing on agent B does not materialize agent A's surface.

The canonical live-feed cut now includes `/data`: its separate connection atom,
listener flag, coalescer, uncompressed `/data/sse`, and the unused generic
`/sse` registry are deleted. A cheap `/data` shell opens `/data/feed`, which
uses the same gzip, heartbeat, latest-wins backpressure, response-owned cleanup,
and normalized subscription cache as agent/debug views. Live proof observed a
database transaction produce a second data-browser morph and then retracted the
proof row. A first-paint ownership bug discovered during that proof is fixed at
the shared feed boundary: pre-normalized sockets can no longer alias through a
nil cache key and receive another page's HTML. Twenty-four equivalent agent
feeds completed first paint within a 1 ms spread, closed back to empty view and
subscription registries, and used about 66 MB less heap than the prior
comparable run. The optional Caddy edge served the same gzip feed over HTTP/2
with immediate flushing; it remains outside the default development process
graph.

The UI vocabulary cut is now underway in the existing render path. Core focus
derivation uses `last-updated-surface`/`::surface-sym`, unresolved canvas facts
use one canvas warning, the overridable failure seam is `error-card`, block
slots use stable `#surface-*` identifiers, and the generated stylesheet uses
`.seon-card*` plus `.surface-focus`. Focused recency, warning, render, canvas,
and agent-view suites pass with no forwarding aliases. Remaining active prose,
helper names, and downstream ACME references are part of the same in-place cut.
Canvas resolution now also has one authority: explicit pin, configured canvas
block default, derived focus, then welcome. The human renderer returns that
resolved metadata to the context block, eliminating the split reader that made
root describe `system-view` while displaying the welcome. Live root proof shows
the configured system view in both projections and a 214-token canvas block.

The CLJS test process now installs the pod's existing third-party log gate as a
Shadow preload before any test namespace. A representative database run fell
from about 1.85M estimated tokens of trace-heavy output to about 43 estimated
tokens with the same 43 tests/329 assertions passing. Canonical timestamped
test logs are bounded to the newest 20, and normal client/ACME/bench bundles no
longer preload the platform test graph.

The giant root instrumentation warning was traced to a real hot-reload defect,
not suppressed as context. The Node pod was calling Shadow's browser-only
reload-source filter after Node had already loaded the files, so it selected no
namespaces and left fresh definitions unwrapped. Reload selection now follows
Shadow's Node client semantics and re-instruments the exact changed namespaces;
a cold live census reports zero gaps. The warning remains a derived invariant
alarm and renders nothing while healthy. SCI environment reconstruction now
has one authority as well: persisted `:seon.ns/require-edges` committed at agent
birth or eval. The render-time source parser and its unbounded fallback-note
atom are deleted; focused require/replay/SCI tests pass 51 tests and 231
assertions.

A later real Shadow edit exercised the repaired hot path: six affected
namespaces were selected, 36 replaced definitions were unstrumented and
re-instrumented, both agent runtimes were rehosted, and the post-reload live
coverage census remained zero gaps.

The same no-compatibility rule now applies to renderer dependencies. The
analyzer tee's `:seon.fn/read-attrs` datoms are the sole declared read set;
recency/invalidation no longer regex-scan persisted source for old rows. This
removes a second parser and prevents strings, comments, and unresolved aliases
from inventing dependencies. The focused behavioral gate passes 16 tests and
49 assertions, including source-only omission and persisted dynamic reads.

SCI invocation routing is now local as well. Each fresh context closes over its
own input accessor and deadline; the process-global input/deadline volatiles are
deleted, including from warmup. Existing bounded-render behavior passes 7 tests
and 31 assertions, while nested or future concurrent renders can no longer
cross-contaminate one another.

SCI's process-lifetime “warned” set is also gone. Failure log/error-write
suppression now uses a 256-key FIFO window: persistent failures do not flood the
database or logs, and unique failures cannot grow retained memory without bound.

The direct Babashka edit hook now proves repository containment before it loads
configuration or writes diagnostics, serializes bounded diagnostic writes
across concurrent hook processes, and cannot throw from its terminal log sink.
The disabled-but-retryable Gemini queue, timestamp, and pending-file mechanism
are deleted; model review is explicit rather than an automatic network side
effect of editing a file.

The public operator now owns `test pod|database|operator|all` and delegates to
the existing CLJS and writer runners. The operator gate includes lifecycle,
artifact, Markdown, and docstring behavior; it no longer leaves the two linter
suites orphaned. The underlying focused scripts remain implementation doors,
not competing harnesses.

Focused pod selectors now drive Shadow's native compile-time `:namespaces`
input as well as runtime selection. The one test bundle has a portable
compile-plus-run owner lock, and `--no-build` requires an exact content
fingerprint over namespace selection, source/config/dependency inputs, and
downstream flavor. Concurrent agents cannot overwrite one another's running
artifact, dead locks recover, and stale bundles fail loudly.

The writer test process now suppresses only `datahike.writer` error logging:
expected transaction-conflict cases remain behavioral assertions, while their
repeated full stack traces no longer dominate a successful focused run.

The test runner's bounded full-result atom and `last-result` API are deleted.
Full run values already return through the evaluator's addressable result
symbols; only durable, queryable per-test outcome facts are projected into the
database. There is no second process-local result-history authority.

The source-substring test dependency heuristic is also deleted from both auto-
rerun selection and function status rendering. Newly defined tests still run
from the exact analyzer diff; existing-test reruns wait for durable analyzer-
derived reference facts rather than manufacturing relationships from text.

Platform tests are no longer a boot-time program-graph population. The obsolete
test preload, compile-time deftest enumerator, `!indexed-test-vars`, and
`index-tests` builder are deleted. Agent-defined tests enter through the same
analyzer tee as other declarations; the compiled snapshot reconciler removes
legacy boot-authored test rows while preserving agent-authored ones.

The source-grounded system audits are complete and committed:

- [[../database-lifecycle-recovery/research/database-runtime-responsiveness-audit-2026-07-13]]
- [[research/web-responsiveness-audit-2026-07-13]]
- [[research/live-feed-fix-review-2026-07-13]]
- [[../agent-runtime-correctness/research/agent-lifecycle-responsiveness-audit-2026-07-13]]
- [[research/seon-cli-lifecycle-audit-2026-07-13]]
- [[research/jvm-archive-boundary-2026-07-13]]
- [[research/jvm-server-cljs-client-storage-sync-2026-07-13]]
- [[research/client-distribution-and-server-rendering-boundary-2026-07-13]]
- [[research/surface-vocabulary-and-dead-ui-path-audit-2026-07-13]]
- [[research/root-view-presence-crash-batch-audit-2026-07-13]]
- [[research/cljs-test-suite-speed-and-quality-audit-2026-07-12]]
- [[research/phase-1-baseline-2026-07-13]]

Several foundational corrections have already landed:

- generated persistent identities have one schema-driven atomic allocator;
- normal transaction provenance is only resolvable user and process refs;
- cold runtime boot, agent birth, and agent resume are separate operations;
- agent birth is one transaction and ordinary resume does not write;
- the duplicate homegrown evaluator/gym is deleted; Inspect AI is the sole
  model/agent evaluation harness;
- the second complete program build and boot-time ghost-pruning pass are gone;
- the maintained Datahike/Konserve forks include effective-datom, connection,
  branch, ordered-commit, cache, and shutdown fixes;
- transaction IDs have durable same-payload receipt/recovery semantics;
- replay is bounded, cursor-checked, and deduplicated against concurrent live
  frames;
- normal transcript HTML is bounded and chat-first;
- stable render units and the lazy debug web UI are partly cut over; and
- the external shell supervisor now protects against PID reuse, lifecycle
  races, and orphan process groups.

The route schema also now records its one same-origin middleware gate as one
keyword fact. The previous vector schema became unordered cardinality-many data
in Datahike and falsely promised middleware-chain ordering that the database
could not preserve.

Those gains are the base. The remaining work is not a restart from scratch.

## Target system

### Runtime roles

| Role | Owns | Does not own |
|---|---|---|
| JVM server | serialized Datahike writes, durable Konserve storage, transaction receipts, branch/as-of/restore, schema/config commit authority, embeddings, secondary indexes, bounded heavy work | agent execution, context rendering, HTML, a duplicate application |
| CLJS UI host and agent runtimes | agent loop/eval, program reconstruction, context derivation, canvas/surfaces, Hiccup, Datastar, server-hosted agents | authoritative writes, cloud credentials, a second database |
| Browser | thin Datastar HTML, per-tab navigation identity, human input, and device-originated facts | authoritative writes, a local full-history database, JVM-only indexes |

The local development composition co-locates the JVM writer, Shadow watcher,
and Node CLJS runtime. A hosted deployment may run the same JVM server beside
headless Node CLJS agent/UI processes. Phone-class clients are intentionally
thin and connect to that hosted cluster; local phone data enters through typed
facts. Browser replicas and a native shell are later work, not a second runtime
introduced by this refactor.

### Current local data path and preserved remote seam

The current refactor proves two local contracts without turning them into
independently configured systems:

1. **Commit notification** — old/new coordinate, effective datoms, changed
   attributes, request ID, and transaction metadata for listeners, dependency
   invalidation, and durable processors.
2. **HTML delivery** — complete-element Datastar morphs for thin clients.

The authoritative local writer acknowledges a transaction after the local
Datahike commit and its same-request receipt are accepted; it does not wait for
a UI replica, remote mirror, or future cloud copy to catch up. Exact bounded
transaction replay remains available for receipts and forensics. Coalesce
notifications, never state.

The source-grounded immutable-Konserve-root and Kabel research is preserved for
a later remote-replica PRD. It does not justify retaining a second live routing
path in this branch, and its unresolved cloud/RPO/client choices do not block
the local system.

### One UI vocabulary

| Term | Meaning |
|---|---|
| block | database-owned context unit carrying zero or more render declarations |
| render | ephemeral projection for an audience/format |
| surface | resolved HTML render displayed by the web UI |
| twin | AI and HTML projections of the same block/function |
| canvas | focal, agent-controlled surface in an agent view |
| card | visual CSS component or compact/expanded face only |
| slot | named layout placement for a surface |
| view/page | route-level composition of surfaces |

Active APIs, DOM, CSS, config, skills, tests, and downstream ACME converge on
this vocabulary. There is no live-tile/tile architectural API, world view, or
inspector product name. Historical research, WIT's language keyword, Node
Inspector/CDP, Inspect AI, geometric “tile the frame,” and ordinary English are
not rewritten.

The persisted canvas attribute is already correct:
`:seon.render.canvas/content`. Do not add a stored surface/card entity.

### One database vocabulary

Seon calls the durable EAV system a **database** or **db**, everywhere. “Store”
is not a second product concept and is removed from Seon namespaces, schemas,
coordinates, functions, paths, CLI output, UI, skills, tests, and active docs.

| Canonical term | Meaning |
|---|---|
| database / db | one logical Datahike database and its accumulated facts |
| database name / database ID | routing label / stable identity for that database |
| database coordinate | `{database-id, branch, commit-id, t}` |
| backend | the physical Konserve implementation and location behind a database |
| replica | a readable local representation synchronized from an authoritative database |
| cache | bounded, discardable derived runtime data |
| blob archive | content-addressed durable large values referenced by database facts |

Third-party APIs may still use a literal `:store` key internally. That spelling
is confined to the Datahike/Konserve adapter and translated immediately; it is
never re-exported as Seon vocabulary. Ordinary English verbs in historical
material and upstream source are not compatibility APIs.

The active result-persistence ceiling follows the same rule:
`:seon.config.render/database-edn-cap`, `seon.config/database-edn-cap`, and
`SEON_RENDER_DATABASE_EDN_CAP` are the one schema/accessor/environment family.
The obsolete comparison manifest is deleted; config-free boot now means the
database remains authoritative rather than silently falling back to legacy
context.

Namespace ownership follows the same vocabulary:

| Namespace | Owns |
|---|---|
| `seon.db` | canonical public query/transaction/database API on each platform |
| `seon.db.protocol` | one platform-neutral message schema and pure protocol data transformations |
| `seon.db.backend` | JVM-only translation from fully namespaced Seon database options into private Datahike/Konserve config maps |
| `seon.db.registry` | JVM-only live connection/database/branch registry and lifecycle |
| `seon.db.browser` | bounded, index-backed, read-only projections used by the canonical `/data` database browser |
| `seon.db.transport.uds` | local Unix-socket framing and delivery only |
| `seon.db.transport.websocket` | later remote framing and delivery only |

Protocol semantics never live in a transport adapter. Every Seon-owned map key
is fully namespaced to the namespace that specs and manages it.

### Database browser target

`/data` is the one operator-facing database exploration view. It describes
facts as attributes, entities, references, transactions, and history—never as
entity kinds and never as an unqualified “inventory.”

| Region | Default cost | Expanded capability |
|---|---|---|
| database bar | O(1) database datom count/head coordinate plus installed-schema size | branch/as-of coordinate selection when lifecycle support lands |
| attribute navigator | installed schema only; grouped visually by attribute namespace | selected attribute schema, bounded AEVT/AVET rows, values, carrier entities, and cursor |
| entity table | one cursor-bounded page for the selected attribute/search | sortable visible columns only; no complete pull of offscreen rows |
| entity detail | absent until selected | EAVT facts, identity, outbound refs, reverse refs, provenance, and bounded entity history |
| transaction browser | absent until selected/opened | latest transaction metadata, user/process/instant, effective datoms, and bounded history reconstruction |
| raw data | closed stub | exact EDN/datoms for the selected bounded object, rendered only when expanded |

Navigation state is encoded in validated URL parameters so links, reloads, and
back/forward work without database writes. Index cursors replace offset walks.
A page reads at most `page-size + 1` rows to prove whether another page exists;
it does not compute an exact global count merely to render pagination. Total
datoms use the database index's counted root rather than Datahike `metrics`,
whose per-attribute diagnostics scan the complete EAVT index. Transaction
reconstruction is explicitly on demand and budgeted because Datahike does not
currently expose a TX-leading primary index.

The browser is intentionally complete: knowledge-base facts, plans, messages,
agent-authored domain attributes, schemas, framework facts, and transaction
metadata are all reachable through the same attribute/entity/ref/history
machinery. User/domain attributes lead and framework/system groups begin
collapsed, but no second KB inventory or hidden data path is created.

The source-grounded access rules are:

- EAVT cursors page entities/facts; AEVT cursors page one attribute's carrier
  entities; AVET sorts/searches values only when that attribute is indexed.
- Datahike Datalog offset/limit is not browser pagination because it slices
  after collecting/deduplicating results. Browser pages use `seek-datoms` or
  `rseek-datoms` and opaque validated cursors.
- Non-indexed values are bounded AEVT samples labeled as such; the UI never
  implies that unsupported value sorting/search is complete.
- Reverse refs probe the schema's indexed ref attributes lazily. There is no
  cross-attribute incoming-ref index, so “all incoming refs” never becomes one
  unbounded wildcard query.
- Add a general Datahike `count-datoms` API backed by the existing subtree
  `-count-slice` primitive, with CLJ/CLJS behavioral tests and Seon wrapper.
  Keep it library-general and upstreamable; do not cache counts as database
  facts.
- Transaction IDs page backward arithmetically from the database head and
  metadata reads by exact EAVT prefix. Exact transaction datoms remain a
  capped, explicitly opened history reconstruction. If profiling proves that
  inadequate, add a Datahike-owned transaction-leading index rather than a
  Seon transaction projection.

### Operator contract

The owner-selected primary door is:

- `bin/seon up` starts the complete development stack;
- it waits for real readiness and prints all useful URLs;
- it opens a browser only with `--open`;
- it makes no fake production claim; and
- paused and advanced process verbs are not part of the primary UX.

`down`, `restart`, `status`, `logs`, `doctor`, scoped
`cluster reset`, and explicit config/branch operations remain available.
The implementation is a Babashka program with process specifications and state
transitions as data; the shell file becomes a tiny launcher.

In a source checkout, every `up` performs one complete canonical writer + CLJS
build before process reconciliation, then leaves file watchers running for
incremental updates. The build artifact digest is the launch truth: a changed
artifact restarts only its dependent process; an unchanged artifact proves the
running code without a stale-log or mtime shortcut. A packaged installation
verifies immutable shipped artifacts instead of pretending to be a source
checkout.

Readiness is one atomic application-ready fact backed by direct process/socket/
HTTP verification. There is no fixed three-second stabilization ritual.

### First run, root, and human navigation

A provably fresh database is initialized once from the explicitly selected
manifest, creates the reserved root plus one ordinary readable-word agent, and
prints both URLs. `bin/seon up --open` opens the ordinary agent; `/` remains the
root system view rather than the default work destination.

Root is the system-scoped coordinator. It may technically do ordinary work, but
its small root-only context tells it to understand the fleet, start an ordinary
agent when necessary, route/delegate work, and move the human to that agent. The
role text stays deliberately short. Operational knowledge comes from root's
fully specified home-require namespace cards; entering a namespace makes its
source current and brings in the colocated/state-gated context for that work.
Root's home requirements are one complete, deliberately smaller role-specific
list, replacing the ordinary agent list through the existing scalar override.
That lets root omit workbench capabilities it has not proven it needs; do not
add a second union/merge rule. The root canvas's bounded AI twin supplies current
fleet facts through the existing canvas block; there is no second fleet-summary
instruction block. No skills catalog or long generic manual is injected merely
because the agent is root.

The root canvas is the fleet view. Its cheap shell lists every agent with
identity, purpose, derived state, and the label of its shared agent-derived
focus (pin, then agent recency, then welcome). Each human-facing agent card uses
the same surface catalog, agent-derived focus function, and compact materializer
as an agent page with no session override; the current
`seon.ui.agent-view` functions and `:seon.ui.agent-view/*` working-map keys move
to `seon.render.surface` / `:seon.render.surface/*`, and their old definitions
are deleted. Visible/expanded cards are independent view units, so one agent
update does not rebuild every preview. The root AI twin
always carries the complete compact agent list, then spends a bounded detail budget
on running, erroring, and most-recently-active agents: up to five recent
messages, recent failed-eval summaries, and the bounded AI render of their
canvas. Omitted detail is explicit, never mistaken for an absent agent.

Each browser tab has one database-backed UI-session identity. The session stores
one normalized local location fact plus a ref to the human; the transaction
already supplies recency/provenance, so no duplicate `updated-at` or active flag
is stored. On an agent page, an explicit surface pin is encoded in that
location's query component; page focus is the valid session pin when present
and the shared agent-derived focus otherwise. A root card never claims to mirror
another tab's pin. Unpinned selection, scroll position, open disclosures, and
form signals remain transient.
A human message carries the originating session ref, and each turn records the
exact inbound message it is assigned to answer. Root's fully-specified
navigation function follows turn → cause-message → web-session through normal
injection, reverse-routes an agent target, and updates the same location fact.
The tab's existing Datastar feed applies the official Datastar redirect-helper
semantics for that changed fact. In the reference SDK this is an auto-removing
script patch on the existing stream, not a second redirect event or channel.
Browser navigation writes the same fact, so root can query what the human
is seeing without a parallel presence service. Per-tab identity prevents two
open tabs from fighting over one global cursor.

### Skills are importable data, not standing context

The existing `my.skills` corpus/import mechanism is retained and refined in
place. A standard `SKILL.md` directory, CLI import, or later web upload all pass
through one parser/validator and transact the same canonical skill source facts;
config-free restart reads those facts from the database rather than requiring
the original upload path. `seon-skills` is the shipped corpus source and tool
directories are generated or validated adapter views.

Importing a skill does not install a permanent skills context block. Default and
test agents keep that block disabled so dynamic context, compact namespace cards,
current-namespace source, and colocated state-specific blocks must surface what
is actually needed. Explicit skill loading remains available as an override and
is evaluated behaviorally, not by asserting prose.

## Settled invariants

- The JVM application is archived; the JVM server is permanent.
- The canonical renderer is CLJS. The JVM never grows a parity renderer.
- `seon.db` remains the sole application database API.
- The database stores facts and canonical source forms, not processing traces,
  dirty flags, render output, or derivable lifecycle state.
- Config is optional on an existing healthy database. When explicitly selected,
  it repairs exactly its declared subset and does nothing when converged.
- A fresh writable database receives one explicit genesis/config floor, the
  reserved root, and one ordinary initial agent. This one-time birth is not a
  config-managed population on later boots.
- Malli runtime state is rebuilt once from canonical database facts. Committed
  eval changes carry exact symbol deltas; Shadow reloads query only the namespace
  resources Shadow actually loaded and restore only wrappers that are absent.
- Arbitrary evals and external effects are never replayed.
- After an unexpected runtime crash, every interrupted nonterminated agent is
  fenced back to derived `:idle`; the supervisor records one recovery anchor in
  that same transaction. The affected agents and ambiguity are projections of
  the transaction, and root renders the notice. Root or the human decides what
  to resume.
- Batch mode attempts every complete parsed form in order. A normal form error
  is persisted and does not suppress later forms; the next turn sees every real
  success and failure.
- Every database identity, map key, and public contract is fully namespaced and
  schema'd.
- `my.canvas` is the one permanent agent-facing canvas/control API; current
  agent/database identity is injected.
- Root has one concise role-specific block plus orchestration/navigation
  namespace cards. It does not receive a long generic manual.
- Skills are importable database facts but not a default context block. Dynamic
  context, compact namespace cards, current-namespace source, and colocated
  state blocks surface relevant capabilities.
- Four dormant display adapters are deleted precisely:
  `seon.agent.ctx.findings`, `inventory`, `jobs`, and `testrun`.
  Durable findings, job execution, and parsed test-run facts remain. The weak
  whole-database `db/store-inventory` API is also deleted, not renamed: schema
  discovery uses installed attributes, domain discovery belongs in small
  purpose-specific database queries, and operator exploration belongs in the
  canonical `/data` browser. A refined KB may compose those facts later without
  restoring a global inventory/context mechanism.
- One skill importer persists exact validated source; `seon-skills` supplies the
  shipped corpus and generated/validated tool views are not authorities.
- One runtime attaches to exactly one `{database-id, branch}` coordinate. The
  existing UDS path is the local behavioral authority; no permanent dual
  routing toggle survives this refactor.
- A successful write is acknowledged after the authoritative local commit and
  receipt are accepted, without waiting for UI catch-up or future cloud
  mirroring.
- One database-backed per-tab UI-session location is the only human-navigation
  state. Root redirects the originating session through the normal Datastar
  feed; there is no second presence or push channel.
- Tests assert facts, transitions, envelopes, DOM identity, omission,
  idempotency, and rendered structure—not teaching prose.
- Every replacement deletes the superseded mechanism in the same phase after
  proof.
- ACME is updated only after the default cluster passes and its current shared
  work lane is clean.

## Known defects to remove

| Area | Current defect |
|---|---|
| JVM source | The retained writer reaches twelve namespaces. The old Integrant/core.async/agent/web application remains searchable until the archive cut. |
| JVM artifact | Source and uberjar use the same complete `:writer` basis with the maintained forks and one SLF4J provider, but no published launch manifest yet records the artifact/runtime contract. |
| Dependencies | The writer and writer-test closures are honest and narrow. Heavy paused-app dependencies still live in the base graph used by old JVM/tools, and CLJS/tool ownership is not yet fully separated. |
| Writer protocol | The semantic protocol, JVM writer/server, CLJS replica, and UDS transports are separated and the duplicate operations/helpers are deleted. A typed supervisor administration surface and cold process proof remain. |
| Database vocabulary | The protocol/backend/replica path is canonical, the managed leaf is `/db`, and the generic `store-inventory` API/context/tooling family is deleted. Runtime and developer skills are converged; downstream ACME still needs the proven vocabulary cut. |
| Database browser | The obsolete inventory surfaces are deleted. `/data` uses the canonical shared gzip feed, cheap shell, schema navigator, and bounded AEVT cursor pages. Entity/ref/transaction/history units remain. |
| Developer hooks | The direct Babashka hook is repository-contained before config/artifact access, runtime-independent, locally deterministic, and log-bounded under a cross-process lock. Automatic model review is deleted. The operator gate includes its Markdown/docstring checks. |
| Operator | The Babashka graph and thin launcher are built and focused-tested; active caller migration plus default/ACME/Inspect live proof remain. |
| Tests | Public pod/database/operator doors delegate to one runner each; focused pod builds use compile-time namespace selection, one bundle lock, and exact freshness fingerprints. Disabled/paused-application tests and remaining intentional expected-failure noise still need removal. |
| UI | The four dormant context renderers and their unconditional boot load are deleted. Active symbols, CSS, DOM, docs, and ACME still need the tile-to-surface/card vocabulary cut; skill teaching is already converged. |
| Live rendering | Agent surfaces and the whole debug/data targets use runtime-observed reads; normalized subscriptions suppress identical consecutive output. Per-region debug/data unitization, layout/focus browser proof, and grown-database profiling remain. |
| Recent activity reads | `seon.render.default/recent-messages`, `seon.agent.ctx/messages`, transcript/activity queries, `seon.derive/real-eval-oks`, and the function menu independently scan and sort growing message/eval history before taking a small tail. Root's current cross-agent activity does the same over the whole database. |
| Root/UI presence | `/` already renders root's system canvas, but first-run routing, concise root role context, originating-tab identity, database-backed current location, and feed-driven agent navigation are not one finished path. |
| Root context | Root's scalar home-require replacement, sparse system-canvas pin, and ordinary-agent fallback are now distinct. Concise root role context and browser-location awareness remain unfinished. |
| Skills | `seon-skills` now generates exact shared tool adapters, Codex-only operator skills generate their Claude views, and the operator suite rejects drift. File-backed imported bodies still depend on source paths after import. |
| Prototypes | Wasmtime/WIT Tauri, Rust client-runtime, and old libdatahike CLJS spikes remain in the active tree despite settled rejection. |

## Implementation discipline

- Observe the current default cluster before and after each phase.
- Start each phase from a coordinated commit and stage only files owned by that
  phase.
- Commit small, reviewable gains; do not accumulate the entire refactor.
- Read the relevant vendored library source before relying on behavior.
- Fix Datahike/Konserve/Kabel behavior in the maintained source that owns it;
  do not copy a frozen fork of the mechanism into Seon.
- Keep one state-machine implementation behind transport/platform adapters.
- Use `apply_patch` for source edits and preserve other agents' work.
- Prove behavior at the smallest useful tier, then cold-reset/live-prove at the
  phase boundary.
- No exact context prose tests, no hidden retry-to-green test runner, no
  compatibility namespace, and no in-repo archive source tree.
- Human-visible sizes are estimated tokens through the one estimator.

## State-transition acceptance table

| Transition | Durable facts/work | Process/reactive work | Failure proof |
|---|---|---|---|
| `bin/seon up`, source checkout | fully rebuild and publish canonical writer + CLJS artifacts; no database write when converged | reconcile changed artifact dependents, start watchers, wait for atomic readiness, print URLs | no stale artifact/log truth, fixed delay, duplicate process, or manual build prerequisite |
| `bin/seon up`, packaged | verify immutable shipped artifact manifest | reconcile process identities/readiness | packaged mode never silently compiles a different program |
| fresh database | minimal genesis, native schema floor, root/process refs, explicitly selected initial config, root plus one ordinary agent | rebuild Malli/program runtime and services; `--open` selects the ordinary agent | no circular provenance, partial schema, hidden ambient config, or root-as-default-workspace |
| existing database, no config | normally no transaction | rebuild process-local handles/registries; resume durable work | restart does not “heal” by rewriting canonical facts |
| explicit config apply | exact managed-subset delta plus lifecycle intent/recovery facts | invalidate only affected projections | missing/changed/extra facts repaired; outside facts unchanged; convergence writes nothing |
| core/schema hot reload | one exact program/schema delta | load/instrument only changed dependency closure | removal and same-key schema change work; no global rescan or ghost prune |
| agent birth | one allocation transaction for identity and initial components | create compiler namespace, host, listener, wake | no cluster seed/global instrumentation; failed birth leaves no partial agent |
| agent resume | normally none | restore one host/wake from durable facts | arbitrary evals/effects are not replayed |
| unexpected runtime crash | close/fence interrupted runs, terminalize running turns, and persist one recovery anchor in the same transaction | rebuild root and safe transient services; derive the detailed notice; leave affected agents idle | no interrupted form/effect is replayed and root sees exactly which agents may need resumption |
| agent eval batch | one eval/result fact per parsed entry plus resulting domain/declaration facts | execute every complete form in order, capture each error, continue, instrument changed defs | an early ordinary error cannot erase later attempts; a process crash cannot fabricate missing results |
| local write, lost reply | one commit and one same-ID receipt | retry identical request and catch the local reader up to accepted coordinate | different-payload ID reuse rejects; every disconnect edge is at-most-once commit |
| browser action | one typed command/transaction and receipt | Datastar call → writer → commit notification → affected unit morph | no manual refresh, duplicate client state, or silent handler failure |
| root navigation | upsert the originating UI session's normalized location | the same session feed applies one redirect patch to the reverse-routed agent URL | another tab is unchanged; reload derives the selected location from the database |
| root fleet view | none beyond normal agent/session facts | cheap all-agent catalog; visible non-root card units materialize the compact agent-derived focus; bounded AI detail derives separately | every agent is represented in structured summary data; a card equals a no-session-pin agent page and never claims parity with another tab's pinned selection; unrelated cards do not render; token caps are proven without prose assertions |
| debug route closed/open | none | closed owns no debug render/listener; open activates only requested units | prompt/raw/HTML/token work is absent while closed |
| as-of/fork/restore | branch/head/intent facts through Datahike primitives | quiesce, drain, attach exact coordinate, rebuild process state | stale writers/cursors cannot cross head movement; external effects are not undone/replayed |
| stop/reset | only explicit lifecycle facts | reverse-order drain, verify PID+start stamp/process group, then mutate the named database | no global nuke, reused-PID signal, orphan child, or deletion under a live writer |

## Ordered implementation plan

### Phase 1 — review, coordinate, and freeze the archival boundary

1. Let the active ACME/plan/repl-autosuggest lane commit or clearly hand off
   its files. Do not absorb its dirty working tree into this refactor.
2. Record the exact default-cluster process set, writer namespace closure,
   dependency trees, targeted test doors, cold/warm boot, agent birth, live feed,
   browser action, CPU, heap, event-loop delay, and RSS.
3. Build the current CLJS artifact and writer artifact from a clean dependency
   state far enough to expose packaging defects honestly.
4. Verify the existing root system view, root-only blocks, multi-form batch
   behavior, and skill importer against the new settled contract before
   deciding what old material survives.
5. Create an annotated pre-removal tag or protected archive branch. Add one
   concise pointer document; Git is the archive.

Exit proof: one known commit can still start, birth/resume an agent, commit and
replay a transaction, render the web UI, and process a canvas form. Every
subsequent deletion is recoverable from the archive ref.

### Phase 2 — isolate the permanent JVM server

1. Atomically rehome the database boundary in place: `seon.store.wire` and
   `seon.server.wire` converge on shared `seon.db.protocol` plus the local
   `seon.db.transport.uds` adapter; `seon.server.store` becomes
   `seon.db.backend`; `seon.server.registry` becomes `seon.db.registry`; and
   every `:seon.store.wire/*` / Seon `store-id` / `store-path` / `store-name`
   contract becomes the fully namespaced protocol/database/backend term owned
   by that namespace. Rename the managed filesystem leaf from `/store` to
   `/db`; test databases need no migration. Do not leave aliases, forwarding
   vars, or dual protocol keys.
2. Fold the exact Datahike/Konserve fork, secondary-index source, JVM flags,
   writer dependencies, and main class into one honest server build contract.
3. Split dependency ownership into minimal shared, CLJS, writer,
   writer-test, build, and tool aliases. Remove accidental transitive reliance.
4. Fix `writer-uber` and preflight the artifact produced from the same basis
   used by local launch.
5. Add `bin/test-writer` with only writer, receipt/replay, schema bridge,
   IDs, branches/restore, storage, codec, and embedding tests.
6. Delete `seon.server.reactive`, its boot schemas/ops/hooks, and the
   in-process subscriber registry.
7. Delete the duplicate string Transit helper, unwired agent registry, facts
   POC, fake SQLite path, and unused filter/entity/pull/batch wire operations.
8. Replace arbitrary writer-REPL administration with a small typed
   root/supervisor admin surface for database/branch lifecycle and bounded
   diagnostics.
9. Keep the UDS transaction/receipt/raw-commit/replay path unchanged as the
   correctness baseline.

Exit proof: a standalone JVM process loads only the retained server closure,
opens fresh and existing databases, commits and recovers one request, broadcasts and
replays it, runs optional KNN work, performs typed admin operations, and drains
cleanly. No paused application or nREPL namespace loads.

### Phase 3 — replace the operator, archive the old application, and cut test tax

1. Replace `bin/seon` in place with a thin launcher and Babashka
   `seon.dev.cli` library. Process graph, dependencies, readiness, locks,
   artifacts, and transitions are data.
2. Preserve PID+OS-start identity, process-group ownership, atomic lifecycle
   locks, stale-artifact cleanup, idempotent reconciliation, reverse drain, and
   scoped destructive safety.
3. Make bare `bin/seon` equivalent to `bin/seon up`; `up` starts the complete
   development stack and `--open` is the only browser-launch switch.
4. In source mode, perform one complete canonical writer + CLJS build on every
   `up`, publish it through one atomic artifact manifest, then start incremental
   watchers. Restart only processes whose artifact digest changed. Packaged mode
   verifies immutable shipped artifacts. Remove presence/mtime heuristics and
   special benchmark artifact paths.
5. Replace fixed stabilization waits with one atomic application-ready signal
   plus direct process/socket/HTTP verification. Bound the Shadow JVM and make
   the current build result—not an old log line—its readiness truth.
6. Remove global nuke. Reset only a named cluster after proving its writer and
   readers are drained.
7. Port the few useful syntax/markdown/docstring checks to a direct
   Babashka/tool door. Delete the dead nREPL hook pipeline and update hook
   configuration atomically.
8. Delete the paused Integrant/core.async JVM application, old agent/providers,
   context/graph/session/embedded DB, JVM renderer/web/SSE, old MCP/REPL, app
   resources/profiles/aliases, and their tests.
9. Delete the disabled-test graveyard and the Wasmtime/WIT, Rust client-runtime,
   old libdatahike CLJS, and unused harness trees after their evidence is linked.
   Remove old Inspect run branches/artifacts after proving they are not recent or
   referenced by the concurrently active lane; do not introduce an arbitrary
   retention policy in this refactor.
10. Keep two primary code gates: focused `bin/test-cljs` and focused
   `bin/test-writer`. Separate fast pure tests from explicit runtime,
   subprocess, browser, and process acceptance tiers.
11. Remove test/demo preloads from the ordinary pod artifact. Delete hidden
    list/poll/kill and tail-retry-to-green runner behavior. Every async test has
    one bounded terminal.

Exit proof: a clean source/dependency search contains only the JVM server and
active shared CLJ/CLJC sources; `bin/seon up` brings a nontechnical user to a
ready URL; no port 7888/8080 or paused process exists; focused tests do not load
or discover archived behavior.

### Phase 4 — finish database truth and lifecycle reconstruction

1. **Exact desired-population compiler complete:** scalar,
   cardinality-many, ref/component structural comparison, omitted-attribute
   removal, stale-entity cascade, unmanaged-identity collision rejection,
   full-head fence, bounded reread/recompile, and transact-if-nonempty all run
   through `seon.state/reconcile!`. The maintained Datahike writer owns the
   atomic basis precondition and keeps an expected stale rejection out of error
   logs; the canonical UDS protocol carries the same fact end to end. Focused
   proofs cover first-use schema installation/retry and basis-stable no-op.
2. **Runtime boundary complete:** external config is operation-scoped and
   optional. A config-free boot preserves database facts, the singleton now
   stores agent/root context and skill selection needed for later births, fresh
   `bin/seon up` selects the shipped manifest once, and
   `bin/seon config apply <path>` is explicit. Singleton attribute removal now
   uses the exact compiler, and the old config-heal function/transaction are
   deleted. Remaining: freeze the payload in the supervisor intent.
3. **Canonical form cut complete:** every schema row now persists the full
   EDN-round-tripping `:seon.schema/form`; runtime function/regex objects are
   rejected as durable definitions, schema source replay and the async self-tee
   are removed, failed redefinitions restore exactly, and replay activates
   database forms before code. Native backend reopening remains.
4. **Candidate base complete:** a complete form set now builds and validates an
   immutable Malli registry, entity render catalog, and stable fingerprint
   before activation. The same projection now derives exact direct and reverse
   transitive schema-reference indexes through Malli's walker (keyword data is
   not mistaken for a reference). The renderer consumes that catalog directly; persisted
   required/id/render decomposition, its boot transaction, Datalog discovery,
   and the renderer cache atom are deleted. Remaining: compute compatible
   missing Datahike attributes in the candidate, bound
   historical projections by fingerprint. Agent program/schema transitions now
   build the complete candidate before recording; an invalid dependent contract
   becomes the eval's user-input failure and commits no declaration facts.
   Remaining: stop admission/reconstruct from committed facts if the already
   validated post-commit wrapper publication itself fails. The full evidence and failure matrix are in
   [[../database-lifecycle-recovery/research/malli-runtime-schema-authority-audit-2026-07-13]].
5. Use one analyzer/program snapshot and one exact add/change/remove
   transaction. Verify the ghost-pruning builder and every stale compatibility
   branch are absent.
6. **Incremental instrumentation active:** cold boot and Shadow reload compile
   contracts against the exact active immutable registry; an accepted schema
   change refreshes only function contracts in its old/new transitive closure.
   Delta replacement compiles completely before var surgery, so one rejected
   target leaves the prior wrappers untouched, and omitted spec/schema-error
   facts become explicit retractions rather than surviving identity upserts.
   The immutable candidate now also owns every parsed/validated function
   contract and its exact schema-reference index. Cold publication consumes
   that data directly, and schema/function deltas use the old/new indexes with
   no contract-row scan or EDN reparse. Shadow's Node build-notify path now
   selects exactly the resources its Node client actually required; the former
   browser helper returned an empty set after reload and silently left hundreds
   of replaced live vars unwrapped. A cold reset instruments the complete
   projection, and a live reload repairs only the affected namespace rows.
   Remaining: close admission/reconstruct when post-commit publication cannot
   complete.
7. Reconstruct declarations/program state only. Never replay arbitrary evals or
   process-local values.
8. **Crash recovery complete:** the cold-start supervisor transition fence/closes every
   interrupted open run, mark its running turn `:interrupted` without executing
   or fabricating an eval, leave every affected agent derived idle, and persist
   one idempotent recovery anchor in that same transaction. Derive affected
   agent/run/turn refs and prior/current coordinates by joining the anchor's
   transaction to its changed datoms and commit parent; root renders that join
   as the notice. Recovery runs before agent resume, a second pass is a no-op,
   terminated agents are untouched, and focused tests prove no fabricated
   messages. Remaining: have clean planned restarts quiesce at turn boundaries
   rather than masquerading as crashes.
9. Make batch evaluation explicitly non-fail-fast: attempt every complete parsed
   form in order, persist each success/error at its transcript position, and show
   the complete real batch on the next turn. Later dependent forms may fail
   normally; no synthetic results are inserted.
10. On a provably fresh database, create root plus one ordinary agent through the
    normal atomic birth compiler exactly once. Existing/config-repair boots never
    reassert or recreate that ordinary agent.
11. Finish the canonical `{database-id, branch, commit-id, t}` coordinate through
   reads, receipts, feeds, turns, caches, bookmarks, and errors.
12. Finish read-only as-of, same-database writable branches, non-autonomous forensic
   runtimes, quiesced restore/undo, branch-local blobs, and crash recovery
   through the maintained Datahike lifecycle.

Exit proof: fresh, converged, partial-config, config-free, hot-reload, first-run,
birth, resume, multi-form failure, as-of, fork, restore, undo, and crash-boundary
transitions satisfy the acceptance table with no broad rewrite, physical copy
fork, arbitrary replay, or duplicate runtime registry. A crash leaves affected
agents idle and one exact notice visible to root.

### Phase 5 — converge the local web UI and agent-facing surface

1. Freeze the vocabulary in active architecture, then rename the existing
   symbols in place:
   `last-updated-surface`, `::surface-sym`,
   unresolved-canvas warning, error-card seam, surface renderers, fleet cards,
   `#surface-*`, and `.seon-card*`.
2. Update every producer/consumer/schema/test and regenerate CSS atomically.
   Do not leave forwarding vars or old selectors.
3. **Complete:** the dormant findings, inventory, jobs, and test-run display
   adapters, their unconditional boot requires, display-only tests,
   `db/store-inventory`, `my.kb/inventory`, warning coupling, and teaching
   references are deleted. Durable KB facts, job controls, parsed test-run
   facts, and lifecycle tests remain. The header keeps its cheap database link
   and `/data` is the only exploration surface.
4. Port `/data` in place to the canonical render-unit and shared gzip Datastar
   feed lifecycle. **Feed cut complete:** `/data/sse`, `!data-connections`, its
   listener flag, broadcast loop, and the generic `/sse` registry are deleted;
   the route returns a cheap shell and `/data/feed` owns one normalized view
   descriptor. **Bounded navigator complete:** the full `[?e ?a]` plus
   transaction-history scans are deleted. The default reads installed schema;
   selecting an attribute reads a cursor-bounded AEVT page through the shared
   observed-read boundary. **Remaining:** let `/view/unit` activate entity,
   transaction, reverse-ref, and history details only while opened. URL params
   remain the shareable navigation state.
5. Add fully specified, read-only `seon.db.browser` projections backed by
   Datahike indexes and bounded pages: installed attributes/schema, attribute
   values and carrier entities, entity facts/outbound and reverse refs,
   transaction datoms/user/process/instant, and history. Omit unavailable
   sections. List user/domain/KB data first and keep framework/system groups
   collapsed, while making every installed attribute reachable. Counts/samples
   that cannot be obtained cheaply are lazy units with explicit budgets, not
   work performed on every transaction. **Partial:** installed attribute
   grouping, schema detail, AEVT datom rows, opaque cursor continuation, and
   exact reactive replay are complete. Entity/ref/transaction/history units
   remain.
6. Add the general Datahike `count-datoms` public primitive over its existing
   subtree count-slice implementation, then expose it only through the
   fully-specified Seon database API. Use cursor windows—not Datalog
   offset/limit—for every page. Prove CLJ/CLJS, current/history, indexed and
   non-indexed edge behavior in the maintained fork and prepare it for upstream.
7. Give each browser region a stable fully namespaced unit coordinate and
   observed database dependencies. A commit rerenders only the open summary,
   table, or detail whose read result changed. Attribute pages match changed
   attrs; entity/reverse-ref pages match the existing changed datoms/entity IDs;
   immutable past transaction units never rerender. Equivalent tabs compose
   through the existing cache/fan-out; identical output sends no morph.
   Pagination and row windows are bounded, and closed details construct no
   Hiccup or SCI work. **Partial:** agent surfaces already transition by exact
   observed read result; the current whole debug/data targets now use the same
   observer and normalized subscriptions suppress identical consecutive
   morphs. `/view/unit` activation now returns the Datastar SSE patch protocol
   rather than inert bare HTML, so expanded debug disclosures actually mount.
   A canvas SCI failure is recorded once at the bounding source; its outer
   fallback wrapper cannot transact again and create a render/error
   invalidation loop. The source-checkout operator also restores fail-loud
   development rendering by default while retaining an explicit graceful-mode
   override. The canvas context now composes the existing bounded render-fn cap
   over its AI twin and renderer source independently; the observed failing
   agent fell from 11,870 to 4,358 estimated tokens without another stored
   projection or context path. Debug panes and database details still need
   their own coordinates and bounded projections.
8. Keep installed-schema and direct attribute-presence queries as the small
   composable agent/domain discovery tools. A later KB surface must be a focused
   domain query through the normal block/render/surface mechanism, not a
   restored global inventory/context block.
9. **Adapter generation complete:** `seon-skills` is the runtime authority;
   `bin/seon skills sync` generates exact shared tool views, operator-only Codex
   skills generate their Claude adapters, and `bin/seon skills check` runs in
   the operator gate. Refine the existing import path in place: one
   parser/validator and desired-fact compiler accepts the shipped corpus, an
   operator directory, or uploaded `SKILL.md` content; it stores exact canonical
   source/body facts so a later config-free restart does not depend on the
   original path. Keep default/test skill context blocks absent; explicit load
   remains an override through the normal block mechanism.
10. **Bounded fact-owner readers active:** `seon.agent.message` owns recent
    conversation/global message windows, `seon.eval` owns recent per-agent/global
    eval windows and bounded error-storm signal, and `seon.log/tail` remains the
    one error-log tail. Datahike's
    fixed lazy `rseek-datoms` is exposed only through the fully specified
    `seon.db` wrapper; agent context and root system activity now compose these
    bounded append-order streams without a complete history scan. Function-menu
    ranking and header error-storm detection now consume those same fact-owner
    windows; their duplicate full-history queries and the parallel
    `:seon.derive/error-storm` vocabulary are deleted. The normal HTML
    transcript caps each fact-owner source before materialization and then
    applies the same retained-turn policy; the deliberate AI transcript history
    policy is unchanged. The redundant tail step in `seon.render.default` is
    also gone. No recent-list projection is stored and no caller relies on a
    growing Datalog sort. This item is complete.
11. Restore a deliberately small root-only role block after behavioral review:
    root understands the fleet, starts/routes to ordinary agents, and handles
    recovery notices. Keep root's home requirements as one complete curated
    scalar replacement rather than unioning in the ordinary workbench; align the
    no-config ordinary fallback so it does not grant orchestration. Put
    operational detail in root's orchestration/navigation namespace cards;
    moving into a namespace brings its full source and colocated/state-gated
    context. Root's system canvas contributes bounded current fleet facts
    through the existing canvas AI twin. Do not restore the retired instruction
    wall or add a second fleet block.
    Move the current surface catalog/focus/materialization logic out of the page
    layout into `seon.render.surface`, colocating its fully namespaced schemas
    and deleting the old `seon.ui.agent-view` definitions, then use it for both
    the agent page and root's fleet cards. Every agent gets a cheap card shell;
    visible non-root cards show the compact agent-derived focus, and closed
    details lazily show up to five recent messages and failed evals. Root remains
    in the agent list, but its own card is summary-only: materializing root's focused
    `system-view` inside itself would recurse. The root AI
    twin lists every agent and includes bounded canvas-AI/message/error detail
    for non-root running, erroring, then most-recently-active agents until its
    block cap.
    Make `/` + its one feed the only fleet/root view. Delete the separate
    `/agents` GET/feed; keep `POST /agents` as the sole HTTP birth action, and
    canonicalize `/agent/root` to `/` before opening a feed.
    **Route cut complete:** the duplicate fleet renderer, shim, feed, route
    datoms, and display-only tests are deleted; agent birth is now a canonical
    database route at `POST /agents` instead of a conflicting static
    supplement entry, the shared header calls it, and `/agent/root` redirects to `/`. Remaining work in this
    step is the concise root role block, bounded lazy card detail, and session-
    aware navigation.
12. Add one fully specified database-backed UI-session model owned by its web
    namespace: per-tab identity, human ref, and normalized local location only.
    Keep `{database-id, branch, session-id}` in `sessionStorage`. Bootstrap reuses
    it only when the attachment matches and the lookup ref exists for the current
    human; otherwise allocate the replacement through the one writer-side
    `seon.db.id/allocate!` path, return/store it, then open the keyed feed. If a
    reset or restore removes an open feed's session, clear that tuple and force
    the same bootstrap instead of client-upserting a ghost identity. Compare
    normalized locations and transact only when changed.
    Encode a manual agent-surface choice in the location query; no query value
    means the shared agent-derived focus. Do not persist scroll,
    disclosure, or form-signal state.
    Link an inbound human message to its originating session and record the
    exact message assigned to each turn as
    `:seon.agent.turn/cause-message`. Browser route changes and root's protected
    `seon.web.session/select-agent!` update that same location fact; its
    context-only injected session ID comes only through
    turn → cause-message → web-session, caller input cannot override it, and
    absence is an error. The existing feed applies
    the official Datastar redirect-helper semantics only when its normalized
    current route differs from the stored location.
    Do not store duplicate agent/route projections, `updated-at`, active flags,
    or a presence registry.
13. Keep `my.canvas` as the permanent API, make its leaf encodings
   browser-portable, and ensure its docstrings/Malli errors make buttons,
   inputs, selects, toggles, forms, state, save, pin, and clear self-explanatory.
14. **Agent surface observation complete:** materialized agent surfaces and both
   headers capture runtime database reads, normalized subscriptions learn those
   observations from the shared first paint, and changed-result replay suppresses
   unrelated cross-agent Hiccup/SCI work. Remaining: carry the same unit contract
   through data/debug/root units, add the measured bounded compositional output
   cache, and suppress identical serialized output in the existing Datastar feed.
15. Pay only for open/visible work: debug remains an empty shell until opened;
   offscreen/closed bodies are stubs; hidden source/result/error trees are not
   constructed.
16. Finish the responsive layout: full-height primary canvas, independent
   readable right rail, bounded fonts/code, compact plan disclosures,
   transcript bottom anchoring, no visible focused duplicate, and no live-bar
   overlap.
17. Prove agent-derived focus: canvas/domain writes select canvas; accepted
    human messages and agent replies select transcript; an unpinned rail choice
    yields to the next deliberate update, while the explicit per-tab pin remains
    until released or its surface disappears.
18. Prove every `my.canvas` control with valid, invalid, rejected, rapid, and
    throwing handlers. Feedback is structured and visible to the agent.
19. Add one optional root system-status surface only after the operator owns a
    reusable process-status projection. It samples pod/writer liveness, CPU,
    RSS, uptime, and feed pressure on demand; it persists no rolling projection,
    refreshes as one view unit on the existing feed at a modest cadence, and
    contributes only anomalous status to root's AI context. Do not revive the
    paused JVM health application or create a second metrics stream.
20. Cold-prove the default cluster, then coordinate the same no-alias cutover in
    ACME and rebuild/reset it.

Exit proof: one database transaction causes only affected units to render and
one Datastar path to update; the agent view, compact previews, forms, focus,
scroll, debug view, database browser, CSS, skills, and ACME use the same
render-unit/feed contract. `/data` can inspect schema, entities, refs,
transactions, provenance, history, and KB/domain facts without a global
per-commit scan. `/` is root's coherent system view; root can start an ordinary
agent and redirect only the originating browser tab through database facts.
Grown-database idle feeds do not repeat SCI/HTML work or sawtooth RSS.

### Phase 6 — local acceptance, profiling, documentation, and graduation

1. Run focused structural/generative tests during each phase, then run the
   complete active CLJS and writer gates once at the boundary.
2. Run a bounded Inspect AI smoke check that covers the basic agent loop,
   database write/read, and one canvas interaction. The full paid planning/
   memory/UI battery is deliberately deferred; this branch only proves the
   refactor did not break the harness or basic agentic work.
3. Run the full transition table from a destructively reset authorized default
   cluster, including failure injection at every local process, write/receipt,
   restore, and crash boundary.
4. Browser-drive `/`, first-run routing, agent, debug, canvas controls, root
   navigation, focus, scroll, `/data`, route facts, two tabs, reconnect, and
   responsive layouts. Verify gzip SSE server-side.
5. Profile cold/warm boot, five agent births, writer latency, sync, dirty-unit renders,
   SCI invocations, gzip bytes, browser morph time, event-loop delay, CPU, heap,
   GC, and RSS on small and grown databases.
6. Explicit database-read and retained-result budgets are implemented at their
   owners. Complete the remaining render budgets and fail loudly when
   agent-authored work exceeds them; do not hide unbounded work by increasing
   timeouts.
7. Update active architecture, skills, runbooks, Docker/build docs, and
   operator help to describe only proven behavior. Mark historical material as
   history rather than rewriting it.
8. Prove the default cluster first, then ACME. Mark the PRD complete only after
   active searches and runtime process/classpath inspection find no superseded
   mechanism.

Exit: fast, stable, responsive agents; one writer, one CLJS runtime/UI, one
local protocol, one operator, one test authority split, one vocabulary, and no
known duplicate or compatibility path in the local cluster.

## Deferred follow-on direction — preserve evidence, do not implement here

The research remains useful, but these are separate PRDs after local graduation:

- **Remote writer/replica.** UDS remains local and WebSocket is the likely remote
  adapter over one `seon.db.protocol` state machine. Immutable Konserve-root sync
  with head-last publication is the leading state-transfer design; exact replay
  remains opt-in. Before adoption, Datahike/Kabel must own and prove foreign
  listeners, deadlines, cancellation, reconnect, backpressure, branch scoping,
  and clean shutdown. Do not delete source that is useful to that work, but do
  not run a second live path now.
- **Cloud.** Evaluate GCS first when cloud work begins. The current owner policy
  is local-authority acknowledgment: success does not wait for a cloud mirror,
  so the eventual deployment must publish and measure its nonzero cloud RPO
  honestly. Exact mirroring/topology remains undecided.
- **Thin/mobile clients.** Phone-class clients are thin and use a hosted JVM +
  Node cluster; the UI is phone-focused and primarily admits local device facts.
  Browser/IndexedDB replica shape, history depth, native packaging, and offline
  mutation semantics remain open. They must reuse the canonical CLJS renderer
  and database protocol rather than create a second client runtime.
- **Inspect AI.** Full paid journeys for long-term planning, later database
  recall, interactive UI construction, and cross-agent behavior are deferred.
  Stale old run branches/artifacts may be deleted after active references are
  checked; no permanent retention policy is selected here.

## Commit and proof policy

Each numbered phase is several small commits, not one giant patch. A normal
sequence is:

1. contract/schema or build-boundary commit;
2. implementation and caller cutover;
3. old-path deletion;
4. focused behavioral proof;
5. cold/live evidence and active-doc update.

Do not begin the next phase while the current phase has a known broken
transition. Report remaining work honestly; a green test suite never overrides
the running system.

## Definition of done

- A new user runs bare `bin/seon` or `bin/seon up`, sees truthful build/readiness
  progress and useful URLs, and can operate agents without knowing process names.
- A first-ever database contains root plus one ordinary agent; `--open` lands on
  the ordinary agent while `/` is root's system/coordinator view.
- Cold and warm starts are bounded, idempotent, and free of ghost pruning,
  broad schema/program rewrites, global instrumentation, or duplicate services.
- Agent birth/resume/eval/crash and config/schema/program/restore transitions are
  explicit and database-correct; a crash never replays effects and leaves
  interrupted agents idle with one exact recovery anchor and a derived root
  notice.
- Batch mode attempts every complete form and preserves each real success/error
  for the next turn.
- One JVM server owns writes/storage/heavy work; one CLJS source owns agents and
  rendering on server and client.
- The local UDS writer/read path has one fully namespaced semantic protocol and
  same-request recovery contract; remote transport remains a documented seam.
- Canvas, surfaces, cards, blocks, slots, and views mean one thing everywhere;
  tile/live-tile/world/inspector are absent from active product vocabulary.
- The normal web UI is bounded and reactive; closed debug/offscreen content
  costs nothing; context and HTML render only when used.
- Buttons, inputs, selects, toggles, forms, errors, focus, and scroll work live
  from database facts.
- Root can see the originating tab's database-backed location, start an ordinary
  agent, and switch only that tab through the existing Datastar feed.
- `/data` lazily explores every installed attribute, including KB/domain facts,
  without a global per-transaction scan.
- Standard skills import into canonical database facts through one path, while
  the default/test skills context block stays absent and root context stays
  concise, namespace-led, and state-gated.
- The active test doors are fast by default, bounded, behavioral, and contain no
  retired application or homegrown evaluator.
- The old JVM application and rejected prototypes are recoverable from Git but
  absent from active source, classpaths, startup, tests, docs, and skills.
