---
type: issue
status: open
severity: friction
created: 2026-09-26
tags: [issue, schema, sci, repl, dogfood]
---

# `seon.schema/register!` works only as a top-level form

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 04f3a159b, branch `dogfood-repl-opus-10`, 2026-09-26)

- `(seon.schema/register! :dogfood.book/year [:int {:min 0}])` as a top-level form answered
  `:dogfood.book/year`.
- The same call inside a `let`, to time it: `(let [m (seon.profile/begin) _ (seon.schema/register! …)] …)`
  answered `seon.sci.kernel/failure-value refused … got nil nil. Fix: Schema declarations require an isolated
  registration delta.`
- `(def mark (seon.profile/begin))`, then the top-level `register!`, then `(seon.profile/explain mark 12)`
  worked: 299 ms, with `seon.schema.datahike/declared-storable?` called 14,580 times and `seon.db/with-declarations`
  944 times. The first run on the branch, three `register!` calls, took 1,786 ms (turn open to close).

## Which model is wrong

A function whose meaning depends on being the whole top-level form is a special form in disguise.
The registration delta is opened by the evaluator only when it recognizes a top-level `register!`
form, so the same call in any other position has no delta and refuses. "A form is parsed once and
evaluated once; its results and facts are stored". The evaluation of *any* form should carry the
registration delta as one of its recorded effects, exactly as it carries `defn` rows, so that
`register!` is an ordinary function wherever it is called. That includes inside `do`, `let`, a helper
function, or a test body.

## Expected

`(let [k (seon.schema/register! :x/y :int)] k)` answers `:x/y`, and the declaration settles with the run.

## Regression

A `register!` nested in `let` and in a helper function each installs its attribute.

## Re-verified open 2026-09-26 (dogfood-repl-opus-2, cycle 10, archive 6ccf12a5a)

`(let [n :dogfood.c4/thing] (seon.schema/register! n [:string {:min 1}]))` → "Execution error (ExceptionInfo) …
Schema declarations require an isolated registration delta."
