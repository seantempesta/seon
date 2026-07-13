---
type: research
status: active
tags: [research, agent]
---

# LoRA training-data audit — every pair judged by the live REPL pipeline

**Date:** 2026-07-12 · **Question:** does the frontier-draft LoRA training
set (619 train + 28 valid pairs, [[qwen-lora-2026-07-12]] §Data generation)
actually check out in a real runtime, is it representative of the mined
distribution, and what does that mean for the A2 recipe? · **Method:** all
557 non-abstain kept pairs driven through the LIVE turn pipeline
(`run-turn!` → reader → `eval-batch!` → instrumented fns → `:seon.eval`
rows) on hermetic per-pair `:memory` worlds in the pinned worktree
(93c8d8ad). The mechanical curation filter had **no REPL eval** — this
audit is that missing gate, run after the fact.

TL;DR — **149/557 = 26.8% of the kept pairs hard-fail the live runtime**
(they would have been rejected by the REPL-proven pipeline design.md
§Data sources specifies). The failures are NOT random: **49% of everything
DeepSeek-authored** (kept 48.6%, kept-repaired 51.9%) fails, while **all
254 mechanical-fallback targets are clean**. One filter blindspot explains
128 of the 149: `head_known` matched bare fn names against the whole
170-fn index, but an agent's home ns resolves only 5 aliases + 5 refers —
so `(list-open …)`, `(query '[…])`, `(remember …)` pass the filter and are
undeclared vars at serving time. Coverage is far narrower than it looks
(15/170 fns via resolvable heads), the kind mix is skewed vs the mined 214
(register 35.5% vs 5.4%; query 3.1% vs 14.3%), and the contexts themselves
teach broken grammar (70 unbalanced echoes, 45 fabricated query results).
Two core bugs surfaced as by-catch (silent-ok eval on quoted-arg
undeclared heads; `db/query` silently returning `#{}` on a request map
without `:seon.db/query`). Verdict: text-staged generation is salvageable
only WITH the eval gate; A2 needs db-staged worlds for anything
query/report-shaped.

## Method — the audit harness

Three committed pieces (all pin-targeted, run in `/Users/sean/src/seon-pin`):

1. `src-needle/scripts/lora_audit_manifest.py` — parses each kept pair's
   situation into a staging recipe: the plan block's render grammar →
   verbatim `:my.plan/*` step rows (exact ids/titles/status glyphs, a
   minted root), worker/peer agents from the message text, and the
   transcript's echoed evals (the situation's CLAIMED history) with parse
   repairs recorded. Emits `src-needle/data/lora/audit-manifest.jsonl`.
2. `src-needle/audit/seon/needle_lora_audit_test.cljs` — env-gated
   (`SEON_LORA_AUDIT`) cljs.test ns, copied into the pin's `test/` and
   compiled as its OWN `:node-test` build (`:lora-audit`, local
   shadow-cljs.edn patch in the pin). Per pair: fresh
   `client/open-agent-conn!` world → `setup-agent-ns!` + `agent/create!`
   (the gym's boot order) → `seed-core!` + plan tx + `open-run!` → ONE
   scripted turn replaying the echoes → ONE scripted turn whose LLM reply
   IS the raw target (the gym's stub `llm-fn`), so the target passes
   through the real reader/segmentation/eval/record path — then reads the
   turn's `:seon.eval` rows + live values back. Live-pod instrumentation
   posture (`index-core!` rows + `instrument-from-db!`, 699 wrapped,
   injection verified working in-harness).
3. `src-needle/scripts/lora_audit_report.py` — classification + stats;
   stable numbers in `src-needle/data/lora/audit-summary.json`.

Parity checks done before trusting verdicts: `db/current-agent-id`
resolves inside a scripted turn; `plan/reconcile!` with omitted
`:seon.agent/id` succeeds via the injecting wrapper (it fails if the
audit shares a process with the suite's instrument/uninstrument fixtures
— hence the isolated build); explicit-id plan calls, `register!`,
`transact!`, `message/user`, `complete` all behave as on a live pod.

Harness limits (honest): fs is rooted at the pin's `src/`+`docs/`
(read-only, the gym posture); no network/shell executes (every such
target had a bare head and died at the reader/analyzer before any
effect); report-stage worlds can only be staged as completely as the
transcript window allows — 5 arc pairs land in a separate `staging-gap`
class rather than being called defective.

## Q1 — does it check out in the REPL?

557 non-abstain kept pairs (the 90 abstain rows have empty targets —
nothing to eval; mechanically verified all-empty):

| class | pairs | share |
|---|---|---|
| eval-clean-after-staging | 388 | 69.7% |
| eval-error | 143 | 25.7% |
| eval-clean (no situation staging needed) | 15 | 2.7% |
| envelope-error (verb refused, `ok? false`) | 6 | 1.1% |
| staging-gap (unverifiable under text-staging) | 5 | 0.9% |

Detail: undeclared-var 111 · undeclared-var-silent-ok 17 ·
other-error 10 · invalid-args 4 · read-error 1 · envelope 6.
Per form: 1041 eval rows, 153 failed, 6 envelope-refused.
id-ungrounded: **0** (the ingredients gate did hold for `XXX-26…` ids —
but see the attr-invention note in Q2; the gate never covered attribute
names).

**The split that matters — by curation status:**

| status | pairs | hard-fail | rate |
|---|---|---|---|
| kept (DeepSeek verbatim) | 251 | 122 | **48.6%** |
| kept-repaired | 52 | 27 | **51.9%** |
| kept-mech (mechanical fallback) | 254 | 0 | **0%** |

Everything clean is either the correct-by-construction mechanical gold or
the subset of drafts that used the aliased simple calls (`plan/done!`,
`schema/register!`, `db/transact!`). Half of what the frontier model
actually authored — the whole point of frontier-draft mode — is broken at
serving time. The valid split is no better: 5/25 non-abstain valid rows
fail (the held-out-loss curve is measured 20% on broken targets).

### Failure taxonomy, with verbatim exemplars

**1. Bare heads that cannot resolve in an agent's ns — 128 pairs (23%).**
The filter's `head_known` accepted any bare name found in the 170-fn
index ∪ clojure.core, but the home ns wires only
`message/ agent/ schema/ db/ plan/` + refers
`wait complete pause resume terminate`. The KT3 cards render fns as bare
`(defn list-open …)` with NO namespace, so DeepSeek emitted bare names —
systematically:

```clojure
(list-open {:seon.agent/id "q0V-2607130202"})
;; ⟹ `my.agent.q0V-…/list-open` is not defined. This form ran NOTHING.
;;    Did you mean `plan/list-open`?

(query '{:find [cuisine (count r)]
         :where [[r :my.recipe/cuisine cuisine]]})
;; ⟹ `my.agent.q0V-…/query` is not defined … Did you mean `db/query`?
;;    (also: non-? datalog vars — broken twice over)
```

Top bare heads: `list-open` 24, `query` 21, `search` 6, `transact!` 5,
`plan!` 4, `load` 4, then a long tail (`remember`, `text`, `run-bg!`,
`py-run`, `grep`, `fetch`, `show!` …) — the ENTIRE kt2b "wide fn surface"
family is written this way: **92/127 kt2b pairs (72%) fail**.

**2. clojure.core shadowing — the nastiest subclass.** A bare name that
IS in clojure.core silently calls the wrong function:

```clojure
(drop {:my.plan/id "QkD-2607130255"})   ; my.plan/drop! intent
;; ⟹ Assert failed: (number? n)         ; clojure.core/drop
(load-file {:seon.agent.fs/path "/app/access_log" :max-lines 5})
;; ⟹ goog.nodeGlobalRequire is not a function
```

(`(next {:seon.agent/id …})` — the `my.plan/next` intent — returns `nil`
via clojure.core/next with ok? true: recorded eval-clean by the audit and
by any mechanical gate, yet it answers the user's ask with nothing.)

**3. Refers called with the wrong shape — 4 pairs.** `complete` does
resolve (a lifecycle refer), but takes a string result, not a map:

```clojure
(complete {:summary "Subscription audit groundwork completed — …"})
;; ⟹ :malli.core/invalid-input
```

**4. Value/shape defects the DB rejected — 6 envelope pairs.**

```clojure
;; stored nil (optional = absent violated); curation didn't check values
{:my.tool/name "torque wrench" … :my.tool/due nil}
;; ⟹ "Malli validation failed for :my.tool/due: expected :string, got nil"

;; the plan-keys vocab gate accepted :steps (it's a my.plan RESPONSE key)
(my.plan/plan! {:my.plan/title "…" :my.plan/steps [...]})
;; ⟹ "plan!: unknown key :my.plan/steps Accepted my.plan keys: …"

;; kt2b db-writes invent bare-ns attrs and transact them unregistered
{:cache/id :KESTREL :cache/weight 42.5}
;; ⟹ "Unregistered attributes in transaction: [:cache/id :cache/weight]"
```

**5. The curation pipeline corrupted one target itself** (`kept-repaired`,
in train.jsonl): the id-string repair regex turned
`:my.plan/id :utY-2607121831-1` into `:my.plan/id "utY-2607121831"-1` —
an odd-entry map literal that no longer parses (`READ ERROR — No value
supplied for key: :open`). The repair pass never re-parsed its own
output.

### The contexts are defective too (what the model is TAUGHT)

The audit also replayed every context's echoed history through the live
pipeline (700 echo evals):

- **70 pairs (12.6%) carry an unbalanced echo** — the generator's 3-map
  transact echo template emits `…rating 4}]}})` (one extra `}`), so the
  transcript the model learns from contains an unparseable form
  (bookkeep-step ×45, report ×25). The SAME 70 windows also dropped the
  `register!` lines, so the echoed transact could never have succeeded in
  the claimed order — the situation's own history is unrealizable as
  written.
- **All 45 finish-stage contexts fabricate a result**: they echo
  `(seon.db/query {:query '[…]}) ⟹ #{"miso ramen" …}` — the bare
  `:query` key call actually returns `#{}` (verified live). The context
  teaches that a wrong-shaped call yields data.

### Core bugs found as by-catch (Report Code Smells)

1. **Silent-ok eval on quoted-arg undeclared heads** (pin 93c8d8ad,
   deterministic repro): `(frobnicate-xyz '[:find ?x])` — an undeclared
   bare head whose argument is QUOTED — records `:seon.eval/ok? true`
   with NO stashed value; the same head with a map arg correctly fails
   with the undeclared-var error. 17 pairs rode this into "ok". This is a
   live-pod false-confidence bug (the A.4 class) AND it taints the
   ok-eval mining criterion: some of the mined-214's "ok" turns may be
   exactly such silent no-ops. Worth its own unit; not fixed here.
2. **`db/query` silently returns `#{}`** for a request map carrying no
   `:seon.db/query` key (e.g. bare `:query`) instead of an error
   envelope. Both audit-visible and agent-visible.
3. (Data-layer, informational) the runtime-reliability lane's live pod
   crashed during this session on an unrelated `:core` fault
   (`SEON-CORE-FAULT :malli.core/invalid-input @t=536871576`,
   `datahike.db.utils Bad attribute type: nil` right after FEED OPEN) —
   noted for that lane, untouched here.

## Q2 — is it complex, does it cover the system?

**Forms per pair** (substantive, ns-moves excluded): 1 form ×369 (66%),
2 ×79, 3 ×15, 4 ×90 (the per-attribute register bundles), 5+ ×3. The set
is overwhelmingly single-form; the multi-form mass is one template (the
register bundle).

**Fn coverage of the 170-fn index** (the brief's "168" is two fns stale):
**15 fns via heads that actually resolve**; 55 counting bare-name intent.
Uncovered areas: seon.db 23 fns, seon.schema 13, my.kb 11, my.canvas 10,
seon.test.runner 9, seon.agent.fs 8, my.ui 6, my.skills 5,
seon.agent.schedule 5 … The kt2b family was supposed to buy surface
width; bare heads made that width fake — the LoRA can only have learned
~15 callable usages.

**Form-kind distribution vs the mined 214** (same edamame analyzer, both
sides):

| kind | synthetic | mined-214 |
|---|---|---|
| register | **35.5%** | 5.4% |
| plan | 25.0% | 28.5% |
| other | 21.6% | 24.8% |
| ns-move | 8.7% | 17.7% |
| transact | 6.2% | 1.7% |
| query | **3.1%** | **14.3%** |
| defn | **0.0%** | 7.6% |

The synthetic set is register-heavy 6.5× and query-light 4.6× vs what
real agents do; `defn` (7.6% of real turns) is absent by v0 design. The
model is being tuned toward schema-bundle emission and away from the
query/report behavior that the held-out set actually measures.

**Multi-step arc depth / state-coupling:** 279 pairs (50%) reuse a
context id, 53 (10%) reuse only context-established attrs, **225 (40%)
are context-free** — their target would read the same under any
situation. The arc family is genuinely state-coupled; kt2b mostly isn't.

**Argument realism (copy-fidelity):** of pairs with quoted strings in the
target, 278 copy every string verbatim from the situation (the intended
copy-task fidelity — reconcile!/seed rows), 112 are all-fresh (mostly
kt2b answer-strings like "The final total came to 59.5 kg." — invented,
ungrounded in any computed value), 127 have no strings. The `XXX-26…` id
gate held (0 violations), but **attribute names were never gated**: kt2b
targets freely invent `:cache/weight`-style bare-ns attrs the situation
never established (2 pairs query them, 6 more transact them).

## Q3 — verdict for the A2 recipe

**What fraction would the full REPL-proven pipeline have rejected?
149/557 = 26.8%** of kept pairs (by construction: parse ∧ eval-ok ∧
envelope-ok on a staged world), plus 5 staging-gap pairs it would have
DECIDED that text-staging cannot. Concentrated: **49.2% of the 303
DeepSeek-authored pairs** vs 0% of the mechanical 254. The $0.24 draft
spend was cheap, but half its yield is training-data poison that one
`eval` call per pair would have caught.

**Is text-staged generation good enough?** As run — no. Three gaps, in
increasing depth:

1. **Fixable without db-staging (most of the damage):** the resolvability
   blindspot. Gate heads against the REAL home-ns resolution table
   (aliases + refers + fully-qualified real ns), not name-existence in
   the index — this alone kills ~128/149. Same for the plan-keys vocab
   (request keys, not all `::` tokens), value-level nil checks, and
   re-parsing after every mechanical repair. But note these fixes are
   converging on re-implementing the evaluator predicate by hand — the
   eval gate IS the mechanical filter done right.
2. **Needs a REAL world even when text-authored:** envelope verdicts
   (`plan!: unknown key`, unregistered attrs, nil values) and the
   injected-key behavior only exist at eval time; and the eval gate needs
   the staged world to distinguish "wrong call" from "empty world".
   This audit's harness (staging ladder + scripted turns) is exactly that
   machinery and is reusable as A2's gate as-is.
3. **Needs db-staged situations (the design's value-tier):** anything
   whose TARGET is a read. Report/query pairs on text-staged worlds are
   either unverifiable (5 staging-gap pairs — the window dropped the
   history), vacuous (`#{}` results — 2 pairs), or fabricated (45 finish
   contexts echoing invented result sets). A db-staged world gives
   byte-exact contexts rendered by the real profile AND checkable query
   results — the render-grammar drift risk the qwen-lora doc flags as a
   limitation also disappears. Quantified floor for "db-staging changes
   the verdict": ~52 pairs (5 gap + 2 vacuous + 45 fabricated-result
   finish contexts) — ~9% of the set — plus the unmeasurable value of
   contexts that stop teaching unparseable/fabricated history (70 + 45
   pairs' inputs).

**A2 recipe:** keep frontier drafts as raw material (the distribution
anchor is real — the aliased-call subset was largely correct), but stage
every situation as a value-tier db world (`db-with` over the seeded base,
per design.md), render the context FROM the world, and mint a pair only
after the target passes eval + envelope on that world. Rejected drafts
stay as negative/correction data. The gym-stub turn driver used here is
the pattern (one scripted turn = one candidate reply); the manifest
builder's plan-block parser becomes unnecessary once worlds are staged
first and rendered, not reverse-engineered.

## What this means for the in-flight LoRA eval's credibility

- **The trained behavior mix is now known:** ~72% of pairs teach correct,
  mostly mechanical-template behavior (plan bookkeeping with explicit
  ids, per-attribute register bundles, seed transacts, reconcile!,
  abstain); ~27% teach calls that fail (or silently no-op) on a live
  pod, with bare heads systematically presented as valid.
- **A "beats the stock bars" verdict on KT3/KT3-redux text-similarity
  scoring is NOT evidence of usable suggestions.** The scorers match
  emitted text against `target_bundle`s; a LoRA that faithfully learned
  `(list-open …)` / `(query '[…])` bare-head patterns can score while
  every such suggestion is a serving-time no-op. Any positive eval
  result should be caveated until preds are re-scored with a
  resolvability check (cheap: the manifest builder's head table) or
  re-evaled through this harness.
- **The register-heavy/query-light skew** means the weakness the finetune
  was targeting (register .100) gets massive signal (35.5% of forms),
  while the held-out's largest kind (query 14.3%) gets almost none —
  expect asymmetric deltas, and don't read a register-driven aggregate
  lift as general competence.
- **The mined held-out itself needs a re-audit**: the silent-ok bug means
  "ok-eval turns" can include quoted-arg undeclared no-ops; the same
  harness can re-verify the 214 (not done here — different render
  grammar needs its own staging pass; recommended follow-up).
- The 28-row valid set carries 5 broken targets — val-loss compares
  against defective references for 20% of its rows.

## Runbook (rerun end-to-end)

```bash
# 1. manifest (main tree)
python3 src-needle/scripts/lora_audit_manifest.py
# 2. harness (pin; needs npm ci + out/bootstrap once)
cp src-needle/audit/seon/needle_lora_audit_test.cljs \
   /Users/sean/src/seon-pin/test/seon/
#    pin shadow-cljs.edn carries a LOCAL :lora-audit :node-test build
#    (ns-regexp "needle-lora-audit-test$") — isolation from the suite's
#    instrument/uninstrument fixtures is REQUIRED (stale instrumented?
#    flags otherwise make instrument-from-db! skip vars → injection off)
cd /Users/sean/src/seon-pin && clojure -M:cljs compile lora-audit
SEON_LORA_AUDIT=1 SEON_CONFIG=config/test.edn \
SEON_LORA_AUDIT_MANIFEST=$SEON/src-needle/data/lora/audit-manifest.jsonl \
SEON_LORA_AUDIT_OUT=$SEON/src-needle/data/lora/audit-results.jsonl \
node out/lora-audit/test.js       # 557 pairs ≈ 10 min, CPU-only
# 3. classify + stats (main tree)
python3 src-needle/scripts/lora_audit_report.py
```

`SEON_LORA_AUDIT_LIMIT` / `SEON_LORA_AUDIT_ONLY=sid,sid` scope smoke
runs. Derived data (manifest/results/summary) is gitignored under
`src-needle/data/lora/`.
