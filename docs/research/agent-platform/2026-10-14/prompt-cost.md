---
title: "Prompt cost of an Implement attempt: where the 23-35k tokens per call go (S5 run 4b, attempt 68d331fe623f)"
status: complete (lane prompt-cost, 2026-10-14, SCI mode as agent `prompt-cost`, measurement only)
---

# Prompt cost of one Implement attempt

Attempt `68d331fe623f` (agent `attempt/68d331fe623f`, chain branch `:chain-f1f6dc8e6652`): 31 model calls, 727,316 prompt
tokens (521,600 cached), 7,488 completion. Only this attempt was measured; attempts 1 and 2 (40 calls, about 50k and 26k
tokens per call) are not reconstructed here.

## How it was measured, and its limits

- Real prompt size per call is the stored `:seon.ai.usage/prompt-tokens` / `:seon.ai.usage/cached-tokens` on each
  attempt (ordered by attempt entity id).
- History is `seon.context/exchanges` on the branch head (forms and replies as `seon.repl/reply-text` shows them),
  sized with `seon.ai.tokens/estimate`. The history before call k is the first k-1 turns, so the history sizes below are
  exact for the shown text; the reconstruction was checked against the real usage (below, within 3%).
- `seon.context/model-context` itself could not be called in SCI mode: it needs the render request's caps and context,
  and `{:seon.boot/cluster-name "default"}` answers `:seon.render/request-error` "The help render did not run" (2-3 s,
  4-10 GB allocated per refused call). So the block and system message are NOT rebuilt as sent. Their sizes come from the
  real usage minus the history, and the block's sections from `seon.context/current-state-ai` at the branch head sized by
  `pr-str` (an over-estimate of about 1.4x against the value printer; the section ratios hold, the absolute numbers do
  not). The block at call 1/15/30 is therefore the head block, not the block as of that call.

## Breakdown (estimated tokens)

| | Call 1 | Call 15 | Call 30 | Average of 31 |
|---|---|---|---|---|
| **Real prompt tokens** | 7,946 | 23,795 | 34,322 | 23,461 |
| of which cached | 896 | 17,536 | 28,288 | 16,826 (72%) |
| History (reconstructed, 0 / 14 / 29 turns) | 0 | 16,558 | 27,536 | about 15,700 |
|  history: agent forms (comments and code) | 0 | about 3,000 | about 7,000 | about 3,700 |
|  history: replies (as printed) | 0 | about 13,500 | about 20,500 | about 12,000 |
| System (`(help)` render) plus block (real minus history) | 7,946 | 7,237 | 6,786 | about 7,100 |
|  system (call 1 cache hit on the shared prefix) | about 900 | about 900 | about 900 | about 900 |
|  block (the rest) | about 7,000 | about 6,300 | about 5,900 | about 6,200 |

Reconstruction check: call 15 = 7.2k + 16.6k = 23.8k real; call 30 = 6.8k + 27.5k = 34.3k real.

Block sections at the head (`pr-str` estimate, 11.0k total, shown about 7.7k as the caller measured):

| Section | `pr-str` tokens |
|---|---|
| `:seon.context/must-reads` (21 entries) | 6,490 |
| `:seon.context/proofs` (red proof with both failing assertions as data) | 1,552 |
| `:seon.context/task` | 1,257 |
| `:seon.context/changes` (10 functions, 1 test, 1 schema, 2 unresolved) | 1,015 |
| `:seon.context/attempts` | 251 |
| `:seon.context/cost` | 247 |
| `:seon.context/step`, `chain`, `turns` | 211, 60, 25 |

Must-reads, in order (`pr-str` tokens, what, why):

| Tokens | Entry | Why it is there |
|---|---|---|
| 986 | `seon.error/reply-value` (doc: body, example, schemas) | the producer being changed |
| 651 | `docs/.../live-chain-rough-1.md` at doc level (heading, lead, 8-entry `below` outline with token counts, 2 nested refs) | cited by the plan; the file is 5,943 tokens but only the outline is shown |
| 642 / 443 / 441 | `seon.error/names-in`, `my.program/source`, `my.task/send-back!` | cited by the plan / generic verbs |
| 609 / 505 | `seon.error-test/a-guessed-name-...`, `a-guessed-schema-key-...` | the tests that guard the change |
| 421 / 307 / 285 / 238 | `seon.error/nearest-calls`, `names-shown`, `seon.db/db`, `:seon.error/nearest` | cited symbols |
| 217 / 181 / 161 / 84 / 75 | `:seon.fn/sym`, `nearest-names`, `seon.sci.eval/interpretation-error`, `calling-forms`, `declared-by-test?` | cited symbols |
| 69 / 46 / 45 / 34 / 33 | `clojure.core/subs`, `clojure.string`, `clojure.core`, `:seon.fn/private?`, `:seon.fn/arglists` | cited in the plan prose |

So no must-read is cited whole at code level here: the run-note is already an outline. The 21 entries are many small
ones, most re-explaining something the agent has just read or can `doc` in one call.

Per-reply meta in history: every reply opens `#:seon.repl{:result result/<12 hex>, :ns ..., :ms n, :alloc-mb n,` which is
about 25 tokens; 92 of the 95 replies carry it (about 2,300 tokens by call 31). The `:seon.cost` map is NOT in the history
(the 450-token `:seon.repl/request` cost map appears only in the MCP reply to an outside agent, not in `model-context`), so the
cost-map concern does not apply to Seon-agent prompts; it applies to every MCP reply, about 400-500 tokens each.

Biggest history items: the reply to `(library-names 'clojure.core)` (4,234 tokens, a flat list of every public name,
flagged `:large-value` by the REPL and sent whole at turn 8, then re-sent at every later call), the `my.program/source`
of `seon.error/reply-value` (about 2,650 tokens, the same text the must-read already carries at 986), and four
replies of 700-1,100 tokens (errors and sources).

Billing shape: the block is last, so it is never in the cached prefix. Uncached tokens per call average 6,635 (727,316
- 521,600 = 205,716 over 31 calls), which is the block plus the newest exchange. At cached prices the history is the
cheap part; the block is the expensive part.

## Top 5 waste items

Savings are per call, averaged over a 31-call attempt, in prompt tokens; "full-price" counts the uncached tokens only.

1. **The block re-sent uncached every call (about 6,200 tokens, all of it full price).** Producer:
   `seon.context/model-context` (`:seon.ai/prompt` is the block, laid after the exchanges). Fix: split the block into a
   stable head (task, plan, must-reads, fixed at the attempt's start) laid out before the history, and a volatile tail
   (proofs, changes, step, cost, turns) after it. The stable part (about 4,500 shown) then joins the cached prefix after call 1:
   no token reduction, but about 4,300 tokens per call move from full price to cached (an uncached share of 6.6k falls
   to about 2.3k, i.e. 65% less full-price input on this attempt). Caveat: a must-read that appears later must not
   reorder earlier text, so late citations go to the tail.
2. **Large agent-value replies kept at full size for the rest of the attempt (about 3,500 per call average, 4,234 and
   2,650 tokens twice-over).** Producer: `seon.context/exchange-form` (the history unit, built from
   `seon.repl/reply-text`). Fix: a history unit older than the last few turns whose reply is over a token threshold dial
   shows its value's head and the runnable elision form on its result handle (the value stays whole in `seon.cache`; the
   printer already emits `(drop n result/...)` forms). The unit is a function of content, so it is cached once. One-time
   recache at the turn it ages out. Saving about 3,000-3,500 on a call like this one, 0 for attempts with small replies.
3. **Must-reads that re-explain what the agent just read or the help already says (about 1,200 shown; 1,800 `pr-str`).**
   Producer: `seon.context/must-read-ai` (via `seon.context/current-state-ai`). Fix: cite at name level (symbol plus
   summary line plus the `(my.program/source ...)` operation, no body or example) every ref that is a function the agent
   has already opened with `my.program/source` or `doc` in this attempt, and every library or namespace-level ref
   (`clojure.core`, `clojure.string`, `clojure.core/subs`, `:seon.fn/*` attributes, `my.program/source`,
   `my.task/send-back!`, `seon.db/db`). That removes about 2,400 `pr-str` tokens now (about 1,600 shown, from the
   largest 14 entries) at the price of one `doc` call where the agent wants the body; the producer decides by the unit's
   level, not by a name list: the plan's citation level (`:doc` versus `:code`) is already data. Saving about 1,200-1,600.
4. **Reply meta and boilerplate findings in every history unit (about 1,200 per call average, 2,300 at the end).**
   Producer: `seon.repl/reply-text` through its response (`:seon.repl/ms`, `:seon.repl/alloc-mb`, `:ns`, and the
   `:findings` `:seon.repl/fix` prose repeated per reply, about 60-100 tokens each, 8 times in this attempt). Fix: the
   current evaluation's reply keeps the cost (the agent acts on it); a unit older than the last turn drops `:ms`/`:alloc-mb`/`:ns`
   and a finding's `:seon.repl/fix` text (keeping its `:seon.repl/finding` keyword, whose fix is in the help). Saving
   about 600-900 per call.
5. **The `changes` section echoes docstrings of functions the agent wrote this attempt, and unresolved entries repeat one
   message (about 1,000 `pr-str`, about 700 shown).** Producer: the `:seon.context/changes` render
   (`seon.context/current-state-ai`). Fix: name plus `doc`/`source` operation only for entries the attempt itself
   defined (the agent has the text in its own history), the one message once for all `:unresolved` entries, and the
   cost section (`:seon.context/cost`, 247) shown only when a measure regressed or is new at the first call it appears.
   Saving about 500-700.

Total if all five land: about 6,200 + ... moved to cached (item 1) plus 5,300-6,700 tokens removed per call
(items 2-5), i.e. 23% to 29% of the average prompt (23,461) and over 40% of the full-price input.

## Smaller observations

- The agent's own forms are 3,700 per call on average (comments and probes); not waste, they are the agent's reasoning.
- History never re-sends the `:seon.cost` map; only the MCP reply does (the 450-token map on every `eval_clj` reply
  here, mostly `:jvm-window`, `:shared`, `:stages`); trimming that is a render of `:seon.repl/request` for the MCP entrant,
  worth about 400 tokens per outside-agent reply, none for Seon agents.
- Reaching the block at all: 7,946 tokens at call 1 means about 7,000 of the first prompt is the block, so the Implement
  agent starts with about 8k before it asks anything; items 1 and 3 are the levers on that number.

## Frictions found (file as tasks; none filed here, no tasks file is claimed)

1. `seon.context/model-context` cannot be called from an agent's SCI context for another agent: a request with only
   `:seon.boot/cluster-name` answers `:seon.render/request-error` ("The help render did not run"), after 2-3 s and 4-10 GB
   allocated. There is no read-only way to rebuild a stored call's prompt at its basis (`seon.render.web/sent-context`
   does it on the web with the service's caps, as the debug page's `sent=true&basis=` query).
2. A `def` in an `eval_clj` reply is installed as a program entity and re-interpreted on every later evaluation: after
   `(def head ...)` failed once (database passed as a map), every later reply carried the two stale `:seon.sci.eval/program`
   errors and cost 400-2,000 ms; `(def ex ...)` re-ran a 490 MB allocation. Probes had to be single `let` forms.
3. `(ns-publics 'seon.ai.tokens)` in SCI returns `clojure.core` publics (`primitives-classnames`, `restart-agent`, ...)
   re-bound under `seon.ai.tokens`, not the namespace's own names. It misled the first probe.
4. Every evaluation over 1 s here returned `:slow` and `:large-allocation` findings with fix prose; those are correct,
   but the same two findings with the same fix text are repeated verbatim per reply (see item 4).
