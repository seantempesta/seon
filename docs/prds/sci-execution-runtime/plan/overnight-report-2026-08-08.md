---
type: prd
status: active
tags: [prd, runtime, testing]
---

# Overnight report — 2026-08-08 (accumulating; final version at morning)

## THE HEADLINE: THE ARC RUNS

([drive-fix-redrive-2026-08-08.md](../research/drive-fix-redrive-2026-08-08.md),
`a7683f0ae`..`4bfc2fdfa`.) A human message opens its own run, renders a
REAL 24,257-token context, the model plans against it, four forms settle
four receipts, and the run closes with a settled reply — twice. The
first human-triggered end-to-end agent turns in the fresh system's
life, achieved by hot reload alone (509-char context → 80,834 across
the two reader fixes, no restart). The as-of reader class is dead
across all four database views (derived through Datahike's own
IHistory interface, with one documented deliberate divergence: the
run-opening instant is `<=`, not strictly past). The fault loop is
stopped (a self-escalation guard; the full second-mechanism deletion is
the recorded follow-on — a protected test pins per-failure cross-agent
escalation). Token economics restored: completion:prompt 0.22 (from
46.7); the 08-06 prompt explosion is measurable again and 45% smaller
(24,257 vs 44,306) — still the open sentinel at that size. The
starvation diagnosis was REFUTED (selection was never starving; the
observer's 46/41 receipt gap did not reproduce). The healthy context
immediately exposed the next blocker, dispatched: an agent cannot
define a CONTRACTED function — the first thing a healthy agent tries.

Owner directives in force: run hot until morning; uncapped parallelism
until measured degradation (load is a stress test); multivariate success
(fewer bugs, faster, fewer lines, better agent context); adversarial
socratic checks on every claim; rotate ideas 90° before implementing;
token explosions = high-priority bugs; complete bare runs are
truth-checks, not gates.

## Decisions needing you (ranked)

1. ~~Background work bounds~~ **RULED AND LANDED** (`aeb17ec7f`):
   config default 600 s (`:seon.config.effect.background/time-limit-ms`)
   plus an optional per-call override winning in either direction, no
   clamp —
   the owner's "great defaults and easy and intuitive overrides"
   verbatim; a fresh detached arm makes unbounded unrepresentable;
   proven live both directions
   ([archived issue](../../../seon/issues/archive/the-effect-door-runs-capability-handlers-unarmed.md)).
2. **Two 30-second naming/shape calls from P17-S1** (accepted on your
   existing rulings' authority, veto open): (a) the row schema landed as
   `:seon.call-preparation/*` — "provider" was deliberately NOT reused
   because the vocabulary table binds "provider descriptor row" to
   hosted-model config rows in the same initialization vector; (b)
   suppliers take ONE argument (the environment) — r2's zero-arg
   spec is impossible once the dynamic var dies (ruling 2), and a
   one-arg declared contract keeps everything a query.
3. **May a run refine a schema key nothing depends on?**
   ([issue](../../../seon/issues/within-run-schema-key-refinement-needs-an-owner-ruling.md))
   — a genuinely irreducible tension: the accretion rule's "a key's
   definition never changes" refuses it; the usage guard (which knows no
   data depends on it) permits it. The guard-decides reading is
   IMPLEMENTED (the blocker's own acceptance mandated it verbatim);
   the issue prices the strict alternative. Related follow-up when you
   rule: the unified decision path's two refusals have mismatched faces
   (flat typed value vs ex-info surfacing as a Datahike write-rejected
   prose string).
4. **Installation-lock scope for single-root operator work**
   ([issue](../../../seon/issues/jvm-operator-work-takes-the-installation-lock-for-one-root.md))
   — cleanup/collect/refork still serialize on the installation lock;
   the obvious per-root change DEADLOCKS because a child JVM inherits
   the parent command's root lock through the `-under-lock!`
   convention. Needs a design ruling, recorded with the deadlock
   analysis.
5. ~~Component ref~~ **RULED (option 1) AND LANDED** (`e7bafc957`):
   the widening derives from the declared `:seon.db/component true`
   property at ONE choke point (`compilable-form` — every compile path
   passes it; a new site cannot forget it); compiled shapes widen,
   persisted forms untouched; shape-selection risk discharged by probe.
   LIVE: the first agent-authored contracted function through the full
   production path — defined, called (receipt `{:label "b" :amount 9}`),
   contract queried back — bootstrap-authored forms (no model key in
   the lane; model authorship lands with the arc). Both issues archived.
   (Original decision text follows for the record.)
   **Does a component ref admit the component value?**
   ([issue](../../../seon/issues/archive/a-component-value-is-refused-by-its-own-ref-shape.md),
   three options, recommendation marked) — `:seon.db/ref` admits
   int/string/lookup-tuple but never the component value itself; all 46
   `:seon.db/component true` declarations share the identical broken
   shape, and it is THE remaining wall between an agent and defining a
   contracted function (the admission-source half is fixed and
   unconstructable-wrong at `4fd806a94`; `(ns … :as …)` is blocked by
   the same class). A registry-wide contract decision — the lane
   correctly stopped at the design gate rather than editing 46 sites.
6. (accumulating)

## Landed overnight (chronological, with commits)

- Adversarial audit (`ae98ef06a`) — refuted "declaration-population done"
  (admission seam dominant: 54,884 fallbacks/hour); third DECLARED
  identity-collision family; effect door unarmed + background deadline
  inversion; calibration: boot-as-only-constructor, schema-derived
  members, sci refusal, arm structure all held.
- Warning-yield sweep (`8019a79d4`, `7dd61703f`) — warning wall fixed at
  owner (was 45% of boot-test transcript); seon.ai was resolving 66
  populations PER MODEL REQUEST — now one; first-party frame derivation
  computed from the CLI basis (rotation applied after audit objection);
  issues index check-clean including fixing the checker's own regex bug.

- Render-proc stop completion (`8872311d1`..`897aeb51d`) — cause was
  NOT the stop path: a declared producer delegating its own value
  RE-ENTERED selection (infinite render recursion at depth 13 on one
  virtual thread; found by in-window virtual-thread dump). Class made
  unconstructable via the rendering-chain set, not depth-capped.
  `seon.render.web-test`: first zero-error run ever (38/274, twice).
  The recent SSE fix was checked first and is innocent. Follow-up owed:
  `resources/seon/schemas/seon.render.edn` is currently UNEDITABLE (five
  pre-existing `:any` declarations block every edit at admission) — the
  new `:seon.render/rendering` key works via open maps but cannot be
  declared until that file is repaired (queued).

- Cleanup lane (7 commits, `7706a4279`..`b0900b28a`) — all 10
  plain-accumulator census items converted to immutable returns; opaque
  generators construct fresh samples; two dummy atoms deleted via an
  honest untracked arity; `var-process` sheds an `:any`. HONEST DELTA:
  **+148 lines net** (threading costs characters; the regression + shape
  comments are most of it) — what shrank is 12 mutable constructors and
  the shared-sample class, not lines. Deferred rows recorded (files
  owned by running lanes). Dead-code sweep found NOTHING deletable —
  the 5 kondo unused-private warnings in fs/jvm.clj are false (reached
  via capability symbols; kondo cannot see that edge).
- **Suite tiering SHIPPED — the overnight headline number**
  (`b6886bf36`..`0cc63c829`): bare `bin/test` went **965.9 s → 43.6 s**
  no-change (platform tier only), **52.5 s green end-to-end** on a
  one-file change, 83% bulk skipped under four-lane churn, fail-fast
  verdicts in ~37 s. One runner evolved in place; the old
  namespace-closure selector DELETED; changed = program-graph
  reachability against a content-digest green basis; widening loud and
  named; selector class regression non-vacuous both directions. Honest
  gaps recorded in the spec addendum: the platform tier lacks
  flow/settle/sci-fork coverage until the spec's seven consolidated
  regressions land (a flow test hangs without its siblings — issue
  filed); macroexpand-only test edges are a missing index fact.
- New loose ends queued: `settle!` has no `:malli/schema` (contract
  census catch, W1 wave); `seon.render.transcript-test` 28 failures
  (needs a lane once the tree is coherent); `seon.fn-test`/`program-test`
  errors to re-verify after the selection.clj fix settles.

- **Tool exercise (`a4f6f31d1`) — six blockers on never-driven paths.**
  The big one: EVERY background capability request loses its connection
  on the `:io` hop (8/8 failed; foreground identical command staged
  40 MB correctly) — Defect I failing outright, relayed to the
  carriage-finish lane as acceptance evidence. Also: `my.fs` windowed
  reads refuse on whole-file size; `my.web` is unreachable from agent
  code (the only two unresolvable `my.*` fns); an interrupted
  `my.shell/run` orphans its child AND its receipt (recorder throws
  too); `next-agent-work` requires a `now` it never reads (all curation
  proofs fail); a schema-resource edit bricks value admission in every
  RUNNING cluster (live urgency evidence for the sentinel deletion).
  Sound under exercise: effect identity/provenance across 22 receipts
  incl. 8 concurrent, transport law at 40 MB (blob tier, 211 ms), fs
  symlink discipline. Velocity finding: ~2.4 s per-form turn overhead.
  Token findings: ~290 tokens per poll result; a six-word error
  rendering as 2,154 chars. Tool-repairs lane dispatched on the four
  agent-facing defects.

- **Admission blocker CLOSED** (`9d5c986eb`/`45a87196a`/`114dc6aa7`):
  one declarations delay in the walk state — an identity question went
  15.263 ms → 0.012 ms; a realistic 121-node admission ~120 populations
  → 1 (374 ms → 24 ms); the test-side hoist alone took admit-test
  259 s → 13 s. Class regression counts one resolution per admission,
  zero for scalars, zero when supplied. Remainders recorded in the
  issue for their owners (per-EVAL projection rebuild in eval.clj is
  the notable one). The fallback warning's per-caller counter did the
  finding work — the diagnostics flywheel, again.
- **Coherence incident #4, escalated:** P17-S1's uncommitted state
  still blocks every cluster boot tree-wide (config rows referencing
  unpublished supplied-* fns). Second, urgent notice sent with exact
  restore steps and a stop-and-hand-over option.

- **Carriage COMPLETE** (`226da97f8`..`43544d48a`): all three `bound-fn*`
  sites deleted (dependence falsifier written first, fails both ways
  when wraps restore); arm capture DERIVED from the surface — awaited
  submissions carry the submitter's arm, detached ones don't (no flag);
  the effect door adopts the arm (unbounded handler now interrupted at
  ~300 ms; 0 → ≥20000 attributed entrances); the background connection
  loss fixed at cause (worker rebuilds the request frame from the
  submission's environment); a LATENT break found that conveyance had
  hidden (admission dials read through a binding frame at settlement).
  Platform tier green before and after. Named remainder: the foreground
  `bound-fn` survives until Phase 3 converts the dynamic-var readers
  (shell.jvm:290 and peers) — recorded in source and issues.
- **P17-S1 COMPLETE** (`2a12b95ed`..`331b9f310`): call-preparation rows,
  plan derivation, cluster-local cache with basis refresh, the two
  database suppliers, shipped config rows landed LAST with a
  fixture-boot proof; 9/32 green. It also fixed two cluster.clj defects
  in passing (the bare-`distinct` refusal crash and the true sequencing
  cause: initialization rows transacted BEFORE program rows — moved to
  the end of populate-source!). S2's key finding recorded: the hook
  fires only for sci.lang.Var callees. And it exonerated itself on my
  stale second escalation — config was restored within a minute of the
  first flag; the shared-root refusal was the stale-projection class,
  and the sovereign old default branch then needed the refork (running).

- **Schema-environment lane CLOSED (criterion 1b correctly deferred)**
  (`f2903354a`, `37700ec64`): predicate resolution = the Var a qualified
  symbol names (the last-writer-wins cache and its snapshot machinery
  DELETED — with a mid-flight refutation that mattered: naive
  requiring-resolve would have let an agent [:fn] form load arbitrary
  namespaces; constrained to already-loaded Vars); compiled state hangs
  off the projection (the process slots deleted). The third criterion
  was implemented, measured green on 177/178, and REVERTED on the one
  real dependency: malli.instrument registers contracts through Malli's
  global default — which enlarges the class (malli.core's
  -function-schemas* is a second process-wide slot). The reverted diff
  lives as the issue's falsifier. The four dynamic vars stay for Phase 3
  (hook consumption first — boundary named, not crossed).
- **PLATFORM DEFECT dispatched: `--root` lock isolation is broken** —
  with-operator-lock ignores its root and locks the shared repository
  file; six commands across three roots queued silently (oldest
  11m35s). "Clusters can't boot" tonight was partly QUEUED, not broken;
  the default refork is likely in that queue now. Repair lane launched
  (root-derived lock, bounded loud waiting, stale-holder cleanup).

## In flight at last update

stop-completion (render proc liveness) · suite-speed (tiers +
changed-only default) · P17-S1 (provider rows) · cleanup (census
deletions) · tool-exercise (IO/background hammering) · admission blocker
(per-node resolution) · carriage-finish (door arm, background,
bound-fn* deletion) · schema-environment (Defect I root owner).

## Defect ledger (found overnight)

- Per-node admission resolution (blocker; lane on it).
- Third identity-collision family, declared shape (appended to issue).
- Effect door unarmed / background deadline inversion (lane on it).
- selection.clj `(partial instance? File)` contract broke 33 tests
  (flagged to owning lane mid-flight).
- MCP envelope echoes the submitted form in every event (5× duplication;
  queued).

## THE DRIVE RAN — first settled run ever, honest mixed verdict

([live-drive-2026-08-08.md](../research/live-drive-2026-08-08.md)). The
platform completed its FIRST settled run: real context capture, a real
DeepSeek attempt, 13 receipts, clean close — but triggered by a system
error message, not the human's, with a 509-character context (the render
walk refuses as-of values — the pinned-temporality class biting a second
time; the driver fixed the first instance live with the authorized
one-line exception). Six-blocker scorecard vs 08-06: 2 fixed (problems
projection, /data — plus debug page and transcript pull-many), 2
transformed, 1 recurred WORSE (message selection starves human messages
while fault messages self-feed), 1 unmeasurable until the context is
real. Token sentinel: an INVERSE explosion — 225-token prompt → 10,502
completion tokens of the model rationally debugging its own empty
context; and one error's data-edn is 4.25M chars. Fix-and-re-drive lane
dispatched on the driver's single-seam recommendation: kill the
as-of-reader CLASS (four-view total-reader regression owed and
generalized), fix message-selection starvation, re-drive. Observer's
independent report still incoming.

**The observer's independent account
([live-drive-observer-2026-08-08.md](../research/live-drive-observer-2026-08-08.md))
— three plain disagreements, all evidence-backed:** "admission blocker
CLOSED" was too strong (the dominant resolver MOVED to seon.print —
9,665 resolutions/20 min at a measured 10.6 ms; only Phase 3's
environment supply ends the class; the /data 5.5 s stall is PROVEN as
556 of those calls, predicted-vs-measured within 8%); the warning wall
still owns 70/87 boot lines; and the driver's "claiming recurred" is
REFUTED — claiming was exactly-once at all 84 samples, the starvation
is upstream in selection/wake. New blockers: the self-sustaining fault
loop (nine PAID calls in 20 min, no stimulus — each failed turn's fault
message wakes the next); 46 forms → 41 receipts (runs close leaving
receiptless forms). The economics finding worth remembering: **a
starved prompt costs more than a bloated one** — 2,025 prompt tokens
became 66,591 completion tokens (32.9:1) of the model reasoning around
missing context. Calibration: custody clean at all 84 samples (the
08-06 custody failure is dead), provider integration sound, capture
durable-and-exact is what made the diagnosis possible at all. All
refinements relayed to the running fix-and-re-drive lane.

## The drive-arc launch record

Default reforked from current-src at tonight's HEAD (commit `6a76b0c6…`)
and standing at `http://127.0.0.1:7994`. Driver + independent observer
launched: the 08-06 arc rerun first (explicit fixed/present/transformed
verdict per old blocker), then the planning+memory extension; token
sentinels armed on every prompt and render; the observer trusts nothing
the driver says. Results land in
`research/live-drive-2026-08-08.md` + `live-drive-observer-2026-08-08.md`.

## The earlier drive-arc stall — resolved, kept for the record

The reset stalled on a chain of three real platform defects the stress
load exposed in sequence, each filed: the operator lifecycle lock
ignores `--root` (fix lane running); the shared store flock was held by
queued/zombie operators; and the dependency class-cache prepare races
concurrent JVM launches (issue
[dependency-class-cache-prepare-races-concurrent-jvm-launches](../../../seon/issues/dependency-class-cache-prepare-races-concurrent-jvm-launches.md),
with a second failure mode appended by the tool-exercise lane — and my
own rm-per-attempt retry loop was an instance of that second mode,
caught by rotation and stopped). Plus one true refusal: the sovereign
old default branch correctly refused new config rows referencing
functions its fork predates — the refork is the remedy and awaits a
quiet launch window. Interim policy: no staging deletions while lanes
launch JVMs; single clean start at quiescence. The drive runs the
moment default stands.

## Late-night additions to the stall chain (all owned, all loud)

- **Boot broken at HEAD by a repair**: the my.web fix (2db8a4be4)
  eagerly requires the whole program graph including test-provenanced
  namespaces the boot JVM cannot load — no cluster boots; owning lane
  flagged with the fix shape (bind the servable slice, provenance is a
  declared fact, never a path rule).
- **The refork class RECURRED in a second shape**: init --force
  destroyed a real cluster branch then failed its own second store open
  (process's own retained holder) — routed to the operator-lock lane
  with the census-contradiction observation ("flock free" one second
  before "held by a live process").
- The background-connection verdict stays honestly OPEN — the
  re-verification could not run behind these; it read f3b8eabda and
  judges it right-shaped but unproven. Ten minutes of work once boot
  stands.

- **Tool-repairs COMPLETE and boot FIXED** (`4eb8c6ab4` + five more):
  process-membership is a computed classpath fact (graph membership ≠
  process membership — two facts, the second answered by the
  classpath, never a path rule); boot reaches every layer AND my.web
  resolves — both halves proven, neither traded. All four agent-facing
  defects landed: windowed reads (a 4 KB tail of a 20 MB file in 76 ms
  where refusal was the old answer), my.web reachable (cause measured:
  it registered no predicate so nothing ever loaded it), interrupted
  shell runs cut at the arm's published deadline with one evaluation
  constructor, the unread `now` deleted. New finds filed: the ~2.4 s
  per-form cost (blocker, velocity incident), poll's token cost, the
  2,154-char error face, and my.web returning HTML as a vector of
  integers (two capabilities disagreeing on text decoding). It also
  attributed the turn-test red precisely (the conveyance deletion vs
  the suite's dynamic stub) — relayed to the triage lane.

- **Turn-test red CLOSED, attribution verified not inherited**
  (`1d055a7dd`): the conveyance deletion was correct and stands — the
  fixture pinned the deleted carrier (a dynamic Var whose binding the
  old `bound-fn*` carried; git -S shows that wrap was introduced FOR
  this in July and deleted tonight). Fix: a NON-dynamic injection Var —
  `binding` on it will not compile, so the thread-local shape is
  unrepresentable, not just fixed. Zero production change; 49/339 green
  ×3; platform tier clean. One new ugly-output issue: a bare `case`
  over print faces throws "No matching clause: " naming nothing.

- **Operator reliability landing COMPLETE** (`912199d73`..`e83fd4bae`):
  lifecycle locks scoped per root (locking a foreign root
  unconstructable; waiting loud with holder pid/command/duration; 15-min
  backstop refusing with flat facts); the recurred refork class fixed at
  BOTH causes (the composition discarded the store it was handed; the
  refusal said held-elsewhere about the process's OWN hold — now
  held-by-this-process naming the pid, and the orchestrator's
  census-contradiction hypothesis is REFUTED: the wording lied, the
  census never did); a real POSIX descriptor hazard caught by the
  lane's own regression before shipping (closing any descriptor drops
  the process's lock — threads now claim in-process first). Live proof:
  the exact destructive reproduction now reforks cleanly; isolated
  roots run concurrent lifecycle commands in 0.092 s against a held
  foreign lock.

- **Escalation single-owner LANDED** (`c3f526cb3`): the hand-rolled
  unbounded escalation deleted with its guard (structurally unnecessary
  — a phase failure is a value, the self-mail arm cannot fire); one
  honest port (failure message text into the notice prose,
  omit-when-absent); escalation is one message per signature per
  process, cross-agent storms die with the same cut; live probe of the
  exact fault-storm conditions: zero new messages, zero provider calls.
  The self-wake issue honestly NARROWED not closed (one acceptance
  clause — reply-refusal-to-attempt-count — still unclaimed, downgraded
  to friction with its fixtures named). All three 2026-08-08 ruling
  lanes are now landed; the whole-system arc launches next.

## THE WHOLE-SYSTEM ARC — driver's account (observer pending)

([whole-system-arc-drive-2026-08-08.md](../research/whole-system-arc-drive-2026-08-08.md)).
PROVEN: **four agents in concurrent live turns, one cluster** (four runs
open simultaneously, one holder each, zero custody faults); the
stage-3 restart WITH A RUN IN FLIGHT (receipts unchanged at 102,
nothing re-executed, custody shed, the unconsumed message re-drove root
5 s after boot); every web item (27 SSE morphs / 7.2 MB live; mid-turn
reconnect → one current keyframe; debug pane exact prompt bytes in
31 ms; /data answers). BLOCKED: call preparation is NEVER INSTALLED in
production (suite green on a dead mechanism — the fixture-vs-boot class
again; S2 dispatch is the fix, one-line arming correctly refused);
mid-stream provider disconnects discard whole turns (7 consecutive —
what actually blocked model-authored functions). SOLVED: the receipt
gap = DeepSeek control tokens leaking into reply text (comment-only
forms). DECISIONS RAISED: canvas is UNBUILT (vocab says it is THE
surface; rg finds nothing — target never built in the fresh tree);
disposition-less turns close while the docstring says stay open (one is
wrong); agents have no taught route to declaring data attributes;
root's prompt grows to 36k across turns without completion (resets at
completion — the ceiling is turns-since-completion) with poor cache
hits. Driver's own errors recorded honestly (operator-side agent
creation, one false teaching, undeclared attributes refused correctly).

**The observer's arc account
([whole-system-arc-observer-2026-08-08.md](../research/whole-system-arc-observer-2026-08-08.md))
— the concurrency headline CONFIRMED by measurement** (20–30 s pairwise
turn overlaps from opened-at/closed-at; custody clean; claiming
exactly-once; ZERO cross-agent prompt leakage counted mention by
mention; the empty-context blocker genuinely dead). Moved claims: the
restart hid that recovery closes interrupted runs UNMARKED (zero
interrupted-at datoms cluster-wide — the crash model's honesty clause
undemonstrated); `tokens/estimate` runs 23–26% below DeepSeek (a 35.8k
prompt passed a 32.7k budget); the provider failures are
concurrency-correlated with the per-request HttpClient at ai.clj:1069 —
plausibly ONE transport cause behind both lanes' symptoms. Its method
section records four new vacuous-probe traps caught (the
`d/datoms :avet` non-indexed silence is the one every future observer
must know). POST-ARC WAVE DISPATCHED, five file-disjoint lanes: P17-S2
(call preparation into production — the arc's exact failing call as
live proof), provider transport (shared client + honest truncation, no
retries), recovery-marks-interrupted, token calibration from recorded
usage facts, and the control-token reply leak.

- **Token calibration LANDED** (`cc9357948`, `26a047ca2`): the estimator
  fits chars-per-token PER MODEL from the provider's own recorded
  prompt_tokens (one new scalar fact, no second sizer), reports its
  measured error band, admits near-limit only with a loud warn, names
  every judgement's basis, and the shipped fallback invents NO band
  (unknown error stays unknown). Proven: the observer's 35.8k prompt
  that passed a 32.7k budget under chars/4 is now refused (+0.7% from
  actual); reproduces from the live cluster's 14 attempts (3.28
  chars/tok, 9.3% band). Render lane dispatched on its two foreign
  finds: the transcript floor rendering a missing-root-identity refusal
  where agent output belongs, and the render.edn-uneditable blocker
  (both agent-facing dogfood defects).

## Pending milestones

- stop-completion lands → reset → 08-06 drive-arc rerun with observer →
  extend to planning+memory → messaging wave + dogfood lanes.
- Rename waves at natural drain points (owner-approved list in the PRD).
- **Degradation point FOUND at 8 concurrent lanes — and it is not the
  machine.** CPU/memory hold; measurement noise ~40% under load is the
  only compute cost. The binding constraint is SHARED-TREE COHERENCE:
  three fixture outages in one stretch, all one shape — a lane's
  mid-edit state (P17-S1's config rows referencing unpublished
  suppliers; a parse error in schema_test.clj; an unclaimed diagnostic
  hunk) turning every sibling's proofs red until it lands. The gates
  refuse loudly and attribution takes minutes (twice the flagged lane
  refuted correctly and the real owner was found by diff), but each
  incident stalls the fleet. Mitigation applied: aggressive
  commit-small-slices enforcement via direct pings; NOT adopting
  worktrees (standing ruling: shared checkout is the model). The honest
  ceiling at current discipline: ~6-8 heavy lanes when several own hot
  files; more is fine when file ownership is disjoint and cold.
