---
type: issue
status: open
severity: friction
tags: [issue, sci, schema, wave/schema-admission]
---

# Preserve explicitly declared usage metadata on runtime tests

The core-functions canonical SCI probe on 2026-09-14 found that adding
`:seon.test/usage true` to a runtime test declaration still produced a program
row without that fact. Ordinary test admission is now fixed separately; this
is a metadata-fidelity defect, not a reason to require usage on every test.

`src/seon/sci/eval.clj:390`'s `definition-row` test branch reads Var metadata
but constructs only test symbol, namespace and source. In contrast,
`src/seon/fn.clj`'s `var-row` preserves the explicit usage metadata at static
indexing. The test branch near eval.clj:400 omits that explicit fact.

The runtime declaration owner should preserve explicitly asserted usage and
leave it absent for ordinary tests. One canonical SCI regression should compare
both declarations' program rows and their admitted database facts. This is
outside the follow-up's explicitly authorized referral hunks in eval.clj.
