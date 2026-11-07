# Sharing

A branch is main plus its own changes: an index is a tree of immutable nodes, so branches that hold the same datoms point at the same nodes, and a result stands until something it read changes. Most of what this page describes is planned or prototyped, and each part says which. The plans are on tasks ``seon.db/a-branch-is-main-plus-its-own-changes``, ``seon.program/a-tests-digest-reads-what-it-ran`` and ``seon.agent/a-message-is-a-durable-mailbox-and-a-channel-only-wakes``.

## One tree, everything linked

There is no boundary between program and data (owner, 2026-11-07: "I want everything that is linked to be linked"). One db, one tree. Sharing comes from sort order: datoms that change together sort together. A fact that changes on its own schedule is its own entity with a ref to what it is about, as `git notes` attach to a commit, so it links without rewriting the leaf of what it is about. An eval's result is one entity per distinct value, by content id, and each eval refs it.

## The data model

**Findings since the proposal below, which they change.** Reviewed by the Web and Context sessions, gpt-6-astra and gemini-4-argon, with experiments, one each.

- **How bytes are shared and what a merge means are two questions.** Content-hashed nodes answer the first alone: the same datoms in the same shape are one node, whatever put them there. On this repository's real history through main, a user and an agent (13 program merges), 28 percent of EAVT, 9 of AEVT and 6 of AVET stayed private against 39, 52 and 86 with random keys, and the store grew 153 MB against 229. A read costs the same on all three branches. Boundaries by content, projected on those datoms, would leave 19.6, 3.9 and 0.5.
- **A merge that takes only part of a branch must record no parent.** A parent says "I hold all of this". Tested: s1 and s2 fork U; s1 merges D up; s2 takes a program-only merge from U with U as a parent, then merges up whole: its merge base holds D and it does not, so D reads as retracted and is lost. Git's word for taking part is cherry-pick, which records no parent. Three reviewers found this independently. So a whole merge records a parent, and a partial one records how far it took some other way.
- **What churns the program.** `:form/order` was 968 of the 1,494 datoms differing between an agent and main: one form inserted renumbers every later form in its file, on entities spread through EAVT by content id, and a merge ignores a form's place, so branches hold it stale. A form's place is a fact about its file, and should be one fact there.
- **A real merge costs 400 M ticks, not 1 M.** Median through both steps: `seon.merge/merge-tx` 273 M, `seon.merge/delta-three-way` 154 M, the write 76 M, `d/merge-base` 0.5 M. `carried-tx` diffs the whole db against its base, unfiltered, and keeps what it wants.
- **Open, not tested:** one db value composed of a branch's own datoms over another's, beneath Datahike's read interface, as an overlay. Pull, query and read tracking would need no change, and nothing is copied; a negative read and a card-one value shadowed across parts are where it would break.

A proposal for the owner's ruling, built from what the Web and Context sessions need; not built. It takes its shape from git, which already does this: one object store, a branch is a pointer, and a branch sees only what its tree links.

**Entities and refs.** A thing is an entity. Its id is issued once per store, or is a content id, so the same thing has the same id on every branch. A ref is valid on any branch.

**One question decides where a fact lives: can two branches legitimately hold different values of it?**

- **Yes: on a branch, and a merge moves it.** Forms, vars, files, config, tasks, a user's data, evals and their results, and the refs an agent holds. Isolated until merged. One merge function, `git merge`'s, with two options: everything that does not conflict, or the program only.
- **No: once, on the branch `:shared`, which no merge touches.** Messages, UI state, embeddings, and what the system records about an agent. Written through root's writer. Main stays the program and what was decided, and a click never moves it.

Each attribute has one home, stated on its schema, so it has one writer and a merge has nothing to conflict on. An entity can hold attributes in both places: an agent's turn on its branch, its budget on `:shared`.

**Reading.** A db value reads only its own datoms: `d/pull` through a ref to an entity it does not hold answers `[]`, and `d/datoms` lists the ref itself. A reader that wants both reads each from its own db value, or a `d/q` over `$branch $shared`. A shared entity that never changes reads the same at any later point, so a past request still rebuilds as it was sent. What changes on `:shared` needs the point it was read at.

**A message.** An entity on `:shared` with `:seon.message/from`, `:seon.message/to`, `:seon.message/body` and `:seon.message/sent-ms`. At its next act an agent does a `git pull` from `:shared`: the `:seon.message/to` datoms addressed to it since its merge base with `:shared` come onto its branch. That is what it holds. Removing one is one plain retract on its own branch, and the merge base has moved past it, so it never returns: no read state and no stored `t`. A dropped wake costs delay only, since the commit graph says what is new. A Flow channel wakes the process.

**What was tested,** on one scratch store with content-hashed nodes, one experiment each. 3 agents, 120 turns, a message to each and 5 evals on each branch every turn: EAVT 9.3 percent private against 21.5 with messages carried onto every branch, AEVT 10.0 against 18.4, AVET 3.1 against 3.0; a branch holds no other agent's message; main does not move. A ref followed costs 12 k ticks. Pull, with no state of its own: an unlinked message stayed gone through a later pull, a merge of main and a restart; a fork and its parent each kept their own; both merge bases held. A pull costs under 1 M ticks plus `d/merge-base`, which walks the commit graph, 15 k ticks a commit: 29.7 M at 2,000 commits. Datahike's docstring says it may be cached forever, and after a pull the base is the head just merged, so a process holds it in memory and walks only after a restart. That cache is not yet tried.

**What it costs.** A reader has to name `:shared`, and a `d/q` on a branch alone finds no message text. A message no ref reaches stays on `:shared` until a rule reclaims it, and collection must keep whatever any branch refs. Not tested: the writer's load on `:shared`, what a page reads to list what many branches hold, and how `seon.ctx` takes a second db value.

**The words,** from the Web session's survey, to fix when this is built: `:branch :db` meaning main; session for an agent, a branch or a chain of tasks; four ids of one eval; five names for how something stands, which become facts of what happened plus one function that reads them, with process state in Flow; `:seon.task/kind` as an enum, which become templates as maps; take and carry for what git calls pull and merge; and "user", in four senses.

## What a real branch spends its nodes on

Read from a copy of a real store of 5 agents' branches (about 225,000 datoms, one model's work on one main that moved little, no writes), comparing each branch's leaves with main's. By address, 4 to 27 percent of EAVT's nodes are private, and by content 2 to 25: these branches are young, so content-hashed keys barely change them, and drift is what those keys fix on a long-lived branch. What costs them is what they write: on the branch writing most, `seon.test` datoms were 5,416 of its 7,557 own, on 1,467 var entities, touching 1,278 of the 1,366 stretches of EAVT it touched at all; without them, 123. A test's facts sit on its var, which sorts by a content id among the forms, so one prove rewrites leaves all over the program. Nothing read most of them, so a branch now keeps only the facts worth keeping (```seon.proofs/facts-worth-keeping```), an estimated cut from 25 percent to about 6 on that branch, not yet measured on a real branch. A result placed by hash is small beside this: 42 to 66 entities, and 44 to 69 stretches.

## A process catches up to a db value

A proposal, tested as far as this says, not built. All a process holds that comes from the db is derived from one db value: its loaded vars, schema registry, held config, text index and `ctx`. So it needs only the value it last applied, and to ask what differs: `d/datoms-diff` of that value and the head. Today each path that writes passes on what it wrote by hand, as ops (```seon.agent/merge-main!```, ```seon.agent/transact!```, the writer's notices), so a new way of moving a branch has to remember every consumer. The diff does not care how the head moved, and a restart is the same path.

Tested on this repository's real history, one experiment for each key scheme. In 11 program merges of 107 to 5,342 changed datoms, the diff held exactly what the report held. It costs about 13 k ticks a datom whole (6.7 M at 504, 69.9 M at 5,342), and 0.6 to 8.3 M read only by the 5 attributes a process loads from. A small own write costs 0.3 M committed and 0.1 M on a `d/with` value not yet stored, the same as the report on all 30 tries.

What says a head moved is separate from what changed. In a process, `d/listen` fired for a transact and a merge, tested on one process, and it hears only that process's writes to that branch; across processes it is a Flow channel. Either only says "look", so a missed one costs delay and nothing is lost. A Flow channel still wakes a process. Not tested: `d/listen` with the run-ahead writer, load order from a diff in place of a report's, and failing before a commit a merge a process cannot apply, which needs the same function over a `d/with` value. Task ``seon.program/a-process-catches-up-to-a-db-value``.

## A branch and a merge

Think of `git branch` and `git merge`. A branch starts as a pointer to main's nodes. Its own writes make new nodes. Merging main today (```seon.agent/merge-main!```) writes main's changes into the branch's own tree, like applying a patch: right content, new addresses, shared never again. A tree-level merge, prototyped and not landed, compares base, ours and main, and where the branch left a subtree unchanged and every change in it is program, points at main's node. After 200 merges on a scratch store: 21 percent private nodes against 89, correct by datoms.

Why a merge costs sharing at all: konserve is a key to a value and knows nothing of trees, so two branches share a node only when they name the same key. Today a node's key is a random id (```datahike.index.persistent-set``` without `:crypto-hash?`), so every node a branch rewrites is its own, even with the same datoms inside. In a model at leaf level (200,000 keys, 150 own entities that sort together, one run, no real store), 1,000 merges left 99.6 percent of leaves private with random keys, 0.6 percent with content-hashed keys when merged each time, 24.9 percent merged all at once, and 0.5 percent either way with leaf boundaries cut by content instead of count, as a prolly tree does: the same datoms then make the same nodes in any order, which could make the adoption rule below unnecessary. Real merges agree (100 program merges, about 19,500 datoms, one run each): with random keys 66 to 81 percent of a branch's nodes are private per index, and with `:crypto-hash?` on, which exists today, 6 to 7 percent, or 8 to 9 with data of its own, on a store of 53 MB against 80. A merge keeps a carried datom's tx: the only datoms that differed were the branch's own and one `:db/txInstant` per merge. So a tree-level merge may not be needed: this flag and a plain merge may do most of it. Hashing through hasch costs a commit of 3 forms 24.0 M ticks against 2.35 M; a SHA-256 of a leaf's stored bytes, tried in a worktree, costs 3.77 M and shares the same. All 1,504 tests passed on stores made this way, with no verdict reused; the check steps passed with hasch. Not measured: a real agent's store, and content boundaries in the tree, which the model says would remove what is left.

No choice of boundary helps writes that land everywhere: 150 entities placed by hash touch about 23 percent of leaves under every scheme. Only sort order helps, or holding writes back as Datomic's live index and a hitchhiker tree's node buffers do.

What may not be adopted: main's platform records. A message a branch holds reads as taken (```seon.agent/untaken-entries```), and root and a branch both write an agent's facts. The mailbox removes the first.

## A result stands until what it read changes

A test's result is keyed by what its run read: a fixed seed, the functions it called (```seon.profile/reads```, in each verdict since 2026-11-07), what those reach through data, and the config and files it read. Same reads, nobody reruns it, on any branch. A changed read runs once, and that run serves everyone holding the same reads. A branch that redefined a function gets its own result. Selection does not work this way yet: it is static reach ([tests run by reach](source.md#tests-run-by-reach)). The same question is meant to serve check steps and the gate's keys, compared with the render cache's recorded reads ([render](render.md)). A cached model reply is not on this list: 0 of 251 calls repeated on a real run.

## A message waits in a mailbox

Planned. A message is a fact on main, whoever sends it. It reaches the agent's entity and stays until the agent removes it ([users](users.md#messages)). Its commit wakes the agent. A channel only wakes: a dropped wake costs delay, never the message.

## A metric accretes

Planned; the numbers are from a scratch store. What a fact already records is a query, never stored again. What only a process knows, such as what each function cost, stays in that process's counters until a test or a turn ends, then is written as values that add up: count, sum, min, max and a fixed log-scale histogram. Two writers or two branches then combine exactly. A running average does not combine, so it is sum over count; a median or mode comes from the histogram, to within one bucket. Each writer holds its own entity per subject, so a merge has nothing to conflict on. Each record also holds the function's worst calls, with enough to find them again. Records ride on a transaction the system already writes, as the first Seon's did, so a metric never causes a commit. A slow or regressed function is a derived problem in [health](health.md): a read over these facts when someone asks, never a watcher. `:db/noHistory` is what keeps it cheap: updating 1,000 functions as one value each costs 10.6 M ticks and 4.7 MB over 20 updates, against 32.0 M and 58 MB with history. The price is no trend, unless a rollup per day or commit is written. Task ``seon.program/a-metric-accretes``.

## What is not decided

Each of these has a measured alternative and a real cost, and none is final:

- **Tree merge or rebase.** A tree merge rewrites no history and moves no head, but it is a change to the Datahike fork. Rebase needs no fork change, but it takes main's platform records and moves a live branch's head. Both wait on the mailbox for messages.
- **Reads or reach for a result's key.** Reads reran about a tenth as many tests in a replay, as an estimate. They miss what a run could not record: an addition to a registry, a process test's children, a definition with no record. So they stand only with a regular fresh full run, and reach stays for what they cannot see.
- **What keys a kept derivation.** Text is always safe and throws away too much: one comment in `src/seon/program.clj` costs a plan 5,271 M ticks against 165 M. Keying by the forms that derive the facts is cheap but rests on that reach being right (task ``seon.program/a-kept-tree-is-keyed-by-the-text-of-two-files``).
- **History.** Kept everywhere by ruling, and about 3 times the write and 12 times the store on an updated value. Regenerable stores and metrics are the candidates to go without; records stay.
