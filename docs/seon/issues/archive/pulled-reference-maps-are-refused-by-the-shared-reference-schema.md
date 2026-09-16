---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, database, render]
---

# Pulled reference maps are refused by the shared reference schema

Default agent `2393cac275ae` cannot display turn `53b6c6a503ad`'s prompt:
`seon.eval/of-agent` refuses `:seon.eval/origin` at return path
`[17 :seon.eval/origin]`, because wildcard pull returns `{:db/id n}` while
`:seon.db/ref` admits only integers, strings and lookup refs. HTTP returns 500;
Datastar discards its body and the session status remains “Loading…”.

Four local schema patches previously covered this class at evaluation run,
evaluation renderer, test run and message reference entries. The origin is
another instance. Datahike's `transaction/explode` explicitly admits a nested
map under a reference attribute, so the shared declaration owns this spelling.

The fix and verification are recorded in
[the landing note](../../../prds/steward-platform/research/pulled-ref-is-a-ref-2026-09-16.md).
The shared declaration now admits the nested-map spelling. All 48 storable
reference-bearing schemas transact and preserve their reference targets;
the existing issue-origin fixture returns under armed contracts. The assigned
prompt was observed through HTTP 200 and Chrome on the unchanged default JVM.
The separate UI commit `eec636a97` renders typed acquisition refusals in full.

The stronger whole-map round-trip assertion exposes a different defect:
17 stored schemas require sets where pull returns vectors. That remains open
in [the collection issue](../wildcard-pulled-collections-do-not-satisfy-entity-set-contracts.md).
This resolution does not claim that the exhaustive whole-map regression is green.
