---
type: issue
status: resolved
severity: friction
tags: [issue, database, flow]
---

# Datahike full aggregation has optional integration drift

## Problem

The complete maintained Datahike aggregation reaches optional integration
surfaces beyond the reactive-read and query-cache contract. Retained full-run
logs name Stratum valid-time assertions, Java bindings, API documentation,
HTTP JSON handling, purge behavior, and query-planner probes, but those logs
are dated 2026-07-16 and predate the maintained reactive/cache revision. They
do not prove that those failures reproduce now.

The two planner probes that can directly invalidate reactive query semantics
were rerun at revision `3af6e46e` in both persistent-set and hitchhiker-tree
profiles. One test initially proved its query assertions but leaked its owned
connection during deletion; the fixture now releases before deleting. Both
planner selectors pass: four tests and twelve assertions in total. The focused
weighted-LRU and query-cache profiles pass 162 tests and 990 assertions, and
the CLJS gate passes 138 tests and 951 assertions.

The remaining named failures were reproduced and repaired through maintained
revision `d664dce7`. Purge index surgery omitted its attribute retractions from
the committed transaction report, which let a cached query inherit a stale
pre-purge value; the purge primitive now records effective retractions and its
existing behavioral test asserts the report. Stratum selected only an
open-ended predecessor for partial valid-time updates, so a contiguous finite
window dropped unchanged attributes; it now selects the latest predecessor
whose valid start does not follow the new row. Java and raw HTTP JSON fixtures
also release their live connections before database deletion. The combined
named gate passes 14 tests and 196 assertions across both maintained index
profiles.

The first unfiltered aggregation then ran 2,599 tests / 13,527 assertions and
reduced the remaining result to 18 errors / 3 failures. Focused reproduction
showed three independent fixture/serialization owners: API documentation and
schema tests reused fixed stores without deterministic release; the HTTP
writer decoded remote transaction-report database values with the local
mapper; and `upsert-history` retained its connection across profile reuse.
Revisions `d45ee18b`, `eedea005`, and `d664dce7` repair those owners. Their
focused PSS/HHT proofs pass 8 tests / 74 assertions, 4 tests / 18 assertions,
and 2 tests / 26 assertions respectively. The planner as-of selector also
passes independently in both profiles (2 tests / 4 assertions), confirming its
aggregate failure was shared-fixture interference.

Two unfiltered reruns then exposed the last shared-store owners instead of
reactive defects: the database-hash fixture retained four reconnects, and the
schema-persistence fixture retained its reconnect while reusing a fixed memory
store. Revisions `c2379bcf` and `d59f76bb` give those tests independent stores
and deterministic release/delete ownership. Their focused PSS/HHT gates pass
2 tests / 20 assertions and 2 tests / 8 assertions.

The final complete maintained aggregation at `d59f76bb` passes 2,599 tests /
13,651 assertions with zero failures or errors across `clj-pss`, `clj-hht`,
and `specs`. This issue is closed.

## Acceptance criteria

- Reproduce each remaining named integration failure with its smallest owning
  runner; do not infer current failure from the dated aggregate logs.
- Separate obsolete fixture or platform assumptions from production defects.
- Repair each owning mechanism without weakening assertions or coupling it to
  reactive cache inheritance.
- Run the complete maintained Datahike aggregation after the focused owners are
  green and record its final test and assertion totals.
