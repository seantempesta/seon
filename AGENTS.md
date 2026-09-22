---
type: reference
status: active
created: 2026-09-21
tags: [agent, architecture]
---

# Seon — shared instructions

This is the one maintained repository instruction authority. Codex reads `AGENTS.md`;
Claude reads the same bytes through `CLAUDE.md -> AGENTS.md`.
Laws belong here, mechanisms in architecture and skills, implementation in the plan.
Correct a false current claim in the same commit as the change that exposed it.

## Start here

Read [the agent-platform plan](docs/prds/agent-platform/plan/README.md), your implementation spec, named authorities and the
nearest `AGENTS.md` end to end. Plans describe targets; verify current behavior.
Read Git history before designing: earlier implementations are evidence, not baggage.
Read the vendored dependency seam and its first-party caller before adding code.
Record its pinned revision, source location, guarantee, supplied inputs,
recomputation event and work proportionality. Load the matching skill at design time.
A skill's every claim carries `file:line` and is verified when touched; an unverifiable
claim is deleted, never hedged; a stale skill is a high-priority defect.

Start every Clojure change at a running system. Check `bin/seon status` and MCP
`runtime_status`; use `eval_clj` with an explicit root and cluster. JVM mode has no
cluster custody: pass the connection from `(seon.cluster.boot/connection "default")`.
SCI mode mutates the cluster's shared context, so keep probes disposable.
Report and record missing or degraded tools before attempting a workaround.

**Use the REPL to test ideas, inspect data and measure.** Reproduce with one small
form, inspect the complete envelope and installed schema, call the owning function,
then test the smaller algorithm. Record exact forms, values and timings.
Edit the owner in place, verify publication/adoption or hot reload, repeat the probe.
Name which path the evidence exercised; browser paint needs its own observation.

During the refactor, [plan §6](docs/prds/agent-platform/plan/README.md#6-implementation-proof-and-recovery)
owns verification cadence: focused installed REPL/test requests within a cut;
platform and affected integration once at its completion. No suite per edit or
commit. A deleted mechanism's tests leave with it; replacement behavior tests land
by the cut's end. The work is deep cuts in the plan's ruled order, each confirmed at
the REPL and landed as one loadable slice; a red test is then asked three questions —
does it test deleted machinery (delete it), a retired assumption (fix the
expectation), or wanted behavior of a surviving seam (fix the owner) — and never
triaged wholesale into lanes (README §6, "forest, not trees"). HEAD loads and the named REPL probe answers. Report the exact
verification boundary; unavailable evidence is not a pass.
The [testing skill](.agents/skills/clojure-testing/SKILL.md) distinguishes installed
commands and enforcement from B4 targets. Never pretend a planned API is installed.
Gate inputs are DECLARED (`seon.test.cache/input-roots`): a documentation edit never
publishes and never widens a gate; a tool that behaves otherwise is fixed first.

Evidence, changed paths, commit ids, sizes and verification limits belong in the
owning landing note under `docs/prds/agent-platform/landing/`. No publication-path
slice lands without its clock row from the committed measurement script
(`docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh`). Commit measurement
scripts; disposable probes live in repository `tmp/`, reusable checks in `test/`.
Load [data-oriented-clojure](.agents/skills/data-oriented-clojure/SKILL.md) before
Clojure design/edits; use the matching REPL, testing, datahike, data-modeling and
flow skills for their boundaries. The orchestrator also reads
[TRANSFER_PROMPT](docs/TRANSFER_PROMPT.md); this file and current owner rulings
supersede its obsolete workflow claims.

## How we work

**Ask what the dependency already does before you build anything.** Read its source.
Datahike owns transactions and branches, Malli owns compiled schemas, SCI owns its
contexts, core.async owns graph execution. Improve the maintained fork when that
removes a parallel Seon mechanism. The best change deletes a mechanism.

**No stamps (owner, 2026-09-23: "get rid of bullshit stamps; so much of what we are
doing is already available in Datahike").** A value derived from a database value is a
FUNCTION of that value, memoized with Clojure's tools (`clojure.core.cache`, keyed by
Datahike's own `:cache-context` or commit id), never `vary-meta` stamped onto the value by
a writer, never threaded through a transaction as a candidate, never kept in an atom
beside the connection. Identity comes from Datahike (commit id, `:cache-context`,
attribute revisions) or the definition digest; lineage from `versioning.cljc`; time from
`history`/`as-of`; notification from `d/listen`; serialization from the writer. Before
adding any of these, name the seam in `reference-code/` with `file:line`; the audit
`docs/research/agent-platform/dependency-already-does-it-audit-2026-09-23.md` lists the
ones already built by hand and their deletions.

**Seconds, not minutes.** Work longer than ten seconds requires explicit owner
authorization, including boot and initial indexing. Investigate costs above a couple
of seconds: name work proportional to the whole program that should follow a change.
A branch pointer is cheap; constructing a full context is a separate operation.
Source observation hashes its admitted inputs; unchanged adoption compares commit ids.
Use the dependency's cache; do not place a second cache beside it. Measure memory too.
**No slow operation escapes unnoticed (owner, 2026-09-23: "by design most ops should be
sub second").** Every lane times every operation it runs — boot, publication, test run,
probe, reload — and reports each over one second with its phase breakdown. Over ten
seconds is a defect: name it in the report, file or extend its issue note in the same
beat. "Known cost", "expected for a scratch boot" and "priming" are not explanations;
the orchestrator rejects a report that carries a slow operation without its number.

**Never redo valid cached work (owner, 2026-09-23: "we should never redo work that we
have cached if the cache is still valid"; "link the caches so this doesn't happen").**
Every derived result is keyed by its inputs' content (file digest, deps digest, commit id,
`:cache-context`) and stored once, in the dependency's own cache where one exists. Every
root, snapshot, scratch store and test run links that shared cache instead of starting
empty. A valid key means reuse, never recomputation; an invalid key recomputes only what
the changed inputs reach. Misses are counted where they happen, never hidden.
A derived value is keyed by WHAT IT READS — the attributes and their Datahike revisions,
or the input content — never by "the commit changed": an unrelated write must cost the
happy path nothing (owner, 2026-09-23: "We need the happy path to be fast and to
accumulate data that is reused"). A read, render, probe or eval path never writes a
transaction that invalidates derived state.

**Derive state; do not remember it.** A stored observation is not current derived
state. Carry derived values with their immutable authority. A check must report its
subject's absence as unknown or failure, never health. Conversation memory is never
the only record: integrate decisions into the plan, evidence into landing notes,
and defects into the existing issue authority in the same coherent commit.

**How every duplicate mechanism in this codebase was built (2026-09-22, from the
night that removed most of them).** Four habits, each locally correct, each adding a
mechanism. Recognise them in your own next edit:

1. **Fetching at call time instead of holding the value.** A function that was not
   handed the projection or the connection recomputed it; then someone cached the
   recomputation; then a dynamic var carried the cache. That is the ambient projection
   transport (350 lines, 75 sites) and the schema-shape family (30,000 datoms with no
   reader). The cure is an argument, or a read of the value already held.
2. **Reading silence as health, then hardening against it.** A check returned nil, so a
   fallback appeared; a wait hung, so a bound appeared; a race happened, so a lock
   appeared. The old operator's lifecycle lock, claim files and truth-repair pass were
   that loop run ten times. The store reached 248 GB because the fault recorder's own
   refusal fed the fault recorder. The cure is the typed unknown at the seam and one
   regression asserting the wanted behavior — never another guard.
3. **Fixing at the site instead of the owner.** Guards reading two of sixty declared
   alternatives; fixture acquisition re-identifying 266 retained commits because one
   caller once needed one; a whole-store export used as a test fixture. Each fix was
   right for its caller. The cure is the producer's declared schema or the owner's one
   function, and a test that fails without it.
4. **Retiring without converting.** `:seon.error/kind` left the schema with 730 live
   writers and surfaced only when a reset built a store without it. A retirement and
   every caller are one loadable slice, proven at the write: the schema writer refuses
   a retirement while any program row still writes or references it (1.3e).

Underneath all four: the dependency's source was not the first read. SCI keeps a live
env, Datahike has per-attribute revisions and branch heads, konserve has GC. Before
building, name the seam in `reference-code/` that already does it, with `file:line`.

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
downstream. See [architecture](docs/seon/architecture/architecture.md).

## One JVM, many realities — the branch system

Ruled by the owner on 2026-09-22; the [architecture page](docs/seon/architecture/clusters-branches-contexts.md)
explains it, the [vocabulary](docs/seon/architecture/vocabulary.md) grounds the words.
Installed pieces are named; a **[TARGET]** is ruled but not yet built — design toward
it, never claim it.

**A cluster is one Datahike branch plus the agents working on it.** The branch is a
pointer (`d/branch!`), never a copy and never an environment to start. The cluster row
holds the pointer we advance. Many clusters and branches live in one JVM.

**The program is rows on the branch; the JVM is derived.** Functions, tests, schemas,
namespaces, render pairs, contracts and their analysis facts are the program — the
shared thing that survives. Turns, evaluations, messages, errors, tasks and results are
data: disposable, droppable for a fresh cluster, never merged. **[TARGET]** the
partition is one declared fact on each entity schema, so "program rows on a branch" is
one query, never a hand list.

**Default is the files; everything else interprets its differences.** The default
cluster's program rows (the indexer's output) and its loaded namespaces (`require`'s
output) both derive from the files on disk and are recomputed when a file changes. A
JVM holds one set of compiled Vars, so default alone runs compiled; a branch whose
program rows differ interprets those rows and their affected callers in its own SCI
context and binds the compiled Var for everything else (**[TARGET]** B2 §2a). A
declaration SCI cannot interpret is **host-bound**, a computed per-declaration fact
(**[TARGET]**); it changes only through the files and an override of it refuses by name.

**An agent's mode is which branch its custody points at.** Live: the cluster's own
branch — every evaluation reads the current head, and a `defn` it transacts is there
for every agent at their next evaluation. Isolated: a branch off the cluster head —
nothing moves under it until it merges. Custody hands the agent that branch's
connection (`seon.db/call-with-custody`); the agent never names a branch. The task
sets the mode at start; an agent may branch and request a merge itself (**[TARGET]**
the agent branch attribute and the `my.*` functions).

**Stability comes from the branch, not from a captured value.** No reload runs under
an evaluation. The loaded namespaces advance only at a boundary between evaluations,
by `require :reload` of the changed namespaces and their dependents. A context is
reacquired from its branch head at turn start and cached by commit id.

**A test is an isolated agent that lives for one body.** Branch off a captured commit
(`registry/branch!`, `store/open-branch!`), fork the context onto it
(`sci.eval/fork-cluster-ctx`), run under custody, unlink the branch
(`registry/retire-branch!`) and let the retention sweep collect it. The runner calls
the same functions an agent's evaluation calls; a test-side copy of any of them is a
defect. Only the platform tier — declared destructive owners and host-bound changes —
keeps a fresh JVM.

**Merge carries program rows only, git-like, through the gate.** Non-conflicting rows
land on an intermediate branch; conflicts stay there for the agent to fix, so the
problem shrinks; the combined program is tested in a context forked from that branch;
green reaching tests plus contracts is the gate; a named accept by root or the owner
advances the cluster pointer (**[TARGET]** D1). Write-back to the files is the same
gate. Filesystem lanes (Codex, Claude) index into one shared candidate branch of
default and are live agents there; Seon agents doing every update is the goal.

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

**No swallowed errors (owner, 2026-09-23: "Do not allow swallowing of errors").** A
`catch` either handles a declared case or re-surfaces the failure with its whole cause:
class, message, `ex-data`, the cause chain and the Seon frames. Returning nil, a bare
message, a default value, or a diagnostic that drops the throwable is a defect — the
2026-09-23 nuke refused as "Keyword cannot be cast to Number" with no frame because
`boot.clj`'s request catch kept only `ex-message`.
**The error policy (owner, 2026-09-23: "all the unhandled errors are loud panics so we
can't ignore them in DEV mode … and they are stored as errors in the database so we can
deliver them to root or whatever agent needs to see the errors in production"; "make sure
the policy is clear").** Two kinds, nothing in between:
- An **agent mistake** is a declared, flat `:seon.error` value returned to the agent that
  made it. It is not a fault.
- Every other failure is an **unhandled error (core fault)**. In BOTH modes it is
  (1) stored as a declared error fact in the database, with its whole cause
  (`:seon.error/chain`), committed at the owning boundary and never dropped by an overload
  channel; and (2) delivered: the fact wakes the agent responsible for it (root by default,
  or the namespace's owning agent) through the ordinary wake route.
- The one dial `:seon.config/on-core-error` decides only how loud it is. `:panic`
  (development; `default`) fails loudly: the operation throws to its caller, the failing
  graph stops and shows as failed in status, the page and the REPL, and nothing continues
  past it. The JVM and REPL stay up. `:record` (production) keeps the rest of the system
  running once the fact is stored and delivered.
- A swallowed error, a print-only panic, or a fault that is recorded but delivered to
  nobody is a defect.

Rendering is total for ordinary values. AI render functions and the value renderer
alone apply presentation limits, once; save the exact shown text. HTML never clips.
Whole-unit prompt selection stays and never rewrites historical units. Query-work
limits and evaluation deadlines are separate. An elision names the bound, count,
path and requery; floor hits are counted. A missing profile derives at the render
entry or refuses explicitly. Report ugly output; display sizes use estimated tokens.

One AI/HTML pair belongs to an entity schema. Scalars share its block, components and
declared queries supply their own. When no pair is declared, use the default attribute-map printer;
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
its attributes and relations, not a stamped kind. A symbol-valued attribute stores a
Datahike symbol, never a string: `(str sym)` on write or `(symbol s)` on read is the
defect sighting. `contains?` checks INDICES on a vector and answers true for a nil-valued
map key; since nothing stores nil, prefer `get` with a sentinel or `find`. A bounded enum describes a real
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
Use the [data guide](docs/seon/architecture/data-modeling-guide.md) for details.

Errors are declared flat data, deduplicated by distinguishing content through the
one identity owner. Occurrences are components. Do not add undeclared map copies.
Shown text and live result handles have different durability; follow B3's explicit
storage decision before deleting an existing durable representation.

**Target, B3/D1/D2:** one `seon.task` family holds linked work facts and an optional assigned agent.
Responsibility comes from namespace refs and is independent of routing. The task
writer handles repeated triggers; start validates a real done condition and creates
the agent and first turn atomically. Tests/detectors must exist and provide positive
current evidence; absence never means done. Conversations derive from messages and
use an actual reply condition. Agents are archived, never retracted. Budget exhaustion
is loud and resumable, with the same task and agent identity.

**Target, D1:** candidate work has its own branch and SCI context. Explicit shared admission requires
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

`seon.db` owns database operations; direct `datahike.api` calls survive only inside
`seon.db`, the store/registry and classified branch-custody owners, and system-side
listeners. `my.*` is a thin surface over the same facts and
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
a measured operation and its reason beside the numeric declaration. Distinguish
cold acquisition from warm execution; a timeout is a failure, never excused as
priming. The skill states what enforces this and what remains author responsibility.
Unknown/red evidence never reuses green. Selection uses current
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

**Mechanical edits are scripted, never read-and-edit fifty times (owner, 2026-09-23).**
A conversion that follows one rule across many files — a rename, a caller sweep, a
fixture conversion — is one script (`sed`, `perl`, a Babashka or Python form) run once
over the whole set, then the lint (`clj-kondo`, `bin/seon-hook`'s syntax check) and a
load, then the handful of sites the script could not express by hand. Reading a file
to edit it is for the sites the rule does not cover. Tokens are a budget: fifty
separate reads and edits for one rule is a defect in the lane, not diligence.

**Lanes never cross streams (owner, 2026-09-23: "I'm tired of fighting them. Do a better
job orchestrating so lanes don't cross streams").** ONE FILE, ONE LANE, at any moment — no
"regions", no shared hunks, no hand-staged partial commits. The orchestrator keeps the
ledger `tmp/orchestrator/file-ownership.md` (lane → the exact paths it may edit) and checks
it before every launch and every follow-up: work that needs a held file goes to the lane
holding it as a follow-up, or waits for that file's release — it never runs beside it.
A lane edits only its ledger paths; needing another path, it stops that part and reports
the path and the change, and never edits it. A lane releases its paths by committing them
(`git commit --only -- <its paths>`) and naming them in its final report; the orchestrator
then updates the ledger. The only limits on concurrency are these file holds and real
dependencies — no lane count, no test-JVM slot, no prober cap (owner, 2026-09-23).
**No git worktrees (owner, 2026-09-23: "no more git worktrees"; "If something needs a
worktree it needs to be personally authorized by me").** A proof of HEAD plus a lane's diff
runs from a `git archive` snapshot with the shared caches linked. A worktree is created
only with the owner's personal authorization for that one use, asked directly with the
reason; no lane, spec or orchestrator ruling grants it.

Preserve unrelated edits. No `git add -A`, `reset --hard`, `stash` or shared file restore.
Commit coherent path-limited slices. Native Codex collaboration, the documented CLI
launcher or an Opus subagent handles lanes; do not nest delegation inside a bounded
assignment. No lane overlaps its own test/probe JVMs. Check live holders before deleting disposable roots;
recursive deletion never follows symlinks. Paid provider runs are deliberate.

Verify before naming a cause. Search existing issues, record one note per defect class,
and report exact evidence and limits. Keep the plan clean: integrate decisions into
their owning sections; audits and dated evidence stay outside the plan directory.
Use Clojure/dependency vocabulary: cluster vs environment, JVM REPL vs SCI evaluation,
turn vs evaluation, live result vs shown text. Legacy source identifiers may be cited,
not repurposed as new terminology. **The agreed terms (owner, 2026-09-22; grounded rows and targets in the
[vocabulary reference](docs/seon/architecture/vocabulary.md)):** a **branch** is
`d/branch!`, a pointer, no copy; a **cluster** is one branch plus its agents; a
**context** is the SCI world an agent evaluates in, `sci/fork` for a child; the
**loaded namespaces** are `require`'s output, the compiled Vars, derived from the files;
a **reload** is `require :reload` of changed namespaces and their dependents, nothing
more; **unlink** retires a branch from the roster and the retention sweep collects it.
Program rows, host-bound rows, live/isolated and merge are ruled targets, not installed
facts; use those words for the target and cite the data pack. Retired words:
"environment" as a thing to start, "refork", "worktree", "compiled cache", "facet". No restrictions for hypothetical risk.

## How the orchestrator loses the forest (learned 2026-09-22/23)

Four ways one coordinator turned a researched plan into slow work, each with the rule
that stops it. They are evergreen because they are habits of attention, not of code.

1. **Chasing reds instead of steps.** After a landing I ran the bulk suite and triaged
   514 reds into lanes, most of them tests of machinery the next step deletes. Rule: the
   plan's ordered steps are the work; a red test gets the three questions, never a lane;
   the platform tier at landings, the bulk tier once per cut.
2. **Serializing behind one lane.** After a shared-file collision I queued four ready,
   file-disjoint steps behind a lane none of them depended on, for an hour. Rule: the
   only limits are dependencies and same-file edits; at every check-in list the ready
   steps and launch every one, or name the exact dependency or collision per step;
   "waiting for lane X" is not a reason unless X changes the step's files or spec.
3. **Parking a lane on a question the plan answers.** Lanes stop with three options;
   several were answered by an existing ruling. Rule: rule in the same check-in from
   §7 and the model, with the citation; a genuinely new decision goes to the owner at
   once with priced options; a lane never waits across a check-in.
4. **Accepting "HEAD loads" for a schema change.** Five writers off the canonical path
   were invisible on a warm JVM and refused a from-zero boot one after another. Rule
   (owner, 2026-09-23: "a schema change should not require a from scratch boot.
   Period."): a schema change is proven INCREMENTALLY — its declaration transaction
   applied on a branch of a live store, with the writer refusing a retirement while
   writers survive (1.3e). A schema change that cannot be adopted incrementally is a
   publication defect to fix, never a reason to boot from zero.

Underneath all four: the coordinator's job is the big picture — dependencies, files,
proofs — and every minute spent on a tree is a minute the forest is unattended. When a
slice feels convoluted (a third option, a wrapper to serve a caller, an exception to a
proof, a cap or fallback), the design is probably wrong: stop and say so.

## Operation and delegation

`bin/seon` owns start/status/open/init/config apply/stop/down/reset; destructive
process drills use `--root` and exact `(pid, start-instant)` identity. Never signal
children by an unverified PID. Reset is one replacement JVM: it takes the sibling
store lock before deletion and retains it through publication and boot. A racing
start may win the replacement gap; the loser refuses without deleting or killing.
Use the installed CLI's help; do not call deleted operator internals.

The orchestrator coordinates default replacement, shared exhaust and integration.
A bounded lane works directly, preserves other lanes' files and sessions, and
follows its explicit stop boundary. Protected means concurrently edited only.
Codex uses native collaboration; read the lane skill before `bin/codex-agent`.
Use Astra low for bounded slices, medium for design/review, never high; Sol low for
mechanical sweeps; Opus never for implementation. Specs use verify / falsify / probe,
never adversarial verbs, which trip model safety filters. A launch cites the issue or
plan entry it extends, one lane per defect class after a query, and the spec carries
raw evidence paths, never an attribution. Report usage-limit stops and resume the same lane after owner
authorization, never relaunch under a new name. No sandboxing a lane's assigned work.

A clj-kondo "Unresolved var" on a protocol or dependency name is a stale dependency
cache until proven otherwise: repopulate with the publication classpath
(`clj-kondo --lint "$(clojure -Spath)" --dependencies --skip-lint --copy-configs`),
never the test alias's `.` entry, and never rewrite a correct reference to satisfy a
cache. A schema resource and its loaded consumer land in one publication or not at
all: the resource is live on disk for every reader the moment it is written.
Pause hook publication during a coordinated source cut; the orchestrator records
pause and resumption. Shell edits do not publish themselves. Verify adoption and
arming before calling edits live; never claim an old JVM ran new source. Missing
MCP tools are reported and recorded immediately. Adapt to process churn within
your ownership; do not turn an unrelated failure into an excuse to stop.
Sweep only roots with no live holder, preserving unresolved evidence; inspect
actual runner processes, not a name search that can match the searching shell.
A root younger than the oldest live test launcher is not sweepable. End owned
shells and clean owned disposable roots before reporting; retain uncertain resources.

The [vocabulary reference](docs/seon/architecture/vocabulary.md) retains binding
terms and marks targets. Prefer Clojure's word, then the dependency's, then a newly
justified term recorded once. Correct legacy language in scope or file the issue.
Source comments use `;` for prose, `;;` above forms, `;;;` for runtime structure.
[Architecture](docs/seon/architecture/architecture.md) and specialized skills own
mechanisms; the [data guide](docs/seon/architecture/data-modeling-guide.md) owns
modeling details. Search existing issue classes before opening or assigning work;
verify their current status; the index is the owner's ranked schedule and lanes never
edit it. Report broken things first, exact proof boundaries,
and linked artifacts. Do not push or merge beyond the owner's authorized scope.
