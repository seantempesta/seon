---
type: issue
status: open
severity: performance
created: 2026-09-23
tags: [issue, publication, performance, seconds-not-minutes]
---

# A three-file changed-path publication takes a minute

Lane `oversight-owning-instance` ran this on a warm scratch root (`git archive`
of `fe624bf22` plus the lane's paths, booted and running, 2026-09-22):

`bin/seon --root <root> init --changed resources/seon/schemas/seon.oversight.edn src/seon/oversight.clj src/seon/render/web.clj`

| publication | wall s | result |
| --- | --- | --- |
| retire: resource removed, both sources at HEAD | 60.38 | commit `6ab2ee8f…`, 25 `:seon.oversight/*` schema rows gone |
| adopt: resource and sources restored | 60.59 | commit `6ab2eed0…`, 25 rows back |

The work that changed is 25 schema rows and two namespaces, but the time is
close to a cold publication. The operator printed no phase lines, so the
interval has not been split up. `issue-indexing-at-publication-costs-13-seconds.md`
covers one part that runs on every changed-path publication. Wanted: phase lines
for capture, compare, lint, index and transaction, and a cost that grows with
the changed declarations, not with the whole program.

Sighting 2026-09-23 (lane render-cache-per-branch): on a scratch root booted from the
working tree, `(seon.cluster/refresh-source! root [src/seon/render.clj
test/seon/render_cache_test.clj] "default")` took 12,206 ms, reloaded only
`seon.render-cache-test` (render.clj was unchanged since boot) and re-armed 3
identities. One new test file, twelve seconds.

## Sighting 2026-09-25 19:31Z (fix-boot-cost-opus landing)
`bin/seon start` ready-ms 35,314 (50.9 s wall): ~31 s was publishing 12 changed files at boot — 19 s in `seon.fn/analyze-rows`
(fn.clj) writing program rows, 12 s more in `populate-source!` (cluster.clj:1643) via `full-source-refresh!` (cluster.clj:1953,
from boot.clj:197). A separate `bin/seon init --dev` adoption of 38 paths took 32 s (20:00Z). Start with no changed source is the
~4–5 s layer cost. Cost follows total analysis of changed files, not the change: the fix belongs in analysis reuse.
