---
type: issue
status: resolved
severity: blocker
tags: [issue, ai, test, wave/ai-retry-evidence]
---

# Reconcile generated model-attempt traces with durable facts

## Problem

The seeded model-attempt oracle disagrees with the durable turn facts for a
credential failure with no backup and zero retries.

## Evidence

At HEAD on 2026-08-29, explicit `bin/test seon.cluster.turn-test` reproducibly
failed `generated-model-attempt-traces-preserve-presence-and-episode-laws` at
seed `202607280402`. The shrunk counterexample was one credential failure,
`backup? false`, and `maximum-retries 0`. The property does not exercise the
launcher or caps fixtures changed by the effective-config census sweep.

Exact shrunk case from the repair-lane reproduction:

```clojure
#:seon.cluster.turn-test
{:outcomes
 [#:seon.cluster.turn-test
  {:error-class :credential
   :kind :seon.ai/no-credential
   :transmitted? false
   :outcome :failure}]
 :backup? false
 :maximum-retries 0}
```

## Owner

The model-attempt settlement derivation and its independent oracle in
`seon.cluster.turn-test`.

## Acceptance

The fixed-seed property agrees with durable attempt facts for every generated
partition and the complete turn namespace is green.

## Resolution

The durable attempt facts were correct. The property fixture's
`::backup? false` scenario omitted backup attributes from a sparse config
update, but omission inherits the shipped OpenRouter backup; it does not
retract it. The loop therefore correctly failed over after the credential
failure and recorded two attempts while the oracle expected one.

The canonical turn fixture now explicitly sets the optional
`:seon.config.ai.backup/model` decision to `seon.config/absent`. Scenarios that
exercise failover continue to opt in through the existing backup helper, so
the generated input and the database configuration describe the same world.

Verified 2026-09-02 with
`bin/test seon.cluster.turn-test`: 55 tests, 391 assertions, 0 failures,
0 errors. The known concurrent preflight-sweep failure did not occur; this run
needed no retry.
