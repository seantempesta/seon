---
type: issue
status: open
severity: friction
tags: [issue, database, diagnostics]
---

# Keep expected Datahike errors from logging beside returned values

## Problem

An expected database mistake is returned to the caller as a useful flat error
value, but Datahike also logs the dependency failure before that value returns.
Writer refusals emit a complete exception and stack; read mistakes emit a raw
dependency line. This makes tests, operator output, and the agent's own REPL
noisy and exposes the same internals the agent-facing value should humanize.

## Evidence

On 2026-08-04, `bin/test seon.db-test` exercised the intentional
`:transact/unique` regression. The suite passed, but Datahike emitted both the
transaction error and the full `:datahike/write-error` exception trace through
`datahike.writer/create-thread` before `seon.db` received the refusal.

The data-session dogfood pass found the read-side sibling in scratch cluster
`codex-repl-dogfood-0804`, through MCP `eval_clj` in `door` mode:

```clojure
(seon.db/pull '[:seon.test.run/idd]
              [:seon.test.run/id "dogfood-run-001"])
```

Before returning the flat `:seon.db/invalid-read` value, the REPL emitted:

```text
2026-08-04T22:16:00.640939Z :error datahike.db.utils [189 11] Bad entity attribute :seon.test.run/idd at (resolve-datom db 14194 :seon.test.run/idd nil nil), not defined in current schema
```

The returned value then repeated the same `resolve-datom` implementation face.
This is an expected caller typo, not a core fault, and it should occupy one
agent-visible face rather than stdout plus a value.

## Owner

Datahike's database diagnostic boundaries and Seon's database invocation policy
own the distinction between expected caller mistakes and unexpected database
faults.

## Acceptance

An expected Datahike refusal or invalid read remains one flat, structured
`:seon.error` value without printing a second log line or writer stack, while
unexpected database faults stay loud and retain their diagnostic trace.
