---
type: architecture
status: active — first pass 2026-09-21 (Fable), for astra review before the clean write
tags: [architecture, jvm, cluster, program-graph, publication, contracts, errors, tasks, tests, profiling, namespace-agents]
---

# Seon — the system map

Seon is one long-lived Clojure JVM in which agents and one human build on
the same durable world. Every fact is in a temporal Datahike database; the
program itself — functions, namespaces, schemas, tests, the edges between
them — is a queryable graph in that database; every function runs under a
Malli contract armed by one wrapper; every agent evaluates Clojure through
an SCI context forked from one base. Source files are one projection in
(indexing) and one out (write-back). The mission, in the owner's words, is
[the goals note §1](../../prds/agent-platform/research/durable-goals-and-rulings-2026-09-21.md):
"The entire program graph is in the database and it's queryable … make it
easy to refactor and impossible to cause certain software failures."

Read [README.md](README.md) first for the Current / Target marking. Each
section below ends with **Data flow**, **Current / Target** and
**Reference code** lines; the lane letters (A1, A2, B1–B4, C1) are the
[plan's](../../prds/agent-platform/plan/README.md).

## 0. What we were doing that was dumb

Every audit of the tree found the same shape: **a mirror kept beside the
authority that already holds the fact, then machinery to keep the mirror
current, then tests to police the machinery's cost**
([synthesis §1](../../prds/agent-platform/research/synthesis-2026-09-21.md)).
The database value already carries its compiled schema projection, and nine
derivations rebuilt it. Datahike's query cache already answers "is this read
current", and three mechanisms re-answered it, the third by re-running the
read. clj-kondo keeps its own namespace cache, and we swept it and linted
twice. The OS process table and the prepl already say whether a JVM is
alive, and ~1,300 lines of process records said it again. Datahike branch
heads are commit ids, and `current-src.edn` mirrored them. The wrapper
already derives the error union a function can return, and fourteen hand
copies (six different member sets) restated it. In every case the fix is
the same and it is smaller: **read the authority; delete the mirror; make
the work proportional to the change, never to the program.** The sections
below say, per mechanism, what is computed once, where it is carried, and
what recomputes it.

## 1. One JVM

One process root owns one physical Datahike store under a lifetime `flock`
that is ours (nothing in Datahike stops a second process opening the same
store), one bounded `:compute` executor and one virtual-thread `:io`
executor. Cluster instances share the process; nothing may assume "the"
cluster. Development, tests, agents and the web UI all run in this JVM; the
only reasons to start a second one are a cold boot from zero and the
platform tier's boot-from-zero regressions.

**Data flow.** Process identity is (pid, start-instant), derived once at
start. The store lock is taken once at store acquisition and held for the
process lifetime. Executors are created once; every proc declares `:io` or
`:compute` and is refused without one. Nothing here is recomputed.

**Current / Target.** Current: `src/seon/cluster/store.clj:306` `acquire-flock!`;
`src/seon/flow.clj:162-165` refuses a proc without a workload, `:186`
`bounded-platform-executor`; `src/seon/db.clj:4578` `transact!` is the one
write seam over Datahike's per-connection serial writer. Beside it sits a
process-record, advertisement, claim and repair layer of ~1,300 lines
(`src/seon/operator/state.clj`, `script/seon/fresh_operator.clj`) that
restates what the process table and the prepl answer. Target: that layer is
deleted; liveness is asked of the OS and the prepl (goals note §2b, "one JVM,
index once, then incremental"); lane B1.

**Reference code.** datahike `src/datahike/writer.cljc:42` `LocalWriter`
(one processing thread and one commit thread per connection — we never
build writers, we call `transact`), `:105` `create-thread`;
`src/datahike/gc_guard.cljc:36-42` (Datahike assumes a single JVM; its
permit is process-local — the `flock` is why); core.async
`src/main/clojure/clojure/core/async/impl/dispatch.clj:91` `create-default-executor`
(`:compute` → bounded platform pool, `:io` → virtual threads), `:97`
`executor-for`; `flow.clj:76-78` `create-flow` takes `:io-exec`/`:compute-exec`;
clojure `src/clj/clojure/core/server.clj:228` the io-prepl every tool speaks to.

## 2. Boot and running

The system has two states. **Boot** is the 0→1 construction in dependency
order — process, store, facts, flow — and opens the REPL before store
acquisition so a boot failure is fixable live. **Running** is everything
after: agents, plumbing, the web UI, all receiving the environment boot
produced. Interrupted execution never resumes: boot closes every open turn
and marks its unfinished evaluations interrupted; the agent adapts from
stored shown text.

**Data flow.** Boot reads a closed bootstrap config (store path, prepl bind,
log dir), acquires the store, materializes the cluster's branch head into a
database value, builds the environment value once, arms contracts, starts
the graphs. Recovery is one transaction over the open turns of the branch
(proportional to open turns, never to history). A fresh cluster seeds its
`root` agent's namespace row, one message and the generated opening turn.

**Current / Target.** Current: `src/seon/turn.clj:1704` `recover-tx`,
`:1713` `recover-call` (close open turns, stamp interrupted);
`src/seon/bootstrap.clj:887` `seed-tx` for an absent `root` agent, called from
`src/seon/cluster.clj:2456-2459` (working tree). Boot to ready measured
16.2 s after the 2026-09-22 cuts (goals note §2b), ready in 5,608 ms on
`default` on 2026-09-21 (data pack B4 §0b). Target: boot carries no test
namespaces — tests are program facts resolved by identity after boot (ruling
§1n); the number is paid once and measured by the committed script
(goals note §2b, "landed" = script row + platform tier green); lanes B1, B4.

**Reference code.** clojure `src/clj/clojure/core/server.clj:228` (prepl
opens first); core.async `flow.clj:136-155` `ping`/`ping-proc` (a graph
publishes its own readiness — no polling); datahike
`src/datahike/versioning.cljc:457` `commit-id` (the branch head boot reads).

## 3. Cluster, environment, flow

A **cluster** is one Datahike branch, its agents and their shared plumbing
(the render proc, the fault committer). Boot produces ONE environment value
per cluster (`seon.env`, schema `resources/seon/schemas/seon.env.edn`); each
agent receives a scoped view of it. Every agent is its own core.async.flow
graph from one blueprint, parked between turns and woken by listened datoms;
there is no central loop or scheduler. Channels carry only what is free to
lose: sliding-1 for latest-wins, fixed for backpressure, counted-dropping for
observation.

**Data flow.** The environment is built once at cluster construction and
handed to running code as an argument (values carry their world); the
database value it holds is replaced per transaction by Datahike. An agent's
graph is created on first work and receives its wake as a signal, then
re-derives work from facts. Recomputation is per wake and proportional to the
agent's open facts.

**Current / Target.** Current: `src/seon/cluster/registry.clj:178` `branch!`
(a cluster is a branch); `src/seon/cluster/agent.clj:469` `graph-definition`,
`:497` the turn proc `#'turn/step :io`, `:504` the per-agent schedule proc;
`src/seon/flow.clj:1122` `fault-committer-proc`; `src/seon/cluster/wake.clj:92`
`wake-attributes` (listened attributes derive from the schema). The
environment is held in an atom (`src/seon/env.clj:81` `environment-state`,
`:107` `replace-environment!`), read at nine sites in three files (data pack
B3 §7c) — a fetch-at-call-time holder. Beside flow's own `futurize` sits a
704-line work launcher and a per-turn watchdog thread (`src/seon/flow.clj:241-944`,
`src/seon/turn.clj:5053-5262`). Target: the launcher and watchdog dissolve
into `:workload :compute` + `:compute-timeout-ms` (synthesis §1 row 7); the
environment is a carried value; lane B2.

**Reference code.** core.async `flow.clj:76` `create-flow`, `:78` the exec
keys, `:186-188` `:compute-timeout-ms`; `flow/impl.clj:29` `futurize`,
`:243` `proc` (a `:compute` step runs as a `FutureTask` with the deadline;
the timeout surfaces on the `::flow/error` port, never as a return value);
`impl/dispatch.clj:91-97`; datahike `versioning.cljc:212` `branch!`, `:279`
`delete-branch!` (refuses main and refuses while a connection is active),
`src/datahike/connections.cljc:5` `active-connection` (positive reference
count is the one liveness answer).

## 4. The program graph in the database

Functions, namespaces, schemas and tests are entities. A function's
qualified name is a `:qualified-symbol` identity; its call edges are an
indexed SET OF SYMBOLS (a value, so deleting the callee touches no caller
datom and "A calls a name with no row" is one Datalog clause); its analyzed
source digest is required on every row; namespace and file are refs. Which
tests reach a function is a query over stored edges, never a naming
convention or an annotation.

**Data flow.** clj-kondo analysis of a changed file produces var
definitions with name/end row and column; the indexer turns them into rows
keyed by symbol and transacts the difference against the previous rows for
that file. Reach is derived at query time from `:seon.fn/calls`. The digest
is computed per changed path. Everything is proportional to the changed
files and their callers.

**Current / Target.** Current: `resources/seon/schemas/seon.fn.edn:179`
`:seon.fn/sym` (identity, `:db.type/symbol`), `:31` `:seon.fn/calls`
`[:set {:seon.db/index true} …]`, `:116` `:seon.program/analyzed-source-digest`
required (`seon.program.edn:1`); `src/seon/fn.clj:106` `sha-256`, `:116`
`:seon.fn.file/digest` over the whole file, `:659-660` a file-indexed
function row takes the FILE's digest (working tree) — two functions in one
file share it. Live on `default`, 2026-09-21: 4,600 function rows, 407
namespaces, 2,157 tests, 481,640 datoms; `:seon.test/platform` on 110 rows,
`:seon.test/subject` on 0, `:seon.test/reach` on 0 (data packs B1, B4). No
form span is stored, so write-back has nothing to address. Target: a
per-declaration content digest (ruling D9: "identity is the definition's
CONTENT DIGEST, never a branch-local id") and a file plus form span on every
declaration, both from the analysis clj-kondo already returns; lane B1, and
C1 depends on the digest.

**Reference code.** clj-kondo `src/clj_kondo/impl/analysis.clj:87-115`
`reg-var!` (the per-var row carries `:name-row :name-col :name-end-row
:name-end-col :end-row :end-col :arglist-strs :private :macro :fixed-arities
:doc` — the span is already there), `src/clj_kondo/core.clj:67` `run!`
(`:analysis` and `:cache-dir` travel together); datahike
`src/datahike/query.cljc:2877` `query-dependency-plan` (attribute-granularity
dependencies without executing).

## 5. Publication as one prepl request

An edit becomes program facts through ONE path: the running JVM's prepl
receives the changed paths; each path's digest is computed and compared with
its stored file row; the changed files are linted by clj-kondo with its own
cache; the difference in declarations is transacted on the unpublished
branch in one open transaction; the transaction report names the changed
declarations, from which the callers to re-lint are selected; the head is
moved; exactly the namespaces the report names are reloaded (`require
:reload`; Vars are the indirection); the wrappers whose contract or
referenced definitions changed are re-armed and every other wrapper keeps
its identity. A request with nothing changed compares two commit ids.

**Data flow.** Inputs: the changed paths (hashed, only those). Analysis:
clj-kondo over those files, cache-resolved. Write: one transaction of the
difference. Callers: selected from the report's `:seon.fn/spec` and
`:seon.schema/form` datoms, linted only when a contract digest changed.
Reload: the report's namespaces. Re-arm: the wrappers `current-wrapper?`
rejects. Cost is proportional to changed declarations and their callers.

**Current / Target.** Current (working-tree lines): seven entry points into
`src/seon/cluster.clj:2174` `refresh-source!` and four adoption paths (data
pack B1 §3a–b); `:2037` `development-source-refresh!`, `:1922` `reload-order`
(25 lines, our own topological sort), `:1971` `load-development-definitions!`;
`src/seon/cluster/source.clj:109` `path-digests`, `:139` `current` (the
branch head is a commit id), `:371` `publish!`; `src/seon/fn.clj:3314` `index!`,
`:3047` `reconcile-tx-in`, `:2274` `caller-files` (re-signatured over a
transaction report in the preserved draft); `src/seon/instrument.clj:844`
`current-wrapper?` decides re-arming. Measured 2026-09-23: repeat docstring
edit 2,723 ms (source build 553, analysis 475, publication 303, adoption 406,
projection 248 for 1,560 contracts), no change 264 ms, complete publication
195 s (data pack B1 §5). Nine progress mechanisms and seventeen bound
declarations sit on this path (B1 §3c–d). Target: the one path above,
docstring edit sub-second, from zero ≤ 60 s, callers re-linted only on a
contract-digest change, one namespace reloaded for an ordinary edit (goals
note §2b rows 1, 5–7, 9; rulings "every publication input is a file row
carrying its digest" and "the transaction report is the seam for caller
lint"); lane B1. The preserved draft
(`docs/prds/agent-platform/research/one-jvm-lane-items-6-8-draft-2026-09-21.patch`)
has the caller algorithm but lands as a second analysis pass and second
transaction; the target is one open transaction.

**Reference code.** clj-kondo `src/clj_kondo/impl/cache.clj:23` `from-cache-1`
(returns a `:disk` entry whenever the transit file exists; never checks the
recorded filename — the ~3-line fork change), `:83` `with-cache`, `:127`
`load-when-missing` (no-ops silently for a namespace never linted — why
SCI-only definitions still need a prelude), `src/clj_kondo/core.clj:67`
`run!` (`:cache false` disables resolution; `:cache-dir` selects); clj-reload
`src/clj_reload/parse.clj:122` `dependees`, `:163` `topo-sort` (the
maintained shape of `reload-order`; its default `on-cycle` throws); datahike
`versioning.cljc:457` `commit-id`; `script/seon/fresh_operator.clj:1820`
`prepl-eval!` (the prepl client that already exists); `src/seon/instrument.clj:844-858`.

## 6. A fork is a branch pointer; reset from zero

A new cluster forks the published commit: a Datahike `branch!`, milliseconds.
Reset is the recovery for any schema breakage: down, destroy, republish from
zero, refork, start, adopt. Database data is disposable by ruling; nothing is
ever migrated.

**Data flow.** Fork: write one branch pointer, connect, carry the publisher's
projection onto the new value. Reset: the one complete analysis (the cold
case) and one transaction of the whole population. Fork is O(1); reset is
O(program) and is the only operation allowed to be.

**Current / Target.** Current: `src/seon/cluster/registry.clj:178` `branch!`;
fork of a cluster 0.34 s, landed (goals note §2b); the fixture fork
`test/seon/test_support.clj:946` `with-branched-database` 37.0 ms p50 versus
`:921` `with-fresh-database` 4,647.8 ms, because it calls Datahike's
`fork-database`, a whole-store copy, and rebuilds the projection (3,996 ms
of it). Reset measured 325 s on 2026-09-23, republish 260 s; the cold
publication's phases over two seconds are preparation 7.8, contract
compilation 12.9 + 9.5 + 2.6, final population compilation 13.2, the
transaction of 107,049 datoms 36.1, activation seal 3.4, branch-head readback
4.9 (data pack B1 §5). Target: from zero ≤ 60 s; every phase named by its
algorithm, never by "which path it took" (goals note §2b rows 1, 8; "a fork
is a branch pointer: milliseconds"); lanes B1, A1, A2.

**Reference code.** datahike `versioning.cljc:212` `branch!` (pointer; refuses
`:branch-already-exists`) versus `:550` `fork-database` ("copies every
konserve key from the source store into the target store" — never on a hot
path), `:734` `merge!`; `src/datahike/gc.cljc:83` `gc-storage!` (reachable
keys from branch heads; bytes of a deleted branch survive until GC);
konserve `src/konserve/gc.cljc:8` `sweep!`.

## 7. The projection, computed once and carried

The compiled Malli registry of every schema and contract — the projection —
is built where the program is published and CARRIED on the database value.
Everything else reads it: arming, call preparation, the writer's validator,
SCI acquisition, rendering. Nothing rebuilds it in running code.

**Data flow.** Built once per publication (proportional to changed
declarations through the lazy registry), attached to the value at
`carry-projection-state`, read by `carried-projection`. A value made from a
commit carries the publisher's generation. Recomputation happens only when
the program facts change.

**Current / Target.** Current: `src/seon/db.clj:1219` `carried-projection`,
`:249` `carry-projection-state`; `src/seon/schema.clj:454-503`
`projection-registry` (the landed idiom: compile serially once, then
`mr/fast-registry`); but `src/seon/schema.clj:2788` `projection-from-database`
rebuilds from rows at 43 `src/` call sites in 15 files (data pack A1 §4b),
costing 3,996 ms of the 4,648 ms fixture base and 248 ms per newly
materialized commit; `src/seon/instrument.clj:927` `apply!` derives a
whole-population projection per arming pass. Target: the seam returns the
carried value or a typed refusal; arming performs zero named-declaration
compilations for an unchanged generation (goals note §2b row 11, rows 10 and
12); lane A1. The A1/A2 boundary is `carried-projection`, which A1 reads and
A2 does not change.

**Reference code.** malli `src/malli/registry.cljc:11-22` `Registry` /
`fast-registry` (a sealed registry answers by `HashMap.get`, never by
compiling), `:97` `schema`; `src/malli/core.cljc:268` `-memoize` (Malli's own
per-schema cache — no second validator cache), `:2771` `entries` (keys and
`:optional` off the compiled node), `:2848` `from-ast`/`ast` (the canonical
normal form; keeps `{:closed false}` — normalize at the fingerprint site),
`:3118` `-instrument` (takes a compiled schema).

## 8. Contracts on every function, armed by the wrapper

Every function, private included, carries a complete Malli input and output
contract. One wrapper on the JVM Var root (and, through `bind-root!`, in the
SCI context) validates inputs, output and guards, and refuses a call whose
contract fails with a flat `:seon.error` value naming the function and the
offending argument. The same wrapper checks that a returned error satisfies
one of the schemas the output contract declares. Arming is what makes every
other guarantee in this document true at the seam.

**Data flow.** At arming: for each contracted Var, look the compiled contract
up in the carried registry, build the wrapper once, stamp what it was armed
for on the Var's metadata. Per call: validate, apply, validate. Re-arm only
when `current-wrapper?` sees a changed contract or referenced definition;
otherwise identity is retained. Proportional to changed contracts, never to
the population.

**Current / Target.** Current: `src/seon/instrument.clj:860` `arm-var!`,
`:734` `compiled-wrapper`, `:844` `current-wrapper?`, `:898`
`collect-contracts!` (walks `ns-interns`, private included), `:777-808` the
declared-union check on a returned error (landed `796a76314`);
`src/seon/sci/eval.clj:695` `install-function-contract!` binds the wrapper
into an SCI context. Per armed call today: rest-arg seq, a dynamic-var read,
a three-way scan of every argument for a projection, a `binding` push, a
cache lookup, then the validations — 1,157 ns against 180 ns unwrapped (data
pack C1 §1, §8). 1,570 armed Vars in the JVM against 5,191 functions; 16 of
3,161 private functions carry a contract; 352 private `seon.db` readers are
uncontracted (goals note §2a row 4). Target: the per-call argument scan and
binding go (A1); every function carries a contract and an uncontracted
identity is a positive finding at publication (ruling §1j); the first live
namespace-agent task class is contract coverage (owner, 2026-09-21).

**Reference code.** malli `src/malli/core.cljc:3118` `-instrument` (input /
output / arity dispatch with a `:report` seam — hand it our refusal
constructor), `:2771` `entries`; sci `src/sci/core.cljc:273` `bind-root!`,
`src/sci/impl/utils.cljc:362-379` (equal generation ⇒ mutate in place;
unequal ⇒ copy the Var into this env with the fork's generation — the base
Var is never mutated through a fork).

## 9. Errors as data

An error IS a map: a base (`:seon.error/at`, `/layer`, `/operation`,
optional `/message`) plus a domain error schema declared in the owning
resource. There is no kind stamp, no class marker and no general predicate;
the error's SCHEMA is its meaning, and a consumer branches on a required
member that no sibling in the callee's declared union shares. A function's
output contract names the exact error schemas it can return; the wrapper
refuses a returned error satisfying none. Recurrence identity is
content-derived and kindless: `seon.id/id` over layer, operation, the sorted
set of schema keys the observation satisfies, throwable class and top frame,
the violated expectation, the location path — never time, process or
offending bytes. Same hash = same bug: one root entity, occurrences as
components, one repeat count, one task, one notification. One dial: dev
panics at the seam, prod records the fact and continues; a database failure
is a system-down panic handled at the REPL.

**Data flow.** Constructed at the seam as a value; validated by the wrapper
against the declared union; recorded by `recording` → `commit-tx` as a
`:seon.error` entity keyed by signature with an occurrence component; the
offending value is an ordinary `result/e<id>` the agent can reach, shown
text capped by the value renderer, blob complete. Cost is per error.

**Current / Target.** Current: `src/seon/error.clj:200` `signature` (the D13
identity, landed), `:1683` `recording`, `:1753` `commit-tx`, `:1921` the
union derivation (its name carries a retired spelling), `:304` `diagnostic`
over `src/seon/error/refusal.clj:37` — 275 construction sites restating two
members already on the map (data pack B3 §4a); 14 hand-copied unions, 695
lines, six different member sets (§4b); 799 `:seon.error/kind` sites while
the attribute is absent from the live installed schema (§3a — a pure code
cut); 167 `;; debt:` inline guards. Target: rulings D3, D12, D13 and §1k,
§1o, §1q as stated above; the copies dissolve into the declared unions the
wrapper already checks; lane B3.

**Reference code.** malli `src/malli/error.cljc:44` `default-errors` (a data
table; the fork accretes the absent collection types), `:288`
`error-message` (ten-step fallback), `:374` `humanize` (mirrors the value's
shape, not a flat list); `src/malli/core.cljc:2659` `explain` (no caching —
build the explainer once), `:996` `-or-schema` (a failure against an
N-member `:or` carries every branch's problems); clojure
`src/clj/clojure/core.clj:4924` `ex-info`, `src/clj/clojure/core_print.clj:473`
`Throwable->map` (the frame shape we store); datahike
`src/datahike/db/transaction.cljc:321` (provenance rides `:tx-meta`, never a
domain datom).

## 10. Tasks

`seon.task` is the ONE entity for work an agent does: linked facts (subject
refs, tests that define done, errors, functions) plus an optional agent and a
budget. A "template" is the render pair plus the units the task's data
selects — never an entity or a registry. A trigger (a detector finding, a
recurring error, a red test, a user's message) resolves to a task identity AT
THE WRITER: an existing task's agent receives the occurrence as a wake; a new
one creates the task and its agent with the first turn open in the same
transaction. Done is a query written by settlement only: the cited tests
verify on the current reach digest, or the detector stops naming the
subject. A same-identity merge conflict opens a conflict task for `root`
carrying both sources and the basis. A conversation is DERIVED from
`seon.message` facts — no entity. Namespace responsibility is a
cardinality-many ref set on the namespace, many-to-many; it is context, never
the routing key.

**Data flow.** Detectors are queries over the program graph; each finding's
identity is `seon.id/id` over the detector symbol and the subject's installed
identity value, so two runs upsert one entity. Settlement is a transaction
function reading the task's linked tests and the detector's current answer.
Proportional to findings, never an entity per event.

**Current / Target.** Current: two lifecycles side by side — `seon.issue`
(`src/seon/issue.clj:554` `subject-id`, the identity idiom to keep; `:338`
`index-tx` over 240 markdown notes; `:950` `adopt-tx`) and `seon.plan`
(1,886 lines), reached from the turn loop at seven sites (data pack B3 §6);
`src/seon/task.clj` does not exist; `resources/seon/schemas/seon.ns.edn:28`
holds a single-valued responsibility ref under a retired spelling on 2 of
411 namespaces; `:seon.ns/agents` does not exist. Target: goals note §2d
rows 1–9 and rulings D1, D2, D6, T1, F4 as stated above; lane B3, with the
turn-loop call sites coordinated with B2.

**Reference code.** `src/seon/id.clj:55` (the one identity derivation; the
task id and the error signature are both `seon.id/id` over a sorted map);
`src/seon/turn.clj:465` `open-run-tx-call` (the idiom: the decision inside a
`:db.fn/call`, never a caller pre-read); datahike `src/datahike/db/transaction.cljc`
(transaction functions run inside the writer's serial loop).

## 11. The test system, in process

A test is a program fact resolved by identity, not a file. "Which tests reach
my change" is a query over `:seon.fn/calls` edges plus the last recorded
green basis; an agent runs exactly those, in the cluster's own JVM, on a
forked branch plus SCI fork, and the results are recorded as facts. An
unchanged, previously green request selects zero members and returns the
recorded result. Every test carries a bound (default 5 s) and FAILS when it
exceeds it; a longer bound must carry its reason and its number. The
platform tier — boot from zero and destructive drills — is the one
subprocess; `bin/test` is a launcher that starts a JVM and hands it a
request, nothing more.

**Data flow.** Selection: changed function identities (by content digest) →
reverse reach over stored edges → members lacking a green record at this
basis. Execution: one `with-database` fork (37 ms) per test, the armed
contracts of the cluster's same armed generation. Recording: one transaction of the
members' results on the branch they ran on. Proportional to the change and
its reach.

**Current / Target.** Current: the surviving path exists —
`src/seon/test.clj:1901` `check` → `:1595` `check-admission` → `:576`
`run-owned` → `src/seon/test/runner.clj:3145` `commit-results!`; `:61`
`changed-since-green`, `:840` `select`, `:2142` `verified?`;
`runner.clj:2362` `reach-memberships` (computed live; `:seon.test/reach` is
stored on 0 of 2,157 rows); `bin/test-check:43` already drives the live
cluster with no JVM. Beside it: `bin/test` 1,107 shell lines computing
selection and tally, a 682-line pool/checkout/exchange layer
(`runner.clj:3628-4307`), a verbatim copy of `arm.clj` (`runner.clj:1724-1848`),
and `runner.clj:369-371` letting a reason without a number keep the 5 s
default silently — 65 files declare 18.9 h of long bounds, 38+ of them
reason-only (data pack B4 §3–4). Target: goals note §2c (rows 1–9) and the
bound rule (§2b row 5); the platform tier is the one subprocess; hoisting the
repeated 46 lines/test of setup is the namespace agents' second task class;
lane B4.

**Reference code.** clojure `src/clj/clojure/test.clj:325` `report` (a
dynamic defmulti — reporters substitute, never reimplement), `:710`
`test-var`; kaocha `src/kaocha/type/var.clj:30-63` (running one var with
reporting, 34 lines), `src/kaocha/testable.clj:214-228` (a collection with
fail-fast, 15 lines), `src/kaocha/plugin/profiling.clj:17` (per-test
duration as a plugin — kaocha measures, never fails on time; the bound is
ours); datahike `versioning.cljc:212` `branch!`;
`test/seon/test_support.clj:946` (the 37 ms fork idiom to build on).

## 12. Profiling on the wrapper

We already wrap every contracted function, so profiling is two `nanoTime`
reads in that wrapper feeding a `LongAdder` count and total and a
`LongAccumulator` max per armed definition, keyed by (symbol, definition
content digest, branch). A flush on a bound transacts the aggregates as
facts with the symbol as a value; hot chains are a Datalog join over
`:seon.fn/calls`; a finding resolves to a task identity (ruling D2) and an
agent profiles and fixes it. A function an agent redefined on a fork has the
same symbol and a different digest, so its samples land on its own version.
Java Flight Recorder is the independent check.

**Data flow.** Per call: ~40 ns of sampling on a 1,157 ns armed call (data
pack C1 §8; 3.5 %). Per flush: one transaction of the counters that moved,
replacing attributes on aggregate entities — never an entity per event. Per
analysis: one query. Contention with the database is the flush's alone.

**Current / Target.** Current: nothing samples; the wrapper's invocation path
is `src/seon/instrument.clj:880-889` and the arm-time closure `:864-878`
stamps seven metadata keys, none a definition digest; the row digest is the
file's (§4). The one measurement: unwrapped 179.79 ns, armed 1,156.88 ns,
sampled-unwrapped 219.88 ns, `nanoTime` 13.42 ns; 1,570 armed Vars. Target:
as stated (owner, 2026-09-21; synthesis §8.5); lane C1, after A1 (the clean
invocation path) and B1's per-declaration digest; the schema resource for the
aggregate is C1's to declare.

**Reference code.** `src/seon/instrument.clj:860-896` (the hook point A1
leaves clean); `src/seon/schedule.clj:818` `schedule-step` and `:44-70` the
root maintenance rows (periodic work at one-minute granularity — the flush's
existing home); `src/seon/db.clj:4283` (branch from a database value),
`:460-462` (from a connection); JDK `java.util.concurrent.atomic.LongAdder`
/ `LongAccumulator` (not vendored — read the javadoc; `sum` is not an
atomic snapshot); `clojure.lang.Compiler/demunge` as used at
`src/seon/error.clj:505-525` (JFR frames → symbols).

## 13. Namespace agents on an isolated branch and SCI fork

A task's agent works on its own Datahike branch AND its own SCI context.
Candidate writes leave the shared branch unchanged; the changed program
entities since the fork basis are a pure projection. The merge gate runs
exactly the tests whose reach changed, armed; accepted entities replace the
shared definitions through `exact-replacement-tx`; a same-identity conflict
becomes a conflict task for `root`. Write-back writes accepted definitions to
their files by exact span, gated, and the ordinary indexer reproduces the
merged entities byte for byte from the written files. Agents never shell out
to change their own system; adoption, tests and collection are declared
requests.

**Data flow.** Fork: a branch pointer plus `sci/fork` (0.0015–0.02 ms warm).
Work: the agent's evaluations on its branch. Merge: the projection of changed
entities → reach → selected tests → one acceptance record → one replacement
transaction. Write-back: per accepted declaration, one span replacement in
one file, then the same publication path as any edit. Proportional to the
task's changes.

**Current / Target.** Current: the candidate install path is production
reachable — `src/seon/sci/eval.clj:3181` `install-candidate-function!`,
`:3227` `accept-candidate!`, `:3320` `evaluate-candidate`;
`src/seon/program.cljc:1042` `exact-replacement-tx`; `src/my/program.clj:278`
`breaks`, `:152` `callers`, `:167` `tests-reaching` (the refactoring reads);
`bin/seon init NAME` forks a published commit; no merge gate, no acceptance
record, no write-back, no span. Target: goals note §2f rows 1–4 and rulings
C2, D3 (09-17), D5, D6, R5; first live task class contract coverage, second
hoisting repeated test setup (owner, 2026-09-21); lanes B2 (fork), B3 (task),
B4 (gate), B1 (write-back through the indexer).

**Reference code.** datahike `versioning.cljc:212` `branch!`, `:734` `merge!`;
sci `src/sci/core.cljc:345` `fork` (a new env atom over the same namespace
map plus a fresh generation; no per-Var allocation), `:260` `intern`;
clj-kondo `impl/analysis.clj:87-115` (the span to write back to); rewrite-clj
`src/rewrite_clj/parser.cljc:34` `parse-string` (whitespace-preserving
rewrite of one form); edamame `src/edamame/core.cljc:9` `parse-string`
(source fidelity with locations).

## 14. Where the rest is

[agent-runtime.md](agent-runtime.md) owns the turn loop; [ui.md](ui.md) the
human surface; [reference-code.md](reference-code.md) the twenty vendored
repositories; [data-modeling-guide.md](data-modeling-guide.md) every
modeling ruling. Implementation detail — commit order, REPL forms, deletion
lists, numbers — is the lane specs' under `docs/prds/agent-platform/plan/`.
