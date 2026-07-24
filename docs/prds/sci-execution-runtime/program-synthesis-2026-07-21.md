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

## MORNING 2026-07-24 (owner awake — supersedes the overnight block)

Owner directives (08:20): the overnight "reserved for owner" hold on
R45 S3/S4 is LIFTED and fast boot is TOP PRIORITY — the 300–455s boots
wasted the night's wall-clock; attack the biggest slowdowns in order.
Breaking things is authorized (not production; great-refactor posture);
old tests do not need to pass — write better tests from lessons, never
green-wash. Sol remains the implementation fleet; orchestrator audits
overnight fix quality for missed class-level designs.

Lanes live (08:25): r45s3 (pageplan+apply, session 019f9412-d9e0) ∥
u9s0a great-deletion S0a (059146, resumed from wind-down) ∥
lifecycle-caveat re-drive on default (session 019f9410-b7b2, survived
handoff) ∥ Fable audit agent → research/overnight-fix-quality-audit-
2026-07-24.md. Spine after S3: S4 startgate → S5 maintain; refills:
web-UI fixes (after u9s0a returns; serve.cljs owner), containment
HIGH items. §8 preprocessing decisions confirmed at recommendations.

Orchestrator review note (08:25): 170d97862 verified computed-rule
(capability manifest → namespaces, no hand list). 60d09ef38 flagged
PROVISIONAL: `no-dispatch-reply?` bypasses the planner instead of
plan-execution returning a trivial empty plan — candidate class fix,
awaiting the fix-quality audit verdict before re-opening.

OWNER RULINGS (2026-07-24 ~08:40, recorded same-turn):
- R47 pod-LLM removal RATIFIED (e21c85417 stands).
- R48 GRADUATION = PROVEN-PURE COMPILE: native compile ONLY after
  P4/R33 pure-call-graph admission proves door-equivalence; the
  differential test stays as sanity; the invocation deadline stays
  the runaway backstop. Until P4 lands, `graduate!` must refuse
  loudly (no silent nursery downgrade) — no fn may reach host eval
  through the tests-pass gate.
- R49 MALLI [:fn] PREDICATES THROUGH THE DOOR: schema-predicate
  sci compilation/execution uses the one guarded door; delete the
  private unguarded contexts.
- Owner-reserved queue RELEASED: C1 codec totality + db-call
  deadlines (WHEN: S3 returns — shares protocol.cljc), graduation
  drills (WHEN: graduation gate), P6 (WHEN: after P3/P4).
- Owner wants active participation in big design decisions —
  batch design forks to him with recommendations; don't auto-rule.

FIX-QUALITY AUDIT ACCEPTED (research/overnight-fix-quality-audit-
2026-07-24.md): 7 CLASS / 6 INSTANCE / 2 docs; no weakened tests.
Weakness queue (owner package + WHEN):
- W-serve-fault-door: ONE terminal-catch fault door in serve.cljs
  replacing ~10 open-coded 500 catches (WHEN: web-UI lane after
  u9s0a returns; check U9 route-death first).
- W-no-roots-arm: add `:no-roots → :no-dispatch` disposition arm in
  the planner; DELETE `no-dispatch-reply?` (WHEN: after lifecycle
  re-drive returns — same mechanism under live proof now).
- W-attempt-timeout-config: `SEON_LLM_ATTEMPT_TIMEOUT_MS`
  System/getenv at claim time → config fact through manifest apply
  (WHEN: with W-no-roots-arm, same driver/host.clj owner).
- W-claimant-acquisition-contract: no single claimant acquisition
  contract exists (per-fact-family choke points only) — fold into
  S4 startgate's acquisition work, verify there.

LIFECYCLE RE-DRIVE VERDICT (~08:50): NOT CAVEAT-CLEARED, two causes:
(1) infra — u9s0a source edits hit the default watcher mid-drive,
/agents/run 503, pod drained (freeze-rule violation; STANDING: live
proofs on default WAIT for source lanes to land); (2) substantive —
formless final replies close :done with NO transcript message entity
(issue formless-claimant-reply-is-not-delivered-to-the-transcript,
fd2fc35a8). Same class as the no-dispatch bypass → noroots lane
dispatched (planner :no-roots arm + one-delivery-path + timeout
config fact; deletes no-dispatch-reply?). Alive caveat now needs:
noroots + u9s0a landed → rebuild default → ONE re-drive. Lanes live:
r45s3 ∥ u9s0a ∥ containment ∥ noroots.

## OVERNIGHT PROGRAM (owner-ruled, 2026-07-23 night — supersedes the

## older restart protocol below until morning)

Owner rulings for the night: UNLIMITED DeepSeek for bug-finding
drives; browser-testing agents verify the UI (rendering, db-change
responsiveness, SSE datastar updates); continue source cleanup +
test/codebase improvement; deliver the system RUNNING AS WELL AS
POSSIBLE by morning. Reserved for the owner awake (do NOT do
overnight): C1 codec totality + db-call deadline conversions; R45
S3/S4 apply/start operator verbs; graduation drills (C9 pause);
P6 implementation.

PRIORITY 0 (owns the night until green): finish the checkpoint break
chain — currently #6 cljshang (CLJS full-suite hang) + #8 r43fix2
(seon.host.context symbol into SCI env) in flight; seamsweep audit
returns a batch-prediction of remaining breaks → fix in one parallel
wave; then rerun the three suites → live-proof ledger (guard live,
U4 restart byte-identity, U5 web gzip transcripts, U6 LLM
batch+stream, U8a leaf invocations) with baseline perf capture →
demo agent drive.

PRIORITY 1 (after green, file-disjoint lanes in order as slots
free): U9 great deletion (slice S0a agent-view move FIRST) ·
schedfix (R46 semantics + discarded-error/false-:done receipt fix) ·
P3 read-side admission + computed populations · runtime-cost
measurement on the frozen artifact · test-simplification mechanical
batch (fixture migrations + 38 prose pins) · busy-spin
park-until-publication fix · census stragglers as filler.

CONTINUOUS ALL NIGHT (post-checkpoint): (a) LIVE AGENT DRIVES —
DeepSeek unlimited by owner ruling, multi-step plans + db-backed
memory, every anomaly becomes an issue + fix lane, drives repeat
after each wave slice; (b) BROWSER/UI VERIFICATION lanes — root
view + /agent/{id} + /data rendering, a real transact must morph
the page (SSE datastar over gzip verified server-side per the
browser-automation skill; browser bridge may 503 on long SSE),
responsiveness after db changes, console errors; (c) cleanup lanes
from the accepted audits (fragile-tests F-rows, census, wiki-driven
smells) whenever a slot is otherwise idle.

MORNING ITEMS list lives at the bottom of this section — anything
needing owner taste parks there, never blocks. No victory
declarations; anchor updated per landing; kill-and-resume freely on
derivable rulings; stops that need genuine owner taste park in
MORNING ITEMS with a recommendation.

ISSUE-TRIAGE LANE (dispatched): every open issue classified
stale-verify-archive / fold-into-unit / fix-tonight / needs-owner,
with verification evidence required for every archive; index.md
reconciled; fix-tonight list feeds overnight slots; needs-owner
feeds MORNING ITEMS. The issue COUNT is a first-class morning-report
metric.

CODEX OUTAGE (2026-07-23 ~23:00): Sol usage limit exhausted —
resets Jul 28 or on credit purchase (chatgpt.com/codex/settings/
usage). Remaining night work switches to FABLE subagents (owner
authorized, expensive) + orchestrator-run gates. Already-running
codex processes may complete; no new codex turns possible.

CONTAINMENT AUDIT LANDED (dee9b392e — the night's biggest find;
4 issues filed 737eb2668): CRITICAL = graduated corpus source
compiles with host clojure.core/eval (native JVM reach, invisible
to step accounting — the graduation escape); HIGH = Malli builds
its own unguarded SCI contexts for schema code; ctx file reads
bypass the fs grant; R32 result handles unimplemented on JVM
(folds into P6); break-8 data-to-code residue in fixtures. Bun
child natively-JS confirmed (dies at U9 — urgency reinforced).
Mechanical fixes queue post-rerun; design items → MORNING.

MORNING ITEMS (accumulating):
- GRADUATION SEMANTICS (from the containment audit): graduated fns
  currently escape the door via host eval. Recommendation: graduation
  = proven-pure compilation — native compile ONLY after P4/R33
  pure-call-graph admission proves door-equivalence; the door's exit
  exam. Decide (a) stay-interpreted / (b) recommended / (c) ruled
  status quo.
- CODEX CREDITS: Sol is the cheap implementation fleet and is dry
  until Jul 28 — decide whether to purchase credits (the night
  continued on Fable lanes).
- (seed) Bun self-host comparison number post-checkpoint if wanted —
  cheap re-run, decision-moot (path dies at U9).

## Restart protocol (rewritten at the 2026-07-23 PM wind-down)

1. Read this file COMPLETELY — the dated bullets above the rulings
   index are the day's ledger, newest first; rulings R9-R40 are the
   design record. Then `unified-plan-2026-07-23.md` (U-units + the
   P1-P6 derived-execution program).
2. SWEEP FOR UNREVIEWED RETURNS FIRST: ls -t tmp/orchestrator/*-summary.txt
   and *-final*.txt; anything newer than the anchor's last lane bullet
   is UNREVIEWED — skeptic-review it before any new dispatch (this rule
   exists because a return sat 55min unreviewed today).
3. LIVE LANES AT WIND-DOWN (resume with codex exec resume <id>
   --dangerously-bypass-approvals-and-sandbox from the REPO ROOT;
   read the lane's summary + the anchor bullet first):
   - initpage (paged boot initialization + R40 frame recalibration;
     carries TWO orchestrator hunks in protocol.cljc + resolve.cljc —
     verify attribution at its commit): thread 019f8fcb-6444-78d2-ba4b-6f0b2a134d39
   - p1b (artifact inventories + R39 private corpus rows; three
     rulings issued — registry-as-inventory, build grants, plan.cljc
     acquisition): thread 019f8fb6-d582-79b3-88b9-795a6522ad92
4. THEN THE CHECKPOINT (the anchor's POST-U2 list, updated): frozen
   tree → full bin/test-cljs + bin/test-writer + operator → the live
   proof ledger on FRESH-RESET clusters (R38) → demo bug-finder run.
   Serial, orchestrator-owned, logs to files.
5. THEN U9 the great deletion → P3/P4 → web slice 2 → JVM package
   leaf host → P6 invoke family (R35 build map).
6. Standing mechanics unchanged (below) + the day's additions: every
   stop is probably right (100% were today); rulings/grants resume
   the SAME session; entangled shared-file hunks get combined commits
   with attribution; drills stay PAUSED until core hardening (C9).
7. codex driving: docs/seon/reference/driving-codex-agents.md —
   NEVER sandbox mode; always < /dev/null; -o summary files.

## Current state (2026-07-23 PM wind-down — supersedes the morning block)

- HOMESCHEMA FIXED+ARCHIVED (2026-07-23 night): the single
  `:seon.ns/name` symbol-identity declaration moved to the portable
  `seon.ns.source` owner; home/render first referencers now require that
  owner before registration. Cold JVM candidate projection, focused JVM
  regression (1/2), CLJS home (9/18), render-function (6/24), and namespace
  storage (1/6) gates are green; `:execution` and integration-driver cold
  builds complete. Default restart was safely refused by unrelated dirty
  `reference-code/datahike` dependency work; proof log:
  `tmp/orchestrator/homeschema-gate.log`.
- CONVERSION FIRST WAVE COMPLETE AND ACCEPTED: guarded door (U1) ·
  writer pipelining + fork basis fix, pushed (U3) · render purity +
  byte identity (U4) · JVM web/SSE tier (U5) · claim-native portable
  driver both tiers, legacy loop DELETED, live cross-tier handoff +
  race + reacquire + U12-segment proven (U2) · portable LLM phase +
  adapter cores + JVM java.net.http transport, batch+stream (U6a/b) ·
  four JVM capability leaves + my.* bindings (U8a) · schema admission
  R30/R31/R34 at three gates · test discovery widened (559-test
  writer gate + orphan gate) · full ctx family portable (U7) ·
  streaming R36 modes + no-history partials + SEVEN provider
  descriptor rows, Gemini qualified (R37).
- DERIVED-EXECUTION PROGRAM: P1 edge bundle ✓ · P2 plan-execution ✓
  (+ invocation roots, selected tier, inventory producer) · P5
  enforcement ✓ (plan-before-dispatch, router scans deleted) ·
  P1b + R39 IN FLIGHT · P3/P4 queued · P6 invoke family capstone.
- LIVE LANES + resume handles: see the restart protocol above.
- NEXT HARD GATE: the frozen-tree checkpoint (full suites + the
  live-proof ledger on fresh-reset clusters + demo bug-finder),
  then U9 the great deletion.
- Rulings R9-R40; drills PAUSED (C9) until core hardening; the
  bug-class triage (C1-C12) is the class map; ~15 research files
  dated 2026-07-23 in research/.

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
  and my.* host bindings. Specs: tmp/orchestrator/{test-integrity,
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
  and corpus-resident predicate compilation path.
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
- R38 (owner, 2026-07-23): clusters ALWAYS reset to the latest code
  and schema — never any data migration; reset scheduling is at
  orchestrator discretion. Checkpoint practice: every live proof
  runs on a FRESH-RESET cluster at the final artifact; the default
  cluster resets at the checkpoint boundary so it carries today's
  complete schema (claim/phase/partial-text/descriptors/edges)
  fresh-installed.
- STREAMING LANE COMPLETE minus one proof (098b27bcf +
  e88057afd + c9c731ad2 + 924c8ad30): all four R36 combinations
  green; db-backed partials in transcripts; SEVEN provider
  descriptor rows; GEMINI COMPAT SURFACE QUALIFIED (R37 satisfied
  as a row — no native core needed); entity-interest follow-up
  filed with measured 1.094ms p50 recompute. BLOCKER for the live
  morph proof + the checkpoint: standalone writer boot dies
  :malli.core/sci-not-available (protocol.cljc:188) — the
  schema-admission session RESUMED priority-zero to route core
  predicates through requiring-resolve on every load path incl.
  standalone boot, with the fixture-vs-boot regression.
- QUEUE STATE (2026-07-23 late): DISPATCHED — P5 driver enforcement
  and router deletion (spec tmp/orchestrator/p5-enforcement-spec.md)
  and P1b per-artifact export inventories (p1b-inventory-spec.md);
  LIVE — schemagate provenance-recognition fix (checkpoint gate),
  arch-drift Fable audit (docs). POST-CHECKPOINT ORDER: P3 + P4
  (behind schemagate's files) → U9 great deletion → web slice 2
  (routes + /agent pages + entity-scoped interest, measured 1.094ms)
  → JVM package leaf host (research done) → census stragglers +
  triage #10/read-cost rows. AGENTS.md gained the testing-mentality
  law (8a4dcdd1d).
- P5 stop verified (2026-07-23 late): three P2-contract gaps — no
  invocation-form roots (parsed replies unplannable), a REAL
  fail-closed bug (empty roots → vacuous :anywhere), no selected
  tier, no installed-leaf inventory producer. P2 session RESUMED
  with all four (in-memory form analysis via the P1 fns; :no-roots
  fail-closed; selection policy as data; the capability installer's
  one inventory enumerator). P5 resumes after P2's extension lands;
  its wiki entry staged at tmp/orchestrator/p5-wiki-append.md.
  Provenance-fix commit observed (adc25b852) — review on report.
- R39 (owner, 2026-07-23 late): PRIVATE first-party core helpers
  become corpus rows; third-party internals stay out (structural —
  indexing walks first-party only). Differentiator = presence-based
  :seon.fn/private? true (absent = public; no stored false; the
  analyzer already retains privacy — the current gap is only the
  indexing-time filter). Consumer consequences, deliberate: (i)
  agent-facing DEFAULT context/menu/namespace renders OMIT privates
  (derived views query the absence); drill/search/source reads
  REACH them (show-don't-tell — agents can study core internals);
  (ii) the census keeps counting public vars (its computed rule
  keys on publicity); (iii) boot population grows — safe once the
  paging lane lands (ordering: paging FIRST). Implementation queues
  onto the artifact-inventory lane's return (it owns
  client/indexing.clj and must reconcile: first-party privates as
  corpus rows, its build inventory narrowing to third-party
  terminals + export digests — no double coverage).
- R40 (owner + R27, 2026-07-23 late): the 4 MiB transport frame
  bound is a per-MESSAGE wire limit, not database capacity (storage
  unbounded; big user data rides the blob tier by design). A
  legitimate operation hit it ⇒ it was functioning as a governor:
  RECALIBRATE upward (≥100× ordinary P99.9 frames; ~64 MiB class)
  with provenance — folded into the initpage lane's unit. Paging
  stays REGARDLESS: boot payload grows unboundedly with the corpus
  (R39 just grew it), and crash-mid-seed must yield provably
  unseeded-or-complete. The raised limit returns to pure
  runaway-breaker duty.
- R40 APPLIED (2026-07-23 late): frame bound 4→64 MiB (edit sits in
  protocol.cljc ENTANGLED with the live initpage lane's paging
  shapes — rides its commit; verify attribution + rerun the
  transport gate at its return) + input cap 32→128 MiB committed
  clean (frame ≤ input invariant). The one focused-gate failure is
  the lane's mid-flight ensure-database reshaping, not the limits.
- P5 COMPLETE (411627db8): plan enforcement live at reply-ready→
  evaling (verify→provision→execute, release-handoff, pre-receipt
  steering, exact-plan drift = :core-bug); router consumes
  :selected-tier, scans DELETED; result-symbol routing retained;
  dual-tier green. pure-block? = a verified planner-contract stop
  (issue planner-lacks-per-root-purity-projection — P-ladder row).
  Its reset proof exposed the R40 parallel-literal collision:
  queued-request cap was an independent 64 MiB literal → floor
  violated at the header margin. FIXED at the class root: the
  derivation now derives from the ONE frame constant (+header,
  ×2 cap) — rides the initpage lane's resolve.cljc commit (also
  flag: the lane's page-size docstring still says 4 MiB — stale
  against R40, correct at its return). Namespace load proven;
  behavioral proof = the lane's reset acceptance.
- P1b stop RULED (2026-07-23 PM; review was 55min late — caught by
  owner progress question): (i) the wrapper/host-binding registry
  IS the JVM claimant's artifact inventory, explicitly (one
  enumerator; classpath analysis only on proven gap); (ii)
  build/digest owners granted (shadow-cljs.edn, program_artifact/
  artifact.clj, release propagation); (iii) plan.cljc's hardcoded
  inventories-unavailable → real acquisition granted. R39 (private
  corpus rows) FOLDED into the same lane (owns indexing.clj); its
  live proof sequences through the paged initialization.
- Provenance recognition ACCEPTED (adc25b852 + b6ecb55df: precommit
  core validation without fabricated provenance; strict :maybe
  agent-only per R31; former boot failures pass). NEXT DOMINO =
  fresh-boot initialization exceeds the 4 MiB frame (today's row
  growth; error correctly names its R27 key). RULED: PAGE the
  initialization (bounded frames at any corpus size; partial seed
  impossible to mistake for complete), never raise the limit for
  normal growth. initpage lane DISPATCHED — the checkpoint's last
  gate.
- WIND-DOWN FINAL (2026-07-23): BOTH lanes landed, tree CLEAN, all
  clusters down. initpage: paging proven (10x corpus = 1,092 pages,
  largest frame 337 KB; crash-mid-seed + forced-restart proofs
  green) and it REVERSED the R40 64 MiB raise per its spec —
  orchestrator ACCEPTS on the evidence: with paging + blob-tier,
  4 MiB is a true runaway breaker (>10x legit frames) and the
  revert also dissolves P5's queue-floor collision (which the raise
  itself caused). R40 AMENDED: superseded by paging; the durable law
  is 'fix the payload shape, not the ceiling'. P1b+R39 landed
  (inventories both tiers; 1,618 private corpus rows; planner
  places compiled terminals exactly). THE ONE REMAINING CHECKPOINT
  BLOCKER: pod startup fails resolving the core predicate binding
  seon.db.protocol/ordinary-wire-value? on the client tier (issue
  paged-initialization-misses-public-core-predicate-binding) — the
  client-tier sibling of the writer's requiring-resolve fix; FIRST
  ACTION for the next session, then the checkpoint.
- PREDFIX LANE DISPATCHED (2026-07-23 late, new session day): the
  schema-admission codex session (019f8fb5-eb2b-7232-97b4-3785111d6507)
  RESUMED on the one remaining checkpoint blocker — client-tier SCI
  reconstruction misses the registered public core-predicate binding
  seon.db.protocol/ordinary-wire-value? (issue paged-initialization-
  misses-public-core-predicate-binding; the client sibling of its own
  writer requiring-resolve fix). Prompt tmp/orchestrator/
  predfix-prompt.txt; isolated cluster predfix; live reset-boundary
  proof to pod readiness required. THE FROZEN-TREE CHECKPOINT FIRES
  ON ITS ACCEPTED RETURN (full bin/test-cljs + bin/test-writer +
  operator gate, then the live-proof ledger on FRESH-RESET clusters
  per R38, capped by one demo bug-finder run).

- FRAGILE-TEST AUDIT ACCEPTED (2026-07-23 eve, verified from source:
  research/fragile-tests-audit-2026-07-23.md): (1) namespace-doc
  failure = stale program-source sidecar predating U7's warn rename
  (current sidecar correct; residual smell = exact-prose pins +
  digest-not-freshness check); (2) host-registry parity fixture is
  stale against the correct R30 gate (12269fd57 updated five test
  files, missed this sixth) — predicted red at the checkpoint, fix =
  give the fixture a schema; (3) my.plan [1 1]→[0 0] is REAL: U7
  trusted table lacks my.plan entries AND error/agent-authored-sym?
  classifies trust by NAME-PREFIX regex — a hand-rule contradicting
  R34; issue my-toolkit-renderer-misclassified-as-agent-authored
  (owner ruling requested, recommendation = R34 derived provenance).
  Also filed: read-side-attribute-admission-fails-open (the P3
  pull-pattern admission target, verified: extraction exists via
  datahike dependency plans incl. pull; only the gate is missing).
  F-rows queued: fixtures ride paged initialization (F1), fail-open
  validation windows (F2), 56 exact-prose steering pins (F3), six
  transit point tests → C1 generative round-trip (F4).
- OWNER RULING R41 (2026-07-23 eve): wire-codec totality fallback =
  serialize-as-TEXT with LOUD warnings when a value has no ordinary
  projection; error posture is a config dial — DEVELOPMENT panics/
  crashes on codec fallback so it is always found; PRODUCTION never
  crashes (warn + steer). Folds into the C1 codec lane. Owner also
  confirmed: validation-off switches die (triage #6), byte-identity
  as the one render regression, one-seeding-path for fixtures.

- OWNER RULINGS R42/R43 (2026-07-23 eve): R42 DETECT, DON'T GUESS —
  readiness/liveness is EVENT-DRIVEN detection (observe boot progress:
  page receipts, boot-phase log advance, the readiness advertisement),
  never a guessed wall-clock total. The only legal clock is a STALL
  breaker (no observed progress for a bounded interval, config fact,
  loud). Predfix's in-flight total-duration config fact gets re-ruled
  to stall-based detection on its return (its aero/loud plumbing is
  kept; only the condition changes). Evidence answer: the pod did NOT
  need a reset — the 120s kill hit a HEALTHY boot mid paging + SCI
  corpus reconstruction.
  R43 AUTHORSHIP IS PROVENANCE, NEVER A NAME RULE OR STORED ATTRIBUTE —
  agent-vs-core classification (fault attribution, trusted render path,
  SCI wrapping) derives from the corpus row's asserting-transaction
  provenance (R34 cache in the compiled projection): core provenance ⇒
  core; agent-turn provenance ⇒ agent; compiled artifact-inventory
  terminals (third-party) ⇒ core; unknown ⇒ agent fail-closed.
  error/agent-authored-sym?'s prefix regex and the static renderer
  table dissolve into this one computed rule. Owner also confirmed the
  read-side direction: beef up the ONE datahike-parser-level extractor
  (dependency plans) to serve every listen/register/admission consumer.
  agent-bootstrap-attrs dies via the already-landed computed bootstrap
  closure (paged init, writer) + R29 committed-projection acquisition
  (client) — P3 deletes the list.

- OWNER RULINGS (2026-07-23 eve #2): R42 CONFIRMED as progress
  observability + stall breaker + config fact. STANDING SMELL RULE:
  every poll loop and every timeout in the codebase is a candidate
  bad design — hunt them; prefer event-driven detection (queue row
  for a poll/timeout census). GAS PEDAL: schedule lanes aggressively
  whenever file-disjoint; agents/lanes are built resilient to resets/
  restarts and just continue; bail-and-restart is always acceptable
  on parallel conflict. Checkpoint stays a tight freeze, but prep
  (specs, fixture repairs disjoint from predfix) runs NOW so the
  post-checkpoint wave launches instantly.
- FIXFIXTURE LANE DISPATCHED (2026-07-23 eve): repair the stale R30
  parity fixture (host_registry_writer_test.clj:591 schema-less
  parity-double; audit finding #2) + sweep for sibling fixtures the
  R30 commit missed — so the checkpoint doesn't go red on known-stale
  fixtures. File-disjoint from predfix.

- GAS-PEDAL STATE (2026-07-23 eve): THREE lanes live — predfix
  (R42 stall-breaker rework + live proof; killed-and-resumed per the
  owner's kill-and-resume rule when R42 superseded the total-duration
  design; its config commits e2c7187ee + 7e9298243 kept), fixfixture
  (stale R30 fixtures, spec tmp/orchestrator/fixfixture-spec.md),
  poll/timeout census (read-only Fable, report to research/
  poll-timeout-census-2026-07-23.md — the owner's standing smell
  rule made systematic). STAGED for instant post-checkpoint dispatch:
  tmp/orchestrator/r43-trust-provenance-spec.md +
  p3-registration-spec.md ({{LANE_MAP}} placeholders get the live
  lane map at dispatch). Owner goal rewritten (gas on straightaways,
  slow on curves); juicy/surprising issues get surfaced to the owner
  as design hints as they land.

- POLL/TIMEOUT CENSUS ACCEPTED (2026-07-23 eve, top claims verified
  from source: research/poll-timeout-census-2026-07-23.md): 46 sites,
  13 guessed-total clusters, 5 pure literals. Worst: db/host.clj:15-22
  (110s/120s pool/call/interest literals — the exact class that killed
  the paged boot, on EVERY db call), session.cljs:505/:540 (15s/120s
  client-tier inline), process.clj:386/596/673 (watcher/host/writer
  ready-timeouts — predfix's siblings, EXTEND ITS SESSION on return),
  llm-attempt-timeout (stall-not-total candidate), execution/host.cljs
  :32 (240s unconfigurable). Poll→push wins: ProcessHandle.onExit for
  two liveness polls; host recovery poll → writer feed events. NEW
  DEFECT (verified shape): writer run-readiness! busy-spin (issue
  writer-run-readiness-busy-spins-without-runtime; Sol read-only trace
  DISPATCHED). Legitimate sites ledgered (lease heartbeat, watchdog,
  guard facts) — do not re-audit. CONVERSION UNIT queued post-
  checkpoint: db/host + session deadlines → R27/R42 facts (Sol,
  census §worst as spec base).

- FIXFIXTURE ACCEPTED (fe4bfed0c + 4aeab5efd, verified path-limited,
  focused 40/247 green): FIVE schema-less durable-defn fixtures
  repaired across three writer suites (audit predicted one — the
  computed sweep found four more); zero remain; sweep recipe in the
  wiki. It EXPOSED a second F1 instance: the registry suite's fixture
  db lacks the landed :seon.program.edge/* canonical schemas (subset
  hand list gone stale) — FIXSEED LANE DISPATCHED: seed the fixture
  canonical-schema population COMPUTED from the one schema authority
  (protected paths = all predfix-owned files; stop-and-report if the
  fix needs them). Predfix observed mid live-proof; its stall commit
  b8216c27a added boot-progress heartbeats (client/admission/session)
  per the R42 obligation.

- PREDFIX ACCEPTED (2026-07-23 eve; 8 commits 719bb8e1d..c9a9ecaa4,
  live proof verified from predfix-up.log): client-tier core-predicate
  reconstruction FIXED at the one registration authority (projection
  producer/dependency walk, instrumentation consumer, admission owner,
  cold-reconstruction caller — no second registry); R42 IMPLEMENTED —
  pod spec carries NO total-duration timeout, the R27 stall fact
  :seon.config.operator/pod-boot-stall-timeout-ms (300s, log-advance
  resets) replaces it, boot path gained full progress observability
  (97/97 page receipts, acquisition pages, projection/instrumentation
  transitions, heartbeat); fresh reset → POD READY in a MEASURED 271s
  (PERF ROW: fresh boot >2min — SCI reconstruction of 925 instrumented
  fns dominates; not an abort condition per R42); blocker issue closed
  and archived. NEW ISSUE (its find): predfix-web-render-record-survives-
  operator-down (orphaned managed-process record → reset exit 1 after
  the accepted pod proof; operator-owned recovery needed). BUSY-SPIN
  TRACE ACCEPTED into the issue (real failure-path spin via stop-
  ordering; park-until-owner-publication fix shape ruled; post-
  checkpoint Sol unit). CHECKPOINT now waits ONLY on fixseed.

- U9 DELETION PLAN ACCEPTED (2026-07-23 eve, B1/B2 verified from
  source: research/u9-deletion-plan-2026-07-23.md): the "consumers
  already re-pointed" assumption was PARTLY FALSE — B1 the live
  /agent/{id} page still child-renders (datastar.cljs:1093 →
  invoke-compiled!; the JVM host REFUSES render symbols by design) so
  U9 does the in-pod agent-view move itself (slice S0a, U4-prompt
  precedent); B2 scheduled-fns eval (loop.cljs:556 → child) is
  plausibly ALREADY BROKEN (invoke-now! rejects tier-less eval
  batches) — LIVE PROBE at the checkpoint drive + OWNER DECISION
  pending (recommendation: fire = durable turn + wake, JVM claimant
  evals); B3 self-host lookup-value still backs route handlers/serve
  controls — one compiled-table mechanism, SEQUENCED WITH the staged
  R43 spec (shared trusted-table/classifier ownership). Audit drift
  refreshed (files already halved; 3 new probe Shadow builds join the
  cut; P1b inventory sidecars are survivors); wire symbol
  eval-batch! served by JVM while its namespace dies = named risk;
  census cutover = host_surface_writer_test.clj:23-25 with ~17
  pending rows to resolve before the flip (U9's final commit).

- FIXSEED STOP RULED (2026-07-23 eve, correct stop): the computed JVM
  schema population (registered-schemas, 1,450 rows) excludes GENESIS
  forms that are CLJS-captive in client.cljs/index-schemas — the
  schema-registration-portability class in miniature. RULED option
  (a): extract the genesis/boot schema-row producer into the one
  portable schema authority (predfix done ⇒ client.cljs unprotected);
  index-schemas delegates; fixtures consume the SAME producer; no
  genesis seed list anywhere. Lane resumed. OWNER LANES DISPATCHED:
  procmgmt research (orphaned process records impossible-by-
  construction; ProcessHandle.onExit; R22 self-derived identity
  precedent) + b2probe (live scheduled-fns fire proof on its own
  cluster + durable-turn-wake design evaluation with owner question
  list). Boot-time design investigation running (271s issue).
  U9 TIMING (owner question answered): the deletion has NOT run — it
  was dependency-gated on the checkpoint; its plan is now grounded
  (accepted today) and it dispatches IMMEDIATELY after the checkpoint
  goes green, with slice S0a (in-pod agent-view move) first.

- BOOT-TIME DESIGN ACCEPTED (2026-07-23 eve, both root causes
  verified from source: research/boot-time-design-2026-07-23.md):
  the 271s is NOT primarily re-derivation of build artifacts — it is
  (1) a QUADRATIC in schema/build-projection (3,298 per-row contract
  asserts each re-walking the full population; Malli compile itself
  0.37s) and (2) the projection built TWICE (the schemagate
  prevalidation fix discards its build; admission rebuilds
  identically — a fix-introduced regression caught by measurement).
  35s gap = reconcile-config!+ensure-initial-agent!, unlogged (R42
  gap). Sidecar consumption demoted to D3. TARGET ≤90s. BOOTFAST
  lane (D1 de-quadratic + D2 fingerprint-guarded reuse + D4 gap
  instrumentation) dispatches when fixseed frees schema.cljc —
  BEFORE the checkpoint, so every live proof boots faster. Issue
  updated with the corrected breakdown.

- PROCMGMT DESIGN ACCEPTED (2026-07-23 eve, graph/list mismatch
  verified: research/process-management-design-2026-07-23.md): the
  orphaned web-render record = clean-or-force! filtering targets
  through a stale FOUR-MEMBER shutdown list omitting web-render (the
  FOURTH hand list found today) while reporting success. Design:
  records = immutable generation descriptors (R22 analog — identity
  says which instance is meant); liveness DERIVED from
  (pid,start-instant) observation, never stored; one reconciliation
  mechanism over the canonical owned-process graph; requested targets
  ≡ returned absence proofs; onExit = push while alive, never durable
  authority (bin/seon is one-shot bb). PROCFIX PRE-CHECKPOINT SLICE
  LANDED in `fe5e289b9`: computed graph-derived lifecycle targets,
  exact-identity dead-generation reap, loud target/result equality,
  and a real-process kill-9 regression. Focused operator proof: 118
  tests / 545 assertions green
  (`tmp/orchestrator/procfix-gate.log`). Isolated `procfix` proof:
  boot → kill exact web-render workload → ordinary down with all five
  results → reset to full readiness without cleanup → final clean
  down with all records absent
  (`tmp/orchestrator/procfix-live.log`). Full reconciliation and
  onExit push redesign remain queued post-checkpoint.

- OWNER RULING R44 (2026-07-23 eve): (a) PERSISTENT CACHES are
  permitted as :seon.db/no-history? database attributes when the
  cache is key to the system and reasonably bounded (keyed
  derivations only — a cache may SKIP work, never change results;
  derive-don't-store still governs what qualifies). For the BOOT
  projection specifically the orchestrator recommendation is an
  artifact-adjacent file cache (fingerprint-keyed) because the
  projection is needed BEFORE a database session exists (chicken-and-
  egg); db no-history caches fit post-attach derivations. (b) CPU
  PARALLELISM: heavy computation runs on bounded PLATFORM-thread
  parallelism (reducers/fold or an explicit cores-sized executor —
  the existing eval-pool bulkhead pattern), never on virtual threads
  (vthreads = I/O parking concurrency only) and not lazy pmap
  (chunking/laziness artifacts). Boot folds this in: after D1
  de-quadraticizes, the per-row contract validation is embarrassingly
  parallel over the immutable registry → fold across cores; phase
  overlap (indexing while pages stream) is the second axis. Bootfast
  spec carries both.

- OWNER RULING R45 (2026-07-23 eve, boot north star): BOOT COST IS
  PROPORTIONAL TO CHANGE, NOT CORPUS SIZE. (a) RESUME: when the last
  known state still holds (artifact digest + schema fingerprint +
  committed basis unchanged), startup is NEAR-INSTANT — load the
  keyed caches, verify identity, reconnect; re-derive NOTHING that
  identity proves unchanged. (b) BUILD COMPILES STARTUP: the build
  step emits boot-ready artifacts (projection cache, program rows,
  inventories — whatever boot would otherwise derive), digest-bound
  so a stale artifact can never lie; boot's job shrinks to verify +
  load + attach. Fresh reset pays derivation ONCE per artifact;
  every subsequent boot of the same artifact is a resume. This
  promotes D3 (build-side sidecars) from deferred to the program's
  end-state and reframes D2's cache as the resume path. Bootfast
  implements D1/D2/D4 now; the build-compiles-startup unit specs
  after its re-measurement.
- R45 AMENDED (owner, same evening): INDEXING A RELEASE IS AN
  EXPLICIT OPERATION, applied to a cluster deliberately (the config
  explicit-apply philosophy extended to the whole release) — startup
  itself NEVER pays a derivation tax and must come in ≤10 SECONDS.
  The derive-once cost lives in the explicit index/apply step, not
  in any process start. Startup = verify identity + load + attach,
  always. This makes build-compiles-startup the MAINLINE (not a
  follow-up): release indexing produces the seeded/derived artifacts;
  cluster application installs them; every boot is a resume. The
  10s figure is a design target for the startup path, not an R27
  runtime limit.
- R45 DESIGN AGENT DISPATCHED (owner-directed, Fable): the definitive
  pre-processing design → research/preprocessing-design-2026-07-23.md
  — operations/contracts/key algebra, full cache inventory with
  write-through triggers, ≤10s startup sequence, failure modes, and
  SCI-FORK INTERNALS (owner: we own reference-code/sci — study its
  analyzer/env/jit caching for what pre-processing can persist vs
  what must re-materialize; spec a fork extension if a clean
  analysis-cache hook needs one). Implementation units spec from it.
- R45 addendum (owner, same evening): CHEAP CLUSTER SPIN-UP is the
  same problem — pre-processing is RELEASE-SCOPED and shared by every
  cluster (one derivation per artifact, N clusters pay only
  apply+attach); the design must separate release-scoped identity
  (artifact digest × schema fingerprint) from cluster-scoped identity
  (basis) so the expensive part is shared; preprocessed release
  artifacts stored once by reference, per-cluster storage only for
  the store/manifests; the ≤10s target covers a fresh cluster's first
  boot; ephemeral lane clusters stay cheap. Relayed to the running
  design agent mid-flight.
- R45 LAYERING (owner, same evening): each cluster's DATABASE owns
  its divergence — schemas/functions/state diverge per cluster from
  the shared compiled base; the divergence layer's caches are db
  facts in that cluster's database (the R44 no-history permission's
  home); resume identity composes release-scoped base identity ×
  cluster-scoped divergence identity. Relayed to the design agent.
- FIXSEED STOP #2 RULED (2026-07-23 eve): OPTION 1 — fixtures seed
  the complete production boot program through the SAME paged
  initialization (fixtures are clusters in miniature; one seeding
  path; the schema-only bootstrap rejected as a second semantics);
  population computed once per test process, reused immutable; its
  settled extraction (canonical-schema-rows portable + CLJS-captive
  registration moves) commits first path-limited. The script/**
  uncommitted hunks are PROCFIX's live work — verified disjoint.
- R45 DESIGN ACCEPTED (2026-07-23 eve, sci verdict verified:
  research/preprocessing-design-2026-07-23.md is the authority):
  three identities (base = artifact×core-fingerprint; cluster =
  release×manifest digest; divergence = fingerprint×basis, db-
  resident) with resume = base ⊕ divergence; operations pre-process/
  apply/start/maintain — APPLY absorbs paged seed + config reconcile
  plus initial-agent birth (today's 35s gap moves out of startup);
  START = three hash checks, loud cluster-apply remedy; MAINTAIN =
  the mutating tx itself carries the divergence-cache delta (crash
  window unrepresentable). 14-cache inventory; sci analysis output =
  reify-Eval closures, NEVER persistable (verified types.cljc) — no
  fork extension now, precomputed base-load plan instead. Estimates:
  startup ≈5-9s/process; fresh-cluster apply ≈16s→10s; ephemeral
  lane clusters 271s→tens of seconds. Unit ladder S1-S7 (S1 =
  bootfast, awaiting fixseed's schema.cljc release). THREE OWNER
  DECISIONS OPEN (§8): divergence-cache granularity, sci base-load
  fallback trigger, apply ergonomics — recommendations in the doc,
  surfaced to owner.
- R45 §8 DECISIONS SETTLED (owner, 2026-07-23 eve, via structured
  choices): (1) divergence cache = ONE no-history delta entity
  updated in the mutating tx, with a loud R27 size breaker; (2) sci
  base-context builder = ONLY on measured need (ship the precomputed
  load plan; no speculative machinery); (3) apply policy = AUTO-apply
  for ephemeral clusters, EXPLICIT with loud refusal + exact remedy
  for file-backed clusters. The preprocessing design doc is now fully
  ruled; S-ladder implementation unblocked.
- OWNER RULING R47 (2026-07-23 eve): NAMES GROUND IN THE SOURCE
  MATERIAL — no invented nouns ("sidecar"/"coordination" class); the
  system is TRANSACTION PROCESSING (data in from sources → transform
  → store some → emit side effects), close to the metal; before
  naming an integration point, vendor the dependency as a submodule,
  read it, use its exact names (Datahike: tx-data/transaction;
  initialization: pages/rows/phases). AGENTS.md §Vocabulary updated;
  the S2 lane was KILL-RESUMED mid-flight so no 'sidecar' name lands
  in code/schema/wiki — its artifact is pre-parsed transaction data
  for initialization pages, named in the producers'/consumers' own
  vocabulary.
- SETTLECLOSE GREEN (2026-07-23 night): the final two fixture-class
  failures are closed without weakening either production boundary.
  Writer fixtures now keep canonical host schema rows out of SCI
  source, use the compiled initialization-page population, declare
  fixture-only schema supplements explicitly, and allocate generated
  identities through the maintained candidate transaction contract.
  The suite-wide residue sweep covered authored invocation, cancel,
  graduate, hostile battery, instrument, and interrupt fixtures.
  Operator package graph derivation now hashes the manifest-selected
  program-source member while preserving the ownership assertion.
  Integrated proof: focused cancel 4/31/0/0; full writer
  641/4,339/0/0; full operator 318/1,837/0/0; the already-settled
  full CLJS gate remains 1,592/7,866/0/0. The earliest unsettled
  contract returns to the ordered [[unified-plan-2026-07-23]] spine;
  this checkpoint has no occupied fix lane or local refill.
- ★ CHECKPOINT SUITES ALL GREEN (verified from raw logs): CLJS
  1,592/7,866 · writer 641/4,339 · operator 318/1,837 · 0F/0E each
  (settleclose 1a47c1062 + 5cc3b32e1: break-8 fixture class swept
  suite-wide, program-source digest selection fixed). A REAL
  MILESTONE — but NOT graduation: the goal's bar is PROVABLY ALIVE.
- ★ CFGID SOURCE FIXED; LIVE GATE CORRECTLY STOPPED
  (`fdba88aad` + `7b16ca694`, 2026-07-24): the portable durable-attempt
  owner now persists the adapters' actual `:seon.ai/text` response, and one
  portable `seon.config.resolve/cluster-config-lookup-ref` is the config
  singleton acquisition identity for pod, claimant, execution, render, and
  web consumers. Focused JVM proof is 9 tests / 36 assertions / 0F / 0E.
  The requested `cfgid` gate made no paid call: its preflight found the
  artifact manifest not current for the source commit, no target pod, and the
  still-open named-cluster lifecycle defect aliasing default host/web records
  with `:current-spec? false`. Evidence:
  `tmp/orchestrator/cfgid-gate.log`. Earliest unsettled integrated contract is
  unchanged: a rebuilt-cluster DeepSeek attempt must persist a non-empty reply
  blob, acquire both invocation limits, advance through `:evaling`, and write
  terminal eval receipts. The orchestrator's default rebuild and final
  re-drive are the dependency-ready refill; do not archive the config issue
  before those datoms exist.
- ★★★ PROVABLY ALIVE — VERIFIED FROM DATOMS (2026-07-24 ~5:45am,
  canonical DEFAULT cluster, orchestrator-verified in canondrive-
  gate.log, NOT just lane-reported): agent hip-pugs-shop PLANNED
  ([root :my.plan/title ... 536874314]), defined its OWN schema +
  WROTE 3 facts ([8405 :my.canondrive.memory/claim "Apollo 11
  launched..." 536874408]), READ them back a later turn (eval
  emio0g4ozpk0 :done ok 536874436, all 3 rows), and SYNTHESIZED
  correctly (tx 536874453). 11 turns / 11 :success attempts / no
  wedge / both runs released / cluster UP. THE PROVABLY-ALIVE BAR IS
  MET. CAVEAT: the final lifecycle/complete form hit the separate
  known edge (jvm-claimant-rejects-visible-reply-without-exact-
  execution-plan) — released correctly, no wedge; agent WORK alive,
  final-form polish edge remains. NOT GRADUATION (that needs U9 +
  census-zero + U10/U12 drills + the web-UI bugs) — alive ≠
  graduated; the loop continues. lifecycle-complete lane dispatched
  to remove the caveat.
- ★★ MEMORY LAYER PROVEN — CROSS-TURN MEMORY ALIVE (planschema
  0ae0fda9e + f6dd94682 + 3fd9137f6, 2026-07-24 ~5:30am, isolated-
  cluster live proof): my.plan/plan! persisted a full nested plan,
  3 memory schemas committed, MEMORY READ-BACK returned
  'CLAIMANT_MEMORY_ALIVE' across turns, all 7 turns terminal, no
  orphans. The provably-alive bar met on the lane's cluster. Fixes:
  claimant my.* schema lookup via committed projection; /agents/run
  timeout terminalizes the turn; portable seon.db.id/allocate!.
  One SEPARATE edge on the final lifecycle/complete form (existing
  issue jvm-claimant-rejects-visible-reply-without-exact-execution-
  plan) — did NOT wedge or invalidate plan/memory. REBUILDING DEFAULT
  for the CANONICAL cross-turn-memory re-drive.
- ★ DRIVE7 — EVAL WORKS, REACHED THE MEMORY LAYER (2026-07-24 ~5am):
  11/11 DeepSeek attempts :success with NON-EMPTY replies (reply-text
  fix proven; claimant2 lane independently replicated it — 163-byte
  reply), 9/9 EVAL receipts :done :seon.eval/ok? true (config-identity
  fix unblocked eval — agent code runs through the guarded door on the
  JVM claimant). LAST-LAYER blocker: my.plan schema projection EMPTY
  on the claimant ('Accepted my.plan keys: .') → plan!/memory blocked;
  A timeout also orphaned an :evaling turn (nothing-wedges gap). planschema
  lane (my.* projection-acquisition completeness + timeout-terminalizes-
  turn). LADDER FULLY MAPPED: pod contract→crash→routing→claimant
  config→allocation→eval config→EVAL→my.plan projection. Every layer
  below memory now GREEN.
- ★ PLANSCHEMA — MEMORY LAYER LIVE (2026-07-24 ~6:40am):
  `0ae0fda9e` routes claimant schema introspection to the retained committed
  projection, `f6dd94682` terminalizes an observation timeout's active turn in
  the fenced run-close transition, and `3fd9137f6` deletes the claimant's
  duplicate allocation implementation in favor of portable
  `seon.db.id/allocate!`. Isolated JVM claimant
  `99081@2026-07-24T10:21:35.424583Z` persisted nested plan root
  `mft542256r45`, committed three `:my.planschema.memory/*` schemas, wrote the
  fact, and read back `CLAIMANT_MEMORY_ALIVE`. Run `q5ddb6i4pp4z` has six
  `:done`/`:published` turns plus one `:error`/`:published` turn, no running
  turn, no claimant, and no agent current-run. The last completion form
  reproduces the already-open exact-execution-plan issue, so the earliest
  unsettled U2 contract remains that placement/projection boundary. Integrated
  proof is the retained completion form producing a successful eval receipt
  and `:completed` run on the default cross-turn-memory re-drive. The
  dependency-ready parallel portfolio remains the pod republication and named
  cluster reconciliation issues; the next refill is the execution-plan owner.
  Final graduation remains the rebuilt default-cluster re-drive and later U12
  fleet gate. Evidence: `tmp/orchestrator/planschema-gate.log`.
- ★ DRIVE6 — LLM ATTEMPT SUCCEEDS (2026-07-24 ~4am): the JVM claimant
  made a REAL DeepSeek call — attempt :open→HTTP 200→:success at tx
  536873735. Transport works; the pod→JVM chain paid off. Nothing-
  wedges held a 4th time (turn :error, run closed, refs retracted,
  core fault recorded, cluster UP). Blocker: eval config acquisition
  uses WRONG identity ([:seon.config/id :seon.config/singleton] vs the
  real [:seon.config/id 'cluster']). FLAG: the :success attempt
  persisted an EMPTY reply blob (e3b0…b855) — cfgid lane investigates
  whether reply extraction drops JVM-claimant content. cfgid lane
  (config-singleton-identity + reply-extraction). LADDER: …claimant
  config → allocation → eval config identity. Nearly topped.
- ★ NOTHING-WEDGES INVARIANT NOW HOLDS (094e7a7e6, 2026-07-24 ~3:30am):
  claimant timeout inheritance shared pod/JVM; phase errors now persist
  a fault, terminalize the open attempt, close the run :error, and
  RELEASE custody — the lane's own live proof hit a new error at epoch
  2 and FAULTED VISIBLY + released, no wedge (writer 22/137, driver
  12/53, config 4/57 green). It then peeled the next layer itself: a
  JVM claimant remote-identity ALLOCATION error, fixed (62cd2348b +
  356519dd0 + 08942c9f9). Rebuild+re-drive carrying all of it. LADDER
  now: pod contract → pod crash → routing → claimant config/wedge →
  claimant allocation.
- ★ FINAL-DRIVE VERDICT (2026-07-24, ~3am): NOT-ALIVE, but the
  pod-LLM removal WORKED — the JVM claimant (epoch 2) now picks up
  turns (one layer deeper: we're inside the claimant attempt path).
  Turn WEDGED: 711 heartbeats, no attempt receipt, reply=''. TWO new
  blockers: (A) claimant treats the OPTIONAL :seon.ai/agent-attempt-
  timeout-ms as REQUIRED (ordinary agents inherit it) → config error
  before the transport; (B) the NOTHING-WEDGES violation — the claim
  driver DROPS that phase error and RETAINS the lease, heartbeating
  forever instead of faulting+releasing. Resilience PASSED a 3rd time
  (5 processes stable, no drain/desync — framedesync holds).
  claimantpath lane (BOTH, one lane — shared path; config-acquisition
  consistency + phase-error-must-fault-and-release). After: rebuild +
  re-drive. LADDER: pod contract → pod crash → pod-vs-JVM routing →
  claimant config + driver wedge. Each re-drive one layer deeper.
- ★ CLAIMANTPATH SETTLED (2026-07-24): commit `094e7a7e6` closes
  both entangled attempt-path blockers. Pod and JVM now acquire the
  attempt-timeout fallback through one portable resolver, with the
  optional agent row remaining an override. Every direct phase error
  now performs one epoch/phase-fenced terminal transaction: crash
  open attempts, publish turn error, close run error, retract
  claimant and current-run connection, then persist the flat fault.
  Integrated proof: focused writer 22/137/0/0; portable driver
  12/53/0/0; portable config 4/57/0/0. Isolated `claimantpath`
  live proof reached JVM host workload PID 23197 at epoch 2 and
  exposed a new identity-allocation null-Future core error; the run
  closed `:error`, turn published `:error`, fault entity 6534
  persisted, and custody was absent — the nothing-wedges contract
  held. Earliest unsettled contract is now
  [[../../seon/issues/jvm-claimant-id-allocation-future-is-null]];
  its integrated proof is a durable attempt receipt followed by
  provider success/reply or the next visible terminal fault. Shared-tree
  follow-up commits `62cd2348b` + `356519dd0` now route JVM
  database-value identity allocation through `seon.db`, add its focused
  regression, and preserve the Babashka operator boundary. The next
  dependency-ready refill is therefore a source-frozen rebuild and live
  attempt through those commits, not more allocation diagnosis. Final
  graduation remains the orchestrator's rebuilt default-cluster DeepSeek
  redrive plus database-backed memory turn.
- ★ CLAIMANT2 MODEL BOUNDARY ALIVE (2026-07-24): the source-frozen
  `claimant2` reset at `fdba88aad` proves the requested transport gate.
  Pod workload PID `48530` rendered at epoch 1; JVM host workload PID
  `50645` acquired epoch 2. Attempt `sj29e811vgsg` atomically changed
  `:open → :success` with literal DeepSeek HTTP 200, request ID
  `a4e17535-b53d-4f17-a973-82acd6eb89e9`, and a nonempty 163-byte reply
  blob. The historical “long-lived JVM transport state” diagnosis is
  falsified: the eleven failures were pod-owned; credentials and auth
  headers are per request, while only connect timeout is process-client
  construction state. Diagnostics, pod LLM removal, component-pull
  contract, remote allocation, response-key, and config-identity fixes
  are closed. Earliest unsettled contract:
  [[../../seon/issues/jvm-claimant-rejects-visible-reply-without-exact-execution-plan]];
  integrated closure proof = two successful eval receipts + completed
  run from the retained reply. Dependency-ready portfolio:
  [[../../seon/issues/pod-republication-passes-nil-reusable-projection]]
  and [[../../seon/issues/named-cluster-open-does-not-reconcile-jvm-host]].
  Next refill is the derived execution-plan acquisition/enforcement
  owner. Final graduation remains the orchestrator's rebuilt default
  real-work and database-memory redrive. Evidence:
  `tmp/orchestrator/claimant2-gate.log`.
- ★ ROOT CAUSE CORRECTED + FIXED (2026-07-24, ~2:30am): my
  "long-lived claimant transport" diagnosis was WRONG (lane caught
  it) — the failing PID 35849 was the BUN POD, not the JVM claimant
  (35766). The pod retained LLM capability and never handed attempts
  to java.net.http; the pod's LLM path was the broken one. FIX
  (e21c85417 + de1458b24 + dbc283252): removed the pod LLM dispatch
  path so attempts route to the JVM claimant (topology-aligned — pod
  is retiring), restored flat transport diagnostics, fixed the
  config-pull component-selector contract, separated adapter/claimant
  timeouts; ai suite 19/116 green. /agents/run fault-datom persist
  also landed (762424f91). MORNING FLAG: removing pod LLM is an
  interim-topology change (defensible bug fix + topology direction,
  but owner should confirm). New operator blocker filed (named-
  cluster-open-does-not-reconcile-jvm-host) — why the isolated proof
  couldn't run; the DEFAULT re-drive is the proof. Rebuild+FINAL
  re-drive running.
- ★ RE-DRIVE VERDICT (2026-07-24): NOT-ALIVE, but 2 of 3 original
  live blockers DEAD — turns now OPEN and advance (:rendered →
  :attempt-open → :published; render-prompt fix worked) and
  FRAME-RESILIENCE PASSED (a core fault left the pod READY, next
  request succeeded — the framedesync integrated proof). The agent
  reached real DeepSeek calls. REMAINING BLOCKER: 11/11 attempts
  :provider-error on the LONG-LIVED CLAIMANT while the identical
  prompt SUCCEEDS via a fresh JVM leaf (localized to the claimant's
  LLM transport; the error drops its cause). Lanes: claimantllm
  (restore diagnostics → root-cause long-lived-vs-fresh → fix) +
  agentsrun (config pull-pattern output contract + persist the
  dropped /agents/run fault datom). After: rebuild + FINAL re-drive.
- ★ CLAIMANT LLM ATTRIBUTION CORRECTED + SOURCE FIXED
  (2026-07-24): historical claim datoms identify workload PID 35849,
  the Bun pod, for every failing run whose claimant was retained; the
  JVM host was PID 35766. The 11 failures therefore never exercised
  `java.net.http`. The structural cause was the pod retaining
  `:seon.agent.driver.capability/llm` after the JVM leaf landed, so the
  one driver kept its render claim through both attempt phases. The
  pod's superseded capability/dispatch arms are removed; the existing
  eligibility mechanism now hands LLM/eval custody to the JVM and
  publication back to the pod. Diagnostics also retain bounded flat
  message, exception class/message, transport/timeout classification,
  HTTP status/body, and exact success status on the attempt receipt.
  Focused proof is green: JVM HTTP/receipt 11 tests / 63 assertions;
  JVM portable receipt 5/22; CLJS pod capability 2/4; CLJS portable
  receipt 3/16; all 0F/0E.
- ★ CLAIMANTLLM LIVE GATE STOPPED BEFORE PROVIDER CALL
  (2026-07-24): the rebuilt default source artifact is ready, but the
  supported named-cluster path reconciles only the target pod.
  `cluster status claimantllm` was degraded and joined the default
  host record as `current-spec? false`; no target host record existed.
  The isolated pod was stopped cleanly. Earliest unsettled contract:
  [[../../seon/issues/named-cluster-open-does-not-reconcile-jvm-host]].
  Integrated closure proof: target-specific host/pod/web-render ready
  without changing source-cluster generations, then claim history
  pod-render → target-JVM LLM/eval → pod-publish, real DeepSeek HTTP
  200, successful attempt receipt, reply blob, eval receipts, and
  completed run. Dependency-ready portfolio: the operator cluster
  lifecycle owner only; no provider transport change is justified.
  Next refill after that owner lands is the one-attempt
  `claimantllm` runbook. Final graduation gate remains the full
  provably-alive real-work re-drive. Evidence:
  `tmp/orchestrator/claimantllm-gate.log` and the three dated claimant
  LLM research reports.
- ★ RENDER-PROMPT REPLY-POLICY BLOCKER SOURCE-FIXED (`a88e11505`,
  2026-07-24): the public output contract now requires the registered
  `:seon.ai/wire-stream?` and `:seon.ai/reply-evaluation` projections
  returned by the compiled renderer, not retired
  `:seon.config/repl-mode`. The focused real producer-through-consumer
  regression covers all four legal R36 combinations: 17 tests / 51
  assertions / 0 failures / 0 errors
  (`tmp/orchestrator/replmode-gate.log`). The orchestrator owns the
  required frozen-tree rebuild, restart, and fresh live agent re-drive;
  this source lane ran no cluster.
- ★ DATABASE FRAME-DESYNCHRONIZATION BLOCKER SOURCE-FIXED
  (`0b8ad3537`, 2026-07-24): the fault exposed two independently
  scheduled output holders on one UDS socket. A partial unsolicited
  event remained in `::event-state`, then a newly queued response in
  `::outputs` preempted it, splicing the response frame into the event
  before its suffix. This was frame scheduling, not C1 codec totality.
  Opening responses, request responses, and events now share the one
  ordered `::outputs` deque. The real-socket partial-write regression
  and focused JVM 40/217 plus CLJS 23/80 gates are green with zero
  failures/errors (`tmp/orchestrator/framedesync-gate.log`). The
  orchestrator owns the coordinated rebuild, restart, and core-fault
  live re-drive; this source lane ran no cluster.
- ★ RE-DRIVE RUNNING (2026-07-24): both fixes landed (render-prompt
  contract a88e11505; UDS frame-ordering 0b8ad3537 — a real
  scheduling race, NOT C1, so C1 stays owner-reserved). Fresh
  restart→READY (314s, all 5). Decisive DeepSeek drive testing the
  provably-alive bar: agent completes multi-step work + cross-turn
  db memory + core-fault leaves pod READY (framedesync integrated
  proof). Verdict pending.
- ★ LIVE PROOF VERDICT: NOT-YET-ALIVE, ROOT-CAUSED (2026-07-24).
  Web: the JVM /data tier MORPHS correctly on transact (SSE datastar
  ALIVE, marker at basis 536871707) — but 5 web bugs filed: agent/
  root feeds show 'execution child did not become ready' (B1/U9
  child-render path), no gzip, JVM static assets 404, no coalescing
  (21 tx→22 frames), pod drained mid-run. Drive: the agent could NOT
  complete a turn — TWO-LINK CHAIN: (1) render-prompt's :malli/schema
  still requires RETIRED :seon.config/repl-mode (turn.cljs:351, a
  BN-5 reply-policy straggler) → every turn faults on output
  validation; (2) that fault DESYNCS the wire frame ('on.e' read as
  a length) → pod :crash. The completed source lanes root-caused and
  fixed both links: the prompt contract now matches explicit reply
  policy, and one ordered UDS output deque prevents a response from
  preempting a partial event frame. After both:
  rebuild+restart+RE-DRIVE. Graduation still NOT claimed — the goal's
  bar is an agent completing real work.
- ★ LIVE PROOFS RUNNING (2026-07-24, cluster verified ready, root
  / = HTTP 200): livedrive (DeepSeek multi-turn agent with cross-turn
  db memory — the provably-alive test) + webverify (runbook:
  transact→SSE-datastar-morph server-side capture, responsiveness,
  console/status) both driving the live default cluster. Anomalies
  → issues. This is the goal's actual bar; graduation is not claimed
  until an agent completes real work + the UI morphs on db change.
- ★ PRIORITY-ZERO LIVE BLOCKER CLOSED (`c2c5faeff`, 2026-07-24):
  direct replay proved the successful schema/contract rows reconstruct,
  falsifying the forward-reference hypothesis. The omitted third
  `execute-many` member was the whole function-source population and
  exceeded aggregate result weight. Host admission now freezes one
  database value, pages identities, and reads one variable-size form
  row per bounded query; genuine missing references name owner key,
  missing key, and namespace. Focused JVM 23/187 and CLJS 21/148 are
  green. Fresh isolated `hostdrain` reset/start → restart → status
  proved all five processes alive/ready, host not drained, followed by
  clean shutdown (`tmp/orchestrator/hostdrain-gate.log`). The issue is
  archived; the live-proof ledger is unblocked.
- SETTLE RERUN: CLJS GREEN (1,592/7,866/0/0); fresh boot 308s
  (perf row stands — R45 preprocessing is the fix). Writer + operator
  each at ONE fixture-class error: (A) host-cancel-writer-test =
  break-8 sibling (host symbol into SCI); (B) process-test artifact
  absence in an ownership assertion. SETTLECLOSE lane dispatched to
  kill BOTH + sweep the break-8 fixture class suite-wide (the
  containment audit predicted more). These are the last two between
  here and a fully green three-suite checkpoint.
- QUERYCACHE FIXED+ARCHIVED (caf52685 fork + 108d6dfc1, 210/1,347
  green): cache keys retain normalized identities only; 117 open.
  MORNING ITEM added: push fork commit caf52685 to
  seantempesta/datahike. TREE CLEAN — SETTLE RERUN LAUNCHED.
- OWNER TO SLEEP (2026-07-23 ~23:20; final directive: proactive
  audits + solutions, spend Sol freely, maximize the night). THREE
  read-only audits live: containment-surface (break-8 class — the
  complete agent-SCI binding inventory), U9 plan re-verification at
  post-wave HEAD, web-verification runbook prep. NIGHT ORDER after
  querycache lands: settle rerun (3 suites) → live-proof ledger +
  baseline perf → drives + browser verification (runbook) → U9 S0a,
  schedfix, P3, and fix-tonight batch waves → morning report
  (checkpoint verdict, issue count, juicy finds, MORNING ITEMS incl.
  the 31 needs-owner rows from triage).
- FULL CLJS GREEN (e5d5fa780, Fable lane, accepted): 1,592 tests /
  7,866 assertions / 0F / 0E — the checkpoint's first fully green
  CLJS suite. The last fixture now seeds through canonical-schema-
  rows + canonical-database-attributes with the forwarding assertion
  STRENGTHENED to the per-page wire shape. New scar recorded:
  register! permits forward references, so the canonical producers
  outside the full require graph emit dangling references failing
  deep in build-projection (future class candidate). CODEX CREDITS
  RESTORED (owner topped up; probe green) — Sol fleet back. SETTLE
  RERUN waits only on querycache's landing.
- BN-5/10/6 CLOSED (accepted): BN-5 = REAL production defects fixed
  (run-limit + historical consumers now read reply-policy-from-rows)
  · BN-6 = three web limits gained their missing runtime readers
  (fail-loud on absent facts) · BN-10 tests assert current
  descriptor contracts. ALL 11 BN GROUPS CLOSED. FULL CLJS:
  1,592 tests / 7,865 assertions / ONE failure (a fixture without
  bootstrap rows — fixseed session dispatched for the final green).
- HOMESCHEMA FIXED structurally (b8aa50788: the one :seon.ns/name
  declaration moved to its owner, referencers require it, cold-load
  regression; archived — 118 OPEN). Two small flags for the settle:
  a dirty reference-code/datahike state refused a restart (inspect
  at settle), and three archived notes have invalid frontmatter
  blocking issues-index regen (fold into next cleanup slot).
- R43 RENDER CLUSTER CLOSED (a675ab8ed, accepted): all four
  namespaces green (107/608), TEST-ONLY — fixtures model provenance
  with exact artifact exports, fail-closed unknowns, parity
  fingerprints re-derived; production classifier unchanged. The
  16F+1E remainder is dead. Awaiting: BN-5/10/6 closer + two
  refills → THE SETTLE RERUN.
- HOSTERR FIXED+ARCHIVED (d1e6612fe + d68d6a25a, 36/198 green):
  flat writer errors recognized, startup error frame before EOF,
  structured Throwable logging — silent loss dead. 119 OPEN.
  Refills: homeschema (registration-before-reference) + querycache
  (foreign database-value retention) dispatched, disjoint from both
  closers.
- BN-1/9 LANDED (546718cb4 + 4 more, accepted): claim-epoch
  threaded through every current-run acquisition/mutation
  (production CAS fence now satisfiable from web/client paths);
  fenced test shapes; PLUS real finds — retry.cljc Promise-as-value
  defect (13 reds), .cljc ns-doc reader-conditional parser fix,
  internal-require boundary rename. CLJS 64F+2E → 30F+1E with EXACT
  attribution. FINAL CLOSERS DISPATCHED: cljsreds session → BN-5 +
  BN-10 + BN-6 (files freed by BN-1; expected remainder after =
  only the render cluster); r43 session → the 16F+1E provenance-
  render consumers (block/handlers/value/portability). When both
  land: the settle rerun (all three suites) and the live-proof
  ledger.
- ENSUREPATH FIXED+ARCHIVED (d0a73db8e +2, accepted; 37/224 green):
  bare file-backed ensures return not-found — never create stores/
  directories; known names reject mismatched paths; creation
  authority stays explicit (init/writer startup). Issue count 120.
- BN-2/3/4 FIXED (fa3cb6c37, accepted): release package preserves
  the checkout build layout with all four members digest-verified
  (application SHA e1b9490…); standalone bin/test-writer resolves
  the ONE current manifest via SEON_WRITER_ARTIFACT_MANIFEST and
  fails loud with the exact remedy on a clean slate (no more silent
  stale-state consumption); acme.pod load = registration only, the
  12s timer runs only from -main (measured: ACME derivation 23.06s
  vs client 21.67s — the deterministic stall gone). BN LEDGER:
  2,3,4,7,8,9,11 fixed = 7 of 11; BN-1/9 live (critical path);
  BN-5/10 + BN-6 queued on it. Fix-tonight commits observed
  (d0a73db8e ensure refusal, 1115c3f35 owner-close sync) — review
  at those lanes' returns.
- BN-7 FIXED (56ed96dd9, accepted — the computed rule was SOUND;
  six incomplete entity DECLARATIONS completed, no list restored;
  parity regression strengthened). Watch item resolved: kb/ctx/
  testrun edits = BN-7's declarations; retry commits (294f3dd02,
  1f14ef6e2) = the BN-1 lane's red-cluster fixes, verify at its
  return. BN LEDGER: 7,8,9,11 fixed; BN-1/9 (critical) + BN-2/3/4
  live; BN-5/10, BN-6 queued on BN-1.
- ISSUE TRIAGE ACCEPTED + COMMITTED (7df2bd115): honest count
  126→121 open (5 source-verified archives; the old '113' was
  wrong); 42 blockers / 71 friction / 8 cleanup; 54 fold-into-unit
  stamped; 36 fix-tonight RANKED (top-10 in the report); 31
  needs-owner → MORNING ITEMS; index regenerated, check passing.
  The retry/kb/testrun src edits are the BN-1 lane's red-cluster
  mandate (triage confirmed docs-only) — verify at its return.
  FIX-TONIGHT DISPATCH #1: ensurepath (ensure-database wrong-path
  creation) + hosterr (host-session errors vanish silently) — both
  disjoint from the wave; next refills from the ranked list as
  slots free.
- HOSTERR FIXED+ARCHIVED (`d1e6612fe`): host startup now recognizes the
  database leaf's canonical flat error value and sends the existing bounded
  startup error frame before EOF; non-timeout session Throwables retain their
  core-fault datom and emit one structured error log event. The real fake
  writer is stopped and restarted between sessions in the recurring
  conformance proof; 36 tests / 198 assertions green
  (`tmp/orchestrator/hosterr-gate.log`). The adjacent projection fake now
  recognizes function-source rows, restoring the suite's current three-member
  acquisition contract.
- FIX-TONIGHT ensurepath LANDED (`d0a73db8e`): external bare file
  ensure is open-existing only; absent stores return not-found before
  filesystem creation, and known logical routes reject path changes.
  Focused writer initialization 14/83 + registry lifecycle 23/141
  green; blocker issue archived with recurring proof.
- BN-5/10 STOPPED CORRECTLY at the overlap gate (its consumers =
  BN-1's live files run.cljs/serve.cljs); requeues on BN-1's commit;
  BN-1 (claim-epoch) is the wave's critical path. WATCH ITEM: tree
  shows edits in src/seon/retry.cljc + my/kb + agent/testrun +
  agent/ctx and the portable-retry issue archived — NO dispatched
  lane owns those paths; verify at the issue-triage lane's return
  whether it exceeded its docs-only write boundary (its spec said
  fix-tonight candidates go to the orchestrator, not self-fix).
- BN LEDGER (running): #8 benchmark ✓ (ef0680725) · #9 claimant
  arity + prose pins ✓ (0b3976ad0) · #11 transcript direct-call ✓
  (same commit; PRODUCTION IMPACT DEFINITIVELY NO — fixture-only) ·
  BN-5/10 lane dispatched (driver files freed by #9) · BN-1/9,
  BN-2/3/4, BN-7 in flight · BN-6 queued behind BN-1's serve.cljs.
  Full-suite rerun deferred until the wave settles (one honest
  rerun, not eight partial ones).
- SEAM SWEEP ACCEPTED + BATCH WAVE DISPATCHED (research/
  integration-seam-sweep-2026-07-23.md: 11 breaks-now + 2 suspicious
  and 2 breaks-on-growth beyond the chain's 7; four consumer groups
  explain 18 of the unmasked reds). WAVE (all session-resumes with
  owned files + protected maps): cljsreds session → BN-1 claim-epoch
  production callers (4 sites silently failing the CAS fence — the
  sweep's most serious find) + BN-9 fenced test shapes + unowned red
  clusters; S2 session → BN-2 release inventory paths + BN-3
  standalone test-writer artifact dependence + BN-4 ACME 12s timer
  in derivation; fixseed session → BN-7 computed population drops
  persisted attributes (complete the computed rule, never a list;
  strengthen the parity regression that should have caught it); r43
  session → BN-11 transcript direct-call classification + BN-8
  benchmark fuel residue. QUEUED behind live lanes: BN-5+BN-10
  (reply-policy/provider consumers — after break9 frees driver
  files), BN-6 (web limits readers — after BN-1 frees serve.cljs).
  Breaks-on-growth + suspicious rows → issue notes via triage lane.
- BREAK #8 FIXED (32e7b75eb, accepted — the containment-correct
  answer): the fixture spliced canonical tx data into an evaluated
  SCI form (symbol VALUES analyzed as code); fix keeps host schema
  data outside agent SCI, no binding widened; bonus real fix =
  acquisition max-results counted tuple CELLS not rows. BREAK #9
  surfaced (claimant invocation 7-arg arity) + a stale prose pin
  (writer error got MORE specific) — break9 lane dispatched; r43's
  wiki scar staged (wiki lane-owned). CHAIN LEDGER: 9 breaks found,
  8 fixed, #9 + 64F/2E cljs reds in flight.
- BREAK #6 FIXED (1b30bb578, accepted): the CLJS hang = a
  single-arity stub of multi-arity publish-committed! throwing on
  the unavailable rail while the test awaited only success — done
  never fired; stub now preserves both arities, test settles on all
  outcomes, NO timeout raise. UNMASKED: the full gate now completes
  (153 ns, 1,590 tests, 7,853 assertions, 139s) revealing 64F+2E
  the hang hid — CLJSREDS triage lane dispatched (cluster →
  attribute class → fix ruled clusters → full rerun with honest
  counts; r43fix2 files protected).
- BREAKS #7 FIXED + STATUSFIX ACCEPTED (ff30d1162: analyzer
  confined to its leaf, pure classification extracted to
  seon.dev.program-inventory, test moved to operator discovery, NO
  clojurescript on the writer — the design-correct fix; 8953b45e3:
  status shares select-manifest with up, 44/122 green + live exit
  0). BREAK #8 surfaced by the writer gate: sci analysis 'Unable to
  resolve symbol: seon.host.context' in the authored-invocation
  writer test — the r43 resolution seam leaking a host-namespace
  symbol into a SCI env; r43 session resumed (containment rule:
  never bind host namespaces into agent SCI — fix the resolution
  mechanism). Its acceptance = the FULL writer gate with honest
  counts.
- PARALLEL FIX POSTURE (owner, 2026-07-23 late): FOUR lanes on the
  break chain — cljshang (#6) + writercp (#7) fixing; SEAMSWEEP
  audit (read-only) batch-predicting remaining breaks from the day's
  producer/consumer contract changes (signature/config/artifact-
  membership/limit-growth sweep) so discovery stops being serial;
  statusfix on the two-config-paths smell. Discipline: no duplicate
  coverage, file-disjoint fixes, audits read-only.
- CHECKPOINT SUITE RESULTS (first full sweep since morning):
  OPERATOR GREEN 316/1,831. CLJS = TIMEOUT not failures (compile
  13s/56 ns, then javascript-exit 124 at the 30min cap, complete?
  false, zero recorded reds) — a HANG; cljshang lane bisecting from
  the log tail. WRITER = classpath break at load —
  seon.client.indexing (CLJS analyzer) entered writer scope
  (FileNotFoundException cljs/env); writercp lane tracing the
  require chain (writer consuming the analyzer would be a design
  regression vs the S2 artifact model — fix at the owner, classpath
  add is last resort). Default cluster reset itself was GREEN
  (314s full readiness). Breaks #6/#7 of the serial chain.
- BREAK #5 FIXED — CHAIN EXHAUSTED (7a1c5de68 + 647613f97,
  accepted): acquisition paged by canonical row (one identity = the
  exact expansion unit since result-weight charges string contents);
  breaker untouched; synthetic over-cap regression green; LIVE PROOF
  = fresh acqpage reset to FULL STACK READINESS (watcher/writer/
  host/pod/web-render, exit 0, 294s incl. build; 927 schema + 2,948
  fn identities paged; no breaker fired). Five serial breaks found
  and fixed by the gate: test arity → client arity → devtools
  socket → inventory membership → acquisition weight. CHECKPOINT
  RESUMES: default reset + suites.
- BREAK #4 FIXED (f8358abec, accepted: both inventories immutable-
  runtime members with exact digests, ENOENT boundary passed,
  128/722 green). BREAK #5 (the chain reaches the read side):
  committed-program acquisition result weight 63,344 > 60,000 —
  corpus growth crossing the R27 read breaker, the exact mirror of
  the morning's write-side frame. RULED by the R40-amended law: PAGE
  the acquisition reads at one basis (page weight <= cap by
  construction, derived not a second knob); the breaker stays.
  Schema-admission session resumed; acceptance = FULL POD READINESS
  on cluster acqpage.
- BREAK #3 FIXED (73d41179d, accepted): derivation runs with
  Shadow's disabled-devtools config + devtools client entry removed;
  live proof = watcher first flush publishes the v11 manifest,
  digests match, subprocess exits; issue archived. BREAK #4 (its
  proof found the next): pod boot ENOENTs on out/execution/
  program-inventory.edn — r43 made boot READ the execution inventory
  but the immutable-runtime membership ships only client artifacts.
  S2 resumed: membership fix through the same machinery + full
  reconciliation proof to POD readiness. The checkpoint's serial
  break-chain: test arity → client arity → devtools socket →
  inventory membership; each stage failed loudly and correctly.
- BREAK #2 FIXED + BREAK #3 ISOLATED (clientfix 692bd252c +
  48c0c0b12, accepted): shell caller fixed via proper config
  acquisition; BOTH canonical builds now zero first-party arity/
  undeclared warnings; shell suite 21/81 green. THE REAL FLUSH
  BLOCKER isolated: the S2 derivation subprocess retains a Shadow
  devtools socket (compiled client autoconnects under the watcher)
  → never exits → killed at bound → v10 manifest → watcher timeout;
  derivation logic itself CORRECT (one-shot published rows,
  SHA 524e2e00). S2 session RESUMED: disable devtools autoconnect
  via Shadow's own mechanism (R47), prove first flush publishes v11
  on an isolated cluster. Checkpoint resumes on its landing.
- CHECKPOINT BREAK #2 (same class, client build): shell
  internal.cljs:120 exec still passes 2 args to core/run-request
  (3 since the shell config-fact conversion) — R28-era breakage on a
  STILL-ALIVE pod surface, exposed because the program-row derivation
  executes the client build. CLIENTFIX lane dispatched: fix the
  caller via proper config acquisition + compile BOTH canonical
  builds (client AND test — r43fix proved only :test) to zero
  first-party arity/undeclared warnings + prove the v11 manifest
  publishes. Lesson sharpened: the class gate is BOTH builds.
- CLIENTFIX RESULT (`692bd252c`): the pod shell requests now acquire the operation's
  config singleton through the existing context-only request injection;
  the deleted default-var residue is gone. Both canonical builds compile
  with zero first-party arity/undeclared warnings, and the focused shell
  suite passes 21 tests / 81 assertions. The remaining warnings are
  dependency-owned SCI/Datahike inference warnings plus the known
  `cljs/analyzer/api.cljc` resource warnings. V11 PUBLICATION REMAINS
  BLOCKED by a distinct artifact-hook lifetime defect: the watch-derived
  temporary program-row Bun process retains its Shadow devtools socket,
  so the managed watcher times out before its first client flush. Recorded
  as `docs/seon/issues/program-row-derivation-retains-shadow-devtools-socket.md`;
  clientfix did not expand into that separate mechanism.
- BREAK #1 FIXED (52124e45e, accepted): exactly one stale call site
  (exhaustive-search proven); fixtures made honest under the
  provenance classifier (incl. an adjacent stale corpus-implies-core
  expectation); FULL canonical test build compiles clean (467
  files); execution suite 41/189 green. CHECKPOINT RE-ENTERED at
  step 1 (reset + fresh boot, timed).
- CHECKPOINT BREAK #1 (found by the gate, as designed): the fresh
  build fails — execution_test.cljs:1073 still calls single-arg
  fault-for (R43 focused gates never compiled the full canonical
  test build) → client compile error → program-row derivation fails
  LOUD → no v11 manifest → watcher flush timeout. ONE causal chain,
  every stage failing correctly. R43 session resumed: fix every
  stale call site with honest projection fixtures + prove the class
  by compiling the FULL canonical test build to zero errors. Wiki
  lesson queued: lane gates must include the canonical-build compile
  when a public signature changes.
- R43 ACCEPTED (69d53311b, verified: prefix regex GONE, classifier
  is provenance-driven with the ruled precedence; the no-op-assertion
  leak regression proven against real Datahike; my.plan dispatch
  regression GREEN — the morning's broken render is fixed; all
  focused gates green incl. dual-tier + reuse-path parity). Static
  renderer map is resolution-only; trust flows through the one seam.
  TREE FROZEN (2026-07-23 eve): every lane landed — THE CHECKPOINT
  RUNS NOW per tmp/orchestrator/checkpoint-runbook-2026-07-23.md
  (default reset + fresh build first = v11 artifacts for the fixture
  gate + the real bootfast boot measurement, then full suites, then
  the live-proof ledger + baseline perf capture + demo drive).
- INTERP-BENCH ACCEPTED (ddd3880e9): guarded sci vs compiled JVM =
  glue 1.28x, Malli-heavy 1.34x, 10k transform 3.48x, hot loop
  12.20x; guard = 7-15 ns/safepoint (interpretation dominates, not
  the check). CORPUS CLASSIFICATION (computed): 69.8% glue / 22.6%
  callback traversal / 7.6% explicit loop-recursion. VERDICT: the
  space-for-computation trade is favorable — the common 70% pays
  ~28% on code that mostly waits on db/LLM; the expensive 7.6% is
  exactly P6's compiled-routing target. Bun comparison honestly
  absent (stale v10 manifest blocks pod boot pre-checkpoint).
- GUARDBATCH RESULT: DO NOT LAND (correct measure-first refusal;
  guard restored byte-identical, 11/35 gates green): current check
  = ~13.6 ns/step / ~9.7 ns isolated on this machine (better than
  the 29.9 calibration baseline — conditions differ); N=1000 within
  variance, N=32/100 slower. ROOT INSIGHT: sci invokes the guard as
  a closure call per safepoint, so ANY counter is a mutable cell
  access — batching cannot remove the dominant op; the orchestrator's
  register-countdown theory was WRONG. Optimal setting = the current
  unbatched check. Future lever if ever needed = the hook's
  invocation frequency inside the sci fork (measured-need-only).
  Raw data tmp/orchestrator/guardbatch-*.edn.
- GUARDBATCH + R27 (owner): the sync interval N is an aero config
  fact (:seon.config.guard/interpreter-step-sync-interval — schema,
  docstring stating the trade with measured calibration provenance,
  documented beside the sibling guard facts/ADR 010), never a
  literal; lane kill-resumed with the requirement.
- GUARDBATCH DISPATCHED (owner pre-authorized 'measure and if
  tradeoff-free then do it'): measure current check ns/step, prototype
  local-countdown/sync-every-N, land ONLY on material measured win
  with all seven tradeoff-free conditions green (determinism, bounded
  overshoot, single-threaded halting falsifier, guard suites,
  deadline/output-cap untouched, one mechanism, context-reuse
  regression); calibration addendum with provenance if landed.
- GUARD BATCH-CHECK CANDIDATE (owner insight, 2026-07-23 eve):
  sync the interpreter-step counter to the shared cell every N
  steps (local countdown in the hot path) — cuts the ~30ns/step
  (cell barrier) ~N x with bounded overshoot <=N steps (0.0001% of
  the 100M budget), determinism + single-threaded halting preserved.
  CONDITIONED on interp-bench's guard-on/off measurement: material
  hot-loop delta => implement inside the one guard closure +
  recalibrate; noise => skip. Queue row, not dispatched.
- FIXSEED CONSUMPTION ACCEPTED (79493e604): fixtures initialize
  from compiled program rows — manifest-rooted, SHA-256-verified,
  the one pager, all bespoke/schema-only/per-test paths DELETED;
  loud :core-bug on any staleness (the design refusing stale
  artifacts is the feature). Registry-gate green lands at the
  checkpoint's fresh frozen-tree build (current artifact = v11 +
  today's renames). FIXTURES-ARE-CLUSTERS-IN-MINIATURE IS BUILT.
  ONE lane before the freeze: r43trust.
- TRADEOFF AUDITS DISPATCHED (owner, 2026-07-23 eve — 'understand
  the tradeoff better'): (1) interp-bench — sci-on-JVM vs compiled
  JVM vs (if still executable) Bun self-host on a representative
  workload matrix, through the REAL guarded door, guard cost
  isolated, plus a COMPUTED glue-vs-compute-heavy classification of
  the actual corpus (program-rows.edn sample) feeding P6 placement;
  (2) runtime-cost — 10-15 agent live load on an isolated cluster:
  per-turn phase latency from receipts (coordination vs LLM vs eval
  columns), writer tx/s, jstat GC per JVM, RSS idle/load/after,
  honest 100-agent extrapolation + R27 -Xmx recommendations. NOT
  kill drills (C9 pause respected). Checkpoint drive additionally
  captures the baseline perf ledger. Both on isolated clusters,
  source-read-only, parallel to the freeze chain.
- INTERPRETER-STEP RENAME ACCEPTED (946e1a190, verified: zero
  'fuel' left in src/config runtime; steering asserts 'exceeded its
  interpreter-step budget'; no compat alias per R38; deadline/
  output-cap untouched; AGENTS.md row updated by grant): config
  facts are :seon.config.guard/*-interpreter-step-budget; guard
  internals interpreter-steps-*. TWO lanes remain before the freeze:
  fixseed (consumption) + r43trust.
- S2 TRANSACTION-DATA ACCEPTED (4b3d32093, verified path-limited,
  58/313 green): program-rows.edn published per supported build +
  release under the grounded name — compiled index derivation, row
  bytes preserved exactly, program-sources digest binding, artifact
  manifest + application digest membership; 5,487 rows current
  corpus. FIXSEED RESUMED on its trigger (fixture consumption via
  the artifact manifest + digest verification; registry-gate green
  = acceptance; r43trust + interpreter-step files protected).
- ADVISORY (owner, 2026-07-23 eve): the codex agents database may
  be broken (owner repairing) — until the all-clear: no new codex
  dispatches; strange lane failures attribute ENVIRONMENT-FIRST;
  accepted work is safe in git; exposure = in-flight uncommitted
  lane work + session resumability. Three lanes were alive and
  healthy at the advisory (r43trust, transaction-data, interpreter-
  step rename).
- BOOTFAST (S1) ACCEPTED (df2cb508a, verified from gate log):
  build-projection 2,962→210 ms COLD (14.09×), fingerprint
  byte-identical; reducers/fold adds 1.22× on the JVM (CLJS stays
  serial — no parallel reducers, registry can't cross Bun workers);
  duplicate fresh-boot projection build eliminated via
  fingerprint-guarded reuse (mismatch → full rebuild); the 35s gap
  (reconcile-config!/ensure-initial-agent!) now logs progress. Wiki
  scar staged (bootfast-wiki-scar.md) pending the wiki file's
  release. Real boot numbers measured at the checkpoint. R43TRUST
  RESUMED into the freed schema/client/turn files with the grant
  (authorship fields through BOTH cold and reuse paths, in the
  fingerprint if classification-affecting).
- R47 APPLIED TO THE GUARD (owner catch, 2026-07-23 eve): 'fuel' is
  an invented metaphor for what is literally an interpreter STEP
  count at sci's :interrupt-fn safepoint — STEPBUDGET rename lane
  dispatched, refined by owner: INTERPRETER-step budget ('step' alone
  collides with agent-loop steps; config facts →
  :seon.config.guard/*-interpreter-step-budget;
  internal counters → steps; steering says 'exceeded its step
  budget'; deadline/output-cap unchanged — already literal; history
  keeps its vocabulary; R38 makes the config-key rename
  migration-free). Six files + tests, all unowned by live lanes.
- R43 STOP RULED (2026-07-23 eve, correct stop + real design catch):
  SPEC-provenance is unsafe for trust — an agent can replace SOURCE
  while keeping the identical spec (no-op assertion retains core
  provenance) and inherit core trust. RULED: classification keys on
  the SOURCE datom's asserting transaction; precedence corpus-source
  provenance (core→core, agent/unknown→agent) BEFORE exact compiled
  artifact export (→core), else agent fail-closed; one seam
  (agent-authored-sym? symbol projection). Its schema.cljc need
  collides with live bootfast (same build-projection region) —
  r43trust RESUMES AFTER bootfast lands with the grant
  (schema.cljc source-authorship projection fields + turn.cljs
  consumer). It reverted its exploratory edits before stopping; tree
  carries only rowsidecar's live work.
- TESTSIMP AUDIT ACCEPTED (research/test-simplification-audit-
  2026-07-23.md; honest counts incl. pushback): 228 test files /
  2,296 deftests; migrate 20 raw-genesis transactions (8 host
  namespaces) + 6 bespoke initializers + 8 repeated schema installs
  onto the shared fixture entry when it lands; prose pins split 38
  fragile vs 18 LEGITIMATE exact oracles (keep); C1 property justifies
  13 deletions + render byte-parity consolidation 5 more (~18-25 net,
  bounded honest estimate); only ONE stale call-count test (my.plan
  pre-U7 — already the R43 regression). Implementation lanes queue
  behind the S2→fixseed chain (same dependency).
- FIXSEED WRAPPED + ACCEPTED (13183c222 + e84e10bf5, verified: JVM
  require proof 884 canonical keys / 247 database attributes;
  schema.cljc+client.cljs clean): boot schema population PORTABLE in
  the schema authority; canonical-database-attributes COMPUTED from
  entity-map membership + persistence facets; agent-bootstrap-attrs
  = computed delegation (hand list DEAD, the fixed genesis list too);
  production still seeds through the one paged path. Registry-gate
  red (15F/3E) = attributed fixture-program-rows gap awaiting S2;
  session resumes on S2's landing. REVIEW FLAG for rowsidecar: its
  in-flight hook requires babashka.process outside the test-hook
  classpath and blocked the focused CLJS execution manifest — its
  return must prove the test build path intact. BOOTFAST (S1)
  DISPATCHED into the freed schema/client files (REPL probe first,
  byte-equality gate, R44 fold parallelism, D2 reuse, D4 gap
  instrumentation).
- FIXSEED STOP #3 RULED (2026-07-23 eve, convergent): the fixture's
  JVM reconstruction of index-core! rows cannot equal the CLJS
  analyzer's output — the portable answer is the build-emitted exact
  program-row artifact, WHICH IS S2 (rowsidecar), already in flight.
  Fixseed extraction landed (13183c222); ruled: wrap cleanly (commit
  the shared-pager genesis fix, document the sidecar-dependent
  residual, gate red 15F/3E = attributed fixture-program-rows gap,
  not production), resume on S2's landing. CHECKPOINT SEQUENCING:
  hold for rowsidecar → fixseed-resume → green registry gate →
  bootfast → freeze → checkpoint (honest green over speed; parallel
  lanes fill the wait).
- PARALLEL FILL (2026-07-23 eve, owner: 'anything else in
  parallel?'): THREE new lanes beside fixseed, all file-disjoint —
  r43trust (implementation: derived-provenance classification,
  error.cljc + render/core.cljc, fixes live my.plan rendering before
  the checkpoint demo), testsimp-audit (read-only: the migration
  worklist onto the one seeding path + F-row folds), rowsidecar (S2:
  build-side program-rows sidecar, production only, byte-faithful
  regression vs live derivation; consumption stays S3/S4). Four
  lanes live total.
- B2 LIVE-PROVEN BROKEN + R46 SETTLED (2026-07-23 eve): the probe
  showed the tier-less scheduled eval's ERROR VALUE IS DISCARDED and
  the turn closes falsely :done with zero receipts (receipt-integrity
  class, part of the fix). Design accepted (research/scheduled-fns-
  eval-design-2026-07-23.md). R46 (owner, via structured choices):
  fire = durable eval-only turn then ordinary LLM wake (agent reacts,
  incl. to failures); missed fires SKIP + record a visible miss fact;
  one ordered batch per agent/minute, all forms attempted, stable
  schedule-id order; CLOCK = JVM CLAIMANT duty effective now (owner
  said "JVM side for sure", floated writer — orchestrator pushback
  recorded: R26 keeps the writer tx+feed only; claimant creates
  CAS-idempotent fire facts keyed schedule×nominal-minute, races
  safe; scheduled FUNCTION executes behind the guarded sci door;
  cron strings parsed as data by the core parser). Remaining 22
  design questions ruled by existing laws (frozen fire basis;
  content-addressed source blob under its OWN attribute — reply-blob
  stays LLM evidence; fires are events surviving schedule edits;
  transcript-visible, work-count-excluded; typed error for absent
  session). SCHEDFIX lane queues at the front of the post-checkpoint
  wave (after fixseed frees agent/*).
- PROCFIX ACCEPTED (fe5e289b9 + 558a9d601, verified path-limited,
  118/545 green): lifecycle membership graph-derived, target/result
  equality loud, exact-identity dead-generation reaping; LIVE proof
  = kill web-render child → ordinary down → fresh reset succeeds →
  all records absent. Orphaned-record class dead; issue archived.
  Full reconciliation/onExit redesign remains post-checkpoint.
- TEST-SIMPLIFICATION AUDIT QUEUED (owner, 2026-07-23 eve): once
  fixseed proves the fixtures-are-clusters-in-miniature mechanism, a
  Sol audit sweeps the whole test surface to migrate suites onto it
  and simplify: every bespoke fixture-seeding helper dissolves into
  the one paged-initialization path; combined with the fragile-tests
  audit's F-rows (exact-prose pins → behavior asserts, point tests →
  the C1 generative round-trip, one regression per class). Deliverable
  = migration worklist + deletions, then implementation lanes.
  Dispatch trigger: fixseed's accepted return.
- R45 vocabulary + maintenance (owner, same evening): the operation
  is PRE-PROCESSING (use that name); and the preprocessed cache is
  kept CURRENT AS CHANGES HAPPEN — every mutation that would
  invalidate it (registration, durable defn, hot reload) updates the
  keyed cache write-through in the same flow, O(delta), so a restart
  or resume at ANY moment finds a current cache and is free. Explicit
  apply covers artifact/schema-generation changes; write-through
  maintenance covers the running cluster. A restart never re-derives
  and never waits for a batch step.

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
