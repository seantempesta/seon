---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime]
---

# Web-render record survives isolated operator down

## Evidence

On 2026-07-23, fresh `predfix` resets repeatedly reached pod readiness and
then failed while reconciling web-render:

```text
Refusing to replace a managed process without clean-or-force evidence.

```

The retained reset transcript is `tmp/orchestrator/predfix-up.log`. Before the
final proof, `bin/seon down` reported pod, host, and writer absent, but a
web-render owner/anchor from an earlier interrupted reset remained live and its
record was later classified as `:seon.dev.process.status/managed-process-present`.

This is downstream of the accepted schema/client reconstruction proof and did
not prevent the fresh pod from reaching `auto-boot ready`.

## Expected owner

The operator lifecycle owner must include web-render in the same recorded
clean-or-force recovery closure as pod, host, and writer. Do not bypass the
managed-process safety check or kill the child directly.

## Acceptance

- An interrupted reset followed by `bin/seon down` leaves no live or recorded
  web-render generation.
- A subsequent isolated reset can reconcile web-render after pod readiness.
- A recurring operator lifecycle regression proves the interruption boundary.

## Resolution

Resolved by `fe5e289b9`. Lifecycle membership, dependency order, shutdown
order, rebuild-reader selection, log selection, and returned absence evidence
now derive from the canonical owned-process graph. A missing or duplicate
requested result fails loudly.

Exact recorded owner, anchor, and workload identities now determine whether a
generation is live. A generation whose recorded `(pid,start-instant)`
identities are all dead is consumed automatically during down or replacement;
a live recorded generation still refuses replacement.

`seon.dev.process-test/dead-web-render-generation-is-consumed-before-replacement`
kills a real web-render workload, proves down consumes its record, proves
replacement succeeds without cleanup or force, and proves a live foreign
generation remains protected. The focused operator gate passed 118 tests and
545 assertions in `tmp/orchestrator/procfix-gate.log`.

The isolated `procfix` live proof booted all five processes, killed only the
recorded web-render workload, completed ordinary down with all five requested
results, reset the cluster to full readiness without manual cleanup, and ended
with a clean down plus all five records absent. Its transcript is
`tmp/orchestrator/procfix-live.log`.
