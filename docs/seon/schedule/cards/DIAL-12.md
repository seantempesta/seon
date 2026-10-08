---
title: "DIAL-12: Web deadlines and ports are dials"
type: reference
status: waits on UL-7, PR-19
created: 2026-10-08
---

# DIAL-12: Web deadlines and ports are dials

Ledger row DIAL-12 (group D). Source: `dials/dials.md` D24, D25. Model: Sonnet. Host-bound (the web server):
throwaway check.

## Sites (verified at `1fcdbf47a`)

- The page's health deadline: `:seon.health/deadline-ms 50` in the health-surface builder
  (`src/seon/render/web.clj:~2029-2031`).
- `seon.render.web/port-floor` 7700 (`:3632-3641`) and `port-ceiling` 8000 (`:3643-3647`), read by
  `derived-port` (`:3649-3675`).
- `debug-defaults` (`:217-221`) are display counts: they go to the value printer's budget (PR rows), **not** dials;
  not this card.

## Files

- `resources/seon/schemas/seon.config.web.edn` (dirty at `1fcdbf47a`: claim after its holder)
- `src/seon/render/web.clj`
- `test/seon/render/web_test.clj`

## Schema

```clojure
:health-deadline-ms [:int {:min 1 :seon.config/dial true :seon.config/default 50 :description "How long the page's health surface waits before showing :pending."}]
:port-floor [:int {:min 1024 :max 65535 :seon.config/dial true :seon.config/default 7700 :description "Bottom of the derived web port range (7700-7999 carries no IANA assignment)."}]
:port-ceiling [:int {:min 1025 :max 65536 :seon.config/dial true :seon.config/default 8000 :description "Exclusive top of the derived web port range."}]
```

## Functions

- Delete the two `def`s. `derived-port` `[cluster-name floor ceiling]` (contract gains two `[:int {:min 1}]`);
  its caller (the server start, `:~3693`) passes the effective configuration's values the service already carries
  (read how `service` is built and add the two keys there).
- The health surface reads `(:seon.config.web/health-deadline-ms effective)` from the service.

## Proof

New `seon.render.web-test/web-bounds-come-from-config`: `(web/derived-port "c" 9000 9010)` is in `[9000, 9010)`;
a service built with `:seon.config.web/health-deadline-ms 1` shows `:pending` for a slow problems unit (reuse
the existing health-surface test's fixture).

`--test seon.render.web-test --test seon.config-defaults-test`

## Cost

D: none.

## Commit

`bin/lane commit <lane> -m "DIAL-12: web deadlines and the port range are dials" --test seon.render.web-test --test seon.config-defaults-test resources/seon/schemas/seon.config.web.edn src/seon/render/web.clj test/seon/render/web_test.clj`

## Dependencies

UL-7, PR-19 (`web.clj`); DIAL-11 (the health deadline's meaning).
