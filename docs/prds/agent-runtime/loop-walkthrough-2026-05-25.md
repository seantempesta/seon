---
type: prd
status: draft
tags: [prd, agent, runtime]
---

# Agent runtime loop — four scenarios as literal data

Companion to [unified-loop-v1.md](unified-loop-v1.md). Each scenario
shows initial DB state, the stimulus event, every tx the dispatcher
produces in order, and what `seon.render/assemble-ai-context` returns
on the agent's next render. All data is literal — copy it into a REPL
and the maps validate against the registered schemas.

Timestamps use the form `#inst "2026-05-25T10:00:00.000Z"` with `+Ns`
suffixes for relative offsets (`+1s`, `+1.5s`, …). Tx-meta is written
as `^{:seon.db/origin :X}` next to each transact block.

## Common preconditions (all scenarios)

Substrate handlers are registered at boot — present in every scenario's
initial state, omitted from the per-scenario block for brevity:

```clojure
;; Substrate handlers (omitted from per-scenario state).
[{:seon.handler/name  :seon.handler/wake-on-message-to
  :seon.handler/agent nil
  :seon.handler/match {:seon.handler.match/attr :seon.message/to}
  :seon.handler/fn    'seon.runtime/wake-on-message-to
  :seon.handler/on-origin #{:user :agent :system}
  :seon.handler/priority 0}
 {:seon.handler/name  :seon.handler/route-async-result
  :seon.handler/agent nil
  :seon.handler/match {:seon.handler.match/attr :seon.async-result/agent}
  :seon.handler/fn    'seon.runtime/route-async-result
  :seon.handler/on-origin #{:user :agent :system}
  :seon.handler/priority 0}
 {:seon.handler/name  :seon.handler/process-turn-request
  :seon.handler/agent nil
  :seon.handler/match {:seon.handler.match/attr :seon.turn-request/agent}
  :seon.handler/fn    'seon.runtime/process-turn-request
  :seon.handler/on-origin #{:user :agent :system}
  :seon.handler/priority 0}
 {:seon.handler/name  :seon.handler/surface-system-error
  :seon.handler/agent nil
  :seon.handler/match {:seon.handler.match/attr :seon.system/error}
  :seon.handler/fn    'seon.runtime/surface-system-error
  :seon.handler/on-origin #{:user :agent :system}
  :seon.handler/priority 0}]
```

Stable `:seon.ctx/*` entities are also baseline. The default ones
(per `seon.render.default`) are:

```clojure
[{:seon.ctx/id        "ctx-stable-instructions"
  :seon.ctx/agent     [:seon.agent/id "<aid>"]
  :seon.ctx/updated-at #inst "2026-05-25T10:00:00.000Z"  ; never bumped
  :seon.render/ai     'seon.render.default/how-you-respond
  :seon.render/html   'seon.render.default/pretty-html}
 {:seon.ctx/id        "ctx-stable-conventions"
  :seon.ctx/agent     [:seon.agent/id "<aid>"]
  :seon.ctx/updated-at #inst "2026-05-25T10:00:00.000Z"
  :seon.render/ai     'seon.render.default/conventions
  :seon.render/html   'seon.render.default/pretty-html}
 {:seon.ctx/id        "ctx-stable-schemas"
  :seon.ctx/agent     [:seon.agent/id "<aid>"]
  :seon.ctx/updated-at #inst "2026-05-25T10:00:00.000Z"
  :seon.render/ai     'seon.render.default/schema-reference
  :seon.render/html   'seon.render.default/pretty-html}]
```

Volatile ctx (conversation, recent evals, async results) is produced
**per-tx by handlers** — those entities appear in the scenarios below.

---

## Scenario 1 — single user message → one productive turn → second narration turn → stop

### Initial state

```clojure
;; t0 — t = #inst "2026-05-25T10:00:00.000Z"
{:seon.agent/id          "A-abc123def456"
 :seon.agent/state       :stopped
 :seon.agent/step-count  0
 :seon.agent/max-steps   8
 :seon.render/ai         'seon.render.default/ctx
 :seon.render/html       'seon.render.default/view}
;; + the three stable ctx entities above, scoped to "A-abc123def456".
```

### Stimulus

User types "what's 2+2" in the loopback UI. Web handler calls
`seon.agent/chat`, which transacts:

```clojure
^{:seon.db/origin :user}
[{:seon.message/id      "msg-u-1"
  :seon.message/role    :user
  :seon.message/from    :user
  :seon.message/to      [[:seon.agent/id "A-abc123def456"]]
  :seon.message/content "what's 2+2"
  :seon.message/at      #inst "2026-05-25T10:00:00.000Z"}]
```

### Step-by-step

**Step 1.** Dispatcher walks added datoms. `:seon.message/to` matches
`wake-on-message-to`. Origin `:user`, on-origin allows. Handler returns:

```clojure
{:effects [{:effect/type :wake :agent "A-abc123def456"}]}
```

**Step 2.** `:wake` interpreter checks state `:stopped` → flips
`:running` and invokes `(run-agent-loop! "A-abc123def456")`. Transacts:

```clojure
^{:seon.db/origin :handler}
[[:db/add [:seon.agent/id "A-abc123def456"] :seon.agent/state :running]]
```

`process-turn-request` does not match (attr is `:seon.agent/state`, not
`:seon.turn-request/agent`). Origin-skip applies to other handlers
anyway.

**Step 3.** `run-turn!` opens a turn, calls `assemble-ai-context`,
calls the LLM (Promise resolves in 800ms with `";; addition\n(+ 2 2)"`).
`ask-and-eval!` writes the assistant message + eval entities + a
`:seon.ctx.recent-eval` entity (the volatile ctx for the eval result)
+ a `:seon.turn-request` (form-count is 1):

```clojure
^{:seon.db/origin :agent}
[{:seon.message/id      "msg-a-1"
  :seon.message/role    :assistant
  :seon.message/from    [:seon.agent/id "A-abc123def456"]
  :seon.message/to      [:user]
  :seon.message/content ";; addition\n(+ 2 2)"
  :seon.message/at      #inst "2026-05-25T10:00:00.800Z"}
 {:seon.eval/id        "ev-1"
  :seon.eval/agent     [:seon.agent/id "A-abc123def456"]
  :seon.eval/from-message [:seon.message/id "msg-a-1"]
  :seon.eval/source    "(+ 2 2)"
  :seon.eval/ok?       true
  :seon.eval/result-edn "4"
  :seon.eval/at        #inst "2026-05-25T10:00:00.820Z"}
 {:seon.ctx/id        "ctx-recent-eval-A-abc123def456"
  :seon.ctx/agent     [:seon.agent/id "A-abc123def456"]
  :seon.ctx/updated-at #inst "2026-05-25T10:00:00.820Z"
  :seon.render/ai     'seon.render.default/recent-evals-block
  :seon.render/html   'seon.render.default/pretty-html}
 {:seon.ctx/id        "ctx-conversation-A-abc123def456"
  :seon.ctx/agent     [:seon.agent/id "A-abc123def456"]
  :seon.ctx/updated-at #inst "2026-05-25T10:00:00.800Z"
  :seon.render/ai     'seon.render.default/recent-conversation
  :seon.render/html   'seon.render.default/pretty-html}
 {:seon.turn-request/id    "tr-1"
  :seon.turn-request/agent [:seon.agent/id "A-abc123def456"]
  :seon.turn-request/at    #inst "2026-05-25T10:00:00.821Z"}]
```

**Step 4.** Dispatcher walks the new datoms. `:seon.turn-request/agent`
matches `process-turn-request`. Origin `:agent`, allowed. Handler
reads `step-count 0`, `max-steps 8` → emits:

```clojure
{:tx [[:db/add [:seon.agent/id "A-abc123def456"] :seon.agent/step-count 1]]
 :effects [{:effect/type :wake :agent "A-abc123def456"}]}
```

`:wake` finds state already `:running` → no-op. The recursive
`run-agent-loop!` call inside `:wake` is gated by an
"is-loop-fiber-running?" check — already alive, returns. The current
fiber continues into its next turn.

**Step 5.** Second turn. `run-turn!` → LLM returns `";; 2+2 = 4"`
(narration only, no parens to parse). `ask-and-eval!` writes the
assistant message, bumps `:seon.ctx.conversation`'s `:updated-at`, and
because form-count is 0 does **not** transact a `:seon.turn-request`:

```clojure
^{:seon.db/origin :agent}
[{:seon.message/id      "msg-a-2"
  :seon.message/role    :assistant
  :seon.message/from    [:seon.agent/id "A-abc123def456"]
  :seon.message/to      [:user]
  :seon.message/content ";; 2+2 = 4"
  :seon.message/at      #inst "2026-05-25T10:00:01.620Z"}
 {:seon.ctx/id         "ctx-conversation-A-abc123def456"
  :seon.ctx/updated-at #inst "2026-05-25T10:00:01.620Z"}]
```

**Step 6.** `run-turn!`'s wrapper sees zero forms → transacts:

```clojure
^{:seon.db/origin :system}
[[:db/add [:seon.agent/id "A-abc123def456"] :seon.agent/state :stopped]]
```

### Final state

```clojure
{:seon.agent/state       :stopped
 :seon.agent/step-count  1
 :messages 3      ; one user, two assistant
 :evals    1
 :turn-requests 1 ; tr-1
 :ctx entities    ; 3 stable + recent-eval + conversation = 5}
```

### Next-render `assemble-ai-context`

Query returns 5 ctx entities for the agent, sorted by `:updated-at` asc:

| Order | id | updated-at | renderer |
|---|---|---|---|
| 1 | ctx-stable-instructions | 10:00:00.000 | how-you-respond |
| 2 | ctx-stable-conventions | 10:00:00.000 | conventions |
| 3 | ctx-stable-schemas | 10:00:00.000 | schema-reference |
| 4 | ctx-recent-eval-… | 10:00:00.820 | recent-evals-block |
| 5 | ctx-conversation-… | 10:00:01.620 | recent-conversation |

Rows 1-3 are the cache-stable prefix. Rows 4-5 are the dynamic tail.

---

## Scenario 2 — Agent A spawns Agent B with refs, B replies, A wakes

### Initial state

```clojure
;; Agent A is :running mid-turn. Has emitted a (seon.agent/spawn! ...)
;; form whose eval issued an :effect/type :spawn-agent.
{:seon.agent/id "A-aaa111aaa111" :seon.agent/state :running ...}
;; B does not exist yet.
```

### Stimulus

The `:spawn-agent` effect interpreter runs:

```clojure
^{:seon.db/origin :system}
[{:seon.agent/id          "B-bbb222bbb222"
  :seon.agent/state       :stopped
  :seon.agent/step-count  0
  :seon.agent/max-steps   8
  :seon.agent/parent      [:seon.agent/id "A-aaa111aaa111"]
  :seon.render/ai         'seon.render.default/ctx
  :seon.render/html       'seon.render.default/view}
 {:seon.message/id      "msg-spawn-1"
  :seon.message/role    :user
  :seon.message/from    [:seon.agent/id "A-aaa111aaa111"]
  :seon.message/to      [[:seon.agent/id "B-bbb222bbb222"]]
  :seon.message/content "Validate this datom: [42 :foo/bar \"x\"]"
  :seon.message/refs    [[:seon.datom/id "datom-1"]]   ; a ref into A's workspace
  :seon.message/at      #inst "2026-05-25T11:00:00.000Z"}]
```

### Step-by-step

**Step 1.** Dispatcher: `:seon.message/to` matches
`wake-on-message-to` scoped to B. Returns:

```clojure
{:effects [{:effect/type :wake :agent "B-bbb222bbb222"}]}
```

**Step 2.** `:wake` flips B to `:running`, invokes B's `run-agent-loop!`.
Transacts:

```clojure
^{:seon.db/origin :handler}
[[:db/add [:seon.agent/id "B-bbb222bbb222"] :seon.agent/state :running]]
```

**Step 3.** B's first turn. `assemble-ai-context` for B includes the
new message in its `recent-conversation` section (B is the `:to`); B's
default ctx renderer also walks `:seon.message/refs` and pulls
`[:seon.datom/id "datom-1"]` into the prompt. LLM emits a
`(seon.datom/valid? ...)` form + reply. Two transacts:

```clojure
^{:seon.db/origin :agent}
[{:seon.message/id      "msg-b-1"
  :seon.message/role    :assistant
  :seon.message/from    [:seon.agent/id "B-bbb222bbb222"]
  :seon.message/to      [[:seon.agent/id "A-aaa111aaa111"]]
  :seon.message/in-reply-to [:seon.message/id "msg-spawn-1"]
  :seon.message/content ";; valid? false — :foo/bar is :int but value is string\n(seon.datom/valid? ...)"
  :seon.message/at      #inst "2026-05-25T11:00:01.200Z"}
 {:seon.eval/id "ev-b-1" :seon.eval/agent [:seon.agent/id "B-bbb222bbb222"]
  :seon.eval/source "(seon.datom/valid? ...)" :seon.eval/ok? true
  :seon.eval/result-edn "false" :seon.eval/at #inst "2026-05-25T11:00:01.220Z"}
 {:seon.turn-request/id "tr-b-1"
  :seon.turn-request/agent [:seon.agent/id "B-bbb222bbb222"]
  :seon.turn-request/at #inst "2026-05-25T11:00:01.221Z"}]
```

**Step 4.** Two handlers fire on this tx:

- `wake-on-message-to` matches `:seon.message/to` → A (the recipient
  of B's reply). Returns `{:effects [{:effect/type :wake :agent "A-aaa111aaa111"}]}`.
- `process-turn-request` matches `:seon.turn-request/agent` → B.
  Returns `{:tx [[:db/add ... :step-count 1]] :effects [{:effect/type :wake :agent "B-bbb222bbb222"}]}`.

Both fire in priority order (both priority 0; deterministic by handler-name).

**Step 5.** A's wake: A is already `:running` mid-turn → no-op (the
existing fiber will see the new message on its next render). B's
wake: B already `:running` → no-op.

**Step 6.** B's second turn runs. LLM emits narration-only (the work
was done). Zero forms → no `:seon.turn-request` → close path flips
`:seon.agent/state :stopped` for B.

**Step 7.** A's current turn completes naturally; on its next render
it sees the message from B.

### Final state

A and B both `:stopped`. Messages form a two-way thread via
`:seon.message/from` / `:seon.message/to`. Pulling A:

```clojure
(db/pull '[* {:seon.message/_from [*]
              :seon.message/_to   [*]}]
         [:seon.agent/id "A-aaa111aaa111"])
;; shows both A→B and B→A messages.
```

### Next-render `assemble-ai-context` for A

Includes A's stable prefix + a `recent-conversation` entity whose
`:updated-at` is now 11:00:01.200 (when B replied). The conversation
renderer pulls every message where A is `:from` OR in `:to` — so B's
reply appears.

---

## Scenario 3 — `:run-llm` effect; agent stops; async result arrives 3s later; agent continues

### Initial state

```clojure
{:seon.agent/id "A-aaa333aaa333"
 :seon.agent/state :running
 :seon.agent/step-count 2
 :seon.agent/max-steps 8 ...}
;; Agent has been working on a query; just emitted a form that called
;; (seon.llm/ask-async {:prompt "..."}).
```

### Stimulus

The agent's eval batch executes:

```clojure
(seon.llm/ask-async {:prompt "summarize the eval log"})
```

…which the `seon.llm/ask-async` fn implements by emitting an effect:

```clojure
{:effect/type :run-llm
 :agent       "A-aaa333aaa333"
 :corr        "corr-llm-99"
 :request     {:model "..." :messages [...]}}
```

The effect is queued; the fn returns a sentinel `:seon.llm/pending`.

### Step-by-step

**Step 1.** `eval-batch!` finishes; `run-turn!` writes the assistant
message (containing the call + narration). Since one form was emitted
and it returned successfully (the sentinel value), `:seon.turn-request`
is transacted:

```clojure
^{:seon.db/origin :agent}
[{:seon.message/id "msg-a-3" :seon.message/role :assistant ...}
 {:seon.eval/id "ev-async-1" :seon.eval/ok? true
  :seon.eval/result-edn ":seon.llm/pending" ...}
 {:seon.turn-request/id "tr-async-1"
  :seon.turn-request/agent [:seon.agent/id "A-aaa333aaa333"] ...}]
```

**Step 2.** `process-turn-request` bumps step-count to 3, emits `:wake`.
The next turn's LLM call returns narration-only ("I'm waiting on the
async summary"). Zero forms → stop. Transacts:

```clojure
^{:seon.db/origin :system}
[[:db/add [:seon.agent/id "A-aaa333aaa333"] :seon.agent/state :stopped]]
```

**Step 3.** 3 seconds elapse (10:00:04). The `:run-llm` Promise (queued
back in Step 1) resolves. The interpreter transacts:

```clojure
^{:seon.db/origin :system}
[{:seon.async-result/id    "ar-llm-1"
  :seon.async-result/agent [:seon.agent/id "A-aaa333aaa333"]
  :seon.async-result/of    [:seon.effect/corr "corr-llm-99"]
  :seon.async-result/ok?   true
  :seon.async-result/value "(brief textual summary)"
  :seon.async-result/at    #inst "2026-05-25T12:00:04.000Z"
  :seon.render/ai          'seon.async-result/render-ai
  :seon.render/html        'seon.async-result/render-html
  :seon.ctx/updated-at     #inst "2026-05-25T12:00:04.000Z"}]
```

**Step 4.** Dispatcher: `:seon.async-result/agent` matches
`route-async-result`. Returns:

```clojure
{:effects [{:effect/type :wake :agent "A-aaa333aaa333"}]}
```

**Step 5.** `:wake` finds `:stopped` → flips `:running` and invokes
`run-agent-loop!`. Next render's `assemble-ai-context` includes the
new `:seon.async-result` entity (renderer `seon.async-result/render-ai`
emits a `## Async result` block). Agent reasons about the summary,
emits a final message, stops naturally.

### Error variant

If the LLM HTTP fetch threw (network unreachable), the interpreter
catches and transacts:

```clojure
^{:seon.db/origin :system}
[{:seon.async-result/id    "ar-llm-2"
  :seon.async-result/agent [:seon.agent/id "A-aaa333aaa333"]
  :seon.async-result/of    [:seon.effect/corr "corr-llm-99"]
  :seon.async-result/ok?   false
  :seon.async-result/error {:seon.error/kind   :network
                            :seon.error/msg    "ECONNRESET"
                            :seon.error/at     #inst "2026-05-25T12:00:04.000Z"}
  :seon.async-result/at    #inst "2026-05-25T12:00:04.000Z"
  :seon.render/ai          'seon.async-result/render-ai
  :seon.ctx/updated-at     #inst "2026-05-25T12:00:04.000Z"}]
```

Same path. The agent's renderer surfaces the error envelope; the agent
decides whether to retry. No exception ever propagates.

### Final state

`A-aaa333aaa333` `:stopped`. The `:seon.async-result` entity is in
history; future renders include it (until it ages out via janitor —
out of scope).

---

## Scenario 4 — Agent registers its own handler mid-turn

### Initial state

```clojure
{:seon.agent/id "A-aaa444aaa444"
 :seon.agent/state :running
 :seon.agent/step-count 0 ...}
;; No agent-scoped handlers yet.
```

### Stimulus (turn 1)

LLM emits this form during a turn:

```clojure
(seon.handler/register!
  {:seon.handler/name  :my/auto-rerun-failed-eval
   :seon.handler/agent [:seon.agent/id "A-aaa444aaa444"]
   :seon.handler/match {:seon.handler.match/attr   :seon.eval/ok?
                        :seon.handler.match/value? false}
   :seon.handler/fn    'seon.agent.A-aaa444aaa444/auto-rerun
   :seon.handler/priority 50})
```

…and also `defn`s `seon.agent.A-aaa444aaa444/auto-rerun` in the same
batch (detect-and-tee captures it as a `:seon.fn` entity; the symbol
is now resolvable via `lookup-value`).

### Step-by-step

**Step 1.** `eval-batch!` runs the `defn` first (no error), then the
`register!` call. `register!` transacts:

```clojure
^{:seon.db/origin :agent}
[{:seon.handler/name      :my/auto-rerun-failed-eval
  :seon.handler/agent     [:seon.agent/id "A-aaa444aaa444"]
  :seon.handler/match     {:seon.handler.match/attr   :seon.eval/ok?
                           :seon.handler.match/value? false}
  :seon.handler/fn        'seon.agent.A-aaa444aaa444/auto-rerun
  :seon.handler/on-origin #{:user :agent :system}
  :seon.handler/priority  50
  :seon.handler/updated-at #inst "2026-05-25T13:00:00.000Z"}]
```

**Step 2.** Dispatcher walks this tx. The added datoms are
`:seon.handler/*` attrs — no registered handler matches those attrs
(by design — handler-tx doesn't trigger handlers; only the
handler-index cache invalidates). No effects.

**Step 3.** Next form in the batch (still turn 1) is a deliberately
failing eval:

```clojure
(throw (ex-info "test failure" {}))
```

`record-eval!` transacts:

```clojure
^{:seon.db/origin :agent}
[{:seon.eval/id    "ev-fail-1"
  :seon.eval/agent [:seon.agent/id "A-aaa444aaa444"]
  :seon.eval/source "(throw (ex-info ...))"
  :seon.eval/ok?    false
  :seon.eval/error  "test failure"
  :seon.eval/at     #inst "2026-05-25T13:00:00.100Z"}]
```

**Step 4.** Dispatcher walks. `:seon.eval/ok?` with value `false`
matches the just-registered `:my/auto-rerun-failed-eval` (agent-scoped
to A). Origin `:agent`, allowed. Handler is invoked. `auto-rerun` was
written by the agent to do this:

```clojure
(defn auto-rerun [{:seon.db/keys [db tx-report]}]
  ;; emit a system message asking the agent to look at the failure
  {:tx [{:seon.message/id "msg-sys-rerun-1"
         :seon.message/role :system
         :seon.message/to   [[:seon.agent/id "A-aaa444aaa444"]]
         :seon.message/content "An eval just failed. Auto-rerun handler fired."
         :seon.message/at   (js/Date.)}]})
```

**Step 5.** Handler returns `{:tx [...]}`. Dispatcher transacts:

```clojure
^{:seon.db/origin :handler}
[{:seon.message/id "msg-sys-rerun-1" ...}]
```

**Step 6.** New tx with `:seon.message/to` value pointing at A —
`wake-on-message-to` matches. But origin is `:handler`. The default
`:on-origin #{:user :agent :system}` does NOT include `:handler` →
the wake handler skips. No re-fire, no loop. The new message will be
visible on A's next natural render (A is still `:running` from the
current turn).

### Final state

```clojure
{:handler entities  1 substrate × 4 + agent-scoped × 1 = 5
 :messages           includes the auto-generated system message
 :evals              ev-fail-1 (the failure)}
```

### Next-render `assemble-ai-context` for A

The `recent-conversation` ctx entity's `:updated-at` was bumped by the
system-message tx → it moves to the very end of the prefix-stable
prefix → tail order. Agent sees the system message and reasons about
it on its next turn.

---

## Notes shared across scenarios

- **Cache-stability invariant**: in every scenario, the first 3 ctx
  entities (`ctx-stable-instructions`, `ctx-stable-conventions`,
  `ctx-stable-schemas`) keep their original `:updated-at`. The bytes
  the LLM provider sees for those positions are identical
  turn-over-turn → prefix-caching wins.
- **Handler-origin skip is the dominant cycle guard**. Scenarios 1, 2,
  and 4 all rely on it. Scenario 4 in particular demonstrates the
  "agent transacts via handler → wake-on-message would re-fire but
  doesn't" path.
- **Every async path closes through a tx**, never a Promise the agent
  awaits directly. Scenario 3 is the canonical example.
- **No `:effect/type :tx` appears anywhere.** Handlers return `{:tx ...}`
  directly; the dispatcher transacts under `:seon.db/origin :handler`.

## Cross-references

- [unified-loop-v1.md](unified-loop-v1.md) — design.
- [loop-testing-strategy-2026-05-25.md](loop-testing-strategy-2026-05-25.md) — how each scenario becomes a test.
- `src/seon/render.cljs` — `assemble-ai-context` lives here.
- `src/seon/agent.cljs:597-706` — current `run-turn!` / loop.
