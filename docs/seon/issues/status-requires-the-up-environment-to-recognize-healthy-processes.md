---
type: issue
status: active
tags: [issue, health, pod]
---

# Status requires the up environment to recognize healthy processes

## Problem

`bin/seon status` recomputes the desired writer and pod process specifications
from the status caller's ambient environment. A healthy packaged system is
therefore reported as degraded when `status` omits an environment override
that was present at `up`, even though both processes and their readiness
endpoints remain healthy.

## Evidence

On 2026-07-18, the source-free package at application digest
`f3df6eb22b51c3a40755eac7229b11cda41ec4311280406fadbe5ff072bc372f`
was started with `SEON_PORT=0` and `SEON_FEED_COMPRESSION=gzip`. A later plain
`status` reported both live processes as `not-ready`, while the writer log
contained `[database] ready`, the pod continued logging heartbeats, and `/`
returned HTTP 200 in 1.14 ms. Repeating `status` with the original two
environment values reported both same process records ready. The readiness
probes were not the cause: `process/status` first requires
`same-process-spec?`, whose environment digest is derived again from the
current operator invocation.

## Owner

The one operator process-spec and status boundary in
`script/seon/dev/process.clj` and `script/seon/dev/config.clj`.

## Acceptance

- A process admitted by `up` remains ready in `status` without restating
  non-secret launch overrides.
- Status still reports a real artifact, launch, or readiness mismatch.
- Development, packaged, downstream, and multi-cluster status use the same
  rule.
- Focused operator tests cover an override at `up` followed by a plain status
  invocation.
