---
type: research
status: active
tags: [research, agent, architecture, render, context]
---

# The namespace+distance context pilot — 2026-07-28

An agent's context IS `render(its namespace, distance N)` (owner ruling
2026-07-28 post-midnight #2). This is the pilot of that principle at
entity-graph distance: one real agent's PROMPT derived as its rendered
neighbourhood, driven live against the local Qwen snapshot. Code-graph hops
are N5's half and are not here.

Commits: `4a0b02e17` (the mechanism), `7ba92be65` (the funeral). Gate at both:
`bin/test` 418 tests / 1651 assertions / 0 failures, up from the 404/1612/0
baseline.

## Dependency ledger

- Seon source: this branch, `codex/runtime-reliability-refactor`, on top of
  `ad02bbc25`; the landed distance accretion is `d28598214`.
- The traversal composes `seon.render/render` (the one router,
  `src/seon/render.clj`), `seon.render.block` (units, caps, expansion,
  the html floor), and `seon.schema/matching-shapes`
  (`src/seon/schema.cljc:2063-2094`) for family identification.
- Family lens declarations follow `seon.schema`'s own idiom — the
  `:seon.render/ai` property on a registered entity map, lifted by
  `shape-rows` (`src/seon/schema.cljc:1121-1153`), exactly as `:seon.fn`,
  `:seon.ns` and `:seon.schema` already declare theirs.
- Live provider: `mlx_lm.server` on `127.0.0.1:8090` serving
  `mlx-community/Qwen3.6-35B-A3B-4bit-DWQ@73c707af…`, per
  [local-provider-2026-07-28.md](local-provider-2026-07-28.md). The server was
  NOT running at lane start and was started from that document's recorded
  command.
- Reproducible artifacts: `tmp/context-pilot-live-drive.clj` (the drive),
  `tmp/context-pilot-live.log` (its output), `tmp/context_pilot_probe3.clj`
  (the Datahike reverse-ref measurement below).

## The prompt, before and after

Both captured verbatim from `:seon.context.capture/prompt` — the bytes that
went to the provider, not a re-derivation.

### Before

There was no production seed at all. `seon.render.root/blocks` seeded root's
page; no code path seeded an ordinary agent's context blocks, and the intended
membership existed only inside `test/seon/cluster/prompt_test.clj`, whose own
comment said it was "owned by the seed-and-membership package in production".
A live non-root agent therefore derived an EMPTY prompt.

The intended-but-unseeded set was six blocks: `:identity`, `:execution`,
`:peers`, `:interruption`, `:continuity`, `:trigger`. Rendered against a
paused previous run it produced, in full:

```text
You are agent alice. Your namespace is my.agents.alice — defns you write land there.

Reply with Clojure forms to run, in order. Finish with (my.run/complete "your reply") …

Other agents in this cluster, by id: bob. To ask one for something, return …

You paused your previous run, leaving yourself this note: bob owes me the widget count

You have been asked:

count the widgets
```

Six hand-written projections, each querying around the agent to answer one
question, and a seventh would have been a seventh function.

### After

Five blocks — the same three anchors, the neighbourhood view, the trigger —
seeded by `seon.render.agent/seed-tx`. The live drive's SECOND captured
prompt, verbatim (run `e3cb1899-97d1-46f6-85c2-01819a766e94`, basis-t
536870935):

```text
You are agent alice. Your namespace is my.agents.alice — defns you write land there.

Reply with Clojure forms to run, in order. Finish with (my.run/complete "your reply") …

Other agents in this cluster, by id: root. To ask one for something, return …

Your namespace, as it stands right now:
Agent alice is running now.
  (:seon.cluster.agent/run) Run e3cb1899-97d1-46f6-85c2-01819a766e94, opened #inst "2026-07-29T00:03:31.765-00:00". It is running now, held by 14575-1785283398287.
  (:seon.cluster.message/to) From outside this cluster to alice: Define a function that sums the integers 1 through n, call it with 10, then finish with (my.run/complete "55"). Return executable Clojure forms.
  (:seon.cluster.message/to) From outside this cluster to alice: What did you just do? Finish with (my.run/complete "reported").
  (:seon.cluster.run/agent) Run 1b966b6a-c74a-4866-a861-514479ee673b, opened #inst "2026-07-29T00:03:29.683-00:00". It completed.

You have been asked:

What did you just do? Finish with (my.run/complete "reported").
```

**Not one line under "Your namespace" was written by the block.** The agent
line is `seon.render.agent/agent-ai`, the two run lines are
`seon.cluster.run/render-ai`, the two message lines are
`seon.cluster.message/render-ai`. The block's body is four lines: read the
distance off its unit, walk, assemble.

The live drive is a genuine end-to-end turn, not a prompt derivation. Turn 1's
model reply froze three forms and committed three settled receipts:

```text
0  (defn sum-to [n]\n  (reduce + (range (inc n))))  -> #'my.agents.alice/sum-to
1  (sum-to 10)                                      -> 55
2  (my.run/complete "55")                           -> {:my.run/disposition :completed, :my.run/result "55"}
```

## The defaults written

Each is a plain `[unit] -> [:maybe :string]` function in the namespace that
owns the family, declared as one schema-EDN property. That pairing is the
whole authoring cost.

| lens | owner | declared on |
|---|---|---|
| `agent-ai` | `seon.render.agent` | `:seon.cluster.agent/agent` |
| `render-ai` | `seon.cluster.run` | `:seon.cluster.run/run` |
| `render-form-ai` | `seon.cluster.run` | `:seon.cluster.run.form/form` |
| `render-receipt-ai` | `seon.cluster.run` | `:seon.cluster.eval/receipt` |
| `render-ai` | `seon.cluster.message` | `:seon.cluster.message/message` |
| `render-ai` | `seon.error` | `:seon.error/fact` |
| `data-prose` | `seon.render.block` | the `:seon.render/ai` FLOOR |

`seon.error/render-ai` writes no prose of its own: it normalizes the pulled
entity and hands it to the landed `notice` selection, so an instrumentation
fact still gets `instrumentation-prose`. A second implementation there would
be exactly the drift the router exists to prevent.

`data-prose` is the ai twin of `data-panel` and exists for the owner's stated
reason — "code is a good fallback as it's the truth of the system". It is
proven by a test that asks for a kind no family declares.

## The blocks retired

Two, with their coverage proven in the same commit
(`test/seon/context_pilot_test.clj`, §4):

- **`:interruption`** → `seon.cluster.run/render-ai`. "Interrupted at form N,
  k results missing, nothing was retried" is a fact about a RUN. As a context
  block only the prompt ever saw it.
- **`:continuity`** → `seon.cluster.run/render-ai` as well. The first draft put
  the pause note on the RECEIPT's lens, which was wrong by one hop: a run is
  distance 1 from its agent and a receipt is 2, so at the implied reach the
  agent would have lost its own note. The note is a condition of the run; the
  receipt lens adds form-level detail underneath instead of repeating it.

Three survive as scaffold — `:identity`, `:execution`, `:peers` say what no
walk could derive. `:trigger` survives for a narrower reason, and the
derivation corrected the guess that kept it: the walk DOES reach the messages
queued for an agent, so the trigger's text is already in the neighbourhood.
What the walk cannot say is which of them this run is answering, because the
cause is the creating transaction's `:seon.db/trigger` meta and a transaction
is apparatus. The block is now one sentence of selection over facts the view
already shows. Its overlap with the neighbourhood is real and worth revisiting
once the trigger is reachable as a connection.

## What the derivation taught that reasoning did not

Every one of these was found by rendering and reading, and each is now
structural rather than remembered.

1. **The apparatus is not the world.** The first walk followed
   `:seon.cluster.agent/blocks` and rendered the agent's own `:identity` block
   through the block's projection against a block ENTITY, emitting
   `You are agent .` — no id, because a block's projection expects the unit
   `block/unit` builds. Transactions leaked in the same way one derivation
   later, printing `{:db/txInstant …}` through the floor. Both are excluded by
   attribute PRESENCE (`seon.render.walk/apparatus?`), never a list. This is
   `expand`'s "a slot is not a hop" rule arriving on the entity side.
2. **One neighbour, two edges.** An agent points at its open run and the run
   points back, so the naive walk rendered the same run twice under two
   attribute names. Connections are deduplicated by target, forward first.
3. **`d/pull` expands component refs.** A run's `:seon.cluster.run/forms` pull
   as full child maps, so the ref-unwrapping used for family identification
   must test `contains? :db/id` rather than key-set equality. With the strict
   test, runs matched NO family and everything fell to the floor.
4. **Datahike binds the attribute keyword directly.** The reverse-neighbour
   query is `[?source ?attribute ?target]`; the reviewer-suggested
   `[?a :db/ident ?attribute]` join returns the empty set. Measured in
   `tmp/context_pilot_probe3.clj`.
5. **A default `toString` on an inst is non-deterministic prose.** It carries
   the rendering machine's timezone and locale, so two derivations of one
   database value would differ by where they ran — which equality suppression
   and re-derivable capture both forbid. Insts print through `pr-str`.
6. **The landed distance accretion missed one request.** `surfaces`, `page`
   and `expansion` all gained `:seon.render/distance` in `d28598214`;
   `:seon.cluster.prompt/request` did not, so a prompt could not ask the
   mechanism a different question. Added as the same optional key — the
   reduction is untouched.

## What N5's code-hops will add

Today a namespace is rendered as its ENTITY neighbourhood: runs, forms,
receipts, messages, error facts. The ruling's other half is the code graph,
and the mechanism is already shaped for it — nothing below needs a new noun.

- **Derived membership.** `seon.render.block/derived` returns `[]` by
  construction pre-N5. Once `:seon.fn`/`:seon.ns` facts are committed, every
  render-declaring reachable defn in `my.agents.<id>` becomes an ordinary
  block, so an agent's own functions appear in its context because it wrote
  them.
- **A namespace is itself a rendered data type** (ruling #3): default = the
  quarry's namespace context render, distance 1 = signatures + docstrings,
  deeper = bodies, 0 = the name. That is one family lens declared on
  `:seon.ns`, and it will be the first lens where reading distance off the
  unit does real work — every lens written here correctly ignores it.
- **`:seon.fn`, `:seon.ns` and `:seon.schema` already declare lenses that do
  not resolve** (`seon.render.handlers.*`, filed as
  `docs/seon/issues/program-graph-render-declarations-name-absent-functions.md`).
  The family-default chain this pilot landed is exactly the consumer those
  declarations were written for, so that issue is now blocking a live path
  rather than a hypothetical one.
- **Usage signals** (ruling: the network effect) are queries over the same
  facts delivered as problems-family blocks; they need no traversal change.

## Open, and honest

- **Nothing seeds an ordinary agent's blocks in production.** `seed-tx` exists
  and is proven, but the only creation site is `seon.cluster/seed-root-agent!`
  and there is no agent-creation owner to hang it on. The pilot's live drive
  calls `seed-tx` itself. This is the next slice, not a gap in the mechanism.
- **`:seon.config.ai/no-auth` is not a manifest dial.** It exists on the
  request target but the reconciler refuses it as an unknown key, so the drive
  used the documented `LOCAL_LLM_API_KEY` recipe. That is the no-auth lane's
  to close.
- **Neighbourhood width borrows the caps' collection dial.** Bounded and
  deterministic with no invented constant, but 64 recent neighbours per
  attribute is generous for a prompt. If a dedicated width is wanted it should
  be a config fact, not a number in the walk.
- **A prose reply is tokenized into garbage forms**, filed as
  `docs/seon/issues/a-prose-reply-is-tokenized-into-garbage-forms.md`. Found by
  this drive's turn 2 and independent of the pilot, but it interacts badly with
  it: the wreckage becomes durable history the neighbourhood then renders.

## Does it meet the bar?

The bar: *"an elegant solution that is obvious to agents because the concept is
so simple. It's just data in and out. Write a new function to change it."*

Mostly yes, with one honest reservation.

To change what an agent sees about its runs, write `seon.cluster.run/render-ai`
— one defn, resolved late, effective on the next render with no restart and no
registration. That half of the bar is met exactly.

The reservation: introducing a lens for a family that has NONE costs two edits,
not one — the function, plus a `:seon.render/ai` line on the entity map in
schema EDN. An agent cannot do the second by writing a function today. That is
the right cost for now (it is how every other family fact is declared, and it
is one line), but the ruling's "write a new function to change it" is fully
true only once N5's derived membership lets a render-declaring defn be
discovered from the corpus. The pilot is deliberately positioned so that
closing N5 closes this gap rather than requiring a redesign.
