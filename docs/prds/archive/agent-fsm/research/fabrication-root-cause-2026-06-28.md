---
type: research
status: active
tags: [research, agent, context]
---

# Fabrication / honesty root-cause + the `result/<id>` first-reference artifact

> Owner-directed deep dive (READ-ONLY on the live pod, no live LLM drive): chase
> the AGENT FABRICATION / HONESTY cluster. Fabrication = the agent reports a wrong
> number to the human while its STORED data is correct. The owner frames it as a
> CONTEXT error ("every token should be there or refine it to return better
> tokens"). This note reproduces the `result/<id>` artifact verbatim in the REPL,
> separates the THREE distinct causes, and ranks concrete fixes (each tagged Core
> vs context/render, with file:line).

## TL;DR

- **Fabrication is THREE distinct bugs, not one.** (A) the model authors the
  human-facing `(message/user …)` body in the SAME response (batch) as the eval
  that computes the value — so the prose literally predates the result it quotes
  (the dominant, structural cause); (B) once the real value exists, it is present
  in the next turn's transcript but BURIED as one `;=> value ; result/<id>` line —
  not surfaced where the model composes its reply, and the existing "REPORT THE
  VALUE" guidance does not land on weak models; (C) the `result/<id>`
  pending-Promise stash trap can make the value genuinely unreadable, pushing the
  agent to narrate from memory.
- **The task's hypothesis ("the prior turn's computed value isn't cite-ably
  present next turn → the agent retypes from memory") is only PARTIALLY true.** In
  every documented case the correct value WAS present in the SAME-turn transcript
  (`[[:dining 106] …]`; the real `db/store-inventory` result). The transcript does
  NOT clip (clipping is OFF — `transcript-block`), so eviction is not the cause.
  The break is the **same-response compose-and-report ordering** plus **burial of
  the cite-able value**, not absence.
- **The `result/<id>` first-reference artifact IS a real Core bug and I reproduced
  it verbatim.** When a form's value is a PENDING Promise (auto-await timeout, or
  the gym's awaited `:memory` pull that exceeds budget), the stash at `result/<id>`
  holds a RAW js/Promise. Only a BARE `result/<id>` reference auto-awaits it; any
  IN-FORM use (`(first result/<id>)`, `(group-by k result/<id>)`, `(let [xs
  result/<id>] …)`) operates on the un-awaited Promise → `:ok false` "ERROR" / nil
  / a `#‹fn›`-shaped garbage. And the stash is NEVER updated to the resolved value,
  so the trap persists across retries.
- **Single highest-leverage fix (context/render, mine):** a DERIVED "values you
  just computed — quote these" cite surface rendered immediately above the
  readline (the composition point), listing the last N successful eval
  `result/<id> => value` rows. Reactive/derive-don't-store; puts the real number
  in the nearest tokens to where the model writes `(message/user …)`. Pair it with
  the Core stash self-heal (below) so those values are never a broken Promise.

## The reproduced artifact (verbatim, live default pod 7890, read-only)

`my.data` is sync (no `^:async`, returns a plain map) — so it is NOT the Promise
source. The artifact lives in the eval value-stash path. Reproduced by stashing a
pending Promise exactly as `eval-form-entry!` does in its `pending?` branch:

```clojure
;; stash a PENDING promise as result/tpend1 (what eval-once does on auto-await
;; timeout / defer — seon.eval/eval-form-entry! lines 2624-2631):
(def slow-p (js/Promise. (fn [res _] (js/setTimeout #(res [{:a 1} {:a 2} {:a 3}]) 50))))
(e/stash-result-raw! "tpend1" slow-p)
(e/bind-result-var! cs "tpend1" slow-p)

;; the stash holds a RAW Promise:
;; => {:stashed-type #object[Promise], :is-promise true}

;; BARE reference — result-var-ref? routes it through :expr; e/eval returns the
;; Promise (the full eval-once pipeline's maybe-await-value would then await it):
"result/tpend1"   ;; => {:ok true, :type "function Promise()…", :promise? true}

;; IN-FORM references — the agent's actual usage — all FAIL:
"(first result/tpend1)"                 ;; => {:ok false, :error "ERROR"}
"(group-by :a result/tpend1)"           ;; => {:ok false}
"(count result/tpend1)"                 ;; => {:ok false}
"(let [xs result/tpend1] (mapv :a xs))" ;; => {:ok false, :error "ERROR"}
```

This matches Finding 1 of `my-data-gym-drive-2026-06-28.md` ("the same shape over
`result/VyZ-…` + `group-by` returned nil TWICE … `(type (first result/VyZ-…))`
came back `#‹fn›` … before the third identical attempt resolved"). The surface
form ("ERROR" vs nil vs `#‹fn›`) varies with the wrapping form; the cause is one:
**`maybe-await-value` only awaits the form's TOP-LEVEL return; a Promise read out
of the stash inside a larger form is never awaited.** "Resolves on the third
attempt" = the agent eventually re-ran the source expression (fast enough to
auto-await within budget) or used a bare reference — NOT the stash self-healing.

### The fix is verified in the REPL

Attaching a `.then` to the pending Promise that RE-STASHES + RE-BINDS the resolved
value makes in-form references work:

```clojure
(def slow-p2 (js/Promise. (fn [res _] (js/setTimeout #(res [{:a 1} {:a 2} {:a 3}]) 30))))
(e/stash-result-raw! "tpend2" slow-p2)
(e/bind-result-var! cs "tpend2" slow-p2)
;; THE FIX — self-heal the stash when the pending promise resolves:
(-> slow-p2 (.then (fn [v] (e/stash-result-raw! "tpend2" v)
                           (e/bind-result-var! cs "tpend2" v))))
;; …80ms later, an IN-FORM reference now succeeds:
"(mapv :a result/tpend2)"   ;; => {:ok true, :ai "[1 2 3]"}
```

## Root-cause chain (fabrication)

The agent loop turns ONE LLM response into ONE batch: `seon.agent.turn/ask-and-eval-reply!`
→ `seon.repl.internal/parse-forms` → `seon.eval/eval-batch!`. Every form in a
single reply is evaluated together. So when the model writes, in one reply:

```clojure
(db/store-inventory)
(message/user "I have 1,234 datoms across 12 entity kinds and 47 attributes.")
```

the `(message/user …)` body was authored BEFORE `store-inventory` ran — the model
could not have seen the result. The numbers are a guess. This is the dominant,
structural cause (corroborated: `live-context-audit-2026-06-28.md` B3 — the agent
had the REAL `db/store-inventory` in its own SAME-turn transcript yet sent
`1,234 / 12 / 47`; truth was 4 kinds. `my-data-gym-drive` Run 2 — sent "$203.47"
then self-corrected from the real `result/<id>` a turn later).

Two amplifiers:

1. **Burial.** The cite-able value is present next turn but is one `;=> value ;
   result/<id>` line deep in the flat event log (`format-eval-row`, ctx.cljs:567).
   The existing guidance "REPORT THE VALUE YOUR LAST EVAL RETURNED" + the
   correct-shape example (ctx.cljs:925-956) is strong but **does not land on weak
   models** (audit B3, B4). The value is not where the model composes the reply.
2. **The pending-Promise trap (the reproduced artifact).** When reading a value
   back genuinely fails, the agent falls back to narrating from memory. This both
   inflates `eval-error-rate` and directly feeds fabrication.

Note: `neutralize-result-claims` (ctx.cljs:518) already rewrites the agent's own
fabricated `;; => …` / bare `=> …` lines to `;; [unverified narration …]` — but
ONLY in the TRANSCRIPT render of `:seon.eval/narration` / `:seon.eval/source`. The
OUTBOUND `(message/user …)` body the human receives is never neutralized — by
design (it's the product), but it means the human gets the raw fabricated number.

## Ranked fixes

### 1. [CONTEXT/render — mine] Derived "cite these" surface above the readline — HIGHEST LEVERAGE for fabrication

Add a reactive section function that derives the agent's last N successful,
non-trivial eval values and renders them as a compact cite-card immediately above
the live `ns=>` readline (`seon.agent.ctx.transcript/readline`, transcript.cljs:360),
e.g.:

```
; values you JUST computed — quote THESE, do not retype a number from memory:
;   result/Abc => 4          ; (db/store-inventory …)
;   result/Vyz => [[:dining 106] [:groceries 73] [:transport 40]]
```

Derive-don't-store (query the agent's recent `:seon.eval/ok? true` rows with a
non-empty `:seon.eval/result-edn`); stores nothing; self-heals as new evals land.
Puts the real value in the nearest tokens to where the model writes
`(message/user …)`. This is the "make the render ALWAYS surface the cite-able
value so honesty is the easy path" fix the task asks for. Tag: **context/render**,
new section fn in `seon.agent.ctx.transcript` (or a sibling block wired via
`seon.config`).

### 2. [CORE — seon.eval] Pending-Promise stash self-heal — VERIFIED, fixes the reproduced artifact

`eval-form-entry!` stashes the raw pending Promise (`seon.eval` lines 2624-2631)
and `maybe-await-value` (`seon.eval` lines 1282-1325) only awaits the top-level
return. Fix: in the `pending?` branch, after `stash-result-raw!` /
`bind-result-var!`, attach `.then` to `pending-promise` that RE-stashes +
RE-binds the resolved value (and `.catch` no-ops). Then `result/<id>` becomes real
data the instant the Promise resolves, and EVERY reference (bare or in-form)
works. REPL-verified above. The `:seon.eval/pending` placeholder stays honest
while it runs. Tag: **Core**, `src/seon/eval.cljs:2624-2631` (+ helper near
`maybe-await-value`).

### 3. [CONTEXT/guidance — mine] Sharpen the anti-fabrication guidance to name the same-response failure

The guidance at `ctx.cljs:925-956` teaches "query this turn, reply next turn" and
"REPORT THE VALUE YOUR LAST EVAL RETURNED" but does not name the EXACT failure.
Add one crisp line: *"A number inside a `(message/user …)` in the SAME reply as
the eval that produced it is a GUESS — the runtime had not run that form when you
wrote the prose. State figures only from a `result/<id>` already on a prior `;=>`
line."* Pairs with #1 (the cite-card is the value it points at). Tag:
**context/guidance**, edit the existing system prose in place (no new block).

### 4. [CORE/context — flagged, NOT recommended as a first move] Outbound-message grounding check

A grounding gate on `(message/user …)` (flag numbers in the body that match no
`result/<id>` value) is tempting but fragile and edge-case-shaped (owner standing
pref: simple core over edge defenses). List it only so it is not re-derived; do
#1-#3 first and re-drive before considering it.

## What is NOT the cause (falsified)

- **Transcript eviction.** `transcript-block` (transcript.cljs:424) renders ALL
  events with clipping OFF (`:seon.render/clip :none`); the `transcript-token-cap`
  knob is present but disabled. The cite-able `;=> value ; result/<id>` line is
  not dropped between turns.
- **Result-body clipping losing the number.** The citable result body caps at
  `result-body-render-cap` (16384 chars ≈ 4k tok) and renders WHOLE below that
  (format-eval-row, ctx.cljs:664-687); the small aggregates in evidence
  (`[[:dining 106]…]`, a 4-row inventory) are far under the cap.
- **`my.data` async.** `my.data/rows`/`group-sum`/`max-by` are sync, no Promise
  (live-checked: `(instance? js/Promise (mydata/rows …))` => false). The
  pending-Promise trap comes from the gym's awaited `:memory` db path, not my.data.

## Evidence index

- Reproduced live on the default pod via `mcp__seon_cljs__eval` session "default"
  (read-only; only volatile globalThis `result/*` test stash touched, no DB
  writes): the pending-Promise in-form-reference failure + the `.then` self-heal.
- `seon.eval` — `maybe-await-value` (1282-1325), `pending-placeholder` (154-162),
  `eval-form-entry!` stash/bind (2624-2631), `eval-batch!` (2740) one-reply-one-batch.
- `seon.agent.ctx` — `format-eval-row` (567-712), `neutralize-result-claims`
  (518-541), system anti-fabrication prose (925-974).
- `seon.agent.ctx.transcript` — `transcript-block` no-clip (424-511), `readline`
  (360-407), `transcript-token-cap` OFF (45-51).
- Drives: `my-data-gym-drive-2026-06-28.md` Finding 1 + Finding 3 (honesty);
  `context-usage-drive-2026-06-28.md` §4 (Node version/path/pid fabricated in the
  message while stored facts correct); `live-context-audit-2026-06-28.md` B3
  (1,234/12/47 vs real 4 kinds, same-turn).
