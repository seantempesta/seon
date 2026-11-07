# Users

The design for users, worlds within worlds: not built yet, and the one place the three sessions read it from; each claim says whether it ran (lab), was read from code (read) or is untested.

A user is an agent, a session is a branch of its user's branch, and the system is one mechanism used in different data configurations. State is always in the database and the system is a function of that state: there are no runs and no objects, only entities, attributes and refs. Owner: the Web session. Words are git's and Clojure's ([vocabulary](vocabulary.md)).

## What each thing is

A thing is its data structure: any entity holding the shape is that thing, however it got there, and where each part is stored is then ours to choose.

- **User:** an agent entity holding ``:seon.user/name``, its lookup ref and what makes it a user; until there is a login, boot writes one named for the OS account running root, keeping one entity across boots. On main: no branch yet. Same key, same entity: login decides later who may be ``sean``. It has a process only while something needs one, and it can run with a model to act for its person, recording like any agent. Marking each record as the person's or their agent's is open.
- **Who started what:** a task names who started it (``:seon.task/started-by``, the person asking or the agent launching it), and its agent, when it starts, joins that one's ``:seon.agent/agents``. Ended tasks tell them how they stand.
- **Who points at what:** ``:seon.agent/agents``, a set of refs on the user, on main. Many users can point at one agent, and any agent can hold the same refs to those it started; there is no owner attribute; removing a ref keeps its history. Windows over it must order by when each joined, since agent ids are content-derived (lab: the newest fell out of a window of 20 at 1,000).
- **Session:** an agent on a temp branch of its user's branch.
- **Eval:** an entity attached to its agent: code, intent, and ``:seon.eval/response``, what is about that evaluation (its handle, ``:ms``, ``:ticks``, ``:bytes``, ``:ns``, ``:warnings``), and ``:seon.eval/result``, a ref. Built: ``seon.db/response-of`` answers the whole response as every reader has always had it. Its id is `"e<eid>"`, issued by the store before it runs, uniquely across branches (built, ``seon.db/take-eid``). Evaluating again (``seon.repl/again``) moves the ref, and the old result stays (``seon.repl-test/an-eval-evaluated-again-moves-its-result-ref``).
- **Result:** one immutable entity per value, the same on every branch (built, ``:seon.result/result-fact``): ``:seon.result/hash``, the full hash as identity and content id, the lookup ref; ``:seon.result/value``, ``:ex``, ``:out``, ``:err``, ``:forms``, written by ``seon.db/with-response``. The wire keeps the short handle, its first eight digits: the identity must be the full hash, or two values sharing a prefix would become one entity. An answer not kept as it is (a host object, a value past the bound, several forms) is hashed with its eval's id, as its handle always was, so its result is its eval's own. A nil value is a result with no value and no error; an eval that threw holds a result with ``:ex`` and no ``:value``. Proof: ``seon.repl-test/two-evals-of-one-value-hold-one-result``; what the model reads is unchanged: ``seon.context-test/what-the-model-reads-is-the-same-with-result-entities``.
  A content-id result has the same eid on two branches, and two sessions producing one value merge into one entity with no conflict, each eval pointing at it (lab). A new small result costs 50% more datoms than inline and about 38% more time; re-using one costs the same as inline (lab, 300 evals, one run). Whether the small case is worth an entity is open: a value below the out-of-line size could stay on the eval, at the cost of two shapes.
- **Message:** one entity, read by everyone it's to: ``:seon.message/from``, ``:seon.message/to``, a set of refs, ``:seon.message/body``, at an issued eid. Built: see [Messages](#messages).
- **Tab:** one entity per browser tab, holding what that tab shows. Not built: see [What's on screen](#whats-on-screen).
- **Layout:** a data structure naming existing components, on its own key, as root's already is. Functions remain for what data can't express.

## Where each fact lives

Each fact lives where the readers who need it find it, and that placement is what removes guards and what makes reads lazy.

- **Main:** that an agent or user exists, its branch and upstream, who points at what, and messages addressed to a branch. Delivery is the fact.
- **A branch:** evals and a person's data and functions.
- **Every branch alike:** content-id entities, forms, results and messages, so forks share them and they can't conflict.
- **Merge bases:** one, from the commit graph. A partial merge recorded as a parent loses no data, tested through ```seon.merge/carried-tx```, which carries only retractions its source wrote; docs/sharing.md#the-data-model says what it does get wrong.
- **Live process state:** main only, root's observation.
- **Laziness comes from placement:** reading 300 evals' code cold with 60 KB values took 100-125 ms inline and 3 ms with a content-id result, the same as no large values; following the ref took about 1 ms (lab, one run). A node load realizes every large value in it, so a value next to what you read gets read. Identical values share one result, because the value is no longer mixed with ``:ms`` and ``:ticks``. The result is not joined into the agent's ``@ctx`` pattern: ctx holds an entity once, at its first path, so a result two evals share would be a stub at the second, and joining would load every value with the walk; a reader that needs the value reads it from the db by the ref (``seon.db/response-of``), as the model's request and the page do when they draw an eval.

## Four interfaces

There are four ways to touch this system, and no interface writes another process's branch, so the guards that would protect against that have nothing to do.

- **Say something:** a message fact, to any agent in any state.
- **Evaluate:** the agent's REPL. A person's input and a model's reply are the same shape: ``seon.reader/forgive`` turns text into a message and evals, with a ``;;`` line above a form as its optional intent. Words wake an agent; a form doesn't.
- **Read:** ``@ctx``, a pull on an entity. An agent's for its context, a user's for its home page.
- **Merge:** one pure function of references, never db values: target, source and base in ``:seon.db/at-opts``' shape, read by ``seon.db/at``, and options. It answers tx-data, written by whoever writes the target.

## The merges

One merge function takes different options for each case, which is what makes this one system rather than several.

- **Merge a session into its user's branch:** every datom that doesn't conflict, data, code and transcript, by a person's decision. Data and code mix here and only here. Until then the session is a branch nobody needs to delete.
- **Pull:** main into a user's branch and on to its sessions: program only, schemas, functions, tests and docs, ``data-attrs #{}``. So every branch has the same program and different data. A session takes its user's data at the fork and only program afterwards.
- **Merge to main:** an agent's work through the gate, per agent's work. A person decides when a session's code goes.
- **Options:** which keys (a cherry-pick has no second parent and doesn't move the base), ``-X theirs`` or ``-X ours``, and which data. A curated send of one namespace of two, and main's version winning a conflict, both worked (lab).
- **A refused merge is a normal step:** main moved, the error says so, the agent merges main and retries.

A test's digest survives both: a function untouched on a user's branch reads identically to main after a whole merge and after a rebase, and one the branch redefined reads as that branch's, so it gets its own verdict (lab, comparing what a read would hash).

Whole merges retract nothing: agent to user to main to user, forms and data, both pull orders, twice (lab). Criss-cross goes away with the base as a fact; either ancestor gave the same merge (lab).

## Lazy

Be lazy about whether work happens, never about when within a step: decide at a boundary, an agent's start, a turn or a read of a part, then do the work there.

A lazy value realized later can pin a db (160 MB per proc, measured), miss read recording and so give a false green, throw past the catch that names the failure, or charge the wrong eval. So:

- **An agent's first act is to merge.** Nothing merges on a branch with no agent, and dead branches cost nothing. Merging once when needed took a third of the time of merging every time (lab: 6.1 s against 17.5 s over 200 merges).
- **A conflict** surfaces as unmerged entries and can launch an agent, under a spend limit, when a process next exists on that branch.
- **"Behind"** is a function of state, readable with no process, so a page can say so.

## Sharing

A branch should be main plus its own changes, so everything is a fork of a shared base; today's merges don't keep it that way, because a merge writes its own copy of each leaf.

- After 200 program merges, 84% of a user's index was its own (lab, real merges, 1,080 forms).
- Content addressing got 12-15% merged every time but 59% merged once, and a write phase went from 29 s to 52 s, so it's not recommended (lab).
- Rebase keeps a branch's own nodes flat, tracking its data: about 885 to 930 at 5,000 entities, about 6.2 s each, flat in how far behind; a retraction, an own function and an old commit all survived (lab). As a lazy step on a count, checked at an agent's start, a branch nobody uses never pays.
- Rebase isn't proved safe: it sits on main's whole head, so it takes main's platform records too, and a message's presence on a branch is its delivery, so a message could read as delivered before it's taken (read). Optimization's tree merge has the same question. Optimization owns the choice: see task ``seon.db/a-branch-is-main-plus-its-own-changes``.

## Messages

A message is delivered to an agent's entity and stays there until the agent removes it, and the agent's pull and renders show what it holds, so there is no read or unread state and no special function (owner, 2026-11-07). Built: this section describes what runs (`seon.agent-test/one-message-reaches-two-agents`, `a-removed-message-is-not-delivered-again`, `a-program-pull-leaves-messages-alone`, `a-message-through-removal-merge-and-restart`).

- **One entity:** ``:seon.message/from`` (an agent's key, `"root"` or the user's name), ``:seon.message/to``, a set of refs to the recipients' agent entities (by key, `[:seon.agent/key k]`; indexed), ``:seon.message/sent-ms``, recorded by root's writer at the send, and ``:seon.message/body``, with no kind (a verdict or a stage boundary is a message from root), at an issued eid (``seon.task/message-tx``). Its attributes are what an agent sorts and filters on at a REPL: ``(sort-by (juxt :seon.message/sent-ms :db/id) (vals (:messages @ctx)))``. Time is recorded and only orders; nothing decides by it. A forward set on the agent was rejected: every `*` pull of that agent then carries them all (43 ms and 158 KB of text at 10,000, lab).
- **Delivered:** a send writes the message on main (``seon.root/message-write``: one transaction, a person's message and an agent's send alike, to an agent that has no process too). An agent's next act (``seon.agent/deliver!``: a turn's, ``seon.turn/deliver!``, or its code's ``(messages)``) writes the messages main holds to it, and its branch does not, on its branch, whole except for `to`, which names this agent only: a seek of `:seon.message/to` on its entity (``seon.task/undelivered``, never a scan of the messages), O(the messages to it). Main is read in that one place (``seon.agent/undelivered``, through ``seon.agent/heard-main``), so where messages live is one change. No fact says it read one, and a model call names none (``:seon.message/call`` places a message in the turn that reads it).
- **Removed:** ``(swap! ctx update :messages dissoc e)`` retracts this agent's own ref. The entity and other recipients' refs stay. It isn't delivered again, because the entity is on the branch, and the model no longer sees it. The message's render carries that form.
- **A removal stays on the agent's branch:** a merge compares against the merge base, which never held the ref, so main still names it. A program pull (``seon.agent/merge-main!``) brings the program, never another agent's messages. Going Back to an earlier point delivers what came since again.
- **Chat:** text that isn't code is a message, a reply included, so one render covers both sides: from me is role `assistant`, anything else `user`. A reply names the model call that made it, which is what it came from and says nothing of reading. Order for the model is the order messages reached the branch, so its history only grows (``seon.context/first-delivered``: the transaction that put this agent's ref on each). A person's message and an agent's reply run through the real chat render (a browser and a real model, 2026-11-07); a reply as a message with a `to` isn't built (``seon.agent/a-reply-is-a-message-with-a-to``).
- **Waking** is a Flow channel's, never a watch on commits (owner, 2026-11-07). When root's writer has answered a message's commit, the api puts one ``{:agent key}`` per recipient on the reconciler's `:wake` port (``seon.root``'s `back-op`; `resources/seon/system.edn`). The reconciler (``seon.reconcile/wake``) holds a woken agent in its index (`:woken`) if a task of it waits (parked, told or at its cap), which is what makes that task ready; a wake for an agent that is running does nothing then, and is held (`:late`) until the agent waits, when its branch is read against main (``seon.reconcile/settle-late``, one read of its messages) and it is woken only if a message is still not on its branch. Its start consumes the wake. A message an agent keeps (it is on its branch) never wakes it again, because nothing but a send wakes. At a boot there are no pending wakes, so the first listing reads, once, each waiting task's agent against main (``seon.reconcile/boot-wakes``: O(the waiting agents x the messages to each), a seek and a lookup apiece), so a lost wake costs delay, never a message. A message that lands after a wake was dropped for a running agent and before it parks is found at its park.
- **Turns:** a configured wake allowance, ``:seon.config.agent/wake-turns`` (10), is the main control of the agentic loop: the write that starts a woken agent raises ``:seon.agent/max-turns`` by it, whoever sent the message (``seon.task/start-tx``), so an agent at its cap runs again for a message too ([the cap](turn.md#the-cap)). An agent stops early by replying with no evals (`evals` empty or missing, as plain text with no call). An address always means that agent, so a message extends it, and a fresh agent is ``my.task/launch!``. Two agents answering each other are each woken by every message and each spend the allowance per wake; nothing else bounds them, so a total limit is undecided.

## What's on screen

What a person sees is a reflection of the database: a click or a URL writes a fact, the commit causes the re-render, and the patch sets the address bar too, so no UI state is held in a process. Today's isn't: the open sections, the turn in view and "more" are in ``seon.render.proc``'s memory and go on a restart, and the URL is the only thing that survives.

- **Each tab is an entity,** ``:seon.tab/user``, ``:seon.tab/view``, ``:seon.tab/open``. With one current view on the user, tab A would flip when tab B navigated. A tab reconnecting with its id gets everything back, and `/` opens the user's newest. Which tabs to ignore as they pile up is open.
- **`/` is whoever is signed in** (``:seon.root/signed-in`` on root, which boot sets to the OS account until there is a login), drawn as that user's page; with no one, a sign-in page (``seon.render.ui/sign-in``). Root's own page is `/root`; `/user/<name>` is a user's page by name.
- **A user's page is a kept walk,** as root's is: a move of main draws what it reached, so an agent started from it appears with no reload (``seon.render.proc/user-walk``).
- **What a person types** is its words and its forms, told apart by the one reader (``seon.reader/words-and-forms``): words a message, forms its REPL's. An agent with a task can be spoken to with no process, by its key.
- **It already works for a user-shaped page:** an agent with no branch is read from main with its reads tracked (``seon.render.proc``). A fact on main redrew that page 29 times of 30, median 34 ms, max 52 ms, about 430 bytes a patch, with no code added (lab, one run, under load of about 7, the tag built at runtime so no eval's code could hold it). What a view with a user's parts around an agent's branch does is untested.
- **It isn't automatic everywhere:** a live agent's page draws itself, so a fact on main like ``:seon.agent/opened`` shows only at its next turn, and no patch came in 5 s (lab, 30 tries). Root's page sent nothing per fact (lab, 20 sends of an attribute it doesn't draw).
- **A click costs main a commit:** about 24 ms of CPU across a root's processes (lab: 150 facts, 3.58 s against 0.09 s idle over 30 s, with no agent working), and no bytes on another agent's stream. A running agent reads main as moved after one, and its turn's merge check stays about 3 ms up to 5,000 unmerged facts, since they aren't program (lab, three runs each). Nothing like a hover should write. Not measured: many agents working at once.

## When a definition changes under data

A definition's change and the data it leaves behind are one merge result, so a branch either fits or says what doesn't, and it can't merge up until it does.

What happens today (lab): a type change is refused where the attribute is stored; main narrowing ``:int`` to ``[:int {:min 100}]`` merges clean and leaves ``3`` unreported; narrowing on your own branch reports 4 of 4 refused and registers anyway.

- For each schema key a merge moved or a registration changed, read that attribute's values (``usage-check``). Each that no longer fits becomes an unmerged entry, which the status and done rule already handle.
- A definition can carry its migration, a program var old to new, applied first, so easy cases cost no agent.
- What can't be migrated is a new attribute, as a type change already is. Widening needs nothing.
- Untested: a required key added to an entity schema, and a real agent resolving these from its status alone.

## Correct

A branch is correct when, read on its value alone, every datom fits its schema, every form loads and has its contract, and what's unmerged is listed as data.

A commit is one transaction, so a branch is never half-merged, and one behind main is a consistent older value that knows it's behind. A pull brings a schema and the functions using it in one commit, and loads changed forms into a live process in the same step: a live function returned 1, its agent could see main had moved, and it returned 2 only after merging (lab).

## The steps

The steps below merge one at a time, each usable alone, with today's agents unchanged by proof, and each says what it deletes: no step adds a mechanism beside one that exists (owner, 2026-11-07). Each has its task entry, or gets one once its shape has been tried at the REPL.

1. Done: the write check holds a value to its branch's schema, and a merged schema entry brings its attribute's declaration.
2. Done: an eval's result is one entity per value on every branch, and an eval's id is issued by the store. Two shapes remain, a whole old response that ``seon.db/response-of`` still reads and ``:seon.eval/id`` repeating the eid; how they go is Optimization's call, with how results are stored (owner, 2026-11-07).
3. Messages, Web's next: one send, delivery and removal by data, a wake rule; the first, one send as one fact on main, is done, and the two tasks after it remain. Functions that mutate behind a read-looking name go with them, by the owner's ruling: task ``my.agent/no-function-does-what-ctx-can``.
4. Users, and input through ``forgive``.
5. Sessions and merging them.
6. The home page from the user's ``@ctx``, with layouts as data and tabs as entities.

Merges are Optimization's, because its tree merge may change them (owner, 2026-11-07): ``seon.merge/a-branch-holds-its-merge-bases-as-facts`` and ``seon.merge/a-merge-takes-options`` wait on it. As written they'd add a function and facts beside what exists, three merge entry points (``seon.agent/merge-main!``, ``seon.writer/branch-merge``, ``:merge-candidate``), ``:seon.agent/base-commit`` and six walks of the commit graph; whatever lands replaces those. Sharing goes with it, on ``seon.db/a-branch-is-main-plus-its-own-changes``. So far the tree merge shares about as well as a rebase, costs far less at a large delta, and rewrites no history (Optimization, prototype). Steps 4 and 5 need the two merge cases above, so they build on today's merge only where that's one small change.

Out for now: one user's renders drawing another's agents, login, teams. Depends on Context's ``seon.source/seon-files-move-by-branch`` (scripts landed, config not yet), and on Optimization's digest work for what's reused across branches. There's no hard boundary between data and forms: one db, one tree; what changes on its own schedule is its own entity, by ref (owner, 2026-11-07).

## Tasks and proofs

Tasks are small and their proofs are ours, written and watched red before release, so that a Seon agent or a Claude agent over MCP can work them (Context, from real tasks; a small sample).

- One behaviour, one place, an exact reproduction with what it answers now and what it should, under about 600 characters, saying what's out of scope. A task needing a design decision isn't an agent's task yet.
- The agent may add proofs: ours is the floor. Proofs stated on data with exact values, on small worlds, with no process where the claim allows.
- One process test per mechanism, written last. What changes what's running splits into a pure part and a live part in a worktree.
- A generative suite comes after the first merge tasks: random merge, pull, rebase, cherry-pick and schema-change sequences checked against invariants, shrinking to the op sequence that broke. A "no problems" check is an extra assertion, never the only proof.

## Known gaps, from an independent audit (2026-11-08)

Read from the code, not run; each has a task.
- A verdict a root writes doesn't wake its agent: only the api's sends wake, so `:told` waits for a restart. Root's own message writes need the same wake (``seon.agent/every-message-root-writes-wakes``).
- ``seon.reader/words-and-forms`` reads repairs' offsets, which closers inserted earlier shift: `"(foo\nhello"` loses the words. `scan` knows each line's kind and should answer it (``seon.reader/words-and-forms-reads-the-scans-kinds``).
- The user has gained special cases in six places (`earlier-agents-tx`, `write`, `tell-tx`, `agent-view`'s input, `users-side`, `user-walk`), which says the model isn't finished. `user-walk` is a third walk keeper beside `root-walk` and `branch-walk!` (``seon.render/a-users-walk-is-a-branch-walk``).
- ``seon.root/person`` reads the OS account outside the config loader, and `ask!` falls back to it when an agent's key isn't found: a fake default.
- `:lost` reads "stopped by a restart" also when its parked process died; the task's reason should give the words.
- ``seon.reconcile/after-work`` skips tests only for tasks with no proofs; if a park claims nothing, it never should.
- ``seon.db/response-of`` glues a result back onto its eval for every reader; a pull pattern joining `:seon.eval/result` would replace it.
- Tests whose names claim more than they assert: the break test sets up no restart, and its merge check can't fail on messages, since a merge never carries them (that, not the merge base, is why a removal stays on a branch); `two-agents-answering-each-other-end` ends because the test stops sending.
