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
- R31 FINALIZED (owner, 2026-07-23 midday): :any is the undefined
  hole — hard-banned in agent contracts; genuinely polymorphic slots
  become NAMED predicate schemas ([:fn] with :error/message +
  :gen/schema; agent contracts may REFERENCE core-admitted
  predicates, the writer never executes agent-authored predicates —
  provenance+structure admission, no symbol lists). :maybe is
  DOWNGRADED: judged against the absent-key and error-union laws as
  files are touched, no dedicated sweep. Opaque boundaries (class A)
  stay legitimate, lower priority. Hard walker gate applies to NEW/
  durable agent contracts at three admission points (register!,
  committed projection construction, durable defn admission); the
  221-item backlog burns down as lanes touch files. Coercion policy
  ADOPTED per the census §3 table: validation wrappers everywhere,
  coercion at the few declared boundaries only, explicit named
  converters when conversion is the operation. Census:
  research/schema-strictness-census-2026-07-23.md (576 executable
  occurrences: 124 A / 229 B / 221 C / 2 D).
- R33 (owner + design, 2026-07-23 midday): CONTRACTS ARE CORPUS DATA,
  VALIDATED EVERYWHERE. Agent code is corpus source (database facts),
  and SCI is one interpreter on every tier — so schemas INCLUDING
  [:fn] predicates over agent-written fns compile on any tier by
  resolving through the corpus-loaded SCI env behind the guarded
  door. No .cljs/.cljc or reader conditionals for agent code ever
  (conditionals are for OUR platform leaves only). Contract-predicate
  admission gains ONE computed rule: a predicate's call graph must be
  pure and capability-free (derived from the indexed program-graph
  edges — portability is DERIVED, never declared). Every tier
  validates fully claimant-side; the writer's stance (structural-only
  vs also core predicates) is downgraded from design constraint to a
  defense-in-depth policy knob — owner satisfied that SCI containment
  protects the database; R26's agent-code-never-in-writer stands for
  liveness/blast-radius reasons, not safety. Follow-up queued to the
  schema-admission lane on return: the pure-call-graph admission rule
  + corpus-resident predicate compilation path.
- R34 (design, 2026-07-23): registration PROVENANCE IS DERIVED from
  the asserting transaction's :seon.db/user//process facts (core
  process identities = core-admitted; agent turns = agent-authored;
  unrecognizable/pre-provenance rows default agent = fail-closed),
  carried in the compiled projection as cache. No stored source
  field, no name inference. Schema-admission lane resumed with the
  ruling + grants (acquisition producers, error/instrument for
  spell-check wiring); R33's transitive purity walk may hand off to
  the execution-planning unit (research running) — one graph walker.
- Execution-planning design research DISPATCHED (owner-directed):
  graph-derived placement + schema/capability manifests per form
  (plan→provision→execute; absorbs the mixed-tier router; enforces
  R33 purity; plans derived-never-stored per R21).
- R35 (owner north star, 2026-07-23): TRANSPARENT DISTRIBUTION.
  Agents write plain Clojure with no placement awareness; the derived
  plan (execution-planning research, running) routes every call —
  local SCI for pure corpus logic, local leaves for installed
  capabilities, transparent wire invocations (the one R17 pattern)
  for calls whose plan says elsewhere. Data crosses as
  schema-projected values; tier-local objects cross as R32
  result-symbol HANDLES (tracked per instance, wiped on its restart,
  steering to re-derive — transparency degrades loudly into data,
  never silently into staleness). External effects keep full receipt
  discipline when remoted. Same-tier calls coalesce (the existing
  batch-routing instinct generalized). JIT schema installation =
  R29 acquisition at the run's basis; the ahead-of-time manifest is
  a verification artifact. Implementation unit queues behind the
  planning research's return.
- R35 BUILD MAP (owner-confirmed, 2026-07-23): (1) insertion point =
  the existing parse→dispatch gap (the batch router generalizes to
  call-level plan routing); (2) placement is TOTAL because SCI is
  itself a platform — SCI-local is the floor, never a failure; (3)
  the transparent proxy = the existing wrapper-installer mechanism,
  placement-aware (local impl vs wire-calling stub per var; sync on
  vthreads, awaited on pod); (4) the wire = ONE new invoke
  request/response family on the existing seon.db typed protocol
  (R17; transit-framed, request-id correlated, receipts ride as for
  db effects) — never a second protocol; (5) agent-code coverage IS
  full coverage (core is compiled per tier behind the seam). R35
  implementation spec writes when the execution-planning research
  returns; owner reviews before dispatch.
- AFTERNOON LANDINGS (2026-07-23): U8a ACCEPTED — JVM leaves for
  fs/shell/web/blob + 20 my.* plan/kb/skills bindings via the one
  wrapper registry; census blockers 176→128; live invocation
  deferred to the checkpoint (blocked by the transport lane's
  transient mid-edit state — attributed, no action). SPINE DOOR SWAP
  LANDED (cd7d3ebf8): authored prompt renders route through
  :seon.render/invoke-authored! behind the U1 guard; the option-A
  window is CLOSED; invoke-plans! prompt arm deleted; guarded-door
  issue resolved; byte identity green. Pod render containment is
  door-complete.
- EXECUTION-PLANNING DESIGN ACCEPTED (research/execution-planning-
  design-2026-07-23.md) + owner green-light: plan-execution as the
  sole placement authority (fail-closed dynamic edges; manifests =
  verification artifacts on persistent leaves per the owner's JIT
  instinct; plans derived-never-stored, basis+digest keyed; absorbs
  router scans, regex purity, ctx schema-ref walking, the bootstrap
  hand list). EDGE-BUNDLE LANE DISPATCHED (the earliest unsettled
  contract: call edges, typed attribute edges, uncertainty edges,
  effect/leaf descriptors, artifact export inventory, digest
  integration — spec tmp/orchestrator/edge-bundle-spec.md). Next
  after it: the pure planner (M), then consumers (schema
  verification, R33 admission, driver enforcement, router deletion)
  in parallel. LEAF-RUNTIME ruling (owner Q&A): a leaf artifact =
  the transitive closure of its entry (wire + platform leaves +
  package wrappers + required cores; SCI optional — presence widens
  the tier inventory); the invocable surface is the PUBLISHED
  export inventory, never a naming convention; transparent routing
  covers the whole inventory.
- U6b TRANSPORT LEAF CODE-COMPLETE (200e847e9): java.net.http leaf,
  BOTH batch + SSE-stream with the portable first-form abort, one
  shared HttpClient, timeout/error mapping onto the one :seon.ai
  vocabulary, R27 facts, host installation; 6/23 green; the real
  attempt-CAS regression satisfies audit §0.6; seam issue resolved.
  Paid live claimant proofs DEFERRED to the checkpoint — startup was
  blocked by the schema-admission lane's in-flight schema.cljc edit
  (runtime symbol passed to cljs.core/resolve breaks CLJS compile —
  REVIEW FLAG for that lane's return: its dual-tier file needs a
  CLJS compile gate, not only JVM tests). With live proof, the pod
  becomes genuinely optional for the LLM phase (D4 fulfilled:
  streaming + normal).
- OWNER DIRECTION (2026-07-23 PM): LLM follow-ups queued. (i)
  Provider unification: mine litellm-clj (vendored 16f25fa1e;
  core.async — owner + rulings lean inspiration-not-adoption,
  evaluation running unbiased) into PROVIDER DESCRIPTORS AS DATA
  (endpoint/auth/quirks/capabilities rows; adding a provider = a
  row) + richer normalized response metadata on attempt receipts.
  (ii) UI STREAMING WITHOUT losing multi-form batch: claimant
  consumes the full stream (recovers the usage chunk) while
  separately publishing partials; candidate shapes = coalesced
  latest-wins db writes (~2-3 tx/s per streaming agent vs the ~300
  tx/s pipelined ceiling; attribute-indexed interests keep other
  agents unbothered; datastar latest-wins mailboxes drop stale
  morphs) vs a policy-fenced ephemeral side-channel; design by
  RESUMING the litellm evaluation agent on its return, pointed at
  jvm-web-sse + writer-throughput research + seon.reactive; owner
  reviews the design.
- OWNER Q&A (2026-07-23 PM): per-attribute history-off CONFIRMED —
  :db/noHistory is first-class in the fork (schema.cljc:65,185;
  Datahike uses it on :db/txInstant); our bridge lacks the facet.
  QUEUE: (S) add the :seon.db/no-history? facet to the Seon-owned
  bridge (component-ref facet precedent) — prerequisite for the
  UI-streaming coalesced-write option (partial-text = no-history,
  retracted at terminal, zero durable residue). VERIFY-FIRST row:
  whether :seon.agent.run/last-beat-at can go no-history (recovery
  notices derive from history joins — check what claim archaeology
  reads before flipping).
- U7 COMPLETE (e6a23e37b + bc27f46eb): the whole ctx block family
  portable around ONE acquisition executor; admin isolated in
  ctx/admin.cljs; typeahead the experimental leaf; JVM requires +
  cross-tier byte assertion + authored-infinite-render guard green.
  Rendering is now tier-independent — the R26 web-render and
  claimant tiers can render agent context. U9 (great deletion) is
  DEPENDENCY-READY (U2+U5+U7 all landed) behind the checkpoint;
  edge-bundle handoff #3 (execution.cljs artifact inventory) is
  UNBLOCKED — grant on that lane's return.
- INTEGRATION CHECKPOINT IS DUE when the edge-bundle lane returns
  (last source-editing lane): frozen tree → full bin/test-cljs +
  widened bin/test-writer + operator gate → the deferred live-proof
  list (U1 guard live, U4 restart byte comparison, U5 web
  up/status/down + identity + gzip transcripts, U6a/U6b live LLM
  drives incl. JVM-claimant batch+stream, U8a leaf invocations) →
  one demo bug-finder run. Then U9 + P2 dispatch.
- R36 (owner, 2026-07-23 PM): first-form early completion is KEPT
  as an explicit mode. :seon.ai/reply-evaluation #{:first-form
  :batch} orthogonal to :seon.ai/wire-stream? — all four
  combinations legal, per-agent config facts select ("some LLMs
  just work much better with immediate eval"). The streaming
  design's read-to-EOF applies to :batch; :first-form keeps upstream
  abort + estimated usage. Implementation lane dispatched: (1) the
  :seon.db/no-history? bridge facet, (2) the streaming refactor per
  the design with BOTH modes + the presentation sink + partial-text
  publication, (3) provider descriptor rows (Kimi/Z.AI/OpenRouter/
  DeepSeek-cached; Gemini row-only-if-compat-qualifies).
- R37 (owner, 2026-07-23 PM): GEMINI IS REQUIRED. Path decided by
  the streaming lane's compat-surface qualification probe: qualifies
  → descriptor row; falls short → a genuine third :gemini native
  wire core (JSONL streaming, own transforms) in the core+leaf
  pattern, litellm's native gemini.clj as cited reference for the
  transforms — the owner's requirement is the proven need the
  evaluation asked for. Embeddings VERIFIED intact: embed-writer
  tests green in the widened gate, Proximum index stores exercised,
  the one SEON_EMBED/Vertex path untouched.
- P1 COMPLETE (57761ddb4 + 3169c1967): canonical edge bundle landed
  — alias-resolved call edges both tees, typed attr edges via the new
  pure dependency-projection seam, uncertainty/effect/binding facts,
  sibling graph digest with mutation coverage, no stored closures;
  fixture-proven. Deferred handoff: per-artifact export inventory
  (execution.cljs freed by U7 — grant with/after P2). P2 PLANNER
  DISPATCHED (pure plan-execution + manifests + cache key; consumers
  stay P4/P5; spec tmp/orchestrator/p2-planner-spec.md). CHECKPOINT
  fires when the streaming lane returns (P2 is file-disjoint and may
  span it; its gates are localized).
- P2 stop RULED (2026-07-23 PM): the pure planner takes a FIFTH
  input — :seon.execution/planning-projection, basis-fenced to the
  db-value (edge bundles + terminal connections, graph digest,
  schema projection + fingerprint, inventories); the impure
  acquisition builder (acquire-planning-projection) is the ONLY
  query site, beside the planner — acquire-then-derive, the bound
  committed projection precedent. P1 completeness gap granted to
  P2: function→terminal connection rows so persisted bundles
  reconstruct (digest-equality regression). Streaming lane observed
  landing the no-history facet (1c8a5d2f1) mid-flight.
- Streaming lane stage-1 landed (no-history facet, real temporal
  regression) + stop RULED (2026-07-23 PM): (i) narrow driver seams
  granted (frozen reply-evaluation threaded to reply-program; run
  accounting off the mode fact); (ii) entity-scoped interest ruled
  OUT of this unit — attribute-level wakes + equality suppression
  accepted, design row filed with a measured recompute cost for the
  web-tier-slice-2 unit; (iii) local providers (:diffusiongemma/
  :typeahead) stay on compiled local-worker dispatch as an EXPLICIT
  documented contract (D12) — descriptors are hosted-wire only.
  Lane resumed through stage 4 incl. the R37 Gemini probe.
- P2 COMPLETE (f3ddfb0bb + 2c4d72400): plan-execution landed —
  pure fenced planner (placement/eligible-tiers/manifests/unresolved/
  cache-key over the fifth planning-projection input), the coverage
  helper, the ONE impure acquirer (real-writer test green), and the
  P1 terminal-connection fix with digest-equality reconstruction.
  P3/P4/P5 are dependency-ready behind the checkpoint. Streaming
  lane observed mid-stage-3 (partials rendering in transcripts,
  14619bf56). LAST LANE OUT = streaming; checkpoint on its return.
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
