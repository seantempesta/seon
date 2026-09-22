---
type: issue
status: open
severity: high
created: 2026-09-23
tags: [issue, publication, contracts, reload]
---

# Incremental adoption refused owned-value plans under a stale armed contract

**Evidence (2026-09-22, lane reload-per-declaration).** On a live scratch JVM armed at
`da703089b`, `bin/seon --root tmp/reload-b-root init --dev head --changed …` publishing
`da703089b..af5eea72f` (it includes `6bf3bde78` in `src/seon/db.clj`) failed after
228,300 ms: "The cluster population transaction was refused: seon.db/write-owned-values-error
refused attribute-plans at [893]: expected a keyword, got an integer 893"
(`tmp/reload-per-declaration-evidence/adopt-c-to-d.log`). Datahike's `-schema` maps
each attribute's entity id to its ident (1,207 integer keys on that database; 893 is
`:seon.render.value/error-window`), and HEAD's contract already admits
`[:map-of [:or :keyword :int] :map]` (`src/seon/db.clj:3119-3122`). Unverified
explanation: the contract armed before the edit checked the changed function during
the publication that carries the change. The retry exceeded the 300 s prepl bound
(`adopt-c-to-d-2.log`, 300,047 ms, outcome unknown).

**Question for the owner of arming at adoption.** A publication that changes a
function and its contract runs its own transactions under the previously armed
wrapper. Either arm from the rows being published before their writes run, or run
the population write under the loaded code's own contract.
