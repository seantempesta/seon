---
type: issue
status: resolved
severity: friction
tags: [issue, sci, schema, class/n9, wave/per-cluster-live-graph]
---

# Every contracted `defn` rebuilds the whole schema projection (~21-30 ms)

Measured 2026-08-01 (`tmp/sci-session/probe3_defn_cost.clj`,
`probe2_replay.clj`, `probe6_scale.clj`, fresh `clojure -M:dev` JVM, own
store root — no live cluster touched):

| Form through `seon.sci.eval/evaluate` | Median |
|---|---|
| `(def q1 42)` | 0.13 ms |
| `(defn p9 [x] (inc x))` (no contract) | 0.32 ms |
| `(defn h1 {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))` | **21.6-30.5 ms** |
| `seon.sci.reader/read` of that same source | 0.31 ms |
| `seon.program/declaration-row` | 0.004 ms |
| `seon.schema/projection-with-function-contract` | **44.6 ms** |

The whole cost is `projection-with-function-contract`
(`src/seon/schema.cljc:1965-1991`), which calls `build-projection` over
**every registered schema form** to admit one function contract. It is
O(registry) per agent `defn`, paid inside the armed boundary on the
`:compute` workload.

Consequences:

- the one persistence rule we teach agents (`defn` + `:malli/schema`
  persists) is ~90x more expensive than any other form an agent writes;
- 20 contracted defns cost 610 ms while 200 ordinary forms cost 68 ms;
- it dominates any replay-on-wake design
  (`docs/prds/sci-execution-runtime/research/sci-session-persistence-2026-08-01.md`)
  and it will dominate the grader's surface-preparation runs, which are
  contracted defns almost exclusively.

Expected owner: `seon.schema`. The projection is a derived value built
from forms + function contracts; adding one contract should be an
incremental validation of that contract against the existing projection,
not a whole-registry rebuild.

Acceptance: a contracted `defn` through the SCI evaluator costs the same order as
an uncontracted one (target < 2 ms at the current registry size), the
admission semantics are unchanged (a bad contract is still refused with
the same error), and a recurring test pins the cost class (e.g. the
per-form cost of a contracted defn stays within a small multiple of an
uncontracted one at a registry size the test constructs).

## Backlog triage 2026-08-02

**Still real after parsed contracts and live enforcement.** Those landings
changed the durable representation and installed wrapper, not this admission
cost: current `seon.schema/projection-with-function-contract` still calls
`build-projection` over all forms and contracts, and `seon.sci.eval/program-row`
still invokes it for every contracted definition. The destination is the
contract-projection performance follow-up, not the completed per-cluster live-
context wave.

## Live SCI evaluation exercise 2026-08-04

An isolated `edgefaces0804` cluster reproduced the same cost class through MCP
`eval_clj` in `door` mode, but the evaluation diagnostic made the allocation
cost visible as well:

```clojure
(defn ^{:malli/schema [:=> [:cat :string] :string]}
  unicode-doc-edge
  "Snowman ☃, emoji 🧪, combining é.\nSecond doc line."
  [x] x)
```

The definition took 135 ms and recorded 578,302,120 allocated bytes. A second
contracted definition took 169 ms and recorded 578,696,192 allocated bytes.
The face itself was correctly `#'user/unicode-doc-edge`; the defect is the
whole-registry work paid to produce it. Acceptance should therefore retain an
allocation-class assertion as well as the latency comparison, so a fast heap-
intensive rebuild cannot false-green the repair.

## Closure verification — 2026-08-13

**CONFIRMED-STALE at `06e654c76`; resolved by `fba6bc4c1`.** The structural
cause measured here is gone:

- `src/seon/sci/eval.clj:320-335` still validates each contracted definition
  through `projection-with-function-contract`, preserving the admission seam.
- `src/seon/schema.clj:2579-2653` now compiles only the supplied definition,
  replaces that function's dependency/admission/fingerprint entries, and
  validates the affected render contracts in place. It does not call
  `build-projection` or walk every registered contract.

This source verification closes the whole-projection-rebuild defect. It does
not claim a fresh live latency/allocation measurement; any remaining current
performance regression requires a new measurement against this incremental
implementation.
