---
type: issue
status: open
severity: defect
created: 2026-09-26
tags: [issue, db, read-refusal, keep-the-whole-cause]
---

# A read handed a refusal restamps its operation

`seon.db`'s read owners accept a read refusal in the database position and pass it on, but each
overwrites `:seon.db/refused-read-operation` with its own name: `pull-call`
(`src/seon/db.clj:2569-2570`, `(assoc database :seon.db/invalid-read true :seon.db/refused-read-operation public-operation)`),
and the same pattern in `q` (`:2417`) and `db` (`:2226`). A `seon.db/q` refusal that reaches
`seon.turn/current-run` comes back naming `seon.db/pull`; every other member is equal. The refusal is
no longer the one the failing read produced ("keep the whole cause").

## Evidence

`seon.turn-test/an-open-turn-read-refusal-refuses-the-writer-verbatim` (turn_test.clj:1036, root run
abab00602fe6, `tmp/orchestrator/verify-0415.out.d/008.txt`): expected the `q` refusal verbatim; actual is the
same map with `:seon.db/refused-read-operation seon.db/pull`.

## Proposed fix (src/seon/db.clj, root grant needed)

Stamp only a value that is not already a read refusal, at each of the three sites:

```clojure
(if (:seon.db/invalid-read database)
  database
  (assoc database :seon.db/invalid-read true :seon.db/refused-read-operation public-operation))
```

Regression: the turn-test member above, unchanged.
