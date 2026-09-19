---
type: research
status: proposed
created: 2026-09-19
tags: [research, database, schema, agents, testing, rendering]
---

# Namespace agents: data guarantees before parallel self-repair

The owner wants several agents able to care for the same namespace, with
reusable data-driven context for repairs, schema improvements, tests, error
response and conversation. The implementation objective is a live agent
producing a tested program change, safely integrating it into a cluster,
then reproducing it in source files under a stronger gate. This is a design
and audit, not a claim that that integration path is implemented.

The ordered execution schedule lives in [the roadmap](../plan/README.md).
This note owns the design, evidence and acceptance conditions.

## Current evidence and limits

Default was stopped. `bin/seon start default` started PID 41822 with the web
UI on port 7994 and prepl on 58659. Both MCP JVM and SCI evaluations returned
42 before substantive delegated research proceeded. Startup completed in
about 21.4 seconds. The tree contains inherited, partly staged database and
test-system work; none was reverted, committed or represented as verified.

Two `bin/seon init --dev default` attempts subsequently exited 1 at the
declared 30,000 ms silence boundary after reporting a compiled program
population of 11,191 entities, 23,734 identities and 39,441 keyword facts.
The command totals were 74,711 ms and 59,232 ms. The database had no adopted
`:seon.source/commit-id` value in the inspected cluster. The published source
head was `6aac85dd-adc8-5171-bde7-5ce8a41de5d3`. Therefore **running and
REPL-accessible does not mean the working tree was successfully adopted**.
No source fix, fresh reset proof or green platform gate is claimed here.
Logs: `data/operator/operations/init-init-42258.log` and
`data/operator/operations/init-init-45646.log`. These are operational logs;
the observations above are the durable record. Slow publication already has
an [open issue](../../../seon/issues/complete-publication-takes-seventy-seconds.md).
The timeout does not identify its cause or prove a permanent writer hang.

The three schema reports cover the sorted 210 EDN resources, 70 each, with
per-file hashes. They separate schema/source findings from live probes:

- [A: contracts and agent/plan relationships](schema-audit-a-native-2026-09-19.md).
- [B: namespace responsibility, work and message relationships](schema-audit-b-supplement-2026-09-19.md).
- [C: schema shapes, results and admission](schema-audit-c-native-2026-09-19.md).

Another session wrote overlapping audit filenames during this pass. Its
files were preserved; these uniquely named reports identify this team's
evidence and prevent accidental conflation of static and live measurements.

The live census found 3,573 bound, nonmacro function Vars across 110 loaded
source namespaces: 1,239 with Malli contracts, 1,238 wrapped, and 2,334
without contracts (2,269 private and 65 public). The one contracted,
unwrapped Var was the primitive-return `seon.sci.admit/required-cap`.
These are the census's stated population, not a count of every anonymous
closure or JVM method. Existing arming checks the declared, armable set;
it does not establish completeness of that set. Universal instrumentation
is a requirement, **not the present state**.

## Names and relationships

Use **namespace agents**. Responsibility is a many-to-many relation: a
namespace may have several agents and an agent may cover several namespaces.
The agent's current evaluation namespace is a different fact. Today
`:seon.agent/namespace` is nonunique but scalar; `:seon.ns/steward` is
scalar, and `seon.cluster.agent/steward-call` retains the first agent.
Simply renaming the latter would preserve the defect.

Prefer **task templates** for reusable work context, **tasks** for concrete
obligations, **issues** for detected defects, and **conversations** for an
ongoing exchange. Answering a particular request is a task in a conversation.
The conversation itself need not become an issue or have severity, a defect
detector or a code test. Delivery/threading can be checked mechanically;
the usefulness of an answer cannot be established by a delivered-message
test alone.

Implement this by generalizing the existing issue/plan/settlement mechanism
in place. Do not resurrect the withdrawn `my.task` stack or create another
scheduler. Registry discovery and invariant examples must precede final
attribute names. Replace obsolete singular keys and their consumers together
at a reset boundary; a key must not silently acquire different semantics.

Namespace responsibility, participation in a task, and authority to accept
a particular change are distinct. Several agents can work in the same
namespace without receiving duplicate work. A task can have several
participants if that is useful; give independent attempts their own identity
and evidence, rather than deriving every worker solely from the issue ID.
Do not use namespace membership as exclusive execution permission.

## The data contract to establish first

An entity remains its attributes, values and refs. There is no generic kind
stamp. Each durable relationship must state its deletion behavior and whether
it describes a current entity or records an observed token. An observation
that must survive deletion stores the symbol/value, not a ref that sweeps.
Components are validated as part of their owner's complete value.

The audit must produce executable counterexamples for these classes:

| Class | Guarantee to enforce at the authority |
|---|---|
| Missing evidence mistaken for success | An absent subject, unrun test, unavailable observation and observed empty result remain distinguishable. Positive observation evidence carries its basis/digest. |
| Well-typed but contradictory state | Owning agent/backlink equality, current-step membership, lifecycle transitions and result-count relationships validate against the transaction's final database. |
| Deletion changes meaning | Deleting a listened-to entity cannot turn a specific subscription into a wildcard. Required refs refuse, optional refs sweep only when that is the intended behavior. |
| Incomplete shape descriptions | A shape child has a meaningful payload and valid ordering; a result cannot simultaneously be passed, skipped and over its declared case count. |
| Constructor-only validation | Direct datoms, retractions, nested transaction functions and component edits receive the same guarantees as helper constructors. |
| Contract coverage blind spots | The complete eligible function population is independently compared with declared contracts and installed wrappers; an empty/missing population fails visibly. |

Open maps stay open. Strong validation constrains declared facts and their
relationships, rather than refusing unrelated additional attributes. Do not
replace every polymorphic boundary with a falsely narrow type; the generic
value renderer, foreign values and genuine executable objects need honest
contracts. Stored entity, transaction input and pulled result are different
shapes; derive the latter from the selector instead of copying map schemas.

Error admission needs the same treatment. Every diagnostic must preserve the
original function, operation, argument/path, expected shape, offending value,
cause and program basis when available. A later contract refusal must not
erase the original failure. Test invalid calls through both armed JVM and
interpreted SCI paths, including return failures, before claiming parity.
Use the existing flat error owner and total bounded renderer.

## Templates supply facts; render functions teach their use

A template is inspectable program/database data identifying the subject
constraints, context selection, acceptance functions/tests, intended changes
and execution bounds. A detector may discover subjects; a user request may
supply one directly. Neither requires a parallel registry of procedures.
Instantiation links the agent and its ordinary plan to those facts and the
particular subject. Shared code, schemas and evidence remain joinable at
their existing identities; do not copy them into a prompt-shaped record.

The existing walk discovers the linked concerns and calls their AI/HTML
render pairs. The AI pair selects concise explanation and useful REPL forms;
the HTML pair explains the same facts to the user. The value printer remains
the total fallback. Agents can write better pairs through ordinary program
admission, with contracts, tests and the same integration gates as other code.
No second clipping location or prompt assembler is introduced.

First template examples are repair a failing test, strengthen a schema with
a reproducing example, add missing coverage, repair an observed error, and
answer a request. Each opening should show the actual subject, why it needs
attention, relevant schemas/callers/tests, explicit missing evidence, the
current acceptance result, and useful forms. Tests of generated forms must
exercise real SCI and the canonical database fixture. Mere existence of a
render pair does not prove it teaches the agent anything useful.

Conversation context follows message relationships and relevant shared facts.
Reuse message `:caused-by` where it expresses the reply relation. A message
subject token is not automatically a conversation ID or reply-to reference.
The turn's handled claim, whether a wake was answered, and fulfillment of a
user request are separate questions. A later unrelated reply proves none of
them. A missing/failed context read must render an explicit unknown.

## Isolation: three concrete choices

| Choice | Guarantee | Cost and what it gives up |
|---|---|---|
| **SCI context plus Datahike branch per independent change — recommended** | Private definitions and candidate program/schema datoms are separate; acceptance can name an exact base and candidate. Multiple changes to one namespace can proceed concurrently. | Moderate integration work, reusing existing fork owners. Branches share storage; external effects, JVM roots and arbitrary mutable objects are not isolated by this. |
| SCI context only, shared connection | Private definitions can differ without changing the base context. | Lowest cost; already demonstrated. Gives up durable write isolation, so suitable for read-only/private experiments, not the proposed schema/code merge guarantee. |
| Separate JVM/operator root per candidate | Separates loaded host code and process state as well as database writes. | Highest startup, storage and lifecycle cost. Appropriate for host/protocol/macro changes that cannot be honestly tested in the interpreted candidate; unnecessary for every ordinary repair. |

The recommended option is a proposed implementation choice, not a claim of
approval for a cross-owner refactor in this audit turn.

SCI `fork` copies its environment with a new generation; it does not deep
copy all reachable objects (`reference-code/sci/src/sci/core.cljc:345`).
Seon already has `fork-candidate-ctx` and `fork-cluster-ctx`
(`src/seon/sci/eval.clj:2957`, `:2195`). Reuse them with the branch's actual
connection, projection and environment. Datahike branch roots share stored
indexes (`reference-code/datahike/src/datahike/versioning.cljc:224`).

The parallel analysis adds a useful constraint: `seon.env/scope` only admits
turn-layer values (`src/seon/env.clj:253`); it must not rebind the branch
connection. Construct a candidate **cluster environment** on the branch
using the existing cluster/registry lifecycle, then scope its agents. This
keeps connection, projection, listeners and execution custody coherent.
The historical 17 ms branch-creation measurement is not a measurement of
cluster boot/acquisition or tests. Measure those separately before scaling.

The [exact successful MCP probe](namespace-agent-isolation-probe-2026-09-19.json)
at database basis 536871516 took 36 ms: fork A returned `:a`, fork B `:b`,
the base did not resolve the probe name, and both forks held the identical,
present connection. No durable transaction was issued. This proves private
definition separation and directly exposes the remaining custody boundary.

`base-ctx` currently copies core JVM roots and interprets agent admissions
(`src/seon/sci/eval.clj:2091`). Loading an arbitrary candidate entirely from
its database program is therefore not yet a universal guarantee. Finish and
verify the existing acquisition-by-digest work; explicitly classify edits
requiring a fresh process. No speculative hot redefinition of shared JVM
roots should count as an isolated candidate test.

## Acceptance into the cluster and source files

**The test-system overhaul is the shared dependency, not a new lane-local
runner.** An individual agent test, a namespace selection, a candidate
cluster check and a disk integration check are requests to the same
database-backed selection/execution/recording owner. Derive the requested
members and whether their called/referenced definitions, contracts, schemas,
test source or declared external inputs changed. Reuse compatible recorded
evidence; execute invalidated or missing members. Test-body observations of
live task/database state also need their declared basis/read evidence.
Unchanged function text alone cannot prove those observations unchanged.

Keep result status, membership, counts, dependency/input digests, basis and
provenance queryable as datoms. Store oversized durable failure/output
payloads through **`seon.blob` in the existing Konserve store**, retaining
their digest and useful bounded rendering. The existing test-failure shapes
already declare `expected-blob`/`actual-blob`; reuse and verify that path.
Do not store arbitrary live evaluation result objects or introduce another
blob backend. A result-recording failure fails completion; a missing blob is
unavailable evidence, not an empty success.

The in-flight stage 1–3 test-system work owns this mechanism. Integration
should extend its declared requests and selection facts, preserving one
definition of reuse across agent, cluster and disk callers. Tests proving
reuse must include unchanged-green reuse, one changed transitive dependency,
a schema-only change, missing evidence, and an oversized result retrievable
by digest. The stronger disk gate expands the required evidence and execution
environment; it need not gratuitously rerun compatible unaffected members.

Candidate success is not cluster success. Existing `evaluate-candidate`
and exact declaration replacement are ingredients, not an optimistic merge
protocol. Datahike `merge!` accepts caller-supplied transaction data and
tracks parents; it is not a semantic code/schema merger
(`reference-code/datahike/src/datahike/versioning.cljc:734`).

Reuse the existing transaction-time `declaration-diverged-since-open?`
(`src/seon/turn.clj:1147`) and exact replacement owner as the starting point.
Its same-identity check is valuable but does not prove tests remain valid
after other callers or schemas change. Main's current acceptance authority
judges candidate changes, including changes to acceptance code itself; the
candidate does not get to substitute its own weaker gate.

The cluster acceptance record must identify the base, proposed definitions,
schema dependencies, selected tests, actual results and tested target head.
Build and test the proposed combined state before making it visible. At the
writer, refuse if the target no longer matches that tested state; reconstruct
and rerun affected gates. Two workers changing the same function must never
silently overwrite one another. Even disjoint function edits may conflict
through contracts, schemas or callers.

Apply declaration removals and caller repairs in one final-state-validated
transaction. Transfer identities and owned values, not branch-local numeric
entity IDs. Test evidence names the exact candidate/program and selection;
missing tests, vanished subjects and stale results are not green. Zero new
executions is valid only when positive compatible recorded evidence covers
the complete required selection; it is not success merely because no tests
ran. An agent cannot weaken its acceptance set to declare itself done.
Include a canonical-fixture negative example before the repair and the
positive proof afterward. Platform and live acquisition proofs remain gates.

Disk acceptance adds source fidelity. Derive an export from the accepted
program facts and recorded source spans, check expected file digests, stage
the changed files in an isolated checkout and index them through the ordinary
publisher. The resulting definitions, contracts, schema declarations and
tests must match the accepted candidate. Preserve untouched bytes, comments,
reader conditionals and form ordering. Existing-file edits require this
stronger proof; an exporter limited to new namespaces does not satisfy the
owner's refactoring objective.

Compose the existing `seon.edit` lossless splice and digest-fenced
`seon.edit.jvm`/`seon.fs.jvm` writer instead of creating another exporter
mechanism. Agent-admitted overrides lack current file coordinates by design:
recover their historical source provenance through `seon.program/overrides`.
Missing coordinates alone do not mean a definition should be appended.

Run targeted armed tests, platform tests, the relevant broader integration
selection and a fresh boot/SCI acquisition against the exported tree, then
make a path-limited commit. Cross-file writes and a database transaction are
not one atomic operation: record positive stage evidence so interruption is
detectable and completion can be retried without overwriting unrelated work.
Do not mark export complete merely because the cluster merge succeeded.

## Smallest convincing live demonstration

Two namespace agents share responsibility for one namespace. They receive
different concrete tasks through template data, render their own useful
context, and experiment on separate SCI contexts and database branches.
One task improves a schema guarantee and its regression; the other improves
a function or render pair with its own regression. Exercise both a disjoint
merge and a deliberately conflicting same-identity change: the conflict
must refuse with useful repair information. A malformed candidate must fail
before cluster installation. Accepted work must survive export, fresh
publication and the stronger disk gate. A reply task demonstrates the same
context mechanism without fabricating an issue.

Do not wait for all 2,334 uncovered Vars to be repaired before proving a
bounded slice. First close every uncovered/weak boundary reachable by that
slice and make remaining coverage visible. Broaden namespace work only as
the invariant gates justify it. This prevents both a premature autonomous
campaign and an endless cleanup prerequisite.

## Reading and verification record

The orchestrator read AGENTS.md, the transfer manual, data-modeling,
datahike, data-oriented-clojure and repl skills, the modeling guide and
architecture data model, the 2026-09-17 modeling study, the 2026-09-16
deletion study, the complete turn-loop PRD, current roadmap, namespace data
model, original self-improvement idea and effects/write-back proposal end to
end. The flow skill and vendored SCI/Datahike seams grounded isolation.
The working-edge logs were read at their latest resume blocks, not claimed
as complete historical reading. Researchers read the modeling authorities
and partitioned additional studies; their reports state exact coverage.
The very large historical schema-key appendix is not represented as a
second complete schema audit. The current 210 resources are the audit target.

No Clojure production code or schemas were changed by this research team.
No tests were run in this planning pass. Live observations are explicit
above and in the individual reports; source-derived counterexamples still
need canonical-fixture regressions. Existing test names are planned gate
inputs, not evidence that this turn ran them.
