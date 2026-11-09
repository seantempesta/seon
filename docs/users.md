# Users

The design for users, worlds within worlds: not built yet, and the one place the three sessions read it from; each claim says whether it ran (lab), was read from code (read) or is untested.

A user is an agent, a session is a branch of its user's branch, and the system is one mechanism used in different data configurations. State is always in the database and the system is a function of that state: there are no runs and no objects, only entities, attributes and refs. Owner: the Web session. Words are git's and Clojure's ([vocabulary](vocabulary.md)).

## One system of agents: the design (agreed 2026-11-09)

Nothing here is a new concept: every part of Seon is the same few pieces, and what makes one part differ from another is its data and its pull (owner, 2026-11-09: "We are designing one system for agents that we are re-using everywhere"; "make sure you repeat back to me the plan on how we are NOT INVENTING ANY NEW CONCEPTS"). Agreed by the Web, Context and Optimization sessions; the stages and who builds each are [below](#the-stages-agreed-2026-11-09).

**The pieces, reused everywhere:**
1. **An entity:** an id, its attributes and its refs, each ref naming what it points at (`:seon.db/to`, [data modeling](data-modeling.md#things-relate-by-refs)); one schema registry; every entry optional unless essential, what is there validated.
2. **An agent:** an entity with a key, its own branch and a process forked from the fork server, running its Flow graph (transport, REPL, tests). The user, root and every working agent are this; none is a separate kind of process.
3. **A pull pattern on the entity** (`:seon.ctx/pattern`), giving `@ctx`: the agent's context and its page, keyed by attributes (no `:as`, no `*`), its joins the ones its refs imply (```seon.db/implied-pattern```).
4. **A render:** a function of data (and the db) answering hiccup, named by the entity's schema (`:seon.render/hiccup`) or the value's own key; it may call other renders, composed. The walk (```seon.render/walker``` over ```seon.ratom```) reruns only the components whose `@ctx` paths or db reads moved. There are no layout functions: an entity is drawn by its render, a joined entity by its own. The model walks the same hiccup; model renders recognized from specs in it answer the messages: the elements a model render answers carry the marks the request reads (`:wire/role`, `:wire/call-id`, `:wire/field`, `:wire/ref`, `:wire/turn`), and an element for the person alone carries `:wire/for "page"`, so merging an entity's two renders into one keeps them, and test/seon/recorded_requests.edn holds the request byte for byte.
5. **Messages and requests** over the agent's socket and Flow channels: the existing `:render` and `:eval` requests.
6. **One merge function**, branch to main and back, its options syncing or private ([sharing](sharing.md)).
7. **One reconciler**, desired state against observed: it starts, parks and restarts agents.

**What makes each part differ is its data and its pull:**
- **The user** is an agent with a branch, like any other (owner, 2026-11-09: "User should have a branch just like the other agents"), and always a process (owner: "I want the user to have a process regardless. Anything that's user focused should be handled by this agent too so separate users don't take down the system"). Its pull reaches itself, root and the agents it started (`:seon.agent/agents`, their main half: status, task, messages). Its tree is read only: it changes nothing of another agent's, it sends requests, and agents change their own branches (owner: "The user isn't modifying anything it's sending the requests to agents"). Its walk is the person's whole view. It holds, beside `@ctx`, the renders it asked its agents for, in a ratom of its own diffed as ctx is (```seon.ctx/changes```), read through a view so each component reruns on its own agent's paths. For now it only renders and serves; a model that reads the whole view and routes everything the person types is later (owner: "in a future version we might want to have that agent do routing of all requests").
- **Root** is the agent keyed `"root"`, an ordinary agent working its task `request/root`; its pull reaches the sessions it started.
- **A working agent's** pull reaches its task, its evals, model calls, messages, cites and searches; its render is its page and its model's context. It renders its own context in its own process, by its own definitions: asked `{:op :render :since t}`, it answers its whole expanded hiccup (every render call run there, so the answer holds no var or fn head, each component's element carrying its key-id and its walk revision as `data-rev`). A `:render` proc of its own answers while its REPL waits on a model call; the REPL stays the walk's one flusher; a render reads the db at ctx's own point; each render goes on a sliding buffer of one, so only the newest is held; an ask is the credit, so with no ask nothing renders (owner: "since we are sending everything every time it's okay to drop intermediate renders").
- **The system entity** (`:seon.system/*`, today `{:seon.root/id :root}`): boot, config, quiescence; data root's procs read, not an agent.
- **Root's process** is the go-between (owner: "root I think is mostly the go between for flow communication and isn't doing anything that can allow an agent or the user to take down the system"): the writer, the reconciler, the fork server, the web transport (html and Datastar patches from the user agent's hiccup, a malformed answer an error on that user's page) and the merge gate. It runs no render of an agent or a user.

**Gone, because each was a special case:** root's render walks of pages (`root-walk`, `user-walk`, `branch-walk!`, the cold draw); every layout, ```seon.render/layout-of``` and `:seon.render/layout`; an entity's second render; root's session as a thing apart (```seon.root/root-session-pattern```, its refresh, the special message path); `:seon.agent/root`; an opened eval written on main (opening is an eval with origin `:context` the user agent sends the agent); `:as` and `*` in patterns.

## What each thing is

A thing is its data structure: any entity holding the shape is that thing, however it got there, and where each part is stored is then ours to choose.

- **User:** an agent entity holding ``:seon.user/name``, its lookup ref and what makes it a user; until there is a login, boot writes one named for the OS account running root, keeping one entity across boots. Today on main with no branch; by the design above it gets a branch and a process like any agent. Same key, same entity: login decides later who may be ``sean``.
- **Who started what:** a task names who started it (``:seon.task/started-by``, the person asking or the agent launching it), and its agent, when it starts, joins that one's ``:seon.agent/agents``. Ended tasks tell them how they stand.
- **Who points at what:** ``:seon.agent/agents``, a set of refs on the user, on main. Many users can point at one agent, and any agent can hold the same refs to those it started; there is no owner attribute; removing a ref keeps its history. Windows over it must order by when each joined, since agent ids are content-derived (lab: the newest fell out of a window of 20 at 1,000).
- **Session:** an agent on a temp branch of its user's branch.
- **Eval:** an entity attached to its agent: code, intent, and ``:seon.eval/response``, what is about that evaluation (its handle, ``:ms``, ``:ticks``, ``:bytes``, ``:ns``, ``:warnings``), and ``:seon.eval/result``, a ref. Built: ``seon.db/response-of`` answers the whole response as every reader has always had it. Its id is `"e<eid>"`, issued by the store before it runs, uniquely across branches (built, ``seon.db/take-eid``). Evaluating again (``seon.repl/again``) moves the ref, and the old result stays (``seon.repl-test/an-eval-evaluated-again-moves-its-result-ref``).
- **Result:** one immutable entity per value, the same on every branch (built, ``:seon.result/result-fact``): ``:seon.result/hash``, the full hash as identity and content id, the lookup ref; ``:seon.result/value``, ``:ex``, ``:out``, ``:err``, ``:forms``, written by ``seon.db/with-response``. The wire keeps the short handle, its first eight digits: the identity must be the full hash, or two values sharing a prefix would become one entity. An answer not kept as it is (a host object, a value past the bound, several forms) is hashed with its eval's id, as its handle always was, so its result is its eval's own. A nil value is a result with no value and no error; an eval that threw holds a result with ``:ex`` and no ``:value``. Proof: ``seon.repl-test/two-evals-of-one-value-hold-one-result``; what the model reads is unchanged: ``seon.ctx.wire-test/what-the-model-reads-is-the-same-with-result-entities``.
  A content-id result has the same eid on two branches, and two sessions producing one value merge into one entity with no conflict, each eval pointing at it (lab). A new small result costs 50% more datoms than inline and about 38% more time; re-using one costs the same as inline (lab, 300 evals, one run). Whether the small case is worth an entity is open: a value below the out-of-line size could stay on the eval, at the cost of two shapes.
- **Message:** one entity, read by everyone it's to: ``:seon.message/from``, ``:seon.message/to``, a set of refs, ``:seon.message/body``, at an issued eid. Built: see [Messages](#messages).
- **Tab:** one entity per browser tab, holding what that tab shows. Not built: see [What's on screen](#whats-on-screen).
- **Layout:** a data structure naming existing components, on its own key, as root's already is. Functions remain for what data can't express.

## Where each fact lives

Each fact lives where the readers who need it find it, and that placement is what removes guards and what makes reads lazy.

- **Main:** that an agent or user exists, its branch and upstream, who points at what, and messages addressed to a branch. Delivery is the fact.
- **A branch:** evals and a person's data and functions.
- **Every branch alike:** content-id entities, forms, results and messages, so forks share them and they can't conflict.
- **Merge bases:** one, from the commit graph. A partial merge recorded as a parent loses no data, tested through ```seon.merge/carried-tx```, which carries only retractions its source wrote; docs/sharing.md#where-a-fact-lives says what it does get wrong.
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
- Rebase isn't proved safe: it sits on main's whole head, so it takes main's platform records too, and a message's presence on a branch is its delivery, so a message could read as delivered before it's taken (read). With content-addressed nodes a plain merge makes the same nodes a rebase would ([sharing](sharing.md#what-a-branchs-nodes-are)), so neither a tree merge nor a rebase is needed for sharing; Optimization owns the choice.

## Messages

A message is delivered to an agent's entity and stays there until the agent removes it, and the agent's pull and renders show what it holds, so there is no read or unread state and no special function (owner, 2026-11-07). Built: this section describes what runs (`seon.agent-test/one-message-reaches-two-agents`, `a-removed-message-is-not-delivered-again`, `a-program-pull-leaves-messages-alone`, `a-message-through-removal-merge-and-restart`).

- **One entity:** ``:seon.message/from`` (an agent's key, `"root"` or the user's name), ``:seon.message/to``, a set of refs to the recipients' agent entities (by key, `[:seon.agent/key k]`; indexed), ``:seon.message/sent-ms``, recorded by root's writer at the send, and ``:seon.message/body``, with no kind (a verdict or a stage boundary is a message from root), at an issued eid (``seon.task/message-tx``). Its attributes are what an agent sorts and filters on at a REPL: ``(sort-by (juxt :seon.message/sent-ms :db/id) (vals (:messages @ctx)))``. Time is recorded and only orders; nothing decides by it. A forward set on the agent was rejected: every `*` pull of that agent then carries them all (43 ms and 158 KB of text at 10,000, lab).
- **Delivered:** a send writes the message on main (``seon.root/message-write``: one transaction, a person's message and an agent's send alike, to an agent that has no process too). An agent's next act (``seon.agent/deliver!``: a turn's, ``seon.turn/deliver!``, or its code's ``(messages)``) writes the messages main holds to it, and its branch does not, on its branch, whole except for `to`, which names this agent only: a seek of `:seon.message/to` on its entity (``seon.task/undelivered``, never a scan of the messages), O(the messages to it). Main is read in that one place (``seon.agent/undelivered``, through ``seon.agent/heard-main``), so where messages live is one change. No fact says it read one, and a model call names none (``:seon.message/call`` places a message in the turn that reads it).
- **Removed:** ``(swap! ctx update :messages dissoc e)`` retracts this agent's own ref. The entity and other recipients' refs stay. It isn't delivered again, because the entity is on the branch, and the model no longer sees it. The message's render carries that form.
- **A removal stays on the agent's branch:** a merge compares against the merge base, which never held the ref, so main still names it. A program pull (``seon.agent/merge-main!``) brings the program, never another agent's messages. Going Back to an earlier point delivers what came since again.
- **Chat:** text that isn't code is a message, a reply included, so one render covers both sides: from me is role `assistant`, anything else `user`. A reply names the model call that made it, which is what it came from and says nothing of reading. Order for the model is the order messages reached the branch, so its history only grows (``seon.ctx/first-delivered``: the transaction that put this agent's ref on each). A person's message and an agent's reply run through the real chat render (a browser and a real model, 2026-11-07); a reply as a message with a `to` isn't built (``seon.agent/a-reply-is-a-message-with-a-to``).
- **Waking** is a Flow channel's, never a watch on commits (owner, 2026-11-07). When root's writer has answered a message's commit, the api puts one ``{:agent key}`` per recipient on the reconciler's `:wake` port (``seon.root``'s `back-op`; `resources/seon/system.edn`). The reconciler (``seon.reconcile/wake``) holds a woken agent in its index (`:woken`) if a task of it waits (parked, told or at its cap), which is what makes that task ready; a wake for an agent that is running does nothing then, and is held (`:late`) until the agent waits, when its branch is read against main (``seon.reconcile/settle-late``, one read of its messages) and it is woken only if a message is still not on its branch. Its start consumes the wake. A message an agent keeps (it is on its branch) never wakes it again, because nothing but a send wakes. At a boot there are no pending wakes, so the first listing reads, once, each waiting task's agent against main (``seon.reconcile/boot-wakes``: O(the waiting agents x the messages to each), a seek and a lookup apiece), so a lost wake costs delay, never a message. A message that lands after a wake was dropped for a running agent and before it parks is found at its park.
- **Turns:** a configured wake allowance, ``:seon.config.agent/wake-turns`` (10), is the main control of the agentic loop: the write that starts a woken agent raises ``:seon.agent/max-turns`` by it, whoever sent the message (``seon.task/start-tx``), so an agent at its cap runs again for a message too ([the cap](turn.md#the-cap)). An agent stops early by replying with no evals (`evals` empty or missing, as plain text with no call). An address always means that agent, so a message extends it, and a fresh agent is ``my.task/launch!``. Two agents answering each other are each woken by every message and each spend the allowance per wake; nothing else bounds them, and none is built until two agents are seen doing it (owner, 2026-11-08).

## What's on screen

What runs today, which stages 1b, 2a and 4 of [the stages](#the-stages-agreed-2026-11-09) replace: root's render proc walks the user's page and root's, and draws an agent's from its branch.

What a person sees is a reflection of the database: a click or a URL writes a fact, the commit causes the re-render, and the patch sets the address bar too, so no UI state is held in a process. Today's isn't: the open sections, the turn in view and "more" are in ``seon.render.proc``'s memory and go on a restart, and the URL is the only thing that survives.

- **Each tab is an entity,** ``:seon.tab/user``, ``:seon.tab/view``, ``:seon.tab/open``. With one current view on the user, tab A would flip when tab B navigated. A tab reconnecting with its id gets everything back, and `/` opens the user's newest. Which tabs to ignore as they pile up is open.
- **`/` is whoever is signed in** (``:seon.root/signed-in`` on root, which boot sets to the OS account until there is a login), drawn as that user's page; with no one, a sign-in page (``seon.render.ui/sign-in``). Root's own page is `/root`; `/user/<name>` is a user's page by name.
- **A user's page is the sessions they started, as tiles** (owner, 2026-11-08: "Make them tile nicely with enough of a size that you can tell what's going on and which session the user want to dive into"; a feed of every message, newest first, only grew and repeated each chat out of place): ```seon.render.ui/started-cards``` groups them by their state in plain words, parked (a message wakes it: open the session and type), then working, then finished folded (owner, 2026-11-08: "Just show the status as parked") (```seon.render.ui/sessions-by-standing```, which root's work list draws too), each a ```seon.render.ui/session-card```: what it works on (the first line the person typed, when a person started it; else its task's name), how it stands, and the last exchange: the person's newest message to it, then the agent's answer to it (the task's reason, which root's message carries, shown only when written after that message, by transaction, so an answer to an earlier question never sits under a later one; owner, 2026-11-08: an atom tile showed a paragraph on agents), drawn as Markdown and faded past about twelve lines, at most two tiles across. The whole tile is a link to its chat. The messages stay on the user's entity; the page draws none of them as a list (`seon.render-test/a-users-home-is-tiles-by-what-needs-them`, `seon.render-test/a-tile-shows-the-last-exchange`).
- **A user's page is a kept walk,** as root's is: a move of main draws what it reached, so an agent started from it appears with no reload (``seon.render.proc/user-walk``).
- **What a person types** is its words and its forms, told apart by the one reader (``seon.reader/words-and-forms``): words a message, forms its REPL's. An agent with a task can be spoken to with no process, by its key.
- **It already works for a user-shaped page:** an agent with no branch is read from main with its reads tracked (``seon.render.proc``). A fact on main redrew that page 29 times of 30, median 34 ms, max 52 ms, about 430 bytes a patch, with no code added (lab, one run, under load of about 7, the tag built at runtime so no eval's code could hold it). What a view with a user's parts around an agent's branch does is untested.
- **It isn't automatic everywhere:** a live agent's page draws itself, so a fact on main like ``:seon.agent/opened`` shows only at its next turn, and no patch came in 5 s (lab, 30 tries). Root's page sent nothing per fact (lab, 20 sends of an attribute it doesn't draw).
- **A click costs main a commit:** about 24 ms of CPU across a root's processes (lab: 150 facts, 3.58 s against 0.09 s idle over 30 s, with no agent working), and no bytes on another agent's stream. A running agent reads main as moved after one, and its turn's merge check stays about 3 ms up to 5,000 unmerged facts, since they aren't program (lab, three runs each). Nothing like a hover should write. Not measured: many agents working at once.

## Root's session

What runs today, which stage 3 of [the stages](#the-stages-agreed-2026-11-09) replaces: root becomes the agent keyed `"root"` itself, and the system entity holds what root's entity holds now.

**Root's session is an ordinary agent a person talks to Seon through** (owner, 2026-11-08: "root should be it's own agent session with it's own custom context"; "a valid agent just like any other agent, it just has some different additional context and links"; "DO NOT MAKE A PARALLELL SYSTEM FOR ROOT"). It is an agent entity keyed `"root"`, a reserved key as the user's is their name, linked to root by `:seon.agent/root` as every agent is, and it works the task `request/root`, made from the chat template. The first message to root makes both, parked, in the same write, and that write's wake spawns it (```seon.root/root-session-tx```, in root's api's `:message`; owner, 2026-11-08: made at the first message, so a root no one talks to, every test root among them, has none). Later messages wake it as one wakes any waiting agent, and the ordinary spawn continues it by its key (``seon.task/session-key``); each boot writes its pattern again when it has one (```seon.root/root-session-refresh-tx```). It is a normal agent that tracks main as the others do (owner, 2026-11-08): its record is on its own branch, and it reads main's sessions at the point its turn heard (`:seon.agent/main-commit`); working on main itself would make it a second writer of main and put its every turn on main (Optimization's measure). What makes it root's is data alone: its own `:seon.ctx/pattern`, the agent's with a join to root's sessions, and its task's plan, which says what it is for. Its task list shows those sessions (```seon.render.ui/sessions```: each by its address, what it works on and how it stands), so it knows what is already being done.

What it does, for now: it answers the person itself when it can. When they want work done, it looks for a session already on it and gives them that session's address (`/agent/<key>`); otherwise it starts one with `(my.task/launch! "what they want")`, which tells root's session how that work ends. Root's own entity (`:seon.root/id`) is untouched: the store derives an entity's id from its identity, so root's entity cannot also hold an agent's key. The evals Claude sessions send root's REPL are recorded on root's entity, never on its session's, so they never reach its conversation (`seon.render-test/roots-session-is-an-agent-of-its-own`, `roots-session-reads-its-sessions`).

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

## The stages (agreed 2026-11-09)

The design above lands in stages, each merged alone with main green, each saying what it deletes; a stage starts when what it needs has merged (owner, 2026-11-09: "We've talked about a lot of ideas so we need to carefully stage it and agree on dividing up the work"). One writer per function at a time: a session names the functions it will change before building, by message; Context reviews what changes what the model reads, Web what a person sees, Optimization the data model, merges and boot.

0. **Done: each ref names what it points at** (Web, 4531d84d48): `:seon.db/to`, ```seon.db/targets```, ```seon.db/reaching```, ```seon.db/implied-pattern```.
1. **In parallel, each needing only stage 0:**
   - a. **A context is keyed by attributes** (Context; task ``seon.ctx/a-context-is-keyed-by-attributes``): no `:as` on a ref join and no `*` in any pattern, every reader moved to the attribute; the renders stage 2 replaces renamed mechanically; root's patterns excepted until stage 3; the docs' paths moved in the same commit.
   - b. **An agent renders its own context, and root carries it** (Optimization the agent side, Web root's, one merge; task ``seon.repl/an-agents-process-renders-data-to-hiccup``): the `:render` proc, `:since`, the whole expanded hiccup, `data-rev`, an ask's take-and-flush; root serves html from it; `branch-walk!`, the cold draw and the units answer go.
   - c. **The boot race** (Optimization): Jolt's main carrier, fixed in our fork (task ``seon.check/a-forked-test-child-died-stuck``).
   - d. **Boot resumes from the db; files are written, never read** (Optimization; task ``seon.root/a-boot-resumes-from-the-db``; owner, 2026-11-09: "Boot's job is to resume the existing system or if it's fresh to index it and get it to the point that everything is running off the db. From that point on we are syncing data TO files not reading from them"): a running system runs off the db and writes files from it, and a boot resumes from the db and reads no file and no git; indexing from files is an explicit act a person chooses, seeding a fresh store (```seon.root/seed-program!```) or re-indexing one on purpose, never something a boot or a running root does by itself (owner: "Of course we can re-index if we want. But I mean as a functioning system concept"); root's following of the checkout (the watcher of ```seon.root/import-source!```) goes; a binary built from another program than main's is a problem fact, never imported to catch up.
2. **After 1a and 1b:**
   - a. **One render per entity schema** (Web): layouts deleted (```seon.render/layout-of```, `:seon.render/layout`, the page layouts and their lists); each entity drawn by its schema's render, which leaves each joined entity to its own; an entity's second render merged into one; renders written for a cite, a doc section, a search, a result and a var with its verdict; a bare `{:db/id}` drawn as a link to where the entity is, never again; eval tiles: an eval whose value has a render is its result's render in the sidebar.
   - b. **Model renders from the hiccup** (Context, with 2a): the model walks the page's hiccup and model renders recognized from specs in it answer the messages; each turn's diff is its model call's model render, between its `:seon.model-call/basis` and the previous call's (task ``seon.render/a-model-render-draws-each-turns-diff``).
3. **The system entity and root as an agent** (Optimization the store and boot, Web root's session code; task ``seon.root/the-system-entity-and-root-as-an-agent``; one store erase, the other sessions warned): `{:seon.root/id :root}` becomes `:seon.system/*`; root is the agent keyed `"root"` made with the user's link to it; root's patterns lose their aliases; `:seon.agent/root` goes, liveness read from `:seon.agent/boot`.
4. **The user agent, rendering only** (Optimization its lifecycle and branch, task ``seon.agent/the-user-agent-lives-on-its-own-branch``; Web its walk and root's transport of it, task ``seon.render/the-user-agent-renders-the-users-view``): always a process, kept by the reconciler, counted in max-running and exempt from task admission, on a branch of its own; its read-only walk of its pull, holding the renders it asks its agents for; root serves its hiccup; opening an eval is an eval it sends the agent, and ```seon.root/opened!```, `:seon.agent/opened` and ```seon.agent/untaken-facts``` go. Two agents whose patterns reach each other each answer with the other's last render.
5. **A session syncs with main unless private** (Optimization; task ``seon.merge/a-session-syncs-with-main-unless-private``): independent of 1-4, open questions with the owner below.
- **Later, not now:** the user agent's model, reading the whole view and routing what the person types; login and other users.

Open with the owner: whether a person opening an eval on a working agent changes what that agent's model reads (it does if the opened state is in the hiccup the model walks) or stays page-only (Context's pick: page-only, `:wire/for "page"` on the opened body); how a Claude session's landing enters the db once root reads no files (recommended: `bin/land` submits the landed change to root as data, as an agent's `edit!` does, and root merges it into main and writes the files back; else sessions edit through the db as Seon agents do); a cite's target, a value or a ref (stage 1a keeps `:by` joins named until then); when a syncing session's data goes up, each turn or the task's end; who sets a session private; whether opening an eval on a working agent may wait for its run's request (else its REPL takes `:context` evals at its parks).

Done before these stages: the write check holds a value to its branch's schema; an eval's result is one entity per value, its id issued by the store; one send of a message as one fact on main; tabs as entities dropped (owner, 2026-11-08).

Merges are Optimization's, because its tree merge may change them (owner, 2026-11-07): ``seon.merge/a-branch-holds-its-merge-bases-as-facts`` and ``seon.merge/a-merge-takes-options`` wait on it. As written they'd add a function and facts beside what exists, three merge entry points (``seon.agent/merge-main!``, ``seon.writer/branch-merge``, ``:merge-candidate``), ``:seon.agent/base-commit`` and six walks of the commit graph; whatever lands replaces those. Sharing is content-addressed nodes ([sharing](sharing.md#what-a-branchs-nodes-are)). So far the tree merge shares about as well as a rebase, costs far less at a large delta, and rewrites no history (Optimization, prototype). Steps 4 and 5 need the two merge cases above, so they build on today's merge only where that's one small change.

Out for now: one user's renders drawing another's agents, login, teams. Depends on Context's ``seon.source/seon-files-move-by-branch`` (scripts landed, config not yet), and on Optimization's digest work for what's reused across branches. There's no hard boundary between data and forms: one db, one tree; what changes on its own schedule is its own entity, by ref (owner, 2026-11-07).

## Tasks and proofs

Tasks are small and their proofs are ours, written and watched red before release, so that a Seon agent or a Claude agent over MCP can work them (Context, from real tasks; a small sample).

- One behaviour, one place, an exact reproduction with what it answers now and what it should, under about 600 characters, saying what's out of scope. A task needing a design decision isn't an agent's task yet.
- The agent may add proofs: ours is the floor. Proofs stated on data with exact values, on small worlds, with no process where the claim allows.
- One process test per mechanism, written last. What changes what's running splits into a pure part and a live part in a worktree.
- A generative suite comes after the first merge tasks: random merge, pull, rebase, cherry-pick and schema-change sequences checked against invariants, shrinking to the op sequence that broke. A "no problems" check is an extra assertion, never the only proof.

## Known gaps, from an independent audit (2026-11-08)

Read from the code, not run; each has a task.
- The user has gained special cases in six places (`earlier-agents-tx`, `write`, `tell-tx`, `agent-view`'s input, `users-side`, `user-walk`), which says the model isn't finished. `user-walk` is a third walk keeper beside `root-walk` and `branch-walk!` (``seon.render/a-users-walk-is-a-branch-walk``).
- ``seon.root/person`` reads the OS account outside the config loader, and `ask!` falls back to it when an agent's key isn't found: a fake default.
- `:lost` reads "stopped by a restart" also when its parked process died; the task's reason should give the words.
- ``seon.reconcile/after-work`` skips tests only for tasks with no proofs; if a park claims nothing, it never should.
- ``seon.db/response-of`` glues a result back onto its eval for every reader; a pull pattern joining `:seon.eval/result` would replace it.
- Tests whose names claim more than they assert: the break test sets up no restart, and its merge check can't fail on messages, since a merge never carries them (that, not the merge base, is why a removal stays on a branch); `two-agents-answering-each-other-end` ends because the test stops sending.
