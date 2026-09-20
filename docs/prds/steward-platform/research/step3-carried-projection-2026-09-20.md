---
type: research
status: complete-with-limitations
created: 2026-09-20
tags: [schema, malli, datahike, projection, adoption, prototype]
---

# Step 3: stamped database values and acquired projections

This is a research/prototype lane, not a production implementation. No `src/`
or `test/` file is owned or changed. The source tree was concurrently edited;
the census records its own file digests rather than claiming one Git commit
contains all inspected bytes. The resumed instruction forbids worktrees and
requires a scratch operator root for restart verification; it supersedes the
original scratch-JVM-only restriction. No worktree was created.

Read end to end: AGENTS.md §§1–4 and lane rules 11–16; the Malli-native bridge
PRD (including re-reading its ruled §6 after resume); bridge-dissolution-review;
publication-projection-repair; the data-modeling guide §§5–6; and the Datahike
skill. Also read the data-oriented Clojure, REPL and testing skills and the
context-generation roadmap entry. These are the named authorities, not claims
that their historical line numbers match today's concurrently edited files.

The dependent-ref and heterogeneous-tuple review belongs to the other review
lane and is deliberately not repeated here. The useful archaeology is the
publication repair's **0 persisted forms versus 3,208 supplied forms**, its
per-wrapper config-acquisition repair, and the dissolution review's explicit
restart and candidate exceptions. `git log` was used to inspect the recent
carrier/source-owner history. No foreign lane or session was operated.

**Result:** stamped lookup and explicit once-per-generation persisted-row
acquisition verify across a real JVM restart. Metadata alone is insufficient;
`with` can preserve a stale projection. Candidate replacement requires dependent
recompilation. Durable candidate-row admission did not verify: that separate
probe exhausted a 6 GiB scratch heap. No production adoption or gate is claimed.

## Dependency ledger and the carrier's actual representation

Dependency pins inspected: Datahike `e11845bac78e1241bca0766ddc07d978bd63d74a`;
Malli `606083c5c5b388e84d169c7080af33ed3ec242ae`.

- `src/seon/db.clj:232` (`carry-projection-state`) uses `vary-meta` to snapshot
  `:seon.schema/projection` on a Datahike DB record. It additionally carries a
  mutable state pointer. Existing snapshot metadata wins over new state.
- `src/seon/db.clj:200` discovers connection state from the wrapped atom's
  metadata, falling back to the operator instance table. `:218` attaches that
  pointer; `:275` obtains the raw DB, snapshots connection state and then falls
  back to a handed construction projection. This fallback was essential to
  the repaired fresh-publication case.
- `src/seon/db.clj:1115` follows `IHistory/-origin` to the schema DB;
  `:1168` reads **Clojure metadata** there. It does not read a wrapper map or
  a persisted `:meta` field.
- Datahike `db.cljc:96` expands ordinary `defrecord` and replaces selected
  methods; `:307` declares DB, including a separate field named **`meta`**.
  `db-transient`/`db-persistent!` at `:198`/`:217` update indexes on the record.
  They do not intentionally strip its Clojure metadata.
- Datahike `api/impl.cljc:145` returns `@conn`; it does not copy connection
  metadata onto the value. `connector.cljc:38`/`:45` expose metadata on the
  connection's atom; `:99` creates a fresh atom with listener metadata only.
  `connections.cljc:38` can reuse an existing live connection. Therefore a
  second `connect` without final `release` is **not** a restart proof.
- `connector.cljc:82` returns the atom's DB for a streaming writer, but its
  non-streaming path reconstructs from storage. Application metadata survival
  is not a general `d/db` guarantee.
- Datahike `writing.cljc:48`/`:165` stores the DB's **field** `:meta`, not
  `(meta db)`. `stored->db` at `:231`/`:252` constructs a fresh empty DB and
  populates its stored fields (`:276` sets the field `:meta`). Arbitrary
  Clojure metadata is absent from that reconstruction.
- `versioning.cljc:499`/`:510` implements `branch-as-db` using `stored->db`;
  `:469`/`:486` does the same for `commit-as-db`. `api/impl.cljc:387` delegates
  to it. Branches reuse index roots (`versioning.cljc:224`); cross-store forks
  copy stored data (`:550`), so **datoms survive and process metadata does not**.
- Temporal constructors at `api/impl.cljc:148`, `:153`, `:185` retain their
  input DB as `origin-db`; `db.cljc:544`, `:609`, `:675` return that origin.
  The wrapper's own metadata need not contain the projection for Seon's
  origin-based reader to find it.
- Malli `registry.cljc:17` retains schema values in a fast registry;
  `:54` searches a composite left-to-right. Composition changes lookup order;
  it does not reconstruct an already compiled schema or replace its captured
  registry. This matters for candidate replacement, below.

## Identity and restart contract

Use a new **non-identity** root attribute, proposed
`:seon.cluster/projection-digest`, whose value is the selected published
`:seon.source/digest`. The source digest's declaration has
`:seon.db/identity true`; aliasing it without overriding that property would
make the cluster stamp an upsert identity. The stamp is an observation of
which population this branch contains, not the identity of the cluster.

`src/seon/cluster/source.clj:104`–`:152` computes `:seon.source/digest` from
source paths and file bytes. It is **not** the digest of `m/form` outputs.
The canonical fixture even seals its population with a 64-zero sentinel
(`test/seon/test_support.clj:348`). Preserve the ruled population identity;
use a separately computed canonical-form digest as the restart comparison
oracle. Do not claim those two hashes should be equal.

Restart acquisition must read the **persisted declaration population of the
stamped DB/commit**, including schema rows, function contracts, function
sources and admission provenance. Keep `projection-from-rows`' duplicate,
source and provenance checks (`src/seon/schema.clj:2414`; database queries
at `:2643`). Packaged forms are not a restart source for an older sovereign
branch. Ordinary `projection-for` reads neither those rows nor files: it
reads the stamp and resolves an already acquired registry, or refuses.

The scratch namespace retains its holder in a private experiment atom. Production
must pass the holder explicitly or close its one-argument reader over that holder; this does not justify a new process-global registry
service. Integrate the acquired registry into the existing DB/environment
carrier. A retained DB must keep its own generation even after the
connection advances. Never overwrite its projection merely because the
holder/environment has a newer value.

The same digest may describe several exact forks; their registry may be
shared, but their connections, database values, basis transactions and
agent state must remain distinct. Candidate/accepted declaration changes
must select a new generation. A holder miss is not permission to reconstruct
at a running read. Explicit acquisition verifies, builds once and publishes
an immutable entry; ordinary lookup does not populate the holder.

Temporal interpretation should retain today's **origin schema** rule:
`projection-for` reads the stamp from `schema-database`, not from the filtered
view. A `since` view may contain no stamp datom, and a history view may expose
several past stamp values. Neither means the origin lacks a stamp. To request
an older schema world, acquire the older commit DB explicitly. This preserves
existing semantics rather than silently changing as-of interpretation.

## Census method and risk key

The primary census is the six requested search families under `src/`,
including `call-with-projection-state`. `tmp/probe/step3-inventory.py` strips
comments/string contents, groups lexical occurrences by top-level owner,
and records source SHA-256s in `tmp/probe/step3-inventory.json`. The result is
**125 matching owner spans across 32 files, 180 lexical occurrences**:
**33 P, 79 R, 13 C; 33 marked adoption-path owners**. These are not 180
runtime calls: definitions and local bindings are included. They are a dated
conversion inventory, not a maintained production roster. Supplemental
selectors below cover mechanisms the six-name search cannot find.

P = publication/boot/acquisition input; R = running consumer or propagation;
C = candidate construction/admission (shared acquisition constructors are
classified with C). ★ marks a path used in publication/development adoption,
not every transitive caller in the program graph.

Sources: F = forms/declaration constructor or packaged input; D = persisted
row reconstruction; V = value-carried projection; H = supplied/bound state.
The table names the source family used at that owner, not an assertion that
all F calls reread disk (packaged forms are cached). A “not consulted here”
entry means that this seam does not check a carried value; it does **not**
mean a supplied DB is necessarily bare. Most D running readers receive a DB
which can already carry V and simply ignore that fact.

Risk A = the **lost construction/wrong generation** class: selecting empty
or older rows, a context, or packaged forms instead of the selected
construction generation changes reference/codec/contract interpretation.
Risk B = the **repeated compilation during population/config acquisition**
class: reconstructing rows/registries or resolving defaults in a repeated
path. The table describes how a seam could reproduce a class, not a measured
claim that every listed seam caused this week's incident. H-only propagation
has no independent compilation cost; it must preserve the selected generation.

| File:line / owner | Class | Sources consulted | Carried state consumed? | Adoption risk |
|---|---|---|---|---|
| `src/seon/agent.clj:179` `render-settings-html` | R | D | not consulted here | B: row reconstruction |
| `src/seon/bootstrap.clj:718` `next-entry` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/cluster/agent.clj:555` `submit-source!` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/cluster/source.clj:174` `database` | P ★ | D | not consulted here | B: row reconstruction; A: construction/world mismatch |
| `src/seon/cluster/source.clj:425` `record-results-at-head!` | R | D/H | supplied (H) | B: row reconstruction |
| `src/seon/cluster/source.clj:465` `record-results!` | R | D | not consulted here | B: row reconstruction |
| `src/seon/cluster/wake.clj:416` `wake-matchers` | R | D | not consulted here | B: row reconstruction |
| `src/seon/cluster.clj:299` `mcp-effective` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/cluster.clj:604` `mcp-runtime-observation` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/cluster.clj:681` `require-candidate-value` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/cluster.clj:709` `resolve-bootstrap` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/cluster.clj:1213` `activation-requirements` | P ★ | D | not consulted here | B: row reconstruction; A: construction/world mismatch |
| `src/seon/cluster.clj:1638` `require-admissible-branch!` | P ★ | F/H | supplied (H) | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/cluster.clj:1670` `accrete-schema-population!` | P ★ | F/H | supplied (H) | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/cluster.clj:1730` `populate-source!` | P ★ | F/H | supplied (H) | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/cluster.clj:1974` `source-base!` | P ★ | D/H | supplied (H) | B: row reconstruction; A: construction/world mismatch |
| `src/seon/cluster.clj:2227` `incremental-source-refresh!` | P ★ | F | not consulted here | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/cluster.clj:2462` `development-source-refresh!` | P ★ | D/H | supplied (H) | B: row reconstruction; A: construction/world mismatch |
| `src/seon/cluster.clj:2647` `refresh-source!` | P ★ | F/H | supplied (H) | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/cluster.clj:3049` `commit-fault!` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/cluster.clj:3212` `projection-executor` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/cluster.clj:3500` `stand-cluster-runtime!` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/cluster.clj:3634` `stand-boot-layers!` | P | D/H | supplied (H) | B: row reconstruction |
| `src/seon/config.clj:324` `default-population` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/config.clj:360` `default-decisions` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/config.clj:370` `read-manifest` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/config.clj:381` `compile-settings` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/config.clj:496` `apply-compiled!` | R ★ | D/V | yes (V) | B: row reconstruction; A: construction/world mismatch |
| `src/seon/config.clj:570` `apply!` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/config.clj:581` `effective` | R ★ | V | yes (V) | A if handed generation differs |
| `src/seon/db.clj:253` `carry-derived-projection` | P ★ | F/D | not consulted here | B: row reconstruction; B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/db.clj:1168` `carried-projection` | R ★ | V | yes (V) | A if handed generation differs |
| `src/seon/db.clj:1189` `read-declarations` | R ★ | V | yes (V) | A if handed generation differs |
| `src/seon/db.clj:1255` `ask-declarations` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/db.clj:3743` `arity-mismatches` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/db.clj:3958` `write-report-validator` | R ★ | H | supplied (H) | A if handed generation differs |
| `src/seon/db.clj:4124` `transact-call` | R ★ | V | yes (V) | A if handed generation differs |
| `src/seon/effect.clj:190` `accepts-request?` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/effect.clj:459` `with-request-context` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/env.clj:151` `declared-members` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/error.clj:1075` `reader-correction` | R | D | not consulted here | B: row reconstruction |
| `src/seon/error.clj:1153` `refusal-data` | R | D | not consulted here | B: row reconstruction |
| `src/seon/error.clj:1507` `commit-call` | R | D | not consulted here | B: row reconstruction |
| `src/seon/error.clj:1595` `recording` | R | D | not consulted here | B: row reconstruction |
| `src/seon/error.clj:1782` `rendered-error-value` | R | D | not consulted here | B: row reconstruction |
| `src/seon/error.clj:2005` `faults-form` | R | D | not consulted here | B: row reconstruction |
| `src/seon/error.clj:2048` `render-faults-html` | R | D | not consulted here | B: row reconstruction |
| `src/seon/flow.clj:1106` `projection-executor` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/fn.clj:826` `runtime-analysis-batch` | R ★ | V | yes (V) | A if handed generation differs |
| `src/seon/fn.clj:1195` `source-rows` | R ★ | V | yes (V) | A if handed generation differs |
| `src/seon/fn.clj:1249` `declaration-forms` | P ★ | F | not consulted here | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/fn.clj:1586` `contract-findings` | R ★ | D/V | yes (V) | B: row reconstruction; A: construction/world mismatch |
| `src/seon/fn.clj:2450` `backfill-contract-facts!` | P | D | not consulted here | B: row reconstruction |
| `src/seon/fn.clj:2536` `desired-rows` | P ★ | F | not consulted here | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/fn.clj:2755` `reconcile-tx-in` | P ★ | D/V | yes (V) | B: row reconstruction; A: construction/world mismatch |
| `src/seon/fn.clj:2982` `index!` | P ★ | D/V/H | yes (V) | B: row reconstruction; A: construction/world mismatch |
| `src/seon/instrument.clj:997` `apply!` | R ★ | F | not consulted here | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/print.cljc:341` `shipped-option-defaults` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/print.cljc:932` `node-face-validator*` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/problems.clj:385` `problems` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/program.cljc:241` `authored-shapes` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/reconcile.cljc:320` `plan` | R | D/V/H | yes (V) | B: row reconstruction |
| `src/seon/render/walk.clj:382` `root-pull-plan` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/render/walk.clj:490` `acquired-tree` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/render/walk.clj:549` `root-acquisition` | R | V/H | yes (V) | propagation; require matching generation |
| `src/seon/render/walk.clj:766` `neighborhood` | R | V/H | yes (V) | propagation; require matching generation |
| `src/seon/render/web.clj:683` `declared-entity-units` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/render/web.clj:2197` `current-page` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/render/web.clj:2333` `render-pass` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/render/web.clj:2474` `derive-context!` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/render/web.clj:3264` `debug-response` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/render/web.clj:3459` `data-response` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/render/web.clj:3562` `handler` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/render.clj:110` `request-projection` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/render.clj:121` `request-profile` | R | V/H | yes (V) | propagation; require matching generation |
| `src/seon/render.clj:343` `schema-producers` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/render.clj:1035` `invoke-selected` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/schedule.clj:512` `execution-context` | R | D | not consulted here | B: row reconstruction |
| `src/seon/schema/admission.clj:395` `admit` | C | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema/datahike.clj:14` `packaged-forms` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema/datahike.clj:53` `resolve-malli-form` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema/datahike.clj:98` `resolve-datahike-form` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema/datahike.clj:193` `form->datahike-value-type` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema/datahike.clj:283` `malli->datahike-attr` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema/datahike.clj:297` `malli->datahike-schema` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema/datahike.clj:589` `encode-transaction` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema/datahike.clj:635` `decode-attribute-value` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema/edn.clj:412` `packaged-forms` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema.clj:41` `schema-edn-packaged-forms` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema.clj:821` `*packaged-forms*` | R | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema.clj:1013` `packaged-forms` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema.clj:1020` `candidate-forms` | C | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema.clj:1042` `declaration-population` | C | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/schema.clj:1057` `call-with-forms` | C ★ | F | not consulted here | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/schema.clj:1060` `call-with-projection` | R ★ | H | supplied (H) | A if handed generation differs |
| `src/seon/schema.clj:1068` `call-with-projection-state` | R ★ | F/H | supplied (H) | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/schema.clj:1148` `declaration-projection` | C ★ | F | not consulted here | B: fresh compilation/input selection; A: construction/world mismatch |
| `src/seon/schema.clj:2675` `projection-from-database` | C ★ | D | not consulted here | B: row reconstruction; A: construction/world mismatch |
| `src/seon/sci/eval.clj:623` `evaluation-projection` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/sci/eval.clj:792` `committed-row?` | C | D/V | yes (V) | B: row reconstruction |
| `src/seon/sci/eval.clj:822` `install-row!` | C ★ | D | not consulted here | B: row reconstruction; A: construction/world mismatch |
| `src/seon/sci/eval.clj:1397` `documentation-contract` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/sci/eval.clj:1676` `acquire-program!` | C ★ | V/H | yes (V) | A if handed generation differs |
| `src/seon/sci/eval.clj:2109` `base-ctx` | C ★ | D/V/H | yes (V) | B: row reconstruction; A: construction/world mismatch |
| `src/seon/sci/eval.clj:2220` `fork-cluster-ctx` | C ★ | D | not consulted here | B: row reconstruction; A: construction/world mismatch |
| `src/seon/sci/eval.clj:2636` `evaluate` | R | V/H | yes (V) | propagation; require matching generation |
| `src/seon/test/arm.clj:38` `packaged-test-projection` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/test/arm.clj:249` `initialize-contracts!` | P | H | supplied (H) | propagation; require matching generation |
| `src/seon/test/fast.clj:35` `-main` | P | H | supplied (H) | propagation; require matching generation |
| `src/seon/test/runner.clj:1445` `restore-live-cluster-schema!` | P | D | not consulted here | B: row reconstruction |
| `src/seon/test/runner.clj:1686` `packaged-test-projection` | P | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/test/runner.clj:1935` `serve-worker-commands!` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/test/runner.clj:1996` `worker-command-loop!` | P | D/H | supplied (H) | B: row reconstruction |
| `src/seon/test/runner.clj:2183` `reach-cache` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/test/runner.clj:2591` `complete-members` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/test/runner.clj:4494` `coordinator-main!` | P | H | supplied (H) | propagation; require matching generation |
| `src/seon/test/runner.clj:4532` `-main` | P | D | not consulted here | B: row reconstruction |
| `src/seon/test.clj:461` `run` | R | V/H | yes (V) | propagation; require matching generation |
| `src/seon/test.clj:1453` `resolve-test` | R | H | supplied (H) | propagation; require matching generation |
| `src/seon/test.clj:1482` `prepare-tests!` | R | D | not consulted here | B: row reconstruction |
| `src/seon/test.clj:1936` `check-request` | R | V/H | yes (V) | propagation; require matching generation |
| `src/seon/turn.clj:1207` `declaration-projection` | C | F/D/V | yes (V) | B: row reconstruction; B: fresh compilation/input selection |
| `src/seon/turn.clj:1244` `row-tx` | C | F | not consulted here | B: fresh compilation/input selection |
| `src/seon/turn.clj:3473` `evaluation-terminal-data` | R | V | yes (V) | propagation; require matching generation |
| `src/seon/turn.clj:4980` `turn` | R | H | supplied (H) | propagation; require matching generation |

### Supplemental selectors and exact precedence

The lexical table intentionally over-includes carriers and constructors, but
cannot find every selector merely by those six names. These additional
owners and their precise choices belong in the conversion:

| Owner | Current choice; state already available | Risk and required treatment |
|---|---|---|
| `src/seon/db.clj:200` `connection-projection-state`; `:218` `carry-connection-projection-state!`; `:232` `carry-projection-state`; `:275` `resolve-database-value` | connection metadata → operator instance lookup; retained DB metadata → state's projection; acquired state → handed construction projection | A: identity of connection is checked today, but generation matching is absent. Keep explicit construction acquisition before rows/stamp exist; running values require stamp and matching snapshot. Remove operator-table discovery after callers carry state. |
| `src/seon/db.clj:1115` `schema-database`; `:1213` relation-only declarations | temporal origin owns schema; a relation-only read has no DB and uses handed declarations | A: looking for a stamp in `since`/history results is wrong. Do not require a fictional DB for a relation-only query. |
| `src/seon/schema.clj:1017` `candidate-forms`; `:1028` `declaration-population`; `:1077` `active-projection`; `current-projection`, `handed-projection`, `registered-schemas` | candidate overlay → packaged binding → projection-state → bound projection → packaged fallback; active state and bound projection are alternate custodians | A/B: a generic “give me declarations” call can change world or reconstruct. Convert runtime callers to explicit acquired values; retain packaged input only at construction. |
| `src/seon/schema.clj` `build-projection`, `materialize-projection`, `projection-from-rows`, `projection-with-schema`, `projection-without-schema`, `projection-with-function-contract`, `projection-with-pulled-form-in` | constructor / candidate delta / selector-derived form, not an ordinary DB read | B: consolidate generation acquisition; preserve candidate changes and selector-local derived schemas. A pulled-form projection does not change the stored population stamp. |
| `src/seon/schema/edn.clj:603` `admit` | `current-projection` when present, full build otherwise | A/B: admission must name its base/candidate; no invisible package fallback for running admission. |
| `src/seon/ai.clj:410` `configured-targets`, `:596` `wire-settings`, `:632` `extra-body-request-ident`; `src/seon/shell/jvm.clj:78` `environment-overrides`; `src/seon/program.cljc:87` `base-context-injected-symbols` | `declaration-population`, with no explicit DB selection here | A/B: supplied environment already has the world. Feed its generation's forms instead of the dynamic selector. These are additional runtime consumers outside the six-name census. |
| `src/seon/instrument.clj:617` policy resolution | unwrapped `handed-projection` | A/B: use the one generation/policy acquired by arming; do not move config/default compilation back into a wrapper call. |
| `src/seon/env.clj:105` `advance-projection!`; `:141` `declared-members` | state advances by basis; member grammar is a process-lifetime core-definition delay | A: a newer basis alone does not prove a registry matches its stamp. Core bootstrap grammar is an explicit pre-population dependency, not permission for post-publication package fallback. |
| `src/seon/sci/eval.clj:618` `evaluation-projection` | context → request → carried DB (opposite the render reader's DB-first precedence) | A: an old context can interpret a new DB with old declarations. Select the DB stamp and verify any supplied/context generation against it. |
| `src/seon/sci/eval.clj:810` `install-row!` | prepared projection → context → row rebuild; accepted functions/schemas may rebuild again | A/B: carry the exact terminal candidate through installation, not per-row reconstruction. Match committed row/source and generation. |
| `src/seon/render.clj:88` `request-projection`; `src/seon/render/walk.clj:382`, `:549`, `:766` | carried DB → supplied request → context; walk additionally falls back to `current-projection` | A: retain DB authority and refuse conflicting supplied worlds. DB-less rendering may use an explicitly supplied projection; it does not invent a DB stamp. |
| `src/seon/fn.clj:1238` `declaration-forms` | request forms → packaged forms; indexing already has the construction/published DB | A/B: request the generation once and pass its forms through row ownership, reference flattening and reconciliation. |
| `src/seon/turn.clj:1207` `declaration-projection` | carried DB; after earlier declarations in the same transaction, reconstruct from the writer's current datoms | A/B: this is a necessary candidate advance, not removable dead fallback. Carry the progressively admitted candidate, or acquire it at that transaction boundary; later declarations must see it. |
| `script/seon/fresh_operator.clj:1900` `effective-form`; `:1916` `instrument-form` | dynamically resolved `call-with-projection-state`, surrounding raw connection dereference | A/B: ensure generated forms supply a DB acquired under the same stamped generation; raw `@connection` does not inherit atom metadata. |
| `test/seon/test_support.clj:145` `with-published-file-database`; `:388` `create-base`; `:949` `reconnect-with-projection` | explicit acquisition from rows at fixture construction/reconnect | P: keep one row loader at acquisition and attach registry before seeding/SCI. Do not replace the canonical fixture with a small schema roster. |
| `test/seon/test_support.clj:939` `run-database-body`; `:672` fixture projection; `:770` fixture restoration; `:1054` program-row helper | carried state and occasional reacquisition | A/B: preserve generation custody and restore the right fixture world; acquisition is allowed, ordinary helper reads must not rebuild. |

The supplemental table is deliberately not summed with the 125-owner census:
it overlaps it and groups constructor families. Its source references are the
inspected working-tree snapshot; later bridge edits can move line numbers.
Function names and the recorded source digests are the stable search anchors.

## Exact production changes required by step 3

This is the edit specification, not a suggestion to create another subsystem.
The six-name census plus supplemental table is the caller conversion list.

| File / functions | Change |
|---|---|
| `resources/seon/schemas/seon.cluster.edn` cluster entity; `resources/seon/schemas/seon.source.edn` digest type | Declare the new non-identity stamp, reusing digest validation with identity disabled. Require it for running cluster acquisition; publication's pre-stamp construction remains explicit. Reset once with the owning schema cut. |
| `src/seon/db.clj` connection/carrier/acquisition functions, `carried-projection`, `read-declarations`, `arity-mismatches`, `transact-call`, `write-report-validator` | Resolve origin stamp and its matching acquired value. Do not let retained metadata override a different stamp. Ordinary writes preserve generation; declaration writes validate and install candidate plus stamp atomically. All missing/conflicting cases are typed refusals. Retire hybrid `carry-derived-projection` rebuilding packaged + persisted populations. |
| `src/seon/schema.clj` `projection-from-rows`, `derive-projection-from-database`, `projection-from-database`; construction/delta functions | Keep one explicitly named acquisition loader from rows. Remove public running reconstruction only with all callers converted. Preserve exact forms, admissions, function sources/contracts and duplicate detection. The bridge steps 1–2 own compiled registry construction; reuse them. |
| `src/seon/cluster/source.clj` `database`, publication/sealing/upsert owners, result-recording acquisition | Acquire exact stamped commit once; publication input constructs before schema rows exist. Stamp the same population that the branch transaction contains. Test-result recording changes evidence, not the program generation. |
| `src/seon/cluster.clj` `populate-source!`, `source-base!`, `incremental-source-refresh!`, `development-source-refresh!`, `refresh-source!`, `stand-boot-layers!`, activation/schema compatibility owners | Hand one construction generation into native attributes, canonical rows and program indexing. On adoption reconcile with the selected candidate, then carry that generation through load, config, instrumentation and SCI. On restart load persisted rows once before running readers. Preserve existing staged JVM-adoption semantics. |
| `src/seon/fn.clj` indexing/reconciliation/read owners listed above | Remove carried→row and request→packaged fallbacks. Fresh indexing receives the explicit pre-stamp generation; published/adopted reads receive stamped values. Reference flattening must never infer its schema from an empty DB. |
| `src/seon/sci/eval.clj` `evaluation-projection`, `committed-row?`, `install-row!`, `acquire-program!`, `base-ctx`, `fork-cluster-ctx`, documentation/evaluation consumers | Require consistent generation across DB/context/request; acquire once at context creation; advance accepted candidate once per terminal world. Do not rebuild per installed row or ordinary evaluation. |
| `src/seon/turn.clj` `declaration-projection`, `row-tx`, settlement candidate propagation | Preserve sequential declarations in one transaction. Update the candidate for later declarations and validate base/generation at the writer, not at a pre-read before queuing. |
| `src/seon/env.clj` `advance-projection!`, environment construction; `src/seon/cluster/agent.clj`, `src/seon/flow.clj`, `src/seon/effect.clj` propagation | Carry the same acquired value and stamp with the environment/request. Do not add a second registry service or mutable forms table. A newer basis does not authorize a mismatched generation. |
| `src/seon/config.clj` `apply-compiled!`, `apply!`, `effective`; `src/seon/instrument.clj` `apply!`/policy acquisition; operator generated forms | Distinguish manifest publication input from effective running config. Keep policy/default acquisition once per arming operation; compiled wrappers consume retained validators and supplied policy. |
| `src/seon/error.clj`, `src/seon/schedule.clj`, `src/seon/cluster/wake.clj`, `src/seon/reconcile.cljc`, `src/seon/problems.clj` tabled readers | Consume the stamped value instead of scanning declaration rows. Preserve typed missing-input reporting, especially error paths; an absent registry is not an empty schema population. |
| `src/seon/render.clj`, `src/seon/render/walk.clj`, `src/seon/render/web.clj`, `src/seon/bootstrap.clj`, `src/seon/agent.clj` tabled readers | Keep the DB's generation authoritative and verify explicit supplied context. No package lookup at paint/evaluation time. Carry DB-less render authority explicitly. |
| `src/seon/ai.clj`, `src/seon/shell/jvm.clj`, `src/seon/program.cljc`, `src/seon/print.cljc`, schema convenience arities | Convert implicit declaration selection to explicit generation inputs. Bootstrap/core grammar has an explicit acquisition lifetime; remove the convenience fallback only after converting callers. |
| `src/seon/test.clj`, `src/seon/test/runner.clj`, `src/seon/test/arm.clj`, `src/seon/test/fast.clj`, `test/seon/test_support.clj` tabled owners | The canonical harness acquires/seals its own stamped population. Worker/context reads use that value. Fixture's all-zero sentinel must not collide in a shared holder across different fixture populations; use the same real generation derivation as publication. |
| `AGENTS.md`, bridge PRD and affected skills | Update carrier, restart and candidate claims in the implementation slice. Do not leave historical “rebuild at every read” examples as current instructions. |

## Adoption proof the implementation must pass

The orchestrator, after bridge steps 1–2 and the batched reset, owns these
proofs. This research does not claim a live production adoption implementation.

1. **Fresh publication:** begin with zero canonical schema rows. The indexer's
   construction DB, reference flattening and writer use the exact supplied
   generation. Assert native component/reference attributes and nonzero
   expected program/schema population, not merely a green return value.
2. **Complete and incremental adoption:** retain an old DB before adoption;
   publish one schema/contract change; verify source digest, branch stamp,
   environment generation, wrapper contracts, and acquired SCI generation
   agree after success. The retained DB must still resolve its original
   registry. Observe the affected page separately from successful publication.
3. **Config cost:** count row-loader and registry-constructor invocations,
   plus effective-policy acquisition, across population, wrapper arming,
   accepted-row installation and first render. Ordinary reads perform **zero**
   row builds; each new generation/policy is acquired once. Measure publication,
   config validation and population commit phases separately, without raising
   observer bounds or attributing all writer time to registry compilation.
4. **Absence and mismatch:** unstamped DB, stamped-but-unacquired DB, stale
   carried metadata, mismatched explicit request/context, and `since` containing
   no stamp datom each have their defined result. Missing is typed; temporal
   origin acquisition succeeds when its stamp is present.
5. **Restart and sovereign branch:** stop/start the isolated root and rebuild
   from persisted rows for its stamp; compare canonical forms and normalized
   compiled-form digests. Change packaged input separately and prove it cannot
   replace an older branch's definitions. Predicate *execution* identity is a
   separate test: a stable named Var can change its root across reload.
6. **Candidate ordering and writer authority:** first declaration adds/replaces
   a schema; a later declaration in the same transaction sees that candidate.
   A queued candidate whose base changed refuses at the writer. Recompile
   dependents for replacements; retain unaffected compiled schemas. Rejected
   declaration transactions advance neither rows nor stamp.
7. Run the PRD's canonical armed named gates and platform proof under the
   orchestrator. No new regression was added here, per the resumed assignment.

## Candidate and holder semantics

Candidate identity is a digest of **[base generation, canonical admitted
candidate declarations]**, derived through `seon.id/digest`, not a string
concatenation or another identity generator. For replacement/removal the
candidate representation includes those operations and the complete affected
program declarations, including contracts and predicate sources. The prototype
uses one added scalar schema; it does not claim that a forms-only hash covers
all production declaration changes.

An exact fork initially has the base stamp and reuses its compiled registry.
A candidate fork transacts its declaration rows and candidate stamp together.
Until that candidate registry is acquired, lookup refuses even when the base
registry is present. An unsuccessful candidate transaction cannot advance the
base stamp. Reconstructing a candidate after restart needs its persisted
complete effective population; storing only an overlay without its base
identity/available population would be insufficient.

A composite of **compiled** candidate roots followed by compiled base roots
works for additions. Replacing a referenced base schema is different: an
already compiled dependent retains the registry with which it was compiled.
Recompile the changed keys and their dependent closure in the candidate
registry. The existing projection dependency graph supplies that closure;
do not infer it from names or add a second maintained dependent list.

A holder entry has a generation and the retained compiled schemas plus the
projection's required admission/storage products. It is not just a map of
validators. The prototype's size report counts retained root entries and
canonical form bytes; **it is not a retained-heap measurement**. Compiled
schemas contain caches, predicate Vars and generator objects. Comparing raw
JVM object printing across restarts would be a false proof. The comparison
below uses exact canonical authored forms and normalized `m/form` output,
replacing named callable/generator objects by their resolved qualified
symbols before canonical encoding. Unidentified objects refuse the probe;
they are never omitted. Stable declaration bytes do not promise that a live
Var's executable root cannot change after reload.

A hash-keyed holder also needs lifetime ownership. Do not evict an entry still
needed by a retained DB/temporal view; carrying the compiled value itself in
that DB's metadata naturally retains it. A missing entry yields the declared
unknown, not a rebuild or selection of a newer registry. Sharing is scoped to
an acquisition environment with the same executable predicate bindings; the
source digest alone is not evidence of equivalent arbitrary host closures.

## Census byte identity

Initial census occurred before the pause, near observed HEAD `2f5c253cf5a910c0524288e64a7d5d9559733543`; resumed HEAD was `12d96812675cdba28773a9347a8192f8cc08256e`. Neither identifies all dirty bytes. SHA-256 of each censused file:

```text
ca7dfb7c32e782280a166504f18e3feef0216ebc0e7ae43a9c3628db80be8139  src/seon/agent.clj
450a778d2b99804b69913f953a0f32966c7d9e38ae7a93987ec999c741fb0ef7  src/seon/bootstrap.clj
4819c2a7f27284af10d54df74a262ef1c93b99221920ba724ed6fd525bb35a01  src/seon/cluster/agent.clj
002283e0fc4ca04911a09754b65f18b7e6d6fee367088e29de9898ff76b7abef  src/seon/cluster/source.clj
980ff3c4abae8b6d507cb535a53b8b2d05937936af9e5ed76562d3a815d24cd0  src/seon/cluster/wake.clj
69136a85bdcefa5ceaccc7e92ba3d44d0fd630987312cca4e051fdef73c3f594  src/seon/cluster.clj
67fd37cf207bdfd374d3801f083b4ac5855cd2256d13baf3eede4e5e88c0daa6  src/seon/config.clj
425899894f95cd2b3ff0abf061f0e822706c9f08d06fd4ef243a6f0d30bb4d89  src/seon/db.clj
c79113e282f64830ba539afbb259683ad2c55a6e0ec3726da9da26c9cd7353af  src/seon/effect.clj
18dc250e731abb2d8599a91b90067ef7b907ac51ba48db8d3a235694fd0eca66  src/seon/env.clj
8822b81dfc36247b09c150807371ef5bbb8f181f59d8351cce55500503a54a4c  src/seon/error.clj
79dab529b809d0071a98bf5368c3c9a9e5446eff2c8572bb7b7c837b2eef47dc  src/seon/flow.clj
a591ce38c8ae285d381571eb3c8b86da249d16e6c96ba84ec181083a4f9040a7  src/seon/fn.clj
8630414379c33b3654d1c5233d99f2b925c334967f7883d661b7a341e7b66720  src/seon/instrument.clj
d42634c4b3a8f7e987845ab80c16f9b50522d94d4af051fe4eceb28d47f93186  src/seon/print.cljc
956de0c8b20a5aae0d89692ca09ae992a686871840feb60fa26a10dd568a1eb3  src/seon/problems.clj
9fd1232902e4ec7a766a090089a5ba87a1b747cdee1325b56198963868a5d884  src/seon/program.cljc
66f107ca26878f41c367b323c4d229920a1180d63e9037c9eccc77004c2a97c7  src/seon/reconcile.cljc
9dde5e92178011d65f3777841ea5a18b202b17258e33fcdab3a610744b78d084  src/seon/render/walk.clj
b75f15419934f41f89e96c46b97d6ba2d7033543c4735788592067bea10b00ad  src/seon/render/web.clj
d8fdf93d87488833aa0b25cb2f1f7fb9ae279c977acc0d1ea3347ff707487b99  src/seon/render.clj
c57467dcd4ad4d0f2b34fdbf380ce8717c7f7812cec4ee319b8232bc7c0e940b  src/seon/schedule.clj
e8f5c04c663f3376f4607668a3884a73e2c055542390e5a3a7062c8702098241  src/seon/schema/admission.clj
e4291b3dc28dd8076e3541303f0212b82f3be79d4d5c582dc7a36d5615e925f8  src/seon/schema/datahike.clj
56fde0c05231d877228d1af61c996af3fa60eaad89ea0be10cef370b1e008eb1  src/seon/schema/edn.clj
c8972b4f2b46ebac8258b9f393939b7d37a8e0c6457c0a9c75541efa3eb892af  src/seon/schema.clj
59b36e86ba8237b7df1a82d9b38c26b2141fba394fdc065a188ef88394a2a568  src/seon/sci/eval.clj
f3a5166d34d7570d5d4f674d33d226f904a3380a61e6bee9077f7b5216ecf96f  src/seon/test/arm.clj
84514502cce22996f6bf8f74135c77934cb0bcc8cffb2ea239b365debe340059  src/seon/test/fast.clj
74bdab843d4a7d718cd7df5ace54b71a65de95aae1ff0079b878f2aadc7b97ad  src/seon/test/runner.clj
a310088766bf654816c8839f8f18d8ae4980f27dfec652eba5124f87aa48ca9f  src/seon/test.clj
4276fa0664bf4f6a37b1c276b9836240f5832fc90d7f57c8359cb77480a1ea07  src/seon/turn.clj
```

## Measured canonical-fixture probe

`clojure -M:test tmp/probe/step3.clj` invoked
`seon.test.arm/initialize-contracts!` and handed that projection to
`seon.test-support/with-database`. The arming report was **1,368 instrumented
contracts**, **110 program namespaces**, mode **`:panic`**. The custom probe
functions are not indexed production functions; this is armed dependency/
fixture evidence, not a new registered regression or a cold gate.

| Operation on canonical fixture | Own Clojure metadata contains projection | `seon.db/carried-projection` finds same projection | Stamp retained |
|---|---:|---:|---:|
| `seon.db/db` | yes | yes | yes |
| `datahike.api/db` | no | no | yes |
| `history` of the carried DB | no | yes, via origin | yes, via origin |
| `as-of` at the basis of the carried DB | no | yes, via origin | yes, via origin |
| `since` at that basis | no | yes, via origin | yes, via origin |
| `with` on the carried DB | yes | yes | yes |
| `branch-as-db` materialization | no | no | yes |

The canonical fixture has **3,228** forms and source entity **38111** in this
run. An unstamped `with` result returned the missing-stamp refusal; an empty
holder with the stamp present returned unacquired-generation. Direct snapshot
metadata and an origin-carried projection are reported separately; the table
does not mistake `meta(history-db) = nil` for projection loss.

Compilation of the retained roots: **137.8845 ms**. One-generation holder:
**1 entry**, **3,228 compiled roots**, **718,796 bytes** of normalized canonical
compiled forms. Digest of those bytes:
`2d436c6cca9072eaa48bb6f74c2a59ebeeb8a358814fcad2f3d84bc06a486a64`.
Canonical authored-forms digest:
`eb06065453f90a8cfbf87ede73c3577bf242d0adb3fbba701042d3bb3c95308f`.

Five warmed trials of 10,000 **stamp query + holder lookup** calls took
`[156.270000 116.350584 98.652708 115.946208 86.214750]` ms:
**8.621–15.627 μs/read**, median **11.595 μs/read**. These are shared-machine
measurements, not a CI threshold or a full decoder benchmark. The prototype
uses a Datalog stamp query; the existing DB owner can resolve the unique
root attribute by indexed datoms without scanning declaration rows.

The fixture run then refused the attempted cross-store memory→file fork with
`{:type :must-be-flushed}`, message **“PSS root must be flushed before
serialization”**. Therefore this run supplies the table/lookup measurements,
**not restart proof**. It exited 1. The earlier exploratory run had omitted
the supplied fixture projection and later encountered an unidentified
generator object; those were corrected probe setup defects, not production
findings. The armed run's retained output is `tmp/probe/step3-output.txt`.

## Operational boundary during scratch-root preparation

Scratch root: `tmp/step3-root`, cluster `step3`; no action targeted the main
root's `default` process. Initial read-only MCP health before the pause
answered for PID 41822. Later operator work was entirely root-qualified.

Starting a virgin root before publishing correctly refused “No `current-src`
branch is published.” Two subsequent ordinary root publications hit the
unchanged **180,000 ms** lifecycle bound, at **180,020** and **180,026 ms**.
Both had compiled **32,880 entities / 25,127 identities / 40,818 keyword facts**
before the observer stopped them. The first used a 3 GiB heap ceiling and the
second 6 GiB; increasing heap did not remove that boundary.

A virtual-thread-inclusive sample of the second owned publication JVM
(PID 21881) recorded the main thread waiting in `seon.db/transact-call`
(`db.clj:4167`) under `seon.fn/index!` (`fn.clj:3045`). The active writer
was in `write-owned-values-error` → `owners-of` (`db.clj:3575`, `:3596`),
through Datahike indexed datoms. This supports **component-owner traversal
at the sample**, not an attribution to registry/config compilation or a
claim of deadlock. It extends the existing writer-performance evidence in
`writer-hang-root-cause-2026-09-18.md`; no foreign source or lane was repaired.

For the restart experiment only, `tmp/probe/step3-hook.edn` copies the hook
config with `[:current-source :timeout-seconds]` set to **600**. The operator's
existing `SEON_HOOK_CONFIG` input selects it (`script/seon/fresh_operator.clj:273`).
The shared config and production observer bounds remain unchanged. Any
successful setup with this bound is **not proof of the PRD's publication
performance target**. This is a declared research acquisition bound after a
measured failure, not an unreported retry or a passing gate.


## File-backed publication and measured probes

The scratch publication succeeded with the declared 600-second observer bound:
**257,795 ms** initialization, **258,100 ms** lifecycle. It published commit
`6aaf2ec1-230f-555e-b1ac-a1968ac0c19b`, population digest
`e3353ecaed0737afbbf89b5f0ffbd9bcd52f7ca30f5446839c5b148b8d486289`.
The population phase reported **98,825 operations / 61,941 ms**. Initial
cluster acquisition then took **61,778 ms**, lifecycle **61,947 ms**, PID 25518.
Evidence: `tmp/probe/step3-root-init-bounded.txt` and
`tmp/probe/step3-root-start-ready.txt`.

The prototype installed only a native physical string/cardinality-one attribute
on the actual scratch cluster root, and stamped it with the published source
digest. This isolates Datahike persistence: it is **not** proof that the proposed
attribute has been admitted through Seon's canonical population or that the
production adoption path already implements step 3.

`tmp/probe/step3-root-first.edn` records the same seven survival outcomes as the
fixture table, now from a file-backed branch. The stamp transaction survived
`d/db`, `with`, temporal wrappers and a materialized exact fork. Native `d/db`
and `branch-as-db` had neither direct nor origin-carried projection metadata;
`seon.db/db` supplied it. Both missing-stamp and cold-holder refusals validated
against the acquired canonical `:seon.error/base` schema.

First acquisition: **8,767.39675 ms** reading/reconstructing the persisted
population, **177.017417 ms** compiling **3,228 roots**. The one-entry holder's
normalized compiled forms occupied **718,796 UTF-8 bytes**; both digests matched
the canonical fixture exactly. Five warmed 10,000-lookup trials took
`[242.332833 152.075250 154.227292 141.553250 140.148083]` ms:
**14.015–24.233 μs/read**, median **15.208 μs/read**. Acquisition count remained
one. These measurements include the stamp query, not only a map lookup.

`tmp/probe/step3-stale.edn` verifies a crucial counterexample: `d/with` changing
the stamp **retains the old projection metadata by identity**. The proposed
reader sees the new stamp and returns `unacquired-generation`; blindly trusting
carried metadata would reproduce risk A. Ordinary metadata survival alone is
therefore insufficient evidence of correct projection selection.

The four candidate override assertions all returned true: a direct overridden
scalar accepts its new value; an already compiled dependent still accepts the
old scalar and rejects the new one; recompiling that dependent accepts the new
value. This falsifies the assumption that `[compiled-candidate, compiled-base]`
composition alone handles replacement. The exact code appears below.

The separate persisted-candidate-row probe did **not** pass. MCP first exceeded
60 seconds, then returned a value-projection failure, then exceeded 30 seconds.
Saving the underlying exception established `OutOfMemoryError: Java heap space`
(`tmp/probe/step3-probe-exception.edn`) in the 6 GiB scratch JVM. The precise
allocating call is not established; attributing this to session reuse, Malli,
Datahike, or foreign edits would exceed the evidence. The earlier suggestion of
a session-specific timeout is withdrawn. A bounded candidate-value probe below
isolates branch/stamp/registry semantics without claiming durable declaration
admission. The failed helper remains in the embedded script for reproducibility;
it is not part of the passing results.

The publication's operator log emitted one **5,410,579-byte** result line.
That unreadable output and MCP's masked candidate failure are observation
defects recorded here under the assignment's single-note write boundary.
Subsequent inspection bounded each line rather than reproducing the payload.


## Actual JVM restart result and final scope

The operator downed PID **25518** and reported all recorded JVMs stopped and
its store flock free. A fresh `start step3` acquired the same file store in
PID **29150**, with **65,101 ms** start / **65,230 ms** lifecycle. The probe was
loaded afresh, giving an empty holder. Before acquisition, the stamp survived
and lookup returned the typed **unacquired-generation** refusal. The connection
metadata marker from the previous JVM was absent; the raw Datahike DB had no
carried projection. Evidence: `tmp/probe/step3-root-restart.txt` and
`tmp/probe/step3-root-restarted.edn`.

Explicit persisted-row acquisition took **8,437.389083 ms**, compilation
**315.200625 ms**. It reconstructed **3,228** compiled roots and **718,796**
normalized form bytes. The population stamp, canonical authored-form digest and
normalized compiled-form digest were exactly the pre-restart values recorded
above. After 100 ordinary reads the acquisition counter was still **1**.
This is a real process replacement, not `connect` returning a cached connection.
It verifies persisted rows as the restart source; it does not rely on rereading
packaged forms to select the sovereign population. Loaded host predicate Vars
remain an explicit executable binding dependency, as discussed above.

After restart, `candidate-value!` completed with all **six** assertions true:
an exact fork keeps the base stamp; a candidate `with` value carries the new
composite digest; the base branch is unchanged; a holder containing only the
base refuses the candidate; the candidate-first registry accepts the added
string and rejects a number. The four replacement/dependent assertions also
passed again. Combined MCP evaluation time: **75 ms**. Evidence:
`tmp/probe/step3-candidate-value.edn`. The candidate transaction here is
speculative `with` on a materialized branch; this is not evidence of persisted
candidate declaration admission. That larger failed probe remains a separately
named implementation-proof obligation.

No new owner ruling is required for the verified mechanism. Follow the ruled
stamp identity and existing origin-schema semantics. The implementation must
still verify durable candidate admission, stamp/data atomicity through Seon's
writer, generation-safe retention, and the adoption checklist above before
claiming step 3 complete. The research supplies the conversion inventory and
falsifies unsafe metadata/composite assumptions; it does not authorize skipping
those proofs. No regression was added or wired into any suite.

Foreign boundary: the working tree contained concurrent schema/render/test
edits. This lane did not edit, reset, reload, or message their owners. The scratch
publication used the observed shared tree, whose census bytes are recorded
above; it is not a HEAD-only cold gate. No shared-tree load failure required an
overlay run. The measured publication timeout/owner traversal and candidate OOM
are reported as observations, not attributed to those foreign changes. No
`bin/test` gate, worktree, or second overlapping owned JVM was used.

## Reproduction code and retained evidence

The two scripts below preserve the exact prototype in this committed note;
`tmp/probe/` is disposable and normally ignored by Git. The first script's
memory-to-file fork is a recorded failed experiment; the second script's
`candidate-branch!` is the recorded OOM experiment. Do not interpret their
presence as passing regressions. Passing entry points on the scratch cluster
were `first-run!`, then after down/start `restart-run!`, `candidate-value!`,
`candidate-probe`, and `changed-stamp-with`. These are bounded research helpers,
not production APIs or a proposed second owner of projection state.

Run the fixture script as one foreground `clojure -M:test tmp/probe/step3.clj`.
For the file-backed proof, publish and start only `tmp/step3-root` with the
scratch observer configuration described above, then use root-qualified JVM
MCP to `load-file` the helper and call those functions with
`(seon.operator/connection "step3")`. Save expected evidence before restart;
`restart-run!` reads that file. Finally down the same root and verify its JVM
has exited before deleting it. No call in this reproduction targets `default`.

### `tmp/probe/step3.clj`

```clojure
(require '[seon.test-support :as ts] '[seon.test.arm :as arm] '[seon.db :as db] '[seon.schema :as s]
         '[seon.id :as id] '[datahike.api :as d] '[malli.core :as m]
         '[malli.registry :as mr] '[clojure.walk :as walk] '[clojure.edn :as edn])
(defn emit [label value] (println label (pr-str value)) (flush))
(defn elapsed [f] (let [t (System/nanoTime) v (f)] [v (/ (- (System/nanoTime) t) 1e6)]))
(defn stamp [database]
  (let [xs (d/q '[:find ?e ?digest :where [?e :seon.source/digest ?digest]] (db/schema-database database))]
    (if (= 1 (count xs)) (second (first xs))
        {:seon.error/kind :probe/missing-or-ambiguous-stamp :probe/count (count xs)})))
(defn projection-for [holder database]
  (let [digest (stamp database)]
    (if (map? digest) digest
        (or (get @holder digest)
            {:seon.error/kind :probe/unacquired-generation :probe/digest digest}))))
(defn compiled [projection]
  (let [registry (:seon.schema.projection/registry projection)]
    (into {} (map (fn [k] [k (m/schema (s/compilable-form (get (:seon.schema.projection/forms projection) k) {}) {:registry registry})]))
          (keys (:seon.schema.projection/forms projection)))))
(defn portable [value]
  (let [symbols (into {} (for [n (sort-by ns-name (all-ns)) [sym v] (sort-by key (ns-interns n)) :when (and (bound? v) (or (fn? @v) (instance? clojure.test.check.generators.Generator @v)))]
                          [@v (symbol (str (ns-name n)) (str sym))]))]
    (walk/prewalk (fn [v] (cond (var? v) (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v))))
                                (or (fn? v) (instance? clojure.test.check.generators.Generator v)) (or (get symbols v) (throw (ex-info "Unidentified callable" {:probe/class (str (class v))})))
                                :else v)) value)))
(defn bytes-of [schemas]
  (s/canonical-data-string
    (portable (into {} (map (fn [[k v]] [k (m/form v)])) schemas))))
(emit :phase :fixture-start)
(try
 (s/call-with-projection (:seon.test.runner/projection (arm/initialize-contracts! "step3-probe" []))
  (fn [] (ts/with-database
  (fn [conn]
   (let [database (db/db conn) p (db/carried-projection database)
         root (d/q '[:find ?e . :where [?e :seon.source/digest]] database)
         digest (stamp database) holder (atom {}) t (:max-tx database)]
    (emit :fixture {:root root :digest digest :forms (count (:seon.schema.projection/forms p))})
    (emit :survival
      (into {} (for [[label value] [[:seon-db database] [:datahike-db (d/db conn)]
                                   [:history (d/history database)] [:as-of (d/as-of database t)]
                                   [:since (d/since database t)] [:with (:db-after (d/with database []))]]]
                 [label {:class (str (class value)) :metadata (boolean (:seon.schema/projection (meta value)))
                         :carried (identical? p (db/carried-projection value)) :stamp (stamp value)}])))
    (let [branch-db (d/branch-as-db conn (:branch (:config @conn)))]
      (try (emit :branch-as-db {:metadata (boolean (db/carried-projection branch-db)) :stamp (stamp branch-db)})
           (finally (d/release-materialized-db branch-db))))
    (emit :absence (projection-for holder (:db-after (d/with database [[:db/retract root :seon.source/digest digest]]))))
    (emit :cold-holder (projection-for holder database))
    (let [[schemas compile-ms] (elapsed #(compiled p))
          entry {:probe/registry (mr/fast-registry schemas) :probe/forms-count (count schemas)}
          original-bytes (bytes-of schemas)]
      (swap! holder assoc digest entry)
      (emit :compile {:ms compile-ms :entries (count schemas) :compiled-form-utf8-bytes (alength (.getBytes original-bytes "UTF-8"))
                      :compiled-form-digest (id/id original-bytes 64) :canonical-forms-digest (id/id (s/canonical-data-string (:seon.schema.projection/forms p)) 64) :holder-generations (count @holder)})
      (dotimes [_ 1000] (projection-for holder database))
      (emit :lookup-ms-per-10000 (vec (repeatedly 5 #(second (elapsed (fn [] (dotimes [_ 10000] (projection-for holder database))))))))
      (emit :phase :restart-reconstruction)
      (let [configuration (d/fork-database (:config @conn)
                           {:store {:backend :file :path "tmp/probe/step3-store"}})]
        (spit "tmp/probe/step3-restart.edn" (pr-str {:configuration configuration :compiled-form-digest (id/id original-bytes 64) :canonical-forms-digest (id/id (s/canonical-data-string (:seon.schema.projection/forms p)) 64)}))
        (emit :disk-copy :ready))
      (let [branch :step3-reopen configuration (assoc (:config @conn) :branch branch)]
        (d/branch! conn (:branch (:config @conn)) branch)
        (try
          (let [first-conn (d/connect configuration)]
            (alter-meta! (:wrapped-atom first-conn) assoc :probe/marker true)
            (d/release first-conn))
          (let [reopened (d/connect configuration)]
            (try
             (let [raw (d/db reopened)
                   [rebuilt rebuild-ms] (elapsed #(s/projection-from-database raw))
                   [schemas2 compile2-ms] (elapsed #(compiled rebuilt))
                   restored-bytes (bytes-of schemas2)]
               (emit :reopen {:connection-metadata (boolean (:probe/marker (meta reopened)))
                              :db-metadata (boolean (db/carried-projection raw)) :stamp (stamp raw)
                              :rebuild-ms rebuild-ms :compile-ms compile2-ms
                              :forms-equal (= (:seon.schema.projection/forms p) (:seon.schema.projection/forms rebuilt))
                              :compiled-bytes-equal (= original-bytes restored-bytes)
                              :compiled-form-digest (id/id restored-bytes 64)})
               (assert (= original-bytes restored-bytes) "Compiled forms differ after row acquisition")
               (reset! holder {})
               (emit :restart-missing (projection-for holder raw))
               (swap! holder assoc digest {:probe/registry (mr/fast-registry schemas2)})
               (emit :restart-acquired (boolean (:probe/registry (projection-for holder raw)))))
             (finally (d/release reopened))))
          (finally (d/delete-branch! conn branch))))
      (let [candidate-forms {:probe/candidate [:map [:probe/value :int]]}
            candidate-digest (id/digest 64 [digest (s/canonical-data-string candidate-forms)])
            candidate-registry (mr/composite-registry (mr/fast-registry candidate-forms) (:probe/registry entry))
            candidate-db (:db-after (d/with database [[:db/add root :seon.source/digest candidate-digest]]))]
        (emit :candidate-before-acquisition (projection-for holder candidate-db))
        (swap! holder assoc candidate-digest {:probe/registry candidate-registry})
        (emit :candidate {:digest candidate-digest :base-unchanged (= digest (stamp database))
                          :candidate-stamp (= candidate-digest (stamp candidate-db))
                          :accepts (m/validate :probe/candidate {:probe/value 1} {:registry candidate-registry})
                          :refuses (not (m/validate :probe/candidate {:probe/value "bad"} {:registry candidate-registry}))
                          :base-retained (identical? (mr/schema (:probe/registry entry) :seon.agent/id)
                                                    (mr/schema candidate-registry :seon.agent/id))}))))))
 (let [raw {:probe/x :int :probe/entity [:map [:probe/x :probe/x]]}
       base-raw (mr/composite-registry raw (m/default-schemas))
       base (mr/fast-registry (into {} (map (fn [[k f]] [k (m/schema f {:registry base-raw})])) raw))
       override (mr/composite-registry {:probe/x (m/schema :string)} base)
       repaired (mr/composite-registry
                  {:probe/x (m/schema :string)
                   :probe/entity (m/schema (:probe/entity raw) {:registry override})} base)]
   (emit :override {:direct-new (m/validate :probe/x "s" {:registry override})
                    :dependent-still-old (m/validate :probe/entity {:probe/x 1} {:registry override})
                    :dependent-rejects-new (not (m/validate :probe/entity {:probe/x "s"} {:registry override}))
                    :recompiled-accepts-new (m/validate :probe/entity {:probe/x "s"} {:registry repaired})}))
 (emit :result :passed)))
 (catch Throwable e (emit :failure {:message (ex-message e) :data (ex-data e)}) (.printStackTrace e) (System/exit 1))
 (finally (shutdown-agents)))

```

### `tmp/probe/step3-root-probe.clj`

```clojure
(ns step3.probe
  (:require [seon.db :as db] [seon.schema :as s] [seon.id :as id] [seon.error :as error]
            [datahike.api :as d] [malli.core :as m] [malli.registry :as mr]
            [clojure.walk :as walk] [clojure.edn :as edn]))

(def holder (atom {}))
(def acquisitions (atom 0))
(defn elapsed [f] (let [t (System/nanoTime) v (f)] [v (/ (- (System/nanoTime) t) 1e6)]))
(defn refusal [reason message offending]
  (error/diagnostic
    {:seon.error/at (java.util.Date.)
     :seon.error/layer :step3.probe/projection
     :seon.error/operation 'step3.probe/projection-for
     :seon.error/kind reason
     :seon.error/message message
     :seon.error/diagnostic-layer :projection-acquisition
     :seon.error/diagnostic-operation 'step3.probe/projection-for
     :seon.error/diagnostic-member :seon.cluster/projection-digest
     :seon.error/diagnostic-expected :one-acquired-population
     :seon.error/diagnostic-offending offending
     :seon.error/diagnostic-cause reason
     :seon.error/diagnostic-evidence offending}))
(defn stamp [database]
  (let [origin (db/schema-database database)
        xs (when (get (:schema origin) :seon.cluster/projection-digest)
             (d/q '[:find ?e ?digest :where [?e :seon.cluster/projection-digest ?digest]] origin))]
    (if (= 1 (count xs)) (second (first xs))
        (refusal :step3.probe/missing-or-ambiguous-stamp
                 "The database must carry exactly one population stamp."
                 {:step3.probe/count (count xs)}))))
(defn projection-for [database]
  (let [digest (stamp database)]
    (if (map? digest) digest
        (or (get @holder digest)
            (refusal :step3.probe/unacquired-generation
                     "Acquire the stamped population before reading."
                     {:step3.probe/digest digest})))))
(defn compiled [projection]
  (let [registry (:seon.schema.projection/registry projection)]
    (into {} (map (fn [[k form]]
                    [k (m/schema (s/compilable-form form {}) {:registry registry})]))
          (:seon.schema.projection/forms projection))))
(defn portable [value]
  (let [symbols (into {} (for [n (sort-by ns-name (all-ns))
                              [sym v] (sort-by key (ns-interns n))
                              :when (and (bound? v)
                                         (or (fn? @v) (instance? clojure.test.check.generators.Generator @v)))]
                          [@v (symbol (str (ns-name n)) (str sym))]))]
    (walk/prewalk
      (fn [v]
        (cond (var? v) (symbol (str (ns-name (:ns (meta v)))) (str (:name (meta v))))
              (or (fn? v) (instance? clojure.test.check.generators.Generator v))
              (or (get symbols v) (throw (ex-info "Unidentified callable" {:step3.probe/class (str (class v))})))
              :else v)) value)))
(defn schema-bytes [schemas]
  (s/canonical-data-string (portable (into {} (map (fn [[k v]] [k (m/form v)])) schemas))))
(defn acquire! [database]
  (let [digest (stamp database)]
    (if (map? digest) digest
      (or (get @holder digest)
          (let [[p row-ms] (elapsed #(s/projection-from-database (db/schema-database database)))
                _ (swap! acquisitions inc)
                [schemas compile-ms] (elapsed #(compiled p))
                bytes (schema-bytes schemas)
                entry {:step3.probe/registry (mr/fast-registry schemas)
                       :step3.probe/projection p
                       :step3.probe/evidence
                       {:step3.probe/digest digest
                        :step3.probe/forms (count schemas)
                        :step3.probe/row-acquisition-ms row-ms
                        :step3.probe/compile-ms compile-ms
                        :step3.probe/compiled-form-bytes (alength (.getBytes bytes "UTF-8"))
                        :step3.probe/compiled-form-digest (id/id bytes 64)
                        :step3.probe/canonical-form-digest
                        (id/id (s/canonical-data-string (:seon.schema.projection/forms p)) 64)}}]
            (swap! holder assoc digest entry)
            entry)))))
(defn summary [database]
  {:step3.probe/stamp (stamp database)
   :step3.probe/stored-commit-id (get-in (db/schema-database database) [:meta :datahike/commit-id])
   :step3.probe/direct-metadata (boolean (:seon.schema/projection (meta database)))
   :step3.probe/carried (boolean (db/carried-projection database))})
(defn first-run! [connection]
  (let [before (db/db connection)
        missing (projection-for before)
        root (d/q '[:find ?e . :where [?e :seon.cluster/name "step3"]] before)
        source-digest (d/q '[:find ?digest . :where [?e :seon.source/digest ?digest]] before)]
    (assert root "Scratch cluster root absent")
    (assert source-digest "Published source digest absent")
    (d/transact connection
      {:tx-data [{:db/ident :seon.cluster/projection-digest :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
                 [:db/add root :seon.cluster/projection-digest source-digest]]})
    (alter-meta! (:wrapped-atom connection) assoc :step3.probe/marker true)
    (let [database (db/db connection)
          cold (projection-for database)
          entry (acquire! database)
          evidence (:step3.probe/evidence entry)
          _ (assert evidence "Acquisition did not return a registry")
          _ (assert (m/validate (mr/schema (:step3.probe/registry entry) :seon.error/base) missing))
          _ (assert (m/validate (mr/schema (:step3.probe/registry entry) :seon.error/base) cold))
          _ (spit "tmp/probe/step3-root-expected.edn" (pr-str evidence))
          basis (:max-tx database)
          survival (into {} (for [[label value] [[:raw-db (d/db connection)] [:seon-db database]
                                               [:with (:db-after (d/with database []))]
                                               [:as-of (d/as-of database basis)] [:history (d/history database)]
                                               [:since (d/since database basis)]]]
                              [label (summary value)]))
          _ (dotimes [_ 1000] (projection-for database))
          times (vec (repeatedly 5 #(second (elapsed (fn [] (dotimes [_ 10000] (projection-for database)))))))]
      (d/branch! connection (:branch (:config @connection)) :step3-candidate)
      (let [branch-db (d/branch-as-db connection :step3-candidate)]
        (try
          (let [report {:step3.probe/missing missing :step3.probe/cold cold
                        :step3.probe/acquisition evidence :step3.probe/survival survival
                        :step3.probe/branch (summary branch-db)
                        :step3.probe/lookup-ms-per-10000 times
                        :step3.probe/acquisitions @acquisitions :step3.probe/holder-size (count @holder)}]
            (assert (= source-digest (stamp branch-db)))
            (assert (= 1 @acquisitions))
            report)
          (finally (d/release-materialized-db branch-db) (d/delete-branch! connection :step3-candidate)))))))
(defn restart-run! [connection]
  (let [raw (d/db connection)
        cold (projection-for raw)
        expected (edn/read-string (slurp "tmp/probe/step3-root-expected.edn"))
        entry (acquire! raw)
        evidence (:step3.probe/evidence entry)
        digest-keys [:step3.probe/digest :step3.probe/forms :step3.probe/compiled-form-bytes
                     :step3.probe/compiled-form-digest :step3.probe/canonical-form-digest]
        same (= (select-keys expected digest-keys) (select-keys evidence digest-keys))]
    (assert (= :step3.probe/unacquired-generation (:seon.error/kind cold)))
    (assert same "Persisted population does not reconstruct the same canonical compiled forms")
    (dotimes [_ 100] (projection-for raw))
    (assert (= 1 @acquisitions))
    {:step3.probe/raw (summary raw)
     :step3.probe/connection-marker (boolean (:step3.probe/marker (meta connection)))
     :step3.probe/cold cold :step3.probe/acquired evidence
     :step3.probe/digests-equal same :step3.probe/acquisitions @acquisitions}))
(defn candidate-probe []
  (let [forms {:step3.probe/x :int :step3.probe/entity [:map [:step3.probe/x :step3.probe/x]]}
        raw (mr/composite-registry forms (m/default-schemas))
        base (mr/fast-registry (into {} (map (fn [[k f]] [k (m/schema f {:registry raw})])) forms))
        override (mr/composite-registry {:step3.probe/x (m/schema :string)} base)
        repaired (mr/composite-registry
                    {:step3.probe/x (m/schema :string)
                     :step3.probe/entity (m/schema (:step3.probe/entity forms) {:registry (mr/composite-registry override (m/default-schemas))})}
                    base)
        report {:step3.probe/direct-new (m/validate :step3.probe/x "s" {:registry override})
                :step3.probe/dependent-still-old (m/validate :step3.probe/entity {:step3.probe/x 1} {:registry override})
                :step3.probe/dependent-rejects-new (not (m/validate :step3.probe/entity {:step3.probe/x "s"} {:registry override}))
                :step3.probe/recompiled-accepts-new (m/validate :step3.probe/entity {:step3.probe/x "s"} {:registry repaired})}]
    (assert (every? true? (vals report))) report))
(defn candidate-branch! [connection]
  (let [base-db (db/db connection)
        base-digest (stamp base-db)
        base-entry (acquire! base-db)
        candidate-forms {:step3.probe/addition :string}
        candidate-digest (id/digest 64 [base-digest (s/canonical-data-string candidate-forms)])
        candidate-registry (mr/composite-registry
                             (mr/fast-registry {:step3.probe/addition (m/schema :string)})
                             (:step3.probe/registry base-entry))
        config (assoc (:config @connection) :branch :step3-candidate)]
    (d/branch! connection (:branch (:config @connection)) :step3-candidate)
    (try
      (let [candidate-connection (d/connect config)]
        (try
          (let [root (d/q '[:find ?e . :where [?e :seon.cluster/name "step3"]] @candidate-connection)
                rows (mapv #(assoc % :seon.schema.admission/source :agent)
                           (s/canonical-schema-rows candidate-forms))]
            (d/transact candidate-connection
              {:tx-data (into [[:db/add root :seon.cluster/projection-digest candidate-digest]] rows)})
            (let [candidate-db @candidate-connection
                  refused (projection-for candidate-db)]
              (assert (= :step3.probe/unacquired-generation (:seon.error/kind refused)))
              (swap! holder assoc candidate-digest {:step3.probe/registry candidate-registry})
              (let [rebuilt (s/projection-from-database candidate-db)
                    report {:step3.probe/base-stamp (stamp (db/db connection))
                            :step3.probe/candidate-stamp (stamp candidate-db)
                            :step3.probe/before-acquisition refused
                            :step3.probe/new-schema-valid (m/validate :step3.probe/addition "yes" {:registry candidate-registry})
                            :step3.probe/new-schema-refuses (not (m/validate :step3.probe/addition 1 {:registry candidate-registry}))
                            :step3.probe/rows-reconstruct-candidate
                            (= :string (get (:seon.schema.projection/forms rebuilt) :step3.probe/addition))}]
                (assert (= base-digest (stamp (db/db connection))))
                (assert (= candidate-digest (stamp candidate-db)))
                (assert (:step3.probe/rows-reconstruct-candidate report))
                report)))
          (finally (d/release candidate-connection))))
      (finally (d/delete-branch! connection :step3-candidate)
               (swap! holder dissoc candidate-digest)))))
(defn changed-stamp-with [connection]
  (let [database (db/db connection)
        root (d/q '[:find ?e . :where [?e :seon.cluster/name "step3"]] database)
        digest (id/digest 64 [(stamp database) :step3.probe/unacquired-change])
        changed (:db-after (d/with database [[:db/add root :seon.cluster/projection-digest digest]]))
        refusal (projection-for changed)
        report {:step3.probe/old-metadata-survives
                (identical? (db/carried-projection database) (db/carried-projection changed))
                :step3.probe/new-stamp (= digest (stamp changed))
                :step3.probe/refusal refusal}]
    (assert (= :step3.probe/unacquired-generation (:seon.error/kind refusal))) report))

(defn candidate-value! [connection]
  (let [base (d/db connection)
        base-digest (stamp base)
        digest (id/digest 64 [base-digest {:step3.probe/addition :string}])
        root (d/q '[:find ?e . :where [?e :seon.cluster/name "step3"]] base)]
    (d/branch! connection (:branch (:config base)) :step3-candidate-value)
    (let [forked (d/branch-as-db connection :step3-candidate-value)]
      (try
        (let [candidate (:db-after (d/with forked [[:db/add root :seon.cluster/projection-digest digest]]))
              refusal (projection-for candidate)
              registry (mr/composite-registry
                        {:step3.probe/addition (m/schema :string)}
                        (:step3.probe/registry (projection-for base)))
              report {:step3.probe/fork-keeps-base (= base-digest (stamp forked))
                      :step3.probe/candidate-stamp (= digest (stamp candidate))
                      :step3.probe/base-unchanged (= base-digest (stamp (d/db connection)))
                      :step3.probe/absence-refuses (= :step3.probe/unacquired-generation (:seon.error/kind refusal))
                      :step3.probe/candidate-valid (m/validate :step3.probe/addition "yes" {:registry registry})
                      :step3.probe/candidate-refuses (not (m/validate :step3.probe/addition 1 {:registry registry}))}]
          (assert (every? true? (vals report))) report)
        (finally (d/release-materialized-db forked)
                 (d/delete-branch! connection :step3-candidate-value))))))

```

## Cleanup and evidence manifest

The final root-qualified `down` stopped PID 29150 in **1,969 ms** (lifecycle
**2,060 ms**) and confirmed the store flock free. Both owned process identities
were absent before cleanup. `tmp/step3-root` and the failed partial
`tmp/probe/step3-store` were deleted. No uncited disposable store remains.
Evidence: `tmp/probe/step3-root-down.txt`. The retained probe files below are
explicitly cited evidence; the thread samples support only the observations
stated above, not an inferred cause for the OOM.

| Retained file | Bytes | SHA-256 |
|---|---:|---|
| `tmp/probe/step3-candidate-threads-2.json` | 91883 | `667786449f8be05632bf20e6e21e0f9a65347c0183b246523479e90a101a56f2` |
| `tmp/probe/step3-candidate-threads.json` | 75291 | `6c3db3c684e53704c5364b78f194ac811e12654bb7a5f75778958af157442fbd` |
| `tmp/probe/step3-candidate-value.edn` | 304 | `f38fb762d3ca65cab9fb338d2114f2e7cd570627bed1d4b5579e72854ef1b944` |
| `tmp/probe/step3-enrich.py` | 1401 | `6e78cab71499e598abc870b18d3389f3c6f9cfbbbeb75f27b1a868315a79ae3b` |
| `tmp/probe/step3-hook.edn` | 571 | `806c6caad4f2db174db22fea8211876a6849f1601bad8ff5b92609cc807f2b96` |
| `tmp/probe/step3-inventory.json` | 40080 | `2860900ef9f99f2477ea0eb9ed362ca1f3bd4e5fc8f31fa2f3c39513215eafca` |
| `tmp/probe/step3-inventory.py` | 4504 | `abe49a7e9fbe681efcd740d55823f410d24606c02c33acd803eed71f3832cf94` |
| `tmp/probe/step3-output.txt` | 7746 | `c4acf4529d1399b6a5ea687a2d4c545a91f9b761e37dfab581e5e23d487a3a2d` |
| `tmp/probe/step3-probe-exception.edn` | 704 | `140462cb2025d2bccd5cd8a5d98e794a2ae880e0aef231ee36cbd2f72cf08c85` |
| `tmp/probe/step3-publication-threads.json` | 24030 | `e18cca29059f2dda8ad2475b64d2e55015fb086f0d20a8db8d64926e3042711c` |
| `tmp/probe/step3-root-down.txt` | 992 | `25e3f8175244160365bb0599a0235fb6c2c1a0ce7e819e794529f133694f66d6` |
| `tmp/probe/step3-root-expected.edn` | 368 | `9c5c5054f26d552c6c225935aba57158e6d3f672e3211bf3fb686fdf08bb3ab0` |
| `tmp/probe/step3-root-first.edn` | 3369 | `cddbfc657f5a4f8ff6b578ccad7aa03a7aa2aa71ad0ad84079dca345e7ec7c1a` |
| `tmp/probe/step3-root-init-bounded.txt` | 175041 | `e088dda0837c407d254f2db59e7d9887e51958eb65438a674906405b4b0b462e` |
| `tmp/probe/step3-root-init-retry.txt` | 3374 | `32f9898887b5776e783406313f0fa4b0be61b4e7fdee7e05b4f1823715cd622c` |
| `tmp/probe/step3-root-init.txt` | 3502 | `a69c51a909e878e18739fb52434fee31bc7307857962008dae1591d0843546b2` |
| `tmp/probe/step3-root-probe.clj` | 14051 | `e4894fdcd69c4d718b4029f23e5a4921e0cd8bb1e486b41737c419d9b1fd646e` |
| `tmp/probe/step3-root-restart.txt` | 1846 | `bda229338a4c732fcc50d2335b4dc2673db1a159d59bb44d878afacb08f5e5e7` |
| `tmp/probe/step3-root-restarted.edn` | 1436 | `810ed61dc43b58e2cc4af10658cc8dee52beff71fd3e3dc707ac96c52edf3a6e` |
| `tmp/probe/step3-root-start-ready.txt` | 1848 | `c91b4d7f60ae5875f48263ab42626005e6cae19117e6d5a9d38ac5e4a1977895` |
| `tmp/probe/step3-root-start.txt` | 2387 | `62f87a724a757a0fb90d92f06057bc3480530bd78a55bb01f02ab38e1ea5f7aa` |
| `tmp/probe/step3-seams-enriched.md` | 15067 | `cda52351b7b8a9562c9a2b8db770924df064a45a200f59aebb379c11f11aefa4` |
| `tmp/probe/step3-seams.md` | 8234 | `2c5a0e628d741e9452f430bd1d64b8a542fba6c85064fd1756c40bc3b818dab9` |
| `tmp/probe/step3-stale.edn` | 841 | `fb1f5b7bde62f776e3db988e124441c14506f94212fc71b48d29306bddd8cd0e` |
| `tmp/probe/step3.clj` | 8524 | `8b0a9d29009ae08b3d94e71963435b1775ff452d609160997eb00795e9379603` |
