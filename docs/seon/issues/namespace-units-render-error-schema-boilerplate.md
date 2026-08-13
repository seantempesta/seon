---
type: issue
status: open
severity: friction
tags: [issue, render, context, schema]
---

# Render a capability namespace by its API, not by its Malli source

## Problem

The compact namespace card renders every declared schema as its raw Malli
form. In a capability namespace most of those declarations are the per-failure
`X` marker and `X-error` map pair, which an agent RECEIVES and never
CONSTRUCTS, and each `-error` row carries the render-producer wiring
(`:seon.render/ai`, `:seon.render/html`) that is pure internal plumbing.

Measured on the live default cluster on 2026-08-10, root's exact
`:seon.render/ai` context was 63,669 characters / **15,917 estimated
tokens**, of which:

| Line kind | count | est. tokens | share |
|---|---|---|---|
| `; schema <key> = <malli>` | 201 | 6,857 | 43% |
| …of which `*-error` rows alone | 43 | 3,459 | **22%** |
| `; (register! …)` referenced closure | 68 | 1,586 | 10% |
| `; fn <sym> — <contract> — <doc>` (the API) | 21 | 829 | **5%** |

Five percent of the agent's context is the surface it can call.

The referenced-schema closure is emitted per unit with no cross-unit
deduplication: `(register! :seon.error/value …)` appears 6 times,
`:seon.error/kind` 6 times, `:seon.blob/digest` 4 times, and
`; referenced schemas` opens 7 separate blocks. `seon.render.walk/prose`
deduplicates whole UNITS by logical key (`src/seon/render/walk.clj:551-566`)
but nothing deduplicates lines across units.

The closure also renders `; (register! :seon.schema/value :any)` three
times — teaching a form the same context panics on ninety lines earlier
("`…/largest` uses `:any` in an agent-authored contract").

## Evidence

Producer: `src/seon/render/ns.clj:351-354` (`compact-schema-line`) and
`:363-379` (`referenced-schema-ai-section`).

One representative rendered line, unedited, from root's live context:

```text
; schema :my.fs/not-found-error = [:map {:seon.error/class true, :seon.render/ai seon.error/render-ai, :seon.render/html seon.error/render-html, :error/message "must identify the absent filesystem path"} [:my.fs/not-found :my.fs/not-found] [:seon.error/message :seon.error/message]]
```

`:seon.render/ai seon.error/render-ai` appears **40 times** in one context.
The one fact worth a token in that line — "a not-found error identifies the
absent path" — is already its `:error/message`.

Per-unit cost of the seven toolkit namespaces: `my.fs` 2,843,
`my.web` 2,249, `my.edit` 1,516, `my.shell` 885, `my.background` 801,
`my.message` 745, `my.run` 511 — **9,552 estimated tokens, 60% of root's
context**.

Full measurement:
[context quality audit 2026-08-10](../../prds/sci-execution-runtime/research/context-quality-audit-2026-08-10.md),
finding 4 (with finding 10 for the `:any` contradiction).

## Owner

`seon.render.ns` owns both projections of one namespace representation.

## Acceptance

An error schema renders as its `:error/message` sentence, not its Malli
form. Render-producer and other internal Malli properties never reach the
agent projection. The referenced-schema closure is hoisted to one
walk-level section rendered once per walk rather than once per unit, and no
schema the agent is forbidden to write is shown to it as a registration
example. One recurring measurement pins the toolkit-namespace share of a
rendered context so the ratio cannot silently invert again.

## N1 disposition — 2026-08-12

Partially resolved by `5e449b275`: compact AI and HTML namespace projections
now render an error declaration as its `:error/message` sentence, never its
render-producer property map. Still open because the referenced-schema closure
must be hoisted and deduplicated in protected `src/seon/render/walk.clj`, and a
recurring whole-context share measurement must be added there. Exact Phase-1
edit: accumulate referenced schema identities across selected units, emit one
walk-level section, and exclude declarations the agent cannot author.
