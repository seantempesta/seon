---
type: research
status: active
tags: [research, agent, runtime]
---

# Observer lane — model authoring drive on `default`, 2026-08-10

Independent observation of the model-authoring drive on cluster `default`, run
as the trust-nothing half of a driver+observer pair. This lane took no stimulus
action: it submitted no message, transacted nothing, stopped nothing, reforked
nothing. Every fact below comes from my own read-only Datalog against the live
connection, my own HTTP requests to the running server, and source reads.

I read
[whole-system-arc-observer-2026-08-08.md](whole-system-arc-observer-2026-08-08.md)
end to end before starting, including its four method traps. I did not repeat
any of them. I hit **four new ones** and caught all four; they are in
[Method](#method-and-four-new-traps) because the class matters more than my
embarrassment.

## Verdicts

| # | Claim | Verdict |
|---|---|---|
| 1 | A model-authored contracted function exists | **PROVEN** — `my.agents.root/token-pressure` |
| 2 | It was called and settled a receipt | **PROVEN** — ordinal 1, correct value, no error |
| 3 | Stage 2 automatic render selection happened | **SPLIT: mechanism PROVEN, live occurrence REFUTED** |
| 4 | Token economics stayed sane | **PROVEN** — 10.6k–13.8k prompts, worst c:p 0.417 |
| 5 | Custody stayed clean | **PROVEN** — one holder open, shed on close, always |
| 6 | The UI stayed live | **PROVEN** — 200 throughout; `/data` regression is fixed |

The headline: **this drive did what the 08-08 arc could not — a real DeepSeek
reply authored a durable contracted function and called it, with every form
settling a receipt.** The stage-2 result is the interesting one and is stated
carefully below: the model wrote a correct producer, the selector really does
choose it automatically, and yet nothing in the running system ever rendered
through it.

## Scope

- cluster `default`, pid `91415`, JVM start `2026-08-10T21:25:24Z`,
  prepl `127.0.0.1:56626`, web `http://127.0.0.1:7994`, `ready-ms` 4,199;
- process identity on every held run: `91415-1786397124217`;
- observation window `21:28:17Z` → `21:36:53Z`; drive settled at `21:35`;
- one agent (`root`) throughout — this drive was single-agent, so the 08-08
  concurrency findings were not re-exercised and nothing here speaks to them;
- baseline at window open: 5 runs, 22 forms, 22 receipts, 4 attempts, 7 error
  facts, 2,773 `:seon.fn/sym` facts, 1,941 schemas, 256 `:seon.render/ai` +
  254 `:seon.render/html` producers;
- final: 8 runs, 30 forms, 30 receipts, 7 attempts, 7 captures, 7 error facts,
  2,775 function facts (**+2, both model-authored**), schemas unchanged at 1,941.

Raw dumps under `tmp/` (project-local, gitignored, so every number they
support is reproduced inline in this report rather than left only in a file):
`observer-0810-spec.edn` (authored contract, source, receipt, every attempt
row), `observer-0810-render.edn` (the stage-2 producer and the selection
evidence), `observer-0810-errors.edn`, `observer-0810-msgs.edn`,
`observer-0810-final.edn` (token table), `observer-0810-outside.sh` + `.log`
(external liveness probe).

### Method, and four new traps

Each produced a plausible wrong answer with **no error and no warning**.

1. **`:seon.fn/name` does not exist; the attribute is `:seon.fn/sym`.** My first
   program-graph probe reported `:n-fns 0` on a cluster holding 2,773 function
   facts. A count over an *undeclared* attribute is silently zero — the `:avet`
   trap without needing an index to be involved. Confirm every attribute
   against `[?e :db/ident ?a]` before believing a zero.
2. **There is no `:seon.cluster.eval/receipt` attribute — the eval row *is* the
   receipt**, identified `[run-id ordinal]`. I counted receipts by a
   non-existent attribute and got 0 against 22 real ones.
3. **Bootstrap plan forms masquerade as run forms and fabricate a receipt gap.**
   `[?f :seon.cluster.run.form/ordinal _]` matches **43** entities while only
   **30** are run forms. The other 13 are `:seon.bootstrap.plan.form/*`
   entities that reuse `:seon.cluster.run.form/ordinal` and
   `:seon.cluster.run.form/source` but carry no `:seon.cluster.run.form/run`
   and no `:seon.cluster.run.form/id`. Counting that way reports "43 forms, 30
   receipts" — a 13-receipt gap that does not exist. **The honest probe is the
   set difference of `[?rid ?ord]` pairs joined through `/run`**, which returns
   empty. Any future receipt-gap claim must be by identity, never by count.
4. **`:seon.render/ai` is `db.type/string`, so a symbol producer is stored as a
   string.** I filtered producers with `symbol?` and got zero, which reads as
   "the symbol arm of the producer contract is unused." It is heavily used —
   `seon.error/render-ai`, `seon.bootstrap/render-ai`,
   `seon.cluster.agent/render-creation-ai` and dozens more are symbol producers
   stored as strings.

Two further mechanics future observers need:

- **`print`/`with-out-str` output does not come back through `eval_clj`.** The
  return value is `nil` and the text is simply lost. `spit` to `tmp/` and read
  the file.
- **The MCP projection elides list tails in place**, leaving a vector that
  looks complete but ends in the bare string `"seon.sci.admit/elided"`. My
  `my.*` schema-key roster and the `:seon.fn` attribute roster both came back
  truncated that way. Count by query; dump bodies to a file.
- Restating the predecessor's trap because I re-hit its shape: **`d/q` returns
  a set.** `:distinct-ai-values` is 35 while `:seon.render/ai` has 256 datoms.
  Include the entity in the find to count datoms.
- A Datalog predicate comparing strings, `[(> ?opened-at "2026-08-10T21:29:00Z")]`,
  matched **nothing** and raised nothing, hiding runs that plainly qualified.
  Filter in Clojure after the query.

## Timeline

| Time (UTC) | Event |
|---|---|
| 21:25:24 | JVM start, pid 91415, ready in 4.2 s |
| 21:25:30–21:25:36 | `bootstrap:root` — 13 forms, 13 receipts |
| 21:25:52–21:25:53 | 4 scheduled-maintenance error facts arrive as messages to root |
| 21:25:53–21:26:40 | 4 root runs on those maintenance faults |
| 21:26:10 | run `deb721df` — **model reply unreadable, whole turn discarded** |
| 21:29:12 | **stage 1**: run `30019a4b` defines `token-pressure`, calls it, completes |
| 21:31:20 | **stage 2**: run `9ff4c153` defines `token-pressure-line`, calls it, completes |
| 21:33:57 | run `fe408373` queries both `:seon.fn/spec` facts back and completes |
| 21:36:53 | window ends; 8 runs, 30/30 forms/receipts, no open run |

## Verdict 1 — a model-authored contracted function exists: PROVEN

`my.agents.root/token-pressure`. The deciding query and its answer:

```clojure
(d/q '[:find ?spec . :where [?f :seon.fn/sym "my.agents.root/token-pressure"]
                            [?f :seon.fn/spec ?spec]] db)
⟹ 
"[:=> [:cat [:sequential [:map [:prompt-tokens :int] [:completion-tokens :int]]]]
      [:map [:turns :int] [:prompt-total :int] [:completion-total :int] [:ratio :double]]]"
```

Complete on both sides: no `:any`, no bare `[:maybe ...]`, every key typed. The
program graph went 2,773 → 2,774 function facts at that transaction.

**Provenance — this is a model reply, not bootstrap.** The chain, each link a
fact I queried:

- `:seon.ai.attempt/id "30019a4b-…-attempt-0"`, `:seon.ai.attempt/at`
  `2026-08-10T21:29:12.403Z`, `:seon.ai/model "deepseek-v4-flash"`,
  `:seon.ai/endpoint "https://api.deepseek.com/chat/completions"`,
  `:seon.ai.attempt/finish-reason "stop"`, usage 12,161 prompt / 2,551
  completion, `:seon.ai.attempt/reasoning-size 8094`;
- that attempt's `:seon.ai.attempt/run` is db/id 25939, which is run
  `30019a4b-cbfa-44e2-ad58-ca26e58e01eb`;
- that run's three forms are the `defn`, the call, and `my.run/complete`;
- the `defn` form's source (in `tmp/observer-0810-spec.edn`) is the function
  now recorded in the program graph.

The contrast that makes this decisive: `bootstrap:root`'s 13 forms have **no
`:seon.ai.attempt` row at all**, which is exactly how a bootstrap-authored
function (`my.agents.root/largest`) is distinguishable from this one.

## Verdict 2 — it was called and settled a receipt: PROVEN

Ordinal 1 of the same run called it on a sample and settled a receipt with a
real value and no error:

```clojure
;; form
(token-pressure [{:prompt-tokens 100 :completion-tokens 25}
                 {:prompt-tokens 300 :completion-tokens 75}])
;; receipt :seon.cluster.eval/result-edn, decoded
{:turns 2, :prompt-total 400, :completion-total 100, :ratio 0.25}
```

The arithmetic is correct. All three receipts of the run carry no
`:seon.cluster.eval/error`. Across the whole drive **30 forms and 30 receipts,
zero unsettled pairs, zero `:seon.cluster.eval/interrupted-at`** — so the
`a-runs-last-form-can-close-without-a-receipt` gap did **not** recur here.

## Verdict 3 — stage 2 automatic render selection: mechanism PROVEN, live occurrence REFUTED

This is the finding worth the owner's time, and it is where I disagree with
what a driver report is likely to claim.

**What the model wrote is correct.** Run `9ff4c153` defined
`my.agents.root/token-pressure-line` with the recorded contract

```clojure
[:=> [:cat [:map [:seon.render/value [:map [:turns :int] [:prompt-total :int]
                                            [:completion-total :int] [:ratio :double]]]]]
     :seon.render/ai]
```

and its ordinal-1 call returned
`"Across 2 turns, prompt tokens totaled 400, completion tokens totaled 100, and
the completion/prompt ratio was 0.25."`

**The selector really does choose it, automatically, by contract fit alone.** I
proved this myself rather than taking the driver's word. I handed
`seon.render/render-ai` only the value and the owning namespace — no explicit
producer, no schema declaration, nothing naming the function:

```clojure
(seon.render/render-ai
  {:seon.sci.eval/ctx ctx
   :seon.render/namespace 'my.agents.root
   :seon.render/value {:turns 2 :prompt-total 400 :completion-total 100 :ratio 0.25}
   :seon.db/db db :seon.render/output :seon.render/ai
   :seon.sci.admit/caps {...} :seon.sci.eval/time-limit-ms 30000
   :seon.config/on-core-error :record})
⟹ "Across 2 turns, prompt tokens totaled 400, completion tokens totaled 100,
;;     and the completion/prompt ratio was 0.25."
```

`seon.sci.kernel/public-functions-in` sees all three of the agent's public
functions, and `candidates` (`src/seon/render.clj:110-137`) picked the one whose
input accepts the render argument and whose declared return is exactly
`:seon.render/ai`. That is a genuine capability and it is working.

**But nothing in the running system ever rendered through it.** My own page
fetch is the falsifier: the served debug page for the agent's namespace
contains the summary as the plain map
`{:turns 2, :prompt-total 400, :completion-total 100, :ratio 0.25}`, and the
string `"Across 2 turns"` appears **zero times** anywhere on it. The database
agrees — `:seon.render/ai` still has exactly 256 datoms, none mentioning
`token-pressure`, and there is **no `:my.agents.root/*` schema key at all**.

**The cause is an asymmetry between two selection paths**, which I read in
source rather than inferred:

- the **top-level** path `render-ai`/`render-html`/`render-call` → `producer`
  (`render.clj:203-213`) tries an explicit producer, then **contract fit** via
  `candidates`. This is the path my probe exercised;
- the **nested-node** path `project-node*` (`render.clj:~315`) selects only
  `(get value output)` or `schema-producer` — a producer **declared on a
  registered schema**. It never consults `candidates`.

An agent's returned value appears inside a print tree in its session, so it is
rendered by the nested path. A producer authored exactly as the driver's
instruction described is therefore **inert for the surface the agent can
actually see**, unless something calls the namespace-level render with that
value as the whole render value. The driver's stage-2 message asserts "the
render selector chooses a producer by contract fit alone" as though it were
unconditional; that is true of one path and false of the other, and the drive
never exercised the one where it is true.

I am recording this as a finding for an owner ruling, not calling it a bug: the
comment above `project-node*` shows the nested path deliberately restricts
selection to make a measured 2026-08-07 render cycle unconstructable. Whether
contract fit should also apply at nested nodes is a design question with that
incident attached to it.

Filed: [Contract-fit render selection never reaches a value inside a print tree](../../../seon/issues/contract-fit-render-selection-never-reaches-a-nested-value.md).

## Verdict 4 — token economics: PROVEN sane

Every attempt, from durable `:seon.ai.attempt/usage-edn`, joined to its run's
context capture. `finish_reason` is `stop` on all seven; there is no
truncation ref anywhere and no attempt error.

| At (UTC) | Run | Prompt chars | `tokens/estimate` | Provider prompt | Completion | c:p | Reasoning | Cache hit | Drift |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 21:25:53 | 73a73f84 | 34,798 | 8,699 | 10,766 | 1,469 | 0.136 | 1,349 | 512 | 1.238 |
| 21:26:10 | deb721df | 34,100 | 8,525 | 10,665 | 1,229 | 0.115 | 1,054 | 512 | 1.251 |
| 21:26:24 | 95385948 | 33,989 | 8,497 | 10,605 | 832 | 0.078 | 775 | 1,280 | 1.248 |
| 21:26:35 | 4464c9b8 | 35,513 | 8,878 | 11,056 | 299 | 0.027 | 284 | 1,408 | 1.245 |
| 21:29:12 | 30019a4b | 39,495 | 9,873 | 12,161 | 2,551 | 0.210 | 2,195 | 1,536 | 1.232 |
| 21:31:20 | 9ff4c153 | 43,169 | 10,792 | 13,151 | 5,490 | **0.417** | 5,174 | 1,664 | 1.219 |
| 21:33:57 | fe408373 | 45,446 | 11,361 | 13,755 | 1,716 | 0.125 | 1,573 | 3,584 | 1.211 |

- **No prompt over ~15k tokens and none starved under 1k.** The range is
  10,605–13,755 provider tokens, well inside the 32,768
  `:seon.config.ai/prompt-token-budget`. Unlike 08-08, the budget was never
  breached — because this drive was short, not because anything changed.
- **Growth is bounded and gentle**: 10,766 → 13,755 over seven turns, roughly
  +500 tokens per turn as the session transcript accretes. Cache hits grow with
  it (512 → 3,584), so the marginal cost grows more slowly than the prompt.
- **Worst completion:prompt ratio is 0.417**, on the stage-2 turn — the one that
  had to reason about a render contract. Against the 46.7 pathology the
  directive told me to watch for, this is nowhere near trouble.
- **The 08-08 estimator blocker is genuinely FIXED, and I confirmed it against
  the provider's own counts.** I drafted the opposite claim first — "the drift
  reproduces, 1.21–1.25×" — and the facts refuted me, so the correction is
  recorded here rather than quietly dropped. `seon.ai.tokens/estimate`'s
  one-argument arity *is* still chars/4, but its docstring says so
  ("uses the uncalibrated `shipped-calibration`… supply a calibration whenever
  one is derivable — a budget check always should"), and
  `seon.cluster.prompt/model-calibration` fits the ratio from the cluster's own
  attempt facts. The recorded contribution tokens prove the calibration is live:

  | Capture | Prompt chars | Recorded estimate | Provider | Error | chars/token |
  |---|---:|---:|---:|---:|---:|
  | 73a73f84 | 34,798 | 8,699 | 10,766 | **−19.2%** | 4.00 (uncalibrated) |
  | deb721df | 34,100 | 10,550 | 10,665 | −1.1% | 3.23 |
  | 95385948 | 33,989 | 10,572 | 10,605 | −0.3% | 3.22 |
  | 4464c9b8 | 35,513 | 11,057 | 11,056 | +0.01% | 3.21 |
  | 30019a4b | 39,495 | 12,297 | 12,161 | +1.1% | 3.21 |
  | 9ff4c153 | 43,169 | 13,408 | 13,151 | +2.0% | 3.22 |
  | fe408373 | 45,446 | 14,062 | 13,755 | +2.2% | 3.23 |

  Once calibrated the estimate tracks the provider **within ±2.2%**, and the
  fitted 3.21–3.23 chars/token independently reproduces the 3.23/3.28 the
  archived note fitted — from a different cluster's facts. That is a fix that
  held up under adversarial measurement.

  **The one residual**: the very first turn after a fresh boot has no attempt
  facts to fit, falls back to the uncalibrated chars/4 basis, and is **19.2%
  low** — the exact error the blocker was about. It is bounded (one turn per
  JVM), it names itself as uncalibrated by design, and the budget check adds
  the calibration's error band, so I am recording it as a known edge rather
  than reopening the note. It would only bite a first prompt already near the
  32,768 budget.
- **`thinking :disabled` is still billing reasoning tokens**: 90–94% of every
  completion is reasoning (5,174 of 5,490 on the stage-2 turn), reproducing the
  08-08 observation.
- **One paid turn was discarded.** Run `deb721df` opened and closed inside the
  same second at 21:26:10 with **zero forms**, because the reply did not read:
  `:seon.cluster.reply/unreadable` — `"EOF while reading, expected ) to match (
  at [12,1]"`. We paid for 10,665 + 1,229 tokens and recorded nothing but the
  error. This is a different mechanism from the mid-stream disconnect already
  filed (the body arrived complete and finished with `stop`; the *forms* were
  unbalanced), and the run is indistinguishable by query from a run that simply
  had nothing to do. Filed:
  [A run whose reply does not read closes empty and silent](../../../seon/issues/an-unreadable-reply-closes-a-run-with-no-forms-and-no-trace.md).

## Verdict 5 — custody: PROVEN clean

Every sample, every run: an open run carried exactly one
`:seon.cluster.run/process` and it was always `"91415-1786397124217"`; every
closed run had shed it. My census computed `:closed-still-held` on every sample
and it was empty every time, including the samples taken while a run was open
and held (`30019a4b` at 21:29:23, `9ff4c153` at 21:31:35). No orphan holder, no
double hold, no stale pid. 8 runs, 8 clean transitions.

I specifically avoided the 08-08 sampler's crash: `:seon.cluster.run/process` is
a **string**, so a map-spec pull on it throws exactly when a run is open — the
failure mode that once produced "no open runs, custody clean" as a confident
wrong answer. My census pulls it as a plain attribute.

## Verdict 6 — the UI stayed live: PROVEN

An external probe (`tmp/observer-0810-outside.sh`) sampled `/` every 5 s for the
whole window and recorded **200 on every sample**, 15–18 ms each, with no gap.
Route measurements I took myself:

| Route | Status | Bytes | Cold | Warm |
|---|---|---:|---:|---:|
| `/` | 200 | 289,577 → 308,786 | 1.31 s | 0.016 s |
| `/ns/my.agents.root` | 200 | 289,577 | — | 0.017 s |
| `/ns/my.agents.root/debug` | 200 | 39,075–46,931 | — | 0.030 s |
| `/data` | 200 | 3,147 | 0.32 s | **0.123–0.131 s** |
| `/feed/root` | 200 | 367,596 B in 8 s | — | ~46 KB/s sustained |

No renderer-unavailable box, no error wall, and no `"Renderer unavailable."`
string on any page I fetched. The page grew with the drive (289,577 → 308,786
bytes) and served `token-pressure` 23 times within a minute of it being
authored, so live update through the database is working end to end.

**`/data` is fixed.** The open issue records 5.5 s (08-08 measured 6.4–6.5 s for
the same 3 KB). I measure **0.123–0.131 s across four consecutive samples**, a
~50× improvement, with a real warm path. Evidence appended to
[Return `/data` without a five-second stall](../../../seon/issues/data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md);
I left it open for the owner to close since I did not verify the fix's cause.

## Other findings

- **The wrong-arity defect reproduces**, now on a third namespace. Bootstrap
  ordinal 10 calls `(largest)` and the recorded error is
  `"No such namespace: my.agents.root"` — while ordinal 1 entered that
  namespace, ordinal 8 defined `largest` in it, and ordinals 9 and 11 called it
  successfully in the same run. The message is false and it is the shipped
  teaching lesson. Existing issue:
  [a-wrong-arity-call-reports-a-missing-namespace](../../../seon/issues/a-wrong-arity-call-reports-a-missing-namespace.md).
- **Error data is still oversized for operator faults.** `:seon.error/data-edn`
  is 177,293 characters for `:seon.operator/reap-incomplete` and 175,215 for
  `:seon.operator/process-census-incomplete` — two print trees serialized into
  error data. Better than the 4.25 MB of the predecessor's window but far above
  the 158 KB worst case of 08-08, and these are routine scheduled-maintenance
  faults, not exotic ones. Evidence appended to
  [contract-violation-serializes-print-tree-inside-error-data](../../../seon/issues/contract-violation-serializes-print-tree-inside-error-data.md).
- **The declaration-population fallback warning still fires constantly.** Nine
  of my probes emitted `DECLARATION POPULATION FALLBACK ×N` lines (×1 through
  ×1000) from `seon.print`, `seon.schema.datahike`, `seon.sci.admit`,
  `seon.instrument`, and `seon.cluster` — one probe alone reported ×1000. It is
  noise on every single read. Existing issue:
  [value-admission-resolves-the-declaration-population-per-node](../../../seon/issues/value-admission-resolves-the-declaration-population-per-node.md).
- **Contract refusals read very well.** Building the render request wrong three
  times gave me three precise, flat refusals naming exactly the missing or
  wrong keys — `:seon.sci.admit/caps`, then the four
  `:seon.config.eval.result/*` sub-keys, then
  `":degrade" should be either :record or :panic`. Each one told me the next
  move. This is the loud-refusal ethos working as intended.

## What is genuinely in good shape

Calibration, not alarm.

- **Model authoring works end to end.** A real provider reply defined a
  contracted function, the contract landed as a durable queryable fact, a later
  form called it, the receipt settled with the right value, and a still later
  turn queried its own contract back out of the database. That is the whole
  loop this program is for, and it closed twice in eight minutes.
- **Receipts are complete.** 30 forms, 30 receipts, zero unsettled, zero
  interrupted. The 08-08 gap did not recur.
- **Custody is boringly correct** across eight runs.
- **Contract-fit render selection is real**, and I confirmed it by probe rather
  than by reading a design document.
- **The web surface is fast and stayed up**, and the `/data` stall that two
  prior lanes filed is gone.
- **Token behaviour is unremarkable in the good way** — bounded growth, healthy
  ratios, growing cache hits, `stop` every time.
- **The durable diagnostics did the work again.** `:seon.ai.attempt/*` gave me
  the provenance chain that separates model authoring from bootstrap authoring;
  `:seon.context.capture/*` made the estimator drift measurable; the eval
  receipt's `result-edn` proved the call. None of it required touching process
  memory.

## Issues

New:

- [Contract-fit render selection never reaches a value inside a print tree](../../../seon/issues/contract-fit-render-selection-never-reaches-a-nested-value.md)
- [An unreadable reply closes a run with no forms and no trace](../../../seon/issues/an-unreadable-reply-closes-a-run-with-no-forms-and-no-trace.md)

Evidence appended to existing notes:

- [Return `/data` without a five-second stall](../../../seon/issues/data-page-takes-five-and-a-half-seconds-for-three-kilobytes.md)
- [Keep contract-violation evidence as data](../../../seon/issues/contract-violation-serializes-print-tree-inside-error-data.md)

Independently CONFIRMED as fixed, no action needed:

- [Bound the prompt by the provider's token count, not a chars/4 estimate](../../../seon/issues/archive/prompt-token-budget-is-checked-against-a-25-percent-low-estimate.md)
  (archived/resolved) — calibrated estimates track the provider within ±2.2%
  on seven fresh samples; the fitted ratio reproduces the note's own.
- [Name the arity when an agent calls its own function with the wrong one](../../../seon/issues/a-wrong-arity-call-reports-a-missing-namespace.md)
  already carries 2026-08-10 evidence including this exact `my.agents.root`
  case; I confirmed the recurrence and added nothing rather than duplicating.
