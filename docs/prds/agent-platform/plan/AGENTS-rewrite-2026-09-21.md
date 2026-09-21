---
type: reference
status: proposed replacement; activate with the implementation transition
created: 2026-09-21
tags: [agent, architecture]
---

# Seon — shared instructions

This is the proposed replacement for the repository's shared instructions.
The existing root `AGENTS.md` remains active until the implementation transition.
At activation, Codex reads `AGENTS.md`; Claude reads its `CLAUDE.md` symlink.
Laws belong here, mechanisms in architecture and skills, implementation in the plan.
Correct a false current claim in the same commit as the change that exposed it.

## Start here

Read [the plan](README.md), your implementation spec, named authorities and the
nearest `AGENTS.md` end to end. Plans describe targets; verify current behavior.
Read Git history before designing: earlier implementations are evidence, not baggage.
Read the vendored dependency seam and its first-party caller before adding code.
Record its pinned revision, source location, guarantee, supplied inputs,
recomputation event and work proportionality. Load the matching skill at design time.

Start every Clojure change at a running system. Check `bin/seon status` and MCP
`runtime_status`; use `eval_clj` with an explicit root and cluster. JVM mode has no
cluster custody: pass the connection from `(seon.operator/connection "default")`.
SCI mode mutates the cluster's shared context, so keep probes disposable.
Report and record missing or degraded tools before attempting a workaround.

**Use the REPL to test ideas, inspect data and measure.** Reproduce with one small
form, inspect the complete envelope and installed schema, call the owning function,
then test the smaller algorithm. Record exact forms, values and timings.
Edit the owner in place, verify publication/adoption or hot reload, repeat the probe.
Name which path the evidence exercised; browser paint needs its own observation.

Run the armed tests reaching the change through the installed request described in
[B4](lane-b4-tests-in-process.md). Until that request replaces the launchers, use the
current gate authority; a proposed API is not an executable instruction.
Evidence, changed paths, commit ids, sizes and verification limits belong in the
owning landing note under `../landing/`. Commit measurement scripts; disposable
probes live in repository `tmp/`, reusable checks in `test/`.

## How we work

**Ask what the dependency already does before you build anything.** Read its source.
Datahike owns transactions and branches, Malli owns compiled schemas, SCI owns its
contexts, core.async owns graph execution. Improve the maintained fork when that
removes a parallel Seon mechanism. The best change deletes a mechanism.

**Seconds, not minutes.** Work longer than ten seconds requires explicit owner
authorization, including boot and initial indexing. Investigate costs above a couple
of seconds: name work proportional to the whole program that should follow a change.
A branch pointer is cheap; constructing a full context is a separate operation.
Source observation hashes its admitted inputs; unchanged adoption compares commit ids.
Use the dependency's cache; do not place a second cache beside it. Measure memory too.

**Derive state; do not remember it.** A stored observation is not current derived
state. Carry derived values with their immutable authority. A check must report its
subject's absence as unknown or failure, never health. Conversation memory is never
the only record: integrate decisions into the plan, evidence into landing notes,
and defects into the existing issue authority in the same coherent commit.

## The system

One JVM runs the CLJ system, REPL-first. Boot opens the REPL before store acquisition,
then constructs process → store → facts → flow; every layer publishes readiness.
Tiny bootstrap settings select process paths and binds. Running cluster configuration
is database facts; credentials are environment-variable names, never stored secrets.
One process root holds the store's lifetime lock; Datahike serializes transactions.

A cluster is one database branch, its agents and shared plumbing. Boot supplies one
environment per cluster, scoped per agent. Many clusters may share one JVM.
Ordinary clusters retain their program; selected development clusters adopt explicitly.
A file edit is live only after publication, reload and arming succeed. Hot reload
alone changes loaded behavior, not indexed program facts.

Each agent owns a flow graph. Procs declare `:io` or `:compute`; no central scheduler
or dispatcher is added. Transforms reference Vars; topology changes rebuild the graph.
Channels carry only losable data: facts can rederive it or a newer complete value
replaces it. Buffers express that policy. Recovery inputs are durable facts, with
bulky durable payloads in blobs; actual evaluation results are excluded.

Interrupted execution never resumes. Recovery closes open turns and records unfinished
evaluations as interrupted. Private defs, atoms and result objects stay in memory;
shown text survives restart. Every function in the cluster's program is callable;
prompt visibility never grants or denies execution. Consumer-specific domains belong
downstream. See [architecture](../../../seon/architecture/architecture.md).

## Five design laws

### Values carry their world

Pass the environment, database value/connection, projection, render profile and
settlement inputs explicitly. Derived state rides its immutable value. Never rebuild
it at each call or fetch a different world's inputs from a global registry.
The existing custody boundary supplies agent db/conn elision; it is not permission
for additional ambient services. Profiling/test observations follow explicit execution
inputs. `seon.config/defaults` is a compiled program constant, not cluster configuration.

No seam may act on a pre-read or a mirror that its authority will re-decide: derive
at the authority, or hand it the decision. A pre-read is legitimate only when its
answer cannot change before the authority acts; otherwise validate the basis there.

### Facts over inference

Program declarations and durable state are queryable facts. If answering a question
requires guessing names, joining text or walking files, declare the missing fact at
its producer. Search the schema registry before declaring another key.
A mirror must be derived, checked for drift, or dated as a point-in-time observation.
A REGEX IN PRODUCTION CODE REQUIRES THE OWNER'S PERMISSION — STOP AND ASK.
Working searches with `rg` are ordinary tooling.

### Bounded, event-driven execution

Interfaces publish readiness; install listeners before deriving work. Every execution
surface has a declared bound enforced at admission. Await the exact terminal event.
A bound firing names the missing event and fails; it is never a silent retry or pass.
A timeout is not termination. Do not admit overlapping work or release its resources
until exit is observed. SCI's interrupt hook covers interpreted execution, not
arbitrary host functions. Retain isolation where termination cannot be guaranteed.

### Total, honest boundaries

Every function, private included, has a complete Malli contract. Invalid input prevents
entry; invalid output refuses the result. Refusals identify operation, member, expected
shape and offending value. Unavailable evidence is typed unknown, never silence.
Domain functions enumerate possible errors. Body-derived verification of their declared
unions remains pending an explicitly owned producer, checker and regression; do not
claim that guarantee from declared contracts alone. A polymorphic boundary must justify its schema.
No general error predicate, copied error union or discriminator stamp.

Agent mistakes are flat values. Core errors follow the configured panic/record policy;
a failed graph is positively visible. Database failure is handled at the reachable
REPL, never through another durable replay store.

Rendering is total for ordinary values. AI render functions and the value renderer
alone apply presentation limits, once; save the exact shown text. HTML never clips.
Whole-unit prompt selection stays and never rewrites historical units. Query-work
limits and evaluation deadlines are separate. An elision names the bound, count,
path and requery; floor hits are counted. A missing profile derives at the render
entry or refuses explicitly. Report ugly output; display sizes use estimated tokens.

One AI/HTML pair belongs to an entity schema. Scalars share its block, components and
declared queries supply their own. No pair uses the default attribute-map printer;
a failed render gives a diagnostic. Error rendering composes the base and every
satisfied schema. An attribute request never silently becomes an entity request.

### One mechanism, accreted in place

Fix the owner; no version-two namespace or parallel registry, renderer, retry or runner.
Maps are open: declared members validate, extra members do not refuse. Narrowing input,
requiring an optional member or weakening output is breakage. Different semantics
needs a new key. Retire a public Var and convert every caller in one loadable slice.
When a decision creates hours of cross-owner work or unclear guarantees, stop before
production edits and offer exactly three concrete options: simplest viable constraint
first, recommendation marked, each with guarantee, cost and what it gives up.

## Data, tasks and admission

Use immutable transformations, fully namespaced keys and one declared schema per
identity. Stored absence is no key, never nil. A symbol stays a symbol. An entity is
its attributes and relations, not a stamped kind. A bounded enum describes a real
state or dependency grammar; it never selects an invented entity taxonomy.

Retraction deletes; history answers what existed. Required refs refuse a deletion
that would invalidate the referrer; optional refs may sweep; components cascade.
An observation that must outlive its target stores a value. Surviving references to
a removed declaration require repair in the same transaction or the deletion refuses.
No placeholder function or test repairs a missing identity.

Validate final owning values, including swept refs and identity-less components.
Missing children, cycles, multiple owners and exhausted validation bounds refuse.
An entity schema describes stored data; pulled shape derives from it and its selector.
A pull must not silently truncate. Each function/test's required analysis digest
proves it was analyzed; an empty cardinality-many attribute cannot prove an event.
Use the [data guide](../../../seon/architecture/data-modeling-guide.md) for details.

Errors are declared flat data, deduplicated by distinguishing content through the
one identity owner. Occurrences are components. Do not add undeclared map copies.
Shown text and live result handles have different durability; follow B3's explicit
storage decision before deleting an existing durable representation.

One `seon.task` family holds linked work facts and an optional assigned agent.
Responsibility comes from namespace refs and is independent of routing. The task
writer handles repeated triggers; start validates a real done condition and creates
the agent and first turn atomically. Tests/detectors must exist and provide positive
current evidence; absence never means done. Conversations derive from messages and
use an actual reply condition. Agents are archived, never retracted. Budget exhaustion
is loud and resumable, with the same task and agent identity.

Candidate work has its own branch and SCI context. Explicit shared admission requires
contracts, the task tests and every test reaching changed functions on the combined
program; missing coverage refuses by name. Private experiments remain possible.
The writer checks the tested head. Same-identity conflicts become root tasks.
Write-back checks file bases, stages exact-span changes and uses the canonical indexer
to reproduce equivalent definition facts before installation. Product agents use
declared evaluation/adoption/test requests, never shell commands for self-modification.

Wakes derive from listened datoms and the basis of a qualifying ordinary reply;
opening/system-only turns answer nothing. Handling is independent. Message subject
is a literal token; `:seon.message/from` marks an inside wake. Before each turn,
refresh every distinct changed read using its latest evidence; never rerun writes or
effects. Compaction wipes evaluations and regenerates the opening.

`seon.db` owns database operations. `my.*` is a thin surface over the same facts and
writers. Config reconciles differences and resolves function symbols to current rows.
Provenance belongs to transactions; write bounds derive from it, and bounded refusals
retain the proposed transaction data. Use `seon.id` for identity, never a new generator.
Supplied defaults match the declared schema name, not structural equivalence.

## Tests, operation and collaboration

Use the canonical database fixture, real SCI, armed contracts and canonical entity
helpers. Every fixture write surfaces refusal. Pass projection/environment, proc
inputs and fixed render profiles explicitly. Own no JVM-global state; preserve
instrumentation deliberately and never restore roots replaced by a reload.
Stop graphs before retracting facts they settle. Resource scopes release everything
even when setup or cleanup fails. The testing skill owns helper signatures.

Tests fail over their declared bound (default five seconds); longer bounds require
both number and reason. Unknown/red evidence never reuses green. Selection uses current
dependencies and each member's actual tested program/input evidence. Past observed
reach is diagnostic. Record completion even when no armed function was observed.
Retain destructive-process isolation until the replacement proves its confinement.
A recording failure cannot report success. Preserve one regression per behavior class;
delete a machinery test only with its obsolete mechanism.

The development REPL stays available. Lanes never stop, refork or reset `default`;
they report RESET NEEDED. The orchestrator batches recovery, preserves required evidence,
then reconnects and verifies. Reset loses database and private state; disposable is not
synonymous with automatically reconstructible. Adoption freshness and browser paint
are observed separately. Do not claim a disabled hook published an edit.

Preserve unrelated edits. Own explicit paths; no `git add -A`, `reset --hard`, shared
file restore or structural-edit worktree. Commit coherent path-limited slices. Native
Codex collaboration or the documented CLI launcher handles lanes; do not nest delegation
inside a bounded assignment. At most three editing lanes and two test JVMs; no lane
overlaps its own test/probe JVMs. Check live holders before deleting disposable roots;
recursive deletion never follows symlinks. Paid provider runs are deliberate.

Verify before naming a cause. Search existing issues, record one note per defect class,
and report exact evidence and limits. Keep the plan clean: integrate decisions into
their owning sections; audits and dated evidence stay outside the plan directory.
Use Clojure/dependency vocabulary: cluster vs environment, JVM REPL vs SCI evaluation,
turn vs evaluation, live result vs shown text. Legacy source identifiers may be cited,
not repurposed as new terminology. No restrictions for hypothetical risk.
