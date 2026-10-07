# deep-value lane (2026-10-07)

Task: seon.turn/a-deep-value-loses-the-run.

## Reproduced (default pid 30874, branch agent deep-value-probe)

`(def deep (reduce (fn [v _] [v]) [] (range 10000)))` then `deep`: reply is only
"run transition refused: no-terminal-fact" (seon.turn/receipt-settle-call); the value is lost.

## Cause (measured, jvm mode)

- `seon.sci.eval/shown-result` on the deep value throws `StackOverflowError` (24 ms).
- Stage by stage: `render.value/prepare` 1 ms ok; `print.value/bounded-text` ok; `admit/admit-value` 17 ms ok
  (iterative walk); `value-storage` with no elisions ok; `value-storage` with elisions (the cut value is
  stored) throws: `render.value/artifact-edn` -> `admit/canonical-edn` -> `seon.id/printed` ->
  `print-canonical!` = `clojure.core/pr`, which recurses once per level (print-sequential / print-prefix-map
  frames fill the 1024-frame trace). The print node of a 10,000-deep vector is ~20,000 nested
  maps/vectors.
- The throw escapes shown-result, the batch entry has no shown text, the receipt carries no terminal fact,
  and the writer refuses the whole settlement with `::no-terminal-fact`.

## Design

The producer is the one canonical printer, `seon.id/print-canonical!` (identities, stored artifacts, error
data blobs, test-failure artifacts all go through it). Make it iterative: an explicit stack of open
collection frames. A value takes this path only when the print-method Clojure would dispatch to it is
EXACTLY one of the four standard collection methods (IPersistentVector, IPersistentMap, IPersistentSet,
ISeq), compared by `identical?` on `get-method` with the same dispatch (`:type` meta keyword else class);
those methods' text is reproduced exactly (print-sequential separators, `lift-ns` namespace-map prefix
under `*print-namespace-maps*` true, no meta since `*print-meta*` false, no length/level). Everything else
(scalars, strings, records, objects, Seon's env print-method) is printed by `print-method` itself, so the
text is byte-identical and every stored identity is unchanged.

Cost: same linear walk, memory proportional to depth (one frame per open collection) instead of JVM stack.
Before: StackOverflowError at ~2,000 levels of nested collections. After: bounded by heap only.

Smallest correct fix: one function in the owner every caller already shares; no caller changes, no
second printer (it IS the printer, made iterative), no clipping, no big-stack thread.

## Not doing

- `seon.sci.eval/value-storage` is held by lane value-storage, which deletes it (no evaluation value copy
  is stored). This fix makes the path total whether or not that lands.
- `seon.error.refusal/referenced` also overflows on an ex-info whose ex-data is 10,000 deep
  (`(throw (ex-info "deep" {:v deep}))` -> reply `:error java.lang.StackOverflowError` and
  `shown-failure refused argument 0 error: expected a map, got nil`): file held by lane error-frames;
  reported, not touched.
- Reading back a stored artifact deeper than ~2,000 collection levels: the edamame reader
  (`seon.sci.reader/read`) answers `java.lang.StackOverflowError` as its reader error, and
  `render.value/read-artifact` then misreports it as "not a value artifact". Restart-only path; reported.
- Reader error path on malformed nested maps is super-linear: `{:a {:a ...}}` with one form missing,
  100 levels 1.46 s, 200 levels 6.7 s (`seon.sci.reader/read`). Reported.
