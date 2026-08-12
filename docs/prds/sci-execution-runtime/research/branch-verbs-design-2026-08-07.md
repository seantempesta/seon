---
type: research
status: complete
tags: [research, runtime, database]
---

# `my.branch` — verb design and root's cross-branch capability, probed (2026-08-07)

Design + REPL probing for the git-style branch/history verbs, scoped to one
question: **is root's outer capability demonstrably covered under the
`seon.env` model?** Root must see every branch, obtain any branch's database
value (current head or pinned), pass it EXPLICITLY to ordinary `seon.db`
reads so the caller wins over the environment's supplied default, and be
custody-fenced on foreign WRITES while foreign reads stay open.

Documents read end to end before designing: the sealed
[seon.env PRD](../plan/seon-env-prd-2026-08-07.md) (including
"Current versus pinned database values" and naming ruling 5 — no "ambient",
no "tower", library or literal names only), the
[the agent's defs and checkout PRD](../plan/agent-desk-and-checkout-prd-2026-08-05.md),
and the `my.branch` `[TARGET]` row in the vocabulary table of
[CLAUDE.md](../../../../CLAUDE.md). Naming in this document follows ruling 5:
Datahike's own words for temporality (`d/db` on a connection is CURRENT; a
database VALUE is pinned; the transaction report's `:db-after` is the next
value), Clojure's own words for the carriers, and no umbrella nouns.

This is design + evidence. **No production `src/` change is proposed here as
landed work**; the two defects found are filed as issues.

## Dependency ledger

| Dependency / mechanism | Selected source | What it establishes |
|---|---|---|
| Datahike versioning | `reference-code/datahike/src/datahike/versioning.cljc` | `branches`, `branch!`, `delete-branch!`, `branch-as-db`, `commit-as-db`, `commit-id`, `parent-commit-ids`, `branch-history`, `force-branch!`, `merge!` |
| Datahike api export list | `reference-code/datahike/src/datahike/api/specification.cljc:916,984,1029,1057` | which of those are public API (`branch-history` is NOT) |
| Connection identity | `reference-code/datahike/src/datahike/store.cljc:44-55` | self-writer connection id is `[store-id branch]` |
| Connection registry | `reference-code/datahike/src/datahike/connections.cljc:3-9,37-92` | one process-local entry per connection id, reference counted |
| Seon store custody | `src/seon/cluster/store.clj:271-352,375-406` | one flock per process root; `open-branch!` refuses a second connection to one branch |
| Seon branch lifecycle | `src/seon/cluster/registry.clj:92-98,104-111,160-294` | `cluster-branch`, `roster`, `branch!`, `retire-branch!`, `reset-cluster!` — the ONE lifecycle owner |
| Seon database reads | `src/seon/db.clj:95-119,163-174,547-560,605-650,887-926,1163-1187` | explicit-value reads, current resolution, the foreign-write fence |
| Run basis | `src/seon/cluster/run.clj:242-263` | `opening-db` — `as-of` the run's opening transaction |

## 1. The design

### 1.0 The one sentence

`my.branch` is a READ-and-FORK surface over the cluster's Datahike branches.
It never mutates a working tree, never merges, and never reaches a remote —
because none of those three things exist here. What it does is select, walk,
compare, and fork DATABASE VALUES.

### 1.1 `checkout` — selecting the database value a run opens at

Per §4 of the checkout PRD, checkout is **not** a working-tree mutation and
nothing is copied anywhere. Two states already named there:

- a **live namespace owner** declares nothing, and every run opens at the
  branch head — `(d/db connection)` at run open;
- a **fork-in-time agent** carries a checkout on its agent entity: a branch
  plus an opening commit id. A single run may override it; most specific
  wins.

So `my.branch/checkout` writes ONE fact — the agent's checkout — and returns
what the next run will open at. It performs no evaluation and builds no
context; the ctx derives from the database at a basis, so checking out the
database is checking out the program world (checkout PRD §4, "the ctx is not
a third surface").

Under the environment model this composes without a new mechanism. The
environment stores the CONNECTION, and the run loop is the pinned-mode caller
described in the PRD's "Current versus pinned database values": it obtains
its opening value once (today `run/opening-db`, `src/seon/cluster/run.clj:242-263`,
which is `as-of` the run's `opened-at` transaction) and threads it — and then
each `:db-after` — through the reduce over forms. A checked-out agent changes
only WHICH value that first step produces: `d/branch-as-db` for another
branch's head, `d/commit-as-db` for a pinned commit. Everything downstream is
unchanged because it was already receiving an explicit value.

`git checkout` mapping, honestly: like `git checkout`, except nothing is
written to disk and the previous state is not disturbed — the branch you leave
keeps advancing.

### 1.2 `log` — the commit walk

`log` is the parent walk over commit ids, and the honest unit is one commit =
one transaction (Datahike commits every transaction;
`versioning.cljc:561-567` states this in `fork-database`'s docstring).

Two implementations exist in the dependency and only one serves root:

- `versioning/branch-history` (`versioning.cljc:191-210`) walks parents from
  `(:branch (:config @conn))`. It requires a connection ATTACHED to that
  branch, and it always returns a core.async channel even under the
  synchronous default. It is also not exported through `datahike.api`.
- `branch-as-db` → `parent-commit-ids` → `commit-as-db`
  (`versioning.cljc:499-510,463-467,469-488`) all accept **a connection, a
  database value, or a raw store**, so root's single main store connection
  walks ANY branch's history without opening a second connection.

**Recommendation:** the second. One walk owner, foreign-branch-capable,
synchronous, and it is the only shape root can use. Probe vi below runs it and
gets the same commits as the attached walk.

Each entry carries the commit id, the basis transaction `:t`, and the parent
commit ids — Datahike's own three facts, nothing invented. Rendering (message,
author, subject) has no source here and must not be faked; what a Seon log
entry can honestly add later is the transaction metadata already committed
(`:seon.db/user`, `:seon.db/process`).

`git log` mapping: like `git log`, except each commit is one transaction and
there is no message unless the transaction recorded one.

### 1.3 `diff` — one honest shape, and what it cannot mean

**Recommendation: `diff` is the datom-level change stream between two bases of
ONE branch's history, computed as `since` over the `history` view.** Two
arguments (from-basis, to-basis defaulting to the branch head); the result is
the ordered datom stream with Datahike's `:added` flag, so assertions and
retractions both appear.

The critical correction the probe surfaced, and the reason `since` alone is
the wrong answer: **`d/since` is a filtered view of CURRENT datoms whose
transaction is later than `t`.** A value asserted after `t` and then replaced
does NOT appear in it. Measured (probe vi): on a branch where `"a-first"` was
asserted at t+1 and upserted to `"a-second"` at t+2, `since` from the fork
point reports only `["a-second" "a-third"]`. The same range over
`(d/since (d/history db) t)` reports the true stream, including the retraction:

```clojure
[{:a :probe/label :v "a-first"  :added true}
 {:a :probe/label :v "a-first"  :added false}
 {:a :probe/label :v "a-second" :added true}
 {:a :probe/label :v "a-third"  :added true}
 …]
```

So the owner is `history` + `since`, never bare `since`. This depends on
`:keep-history? true`, which is the store's creation default
(`src/seon/cluster/store.clj:148-149`) and is creation-fixed; a
`:keep-history? false` store must refuse `diff` rather than answer it wrongly.

**What `diff` cannot mean without an index.** Git's diff compares two trees
through a merge base. Here:

- there is no index and no working tree, so "staged vs unstaged" has no
  referent at all;
- comparing **two different branches** is not a temporal range. Branch A and
  branch B share ancestry but their transaction ids diverge after the fork
  point, so no single `since` spans them. The only correct cross-branch answer
  is a set difference over enumerated datoms of both values — O(size of both
  branches), not O(change). Probe vi computes the shared-ancestor commit set
  and the fork point, which makes a **cross-branch diff expressible as two
  one-branch diffs from the fork point** ("what A added since we diverged" and
  "what B added since we diverged"). That is the shape to offer, and it is
  cheap. A true symmetric datom difference should be refused, or offered only
  under an explicit bounded form, until something measures it.

`git diff` mapping: like `git diff`, except the unit is a datom rather than a
line, there is no index to diff against, and a two-branch diff is stated as
two diffs from the fork point.

### 1.4 `status` — branch head versus this agent's basis

`status` is a pure derivation over facts that already exist, and it is the
verb that teaches the two-world contract every time it renders (checkout PRD
§5, "Context gains git-shaped views"). Its fields:

- the branch this agent is on (`:seon.store/branch`, derived from the cluster
  name by the ONE derivation `registry/cluster-branch`,
  `src/seon/cluster/registry.clj:92-98`);
- the branch HEAD: commit id and basis `:t` of `(d/db connection)`;
- this agent's BASIS: the checkout's commit id and `:t`, or the head when the
  agent is a live namespace owner — plus, for a pinned agent, how many commits
  behind it is (a count over the log walk);
- the agent's defs: which `:seon.def/*` rows are uncommitted session state versus what
  is in the shared program graph — the "working tree" half of the mapping,
  already landed as W-A.

Nothing here is stored. `status` is a query, and if a field cannot be answered
by query the missing fact is the defect.

`git status` mapping: like `git status`, except "modified files" is your agent defs
(defs and atoms) and "ahead/behind" is measured in transactions.

### 1.5 `fork` — Datahike `branch!` through the one lifecycle owner

`fork` creates a new branch from a branch keyword or a commit uuid. The
implementation is NOT `datahike.api/branch!` directly: it is
`seon.cluster.registry/branch!` (`src/seon/cluster/registry.clj:160-206`),
which is already the ONE owner, is idempotent by the roster, treats Datahike's
`:branch-already-exists` as idempotence rather than failure, and holds the
store-id-keyed roster permit that makes concurrent forks safe
(`versioning.cljc:212-277`, and the registry docstring's provenance: before
that fix twelve concurrent creates reported eleven successes and landed nine).

Measured cost: **median 17.0 ms** over ten forks (probe v), consistent with
the 17 ms figure the registry docstring cites.

Custody note that must survive into implementation: forking a branch does not
give the forker a connection to it. `store/open-branch!` refuses a second
connection to one branch in the process (`src/seon/cluster/store.clj:375-406`),
and connection identity is `[store-id branch]`
(`reference-code/datahike/src/datahike/store.cljc:44-55`), so the fence in §1.7
below has something exact to compare.

`git checkout -b` mapping: like `git checkout -b`, except the new branch is a
whole database and creating it costs ~17 ms.

### 1.6 How root's cross-branch reads compose with `seon.env`

No new mechanism, and no dynamic var. Three moves, all of them already the
PRD's:

1. **Root obtains the value explicitly.** `d/branch-as-db` and
   `d/commit-as-db` take root's main store connection and return an ordinary
   immutable database value for ANY branch — head or pinned. Measured median
   **0.219 ms** for a foreign head (probe v). Root never needs a second
   connection, which is what keeps the flock and the one-connection-per-branch
   rule intact.
2. **Caller wins.** Every `seon.db` read already has an explicit-value arity
   and an elided arity; the elided arity resolves the current value at the
   public-call boundary (`src/seon/db.clj:95-110`, `resolve-database-value`
   "resolve latest exactly once"). Passing a value skips resolution entirely.
   Probe iii proves this with the two in direct conflict: with current
   resolution naming branch A, `(db/q head-b …)` returns branch B's data and
   `(db/q pinned-a …)` returns the pinned older state — five reads, five
   correct answers, in one binding.
3. **The environment supplies the default, not the value.** Per the PRD's
   "elide for current, pass for consistent": the environment stores the
   CONNECTION; a declared-and-absent `:seon.db/db` is derefed at call
   preparation, so an elided read is always the latest committed value and a
   supplied value is never replaced. The branch verbs are simply the
   pass-for-consistent side, and root's cross-branch work is the extreme case
   of it — the value it passes came from a different branch entirely.

The consequence worth stating plainly: **root's cross-branch capability needs
nothing that the environment model does not already give it.** Explicit values
win; there is no "current branch" slot to switch, and therefore nothing that
can leak between clusters.

### 1.7 What is custody-fenced

**Foreign WRITES are refused. Foreign READS are open.** That is the settled
shape, and both halves are verified (probe iv).

The fence is `seon.db/foreign-connection-error` (`src/seon/db.clj:163-174`),
called only from the two-argument `transact!` (`:1186`). It compares Datahike
connection ids, which for two branches of one store differ exactly in the
branch keyword. Measured refusal:

```clojure
{:seon.error/kind :seon.db/foreign-connection
 :seon.error/message "The explicit transaction connection does not belong to the calling agent's cluster."
 :seon.error/data {:seon.db/ambient-connection-id  [#uuid "af32…" :probe-a]
                   :seon.db/explicit-connection-id [#uuid "af32…" :probe-b]}}
```

Branch B carried no `"smuggled"` datom afterwards; the agent's own write to
branch A committed normally in the same binding; and a read of branch B's
value succeeded in that same binding. Reads being open is what makes root's
`log`/`diff`/`status` across branches possible at all, and it is safe because
a database value is immutable and carries no writer.

Two things the fence does NOT cover, both filed:

- it reads the dynamic var `seon.db/*conn*` and nothing else, so the PRD's
  Phase 3 deletion of that var would silently turn the guard permanently false
  — a custody failure with no crash and no failing test. Issue:
  [foreign-write-fence-reads-only-the-dynamic-var](../../../seon/issues/foreign-write-fence-reads-only-the-dynamic-var.md)
  (blocker);
- an UNBOUND caller may write to any live connection (verified: the identical
  write committed with no binding). That is deliberate for system callers
  today, and the conversion must not turn it into an agent-reachable bypass.

Branch LIFECYCLE is fenced structurally rather than by comparison: only
`seon.cluster.registry` holds a connection that may call `branch!`,
`delete-branch!`, or `gc-storage`, and a cluster receives a branch connection
and never the branch API (`src/seon/cluster/registry.clj:15-18`), so cluster A
holds no handle that can delete cluster B. `my.branch/fork` therefore routes a
REQUEST to that owner; it does not carry the capability.

### 1.8 What stays out of v1

- **No remotes.** There is no second store to fetch from. The one place two
  histories meet is publication, and the checkout PRD already rules that link
  to be a single provenance fact (the published `current-src` commit records
  the source git SHA), joined by query. Real git on disk stays raw CLI through
  `my.shell`.
- **No index / staging.** There is no working tree to stage from; the agent's defs is
  the working tree and it commits at turn settlement.
- **No merge — and the source does NOT make it trivial.** `versioning/merge!`
  (`versioning.cljc:734-748`) is explicit: "It is the responsibility of the
  caller to make sure that tx-data contains the data to be merged into the
  branch from the parents. This function ensures that the parent commits are
  properly tracked." It records parent commits; it computes nothing. Every
  hard part — deriving the merged tx-data, detecting conflicting datoms,
  resolving them — would be ours to invent. That is precisely the ff-only
  ruling's target, so it stays out. `force-branch!` (`versioning.cljc:323-455`)
  likewise stays out of the agent surface: it is `git reset --hard` with a
  `:expected-current-commit` guard, and it belongs to publication, which
  already owns it.
- **No `fork-database`.** `versioning/fork-database` (`:550-732`) copies every
  konserve key into a NEW store. That is a whole-store operation, not a
  branch, and it is not what "fork" means here.
- **No cross-branch symmetric datom diff** until something measures it (§1.3).

## 2. Probe inventory

One file, `tmp/env-probes/env_probes/branch_verbs.clj`, namespace
`env-probes.branch-verbs`, one entry point `run` returning
`{:probe/verdict …}`. Preserved as evidence under
[branch-verbs-probes/](branch-verbs-probes/). It builds an ISOLATED file store
at `tmp/branch-probe-store`, deletes it on the way in and out, and releases the
flock in a `finally` — it never touches the shared default cluster or any
operator root.

```sh
clojure -M:dev -e '(load-file "tmp/env-probes/env_probes/branch_verbs.clj")
                   (clojure.pprint/pprint (env-probes.branch-verbs/run))'
```

`run` accepts `{:probe/fork-samples 10 :probe/head-samples 20}`. The recorded
2026-08-07 output is [branch-verbs-output.edn](branch-verbs-probes/branch-verbs-output.edn).

| Probe | Question | Verdict |
|---|---|---|
| i | enumerate the branches of one store | **PASS** |
| ii | fork two branches, distinct datoms in each, head + pinned value per branch | **PASS** |
| iii | explicit foreign value beats current resolution | **PASS** |
| iv | foreign WRITE refused, foreign READ open | **PASS** |
| v | timings for fork and foreign head value | recorded |
| vi | root's log walk and the honest diff shape | **PASS** |

### 2.1 (i) Enumerate

`registry/roster` over the main connection (`src/seon/cluster/registry.clj:104-111`,
`d/branches`): `#{:db}` before, `#{:db :probe-a :probe-b}` after two forks.
The roster IS the fact — a branch in it exists, a branch absent from it does
not, whatever blobs are on disk.

### 2.2 (ii) Fork, transact, head and pinned

Both forks report `:seon.cluster/created? true`. Branch A: assert `"a-first"`
(capture `:db-after` as the pinned value, `:t` 536870914), then upsert to
`"a-second"`. Branch B: assert `"b-only"`. Then:

| value | labels visible | commit id |
|---|---|---|
| `(d/db conn-a)` — A's head | `["a-second"]` | `6a76…5f90` |
| `(d/db conn-b)` — B's head | `["b-only"]` | `6a76…5947` |
| `:db-after` of A's first transaction (pinned) | `["a-first"]` | — |
| `(db/as-of head-a 536870914)` | `["a-first"]` | — |
| `(d/branch-as-db main :probe-b)` — root, no B connection | `["b-only"]` | `6a76…5947` (identical) |

The pinned value and the `as-of` value agree, which is the PRD's chain
(`opening-db → transact! → :db-after`) and the time-travel form landing on the
same basis. Root's foreign head carries the SAME commit id as the connection
holder's head — root is not looking at a copy.

### 2.3 (iii) Caller wins

Inside `(binding [db/*conn* conn-a] …)`, i.e. with current resolution naming
branch A:

| read | result |
|---|---|
| `(db/q '[…] (db/db))` — elided, current | `["a-second"]` (branch A) |
| `(db/q '[…] head-b)` | `["b-only"]` (branch B) |
| `(db/q '[…] (d/branch-as-db main :probe-b))` | `["b-only"]` |
| `(db/q '[…] pinned-a)` | `["a-first"]` |
| `(db/q '[…] as-of-a)` | `["a-first"]` |

Five reads, one binding, five different bases, every answer the caller's. The
mechanism is `aligned-query-arguments` (`src/seon/db.clj:572-603`): current
resolution is consulted only when no explicit database is present.

### 2.4 (iv) Custody

Verbatim refusal value in §1.7. Also asserted in the same run: the own-branch
write committed (`:tx 536870916`); branch B held no `"smuggled"` label
afterwards; the unbound write to branch B COMMITTED (`:db-after` present),
which is the honestly-recorded hole; and the read of branch B stayed open.

### 2.5 (v) Timings

| operation | samples | median |
|---|---|---|
| `registry/branch!` (fork from `:db`) | 10 | **17.0 ms** |
| `d/branch-as-db` (foreign head value) | 20 | **0.219 ms** |

Both on the `:file` backend with fused index roots
(`src/seon/cluster/store.clj:152-165`), warm JVM, isolated store. The fork
figure matches the ~17 ms the registry docstring and the checkout PRD already
cite; the foreign-head figure is new and is the number that matters for
`status` and `log`, which read foreign heads repeatedly. At 0.2 ms a status
render may read every branch's head without a cache.

### 2.6 (vi) Root's log walk and the diff shape

`store-log` (in the probe) walks `branch-as-db` → `parent-commit-ids` →
`commit-as-db` from the MAIN connection only, and returns branch B's four
commits with their parents. The attached `versioning/branch-history` walk on
branch A returns the same shape for the branch it is attached to. Intersecting
the two commit-id sets yields two shared ancestors; the later of them
(`:t` 536870913) is the fork point.

From that fork point, `since` on each branch gives each side's additions
(`["b-only" "unbound-write"]` and `["a-second" "a-third"]`), and
`(d/since (d/history db) t)` gives the true assertion/retraction stream quoted
in §1.3. This is the whole evidentiary basis for the §1.3 recommendation.

## 3. Gaps found

1. **`seon.db` owns no branch or commit reads** — filed as
   [seon-db-has-no-branch-or-commit-reads](../../../seon/issues/seon-db-has-no-branch-or-commit-reads.md)
   (friction). Every verb in §1 needs `branches`, `branch-as-db`,
   `commit-as-db`, and `parent-commit-ids`, and today the only way to get them
   is a direct `datahike.api` call outside the namespaces ruling #41 allows.
   This is the concrete blocker for W-C.
2. **The foreign-write fence reads only `seon.db/*conn*`** — filed as
   [foreign-write-fence-reads-only-the-dynamic-var](../../../seon/issues/foreign-write-fence-reads-only-the-dynamic-var.md)
   (blocker). Deleting the var in Phase 3 without re-rooting the fence on the
   environment silently admits every foreign-branch write.
3. **`versioning/branch-history` is unexported, channel-returning, and
   attached-branch-only** (`versioning.cljc:191-210`, absent from
   `api/specification.cljc`). Not filed separately — it is folded into gap 1,
   whose acceptance criteria require one walk owner rather than two.
4. **`diff` has a store-configuration precondition.** It is correct only with
   `:keep-history? true`. That is the creation default
   (`src/seon/cluster/store.clj:148-149`) and is creation-fixed with an
   explicit reopen mismatch refusal (`:304-316`), so the verb can check the
   stored setting and refuse honestly rather than answer wrongly. Named here so
   the implementer does not discover it from a wrong answer.
5. **No `:seon.cluster.agent/checkout` attribute exists yet.** §1.1 assumes
   the W-B fact from the checkout PRD; W-C cannot land before it. Not a defect
   — a sequencing dependency, restated so it is not rediscovered.

## 4. Ugly output

- **The refusal value is good.** `:seon.db/foreign-connection` carries both
  `[store-id branch]` connection ids, so the reader sees exactly which two
  branches were involved. Worth keeping as the template.
- **A raw commit walk is unreadable.** The probe's log is a vector of
  `{:datahike/commit-id #uuid "6a763cbe-0c2b-…" :t 536870915
  :datahike/parents [#uuid …]}`. Full uuids and raw 536-million transaction
  ids give a reader nothing to hold on to. If `log` is to be an agent-facing
  view it needs a declared `:seon.render/ai` producer that abbreviates the
  commit id (git shows seven characters for a reason), renders `:t` as an
  ordinal or a `txInstant`, and marks the current basis — otherwise the agent
  is reading a wall of hex.
- **Datahike's schema refusals are loud but the message buries the fix.**
  Using a non-unique attribute in a lookup ref logged two error lines and threw
  before the caller's own error path ran:
  `Lookup ref attribute should be marked as :db/unique: [:probe/label "a-first"]`.
  The sentence is accurate; it appears three times in three formats (a
  `:error` log line, a `:datahike/write-rejected` line, and the exception) for
  one mistake.
- **`d/datoms` refused an argument map** (`Wrong number of args (1) passed to:
  datahike.api.impl/eval…/fn`) where `d/q` accepts one. The message names an
  `eval`-generated function, so the reader cannot tell which arity was
  expected. Minor, but it is the kind of arity error an agent cannot self-
  correct from the text.

## 5. What this closes and what it does not

**Closed:** root's outer-scoped branch capability is demonstrably covered
under the environment model, with no new carrier and no dynamic var — root
enumerates branches, obtains any branch's head or pinned value in 0.2 ms
holding only the main connection, passes it explicitly to ordinary `seon.db`
reads where it beats current resolution, and is refused on a foreign write
while foreign reads stay open. All five assigned questions plus the log/diff
question pass on live evidence.

**Not closed:** the two filed defects (a missing `seon.db` read surface and a
fence rooted on a var slated for deletion) both sit on the path from this
design to W-C, and neither is fixed here.
