---
type: issue
status: open
tags: [issue, config, cluster]
---

# A forked cluster keeps the ancestor's `:seon.config/cluster`, so `config/effective` returns `{}`

## Evidence

Session-curation research probe, 2026-08-04
([session-curation-replay-mechanics-opus-2026-08-04.md](../../prds/sci-execution-runtime/research/session-curation-replay-mechanics-opus-2026-08-04.md)).
On a freshly started scratch cluster (`bin/seon start curation-opus`):

```clojure
(seon.config/effective @conn "curation-opus")
;; => {}   (an empty map, not nil, not an error value)

(db/q '[:find ?c . :where [?config :seon.config/cluster ?c]] @conn)
;; => "opuseffect0804"
```

The config singleton on the new cluster's branch still names the cluster it
was forked from. The immediate consequence is a contract violation from
`seon.config/result-caps`, whose message is a ~5 KB wall listing every
config key as "missing required key" — it never says which cluster's config
facts were not found.

## Why this matters beyond probes

`seon.eval.drive` and `seon.bootstrap-drive` both resolve admission caps
through exactly this call — `(config/result-caps (config/effective db
cluster-name))` (`src/seon/eval/drive.clj:78-79`,
`src/seon/bootstrap_drive.clj:146-147`). A drive whose cluster inherited an
ancestor's config name therefore fails at the caps read rather than at
anything to do with the objective, and the failure names nothing useful.

Two candidate causes, both worth checking: boot's config reconcile treats
the inherited singleton as converged because it compares dials rather than
the cluster name, and/or the published source branch carries a live
cluster's config facts (the same fork also carried another cluster's agent
entities).

## Expected behavior

A cluster's own name is a fact of that cluster's branch: boot asserts
`:seon.config/cluster` for THIS cluster during config reconcile, so
`config/effective db <this cluster>` is complete on the first read after
start. `config/effective` returns a flat error value naming the cluster
when no config facts match, never an empty map, and `result-caps` refuses
with that cause rather than with a per-key wall.

## Acceptance

A reset-boundary live proof: start a cluster forked from a published source
that carries a different cluster's config, then assert
`(:seon.config/cluster (config/effective db <name>))` equals `<name>` and
`config/result-caps` succeeds. A separate regression asserts
`config/effective` on an unknown cluster name returns a flat error naming
it.
