---
type: research
status: complete
tags: [research, agent-runtime, data-model]
---

# Work identity, messaging, and human users in fresh Seon

## Verdict

Fresh Seon should keep four facts distinct:

1. A **user** is a durable participant identified by `:seon.user/id`.
2. A **message** is a durable act of communication. It wakes an agent, records
   its sender when known, and may point `about` a durable work item.
3. A **work item** is durable intent: goal, parent, dependencies, owner, and
   semantic order. Completion, eligibility, progress, and current focus are
   derived from connections and terminal evidence, never stored as status
   labels or an active pointer.
4. An **instruction** is durable context policy, not a message and not a work
   item. The existing cluster/agent instruction connections already express
   that distinction.

The immediate missing seam is the human boundary. Today an outside task is a
message with no `from`; a completed human-triggered run creates no reply
message; the answer survives only inside the terminal eval receipt. The
maintained Inspect harness therefore injects an anonymous inbound message and
queries the receipt values for the last `my.run/complete` result. A first-class
user should become the sender of that message, transaction provenance should
point to the same entity, and the durable answer should remain the completion
receipt joined to the triggering message and run. A new user-addressed message
is unnecessary unless Seon decides that replies themselves must wake or feed a
user-owned consumer.

I read the named plan quarry and its only plan test end to end:
`src-old/my/plan.cljc`, every file under `src-old/my/plan/`, and
`test-old/my/plan_test.cljs`. I also read the fresh message, run, work, loop,
wake, schema, web-inbound, and maintained Inspect entry-point owners end to
end or through their complete owning functions. The quarry below supplies
lessons only; none of its implementation is proposed for restoration.

## Dependency and authority ledger

- Datahike is pinned at `0e8601d7f2f68c01070e13a95483bc82be04cabc`.
  A Datahike ref attribute is only typed as `:db.type/ref`; transaction input
  resolves its value to an entity id (`reference-code/datahike/src/datahike/db/transaction.cljc:793`).
  It does not constrain which identity attribute the referenced entity carries.
- Malli is pinned at `80138076960e7820523b4cb932c5b5d1936d4e7f`.
  Fresh Seon's Malli-to-Datahike bridge maps `:seon.db/ref` directly to
  `:db.type/ref` (`src/seon/schema/datahike.clj:115`).
- Core.async is pinned at `dc35f3e0d7bc2eef502e77982f48641f025c8051`.
  The relevant use is Seon's nonblocking, payload-free wake route, not a
  separate messaging queue (`src/seon/cluster/wake.clj:146-161`).
- Current target authorities are
  [agent runtime](docs/seon/architecture/agent-runtime.md) and
  [data model](docs/seon/architecture/data-model.md). Their governing laws are
  attribute-presence state (`docs/seon/architecture/agent-runtime.md:18-34`),
  ordered children carrying an ordinal rather than cardinality-many pretending
  to be ordered (`docs/seon/architecture/data-model.md:27-37`), and transaction
  provenance on the transaction entity (`docs/seon/architecture/data-model.md:126-136`).
- The active roadmap explicitly says that `my.plan` was never ported and that
  v0 planning currently works through messages
  (`docs/prds/sci-execution-runtime/plan/README.md:698-711`). The vocabulary
  authority reserves `my.plan` for the agent planning toolkit and distinguishes
  it from an execution plan (`AGENTS.md:642`).

## Part 1 — the plan quarry

### What the old model was

The old namespace accurately described its ambition: durable agent-scoped plan
facts forming a tree plus dependency graph, with readiness, blockage, progress,
and a bounded context view derived from those facts
(`src-old/my/plan.cljc:1-7`). Its stored step contained:

- identity, title, description, goal, expected outcome, and pace;
- owning agent, requester, triggering message, optional generated namespace,
  and an assignment claim;
- one `parent` ref for the tree and cardinality-many `needs` refs for the DAG;
- `created-at` and optional `completed-at`; and
- the stored enum `:open`, `:active`, `:done`, or `:blocked`
  (`src-old/my/plan.cljc:24-43`, `src-old/my/plan.cljc:325-342`).

Nested plan creation used recursive `children`; author labels and `after`
relationships compiled into parent and dependency refs
(`src-old/my/plan.cljc:95-124`). Reads reconstructed a forest and nested
subtrees from the flat rows (`src-old/my/plan/internal.cljc:187-231`).
Cardinality-many dependencies were treated as a set in the pure row helpers
(`src-old/my/plan/internal.cljc:72-83`), while presentation order was imposed by
the temporal `created-at` field (`src-old/my/plan/internal.cljc:149-161`,
`src-old/my/plan/internal.cljc:220-240`).

The Datalog rules defined unfinished work by stored status, then derived
descendants, leaves, dependency blockage, readiness, and the drained-parent
close action (`src-old/my/plan/internal.cljc:24-46`). Progress was derived as
done leaves over total leaves (`src-old/my/plan/internal.cljc:129-147`). The
current position preferred a stored active step, falling back to the oldest
ready leaf (`src-old/my/plan/internal.cljc:175-185`).

### Agent-facing surface

The ordinary agent toolkit was broad:

| Function | Old contract | Evidence |
|---|---|---|
| `step!` | add one open step with optional parent, dependencies, and expectation | `src-old/my/plan.cljc:849-905` |
| `plan!` | create one nested plan once, refusing same-title duplication | `src-old/my/plan.cljc:907-955` |
| `active!` | store the selected focus and demote previous active work | `src-old/my/plan.cljc:1325-1370` |
| `done!`, `blocked!`, `reopen!` | mutate the stored status and completion time | `src-old/my/plan.cljc:1389-1476` |
| `needs!`, `move!`, `drop!` | edit dependency, parent, or subtree relationships | `src-old/my/plan.cljc:1478-1569` |
| `reconcile!` | diff one edited whole-plan document by stable ids in one transaction | `src-old/my/plan.cljc:1571-1672` |
| `next`, `position` | return the oldest ready work or stored-active/ready focus | `src-old/my/plan.cljc:1674-1754` |
| `tree`, `document`, `status`, `list-open` | structural, editing, progress, and bounded-open reads | `src-old/my/plan.cljc:1756-1867` |

The namespace also accumulated non-toolkit system responsibilities: generated
namespace DAG compilation and publication, generated-root terminal delivery,
automatic stuck-work consultation, plan context rendering, and HTML rendering.
The internal namespace inventory makes that breadth visible
(`src-old/my/plan/internal.cljc:740-1023`,
`src-old/my/plan/internal.cljc:1075-1244`,
`src-old/my/plan/internal.cljc:1246-1801`,
`src-old/my/plan/internal.cljc:1826-2120`). Those are not part of a fresh
`my.plan` toolkit.

### What worked

1. **Relationships first.** Parent and dependency refs made hierarchy,
   descendants, readiness, and roll-up queryable. The tests exercised the same
   row set through readiness, progress, and tree projections
   (`test-old/my/plan_test.cljs:41-50`) and verified dependency blockage plus a
   drained parent's final ready action (`test-old/my/plan_test.cljs:1261-1286`).
2. **Pure derivations over immutable rows.** Cycle-safe descendants, ancestry,
   readiness, progress, and windowed projections were independently testable
   (`src-old/my/plan/internal.cljc:67-240`). This is directly reusable as a
   lesson even though the status-bearing input model is not.
3. **One whole-document reconciliation transaction.** Stable identity, explicit
   ambiguity refusal, done-item immunity, and one atomic diff gave replanning an
   honest data-shaped boundary (`src-old/my/plan.cljc:1571-1589`). Tests covered
   round-trip and no-write convergence (`test-old/my/plan_test.cljs:208-235`)
   plus identity and compiler refusal cases
   (`test-old/my/plan_test.cljs:1538-1654`).
4. **Bounded context instead of the whole tree.** The context selected one
   anchor, at most seven ready items, and five recent completions
   (`src-old/my/plan/internal.cljc:1246-1269`). This kept a large durable graph
   queryable without making every prompt scale with it.
5. **Measured teaching changed behavior.** Commit `952ea270ad2e` records the
   planning flagship green on both models with decompose-first and
   close-adjacent guidance; DeepSeek used four pre-restart and two resumed
   turns, Spark six and three, with zero replans. Commit `2bbaf29b1d81` records
   three of three whole-document authoring successes by one model but zero of
   two by the other, which honestly locates the remaining difficulty in
   one-large-form authoring rather than the data operation itself.

### What was abandoned, and why the evidence matters

- The entire namespace moved to `src-old` in commit `f25e3459443c`; that commit
  makes fresh `src/` the project and calls `src-old/` the quarry. The active
  roadmap independently confirms that `my.plan` was never ported
  (`docs/prds/sci-execution-runtime/plan/README.md:698-711`).
- Stored status was the central old control signal. The rule set, write API, and
  anchor all depend on it (`src-old/my/plan/internal.cljc:24-46`,
  `src-old/my/plan/internal.cljc:108-127`,
  `src-old/my/plan/internal.cljc:175-185`). Fresh law explicitly rejects a
  stored status that restates terminal facts
  (`docs/seon/architecture/data-model.md:22-37`).
- `active!` made current focus a mutable pointer encoded as a status. It was
  needed partly to correct prompt focus, and later had to be overridden by the
  current run's causal message in tests (`test-old/my/plan_test.cljs:1300-1397`).
  That is evidence that current work belongs to a derivation from unfinished
  work plus the active run trigger, not a second stored truth.
- Temporal creation/completion clocks were made into semantic ordering and
  bounded-recall inputs (`src-old/my/plan/internal.cljc:149-161`,
  `src-old/my/plan/internal.cljc:1299-1309`). Fresh work ordering needs an
  explicit ordinal; provenance clocks must not silently become priority.
- Message intake automatically created an open plan row with status and clock
  in the same old transaction (`src-old/seon/agent/message.cljc:329-386`). That
  collapsed “somebody said something” into “this is durable planned work” and
  made every human message a planning policy decision.
- Automatic replanning used a message-content marker and searched message text
  for it (`src-old/my/plan/internal.cljc:1379-1386`). Commit `8492b275b673`
  records a six-of-seven live success, but its mechanism is still a hidden text
  protocol and a system-side effect inside planner code. Fresh Seon should
  represent consultation intent and settlement as values, messages, and facts,
  not parse a marker out of prose.
- Generated-code publication, claims, namespace scheduling, automatic
  consultation, context bands, and HTML rendering turned the planning toolkit
  into a second runtime. Its breadth is visible in the internal functions cited
  above. Fresh Seon's run loop, render walk, program graph, and message owner
  already own those concerns.
- The old recursive plan and rendered-value schemas were closed
  (`src-old/my/plan.cljc:165-190`), conflicting with the current
  accretion-friendly open-map law.
- The one markdown-to-plan parser was explicitly deleted as “magic parse” in
  commit `99c5046bf1da`; the surviving update input was nested EDN. That is a
  useful precedent: work is data, not a prose grammar.

### Fresh equivalents

| Quarry mechanism | Fresh equivalent under current law |
|---|---|
| plan/step identity | one identity attribute on a work entity; no kind stamp |
| nested children | child points to parent; child carries an explicit sibling/work ordinal when order is semantic |
| dependency DAG | cardinality-many refs to prerequisite work; membership is a set |
| `:open` | absence of terminal outcome/completion evidence |
| `:done` | presence of an outcome/completion ref or fact, not a label |
| `:blocked` | derive from unfinished prerequisites; if an external impediment is irreducible, record the impediment as its own fact and derive blockage from its presence |
| `:active` | derive current work from the current run's triggering message/work ref; otherwise select earliest eligible unfinished work by explicit ordinal |
| progress | derive completed terminal leaves over all leaves |
| plan goal | durable goal text/shape on the root work item; a message may point `about` it |
| `created-at` priority | explicit order/priority fact; transaction time remains provenance only |
| plan context block | ordinary render producers over the work facts, participating in the one walk/cache path |
| automatic consult | agent returns a message/assignment value; settlement derives from the reply's shape and refs |
| `reconcile!` | retain the lesson of one identity-preserving data diff, but design it fresh over presence-based work facts |

The `my.plan` name itself is not a conflict when it means this small
agent-authored planning toolkit. It conflicts when used for generated program
publication, runtime scheduling, context assembly, or placement. The vocabulary
authority already reserves “execution plan” for `plan-execution` and `my.plan`
for the planning toolkit (`AGENTS.md:642`).

## Part 2 — messaging and replies today

### How a task reaches an agent

1. The web route is `POST /agent/{id}/message`
   (`src/seon/render/route.clj:13-19`). The handler decodes `content` and passes
   the path agent id to the inbound owner (`src/seon/render/web.clj:1359-1367`).
2. `message/inbound-tx` validates that the recipient agent exists and that
   content is nonblank and bounded. It produces one message with `to`, content,
   and time, deliberately omitting `from` and provenance from the row
   (`src/seon/cluster/message.clj:258-303`).
3. The web owner commits that transaction function with process provenance
   (`src/seon/render/web.clj:1140-1156`). It adds `:seon.db/user` only when the
   service's `id` resolves as an agent, and then points it at that agent
   (`src/seon/render/web.clj:1121-1125`). This is not human identity.
4. The commit's `:seon.cluster.message/to` datom is routed by the one Datahike
   listener directly to the recipient agent entity's mailbox. A missing route
   wakes the armer (`src/seon/cluster/wake.clj:163-186`,
   `src/seon/cluster/wake.clj:203-234`). The wake says only “look.”
5. `next-agent-work` first respects a held run, otherwise selects the oldest
   unanswered message addressed to that agent
   (`src/seon/cluster/work.clj:565-610`,
   `src/seon/cluster/work.clj:646-673`). “Unanswered” means no run points to the
   message as its trigger.
6. The loop opens and claims a run, recording `:seon.cluster.run/trigger` in the
   same transaction before any model call (`src/seon/cluster/loop.clj:1132-1160`).
   That ref, not a message flag, is answeredness.
7. The provider reply is split into ordered forms, frozen as a run plan, then
   each form is evaluated. The final receipt and any interpreted disposition or
   message rows commit atomically (`src/seon/cluster/loop.clj:286-335`,
   `src/seon/cluster/loop.clj:1692-1722`).

### How the answer gets back, and to whom

`my.run/complete` returns the pure value
`{:my.run/disposition :completed :my.run/result result}`; it does not deliver
anything itself (`src/my/run.clj:69-84`). The loop stores that admitted value in
the terminal receipt and closes the run in the same transaction
(`src/seon/cluster/loop.clj:249-335`).

If the trigger's `from` resolves to an agent, `message/reply` derives an
ordinary `my.message/send` value to that asking agent. It suppresses the reply
when the trigger is already an answer to the current agent, preventing a bounce
(`src/seon/cluster/message.clj:135-182`). The loop sends the derived row through
the same delivery path and terminal transaction as explicit agent messages
(`src/seon/cluster/loop.clj:625-672`,
`src/seon/cluster/loop.clj:1536-1601`).

If the trigger has no agent sender, `message/reply` returns nil. The source calls
this a human request and says its completion goes to a surface rather than an
agent message (`src/seon/cluster/message.clj:152-154`). Concretely, there is no
user-addressed durable message and no `/result` attribute on the run. The reply
is the `my.run/complete` map serialized in the terminal eval receipt's
`:seon.cluster.eval/result-edn`, joined to the run and from there to the trigger
(`resources/seon/schemas/seon.cluster.eval.edn:9-49`,
`resources/seon/schemas/seon.cluster.run.edn:13-40`). The web transcript can
render that receipt; a machine consumer must query and decode it.

### Threading that exists

There is no conversation/thread entity and no stored answered or reply flag.
Threading is the following ref graph:

```text
outside/agent message
        ↑ :seon.cluster.run/trigger
       run
        ↓ terminal receipt contains my.run/complete value
outbound message --:seon.cluster.message/caused-by--> trigger message
        └--:seon.cluster.message/about-----------> assigned/explained fact
```

- `run/trigger` makes answeredness and the request-to-run join queryable
  (`src/seon/cluster/message.clj:70-84`).
- `message/caused-by` makes chain depth and “already an answer to us” derivable
  (`src/seon/cluster/message.clj:86-119`,
  `src/seon/cluster/message.clj:163-180`).
- `message/about` can point at any uniquely identified fact; ambiguity is a
  refusal rather than a guessed identity (`src/seon/cluster/message.clj:203-242`).

This is enough for causal threading. An explicit conversation entity is not
currently justified.

### What src-inspect-ai does now

The package README names `seon_cluster.py` as the current io-prepl client and
says the deleted pod HTTP adapters are unsupported
(`src-inspect-ai/README.md:17-32`). The maintained `seon` model provider:

1. projects Inspect's ordered chat messages into one objective string
   (`src-inspect-ai/src/seon_inspect/provider.py:22-39`);
2. calls `SeonHost.run_sample` (`src-inspect-ai/src/seon_inspect/provider.py:68-89`);
3. sends one io-prepl form invoking `seon.eval.drive/run-sample-json!`
   (`src-inspect-ai/src/seon_inspect/host.py:88-103`,
   `src-inspect-ai/src/seon_inspect/host.py:180-229`); and
4. uses the drive report's `completed-result` as Inspect's model completion
   (`src-inspect-ai/src/seon_inspect/provider.py:79-89`).

Inside the JVM, the drive creates an agent, calls `message/inbound-tx`, and
commits the objective as an ordinary anonymous inbound message
(`src/seon/eval/drive.clj:76-108`, `src/seon/eval/drive.clj:265-300`). It then
queries runs triggered by that exact message, pulls their eval receipts, parses
the stored result values, and selects the last completed result
(`src/seon/eval/drive.clj:110-168`). It is therefore **both** an eval-driven
harness at the outer boundary and a real message-driven agent episode inside:
io-prepl eval invokes the drive; the drive injects a message; the harness reads
receipt facts.

`src-inspect-ai/src/seon_inspect/solver.py` is stale. It still calls the deleted
`POST /agents/run` pod door and claims that door injects a real user message
(`src-inspect-ai/src/seon_inspect/solver.py:1-23`,
`src-inspect-ai/src/seon_inspect/solver.py:50-61`). It is not the maintained
surface named by the README.

### What “a user entity sends the task; the harness reads the reply facts” requires

It requires no second drive mechanism:

1. Ensure one `:seon.user/id` entity in the sample cluster.
2. Commit the objective message with `from` pointing at that user and
   `:seon.db/user` transaction provenance pointing at the same user.
3. Preserve `to` as the addressed agent so the existing wake route and work
   derivation remain unchanged.
4. Keep the exact objective message id. Query the run whose `trigger` points at
   it, then the terminal eval receipt carrying the last completed disposition,
   exactly as the current drive already does.
5. Treat absence of a completed disposition, a capped episode, and a stopped
   episode as distinct fact-derived outcomes; do not manufacture an empty reply.

The only production design change hidden in step 2 is sender semantics. A raw
Datahike ref can point at a user today, but current code defines `from` as an
agent: `sender` joins it only through `:seon.cluster.agent/id`, rendering resolves
only agent ids, and reply delivery addresses only agent ids
(`src/seon/cluster/message.clj:121-133`,
`src/seon/cluster/message.clj:427-461`,
`src/seon/cluster/message.clj:188-193`). Physically transactable is not the same
as semantically supported.

## Part 3 — human users

### What exists today

Fresh schema has `:seon.db/user`, an indexed transaction ref
(`resources/seon/schemas/seon.db.edn:62-65`), but no `:seon.user/id` attribute or
user entity schema in `resources/seon/schemas/`. Current architecture defines
that provenance target as an existing agent or root ref
(`docs/seon/architecture/data-model.md:126-136`). The web inbound path follows
that design by recording an agent as the transaction “user” when it can
(`src/seon/render/web.clj:1121-1125`).

The message schema's `from`, `to`, `about`, and `caused-by` are all generic
database refs (`resources/seon/schemas/seon.cluster.message.edn:1-5`,
`resources/seon/schemas/seon.cluster.message.edn:27-28`,
`resources/seon/schemas/seon.cluster.message.edn:71-78`). Nevertheless, the
entity contract and all fresh behavior call `to` an agent recipient and treat
absence of `from` as outside the agent population
(`docs/seon/architecture/agent-runtime.md:173-190`). A user entity can be the
physical target of those refs, but doing so today changes their implemented
meaning and breaks wake/reply/render assumptions.

### Minimal honest user entity

The minimum durable declaration is one identity attribute:

```clojure
{:seon.user/id "user"}
```

That is enough for lookup refs, message sender identity, transaction provenance,
and later accretion. If an entity schema is declared for render/discovery, it
should require only `:seon.user/id` and remain open. It needs no kind, role,
status, session, created time, preferences map, or predeclared profile blob.

“Record some info about them” should mean adding independently declared,
globally identified attributes to the same entity as real needs arise. A display
name, locale, contact method, or domain fact can accrete without changing what
`:seon.user/id` means. A generic opaque `profile` map would make those facts
unqueryable; a closed user map would make accretion break existing producers.

The identity should also be the target of `:seon.db/user` for actions performed
by the human or harness on that human's behalf. Agent-authored transactions may
continue to point provenance at the agent. This makes “who submitted these
facts?” resolve uniformly without calling agents human users.

### Quarry lessons about humans

The old system had exactly this minimal entity: `:seon.user/id` was a unique
identity, `:seon.user` required only it, and the singleton lookup ref was
`[:seon.user/id "user"]` (`src-old/seon/agent/message.cljc:69-81`). Message
participants could be users or agents and pulls resolved either identity
(`src-old/seon/agent/message.cljc:114-116`,
`src-old/seon/agent/message/internal.cljc:91-95`). That uniform identity graph
worked well enough that old `my.plan` could return generated terminal answers
to either the requesting agent or user
(`src-old/my/plan.cljc:993-1005`).

The parts not to restore are equally instructive:

- the old message row stored an `origin` enum even though sender identity could
  derive human versus agent (`src-old/seon/agent/message.cljc:60-67`);
- it stored hop counts and required a later human-barrier query to correct the
  semantics (`src-old/seon/agent/message/internal.cljc:35-56`);
- every human-origin message automatically created an open plan/status row
  (`src-old/seon/agent/message.cljc:329-386`); and
- the effectful `message!` path performed acquisition, allocation, and
  transaction from inside the agent-facing call
  (`src-old/seon/agent/message.cljc:434-536`).

Fresh Seon's pure message values, run-loop commit, derived causal chain, and
separate work decision should remain.

### Naming and current inconsistency

Under the requested Clojure/REPL idiom, recommend `:seon.user/id` and call the
human at the prompt “the user.” `:seon.user/id "user"` also mirrors the default
REPL namespace without inventing a product noun.

There is an unresolved contradiction with ruling #24. The roadmap currently
says the **agent** is Clojure's `user` and the person is “the human”
(`docs/prds/sci-execution-runtime/plan/README.md:1711-1724`). The present task's
owner direction says the human at the prompt is “user.” These cannot both be
the naming authority. The source also calls agents “users” in transaction
provenance: the architecture says `:seon.db/user` points at an agent or root
(`docs/seon/architecture/data-model.md:126-136`), and the web path writes the
service agent there (`src/seon/render/web.clj:1121-1125`).

Recommended vocabulary:

- **user** — the human participant entity, `:seon.user/id`;
- **agent** — `:seon.cluster.agent/id`;
- **`user` namespace** — only the ordinary Clojure default namespace when no
  real agent namespace is assigned; do not infer participant identity from it;
- **`:seon.db/user`** — transaction author/submitter provenance, capable of
  pointing to either a user or agent, while prose calls the resolved entity by
  what it is.

This preserves the dependency's established `:seon.db/user` name while stopping
the prose-level conflation.

## Ranked open design decisions

### 1. Settle whether message participants are agents only or users plus agents

**Recommendation:** generalize sender identity, not wake ownership. Allow
`message/from` to be a user or agent; keep `message/to` agent-only for this
slice. The task message then has an honest sender while the one existing wake
route remains exact. If later the UI needs durable user-addressed messages,
add that only with an explicit user-consumer delivery contract; do not point the
current wake attribute at a user and hope the agent router ignores it.

### 2. Make the receipt join the canonical human reply contract

**Recommendation:** define the human reply as the last terminal receipt value
for the run triggered by the user's message. The current harness already proves
this query (`src/seon/eval/drive.clj:110-168`). Do not duplicate the result onto
the run or create a user message merely to make the harness convenient. Add a
reply message only if a user-side consumer genuinely needs message semantics.

### 3. Choose the fresh work terminal fact and ordering fact before `my.plan`

**Recommendation:** work identity + parent/dependency/owner/order facts, with
completion represented by outcome presence. Use an explicit ordinal for
semantic order. Derive earliest unfinished eligible work; do not port status,
active, created-at ordering, or completed-at recall. The terminal outcome shape
is the earliest unsettled contract because every read and toolkit function
depends on it.

### 4. Keep messages, work, goals, and instructions separate

**Recommendation:** a task message may point `about` a work/goal identity, but
message receipt must not automatically mint work. Per-task goal/instructions
belong on the durable work root; standing instructions remain
`:seon.cluster.instruction` rows connected through the cluster or agent
(`resources/seon/schemas/seon.cluster.instruction.edn:1-15`,
`resources/seon/schemas/seon.cluster.agent.edn:35-44`). This permits chat without
inventing a plan and long-term work without depending on transcript retention.

### 5. Keep causal refs instead of adding a thread entity

**Recommendation:** retain `run/trigger`, `message/caused-by`, and
`message/about`. They already derive answeredness, chain depth, delegation
reply, and work association. Add a conversation identity only when a concrete
query cannot be answered by this graph; no such query appeared in this audit.

### 6. Keep `my.plan` small

**Recommendation:** reserve it for pure constructors/queries and value-returning
agent planning operations over the new work facts. Reconciliation may return a
data diff for the run loop or database capability owner to apply. Generated
code publication, run scheduling, automatic consultation, context bands, and
HTML stay with their existing fresh owners.

### 7. Resolve “user” versus “human” wording explicitly

**Recommendation:** accept this task's newer owner direction: user means the
human entity; agent means the agent; `user` as a namespace is Clojure syntax,
not agent identity. Amend ruling #24's prose when the owner seals this. Until
then, any schema work would encode an unresolved vocabulary conflict.

## Tool and render feedback

1. `bin/seon status` rendered a live default cluster with a prepl coordinate,
   then immediately reported that the roster was unreadable because the same
   recorded JVM's prepl was unreachable. That face is internally contradictory
   to a reader and should distinguish advertised coordinates from verified
   reachability.
2. `src-inspect-ai/README.md` correctly names the maintained io-prepl surface,
   but `src-inspect-ai/src/seon_inspect/solver.py:1-23` still presents the
   deleted `/agents/run` pod door as canonical. This is high-friction source
   output: a reader following the module rather than the README gets the wrong
   harness architecture.
3. Current architecture says `my.run/wait` releases a run for later resumption
   (`docs/seon/architecture/agent-runtime.md:104-116`), while the implemented
   function and loop say the run closes and a later trigger opens a fresh run
   (`src/my/run.clj:40-55`, `src/seon/cluster/loop.clj:320-335`). The target
   document is stale at a user-visible lifecycle boundary.
4. Current message rendering collapses both a human and a system error recorder
   to “From outside this cluster” (`src/seon/cluster/message.clj:427-461`). That
   is honest under the current facts but too coarse once a user identity exists;
   the user row should let the same family renderer name the actual sender.
