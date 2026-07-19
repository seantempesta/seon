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

## Stale database assumptions in Inspect

The pod's `/agents/run` response emits `database`, an ordinary database value
with `db_name`, basis transaction `t`, temporal flags, and commit ID.
`seon_inspect.solver` now retains that as `pod_database_value`. The next live
database scorer is still incompatible:

- `milestone.py` and `reachability.py` read the nonexistent
  `pod_database_coordinate` key;
- they expect invented `{database_id, branch, commit_id, t}` wrappers instead
  of the database value the runtime produces;
- turn evidence supplies `rendered_transaction`, not a
  `rendered_coordinate` map; and
- eval evidence supplies eval ID, turn ID, eval transaction, time, success,
  source, and narration. Production emits no per-eval `operation_evidence`,
  tagged request/result tree, or `coordinate_valid` field.

The current typed product seam is already sufficient for durable facts:
`POST /_seon/operator/product-evidence` evaluates one typed query against one
immutable database value and returns both the database value and result. The
minimal correction is to compare eval and rendered transactions with the
database value's `t`, use explicit final read-back queries for scenario facts,
and delete the test-only operation wrapper assumptions. Only add operation
capture if a future scorer truly needs ephemeral arguments or results that are
not durable database facts.

## Ordered next boundary

1. Prove the clarified fixed namespace contract for three consecutive live
   samples.
2. Standardize milestone and reachability scorers on `pod_database_value`,
   basis transaction `t`, and `rendered_transaction`.
3. Wire the fixed database scenario to scenario-specific Datahike queries
   through the existing typed product-evidence endpoint.
4. Retain operation evidence only where the database cannot derive the fact;
   do not rebuild a generic parallel event system for the scorer.
