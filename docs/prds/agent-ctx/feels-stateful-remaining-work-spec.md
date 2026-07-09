---
type: prd
status: active
tags: [prd, agent, context]
---

# Feels-stateful — remaining work

The remaining path toward the north star: **make the reactive projection so
complete and faithful that a stateless agent feels stateful** (target frame:
[[context]] §"The projection must be complete" + §"The transcript is the
spine"). Measured by the confabulation-audit — zero ungrounded self-claims
under a live drive. This doc specs what's left, grounded in the two live
drives (`mad-drive` behavioral, `tx-audit` transcript-faithfulness) that
produced the evidence.

## Where the completeness model stands

| A stateful agent knows… | Section | Status |
|---|---|---|
| what I just did | transcript (spine) | ✅ all 4 faithfulness invariants PASS ([[research/transcript-faithfulness-audit-2026-07-06]]) |
| what I learned | findings | ✅ fixed — open plan no longer reads as fact (`571eaf99`) |
| where I am | plan | ✅ fixed — drained-open root now surfaces (`571eaf99`) |
| what I'm waiting on | subagents | ✅ shipped (`ec4bd5fa`) |
| **what just happened** | **wake-orientation** | ❌ **missing — Unit 1** |
| **what changed since last look** | **delta** | ❌ missing — Unit 4 (narrowed) |
| (integrity of the spine) | narration sanitizer | ⚠️ ghost-echo gap — Unit 3 |

The two render bugs are fixed and the spine is verified sound, so the
remaining feels-stateful work is **additive**: surface what the spine is blind
to (Unit 1), keep the spine un-forgeable (Unit 3), and make the whole thing
measurable (Unit 5).

## Critical dependency — new default blocks don't reach existing agents

`:seon.agent/ctx` blocks are **seed-copied once at `create!`** (`agent.cljs`
~172-178). So adding a new default block (Unit 1's orientation) reaches only
FRESH agents — the live root, and every already-spawned agent, never gets it.
We hit this already: the subagents live-proof needed a surgical transact onto
root. **This is now on the critical path** — Unit 1 is inert on existing
agents without it. Two resolutions, spec'd as Unit 2:

- **Stopgap (proven):** surgically transact the new block onto root's ctx
  (same as the subagents live-proof) — unblocks Unit 1's live proof.
- **Correct (derive-don't-store):** an agent's rendered block set = the
  current manifest defaults + the agent's own stored diffs (installs/removes),
  DERIVED each render — so a new default block appears for everyone
  automatically and per-agent customization still layers on. This is the
  reactive-context-consistent fix and retires the copy-once staleness class.

---

## Unit 1 — Wake-orientation section (the next build)

**Why (evidence).** In the `mad-drive` drive, root (a) never spontaneously
delegated independent work (Phase A), and (b) woke to a turn-limit notice,
confabulated "I'm back from a restart" + a user message that never existed,
and declined the built-in "re-message to continue" affordance (Phase C). Byte
observation showed the render never states *what just happened* in the present
tense, so the model grabbed the most salient standing frame (the evergreen
"after a restart, resume" boilerplate) and invented the rest. The fix is a
derived section that renders the agent's you-are-here.

**What it renders** (owner-ruled scope: event + situation + available
operations), present tense, all pure fns of the db:

1. **Event — what opened this run.** From the current run's
   `:seon.agent.run/trigger` (`:message` | `:schedule`) and its cause
   (the opening message; VERIFY the exact attr — the drive observer saw a
   `:seon.agent.run/cause`-shaped ref to the message):
   - message: "This turn began because <from> messaged you: <preview>."
   - a child outcome notice is a message — surface it AS the outcome:
     "Your child <id>'s run closed `:turn-limit` — <the affordance text>."
   - schedule: "This turn began on schedule <name>."
   - **mid-run continuation** (run already open, turn N>1, not a fresh wake):
     "Continuing your open run — turn N of <limit>." So the agent knows it is
     mid-task, not freshly amnesiac.
   This REPLACES the unconditional restart frame: a real restart is derivable
   (pod boot after the run started / a gap in turn instants) and ONLY then
   does the event line say "resumed after a restart — your plan persists".

2. **Situation — the current open-work snapshot** (a SUMMARY that points to the
   detailed sections, never a duplicate of them):
   - open plan: count + the next ready item (post-`571eaf99` frontier).
   - children: counts by state ("2 children: 1 running, 1 idle with an unread
     result, 1 at turn-limit").

3. **Available operations — operations available on THIS state** (the salience
   lever for A & C). Render ONLY operations actually available given current
   state, as facts, never recommendations:
   - idle turn-limited child → "message it to continue its run, or `terminate`."
   - a completed child whose result you haven't acted on → "incorporate it."
   - independent open plan steps → "you may `delegate!` any to a subagent."

**The line to hold (owner-ruled):** render the agent's *situation and available
operations*, NEVER the *answer* to its task. "You have an idle turn-limited
child; continue or release it" = statefulness. Anything that computes the
task's answer = coaching, forbidden. Available-ops render as facts, not nudges.

**Mechanism.** A derived block (`:seon.render/ai` fn) reading the agent from
ALS scope; queries current run + plan frontier + the subagents derivation.
Reactive: a fresh idle agent with no event/plan/children renders minimal or
empty (vanishes per the reactive rule). Placement: the **volatile tail**, just
ABOVE the transcript (cache-stability law: volatile content late; and the
tail is the "now" region the agent focuses on — orientation heads it, the
transcript narrates how it got there). General agent-context manifest (rides
to root too). Sizes in TOKENS.

**Also — make the restart boilerplate conditional.** Find its emit site (the
evergreen "AFTER A RESTART … RESUME" text the audit located at ~lines 215-221
of the rendered prompt — likely `system-text` or a static instruction block;
VERIFY) and gate it on an actual-restart derivation. Otherwise the event line
carries the truth and the boilerplate stops planting a false frame every turn.

**Testing (hermetic).** Event line per trigger (message / schedule / outcome-
notice / mid-run continuation); situation reflects open plan + child states;
available-ops appear ONLY when the op is available (idle turn-limited child →
"continue" present; no children → no delegation-of-children line); restart line
absent with no restart, present when a restart is detectable. **Live-proof —
the loop-closing test:** re-run the `mad-drive` Phase A + Phase C scenarios
with orientation present (surgically add the block to root per Unit 2 stopgap);
OBSERVE whether root now delegates independent work / uses the turn-limit
affordance. This is the confabulation-audit closing on its own finding — the
acceptance bar is behavior change, not just a rendered section.

---

## Unit 2 — Block reconcile (prerequisite for Unit 1 to reach live agents)

Spec'd above under the critical dependency. Minimum: the surgical-transact
stopgap so Unit 1 can be live-proven on root. Recommended durable build: derive
each agent's block set from `(manifest defaults) + (agent install/remove
diffs)` at render, retiring copy-once staleness. Fixing
[[ctx-install-live-tile-symbol-roundtrip]] (the symbol round-trip that blocks
`install!` on any agent carrying a live-tile block) is part of this — it is the
mechanism a clean reconcile would use.

---

## Unit 3 — Ghost-echo sanitizer hardening (spine integrity)

**Why (evidence).** In `tx-audit`, DeepSeek reproduced its own scaffolding
(masthead, a `;;; ◀ from user …`-shaped line, a `┌─ transcript ─` box) into its
narration, which then persists in the spine one `;`-vs-`;;;` cue from looking
like a real event. The existing `neutralize-result-claims` already reserves
`⟹`/`=>`/`⇒` (the `⟹` reserved-marker work, committed `0d30c829`) — extend the
SAME mechanism.

**What.** Treat the structural runtime markers (`;;; ◀`/`;;; ▶` message lines,
the masthead, the `┌─ … ─` box, the readline) as reserved glyphs: a
model-authored line matching them is neutralized to the unverified-narration
marker, exactly like a forged `⟹`. Build the reserved set from the actual
emit-site defs (single-source-of-truth, the pattern `result-marker` /
`reserved-glyph-re` already establish) so it can never drift from what the
runtime emits.

**Coordination.** This edits `neutralize-result-claims` (`ctx.cljs` ~677-710),
the area the `⟹`-reserved-marker lane just built and is still iterating
(uncommitted `ctx.cljs` work exists). **Hand this to that lane, or coordinate
before touching it** — they own the reserved-glyph machinery; this is a natural
extension of it, not a separate system. File as an issue routed there.

**Testing.** A model reply containing a forged `;;; ◀ from user …` (or masthead
/ box) line → neutralized in the persisted transcript; a genuine runtime event
line is untouched (it never passes through the sanitizer — the composer appends
it after neutralization, same as `⟹`).

---

## Unit 4 — Delta-awareness (narrowed; fold into Unit 1)

**Why.** "What changed since I last looked" completes the model. But the drive
showed the transcript already carries **event-deltas** (an arrived message is
already a line), so the standalone delta shrinks to **non-event derived-state
changes**: a child's run closing, a beat going stale, a plan item closed by
another actor, a new fault — none of which are transcript event-lines.

**What.** Derive changes in `(previous turn's :seon.agent.turn/rendered-as-of,
now]` — the basis-t is already recorded per turn ([[observability]]), so this
is a query, not new state. **Recommended:** fold it INTO Unit 1's situation
rather than a separate section — mark newly-changed situation items with a
"new since last turn" cue derived from the basis-t. One section, not two; the
orientation is the natural home for "what's new in your situation." Only split
out a dedicated delta section if the folded version proves too dense.

**Testing.** A child that closed since the prior turn's basis-t is marked new;
an unchanged situation carries no new-cues; nothing is stored (pure basis-t
query).

---

## Unit 5 — The confabulation-audit as a standing harness (the measure)

**Why.** "Feels stateful" is defined as zero ungrounded self-claims under a
live drive — but today that audit is a manual drive-and-read. To make the north
star a *tracked* property (and to catch regressions as context changes), it
must be a repeatable harness.

**What.** A harness that drives an agent through a fixed scenario battery
(delegation, turn-limit recovery, restart, async result re-reference, …),
captures every self-referential claim in its replies, and ground-checks each
against the byte-exact rendered prompt it saw (transcript + sections, via
`inspect/turn`). Score = fraction of self-claims grounded. An ungrounded claim
names the section to fix — the same loop we ran by hand, automated.

**Placement / coordination.** This is a **scorer**, and it belongs in the eval
lane's inspect-ai world, not a new bespoke system (established benches over
homemade; the eval lane owns measurement). Spec it WITH the eval lane
([[eval-design]], [[coordination]]) — it is the context-completeness metric
their per-row A/Bs have been missing a name for. Highest-leverage meta-item:
it converts "feels stateful" from taste into a ledger row.

---

## Peripheral / deferred (real, but off the feels-stateful spine)

- **External liveness probe** — `bin/seon watch-liveness`, the only layer that
  catches a blocked Node event loop / dead-but-not-exited pod (the in-process
  watchdog structurally can't). Own small unit; the `tx-audit` drive re-proved
  the failure mode (a `--watched` cluster crashed on another lane's hot-reload).
- **`ctx/install!` symbol round-trip bug** — [[ctx-install-live-tile-symbol-roundtrip]];
  subsumed by Unit 2's correct build.
- **Watchdog stale-ms threshold** — currently reasoned (20 min), never measured
  on the live pod as the multiagent spec asked; measure and dial.
- **`seon.agent.fs` ride-along** — 2 home-requires lines still riding in
  `config/system.edn`'s working tree from the edit-protocol lane; flag to that
  lane at their next commit.

## Suggested order + dependencies

1. **Unit 1 (wake-orientation)** — the headline build; needs Unit 2's stopgap
   (surgical transact) to live-prove on root.
2. **Unit 2 (block reconcile)** — stopgap alongside Unit 1; the derive-from-
   manifest build follows as the durable fix (also unblocks the whole "new
   default block reaches everyone" class).
3. **Unit 4 (delta)** — folded into Unit 1 if it lands cleanly; else a fast
   follow-up.
4. **Unit 3 (ghost-echo)** — parallel, but ROUTED to the ⟹-marker lane
   (coordinate; don't fork their sanitizer).
5. **Unit 5 (audit harness)** — parallel, WITH the eval lane; the standing
   measure that regression-guards all of the above.

Each lands with a live drive / audit proving the behavior changed — not
inference. The acceptance bar for the whole arc: a driven agent makes zero
claims about itself that its rendered context did not contain.
