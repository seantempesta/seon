---
type: research
status: read-only findings; stage-1 recommendation awaiting review
created: 2026-09-17
tags: [testing, selection, datahike, performance, dependencies, steward]
---

# Test selection efficiency, and what Datahike already gives us

Owner question (2026-09-17): *"Do we have an efficient way of querying to find
what tests are affected and efficiently running just those?"* and *"go through
the source code in reference-code and update it if it's out of date."*

**Verdict in one line: the reverse-reach query is fast and correct today
(14.181 ms for the worst seed on `default`); the CHANGE side is three
disagreeing selectors, two of which never touch the database, and that — not
the graph walk — is what makes `bin/test` select wrongly and widely.**

Authority read end to end before writing: [AGENTS.md](../../../../AGENTS.md)
§"Ask what the dependency already does before you build anything", §2.1–§2.3;
[the test-system PRD](../plan/test-system-is-the-database-prd-2026-09-17.md) §1
(P1–P6) and §2–§3; [the stage-0 design note](test-system-stage0-design-2026-09-17.md)
§1. Vocabulary is Datahike's and Clojure's throughout: **database value**,
**basis `:t`**, **history**, **`since`**, **AVET**, **datom**, **rule**,
**test Var**, **`:once` fixture**, **report**.

**Measurement boundary.** Every live number below came from three read-only
`mcp__seon__eval_clj` calls in `jvm` mode against cluster `default`
(custody `(seon.operator/connection "default")`), at basis `:t` 536871626 →
536871639, in the owner's long-lived JVM under ordinary load. No transaction,
no test JVM, no `bin/seon` state change. Numbers are single observations on a
warm JVM, not distributions. Historical numbers are cited to the note that
recorded them.

**Population of `default` at basis 536871626** (query 1, `seon.db/datoms`
`:aevt` counts): 5,019 `:seon.fn/sym` datoms, 1,829 `:seon.test/sym` datoms,
68,930 `:seon.fn/calls` datoms, 229,262 `:seon.test/reach` datoms held by
**281** tests, 278 `:seon.test/reach-digest` datoms. The branch has 727
transactions (basis 536871639 − 536870912).

---

## 1. The three selectors today

### 1a. `seon.test/check-in-process` — the in-process (hook / agent) selector

`src/seon/test.clj:535-567` is the entry; the whole body runs to `:709`.

- **Input:** a request map of `:seon.db/connection`, `:seon.test/changed`
  (symbols or `[attribute value]` adoption identities), `:seon.test/paths`
  (repository-relative strings), `:seon.test/namespaces`,
  `:seon.boot/cluster-name`. It derives its own database value at `:542`
  (`(db/db connection)`).
- **Query it runs:** it does not run one Datalog query. It calls
  `changed-reach` (`src/seon/test.clj:432`) → `identity-tests` (`:407`) →
  `seon.fn/tests-reaching` (`src/seon/fn.clj:1214`) per changed identity. For a
  namespace identity it first runs two Datalog queries (`src/seon/test.clj:426`,
  `:429`) to enumerate that namespace's functions and tests:

  ```clojure
  '[:find [?symbol ...] :in $ ?ns
    :where [?n :seon.ns/name ?ns] [?f :seon.fn/ns ?n] [?f :seon.fn/sym ?symbol]]
  ```

- With **no** `:seon.test/changed` it instead selects candidates by namespace
  (`namespace-tests`, `src/seon/test.clj:502`) and filters them through
  `stale-in` (`:459`), which compares the recorded `:seon.test/reach-digest`
  against a freshly derived one (`seon.test.runner/reach-digests`).
- **Index it hits:** AVET, through `gate-set` (below) plus `:seon.ns/name`
  lookups.
- **Widening:** `check-in-process:547` reuses `seon.test.selection/widening-path?`
  — a **string-prefix test over file paths** (`src/seon/test/selection.clj:108`)
  — so a path selector leaks into the database-side selector. Any changed path
  under `resources`, `config`, `script`, `bin/test`, `bb.edn`, `deps.edn`,
  `.clj-kondo/config.edn` widens to **every declared test namespace**
  (`:556-561`).
- **Cluster:** `:544` defaults the cluster to `"default"` implicitly, which the
  PRD's E1 forbids and which `runner/record!:2385` separately refuses as a
  destination.
- **Cost:** dominated by `gate-set`; see 1b. `stale-in`'s digest derivation cost
  is measured in 1d.

Related but distinct: `seon.test/changed-since-green` (`src/seon/test.clj:54`)
is a **per-test diagnostic**, not the run selector. It reconstructs a test's last
green `:t` by folding `db/history` events at `:70-77`, then runs the only
`since`-based Datalog query in the current selection code
(`src/seon/test.clj:97`):

```clojure
'[:find ?f ?sym
  :in $ $since [?f ...] [?a ...]
  :where [$since ?f ?a] [?f :seon.fn/sym ?sym]]
;; sources: database, (db/since history green), the test's recorded reach ids,
;;          [:seon.fn/source :seon.fn/spec]
```

That is the right shape and the wrong scope: it answers "what changed under
ONE test", not "which tests must run". **Defect found:** it reads the test's
closure through `db/pull` at `:63` (`{:seon.test/reach [:db/id]}`), and
Datahike's pull caps a cardinality-many attribute at
`+default-limit+ 1000` (`reference-code/datahike/src/datahike/pull_api.cljc:16`).
With 229,262 reach datoms over 281 tests (mean 816, several at or above the cap
— query 2 measured a test with exactly 1000 members returned by both pull and
`datoms`), this silently truncates. The same pull-capped read feeds
`held-members` in the recording writer (`src/seon/test/runner.clj:2229`), so
stale members past the first 1000 are never retracted. File as an issue.

### 1b. `seon.fn/gate-set` / `tests-reaching` — the reverse-reach query

`src/seon/fn.clj:1157-1211`; `tests-reaching` is a one-line alias at `:1214-1220`.

- **Input:** a **database value** and one `:seon.fn/sym` **string**. No files, no
  basis, no stored artifact.
- **The query:** an **iterative frontier walk in Clojure over AVET**, not a rule.
  `src/seon/fn.clj:1176`:

  ```clojure
  (db/datoms database :avet :seon.fn/calls entity)   ; incoming edges of `entity`
  ```

  looping `pending`/`seen`/`callers` at `:1169-1182`, then three finishing
  Datalog queries over the collected caller entities — by call edge (`:1187`),
  by `:seon.test/subject` (`:1192`), by `:seon.test/pending-subject` (`:1200`).
- **Index:** AVET. **`:seon.fn/calls` is a `:seon.db/ref`**
  (`resources/seon/schemas/seon.fn.edn:34`), and Datahike gives every
  `:db.type/ref` attribute `:db/index` implicitly
  (`reference-code/datahike/src/datahike/db/utils.cljc:312`), so the reverse
  lookup is an indexed seek with no schema change needed.
- **Measured (query 1, `default`):**

  | seed | cold ms | warm ms | tests selected |
  |---|---:|---:|---:|
  | `seon.db/transact!` | **14.181** | 12.288 | **1,009** of 1,829 (55%) |
  | `seon.test/check` | 2.534 | — | 10 |
  | `seon.test.selection/widening-path?` (leaf) | 1.562 | 0.924 | 11 |

- **This used to be the bottleneck and is not any more.** The recursive Datalog
  formulation measured **7,217.673 ms / 936 tests** for `seon.id/id` on
  2026-09-15 ([issue](../../../seon/issues/function-entity-render-reach-query-cost.md)),
  and **6,753.094 ms → 19.579 ms (11.445 ms armed), identical 1,001 tests**
  before/after commit `1d141d26a` *"Derive gate sets from indexed incoming call
  edges"* ([turn-bookkeeping-cost-2026-09-16.md:489](../../context-generation/research/turn-bookkeeping-cost-2026-09-16.md)).
  A ~350× improvement, from replacing a recursive rule with a frontier walk over
  the same index. **The issue note is stale**: it still describes
  `gate-set` as "recursive Datalog" at `src/seon/fn.clj:874`. Update or close it.
- **Contradiction with E2 (coverage IS the call graph):** `gate-set` still
  admits `:seon.test/subject` (`:1192`) and `:seon.test/pending-subject`
  (`:1200`) — declared metadata naming what a test tests. The stage-0 note flags
  the same at `fn.clj:1195-1207`. Stage 1 must not copy it.

### 1c. `seon.test.runner/bulk-selection` — the `bin/test` coordinator selector

`src/seon/test/runner.clj:2615-2665`, called from the coordinator at `:3713`;
`reaching-selection` at `:2606`; `requested-changed-paths` at `:2595`;
`record-green-basis!` at `:2667`, called at `:3577`.

- **Input:** none of it is a database value. It reads **JVM system properties**
  (`seon.test.changed-paths-file`, `seon.test.source-root`, `runner.clj:2589`,
  `:2597`), a **file of repository-relative paths**, an in-memory
  **`seon.fn` manifest value**, and a **stored basis file**
  `tmp/test-basis/green-basis.edn` (`src/seon/test/selection.clj:175`).
- **The query:** there is none. `seon.test.selection/input-digests`
  (`src/seon/test/selection.clj:70`) walks `src`, `test`, `resources`, `config`,
  `script`, `bin/test`, `bb.edn`, `deps.edn`, `.clj-kondo/config.edn`,
  **SHA-256s every file**, and `changed-inputs` (`:84`) diffs that map against
  the basis file. `reaching-tests` (`src/seon/test/selection.clj:131`) then
  builds a `callers-of` index in memory from manifest rows and runs the same
  frontier walk as `gate-set` — over **manifest artifacts, not datoms**
  (`:153-163`).
- **Index:** the filesystem, then a transient hash map. It hits no Datahike index
  at all.
- **Widening:** `:2628` and `:2652` widen to `:all` on any widening path, any
  removed input, or a missing basis file.
- **Cost:** two full SHA-256 walks of `src` + `test` + `resources` + `config` +
  `script` per gate (once for the compare, once to record the new basis at
  `:3577`), plus the manifest build. Not separately timed in the gate logs; the
  enclosing phases are: published base preparation **41,508 / 3 / 118,399 /
  44,011 ms** across four gates, worker startup **11.189–16.179 s each**,
  coordinator-plus-tests **130–748 s**, total wall **147–803 s**
  ([test-suite-cost-plan-2026-09-15.md:15-30](../../context-generation/research/test-suite-cost-plan-2026-09-15.md)).
- **Why it is wrong, not merely slow:** the basis is a file in `tmp/`, so it is
  per-checkout and lost with any sweep; "changed" is byte difference from a
  *green* basis, which is neither a cluster nor a basis `:t`; and the selector
  answers a different question from 1a on the same request. The stage-0 note
  states the same at `test.clj:535`, `runner.clj:2615`, `fast.clj:18`.

### 1d. The fourth, undocumented selector: the recorded reach index

Not in the assignment's list, and it is the most interesting thing in the tree.
`seon.test.runner/reach-refresh` (`src/seon/test/runner.clj:1799`) **already does
what stage 1 wants**:

```clojure
(db/q '[:find [?e ...] :in $ [?a ...] :where [?e ?a]]
      (db/since (db/history database) (::reach-basis previous))
      reach-attributes)
```

It derives changed identities from `db/since` against the previous index's
basis, pulls only those rows, and incrementally invalidates per-test digests by
dependency token (`:1832-1847`). `reach-entry` (`:1848`) walks `:seon.fn/calls`
FORWARD from one test and returns `::reach-refs` — the test's transitive closure
as `[:seon.fn/sym …]` lookup refs. `reach-memberships` (`:1926`) exposes it, and
`commit-results!` stores it as `:seon.test/reach` at `:2254`. So **the
materialised closure already exists as durable facts**, maintained at recording
time, and the reverse lookup is one indexed seek.

Measured (query 1 and 2):

| operation | ms | result |
|---|---:|---|
| `reach-digests` for 3 tests, cold index refresh | **84.521** | 3 digests |
| same, warm | **0.070** | 3 digests |
| `(db/datoms db :avet :seon.test/reach <transact!-id>)` | **0.0738** | **186** tests |
| `(db/datoms db :avet :seon.test/reach <leaf-id>)` | **0.0425** | 0 tests |

The 186-vs-1,009 and 0-vs-11 gaps are **coverage, not disagreement**: only 281
of 1,829 tests have ever recorded a reach membership, because the fact is written
only when a test runs and its result is committed. That is the staleness
boundary, and it is exactly answerable: a test with no `:seon.test/reach` and no
`:seon.test/reach-unknown` is *unknown*, never *unaffected* — which is the
recurring failure class AGENTS.md names ("absence of signal as health").

---

## 2. What Datahike already gives us

Read at the pins currently checked out under `reference-code/datahike`.

**`since` / `as-of`.** `seon.db/since` (`src/seon/db.clj:2142`) and
`seon.db/history` (`:2115`) wrap `datahike.api`; `database-view`
(`src/seon/db.clj:2053`) refuses a non-temporal database as a typed value rather
than throwing. `since` **excludes** the time point, so the change basis is the
last run's `:t` itself, with no off-by-one. Measured on `default` (query 3),
deriving changed `:seon.fn/source` / `:seon.fn/spec` / `:seon.fn/calls`
identities:

| change basis | ms | changed datoms |
|---|---:|---:|
| basis − 1 tx | 24.589 | 1 |
| basis − 5 tx | 24.084 | 1 |
| basis − 20 tx | 24.972 | 2 |
| basis − 200 tx | 24.437 | 51 |
| whole history (clamped) | 90.1–90.6 | 11,376 |

**~24 ms, flat in distance.** That is the entire cost of half of stage-1
selection, and it walks nothing.

**The transaction log.** `datahike.api/tx-range` exists in the dependency but is
**not exposed by `seon.db`** — there is no `tx-range` in `src/seon/db.clj` (only
`basis-t :332`, `q :1674`, `pull :1803`, `pull-many :1852`, `datoms :2009`,
`index-page :2020`, `history :2115`, `as-of :2128`, `since :2142`). Given the
24 ms `since` result, do **not** add it: `since` answers the same question
through the indexes the database already maintains, and `seon.db` is the one
database namespace (AGENTS §3).

**VAET — the premise is refuted.** Datahike has **no VAET index**. Its indexes
are `:eavt`, `:aevt`, `:avet` and their temporal twins
(`reference-code/datahike/src/datahike/db.cljc:934`, `:945`, `:984`, `:995`,
`:1007`); the only occurrence of "vaet" in the tree is a docstring in the Python
codegen (`reference-code/datahike/src/datahike/codegen/python.clj:599`). Reverse
ref traversal is AVET, and every `:db.type/ref` is indexed automatically
(`reference-code/datahike/src/datahike/db/utils.cljc:312`). `gate-set` is
already using the only correct mechanism; `:seon.test/reach` (a ref, so indexed)
gets the same seek for free.

**Recursive rules and their cost.** `datahike.query/solve-rule`
(`reference-code/datahike/src/datahike/query.cljc:1317`) expands rule branches
onto an explicit stack. **It does not memoize**: the clause cache is a
commented-out TODO at `query.cljc:1321` (`;; clause-cache (atom {}) ;; TODO`).
Termination on a cyclic graph rests entirely on `rule-gen-guards`
(`:1296`) emitting `-differ?` guards against previously used call arguments —
that is repeated-call-pattern suppression, not tabling. Over 5,019 nodes and
68,930 edges this is why the rule formulation measured **6,753 ms** where the
frontier walk measures **19.6 ms** on the identical seed and identical answer.
A recursive rule is also refused outright in some planner positions
([the-query-planner-rejects-a-negation-bound-by-a-recursive-rule](../../../seon/issues/the-query-planner-rejects-a-negation-bound-by-a-recursive-rule.md):
`:recursive-rule` credits no variable binding at
`reference-code/datahike/src/datahike/query/lower.cljc:1092`). **Conclusion: a
recursive Datalog rule is not tractable per request over this graph, and
`seon.fn/currently-failing-functions` (`src/seon/fn.clj:1228`) and
`functions-without-tests` (`:1243`) still use `test-reach-rules`
(`src/seon/fn.clj:1131`) — file that as a follow-up.**

*Honesty note:* I attempted to time the recursive-rule reverse closure directly
in queries 2 and 3 and both attempts failed inside my own probe harness with an
`ArityException`, not in the database. I did not spend a fourth query (the
assignment caps me at three). The rule cost above is therefore cited from the
two recorded before/after measurements, not re-measured today.

**The query result cache.** `advance-query-cache-context`
(`reference-code/datahike/src/datahike/query.cljc:2568`, called from
`writer.cljc:263`) advances a per-attribute revision map on each commit, and
promotes the conservative revision when the transaction is unsafe, attribute-set
unknown, or touches a schema attribute. `source-context-unchanged?` (`:2963`)
lets a cached result be inherited by a newer snapshot when **every attribute in
that query's dependency plan** has an unchanged revision, and refuses outright
when the plan is `:all` (`:2967`). **The boundary a peer found:** the guard is
per-attribute, so a result row carrying a `:db/id` stays inheritable across an
entity deletion when the deletion retracted attributes the plan did not name.
For selection this means: **a selector that returns `:db/id`s must not rely on
the cache for deletion correctness.** Stage 1's answer is already the right
shape — return `[:seon.fn/sym …]` / `[:seon.test/sym …]` lookup refs, exactly
what `::reach-refs` stores (`runner.clj:1871`), and treat tombstones through the
ruled PROGRAM IDENTITY ROWS NEVER RETRACT invariant rather than through cached
`:db/id`s.

---

## 3. The one selection function

`(select db request)`, pure over one immutable database value, in `seon.test`.
Request: `{:seon.boot/cluster-name, :seon.test/change-basis (optional, default
derived), :seon.test/namespaces, :seon.test/identities}`.

### (a) Changed identities — `since`, measured 24 ms

```clojure
(db/q '[:find ?e ?a
        :in $since [?a ...]
        :where [$since ?e ?a]]
      (db/since (db/history database) change-basis)
      [:seon.fn/source :seon.fn/spec :seon.fn/calls])
```

`since` excludes the time point, so `change-basis` is the previous run's
`:seon.test.run/basis-t` verbatim. History retains retractions, so a tombstoned
identity appears in the same result with `?added` false — add `?added` to the
`:find` and treat a retraction of `:seon.fn/source` as a changed identity.
This replaces **both** `selection/input-digests` (two SHA-256 filesystem walks)
and `selection/read-basis` (a file in `tmp/`). Measured cost on real distances:
**24.1–25.0 ms, flat**; whole-history worst case 90.6 ms.

### (b) Affected tests — three candidates, measured

| candidate | mechanism | measured | correctness |
|---|---|---|---|
| recursive Datalog rule | `test-reach-rules` (`fn.clj:1131`) via `solve-rule` | **6,753 ms** for one seed (recorded 2026-09-16) | correct; no memoization (`query.cljc:1321`); refused in some planner positions |
| **iterative frontier over AVET** | `gate-set` (`fn.clj:1169-1182`) | **14.181 ms** worst seed (1,009 tests); **1.562 ms** leaf (11 tests) | correct and complete for every indexed test; closes per request, so never stale |
| recorded `:seon.test/reach` reverse seek | `(db/datoms db :avet :seon.test/reach id)` | **0.074 ms** (186 tests) | fast, but covers only **281 / 1,829** tests, and its members go stale past the 1000-element pull cap at `runner.clj:2229` |

**Recommendation: the iterative frontier walk, per request, seeded by (a).**
Not the rule (450× slower, and planner-fragile). Not the materialised closure as
the *authority*: maintaining it at recording time is not cheaper than closing per
request — closing costs 14 ms at the worst seed, while the materialised form
costs 229,262 durable datoms, only covers tests that have run, and inherits the
pull cap. Keep `:seon.test/reach` for what it is good at — it is *evidence of
what a run actually tested*, which is a different fact from *what must run next*
— and stop treating it as a selector input.

Because the frontier walk is per-seed, the loop must seed once with the union:
walk from all changed identities in one `pending` collection rather than calling
`gate-set` per symbol, so `seen` is shared. With 51 changed identities at the
200-transaction distance, per-symbol calls would repeat the walk 51 times;
one shared-`seen` walk visits each reachable node once. **Expected stage-1
cost for a realistic change: ~24 ms (a) + one shared walk bounded above by the
14 ms worst single seed ≈ under 50 ms.** Today's coordinator pays two full
SHA-256 tree walks for the same answer.

### (c) Plus platform-declared and explicitly named

`:seon.test/platform` is a program-row fact queried, not read off a Var
(`runner.clj:761`, `:766`; the same pattern `check-in-process` uses for
`:seon.test/long` at `test.clj:583`). Explicit identities and namespaces enter
as members with their own reason. Widening (`selection/widening-path?`) must
NOT survive stage 1 as a path-prefix test: the schema and config changes it
stands in for are `:seon.schema/key` datoms, which `since` already reports.

### (d) Reasons per member

One reason value per selected identity, recorded on the run's member before
execution (PRD §3): `{:seon.test/sym s, :seon.test/selected-because
:seon.test/reaches-changed, :seon.fn/sym "…"}` | `:seon.test/named` |
`:seon.test/platform` | `:seon.test/reach-unknown` (a test with no recorded
closure and no reach from the changed set is UNKNOWN, and stage 1 must decide
loudly whether unknown runs or refuses — silence here is the failure class).

---

## 4. Running just those, efficiently

**`seon.test/run` per Var re-runs the `:once` fixture per test.**
`runner/run-var!` (`src/seon/test/runner.clj:579`) ends at `:637` with
`(test/test-vars [test-var])` — a **single-element** vector. `clojure.test/test-vars`
(`reference-code/clojure/src/clj/clojure/test.clj:722-733`) groups by namespace
and wraps each group in `(join-fixtures (::once-fixtures (meta ns)))`, so a
one-Var call runs that namespace's `:once` fixture **once per Var**.
`check-in-process` loops exactly that way (`src/seon/test.clj:664-680`). For the
canonical database fixture this is the single largest avoidable cost in the
in-process path.

`runner/run-selected-tests` (`src/seon/test/runner.clj:1019`) already does it
right: it groups the selected Vars by namespace (`:1021`) and calls
`(test/test-vars namespace-vars)` once per namespace (`:1038`), with
`:begin-test-ns` / `:end-test-ns` reports around it and an explicit refusal when
a `test-ns-hook` cannot express a subset (`:1032`). **Stage 2 should accrete a
selected-Var collection arity onto `seon.test/run` that delegates here, and
delete the singleton loop** — the stage-0 note says the same. Note the one
constraint `run-selected-tests` encodes: a namespace with `test-ns-hook` is
all-or-nothing.

**Fixture base cost, warm vs cold.** Not cleanly split anywhere, and the gate
logs cannot split it: "END measures the task including fixtures
(`src/seon/test/runner.clj:890`), **not** a fixture/body split"
([test-suite-cost-plan-2026-09-15.md:12](../../context-generation/research/test-suite-cost-plan-2026-09-15.md)).
What is recorded: the canonical base is **one per JVM**, and the first fixture
user is charged the whole construction
([fast-tier-top20-2026-08-03.md:51](../../sci-execution-runtime/research/fast-tier-top20-2026-08-03.md));
a class regression run against an **unrealized** base took **32,756.598 ms**
([turn-bookkeeping-cost-2026-09-16.md:319](../../context-generation/research/turn-bookkeeping-cost-2026-09-16.md)),
while a probe on a **privately cloned prepared** base completed in **6,411 ms**
([test-suite-cost-plan-2026-09-15.md:657](../../context-generation/research/test-suite-cost-plan-2026-09-15.md)).
`check-in-process` already realizes the base before starting any per-Var backstop
(`src/seon/test.clj:652-659`) precisely because of this. **A clean cold/warm base
measurement does not exist and stage 1 should produce one**; I did not run tests.

**"Only what changed since the last run ON THIS CLUSTER" — the run schema has
it.** `resources/seon/schemas/seon.test.run.edn` declares
`:seon.test.run/basis-t` (`:seon.db/basis-t`), `:seon.test.run/tested-branch`
("branch of the immutable database tested; **basis-t belongs to this branch**"),
`:seon.test.run/branch` (the durable result destination), `:at`, `:git-sha`,
`:program-digest`, `:id`. Per test, `commit-results!` writes
`:seon.test/run-basis-t` and `:seon.test/run-at` (`src/seon/test/runner.clj:2249`).
So `(select db request)` can derive its default change basis as: the maximum
`:seon.test.run/basis-t` over completed runs whose `:seon.test.run/tested-branch`
is this cluster's branch. **One gap:** a cluster NAME is not on the run entity —
only branches. E1 says the cluster is explicit; either the branch is the
cluster's identity here (it is, one cluster = one branch, AGENTS §1) and the PRD
should say so, or a cluster ref is accreted. Raise it as a stage-1 decision.

---

## 5. Dependency currency

Read-only sweep (a plain `git fetch` only; **no pin moved, nothing checked out,
nothing committed**). Working checkout SHA equals the recorded gitlink for all
eight submodules. Network reachable for every fetch.

| dependency | pinned gitlink | remote | ahead / behind default |
|---|---|---|---|
| datahike | `49ea5933` 2026-09-15 *Detach speculative query-cache identity before transaction functions run* | `seantempesta/datahike` (Seon fork); upstream `replikativ/datahike` | 5 ahead / 0 behind fork `main` (**5 unpushed**); 106 ahead / **161 behind** upstream |
| persistent-sorted-set | `e1a17bbe` 2026-07-10 *fix(diff-buf): project onto a copy (#19)* | `replikativ/persistent-sorted-set` (upstream) | 0 / **7 behind** |
| konserve | `07377c27` 2026-08-05 *Expose fixed GC deletion batches to falsifiers* | `seantempesta/konserve` (fork); upstream `replikativ/konserve` | 0 / 0 vs fork; 7 ahead / **39 behind** upstream |
| sci | `fcbd8862` 2026-08-11 *Add fact-safe SCI Var root installation* | `babashka/sci` (upstream); `seantempesta/sci` (fork) | 17 ahead / **29 behind** upstream; 5 unpushed vs `fork/main`, 7 vs `fork/seon` |
| clj-kondo | `57252e07` 2026-07-30 *Resolve attr-map metadata keywords in source namespace* | `seantempesta/clj-kondo` (fork); upstream `clj-kondo/clj-kondo` | 2 ahead / 5 behind fork; 2 ahead / **27 behind** upstream |
| malli | `3517a3cd` 2026-08-10 *fix(sci): resolve loaded qualified Vars directly* | `metosin/malli` (upstream, 2 local patches, no fork remote) | 2 ahead / **74 behind** |
| core.async | `dc35f3e0` 2026-06-04 *prepare release v1.10.874-alpha3* | `clojure/core.async` | **0 ahead / 5 behind `origin/dev-flow-alpha`** |
| clojure | `b18d3adc` 2026-07-20 *CLJ-2892 remove deprecated AccessController* | `clojure/clojure` | 0 / **37 behind** (1.13.0-alpha line) |

**core.async caveat worth recording in AGENTS.md:** `origin/master` contains
`c63dfee remove flow in master`. Flow lives **only** on `dev-flow-alpha`, so
master is never the comparison branch for this pin. Against the correct branch
we are 5 behind and all five are docs plus a release-plugin bump — **zero flow
behavior change.**

### Which pins moved since [agents-md-audit-2026-09-15](../../context-generation/research/agents-md-audit-2026-09-15.md)

That audit fixes its basis as superproject commit `be0c49830` and tells the
reader to resolve gitlinks with `git rev-parse be0c4983…:reference-code/NAME`
rather than printing SHAs. Resolved and compared:

**Exactly one pin moved: datahike, `cdcb5792` (2026-08-12 *Compile shared pull
selector DAGs once*) → `49ea5933` (2026-09-15), one commit, the old SHA an
ancestor.** That commit is a Seon-authored query-cache correctness fix —
`src/datahike/core.cljc` +15/−7 with a new `query_cache_test.cljc` case — i.e.
the same cache seam §2 discusses. The other seven pins are unchanged. So the
markdown lint's "gitlinks no longer match" is one moved submodule, not drift
across the board.

### Upstream changes that bear on selection, query performance, GC, or analysis

**datahike (161 behind) — the significant one.** Recursive rules: `e15505fa`
*make recursive-rule demand restriction incremental and always-on (#889)*,
`6d5f602d` *evaluate a recursive rule's caller-supplied args as demand*,
`5f859c00` *a recursive rule no longer inherits its caller's relations*,
`b5ef35e2` *planner binds rule head vars that no branch body binds*. Those are
the exact deficiencies §2 measures at our pin, upstream and fixed — but not a
reason to keep the rule formulation: demand restriction is not memoization, and
the frontier walk is already 450× faster. Correctness: `225d6d23` *an undeclared
attribute matches nothing, not everything (#976)* — a latent absence-as-health
defect in any selector querying an attribute that has not been installed yet.
Temporal: `cbdbd242` *as-of agrees with the current indices for same-transaction
churn (#950)* and `5bacf93b` *History no longer loses a same-transaction
retraction (#949)* — **directly load-bearing for a `since`-based selector**,
since a change and its retraction inside one transaction is exactly the adoption
pattern. Bounds: `bb2ce2cc` *Query deadlines* adds `:timeout` plus
`datahike.query/*query-timeout-ms*` failing with `:datahike/query-timeout` —
the dependency's own expression of AGENTS §2.3, which stage 1 should adopt
rather than re-invent. Caching: `a18ce206` *A result cache for snapshot reads*,
`1186f9ab` *cache db view hash*, `1ea8972a` *runtime snapshot dependency tokens
for projection caches*, `5e3dd26a` O(1) bucket weight. Indexes: `cbcf21be`
fenced background AVET builds with atomic schema activation, `54ea57e9` *never
answer from a secondary index that is still building (#914)*, `d2b9e525` share
the node cache between connections to one store. GC: `ae659349` durable GC
roots, `ffa9f98d` long-running operations protect themselves from GC,
`8d5bffdf` a background GC must not kill an unrelated transaction, `11426b97`
`:db.type/store-ref` blobs tracked by GC.

**persistent-sorted-set (7 behind).** `5c5999e` *a write to a cold tree erased
the measure, and the next read restored the subtree (#24)* — a correctness bug
in the structure underneath every Datahike index read, on the cold path. Also
`2b5bff9` diff-buf and B-tree correctness pass, `a8e90b3` breadth-first warming.

**konserve (39 behind).** `b812eb2` *the GC safe point belongs to the store*
and `596b5ec` *monotonic write stamps — last-write becomes a hybrid logical
clock* are the seams datahike's own GC-root commits depend on: coupled, not
independently movable.

**sci (29 behind).** Nothing touches `interrupt.cljc`, `fork`, `intern`, or
`doc/interrupt.md` except `82a4297b` (pst frames). The rest is interop and
callsite caching plus Clojure 1.13 destructuring. Our 17 fork commits are
exactly the Seon seams (`:call-preparation-hook`, copy-on-write forked Vars,
read-only stock Vars, call observation, namespace snapshots, fact-safe Var root
installation) — a **rebase**, not a fast-forward.

**clj-kondo (27 behind) — analysis fidelity, which is selection fidelity.**
`95c7099d` *[perf] various optimizations*, plus false-positive fixes we eat
daily: `52d4e2fd`/`a8c538e7` `keys`/`vals` nilability, `31afed37` a map value's
type does not leak to element bindings, `4022ff03` keys after `&` count as
written, `ef26c0df` conflicting alias for current namespace, `a021ce30` no
redundant int/float coercion warning, `d72e57fd` a hook-rewritten call is only
checked for arity. Since `:seon.fn/calls` is clj-kondo-derived (E2), analyzer
fidelity is upstream of every selection answer.

**malli (74 behind).** `16961e2b` *`mi/collect!` when `*ns*` has been unmapped*
— a plausible match for instrumentation flakiness under hot reload; `7bcf4a7a`
arity extraction before instrument; several validator/ref performance commits.

**clojure (37 behind, 1.13.0-alpha).** `da6d70e1` *CLJ-2897 fixed class loading
context for prepl evaluation* and `98af726e` *`*repl*` not true inside prepl* —
`bin/seon`'s prepl is a first-class seam, so note these, but tracking an alpha
for two prepl fixes is not worth whole-program risk.

### Amendment 2026-09-17 17:40Z (orchestrator, from the pin-move lane's refutation)

The persistent-sorted-set row above is amended by
[pss-pin-move-2026-09-17.md](pss-pin-move-2026-09-17.md): `8fea23b` is a
clean fast-forward and green on its own suites (418/56,149 JVM; 252/3,209
node), but (1) every site `5c5999e` touches is guarded by
`measureOps != null && _measure != null`, and Datahike configures no measure
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:547`), so the
fix is a no-op for us; and (2) the submodule is not on our classpath —
Datahike resolves `persistent-sorted-set {:mvn/version "0.4.137"}` from Maven
(`reference-code/datahike/deps.edn:20`), and the current gitlink is exactly
that released tag. **Do not move the gitlink alone.** Moving the Maven
coordinate (0.4.137 → 0.5.144) belongs to the Datahike upstream-merge lane,
gitlink and coordinate together. HOLD.

### Recommendation per pin, and the proof a move needs

| dependency | verdict | reason | proof |
|---|---|---|---|
| **persistent-sorted-set** | **MOVE** to `8fea23b` (clean fast-forward, 0 ahead) | `5c5999e` is a real cold-tree correctness bug beneath every index read | `clj -X:test` (cognitect runner, `test-clojure`, `-Dpss.diffBufSize=256 -ea`) + `clj -M:node-tests`; then datahike's suite; then `bin/test --platform`. Move together with any datahike bump. |
| **clj-kondo** | **MOVE**, two steps: push our 2 commits to the fork, then fast-forward the fork onto upstream `13a32d1c` | analyzer fidelity is upstream of `:seon.fn/calls`, and our 2 metadata commits are the only divergence | `clj -M:test` + `clj -X:test-regression`; then re-lint `src/` through the edit hook's own path; then `bin/test --platform`. |
| **datahike** | **HOLD the pin; open a scheduled upstream-merge lane** | 161 upstream commits Seon genuinely wants (temporal correctness, query deadlines, GC roots) against a fork 106 ahead with 5 unpushed — a merge project, not a bump | `bb test` (bb.edn `test` task, kaocha via `:test`, `-ea --add-modules jdk.incubator.vector`) + `bb planner-bench --assert`; then `bin/test --platform`. **Free prerequisite: push the 5 local commits.** |
| **konserve** | HOLD | coupled to the datahike merge through the GC safe-point and write-clock changes | `clj -X:test` (jimfs) + `clj -M:cljs` node tests; then datahike store tests; then `bin/test --platform`. |
| **sci** | HOLD | 17 fork commits are our seams; nothing upstream fixes an interrupt/time-limit/fork/intern defect we have | `bb test:jvm`; then `bin/test --platform` plus a live SCI-mode evaluation probe on a scratch cluster. |
| **malli** | HOLD until the 2 SCI patches are re-applied, then MOVE | instrumentation is load-bearing for every gate; a malli regression silently disarms contracts | `bb test-clj` with `TEST_SCI=true`; then the instrumentation-sensitive Seon set and `bin/test --platform`. |
| **core.async** | HOLD | the 5 commits on `dev-flow-alpha` are docs plus a release-plugin bump; zero flow behavior | `mvn test` / `lein test`; then `bin/test --platform`. Not worth a gate. |
| **clojure** | HOLD; revisit at 1.13.0 final | two prepl fixes do not justify tracking an alpha | `mvn test` / `ant test`; then `bin/test --platform` **and** a live boot + prepl reachability probe on a scratch cluster. |

**Cross-cutting, and the most urgent item in this section:** three forks carry
**unpushed local commits** — datahike 5, sci 5 (vs `fork/main`) and 7 (vs
`fork/seon`), clj-kondo 2. Their only record is this laptop. Pushing them costs
nothing, changes no pin, and is a prerequisite for every move above.

---

## 6. Verdict

**Do we have an efficient, correct selector today? No — but only one half is
broken, and it is not the half everyone suspects.**

- The **reverse-reach query is solved**: `gate-set`'s frontier walk over AVET
  answers the worst seed on `default` in **14.181 ms** (1,009 of 1,829 tests) and
  a leaf in **1.562 ms**. The 7.2-second number in the open issue is stale by one
  commit (`1d141d26a`).
- The **change side is three different answers to one question**: a database
  identity set (`check-in-process`), a SHA-256 filesystem diff against a file in
  `tmp/` (`bulk-selection` + `selection.clj`), and loaded-namespace membership
  (`fast.clj:18`). None of them is the cluster's last recorded run.
- **Datahike already answers the change side in 24 ms** via
  `(db/since (db/history db) basis)`, flat in distance, with retractions
  included — replacing both filesystem walks and the basis file.

### Smallest set of changes, in stage-1 terms

1. `seon.test/select` — one public function of `(database, request)` doing (a)
   `since` from the last run's `:seon.test.run/basis-t` on this cluster's branch,
   (b) ONE shared-`seen` frontier walk seeded by all changed identities, (c)
   platform-declared plus explicitly named, (d) a reason per member.
2. Route `check`, `run-owned` admission and the coordinator's request through it.
   `bin/test` parses arguments into a request and decides nothing; `--paths`
   stays snapshot isolation, never a selector.
3. **Delete in the same slice**: `src/seon/test/selection.clj` entirely (basis
   file, `input-digests`, `changed-inputs`, `widening-path?`, `reaching-tests`),
   `runner/requested-changed-paths`, `reaching-selection`, `bulk-selection`,
   `record-green-basis!`, and the coordinator policy at `runner.clj:3713`,
   `:3577`. First move the classpath-cache fingerprint off `selection/input-digests`.
4. Drop `:seon.test/subject` / `:seon.test/pending-subject` from the walk
   (E2: coverage is the stored call graph).
5. Accrete the selected-Var collection arity on the execution owner
   (delegate to `run-selected-tests`) and delete the singleton loop in
   `check-in-process`.
6. File two issues found here: the 1000-element pull cap on `:seon.test/reach`
   (`test.clj:63`, `runner.clj:2229` vs `pull_api.cljc:16`), and the stale
   7.2-second claim in
   [function-entity-render-reach-query-cost](../../../seon/issues/function-entity-render-reach-query-cost.md).

### The regression that pins it

One test, canonical database fixture plus `::test-support/extra-schema` for the
synthetic graph, armed contracts, no mocks:

- Build a fixture graph of **N = 12** `:seon.fn/sym` rows with declared
  `:seon.fn/calls` edges four levels deep including one **cycle**, and **M = 6**
  `:seon.test/sym` rows with call edges into it: two reaching the changed leaf,
  one reaching it only through the cycle, one reaching a sibling subtree only,
  one declared `:seon.test/platform`, one with no edges at all and no recorded
  `:seon.test/reach`.
- Record a run entity with `:seon.test.run/basis-t` and
  `:seon.test.run/tested-branch` at the fixture's basis.
- Transact a new `:seon.fn/source` for exactly one leaf. Retract another
  identity's `:seon.fn/source` to cover the tombstone path.
- Assert the **exact** selected set and the **exact** reason on each member:
  the three reaching tests with `:seon.test/reaches-changed` naming the leaf, the
  platform test with `:seon.test/platform`, the edgeless test with
  `:seon.test/reach-unknown`, and the sibling-subtree test **absent**.
- Assert `(select db request)` is a pure function of the database value: calling
  it twice on the same value returns `=` results and transacts nothing.
- Record **ms** and **datoms read** for the call (the read evidence
  `seon.db` already appends carries the dependency plan), and assert the datom
  count is bounded by the fixture graph — the honest bound, not a tuned constant.
- Assert the ABSENCE case loudly: a test with no recorded reach and no path from
  the change is reported as unknown, never as unaffected.

