---
type: issue
status: open
severity: friction
tags: [issue, render, performance, class/n9, wave/render-context-cache]
---

# Give AI context rendering the retained-bytes render path

## Problem

The `:seon.render/ai` projection uses the same namespace-owned renderer
selection as HTML, but prompt construction performs a fresh uncached walk. Two
identical context renders at one database value execute every renderer twice.
Across turns, the prompt is also unsuitable for provider prefix caching: its
first line embeds the changing database basis and its final line embeds both
that basis and its transaction instant.

## Evidence

- A context probe rendered an agent whose walk reached data owned by another
  namespace. Both calls produced identical context bytes and the same renderer
  sequence, but each call invoked all 12 renderers again.
- Removing the reached namespace's renderer caused its declared schema
  producer/floor to serve the value; the referring namespace's renderer never
  ran. Ownership is shared correctly, while retained-byte economics are not.
- `src/seon/cluster/prompt.clj:1-8,40-69` explicitly describes and performs one
  fresh, deliberately uncached `seon.render/walk` for every prompt.
- `src/seon/render/walk.clj:595-625` emits `basis=<max-tx>` in the first context
  line. `src/seon/render.clj:293-307,386-391` emits the basis and
  `:db/txInstant` in the appended REPL-state line. Stable substantive context
  therefore moves behind a volatile prefix on each later turn.
- `src/seon/cluster/loop.clj:1291-1334` captures that exact string before the
  provider call and hands it to `:seon.ai/prompt`, so the volatility is in the
  provider input rather than only a diagnostic projection.
- DeepSeek's 2026-08-03 context-caching contract requires complete persisted
  prefix units to match; Kimi's current guidance likewise says changing the
  initial prefix reduces automatic cache hits. Verified sources are in
  `docs/prds/sci-execution-runtime/research/llm-provider-research-2026-08-03.md`.

## Owner

The shared render-proc retained-fragment boundary and
`seon.cluster.prompt/prompt`.

## Acceptance

Two context renders at an unchanged database value return identical retained
bytes without invoking any renderer a second time. A one-block fact change
invokes and serializes only that block while preserving the namespace-owned
selection and ambiguity rules used by HTML. Consecutive semantically similar
turns keep stable blocks before volatile basis/time metadata; an exact-capture
comparison measures the retained common prefix, and provider usage confirms
DeepSeek/Kimi cache-hit tokens rather than inferring a hit from local equality.
