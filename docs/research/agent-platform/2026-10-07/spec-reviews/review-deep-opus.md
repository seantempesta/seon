# Review of 77c9f5459 (seon.id iterative canonical printer) — lane deep-review (Opus), 2026-10-07

Scope narrowed mid-review by owner ruling: hashing leaves printed text (lane `structural-hash` owns
`src/seon/id.clj`), so seon.id findings go to root, not edits; this lane fixed the two reader problems.

All probes ran in jvm mode on default (pid running pre-77c9f5459 code, so the new printer was evaluated
as a local `fn` read from the source file, never interned) plus one throwaway `java -cp clojure-1.12.5`
JVM for timing. Probe files: scratchpad `probe1.clj`..`probe4.clj`, `bench.clj`, `variant.clj`.

## seon.id (77c9f5459) — for root / structural-hash

1. **Byte identity: verified, no divergence.** 174 values (every kind in the spec: array/hash/sorted maps,
   namespaced maps incl. mixed/partial namespaces and symbol keys, `{nil 1}`, map entries, 40 live record
   classes, struct maps, sets, `:type`-meta collections incl. non-keyword `:type`, symbols/keywords with
   spaces and empty names, `/`, chars incl. `\u0000`, escaped strings, ratios, bigdec/bigint, NaN/Inf,
   regex, inst, uuid, Date/Timestamp, tagged literals, reader conditionals, java collections, queues,
   arrays, atoms/delays/vars/fns, eductions, lazy/chunked/range/string seqs, empties), each top-level, inside
   a vector, as a namespaced-map value and as a map key: 0 differences from `pr` under the fixed bindings.
   `lift-ns` matches clojure 1.12.5 core_print.clj (checked against the loaded resource).
2. **Depth-safe only for the four standard collections (low; the claim "any depth" is overstated).**
   A deep value under any other print-method recurses through `pr-on` and still throws
   `StackOverflowError` (measured, 10,000 deep): `(tagged-literal 'x deep)`, `(with-meta {:a deep} {:type :foo})`
   (the `:default` method re-dispatches), `(java.util.ArrayList. [deep])`, `(java.util.HashMap. {:a deep})`,
   records holding a deep value. Deep keys of plain maps are fine (40,002 chars). Moot once hashing is
   structural; whatever replaces `printed` for storage should state its depth guarantee per wrapper.
3. **Cost: no real slowdown.** Isolated JVM, median of 5: 3-element vector pr 2.02 us vs commit 1.99 us;
   medium task map 3.34 vs 3.75 us (+12%); 200 maps 401 vs 473 us (+18%). An
   `(instance? IPersistentCollection x)` pre-filter before `get-method` (variant.clj) brings the last two to
   3.28 us / 413 us, i.e. parity with `pr`. The 1.13 -> 1.74 us in the commit message is within noise
   on the loaded default JVM (repeat runs there swung 2.3–3.4 us).
4. **Regressions:** seon.id-test pins literal texts for 16 values and the 10,000-deep vector/ns-map;
   they do not pin the wrapper cases in (2). Babashka (5): not re-verified (scope cut).
5. Related, not in the commit: `seon.schema.datahike` (~line 533) round-trips `(edn/read-string (id/printed v))`,
   which overflows on the same depth the printer now handles (file held by commit-validation).

## Reader problems (fixed / filed)

**(b) Super-linear malformed nested maps — FIXED at the producer, `seon.sci.reader/recovery-point`.**
Cause (measured): a failure nested in a collection was classified `:invalid-token` (nested odd map) and
recovered by `token-end` from the form's start, which skipped only `{:a` and re-read the rest; one read
of an edamame failure costs O(depth) (the fork wraps one ex-info per enclosing container: 35 ms at 100,
71 ms at 200), times O(depth) retries → 25/50/100 levels: 134 / 584 / 4,280 ms, 199 events for one
missing value. Worse, it was a correctness bug: `[1 2 3x 4]\n(+ 1 2)` read as
`error, 1, 2, error, 4, stray-closer, (+ 1 2)` and `{:a {:a 1 :b}}` leaked `:a` — inner elements became
forms that would be evaluated. Fix: `token-end` recovery only for a top-level token (`::nested?` from
Edamame's containers); a nested failure skips to the next column-zero anchor; a nested odd map is
classified `:odd-map` (Edamame locates it at its own `{`). Now one error event, one read.
Regression: `seon.sci.reader-test/a-failure-inside-a-collection-skips-the-whole-form`.

**(a) Deep artifact read-back — misreport FIXED, depth FILED.** `seon.sci.reader/read` of a 3,000-deep
vector answers one recovered error event (`java.lang.StackOverflowError`); `read-artifact` only checked
for a non-vector failure, so it reported "not a value artifact". It now reports any error event as
"The stored content does not read: …". Regression:
`seon.render.value-test/an-unreadable-artifact-names-the-readers-failure`. The reader's recursion itself
(edamame) is a task in `resources/seon/tasks/seon.sci.reader.edn`
("a value deeper than the reader's stack cannot be read back").
