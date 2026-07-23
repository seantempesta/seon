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
- U4 stop #1 (verified correct): R0 impossible without the turn.cljs
  prompt rewire (symbol at :335-336 → invoke-compiled! :398-405 always
  dispatches into the child; host refuses render). RULED: U4 granted
  the NARROW turn.cljs prompt-render call path only (per deletion
  audit :119-121,:213-216); eval dispatch/fences stay U2's. Resumed
  same session (u4-resume1-out.txt). Wiki gained the
  symbolic-invocation/process-relocation trap (d05f5ecd6).
- OVERNIGHT owner directives: R28 (break CLJS, no dual-maintenance,
  no tests-to-prove-breakage); if the queue drains early — bug
  fixing, cleanup, live LLM drives as bug-finders, robustness; NO
  early victory declarations.
- Overnight orchestrator rulings (2026-07-23, recorded same-turn):
  (a) config-owner sequencing — seon.config.resolve.cljc granted to
  U4 FIRST (its sections commit as U4's first implementation commit),
  then to U1 for the guard-budget sections; two lanes never own it
  simultaneously. (b) U4 authored renders = option A: trusted core
  blocks direct in-pod; authored symbols through the existing generic
  execution invocation (child containment) as a LOUD temporary
  dependency until U7's door split; only the compiled render-prompt!
  child dispatch is deleted. (c) U1 Bun tier re-ruled: production Bun
  is self-host (no sci) and dies at U9 — the single-threaded
  falsifier is fuel's thread-free determinism proven on the JVM
  (deadline lane not armed, deterministic steps-used, no thread deps
  in the .cljc); no speculative Bun seam. (d) U1 fuel cell =
  per-retained-CONTEXT holder reset at each door entry, with a
  second-session context-reuse regression; no dynamic vars in the
  guard hot path.
- U5 dispatched (first slice: web-render operator member + db.host
  listen extension + /data + /data/feed via http-kit +
  datastar-clojure; spec tmp/orchestrator/u5-web-sse-spec.md; R26
  reconciliation: own process, NOT the writer).
- Further overnight rulings: (e) U4 granted the narrow
  script/seon/dev/config.clj selected-manifest resolution region +
  operator config tests (second config caller found). (f) L5 purity
  mechanism = verified file content + LOUD hash-mismatch refusal vs
  the transacted fingerprint; NO my.blob byte publication (second
  content store not ordered) — the strict-restart-purity blob
  variant is a MORNING OWNER ITEM. (g) U5 config queue position is
  third (U4 → U1 → U5 on seon.config.resolve); proceeds non-config
  work now behind accessor seams. (h) U5 transport: dedicated
  interest-bearing db.host session on PUBLIC framing primitives
  approved; uds.cljc stays untouched (private-primitive blocks are a
  separate evidence-based ruling).
- MORNING OWNER ITEMS (batched): U6 JVM LLM leaf timing (D4 —
  streaming+normal both settled; it now also gates the LIVE U12
  drill's non-pod LLM path, though pre-U6 the pod claimant covers
  it); my.blob strict-restart-purity variant for identity files
  (f above); D1 beat-cadence default once U3's numbers land.
- Config entanglement resolved (2026-07-23 overnight): U4's
  render-context sections and U3's mutation-admission sections landed
  interleaved in the one uncommitted config owner; orchestrator
  combined-commit 3d8c9a9a6 released the file (wiki recipe). U1's
  config gate condition (a resolve.cljc commit) is now satisfied; U5
  remains third in the config queue. U6a adapter-core spec drafted
  (tmp/orchestrator/u6a-adapter-core-spec-draft.md) as next refill.
- U1 ACCEPTED (2026-07-23 overnight): guard.cljc door landed
  (8000f5327 + 3 follow-ups) — deterministic fuel, second-session
  regression, native-reduce coverage, output-cap via the marker,
  29.9 ns/check, R27 facts ≥100x measured P99.9 (calibration in
  research/u1-fuel-calibration-2026-07-23.md). Full-suite + live
  checkpoint DEFERRED to the orchestrator's serial integration gate
  (source freeze blocked by U3/U5 uncommitted writer/db files —
  correct call, see issue full-writer-gate-fails-during-runtime-
  lane-integration).
- U5 first slice LANDED (1cdb048c3): fifth web-render operator
  member, db.host interest session (public framing, reader vthread,
  resync), JVM /data + /data/feed (http-kit + datastar SDK, vthread
  per connection, latest-wins mailbox), nine R27 web-render facts;
  focused proofs green incl. real transact→second-morph. Live
  graduation was blocked by a UnixPath/slurp defect in U4's operator
  config edit (b6183ee9d) — orchestrator fixed top-level (2a2844d7e);
  U5 resumed for live up/status/down + gzip parity transcripts. Full
  writer-gate noise attributed to U3's in-flight files (existing
  integration issue; frozen-tree checkpoint pending).
- U3 stop #1 verified + RULED (2026-07-23 overnight): Datahike
  LocalWriter rewinds uncommitted :max-tx → two same-expected-basis
  txs both commit under pipelining (direct dependency probe; issue
  datahike-local-writer-rewinds-uncommitted-basis). FORK FIX GRANTED
  (reference-code/datahike is the first-party fork; protection ≠
  protecting a proven defect); U3 resumed: minimal LocalWriter fix +
  fork regression + submodule pin bump + then commit its complete
  Seon-side work (pipelining 30.96x at depth 64 within-run; kill
  recovery green; wait-for-original duplicate semantics). MORNING
  ITEM: push the fork commit to seantempesta/datahike. Probe C
  shared-file scaling gap (1.55-1.96x vs 4x) recorded as its own
  issue — capacity finding, not a blocker.
- SECOND fork defect queued to U3 on return (found by U5's live
  attempt; issue config-schema-alias-blocks-fresh-cluster-open):
  datahike.schema/malli-form->datahike-attribute cannot store a bare
  registered-alias form (:seon.config.render-context/sha-256 →
  :seon.content-hash/digest); the bridge already receives the forms
  map — dereference recursively (fix the bridge, never inline
  copies). Blocks U5 live graduation + will block U4's live proof;
  rejection site writer.clj:442 is U3-owned.
- U2 THE SPINE DISPATCHED (spec tmp/orchestrator/
  u2-claim-driver-spec.md, effort=high): both tiers run the one
  portable claim-native driver as phase-limited claimants (pod:
  render+LLM; JVM host: eval via U1's door); poll-first JVM noticing;
  attempt receipts + retry-decision relocation folded in; legacy Bun
  loop-driving path deletes; U12 drill is the gate. Preserves U4's
  committed turn.cljs rewire (488f3dd5e).
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
- R28 (owner, 2026-07-23 overnight): breaking the CLJS/Bun side is
  AUTHORIZED and dual-maintenance is explicitly NOT wanted — no
  compat shims, no keeping both paths working. JVM gates
  (bin/test-writer, JVM loads, live JVM proofs) are authoritative;
  never spend cycles running CLJS suites to prove known breakage —
  run them only when a unit claims a still-alive pod surface works.

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
