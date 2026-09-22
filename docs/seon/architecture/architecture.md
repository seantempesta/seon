---
type: architecture
status: active
created: 2026-09-21
tags: [architecture, agent-platform]
---

# System architecture

Seon is a Clojure program whose declarations and durable activity are database
facts. One JVM hosts clusters; each cluster owns a Datahike branch, a connection,
shared plumbing and agent graphs. Agents evaluate against the program of their
cluster and propose changes through the same declaration authority that indexes
files. The target closes that loop with isolated candidates, explicit tested
acceptance and verified write-back to source.

The [implementation specifications](../../prds/agent-platform/plan/README.md)
own the cuts and acceptance proofs. This document explains their composition.
Existing seams are identified below; planned removals and guarantees are targets
until those proofs pass. A source citation is not fresh runtime evidence.

## 1. Values, facts and execution

A computation receives its database, projection, environment and execution inputs
as values. Derived objects belong to the value they derive from: validators on
the projection, custody on the connection, program identity on the admitted
execution. Running code does not reconstruct those inputs from a global registry.

Durable identities, messages, evaluations, errors, tasks and test results are
facts. Channels carry only replaceable notifications or work whose loss is
recoverable from facts. A buffer declares its loss semantics: sliding-1 retains
the latest wake; a dropping observation tap coalesces requests to reread current
facts; a fixed buffer applies backpressure. The channel is never the only copy
of something recovery must know.

Every execution has an admission bound and an observable completion. A timeout
reports that completion did not arrive. It does not prove the computation exited.
Unavailable evidence is an explicit result, never healthy silence. These rules
apply to boot, evaluation, tests, rendering, effects and cleanup.

## 2. Process, store and boot

The operator supplies a small bootstrap configuration: process root, store path,
REPL binding and logs. It opens the host REPL before store acquisition so boot
failure can be inspected. The process root owns the store under a lifetime lock;
Datahike's connection writer serializes transactions, but does not prevent another
process from opening the same store. A cluster receives one environment value
with explicit custody and the process's bounded compute and IO executors.

Boot constructs dependencies in order and publishes readiness at each owning
layer. Flow graphs then run independently. A ping reports responding procs; a
partial or empty response is not proof every expected proc is ready. Shared
plumbing is per cluster, while each agent owns its own Flow graph. Transforms
choose `:io` or `:compute` explicitly; blocking work must not occupy compute
threads. `:io` uses virtual threads where supported.

The running JVM owns ordinary program operations. External supervision remains
necessary to start a JVM, observe process exit, stop an unresponsive process and
recover a disposable root. A reset destroys database history, turns, tasks and
results as well as private in-memory objects; preserve evidence and reconstruction
inputs first. A schema change never needs a reset: every branch adopts it in place as it
opens (`seon.cluster/declaration-changes`), dropping the data behind a replaced or retired
attribute rather than migrating it (README §7 "Schema change and reset").

**Existing seams:** `src/seon/cluster/registry.clj:178` branch acquisition;
`reference-code/datahike/src/datahike/writer.cljc` connection writer;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:136-155`
ping; `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:82-116`
workloads. **Target owner:** [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md),
with [B2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md) lifecycle.
B1 first repairs MCP status/evaluation access. Host/JDK/Babashka startup is observed
before assigning a failure to the macOS upgrade; stale advertisements prove no OS
regression.

## 3. One declaration identity

Functions, tests, namespaces and schemas enter through canonical analysis and
transaction construction. File indexing and agent definitions share that owner.
Each analyzed declaration carries positive analysis evidence; absent call datoms
then means analyzed and calls nothing, rather than never analyzed.

B1's target `:seon.program/definition-digest` identifies the qualified declaration,
exact source, complete normalized resolver context and effective semantic metadata,
including acquisition and test metadata. Requires, aliases, refers, renames and
imports participate. Location, authorship, branch identity and unrelated file bytes
do not. Transitive dependencies and external input evidence remain separate.
A1, B2, B4, C1 and D1 consume this exact identity, without their own digest recipe.

The digest of the latest database row is not evidence of the callable installed in
a JVM or SCI context. Installation carries the identity of the actual callable
and its contract/dependencies. Missing identity is unknown and cannot justify
reuse. Namespace and macro changes can alter a declaration's meaning without
changing its body; publication and test selection retain those dependencies.

**Existing seams:** `src/seon/fn/analyzer.clj:505-568` namespace context;
`src/seon/program.cljc` exact replacement; `src/seon/id.clj` identity derivation.
**Target contract:** [B1 §2g](../../prds/agent-platform/plan/lane-b1-one-publication-path.md).

## 4. Publication from a transaction report

The target publication path is: capture admitted inputs, analyze changed files,
transact into an unpublished branch, use its report to identify affected callers,
lint them against the resulting program, record findings in a second transaction,
then move the published head under exclusive publication custody. Refusal leaves
the published program unchanged. It must also leave analyzer cache state usable
for the next request; clj-kondo can write its cache before reporting findings.

Explicit no-change source requests still observe their admitted inputs. Pathless
requests discover new/deleted inputs as well as stored ones. Missed-publication
recovery remains until its replacement covers those cases. Only unchanged
*adoption* is the simple comparison of published and adopted commit ids.
Changed schema references, resolver context, macros and external inputs affect
analysis, caller selection, validation or arming where their meaning requires it.

Publication produces facts. Development adoption copies admitted facts into the
selected cluster, reloads affected definitions, installs the matching program and
arms contracts before recording its adopted commit. Ordinary clusters keep their
older program. The publication report, loaded behavior and browser paint are
three separate observations; success at one cannot stand in for the others.

Datahike `force-branch!` accepts an expected current commit but is not a general
cross-process compare-and-swap. Its use requires exclusive store-write custody and
coherent connection refresh. A branch pointer does not make a concurrent whole-store
copy consistent. Rejected/overlapping analysis and stale-head publication are
proof gates before redundant scans or exclusion mechanisms disappear.

**Existing seams:** `reference-code/datahike/src/datahike/versioning.cljc:323-367`;
`reference-code/clj-kondo/src/clj_kondo/core.clj:242-270` cache processing.
**Target owner:** [B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md).

## 5. Projection and final write admission

A projection carries compiled schema nodes, contracts and read/write interpretation
for one program. A1 acquires it once, retains unaffected compiled nodes on change,
and passes it to validators, wrappers, SCI and rendering. A2/B1 establish carriage
on raw, materialized, temporal and forked values before reconstruction fallbacks
are removed. Temporal values use their origin's schema interpretation.

Compilation counts alone do not establish incremental cost. Copying a registry,
walking all contracts or reconstructing whole rows can still scale with the
program. Malli `fast-registry` copies into a HashMap; retaining compiled nodes does
not make that copy free. Measure visited, copied and compiled work separately.

Seon's prepared validator sees Datahike's final report: before/after values,
effective datoms and attempted writes. It validates complete final owning values,
including component owners found before and after child changes, and checks
surviving named referrers before deletion. Narrowing validation uses producer facts
for render properties and effective arity/default dependencies; the broad check
stays until the relation is complete. Changed callers and callees both participate.

Native heterogeneous storage is conditional on A2 proving comparator equality,
ordering, retraction, history and reconnect semantics. `:db.type/any` admission
alone is not that proof; the current codec remains until the fork supports the
required shapes. There is no mixed-tuple fallback for arbitrary values.

**Existing seams:** `src/seon/db.clj:1219` carriage, `:3586-3609` entity values,
`:4050-4121` final report; `reference-code/datahike/src/datahike/db/transaction.cljc:1206-1226`
validation callback; `reference-code/malli/src/malli/registry.cljc:17-30` registries.
**Owners:** [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md),
[A2](../../prds/agent-platform/plan/lane-a2-datahike-one-answer.md).
The [modeling guide](data-modeling-guide.md) explains the stored guarantees.

## 6. Contracts and independent execution

Every function, private included, has a complete input/output contract. The wrapper
uses validators acquired with the callable. Input failure prevents entry; output
failure refuses the returned value. Malli's report callback must not return into
execution on invalid input. Supplied defaults match declared argument names,
caller-supplied values win, and unavailable required values produce a named error.

An old cluster, a newly adopted cluster and an existing candidate must keep their
own definitions and contracts. A shared JVM Var changes when reloaded; capturing
its old root still does not freeze the Vars called indirectly by that root. SCI
copy-on-write isolates SCI Var updates, not arbitrary shared JVM behavior.
A1/B2 prove direct and indirect calls, contracts, macros, dynamic Vars and noncallable
defs across generations before removing context-specific resolution or isolation.
A typed fallback to loaded JVM behavior cannot certify an unexecuted proposal.

**Existing seams:** `reference-code/malli/src/malli/core.cljc:2193-2218`
instrumentation; `reference-code/sci/src/sci/impl/utils.cljc:362-379`
copy-on-write; `src/seon/sci/eval.clj:2218` base acquisition.
**Owners:** [A1](../../prds/agent-platform/plan/lane-a1-projection-carried.md)
and [B2](../../prds/agent-platform/plan/lane-b2-walk-flow-fork.md).
[Agent execution](agent-runtime.md) covers turns and private objects.

## 7. Errors and tasks

An error is a flat map with base location/time/operation members and declared domain
members. The producing function declares its exact error alternatives. A forwarding
boundary derives the precise union of its callees; a broad success schema must not
admit an undeclared error. Successful returns do not trigger classification against
the entire error population. Recording separately computes all satisfied error
schemas for the existing recurrence signature and preserves declared evidence.

The recorder retains one root per signature and occurrence components. A repeat
reads its occurrence and existence of prior evidence rather than summing every
occurrence. Evaluation-owned offending objects use the existing result handle and
shown text; after restart the object is unavailable. **Error payload durability is
an explicit decision gate:** B3's proposed shown-text/no-blob representation conflicts
with the complete-rendering-blob requirement. Do not delete durable payload evidence
until the owner settles that contract; do not serialize arbitrary live results as
a workaround. Error rendering composes the base and satisfied domain schemas.

The target `seon.task` is the one work family. Trigger resolves identity at the
writer; it does not start an agent. Start validates completion inputs, assigns the
agent and admits the first work. A repeat retains the existing task/agent; a new
actionable occurrence reaches a parked agent through the existing wake/message
owner. Namespace responsibility is many-to-many `:seon.ns/agents`, separate from
assignment and the REPL's current namespace.

Repair completion requires every declared test obligation and any subject-local
detector obligation. Missing test, subject or detector is unknown, never done.
Conversation completion is the accepted reply answering its triggering message.
A parent requires a nonempty complete child set. Settlement alone writes closure
and budget exhaustion. Parent/needs deletion cannot silently erase an obligation.
Candidate-local completion supplies evidence; explicit shared acceptance resolves
the shared repair task.

**Existing seams:** `src/seon/error.clj:200-225` signature; `:1567-1681` recording;
`src/seon/issue.clj:554-564` trigger identity, `:1028-1064` completion;
`reference-code/datahike/src/datahike/db/transaction.cljc:903-922` tx provenance.
**Target owner:** [B3](../../prds/agent-platform/plan/lane-b3-errors-tasks-dials.md).

## 8. One test request, explicit evidence

The target `seon.test/run` captures an immutable execution database/commit with its
matching projection/context and an explicit durable recording connection. Policies
select eligibility; they do not promise every selected body executes. Fixtures fork
the supplied execution commit, including D1's combined proposal, never implicitly
current-src. JVM resolution independently proves the loaded definitions match.

Selection uses conservative current calls, references, declared subjects and
schema/input dependencies. Past observed reach is diagnostic. An unobserved branch
of an earlier execution cannot exclude a required test. Each member compares against
its own previous green basis, own digest, reachable content and inputs. A later green
for another test proves nothing about an older untested change. Missing graph/input
evidence widens or refuses; disconnected/new and unfinished tests remain obligations.

Each completed member records actual counts, failure evidence, tested identity and
termination. Timeout or Future cancellation is not thread exit. A still-running body
blocks further execution and yields unfinished evidence under the request bound.
Namespace fixtures, child work, restoration and cross-request exclusion survive the
simplification. Branches do not isolate process globals or store-global blobs;
independent stores require coherent export, and unsafe host work retains isolation.

Boot/destructive hosts use the same run authority with their actual execution input.
A printed green tally is insufficient if deleting scratch storage deletes its only
facts. Retain the run and tested commit at the named authority before cleanup.
Recording refusal cannot return green.

**Existing seams:** `src/seon/test.clj:143-196` current timeout implementation;
`reference-code/clojure/src/clj/clojure/test.clj:725-737` fixtures;
`reference-code/datahike/src/datahike/versioning.cljc:550-585` copy limitation.
**Target owner:** [B4](../../prds/agent-platform/plan/lane-b4-tests-in-process.md).

## 9. Inclusive observation at the wrapper

C1 adds observation to the existing wrapper. It captures the installed symbol and
B1 definition digest and carries execution ownership into child work. Primitive
clock/counter operations record completed calls, including throws, without database
reads, hashing or transactions per call. Elapsed wall time is inclusive and includes
waits. Static call totals cannot be subtracted to infer self time.

Existing completion/settlement events hand immutable cumulative observations to the
writer. Live `LongAdder` reads are approximate; independent counters do not form an
atomic tuple. Exact member observations require that execution and its children to
quiesce. Never drain active counters with `sumThenReset`. Refusal keeps cumulative
observations available; repeated or older submissions cannot replace newer evidence.
Origins distinguish process restart, inherited branch facts and new work. Compatible
cumulative observations yield interval count/total; maximum remains lifetime maximum.

Open/background work uses an existing completion or schedule with a declared bound.
Missing/unattributed evidence is explicit; crashes lose uncommitted samples. Profiling
failure does not invalidate turn settlement. Automatic findings need a meaningful
evidence predicate and positive completion condition. No self-time ranking, invented
window threshold or top-N task policy follows from inclusive totals.

**Target owner and measured proof gates:**
[C1](../../prds/agent-platform/plan/lane-c1-wrapper-profiling.md), sharing A1's wrapper,
B4's member observation and B3's task writer.

## 10. Explicit candidate acceptance and source write-back

A candidate records its immutable fork basis and runs only its assigned task.
Inherited agents, open turns and schedules must not execute accidentally. Its
branch/context/custody and cluster-qualified message address remain explicit.
Branch creation alone is not this lifecycle proof.

An explicit merge captures basis B, candidate C and shared H. Canonical net changes
compare B1 definition digests. Equal final content is unchanged; competing changes
to one identity become a structural conflict task assigned to root. Complete rows
normalize through the program owner; branch-local numeric ids cannot cross as if
they were identities.

Scratch starts from H and admits the proposal through Seon's final writer validator.
Malformed schemas, invalid refs, unresolved surviving callers and missing coverage
refuse. B4 runs the combined program's current reaching tests union the task's explicit
tests. Every required member needs valid executed/reused green evidence. The final
writer accepts only the tested H; a moved head requires fresh combined analysis and
proof. Datahike merge must carry both expected basis and Seon's validator through its
existing writer, preserving required parent lineage. Current `merge!` alone supplies
neither guarantee. Preserve tested commits and run facts through scratch retirement.

Write-back consumes the accepted delta across functions, tests, schemas and namespaces.
It stages complete files in an isolated checkout from the expected source commit,
using existing digest-fenced UTF-8 splices. B1's canonical file analyzer checks resolver
context, intended deletions, unchanged neighbors and definition digests. Compare exact
source bytes separately. The proposed callable must load and execute through B4's
existing isolated host; fallback behavior is not proof. Controlled integration then
publishes/adopts the complete loadable set without exposing partial file edits.

Interrupted export resumes from staged bytes, commits and publication facts. Unexpected
checkout bytes refuse; no unrelated edit is restored. Automatic path-limited commit
after those proofs is D1's recommendation, with extra platform proof or human review
remaining owner policy choices. Explicit acceptance never implies an automatic push.

**Existing seams:** `reference-code/datahike/src/datahike/versioning.cljc:734-748`;
`reference-code/datahike/src/datahike/writing.cljc:860-889`; `src/seon/edit.clj:254-316`;
`src/seon/fs/jvm.clj:593-712`. **Target owner:**
[D1](../../prds/agent-platform/plan/lane-d1-isolation-merge-writeback.md).
