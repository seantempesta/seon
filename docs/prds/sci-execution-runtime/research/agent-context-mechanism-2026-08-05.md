---
type: research
status: active
tags: [research, agent, context, sci, session-curation]
---

# Agent context: one mechanism for base, initial, and repaired context

Date: 2026-08-05

This report is design only. It makes no production change.

## Reading and dependency ledger

I read every named source and plan document end to end: `src/seon/bootstrap.clj`,
`resources/seon/bootstrap.edn`, `src/seon/cluster/run.clj`,
`src/seon/cluster/curate.clj`, `src/seon/render/transcript.clj`,
`src/seon/render/walk.clj`, `src/seon/cluster/prompt.clj`,
`docs/prds/sci-execution-runtime/plan/session-curation-prd-2026-08-04.md`,
the complete `docs/prds/sci-execution-runtime/plan/README.md` including
“Rulings 2026-08-04,” `context-mvp-2026-07-31.md`,
`repl-session-context-2026-08-01.md`, and
`bootstrap-vector-design-2026-08-01.md`. I also read the current working edge,
the context and agent-runtime architecture targets, and the complete repository
orientation before making the design judgment
(`docs/prds/sci-execution-runtime/plan/unsettled.md:7-37`;
`docs/seon/architecture/context.md:1-468`;
`docs/seon/architecture/agent-runtime.md:1-301`;
`docs/TRANSFER_PROMPT.md:1-597`).

The exact dependencies are the pinned generation-aware SCI fork, Datahike's
branch-at-commit behavior, and Seon's existing run transaction functions. SCI
states that a fork isolates new and redefined Vars from its parent
(`reference-code/sci/src/sci/core.cljc:331-337`). Curation already forks the
database at the original opening commit and builds an acquired SCI context on
that branch (`src/seon/cluster/curate.clj:148-192`). Ordered plans are already
represented as component refs plus explicit ordinals because a Datahike
cardinality-many value is not an ordered vector
(`resources/seon/schemas/seon.bootstrap.plan.edn:1-15`;
`src/seon/bootstrap.clj:175-205`). The first-party idiom to retain is
`system-run-tx`, which composes the ordinary open, claim, and plan transitions
instead of inventing a replay or bootstrap executor
(`src/seon/cluster/run.clj:398-535`).

## Findings

### 1. How initial forms work today

The “initial forms key” is not presently an agent key. It is the cluster's
single `:seon.cluster/bootstrap-plan` ref, whose target is the globally
identified `:seon.bootstrap.plan/id :default` entity. That plan owns a
cardinality-many component set of form rows; order is recovered from each
row's `:seon.cluster.run.form/ordinal`. The plan also stores a digest of the
packaged raw form maps (`resources/seon/schemas/seon.cluster.edn:1-17`;
`resources/seon/schemas/seon.bootstrap.plan.edn:1-15`;
`resources/seon/schemas/seon.bootstrap.plan.form.edn:1-13`;
`src/seon/bootstrap.clj:11-13,141-173`).

The shipped value is `resources/seon/bootstrap.edn`: thirteen ordered form
maps, beginning with `(help)`, then an `in-ns`, discovery queries, a deliberate
contract refusal and repair, calls, and a persistence query. The first form's
extra `/context` string is the text printed by the `help` macro; it is not a
second prompt injection (`resources/seon/bootstrap.edn:1-75`;
`src/seon/bootstrap.clj:87-128`). `populate-source!` installs the plan into the
published source database, and every cluster entity is then converged to that
same `:default` plan ref (`src/seon/cluster.clj:747-783,1177-1259`).

At agent creation, `ensure-entity-call` first derives the namespace and agent
rows and, in the same transaction, appends `bootstrap/seed-tx`. Existing agents
are returned untouched. The seed therefore happens exactly once, only on the
absent-agent branch, and creation is incomplete unless the deterministic
`bootstrap:<agent-id>` run exists (`src/seon/cluster.clj:1262-1337`;
`src/seon/cluster/agent.clj:90-105`).

`seed-tx` reads only the owning cluster's plan. It sorts and validates
contiguous ordinals, replaces every literal `{{seon.ns/name}}` token with the
new agent's namespace, and maps `:agent` to that namespace and `:user` to
`user`. It also installs the namespace requires/refers for `help`, `dir`, and
`doc` (`src/seon/bootstrap.clj:81-85,175-245,254-300`). The current plan digest,
however, hashes the cluster's raw ordered query rows before namespace-token
resolution, so two agents using that cluster necessarily receive the same
digest even though the frozen sources contain agent-specific namespace text
(`src/seon/bootstrap.clj:207-252`).

The seed is already an ordinary system-authored run. `system-run-tx` opens the
run, records its opening commit, claims it, and freezes the supplied sources
and digest through the ordinary plan transition. `plan-call` materializes the
ordered run-form entities and their namespace refs under the normal custody and
single-plan fences (`src/seon/cluster/run.clj:398-488,503-535`). Because the run
already has `/plan-digest`, work derives resume/fold rather than a model call;
the bootstrap-vector design correctly identified this as “a run whose plan the
system wrote” (`docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md:31-70`).

Each evaluation then produces the same durable receipt and terminal facts as
any other run. Contracted declarations and session-image rows commit at that
same terminal boundary; the committed program row is installed into the
current SCI context only after the transaction succeeds
(`src/seon/cluster/loop.clj:1641-1661`). The transcript reaches those receipts
by joining the agent to its runs. Thus the preparation becomes prompt context
because it is history, not because the bootstrap resource is prepended to the
prompt (`src/seon/render/transcript.clj:105-197`;
`src/seon/cluster/prompt.clj:66-94`).

The current transcript nevertheless has bootstrap-only presentation logic.
Its shared `active-run` rule correctly removes any run superseded by another,
but also classifies pinning by equality with the reconstructed deterministic
bootstrap run id. Recent receipts explicitly exclude that id; pinned receipts
select it; the budget floor always retains its full receipt prefix
(`src/seon/render/transcript.clj:84-90,170-197,668-725`). Commit `c508d848c`
folded earlier duplicated exclusions into the one rule, but did not remove the
bootstrap identity convention (`docs/prds/sci-execution-runtime/plan/session-curation-prd-2026-08-04.md:56-66,209-218`).

Today, therefore:

- the declaration is per cluster, while the resolved source text, run, forms,
  receipts, namespace mutations, and durable declarations are per agent
  (`src/seon/bootstrap.clj:175-300`);
- a new agent cannot select forms different from its cluster's plan because
  the agent schema and creation request have no such ref
  (`resources/seon/schemas/seon.cluster.agent.edn:1-14,48-63`);
- an existing agent's initial declaration cannot be changed because no agent
  declaration exists and `ensure-entity-call` deliberately does nothing for an
  existing identity (`src/seon/cluster.clj:1274-1286`);
- preparation cannot be re-run through the bootstrap API because its run id is
  deterministic and run/form/receipt identities are single-use
  (`src/seon/bootstrap.clj:130-135`;
  `src/seon/cluster/run.clj:537-542`); and
- changing the cluster plan affects only agents created afterward. Existing
  agents retain the exact sources and receipts already frozen on their run
  (`test/seon/bootstrap_test.clj:157-225`).

### 2. What is already one mechanism

There are two distinct meanings of “context” and they must not be collapsed.
The **agent context** is prompt text: one bounded walk rooted at the agent over
one immutable database value. Explicit forward and reverse refs discover
facts, the transcript renderer projects active run history, and `prompt`
acquires those exact bytes (`src/seon/render/walk.clj:1-38,58-137`;
`src/seon/cluster/prompt.clj:66-94`). The **SCI base `ctx`** is executable
cluster program state acquired from program-graph and session-image facts;
forking applies to this `ctx`, not to prompt text
(`src/seon/sci/eval.clj:1271-1361`).

With that distinction, the requested three ideas already mostly compose as one
session mechanism:

1. Cluster base: the prompt walk derives current visible program/database
   facts, while the SCI base acquires the executable program graph. The ruled
   target gives each run a fresh fork of that acquired base
   (`docs/seon/architecture/context.md:7-25,243-271`;
   `docs/prds/sci-execution-runtime/plan/README.md:381-397`).
2. Initial forms: a system-authored run freezes and evaluates an ordered source
   vector, leaving ordinary forms and receipts in history
   (`src/seon/bootstrap.clj:254-300`;
   `src/seon/cluster/run.clj:503-535`).
3. Controlled or repaired history: proof mechanically executes an editor's
   revision through the same `system-run-tx`; adoption creates another such
   run, copies the proved receipts, and connects it to the old run(s) through
   `:seon.cluster.run/supersedes`. The one active-runs projection hides the
   replaced future without deleting it
   (`src/seon/cluster/curate.clj:148-251,301-350`;
   `src/seon/render/transcript.clj:84-90`).

Commit `dbcacc91b` therefore landed the decisive unification: a system-authored
run is usable for any existing agent, not only creation. Curation is not a
second way to put forms into a session; it is the second caller of that run
constructor, plus proof and supersession
(`docs/prds/sci-execution-runtime/plan/session-curation-prd-2026-08-04.md:68-76,219-225`).

The one missing contract is a generic **initial-forms declaration and
resolver** at both cluster and agent specificity. It is not another executor,
context store, or renderer. Once resolution supplies an ordered source vector,
the existing system-run path owns execution and the active-runs projection
owns what remains visible.

### 3. Staleness and updates

Prompt freshness is landed: every model call requests the render from a frozen
database value, and the walk is a pure traversal of that value. A later model
call can therefore see newly committed run receipts, functions, schemas, tests,
messages, and other reachable facts without accumulating a process-local
prompt (`docs/seon/architecture/context.md:7-25`;
`src/seon/cluster/prompt.clj:66-94`). A provider call already in flight keeps
the exact captured prompt; it does not change underneath the request
(`docs/seon/architecture/context.md:454-458`).

Executable-context freshness is **ruled but not built**. Current boot creates
one acquired cluster SCI context once, before agent graphs arm, and current
evals mutate the supplied context (`src/seon/cluster.clj:1922-1945`;
`.agents/skills/data-oriented-clojure/references/program-state.md:27-37`). A
successful runtime declaration is installed into that live context after its
terminal transaction, but there is not yet a fresh run fork and run-boundary
reacquisition (`src/seon/cluster/loop.clj:1641-1661`;
`docs/prds/sci-execution-runtime/plan/unsettled.md:19-33`).

The ruled model is precise: a run records its opening commit, acquires the
cluster program graph at that boundary, and evaluates in a fresh
generation-aware `sci/fork`. Changes committed after that boundary do not
mutate the running fork. A later run acquires the accepted function, schema,
and test facts and forks the refreshed base. Cross-agent propagation is
definition → admission/quality gate → durable program fact → acquisition at
the next run boundary (`docs/prds/sci-execution-runtime/plan/README.md:381-397,469-479`;
`docs/seon/architecture/agent-runtime.md:7-14,147-169`).

There is a separate source-publication boundary. Editing files updates the
published `current-src` branch; an already-forked cluster is sovereign and is
not synchronized from it. Such a cluster sees new file-authored functions,
schemas, or tests only after an explicit destructive refork, whereas accepted
program changes committed inside that cluster enter its later run-boundary
acquisition. These are not competing freshness paths: one selects a cluster's
program lineage, the other acquires facts within that lineage
(`docs/TRANSFER_PROMPT.md:118-130`;
`src/seon/cluster.clj:1877-1894`).

## Design

### Invariant: most specific wins by wholesale replacement

Both scopes declare the same thing:

- `:seon.cluster/initial-forms` is the cluster's required default ref; and
- `:seon.cluster.agent/initial-forms` is an agent's optional override ref.

**Resolution invariant:** if the agent entity has an
`:seon.cluster.agent/initial-forms` datom, that declaration is the complete
resolved declaration. The cluster declaration is not read or appended. If the
agent datom is absent, resolve the cluster's declaration. A cluster without
that ref is invalid rather than a third fallback state. Agent presence wins
even when the selected declaration contains zero forms.

| Agent ref | Cluster ref | Resolved declaration |
|---|---|---|
| present, non-empty declaration | present | agent declaration only |
| present, empty declaration | present | empty agent declaration; no cluster forms |
| absent | present | cluster declaration |
| absent | absent | refusal: malformed cluster facts |

This is whole-vector replacement, not composition. An agent removes one
cluster form by pointing at an agent declaration that contains the desired
vector without that form; it suppresses all cluster preparation with an
explicit empty declaration. Absence means inheritance and empty means an
intentional override, so nil or a boolean “disabled” flag is unnecessary.

This is the recommended simplest rule. It is deterministic, queryable, and
makes exact control possible without merge order, duplicate handling,
subtraction markers, or a composition language. It gives up implicit layering:
an overridden agent does not automatically receive later additions to the
cluster default. A creator that wants both must materialize one explicit
ordered agent declaration containing the desired combined vector. That cost is
desirable because the resulting session is inspectable as facts rather than as
the outcome of hidden inheritance arithmetic.

### Facts

Replace the bootstrap plan family in place with one generic declaration
family:

```clojure
{:seon.initial-forms/id <stable identity>
 :seon.initial-forms/forms
 [{:seon.initial-form/ordinal 0
   :seon.initial-form/source "(help)"
   :seon.initial-form/ns-designation :agent}
  ...]}
```

The declaration's component collection may be empty. Non-empty declarations
must have exactly the contiguous ordinals `0..n-1`; the resolver sorts by the
ordinal and refuses duplicates or gaps. Explicit ordinals remain necessary
because Datahike cardinality-many is a set; source order must never depend on
entity ids or transaction order (`resources/seon/schemas/seon.bootstrap.plan.edn:1-4`;
`src/seon/bootstrap.clj:175-205`). The cluster and agent refs are
cardinality-one. An optional `:seon.cluster.run/initial-forms` ref records which
declaration produced an initial preparation run; it is provenance, not a run
kind and not a visibility flag.

Declarations are immutable values: changing initial forms creates a new
declaration and replaces the cluster or agent ref. It never edits a declaration
already named by a historical run. Retracting an agent's ref restores cluster
inheritance; replacing it changes future resolution; neither operation rewrites
past forms or receipts.

The shipped generic vector becomes an ordinary initialization row referenced
by the cluster default. A model-calibrated or editor-authored vector is another
entity in the same declaration family. There is no provider switch inside the
resolver and no alternate resource-loading path.

### Resolution, digest, and creation transaction

One pure `resolve-initial-forms` operation takes the transaction database
value, agent id, cluster id, and assigned namespace:

1. select the agent ref by attribute presence, otherwise the cluster ref;
2. query and ordinal-sort that declaration's component forms;
3. validate the contiguous ordinals;
4. replace the exact `{{seon.ns/name}}` token and resolve `:agent`/`:user`
   namespace designations exactly as today; and
5. return the declaration ref plus the exact `:seon.cluster.reply/sources`
   vector that will be passed to `system-run-tx`.

For a new agent, “agent ref presence” is read from the proposed creation row,
because the row is not yet in the transaction database value. For an existing
agent, it is read from the actual datom. A dangling selected ref or malformed
declaration refuses; it never falls back to the less-specific cluster ref.

The plan digest is SHA-256 over the **resolved ordered run-source data**—the
canonical vector of each form's final source string and final
`:seon.ns/name`—after selection, token substitution, and namespace
designation. It is not the digest stored on or computed from the unresolved
declaration. The exact same vector is supplied to `system-run-tx`, so the
digest and frozen form rows cannot disagree. Two agents with different
declarations, or with namespace-sensitive resolved sources, are honestly
distinguishable (`src/seon/bootstrap.clj:207-252`;
`src/seon/cluster/run.clj:464-488`).

Agent creation accepts the optional agent declaration ref as part of the
creation request and writes it on the agent row. The in-transaction creation
function resolves against the proposed agent row plus the existing cluster
facts, then appends exactly one `system-run-tx` and the run's declaration ref
to the same transaction. That preserves the current atomic guarantee: no graph
can observe a created agent without its resolved preparation run
(`src/seon/cluster.clj:1262-1304`). An explicit empty declaration still creates
a zero-form, digest-bearing system run, which closes without evaluation; this
records that preparation was intentionally suppressed rather than omitted by
failure.

Run identity must cease carrying bootstrap semantics. Creation may use a
stable request identity solely as the idempotency fence; re-preparation always
uses a new run id. Consumers find preparation through the run's declaration
ref and facts, never by parsing or reconstructing the id.

### Re-preparation and repair

Re-preparation resolves the agent's current most-specific declaration at the
request basis, opens a new system-authored run with a new id, and supersedes
the prior active initial-forms run in the same transaction. It never reopens or
re-executes the old run. The old declaration, sources, receipts, digest, and
supersession edge remain queryable. Repeating an unchanged vector is therefore
an honest new execution with the same plan digest and a distinct run identity.

An editor-controlled repair remains the already-ruled curation flow: the
editor returns an ordered revision; the system proves it on a fresh fork at
the original opening commit; adoption creates a system-authored run and
connects it to every replaced run through `/supersedes`. If the repaired span
includes preparation, its new active run replaces that history exactly like
any other span. No mutation of old receipts, context blob, or agent-local SCI
context is involved (`src/seon/cluster/curate.clj:148-251,301-350`).

The transcript's only membership predicate remains:

```clojure
run belongs to agent
AND no run supersedes it
```

Initial, re-preparation, ordinary model-authored, and adopted repair runs all
enter the same candidate history and the same token-fit policy. Initial forms
are not identified, excluded, or pinned by a magic run id. Their receipts are
durable and queryable even when the bounded transcript elides them. This is the
simplest rule and matches the target context ruling that there are no special
prompt assembly bands or pins (`docs/seon/architecture/context.md:338-364`).

### Flexibility proof

- **Per-agent preparation:** cluster points to declaration `G`; agent `A`
  carries `/initial-forms -> A1`; resolver selects `A1` wholesale and its
  system run records `run/initial-forms -> A1` plus the digest of `A1`'s
  resolved sources.
- **Re-preparation:** a new system run records the currently resolved
  declaration and digest and `/supersedes ->` the prior active preparation
  run; old facts remain forensic history.
- **Editor-controlled exact context:** an adopted proof run contains the
  editor's exact ordered revision and `/supersedes ->` every active run being
  replaced; active-run derivation exposes only the revision.
- **Per-model calibrated vectors:** one declaration entity per calibrated
  vector; creation selects the desired entity on the agent, while agents with
  no override inherit the generic cluster declaration. The vector design
  already ruled different source vectors as data, not different mechanics
  (`docs/prds/sci-execution-runtime/plan/bootstrap-vector-design-2026-08-01.md:58-70`;
  `docs/prds/sci-execution-runtime/plan/repl-session-context-2026-08-01.md:156-169`).

## Deletion list

The implementation wave should cut the complete old shape after the listed
replacement fact or consumer has landed. Git is the archive; none of these
paths merits compatibility code.

| Delete | Why it is old | Safe only after |
|---|---|---|
| `:seon.cluster/bootstrap-plan`, `:seon.bootstrap.plan/{id,digest,forms}`, `:seon.bootstrap.plan.form/context`, and their schemas/render metadata | cluster-only declaration, stored unresolved digest, and bootstrap-specific presentation | generic initial-forms declaration schemas, cluster+agent refs, initialization rows, generic declaration renders, and source-branch population are admitted (`resources/seon/schemas/seon.cluster.edn:1-12`; `resources/seon/schemas/seon.bootstrap.plan.edn:1-15`; `resources/seon/schemas/seon.bootstrap.plan.form.edn:1-13`) |
| `resources/seon/bootstrap.edn` as a privileged runtime resource and `bootstrap/packaged-forms`, `population-tx`, `ordered-plan-rows`, `ordered-sources`, `agent-sources`, and old `plan-digest` | a second file-to-session authority and cluster-only resolver | shipped vector is populated as ordinary `:seon.initial-forms` rows and the one resolver returns final sources+digest (`src/seon/bootstrap.clj:81-113,137-252`; `src/seon/cluster.clj:747-783`) |
| `bootstrap/run-id`, `bootstrap/seed-tx`, and `ensure-entity-call`'s direct bootstrap call | bootstrap semantics encoded in identity and a special constructor wrapper | creation request carries optional agent ref; atomic resolver calls `system-run-tx`; preparation run carries declaration provenance; creation/re-preparation idempotency tests use facts rather than id text (`src/seon/bootstrap.clj:130-135,254-300`; `src/seon/cluster.clj:1262-1337`) |
| cluster and creation result prose saying “bootstrap plan/run” | render output exposes deleted vocabulary | the same renders read generic initial-forms refs and actual run facts (`src/seon/cluster.clj:95-138`; `src/seon/cluster/agent.clj:107-128`) |
| transcript's `seon.bootstrap` dependency, `bootstrap/run-id` argument, id-based `not=`/boolean pin classification, `pinned-receipt-ids`, pinned partition, and bootstrap minimum-budget floor | bootstrap-only visibility and fit policy survive inside the otherwise-correct active-runs rule | every run uses the same candidate history and fit policy, all receipt/count/comment/history queries join the two-clause active-run rule, and token-accounting regressions prove superseded runs do not count (`src/seon/render/transcript.clj:14,84-90,105-197,414-433,668-725`) |
| `seon.eval.drive/bootstrap-complete?` and every test/helper that waits by reconstructing `bootstrap:<agent-id>` or recounting `bootstrap/agent-sources` | hidden consumer of the old identity and cluster-only resolver | drive awaits the actual creation result/run ref and verifies terminal receipt count from that run's frozen forms (`src/seon/eval/drive.clj:1-53`) |
| bootstrap-only tests and render coverage (`test/seon/bootstrap_test.clj`, bootstrap fixtures in transcript, boot, restart, armed, oversight, concurrency, and render coverage tests) | they pin deleted names and magic ids | replacement tests prove inheritance, wholesale agent override, explicit empty override, resolved digest distinction, atomic creation, re-preparation supersession, curation replacement, and one active-run projection (current inventory: `test/seon/bootstrap_test.clj:1-237`; `test/seon/render/transcript_test.clj:310-470`) |

`help`, `dir`, and `doc` are useful ordinary REPL functions, not a second
initial-forms mechanism. Move their ownership to the surviving REPL namespace
and update the default declaration's namespace bindings before deleting the
bootstrap namespace. Do not delete their behavior merely because the current
namespace also owns the obsolete plan code (`src/seon/bootstrap.clj:109-128,275-290`).

The ordinary model reply path is not deleted: it freezes a model-authored
source vector through `run/plan-tx` after the provider call
(`src/seon/cluster/loop.clj:1315-1342`). Curation's receipt adoption is also
not a competing insertion path: it first constructs the replacement run with
`system-run-tx`, then commits receipts already mechanically proved on the
scratch branch (`src/seon/cluster/curate.clj:301-339`). These are the same run
model with different authorship/proof, not legacy bootstrap mechanisms.

## Ugly output observed

The walk currently frames every rendered value with cryptic comment lines such
as `;; dN · ...`, `;; unit=... branch=...`, and a comment-only volatile
metadata section (`src/seon/render/walk.clj:541-644`). This is already recorded
as the strict-REPL-display issues “Make the rendered walk an ordinary REPL
value” and “Return walk state and failures without comment notices”; it is not
part of this design-only lane and I did not edit it
(`docs/seon/issues/index.md:121-126`).
