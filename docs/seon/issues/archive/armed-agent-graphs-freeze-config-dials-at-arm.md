---
type: issue
status: resolved
severity: friction
tags: [issue, config, flow, agent]
---

# Let a live config apply reach an armed agent graph

## Problem

`seon.cluster/loop-handle` reads `config/effective` **once**, when the cluster
arms its agent graphs, and merges the result into the handle the turn proc
carries for its whole life: the provider targets, the retry strategy, the eval
time limit, the admission caps, the core-error dial, and the chain bound.

A `config apply` against a live cluster therefore commits new facts that the
running turns never see. Observed: applying the sanctioned local-Ollama row to
a live cluster, then sending a message — the run opened, called the **boot-time**
DeepSeek endpoint, and closed 90 ms later with
`"The environment variable DEEPSEEK_API_KEY is not set."` while
`config/effective` already reported the Ollama endpoint.

This is stored-derived state in a handle: the config facts are the truth, and
the loop reads a snapshot of them. It also contradicts the neighbouring design
note in `serve!` (`src/seon/cluster.clj:879-881`), which says the render proc
reads its coalesce floor from the config facts **per pass** so a live dial
change applies without restarting a tab — two dial-reading conventions in one
file.

Practical cost today: any lane configuring a provider must pass the manifest at
`start`, and a live `bin/seon config apply` silently does nothing to turns. The
next boot also reconciles defaults + manifest, so a live overlay is overwritten
on restart unless it is named in the start manifest.

## Evidence

- `src/seon/cluster.clj:962-995` — `loop-handle`, `(config/effective @connection
  cluster-name)` read once and merged whole.
- `src/seon/cluster/loop.cljc:870` — the turn reads `(:seon.ai/primary cluster)`
  from that frozen handle.
- `src/seon/cluster.clj:879-881` — the per-pass convention the render proc uses.
- Live, cluster `preflight-mvp` at HEAD `24aaacbac`: `config/apply!` with the
  Ollama manifest returned converged and `config/effective` reported
  `"http://127.0.0.1:11434/v1/chat/completions"`; the very next run
  (`d7f63fd1-9071-4427-9f2c-3f23d2ef2de2`, opened 19:09:35.191) closed at
  19:09:35.281 with `:seon.cluster.run/error "The environment variable
  DEEPSEEK_API_KEY is not set."` and committed a matching
  `:seon.ai/no-credential` fault.
- Restarting with the same manifest at `start!` made the identical message
  drive a real turn against Ollama.
- `docs/prds/sci-execution-runtime/research/turn-loop-preflight-2026-07-31.md`.

## Owner

`src/seon/cluster.clj` (`loop-handle`) with `src/seon/cluster/loop.cljc`.

## Acceptance

A dial changed by `config apply` on a live cluster takes effect on the next
turn without re-arming, by the turn deriving its dials from the database value
it already holds rather than from a handle field — or, if the owner rules that
arm-time freezing is correct, `config apply` refuses loudly for the dials it
cannot deliver instead of reporting success. Either way one convention, stated
once, covering both the render proc and the turn proc.

## Resolution

Resolved by `de7a01483` (`Resolve AI settings once per turn`).
`seon.cluster.loop/turn-step` now reads `config/effective` from the turn's
current database value and resolves one immutable settings value per turn;
`bf2ef7797` records the live proof that a sparse config apply changed the next
call's model without changing the process or graph identity.
