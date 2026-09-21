---
type: issue
status: resolved
severity: friction
tags: [issue, render, testing, wave/render-producers]
---

# `form-is-the-third-output-of-the-existing-selection-chain` asserts shapes three rulings replaced

## Problem

`seon.render.history-test/form-is-the-third-output-of-the-existing-selection-chain`
(`test/seon/render/history_test.clj:28`) is 3 / 9 / 0. Nine assertions pin the
render selection chain to message, agent and namespace shapes that later
rulings moved. The test has been red since `ae0e54841` (2026-09-09) and
surfaced only in batch 83, because that is the first gate list to name
`seon.render.history-test`.

It is NOT caused by the opening-subject repair (`9107232d7`, `58bd7f4f3`).
Those commits touch `test/seon/render/history_test.clj` only at lines 135-156
and 220-228; this deftest's body (lines 28-103) is byte-identical to
`484e05bdb`, and `seon.bootstrap` is not on the path from
`seon.render/producer` to a pulled message.

## Evidence

In process on `default` pid 88182, canonical fixture, armed,
`seon.render.history-test` reloaded through `seon.test`'s own loader: 3 / 9 / 0.

Four of the nine follow a ruling that is already in the tree:

1. `(my.message/read "history-message")` vs
   `(my.message/read #:my.message{:id "history-message"})`.
   `seon.render.transcript/message-form` (`src/seon/render/transcript.clj:790`)
   has passed a namespaced map since `105acca21` "Use request maps for my APIs"
   — AGENTS.md §3, one namespaced map in/out for API-like functions. The
   expectation is the stale side.
2. `(my.message/inbox)` vs the reverse-edge pull. `inbox-form`
   (`:796`) reads `:seon.message/_inbox` since `ae0e54841`, whose own
   docstring names it "The recipient's pending inbox edge". Two assertions.
3. `(selected message :seon.message/to)` expected
   `seon.render.transcript/inbox-form`, got `seon.render/render-form`. **The
   message did not lose its declared pair.** `inbox-form` is declared on
   `:seon.message/inbox` (`resources/seon/schemas/seon.message.edn:151`), the
   attribute that now carries the recipient edge; `:seon.message/to`
   (`:73`) has no form pair, so the generic pull is the declared answer. The
   test's fixture also seeds `:seon.message/to`'s schema row and not
   `:seon.message/inbox`'s.

Three more have a declaration to point at but no dated ruling:

4. An agent entity pulled `'[*]` selects `seon.render/render-form`, not
   `seon.cluster.agent/situation-form`. `situation-form` is declared on the
   derived `:seon.agent/situation` MAP (`resources/seon/schemas/seon.agent.edn:126`),
   not on the agent's attribute map, and the fixture's filtered canonical-row
   set contains no agent schema key at all. Two assertions.
5. The attribute floor for `:seon.ns/requires` expected a `db/q` listing and
   got `seon.render.ns/namespace-form` with
   `(seon.db/pull '[*] [:seon.ns/name fixture.history])`. Two assertions.
   Whether an attribute-scoped request may fall back to the entity's pair is
   the open question here.

## Resolution (2026-09-16)

The owner ruled decision 9 option 1 on 2026-09-17: **no render fallback**. An
attribute-scoped render request that finds no declared pair for that attribute
resolves to the generic printer; it never borrows the owning entity's or a
neighbour's declared form, so every uncurated attribute is visibly generic and
therefore findable by the render-pair curation task.

`src/seon/render.clj` now asks `attribute-scoped?` instead of guessing from the
value's keys: an attribute-scoped request resolves to that attribute's declared
pair or falls through the schema stage to the floor. The neighbour case the old
`attribute-value?` guard protected is preserved at its authority —
`seon.render.walk/scoped-attribute` stamps `:seon.render.walk/attribute` on a
render request only for a member the walk synthesized FOR an attribute, never
for a neighbour entity it merely reached THROUGH one.

The four drift assertions (1)-(3) were updated to the declarations already in
the tree, the fixture now seeds `:seon.message/inbox` and `:seon.agent/agent`,
and (4) and (5) assert the ruled behaviour. A new regression,
`seon.render.history-test/a-neighbour-the-walk-reached-renders-by-its-own-shape`,
pins the neighbour case. In process on `default`: 14/0/0 and 4/0/0.

One observation was filed rather than absorbed:
[a-generic-attribute-scoped-render-answers-with-the-owning-entity](a-generic-attribute-scoped-render-answers-with-the-owning-entity.md).

## Owned by

The render selection owner. Not repaired by the opening-subject lane, which
named the namespace in its gate request and found the red.
