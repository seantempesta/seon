---
title: Web rendered transaction observability unification
type: research
status: completed
tags: [research, prd, database, web]
---

# Web rendered transaction observability unification

## Decision

Make `seon.agent.debug/turn` the one semantic owner of turn reconstruction.
The web composition door remains the one JSON boundary, but it only projects
the ordinary turn bundle returned by that owner. It does not reconstruct a
second turn model from copied database identity, reopen local database
connections, or replay captured eval operations.

The final response database value and each turn's
`:seon.agent.turn/rendered-tx` are sufficient for ordinary historical work:

```clojure
(let [historical (db/as-of final-database rendered-tx)]
  ;; every historical query or pull receives `historical`
  ...)

```

Provider attempts remain ordinary component facts on their parent turn. They
retain the non-secret request values actually used, retry ordinal, timeout,
outcome, and present provider response identity. They do not copy database id,
branch, commit id, or transaction id. Eval evidence retains the eval entity's
ordinary domain facts. The deleted eval operation observer, operation blob,
JSON-tagged replay format, and exact-operation-origin validator do not return
under another name.

There is no compatibility response and no second reconstruction function. The
existing `turn_evidence`, `model_transport_evidence`, and `eval_evidence` JSON
members change in place.

## Dependency ledger

| Dependency or owner | Selected source | Constraint used |
|---|---|---|
| Recovery order | [[roadmap]] and [[system-recovery-graduation-plan-2026-07-16]] | One implementation per behavior; no local database or compatibility evidence path. |
| Eval decision | `f94cf080`, [[eval-native-result-database-value-cut-2026-07-16]] | Eval operation capture and `:seon.eval/database-operations-blob` are deleted. Authority request evidence and explicit diagnostics are the retained alternatives. |
| Turn record | `src/seon/agent/turn.cljs` and `docs/seon/architecture/observability.md` | One native `:seon.agent.turn/rendered-tx` ref replaces four copied turn fields. Attempt facts do not copy database identity. |
| Turn reconstruction | `src/seon/agent/debug.cljs` | `turn` is asynchronous, accepts optional `:seon.db/db`, and returns the rendered transaction plus prompt/reply blob bytes. It is the retained semantic owner. |
| Database facade | `src/seon/db.cljs` | `db/as-of` transforms an ordinary database value; asynchronous query and pull rehydrate it at the authority. There is no CLJS connection or `at-coordinate`. |
| Datahike | `reference-code/datahike` at `a464cd887458d2572414a6ea951c477b0981fdae` | `AsOfDB` delegates indexes to its origin and adds an inclusive transaction-time predicate. Current plus temporal datoms are reassembled into the visible value. |
| LLM config | `src/seon/ai.cljs` | `resolved-config-from-rows` is the one pure config resolver; web evidence must use it rather than the removed `ai/resolved-config`. |
| Web boundary | `src/seon/web/serve.cljs` | `POST /agents/run` remains the one composition response. Its final immutable database value scopes turns, evals, reply, and evidence. |
| Inspect consumers | `src-inspect-ai/src/seon_inspect/{solver,milestone,reachability}.py` | Current consumers require copied coordinates and eval operation replay. They must change atomically with the production response rather than preserving a compatibility payload. |

## Datahike grounding

`datahike.api.impl/as-of` constructs `AsOfDB` only for a history-retaining
database. `AsOfDB` keeps the origin database and requested transaction point;
its search context adds the inclusive predicate `datom-tx <= rendered-tx`.
Index reads delegate to the origin's native indexes, and temporal post-processing
reassembles cardinality-one and cardinality-many facts at that point.

Two consequences matter here:

- `rendered-tx` is a Datahike transaction ref, not a partial home-grown
  database coordinate. It is the correct argument to `as-of`.
- The containing ordinary database value still matters. An as-of wrapper uses
  the origin database's indexes and history. A bare transaction id cannot name
  another database or lineage, so every reconstruction starts from the final
  request database value or an explicitly resolved retained database value.

The origin database can report its current maximum transaction even while an
`AsOfDB` filters reads to an earlier point. Consumers must inspect the ordinary
database value's temporal selection, not infer the historical cut from the
origin's basis alone.

## Current contradiction

The turn and debug owners already use `rendered-tx`, but
`seon.web.serve` still implements the removed model:

- it calls the now-asynchronous `agent-debug/turn` as if it were synchronous;
- it expects four deleted turn attributes and four deleted attempt attributes;
- it calls removed `db/head-coordinate`, `db/at-coordinate`, and
  `db/resolve-transaction-coordinate!` functions;
- it calls removed `ai/resolved-config` and synchronous database reads; and
- it parses an eval operation blob whose producer and read-observation schema
  no longer exist.

The maintained client compile exposes these as undeclared-var warnings. This
is not missing compatibility surface. It is the exact deletion boundary.

## Exact production deletion inventory

### Copied turn identity

Delete from `src/seon/web/serve.cljs`:

- `captured-turn-coordinate-json` and the `:rendered_coordinate` field it adds
  in `turn-evidence`;
- `turn-rendered-coordinate`;
- pulls of `:seon.agent.turn/rendered-database-id`,
  `:seon.agent.turn/rendered-branch`,
  `:seon.agent.turn/rendered-commit-id`, and
  `:seon.agent.turn/rendered-t`; and
- every validation that reconstructs a full coordinate from those four
  fields.

Those attributes are already absent from the current turn schema and client
bootstrap list. Do not register them again. Project the existing
`:seon.agent.turn/rendered-tx` as `rendered_transaction` when an external
consumer needs the historical point.

### Copied attempt identity

Delete from `src/seon/web/serve.cljs`:

- the four identity attributes in `attempt-pull-pattern` and
  `required-attempt-attrs`;
- `attempt-coordinate`;
- attempt JSON `:coordinate` and `:coordinate_valid`;
- `operation-coordinate-valid?` as used for provider attempts;
- per-attempt calls to `origin-valid?`; and
- per-attempt `db/at-coordinate` reconstruction.

The deleted attributes are:

- `:seon.ai.attempt/database-id`;
- `:seon.ai.attempt/branch`;
- `:seon.ai.attempt/commit-id`; and
- `:seon.ai.attempt/t`.

They are already absent from the current attempt schema and bootstrap list.
All attempts under one turn use the parent turn's rendered database value.

### Eval operation replay

Delete from `src/seon/web/serve.cljs`:

- `evidence-order-key` and `evidence-json-value`;
- `coordinate-origin-validator`;
- `operation-json` and `valid-operation-vector?`;
- `supported-evidence-json?`;
- `project-operation-evidence`;
- `json-coordinate->coordinate`;
- `require-exact-operation-origins`;
- the `:seon.eval/database-operations-blob` pull branch in
  `project-eval-evidence`; and
- the asynchronous operation-origin pass in `eval-evidence`.

Delete `:seon.eval/database-operations-blob` from
`src/seon/client.cljs`'s bootstrap attributes. The eval namespace no longer
registers or writes it. Do not add the old `:seon.db/read-observation` schema to
the new facade.

`project-eval-evidence` remains, but becomes a pure ordered projection of the
eval id, parent turn id, eval transaction, timestamp, success, source, and
present narration. It needs no final coordinate or evidence-size cap.

## One replacement flow

### Final request value

After the requested run reaches its terminal state, acquire one latest
ordinary database value. Every response query and pull receives that exact
value. A timeout-closing transaction advances the session cache first, then
the handler acquires the final value once. Do not dereference a connection at
each leaf.

The response may retain a JSON projection of this final database value as
request-scope identity. It is not copied onto turns or attempts.

### Turn evidence

Make `turn-evidence` asynchronous. For each selected turn id, call the one
owner:

```clojure
(await
 (agent-debug/turn {:seon.db/db final-database
                    :seon.agent.turn/id turn-id}))

```

Project its status, time, `rendered-tx`, prompt/reply bytes and token counts,
and present errors. Do not read the blob archive again in web code. Ordering
continues to come from the request's already selected `turn-rows`.

### Historical provider evidence

Select `:seon.agent.turn/rendered-tx` with each turn row. For one turn:

1. derive `historical = (db/as-of final-database rendered-tx)`;
2. acquire the agent row, AI config row, and cluster config row at
   `historical`, using one bounded authority request;
3. call the existing `ai/resolved-config-from-rows` pure owner;
4. derive adapter, endpoint, response-identity bounds, and repl mode from
   those rows; and
5. compare every attempt component against that one resolution.

`historical-attempt-config-valid?` therefore accepts an already derived
resolution and an attempt. `historical-turn-stream-valid?` accepts the
historical repl mode and attempts. Neither accepts a connection or coordinate.
The final JSON contains attempts under their parent turn, without a database
identity per attempt. A missing or invalid rendered transaction makes that
turn's transport evidence malformed and fails closed.

The historical acquisition may use `ai/config-pull-pattern`,
`ai/model-transport-pull-pattern`, and `ai/resolved-config-from-rows` directly.
Do not recreate a second config resolver in web code and do not call the full
prompt renderer merely to recover config.

### Eval evidence

Query evals from the final request database value and selected turn entity
set, sort by their native transaction id, and pull only the retained eval
facts. This preserves order and request membership without claiming to record
every database operation or external side effect performed by the eval.

If a scorer requires proof of a particular query result, it must request one
explicit bounded authority diagnostic or score persisted domain facts. It may
not make eval-wide operation capture a hidden prerequisite of ordinary
execution.

## Identity that remains legitimate

Do not globally delete `seon.db.coordinate` vocabulary. These uses name an
authority or lifecycle resource rather than copying a turn's database state:

- authority committed-event and listener positions used for ordered feed
  delivery and resynchronization;
- restore target, prepared, forced-main, undo, and completion identity;
- blob retained-set materialization at an exact restore/retention point;
- historical web-feed selectors that must name a retained attachment before
  the authority can produce an ordinary database value; and
- Datastar subscription/render-byte identity and `view_unit`'s unrelated
  presentation coordinate.

The `/agents/run` final database identity is also legitimate request-scope
evidence. What is deleted is the repeated database id, branch, commit id, and
transaction id on every turn and every provider attempt.

## Test disposition

### Delete

From `test/seon/web/serve_test.cljs`, delete:

- `evidence-operations`;
- `operation-evidence-projection-is-bounded-lossless-and-fail-closed`;
- `exact-transaction-origin-is-deduplicated-and-fails-closed`; and
- `tagged-evidence-order-is-recursively-stable-and-unsupported-fails`.

Delete
`test/seon/eval/promise_ergonomics_test.cljs`'s
`eval-hands-awaited-database-operations-to-the-recorder`; the eval audit
already identifies it as a test of a removed mechanism.

Remove copied-coordinate assertions from
`test/seon/agent_retry_test.cljs`, including
`retry-keeps-one-coordinate-pinned-config-resolution`'s attempt commit-id
assertion and the timeout test's attempt commit-id assertion. Preserve their
real claims: every retry receives the same captured config resolution,
ordinals/outcomes are ordered, secrets are absent, and timeout aborts once.

### Rewrite in place

In `test/seon/web/serve_test.cljs`:

- `eval-evidence-is-request-scoped-and-stably-ordered` no longer supplies a
  final coordinate and proves no `operation_evidence` key is manufactured;
- model-transport fixtures contain no attempt database identity;
- historical config/stream proof supplies one `rendered-tx`, constructs
  `db/as-of` from the final ordinary database value, and proves all attempts
  use the parent turn's resolution;
- model-transport JSON proves ordering, bounds, response-identity validation,
  transport drift, and failure on missing/invalid `rendered-tx`; and
- turn evidence proves the web awaits `agent-debug/turn` with the exact final
  database value and does not reread blobs.

Keep same-origin, operator peer, readiness, restore, and blob-retention tests.
Their coordinate use belongs to the legitimate lifecycle boundaries above.

### Inspect consumers

Update atomically:

- `src-inspect-ai/src/seon_inspect/solver.py` and its tests: provider attempts
  no longer require `coordinate` or `coordinate_valid`; require parent turn
  membership plus successful historical config validation from the production
  projection;
- `src-inspect-ai/src/seon_inspect/milestone.py` and its tests: delete
  `_operations` and every required `operation_evidence` mutation;
- `src-inspect-ai/src/seon_inspect/reachability.py` and its tests: delete
  decoded operation replay and copied `rendered_coordinate` checks; use
  `rendered_transaction` only for membership/ordering metadata, and score
  effects from persisted facts or an explicit diagnostic; and
- solver/admitted-run/frozen-tool fixtures: replace copied turn/attempt
  coordinates with one final database identity and per-turn
  `rendered_transaction`.

Do not weaken a scorer silently. A row whose only evidence was captured query
result replay remains explicitly unsupported until its task owns a bounded
authority diagnostic or a persisted-domain-fact oracle.

## Implementation order

1. Make the `/agents/run` terminal read phase acquire one ordinary database
   value and await all database operations.
2. Make `turn-evidence` an asynchronous projection of `agent-debug/turn`.
3. Select `rendered-tx` with turn rows and port model transport validation to
   one `db/as-of` value per turn.
4. Delete copied turn and attempt identity plus origin validation.
5. Reduce eval evidence to retained eval facts and delete operation replay.
6. Delete the stale client bootstrap attribute and obsolete tests.
7. Update Inspect consumers in the same response-contract commit.
8. Reconcile the composition-door paragraphs in
   `docs/seon/architecture/observability.md` and the localized web authority;
   neither may continue to promise eval operation blobs or copied attempt
   coordinates.

## Minimal proof

The shortest source falsifier requires zero matches in
`src/seon/web/serve.cljs`, `src/seon/agent/turn.cljs`, and their bootstrap
attribute lists for:

```text
rendered-database-id
rendered-branch
rendered-commit-id
rendered-t
seon.ai.attempt/database-id
seon.ai.attempt/branch
seon.ai.attempt/commit-id
seon.ai.attempt/t
database-operations-blob
read-observation
db/at-coordinate
db/head-coordinate
db/resolve-transaction-coordinate!

```

Then run focused proof that:

1. one final ordinary database value is passed identically to turn, eval,
   reply, and model-evidence acquisition;
2. two attempts under one turn are checked against one
   `(db/as-of final-database rendered-tx)` resolution;
3. two turns with different rendered transactions resolve independently;
4. missing rendered transaction, failed historical query, malformed attempt,
   or oversized evidence fails closed as data;
5. eval rows remain request-scoped and transaction-ordered without an
   operation blob; and
6. the client compiles with no warning in `seon.web.serve`, then the focused
   CLJS web tests and affected Inspect Python tests pass.

The later coordinated live gate drives one `/agents/run` request containing a
provider retry and database-using eval, verifies turn prompt/reply bytes and
provider facts from the production response, and confirms the response never
claims arbitrary eval-operation replay.

## Exit measure

The cut is complete when `POST /agents/run`, the debug page, and Inspect all
consume the same ordinary turn record: one final request database value, one
`rendered-tx` per turn, prompt/reply blobs, eval entities, and attempt component
facts. No web or scorer code reconstructs copied database coordinates or reads
an eval operation blob, and lifecycle/feed selectors retain their exact
authority identity without being confused with turn history.
