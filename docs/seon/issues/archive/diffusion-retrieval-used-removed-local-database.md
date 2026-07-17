---
type: issue
status: resolved
severity: friction
tags: [issue, database, cljs, capability]
---

# Diffusion retrieval used the removed local database

## Failure

Diffusion retrieval dereferenced the removed `seon.db/*conn*`, treated current
asynchronous database reads as collections, and scanned symbols before issuing
one pull per candidate. Oracle callers consequently treated a Promise as the
completed retrieval result. Embedded Datahike fixtures hid the obsolete seam.

## Resolution

Retrieval now captures one ordinary database value and acquires the compact
function corpus—symbol, argument lists, documentation, and schema—in one
authoritative query. Symbol resolution, candidate ranking, and injection
construction are pure transformations over those facts. The oracle and
semantic enhancement await that mechanism. Fixtures return ordinary query
data through `seon.db` and retain generative proof over the pure transformation.

## Verification

`bin/test-cljs --test=seon.diffusion.retrieval-test
--test=seon.diffusion.oracle-test` passes 16 tests and 122 assertions with no
warnings.
