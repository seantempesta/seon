---
type: research
status: active
tags: [research, agent, database, milestone]
---

# Live Inspect contract audit — 2026-07-19

## Question

Why did the first source-admitted namespace milestone fail after 16–23 turns,
and what will block the later-turn database scenario next?

## Native evidence

The retained logs are:

- `src-inspect-ai/logs/2026-07-19T06-32-40-00-00_milestone-lift_GeCsGY69cPQimaKyPuXLGZ.eval` — all provider calls succeeded, but the stale Inspect validator demanded test-only `coordinate` fields;
- `src-inspect-ai/logs/2026-07-19T06-41-13-00-00_milestone-lift_c2GEy5hAqDMJH4babEvKLW.eval` — transport admission passed, then an unrelated local-model-server identity requirement rejected the configured remote provider;
- `src-inspect-ai/logs/2026-07-19T06-48-22-00-00_milestone-lift_4Ebn4FuB9Cug67rqGoEi3n.eval` — the first completely admitted sample reached the scorer and missed only the conversion-value check; and
- `src-inspect-ai/logs/2026-07-19T06-52-09-00-00_milestone-lift_fxN7bWkJXsVehcJBqs9K3B.eval` — three admitted samples completed with two passes, one real `NaN` result, zero fabrication, 13–23 turns, 46–89 evals, and 164.5–264.0 seconds.

Commits `33fcc17b` and `defe85a2` align transport admission with the real
producer. Each attempt carries `historical_config_valid`; the run carries one
ordinary database value. A configured remote provider is scorable from its
retained provider, endpoint, request model, response model, request ID, and
source identity without pretending that Seon owns or hashed its weights. A
present local model-server identity remains strictly validated for formal
same-artifact comparisons. Commit `df5a761e` accepts either a two-decimal
conversion or the mathematically equivalent more precise result within 0.01.

## Turn and context cost

The dominant delay is model repair work, not the Bun-to-JVM database hop.
`src/seon/agent/loop.cljs` continues after every productive turn until
`complete`, an explicit bound, or three consecutive zero-form turns. The
retained agents consumed 13–23 turns because the namespace contract said to
use `db/query` without showing its quoted Datalog form. Several agents emitted
an unquoted `[:find ?m ...]`; ClojureScript correctly treated `?m` as an
undefined variable, and later work compounded that failure. The adjacent
database contract already teaches a quoted query.

The first turns contain roughly 26,400 context tokens plus 536 system tokens.
That is primarily the stable documentation for 14 toolkit namespaces, not the
423-token task. Anthropic requests already put stable context behind the
provider cache boundary. Growth toward 30,000–34,000 tokens is transcript and
eval history accumulated by repair turns. Turn reopen overhead is generally
0.6–1.2 seconds; provider work is commonly 3–10 seconds and reaches 15–40
seconds on large-output turns. This evidence does not implicate database
transport as a material delay.

The shortest falsifier is now part of the maintained contract: teach the exact
quoted map form
`(db/query {:seon.db/query '[:find ?m :where [_ :my.units/meters ?m]]})`, then
rerun three fixed epochs. If undefined-variable repairs disappear and the
median turn count drops, interface teaching was the primary cost. A later
profiling pass should compare saved prompt blocks for the observed
26k→9k→26k swing before changing context selection.

## Database scorer correction

The pod's `/agents/run` response emits `database`, an ordinary database value
with `db_name`, basis transaction `t`, temporal flags, and commit ID.
`seon_inspect.solver` retains that as `pod_database_value`. The milestone
database scorer previously remained incompatible:

- `milestone.py` and `reachability.py` read the nonexistent
  `pod_database_coordinate` key;
- they expect invented `{database_id, branch, commit_id, t}` wrappers instead
  of the database value the runtime produces;
- turn evidence supplies `rendered_transaction`, not a
  `rendered_coordinate` map; and
- eval evidence supplies eval ID, turn ID, eval transaction, time, success,
  source, and narration. Production emits no per-eval `operation_evidence`,
  tagged request/result tree, or `coordinate_valid` field.

The current typed product seam is sufficient for durable facts:
`POST /_seon/operator/product-evidence` evaluates one typed query against one
immutable database value and returns both the database value and result. The
minimal correction is to compare eval and rendered transactions with the
database value's `t`, use explicit final read-back queries for scenario facts,
and delete the test-only operation wrapper assumptions. Only add operation
capture if a future scorer truly needs ephemeral arguments or results that are
not durable database facts.

The milestone path now implements that correction. It retains ordered eval
rows only to prove the successful schema/transact/query/report/complete
sequence, requires each eval transaction to be at or before the final database
value's basis transaction, and calls the typed product-evidence endpoint once
to read the actual identity/measure pairs. The scorer compares those final
facts and their thresholded total with the generated oracle. The synthetic
operation request/result tree and its test fixtures are deleted. Focused
milestone/solver proof passes 70 tests, and the change removes 28 net source
and test lines. Live database proof remains required before the issue closes.
Reachability still owns the same stale wrapper vocabulary independently and is
the next source correction after this live milestone falsifier.

The first seeded live row then found one remaining scorer-only assumption. The
contract asked the agent to query the stored records and compute from that
result; the agent did exactly that in two successful evals and the final typed
query returned all five correct facts. The scorer had required the threshold
predicate inside the Datalog form itself. It now recognizes either a direct
aggregate query or a later successful computation that references the query's
`result/...` value, while final database readback remains mandatory. Focused
proof is 71 tests; the seeded live rerun remains the next falsifier.

A second seeded row proved every database fact and computation but missed only
an extra `message/user` call before `complete`. That duplication does not test
database memory: `complete` already supplies the delivered human reply. The
database contract and scorer now require one truthful completion after the
verified computation, and the intentionally frozen `database_workflow`
artifact and hashes were regenerated through the canonical generator. The
combined milestone/generator/solver proof passes 156 tests with 11 net lines
removed in this simplification. A fresh generated run remains required.

The final falsifier passes. The fixed database workflow scored accuracy 1.0
and zero fabrication in 48 seconds
(`2026-07-19T08-16-22-00-00_milestone-lift_j9nCsGHjfczVVYrB7G7c86.eval`). A
fresh seed-3 generated workflow then scored accuracy 1.0 and zero fabrication
in 61 seconds
(`2026-07-19T08-27-42-00-00_milestone-lift_2hSWbcW8Lb6FvYPyNaHtHH.eval`)
using dynamic attributes, exact final
facts, and the typed final database query. Seed 1's correct work exposed the
query/computation mismatch; seed 2's correct database work exposed redundant
reporting; seed 3 proves the simplified contract end to end. The milestone
database scorer is graduated. Reachability is now the earliest stale evidence
consumer.

Reachability now consumes the same production evidence. Turn rows require
`rendered_transaction` at or before the final database value's basis
transaction; eval rows retain the same order and membership rule. Root child
creation is proven by a successful `start!`, the one child ID appearing in a
later database-derived prompt, and a later explicit database query over that
ID, purpose, and parent. Namespace discovery and skill load/unload are proven
by their successful calls and the later appearance or disappearance of the
real function or skill body in context. The scorer-only tagged operation
decoder, database wrapper, and fixtures are deleted. Combined reachability,
milestone, and solver proof passes 114 tests, and no banned database wrapper
vocabulary remains in those owners. A live reachability row is the next
falsifier.

The first root-orchestration live attempt exposed one runtime gap outside the
scorer. A retained branch is intentionally non-autonomous, so inherited root
was durable but not process-hosted. `/agents/run` validated explicit root and
committed its message without calling the existing `agent.runtime/resume!`;
the request waited with no open run until a direct REPL resume proved the
queued message immediately wakes and executes. The task boundary now resumes
an explicit durable agent before injecting its message and before starting the
request timeout clock. Focused web proof passes 22 tests/87 assertions. A fresh
root-orchestration run is required.

## Ordered next boundary

1. Run a live reachability row against production evidence.
2. Run seeded reachability variants against production evidence.
3. Retain operation evidence only where the database cannot derive the fact;
   do not rebuild a generic parallel event system for the scorer.
