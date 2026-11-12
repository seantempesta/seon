# Sharing

How branches share data, how a merge works, where each fact lives, and what each operation should cost. A branch is a pointer to a tree of immutable index nodes, so a fork costs one pointer; a merge is git's three-way merge by key; and what a branch writes decides how much of its tree is its own.

## One tree, everything linked

There is no boundary between program and data: one db, one tree. Sharing comes from sort order: datoms that change together sort together. A fact that changes on its own schedule is its own entity with a ref to what it is about, as `git notes` attach to a commit, so it doesn't rewrite the leaf of what it is about. Ids are issued once per store or are content ids, so the same thing has the same id on every branch.

## Where a fact lives

One question decides it: can two branches legitimately hold different values of it?

- **Yes: on a branch, moved by a merge.** Forms, vars, files, config, tasks, a user's data, evals and their results.
- **No: on main, and no merge carries it.** Any `seon.*` attribute but the task content an agent may improve (`seon.merge/carried-platform-attrs`): messages, what root records of an agent, UI facts. A message is one entity on main with `:seon.message/to`, from, body and sent-ms; an agent's next act writes the ones to it onto its own branch ([users](users.md#messages)).
- **Each attribute of an entity is set on one branch.** A branch has one writer, so a conflict is two branches setting the same attribute of one entity since their base. An agent's entity is set on main by root (status, process) and on its branch by its process (turn, namespace).

A db value reads only its own branch's datoms: a ref to an entity only another branch holds is valid, but `d/pull` through it answers nothing.

## A merge

Git's three-way merge of base, ours and theirs, by key (`seon.merge/three-way`; read where they differ: `seon.merge/delta-three-way`):

- **The datoms carried are the source's, as they are,** tx included (`seon.merge/carried-tx`), and only a retraction the source made travels, so a partial merge never reads what it lacks as removed. It can report a false `:deleted-by-them` conflict (task ``seon.merge/what-a-side-never-held-is-no-change``).
- **A parent is the commit merged,** never a branch name, which Datahike would read as that branch's head when the merge commits ([merging](merging.md)).
- **A pull of main starts from git's merge base** (`seon.agent/merge-main!`), read from the commit graph, so after main merged a commit of this branch that commit is the base, and an agent editing what it merged meets no conflict with itself (`seon.agent-test/a-pull-after-its-own-merge-starts-from-what-it-merged`). A second merge up carries only what changed since (`seon.writer/octopus-merge`). An agent stays on its branch and in its process throughout; a restart is for a new binary or a dead process.
- **A form's place is a sort key with gaps** (`seon.program/stable-places`): an inserted form moves no other.
- **What a merge reads, it reads once:** the schema per merge, not per datom (`seon.program-cost-test/a-merge-costs-what-it-carries`).
- **Before a merge lands,** its changed files are analyzed where the candidate is computed, and the slow tests run last ([merging](merging.md#a-slow-test-runs-last)).

Merge options (a choice of keys, `-X theirs`) are task ``seon.merge/a-merge-takes-options``.

## What a branch's nodes are

An index node's address is the hash of its content (Datahike's `:crypto-hash?`, set for every store by `seon.db/layout`; a leaf's address is the SHA-256 of its stored bytes, [datahike](forks.md#datahike)), so two branches holding the same datoms name one node, and a branch's own nodes are the ones its own datoms are in. A branch that took 20 of main's changes and wrote one note held 4 of its 157 EAVT nodes as its own, against 76 with random addresses (`seon.db-test/a-branchs-own-nodes-follow-its-own-datoms`). A store made with random addresses opens as it was made. Collection with shared nodes is mark and sweep only ([storage](storage.md#collection)).

Which datoms a node holds depends on insertion order: a full leaf of 64 splits in half at the 65th key, and a merge inserts main's datoms in another order than main did (`seon.merge/merge-tx`). So a branch also owns about one node per eight merges that holds only main's datoms; its bound is a fraction of the tree, not a fixed count.

What also keeps a branch small is writing little: a test's facts are kept only where something reads them (`seon.proofs/facts-worth-keeping`).

## What each operation should cost

A number far off its line is a defect to find. n is what the operation is about, not the store's size; the program is about 200,000 datoms.

- **Fork a branch:** O(1); 0.28 M ticks.
- **Read an entity:** O(log N), the same on any branch.
- **Commit k datoms:** O(k); about 5 k ticks a datom without history, 14 k with.
- **Merge k carried datoms:** O(k); 7.3 k ticks a datom in `merge-tx`.
- **Insert in a file:** O(1) places.
- **Analyze a merged tree, one file:** O(that file).
- **`bin/prove` choosing deftests after a body edit:** O(changed forms), 165 M ticks. After an edit to `seon/source.clj` or `seon/program.clj` every form is read again: **off** (task ``seon.source/the-test-runners-kept-programs-are-keyed-by-their-indexing-code``).
- **`d/schema`:** should be O(1) once per db value; it is rebuilt each call (241 k ticks): **off** (task ``seon.db/the-schema-map-is-read-once-a-value``).
- **Diff of two commits m datoms apart (`d/datoms-diff`):** O(the nodes the m datoms sit in); shared subtrees are skipped (`seon.merge-cost-test/a-diff-of-two-commits-costs-the-nodes-that-differ`). The in-memory store has no addresses, so measure it on disk ([how we measure](cost.md#how-we-measure)).
- **A small write:** about 0.1 M ticks; measured 0.3 M: **off** (task ``seon.db/a-small-write-costs-its-minimum``).

## A result stands until what it read changes

A defect today (task ``seon.program/a-tests-digest-reads-what-it-ran``). A test's verdict is keyed by its static reach, the vars its forms refer to ([source](source.md#tests-run-by-reach)). A process test forks an agent, which reaches root's code by messages, not var references, so a change in `seon.reconcile`, `seon.writer` or `seon.system` can keep a stale green. The daily `bin/test --all` on main is the net; a red it finds is a `:platform` problem naming each test (`seon.prove/full-answered`), and one task per red is task ``seon.prove/a-red-in-the-daily-full-test-is-a-task``. The fix: a test's key is a fixed seed and the vars it called, recorded by every process it executed in.

## Catching up to a db value

A process holding what derives from a db value (loaded vars, schema registry, config, text index) can take what moved since the value it last applied by a diff (`d/datoms-diff`). It is not used: a diff costs about 1.8 M ticks on a one-datom write where the transaction report is free, so readers take the report. A reader that can really miss a commit is task ``seon.program/a-process-catches-up-to-a-db-value``.

## A function's cost is a fact

What an agent's functions cost (calls, ticks, bytes, by `seon.profile/accrue` at each eval, `seon.agent/note-costs!`) is an observation of that process, kept live as `(:fn-costs @seon.agent/state)`. It is never written to the store, since two branches would each rewrite it (`seon.agent-test/what-a-function-cost-is-measured-live-and-never-stored`). A cost read across runs would come back as each run's own fact, which a merge unions.
