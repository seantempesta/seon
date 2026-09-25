---
type: report
status: stopped at a held file; no code committed
created: 2026-09-25
tags: [agent-platform, turn, read-evidence, performance]
---

# Lane fix-read-sink-opus: landing (2026-09-25)

`seon.turn/evaluate-sources` binds `db/*read-evidence-sink*` around the whole
`gate-function-install` + `evaluate-for-install` call (src/seon/turn.clj:4753). Every read
the platform makes while installing, checking program identity, deriving the profile,
running gate tests or rendering is recorded as the form's read evidence. The fix is to
scope the sink to the form's own evaluation, which happens only inside `eval-form!` in
`seon.sci.eval/evaluate` (src/seon/sci/eval.clj:4210-4235). That file, and
`resources/seon/schemas/seon.sci.eval.edn`, belong to impl-repl-s1-revise-2-opus (running).
**This lane stopped before editing and committed no code.**

## Broken or open first

1. **The defect is not fixed.** The fix needs 3 lines in `src/seon/sci/eval.clj`, which this
   lane does not hold. Committing only the turn.clj half would drop all evidence, because
   `evaluate` ignores the new request key. That would make every read look current.
2. The regression was not written, because it would fail until the sci/eval half lands.
3. No test ran (load rule). RESET NEEDED: no.

## What the REPL showed

Probe: in JVM mode on the disposable branch `fix-read-sink` (now retired), a watch on a
sink atom recorded the Seon stack frames of each append. The probe called
`seon.sci.eval/evaluate` with the branch's context, as `script/seon/dev/mcp.clj:535` does.
Each probe was one form.

| form | reads | form's own | platform producers |
|---|---|---|---|
| `(inc 1)` | 5 (5.6 ms) | 0 | 4 × `db/database-identity` ← `sci.eval/commit-id-of` ← `acquired-database?` / `program-identity` ← `call-detecting-program-change` (sci/eval.clj:1374); 1 × `db/pull` ← `config/effective` ← `render/request-profile` |
| `(seon.db/q '[:find ?n …])` | 6 (9.6 ms) | **1**, via `eval-form!` | the same 5 |
| `(my.turn/turns-left)` (unresolved on the branch) | 6 | 0 | the same 5, plus 1 `db/q` ← `error/refusal-text` ← `render-ai` (render of the refusal) |

What stored turns hold (read-only pulls on default):

- `(inc 1)` and `(+ 1 1)` (evaluations 87611 and 87627) hold 7 reads each. Two more come from
  the turn side, including a `:seon.source/commit-id` query.
- **`(quote 1)` (evaluation 73041, ordinal 0, system turn) holds 7,286 reads.** These break
  down as:
  - 5,235 index reads with no request. Their plans name `:seon.fn/calls`,
    `:seon.fn/references` and `:seon.test/subject` (1,745 each), the program-graph obligation
    walk (`db.clj:4058`, `test.clj:432`);
  - 447 namespace-row wildcard pulls. They use the selector at `sci/eval.clj:1510` / `:2585`
    (install and context construction);
  - 107 config pulls, 107 config queries, and per-function `[* ]` pulls.

  `(quote 1)` cannot read, so every one of these is platform work that ran inside the
  sink's scope.

## Fix, ready to apply when sci/eval.clj frees

It reuses the existing seam: `seon.sci.kernel/invoke` already takes the sink as request data
(`:seon.db/read-evidence-sink`, schema at seon.sci.eval.edn:148-150, kernel.clj:597-612).
`evaluate` takes the same key.

- `tmp/fix-read-sink/turn.patch` (+5/−3). The turn passes `:seon.db/read-evidence-sink
  captured` in the request and removes the `binding`. The body of `call-with-walk-context`
  then needs re-indenting by two columns.
- `src/seon/sci/eval.clj`, about +3:
  - inside `eval-form!` (:4223), wrap `(sci/eval-form execution-ctx form)` in
    `(binding [db/*read-evidence-sink* (:seon.db/read-evidence-sink request)] …)`;
  - wrap the success `shown-result` call (:4356) in the same binding. A lazy value realized
    while printing, and a renderer's or walk's reads of the value (walk.clj:529 appends to
    the ambient sink on purpose), then stay evidence of the shown text.
- `resources/seon/schemas/seon.sci.eval.edn` `:request` (:178): add
  `[:seon.db/read-evidence-sink {:optional true} :seon.db/read-evidence-sink]`.

Net is about +8 lines. It deletes the whole-install capture, and nothing else needs to move.

The other sinks (render/web.clj, walk.clj:449, call_preparation.clj:314/735) are separate
render/cache scopes, not duplicates. The `binding … nil` in call_preparation is the existing
exclusion seam and stays.

Remaining risk after the fix: platform work that runs lazily *inside* a call the form makes
still lands in scope. The 73041 kinds (graph walk, namespace pulls) have to be re-counted
after adoption. If they persist, their producer binds the sink to nil, as call_preparation
does.

Expected effect: `(inc 1)` goes from 7 reads to 0, and `(quote 1)` from 7,286 to 0. A form
with one `seon.db/q` goes to 1.

One consequence to accept: `read-only-evaluation?` (turn.clj:1959) requires a non-empty
evidence vector. A form that reads nothing is no longer treated as a generated read, which
is correct, since it has nothing to go stale.

## Regression to land with the sci/eval half (test/seon/cluster/turn_test.clj or sci/eval_test.clj)

Through `evaluate-sources` on a test branch:

- `(quote 1)` stores an empty `:seon.cluster.eval/read-evidence`;
- `(seon.db/q '[:find ?n . :where [_ :seon.ns/name ?n]])` stores exactly one read, whose
  request is that query.

## Root runs (after both halves land)

```
bin/test-check default --ns seon.read-evidence-test
bin/test-check default --ns seon.rereads-test
bin/test-check default --test seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts
bin/test-check default --ns seon.cluster.turn-test
bin/test-check default --ns seon.sci.eval-test
```

## Lines

This lane changed only this note. Source and tests are +0/−0.
