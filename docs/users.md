# Users

A user is an agent, a session is an agent on a branch, and the system is one mechanism in different data configurations: entities, attributes and refs, with the system a function of that state. Parts of this design run today and the rest lands by [the stages](#the-stages-agreed-2026-11-09); each section says which.

## One system of agents: the design (agreed 2026-11-09)

Every part of Seon is the same few pieces, and what makes one part differ from another is its data and its pull; nothing here is a new concept.

**The pieces:**
1. **An entity:** an id, its attributes and its refs, each ref naming what it points at (`:seon.db/to`, [data modeling](data-modeling.md#things-relate-by-refs)); every schema entry optional unless it is identity, and what is present validated at the write.
2. **An agent:** an entity with a key, its own branch and a process forked from the fork server. The user, root and every working agent are this.
3. **A pull pattern** (`:seon.ctx/pattern`) giving `@ctx`, the agent's context and its page, keyed by attributes (no `:as`, no `*`), its joins those its refs imply (```seon.db/implied-pattern```).
4. **A render** named by the entity's schema; **one pass over the pull renders both the page and the model's context** ([render](render.md#one-rendering-system-drawn-from-the-data)). What an entity is is decided as the write check decides it, by the best-matching schema of the attributes it holds, a tie an error value (```seon.db/most-specific```). Its renders are properties of that schema, one per output, run in one component run. A parent's render orders and places its children; the request is the root's model output with each child's in its place. A render takes what it reads as arguments, and the cache is an optimization, never relied on.
5. **Messages and requests** over the agent's socket and Flow channels: the `:render` and `:eval` requests.
6. **One merge function**, branch to main and back ([sharing](sharing.md)).
7. **One reconciler**, desired state against observed: it starts, parks and restarts agents.

**What makes each part differ:**
- **The user** is an agent with a branch and always a process (stage 4). Its pull reaches itself, root and the agents it started (`:seon.agent/_parent`, their main half). Its tree is read-only: it sends requests, and agents change their own branches. Its walk is the person's whole view, holding beside `@ctx` the renders it asked its agents for.
- **Root** is the agent keyed `"root"`, working its task `request/root`; its pull reaches the sessions it started.
- **A working agent's** pull reaches its task, evals, model calls, messages, cites and searches. It renders its own page in its own process when asked (the `:render` request, ```seon.repl/render-ctx```), answering the components' html with their revisions.
- **The system entity** (`:seon.system/root-fact` today, `seon.graph` at stage 3): boot, config, quiescence; data root's procs read, not an agent.
- **Root's process** is the go-between: the writer, the reconciler, the fork server, the web transport and the merge gate. At the end of stage 4 it runs no render of an agent or a user.

**How data flows, each hop by its one mechanism:**
- **Inside a process:** Flow procs and bounded channels ([flow](flow.md)); a step never blocks, a request is answered once, back-pressure is a full channel.
- **Between root and an agent:** one socket per agent (```seon.sock/send!```), framed messages; root's side is its agent proc (```seon.agent-proc/agent-frame```).
- **Into the db:** an agent writes its own branch; root's writer is the one writer of main. A message is one fact on main (```seon.system/message-write```), delivered onto the recipient's branch by its next act (```seon.agent/deliver!```).
- **Between branches:** the one merge: main into an agent's branch at its turn's open (```seon.agent/merge-main!```), an agent's work into main through the gate.
- **Between agents:** a message, or a request routed through root; never a write to another agent's branch.
- **To and from the browser:** root's web proc writes html and Datastar patches on one compressed event stream per tab ([render](render.md#in-flight-output-streams)); a click posts to the web proc, which sends it on as a request.

**Goes with the stages, each a special case:** root's render walks of pages (`root-walk`, `user-walk`, `branch-walk!`, the cold draw); root's session as a thing apart; `:seon.agent/root`; an opened eval written on main (`:seon.agent/opened`, ```seon.system/opened!```, ```seon.agent/untaken-facts```).

## The data model: schemas, refs and pulls

Every part of the design is entities, attributes and refs, each defined once in the schema registry ([data modeling](data-modeling.md)): an entity schema names which attributes go together and may extend another (```seon.db/parents```), and a ref names its targets (`:seon.db/to`). The schemas are in `resources/seon/schemas/`; the entity index's `:targets` (```seon.db/index```), ```seon.db/reaching``` and ```seon.db/implied-pattern``` read the links.

### The entities

Each is its own schema, linked to the others by refs ("refs out" point away; "reached by" are refs pointing at it, read in reverse in a pull).

- **An agent**, `:seon.db/agent-fact`: identity `:seon.agent/key` (a content id). Refs out: `:seon.agent/task`, `:seon.agent/parent` (who started it), `:seon.agent/system` (doc sections). Reached by: `:seon.eval/agent`, `:seon.model-call/agent`, `:seon.message/to`, `:seon.cite/of`, `:seon.search/of`, `:seon.task/agent`, `:seon.task/started-by`, `:seon.process/agent`, `:seon.task/examples`. Render: ```seon.render.ui/agent```. A started agent, `:seon.db/agent-start-fact`, extends it with its boot and start time.
- **A user**, `:seon.db/user-fact`: the agent schema plus `:seon.user/name` (an identity).
- **The system**, `:seon.system/root-fact`: identity `:seon.system/id`; boot, generation, quiescence, source, the config in force (its own entity, `:seon.system/config`), who is signed in (`:seon.system/signed-in`).
- **A task**, `:seon.task/task-fact`: identity `:seon.task/id`. Refs out: `:seon.task/needs`, `:seon.task/examples`, `:seon.task/agent`, `:seon.task/started-by`. Its proofs are var names, joined by value.
- **An eval**, `:seon.db/eval-fact`: identity `:seon.eval/id`. Refs out: `:seon.eval/agent`, `:seon.eval/call`, `:seon.eval/result`; its value's render schema is `:seon.eval/schema`.
- **A result**, `:seon.result/result-fact`: identity `:seon.result/hash`, a content id.
- **A model call** (a turn), `:seon.model-call/fact`: identity `:seon.model-call/id`; ref out `:seon.model-call/agent`.
- **A message**, `:seon.message/fact`: identity `:seon.message/id`; ref out `:seon.message/to`.
- **A cite**, `:seon.cite/cite-fact`: refs out `:seon.cite/of` and `:seon.cite/target`, which names something that exists ([an entity cites](references.md#an-entity-cites)).
- **A search**, `:seon.search/search-fact`: ref out `:seon.search/of`.
- **A doc section**, `:seon.doc/fact`: identity `:seon.doc/address`; ref out `:seon.doc/parent`.
- **A var**, `:seon.db/var-fact`: identity `:var/name`; its `:seon.test/*` facts are a proof's verdict.
- **A process**, `:seon.process/process-fact`: ref out `:seon.process/agent`; root's observation of a spawn.

### Who writes what, and where

One writer per attribute ([sharing](sharing.md#where-a-fact-lives)). On main, by root's writer: what root observes of an agent (`:seon.agent/status`, `boot`, `branch`, `task`, `model`, `max-turns`, `holder`), process entities, the system entity, messages as sent, and `:seon.agent/parent`. On the agent's own branch, by its process: its turn, namespace, the commits it heard (`:seon.agent/main-commit`, `main-program`, `base-commit`), its evals, results, model calls, cites, searches and delivered messages. Content-id entities (results, cites, messages) are the same on every branch. An agent reads its main half at `:seon.agent/main-commit`.

### Who started whom

The agents form one tree rooted at the user: each agent names its parent, `:seon.agent/parent`, one ref written with the agent from its task's `:seon.task/started-by` (```seon.task/start-tx```) and kept when it continues; the agents one started are its `:seon.agent/_parent`. A child's write never touches its parent, so two branches starting children of one agent do not conflict. A refine is started by the agent whose run it refines (```seon.task/refine-tx```). `:seon.task/started-by` is written where the task is released: a person's request through root's `:ask` (the asker), an agent's launch (```seon.agent/launch!```), or a release at root's REPL (```my.root/run!```, ```my.root/request!```), whose evaluator is the user. Root's session names the user its parent in the write that makes it (```seon.system/root-agent-tx```), so the user's tree holds it and what it launches before it ever starts.

### Each role's pull

A pattern is data on the entity's schema (`:seon.ctx/pattern`), or the entity's own:
- **A working agent:** `:seon.db/agent-fact`'s pattern: its task by `:seon.task/task-fact`'s pattern and, in reverse, its evals, model calls, messages, cites, searches, system sections, and the agents it started as references.
- **Root's session:** its role's pattern plus a join to who started it (below).
- **The user:** `:seon.db/user-fact`'s pattern: its name and the agents it started, each by `:seon.agent/entry`, to `:seon.config.render/agent-tree-depth` levels.

## What each thing is

A thing is its data structure: any entity holding the shape is that thing, however it got there.

- **User:** an agent entity holding ``:seon.user/name``. A fresh store's user is named by ``:seon.config.root/user-name``; a person's `ask!` and `message!` are from the signed-in user, refused with none.
- **Who points at what:** ``:seon.agent/parent``, one ref on the child; there is no owner attribute, and a window over the children (`:seon.agent/_parent`) orders by when each was made, since agent ids are content-derived.
- **Session:** an agent on a branch of its user's branch (stage 4).
- **Eval:** an entity on its agent: code, intent, ``:seon.eval/response`` (handle, ``:ms``, ``:ticks``, ``:bytes``, ``:ns``, ``:warnings``) and ``:seon.eval/result``, a ref; ``seon.db/response-of`` answers the whole. Its id is a 16-character digest: of its call and place in the reply for a model's eval, else of a fresh UUID (``seon.repl/eval-id``); its entity id is issued before it runs (``seon.db/take-eid``). Evaluating again (``seon.repl/again``) moves the ref, and the old result stays (``seon.repl-test/an-eval-evaluated-again-moves-its-result-ref``).
- **Result:** one immutable entity per value, the same on every branch: ``:seon.result/hash`` (the full hash; the wire's handle is its first eight digits), ``:seon.result/value``, ``:ex``, ``:out``, ``:err``, ``:forms``, written by ``seon.db/with-response``. An answer not kept as it is (a host object, a value past the bound) is hashed with its eval's id. Two evals of one value share one result, and two branches producing it merge with no conflict (``seon.repl-test/two-evals-of-one-value-hold-one-result``). The result is not joined into `@ctx`'s pattern, which would load every value; a reader follows the ref.
- **Message:** one entity read by everyone it is to ([Messages](#messages)).

## Where each fact lives

Each fact lives where its readers find it, and that placement is what removes guards and makes reads lazy. Main holds that an agent exists, its branch, who points at what, messages as sent, and live process state; a branch holds evals and a person's data and functions; content-id entities are on every branch alike, so forks share them and cannot conflict. A value next to what you read gets read (a node load realizes every value in it), so large values live on their own result entity.

## Four interfaces

There are four ways to touch this system, and none writes another process's branch.

- **Say something:** a message fact, to any agent in any state.
- **Evaluate:** the agent's REPL. A person's input and a model's reply are one shape: ``seon.reader/forgive`` turns text into a message and evals, a ``;;`` line above a form its intent. Words wake an agent; a form doesn't.
- **Read:** ``@ctx``, a pull on an entity.
- **Merge:** one pure function of references (target, source and base in ``:seon.db/at-opts``' shape, read by ``seon.db/at``) and options, answering tx-data written by whoever writes the target.

## The merges

One merge function takes different options for each case ([sharing](sharing.md#what-a-branchs-nodes-are)).

- **Pull:** main into a branch takes program only (schemas, functions, tests, docs), so every branch has the same program and its own data.
- **Merge to main:** an agent's work through the gate.
- **Merge a session into its user's branch** (stage 5): every datom that does not conflict, by a person's decision.
- **Options:** which keys (a cherry-pick has no second parent), `-X theirs` or `-X ours`, and which data (task ``seon.merge/a-merge-takes-options``).
- **A refused merge is a normal step:** main moved, the error says so, the agent merges main and tries again.

An agent's first act is to merge, so a branch with no agent costs nothing. A conflict surfaces as unmerged entries, which the status and done rule read. "Behind" is a function of state, readable with no process. A schema change a merge brings is checked against the values the branch holds (``my.schema`` `usage-check`).

## Messages

A message is delivered to an agent's entity and stays there until the agent removes it, and the agent's pull and renders show what it holds, so there is no read or unread state (`seon.agent-test/one-message-reaches-two-agents`, `a-removed-message-is-not-delivered-again`, `a-program-pull-leaves-messages-alone`).

- **One entity:** ``:seon.message/from``, a ref to an agent or to the system entity for a mechanical message, written by identity (``seon.task/sender``) and read by name (``seon.task/sender-name``, `"seon"` for the system); ``:seon.message/to``, a set of refs to recipients by key; ``:seon.message/sent-ms``; ``:seon.message/body``; made by ``seon.task/message-tx``. An agent sorts them at its REPL: ``(sort-by (juxt :seon.message/sent-ms :db/id) (vals (:seon.message/_to @ctx)))``. Time only orders; nothing decides by it.
- **Delivered:** a send writes the message on main (``seon.system/message-write``), to an agent with no process too. The agent's next act (``seon.agent/deliver!``, from a turn or from ``(messages)``) writes onto its branch the messages main holds to it that the branch does not (``seon.task/undelivered``, a seek of `:seon.message/to`, O(the messages to it)); main is read in that one place (``seon.agent/undelivered`` through ``seon.agent/heard-main``). The message on the recipient's branch is the proof of delivery: no mark, and no model call names a message. The turn that took it is derived: the first turn whose basis is at or after the transaction that put the agent's ref on it (the link's transaction, held in the pull at `:at`; ``seon.ctx/placed-messages``).
- **Removed:** ``(swap! ctx update :seon.message/_to dissoc e)`` retracts this agent's ref; the entity and other recipients' refs stay, and it is not delivered again. A program pull never brings another agent's messages.
- **Chat:** text that is not code is a message, a reply included; for the model, from me is `assistant`, anything else `user`, in the order messages reached the branch. A reply as a message with a `to` is task ``seon.agent/a-reply-is-a-message-with-a-to``.
- **Waking** is a Flow channel's, never a watch on commits. When the writer has answered a message's commit, the api puts `{:agent key}` per recipient on the reconciler's `:wake` port. The reconciler (``seon.reconcile/wake``) wakes an agent whose task waits (parked, told or at its cap); a wake for a running agent is held (`:late`) until it waits, then checked against its branch (``seon.reconcile/settle-late``). At boot each waiting agent is checked against main once (``seon.reconcile/boot-wakes``), so a lost wake costs delay, never a message.
- **Turns:** the write that starts a woken agent raises ``:seon.agent/max-turns`` by ``:seon.config.agent/wake-turns`` (``seon.task/start-tx``; [the cap](turn.md#the-cap)). An agent stops early by replying with no evals. A fresh agent is ``my.task/launch!``. Two agents answering each other are bounded only by that allowance.

## What's on screen

What runs today, which stages 2a and 4 replace: root's render proc walks the user's page and root's, and renders an agent's from its branch when the agent does not ([render](render.md#the-page)).

- **`/` is whoever is signed in** (``:seon.system/signed-in``, set by boot until there is a login); with no one, a sign-in page (``seon.render.ui/sign-in``). `/` shows the user's focus, `:seon.user/focus`, the agent whose page a tab last opened (opening root's page or `/` retracts it), else root's page; `/root` is root's, `/user/<name>` a user's, and the user's own page is `/agent/<their key>`.
- **A user's page is the sessions they started, as tiles**: each the agent's entry (```seon.render.ui/agent``` nested: what was asked, how it stands, the newest message to it and its answer), grouped as parked, working or finished, those that need the person first. It is a kept walk, so an agent started from it appears with no reload.
- **What a person types** is split into words and forms by ``seon.reader/words-and-forms``: words a message, forms its REPL's.
- **UI state is in process memory:** the open sections, the turn in view and "more" are in ``seon.render.proc``'s memory and go on a restart; the URL is what survives. A click that writes a fact costs main a commit, so nothing like a hover writes.

## Root's agent

Root's session is an ordinary agent a person talks to Seon through, keyed `"root"` and working the task `request/root`, made from the chat template; no parallel system for root. Root's boot makes both when main has none, the task parked, and the first message's wake spawns it (```seon.system/root-agent-boot-tx```); later messages wake it as any agent, continued by its key (``seon.task/agent-key``). It works on its own branch and reads main at the point its turn heard. What makes it root's is data: its own `:seon.ctx/pattern`, `[:seon.db/agent-fact {:seon.agent/parent :seon.db/user-fact}]`, its role's pattern and a join to the user who started it, written once when it is made (```seon.system/root-agent-tx```), and its task's plan. Its render is the stage ([render](render.md#an-entity-may-name-its-own-render)). It asks for the sessions at its REPL (```my.user/agents```; `seon.render-test/roots-session-asks-for-its-sessions`) and either answers itself, points the person to a session already on the work (`/agent/<key>`), or starts one with `(my.task/launch! "what they want")`. The evals Claude sessions send root's REPL are recorded on root's entity (`:seon.system/id`), never in its session's conversation (`seon.render-test/roots-session-is-an-agent-of-its-own`). The system's own view beside the page is placed for it by ```seon.render.proc/page-value```. Stage 3 makes root itself the agent keyed `"root"`.

## The stages (agreed 2026-11-09)

The design lands in stages, each merged alone with main green and saying what it deletes; each stage's state and plan are its task's.

0. **Done:** each ref names what it points at (`:seon.db/to`, ```seon.db/reaching```, ```seon.db/implied-pattern```).
1. a. **Done:** a context is keyed by attributes (no `:as`, no `*`).
   b. **Built:** an agent renders its own page (```seon.repl/render-ctx```, the `:render` request); its reader becomes the user agent at stage 4.
   c. **Done:** the boot race, fixed in our Jolt fork.
   d. **Built:** boot resumes from the db and reads no file; indexing is an explicit act ([source](source.md#a-boot-resumes-from-the-db), [the bridge](source.md#a-landing-enters-main-as-data)).
2. **One pass over the pull:**
   a. **The walk recognizes, looks up, runs and places** (task ``seon.render/an-entity-is-drawn-by-its-schemas-render``): renders named by data run as components, a parent's render places its children, the request is read from the root's model output, and hand-placed components go.
   b. **The model renders and each turn's diff** (task ``seon.render/a-model-render-draws-each-turns-diff``): a model render per entity schema; a turn's diff computed from its two bases, never stored (```seon.ctx.wire/page-edits-between```); the `:wire/*` marks go; `test/seon/recorded_requests.edn` byte for byte.
3. **The system entity and root as an agent** (task ``seon.system/the-system-entity-and-root-as-an-agent``; one store erase): the system's facts on a `seon.graph` entity, root the agent keyed `"root"`, `:seon.agent/root` gone, liveness read from `:seon.agent/boot`.
4. **The user agent** (tasks ``seon.agent/the-user-agent-lives-on-its-own-branch``, ``seon.render/the-user-agent-renders-the-users-view``): always a process on its own branch; its read-only walk of its pull, holding the renders it asks its agents for, generates the page; root relays bytes and renders nothing, so root's page walks go; opening an eval is an eval it sends the agent.
5. **A session syncs with main unless private** (task ``seon.merge/a-session-syncs-with-main-unless-private``): a syncing session takes main in at each turn's open and gives back at each turn's end everything not held by an unmerged entry or a reached red test; a private one reaches main only when the person saves it.

Later: the user agent's model routing what the person types; login and other users.

Settled with the stages: the model render's property is `:seon.render/openai-compat` (`:seon.render/model` names how a value prints for the model); an agent answers a render ask with the components that moved, by id; a message is delivered when it is on the recipient's branch; an opened eval's result is read whole by the model too; a cite's target is a ref to an entity that exists, or one the same transaction creates.
