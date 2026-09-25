---
type: landing
status: landed-unverified
lane: fix-instrument-f2-regressions (Opus 5.5)
created: 2026-09-25
source: docs/research/agent-platform/red-baseline-census-2026-09-25.md row #16
---

# instrument-test reds after F2 (2026-09-25)

The root's HEAD run showed 7 red deftests (pass 393 / fail 12 / error 4). F2 (685ffd6e6) caused none of them.
An unarmed Var never reaches the wrapper. The other reds come from the minimal-refusal `catch Throwable` that
ab7e4a643 and 393846eb2 added in `compiled-wrapper`. Evidence came from the recorded results on default
(`seon.test/recorded-result`) and from JVM probes in the scratch namespace `lane-f2fix`. No Var of default was
redefined. The only exception was a `load-string` copy under `lane-f2fix.instrument`, which was removed afterwards.

## Broken or unproven first

- **No test was run** (LOAD RULE). Every item below still needs root's rerun.
- `a-sovereign-sci-fork-acquires-its-own-recorder`: not investigated. It took 12,474 ms against a 5,000 ms bound.
  Its recorded error is the foreign analysis refusal (`operator_test.clj:59`, fixed by 641cd84ba). Root's rerun
  shows whether the time remains without that refusal.
- **RESET NEEDED: no.** Adoption of `src/seon/instrument.clj` is still pending (publication is paused).

## Classes and evidence

1. **Source regression: a bound firing became a minimal refusal** (`a-deadline-firing-…`, :527/:531).
   - The recorded actual was `"seon.instrument-test/deadline-probe :malli.core/invalid-input: the refusal could not
     be built."`, with SCI's `:sci.impl/interrupt` in its chain.
   - ab7e4a643 deleted 325fd0e6b's rule that `kernel/interrupted?` failures leave unchanged.
   - Fix: `reraise-bound!` in `compiled-wrapper`'s `refusal`. It covers both the construction catch and Malli's
     `::invalid-schema-at-call`, which wraps an interrupt fired during validation because the interrupt is an
     `ex-info`.
   - Probe (the same lazy deadline value, inside `kernel/arm`):
     - before: `{:interrupted? false, :msg "…the refusal could not be built."}`
     - after (edited copy): `{:frames true, :interrupted? true, :msg "time-limit"}`
   - Commit 09d32292b: +13 / −3 in src.
2. **Test asserting a retired pass-through** (`a-reporter-failure-…` :1536/:1537 and `an-invalid-refusal-…`
   :1500/:1508).
   - 393846eb2 made "a construction that throws" and "a refusal its schema rejects" produce the minimal
     `:seon.error/base` (see the regression `a-refusal-that-cannot-be-built-is-a-minimal-declared-error`). That
     law is later and was reviewed.
   - The tests now assert:
     - the root of the chain carries the humanizer message and its data (`"original cause"`) or the rejection
       message;
     - the value is a base and not a contract error;
     - `kernel/failure-value` keeps member, expected, offending, chain and message.
   - All of these were probed live: `:base? true, :ce? false, :ev "original cause"`, and kernel `:same true`.
   - Commit ce3342645.
3. **Test relying on ambient arming** (`an-error-shaped-argument-…` :598–601, `the-caller-frame-…` :1435 plus the
   `render-ai` NPE, `host-diagnostics-…` :1454 arglists nil).
   - `seon.instrument-test/prefix-contract` is unarmed on default: `:malli.instrument/original` is absent, and only
     3 of 1,911 armed Vars are in test namespaces. The suite loads after boot arming, so each call returned
     instead of refusing.
   - The census of 2026-09-23 (`lane-wrapper-profiling-2026-09-23.md:210`) already suspected this, before F2.
   - Fix: `arm-prefix-contract!` calls `apply!` over its one changed identity and asserts `registered` is 1. The
     fixture restores the root. Default's carried projection declares the contract (probe: `:reg true`).
   - Commit 0e91f862a.

Net: src +13/−3, test +35/−12. Commits 09d32292b, ce3342645, 0e91f862a.

## Root runs

1. `bin/test-check default --ns seon.instrument-test`. The expected result is that the 6 classified deftests turn
   green, and the sovereign fork result is reported against its time bound.
2. After adoption of `src/seon/instrument.clj`, run
   `bin/test-check default --test seon.instrument-test/a-refusal-that-cannot-be-built-is-a-minimal-declared-error`.
   This confirms that `reraise-bound!` leaves non-interrupt failures minimal.

## Out of scope finding

`seon.test/failure-text` (`src/seon/test.clj:39`) prints the blob hint `(seon.blob/get digest)`. That call has
the wrong arity: `seon.blob/get` takes `[connection digest]` (`src/seon/blob.clj:376`), so the hint refuses when
someone follows it. `docs/seon/issues/` has no issue for this. `src/seon/test.clj` is outside this lane's paths,
and it has an uncommitted foreign edit.
