---
type: landing
status: landed-unverified
lane: f2-instrument-one-wrapper (Opus 5.5)
created: 2026-09-24
source: docs/research/agent-platform/projection-call-cost-2026-09-24.md (F2); contracts-2026-09-24.md row 11
---

# F2: one wrapper per adoption (2026-09-24)

`seon.instrument/arm-var!` installed a host wrapper that, on every call, sniffed a projection from
the arguments (meta, `:seon.schema/projection`, `:seon.env/environment`) or the binding
(`handed-projection`), and recompiled `compiled-wrapper` into a one-slot holder whenever that object
was not `identical?` to the last one seen. **Approach:** arm once. The installed wrapper calls the
wrapper that adoption compiled (`boot-wrapper`). A call never picks a projection, so it never
compiles. Content identity is already decided at arming: `current-wrapper?` compares the contract
and its closed-over definitions by value. An equal-content projection keeps the wrapper, and changed
content rearms. Other branches' differing rows interpret in SCI (CLAUDE "One JVM, many realities").

## Broken or unproven first

- **No test was run.** The LOAD RULE applies. The edit is not adopted into default: hook publication
  is paused and root adopts. The regression, the converted callers and the fault timing are all
  unverified until root runs the commands below.
- **The per-fault recompile count was not measured.** It needs the fault test to run. The read-only
  snapshot below gives the live holder state, not a per-test count.
- **Risk (was UNVERIFIED in the contracts table and is still unverified):** a caller may rely on a
  host contract validating against a *branch's* differing schema definition. After this change, the
  loaded declarations decide. That is the law, but a test that asserted the old behaviour would now
  go red. `validates-loaded-contract?` and its fallback comment show the switch mostly re-validated
  equal content.
- **Ownership:** I converted callers outside the named row, because they passed a projection *map* as
  `boot-wrapper` and depended on the deleted per-call path: `test/seon/profile_test.clj` (arm-probe!)
  and `test/seon/error/refusal_test.clj`. Both are free in the ledger. Without the conversion, their
  armed Vars would call a map.

## Step 1: measurement before the edit (read-only, default pid 39156)

A scratch namespace `f2-probe` reflected each armed root's closed-over `supplied_wrapper` atom and
read `(first @atom)`. It redefined and re-armed nothing.

| observation | value |
|---|---:|
| armed host Vars | 1,869 |
| Vars whose holder had compiled a per-call wrapper since boot (holder non-nil) | **598** (≥598 recompiles) |
| distinct projection objects held | 3 |
| of those, with `=` `:seon.schema.projection/forms` (3,378 forms each) | 3 of 3 |
| holders on a 7-key projection with no function contracts (a hand-built/test world) | 100 |
| holders on carried projections with 2,038 and 2,037 contracts | 466 / 32 |
| holder identity changes over 40 s idle (40 samples at 1 s) | 0 |
| `compiled-wrapper` for `seon.db/q` (retained schema), warm | 0.04–0.09 ms (first 0.22–0.32) |

Every recompile was for equal forms. On a switch, the first call of the new wrapper also pays Malli
validator construction. The research lane measured 1.67–2.75 ms per armed q under a switch, against
0.12 ms. An idle JVM does not thrash. The thrash comes from test worlds and from previous or as-of
values that alternate with the live projection.

**After (structural):** the `supplied-wrapper` holder no longer exists, so a call compiles 0 times.
A scratch copy of the edited namespace (`f2-scratch.instrument`, loaded with `load-string`, then
removed) armed a scratch Var. It accepted `1` and refused `"x"` with the declared contract error. A
rearm with an `assoc`ed (non-identical) projection of equal contract and definitions kept the
`identical?` wrapper. The call cost was 0.67 µs.

## Change

- `src/seon/instrument.clj`: deleted `request-member`, `supplied-projection` and
  `validates-loaded-contract?`, the `supplied-wrapper` atom and its switching block, and
  `arm-var!`'s dead `caps` parameter. The wrapper is now
  `(if *compiling-contract* (apply original args) (profile/timed (apply boot-wrapper args)))`.
  The `apply!` docstring was corrected. 18 lines added, 77 deleted.
- `test/seon/instrument_test.clj`: the `arm-with!` helper (compile once, then install) replaces two
  `arm-var! … projection projection caps` callers. New class regression:
  `one-wrapper-per-adoption-keyed-by-content-never-by-call`. It checks that two equal-content
  projections keep one wrapper, that a call carrying another projection neither re-selects nor
  recompiles, and that adopting changed content rearms and changes validation. 53 added, 10 deleted.
- `test/seon/profile_test.clj` (9 added, 6 deleted) and `test/seon/error/refusal_test.clj` (8 added,
  3 deleted): the same conversion.
- Net: src −59, test +51.

## Root runs (serially)

1. `bin/test-check default --ns seon.instrument-test`
2. `bin/test-check default --ns seon.profile-test`
3. `bin/test-check default --ns seon.error.refusal-test`
4. `bin/test-check default --test seon.fault-test/record-mode-stores-the-whole-fault-and-wakes-the-responsible-agent`.
   Record the elapsed time against the 1,591 ms before. The target is under 1,000 ms. The recording
   itself costs 168–390 ms (6fbfe21cb).
5. After adoption, repeat the probe `seon.db/q` inside `call-with-projection` of an `assoc`ed
   projection. Expect ~0.12 ms per call, not 2.75 ms. Also check the `seon.schema/call-with-projection`
   and `seon.db/with-declarations` profile rows before and after.

## Out of scope finding

`seon.cluster/first-seon-frame` (`src/seon/cluster.clj:356`) refuses a trace whose frame has a nil
file. Frames from `load-string` or eval have no file. The contract error was
`refused trace at [0 2]: expected a string, got nil`. The refusal replaced the real exception in the
MCP reply, so the MCP showed a projection error instead of the cause. I searched `docs/seon/issues`
and found no issue for it. I recorded none, because the issue files are outside this lane's paths.
Root should file it under the error-evidence owner.
