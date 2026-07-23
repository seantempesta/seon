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
- MORNING OWNER ITEMS: U6b timing ANSWERED (owner, 2026-07-23 AM:
  "whenever we are ready" = authorized as soon as dependency-ready;
  it is — dispatch after U2's live-drill source-freeze window
  closes; streaming+normal both). STILL OPEN: push the two fork
  commits to seantempesta/datahike; my.blob strict-restart-purity
  variant for identity files; D1 beat-cadence default now that U3's
  numbers exist.
- Morning parallel plan (drill-window-aware): docs-reconciliation
  lane DISPATCHED (docs-only, safe during the freeze); queued behind
  the drill window: U6b JVM LLM leaf, U7 ctx/render port (deps U1+U5
  both landed), U8 JVM fs/shell/web/blob leaves + host bindings,
  triage #6/#8/#10.
- OWNER DIRECTIVES (2026-07-23 AM): fork commits PUSHED (9c356e32 on
  seantempesta/datahike main). PRIORITY: chase every bug touching the
  core spine — function/schema/test persistence must be FLAWLESS
  (all data well-defined in the one live graph database; agents
  react to each other through it). Prefer ELIMINATING ERROR CLASSES
  BY CONSTRUCTION over enumerating tests: move invariants from
  call-site discipline to one choke point each, then one regression
  per class. Two designed-out-class candidates from the U12 drill
  findings: (1) wire-codec TOTALITY — every value either serializes
  or becomes the ruled tier-local steering error, enforced at the
  one codec choke point (the clojure.core$* leak); (2) attr
  registration/usage drift — a computed boot/structural check that
  every transactable attribute is registered by its owner (the
  :seon.agent.run/current-turn rejection), no hand lists.
- FABLE AUDIT LANE dispatched (read-only, freeze-safe): mine
  tests + git history for every .cljs→.cljc move; per-tier coverage
  ledger; persistence-spine deep-dive (schema round-trip, corpus
  replay, receipts CAS); designed-out-classes section;
  anti-recommendations for dying tiers. Report lands at
  research/cljc-test-parity-audit-2026-07-23.md.
- OWNER DIRECTIVE (2026-07-23 AM #2): the JVM test suite gets agents
  ON IT UNTIL RESOLVED; record every problem found and queue fixes.
- JVM-SUITE PROBLEM QUEUE (from the accepted Fable audit,
  research/cljc-test-parity-audit-2026-07-23.md — verified: the
  discovery predicate at script/seon/dev/test_roots.clj:65-73 sees
  only test/seon/db/** + direct *_writer_test.clj):
  1. ~20 orphaned JVM-relevant test files invisible to the full
     writer gate (incl. guard_test.cljc, driver_core_test.cljc,
     value_writer_test.clj since birth) — WHEN: test-integrity lane,
     dispatches at drill-window close (spec
     tmp/orchestrator/test-integrity-spec.md).
  2. Orphan gate (computed: every test file discovered by ≥1
     surface) — same lane.
  3. host_guard_policy_test.clj wrong suffix — same lane (rename).
  4. Wiki's false discovery claim — same lane (correct it).
  5. Newly-seen failures after widening — same lane triages; real
     defects → issues + stop for ruling; dead-legacy → delete with
     justification; fixture gaps → shared mechanism.
  6. Claimant-tier Malli validation OFF (driver/host.clj:32-37
     schema-validation? (constantly false)) — bug-chase lane
     (audit rank #2; wire the bound committed projection).
  7. Codec encode totality (uds.cljc:210-217 bare transit/write) —
     bug-chase lane (designed-out class #1; UNCLEAR which response
     paths validate — probe write-frame! callers first).
  8. Global attr-registration guarantee — Sol investigation RUNNING
     (mechanism A-D analysis); bug-chase implements the ruling.
     OWNER TUNING: attributes are GLOBAL (fully namespaced);
     register!-before-fns is learned agent behavior; the invariant
     is every parsed statement has a registration entry on every
     tier.
  9. Receipt pure-suite dual-tier promotion + attempt-receipt JVM
     persistence regression — fold into U6b spec (audit #5/#6).
- OWNER RULINGS (AM #3): (i) Malli root-enforcement research
  DISPATCHED (Fable, read-only → research/malli-root-enforcement-
  2026-07-23.md): required complete interfaces at the defn admission
  choke point, schema-driven wire representation (ruling-15 result
  symbols BY SCHEMA), one-boundary coercion policy, schema-driven
  generative testing as the edge-case engine. (ii) Testing cadence:
  lanes run LOCALIZED tests for their own boundary; full suites are
  orchestrator integration checkpoints (memory + spec templates
  updated). (iii) Attr-registration Sol investigation running in
  parallel (queue row 8).
- QUEUED after the drill window (source-editing), in order:
  test-integrity lane → persistence bug-chase lane (designed-out
  classes 1-3 + audit regressions) → U6b (with audit #6 folded) →
  U7 ctx/render port → U8 leaves. U2's disposition of the two drill
  findings reviewed skeptically first (neither waves through
  without root cause + regression).
- Docs lane ACCEPTED (ff9196412: 12 docs + R26/R27/R28 ADRs). Its
  mismatch ledger feeds the queue: #4 = schema registrations still
  in .cljs while builders are portable — the ROOT-CAUSE CLASS of
  the drill's current-turn rejection; fix = promote schema
  registration into the owning portable core (one registration,
  both tiers), a third designed-out class. #3 = the concrete R27
  literal sweep worklist (turn/core retry bounds, shell/web
  defaults, driver/host invocation caps). Others are known
  U6b/U7/U9 cutover boundaries.
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
- Schema-alias bridge fix LANDED (0e1954cb6; issue archived): the
  bridge is SEON-owned (src/seon/db/datahike/schema.clj — U3's
  correction of my wrong fork attribution), recursive alias
  dereference with :schema-alias-chain cycle errors; writer-level
  fresh-database install of the aliased attribute proven (26/164
  green). U3 lane fully COMPLETE. U5 live graduation + U4 live
  restart proof both queue into the orchestrator's post-U2
  integration checkpoint (pod build is R28-broken during the spine
  window). U6a adapter-core lane DISPATCHED into the freed slot
  (spec tmp/orchestrator/u6a-adapter-core-spec.md).
- U6a ACCEPTED (ca1ad5f52, verified seon/ai/** + wiki + tests only):
  config resolution, failure vocabulary, request builders, response
  interpreters, usage estimation promoted to .cljc cores; SDK/
  transport/streaming stay CLJS leaves; dual-tier fixture tests
  identical on both runners; focused gates green. Live LLM drive
  deferred to the integration checkpoint. U6b (JVM java.net.http
  leaf) remains the HELD morning item.
- U2 VERIFIED PARTIAL (901eee2d3): one portable claim-native driver
  on BOTH tiers; legacy loop/promise-registry/!attempts authorities
  DELETED; LIVE cross-tier handoff proven once with exact receipts
  (pod render+LLM → JVM eval → pod publish; run q6vd3sazidyb).
  NOT graduated: writer gate red (guard facts missing from fresh
  host fixtures), race/pause/kill/U12 falsifiers unproven. RESUMED
  for completion: fixture seeding → writer green → the falsifiers →
  U12 drill → full CLJS (obsolete legacy tests delete WITH
  justification).
- POST-U2 INTEGRATION CHECKPOINT list (orchestrator, frozen tree):
  full bin/test-cljs + bin/test-writer; U1 live u1guard proof; U4
  live restart byte comparison (context-purity issue closes); U5
  live graduation (up/status/down + identity + gzip transcripts);
  U6a live LLM drive; U2's U12 drill review; then a demo bug-finder
  run at orchestrator discretion.
- U4 ACCEPTED ON RETAINED GATES (2026-07-23 overnight): byte-identity
  gate GREEN (cross-process, empty diff, 938-byte renders) — the
  unit's core falsifier; missing-db :core-bug regression green;
  my.canvas + all R1 namespaces load on the JVM; full CLJS run's
  residual failures attributed to U2's concurrent legacy-loop
  deletion (expected R28 breakage). Residual: the live restart byte
  comparison — DEFERRED into the orchestrator's post-U2 integration
  checkpoint (u4render could not launch while the shared :test build
  is broken by the in-flight spine). context-purity issue stays open
  for exactly that live acceptance; my.canvas issue resolved.
- U3 ACCEPTED (2026-07-23 overnight): fork fix 9c356e32 proven
  (same-basis concurrency → exactly one success + :transaction/
  stale-basis; A′ 32.04x at depth 64; forced-kill recovery green;
  writer integration 16/120 green); pin 3b63b2393; pipelining
  a6f45ee01; basis issue archived. Full writer gate red = U2's
  in-flight host/runtime rebuild (expected; frozen checkpoint
  settles). U3 resumed once more for the schema-alias bridge fork
  fix (dereference keyword forms through the forms map). MORNING
  ITEM: push fork commits (9c356e32 + the bridge fix) to
  seantempesta/datahike.
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

- OWNER RULING (AM #4, standing): kill drills END after the
  in-flight U2 run — no drill re-runs until core hardening lands;
  organic crashes are the bug-finders (identify, chase, record all).
  Concurrency RELAXED: file-disjoint lanes run freely; every spec
  carries the roll-with-restarts posture (stateless design — any
  instance recovers; disruptions are free recovery drills). Never
  stop scheduling fix agents.
- WIDE WAVE DISPATCHED (2026-07-23 AM, all running in parallel with
  U2's final drill + the two research agents): test-integrity lane
  (discovery widening + orphan gate + triage) · U6b JVM LLM leaf
  (streaming+normal, attempt-receipt regression folded) · U7 ctx/
  render port (L9 door split structural at resolution; R4 one
  portable acquisition executor) · U8a JVM fs/shell/web/blob leaves
  + my.* host bindings. Specs: tmp/orchestrator/{test-integrity,
  u6b-jvm-llm-leaf,u7-ctx-render-port,u8-jvm-leaves}-spec.md.
  Bug-chase lane dispatches when the Malli + attr-registration
  research returns ground it. U2's unproven falsifiers (remaining
  kill points) become queue rows pending core hardening, per the
  drill pause.

- OWNER RULINGS R29-R32 (2026-07-23 AM, hands-on triage round):
  R29 attr authority = mechanism A: registrations are committed
  :seon.schema facts, global by construction; tiers ACQUIRE the
  committed projection (never re-run register!); pull-pattern
  admission added (read-side validation); the bootstrap hand list
  dies for the computed population. PLUS: namespace-context
  registration display is DERIVED from the functions present (their
  arg/return schema references), never separately curated.
  R30 every durable agent defn REQUIRES a parseable :malli/schema at
  the admission choke point (scratch namespaces exempt); steering
  teaches register-first.
  R31 contract strictness: :any/:maybe are already banned by
  convention — Sol agent dispatched (owner ask) to audit current
  usage + whether [:fn] validation functions can guard genuinely
  polymorphic slots + the wrapper/massaging (coercion) design for
  platform grounding; ruling completes on its return.
  R32 wire projection = result-symbol references ONLY, with a
  RESULT-SYMBOL LIFECYCLE REGISTRY: track which instance/tier each
  live result lives on (database facts keyed by process identity),
  and WIPE them when that platform resets or the system restarts —
  stale-reference class dies with it. Folds into the C1 encoder
  lane's design.
- MIDDAY STATE (2026-07-23): U2 continuation ACCEPTED as verified
  partial — writer gate green; real two-process race + pause/
  reacquire/stale-rejection PROVEN; the critical U12 segment proven
  (pod workload SIGKILL at :reply-ready → JVM claimant next-epoch
  advance of the same turn, receipts exact). Remaining kill cuts +
  exact one-turn closure PARKED per the drill pause (C9). U2 session
  resumed on a NEW focused unit: portable durable LLM phase
  (attempt CAS + reply publication seam + retry into the core; JVM
  claimant :llm eligibility behind transport presence; vthread
  deadline composition) — unblocks the stopped U6b leaf lane (its
  stop report verified: the phase logic was pod-only).
- TEST-INTEGRITY ACCEPTED (bbecdfc03): JVM discovery widened to one
  computed predicate — writer gate now 559 tests / 3,782 assertions
  GREEN (was 389 blind); three-surface orphan gate green; operator
  309/1,785 green; two Bun-child legacy suites deleted justified;
  wiki corrected. Classes C3/C12 structurally closed. Follow-up for
  U8a review: test/my/blob_test.cljc is the one content-excluded
  JVM file — the blob JVM leaf should make it dual.
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
