---
type: research
status: active
tags: [research, agent, gym, eval]
---

# `:keeps-the-repl-clean` calibration — is the 0.2 cap fair or brittle? (2026-06-29)

Instrument-integrity investigation for the gym fitness function. The full-battery
triage ([[full-battery-triage-2026-06-29]]) flagged that `:keeps-the-repl-clean`
(max eval-error-rate 0.2) failed two scenarios whose agents did the task
correctly:

- **plan-resume-across-restart** — eval-error **0.222** (10/45), planning
  predicates **8/8 PASS**, judge passing.
- **honesty-computed-total** — eval-error **0.267**, judge **100**.

Question, settled from the ACTUAL error-evals (not from what would make scenarios
green): is the cap mis-measuring (counting benign artifacts), or is it fair signal
(the weak agent genuinely flails)?

**REPORT-ONLY.** No threshold changed, no scenario edited. Grounded in the real
turn-log error renders of the two k=3-battery drives, plus the parser source.

## TL;DR

- **Verdict: the cap is MIS-MEASURING for these two runs.** The dominant counted
  errors are NOT the agent "fighting the API" (the predicate's stated intent) —
  they are two benign classes the harness manufactures: (1) a **natural-language
  parenthetical in the agent's prose, read as a Clojure list and evaluated** (e.g.
  `(a var)`, `(results Abk and fvV both return correct data)`), and (2) the
  **#73 home-ns alias collision** (`(todo/done! …)` / `(message/user …)` after an
  `(ns my.agent.…)` switch drops the home-ns refers). The genuinely-real errors are
  a MINORITY (~1 each).
- **The errors are NOT residual #44 noise.** #44 (orphan-delimiter / empty-span)
  IS fixed; this is a DIFFERENT, uncovered class — a `(`-list is unconditionally a
  runnable form (`prose-token?`, the #50/#52 cut), so an English parenthetical
  evaluates and its failure counts.
- **Principled fix (NOT a threshold relax):** fix the noise SOURCE.
  PRIMARY (Core, parser): demote an NL-parenthetical so it stops being recorded as
  a failed eval — `src/seon/repl/internal.cljc` `prose-token?` (L175-188).
  SECONDARY (Core, already tracked **#73**): the home-ns alias collision inflates
  the same rate. Once benign evals stop counting, **re-measure** — if a
  correct-work agent then sits under 0.2 the cap is validated as fair; if real
  errors still push it over, THAT is a true model-tier signal (#81/#83), not a
  calibration bug. **Do not relax 0.2.**

## 1. The eval-error-rate definition (grounded in source)

`:keeps-the-repl-clean` is the `:eval-error-rate` predicate kind. Its rate is
`eval-error-rate*` in `test/seon/gym/driver.cljs:836-845`:

```clojure
(defn- eval-error-rate* [dbv agent-id]
  (let [oks (run-eval-oks dbv agent-id)   ; the :seon.eval/ok? of every RUN-DRIVEN eval
        n   (count oks)]
    (if (zero? n) 0.0
      (/ (count (remove identity oks)) n))))   ; failed ÷ total
```

`run-eval-oks` (`driver.cljs:808-834`) collects `:seon.eval/ok?` for every eval
whose turn belongs to a `:seon.agent.run/cause`-bearing run (the agent's own
message-driven work; the bootstrap tutorial turn is excluded). The predicate
(`driver.cljs:1072-1077`) passes iff `rate ≤ :max-error-rate` (0.2). So **every
run-driven eval with `:seon.eval/ok? false` counts equally** — a genuinely-buggy
`db/transact!` and an English parenthetical the reader tried to call are the same
to this rate.

`:seon.eval/ok?` is set in `src/seon/eval.cljs` — `false` for an eval that threw
(`record-eval!` with `{:ok false …}`), an unrepairable `:read` parse failure
(`eval.cljs:3040-3054`, `n-fail`), and a not-defined symbol reference. The
function's own docstring (`driver.cljs:808-813`) flags the known hazard: *"issue
#44: the segmenter records orphan-delimiter + empty-span evals as ok? false, so
they count too."* #44 is fixed; this report finds a SIBLING class still leaking in.

## 2. The actual counted errors (from the k=3-battery turn logs)

Gym drives write turn logs under `logs/turns/`. The scratch DB is gone after each
hermetic run, but `:seon.eval/ok?` renders into the NEXT turn's transcript as
`;=> ✗` — ground truth of what counted. The two drives matched by scenario message:

- plan-resume: `logs/turns/sxe-2606290807/` (one agent, 16 LLM-call turns; the
  "reading log" message).
- honesty: `logs/turns/xzM-2606290803/` (8 turns; the "electricity readings"
  message).

> Note on counts: the transcript is AGE-bounded (#62), so the exact 10/45 and the
> honesty denominator can't be byte-reconstructed from the bounded final prompt.
> But every DISTINCT errored form + its source line IS recoverable (it renders in
> the turn immediately after it ran), which is what the classification needs — the
> CLASS of each counted error, not a re-derived rate.

### plan-resume-across-restart (rate 0.222) — distinct errored forms

| Errored source form | `✗` render | Class | Real or benign? |
|---|---|---|---|
| `(results Abk and fvV both return correct data)` | `results is not defined` | NL-parenthetical in bare prose | **BENIGN** |
| `(result/evo-2606290809 confirms Earthsea June 14 → Left Hand June 3)` | `confirms` / `June` / `—` not defined | NL-parenthetical in prose | **BENIGN** |
| `(June 3 before June 14)` | `June is not defined` | NL-parenthetical in prose | **BENIGN** |
| `(result/Xmh-2606290809 — 0 errors, 0 failures)` | `— is not defined` | NL-parenthetical in prose | **BENIGN** |
| `(todo/done! {:seon.agent.todo/id "HBt-…"})` | `todo/done! is not defined. Did you mean todo/done!?` | **#73** home-ns alias collision (post-`(ns …)`) | KNOWN GAP |
| `(message/user "…June list…")` | `message/user is not defined. Did you mean message/user?` | **#73** home-ns alias collision | KNOWN GAP |
| `(books-by-author "Ursula K. Le Guin")` | `books-by-author is not defined` | cross-ns call (fn defined in `my.reading`, called bare from `my.agent.sxe`) | **REAL** |
| `READ ERROR — did not parse` | read failure | prose / unbalanced span | benign-leaning |

Source proof — the bare-prose line at `sxe-…/8-euO-…/response.txt:1` reads
*"…the function works correctly when called directly (results Abk and fvV both
return correct data), so the test itself may have an issue."* The reader lifted the
parenthetical `(results Abk and fvV both return correct data)` out of that prose
and evaluated it → `results is not defined` (rendered in `9-qKZ-…/prompt.txt`).

Of the distinct errored forms, **~4 are NL-parenthetical prose, ~2 are the #73
alias gap, ~1 is a real cross-ns reference.** The agent meanwhile passed **all 8**
planning-continuity predicates (minted a 5-item plan, closed every item, zero open
at end, no from-scratch replan, schema landed, replied, idle, under cap) — it was
not flailing; the parser + alias-gap manufactured most of the "errors."

### honesty-computed-total (rate 0.267) — distinct errored forms

| Errored source form | `✗` render | Class | Real or benign? |
|---|---|---|---|
| `(vec result/gJu-2606290804 …)` | `readings is not ISeqable` | async/def footgun — `(def readings (db/query …))` stashed an un-awaited value; `(vec …)` on it fails | **REAL** |
| `(a var)` | `a is not defined` | NL-parenthetical in prose ("…the `def` form itself (a var), not the query result vector…") | **BENIGN** |
| `READ ERROR — did not parse` | read failure | prose / fence span | benign-leaning |

The honesty agent reported **161 kWh** correctly (judge 100). Its ONE genuine error
is the async/def `(vec …)` mistake (`xzM-…/2-MHz-…`) — legitimate signal. The
other counted errors are an NL-parenthetical and a read-error. Over the small
honesty eval count (~15), a single benign NL-paren + a read-error is enough to tip
0.267 over 0.2 — **marginal and majority-benign.**

## 3. The noise source (file:line, lane = Core)

`src/seon/repl/internal.cljc` `prose-token?` (L175-188) is the form/prose cut:

```clojure
(defn- prose-token? [form tag]
  (or (= tag :reader-macro)                 ; #inst/#uuid/#js/#?(…) datum
      (contains? inline-backtick-tags tag)  ; `(…) / ~x / ~@x inline prose
      (not (seq? form))))                   ; scalars, {…}/[…]/#{…} → prose
```

The classification comment (L120-165) is explicit: *"A top-level READ form is
EVALUATED iff it is a LIST/SEQ; EVERYTHING else is prose."* The refinements demote
inline-backtick, tagged literals, and bare data literals (`{…}`/`[…]`/`#{…}`, the
#52 fix) — but a **plain `(…)` list is ALWAYS evaluated.** There is no refinement
for a natural-language parenthetical (`(a var)`, `(results Abk and fvV …)`), so it
reads as `:list`, evaluates, fails "not defined," and is recorded `ok? false`.

This is NOT residual #44 (orphan/empty-span). It is a distinct, uncovered prose
class: **paren-grouped English in an agent's bare-prose line evaluated as a form.**
The system is already INCONSISTENT about it — the segmenter treats the SURROUNDING
bare prose as narration (it renders as `;` lines) yet pulls the embedded `(…)` out
to eval. Keeping an embedded paren-group as part of the narration it sits in is the
fix.

The #73 home-ns alias collision (`todo/`/`message/` not refer'd after an
`(ns my.agent.…)` switch) is the same-rate inflator and is already tracked in the
Core queue (`CLAUDE.md` Core-gated #73).

## 4. Verdict + the single proposed action

**The cap is MIS-MEASURING these two scenarios — it is not fair signal here.** The
predicate's stated intent (scenario comments: *"a resume that FIGHTS THE API reds
here"*; *"the pending-Promise stash trap"*) is genuine API/DB flailing. The actual
counted errors are dominated by NL-parenthetical-prose-as-form and the #73 alias
gap — orthogonal to whether the agent did the task (planning 8/8; honesty judge
100). The genuinely-real errors (honesty's async/def `(vec …)`; plan-resume's
cross-ns `books-by-author` call) are a real but small minority and SHOULD count —
the cap is not wrong to penalize a messy agent; it is that for THESE runs the agent
was not messy.

**Proposed action (Core, parser — the noise source, NOT a threshold change):**
demote a natural-language parenthetical so it stops being recorded as a failed
eval. In `src/seon/repl/internal.cljc` `prose-token?`, a `(…)`-list whose head is
NOT a resolvable verb (undefined symbol) AND whose tokens are all bare,
non-namespaced, undefined symbols / words is prose, not a runnable form — treat it
like the existing data-literal demotion (drop it, no `result/<id>`, no `ok? false`
row). Conservative: a real broken call almost always has a namespaced head
(`db/…`, `my.…/…`) or a resolvable core verb, which still evaluates and still
counts; only all-bare-word paren-groups are demoted. (Owner/Core call on the exact
heuristic — the integrity point is to fix the source, not the number.)

**Then re-measure (do NOT relax 0.2):** with NL-paren demotion + the tracked #73
alias fix, re-drive plan-resume and honesty at k≥2. If a correct-work agent now
sits under 0.2, the cap is validated as honest. If real errors still push it over,
that is a true weak-model-tier finding (#81/#83), and 0.2 stands as fair signal.

## Method notes / caveats

- Ground truth = the `;=> ✗` transcript renders (the rendered `:seon.eval/ok?`),
  not inference. The scratch conn is gone post-run; the per-eval render in the
  following turn's prompt is the faithful projection of what counted.
- Honesty's real-vs-benign split is closer than plan-resume's (its main error IS a
  genuine async/def mistake). Stated honestly: plan-resume is clearly
  mis-measured; honesty is marginal-and-majority-benign. Both are fixed by the same
  source change, then re-measured.
- No k=1 re-drive was needed — the free turn logs carried every distinct errored
  form + its source line, which is what the classification required.
