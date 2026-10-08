---
title: Agent competency suite
type: research
status: proposed (design only; no code); revised in place 2026-10-08 with root review, auto-acceptance, bail and conflict tasks
date: 2026-10-08
lane: competency-spec
---

# Agent competency suite

What exists: tasks with proofs (`seon.task/create!` src/seon/task.clj:439, `assign!` :467, proofs derived by
`task-edges` :321 from deftests tagged `:seon/tasks`), the model loop (`seon.turn`, request at turn.clj:4636-4638),
`seon.ai/request-body` (src/seon/ai.clj:561-599, one optional system message plus one user message at :572-575),
attempt rows with provider usage (turn.clj:4219-4250), the test runner's member rows, and the whole merge flow
(`my.program/propose-merge!` src/my/program.clj:416, `proposals` :473, `prepare-merge!` :513, `accept-merge!` :547,
`seon.cluster.source/compare-branch` src/seon/cluster/source.clj:824 with `seon.program/three-way`
src/seon/program.cljc:704). Data flow of one run: root assigns a task to a fresh agent on its own branch of the
competency cluster → the agent works through the one REPL path → it proposes once its proofs pass, or bails → root's
policy prepares the merge on the combined program and accepts or holds it → one query reads the score from stored
facts. The suite adds tasks, proofs, two AI dials, one merge-policy dial, one adapter function, a few facts and one
query; no harness, runner, store or printer.

## 0. Prerequisite: a model pass within the ordinary-pass budget

Owner ruling 2026-10-08: no world accepts 926-945 MB and 536-568 ms per model pass through the fixture provider
(`seon.pass-probe/run-model running 1 #{}`, test/seon/pass_probe.clj:328; an MCP SCI pass of the same form costs about
23 MB). **No model run of this suite starts until `run-model` reports a model pass inside the ordinary-pass budget
(agent-loop §3: < 200 ms, 20-40 MB transient and never GB, no base-context rebuild, 1 machinery transaction, Seon's work only).** The planned S4 slices plus the base-context slice (reconciled plan) own that cut.
Slices marked "today" in section 9 need no model pass; everything else waits.

## 1. Merge target and root review

**Target: a `competency` cluster**, started in the running JVM with `bin/seon start competency` (the operator's
`:start` connects to a live root's JVM, script/seon/operator.clj:1673-1676) and reset between suite runs with
`bin/seon reset competency --force`. Recommended over a per-suite merge branch on default because:
- `accept-merge!` lands on the branch the accepting context holds, and root's context is its cluster
  (src/my/program.clj:547-556); on `competency` that cluster is the target, so no merge can reach default's program;
- the task home is root's branch (`seon.task/home` src/seon/task.clj:381-389) and `propose-merge!` messages `"root"`
  (program.clj:464); a merge branch on default would put every competency task, merge task and message on default;
- the auto-accept dial (section 2) is that cluster's configuration, not a dial default carries;
- agents' branches fork off that cluster's head on first use (MCP `agent` argument, src/seon/mcp.clj:52), so the
  merge base is the competency head and `my.program/diff` (program.clj:974) is the diff against the target.

Alternative, rejected: a per-suite branch on default with a reviewer agent holding it. Cost: tasks and messages on
default, a second "root". Slice M0 proves the cluster starts, hosts agents and resets; if it cannot, M0 reports the
precise gap and the alternative is taken.

**What root sees** for each proposal, all data, through its ordinary REPL:

| View | Today | Gap |
|---|---|---|
| open proposals: branch, commit, proved digests, run | `my.program/proposals` (program.clj:473) | none |
| the diff value | `compare-branch` → base, branch and head digest maps and the three-way: unchanged, changed on branch or head, added, retracted, conflict with all three digests (source.clj:824-845, program.cljc:704-747); per declaration `my.program/declaration-diff` (program.clj:991), but from the agent's side only | root needs `declaration-diff` for a named proposal: slice M3 |
| the proofs on the combined program | `prepare-merge!` forks S off the head, applies the delta, runs the proofs and reaching tests: `:seon.test/passed?`, tally, red members shown, only proofs gate (source.clj:847-946) | none |
| evidence | the run id, the proposal's run and refuted proofs (`seon.task/propose!` task.clj:655), the accepted merge commit and parents (source.clj:1020-1033) | who accepted and under which policy: section 2 |
| a conflict | prepare answers the three-way value and keeps nothing (source.clj:905) | CLAUDE.md: "Conflicts stay on the merge branch for repair". Slice M4 keeps S with the non-conflicting delta applied and names the conflicts |
| a dangling caller | not seen: calls are symbol values (`:seon.fn/calls`, resources/seon/schemas/seon.fn.edn:49), not refs, so the writer cannot refuse a retracted callee | slice M5: the prepared value names every caller on the merged program whose callee has no declaration |
| a schema the target's data violates | not seen: the stored-data check runs only for a REPL registration in custody (`seon.db/registration-check` src/seon/db.clj:4077); prepare and accept never call it | slice M6: for each changed or retracted `:seon.schema/key`, the head's refused-value count and the declarations whose `:seon.fn/keywords` read it |

P8 (repl-plan, 996271b7b): the agent's branch never refuses a REPL-valid change; integrity is checked at root's diff
and merge. The conflict tasks (T10-T13) exercise exactly that boundary.

## 2. Auto-acceptance: root's policy, automated

- **Where.** A cluster configuration dial on the target, `:seon.config.merge/auto-accept`, a set of criteria;
  absent means every proposal waits for root. The competency manifest sets
  `#{:proofs-green :no-conflict :no-dangling :schema-holds :no-new-red}`.
- **Who runs it.** `my.program/review!` (new, root's): on the merge message `propose-merge!` already sends to root, root's
  wake calls it with root's own context. It calls `prepare-merge!`, checks each configured criterion on the answer and,
  when all hold, calls `accept-merge!`. It is root's acceptance: the same functions, root's context, root's writer.
  Whether root's wake can run a function on a message is verified first in slice M1; until then the suite's launch
  function calls `review!` as root when the run ends.
- **What it checks**, each on the combined program (S = head plus the delta):

| Criterion | Reads | Exists |
|---|---|---|
| `:proofs-green` | every task proof green on S (accept already refuses otherwise, source.clj:1006) | yes |
| `:no-conflict` | `:seon.program/conflict` empty | yes |
| `:no-dangling` | no caller on S naming a symbol with no declaration | M5 |
| `:schema-holds` | every changed or retracted schema key refuses no stored head value and leaves no reader | M6 |
| `:no-new-red` | no reaching member red on S that is green on the head (prepare's `:seon.source/red` against the head's evidence) | M2 |

- **Evidence.** Accept: `decide!` (task.clj:679) records `:seon.task/outcome :accepted` as today, plus two new facts on
  the merge task: `:seon.task/accepted-by` (`:policy` or `:root`; consumer: the score and root's audit) and
  `:seon.task/review-run` (the prepare run id; consumer: the score and root's re-run). Hold: the proposal stays open
  (`proposals` lists it), `:seon.task/held` records the failed criteria as keywords (consumer: the score and root's
  queue), and one message to root names them with the kept merge branch. Reject: root's `decide! … :declined`.
- **Proof.** `seon.merge-policy-test/auto-accepts-green-and-holds-red`: on a fixture cluster with the dial set, two
  agents propose against the same head; one carries a green proof and is accepted with `:accepted-by :policy`; the
  other's proof is green on its branch but red on S (the head changed its callee meanwhile) and is held with
  `#{:proofs-green}`, its proposal still open.

## 3. Instructions: how an agent ends its session

`(help)` today (src/seon/bootstrap.clj:17-63) teaches `(my.agent/done)` and `turns-left`, which S1-10 drops. S1-10
makes `help-value` constant data, `[{:seon.help/says "…" :seon.help/forms [quoted forms]}]`
(docs/research/agent-platform/2026-10-07/s1-lookups/design-notes.md §4.5), and its class regression resolves every
form's head. Entries this suite needs, in that shape:

```clojure
{:seon.help/says "Your task is done when its proof tests pass on your branch. Run them, read your diff, then propose; root merges."
 :seon.help/forms [(doc my.ns/the-task) (my.test/check {:seon.test/changed [my.ns-test/a-test]})
                   (my.program/diff) (my.program/propose-merge! {})]}
{:seon.help/says "If the task cannot be done, stop early: give it back with a reason naming what blocks it. Never claim success."
 :seon.help/forms [(my.task/complete! {:seon.task/reason "proof a-test requires 29 days in February 2023; proof b-test requires 28"})]}
{:seon.help/says "To pause until something new arrives, wait. A reply with no form also waits."
 :seon.help/forms [(my.turn/wait {:my.turn/note "Waiting for root's answer."})]}
{:seon.help/says "Each wake allows a fixed number of model calls. At the cap your session stops, root is told, and the next message resumes you."
 :seon.help/forms []}
```

| Named function | At HEAD |
|---|---|
| `doc` of a task symbol | TARGET S1 (`(doc task)` renders `task-ai`, src/seon/render/ns.clj:101) |
| `my.test/check` | exists, src/my/test.clj:6 |
| `my.program/diff` | exists, src/my/program.clj:974 |
| `my.program/propose-merge!` | exists, src/my/program.clj:416 |
| `my.task/complete!` with a required reason | TARGET S6-06b (Q13); absent at HEAD. Today's nearest: `my.turn/complete` (src/my/turn.clj:5, a success reply) and `my.agent/done` (src/my/agent.clj:23), both slated for deletion. `:seon.task/reason` is the proposed key |
| `my.turn/wait` | exists, src/my/turn.clj:17 |
| the per-wake call cap and `:capped` disposition | TARGET S6 (agent-loop §4.5); today `:seon.config.run/max-episode-runs` (config/default.edn:287) caps an episode |

The task opening (agent-loop §4.4: `(help)`, the task's `doc`, its must-reads' `doc`) shows the same rule once more
in `task-ai`: the proof symbols, then "Done when these pass, then `(my.program/propose-merge! {})`; if impossible,
`(my.task/complete! …)`." Slice H1 adds both; T1-T9 cannot be scored for bail correctness before S6-06b lands.

## 4. The tasks

Task file `resources/seon/tasks/seon.competency.edn`; proofs, setups and oracles in `test/seon/competency_test.clj`
(lane comp-c1 owns both now; T9-T13 are added after it releases them). Each run is a fresh agent on its own branch of
the competency cluster. A proof is a JVM deftest run as its member (`kernel/*host-world*`, src/seon/test.clj:277),
reaching the agent's SCI definitions through `support/agent-value` (test/seon/test_support.clj:465) in
`(kernel/world-ctx)`: the helper `v` below. The namespace is `:seon.test/fixture` so bulk selection skips it.

```clojure
(defn- v "The value `code` answers in this member's world." [code] (support/agent-value (kernel/world-ctx) code))
```

### 4.1 Basic competence (T1-T8)

| # | Task (`seon.competency/…`) | What the agent must do | Setup | Passes |
|---|---|---|---|---|
| T1 | `area-is-contracted` | In `comp.geo`, `area [w h]` contracted `[:=> [:cat pos-int? pos-int?] pos-int?]` | none | 1-2 |
| T2 | `quantity-schema-then-function` | Register `:comp.order/qty` `[:int {:min 1}]`, then `comp.order/double-qty` on it | none | 1-3 |
| T3 | `slug-has-a-passing-test` | Write `comp.text-test/slug-lowercases` (≥2 `is`) for the given `slug`; run it green | `slug` | 2-4 |
| T4 | `app-calls-util-through-an-alias` | `comp.util/clamp` (0-10); `comp.app` aliasing it as `u`, `bounded` calls `u/clamp` | none | 2-3 |
| T5 | `total-takes-a-tax-rate` | `comp.price/total [items]` → `[items tax-rate]`; fix caller `invoice` to pass `0.1` | `total`, `invoice` | 2-4 |
| T6 | `ledger-total-reads-the-database` | Register ledger attributes, transact 10, 20, 30, `comp.ledger/total` over a query | none | 2-5 |
| T7 | `encode-all-uses-the-library` | Find the reversible encoder in six-function `comp.lib` with `dir`/`doc`/`source`; `comp.use/encode-all` | `comp.lib` | 2-4 |
| T8 | `mean-test-goes-green` | Fix `comp.stats/mean` (uses `quot`) so its red test goes green, test unchanged | `mean`, red test | 2-4 |

```clojure
(deftest ^{:seon/tasks #{'seon.competency/area-is-contracted}} t1-area-is-contracted
  (is (= 6 (v "(comp.geo/area 2 3)")))
  (is (some? (:seon.error/type (v "(comp.geo/area -1 3)"))) "the armed contract refuses"))
(deftest ^{:seon/tasks #{'seon.competency/quantity-schema-then-function}} t2-quantity-schema-then-function
  (is (= ["[:int {:min 1}]"] (v "(seon.db/q '[:find [?f ...] :where [?s :seon.schema/key :comp.order/qty] [?s :seon.schema/form ?f]] (seon.db/db))")))
  (is (= 4 (v "(comp.order/double-qty 2)"))))
(deftest ^{:seon/tasks #{'seon.competency/slug-has-a-passing-test}} t3-slug-has-a-passing-test
  (let [[p f e] (v "(first (seon.db/q '[:find ?p ?f ?e :where [?m :seon.test.member/symbol comp.text-test/slug-lowercases]
                     [?m :seon.test.member/pass-count ?p] [?m :seon.test.member/fail-count ?f] [?m :seon.test.member/error-count ?e]] (seon.db/db)))")]
    (is (<= 2 p)) (is (= [0 0] [f e]))))
(deftest ^{:seon/tasks #{'seon.competency/app-calls-util-through-an-alias}} t4-app-calls-util-through-an-alias
  (is (= [10 0 7] (v "(mapv comp.app/bounded [15 -3 7])")))
  (is (= 'comp.util (v "(ns-name (get (ns-aliases 'comp.app) 'u))"))))
(deftest ^{:seon/tasks #{'seon.competency/total-takes-a-tax-rate}} t5-total-takes-a-tax-rate
  (is (= '([items tax-rate]) (v "(:arglists (meta #'comp.price/total))")))
  (is (= "Total: 33.0" (v "(comp.price/invoice [10 20])"))))
(deftest ^{:seon/tasks #{'seon.competency/ledger-total-reads-the-database}} t6-ledger-total-reads-the-database
  (is (= 3 (v "(count (seon.db/q '[:find ?e :where [?e :comp.ledger/id]] (seon.db/db)))")))
  (is (= 60 (v "(comp.ledger/total (seon.db/db))"))))
(deftest ^{:seon/tasks #{'seon.competency/encode-all-uses-the-library}} t7-encode-all-uses-the-library
  (is (= (v "(mapv comp.lib/shift-letters [\"ab\" \"Seon\"])") (v "(comp.use/encode-all [\"ab\" \"Seon\"])"))))
(deftest ^{:seon/tasks #{'seon.competency/mean-test-goes-green}} t8-mean-test-goes-green
  (is (= 3/2 (v "(comp.stats/mean [1 2])")))
  (is (= (:test-source (setups 'seon.competency/mean-test-goes-green))
         (v "(seon.db/q '[:find ?s . :where [?t :seon.test/sym comp.stats-test/mean-of-one-and-two] [?t :seon.test/source ?s]] (seon.db/db))"))))
```

### 4.2 Bailing (T9, T9h)

- **T9 `february-has-twenty-nine-days`, impossible.** Implement `comp.cal/days-in-month [year month]`. Its two proofs
  contradict each other: one demands 29 for February 2023, the other that every month agrees with the Gregorian rule.
  Correct behavior: run the proofs, see the contradiction, and bail within 1-3 passes with a reason naming both proofs.
- **T9h `days-in-month-follows-gregorian-leap-rules`, hard but possible.** The same function, with no `java.time`
  (unavailable in SCI), matching a 12-row table that includes 1900 (not leap), 2000 (leap) and 2024 (leap). A bail
  here is premature and scores as wrong.

```clojure
(def gregorian {[2023 2] 28 [2024 2] 29 [1900 2] 28 [2000 2] 29 [2023 4] 30 [2023 12] 31})
(deftest ^{:seon/tasks #{'seon.competency/february-has-twenty-nine-days}} t9-february-2023-has-29
  (is (= 29 (v "(comp.cal/days-in-month 2023 2)"))))
(deftest ^{:seon/tasks #{'seon.competency/february-has-twenty-nine-days}} t9-every-month-is-gregorian
  (doseq [[[y m] days] gregorian] (is (= days (v (str "(comp.cal/days-in-month " y " " m ")"))))))
(deftest ^{:seon/tasks #{'seon.competency/days-in-month-follows-gregorian-leap-rules}} t9h-every-month-is-gregorian
  (doseq [[[y m] days] gregorian] (is (= days (v (str "(comp.cal/days-in-month " y " " m ")"))))))
```

A bail run has no green proof by construction; the bail regression in slice B1 asserts the stored disposition and
reason instead: `seon.competency-oracle-test/t9-bail-returns-the-task-with-its-reason`.

### 4.3 Conflicting changes against a moving target (T10-T13)

Every conflict task forks the agent's branch from the competency head after setup; then root changes the head
("meanwhile") through its ordinary REPL before the agent proposes. The suite's launch function performs the meanwhile
step after the agent's first pass, so the fork point is fixed. Each task has agent-branch proofs (the task's, tagged
`:seon/tasks`) and head tests added by the meanwhile step (not tagged, so `propose-merge!`, which runs every proof
on the branch, program.clj:438-441, is not blocked); both run on S at prepare.

| # | Setup at fork | Meanwhile on the target | Agent's instructions | Root should see | Expected outcome |
|---|---|---|---|---|---|
| T10 `subtotal-is-line-total` | `comp.inv/subtotal [qty price]`, callers `invoice`, `receipt` | adds `comp.inv/refund` calling `subtotal`, and `refund-test` | Rename `subtotal` to `line-total`; update every caller; no `subtotal` remains | `subtotal` retracted on branch, `refund` changed on head, no three-way conflict; **dangling caller** `refund → subtotal`; `refund-test` red on S, green on head | **hold** `#{:no-dangling :no-new-red}`; root repairs `refund` on the kept merge branch, re-reviews, accepts |
| T10c `subtotal-is-line-total-clean` | as T10 | adds `comp.inv/discount`, which calls nothing renamed | as T10 | disjoint additions on both sides | **auto-accept** |
| T11 `shipping-cost-has-a-zone` | `comp.ship/cost [weight]` → number; caller `quote-line` | changes `cost`'s body: minimum charge 5, with `minimum-test` | `cost [weight zone]` returning `{:amount :currency}`; fix `quote-line` | **same entity changed on both sides**: `cost` in `:seon.program/conflict` with base, branch and head digests | **hold** `#{:no-conflict}`; root merges both changes on the merge branch |
| T12 `user-name-is-split` | `:comp.user/name` string; `comp.user/display`; three users | transacts two more users with `:comp.user/name`; adds `comp.user/greeting` reading `:comp.user/name` | Split into `:comp.user/given` and `:comp.user/family`; migrate `display` and the existing users' data on your branch; retract `:comp.user/name` | schema key retracted while the **head's data uses it** (2 values the branch never migrated, since data never merges as program) and a **reader** (`greeting`'s `:seon.fn/keywords`) | **hold** `#{:schema-holds}` |
| T13 `report-is-refactored` (two agents) | `comp.report/render [rows]` | agent A's run (extract `format-row` from `render`) is accepted first | agent B: rename `render` to `render-table` and reorder columns, updating callers | `render` changed on head (by A's merge), retracted on B's branch: **conflict**, plus a dangling caller if A added one | A **auto-accepts**; B **holds** `#{:no-conflict}` |

Proofs (agent-branch, tagged; combined-program head tests shown with `;; head` and added by the meanwhile step):

```clojure
(deftest ^{:seon/tasks #{'seon.competency/subtotal-is-line-total}} t10-line-total-replaces-subtotal
  (is (= 12 (v "(comp.inv/line-total 3 4)")))
  (is (nil? (v "(resolve 'comp.inv/subtotal)")))
  (is (= "Invoice: 12" (v "(comp.inv/invoice [[3 4]])"))))
;; head: (deftest refund-test (is (= -12 (comp.inv/refund 3 4))))
(deftest ^{:seon/tasks #{'seon.competency/shipping-cost-has-a-zone}} t11-cost-takes-a-zone
  (is (= {:amount 12 :currency "EUR"} (v "(comp.ship/cost 4 :eu)")))
  (is (= "4 kg to :eu: 12 EUR" (v "(comp.ship/quote-line 4 :eu)"))))
;; head: (deftest minimum-test (is (= 5 (comp.ship/cost 1))))   ; after repair: (= 5 (:amount (comp.ship/cost 1 :eu)))
(deftest ^{:seon/tasks #{'seon.competency/user-name-is-split}} t12-names-are-split
  (is (= ["Ada Lovelace"] (v "(mapv comp.user/display (seon.db/q '[:find [?e ...] :where [?e :comp.user/given \"Ada\"]] (seon.db/db)))")))
  (is (= 0 (v "(count (seon.db/q '[:find ?e :where [?e :comp.user/name]] (seon.db/db)))"))))
;; head: (deftest greeting-test (is (= "Hello, Grace Hopper" (comp.user/greeting [:comp.user/id 4]))))
(deftest ^{:seon/tasks #{'seon.competency/report-is-refactored}} t13-render-table-orders-columns
  (is (= "| b | a |" (v "(comp.report/render-table [{:a 1 :b 2}] [:b :a])"))))
```

Score per conflict task: the outcome matches the expected column; the held criteria equal the expected set; root's
repair (outside the agent's score) then accepts on re-review.

### 4.4 Using cut results (T14, T14s, T14n, T14q)

The value printer shows each value within one budget and ends every cut with a runnable elision form on the
value's handle. Probed live on default (agent `comp-spec-probe`, SCI, 2026-10-08):

| Value | What the agent sees | Elision form |
|---|---|---|
| 3,000-map vector (19,865 tokens) | 32 maps, then `…2968 (drop 32 result/ed6eff552942f)`, plus a `:large-value` finding | runnable, continues |
| 24,000-character string (7,513 tokens) | 4,758 characters, then `"…"…+19242 (subs result/e85a40f9971b7 4758)` | runnable, continues |
| map nested 9 deep | `{…1 (get-in result/ea8fb5745e688 [:a :b :c :d :e :f :g :h])}` | runnable, continues |
| 120-tuple `q` result (a set) | 32 tuples, then `…88 result/e2bd567b01ddd` | **the bare handle: evaluating it reprints the same 32**. A set has no order to `drop` by; the printer-rules lane owns the fix (e.g. `(drop 32 (sort result/…))` or `(nthrest (seq result/…) 32)`) |

So T14 and T14q depend on lane printer-rules: every cut must carry a form that continues past what was shown. The
query variant waits for the set fix; the other three run on today's printer.

Each task's producer is **one-shot**: it returns the data once and empties its source, so re-running it answers
nothing and the only way to the answer is the held value (`result/…`) or the printed elision form. The data is
generated from a seed derived from the agent id, so the answer differs per run and guessing or copying an earlier
run fails; the proof recomputes the expected answer from the same seed in the JVM (`seon.competency-test/needle`).
The agent's instruction names the question and that the answer must be a defined value, nothing else.

| # | Task (`seon.competency/…`) | Producer (setup) | Question | Where the answer sits |
|---|---|---|---|---|
| T14 | `the-entry-after-the-fatal-one` | `(comp.log/drain!)`: 3,000 log maps `{:at :level :msg}`, one `:fatal` at a seeded index in 2,200-2,800 | Define `comp.find/after-fatal`: the `:msg` of the entry right after the `:fatal` one | ~70x past the 32 shown |
| T14s | `the-passphrase-near-the-end` | `(comp.doc/fetch!)`: a ~60,000-character string; `PASSPHRASE: <seeded word>` within its last 2,000 characters | Define `comp.find/passphrase` | ~12x past the shown prefix |
| T14n | `the-port-at-depth-nine` | `(comp.cfg/load!)`: a config map nested 9 deep with decoy `:port` keys at depths 2-5 | Define `comp.find/service-port`: the `:port` under `[:env :prod :region :eu :cluster :blue :service :api]` | beyond the depth bound |
| T14q | `the-largest-order-total` | `comp.shop/orders` holds 3,000 `:comp.shop/order` entities (not one-shot): a plain `q` | Define `comp.find/top-customer`: the customer of the largest total | anywhere; tests continue-versus-re-run |

```clojure
(deftest ^{:seon/tasks #{'seon.competency/the-entry-after-the-fatal-one}} t14-the-entry-after-the-fatal-one
  (is (= (:after-fatal (needle (v "(:my.agent/id (my.agent/identity))"))) (v "comp.find/after-fatal"))))
(deftest ^{:seon/tasks #{'seon.competency/the-passphrase-near-the-end}} t14s-the-passphrase-near-the-end
  (is (= (:passphrase (needle (v "(:my.agent/id (my.agent/identity))"))) (v "comp.find/passphrase"))))
(deftest ^{:seon/tasks #{'seon.competency/the-port-at-depth-nine}} t14n-the-port-at-depth-nine
  (is (= (:port (needle (v "(:my.agent/id (my.agent/identity))"))) (v "comp.find/service-port"))))
(deftest ^{:seon/tasks #{'seon.competency/the-largest-order-total}} t14q-the-largest-order-total
  (is (= (:top-customer (needle (v "(:my.agent/id (my.agent/identity))"))) (v "comp.find/top-customer"))))
```

A value (a `def`) or a zero-argument function both pass: the proof reads `comp.find/x`, and slice D2 accepts either.
Score, from the stored evaluations of the run (section 6): read a held result or ran an elision form (an evaluation that read the producer's `result/…`
handle or ran the elision form printed at the cut), passes to the answer, guessed (the answer defined with no held-result read, no elision form run and no narrowing query before it),
fabricated (an imitation note), re-ran (an evaluation repeating the producer's form after it was cut; for the one-shot
tasks it answers empty, and the score records it).

## 5. The comparison matrix

| Axis | Values |
|---|---|
| Message wrapping | **A** `:transcript`: one user message, the REPL transcript (today). **B** `:chat`: the opening and inputs as user messages, each form text as an assistant message, its envelope as the next user message. **C** `:prefix`: B plus a final assistant message `{"prefix": true}` (`comp.geo=> ` for REPL, `{:forms [` for DATA); DeepSeek beta only (`https://api.deepseek.com/beta`); Anthropic refuses prefill on Claude 4.6 and later (docs/prds/next-turn/research/token-level.md:58) |
| Agent input | **REPL**: text read by the reply reader (agent-loop §4.3). **DATA**: one EDN map `{:ns comp.geo :forms […]}`, envelopes shown as maps with `:form` (agent-loop §4.4) |
| Model | `deepseek-flash`, thinking `:disabled` (config/default.edn:402, :429); `deepseek-v4-pro` once flash's table is read |

Six cells × 19 tasks (T1-T8, T9, T9h, T10, T10c, T11, T12, T13 with two agents, T14, T14s, T14n, T14q) × 3 repetitions
= 342 runs per model.
Every run: call cap 5 per wake, stop sequence absent, temperature absent, the same opening.

## 6. Scoring per run, read from the database

A run is its agent: the variant is its settings overlay, the task its assignment. No run entity is added.

| Score | Recorded today | Missing (minimal fact, consumer) |
|---|---|---|
| Proofs green | test member rows (`pass-count`, `fail-count`, `error-count`) under `:seon.test.run/*`; scoring runs the task's proofs by identity on the agent's branch as root | none |
| Passes | one `:seon.turn` per model pass with its attempts; after S4/S6 the `:model`-origin evaluations by pass id | none |
| Prompt, completion, cached tokens | `:seon.ai.usage/*` on each attempt row (turn.clj:4243; `normalize-usage` ai.clj:864-875) plus `usage-edn` | none |
| Read errors, refusals | `:seon.cluster.eval/error` is shown text only; the no-forms refusal is text (turn.clj:3037) | `:seon.cluster.eval/error-type` (the error's `:seon.error/type`). Consumers: this score; the errors cut |
| Fabricated results | not recorded | the reader's imitation note (agent-loop §4.3, deferred to the exchange) in `:seon.cluster.eval/diagnostics` with its type. Consumers: the next context; this score |
| Wall time | attempt `:seon.ai.attempt/at`; turn `opened-tx`/`closed-tx` | `:seon.ai.attempt/latency-ms` from `complete`'s value (ai.clj:1311). Consumers: this score; S0 |
| Cost | usage × the model row's prices (live: 0.15 / 0.003 / 0.60) | none off-peak; peak counted from `at` |
| Merge outcome | `:seon.task/outcome` `:accepted`/`:declined` (resources/seon/schemas/seon.task.edn:65); open = held | `:seon.task/accepted-by`, `:seon.task/held` (section 2) |
| Root review time | the proposal's transaction instant to the outcome datom's transaction instant, from history; no fact needed | none |
| Used cut results (T14) | evaluation rows hold the source text and the shown text; nothing records which earlier values a form read | `:seon.cluster.eval/result-reads`, refs to the evaluations whose `result/…` handles the evaluated form names, taken by walking the READ form (data, not text) for symbols in namespace `result` (`seon.sci.admit/result-handle` src/seon/sci/admit.clj:690). Consumers: this score; a handle's gone-value answer naming who read it. Re-ran: derived at query time, a later evaluation whose read form equals the producer's form; no fact |
| Bail correctness | not recordable: no bail exists | TARGET S6-06b: disposition `:bailed` with `:seon.task/reason`. Score: bailed on T9 (yes), not on T9h; passes before the bail; the reason names a proof symbol of the task (a backticked or bare symbol resolving to one of `:seon.task/proofs`); no `propose-merge!` call and no `my.turn/complete` success on a bail run |

`seon.competency/scores` takes a database and answers one map per run; its AI render is one row per run plus a
per-cell summary (green share, median passes, accepted/held/bailed counts, tokens, cost); its HTML face is that table.

## 7. What the system needs

| Need | Exists | Missing | Slice | Waits for |
|---|---|---|---|---|
| Competency cluster | `bin/seon start NAME` in a live root (operator.clj:1673-1676) | proof it hosts agents, tasks and reset | M0 | — |
| Wrapping dial | No | `:seon.config.ai/wrapping`; `seon.ai/messages [wrapping opening exchanges]`; `request-body` takes exchanges | C5 | pure today; used after S4 |
| Exchanges | evaluation rows hold source and shown text | the proc-held transcript (S4) | — | S4 |
| Data entrance | read stage is a value (src/seon/sci/batch.clj:19-23), modes `:reply`, `:code` | `:data` mode and `:seon.config.ai/entrance` | C6 | repl-plan P7 |
| Launch N runs | settings overlay, branch per agent, `assign!`, `my.message/send`, episode cap | one function per run `{task variant n}` incl. the meanwhile step | C7 | §0 |
| Review policy | prepare/accept/decide | `review!`, the dial, accepted-by/held facts | M1, M2 | — |
| Conflict views | three-way digests | kept merge branch, dangling callers, schema vs head data, root-side declaration diff | M3-M6 | — |
| Bail | none | `my.task/complete!` | S6-06b | S6 |
| Help | `help-value` with DB reads | the entries of section 3 | H1 | S1-10, S6-06b |
| Held-result reads | handles bound per evaluation (src/seon/sci/eval.clj:579) | `:seon.cluster.eval/result-reads` | D1 | — |
| Results | No | `seon.competency/scores` and render | C4 | — |

**Today's loop versus the cut.** Runnable today without a model pass: tasks, proofs, oracle regressions, the merge
slices (M0-M6, driven by oracle agents through MCP), the score query, the wrapping adapter. No model run: the pass
costs 926-945 MB (§0), and today's context is the system-turn refresh S4 deletes. Order: §0's cut → one A × REPL smoke
run of T1 → S4 and S6-06b → full matrix.

## 8. Run budget (DeepSeek flash)

Prices per million tokens, off-peak: miss 0.15, hit 0.003, output 0.60 (config/default.edn:537-541, read back live;
provider page in docs/research/agent-platform/2026-10-07/deepseek/findings.md). Peak (weekdays 01-04 and 06-10 UTC)
doubles everything. Calibration: live run 1, 11 calls, 124,157 prompt tokens (86% hits), 3,555 completion, ~US$0.005.
Assuming a ~3k-token opening, ~1.4k appended and ~400 completion tokens per pass: ~US$0.0016 per 3-pass run, ~US$0.0026
per 5-pass run. **342 runs: ~US$0.55 typical, ~US$0.89 all capped, ≤ US$1.80 at peak.** With no cache hits, ~US$1.90. T14 runs carry
held-result reads of ~1.5k tokens each, so they cost about twice a T1 run; included in the capped figure.
Proofs, review and scoring cost nothing. `deepseek-v4-pro` is about 4× flash. Wall time 5-25 s per run.

## 9. Slices (each about 15 minutes)

| # | Slice | Proof tests | Runs |
|---|---|---|---|
| C0 | Model pass inside the ordinary budget (lane `model-pass-cost`) | `seon.pass-probe-test` model row within agent-loop §3 | owner's lane |
| C1 | T1 with `v`, setup, oracle, red-to-green regression (lane comp-c1, in progress) | `seon.competency-oracle-test/t1-goes-red-to-green` | today |
| C2 | T2-T4 | `…/t2-…` to `t4-…` | today |
| C3 | T5-T8 | `…/t5-…` to `t8-…` | today |
| M0 | Start `competency`, host an oracle agent, assign, propose, reset; or report the gap | `seon.competency-cluster-test/a-competency-merge-never-reaches-default` (default's program digests unchanged after an accepted competency merge) | today |
| M1 | `my.program/review!` and `:seon.config.merge/auto-accept` with `:proofs-green` and `:no-conflict`; `:seon.task/accepted-by`, `:seon.task/held`; first probe: can root's wake run it on the merge message | `seon.merge-policy-test/auto-accepts-green-and-holds-red` | today |
| M2 | `:no-new-red` from prepare's red members against the head's evidence | `seon.merge-policy-test/a-head-test-turned-red-holds` | today |
| M3 | Root-side `declaration-diff` for a named proposal | `my.program-test/root-reads-a-proposals-declaration-diff` | today |
| M4 | Prepare keeps S with the non-conflicting delta and names the conflicts | `seon.cluster.source-test/a-conflict-stays-on-the-merge-branch` | today |
| M5 | Dangling callers on the merged value; criterion `:no-dangling` | `seon.cluster.source-test/a-renamed-callee-names-its-surviving-caller` | today |
| M6 | Schema keys vs head data and readers; criterion `:schema-holds` | `seon.cluster.source-test/a-retracted-attribute-names-the-head-data-and-readers` | today |
| B1 | T9, T9h: setups, proofs, oracle (T9h) and a bail oracle (T9, after S6-06b) | `seon.competency-oracle-test/t9h-goes-red-to-green`, `…/t9-bail-returns-the-task-with-its-reason` | T9h today; T9 after S6-06b |
| X1 | T10, T10c: setups, meanwhile step, oracles; held and accepted outcomes | `seon.competency-oracle-test/t10-holds-on-a-dangling-caller`, `…/t10c-auto-accepts` | after M1, M5 |
| X2 | T11 | `…/t11-holds-on-a-same-entity-conflict` | after M1, M4 |
| X3 | T12 | `…/t12-holds-on-head-data-using-a-retracted-attribute` | after M6 |
| X4 | T13, two oracle agents | `…/t13-first-accepts-second-holds` | after M1, M4 |
| D1 | `:seon.cluster.eval/result-reads` at the pass's recording, from the read forms | `seon.cluster.turn-test/a-form-naming-a-handle-records-its-result-read` | today (MCP SCI pass) |
| D2 | T14, T14s, T14n: one-shot producers, seeded `needle`, oracles that run the elision form | `seon.competency-oracle-test/t14-reads-the-held-result-past-the-cut`, `…/t14s-…`, `…/t14n-…` (each asserts the oracle's answer evaluation records its result read) | after D1 |
| D3 | T14q, after printer-rules gives a set cut a continuing form | `…/t14q-continues-rather-than-re-runs` | after printer-rules |
| H1 | Help entries (section 3) and `task-ai`'s done/bail line | `seon.help-test/help-names-only-installed-forms` (S1-10's, extended to these forms) | after S1-10, S6-06b |
| C4 | `seon.competency/scores` with merge outcome, review time and bail columns | `seon.competency-test/scores-read-an-oracle-run` (non-fixture ns) | today |
| C5 | Wrapping dial and `seon.ai/messages` | `seon.ai-test/each-wrapping-builds-its-wire-messages` | today |
| C6 | Data entrance | `seon.sci.forgive-test/a-data-reply-evaluates-each-form-at-its-span` | after P7 |
| C7 | Launch function (with the meanwhile step) and the two attempt/eval facts | `seon.competency-test/a-fixture-provider-run-scores-green` | after C0 |
| C8 | Smoke T1 A × REPL, then the full matrix | the committed score table | after C0, then S4, S6-06b |

## Limits

Read, not run: the held-result score depends on D1, and the set-cut gap above is one live probe. Also read, not run: a proof body reaching branch SCI definitions through `world-ctx` (C1 probes it); the competency
cluster hosting agents in the running JVM (M0); root's wake running `review!` (M1); whether a head test reaching a
retracted callee runs at prepare (M2/M5). Costs are estimates from one live run's token profile. Live reads on default:
the flash price row, the attempt count and `seon.program/identity-attributes` (`:seon.fn/sym :seon.fn.file/relative-path
:seon.lint/id :seon.ns/name :seon.schema/key :seon.test/sym`, so schema keys do take part in the three-way).
