---
type: issue
status: open
severity: friction
tags: [issue, agent]
---

# Model can ghost-echo runtime scaffolding into the transcript spine

## Problem

A model can echo message, masthead, transcript-box, or readline scaffolding in
its narration. If a transcript renderer attributes that text structurally, it
can look like a genuine inbound event despite having no supporting database
fact.

## Evidence

The transcript-faithfulness audit found this on 2026-07-06 in the `tx-audit`
cluster, turn `dtU-2607062046`. DeepSeek reproduced a masthead, a
`;;; ◀ from user … (NEW — unanswered)`-shaped line, and a
`┌─ transcript ─` box in its narration.

The batch reply path formerly rewrote result glyphs before blob capture and
parsing, while other structural runtime markers were not rewritten. The
agent-runtime-correctness reply-preservation slice removed that sanitizer:
one exact provider string now reaches both the content-addressed blob and
parser, and only parsed forms create eval facts. Model-authored scaffolding
remains byte-identical evidence and does not acquire runtime-event authority.

This is narration-channel hardening, not a transcript-spine defect. The spine
passed the four faithfulness invariants in
[[research/transcript-faithfulness-audit-2026-07-06]].

## Owner

The raw reply, ordered parser/eval transition, and database-derived transcript
rendering owned by `seon.agent.turn`, `seon.repl.internal`, and the context
composer. This belongs to the agent-runtime-correctness reply-preservation
slice, not a reserved-glyph sanitizer.

## Acceptance

- Preserve the exact provider reply in its blob and parse it once.
- A reply containing forged result, message, masthead, box, or readline text
  renders unambiguously as model narration and creates no runtime event or
  eval-result fact.
- Genuine runtime events and results derive and render only from their committed
  database facts.
- Remove the result-claim rewrite rather than extending the reserved-glyph set.

Context: [[feels-stateful-remaining-work-spec]] Unit 3.

## 2026-07-15 checkpoint

Deterministic parser and turn-capture regressions cover forged result text:
the blob hash addresses the exact UTF-8 reply, the debug round trip is
byte-identical, and only the two real forms create eval/result rows. The
remaining open work is rendering arbitrary message, masthead, box, and
readline-shaped narration unambiguously without rewriting it.
