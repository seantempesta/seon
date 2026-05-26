---
type: research
status: completed
tags: [research, testing, verification]
---

# Phase 1.5 Cleanup — async-fixture probes wired + comment fixes (2026-05-26)

Follows-up `phase-1.5-verification-2026-05-26.md`. All three cleanup items
shipped in one atomic change touching 2 files
(`src/seon/dev/test_preload.cljs`, `src/seon/test/runner.cljs`).

---

## Item 1 — async-fixture probes wired (proves async fixtures work)

The verifier wrote `test/seon/test/async_fixture_probes.cljs` and
`test/seon/test/async_fixture_test.cljs` but never wired them into
`seon.dev.test-preload`, so shadow-cljs's `:client` build never
transitively compiled them and the runner reported `0 tests`.

Reviewed the files first — they are reasonable:

- Probes register a Promise-returning `:once :before` (50ms setTimeout) and
  a Promise-returning `:each :after` (20ms setTimeout). Both push lifecycle
  markers on resolve.
- Driver test asserts `summary {:error 0 :fail 0}` AND the exact lifecycle
  vector `[:once-before-async-resolved :each-before :probe-a-body
   :each-after-async-resolved :each-before :probe-b-body
   :each-after-async-resolved :once-after]`.
- Critically, the assertion would fail if `run-fixture-fn!` didn't await
  the Promise (markers would appear out of order or be missing).

**Action:** added both nses to `seon.dev.test-preload`'s `:require`. Pod
restart needed (shadow's hot-reload doesn't auto-load NEW preload deps
into a running `:node-script` runtime).

**REPL outcome (after `bin/seon restart pod`):**

```clojure
;; vars-in-ns lookup works after restart
(some? (js/goog.getObjectByName "seon.test.async_fixture_test"))
;; => true

;; the live driver test passes
(seon.test.runner/run-ns! {:seon.test.runner/ns 'seon.test.async-fixture-test
                            :seon.test.runner/record? false})
;; summary: {:type :summary, :test 1, :pass 3, :fail 0, :error 0}
```

All three assertions in the driver passed, including the exact lifecycle
ordering. The async `:once :before` was awaited before the first test
body ran; the async `:each :after` from probe-a was awaited before
probe-b started. **`run-fixture-fn!`'s Promise handling is correct as
written — no runner bug uncovered, no fix needed.**

## Item 2 — group-by ordering comment

Replaced "Stable per-ns groupings preserve the input ordering of vars
within a ns" with a comment that calls out the truth: WITHIN-ns is
stable, ACROSS-ns is stable only up to ~8 nses (CLJS
PersistentArrayMap→PersistentHashMap threshold). Single-ns is the
common case via `run-ns!`; multi-ns batches with >8 nses iterate
non-deterministically. No code change — the fix was a one-line comment
update; reordering the accumulator is deferred until a use case
demands it.

## Item 3 — `:var` field type note on `::test-event`

Added an inline schema comment above `[:var {:optional true} :symbol]`
warning consumers that the value is a CLJS Symbol, not a JS object —
`(.-sym v)` returns nil. Use `(name v)`, `(namespace v)`, or
`(str v)`. The verifier's NIT finding is now documented inline.

## Self-test regression check

```clojure
(seon.test.runner/run-ns! {:seon.test.runner/ns 'seon.test.runner-test
                            :seon.test.runner/record? false})
;; => {:type :summary, :test 7, :pass 26, :fail 0, :error 0}

(seon.test.runner/run-ns! {:seon.test.runner/ns 'seon.test.fixture-support-test
                            :seon.test.runner/record? false})
;; => {:type :summary, :test 1, :pass 4, :fail 0, :error 0}
```

Both self-tests are green at the verifier's baseline numbers.

---

## Files changed

- `src/seon/dev/test_preload.cljs` — added the two probe nses.
- `src/seon/test/runner.cljs` — fixed group-by ordering comment;
  added `:var` schema note.
