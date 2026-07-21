---
type: research
status: active
tags: [research, agent, context, flow]
---

# Transcript waste characterization — is the 20k WASTE or legitimate history? (2026-06-28)

> Owner question, answered BEFORE we build any transcript clipping: is the agent
> transcript big because of WASTE (agents not thinking things through — sloppy/failed
> evals, oversized dumps, redundant re-queries, verbose narration) or because of
> LEGITIMATE working history (big values the agent genuinely needed)? Read-only,
> measured on the LIVE default pod (7890), agents `root` (118 evals, 20,315 tok) and
> a focused non-root comparator `BnP-2606281709` (76 evals, 9,448 tok). All numbers
> are TOKENS (`seon.ai.tokens/estimate`, chars/4). No writes, no LLM drive.

## TL;DR — the hypothesis is CONFIRMED: it's WASTE, not legitimate history

**~70% of `root`'s 20,315-token transcript is waste from ONE failure mode — the agent
could not find/use the `message`/`wait`/`complete` verbs and flailed.** Concretely:

- **A redundant `search/grep` flood = ~11,230 tok (~55% of the whole transcript).**
  24 grep evals, ALL near-duplicate hunts for `defn wait` / `defn- (wait|complete)` /
  `init-message-verbs` / `intern` across `src/seon/agent/message.{clj,cljs}` and
  `lifecycle.{clj,cljs}`. They produced **78% of ALL value-body tokens** (8,083 of
  10,373). This is not "a big value the agent needed and re-references" — it is the
  same search retyped 24 ways because the verb wouldn't resolve. Several are byte
  duplicates.
- **The message-verb hunt's other tendrils = ~3,300 tok more**: 15 `message`/`wait`/
  `complete` evals (9 FAILED — the `init-message-verbs!` install-timing race) +
  12 `resolve`/`ns-publics` probes for the same vars.
- **Failed evals overall: 27 of 118 (23%)**, ~3,934 tok of pure waste — and they split
  into exactly the three known bugs: uncommented model prose parsed-as-a-form
  (`` `:my.kb/source` + … `` , a lone `"`), the **`result/<id>` in-form Promise trap**
  (`(get-in result/4IU-… [:seon.render/text])` failing repeatedly), and undefined
  verbs (`(wait …)`).
- **Legitimately useful working history is the MINORITY** — the genuine DB work
  (`db/store-inventory`, real queries the agent used) is only the `:db` class:
  **8 evals, ~1,486 tok (~7%)**. Generous accounting of useful `:other` ok evals +
  real narration lands legitimate history at **~25–30%**.
- **The big-value rows are OLD, but the content is itself waste.** The oldest 103
  evals hold 9,685 of 10,373 value-tok and ALL 24 greps; the newest 15 hold ~688.
  So age-tiering WOULD mechanically reclaim them — but it would be **compressing a
  redundant grep flood, not bounding legitimate re-referenced values.** The eviction
  design's load-bearing premise ("old value → `result/<id>` pointer, agent
  re-references when needed") does **not** hold for the dominant content: the agent
  would never re-reference grep-dump #14.

**Therefore the right fix is a MIX, but the PRIMARY lever is UPSTREAM, not clipping:**

1. **(b) Upstream eval discipline + verb discoverability — the #1 fix.** Kill the
   `message`/`wait`/`complete` install-timing + discoverability problem (the charter's
   "`message/user` not defined burned ~100 evals" item, Core). That single fix prevents
   the ~14,500-token hunt-and-flail from ever being produced. Nothing about clipping
   touches this.
2. **(c) The pending-Promise stash self-heal** (`src/seon/eval.cljs:2624-2631`, per
   `fabrication-root-cause-2026-06-28.md` fix #2). Kills the `result/<id>` in-form
   re-query waste — the repeated `(get-in result/4IU-… …)` failures.
3. **Bound the dump WHEN it's produced, not when it ages.** `search/grep` returns
   ~300–700 tok per call; cap the result at the source so even a (less-redundant)
   search burst can't each dump full context. Value-rendering-at-source.
4. **(a) Age-tiering / clipping — a BACKSTOP, not the cure.** Still worth doing for
   BOUNDEDNESS (a long task agent grows unbounded regardless of discipline), but on
   real transcripts today it compresses waste, and its re-reference premise is mostly
   false. Do it AFTER 1–3, sized as "cap the prompt," not "preserve cite-able history."

**Skeptic's bottom line:** `root`'s 20k is **not** representative of a clean focused
agent doing legitimate big-value work — it is one agent's discoverability meltdown.
And the comparator `BnP` (a memory-task agent) is **also** waste-dominated, just
differently: **46% of its evals FAILED** and **narration (5,801) outweighs value-body
(3,193)**. Two agents, two waste profiles, neither "legitimate big values." Age-tiering
alone would have made both prompts smaller while leaving the agents just as stuck.

## Method (live, read-only)

Per-agent eval rows pulled via `seon.agent.ctx/agent-turns` → turn evals, each
component measured AS RENDERED (`format-eval-row` caps: source/stdout/error at
`eval-render-cap` = 1500 chars, citable value body at `result-body-render-cap` =
16384 chars; `tokens/estimate` = chars/4). Rendered block measured directly via
`seon.agent.ctx.transcript/transcript-block`. The per-component sum (22,605 for root)
runs slightly above the rendered block (20,315) because the transcript drops
content-free noise evals and coalesces ≥3 consecutive same-error runs; proportions
hold. The transcript is PER-AGENT by construction (`eval-events` walks only the
target agent's turns) — root's 118 evals are all root's own; it is NOT polluted by
other agents' evals.

## root (20,315 tok) — component split (per-row, capped, summed)

```
{:n 118 :n-ok 91 :n-failed 27
 :tok {:narr 7818 :src 3131 :out 0 :val 10373 :err 1283}
 :failed-tok-total 3934   ; the 27 failed evals' narr+src+err
 :ok-tok-total 18671
 :component-grand-total 22605}  ; rendered block = 20,315 after noise-drop/coalesce
```

Value body (10,373) is the largest component, but narration (7,818) is a close
second — narration is NOT trivial. stdout is 0 (nothing printed).

## root — by source CLASS (the smoking gun)

```
[:grep          {:n 24 :val 8083 :narr 2496 :src 651 :err 0   :n-failed 0}]   ; ~11,230 tok
[:other         {:n 48 :val 1021 :narr 2631 :src 1149 :err 872 :n-failed 17}]
[:message-verb  {:n 15 :val 121  :narr 1061 :src 993  :err 373 :n-failed 9}]   ; install-timing race
[:db            {:n 8  :val 1002 :narr 248  :src 198  :err 38  :n-failed 1}]   ; the LEGIT work, ~1,486 tok
[:blank         {:n 11 :val 0    :narr 923  :src 0    :err 0   :n-failed 0}]   ; comment-only rows
[:resolve-probe {:n 12 :val 146  :narr 459  :src 140  :err 0   :n-failed 0}]   ; ns-publics/resolve hunt
```

The 24 greps (verbatim) — a single search retyped 24 ways, hunting the verb defs:

```
(search/grep {:pattern "defn wait"})
(search/grep {:pattern "defn wait" :path "src/seon/agent"})
(search/grep {:pattern "defn" :path "src/seon/agent"})
(search/grep {:pattern "defn- wait" :path "src/seon/agent/message.clj"})
(search/grep {:pattern "defn-" :path "src/seon/agent/message.clj"})
(search/grep {:pattern "defn- (wait|complete)" :path "src/seon/agent/message.cljs"})   ; ×3
(search/grep {:pattern "defn- (wait|complete)" :path "src/seon/agent/message.clj"})
(search/grep {:pattern "init-message-verbs"})
(search/grep {:pattern "defn init-message-verbs" :path "src/seon/agent/message.clj" …}) ; ×3
(search/grep {:pattern "intern.*'wait|intern.*'complete"})
(search/grep {:pattern "intern" :path "src/seon/agent/message.cljs"})
(search/grep {:pattern "defn wait|defn complete" :path "src/seon/agent/lifecycle.clj…"})
… (24 total — every one a wait/complete/message/init-message-verbs hunt)
```

This is the **message-verb discoverability + `init-message-verbs!` install-timing**
problem (charter P0: "`message/user` … one drive burned ~100 evals finding it")
manifesting as ~11k tokens of redundant value body. Decisively waste.

## root — exact-duplicate re-runs (the "couldn't read it back" tell)

```
{:n-distinct-dup-forms 14 :total-dup-evals 41 :wasted-tok-on-repeats 3056
 :top [[5 "(ns-publics 'my.agent.root)"]
       [5 "(require '[seon.agent.search :as search])"]
       [4 "(seon.agent.ctx/render-namespace {:seon.ns/name :seon.agent.message})"]
       [4 "(require '[seon.agent.message :as msg])"]
       [4 "(ns-publics 'seon.agent.message)"]
       [3 "(search/grep {:pattern \"defn- (wait|complete)\" …})"]
       [2 "(wait \"awaiting task from user\")"]]}
```

14 forms run more than once, 41 eval rows total (27 are repeats), ~3,056 tok burned
re-running identical forms — `ns-publics`/`require`/`render-namespace` re-issued 4–5×
because the agent kept re-orienting in the same struggle (orthogonal slice; overlaps
the grep/other classes).

## root — failed evals (27, ~3,934 tok) — the three known bugs

Top failures by error tokens (sources):

```
"\""                                                              ; stray quote (parse artifact)
"`:my.kb.runtime/*`. Let me check what they stored …"            ; uncommented prose parsed-as-form
"`:idle` on turn 2, but by turn 5 both verbs are undefined …"    ; uncommented prose
"(get-in result/4IU-2606281655 [:seon.render/text])"             ; result/<id> IN-FORM Promise trap
"(clojure.string/includes? (get-in result/4IU-2606281655 …))"    ; same trap, retried
"(str (get-in result/4IU-2606281655 [:seon.render/text]))"       ; same trap, retried again
"(wait \"awaiting task from user\")"                              ; undefined verb (install race)
```

All three failure causes are documented elsewhere: uncommented-prose-as-form (parser),
the **`result/<id>` in-form pending-Promise trap** (`fabrication-root-cause-2026-06-28.md`),
and the verb install race. Pure waste, and the result/<id> trap directly drives
redundant re-queries.

## root — AGE distribution (oldest → newest)

```
:newest-5  {:val 69   :narr 211  :src 292  :err 99   :n 5  :n-grep 0}
:next-10   {:val 619  :narr 702  :src 334  :err 100  :n 10 :n-grep 0}
:older-103 {:val 9685 :narr 6905 :src 2505 :err 1084 :n 103 :n-grep 24}
```

The big-value rows are OLD: the oldest 103 evals carry 9,685 of 10,373 value-tok and
**all 24 greps**; the newest 15 carry ~688 value-tok. So an age-tier WOULD reclaim
them — but it would be compressing the grep flood, and the agent will not re-reference
those grep dumps, so the eviction design's "pointer + re-reference" premise is mostly
moot here. Age-tiering bounds the symptom; it does not stop the agent producing 11k
tokens of redundant search.

## BnP-2606281709 (9,448 tok) — a focused memory-task agent, ALSO waste-dominated

```
{:block-tokens 9448
 :summary {:n 76 :n-ok 41 :n-failed 35   ; 46% FAILURE rate
           :tok {:narr 5801 :src 4117 :out 0 :val 3193 :err 1274}
           :failed-tok-total 6049         ; ~42% of component tokens on FAILED evals
           :ok-tok-total 8336}
 :by-class [[:other {:n 49 :val 2627 :narr 1421 :nfail 23}]
            [:db    {:n 17 :val 566  :narr 2131 :nfail 10}]
            [:blank {:n 8  :val 0    :narr 1844 :nfail 0}]
            [:message-verb {:n 2 :val 0 :narr 405 :nfail 2}]]}
```

No grep flood — a DIFFERENT waste profile: **narration (5,801) is the LARGEST
component**, value-body (3,193) is third, and **35 of 76 evals (46%) FAILED**. The
failures are real DB-API struggles + parse artifacts:

```
"(db/query {:seon.db/query {:find '[(pull ?e [*])] …"            ; malformed query map
"(db/pull '[*] [:my.kb.runtime/slug \"pod-process\"])"          ; pull on a non-existent lookup-ref
"(seon.schema/register! {:seon.schema.attr/name :my.kb.runtim…" ; wrong register! shape
"(result :CzE-2606281712)"                                       ; wrong result-deref syntax
"(the schema map + a list of attr maps)"                         ; uncommented prose as form
"`:my.kb/source` + `:my.kb/confidence` are also unregistered."   ; uncommented prose as form
```

So even a clean focused agent's transcript is dominated by **failed API attempts +
over-narration**, not legitimate big values. Value-body is a minority everywhere we
measured.

## Shared-pod artifact — clarified

`root`'s 20k is NOT a cross-agent pollution artifact: the transcript walks only the
target agent's turns, so all 118 evals are root's own. The
`token-efficiency-audit-2026-06-28.md` note about a "weak-agent failure flood on the
SHARED pod" refers to the `:warnings` block (which queries failed evals across ALL
agents), not the transcript. The transcript is per-agent. BUT `root`'s 20k is still
**not representative of a healthy focused agent** — for a different reason: it captures
one agent's verb-discoverability meltdown. A clean run would not generate the grep
flood at all (fix the verb discoverability and root's transcript roughly halves at the
source, before any clipping).

## Waste vs legitimate — the attribution (root)

| Class | Tokens | Verdict |
|---|---:|---|
| `search/grep` flood (verb hunt) | ~11,230 | WASTE (redundant re-query) |
| `message`/`wait`/`complete` (9/15 failed) | ~2,548 | WASTE (install race + hunt) |
| `resolve`/`ns-publics` probes | ~745 | WASTE (hunt) |
| failed `:other` (parse artifacts + result/<id> trap) | ~1,500 | WASTE (known bugs) |
| blank/narration-only rows | ~923 | mostly low-value |
| `:db` real work + values used | ~1,486 | LEGITIMATE |
| `:other` ok (real narration + values used) | ~1,900 | LEGITIMATE (generous) |

**≈ 70–75% waste, 25–30% legitimate.** The waste is overwhelmingly ONE root cause
(verb discoverability/install-timing) plus the result/<id> Promise trap — NOT "old big
values the agent re-references."

## Recommendation (file:line)

1. **Verb discoverability + `init-message-verbs!` install-timing — Core, the #1 fix.**
   The `message`/`wait`/`complete` verbs must resolve on first reference and be
   discoverable from the always-on context (so the agent never greps `src/seon/agent/`
   for them). This is the charter's P0 "message-verb install-timing + discoverability"
   item. Prevents ~14,500 tok of root's transcript from ever existing. Owner-relayed as
   the UI lane's "#1 blocker"; install path is `seon.agent.message/init-message-verbs!`.
2. **Pending-Promise stash self-heal — Core, `src/seon/eval.cljs:2624-2631`.** Per
   `fabrication-root-cause-2026-06-28.md` fix #2 (REPL-verified there): attach `.then`
   in the `pending?` branch to re-`stash-result-raw!` + re-`bind-result-var!` the
   resolved value, so `(get-in result/<id> …)` in-form references stop failing and the
   agent stops re-running them.
3. **Cap search output at the source — `seon.agent.search/grep`.** A grep result is
   ~300–700 tok; bound the rendered hit count/context at production so a search burst
   can't each dump full context (value-rendering-at-source, complements the per-row
   `result-body-render-cap`).
4. **Age-tiering / clipping — `seon.agent.ctx.transcript/transcript-block:424` +
   `transcript-token-cap` (config.cljs:263) — a BOUNDEDNESS backstop, do AFTER 1–3.**
   Still justified to cap an unbounded long-task agent, and the `transcript-eviction-
   2026-06-28.md` mechanism is sound. But on today's transcripts it compresses waste,
   and its "agent re-references the clipped value" premise does not hold for the
   dominant grep-dump content — so size it as "cap the prompt," not as "preserve
   cite-able history," and expect the upstream fixes (1–3) to do the real reduction.

## Verbatim REPL transcript of the measurement

```clojure
;; agents on the pod
=> {:n-agents 5 :agents [{:seon.agent/id "root"} {:seon.agent/id "ogS-2606281649"}
    {:seon.agent/id "lqj-2606281709"} {:seon.agent/id "gOn-2606281709"}
    {:seon.agent/id "BnP-2606281709"}]}

;; eval counts per agent
=> {"root" 118 "ogS-2606281649" 57 "lqj-2606281709" 31 "gOn-2606281709" 35 "BnP-2606281709" 76}

;; render caps
=> {:eval-render-cap 1500 :result-body-render-cap 16384 :message-render-cap 4000
    :transcript-token-cap 6000}

;; root rendered transcript block
=> {:chars 81260 :tokens 20315}

;; root component summary
=> {:n 118 :n-ok 91 :n-failed 27
    :tok {:narr 7818 :src 3131 :out 0 :val 10373 :err 1283}
    :failed-tok-total 3934 :ok-tok-total 18671 :component-grand-total 22605}

;; root top-10 value-body rows — ALL search/grep variations of the same hunt
=> ([687 "(search/grep {:pattern \"wait.*complete.*message\"})"]
    [571 "(search/grep {:pattern \"intern.*wait|intern.*complete|intern.*message\" …})"]
    [522 "(search/grep {:pattern \"wait|complete\" :path \"src/seon…\"})"]
    [511 "(search/grep {:pattern \"intern\" :path \"src/seon/agent/…\"})"]
    [495 "(search/grep {:pattern \"defn wait\"})"] …)

;; root by-class
=> ([:grep {:n 24 :val 8083 :narr 2496 :src 651 :err 0 :n-failed 0}]
    [:other {:n 48 :val 1021 :narr 2631 :src 1149 :err 872 :n-failed 17}]
    [:message-verb {:n 15 :val 121 :narr 1061 :src 993 :err 373 :n-failed 9}]
    [:db {:n 8 :val 1002 :narr 248 :src 198 :err 38 :n-failed 1}]
    [:blank {:n 11 :val 0 :narr 923 :src 0 :err 0 :n-failed 0}]
    [:resolve-probe {:n 12 :val 146 :narr 459 :src 140 :err 0 :n-failed 0}])

;; root exact-duplicate re-runs
=> {:n-distinct-dup-forms 14 :total-dup-evals 41 :wasted-tok-on-repeats 3056}

;; root age buckets (oldest->newest)
=> {:newest-5 {:val 69 :narr 211 :n 5 :n-grep 0}
    :next-10 {:val 619 :narr 702 :n 10 :n-grep 0}
    :older-103 {:val 9685 :narr 6905 :n 103 :n-grep 24}}

;; BnP-2606281709 comparator
=> {:block-tokens 9448
    :summary {:n 76 :n-ok 41 :n-failed 35
              :tok {:narr 5801 :src 4117 :out 0 :val 3193 :err 1274}
              :failed-tok-total 6049 :ok-tok-total 8336}}
```
