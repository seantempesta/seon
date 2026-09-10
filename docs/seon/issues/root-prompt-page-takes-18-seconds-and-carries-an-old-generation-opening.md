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

## Compaction on `default` at 21:58

`POST compact` 0.26 s; `POST system-turn` **13.9 s**; `?prompt=true`
afterwards **20.1 s**. The regenerated opening is the current
generator's (no printed-data forms, the cluster block present, 11
evaluations) and fallbacks dropped to two. So the cost is in generating
root's opening itself, most likely the cluster status derivation or the
per-agent accounting (store footprint scan, attempt/blob accounting) —
one of root's read forms takes ~14 s to evaluate, and the prompt page
re-runs the comparison against it.

