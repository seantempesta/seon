---
type: prd
status: active
tags: [prd, agent, architecture]
---

# What is not settled

**SEALED (owner, s3 close): schema definitions leave code files.**
Attribute/entity schemas are EDN data under `src/seon/schema/*.edn` —
the schema OWNER's folder, on the classpath so runtime inspection and
`(schema/reload!)` are first-class. The population is GLOBAL: file
boundaries are editorial convenience with zero semantic meaning; the
loader merges every file and refuses a duplicate attribute across files.
ONE validating admission gate (all references resolve, every `[:fn]`
names a registered core predicate, generative-honesty lint) shared by
both producers: our declared files at build/boot, and agents'
`register!` transactions at runtime — one gate, one registry, and the
build-time indexer READS THE DIRECTORY instead of loading namespaces to
scrape registrations (deleting the load-set-closure failure class at the
producer). Function contracts stay on defns; named predicates stay in
code. Conversion of N2's in-code registrations into the first schema EDN
happens when the nucleus-run-impl lane returns (never edit a sealed file
a lane holds); fold into the B2 contract at authoring.

**2026-07-26 s3 close — the nucleus era.** README's session-3 rulings and
the nucleus ladder (R0/B0-B3/N2-N6) supersede everything below that
contradicts them; rows below predate the pivot and survive only as
evidence pointers. **N2 IS GREEN** (verified 2026-07-26 s3 close: 7 tests / 24 assertions /
0 failures / 0 errors via the nucleus loop command; implementation
`d30a405f9`, contract revisions `1b03d80bb`+`a370b5e31`). The
construction loop is PROVEN: three friction cycles, every stop
legitimate, no schema or test weakened. First rung metrics: 1 namespace,
17 registered attributes, 8 pure functions, ~330 nucleus source lines.
Non-blocking friction on record: claim-tx's two observed takeover fields
are independently optional but required together — tighten to one
optional takeover map at the next contract revision, not before.
Corroborated 2026-07-27 by the first live Gemini hook review
(tmp/reviews/20260727T112009): a nil `observed-epoch` in takeover mode
emits `[:db.fn/cas ... ::claim-epoch nil 1]` against a non-nil epoch —
the takeover map must carry a REQUIRED epoch. Fold into the same
revision.

**R0 EXECUTED 2026-07-27** under the session-2 rulings (fresh tree IS the
project): fresh `src/` = seon.cluster.run + seon.schema{,.form,.internal,
.datahike} + seon.flow; fresh `test/` = run-test + flow.loop-test; root
`deps.edn` rewritten (fresh default + `:dev`/`:test`; old world behind
repointed `:writer`/`:writer-test`/`:cljs` aliases); `bin/test` is the
system gate (8.15 s full, selection via the edit hook in seconds); system
load 2.23 s (10-second ruling already satisfied pre-B0). Contract
revision on the sealed N2 test (author-owned): the malli→datahike bridge
moved to its proper owner `seon.schema.datahike` — the test no longer
requires the old `seon.db` facade. `seon.flow` owns its config dials
locally (the `config.resolve` seam cut). Verified: `bin/test` 11/55/0/0;
hook selects exactly the affected fresh suite per edited file.

**N2 REVISION GREEN (2026-07-27): 6/28/0 via bin/test** — transitions
inside the transaction (Opus implementation `ba5cb0c1e`, contract
`c65ddeeda`, suite harness fix by the author after the lane's correct
friction stop). Open contract decision for N3: a refusal's ex-data does
not survive Datahike's writer boundary (caller sees {}; the kind/rule
live in the message and cause chain) — the run loop's transact wrapper
is the ONE unwrap point to design when the loop consumes refusals.

**Quality-review-1 verdict (2026-07-27, `d91dab541`) — resolved by the
revision above; original text:** the N2 contract
revision is now FIRST, ahead of B0/B1 — two live-reproduced correctness
holes (takeover eligibility not fenced in the transition; agent pointer
not fenced at open/close) plus the property gaps that let them through
(no takeover/close/heartbeat coverage; terminal-preservation unproven)
and the Gemini-corroborated nil-epoch takeover. One revision wave,
orchestrator-authored: eligibility moves INTO the database transition
(the invalid state unrepresentable, per the quarry's run-fence), one
required takeover map, and a state-machine generative property over
transition sequences that observes durable facts. Separate bounded lane:
adopt the surviving flow suite out of `test-old/` (15/72 green against
fresh source, currently invisible to `bin/test`). B2 design input:
replace the `core-process-identities` allowlist with a computed rule.

**BOUNDARY 2026-07-27 EOD — the tower stands; B2 sealing is next.**
GREEN via bin/test (42/182/0 + the fcntl falsifier): N2 run model
(transactions-as-transitions, model-based state machine), B0 entry
(REPL-first, ten-second bound in-suite), B1 store (flock + genesis
repair + cross-process falsifiers), adopted flow suite, bridge
regressions. FOUR PLAN DOCS delivered and owner-reviewed under the
planning-agent workflow (plan → orchestrator fixes → seal → delegate):
b2-plan (branch-per-cluster ADOPTED, fork fix blocking+falsifier-first),
n3-plan (parser shed to sci reader, no lease clock, 4-line wake), n4-plan
(zero new attributes, mult-not-flow fanout, listener-on-commit-path
measured), test-selection-spec. ALL RULINGS in README ('Rulings
2026-07-27' ×3 batches — read every one before sealing).

**Quality-review-2 (`4bc02d33e`) triage — folds into the B2 wave:**
five reproduced blockers. Dissolve by design: lease expiry games (N3
deletes lease clocks entirely) and terminal-receipt reversion (receipt
writes become fenced transitions in the N3 contract). Fix in the wave:
failed d/release drops the flock while the connection lives
(store.clj:299 — keep the fence on failure, loudly), name-addressed
stop! kills replacement instances (cluster.clj:336 — stops become
instance-addressed), close tolerates a broken agent pointer silently
(run.cljc:419 — refuse, never omit). Plus the standing Gemini backlog
race and the issue-index lifecycle cleanup.

**B2 wave progress:** revision chunk SEALED (`78ddeb885` — store map
arg + open-branch! + fence-survives-failed-release; B0 store-dir/
ancestor-branch + instance-addressed stop!; N2 close
::agent-pointer-broken; falsifiers sealed for each) — implementation
lane b2-revisions-impl making it green. fork-roster-fix lane running
(falsifier-first, blocking registry work). Issue triage done
(`90a3cac60`+`865431fec`): 164 stale notes archived, 17 real open
issues mapped to rungs (see triage table in the lane summary /
index.md). Lease deletion + receipt fencing deferred to N3 sealing
(their consumer).

NEXT: **chunk 2 authoring — the six new B2 namespaces** (fresh-window
work; b2-plan §9 has seal-ready candidates + §7 the reconcile
algorithm quarried): seon.schema.edn, seon.reconcile (plan/reconcile!
— pure plan BEFORE transact, empty = NO transaction, :max-tx unchanged
= converged; reuses the N2 [:db.fn/call #'f] idiom), seon.config (+ THE
default manifest — honest dials = seon.flow's + on-core-error, never
State A's 39), seon.cluster.ancestor, seon.cluster.registry (UNBLOCKED — fork roster
fix landed 357ffc87/a6434ecee, falsifier-first, issue closed),
seon.cluster.export. Note for the ancestor contract: the BUILD half's
program-facts producer is N5's indexer — B2's ancestor seals with the
build population injected (schema facts now, program facts at N5) so
the fork mechanics don't wait. Then implementation lanes. Revision
chunk GREEN (5c95e259c); schema-EDN GREEN (b432bd07f — 103 declarations
converted, one gate live on both producers, honest generators
throughout). **IMMEDIATE WORKING EDGE (write-down before compaction, 2026-07-27
night).** The boot COMPOSITION (task #9) is sealed (f2fecffea: start!
threads the whole tower, REPL survives any later-layer failure, the
throw carries the degraded instance under :seon.boot/instance) with
seal-side EDN fixes at 35a938872 (boot.edn instance gains the three
optional tower keys; provenance.edn gains the :seon.db.process/id
identity plus :seon.db/index facets on the two refs). ACTIVATION FIXED (e02dcfd9b,
2026-07-27 night): the load-order coupling was real — ALL of
reconcile's registrations moved to seon/schema/reconcile.edn (one
authority; code register! block deleted), and provenance.edn's
`[:seon.db/ref {props}]` was invalid Malli — facet properties attach
to TYPES only, so the two refs use the one supported idiom
`[:and {:seon.db/index true} :seon.db/ref]` (the datahike bridge
recurses through :and heads). PROVEN: (require 'seon.cluster
'seon.config) clean; bin/test 87/359/7 where the 7 failures are
EXACTLY the sealed composition falsifiers awaiting implementation.
COMPOSITION GREEN (2d2655922, full gate 87/358/0 independently
re-verified; live probe: siblings share one store, last stop frees the
flock, failed tower leaves a working REPL, same name restarts clean).
Seal-side follow-ups b3b8d6a92 (honest crash walk, populate-ancestor!
contract, trust-list-inert + half-released-stop! issue evidence). The
GC experiment is ANSWERED (4a70900e1): retire ~50-60ms, reclaiming GC
~160-255ms, storage halved, survivors exact — retire casually at
teardown, coalesce full-store GC. N3 rulings recorded (858436ca8):
interrupted+adapt no auto-retry, my.run = complete/wait only, run-why
= trigger ref as tx-meta. ADMISSION SEALED (6caf0f5fe): the draft (884c0ca6e,
src/seon/sci/admit.clj + schema/admit.edn + admit_test.clj,
probe-grounded: realization calls the interrupt-fn at EVERY node;
IDeref never dereferenced; cycles unrepresentable by construction;
node budget primary) sealed with the activation seam KILLED AT THE
GATE — seon.schema.edn requiring-resolves a [:fn] predicate's owner
namespace before refusing (the computed rule; falsifier pair sealed,
edn_test_fixture.clj deliberately undiscovered). Four cap dials wired
into manifest/effective/entity + config/default.edn (12/64/4096/4096,
probe provenance). OPEN OWNER QUESTION (drafted choice stands):
projection failure = marker, never dev-panic — R41 tension flagged.
Full gate LOADS clean: 96/386, red = exactly the 11 admit
awaits-implementation stubs. sci on default classpath (858d6bf86).
TRIAGE DONE (2fef74665): 5 resolved archived, 10 open-current,
3 deferred, 2 N3-owned, one extracted
(cluster-stop-release-failure-becomes-unaddressable). TRUST DESIGN
DONE (0e71d42b5): core = first assertion precedes the ancestor
digest-seal tx, fail-closed :agent, literal list dies.
ADMIT GREEN (171a94e02, one file): suite 9/57/0 re-verified
independently; full gate 101/453/0 at the lane's run. The lane went
past the sealed suite (5 seeds × 200 trials + an idempotence property)
and fixed three self-found defects — depth-through-markers off-by-one,
node-budget overrun by marker cost, sci.lang require (caught BY the
new predicate-owner resolution). Documented tensions live in the
admit.clj docstring/risk notes: ::elided is scalar (capped? is the
honest signal), Instant normalizes to Date, host java.util.Map
projects opaque, record tag costs one width slot, test/prod node
accounting deliberately independent. TRUST DERIVATION LANDED (eda73bead, full gate 101/453/0
independently verified): admission derives core from d/history (first
assertion strictly precedes the unique ancestor seal tx), everything
fails closed :agent, the literal roster DELETED, ancestor population
reordered so canonical schema rows carry core provenance; issue
closed+archived (c2db0e61f). N3 PACKAGE 1 (pure derivation) DRAFTED (0f5f5607e: my.run,
seon.cluster.{reply,work,prompt}, five schema EDNs, sealed suites
with fixed seeds) and SEALED (6cf1d7317: :seon.db/trigger moved to
provenance.edn with the indexed :and wrap; work-namespace split,
interruption/next-work split, crashed-run answeredness all blessed as
drafted). Package-2 rulings sent: transact!/refusal accrete INTO
store.clj (new fns only); the flat :seon.error shape registers ONCE
in package 2. ALSO LANDED: bridge :and-unwrap fix at the choke point
(7012c595d, class-wide parity checks, issue archived);
dial-attributes shape fix + archive status fix (8372d64be);
bb-classpath fix BLOCKED on old-operator launchers (evidence
6a9b5a452, issue open — the seam dies with the operator). QUEUED
OWNER QUESTIONS: R41 vs marker-not-panic at admission; does the
[:maybe] ban extend to fn return contracts (next-work nil = idle,
read-advertisement)? PACKAGE 1 GREEN with one seal revision (0e1e9dc01): the fold's end is
its own FOURTH situation :close (ruled (b) — fold-vs-close visible in
the value, never a missing-key flag); the reply-level prose-vs-plan
verdict blessed (a per-form filter would silently eat trailing agent
forms). HOOK FIXED (8ac9a9c02, velocity incident closed): the hook no
longer loads retired seon.dev.config; correct selection proven on
three cases; bin/seon test changed still routes through the old
operator and dies with it. N5 PLAN REVIEWED (d500c8bfb): 19 findings
(6 confirmed defects incl. :seon.fn/schema-refs duplicate owner and
the namespace-row derivation, 6 stale-vs-today, 7 owner questions),
NOT ready to seal — revision lane running with the review as work
order. N3 PACKAGE 2 DRAFTED (997252038: seon.cluster.wake C1-C3 with both
prohibitions measured, seon.cluster.loop C9 with crash rows as
kill-positions-over-facts, seon.ai C10 with countable one-attempt +
credential as the one env read, store.clj transact!/refusal ACCRETION
below a banner, :seon.error registered once in error.edn; full gate
143/593 with 36 awaits-implementation red). SEAL RULINGS SENT (the
Opus agent applies as one bounded seal-revision commit, then
implements to green): (1) :seon.error/value re-point blessed
everywhere incl. sealed my.run/complete; (2) loop's turn/step may not
contract as bare [:map] — named shapes in loop.edn; (3) wake_test's
throwing-handler falsifier must assert fault DELIVERY (review-caught);
(4) wire pins: JDK java.net.http + org.clojure/data.json (landed
91d45256e), string JSON keys at the one :any boundary. The live
seon.ai call and the kill -9 process-boundary proof stay
ORCHESTRATOR-OWNED integration falsifiers, out of bin/test.
QUEUED MINE: delete seon.flow/database-proc + its testbed pins at a
lane-quiet point (n3-plan §4.1); the R41-vs-marker and [:maybe]-in-fn-
returns owner questions; task #3 MCP verify (needs Sean's restart).
N5 PLAN REVISED (aa6759fd3): all 19 review findings dispositioned —
12 fixed in place (schema-refs duplicate removed, acquisition
composes the landed projection/admission owners, ancestor population
matches today's provenance order, reset + process-kill proofs moved
IN-SUITE), 7 promoted to one "Owner decisions required before seal"
section (7 decisions, options + recommendation each) for a single
batch ruling when N5's rung opens. N3 PACKAGE 2 GREEN (seal revision 97e28e675 + implementation
93aa9d6de): FULL GATE 148/648/0 INDEPENDENTLY VERIFIED. wake's
offer!-false = closed-channel fault (honest, not manufactured);
author-written turn coverage drives [:open :call :resume :resume
:close] end to end with the evaluator injected as a qualified symbol
(the seon.sci.eval adoption plugs in untouched); my.run seal fixes
e167c6bc8 (honest close-only docstring, wrong-type guard on both
dispositions — review-caught ClassCastException). N3 REMAINING: the
ORCHESTRATOR-OWNED integration proofs (one live DeepSeek call proving
request/response shapes; a live flow graph driving step; the kill -9
child); the seon.sci.eval adoption rung; digest SHA-256 helper
triplicated (ancestor/config/loop — wants one owner, queue). N4 PLAN
REVIEWED (399e818b4): NOT ready to seal — revision lane running.
DATABASE-PROC DELETED (c7a93b075, -446 lines: proc + three private
helpers + four schema rows + pinning tests in the same commit per the
deletion doctrine; gate 147/640/0 INDEPENDENTLY VERIFIED; lost
incidental coverage named honestly in the commit — the surviving
owner is seon.cluster.loop + wake under their own suites).
THE LIVE DRIVE (tmp/n3-live-drive.clj, owner watching, four rounds):
(1) trigger REFUSED — the fixture-vs-live-boot class: message/eval/
form/agent families had no entity maps so canonical-database-
attributes never installed them live; FIXED 38ab48470 (four entity
maps + the non-vacuous class-killer: loop's declared write set ⊆
installable, plus a boot-derivation database test; gate 164/723/0).
(2) same refusal — the cluster branch pre-existed in the roster,
found never re-forked; drives now use a fresh root per run. (3) run
opened+claimed but stalled claimed-with-no-plan 120s: ai/complete
returned no-credential invisibly (DEEPSEEK_API_KEY lives in .env,
never in the tool shell — `set -o allexport; source .env` before
drives) and the error value DIED WITH THE TURN — durability gap
dispatched. (4) REAL TURN: claim 0.6s, plan frozen +2.7s (DeepSeek,
three forms), fold ran, receipts durable with full sci diagnostics,
run closed. Form 1 defn :done (Gauss!); forms 0/2 errored on two
base-ctx gaps — (in-ns): evaluator must evaluate IN my.agents.<id> by
construction + prompt says so; (println): sci *out*/*err* unbound in
the fork. Base-ctx fixes landed 020966ea4 (namespace-by-construction with ONE
derivation shared by prompt+eval; in-ns WORKS contained by the fork —
flagged, not forbidden; sci *out*/*err* captured as bounded
:seon.cluster.eval/output receipt evidence; credential nil guard;
model error closes the run WITH :seon.cluster.run/error in the same
tx; the class-killer caught that new attribute too; turn_test fixture
now installs canonical-database-attributes). **PHASE 1 COMPLETE
(97a0824b5, research/n3-live-proof-2026-07-27.md): first full live
turn — boot 1.28s, claim +0.65s, DeepSeek plan +2.68s, 4/4 receipts
:done, (my.run/complete "55"), run closed +2.41s, faults nil.**
PHASE 2 (kill -9 → interrupted+adapt) IN PREP: drill scripts
tmp/n3-crash-{child,verify}.clj; the Opus agent is correcting the
verify choreography (interruption requires an UNCLAIMED run — what
settles claimed-by-dead-pid, lease 60s, may interact with the
run-contract-hardening lane's lease-expiry fix). HARDENING LANDED (21215ce28: ::now threaded one-clock-per-pass,
held-run refuses ::lease-expired, takeover/terminal-preservation
acceptance; both issues archived). GAPS 1+3 LANDED (ba723b2d1:
start! recovers dead custody AT BOOT by fact — falsifier plants a
ten-minute future lease and boots in <10s; instance reports
:seon.boot/recovered-runs counts; prompt warning no longer shadowed —
excludes the run the agent pointer names; process-identity
<pid>-<start-millis> added, bare-pid recyclability named). Gate
171/777/0 verified. OWNER DIRECTION (evening): errors must surface
the exact problem — wire flow's error channel via fault-committer,
fault fact + explanation MESSAGE to the triggering agent in ONE tx
(eval errors stay receipt-only), escalation recipient as a config
dial, a `problems` derivation, malli instrumentation at dev boot
(currently NOWHERE enabled) with reload-reapply; model-call
resilience = primary/backup descriptor rows with instant failover on
error-class (backup's context says the primary failed and why),
backoff-without-secondary reconciled with the no-retry ruling.
IN FLIGHT (three): Opus research agent grounds the error wiring in
core.async.flow/sci/malli/quarry source
(error-handling-grounding-2026-07-27.md); sol model-failover-research
mines litellm-clj + again (model-failover-2026-07-27.md); the Opus
drafting agent lands the final pre-drill unit (GAP 2 loop settling,
process-identity agreement in handles+drill scripts, drill verify
reconciled with boot recovery). THEN the kill -9 drill runs.
N4 PLAN REVISED (ab2911caa): all 25 review findings dispositioned,
composed with the landed N3 owners, socket/reset/child-loss proofs
moved in-suite, EIGHT owner decisions consolidated — both N4 and N5
plans are now review-hardened and wait only on their owner-decision
batches. IN FLIGHT (two lanes): the Opus agent builds the seon.sci.eval
adoption — C7's evaluator owner, the LAST dependency before the N3
integration proof (never throws, deadline the only limit, admission
inside the armed boundary; proof = turn_test's injected symbol points
at the REAL evaluator, driving a real sci eval end to end with no
model call); digest-unify gives the triplicated SHA-256 helper one
owner (byte-identical proof required). NEXT SESSION'S SPINE, in order: (1) the N3
integration proof, orchestrator-owned — bin/repl or clojure -M:dev,
start! a cluster, install a live flow graph with the loop proc, drive
one REAL turn (one live DeepSeek call proving request/response
shapes), then kill -9 the child and prove interrupted+adapt; (2) the
seon.sci.eval adoption rung (the evaluator symbol injects into
turn/step untouched); (3) rule the N4/N5 owner-decision batches +
R41-vs-marker + [:maybe]-in-fn-returns with Sean; (4) digest SHA-256
helper triplication (ancestor/config/loop) wants one owner; (5) task
#3 MCP verify (needs Sean's restart). The Opus drafting agent
(SendMessage name a96513c593d3a6a83) holds the full N3 context. n5-plan 909394481 awaits deep review at its
rung; GC verdict recorded above. Its accepted design: process-local
store holder + refcount under ONE lock with running-instances;
stop! also releases branch connection, last instance releases the
store (stop!'s docstring needs that revision — seal owner's). Its
open risk: digest roots default ["src"] refuses on source-less
deployments (answer at the publish build). ALSO STANDING: stop!
docstring revision; the GC-cost experiment; issue
process-liveness-check-has-no-single-owner.

**B2 COMPLETE (2026-07-27 late): FULL GATE 85/345/0.** Every package
sealed AND green: config+reconcile (18a27e816, converged=zero-writes
proven by :max-tx), fork machinery (a35c95d0a — ancestor rename-at-end
builds, registry as the one branch-lifecycle owner, export with the
loud create+re-transact fallback proven by hand), provenance attrs,
THE defaults document with the computed concurrency default. Sealed
falsifiers cover fork+isolation+GC-survival in-suite against real
:file stores. NEXT UNIT: the boot COMPOSITION (task #9) — start!
threads resolve-bootstrap → open-store! → ancestor/ensure! →
ensure-cluster! → open-branch! → config/apply! → advertisement; B0
contract revision by the author, then a lane; falsifier = the full
tower via bin/repl <10s + near-instant second cluster + kill -9 reboot.
Then N3 from its reviewed plan (value-admission package first).
Open experiment owed: GC/retire cost over ten warm clusters — config→
facts (pure plan before transact; empty plan = NO transaction; :max-tx
unchanged = converged), schema-EDN loader + one admission gate,
branch-per-cluster (open-branch!, ancestor genesis, the ~15-line fork
roster fix WRITTEN FALSIFIER-FIRST, issue
datahike-branch-roster-read-modify-write-race), plus the B0/B1 author
revisions the verdict requires (store moves to the process root,
cluster-paths drops store-dir). Then implementation lanes (A/B: sol +
Opus both proven; effort medium default). Then N3 (value-admission gate
as its own small package first — owner-scoped: force + size-cap at the
choke point; allocation = O4 watermark), N4 behind it, N5 after B2.

WORKFLOW (proven today, keep the cadence): planning agent per rung →
orchestrator fixes plan → sealed contracts → implementation lane →
friction stops are usually AUTHOR defects (5 of 5 today) — fix the
defect, never relax the bar → quality-review lane at every rung
boundary (#2 in flight covering B0/B1) → Gemini hook reviews run
per-edit with 4 skills dynamically loaded. Owner wants rulings via
AskUserQuestion in PLAIN LANGUAGE with options. MCP eval_clj reaches
fresh instances after a client restart (bin/repl starts one).

Owner instruction, 2026-07-26: *"I feel like we are close to representing
everything witht he same primitves and composing them together but we aren't
there yet and be honest about what isn't done."*

This file is that honesty. [README.md](README.md) says what to do; this says
what we do not yet know. Nothing here is a task list — a row graduates out of
this file either into a plan step or into a `docs/seon/issues/` note, and a row
that stays vague is a row nobody has thought about hard enough yet.

Three categories, and the distinction matters:

- **UNDECIDED** — the owner must rule; we can state the trade but not resolve it.
- **UNKNOWN** — nobody has the evidence yet; there is a named experiment.
- **UNBUILT** — decided and understood, simply not done. These live in
  [README.md](README.md)'s steps and appear here only where the gap is bigger
  than the step admits.

## 1. UNDECIDED — needs an owner ruling

**Empty as of 2026-07-26 PM.** All four standing rulings landed the same day
— see README's "Rulings 2026-07-26 PM" section for the full text:

- **O14 dissolved**: web-render merges into the cluster JVM, so nothing
  rendered is stored — the commit-vs-derive debate was about *transport*,
  and same-memory serving removes the transport.
- **O4**: diagnostic + a process-heap watermark at heartbeat cadence; spikes
  remain the process boundary.
- **O2**: clusters never share a store; second open refuses via one `flock`
  assert.
- **Flow**: the non-adoption recommendation was REVERSED — adopted, Path A,
  `seon.flow` implements `flow.spi`, flow-monitor is the ops surface.

**Resolved 2026-07-26, recorded so it is not reopened.** Integrant is adopted
**narrowly and conditionally** (`bd8038419`): only when writer, driver and
web-render merge into one JVM, and only if that merge deletes the ~360 lines of
standalone lifecycle scaffolding it identifies. The operator's OS-process graph
stays separate — an OS process cannot be an `init-key` value. Shape: one root
system containing one nested Integrant system per cluster, so a single-cluster
reset halts only its own nested system. Strongest borrow from the archive: the
single derived `ig/assert-key :seon/component` Malli-validation choke point.
`suspend-key!`/`resume-key` are **rejected** until measurement proves a specific
restart resource is too slow. Biggest risk, and an acceptance condition rather
than a preference: a flat `refset` edge would make one cluster's halt traverse
shared resources and take down every cluster.

## 2. UNKNOWN — needs evidence, with the experiment named

**Answered 2026-07-26 (evidence in `research/`, kept here one line each):**
boot after the door deletion = **2,794 ms** mean artifact boot
(`boot-remeasure-2026-07-26.md`); the pod cut **does** lose needed coverage,
differentiated — 34 namespaces need fresh JVM invariants, 23 delete clean,
36 already covered (`pod-test-coverage-2026-07-26.md`); the three
`::calls` discard sites are **closed**, unknown targets fail toward
not-pure/not-`:compute` (`62bc86cb1`); the double-send experiment is
**unreachable until step 1's messaging binding exists** — the lifecycle
reply path commits message+receipt+closure atomically, so the reachable
window opens only with agent-authored sends; idempotency stays UNPROVEN and
is a step-1 acceptance item (`double-send-experiment-2026-07-26.md`). The
semaphore-replacement and three-turtles questions are now the flow
testbed's scenario matrix (`flow-testbed-2026-07-26.md`, in flight).

- **~~Does the submission-channel design replace the semaphore?~~ ANSWERED
  2026-07-26** (`3564882a3`): the semaphore is **deleted, not kept beside the
  channel** — `open!`/`available`/`permits` go; its queueing job becomes the
  channel's fixed buffer and its concurrency job becomes the launcher's slot
  count, both per-class config facts, with nothing outside the launcher able to
  acquire capacity. The launcher is one loop parked in `alts!!` over the three
  class channels. What remains UNKNOWN is only the measurement below. A
  bounded channel bounds the *queue* and parks puts (`async.clj:113-117`: "When
  full, puts will block/park"). It does **not** bound parallelism — the
  executor does. So `seon.sci.eval`'s semaphore is doing two jobs, and the
  replacement is (bounded channel = backpressure) + (bounded `:compute`
  executor = parallelism). Unverified: whether `:seon.eval/available`'s
  accounting survives that split, and what `newCachedThreadPool`'s removal does
  to the measured "a wedged eval degrades capacity by exactly one" property.
  **Experiment:** wedge N evals under the channel design and confirm capacity
  degrades by exactly N and a query still names the wedged step.
- **Agent messaging must be adapted, and the target shape is not settled.**
  Owner, 2026-07-26: *"we likely need to adapt agent messaing."* What is
  established: delivery is already pure derivation with no read/ack flag
  (`waking-inbound?`); the turn boundary is the take, so a message can never
  preempt a running eval; and the wake attribute must stay disjoint from
  attributes the wake path itself commits. What is **not** established: whether
  message identity derived from the sending receipt `(run, ordinal, epoch)` is
  sufficient to make delivery idempotent under re-execution, and what happens to
  a message whose sending form re-executes after a crash. Today's reply message
  takes a *freshly allocated* id, so re-execution can double-send. **Experiment:**
  kill a process after a send commits but before the run closes, and observe
  whether the recipient receives one message or two.
- **Whether the three turtles are genuinely one mechanism.** N cluster-writer
  flows, M agent drives, and every function call inside a turn are supposed to
  share one dispatch substrate. Partly verified: Datahike's writer is already a
  two-stage core.async pipeline, and on this JDK `go` expands to
  `(thread-call … :io)` (`async.clj:528-529`), so the database already rides
  `executor-for :io`. **Not** verified: that agent evals and function calls can
  join it without a second scheduler, and what the honest seam is. The
  scheduling design claims exactly one seam — agent interpreted code
  additionally carries `:interrupt-fn` + platform thread + permit, switched on by
  computed provenance. That claim has not been built or measured.
- **Whether workload can be derived soundly at all.** The derivation depends on
  `:seon.program.edge/calls`, which has three measured discard sites — a
  higher-order caller is a **silent false negative**, so `(map my-blocking-fn xs)`
  records only `clojure.core/map`. Until those close, any derived workload is
  wrong in the one direction that wedges a `:compute` thread. **Experiment:** fix
  the three sites, then assert that a call graph reaching a capability edge is
  never classified pure.
- **JVM boot after the door deletion.** The last pair is 10,293 → 3,886 ms
  (`-Xmx2g`, JDK 26.0.1, AOT 92.7% / AppCDS 7.3%). The residual was 63% three
  non-AOT namespaces including `seon.host.context` at ~900 ms — **which is now
  deleted**. Unmeasured. **Experiment:** re-run the boot breakdown at the same
  flags.
- **Whether the pod cut loses coverage we need.** It removes 98 CLJS test
  namespaces / 1,080 `deftest`s plus the CLJS branches of 24 `.cljc` namespaces.
  `bin/test-writer` must claim that ground, and nobody has enumerated which of
  those 1,080 assert a *surviving* mechanism versus a deleted one.

## 3. UNBUILT — understood, not done, and bigger than its step admits

- **An agent cannot act at all.** Its entire callable surface is
  `clojure.core`, `clojure.string`, and five `seon.agent.lifecycle` vars. No db,
  blob, fs, shell, web, messaging or LLM. Every demo, every load test and every
  proof of the design flows through the door that does not exist yet.
- **UPDATE 2026-07-26 PM: the gate is GREEN — 551 tests / 3,881 assertions,
  0 failures, 0 errors** (`tmp/plan-evidence/vector-order-test-writer-full-2026-07-26.log`).
  The registration fix, the frozen-prompt fixture fix, and the
  ordered-collection reshape (5 sets, 2 positions) landed same-day; the
  original note below is history.
- **The JVM gate is RESTORED and RED** *(2026-07-26 — see the 2026-07-26 gate evidence (state.md deleted 2026-07-27),
  regenerated from the retained log)*. **544 tests / 3,676 assertions, 3 failures,
  1 error.** All four are named and filed. Restoring it paid immediately: six
  stored attributes declaring ordered collections had been invisible for as long
  as the runner was broken. And a hypothesis worth testing before anything else —
  the one invalid registration
  ([[../../../seon/issues/sci-eval-evaluation-schema-does-not-resolve-its-predicate]])
  may be causing 3 of the 4 failures, which would put the suite one fix from
  green. Original note follows. `bin/test-writer` needs the compiled artifact. The
  freeze rebuilt it and **`writer` and `host` both reached ready** — live proof
  that the 58-line replacement `seon.host` main boots, which closes one of the
  owed proofs. The **pod** failed readiness on a release-digest mismatch
  (`this cluster was applied at release 596b6c1d; this artifact is dbdb10f7`,
  remedy `bin/seon cluster apply default`), which is expected: the pod is on the
  deletion list and the startgate is doing its job. Verify against the live tree for
  the suite count rather than trusting this bullet.
- **The wire is still on the agent path.** `seon.db.host/writer-session` opens a
  UDS session to a separate `writer` process, so every agent read and write
  crosses a socket — measured at 6-7 writer round-trips for one form containing
  one write. O1's co-location is the target, not the state.
- **Two blockers filed today that no step yet owns end to end.** A run opened
  before its plan commits is unrecoverable by either recovery query, in the
  window holding 78.5% of a turn
  ([[../../../seon/issues/run-is-unrecoverable-before-its-plan-commits]]); and
  agent-to-agent messages never wake anyone because the wake query requires
  `:origin :human`
  ([[../../../seon/issues/agent-messages-never-wake-the-jvm-driver]]).
- **The corpus round trip is broken in three places at once**: nothing writes
  `:seon.fn`/`:seon.ns`/`:seon.schema`, boot installs no corpus, and a `defn` in
  form 1 is invisible to form 2. Note the correction: `:load-fn` alone cannot
  resolve a bare same-namespace symbol, so this is not "add a `:load-fn`".

## 4. Where the primitives do not yet compose — the honest core of this file

The owner's read is that we are close to one set of primitives that compose.
That is true in four places and not yet true in three, and the three are worth
more attention than the four:

**Composing already.** A read is a pointer into a database value at a basis. A
change is a transaction whose report gives the next basis. Custody is CAS +
epoch + lease facts. Delivery, wake and render all derive from facts through one
predicate each, with no stored flag.

**Not yet composing:**

1. **Scheduling is not one mechanism — it is four expressions of one idea.** The
   eval bound is a `Semaphore`; the transaction bound is a Datahike queue size
   we never set; run admission and capability calls have **no** bound at all.
   The design says one bounded submission channel per workload class. Until that
   lands, "backpressure" is a property of one path and an absence in two.
2. **The corpus is a fact store without a resolver.** Code is committed as facts
   and nothing loads it back. So "code is data" is currently half a primitive —
   the write half. Until acquisition materializes a namespace from facts at a
   basis, the corpus composes with nothing.
3. **Containment has one hole that is not a policy choice.** A lazy value leaves
   the armed boundary unrealized and is realized later with no `:interrupt-fn`.
   Until realization happens inside the boundary at one choke point, "everything
   leaving is bounded" is aspiration, not a primitive.

The pattern in all three: **the write side of a primitive exists and the read
side does not.** Facts are committed but not resolved; work is scheduled but not
bounded; values are produced but not admitted. That is a more useful way to hold
the remaining work than a step list, and it is why the plan's step 1, 3 and 4
are ordered the way they are.

## 5. Things believed true that were wrong within a day — read before trusting a row

Recorded because the failure mode is systematic, not incidental:

- Six assumptions were tested in the previous session and **six were wrong**.
- Four of six defects in one plan row were already fixed at HEAD; the row was
  written from a document one day old.
- The plan's own `file:line` anchors went stale in a day because the plan's own
  work moved them. Prefer symbols.
- A "~1,160 zero-caller lines" deletion claim was false for two of its three
  units.
- Multi-agent messaging was assumed working by the ledger, the capability index
  and the plan. It is not, and it is a one-line query filter.

**So: re-grep a row's evidence before acting on it.** Every claim in this file
was verified on 2026-07-26 and may already be stale.
