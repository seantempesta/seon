---
type: prd
status: active
tags: [prd, architecture, agent, database]
---

# Program synthesis 2026-07-21 — cleanup-first sci transition

The program goal is and always was **cleanup**: deps, build targets,
packages, unembarrassing source, bugs and rough edges chased down. The
sci pivot is the biggest cleanup lever (−~5,100 production LOC, −~2,800
test LOC, −6 shadow builds, one compiler instead of two), not a
replacement goal. This document reconciles the 2026-07-21 audit
evidence, de-risk probes, and owner rulings into the ordered
work-package series. It supersedes the exploration framing above the
U-ledger in [[roadmap]]; the U-ledger rows remain the unit history.

## Evidence base (all committed, dated 2026-07-21)

Audits: [[research/audit-host-robustness-2026-07-21]] ·
[[research/audit-deletion-inventory-2026-07-21]] ·
[[research/audit-db-parallelism-isolation-2026-07-21]] ·
[[../generate-code/research/audit-execute-code-pipeline-2026-07-21]] ·
[[research/audit-benchmark-pkg-readiness-2026-07-21]] ·
[[research/audit-doc-drift-2026-07-21]] ·
[[../generate-code/research/llm-retry-fallback-resilience-2026-07-21]]

De-risk probes (all executed, all favorable):
[[research/probe-shared-var-protection-2026-07-21]] (stamping, no sci
patch) · [[research/probe-writer-connection-pool-2026-07-21]] (pool
viable, recipe + numbers) ·
[[research/probe-interrupt-core-merge-2026-07-21]] (safe with caveats;
with-ctx lazy-forcing requirement; perf tax accepted at agent scale).

## Owner rulings ledger (2026-07-21)

1. LLM timeout is fallback-eligible.
2. Planning fallback = Muse; DeepSeek is the implementer model.
   Experiment: DeepSeek thinking for planning vs no-thinking for
   implementation.
3. npm: per-cluster installs under `data/clusters/<name>/pkgs/`, shared
   downloads (bun global cache); package/metadata areas clearly separate
   per cluster on BOTH JS and JVM sides.
4. Maven/CLJ third-party code runs in a separate disposable JVM package
   host (it will get dirty); Seon-level resilience: crash → easy
   restart, lose only runtime state.
5. Writer stays flavor-shared + server-side default read
   ceilings/deadlines; writer-per-cluster only if ceilings prove
   insufficient.
6. cljs.js bootstrap KEPT as a quarantined experimental artifact for
   the diffusion oracle (real CLJS analyzer is irreplaceable for form
   analysis; separate build, so size is moot). U11 deletes every
   production reference; rg-enforced zero `src/` requires; rot waits
   for the diffusion lane. Diffusion is experimental, not program
   scope.
7. Config: no magic numbers in source. Every operational limit is a
   named aero key → database fact at boot; agent-relevant limits render
   into context; defaults computed from hardware where sensible; caps
   reject with steering error values, sized for high parallel
   throughput.
8. Docs: keep current program PRDs + `docs/seon/architecture/` current;
   archive every other PRD folder. One ledger, one architecture truth.
9. Vocabulary: the dependency's own words, accurate not clever.
   Value-browser "drill" → get-in/path names (at the Stage 1.5
   boundary); "kill drill" is temporary prose only.
10. Implementer preamble (mandatory in every spec): read the
    `reference-code/` source you interface with; report (a) whether a
    better seam exists now that the source is understood and (b) what
    upstream calls each thing — use their terms. Stopping early to
    report is FREE (session resume keeps full context) — specs say so
    explicitly and encourage it; three seam corrections in two days
    (W0.1, W0.4 ×2) prove the loop works.

## Strong / weak / PoC map

**STRONG (keep, build on):** writer multi-class dispatcher (parallel
reads, per-database mutation serialization, reject-not-queue overflow,
durable request-id idempotency + recovery); pod session (256 in-flight,
deadlines, cancel, reconnect); host message contract, cluster-scoping
refusals, receipt-before-run recording with CAS terminal, settle-once
cancel, restore-by-replay; U2 registry + hot-swap; U3 graduation; U5
computed toolkit ledger; generate-code parse→DAG→failure-isolated
execution + CAS-claim fix-up scheduler; `seon.retry` (the `again` port)
as sole LLM retry authority with per-attempt provenance; toolkit
benchmark parity (shell/fs/web/search/plans).

**WEAK (bounded fixes):** oversize error frames escape `settle!`;
acceptor-loop single-catch; global schema snapshot race; regex-classified
interrupts; agent queries carry no caps; print floods; thin fix-up
worker context (Stage 6 unbuilt); K3 not graduated for planning (two
live timeouts, no fallback); B8/B11 intermittents; branch-qualified
eval-cljs hang; two topological orderers over `:seon.ns/require-edges`.

**PoC (redesign settled by probes/rulings, implement):** host writer
channel (one deadline-less locked socket → pool per recipe);
interruption coverage (merge `sci.interrupt/clojure-core`); shared-var
exposure (stamp `:sci/built-in`); cancel ghost-execution
(future-cancel + generation check); npm/maven execution placement
(disposable per-cluster package hosts); U13 install design
(per-cluster roots, staged-then-atomic).

## Work-package series (dependency order)

Model policy: I (orchestrator) write each spec with the hard decisions
resolved; Codex `gpt-5.6-sol` implements — `medium` effort for W0/W3/W6
mechanism work, `low` for mechanical/doc packages. Every spec carries
the ruling-10 grounding preamble, exact owned paths, falsifiable gates,
and shared-tree/path-limited-commit rules.

- **W0 — containment hardening** (critical path; gate: hostile-eval
  battery — each audit vector's attack form evaluated live; cluster
  stays responsive, every other agent's turn completes).
  **W0.1 DONE `82a0c4b4`**: interrupt core/string merged at base,
  wire-safe-value serializes under with-ctx (seam corrected by the
  implementer's audit), graduation nursery tests run under their
  originating context; runaways settle ~103ms on a 100ms deadline with
  same-single-worker-host recovery; full writer gate 287/2193 green;
  host audit found no other direct SCI invocation outside with-ctx.
  **W0.2 DONE `3346e54f`**: host-authored base/portable/capability/
  post-boot wrapper vars stamped `:sci/built-in` via the new
  `register-host-wrappers!` entry; agent corpus vars stay writable
  through the recorded graduation/edit path (ownership-class ruling);
  structural ex-data classification with message fallback; steering
  names the agent's home ns; full gate 297/2232 green incl. graduation
  and read-ceiling suites.
  **W0.5 DONE `7cab9119`** (writer lane): capless reads get server
  defaults, supplied caps clamped, Datahike's own
  `:datahike.resource/*` vocabulary, one defaults map documented for
  the W1 relocation; focused 5/14 + adjacent 20/158 green.
  Remaining sub-units:
  W0.1 merge interrupt core into `build-base!` + force/serialize eval
  results under `sci.ctx-store/with-ctx` (probe caveat) + pool
  fairness/queue bounds; W0.2 stamp base+registry vars `:sci/built-in`
  at build/registration + steering error prose; W0.3 `.cancel` the
  future + claimed-generation check (kills ghost execution); W0.4
  replace `writer-call!` single channel with the proven pool recipe
  (retained members, one in-flight each, close-and-replace on deadline,
  same-request-id write retry, interests pinned, size ≤ writer
  cpu-workers); W0.5 writer-side default read ceilings/deadlines
  (client caps become server defaults); W0.6 frame-write inside try,
  per-accept catch, capped output capture feeding the existing
  `::output` seam; W0.7 the battery test.
- **W1 — operational limits as config facts**: sweep magic numbers
  (connection cap 256, host pool 10, -Xmx512m, frame/slots/deadlines,
  retry policy, render/get-in caps, executor capacities) into aero →
  `:seon.config` facts; hardware-computed defaults; agent-relevant
  limits rendered as a derived context block; rejections name their
  config key. Subsumes source-cleanup's config-through-aero inventory.
- **W2 — LLM resilience**: `:seon.ai/agent-fallback-variants` (ordered,
  resolved frozen at turn open, consulted after retryable exhaustion or
  attempt timeout, per-call only, provenance recorded); timeout
  fallback-eligible; planning=kimi-k3→Muse fallback, workers=DeepSeek;
  the thinking/no-thinking DeepSeek experiment as a scored comparison.
- **W3 — host parity punch list** (U6 + U11 blockers): instrumentation
  over sci vars; host-side run-fence CAS; output capture parity;
  repair sub-loop/preflight parity; authored function invocation on the
  host tier (currently child-only — hard U11 blocker); typed interrupt
  classification (no message regex).
- **W4 — teaching/steering rewrites (U8)**: both system-texts (config
  override + ctx.cljs fallback — remove "NO JVM", async contract →
  sync idiom); skills (`datahike`/`data-oriented-clojure`/`ui-canvas`
  await sections; `clojurescript` skill dies at U11); `my.plan`
  development-teaching two-phrase fix; encode the owner teaching
  contract: specs first → dependency fns → main namespaces, any write
  order, parser orders, last version wins.
- **W5 — U11 cutover deletions**: band-by-band per the deletion
  inventory (12 rewiring points cited); renders migrate into the pod
  (its require closure already compiles there); child lane + cljs.js
  engine bands delete; bootstrap quarantine per ruling 6; `execution`
  protocol band promotes to `.cljc` killing the hand JVM schema
  projection; sequenced AFTER Stage 1.5's retire-while-sampling proof
  and W3. Get-in/path renames land here too (ruling 9).
- **W6 — packages**: U13 redesign (per-cluster roots, shared caches,
  staged-then-atomic install, config-fact policy, wrapper gen into the
  U2 registry with real docs/arglists); disposable per-cluster Bun
  package host + disposable JVM package host (same UDS envelope,
  stateless, respawn-on-crash); package-capabilities P1–P7 Phase 0
  (root pins, agents author `my.*` wrappers from goal tasks —
  [[../package-capabilities/roadmap]]) may start before U13 lands.
- **W7 — generate-code completion**: Stage 6 worker-context bundle
  (full plan visibility: planner reply, accepted prefix, failed eval
  ids, sibling status); the three terminal/retry issues; Stage 8 proof
  drive; long-list batching design deferred until MVP evidence.
- **W8 — doc/PRD hygiene**: archive all non-program PRD folders to
  `docs/prds/archive/` with status flipped (absorb runtime-reliability
  current-state first); delete empty gym-v2; architecture docs gain the
  affirmative sci-host description (today only two denials exist);
  fix root-authority replica drift; vocabulary table rows.
- **W9 — deps/targets cleanup**: pushed sci mirror for a publishable
  coordinate; alias audit; shadow build matrix shrink; the
  `.shadow-cljs-b2/` and `out-b2/` cruft; root package.json reconciled
  with per-cluster package design.
- **W10 — bug ledger chase-down** (continuous fill-in lane): B8/B11
  intermittents, branch-qualified eval-cljs hang, two-orderers
  convergence, remaining audit WEAKs not covered above.

Then **U10** (integration kill/restart tests with live agents) and
**U12** (the graduation gate: 100-agent cluster, real work, host kill +
pod restart, zero fact loss, no operator intervention) close the
program, with source-cleanup stages 2–5 completing in their own PRD at
the named boundaries.

## Design addenda (owner session, 2026-07-21 evening)

- **Package layout**: `data/clusters/<name>/packages/` holding each
  ecosystem's own manifests — `package.json`/`bun.lock`/`node_modules`
  (npm) and `deps.edn` (JVM). Shared downloads are the native caches
  (bun global cache, `~/.m2/repository`, `~/.gitlibs`). JVM adds are
  live via Clojure 1.12 `add-lib` (grounded: add-only via
  `DynamicClassLoader.addURL`; the resolution result is a **basis**);
  change/remove = terminate + rebuild basis + relaunch the stateless
  package host, with registry wrappers queueing calls bounded-with-
  deadline across the swap.
- **Remote values are handles** (playwright-grounded): what cannot
  cross the wire becomes a `:seon.handle/*` fact — guid id, typed
  **channel**, host coordinate + session generation, bounded printed
  summary — bound through the existing `result/{id}` symbol, rendered
  as remote by derivation. Calls act on handles through their channel's
  capability functions, which execute where the value lives; only
  ordinary transit data travels (new `seon/handle` tagged type).
  Lifecycle: create/adopt/dispose + per-channel gc caps that collect
  oldest handles with a steering error. Handles are runtime state:
  host restart invalidates them honestly; facts persist. Teaching
  rules (W4): data crosses; handles for the rest; act via channel
  functions; prefer extracting data over holding handles.
- **Agent-facing `seon.db` is synchronous and Datomic-shaped** on the
  host tier (`q`/`pull`/`entity`/`transact` familiar arities), ambient
  latest-db as the smart default with explicit db-value override. The
  async facade remains pod-internal only.
- **ns merge, canonical CLJC**: an agent ns re-declaration merges
  requires — never silently drops edges the stored namespace or later
  forms need (strengthens the existing augment-ns-source seam). Stored
  source is canonical CLJC, evaluable on either host.
- **Analysis ownership after cutover**: tools.reader +
  sci var metadata (+ real Clojure for graduated code) in
  `seon.host.record`, the ONE corpus graph owner; clj-kondo vendored
  as the deeper-static-analysis option. The CLJS analyzer survives
  only inside the quarantined diffusion oracle.
- **Runtime is lazily materialized from facts**: sci `:load-fn` serves
  namespaces from the corpus on require; registered/graduated fns are
  shared vars (instant fleet-wide); context renders are derived and
  paged, so thousand-turn agents render bounded prompts. Cross-agent
  live require of a session-authored namespace is a W3 gate item.
- **Protocol**: UDS + length-prefixed transit frames, the one codec,
  versioned contracts (database protocol + execution contract) —
  validated against Bun IPC/gRPC/nREPL and kept. Extension mechanism
  is transit tagged types + new ops, not a protocol swap.
- **Agent-steering errors are abridged-first, addressable-full**
  (owner, 2026-07-21): every error value leads with a compact optimized
  steering head — the classified cause, the suggestion, the failing
  frame in the agent's own terms — within a token budget measured by
  `seon.ai.tokens/estimate`. The complete detail (full sci stacktrace,
  ex-data chain, analysis context) is persisted like any large value
  and addressed on demand through the existing result/{id} binding and
  the get-in/path value browser — never inlined. A 20-page stack trace
  is a reference the agent can follow, not a payload the agent must
  scroll past. Applies to the sci fork's error patches (W3), capability
  host errors (W6), and every hostile-gate error shape. Mechanism: NOT
  a new shape — the abridged head is the value's `:seon.render/ai` view
  and the rich web view its `:seon.render/html` view, through the ONE
  existing render-slot dispatch (view-bound recursion in `seon.render`;
  slots registered in `seon.render.schema:29`; `seon.handlers.eval`'s
  render-ai/render-html pair is the first-party idiom; ai renders
  already clip to a token budget, `seon.config:1278`). Handles render
  the same way: ai → compact remote reference, html → interactive
  channel/host card — the transcript's remote annotation falls out of
  the dispatcher.
- **Robustness DNA**: `:seon.config/on-core-error` stays the dev
  fast-loud dial; production layering = errors-as-values (exceptions),
  interrupt merge + watchdog + pool fairness (runaways), disposable
  package hosts (native crashes — core sci host runs zero third-party
  native code). The W0.7 hostile battery is a permanent test surface;
  every new capability ships its hostile gate.

- **One eval pipeline, no parallel guards** (owner, 2026-07-21): every
  piece of agent-authored code — eval forms, canvas renderers, AI
  twins, button handlers, authored invocations — executes through the
  ONE execution dispatch into the agent's execution environment, under
  the same deadline/interrupt/error-value containment. The June-era
  canvas-specific sci guard is history, not a pattern. W5 refinement:
  U11's render-into-pod migration covers CORE rendering only — the pod
  renders data and compiled blocks (registered canvas forms are pure
  data per the render/canvas.cljs platform law); agent-authored
  renderer/handler FUNCTIONS route through the one dispatch to the
  agent's sci context (the W3 authored-invocation port), never
  executing in the pod. A hung renderer yields honest-unavailable
  rendering on the feed; it cannot block SSE or the pod.
- **Key namespaces are a discoverability promise** (owner, 2026-07-21):
  every key fully namespaced and spec'd, and the key's namespace is
  where a reader would expect to find the functions operating on that
  data. `:seon.handle/*` keys therefore require the handle operations
  to live in a `seon.handle` namespace (or the keys take the real
  owner's namespace) — decide at W6 spec time by placing the functions
  first, then naming the keys after their owner. No vanity namespaces.

## Testing policy

- **Behavior, never exact strings**: tests assert facts, transitions,
  envelopes, DOM identity, omission, idempotency, structure. LLM/context
  tests assert presence and shape of rendered blocks, never wording —
  context prose is tuned continuously and must not break tests.
- **Delete obsolete tests in the same refactor** as their mechanism
  (W5 removes ~2,800 test LOC with the child fleet; simplification
  legitimately shrinks the suite).
- **Edge-case and hostile-first**: each unit gate is one happy path
  plus its hostile entries (malformed, oversized, hanging, crashing);
  the W0.7 battery and per-capability hostile gates accumulate.
- **Generative tier**: `malli.generator` + `test.check` derive property
  tests from a function's own schema — also the graduation gate tier
  for agent-authored code.
- **Three surfaces only**: `bin/test-cljs`, `bin/test-writer` +
  `bin/seon test operator`, and `src-inspect-ai/`. No new runners.

## Execution state (2026-07-21, restart anchor)

Accepted and committed: W8a (PRD archival, `607147a6`+`fa81d07c`),
W0.1 (`82a0c4b4`), W0.2 (`3346e54f`), W0.5 (`7cab9119`),
**W2 (`bd357aa5`)** — fallback `:seon.ai/agent-fallback-variant`
keyword, `:muse` variant with minimal thinking, fallback inside
`call-llm!`, full CLJS gate 1458/7035 green. sci forked to
seantempesta/sci at `be4021d`, submodule repointed (`3c11679c`).
Error-quality design accepted:
[[research/error-quality-u6-w3-design-2026-07-21]] with WP-A..WP-D cut.

Also accepted: **W0.3 cancel-ghost `46a304e1`** — Future.cancel(false)
plus a token-identity check before watchdog/eval/receipt/db; a queued
cancel leaves zero receipts and zero writes; writer gate 301/2262 green.

Also accepted: **W4a tier-aware teaching `c238ab9e`** — one pure
tier-aware `render-system-text` (shared body + one tier-selected
platform section), runtime tier acquisition via presence query on
`:seon.execution.host/eval-socket-path` (4th acquisition member),
config override reduced to literal shared body, generate-code contract
(specs→deps→mains, any order, last version wins) in both texts,
platform-neutral development-teaching; `runtime_test.cljs:164` updated
for the added member (`[65536 4096 256 8]`); full gate 1462/7062 green
(shell-test env noise cleared — machine quiet).

IN FLIGHT / UNCOMMITTED:
- **W0.4 writer pool dispatched to Codex** (medium effort) per
  `specs/w0.4-writer-pool.md`; owns `src/seon/host/context.clj`,
  `src/seon/host.clj`, new `test/seon/host_pool_writer_test.clj`.
- **W6 package-host design RETURNED and accepted**
  (`research/w6-package-host-design-2026-07-21.md`): ledger-first
  per-cluster packages/, two disposable `seon.packages.host` platform
  impls over the one UDS envelope, `seon.handle` decided
  functions-first, WP-K→(WP-B∥WP-J)→WP-H→WP-W→WP-S cut; WP-K is
  parallel-safe NOW (no W0.x file overlap). Five owner decisions
  batched with the error-quality design's three — awaiting the owner.
- **Namespace-hierarchy design lane running** (owner-requested
  refactor of all namespaces + host.clj decomposition; writes
  `research/namespace-hierarchy-design-2026-07-21.md`). Sequencing
  law: host-file moves land only after W0.4/WP-A/W0.6; zero effort on
  W5 death-row bands.

If a lane died mid-work: uncommitted changes sit in the shared tree on
its owned paths — review the diff against `specs/<unit>.md`, finish or
re-dispatch.

Work-order specs are durable under `specs/` (one file per unit, the
exact text dispatched). Driving protocol:
`docs/seon/reference/driving-codex-agents.md` + implementer preamble
(ruling 10). Review loop: read summary → diff vs spec → rerun the
focused gate → accept (path-limited commit stands) or resume with
corrections. Next after in-flight lanes: WP-A (sci fork error patch),
W0.6, W0.7 battery, then W3 authored invocation; W6 packages await the
Fable design.

## Sequencing and parallel portfolio

**Earliest unsettled contract:** W0 (containment) — everything fleet-
shaped depends on "an agent cannot take down or lock up the cluster".
**Integrated proof:** the W0.7 hostile battery green on a live cluster.
**Parallel portfolio (safe now):** W1, W2, W4, W8, W9 are independent
of W0 and of each other; W6 Phase 0 (package capabilities) and W7 are
independent; W10 fills idle slots. W3 follows W0; W5 follows W3 + the
Stage 1.5 boundary; U10/U12 last.
**Refills:** after W0 → W3; after W3 → W5; after any doc/config lane →
next W10 item.
