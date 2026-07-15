---
type: research
status: completed
tags: [research, agent, schema, flow]
---

# Post-commit program admission audit

## Decision

The smallest safe implementation is one process-local runtime-admission cell
and one program-publication coordinator. The coordinator closes admission
before touching wrappers or the active projection, reconstructs the accepted
generation from one post-commit database value, publishes or repairs that
generation behind the closed gate, and reopens admission only after exact
wrapper and projection verification. It never compensates with a database
transaction and never serves the previous projection after a commit.

This slice has one hard prerequisite. Candidate preparation must reject
incomplete multi-arity and variadic contracts before Malli touches a live var.
The working-tree predecessor now enforces that source-grounded precondition in
both cold and delta preparation; focused fixed/variadic identity and repeated
reconstruction regressions pass. The defect and its remaining post-fix live
proof are tracked in
[[../../../seon/issues/incomplete-multi-arity-contract-corrupts-runtime-wrapper]].

Two phrases in current source are materially inaccurate:

- `seon.schema/activate-projection!` is not atomic. It resets the declaration
  collector, resets the active projection, and only then calls Malli's
  throwable default-registry setter.
- `seon.instrument/instrument-delta!` prepares all schemas before mutation, but
  Malli still mutates vars one at a time. A thrown second replacement leaves
  the first symbol newly wrapped and the remaining symbols unwrapped after the
  old generation was already removed.

Admission is the atomic visibility boundary around those unavoidable
process-local effects. It is not a second program registry or durable state.

## Dependency ledger

| Dependency or mechanism | Selected identity | Grounded behavior | Existing Seon owner |
|---|---|---|---|
| Malli | `metosin/malli` 0.20.0; exact reference SHA `80138076960e7820523b4cb932c5b5d1936d4e7f` | `registry.cljc:40-46` keeps the default registry in a library atom; `set-default-registry!` resets it in normal mode and throws in strict mode. `instrument.cljs:95-131` walks symbols and mutates each live var immediately. `instrument!` and `unstrument!` provide no transaction or rollback. | `seon.schema` builds the immutable registry; `seon.instrument` supplies exact target data. |
| Malli function schemas | same identity | `core.cljc:89-93,2192-2202,2233-2295` exposes exact compiled arrow rows through `m/-function-schema-arities` and `m/-function-info`. A `:function` schema rejects duplicate schema arities but does not compare them with the live CLJS function. `instrument.cljs:35-94` mutates accessors for multi-arity functions but wraps a pure variadic function as one value. | `seon.instrument/prepare-target` requires exact profiles before accessor mutation and permits a stricter callable schema for the safe whole-function pure-variadic path. |
| ClojureScript runtime function shape | CLJS 1.12.145 in `deps.edn`; maintained reference source under `reference-code/clojurescript` | A multi-arity live fn exposes `cljs$lang$maxFixedArity`, fixed `cljs$core$IFn$_invoke$arity$N` accessors, and an optional variadic accessor. These are the exact runtime values Malli edits. | `seon.instrument` already reads the same properties in `-simple-fixed-arity-fn?`, `async-unwrappable?`, and `async-fn?`. |
| Canonical program projection | first-party `seon.schema` | `build-projection` is pure and validates the complete forms/contracts graph. `*schemas`, `!projection`, and Malli's default registry are separate process globals. | `src/seon/schema.cljc`. |
| Incremental publication | first-party `seon.eval` + `seon.instrument` | A successful form builds a candidate before commit. `record-eval!` commits the declaration tee; only afterward does `instrument-projection-delta!` mutate wrappers and `activate-projection!` publish the candidate. | `src/seon/eval.cljs:4219-4459`; `src/seon/instrument.cljc:418-683`. |
| Cold reconstruction | first-party `seon.client` | Database schema/function rows are read into a projection, stored namespaces are loaded, the complete projection is instrumented, agents resume, web starts, and the ticker installs. Boot throws and exits on a cold failure, but no reusable admission state exists after readiness. | `src/seon/client.cljs:829-850,908-985,2278-2425`. |
| Core fault capture | first-party `seon.error` | `record!` tags the raw error, buffers/persists one bounded projection, and follows `:seon.config/on-core-error`. It never throws. It does not itself close runtime admission. | `src/seon/error.cljs:505-587`. |
| Operator readiness | Babashka operator | Pod readiness currently requires a port file, an old `auto-boot ready` log substring, and any 2xx-3xx response from `/`. It remains true after a later publication fault. | `script/seon/dev/process.clj:260-270,299-315`. |

## Live probes

The default pod was available at the complete database-backed program
projection fingerprint `1197497774`; its Malli mode was `"default"`.

### The projection's third mutation can throw

The selected Malli setter was invoked against the current immutable registry
under an isolated `with-redefs` of its compile-time mode to `"strict"`. It
returned:

```clojure
{:message "can't set default registry, invalid mode"
 :data {:mode "strict" :type "default"}}

```

The current production mode is normal, so this is not a claim that today's
third call usually fails. It proves the public setter is throwable and that
`activate-projection!` cannot claim three-call atomicity. In normal mode,
`reset!` watch failure is another library-level way a pointer may have changed
before an exception propagates.

### Malli can throw after partial wrapper surgery

Two isolated one-arity functions were first instrumented. The live probe then
wrapped Malli's `-replace-fn` to perform the first replacement and throw on the
second while calling the real `seon.instrument/instrument-delta!`. The observed
state before cleanup was:

```clojure
{:thrown "injected second replacement failure"
 :replace-calls 2
 :a-wrapped? true
 :b-wrapped? false}

```

The probe's `finally` unstrumented both functions and then deleted the temporary
vars. This falsifies any plan that treats `::instrument/ok? false` as the only
post-commit failure. Fatal preparation rejections return false before mutation;
Malli surgery failures throw after an arbitrary prefix has changed.

### Incomplete multi-arity contracts corrupt reconstruction

A separate live probe supplied a single `:=>` contract to an isolated two-arity
function. Malli returned without error, set its instrumented marker, and left
the one-argument call unvalidated. Subsequent `unstrument!` installed a missing
recorded original into the one-argument accessor, making it non-callable. The
function was redefined immediately. The compiled contract and live function
already expose exact comparable arity data; no heuristic or source parse is
needed.

## Exact current failure path

For one ordinary agent form:

1. `eval-form-entry!` executes the form, mutating analyzer state, declarations,
   and any defined live var.
2. It snapshots changed schema keys and function symbols and builds the complete
   candidate projection before the transaction.
3. `record-eval!` commits the eval row and declaration tee. The database now
   owns generation N.
4. `instrument-projection-delta!` prepares exact targets, then
   `instrument-delta!` unstruments the complete selected old set and asks Malli
   to instrument the new set one live symbol at a time.
5. A fatal preparation rejection returns `::instrument/ok? false`. The caller
   records a core fault and leaves the old active projection installed.
6. A Malli surgery throw escapes `eval-form-entry!` and `eval-batch!`. The
   loop's outer Promise catch logs it; the process safety net may record it, but
   no owner closes admission. Some wrappers may already be from N, absent, or
   corrupt.
7. On success, `activate-projection!` resets `*schemas`, resets `!projection`,
   then calls the throwable Malli setter. Failure at that last call leaves local
   state claiming N while the default registry may still be N-1.
8. Later wakes, schedules, direct UI calls, agent births, and eval entries keep
   using whichever mixture remains.

The `::ok? false` branch and thrown branch therefore need the same owning
transition. Recording at an outer process net is evidence capture, not
publication recovery.

## Process-local state inventory

| State | Role | Required treatment while unavailable |
|---|---|---|
| `seon.schema/*schemas` | module/eval declaration candidate collector | Never serve it as current runtime authority. Fold collector and active projection into one schema-state atom, or ensure all runtime reads use only the one projection while admission is closed. |
| `seon.schema/!projection` | active immutable forms/registry/dependency/catalog projection | Publish exactly one committed projection; never retain N-1 as admissible after N commits. |
| Malli `registry*` | convenience default registry pointer | Repoint during publication, but do not treat it as atomic or durable. Prefer one stable Seon registry facade over the active schema-state atom so per-generation publication does not call the throwable setter. |
| Live JS vars and Malli wrapper markers | executable bodies plus reversible wrapper records | Hidden behind closed admission during delta/full reconciliation. Verify every committed target afterward. |
| `seon.repl/!compile-state` and analyzer namespaces | self-host compiler/runtime state | Required to reconstruct stored declarations. Keep addressable to internal recovery and MCP diagnosis; do not expose it through autonomous eval admission. |
| `seon.agent.loop/!loop-input`, DB wake listeners, and `!ticker` | process hosting and automatic work triggers | Existing accepted work must observe the closed gate before another turn; new wakes/schedules must return or skip before opening a run. The gate is primary; uninstallation is cleanup, not correctness. |
| `seon.web.serve/!server` and Datastar listeners | inspection and command transport | Read-only diagnosis may remain available. State-changing commands return typed unavailable/HTTP 503. |
| `seon.client/!state` | boot/reload/heartbeat metadata | Do not overload this unrelated map as admission truth. |
| result slots on `globalThis` | capped handles for already-run forms | Existing values may remain inspectable, but no new agent eval/result admission after closure. |

## One runtime-admission mechanism

Add `src/seon/runtime/admission.cljs` as the sole process-local visibility and
program-publication coordinator. This is a disposable reconstruction state, not
database truth and not a second lifecycle registry.

### State and schemas

Register these internal/public shapes in the owning namespace:

```clojure
:seon.runtime.admission/status
[:enum :starting :publishing :available :unavailable]

:seon.runtime.admission/state
[:map
 [:seon.runtime.admission/status :seon.runtime.admission/status]
 [:seon.runtime.admission/generation {:optional true} :int]
 [:seon.runtime.admission/reason {:optional true} :string]]

```

The one `defonce` atom starts at `:starting`. Its generation is the accepted
projection fingerprint, not a second counter. Do not store an error census,
retry count, or duplicate projection in it.

Public functions and their exact responsibilities:

- `state` returns the immutable current state.
- `available?` is the one predicate all execution boundaries use.
- `unavailable` returns one typed `:seon/error` value containing status,
  generation, and a concise recovery instruction; it performs no recording.
- `begin-publication!` closes visibility synchronously and returns whether this
  caller owns the available/starting to publishing transition.
- `publish-committed!` owns reconstruction, wrapper reconciliation, projection
  publication, verification, fault recording, and reopen.
- `mark-unavailable!` is idempotent and records only when its caller owns the
  failed publication occurrence. Repeated boundary refusals return data and
  never record another core fault.

Internal boot/reconstruction calls bypass `available?` only through private
coordinator functions. There is no public force-open or kill-switch path.

### Make schema publication one visible mutation

Strengthen `seon.schema` rather than adding another registry:

1. Replace the independent collector/projection atoms with one atom containing
   both candidate forms and the active projection, so activation changes the
   Seon-visible generation in one `reset!`.
2. Make Malli's convenience default one stable registry facade that reads the
   active projection's forms from that atom. `relink-registry!` remains only the
   boot/bundle-stomp integration call; a normal generation publication no
   longer calls `mr/set-default-registry!`.
3. Continue passing the candidate's immutable explicit registry to every
   wrapper preparation. The stable default is convenience, never validation
   authority.
4. Rename or rewrite `activate-projection!` to state its real single-atom
   semantics. Tests must stop reaching into two private atoms to repair
   fixtures.

This removes the known third-call split without pretending Malli var surgery is
atomic.

### Enforce wrapper preconditions before this slice relies on repair

In `seon.instrument/prepare-target`:

1. Compile with `m/function-schema` and derive schema arity rows via
   `m/-function-schema-arities` plus `m/-function-info`.
2. Derive the live fixed accessor set and optional variadic accessor/minimum
   from the original CLJS function.
3. Reject missing, extra, or incompatible coverage as a new fatal reason such
   as `::arity-mismatch`; include expected and actual arity data in the
   rejection.
4. Only a complete parity result may enter Malli's data map.

Do not patch Malli's missing-original symptom locally and accept incomplete
contracts. An upstream nil guard would reduce corruption but would not make the
contract true.

### Publication transition

`publish-committed!` performs this one ordered transition:

1. Close admission synchronously. Snapshot the old projection only for the
   wrapper union; it is no longer admissible.
2. Dereference `db/*conn*` once after the accepted transaction. Re-read all
   canonical `:seon.schema/key`/`:seon.schema/form` and
   `:seon.fn/sym`/`:seon.fn/spec` rows from that immutable database value.
   Move the duplicate private query helpers from `seon.client` into the schema
   projection boundary as pure data-in functions, or pass their decoded maps
   into the coordinator; do not let the coordinator re-deref.
3. Build the complete committed projection. Prepare every wrapper target and
   verify live/schema arity parity before mutation.
4. Reconcile the union of old and committed function symbols behind the closed
   gate: unstrument the union, instrument the complete committed target set,
   and verify that every wrappable target has exactly one wrapper while every
   removed target is original/unwrapped.
5. Publish the committed projection through the single schema-state mutation.
6. Set admission to `:available` with its fingerprint only after verification.
7. If any step throws or returns a fatal rejection, catch it at this owner,
   attempt the same full reconstruction once from a newly frozen current
   database value, and reopen only if that complete attempt verifies. Record
   one bounded core fault for the original failed occurrence whether repair
   succeeds or not. If repair fails, remain `:unavailable` and fail readiness.

Incremental delta remains a performance choice inside this transition. Full
committed reconstruction is the correctness path and must use the same target
builder and verifier, not a second registry or replay engine.

## Exact admission entry points

The gate belongs at work admission, not every low-level database operation;
fault recording and reconstruction must still write/read while closed.

| Entry point | Current action | Required closed behavior |
|---|---|---|
| `seon.eval/eval-batch!` and its per-entry loop | Admits an agent reply batch and continues N+1 after ordinary form failure | Check before the batch and before every next entry. After publication closes during entry N, record no fabricated N+1 eval; return the typed unavailable value and stop the fold. Raw `seon.eval/eval` remains available only to internal replay and MCP diagnosis. |
| `seon.agent.loop/run-loop!` | Beats and starts another LLM turn until an FSM bound | Check before each beat/LLM turn and after a returned turn. On closure, stop driving without opening another turn. Preserve the already-committed run for crash/recovery rather than pretending completion. |
| `seon.agent.loop/wake-handler` | Opens or renews a message run from a transaction listener | Refuse before open/renew. This is a safety backstop even though `message!` also returns unavailable. |
| `seon.agent.message/message!` | Commits the human/agent work request that would trigger a wake | Return the existing DB-style failure envelope with a typed unavailable error before identity allocation or transaction. This prevents a durable request whose one-shot listener event was intentionally discarded. Core fault persistence does not use this path. |
| `seon.agent.schedule/fire-due-schedules!` and ticker | Opens schedule runs, executes scheduled functions, and drives them | Return an empty fired vector plus typed unavailable status before scanning/opening. `run-tick!` must not swallow this into a normal successful tick. |
| `seon.agent/start!`, `create!`, `delegate!`, and `runtime/resume!` | Mint or host executable agents | Gate the lowest shared birth/resume transitions before durable mint or listener installation. Boot calls an internal reconstruction bypass before admission opens. |
| `seon.agent.loop/drive-run!` | Re-enters a paused/open run from `/resume` or schedules | Return typed unavailable before scheduling `setTimeout`. Stop/complete/terminate controls remain allowed so operators can drain. |
| `seon.web.reactive.call/invoke!` | Directly resolves and applies a live agent function, bypassing eval-batch | Return typed unavailable before lookup/apply. The handler maps it to HTTP 503, not 422. |
| State-changing web routes in `seon.web.serve` | `/chat`, `/agents`, `/agents/run`, `/resume`, `/clear`, config apply | Let the owning domain gate decide and consistently translate unavailable to 503. Read/debug routes and stop/complete controls remain available. |
| `seon.client/shadow-build-notify!` and `after-reload` | Mutate wrappers and then always rehost listeners/ticker | Enter the same publication coordinator. A failed Shadow import or instrumentation transition marks unavailable; `after-reload` must not re-arm autonomous work until the committed/current generation verifies. This joins the root cause in [[../../../seon/issues/hot-reload-schema-import-can-partially-fail]]. |

The developer MCP/Shadow REPL stays addressable while unavailable. Otherwise a
failed pod would remove its own diagnosis and recovery surface. Cluster agent
advertisement should include admission state rather than hiding agent ids.

## Readiness cut

Add one read-only application readiness response backed by admission state,
served even when unavailable. `script/seon/dev/process.clj/http-ready?` must
probe that exact route and accept only the available status for both initial
wait and later `bin/seon status`. Delete the old requirement for an historical
`auto-boot ready` substring; a log line cannot become false again.

The port file continues to identify the live HTTP process. Process liveness,
socket reachability, and application admission remain separate checks composed
by the operator.

## Failure matrix

| Failure | Required state and evidence |
|---|---|
| Candidate schema or function contract invalid before commit | No transaction, wrapper, projection, or admission change; return user-input data. |
| Live/schema arity mismatch before commit | Fatal candidate rejection with exact live and schema arities; no Malli mutation. |
| Database transaction fails | Old projection/wrappers remain available; declaration collector restores exactly. |
| Commit succeeds; fatal target preparation rejection | Admission closes; one core fault records; full committed reconstruction runs. Reopen only on verified success. |
| Commit succeeds; Malli throws after zero or several symbol mutations | Same owner catches it. No boundary can observe the mixed wrappers. Full union reconciliation repairs or runtime stays unavailable. |
| Complete wrapper reconciliation succeeds; schema-state publication fails | Remain closed, record once, rerun full committed reconstruction. Never serve N wrappers against N-1 projection. |
| Malli default relink throws during cold boot | Admission remains `:starting`/unavailable and boot readiness fails. No runtime service starts. Normal generation publication does not call this setter. |
| Immediate reconstruction succeeds | Publish exact committed fingerprint, reopen, retain one core fault as evidence, and let the next work request proceed. |
| Immediate reconstruction deterministically fails | Stay unavailable, readiness returns non-2xx/typed status, autonomous entries reject without recording more faults. Config-free process restart retries from committed facts. |
| Failure occurs during entry N of a multi-form batch | Entry N's real committed outcome remains; entries N+1 onward are not executed or fabricated. Batch returns ids already committed plus unavailable status. |
| Failure while another run awaits an LLM/provider | On continuation, the loop observes admission before another write/turn. Any already-open turn follows crash recovery; no new generation-N-1 execution starts. |
| Hot reload import fails before coherent publication | Retain or reconstruct one complete committed generation, mark unavailable if coherence cannot be proven, and do not rehost/tick. |

## Tests

### `test/seon/instrument_delta_test.cljs`

- Incomplete two-arity and variadic contracts return `::arity-mismatch` before
  mutation; prior wrapper identities and every live accessor remain identical.
- Complete `:function` contracts survive repeated delta and full
  reconciliation with all fixed/variadic calls validated.
- Inject a throw on Malli replacement two of three; assert the coordinator
  closes admission, repairs the complete committed target set, and leaves
  wrapper depth exactly one.

### `test/seon/schema_test.cljs`

- Activation changes collector/current projection through one schema-state
  mutation.
- A throwing default-registry relink cannot produce a split current generation.
- Runtime lookups see the stable registry facade's newly active forms only
  after publication.

### Eval/publication tests

- Inject `::instrument/ok? false` after an accepted tee. Assert one core fault,
  no bound result that implies publication success, typed unavailable for the
  following form, and no N+1 eval row.
- Inject a thrown second Malli mutation and prove the same behavior.
- Let full reconstruction succeed and prove a later new batch uses the committed
  contract; force it to fail and prove all later batches reject.
- Assert exact one-error cardinality after repeated web/message/schedule
  refusals.

### Agent, schedule, and web tests

- Message, schedule, spawn, resume, run-loop next-turn, and `/call` each reject
  without their owning write/invocation while unavailable.
- Stop/complete/terminate and read-only diagnosis remain available.
- HTTP readiness flips from available to unavailable in the same process and
  `seon.dev.process/ready?` follows the current response rather than a prior log.
- `after-reload` cannot reinstall wake listeners or the ticker after failed
  publication.

## Live graduation proof

1. Start a config-free populated default cluster and record its database
   coordinate plus projection fingerprint.
2. Deterministically inject a post-commit second-wrapper failure for an isolated
   declaration transition.
3. Observe one accepted declaration transaction, one core fault datom, closed
   readiness, no later eval/message/schedule/call effect, and no fabricated
   multi-form result.
4. Permit full committed reconstruction and observe one active projection and
   one wrapper per target at the committed fingerprint.
5. Repeat with deterministic repair failure; verify the same process stays
   unavailable and every admission returns typed data.
6. Restart with no config. Prove reconstruction from canonical database facts,
   matching fingerprint/coordinate, readiness reopening, and a later valid
   agent eval under the committed contract.

## One-mechanism deletion and cutover

- Replace the direct eval call to `instrument-projection-delta!` plus
  `schema/activate-projection!` with the one publication coordinator.
- Replace cold boot's direct activation/instrument calls and hot reload's direct
  namespace instrumentation with that coordinator's cold/delta modes.
- Remove per-publication `mr/set-default-registry!`; retain only one boot relink
  to the stable Seon registry facade.
- Delete the old misleading `activate-projection!` atomicity contract and the
  two-private-atom fixture repair pattern.
- Delete operator readiness dependence on the `auto-boot ready` log substring.
- Do not add rollback transactions, a wrapper registry, a program-generation
  database entity, per-entry error recording, or a second recovery loop.

## Blockers and non-goals

- The multi-arity contract parity issue is a hard predecessor because the full
  repair path otherwise can itself corrupt a live function.
- Atomic replacement of every JavaScript var is not available from selected
  Malli. Fail-closed visibility plus verified full reconstruction is the honest
  boundary.
- Worker-process isolation and cancellation remain owned by the separate eval
  containment issue. This slice prevents continued admission; it does not
  preempt a synchronous CPU loop already blocking Node.
- Native branch/restore admission should later reuse this same admission cell
  and coordinator boundary. It must not introduce another drain/readiness
  state machine.
