# Sharing

A branch is main plus its own changes: an index is a tree of immutable nodes, so branches that hold the same datoms point at the same nodes, and a result stands until something it read changes. Most of what this page describes is planned or prototyped, and each part says which. The plans are on tasks ``seon.db/a-branch-is-main-plus-its-own-changes``, ``seon.program/a-tests-digest-reads-what-it-ran`` and ``seon.agent/a-message-is-a-durable-mailbox-and-a-channel-only-wakes``.

## One tree, everything linked

There is no boundary between program and data (owner, 2026-11-07: "I want everything that is linked to be linked"). One db, one tree. Sharing comes from sort order: datoms that change together sort together. A fact that changes on its own schedule is its own entity with a ref to what it is about, as `git notes` attach to a commit, so it links without rewriting the leaf of what it is about. An eval's result is one entity per distinct value, by content id, and each eval refs it.

## A branch and a merge

Think of `git branch` and `git merge`. A branch starts as a pointer to main's nodes. Its own writes make new nodes. Merging main today (```seon.agent/merge-main!```) writes main's changes into the branch's own tree, like applying a patch: right content, new addresses, shared never again. A tree-level merge, prototyped and not landed, compares base, ours and main, and where the branch left a subtree unchanged and every change in it is program, points at main's node. After 200 merges on a scratch store: 21 percent private nodes against 89, correct by datoms.

What may not be adopted: main's platform records. A message a branch holds reads as taken (```seon.agent/untaken-entries```), and root and a branch both write an agent's facts. The mailbox removes the first.

## A result stands until what it read changes

A test's result is keyed by what its run read: a fixed seed, the functions it called (```seon.profile/reads```, in each verdict since 2026-11-07), what those reach through data, and the config and files it read. Same reads, nobody reruns it, on any branch. A changed read runs once, and that run serves everyone holding the same reads. A branch that redefined a function gets its own result. Selection does not work this way yet: it is static reach ([tests run by reach](source.md#tests-run-by-reach)). The same question is meant to serve check steps and the gate's keys, compared with the render cache's recorded reads ([render](render.md)). A cached model reply is not on this list: 0 of 251 calls repeated on a real run.

## A message waits in a mailbox

Planned. A message is a fact on main, whoever sends it. ``:seon.agent/taken`` holds the main t of the last message the agent took, and it is behind when a message to its branch has a tx past that t. Behind wants a process. A channel only wakes: a dropped wake costs delay, never the message.

## A metric accretes

Planned; the numbers are from a scratch store. What a fact already records is a query, never stored again. What only a process knows, such as what each function cost, stays in that process's counters until a test or a turn ends, then is written as values that add up: count, sum, min, max and a fixed log-scale histogram. Two writers or two branches then combine exactly. A running average does not combine, so it is sum over count; a median or mode comes from the histogram, to within one bucket. Each writer holds its own entity per subject, like a grow-only counter, so a merge has nothing to conflict on. `:db/noHistory` is what keeps it cheap: updating 1,000 functions as one value each costs 10.6 M ticks and 4.7 MB over 20 updates, against 32.0 M and 58 MB with history. The price is no trend, unless a rollup per day or commit is written. Task ``seon.program/a-metric-accretes``.

## What is not decided

Each of these has a measured alternative and a real cost, and none is final:

- **Tree merge or rebase.** A tree merge rewrites no history and moves no head, but it is a change to the Datahike fork. Rebase needs no fork change, but it takes main's platform records and moves a live branch's head. Both wait on the mailbox for messages.
- **Reads or reach for a result's key.** Reads reran about a tenth as many tests in a replay, as an estimate. They miss what a run could not record: an addition to a registry, a process test's children, a definition with no record. So they stand only with a regular fresh full run, and reach stays for what they cannot see.
- **What keys a kept derivation.** Text is always safe and throws away too much: one comment in `src/seon/program.clj` costs a plan 5,271 M ticks against 165 M. Keying by the forms that derive the facts is cheap but rests on that reach being right (task ``seon.program/a-kept-tree-is-keyed-by-the-text-of-two-files``).
- **History.** Kept everywhere by ruling, and about 3 times the write and 12 times the store on an updated value. Regenerable stores and metrics are the candidates to go without; records stay.
