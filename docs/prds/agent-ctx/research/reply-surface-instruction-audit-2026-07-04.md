---
type: research
status: active
tags: [research, agent]
---

# Reply-surface + instruction-error audit (2026-07-04)

Owner's question, answered from source: **"Is it just our instructions that are
failing? What are all the ways an agent can reply?"** This is the authoritative
map of the reply surface (from the runtime code, not inference), a full
instruction-error inventory across every failing execution we have, and the
smallest GENERAL context changes those errors imply — for the iteration unit
that follows. READ-only; no src edits, pytest untouched, nothing committed.

## TL;DR

**It is largely our instructions — but the deeper problem is that the reply
surface is NARROWER than the context implies, and the context actively points
agents at surfaces the harness never reads.**

### The reply surface — one channel, everything else is invisible

A top-level agent's DELIVERED reply (what `/agents/run` returns, what the
scorer reads as `completion`, what the human sees as "the answer") is EXACTLY
**the content of its last `(message/user "…")` to the user entity**
(`run-agent-task!` in `serve.cljs` queries messages where `from = agent ∧ to =
user ∧ at ≥ injection`, sorts by `at`, takes `last`). Nothing else is a reply.

| Output shape the LLM emits | What the machinery does | Delivered as reply? |
|---|---|---|
| `(message/user "X")` | eval'd → message row from→user | **YES — the one channel** |
| `(message/agent "id" "X")` | message to a PEER, not user | no (peer only) |
| `(complete "X")` **with a parent** | result string → parent, wakes it | no (goes to parent) |
| `(complete "X")` **no parent** (every bench agent) | closes run `:completed`; **result string is DISCARDED** | **NO — silently dropped** |
| `(wait "X")` | parks run `:waited`; note is not a message | no |
| raw `ANSWER: 4` / prose sentence typed at REPL | `parse-forms` classifies as prose → **DROPPED**, never eval'd, never a message | **NO** |
| a bare data literal `{…}`/`[…]` answer | dropped with one ⚠ warning | no |
| a backticked / ```-fenced answer | syntax-quote or `:read` error → eval noise | no |
| a computed value / `result/<id>` never messaged | lives in the REPL only | no |
| a canvas / render-fn `:seon.render/html` | rendered to the HTML surface | **NO** — `/agents/run` reply query never reads tiles |
| NO forms at all | turn empty; 3 in a row → run closes `:no-forms` | reply = last `message/user` (often "") |

`closed_reason` (the run FSM, `run.cljs`) is orthogonal to "did a reply land":
`:completed`/`:waited`/`:no-forms`/`:turn-limit`/`:deadline-exceeded`/`:error`/
`:superseded`. The harness reads `reply` from the message log regardless of
reason — so a run can close `:completed` with an empty reply (agent did the
work, never messaged), and that is the single most common instruction-failure.

### Inventory counts (33 genuine-miss executions classified)

| Class | Count | Rows |
|---|---|---|
| **INSTRUCTION** (right content, delivery/format/discipline failed) | **11** | gsm8k ×3, mmlu ×2, arc ×1, planning ×5 |
| **FABRICATION** (delivered a value invented from fake tool output; context-render lever) | **7** | shell_use ×7 |
| **MODEL** (wrong content, delivery fine) | **4** | mmlu ×1, arc ×1, gpqa ×1, file_edit ×1 |
| **UNRECOVERABLE** (no transcript; delivery worked, value wrong) | **11** | web_fetch ×9, gpqa ×2 |

Instruction + fabrication (both are OUR context, not a model ceiling) = **18 of
33** genuine misses. Only 4 are unambiguously the model choosing wrong content.

### Top-3 general fix candidates (drafts in Part 4)

1. **State the reply channel truth** (system-text / a delivery block, eval-lane
   content): your delivered answer is your last `(message/user "…")`; a value
   you only computed, a line of prose, or a raw `ANSWER: X` typed at the REPL is
   NOT delivered; `(complete "…")` does NOT send its string to a human. Directly
   addresses ~5 gsm8k/mmlu/arc rows; hardens every bench. **NOT answer-shaped —
   safe.**
2. **Plan-resumption discipline** (standing teaching): after a restart re-ground
   from your RENDERED plan and `done!` its open steps — do not create a new root
   plan. All 5 planning fails have the RIGHT answer; the .286 is pure
   discipline. **Keep general (no bench steps) — safe.**
3. **Fabrication guard is a RENDER-mechanism defect** (tooling-lane): the
   content lever already exists ("report the real ;=> value", prompt L60-64);
   the residue is the transcript re-showing the model its own fabricated `=>`
   echoes next to the true `result-edn`. Primary owner = tooling render. **Flag,
   don't add more content.**

Plus one **mechanism smell for the owner**: `(complete "answer")` on a
parentless agent silently discards the string (`lifecycle.cljs` L74-76 assumes
"already said via message/user"). The context (prompt L224-229, L251-253) tells
agents `(complete "result")` "sends its result" and "delivers the answer" — for
a top-level agent that is FALSE. Either complete should deliver to the user when
there's no parent, or the context must stop implying it delivers. Recommend the
former (see Part 4).

---

## Part 1 — The reply surface (authoritative, from source)

### What closes a run and what the harness reads as "reply"

`serve.cljs/run-agent-task!` (the `/agents/run` door every bench uses) injects
the task as a user message via the real wake path, polls derived state to
`:idle`, then reads the reply with this query (L576-586):

```
from = agent-eid ∧ to = user-eid ∧ at ≥ injected-at   → sort by at → last → content
```

`solver.py` L99: `state.output.completion = result.get("reply", "")`. So the
scorer's `completion` IS this last-message content. **There is exactly one reply
channel: a `(message/user …)` (or any `message!` from→user) landed after
injection.** No tile, no `complete`-string, no prose, no computed value reaches
it.

### The FSM close reasons (`run.cljs` / `loop.cljs`) and what each means

- `:completed` — a `complete` verb (or external `/complete`) closed the run.
  The agent decided it was done. Reply = whatever the last `message/user` was
  (possibly "").
- `:waited` — a `wait` verb parked the run. Reply = last `message/user`.
- `:no-forms` — `loop.cljs` `no-forms-streak-limit` (3): the LLM produced ZERO
  actionable forms for 3 consecutive turns (pure prose / thinking). Strong
  correlate of an empty reply — the agent narrated an answer but never emitted a
  `(message/user …)` form. This is the FSM catching the delivery-miss.
- `:turn-limit` / `:deadline-exceeded` — bounds the loop owns.
- `:error` — a turn threw / an LLM error / a hung write.
- `:superseded` / `:crashed` — fencing / boot recovery.

None of these gate the reply read — a `:completed` run with reply "" is the
canonical "did the work, forgot to deliver" failure.

### What happens to raw turn text — `repl.internal/parse-forms`

The LLM reply is captured verbatim to a blob (`turn.cljs` `ask-and-eval-reply!`)
then run through `parse-forms` (`repl/internal.cljc`) and `eval-batch!`. The
FORMS-AND-PROSE-ONLY rule (locked #50/#52) is the load-bearing gate:

- **Only a LIST `(…)`** (and list-shaped reader macros `'x @x #(…)` `` `(…) `` `#'x`),
  or a bare `result/<id>` re-reference, is EVALUATED.
- **Everything else is prose and is DROPPED, not echoed:** bare atoms,
  sentences, a bare `ANSWER: 4` token, a bare `=>` echo, tagged literals, AND a
  top-level data literal `{…}`/`[…]`/`#{…}` (dropped with ONE ⚠ warning).
- A markdown code-fence line is stripped (Postel); a leading backtick is
  classified as inline prose (dropped) or throws → `:read`.
- A genuinely broken FORM (opener at span start, `(+ 1 3x)`) becomes a `:read`
  failure the agent sees next turn; a best-effort parinfer repair runs on top
  (`eval.cljs` L3941+) for missing/surplus closers only — never guesses tokens.

**Consequence for replies:** if the model writes its final answer as raw text
(`ANSWER: 4`, `The answer is C`), it is DROPPED — no eval, no message, no reply.
The ONLY way an answer leaves the agent is as the string ARGUMENT to a
`(message/user "…")` list form.

### The observed failure modes, mapped

- **`5602e6ac` (gsm8k)** — "ANSWER: 4" written repeatedly as raw markdown →
  each turn re-parsed as code → `:read` errors ("(17 - x) not defined") →
  never a `message/user` → reply "". 11 turns, zero delivery. The
  parse-forms drop is doing exactly what it's designed to; the model never
  learned the string must be a `message/user` argument.
- **`4237339d` (gsm8k)** — prose narration ending "Final answer:" with nothing
  after → no forms → `:no-forms` close → reply "".
- **mmlu `c8ff2c7c`/`ee8915ef`, arc `MEA_2016_5_4`** — answered-in-prose: the
  model DID `message/user` (delivery worked) but wrote "The answer is C" /
  "I've answered — D" instead of the "ANSWER: X" contract → the MCQ parser
  extracts nothing. (arc MEA is documented in `catalog.py` L98-103: the visible
  "ANSWER: D" is a misleading artifact of an unparsed second run; the real pod
  reply didn't carry a parseable contract token.)

### The teaching-vs-machinery gap

The context (prompt + `:repl` skill) TEACHES the parse rule accurately (prompt
L30-35: "A form RUNS only if it starts with ( … Everything else is treated as a
NOTE, not run"). What it does NOT connect: **a stated output format is text, and
text is dropped — so the format string must be the argument to `message/user`.**
And it points the agent AWAY from the reply channel (prompt L105-107: the live
tile is "your PRIMARY surface", `message/user` is "narration/backup that scrolls
away") — but for the harness (and for a crisp answer) `message/user` IS the
deliverable and the tile is invisible.

---

## Part 2 — What our context currently teaches about replying

Verbatim from the retained rendered prompt
(`evals/runs/2026-07-03-first-dev-pass/rendered-context-sanity-prompt.txt`, a
full turn-0 context):

**It DOES say (good):**

- L30-35: "A form RUNS only if it starts with ( … a bare data literal you paste
  ({...}, [...], #{...}) — these do NOT evaluate and produce NO result. To use a
  value, wrap it in a form."
- L60-64: "REPORT THE VALUE YOUR LAST EVAL RETURNED. A number you state to your
  human … must be the ;=> value the runtime just wrote, never one you remember
  or read off source." (the fabrication content lever — already present.)
- L204-212: "TELL YOUR HUMAN with (message/user "…"). They see exactly what you
  send … send the answer when you have it."
- L240-250: "FINISHING IS AN ACT … The moment that thing EXISTS and you have
  delivered it (said the answer with (message/user …), or handed the pointer
  with (complete …)), the task is DONE."

**Gaps / ambiguities (nothing says these):**

1. **No line connects "raw text is dropped" to "so your answer must be a
   `message/user` argument".** L30-35 teaches the drop; L204 teaches
   `message/user`; the agent must bridge them itself, and the delivery-miss rows
   show it often doesn't.
2. **No line says a stated output FORMAT (e.g. "answer with ANSWER: X") goes
   INSIDE the delivered message** — `(message/user "ANSWER: X")`, verbatim — not
   typed as prose and not paraphrased.
3. **`complete` is described as delivering to a human** (L224-229 assume a
   parent; L251-253 "deliver the answer … it is how you close, whoever asked (a
   human or a parent agent)"). For a PARENTLESS top-level agent this is FALSE —
   `complete`'s string is discarded (Part 1 / Part 4 smell). The context never
   distinguishes the parent/no-parent case.
4. **The tile-vs-message framing (L105-107) actively de-prioritizes the one
   channel that IS the reply.** "message/user is narration/backup that scrolls
   away" is true for a live human watching a canvas, but wrong for "the
   deliverable answer" — and there is no carve-out saying the FINAL answer must
   still be delivered by message/user (or complete-to-parent).

The `:repl` skill body (prompt L303-360) reinforces the parse mechanics
correctly but is silent on delivery — it is about landing forms, not about which
form delivers the answer.

---

## Part 3 — Instruction-error inventory (every failing execution)

Recoverability note: **dev-pass** rows drove long-lived acme (blobs survive,
`data/clusters/acme/blobs/`, 226 blobs / 21M); **concurrent-pass** rows used
per-sample ephemeral clusters that were DESTROYED — those transcripts are gone,
so their root cause is UNRECOVERABLE beyond the jsonl `completion`/`score`
fields. gsm8k's 3 reply-discipline fails are reused verbatim from
[[deepseek-published-benchmarks-2026-07-04]] §"GSM8K outlier audit" (blob
evidence already recovered there — not redone).

### gsm8k — 3 genuine misses (7 others are label-noise golds, excluded)

| Row | Class | Evidence |
|---|---|---|
| `4237339d` e1 | INSTRUCTION (delivery-miss) | blob `78ef7362`: prose ending "Final answer:" nothing after; `:no-forms`; reply "" |
| `5602e6ac` e2 | INSTRUCTION (delivery-miss, math RIGHT) | blob `7e483c06`: derives x=4, writes raw "ANSWER: 4" → `:read` loops; never `message/user`; reply "" |
| `c7e0bdd1` e2 | INSTRUCTION (format/composition-miss, math RIGHT) | blob `94232109`: computed 4 blue + 6 red, replied the split; asked total (10) never stated |

### mmlu (concurrent) — 3 fails

| Row | Class | Evidence (jsonl `completion`) |
|---|---|---|
| `mmlu_c8ff2c7c` | **INSTRUCTION** (format-miss, content D RIGHT) | "I've answered the multiple choice question — D is the most representative…" → no "ANSWER:" token, extractor empty |
| `mmlu_ee8915ef` | **INSTRUCTION** (format-miss, content C RIGHT) | "The answer is **C) World War II**." → target C, no contract token, extractor empty |
| `mmlu_50ed4bef` | MODEL | "ANSWER: B" — contract followed, target A; wrong option |

### arc_challenge (concurrent) — 2 fails

| Row | Class | Evidence |
|---|---|---|
| `MEA_2016_5_4` | **INSTRUCTION** (format-miss) | scored I with empty extraction; visible "ANSWER: D" is a documented artifact of an unparsed second run (`catalog.py` L98-103) — real pod reply lacked a parseable contract token |
| `MCAS_2003_8_29` | MODEL | "ANSWER: C" contract-correct, target B; wrong option |

### gpqa_diamond (concurrent) — 3 fails (hard-science, thinking-off)

| Row | Class | Evidence |
|---|---|---|
| `rec4L69T0Y1AS4AFS` | MODEL | truncated reasoning, sc_ans=C, target D — wrong content |
| `recjgMJaMxz4ESDF2` | UNRECOVERABLE | prose analysis truncated, no completion tail, no transcript — content unverifiable |
| `reczQ4I0VpENdMtIj` | UNRECOVERABLE | no completion captured, no transcript |

### web_fetch — dev-pass EXCLUDED, concurrent 9 fails UNRECOVERABLE

Dev-pass web_fetch: all 8 rows are `hot_reload_contaminated` or `run_error` —
excluded as contaminated/flake (not genuine misses).

Concurrent 9 fails: every one DELIVERED a reply (reply_tail present, `:completed`)
but the VALUE is wrong. Transcripts destroyed (per-sample ephemeral). Pattern:

| Row(s) | gold → delivered | Shape |
|---|---|---|
| 000, 000e3 | 352 → 14, 11 | wildly off |
| 001e2, 001e3 | 1920 → 1887, 1892 | off by ~33 (systematic — a dropped row / boundary) |
| 002 | 325 → 247 | off |
| 005e1, 005e3 | 731 → 5, 1000 | off |
| 007 | 587 → 50 | off |
| 006 | "easels" → "Gizmo" | wrong string |

Delivery worked; the reply channel is NOT the failure here. Root is either a
compute error over fetched content or fabrication (see the shell finding) —
**UNRECOVERABLE per-row** without transcripts. The close 1920→1887/1892 pair
suggests a real read-and-sum-off-by-a-row bug worth a targeted re-drive WITH
capture, not a context change.

### shell_use (dev-pass, acme) — 7 fails = FABRICATION

| Row | Failure | Class |
|---|---|---|
| 000 e1 | line-count got '42' expected '12' | FABRICATION |
| 005 e1 | csv-count got '48' expected '2' | FABRICATION |
| 006 e3 | sum got '417' expected '340' | FABRICATION |
| 000 e2 | line-count.txt missing | incomplete |
| 001 e1/e3 | tundra.txt missing | incomplete |
| 005 e3 | csv-count got '2' expected '2\n' (trailing newline) | near-miss format |

The class is CONFIRMED live (not per-row transcript-recovered, but the mechanism
is nailed): `coordination.md` L663-693 — a live shell drive showed the model
**batching commands and inventing `ls`/`wc` outputs + "=> result" echoes in one
turn**, with the true `:seon.eval/result-edn` disagreeing (real `wc` exit 1).
"42" (the canonical hallucinated number) and "48" are fabricated counts, not
computed. The eval→tooling flag (L687-693) already identifies the render lever:
the transcript may re-show the model its own fabricated `=>` echoes as
`:seon.eval/narration` verbatim next to the real result-edn — reinforcing
fabrication. **Root = render mechanism (tooling lane), not a content gap** (the
"report the real ;=> value" teaching already exists, prompt L60-64).

### file_edit (dev-pass) — 1 genuine fail

| Row | Failure | Class |
|---|---|---|
| `seed1-004` | deploy.edn: updated :host + :port but omitted `:replicas 9` | MODEL (incomplete edit) |

(Other file_edit fails are `hot_reload_contaminated`, excluded.)

### long_term_planning (dev-pass) — 5 fails, ALL right-answer

Every fail has `final.ok = true` (the ANSWER is correct); the miss is trajectory
discipline. **All 5 = INSTRUCTION.**

| Row | trajectory failure |
|---|---|
| `seed1-000` | pre-restart steps left open (`done!` not called) |
| `seed1-003` | no pre-restart step completed after restart — resumption lost |
| `seed1-007` | no durable plan before restart (0 steps) + re-planned from scratch (2 new roots) |
| `seed1-008` | re-planned from scratch (new root after restart) |
| `seed1-009` | pre-restart steps left open |

Two discipline sub-classes: (a) not closing steps with `done!`; (b) re-planning
from scratch after a restart instead of resuming the rendered plan. Both are
context-teachable, both general.

---

## Part 4 — Candidate fixes (general only — the no-cheating law)

Ownership boundary (per `agent-ctx/CLAUDE.md`): **CONTENT** of context blocks /
system-text / skills = eval lane (via `config/system.edn` or the shared
instruction surface); **render MECHANISM** = tooling lane. Each candidate tags
its owner. None encode a bench answer; the one borderline case is flagged.

### Fix 1 — State the reply-channel truth (eval-lane content) · rank 1

Addresses: gsm8k `4237339d`/`5602e6ac`/`c7e0bdd1`, mmlu `c8ff2c7c`/`ee8915ef`,
arc `MEA` — **~6 rows directly**, and hardens every future bench. This is the
highest cross-row impact and the most clearly-general.

Draft (a tight addition to the MESSAGING+LIFECYCLE block, near prompt L204):

> YOUR DELIVERED ANSWER IS YOUR LAST `(message/user "…")`. Whoever asked reads
> exactly that string — nothing else. A value you only computed, a line of prose,
> or an answer typed raw (`ANSWER: 4` on its own line) is NOT delivered: raw text
> is a NOTE and is dropped, never sent. When you have the answer, put it INSIDE a
> message: `(message/user "…")`. If you were asked for a specific format, that
> exact format is the string you send — `(message/user "ANSWER: C")`, verbatim —
> not narrated and not paraphrased. `(complete …)` closes your run but does NOT
> say anything to a human; deliver with `message/user` FIRST, then `complete`.

Not answer-shaped (it teaches the delivery mechanism, not any answer). **Safe.**

### Fix 2 — Plan-resumption discipline (eval-lane content) · rank 2

Addresses: all 5 planning fails (the .286 headline row) — every one has the
right answer, so this is pure upside with no capability risk.

Draft (extend the existing plan standing-teaching, prompt L187-192):

> Lay the WHOLE plan first and CLOSE each step as you finish it: `done!` the step
> the moment its work is verified — an open step you actually finished reads as
> unfinished. After a restart, your plan is still rendered above you: RE-GROUND
> from it — take up the open steps and `done!` them as you complete them. Do NOT
> create a new plan for work you already planned; resuming the existing plan is
> the point, re-planning from scratch loses your progress.

BORDERLINE — must stay general. It says "resume, close steps, don't re-plan"; it
must NOT mention easels/flasks/couriers or the number of steps. As drafted it is
process-only. **Safe if kept generic; flag to owner to confirm it doesn't read
as coaching the planning bench specifically.**

### Fix 3 — Fabrication is a render defect, not a content gap (tooling-lane) · rank 3

Addresses: shell ×7 (+ suspected web_fetch). The CONTENT lever is already spent
(prompt L60-64 "report the real ;=> value … never one you remember"). The
residue is MECHANISM: the transcript re-shows the model its own fabricated `=>`
echoes (`:seon.eval/narration`) next to the true `:seon.eval/result-edn`, so the
next turn's context reinforces the fabrication. Fix belongs in `turn.cljs`
transcript assembly / the render — visually separate narrated-echo from the real
result so a fabricated `=> 42` can't masquerade as a value. **Owner = tooling
lane; A/B against the frozen shell rows.** Do NOT add more content here — a
louder "don't fabricate" line has no lever left to pull. Flagged, not drafted.

### Mechanism smell for the owner — `complete` silently drops the human answer

`lifecycle.cljs` L71-100: `(complete result)` sends `result` to the parent when
one exists; **with no parent the string is discarded** (docstring L76: "no
parent → the result is for the human (already said via message/user)"). But
nothing enforces that the human answer was said, and the context tells agents
`(complete "result")` "sends its result string" / "delivers the answer" (prompt
L224-253). A top-level bench agent that computes the answer and calls
`(complete "4")` — reasonably, per the context — delivers NOTHING.

Recommendation: **make `complete` deliver to the user when there is no parent**
(send `result` to `user-ref` in the no-parent branch), so the two "closing"
verbs both deliver. This collapses a whole failure class (compute-then-complete
without a separate message) into a success, and makes the context's own promise
true. Alternative (weaker): change the context to never imply complete delivers
to a human. This is a MECHANISM change (tooling lane) — surfaced with a
recommendation, not applied.

## Complexity artifacts found

- **`complete` no-parent silent drop** (`lifecycle.cljs` L74-76) — the smell
  above; a delivery path that depends on an unenforced assumption. Owner = tooling.
- **Two "answer" surfaces that aren't the reply** — the canvas
  (`:seon.render/html`, taught as PRIMARY, prompt L105-107) and `complete`'s
  string are both surfaces the `/agents/run` reply query never reads. The
  context frames `message/user` as backup while it is the ONLY delivery channel.
  Not a parallel mechanism to rip out, but a teaching that points away from the
  load-bearing surface — resolved by Fix 1, flag for owner.
- **web_fetch off-by-~33 pair** (1920→1887/1892) — a possible real
  read-and-sum bug distinct from fabrication; needs a targeted re-drive WITH
  capture, not a context change. Logged for the eval lane.
