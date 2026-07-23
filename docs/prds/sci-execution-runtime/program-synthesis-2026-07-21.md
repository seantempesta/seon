---
type: prd
status: active
tags: [prd, agent, runtime, architecture]
---

# Program anchor — all-JVM sci runtime (pruned 2026-07-23)

This file was a 2,640-line session diary; it is now the COMPACT
state ledger. The full history lives in git at `b1752173c` and
earlier — consult it only for archaeology, never for current truth.

**THE QUEUE AUTHORITY IS [[unified-plan-2026-07-23]]** — topology,
units U1-U10, sequencing, decisions, graduation gate. Read it
COMPLETELY before dispatching anything.

## Restart protocol

1. Read `unified-plan-2026-07-23.md` completely.
2. Read this file completely (it is short now).
3. Before writing EACH unit's dispatch spec, read that unit's cited
   research sections in full (U1 → jvm-concurrency §guarded-door;
   U2 → loop-cljc-sci-design whole + concurrency §adoption; U3 →
   writer-throughput §5.1+probes; U4 → render-ctx inventory+ledger;
   U5 → jvm-web-sse). You cannot judge a sol lane's return against
   a spec you did not ground yourself.
4. Read `conversion-wiki.md` before dispatching.

## Current state (2026-07-23, restart boundary)

- THE SEAM is landed across 7 families (db 15 · message 2 ·
  lifecycle 5 · fs 12 · shell 8 · web 3 · blob 5 — portable .cljc
  cores, dual-tier gates, live proofs). Loader door + ruling-16
  package namespaces landed. Mixed-tier per-batch routing landed.
- The E2E DEMO ran GREEN: one agent, mixed-tier arc (npm package in
  Bun + sci evals on the JVM host), mid-scenario restart survived,
  honest perf (13-46 reply tok/s; ~50k/turn bounded prompts).
- Census (re-baselined under ruling 19): 223 public vars — 46 done,
  173 capability-pending, 4 platform-pending.
- Issue backlog triaged: 113 open (top-10 ranked in
  research/issue-triage-2026-07-23.md); 407 archived.
- Three designs + four research reports ACCEPTED (all in
  `research/`): p1-capability-seam · loop-cljc-sci ·
  llm-http-io · jvm-concurrency · jvm-web-sse · writer-throughput
  · render-ctx-portability. Deletion audit + issue triage done.
- Restart residue resolved (2026-07-23): the 18 staged issue-archive
  deletions were the triage tail — committed (86579c7f0). The
  uncommitted claim-epoch retrofit in agent/{loop,run,turn}.cljs is
  the post-R22 L0 attempt, SUPERSEDED by ruling 24 (it is the exact
  legacy-loop fence retrofit R24 forbids); diff preserved at
  tmp/orchestrator/l0-retrofit-superseded-by-r24.patch. The compile
  probe then proved the retrofit INCOMPLETE (run-loop! 3-arity vs the
  stale 2-arg call at test/seon/runtime/admission_test.cljs:768), so
  the residue was reverse-applied — tree is HEAD-clean; re-apply the
  patch only if U2 wants it as reference material.
- LANES (first wave, all RUNNING 2026-07-23): U1 guarded door
  (spec tmp/orchestrator/u1-guarded-door-spec.md, thread
  019f8e36-8d84-7b51-b686-5b37991f8779) · U3 writer admission
  (u3-writer-admission-spec.md, thread
  019f8e34-6e63-7e12-8be9-c1fe69c2a98f) · U4 render purity
  (u4-render-purity-spec.md, thread
  019f8e36-92ee-7a80-b0ea-e1902bf38f41). Summaries land at
  tmp/orchestrator/u{1,3,4}-summary.txt; final messages at
  u{1,3,4}-final-message.txt. U2 dispatches when U1 lands; U5 is the
  next refill; U6 held for the owner HTTP talk.
- Owner answers at wind-down: first wave (U1+U3+U4) AUTHORIZED;
  U5 lane builds the web-render operator member itself; demo
  re-runs are BUG-FINDERS, cheap and at orchestrator discretion —
  never benchmarks; the JVM LLM leaf must support BOTH streaming
  and normal sessions (D4 partially settled).

## Rulings index (full text: git b1752173c and design docs)

- R9 op-id: `:seon.capability/op-id` optional public idempotency
  key; internal request-id never public.
- R15 unserializable at a boundary → flat steering error; tier-
  local values by result symbol; handles opt-in (WP-H).
- R16 package wrappers: `seon.packages.js.<pkg>` /
  `seon.packages.jvm.<pkg>` — prefix IS build/locality; native
  manifests; cluster corpus, never src/.
- R17 package-host comms follow the seon.db wire pattern;
  cluster-scoped parallelism.
- R18 mixed-tier arcs: per-batch tier selection computed from
  namespace references (superseded for evals by the all-JVM
  direction; package calls still cross the wire).
- R19 metadata minimalism: only `:malli/schema` required;
  agent-facing marker removed; effect optional (absent ⇒
  :external/non-replayable — proven by regression).
- R20 loop design decisions (a-g) incl. claim attrs on the run
  entity; 20c AMENDED: LLM I/O research done, HTTP leaf = owner
  discussion; 20d render: in-pod move first, port later.
- R21 context DERIVED never stored: datoms carry refs/basis-t/
  counts only; bytes in blobs; render inputs must be db-derived.
- R22 claimant identity self-derived (own pid + start instant).
- R23 reacquire = claimant nil→me + epoch e→inc(e) + beat; pure
  builders never manufacture authority.
- R24 break-and-replace the driver: one claim-native portable
  driver; no fence retrofit into the legacy Bun loop.
- R25 all-JVM core direction (fulfilled by the unified plan).
- R26 topology: writer JVM (tx+feed ONLY, agent code NEVER) ·
  web-render JVM (pure derivation, own process) · claimant JVMs ·
  disposable Bun js-package leaf host · static browser.
- R27 limits are CIRCUIT BREAKERS never governors: every limit an
  aero config fact (schema+docstring+calibration provenance),
  defaults ≥100× measured legitimate P99.9, firing always loud,
  NO numeric limit literals in runtime code.

## Standing mechanics (all learned the hard way)

- Check lane summary files ON DISK; never strand on notifications.
- Specs name every other live lane's grants PROTECTED (tests too)
  and the lane's OWN isolated cluster name — never borrow a
  cluster.
- Pre-rule every known decision in the spec; stop-cycles are the
  cost center. STOPPING EARLY IS FREE and stops are usually right:
  verify claims from source, rule, re-dispatch.
- Path-limited commits always; add untracked owned files
  explicitly; commit accepted work promptly (live proofs need a
  churn-free tree).
- Full gate logs to files always; serial authoritative gates at
  integration are the orchestrator's job; every schema/acquisition
  change gets the full boot-path regression AND a reset-boundary
  live proof on an isolated cluster ending in its own operator
  down.
- codex dispatches via harness background, never shell-&; anchor
  updated same-turn for every ruling/lane state; skeptic posture
  on every return (re-derive from diffs).
- Agent-authored code NEVER executes in the writer process.
- LLM runs find bugs; they are not benchmarks (owner).

## Key documents

- [[unified-plan-2026-07-23]] — the queue (U1-U10, D1-D5).
- `research/` — the seven accepted design/research reports + the
  audits (deletion, issue triage) + dated session research.
- [[conversion-wiki]] — shared scar tissue; every lane reads before
  work and appends before done.
- `docs/seon/issues/` — 113 open, triaged; top-10 in the triage
  report.
- `tmp/orchestrator/` — lane specs, summaries, gate logs.
