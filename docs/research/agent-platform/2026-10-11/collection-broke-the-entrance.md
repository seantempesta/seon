---
type: research
title: A collection broke default's REPL entrance
date: 2026-10-11
---

# A collection broke default's REPL entrance (2026-10-11 ~10:30 local)

Every MCP eval_clj on default (pid 99159, start 2026-10-10T17:44:27Z, HEAD c1230efc3), JVM or SCI, any agent, even `(+ 1 2)`, answered at layer `seon.mcp/call`:

`Node not found in storage.` {:type :node-not-found :address #uuid "6aca7710-2cb8-4203-ad7a-adc66a6fa514"} (error signature a7fb2b8d8659…; occurrences a2d664783738, ffd13942a1a0, d7d774c8b189).

`bin/seon status` still answered (health state :unknown, basis t 536871956, commit 6acb034c-f5b5-5c4f-8c0b-e8dda222bf94). The last collection (receipt 5a6dfdb924d5) completed 2026-10-11T03:02:29Z: 138,626 objects swept, 638.9 MB reclaimed, 62,407 objects / 164.7 MB after. The missing node's id (6aca7710…) is from the previous evening, so a value held in memory since then (not the store's head) referenced nodes the collection reclaimed: a held database value on the entrance path, against the law that a database value is never held in a world but read from its connection at the call.

Recovery: root restarted default (`bin/seon start --head`). Root cause: lane collection-holds.
