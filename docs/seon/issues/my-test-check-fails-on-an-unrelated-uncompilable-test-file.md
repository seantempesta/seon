---
type: issue
status: open
severity: blocker
created: 2026-09-26
tags: [issue, test, my-test, dogfood]
---

# `my.test/check` fails on an unrelated test file that does not compile, and `my.test/run` answers nothing

A hosted agent cannot run the tests that reach its change. That is step three of the
dogfood milestone.

## Observed (MCP SCI on a branch of default, archive fbd0897df, 2026-09-26)

```
user=> (my.test/check {})
seon.sci.kernel/failure-value refused  at []: expected the declared requirement
(:successful-evaluation), got nil nil. Fix: Syntax error compiling var at
(seon/cluster/boot_test.clj:147:43). Inspect the named requirement before retrying. …
Contract finding: seon.sci.kernel/failure-value returned this error, and none of its declared
error alternatives matches it.
```

The agent had changed only `user/dre-sq` and defined `user/dre-sq-test`. The same answer
appeared on archive 052552039.

`(my.test/run)` gave two different answers on the two archives:

- On 052552039 it answered `[]`, and so did `(my.test/run-owned {})`, right after the agent
  defined `dre-sq-test` in its own namespace. An empty answer reports health from the absence of
  a signal.
- On fbd0897df, in a later run after the test had settled, it answered
  `seon.sci.kernel/failure-value refused … Fix: Nothing found for entity id
  [:seon.cluster.eval/id "116da86a91eb"]`.

## Expected

- `check` selects the tests that reach the change. A file outside that selection cannot fail
  the request. If a selected test cannot load, the refusal names that test and its file.
- `run` answers the agent's namespace tests with their results, or a declared refusal.
- The error keeps its cause chain rather than `got nil nil`.

## Regressions

Each regression is a branch agent that defines a contracted function and a `deftest` over it:

- `(my.test/check {})` answers a run result that includes the test.
- `(my.test/run)` answers that test's result.
