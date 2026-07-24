---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, operator]
---

# Claimant host drains after clean restart

## Problem

A clean `bin/seon restart` reports the cluster ready after writer, host, pod,
and web-render readiness, but the claimant host then exits. A following
`bin/seon status` reports the cluster degraded with the host drained while the
other processes remain alive.

This was observed after the final-two fixture checkpoint and is independent of
its green writer and operator suites. The same drained host state was present
before the final path-limited fixture commit.

## Evidence

The restart completed with `◆ Seon is ready` and `restart: clean`. The claimant
host log then failed in `seon.host/start!` with:

`Committed schema projection acquisition failed.`

The writer stayed alive and ready, and the pod completed its committed schema
and function acquisition pages.

## Owner

Claimant host admission at `seon.host/start!`, the committed projection
producer in `seon.host.context`, and operator readiness/status reconciliation.

## Acceptance

- A clean restart either keeps the claimant host alive and ready or reports
  the acquisition failure before declaring the cluster ready.
- The projection error value is logged with its exact missing or invalid
  schema/function evidence.
- `bin/seon status` agrees with the restart result without a transient
  ready-to-degraded transition.
- The existing writer, CLJS, and operator checkpoint gates remain green.
