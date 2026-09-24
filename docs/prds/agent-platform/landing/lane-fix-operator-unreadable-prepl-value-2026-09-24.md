# Lane fix-operator-unreadable-prepl-value (2026-09-24)

The babashka operator read a prepl `:ret :val` with `clojure.edn/read-string`; the operator now reads it
with Clojure's reader under `*read-eval* false` and `*default-data-reader-fn* tagged-literal`
(`seon.operator/read-prepl-val`). Owner ruling (a): the operator reads what our own trusted prepl prints;
the JVM keeps printing native values, so refusals keep their proposed transaction data intact.

## Broken / not yet proven

- **RESET NEEDED (or root `(require 'seon.operator :reload)` in default).** `script/seon/operator.clj` has no
  program rows (`:seon.fn/sym seon.operator/prepl-value!` queries nil) and is loaded from
  `file:/Users/sean/src/seon/script/seon/operator.clj`; hook publication does not reload it. Default's JVM
  therefore still holds the old reader, and the new JVM regression `seon.operator-test` is red there
  (`bin/test-check default --ns seon.operator-test`: 7 fail, value nil because the old reader refuses).
  It is expected green after default reloads the namespace. Publication gap: script/ namespaces loaded
  into the JVM are neither rows nor reloaded; recorded here for root.
- The hook itself runs the operator fresh in babashka, so the fix is live for hook publication now.

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
