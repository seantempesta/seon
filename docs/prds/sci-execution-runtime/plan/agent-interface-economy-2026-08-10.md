---
type: prd
status: active
tags: [prd, render, agent, runtime]
---

# The agent interface — owner rulings and plan (2026-08-10)

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

## Plan skeleton (sequencing only; content governed by the rulings)

- **Now (running):** the clean full-suite gate; the model-authoring
  drive observer. Tree frozen until the gate lands.
- **Phase 1 — prep (only what the target needs):** make the printer
  total (the unqualified-keys refusal at `render-argument`,
  `src/seon/render.clj:106-107`, is the found seam; delete the
  renderer-unavailable substitute face; elision never longer than its
  value) and stand up the recurring printer STRESS HARNESS (generated
  values from every registered schema + real database values; every
  bad face is a grammar fix). Finish the declaration-resolution noise
  at its last owners. Measure warm-walk cost at unchanged basis (both
  projections) and make reuse real. Root-cause the suite fat tail
  (the ~185 s operator tests, the await-process! wedge).
- **Phase 2 — the transcript walk:** design session with the owner on
  the unit grammar (see open questions), then build: walk renders the
  ordered run/form/result facts as form → printed-value units; delete
  the separate transcript assembly, the schema walls, the comment
  headers; deep doc + bulk faces tool; the HTML page is the same
  session rendered with the page profile. Storage verified 2026-08-10:
  form sources/ordinals and settled results are already facts — this
  is render-side unification plus deletions.
- **Phase 3 — prove by drives:** a fresh agent bootstraps on the
  transcript context; re-drive model authoring on the minimum context
  vs today's measured baseline; browser re-walk; then the multi-agent
  preview discussion on proven ground.
- **Phase 4 — the bad-output catalog** per the owner directive,
  trimmed to the classes that survive Phases 1–2 structurally.

## OPEN QUESTIONS (nothing here is decided)

1. The unit grammar for the design session: how does an inbound HUMAN
   message appear in the session (as what form/value)? How does model
   prose appear (comment grammar is the current convention — does it
   survive the "no commented weird summaries" rule as INPUT prose
   while outputs stay pure data)?
2. Which forms open a fresh agent's session (the current bootstrap
   forms re-read as the transcript, plus which injected requires)?
3. What "lightly explained API" looks like concretely (names +
   arglists + one-liners is the working guess — NOT ruled).
4. The minimum-context target size: found by experiment (ruled
   "find the minimum"), no number is ruled.
5. How much of the bad-output catalog survives after Phases 1–2; which
   observation facts are actually worth committing (census Option 1
   was a lane's recommendation, not an owner ruling).
6. Schema rendering's replacement face (owner: current form "is
   fucking stupid" — the replacement is a design-session item, not
   pre-decided here).
