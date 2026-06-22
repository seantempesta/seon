---
type: prd
status: active
tags: [prd, agent]
---

# Reliability bug hunt #2 — #49–53 (root-caused + designed; QUEUED after #40–43)

Root-cause traced + verified against live code (2026-06-21, hunt #2: false-correction,
long convo, impossible-action, ambiguous, prompt-injection, false-premise). Execute
**after** the #40–43 batch commits (see [[reliability-fixes-40-43-2026-06-21]]) — shared
files (eval.cljs, agent.cljs, message.cljs, repl/internal.cljc, ctx.cljs), so sequence to
avoid entanglement. **#50 is the root cause and goes first.** NEVER name the downstream —
use `acme`.

## Two principles (keep it simple + NON-FRAGILE)
Nothing depends on the model emitting new syntax — **fences were REJECTED**: one non-compliant
turn where the model forgets the fence would stop ALL its code from running = demo-killer.
Everything reduces to two principles, mostly DELETIONS + scoring tweaks:
1. **OUTPUT** — our functions return clean DATA; oversized output is clipped to a summary, the
   full value stays in `result/{id}`. *(#40/#41 in the running batch; #53 here.)*
2. **INPUT/DELIVERY** — only code the agent submits runs; in-turn errors (prose misreads,
   exploratory failures) are **ADVISORY** (shown, never scored as turn-failure, never blocking
   a reply, never re-waking); a valid reply always delivers; an accepted message always gets a
   turn. *(#50/#51/#49.)*
The batch should end up SIMPLER than today, with no new contract the model can violate.

## Root causes (verified)

| Bug | Root cause | Key sites |
|-----|-----------|-----------|
| **#50** prose-as-eval (ROOT) | the WHOLE LLM completion is parsed+evaluated as one REPL transcript; a clean-reading `(names emails)`/`{…}` in prose is read as code, scored as a failed/empty eval, rendered as `=>✗` → drives self-wake (#47), re-processing (#43) | `agent.cljs:1091-1098`, `repl/internal.cljc:128-140` (`narration-atom?` rescues only bare atoms), `ctx.cljs:506-615` |
| **#49** dropped /chat intake | ack-before-enqueue race: the wake fires synchronously in the own-tx writer go-block, gated by `!kick-scheduled` held for the WHOLE loop; state flips `:idle` (close-tx) BEFORE the loop returns → a msg in that tail window passes the state guard but the latch rejects it, never re-drained | `agent.cljs:554/608-621/1053`, `serve.cljs:428-449`, `writer.cljc:258` |
| **#51** batch-poison | `batch-failure-lines` (message.cljs:191-224) flags ANY earlier `:seon.eval/ok? false` row → `message!` REFUSES the terminal valid `reply!` | `message.cljs:285-299`, `212-216` |
| **#52** `=>` fabrication | #44 is a render-time string scrub, but a fabricated `(grants) => {…}` has its trailing `{…}` read as a self-evaluating `:kind :form` at EVAL time → real `stash-result-raw!`+`bind-result-var!`+`result/<id>`+`:seon.eval` row → replayed as runtime-real | `repl/internal.cljc:128-140/286-287`, `eval.cljs:2367-2373`, `ctx.cljs:434-439` |
| **#53** success truncation | policy mismatch: the 1500-char agent `eval-render-cap` (vs 50000 core) clips a legitimate ≤16384 `store-inventory`/all-events read MID-VALUE; agent re-queries around the clip | `ctx.cljs:300-307/561/593`, `core-authored-turn?` 707-727 |

## 1. #50 — prose never scored as a failed eval (do FIRST; NON-FRAGILE, no fences)
**Rejected: an explicit eval fence.** Mandatory fences make the model's compliance load-bearing
— one turn it forgets the fence → none of its code runs → demo-killer. The robust fix is a
substrate-side SCORING change that depends on ZERO model behavior:
- Prose/narration that fails to PARSE (a `[source: …]` read-error) is **SILENTLY SKIPPED** —
  never a `:seon.eval/ok? false` row, never `n-fail++`, never rendered as `=>✗`.
- A token that DOES parse but errors (incl. prose that happens to read as a form, e.g.
  `(names emails)` → "names is not defined") is **ADVISORY**: the agent still SEES the error in
  the transcript (real code failures stay visible), but it does NOT count as a turn-failure,
  does NOT re-wake the agent, and does NOT block delivery.
- A turn that called `reply!` HALTS regardless of any incidental form error (= #43 halt + #51).
- **Sites:** `agent.cljs` ask-and-eval-reply (keep prose/error rows OUT of the re-wake /
  turn-fail signal), `eval.cljs` eval-batch (skip-don't-score unparseable prose), `ctx.cljs`
  format-eval-row (render an in-turn error as advisory context, not a "your turn failed" banner).
  **No new `:kind`, no parse-time contract, nothing the model must comply with.**
- **Converges #50 + #51 into ONE rule:** in-turn errors (prose misreads, exploratory failures)
  are advisory — shown, never scored as turn-failure, never blocking a reply, never re-waking.
- **Interaction w/ #40-43:** #41 fixes the VALUES side (render-result-edn), #50 the scoring/READ
  side — complementary; #50's eval-batch edit rebases on post-#40-43 eval.cljs.

## 2a. #51 — delivery (fix A: advisory, not gate)
- **`message.cljs:285-299`:** remove the blocking refusal — always transact when content+from
  valid. **`batch-failure-lines` (191-224):** becomes a pure derivation feeding a reactive
  render section ("your reply landed AND these sibling forms failed: …") — no stored flag,
  self-healing. **Retire `:seon.agent.message/force`** (no refusal left to override).
  **Prompt (ctx.cljs ~1053-1057):** rewrite the policy line. **Rewrite `test/` REFUSED/force tests.**
- **Interaction:** land on top of #43's `message!` origin stamp (insert in the `:else` region,
  preserve the stamp); #43 chose NOT to gate on reply!, so no collision.
- **USER DECISION:** confirm fix (A) full-removal+advisory vs. (B) a narrow surviving gate —
  hinges on whether a real false-user-claim incident exists or was only theorized (check
  `fix-everything-prd-2026-06-11 §1 ROOT-3`). Recommend (A) (reactive-context aligned).

## 2b. #49 — intake (narrow latch + post-loop drain)
- **`agent.cljs` inbound-message-handler:** clear `!kick-scheduled` at the FIRST turn's
  `:running` flip (not loop end); `:running` is the steady-state in-flight guard thereafter.
- **`run-agentic-loop!` exit:** clear the latch FIRST, then re-query un-drained inbound using
  **#43's origin-aware predicate** (`to∋me ∧ from≠me ∧ origin∈{:human,:agent} ∧ hops<cap`,
  newer than baseline, not yet answered); re-schedule the loop if any, loop-drain until empty.
- **Observability:** intake log per accepted msg in `serve.cljs` handle-chat! (before 204);
  fail-loud `console.warn` in the dropped-wake branch.
- **Interaction:** DIRECT overlap with #43 (same predicates) — sequence STRICTLY after #43 Task D.
- **Risks (mitigated):** don't re-introduce the double-loop (narrow latch to first `:running`);
  drain racing the clear (clear-then-drain-to-empty); drain fighting `:replied` halt (drain only
  on waking-origin msgs newer than baseline, not yet answered).

## 3. #52 — fabricated `=>` echo (CUT the quarantine; DEFER or one-line net)
The `:kind :echo` parse-time quarantine is **CUT** — over-built, and the LOWEST-severity of the
five: a fabricated `(x) => {…}` self-evaluates to a value the model itself wrote; the harm
(citing it later as runtime-verified) is a subtle correctness issue, NOT a crash/demo-breaker.
Two simple options, no new machinery:
- **(a) DEFER past the demo (recommended)** — the existing #44 render-scrub stays as the net for
  pure-text `=>` claims; #50's advisory scoring also stops the fake echo's `=>✗` from scoring.
- **(b) one-line net (if cheap):** in the eval dispatch, do NOT mint a `result/{id}` for a bare
  self-evaluating literal that *immediately trails* a `=>`/`⇒` token — a single skip in that one
  shape, no `:kind` taxonomy.
Revisit fully only if it actually surfaces in a demo.

## 4. #53 — success truncation cap / pagination
- **`ctx.cljs:300-307` + the split ~561:** raise the agent display cap to the store cap
  (`store-edn-cap` = 16384 — the true per-row ceiling; a runaway pull is already store-clipped).
  **Collapse the 1500/16384/50000 three-tier** to one (16384) or two meaningful tiers.
- **`cap-result-body` (354-385):** when a success still exceeds the cap, emit an ACTIONABLE
  pointer (`(count result/<id>)`, `(take 20 result/<id>)`, store-inventory kind-filter) and
  clip at a ROW boundary, never mid-token.
- **`eval.cljs` render-result-edn (~1963-2001):** add a SECOND trigger — row-bounded preview +
  guide when the projection exceeds the display cap even at ≤50 rows (project first, then size).
- **Interaction:** #40's compact `transact!` removes the biggest accidental trigger; design
  assuming transact! is already compact. Rebase on post-#40-43 render-result-edn.
- **USER DECISION:** collapse to one 16384 cap vs. keep 16384/50000; whether a total-context
  sum-across-rows budget is needed for long sessions.

## 5. Sequenced execution (after #40–43 commits)
1. **#50** prose-advisory scoring — `agent.cljs`, `eval.cljs`, `ctx.cljs` (no parse contract, no `:kind`).
2. **#51** delete the delivery gate (advisory) — `message.cljs` on top of #43's origin stamp + `test/` rewrite. *(Converges with #50: both = in-turn errors are advisory.)*
3. **#49** intake race — `agent.cljs`/`serve.cljs`, strictly after #43 Task D (same handler).
4. **#53** truncation cap — `ctx.cljs`/`eval.cljs`, rebase on post-#40-43 render-result-edn.
5. **#52** DEFERRED (or the one-line net) — lowest severity, not demo-critical.
Shared files now touch different fns/regions; `repl/internal.cljc` is largely UNTOUCHED (no fence/`:kind` parse rewrite). eval.cljs: #50 scoring / #53 render — different fns. agent.cljs: #50 scoring vs #49 wake — different regions.

## 6. Verification
Per-leg read-only code-reasoning + `bin/test-cljs` ONCE at the checkpoint (never overlapping
run-tests in the live pod). Then ONE bounded live DeepSeek drive (standing permission; ONLY
after #43 + all of #49–53 land; pod restarted): (1) /chat → turn enqueued + prose reply not
read back as a failed eval (#50); (2) thrown sibling + valid reply! → delivers, sibling shows
advisory next turn (#51); (3) a SECOND /chat in the tail window → reliably produces a turn, no
resend (#49); (4) fabricated `=> {…}` → no result/<id>, downstream doesn't cite it (#52); (5)
`store-inventory` renders whole/row-clipped, not mid-token (#53).

## 7. Open decisions for the user
1. **#50:** DECIDED — advisory scoring, NO fences (non-fragile; nothing the model must comply with).
2. **#51:** confirm full gate-removal + advisory render (rec) vs. a narrow surviving gate — hinges on whether a real false-user-claim incident exists (check `fix-everything-prd-2026-06-11 §1 ROOT-3`). Retiring `:force` is part of removal.
3. **#52:** defer past the demo (rec) vs. the one-line net now.
4. **#53:** one 16384 cap vs. 16384-agent/50000-core two-tier; total-context sum-cap for long sessions?

## 8. Flagged gaps (not papered over)
- #50: confirm an in-turn error rendered "advisory" still gives the agent enough signal to fix code it genuinely INTENDED to run (it should — the error stays visible; it just doesn't re-wake/turn-fail). Validate on the live drive.
- #49 exact tail-window width unconfirmed; other `message!` callers (agent↔agent) not enumerated.
- #51 whether the historical false-claim was ever live (drives full-removal vs. narrow gate).
- #53 all-events return shape (counted vector vs lazy seq) unconfirmed; total-context budget unknown.
