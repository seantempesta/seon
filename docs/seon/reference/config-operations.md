---
type: reference
status: active
tags: [reference, database, config]
---

# Operational configuration

Seon configuration is a flat EDN map of registered `:seon.config.*` dials.
`config/default.edn` decides every shipped dial. A selected manifest is a sparse
overlay: omitted keys inherit the shipped decision, while
`:seon.config/absent` explicitly retracts an optional dial. Unknown keys,
invalid values, and attempts to retract required dials are refused by
`seon.config/compile-manifest` before reconciliation.

The compiled effective map is reconciled onto the cluster's
`:seon.config/cluster` entity. Runtime consumers read that database row through
`seon.config/effective`; they do not reread the manifest. The manifest digest
covers the canonical effective map, so applying an already-converged overlay
writes nothing.

## Operator commands

Start the default cluster with shipped defaults:

```sh
bin/seon start
```

Start a named cluster with a sparse overlay:

```sh
bin/seon start research --config config/research.edn
```

Apply an overlay to a live cluster:

```sh
bin/seon config apply research config/research.edn
```

Omit `research` to apply to `default`. `config apply` requires a live cluster;
it reconciles facts in that cluster and does not restart its JVM or rebuild its
Flow graphs.

Use `bin/seon status` to reconcile and list this operator root's clusters. Use
`bin/seon init` to publish `current-src`, `bin/seon init NAME` to fork a dormant
cluster, and `bin/seon reset --force` only to destroy and rebuild the entire
operator root. `reset` is not configuration repair.

For destructive or isolated work, select an existing private operator root:

```sh
bin/seon --root tmp/my-proof-root reset --force
```

The full command grammar is printed by `bin/seon --help` and is owned by
`script/seon/fresh_operator.clj`.

## Dial authority

`resources/seon/schema/config.edn` declares the config attributes and their
value schemas. Other schema resources may also register dials; the config
compiler derives the closed manifest and effective-map schemas from those
registrations. Do not maintain a second dial table in documentation.

The currently shipped decisions, units, and provenance live beside the values
in `config/default.edn`. That file is the exact example to read before writing
an overlay. Credentials are the exception: AI configuration stores only the
name of an environment variable, and `seon.ai/credential` reads its value at
the HTTP leaf. Credential values never become database facts or enter Git.

## Live versus start-time behavior

The current config surface is fact-backed. `config apply` changes the database
row immediately, and consumers decide when to reread it. The agent run loop
resolves AI settings once from one immutable database value at the start of
each `:call` turn, so a config apply affects the next model call without a JVM
restart. The web renderer also reads its configuration from the cluster
database. There is no writer-reconstruction, pod-reconnection, UDS, executor,
or environment-driven watchdog configuration path in fresh Seon.

## Sources checked

- `src/seon/config.cljc` — manifest compilation, absence, reconciliation, and
  database reads.
- `resources/seon/schema/config.edn` plus the other
  `resources/seon/schema/*.edn` registrations — admitted dials.
- `config/default.edn` — shipped decisions and provenance.
- `script/seon/fresh_operator.clj` — operator grammar and live apply path.
- `src/seon/cluster/loop.cljc` and `src/seon/ai.cljc` — per-turn AI resolution.
