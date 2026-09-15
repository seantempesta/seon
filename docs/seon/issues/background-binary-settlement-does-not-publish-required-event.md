---
type: issue
status: open
severity: friction
tags: [issue, runtime, blob, effect, class/n10, wave/background-settlement]
---

# Publish terminal evidence for every background binary result

## Problem

The complete suite can submit a background binary capability and then receive
no transaction event identifying its terminal receipt. The test times out
before it can check bytes, so the background/blob boundary has no proof that
every accepted request settles observably.

## Evidence

At clean commit `48eb25ab7`,
`seon.background-blob-test/background-binary-results-remain-exact-across-the-inline-threshold`
errored at `background_blob_test.clj:148`: `await-event!` reported “The test
channel did not publish its required event.” The request carried an explicit
environment and connection, so this is not evidence for the previously fixed
missing-dynamic-binding cause in
[[every-background-capability-request-loses-its-connection]]. Full evidence is
at `tmp/full-gate-2026-08-10b.log:790-832`.

## Owner

Suspected owner: `seon.effect` background settlement and the blob-result
transaction/listener boundary. The first probe should inspect the retained
database and worker completion before assigning the cause to either side.

## Acceptance

- Every accepted background binary request produces one queryable terminal
  receipt or one flat terminal error.
- The test waits on that durable transition and reaches all byte-exactness
  assertions across the inline threshold.
- A focused repetition distinguishes worker non-completion, refused commit,
  and lost listener delivery instead of reporting only a channel timeout.

## Re-verified at HEAD (2026-09-15)

UNVERIFIABLE-WITHOUT-GATE (`seon.background-blob-test`, after repairing setup). Before the no-JVM correction, ran `bin/test-fast --paths docs/seon/issues/background-binary-settlement-does-not-publish-required-event.md -- seon.background-blob-test`. Snapshot HEAD `ad5780df5856f1adf30fd1995706c0564a716129`, no snapshot differences, contracts armed (983 instrumented). Result: 1 test / 1 assertion, 0 failures / 1 error. `background_blob_test.clj:98` threw NullPointerException in `(dec threshold)` because the threshold query returned nil. No background request was submitted: this does not reproduce lost terminal delivery. Audited HEAD still queries `:seon.config.eval.result/blob-threshold` at `test/seon/background_blob_test.clj:93-98`; settlement remains at `src/seon/effect.clj:488-503`. Fix sketch: obtain the current declared effect threshold in the canonical fixture, then rerun the byte/event regression. Downgraded to friction: current proof is a broken regression setup, not a live execution failure.

surface: runner-gate
