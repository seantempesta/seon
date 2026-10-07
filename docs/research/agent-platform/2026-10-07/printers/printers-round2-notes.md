# printers-round2 — lane notes (2026-10-07)

Root rulings: the value printer is the ONE printer. Its core (structural selection under a fixed budget, no render,
database or config) is `seon.render.value/core-text` / `core-html`; the former `seon.print.value` code is that core's
private last resort. `seon.render.value/text` / `html` are the total unit entries (a refusal or throw answers the
core's print plus one diagnostic). Low-level namespaces keep values as data.

## Commits

- 0c833730c refusal: second clipper deleted (bounded-cost, bounded-walk, held-members, held-by-name, bounded-data,
  within-evidence-bound?, within-evidence-bound-chain?, evidence-characters; `recorded-chain` folded into `chain`;
  schema `:seon.error/bounded-chain` deleted). Link ex-data stays whole (worlds and places referenced).
- 3fab5b880 refusal (batch-C review): `diagnostic`'s frame is `root-frame`'s; `referenced` hashes each rebuilt level
  as it closes (a set holding a 200,000-deep member: StackOverflowError before, 202 ms after); agent ex-data over
  16 KB stays whole through admission. Three regressions.
- 83b8ecfe8 test.runner: report-value shows a throwable as its chain data through render-ai; drift and
  record-interrupted! through report-value; reach-digests fixed message, values as data.
- 008c909a7 render/walk returns data; its refusals are error values.
- 108dd1c3e turn.batch changed-since-basis: fixed message, identities in `:seon.error/offending`.
- fa31a1a97 one printer: `seon.print.value` folded into `seon.render.value` (core-text/core-html; private floor);
  total `text`/`html` replace six guards; every print.value caller converted; db.clj error-text (8), fs deletion log,
  error/problems ui-print; `call-with-root-bindings` moved to seon.db and `seon.db/connection-generator` connects
  through it (regression in seon.flow-test). Proof: 148 named tests green (800 assertions, 108 s).

## Reds identical on HEAD 3fab5b880 (not this lane's)

- seon.turn-test/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer and
  a-schema-key-changed-since-the-run-read-it-is-refused-at-the-writer (fixture write refused: no `[:seon.ns/name user]`).
- seon.run-test/d8-a-test-obtains-a-forms-declaration-row-from-the-entrys-change.
- seon.cluster.instruction-test/every-qualified-call-in-instruction-code-resolves-at-its-arity (`seon.render/walk`,
  `my.message/send` unresolved).
- seon.test.runner-test/the-deliberate-failure-fixtures-declare-their-exclusion (my.examples-fixture lacks its
  `:seon.test/fixture` marker; test/my is lane examples-world's).
- seon.config-test caps-refused-for-want-of-a-cluster-carry-that-refusal-as-the-cause,
  two-clusters-on-one-jvm-have-no-config-bleed; seon.db-test database-operations-preserve-refusals-at-their-declared-boundary,
  query-and-pull-append-evidence-only-when-captured; seon.render.value-test a-value-s-own-inline-function-renders-it,
  dir-of-a-large-namespace-is-its-declared-renderer-text; seon.render.web-test
  a-reconnect-refuses-a-pass-derived-before-it-connected, a-route-throw-answers-a-bounded-500-inside-the-handler,
  unrelated-transaction-reuses-debug-observation-and-render-call; seon.schema.admission-test
  missing-recorded-admission-source-fails-closed, recorded-admission-is-representation-independent,
  shipped-schemas-remain-core-in-a-non-temporal-database; seon.test-support-test
  shared-property-reporting-is-a-clojure-test-assertion.
- Whole render.web-test, config-test and db-test namespaces exceed the check's 120 s limit beside the rest; the
  printer slice named their reaching subsets. Not run: the declared-long tests (include-long).

## Judged legitimate (no value printed outside the printer)

- test.runner report-error!/report-event! println (5): a fixed header plus `failure-message`, which is the value
  printer's output (report-value).
- schema warn-classpath-fallback! (3): fixed text and the caller's name.
- schema.admission -main `prn`: the findings EDN is the wire bin/seon-hook reads (serialization, not display).
- seon.sci.kernel memory-pressure-error: already fixed text with the pressure as data keys at HEAD.

## Open decisions

1. script/seon/operator.clj (babashka) cli-print 2 and log 2: babashka cannot load the value printer (render.value
   needs datahike, malli, sci), and `seon.print.value`, the projection-free printer it could load, is folded into
   the printer per the ruling. Options: the operator prints only fixed text and asks the JVM (prepl) for any shown
   value, which boot already answers through `core-text`; or keep printing EDN results as its wire.
2. test.runner reach-canonical (sort-by-print, 2 sites): orders map and set members for `id/digest`; changing the
   order changes every stored reach digest once, so it waits on the structural-hash (seon.id) decision.
3. turn.clj settle-batch-refusal! `:seon.turn/deletion-proposals-edn` and record-attempt! `-edn` attributes store a
   printed copy by schema; they wait on the owner's "what a stored value is for the remaining printed copies".
4. schema.admission findings now name their data keys in `:message`; bin/seon-hook shows only `:message`
   (bin/seon-hook feedback-response), so the editing agent sees the key names, not the similar schemas, until the
   hook shows finding data.
5. `:seon.render/walk-operation` (resources/seon/schemas/seon.render.edn) has no producer since the walk change.
