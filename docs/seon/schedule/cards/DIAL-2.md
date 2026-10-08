---
title: "DIAL-2: Index creation settings are dials"
type: reference
status: waits on dials-16gb
created: 2026-10-08
---

# DIAL-2: Index creation settings are dials

Ledger row DIAL-2 (group O). Source: `dials/dials.md` D3. Owner: "no hardcoded limits". Model: Sonnet. Host-bound
(store creation, a schema resource): throwaway check.

## Site (verified at `1fcdbf47a`)

`seon.cluster.store/datahike-configuration` (`src/seon/cluster/store.clj:~190-229`) hardcodes
`:index-config {:branching-factor 128 :diff-buf-size 32}` (`:227-228`; the comment above them is the measurement
that chose them: 184 KB per real commit against 974 KB to 4.3 MB at 4,096). The node-cache dial beside it already
shows the pattern: `declared-store-cache-size` (`:156-162`) reads the dial's declared default from
`seon.config.db.edn`, and `datahike-configuration`'s 3-arity takes the configured value (`:184`).

These are **creation-only**: Datahike stores the index config with the store; `open-configuration` (`:~232`)
adopts the store's creation-time settings, so a changed dial affects only new stores.

## Files

- `resources/seon/schemas/seon.config.db.edn`
- `src/seon/cluster/store.clj`
- `test/seon/cluster/store_test.clj`

## Schema

```clojure
:branching-factor [:int {:min 16 :seon.config/dial true :seon.config/default 128
                         :description "Index node branching factor for a NEW store (creation-only; a store keeps the value it was created with). 128 measured 184 KB per real commit against up to 4.3 MB at 4,096."}]
:diff-buffer-size [:int {:min 1 :seon.config/dial true :seon.config/default 32
                         :description "Index diff buffer per node for a NEW store (creation-only)."}]
```

## Functions

- CHANGE `seon.cluster.store/datahike-configuration`: the index settings come from one map argument instead of
  literals. Its arities grow by one map `index-settings` (`{:seon.config.db/branching-factor n :seon.config.db/diff-buffer-size m}`),
  contract member `[:map [:seon.config.db/branching-factor [:int {:min 16}]] [:seon.config.db/diff-buffer-size [:int {:min 1}]]]`;
  the shorter arities read the declared defaults the way `declared-store-cache-size` does (generalize that private
  function to `declared-db-dial [dial]`, contract `[:=> [:cat :qualified-keyword] :int]`, and use it for all three).
- Boot's store creation passes the applied configuration's two values (follow `rg -n "datahike-configuration" src script`
  to the caller that holds the applied config, as it passes `store-cache-size` today).

## Proof

New `seon.cluster.store-test/a-new-store-takes-its-index-settings-from-config`: `(store/datahike-configuration "tmp/x/store" true 1024 {:seon.config.db/branching-factor 64 :seon.config.db/diff-buffer-size 8})`
has `[:index-config :branching-factor]` 64 and `:diff-buf-size` 8; the 1-arity has 128 and 32.

`--test seon.cluster.store-test --test seon.config-defaults-test`

## Cost

D: none (read once at store creation).

## Commit

`bin/lane commit <lane> -m "DIAL-2: index creation settings are dials" --test seon.cluster.store-test --test seon.config-defaults-test resources/seon/schemas/seon.config.db.edn src/seon/cluster/store.clj test/seon/cluster/store_test.clj`

## Dependencies

DIAL-0 (dials-16gb holds `store.clj`, `seon.config.db.edn`, `store_test.clj`).
