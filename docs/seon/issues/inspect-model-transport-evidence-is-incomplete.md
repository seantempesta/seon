---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, research]
---

# Retain complete model transport evidence in Inspect logs

## Problem

`POST /agents/run` and the retained Inspect `.eval` project model intent only
after a run finishes. They omit the OpenAI-compatible base URL and adapter
timeout, and the adapter may reread reactive configuration while assembling one
request. A completed log therefore cannot prove which immutable database value,
endpoint, model bytes, timeout layers, or response identity governed any
provider attempt. Final intent is insufficient provenance for a formal
capability result or comparison between model arms.

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

The complete grounded contract is in
[[../../prds/agentic-tool-refinement/research/model-transport-evidence-audit-2026-07-15]].
It also finds that retries retain only a count, known response `model` and
`system_fingerprint` fields are discarded, and the outer attempt cap is
distinct from the adapter timeout and run deadline.

## Owner

Strengthen the existing `seon.ai/resolved-config` projection, then capture one
immutable database value and resolved transport value at the existing retry
thunk for each attempt. Dispatch and adapters consume that value without
rereads. Ordered bounded attempt facts connect to the turn and flow through the
one `/agents/run` response; `seon_inspect.solver` remains a lossless evidence
consumer. Per-call evidence is never inferred from final or current state.

## Acceptance

- Transact AI row A with a distinct endpoint and timeout, retain its complete
  coordinate, then transact row B with different values.
- Resolve A through `seon.db/at-coordinate` and prove the effective AI
  projection returns A's endpoint, timeout, credential-source name, and
  extra-body digest, never B's.
- A fake fetch that transacts B after attempt capture still receives and records
  only A; no field is reread from B while assembling the request.
- `/agents/run` reports ordered per-attempt coordinates, endpoints, adapter and
  outer timeouts, immutable model identity, outcomes, and present response
  identity; the Inspect solver retains them unchanged in `.eval` metadata.
- A static admitted run rejects mid-run transport/configuration drift rather
  than treating the final coordinate as proof for every earlier provider call.
- Missing coordinate fields, wrong attachment, an unretained commit, or absent
  required OpenAI-compatible endpoint/timeout fail loudly as evidence errors.
- Until this closes, the first controlled sample may remain diagnostic only
  when a manual historical reconstruction is recorded; no formal capability or
  model-comparison claim relies on that manual exception.
