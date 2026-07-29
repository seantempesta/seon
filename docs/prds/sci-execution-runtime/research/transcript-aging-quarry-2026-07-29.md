---
type: research
status: active
tags: [prd, agent, context, transcript, planning]
---

# Transcript aging and `my.plan` quarry

## Scope and dependency ledger

This is quarry evidence for the next design session, not an implementation
plan. It distinguishes three things that are easy to conflate:

1. the measured four-resolution transcript prototype;
2. the later mechanism that actually shipped in State A and now survives under
   `src-old/`; and
3. the fresh architecture and the owner's 2026-07-29 rulings that decide which
   lessons may survive.

The mechanism depended only on first-party transcript facts and rendering:
stored `:seon.agent.turn/*`, `:seon.eval/*`, and
`:seon.agent.message/*` facts; `seon.ai.tokens/estimate`; the one recursive
render path; and database-derived context blocks. There is no external library
semantic to import. Relevant source lineage is commit `9eb137228` (measured
prototype), `b563280ca` (first active age-band eviction), `4d31c47f6`
(three-level result decay), `5cfc0127e` (bounded total transcript), and
`b2d701ccd` (bounded newest-turn acquisition).

## Executive finding

The “pretty cool” work was real, but it has two layers. The measured prototype
assigned detail by **event age**—6 full events, then 10 light, then 16
pointer-only, then a permanent one-line summary head—and proved that the
summary prefix grew append-only and stayed byte-identical
(`docs/prds/archive/agent-fsm/research/tiered_transcript_proto.cljs:49-98`,
`docs/prds/archive/agent-fsm/research/transcript-dynamic-cache-aware-2026-06-28.md:28-38`).
That four-resolution gradient remained a prototype, explicitly outside every
classpath and test runner
(`docs/prds/archive/agent-fsm/research/tiered_transcript_proto.cljs:1-7`);
State A instead shipped **turn-aged result-body caps plus omission**, never a
stored or model-written summary.

## The measured prototype: four detail levels, not shipped

The prototype's tier was a pure function of the count of newer events. Its
recommended schedule was:

| Event age | Detail | Exact projection |
|---:|---|---|
| 0–5 | `:full` | the then-current full eval or message render |
| 6–15 | `:light` | narration ≤240 chars, source ≤300, value ≤400, error ≤200; retained `result/<id>` |
| 16–31 | `:pointer` | narration ≤140, source ≤160, result replaced by a type/size hint plus `result/<id>`; error ≤120 |
| 32+ | `:summary` | one line: first source line ≤60 plus result shape or failure; no result handle |

The exact projections are in the runnable research artifact
(`docs/prds/archive/agent-fsm/research/tiered_transcript_proto.cljs:54-80`);
the age calculation and fallback to `:summary` are
`tiered_transcript_proto.cljs:84-98`. Messages stayed full through the pointer
bands and clipped to 90 characters only in the summary band
(`tiered_transcript_proto.cljs:55-58`).

On the real root transcript (146 events, 21,843 estimated tokens), the
recommended schedule rendered 3,718 tokens: 1,673 tokens across 114 frozen
summary events and 2,045 tokens in the volatile 32-event tail. The research
proved the 140-event summary prefix was an exact prefix of the 143-event
version, which was an exact prefix of the 146-event version
(`docs/prds/archive/agent-fsm/research/transcript-dynamic-cache-aware-2026-06-28.md:97-106`,
`:166-205`). The important insight was not merely clipping: once an event
entered the terminal summary band its bytes never changed again, so the oldest
prefix only grew.

This design was explicitly display-only. Raw eval/message facts remained
unchanged, detail was recomputed during render, and re-reading a
`result/<id>` would create a new recent eval at full detail
(`transcript-dynamic-cache-aware-2026-06-28.md:232-234`,
`:272-279`). It proposed splitting frozen history into a cached block and
recent transcript into a volatile block (`:108-128`, `:236-263`), but that
split did not become the surviving runtime.

## State A as it actually worked

### Result detail decayed at turn offsets

The shipped transcript block carried three database-configurable decay levels:

| Offset from newest retained turn | Result-body cap |
|---:|---:|
| 0–1 | 4,096 estimated tokens |
| 2–4 | 1,024 estimated tokens |
| 5+ | 512 estimated tokens |

Those are the surviving defaults in
`src-old/seon/agent/ctx/transcript.cljc:60-70`. Selection took the level with
the largest `from-turn-offset` not exceeding the eval's offset
(`src-old/seon/agent/ctx/transcript.cljc:118-134`). During each render, the
renderer found the newest retained turn, calculated
`newest-turn-index - eval-turn-index`, and injected the selected cap into the
ordinary eval renderer (`src-old/seon/agent/ctx/transcript.cljc:1357-1392`).
Nothing changed in the stored eval; only its rendered result-body detail did.

The first active schedule, commit `4d31c47f6`, was 16,384 at offsets 0–1,
1,500 at offsets 2–4, and 200 at offset 5+. Commit `5cfc0127e` revised that to
4,096 / 1,024 / 512 because 200 tokens was too thin for identity and useful
diagnostics; the current source's `git blame` attributes the threshold change
to that commit. The detail transition happened only when a newer **turn**
crossed offsets 2 or 5, so an aged row was byte-identical between boundaries.

### Old history was omitted, not summarized

The surviving acquisition reads at most the newest 50 turns by a reverse
`:seon.agent.turn/at` index scan
(`src-old/seon/agent/ctx/transcript.cljc:969-1061`,
`:1121-1155`). It then reads only messages at or after the oldest retained
turn, except that the message opening the current run remains included even
when older (`src-old/seon/agent/ctx/transcript.cljc:1063-1096`,
`:1189-1201`). Older history is represented by one honest omission notice;
its events are not rewritten into a compacted story
(`src-old/seon/agent/ctx/transcript.cljc:1405-1415`).

Within the retained window, the newest 25 turns form an uncharged append-only
chunk. Events in the older retained chunk share an 8,192-token budget, charged
newest-first against each event's **complete rendered text**—source, narration,
result, and error—and an event that does not fit is omitted whole
(`src-old/seon/agent/ctx/transcript.cljc:75-80`, `:249-275`,
`:1393-1404`). Thus the actual expiry triggers were:

- a turn becoming 50+ turns older than the newest retained turn, at which point
  acquisition stopped reading it;
- an event in the settled 25-turn chunk failing the shared 8,192-token budget,
  at which point that whole rendered event disappeared; and
- a result crossing turn offsets 2 or 5, which reduced only its result-body
  detail rather than expiring the event.

The earlier `::tiers`, `clip-events-by-tiers`,
`clip-events-by-turn-window`, and `turn-window-cutoff` functions remain in the
quarry, but the live formatter no longer calls them. `rg` finds their only
callers in their own definitions and old tests; the render path uses bounded
acquisition plus `clip-rendered-events-by-settled-budget`. This matters: the
comments about a 50/25 “rotation” describe the July 14 predecessor, while the
final State A path enforces the same practical bound at acquisition.

### What triggered recomputation

There was no compaction job, timer, summarizer, or expiry transaction. A prompt
render against a database value re-acquired the bounded event window and
derived every cap and omission from that value plus the block's policy datoms
(`src-old/seon/agent/ctx/transcript.cljc:1121-1266`,
`:1270-1275`). Changing the block policy changed the next render; adding a
turn changed offsets; neither action stamped an event with a tier or deleted
its underlying facts.

## What was genuinely good

The prototype and the shipped mechanism shared four sound ideas:

- **Keep raw history and derive presentation detail.** Neither mechanism
  rewrote an eval or message when it aged. The same fact could be rendered
  compactly in routine context and recovered at full fidelity through an
  explicit read.
- **Use discrete transitions, not a clock.** Detail changed only after a new
  event or turn crossed a count boundary. Between transitions, old rendered
  bytes stayed identical, which is the property a prefix cache actually needs.
- **Spend detail where current work is happening.** Full recent results
  preserved working identity and diagnostics; old results retained less body
  detail before whole old events were eventually omitted.
- **Bound prompt work and say when information was omitted.** State A's
  newest-50 acquisition, newest-25 active band, and 8,192-token settled budget
  made transcript cost mechanically bounded rather than trusting prose
  discipline.

The measured prototype added one particularly valuable observation: a terminal
detail band can make an old prefix append-only. Its exact-prefix measurements
showed that this can improve cache reuse by construction, not by claiming that
old context is “stable.” The shipped design chose a different stability trade:
results changed at only two turn boundaries, and the oldest material simply
left the window.

## What the fresh rulings supersede

The old thresholds are quarry evidence, not fresh-system defaults. Nothing
measured that 6/10/16 events or 4,096/1,024/512 tokens are optimal for the
current models, and the prototype's character caps are not interchangeable
with State A's estimated-token caps.

More importantly, the old terminal `:summary` projection conflicts with the
fresh raw-history rule. The current architecture says retained transcript
events are never rewritten into summaries and that an omitted tail is marked
honestly; durable intent belongs in database facts rather than transcript
residue (`docs/seon/architecture/context.md:479-486`). The reusable lesson is
age-varying **projection**, stable bytes, and bounded expiry—not the specific
synthetic one-line summary.

The dynamic-block composition is also superseded. The owner's late-morning
ruling says context is mostly transcript, while blocks survive only as static
scaffold: the system message, REPL instructions, and `AGENTS.md` loading
(`docs/prds/sci-execution-runtime/plan/README.md:692-698`). Context components
render in parallel and are ordered by observed change time, stable material
first and churn last, with transcript last (`plan/README.md:699-705`). That
ruling preserves the prototype's cache objective but rejects a hand-built
forest of dynamic per-agent blocks.

There is a documentation seam to resolve next session. The earlier architecture
still describes semantic bands plus a Bayesian rendered-byte change estimator,
frozen ordering epochs, and hysteresis
(`docs/seon/architecture/context.md:440-466`), while the newer owner ruling
names parallel rendering and change-timestamp ordering. This quarry does not
silently choose an algorithm between them; it records the newer ruling as the
design constraint and leaves the exact ordering statistic for settlement.

## `my.plan` as it actually worked

### It was never redesigned for the fresh system

The answer to the owner's question is **no**. The active plan says explicitly
that "`my.plan` was NEVER ported" and that the v0 planner works through plain
messages (`docs/prds/sci-execution-runtime/plan/README.md:688-691`). Commit
`f25e34594` only moved the old namespace into the quarry during the fresh-tree
split; it did not redesign it. The surviving State A implementation is 3,987
source lines across `src-old/my/plan.cljc` and
`src-old/my/plan/internal.cljc`, plus a 1,701-line old test namespace.

Its meaningful redesign happened **inside State A**. Commit `51a8cab8b`
mechanically renamed `seon.agent.todo` to `my.plan`, then commit `1cda29489`
added the dependency-aware graph, position anchor, and bounded render. That is
valuable historical work, but it predates the fresh claim-native,
message-driven architecture.

### The data and public surface

A plan was a per-agent database graph, explicitly “never transcript or
todo-list state” (`src-old/my/plan.cljc:1-7`). Each step stored an identity,
title, one of four statuses (`:open`, `:active`, `:done`, `:blocked`), creation
and optional completion times, agent scope, a parent tree edge, cardinality-many
dependency edges, and optional root goal, expected outcome, and pace. Later
generated-code work added originating agent/message, namespace, and claim
fields (`src-old/my/plan.cljc:24-43`).

The authoring shape supported a nested tree, symbolic labels, and `:after`
dependencies (`src-old/my/plan.cljc:95-124`). `plan!` created the entire tree
once and refused a same-title duplicate; `reconcile!` accepted an edited open
document, diffed it by identity, protected done history, and committed the
whole delta in one transaction (`src-old/my/plan.cljc:907-920`,
`:1571-1589`). Smaller mutation functions added a step, selected active work,
marked done/blocked/open, added dependencies, moved or dropped subtrees, and
read the next queue, current position, tree, document, status, or open list.

The graph semantics were substantially better than a checklist:

- readiness was derived for an open, unblocked leaf, or for a drained parent
  whose final action was verification and closure;
- an unmet dependency with open work derived blockage;
- an active step won the position anchor, otherwise the oldest ready leaf did;
  and
- done/total progress was a derived leaf roll-up.

Those rules are explicit in `src-old/my/plan/internal.cljc:24-46` and their
ordinary-row equivalents in `:98-185`. One active position per agent was
enforced by demoting any other active step in the same transaction
(`src-old/my/plan.cljc:1325-1370`). `done!` told the agent to verify `:expect`,
then stored `:done` and `:completed-at`; the function itself did not run or
validate the expected condition (`src-old/my/plan.cljc:1389-1417`).

### The bounded projection

The plan prompt was intentionally not the whole graph. It rendered one
position anchor, up to 7 ready steps plus an overflow count, and the 5 most
recently completed steps; the completed interior stayed queryable but left the
prompt (`src-old/my/plan/internal.cljc:1246-1269`). Absolute datom-derived
timestamps kept an unchanged line byte-identical
(`src-old/my/plan/internal.cljc:1658-1667`). A colocated render value also had
AI and HTML twins (`src-old/my/plan.cljc:165-190`).

This bounded frontier is the best `my.plan` lesson: current position, immediate
ready work, and a small anti-redo tail are more useful than replaying an entire
plan. It also matches the transcript work's broader principle—retain durable
truth, but vary how much of it enters the current model call.

### The parts that must not be ported

The surface violated the fresh conversion test in several ways:

- Agents performed runtime state transitions effectfully from inside eval
  through `active!`, `done!`, `blocked!`, `reopen!`, `reconcile!`, and related
  database-writing functions.
- The stored status machine mixed authored intent (`:open`) with derived or
  runtime facts (`:active`, `:blocked`, `:done`) even though fresh completion is
  supposed to come from an observable test or schema'd return.
- A post-turn `maybe-consult!` path inspected repeated eval failures, selected
  another agent, sent a planner message, and used a marker embedded in message
  prose to derive once-per-episode delivery
  (`src-old/my/plan/internal.cljc:1036-1048`, `:1183-1238`).
- Generated-code planning grew specialized root, namespace, claim,
  publication, terminal-transition, and repair behavior inside the generic
  planning namespace. Even ordinary transitions needed special guards because
  eval evidence, not `done!`, owned those generated roots
  (`src-old/my/plan.cljc:1372-1417`).
- The plan block taught an imperative workflow and generated-code operating
  manual dynamically in every relevant prompt
  (`src-old/my/plan/internal.cljc:1701-1785`). Under the new ruling, dynamic
  work belongs mostly in transcript; blocks are static scaffold only.

These were locally thoughtful patches to State A failure modes. Together,
however, they demonstrate why the mechanism should not be relocated: the
planning toolkit had become a second run loop, state machine, delegation
policy, renderer, and generated-code coordinator.

## Honest map onto messages-set-up-planning

The fresh v0 already establishes the starting point: a human messages root,
root messages an ordinary planner, failures become routed problem messages,
and completion derives only when every form is settled
(`docs/prds/sci-execution-runtime/plan/generate-code-v0-plan-2026-07-29.md:83-99`).
The surface is explicitly a message, not a new lifecycle-like function
(`generate-code-v0-plan-2026-07-29.md:103-113`). Therefore “messages set up the
planning system” can honestly retain the following:

| State A shape | Keep | Message-native mapping |
|---|---|---|
| root `goal`, `pace` | the durable statement of why work exists and whether it spans episodes | the initiating message carries the goal and arbitrary context; goal-seeking mode supplies the call/episode contract |
| per-step `expect` | a falsifiable outcome | a routed problem or planner-produced pure value names the observable test/schema condition; the driver derives settlement from receipts and test facts |
| parent and `needs` graph | only if independent work actually requires machine-readable dependency order | the planner may return a pure schema'd plan value; the driver interprets it, while no agent mutates readiness |
| derived ready frontier and roll-up | the projection principle | derive current unsettled problems from messages, receipts, namespace ownership, and tests; do not store a second progress projection |
| bounded anchor/frontier/done tail | the context-economy lesson | render a bounded view of the planning conversation inside the aged transcript; expose fuller durable facts through explicit reads or a human surface |
| `reconcile!` whole-document edit | the ability to revise intent coherently | a new planner message supersedes or amends the earlier proposal, with ordinary message identity/provenance; whether this also commits normalized plan facts remains open |
| `active!` / `done!` / `blocked!` / `reopen!` | none of the effectful surface | a run is active because it is claimed; success/failure/interruption are receipts and observable facts; the loop interprets a pure disposition or schema'd return |
| `maybe-consult!` | routing a genuinely stuck problem to an appropriate agent | send an ordinary addressed problem message through the one message mechanism; idempotency comes from message/receipt identity, never a prose marker or post-turn hook |
| generated-code special arms | none | namespace attribution, routed problems, ownership, and settlement stay in their existing generic message/form/receipt owners |

This is a mapping of lessons, not a decision to recreate a durable plan graph.
The least-assumptive v0 is messages plus existing receipts/test facts. A
normalized plan value or durable dependency facts should be added only if an
observed multi-episode case cannot derive its next work and completion from
those truths.

## Open design questions for the next session

1. **What exactly does the initiating message establish?** Settle the
   goal-seeking mode's input schema, arbitrary context, turn/use budget, expected
   return schema, and observable completion query. Do not infer mode from
   message prose or missing provenance.
2. **Is a durable plan graph needed at all?** First test plain messages against
   restart, multi-session work, dependency ordering, and re-planning. If some
   information must outlive the transcript window, name the irreducible facts;
   do not restore `my.plan` wholesale.
3. **If a planner returns a plan value, who interprets it?** Define the minimal
   pure schema, whether dependencies are necessary, and which driver transition
   commits any durable facts. Agent code must not call lifecycle or database
   mutations from inside eval.
4. **How is completion total?** Reconcile schema'd return, all-tests-pass,
   owner-fixed, owner-declared-can't-fix, interruption, and silent-owner cases.
   The generate-code v0 already requires silent work to remain unsettled; no
   message receipt may masquerade as goal completion.
5. **How does revision supersede prior intent?** Decide whether a later planner
   message references and replaces an earlier proposal, appends a delta, or
   produces a new attempt. Use ordinary identities and provenance, not title
   matching or prose markers.
6. **What context view replaces the dynamic plan block?** Settle how a bounded
   current goal/frontier appears inside a mostly-transcript prompt, what fuller
   view remains available to humans, and whether any non-static plan block is
   still permitted under the newest ruling.
7. **Which aging policy survives evaluation?** Compare count-based detail
   bands, result-body decay plus omission, and a truly terminal frozen
   projection. The fresh no-summary rule currently excludes the prototype's
   synthetic summary head; changing that requires an explicit ruling and
   model/cache evidence.
8. **What is the exact cache ordering algorithm?** Preserve parallel render,
   stable-first/churn-last, and transcript-last. Reconcile the newer
   change-timestamp ruling with the older Bayesian/hysteresis architecture and
   measure actual provider cache-read reuse.
9. **How are errors and re-planning batched?** Root should receive all queued
   errors in one wake, and routed problems already carry arbitrary planner
   context. Determine the single message/flow derivation that replaces
   `maybe-consult!` without installing a second scheduler.

## Requested compact verdict

The measured aging mechanism rendered the newest 6 events in full, the next 10
lightly clipped, the next 16 as pointers, and everything older as a stable
one-line summary, without changing raw facts. State A did not ship that
four-level scheme: it capped eval result bodies at 4,096/1,024/512 estimated
tokens at turn offsets 0–1/2–4/5+, acquired the newest 50 turns, left the newest
25 uncharged, and fit older retained events whole into an 8,192-token budget.
Nothing summarized or expired on a timer; a new render derived changed detail
at count boundaries and omitted facts only from the prompt.

`my.plan` was never redesigned for the fresh system. Keep its goal/expect,
dependency, derived-frontier, and bounded-projection lessons, but do not port
its agent-called status mutations, consultation hook, dynamic plan manual, or
generated-code coordinator. Start with messages establishing goal-seeking work
and derive completion from receipts/tests; add a pure plan value or normalized
durable plan facts only if a measured multi-episode case proves messages alone
insufficient.
