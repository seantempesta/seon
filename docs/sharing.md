# Sharing

A branch is main plus its own changes: an index is a tree of immutable nodes, so branches that hold the same datoms point at the same nodes, and a result stands until something it read changes. Most of what this page describes is planned or prototyped, and each part says which. The plans are on tasks ``seon.db/a-branch-is-main-plus-its-own-changes``, ``seon.program/a-tests-digest-reads-what-it-ran`` and ``seon.agent/a-message-is-a-durable-mailbox-and-a-channel-only-wakes``.

## One tree, everything linked

There is no boundary between program and data (owner, 2026-11-07: "I want everything that is linked to be linked"). One db, one tree. Sharing comes from sort order: datoms that change together sort together. A fact that changes on its own schedule is its own entity with a ref to what it is about, as `git notes` attach to a commit, so it links without rewriting the leaf of what it is about. An eval's result is one entity per distinct value, by content id, and each eval refs it.

## Where data is

A branch's db value holds only the datoms on that branch. Ids are issued once per store, so a ref on a branch to an entity only main holds is valid, but `d/pull` and `d/entity` on the branch read nothing through it: tested, a pull answers `:inbox []` and `(:m/body (d/entity branch-db e))` nil. The same `d/pull` on main's db value reads it, a `d/q` over `$branch $main` joins the two, and retracting the ref on the branch leaves the entity on main. Datahike has no read-through from a branch to main, and none is needed. A proposal, not yet agreed:

- **What a branch changes and a merge carries (forms, vars, files, config, its own tasks' work, its evals and their results):** on the branch, read from the branch's db value. Content ids make the same value one entity everywhere, and [content-hashed nodes](#a-branch-and-a-merge) make the same datoms one node, so a branch holding them costs little.
- **What no branch changes and every reader wants at its newest (messages, an agent's entity as the system sees it, what root records):** on main only, written through root's writer, read from main's db value, which the reader takes as `{:branch :commit :t}`. A render that wants both reads each from its own db value. No branch holds a copy, so a merge has nothing to carry and nothing can conflict.
- **What it costs:** one seek on main per entity read, against the db value the agent already holds.

So a message is an entity on main with `:seon.message/from`, `:seon.message/to`, `:seon.message/body` and `:seon.message/sent-ms`; the agent's entity on main refs it; the agent's render pulls those refs from main; removing one retracts the ref through root's writer, and the message stays. A Flow channel wakes the process; the message never does.

## A branch and a merge

Think of `git branch` and `git merge`. A branch starts as a pointer to main's nodes. Its own writes make new nodes. Merging main today (```seon.agent/merge-main!```) writes main's changes into the branch's own tree, like applying a patch: right content, new addresses, shared never again. A tree-level merge, prototyped and not landed, compares base, ours and main, and where the branch left a subtree unchanged and every change in it is program, points at main's node. After 200 merges on a scratch store: 21 percent private nodes against 89, correct by datoms.

Why a merge costs sharing at all: konserve is a key to a value and knows nothing of trees, so two branches share a node only when they name the same key. Today a node's key is a random id (```datahike.index.persistent-set``` without `:crypto-hash?`), so every node a branch rewrites is its own, even with the same datoms inside. In a model at leaf level (200,000 keys, 150 own entities that sort together, one run, no real store), 1,000 merges left 99.6 percent of leaves private with random keys, 0.6 percent with content-hashed keys when merged each time, 24.9 percent merged all at once, and 0.5 percent either way with leaf boundaries cut by content instead of count, as a prolly tree does: the same datoms then make the same nodes in any order, which could make the adoption rule below unnecessary. Real merges agree (100 program merges, about 19,500 datoms, one run each): with random keys 66 to 81 percent of a branch's nodes are private per index, and with `:crypto-hash?` on, which exists today, 6 to 7 percent, or 8 to 9 with data of its own, on a store of 53 MB against 80. A merge keeps a carried datom's tx: the only datoms that differed were the branch's own and one `:db/txInstant` per merge. So a tree-level merge may not be needed: this flag and a plain merge may do most of it. Not measured here: what the hashing costs (Web measured a write phase of 29 s become 52 s), a real run's store, and content boundaries in the tree, which the model says would remove what is left.

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
