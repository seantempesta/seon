---
type: research
status: active
tags: [research, agent]
---

# Scorer false-negative audit — is exact-match deflating the measured ceilings?

**Date:** 2026-07-12 · **Question (owner-directed, adversarial):** the
KT3/KT3b useful-match metric compares predictions against what the agent
HISTORICALLY did next — but multiple next actions are often equally
valid. If reasonable-but-different is common among zero-scored
predictions, every measured ceiling (.26–.38) is deflated and low scores
are misread as incapability. Measure the scorer's FALSE-NEGATIVE rate. ·
**Data:** the KT3/KT3b raw scored runs (`src-needle/data/kt3{,b}/`) over
`data/tune/acme-2026-07-12.jsonl` · **Script:**
`src-needle/scripts/fn_audit.py` (sample / judge / selftest / report;
stdlib) · **Judges:** `muse-spark-1.1` (`reasoning_effort minimal`) +
`deepseek-v4-pro` (thinking ENABLED), blind-then-target two-pass, human
adjudication of every disagreement (rulings pre-registered before seeing
judge verdicts) · **Raw outputs:** `src-needle/data/fn-audit/`
(gitignored) · **Spend:** $0.46.

## TL;DR

- **The hypothesis is confirmed for the frontier arm and refuted for
  the small-model arms.** Of DeepSeek's zero-scored predictions, **40%
  are reasonable alternatives** (8/20 judged; Wilson 95% .22–.61) — the
  agent's own context prescribes the predicted act. Of the 1.5B
  instr-few zeros: **0/14** (CI 0–.22). StarCoder2 base: **0/6** (CI
  0–.39). Exact-match deflates the FRONTIER ceiling specifically; small
  models' zeros are real errors.
- **Corrected DeepSeek ceiling ≈ .36–.46** (measured .261 + judgeable
  zero-mass .500 × FN rate .40 = **.461** treating a
  reasonable-alternative as full credit, CI .37–.57; half credit →
  .361). That moves KT3's headline from "below the <30–40% kill band"
  to **at-or-above the band floor** — the STOP verdict's arithmetic
  basis softens, though the coverage-gap fix lane stands (see the
  gradient caveat: 3/4 judged low-coverage frontier zeros were the
  prescribed plan act, so the low-cov bucket's ".105 = noise" reading
  is partly scorer artifact).
- **KT3b's headline "1.5B ties the frontier" dissolves under
  plausibility judging.** The tie (.265 vs .261) compared raw
  exact-match; the frontier's zeros are 40% reasonable alternatives
  while the 1.5B's are 0% — corrected, the frontier leads ~.36–.46 vs
  ~.27. The $0-baseline bar for B3 should still be the 1.5B number, but
  any vehicle-swap reading of "tie" should know it is a scorer
  artifact.
- **All 8 false negatives share one mechanical signature:** every one
  is a `my.plan`-family call whose every id is visible in the context —
  6/8 are literally `(my.plan/active! {:my.plan/id "<the plan block's
  own → next ready id>"})`, i.e. the act the projection ITSELF
  prescribes. The signature alone has only .47 precision (9/17
  signature-matching judged items are real errors — redundant
  re-activation of the already-▶ step, `done!` on a message id,
  template copies), so the fix needs the plan-block STATE, not just
  the text.
- **Scorer self-test: PASS.** All 214 targets score exactly 1.0 as
  their own predictions — no identity-path bugs. Separately, 3/214
  targets are junk (prose parsed as calls; rows 31/184 are pure junk —
  no prediction can score >0 on them).
- **Recommendation:** keep the mechanical scorer as the core; do NOT
  put an LLM judge inside offline useful-match. Add a
  mechanically-derived **prescribed-act accept-set** (from the plan
  block: `active!` on the next-ready id when nothing is ▶ active,
  `done!` on the ▶ step, the read probes) — it covers 8/8 observed FNs
  at $0 — plus the dataset fixes. Reserve LLM plausibility judging for
  periodic calibration of band-gated verdicts (~$0.50/40 items), as
  done here.

## Method

### Sample

40 zero-scored predictions (`useful == 0.0` under the KT3 scorer),
stratified across three arms and, within arm, proportionally by the
target's primary substantive form-kind (seeded draw, seed 42):

| arm | zero-useful rows | judgeable | sampled |
|---|---|---|---|
| KT3 DeepSeek-v4-pro (n=214) | 132 (.617) | 107 | 20 |
| KT3b Qwen2.5-Coder-1.5B-Instruct instr-few (n=211) | 116 (.550) | 81 | 14 |
| KT3b StarCoder2-3B base cont (n=214) | 173 (.808) | 71 | 6 |

"Judgeable" = the prediction parses AND contains at least one call AND
the target has a substantive (non-ns-move) call. The excluded remainder
is auto-classified, not ignored — it stays zero in the corrected
arithmetic as true negatives:

- **parse-fail** (3 / 26 / 12 per arm) and **no-call** predictions
  (1 / 3 / 78 — the base-model display-grammar mimicry) cannot be
  reasonable alternatives by construction;
- **pure ns-move targets** (21 / 6 / 12): rows whose target is only
  `in-ns`/`require` boilerplate — the substantive lens already excludes
  them; leaving them out of the FN universe makes the correction
  conservative (some of those predictions may also be reasonable).

### Judging protocol

Two independent LLM judges per item, two stateless passes each:

1. **Blind pass** — the judge sees the row's context (verbatim), the
   cards, and the candidate prediction; NOT the target. It answers
   `reasonable` (would a competent seon agent plausibly evaluate this
   next, in this situation?) and `valid` (parses, real fns from the
   visible world, sane args). The rubric pins "competent" to the
   system's visible idioms so generic in-memory Clojure is not
   over-credited.
2. **Target pass** — a separate stateless call WITH the historical
   target, classifying the relationship: `reasonable-alternative` /
   `premature-but-sensible` / `wrong-but-related` / `nonsense`.

Final per-judge label gates the target-pass category on the blind pass:
a `reasonable-alternative` claim is downgraded unless the judge ALSO
found the candidate reasonable blind. The 10 judge disagreements were
adjudicated by reading each case; the adjudication rulings were
pre-registered before any judge verdict was read (scratchpad copy) and
refined only with mechanical evidence (id-visibility greps against the
full context, which corrected two of my own tail-only misreads).

**False negative** = a zero-scored prediction whose final category is
`reasonable-alternative`.

### Judge agreement

| lens | n | raw agreement | Cohen's κ |
|---|---|---|---|
| blind `reasonable` (binary) | 39 | .897 | .77 |
| final category (4-way, gated) | 40 | .75 | .53 |

Substantial agreement on blind reasonableness; moderate on the 4-way
category — the disagreement mass sits almost entirely on the
`reasonable-alternative` / `premature-but-sensible` /
`wrong-but-related` boundaries, which is why the human pass adjudicated
all 10 splits. Protocol warts, for the record: Muse's hidden reasoning
burned a 1024-token cap to empty content on 4/40 items (re-fetched at
4096); DeepSeek emitted one category label contradicting its own
rationale (case deepseek:42 — labeled `nonsense` while the rationale
describes a reasonable alternative; its blind pass said reasonable) and
one null `reasonable` field. LLM judges need exactly this kind of
gating + adjudication — one more reason not to put one inside the
offline metric.

## Scorer self-test — PASS (with a dataset caveat found on the way)

All 214 dataset targets scored as their own predictions:
**214/214 rows score useful = 1.0 exactly** (the task asked for 20; the
full population is free and strictly stronger). No identity-path bugs:
greedy pairing, map-key credit, nested-call collection, and the
`::`-auto-resolve path all behave on the identity case. Result file:
`src-needle/data/fn-audit/selftest.json`.

The caveat the self-test cannot see: **3/214 targets are junk** — prose
the mined agent happened to eval, parsed as calls:

- row 31: `(root azm-2607112358, 3 steps, multi-session)` — pure junk:
  NO prediction can score above 0 on this row;
- row 184: `(The plan has been laid down)` — same, pure junk;
- row 179: `(which is incorrect)` heading otherwise-real forms (KT3
  already flagged this one).

Rows 31 and 184 are structural false negatives independent of any
judge; both landed in the audit sample by chance and are counted in the
FN rate. Dataset hygiene: drop or re-mine targets whose call heads
resolve to nothing.

## Results

### False-negative rates (final, adjudicated)

| arm | judged | FN (reasonable-alternative) | rate | Wilson 95% |
|---|---|---|---|---|
| DeepSeek (KT3) | 20 | 8 | **.40** | .22–.61 |
| 1.5B-Instruct instr-few (KT3b) | 14 | 0 | **.00** | 0–.22 |
| StarCoder2-3B cont (KT3b) | 6 | 0 | **.00** | 0–.39 |
| pooled | 40 | 8 | .20 | .11–.35 |

The asymmetry is the finding. The frontier model, when it "misses," is
half the time doing the situation's other sensible act — most often the
act the plan block itself prescribes. The small models, when they miss,
are re-evaluating a form that just failed or just succeeded (14/32
non-RA judged items repeat a form verbatim visible in the context),
copying instruction templates with `"…"` placeholders, inventing fns
(`my.plan/plan!`), or pasting exemplar-leaked ids — real errors the
metric correctly punishes.

### The 8 false negatives — one signature

Every FN is a `my.plan`-family call with all emitted ids visible in the
context; 6/8 are `active!` on the plan block's "→ next ready" id (the
block's standing instruction says verbatim: *take one up with
`(my.plan/active! {:my.plan/id "<id>"})`*), 1 is `my.plan/document` on
the worker id the ask names, 1 is `active!` on the open ✉ step before
answering it. Coverage: 3/8 sit in rows with ingredients-coverage <.25
(the metric measures TARGET-ingredient visibility, and these targets
were exploratory probes ungrounded in the projection — while the
PREDICTION's ingredients were fully visible).

The mechanical signature (pure plan-family calls, all ids
context-visible) captures 8/8 FNs but matches 17 judged items — **.47
precision**. The other 9 are: re-`active!` of the already-▶ step
(deepseek:191/194), `done!` on a message event id (deepseek:33),
`done!`-immediately-after-`active!` bundles, step-duplication of steps
the block already shows, and the placeholder-template copy. So a
scoring fix must read the plan-block state (next-ready id, ▶ marker,
existing step titles) — all present in the row's context text — not
just match fn names.

### Per form-kind (final categories, by the TARGET's primary kind)

| target kind | n | reasonable-alternative | premature-but-sensible | wrong-but-related |
|---|---|---|---|---|
| plan | 14 | 3 | 1 | 10 |
| other | 13 | 4 | 1 | 8 |
| query | 6 | 0 | 0 | 6 |
| register | 2 | 1 | 0 | 1 |
| defn | 4 | 0 | 1 | 3 |
| transact | 1 | 0 | 0 | 1 |

The owner's conjecture ("plan-step mismatches are mostly
reasonable-alternatives while register mismatches are real errors")
holds in a sharper form: it is the **prediction's** kind, not the
target's, that separates them. FN cases are plan-idiom predictions
regardless of target kind (the register-target FN is a plan act
predicted where history registered a schema). Query-target zeros are
0/6 reasonable — they are verbatim repeats of just-run or just-failed
forms. Defn-target zeros are real errors everywhere (consistent with
every prior defn reading).

### Corrected-ceiling arithmetic (honest, with the assumptions visible)

Correction model: `corrected ≈ measured + judgeable-zero-mass × FN-rate
× credit`, where credit is what a reasonable-alternative would score
under a metric that accepted it. Judgeable-zero-mass uses the
107/81/71 judgeable rows (parse-fails, no-call rows, and
pure-ns-move-target rows stay at 0 — conservative). Error bars
propagate the FN-rate Wilson interval only (the dominant term at n=20).

| arm | measured | zero mass | judgeable mass | FN rate [95%] | corrected (credit=1.0) | corrected (credit=0.5) |
|---|---|---|---|---|---|---|
| DeepSeek | .261 | .617 | .500 | .40 [.22–.61] | **.461** [.37–.57] | .361 |
| instr-few | .265 | .550 | .384 | .00 [0–.22] | **.265** [.265–.35] | .265 |
| StarCoder2 cont | .121 | .808 | .332 | .00 [0–.39] | **.121** [.121–.25] | .121 |

Same correction on the row-level headline "useful ≥ .5" for DeepSeek:
.266 + .500 × .40 = **.466** of rows would carry an acceptable
suggestion.

What this does and does not change:

- **KT3's STOP-verdict arithmetic softens.** The <~30–40% band read
  .261 as below the floor; the corrected point sits at .36–.46. Even
  the conservative half-credit reading (.361) is inside the band. The
  projection carries more signal than the raw number said.
- **The coverage-gradient story needs one asterisk, not retraction.**
  33/40 of DeepSeek's low-coverage rows are zeros; of the 4 judged
  low-cov zeros, 3 were reasonable alternatives (n tiny). So the
  low-cov bucket's ".105 = noise" is partly "the historical target was
  an ungrounded exploratory probe and the model did the prescribed
  plan act instead" — a scorer artifact, not pure projection failure.
  The gradient's direction survives (high-coverage rows are where
  COPY-kind credit lives, and that is what v0 serves), but low-cov
  rows overstate hopelessness.
- **KT3b's tie was an artifact; the $0 baseline is unchanged.** The
  bar for B3 stays .265 (that is what the mechanical metric will score
  needle with, apples-to-apples). But the two-tier/vehicle-swap
  discussion should not describe the 1.5B as "frontier-equal": its
  zeros are real defects; corrected, DeepSeek leads by ~.10–.20.
- Unexamined in either direction: the 25 partially-credited DeepSeek
  rows (0 < useful < .5), and KT3's own documented generosity on
  matched pairs (row 44's wrong-`:where` full credit) — the metric
  errs generous on the matched side and strict on the unmatched side;
  the two biases partially offset in the mean.

## Recommendation — plausibility judge or exact match?

**Exact-match stays the offline core; no LLM-judge component inside
useful-match.** Grounds: it is deterministic and self-test-clean; for
the small-model arms (the ones the lane actually trains and gates) its
zeros are ~all real errors (0/20 FN, pooled CI upper .17); and the
judges themselves needed gating + human adjudication (κ=.53 on the
4-way) — putting one inside the metric would trade a measured,
one-sided bias for an unmeasured, drifting one.

Three changes, in order of value:

1. **Prescribed-act accept-set (mechanical, $0).** Extend
   `kt3_score.clj` scoring with a per-row accept-set derived from the
   row's own plan block: `active!` on the "→ next ready" id when no
   step is ▶ active; `done!` on the ▶ step; the read probes
   (`document`/`tree`/`list-open`) on ids/agents the block names. A
   zero-scoring prediction that exactly matches the accept-set is
   reported as `prescribed-alt` (a separate column, NOT folded into
   `useful` silently). Covers 8/8 observed FNs; the state guards are
   exactly what separated them from the 9 same-signature real errors.
2. **Dataset hygiene.** Drop/re-mine the 3 junk-target rows (31, 184,
   179); report pure-ns-move-target rows separately (already the
   substantive lens). Both are one-line filters at export time.
3. **Band-gated verdicts get a calibration pass, not a new metric.**
   Any future KT-style STOP/GO read against an absolute band should
   include this audit's ~40-item two-judge calibration (~$0.50, ~1 h
   wall) so the band comparison uses a corrected point. Per-run
   training-loop scoring does not need it.

## Appendix — 10 judged cases (predictions and targets verbatim)

Full contexts for every case: `src-needle/data/fn-audit/sample.json`;
all four verdict files + `report.json` alongside it.

### 1. deepseek:2 — FINAL: reasonable-alternative (judges agreed)

Coverage 0. Plan block shows `→ next ready: wZw-2607112119 «[ ] 1.
Design a structured shape for expenses…»`.

```clojure
;; prediction
(my.plan/active! {:my.plan/id "wZw-2607112119"})
;; target
(in-ns 'my.agent.AzY-2607112118)
(seon.agent.search/grep-graph {:seon.agent.search/pattern "my.kb" :seon.agent.search/max-results 20})
```

Muse: "prescribed way to start step 1… target's grep is exploratory
prep for the same design work." DeepSeek judge: "both are sensible next
actions." The projection's own instruction is the prediction.

### 2. deepseek:27 — FINAL: reasonable-alternative (agreed)

Coverage .75. Target does `tree` then `done!` on the SAME id the
prediction activates.

```clojure
;; prediction
(my.plan/active! {:my.plan/id "WSf-2607112332"})
;; target
(my.plan/tree {:my.plan/root? "rtD-2607112332" :seon.agent/id "oOF-2607112331"})
(my.plan/done! {:my.plan/id "WSf-2607112332"})
```

### 3. deepseek:42 — FINAL: reasonable-alternative (adjudicated)

Coverage 0. The ask (visible in context) explicitly instructs reading
the worker's plan document.

```clojure
;; prediction
(my.plan/document {:seon.agent/id "oOF-2607112331"})
;; target
(seon.agent.fs/list-dir {:seon.agent.fs/path "/"})
```

Both judges found it reasonable blind; DeepSeek's target-pass label
(`nonsense`) contradicted its own rationale ("the candidate retrieves
the plan as explicitly instructed, while the agent instead listed the
root directory") — adjudicated to the rationale, against the label.

### 4. deepseek:184 — FINAL: reasonable-alternative (agreed) + junk target

Coverage 1. The TARGET is prose that parsed as a call — no prediction
can score on this row.

```clojure
;; prediction
(my.plan/active! {:my.plan/id "ZEg-2607112254"})   ; ZEg = the block's "next ready" id
;; target
(The plan has been laid down)
```

### 5. deepseek:172 — FINAL: wrong-but-related (adjudicated)

Coverage .93. The right IDEA (per-attribute registration — the system
idiom, arguably better than the target's broken `(map [...])` defn) in
an unrunnable form: `seon.dynamic` does not exist, so the `require`
fails and nothing evaluates.

```clojure
;; prediction (head)
(require '[seon.dynamic :as sd])
(sd/register! :my.kb.book/id {:seon.db/identity true})
;; … 6 more sd/register! calls
;; target (head)
(defn register-book-schema []
  "Register the schema for book entities in the knowledge base."
  (seon.schema/register! :my.kb.book (map [:my.kb.book/id {:seon.db/identity true} …])))
```

Muse called it reasonable-alternative; DeepSeek judge flagged the
unlisted `sd/register!`. Adjudicated WR: a reasonable ALTERNATIVE must
be a runnable act — invented namespaces are precisely the
hallucinated-fn defect the metric exists to catch.

### 6. deepseek:95 — FINAL: wrong-but-related (agreed)

Coverage .5. The prediction re-registers an attribute whose successful
registration (`⟹ :my.expense/id`) is visible lines above.

```clojure
;; prediction
(seon.schema/register! :my.expense/id [:string {:min 1, :seon.db/identity true}])
;; target
(seon.db/transact! {:seon.db/tx-data [{:my.expense/id "probe-001" …}]})
(seon.db/query '{:find [[?e ...]] :where [[?e :my.expense/id "probe-001"]]})
```

### 7. deepseek:33 — FINAL: wrong-but-related (adjudicated)

Coverage .66. `PNd-2607112358` is a MESSAGE event id (`;;; ▶ to
agent-OMU… [PNd-2607112358]`), not a plan id; `done!` on it is the
id-fidelity defect KT3's id lens flagged.

```clojure
;; prediction
(my.plan/done! {:my.plan/id "PNd-2607112358"})
;; target
(my.plan/list-open {:seon.agent/id "IHk-2607112331"})
```

Muse said premature-but-sensible; DeepSeek judge's blind pass caught
the message-id error. Adjudicated WR.

### 8. instr-few:180 — FINAL: premature-but-sensible (agreed)

Coverage .9. The transcript just defined `register-book-schema` (with a
truncated-attr bug); the prediction invokes it, the target redefines it
fixed first.

```clojure
;; prediction
(register-book-schema)
;; target (head)
(defn register-book-schema []
  "Register the schema for book entities using plain keywords to avoid namespace errors."
  (seon.schema/register! :my.kb.book {:map […  :my.kb.book/created-at :inst]}))
```

The closest small-model case to a false negative — both judges: sensible
invoke-after-define, but it skips the fix the target performs.

### 9. instr-few:0 — FINAL: wrong-but-related (agreed)

Coverage .33. Invented fn + exemplar leakage: `my.plan/plan!` does not
exist (the real surface is `reconcile!`/`step!`), and
`mmR-2607112016`/`kpc-2607112000` are ids copied verbatim from few-shot
exemplar 161 into an unrelated world; `done!` fires with no work done.

```clojure
;; prediction (head)
(my.plan/plan! {:title "expense-tracker" :my.plan/goal "…" :children […]})
(my.plan/active! {:my.plan/id "mmR-2607112016"})
(my.plan/done! {:my.plan/id "mmR-2607112016"})
;; target (head)
(plan/reconcile! {:my.plan/markdown "# Expense tracker groundwork\n…"})
```

### 10. starcoder2-cont:79 — FINAL: wrong-but-related (agreed)

Coverage 1. The base model copied the plan block's instructional
template verbatim, placeholders included.

```clojure
;; prediction
(plan/step! {:my.plan/title "…" :my.plan/parent [:my.plan/id "<an id here>"]})
;; target
(in-ns 'my.agent.NLN-2607112129)
(def lowest (->> (db/query {:query '[:find ?p ?o :where [?e :my.team/person ?p] [?e :my.team/utc-offset ?o]]}) (sort-by second) first))
```

## Limitations

- n=40 judged items (n=20 for the headline DeepSeek arm) — the Wilson
  intervals are wide; this is a defect-rate estimate, not a precise
  correction. Per-kind cells are 1–8 items.
- Judges see the same projection the predictor saw — "reasonable" is a
  ruling under partial information, which is exactly what the
  hypothesis asks (equally-valid next act GIVEN the situation), not
  ground truth against the full db.
- DeepSeek judges DeepSeek's own predictions. Observed behavior cuts
  both ways (it was harsher than Muse on its own outputs as often as
  more generous), the second judge family and the blind gating bound
  it, and every disagreement was human-read.
- Only useful==0 rows were audited; the 25 partially-credited DeepSeek
  rows were not re-examined in either direction.
- The corrected ceiling treats an accepted alternative as credit 1.0
  (upper bound; .5-credit variant reported). The truth for serving
  depends on what a suggestion surface does with a prescribed-act
  suggestion — likely close to full value (correct id filled in), which
  is why the accept-set recommendation reports it as its own column
  rather than silently inflating `useful`.
