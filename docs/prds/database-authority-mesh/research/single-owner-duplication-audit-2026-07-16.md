---
title: Single-owner duplication audit
type: research
status: completed
tags: [research, prd, database, agent, web]
---

# Single-owner duplication audit

## Result

The checkout does not contain a second `seon.db` facade or a remaining
`seon.db.replica` namespace. The replacement has already claimed the public
name. The dominant duplication is therefore a half-completed ownership cut:
the one asynchronous authority facade exists, while production consumers,
rendering helpers, program publication, and the CLJS test corpus still encode
the deleted local-Datahike behavior.

The high-impact order is:

1. remove every production `db/*conn*` and removed-facade assumption by
   migrating each complete behavior to the existing asynchronous `seon.db`;
2. make authority admission the only compiled-program and native-schema
   reconciler, then delete the pod boot reconciler;
3. make the execution child the only owner of agent eval and page rendering,
   retaining `seon.render/render` only as a pure recursive formatter over
   already acquired data;
4. replace or delete the 50 CLJS test namespaces that still install a local
   connection, rather than restoring their missing helper; and
5. remove active compatibility routes, artifact readers, and legacy process
   retirement only after their durable inputs have been regenerated or
   explicitly retired.

There is one Datastar feed registry, not two. Its remaining problem is a hybrid
invalidation contract: it calls removed database helpers and expects the old
listener acknowledgement. Strengthen that registry in place; do not build a
second feed.

## Dependency ledger and method

| Owner or dependency | Inspected source | Constraint used |
|---|---|---|
| Seon architecture | `docs/seon/architecture/architecture.md` and `src/seon/AGENTS.md` | One database facade, one eval owner, one renderer, one lifecycle and one test surface per boundary. |
| Recovery program | `docs/prds/database-authority-mesh/roadmap.md` and [[system-recovery-graduation-plan-2026-07-16]] | Correctness and deletion precede tuning; no compatibility namespace or generation-suffixed implementation is allowed. |
| Seon database facade | `src/seon/db.cljs`, current shared worktree | `db`, reads, writes, temporal selection, interests and release are asynchronous operations over ordinary database maps. No local connection public var exists. |
| Datahike | `reference-code/datahike` at the selected `670cd1ada40462cb5927f0dc687f6b3a95f9e13f` line recorded in the roadmap | The JVM owns connections, indexes, immutable database values, query sharing and serialized writes. |
| Bun | `reference-code/bun` at `be77b652884b16a103cfaa4af3c1102f72f2dcd3` | The supervised execution child, not an in-pod interpreter, is the isolation boundary. |
| Babashka process | `reference-code/babashka-process` at `16a84e0a` | The operator owns outer process lifecycle; that is distinct from agent run lifecycle. |

The audit searched `src/`, `script/`, `bin/`, and `test/` for namespace and
function generations, `legacy`, `compat`, `remote`, `local`, `replica`,
`replay`, database constructors, `db/*conn*`, eval/compiler entrypoints,
render entrypoints, feed/listener owners, and test launchers. A name is counted
as a duplicate only when it preserves a competing implementation or behavior.
Ordinary transition variables such as `old-projection`/`new-projection`, local
lexical values, external API paths, and historical PRD citations are excluded.

## Active duplicate and superseded mechanisms

### 1. Authority database access versus local-connection consumers

**Surviving owner:** `src/seon/db.cljs`.

The facade owns one persistent session and cached current ordinary database
map. Its public reads and writes are asynchronous (`db`, `query`, `pull`,
`pull-many`, `entity`, `installed-schema`, `execute-many`, `index-page`,
`listen!`, and `transact!`). It defines no `*conn*`, `head-coordinate`,
`at-coordinate`, `entity-lazy`, or synchronous Datahike-value API.

**Superseded production consumers:** direct search still finds ambient local
database use in these owners:

- agent control: `src/seon/agent.cljs`, `agent/run.cljs`,
  `agent/turn.cljs`, `agent/lifecycle.cljs`, `agent/schedule.cljs`,
  `agent/home.cljs`, and `derive.cljs`;
- eval and support: `src/seon/eval.cljs`, `ai/typeahead.cljs`,
  `repl/autocomplete.cljs`, `diffusion/retrieval.cljs`, and
  `diffusion/oracle.cljs`;
- rendering and web: `src/seon/render.cljs`, `render/default.cljs`,
  `render/canvas.cljs`, `render/chat.cljs`, `web/serve.cljs`,
  `web/reactive/call.cljs`, and `agent/debug.cljs`; and
- toolkit: three remaining dereferences in `src/my/plan/internal.cljs`.

These are not another usable facade; many now call vars that no longer exist.
They are a source-reachable description of the old system and the main reason
the complete CLJS application cannot compile and run.

**Safe deletion dependency:** migrate one complete behavior at a time. Capture
one current `:seon.db/db` map at its asynchronous boundary, group known reads,
keep inner derivation pure, and transact once. Delete the ambient fallback and
its obsolete arity in that same behavior cut. Do not reintroduce `*conn*`, an
in-memory test connection, or a `remote` facade to make callers compile.

**Impact/order:** first. Every later renderer, lifecycle, and test proof
depends on this cut.

### 2. Two compiled-program reconciliation owners

**Surviving owners:** `seon.client/index-core!` and `index-schemas` build the
one compiled desired population; `seon.db.program/compile-tx-data` computes the
one exact authority-side delta; database ensure/open commits it before
admission.

**Duplicate owner to remove:** `src/seon/client.cljs` still contains
`install-runtime-schema!`, `acquire-core-program!`,
`compile-core-program-tx`, `core-program-tx`, `commit-core-program!`,
`seed-core!`, and `boot-seed!`. `start-runtime-impl!` separately opens the
session, performs boot writes, publishes the committed projection, recovers,
and creates agents. Meanwhile `src/seon/db/program.cljc` now implements the
same desired-versus-current program delta for authority initialization.

This is true overlapping ownership, not merely shared pure code: schema and
program admission can currently be decided at both pod boot and authority
ensure/open.

**Consumers:** `start-runtime!`, Shadow reload publication, state/index-core
tests, test seed helpers, and runtime admission/instrumentation.

**Safe deletion dependency:** finish fresh/full, partial/exact-delta, and
converged/no-write ensure/open proof. The open response must name the admitted
database value, complete schema validation must precede the commit, and a
failed initialization must not publish a runtime. Then delete the pod's
schema/program transaction and stale-retry loop. Retain the desired-data
builders, not their second commit owner.

**Impact/order:** second, immediately after the authority initialization
contract settles and before more consumers are migrated.

### 3. Page/canvas rendering through both the child and the old local path

**Surviving owner:** `seon.execution.runtime/render-agent-view!` in the
supervised child, normalized by `seon.render.surface` and formatted for the web
host by `seon.ui.agent-view`. `seon.execution.runtime/render-prompt!` similarly
owns prompt computation at one database value.

**Duplicate owner to remove:** `seon.render/render-agent-canvas`,
`seon.render/slot`, the local pull-based canvas state in
`seon.render.canvas`, and `seon.render.sci` still form a second page/canvas
execution path. The old entrypoint has no production caller; its direct
consumers are the old render/canvas and context tests. The execution child
already builds independent surfaces and calls selected render functions with
ordinary input.

**What survives from `seon.render`:** the guarded recursive `render`/`block`
formatter remains useful for transcript and entity conversion after all
database acquisition has moved outside it. Namespace identity alone is not a
reason to delete that pure formatter.

**Safe deletion dependency:** prove root and agent pages, canvas functions,
prompt twins, and a hung authored renderer through
`render-agent-view!`/`render-prompt!`; make their core formatters consume one
grouped ordinary-data acquisition. Then delete `render-agent-canvas`, local
slot/canvas reads, SCI invocation, and their implementation-specific tests in
one cut.

**Impact/order:** third. This removes a second interpreter, a second timeout
model, local database reads, and a large cognitive branch.

### 4. Child eval versus pod-global compiler publication

**Surviving owner:** one `cljs.js` compile state per supervised execution child,
prepared through `seon.eval/load-authored-program!` and invoked by
`seon.execution`. The `seon.eval` namespace remains the compiler/evaluator
library; ownership is determined by which process retains its state.

**Duplicate owner to remove:** `seon.repl/!compile-state`, the projection and
instrumentation half of `seon.runtime.admission`, and publication calls from
`seon.client` and `seon.eval` retain a pod-global compiler/program generation.
That is parallel runtime state even though it reuses the same evaluator code.
The simple admission open/closed gate may remain process-local, but it should
not retain a second executable program projection after children own eval.

**Safe deletion dependency:** prove definition persistence within one child,
isolation between two children, whole-namespace reconstruction after child
death, authored eval/test execution, and hot program-digest replacement. Move
any remaining repair/autocomplete metadata acquisition to ordinary program
data before deleting the global compile state and projection publication.

**Impact/order:** third alongside rendering, after authority initialization.

### 5. A separate Node `worker-eval` execution service

**Surviving owner for Seon agent code:** the same supervised Bun execution
child and `seon.eval` library described above.

**Duplicate owner:** `src/seon/worker_eval.cljs` defines another stateful
`cljs.js` compiler with Node `vm.runInThisContext` timeout semantics.
`shadow-cljs.edn` publishes it as `:worker-oracle-eval`; downstream Python in
`src-diffusion/` and scoring code in `src-inspect-ai/` launch it. It is not a
production pod fallback, but it is an active second eval service in core
`src/`, uses Node-specific process semantics, and duplicates bootstrap,
classification, session, timeout, and compiler-state behavior.

**Safe deletion dependency:** decide whether the diffusion oracle remains a
supported downstream feature. If yes, make its eval request use the canonical
execution child protocol or move the entire specialized worker to the
downstream repository. Preserve pure repair-candidate functions in their real
owner. If no current maintained gate launches it, delete the Shadow build and
core worker together.

**Impact/order:** after the running core system. It is real duplication but is
not on the current startup critical path.

### 6. One Datastar registry with stale local-era invalidation

**Surviving owner:** `src/seon/web/datastar.cljs` has one normalized
subscription registry, one shared render-in-flight owner, one coalescer, one
database interest, and one SSE fanout. `open-agent-feed!` is an adapter onto
that registry, not a second feed implementation.

**Stale behavior to replace in place:** `register-listener!` expects listener
registration to yield a database coordinate, while canonical `db/listen!`
returns its public key. Full/change render functions also call removed
`db/head-coordinate`, and comments still describe replaying observed reads.
`web/serve.cljs` retains `:seon.db/read-replayable?` projection and removed
`db/at-coordinate` calls.

**Safe deletion dependency:** settle one atomic listen acknowledgement plus
current-database/resynchronization rule. Render a relevant commit once at its
`db-after`, fence stale child completion, and retain selective dependency
invalidation. Then delete old operation-capture/read-replay evidence and the
stale coordinate helpers. Do not add a parallel authority-event feed.

**Impact/order:** after the child render path is green, before browser live
proof.

### 7. Test architecture still recreates the deleted database owner

**Surviving surfaces:** `bin/test-cljs`, `bin/test-writer`, and
`bin/seon test operator`. CLJS behavior tests use the existing authority test
session; Datahike internals remain in writer tests.

**Duplicate test system:** direct search finds:

- 33 CLJS test namespaces calling the now-absent
  `seon.client/open-agent-conn!`;
- 34 CLJS test namespaces requiring `datahike.api`; and
- 50 CLJS test namespaces referring to `db/*conn*`.

The overlap is intentional evidence: these tests construct a second embedded
database, install schemas, mutate a root var across Promises, and exercise
local lazy-value behavior instead of the production protocol. Restoring
`open-agent-conn!` would recreate the architecture solely to satisfy tests.

**Safe deletion dependency:** preserve pure transformation assertions by
passing ordinary rows. Move writer semantics to focused CLJ tests. Run public
CLJS database behavior through the existing controlled authority session.
Delete claims whose only subject is connection identity, local entity
laziness, replica replay, SCI timeout fallback, or removed arities.

`bin/test-cljs` also hard-codes `node out/test/test.js` and records
`node-seconds`/`node-exit`. Select the operator's one JavaScript runtime after
the Bun conformance gate; Node may be a separately named benchmark command,
not an implicit second maintained gate.

**Impact/order:** convert each cohort with its owning behavior, then run one
complete frozen-source gate. Do not defer all test conversion to a final
mechanical pass.

### 8. Active compatibility routes and process/artifact generations

These are lower-impact but are genuine alternate paths, not historical prose.

- `src/seon/web/router.cljs` serves both the canonical seeded
  `/agent/{id}/call` and a static flat `/call` explicitly labeled
  back-compat. The same handler does not make the route duplicate harmless:
  it preserves two public invocation addresses. Delete `/call` after generated
  hiccup and any downstream client use only the agent-scoped route.
- `script/seon/dev/artifact.clj` actively validates manifest schemas
  `v2` through `v5` and upgrades `v1` while reading. The current writer emits
  only version 5. Regenerate or delete project-local cached manifests, prove
  the operator rebuilds from absence, then retain one manifest schema and one
  reader.
- `script/seon/dev/process.clj` actively recognizes process records with no
  containment owner, reports `legacy-live`, and uses
  `retire-live-legacy!` to hard-kill their process group. Once the normal
  operator has retired all such records and a clean-absence start is proven,
  delete the legacy status, grace/config remnants, retirement branch, and
  dedicated tests.

The artifact and process paths should not interrupt database recovery. They
are ordered after the application is running because their old inputs are
small, local, and explicitly discoverable.

### 9. Dead reader-conditional allocator copy

`src/seon/db/id.cljc` has one large outer `:clj` branch followed by the actual
standalone `:cljs` allocator. Inside the outer CLJ branch are nested `:cljs`
definitions for generator-policy acquisition and remote allocation. Those
nested clauses are unreachable: CLJ reading discards them, while CLJS reading
discards the outer branch containing them. The later standalone CLJS block is
the live remote allocator.

This is dead duplicated source rather than two live allocator bodies. Retain
one CLJ writer implementation and the one standalone CLJS remote allocator;
delete the unreachable nested CLJS clauses. A later clarity pass may extract
truly shared pure validation, but should not create `local`/`remote` public
function names.

## Numbered and generation names that are not competing implementations

The following names should not be mechanically renamed or deleted merely
because they contain a number or compatibility word:

- `seon.db.protocol/current-version` and
  `seon.execution/protocol-version` are single wire-contract values. There is
  no `protocol-v8`/`protocol-v9` dispatcher beside them. One stale UDS test
  still expects database protocol version 8 while the current contract is 10;
  fix or delete that assertion rather than creating version adapters.
- `seon.dev.restore/intent-version`,
  `:seon.dev.restore.canonical/v1`, and autocomplete export `.../v1` are
  serialized format tags, not function generations. They can be renamed only
  through an intentional stored-format migration.
- OpenAI, Anthropic, Runpod, and Gemini `v1`/`v2` strings describe external
  provider endpoints or models.
- `openai-compat` is one provider adapter category, not a deprecated Seon
  implementation generation.
- `new-view-id`, `new-intent`, `new-def`, and old/new projection variables
  describe values in one transition; they do not select implementations.
- `V0`, `v1.md`, `context-v4`, and similar comments are historical design
  citations. Remove stale comments when editing their owner, but they do not
  by themselves create runtime duplication.

Two stored compatibility shapes require separate migration decisions:
`:seon.db.id/legacy-value` admits old persisted IDs, and
`:seon.db.restore/legacy-completion` admits old restore evidence. They are not
parallel access/eval/render mechanisms, but they are active schema branches.
Delete them only after querying every maintained database and either rewriting
or deliberately abandoning matching facts; silent rejection would make old
data unreadable.

## Graduation checks for one-owner closure

Static closure from one frozen source digest should prove:

1. no production CLJS file refers to `db/*conn*`, `open-agent-conn!`,
   `at-coordinate`, `entity-lazy`, operation capture, or read replay;
2. only `seon.db.program` compiles program reconciliation and only authority
   ensure/open commits it;
3. no production caller reaches `render-agent-canvas`, `seon.render.sci`, a
   pod-global compile state, or `worker-eval` for agent work;
4. every agent page and prompt enters through the supervised child;
5. the one Datastar registry consumes the canonical interest and
   resynchronization contract;
6. CLJS tests do not construct Datahike connections outside a deliberately
   isolated dependency test; and
7. the operator, pod, child, writer, and maintained test runner each select one
   JavaScript/process path without a compatibility fallback.

The live graduation remains the recovery plan's complete matrix: fresh start,
converged no-write reopen, multiple agents, browser feeds and calls, child
death/reconstruction, independent pod/JVM restart, multiple databases, all
maintained gates, and only then performance measurement.
