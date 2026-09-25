---
type: issue
status: open
severity: platform
created: 2026-09-25
tags: [issue, db, turn, performance, agent-platform]
---

# Read evidence digests every captured read result

`seon.turn/evaluate-sources` (src/seon/turn.clj:4762) stores
`(db/read-evidence @captured)` for each evaluated form. `db/read-evidence`
(src/seon/db.clj:900-912) runs `read-result-digest` (db.clj:574) on each read result.
`read-result-digest` walks the whole result with `stable-value` (db.clj:540-566) and then
serializes it with `schema/canonical-data-string` for SHA-256. The cost is proportional to
the size of every read result, and it is paid on every form.

Measured with 100 ms thread samples of one batched turn fixture (lane fix-turn-hang-opus,
default pid 23282): 80 of 366 samples, about 8 s over two call passes of 8 forms, were in
`stable-value` / `canonical-data-string` under `read-evidence` ← `evaluate-sources`.

Cost target: a read's identity is its request plus its basis (Datahike's dependency plan and
revision, db.clj:867). Digest the result only where a replay has to compare it, or bound the
digest to what currency checking reads.
