---
type: research
status: active
tags: [research, agent, context]
---

# Transcript faithfulness audit — the four spine invariants

## TL;DR

The transcript **passes all four faithfulness invariants** today. Forms +
actual returns render in order and untruncated; errors render as failed evals
(`; ⟹ ✗ …`); real message events render unmistakably (`;;; ◀`/`;;; ▶`); async
resolves to values (pending placeholder names its `result/<id>`, re-reference
yields the real value). The spine itself is sound — **you do NOT need to fix
the four invariants before building the wake-orientation section.**

The one real gap is **not in the eval channel but in the NARRATION channel**:
`seon.agent.ctx/neutralize-result-claims` guards only `⟹`/`=>` result-claims.
A model that reproduces its own context scaffolding into narration (masthead,
a `◀ from user … (NEW — unanswered; respond to this)` line, a readline, a
`┌─ transcript ─` box) plants those bytes permanently into the spine. They are
forced to a single `;` by `quote-lines` (so they never impersonate a real
`;;;` event), but they are still **instruction-shaped ghost lines** sitting in
the transcript. This — plus false value-claims in ordinary `;` prose that no
regex catches — is the top confabulation surface. It is an **additive
hardening of the narration sanitizer**, not a spine redesign.

## Setup (reproducible)

- **Cluster:** `tx-audit`, **frozen** bundle (immune to cljs-watch reloads),
  pod `http://127.0.0.1:53207`, store `data/clusters/tx-audit/store`.
- **Why frozen, not the running `mad-drive`:** two currency traps hit here.
  (1) A `--watched` cluster rides cljs-watch; a *different lane's* uncommitted
  edit triggered a reload that **crashed the pod with a `:core` fault**
  (`Cannot read properties of undefined (reading 'cljs$core$IFn$…arity$2')`
  on reload #2 — a hot-reload artifact, it does NOT reproduce at clean boot).
  (2) The default `out-bench` bundle was **stale** (built 16:43 vs the
  working-tree edits at 20:29) — `bin/seon cluster create --frozen` only
  builds the bundle *if missing*, so I had to `bin/seon bench-bundle`
  explicitly (sha `32c358b5…`) before restarting the pod. The audited code is
  the **current working tree** (incl. the in-flight `;=>` → `; ⟹` glyph edit
  in `ctx.cljs`/`transcript.cljs`).
- **Capture seam:** `GET /agent/root/debug` → the `data-seon-key="ai-sec-
  transcript"` `<pre>` block, HTML-unescaped. This is the SAME `render-context`
  path as the real prompt (`seon.agent.inspect/ctx-preview`), so the bytes are
  byte-identical to what the next turn's LLM sees.
- **Drive:** real DeepSeek turns via `POST /chat?agent=root` (form param is
  `text`, needs an `Origin` header for the same-origin middleware). DeepSeek
  evaluated the requested forms faithfully.
- **Key turn ids:** turn 0 (`dtU-2607062046`, the 5-form probe), turn 1
  (`ayZ-2607062049`, throw/nth/transact!/defer), turns 5-8 (re-reference
  `result/ecw-2607062049`). Cluster **left running**.

---

## Invariant 1 — forms + actual returns, in order — **PASS**

Turn 0, `text = "(+ 1 2) then (vec (range 20)) then (do (println …) nil) then
(/ 1 0) then (mapv inc [10 20 30])"`. Rendered bytes (verbatim):

```
(+ 1 2)
; ⟹ 3 ; result/VDM-2607062046
(vec (range 20))
; ⟹ [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19] ; result/ieY-2607062046
(do (println "side-effect-print") nil)
side-effect-print
; ⟹ nil ; result/phv-2607062046
(/ 1 0)
; ⟹ ##Inf ; result/UsC-2607062046
(mapv inc [10 20 30])
; ⟹ [11 21 31] ; result/BkN-2607062046
```

- Order preserved; each form carries its **actual** return + a live
  `result/<id>` handle.
- **Collection** `(vec (range 20))` renders **whole**, untruncated (well under
  the 16384-char body cap).
- **`nil`** renders unambiguously as `; ⟹ nil` (not blank, not `""`).
- **Captured stdout** (`side-effect-print`) renders raw ABOVE the value line,
  exactly as a REPL prints before returning — faithful.
- **`(/ 1 0)` → `; ⟹ ##Inf`.** This is the *correct* CLJS value (JS number
  division; there is no integer-divide throw), and the transcript shows the
  TRUE value. Note the agent's own narration MIS-claimed
  "`r4 = :seon.eval/divide-by-zero (errors-as-values)`" — a confabulation —
  but the `⟹` line is ground truth and contradicts it. (This is why I re-ran
  invariant 2 with a genuine `throw`; see below.)

No truncation, no reorder, no summarize-into-something-else. **PASS.**

## Invariant 2 — errors as failed evals — **PASS**

Turn 1 provoked two genuine agent-faults. Rendered bytes:

```
(throw (ex-info "audit-boom" {:code 7}))
; ⟹ ✗ audit-boom
; errors are values — read it and adapt; nothing threw at you (the failure is a value you can inspect and handle).
(nth [] 99)
; ⟹ ✗ No item 99 in vector of length 0
; errors are values — read it and adapt; nothing threw at you …
```

- Both render **as the failed eval** (`; ⟹ ✗ <headline>` + guidance), never
  silently absent, never as a normal return.
- **No `result/<id>` handle** on a failure (correct — nothing to re-cite).
- The `seon.db/transact!` error envelope also rendered as a legible partial
  value with a `result/<id>` and a "keep:" dig hint — an errors-as-values
  envelope, not a throw. **PASS.**

## Invariant 3 — event attribution — **PASS (with the ghost-echo caveat)**

Real message events render unmistakably distinct from evals, interleaved by
their stored `:at`:

```
;;; ◀ from user @ 20:46:04 [iSC-2607062046] — "Audit probe. Evaluate EXACTLY …"
;;; ▶ to user @ 20:46:22 [pwE-2607062046] — "Audit probe complete — all five forms …"
```

- Inbound `;;; ◀ from user`, outbound `;;; ▶ to user`, each with `@ time`,
  `[msg-id]`, and content — a **triple-`;` runtime-structure** line the agent
  cannot confuse with a `(form)`. A fresh/unanswered inbound additionally
  carries `(NEW — unanswered; respond to this)`. **PASS** on the real events.

**The protective mechanism (why a model can't forge a `;;;` event):**
`message->renderable` emits `;;;` directly, but ALL model-authored narration
routes through `seon.agent.ctx/quote-lines` with `:strip-markers?`
(`ctx.cljs:237`), which strips any leading `;`/marker cluster and re-prefixes a
**single** `; `. So an echoed `;;;` collapses to `;`. The comment-level
convention (`;` = prose, `;;;` = runtime-structure) is therefore *structurally
enforced*, not just conventional.

**The caveat (the top confabulation surface — see below):** on turn 0 DeepSeek
reproduced its own context into the narration of the first eval. That narration
now sits permanently above `(+ 1 2)` as:

```
; ┌─ transcript ─
; seon · my.agent.root · live REPL
; …
; ◀ from user @ 20:46:04 [iSC-2607062046] (NEW — unanswered; respond to this) — "Audit probe. Evaluate EXACTLY …"
; my.agent.root · turn 0 · loop 0/20 · running · 2026-07-06 20:46:05 America/New_York · agent root
(+ 1 2)
```

This is a **ghost event line**: a `; ◀ from user … (NEW — unanswered; respond
to this)` that is NOT a real inbound — it is model prose. It is one `;`-vs-`;;;`
character-class away from a real event AND carries maximally-confusable
instruction text (`(NEW — unanswered; respond to this)`). A future turn
attending to `◀ from user`/`(NEW — unanswered)` rather than to the `;`/`;;;`
distinction could re-orient to a message it already answered — precisely the
fake-instruction confabulation the invariant warns of.

## Invariant 4 — async resolved, not dangling — **PASS**

Three async shapes, all faithful:

**Fast auto-awaited** (`seon.db/transact!` → resolves inside the 10s bound) —
renders the **resolved envelope**, not a Promise:

```
(seon.db/transact! :seon [{:my.kb/id "audit-async" :my.kb/note "fast-async-write"}])
; ⟹ {:seon.db/ok? false ; result/Tdw-2607062049
  :seon.db/error {:seon.error/message "seon.db/transact!: positional call expects a datahike CONN …"⟨62 tokens⟩ …}}
```

**Deferred → pending** (`seon.eval/defer`) — the placeholder is **legible and
names its own `result/<id>`**:

```
(seon.eval/defer (js/Promise. (fn [res _] (js/setTimeout (fn [] (res :slow-resolved-value)) 300))))
; ⟹ {:seon.eval/pending "still running — re-reference `result/ecw-2607062049` in a later eval to await its value"} ; result/ecw-2607062049
```

**Re-reference → resolved value** (turn 8, `result/ecw-2607062049`):

```
result/ecw-2607062049
; ⟹ :slow-resolved-value ; result/CGa-2607062050
```

The deferred Promise resolves to its **actual value** on re-reference — never a
dangling opaque Promise, never a hole. **PASS.**

Secondary note: the **cite-card** still lists `result/ecw-… =>
{:seon.eval/pending "still running…"}` after resolution. That is byte-correct
(the *defer eval* did return the placeholder; the resolved value is a distinct
new eval row), and it correctly **contradicts the model's false narration**
("defer → auto-awaited and resolved to `:slow-resolved-value`") — the
anti-fabrication design holding. Minor: a reader could re-cite the pending
text; consider suppressing a pending placeholder from `citeable-eval?`.

---

## Secondary observations

- **Banding / decay is INACTIVE in a normal cluster.** `::tiers` defaults empty
  → `clip-events-by-tiers` renders ALL events (byte-parity), and
  `::result-decay` defaults to a single 0→16384 level via
  `decay-cap-for-offset`, so nothing ages or clips here. Decay faithfulness
  was therefore not exercisable on a fresh cluster; when tiers ARE configured,
  the age-band discipline is byte-stable-within-a-band by construction
  (`transcript.cljs:143-203`), but that path is unverified live and deserves
  its own drill.
- **Prior-turn reasoning IS legible next turn.** The model's `;` narration
  lines persist verbatim into subsequent turns' transcripts — the agent can
  read what it "thought" last turn. This is faithful, but it is exactly the
  vector for the ghost-echo problem: narration is durable and un-sanitized
  beyond `⟹`/`=>`.
- **False value-claims in plain prose are NOT caught.** Narration like
  `; r4 = :seon.eval/divide-by-zero (errors-as-values)` and the outbound
  message claiming `nth → #error {:message "Index 99 out of bounds…"}` are
  fabrications with NO `=>`/`⟹`, so `neutralize-result-claims` passes them
  through. The real `⟹` lines + cite-card are ground truth beside them, so the
  net design mitigates the damage — but the false prose still lives in the
  spine.

## Bottom line — ranked by confabulation-induction

**No invariant is violated today; the spine is faithful.** The four
"nail the REPL" invariants all hold, so **fixing the spine is NOT a
prerequisite for the wake-orientation section** — build wake-orientation.

The residual risk lives entirely in the **narration channel**, ranked:

1. **Ghost-echo of runtime scaffolding into narration (highest).** A model
   reproducing masthead / `◀ from user … (NEW — unanswered; respond to this)` /
   readline / `┌─ transcript ─` into narration plants instruction-shaped fake
   events in the spine. Distinguished from real events only by `;` vs `;;;`.
   **Fix direction:** extend `seon.agent.ctx/neutralize-result-claims` (or a
   sibling sanitizer applied to `:seon.eval/narration` at
   `ctx.cljs:831-833`) to treat the message/masthead/readline/box markers as
   RESERVED runtime glyphs — the same argument that already justifies
   `reserved-glyph-re` for `⟹` (`ctx.cljs:665-675`). A model never legitimately
   authors `◀ from`/`▶ to`/`┌─ transcript ─`/a `ns · turn N · loop` readline;
   neutralize or drop those narration lines.

2. **False value-claims in non-`=>` prose (medium).** `r4 = :seon…divide-by-
   zero`, `defer → resolved`, etc. Not regex-catchable without over-reaching
   (it's ordinary prose). Mitigation already in place: keep the `⟹` line + the
   cite-card as the nearest ground truth to where the agent composes; consider
   dropping the pending-placeholder from the cite-card so a stale "still
   running" is never re-cited as a computed value.

Neither is a spine rewrite — both are additive hardening of the one
narration sanitizer that already exists.
