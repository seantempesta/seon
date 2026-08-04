---
type: issue
status: open
severity: friction
tags: [issue, database, schema, diagnostics]
---

# Make database request errors name the public operation

## Problem

Malformed `seon.db` argument maps and query clauses pass through the public
contract and fail inside dependency or codec helpers. The resulting faces name
internal functions, omit the missing public key, or dump parser structures.
An agent cannot tell how to repair the call from the owner it invoked.

## Evidence

Scratch cluster `codex-repl-dogfood-0804`, MCP `eval_clj`, `door` mode:

```clojure
(seon.db/q {:args []})
;; => Cannot parse :find ... :fragment nil

(seon.db/pull {:eid [:seon.test.run/id "dogfood-run-001"]})
;; => Cannot parse pull pattern ... :operation :datahike.pull/result

(seon.db/transact! {:not-tx-data []})
;; => seon.schema.datahike/encode-transaction violated its contract ...
```

The transaction face serializes the bad value twice inside
`:seon.instrument/problems` and `:seon.instrument/args` print-node strings. It
never states Datahike's actual public rule: a transaction map requires
`:tx-data`.

A malformed six-position data pattern:

```clojure
(seon.db/q
 '[:find ?e
   :where [?e :seon.test.run/id "dogfood-run-001" ?tx true :extra]])
```

returns only `Pattern mismatch` plus an internal pattern containing
`resolve-pattern-lookup-entity-id` and anonymous `#` cuts.

The public owners and contracts are at `src/seon/db.clj:540-592`, `:609-643`,
and `:1086-1113`. Datahike's accepted transaction shapes are explicit at
`reference-code/datahike/src/datahike/api/impl.cljc:30-48`.

## Owner

The `seon.db` public boundary owns classifying and humanizing invalid request
shapes before dependency helpers execute. Instrumentation owns presenting a
bounded public-operation error without serialized print-node internals.

## Acceptance

- Missing `:query`, `:selector`, and `:tx-data` keys return concise flat errors
  naming `seon.db/q`, `seon.db/pull`, and `seon.db/transact!` respectively.
- A malformed Datalog clause identifies the supplied clause and the accepted
  shape without dependency implementation symbols.
- Error data remains structured and bounded; it does not duplicate the bad
  value inside serialized print-node strings.
