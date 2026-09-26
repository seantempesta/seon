---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, read-evidence, turn, settlement, dogfood]
---

# A query with a regex literal cannot record its read evidence, and the run stays open forever

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 10d6fdff5, pid 39759, 2026-09-26)

Reproduced twice, on branches `dogfood-repl-opus-5` and `dogfood-repl-opus-6`. The minimal
form, on a fresh branch after `(+ 1 2)` answered `3`:

```clojure
(seon.db/q '[:find (count ?s) . :where [?f :seon.fn/sym ?s] [(str ?s) ?t] [(re-find #"merge" ?t)]])
```

- MCP `eval_clj` answered `timeout` after 60 s.
- The branch's error window recorded `seon.db/transact!`: "The EDN-backed attribute
  `:seon.db/read-request` has an invalid logical value." (`src/seon/schema/datahike.clj:321`).
- The run's turn (`ae1fe1c09258`, opened 03:08:36Z) was still **open** a minute later. Its one
  evaluation row had no error and no duration, and no JVM thread was running SCI or a Datahike
  query. The run was dead but never settled.
- Every later run on the branch answered `run transition refused: agent-already-running`. The
  agent was wedged until I retired the branch. Retire worked.
- A regex that reads no datoms (`[(re-find #"a" "abc") ?x]`) answered `"a"`, because no read
  evidence is recorded for it.

## Which model is wrong

1. **Read evidence stores a copy of the request.** `:seon.db/read-request` stores the query form
   itself, as an EDN-backed value. A query is code, and code may hold host values such as a
   `java.util.regex.Pattern` from `#"…"`, which is not EDN. Store references, never copies: the
   evidence should hold the query's **source text or its digest**, which the reader already
   has, not the evaluated form.
2. **A recording failure leaves execution open.** The run could not write its evaluation facts, and
   nothing closed the turn. The laws say to close interrupted turns and mark unfinished
   evaluations, and that a recording failure is not success. The settlement owner must close the
   run with the recording refusal as its error, and that error must reach the agent's reply, not
   an MCP timeout.

## Expected

The query answers its count. If some recorded value cannot be stored, the run still settles: the
evaluation shows the refusal, the turn closes, and the next run is admitted.

## Regression

Run the form above on a branch. It answers a number, and a second run on the same branch is
admitted.

## Design (2026-09-26, lost-error-seams lane)

**What exists.** `seon.db/read-evidence` (db.clj) turns captured reads into evidence maps. The durable caller is
the evaluation receipt, `turn.clj:4867`, which calls `(db/read-evidence @captured)` and stores each map as a
`:seon.cluster.eval/read-evidence-entity` component. Process-local render callers pass
`{:seon.db/retain-read-results? true}` and never store what they get (render.clj, render/walk.clj,
render/web.clj). Both kinds of evidence carry `:seon.db/read-request`, which is a copy of the query form and
its arguments, together with a result digest. `read-evidence-current?` replays that copy (`replay-read`) to tell
a real change from a revision bump.

**Why the copy is the wrong model.** The durable replay is a second semantic comparator. `system-turn`
(turn.clj:2146) already re-evaluates a changed source and silences it when its shown value is unchanged; the
replay only lets it skip that preview. The price is storing code: `#"…"` becomes a host `Pattern`, which is not
EDN, so the receipt write refuses.

**(a) Change, db.clj only.** The durable evidence stops carrying the request and its digest. It keeps what
Datahike reports: the dependency plan and revision. The evaluation that owns the evidence (a component)
references the source. Retained, process-local evidence keeps the request, the result and the digest for its
own replay, which is never stored.
- Cost: a changed revision now always previews the source instead of replaying the query. That is one SCI
  evaluation instead of one query, only when a read's revision changed.
- Deletions: no durable request copy, and no result digest on durable rows.
- Follow-up: `seon.cluster.eval.edn`'s `read-evidence-entity` drops its now-unproduced `:seon.db/read-request`
  and `:seon.db/read-result-digest` members, and adoption retires the attributes.

**(b) Settle on recording failure (turn.clj, the dogfood-API lane).** A refused receipt or evidence write must
close the run with that refusal as its error, through the ordinary settlement, so the next run is admitted.
It must never leave the turn open. See the hunk handed to root.
