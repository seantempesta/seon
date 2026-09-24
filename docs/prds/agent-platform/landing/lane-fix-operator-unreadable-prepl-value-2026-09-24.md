# Lane fix-operator-unreadable-prepl-value (2026-09-24)

The babashka operator read a prepl `:ret :val` with `clojure.edn/read-string`; the operator now reads it
with Clojure's reader under `*read-eval* false` and `*default-data-reader-fn* tagged-literal`
(`seon.operator/read-prepl-val`). Owner ruling (a): the operator reads what our own trusted prepl prints;
the JVM keeps printing native values, so refusals keep their proposed transaction data intact.

## Broken / not yet proven

- `seon.adoption-diagnostic-test/operator-reports-the-typed-refusal-over-a-real-prepl` is red at
  `(< byte-count 1024)`: 2596 bytes. Pre-existing, not this lane: a plain io-prepl's exception `:val`
  is Throwable->map with its trace (1348 chars, which EDN read identically before), carried whole under
  `:seon.error/offending :val`; this lane's message change adds about 26 bytes. Test owner decides whether
  that bound or the retained trace is wrong (not owned here).
- Publication gap filed: docs/seon/issues/script-namespaces-loaded-by-the-jvm-are-not-published.md.
  Default was reloaded with `(require 'seon.operator :reload)` (root 56 ms, lane 54 ms), as authorized.

## Follow-up (root report)

- Thrown refusals: the `:exception` branch now takes its message from a Seon error map, else the
  Throwable->map's `:data :seon.error/message`, else its `:cause` (was always "PREPL evaluation failed.").
  The test's `(:val (ex-data thrown))` assertion was wrong: evidence lives under `:seon.error/offending`.
- `bin/test-check default --ns seon.operator-test`: run cf91ab04787c, pass 8 / fail 0, 1160 ms.

## Evidence

- bb and JVM reader probe (same results): `#'seon.test.runner/record-tx` -> `(var ...)`; `#inst` ->
  `java.util.Date`; `#uuid` -> `java.util.UUID`; `#object[...]`, `#error{...}` -> `clojure.lang.TaggedLiteral`;
  `#=(...)` refuses "EvalReader not allowed when *read-eval* is false.". EDN with `{:default tagged-literal}`
  still fails on `#'`, so it is not an option.
- Live, bb operator against default's prepl, form `{:m "Write refused." :tx [(var clojure.core/identity)]}`:
  HEAD operator -> `"Malformed PREPL result: No dispatch macro for: '"` (0.03 s);
  changed operator -> `{:m "Write refused.", :tx [(var clojure.core/identity)]}` (0.03 s). No cost change.
- A full refusal-shaped map with Date, UUID, Var and Object read back with native classes in bb.

## Changes

- `script/seon/operator.clj` +10/-1 (one contracted private reader; one call site).
- `test/seon/operator_test.clj` +37 (new): real `io-prepl`; returned and thrown refusals carrying a Var,
  `#inst`, `#uuid` and `#object` arrive with message and data.
- clj-kondo: no new warnings (20 pre-existing).
