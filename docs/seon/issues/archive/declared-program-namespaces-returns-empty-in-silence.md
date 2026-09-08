---
type: defect
status: resolved
severity: friction
tags: [testing, instrument, class/absence-as-health]
---

# `declared-program-namespaces` returns `[]` in silence

The test runner's worker loads the program before arming contracts
(`ccfaeb14d`) and refuses when it armed nothing. `verify-p1-p6-and-backlog`
(finding 9) found the input to that check unchecked: when the relative root
does not resolve, `declared-program-namespaces` returns `[]` silently, and
the zero-instrumented refusal cannot see it because the worker's own test
vars keep the count positive. Fix: an unresolvable root is a typed refusal;
the arming assertion compares against the program graph's namespace count,
not against zero.

## Resolved 2026-09-08 (`test-harness`)

Both halves this note names, plus a third absence it did not.

`seon.test.runner/declared-program-namespaces` is now TOTAL. An unresolvable
root is a typed refusal naming the root AND the working directory it resolved
from; a source file whose first form is not an `ns` form is a typed refusal
naming the file (the silent `keep` this note's second paragraph describes); an
unreadable file is a typed refusal; and an empty derivation is a typed refusal
of its own, so the caller can never receive `[]`.

The arming assertion no longer compares against zero. `arm-contracts!`
requires every declared program namespace, then refuses when
`(set/difference (seon.instrument/armable program)
                (seon.instrument/instrumented))`
is non-empty, NAMING the unarmed contracts. `seon.instrument/armable` is new
and derives the expected set with malli's own two rules — `mi/-schema` and the
`clojure.lang.IFn$` primitive exclusion
(`reference-code/malli/src/malli/instrument.clj:16,24`) — so the expected set
and the set `apply!` installs cannot drift, and no list is maintained
anywhere.

Regressions: `seon.test-runner-test/the-armed-program-derivation-refuses-absence-instead-of-answering-empty`
(all four absences, plus a non-vacuity assertion that the REAL root derives a
program containing `seon.artifact`) and
`arming-refuses-when-a-program-contract-carries-no-wrapper`.

Measured, gate vs live cluster: the worker arms **876 of 876** armable program
contracts across all 91 declared namespaces; the live `default` cluster arms
810 of the 820 it has loaded. The gate is now a strict superset. The ten a
cluster misses are filed separately as
[a-live-cluster-arms-ten-fewer-contracts-than-it-declares](a-live-cluster-arms-ten-fewer-contracts-than-it-declares.md).
