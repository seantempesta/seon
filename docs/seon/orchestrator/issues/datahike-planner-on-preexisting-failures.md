---
type: issue
status: active
tags: [issue, database]
---

# Datahike fork: 2 pre-existing failures when the planner is globally ON

Found 2026-07-02 during the collect-field fix verification (base-vs-fix
comparison proved both exist at `e6d196d5`, before the fix — not regressions).

## Symptom

`DATAHIKE_QUERY_PLANNER=true clojure -M:test -m kaocha.runner clj-pss` in
`reference-code/datahike`:

- FAIL `datahike.test.attribute_refs.query-rules-test/test-rules` — "Rule
  context is isolated from outer context" returns `#{}` under the planner.
- 1 ERROR whose test name wasn't captured in the run output — reproduce with
  the command above.

Default suite (planner off + planner tests self-binding) is green
(626/2750 after the fix), and CLJS node-test is green — so the pod's exposure
is limited to whatever CLJS query shapes hit these paths.

## Why it matters

The pod ALWAYS runs the planner (`*force-legacy*` defaults false on CLJS), so
planner-on JVM failures are candidate silent-wrong-results bugs on the pod —
same class as the collect-field bug
([[datahike-query-clause-order-empty-results]]), which also only surfaced
live.

## Next

Reproduce both under `DATAHIKE_QUERY_PLANNER=true`, root-cause in the fork's
planner (rule/context handling), fix on a branch off `sync-upstream`, push.

## Status

Open — queued (tooling lane).
