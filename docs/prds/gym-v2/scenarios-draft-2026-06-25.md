---
type: prd
status: draft
tags: [prd, agent]
---

# Gym v2 — Genuinely-Useful Cross-Session Scenarios (DRAFT for owner review)

12 cross-session scenarios (agent A stores cold → fresh agent B retrieves+PROCESSES), authored as valid EDN against `:seon.gym/scenario` (verified key-by-key vs `driver.cljs:258-272`). Outcome signals anchor on op-families (`seon.db/(query|pull|entity|store-inventory)`) + attr-name stems; answer correctness lives ONLY on the separate `:llm-judge` axis with shape-level references. Pairs with [[docs/prds/gym-v2/design]]. **For review — decide the format additions in the limitations section before implementing.**

## Format reference (extracted)

A scenario file = one map or vector of maps; `load-scenarios!` validates vs `:seon.gym/scenario` then `check-self-bait!` (turn message text must not appear verbatim in a fixture).

**Scenario keys** (`driver.cljs:259-272`): `:seon.gym.scenario/{id(kw) doc(str) tier(:stub|:paid) status(:active|:todo) axes(vec of fixed enum) schema-registrations([[kw malli]]) fixtures(:seon.db/tx-data, one tx; string vals support {{today}}/{{days-ago:N}} past-only) fixture-sources(vec of source strings) llm(:scripted-replay|:rejecting) turns predicates}`.

**Axes enum (fixed, can't invent)**: `:sees-question :searches-first :models-work-directed :reuses-schemas :consults-findings :reuses-functions :writes-tests :replies-honestly :terminates :stores-proactively`.

**Turn**: `{:seon.gym.turn/agent :a|:b (default :a; strict order, one scratch store) :message "NL" :llm-script [..] (stub only)}`.

**Predicate kinds** (`:209-222`):
- `:datalog` — `:query [literal vector]`, optional `:args` (`:seon.gym.agent/<d>` → that agent's id), `:expect` ∈ `:non-empty :empty [:count n] [:count<= n] [:count>= n] [:some-includes "s"] [:every-in ["a"..]]`.
- `:domain-attrs` — `:expect` over rows = every agent-provenance attr (`seon.warn/domain-attrs`). `[:every-in [..]]` = no forked attr; `[:count>= n]` = vacuity guard.
- `:first-eval-matches` — `:pattern "regex"` over the first message-driven eval source. optional `:agent`.
- `:eval-count-matching` — `:pattern` + `:expect` over matching message-driven evals. optional `:agent`.
- `:llm-judge` — `:rubric` + `:reference` (shape-level GT) + optional `:agent`. Graded 0-100 on the SEPARATE judge axis.
- `:transcript-includes/excludes`, `:prompt-includes/excludes/every-turn` — restricted (design §6 r2) to the user's own message text or telemetry, never a rendered section's phrasing.

**Datalog engine constraints obeyed**: no double-identity-join through one message (reply checks use `from-agent + hops>0`); no two distinct unbound attr vars joined on one entity (name-stem checks ride nested `q`+`set`); ref slots match by joining through the target's identity; linked fixtures use the shared-tempid idiom (a ref slot carries a `:db/id "p1"` tempid resolved in the same tx — lookup-refs don't resolve against not-yet-committed entities).

**Reusable cold-boot invariant predicate** (design §2): B's only `woken-by` turn is the question —
```clojure
{:seon.gym.predicate/id :b-booted-cold-no-prior-turns :seon.gym.predicate/kind :datalog
 :seon.gym.predicate/axis :consults-findings :seon.gym.predicate/args [:seon.gym.agent/b]
 :seon.gym.predicate/query [:find ?t :in $ ?bid :where [?ag :seon.agent/id ?bid]
   [?ag :seon.agent/sessions ?s] [?s :seon.agent.session/turns ?t] [?t :seon.agent.turn/woken-by _]]
 :seon.gym.predicate/expect [:count<= 1]}
```

## The 12 scenarios (id · domain · what it tests)

| id | domain | tests | mechanical legs | correctness |
|---|---|---|---|---|
| X1 subscriptions-total-and-max | finance | new-schema + SUM + MAX | discovery, ≥5 rows, idle | judge: total 101, max Adobe 45 |
| X2 maintenance-overdue-by-cadence | home | discovery + DERIVE overdue from last-done+cadence | discovery, ≥2 rows | judge: furnace overdue, smoke not |
| X3 expense-reuse-and-category-total | finance | reuse-no-fork + category SUM | row-landed, no-fork, discovery | judge: dining 106 |
| X4 run-extend-not-fork | health | extend domain not fork | row-landed, no new ns, discovery | (mostly mechanical) |
| X5 task-project-owner-link | projects | single-link traverse | discovery, ref-attr touched | judge: Priya via link |
| X6 reading-author-source-note-multihop | reading | two-hop synthesis | discovery, hop-attrs touched | judge: Hari + claim |
| X7 mealplan-shopping-list-multihop | recipes | multi-hop + dedup/SUM | discovery, plan/ingredient refs | judge: onion=3, full union |
| X8 cellar-region-vocabulary-gap | cellar | inventory sample-value affordance | discovery, ≥3 rows | judge: Lagavulin/Islay |
| X9 spending-month-over-month-compare | finance | windowed agg + COMPARE | discovery, ≥4 grocery rows | judge: up ~33% (120 vs 90) |
| X10 task-dependency-unblocked | projects | derive over dep DAG | discovery, depends-on touched | judge: startable set |
| X11 warranty-absent-no-fabrication | home | NEGATIVE anti-fabrication | discovery, no fabricated attr | judge: honest about absence |
| X12 narrow-question-no-over-retrieval | mixed | NEGATIVE over-retrieval precision | queried relevant kind, count 0 off-kinds | judge: dentist=Okafor |

All 12 are **paid-tier** (cold discovery+processing needs a real model). **Stub fake-stash companions worth adding** (prove mechanical predicates fire free): X1, X3, **X4**, X7, **X12** (X4/X12 have the strongest mechanical-not-judge signal). The full EDN for each is in the a33 agent deliverable; representative example (X1):

```clojure
{:seon.gym.scenario/id :x1-subscriptions-total-and-max
 :seon.gym.scenario/doc "A stores 5 subscriptions (new :my.subscription/* schema). Fresh B, never told the kind, must discover it, SUM monthly cost (=101) and name the most expensive (Adobe @45)."
 :seon.gym.scenario/tier :paid :seon.gym.scenario/status :active
 :seon.gym.scenario/axes [:consults-findings :replies-honestly :terminates]
 :seon.gym.scenario/schema-registrations
 [[:my.subscription/name [:string {:seon.db/identity true}]]
  [:my.subscription/monthly-usd :int] [:my.subscription/category :keyword]]
 :seon.gym.scenario/fixtures
 [{:my.subscription/name "Netflix" :my.subscription/monthly-usd 18 :my.subscription/category :entertainment}
  {:my.subscription/name "Spotify" :my.subscription/monthly-usd 12 :my.subscription/category :entertainment}
  {:my.subscription/name "Adobe CC" :my.subscription/monthly-usd 45 :my.subscription/category :software}
  {:my.subscription/name "iCloud+" :my.subscription/monthly-usd 9 :my.subscription/category :storage}
  {:my.subscription/name "NYT" :my.subscription/monthly-usd 17 :my.subscription/category :news}]
 :seon.gym.scenario/turns
 [{:seon.gym.turn/agent :a :seon.gym.turn/message "Track my recurring subscriptions: Netflix $18, Spotify $12, Adobe Creative Cloud $45, iCloud+ $9, and the New York Times $17 — all monthly."}
  {:seon.gym.turn/agent :b :seon.gym.turn/message "Roughly how much am I spending on subscriptions every month, and which single one costs me the most?"}]
 :seon.gym.scenario/predicates
 [{:seon.gym.predicate/id :subs-seeded-visible :seon.gym.predicate/kind :datalog
   :seon.gym.predicate/axis :consults-findings
   :seon.gym.predicate/query [:find ?e :where [?e :my.subscription/monthly-usd _]] :seon.gym.predicate/expect [:count>= 5]}
  {:seon.gym.predicate/id :b-discovery-reads-store-first :seon.gym.predicate/kind :first-eval-matches
   :seon.gym.predicate/agent :b :seon.gym.predicate/axis :consults-findings
   :seon.gym.predicate/pattern "seon\\.db/(query|pull|entity|store-inventory)"}
  {:seon.gym.predicate/id :judge-total-and-max :seon.gym.predicate/kind :llm-judge :seon.gym.predicate/agent :b
   :seon.gym.predicate/axis :replies-honestly
   :seon.gym.predicate/rubric "PASS only if the reply states a total monthly subscription cost of 101 USD (allow 100-102) AND identifies the most expensive as Adobe Creative Cloud at 45. FAIL if no total, wrong total, or wrong most-expensive."
   :seon.gym.predicate/reference "Netflix 18, Spotify 12, Adobe 45, iCloud+ 9, NYT 17. Total=101. Max=Adobe 45. Known only by reading the stored rows."}]}
```
(X2-X12 full EDN preserved in the a33 deliverable; to be dropped into `test/seon/gym/scenarios/` during implementation.)

## Format limitations → DRIVER CHANGES TO DECIDE (owner review)

1. **[BIGGEST] No mechanical assertion that a computed aggregate equals a planted scalar.** "total=101", "up ~33%", "onion=3", "startable={..}" are verifiable today ONLY by the fuzzy `:llm-judge`. Proposed: extend `:expect` (`driver.cljs:184`) with `[:scalar= v]`/`[:scalar-approx v tol]` over a `:find ?x .` result; AND/OR a new `:eval-result-matches` kind capturing a driven eval's RETURN value (driver records source not value — `eval-at+source :500` needs a sibling reading `:seon.eval/value`). Either makes "B computed 101" binary.
2. **Workaround is brittle** — "instruct B to store its result, then datalog the value" couples to vocabulary/units and `[:some-includes]` scans the whole store. Deliberately NOT used.
3. **"B followed the link" is inferred, not proven** — anchored on `:eval-count-matching` over ref-attr stems (a behavior proxy). A predicate inspecting the eval's query FORM (did a `:where` join through the ref?) or eval-return-capture on the hop-2 value would make link-follow a true outcome.
4. **No "no stem-colliding fork" predicate** — `:domain-attrs [:every-in]` rejects ANY new attr, so can't express X4's "extend ok, fork not"; approximated with a `register!` regex-lookahead (catches forked namespace, not `:my.run/distance-meters` beside `distance-km`). Proposed `[:no-colliding-stem ["distance"]]`.
5. **Over-retrieval precision is coarse** — X12 penalizes querying off-kinds (`[:count 0]`) but not a broad `pull`/over-wide read. An `:eval-result-size<=` (rows/chars B's reads returned) would generalize the precision axis (and serve condition-B over-pull).
6. **`{{days-ago:N}}` is past-only** — no future dates, so "what renews/what's coming up next" scenarios can't plant a relative future answer key. Proposed `{{days-ahead:N}}`.
7. **The judge is the only semantic axis, and it's paid** — processing correctness can't be regression-tested in the free suite, only the behavioral mechanics. (Structural reason all 12 are paid-tier.)

None of 1-7 block authoring/running the 12 under condition (A); they bound how much of "B computed the right answer" is mechanical vs judge.
