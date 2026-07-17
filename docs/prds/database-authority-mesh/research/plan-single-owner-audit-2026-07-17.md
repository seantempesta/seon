---
title: Plan single-owner audit
type: research
status: completed
tags: [research, database, agent, flow]
---

# Plan single-owner audit

## Decision

The current dirty `my.plan` prototype must not be committed as an integrated
consumer cut. Its pure row transformations are useful evidence, but its
transport still implements the removed coordinate and compact-envelope
contract, its general acquisition transfers far more data than most plan
operations require, and the complete synchronous local-Datahike plan system
remains reachable beside it.

Retain one public `my.plan` API, one ordinary database-value acquisition
convention, operation-specific bounded authority queries, one document
compiler, and pure formatters over already-acquired ordinary data. Delete the
coordinate path, ambient connection reads, compact database envelopes,
per-node HTML database calls, and whichever duplicate plan derivations no
longer have consumers. Do not create a `v2`, compatibility namespace, second
renderer, or plan-owned cache.

This is a current-source audit at Seon `daf1b740` plus a shared dirty worktree.
It makes no claim that the dirty plan work is implemented, integrated, or
green. This audit changed no source, tests, or roadmap.

## Dependency ledger

| Dependency or owner | Selected source | Constraint |
|---|---|---|
| Seon database facade | `src/seon/db.cljs:560-567,594-702,709-724,848-888` | `db` returns one ordinary immutable database value. `execute-many` resolves one explicit, inherited, or current value and returns only `:seon.db/results`. `transact!` accepts `:seon.db/db` and `:seon.db/expected-db`, then returns a native transaction report or direct error value. |
| Seon identity allocation | `src/seon/db/id.cljc:1332-1387,1436-1465` | `allocate!` resolves one database value and runs a pure synchronous transaction builder. The builder may return `:seon.db/expected-db`; allocation returns the native report plus allocated IDs. |
| First-party run pattern | `src/seon/agent/run.cljs:273-307` | A request accepts optional `:seon.db/db`, otherwise awaits `db/db` once, then performs all reads against that exact value. |
| First-party grouped-read pattern | `src/seon/agent/message.cljs:128-174` | A bounded operation selects an index or query shape appropriate to the question, groups independent members, and applies a pure transformation to ordinary results. It does not fetch the whole domain. |
| Maintained Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | The JVM authority owns immutable database values, recursive Datalog, indexed pull/query execution, exact-snapshot result caching, shared in-flight work, propagation across transactions, and connection-generation release. |
| Datahike query cache | `reference-code/datahike/src/datahike/query.cljc:2406-2587,4347-4398` | Plan structures cache independently from results. Completed exact-value hits avoid promises and request identities. Results are bounded by snapshot count and structural weight. |
| Datahike release | `reference-code/datahike/src/datahike/connector.cljc:483-498` | The final connection reference closes query-cache ownership, evicts completed snapshots, and detaches in-flight callers. `my.plan` needs no cache lifetime mechanism. |
| Plan public and internal owners | `src/my/plan.cljs`, `src/my/plan/internal.cljs`, current dirty worktree | The public function names, namespaced maps, schemas, plan facts, document compiler, prompt, escalation, and HTML twin are the behavior to preserve while replacing their database access. |
| Plan semantic proof | `test/my/plan_test.cljs` at HEAD plus the current dirty replacement | HEAD contains 47 behavioral tests. The dirty replacement contains four public tests and duplicates two pure cases in an untracked internal test. Compatibility proof must preserve behavior rather than preserve the local-Datahike fixture. |
| Render and loop consumers | `config/system.edn:237-243`, `src/seon/agent/loop.cljs:458-468`, `src/seon/render.cljs`, `src/seon/execution/runtime.cljs` | The configured plan block has AI and HTML twins, and the loop invokes `maybe-consult!`. These are real consumers that must move before synchronous helpers are deleted. |

The relevant ClojureScript rule is explicit `^:async` plus `await`. Public
`seon.db/query`, `pull`, `entity`, `execute-many`, `transact!`, and `db.id/allocate!`
are asynchronous in the pod. A formerly synchronous helper cannot call them
and still be a pure renderer.

## Current dirty prototype rejection

### Removed database contract remains embedded

`src/my/plan.cljs:296-374` adds a generic plan-row acquisition, but it:

- requires `:seon.db/coordinate` in the `execute-many` response;
- returns that coordinate as private acquisition state;
- builds writes with `:seon.db/expected-coordinate`; and
- never captures or supplies an ordinary `:seon.db/db` value.

The current facade contradicts every one of those assumptions:

- `src/seon/db.cljs:848-883` returns only `{:seon.db/results [...]}` from a
  successful `execute-many`;
- `src/seon/db.cljs:594-605` names the write fence
  `:seon.db/expected-db`; and
- `src/seon/db.cljs:656-664` returns native `:db-before`, `:db-after`,
  `:tx-data`, `:tempids`, and optional `:tx-meta`.

The prototype also branches on `:seon.db/ok?` and reads a nested
`:seon.db/error` in `src/my/plan.cljs:507-516,697-701,787-803` and
`src/my/plan/internal.cljs:100-108,938-956`. Current failures are direct
ordinary error values carrying `:seon.error/message`.

The dirty tests mask this mismatch. `test/my/plan_test.cljs:8-15,36-49` imports
the retired coordinate namespace and fabricates coordinate plus compact
transaction envelopes. Its mutation test at lines 87-104 asserts
`:seon.db/expected-coordinate`, so it proves the removed protocol rather than
the current facade.

### The generic row acquisition is the wrong performance seam

`src/my/plan.cljs:326-363` permits five million work units, 200,000 results,
and two megabytes for the row member used by nearly every public operation.
That replaces indexed authority-side questions with whole-agent or whole-plan
transfer and repeated ClojureScript walks.

This is disproportionate for:

- `done!`, `reopen!`, and `status`, which need one selected step plus derived
  status where applicable;
- `needs!` and `move!`, which need the selected step and named targets;
- `active!`, which needs the selected step and currently active siblings;
- `drop!`, which needs one recursive descendant-ID result;
- `plan!`, which needs an agent existence and duplicate-root check; and
- `next`, `position`, and `list-open`, which already have direct indexed or
  rule-backed query shapes.

Identical queries at one committed database value are already shared by
Datahike's cache and single-flight owner. Fetching a large generic row set into
each Bun process discards that architectural advantage and creates repeated
serialization, allocation, and local computation.

### Two complete plan systems remain

The dirty work adds row versions of readiness, roll-up, status, ancestry,
tree, forest, and open-step derivation at
`src/my/plan/internal.cljs:114-260`. The original database-query versions
remain at lines 338-504 and still serve prompt, escalation, and HTML consumers.

Additional superseded paths remain reachable:

- `status-of` and `unresolved-step-refs` still perform synchronous database
  reads at `src/my/plan/internal.cljs:76-98`;
- `retract-subtree!` still dereferences `@db/*conn*` and expects the old write
  envelope at lines 938-956;
- `plan-body` still calls the local database derivations at lines 1827-1858;
- `build-forest` performs one query followed by one pull per step at lines
  1944-1980;
- HTML then calls `rollup`, `blocked?`, and `ready?` repeatedly per node at
  lines 1989-2117; and
- `plan-block-html` falls back to `@db/*conn*` at lines 2119-2163.

The staged prompt acquisition is also not migrated. It accepts, checks, and
returns coordinates throughout `src/my/plan/internal.cljs:1570-1695`, despite
the settled ordinary database-value contract.

`maybe-consult!` remains another ambient synchronous branch at
`src/my/plan/internal.cljs:1267-1316`. It recomputes escalation, planner,
message history, and subtree data through the old helpers before sending a
message.

### Errors become false empty state

The dirty public `next` path converts a database failure to `[]`, while `tree`
converts one to `nil`. Empty plan data and unavailable database data are
different states. A database failure must remain a direct error value or an
established `my.plan` failure response; it must never look like a successful
empty plan.

### Semantic coverage was deleted, not ported

HEAD `test/my/plan_test.cljs` contains 47 tests covering:

- generated facts, agent scope, resumption, idempotency, and errors;
- plan creation, dependencies, moves, active focus, blocked status, and drops;
- bounded prompt acquisition and constant-size rendering;
- nested roll-up plus AI/HTML agreement;
- document round-trip and every reconcile ambiguity or immunity rule; and
- escalation detection, reset, planner selection, once-per-episode consult,
  and no-planner behavior.

The dirty `test/my/plan_test.cljs` replaces those with four tests. The untracked
`test/my/plan_internal_test.cljs` adds two tests that duplicate two of the four.
The local embedded Datahike fixture must go, but its semantic assertions are
the compatibility specification and must be ported to pure tests, facade
contract tests, and a small real-writer proof.

## One retained plan design

### Public contract

Retain the existing public names and general meanings:

- mutations: `step!`, `plan!`, `active!`, `done!`, `reopen!`, `needs!`,
  `move!`, `drop!`, and `reconcile!`;
- reads: `next`, `position`, `tree`, `document`, `status`, and `list-open`; and
- the existing namespaced request and response maps.

Every applicable request accepts optional `:seon.db/db`. Omission acquires the
cached current database value once. Supplying a value pins every read in the
operation; a mutation derived from it uses that identical value as both
`:seon.db/db` and `:seon.db/expected-db`.

The retained internal convention is:

```clojure
(let [database (or (:seon.db/db request) (await (db/db)))
      acquired (await
                 (db/execute-many
                   {:seon.db/db database
                    :seon.db/members operation-specific-members}))]
  ;; Validate positional member results.
  ;; Apply pure domain transformations.
  ;; Write with database plus expected-db when the read authorizes a write.
  )

```

This is one mechanism without forcing every function through one oversized
query. Query shapes remain specific to the operation.

### Operation-specific authority work

| Operation | Bounded authority projection |
|---|---|
| `done!`, `reopen!` | Pull the selected step's ID and status. |
| `status` | One rule-backed status/roll-up query for the selected ID. |
| `needs!`, `move!` | Pull or query the selected step and referenced target IDs in one grouped request. |
| `active!` | Selected step plus the active IDs owned by the same agent. |
| `drop!` | One recursive `descendant` query returning IDs only. |
| `step!` | Validate parent and needs targets only when present; perform allocation and the domain write in one authoritative commit. |
| `plan!` | Agent existence plus same-title open-root check, followed by one allocation commit. |
| `next` | Existing bounded ready query, sorted by creation time. |
| `position` | Active or first-ready selection plus ancestor path and root roll-up at one database value. |
| `list-open` | Exact unfinished-step projection, scoped by agent unless `:my.plan/all?` is true. |
| `tree`, `document` | Selected root or agent forest rows once, followed by pure assembly and optional done pruning. |
| `reconcile!` | The exact open forest required by the existing compiler, acquired once. |
| AI block | Retain bounded staged active/ready/recent, selected ancestry/roll-up, and conditional escalation queries, all at one database value. |
| HTML twin | Acquire the selected whole forest plus all required derived signals in one bounded grouped request, then perform zero database calls while formatting. |
| `maybe-consult!` | Reuse the same bounded escalation acquisition, include the subtree/message-history members only when flagged, and send through the existing message owner at the same database value. |

### Retained pure transformations

Retain one implementation of each of these:

- schema-derived key validation;
- document traversal and normalization;
- identity resolution;
- `compile-plan` and `compile-reconcile`;
- one cycle-safe forest assembly over already-acquired rows;
- open-document pruning and round-trip shaping;
- `wedge` over already-acquired ordered eval rows;
- prompt section formatting; and
- HTML formatting over a complete ordinary plan projection.

Readiness, blocked status, and recursive roll-up have to have one semantic
owner. The retained default is the existing Datalog rules executed in the
authority, because this shares query work and indexes across Bun processes.
Delete their row-walk equivalents after the HTML and document consumers have
the authority projections they require.

`my.plan/rules` currently re-exports `my.plan.internal/rules`. No repository
consumer uses the public alias. Before deleting it, query persisted current
program source for `my.plan/rules`; if none exists, retain only the private
rule owner. If persisted authored source uses it, make an explicit public API
decision rather than silently keeping two named owners.

## Retained and superseded owners

| Behavior | Retained owner | Superseded owner |
|---|---|---|
| Public plan API and schemas | `my.plan` | No compatibility or versioned namespace. |
| Database selection | One optional/current ordinary `:seon.db/db` value | Coordinate maps, ambient `db/*conn*`, connection arguments. |
| Grouped reads | `seon.db/execute-many` with operation-specific members | Generic 200,000-row acquisition and per-node reads. |
| Mutation order and fencing | JVM writer plus `:seon.db/expected-db` | Compact `ok?` transaction envelopes and expected coordinates. |
| Recursive plan semantics | One rule set executed by Datahike | Parallel database-query and full-row semantic implementations. |
| Document mutation | Existing pure compiler | Any direct ad hoc reconcile/write path. |
| AI rendering | One bounded acquisition plus pure formatter | `plan-body` over local Datahike. |
| HTML rendering | One grouped acquisition plus pure formatter | `build-forest` plus one pull per step and repeated per-node queries. |
| Escalation | One database-value acquisition plus pure wedge/formatting | Ambient `maybe-consult!`, synchronous provider and message-log reads. |
| Cached computation | Datahike exact-value result cache and single-flight | Any Bun-local plan result cache. |

## Impact-ranked implementation order

1. **Replace the database contract in place.** Capture one ordinary database
   value, pass it explicitly to `execute-many`, fence derived writes with
   `expected-db`, and consume native reports or direct errors.
2. **Delete the generic acquisition design.** Give each public operation the
   smallest indexed, pull, or recursive query shape that answers its question.
3. **Restore the compatibility specification.** Port the 47 semantic cases to
   pure compiler tests, facade-stub tests using current result shapes, and a
   narrow real-writer proof. Do not restore the embedded CLJS Datahike owner.
4. **Migrate public mutations and reads.** Delete each old helper as its final
   caller moves; never leave both implementations green.
5. **Migrate the bounded AI block.** Replace its coordinate field with the
   ordinary database value and delete `plan-body` when the configured AI
   consumer uses the new path.
6. **Migrate escalation and consult.** Reuse the plan acquisition and existing
   message transaction owner; delete the ambient path.
7. **Migrate HTML atomically.** Acquire once outside formatting, preserve the
   existing UI behavior, and delete every per-node database call plus the
   ambient fallback.
8. **Run a deletion audit.** Remove coordinate imports, compact envelope
   branches, duplicate row semantics, obsolete tree/query helpers, duplicate
   tests, and the public rules alias if current program data proves it unused.
9. **Graduate with focused, integrated, live, and cache evidence.** Optimize
   query shapes only after behavior is correct and measured.

This order targets architecture-level wins: shared computation, bounded wire
data, no duplicate live database owner, and less code. Artifact size and local
micro-optimizations do not displace it.

## Shortest falsifiers

### Removed-contract static gate

After the cut, this command returns no plan-owned matches:

```bash
rg -n 'expected-coordinate|::db/coordinate|@db/\*conn\*|:seon\.db/ok\?|:seon\.db/error' \
  src/my/plan.cljs src/my/plan/internal.cljs test/my/plan_test.cljs \
  test/my/plan_internal_test.cljs

```

### One-value and native-result contract

Stub `db/db`, `db/execute-many`, `db/transact!`, and `db.id/allocate!` with the
current facade shapes. Assert:

- an omitted database is acquired exactly once;
- an explicit database causes no acquisition;
- every grouped member receives the identical database value;
- a derived write carries that value under both `:seon.db/db` and
  `:seon.db/expected-db`;
- native `:db-after` means success; and
- direct `:seon.error/message` remains an error and never becomes empty data.

### Bounded-work contract

Instrument only the database facade and use representative large plan rows:

- `done!`, `reopen!`, and `status` issue one bounded acquisition and never
  request the entire agent plan;
- `drop!` returns recursive IDs without pulling each entity;
- AI rendering stays constant-size for a 1,000-step plan; and
- HTML performs one grouped acquisition and zero database calls during pure
  formatting.

### Semantic compatibility

The focused gates are:

```bash
bin/test-cljs --test=my.plan-internal-test
bin/test-cljs --test=my.plan-test
bin/test-cljs --test=seon.agent-loop-test

```

The port must retain assertions for agent scope, identity allocation,
idempotency, dependencies, moves, focus demotion, recursive roll-up, drop,
document round-trip, reconcile ambiguity, done-step immunity, prompt bounds,
AI/HTML agreement, wedge reset, consult-once, and no-planner behavior.

### Shared-computation evidence

Use `seon.db/query-with-evidence` for an exact plan query twice at the same
database value and then concurrently from two sessions. Assert a completed
cache hit or one shared in-flight computation. Commit an unrelated attribute
and verify eligible plan results propagate; commit a referenced plan attribute
and verify invalidation. This confirms that caching remains a Datahike concern,
not a new plan mechanism.

### Live behavior

On the default cluster:

1. create a plan with nested children and a dependency;
2. activate, complete, reopen, move, add a need, and drop a subtree;
3. round-trip `document` through `reconcile!` and then apply a real edit;
4. verify the AI plan block and HTML surface agree after each committed
   transaction;
5. restart the pod and verify the same plan with no process-local plan state;
   and
6. observe no unhandled rejection, render exception, or database-coordinate
   compatibility path.

## Acceptance proof

The plan cut is accepted only when all of the following are true:

- one `my.plan` public API remains with no `v1`, `v2`, compatibility, or
  parallel namespace;
- every plan database operation uses one explicit or current ordinary
  database value;
- every read-derived mutation is fenced with that exact value;
- no plan-owned source or test references coordinates, compact database
  envelopes, ambient connections, or synchronous remote reads;
- each public operation has a bounded query/pull shape appropriate to its
  question;
- AI and HTML perform database acquisition outside their pure formatters;
- HTML performs no per-node database I/O;
- Datahike remains the only query cache and shared-computation owner;
- the original semantic cases are preserved on the new boundary;
- focused CLJS, loop, writer/protocol, and relevant render tests pass;
- the default cluster proves create-to-restart behavior live; and
- source deletion leaves no unreachable duplicate plan implementation.

## Protected ownership

An implementation lane for this audit may own only:

- `src/my/plan.cljs`;
- `src/my/plan/internal.cljs`;
- `test/my/plan_test.cljs`; and
- `test/my/plan_internal_test.cljs`.

The current shared worktree also contains unrelated edits in `src/my/blob.cljs`,
`src/seon/agent/ctx/namespaces.cljs`, `src/seon/agent/home.cljs`,
`src/seon/db/internal.cljs`, `src/seon/eval.cljs`,
`src/seon/repl/autocomplete.cljs`, `src/seon/test/runner.cljs`,
`src/seon/web/reactive/call.cljs`, `src/seon/web/serve.cljs`, and their tests.
Those paths are protected from this plan cut. `locks/` is also unrelated and
must remain untouched.
