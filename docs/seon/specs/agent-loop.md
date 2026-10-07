---
type: spec
status: reviewed
created: 2026-10-07
revised: 2026-10-07
tags: [spec, agent, loop, context, cache, task]
---
# The agent loop: one pass, facts on the agent

What exists: a model reply takes three Flow passes and two turn entities (system turn + `:call` turn), an intent commit,
a capture, four bounds and boot recovery (`docs/research/agent-platform/2026-10-07/agent-loop/map.md`;
`src/seon/turn.clj` 6037 lines). Live run 1 (`docs/research/agent-platform/2026-10-07/live-run/report.md`) shows the
existing loop runs a real DeepSeek agent end to end; what failed it was context and protocol, not turn machinery: no
system message, no way to run its proof, a shipped stop string cutting its replies, Markdown read as code.

After: ONE Flow transform per pass reads one database value, asks "is there work?" of the agent's own facts, builds a
provider request from the context design the owner approves, calls the
provider, evaluates the reply through the one fold, hands everything to the ONE shared settlement owner (one
transaction), and, when the pass changed code its task's success tests reach, runs those tests: all green completes
the task. Principal approach: no turn or run entity; a pass is an id minted before evaluation; evaluations and provider
calls are entities referring to the agent; completion is test evidence recorded through the task writer.

Rulings: §8. **[V]** read or probed 2026-10-07 (HEAD `42e569d9d`, default running it); **[U]** proven in the named
slice. Functions are cited by name: line numbers drift.

**Context dependency is blocked:** the owner rejected §3's composition on 2026-10-07. Section 3 is retained
for the redesign owner, not approved for implementation. P4(b), context-dependent tests and the context wiring in
the cut must be revised from `docs/research/agent-platform/2026-10-07/context-design/` before those steps proceed.

## 1. The agent entity after

Existing members stay: `id`, `branch`, `cluster`, `namespace`, `settings` (component), `archived-tx`,
`admission-closed-tx` (`seon.agent.edn` [V]); `runtime` stays for `listens` only. Added (accretion, all optional,
all declared `:seon.wake/context-inert true` so the old loop's generated reads never re-derive them), written on the
agent's row on its HOME branch in the same transaction as its evaluations (`home-call` keeps the cluster row for
routing; status and root read the home row):

| Attribute | Schema | Writer | Readers |
|---|---|---|---|
| `:seon.agent/last-ordinal` | `[:int {:min 0}]` | settlement's `record-call` at the writer | `record-call` |
| `:seon.agent/current-ns` | `:seon.db/ref` to `:seon.ns/name` | every settlement: the last form's ending namespace | the next pass's starting namespace (replaces `fold-namespace`, `previous-ending-namespace`); context frame |
| `:seon.agent/answered-t` | `:seon.db/basis-t` | model-pass settlement whose call returned text: the context's basis | `work`; the inbox read |
| `:seon.agent/disposition` | `[:enum :working :wait :completed :returned]` | model-pass settlement and completion (§4) | `work`; context frame; status |

**Evaluation identity.** A pass mints its id before evaluation (`seon.id/id`, a fresh event identity). Each form's
evaluation id is the installed two-argument `(id/evaluation pass-id position)`, position 0, 1, … within the pass; every
current minter already uses this arity with the run id [V], so the old loop passing its run id as the pass id keeps
every id and handle unchanged. Result handles `result/e<id>` are minted during evaluation and never change.
`:seon.cluster.eval/ordinal` keeps its meaning, the position. The agent-wide order is a separate value assigned at the
writer inside the settlement: `:seon.cluster.eval/agent-ordinal` = `last-ordinal + 1 + position`. Evaluation
(`:seon.cluster.eval/receipt` [V]) gains `agent` (ref, indexed), `agent-ordinal`, `pass`, `call` (optional ref),
`origin` and `error-signatures` (errors §6); `run` becomes optional on the receipt and on `:seon.eval/entity`.

**Origin.** `:seon.cluster.eval/origin` is `[:enum :model :outside :test]`. It replaces `:author` and the old
`:seon.eval/origin` string (the generating entity of system-turn bookkeeping; installed, `:db.type/string` [V]): the
producers, receipt schemas and readers convert together in the loop cut; the two never coexist as current facts.

**Provider call.** The existing `:seon.ai/attempt` entity [V] gains `agent` (ref), `basis-t`, `characters` (prompt
length) and `error-signatures`.

**Is there work?** Derived from one database value; nothing stored for it:

```clojure
(defn work [db agent-id {:keys [cap provider?]}]   ; provider? = no-provider is off, or a fixture :seon.ai/send! is supplied
  (let [a (db/pull db [:db/id :seon.agent/answered-t :seon.agent/disposition] [:seon.agent/id agent-id])
        {:keys [opening anchor]} (wake-ts db (:db/id a))     ; one fold over every candidate wake datom
        calls (or (db/q '[:find (count ?c) . :in $ ?a ?since
                          :where [?c :seon.ai.attempt/agent ?a] [?c :seon.ai.attempt/basis-t ?b] [(>= ?b ?since)]]
                        db (:db/id a) anchor) 0)]
    (when (and provider? (< calls cap))
      (cond (> opening (:seon.agent/answered-t a 0))  :answer
            (= :working (:seon.agent/disposition a)) :continue))))
```

`wake-ts` is today's `seon.turn/outside-wake-t` fold moved to `seon.cluster.wake` and widened to two maxima in one pass
over `wake/agent-wake-datoms` on `(db/history db)`:

- `opening`: the MAXIMUM transaction of a wake on a call-opening attribute (`wake/turn-opening-attributes`,
  `:seon.wake/opens-turn? true`), default 0. A schedule firing (`opens-turn? false` [V]) never opens a call.
- `anchor`: the MAXIMUM transaction of a listened wake from OUTSIDE the agent (`wake/inside-wake?`), default 0. A firing
  refills the cap; an inside wake (agent-sent message, own effect) never does.

Both are maxima over all candidates: `agent-wake-datoms` concatenates AVET slices ordered by entity, not transaction
[V], so no early exit. A failed call counts against the cap and does not advance `answered-t`: retries stop at the cap.
With no provider (`:seon.config.ai/no-provider`, true on default [V], enforced today only in the old loop, while
`ai/complete` never reads it [V]) a model pass is never admitted: no call, no spin. Cost: one pull, one fold over the
agent's lifetime wake datoms (O(wakes), by design), one count over its calls since the anchor.

## 2. The pass

`seon.agent.loop/pass` replaces `seon.turn/step` as the turn proc's transform in `seon.cluster.agent/graph-definition`;
mailbox and schedule procs stay; `turn-step`'s publish-thread and clear-interrupt protocol stays around it.

The transform has Flow's `(state in msg) -> [state out]` shape. Implement these obligations at the existing
owners; this is a target, not a callable installed `pass`:

1. Capture the submission and reply port before work that can throw. Read the agent's home connection and acquire
   one admission basis. An outside request bypasses model admission; a model pass must satisfy `work`.
2. Mint the pass id and, for model work, the call id before building context. A failed context or provider call is
   settled as call data. Keep its original id for every definitely-refused settlement attempt.
3. Segment supplied code or model text with the starting namespace and configured source bound. The installed
   entrances are `(code-sources text namespace-name max-source)` and `(planned-sources text namespace-name max-source)`;
   both return either sources or `:seon.cluster.reply/no-forms-error`. Discriminate that union before iterating: a
   refusal map is not a sequence of sources. Attach a no-forms refusal to the call and settle it so the cap counts it.
4. Evaluate once through `turn/evaluate-sources` and the shared fold, passing the request's SCI or JVM stage and
   acquired context. Hold the batch across every later failure. Settle it through §2.1, even when it has no forms.
5. Reply from the settled batch, with recorded diagnostics beside its values. Only a committed settlement may run
   completion (§4) or re-wake model work; an unknown outcome retains ownership under §2.1.
6. The entrant catch converts with `refusal/caught`, then explicitly records the error and attaches its receipt
   through the recorder (§6 of errors). `caught` alone does not record. Keep any held evaluated values in the reply;
   deliver each request's terminal reply before closing its port. Forward the recorded error to the supervisor
   without counting it again. A recording failure follows the recorder's stop path.

- `admission` reads the effective settings once: the cap `:seon.config.run/max-episode-runs` and `provider?`. A test or
  fixture supplies `:seon.ai/send!` as a request value; nothing is redefined.
- `provider-call` gives the call an identity before the context is built, then renders the context (§3) and calls
  `ai/complete` bounded by its timeout. A context that throws, cannot be rendered or exceeds the budget, and a provider
  failure, are the call row's error (recorded, with its receipt) and no evaluation: the call counts against the cap, so
  it cannot spin. It is `provider-targets` plus `ai/complete`, producing the pure call row (`call-tx`, today's attempt
  row inside `record-attempt!`, made pure) with `agent`, `basis-t`, `characters`, usage, error and
  `ai/model-observation-tx`.
- A model pass has no reply port; only submissions answer. One request owns one reply: every terminal path (settled,
  refused, thrown, stopped) answers it or closes it.
- `settle-pass!` is total, as `settle-evaluated!` is today [V]: an installation, recording or settlement failure after
  evaluation returns the held batch with the failure beside it as a recorded error, never replacing evaluated values.
- The request's evaluate stage is a value: the SCI fold by default, the JVM stage for MCP `jvm-run`, so JVM mode stays
  JVM mode through the same pipeline (nREPL's `::read-fn`/`::eval-fn` seam,
  `nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:72-125`).
- The provider request carries no stop sequence: `config/default.edn` ships `:seon.config.ai/stop ["#:seon.repl"]` [V],
  which cut 5 of 11 replies in live run 1 because the task's own code contained it (task
  `seon.ai/the-shipped-stop-sequence-cuts-replies-that-contain-it`). A reply the provider cut for any reason other than
  its natural end is an error value on the call that the agent reads.
- The reply segmenter reads only forms: prose, Markdown headings and backticked spans become comments
  (task `seon.turn/a-markdown-heading-in-a-reply-is-read-as-code`: `planned-sources` keeps `## What` as source [V]).

### 2.1 The one settlement owner

The existing settlement writer (`seon.turn/settle-evaluated!` → `settle-batch!` [V]) is generalized IN PLACE into
`settle-pass!`, taking a pass instead of a turn; it is also the `:seon.batch/settle` stage of the JVM and test entrants,
replacing `turn.batch/settle`'s run-shaped `record-facts` [V]. One owner, no copied private helpers. One transaction
holds:

1. `[:db.fn/call seon.turn/record-call {...}]` at the writer: reads `last-ordinal` on the in-transaction database,
   assigns each row's `agent-ordinal`, asserts `last-ordinal` and `current-ns`; the MCP thread and the agent's proc
   never collide.
2. Complete evaluation rows: attributes derived from the `:seon.cluster.eval/receipt` schema (today's
   `receipt-terminal-attributes`), namespace upserts placed before the rows that ref them (`evaluated-namespaces`),
   read-evidence component rows (`receipt-read-evidence-tx`), `agent`, `pass`, `origin`, `call`, and each evaluation's
   `error-signatures` from the receipts its batch entry carried (errors §6). Shown text is stored; result values never
   are (results are runtime only).
3. Program facts: `turn.batch/program-facts-call`.
4. The call row (model passes) with its `error-signatures`; a tempid the evaluation rows ref.
5. Agent facts (model passes): `answered-t` when the call returned text; `disposition` (§4).
6. Deliveries: a red form of an owned namespace routes its problem assignment (`evaluation-terminal-data`, rewritten
   without turn queries).
7. Measurement rows (`measurement/significant-tx`).

Staged blobs publish around the commit (`blob/with-publication!` [V]): today the run's reply text above the blob
threshold and recorded-error evidence; after the loop cut the raw reply is not stored and recorded errors store shown
text (errors §6), so only shown text above the threshold remains staged. Committed definitions install after the commit
(`sci.eval/install-evaluated-rows!`).

**Preparation keeps the old duties.** Until the loop cut the old loop calls `settle-pass!` with an explicit mapping:
its run id as the pass id, its turn and run rows, run closing, old completion delivery and disposition, and (until the
errors cut) the folded-fault drain are written exactly as today; each leaves at its own cut. Test-world custody
(`turn/world.clj`) is preserved [U: P1].

**Outcomes.** `settle-pass!` answers `{:outcome :committed|:refused|:unknown :batch settled-batch}`:

- *Committed*: the batch carries committed evaluation ids; the caller replies from it.
- *Definitely refused* (the writer rejected it): revalidate per form and retry once without the refused program facts,
  on a freshly acquired head (`turn.batch/revalidate`; installed `settle-batch!` already reacquires
  `(db/db connection)` after the first refusal, and this must be preserved); forms
  are never re-evaluated. A second refusal records the error and writes the call row alone, under its original
  identity, so the cap counts it.
- *Unknown* (`:seon.db/transaction-outcome-unknown` [V]): no retry and no call-alone write; the write may still commit.
  The agent stays unavailable until the writer reports that transaction's terminal outcome, then derives work from the
  resulting facts; it never re-arms against a head the outstanding write may still change [U: name the `seon.db`
  owner that holds the outstanding transaction; if it cannot report the outcome, the agent stays unavailable with a
  diagnostic]. Storage failing stops the graph: the supervising owner (`seon.cluster.agent`) disarms it.

**Effects.** Forms run with the agent's real connection: a message sent, a task written, a file edited or a remote
request made is durable when the form runs, before the settlement. The loop writes no intent, capture or attempt before
the settlement. A crash or stop mid-pass loses the loop's bookkeeping of that pass (evaluation rows, call row,
`answered-t`), never a completed effect; the next pass re-derives `:answer` and may repeat an effect unless that
effect's owner is idempotent.

### 2.2 Bounds (exactly three policy dials)

| Bound | Owner | Fires as |
|---|---|---|
| Provider timeout `:seon.config.ai/timeout-ms` (180000 on default [V]) | `ai/complete` | `::timeout` error on the call row |
| SCI evaluation time limit per form (dial `:seon.config.eval/time-limit-ms`, world member `:seon.sci.eval/time-limit-ms`) | `seon.sci.eval` | the form's error value |
| Model calls per outside wake `:seon.config.run/max-episode-runs` (100 on default [V]) | `work` | no work; the frame shows `calls left: 0 of N` |

Not dials, and kept: the terminal answer or close of every request (§2); stop. `disarm!` stops the Flow graph,
interrupts the transform and joins its executor under a deadline every lifecycle caller supplies
(`:seon.await/deadline-nanos`, which it already accepts; `release-context!` and re-arm, which pass none today [V], pass
the deadline they hold) instead of `turn-completion-backstop-ms`; while exit is unknown it keeps ownership of the
execution and the agent stays unavailable; then it closes queued requests and their replies. SCI interrupts
interpreted code, not host calls (`sci/doc/interrupt.md:52,63-87`): JVM-mode evaluation and host calls that ignore
interruption have no finite bound, and an MCP caller awaiting such a pass waits until it exits or the agent is
disarmed. The completion permit, backstop and `record-interruption!` go.

## 3. Context: a system message and task-generated context

The provider request is two messages (`ai/request-body` already sends `:seon.ai/system` as a system message and
`:seon.ai/prompt` as the user message [V]; nothing produces a system message today):

- **System message**: `seon.agent.loop/system-message` [V, installed, unwired]: the REPL protocol `(help)` answers
  (forms only, Markdown is read as code, run named tests with `my.test/check`, find with `clojure.repl/apropos` and
  `my.program/source`; target: give back a broken task with `my.task/complete!`, §4), then each toolkit namespace's
  own documentation. The same text on every call of one agent, so the provider caches it. Measured on default: 15,676
  characters (about 3.9k estimated tokens), 446 ms per build [V], so it is a `seon.cache` kind keyed by the toolkit's
  program identity, built once per program change, not per pass.
- **Task context**: `seon.agent.loop/task-context` [V, installed, unwired]: units `{why form value}` generated from the
  task the agent holds: the task, its edges, each proof test's recorded result, failing assertions and source, each
  must-read function's doc and source, the schemas its contracts name, other tasks naming the same functions, recorded
  errors raised by them. Each form is a requery the agent can run; `context-text` prints each value whole through the
  value printer. Measured: 3.7k-7.5k estimated tokens for three real tasks, 13-27 ms [V, context-examples].
- **Inbox**: wakes since `answered-t`: messages and task notifications for this agent, each shown once.
- **History**: this agent's own model evaluations (origin `:model`), selected newest-first under the remaining budget,
  shown oldest-to-newest. Outside runs under the agent's id (an observer's MCP setup) are not history: live run 1 spent
  27-42% of every prompt on them.
- **Frame**: identity, current namespace, disposition, `calls left: n of N`.

```clojure
(defn context [db home-db agent-id]
  (let [a      (db/pull db agent-pull [:seon.agent/id agent-id])
        cal    (prompt/model-calibration db model prior agent-id)
        system (cache/lookup ::system (toolkit-identity db) weigh #(system-message db agent-id))
        task   (derived home-db [::task agent-id] #(task-context % (held-task % agent-id)))   ; tasks live at the home
        inbox  (derived db [::inbox agent-id (:seon.agent/answered-t a)] #(inbox % agent-id))
        fixed  (price [system task inbox (frame a calls-left)] cal)]                       ; separators included
    (if (> fixed budget)
      (error :seon.agent.loop/unsendable-context-error `context "The required context exceeds the budget." {...})
      (let [sel (prompt/select (history-units db (:db/id a) profile) (- budget fixed) cal agent-id profile)]
        {:system system :prompt (compose task inbox sel frame) :basis-t (db/basis-t db) :characters ...}))))
```

- **Budget.** One total budget, `:seon.config.ai/prompt-token-budget`, covers the system message, mandatory units,
  frame and separators, priced first; if they exceed it the context is the `unsendable-context-error`, recorded on the
  call (§2); nothing is clipped. `prompt/select` gets a `[:int {:min 0}]` remainder (its contract takes the dial schema,
  minimum 1, today [V]) and may keep zero units: the keep-the-newest exception goes, and the elision's own text is
  priced. Budget 1,000,000 on default [V] must not exceed the configured model's window [U: P4].
- **Tasks are read at the home.** Tasks, assignments and steps live at the task home (`seon.task/agent-task`,
  `my.task` reads through `task/home-connection` [V]); a branch agent's context reads its task from
  `(db/db (task/home-connection conn))`, its inbox and history from its own branch.
- **History units.** `history-units` reads the agent's `[agent-ordinal id]` pairs with origin `:model` (one indexed
  query) and, per evaluation, `seon.cache/lookup` kind `::eval-unit` keyed by `[eval-id profile-digest
  renderer-symbol printer-digest]`. A miss pulls the unit's stored scalar fields (source, comment, ns name, shown,
  error, output, the stored renderer SYMBOL, timing), declared once as `:seon.cluster.eval/history-unit`, and renders
  them with `repl/render-ai` over that value; the render resolves no refs (today `repl/entity-emission` resolves the ns
  and renderer refs and runs a turn query [V]; both go). Pending code evidence is not in the unit. An unrelated reload
  reuses every unit; only a change to the printer or that renderer misses.
- **Live reads** use `seon.health/derived`, keyed by the derivation name plus the revisions of the attributes it read;
  every non-database input is in the name (the inbox key carries `answered-t`).
- **Not used**: `render.web/derive-context!`, `render/shared-cache`, `render.walk/history`, the system turn. They stay
  loadable with their dependency closure (§5); the new loop calls none of them and they get no new tests.

Cost per pass, unchanged agent with N evaluations: one pull, one ordinal query (O(N) ids), `sort` O(N log N), N cache
hits at ~150-220 ns, the system message and two `derived` hits, pricing and composition linear in the selected text.
Target < 10 ms warm at N = 600 [U: P4; history costs 3.6 s cold today].

**Reconstruction.** The call stores `basis-t` and `characters`, never the prompt. `context` over `(d/as-of db basis-t)`
rebuilds the sent text exactly only while render code, profile, settings, calibration and selection policy are
unchanged and history retains the basis; T5 compares full text in a fixture.

**The owner reads examples first.** Before anything is sent to an agent, complete example outputs of the system message
and task context, produced by the installed functions, are written to files and read by the owner
(`docs/research/agent-platform/2026-10-07/context-examples/`, regenerated after each change to a producer).

## 4. Completion is test-based

A task's success is its tests. The mechanism on the existing task API (`seon.task`, `my.task`, `seon.task.edn` [V]):

- **Admission.** A task names its success tests before it is assigned or launched: `seon.task/assign!` refuses a task
  whose proofs (deftests naming it with `:seon/tasks`; `seon.task/task-edges` `:proofs`, the union of home and branch
  edges) are empty (`:no-proofs`, already a refusal value [V]). Today `assign!` does not check this [V].
- **Running the proofs.** After a committed pass whose program facts changed a definition the task's proofs reach
  (`seon.test` reach selection over the changed definitions), the loop runs those proofs on the agent's branch for its
  current program (`seon.test/run` `:named`, origin `:test`), the check `my.program/propose-merge!` runs today,
  extracted once into `seon.task/proven`. A pass that changed nothing they reach runs nothing.
- **All green is completion.** Through the task writer, at the task home: a completion row naming the assignment, the
  tested branch and commit, the run id, the program and the proofs that passed; `seon.task/task-resolution` derives
  closed (`:seon.task/closed-by :completion`) from it, so the ordinary task view shows the task done. In the same home
  transaction one message to the assigner (root assigns; root's home is the task home). Disposition `:completed`. The
  assignment basis is validated at the writer: a completion against a superseded assignment or a changed task refuses.
  **Unresolved P5 seam:** the completion/message live at the task home, while disposition lives at the agent's
  home (§1). These can be different branches. Specify idempotent recovery between those two writes before P5 lands;
  a crash after task completion must not leave `work` continuing a completed assignment indefinitely. Prove that
  window with a fixture, including repeated completion delivery.
- **Bail.** `my.task/complete!` is ONLY the agent's explicit way to give back a task that is broken or impossible: it
  takes a required reason, ends the assignment, records the reason and sends one message to the assigner. It records no
  success. Disposition `:returned`.
- **Disposition** otherwise: `:working` by default; `:wait` when a form returns `my.turn/wait`'s value, until the next
  opening wake. A new opening wake answers again whatever the disposition. `my.turn/complete` and its reply delivery
  retire at the cut. Merge proposals stay root's separate flow.
- **Evidence from the live run**: task 18c37812b5bc's proof was green on HEAD while the task stayed open, and none of
  the bucket-D tasks examined had a red proof; open tasks whose proofs are all green are a finding (task
  `seon.task/open-tasks-with-all-proofs-green-are-not-flagged`).

## 5. Deleted, kept unused, converted

Deleted after the cut: turn run opening/closing, `next-id`, `plan-call`/`source-rows`/`plan-tx`, submission open;
`recover-tx`/`recover-call`; `next-agent-work`, episode/turns-left, `opening-deferred?`, `unanswered-*`,
`latest-answering-turn-t`, `continuing-reply?` (`outside-wake-t` moves); fault windows, `settle-batch-refusal!`; attempt
helpers, `fold-*`, `evaluation-request`; `open-turn`, `await-turn-permit!`, `call-turn`; `resume-turn`, `close-turn`,
`episode-exhausted!`, `close-escaped-run!`, `turn`, completion backstop, permit, `agent-dial`, write-refusal park,
`step`; context capture and contributions (`seon.context`); `capture-mismatch` and turn-joined calibration
(`seon.cluster.prompt`); permit, backstop and `record-interruption!` (`seon.cluster.agent`); the turn `changed?` query
(`seon.repl`). About 3,500 lines out, 550 in.

Kept loadable, unused and untested until the context design closes, WITH their transitive dependency closure:
`system-turn` (it calls `open-for-agent` in its writer admission, so `open-for-agent` stays), `declared-sources`,
`latest-evaluations`, `system-plan`, `generated-read-fault`, `record-evaluated-*`, `generate-turn` (with
`append-generated-*`), `compact!`, `render.walk/history`, `render.web/derive-context!`. The deletion slice computes the
closure from clj-kondo analysis; the schema keys it reads stay installed. `seon.eval.drive` has a production caller
(`seon.cluster.agent/run-observation` resolves `run-receipts` [V]) and two test callers: the cut converts the MCP
observation to the reply's evaluation ids; drive's remainder is a deletion candidate.

Converted callers in the cut: `render/transcript.clj` (group by `pass`, order by `agent-ordinal`); `cluster/agent.clj`
(transform, armer, `submit-source!`/`evaluate-source!` reply without `:seon.turn/id`, `virtual-turn!` = a request with
a fixture `:seon.ai/send!`); `eval/drive.clj`; `render/web.clj` (debug context wiring awaits the approved context design);
`bootstrap.clj` (the generated opening becomes a message to root); and `cluster/status.clj`, `context.clj`,
`sci/eval.clj`, `error.clj`, `cluster.clj` (boot `recover-tx` goes), `problems.clj`, `repl.clj`, `render.clj`, `mcp.clj`,
`effect.clj`, `prompt.clj`, `message.clj`, `test.clj`, `my/program.clj`, `turn/world.clj`, `walk.clj`, `fault.clj`,
`wake.clj`, `eval.clj` (`eval/of-agent` orders by `[agent agent-ordinal]`).

## 6. Slices

Preparatory slices are invisible unless labeled; each lands through `bin/lane commit LANE -m … --test NS… PATHS`, HEAD
boots after each, and each is dual-reviewed. Then ONE dependency-complete cut, proven by publishing and adopting it on a
branch of the live store before it lands. Order with errors: §8.9.

- **P0 Schema accretion.** §1's agent, evaluation and call attributes (context-inert), `run` optional on the receipt and
  `:seon.eval/entity`, the origin enum, the completion row, `:seon.agent.loop/unsendable-context-error`; published and
  adopted on a live-store branch. References: `malli/src/malli/registry.cljc:17-96`;
  `datahike/src/datahike/db/transaction.cljc:1225-1234`.
- **P1 Shared settlement owner.** First extract the schema-derived row, namespace and read-evidence calculations
  without changing the old writer; then generalize `settle-pass!` and `record-call` and convert every settle-stage
  caller in one loadable slice, keeping the old duties (§2.1). The old loop also writes `agent`, `agent-ordinal`,
  `pass`, `origin` (unread until P6). Tests: `turn-test/a-settlement-that-throws-still-shows-each-evaluated-value`,
  `…/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`, `run-test/item-16-the-reply-is-one-key-set`,
  `cluster.host-test/a-hosted-run-lands-on-its-branch-and-the-parent-head-is-unchanged`, the MCP run observation
  unchanged, plus T11, T16. References: `datahike/src/datahike/api/impl.cljc:30-42`;
  `datahike/src/datahike/db/transaction.cljc:1172-1173`.
- **P2 Pass identity.** `evaluate-sources` takes a pass (id, agent, origin), the acquired context with its diagnostic,
  and an evaluate stage value; the old loop's pass id is its run id, so ids and handles are unchanged. Lands after the
  errors cut (it touches `refusal/world-reference`, errors-owned until then). Test: existing handles unchanged.
  References: `sci/src/sci/core.cljc:260-271,334-340`; `nrepl/src/clojure/nrepl/middleware/interruptible_eval.clj:72-125`.
- **P3 Calls.** `call-tx` pure; `record-attempt!` writes `agent`, `basis-t`, `characters`; one parameterized
  calibration calculation extracted. The active calibration acquisition stays until the cut (no backfill of old
  attempts); the new query runs only on new-pass data. References: `langchain4clj/src/langchain4clj/core.clj:249-262`.
- **P4 The loop namespace, unwired, in three sequential steps in `seon.agent.loop`.** (a) `work`, `admission`,
  `outside-wake-t` → `wake/wake-ts` with its caller converted (T1, T9); (b) context assembly is blocked on the owner's redesign;
  revise T4, T5, T13 and T21 with it; (c) `pass` and `pass!`, the body
  callable once from the REPL (T3, T10, T15, T19, T20). Step (c) also depends on P5
  (`complete-if-proven!` needs the extracted proof/completion owner), so land P5 before P4(c).
  Depends on the errors cut (`caught`, the constructor, receipts).
  Then root's one owner-authorized paid pass on a disposable branch agent (overlay `no-provider false`; the agent
  overlay cannot lift it today, task `seon.config/no-provider-cannot-be-lifted-per-agent`; model `deepseek-flash` as
  configured on default, endpoint verified from config before the call): check the call, the evaluations,
  `answered-t`, `disposition`, and exact rebuilt text. References: `core.async/src/main/clojure/clojure/core/async/flow.clj:165-200,291-305`.
- **P5 Task completion (additive feature).** Admission `:no-proofs` in `assign!`; `seon.task/proven` extracted from
  `propose-merge!`; the completion row through the task writer and its `task-resolution` consumer; `my.task/complete!`
  as bail. The old loop's `my.turn/complete` still works. Tests: T7, T17, T18. References:
  `kaocha/src/kaocha/testable.clj:105-137`; `clojure/src/clj/clojure/test.clj:708-736`.
- **P6 Reader preparation, unwired.** Prepare transcript grouping by `pass`, `eval/of-agent`, status and the MCP
  observation against P1's new rows. Activate them only in the cut: older stored rows lack those attributes, so
  switching earlier silently drops their history and is not invisible. No backfill is planned; prove old readers
  remain active before the cut and new readers show all post-P1 rows with the existing transcript and MCP tests.
- **P7 Dial rename.** `max-episode-runs` → `:seon.config.agent/max-calls-per-wake`, with its defaults, stored facts and
  consumers; the shipped stop string removed. Published and adopted on a live-store branch.
- **Cut.** Together: P6's readers activate; the proc transform → `loop/pass`; armer `agents-to-arm` → `loop/work` (schedule-only arming
  kept); permit and backstop out of `arm!`, every `disarm!` caller passes its deadline; MCP SCI (`sci-run`) and JVM
  (`jvm-run`) and the test harness submit passes with origin `:outside`/`:test` (the direct JVM path's access to the
  agent's private SCI context is serialized through the same request port); bootstrap's opening becomes a message to
  root; every reader of §5; the agent schema's render attachment awaits the approved context design; disposition from §4 and
  `my.turn/complete` retired; `:seon.eval/origin` retired. Proven by publish+adopt on a live-store branch and T1-T22.
  Then root resets default once: evaluations and recorded errors are not migrated. References: all of P0-P7;
  `clerk/src/nextjournal/clerk/viewer.cljc:1470-1497`.
- **After the cut**: deletion of §5's spans minus the kept closure, with their tests, triaged by the commit that changed
  each behavior; the turn schema keys not in the closure retire.

## 7. Tests

New, in `test/seon/agent/loop_test.clj` (which already holds the two context-producer tests), one behavior each,
asserted on data, through injected worlds and a fixture `:seon.ai/send!` value (no paid call):

- **T1 work** (`are` table): an opening wake after `answered-t` is `:answer`; a schedule firing is not, but refills the
  cap; at the cap a fresh INSIDE wake gives nil and a fresh OUTSIDE wake gives `:answer`; with no outside wake the anchor
  is 0; an older entity with a newer transaction sets the anchor; `:completed`, `:returned` or `:wait` with no newer
  opening wake is nil.
- **T2 one pass**: reply `"(+ 1 2)"` gives one evaluation `{agent, agent-ordinal n+1, pass, origin :model, call}`, a call
  with `basis-t`, `answered-t` = that basis, all at one `:t`.
- **T3 failed call**: a fixture timeout records the call with its error, no evaluation, `answered-t` unchanged.
- **T4 cache**: two `context` calls with no write between miss no `::eval-unit`; after a pass, misses equal its forms; a
  write of `answered-t` alone changes the inbox; a profile change misses; an unrelated reload misses nothing; an
  evicted unit re-renders to the same text.
- **T5 rebuild**: `context` over `(d/as-of db basis-t)` equals the sent text in full.
- **T6 outside**: an MCP SCI run writes evaluations with origin `:outside`, no call, moves `current-ns`, not `answered-t`.
- **T7 completion**: a pass that changes code a red proof reaches runs it; red leaves the task open; a pass making it
  green records the completion, the ordinary task query shows it closed, root gets one message, the pass stores
  `:completed`.
- **T8 effects survive**: a pass interrupted after a form sent a message keeps the message and writes no evaluation rows;
  `work` is still `:answer`.
- **T9 no provider**: with no-provider set and no fixture, `work` is nil and the send leaf is never entered.
- **T10 terminal reply**: a request whose pass throws answers the error map; a stopped graph closes queued replies.
- **T11 unknown outcome**: a settlement answering outcome-unknown is not retried, writes no call-alone row and the agent
  is not re-armed before the outcome is known.
- **T12 repeated error**: a repeated failure puts its signature on the second evaluation with no new message.
- **T13 budget**: the whole request fits the budget; zero remainder keeps no history unit; an oversized newest unit is
  dropped with a priced elision; oversize mandatory parts are the unsendable error.
- **T14 completion target**: a message arriving mid-work does not change whom the completion notifies.
- **T15 context failure**: a context that throws records a call with its error and counts against the cap.
- **T16 handles**: handles minted during evaluation equal the ids stored after concurrent outside and model settlements
  assign agent ordinals.
- **T17 admission**: assigning a task with no proofs refuses `:no-proofs`.
- **T18 bail**: `my.task/complete!` with a reason ends the assignment, records the reason, messages the assigner and
  records no completion.
- **T19 model pass**: a model pass with no reply port settles and rewakes.
- **T20 post-evaluation failure**: an installation failure after evaluation keeps every evaluated value beside the
  recorded error.
- **T21 history**: outside runs under the agent's id are not history units.
- **T22 no stop cut**: a reply containing `#:seon.repl` reaches the segmenter whole.

Kept and rehomed (assertions on turn shape converted): `turn-test/a-call-omitting-its-database-sees-the-forms-own-write`,
`cluster.host-test/an-unsettled-host-run-keeps-ownership-until-it-exits`, `cluster.turn-test/a-red-form-routes-to-its-
namespace-owner-and-the-fold-continues`, `mcp-entrance-test/an-sci-call-answers-what-the-agents-next-context-reads`,
`mcp-entrance-test/a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle`, `cluster.agent-test/disarm-drops-
the-route-before-closing-it`, `cluster.prompt-test/calibration-uses-the-agents-recent-attempts-and-config-prior`,
`run-test/item-18-…`, `item-19-…`. Deleted with their machinery after the cut: `turn_loop_test`, `turn_work_test`,
`turn_work_cost_test`, `turn_backstop_test`, `turn_boot_test`, `turn_continue_test`, `turn_disposition_test` (replaced
by T7), `context_capture_test`, `context_capture_history_test`, `loop_proof_test`, the turn-entity, episode, recovery and
system-turn deftests in `turn_test` and `cluster/turn_test`, and capture cases in `cluster/prompt_test`; per-test triage
by the commit that changed the behavior [U: exact list].

## 8. Owner decisions (2026-10-07)

1. No turn or run entities. Agent information on the agent; evaluations ref the agent and the call that produced them;
   `:seon.cluster.eval/origin` `[:enum :model :outside :test]` replaces `:author` and the old `:seon.eval/origin` string.
2. Evaluation identity = (pass id, position in that pass), minted BEFORE evaluation with the installed two-argument
   `id/evaluation`; handles never change. The agent-wide ordinal is assigned separately at the writer.
3. The existing settlement writer is generalized in place into one shared settlement owner taking a pass. The old loop
   uses it until the cut, with its old duties kept. No second writer.
4. Errors reach the agent as errors §6 rules: every occurrence on the evaluation or call where it happened, in the
   reply of the agent that caused it; the opening's one message goes to root. The temporal fault window goes.
5. Completion is test-based: a task names its success tests before assignment; after a pass that changed code they
   reach, the loop runs them; all green completes the task, the only success. `my.task/complete!` is only the agent's
   way to give back a broken or impossible task, with a reason.
6. Invisible preparatory slices, then ONE dependency-complete cut proven by publish+adopt on a live-store branch.
   Default is reset once at the cut; evaluations and recorded errors are not migrated.
7. The system-message plus task-generated-context composition was rejected on 2026-10-07; its replacement is owned
   by `docs/research/agent-platform/2026-10-07/context-design/`. Context-dependent slice and test requirements remain
   blocked on that decision. Results are runtime only.
8. Crash loses the in-flight pass's bookkeeping, not completed effects. A failed call does not advance `answered-t`; the
   cap bounds retries. Exactly three policy bounds (provider timeout, SCI evaluation limit, calls per outside wake); the
   terminal answer of every MCP request and interrupt+join ownership are kept, not dials. Old context-selection code
   stays loadable with its dependency closure, untested.
9. Sequencing: loop P0-P1 → errors S1b, P1-P4 → errors cut → loop P2-P3, P4(a), P5 → approved context P4(b) → P4(c), P6-P7 →
   loop cut → deletion; files are claimed per slice through `bin/lane`.
10. Live runs use DeepSeek's flash model as configured on default; paid calls are deliberate and owner-approved.
