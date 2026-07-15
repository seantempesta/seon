---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, research]
---

# Retain complete model transport evidence in Inspect logs

## Problem

`POST /agents/run` and the retained Inspect `.eval` project the effective model,
sampling, and thinking values but omit the OpenAI-compatible base URL and
adapter timeout. A completed log can therefore name immutable model bytes while
failing to prove which endpoint served them or which request timeout governed
the call. That is insufficient provenance for a formal capability result or a
comparison between model arms.

## Evidence

Inspect's public `read_eval_log` shows that native sample metadata retains a
complete `pod_database_coordinate` with database id, branch, commit id, and
`t`; every retained turn's rendered coordinate has the same complete shape.
`seon.db/at-coordinate` can validate the attachment, retained commit, and exact
`t`, after which `seon.ai/current` reconstructs the historical global AI row,
including `:seon.ai/base-url` and `:seon.ai/timeout-ms`. This makes a manual
forensic reconstruction possible while the referenced Datahike commit remains
retained, but no public end-to-end helper or run projection performs it.

`seon.ai/resolved-config` is already the one database-derived effective model
resolver consumed per request. Its returned transport projection intentionally
does not include endpoint or timeout, and `seon.web.serve` consequently cannot
place those values in `/agents/run` evidence. Inspect should preserve the pod's
projection rather than opening a second database client or inventing another
configuration authority.

## Owner

Strengthen the existing `seon.ai/resolved-config` projection and have the one
`/agents/run` response owner retain its transport fields from the same immutable
database value as the reported coordinate. `seon_inspect.solver` remains a
lossless evidence consumer. Per-call effective evidence must not be inferred
from current environment state after the run.

## Acceptance

- Transact AI row A with a distinct endpoint and timeout, retain its complete
  coordinate, then transact row B with different values.
- Resolve A through `seon.db/at-coordinate` and prove the effective AI
  projection returns A's endpoint and timeout, never B's.
- `/agents/run` reports endpoint, adapter timeout, immutable model identity,
  and database coordinate from the same effective request configuration, and
  the Inspect solver retains them unchanged in `.eval` metadata.
- A static admitted run rejects mid-run transport/configuration drift rather
  than treating the final coordinate as proof for every earlier provider call.
- Missing coordinate fields, wrong attachment, an unretained commit, or absent
  required OpenAI-compatible endpoint/timeout fail loudly as evidence errors.
- Until this closes, the first controlled sample may remain diagnostic only
  when a manual historical reconstruction is recorded; no formal capability or
  model-comparison claim relies on that manual exception.
