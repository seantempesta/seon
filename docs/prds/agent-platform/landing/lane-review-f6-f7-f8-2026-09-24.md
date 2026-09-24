---
type: landing
status: F6 and F8 committed code only (owner load rule); F7 stopped with options; unadopted
lane: review-f6-f7-f8
created: 2026-09-24
design: docs/research/agent-platform/review-landings-2026-09-24.md F6, F7, F8
---

# Lane review-f6-f7-f8 (2026-09-24)

Findings F6, F7, F8 of `docs/research/agent-platform/review-landings-2026-09-24.md`.
F6 and F8 landed. F7 is **stopped with options**: no derived macro source exists
within the size limit.

## Broken or open first

- **F7 is not fixed.** `seon.fn/host-macro?` (`src/seon/fn.clj:813`) still reads
  `find-ns`/`ns-resolve`. The reason and three priced options are below.
- **Pre-existing red found (outside scope; `fn_test.clj` is owned only for new regressions):**
  `seon.fn-test/namespace-context-reads-clauses-from-the-publication-analysis`
  (`test/seon/fn_test.clj:2490`) asserts that a top-level `(require '[clojure.data :as data])`
  is not a clause. Its expected `:requires` leaves out `clojure.data`, and `:aliases` must be
  exactly `{e clojure.edn ab a.b}`. The analyzer has attributed top-level requires since
  `a209c6d3f` / fix-ns-from-kondo. A probe of `seon.fn/namespace-context` on that exact
  fixture returned `:requires #{clojure.test clojure.set clojure.edn clojure.data clojure.walk}`
  and `:aliases {ab a.b, data clojure.data, e clojure.edn}`. I expect the test is red on
  HEAD. It was not run, per the load rule. The fix is in the test only: add `clojure.data`
  and `data`, and delete the "a later require is no clause" message. Owner: whoever holds
  `test/seon/fn_test.clj`.
- The F6 prose assertion (`interrupted_request_test.clj`, "the unknown is shown by name")
  still reads the whole `problems/ai-prose`. If the prose bound (fix-problems-prose-bound)
  elides names when many inherited unknowns exist, it can fail for the same inherited-world
  reason. F6 named only lines 102/103, so this was not changed.

## F6: commit `15539cd68`

`test/seon/test/interrupted_request_test.clj`: both assertions now intersect the failed and
unknown lists with the fixture's own `#{finished red interrupted}`. They assert `#{red}`
failed and `#{interrupted}` alone unknown. The per-symbol checks are kept: without S0, an
open member that is listed as failed, or not listed as unknown, still fails the test.
Net +7 lines.

## F8: commit `e66b50448`

- **Cause (REPL):** clj-kondo attributes the `:rename` keyword inside a top-level `require`
  call to the requiring namespace. The keyword row carried `:from scratch.f8` at row 2,
  col 39. `with-namespace-clauses` took renames only by position inside the `ns` form, so
  for the counterexample `clause-renames` was `[]` and the refer `union` was kept with no
  local `join`.
  Separately, a `:rename` inside the `ns` form carries the *previous* namespace as `:from`
  (probe: `:from user`), not "no `:from`". The docstring is corrected.
- **Fix:** `parsed-facts` (the existing kondo node parse) also returns `::require-forms`,
  the spans of `require`/`clojure.core/require` call lists. A bare `:rename` keyword inside
  one of those spans joins its `:from` namespace's `::clause-renames`.
  `seon.fn/namespace-context` then issues the existing `:seon.fn/index-refused` refusal;
  its message now says "A require's :rename". A body `(:rename m)` is still data.
  `with-namespace-clauses` gained its missing contract.
- **Probe after (JVM, analyzer reloaded):** top-level rename gives `[{:row 2 :col 39}]`.
  An ns-form rename gives `[{:row 1 :col 54}]`, unchanged. A body keyword gives `[]`.
  `namespace-context` on the counterexample returns the refusal and names that entry.
- **Cost:** `parsed-facts` on `src/seon/fn.clj` (3,742 lines, 15,287 nodes) takes
  30.3–33.6 ms after the change. The added filter pass, timed alone, takes 2.3–4.7 ms
  (about 8–15%). That is under the 20% / 50 ms threshold, and each file is parsed once
  per analysis.
- **Regression:** `seon.fn-test/a-top-level-require-rename-is-refused-by-name`, using the
  exact counterexample. It asserts the declared refusal schema and that the named entry is
  on row 2. It would fail before the fix, because `namespace-context` returned a context.
- Net +38 lines across 3 files.

## F7: stopped, three options

**What the REPL showed:**
- clj-kondo *does* derive the fact. With core.async's jar analyzed into a cache
  (`clj-kondo.core/run!` with `:canonical-paths true`, 306 ms), `seon.fn.analyzer/analyze`
  of a `go-loop` caller given that `::cache-root` returns `:macro true` on the `go-loop`
  usage. The fact derives from the dependency's source and does not depend on load order.
- The checkout's shared cache `.clj-kondo/.cache/v1` has **no dependency entries at all**
  (492 clj entries, all first-party or fixture). No code populates them, so without that
  cache `go-loop` arrives with no `:macro`.
- A jar cache written *without* canonical paths records inner filenames like
  `clojure/core/async.clj`. `stale-cache-entries` classifies those as `::source-absent` and
  deletes them (observed: the entry disappeared after one `analyze`). Any population step
  must write canonical `jar:` filenames.
- If I refused "unknown" from today's analysis, every call into a non-interpreted
  dependency would be refused, because the cache knows none of them. That would break
  publication.

**Options (simplest viable first):**
1. **Refuse by name only where kondo cannot resolve the macro, after a one-time dependency
   cache population.** Add `bin/seon`/boot step to lint the classpath jars with
   `:dependencies true :canonical-paths true` into the shared cache. kondo skip-marks each
   jar by name and config hash, so reruns are cheap. `host-bound-callers` uses
   `::analyzer/macro`, and `analyze` reports the namespaces a usage targets that have no
   cache or built-in entry, which refuse by name.
   Guarantee: deterministic facts from source. Cost: about 40–60 lines across analyzer, fn
   and boot. The cold lint of about 100 jars is unmeasured; extrapolated from 0.3–0.75 s
   for core.async it is tens of seconds, which is over the 10-second rule, so it needs
   owner authorization and a clock row. Sacrifice: new dependency-arity findings may
   surface at publication; the finding gate needs checking.
   **Recommended**, because it is the only option that derives the fact at the boundary.
2. **`requiring-resolve` at classification.** Loading the target namespace makes the JVM
   fact the same whatever the load order (3 lines; an unloadable namespace refuses by name).
   Guarantee: determinism against load order only. It is keyed by the launched classpath,
   which `seon.cluster.source` already reports as "JVM RESTART NEEDED" when it changes.
   Sacrifice: analysis loads dependency namespaces as a side effect, and the fact still
   comes from the JVM, not from analysis. The finding rules this out as unkeyed.
3. **Treat every var usage into a non-interpreted, non-first-party namespace as host-bound**
   unless kondo marks it a non-macro. That is roughly equivalent to "refuse unknown as
   host-bound".
   Guarantee: conservative and deterministic. Cost: about 5 lines. Sacrifice: with today's
   empty dependency cache, most program functions calling any dependency become host-bound
   (SCI cannot interpret branch overrides of them). That is a large behaviour change.

Root rules. Nothing was edited for F7.

## Verification limits and what root runs

Per the load rule, no test requests were run. The analyzer namespace was reloaded once in
default (`require :reload`, the owned namespace) for the probes. `seon.fn` was not reloaded,
so the refusal message change is unpublished there. clj-kondo lint of all touched files:
0 errors (only warnings that were already there).

Root runs, serially:
- `bin/test-check default --test seon.test.interrupted-request-test/one-incomplete-member-is-its-own-unknown-and-its-run-mates-stay-readable`
- `bin/test-check default --test seon.fn-test/a-top-level-require-rename-is-refused-by-name`
- `bin/test-check default --test seon.fn.analyzer-test/namespace-clauses-are-every-require-clj-kondo-attributes-to-the-namespace`
- `bin/test-check default --test seon.fn-test/namespace-context-reads-clauses-from-the-publication-analysis`
  (expected red before this lane; see above)

Commits: `15539cd68` (F6), `e66b50448` (F8), plus this note. Released paths:
`test/seon/test/interrupted_request_test.clj`, `src/seon/fn.clj`, `src/seon/fn/analyzer.clj`,
`test/seon/fn_test.clj`, `test/seon/fn/analyzer_test.clj` (not edited), and this landing note.
