---
title: "Alignment dig: every place Seon keeps a point in time by hand"
type: research
status: dig (evidence only; no design, no judgement)
created: 2026-10-15
lane: align-dig (Haiku, gather and quote only)
basis: HEAD 53e3d464a
---

# Alignment dig: every place Seon keeps a point in time by hand

Owner question (2026-10-15): "I want immutable solutions that work with the system. We are always talking about aligning
the same things: SCI and db, points in time, random agent test envs, whatever. How can we best support this?"

Root's working thesis, for designers to test: a point in time is one immutable value (a Datahike commit id). Everything
derived is a pure function of commit ids and content digests, cached by content. "Alignment" bugs appear where Seon keeps
a mutable or positional notion of "which point in time" that must be kept in step by hand.

Method: grep and read at HEAD `53e3d464a` over `src/`, `resources/`, the 2026-10-1x research notes, and `git log` since
2026-10-08. Each row is a symbol, a one-line quote from the code or doc, what it aligns, how it is kept aligned today, and
any linked bug. This is an inventory, not exhaustive: it is what the searches found in the time box. Rows marked
**(verify)** are read from a commit subject or a note and were not reproduced by this lane.

Categories: **A** SCI vs db, **B** points in time, **C** test environments, **D** caches keyed by place or stamp,
**E** connections, branch lifecycle and live objects.

Legend for "kept aligned by":
- `commit-id` = the value is a Datahike commit id (immutable, content-like)
- `t` = a transaction t (branch-local after a fork, per exp-c)
- `atom` / `volatile` / `defonce` / `delay` = JVM mutable cell or memo
- `held conn` = a live Datahike connection the code holds
- `stored copy` = a fact written once that must be kept equal to something else
- `reload` = a JVM reload or adoption moves the loaded program
- `stamp` = a value compared by equality to decide validity
- `registry` = a per-execution map of live objects

---

## A. SCI vs db (how an agent's program context is aligned with its branch)

| # | Symbol | Quote | Aligns | Kept aligned by | Linked bug |
|---|---|---|---|---|---|
| A1 | `seon.sci.eval/loaded-identity` | `(defonce loaded-identity (atom {}))` ("this value names the JVM's loaded program") | loaded JVM Vars vs adopted commit | atom, advanced by `advance-loaded-identity!` (`reset-vals!`) at adoption; `reload` | none found |
| A2 | `seon.sci.eval/call-detecting-program-change` | "A change by another thread since `since` ... answers the refusal naming both identities instead of the result." | a result vs the loaded identity it was computed under | compare-and-answer: read identity at start and end, `stamp`; no lock | none found |
| A3 | `seon.sci.eval/loaded-source` and `::loaded-connection` | "its `:seon.source/commit-id` moves when `seon.cluster/development-source-refresh!` has reloaded and armed" | SCI program base vs the loaded commit | held conn of the adopting cluster, read at use; `reload` | none found |
| A4 | `seon.cluster/...` development adoption (`:seon.source/commit-id` on the cluster-name entity) | "Development adoption basis moved before its record." | the adopted commit record vs the published head | stored copy written after reload, guarded by CAS on the expected prior and published commit | 806a97324 (adoption record moved onto chain branch; prove pass parked on overlay) (verify) |
| A5 | `seon.cluster.agent/adopt-cluster-declarations!` | "forked before an adoption converges at each acquisition, before any write." | a branch's program vs the cluster's adopted declarations | re-applied at each acquisition from `(db/carried-projection database)`; pull, not push | none found |
| A6 | `seon.cluster.agent/acquire-context!` | "Acquisition requires the handle's registry and an explicit agent branch." | agent's SCI context vs its branch's connection vs its agent id | registry atom keyed `[branch agent-id]` (`:seon.agent/contexts`); held conn (borrowed or opened); reacquired on each acquisition | none found |
| A7 | `seon.cluster.agent/acquire-context!` (`held-base`, `sci.eval/branch-ctx`) | "A branch no execution holds yet gets its program as one copy, acquired for `selected`." | the program copy vs the branch's commit | place-keyed reuse: a branch held by an execution reuses its base ctx; first acquisition copies | 10-14 `sci-reuse-across-tasks.md` (measured per-branch acquisition cost) |
| A8 | `seon.sci.eval/program-context` | "One `seon.cache` entry per program content (`program-content-key`), so equal programs on many branches and commits answer the identical value" | program ctx vs `database`'s revisions | content key: `[store-identity revisions dial-revisions supplied-loaded commit]`; `predecessor` content key for incremental derive | 10-14 `digest-and-def-defects.md` defect 2: a private `def` vanishes between MCP evaluations ("the program context interprets rows without the agent's private Vars") |
| A9 | `seon.sci.eval` `::kernel/installed-functions` and `::kernel/program-snapshot` (created as `(atom #{})`, `(atom {:functions {} :namespaces {}})`) | "fresh kernel atoms of the value's program, and the value it forked from." | the SCI context's installed functions vs its program | per-context atoms, rebuilt from the program at fork (`carried-snapshot-keys`); `atom` | none found; not on the AGENTS justified-atom list (see D8) |
| A10 | `seon.sci.eval/context-database` | "Read the context's connection at use, including an explicitly bound evaluation." | a ctx's db value vs its connection | read at use, never stored in the ctx; `held conn` | none found. Contrast: `seon.turn.world/world` "It holds no database value." (a counter-example done right) |

## B. Points in time (every t, commit id, base and as-of)

| # | Symbol | Quote | Aligns | Kept aligned by | Linked bug |
|---|---|---|---|---|---|
| B1 | `seon.db/basis-t` | "Takes any database value shape ... use `basis-t` when the question is ... an as-of/history value carries no top-level :max-tx entry." | any db value vs its transaction t | `dbi/-max-tx` read per value; `t` | none found |
| B2 | `seon.db/commit-database` (and the "Only a committed value has a commit id" rule) | "Only a committed value has a commit id. An as-of, since, history, or ..." | a commit id vs a db value | `commit-id`; as-of/since values carry no id | none found |
| B3 | `seon.cluster.source/commit-database`, `commit-as-db` | "the adopted source commit is unavailable" (refusal) | commit id vs a stored db value | `commit-id`; `d/commit-as-db` on the store connection | none found |
| B4 | `seon.cluster.source/merge-base` | `(cache/lookup ::merge-base (vec (sort [a b])) (constantly 64) ...)` | two commit ids vs their merge base | `commit-id` pair, content-keyed by the sorted pair | none found. One merge base plan: `one-merge-base.md` (2026-10-15) |
| B5 | `seon.cluster.registry/fork-point` and `:seon.branch/base-t` (fork entity) | `:seon.branch/base-t` "The branch's own transaction t its merge base toward its target is as of" | the branch's start vs main | stored copy: a t written on the fork entity at `record-fork!`; `fork-point` reads it | one-merge-base and omb-gc notes (2026-10-15): a branch's start has two records (t and commit) (verify) |
| B6 | `seon.program/changed-identities` | "The walk starts at the branch's fork, the `:seon.branch/base-t` its own fork entity records" (`(or (:seon.branch/base-t fork) merge-base)`) | the branch's changed declarations vs main | two bases: a stored t (fork entity) or a merge-base commit, chosen by `or` | verify: the fallback mixes a t and a commit id as "the base" |
| B7 | `seon.test/changed-since-green` | "Name reached definitions whose content changed since the latest admitted green." | a green run's basis t vs the current db | stored `:seon.test.run/basis-t`; `(db/as-of database basis)` | none found |
| B8 | `seon.test/run` basis (`basis-t`, `supplied-basis`, `comparison`) | `comparison (or supplied-basis (some-> (last completed) (one :seon.test.run/basis-t)))` | the run's t vs the previous run's t | stored t copy on each run entity; comparison read from the branch's own run history | exp-c: "a t and eids are branch-local" (after fork) (see C7) |
| B9 | `seon.test` unchanged-through-t check | `(= (:seon.test.run/unchanged-through-t request) (db/basis-t database))` | a reuse decision vs the current t | `t` equality plus `runner/program-written-since?` | none found |
| B10 | `seon.test` since window (`db/since` on `changes-since`) | `(db/since (db/history database) changes-since)` | which definitions changed since a t | `t` window over history | none found |
| B11 | `seon.error/occurred-since`, `:seon.error/basis-t` | "The errors written on `database`'s branch after basis `since`" | error occurrences vs a t | stored `basis-t` on each error; `?tx > ?since` query | none found |
| B12 | `seon.context` current results (`newest` of `:seon.test.run/basis-t`), and task assignment `(db/as-of database assigned)` | `(newest (keep ... :seon.test.run/basis-t) ...)` | what the agent's context shows vs the run t | `t` read from the run entity; `as-of` for the assignment | none found |
| B13 | `:seon.turn/opened-tx "datomic.tx"` (placeholder string) | `:seon.turn/opened-tx "datomic.tx"` (agent.clj, test.clj, turn.clj) | the run's opening transaction | a literal placeholder replaced by the writer; "TRANSACTION REFS ARE NOT CONTENT" (turn.clj) | none found; a stand-in value in a stored shape |
| B14 | `:seon.agent/answered-t` (the answered basis) | documented in `docs/seon/schedule/cards/README-agent-entity.md` as "a home `t`" | which t an agent answered | NOT PRESENT at HEAD in `src/` or `resources/` (grep: zero attribute hits). The answered basis is a planned t | none found; a documented point in time with no implementation |
| B15 | `seon.render/call-reach-key` | "An as-of value over a committed origin at a time point the origin has reached is a fixed point" | an as-of render's reach vs the origin's content | `[connection-id generation time-point]` for as-of; revisions for committed | 10-15 `cold-block.md` (see C9) |
| B16 | `seon.render` `:seon.render.call/basis-transaction` (`(db/basis-t database)`) | (stored on the rendered call) | a render vs the t it read | stored t copy | none found |

## C. Test environments (worlds, overlays, carried results, reach)

| # | Symbol | Quote | Aligns | Kept aligned by | Linked bug |
|---|---|---|---|---|---|
| C1 | `seon.cluster.store/open-overlay!` | "A store value whose writes stay in memory over `store`, which is never written." | a test world vs the head it started from | Datahike `:datahike/overlay` with a fresh `random-uuid` per world; reads fall through; released with `release-store!` | 10-15 `overlay-mem-fix.md`: "+128 MB per test world"; f423f0b03 "Releasing an overlay ends its open branch connections" (fixed); 10-14 `storage-nodes-missing.md` (loss not reproduced; the missing node was a clone's) |
| C2 | `seon.test` `open-world!` and `carry-world-projection!` | "Open `branch`, `cluster`'s test world, with the schema projection its value derives." | the world's projection vs its connection value | held conn plus `carry-connection-projection-state!` | none found |
| C3 | `seon.test` `world-base` | "The published program this JVM loaded, which every test world starts from" (loaded identity, else recorded adoption, else held) | the test world's program vs the JVM's loaded program | three sources, `cond` order; `commit-id` | 806a97324 (prove pass parked on the overlay; fixed by moving the branch onto main's adoption record) (verify) |
| C4 | `seon.test` `request-view!` | "that branch opened in an overlay of its store ... so nothing a run records on it outlives the run" | a test run's view vs the eval request's uncommitted facts | dynamic `db/*request-facts*`, identity check on the connection, overlay with facts transacted | 61805118f "reach on an uncommitted value costs 1.9 GB"; exp-c §7 (verify) |
| C5 | `seon.test` `program-overlay-tx` | "writes `program-overlay-tx`'s rows there: the candidate's changes since ..." | candidate changes vs the recorded base | rows written into the overlay; base via `(db/commit-database candidate merge-base)` | none found |
| C6 | `seon.test` `carried-results` | "the content, not the evaluator that ran it, ruling 6" | a result another branch recorded vs this branch's reach | identity `[sym reach-digest input-digest]`; `seon.cache` first, then recorded runs of other live branches (each head read once) | cd848e1df (identity is content); e395c96cf "the gate's reuse misses on the evaluator in the reuse identity" (fixed as a patch) |
| C7 | `seon.test.runner/reach-owner` (and `::reach-head`) | `(assoc (dissoc identity :datahike.value/commit-id) :seon.store/branch <branch>)` | the reach index vs the branch and its connection generation | place-keyed: store, branch and generation; a new branch's prior is a miss | exp-c 10-15: "Measured: main and the chain differ in the branch keyword AND in the generation uuid"; cost ~470 MB per new branch (cause 3A); 6ce9dd8ad (prior by content, partial) |
| C8 | `seon.test.runner/reach-changed-entities` | (cached on `[attribute before after]`) | which entities changed between two reach indexes | takes the prior's `::basis` as a t on this database, returns entity ids: branch-bound | exp-c: "branch-bound (it needs the prior's `::basis` as a t on this database ... entity ids)" |
| C9 | `seon.test/destructive-key` | "`seon.test/destructive-key` returns `[]` on `AsOfDB` (`attempt-start`)" | a host's destructive reach keyed for an as-of view | the key reads `program-revisions`, which is refused on an as-of value (not a committed DB), so the key is `[]` and `::host` and `::destructive-reach` bypass `seon.cache` | 10-15 `cold-block.md` (Top Cost #2, 767-1,370 ms per call, 1.24 GB); a654ad995 "destructive key of an as-of view ... reuses seon.cache on a start view" (fix commit, verify) |
| C10 | `seon.test` world delays (`applied`, `written`) and `:seon.effect/counter (atom -1)` | `written (delay (when (seq tx-data) (db/transact! connection tx-data)))` | per-world config and fixture writes vs the world's connection | `delay` memo per world; `atom` counter | none found |
| C11 | `seon.test` `loaded-source-drift` | `(:seon.test/loaded-source-drift selection)` gates `carried-results` | a carried result vs the loaded source it ran against | flag computed per request; refuses carry when drift | none found |

## D. Caches keyed by place or stamp (AGENTS: "Key derivations by what they read")

| # | Symbol | Quote | Aligns | Kept aligned by | Linked bug |
|---|---|---|---|---|---|
| D1 | `seon.cache/process` | "THE process cache: one per JVM, because its kinds are derived values of" | derived values vs their inputs | Caffeine, weighed, kinds keyed by content (the good path) | none found. Note: the cache is process-scoped; the derivation keys decide alignment |
| D2 | `seon.render` `branch-scope` and the render cache atom | "Forks on the same branch share one cache." `(atom scope)` stored under `:seon.render/cache` in the projection state | a render cache vs its branch connection generation | place-keyed: `[:datahike.cache/connection-id :datahike.cache/generation]` stored in an atom, compared (`own?`) on each use, replaced when different | none found; cold-block (C9) shows the as-of path misses |
| D3 | `seon.db` `request-view` cache | `(seon.cache/lookup ::request-view [(get-in head [:meta :datahike/commit-id]) (dbi/-max-tx head) digest] ...)` | a request's view vs the head | `[commit-id max-tx digest]`; an uncommitted head has no commit id | 61805118f (1.9 GB reach on an uncommitted value) (verify whether this key is the one involved) |
| D4 | `seon.config/effective` cache | `(cache/lookup ::effective [commit cluster-name (schema.edn/declaration-digest)])` | the effective config vs the commit | `[commit cluster declaration-digest]`: content keyed | none found |
| D5 | `seon.cluster.agent/basis-valid?` | `(cache/lookup ::basis-valid [(db/commit-id database) sym] ...)` | task obligations vs main | `[commit-id sym]`; the commit id is the point in time | none found |
| D6 | `seon.test.runner` result cache | `(cache/lookup ::result reuse-key (constantly 256) ...)` (runner.clj) | a test outcome vs its reuse identity | `reuse-key` (content); red included | e395c96cf (reuse missed on the evaluator in the identity, see C6) |
| D7 | `seon.health` `::replaced-roots` | `(cache/lookup ::replaced-roots [clojure.lang.Var/rev files] weight ...)` | replaced roots vs the JVM's Var revision | a JVM counter (`Var/rev`) read as part of the key: a loaded-program stamp | none found |
| D8 | counters held in atoms and maps: `seon.schema/!fallback-counts (atom {})`, `seon.fault/folded` (ConcurrentHashMap), `seon.profile/cells-by-key` | (process-scope counters) | counts vs their events | `atom` / map; `profile` cells are on the AGENTS justified list, the other two are not listed | none found (AGENTS §3.19 ledger not found at HEAD) |
| D9 | `seon.cluster/launched-source-digests` (defonce) | (process record of launched source digests) | what was launched vs the source on disk | `defonce` set of digests | none found |

## E. Connections, branch lifecycle and live objects

| # | Symbol | Quote | Aligns | Kept aligned by | Linked bug |
|---|---|---|---|---|---|
| E1 | `seon.cluster.registry/on-branch` | "Call `f` with a connection to `branch` ... holding its own reference ... until `f` returns" | a branch pointer vs one action's connection | held conn, one reference per action (`acquire-branch!` / `release-branch!`) | none found |
| E2 | `seon.cluster.registry/active-branch-connection` | (borrowed in `acquire-context!` when another holder has the branch open) | the agent's ctx vs an already-open connection | borrowed held conn | none found |
| E3 | `seon.cluster.store/open-branch!` and `branch-open-monitor` | "this process already holds a connection to that branch ... one writer" (refusal `::branch-already-open`) | one connection per branch per process | `locking` a monitor object; refuses a second connect | f423f0b03 (overlay releases end open branch connections) |
| E4 | `seon.cluster.store` projection state as connection metadata | `(alter-meta! (:wrapped-atom connection) dissoc :seon.sci.eval/projection-state)` | projection state vs its connection | metadata on the connection's atom, dissoc on release | none found; a live map hung on a connection's metadata |
| E5 | `seon.cluster.agent` registry (`:seon.agent/contexts`, `:seon.agent/children`) | "the one process-local atom of one execution's live objects" | an execution's handles vs its branches | `atom` per execution, keyed by `[branch agent-id]` and by branch | none found |
| E6 | `seon.cluster.registry/fork!` and `record-fork!` | "Datahike's `force-branch!` writes immutable values before atomically ..." | a fork's entity vs its base | fork entity with `:seon.branch/base-t`; Datahike writes the branch pointer | see B5 |
| E7 | `seon.cluster.registry/apply-config-to-branches!` | "`seon.config/apply!` the document at `path` on each of `branches` ... each held for its one action" | config on each branch vs root's intent | explicit write per branch by root, never inherited | 3418b8d7b and e0fe8da52 (config apply opens each branch for its one action; ruling 7) |
| E8 | `seon.env` environment atom and `env/replace-environment!` | `(atom (assoc empty-environment :seon.boot/cluster-name cluster-name))` | an execution's environment (store, routing) vs its context | `atom` replaced at acquisition | none found |
| E9 | `seon.cluster.registry` `branch-heads` | `heads (atom [])` (registry.clj) | the branch heads read in one pass | `atom` local to one scan | none found |
| E10 | `seon.flow` proc state: `active-work`, `io-submissions`, `accepting?`, `::status`, `::active?`, `::task` | `active-work (atom {})`, `accepting? (atom true)` | a proc's work vs the graph's lifecycle | `atom` held by the Flow proc (listed as allowed) | 734e0aed7 "a proc's lifecycle transition builds no world, so a stop can no longer fail to exit"; 10-15 `flow-stop-hang.md` (a stop waited on a world) |
| E11 | `seon.turn` `:seon.turn/namespace-cursor (volatile! starting-namespace)` | (the REPL namespace position of a turn) | the turn's namespace vs the REPL's namespace | `volatile` per turn | none found |
| E12 | `seon.db` projection delays (`(delay (carried-projection database))`, `(delay (nearest-base builder database))`) | `base (delay (nearest-base builder database))` | a projection builder vs its database | `delay` memo per call | none found |

---

## Governing lines (quoted, not argued)

- `docs/research/agent-platform/2026-10-15/branch-model-rulings.md`, ruling 1: "A branch runs its own HEAD, like a git
  checkout (amended 10-15, below)." Amendment: "Branch HEADs are Datahike branch pointers held by the world's
  connection; nothing recomputes where a HEAD is." Also: "Nothing is copied: a branch shares main's immutable indexes as
  of its fork and adds its own datoms."
- Deleted at HEAD (grep, zero hits in `src/`): `seen-database`, `moved-past`, `merge-sides`, `commit-digests`,
  `main-changed`, `seen-digests`, `moved-reach`. Ruling 1 said these are deleted; they are.
- `AGENTS.md`, "The opposite of virtual machines" (owner, 2026-10-15): "reuse as much as possible ... Isolation comes
  from immutable values and branches, never from copies: a branch shares main's indexes and runs its own HEAD ... every
  derivation is keyed by the content it reads."
- `AGENTS.md`, "Reuse content-valid work; derive without stamps": "Key derivations by what they read: content digests
  or the dependency's upstream change tracking. A commit id identifies a database value, not an excuse to recompute after
  unrelated writes."
- `AGENTS.md`, "Prefer values to places": "State lives colocated in its owner's value ... Mutable state is at most one
  validated state map per process and one per cluster ... Any further atom needs a reason the owner accepts in plain
  words, and a lint checks this (ledger §3.19)." The ledger file was not found at HEAD (`docs/seon/` holds no §3.19).
- `AGENTS.md`, "Use the database owner": "Direct `datahike.api` calls belong only in `seon.db`..."
- `docs/seon/plan.md` / commit d7cbfc7d8 "Plan 1 ... with the seven drifts root and lanes made on 10-14/15": the drifts
  are listed in that commit's added lines ("Root and its lanes drifted from this repeatedly on 2026-10-14/15. Check
  every design against these before writing it."). This lane did not reproduce the list.
- Owner ruling: `seon.cluster.agent/acquire-context!` and the branch model: "branches diverge like git ... make it as
  immutable and pointer based as possible" (branch-model-rulings.md amendment).

## Notes for designers (evidence only)

1. The t values are branch-local after a fork (exp-c). B8, B9, B10, C7 and C8 compare t values across branches or
   against a prior's basis. Those are the cross-place alignments most likely to drift.
2. The as-of path is the one with the most measured misses: C9 (`destructive-key` returns `[]` on an as-of value),
   B15 and D2 (render caches keyed by branch scope, not by the as-of fixed point).
3. Stored t copies (B5 `:seon.branch/base-t`, B7 run `basis-t`, B16 render `basis-transaction`, B11 error `basis-t`)
   are the "stored copy" class. Each is written once and must be re-read against the commit it names.
4. `:seon.agent/answered-t` (B14) is documented as a home `t` and is absent at HEAD. Any design that relies on it is
   relying on a point in time that does not yet exist.
5. Per-context atoms (A9) mirror the program. They are not on the AGENTS justified-atom list.
6. A10 (`context-database`) and `seon.turn.world/world` ("It holds no database value") already read the connection at
   use. They are the pattern the other rows lack.

## Counts per category

| Category | Rows | Rows with a linked bug (from notes or commit subjects) | Rows with a bug confirmed by this lane |
|---|---|---|---|
| A. SCI vs db | 10 | 3 (A4, A7, A8) | 0 |
| B. Points in time | 16 | 3 (B5, B6, B15) | 0 |
| C. Test environments | 11 | 7 (C1, C3, C4, C6, C7, C8, C9) | 0 |
| D. Caches keyed by place or stamp | 9 | 2 (D3, D6) | 0 |
| E. Connections, lifecycle, live objects | 12 | 3 (E3, E7, E10) | 0 |
| **Total** | **58** | **18** | **0** |

No row was reproduced by this lane (Haiku, read-only, no REPL). "Linked bug" means the bug is named in a dated note or a
commit subject; each one is a pointer for a designer to verify, not a finding.

## Rows linked to observed bugs (for the designers' queue)

- **A8** `seon.sci.eval/program-context`: 10-14 `digest-and-def-defects.md` defect 2, a private `def` vanishes between
  MCP evaluations (program context interprets rows without the agent's private Vars).
- **A7** `seon.cluster.agent/acquire-context!` (`branch-ctx` copy): 10-14 `sci-reuse-across-tasks.md`.
- **A4** development adoption record: commit 806a97324 (chain branch moved onto main's adoption record).
- **B5 / B6** `:seon.branch/base-t` vs merge-base: one-merge-base notes (10-15) and commit d8169384b (verify).
- **B13** `"datomic.tx"` placeholder: no bug found, listed as a stand-in in a stored shape.
- **B14** `:seon.agent/answered-t`: documented, absent at HEAD.
- **B15 / C9 / D2** as-of render and destructive key: 10-15 `cold-block.md`; commit a654ad995 (fix, verify).
- **C1** `seon.cluster.store/open-overlay!`: 10-15 `overlay-mem-fix.md` (+128 MB per test world); f423f0b03.
- **C1** `storage-nodes-missing` (10-14): loss not reproduced; the missing node was in a throwaway clone.
- **C3** `seon.test` `world-base`: commit 806a97324 (prove pass parked on the test world's overlay) (verify).
- **C4** `seon.test` `request-view!`: commit 61805118f ("reach on an uncommitted value costs 1.9 GB") and exp-c §7.
- **C6** `seon.test` `carried-results`: commits cd848e1df and e395c96cf (reuse identity: content, and the evaluator).
- **C7** `seon.test.runner/reach-owner`: 10-15 `exp-c-reach-by-content.md` (cause 3A, ~470 MB per new branch);
  commit 6ce9dd8ad (partial fix, prior by content).
- **C8** `seon.test.runner/reach-changed-entities`: branch-bound t and entity ids (exp-c).
- **C9** `seon.test/destructive-key`: 10-15 `cold-block.md` (767-1,370 ms per call, 1.24 GB).
- **D3** `seon.db` `request-view` key: 61805118f (verify whether it is the key involved).
- **D6** `seon.test.runner` result cache: e395c96cf.
- **E3** `seon.cluster.store/open-branch!`: f423f0b03 (overlay release ends connections).
- **E7** `apply-config-to-branches!`: 3418b8d7b, e0fe8da52 (explicit per-branch config; ruling 7).
- **E10** flow proc state: 734e0aed7 and 10-15 `flow-stop-hang.md`.

Sources consulted: `src/seon/{sci/eval,cluster/agent,cluster/store,cluster/registry,cluster/source,test,test/runner,render,db,
turn/world,context,program,cache,flow,env,health,fault,profile}.clj*`; `docs/research/agent-platform/2026-10-15/` (branch-model-rulings,
exp-c-reach-by-content, cold-block, overlay-mem-fix, flow-stop-hang, one-merge-base, omb-gc-notes);
`docs/research/agent-platform/2026-10-14/` (digest-and-def-defects, sci-reuse-across-tasks, storage-nodes-missing, cache-after-boot);
`git log --since=2026-10-08`; `AGENTS.md`; `docs/seon/schedule/cards/README-agent-entity.md`.
