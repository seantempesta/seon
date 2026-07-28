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
**PHASE 2 COMPLETE (dd8a483db, evidence in
n3-live-proof-2026-07-27.md): kill -9 mid-model-call → reboot →
start! recovered dead custody BY FACT ({:recovered-runs 1}, lease
still ~50s future) → the LOOP buried the orphan itself and drove a
NEW run to completion (real DeepSeek) in 3.1s of reboot → receipts
only for the new run, crashed run never re-planned, warning in the
new prompt. Interrupted+adapt proven over real facts.** Pre-drill
unit a6d426983 (GAP 2 settle-before-derive; ONE holder string
cluster/process-identity everywhere; drill = production wiring only).
Gate 172/785/0. RESEARCH IN: error-handling-grounding-2026-07-27.md
(e8fdd3518 — flow's error chan is sliding-100 SILENT DROP and start!
wires NO consumer, every core fault vanishes live; three incompatible
error-report shapes, ::flow/state must go through admit never pr-str;
classification needs NO predicate — evaluate never throws so channel
= classification; message-to = delivery already built; malli :report
does NOT prevent the bad call, +129-175ns cost, re-eval silently
strips instrumentation → explicit idempotent apply!, hot fns are
defn- so "public + schema" needs no list; six defects D1-D6 → issue
lane binayawwx filing) and model-failover-2026-07-27.md (b44bd1527,
litellm-clj + again mined). ERROR-WIRING SLICE 1 LANDED (3bd147643, gate 172/789/0): fault
entity schema + :seon.cluster.message/about + two fault dials + the
completeness-rule fix (requiredness reads :seon.config/effective —
reading the manifest computed a VACUOUS #{} because every manifest
entry is optional by design; optional dials were unrepresentable
before). The drafting agent stopped honestly with context exhausted
rather than half-drafting four packages. ORCHESTRATOR RULING: the
rename stands — ONE owner `seon.error`, fault.edn's entity merges
into the error family BEFORE anything references :seon.fault/*
(agent's recommendation accepted; a one-file change today, expensive
after four namespaces reference it). OWNER DIRECTION (late evening):
one normalization function for EVERY error class (kinds computed
from sites, never a hand list; standing totality property: every
committed error fact validates :seon.error/value) + PROJECTIONS PER
CONSUMER exactly like the render contract — ai steering prose
(stored at commit time; it IS the failover "you are the backup"
message and the explanation message content), log line (derived),
html at N4 via the ONE render contract (design the fact to permit,
build nothing). STEP 1 DRAFTED (5ac6cf4ef, agent a072b05ef16cbadcd — the exhausted
predecessor was a96513c593d3a6a83): seon.error normalizer (four
families detected STRUCTURALLY, fail-closed :seon.error/unclassified;
kind from deepest ex-data, no enumeration; signature excludes the
message so recurrence is countable; THE RECORDER NEVER PANICS —
orchestrator-ruled: the dial governs the failing site, the error
system is the loudness mechanism, "the fire alarm doesn't burn" —
present to owner), seon.render generic router (kinds computed from
:seon.render/* qualified-symbol keys; late requiring-resolve of the
VAR; wrapped {kind,output}; undiscovered fixture proves
resolve-loads-owner), notice carries reason + projection keys
(derived), fault.edn merged into error.edn (and its bare-ref
message/about defect caught+fixed in-draft). All seven taste calls
ruled as drafted. Gate 198/822 with exactly 33 stub errors.
STEP 1 GREEN (44435f07b + seal revisions 74a8efb08 + ui.md accretion
de59da156; gate 199/873/0 INDEPENDENTLY VERIFIED): normalizer +
router implemented; the agent read ACTUAL outputs and fixed two
things tests missed (root-cause prose over the wrapper word;
capped? = elided-or-truncated asserted both halves); the ai prose
sample ends "Nothing will retry this for you: read error err-7f21
and decide from the current facts." OWNER RULINGS (night): boot =
live agent host with ZERO token cost (loop armed-idle; agents are
ROWS not processes — root agent seeded free at boot; models called
only on real triggers); two-cluster live proof right after step 2;
CONCURRENT AGENTS PER CLUSTER is the target end state — "the
database is the intermediary... design it correctly with flow and it
should just work" — safe dial-bounded version first, measured;
one-user-per-workspace, maybe multiple. STEP 2 GREEN (e1f7262c6, 203/913/0 — one 1-in-3 FLAKE filed as
full-gate-has-a-one-in-three-flake-post-step-2): boot arms fault
fan-out + root agent + armed-idle loop (dials-derived handle, four
:seon.config.ai/* dials, zero model calls); refusal moved to
seon.error PURE (returns tx-data — the caller commits); D1/D3/D4
closed+archived. THREE LIVE FINDINGS: (1) THE ERROR STORM — delivery
IS a wake, so one broken code path self-fed 6 faults/1.5s; bounded by
recurrence signature (limit → one :recurring escalation → silence;
facts keep committing, a fact alone wakes nobody); (2) who-is-told is
COMPUTED from the fact (only a Throwable messages the attributed
agent; refusals = fact + escalation only — messaging refusals made a
test drive open runs to DISCUSS refusals); (3) the newly-wired error
channel immediately caught a real defect (loop's ::turn-report
resolved no channel — invisible exactly as long as nobody read the
channel; now rides ::flow/report). OWNER-LOOK ITEM: root's
escalation message opens a real run for root (delivery-as-wake) —
bounded limit+1 per signature per process; revisit when root gets a
real prompt. NAMED NOT DONE: flow/stop mid-turn loses that
transaction (kill row; recover-tx settles next boot; honest fix = a
completion the proc publishes). DISPATCHER DESIGN IN (d6212af43):
the double-token-spend race is the ONE thing the database cannot
see — process-local active-agent set fences money, everything else
is transitions; lands after the two-cluster measurement.
STEP 3 GREEN (b2fefe9d5, 218/954/0, TEN consecutive gate runs — the
flake was the agent's own armed-test equality racing a still-storming
producer; de-flake lesson: equality on a producing producer is a race
BY CONSTRUCTION, upper bounds are monotone-safe; issue archived).
seon.problems: pure over (db, live-processes), four families keyed BY
family (no :type), healthy = {}, signatures grouped (100 recurrences
= 1 problem), drives print it on failure (live-verified). Stale
triggers DEFERRED correctly (threshold = a number standing in for an
unobservable event; derivable when the loop publishes a pass
boundary). Three more recorder-survives-our-mistakes fixes:
attribution read off the FACT (request/db divergence suppressed a
real notice), dangling ref dropped not emitted, nil-limit = fact
committed nothing mailed. STEP 4 GREEN (6215ff0bc, 225/976/0 verified): seon.instrument —
computed selection (public + :malli/schema; hot walkers are defn- by
construction), :panic throws OUR flat violation value which composes
into the wired fault path (violation → error fact → message →
problems), :record instruments NOTHING (judgment flagged,
one-line-reversible), wired at bin/repl + drives NOT start! (a
cluster dial must not mutate process-global var roots — ruled
accepted). FOUR never-compiled contracts caught on first collect!
(catn duplicate key, bare-symbol :fn, loop/cluster missing
::flow/pid, turn-report demanding an id :open lacks) — the step's
argument made empirical. Nineteen released-connection teardown
violations = ONE issue
(instrumentation-surfaces-released-connection-contracts) with the
real bar: gate green WITH apply! active. Two runaway probe JVMs from
2026-07-26 (99% CPU × 29h) found+killed during owner process-audit.
STEP 5 SLICE 1 GREEN (e3bc8c31f, 232/1001/0 verified): transport-
phase evidence (request-transmitted?/response-started?/
output-observed? from the JDK's OWN exception taxonomy — connect-
class exceptions prove nothing was transmitted; everything else
counts as transmitted) + seon.ai/disposition, pure,
:failover-now|:backoff|:fail with the backoff set COMPUTED.
ORCHESTRATOR RULING: the agent's departure from my brief is ACCEPTED
— a plain ::timeout is :fail, only a CONNECT timeout fails over
(a transmitted request is ambiguously paid; re-calling it is exactly
what the no-retry ruling forbids; my brief was looser than the
research and the research wins). STEP-5 REMAINDER (the drafting
agent a072b05ef16cbadcd is context-EXHAUSTED after five green
steps — a FRESH agent takes this, in dependency order, spec in its
final report + research/model-failover-2026-07-27.md §named
sections): (1) descriptor rows growing the :seon.config.ai/* dials
(primary + optional backup, reconcile not duplicate); (2)
per-attempt receipt facts (new schema family); (3) failover
execution in the loop's :call branch consuming disposition, backup
context = error/ai-prose over the primary's COMMITTED fact (commit
before the backup call — ordering constraint); (4) backoff strategy
as again-style config facts, no-backup path only. **TASK #10 COMPLETE — STEP 5 DONE (cd9f41fb3 + 4f93d6587, gate
246/1095/0 VERIFIED).** Descriptor rows: backup = OVERRIDES over the
primary (:seon.config.ai.backup/model decides existence; partial
backup unrepresentable; loop/provider DELETED — same map, second
name; deliberate departure from research option 2, recorded in
config.edn). Attempt facts: one row per call, role by CONNECTION
(failover-from/delay-ms, no :primary stamp); primary's error fact
commits BEFORE the backup call and record-attempt! returning nil
after a refusal makes that total (a paid call whose reason couldn't
commit doesn't happen); backup context = ai-prose over the READ-BACK
fact (ordering proven by equality); backoff schedule EMPTY whenever
a backup exists (held by data). Judgment calls accepted: problems
not extended (successful failover isn't a problem); no :running
attempt row (a second interruption mechanism for a fact nothing
reads); the failover prose carries operator-aimed noise → the
projection-review experiment's first exhibit. FILED:
a-turns-model-work-can-outlive-its-own-run-lease (60s deadline = 60s
lease; honest fix is a claim-contract interface change, N2/N3-owned);
stop-may-leave-the-prepl-server-name-registered (unverified Gemini
flag, same-JVM same-name restart). Datahike scar: :db.type/long is
EXACTLY java.lang.Long — an Integer refuses the WHOLE transaction.
OWNER RENDERING RULINGS (night, binding on N4): root is PER-CLUSTER
(one root agent each; multi-cluster root someday); PORT the old root
interface — "it's really just different context blocks that return
:seon.render/ai and :seon.render/html" so BLOCKS are central to N4
and root/agent views are one mechanism; translate the old UI's
interaction (don't reinvent), FASTER + MORE RESPONSIVE as named
goals; canvas unified — all renders through the guarded sci door now
(the old infinite-loop special-casing comes free from N3), target =
agent picks ANY function returning hiccup, order is orchestrator's
pick (problems → block pages → canvas); LOOK: port the old design
language + named polish acceptance rows (blinking cursors, paste-
friendly input box, a chat display that's easy to follow). NOTE: the
first N4 drafting agent was STOPPED BY THE OWNER mid-draft — do not
resume it; a fresh N4 launch bakes these rulings in, pending owner
go. AGENT MESSAGING: the substrate is live-proven (escalation
messages open real runs); the missing 5% is the agent-facing
my.message hands — one small rung, queue with the gold order.
QUARRY GOLD INVENTORY IN (7f403bace: 155 files/75k lines censused,
36-row crosswalk): the remaining gold is the continuity/composition
layer — turn evidence + blobs, derived context, plans + memory,
collaboration, canvas/tools, schedules, Inspect integration;
pod/self-host/CLJS stays lead. OWNER GO + PERFORMANCE BAR (night): "no N=1 attempts. This shit has
to be fast. Like 60fps fast for very dynamic rendering" — the 16ms
frame budget under churn is a DESIGN INPUT measured by a committed
benchmark harness, never asserted; PORT the tailwind-CSS build
system from the quarry (standalone if it rode the dead CLJS build);
two named exercise goals prove the design: LIVE TOKEN COUNTS and
STREAMING TOKENS to the interface (exposes the seam: seon.ai is
one-shot — the streaming SSE path is a named seal-side revision
composing with the failover work, partials land on the no-history
churn attribute, counts derive from the stream). OWNER ARCHITECTURAL RULING (night, error rendering): consumers reach
an error's renderings ONLY through the one router — :seon.render/ai
on the unit, never a bespoke seon.error/ai-prose call site (ai-prose
demotes to the DEFAULT implementation a key points at). GENERIC
default renderers per output kind + SPECIALIZED renderers selected
WHERE THE UNIT IS BUILT from the fact's own attributes (computed,
never consumer-side conditionals) — first specialist: malli
validation failures render detailed problem identification from the
full explanation. Both in-flight lanes corrected; N4's block
contract names the generic+specialist selection as a reusable shape.
PROJECTION REVIEW IN (ead99eb98: verbatim outputs of every error
family + critique + before/after rewrite proposals in
research/projection-review-2026-07-28.md; five consumer-visible
issues — noisy failover context, unclassified transition prose, lost
instrumentation evidence, unstable sci object identities in
receipts, duplicate storm-limit messages; each fix is one
hot-reloadable projection defn once the owner approves the revision
list — OWNER CRITIQUE SESSION is the next step on this thread).
N4 PACKAGE 1 DRAFTED + SEALED (6dcda1ab9 + 4fa0c96f7; map in
research/n4-contracts-2026-07-27.md): block.edn family +
seon.render.{hiccup,block} + two sealed suites + bench harness +
bin/css. HEADLINE (measured): the old UI morphed the WHOLE PAGE —
admitting a 250-event page = 7.5ms p50 of the 16ms frame before
serialization; BLOCK-TARGETED morphs (interest/suppression rekeyed by
block) are the 60fps thesis. Generic+specialist = select +
:seon.render/selection ("the consumer never branches and the
specialist's name never leaves its producer"). Streaming MINED
(llm-adapters.md:545-563): wire-stream? separate from
reply-evaluation, BodyHandlers/ofLines branch on complete, partials
on the churn attribute, counts from the same fold; interactions
settled — response-started? observable at first chunk, mid-stream
failure = transmitted = no-retry unchanged. SEAL RULINGS: block
naming stands (ui.md accretes); durable html slot = qualified symbol
ONLY (kills the pr-str codec); top-level derived; JetBrains Mono gets
BUNDLED (silent fallback is a lie about the design); D6 Option B
(seon.ai reopens as the one prefix producer). Adoptions D1-D8
recorded in the contracts doc. FILED:
a-self-referential-schema-overflows-the-stack (schema owner's, not
N4's). SEQUENCING: problems html + anything touching
error.clj/problems.clj/loop.cljc WAITS for the error-system lane's
commit; kind declarations live in block.edn ONCE — reference never
re-declare; issues index regenerated by whoever finishes last;
package-2 deps.edn/config revisions (Datastar coords out of :host,
resources path) are ORCHESTRATOR-owned on the agent's signal.
UNIVERSALITY AUDIT IN (ca0b5aa5d): the CONTRACT is universal,
production use is not yet — three filed: prompt assembly bypasses
the router (converges with the context-blocks work — the prompt IS
an ai render of blocks), stderr presentations bypass the log kind,
program-graph render declarations name ABSENT functions (N5's).
The coverage shopping list (which units grow ai/html/log renders, in
order) is in the report — feeds N4 block pages directly.
ERROR LANE LANDED (1c7abb6a7 — projections through the render
contract + approved conciseness; N4's fence lifted, N4 is now
last-finisher for the issues index).
**THE OVERNIGHT PROGRAM (owner asleep, rulings recorded):** morning
goal = ALL of (browsable UI / subagents messaging / proofs),
sequenced by READINESS — if rendering logic isn't solid, HOLD the UI
milestone and advance the others; solidity outranks demos. TOKENS:
unlimited DeepSeek; Muse sparingly if DeepSeek struggles; UNLIMITED
LOCAL — Qwen 3.6 35B A3B named ("particularly good at agentic
workflows"). Guardrails: defaults (path-limited commits, no history
changes, deps.edn only the named Datastar/resources promotion, ui.md
only via sealed-review commits, never data/clusters/default or
ACME). RECURSIVE RENDERING (task #11, owner): pages are folds over
the entity graph — unit refs render as units, bounded
(depth/nodes/visited — entity graphs cycle); "the /data browser is
ESPECIALLY that" (the get-in drill = the purest case, joins N4's
page set). Task #12: the audit's coverage shopping list.
N4 PACKAGE 1 GREEN (306/1248 at its commit): expand was UNBOUNDED —
fan-out WITHOUT cycles OOM'd the JVM at 22 blocks (per-path visited
refuses the wrong thing); now bounded by the SAME admission caps
(node+depth separate budgets, depth-first so elisions are stable for
equality suppression). Fused-walk experiment: NO — the 7.5ms
admission was a bad predicate; reorder+index → 0.012ms p50 (670×);
"an expensive-looking stage is a reason to READ the stage." Block
thesis on surviving numbers: 287B/0.004ms block morph vs
82,893B/0.460ms whole-page (289×/115×). Problems page = one key +
two functions (live-processes REFUSES a default — #{} invents
problems, assume-alive hides them). CSS = semantic classes
(agent-authored html makes utility soup unreviewable). ui.md
accreted ×3. Font = filed LICENSING issue (release artifacts ship an
unlicensed woff2, weight mismatch — remedy in the note).
LOCAL QWEN LIVE (ee133634e+cd613e30d,
research/local-provider-2026-07-28.md): Qwen3.6-35B-A3B-4bit-DWQ via
MLX at 127.0.0.1:8090, 42.6 tok/s, real turn trigger-to-close 2.6s,
dummy LOCAL_LLM_API_KEY=LOCAL (no-auth provider admission = morning
decision). DEPS PROMOTED (b8601fabe): Datastar SDK + http-kit
adapter on :deps (http-kit rides the adapter's 2.9.0-beta2),
resources/ on :paths. Two config reds mid-flight = the my.message
lane's in-progress dial (theirs; gate-green is their bar).
CONTEXT-BLOCKS PLAN IN (b15d3c418: quarry findings, pre/post-N5
boundaries, prompt-router convergence, measured cache-gradient,
EIGHT batched morning decisions) — falsification review lane running
before any seal. ARCHITECTURE DOCS CURRENT (a237c51a2:
observability/ui/agent-runtime updated with tonight's settled
contracts, one durable law added, unsettled items correctly
excluded). IN FLIGHT (four): N4 PACKAGE 2 (SSE/web layer, block
pages, ref-following + /data, streaming exercises — local Qwen
preferred for repeated checks); my.message Opus agent;
context-blocks-review (falsification); test-design-review
(report-only phase — the goal's dissolution clause; constructions
implement after the morning read). MORNING BATCH accumulating:
context-blocks decisions (8, post-review), no-auth provider
admission, font licensing remedy, N4/N5 decision batches,
my.message's conversation-loop dial, R41-recorder carve-out
confirmation, projection-review outputs re-read. ORCHESTRATOR QUEUE (my successor
runs): review each return; two-cluster proof + failover live drive
(recipes in research/scripts/); context-blocks plan → independent
falsification → morning decision batch for the owner; the
released-connection family; gate verify after N4 lands. NEXT QUEUE: two-cluster proof (+
failover live drive rides it); released-connection family;
dispatcher lands post-measurement; my.message rung; then the gold
order (turn evidence + blobs, derived context, plans + memory,
collaboration, schedules, Inspect). THEN: two-cluster proof; projection-review experiment;
steps 3-5. Earlier step-1 note follows:
IN FLIGHT: the same agent applies authorized seal revisions
(:seon.config.fault/*→error dial rename across five files; admit
record {:optional true}; ui.md accretion drafted as a SEPARATE commit
for isolated review) then implements step 1 to full-gate 0/0.
OWNER DIRECTIONS (late night): FAIL LOUD ≠ FALL DOWN ruling recorded
in README (c174607ad — dev :panic halts the ACTIVITY, never the
tower/REPL/UI; dig-into-it is the point). ITERATE THE PROJECTIONS:
after step 1 greens, run a projection-review experiment — generate
real errors of every family, render every projection, put actual
outputs in front of the owner to critique and refine (hot-reloadable
defns behind symbol routing = free iteration). SIZE DISCIPLINE
confirmed: all fact data through the admit codec (bounded by
construction); stack traces NOT in the step-1 fact — D2's owner lands
a bounded frame projection, full traces go to blob storage with a ref
(three-tier rule); recurrence signature prevents crash-loop
duplication. My error-consumption surfaces: problems via REPL (task
#3 MCP restart unblocks live eval_clj), log-projection files, drives
print problems on failure, N4 html problems surface. The
orchestrator seals on its return. OWNER DIRECTION (night, routing):
the router's delivery substrate is COMMITTED FACTS + listen!
attribute interest (the wake mechanism generalized — subscribe to the
projection-key attribute set; any committed entity carrying one
routes; never a second channel, processes commit what they want
routed). Keys are the ROUTING authority (deterministic, no-kinds
rule); malli schema-matching (filter registered-schemas by
valid-candidate-value? — multiple matches always possible since open
shapes subsume) is a DIAGNOSTIC surface for inspector/steering, never
routing. Remaining order after step 1: (1) seon.error normalizer + totality property
+ ai/log projections + fault.edn→error family rename; (2)
seon.error/commit! + boot wiring as a cluster.clj revision (D4);
(3) seon.problems; (4) seon.instrument (near-mechanical from the
measurements); (5) seon.ai failover rows + disposition reducer +
ai-projection notice. D1/D3 land WITH the error owner that consumes
the discarded values, not before. I seal each on return.
OWNER DIRECTION (night, loved the projections concept): GENERALIZE —
any entity/map may carry projection keys (output-kind → FULLY
QUALIFIED SYMBOL naming the projection fn); one router resolves
(requiring-resolve, var-backed for hot reload — the proven
populate/evaluate/predicate-owner idiom) and applies. The render
contract's ai+html pair becomes the special case; new output kinds
(log, sms, metrics…) are accretion — add a key, write the fn, no
router change. Seal note: ui.md currently fixes exactly two
projections — admitting the open kind set is a deliberate contract
accretion to write into ui.md at seal; each kind names its consumer.
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
