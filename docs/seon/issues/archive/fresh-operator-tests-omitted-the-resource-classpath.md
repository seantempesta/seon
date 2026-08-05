---
type: issue
status: resolved
severity: friction
tags: [issue, operator, testing, classpath]
---

# Fresh operator tests omitted the resource classpath

## Problem

Fresh-operator test subprocesses overrode Babashka's classpath with `script/`
alone after the surviving `seon.operator.state` owner moved under `resources/`.
The live `bin/seon` command correctly used `script:resources`, but private
operator probes in the recurring test namespace could no longer load the
operator.

## Evidence

The first process-record regression failed before exercising its subject:

```text
Could not locate seon/operator/state.bb, seon/operator/state.clj or
seon/operator/state.cljc on classpath.
```

Seven test command constructors repeated the incomplete `script/` classpath.

## Owner

`test/seon/dev/fresh_operator_test.clj` owns subprocess construction for the
fresh operator tests and must exercise the same source roots as `bin/seon`.

## Acceptance

- Every fresh-operator test subprocess uses one shared `script:resources`
  classpath value.
- The process-record regression reaches the operator boundary.
- The focused record gate passes.

## Resolution

Resolved by the commit containing this note. One `operator-classpath` value now
feeds all seven subprocess constructors. The focused gate passed 4 tests and
28 assertions with zero failures or errors.
