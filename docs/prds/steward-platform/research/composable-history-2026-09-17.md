---
type: research
status: review
created: 2026-09-17
tags: [render, turn, prompt, history, composability]
---

# Composable history — how the agent's history is built, and how to make it units

Read end to end before writing: the program-facts PRD §1e F3/F4
(`docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md:186-219`),
owner decisions Part 1.2 and Decision 7
(`docs/prds/steward-platform/plan/owner-decisions-2026-09-17.md:89-119`, `:536-604`),
batch B §7 (`docs/prds/steward-platform/plan/decisions/batch-b-admission-render-turn-2026-09-17.md:173-307`),
AGENTS.md §2.4 and the vocabulary rows for the agent's history, shown text,
render function and block, and the turn PRD §13–§15
(`docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md`).

**The headline, and it is not what the decision documents assume.** The prompt
path is ALREADY per-evaluation and composed. `seon.render.walk/history`
(`src/seon/render/walk.clj:891`) renders each evaluation entity separately
through the evaluation schema's declared AI pair and returns a vector of
units; `seon.render.web/history-segments` (`src/seon/render/web.clj:2381`)
turns those into segments and `derive-context!` (`:2464-2472`) publishes both
the segments and their `(apply str segments)` join. **Nothing on that path
clips.** The giant-string cut the decision documents attribute to the prompt
lives in a different renderer — `seon.render.transcript`, which serves the
web page, `seon.eval.drive`, and the concurrency and transcript regressions —
and specifically in `bounded-scalar`/`floor-text`
(`src/seon/render/transcript.clj:412-435`), which re-admits an ALREADY
RENDERED AI string as a scalar value node and fits it again. That is a second
clipping spot and an AGENTS.md §2.4 violation on its own terms.

So F3's composable design is mostly a promotion of what walk/history already
does, plus the deletion of the transcript's re-fit and the addition of the one
thing that is genuinely missing: a selection step under the prompt's own
budget, and a per-unit size that selection can read without re-rendering.

---

## 1. How the history is built today, end to end

### 1.1 The prompt path (what the provider actually receives)

| step | file:line | what it does |
|---|---|---|
| `seon.cluster.prompt/prompt` | `src/seon/cluster/prompt.clj:215-243` | validates the request, resolves the agent's effective AI settings once, derives the model calibration, calls `acquire-context-report` with `:seon.config.ai/prompt-token-budget` (`:236`) |
| `acquire-context-report` | `src/seon/cluster/prompt.clj:188-213` | calls `render/acquire-context!`, appends `repl/frame`, prices `history-contributions`, computes `tokens/budget-report` (`:207`) |
| `seon.render/acquire-context!` | `src/seon/render.clj:1562-1583` | picks the basis (turn-opening db when `:seon.turn/id` is supplied), delegates to `render.web/derive-context!`, and for a turn-scoped request checks the saved capture reconstructs (`captured-history`, `src/seon/render.clj:1548-1560`) |
| `seon.render.web/derive-context!` | `src/seon/render/web.clj:2411-2481` | resolves the profile once (`:2422`), reuses the shared render cache when call evidence and read evidence are current (`:2434-2441`), else calls `render.walk/history` (`:2444-2453`) |
| `seon.render.walk/history` | `src/seon/render/walk.clj:891-928` | **per evaluation**: `seon.eval/of-agent` (`:902-905`), then for each saved evaluation one `render/render-call` with `:seon.render/output :seon.render/ai` and `:seon.render.call/id [[:seon.cluster.eval/id …]]` (`:913-917`), collecting `{:seon.render.history/call-id … :seon.render/value … :seon.render.history/basis-transaction … :seon.render.history/bytes …}` (`:920-924`) |
| `seon.render/render-call` | `src/seon/render.clj:1352` | selects the declared producer, reuses a retained projection, invokes it |
| declared pair | `resources/seon/schemas/seon.eval.edn:5-8` | `:seon.eval/entity` declares `#:seon.render{:ai seon.repl/render-ai, :html seon.repl/render-html}` |
| `seon.repl/render-ai` | `src/seon/repl.clj:412-417` | `(text (entity-emission unit))` when the evaluation has source |
| `seon.repl/text` | `src/seon/repl.clj:228-243` | prompt line + comment + source (`input-text`, `:220-226`) then `response` (`:202-217`) |
| `seon.repl/response` | `src/seon/repl.clj:202-217` | for an evaluation with `:seon.eval/renderer`, the saved `value-text`; otherwise the ordered `#:seon.repl{…}` reply map |
| `seon.render/present-output` | `src/seon/render.clj:1236-1241` | **passes the producer's output through unchanged** — "the value renderer's AI projection is the one place presentation elides (owner, 2026-09-08); this seam passes the data through" |
| `history-segments` | `src/seon/render/web.clj:2381-2387` | `(str (when (pos? position) "\n\n") (:seon.render.history/bytes entry))` per entry |
| join | `src/seon/render/web.clj:2468` | `:seon.cluster.prompt/text (apply str segments)` |
| verdict only | `src/seon/ai/tokens.cljc:195-210` | `budget-report` classifies `:over`/`:near-limit`/`:within`; **nothing trims** |

**Consequences of this reading.**

1. The units already exist and are already keyed: `:seon.render.history/segments`
   is a vector, one string per evaluation, already consumed by
   `seon.cluster.prompt/prompt` (`src/seon/cluster/prompt.clj:205`) to price
   contributions. Today `prompt` re-joins them into `text` and prices them; it
   never selects among them.
2. The shown text each unit contains was bounded ONCE, at evaluation time, by
   the value renderer (`seon.sci.eval/shown-result`, `src/seon/sci/eval.clj:2035-2064`,
   which calls `render.value/prepare` + `render-ai-data` under
   `render/request-profile`). So the prompt path is already
   "profile-bounded per result, never re-bounded".
3. **There is therefore no clipping of the prompt today at all.** With
   `:seon.config.ai/prompt-token-budget` at 1,000,000 (`config/default.edn:387`,
   confirmed live below) the prompt is effectively unbounded, and its declared
   dial enforces nothing. That is the honest current state, and it is Decision 7's
   open half.

### 1.2 The path that DOES cut — and it is not the prompt

`seon.render.transcript/render-ai` (`src/seon/render/transcript.clj:742-747`)
is used by `src/seon/render/web.clj:1437`, `src/seon/eval/drive.clj:286`, and
the concurrency/transcript regressions — **not** by `seon.cluster.prompt`.
Its `ai-output` (`:623-636`) genuinely elides nothing and says so. The cut is
one layer earlier:

```clojure
;; src/seon/render/transcript.clj:412-435
(defn- floor-text
  [unit value]
  (if (:seon.sci.eval/ctx unit)
    (value/render-ai
     (cond-> (assoc unit :seon.render/value value)
       (nil? (:seon.render.call/id unit))
       (assoc :seon.render.call/id [::history-value value])))
    (pr-str value)))

(defn- bounded-scalar
  [unit value]
  (when (some? value)
    (let [bounded (floor-text unit value)]
      (if (= (pr-str value) bounded) value bounded))))
```

`rendered-family` (`:437-452`) renders an entity through `render/render-call`
and then passes the RESULTING STRING to `bounded-scalar`. The value renderer
admits that string as a `::string` node and `seon.print/fit-text`
(`src/seon/print.cljc:1212-1237`) cuts it at a character offset under the agent
value profile, emitting an elision node whose `::path` is `[]` — it names no
turn, no evaluation, and no attribute. `message-text` (`:454-473`) then
interpolates that node's printed form into its text.

The exact bytes, verbatim from
`tmp/orchestrator/gate-results/batch-70/named.log:1659` (read today, the log is
still on disk):

```
FAIL in (n-agents-fold-independently-on-one-live-cluster) (concurrency_independence_test.clj:497)
s0-n5 with N=5
expected: (str/includes? rendered (:seon.concurrency-independence-test/payload incoming))
actual: (not (str/includes? {:seon.ai.tokens/estimate 492, :seon.print/bound-by :seon.render.profile/token-budget, :seon.print/elision-unit :characters, :seon.print/omitted 1575, :seon.print/prefix ";; I should read this message and decide how to respond.\n(my.message/read #:my.message{:id \"9f9f0d0b\"})\n\nmy.agents.concurrency.s0-n5.a0=> (seon.db/transact! {:tx-data [#:seon.test.run{:id \"stress-s0-n5-row-0-0\", :at #inst \"2026-09-16T12:58:27.208-00:00\", :git-sha \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"} #:seon.test.run{:id \"stress-s0-n5-row-0-1\", :at #inst \"2026-09-16T12:58:27.208-
```

Read the node: `:seon.print/bound-by :seon.render.profile/token-budget`,
`:seon.print/elision-unit :characters`, `:seon.print/omitted 1575`,
`:seon.print/prefix` ending mid-token. Those are `fit-text`'s fields
(`src/seon/print.cljc:1226-1236`). The subject is a whole REPL session's bytes
— a prompt line, a comment and two forms — proving the string that was fit had
already been rendered by the REPL grammar and was re-admitted as a scalar.

**Correction to batch B §7(a).** Its claim that the prompt "is rendered by
`seon.render.transcript/render-ai` … [whose] entries are joined into ONE string,
and that string is then fitted" is wrong for the prompt path at HEAD: the prompt
never calls `transcript/render-ai`, and `present-output`
(`src/seon/render.clj:1236-1241`) explicitly passes through. The cut is real,
the bytes are real, and it is a second clipping spot — it just sits on the page
/ drive / test path, in `transcript/bounded-scalar`. Decision 7's ruling is
unaffected in direction; its seam attribution needs this correction.

**Two more drift findings while reading.** AGENTS.md cites
`src/seon/turn.clj:2039` for the system turn; at HEAD (working tree)
`seon.turn/system-turn` is `src/seon/turn.clj:2191`. Batch B §7(b) notes the
evaluation identity attribute is still `:seon.cluster.eval/id`
(`resources/seon/schemas/seon.cluster.eval.edn:21`) while the family is named
`seon.eval` — confirmed; `seon.eval/of-agent` forces that attribute into every
selector (`src/seon/eval.clj:58-60`).

---

## 2. What is stored per evaluation, and what is not

From `resources/seon/schemas/seon.eval.edn` and
`resources/seon/schemas/seon.cluster.eval.edn`, both read end to end:

| question | answer | attribute / line |
|---|---|---|
| Is each evaluation's shown text a per-unit stored value? | **Yes.** One string per evaluation, produced once at evaluation time under the render profile | `:seon.eval/shown`, `resources/seon/schemas/seon.eval.edn:41-44`; written at `src/seon/sci/eval.clj:2058` (`shown-result`) and `:2198` (the unarmed/interrupted arm) |
| Are `;;` comments stored, separately from source? | **Yes, separately.** "The agent's own comment, stored verbatim" | `:seon.cluster.eval/comment`, `resources/seon/schemas/seon.cluster.eval.edn:14-19`; `:seon.cluster.eval/source` is `:6-7` |
| Is the renderer that produced the shown text stored? | **Yes**, as symbol and as a program ref: "History emits it directly without selecting or invoking a renderer again" | `:seon.eval/renderer` / `:seon.eval/renderer-fn`, `resources/seon/schemas/seon.eval.edn:2-3` |
| Is printed output stored? | Yes | `:seon.cluster.eval/output`, `seon.cluster.eval.edn:24-25` |
| Is the error stored? | Yes, as a message string | `:seon.cluster.eval/error`, `:3` |
| Is read evidence stored? | Yes, as component refs | `:seon.cluster.eval/read-evidence`, `:4-5`; `:read-basis-transaction`, `:2` |
| Ordinal / turn ref / namespace / instant | Yes | `:ordinal :28`, `:run :23`, `:ns :22`, `:at :204` |
| Is the HTML render stored? | **No.** It is re-derived per page load — from the LIVE result object when the agent's SCI context still holds it (`live-response`, `src/seon/repl.clj:428-446`, reading `:seon.sci.eval/result-objects`), otherwise from the saved shown text | `seon.repl/render-html`, `src/seon/repl.clj:445-474` |
| Is the provider reply stored? | Yes, on the TURN, inline or as a blob | `:seon.turn/reply`, `:seon.turn/reply-blob`, `:seon.turn/reply-size`, `resources/seon/schemas/seon.turn.edn:19-25` |
| Is the agent's reasoning text stored? | Yes, on the ATTEMPT, inline or as a blob | `:seon.ai.attempt/reasoning`, `:seon.ai.attempt/reasoning-blob`, `resources/seon/schemas/seon.ai.attempt.edn:7`; read back by `reasoning-attempts`, `src/seon/render/transcript.clj:581-621` |

**What a composable render would need and does NOT have on the entity today:**

1. **A per-unit size.** There is no stored token estimate or character count for
   the rendered unit. `:seon.print/length` and `:seon.print/level`
   (`seon.cluster.eval.edn:55-60`) are the form's `*print-length*`/`*print-level*`
   bindings, not a size. Selection under a budget therefore has to render every
   unit first, then measure — which is what `history-contributions`
   (`src/seon/cluster/prompt.clj:157-186`) does today, after the join.
2. **Nothing else.** Comments are already separable, shown text is already
   per-unit, the renderer identity is already a ref, the turn ref and ordinal
   already order the units. F3's "we can change how we are storing the data on
   the entity record if that's an issue" — it is not an issue. The storage is
   right; the ASSEMBLY is what is missing a step.

---

## 3. The composable design

### 3.1 The unit

**One unit per evaluation, not per turn.** Three reasons, each grounded:

- The evaluation entity is the one that declares an AI/HTML pair
  (`resources/seon/schemas/seon.eval.edn:5-8`); the turn's declared pair is
  `seon.render.transcript/render-history-ai`, which deliberately returns `""`
  (`src/seon/render/transcript.clj:1043-1048`: "The prompt is this concern's AI
  projection, so emit no duplicate text") — the turn has no AI body to compose.
- The evaluation is the unit that already carries its own bounded shown text
  (§2), so per-evaluation selection needs no re-rendering and no second clip.
- A turn's evaluations are contiguous by `(turn-tx, ordinal)`
  (`src/seon/eval.clj:47-64`), so "whole turns" is expressible as a grouping
  over evaluation units when we want it, while the reverse is not true.

Turn grouping stays a PRESENTATION concern of the HTML side (turn headers) and
an optional selection granularity — not a storage or composition change.

### 3.2 The functions

`seon.render.walk/history` already returns units. Promote its entry map to a
declared shape and give it a size, then add one pure selector. All three
functions take their world as arguments (§2.1).

```clojure
;; src/seon/render/walk.clj — unchanged behaviour, one added key per unit.
;; Each unit: {:seon.render.history/call-id …    ; requery identity
;;             :seon.render.history/subject [:seon.cluster.eval/id "…"]
;;             :seon.render.history/basis-transaction t
;;             :seon.cluster.eval/ordinal n
;;             :seon.turn/id "…"
;;             :seon.render.history/bytes "…"     ; the rendered unit
;;             :seon.ai.tokens/estimate n}        ; ADDED: (tokens/estimate bytes calibration)
(defn history
  {:malli/schema [:=> [:cat :seon.render.walk/history-request]
                  [:or [:vector :seon.render.history/unit] :seon.error/value]]}
  [request])

;; NEW, in seon.render.history (a new tiny namespace) or seon.cluster.prompt.
;; PURE. No database, no ctx, no rendering — it only chooses.
(defn select
  "Choose the newest whole units that fit `budget`, oldest dropped first.

  Returns the retained units and, when anything was dropped, ONE elision
  value naming the dropped count, the oldest surviving ordinal and the
  requery identity. This is SELECTION, not elision: every retained unit's
  own result was already bounded by the value renderer at evaluation time."
  {:malli/schema [:=> [:cat [:vector :seon.render.history/unit]
                       :seon.config.ai/prompt-token-budget]
                  :seon.render.history/selection]}
  [units budget])
;; => {:seon.render.history/units [...]
;;     :seon.print/elision {:seon.print/omitted 12
;;                          :seon.print/path [:seon.render.history/units]
;;                          :seon.render.history/oldest-retained
;;                            {:seon.turn/id "…" :seon.cluster.eval/ordinal 4}
;;                          :seon.print/requery
;;                            (seon.eval/of-agent (seon.db/db) "juniper")
;;                          :seon.print/bound-by :seon.config.ai/prompt-token-budget
;;                          :seon.print/elision-unit :evaluations}}

;; NEW. PURE. The composition; replaces `(apply str segments)`.
(defn compose
  "Join selected units, newest-last, with the elision named first."
  {:malli/schema [:=> [:cat :seon.render.history/selection] :string]}
  [selection])
```

`seon.cluster.prompt/acquire-context-report` becomes:

```clojure
(let [units     (:seon.render.history/units acquired)
      selection (history/select units budget)
      history   (history/compose selection)
      frame     (str (when (seq history) "\n\n") (repl/frame …))
      segments  (conj (mapv :seon.render.history/bytes
                            (:seon.render.history/units selection))
                      frame)]
  …)
```

**What this replaces or deletes.**

| deleted / replaced | file:line | why |
|---|---|---|
| `(apply str segments)` as the whole story | `src/seon/render/web.clj:2468` | becomes one of two consumers; `:seon.cluster.prompt/text` stays for the page and the capture check, the prompt uses `compose` on a SELECTION |
| `history-segments` | `src/seon/render/web.clj:2381-2387` | dissolves: the separator belongs to `compose`, the bytes already ride the unit |
| `transcript/floor-text` + `bounded-scalar` re-fit of rendered strings | `src/seon/render/transcript.clj:412-435` | **the actual second clipping spot.** A string that a declared AI renderer already produced must not be re-admitted as a scalar. Keep `floor-text` for genuinely un-rendered scalar values; refuse it for the output of `rendered-family` (`:437-452`) |
| `budget-report` as the prompt's only relationship to its dial | `src/seon/cluster/prompt.clj:207`, `src/seon/ai/tokens.cljc:195-210` | stays as the verdict, but now reports on a prompt the same dial selected |
| `history-contributions`' recomputation of per-segment tokens | `src/seon/cluster/prompt.clj:157-186` | reads `:seon.ai.tokens/estimate` off the unit instead of re-deriving cumulative differences |

**What changes on the entity.** Nothing is required. One optional accretion is
worth pricing: storing the unit's estimate as a derived fact is exactly the
"derive or die" trap — the estimate depends on the model calibration, which
changes as attempts accumulate (`seon.cluster.prompt/model-calibration`,
`src/seon/cluster/prompt.clj:83-114`), so a stored estimate would be a mirror
that goes stale. **Derive it on the unit at render time; do not store it.**

### 3.3 The HTML side (F4's dual render)

The same units, rendered by `seon.repl/render-html` (`src/seon/repl.clj:445-474`),
which already does the right thing: it renders the LIVE result object through the
value's own HTML pair when the agent's SCI context still holds it
(`live-response`, `:428-446`, via `value/render-html-data`), and falls back to
the saved shown text after restart, labelling which (`:469-473`,
"· saved text; live value unavailable"). Two gaps against F4:

1. **`;;` comments are not a distinct block.** `render-html` emits
   `input-text` (prompt + comment + source) as ONE `[:pre [:code {:class
   "seon-eval-prompt"} …]]` (`src/seon/repl.clj:465`). The comment is already a
   separate stored attribute, so the fix is local and needs no schema change:
   split the article into `[:div {:class "seon-eval-thinking"} comment]` and
   `[:pre [:code {:class "seon-eval-input"} prompt+source]]`. Note
   `render-emission-html` (`:427-448`) already lexes comments to
   `seon-syntax-comment` spans — that is styling, not a block; F4 asks for the
   block.
2. **HTML must have no presentation clipping** (§2.4). It has none on this path
   today: `render-html` reads the live object or the saved text directly. The
   transcript's `bounded-scalar` is the exception, and §3.2 deletes it.

### 3.4 Options, simplest first

**Option A — selection only (recommended).** Add `:seon.ai.tokens/estimate` to
each unit in `walk/history`; add pure `select` + `compose`; have
`acquire-context-report` call them; delete `transcript/bounded-scalar`'s re-fit
of rendered strings; split the comment into its own HTML block.
*Guarantee:* the newest turns are always COMPLETE and nameable; every retained
result is still bounded exactly once, at evaluation time; the omission is one
elision value with count, path and requery; the prompt is finally bounded by the
dial declared for it; exactly one clipping spot survives.
*Cost:* two small pure functions, one added key per unit, one deleted helper,
one HTML split. No schema change, no migration, no reset.
*What we give up:* the prompt stops being a pure `str` of whatever the walk
produced — the shape batch B §7(c) option 1 already priced.

**Option B — A, plus turn-granular selection.** `select` groups units by
`:seon.turn/id` and drops whole turns.
*Guarantee:* no turn is ever half-present, so "reply to message X" and its
consequences never separate.
*Cost:* the grouping, plus a refusal path when a single turn exceeds the whole
budget (a real case: one turn can hold many evaluations).
*What we give up:* granularity — a 30-evaluation turn is all or nothing, which
can drop far more than the budget required.

**Option C — A, plus a stored per-unit estimate on the evaluation entity.**
*Guarantee:* selection without rendering; a cheap "how big is my history" query.
*Cost:* a new attribute, plus a checker for calibration drift, plus the reset F5
already schedules.
*What we give up:* the honesty of a derived number. This is the hand-maintained
mirror AGENTS.md §2.2 forbids unless enforced. **Not recommended** unless
rendering every unit becomes a measured cost — and it is not: Juniper's whole
history is 6,467 characters (§5).

**Recommendation: Option A.** It is the smallest change that satisfies F3 and
Decision 7 together, it DELETES a mechanism (the re-fit) rather than adding one,
and it changes no stored fact. Option B is a later refinement of `select`'s
grouping argument, not a different design — `select` can take
`:seon.render.history/granularity` when we have evidence that split turns
confuse agents.

---

## 4. The per-turn reply status for conversational agents (F4)

### 4.1 Where the reply lives as a fact

There is **no `my.message/reply`** today. A reply is an ordinary message
carrying `:seon.message/about` pointing at the message it answers:

- `my.message/send` takes `:my.message/about` and delegates to
  `seon.cluster.message/send!` (`src/my/message.clj:31-44`; `send!` at
  `src/seon/cluster/message.clj:733`).
- `:seon.message/about` is declared as "The entity this message concerns when
  one was named. Its presence marks the message an INSIDE wake"
  (`resources/seon/schemas/seon.message.edn:25-31`), indexed, `:seon.wake/inside true`.
- Answering also removes the trigger's inbox edge
  (`src/seon/cluster/message.clj:258-271`; `:seon.message/inbox` in the entity
  shape at `seon.message.edn:88-90`).
- `my.message/decline` (`src/my/message.clj:46-62`) is the same write with the
  reason as content — i.e. the reply concept already exists as a fact family;
  only its NAME and its per-turn feedback are missing.

F4 says "a reply entity exists for the triggering message (a Markdown reply)".
That is exactly `:seon.message/about`. The one addition worth making is a named
`my.message/reply` that is `send` with `:my.message/about` required, so the
opening can name ONE exact form instead of teaching an optional key.

### 4.2 The done-condition query

Answeredness is already derived in exactly one place. `seon.turn/unanswered-triggers`
(`src/seon/turn.clj:3073-3089`) is "a projection of `unanswered-wakes` onto the
message family, not a second derivation: answeredness is decided in exactly one
place, by `:t`". A reply-shaped done condition is one clause on top of it:

```clojure
;; "a reply exists for the triggering message" — a query, not a stored flag.
[:find ?reply-id .
 :in $ ?agent-id ?trigger-id
 :where
 [?agent   :seon.agent/id      ?agent-id]
 [?trigger :seon.message/id    ?trigger-id]
 [?reply   :seon.message/about ?trigger]
 [?reply   :seon.message/from  ?agent]
 [?reply   :seon.message/id    ?reply-id]]
```

Absent reply ⇒ no binding ⇒ not done. Note the §2 recurring-failure test: this
check reports "not done" when its subject is absent, which is the wanted
direction — the opposite arrangement (a stored `replied?` boolean) would report
health on absence.

### 4.3 How the status reaches the agent every turn

Identically to the issue loop, with no new mechanism. `seon.issue/opening`
emits an ordinary generated read form — `(my.issue/status {:seon.issue/id …})`
(`src/seon/issue/opening.clj:76-77`, emitted by every candidate, e.g. `:118`,
`:126`, `:137`) — and the since-diff re-evaluates every distinct read form each
turn, appending a system-turn evaluation only when the shown text changed
(`seon.turn/system-turn`, `src/seon/turn.clj:2191-2199`: "Project the declared
opening and every distinct retained read form. Unchanged reads contribute no
evaluation"; generated reads are guarded by `generated-read-fault`,
`src/seon/turn.clj:2141`). The rendered text comes from the entity's declared
AI pair (`resources/seon/schemas/seon.issue.edn:47`) via
`seon.issue/status-text` (`src/seon/issue.clj:647-665`).

The conversation gets the same shape: `my.message/status` (or
`my.conversation/status`), declared on the message entity's AI pair, emitted in
the conversational opening, refreshed by the same since-diff. Modelled exactly
on `status-text`, one exact shown text:

```
my.agents.juniper=> (my.message/status #:my.message{:id "9f9f0d0b"})
Message 9f9f0d0b from root: still unanswered; 4 turns remaining.
"Summarise what the steward platform changed this week."
Done condition: you have not replied to message 9f9f0d0b.
Reply with: (my.message/reply
             #:my.message{:about "9f9f0d0b",
                          :content "…your Markdown answer…"})
```

and after the reply lands, the next turn's since-diff sees changed shown text
and appends:

```
my.agents.juniper=> (my.message/status #:my.message{:id "9f9f0d0b"})
Message 9f9f0d0b from root: replied in message 1c4a77e2; 3 turns remaining.
"Summarise what the steward platform changed this week."
```

Nothing new is stored: the status is a render function over
`:seon.message/about`, `:seon.message/from`, `:seon.message/inbox` and the
turn budget the issue loop already reads.

---

## 5. Measured sample — Juniper on `default`, 2026-09-17

Two read-only `mcp__seon__eval_clj` queries, jvm mode, cluster `default`,
explicit custody via `(seon.operator/connection "default")`. No transaction.

**Honest caveat on the production entry.** The JVM the MCP jvm mode reaches has
an EMPTY `seon.operator.runtime/running-instances` (`:instance-keys []`), so no
cluster instance and therefore no `:seon.sci.eval/ctx` was reachable, and
`seon.render/acquire-context!` refused with the exact bytes:

```
seon.render/acquire-context! refused request at [:seon.sci.eval/ctx]:
expected must be an SCI evaluation context, got nil.
Fix: must be an SCI evaluation context Contract: :seon.render/context-request.
```

That is AGENTS.md §4's documented jvm-mode property ("binds no cluster custody")
extended to the render request: **the production history entry cannot be driven
from jvm mode at all**, only from within the cluster. The stored-fact
measurements below are complete; the end-to-end render is not, and I did not
spend a third query.

| measurement | value | source |
|---|---|---|
| agents on `default` | `["juniper" "root" "2393cac275ae"]` | `[_ :seon.agent/id ?id]` |
| Juniper's evaluations | **16** | `seon.eval/of-agent` (`src/seon/eval.clj:9`) |
| with stored `:seon.eval/shown` | **16 of 16** | `seon.eval.edn:41` |
| with stored `:seon.cluster.eval/comment` | **10 of 16** | `seon.cluster.eval.edn:14` |
| with a declared `:seon.eval/renderer` | **3 of 16** | `seon.eval.edn:2` |
| shown-text characters per evaluation, in order | `[3191 112 1854 356 111 2 310 62 279 2 3 28 29 34 27 67]` | measured |
| total shown-text characters | **6,467** | measured |
| estimated tokens of that text | **2,020** (`6467 / 3.2`, integer-floored) | `seon.ai.tokens/estimate`, `src/seon/ai/tokens.cljc:145-157` |
| calibration used | shipped prior, **3.2 chars/token**, sample-count 17, basis `seon.ai.tokens/shipped-prior` | `seon.ai.tokens/shipped-calibration`, `:62-64` |
| `:seon.config.render.agent/token-budget` (value profile) | **15,000** tokens = **48,000 characters** | `config/default.edn:91`; `tokens/estimate-chars` |
| `:seon.config.ai/prompt-token-budget` | **1,000,000** tokens = **3,200,000 characters** | `config/default.edn:387` |
| `budget-report` verdict for the whole history | `:seon.ai.tokens/within` | `src/seon/ai/tokens.cljc:195-210` |

**Where today's cut falls.** Under the 15,000-token value budget the cut offset
is **48,000 characters** — Juniper's entire history is 6,467 characters, so
**the cut does not fire for Juniper today**, and no single evaluation comes
close (the largest shown text is 3,191 characters ≈ 997 tokens, 6.6 % of the
per-result budget). Under the PREVIOUS 1,024-token budget the offset was
**3,276 characters**, which lands inside Juniper's FIRST evaluation
(3,191 characters) plus the next 85 — i.e. the old dial would have shown the
first evaluation and cut in the middle of the second, dropping fourteen of
sixteen evaluations, exactly the shape batch-70 recorded.

**What this measurement does and does not prove.** It proves the dials are no
longer the acute problem and that the stored per-unit data needed for §3 is all
present. It does not prove the prompt is bounded — it is not; at 1,000,000
tokens the declared dial is a verdict over a prompt nothing trims, which is
precisely Decision 7's open half and the reason `select` is worth building
before a long-lived agent accumulates thousands of evaluations.

---

## Open items for the owner

1. Confirm Option A (§3.4). It answers F3 and Decision 7's open half together.
2. Confirm the unit is the EVALUATION, not the turn (§3.1), with turn grouping
   as a later argument to `select`.
3. `my.message/reply` as a named function (`send` with `:my.message/about`
   required), so the conversational opening can name one exact form (§4.1).
4. Batch B §7(a)'s seam attribution needs the correction in §1.2; the ruling
   direction is unchanged.
