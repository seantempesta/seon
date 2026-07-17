---
type: research
status: complete
tags: [database, cljs, flow, research]
---

# Client single-lifecycle audit

## Decision

Keep one process lifecycle in `seon.client/start-runtime!`, one durable package
reconciliation boundary in `seon.db/open-session!` and the writer's existing
database ensure operation, one process-local executable gate in
`seon.runtime.admission`, and one keyed database listener feeding the cached
agent-ID vector required by the synchronous runtime advertisement.

Do not add another initializer, reload registry, replay path, config cache,
membership registry, coordinate adapter, or result envelope. The current
client is broken because it still consumes retired coordinate and envelope
contracts after the public database facade moved to ordinary database values,
native transaction reports, and direct errors. Hot reload also reopens
admission without first reconciling the newly compiled package into the
database.

Host-side reconciliation of committed work is now closed at `e33db778`.
`seon.agent.runtime/resume!` installs the host and the retained loop owner then
drives committed work. Client lifecycle repair must consume that owner; it must
not add a resend, replay, processed flag, or client-specific wake path.

## Dependency ledger

### Selected source

- Seon was inspected at `47754eabda46628aec3cfaf2e56a62ebda224d62`.
- `b950603e` removed the old resumable-agent query value and changed
  resumable-ID acquisition to an ordinary database value.
- `e33db778` closed resume-time committed-work reconciliation in the existing
  loop/runtime owners.
- `reference-code/datahike` is at
  `a464cd887458d2572414a6ea951c477b0981fdae`.
- `reference-code/shadow-cljs` is at
  `4e72595f57618f5c43388ad13d5136cd3bede566`.
- `reference-code/clojurescript` is at
  `946d75f3483c0c8e784e6668bff2c71a25619a77`.

### Retained dependency mechanisms

| Concern | Existing owner | Source evidence |
|---|---|---|
| Latest database value | `seon.db` session | `src/seon/db.cljs:193-212`, `513-567` cache and return one ordinary immutable database descriptor. |
| Native transaction result | `seon.db/transact!` | `src/seon/db.cljs:594-702` returns `:db-before`, `:db-after`, `:tx-data`, `:tempids`, and `:tx-meta`, or a direct `:seon.error/message` value. |
| Transaction interests | `seon.db/listen!` | `src/seon/db.cljs:268-288`, `973-1056` deliver native reports or resynchronization events and return a scalar listener key; unlisten returns boolean or direct error. |
| Datahike listener semantics | Datahike connection | `reference-code/datahike/src/datahike/core.cljc:199-217` proves keyed replacement and scalar key return. |
| Package delta | `seon.db.program/compile-tx-data` | `src/seon/db/program.clj` computes complete add, change, retract, and converged-empty data from one Datahike value. |
| Package admission | writer database ensure | `src/seon/db/writer.clj:1390-1503` installs genesis, native schema, the exact package delta, and missing initial data before returning the admitted database value. |
| Process admission | `seon.runtime.admission` | `src/seon/runtime/admission.cljs` builds the complete current schema and function-contract projection, reconciles instrumentation, and opens one generation gate. |
| Agent membership | `seon.agent` and `seon.derive` | `src/seon/agent.cljs:321-348` and `src/seon/derive.cljs:197-253` derive armable and resumable IDs from one ordinary database value. |
| Quiescence work | `seon.agent.run/quiescence-work!` | `src/seon/agent/run.cljs:319-385` returns current runs, running turns, and the exact ordinary database value used for both queries. |
| Child program loading | `seon.eval` and `cljs.js` | `namespace-source` and `load-authored-program!` assemble one current source section per namespace and let `cljs.js` own dependency loading. They do not replay historical forms. |
| Reload notification | Shadow Node client | `reference-code/shadow-cljs/src/main/shadow/cljs/devtools/client/node.cljs:22-46`, `162-172` loads changed JavaScript before calling the custom completed-build notification. |

The writer initialization tests already prove the desired database behavior:
a fresh database performs one minimal genesis transaction plus one boot/package
transaction, a converged ensure writes nothing, invalid initialization publishes
no connection, and a branch rejects package initialization without advancing
its head (`test/seon/db/writer_initialization_test.clj`). Program compiler tests
prove complete, partial, drift-repair, stale-removal, runtime-authored
preservation, and order independence (`test/seon/db/program_test.clj`).

## Current contradictions

### Client does not compile against the retained facade

The shortest static falsifiers are sufficient:

- `src/seon/client.cljs:348` and `415` reference
  `agent/resumable-agent-ids-query`, which `b950603e` deleted.
- `src/seon/client.cljs:1996` calls `db/assert-preconditions!`, which no longer
  exists.
- `src/seon/client.cljs:2388` calls `db/head-coordinate`, which no longer exists.
- `open-session!` returns `{::db/database-name ::db/db ::db/capabilities}` in
  `src/seon/db.cljs:294-297`, but `src/seon/client.cljs:1997` reads
  `(::db/coordinate session-open)`.

These are migration breaks, not reasons to restore compatibility functions.

### Runtime advertisement consumes two retired contracts

`src/seon/client.cljs:339-457` maintains desired, accepted, and cached
coordinates and assumes that:

- a listener callback receives `::db.protocol/coordinate`; and
- `db/listen!` returns a map containing `::db/key` and `::db/coordinate`.

The retained facade instead:

- delivers a native transaction report with `:db-after` for committed datoms;
- delivers a reconnect resynchronization event carrying `:db-after`; and
- returns the listener key itself.

The session already caches every `:db-after`. Advertisement needs only one
owner identity, the scalar listener key, and the last accepted vector of
resumable IDs. It does not need its own coordinate state machine.

### Cold package initialization exists, but reload omits it

`database-initialization` constructs one deterministic package snapshot and
`open-database-session!` carries it through the existing ensure request. The
writer reconciles it correctly.

On completed Shadow reload, however, `shadow-build-notify!` only calls
`admission/publish-committed!`. It never presents the newly compiled package to
the writer. Admission can therefore rebuild wrappers from stale database facts
while the pod has already loaded newer JavaScript.

The fix is not another reload publisher. Completed reload must reuse the same
database open/ensure boundary, then invoke the same database-derived admission
step. Durable package reconciliation and process-local wrapper activation are
different stages of one publication occurrence:

1. the writer owns canonical database facts; and
2. admission owns live wrappers and availability.

`shadow-reloaded-namespaces` is dead production code referenced only by its
test. Full package reconciliation plus projection diffing supersedes it.

### Session identity incorrectly includes per-call initialization

`session-selection` includes the initialization value. `open-session!` compares
the complete selection to the retained session selection:

- an unchanged initialization returns the cached session without running
  ensure again; and
- a changed package makes the selection unequal and throws that another
  session owns the process.

Session identity must contain only stable connection selection: socket,
database name, backend, path, and optional administrative attachment. Package
initialization is input to each serialized ensure occurrence, not session
identity. An active same-database call with initialization must run ensure and
acquire again. This lets cold start and reload reuse one unversioned operation.

### Startup dependencies still use coordinate envelopes

The client directly invokes several owners that have not migrated:

- `seon.runtime.admission` returns and threads a top-level coordinate even
  though only the schema projection fingerprint is process admission state.
- `seon.runtime.recovery` uses a coordinate-bearing execute-many acquisition,
  compiles `::db/expected-coordinate`, and returns a second
  `:seon.db/ok?` envelope.
- `seon.state/reconcile!`, called by explicit config application, uses a
  coordinate acquisition, an expected-coordinate transaction, and the retired
  database envelope.
- `seon.ai/sync!` and `seon.web.brand/sync!` use a top-level execute-many
  coordinate and branch on `:seon.db/ok?`.
- `seon.client` still checks the old envelope for recovery and initial-agent
  birth even though initial-agent birth now returns its domain success map or a
  direct error.

Each owner should acquire one ordinary database value, attach it to its grouped
reads, use it as `:seon.db/expected-db` when a compare-and-commit fence is
required, and distinguish a native report from a direct error. Domain results
such as `:seon.state/ok?`, `::recovery/repaired?`, and
`::admission/published?` remain useful. They are not database envelopes.

### Config claims database authority but never installs its reader

`src/seon/config.cljs:453-472` declares `set-db-config-view!` and says
`seon.db` installs it once. A repository-wide search finds no caller.

Consequences:

- zero-arity `config-view` falls back to resolving `SEON_CONFIG` or code
  defaults even after database attachment;
- explicit database-value `config-view` falls back to code defaults when the
  missing injected reader is nil; and
- a database transaction or explicit config apply cannot reliably become the
  authoritative value described by `config-view`'s contract.

Do not repair this by installing another synchronous cache. Remove the unused
injection seam and make database-dependent config acquisition an ordinary
asynchronous database read at the owning operation boundary. Pure accessors
that already receive a database value should receive the already-acquired
config row or a pure decoded config view. The narrow pre-attachment sliver may
resolve an explicitly selected manifest or code default, but no post-attachment
runtime operation may silently return to it.

### Startup opens executable and web work before configuration sync completes

Current startup resumes agents and starts the web server before awaiting
`ai/sync!` and `web.brand/sync!`. This permits agent execution to observe old AI
configuration and web readiness to expose old brand data.

Package initialization, explicit config application, AI seed, brand reconcile,
crash recovery, root completion, and initial ordinary-agent creation must
finish before database-derived admission, agent hosting, web readiness, or the
ticker. Distinct provenance and seed policies remain distinct transactions;
they are one ordered startup phase, not one forced physical transaction.

### Quiescence receives a database value and discards it

`quiescence-work!` already returns `::db/db`, but the client looks for
`::db/coordinate`, uses retired pull-many argument names, classifies
`close-run!` through `:seon.db/ok?`, accumulates coordinates, and performs a
second removed `head-coordinate` read.

The final empty-work database value is the frozen application value for the
drain. Pass it to terminal-turn `pull-many`, classify close results as native
reports or direct errors, and close the session after all owners are detached.
The full coordinate currently required by the pod quiesce schema is not
consumed by `script/seon/dev/process.clj`; canonical restore coordinates belong
to the writer/operator administration contract. Do not preserve a public
database-facade coordinate exception solely for this unused field.

### Replay is already absent from the retained runtime path

There is no current `replay-program-graph!` call in `seon.client`. Child loading
uses the compiled artifact as its baseline and loads current database-authored
namespace sections through `cljs.js`. A changed program digest replaces the
child on the next invocation and retries once.

Remove stale replay wording from admission and client comments, including
“replay-skip” descriptions that actually mean compiled-package versus authored
namespace membership. Do not change `load-authored-program!` into form replay,
add a source broadcast, or eagerly retire every idle child.

## Retained lifecycle

### Cold start

1. Claim and validate the closed launch capability.
2. Build and completely validate one deterministic compiled-package snapshot.
3. Open the database session and let writer ensure reconcile package schema,
   program facts, and required initial identities before returning its admitted
   database value.
4. Apply an explicitly selected `SEON_CONFIG` manifest; no selected manifest
   means preserve database config facts.
5. Reconcile the AI seed and brand environment policies against ordinary
   database values.
6. Perform one fenced crash-recovery transition. A clean database writes
   nothing.
7. Complete root and initial ordinary-agent birth. Existing birth writes
   nothing.
8. Attach and settle the one resumable-agent advertisement listener.
9. Close and publish the complete current database-derived schema and function
   projection through admission.
10. Resume all nonterminated agents through the now-settled
    `seon.agent.runtime/resume!` owner.
11. Start web readiness and install the ticker.

No executable work, schedule, or HTTP route becomes ready before step 9.

### Completed reload

1. Build start closes admission synchronously.
2. Build failure leaves admission unavailable and records one fault.
3. Build completion reuses session open/ensure with the new deterministic
   package snapshot.
4. The writer commits one exact package delta, or no transaction when
   converged.
5. Admission reconstructs and verifies the complete accepted projection once.
6. Rehost the current resumable IDs once and reinstall the ticker.
7. Each execution child keeps its current invocation value; its next invocation
   replaces the child only when the program digest differs.

`after-reload` remains effect-free. It is not a second publication hook.

### Attached repeated start

Validate the retained launch capability, settle the existing advertisement,
and idempotently reattach the web surface. Do not rerun package
reconciliation, config, recovery, birth, admission, or hosting merely because a
caller asks for already-running status.

### Quiesce and stop

1. Close executable admission.
2. Stop HTTP/SSE admission while retaining the operator response path.
3. Remove ticker and wake-trigger owners.
4. Repeatedly close idle current runs and wait for running turns until
   `quiescence-work!` returns empty at one ordinary database value.
5. Classify observed terminal turns at that same value.
6. Unhost agent process handles.
7. Unlisten the scalar advertisement key.
8. Detach admission and its process-local projection.
9. Close the database session.
10. Finish web shutdown after the operator receives the typed quiesce result.

Every inverse remains retry-safe under the existing lifecycle phase fence.

## Ordered coherent cuts

### Cut 1: make open and ensure the one package seam

Owners:

- `src/seon/db.cljs`
- `src/seon/client.cljs`
- `test/seon/db_session_test.cljs`
- `test/seon/client_initialization_test.cljs`

Separate stable session selection from per-call initialization. Serialize
same-session ensure/acquire and return its accepted ordinary database value.
Call this same operation from cold start and completed reload. Keep writer
program reconciliation unchanged unless a falsifier shows a writer defect.

### Cut 2: migrate startup dependencies to ordinary values

Owners:

- `src/seon/runtime/admission.cljs`
- `src/seon/runtime/recovery.cljs`
- `src/seon/state.cljs`
- `src/seon/ai.cljs`
- `src/seon/web/brand.cljs`
- their focused tests

Acquire one database value per coherent operation. Use `execute-many` only for
the operation's bounded parallel reads. Compile pure transaction data and use
`::db/expected-db` where a stale-basis fence matters. Consume native reports
and direct errors. Let `seon.db.id/allocate!` retain generator-policy ownership
rather than duplicating it in recovery.

### Cut 3: remove the phantom config reader

Owners:

- `src/seon/config.cljs`
- the one async owner selected for database config acquisition
- `test/seon/config_test.cljs`
- startup/config consumer tests

Delete `!db-config-view` and `set-db-config-view!`. Add no atom, memoized
singleton, or injected synchronous reader. One operation acquires the current
config row from its already-selected database value and passes ordinary decoded
data to pure accessors. Preserve only the explicit pre-attachment manifest
fallback. A config-free reopen reads the retained database facts.

### Cut 4: replace advertisement coordinates with the database value

Owners:

- `src/seon/agent.cljs`
- `src/seon/derive.cljs`
- `src/seon/client.cljs`
- `test/seon/agent/multiagent_test.cljs`
- client advertisement tests

Let the one resumable-ID reader accept an optional database value. Reuse one
query definition for selective invalidation. Register one keyed listener,
settle initially from the session cache, and refresh from event `:db-after`.
After an asynchronous query completes, accept it only when its owner is still
current and its database value still equals the session's cached latest value.
This handles reverse completion without a second sequence or coordinate cache.

### Cut 5: reorder one atomic client start and reload

Owners:

- `src/seon/client.cljs`
- `test/seon/client_initialization_test.cljs`
- `test/seon/instrument_delta_test.cljs`
- startup integration tests

Delete obsolete precondition and envelope branches. Complete package,
configuration, recovery, and birth before publication. Reconcile the package
before admission on reload. Delete `shadow-reloaded-namespaces` and its
test-only contract. Consume `e33db778` hosting without adding wake logic.

### Cut 6: finish ordinary-value quiescence

Owners:

- `src/seon/client.cljs`
- `src/seon/runtime/lifecycle.cljc`
- `script/seon/dev/process.clj`
- `src/seon/web/serve.cljs`
- `test/seon/agent/run_test.cljs`
- `test/seon/runtime/lifecycle_test.cljc`
- `test/seon/dev/process_test.clj`
- `test/seon/web/serve_test.cljs`

Carry the final empty-work database value, use current `pull-many` argument
names, consume native close reports, and delete client coordinate accumulation.
Move any genuinely required restore coordinate resolution to the private
writer/operator administration owner. This is a coordinated cut, not a client
compatibility shim.

### Cut 7: delete stale vocabulary and prove the integrated system

Remove obsolete coordinate/protocol imports, replay comments, local-connection
fixtures, coordinate envelopes, and dead tests in the same owner cuts. Do not
rename retained functions with version suffixes. Run focused proof first, then
one source-frozen full and live checkpoint.

## Protected overlaps

At audit completion, other agents owned uncommitted changes in:

- `src/my/blob.cljs`
- `src/my/plan.cljs`
- `src/my/plan/internal.cljs`
- `src/seon/agent/ctx/namespaces.cljs`
- `src/seon/agent/home.cljs`
- `src/seon/db/internal.cljs`
- `src/seon/eval.cljs`
- `src/seon/repl/autocomplete.cljs`
- `src/seon/test/runner.cljs`
- `src/seon/web/reactive/call.cljs`
- `src/seon/web/serve.cljs`
- `test/my/plan_test.cljs`
- `test/seon/agent/ctx/namespaces_test.cljs`
- `test/seon/agent/home_test.cljs`
- `test/seon/eval/auto_refer_test.cljs`
- `test/seon/repl/autocomplete_test.cljs`
- `test/seon/web/reactive/call_test.cljs`
- untracked `locks/`
- untracked `test/my/plan_internal_test.cljs`

Do not overwrite these paths. The quiescence/web and child no-replay
integration requires a coordinated source freeze and explicit handoff.

## Acceptance matrix

| Scenario | Required evidence |
|---|---|
| Invalid package | Complete projection validation fails before the authority opens or publishes a usable runtime. |
| Fresh database | Writer performs minimal genesis plus one package/initial-data transaction before any agent, schedule, or web work. Root and one ordinary agent are complete before admission. |
| Partial database | Package compiler emits only the missing or changed durable facts and preserves runtime-authored identities. |
| Converged reopen | Package ensure, config reconcile, AI seed, brand reconcile, recovery, and agent birth each submit no transaction when already converged. |
| Config-free reopen | No manifest is read or applied post-attachment; database config facts determine runtime accessors. |
| Explicit config apply | The managed subset changes exactly once, unrelated facts survive, and repeating the same apply writes nothing. |
| Database config authority | A config transaction is visible to the next operation using the latest database value; an older explicit database value returns its historical config; no atom or manifest fallback overrides either. |
| Changed reload | One exact package delta commits before one admission generation opens. Rehosting happens once. No stale database program is advertised as current. |
| Unchanged reload | Package reconciliation writes nothing; admission remains one verified occurrence; no namespace-selection side registry runs. |
| Agent birth | The advertisement vector gains the born agent after its accepted `:db-after`; no polling or second hosted-agent registry exists. |
| Agent termination | The advertisement vector drops the terminated agent from the same selective interest. |
| Listener reconnect | Session listener restoration emits resynchronization with `:db-after`; the advertisement converges without coordinate state or duplicate registration. |
| Reverse async completion | A slower older resumable query cannot replace a projection computed from the current cached database value. |
| Clean restart | Planned quiescence leaves no open work; recovery writes nothing; resumable agents are hosted once. |
| Crash recovery | One database-value-fenced transaction closes interrupted ownership and records one recovery anchor; hosting consumes the repaired facts without replaying a task. |
| Committed task before hosting | `e33db778` drives it through the one retained loop owner exactly once; client startup adds no resend path. |
| Quiescence | Current runs and running turns reach empty; terminal pulls use the same final database value; every host, interest, admission, and session owner detaches. |
| No form replay | A replacement child starts from the compiled package, loads each current authored namespace as one section, and does not evaluate historical eval forms. |
| Child refresh | An invocation at database value T completes at T; the next invocation with a changed digest replaces the child once and retries once. |
| Multi-database | Each explicit database descriptor selects its own authority database; no process-global config or membership cache leaks across names. |

## Verification gates

Focused CLJS owners:

- `seon.client-initialization-test`
- `seon.db-session-test`
- `seon.runtime-admission-test`
- `seon.runtime-recovery-test`
- `seon.agent.multiagent-test`
- `seon.agent.run-test`
- `seon.boot.reconcile-seed-test`
- `seon.state-test`
- `seon.config-test`
- `seon.ai-test`
- `seon.web.brand-test`
- `seon.instrument-delta-test`

Focused JVM/operator owners:

- `seon.db.writer-initialization-test`
- `seon.db.program-test`
- `seon.runtime.lifecycle-test`
- `seon.dev.process-test`

Integrated graduation:

1. run the complete CLJS and writer gates under a source freeze;
2. run the operator lifecycle gate;
3. cold-start a fresh default cluster and observe admitted package and initial
   entities before readiness;
4. restart without config and prove zero initialization/recovery writes;
5. perform changed and unchanged Shadow reloads;
6. create and terminate agents while observing advertisement convergence;
7. crash one agent child and then the pod, proving isolated child replacement
   and one fenced recovery occurrence;
8. quiesce and restart cleanly; and
9. prove the same behavior for a second named database without shared
   process-local config or membership state.

The final proof must also rerun the coordinate/envelope search. Remaining
coordinates must be confined to private writer/operator administration until
that contract is replaced; application reads, startup, advertisement,
configuration, recovery, admission, and quiescence must use ordinary database
values.
