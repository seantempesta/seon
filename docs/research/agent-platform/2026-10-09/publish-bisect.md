---
type: research
status: measured
date: 2026-10-09
lane: publish-bisect
---

# Empty-store publication did not double: the 67.7 s boot was machine contention (publish-bisect, 2026-10-09)

**Verdict: there is no guilty commit.** `platform-step.md` compared one 10-07 reading (36.7 s) with one 10-09 reading
(67.7 s). The operator's own history (`data/clusters/default/logs/operator-check-*.edn`, 1,051 throwaway-JVM boots with
`:seon.boot/ready-ms` and the candidate commit in the message) shows ready-ms is set by machine load, not by commit.
Bisecting would only chase noise.

## Evidence 1: the quiet floor is flat or better

Ready-ms of `check` throwaway JVMs per UTC day (runs above 20 s; every run boots onto an empty store):

| day | runs | p10 | p25 | p50 | p90 |
|---|---|---|---|---|---|
| 09-28 | 13 | 35.1 s | 36.1 s | 38.2 s | 50.0 s |
| 10-06 | 65 | 46.9 s | 51.2 s | 57.1 s | 68.4 s |
| 10-07 | 248 | 36.3 s | 40.4 s | 50.0 s | 75.0 s |
| 10-08 | 549 | 33.1 s | 37.2 s | 41.9 s | 52.7 s |
| 10-09 | 173 | 31.7 s | 33.3 s | 35.4 s | 48.3 s |

10-09 between 00:00 and 06:30 UTC (about 130 boots across about 100 different commits) is flat at 29-45 s, median
about 34 s. The same commit `e50a9299b` booted in 33.8, 33.5, 34.4, 48.3 and 33.1 s in one quarter hour.

## Evidence 2: the jump is contemporaneous with load, not with a commit

From 06:44 UTC on 10-09 ready-ms rises to 51-69 s for every commit alike (`c448e411a` 51 s, `f2c0b15d2` 64 s and
67 s, `e376a6524` 69 s, `769c4a843` 68 s, `86659ca5b` 54 s) while other lanes ran checks and test JVMs at once
(`check: queue position 1 of 1; 2 throwaway JVMs run`; load average 12-32 on this machine).
Commits before 06:30 UTC with the same source (for example `f5eaac1f4`) booted in 34 s.

## Evidence 3: an old commit under today's load is slower than HEAD

Directly at load average 31: `bin/seon check --at a423b8b29 --test seon.cache-test` (a 10-07 commit, 22:35 UTC)
reached ready in **98.0 s** (boot-ms 115.8 s, prepare 33.8 s, total 177.8 s). The old commit that booted in about
36 s on a quiet machine takes 98 s here, so the 36.7 s versus 67.7 s pair compares a quiet reading with a contended
one.

## What stays true

- Whole-program publication onto an empty store is still the dominant boot cost (about 25-30 s of the quiet 33 s;
  `platform-step.md`'s 61.2 s is the same work stretched by CPU contention, since it is CPU-bound on several threads).
  Its remedy is the per-base template store in `docs/seon/schedule/cards/PLATFORM-STEP.md`, not a bisect.
- Every timing quoted for `check` must say the load average (or slot occupancy) it ran at; an "uncontended" label
  needs a quiet-machine reading (the p10 above is that floor, about 32 s).
- No task filed: the cost is already owned by the PLATFORM-STEP card, and no commit regressed it.
