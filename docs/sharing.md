# Sharing

How branches share data, how a merge works, where each kind of fact lives, and what each operation should cost. A branch is a pointer to a tree of immutable index nodes, so a fork costs one pointer; a merge replays the source's transactions on the branch that receives it, planned from base, ours and theirs by each entity's identity; and what a branch writes decides how much of its tree is its own. What is built is said as built; what is planned names its task.

## One tree, everything linked

There is no boundary between program and data (owner, 2026-11-07: "I want everything that is linked to be linked"). One db, one tree. Sharing comes from sort order: datoms that change together sort together. A fact that changes on its own schedule is its own entity with a ref to what it is about, as `git notes` attach to a commit, so it doesn't rewrite the leaf of what it is about. Ids are issued once per store or are content ids, so the same thing has the same id on every branch.

## Where a fact lives

One question decides it: can two branches legitimately hold different values of it?

- **Yes: on a branch, moved by a merge.** Forms, vars, files, config, tasks, a user's data, evals and their results. Isolated until merged.
- **No: on main, as a platform record, and no merge carries it.** Any `seon.*` attribute but the task content an agent may improve (```seon.merge/carried-platform-attrs```): messages, what root records of an agent, UI facts. Messages: a message is one entity on main with `:seon.message/to`, from, body and sent-ms; an agent's next act writes the ones to it onto its own branch in its own transaction, and it removes one with `swap!` on `ctx` ([users](users.md#messages)).
- **Each attribute of an entity is set on one branch.** A branch has one writer, Datahike's commit log, so a conflict is never two writers on a branch: it is two branches setting the same attribute of one entity since their base, which a merge must then settle. An agent's entity is set on main by root (its status and process) and on the agent's branch by its process (its turn, its namespace), so a merge up can meet both sides; setting each of its attributes on one branch is the agent-entity step of [users](users.md).

A db value reads only its own branch's datoms: a ref to an entity only another branch holds is valid, but `d/pull` through it answers nothing. A prototype overlay (one db value over two branches, each read routed by attribute) matched a reference db on 25 of 25 reads; it is not built, since every reader, basis and notice would have to know about two branches.

## A merge

One merge for both directions, planned from base, ours and theirs before anything is written ([merging](merging.md#seons-merge-is-a-three-way-merge-of-the-program)):

- **What it writes is the source's transactions,** each replayed as its own transaction of the receiving branch with a new time, entity ids kept (```seon.merge/replay```); a retraction the source made travels as the source made it, and a key the receiving branch changed too, to another end, is a conflict that writes nothing. What a side never held is no change of its (```seon.merge/net-change``` reads each side against the base).
- **A parent is the commit merged,** never a branch name, which Datahike would read as that branch's head when the merge commits ([merging](merging.md)).
- **A pull of main starts from git's merge base** (```seon.agent/merge-main!```): the main last merged, kept as `:base` with no walk while main has not moved; when main has moved, read from the commit graph over the commits since, so after main merged a commit of this branch (its own merge up) that commit is the base. An agent that goes on after its task merged (a chat's next message) and edits what it merged then meets no conflict with itself (``seon.agent-test/a-pull-after-its-own-merge-starts-from-what-it-merged``). A second merge up carries only what changed since, since the writer's base is read from the same graph (```seon.writer/octopus-merge```). An agent stays on its branch and in its process through all of this: a restart is for a new binary or a dead process.
- **A form's place is a sort key with gaps** (```seon.program/stable-places```): an inserted form takes a place between its neighbours and moves no other.
- **What a merge reads, it reads once:** the schema per merge, not per datom (cost proof `seon.program-cost-test/a-merge-costs-what-it-carries`).
- **Before a merge lands,** its changed files are analyzed where the candidate is computed, with no worktree, and the slow tests run last ([merging](merging.md#a-slow-test-runs-last)).

Options a person can ask of a merge (a choice of keys, `-X theirs`) are task ``seon.merge/a-merge-takes-options``.

## What a branch's nodes are

An index node's address is the hash of its content (Datahike's `:crypto-hash?`, made so with every store: ```seon.db/layout```; a leaf's address is the SHA-256 of the bytes the store writes for its datoms, [datahike](forks.md#datahike)), so two branches that hold the same datoms name one node, and a branch's own nodes are the ones its own datoms are in, never what the merges it took rewrote. Measured on a seeded store, a branch that took 20 of main's changes by merge and wrote one note held 4 of its 157 EAVT nodes as its own (the note's path, leaf to root); with random addresses 76 of 157 (`seon.db-test/a-branchs-own-nodes-follow-its-own-datoms`); in the lab, 6 to 9 percent of a long-lived branch's nodes after 100 merges against 66 to 81. A commit of 3 forms costs 3.77 M ticks against 2.35 M with random addresses (24.0 M when a leaf was hashed by hasch's walk of its values). A store made before keeps its random addresses and opens as it was (a connect adopts how a store was made); a kept store of regenerable output is named by its layout too, so it is made again with it. Collection with shared nodes is mark and sweep only ([storage](storage.md#collection)).

Which datoms a node holds depends on the order they were inserted, though its address is its content's hash: the sorted set cuts a full leaf of 64 in half when the 65th key arrives. A merge inserts main's datoms in another order than main's transaction did (```seon.merge/merge-tx```), so when a leaf overflows inside one entity's datoms the branch and main cut one or two datoms apart, and two leaves holding the same datoms between them get different addresses, which never rejoin. A branch therefore owns, beside the nodes its own datoms are in, about one node per eight merges that holds only main's datoms (measured 2026-11-10 in the scenario of `seon.db-test/a-branchs-own-nodes-follow-its-own-datoms`: 3 of 157 at 20 merges, 16 of 212 at 100). Its bound is a fraction of the tree for that reason (owner, 2026-11-10); a fixed count was luck (an index on `:seon.test/status` moved it from 4 to 9 by adding one datom to the seed).

What also keeps a branch small is writing little:
- A test's facts are kept on a branch only where something reads them (```seon.proofs/facts-worth-keeping```), and a form's place no longer renumbers its file. On a real store of 3 agent branches, EAVT was 3 to 10 percent private by address with random keys, where an earlier real store's was as much as 27, and `seon.test` datoms 30 to 484 where they were 5,416.

## What each operation should cost

A number far off its line is a defect to find. n is what the operation is about, not the store's size. Measured, one experiment each; the program is about 200,000 datoms.

| Operation | Should cost | Measured |
|---|---|---|
| Fork a branch | O(1) | 0.28 M ticks |
| Read an entity | O(log N), the same on any branch | 300 pulls 6.6 M on main, a user and an agent |
| A commit of k datoms | O(k) | about 5 k ticks a datom without history, 14 k with |
| Merge of k carried datoms | O(k) | 7.3 k ticks a datom in `merge-tx` |
| An insert in a file | O(1) places | 1 place |
| Analyzing a merged tree, one file | O(that file) | under 0.1 s a file |
| `bin/prove` deciding which deftests to run, after a body edit | O(changed forms) | 165 M ticks, 1.5 s |
| The same, after an edit to `seon/source.clj` or `seon/program.clj` | O(changed forms) | 5,271 M: the kept analyzed form tree is dropped and every form read again, **off** (task ``seon.source/the-test-runners-kept-programs-are-keyed-by-their-indexing-code``) |
| `d/schema` | O(1) once per db value | 241 k ticks a call, rebuilt each time: **off**, 14 calls in `src`; the fork change is approved (owner, 2026-11-08; task ``seon.db/the-schema-map-is-read-once-a-value``) |
| A diff of two commits m datoms apart (`d/datoms-diff`) | O(the nodes the m datoms sit in), flat in the store's size but for the tree's height | m values replaced on entities spread over a 40,000-datom LMDB store (`seon.merge-cost-test/a-diff-of-two-commits-costs-the-nodes-that-differ`, 2026-11-11): 71.5k, 304k and 1.59 M ticks warm at m = 1, 10 and 100. Every shared subtree is skipped unread; cold, each differing node is one read and decode. On the in-memory test store nothing has an address, so the same diff walks the whole index (3.5 M at 2,500, 14.7 M at 20,000): measure it on disk ([how we measure](cost.md#how-we-measure)) |
| A small write (a few datoms, committed) | about 0.1 M ticks | 0.3 M: **off** about 3× (task ``seon.db/a-small-write-costs-its-minimum``) |

## A result stands until what it read changes

A defect today (owner, 2026-11-08: not knowing what a test executed is a bug; task ``seon.program/a-tests-digest-reads-what-it-ran``). A test's verdict is keyed by its static reach, the vars its forms refer to ([source](source.md#tests-run-by-reach)). A process test forks an agent, which reaches root's code by messages to the server, not by var references, so an agent's code is reached by all 232 process tests and a var of `seon.reconcile`, `seon.writer` or `seon.root` by a median of 2 or 3: a change there can keep a stale green. The daily `bin/test --all` on main is the net (owner, 2026-10-27), and a red it finds is a `:platform` problem naming each test (```seon.prove/full-answered```); one task per red deftest, keyed by its name, is task ``seon.prove/a-red-in-the-daily-full-test-is-a-task``. On 2026-11-07, `bin/test --all` on main with nothing reused was 1,523 of 1,523 green. The fix: a test's key is a fixed seed and the vars it called, recorded by every process it executed in.

## Catching up to a db value

A process that holds what derives from a db value (its loaded vars, schema registry, config, text index) can take what moved from the value it last applied by a diff (`d/datoms-diff`), however it moved. On real history a diff held exactly what a transaction's report held. It was tried for the fork server and a merge of main and reverted (2026-11-08): a diff costs about 1.8 M ticks on a one-datom write where the report is free (920 ticks), the gap it closed was never seen, and the other readers still took ops. A reader that can really miss a commit is where it belongs (task ``seon.program/a-process-catches-up-to-a-db-value``).

## A function's cost is a fact

What an agent's functions cost (calls, ticks, bytes, by ```seon.profile/accrue``` at each eval, ```seon.agent/note-costs!```) is an observation of that process on that machine, kept live in the process: `(:fn-costs @seon.agent/state)`, everything measured since it started. It is never written to the store (the owner, 2026-11-11: no value is stored that two branches would each rewrite; ruled by Point the same day): written once a run as one `:seon.fn/stats` sum a function, it was 30-77% of what an agent branch wrote (measured on the standing root) and two branches rewrote one entity's one attribute (`seon.agent-test/what-a-function-cost-is-measured-live-and-never-stored`). When something reads a cost across runs and agents, each run's measure comes back as its own fact a merge unions, its total a render; the `:seon.fn/*` schema entries leave the store at its next erase.
