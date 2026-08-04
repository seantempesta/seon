---
type: issue
status: open
severity: friction
tags: [issue, runtime, ordering]
---

# Select the latest closed run without comparing run ids

## Problem

The work derivation selects the latest closed run by close transaction, open
transaction, and finally the run id string. That last comparison violates the
ordering class rule even when it is reached only for a transaction tie.

## Evidence

`src/seon/cluster/work.clj`'s `latest-closed-run` sorts rows by
`[closed-tx opened-tx run-id]`. The query already returns the numeric run entity
id, which can supply the total deterministic tie-break without giving the
identity string ordering semantics. This was found by the class sweep
accompanying commit `7cfb2435f`; only message ordering was in that lane's
repair scope.

## Owner

The work derivation that selects refusal-continuation context.

## Acceptance

- Latest-run selection orders solely by transaction facts and numeric entity
  identity.
- A same-transaction fixture with more than nine runs proves no lexical id
  order can affect the selected run.
- No run id parsing or new stored projection is introduced.
