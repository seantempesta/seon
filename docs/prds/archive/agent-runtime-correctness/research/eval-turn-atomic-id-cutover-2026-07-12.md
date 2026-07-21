---
type: research
status: active
tags: [research, database, agent, flow]
---

# Eval and turn atomic identity cutover

## TL;DR

Turn and eval creation must stop accepting pre-minted ids. A turn allocates and
commits its id in `open-turn!` before the LLM body starts, then passes that
committed id into the body. An eval does the reverse around its irreversible
boundary: execute and await the form exactly once without an id, freeze its
runtime outcome and tee data, then let `seon.db.id/allocate!` retry only a pure
transaction builder until the eval row, turn component link, and tee rows commit
together. Only the committed eval id may be bound as `result/<id>`.

The smallest safe public changes are:

- `open-turn!` no longer accepts `:seon.agent.turn/id-of-turn`; its body callback
  changes from zero arguments to one committed turn-id argument.
- `record-eval!` no longer accepts `:seon.eval/id-of-eval`; it returns a
  success/error envelope whose success carries `:seon.eval/id`.
- `run-turn!`, `ask-and-eval!`, and `eval-batch!` retain their public call
  shapes. Their internal callers receive ids from those two owners instead of
  calling `db/new-id!`.

No pending/reservation entity is needed. No eval source, Promise, analyzer
mutation, schema registration, instrumentation pass, test run, log, or result
binding may run inside the allocator's retryable builder.

The allocator's current positive-EID preparation must also be corrected before
this cutover. Datahike advances `max-eid` when an explicit candidate EID is
processed, so an intervening auto-allocated entity can consume a later reserved
candidate EID. The shared CLJC preparation owner should instead inject
collision-free tempids, let the serialized Datahike writer allocate normally,
and resolve allocation-key to EID from the committed transaction report. Both
the JVM wire adapter and direct in-memory test writer must call that one shared
preparation function *inside* their serialized writer operation.

## Locked invariants

- Callers never request or observe an uncommitted raw candidate.
- Every new `:seon.agent.turn/id` and `:seon.eval/id` is committed through
  `seon.db.id/allocate!`.
- A generated-candidate collision can rebuild data, but it can never re-execute
  an eval or call the LLM body twice.
- A `result/<eval-id>` handle exists only for a successfully committed eval row
  and names that row's actual id.
- `:seon.agent.turn/evals`, the nested eval row, and all accepted tee operations
  remain one Datahike transaction.
- Tee-drop recovery may retry persistence without the already-executed form. It
  must not retry on allocator protocol/exhaustion/commit-ambiguity errors.
- A repaired read may expand to several eval entries. Each resulting entry owns
  one independent allocation/commit and contributes its committed id in order.
- Arbitrary transactions performed by an eval do not need an eval-id in
  transaction metadata. The final provenance model is only user plus process.
- No reservation row, fallback generator, `new-id!` alias, legacy creation
  branch, or parallel eval recorder remains after cutover.
- Tests assert datoms, transaction identity, execution counts, and result
  resolution. They do not assert explanatory prose.

## What the current code does

### Turn

`run-turn!` currently calls `db/new-id!` before prompt blob capture and before
`open-turn!` (`src/seon/agent/turn.cljs:570-590`). It installs that uncommitted
value into the ambient transaction context, passes it into `open-turn!`, passes
it again into `ask-and-eval!`, and finally pulls by it
(`src/seon/agent/turn.cljs:597-640`). The scheduled-turn path duplicates the
same pattern (`src/seon/agent/loop.cljs:569-598`).

`open-turn!` already has the right durable bracket: its first transaction leads
with the run CAS fence and creates the turn row, and it invokes the body only if
that transaction succeeds (`src/seon/agent/turn.cljs:263-284`). The missing
piece is ownership of identity allocation.

### Eval

`eval-batch!` currently calls `db/new-id!` before dispatching every parsed entry
and places that value in per-entry transaction metadata
(`src/seon/eval.cljs:4912-4921`). A repaired span reuses the first pre-minted id
and calls `db/new-id!` for each additional repaired entry
(`src/seon/eval.cljs:4964-5001`). The outer loop then appends the pre-minted id
regardless of whether a row committed (`src/seon/eval.cljs:5037-5040`).

The normal form path executes and awaits arbitrary code at
`src/seon/eval.cljs:4127-4136`, but binds its process-local result before the
durable record transaction at `src/seon/eval.cljs:4246-4252`. The record occurs
later at `src/seon/eval.cljs:4355-4367`. Parity and REPL-form paths have the same
ordering (`src/seon/eval.cljs:4437-4465` and `src/seon/eval.cljs:4714-4735`). A
record failure can therefore leave a live `result/<id>` whose eval row never
committed.

`record-eval!` correctly assembles the turn component link and tee operations in
one transaction (`src/seon/eval.cljs:3222-3237`). On a tee failure it retries the
bare eval row and stamps `:seon.eval/record-error`
(`src/seon/eval.cljs:3238-3285`). Its unsafe assumption is that the caller has
already supplied a fresh id.

### The current schema self-tee gate

`seon.schema/register!` has an eager durability hook. During agent eval,
`tee-registered-schema!` suppresses that hook by testing for
`:seon.db/eval-id` in ambient transaction metadata, leaving the gated
detect-and-tee path to persist a successful registration atomically with the
eval (`src/seon/eval.cljs:2798-2879`). Removing the pre-minted id without
replacing this *execution-boundary marker* would let a later-failing form persist
its schema early.

The replacement is a private `seon.eval` AsyncLocalStorage marker such as
`run-with-record-boundary`. It wraps each complete per-entry dispatch, including
async evaluation and repaired-entry dispatch. `tee-registered-schema!` checks
that marker rather than transaction metadata. It is ephemeral execution scope,
never a datom and never transaction metadata. Do not reuse the print-capture ALS;
print routing and schema durability are different responsibilities.

This marker is necessary only while the current self-tee hook exists. The later
canonical Malli/program reconstruction phase may delete the hook and marker
together; this cutover must not make the old hook eager in the meantime.

## Target turn control flow

### `open-turn!` owns turn allocation

Keep the existing two-argument function but change the callback contract:

```clojure
(open-turn! turn-input
  (fn ^:async [committed-turn-id]
    ...))
```

Remove `:seon.agent.turn/id-of-turn` from `turn-input`. At synchronous entry,
capture all candidate-independent facts exactly once: connection, `at`, agent
id, run id, prompt size, rendered coordinate, prompt blob ref, and scheduled
marker. Then call:

```clojure
(db.id/allocate!
  {::db.id/allocations
   [{::db.id/key ::turn
     ::db.id/identity-attr :seon.agent.turn/id}]
   ::db.id/transaction-builder
   (fn [{turn-id ::turn}]
     {:seon.db/tx-data
      [(db/cas-assert agent-ref :seon.agent/run run-ref)
       (assoc stable-turn-row :seon.agent.turn/id turn-id)]})
   :seon.db/conn conn})
```

Omit the CAS item when the turn is intentionally runless, matching current
behavior. The builder may be invoked repeatedly, but it only associates the new
candidate into stable data. A collision does not recapture the prompt, create a
second blob, advance the clock, or call the body.

On allocation failure, return its error envelope and do not invoke the body. On
success, read the committed id from `::db.id/ids`, establish any still-required
temporary turn transaction context with that *committed* value, and call
`close-turn!` around `(body-fn turn-id)`.

Until the provenance phase deletes `:seon.db/turn-id`, preserve current debug
behavior without pre-minting:

- the allocator builder may include the candidate as the open transaction's
  explicit turn metadata; and
- the post-commit body/close span may carry the committed id in ambient context.

That is a short dependency bridge, not an alternate identity path. Phase 3
deletes the metadata writer and the debug joins. Do not preserve eval-id
metadata merely for symmetry; no current reader requires it.

After a turn has committed, every return path should retain its identity.
`open-turn!` should return a map carrying `:seon.agent.turn/id`, including when
the body throws and the bracket marks the turn `:error`. This prevents a real
persisted turn from becoming an id-less "catastrophic" result. The allocation
failure path is the only path with no turn id because it created no turn.

### `run-turn!` and scheduled turns

`run-turn!` keeps its map-in/map-out API. Delete the local `turn-id` mint. Pass a
one-argument callback to `open-turn!`; inside it:

1. log the committed turn id;
2. call `ask-and-eval!` with that id; and
3. return its body result.

After `open-turn!` returns, pull by the returned committed id rather than a local
candidate. The LLM is therefore called only after the run fence and turn
identity have committed.

`exec-scheduled-fns!` makes the identical change: no local mint, and its
`open-turn!` callback receives the id passed to `eval-batch!`. Turn allocation
has one owner for both ordinary and scheduled work.

## Target eval control flow

### `record-eval!` owns eval allocation

Remove `:seon.eval/id-of-eval` from the request. Keep the committed parent turn
id and the candidate-independent observation fields. Return a specified
envelope:

```clojure
;; success
{:seon.db/ok? true
 :seon.eval/id committed-eval-id}

;; failure
{:seon.db/ok? false
 :seon.db/error error-map}
```

An optional internal `:seon.eval/tee-recorded?` boolean is justified only if the
post-record instrumentation path uses it to avoid instrumenting a definition
whose tee was dropped. Do not add it as stored state.

At entry, capture the connection, current agent ref, timestamps, source,
narration, output, ending namespace, result envelope, and complete tee vector.
The allocator builder receives one `::eval` candidate and constructs:

```clojure
{:seon.db/tx-data
 [{:seon.agent.turn/id committed-turn-id
   :seon.agent.turn/evals
   [(assoc stable-eval-row :seon.eval/id candidate-eval-id)]}
  ...tee-operations]}
```

The nested eval identity assertion, component ref assertion, and accepted tee
operations therefore retain one transaction id. The builder is synchronous and
side-effect free. It may only associate candidate-dependent values, produce a
total candidate-dependent result hint, and assemble the already-frozen data.

For a pending Promise, carry a private runtime `:seon.eval/pending?` fact into
the recorder rather than building `(pending-placeholder eval-id)` before an id
exists. The builder renders the placeholder with its candidate. The live
Promise remains outside the builder.

`render-result-edn` is documented pure but currently calls `error/record!` in a
defensive catch. A retryable transaction builder must not run that side effect.
Make candidate-dependent rendering genuinely total/pure, or return a pure
fallback and report a renderer defect once after the allocation attempt. Do not
render, query the DB, inspect the analyzer, read the clock, or mutate a registry
inside the builder.

### Tee-drop recovery

The existing transcript-first recovery remains valid with one change in
ownership:

1. Execute the form once and freeze the outcome/tee.
2. Call `allocate!` with eval + tee.
3. If a non-allocator transaction error is attributable to a nonempty tee, call
   `allocate!` again with the same frozen eval outcome and no tee. This second
   call may choose a different id. It never executes the form again.
4. Stamp `:seon.eval/record-error` by intentional known-id update using the
   fallback call's committed id.
5. Return only that committed id.

Do not enter tee-drop recovery for allocator exhaustion, malformed manifest,
invalid builder, missing committed-EID response, or unresolved write ambiguity.
Those failures are allocator/core failures, not evidence that the tee was bad.
This also keeps the sixteen-attempt bound meaningful instead of silently giving
a failed eval another sixteen rounds through the fallback path.

### Bind `result/<id>` after commit

The normal, parity, and REPL-form paths all follow this order:

1. execute/derive the outcome once;
2. build tee data once;
3. await `record-eval!`;
4. on record success, read `:seon.eval/id`;
5. bind the live value at `result/<committed-id>`; and
6. for a pending Promise, attach the existing late-settlement replacement to
   that committed id.

If persistence fails, bind nothing. If a candidate collides, no rejected
candidate is ever visible in `globalThis.result`, the analyzer namespace, the
transcript, or `:seon.eval/ids`. The already-fixed bounded result eviction and
"late settlement cannot resurrect" behavior remains unchanged.

Follow-up repair datoms, instrumentation, and auto-test execution occur only
after the record returns. The repair update uses the returned committed id.
Neither instrumentation nor the auto-test runner belongs in an allocator
builder.

### Internal function contracts

The smallest dependency-safe internal changes are:

| Function | Input change | Return change |
|---|---|---|
| `record-eval!` | Remove `::id-of-eval`; retain committed turn id and frozen outcome/tee | Specified success/error envelope with committed `:seon.eval/id` |
| `eval-form-entry!` | Remove `::id-of-eval` | Return the recorder envelope/id instead of only mutating counters |
| `record-form-result!` | Remove `::id-of-eval` | Bind after commit and return the recorder envelope/id |
| `dispatch-repl-form!` | Receives no eval id through its request | Propagate the recorder envelope/id from the selected branch |
| `dispatch-eval-entry!` | Remove `::id-of-eval` | Return the one committed record outcome |
| `eval-batch!` | Public arguments unchanged; `turn-id` must already be committed | Append ids returned by dispatch; never append a local candidate |

Keep the current fold volatiles local to one batch for this phase. Replacing
their implementation is unrelated to identity correctness.

### Repaired reads

Delete the "first repaired form reuses this entry's id" branch. No original id
exists. For each repaired entry, call `dispatch-eval-entry!`, await its
recording result, and append its committed id. For an unrepairable read entry,
call `record-eval!` once and append its returned id. Remove the outer
unconditional append.

The invariant becomes structural and simple:

```clojure
(:seon.eval/ids batch)
;; exactly the committed rows, in dispatch order
```

No phantom id represents the pre-repair span, and a two-form repair yields two
distinct eval rows and two result slots only when those forms produced live
values.

## Why Datahike supports this boundary

Datahike resolves identity upserts before converting an entity map to
operations (`reference-code/datahike/src/datahike/db/transaction.cljc:534-622`
and `:845-871`). Its nested-map expansion converts nested ref maps into ordinary
operations in the same transaction (`:646-674`). The transaction loop applies
maps and operations to an immutable report and only publishes the resulting DB
after the complete loop succeeds (`:1105-1215`). Therefore the nested eval row,
turn component link, and tee are already the correct atomic unit; Seon only has
to stop supplying an unsafe id.

The normal Datahike writer is serialized: it dequeues one operation, calls its
configured write function with the exact prior DB, and advances to that
operation's `:db-after` (`reference-code/datahike/src/datahike/writer.cljc:43-112`).
The local writer merges a runtime `write-fn-map` with its defaults at
`:185-203`. Seon's namespaced writer backend supplies that private function at
runtime while the durable config stores only the backend keyword. That is the
correct seam for direct connections: the shared preparation function runs
inside the writer operation, not in a client-side preflight that could race the
commit or in an unserializable persisted config.

ClojureScript `await` is a macro that is legal only in an async environment
(`reference-code/clojurescript/src/main/clojure/cljs/core.cljc:975-977`). The
analyzer derives that environment from `^:async` function metadata
(`reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc:2324-2341`),
and the compiler emits a native async function
(`reference-code/clojurescript/src/main/clojure/cljs/compiler.cljc:945-959`).
Consequently the arbitrary eval/Promise await belongs in the one async outer
operation. The allocator's builder must remain an ordinary synchronous function;
calling it again cannot implicitly await or replay the eval.

## Allocator correction: tempids, not planned positive EIDs

### Failure in the current plan

The current shared helper plans contiguous positive candidate EIDs above a
conservative estimate. That does not make the later EIDs reserved.

Datahike handles an entity map with an explicit positive `:db/id` by using that
number (`transaction.cljc:861-870`). Applying its datoms advances the database's
`max-eid` (`transaction.cljc:500-514`). A later entity map without an id then
uses `next-eid` (`transaction.cljc:861-865`). This transaction is therefore
unsafe:

```clojure
[candidate-A-with-explicit-eid-100
 noncandidate-with-no-id
 candidate-B-with-explicit-eid-101]
```

After A, `max-eid` is 100. The middle entity auto-allocates 101 before B is
processed. Nested maps make the actual processing order still harder to predict.
An upper bound above the *old* `max-eid` cannot reserve several future positive
numbers once the first explicit number moves `max-eid`.

### One shared replacement

The serialized AVET preflight already proves each candidate absent under every
generator-managed identity attribute. Under that same serialized writer, a
normal Datahike identity assertion cannot race into an upsert. Concrete positive
EIDs are unnecessary.

The one CLJC preparation function should:

1. validate the manifest and scan both `db-before` and the incoming transaction
   for candidate-value reuse across all generator-managed identity attributes;
2. assign each candidate entity a collision-free allocator-owned tempid, while
   preserving intentional same-entity grouping;
3. rewrite matching tempid refs as needed without assigning positive EIDs;
4. return allocation-key to tempid, not allocation-key to guessed EID;
5. let Datahike allocate and commit normally; and
6. resolve allocation-key to EID from the committed report's `:tempids`, then
   attach that map as `:seon.db.id/generated-eids`.

The incoming-transaction scan is required. Checking AVET only in `db-before`
still allows one builder to assert the same candidate value under another
generator-managed identity attribute in the same commit.

Both adapters use the same preparation and conflict classifier:

- the JVM wire adapter calls them while holding the sole-writer serialization;
- a direct Datahike connection names the Seon writer backend, whose runtime
  method installs one private `transact!` operation so preparation receives the
  exact writer `old` DB and returns a normal report; and
- manifest-less transactions delegate byte-for-byte to Datahike's ordinary
  writer function.

Do not implement a CLJS query-then-`d/transact!` fallback. That would be a second,
non-atomic algorithm. Production remains wire-authoritative; the direct adapter
exists so isolated behavior tests exercise the same preparation under
Datahike's own serialized local writer.

Normalize candidate conflicts to adapter-neutral `seon.db.id` data. The current
allocator recognizes the wire string `"generated-candidate-conflict"`; the
direct writer should not need to forge a wire error. Wire transport may carry a
wire-specific kind, but retry classification must use the same namespaced
candidate-conflict marker and exact manifest entry on both paths.

## Behavioral tests

### Allocator/writer prerequisites

- A transaction ordered as candidate A, auto-allocated noncandidate, candidate
  B commits three distinct entities and returns the queried EIDs for A and B.
- The same property holds when candidate entities are nested through component
  refs.
- Reusing a candidate value under another generator-managed identity attribute
  in the same incoming transaction rejects the whole transaction with zero
  datoms.
- The direct in-memory writer and JVM wire writer return the same canonical
  prepare/conflict outcome and allocation-key-to-EID behavior.
- An unrelated unique/schema/domain failure is not classified as a generated
  collision and does not rerun the builder.

### Eval

- **Irreversible execution across collision:** force the compact generator to
  emit one already-used candidate and then a fresh candidate. Evaluate a form
  that increments a side-effect counter. Assert the counter is one, one new eval
  row exists, the returned id is the committed row's id, and no row/result handle
  exists for the rejected candidate.
- **Result binding after commit:** force sixteen exact candidate conflicts.
  Assert the form ran once, no new eval datom committed, `:seon.eval/ids` contains
  no uncommitted id, and no live result/analyzer handle was created.
- **Fallback id is authoritative:** make a tee operation fail while the bare
  eval row remains valid. Assert one eval row, one turn component ref, a
  `:seon.eval/record-error`, and `result/<returned-id>` resolving the value. Do
  not assert the explanatory error string.
- **Atomic tee:** query the transaction column of the eval identity assertion,
  turn component assertion, and a successful tee assertion; assert all are the
  same transaction.
- **Many repaired entries:** force one read repair to return two dispatchable
  entries. Assert two distinct committed ids in dispatch order and two rows; no
  third id represents the original broken span.
- **Pending Promise:** force one collision before commit, then settle the Promise.
  Assert the placeholder and later live value both name/update only the committed
  id and late settlement cannot resurrect a pruned slot.
- **Schema rollback without eval metadata:** run a form that registers a schema
  and then fails. Assert neither DB row nor runtime registration remains. Run the
  corrected form and assert one eval row plus one schema row. This proves the
  private record-boundary marker replaced the old eval-id metadata gate.

### Turn

- **Collision before body:** force one turn-id collision. Assert exactly one turn
  row is added and the LLM/body counter is one. The returned id, prompt/reply
  links, and eval parent all resolve to that committed turn.
- **Lost fence:** use a stale run ref. Assert the allocation transaction creates
  no turn and the body/LLM counter remains zero.
- **Committed identity survives body error:** throw from the body. Assert the
  returned error value carries the committed turn id and the stored turn is
  terminal `:error` under that id.
- **Scheduled path:** fire a scheduled function and assert one scheduled turn,
  its committed compact id, and its eval component link. There is no second
  turn-id path.
- **Write ambiguity:** simulate a response lost after a proven commit. Assert the
  allocator recovers the same id and invokes the body once.

These tests should force candidate sequences through a private test seam or the
shared writer fixture. Do not assert package word choices, random literal output,
or context text.

## Caller and test migration inventory

### Production

- `src/seon/agent/turn.cljs` — turn schema policy, `open-turn!`, `run-turn!`,
  logging, post-open context, and pull-by-returned-id.
- `src/seon/eval.cljs` — `record-eval!`, pending placeholder construction,
  post-commit result binding, tee self-suppression, all entry dispatch functions,
  repaired-entry loop, and `eval-batch!` id collection.
- `src/seon/agent/loop.cljs` — scheduled turn creation/callback.
- `src/seon/ai.cljs` and `src/seon/agent/debug.cljs` — no identity creation
  change, but they require the temporary post-commit turn context until Phase 3
  replaces their old turn-metadata reads.
- `src/seon/db.cljs` — delete `new-id!`, `id->time-str`, and old generator
  helpers only after repository-wide callers are migrated.
- `src/seon/gym/driver.cljs` — do not build a gym-specific compatibility path.
  The gym is a retirement/replacement target. Sequence its deletion or minimal
  canonical caller cutover before deleting `new-id!`.

### Direct `record-eval!` tests

`test/seon/eval/record_eval_tee_test.cljs` owns the direct helper and every
direct request carrying `:seon.eval/id-of-eval`. Change its `eval-args` helper to
omit that key and make assertions read the id returned by `record-eval!`. Preserve
the structural success/fallback/record-error/schema-rollback tests; update the
old comments that describe eval-id transaction metadata.

### `eval-batch!` integration tests

The public batch signature stays stable, but these tests currently obtain an
uncommitted parent with `db/new-id!` and must instead run their batch inside an
allocated `open-turn!` callback (or one shared test helper that does exactly
that):

- `test/seon/eval/auto_refer_test.cljs`
- `test/seon/eval/preflight_repair_test.cljs`
- `test/seon/eval/print_capture_test.cljs`
- `test/seon/eval/promise_ergonomics_test.cljs`
- `test/seon/eval/prose_demote_test.cljs`
- `test/seon/eval/record_eval_tee_test.cljs`
- `test/seon/eval/repair_batch_test.cljs`
- `test/seon/eval/repl_forms_test.cljs`
- `test/seon/eval/result_var_test.cljs`
- `test/seon/agent_loop_test.cljs` for the direct batch-fence cases

Use one shared eval test helper that opens a real turn and passes its committed
id into a callback. Do not copy allocator builders into each test. The
back-to-back-batch test can run both batches inside one callback. The stale-run
fence test can open under the old run, supersede inside the callback, then invoke
the batch with the now-stale run token.

`test/seon/eval/memory_safety_test.cljs` exercises pure result rendering/storage
helpers with synthetic runtime ids; it does not create persistent identities and
needs no allocator fixture. Keep its structural clipping assertions.

### Turn/agent integration tests

- `test/seon/agent/turn_capture_test.cljs` — use canonical agent mint or an
  intentional known fixture; `run-turn!` itself remains unchanged. Keep
  prompt/reply/basis/turn identity assertions. Its tx-trail assertion remains
  only until the provenance phase replaces the old metadata join.
- `test/seon/agent_retry_test.cljs` — `ask-and-eval!` still receives an already
  committed turn id from its fixture; build that fixture through `open-turn!`,
  not `new-id!`.
- `test/seon/repl/autocomplete_test.cljs` — migrate agent setup; `run-turn!`
  retains its API.
- `test/seon/agent_loop_test.cljs` — scheduled and direct fence cases as above.
- `test/seon/gym/driver_test.cljs` — retire with the gym harness; migrate only
  unique behavioral regressions to their owning namespaces.

`test/my/plan_test.cljs` and several source files mention `record-eval!` only in
comments; they have no call signature to migrate. Repository-wide removal of
`db/new-id!` will touch additional run/message/plan tests owned by their
respective allocator callsites, outside this eval/turn-specific design.

## Ordered migration

1. Correct shared allocation preparation to tempids/report resolution, add the
   in-writer direct-memory adapter, and make collision data adapter-neutral.
2. Prove the allocator prerequisites above on JVM wire and CLJS direct-memory
   paths.
3. Register compact generator policy on turn/eval identity attributes.
4. Add the private async eval-record boundary marker and switch schema self-tee
   suppression off `:seon.db/eval-id`.
5. Refactor `record-eval!` to allocate and return the committed id. Preserve
   atomic tee and bounded, classified tee-drop recovery.
6. Refactor result binding and the single-entry dispatch functions bottom-up so
   every branch propagates the committed recorder result.
7. Refactor the repaired-entry and main batch loops; delete every eval pre-mint,
   eval-id context write, and unconditional id append.
8. Refactor `open-turn!`, then ordinary `run-turn!`, then scheduled turns. The
   body receives only the committed turn id.
9. Migrate the shared fixtures and focused tests. Do not add exact prose
   assertions.
10. Remove `::id-of-eval`, turn input `id-of-turn`, `db/new-id!`,
    `id->time-str`, stale docs/comments, and every old production caller in the
    same phase. No alias remains.
11. Run focused allocator, record/tee, repair, result-var, turn, loop/schedule,
    and retry tests; then one authoritative full CLJS run.
12. Cold-restart the default pod and live-drive one effectful eval, one pending
    Promise, one multi-form repair, one normal LLM turn, and one scheduled turn.
    Query their datoms and exercise `result/<id>`. Leave ACME untouched.

## Exit proof

The cutover is complete when a repository search finds no `db/new-id!`,
`:seon.eval/id-of-eval`, or caller-supplied
`:seon.agent.turn/id-of-turn`; every newly created turn/eval identity traces to
`seon.db.id/allocate!`; forced collisions never duplicate an LLM/eval side
effect; every returned result symbol resolves the exact committed eval row; and
the eval identity, turn component ref, and accepted tee rows share one
transaction.
