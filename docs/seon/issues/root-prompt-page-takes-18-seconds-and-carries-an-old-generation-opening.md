---
type: issue
status: open
severity: blocker
tags: [issue, render, debug-page, root, performance, class/slow]
---

# Root's `?prompt=true` page takes 18 s, and its opening is from the old generator

Observed 2026-09-09 21:50 on `default` (thirteenth refork, HEAD `505a5113a`):

| request | time |
|---|---|
| `GET /ns/my.agents.root/debug` | 0.51 s |
| `GET /ns/my.agents.root/debug?prompt=true` | 18.2 s (21.3 s first) |
| `GET /ns/my.agents.juniper/debug?prompt=true` | 0.53 s |

The slow part is the prompt comparison, not the stored evaluations (the
slowest `:ms` in root's history is 214 ms). Root's opening was produced at
boot by bootstrap, before the cookbook's generator: its forms print as
`(seon.db/pull (quote […]) …)` — printed data, not agent source — the
help is the old vector, and four blocks fall back to the generic printer
(settings, config, runtime ×2). The new cluster status block
(`501b45570`) is not in it because root was never regenerated.

Fix: (1) profile the `?prompt=true` path for root (thread samples during
the GET) and remove the seam — 18 s for a page is a bug by the standing
order; (2) root's opening regenerates through the current generator
(compaction, or bootstrap using the same generator as the fixture), so it
carries the cluster block and prints as agent source; (3) no generic
printer fallbacks on root's page. Proof: root `?prompt=true` < 1 s warm;
root's opening bytes recorded.
