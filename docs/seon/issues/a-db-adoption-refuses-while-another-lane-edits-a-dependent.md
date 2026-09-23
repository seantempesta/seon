---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, publication, adoption, hot-path, seconds-not-minutes]
---

# Adopting `seon.db` refuses while any dependent file differs from its published bytes

Lane m4-write-bound, 2026-09-23, `bin/seon init --dev default --changed
src/seon/db.clj test/seon/db_test.clj` on pid 90963:

| attempt | wall | refusal |
|---|---|---|
| 1 | 56.0 s | "The source head changed before publication." (`seon.cluster.source/publish!`, `:stale-branch-head` on `:current-src`; concurrent lanes' test-check/init); profile: `seon.cluster/full-source-refresh!` x4 73.0 s, `seon.fn/analyze-rows` 47.8 s |
| 2 | 24.8 s | same |
| 3 | 65.6 s | "Source changed during development adoption." |
| 4, 5 | 9 s, 8 s | same, `:seon.source/changed-paths ["src/seon/render.clj"]` |
| 6 | under 1 s | succeeded once `render.clj` matched its published bytes |

`verify-development-sources!` (`src/seon/cluster.clj:2296-2313`) compares every
RELOADED namespace's disk bytes with its published bytes. `seon.db`'s dependents
include most of the program, so one uncommitted edit by another lane to any
dependent (here nsa-unblock's `render.clj`) refuses the adoption of a file it
does not touch. Under one-file-one-lane, a `seon.db` lane cannot adopt while any
dependent is held. Reloading those dependents would also load the other lane's
uncommitted bytes into default. Separately, a refused attempt still spent
25–66 s in a whole-program source refresh before refusing.

## Sighting, lane cut-1.3f-41, 2026-09-23

`bin/seon init --dev default --changed src/seon/schema/edn.clj`, pid 90963:
attempt 1 took 69.7 s and attempt 2 took 180.6 s. Both were refused with
"The source head changed before publication." (`:current-src`); the profile
shows `seon.cluster/refresh-source!` x6 at 297.8 s inclusive and
`seon.db/with-declarations` x164,780. Each refused attempt still paid a
whole-program source refresh.
