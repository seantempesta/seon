---
type: research
status: active
tags: [research, agent]
---

# KT3 — frontier signal-ceiling on the autocomplete projection

**Date:** 2026-07-12 · **Question:** given EXACTLY the compact context +
cards a tiny model would see (nothing more), can a frontier model predict
the turn's actual forms? · **Data:**
`data/tune/acme-2026-07-12.jsonl` (214 held-out A1 rows: context ≤678
tok, cards, target, meta incl. ingredients-coverage) · **Models:**
`deepseek-v4-pro` (shipped provider, thinking disabled, temp 0, all 214
rows) + `muse-spark-1.1` (`reasoning_effort minimal`, 50-row seeded
subsample) · **Scripts:** `src-needle/scripts/kt3_signal_ceiling.py`
(driver, stdlib) + `src-needle/scripts/kt3_score.clj` (bb/edamame
mechanical scorer — a real Clojure reader, no regexes) · **Raw runs:**
`src-needle/data/kt3/` (gitignored; preds/scored/summary per provider) ·
**Spend:** $0.34 total (DeepSeek $0.11, Muse $0.22, smoke $0.01).

## TL;DR — verdict: STOP (band floor missed; the gap is localized)

**Useful-match = 0.261 overall / 0.291 substantive (DeepSeek, n=214) —
below the design's <~30–40% kill band.** Per the design (§Measurement,
KT3): the projection, not the trainee, is the defect — **file the
context-gap report for the owner before any training spend.**

The failure is not uniform, and that is the actionable finding:

- **The ceiling is coverage-gated, monotone, and confirmed in every
  form-kind.** Rows with ingredients-coverage ≥.75 (110/214) reach
  useful 0.362 — and the copy-heavy kinds the v0 model bets on reach
  **register .450, query .436, plan .323** there. Rows <.25 (the 40
  KT2.5 flagged) sit at **0.105 — noise**. Pearson(coverage, useful)
  = +0.287 overall, positive in every kind (.25–.56).
- So the projection DOES carry predictive signal where it carries the
  ingredients; the defect is that half the dataset's projections don't.
  The fix lane is exactly the design's prediction: context generation
  (owner-gated), not model capacity.
- Secondary defect: **target granularity.** Targets bundle a whole
  turn's ok-forms (490 calls); frontier predictions emit the next 1–2
  actions (312 calls). The "does the model produce the turn's FIRST
  substantive form" lens = 0.313 — the serving-relevant number, also at
  the band floor.

## Method

### Prompt

Per row, ONE user message, nothing else added by construction:

1. the row's `context` verbatim,
2. the row's `cards` under a `;;; ┌─ cards ─ available functions ─`
   bracket (the context's own comment grammar),
3. the terse instruction: *"You are a Clojure REPL agent in the seon
   system. Given this situation, emit ONLY the next REPL form(s) you
   would evaluate — no prose."*

No system prompt, no toolkit docs, no examples — the point is THIS
projection's ceiling. `temperature 0`, `max_tokens 2048`, single sample
per row, single process, concurrency 6 with backoff.
DeepSeek gets the shipped adapter's `{"thinking": {"type": "disabled"}}`
(the API defaults thinking ON and burns the cap with empty content —
reproduced live); Muse gets standard `reasoning_effort: "minimal"` only
(strict gateway, per the adapter).

Mechanical cleanup only: markdown code fences unwrapped when present.

### Scoring (the documented "useful match" formula)

Both sides parse through edamame (`:all true`) — a prediction that fails
to read scores 0. A CALL = any list form with a symbol head, collected
recursively, quoted forms skipped. Symbols normalize to
`{name, last-ns-segment}` (`my.plan/done!` ≡ `plan/done!`; a bare name
matches any ns). Target→prediction pairing is greedy in target order,
each prediction call used once.

- per-target-call credit: 0 unmatched; 1.0 matched with no map arg;
  matched with a keyword-keyed map arg →
  `0.5 + 0.5 · |target keys ∩ pred keys| / |target keys|`
  (keys by NAME, values NOT compared);
- recall-credit = mean credit over target calls; precision = matched
  prediction calls / all prediction calls;
- **useful = harmonic mean (F1) of recall-credit and precision**;
- **substantive** = the same excluding `:ns-move` calls
  (`in-ns`/`require` boilerplate is trivially prependable by a serving
  layer and would otherwise distort both directions — DeepSeek rarely
  emits it, match rate .069 on 72 calls).

Form kinds: `ns-move` · `plan` (any `my.plan` fn) · `register`
(`schema/register!`) · `transact` (`db/transact!`) · `query`
(`db/query|q|pull|pull-by-name|entity|datoms|history|as-of`) · `defn`
(`defn`/`def`/`defmethod`…) · `other` (toolkit calls: `my.kb`,
`seon.agent.search`, `my.data`, agent-defined fns, …).

Target census (all 214 rows, 490 calls): plan 123 · other 152 ·
ns-move 72 · query 70 · register 34 · defn 31 · transact 8.
0 target parse failures.

## Results

### Headline

| arm | n | parse | useful (F1) | useful ≥.5 | substantive | subst ≥.5 |
|---|---|---|---|---|---|---|
| DeepSeek, full set | 214 | .986 | **.261** | .266 | **.291** (n=192) | .307 |
| DeepSeek, 50-row subsample | 50 | 1.0 | .379 | — | .401 | — |
| Muse, same 50 rows | 50 | .98 | .318 | .32 | .331 (n=46) | .348 |

Two frontier models agree within noise on identical rows (DeepSeek .379
vs Muse .318; recall-only .353 vs .373) — this is a projection property,
not a model quirk. The seeded 50-subsample happens to run easier than
the full set; the primary number is the full-set 0.261/0.291.

Auxiliary lenses (DeepSeek, n=214):

- recall-credit mean .241 · precision mean .348 · prediction calls 312
  vs target calls 490 (under-emission: targets are whole-turn bundles,
  the model predicts the next step);
- **next-form lens** (target truncated to its first substantive form):
  head-matched .313, recall-credit .268;
- **id-value copy** (scoped out of useful-match, measured separately):
  of 81 rows whose targets carry `XXX-26…` ids, value recall is only
  **.250** (37/148) with **79 spurious ids emitted** — matched plan
  calls frequently point at the WRONG `:my.plan/id`, so key-only
  useful-match OVERSTATES serving usefulness.

### Per form-kind (DeepSeek, n=214 — the ceiling differs by kind)

| kind | target calls | match rate | mean credit | credit @cov ≥.75 | @.25–.75 | @<.25 |
|---|---|---|---|---|---|---|
| query | 70 | .314 | .304 | **.436** (26 rows) | .221 (26) | .000 (4) |
| plan | 123 | .228 | .209 | **.323** (41) | .196 (38) | .077 (14) |
| other | 152 | .191 | .189 | **.403** (41) | .071 (28) | .104 (24) |
| register | 34 | .176 | .162 | **.450** (10) | .000 (6) | .040 (5) |
| transact | 8 | .125 | .125 | .333 (3) | .000 (4) | .000 (1) |
| defn | 31 | .065 | .065 | .115 (13) | .000 (6) | .000 (4) |
| ns-move | 72 | .069 | .069 | — (excluded from substantive) | | |

Readings:

- **register/query/plan at high coverage clear the band** (.32–.45) —
  the copy-heavy v0 bet is sound WHEN the ingredients are present
  (appendix row 24: a coverage-1.0 `register!` reproduced near
  byte-exact; row 9: coverage-.18 registrations produce an INVENTED
  `:expense/row` map shape instead of the target's per-attr
  `:my.expense/*` rows — the classic missing-ingredient signature).
- **defn ceiling ≈ .07–.12 even at high coverage** — independent
  confirmation of the design's v0 exclusion of defn bodies
  (delegation-not-generation).
- `transact` n=8 is too thin to read alone.

### Coverage correlation (the confirmatory evidence)

| coverage bucket | rows | useful mean |
|---|---|---|
| < .25 | 40 | **.105** |
| .25–.75 | 64 | .183 |
| ≥ .75 | 110 | **.362** |

Pearson(coverage, useful) = **+.287**, Spearman +.273; per-kind Pearson
all positive: transact .56, register .45, other .34, query .32, plan
.30, defn .25. The 40 sub-.25-coverage rows KT2.5 flagged show exactly
the predicted visibly-lower ceiling — a frontier model with the full
weight of its priors extracts almost nothing (.105) from projections
that lack the target's identifiers. **This is the confirmatory evidence
for the context-gap report: no 26M copy-model can succeed where a
frontier reads noise.**

(The Muse 50-row arm alone shows a negative correlation (−.20) — an
n=8 low-coverage bucket dominated by trivially-guessable
`plan/tree`-style bookkeeping rows; the full-set, per-kind stratified
numbers above are the real signal.)

## Verdict

**STOP per the design gate.** Useful-match 0.261 (0.291 substantive,
0.313 next-form) is below the <~30–40% band — the projection, not the
trainee, is the current defect. Before any training spend:

1. **File the context-gap report (owner-gated fix lane).** The defect
   is ingredient coverage, precisely localized: 104/214 rows render
   projections whose ceiling is .105–.183 because the target's
   identifiers (attr names, plan ids, fn names) are absent from
   context+cards. Where coverage ≥.75 the SAME projection supports
   .36–.45 on the v0 copy kinds — the design's division-of-labor bet
   survives; its precondition (the context holds the ingredients)
   is unmet on half the data. This is KT2.5's finding (mean .64)
   independently confirmed by a frontier ceiling measurement.
2. **Target granularity needs a ruling before KT5.** Whole-turn
   multi-form bundles are systematically under-predicted (312 vs 490
   calls) even by frontier models; the next-form framing (or per-form
   pairs) is the honest v0 target shape.
3. **Id-value fidelity (.25 recall, 79 spurious) says key-only scoring
   is the OPTIMISTIC bound** — any serving gate must validate emitted
   ids against the live db (the design's evals-clean gate already
   requires this).

## Limitations

- Single greedy sample per row (temp 0) — a pass@k ceiling would sit
  somewhat higher; it would not move the coverage gradient.
- Key-only arg scoring is generous: appendix row 44 scores 1.0 on a
  `db/query` whose `:where` graph is semantically different. The true
  usable ceiling is lower than reported, not higher.
- A few mined targets carry junk the agent happened to eval OK
  (appendix row 179 opens with `(which is incorrect)`) — dataset
  hygiene to note for the training split, immaterial at eval scale.
- The instruction framing is one sentence; richer serving-time framing
  (system text, toolkit docs) is deliberately absent — that is the
  point of the measurement, but it means these numbers are a floor for
  frontier-with-full-context and a ceiling only for THIS projection.

## Appendix — 10 rows verbatim (DeepSeek raw outputs)

### Row 0 — coverage 0.33, useful 0.00, kinds [ns-move, plan]

**Target (actual forms):**

```clojure
(in-ns 'my.agent.AzY-2607112118)
(require '[my.plan :as plan] '[seon.schema :as schema] '[seon.db :as db] '[my.data :as data] '[seon.agent.fs :as fs] '[my.blob :as blob])
(plan/reconcile! {:my.plan/markdown "# Expense tracker groundwork\nGoal: an expense record my human can query across sessions\n\n- [ ] 1. Design a structured shape for expenses (date, amount in cents, category, note) — expect: one probe expense stores and reads back intact.\n- [ ] 2. Store these three seed expenses: 2026-07-01 coffee 450 food; 2026-07-03 train 1200 transport; 2026-07-05 book 2300 learning — expect: a lookup returns exactly three expenses.\n- [ ] 3. Report the total spent per category — expect: food 450, transport 1200, learning 2300, derived from the stored rows, not recomputed by hand.\n"})
```

**DeepSeek output (raw, verbatim):**

```text
(my.plan/active! {:my.plan/id "XWs-2607112118"})

;; Step 1: Design the expense shape and verify one probe expense stores and reads back intact
(do
  ;; Define the expense shape using a simple map with spec-like validation
  (def expense-shape
    {:expense/date   :string   ;; "YYYY-MM-DD"
     :expense/amount :int      ;; cents
     :expense/category :keyword ;; :food, :transport, :learning
     :expense/note   :string})

  ;; Create an atom to hold expenses (in-memory store for this session)
  (def expenses (atom []))

  ;; Function to add an expense
  (defn add-expense! [date amount category note]
    (let [expense {:expense/date     date
                   :expense/amount   amount
                   :expense/category category
                   :expense/note     note}]
      (swap! expenses conj expense)
      expense))

  ;; Probe: store one expense and read it back
  (let [probe (add-expense! "2026-07-01" 450 :food "coffee")]
    [(= (count @expenses) 1)
     (= probe (first @expenses))
     probe]))
```

### Row 9 — coverage 0.18, useful 0.44, kinds [ns-move, register]

**Target (actual forms):**

```clojure
(in-ns 'my.kb)
(require '[seon.schema :as schema] '[seon.db :as db] '[my.data :as data] '[my.plan :as plan] '[my.kb :as kb])
(schema/register! :my.expense/id [:string {:seon.db/identity true}])
(schema/register! :my.expense/date :string)
(schema/register! :my.expense/amount-cents :int)
(schema/register! :my.expense/category :keyword)
(schema/register! :my.expense/note :string)
```

**DeepSeek output (raw, verbatim):**

```text
(in-ns 'my.kb)
;; step 1: design expense schema
(schema/register! :expense/row
  [:map {:closed true}
   [:expense/date :string]
   [:expense/amount-cents :int]
   [:expense/category :keyword]
   [:expense/note :string]])
```

### Row 23 — coverage 0.62, useful 0.55, kinds [plan]

**Target (actual forms):**

```clojure
(my.plan/step! {:my.plan/title "Store seed expenses" :my.plan/description "Insert the three seed expenses for coffee, train, and book using the :expense schema." :my.plan/parent "LGQ-2607112151" :my.plan/expect "Three records exist in the DB."})
(my.plan/step! {:my.plan/title "Report totals per category" :my.plan.description "Aggregate the total spent per category using my.data functions." :my.plan/parent "LGQ-2607112151" :my.plan/expect "Correct totals for food, transport, and learning."})
```

**DeepSeek output (raw, verbatim):**

```text
(my.plan/step! {:my.plan/title "Design and register schema for expenses"
                     :my.plan/parent [:my.plan/id "QTQ-2607112152"]})
```

### Row 24 — coverage 1.0, useful 0.67, kinds [plan, register]

**Target (actual forms):**

```clojure
(my.plan/done! {:my.plan/id "QTQ-2607112152"})
(seon.schema/register! :expense
  [:map [:date :string]
        [:amount :int]
        [:category :string]
        [:note {:optional true} :string]])
```

**DeepSeek output (raw, verbatim):**

```text
(seon.schema/register! :expense
  [:map
   [:date :string]
   [:amount :int]
   [:category :string]
   [:note {:optional true} :string]])
```

### Row 28 — coverage 0.66, useful 0.00, kinds [plan]

**Target (actual forms):**

```clojure
(my.plan/list-open {:seon.agent/id "oOF-2607112331"})
```

**DeepSeek output (raw, verbatim — nested fence as emitted):**

````text
```clojure
(defn book-shape
  "Structured shape for books: title, author, year, pages."
  {:malli/schema [:=> [:cat :string :string :int :int] :map]}
  [title author year pages]
  {:book/title title
   :book/author author
   :book/year year
   :book/pages pages})
```
````

### Row 71 — coverage 0.07, useful 0.15, kinds [defn, ns-move, other, register, transact]

**Target (actual forms):**

```clojure
(in-ns 'my.agent.NLN-2607112129)
(require '[seon.schema :as schema] '[seon.db :as db] '[my.data :as data] '[my.plan :as plan])
(schema/register! :my.team/person [:string {:seon.db/identity true}])
(schema/register! :my.team/role :string)
(schema/register! :my.team/utc-offset :int)
(def probe-tx (js/Promise.resolve nil))
(set! probe-tx (db/transact! {::db/tx-data [{:my.team/person "Probe" :my.team/role "tester" :my.team/utc-offset 0}]}))
(.then probe-tx (fn [res] (js/console.log "probe tx" res) res))
```

**DeepSeek output (raw, verbatim):**

```text
(in-ns 'my.kb)
```

### Row 86 — coverage 0.0, useful 1.00, kinds [other]

**Target (actual forms):**

```clojure
(my.agent.NLN-2607112129/lowest-offset-person)
```

**DeepSeek output (raw, verbatim):**

```text
(lowest-offset-person)
```

### Row 44 — coverage 0.66, useful 1.00, kinds [query]

(The key-only-scoring generosity example: fn + arg-key match, different
`:where` graph.)

**Target (actual forms):**

```clojure
(seon.db/query {:query '[:find ?id ?title :where [?e :my.plan/id ?id] [?e :my.plan/title ?title] [?e :my.plan/agent ?a] [?a :seon.agent/id "oOF-2607112331"]]})
```

**DeepSeek output (raw, verbatim):**

```text
(seon.db/query {:query '[:find ?k :where [?e :seon.schema/key ?k]] :args []})
```

### Row 163 — coverage 0.8, useful 0.67, kinds [other, plan]

(The wrong-id example: fn matched, `:my.plan/id` value wrong.)

**Target (actual forms):**

```clojure
(my.plan/active! {:my.plan/id "MxL-2607112043"})
(seon.agent.lifecycle/complete "planned")
```

**DeepSeek output (raw, verbatim):**

```text
(my.plan/active! {:my.plan/id "Nxt-2607112043"})
```

### Row 179 — coverage 0.0, useful 0.00, kinds [defn, other, register]

(The junk-target example — the mined turn's ok-forms open with prose
that read as a call.)

**Target (actual forms):**

```clojure
(which is incorrect)
(defn register-book-schema []
  "Register the schema for book entities using correct Malli :map-syntax."
  (seon.schema/register! :my.kb.book
    {:map [:my.kb.book/id {:seon.db/identity true}
           :my.kb.book/title :string {:min 1}
           :my.kb.book/author :string {:min 1}
           :my.kb.book/year :int {:min 1000}
           :my.kb.book/pages :int {:min 1}
           :my.kb.book/rating {:enum [1 2 3 4 5]}
           :my.kb.book/created-]}))
```

**DeepSeek output (raw, verbatim):**

```text
(my.plan/document {:seon.agent/id "oOF-2607112331"})
```
