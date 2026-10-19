# Context: the existing messaging system (2026-10-13)

Read-only research. Code cited by fully qualified symbol. Anything not directly read in code is marked (inferred).

## (A) READ THIS, in order

1. `seon.cluster.message/send!`: the live sender path. Prepares against the sender's database, `offer!`s the message into the recipient's `:seon.agent/inbox` channel, writes only a declared sender copy (`:my.message/keep?`). Why: this is what M16 would replace.
2. `seon.cluster.agent/mailbox-step` and `seon.cluster.agent/receive-message!`: the recipient writes the received message on its own connection, once by id, from its inbox channel. Why: this is P7 as landed; the recipient-owns-its-write rule lives here.
3. `seon.cluster.message/delivery` and `seon.turn/delivery-rows`: the completed-turn and `my.message/send`-in-eval settlement path. Writes `:seon.message/rows` into the SENDER's turn transaction (see section C). Why: this is a second send path that M16 must reconcile, not a dead one.
4. `seon.cluster.wake/route!` (and `wake/delivery`, `wake/deliver!`): a `:seon.message/to` datom wakes the recipient's mailbox channel if it has a routing entry on that branch, else `db<->flow`'s plan channel. Why: the wake, not the message, is what reaches a recipient; the message is data read from its own branch.
5. `seon.turn/unanswered-wakes` and `seon.turn/unanswered-triggers`: answeredness is derived from `:t` against `seon.turn/latest-answering-turn-t`; nothing is stored. Why: this is how "unread-as-work" is decided for turns.
6. `seon.cluster.message/inbox-port`: walks the whole registry tree to find a recipient's inbox channel per send. Why: it is the only place a sender reaches another agent's channel.
7. `seon.cluster.message/inbound-tx` and `seon.render.web/inbound`: the human/web entrance (no `:seon.message/from`). Why: the only existing way an outside party addresses an agent.
8. `seon.render.web/received-response` and `seon.cluster.message/received!` (with `received-call`): acknowledgement; unread = addressed to me without `:seon.message/received-tx`. Why: the inbox's definition of unread.
9. `seon.cluster.message/message-view`, `seon.cluster.message/render-ai`, `seon.cluster.message/render-inbox-ai`: the agent-facing faces. Why: what an agent sees.
10. `seon.cluster.agent/db<->flow` and `seon.cluster.agent/run-pass!`: the root reactor. Currently derives start and lifecycle; it does NOT read `:seon.message/*` rows or offer any message (verified by grep: no `seon.agent/inbox` use in the pass). Why: M16 would add the record-on-main-then-offer step here.
11. Docs: `docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md#messages-u5-absorbed` (the U5 design, absorbed into M16) and `#6-phases-about-fifteen-minutes-each-head-boots-after-every-one-every-deletion-in-the-commit-converting-its-last-caller-each-proof-red-before-its-phase-cost-through-seon-profile-measure` (phase list, M16 row at the `M16 |` line).
12. Docs: `docs/research/agent-platform/2026-10-12/sol-p3-p7.md` (the P3/P7 landing note: "The simpler composition is core.async's existing nonblocking offer and the existing writer").
13. Docs: `docs/seon/plan.md` section 0, "Owner decisions, 2026-10-13 late" (the ordering: I1 stopped, then M13, then M16).
14. Docs: `docs/seon/schedule/cards/TASK-CHAIN.md#4-off-the-shelf-templates` (I1 row, and the I1 slice text).
15. Commits: `c1e0c97c9` (Offer messages through recipient inboxes; this is the change that made send! an offer), `b69403e7b` (P7: recipient writes the received message on its own branch), `f540268a1` and `a9feb9ba4` (task end as one message, id = occurrence; end message is the merge request), `d98ab13ed` (resume by hosting on demand; message to a stopped agent), `cf493d63d` (refused wake is a recorded value), `89629b13c` (unread = no acknowledgement), `fdd9926e8` (superseded: messages to recipient's home branch).
16. Rule: `CLAUDE.md` "Agents transact facts; root reacts" (see section 5 below for the quote and verdict).

## (B) Function map

### src/seon/cluster/message.clj

| Symbol | What it does | Callers (rg, fully qualified and alias) |
|---|---|---|
| `seon.cluster.message/trigger` | The message the run `run-id` answers, from its `:seon.turn/trigger` ref. | `seon.turn/trigger` ref-only; caller `seon.cluster.message/prepared-message`; `seon.turn` aliases `message/trigger` at line ~4595. |
| `seon.cluster.message/caused-by` (private) | The parent message id of a message via `:seon.message/caused-by`. | `seon.cluster.message/chain-depth`. |
| `seon.cluster.message/chain-depth` | Hops from outside the population, derived by walking `caused-by`; `seen` set makes it total. | `seon.cluster.message/delivery`; tests `message_test`, `turn_test`. |
| `seon.cluster.message/sender` | The sending agent id of a message, nil if outside. | `seon.turn/settle` path (`message/sender` at turn.clj ~2864); `seon.cluster.message/reply`. |
| `seon.cluster.message/reply` | The reply a completed run owes its asker, nil if the trigger is a reply to us or outside. | `seon.turn/asked-value` (aliased `message/reply`). |
| `seon.cluster.message/agent-exists?` (private) | Whether an agent id exists in a db value. | `inbound-tx`, `delivery`. |
| `seon.cluster.message/inbound-tx` | Admits one outside message: checks recipient exists, text non-blank, size bound; returns the tx-data vector or a flat error. | `seon.render.web/inbound` (tx `[:db.fn/call #'message/inbound-tx]`), `seon.eval.drive` (lines ~64, 71), tests. |
| `seon.cluster.message/delivery` | Turns candidates into message rows (chain-depth, unknown recipient, no-limit, chain-limit checks). Returns `{:seon.message/rows :seon.error/values}`. | `seon.turn/delivery-rows`; `seon.cluster.message/prepared-message`; tests `my/message_test`. |
| `seon.cluster.message/agent-reference-id` (private) | Resolves a ref (agent id map, entity id, lookup ref) to an agent id. | `format-ai`, `message-view`, `render-html`, `render-inbox-ai`. |
| `seon.cluster.message/data-link` (private) | A hiccup link to the `data` route for an identity. | `render-html`. |
| `seon.cluster.message/message-instant` (private) | The tx instant of a stored message (nil if unstored). | `message-view`, `render-html`, `message-order`, `listing-entry`. |
| `seon.cluster.message/format-ai` | One message as a sentence "Agent X said to Y: content". | `seon.render.transcript` (line ~360); tests. |
| `seon.cluster.message/received-tx` (private) | The acknowledging tx of a message: held value, else stored fact, else nil. | `message-view`, `render-html`. |
| `seon.cluster.message/message-view` | One message as tight data from a viewer's side (recipient gets `:seon.render/operations [(my.message/received! id)]` if unacked). | `render-ai`. |
| `seon.cluster.message/render-ai` | AI face of a message: `message-view` for the viewer. | Schema `:seon.message/message` `:seon.render/ai`. |
| `seon.cluster.message/render-html` | HTML face of a message. | Schema `:seon.render/html`. |
| `seon.cluster.message/message-order` (private) | Sort key: instant, then id. | `inbox-html`, `render-inbox-html`, `render-inbox-ai`. |
| `seon.cluster.message/inbox-form` | The read form `(my.message/inbox {:seon.agent/id ...})` for a recipient. | Schema `:seon.render/form` on `:seon.message/to`. |
| `seon.cluster.message/render-inbox-ai` | Inbox as data: for a recipient ref it reads `inbox*`; for a sequence of maps it sorts and splits unread/received. Two input shapes. | Schema `:seon.render/ai` on `:seon.message/to`; tests. |
| `seon.cluster.message/inbox-html` | Hiccup list of messages addressed to an agent. | `render-inbox-html`. |
| `seon.cluster.message/received-button` (private) | The "received" form that posts `action=received` to the agent context route. | `render-inbox-html`. |
| `seon.cluster.message/render-inbox-html` | Inbox HTML for a recipient ref (unread via `inbox-message-eids`) or a message sequence. | Schema `:seon.render/html` on `:seon.message/to`. |
| `seon.cluster.message/message-selector` (private) | The pull selector for a message with its refs and task members. | `read`, `inbox*`. |
| `seon.cluster.message/endpoint-id`, `admitted-message`, `listing-entry` (private) | Shape converters: pulled message to ref-normalized message; to listing entry (`my.message/id`, `my.message/at`, `my.message/content`, `received!` op). | `read`, `inbox*`. |
| `seon.cluster.message/recipient-eid`, `inbox-message-eids` (private) | Entity id of an agent; entity ids of messages to it not acknowledged (Datalog `not`). | `render-inbox-html`, `inbox*`. |
| `seon.cluster.message/inbox*` (private) | Unread messages for an agent as listing entries, optionally `since` a basis. | `inbox`, `render-inbox-ai`. |
| `seon.cluster.message/inbox` | Public inbox read, arities for request map, `(db agent-id)`, and options. | `my.message/inbox`; `my.root` line ~149 (aliased `message/inbox`). |
| `seon.cluster.message/read` | One message by id, or a not-found error. | `my.message/read`; `transcript` (by symbol in sentences only). |
| `seon.cluster.message/received-call` (private) | Tx-data to acknowledge a message, empty if already acknowledged. | `received!` (inside `[:db.fn/call]`). |
| `seon.cluster.message/received!` | Acknowledge a message on a connection, returning it with `:seon.message/received-tx`. | `my.message/received!`; `seon.render.web/received-response`. |
| `seon.cluster.message/send-value` (private) | Validates recipient, content, about; returns a candidate message value or a flat error. | `send`, `candidate`. |
| `seon.cluster.message/send` | Builds a message value (`to content [about]`). Pure, no write. | Not called from src (only tests and the `my.message` doc). Callers: `test/my/message_test`. |
| `seon.cluster.message/prepared-message` (private) | Admits a candidate against the sender's db: reads max-chain, the sender's current turn trigger, runs `delivery`. | `send-call` (3-arity), `send!`. |
| `seon.cluster.message/send-call` | 2-arity: writes a message once by id. 3-arity: prepares a candidate then writes it (throws its error). | `seon.cluster.agent/receive-message!` (`[:db.fn/call #'message/send-call value]`); `seon.turn` settlement (`delivery` rows, not send-call); tests `merge_test`, `db_flow_test`. |
| `seon.cluster.message/candidate` | Candidate message from a request: send-value plus `:seon.message/id`, assignment, reason, task end and merge. | `my.message` (not directly); tests `merge_test`, `db_flow_test`. |
| `seon.cluster.message/inbox-port` (private) | The recipient's `:seon.agent/inbox` channel found by walking the whole registry tree (`tree-seq`) from root. | `send!`. |
| `seon.cluster.message/undeliverable` (private) | The `:seon.message/undeliverable` error value with recipient, intent (from `task/ready-rules` execution query), cause. | `send!`. |
| `seon.cluster.message/send!` | The live sender path: candidate, prepare against sender db, `offer!` to recipient inbox, optionally keep a sender copy. Returns `{:seon.message/id ... :seon.message/accepted true}` or an error value. | `my.message/send` (`(message/send! request connection agent-id)`). |
| `seon.cluster.message/decline` | Builds a declination map (`to assignment reason`) with validation. | Callers: only `test/my/message_test` (no `my.message/decline` exists any more; see section G). |

### src/my/message.clj (the agent verbs; all forward to seon.cluster.message)

| Symbol | Does | Caller |
|---|---|---|
| `my.message/inbox` | Forwards `(message/inbox request)`. | Agent evals; `script/seon/dev/context_corpus.clj`. |
| `my.message/read` | Forwards `(message/read id db)`. | Agent evals; transcript sentence. |
| `my.message/received!` | Forwards `(message/received! id connection agent)`. | Agent evals; `seon.cluster.message/listing-entry` operation; web button. |
| `my.message/send` | Forwards `(message/send! request connection agent-id)`. The one agent send verb. | Agent evals (call preparation supplies `:seon.db/connection`, `:seon.agent/id`, and `:seon.agent/routing` from `my.program` environment, inferred from `my.program` line ~433 `select-keys ... [:seon.store/store :seon.agent/routing]`). |

### src/seon/cluster/agent.clj (message parts only)

| Symbol | Does | Callers |
|---|---|---|
| `seon.cluster.agent/receive-message!` (private) | Writes a received message once on the mailbox's own connection (`db/with-branch` around `[:db.fn/call #'message/send-call value]`); returns whether new. | `mailbox-step`. |
| `seon.cluster.agent/mailbox-step` | The mailbox Flow proc. On `:seon.agent/inbox` value: `receive-message!` and emits nothing downstream. On the wake channel: counts deliveries and emits one payload-free `:seon.agent/episode`. Closes inbox on stop. | Built by `seon.cluster.agent` proc constructors (`:io` workload). |
| `seon.cluster.agent/db<->flow` | The root reactor proc. Primes at resume; on wake runs `run-pass!`. Does not touch messages. | Flow graph; `seon.cluster.wake/route!` offers to `:seon.flow/plan-channel`. |
| `seon.cluster.agent/run-pass!` | Lifecycle derivations then starts unstarted agents. | `db<->flow`. |
| `seon.cluster.agent/agents-to-start` (private) | Unstarted agents with schedule owners or `turn/more-agent-work?`. | `run-pass!`. |
| `seon.cluster.agent/start-flow!` | Starts an agent's graph and offers its start prime. Creates the `:seon.agent/inbox` channel (`async/chan (:seon.config.agent/inbox-size settings)`) and registers it in routing. | `db<->flow`, `host`, `cluster`. |
| `seon.cluster.agent/channels` | Map eid to mailbox channel for `wake/route!`. | `branch` route registration. |
| `seon.cluster.agent/fenced-route?` | Whether a routed mailbox is closed by the terminal fence. | `route!` `:seon.cluster.wake/fenced?`. |

Note: `seon.cluster.agent/mailbox-step` is documented as the mailbox "forward one payload-free wake downstream" and receives messages on the same proc; the message value is never forwarded.

### src/seon/cluster/wake.clj (message-relevant)

| Symbol | Does | Callers |
|---|---|---|
| `seon.cluster.wake/wake-attributes` | Attributes with `:seon.wake/listen true`. Includes `:seon.message/to`. | `wake-matchers`, `seon.turn` (`wake-attribute-set`). |
| `seon.cluster.wake/turn-opening-attributes` | Listened attributes with `:seon.wake/opens-turn? true`. `:seon.message/to` is one. | `seon.turn` (`:opening` bind). |
| `seon.cluster.wake/inside-attributes` | Attributes whose presence marks a wake inside (`:seon.message/from`). | `seon.turn` (turn bound). |
| `seon.cluster.wake/agent-wake-datoms` | One `:avet` seek per attribute for an agent's wake datoms, newest first. | `seon.turn/unanswered-wakes`. |
| `seon.cluster.wake/delivery` | Classifies `offer!` results: delivered / fenced / refused. | `deliver!`. |
| `seon.cluster.wake/deliver!` (private) | `offer!` one payload-free wake; refusal becomes a diagnostic. | `route!` callback. |
| `seon.cluster.wake/record-refusal!` (private) | Records a refused hand-off through the connection's listener-failure recorder. | `route!` callback. |
| `seon.cluster.wake/wake-matchers` (private) | Compiles attribute to matcher: schema recipients (`:seon.message/to` value is the recipient) and runtime listens. | `route!` callback, per report. |
| `seon.cluster.wake/route!` | Registers the per-branch `d/listen` callback: for each changed datom with a matcher, offers a wake to the agent's mailbox channel, else to `plan-channel`. Also offers the render channel and the db<->flow start wake. | `seon.cluster.agent` branch route (`branch-route` setup, line ~1806). |
| `seon.cluster.wake/unlisten!` | Removes the route. | `agent` stop and branch retirement. |

### src/seon/turn.clj (message consumers, for the reader)

| Symbol | Does |
|---|---|
| `seon.turn/unanswered-wakes` | Agent's wakes not covered by an answering turn `:t`. Message wakes are these with `:seon.wake/attribute :seon.message/to`. |
| `seon.turn/unanswered-triggers` | Projection of `unanswered-wakes` onto message wakes. |
| `seon.turn/asked-value` | Builds the reply message value from a completed evaluation (`message/reply`). |
| `seon.turn/delivery-rows` | Calls `message/delivery`, returns `:seon.message/rows` and recording transactions for refused deliveries. |
| `seon.turn/settle` (settlement tx assembly) | Transacts `:seon.message/rows` on the TURN's own connection (the sender's branch), in the same tx as the turn's facts. |

### src/seon/render/web.clj (message parts)

| Symbol | Does | Caller |
|---|---|---|
| `seon.render.web/inbound-tx-meta` (private) | Tx meta: process id, and `:seon.db/user` only if the user id names an agent. | `inbound`. |
| `seon.render.web/inbound` | Runs `message/inbound-tx` as a decision, then writes `[:db.fn/call #'message/inbound-tx request]` through `write!` on the service connection. 204 on success, 422 on refusal, 500 if completion unavailable. | `inbound-response`. |
| `seon.render.web/inbound-response` (private) | Reads `content` form param, calls `inbound` with the agent id from the path. | Route `::route/agent-message` (`POST /agent/:id/message`). |
| `seon.render.web/received-response` (private) | `message/received!` for the path agent and the `message-id` form param; 204 or 422. | `context-response` (`action=received`). |
| `seon.render.web/context-response` (private) | Dispatches `action` `create-owner` or `received`; else 404. | Route `::route/agent-context`. |
| `seon.render.web/message-bar-html` | The human message form (`POST` to the agent message route, `name=content`). | Agent page render. |

## (C) Data and branch writes

### Schema keys (`:seon.message/*`, resources/seon/schemas/seon.message.edn and my.message.edn)

- Core message: `:seon.message/id` (identity string, `:seon.program/row-schema`), `:seon.message/to` (ref to `:seon.agent/id`, `:seon.db/index`, `:seon.wake/listen true`, `:seon.wake/opens-turn? true`, the recipient edge), `:seon.message/from` (ref, `:seon.db/index`, `:seon.wake/inside true`: its presence makes the wake "inside"), `:seon.message/content` (string min 1), `:seon.message/caused-by` (ref to earlier message), `:seon.message/about` (token), `:seon.message/assignment` (eval id), `:seon.message/received-tx` (ref to the acknowledging tx; sweeps with that tx), `:my.message/reason`, `:seon.task/end`, `:seon.task/merge`.
- Schema map `:seon.message/message` carries `:seon.render/ai seon.cluster.message/render-ai`, `:seon.render/html seon.cluster.message/render-html`, `:seon.render/form seon.render.transcript/message-form`.
- Errors/values: `:seon.message/undeliverable` (recipient, intent, cause in `[:no-graph :closed :full]`), `:seon.message/unknown-recipient`, `:seon.message/chain-limit`, `:seon.message/no-limit`, `:seon.message/blank-content`, `:seon.message/content-too-large`, `:seon.message/inbound-request`, `:seon.message/delivery` (rows and errors), `:seon.message/accepted-result`, `:seon.message/reply-request`, `:seon.message/delivery-request` (declares `:seon.turn/id`, `:seon.cluster.eval/ordinal`; apparently unused by current `delivery`, which takes `my.message/value` and `seon.agent/id`; inferred stale).
- Agent-side: `:my.message/send-request` (to, id?, keep?, routing?, content, about?, assignment?, connection, agent id), `:my.message/message`, `:my.message/declination`, `:my.message/inbox-entry`, `:my.message/inbox`, `:my.message/inbox-request`, `:my.message/read-request`, `:my.message/keep?` (boolean: write sender copy).
- Wake declarations: `:seon.wake/listen`, `:seon.wake/opens-turn?`, `:seon.wake/inside` (resources/seon/schemas/seon.wake.edn; `:seon.wake/*` attributes listed by `seon.cluster.wake/wake-attributes` etc.).
- Config: `:seon.config.message/max-chain` (int min 1, dial); `:seon.config.agent/inbox-size` (buffer size of the inbox channel).

### Where each write lands

| Write | Code path | Branch written |
|---|---|---|
| Turn reply / `my.message/send` settled from eval | `seon.turn` settlement puts `:seon.message/rows` in the turn tx. | The SENDER's own branch (the turn's connection). |
| Sender's kept copy (`:my.message/keep? true`) | `seon.cluster.message/send!` keep branch `db/transact!` `[:db.fn/call send-call message]`. | SENDER's own branch. |
| Recipient's received message | `seon.cluster.agent/receive-message!` on the mailbox's `:seon.db/connection`. | RECIPIENT's own branch (its mailbox). |
| Acknowledgement `:seon.message/received-tx` | `seon.cluster.message/received!` on the caller's connection. | The reader's branch (agent eval or web button). |
| Outside inbound (human/web) | `seon.render.web/inbound` via `write!` on `:seon.store/connection-object`. | Whatever connection the web service holds (inferred: the cluster/main connection; not verified in this read). |
| Error recordings for refused deliveries | `seon.turn/delivery-rows` `error-tx`. | The SENDER's turn tx. |

Nothing in the current code writes a message to another agent's branch. `send!` reaches another agent only by an in-memory channel offer (see section 5).

### Recipient wake

1. A `:seon.message/to` datom is committed on some branch's connection.
2. `seon.cluster.wake/route!` (`d/listen` callback) matches it through `wake-matchers` (schema recipient = `(:v datom)`).
3. If the branch's channels map has a mailbox for that eid, it `offer!`s a payload-free wake to that mailbox (`::mailbox`). Otherwise it offers to the db<->flow plan channel.
4. `mailbox-step` gets the wake and emits `:seon.agent/episode`, which starts a turn.
5. The turn derives unanswered message wakes from `:t` (`seon.turn/unanswered-wakes`), not from a stored flag. Answeredness = a later answering turn's `:t`.
6. Inbox "unread" (for display) is separate: no `:seon.message/received-tx` on the message (`seon.cluster.message/inbox*`, `inbox-message-eids`).

Direct `send!` path, the message value itself travels in-memory: `offer!` into `:seon.agent/inbox`, then `receive-message!` on the recipient's connection. The wake and the message are two channels.

## (D) History timeline

Commits touching `src/seon/cluster/message.clj`, `src/my/message.clj`, `src/seon/cluster/wake.clj`: 102 total. Selected, newest first (from `git log` and `git show -s`):

| Commit | Date | What changed, why |
|---|---|---|
| `73968333e` | recent | One reference rule for prose; docstring citations are definition references (TASK-CHAIN A5). Docs only for message files. |
| `3410f6ca8` | recent | Chain task data (H2). Touches task refs, not message semantics. |
| `d0ebba656` | recent | Store branches and execution registries (CN-18): routing roster becomes a registry; `:seon.agent/routing` shape the send path reads. |
| `cde025f68` / `86430115b` | 10-13 | Held-task rule (M13) and settlement not writing an execution's base context; the send path is gated behind M13 per the task `:needs`. |
| `b69403e7b` | 10-12 | P7: the recipient's mailbox writes a received message on its own branch (3 files, +71/-8 incl. `agent.clj` +40). Patch from `docs/research/agent-platform/2026-10-12/sol-p3-p7-mailbox.patch`. |
| `c1e0c97c9` | 10-12 | Offer messages through recipient inboxes and keep sender copies only by request (10 files, +304/-115). `send!` changed from "open the recipient's branch and transact there" to "offer to inbox, write sender copy only with keep?". Carries `docs/research/agent-platform/2026-10-12/sol-p3-p7.md`. |
| `f540268a1` | 10-10 | A task's end reaches root once: end message id is the end occurrence; `send!` writes nothing for an id already written (Astra audit finding 6). |
| `a9feb9ba4` | 10-10 | A task's end is one message that, ended green, is the merge request (DYNAMIC-CONTEXT stage 3). Added `:seon.task/end`, `:seon.task/merge` on messages. |
| `d98ab13ed` | 10-10 | Resume by hosting on demand; message to a stopped agent resumes it (host tasks). Touches `seon.cluster.host` more than message.clj. |
| `e42d548aa` | 10-11 | Task verbs (owner Q16): deletes `my.message/decline` and other verbs (57 files, +243/-781). `seon.cluster.message/decline` survived with no src caller. |
| `cf493d63d` | 09-28 | Wake: refused delivery is a recorded value; route never removed. |
| `fdd9926e8` | 09-25 | A hosted agent is a cluster row; a message goes to its recipient's home branch (superseded by `c1e0c97c9`). |
| `af278535c` | 09-15 | "Make agent message sends write immediately" (pre-offer design; superseded). |
| `928461bcc` | 09-15 | `my.message` send and decline take one request map with connection and agent id. |
| `89629b13c` | 09-27 | Unread = no acknowledgement; message render is data from the viewer's side (message-ack-opus). Introduced `received!`. |
| `48cded8ba` | 09-27 | Toolkit gaps: `my.message/received!` added. |
| `5d95c4913` | 09-27 | Opening's inbox read is the unread messages, derived from acknowledgements. |
| `9938d46cd` | 09-27 | Inbox HTML shows unread with received! buttons (owner ruling: symmetric faces). |
| `c53c9306e` | 10-11 | Revert of the night's fences (AGENTS.md NO GUARDS OR FENCES). Relevant to any guard in the send path. |
| `f3ef50934` | 09-06 | Execute message and plan source with database custody. |

Earlier designs: `docs/research/agent-platform/2026-10-11/hybrid-agent-world.md` (AW0 to AW7, renamed from P0 to P7 on 2026-10-12; AW3 = "a send writes nothing unless it keeps", AW7 = inbox in-port) and `docs/research/agent-platform/2026-10-12/hybrid-done-and-merge.md` (U5 absorbed into M16). Searched `rg -l "M16|U5|P7|inbound|caused-by" docs/research docs/seon`: about 50 files; the ones that matter are those two plus `docs/seon/specs/merge.md` and `docs/research/agent-platform/2026-10-12/plan-chains-opus.md`.

## (E) Planned vs existing

| Planned item | Source | Existing code that already does part | Genuinely missing | Verdict |
|---|---|---|---|---|
| AW3 / `seon.cluster.agent`-era "a send writes nothing unless it keeps" (task `a-send-writes-nothing-unless-it-keeps`) | `resources/seon/tasks/seon.cluster.message.edn` | `seon.cluster.message/send!` already offers to inbox, `:my.message/keep?` writes sender copy. Task text still says recipient branch lookup is "deleted"; that deletion is done. | Task file text is stale; no code missing. | Exists (task text stale; retire or re-mark). |
| P7 mailbox writes received message on own branch | `docs/research/agent-platform/2026-10-12/sol-p3-p7.md` | `seon.cluster.agent/receive-message!`, `mailbox-step`. | None found. | Exists. |
| M16 `seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record` (U5 absorbed) | `seon.task.edn`-adjacent `resources/seon/tasks/seon.cluster.message.edn`; `hybrid-done-and-merge.md` | The sender's write on its own branch: `seon.turn` settlement already writes `:seon.message/rows` on the sender's branch. The recipient offer: `seon.cluster.message/send!` offers directly to the inbox from the sender's proc. | (a) `seon.cluster.agent/db<->flow` does not read `:seon.message/to` rows or offer any message; (b) root must record the message on main by id before one offer; (c) `send!`'s direct offer must be removed (the sender reaches another agent); (d) undeliverable as data on main's entity. None of (a) to (d) exists. | Missing (the record-then-offer step). Partly duplicate: "sender writes on own branch" already happens in `seon.turn` settlement; the task's first sentence says `send!` writes it, which would duplicate the settlement write. |
| M16 red proof `an-undeliverable-message-is-answered-as-data` | same task | `seon.cluster.message/undeliverable` returns the value. | The main-entity record and the no-graph case through root. | Partly exists. |
| M16 `:needs` on `seon.turn/a-held-task-opens-no-model-turn` (M13) | task file | Commit `cde025f68` landed the held-task clause. | Not verified green. | Exists (dependency landed in code). |
| U5 (absorbed in M16) | `hybrid-done-and-merge.md#messages-u5-absorbed` | Same as M16. | Same. | Duplicate of M16 (single task; treat as one). |
| I1 `seon.task/chain-intake-replies` and TASK-CHAIN I1 (`my.message/send` to user persists a displayable question; web reply records `:seon.message/from` and `:seon.message/caused-by`; root db<->flow delivers to recipient's branch) | `resources/seon/tasks/seon.task.edn`; `docs/seon/schedule/cards/TASK-CHAIN.md#4-off-the-shelf-templates` | `seon.render.web/inbound` writes a message to the agent with no `from`. `seon.cluster.message/send!` with `caused-by` through `:seon.message/trigger` (settlement) records caused-by. Web reply button and inbox exist. | (a) an explicit `:my.message/caused-by` request key (planned as new request-only, resolved to stored ref; the derivation from `:seon.message/trigger` already produces `:seon.message/caused-by`); (b) web POST recording sender (`inbound-tx` has no `from`; `inbound-tx-meta` sets only `:seon.db/user`); (c) root db<->flow delivery to recipient's branch (same gap as M16). | Partly duplicate: caused-by is already derived, I1 adds a second route to the same ref. Real gaps: web sender and root delivery. |
| I1 user address ("sean") | TASK-CHAIN I1 | Users are not agents in code (no `seon.agent/id` for a human). `inbound-tx` refuses unknown recipients. | A user identity, or treat "sean" as an agent with its own branch. | Missing (design decision, not built). |
| I1 "sending to Sean uses the same durable agent-message path without requiring a model graph" | TASK-CHAIN I1 | `send!` returns `undeliverable :no-graph` when a recipient has no graph. | A durable path for a graphless recipient: M16's record-on-main then deliver. | Missing. Would duplicate the no-graph branch of `send!` unless it replaces it. |
| I2 `my.agent/answer!` reads I1's sender | TASK-CHAIN I2 | `:seon.message/from`, `caused-by`, `about` exist on messages. | `my.agent/answer!` and `:seon.task/answer-value`. | Missing (not messaging). |
| P3 "message prepared against sender's db, offered to roster's inbox" | `sol-p3-p7.md` | `send!`. | None. | Exists. |
| `my.message/decline` and `seon.cluster.message/decline` | e42d548aa deleted my.message/decline | `seon.cluster.message/decline` still exists. | Its only caller is a test. | Duplicate of deleted verb: candidate deletion. |

## (F) Open questions (not answered)

1. Which connection does `seon.render.web/inbound` write on (`:seon.store/connection-object`): main or a branch? Not traced to the service constructor.
2. Does the turn settlement's `:seon.message/rows` on the sender's branch ever reach a recipient without a merge? Inferred: no (recipient's wake is on the branch the wake route lives on, and the recipient's `inbox*` reads its own branch). Not reproduced.
3. `seon.cluster.message/send!` maps `:seon.message/unknown-recipient` to cause `:no-graph`; the `:seon.message/cause` enum has no unknown-recipient. Intended? (Looks like a mislabel.)
4. `seon.cluster.message/prepared-message` asserts exactly one `max-chain` value; `seon.cluster.message/delivery` also refuses a non-positive max-chain. Two checks of one dial.
5. `seon.turn` may use `:seon.message/delivery-request` (declared in seon.message.edn with `:seon.turn/id`, `:seon.cluster.eval/ordinal`); no current caller found. Inferred stale.
6. Whether the `:seon.message/to` wake still reaches a mailbox for a message settled on a branch where the recipient has a routing entry (the in-branch channel map is `seon.cluster.agent/channels`). Not executed.
7. Test status of M13 (`seon.turn/a-held-task-opens-no-model-turn`) at HEAD: not checked.

## (G) Overengineered paths

Items marked (inferred) are judgments from reading, not measured. "Lines removable" are rough estimates of body lines, not verified.

1. `seon.cluster.message/decline` (and its schema `:my.message/declination`, `:my.message/no-assignment`, `:my.message/no-reason`, `:my.message/no-recipient` used only there). Duplicate of a verb deleted in `e42d548aa`; only a test calls it. Simpler: delete. Rough: ~40 lines plus 4 schema keys.
2. `seon.cluster.message/inbox-port` walks the whole registry tree with `tree-seq` on every `send!`. Simpler: the routing registry's existing per-eid channel lookup (`seon.cluster.agent/channels` or `seon.cluster.wake` channels fn), which the wake route already uses. (inferred) Rough: ~8 lines, and removes a whole-tree walk per send.
3. Three checks that a recipient exists: `seon.cluster.message/inbound-tx` (`agent-exists?`), `seon.cluster.message/delivery` (`agent-exists?`), and `seon.cluster.message/prepared-message` (via `delivery`), plus `seon.render.web/inbound-tx-meta` (`agent-exists?`). Simpler: one check in `delivery` used by all callers. Rough: ~15 lines.
4. Three idempotence checks for one message id: `seon.cluster.message/send-call` (2-arity `db/q` existence), `seon.cluster.agent/receive-message!` (its own `db/q` existence before `send-call`, which checks again), and `seon.cluster.message/received!` plus `received-call` (read then re-read inside the tx). Simpler: `send-call` alone decides (it already returns `[]` when present); `receive-message!` drops its pre-check. (inferred) Rough: ~10 lines.
5. `seon.cluster.message/received!` reads the message before the transact and again inside `received-call`. Simpler: the in-tx check in `received-call` decides and the caller reads once after. (inferred) Rough: ~5 lines.
6. `seon.cluster.message/received-tx` has three branches (held map, held int, stored lookup) for one fact. Simpler: a single pull via `message-selector` at the caller. (inferred) Rough: ~12 lines.
7. Two unread derivations: `seon.cluster.message/inbox*` (Datalog `not` in `inbox-message-eids`) and `seon.cluster.message/render-inbox-html` (pulls unread through `inbox-message-eids` and re-sorts), while `render-inbox-ai` on a sequence re-derives "received?" by `contains? :seon.message/received-tx` on views. Simpler: one `inbox*`-style read reused by every face. Rough: ~30 lines.
8. Shape converters stacked: `seon.cluster.message/listing-entry` (builds `:my.message/*` keys), then `seon.cluster.message/render-inbox-ai` converts them to `:seon.message/lines` and `dissoc`s `:my.message/at`. A layer that only forwards and renames. Simpler: the listing entry already in the face's keys. (inferred) Rough: ~10 lines.
9. Overlapping message schemas: `:seon.message/message`, `:seon.message/pulled`, `:seon.message/unread-entry`, `:my.message/inbox-entry`, `:my.message/message`, `:my.message/send-request` repeat `to`, `content`, `from`, `id` shapes. Hand lists beside schemas (the CLAUDE.md "derive pulled shapes from schemas" rule). Simpler: derive selectors and entries from `:seon.message/message`. Rough: ~60 lines of schema text.
10. Unresolved-endpoint wording repeated in `seon.cluster.message/format-ai`, `seon.cluster.message/render-html` and `seon.cluster.message/message-view` ("An unresolved sender", "Unresolved sender", raw ref). Three renderers, one decision. Simpler: one endpoint function returning typed unknown; each face renders it. (inferred) Rough: ~25 lines.
11. `seon.cluster.message/agent-reference-id` has three resolution paths in one `cond` (agent-id map, `find` on map, `:db/id`, entity id) plus a database query. Simpler: normalize refs once at the reader (pull `[:seon.agent/id]`), then no resolution. (inferred) Rough: ~15 lines.
12. Two input shapes in one renderer: `seon.cluster.message/render-inbox-ai` and `seon.cluster.message/render-inbox-html` accept a recipient ref OR a message sequence, switching on `sequential?` and `every? map?`. Simpler: two functions, one per shape, each declared by its own schema (the comment says "one producer answers both" for a reason, but the cost is the branch). Rough: ~20 lines.
13. Per-message N+1 reads: `seon.cluster.message/message-order` and `listing-entry` call `message-instant` (one `db/q`) per message; `render-inbox-html` pulls message per id. Inbox of n messages costs n+ queries per read. Simpler: one pull with `:db/txInstant` via the tx of `:seon.message/to`, or one query returning instants. (inferred; cost not measured.)
14. `seon.cluster.wake/route!` callback recomputes `wake-matchers` (a `d/q` over all runtime listens) and `starting-attributes` on every report, with `wake-attributes` again inside `wake-matchers`. Simpler: the compiled matcher lives with the connection and is rebuilt only when `:seon.schema/*` or listen rows change (the docstring already says that). (inferred from code; the docstring claims 0.2 ms per report, which is measured evidence for the current form.) Rough: ~15 lines of recomputation avoided; cost to measure first.
15. `seon.cluster.wake/delivery` + `deliver!` + `record-refusal!`: three functions to turn an `offer!` result into a recorded diagnostic. Simpler: `offer!` result classified inline in `route!`. (inferred) Rough: ~25 lines.
16. `seon.render.web/inbound` runs `message/inbound-tx` once as a decision and again inside `write!` as `[:db.fn/call #'message/inbound-tx request]`. Two evaluations of one validation per POST. Simpler: the writer's refusal is the decision (the value returned from the transaction carries the error). (inferred) Rough: ~6 lines.
17. `seon.cluster.message/chain-depth` `seen` set is justified in its docstring as totality "in the one place whose job is to stop something running forever". Under the CLAUDE.md rule against guards, this is a fence-like check on a cycle that the docstring says cannot happen. Simpler: rely on the writer (a trigger is older than its transaction), or bound by `max-chain` (already the loop bound). (inferred; this is a judgment against the rule, not a bug.) Rough: ~3 lines.
18. `seon.cluster.message/prepared-message` and `seon.turn` settlement each run `delivery` for one candidate, and `seon.turn/asked-value` builds the reply value which `delivery-rows` then re-wraps (`:my.message/value`). Two wrapper layers for one value. (inferred) Rough: ~15 lines.
19. `seon.cluster.agent/db<->flow` `agents-to-start` computes unstarted agents with a whole-cluster query and runs `turn/more-agent-work?` per candidate on every pass. Whole-program derivation per wake. (inferred; cost not measured in this read.)
20. `seon.cluster.message/undeliverable` runs a `task/ready-rules` execution query to name an intent on every refused send. Only needed for the error value, which is the rare path. Acceptable, but it is the second-most expensive line in `send!`. (inferred)
21. The unknown-recipient branch in `seon.cluster.message/send!` maps to `:no-graph` (see section F item 3): a special case that loses the real cause. Simpler: a distinct cause, or refusal before `send!` ever reaches the offer. (inferred)

Summary of (G) by size: items 1, 3, 4, 7, 9, 12 carry most of the removable lines; items 2, 14, 19 carry most of the per-call cost (inferred).

## Verification limits

- Read: all of `src/seon/cluster/message.clj` (959 lines), `src/my/message.clj`, the message-related regions of `src/seon/cluster/agent.clj`, `src/seon/cluster/wake.clj` (the route and matcher section), `src/seon/render/web.clj` (inbound and received), `src/seon/turn.clj` (delivery and reply sections), the schema resources. Not executed. No REPL or tests run (read-only task).
- Not traced: web service connection branch, `my.program` environment supply of `:seon.agent/routing` (inferred), the full turn settlement writer.
