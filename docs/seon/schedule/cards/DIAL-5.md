---
title: "DIAL-5: subprocess cleanup bounds are dials"
type: reference
status: ready
created: 2026-10-08
---

# DIAL-5: Subprocess cleanup bounds are dials

Ledger row DIAL-5 (group O). Source: `dials/dials.md` row D11. Model: Sonnet.

`seon.cluster.process` holds two literals: `subprocess-cleanup-ms` 10000 (`src/seon/cluster/process.clj:111`) and
`subprocess-grace-ms` 1000 (`:113`), used by `signal-pids!` (`:166`) and `run-process!` (`:230`, uses at `:300-302`).
The shell owner already has the dial `:seon.config.shell/termination-grace-ms` (`seon.config.shell.edn:25`, read at
`shell/jvm.clj:380`). `run-process!` runs operator work that may precede any cluster configuration, so its default
comes from the declaration, the pattern `seon.cache/declared-max-bytes` uses (`src/seon/cache.clj:76-83`).

## Files

- `resources/seon/schemas/seon.config.shell.edn`
- `src/seon/cluster/process.clj`
- `test/seon/cluster/process_test.clj` (create if absent)

## Schema

Add to `seon.config.shell.edn`:
`:cleanup-ms [:int {:min 1 :seon.config/dial true :seon.config/default 10000 :description "How long subprocess cleanup waits for a terminated tree to exit before it reports survivors."}]`.
Give `:termination-grace-ms` `:seon.config/default 1000` if it has no declared default (check its line; keep its
current `config/default.edn` decision if present).

## Functions

- ADD `seon.cluster.process/declared-bound` (private) `[dial]` → `[:int {:min 1}]`, contract
  `[:=> [:cat [:enum :seon.config.shell/cleanup-ms :seon.config.shell/termination-grace-ms]] [:int {:min 1}]]`:
  `(-> (io/resource "seon/schemas/seon.config.shell.edn") slurp edn/read-string (get dial) second :seon.config/default)`,
  held in `seon.cache` (kind `::declared-bound`, key the dial, weight 64).
- Delete the two `def`s. `run-process!` reads `(or (:seon.config.shell/cleanup-ms request) (declared-bound :seon.config.shell/cleanup-ms))`
  and the same for the grace bound, from the request map it already takes (add both as optional keys to its request
  contract); `signal-pids!` takes the grace bound as a new first argument from its caller.
- Callers that hold an effective configuration pass the two values in the request (`rg -n "run-process!" src script`;
  convert those that have `effective` in scope; the rest use the declared defaults).

## Proof

New `seon.cluster.process-test/cleanup-bounds-come-from-config`: `(#'process/declared-bound :seon.config.shell/cleanup-ms)`
is 10000, and a `run-process!` of `sleep 5` with a 100 ms deadline and `:seon.config.shell/termination-grace-ms 50`
returns its timeout data within one second (data: the result's phase is `:process-exit`).

`--test seon.cluster.process-test --test seon.shell.jvm-test --test seon.config-defaults-test`

## Cost

D: one cached lookup.

## Commit

`bin/lane commit <lane> -m "DIAL-5: subprocess cleanup and grace bounds are dials" --test seon.cluster.process-test --test seon.shell.jvm-test --test seon.config-defaults-test resources/seon/schemas/seon.config.shell.edn src/seon/cluster/process.clj test/seon/cluster/process_test.clj`

## Dependencies

None. DIAL-15 and SR-T edit `seon.config.shell.edn` after this.
