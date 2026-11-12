# Storage

One durable store holds every branch as Datahike index trees in konserve, one node per key, on LMDB, each node at the address of its content (`:crypto-hash?`, set by `seon.db/layout`). A commit costs the nodes it rewrites, so what it writes depends on how values are laid out, whether history is kept and how nodes are encoded. `seon.db/open!` makes and seeds a store that does not exist (```seon.db/store-cfg```, ```seon.db/made-cfg```), makes root's main from `:db`'s head when the store lacks it, maps the [ids](#ids) page, installs the attributes the store lacks and syncs when any of these wrote. Its failure points are in [failure points](failure-points.md), its config keys (`:seon.config.store/*`) in [config](config.md).

## Identity

A store is named by the id it records, never by where it lies, so a kept store opens wherever it is copied or moved. ```seon.db/store-cfg``` reads the id from the store's `:db` head (```seon.db/stored-id```) and mints one only for a store it is about to make; Datahike refuses a config whose id differs (`:store-identity-mismatch`). A copy keeps its original's id, so one process opens one of them. `(:config @conn)` names where the store lies now (`seon.db-test/a-moved-store-opens`).

## A value is read before its last release

A db value reads its index nodes lazily from its store's LMDB env, and the last `d/release` of the store's connections closes that env, so a value is read before its last release. A read after it answers `{:type :lmdb-env-closed :path}` (`konserve.jolt.lmdb-ffi/close-env` marks the env; `seon.db-test/a-read-after-release-is-a-typed-error`). A released env is not kept open, so a store removed and made again at its path opens its own files (`seon.db-test/a-store-made-again-at-a-removed-path-is-a-new-store`). A process keeps an older value only as a point (`seon.db/point`, made again with `seon.db/at-commit`; [root's heap](machine.md#roots-heap)).

## Who writes a branch

Each branch has one writer: an agent writes its own branch through its own connection, and root writes main through its `:writer` proc ([root](root.md#the-root-graph)); a change reaches main only by a merge ([sharing](sharing.md#a-merge)).

## A kept db value is a reference

A db value is used, then let go: whatever must keep a point in time keeps `{:branch :commit :t}` and makes the db again from it, and nothing hands a db value to another function or process to hold (owner, 2026-11-07: "don't pass around databases ever. We have a data format and schema for it."). A held db value pins its whole version of the index ([machine](machine.md#what-a-process-owns-in-memory)). An error kept past its moment follows the same rule ([errors](errors.md#an-error-keeps-data)).

## A point's commit

A commit's record holds its last transaction (`:max-tx`) and its parents, nothing more; what it holds is derived. Its transactions are those after its base's `:max-tx` up to its own (```datahike.versioning/commit-tx-range```); its base is its one parent, or for a merge the parent with the greatest `:max-tx` whose last transaction the merge's db holds. Transaction ids are issued once across branches ([ids](#ids)), so a db holds exactly its lineage's transactions. Without history a merge's base is refused `:commit-base-ambiguous`; Seon's store keeps history.

The point of a branch at transaction t is found by walking its lineage: ```datahike.versioning/commit-at-tx``` answers `{:branch :commit :t}`, the commit whose last transaction is the greatest at or before t; ```datahike.versioning/commit-holding-tx``` the commit whose span holds t. Each refuses with `:tx-after-head`, `:tx-before-first-commit` or `:commit-expired` (a commit collection removed). The walk keeps each commit record it read in memory by commit id (```datahike.versioning/forget-commit-points!``` drops it) but confirms every answer with one `exists?`. It stops at the first commit at or before t, so a point near the head reads few commits and an old one many (about 17k ticks a commit; a repeat costs only the `exists?`; `datahike.test.commit-at-tx-test`). Since the [retention sweep](#collection) removes old commits of main, a walk to an old point can answer `:commit-expired`; a caller that holds a commit id branches from it and asks no lookup.

## Durability is continuous group commit

A write is answered once the store has committed it, and one followed by an outside effect waits for the sync that covers it; no fixed collection point delays a commit (owner, 2026-10-21: "Why have an arbitrary 10ms collection point?"). The `window` check step proves it across a kill and a reopen ([checks](checks.md#modes)).

```seon.writer/writer-proc```, root's one writer, applies each step's batch (`datahike.writer/apply!` per write, then [main's content mark](#mains-content-mark) when it moved), commits it once (`commit-pending!`, unsynced) and only then answers, so any process reading main after an answer reads the write ([decisions](decisions.md#a-write-to-main-is-answered-after-the-store-holds-it-owner-2026-11-10)); a `:flush?` write is answered after the sync. A one-datom write is answered in about 8 ms at p50. Where root is the store's sole writer (`:seon.config.store/sole-writer?`), its main connection streams (```seon.db/open-main!```), so a deref is its own value rather than a read of the head. An op that throws after a batch it committed keeps that commit (```seon.procs-test/a-refused-op-keeps-the-batch-committed-before-it```).

A store is opened ``MDB_SAFENOSYNC`` (```seon.db/store-cfg```): the flusher's ```seon.db/sync!``` makes every commit so far durable. A killed process, root included, loses nothing; an OS crash or power loss loses only the commits after the last sync ([failure points](failure-points.md#lmdb-crash-safety)).

## The map is address space

LMDB's map (`:seon.config.store/lmdb-map-bytes`, 64 GiB) is address space only: an open or a fork costs the same at any size, and the file grows only as it is used. Anything that walks a map page by page pays for its size under a lock every process mapping the store shares, so a process's memory is read with one `task_info` call (`jolt.host/process-footprint-bytes`, `jolt.process-test/a-process-reads-its-own-footprint`). A smaller map is no fix: at 1 GiB Datahike's commit failed.

## The proof: what a commit costs

`seon.db-test/a-commit-costs-what-it-writes` says a turn committed alone costs what it costs inside one big commit, plus one root-to-leaf path per index (32 KB at most), on real turn text (the checkout's `docs/` and `src/`, since repeated letters compress unrealistically well). Twenty turns, LMDB, branching factor 64:

| layout | per turn / bound |
|---|---|
| values inline, no compression | 1.82 |
| values inline, LZ4 | 1.16 |
| out of line from 1,024 B, no compression | 0.50 |
| out of line from 1,024 B, LZ4 | 0.33 |

Inline values were the cost: every commit rewrites the tail leaf of `:eavt` and `:aevt`, so an inline turn's text was written again by every later commit until its leaf split. Out of line, the cost stays near flat as turns grow; the default codec, zstd, writes less again ([compression](#compression)).

## What a datom costs to add

`seon.db-test/a-datom-costs-its-index-inserts` holds a datom added in memory (`d/db-with`) to its floor: its `:eavt` and `:aevt` inserts and one descent for a cardinality-one attribute's current value, about 7k ticks and 11 KB. An identity costs more than its `:avet` seek should (`seon.db-test/an-identity-costs-its-seek`, an accepted overrun). Datahike's reverse schema is updated by the attribute added, not rebuilt ([datahike](forks.md#datahike)), so ```seon.db/make!``` costs its schema and seed linearly; a test that needs several stores branches one seeded store (```seon.test-store```, in memory). A branch made and deleted (```seon.db/open-main!```, ```seon.db/delete-main!```) costs the same at any store size (```seon.db-test/a-branch-costs-what-it-holds-not-the-store```). A fork of a kept store (`datahike.api/fork-database`) is cheaper but not what `open!` makes: its roster lacks ids and `:db`, a gap in our Datahike to fix before a test store is forked. `seon.db/install-missing!` compares only `:db/contentId` and `:db/index`, so a changed `:db/noHistory` is not seen.

A one-datom write through root's writer rewrites about 25 index nodes, 13 of them the transaction entity's own datoms (`:db/txInstant`, `:seon.tx/runtime`, `:seon.tx/branch`); the commit is about 112k ticks fixed and 18k a node. So an attribute indexed by value or kept in history costs nodes on every write that carries it: `:seon.tx/branch` is not indexed by value, and `:seon.system/collected` keeps no history. Ten transactions in one commit wrote 31 nodes, not 250: what shares a commit is worth more than any per-key cut.

## Plain data

Plain data is what the store keeps as data and EDN carries, and one predicate says what it is: ```seon.contract/plain?```, the schema `:seon.db/plain`: nil, a boolean, number, character, string, keyword, symbol, uuid or instant, or a map, vector, set or list of them. It is narrower than Datahike's `datahike.schema/plain-data?` on purpose: no lazy seq, no bytes, no sorted collection, no record (`seon.db-test/plain-is-what-the-store-keeps`). What is not plain is made so where it is written: `seon.contract/plainly` answers data and never prints (a record as its fields, a sorted collection unsorted, a var as its symbol, anything else as `{:seon.value/type <class>}`; every error passes through it at `seon.contract/error`), and `seon.contract/kept` bounds what an error holds. An eval's record keeps a value only up to `:seon.config.store/max-value-chars` (counted by `seon.contract/within?`); past it the response holds `{:seon.value/gone {:type :over}}` and the handle (``result/e<n>``) still reads the value in the live process.

## The write checks every datom

A write holds each value to its attribute's declared schema, its branch's form where the branch registered one, so a value the schema refuses is never stored. ```seon.db/invalid-datom``` runs at the two points every write passes (```seon.agent/transact!``` and the writer's `:tx` op) and answers `:seon.db/invalid-datom`, naming the attribute and where in the value. It leaves alone an attribute no schema declares, a retraction and a transaction function. Each attribute's validator is found once a transaction from the registry in effect (```seon.contract/held```), so a branch that changes a schema is held to the new one at its next write; the check costs one validator call a datom (```seon.db-test/the-datom-check-costs-a-validator-call-a-datom```).

## The write checks every entity it touches

A transaction is refused, and nothing of it stored, when it leaves an entity it touched holding attributes an entity schema owns without one that schema requires ([a schema is a set of keys written together](data-modeling.md#a-schema-is-a-set-of-keys-written-together)). ```seon.db/invalid-entity``` reads Datahike's tx report before it becomes the value; the refusal is `:seon.db/invalid-entity` (the entity, the schema, the missing keys). An entity the write only refers to is not asked.
- **What a write broke.** A schema is asked of an entity when the write added or retracted an attribute it owns. It refuses a required key held before and not after, and, for an entity the write makes that thing, every required key it lacks. An entity already incomplete is left alone by a write that broke nothing of it (`seon.db-test/a-write-holds-each-entity-it-touches-to-the-schemas-its-keys-select`).
- **A branch's own schemas.** A branch that registered or changed entity schemas is held to its own: the write's `{:forms :registry}` give the entity index again for that write; every other write reads the binary's.
- **Cost.** One seek per (touched entity, required key) on the values before and after; never an entity's size or the store's.
- **One path, every transaction.** A transaction on a db value goes through `seon.db/with` (or `seon.db/db-with`), one on a connection through its checked `'transact!`, ```seon.db/transact-checked```, which ```seon.db/connect``` gives every connection's writer; a function never checks a write itself, and a state the path refuses is fixed at its cause, never exempted. A hypothetical whose result is never stored is `seon.db/speculate` (or `seon.db/speculate-db`), unchecked. `bin/laws transactions` is red on a Datahike transaction at a site not named here: `seon.db/with`, `seon.db/transact-checked`, `seon.db/connect`, `seon.db/speculate` (the path and the hypothetical); `seon.db/seed!`, `seon.db/install-missing!`, `seon.db/retain!`, `seon.system/import-tree!`, `seon.system/seed!`, `seon.system/boot!`, `seon.system/kept-index!`, `seon.agent/init!`, `seon.agent/merge-main!`, `seon.writer/other-op` (each a transaction on a connection, through its checked `'transact!`; a writer's ``'merge!`` is built over that `'transact!`, ``datahike.writer/writer-fn-map``, so a merge is checked too); `seon.agent/transact!`, `seon.writer/commit-batch`, `seon.writer/mark!` and `seon.writer/writer-proc` (the run-ahead `apply!`, the writer's batch and [content mark](#mains-content-mark), through the same `'transact!`).
- **A speculation applies what it reads.** A caller reading a few attributes after a hypothetical speculates the transaction cut to them (```seon.db/project-tx```), never the whole (`seon.source-test/a-speculation-of-what-it-reads-reads-what-the-whole-would`).
- **A transaction's time is the store's.** The path refuses a supplied `:db/txInstant` (```seon.db/supplied-instant```): Datahike stamps each transaction strictly after its branch's last, so no two transactions of a branch share an instant (`seon.db-test/a-transaction-never-supplies-its-own-time`).
- **An entity named by a ref ends with its target.** When a transaction retracts an entity whole, the path also retracts the entities whose schema requires a ref to it (a vector's `:seon.vector/of`, a cite's `:seon.cite/target`, a form's `:form/ns`); no attribute is a component. A cite it ends leaves its task `:seon.task/gone` in the same write ([task references](references.md#task-references); `seon.db-test/an-entity-named-by-a-ref-ends-with-its-target`).

## Entity ids

The entity whose unique attribute holds a value is found by `seon.db/eid`: one seek in that attribute's AVET index, never a Datalog query, and every namespace calls it. Reading an entity's facts needs no id first: a pull by lookup ref (`(d/pull db attrs [:seon.system/id :root])`) answers nil when no entity holds the value.

## Ids

Every entity id and transaction id is issued once across the whole store, every branch and every process, so a datom names the same entity and transaction wherever it travels. The ids come from one page every writing process shares (```seon.db/ids!```, `native/ids.c`): two 64-bit words mapped from the file `ids` beside `data.mdb` (an anonymous page in memory), which a forked process inherits. Datahike takes them through its id source (`datahike.ids`, `datahike.writing/set-ids!`):
- a transaction takes its id when it begins, and a branch's t (`:max-tx`) is its last transaction's id: increasing along the branch, with gaps. A merge carries transactions with their older ids, so time, not id, orders them ([history](#history));
- each fresh entity id is one atomic fetch-add (about 30 ns);
- the page is moved past every branch's head when it may be behind: new, copied, beside a replaced data file, or after the OS lost dirty pages. It carries a stamp (the OS boot identity and the device and inode of `ids` and of the data file, ```seon.db/data-file```), so an open reads the heads only when the stamp is not this boot's and these files' (once per boot per store, after a compaction); a stamped open reads no head. Not caught: a data file overwritten in place, or a volume that lost pages without a reboot. An issued id never committed is a gap, never reused;
- an explicit id in tx-data (a carried datom's) moves the page past it.

**Content ids.** An entity with an identity marked `{:seon.db/content-id true}` (Datahike's `:db/contentId`: `:form/id`, `:var/name`, `:ns/name`, `:file/path`, `:seon.agent/key`, `:seon.message/id` and the rest) takes its id from its identity: `datahike.db.transaction/content-eid`, hasch's hash of `[attribute value]` taken into `[2^40, 2^60)`. Every branch that mints one identity mints one entity, so a merge carries datoms with no remapping. Counter entity ids lie below `tx0` (2^29), transaction ids in `[tx0, 2^40)`. A map takes its content id wherever Datahike resolves it, nested or not (```datahike.db.transaction/entity-map->op-vec```), so one identity in one transaction is one entity; an identity the db already holds keeps its id. Asserting another value of an identity on its entity is refused `:entity-id/collision`. Proofs: `seon.db-test/an-identity-names-its-entity-on-every-branch`, `seon.db-test/a-nested-map-takes-its-content-id`, `seon.db-test/content-ids-cost-the-writes-the-model-says`, `datahike.test.content-id-test`.

**One root per store.** Two roots would issue distinct ids, but two things still forbid it: `d/branch!` updates the branch roster under a lock of its own process only (task `seon.db/two-processes-branch-one-store-at-once`), and a root collects only as the store's only writer ([collection](#collection)).

A speculation takes no ids. The fork server's replica of main is each stored commit of main (```seon.writer/stored!```), so its t is main's. Proofs: `seon.db-test/each-id-is-issued-once-across-branches`, `seon.db-test/a-reopened-store-issues-past-every-head`, `seon.db-test/an-open-reads-no-head-but-its-own`, `seon.db-test/a-killed-writer-leaves-the-page-past-its-commits`, `seon.db-test/a-copied-or-new-page-is-moved-past-the-heads`, `seon.db-test/a-page-from-another-boot-is-moved-past-the-heads`, `seon.db-test/a-page-beside-a-replaced-data-file-is-moved-past-the-heads`, `bin/check ids`.

## A root's main and its branch names

Checks and tests share one store, so a branch's name is qualified by the root that owns it. ```seon.db/main-branch``` is a root's main (its config's `:branch`, `:db` when none), ```seon.db/conn-main``` the same for a connection; ```seon.db/on-main``` names branch b of a main as the store does (unqualified on `:db`), ```seon.db/off-main``` undoes it, ```seon.db/of-main``` lists a main's branches. ```seon.db/branch?``` asks by one read of the head key; ```seon.db/open-main!``` gives a root its main (made from `:db`'s head when absent) and answers `[conn existed?]`; ```seon.db/head``` reads a branch's committed value from the store; ```seon.db/store?``` asks whether an LMDB store exists at a path; ```seon.db/agents-dir``` is where a root's agents keep stderr. A check's or test's main is deleted by the run that made it (`seon.db/delete-main!`).

## Forked processes read memory

Every forked process opens the store itself ([an agent's reads and writes](#an-agents-reads-and-writes)). The fork server reads every node of main's indexes into its heap once (```seon.db/preload!```), so the agents it forks inherit them decoded. ```seon.db/map-use``` answers `{:used-bytes :map-bytes}` for a store.

## A commit's report

The writer turns Datahike's tx report into what main's readers receive: ```seon.db/report->tx```, the report as explicit-id tx-data, the transaction entity's own datoms left out. Our Datahike puts the retraction of a replaced cardinality-one value in the report (```datahike.transaction-test/a-replaced-value-is-retracted-in-the-report```), so a reader sees a ref move away from its old parent, and two keywords in a cardinality-many value are two values, never a lookup ref (```datahike.transaction-test/a-keyword-attributes-two-values-are-two-values```).

## What a read scanned

Datahike records what a read scanned, so a cached view is valid until a transaction touches it ([render](render.md#components)). `d/with-reads` runs a read and answers `[value reads]`; `d/reads-index` keys them by what was scanned: `[:e e]` and `[:ea e a]` (a pull), `[:av a v]` (a point query on a unique value), `[:a a]` (an attribute scan), `:any` (an open range). The recorder is a dynamic binding, so a read inside a Flow step or its go block is recorded. `d/reads-touched?` asks whether a transaction's datoms touch an index; a schema datom touches every read. It costs the transaction's datoms, never the reads recorded (```datahike.reads-test/a-transaction-touches-only-what-a-read-scanned```, ```datahike.reads-test/matching-is-flat-in-what-was-read```).

The same fork refuses a nested call inside a query predicate's arguments rather than read it as a constant (```datahike.query-test/a-nested-call-in-a-predicate-is-refused```), and a predicate that throws is an error, never a false (```datahike.query-test/a-predicate-that-throws-is-an-error```).

## An agent's reads and writes

An agent reads and writes its own branch through four functions it has in every namespace: ```my.db/db``` (the branch's value in this process), ```my.db/q``` and ```my.db/pull``` against it, and ```my.db/transact!```, which answers `{:t :datoms}` once its writer has committed. Inside a test fork a transaction applies to the fork's db only.

A forked process reads the store through an env of its own, since LMDB forbids using an env across `fork()`. A child forgets what it inherited (```seon.db/after-fork!```: env and connections dropped, never closed, node caches kept), connects on its own, and makes each db value it holds again by its commit: an agent its main (```seon.agent/init!```), a clone or test child its parent's branch, base, main and index (```seon.agent/own-store!```; an agent commits its run-ahead before forking a test child). A read through an inherited env is refused, ``MDB_ENV_FORKED`` ([failure points](failure-points.md#lmdb-locks)).

## Merges read what changed

A merge decides what merges from the datoms that differ, never by reading whole programs. ```seon.merge/delta-three-way``` takes the datoms that differ between the base and each side (`datahike.api/datoms-diff`, which skips every subtree two trees share by address), the program keys they bear (```seon.merge/touched-keys```, ```seon.merge/touched-by```), and reads each key's content on base, ours and theirs by seeks (```seon.merge/program-of```); it answers what `seon.merge/three-way` over whole programs answers (`seon.merge-test` checks both). The writer's merge, `:branch-diff`, an agent's `merge-main!` and `seon.merge/unmerged` read this way; ```seon.merge/program``` stays for tests and `mg/check`.

`datoms-diff` costs the nodes that differ, growing with the store only by the tree's height (`seon.merge-cost-test/a-diff-of-two-commits-costs-the-nodes-that-differ`). On a temporal or filtered db value it reads both sides' datoms over the range asked, so give it bounding components.

What merges is written as it is (```seon.merge/merge-tx```, ```seon.merge/carried-tx```): the branch's own datoms for the merging keys, with their entity and transaction ids, retractions first, and their transactions' facts, after the declarations of attributes main lacks. Ids are store-wide and identities take [content ids](#ids), so a merge adds what the branch wrote, never a copy. Platform records (evaluations, model calls, messages) bear no program key and stay where they were written (`seon.merge-test/a-merge-carries-the-branchs-own-datoms`). The three-way stays flat in program size (`seon.merge-cost-test/a-three-way-costs-the-changes-not-the-program`, on a store on disk, since in-memory nodes have no addresses to share).

## Main's content mark

Main moves on nearly every request by records that are no program, so readers of main's content judge by a mark instead of diffing: main's root entity carries `:seon.system/content-t`, a t past every t main held before the commit that last moved its content. ```seon.writer/writer-proc``` moves it in that same commit when the commit's datoms touch content (```seon.merge/moves-content?```), and at its own start. Two commits of main with one mark hold the same content (```seon.merge/same-content?```, two entity reads), so ```seon.agent/merge-main!```, ```seon.agent/main-changes``` and ```seon.merge/content-commit``` answer without a diff. An unmarked db is diffed, and a merge that declares data reads its diff. Only main's points are asked. Proofs: `seon.procs-test/the-writer-marks-the-commit-that-moves-mains-content`, `seon.merge-test/same-content-is-read-from-mains-mark`, `seon.agent-test/a-merge-of-a-main-moved-only-by-records-reads-no-content`.

Main's readers in root judge a commit by its `:ops`: the test service and the fork server's reach graph move only at a commit that moves the program (```seon.program/moves-program?```); the render and the reconciler read records, so every commit is theirs; the vectors proc runs a round on every commit (task ``seon.vector/a-commit-of-no-text-starts-no-round``).

## The union

Every datom any branch committed is held once in U, one ordinary Datahike db queried with `d/q`, `d/pull` and `d/entity` over `(d/history U)` (```seon.union```; at root `(my.root/union sys)`). Ids are store-wide and merges write datoms as they are, so U is a set union with nothing to resolve. Each datom's transaction names its branch (`:seon.tx/branch`, ```seon.db/tx-meta```). U's schema lifts every attribute to cardinality many with no uniqueness (```seon.union/lifted-schema```), since constraints belong to a branch.

Root's writer keeps U in memory: built when first asked (main's history, then each branch from its merge base), then each commit of main or an agent adds that branch's datoms since (```seon.union/catch-up```). One agent's conversation across main and its branch is one query (`seon.union-test/a-message-and-its-reply-on-two-branches-are-one-query`):

```clojure
(d/q '[:find ?body ?reply ?branch :in $
       :where [?m :seon.message/from "root"] [?m :seon.message/body ?body]
              [?r :seon.message/from "demo/f#1" ?tx] [?r :seon.message/body ?reply] [?tx :seon.tx/branch ?branch]]
     (my.root/union sys))
```

A build costs about 3 ms a branch past main's history; keeping it current about 1 ms a commit (`seon.procs-test/the-writer-keeps-the-union-current`).

## History

A store keeps history when `:seon.config.store/keep-history?` is true, the default: `d/history`, `as-of` and `since` work, a retraction stays a datom, and a transaction keeps its tx-meta (``:db/txInstant`` among it; Datahike drops tx-meta without history). The flag is fixed when a store is made, so ```seon.db/made-cfg``` adds it only at creation and ```seon.db/store-cfg``` never names it (`seon.db-test/the-store-keeps-history`, `seon.db-test/a-kept-store-keeps-the-history-it-was-made-with`).

Every transaction Seon makes names its branch and runtime in tx-meta (`:seon.tx/branch`, `:seon.tx/runtime` = `jolt.host/runtime-fingerprint`), from ```seon.db/tx-meta```; a runtime Jolt cannot name is refused by its contract. ```seon.db/install-missing!``` installs those two attributes first, then every other attribute the store lacks in one transaction carrying tx-meta. A branch keeps the schema it was created with, so a resumed agent installs on it too before loading its program, and writes the program facts its forms were recorded without (```seon.program/gained-tx```; `seon.system-test/an-agent-resumes-a-branch-cut-before-an-attribute`). Proofs: `seon.procs-test/the-writers-transactions-name-their-runtime`, `seon.agent-test/an-agents-transactions-name-their-runtime`.

The fork server's replica of main is main's stored commit itself: the writer sends one notice per stored commit (```seon.writer/stored!```), and the fork server loads that commit (`d/commit-as-db`) and runs ```seon.program/load-changes!``` over its ops (`seon.procs-test/the-fork-servers-replica-is-main-at-each-commit`).

An agent reads history at its REPL: ```seon.db/history``` answers an entity's attribute values over time, oldest first, as rows `{:a :v :added? :t :at}` (`:attrs` narrows, `:pull` draws a ref's value as of its row); ```seon.db/at``` answers the db at a `:t`, `:commit` or `:branch`. A store with no history answers `:seon.db/no-history`, and a point it does not hold `:seon.db/unresolved`, never an empty history or a nearest point. `my.agent/history` takes a var's symbol; `my.db/at` is the agent's own branch's.

A transaction's t is its id, unique across the store, so a merged datom keeps the t it was written at; but t does not order transactions across a merge. What orders them is their time, `[:db/txInstant, tx id]` ([decisions](decisions.md#ids-are-unique-across-branches-order-by-time-not-by-transaction-id-owner-2026-11-11)). Datahike stamps ``:db/txInstant`` as the later of the wall clock and one millisecond past its db's last transaction (``datahike.db.transaction/next-tx-instant``), and Seon's write refuses a supplied one ([the write check](#the-write-checks-every-entity-it-touches)). Across branches compare by commit (`seon.db/branch-base`).

History costs nothing on appended turns (the temporal indexes hold only replaced and retracted datoms); replacing a cardinality-one value costs about 1.5x a commit, since it also writes a temporal path.

## Compression

konserve compresses every stored value with the codec `:seon.config.store/compressor` names: `:zstd` (the default, level 3), `:lz4` or `:none`; each value's header names its codec, so a store holding several reads them all. The codecs are `konserve.jolt.zstd` and `konserve.jolt.lz4` over `jolt.ffi` in the [konserve fork](forks.md), their libraries built from pinned source (`bin/build zstd`, `bin/build lz4`) into `target/lib` and exported by `bin/env` (`JOLT_ZSTD_LIB`, `JOLT_LZ4_LIB`). Proof: `seon.db-test/stored-values-compress-through-the-codecs-the-build-made`. On Seon's small values zstd-3 compresses about 2x against LZ4's 1.5x at a cost of a few µs, well under a commit's sync. A dictionary trained on our own values would compress 2-5x better; it is not used until a corpus represents the running system.

## Values out of line

A datom value at least `:seon.config.store/value-out-of-line-bytes` large (1,024; `:never` keeps every value inline) is stored once under its own key, and the index nodes hold a reference to it (Datahike's `:index-config :value-out-of-line-bytes`, in ``datahike.index.persistent-set/CachedStorage`` of [our fork](forks.md)):
- `store` writes a `ValueRef` to each such value's content key (hasch's uuid) and writes the value once; `:eavt` and `:aevt` share it.
- `restore` swaps the values back before a node enters the cache.
- GC's mark adds the keys each node references, so a value lives while a tree reaches it.

The key of a value known to be stored is cached under its datom; a value only queued is not, so a failed commit leaves no reference to a missing value. Nodes describe themselves, so a store reads either kind whatever the config says now (`seon.db-test/a-large-value-lives-once-under-its-own-key`). 1,024 B takes nearly all the gain: a turn's values are 1.5-2.5 KB.

## A run-ahead value shown on the connection

A writer that is its branch's only writer may answer a write when it is applied and store it afterwards. `datahike.writer/apply!` applies a transaction to the connection's run-ahead value; with `{:show? true}` that value is also the connection's, so the next deref reads the write. `commit-pending!` stores the newest value in one commit; `abandon-pending!` drops the pending writes.
- **A shown value has no commit:** its `:datahike/commit-id` is nil and `:datahike/uncommitted-parent` names its base (`datahike.writer/shown?`), so whatever needs a commit id waits for the commit.
- **One kind of write at a time:** while a run-ahead is pending, a transact, batch or merge on that connection is refused (`:run-ahead-pending`).
- **The commit is made from the unmarked value.**

Proof: `datahike.test.run-ahead-test` ([forks](forks.md#datahike)).

## What a connection's deref reads

A deref of a connection whose writer does not stream reads the branch's head from the store and builds a db value from it every time (about 59k ticks, against 24 for a held value), so a process sees other processes' commits. A run-ahead's shown value is answered without it. A `:sync` writer with `{:writer {:backend :sync :streaming? true}}` derefs to the value it holds; root's main connection streams this way when root is the store's sole writer (```seon.db/open-main!```).

## A moved head moves its live connection

Datahike's `force-branch!` moves this process's live `:sync` connection to that branch with it: the connection's value becomes the new head and its writer's next commit is built on it, so a write after the move keeps what the move brought. Run-ahead writes pending on that connection refuse the move first. A connection in another process is not told; its writer must not share the branch ([who writes a branch](#who-writes-a-branch)). Datahike's `datahike.connector/follow-head!`; test `datahike.test.force-branch-live-conn-test`.

## A commit the store refuses

A commit can throw on a value its writes hold (a value hasch or the codec cannot encode), and that must leave the agent alive, so the commit is the edge ([failure points](failure-points.md)): `seon.agent/commit!` catches the throw, drops the writes applied since the last good commit (`datahike.writer/abandon-pending!`), resets the agent's `:db` to the committed value (`seon.ctx` patches `@ctx` by the difference) and answers `:seon.agent/commit-refused`. The agent hears it beside its next eval's answer and as a message from seon: what its code wrote did not stay, the definitions it evaluated are still in the process (`seon.agent-test/a-commit-the-store-refuses-leaves-the-agent-alive`; a ratio inside a large value, `seon.db-test/a-large-value-holding-ratios-commits-and-reads-back`).

## Plain values

A value that is plain data is stored as data: its attribute is ``:db.type/data`` in [our Datahike](forks.md), and the serializer (jfasl) writes it with no text between. The schema says so by its shape: a vector, sequential, tuple, map, map-of, `:any`, or an `:or` whose arms differ in type is plain data (```seon.schema.datahike/declaration``` answers the Datahike attribute map, or `:seon.schema/unstorable` saying why there is none). A vector keeps its order and duplicates; a `[:set T]` of scalars or refs stays cardinality many, one datom a member, so a query can join on a member. `datahike.datom/compare-value` orders plain data consistently with `=`. A value type is fixed once installed, so a kept store holding an attribute as another type is refused at open with `:seon.db/types-differ` (`seon.db-test/a-kept-store-whose-types-differ-is-refused`). jfasl is the size of EDN text and reads and writes about 9x faster.

## Collection

Root collects the store only as its only writer, at quiescence, with no agent live. The reconciler asks once per quiet spell (```seon.reconcile/quiet```, which records `:seon.system/quiescent`), root refuses while an agent is live (`seon.system/api-proc`, `:collect`), and the writer runs ```seon.db/collect!```:
- With `:seon.config.store/sole-writer?` false (the `:scripted` profile of test shards and check steps, whose roots share a store) it sweeps and records nothing.
- When a branch named in main's `:seon.system/collected` record is gone, it runs `datahike.api/gc-storage`, sweeping every key no branch reaches; when branches were only added it records them and sweeps nothing. A new main only records what it sees, so a check boot on a store of many branches does not sweep at its first quiescence.
- The record (`{:swept :ms :branches :gone}`) is committed through the writer's batch.
- A sweep frees pages but never shrinks the file, so it leaves a mark, and the next boot first replaces the store by a copy of its pages in use (```seon.db/compact-if-due!```), atomically by rename.

Seon's `:sync` writer runs the collection itself, not as a transaction. Proofs: `seon.db-test/a-collection-runs-when-a-branch-is-gone`, `seon.db-test/a-root-that-shares-its-store-never-collects`, `seon.db-test/gc-runs-through-the-sync-writer`, `seon.procs-test/a-collection-waits-until-no-agent-is-live`, `seon.reconcile-test/quiescence-is-reported-when-nothing-can-move`.

**Retention.** A long-running store grows by commit snapshots, which deleted branches do not free. So at boot, while root is the only process, ```seon.db/retain!``` sweeps what is older than main's newest `:seon.config.store/keep-commits` (1,000) commits, once that many reached main since its last sweep, by the commit graph's generation, never a clock; the next boot compacts. Each branch's head, its merge base with main and each commit an agent's facts name stay whole ([datahike](forks.md#datahike), `jolt/gc-keep`). What goes is reading an old commit by id; a point past the keep is still read exactly as of its t, but no commit is left to fork a session from, so it expires ([decisions](decisions.md#a-new-session-starts-at-the-commit-at-or-before-a-point-a-read-is-exact-at-any-transaction-owner-2026-11-11)). A turn's basis holds its `:t` too, so the debug page draws a past turn as of t, saying where the draw differs from what was sent.

**Why mark and sweep, at quiescence, as the only writer.** With content-addressed nodes ([sharing](sharing.md#what-a-branchs-nodes-are)) a node may be named by several branches, kept commits or indexes, so it is garbage only when nothing reaches it: `datahike.gc/gc-storage!` marks from every head and kept commit, and Datahike's online collection stays off. A full mark costs about 25k ticks a live node, linear and independent of the number of branches. A commit can reuse a value out of line the sweep is about to delete, and Datahike's collection sees only its own process's in-flight commits, so collecting beside live agents or other writers is not built. The node cache trusts a value's cached key only within the collection generation it was found in (`datahike.index.interface/collection-key`, `jolt/gc-cache-epoch`; `datahike.test.gc-value-key-test`). A live agent holds its branch: root refuses a second spawn on it and caps live agents at `:seon.config.root/max-agents` ([root](root.md#holders-and-refused-spawns)).

## Compiled code

The store holds definitions (`:form/src`), never compiled code: Jolt's compiled units are 15x their source, hold absolute paths and are valid only under the runtime that compiled them (task `seon.program/compiled-units-in-the-store`). A process gets its program by inheriting it (an agent forks the fork server's image, copy-on-write) or by loading what it lacks: ```seon.program/load-all!``` (every live form the binary lacks), ```seon.program/load-changes!``` (the forms and removals a merge's tx-data carry; ```seon.agent/merge-main!``` loads them after its merge commit) and ```seon.program/load-diff!``` (the forms one db has over another). Evaluations are records nothing re-runs. A branch's program at commit C is `d/commit-as-db` at C and `load-diff!` to it: code and data definitions restore exactly; host cells come back empty and host objects are made fresh, as at boot.

An agent's `:base`, against which it chooses the tests that judge its branch, is main at its merge base (```seon.db/branch-base``` after each merge). A turn reads what its branch changed since once (```seon.agent/branch-changes```, ```seon.merge/delta-changes```), and its readers project it: ```seon.merge/divergence```, ```seon.merge/changed-vars```, ```seon.merge/uncontracted``` (```seon.program-cost-test/a-turns-diff-reads-what-the-branch-changed```). The writer's `:spawn` names main's commit and t on `:fork`, and an agent whose branch or image is elsewhere (```seon.agent/misaligned```) reports `:seon.agent/fork-misaligned` (`seon.procs-test/a-fork-names-the-commit-its-agent-starts-at`, `seon.procs-test/a-fork-at-another-commit-is-recorded-misaligned`).

A session is restored at any commit C of its branch the same way, on the current main's image, never a binary of that commit: a spawn with `{:branch b :commit C}` makes `b` from C (`datahike.api/branch!` takes a commit id), and the agent loads the session's change since its base up to C (```seon.program/load-diff!```); a definition that does not load is named in the answer (`:started-without-program`), as is what is not restored (```seon.system/not-restored```). A commit the store no longer holds is refused before anything is written ([failure points](failure-points.md)). Proofs: ```seon.agent-test/a-session-restored-at-an-older-commit-runs-that-commits-definitions```, ```seon.agent-test/a-restore-at-a-commit-the-store-no-longer-holds-is-refused```.

## A renamed attribute

A stored keyword is renamed by erasing the store, never by a migration ([decisions](decisions.md)), so a store made before a rename is refused at open: ```seon.db/retired-names``` holds each old name and its new one, and ```seon.db/install-missing!``` throws `:seon.db/name-retired` naming them and changes nothing (`seon.db-test/a-kept-store-on-a-retired-name-is-refused`).
