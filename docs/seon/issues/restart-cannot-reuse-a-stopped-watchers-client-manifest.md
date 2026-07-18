---
type: issue
status: active
tags: [issue, cljs, pod, flow]
---

# Restart cannot reuse a stopped watcher's client manifest

## Evidence

On 2026-07-18 a normal `bin/seon restart` stopped all three managed processes,
then selected the content-verified manifest fast path. The replacement watcher
compiled all client, execution, and test builds successfully, but remained
not-ready for more than a minute. The writer and pod were never started.

Watcher readiness compares the current client closure with the published
client digest. Shadow's development output includes watcher-specific runtime
data, so a newly started watcher cannot be admitted against the stopped
watcher's manifest. Source and pre-start output bytes matching are insufficient
when no live watcher owns those output bytes.

The interrupted operator was followed by normal `bin/seon down`; watcher,
writer, and pod all report absent. The committed user message for
`tricky-terms-shine` remains database data for the resumed restart proof.

## Acceptance

- Manifest reuse requires the managed watcher that published the current
  client output to remain alive.
- A complete restart enters the existing frozen-source publication path and
  publishes the replacement watcher's first completed flush.
- Writer-only recovery still retains healthy watcher and pod identities.
- Focused operator tests and the interrupted committed-work restart proof pass.
