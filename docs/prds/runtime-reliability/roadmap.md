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

**Current phase: unit 0 graduated; the database authority mesh replacement is
active.**
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

The authority-mesh cut is intentionally no longer dual-running. Commit
`8561ae64` removes the pod-local replica/feed, local Datahike constructors, and
their obsolete lifecycle/replica tests. The canonical `seon.db` facade is being
reduced in place to its asynchronous authority session; remaining synchronous
database-value callers are a breakage inventory, not compatibility obligations.

### 2026-07-16 scheduling override

[[../database-authority-mesh/roadmap]] is the current implementation and proof
ledger. Its current recovery plan first completes one authority-owned database
and package initialization boundary, then proves the existing Bun child over
its compiled package plus current whole namespace sections. Only after those
contracts settle does it migrate complete application behaviors, delete the
remaining local-connection/compiler/replay/rendering owners, restore every
maintained test gate, and run live and performance graduation. Entity/ref domain
modeling remains unchanged; ordinary database-value maps are request-scoped.
The current source is intentionally broken where old synchronous consumers and
tests still name removed local APIs. Those warnings are a dependency inventory,
not compatibility work and not permission to create versioned replacement
functions. Detailed order and evidence are in
[[../database-authority-mesh/research/system-recovery-graduation-plan-2026-07-16]].

The current finish-line order is the six-stage
[[../database-authority-mesh/roadmap#finish-line-execution-plan]]. It supersedes
the older unit-by-unit scheduling prose below wherever that prose conflicts:
close the authority surface; prove exact initialization and recovery; restore
the complete product path; measure the completed architecture; simplify only
measured bottlenecks; then finish the Bun-native and downstream packaging cut.
The older unit table remains a coverage ledger, not six independent runtime
tracks and not a requirement to preserve obsolete intermediate mechanisms.

### 2026-07-18 integrated overnight graduation ledger

[[research/overnight-integrated-graduation-plan-2026-07-18]] is the active
checkbox and evidence ledger for restoring the running system and graduating
real namespace-moving, namespace-targeted, restart-safe agents through Inspect
AI and the browser. It does not replace the ten-unit program ledger or unit 9's
final admission gate; it projects the currently dependency-ready path through
them. Its earliest unsettled contract is live Inspect execution through an
isolated branch and typed product database read. The last exact-artifact
generic-render cut committed its core error 234 milliseconds before the child
exit and the same agent recovered through a clean artifact. The real namespace
task now reaches the admitted scorer: its first three-sample battery passed two
of three with zero fabrication, while the miss and 13–23-turn cost trace to
unquoted Datalog repair loops rather than database transport. The next database
scenario is blocked by stale scorer-only database wrapper and operation-evidence
assumptions; [[research/live-inspect-contract-audit-2026-07-19]] records the
exact producer contract and ordered correction. A clarified retry exposed the
earlier runtime boundary: execution children inherited the pod's subscription
to every committed database value, so sibling transaction volume filled one
idle child's physical-session queue and closed its socket. Protocol version 11
now separates database acquisition from database-advanced delivery; the pod
retains delivery and execution children decline it. Real writer and CLJS
boundary tests pass. The first live retry sustained all three children without
delivery pressure, then exposed two independent gates: a valid 34-turn provider
proof exceeded an unrelated render display cap, and Inspect cancellation left
one execution child alive after its branch pod drained. The provider projection
is already structurally bounded and now remains complete; the existing process
containment issue owns the escaped child. Source proves detached Bun children
occupy separate sessions while normal pod drain never awaited them. The one
runtime inverse now awaits child exits, installs an IPC-disconnect backstop,
and selects vendored Bun's no-orphans parent-death cleanup in the pod's managed
environment. Focused CLJS and operator proof passes; retained-branch close and
abnormal parent-loss proof subsequently passed. Normal retained-branch close reaped two
execution children and released the branch while the shared writer stayed
ready. One real Anthropic-backed namespace sample also passed in 2:24 with
accuracy 1.0 and zero fabrication; the default persisted Meta-compatible
provider currently returns HTTP 402, so that external configuration cannot be
mistaken for a runtime regression. Isolated live TERM and KILL probes each
reaped the pod and its execution child within two seconds, allowed branch
deletion, and left the shared writer ready. The fixed namespace run now passes
all three concurrent epochs with accuracy
and `pass_at_3` 1.0, zero fabrication, and no infrastructure failure in 5:13.
The database scorer's stale database-value assumptions are therefore the next
ordered contract. The milestone scorer now consumes the ordinary database
value, checks eval transactions against its basis transaction, and reads the
actual stored pairs once through the typed product-evidence query; the
synthetic operation tree and fixtures are gone, with 70 focused tests passing
and 28 net lines removed. The fixed workflow passes in 48 seconds and a fresh
seed-3 generated workflow passes in 61 seconds, both with accuracy 1.0 and zero
fabrication. Reachability still owns the same stale assumptions independently
and is now the earliest Inspect contract. Its source now uses the ordinary
database value, rendered/eval transactions, successful calls, and later
database-derived prompt transitions; the synthetic operation decoder and
fixtures are deleted, with 114 combined focused tests passing. One live
namespace-discovery row passes in 41 seconds. Root orchestration then exposed
that `/agents/run` did not process-host an explicit inherited agent on a
non-autonomous branch; a direct `agent.runtime/resume!` immediately woke the
queued message. The one task boundary now resumes explicit durable agents
before message intake and before its timeout clock, with 22 web tests/87
assertions passing. A fresh root row is the next falsifier. The real browser/Datastar
journey is the dependency-ready parallel portfolio; the
complete live Inspect, browser, package, downstream,
multi-cluster, and modest-hardware matrix remains the final gate.

The rebuilt root-row falsifier opened root's turn immediately through
`/agents/run`, closing the explicit-agent hosting defect. It then exposed an
independent context-rendering bug: a partially assembled eval row without
`:seon.eval/ok?` derived `nil` for the boolean argument to
`seon.agent.ctx/cap-result`, and instrumentation correctly retired the
execution child. The derived value is now explicitly boolean and a focused
regression passes 8 tests/19 assertions. On the exact rebuilt artifact the
same request advanced through context rendering to the provider; the provider
then returned Anthropic HTTP 401 because the credential inherited after the
desktop restart is invalid. Root reachability therefore remains unscored for
an external credential reason, while the browser/Datastar journey is the next
dependency-ready local boundary.

The first current-source browser boundary passes. Creating namespace agents in
the web UI updated the root feed without reload. Two independent root tabs
observed one creation transaction, retained independent canvas/plan selection,
survived a clean supervised restart, and both received a later creation morph
after Datastar's reconnect backoff. Server-side root, agent, and database feeds
all emitted `datastar-patch-elements`. The remaining browser boundary is the
complete control/validation/read-back matrix, gzip remote-mode proof, shared
render computation, and slow-client isolation.

The deterministic namespace browser cut now also passes against the fused
default database. Root created `dirty-places-sniff` for
`:my.agents.browser-resident` with one initial message; the live root view
advanced from four to five agents and showed its running state without reload.
The JVM database value proved the exact namespace ref and one root-to-resident
message. A second form submission to the same namespace retained the same five
agents and immutable ID and committed exactly one second message. The resident
later changed from running to paused on its open page. DeepSeek did not follow
the trivial reply instruction and was paused after repeated turns, preserving
the distinction between green product mechanics and the still-red live Inspect
model-trajectory boundary.

The current-source complete local correctness checkpoint now passes CLJS 1,179
tests/5,254 assertions, JVM writer 222/1,836, operator 281/1,583, and offline
Inspect 521 tests with eight environment-gated skips. The first writer run
removed two stale v10 listener assumptions rather than restoring implicit
broadcasts. The first Inspect run removed one retired database wrapper fixture
and retained the solver's fail-closed model-transport admission.

The current recovery/browser boundary now distinguishes authored configuration
from runtime-loader failure. Selecting a missing authored canvas function is an
agent error with exact repair guidance, and `my.canvas/show!` refuses to persist
that selection; a function present in the database program but absent from the
prepared child remains a core fault. The same live drive found that an outer
instrumentation/orchestration wrapper discarded retained child-exit evidence,
preventing the recovery transaction. The turn boundary now preserves the
committed turn ID and nested process evidence. Interrupted transcript rows
derive the recovery ID, detail, and diagnostic blob through the existing eval
ref, explain discarded process-local values, and state that current functions,
schemas, and tests reload in the fresh child. Focused proof passes 80 tests/318
assertions; deliberate exact-artifact child-exit and repeated-crash live proof
is the next gate.

The first deliberate current-source exit now passes. Exact eval
`(js/process.exit 17)` exited child PID `40970` with status 17. The database
contains interrupted eval `g53gfroqzp2c`, interrupted turn `vekljag8n96v`,
crashed run `oqnfelffmex6`, and recovery `ce96f93hun3y` with the eval ref,
artifact digest, bounded process evidence, and diagnostic blob. The fresh
recovery run read the blob and avoided the crashing form; pod and writer stayed
ready. Datahike reverse pull from the eval returned the exact recovery fields
used by the transcript. The repeated identical pre-success crash/root-notice
live gate remains before resolving the owning issue.

The repeated-crash breaker now also passes current source. Deterministic agent
`five-facts-hear` crashed PID `85953`, opened exactly one recovery run, then
crashed replacement PID `85961` on identical source before any successful turn.
Both runs are closed crashed, both evals/turns are interrupted, two recovery
anchors retain independent evidence, no third run or task child exists, and root
received one database message naming recovery `qlme850pqh8g` and its diagnostic
blob. Pod and writer remained ready. The recovery-evidence issue is resolved;
the next product-level recovery gate is its Inspect AI scenario.

Grounding that native Inspect gate against current owners exposed two
fixture-only assumptions before a misleading live run. Function repair must
query the Datahike history database value to observe both source assertions;
the current value contains only the latest. The typed product reader now
accepts namespaced `:seon.db/history?`, derives history from its one acquired
database value, and returns the matching ordinary descriptor. Recovery facts
persist the failed PID/digest/eval/blob, while healthy replacement processes
remain transient in `seon.execution.host/processes`; the provisional scorer's
generic child IDs must therefore be replaced with those real facts. The
history seam passes 18 focused Python tests and the complete 1,186-test/
5,297-assertion CLJS gate. Parent-host process evidence was the next unsettled
Inspect contract.

That contract is now source-complete. A loopback-only operator read returns the
existing demanded `seon.execution.host/processes` value without polling child
event loops or persisting healthy samples. The recovery scorer now joins the
persisted eval ID, failed PID, execution digest, and diagnostic blob to one
ready current process for the same agent with a different PID and the same
artifact. Its native solver acquires one retained branch, drives both real pod
phases, validates each pod result as infrastructure-scorable, reads Datahike
history plus parent-host state, and releases the exact lease in `finally`.
The product slice passes 21 tests, and the complete offline Inspect checkpoint
passes 525 tests with eight intentional environment-gated skips. Exact live
execution is now the earliest Inspect falsifier; host pressure still prevents a
truthful artifact build.

The same native task now covers namespace-targeted launch without an ID-shaped
fixture. Each retained branch selects a fresh valid `my.inspect.n<suffix>`
namespace, root sends two real messages to that namespace, and one current
database query derives the unique nonterminated resident, its two root message
refs, and its earliest eval namespace by transaction. The scorer still checks
one resident, stable reuse, explicit routing, and starting in the requested
namespace. The expanded product slice passes 23 tests; the exact live namespace
and recovery rows share the same branch/pod/admission/log mechanism.

The next product row now drives cross-agent function reuse and repair through
that same mechanism. One namespace publishes an exact qualified function, a
different namespace calls it without redefining it, and a peer repairs the
original symbol and records its test from a fresh execution child. The scorer
joins `:seon.fn/source` history and transaction `:seon.db/user` facts with the
successful eval rows, `:seon.test/last-passed-at`, and the namespace's current
`:seon.fn/sym` facts. It therefore rejects a suffixed replacement without a
second code registry or model-narrated evidence. The product slice passes 25
tests. Its exact isolated-branch live row is the next Inspect falsifier; pod
restart/database read-back follows on the same lease boundary, and complete
Inspect, browser, downstream, package, multi-cluster, performance, and resource
proof remain the final graduation portfolio.

The first exact live repair row opened the isolated branch and found two
earlier runtime contracts. A delegated agent passed the unresolved Promise from
`seon.db/db` to `seon.db/pull`; Malli correctly rejected the input, but the
propagated error lost its invocation population and development crash policy
retired the child as a core fault. The execution adapter now re-establishes its
already-captured agent ID immediately around self-host evaluation, with 14
focused tests/76 assertions passing. Root also kept retrying for more than 40
turns until the request bound, and the first branch close retained closed intent
but returned a failed writer lifecycle response; the identical second close
converged. Exact live replay, bounded root termination, and one-call branch
release are now the ordered proof gate before the repair scorer itself can
graduate.

The corresponding complete offline Inspect checkpoint passes 531 tests with
eight intentional environment-gated skips in 21.25 seconds. Product and
cleanup failures now remain independently visible, and operator stderr retains
the writer response required to diagnose the next release reproduction. The
exact execution artifact cannot yet be rebuilt because the separately owned
Datahike reactive-read lane has coherent work in progress; no live result is
claimed from the stale artifact.

The development-tool reconnect defect is source-grounded in the maintained
Shadow runtime rather than Seon's database advertisement. Shadow commit
`615430b3` permanently stopped reconnecting after more than three websocket
errors, allowing a healthy Bun pod to remain HTTP-ready while disappearing
from `repl-runtimes`. Maintained commit `c98bf60f` keeps the existing
five-second retry bounded per attempt but removes the terminal retry count;
its Node gate passes 2 tests/6 assertions. Seon's existing MCP discovery
already re-resolves replacement client IDs, now covered by a focused regression
in the 18-test/57-assertion MCP gate. Exact pinned-dependency restart and live
watcher-outage/re-advertisement proof remain before resolving the issue. The
first exact-source restart and its one allowed `up` continuation both reached
the newly pinned CLJS dependency, then the host SIGKILLed Tailwind CSS with exit
137. The operator left the default processes cleanly absent. ACME's operator
also reported no owned process, so its old PID-1 Node process was correctly not
signaled. The focused source and MCP gates remain valid; live proof is recorded
as blocked by unrelated host pressure rather than misreported as graduated.

An adversarial timing pass then found that eventual Shadow recovery and MCP's
call deadline disagreed: Shadow waits five seconds before reconnecting while
MCP stopped after roughly two. The existing eval boundary now uses one bounded
6.5-second runtime-acquisition deadline for default and agent-targeted calls,
re-probes current advertisements every 200 milliseconds, fails ambiguity
immediately, and validates `nrepl-select` before publishing a cloned session.
Deterministic outage tests advance past 5,000 milliseconds and evaluate once on
the replacement runtime; failed selection closes the clone. The focused gate
passes 21 tests/67 assertions. Exact live watcher-outage proof remains pending
with the same host-pressure build blocker.

The stale-canvas recovery seam is now source-correct and live-safe. A persisted
selection of absent function `my.agents.canvas-recovery/mistyped` rendered one
bounded error card while the Datastar feed, Bun pod, and JVM writer remained
available. The execution boundary now distinguishes an absent current
`:seon.fn/sym`—an authored application error—from a current function that the
execution child failed to load—a core loader fault. The canvas projection adds
the exact selected symbol and tells the agent to define that qualified function
with a Malli schema returning Hiccup through `my.canvas/view`, or select an
existing function/literal Hiccup through `my.canvas/show!`. The urgent warning
derives existence from current database program facts rather than the pod's
JavaScript vars, so healthy child-hosted authored functions are not falsely
reported and the warning clears with the repair. Focused proof passes
`seon.execution-test` 31/132, `seon.agent.ctx.canvas-test` 10/39, and
`seon.warn-test` 8/44. The exact rebuild then proved that the complete web view
has its own selected-HTML projection: it preserved the safe card but initially
showed only the producer's generic absent-program message. That existing
projection now translates the same error fact into canvas-specific guidance;
focused `seon.execution.runtime-test` proof passes 14/74. Exact rebuilt browser
wording and repair/clear remain the next live canvas falsifier before the
complete control matrix resumes.

That exact rebuilt live falsifier now passes. The agent feed rendered the
qualified `my.agents.canvas-recovery/mistyped` selection with the full Malli,
Hiccup, `my.canvas/view`, and `my.canvas/show!` repair instruction twice (the
primary canvas plus its rail preview) while watcher, writer, and pod remained
ready. Calling the supported `my.canvas/clear!` operation retracted the stale
selection; the next complete feed contained no absent-function error and the
derived warning cause disappeared with the same database fact. The complete
button/input/select/toggle/validation/focus/read-back matrix is again the next
browser boundary.

That proof also exposed a presentation regression: the complete web-view path
had bypassed `seon.render.canvas/error-response` and leaked the diagnostic into
a red card. It now consumes the canonical canvas failure response again. The
human sees only the calm "Updating this canvas…" placeholder; the error
envelope and exact repair remain in the agent's canvas and urgent warning
context. Focused source proof passes; exact live repetition awaits a successful
host-pressure-cleared build before the control matrix resumes.

As of 2026-07-18, one JVM writer has passed the complete autonomous sibling
cluster lifecycle: fresh initialization, distinct database values and store
IDs, concurrent agents and gzip feeds, isolated writes, pod-only restart,
config-free reopen, and final database-reference release. The default
watcher/writer/pod remained unchanged throughout the sibling restart. The
complete ClojureScript gate passes 1,098 tests/4,880 assertions and the affected
writer selection passes 79/538. The ordered boundary is now the complete
correctness/browser journey and concurrent agent/failure load, followed by
resource measurement and only then the Bun-native HTTP/SSE transport cut.

The source-free production boundary now also passes its first complete real
agent and restart/read-back proof. Release application
`114dad143fc79caeee2d62815649ea9e4acfec68479c1d8a19cf3e482d314d7b`
was relocated outside the checkout and ran its JVM writer, Bun pod, and Bun
execution child without development tooling. Instrumentation accepted all
754 selected functions. Agent `common-parents-shout` evaluated `(+ 20 22)`;
the next immutable database value rendered `(+ 20 22) ⟹ 42`, and the agent
sent a user message grounded in that result. All seven transcript renders were
healthy. A clean package restart changed both process generations, and the
gzip debug feed read the committed `42` back. The resumed agent then executed
`(complete "The result is 42")` in one turn; `/agents/run` closed
`:completed` with that exact reply in 7.79 seconds. A complete before/after
inventory of the relocated package remained byte-identical. Commits `35cd07ac` and
`554946f5` close the two defects found by the drive: ordinary forms were
compiled in ClojureScript statement context, and paged transcript query
arguments retained lazy `partition-all` sequences. The next package gate is
concurrent multi-agent failure and reconnect proof. The first drive also
exposed a convergence smell: the model finished its generated one-step plan,
messaged the answer, and chose `wait` after 40.9 seconds before the direct
resumed completion took one turn.

The same boundary now passes concurrent execution and bounded crash recovery.
Two agents ran concurrently in separate Bun execution children and completed
in 7.72 and 7.90 seconds. Release application
`62dfd2e233dc03fde08bc762e4079209fab0534afde537dcb78b17bda18d5d2e`
then ran outside the checkout against a fresh database. The exact eval source
`(js/process.exit 1)` retired one execution child, received its one automatic
replacement, and retired that replacement; `/agents/run` returned
`:crashed` without a third execution while the Bun pod stayed responsive.
A later inbound message started a fresh child and completed
`(complete "replacement child recovered")` in one turn and 7.66 seconds.
The release inventory remained byte-identical and `bin/seon down` cleanly
drained both the Bun pod and JVM writer. The apparent shutdown pause was an
active root turn completing under the existing bounded-turn drain contract,
not a file-lock or containment deadlock. Commit `6fae602b` and the archived
crash-breaker issue own the implementation and exact live evidence. The next
ordered boundary is complete correctness plus browser/feed/reconnect proof.

Deterministic live configuration now also reconverges under the normally
instrumented Bun pod. Reconciliation passes each managed identity attribute as
one scalar Datahike query input inside one immutable database value and one
`execute-many` request; this follows maintained Datahike's explicit rule that
collection-bound keywords are not resolved in datom attribute position. The
focused state/startup selection passes 21 tests/78 assertions, and two
consecutive `bin/seon config apply config/system.edn` operations both returned
unchanged with zero transactions. The drive also isolated an independent
compiled `execute-many` predicate-query failure, recorded in the owning issue;
the bounded indexed reconciliation path does not depend on it.

The real browser gate now passes root interaction and reconnect. One tab posted
and displayed `browser reconnect complete`, remained open across a complete
supervised restart, then posted and displayed `same tab reconnected` without a
reload. Root, database, and debug feeds each returned an immediate complete
Datastar event over Bun's intentionally uncompressed loopback transport. A new
ordinary browser agent also proved birth, page rendering, execution-child work,
terminal reply, and root-driven termination. Exact prompt/reply evidence showed
that fresh agents had no executable syntax example; a second agent produced the
right forms but commented them all out. One comment-plus-form example in the
existing system text corrected that missing context without an output rewrite.
Two independent fresh agents then closed `:completed` in one turn at 10.72 and
9.24 seconds. Four subsequently launched agents occupied separate supervised
Bun execution children at the same time. Three ordinary tasks completed in one
turn at 10.84--12.30 seconds while a fourth hostile process-exit request was
refused and remained bounded by the 45-second caller deadline; none of the
three sibling runs or the Bun pod was disrupted. The immutable source-free
package separately proves the stronger failure case: two exact
`(js/process.exit 1)` executions retired two successive children, the breaker
prevented a third automatic attempt, and a later message completed in a fresh
child. Fresh-agent context now states this real process boundary directly
instead of implying a shared Bun process. Grown-transcript correctness is the
next gate, followed by exact 1/2/4-child resource measurement.

The grown-transcript gate now passes against a real generated database. Fifty
retained turns and 400 evals, each carrying 16,384-character source, output,
and result projections, reproduced the original Datahike result-weight failure
on the actual agent feed. Instrumentation showed the active blocker was a
redundant ordered full-history current-namespace query, not merely the paged
eval pulls. Current namespace now derives from the same bounded eval pages;
four-row pages are calibrated against Datahike's complete pull weight, and
`record-eval!` also bounds structured Malli error projections at their write
owner. The corrected acquisition made 57 bounded database calls with zero
failed members. After a clean supervised restart, the same grown database
served a 75,408-byte complete Datastar patch with no error card. Focused tests
pass 19 tests/77 assertions. A failed feed event remaining cached across hot
reload was isolated as an independent Datastar invalidation issue; it does not
change the now-green cold/restart grown-database boundary. Exact 1/2/4-child
resource measurement is next.

The complete maintained checkpoint on that exact source is green:
ClojureScript passes 1,135 tests/5,047 assertions, the JVM writer passes 219
tests/1,821 assertions, and the operator passes 271 tests/1,532 assertions,
all with zero failures or errors. The current artifact therefore enters
resource measurement with both the real grown feed and every maintained code
gate proven.

Source-free release
`4073c7fadf45c841c0dbf20622456509f1c762eb8a35c77bf4a334a6f8406b1e`
now passes the exact 1/2/4-child resource boundary from a relocated tree and an
external state directory. Its process tree contained the 512 MiB JVM writer,
Bun pod, and demanded Bun execution children, with no Shadow watcher, Clojure
development process, or producer-checkout runtime path. After the same explicit
settled-heap collection used by the baseline, writer plus pod retained 827.3
MiB physical footprint, or about 875 MiB including containment helpers, below
the 900 MiB hard limit. One child retained 231.5 MiB with a 310.7 MiB peak; two
retained 216.2 and 233.0 MiB with 328.6 and 341.1 MiB peaks. Four simultaneous
post-bootstrap children retained 174.1--222.3 MiB each with 310.5--375.0 MiB
peaks. Fixed runtime plus those four was about 1.61 GiB, or 1.66 GiB including
containment, below both the 1.8 GiB improvement target and 2.0 GiB hard limit.
Four ordinary agents independently completed exact replies under the same
package; a separate four-child delayed-work sample exercised the retained and
peak measurements. One thousand four-way loopback requests observed 0.29 ms
p50, 0.80 ms p95, 1.11 ms p99, and 1.80 ms maximum time to first byte.

The relocated package then restarted cleanly and served the previously
committed `four-b green` result through a complete Datastar patch. Its own
operator reverified the release inventory after runtime use, every child had
retired, and `down` drained the Bun pod and JVM writer cleanly. Generated state
remained outside the immutable package. Native `Bun.serve` intentionally keeps
loopback SSE uncompressed; remote configurable compression remains a separate
transport graduation item and is not silently represented as a gzip result.

The generalized-artifact source at commit `c11bd152` independently repeats the
default package boundary. Two clean builds are byte-for-byte identical with
application digest `f3df6eb22b51c3a40755eac7229b11cda41ec4311280406fadbe5ff072bc372f`.
The relocated package served root and data pages and complete configured gzip
feeds, restarted into new writer and pod generations, and shut down cleanly.
Its complete tree remained byte-identical to the untouched second build before
and after runtime. A separate operator defect was exposed: a later `status`
must currently restate launch-time environment overrides or its desired-spec
comparison falsely labels healthy processes degraded. The runtime readiness
probes themselves remained green; the durable issue owns the correction.
The downstream ACME boundary now also uses the one JVM authority. Launch data
separates the Shadow watcher owner from the Datahike writer owner: ACME owns
its `acme-client`/`acme-execution` watcher and Bun pod while pointing directly
at the default writer's process directory, request socket, and REPL port file.
Default and ACME were concurrently ready with one actual JVM writer. An ACME
restart replaced only its watcher and Bun pod while default process IDs stayed
unchanged. A later default writer restart left the ACME Bun pod alive; its
external dependency followed the replacement writer PID and both Datastar
feeds served complete patches. `bin/acme down` then retired only ACME's watcher
and Bun pod while default remained ready. Complete proof on the final source
passes 1,136 CLJS tests/5,053 assertions, 219 writer tests/1,821 assertions,
and 272 operator tests/1,537 assertions. Remote configurable compression is
now also implemented and live-proven: the one feed retains identity as its
default and selects a single Bun-native gzip stream only when configured and
accepted. A 27,185-byte root event used 2,535 compressed bytes, the same
connection promptly delivered its later heartbeat, and Chrome morphed the
compressed feed. Focused proof passes 15 tests/58 assertions, including
explicit `gzip;q=0` refusal. The remaining ordered gate is the complete final
source-free package matrix on this exact transport source.

Final release application
`98e4a83d3b2005a54ada97b443a28d68f2807cc79e3e3e2091a12c9add9ce1cd`
now closes that matrix. It ran from a second external, recursively read-only
directory with every mutable path under external state and no watcher, Shadow,
Clojure CLI, or producer-checkout runtime path. Identity and configured gzip
feeds both passed, including prompt same-stream heartbeat delivery and
`gzip;q=0`. A packaged Bun execution child completed `final immutable package
green` in one turn/one eval/6.14 seconds; a clean second restart rendered the
committed result back. Clean shutdown left writer and pod absent. The package
tree hash stayed exactly `f42552b7…` throughout. Final maintained gates pass
CLJS 1,137/5,060, writer 219/1,821, and operator 272/1,537. The active boundary
is now the complete program audit across the already-recorded browser,
reconnect, recovery, multi-cluster, ACME, resource, terminology, and package
evidence.

The final interaction audit reopened one recovery contract before browser
graduation. A persisted action namespace referenced a schema namespace without
requiring it. Warm eval order masked the missing edge; after restart, complete
program preparation failed before the same agent could evaluate the corrective
`ns` form. This violates the self-healing invariant: persisted authored code
must never remove the supervised repair door for persisted authored code.
[[../../seon/issues/persisted-program-error-prevents-agent-repair]] owns the
exact evidence and acceptance criteria. The ordered spine is now: retain the
trusted child compiler and exact source map after program-load failure, prove a
corrective form commits through the normal eval/program transaction, restart
into the corrected program, then resume the cross-namespace browser action
proof. No SCI, pod-side authored eval, second compiler, or alternate registry
is admissible.

Commit `d34cbc2e` now closes the repair-capability half of that boundary. In an
isolated real database, valid `my.broken/run` was followed by deliberately
invalid current namespace source and a complete clean restart. The fresh child
retained the same supervised eval door, committed a corrective `ns` and `defn`
in two successful evals, restarted cleanly again, and returned `:repaired`
through the ordinary shared-function action path. The trusted compiler, exact
source map, and normal program transaction remain the only mechanism. Direct
failing-namespace/form reporting remains open in the issue; it no longer blocks
the agent's ability to repair. The ordered spine returns to the cross-namespace
browser action proof.

That cross-namespace browser boundary now passes after two defects were fixed
at their existing owners. Commit `842d335f` removes the invented
`execute-many` history flag and supplies Datahike's ordinary history database
value instead. Commit `a8e845c6` carries the selected renderer's existing
`:seon.fn/read-attrs` through canvas acquisition into the one Datastar feed's
dependency set. After a complete clean restart, Chrome rendered
`my.interaction.view/view`, posted through
`my.interaction.actions/save!`, committed `reactive-1784413316743`, and the
already-open feed morphed to the exact saved value without reload or console
error. Focused canvas proof passes 9 tests/33 assertions and execution-runtime
proof passes 13/71. The ordered spine returns to the complete current-source
gate and package-evidence reconciliation; malformed-program recovery and the
shared cross-namespace reactive action are no longer open contracts.

The prevention side of malformed-program recovery is now explicit and tested.
Commit `cbb1632b` proves that unreadable source traverses the normal batch owner
as a failed eval containing the exact source and error but no program
transaction data. Successful declarations alone enter the atomic eval/program
authority write; runtime failures restore analyzer and schema state first.
Focused receipt proof passes 13 tests/56 assertions. The complete current-source
checkpoint is green: ClojureScript 1,140 tests/5,078 assertions, JVM writer
219/1,821, and operator 278/1,570, all with zero failures or errors. The next
ordered boundary is regenerating and auditing the final source-free package on
this exact source, then reconciling its restart/reconnect, ACME, multi-cluster,
and 1/2/4-child evidence against the graduation ledger.

The exact package canvas proof exposed a development fault-policy hole before
browser graduation. A selected render failure already classified
`:seon.error/kind :core-bug` was converted into an ordinary canvas error card,
so the configured `:seon.config/on-core-error :crash` policy never ran and the
execution child remained alive. [[../../seon/issues/core-selected-render-errors-bypass-crash-policy]]
owns the correction and exact evidence. The selected-call-to-Hiccup boundary
now records core failures through the existing `seon.error/record!` owner while
agent-authored failures remain ordinary values; focused proof passes 14 tests
and 75 assertions. The first exact package proved the fault record but exposed
that Hiccup conversion had left the operation's async configuration scope, so
the default `:gate` policy replaced the configured `:crash` policy. Conversion
now remains inside that existing scope; focused proof passes 15 tests and 77
assertions. The ordered gate is a second exact-package crash/host-replacement
proof followed by the remaining web-boundary audit, then the canvas browser
matrix. This fault-policy proof precedes performance work because a stable
process that silently renders a core invariant failure is not healthy.

That second exact package terminated the affected execution child as intended,
but exposed a missing evidence seam: the remote-authority cut had removed the
only `seon.error/set-db-hooks!` call, leaving the dying child to buffer its fault
only in memory. `seon.db` now reinstalls that single late-bound hook over the
ordinary authoritative transaction path; focused remote-database proof passes
18 tests and 84 assertions. The exact gate is repeated once more and requires
both process exit and a queryable `:seon.error/fault :core` datom before host
replacement is credited.

The exact package now proves that contract twice: each feed invocation spawned
a fresh execution child, persisted the core fault at transactions `536871417`
and `536871418`, and exited without taking down the pod. After restoring the
qualified authored canvas renderer, the package rendered `Canvas control
matrix` cleanly and retained the exact read-only tree digest `ff1ea1fc…`. The
subsequent boundary audit simplified the owner further: selected-call failure
classification and recording now live in `seon.execution/call-selected!`, so
canvas, prompt-block, and interactive-call consumers cannot downgrade a core
failure after it becomes protocol data. Renderer-specific policy code is
deleted. Focused selected-call proof passes 28 tests/108 assertions and
execution-runtime proof passes 13/71. The next exact package repeats the
persist-then-exit proof on this final seam, followed by the parent host-error
conversion audit and the canvas browser matrix.

Final exact package `e131a442…` repeats the simplified boundary successfully:
the impossible selected core renderer persisted at transaction `536871421`,
then its task child exited while the pod remained ready. Restoring the qualified
authored renderer produced the full canvas; the recursively read-only package
tree stayed exactly `9d5b083c…`, and normal shutdown was clean. The selected
canvas/prompt/interactive-call contract is therefore closed. The next crash
policy audit is only the top-level compiled composition function: distinguish
its own core throw from the expected report of a supervised child exit, then
resume the canvas browser matrix and complete exact-source gates.

That final distinction is now explicit. Top-level authored `my.*` failures stay
agent errors. A top-level compiled `seon.*` failure performs an error-only read
of the current database configuration, records through the authoritative error
transaction hook, and applies its crash policy; successful calls add no read or
latency. The parent host treats the resulting process exit as supervised
evidence rather than crashing the pod again. Focused execution proof passes 29
tests/114 assertions. The ordered spine returns to the full canvas browser
matrix, followed by exact-current CLJS/writer/operator gates.

The first canvas-browser read exposed a public API drift before interaction:
the state paragraph displayed `#object[Promise …]`. `my.canvas/state` still
claimed a synchronous map after its sole implementation dependency,
`seon.db/pull`, moved to the asynchronous JVM authority. The helper is now
explicitly `^:async` and awaits the remote pull; the maintained canvas skill
shows async renderers and awaited handler reads. Focused proof passes 6 tests
and 22 assertions. [[../../seon/issues/canvas-state-returned-a-promise-as-render-data]]
owns the remaining exact self-host load and browser control matrix.

The exact self-host load then exposed a classification bug rather than a
ClojureScript async limitation. Direct `cljs.js` and authored-program probes
both publish and invoke async functions correctly, but an absent runtime value
for a valid selected `my.*` function was being classified from the function's
namespace and downgraded to an agent fault despite originating in Seon's loader.
That impossible post-load state now explicitly records a core fault and follows
the configured development crash policy; ordinary exceptions thrown by the
authored function remain agent faults. Focused execution proof passes 30 tests
and 118 assertions. The ordered gate is exact persist-then-exit and recovery
proof for this authored-function case, then the browser control matrix.

Current-source release application
`0d8bc9c2ff2088de3103f951d1bd3f94f96d2c80cb4f4ccf6a035aaa9f96197b`
now passes the source-free runtime boundary from `/Users/sean/seon-release-9df21b23`
with all mutable data under `/Users/sean/seon-state-9df21b23`. Its process
inventory contained only the JVM writer and Bun pod until agent demand created
the Bun execution child; no watcher or development runtime was present. Root,
`/data`, and the gzip root feed passed, and Chrome rendered root without console
errors. Agent `funny-ideas-chew` evaluated `(+ 40 2)` to `42` and completed with
`source-free current release green: 42`; after a clean writer/pod restart,
Chrome read that exact committed result back. Normal `down` retired both
processes. The complete package-tree digest remained exactly
`2528ac810e8b77a6f5108b2f18df906a5ecedbac3cd07e7dc372f768aa016da3`
before and after runtime activity. This closes exact-current package
immutability/restart/read-back; remaining reconciliation is current ACME,
multi-cluster, and resource evidence rather than another package mechanism.

The current downstream/shared-writer boundary also passes after safely clearing
one stale pre-migration process record through normal `bin/acme down`. ACME
application `91252536…` ran beside the ready default cluster with its own
watcher and Bun pod and the default-owned JVM writer as its only external
dependency. Chrome rendered the ACME dashboard and downstream surfaces without
console errors. A clean ACME restart replaced only its watcher/pod; default
watcher PID 24043, writer PID 25509, and pod PID 34428 remained unchanged.
ACME retained its database/dashboard, and clean ACME shutdown left default
ready. [[../../seon/issues/acme-operator-migration-drift]] records the remaining
friction: `up` safely refused obsolete `wire-server` records until one normal
`down` removed them. The architecture boundary itself is green; current
resource evidence and the complete audit remain.

The exact-current package now repeats the resource boundary with a stricter
simultaneous sample. Four real `/agents/run` requests kept four distinct task
execution children alive beside the root execution child. All four committed
the exact reply `current resource sample 42`. Their macOS physical footprints
were 196.7, 198.7, 207.6, and 206.7 MiB; the root child was 170.5 MiB. After
settling, the JVM writer and Bun pod measured 553.2 and 223.4 MiB. The complete
writer, pod, root, and four-task-child workload therefore occupied about 1.72
GiB before the small containment helpers, while per-process RSS continued to
overstate pressure by counting shared mapped pages. This is stronger than the
required four-child sample and remains within the prior 1.8 GiB improvement
target and 2.0 GiB hard limit. Exact raw responses and `vmmap` summaries live
under the external state directory at
`tmp/load-current-1784414845`; they are evidence, not package content. The
remaining ordered boundary is the complete requirement audit and measured
database-hop/query-reuse and Datastar fanout work.

The downstream production overlay now passes the same immutable boundary.
ACME supplies separate Shadow mains for the Bun pod and Bun execution child,
so its source is compiled and dynamically reachable in both isolated runtime
roles. Release application `8f8fb5da…` loaded its selected Aero manifest with
the relative include graph intact, rendered its canvas and supporting surfaces
over gzip, ran a real agent through two ACME functions, restarted, and read the
committed results back. Its recursively read-only package hash remained exactly
`3e9775bd…`. Build independence is now also closed. SDK revision `cc093dcf…`
contains committed Seon build source, exact maintained Datahike source, the
patched Bun binary, and Babashka license with complete digest `cc1ebcdb…`.
Two pristine SDK extractions, each denied all reads beneath the producer
checkout by the host sandbox, produced byte-identical complete ACME packages
with application digest `8d5877b9…` and release-manifest SHA-256 `3db8fe1a…`.
Clean downstream development MCP is now closed as well. SDK revision
`245e96f5…`, running under producer-read denial, installed its frozen packages
with patched Bun, built and started its own watcher/JVM writer/Bun pod, then
returned `:sdk-writer/42` through `eval_clj` and `:sdk-pod/42` through
cluster-qualified `eval_cljs`. Normal operator shutdown retired all three
processes cleanly. Release metadata is now closed too: each package ships
manifest-bound source revisions, Bun/Datahike/Babashka license texts,
third-party notices, and a CycloneDX 1.6 inventory containing 36 npm and 81 JVM
components. Two full builds remain byte-identical at application digest
`ce1f0284…`, manifest SHA-256 `21af6e45…`, and SBOM SHA-256 `66dac763…`.
The next distribution boundary is the general downstream descriptor; runtime
immutability, reproducibility, clean build, MCP, and metadata no longer block
it.

The post-protocol-v11 release gate is also current. Application
`ae860bc4522ec10b21a6a9bf14cd55737598cbcd2c92c90819258bfd37b2ba6c`
from commit `be5bd11c` ran source-free with only its JVM writer and Bun pod,
created `nine-lands-tickle` through `POST /agents`, and rendered its
`release-persistence-proof` purpose through the root Datastar feed. A clean
writer/pod restart preserved and rerendered the same committed fact. Normal
shutdown left both processes absent, and the complete package-tree digest was
unchanged at
`8dbbefab82993fd712781ab9af5fd8f91e3fab54bedb7e8ed13cb120ebf67a47`
at every pre-start, running, post-restart, and post-shutdown sample. The default
development cluster was then restored. Current ACME and independent-cluster
restart proof follows below; architecture-level resource measurements remain,
while package immutability does not.

Current ACME startup then found a one-attempt convergence defect. After a
changed artifact publication, `reconcile-development!` tried to stop `writer`
from ACME's local process directory even though that writer is an external
dependency owned by default. The operation failed `containment-uncertain`; the
same second `up` succeeded because publication had already converged. Commit
`d15097fa` now schedules `rebuild-writer` only for a live writer owned by the
selected target. Focused proof passes 38 tests/100 assertions. A subsequent
ACME restart replaced only its watcher and pod, retained agent
`curly-lizards-shop`, and left default watcher PID `61610`, writer PID `61939`,
and pod PID `62040` unchanged and ready. Normal ACME shutdown retired only its
two owned processes. This closes current independent-cluster restart/isolation;
the provider-backed downstream agent journey remains coupled to the live
Inspect credential gate.

Execution-child containment is current rather than inferred. A root feed
demand created detached execution child PID `42029` beneath Bun pod PID
`62042`. Killing the actual Bun pod with `SIGKILL` removed both PIDs
immediately even though the child owned a distinct process group, directly
proving the vendored Bun no-orphans parent-death path. Status classified the
pod generation as drained while the writer and watcher remained ready;
ordinary `bin/seon up` recovered it and reported the prior
`unexpected-exit`. Focused execution-host proof passes 18 tests/83 assertions
and operator process proof passes 61/314. The earlier audit's proposed source
changes were already present: runtime drain awaits `execution.host/stop!`, IPC
disconnect invokes child shutdown, and only the pod receives
`BUN_FEATURE_FLAG_NO_ORPHANS=1`.

The first current architecture measurements are durable in
[[research/architecture-performance-current-2026-07-19]]. Direct JVM cached
query p50 is 0.015 ms and an uncached varying query is 0.162 ms; the complete
Bun→UDS→JVM→UDS→Bun equivalents are 0.979 ms and 1.063 ms. Eight real Bun
clients retained one JVM connection and identical index roots, with exactly
one miss owner and seven joined callers before all second reads hit cache.
Datastar 10/50/100-view waves each retained one subscription, one render, and
one serialization; render duration stayed 74–77 ms and every client received
identical bytes. A cold root render after child reclamation took 1.386 seconds.
`vmmap` measured 635.8 MiB writer, 276.4 MiB pod, and 166.8 MiB full execution
child physical footprints; stopping the child removed its PID completely. The
1.7 GiB JVM seen in development is the Shadow watcher/compiler, not the
packaged writer. Remaining measurements are transaction propagation,
pull/entity/index, 2/4-client waves, slow-client backpressure, and idle CPU.

The read comparison is now complete too: full-path p50 is 0.687 ms for pull,
1.156 ms for eager entity, and 0.913 ms for a 20-datom AVET page. Transaction
latency is the first material database cost: Bun p50 168.6 ms, real writer
pipeline without UDS 138.7 ms, and a bare direct one-datom Datahike transaction
82.2 ms. Exact writer phase timing attributes only 1.9 ms to preparation and
2.3 ms to finish; the durable Datahike future is 137.5 ms p50. Maintained
Datahike/Konserve source shows six history-preserving persistent indexes plus
commit and branch-head writes, with per-file and per-directory force in the
file backend. Safe matched root-fusion/diff-buffer experiments come before any
implementation. Sync, history, and commit-graph removal are not acceptable
optimizations.

The first matched fresh-database experiment is complete. Root fusion reduced
p50 from 48.08 ms to 19.42 ms, p95 from 72.36 ms to 24.79 ms, and file count
from 337 to 55 while cold reopen, history, and commit IDs remained correct.
Diff buffering alone reached 45.03 ms p50 and did not reduce file count; its
small additional gain when combined with fusion is not decision-grade. The
next transaction falsifier is the same comparison against a realistically
grown database before any production configuration or migration decision.

That grown falsifier now passes. Across mirrored run order and a 5,000-entity
history-preserving database, root fusion improved warmed transaction p50 by
about 11%, p95 by about 18%, and growth by 10–16%; file count fell 21%, stored
bytes rose 1.7%, and cold reconnect regressed by less than 1.5 ms. Current and
`as-of` values plus the identical commit ID survived cold reopen. The remaining
decision is whether Datahike can adopt the creation option for existing Seon
databases without a second database path or migration ambiguity.

Live Datastar slow-client isolation now passes. After a non-reading raw socket
forced Bun backpressure and six newest-event replacements across more than 1.1
MiB of serialized updates, two fast feeds received the next commit in the same
millisecond while the slow socket remained blocked. That update rendered once
in 71.98 ms and serialized once for all three sockets. Client abort returned
all view/subscription/pending counts to zero. Application buffering is one
newest event per blocked connection; automatic stale-client eviction is not a
current policy.

The next resource sample also separated steady state from child startup.
Writer CPU was 0.0% median over 60 seconds and pod CPU was 0.8% median; the one
14.0% writer sample aligned exactly with a demanded execution-child start.
Current charged physical footprints were 596.7 MiB writer, 274.6 MiB pod, and
177.1 MiB full execution child. macOS `vmmap` physical footprint is used instead
of unavailable Linux PSS, and the 1.7 GiB Shadow compiler remains explicitly
development-only. Event-loop delay and a natural idle-timeout sample remain.

The first fused default-cluster reset then exposed a stale startup consumer:
the environment-derived AI row committed successfully, but `seon.ai/sync!`
looked for the removed `:seon.db/ok?` wrapper and logged a false failure. It now
uses the current transaction-report-or-error contract already used by brand
seeding. Focused proof passes 11 tests/40 assertions; the archived issue records
the exact database evidence.

The provider blocker is now closed. A one-turn product-door request completed
through `deepseek-v4-pro` in 12.14 seconds with successful model-transport,
turn, eval, and database evidence. A coherent DeepSeek reset then logged the
fixed seed transaction as successful, and an ordinary supervised restart
preserved database-owned DeepSeek plus `:stream` while returning every process
descriptor to current. Exact-source Inspect still fails honestly: `:stream`
removed DeepSeek's fabricated result claims, but namespace discovery omitted
the required closing `complete`, and root orchestration invented work forbidden
by its contract. The retained logs and unchanged scorers make model trajectory,
not provider/runtime infrastructure, the earliest Inspect boundary.

The existing remote query surface now exposes its protocol-native historical
view, closing the only facade gap needed by coordinate-pinned startup birth.
LLM configuration and brand startup sync also use bounded coordinate-fenced
authority pulls rather than ambient replica values.
Root reconciliation and first ordinary-agent birth now share one
coordinate-pinned three-member authority acquisition. A fresh cluster commits
both complete births atomically through the existing generated-ID allocator;
restart and historical retraction are write-free, and no production birth path
receives a local Datahike connection. The next ordered boundary is moving the
remaining parent eval/schedule/test/authored-route consumers into the existing
per-agent execution child so global pod replay can be deleted.
Eval receipt start, terminal recording, fallback stamping, and settled-CAS
inspection are already session-native, removing the local connection from the
durable execution boundary before the broader eval preflight cut.
The child no longer hard-codes one prompt entrypoint. Its existing
symbol-plus-artifact-digest identity selects from a closed direct function map
compiled into the execution artifact, preserving one protocol while opening
the same trusted door for eval and coarse render owners.
Run ownership is always fenced through the authority when a run ID is present,
and an unreadable competing eval receipt cannot authorize a fallback write.
Autocomplete/export and the debug feed already consume the same coordinate-
pinned compiled child result; the obsolete synchronous AI composer is deleted.
Stale debug completions cannot install candidate catalogs, and raw AI
disclosures make no second child call. The remaining
optional prompt owners are closed at `2dc9b44a`, `0602b8a0`, and `93d8e0b0`.
The real turn caller is closed at `43645eaa`; derived auto-run rendering is
closed at `e5556524`. The same child result now carries the coordinate-pinned
system text through capture, retries, and provider delivery. Authored source
loading, compiled execution identity, the static prompt composition root, and
the namespace, transcript, plan, and canvas async owners are settled. Commits
`6ff02c0a` and `60d9582e` keep the
child-local selected-function capability lexical and append it only to compiled
prompt owners; authored functions and nested renderers receive ordinary
declared arguments. Commits `5347ea7d` and `2366590a` delete the four owners'
local prompt acquisition fallbacks and route their reads through the inherited
coordinate. The public
integration point remains
the stable asynchronous `seon.db` facade; Datahike APIs and values remain
JVM-internal.
Commit `551723fc`
moves recovery, generated-ID allocation, and resumable-agent acquisition onto
the settled authority session. Commit `6ccd25df` closes its post-boot
MCP-membership freshness regression with one coordinate-fenced database
interest over the shared resumable-agent query. Parent-owned deadlines,
terminal `proc.exited` evidence, immediate idle retirement, and a thin remote
database child artifact—not a heartbeat or RSS poller—form the measured
supervision/density path. The remaining client lifecycle migrates in parallel;
the session-open switch waits for its one atomic cut.

The database lifecycle work already integrated below remains a prerequisite and
evidence source, but its older restore/undo scheduling cards are not the active
refill queue while the authority-mesh goal is active. Likewise, the original
units 2–8 resume only through consumers of the settled asynchronous authority
surface; they may not extend the replica, transaction broadcast/replay, Node
transport, or synchronous local-DB interfaces that the mesh deletes. Any lower
section labelled “current portfolio”, “current dependency spine”, or
“compaction resume card” and dated before this override is retained historical
evidence, not scheduling authority.

### Program task ledger

This is the high-level program ledger. Each pending unit gets its own
`docs/prds/<chunk>/` folder before detailed research or implementation; its
roadmap then owns the exact files, dependency ledger, evidence, and commits.
Research may run in parallel across the independent domains named below, but
implementation follows the dependency edges and never creates competing
database, renderer, runtime, operator, or packaging mechanisms.

| Order | Unit | State | Depends on | Measurable exit |
|---|---|---|---|---|
| 0 | Current branch graduation | **COMPLETE** | none | Reconciled docs and successor PRDs; clean full pod/writer/operator/Inspect-offline gates; destructive default reset; live CLJ+CLJS MCP, browser, gzip SSE, database read-back, restart, and retained-result/query-budget proof; legacy lane evidence classified before cleanup. |
| 1 | `database-lifecycle-recovery` | **IMPLEMENTING** | 0 | Fresh, converged, config-free reopen, clean restart, crash recovery, canonical coordinates, as-of/fork/restore/undo, and multi-form transitions pass without replay or duplicate registries. |
| 2 | `reactive-render-units` | **CARVED + RE-GROUNDED** | 1 | Datahike owns one tests-first reactive-read and lazy immutable-result protocol; registered consumers converge at configured maximum latency, equal values suppress notification, and Datastar pages are one downstream consumer. |
| 3 | `database-browser` | **CARVED + GROUNDED** | 1, unit contract from 2 | Entity, refs, transactions, provenance, and history are navigable through bounded Datahike index cursors; closed details construct no expensive body; no global scan or second feed exists. |
| 4 | `root-workspace-sessions` | **CARVED + GROUNDED** | 2 | Root has its distinct system layout and concise context; ordinary-agent cards use the same derived focus; database-backed per-tab locations prove two tabs do not fight. |
| 5 | `agent-canvas-interaction` | **CARVED + GROUNDED** | 2, 4 | The one `my.canvas` path proves every control, validation/error result, focus/pin/clear transition, reactive update, and narrow/wide layout in a real browser. |
| 6 | `agent-runtime-correctness` | **CARVED + GROUNDED** | 1 | Raw model replies are preserved; every complete form is attempted; async contracts, plan authority/evidence, retries, errors-as-values, and measured process containment cannot wedge or fabricate agent evidence. |
| 7 | `inspect-autocomplete-evidence` | **CARVED + GROUNDED** | 0, 6 | Inspect source is content-pinned; preserved lane evidence is classified; the reviewed ACME tool-refinement results land through canonical `my.*` schemas/functions; large-planner/small-executor and simpler-model tool-use trials have reproducible task/scorer/provenance evidence. |
| 8 | `independent-downstream-distribution` | **CARVED + AUDITED** | 0, stable runtime/package contracts from 1 and 6 | A clean ACME checkout builds, customizes, starts, MCP-evaluates, restarts, and reads back from released Seon SDK/runtime/writer artifacts while the Seon source checkout is unavailable. |
| 9 | `local-performance-graduation` | **CARVED + GROUNDED; FINAL** | 1–8 | Destructive acceptance matrix and real-browser journey pass; explicit cold/warm latency, idle CPU, event-loop, heap/RSS, feed/render, and grown-database budgets are green; superseded worktrees/processes are safely retired. |

Unit 9 now also owns the source-grounded Bun runtime candidate in
[[../local-performance-graduation/research/bun-production-runtime-integration-audit-2026-07-15]].
Shadow 3.4.10 already declares Bun support; no second CLJS target is planned.
After units 1–8 and the no-source artifact contract settle, one exact artifact
runs under Node and Bun for semantic parity, then Bun may replace Node across
development execution and packaged operation if it wins the retained resource
budgets. Native `Bun.serve`, stream/static delivery, child processes, sockets,
JSC diagnostics, bounded execution cells, test artifact reuse, shared writer
families, on-demand pods, and `--smol` are separate measured cuts, not assumed
benefits or permission to create parallel runtime mechanisms.
The full-control removal plan is now carved as
[[../bun-native-runtime-simplification/roadmap]]. It is a unit-9 implementation
chunk after runtime/distribution identities settle, not permission to disturb
the current unit-1 lifecycle checkpoint. Its cutover begins only under an
explicit all-lane source and lifecycle freeze.
The approved connected database-authority implementation is carved in
[[../database-authority-mesh/roadmap]]. Its first spine corrects Datahike's exact
committed-value identity and adds Datahike-owned single-flight, then builds one
fair multi-database JVM authority with direct Bun child sessions, no Bun
replicas, and atomic removal of the transaction publisher/replay path. No agent
may implement a parallel cache, lease, coordinate, subscription, query protocol,
or broker; consumers wait for the settled capability and execute-many data.

Parallel work is deliberately bounded:

- while unit 1 implementation follows the database lifecycle dependency spine,
  research and handback review may independently advance units 2/3, 6/7, and 8
  in their exact `reference-code/` sources without changing the unsettled
  lifecycle contract;
- units 2 and 3 may share a read-only dependency audit, but unit 3 consumes the
  settled unit contract instead of inventing its own transition/feed path;
- units 4 and 6 may be implemented in parallel after their database/runtime
  prerequisites because they own separate UI-session and agent-loop domains;
- the ACME tool-refinement handback is integrated; its clean worktree remains
  only as an ignored-database evidence owner until archive/discard review, while
  unit 7 advances through the canonical shared-tree implementation; and
- unit 9 is the only final graduation gate and cannot be parallel-claimed from
  partial subsystem evidence.

### Execution cadence

At every continuation or compaction boundary, the top-level agent rereads this
ledger and the current chunk roadmap, keeps one lane advancing the earliest
unfinished dependency, and assigns other available agents coherent work from
later units only where it cannot create a competing mechanism or assume an
unsettled contract. Returned research is written into its owning PRD, reviewed
against source, and either integrated or converted into an explicit ordered
gap before that slot is reused.

The target steady state is one ordered spine plus three independent lanes, not
four agents editing the same milestone. The top level owns integration,
cross-boundary review, ledger maintenance, and live/destructive proof. Agent
slots own coherent path-bounded implementations, exact-source audits, or
independent evidence work. If fewer than three safe parallel tasks exist, do
not invent work: advance the spine until another contract becomes safe to
consume. If a safe documented task exists, leaving a slot idle is a cadence
failure.

The current dependency spine is:

1. finish unit 1 retained-head restore/crash-convergent destructive proof,
   completion-derived undo, and real three-form pod-death proof;
2. implement units 2 and 6 in parallel after their unit-1 prerequisites;
3. let unit 3 consume unit 2's settled unit/cursor contract while unit 4
   consumes its settled root/render contract;
4. finish unit 5 after units 2 and 4, unit 7 after unit 6 and the integrated
   ACME evidence requirements,
   and unit 8 after the runtime/package contracts from units 1 and 6 stabilize;
5. run unit 9 only after every prior unit has integrated behavioral and live
   proof, then perform authorized legacy cleanup.

During unit 1, the default parallel portfolio is lifecycle implementation at
the top level, one lifecycle audit/proof lane when needed, one Inspect/
autocomplete implementation lane, and one independent-downstream packaging lane.
When a lane finishes, refill it from the earliest dependency-ready unit rather
than expanding the current implementation into unrelated files.

### Current working portfolio and refill queue

The current 2026-07-15 portfolio is deliberately split between the restore
dependency spine and independent evidence consumers:

| Lane | Current boundary | Why it can run now | Refill when complete |
|---|---|---|---|
| Top-level integration | **Closed caller/readiness is complete; operator undo and deterministic crash cuts block destructive graduation** | `2e58e8d1` allocates the database completion id atomically and makes plan digest the unique adoption key; `1b08597e` migrates fresh operator intent to UUID and keys completion coordinates by the generated id. `1ec29778` keeps restore startup nonautonomous, and `0968a240` closes routed readiness, replica drift, attached refresh ordering, and disposable fault writes. Launch/client/web/database restore proof passes 62 tests/396 assertions; focused operator passes 29/171, full operator 220/1,263, and focused writer restore-admin plus registry 25/152. Live-proof audit found that undo remains a pure selector with no completion-selected CLI path and the coordinator has no deterministic invocation-local crash cut | Implement completion-selected undo through the existing coordinator, add derived-command proof cuts, then execute the source-frozen destructive matrix |
| Native secondary force | **COMPLETE through the selected Seon artifacts** | Both manifests bind Datahike `940810f5ebbf3eec2d135ea8e7821b2b7647194f`, Konserve `b5c99bc02a7175652a610324215288b78551801f`, Proximum `9846d3e79e1aee48474bc876d3d563d7137209c6`, Shadow CLJS `4e72595f57618f5c43388ad13d5136cd3bede566`, superv.async `3e6ed755f83634c9e9bbb58707f9446420d32ce9`, and partial-cps `1e119b03ea908ad925b98f9ba0a26371c65441e3` | Consume the frozen closure in destructive restore; do not reopen dependency publication absent a new falsifier |
| Restore inverse/planner | **Pure completion-derived selection is complete; effectful operator integration is missing** | `6351790a` derives the exact retained undo head and `9a60761f` closes the intervening pure-data config/schema source collision. Audit proved that `restore_state/plan!` and CLI dispatch still hardcode ordinary branch-name restore, so using the retained undo branch directly would bypass completion authority | Add completion-id plan/apply through the same artifact freeze, confirmation, and converge mechanism; then run undo after nominal restore |
| Inspect/agent evidence | **PATH identity and P0b evidence are closed; namespace/tool refinement is active** | `74530d90` makes process identity depend on the selected executable, and `901be2a9` records the admitted local-model P0b result. The external lane owns only its declared config/agent/Inspect paths and keeps ACME lifecycle untouched until the next source freeze | Review the bounded namespace/tool return after the retained-head source freeze; ACME rebuild remains downstream of default proof |

Current scheduling card:

1. **Ordered spine:** wire completion-selected undo through the existing
   coordinator → add invocation-local deterministic cuts at derived commands →
   use the frozen nonautonomous restore preparation, generated identity, and
   exact readiness read-back → destructive
   crash-convergent restore → completion-derived undo → real three-form
   pod-death proof with committed prefix and absent suffix.
2. **Parallel lane A:** independently review the frozen caller and prepare the
   exact default-cluster evidence collection; it may not edit source or run
   lifecycle commands during the destructive spine.
3. **Parallel lane B:** keep the external namespace/tool and Inspect evidence
   paths isolated, then review their committed return against canonical schemas
   and offline scorers after source freeze.
4. **Parallel lane C:** falsify the independent SIGINT publication-test failure
   against the existing containment owner; fix only a proved owner/fixture bug.
5. **Final admission:** unit 9 remains blocked on integrated units 1–8 and is
   the only place for destructive simultaneous-cluster, performance, and
   authorized legacy-worktree cleanup proof.

The ordered top-level sequence is therefore:

1. **Complete:** branch-qualified open/create/release/delete, retained pod-only
   lifecycle, quiesced restart/crash replacement, and generation-bound process
   evidence;
2. **Complete:** immutable restore intent, exact prepare/admit split, blob
   materialization, completion publication, later-head coordinate resolution,
   and closed launch transport for restore evidence;
3. **Source complete; live proof pending:** schema-before-policy ordering and the
   allocator-owned completion contract are complete. The replacement keeps the
   disposable restore pod nonautonomous and admission closed, serves exact
   non-executable readiness containing the returned completion and `C`, then
   lets only a fresh ordinary pod admit;
4. **Complete:** `be30f420` removes checkout-local dependency inputs and both
   manifest-v4 flavors prove the exact six-coordinate maintained closure plus
   normalized writer digest `3cbacfc0852807f0726c2b82ff7d2b673f68343c3affaaf126aa621453e45ceb`;
5. **Source complete; destructive proof next:** exact completion-coordinate, allocator-owned identity,
   contained-admin, confirmation, artifact-freeze, abort, and UUID/plan-digest
   contracts and nonautonomous caller readiness are integrated; pass
   restore/undo crash cuts and multi-form partial-commit proof;
6. graduate units 2 and 6 from bounded consumers into their complete acceptance
   matrices while starting unit 3/4 only from their settled contracts;
7. complete canvas, Inspect/autocomplete, and independent artifact consumption
   in dependency order; and
8. run unit 9's destructive, browser, performance, simultaneous-cluster, and
   authorized legacy-worktree cleanup matrix last.

The first ACME checkpoint after per-generation grace/trigger publication found
two real historical-shape boundaries. Pre-change managed records omitted the
new grace attribute, and pre-change terminal results omitted the new trigger.
The operator now derives the historical 2,500 ms control value only at record
read, keeps current writes strict, accepts the otherwise exact old terminal,
and preserves trigger absence in returned evidence. The actual stale ACME pod
then drained through `bin/acme down`; the full process namespace passes 34
tests/175 assertions. Source commit and clean restart evidence close this
boundary before the agentic-tool sample resumes.

On every lane return, the top-level agent first reviews source and proof,
updates the owning roadmap, and integrates or rejects the result. It then fills
the free slot with the earliest dependency-ready row above. The immediate
refills are destructive retained-head restore/crash proof from the frozen
public artifact, completion-derived undo through the same transition, and
then the earliest unit 2/6 consumer of the settled lifecycle contract.
Findings that do not block those exits are recorded
with evidence and acceptance criteria instead of becoming an unplanned detour.

The portfolio is a rolling queue, not a batch barrier. A lane may begin its
documented refill as soon as its owned paths are committed and the top-level
review finds no contract conflict; it need not wait for unrelated lanes. The
top level alone sequences shared integration, starts cross-boundary tests, and
runs live/destructive proof. This keeps agents productive without allowing
separately green slices to masquerade as an integrated milestone.

### Compaction resume card

Read this card after the program ledger whenever work resumes. It records the
next safe decisions, not a second architecture:

1. keep the persistent goal on the complete units 1–9 graduation outcome;
2. treat `c60e698e`, `defb8014`, and `8d938d56` as the settled descriptor,
   receipt/recovery, and runner boundaries; do not reopen them to house the
   next lifecycle or render work;
3. treat typed UDS, exact retained source/create intent, pod-owned native
   branch create/close, and both interruption inverses as settled. Close from a
   freshly ensured target head, never the creation coordinate, and never infer
   the source logical route from writer cluster;
4. treat diagnostic schema parity as settled by `56bf7818` and `1e1f0f8e`;
   do not reopen speculative registry relinking unless new evidence falsifies
   the canonical-fact repair;
5. treat public branch lifecycle, descriptor-driven MCP discovery, watcher-
   owned client publication, replica route retention, and the real default
   create/write/restart/close proof as settled through `bb6f10f7`; continue
   unit 1 through anchored containment, clean default restart/crash,
   restore/undo, and multi-form failure proof in that order; and
6. keep the remaining slots on reverse render-unit selection, Inspect task/
   scorer grounding, and downstream artifact work that consumes only settled
   contracts. Never turn a newly found smell into the spine unless it
   invalidates the named acceptance proof.

The persistent goal remains the whole unit 1–9 program. After every lane return
or local commit, reconcile the compact working plan with this section before
continuing. A deep investigation earns continued critical-path time only while
it blocks the named exit measure; otherwise preserve its evidence in the
owning issue/PRD and refill or resume the next dependency-ready boundary.

Immediate unit-0 queue:

1. **Complete:** the documentation hierarchy and practiced REPL-driven
   workflow are corrected. The 51-report localization census in
   [[research/research-localization-classification-2026-07-14]] assigns 23
   reports to one successor, retains 18 as graduation evidence, and keeps ten
   cross-owner reports as link-only shared input. All 23 reports now live under
   their one owner and every affected backlink is repaired.
2. **Complete:** this ledger is reconciled with the generated open-issue index;
   only findings with committed behavioral and live proof are archived.
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
   Local performance has the final admission, measurement, evidence, budget,
   destructive-matrix, and cleanup-gate audit.

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

The 2026-07-15 deterministic follow-up closes the retained path-level proof
gaps without changing production source or dependency aliases. Maintained
Datahike proof now covers planner and legacy broad connected joins,
disconnected Cartesian products, wide acyclic and cyclic recursive pull, and
post-exhaustion recovery: focused CLJ passes 28 tests/76 assertions and the
existing Node CLJS runner, now including the portable pull suite, passes
105/825. Seon's public CLJS boundary passes 1/11 for broad/Cartesian work
exhaustion plus normal query/pull recovery, and exact captured query/pull
budget replay passes 1/6. Local test-only Datahike descendant `eb3e2239`
contains the new dependency proof; the selected implementation remains
`417649` and neither the parent gitlink nor dependency aliases move in this
slice. Live pull recovery, repeated query/pull heap and RSS stabilization, and
arbitrary JavaScript/native allocation containment remain unit-9/process-
isolation evidence and are not claimed by these tests.

That paragraph records the bounded test-only slice at the time it ran. The
current publication descendant is now public at
`940810f5ebbf3eec2d135ea8e7821b2b7647194f`; it retains the dependency proof,
current upstream delete/GC fixes, guarded secondary force, and cold
`src-secondary` availability. Seon's root selection and artifact proof are the
active gate.

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
[[../inspect-autocomplete-evidence/research/inspect-autocomplete-lane-integration-audit-2026-07-14]]
confirms
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
assertions. That checkpoint's two live CLJS classpaths resolved maintained
Datahike `6f90b339…`, Konserve `df6818d4…`, superv.async `3e6ed755…`, and
partial-cps `1e119b03…`; both writer artifacts used the same root `:writer`
Datahike/Konserve basis. That dated checkpoint is superseded by root cutover
`be30f420`: current default and ACME manifest-v4 dependency vectors are
byte-equal at public Datahike `940810f5…`, Konserve `b5c99bc0…`, Proximum
`9846d3e7…`, and the other three maintained coordinates.

A later concurrent default restart and ACME start exposed one remaining shared
source-build boundary: both target-local lifecycle locks could enter the fixed
`writer-uber`, bootstrap, and CSS outputs at once, and ACME failed copying
`target/database-server-classes/seon/items.cljs`. The implementation in
[[research/source-artifact-build-concurrency-2026-07-14]] now brackets the
complete source artifact transaction, hashing, and flavor-manifest publication
with one checkout-derived kernel lock. Deterministic cross-target exclusion is
the local gate. Sequential same-head builds had produced distinct
timestamp-insensitive jar-content digests (`9c5a36d1…` default and `5247aa97…`
ACME). The same lock now owns a persisted source/dependency/CLI/JDK fingerprint
and verifies the canonical jar digest before reuse, so unchanged flavor builds
invoke `writer-uber` once and publish one writer identity. Deterministic reuse,
source/dependency/toolchain invalidation, and corrupt-jar recovery are green.
The live gate is also complete: concurrent default and ACME restarts admitted
only one `writer-uber`; ACME waited on the checkout lock and reused the verified
canonical jar. Both targets reached ready and published writer digest
`80054020…`. Their root and data gzip SSE feeds returned valid Datastar frames,
both reported healthy instrumentation (801 default, 808 ACME), and ACME's
normal root now renders its healthy downstream dashboard instead of installing
the deliberate broken-surface fixture. Current-source MCP calls resolved and
evaluated `default/root` and `acme/root` independently, and routed CLJ evals to
both writer processes. The resulting complete regression checkpoint passes
pod 1,330 tests/6,344 assertions, writer 68/388, and operator 103/606. Its only
first-pass operator error was a stale generated Claude copy of the maintained
Datastar skill; the canonical adapter projection repaired it and the repeated
gate is green.

Commit `8a6ebf60` closes the next observed readiness hole without restarting
either target. Watcher readiness now hashes the current flavor-owned client
output plus its Shadow runtime closure against the published client digest, so
post-publication hot-reload drift degrades status instead of advertising stale
artifact identity. Structured status exposes the non-secret PID start,
environment digest, and artifact digest needed for Inspect admission while
never returning environment values. Root review repeated the focused operator
gate at 25 tests/95 assertions. The later source-frozen rebuild completed for
both targets at normalized writer digest `3cbacfc0…`; their pages and gzip
feeds are healthy. Commit `74530d90` subsequently closed task-independent PATH
identity through process-specific selected-executable resolution, and
`901be2a9` records the admitted local-model P0b result.

Inspect source/run admission is content-pinned and required in native run
metadata; the synchronized offline environment passes 403 tests with eight
expected skips. Commit `f13ecc33` also closes the capability-score admission
blocker: static and ephemeral capability solvers reject timeout, `:error`, and
`:quiesced` after recording evidence but before parsing/scoring, the diagnostic
raw path is explicit, and the scorecard independently classifies all three.
This closes source-attribution and infrastructure-score contamination, not
lifecycle isolation, canonical autocomplete replay/scoring, or the model
graduation battery.

The agentic tool-refinement handback is reconciled in
[[../inspect-autocomplete-evidence/research/agentic-inspect-autocomplete-reconciliation-2026-07-14]].
Its commits and tracked run evidence are integrated or superseded; the clean
worktree remains only because its ignored database evidence lacks an accepted
archive/discard disposition. Unit 7 now owns the canonical shared-tree
autocomplete artifact, frozen Inspect suites, planning-arm comparison,
failure classification, and graduation evidence. Its measured namespace
weight must be reduced through one shared schema closure, never by hiding
complete relevant contracts.

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
3. produce a relocatable, devtools-free Bun runtime package with bootstrap,
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
- Dated diagnostic evidence from 2026-07-14 found a process-global Malli
  projection leak, duplicate async completion, and incomplete-run
  misclassification. The candidate-validation repair preserved the distinction
  between the live declaration candidate and activated immutable projection,
  validated first writes against that candidate, and classified a cold bundle
  load only when zero test namespaces started. The focused schema/database gate
  passed 56 tests/391 assertions and the original contaminating order passed 52
  tests/361 assertions. A later complete-process order re-exposed attachment-
  crossing activated projection state: the namespace cursor is correct, but a
  fresh connection recorded `:malli.core/invalid-schema` after its first defn
  and omitted the following call eval. Exact tracing showed the attachment
  projection was sound: the isolated diagnostic bootstrap lacked canonical
  schema facts, so function-contract publication correctly reconstructed an
  empty committed generation. `open-agent-conn!` now persists those facts;
  `tmp/test-cljs-20260715-054657-19889.log` passes the exact ordered falsifier
  (two tests/nine assertions), and the corrected issue is archived. The earlier
  1,305-test/6,175-assertion checkpoint remains historical evidence, not a
  current complete-green claim.

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

The explicit batch reply-boundary gap closed at `80475818`. One exact provider
string now reaches the content-addressed blob and parser unchanged; the former
result-claim rewrite and new telemetry writes are deleted. Parser position
admits only real complete forms and standalone `result/<id>` references, so
runtime-shaped narration remains byte-identical evidence without creating eval,
result, or message authority. Focused CLJS/JVM parser, turn-capture, context,
and docstring gates are green; the broader retained run has only the two
already-known current-namespace failures. Remaining narration presentation and
complete-form/async/process-death work belongs to the agent-runtime-correctness
unit rather than another reply filter.

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

- Receipt-native Datahike attributes are now derived from the canonical Malli
  forms, installed in Datahike's creation transaction, and validated before a
  reopened connection is published. Make accepted program/schema publication
  fail closed: if post-commit runtime
  publication cannot complete, stop admission and reconstruct from committed
  facts through the existing projection path.
- Freeze supervisor intent so config remains operation-scoped and optional.
  Prove native backend reopen without hidden manifest/runtime state.
- Make clean restart quiesce at turn boundaries; retain the existing
  idle-and-notify recovery transition only for unexpected interruption.
- Finish one canonical `{database-id, branch, commit-id, t}` coordinate through
  reads, receipts, feeds, turns, errors, caches, and bookmarks. Protocol,
  replica, turn/autocomplete, error/reproduction, and historical web-feed
  verticals are complete. Reconcile results now return the same point and the
  hot config projection cache keys plain decoded data by that point without
  retaining a database value. Writer-local native branch lifecycle is complete;
  branch-qualified runtime/operator ownership is next.
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
encapsulation, bounded publication, and lifecycle. At that checkpoint typed
administration, cold live transition proof, and a published artifact manifest
remained outstanding; the current manifest-v4 cutover above supersedes that
dated gap.

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
not suppressed as context. The Bun pod was calling Shadow's browser-only
reload-source filter after Bun had already loaded the files, so it selected no
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
- [[../reactive-render-units/research/web-responsiveness-audit-2026-07-13]]
- [[../reactive-render-units/research/live-feed-fix-review-2026-07-13]]
- [[../agent-runtime-correctness/research/agent-lifecycle-responsiveness-audit-2026-07-13]]
- [[research/seon-cli-lifecycle-audit-2026-07-13]]
- [[research/jvm-archive-boundary-2026-07-13]]
- [[research/jvm-server-cljs-client-storage-sync-2026-07-13]]
- [[../independent-downstream-distribution/research/client-distribution-and-server-rendering-boundary-2026-07-13]]
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
| JVM artifact | Source and uberjar use the same complete `:writer` basis with the maintained forks and one SLF4J provider. Manifest version 4 records the exact maintained dependency vector and normalized writer digest; default and ACME currently agree at `3cbacfc0…`. |
| Dependencies | The writer and writer-test closures are honest and narrow. Heavy paused-app dependencies still live in the base graph used by old JVM/tools, and CLJS/tool ownership is not yet fully separated. |
| Writer protocol | The semantic protocol, JVM writer/server, CLJS replica, and UDS transports are separated and the duplicate operations/helpers are deleted. A typed supervisor administration surface and cold process proof remain. |
| Database vocabulary | The protocol/backend/replica path is canonical, the managed leaf is `/db`, and the generic `store-inventory` API/context/tooling family is deleted. Runtime and developer skills are converged; downstream ACME still needs the proven vocabulary cut. |
| Database browser | The obsolete inventory surfaces are deleted. `/data` uses the canonical shared gzip feed, cheap shell, schema navigator, and bounded AEVT cursor pages. Entity/ref/transaction/history units remain. |
| Developer hooks | The direct Babashka hook is repository-contained before config/artifact access, runtime-independent, locally deterministic, and log-bounded under a cross-process lock. Automatic model review is deleted. The operator gate includes its Markdown/docstring checks. |
| Operator | The Babashka graph and thin launcher are built and focused-tested; default and ACME share one admitted artifact identity and serve healthy pages/feeds. Task-independent process identity is closed at `74530d90`; destructive restore remains the current live gate. |
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
   deleted. The explicit command now requires a ready compatible pod and calls
   the same live reconcile operation as boot without artifact rebuild or
   process replacement; converged applies write nothing. Remaining: freeze
   the resolved payload in the supervisor intent rather than selecting it by
   path at the live boundary.
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
   and the renderer cache atom are deleted. Receipt protocol attributes now
   derive their native signatures from the same canonical forms, enter fresh
   databases through Datahike `:initial-tx`, and fail connection publication on
   a missing or incompatible reopen; the raw schema and receipt seed transaction
   are deleted. Datahike source and live probes prove this genesis exception is
   necessary because transaction metadata cannot use schema first declared in
   its own transaction. Remaining: bring non-protocol compatible native
   additions through the complete candidate and bound historical projections
   by fingerprint. Agent program/schema transitions now
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
   Candidate preparation now proves exact live/contract fixed and variadic
   arity parity before either cold or delta Malli mutation. A mismatch aborts
   the complete candidate with structured live/schema profiles; focused proof
   covers cold all-or-nothing behavior, identity-preserving rejection of
   incomplete two-arity and variadic contracts, and three complete
   unstrument/reinstrument cycles (7 tests, 116 assertions).
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
11. **Primary coordinate verticals complete:** reads, receipts, replay/live
   feeds, turns, autocomplete exports, errors/reproduction, and historical web
   feeds carry the canonical `{database-id, branch, commit-id, t}` point.
   Historical HTTP selection is all-or-none, resolves the retained containing
   commit, keys frozen subscriptions by the full point, echoes the point in the
   response, and rejects partial selectors with 422 rather than falling live.
   The focused web proof passes 36 tests/180 assertions; the combined downstream
   proof passes 64/369. After a full rebuild, default head
   `54b5b7e7-51fb-3220-b079-81a81914d86f`/`:db`/
   `6a56e443-1025-554f-80b6-e81e9793e0ca`/`536870968` returned a gzip
   Datastar frame with the exact `Seon-Database-Coordinate` header, while a
   t-only request returned the structured 422. The residual reconcile result
   and config-view cache now use the same coordinate; focused state/config/
   envelope proof passes 48/235 and replica proof remains green at 17/93.
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
