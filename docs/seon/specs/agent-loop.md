---
type: spec
status: reviewed
created: 2026-10-07
tags: [spec, agent, loop, context, cache]
---
# The agent loop: one pass, facts on the agent (reviewed 2026-10-07)

What exists: a model reply takes three Flow passes and two turn entities (system turn + `:call` turn), an intent commit,
a capture, four bounds and boot recovery (`tmp/agent-loop/map.md` §0-1; `src/seon/turn.clj` 6055 lines). After: ONE
Flow transform per pass reads one database value, asks "is there work?" of the agent's own facts, renders its context
live through `seon.cache`, calls the provider, evaluates the reply through the one fold and writes ONE settlement
transaction (evaluations, program facts, the call, the agent's facts, deliveries). Principal approach: no turn or run
entity; evaluations and provider calls are their own entities referring to the agent; nothing is ever "open".

Owner rulings implemented (2026-10-07): agent information on the agent; evaluations ref the agent plus an ordinal and
the call that produced them; origin distinguishes model / outside / test; context rendered live at call time, cached
by content and read evidence, never stored; the call stores its basis; no system turns in the new loop (the old
selection code stays, unused and untested); crash loses the in-flight reply; exactly three bounds; MCP uses the same
evaluate-and-store path without a provider call.

Legend: **[V]** read or probed today at HEAD `a5a21fd58`; **[U]** unverified, proven in the named slice.

## 1. The agent entity after

Existing members stay: `id`, `branch`, `cluster`, `namespace`, `settings` (component), `archived-tx`,
`admission-closed-tx` (`resources/seon/schemas/seon.agent.edn:1-60` [V]). `runtime` stays for `listens` only.
Added (accretion, all optional so adoption is incremental):

| Attribute | Schema | Writer | Readers |
|---|---|---|---|
| `:seon.agent/last-ordinal` | `[:int {:min 0}]` | settlement's `record-call`, at the writer | `record-call` (next ordinal); context (history range) |
| `:seon.agent/current-ns` | `:seon.db/ref` to `:seon.ns/name` | every settlement (model, outside, test): the last form's ending namespace | next pass's starting namespace (replaces `fold-namespace` turn.clj:4261, `previous-ending-namespace` :563); context frame |
| `:seon.agent/answered-t` | `:seon.db/basis-t` | model-pass settlement whose call returned text: the context's basis | `work`; context (messages "new since") |
| `:seon.agent/disposition` | `[:enum :working :wait :completed]` | model-pass settlement: the disposing form's value, else `:working` | `work`; context frame; status |

Evaluation (`:seon.cluster.eval/receipt`, `seon.cluster.eval.edn:49` [V]) gains `:seon.cluster.eval/agent` (ref,
indexed), `:seon.cluster.eval/call` (optional ref to the provider call) and `:seon.cluster.eval/origin`
(`[:enum :model :outside :test]`, replacing `:author`, Q1); `:seon.cluster.eval/run` becomes optional. `ordinal` is
per agent, monotonic, assigned at the writer.

Provider call: the existing `:seon.ai/attempt` entity (`seon.ai.attempt.edn` [V], all 12 attributes installed on
default [V, probe]) gains `:seon.ai.attempt/agent` (ref), `:seon.ai.attempt/basis-t` (the context's basis; rebuild =
the context render over `(d/as-of db basis-t)`, exact while the render code is unchanged) and
`:seon.ai.attempt/characters` (prompt length; `model-calibration`, prompt.clj:85, joins on the call itself instead of
the capture).

**Is there work?** Derived from one database value; nothing stored for it:

```clojure
(defn work [db agent-id cap]                ; cap = :seon.config.run/max-episode-runs (Q6)
  (let [a        (db/pull db [:db/id :seon.agent/answered-t :seon.agent/disposition] [:seon.agent/id agent-id])
        [wake-t outside-t] (wake-ts db (:db/id a))   ; ONE walk of wake/agent-wake-datoms (wake.clj), newest first:
                                                     ; newest listened wake, newest OUTSIDE wake (outside-wake-t turn.clj:2627)
        calls    (or (db/q '[:find (count ?c) . :in $ ?a ?since
                             :where [?c :seon.ai.attempt/agent ?a]
                                    [?c :seon.ai.attempt/basis-t ?b] [(>= ?b ?since)]]
                           db (:db/id a) outside-t) 0)]
    (when (< calls cap)
      (cond (> wake-t (or (:seon.agent/answered-t a) 0)) :answer      ; a wake the model has not seen
            (= :working (:seon.agent/disposition a))      :continue))))
```

An inside wake (agent-sent message) makes work but never refills the cap; a failed call counts against the cap and does
not advance `answered-t`, so retries stop at the cap (Q3). Cost: one pull, one wake walk (O(the agent's wakes) index
seeks, measured 0.163 ms anchored, turn.clj:2640 docstring [V]), one count over the agent's calls since the anchor.

## 2. The pass

`seon.agent.loop/pass` replaces `seon.turn/step` (turn.clj:5807-6055) as the `:seon.agent/turn` proc's transform in
`graph-definition` (cluster/agent.clj:563); the mailbox and schedule procs stay.

```clojure
(defn pass [{:keys [cluster agent-id wake] :as state} in msg]   ; Flow transform
  (let [conn  (:seon.db/connection cluster)
        db    (db/db conn)
        sub   (when (= in :seon.agent/request) msg)                       ; MCP eval_clj / test / fixture
        work  (when-not sub (work db agent-id (cap db agent-id)))]
    (if-not (or sub work)
      [state nil]                                                          ; idle: writes nothing
      (let [ctx    (when work (context db agent-id))                       ; §3, through seon.cache
            {:seon.ai/keys [primary settings]} (when work (provider-targets db cluster agent-id)) ; turn.clj:4064, moved
            reply  (when work (ai/complete (db/carried-projection db)      ; ai.clj:1269, bounded by timeout-ms
                                           (assoc primary :seon.ai/prompt (:text ctx))))
            text   (or (:seon.cluster.reply/text sub)
                       (when-not (failed? reply) (ai/completion-text reply)))     ; ai.clj:833
            sources (when text ((if sub code-sources planned-sources) text))     ; turn.clj:3060 / 3047
            batch  (when (seq sources)
                     (turn/evaluate-sources                                 ; turn.clj:4875 -> batch/evaluate-batch :650
                      {:seon.turn.loop/cluster cluster :seon.db/db db :seon.agent/id agent-id
                       :seon.cluster.reply/sources sources
                       :seon.ns/name (current-ns db agent-id)}))            ; in memory; writes nothing
            tx     (settlement {:db db :agent-id agent-id :batch batch
                                :origin (cond sub (:origin sub :outside) :else :model)
                                :call (when work (call-tx agent-id ctx settings reply))
                                :answered (when (and work text) (db/basis-t db))})
            commit (settle! conn tx)]                       ; ONE transaction through turn.batch/settle (turn/batch.clj:683), §2.1
        (some-> (:seon.agent/reply sub) (async/put! (turn.batch/reply commit)))
        (when work (async/offer! wake :seon.agent/wake))                    ; rewake: re-derive, maybe idle
        [state nil]))))
```

Reused unchanged: the fold (`seon.batch/evaluate-batch` batch.clj:650 via `evaluate-sources`, which already sets
`:seon.batch/observe` so derive sees writes, run.md D8), `planned-sources`/`code-sources`, `ai/complete`,
`ai/completion-text`, `ai/model-observation-tx` (ai.clj:878), `turn.batch/program-facts-call` (turn/batch.clj:601),
`turn.batch/revalidate` (:650) and the settle retry, `evaluation-facts` (turn.clj:97), `message/reply` (message.clj:86)
and `message/delivery` (:179), `problems/form-problem`, `measurement/significant-tx`, `sci.eval/install-evaluated-rows!`
(sci/eval.clj:1524) after commit. `evaluate-sources` takes a `:seon.turn/id`; the pass supplies the call id (model) or
a fresh id (outside) as a value only, no entity [U: S1 confirms nothing inside the fold pulls it].

### 2.1 The one settlement transaction

1. `[:db.fn/call seon.agent.loop/record-call {...}]` at the writer: reads `:seon.agent/last-ordinal` on the writer's
   basis, assigns `n+1…n+k`, writes one evaluation per entry (`evaluation-facts`) with `agent`, `ordinal`, `origin`,
   `call`; asserts `last-ordinal` and `current-ns`. Ordinals decided at the authority, so the MCP thread's JVM run and
   the agent's proc never collide.
2. Program facts: `turn.batch/program-facts-call` with the batch's folded program.
3. The call (model passes): `call-tx` = the attempt row now built inline in `record-attempt!` (turn.clj:~4160-4224)
   made pure, plus `agent`, `basis-t`, `characters`, its error fact, `ai/model-observation-tx`. No `:seon.turn/attempts`.
4. Agent facts (model passes): `answered-t` when the call returned text; `disposition` from the publish stage's
   `:my.turn/value` (`my.turn.edn` `:wait`/`:completed`), else `:working`.
5. Deliveries: a `:completed` value replies to the sender of the newest wake message at the basis (`message/reply`);
   a red form of an owned namespace routes its problem assignment (today's `evaluation-terminal-data` turn.clj:3330,
   rewritten without turn queries, ~40 lines).
6. Measurement rows and staged blobs as `turn.batch/settle` (turn/batch.clj:683) already commits them.

A failed call with no forms is the same transaction minus 1-2 and 5. A settlement the writer refuses after the one
revalidate retry keeps every value; the boundary records it as a fault (errors.md §3) and writes the call alone so the
cap still counts it. Nothing is written before the settlement: a crash or stop mid-pass loses that reply only;
`answered-t` did not move, so the next arm re-derives `:answer`.

### 2.2 Bounds (exactly three)

| Bound | Owner | Fires as |
|---|---|---|
| Provider timeout `:seon.config.ai/timeout-ms` (180000 on default [V]) | `ai/complete` | `::timeout` error on the call record |
| SCI evaluation time limit per form | `seon.sci.eval` (`:seon.sci.eval/time-limit-ms`, exists) | the form's error value |
| Model calls per outside wake `:seon.config.run/max-episode-runs` (100 on default [V]) | `work` | no work; the frame shows `calls left: 0 of N` |

The MCP await (`evaluate-source!`, cluster/agent.clj:781) waits on its reply port with no dial of its own: the pass it
waits for is bounded by the three above. A stop (`disarm!` agent.clj:1492) still interrupts and joins the transform;
it writes nothing (`record-interruption!` :1462 goes).

## 3. Context through the one cache

`seon.agent.loop/context` is the agent's AI render (Q5) and the only prompt producer:

```clojure
(defn context [db agent-id]
  (let [a       (db/pull db agent-pull [:seon.agent/id agent-id])
        live    [(derived db [::task agent-id]  #(task/steps-view % agent-id))           ; task.clj:510: intent + current step
                 (derived db [::inbox agent-id] #(messages-since % agent-id (:seon.agent/answered-t a)))]
        ords    (db/q '[:find ?o ?id :in $ ?a :where [?e :seon.cluster.eval/agent ?a]
                        [?e :seon.cluster.eval/ordinal ?o] [?e :seon.cluster.eval/id ?id]] db (:db/id a))
        units   (mapv (fn [[_ id]] (seon.cache/lookup ::eval-unit [id @sci.eval/loaded-identity profile-id] weigh
                                                       #(repl/render-ai (db/pull db eval-pull [:seon.cluster.eval/id id]))))
                      (sort-by first ords))
        history (prompt/select units budget calibration agent-id profile)]                ; prompt.clj:302, pure
    {:text (str (render-live live) (prompt/compose history) (frame a calls-left))
     :basis-t (db/basis-t db) :characters ...}))
```

- **Evaluation units** are keyed by `[eval-id loaded-identity profile-id]` in kind `::eval-unit`. An evaluation is
  written once by its settlement and never changed (the system turn's evidence refresh, turn.clj:2164-2181, is what
  mutated them; it is not on this path), so the key never goes stale and nothing invalidates it. `repl/render-ai`
  reads only the entity, its namespace name and renderer symbol (repl.clj:487-575 [V]) once the `changed?` query on
  turns (:525-539) goes in S4. Loaded identity in the key re-renders after a reload changes the printer.
- **Live reads** (task + current step, messages since `answered-t`) use `seon.health/derived` (health.clj:113 [V]):
  `seon.cache` keyed by the revisions of the attributes the derivation actually read, so a commit touching none of them
  is a hit. Faults reach the agent as messages (errors.md §6), so they arrive through the inbox read.
- **Not used**: `render.web/derive-context!` (web.clj:2461) and its per-context atom (`render/shared-cache`,
  render.clj:~1690), `render.walk/history` (walk.clj:841), the system turn. They stay loadable for the open context
  design; the new loop calls none of them and they get no new tests.

Cost per pass, unchanged agent: one pull, one ordinal query (O(agent's evaluations), ids only), N cache hits at
~150-220 ns (cache.clj docstring [V]), two `derived` hits at ~0.03 ms (health.clj:105 [V]), `select` over priced
units. After a pass, misses = the forms that pass settled. Target < 10 ms warm for 600 evaluations [U: measured in S1;
history today costs 3.6 s cold, `call-reach-key` docstring in render.clj].

Budget: `:seon.config.ai/prompt-token-budget` is 1,000,000 on default [V]; it must not exceed the configured model's
window (the DeepSeek lane verifies both) [U].

## 4. Deleted, kept unused, converted

Deleted (estimates from extents, turn.clj [V for starts]):

| Span | ~Lines | What |
|---|---|---|
| turn.clj:385-545, 615-860 | 390 | `open-for-agent`, `open-call`, `close-call`, `next-id`, `plan-call`/`source-rows`/`plan-tx`, `system-run-*`, submission open |
| turn.clj:1492-1540 | 50 | `recover-tx`/`recover-call` |
| turn.clj:2627-3023 | 360 | `next-agent-work`, episode/turns-left, `opening-deferred?`, `unanswered-*`, `latest-answering-turn-t`, `continuing-reply?` (`outside-wake-t` moves, ~40 kept) |
| turn.clj:3330-3790, 3786-3898 | 560 | `evaluation-terminal-data` (rewritten ~40), fault windows, `settle-batch!`, `settle-evaluated!`, `settle-batch-refusal!` |
| turn.clj:3965-4300 | 290 | attempt helpers, `record-attempt!` (pure row kept ~60), `fold-*`, `evaluation-request` |
| turn.clj:4300-4672 | 370 | `open-turn`, `await-turn-part!`, `call-turn` |
| turn.clj:5072-5277, 5400-6055 | 860 | `resume-turn`, `close-turn`, `episode-exhausted!`, `close-escaped-run!`, `turn`, completion backstop, permit, `agent-dial`, write-refusal park, `step` |
| context.clj capture + contributions | ~250 | `capture-tx` and rows (context.clj:336-420) |
| prompt.clj | ~120 | `capture-mismatch` (:336), `history-contributions`, turn-joined calibration |
| cluster/agent.clj | ~250 [U] | completion permit channel, `turn-thread` backstop parts, `record-interruption!`, armer's `turn/more-agent-work?` (now `loop/work`) |
| repl.clj:56-79, 525-539 | 40 | `frame`'s episode read (now from `work`), the turn `changed?` query |

About 3,500 out, about 450 in (`seon.agent.loop` ~300 with contracts, `record-call` ~60, `evaluation-terminal-data`
rewrite ~40, `call-tx` ~50). Kept loadable, unused and untested until the context design closes: `system-turn`,
`declared-sources`, `latest-evaluations`, `system-plan`, `generated-read-fault`, `record-evaluated-*`, `generate-turn`,
`compact!` (~1,150 lines, map §5), `render.walk/history`, `render.web/derive-context!`.

Converted callers (one commit each with their slice; counts are `:seon.turn/` mentions in `src` [V]):

| File | Mentions | Conversion |
|---|---|---|
| `render/transcript.clj` | 154 | group by `:seon.cluster.eval/call` then ordinal; runtime trigger render goes |
| `cluster/agent.clj` | 36 | graph transform, armer, `submit-source!`/`evaluate-source!` reply without `:seon.turn/id`, `virtual-turn!` = a request with a fixture `:seon.ai/send!` |
| `eval/drive.clj` | 29 | dispositions from `:seon.agent/disposition`, results from the reply message |
| `render/web.clj` | 21 | debug context page shows `loop/context`; context actions (system turn, virtual turn) go or call the pass |
| `bootstrap.clj` | 21 | `generated-run-tx` seed becomes a message to root (a wake) [U: root's first task wording] |
| `cluster/status.clj`, `context.clj`, `sci/eval.clj`, `error.clj`, `cluster.clj` (boot `recover-tx` :2966), `problems.clj`, `repl.clj`, `render.clj`, `mcp.clj`, `effect.clj`, `prompt.clj`, `message.clj`, `test.clj`, `my/program.clj`, `turn/world.clj`, `walk.clj`, `fault.clj`, `wake.clj`, `eval.clj` | 2-15 each | agent ref + ordinal instead of run; `eval/of-agent` (eval.clj:17) orders by `[agent ordinal]`; MCP `observed` reads the reply's evaluation ids |

Schema: S1 adds the attributes of §1 (accretion; `run` made optional). Retired in S5: `:seon.turn.work/situation`,
`:seon.turn/trigger`, `handled`, `attempts`, `disposition`, `result`, `reply*`, `:seon.runtime/trigger`,
`:seon.context.capture/*`, `:seon.context.contribution/*`, `:seon.cluster.eval/author`,
`:seon.config.agent/turn-completion-backstop-ms` (also `seon.await.edn:35`, `seon.flow.edn:67`). `:seon.turn/id`,
`agent`, `opened-tx`, `closed-tx` and `:seon.cluster.eval/run`, `executed`, `code-evidence` stay installed while the
kept system-turn code references them; they go with it. Each change is published and adopted on a branch of the live
store through `bin/seon check` (adoption onto the existing store, never a fresh boot). The 595 stored evaluations carry
`run` but no `agent` (Q2).

## 5. Slices

Each lands through `bin/lane commit LANE -m … --test NS… PATHS`; HEAD boots after each.

1. **S1 One real pass.** New `src/seon/agent/loop.clj` (`work`, `context`, `call-tx`, `record-call`, `settlement`,
   `pass!` = the pass body callable once from the REPL), §1 schema accretion, `outside-wake-t` moved to
   `cluster/wake.clj`. Not yet wired into the graph. Proof on a disposable branch agent: overlay `no-provider false`
   and the verified DeepSeek flash model id (`:seon.config.ai/model "deepseek-flash"`, endpoint
   `https://api.deepseek.com/chat/completions`, `no-provider true` on default [V]); send it a message with a small
   task; `(seon.agent.loop/pass! cluster agent-id)`; check the call (basis, usage, characters), the evaluations
   (agent, ordinal, origin `:model`, call), `answered-t`, `disposition`, and that `context` rebuilt as-of the basis
   equals the sent text's length. The first-turn risk the map named (contribution attributes absent) is refuted
   today: they are installed [V, probe]; S1 writes no capture, and its proof publishes and adopts exactly the
   attributes `settlement` writes. The paid run is one call, owner-authorized. Tests T1-T5 with a fixture `send!`.
2. **S2 The proc.** `graph-definition`'s turn proc → `loop/pass`; armer `agents-to-arm` (agent.clj:1586) → `loop/work`;
   permit channel and backstop removed from `arm!`. Live: two DeepSeek passes continuing to `:completed`. T7.
3. **S3 Outside agents and tests.** MCP SCI (`mcp.clj:227` `sci-run` → `evaluate-source!` → request port → pass) and
   JVM (`jvm-run` :255, `record-call` swapped) and the test harness entrant record origin `:outside`/`:test`;
   `evaluate-source!` awaits without the backstop dial. T6.
4. **S4 Readers.** The conversion table of §4; the `changed?` turn query in `repl/entity-emission` goes.
5. **S5 Deletion.** The deleted spans of §4, the boot recovery loop (cluster.clj:2966-2972), the dial; tests below.
6. **Later, not this spec:** when the context design closes, the system-turn/walk selection and the turn schema go.

## 6. Tests

New, in `test/seon/agent/loop_test.clj`, one behavior each, asserted on data, through injected worlds and a fixture
`:seon.ai/send!` value (no paid call):

- **T1 work** — a wake after `answered-t` is `:answer`; `:completed` with no newer wake is nil; at the cap a fresh wake
  is nil; an inside wake is `:answer` but does not reset the cap's anchor.
- **T2 one pass** — reply `"(+ 1 2)"` gives one evaluation `{agent, ordinal n+1, origin :model, call}`, a call with
  `basis-t`, `answered-t` = that basis, all asserted in one transaction (one `:t`).
- **T3 failed call** — a fixture timeout records the call with its error, no evaluation, `answered-t` unchanged, and
  `work` stays `:answer` until the cap.
- **T4 cache** — two `context` calls with no write between: zero `::eval-unit` misses (`seon.cache/status`); after a
  pass, misses equal the forms it settled.
- **T5 rebuild** — `context` over `(d/as-of db basis-t)` has the call's `characters`.
- **T6 outside** — an MCP SCI run writes evaluations with origin `:outside`, no call, moves `current-ns`, not
  `answered-t`.
- **T7 disposition** — a `(my.turn/complete …)` form stores `:completed`, delivers one reply message to the sender, and
  `work` is nil.
- **T8 crash** — a pass interrupted before settlement leaves the database value unchanged and `work` still `:answer`.

Deleted rather than kept green (machinery deleted): `turn_loop_test` (15 deftests), `turn_work_test` (12),
`turn_work_cost_test` (1), `turn_backstop_test` (3), `turn_boot_test` (2), `turn_continue_test` (2),
`turn_disposition_test` (1, replaced by T7), `context_capture_test` (1), `context_capture_history_test` (1),
`loop_proof_test` (3, 883 lines), the turn-entity, episode, recovery and system-turn deftests in `turn_test` (43) and
`cluster/turn_test` (50), and `cluster/prompt_test` cases on capture and calibration (16 total). Fold behaviors listed
in run.md §2 are kept; tests that observe them through a turn move to `run_test`. Per-test triage in S5 by the commit
that changed the behavior [U: exact keep/delete list].

## 7. Owner decisions (2026-10-07)

1. Origin: a new `:seon.cluster.eval/origin` enum `:model :outside :test`; `:author` retires.
2. The stored evaluations without an agent ref: reset default after S4 (data is disposable); no backfill, no second reader.
3. A failed call does not advance `answered-t`; the cap bounds retries.
4. Live reads: the task with its current step, and messages since `answered-t` (faults arrive as messages).
5. The context is the agent schema's own `:seon.render/ai`; identity becomes its first lines.
6. The cap reuses `:seon.config.run/max-episode-runs`, renamed `:seon.config.agent/max-calls-per-wake` in S5.
7. The raw reply is not stored: segmentation keeps prose as comments beside each form's source.
8. Sequencing: S1 starts while errors S1-S2 run, claiming only its new namespace, its test and the schema files it
   accretes; it builds error maps with the errors S2 constructor once that lands.
