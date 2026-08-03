---
type: issue
status: resolved
severity: blocker
tags: [issue, render, error, sci]
---

# An internal contract violation renders its whole candidate-forms registry as the error payload

## Problem

An internal contract violation retains and serializes its complete offending
argument even when that value is the schema registry.

## Evidence

Curriculum research probe, 2026-08-03
([bootstrap-curriculum-2026-08-03.md](../../../prds/sci-execution-runtime/research/bootstrap-curriculum-2026-08-03.md)
§Gaps): the first-defn failure's error value is 276,363 characters because
the violation dumps the complete schema registry / candidate forms into the
payload, allocating ~264 MB en route. Ugly-output standing order applies:
the agent face of an error must be one to three honest lines with bounded
evidence, never a registry dump.

A direct 2026-08-03 probe after fixing the distinct first-touch bug invoked
the instrumented candidate inspector with the registered schema population as
its offending value. `seon.instrument/violation` constructed exception data
containing 1,044,086 printed characters and allocated 150,063,304 bytes in
47 ms. The message was only 134 characters and the problem projection only
371 characters; the admitted `::args` value contributed 1,042,697 characters.
This proves the payload has a different owner from the candidate-projection
prevalidation fixed by `6329da95b`.

## Owner

The error construction site in the contract-violation path (instrument /
install seam). The error-model wave's default renderer plus the
`:seon.instrument/contract-violated` override (the offending key/value
pair) is the target face; the payload must be bounded at CONSTRUCTION, not
merely windowed at print time — a 264 MB allocation happens before any
renderer runs.

Concretely, `seon.instrument/violation` currently runs `m/explain` and
`malli.error/humanize`, then admits the complete offending argument value into
`::args`. `seon.config/result-caps` carries structural admission caps but not
the 4,096-character blob threshold, so the registry is traversed and retained
before any renderer or blob decision can help. The remaining fix must bound or
summarize the offending argument at this construction seam while retaining the
offending key/value, expected shape, problem count, and a capped context. It
must not be implemented as print-time truncation.

## Acceptance

A contract violation's error value is bounded at construction (the
offending key, value, expected shape, and a capped context), renders
through the declared class face, and the 276 KB reproduction from the
research doc returns a value under the admitted inline ceiling.

## Resolution

Resolved in `577ccbc59` at the construction seam. Contract violations now
retain the exact offending key and a bounded offending value, the expected
shape, the complete problem count, and capped representative problem context;
the complete original argument is never installed in the error value. No
print-time truncation was added.

The registry-sized regression constructs a 966-character error value and
allocates 4,819,968 bytes, down from 1,044,086 characters and 150,063,304
bytes. It asserts the constructed value remains below the 4,096-character
inline ceiling and allocation remains below 16 MiB.

After consumer commit `c6a81988c`, `seon.sci.eval-test` passed 49 tests and
221 assertions, including
`failed-evaluation-assembles-failure-presence-facts`. The complete focused
gate (`seon.instrument-test`, `seon.error-test`, `seon.sci.eval-test`, and
`seon.schema-test`) passed 100 tests and 415 assertions with zero failures and
zero errors.
