---
type: prd
status: active
tags: [prd, render, agent, runtime]
---

# The agent interface — owner rulings and plan (2026-08-10)

> **CURRENT AUTHORITY (2026-08-12):** This document preserves the 2026-08-10
> inputs. The design is now ruled complete through rulings 1–50 in
> [the self-generating-context PRD](self-generating-context-prd-2026-08-11.md#owner-rulings-1-50),
> and the executable contract is
> [the evolving-session implementation PRD](evolving-session-implementation-2026-08-12.md).
> Its generated gap closure supersedes the bootstrap, injection, fixed-preview,
> and unresolved-option shapes below.

This document contains ONLY what the owner ruled in the 2026-08-10
session, each marked verbatim-grounded, plus a plan skeleton and an
explicit list of open questions. Anything the owner has not ruled is
in the OPEN QUESTIONS section, not silently assumed.

## What the owner ruled (grounded in the owner's own words)

1. **One mechanism everywhere.** "If we can make the transcript just
   part of the walk that's even better. One mechanism that works
   everywhere." The initial context, the live conversation, and the
   historical transcript are one walk over the same facts. The
   transcript must work for ALL agents under the same rules; bootstrap
   plus additional standing forms is an acceptable combination.
   **SUPERSEDED (2026-08-12):** The “bootstrap plus additional standing
   forms” clause is superseded by ruling 24: no bootstrap plan exists;
   generation with empty history replaces it.
2. **Context is a REPL session that teaches.** The initial context is
   the bootstrap rendered as the walk: it looks to the agent like it
   has already issued ~10–20 forms — requires, docs, queries — with
   their actual results, "and all the functions are super intuitive."
   Teach the agent; no magic. "In a repl you don't get back commented
   weird summaries" — results are DATA printed by the value printer,
   never prose summaries or comment-framed output.
3. **Both renderers are the P in REPL, just different targets.** One
   walk, one printer; the AI text target and the HTML hiccup target
   are two projections with different spends ("same walk, two
   profiles" — owner-selected).
4. **The value printer is the workhorse and must get better
   generically.** "Our value printer should pick up more slack and we
   should improve it so it does more correctly without directly being
   customized for every value." Stress-test it so overrides are needed
   "only for specific situations but make it so this will work
   everywhere too." No hacks: "Don't truncate things to 900 characters
   just so it looks good on one test."
5. **Find the MINIMUM context — assume nothing.** "We need find the
   minimum context using NORMAL value function printing functionality…
   DO NOT ASSUME we have to display everything in the walk." Question
   everything currently rendered: "How are we rendering schemas? is it
   fucking stupid? We need a better solution then." The API can be
   "lightly explained" rather than every function in full; agents pull
   detail on demand.
6. **Smart bulk pull tools.** "We need smart tools to make it easy to
   get an array of docs for multiple functions, schemas, tests,
   namespaces, etc." Deep `doc` on schema keys (deeper definition,
   generated examples, and/or functions that take or return that data)
   was proposed by the owner earlier the same day.
7. **No per-namespace obligations; injection is acceptable.** "I don't
   want to require every namespace to have specific
   namespaces/functions" — the system may INJECT forms into the REPL
   session (not part of the indexed code). Injected-but-honest session
   forms are the accepted mechanism.
   **SUPERSEDED (2026-08-12):** ruling 32 replaces injection with generated
   gap closure from `(pull, retained history)`; no injection mechanism,
   counter, or standing-form roster survives.
8. **Agent-authored renderers appear on the UI automatically.**
   Owner requirement, proven live the same day: a model-authored
   `:seon.render/ai` producer was selected by contract query with zero
   wiring (driver account; observer confirmation pending).
9. **Cheap by construction, hard gate.** Unchanged basis ⇒ no
   re-render, BOTH projections ("It should be cheap to walk and render
   everything if it hasn't changed" — owner-selected as a hard gate).
10. **Track all potentially bad outputs, unified and queryable**
    (owner directive), with the floor automatically using the value
    printer — a substitute face like "renderer unavailable" is a
    defect, not a tracking category.
11. **Long waits are bugs. Root causes everywhere. Holistic, not
    whack-a-mole.** (Standing orders from this session, applied to all
    of the below.)
12. **Fast by default, everywhere.** "No 3s+ page loads or thousands
    of wasted redundant queries. We have one really well designed
    system and we optimize it and if it ever gets long we investigate
    and assume it's a bug." A slow surface is never a tuning backlog
    item — it is a defect with a cause, investigated on sight. The
    open namespace-page cost (~3 s, 3,859 pulls/21,560 datom reads per
    walk, measured 2026-08-10) is governed by this ruling.
13. **Great defaults, always** (restated for this design, owner
    directive): every surface — fresh agent, namespace owner, debug
    overlay, system view — behaves GREAT with zero configuration;
    overrides are one obvious fact or argument, never a tuning
    exercise. An independent critic reviews the PRD against this
    standard before the owner sees it.

## Superseded plan and questions

The plan skeleton and six open questions from this 2026-08-10 record are
closed or superseded by rulings 1–50. There are zero open design choices.
Ruling 24 replaces authored bootstrap forms with a generated opening; ruling
32 replaces injection with gap closure; rulings 37 and 39 settle the
demonstration and root preview; ruling 49 retires `my.plan` unbuilt; and ruling
50 makes effect replay receipt-backed and old by default. Current ordering
lives only in [the program plan](README.md), while exact source phases live in
[the implementation PRD](evolving-session-implementation-2026-08-12.md#phase-ownership-and-dependency-order).
