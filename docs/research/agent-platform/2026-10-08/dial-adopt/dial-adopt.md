---
title: dial-adopt — a new required dial refuses every older config row
type: research
status: active
created: 2026-10-08
---

# dial-adopt: a new required dial refuses every older config row

Task: `seon.config/an-agent-branch-forked-before-a-new-required-dial-refuses-every-eval-request`.

## Reproduction (default JVM, disposable `d/with`, HEAD a1e1eb399)

`seon.config/effective` reads one cluster's config row. For a required dial the
row lacks it supplied only the schema's `:seon.config/default`
(`registration-defaults`). The dials landed today
(`:seon.config.operator/heap-max-bytes`, `:seon.config.operator/check-slots`)
declare no schema default: their shipped decision lives in `config/default.edn`.
So any row written before them (every agent branch forked earlier, and a
cluster row boot had not yet re-applied) is refused:

```clojure
(let [conn (seon.cluster.boot/connection "default")
      db0 (db/db conn)
      dials [:seon.config.operator/heap-max-bytes :seon.config.operator/check-slots]
      row (db/pull db0 dials [:seon.config/cluster "default"])
      older (:db-after (d/with db0 (vec (for [k dials] [:db/retract [:seon.config/cluster "default"] k (get row k)]))))]
  (config/effective older "default"))
;; 11 ms => {:seon.error/type :seon.config/error
;;           :seon.error/message "Effective configuration requires a matching cluster entity with every required dial."
;;           :seon.error/data #:seon.config{:missing [...check-slots ...heap-max-bytes] :available ["default"]}}
```

`config/defaults` (the compiled shipped decisions) holds both dials
(`4294967296`, `2`) at the same moment. Every eval request reads `effective`
(`seon.mcp/observed` for result caps, `seon.sci.eval`), so the refusal fails
each request of an agent whose branch row predates the dial. The missing keys
sat only under `:seon.error/data`.

## `bin/seon config apply default config/default.edn`

The operator read the file client-side and sent the whole shipped document as
`:seon.config/manifest`. The shipped document is not a manifest: it carries the
smart decision `:seon.config.flow.compute/concurrency
:seon.config/available-processors` (and `:seon.config/initialization`), which
the `:seon.operator/request` contract refuses at `seon.cluster.boot/request!`.
`seon.config/apply!`'s path arity, which recognizes the shipped document, was
never reached. The operator then reported only "PREPL evaluation failed.":
`io-prepl` answers an exception as the printed `ex-str` string, and
`prepl-value!` looked for a message only in a map, so the string cause was
reduced to the fixed fallback (it survived only inside `:seon.error/offending`).

## Fix

- `effective-in`: a required dial the row lacks takes the shipped decision
  (`config/defaults`, this JVM's compiled declarations and document) — the
  value `apply!` would write for an undecided dial — so a row written before a
  dial existed reads it at its first read, on every branch.
- The refusal, when a row is absent, names the missing dials in
  `:seon.error/offending`.
- `config apply` sends the canonical path; boot calls `config/apply!`'s path
  arity, which applies the shipped document as itself and any other document
  as a sparse overlay.
- `prepl-value!` keeps a string exception value as the failure's message.
