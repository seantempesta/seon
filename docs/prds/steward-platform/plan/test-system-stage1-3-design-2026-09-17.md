---
type: plan
status: proposed implementation design
created: 2026-09-17
tags: [testing, datahike, sci, design]
---

# Test system stages 1–3: incremental testing after the reset

**Completion means stages 1–3, built once against the post-reset population.**
Budget **4–5 lane-days**, including integration proofs, after the prerequisites
below converge. Stage 2's loader/resolution and stage 3's admission/claim
transactions can be prepared before the reset; stage 1's graph reader cannot
land against today's ref edges. Do not build a ref/symbol compatibility path.
Recommended order: prepare shared run/member declarations and stage 2 now;
prepare stage 3's transactions against those declarations; after reset, land
stage 1, finish stage 2's actual-host proof, then switch both hosts to stage 3.
Stages 4–5 finish presentation and documentation, not incremental selection.

## Authority, observations, and boundaries

Read **AGENTS.md §0–§5 in full**; read these three authorities **end to end**:

- [Test-system PRD](test-system-is-the-database-prd-2026-09-17.md), including
  §0b, §0c and stages 1–4.
- [Stage-0 design](../research/test-system-stage0-design-2026-09-17.md).
- [Stage-0 review](../research/review-test-system-stage0-2026-09-17.md).

Also read the requested portions of
[execution model](../research/test-execution-model-2026-09-16.md) §3, §5,
§6 option A;
[symbol edges](../research/edges-are-symbols-plan-2026-09-16.md) §1–§3;
[reset publication order](reset-batch-2026-09-17.md#publication-order-and-ownership);
and [platform facts](../research/platform-tier-is-a-fact-2026-09-16.md),
including its later implementation section, end to end. The latter's opening
“blocked” is historical: commits `f54771e84` and `8d4b3689f` landed the facts
and bare namespace derivation. Do not repeat that work.

The PRD §0b supersedes stage 0's unresolved worker-custody question. The
cluster JVM is the primary host, **serial across all runs in that JVM** until
derived redefinition facts permit concurrency. Platform/destructive tests and
a checkout different from loaded code use isolated snapshots. Claims/results
belong to the explicitly selected authority cluster; worker body custody is
its isolated copy. No remote Datahike connection protocol, Flow graph, or
implicit `default` is introduced.

Source anchors below are at inspected HEAD
`0d3756246b4486ae424110ca6f770f008a29c03b`, obtained with `git show`, rather
than an assumption that working-tree line numbers are stable. At entry,
`src/seon/test/runner.clj`, `selection.clj`, `cache.clj`, `src/seon/sci/eval.clj`,
`program.cljc`, `cluster.clj`, `cluster/source.clj`, schema owners, and
`resources/seon/schemas/seon.test.edn`, `seon.fn.edn`, `seon.program.edn`,
`seon.ns.edn` were dirty under other work. Their tests were also dirty,
including `test/seon/test/selection_test.clj` and `test/seon/test_support.clj`.
`src/seon/test.clj`, `src/seon/fn.clj`, `dev_cache.clj`, `bin/test`, and
`seon.test.run.edn` were clean when inspected. This is a dated observation,
not an ownership reservation: recheck before editing. No foreign edit is
included in this commit.

`bin/seon status` observed default pid **41413**, alive, no orphan JVMs.
Exactly **one read-only JVM prepl evaluation**, 6 ms as reported by MCP,
observed basis **536871873**, **356** run entities and `:seon.fn/calls`
installed as **`:db.type/ref`**. This proves the reset has not converged in
that observed database, not that current source is broken. Repeatable form:

```clojure
(let [database (seon.db/db (seon.operator/connection "default"))]
  {:basis-t (seon.db/basis-t database)
   :edge-schema (seon.db/pull database
                  [:db/ident :db/valueType :db/index]
                  [:db/ident :seon.fn/calls])
   :runs (seon.db/q '[:find (count ?r) .
                     :where [?r :seon.test.run/id]] database)})
```

Applied the data-oriented-clojure, data-modeling, Datahike, REPL and
clojure-testing skills. No tests, test JVMs, explicit publications, transactions,
reloads, or production edits were performed. The explicit design-only rule
overrides generic gate instructions. This document is the landing evidence;
the code and regression plans below are **not executed proofs**. The Markdown
edit hook automatically queued publication; no further prepl check was made.
Its repository-wide lint reported foreign dependency-pin errors in
`docs/prds/context-generation/research/agents-md-audit-2026-09-15.md`;
that file was not changed and repository-wide lint is not claimed green.

### Dependency ledger and reset prerequisites

| Existing mechanism | Inspected owner; use in this design |
|---|---|
| Serial transaction decisions | `reference-code/datahike/src/datahike/db/transaction.cljc:1153` supplies the mid-transaction database to `:db.fn/call`; `writer.cljc:147` owns commit/abort. `runner.clj:2227`/`:2444` already use the single recording owner. |
| Temporal retractions | Datahike `db/transaction.cljc:998` sweeps incoming refs on retractEntity; `:813` retains retraction datoms. `seon.db/history`/`since` supply the change view. Historical membership must name a symbol, not depend on a surviving program ref. |
| Fixtures/reporting | `reference-code/clojure/src/clj/clojure/test.clj:710`/`:728` own test-var/test-vars, once/each fixtures and begin/end reports. `runner.clj:601` captures both JVM and SCI Vars with explicit custody. |
| SCI acquisition | `src/seon/sci/eval.clj:1443` acquire!, `:778` install-row!, `:1807` cluster-ctx, `:1864` fork-cluster-ctx; SCI `core.cljc` owns fork/resolve. No test-specific interpreter. |
| Classpath | `dev_cache.clj:483` already calls tools.build create-basis with `:aliases [:test]`, consuming ordered `:classpath-roots`; `deps.edn:135` declares that alias. |
| Liveness | `src/seon/cluster/process.clj:12` current-identity; `resources/seon/operator/state.clj:1055` census-observations; `script/seon/fresh_operator.clj:1015` consumes that census. Reuse exact pid/start-instant and observed exit, never claim age. |

Reset group **2** must supply qualified-symbol identities, explicitly indexed
symbol sets for **calls, references, reach**, required
`:seon.program/analyzed-source-digest`, and corresponding graph consumers.
Group **3** must supply actual identity retractions. Group **4** strengthens
run provenance and result declarations; coordinate the additions below with
that owner, retaining `branch` versus `tested-branch` semantics. Parent S1
must admit real agent deftests through the analyzer; parent S3 acquisition
must install overrides by provenance. Missing any prerequisite is a named
integration dependency, never permission to synthesize partial program rows.

## Stage 1 — one selection and recorded membership

**Owner: `seon.test/select`.** Keep `changed-since-green` (`test.clj:54`) as
the per-test diagnostic its docstring promises. `check` (`:834`) becomes an
adapter to selection, admission, and execution. Fold `identity-tests :497`,
`changed-reach :522`, `reaching :529`, and namespace membership into the one
selection derivation; retain public `reaching` as a thin query adapter.
`stale-in :549` remains a diagnostic if still called; it cannot choose a
second run basis. Delete unused private helpers with their final callers.

### Request, basis and result contracts

Add `:seon.test/selection-request` and `/selection-result` in
`resources/seon/schemas/seon.test.edn`. `select` accepts **one map**:

```clojure
{:seon.db/db database-value
 :seon.test.run/cluster cluster-ref
 ;; optional inputs:
 :seon.test/namespaces #{'seon.example-test}
 :seon.test/identities #{'seon.example-test/example}
 :seon.test.run/change-basis-t previous-tested-t
 :seon.test/include-long? false}
```

The database value is required and immutable, carrying its projection. The
cluster ref must resolve to the explicitly requested cluster in that value;
it is neither a connection nor a defaulted name. The CLI resolves its explicit
name once at the boundary; agent custody supplies that identity explicitly.
No-cluster refuses **before** attempting config or store lookup. Empty optional
sets mean no explicit additions. Namespaces contain symbols; identities contain
qualified **test** symbols. Explicit changed-function requests through public
`reaching` still use the same graph owner; do not overload a test identity to
mean both “run this” and “find its callers.”

Default change basis: the tested `:t` of the cluster's last **completed,
usable run for this selection scope**, ordered by its admission transaction,
not wall clock. A run's current `basis-t` always denotes the database tested;
`change-basis-t` is the earlier comparison point. Never subtract branch-local
`:t` values across different stores/branches. Imported snapshot evidence must
retain its original tested branch/commit; absence of a comparable lineage is
first-run, not an invented time comparison.

Scope matters: a named-only or platform-only run must not advance the bare
incremental baseline. Record requested namespace/identity sets and the request
policy (`:incremental`, `:named`, `:platform`, `:all`, `:full`) on the run as
input evidence. Bare means incremental. Names mean named scope; names select
all their eligible tests regardless of freshness. Explicit all/full/platform
requests retain their meaning and do not become silent incremental aliases.
Run defaults and eligibility are decided here, never in bash.

A previous red run is usable change history but **not discharge of its red or
unfinished obligations**. Union surviving unresolved members since the last
successful incremental coverage with new reach; preserve their original
selection reasons. Open runs do not advance the basis. A zero-member run
cannot turn previous red into green or establish the first green basis.
The cluster's last green basis is derived from its successful completed
incremental run facts, not a mutable latest pointer. This is the replacement
for `tmp/test-basis/green-basis.edn`: a fact **on that cluster's branch**, not
an automatic write to `:current-src`. The workaround inventory's historical
destination is superseded by E1.

Result:

```clojure
{:seon.test.run/basis-t tested-t
 :seon.test.run/change-basis-t previous-tested-t ; absent for first-run
 :seon.test.run/members
 [{:seon.test/sym 'seon.example-test/example
   :seon.test.member/reasons #{:reaches-changed :platform}}]}
```

Use stable symbol order for display, set semantics for stored membership.
Multiple reasons are retained: exactly `:platform`, `:reaches-changed`,
`:named`, `:first-run`. The selector returns a flat typed refusal on unknown
coverage, never a partially successful members vector. The empty vector is
successful only after validating population, analysis and basis.

Stage 1 refusals use `seon.error/diagnostic`, with operation, identity,
expected/observed value and branch/basis evidence: `:seon.test/cluster-required`
for omitted custody, `/cluster-unavailable` for failed explicit resolution,
`/cluster-mismatch` for the wrong database, `/invalid-basis` for a supplied
future or noncomparable basis, `/namespace-unresolved` for an explicit
namespace with no eligible test identities, `/fixture-excluded` for explicit
fixture material, `/population-unknown` for missing indexing evidence, and
`/coverage-unknown` for an unbounded schema change. All slash-only spellings
here retain the `seon.test` namespace. First-run is a successful outcome,
not a refusal. Unknown evidence never becomes an empty success.

**Platform/no-op policy, explicitly:** include platform members first when
there is changed work, an outstanding obligation, a first run, or an explicit
named/all/full/platform request. For an unchanged, previously green bare
request, return zero members, including zero platform members. This is the
small policy refinement needed to meet the owner's unchanged-run requirement;
always adding platform tests cannot meet it. Amend the PRD/AGENTS wording in
the implementation commit. After an ordinary edit, N means the exact union of
changed-reach tests and that declared platform tier, not a promise that every
platform test transitively calls the edited function. Output names both sets.

### Post-reset change algorithm

1. Validate installed edge types/indexes and analysis provenance. Derive bare
   namespaces from `:seon.ns/name` rows under the declared test source root,
   using the existing `bare-namespaces` derivation (`runner.clj:733`), then
   tests via their namespace refs. Include admitted agent tests outside file
   roots. Exclude `:seon.test/fixture` and fixture-observation material from
   ordinary gate membership. Explicit fixture names refuse rather than run
   deliberate failures as gate tests. Long declarations retain existing
   all/full and explicit-name policy, using stored facts. Namespace rows
   with zero test rows do not disappear: require analyzer/acquisition
   completeness; discovered unindexed macro tests are an indexing refusal,
   never a private loaded-Var escape list.
2. Compute `(db/since (db/history database) change-basis-t)`. Read **both
   additions and retractions** of `:seon.fn/source`, `/spec`, `/calls`,
   `/references`, and identity datoms `:seon.fn/sym`, `:seon.test/sym`.
   Include test source changes (`:seon.test/source`) and namespace source/
   binding changes, mapping the latter to that namespace's definition symbols.
   Join changed eids to identities in **history**, not only the current db.
   Identity retraction itself seeds the old symbol even if the entire row
   disappeared; delete/recreate with a new eid retains both observations.
   Test-only edits seed that test itself. Preserve existing declared schema
   dependency and file-uncertainty relations; schema changes with no proven
   bounded dependency relation refuse incomplete coverage, never zero tests.
3. Acquire one eid→symbol mapping for function **and test** identities, and
   the schema-declared genuine-ref/file relations **once per operation**.
   Convert endpoints of genuine refs at this boundary. Seed one frontier with
   the **union** of changed symbols. For each unseen symbol, query AVET for
   `:seon.fn/calls` and `:seon.fn/references` using the **symbol value**,
   map incoming eids once, add callers to the frontier, and collect test
   identities. Include seed tests themselves. One shared seen set handles
   diamonds, cycles and overlapping seeds; no per-symbol gate-set calls,
   recursive Datalog, per-edge pulls or persistent adjacency mirror.
4. Accrete this union operation at `seon.fn/gate-sets :1376` (request-map
   arity returning the union); preserve its existing per-seed output contract.
   The new map carries `:seon.db/db` and `:seon.fn/seeds` (qualified-symbol
   set); it returns a sorted vector of `:seon.test/sym` or a flat error.
   Declare that runtime-only request beside the existing gate-set contracts.
   Its existing positional implementation loops over `gate-set-in :1329`
   with a separate seen set per seed: merely calling that arity once does
   **not** implement the required union walk. Both arities share the acquired
   relation helper; selection calls the union arity once.
5. Union eligible named, platform, first-run and outstanding members. Record
   all applicable reasons. Read complete datoms for reach, not the default
   cardinality-many pull limit. `:seon.test/reach` is prior execution evidence,
   not the authority for today's reverse walk.

An analyzed empty calls set is legitimate only with the source digest.
Missing digest on any row needed to establish coverage produces
`:seon.test/analysis-unknown`, with the identity and tested basis. The reset's
required schema should prevent it; the selector must still refuse incomplete
imports instead of interpreting absence as analysis. A qualified unresolved
callee remains a traversable symbol: deleting B still selects tests of A
calling B. Include unresolved-edge evidence; resolution/execution can then
report the actual missing definition. A requested unknown seed with no row,
historical identity or incoming edge is `:seon.test/identity-unresolved`.
Analyzer unknown-namespace evidence follows the existing conservative file
relation, not a fabricated qualified symbol. After reset there are **no old
ref-valued reach rows**. Encountering one is
`:seon.test/edge-schema-mismatch`; no migration or dual reader.

### Inputs outside the program graph

Yes, dependency/config/launcher inputs still need content identity. Dropping
that evidence would make a deps.edn change look like no code change. Move
`selection/input-digests` responsibility to the existing cache/publication
owner (`dev_cache.clj:471`, `src/seon/test/cache.clj`), not into select.
Derive inputs as the snapshot inventory minus files covered by the program
publication; preserve the already-derived complement of graph roots, never
resurrect `widening-inputs` (removed by `6df6967b8`). Include dependency gitlink
identities from the basis; do not recurse through reference-code symlinks.

The publisher records the digest of the sorted path/content-digest map on
the cluster's source publication as `:seon.source/test-input-digest` and
copies that **observed input identity** onto each admitted run as
`:seon.test.run/input-digest`. Cache artifact fingerprints remain cache
concerns. A changed external-input digest invalidates baseline compatibility:
select the full eligible set with `:first-run`, explicitly rendered “first
run for these gate inputs.” This intentionally conservative rule also covers
removed inputs. It does not claim graph reach for dependencies the graph
does not describe. Missing inventory/provenance refuses
`:seon.test/input-evidence-unavailable`; never treat a missing digest as an
unchanged one. No path list reaches select; no file-basis artifact is read.

### Accretive fact model and admission

Read `resources/seon/schemas/seon.test.run.edn:1` in full: today's entity has
id/at/git-sha/program-digest/basis-t/branch/tested-branch, **no members**.
Keep those meanings. Add the following with the quoted text as docstrings.
Existing entity maps receive optional entries; new admission contracts require
the facts relevant to their phase. A many-valued attribute is a set; empty
membership is witnessed by selection-tx, never by an empty-set datom.

| Attribute; type | Docstring |
|---|---|
| `:seon.test.run/cluster`; ref | “Explicit authority cluster that owns this run and its results.” |
| `:seon.test.run/change-basis-t`; basis-t | “Earlier tested basis used to select changes in the same lineage; absent for first-run.” |
| `:seon.test.run/members`; component ref set | “Selected memberships admitted atomically before execution.” |
| `:seon.test.run/selection-tx`; tx ref | “Transaction admitting the complete selection, including zero members.” |
| `:seon.test.run/policy`; enum above | “Requested gate policy; an execution scope, never an entity discriminator.” |
| `:seon.test.run/namespaces`; symbol set | “Namespaces explicitly requested at admission; absence means none requested.” |
| `:seon.test.run/identities`; qualified-symbol set | “Test identities explicitly requested at admission.” |
| `:seon.test.run/include-long?`; boolean | “Explicit long-test inclusion decision for this request.” |
| `:seon.source/test-input-digest`, `:seon.test.run/input-digest`; source/digest | “Digest of publication inputs outside the program graph observed for this publication or run.” |
| `:seon.test.member/symbol`; qualified-symbol | “Selected test name as observed at admission; survives deletion of its program row.” |
| `:seon.test.member/reasons`; enum set | “Selection reasons asserted at admission; several may apply.” |
| `:seon.test.run/covered-by`; non-component ref set | “Existing run members accepted instead of duplicate execution at admission.” |

Create the member schema in `resources/seon/schemas/seon.test.member.edn`.
Normalize the selector's in-memory `:seon.test/sym` to member/symbol before
transacting: the former is a unique program identity and must never be
asserted on a member. Membership map requires symbol and nonempty reasons;
the parent owns it. Store component refs as
`[:set {:seon.db/component true} :seon.db/ref]`; ordinary ref sets omit
the component property. Reuse `:seon.db/basis-t` and `:seon.source/digest`.
Use parent's membership ref plus symbol to locate a member; **no component
identity attribute** is needed. This replaces stage 0's proposed member/id
and member/test ref before they ship, satisfying G5 and deletion law. Run
identity still uses `seon.id/id`; do not add another identity generator.

Extend `runner/record-tx :2227` with phase-specific admission through
`runner/admit-run` (transaction function), called by `commit-results! :2444`'s
existing writer route. Store run provenance, request, selection-tx and all
members in **one transaction before any body starts**. Validate the handed
selection's source commit/program digest, input digest and change basis at
the writer. A concurrent program update refuses `:seon.test/program-mismatch`;
the caller obtains a fresh immutable db and reselects under its original
deadline. Never overwrite the selection with a different snapshot in place.
For checkout gates, admission carries the explicit immutable tested snapshot
and branch provenance; that snapshot is not represented as the live branch's
current program. Comparisons use its publication lineage, not destination :t.

Stage 1 must store terminal counts on members through the existing result
writer as well: otherwise a “last completed run” is not derivable and stage 1
does not work independently. Stage 3 adds claims, not the first usable result.

**Regression exactly:**
`seon.test.runner-test/selection-is-one-function-on-both-hosts`, in
`test/seon/test/runner_test.clj`. Using the canonical database fixture and
real indexed rows, hand the identical immutable db/request to in-process
check admission and worker-request admission; compare normalized symbols,
reason sets and both bases. Include source/spec/reference edits, a diamond
with overlapping seeds, deleted callee and recreated identity, first-run,
named scope, fixture exclusion, missing cluster, missing analysis provenance,
external-input invalidation, and green-then-no-change zero membership.
Assert positive expected identities so two empty answers cannot pass. Use
actual graph facts, no mocked selector. Include a prior named run that cannot
hide an unrelated pending edit. Measure union traversal at several seed counts;
report results, do not assert the unproven “under 50 ms” estimate.

**Delete in this stage:** `runner/bulk-selection :2749`, reaching-selection,
requested-changed-paths and `record-green-basis! :2801`; fast's membership copy
(`src/seon/test/fast.clj:18`); `selection.clj` after relocating cache input
fingerprinting and migrating its callers; shell changed-path selection.
Update `test/seon/test/selection_test.clj` to exercise the new owner, deleting
tests of the retired artifact. Keep snapshot paths strictly snapshot inputs.

Estimate **1½–2 lane-days**. Owner sees selected identities, reasons, tested
and comparison bases before execution, including “no tests selected” on a
second unchanged green request. Stage 1 alone does not prevent two launchers
from duplicating execution; stage 3 supplies that guarantee.

## Stage 2 — resolution from the admitted identity

Change `test/resolve-test :603` into a public contracted map function taking
the tested database, test symbol, acquired SCI ctx, explicit custody, projection,
and shared loader value. Success returns the existing `:seon.test/var`
union of host and SCI Var; refusal is `:seon.error/value`. Validate that the
identity has source, namespace and analysis evidence before resolving.

Provenance wins over coordinates. A `:core` indexed row with `:seon.fn/file`
resolves its qualified Var under the supplied loader, checking `:test`
metadata. An `:agent` row, normally fileless, resolves its interpreted Var
from the acquired base; an agent override **with a retained file coordinate**
uses the same agent path. A missing file cannot alone establish agent
provenance; malformed rows refuse. No fallback from a failed override to a
host Var with the same spelling.

Acquire once for the tested program through `seon.sci.eval/cluster-ctx` →
`acquire!` → `install-row!`. That existing owner evaluates stored test source
after namespace bindings and functions, including dependencies and renamed
deftest; take `(sci/resolve ctx test-symbol)` from the resulting context.
The primary host receives its already acquired matching base from the cluster
environment. A snapshot worker acquires from its copied named-cluster facts,
not just its test files. Use `fork-cluster-ctx` when transferring a base into
the worker's explicit local custody. No per-resolution eval-string into a
shared ctx, no fresh test evaluator, no test source written to a temp file.
Assert matching acquired program identity before use; stale acquisition is
`:seon.test/program-mismatch`. Acquisition refusals must remain visible.

**One classpath derivation.** Make `dev_cache.clj:483` return the resolved
tools.build basis/ordered roots as a value in addition to its existing
artifact output. Resolve in the tool environment where tools.build already
exists. Pass the same value at boot/adoption to `test-loader :106` and at
snapshot launch to workers; rebase only repository-relative roots. Preserve
dependency cache ordering and alias JVM options. Delete the paths-only
deps.edn reader, namespace reload loop in `prepare-tests! :606`, and the
worker's independent `resolve-task-vars :1236` implementation. A JVM with an
incompatible already-loaded class refuses `:seon.test/classpath-incompatible`;
adding a URL cannot replace a class. Loading a namespace is permitted for a
selected core identity, but is no longer discovery or blanket reloading.

Both hosts call `test/run :396` / `run-owned :470` and `runner/run-var! :601`
for capture, arming and explicit custody. Accrete a selected-Var collection
entry at the same capture owner, returning per-member results, so stage 3's
namespace group invokes test-vars once. Preserve SCI interruption through the
existing SCI evaluation boundary when invoking an interpreted body; merely
calling its :test closure outside that boundary is not a deadline proof.
Prime the canonical fixture base before the per-body bound, reusing
`initialize-worker! :3084` and the preparation lane's value/projection.

No new durable test-kind or loader-kind facts. Runtime-only resolution
request/result schemas belong in `seon.test.edn`; the classpath value extends
the cache's existing result contract. New refusals:
`:seon.test/identity-unresolved`, `/provenance-unknown`,
`/classpath-unavailable`, `/classpath-incompatible`, `/program-mismatch`.
Retain the current runner `:seon.test.runner/not-runnable` kind for a present
Var without executable test metadata; do not invent an alternate spelling.

**Regression:** `agent-test-runs-in-a-worker`, in
`test/seon/test/runner_test.clj`. In the canonical fixture, use real SCI
evaluation/admission to install an agent deftest and dependency, with no file,
then call the worker's actual run function **in process**, handing worker
custody and the admitted run. Assert selection includes it, SCI resolution
returns its test Var, the body observes the handed cluster, counts land on
that run's member, and another fixture cluster is untouched. Repeat with an
agent override retaining a core file coordinate. Also load
`seon.test-runner-test` through the same in-process loader with the real
`:test` dependency basis. No hand-written program rows or fake SCI bindings.

Estimate **1 lane-day**, plus any parent S1/S3 gap reported separately.
Owner sees a newly authored test execute and record through the same worker
function as an indexed test, with its own cluster custody. This can be built
before reset after the relevant held files are released; it does not read
edge values. Its full post-reset regression is still required.

## Stage 3 — admission and claims at the writer

`runner/admit-run` becomes the reservation boundary. At its transaction db,
find admitted unfinished members for the **same cluster, tested program/input
digests, lineage and change basis**. Reserve the complement and write
covered-by refs in the same transaction. Reserve on admission, **not first
claim**, closing the race between two selections and worker startup. Reuse of
completed matching members when two requests were selected concurrently is
also permitted; require identical observation scope and provenance. Red
covered results stay red, unfinished covered results stay pending. Explicit
later named reruns use a new request basis and execute again.

Define `runner/claim-member` as one transaction function invoked by
`[:db.fn/call claim-member request]`. Request carries run id, worker process
ref, instant, host and original deadline; it does not supply a preselected
work list. Inside the writer:

1. Verify run selection evidence and process identity. Resolve covered-by
   dependencies to their owning runs; any launcher may help execute those
   outstanding obligations instead of creating duplicate members.
2. Choose a claimable namespace group in stable order; claim **all selected
   remaining members of that namespace** atomically. No new group entity.
   Platform groups precede bulk, and bulk is unavailable until platform
   members (including covered members) complete green. A platform red leaves
   bulk unexecuted and the run red, rather than pending forever.
3. For the primary host, refuse another group while **any** live in-process
   claim in this JVM is outstanding, across clusters and runs. This enforces
   §0c's with-redefs constraint. Worker JVMs execute one group at a time;
   different worker processes can claim disjoint groups.
4. Write worker, claimed-at and claim-tx together. Read accepted members from
   the transaction report/db-after, not the earlier selection. Only the
   holder of that exact claim can execute and complete it.

Namespace groups preserve once fixtures; each fixtures still run per Var.
`test-ns-hook` cannot silently run unselected bodies. Keep the existing
full-namespace requirement; a partial hook namespace request refuses
`:seon.test/namespace-hook-requires-complete-selection`, naming the namespace
and missing members. Do not widen secretly after admission.

Add to the member schema (optional on stored maps; completion/claim contracts
enforce the appropriate combinations):

| Attribute; type | Docstring |
|---|---|
| `worker`; process ref | “Process record owning the current execution claim.” |
| `claimed-at`; inst | “Instant accepted by the writer for this claim; never a lease expiry.” |
| `claim-tx`; tx ref | “Transaction accepting this claim; completion must name this exact transaction.” |
| `completed-tx`; tx ref | “Transaction accepting terminal execution evidence for this member.” |
| `pass-count`, `fail-count`, `error-count`; nonnegative ints | “Observed clojure.test outcome count for this member's accepted execution.” |
| `began?`, `ended?`; booleans | “Whether the corresponding test-var event was observed in the accepted execution.” |
| `failures`; non-component report ref set | “Signature-keyed failing reports observed in this execution; no pass report entities.” |
| `error`; error ref | “Boundary failure preventing normal member completion, when present.” |

All names in that table have prefix `:seon.test.member/`. Counts and event
presence enter in stage 1; claim fields enter in stage 3. Extend existing
`:seon.db.process` with `pid` and `start-instant` using the existing boot
types and docstrings “Operating-system identity observed for this process
record; the two fields identify one generation.” Reuse the process id owner.
Do not invent a per-executor process that makes concurrent threads look like
independent JVMs.

Apply the stage-0 review amendment exactly: pass/fail/error counts on members;
report entities **only for fail/error**, deduplicated by the existing signature
and `seon.id/id`. Declare `:seon.test.report/id` as a unique nonempty string,
with docstring “Identity of immutable failing report content, derived from
test symbol and report signature”; declare `/symbol` as qualified-symbol,
“Test name observed for this report, retained after program deletion.”
`seon.test.report/report` requires those two fields and reuses the existing
failure type/signature and payload attributes by reference. Its identity is
`(seon.id/id [test-symbol signature])`; verify identical payload on upsert.
Use the existing failure-signature computation over the complete captured
claim. A different payload with the same signature refuses an immutable
report conflict (`:seon.test/report-conflict`), never overwrites history.
No pass or begin/end report rows.
Immutable report payload is shared by non-component refs; never attach one
shared component to several members. This replaces stage 0's ordinal event
entity proposal and does not reinterpret the mutable current failure site's
`:seon.test.failure/id` or required test ref. Migrate result readers to these
member reports; coordinate with reset group 4 rather than implement another
result writer. Identical completion
replay writes zero changed evidence datoms; changed replay refuses
`:seon.test.run/immutable`. Derived run verdict/counts are queries, never
stored status/running-total/green fields.

Completion uses the existing `record-tx`/`commit-results!` owner, verifying
worker **and claim-tx** at the writer before recording counts, failures and
completed-tx. A late completion after reclaim is
`:seon.test/claim-replaced`. A missing/deleted subject cannot cause the writer
to mint a program row; retain the member's symbol and record an unresolved
execution outcome through the same boundary. This preserves historical run
evidence without reviving a deleted test.

### Death, contention, and bounds

Use the existing operator process-record census or observed child exit. A
proven-dead exact pid/start-instant may be supplied to the transaction because
that process cannot resurrect; the writer still compares it to the **current**
claim. A mere timeout, absent advertisement or unknown observation does not
authorize reclaim. The existing boolean `cluster.process/live?` catches
lookup failures as false (`src/seon/cluster/process.clj:34`); **do not treat
that convenience result alone as proven death**. At the existing census seam,
retain observation errors as typed unknown and hand confirmed exit evidence
to claim-member. This is an accretive observation result, not a second census.

Bound expiry records its error but does not release in-process serialization
until the body terminates or process death is confirmed. A timed-out JVM body
can keep with-redefs active. SCI uses its uncatchable interrupt; isolated
workers retain bounded retirement and exact process-tree exit checks.

Claim contention returns `:seon.test/claim-conflict` with actual claim owner;
worker asks the same function for available work under the original deadline.
No available work is a distinct successful result: derive completed versus
blocked from the run; listen before rereading, then await changes under the
event backstop. Do not poll or restart a deadline. Unknown process evidence
returns `:seon.test/process-state-unknown`; bound failure remains the existing
worker-exchange diagnostic with worker/event/deadline evidence.

Guarantee: **one live accepted execution claim per member**, including across
launchers. Death after a body effect but before recorded completion can cause
re-execution; this is not exactly-once external effects. Recovery reclaims
the existing member, never duplicates it in another run.

### Launcher disposition at this HEAD

The current coordinator already has a dynamic queue (`run-task-pool! :3296`),
not a static namespace partition. Delete that invocation-local queue and its
independent task assignment, including split-resolved fallback; do not claim
to remove a static scheduler that is not present. Retain readiness, diagnostics,
arming, exact exits and process bounds. Worker work arguments become only
**(run-id, index)**; explicit authority transport, snapshot, classpath and
custody remain environment values, not a hidden namespace work list.

| `bin/test` current lines | Stage/disposition |
|---|---|
| 1–78 | Keep frozen launcher bytes, cwd/Java and preflight; rewrite usage around explicit cluster. |
| 79–265 | Parse only. Delete shell mode policy, default destination/refusal, confirmation-to-namespace derivation. Pass request data to select. |
| 266–435 | Keep run-root lifecycle, bounded cleanup and child launch/await; raw diagnostics are not the verdict. |
| 436–482 | Keep slot/traps/watchdog; formatted phase lines remain diagnostics until stage 4. |
| 483–570 | Keep HEAD-plus-owned-paths snapshot and links; path list never selects tests. |
| 571–585 | Keep bounded slot. Fast becomes the same request with in-process host, no independent selection/execution path. |
| 586–658 | Keep cache preparation; use the shared classpath value; delete old-classpath fallback in stage 2. |
| 659–675 | Bare namespace find is already gone. Remove the shell's empty-set policy; shared selector owns it. |
| 676–701 | Delete processors/2 and namespace-count arithmetic. Runner derives launch count from declared worker bound and admitted claimable isolated groups. Zero work means zero worker JVMs. |
| 702–742 | Materialize only requested worker roots; delete result destination inference and changed-paths file. |
| 743–767 | Keep publication, but use the preparation lane's nearest compatible base plus changed-files path; no full rebuild merely on digest miss. Pass its immutable facts/provenance to admission. |
| 768–788 | Launch N workers with run-id/index after admission. Eliminate coordinator namespace/mode/count task arguments. |
| 789–end | Reap, query run verdict, exit from that query, release slot, retain own diagnostics on failure. Authority unavailable is nonzero unknown. |

Publication precedes selection for checkout gates. Select/admit in the holding
JVM through the existing bounded transport; never serialize a database value
through prepl. Read members back for the announcement. An unchanged loaded
cluster request can avoid worker snapshot preparation entirely. The slot bound
stays a machine load bound; it is not the no-duplicate mechanism.

Move `runner/worker-count :2814` to the admitted request/config decision and
delete bash's copy. Delete `print-final-tally! :3582`, tally arithmetic in
`finish-run! :3681`, and fast's exit-from-counters when their callers migrate.
Stage 3 needs the shared run-verdict query for correct exit. Minimal
`render-run` may land with that query; stage 4 still owns final rendering and
byte-for-byte presentation regression. Do not leave a second tally until then.

**Regression: `no-double-execution`**, in `test/seon/test/runner_test.clj`.
Canonical fixture, real writer and bounded events: admit overlapping requests
concurrently with the same cluster/digest/basis. Assert one admitted member
per shared symbol and second membership exactly the complement, with covered-by
refs for every skipped symbol. Hold the first execution at an event, attempt
the second claim and independently count body entries: one. Test disjoint
namespaces, platform blocking, zero-work completion and red covered evidence.
For recovery use an owned short-lived process record and observed exit (not
a mocked liveness predicate); reclaim its member, reject old claim completion,
and verify new completion plus identical replay. A separate case proves two
in-process requests in the same JVM never overlap, even on different clusters.
Every wait uses the fixture's event backstop; no sleeps or unbounded retries.

Estimate **1½–2 lane-days**. Owner sees who claimed each selected test and
which concurrent run covers it. Two launchers no longer run the same live
obligation twice. An unchanged second bare invocation after green executes
zero bodies and starts zero worker JVMs; a concurrent covered run waits for
the evidence it depends on instead of printing a premature green.

## Implementation handoff and acceptance

Stage 1 owns `src/seon/test.clj`, selection retirement, union gate-sets seam,
runner admission, cache input handoff, run/member/request declarations and
selector regressions. Stage 2 owns resolution/loader, shared SCI acquisition
integration, dev_cache classpath output, worker resolution/capture adapters and
its regressions. Stage 3 owns claim/completion, existing process census error
evidence, launcher simplification and scheduling regressions. These overlap:
use sequential coherent commits or explicitly released seams, not concurrent
edits to runner/test schemas. Preserve preparation-lane improvements.

Before reset, stage 2 and the **pure admission/claim transaction mechanisms**
can be built and verified independently of calls/reach types once shared
declarations and held paths are released. Stage 3's end-to-end scheduling
switch needs stage 1 membership and stage 2 execution; claiming from temporary
hand-made queues now would build it twice. Stage 1 should be designed now,
implemented only on reset groups 2–3. Stage 2 must not repair an old evaluator
that parent S3 replaces. No new schema migration is required by these
accretive stage additions; the required edge/result changes belong to reset.

After each implementation stage, iterate with the canonical armed fixture,
then gate HEAD-plus-owned-paths on its affected namespaces plus platform.
Name the exact snapshot, run id and tested basis. Prove the live observable
through the actual in-process host and, for the isolated tier, a real worker
snapshot; the in-process worker-path regression alone is not the cold proof.
Record measured selection/preparation/execution separately. No under-50-ms
promise: the earlier 13-seed measurement was 6.2 s, and the union operation
must be measured after its reset dependencies land.

Final acceptance sequence: first run establishes full eligible evidence;
edit one ordinary definition; the command names exactly the selected N tests
with reasons and runs only them (platform policy included); repeat unchanged
and observe zero executions; author a fileless SCI test and run it through
the same owner; overlap two launchers and observe complementary memberships;
terminate an owned worker and observe reclaim with rejected stale completion.
Dependency-input changes deliberately request a full new compatible baseline.
Unknown analysis, missing cluster, incompatible schema, unavailable liveness,
unrecorded execution and prior red all remain visibly non-green.
