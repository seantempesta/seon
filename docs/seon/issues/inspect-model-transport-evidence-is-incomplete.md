---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, research]
---

# Retain complete model transport evidence in Inspect logs

## Problem

Ordered turn-owned attempt facts now flow losslessly through `POST /agents/run`
and retained Inspect metadata. A completed log can prove which retained
database value, resolved request configuration, endpoint, timeout layers,
outcome, and present response identity governed every provider attempt. It
still cannot prove which immutable model revision or weights the separately
managed model server used, so formal cross-model comparison remains blocked.
Final intent remains compatibility metadata, not per-call provenance.

## Evidence

The database-derived resolver now includes endpoint, adapter timeout,
credential-source name, and extra-body digest, and the OpenAI-compatible
adapter consumes one caller-supplied immutable resolution without rereading
ambient configuration. Its response also retains bounded response model,
system fingerprint, and request id when those fields are present; absence
remains absence.

The turn retry thunk now owns the immutable database value and resolution
before starting the outer timeout race, and dispatch consumes that supplied
resolution without overwriting it. Each attempt becomes an ordered component
fact connected to its turn, including outer timeouts that return no adapter
response. The retained endpoint is reconstructed from parsed URL components,
so userinfo, query, and fragment bytes never enter evidence. Invalid or
oversized response identity becomes a bounded evidence error without echoing
the rejected bytes. Integration review also corrected the zero-temperature
edge: `0.0` is a present sampling value and remains explicit in attempt facts
rather than disappearing through a truthiness check.

`seon.web.serve` now queries these attempt components from the same immutable
final database value as the rest of `/agents/run`. It resolves every stored
attempt coordinate through `seon.db/at-coordinate`, rejects an unretained or
foreign commit, and compares stored request fields and their absence with
`seon.ai/resolved-config` at that historical value. The response is governed
by the evidence cap read once from the final frozen database, never from the
ambient connection after asynchronous validation. It also re-derives the
adapter from the attempt's canonical resolved configuration and stream mode
from the linked turn's own frozen rendered database coordinate. A merely
well-formed but fabricated adapter or stream boolean therefore fails closed.
The projection carries no provider body, prompt, credential, or raw
environment value.

The outer attempt timeout is intentionally process-owned rather than a model
configuration datom. The runtime records the exact integer applied before each
race, and Inspect rejects a change across comparable attempts in one run. That
is sufficient to establish the execution bound governing the retained call;
cross-run reproducibility additionally relies on the admitted operator's
non-secret process-environment identity. It is not reconstructed from a later
database value or mislabeled as model identity.

`seon_inspect.solver` copies the projection unchanged into native sample
metadata. Its common capability admission gate requires inline ordered
evidence for source-admitted runs, exact request-turn membership, complete
coordinates, required transport identity, a successful final attempt per
turn, and one comparable configuration across the run. Missing, malformed,
oversized, foreign, unretained, out-of-order, drifted, or inconsistent evidence
becomes a sample error before task scoring. Older diagnostic logs remain
explicitly incomplete and are never repaired or backfilled.

Inspect's public `read_eval_log` shows that native sample metadata retains a
complete `pod_database_coordinate` with database id, branch, commit id, and
`t`; every retained turn's rendered coordinate has the same complete shape.
`seon.db/at-coordinate` can validate the attachment, retained commit, and exact
`t`, after which `seon.ai/current` reconstructs the historical global AI row,
including `:seon.ai/base-url` and `:seon.ai/timeout-ms`. This makes a manual
forensic reconstruction possible while the referenced Datahike commit remains
retained, but no public end-to-end helper or run projection performs it.

`seon.ai/resolved-config` is the one database-derived effective model resolver
consumed per request. It now includes endpoint and adapter timeout alongside
the other non-secret transport values, plus the optional response-identity and
endpoint evidence caps from the same immutable `:seon.config` singleton.
Those policy values exist only in the selected manifest and resulting datoms;
config-free historical databases preserve absence. Missing caps omit optional
identity/endpoint evidence without changing the request or model outcome, and
oversized identities preserve successful text and usage while retaining only
a generic cap-bounded marker. `seon.web.serve` validates the existing attempt
components against this resolver; Inspect preserves that projection rather
than opening a second database client or inventing another configuration
authority.

The complete grounded contract is in
[[../../prds/agentic-tool-refinement/research/model-transport-evidence-audit-2026-07-15]].
It also distinguishes the outer attempt cap from the adapter timeout and run
deadline; all three remain independently evidenced.

## Owner

Join the ordered request evidence to the separately managed model server's
declared immutable revision or weights digest and quantization, then finalize
and reopen one native admitted `.eval` as the end-to-end proof. The dedicated
launcher and health/request boundary own server identity. Per-call evidence is
never inferred from final/current state or from `/v1/models` alone.

## Acceptance

- Transact AI row A with a distinct endpoint and timeout, retain its complete
  coordinate, then transact row B with different values.
- Resolve A through `seon.db/at-coordinate` and prove the effective AI
  projection returns A's endpoint, timeout, credential-source name, and
  extra-body digest, never B's.
- A fake fetch that transacts B after attempt capture still receives and records
  only A, including A's evidence caps; no field is reread from B while
  assembling or validating the request and response.
- A final response projection frozen at cap A remains governed by A after the
  ambient config advances to cap B. Adapter and stream mutations fail against
  the attempt coordinate and linked turn-rendered coordinate respectively.
- `/agents/run` reports ordered per-attempt coordinates, endpoints, adapter and
  outer timeouts, immutable model identity, outcomes, and present response
  identity; the Inspect solver retains them unchanged in `.eval` metadata.
- Formal capability admission rejects mid-run transport/configuration drift
  rather than treating the final coordinate as proof for every earlier
  provider call. Runtime retries may capture a newer immutable value as long as
  both attempts remain ordered facts.
- Missing coordinate fields, wrong attachment, an unretained commit, or absent
  required OpenAI-compatible endpoint/timeout fail loudly as evidence errors.
  An absent evidence cap preserves provider behavior but leaves the optional
  identity/endpoint projection absent, so formal admission fails closed.
- Until this closes, the first controlled sample may remain diagnostic only
  when a manual historical reconstruction is recorded; no formal capability or
  model-comparison claim relies on that manual exception.
