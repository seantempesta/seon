---
type: research
status: current
tags: [research, render, flow, evidence]
---

# F2 live render proof — the pipeline on a real cluster boot (2026-07-28)

The reset-boundary evidence for the F2 render conversion. A fixture cannot
produce it: schema population, config facts, the seeded root agent, the
cluster graph with both its procs, and the routing listener are all boot-path
behaviour, and that is a different failure class than any in-memory fixture
sees.

Harness: `tmp/f2-live-sse.clj` (committed, rerunnable). It runs in its OWN JVM
against its OWN store root (`data/f2live`), so it never opens the live default
cluster's store — two JVMs on one store is the condition that once destroyed
40/40 commits.

## What ran

`seon.cluster/start!` on a fresh cluster, a real http-kit server, three real
browser-shaped SSE sockets over `java.net.http`, and one real `d/transact`.
Counted SSE events, byte comparison between tabs, no sleep-as-proof (a 15 s
loud backstop whose firing would itself be the bug report; it did not fire).

## Result — 2026-07-28, cluster `f2live` on 127.0.0.1:7891

| proof | oracle | observed |
|---|---|---|
| initial paint | every block, at its own id | 6 morphs; `surface-agents`, `surface-header`, `surface-messages`, `surface-problems`, `surface-reply`, `surface-tokens` |
| second tab | paints independently from current facts | 6 morphs |
| commit → morph | ONE derivation reaches the socket as the changed block only | 1 morph, carrying `surface-agents` and naming the new agent |
| two tabs | byte-identical bytes from one shared derivation | identical |
| suppression | untouched blocks stay off the wire | `surface-header` absent from the morph |
| reconnect | repaint from CURRENT facts, nothing replayed | 6 morphs, sees the agent committed after the first tab opened |
| streaming datoms | the attribute family is gone, so no partial row is representable | 0 `:seon.ai.stream/*` attributes installed |

The commit was a bare `{:seon.cluster.agent/id "live-probe"}` — an attribute
`route!` routes on — so the run exercised the third delivery, the proc's pass,
the mult, and both taps in one path.

## What this closes

The F2 §1 pipeline end to end on the boot path: `route!`'s per-report render
wake → the render proc's single pass over one database value → equality
suppression at the proc → `mult` → per-tab sliding-1 taps → the per-tab byte
diff at the socket. The morph granularity the first web slice proved is
preserved while the derivation behind it collapsed from per-tab to per-cluster.

## Incidental finding

The live `default` cluster serves `/` as 500 from its own boot, from duplicate
root block rows carrying both the pre-rename `:seon.block/*` keys and the
current `:seon.render.block/*` keys. Unrelated to this wave and filed as
`docs/seon/issues/root-blocks-carry-two-key-vocabularies-and-500-the-page.md`.
Recorded here because it is the reason a reader comparing the live default
page against this proof will see two different behaviours.
