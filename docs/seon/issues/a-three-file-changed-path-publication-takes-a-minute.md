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

## Sighting 2026-09-25 23:50Z (impl-debug-page-ui landing)
`bin/seon init --dev default --changed src/seon/render/web.clj` took 2.4–14 s per iteration; the profile shows
`seon.cluster/refresh-source!` three times plus `full-source-refresh!` — whole-program work for a one-file change.

## Cause 2026-09-26 (perf-boot-opus)
A replay of the a34078273 boot publication (7 changed files, 4 changed schema keys) on a scratch branch took 15.2 s
warm: analysis 1.0 s, reconciliation transaction 3.2 s, then **10.7 s re-linting 241 caller files** — the whole
program. `seon.fn/caller-files` selects the callers of every function whose contract references a changed schema
(57 consumers → 241 files); true signature changes selected 3 files, all already reconciled. Publication lints with
every type-derived linter off, so no contract change can alter a caller's findings. Patch and task `ff74379b6565`
(seon.fn); landing `docs/research/agent-platform/landing/perf-boot-opus-2026-09-26.md`.
