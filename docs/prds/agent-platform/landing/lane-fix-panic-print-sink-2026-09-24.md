---
type: landing
status: landed on default (gate adopted)
lane: fix-panic-print-sink
created: 2026-09-24
issue: docs/seon/issues/unstored-panic-prints-whole-throwable-map-to-stderr.md
---

# Lane fix-panic-print-sink (2026-09-24)

Issue: `docs/seon/issues/unstored-panic-prints-whole-throwable-map-to-stderr.md`
(status set to fixed). The two unstored-fault sinks now print causes through
`seon.render.value/floor`, as `seon.cluster.boot/uncaught-emergency!` does.

## Broken first

- `bin/test-check default --ns seon.flow-test --ns seon.cluster.store-test`
  (run 84e99ee328f9): executed 18 / reused 23 / pass 201 / fail 9 / error 18,
  76,350 ms. None reaches the changed sinks:
  - most flow-test errors: `Cannot interpret seon.render.web-test/with-server:
    Symbol cannot be cast to Compiler$LocalBinding` (SCI interpretation of a
    test macro; foreign, near b855a8a17 / uncommitted `test/seon/render/web_test.clj`).
  - `core-fault-signatures-bound-durable-and-stderr-output`: the
    `with-database` branch inherits default's open fault signatures
    (8 facts, not 1); it drives `committer-step`, not `proc-failed!`.
  - `store-test/an-error-inside-one-store-write-fails-the-commit-and-keeps-the-prior-head`:
    reopen refuses "already open in this process" after an injected writer
    Error; the commit fails, so no listener (and no `listener-failed!`) runs.
    Baseline not measured on the parent (one-JVM rule); attribution by reading.
- Tool gap: after the gate adopted the files, `--test` answered "The admitted
  identity has no executable test Var" for every `seon.flow-test` member until
  `seon.flow-test` was loaded in the JVM (I ran `(require 'seon.flow-test :reload)`
  once through MCP; a test namespace, no source Var redefined). The adoption
  does not load test namespaces the request names.
- The ordinary panic line in both sinks still `prn`s `(ex-message panic)` and
  the receipt unbounded; a Malli refusal message describes rather than prints
  the value (observed 263 chars), so left as is.

## Fix

- `src/seon/flow.clj` `proc-failed!`: the `prn` map keeps message, operation
  and receipt; when the receipt is not committed, `(println (render.value/floor panic))`
  and `(println (render.value/floor failure))` replace the `Throwable->map` data.
  Docstring corrected.
- `src/seon/cluster/store.clj` `listener-failed!`: same; suppressed throwables
  are named by `floor` per cause (`value.clj` `link-lines`), so the separate
  `mapv Throwable->map` goes.
- Neither function throws before or after (both catch the recorder's panic and
  return nil: the Flow loop and listener notification continue). The failure
  and panic values are unchanged and stay whole in memory.
- No load cycle: `seon.render.value`'s transitive requires (45 namespaces,
  tools.namespace over `src/`) contain neither `seon.flow` nor `seon.cluster.store`.

## Evidence (default, pid 99265)

- Before: `pr-str` of `Throwable->map` of an ex-info whose ex-data holds a
  51,000,000-char string: 102,001,596 chars, 2,324 ms. Panic plus failure with
  the failure nested in the panic's ex-data (the incident shape):
  306,005,120 chars, 7,321 ms.
- After: `proc-failed!` on that failure with an unconfigured environment writes
  1,960 bytes (22 lines) to `*err*` in 3.6 ms; the two floors of the nested
  shape total 1,028 chars in 0.8 ms. Bound: each `floor` is at most 8,192 chars
  plus an ellipsis.
- Regression `seon.flow-test/an-unstored-panic-prints-bounded-text-and-keeps-the-whole-cause`:
  both sinks, a 51 MB ex-data value; asserts stderr under 3 x 8,193 chars,
  the cause message present, nil return, and the 51 MB value identical on
  the failure. `bin/test-check default --test ...`: run fb475ed7bd9f, 1 executed,
  7 pass, 0 fail, 1,864 ms. Under the old code it writes more than 102 M chars.
- Gate `bin/test-check default --gate --changed-path` (flow.clj, store.clj,
  flow_test.clj): adopted (adopt 47,054 ms, tests 9,032 ms, publish 2,552 ms;
  59,103 ms total, over ten seconds, dominated by adoption under concurrent
  lanes' publications: `seon.cluster/adopt-then-test!` 123,003 ms inclusive
  across two calls). Not this lane's cost; reported, not fixed.

## Other print sites of a Throwable in `src/` (`rg`)

No other `prn`/`pr`/`println` of a Throwable or `Throwable->map` remains.
Checked: `turn.clj:5419,5442` (messages only), `boot.clj:325-328,369`
(`floor`; `boot.clj:325` `prn`s the evidence map, caller-supplied),
`db.clj:1261`, `schema.clj:1219`, `test/runner.clj:257-278` (bounded by
10d629c54), `cluster.clj:564` (prepl event `prn` through `mcp-valf`).
`Throwable->map` elsewhere feeds data, not a print: `error/refusal.clj:88`,
`sci/admit.clj:297`, `sci/eval.clj:3564`, `test/runner.clj:1726`.

## Lines, commit, limits

- Net: flow.clj +10/-8, store.clj +5/-4, flow_test.clj +33, this note.
- Commit: see `git log -- src/seon/flow.clj` (this lane's slice).
- Limits: the failing namespace members above were not baselined on the parent.
  No live unstored-fault drill on default (would need breaking its recorder).
