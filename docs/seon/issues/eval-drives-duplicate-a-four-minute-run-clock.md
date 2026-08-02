---
type: issue
status: open
severity: friction
tags: [issue, eval, testing, architecture]
---

# Give eval episode backstops one declared owner

## Problem

Two evaluation drivers independently invent the same four-minute-per-run
fallback with the banned `(or supplied magic-number)` shape. Both wait for a
database-observable terminal fact, so the clock must be one loud backstop, not
duplicated lifecycle semantics.

## Evidence

- `src/seon/eval/drive.clj:54-73` already observes terminal facts with a
  Datahike listener.
- `src/seon/eval/drive.clj:320-328` computes
  `(or remote-timeout-ms (* run-cap 240000))`.
- `src/seon/bootstrap_drive.clj:375-393` duplicates the exact formula.
- Neither site names a config/schema owner or records that firing is a core
  fault rather than the expected completion mechanism.

## Owner

The one eval-episode driver request and its terminal-fact waiter.

## Acceptance

One named, schema-contracted backstop is supplied to both entry points. Normal
completion remains event-driven; a fired backstop produces explicit diagnostic
evidence and cannot be confused with an ordinary sample outcome.
