---
type: prd
status: draft
tags: [prd, agent, context, render]
---

# The context pipeline, idealized — from zero, universal in every namespace

*Owner + orchestrator, 2026-08-17 night. This supersedes the
reader-inference ladder (the "producers-of-key" tier is DELETED — the
owner's objection was correct: nothing needs to produce data the pull
already holds). Acquisition is mechanical; curation lives in
rendering; verbs belong to agents.*

```mermaid
flowchart TD
    DB[("one immutable database value<br/>(snapshot at turn start)")]

    subgraph MEMBER["1 · MEMBERSHIP — the agent's three sets, grouped by attribute"]
        direction TB
        OWN["own attributes + values<br/>on the agent/namespace entity"]
        IN["INCOMING refs, grouped by attribute<br/>(messages :to me = ONE render target)"]
        OUT["OUTGOING refs, grouped by attribute<br/>(my session, my run, my requires)"]
    end

    FORM["2 · FORM — mechanical, zero inference<br/>own → identity pull ·<br/>incoming group → reverse-ref query ·<br/>outgoing group → forward pull<br/>(always constructible, identical in every namespace)"]

    EXEC["3 · EXECUTE all target queries in parallel<br/>(independent — one db value, no interdependencies)"]

    subgraph FACES["4 · RENDER each result — least specific to most specific"]
        direction TB
        F1["schema-metadata global default face"]
        F2["a REQUIRED namespace's face fn for this schema"]
        F3["the agent's OWN namespace face fn"]
        F4["explicit render keys ON the data — always wins"]
        F1 --> F2 --> F3 --> F4
    end

    PARSE["5 · PARSE forms + results<br/>(edamame/kondo: every symbol and<br/>namespaced keyword referenced)"]

    EXPLAIN["6 · EXPLAIN — for each first-seen reference,<br/>generate its introduction:<br/>require → dir (names) → schema doc"]

    ORDER["7 · ORDER — explanations before referents ·<br/>pull-tree groups · alphabetical ties ·<br/>live material by basis, newest nearest the turn"]

    EVAL["8 · EVAL the ordered episode through the one loop<br/>→ receipts (frozen batch, ascending settlements)<br/>→ the transcript IS the context"]

    AI(["/ai — join in order → prompt"])
    HTML(["/html — same blocks → namespace view"])

    DB --> MEMBER --> FORM --> EXEC --> FACES --> PARSE --> EXPLAIN --> ORDER --> EVAL
    EVAL --> AI
    EVAL --> HTML
```

## The story in six sentences

The pull gives the agent three sets — what it is, what points at it,
what it points to — and each ATTRIBUTE GROUP is one render target (all
messages-to-me are one unit, not one per message). The form for every
target is mechanical — identity pull, reverse query, forward pull —
so acquisition never infers anything and is identical in every
namespace. All target queries run in parallel against one snapshot
(no interdependencies), and each result renders through the four-level
face resolution: global schema default, then a required namespace's
face, then the agent's own face, then explicit keys on the data —
most specific wins, ties are loud. Parsing the forms and results
yields every symbol and keyword referenced; each first-seen reference
generates its introduction (require, dir, schema doc), and ordering
places every explanation before its first use. Evaluating the ordered
episode through the ordinary loop produces the receipts, and the
transcript IS the context — the same blocks feeding the prompt and
the namespace view. Functions like `my.message/inbox` are VERBS the
agent discovers via `dir` and calls itself — receipted, replayed,
never part of generation.

## The face contract, confirmed against real data (2026-08-17 probe)

A live pull of root's inbound edge returns the group as a VECTOR OF
COMPLETE MESSAGE MAPS under the reverse-attribute key
(`:seon.cluster.message/_to [...]`). So a verb like `inbox` IS a
face: contract `[:vector <message>] → :seon.render/ai` — an
input-shape match against exactly the pulled group, resolved at level
2 (required namespace). The face eats what the pull caught; nothing
re-queries. Two pull-shape rules the real data demands: (1) the
GROUP'S NAME is the reverse attribute — ruling 43 renaming makes the
whole chain legible; (2) EVERY REF LEAF CARRIES ITS IDENTITY
ATTRIBUTE — a bare `{:db/id N}` in a pulled value is a defect (the
production selector already asks identities; ad-hoc subselectors must
too).

## Turn N and message arrival

Replay all receipts by (basis-t, ordinal) — byte-identical prefix.
A new inbound message makes exactly one target stale (the messages
attribute group); the system re-runs THAT query and appends, and when
prior state matters it composes the diff EXPLICITLY over printed
bases — `(diff <query at t₁> <query>)` — a form the agent could have
typed itself, since every result prints its basis. Batteries-included
means NOW; time travel is always spelled in the form.

## What this dissolves and what remains

DISSOLVED (from the mechanics register): producers-of-key inference,
the shape index (M6), the reader-faces predicate (M7), colocation
weighting (M8), auto-run-vs-offered (M9) — acquisition has no readers
to select. The register rows that REMAIN are acquisition- and
render-real: M1 (edge windows must keep the NEWEST members — proven
oldest-first today), M2 (per-edge expansion policy), M11
(render-time transactions), M12 (failed-target retry only on input
change), M13 (the diff face), plus rename/naming (43) so reverse-ref
queries read cleanly. Face-resolution ambiguity is scoped by
construction: levels are strict, and a tie WITHIN a level is a loud
error fixed by removing one claimant.
