---
type: research
status: completed
tags: [research, testing, verification]
---

# Phase 1.5 Verification — fixture-walking in run-vars (2026-05-26)

**Verdict: YELLOW** — core fix is correct and the 5-fail claim holds, but two
issues were found: one is a real latent bug (group-by ordering), one is an
acknowledged gap with a code-path gap in the sentinel test.

---

## Per-probe results

### Probe 1 — Confirm 5 fails across exactly 2 vars

PASS. Live run: `{:test 26 :pass 220 :fail 5 :error 0}`. Fail correlation:

```
seon.db-test/transact!-throws-synchronously-on-unregistered-attr  — 2 fails
seon.db-test/transact!-throws-synchronously-on-bad-value          — 3 fails
```

Exactly 2 vars, matching the audit's rows 5+6. Claim is true.

### Probe 2 — Async :once :before returning a Promise

N/A (UNVERIFIED BY LIVE RUN — confirmed acknowledged). The new probe files
(`test/seon/test/async_fixture_probes.cljs` + `async_fixture_test.cljs`) were
written and compile-loaded but the runner returned 0 tests because the files
are not in `seon.dev.test-preload` — shadow-cljs only compiles what the
preload transitively requires. The code path in `run-fixture-fn!` is correct
(thenable? check confirmed, Promise.resolve chaining confirmed), but no live
test exercises it. The handoff's acknowledgement is accurate.

**Note:** The two probe files were created during verification and are not
wired into the preload. They should be deleted or added to the preload.

### Probe 3 — Async :each :after fires after async body resolves

PASS. The existing `seon.test.fixture-support-test` asserts the exact lifecycle
sequence including `:each-after` AFTER `:probe-c-async-body`. Summary: 1 test,
4 pass, 0 fail, 0 error. The `:once :after` / `:each :after` also appear in the
correct reverse-registration-order relative to their `:before` counterparts.

### Probe 4 — Multi-ns batch grouping

PASS. `run-vars` called with syms spanning `seon.test.runner-probes` (no
fixtures) and `seon.test.fixture-support-probes` (has `:once`/`:each`):

```
lifecycle: [:once-before :each-before :probe-a-body :each-after :once-after]
summary:   {:test 3 :pass 2 :fail 1 :error 0}
```

Fixtures fired only for `fixture-support-probes`. Runner-probes syms ran
without fixture contamination. No spurious errors.

**However:** see Finding 1 — with >8 nses the doseq over `(keys by-ns)` uses
hash-map iteration order, which is non-deterministic. Two nses use array-map
(≤8 keys) so today it's safe, but the comment "Stable per-ns groupings" is
only true up to 8 namespaces.

### Probe 5 — Fixture-throws-then-test

PASS (code review). `run-fixture-fn!` catches `:default`, emits `:error`
event, resolves the Promise. There is no guard in `run-vars` to skip the
test after a `:before` fixture throws — the doseq continues unconditionally.
This matches upstream `cljs.test/wrap-map-fixtures` behavior (concat
:befores + block + :afters with no skip logic). Divergence from upstream:
none. Behavior is correct-by-convention.

### Probe 6 — Fixture ordering with nested testing + is

PASS. Running `fixture-support-probes/probe-a` and `probe-b` via `run-vars`
produces the correct event sequence:

```
[:begin-test-var :pass :end-test-var :begin-test-var :pass :end-test-var :summary]
```

`:var` on each event is a proper CLJS symbol (`seon.test.fixture-support-probes/probe-a`).
`current-var-sym` correctly extracts from the `#js {:sym sym}` stack entry via
`unchecked-get`. Nested `testing` + `is` blocks would not affect this — the
reporter dispatch is keyed on `[::capture :pass]`, and `t/*current-env*` is
bound per-run-vars invocation.

### Probe 7 — Self-test regression

PASS.
- `seon.test.runner-test`: 7 tests, 26 pass, 0 fail, 0 error.
- `seon.test.fixture-support-test`: 1 test, 4 pass, 0 fail, 0 error.

### Bonus — Malli nil-message fix (Phase 1 regression guard)

PASS. `probe-passing-test` has no `:message` in its `(is ...)` form. The
emitted pass event does NOT contain a `:message` key (`:message nil` is
suppressed by the `some?` guard in `record-assertion!`). Phase 1 fix holds.

---

## Findings

### 1. SMELL — group-by ordering non-deterministic beyond 8 namespaces

`run-vars` groups by `:ns` with `group-by`, which returns PersistentHashMap.
CLJS array-map is used for ≤8 keys (insertion-order stable); beyond that,
PersistentHashMap hash-iteration order applies:

```clojure
;; 10 nses → non-insertion order confirmed live:
["ns-zeta" "ns-delta" "ns-gamma" "ns-iota" "ns-beta"
 "ns-epsilon" "ns-kappa" "ns-alpha" "ns-eta" "ns-theta"]
```

The comment on line 413 ("Stable per-ns groupings preserve the input ordering
of vars within a ns") is misleading — it's only stable for the WITHIN-ns
ordering, not across-ns ordering. Today `run-ns!` is single-ns, so this
never triggers. A `::vars` selector spanning many nses would run them in
unpredictable order, making `:once` fixture side-effects non-deterministic.

**Severity:** smell. Not a blocker for current usage. Fix: use
`(reduce (fn [m v] (update m (:ns v) (fnil conj []) v)) (array-map) present)`
to preserve insertion order at all sizes.

### 2. BLOCKER (acknowledged) — async :once :before fixture body unverified

`run-fixture-fn!` has the correct code for awaiting a Promise-returning
`:before` fixture. But no live test exercises this path. The `fixture-support-
test`'s lifecycle only exercises: sync `:once :before`, sync `:each :before`,
sync `:each :after`, and an async TEST BODY (probe-c). An async FIXTURE body
(`:before` returning Promise) is unverified. This is a blocker for the claim
"async fixtures returning a Promise are first-class."

**Severity:** acknowledged blocker for completeness; low priority given no
current seon test uses async fixture bodies. The proof is code review only.
Fix: add `async_fixture_probes.cljs` to `seon.dev.test-preload` and wire
the `async_fixture_test.cljs` through the preload. The files already exist
at `test/seon/test/async_fixture_probes.cljs` and
`test/seon/test/async_fixture_test.cljs` (created during verification,
not yet in the preload — should be wired or deleted).

### 3. NIT — :var on fail events is nil when accessed via `.-sym`

`(some-> e :var (.-sym) str)` returns nil because `:var` on emitted events is
a CLJS Symbol (not a #js object). The correct accessor is `(str (:var e))`.
This is a consumer-side ergonomics issue, not a correctness bug in the runner —
the emitted `:var` value is a proper symbol with correct `namespace`/`name`.
Mentioned because future code reading `:var` via JS property access will be
silently wrong.

---

## Confidence

**High confidence:** Probes 1, 3, 4, 6, 7, and the Phase 1 regression guard
are live REPL runs against the actual code. The 5-fail claim, the exact 2-var
distribution, the fixture lifecycle ordering, and self-test stability are all
confirmed by live output.

**Medium confidence:** Probe 5 (fixture-throws behavior) is confirmed by code
review and confirmed consistent with upstream cljs.test — not by injecting a
throwing fixture at runtime.

**Low confidence:** Probe 2 (async fixture `:before`) is confirmed by code
review only. The `thenable?` check and Promise chain are present and correct,
but the live test path could not be exercised without modifying the preload.

---

## Cleanup note

Two verification files were created at:
- `test/seon/test/async_fixture_probes.cljs`
- `test/seon/test/async_fixture_test.cljs`

These are NOT in the preload and will not run automatically. They should be
wired into `seon.dev.test-preload` (covers Finding 2) or deleted.
