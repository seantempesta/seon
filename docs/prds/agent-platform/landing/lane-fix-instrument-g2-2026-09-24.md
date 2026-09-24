---
type: landing
status: four slices committed; proven in a snapshot root, not on default; skill row edit uncommitted (hook)
created: 2026-09-24
---

# fix-instrument-g2: validator exceptions are reports; minimal refusals keep their evidence

Malli's `-instrument-f` already reports every violation through `:report`; the fork routes a
throwing validator through that same callback, and Seon's wrapper builds its minimal base from
the report it already receives. No new mechanism: one `try` per validator in the fork, one more
argument to `minimal`, and the dead test arming owner deleted.

## Broken or unproven, first

- **Default does not run this.** Proof is from `git archive` snapshots booted as their own roots
  (`tmp/fix-instrument-g2/snap-2`: HEAD + diff, reference-code linked to the checkout at Malli
  `befcd302`; `snap-base`: `d98b18fcb` with Malli `8725a8cb` archived). Default needs
  `start --head` or **RESET NEEDED** to load the new pin.
- **The clojure-testing skill row is edited but not committed.** The markdown hook refuses the
  file for 12 stale line citations on other rows (for example `src/seon/test.clj:1584`;
  `seon.test/run` is now at 1607). My row's own citations passed. Those rows are outside this
  lane's ownership. The edit stays in the working tree for the file's owner to commit after fixing
  them.
- **`seon.instrument-test` is red at the parent and at this change.** Parent: 12 reds of 55. Change:
  10 reds, all of them among the parent's 12. The two parent reds that are gone are
  `a-validator-that-throws…` (replaced) and `cold-arming-keeps…` (it passed a projection where
  `arm-var!` now takes the built wrapper, so it was stale after G1). The surviving 10 are foreign.
  Examples are `a-sovereign-sci-fork…`, which takes 43–60 s, and several tests that assert the
  pre-G3 full refusal text.
- **Test reuse did not notice the Malli change.** After I re-pinned from `af249309` to `befcd302`
  and restarted the root, `seon.test/run` reused 3/3 greens. That may be because a snapshot has no
  `.git`, so the gitlink input cannot be read. I did not verify the cause. The befcd302 proof below
  is therefore a direct in-JVM call of the test bodies, not a recorded run.
- The intermediate commits `393846eb2` and `0b6f0a1bc` were not booted on their own. Only the final
  tree was booted.

## Changes

1. `8eb4ad740`: gitlink to fork `56394c54`. This is the reviewed validator-only report. Its parent
   is the old pin. Body exceptions pass through: the `try` wraps only `(validate x)`.
2. `393846eb2`: fork `befcd302`, pushed to `fork/seon-ref-scope`. `::invalid-schema-at-call` now
   carries the arm's schema plus `:args`/`:value`, the same members as the matching invalid
   report. The map is built only in the `catch`. My first version allocated a data map on every
   call, and I removed that before pushing.
   Seon's `minimal` now takes the report and sets `:seon.error/expected` (the refused arm's form,
   or the function schema for arity) and `:seon.error/offending` (the checked value).
   The test `a-refusal-that-cannot-be-built-is-a-minimal-declared-error` covers:
   - throwing input, output and guard validators;
   - an absent refusal schema;
   - a refusal its schema rejects;
   - a construction that throws (an argument whose `hashCode` throws);
   - a body throw that must come back `identical?`.

   It builds the wrapper that `apply!` installs, without interning or arming any Var.
3. `0b6f0a1bc`: the suite fixture no longer uses `alter-var-root` or resets the atom by hand. It
   runs each test inside `test-support/with-database` plus
   `test-support/preserving-instrumentation-state` (`seon.instrument/restore!`).
4. `90b7a13dd`: deleted `src/seon/test/arm.clj`. The cold-arming test now arms a disposable
   namespace's Var through `apply!` with `:changed-identities`. The selection test arms through
   `apply!`'s `collect-contracts!` and asserts that `armable` for the namespace is covered.
   The skill row now names `seon.cluster/arm-host-program!` (cluster.clj:2394) as the boot
   enforcement.

`deps.edn` resolves `metosin/malli` from `{:local/root "reference-code/malli"}`. In the snapshot,
`(io/resource "malli/core.cljc")` pointed at `reference-code/malli/src`, and the text contained the
new `valid?` call. The dependency-class cache missed (`:pins-unavailable`), so the class was loaded
from source.

## Evidence

- **Fork suite (kaocha).** 247 tests, 18,750 assertions, 0 failures at `56394c54` (106.7 s wall)
  and again at `befcd302`.
- **Probe (plain snapshot JVM, first calls).** Each case below gave a `:seon.error/base`-valid
  minimal error with the chain root message shown.

  | Case | Time | Expected | Offending | Chain root message |
  |---|---|---|---|---|
  | input | 0.9–12 ms | `[:cat [:schema [:ref :probe/absent]]]` | `[1]` | `:malli.core/invalid-ref` |
  | output | 9 ms | — | `2` | `:malli.core/invalid-ref` |
  | guard | 3.9 ms | — | `[[3] 3]` | `:malli.core/invalid-ref` |
  | missing refusal schema | 4.8 ms | — | — | "Missing schema declaration :seon.instrument/refusal-result." |
  | rejected refusal | 4.7 ms | — | — | "Instrumentation constructed an invalid refusal." |
  | construction throws | 2.9 ms | — | — | `unhashable` |
  | body throw | 0.56 ms | — | — | not applicable: the exception came back `identical?` |

  Before the `MapEntry` fix, the guard case itself threw a `ClassCastException` out of `minimal`.
  The probe caught that.
- **Recorded runs on snap-2 (af249309).** Run `edcc9e9135f5`: 28 executed plus 27 reused, 403 pass,
  17 fail, 6 error, 126.0 s. My three tests were green. Parent run `7bd9c5857b3b`: 55 executed,
  348 pass, 25 fail, 6 error, 105.5 s. My first run `2c0bf4d4a772` used the default request limit
  and ran out of time behind the foreign 60 s test.
- **Direct run of the test bodies on befcd302, snapshot JVM, armed contracts.**
  - `a-refusal-that-cannot-be-built…`: 50 pass, 30.8 ms.
  - `cold-arming-keeps…`: 7 pass, 115 ms.
  - `the-selection…`: 17 pass, 481 ms. Inside it, a full `apply!` took 227 ms and then 159 ms
    unchanged, for 1,884 registered.
  - Afterwards the JVM's instrumented count was back to its entry value (1,848), and no scratch
    namespace remained.
- **Hot path (armed `m/-instrument` call with input, output and guard arms; 2M calls × 10,
  minimum ns per call; load average 17–19).** Old pin: 119, 116 (plus one run at 387). New pin:
  122, 141, 123. That is about a 4% difference, within noise and under the 20% / 50 ms rule.
- **Snapshot costs.** Cold boot took 152 s and 131 s (both dependency-cache misses), and a restart
  took 17–74 s. These are properties of a snapshot root, not of this slice.

## Lines

- Source: `instrument.clj` +31/−13 net of the minimal change. `arm.clj` −254.
  **Seon src net −236.**
- Fork: src +15/−4, test +15/−1.
- `instrument_test.clj`: +142/−84.

## Released paths

- `src/seon/instrument.clj`
- `test/seon/instrument_test.clj`
- `src/seon/test/arm.clj` (deleted)
- `reference-code/malli` and its gitlink
- This note

`.agents/skills/clojure-testing/SKILL.md` stays held until its row is committed.
