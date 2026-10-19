---
type: issue
status: open
severity: minor
created: 2026-10-19
tags: [issue, test, web, branch, flake]
---

# The chains-page test retires a chain branch while the feed pass may still read it

## Problem

`seon.render.web-test/the-chains-page-lists-every-chain-and-repaints-on-main-commits`
was recorded red in the stored check logs from 2026-10-15 09:35 to 2026-10-19 23:12
(`docs/research/agent-platform/2026-10-19/head-reds.md`, group G3b). The error is
uncaught from the test's `finally`:

```
branch :chain-... still has a connection in this process
```

`seon.cluster.registry/retire-branch!` refuses with `::cluster-connected` when
`datahike.api/delete-branch!` reports `:branch-has-active-connection`.

## Evidence (lane wake-reds, 2026-10-19)

At HEAD `c0479f90d` the var did not reproduce:

- `bin/seon check --at HEAD --test seon.render.web-test/the-chains-page-lists-every-chain-and-repaints-on-main-commits`:
  pass 7, fail 0 (3735 ms).
- `bin/seon check --at HEAD --test seon.render.web-test` (whole namespace): the var green;
  the five reds were the G3/G3c vars only.

## Hypothesis (unconfirmed)

The test closes its feed stream (`.close stream`) and then retires every
`chain-` branch at once. The feed's pass reads every chain at its branch's head,
so a pass still running when the stream closes holds a connection to the chain
branch. The test does not wait for that pass to exit, which production would wait
for. This would be an intermittent red that depends on timing.

## Next step

Run the var alone several times at HEAD to measure how often it fails. If it fails,
have the test await the feed pass's exit (the handler's own termination event)
before `retire-branch!`. Do not add a retry around the retire.
