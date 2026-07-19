---
type: issue
status: open
severity: blocker
tags: [issue, agent, database, research]
---

# Retain complete model transport evidence in Inspect logs

## Problem

Source-admitted live capability runs now retain complete provider-attempt
evidence and reach the real scorer. Formal local-model comparisons additionally
need a validated model-server artifact identity. These are different claims:
ordinary remote-provider capability scoring must not pretend Seon owns or can
hash externally hosted model weights.

## Current evidence

`seon.web.serve/project-model-transport-evidence` reads ordered attempt
components from the run's final immutable database value. Each attempt retains
its turn ID, ordinal, provider, adapter, requested model, endpoint, adapter and
outer timeouts, stream mode, response model and request ID when present,
outcome, and `historical_config_valid`. The latter proves the stored request
configuration was validated against the historical database value that
governed the attempt; the external JSON projection does not invent another
database wrapper for each attempt.

`POST /agents/run` returns one ordinary `database` value plus turn, eval, and
transport evidence. `seon_inspect.solver` retains the database as
`pod_database_value`. Commit `33fcc17b` removed a stale validator contract that
demanded test-only `coordinate` and `coordinate_valid` fields never emitted by
the runtime. The focused validator accepts an ordered retry with identical
configuration and still rejects absent historical validation, malformed
identity, inconsistent outcomes, or configuration drift.

Commit `defe85a2` separates provider capability evidence from local model-server
artifact evidence. A run against the configured remote provider is admitted
from its source revision and complete transport identity. When
`seon_model_server_identity` is present, the stricter join still requires a
validated immutable local artifact, endpoint/model agreement, and matching
successful response identity. A malformed present identity fails closed;
absence never becomes a fabricated local-artifact claim.

The 2026-07-19 three-epoch retry exposed one more false bound: a valid 34-turn
run produced 21,999 characters of transport evidence, but the web owner reused
the 16,384-character database display cap and returned only
`{:status "oversized"}`. That lost the provider proof and correctly caused
Inspect to reject the sample. Transport evidence is already bounded by the run
turn limit, retry limit, a closed projection of attempt attributes, and the
per-field response-identity and endpoint caps. It now returns that complete
bounded projection instead of applying an unrelated aggregate display cap.

Native logs retained under `src-inspect-ai/logs/` prove the progression:

- `…GeCsGY69cPQimaKyPuXLGZ.eval` reached 61 successful provider calls before the
  stale per-attempt wrapper validator rejected them;
- `…c2GEy5hAqDMJH4babEvKLW.eval` passed transport validation and exposed the
  unrelated mandatory-local-model assumption; and
- `…4Ebn4FuB9Cug67rqGoEi3n.eval` and
  `…fxN7bWkJXsVehcJBqs9K3B.eval` are fully source-admitted remote-provider runs
  that reach the capability scorer.

The complete live audit is
[[../../prds/runtime-reliability/research/live-inspect-contract-audit-2026-07-19]].

## Remaining owner

The issue remains open only for a controlled local-model comparison that
starts an immutable model server, supplies its validated artifact identity,
completes a real task, finalizes the native `.eval`, and reopens it to prove the
source, server, transport attempts, and response identity survived unchanged.
Remote-provider capability runs do not wait for that optional comparison gate.

## Acceptance

- A source-admitted remote-provider run with complete transport evidence and no
  local model-server identity reaches its scorer.
- A maximum-turn run retains every bounded attempt row; a render display cap
  never truncates formal run evidence.
- Missing or false historical configuration validation, malformed attempt
  identity, inconsistent retry outcomes, and transport drift fail before
  scoring.
- A present local model-server identity is validated and joined to every
  attempt's endpoint and requested model, plus successful response identity.
- One controlled immutable local-model task succeeds, its native log is
  finalized and reopened, and the exact artifact and attempt identities agree.
- No production path or maintained test introduces a generic database
  `coordinate`, `point`, or `attachment` wrapper.
