---
title: "DIAL-11: Health thresholds are dials"
type: reference
status: waits on RO-4, RO-11
created: 2026-10-08
---

# DIAL-11: Health thresholds are dials

Ledger row DIAL-11 (group E). Source: `dials/dials.md` D23. Model: Sonnet. Host-bound (a new schema family): throwaway
check.

## Sites (verified at `1fcdbf47a`)

- `seon.health/ranking` (`src/seon/health.clj:54-59`): `{:seon.health/half-life-hours 24 :seon.health/faulted-severity 8}`.
- `seon.health/report`: `(or deadline-ms 900)` twice (`:590`, `:595`); the docstring says "default 900: under the
  one-second bound" (`:558`). The web page passes 50 (DIAL-12).

## Files

- `resources/seon/schemas/seon.config.health.edn` (new family; copy an existing family file's shape)
- `src/seon/health.clj`
- `test/seon/health_test.clj`

## Schema

```clojure
#:seon.config.health{:half-life-hours [:int {:min 1 :seon.config/dial true :seon.config/default 24 :description "A finding's weight halves every this many hours."}]
                     :faulted-severity [:int {:min 1 :seon.config/dial true :seon.config/default 8 :description "A report with a match at or above this severity is faulted."}]
                     :deadline-ms [:int {:min 1 :seon.config/dial true :seon.config/default 900 :description "How long a health report waits for its problems unit before showing :pending (under the one-second bound)."}]}
```

## Functions

- Delete `ranking`. `report` reads the cluster's effective configuration once (it already reads one database value
  and the running instance: `(config/effective database cluster-name)`) and passes the three values to the units
  and the ranking function that read `ranking` today (`rg -n "ranking" src/seon/health.clj`). A request's
  `:seon.health/deadline-ms` still wins over the dial (the web page's 50 ms, until DIAL-12).

## Proof

New `seon.health-test/health-thresholds-come-from-config`: with `:seon.config.health/faulted-severity` set above
every finding's severity on a test cluster, the report is not faulted; at the default it is (use the fixture
finding the existing health tests use).

`--test seon.health-test --test seon.config-defaults-test`

## Cost

D: one effective-config read per report (already a database read per report).

## Commit

`bin/lane commit <lane> -m "DIAL-11: health thresholds are dials" --test seon.health-test --test seon.config-defaults-test resources/seon/schemas/seon.config.health.edn src/seon/health.clj test/seon/health_test.clj`

## Dependencies

RO-4, RO-11 (`health.clj`).
