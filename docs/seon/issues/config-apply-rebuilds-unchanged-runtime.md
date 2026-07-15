---
type: issue
status: open
severity: friction
tags: [issue, config, runtime, acme]
---

# Config apply rebuilds an unchanged runtime

## Problem

`bin/seon config apply <manifest>` promises explicit database config
reconciliation, but routed through the complete development artifact build and
process reconcile path. Applying the same converged manifest could therefore
rebuild the writer and client and restart the writer and pod even though only a
database desired-state operation was requested.

## Evidence

On 2026-07-14 the isolated `acme-agentic-tool-refinement` cluster was ready with
application digest `7808d66b…`. Two consecutive applications of the unchanged
`config/acme.edn` each ran dependency preparation, writer uberjar, client,
bootstrap, and CSS builds. The second ready target had application digest
`2fe1f0f9…`, new writer and pod PIDs, and the same resolved run/transcript
policy. `script/seon/dev/cli.clj/config!` directly called
`reconcile-development!`; the config operation had no narrower live boundary.

Commit `2f348806` removed that widening. Two unchanged live applies wrote zero
operations; an intentional policy delta and restoration each wrote two; and
watcher `81044`, writer `81308`, and pod `81335` remained unchanged. The live
boundary currently transports the selected path and resolves it once in the
pod; it does not yet persist an immutable resolved payload in supervisor intent.

## Owner

The development operator's explicit config operation and the pod's
database-config reconciliation service. Artifact reproducibility is a related
input: unchanged source must not mint a different launch identity.

## Acceptance

- `config apply` validates and freezes the selected manifest as one operation
  payload, reconciles its managed database subset, and returns the transaction
  outcome without rebuilding unrelated artifacts.
- A converged second apply writes no datoms and does not replace watcher,
  writer, or pod processes.
- If the target is down or lacks a compatible config operation boundary, the
  operator reports that state explicitly instead of silently widening to `up`.
- Artifact digests are reproducible for byte-identical maintained inputs, so
  ordinary `up` also avoids false process replacement.
