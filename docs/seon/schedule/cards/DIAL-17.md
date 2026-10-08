---
title: "DIAL-17: the free-disk floor fits a laptop"
type: reference
status: ready after dials-16gb
created: 2026-10-08
---

# DIAL-17: The free-disk floor fits a laptop

Ledger row DIAL-17 (group O). AU-Q24 answered (owner 2026-10-08): 10 GiB, with the ratio as the second floor.
Source: `dials/dials.md` row D31. Model: Sonnet.

`:seon.config.maintenance/min-usable-bytes` ships 53,687,091,200 (50 GiB) in `config/default.edn` (the maintenance
block); `seon.maintenance/observe-footprint!` (`src/seon/maintenance.clj:683-696`) flags low space when usable bytes
fall below it or usable ratio below `:seon.config.maintenance/min-usable-ratio`.

## Files

- `config/default.edn`
- `test/seon/maintenance_test.clj`

## Change

The decision line becomes `:seon.config.maintenance/min-usable-bytes 10737418240` (10 GiB). The ratio line is
unchanged. Check `rg -n "min-usable-bytes" test src config docs/seon` for an assertion of the old value and update it.

## Proof

New `seon.maintenance-test/the-shipped-disk-floor-is-ten-gibibytes`: `(get-in (config/compile-manifest {:seon.boot/cluster-name "floor-probe"}) [:seon.config/effective :seon.config.maintenance/min-usable-bytes])`
is 10737418240 (the same compile the file's `declare-cluster-config!` uses, `maintenance_test.clj:411-420`).

`--test seon.maintenance-test seon.config-defaults-test`

## Commit

`bin/lane commit <lane> -m "DIAL-17: the shipped free-disk floor is 10 GiB" --test seon.maintenance-test seon.config-defaults-test config/default.edn test/seon/maintenance_test.clj`

## Dependencies

`config/default.edn` is held by dials-16gb (and SP-3 lane impl-sp3 also edits it); DIAL-1 rewrites the file as Aero:
land before DIAL-1 or inside it.
