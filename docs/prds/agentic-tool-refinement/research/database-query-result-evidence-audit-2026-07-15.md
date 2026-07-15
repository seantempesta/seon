---
type: research
status: complete
tags: [research, agent, database, flow]
---

# Database query-result evidence audit

## Decision

P0b must not accept the database workflow from eval source and final prose
alone. The shortest correct design is to strengthen Seon's existing database
read-observation mechanism into one per-eval database-operation evidence
mechanism. It records the actual compact `transact!` response and actual
`query` result, persists the complete normalized observation in `my.blob`,
links that blob to the ordinary eval entity, and projects only bounded proof
descriptors through `/agents/run` into native Inspect metadata.

This is global observability, not a database-workflow hook. It benefits every
future tool or scorer that needs to distinguish a call written in source from
an operation that really succeeded. It adds no prompt prose, answer key, writer
REPL, database backdoor, or second evaluation harness.

## Dependency ledger

The audit observed these exact source coordinates before design:

- Seon `20e38b194be6598f7ec1e157e52142c6cf441da5` in the shared checkout;
- maintained Datahike fork
  `eb3e2239b650635977fdc8e73e7c657b23bf3383`, selected by `deps.edn` and
  mirrored at `reference-code/datahike/`;
- Inspect AI `05322696a0f784ec399ef6abbafd3d2a250ea9cc`, installed into the
  `src-inspect-ai` environment from the local directory recorded in
  `src-inspect-ai/uv.lock`; and
- Inspect's nested view source is intentionally dirty under its separate
  pinned overlay. It is unrelated to the Python log/scorer mechanisms used
  here.

The existing mechanisms and first-party call sites are:

- `src/seon/db.cljs`: `query` calls `execute-query`, which records the exact
  normalized request and result when `seon.db.internal/read-capture-als` is
  active; `transact!` already returns a compact success envelope with the full
  committed database coordinate, tx, datom counts, and `:seon.db/ok?`;
- `src/seon/db/internal.cljs`: `run-with-read-capture`,
  `current-read-captures`, `record-read!`, and `normalize-read-value` are the
  one existing immutable operation-observation substrate;
- `test/seon/db/read_observer_test.cljs`: proves transitive query capture,
  host-scalar normalization, foreign database classification, bounded query
  requests, absence of runtime handles, and replay behavior;
- `src/seon/eval.cljs`: `record-eval!` allocates the durable eval identity and
  atomically attaches it to its owning turn. Successful result display is
  already capped before `:seon.eval/result-edn` is written;
- `src/seon/agent/turn.cljs`: turn prompt and reply bytes already use the
  required three-tier pattern: full content-addressed `my.blob` bytes plus a
  database ref, never a large text datom;
- `src/seon/web/serve.cljs`: `/agents/run` takes one final immutable database
  value, scopes turns and evals to the request's runs, and projects exact turn
  bytes, final coordinate, and eval source/ok/narration;
- `test/seon/web/serve_test.cljs`: directly proves request scoping and stable
  eval projection;
- `src-inspect-ai/src/seon_inspect/solver.py`: `_record_result` preserves the
  door's coordinate, turn evidence, and eval evidence in `TaskState.metadata`;
- `src-inspect-ai/src/seon_inspect/milestone.py`: `check_store_recall` consumes
  only eval source/ok plus reply today; `pod_milestone_driver` accepts only the
  door response and introduces no side channel;
- `src-inspect-ai/src/seon_inspect/tasks/milestone_lift.py`: retains the
  generated oracle only in host-side sample metadata and uses native Inspect
  solvers/scorers;
- `reference-code/inspect-ai/src/inspect_ai/scorer/_metric.py`: `Score.metadata`
  is the public structured scorer-evidence field;
- `reference-code/inspect-ai/src/inspect_ai/log/_file.py`: `read_eval_log` and
  `write_eval_log` are the public native-log persistence/read-back boundary;
  and
- `reference-code/inspect-ai/src/inspect_ai/log/_log.py`: `EvalSample.metadata`
  is retained with the native sample and is the existing home of pod evidence.

Datahike query tuples expose a datom's transaction as their fourth position.
The maintained source projects the absolute transaction id, and Seon already
orders evals by their eval datom's monotonic transaction id in
`src/my/plan/internal.cljs`. No wall-clock or new stored position is needed for
this proof.

## Exact current data flow

The generated seed-1 row carries five records, a strict threshold, and the
expected answer in host-only `metadata["oracle"]`. Only its goal-stated input
is sent to the pod.

The current live path is:

1. `milestone_lift` creates the Inspect sample and host oracle.
2. `milestone_solver` calls `pod_milestone_driver`.
3. `pod_run` posts only the task text to `/agents/run`.
4. The Seon loop persists each eval as a component of its turn. Each eval has
   id, time, source, ok, result display or error, and agent ref.
5. The door reads one final immutable database value, selects only runs opened
   by this request, selects their turns and evals, and sorts evals by
   `[wall-clock, eval-id]`.
6. `project-eval-evidence` emits only eval id, time, ok, source, and optional
   narration. It deliberately omits `:seon.eval/result-edn`.
7. `_record_result` copies that projection and the final database coordinate
   into native Inspect sample metadata.
8. `check_store_recall` regex-checks successful eval source for both schema
   registrations, all five literal records in a `transact!` form, a later
   threshold `query` form, and later human/completion calls containing the
   oracle answer. It also checks the final reply for that number.

Two distinctions are therefore lost:

- an eval of `db/transact!` is `:seon.eval/ok? true` when the function returns
  an error envelope, because the Clojure form itself completed normally; and
- an eval source can contain the correct query while its actual query result
  is absent, different, or discarded in favor of prompt arithmetic.

The final coordinate proves where the evidence projection was read. It does
not manufacture the missing operation outcomes.

## Shortest falsifier

The smallest offline falsifier needs no pod:

```python
oracle = generate_rows("database_workflow", 1, 1)[0]["metadata"]["oracle"]
rows = structurally_valid_source_rows_for(oracle)
assert check_milestone("db", rows, oracle["answer"], oracle)["ok"]
```

Those rows contain no result field at all. The current scorer returns correct.
Changing an imagined query result to the wrong number cannot change the score,
because the scorer has nowhere to receive it.

A second fixture makes the transaction defect explicit: give the transaction
eval a real `{:seon.db/ok? false ...}` result while leaving its eval-level
`ok=True`; the current scorer still passes. These are deterministic scorer
discrimination failures and should be the first red tests.

## One-mechanism design

### Capture database operations at the real boundary

Generalize the existing read-capture stack to database-operation capture. Keep
its current normalized request/result representation and nested ALS semantics.
Add a `transact!` observation beside the existing `query`, `pull`, and index
read observations. The transaction observation is recorded only after the
Promise resolves, and carries its compact returned envelope; a rejected write
therefore remains visibly `:seon.db/ok? false`.

Open this operation-capture scope once around each agent eval. The scope must
remain active through the eval's awaits and close before `record-eval!`; Node
AsyncLocalStorage already provides the required fiber isolation. The public
`capture-reads` API remains synchronous for render invalidation. Eval uses the
same internal substrate with an awaited callback and reads its own bucket only
after the callback settles.

Each observation records:

- database operation keyword;
- zero-based operation position inside the eval;
- normalized request;
- normalized response/result;
- complete coordinate of the actual database value read, or the committed
  coordinate returned by `transact!`;
- captured versus foreign database source; and
- replayable status where that concept applies.

No observation is created when no database operation occurred. Absence remains
the signal; do not transact an empty marker.

### Persist once through the existing blob tier

Serialize the ordered observation vector as canonical EDN, write the full
bytes through `my.blob/put!`, and link its lookup ref from the eval entity. The
eval identity, owning turn, and blob ref must land in the same `record-eval!`
transaction. A content-addressed blob written before a later rejected eval
record may be unreferenced, but it cannot masquerade as evidence.

Do not add request/result strings as datoms. Do not copy observations onto the
turn or run. The path remains:

```text
run -> turn -> eval -> operation-evidence blob
```

The eval's identity datom transaction is the durable eval order. The
operation position is order within that one eval. The transaction/query
coordinates establish operation order, and the door's final coordinate must
be on the same database attachment and no earlier than every accepted proof
coordinate.

### Project bounded descriptors at the composition door

From the same final database snapshot already used by `/agents/run`, add an
`operation_evidence` vector to each selected eval row only when its blob ref is
present. The descriptor carries eval id, turn id, eval transaction id,
operation position, operation, complete database coordinate, success flag,
blob hash, byte/token counts, and bounded inline request/result data.

Small canonical values are included exactly. A value over the configured
inline cap is replaced by a descriptor containing its content hash, full blob
hash, exact size, and bounded preview; its tail never enters JSON or the
native log. The database workflow scorer fails closed if the transaction
records or scalar query answer are not exact inline values. It never guesses
from a preview.

This evidence is host-facing Inspect metadata. It is not rendered back into
the agent transcript, so it cannot amplify errors or teach a small model to
echo result grammar.

### Strengthen the existing scorer

Keep one `milestone_scorer`; do not add a companion scorer or post-hoc repair.
For a generated database workflow it requires:

1. the existing schema source checks;
2. one transaction observation on the identified transaction eval whose
   compact response is successful and whose normalized tx data contains the
   oracle's exact five identity/measure pairs in one request;
3. a later query observation on the identified query eval, against the same
   database attachment, whose normalized request names the oracle measure and
   strict threshold and whose actual scalar result equals the oracle answer;
4. transaction coordinate before query coordinate, both no later than the
   door's final coordinate;
5. the existing later human and completion reports, both equal to the actual
   observed scalar result; and
6. exact eval/turn membership and eval-transaction ordering from the request
   evidence, not list position supplied by a fixture.

The scorer may compare retained proof with the host oracle. The pod never
receives the oracle.

## Bounded schema and size rules

- Full operation observations live only in content-addressed blobs. Database
  entities retain refs and small scalar identity/coordinate facts.
- Inline proof uses the existing database-derived render cap rather than a
  scorer literal. If measurement shows operation proof needs a distinct cap,
  add one manifest property under the general observability/config owner,
  reconcile it into the config singleton, and read it from the database.
- Add a database-configured maximum observation count per eval. On overflow,
  retain the configured prefix plus omitted count and full-vector blob hash;
  a scorer whose required operation was omitted fails closed.
- Never inline stack traces, raw errors, database handles, entities, functions,
  or arbitrary JS objects. `normalize-read-value` already removes those runtime
  handles and should remain the single normalizer.
- Every descriptor has a fixed set of fields. Missing evidence is absence, not
  `null`, an empty fabricated row, or a success default.
- JSON projection sorts by eval transaction id and operation position. It does
  not use millisecond time as the correctness order.
- The native log retains the bounded descriptor. Large proof content remains
  addressable by its blob hash; formal scoring never treats a hash or preview
  alone as the scalar answer.
- The agent-visible eval/result/error caps and the raw prompt/reply blob rules
  do not change.

## Acceptance tests

### ClojureScript and live database boundary

- An eval containing one successful `transact!` records its exact normalized
  tx data, successful compact envelope, committed coordinate, and operation
  position.
- A transaction error envelope is recorded as database-operation failure even
  though the eval itself is successful.
- A later scalar `db/query` records the real scalar and the exact database
  coordinate used by that query.
- Nested helper calls are captured once; concurrent agents cannot cross-write
  observation buckets.
- Two operations in one eval retain operation order; two evals with equal
  millisecond timestamps retain order by eval datom transaction.
- An explicit foreign/historical database read is labeled and cannot satisfy
  a current-attachment workflow proof.
- A large result produces a bounded descriptor with hash and honest sizes; no
  tail, stack, source dump, or runtime handle appears in the door response.
- An eval without database operations has no evidence attribute.
- Pod restart followed by read-back from the same final coordinate reproduces
  the same evidence descriptor bytes.
- `/agents/run` excludes evidence from earlier reused-agent runs and from
  turns outside the request window.

### Inspect scorer and native artifact

- A good offline fixture with exact transaction and query proof passes.
- Structurally perfect source with no operation proof fails.
- Prompt-only arithmetic with a wrong or absent observed query result fails.
- A failed transaction envelope fails even when the eval-level `ok` is true.
- Wrong stored facts, a partial transaction, or records split across
  transactions fail.
- Correct proof attached to another eval, turn, request, database attachment,
  or later/earlier order fails.
- An elided large value never passes as the expected scalar.
- `read_eval_log` after native finalization returns byte-identical bounded
  operation metadata and the unchanged score explanation/metadata.
- One admitted live `database_workflow-seed1-000` proves exact start/end source
  and target identity, exact final database coordinate, five committed records,
  the actual later query result, and both reports in one native `.eval`.

The focused gate should cover the existing database observer, eval recorder,
web serve, milestone, admitted-run, and native-log read-back tests. It should
not run the whole repository suite.

## Implementation path and ownership

Implement in this dependency order, with one owner per mechanism:

1. `src/seon/db/internal.cljs`, `src/seon/db.cljs`, and
   `test/seon/db/read_observer_test.cljs`: generalize the existing capture and
   add transaction observations without changing render replay semantics.
2. `src/seon/eval.cljs` and the focused eval recorder tests: open the
   per-eval scope, persist the canonical blob, and link it on the eval row.
3. `src/seon/config.cljs`, `config/system.edn`, and focused config tests only
   if the existing cap cannot own the measured projection; all numeric policy
   remains manifest/database-derived.
4. `src/seon/web/serve.cljs` and `test/seon/web/serve_test.cljs`: project the
   final-snapshot, request-scoped, transaction-ordered bounded descriptors.
5. `src-inspect-ai/src/seon_inspect/solver.py`,
   `src-inspect-ai/src/seon_inspect/milestone.py`, and
   `src-inspect-ai/tests/test_milestone.py`: retain the descriptor and make the
   existing scorer consume it fail-closed.
6. The admitted native-run/read-back tests: prove Inspect's public metadata and
   log APIs preserve the result without another artifact format.
7. Rebuild only ACME, inspect the exact database facts and door response through
   the repository REPL boundary, then replay only the one admitted database
   sample.

The earliest unsettled contract is closed only by the final admitted native
artifact. Unit tests alone do not close P0b.
