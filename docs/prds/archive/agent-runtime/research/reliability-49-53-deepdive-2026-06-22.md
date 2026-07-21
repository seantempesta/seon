---
type: research
status: active
tags: [research, agent]
---

# Reliability #49-53 — Final Source-Level Design (deep dive)

Lead-architect FINAL call on the #49-53 source-level fixes, integrating the
adversarial dissent panel (three lenses) and external Gemini critique. The
theory was verified against real source; the dissent and Gemini converged
independently on three load-bearing weaknesses, and Gemini contributed one
new, adoptable refinement. This doc PRESERVES the minority view rather than
resolving it away.

Built on the committed #40-43 state at `304ef96`. Existing plan:
`docs/prds/agent-runtime/reliability-fixes-49-53-2026-06-21.md`.

Downstream consumer is referred to throughout as **acme** — never named.

## TL;DR — the final solution per problem

- **#50 + #52 (one parse fix) — FORMS-AND-PROSE ONLY (LOCKED 2026-06-22).** A
  top-level READ form evaluates IFF it is a list/seq; everything else is prose.
  User decision: do NOT support bare data-literal echo (a REPL would, but it's a
  minefield of intent-guessing). So `(...)` + reader-macros (`@x`/`'x`/`#(...)` —
  all read as seqs) evaluate; scalars, symbols, AND `{}`/`[]`/`#{}` are prose. No
  inert-recursion, no `=>`-detection — a fabricated `=> {…}` is prose because it
  isn't a list (#52 falls out free). Classify on `(seq? form)`, NEVER
  `coll?`/`sequential?`. Prose is DROPPED, not echoed as `;;` (that echo was the
  `;;`-imitation trap); a one-line warning fires ONLY when a data literal
  (`{}`/`[]`/`#{}`) is demoted, never on ordinary sentences. Delete the false
  `internal.cljc:124` sentence AND rewrite the `ctx.cljs:928-931` prompt to match
  (same patch). Multiline/indented forms are safe — the reader groups a whole
  `(...)` as one top-level form regardless of indentation.

- **#51 (delivery) — NARROW the gate to the envelope-value case + reframe as a
  loop-termination veto, do NOT fully delete.** Drop the eval-error half of
  `batch-failure-lines` (genuine errors are advisory after #50), keep the
  envelope-value half (`{:seon.db/ok? false}` on an `ok? true` row — the half
  the real B3 incident hinged on). The reply **transacts and is delivered**;
  what the gate vetoes is the loop's *terminal halt* — force ONE more turn so
  the advisory render lands in a LIVE turn the agent actually sees. Keep
  `:seon.agent.message/force` as the deliberate escape. Add the advisory render
  as a pure derivation.

- **#49 (intake) — KEEP the latch held for the whole loop; fix the tail window
  with drain-on-exit ONLY.** Do NOT narrow the latch to the first `:running`
  flip (that re-opens the double-loop at inter-turn `:idle` gaps). The
  drain-on-exit re-query in the `.finally` closes the tail window by itself,
  with the latch held continuously until the message log drains empty. Plus
  serve.cljs observability (INTAKE log + fail-loud `console.warn`).

- **The one cap — SPLIT by component, do NOT collapse to a single 16384.**
  Delete `core-eval-render-cap=50000` and its core-authored routing (genuinely
  dead by construction — take that win). Raise the cap to `16384`
  (= `store-edn-cap`) **ONLY for the citable result body** (it has a
  `result/<id>` escape and a 50-row collection cap). Keep `form-ln` (echoed
  source) and `out-ln` (captured stdout) at **1500** — they have neither
  escape hatch and would blow the 24000 transcript budget. Two independently
  named caps with a cross-reference comment, NOT an alias.

- **Loop-coherence verdict — correct-by-construction, loop UNTOUCHED.** All
  three real defects are upstream of `run-agentic-loop!` in the data its
  derivations read. After the fixes, `eval-count` / `replied-since-inbound?` /
  `turns-cap` all see truthful data. `eval-count = n-ok + n-fail` stays
  (gym S-12, load-bearing). The loop's STOP logic does not change.

The theory was right about the LOCATIONS (single classifier, single gate,
latch lifecycle, cap drift) and the loop verdict. The dissent + Gemini were
right that two of the theory's chosen MECHANISMS over-reached (full gate
removal; flat 16384 cap) and one introduced a fresh regression (latch
narrowing). The final design keeps the theory's altitude and adopts the
narrower, regression-free mechanism at each of those three points.

---

## Per-fix: the true-source change

### #50 + #52 — one parse fix at the prose/form classifier

**True source (file:fn):** `src/seon/repl/internal.cljc` — `narration-atom?`
(lines 128-140), the single prose/form boundary, consulted at the one call
site `parse-forms` (line 351). Verified: a bare `{..}`/`[..]`/`#{..}` falls
through to `:else → {:kind :form …}` at lines 286-287 and is evaluated. And
verified at `eval.cljs:2367-2373` that EVERY successful `:kind :form` eval —
including a bare collection literal — calls `stash-result-raw!` +
`bind-result-var!`, minting a durable `result/<id>` and a `:seon.eval` row.
That is exactly how a fabricated `=> {:role :admin :ok true}` becomes
runtime-real and citable (#52).

**The change.** Rename `narration-atom?` → `top-level-datum?` and extend it so
a top-level collection literal is classified as prose-preamble (captured as a
`;;` comment, never evaluated) **only when it is unambiguously not an action**:

1. it is **structurally inert** — a recursive `inert-datum?` that returns true
   iff every nested element is itself a scalar / keyword / string / boolean /
   nil / char or an inert collection, i.e. it contains NO list and NO symbol
   that could be a call; OR
2. it is the literal **directly following a `=>`/`⇒` echo token** in the same
   prose span (the exact #52 shape — the verified live trace shows the `=>`
   folds into narration and only the literal survives as a form).

A top-level collection with a nested call (`{:now (js/Date.)}`,
`[(inc x) (dec y)]`, `[{:my.kb/id "x"} (db/new-id!)]`) is NOT inert → stays
`:kind :form` → evaluates and binds `result/<id>` as today.

**Why minimal/elegant.** It is one predicate at one call site (no new
namespace, no compile-state coupling, no per-consumer scoring patch). It
fixes #50 and #52 with the SAME edit because they are the same defect at the
same line. It is strictly more precise than the theory's raw-shape demotion:
the theory demoted by SHAPE when the real signal is INERTNESS — a fabricated
echo is always inert, a deliberate runnable literal usually is not. The
existing capture path (lines 351-359) already turns a demoted token into a
`;;` comment-preamble line; no new machinery.

**Delete the false comment + fix the prompt (same patch).** `internal.cljc:124`
("an echoed result map `{...}` still evals (harmless identity), exactly
matching the taught contract") is FALSE — verified against the system prompt
`ctx.cljs:928-931` which teaches the OPPOSITE ("a map, set, or list you paste
is read as CODE and the reader runs it"). Delete the stale sentence. AND
rewrite `ctx.cljs:928-931` to describe the NEW runtime ("a bare data literal
you paste is treated as a note, not executed; to evaluate a fresh literal,
include a call or bind it explicitly") — otherwise we trade one prompt/runtime
contradiction for the inverse one.

**What it explicitly does NOT break (honored mustPreserve items):**
- *Concrete-type-only* (all three lenses, non-negotiable): tests `map?`/
  `vector?`/`set?`, never `coll?`/`sequential?`. A list `(..)` and any
  reader-macro seq (`@x`, `'x`, `#'x`, `#(..)`, syntax-quote) stay `:kind
  :form` and keep evaluating.
- *Deliberate result-binding literal* (Lens A1, Lens C): an inert
  `[{:my.kb/id "x"}]` typed alone to bind `result/<id>` for the next turn —
  this IS demoted under the inert rule, so see PRESERVED DISSENT below; a
  literal with a nested call survives untouched.
- *Side-effecting nested forms* (Lens A1, Gemini A1): `{:now (js/Date.)}` is
  non-inert → evaluates → its `(js/Date.)`/`(db/new-id!)` calls run.
- *Prompt/runtime agreement* (all lenses): the load-bearing #50/#52 bug IS the
  disagreement; this patch fixes BOTH `:124` and the prompt at 928-931.
- *neutralize-result-claims twin* (Lens B mustPreserve): the render-time
  scrubber (`ctx.cljs:421`, `bare-result-claim-re`/`result-claim-re`, built
  from 6 captured weak-model files) is NOT weakened. It is the
  complementary layer: parse stops eval/stash of a CURRENT-turn fabricated
  literal; the scrubber stops a model-authored `=>` claim in STORED narration
  from re-entering context on a LATER turn. Both fire.
- *No parallel versions:* the fix lives in the one classifier; no `foo-v2`.

### #51 — narrow the gate to envelope-values; veto the halt, not the write

**True source (file:fn):** `src/seon/agent/message.cljs` — `batch-failure-lines`
(lines 208-241) consulted at the refusal wrapper in `message!` (lines 302-316).
Verified: a refused reply returns an error envelope and NEVER transacts → the
loop's FIRST halt check `replied-since-inbound?` (`agent.cljs:1467`) stays
false → the loop `(recur)`s. The theory's full-removal frees the reply to
transact, but the dissent + Gemini proved that re-opens #26/B3 for the case
that matters.

**The decisive correction (Lens A2/B3, Lens C, Gemini A2/B3 — all three
converge):** the theory's central safety claim — "the modern loop grants a
next turn, so the agent is no longer structurally blind" — is FALSE for the
B3 case. The real incident (`context-blind-spots-2026-06-11.md:133-136`) was a
transact that returned a `{:seon.db/ok? false}` ENVELOPE VALUE, which is
`:seon.eval/ok? true` (eval succeeded, returned a failure value). It is
counted as n-OK, so "failed evals grant a next turn" never applied to it. And
because `reply!` HALTS the loop on `:replied` (checked first), an advisory
render targets a turn that will never run — the human already received the
false "done, stored" claim. Advisory-render-only is vacuous for the exact case
the gate exists to protect.

**The change (two parts):**

1. **Drop the eval-error half** (`message.cljs:230` branch). After #50, a
   genuine eval error is advisory: it counts toward `eval-count`, the loop
   grants a next turn, and the error renders with crystal-clear guidance
   (`ctx.cljs` error-lines, `=> ✗ …`). Blocking a legit reply on an
   exploratory throw IS the post-answer churn the loop economy fights (Lens B
   endorsement; Gemini endorses). So this half becomes pure advisory.

2. **Keep the envelope-value half** (`message.cljs:234-239`) as a narrow gate,
   reframed as a **loop-termination veto, not a write block.** When a
   user-facing reply (`origin` would be `:human`) is composed in the same turn
   as a sibling form that returned an envelope failure, the reply still
   **transacts and is delivered** (the human gets the answer), but the loop is
   forced ONE more turn so the advisory render — "you replied; sibling write X
   returned `{:seon.db/ok? false}`" — lands in a LIVE turn the agent sees,
   giving it a chance to send a correcting message. `:seon.agent.message/force`
   stays as the deliberate "I am replying ABOUT the failure" escape.

   (The exact mechanism for "force one more turn after a delivered reply that
   cited a same-turn envelope failure" is an open user-decision — see DECISIONS
   §5.2. The alternative the panel raised is the reply OUTBOUND path appending
   the envelope-failure note to the human directly. Either satisfies the B3
   mustPreserve; the veto-the-halt form keeps it agent-facing and reactive.)

**Surgical boundary.** Whatever shrinks, the committed #43 origin stamp
(`origin (or origin (if from-user? :human :agent))`, `message.cljs:317-324`)
lives INSIDE the `:else` transact body, BELOW the `failures` let/if —
verified. It MUST survive; `replied-since-inbound?` and the wake trigger
depend on it. Sequencing: **#50 lands before #51** (verified mandate) so
`batch-failure-lines` only ever sees genuine code/envelope failures, never
prose misreads.

**Code-smell fix in passing.** The `batch-failure-lines` docstring (lines
213-217) claims "an ok eval whose live value can't be found is also flagged —
unverifiable is not verified." The code (234-239) wraps the lookup in
`(when (envelope-failure? live) …)` inside a `keep` — a lookup MISS returns
nil and is DROPPED, never flagged. The "unverifiable" branch does not exist in
code. Correct the docstring to match the (narrowed) code.

**What it explicitly does NOT break (honored mustPreserve items):**
- *#26/B3 false-claim protection* (all three lenses, blocking): the
  same-completion blind claim over an `ok? true` envelope-failure value is
  STILL caught — the gate's load-bearing half survives, reframed so the
  protection reaches the agent on a live turn rather than a dead one.
- *Reply terminates the wake* (`agent.cljs:1467` first): a delivered reply
  still halts; the veto adds at most ONE more turn for the narrow envelope
  case, it does not make a valid reply fail to transact.
- *#43 origin stamp:* survives, inside the transact body.
- *Reactive-context:* no stored "you over-claimed" flag. The advisory is a
  pure section function over the `:seon.eval` rows that already exist; the
  one-more-turn veto reads the same derivation, stores nothing to clear.

### #49 — keep latch for whole loop; drain on exit

**True source (file:fn):** `src/seon/agent.cljs` — `inbound-message-handler`
(`!kick-scheduled` conj at line 616, disj at line 627 `.finally`) +
`run-agentic-loop!` exit; plus ack-on-persist in `src/seon/web/serve.cljs
handle-chat!`. Verified the race: the wake guard
`(and (not= :running state) (not (contains? @!kick-scheduled id)))` (lines
614-615) holds the latch for the whole loop, but state flips back to `:idle`
at the LAST turn's close-tx (`agent.cljs:1059`, inside `with-turn-body!`)
BEFORE the loop's `.finally` clears the latch. A message in that tail window
is `:idle` (passes state) but the latch still holds id (fails latch) → no
wake, no re-drain → row unprocessed while the human already saw 204.

**The decisive correction (Lens A3, Lens C5, Gemini A3 — all three converge,
A3 rated BLOCKING):** the theory's edit (a) — narrow the latch to clear at the
first `:running` flip — re-opens the very double-loop the latch exists to
prevent. Verified: `run-agentic-loop!` is multi-turn and state cycles
`:running → :idle → :running` ACROSS turns (open-tx `:running` at line 1010,
close-tx `:idle` at line 1059, each turn). If the latch clears at turn 1's
`:running`, a handler firing in any inter-turn `:idle` gap reads `:idle` AND
finds the latch clear → schedules a SECOND concurrent loop for the same agent.
The narrowing fixes the tail window by re-opening the double-loop in the
inter-turn gaps. It is also UNNECESSARY: the drain-on-exit closes the tail
window by itself.

**The change (drop edit (a); keep latch-for-whole-loop + drain-on-exit):**

1. **Do NOT narrow the latch.** Its current lifetime (held from handler-conj
   through loop `.finally`) is CORRECT for the double-loop guard. Leave it.

2. **Drain on loop exit** (the only liveness edit): at `run-agentic-loop!`
   exit, before releasing the latch, re-query un-drained inbound using #43's
   origin-aware predicate (`to ∋ me ∧ from ≠ me ∧ origin ∈ {:human,:agent} ∧
   hops < cap`, newer than the halt baseline, not yet answered — the exact
   shape `replied-since-inbound?` already encodes). If any exist, **do not
   release the latch** — recur the loop directly (latch stays held across the
   drain, released only when the log query comes back empty). This is Lens C5's
   refinement of the theory's edit (b): the latch's lifetime becomes "from
   handler-schedule until the message log shows nothing left to drain," and the
   double-loop guard stays intact across the drain. It removes the
   clear-then-requery race the theory's "clear FIRST, then re-query" ordering
   would have opened.

3. **serve.cljs observability:** keep the 204 after `message!`-ok, add an
   observable INTAKE log line per accepted message, and a fail-loud
   `console.warn` in any dropped-wake branch (a silently-dropped wake is
   exactly the fail-loud violation the project forbids — Lens B/Gemini both
   call this unambiguously good).

**Constraints honored.** The drain reads the MESSAGE LOG (pure derivation),
not the latch, so a missed `:idle` reset can't strand intake. It filters #43
origin ∈ {:human,:agent} so a self→self `:core` nudge never re-wakes the loop.
No stored pending-wake queue (reactive-context). The open-tx-failure path
(`agent.cljs:1011-1012`, where `:running` may never commit) needs the latch to
release on loop exit as a fallback — preserved, because exit still releases the
latch once the drain query is empty.

**What it explicitly does NOT break (honored mustPreserve items):**
- *Double-loop guard* (Lens A3/C blocking): the latch covers EVERY `:idle`
  window the loop passes through — initial read-schedule window AND every
  inter-turn gap — because we keep it held for the whole loop. No two
  concurrent loops for one agent-id.
- *#43 origin gating:* the drain re-query filters origin ∈ {:human,:agent}.
- *Reactive-context:* drain is a derivation over the message log; no stored
  queue.

### The one cap — split by component (delete the dead core cap)

**True source (file:fn):** `src/seon/ctx.cljs` — the render apply site at line
561 (`limit (if core? core-eval-render-cap eval-render-cap)`), then applied to
`form-ln` (line 571), `out-ln` (line 575), AND the result body via
`cap-result-body` (line 593). `eval-render-cap=1500` (line 300),
`core-eval-render-cap=50000` (line 309), `store-edn-cap=16384`
(`eval.cljs:1710`). Verified the core cap's own docstring (ctx.cljs:320-323)
admits it never bites because `store-edn-cap=16384` bounds the stored string
first. Verified `store-edn-cap`'s docstring (eval.cljs:1724) states "16k is
~10x the render cap … the LLM never sees beyond the render cap anyway" — the
two caps are DELIBERATELY decoupled.

**The decisive correction (Lens A4/B6, Lens C, Gemini (b) MODIFY — all three
converge, and Gemini contributes the new, adoptable refinement):** the theory's
single-cap collapse to 16384 has TWO flaws the source confirms. (i) The cap at
line 561 is shared by `form-ln`, `out-ln`, AND the result body — raising it to
16384 raises ALL THREE; an echoed source blob or a chatty `println` (neither
has a `result/<id>` escape, neither is row-capped) could consume up to 68% of
`transcript-char-budget=24000` in one row, triggering newest-first eviction of
the conversation history a (weak) model needs. (ii) The theory's
"pathological reads stay doubly bounded" covers only the result BODY (via the
50-element `result-row-cap`); it does not cover a pathological `form-ln` or
`out-ln`.

**The change (split by component):**

1. **Delete `core-eval-render-cap=50000` and the core-authored routing
   plumbing** (ctx.cljs:561 `core?` branch, plus the `:seon.ctx/core-authored?`
   threading at ctx.cljs ~705/707-727/748 and transcript.cljs ~60/74/120-123/
   214-215). It is dead by construction (its own docstring admits it). This is
   the genuine no-parallel-versions win — take it.

2. **Result body cap → 16384** (= `store-edn-cap`). The result body is the one
   component that (a) is the actual #53 symptom (a stored ≤16384 value clipped
   mid-value at 1500, driving the re-query), (b) carries a `result/<id>`
   escape, and (c) is already row-capped at 50 elements upstream
   (`render-result-edn`), so a 16384 body is structured, not a wall of text.

3. **Keep `form-ln` (echoed source) and `out-ln` (captured stdout) at 1500.**
   Neither is dereferenceable via `result/<id>`; both are context-wasting noise
   if large. This is Gemini's split — the single most actionable contribution
   from the external pass, and source-confirmed at line 561.

4. **Two named caps, NOT an alias** (Lens C). Keep `eval-render-cap` for the
   result body sized to the store ceiling, with a cross-reference comment ("the
   render cap currently equals `store-edn-cap` so stored results render whole;
   they remain independently tunable"). The drift that produced #53 is
   prevented just as well by a comment, and the three-tier rule (datom
   projection vs persisted blob vs live value) says render-cap and store-cap
   are different tiers allowed to differ — an alias makes them
   physically un-decouplable and forecloses ever tuning the LLM-facing cap for
   token economy without moving the RAM ceiling.

5. **Keep `store-edn-cap=16384`** as the distinct WRITE-TIME per-datom anti-OOM
   ceiling (the 9.7M-pull lesson). Do not lower it.

6. **Keep separate:** `message-render-cap=4000` (inbound chat) and
   `transcript-char-budget=24000` (the section bound whose newest-first
   eviction lets legitimately growing recent context survive). Keep
   `cap-result-body`'s `result/<id>` pointer + size guide (ctx.cljs:354-385)
   for the rare over-16384 result.

**What it explicitly does NOT break (honored mustPreserve items):**
- *Context-SAFETY / no single result dominates context* (all lenses): the
  16384 body is row-capped at 50 elements; `form-ln`/`out-ln` stay at 1500;
  `transcript-char-budget=24000` still holds enough rows for prior turns.
  store-edn-cap=16384 RAM ceiling unchanged.
- *Three-tier storage:* render (projection, capped by component) vs store
  (datom, 16384) vs `result/<id>` stash (uncapped live value) stay three
  independently-reasoned tiers; the two caps are not aliased.
- *No parallel versions:* the dead core cap + routing is deleted in place.

---

## PRESERVED DISSENT

These are the objections we did NOT fully resolve. The minority view survives
here so the next architect sees what we knowingly traded.

1. **#50/#52 — a DELIBERATE inert result-binding literal is still demoted
   (Lens A1, Lens C).** The chosen inert-datum rule means an agent that types
   `[{:my.kb/id "x"}]` or `{:find … :where …}` alone — a fully-inert literal —
   intending to bind `result/<id>` for the next turn now gets it captured as a
   comment; no var is minted; its next-turn `result/<id>` reference is
   undefined. This is the inverse failure the dissent flagged. **Why not fully
   resolved:** the parse layer cannot read INTENT, and the alternative
   (stash-layer fix — let inert literals eval but don't mint a durable datom +
   `result/<id>` when output ≡ input form) is the *truest* source for #52 but
   touches `stash-result-raw!`/`record-eval!`, a larger blast radius than the
   classifier. **Least-harmful compromise:** demote on inertness (kills #52's
   fabrication, the proven-harmful case) AND mitigate the inverse by ensuring
   the demoted literal renders as a visible `;;` line, so the agent SEES its
   literal was treated as a note and can re-emit it inside a binding form. The
   stash-layer alternative is recorded as the fallback if the live drive shows
   inert-binding-literals are common for acme's models. **(Carried as DECISION
   §5.1 — narrow the demotion to `=>`-echo-only if even inert demotion proves
   too aggressive.)**

2. **#51 — for the weakest models, a false success reply can still land if the
   sibling failure is NOT an envelope value (Lens B3, Gemini note).** The narrow
   gate covers only the `ok? true` envelope-failure case. A weak model that
   composes `[failing-eval-error, reply! "done"]` in one batch now delivers the
   reply (eval-error half is advisory) and halts; the false claim lands, with
   correction deferred to a future inbound. **Why not fully resolved:** keeping
   the eval-error half as a block re-introduces the exploratory-throw churn the
   loop economy was built to kill (Lens B + Gemini both endorse dropping it).
   We deliberately accept: a weak model claiming success over a genuine
   eval-error gets that claim delivered, relying on the next-turn-visible error
   + future inbound for correction. **Least-harmful compromise:** the gate
   guards the one structurally-invisible case (the envelope value, where eval
   "succeeded"); genuine errors are visible by construction. This is the
   accepted trade, stated explicitly so it is not a silent regression.

3. **#51 — advisory render scope is anxiety-noise for weak models (Lens B4).**
   "Fire whenever any sibling failed" tells a weak model on most exploratory
   turns "your reply landed BUT sibling X failed" for irrelevant throwaway
   forms, manufacturing self-doubt churn. **Gemini OVERRULED this** (preferring
   the simpler variant). **We side with Lens B over Gemini here:** scope the
   advisory to the TIGHTEST variant — fire only when an outbound `:human` reply
   landed in the same turn as an ENVELOPE-VALUE sibling failure (a write that
   silently failed), NOT for exploratory eval-errors. This correlates the
   advisory with the actual over-claim risk and stays silent for exploration
   noise. Gemini's overrule is low-conviction (it asserts "simpler" without
   engaging the anxiety mechanism), so it does not move us. **(This is
   DECISION §5.2.)**

4. **#49 — "no new state" is mildly overclaimed (Lens C).** The drain is a pure
   derivation, but the SCHEDULING it triggers is shared mutable control flow.
   **Resolved** by keeping the latch held across the drain (recur directly, do
   not clear-then-requery), so there is no window for a concurrent handler to
   race the drain's reschedule. Recorded because the "no new state" framing
   should read as "no new STORED state; the latch is the existing runtime
   artifact, now held one beat longer."

5. **Cap — Gemini overruled the eviction objection (A4/B5) conditionally.**
   Gemini accepts raising the result-body cap to 16384 PROVIDED form-ln/out-ln
   stay at 1500. We adopt exactly that condition, so the objection is honored,
   not overruled: the eviction risk is confined to the one component that is
   row-capped and dereferenceable. The minority caution (a single fat result
   row can still evict 2-3 prior turns) survives as the open verification in
   DECISIONS §5.3 / the implementation plan: confirm the largest core
   creation-eval (`store-inventory`) and the largest routine agent read leave
   room for prior turns under 24000.

---

## Gemini's verbatim response (preserved)

Gemini (model `gemini-3.5-flash` via `agy`) emitted the answer twice; the first
pass truncated mid-sentence inside the C3-overrule bullet, then it restarted and
produced a complete clean pass. The complete second pass is authoritative and is
reproduced verbatim below.

```
### (a) Per-Fix Source-vs-Symptom & Regression Judgment

*   **#49 (Dropped `/chat` Intake Latch):**
    *   **Judgment:** **Symptom Patch with a Critical Regression.**
    *   **Why:** Clearing the latch (`!kick-scheduled`) at the first transition to `:running` (Edit 1) opens a race condition. Because `run-agentic-loop!` is multi-turn, it transitions to `:idle` in `close-tx` at the end of each turn before evaluating the loop continuation. If a handler fires during this inter-turn `:idle` window, and the latch has already been cleared, both guards will pass, spawning a **duplicate concurrent agentic loop** that will corrupt session state.
    *   **True Source Fix:** *The latch must hold for the entire loop execution. The true source is the lack of an atomic drain-and-release check at the exit boundary of the loop.*

*   **#50 (Prose parsed as Clean Form):**
    *   **Judgment:** **Symptom Patch with Silent Regressions.**
    *   **Why:** Demoting all top-level maps, vectors, and sets to comments based on raw type alone breaks standard, valid Clojure patterns. An agent writing a configuration map to bind its value to a `result/<id>` variable, or writing a literal collection containing nested side-effecting evaluations (e.g. `{:time (js/Date.)}`), will have their code silently ignored and treated as prose.
    *   **True Source Fix:** *The parser cannot distinguish between an intentional evaluation of a data structure and a fabricated REPL echo (`=> {...}`).*

*   **#51 (Batch-Failure Gate Refusal):**
    *   **Judgment:** **Symptom Patch with a Blindness Regression.**
    *   **Why:** Fully deleting the gate re-opens the `#26/B3` false-claim bug for weaker models. If a weak model attempts a database transaction that returns a failing envelope (which has `eval-ok? true` but returns `{:seon.db/ok? false}`), the transaction fails but the agent immediately emits a reply ("Success!"). Because the loop terminates on `:replied`, the loop exits. The advisory render is rendered into a turn that *never runs*, meaning the human receives a false success confirmation and the agent never wakes up to see the failure.
    *   **True Source Fix:** *The gate was designed to block all evaluation errors (including exploratory ones) rather than strictly blocking loops from halting when they emit a reply and have an unresolved same-turn transaction failure envelope.*

*   **#52 (Fabricated Trailing Data Literal):**
    *   **Judgment:** **Symptom Patch.**
    *   **Why:** Like #50, this relies on a blunt syntactic parser demotion that breaks legitimate data-literal evaluations.
    *   **True Source Fix:** *Allowing REPL-style echo patterns (like `=> {...}`) to be processed as executable code at all, or failing to filter identity-evaluating forms from the database stash/record path.*

*   **#53/Cap (Cap Consolidation):**
    *   **Judgment:** **Symptom Patch that Regresses Transcript Density.**
    *   **Why:** While deleting `core-eval-render-cap` is a correct removal of dead code, raising the LLM-facing print cap to `16384` for *all* renders (including echoed source and stdout) will cause a single verbose evaluation or chatty stdout to consume up to 68% of the `24000` transcript budget, causing aggressive newest-first eviction of the agent's history context.
    *   **True Source Fix:** *The drift between the database storage ceiling and the LLM-facing render ceiling, but they must be managed with distinct caps for stdout/source versus structured result values.*

---

### (b) The "One Cap at the P" Design & the 16384 Default

**Verdict:** **MODIFY.**

*   **Decisive Reasons:**
    *   **Transcript Budget Eviction:** Setting a flat `16384` cap on all outputs inside a `24000` character budget is dangerous. A single large return will wipe out the agent's memory of past turns.
    *   **Stdout and Source Echo Blast Radius:** The cap applies to the echoed source line (`form-ln`) and captured printed output (`out-ln`). A loop producing verbose logs or an echoed large form will consume the entire budget, bypassing the protection of the `result-row-cap = 50` collection item limit (which only bounds the result body).
    *   **Preserving 10x Decoupling:** The write-time `store-edn-cap = 16384` is a physical DB/RAM guard. The read-time render cap must be smaller for non-citable components to preserve density.

*   **Modification Plan:**
    1.  Delete `core-eval-render-cap` and all its plumbing (as proposed).
    2.  Keep `store-edn-cap = 16384` as the database write ceiling.
    3.  Keep a low cap (`1500` chars) on `form-ln` and `out-ln` (source echoes and stdout), since these cannot be dereferenced via `result/<id>` and represent context-wasting noise if too large.
    4.  Raise the print cap to `16384` *only* for the evaluated result body (which is already protected by the `result-row-cap = 50` limit). If a result is truncated, it prints `result/<id>` for referencing.

---

### (c) Dissent Objections Evaluation

#### Top 3 Most Credible Objections (Will block/change the plan):

1.  **Objection A3 (Double-Loop Re-entry):**
    *   *What it changes:* Reject the narrowing of the latch (Edit A). Keep the latch active for the entire duration of `run-agentic-loop!`.
    *   *Why:* Clearing it at the first `:running` flip fails to cover the inter-turn `:idle` gaps, allowing concurrent handlers to schedule overlapping loops.
2.  **Objection A1 (Silent Demotion of Valid Code in Collections):**
    *   *What it changes:* Reject raw shape-based parsing demotion of maps, vectors, and sets.
    *   *Why:* Demoting collections by type alone breaks nested side-effects (e.g. `(js/Date.)` inside a map) and standard terminal-binding evaluations.
3.  **Objection A2 / B3 (B3 False-Success Blindness):**
    *   *What it changes:* Reject full deletion of the gate. A narrow gate must remain to handle same-turn transaction failure envelopes.
    *   *Why:* If the loop halts immediately on `:replied`, the advisory next-turn render is dead-on-arrival and the false claim lands unchecked.

#### Objections to Overrule:

1.  **Objection B4 (Advisory Render Scope Anxiety-Noise):**
    *   *Overrule:* Overruled. We want the simpler implementation ("whenever any sibling failed") because a weak model needs explicit feedback on exploratory errors rather than silent failure. Keeping it simple avoids complex state-tracking of what constitutes a "write" versus an "exploration".
2.  **Objection A4/B5 (Transcript Budget Eviction):**
    *   *Overrule:* Overruled as a reason to reject raising the result cap, *provided* the modification in (b) is adopted (keeping `form-ln` and `out-ln` capped at `1500`). The row cap of 50 makes a 16384-character result body highly structured, and the agent's ability to pull whole schemas without truncation outweighs the occasional eviction of older context.

---

### (d) More Elegant Alternatives

#### 1. Elegant Alternative for #50 / #52 (Data-Literal vs. Code Boundary)
Instead of syntactic shape-based demotion in the parser (`top-level-datum?`), we should target the **Intent and Echo Structure** at parse time, or filter at the **Stash/Record Layer**:

*   **Lexical/Structure Echo Parser Filter:**
    Modify `parse-forms` to classify a top-level collection as a comment *only* if:
    1.  It is preceded in the same turn's prose by the evaluation echo indicator `=>` (exactly matching the REPL printout pattern).
    2.  Or, it is a **fully-quoted/inert datum** (i.e. recursively contains only scalars, keywords, strings, maps, vectors, sets, and no symbols/lists that could be function calls).
    This preserves expressions like `{:time (js/Date.)}` as `:kind :form` (executable code) while safely demoting `{:role :admin :ok true}` (which is inert and follows a prose block) to a comment.
*   **Stash/Record Filter (Alternative):**
    If a literal collection is evaluated, let it run (which is a harmless identity evaluation), but **do not mint a durable datom or result/<id> handle** if the input form is structurally identical to the evaluated output. This stops the fabrication of new citable database facts (#52) at the true source (the database storage layer), without mutating parser classification rules.

> [!NOTE]
> *Weak Model Failure Mode:* If a weak model attempts to write a configuration map as code to reference it, and it contains no nested calls, it might get categorized as a comment.
> *Mitigation:* The system prompt must be updated (aligning with `ctx.cljs:928-931`) to explicitly state: "To bind a static map/vector/set to a variable, always wrap it in `(identity ...)` or quote it to ensure it is treated as code."

#### 2. Elegant Alternative for #51 (The Batch-Failure Gate)
Rather than deleting the gate entirely or keeping the original overly broad gate:

*   **Surgical Envelope Gate:**
    Keep the gate in `message.cljs:302-316` but restrict it *only* to blocking replies when a same-turn form returned a **transaction failure envelope** (i.e., `eval-ok?` is true but the live value has `{:seon.db/ok? false}`).
    *   Drop the check for `eval-ok? false` (general compiler/runtime errors). These are already visible to the agent and count towards `eval-count`, giving the agent a next turn to see and fix them if it chooses not to reply.
    *   If a transaction envelope failure occurs *and* the agent tries to reply, veto the reply, force a next turn, and render the failure.
    *   Keep the `:force` flag as an escape hatch for weak/strong models.

> [!IMPORTANT]
> *Weak Model Failure Mode:* If a weak model encounters an exploratory syntax error and replies, the loop halts without blocking.
> *Mitigation:* This is acceptable because the error is advisory, and the human can see the syntax error in the transcript next to the reply. The critical B3 boundary (where a transaction failed but the agent claimed it succeeded) remains locked down.

#### 3. Elegant Alternative for #49 (Latch Lifecycle & Exit Drain)
Instead of clearing the latch at the first `:running` flip (which breaks multi-turn loops):

*   **Atomic Loop Drain-on-Exit:**
    1.  Maintain the latch (`!kick-scheduled`) for the entire duration of `run-agentic-loop!`.
    2.  At the end of the loop, inside the `.finally` block:
        *   Perform a synchronous database transaction to mark the loop as `:idle`.
        *   *Immediately* check the message log (using the origin-aware predicate) for any inbound messages that arrived during the loop's execution.
        *   If messages exist, **do not clear the latch**. Recur or schedule another loop iteration directly.
        *   Only clear the latch and exit when a check under the lock confirms the message log has been completely drained to empty.

This keeps the double-loop protection fully intact during all inter-turn `:idle` windows while guaranteeing no message is left unprocessed at exit.
```

### Which Gemini points changed the design

- **(b) split-by-component cap — ADOPTED, this is the single biggest shift.**
  Gemini independently produced a cleaner synthesis than either the theory
  (flat 16384) or Lens C (two-caps-core-vs-agent): keep the dead-code deletion,
  raise to 16384 ONLY for the citable, row-capped result body, keep
  form-ln/out-ln at 1500. Source-confirmed at ctx.cljs:561 (one `limit` feeds
  all three components). This replaced the theory's flat-cap collapse.
- **#51 narrow envelope gate + reframe as loop-termination veto — ADOPTED.**
  Gemini's "veto the reply's terminal effect / force a next turn, not the
  write" reframe is the cleanest way to keep #43 + reactive-context while
  closing the same-completion blind-claim hole the advisory-render-only plan
  could not. This replaced the theory's full removal.
- **#49 keep-latch + drain-on-exit — RATIFIED (no design change, raised
  confidence).** Gemini independently rated the latch-narrowing a Critical
  Regression (matching Lens A3/C5), corroborating the decision to drop edit (a).
- **#50 inert-OR-echo parser filter — ADOPTED over raw shape.** Gemini's
  recursive-inert + `=>`-echo classification (and its stash-layer alternative)
  replaced the theory's raw `map?`/`vector?`/`set?` demotion.

### Which Gemini points were DISCOUNTED

- **B4 overrule (keep advisory "whenever any sibling failed").** Discounted —
  contradicts Lens B's anxiety-noise mechanism and Gemini's own reasoning
  elsewhere; it asserts "simpler" without engaging the mechanism. We side with
  Lens B (tightest envelope-only scope). See PRESERVED DISSENT §3.
- **#49 "synchronous DB transact :idle inside .finally" detail.** Discounted in
  favor of the source-grounded version: close-tx already flips `:idle`
  per-turn; the fix is the drain re-query + hold-latch-across-drain, not a new
  `.finally` transact.
- **"wrap literals in (identity …) or quote them" mitigation.** Discounted —
  it adds exactly the model-facing contract Principle-2 is trying to delete,
  and weak models won't reliably comply. The inert/stash-layer fixes don't
  need it.
- **Gemini's "true source" one-liners** are directionally right but it had no
  source access (it occasionally restates the prompt back). Treat the
  convergence as corroboration, not independent verification — all
  source-claims in this doc were verified by reading the code.

---

## DECISIONS that remain for the user

1. **#50 demotion scope — RESOLVED (user, 2026-06-22): FORMS-AND-PROSE ONLY.**
   A top-level read form evaluates iff `(seq? form)`; maps/vectors/sets/scalars/
   symbols are ALL prose. No inert-check, no `=>`-detection, no bare data-literal
   echo. The user accepted the cost: a bare value you mean to run must be wrapped
   in `(` (e.g. `(def x …)`/`(identity …)`), and the one-line warning teaches it.
   This is the most robust, least-complex rule; it SUPERSEDES PRESERVED DISSENT §1
   (the inert-binding-literal tradeoff is the explicit, accepted cost of dropping
   the minefield).

2. **#51 advisory scope + the "one more turn" mechanism (RECOMMEND: tightest
   scope; veto-the-halt).** (a) Advisory fires only when an outbound `:human`
   reply landed in the same turn as an ENVELOPE-VALUE sibling failure
   (recommended, against Gemini's overrule — avoids weak-model anxiety noise).
   (b) The narrow gate forces ONE more live turn after a delivered reply that
   cited a same-turn envelope failure (recommended — keeps the protection
   agent-facing and reactive) vs the alternative of appending the
   envelope-failure note to the human on the reply's OUTBOUND path. Neither may
   introduce a stored "you over-claimed" flag. (PRESERVED DISSENT §2, §3.)

3. **Cap: alias vs two named caps (RECOMMEND: two named caps + cross-ref
   comment).** The result-body cap currently equals `store-edn-cap=16384`; keep
   them as two independently-tunable defs with a comment, NOT an alias (so the
   LLM-facing cap can be tuned for token economy without moving the RAM
   ceiling). The drift that produced #53 is prevented by the comment. Plus the
   carried verification (NOT optional): confirm the largest core creation-eval
   (`store-inventory`) and the largest routine agent read leave room for 2-3
   prior turns under `transcript-char-budget=24000`. (PRESERVED DISSENT §5.)

4. **Retire `:seon.agent.message/force` permanently? (RECOMMEND: keep it.)**
   Under the narrow gate it still has a job — the deliberate "I am replying
   ABOUT the failure" escape. The theory's full-removal would have retired it;
   since we keep a narrow gate, `:force` stays. (Note: this reverses the
   theory's recommendation, because the gate it depended on for removal is no
   longer fully deleted.)

---

## Implementation plan (sequenced, minimal, building on #40-43 @ 304ef96)

Ordering is load-bearing: **#50 before #51** (so the gate only ever sees real
failures), caps and #49 are independent and can interleave.

1. **#50 + #52 parse fix — DONE (2026-06-22), FORMS-AND-PROSE-ONLY per
   DECISION §1 (supersedes the inert-OR-echo plan below).**
   `src/seon/repl/internal.cljc`: `narration-atom?` replaced by
   `prose-token?` (classify on `(seq? form)`, with a `:reader-macro`-tag
   refinement so `#inst`/`#uuid`/`#js`/`#?(…)` — which sexpr to a seq — are
   prose). A top-level `(…)`/`@x`/`'x`/`#(…)`/`#'x`/`` `(…) `` is a `:kind
   :form` (EVALUATED); scalars/symbols/tagged-literals are DROPPED (no
   entry, no `;;` echo — killing the `;;`-imitation trap); a top-level data
   literal (`{…}`/`[…]`/`#{…}`, `data-literal?`) is DROPPED but emits ONE
   `:kind :comment` warning (`demoted-literal-warning`, idempotent, surfaced
   as a derived note on the comment-only eval row via `format-eval-row` —
   no stored flag). `prose->comment-lines` deleted (dead). The false `:124`
   sentence + the whole prose-capture machinery removed. Same patch:
   `src/seon/ctx.cljs` EVAL MECHANICS + RESULT VARS prompt rewritten to the
   new runtime ((-forms run; bare data/text is a note — wrap to evaluate;
   never paste a printed `=>` value back), and `comment-lines` preserves the
   `⚠` glyph. #52 falls out: `(grants) => {…}` → `(grants)` runs, `=>` is
   dropped prose, `{…}` is demoted+warned (NO eval, NO `result/<id>`).
   Tests: `test/seon/repl/internal_test.cljc` rewritten (9 tests / 138
   assertions green) — `forms-and-prose-only`, `reader-macros-evaluate`,
   `multiline-form-is-one-eval`, plus the recovery/byte-faithful suites
   updated to the new contract.

   ORIGINAL (superseded) plan: add recursive `inert-datum?`; rename
   `narration-atom?` → `top-level-datum?`, extend to demote inert-or-`=>`-echo
   collection literals.

2. **#51 gate narrowing + advisory — DONE (2026-06-22).**
   `src/seon/agent/message.cljs`: the eval-error half of `batch-failure-lines`
   DROPPED; the envelope-value half kept + renamed to
   `turn-envelope-failure-lines` (over a passed turn) + `envelope-failure-lines`
   (current-batch turn). The `message!` write-refusal REMOVED — the reply ALWAYS
   transacts + delivers; the protection is now a LOOP-TERMINATION VETO:
   `same-turn-overclaim?` (loop forces one make-good turn) + the
   `overclaim-advisory-section` pure section fn (TIGHTEST scope — fires only when
   a user-facing reply landed in the prior turn alongside a `{*/ok? false}`
   envelope-value sibling failure; eval-errors and no-reply turns never fire).
   The veto wired at `agent.cljs run-agentic-loop!` (the `replied-since-inbound?`
   branch now checks `msg/same-turn-overclaim?` and `(recur)`s once instead of
   halting). Advisory section registered in `ctx.cljs core-default-ctx` at
   priority 42 (volatile tail). Prompt rewritten to the narrowed contract
   (reply ALWAYS lands; same-turn `{:seon.db/ok? false}` → one make-good turn
   with `<reply-over-claim-warning>`). `:force` kept (request-only, never stored,
   the deliberate "reply ABOUT the failure" escape; the docstring smell at the
   old 213-217 fixed — no "unverifiable is not verified" branch). #43 origin
   stamp PRESERVED (verified: a user-from `message!` stamps origin :human).
   The five guard tests in `test/seon/agent/message_test.cljs` rewritten to the
   narrowed contract; `test/seon/agent_context_test.cljs` section-list assertion
   updated for the new `:reply-over-claim` layout slot. Verified live (read-only,
   no agent drive) through the real `reply!` path: eval-error sibling + reply →
   delivers, no veto; envelope-failure sibling + reply → delivers AND veto fires
   AND advisory renders; envelope-failure with no reply → no veto; all-green → no
   veto. Full `bin/test-cljs` suite: 2 PRE-EXISTING failures remain
   (`ctx_test/selection-rules` + `index_core_test/core-ns-rows-carry-the-minimal-stub`
   — both the `seon.agent.todo` full-source-exemplar drift, UNRELATED to #51 and
   untouched by this change).

3. **#49 drain-on-exit** — `src/seon/agent.cljs`: do NOT narrow the latch;
   add drain-on-exit re-query (origin-aware, reads the message log) that recurs
   the loop while undrained inbound exists, releasing the latch only when the
   query is empty; keep the `.finally` release as the open-tx-failure fallback.
   `src/seon/web/serve.cljs handle-chat!`: INTAKE log per accepted message +
   fail-loud `console.warn` on any dropped wake.

4. **Cap split** — `src/seon/ctx.cljs`: delete `core-eval-render-cap` and the
   `:seon.ctx/core-authored?` routing (ctx.cljs ~561/705/707-727/748 +
   transcript.cljs ~60/74/120-123/214-215); at the render apply site (line
   561), cap `form-ln`/`out-ln` at 1500 and the result body at 16384 (=
   `store-edn-cap`, as a separate named cap with a cross-ref comment, not an
   alias). Keep `store-edn-cap`, `message-render-cap`, `transcript-char-budget`,
   `cap-result-body`.

5. **Batch verification** — full `.cljs` suite via `bin/test-cljs` ONCE at the
   end. Then ONE bounded live drive (DeepSeek pre-authorized; pod is shared
   single-threaded — analyze from code, do not fire overlapping `run-tests`):
   confirm (#50) inert `{..}`/`[..]`/`#{..}` renders as a `;;` comment with no
   result/no error/no `result/<id>`, a literal with a nested call still evals,
   a real list-form error still surfaces; (#51) a thrown sibling + valid reply
   delivers AND the envelope-failure sibling forces a live advisory turn; (#49)
   a message in the post-`:idle` tail window gets a turn; (#53) a ≤16384 result
   renders whole and `form-ln`/`out-ln` stay 1500; and verify `store-inventory`
   + the largest routine agent read leave room for prior turns under 24000.

### Loop-coherence — UNTOUCHED, correct-by-construction

`run-agentic-loop!` (`agent.cljs:1457-1524`) is NOT modified. Verified its
STOP logic: `:error` → return; `replied-since-inbound?` → halt `:replied`
(FIRST); `turns-cap` → `:cap-hit`; `eval-count == 0` → bounded nudge →
`:no-visible-output`; ELSE `(recur)`. `eval-count = n-ok + n-fail`
(`agent.cljs:1116-1117`) stays — counting failures as progress is load-bearing
(gym S-12, verified comment 1107-1115). After the four fixes, the loop's three
reads (`eval-count`, `replied-since-inbound?`, `turns-cap`) all see truthful
data: #50 stops prose inflating `eval-count`; #51 lets a valid reply always
transact so the halt fires by construction (with the narrow envelope veto
adding at most one live turn); #49 ensures every accepted message gets a turn.
Each fix is at the point where wrong data is first created — not a patch on the
loop.
