---
type: plan
status: specified; launch after wave 4 and named proofs
created: 2026-09-21
tags: [namespace-agents, writeback, publication, tests, demonstration]
---

# Wave 5 — accepted definitions to disk, then the two-agent demonstration

This document supplies verbatim, launchable assignments for **5a F2**, **5b
F3**, and **5c demonstration** of
[namespace-agents-plan-2026-09-19.md](namespace-agents-plan-2026-09-19.md).
Pass each quoted assignment together with this entire document. These are
implementation obligations, not claims that a write-back API or proof exists.

## 1. Binding order and inherited acceptance

Wave 5 inherits **Wave 4 §3, “Shared acceptance contract”**, including
“Bases, changes and exact definitions”, “The merge gate is the existing test
system”, and “Durable acceptance and conflict evidence”, by name from
[wave-4-isolation-merge-spec-2026-09-21.md](wave-4-isolation-merge-spec-2026-09-21.md).
Do not restate its gate as a weaker write-back precondition. B, T and C retain
its meanings: immutable fork basis, tested target and source evidence commit.
Let **M** denote the resulting accepting target commit. Git commits are a
separate identity domain, always named explicitly as Git commits below.

Launch only after the orchestrator names the actual commits and evidence for:

1. Wave 4a/4b/4c, its bridge population acquisition/reset prerequisite, and
   its combined-candidate SCI gate, accepting transaction and root-conflict
   proof. A plan or historical green subset is insufficient.
2. Wave 3a's task writer, D2 occurrence wakes, D13 fingerprint and task
   acceptance; 3b's linked render pairs; and 3c's actionable openings for
   the selected proof classes. Read
   [wave-3a-task-family-spec-2026-09-21.md](wave-3a-task-family-spec-2026-09-21.md)
   and section **3c** of
   [wave-3bc-render-pairs-and-template-proofs-spec-2026-09-21.md](wave-3bc-render-pairs-and-template-proofs-spec-2026-09-21.md).
3. The landed common incremental publication, its lineage reuse and
   incremental-versus-complete digest equality, including schema/test,
   namespace/interface and producer-toolchain invalidation. Read
   [publication-dissolution-spec-2026-09-20.md](publication-dissolution-spec-2026-09-20.md)
   end to end. Its “landed” entries explicitly leave some proofs owed.
4. Released source/schema/test/operator owners, compatible loaded consumers,
   and the orchestrator's current hook/reset checkpoint. No lane unpauses
   publication or resets default to manufacture this prerequisite.

Land **5a → 5b → 5c serially**. One implementation JVM per lane, foreground;
no overlapping fast run, probe or fresh-boot child. At most two test JVMs in
the repository and three editing lanes overall, per AGENTS rules 11–16.
The orchestrator executes cold/platform checks and schedules the fresh-boot
proof. A runtime agent requests that evidence as data; it does not run bash.

The namespace plan's §§5/6/8 supersede the older research's sketches:
provenance is recovered before deciding replacement versus insertion;
combined-state tests precede merge; root resolves conflicts; pushes remain
the owner's. Read the complete
[isolation-merge-writeback research](../research/isolation-merge-writeback-2026-09-19.md).
Its “no span means append”, post-merge test sketch and approximate CAS wording
are not the implementation contract.

## 2. Dependency and first-party ledger

These anchors describe the inspected shared tree, not a clean execution
snapshot. Refresh by owning function after predecessors land. No dependency
fork change is authorized.

| Boundary | Source and consequence |
|---|---|
| Function/test coordinates | `resources/seon/schemas/seon.fn.edn:8` declares `/file`; `:9` declares `/form-span` as half-open **UTF-8 bytes**, not character indices. Function storage includes them at `:122–123`; `resources/seon/schemas/seon.test.edn:93–94` uses these same attributes. `seon.fn/text-context`, `exact-source`, `exact-form-span` at `src/seon/fn.clj:112`, `:186`, `:208` compute exact source and byte offsets from captured text and cumulative UTF-8 line starts. `var-row` at `:593` attaches them to both functions and tests. |
| Race-free analysis capture | `src/seon/fn/analyzer.clj:79` maps mirrored paths back to their source; `:317` writes captured sources; `:402` `analyze` analyzes that capture and deletes its private mirror. Analyzer rows/columns are converted by the indexer. `source-rows` at `src/seon/fn.clj:1174` deliberately removes disk coordinates from admitted runtime definitions. Absence is not evidence that the identity is new. |
| File identity | `resources/seon/schemas/seon.fn.file.edn:1` is publication-root-relative path; `:4` is full-file byte digest; `:5` is optional declared source root. `fn/artifact` at `src/seon/fn.clj:1289` rewrites file refs relative to the selected root. Never infer `src` from missing provenance or use a temporary absolute path as durable identity. |
| Two different digests | `fn/declaration-digests` at `src/seon/fn.clj:1257` and `fn.signature/declaration-metadata` at `src/seon/fn/signature.cljc:38` identify interface/metadata changes for invalidation and intentionally omit bodies. `:seon.program/analyzed-source-digest` in `fn/var-row` identifies the complete analyzed input, not this declaration alone. Neither is a sufficient body-sensitive disk-movement check. |
| Ordinary indexer | `fn/build-artifact` at `src/seon/fn.clj:2072`, `build-manifest` at `:2364`, `publication-inputs` at `:2170`, `desired-rows` at `:2837`, `reconcile-tx` at `:3119`, `published-index-rows` at `:3133`, `index!` at `:3222` own analysis, closure, schema/contract rows and reconciliation. Reuse them; do not implement an exporter-owned analyzer. |
| Schema resources | `src/seon/schema/edn.clj:203` reads one EDN map; `:255` validates resource placement; `:301` merges with duplicate-key refusal and files-by-key provenance; `:324` derives the population. `:399` digests canonical declarations, `:408` exposes packaged forms. `src/seon/schema.clj:3477` `canonical-schema-rows` produces stored schema forms. Existing schema rows do not promise function-style source spans. Add capture/span support at this resource reader, not guessed filename parsing in write-back. |
| Lossless edit | `src/seon/edit.clj:20` converts char offsets to UTF-8; `:222` `splice`, `:254` `lossless-candidate`, `:303` `form` preserve surrounding bytes. The named-form selector is not a universal schema-entry locator. Extend the same pure owner with a span request. |
| Parser semantics | `reference-code/rewrite-clj/src/rewrite_clj/parser.cljc:1`, `:40` preserve all nodes including whitespace. `zip.cljc:192` `position-span` is one-based row/column, exclusive end-column, requiring position tracking. `node/namespaced_map.cljc:34`, `:57`, `:92` preserve qualifier context and distinguish key from value nodes. Resolve `#:ns{...}` keys through that reader context, not text matching. Node string lengths are not UTF-8 offsets. |
| Filesystem effect | `src/seon/edit/jvm.clj:95` reads, transforms and passes an expected full-file digest to the existing FS writer. `src/seon/fs/jvm.clj:593` checks digest/absence; `:622` stages and forces bytes; `:651` serializes its writers and rechecks before atomic move. This is per-file replacement with process-local serialization, not an OS transaction across files or exclusion of arbitrary external editors. |
| Durable effects | `src/seon/effect.clj:272` attributes writes; `:302` `settle-call`, `:528` `settle-value!`, `:851` `request!` own admission, blobs, bounded execution and settlement. `resources/seon/schemas/seon.effect.edn:77–100`, `:127–153` retain request/result and settled-at. Use these facts, not a second export job table. |
| Publication | `src/seon/cluster/source.clj:103` `snapshot`, `:155` `current`, `:167` `database`, `:469` `publish!` carry source/input identities and reconcile on current lineage before guarded head publication. `unresolved-report!` at `:179` checks read availability; it does not by itself enforce non-growth of unresolved names. F3 must compare complete before/after sets. |
| Gate provenance | `seon.fn/gate-sets` at `src/seon/fn.clj:1487`; wave 4's landed schema-aware `seon.test/select`, `selection-admission`, `admit-run`, `run-owned`, `recorded-result`; `src/seon/test/runner.clj:2285` `program-digest`. The last digest includes the source seal and runtime deltas: a pre-export branch and a freshly published tree need not have identical test program-digest strings. Compare declared content separately; never rewrite confidence tuples to force equality. |
| Snapshot and host proof | `bin/test:692` begins the canonical HEAD-plus-paths snapshot; `src/seon/test/cache.clj:298` copies it, `:318` `worker-checkout!` materializes the admitted worker checkout. Extend this materialization seam to accept captured candidate bytes before destination writes; no worktree or alternate snapshot implementation. Test input inventory and pinned dependencies remain the existing cache owner's. |
| Edit hook today | `bin/seon-hook:1467` `source-index-path?` admits source/tests/schema/default config, not documentation. `:1518` `publish-source-paths` invokes `bin/seon init --dev CLUSTER --changed PATH...`; `:1615` drains queued paths immediately, with in-flight edits joining a successor. `:1653` queues and returns before convergence. `:1802` detects unnamed shell writes for lint; it does not publish those paths. `.claude/seon-hook.edn` currently disables publication and post-adoption checking. F3 explicitly invokes the publication owner; neither hook notification nor an old source seal proves convergence. |

Read the existing Datahike and SCI seams named in wave 4 §2 when composing
acceptance reads: immutable commits and stable identity values survive branch
cleanup; branch-local EIDs and live contexts do not. Source publication,
cluster adoption, loaded JVM behavior and browser paint are separate proofs.

## 3. Shared write-back contract

### Accepted definitions and provenance are inputs

Read `:seon.program.merge/id` and `/evidence` from the **successful accepting
transaction**, then materialize its typed `/acceptance` payload and M.
Consume `/definitions`, `/base-commit`, `/target-commit`, `/source-commit`,
`/schema-dependencies`, `/selected-tests`, `/test-evidence` and generation
identity exactly as wave 4 declares them. Require M's definitions to agree
with that payload; a proposed row or green source candidate is not acceptance.
For successive accepted merges, select a single explicit target commit E
containing their final definitions. Earlier superseded definitions stay in
history and are not exported over E. Every selected final change needs its
actual accepting transaction, including root's conflict resolution.

`seon.program/writeback-request [request]` derives a **data value**, carrying
one immutable accepted target E, its accepted observations, the disk basis
publication, Git parent, explicit destination root, file captures and export
scope. The caller supplies environments/DB values once at the boundary.
Serialized request evidence contains commit/store/branch locators and blob
digests, never connections, SCI objects, compiled schemas or source EIDs.

For an existing declaration, recover its last indexed file/span/source from
the accepted ancestry and disk basis publication, following the actual
identity. Use current bytes to verify the located declaration, not old offsets
blindly. A new identity needs positive absence in that basis plus an explicit
file destination and insertion anchor; it never guesses a file from its
symbol. A new file additionally carries its exact namespace declaration and
required bindings accepted through wave 4. Missing or ambiguous provenance,
unsupported multi-declaration macro spans and overlapping selected spans are
precise refusals. Retractions require the same accepted deletion/caller repair
as wave 4; splice only their declared region, not a guessed whole file.

### The pure F2 arrow and exact equality

`seon.program/writeback-bytes [accepted-entity current-file-bytes]` is pure.
Here **accepted-entity is an envelope**, containing the portable accepted
row/retraction, its verified basis declaration, identity, source locator and
namespace/schema reader context. It is not a raw pull with unresolved refs.
Return candidate bytes plus spans/digests/identity observations, or a precise
flat refusal. It performs no DB, filesystem, process, publication or Git action.
The only splice is the existing `seon.edit` owner. Batch planning validates all
regions in one captured file and splices nonoverlapping regions from highest
start offset down; never reuse a pre-edit offset after a lower splice.

The declared **basis-content-digest** uses `seon.id/digest` at length 64 on
an ordered vector of identity plus authored declaration: the exact UTF-8
function/test source string, or the resource reader's canonical schema value.
Never feed a byte-array object's printed identity to this digest. Function
body, inline contract and metadata bytes therefore participate; schema whitespace does not.
This is a request observation, not the indexer's interface digest. Preparation
uses the ordinary indexer/resource reader on the captured file to supply its
current declaration span and reader context. The pure F2 function verifies the
capture identity, reads that byte region and computes the same digest without
invoking clj-kondo, a database or the filesystem. A changed capture requires a
new prepared envelope, never a guessed relocated span. Namespace bindings and
other declaration facts are additionally checked by the full round trip below.
Different current declaration content from both basis and accepted result returns
`:seon.program.writeback/disk-moved`, naming path, identity, expected/actual
content digests, old/current spans, accepted merge and both source values (or
explicit absence). A changed file digest alone is insufficient to attribute a
conflict to this declaration; unrelated bytes are preserved and included in
new staging/gate input identity. File move/ambiguous relocation refuses;
there is no automatic file-level merge.

The indexer must reproduce the accepted **portable authored definition**,
including function/test source strings byte for byte and canonical schema
form strings byte for byte. This does not assert equality of entire raw EAVT
rows across branches. Numeric IDs, file coordinates, full analyzed-input
digest, and admission provenance change when a runtime definition becomes
indexed disk source. Declare one explicit `writeback-content` projection at
`seon.program`, derived from row ownership, and use it on both sides. List and
assert those provenance transitions separately: `/source :agent → :core`,
actual file/span, actual complete analyzed-input digest, and regenerated
contract/analysis components. No calls, refs, metadata, contract properties,
namespace bindings or test subjects may be dropped to make equality pass.
A discrepancy in any of them refuses export with identity and attribute path.

Three independent comparisons are required:

1. Candidate/written bytes at each new function/test span equal the accepted
   exact source. Schema value nodes read back to the accepted canonical
   `:seon.schema/form`; preserve every surrounding EDN byte and map key.
2. Ordinary indexing of the complete staged inputs produces exactly the
   accepted portable definitions and the unchanged neighboring definitions.
   Hash their deterministic identity-ordered content through `seon.id/digest`
   and assert equality as well as structural equality, with positive counts.
3. Incremental publication of those files on the existing lineage equals a
   complete publication of that **same staged tree** in canonical program
   content and the publication owner's program digest. Compare the published
   content to E using comparison 2. Do not compare the old branch's
   seal-plus-deltas test digest to a new file snapshot's digest, or rewrite
   recorded test provenance. Unaffected recorded evidence remains reusable.

**Formatted-but-unchanged is a no-op.** For schema EDN, alternate whitespace
or key spelling under an explicit namespaced-map qualifier which reads to the
same accepted canonical definition leaves the original bytes untouched. For
function/test declarations, whose stored source is exact text, “unchanged”
means the current exact declaration already equals the accepted source;
formatting elsewhere in the file is preserved. Check already-equal before
basis divergence: replay after an already-applied accepted formatting edit
must not rewrite or commit again. Merely equal evaluated forms with different
accepted function source bytes are not byte identity and cannot be silently
normalized away. No formatter is introduced to resolve that distinction.

### Request, policy and settlement facts

Search the landed schema population for equivalent declarations first. The
following are exact proposed names in
`resources/seon/schemas/seon.program.writeback.edn`; keep all maps open.
Most are typed **request/result/blob values**, not new stored attributes.

| Key or shape under `:seon.program.writeback/` | Required grammar and meaning |
|---|---|
| `request-id` | Existing `:seon.effect/id` value of the preparation request; observations survive effect-row deletion. Retried final attempts name the same preparation; a changed proposal needs new preparation. |
| `request` | Explicit runtime environment/connection, destination root, accepted commit, merge-ids, disk-basis commit, Git parent, entries, policy and declared deadline. Durable counterpart omits runtime objects and carries the existing effect/blob payload conventions. |
| `accepted-commit`, `disk-basis` | UUID commit values with explicit store/branch locators; required. Missing materializable commits refuse. |
| `merge-ids` | Nonempty set of wave-4 merge request identity values; no source-local refs. |
| `git-parent`, `git-commit` | Full Git object-id strings, never shortened task citations or Datahike UUIDs. Git commit is required only in committed-success, not no-change or refusal. |
| `root`, `path`, `entries` | Explicit filesystem root and root-relative paths; nonempty ordered vector of typed declaration envelopes. Each carries existing program identity/row grammar, basis/accepted content and file capture observations. Paths must remain inside the chosen checkout; no symlink alias to foreign source. |
| `basis-content-digest`, `current-content-digest`, `accepted-content-digest` | 64-character digests of identity plus authored declaration, as defined above; body-sensitive, not declaration-interface digests. Full portable-content equality is a separate required comparison. |
| `before-digest`, `after-digest`, `span`, `bytes` | Reuse existing file-digest, UTF-8 span and immutable content/blob grammars. Pure byte arrays, if used internally, are never persisted and are never mutated by the transform. New-file absence uses the existing explicit FS precondition grammar, not nil. |
| `policy` | Exactly `:seon.program.writeback/targeted-platform-fresh-boot` for this wave. This names an eligibility/evidence policy on a request, not an entity kind or a second selector. |
| `gate-request`, `gate-evidence` | Exact selected tests, platform scope, snapshot input/toolchain identities, original run/member locators/confidence, fresh-boot observation and round-trip comparisons. Schema requires every proof, not optional green booleans. |
| `fresh-boot` | Existing recorded test run/member locator plus root, new process identity `(pid,start-instant)`, fresh store/branch/commit, input/toolchain/population identities and positive runtime probes. Observation values, not a stored ready flag. |
| `candidate`, `result` | Pure candidate or final committed-success/no-change with exact paths, digest observations, publication identity and effect/result locators. Final success requires all gate, write-readback, publication and Git evidence; no-change has a positive equality observation and no Git mutation. |
| `disk-moved`, `provenance-unavailable`, `round-trip-refused`, `gate-required`, `gate-refused`, `partial-write`, `publication-unavailable`, `commit-unavailable` | Specific error schemas composed with the landed error base/facets. Each names operation, missing/mismatched evidence and relevant path/identity/request. No general `/kind`, class boolean, universal error predicate or catch-all success map. |

`seon.operator/prepare-writeback! [request]` and
`seon.operator/writeback! [request]` are bounded compositions through the
existing effect owner. Preparation settles its own effect with immutable
candidate/gate-request evidence; that is **not write-back success**. Final
write-back refuses with gate-required until the orchestrator's recorded
proof locators are supplied and verified. It never starts a waiting daemon.
A new ordinary task attempt can supply completed evidence later.

The write-back settlement fact is the final effect's **existing
`:seon.effect/settled-at` plus its typed `/result-edn` or `/result-blob`**.
A settled refusal is still a refusal. No `/exported?`, `/green?`, duplicate
job entity or mutable progress registry. Large candidates and evidence use
the existing blob owner and declared limits. Historical acceptance remains
immutable; write-back neither amends wave-4 acceptance nor invents a merge.

### F3 policy: stronger proof before destination mutation

All candidate source writes before this gate are to the **owned staged
checkout**, not the destination checkout or its hook target. Reuse the gate's
HEAD-plus-explicit-input snapshot/materialization and input inventory;
staging contains all selected source/schema/test/namespace changes together.
A scratch **operator root alone does not change the source checkout**: boot
must run with the staged checkout's bin/classpath/resources and explicit root.
No shared-tree staging, Git worktree, private publisher or copied default DB.

The fixed policy requires, in order:

1. Valid wave-4 acceptance and the complete three-way equality above; complete
   unresolved-caller sets before/after with no new unresolved names and a
   positive analyzed population. Missing input/coverage is a refusal.
2. The **complete reaching union for every exported identity** over disk
   basis and staged candidate, including changed/new tests themselves and
   schema/namespace dependents through wave 4's landed selector. Deleting an
   edge/test cannot remove an obligation. This is broader than simply reusing
   the merge request's selected list. Runtime members run through the same
   `run-owned` custody and recorder; unavailable host obligations are explicit.
3. All declared **`:seon.test/platform`** members against the exact staged
   input generation in isolated workers, run by the orchestrator. Reuse is
   allowed only through the shared selector's compatible evidence; original
   confidence remains attached. Missing, pending, excluded, zero-assertion,
   unconfirmed or nonterminated members cannot satisfy either tier.
4. A **fresh-boot observation**, always newly executed for this prepared
   export: new owned scratch root/store, new process identity, publication
   from staged bytes, acquisition/arming from that persisted population,
   then actual function, schema-boundary and newly indexed test probes.
   Existing platform green or default's hot reload cannot substitute. The
   orchestrator runs a bounded operator drill using the existing init/start,
   supported MCP and down operations; this is a new process event independent
   of whether the regression which tests that protocol reuses green. Its
   scratch publication starts from files, with no copied recorded results, so
   `run-owned` executes the newly indexed test through normal selection. Keep
   those actual run/member locators and bounded probe evaluations together
   with the process/root observation. A cached regression member alone cannot
   satisfy this requirement; do not invent a force-run test policy.

Down the fresh-boot root and observe child exit before another owned JVM
starts. No nested cold gate inside a regression or lane. The orchestrator
can perform these phases serially; lane iteration remains fast-only.
No destination file, Git index/commit or default publication changes on a
prewrite red or unknown. The request remains reviewable as candidate bytes,
required tests and precise missing evidence.

After green, revalidate Git parent, the captured input inventory and all
file preconditions **before the first destination write**. If a file/input
changed since staging, refuse and prepare again; do not splice a changed
file after testing and call it the tested candidate. Hold the named paths
against cooperative writers for the short install/publication/commit phase.
Each actual replacement still uses the existing FS digest/absence fence.
The FS check cannot exclude arbitrary outside editors between a check and
rename; do not claim that stronger guarantee. Cross-process transactional
filesystem exclusion is outside this wave.

Several files are not one filesystem transaction. On a later write failure,
record the exact applied/unapplied paths and actual digests through the effect
result, return partial-write, publish/commit nothing further, and preserve the
candidate evidence. Do not restore foreign bytes or reset Git. A subsequent
ordinary task attempt observes files: already-equal regions need no write;
remaining stale regions refuse; compatible remainder is prepared and gated
again. Interruption never resumes an execution. Missing settlement means
outcome unknown until actual files, publication and Git history are observed.

Publish the complete installed path set once through the common owner;
for a development target use explicit
`bin/seon init --dev default --changed PATH...` only under the orchestrator's
released/default lifecycle authority. In scratch proofs name scratch root
and cluster. Shell/effect writes cannot rely on editor hooks. The orchestrator
holds the existing hook publication pause across the short multi-file
installation, waits for any previously admitted publication to
settle before the first write, and records the pause/re-enable at the working
edge. This prevents the hook from publishing an intermediate file subset.
A pre-existing structural-cut pause is not permission to install: wait for
that owner's released checkpoint. Explicit publication/adoption uses the same
digest owner while the installation pause is held; re-enable only after the
complete installed inputs converge. A partial outcome leaves publication
coordination with the orchestrator, never an automatic unpause or rollback.
No exporter-specific suppression/cache or sleep. Record publication and
adoption separately, then read back actual rows and file digests. Fresh-boot
preproof remains distinct from this adoption.

Finally commit **only the reviewed written paths**, with literal argv/pathspecs
and a `Seon-Writeback: <preparation-effect-id>` trailer. New files are staged
by explicit path first. Verify the Git tree blobs equal the gated bytes and
that the commit changed no extra path. Refuse pre-existing foreign edits in
any owned destination path; unrelated dirty/staged files remain untouched.
Pure F2 no-change describes bytes only. Final no-change additionally proves
those bytes already belong to the required published, path-limited Git
outcome. Equal disk bytes left by an interrupted earlier write still require
the outstanding publication/commit; their preparation/effect observations
identify owned partial work, not unrelated foreign edits. No push. A
publication, adoption or Git failure is an honest partial outcome, not a
successful export. After a lost Git response, query the trailer and
verify parent, paths and exact blobs before deciding whether a commit already
exists. Never blindly create a duplicate commit or erase an unknown result.

## 4. Lane 5a — F2 pure projection and ordinary-indexer round trip

> Implement **wave 5a only: F2 accepted definitions to exact file bytes** in
> /Users/sean/src/seon, branch steward-platform. Use **gpt-6-astra low**.
> Read this entire document, the whole namespace-agents plan, isolation
> research, wave-4 spec, wave-3a task spec and publication-dissolution spec
> end to end; read AGENTS §§1–3/5 and rules 11–16, and data-oriented-clojure,
> data-modeling, datahike, repl and clojure-testing skills. Refresh every
> ledger seam used here, including fn/analyzer/resource reader/edit owners
> and rewrite-clj's position/namespaced-map source. Verify §1 prerequisites.
>
> You are not alone. Preserve unrelated edits, use released paths, do not
> delegate, create worktrees, operate default or repair foreign sessions.
> This is the pure/data slice; no filesystem exporter, Git runner or new
> indexer. Continue independent design/tests when a foreign owner is held.
>
> **Own** `src/seon/program.cljc` at writeback request/content projection,
> `src/seon/edit.clj` at the pure span operation, `src/seon/fn.clj` at captured
> artifact/span projection, `src/seon/schema/edn.clj` at resource capture and
> entry spans, and `src/seon/schema.clj` only if canonical schema-row
> provenance needs carrying. Own `resources/seon/schemas/seon.program.writeback.edn`
> (new), `seon.program.edn`, `seon.edit.edn`, `seon.fn.edn`, `seon.schema.edn`
> and `seon.schema.edn.edn` in that resource directory only for those shapes.
> Own `test/seon/program_writeback_test.clj` (new), `test/seon/edit_test.clj`,
> `test/seon/fn_test.clj`, `test/seon/schema/edn_test.clj`, and
> `docs/prds/steward-platform/research/wave-5a-writeback-2026-09-21.md`.
> These are the existing schema owners; declare no sibling registry.
>
> **Add exact functions** `seon.program/writeback-request [request]`,
> `writeback-content [shapes row]`, `writeback-bytes [accepted-entity bytes]`
> and `seon.edit/span [source request]`. Accrete `fn/build-artifact` with an
> optional captured-source input so ordinary projection and F2 use one
> implementation; absence retains the current filesystem behavior. Keep
> pure byte transformation separate from analysis IO. Add
> `seon.schema.edn/declaration-spans [request]` at the existing resource
> reader, returning resolved key, exact value source/span and file capture
> identity. The ordinary reader consumes that same parsed capture; preserve
> its duplicate-key, placement, malformed-map and trailing-input checks,
> strengthening any missing check at this owner. Derive configured forms
> after authored entries, never fabricate a disk span for a derived form.
>
> Add no schema identity just to make a component selectable. Entry spans
> can travel in the immutable request/artifact; if a durable provenance
> attribute is needed, declare it once at the schema owner with its actual
> meaning and deletion policy. Reuse fn/file and fn/form-span only where
> their declared grammar/meaning actually fits; do not pretend the entire
> schema map is a function declaration. No filename regex or exporter
> resource roster. Explicit supplied destination plus reader provenance
> decides new schema/test placement. Every private/public function has its
> exact Malli input/output union, including the precise propagated errors.
>
> Recover historical provenance for overrides before insertion. Use the
> accepted row's source exactly; tests must have file namespace bindings
> which resolve ordinary JVM `deftest`/`is`, not only SCI's supplied bindings.
> New tests may append at a verified end-of-file anchor; new files require
> exact accepted ns bytes and absence precondition. Existing schema entries
> replace only the selected value node. Unsupported generated/shared spans,
> unknown destination or source incompatible with indexed metadata refuse
> with complete evidence. Retraction removes only its accepted span; all
> caller repairs and namespace changes remain part of the same batch.
>
> **Canonical regression class:** real with-database population, real
> accepted rows from wave 4's fixture, armed contracts, canonical helpers
> and transacted!, supplied projection/context, fixed render profile and
> bounded exact event waits. Prove a function edit with non-ASCII prefix
> and CRLF, a schema edit in both ordinary and namespaced EDN maps, and a
> newly admitted test in an existing file plus a new test-file case. Reindex
> through the ordinary owners; assert exact source/form, all portable
> definition facts/components/edges and the permitted provenance changes.
> Preserve neighboring comments, separators, trailing bytes and unrelated
> declarations. Two edits in one file prove offset handling.
>
> A body-only disk edit with the same interface digest must return
> disk-moved and no candidate write. Missing/duplicate identity, missing
> historical span, split UTF-8 code point and overlapping/shared spans
> refuse. Check accepted deletion and same-batch repaired callers through
> the real indexer/final writer. Formatted-but-unchanged schema input and
> already-equal accepted function text return exactly the original bytes;
> repeated application is no-change. Do not call semantic form equality
> exact source equality. Wrong source/spec or changed call/subject edges
> must fail round-trip instead of being excluded from comparison.
>
> Extend the existing incremental publication regression with the same
> function/schema/new-test batch. Both incremental and complete indexing
> yield identical portable program content/digests on the same inputs;
> incremental publication preserves recorded evidence lineage. Reuse the
> actual publication fixture; no second hand-rostered program projection.
> If that regression lives outside the listed tests after predecessor
> landing, name and obtain the released owning test path before editing.
>
> Iterate serially with `bin/test-fast --paths <all owned changed paths> --
> seon.program-writeback-test seon.edit-test seon.fn-test
> seon.schema.edn-test` and the actual publication regression namespace.
> No cold gate, --all, --full or nested boot. Require every changed production
> namespace before each coherent path-limited commit. Do not use the generic
> worktree fallback against this assignment's explicit no-worktree rule.
>
> **Deliver** the named landing note with exact input/output bytes, digests,
> canonical comparison counts, no-op/refusal evidence, complete paths and
> fast executed/unchanged/unavailable tally. Report the orchestrator's
> cold/platform and 5b fresh-boot proof as owed. Estimate **1.5–2.5 lane-days**,
> including the schema-entry and provenance work missing from the older
> projection sketch. Stop after 5a, or before a genuine new mechanism with
> three priced options, simplest viable constraint recommended.

## 5. Lane 5b — F3 gate, effect settlement, publication and commit

> Implement **wave 5b only: the stronger write-back policy and its existing
> effect/operator composition** in /Users/sean/src/seon, branch
> steward-platform. Use **gpt-6-astra low**. Read this whole document and
> every §1 authority end to end, AGENTS §§1–3/5 and lane rules 11–16;
> load data-oriented-clojure, data-modeling, datahike, repl,
> clojure-testing and seon-flow-architecture before composition work.
> Read operator/effect/FS/publication/test owners and dependency seams in
> §2. Verify 5a's commit and actual wave-4/task/publication prerequisites.
>
> You are not alone. Preserve unrelated changes. Shared owners are serially
> released from 5a/4c/publication/test work. No delegation, worktrees,
> default restart, foreign session operation or cold gates from this lane.
> Foreign breakage changes the proof boundary, not the assignment's scope.
>
> **Own** `src/seon/operator.clj` at preparation/writeback handoff,
> `src/seon/program.cljc` only at 5a request composition, `src/seon/edit/jvm.clj`
> only to consume the shared span candidate, `src/seon/effect.clj` only for
> any necessary typed request/result carriage, and the existing publication
> seam in `src/seon/cluster/source.clj`/`src/seon/cluster.clj` only for
> explicit captured input and readback composition. Own
> `src/seon/test/cache.clj` and `bin/test` only at shared staged-input
> materialization/admission, `src/seon/test.clj` only at declared policy
> composition, and `script/seon/fresh_operator.clj` only if existing operator
> handoff needs the new request. No unrelated harness or lifecycle rewrite.
> Own `resources/seon/schemas/seon.program.writeback.edn`,
> `seon.operator.edn`, `seon.effect.edn`, `seon.test.edn`, `seon.source.edn`
> at those narrow contracts; `test/seon/program_writeback_test.clj`,
> `test/seon/operator_test.clj`, `test/seon/effect_test.clj`,
> `test/seon/cluster/source_test.clj`, and new
> `test/seon/dev/writeback_boot_test.clj`. Own
> `docs/prds/steward-platform/research/wave-5b-disk-gate-2026-09-21.md`.
>
> **Add exact functions** `seon.operator/prepare-writeback! [request]`,
> `writeback-policy [request]`, `writeback! [request]`; accrete the existing
> fork request with prepared task data if 4c did not already carry it, so
> task initialization precedes its first arming. Private capability
> handlers stay at this owner with declared :io workload and exact schemas.
> Preparation and final write use `seon.effect/request!`; settlement uses
> its ordinary writer. Declare §3 policy/evidence/result shapes. Reuse the
> current shared selector, recorder and effect deadline/output caps. The
> operator resolves destination/custody once and carries values; no globals,
> repeated connection lookups, custom queue, scheduler or blocking gate daemon.
>
> The prepared snapshot is HEAD plus exactly the request's candidate inputs,
> with Git parent, captured disk inputs and pinned dependencies. Factor the
> existing snapshot materialization seam only as needed to admit explicit
> candidate bytes/blobs instead of requiring those bytes on the destination
> disk first. Reuse worker-checkout!, input-roots and input-digests. Refuse
> unrelated dirty source inside the proposed base or released destination
> paths rather than accidentally including it. Stage every file together.
> Runtime requests return the declared gate-request; they do not call
> bin/test. The orchestrator uses the canonical cold launcher on this exact
> staged snapshot and records proof through the same test owner.
>
> **Enforce §3 F3 policy, not a green return code.** Resolve every test/run
> locator, original confidence, actual selected union and terminal assertion
> evidence. Require complete schema/reference coverage, platform tier,
> positive round-trip and unresolved-set comparisons, and new fresh-boot
> observation for this preparation. Covered reservations are not results.
> No full-suite widening, forced rerun flag, reused host proof for SCI
> overrides, input-digest manipulation or second runner. The boot assertion
> is a fresh observed process/root event even if other members reuse green.
>
> Before installing bytes, compare all captured inputs/Git parent and exact
> candidate blob digests. Write via the existing FS capability, preserving
> per-file preconditions and stage/move cleanup. Prove disk-moved before
> writing and partial-write after a later failure separately. Report outcome
> unknown after interruption; a new attempt queries files, effects,
> publication and Git trailer. Never resume old evaluations, rollback foreign
> bytes or report an effect's settled refusal as export success.
>
> Publish all changed paths through the ordinary incremental owner, verify
> its exact written definitions, then perform the literal path-limited
> Git commit and verify tree blobs/path set. No push. Do not let a hook
> race or paused hook satisfy publication. Development adoption is an
> orchestrator-owned explicit transition; a producer mismatch names the
> required host transition and blocks success. Read current-src versus
> adopted commit; a changed Var alone is not publication proof.
>
> **Regressions:** real wave-4 accepted definitions, canonical fixtures,
> actual staged files and digest writer. A failing reaching test, failing
> platform member, missing/old fresh-boot event, absent assertion/member,
> pending coverage, wrong input/toolchain, source mismatch or increased
> unresolved set changes no destination byte or Git state. Query that the
> request/refusal exists. Green with some reused members reports original
> confidence; missing population is unavailable. Verify body-only disk
> movement between preparation and execution, same-file unrelated movement,
> and movement after staging but before the FS check. All refuse the old
> gated candidate. Already-equal bytes cause no replacement/extra commit.
>
> Exercise multi-file failure after the first real write, lost response
> after write/publication/commit, failed settlement recording and duplicate
> final delivery. The next attempt observes exact facts and never duplicates
> the Git commit or overwrites new bytes. A symlinked external sentinel
> survives scratch/stage cleanup. Use the real writer; fixture-controlled
> failure points test the seam, not a mocked FS implementation. Include a
> foreign staged file and prove it is absent from the path-limited commit.
>
> **Fresh-boot test**, orchestrator-only integration: implement
> `seon.dev.writeback-boot-test/exported-definitions-survive-fresh-boot`
> with declared platform/long duration through existing test metadata and
> the real operator fixture. This regression verifies the drill protocol;
> its reuse does not perform the separately required new boot event. Deliver
> a bounded replay of existing init/start, supported MCP probes and down for
> that event, with exact request/preparation correlation. New root
> `tmp/wave-5b-boot-root` from staged source, no copied default store. Assert new process/store identity,
> persisted population acquisition, armed representative function call,
> invalid/valid schema writes at the actual authority, selected new test
> execution and published content equality. Down and observe termination.
> Do not execute this child-JVM fixture inside the lane's fast run; deliver
> its runnable command and required recorded predicates to the orchestrator.
>
> Iterate `bin/test-fast --paths <all owned changed paths> --
> seon.program-writeback-test seon.operator-test seon.effect-test
> seon.cluster.source-test`, plus the released snapshot/selection namespace
> whose owner changed. Require production owners together, serially, before
> path-limited commits. The orchestrator runs the corresponding cold
> --paths scope, --platform, and the fresh-boot fixture against staged inputs;
> no SEON_CODEX_LANE override, nested cold gate or lane platform launch.
>
> **Deliver** exact request/effect/Git/publication locators, byte/digest
> comparisons, fast tally, phase measurements, cleanup and owed cold/boot/
> default proof in the named note. Estimate **2–3 lane-days** including
> staged gate input admission and honest partial outcomes. Stop after 5b;
> no demonstration source repair or campaign. A decision requiring a second
> publisher, cross-process filesystem transaction or task/test mechanism
> goes to the owner with exactly three priced options before edits.

## 6. Lane 5c — runnable two-agent demonstration

> Implement and drive **wave 5c only: the demonstration below**, in
> /Users/sean/src/seon, branch steward-platform, on **gpt-6-astra high** for
> evidence review. Read this entire specification, the namespace plan,
> wave-4 spec and wave-3a spec end to end, and wave-3bc's complete 3c setup,
> five judging bars and exclusions. Read AGENTS §§1–3/5 and rules 11–16;
> use data-oriented-clojure, data-modeling, datahike, repl and
> clojure-testing skills. Verify 5a/5b and all upstream proofs by commit.
>
> You are not alone. No delegation, worktrees, default lifecycle operations,
> source/schema repairs, foreign sessions or paid providers. Use released
> canonical trial subjects and deterministic admitted virtual replies, as
> 3c specifies. This proves context/execution/acceptance, not model quality.
>
> **Own** `test/seon/dev/wave5_demonstration_test.clj` (new) and
> `test/seon/dev/wave5_demonstration_support.clj` (new shared replay/capture
> helper). Consume the landed template-proof helpers without editing
> their owners. Own
> `docs/prds/steward-platform/research/wave-5-demonstration-2026-09-21.md`
> and its sibling artifact directory `wave-5-demonstration-2026-09-21/`,
> including `demonstration.clj`, a replayable sequence of ordinary runtime
> requests, explicit assertions and bounded event waits. No second runner
> or prepl sender. Driver forms are loaded through supported MCP into the
> one owned scratch JVM; cold/boot commands are the orchestrator's phases.
>
> The two main workers each receive **one task** in one forked cluster,
> responsible for the same selected namespace. Worker A uses 3c proof 3,
> strengthen a schema with a reproducing example. Worker B uses 3c proof 1,
> repair a red test, choosing an actually executed new reproducing test
> which fills missing coverage for the related function/render behavior.
> Thus D5's schema-and-missing-tests direction and the plan's function/render
> task both have real acceptance evidence. Choose the final open subjects
> after predecessor landings; do not reopen a fixed production bug. A
> canonical trial declaration may encode the small defect through ordinary
> admission, with the reason recorded. Never forge a failure datom or
> disable contract admission to construct the trial.
>
> A missing-test detector alone is not proof 1: the new reproduction must
> actually execute red before B repairs it. Link that run/member to B's
> single task. Include a harmless shared trial function in both tasks'
> allowed scope to seed the conflict round later. The conflicting proposals
> and their reproductions belong in the recorded task data/opening, not
> hidden patch coaching. Root's later conflict task is separately created
> by the actual merge refusal. No worker-per-occurrence or shared fixed-root
> conflict pool.
>
> Implement the exact phases and assertions below as runnable forms. Fixture
> pass first with real SCI/armed contracts; then the same forms on owned
> `tmp/wave-5-demo-root`, target `wave5-target`. Its two candidate clusters
> are `wave5-schema` and `wave5-function`; root's resolution candidate uses
> the existing wave-4 operator/task API. Keep at most one owned live JVM;
> stop/persist it before the orchestrator's cold/platform/fresh-boot phases,
> then restart and reacquire to finish. Private objects are not restored.
>
> Gate/red/opening failures remain failed recordings and name the upstream
> owner with complete evidence. Continue independent capture work. Do not
> alter production to make the demonstration pass. Before implementation
> commit, load the proof namespace on the test classpath and run the bounded
> fixture members through bin/test-fast --paths. No cold/platform launcher
> from this lane. The orchestrator owns the actual disk gate and authorizes
> the explicit released destination path set for final F2/F3 execution.
>
> **Deliver** path-limited proof/script/artifact commit; exact fact table and
> byte manifests below; separate fast, live, cold, fresh-boot and destination
> commit verdicts. Stop after the full narrative or name its real unmet
> prerequisite, never call an omitted phase green. Estimate **1–2 lane-days
> plus orchestrator gate time**; this is bounded demonstration work, not the
> remaining contract campaign. At a genuine decision bring exactly three
> priced options, simplest viable constraint first and recommended.

### Demonstration script protocol and observable assertions

Implement `prepare!`, `isolate!`, `merge-disjoint!`, `conflict!`, `resolve!`,
`prepare-export!`, `finish-export!`, `answer!`, `verify!`, `cleanup!` once in
the owned test helper namespace `seon.dev.wave5-demonstration-support`.
Each takes one declared request map, returns typed evidence, and uses the
real owners above. The research script calls these same helpers; it does not
copy their implementation. These are test/probe helpers, not production APIs
or a template registry. Supply actual identities, paths and source bytes
after subject selection, so the committed replay contains no `<...>`
placeholders, private expected patches or unchecked sleeps.

A driver runs the forms through the supported root/cluster-qualified MCP;
no extra Clojure CLI process alongside the scratch JVM. Initial operator
commands (once the prerequisite implementation exists):

```sh
bin/seon --root tmp/wave-5-demo-root init wave5-target
bin/seon --root tmp/wave-5-demo-root start wave5-target
```

Next, `prepare!` seeds and measures the trial subjects on the target, then
invokes the ordinary 4c `seon.operator/fork!` once for the two candidate names
from one captured commit. That owner creates one task per candidate. Supply
subject/tests/keys/functions/messages through its task initialization request
before arming; no second task or copied unrelated agent may activate. The
landed fork request must support this existing task data handoff. If it does
not yet, 5b owns the narrow accretion in operator/fork-request and its
initialization call, landing it with the task-before-first-opening regression;
5c consumes that released contract. No empty preliminary turn is counted as
the templated opening. Commit the literal supported-MCP replay form using
those exact task inputs. The script's following phases assert:

| Phase | Runnable action and positive observations required |
|---|---|
| **prepare!** | Capture Git/source/input/toolchain identities, target population and E0. Seed released trial declarations through canonical owners. Execute the schema's accepted bad example and valid neighbor; execute B's real red reproduction. Admit/link one task per candidate using task writer, exact subject/test/run relations and namespace responsibility. Query task IDs and assignments positively: two worker tasks, two distinct agents, one selected namespace; no template/kind attribute. Capture input.edn before actions. |
| **isolate!** | Observe 4c's distinct branch connections/environments/SCI contexts and equal fork B; only each candidate task's agent is armed there. Await actual listened wake datoms, ordinary turn opening basis, opening evaluations and their task origins. Save generated source/shown text and pair selections, judge against the two 3c bars before virtual replies. Let each worker make its admitted repair. Query changed-since and exact branch-local definitions; target and sibling stay at their prior definitions. A malformed candidate must produce a typed admission refusal with unchanged program/stamp and no successful merge record. |
| **merge-disjoint!** | Request A's ordinary runtime merge: selected symbols, real SCI results, completed evidence C, accepting transaction/id/evidence, M and two parents. Target now contains A's exact definition. B's old tested target refuses stale; preserve B's proposal, use 4c refork from new T, reacquire, readmit and gate anew. B then lands through the same writer. Query both exact accepted changes in target, task acceptance tests and original confidence tuples. No target change preceded either gate. |
| **conflict!** | Before concluding the two tasks, take a fresh common fork basis and have both workers propose different admitted changes to the shared trial identity. This can use their existing tasks/candidate assignment via 4c; no second worker task. Gate/merge the first. Deliver the second conflicting merge request twice, varying occurrence time/message/evidence while preserving D13 location/operation/facets. Query both precise refusals and stored occurrence observations. Both must contain B and both sources. The rejected program transaction changes no declaration/stamp/acceptance/parents; the following error/task observation transaction may advance target history. Assert equal error signatures and task fingerprints across the two occurrences, exactly one conflict task, one task-assigned root worker and one notification identity; repeat evidence/wake advances. |
| **resolve!** | Let that task's root worker inspect both source values and B from its ordinary linked opening, author the resolution plus its new acceptance test on a fresh candidate at current T, and run the shared runtime gate. A deliberately red resolution attempt cannot merge. The repaired resolution must have positive recorded test/assertion/termination evidence, its own wave-4 acceptance and target M. Root has no privileged write route. Query the task's current acceptance including recurrence basis; neither old green nor task resolved flag alone counts. |
| **prepare-export!** | Select one final target E containing the two workers' accepted work and root's final resolution. Resolve every merge acceptance and provenance at E. Call prepare-writeback!; query its settled preparation effect and candidate/gate-request payload, positive declaration count, exact original/candidate file blobs, destinations, selected test union and policy. Assert destination bytes and Git index remain untouched. Demonstrate moved-disk refusal in an owned trial destination, not by editing shared source; retain both digests and zero writes. Prepare the real released destination again with exact current captures. |
| **orchestrator gates** | Persist/down the demo JVM and observe exit. On the immutable staged checkout, run canonical targeted cold scope and platform, then the fresh-boot proof on `tmp/wave-5-demo-boot-root` from staged bytes. Record actual run/member locators, executed/unchanged counts and confidence; root/process/store observations and function/schema/new-test results. Down the boot root and observe exit. No copied default DB or test JVM overlaps. Restart demo target and reacquire from facts; pass proof locators to finish-export!, never green literals. |
| **finish-export!** | Execute the existing F3 owner with prepared request and recorded gate evidence. Assert exact disk bytes, per-file digest results, the final effect's settled-at and successful typed payload, incremental publication of all changed files, indexed declared-content equality with E and complete-publication equality. In the actual released destination, verify path-limited Git commit parent/trailer/blob/path set; no push. Replay observes the same outcome/no-change and adds no duplicate Git commit. If any prerequisite changed, the expected result is refusal, not a relaxed rerun. |
| **answer!** | On the same ordinary task/message/render mechanism, fulfill a real request asking which definitions changed and which gates verified them, using 3c proof 5's explicit answer criterion. Cite actual merge/effect/test/Git identities. Query response recipient/parent when provided by the real trigger, handled and wake answering independently, with same-about unrelated root as control. This follow-up does not add a second implementation task to either main worker. Polite acknowledgement or mere wake coverage is not fulfillment. |
| **verify! / cleanup!** | Query positive source/effect/task/test populations and every predicate above before returning success. Preserve original red/refusal attempts and exact bytes. Stop trial graphs before retracting fixture facts. Destroy candidate branches through 4c, proving accepted evidence remains readable on target; down owned roots, await recorded process exit, then remove only owned scratch/stage trees. Verify the symlink sentinel and absence of live holders. Never sweep another lane's roots or alter default. |

The orchestrator's gate invocation in the recorded replay uses the real
materialized staged checkout and concrete namespace/path lists. Its forms are:

```sh
bin/test --paths OWNED_PATHS -- SELECTED_NAMESPACES
bin/test --platform
bin/test --paths OWNED_PATHS -- seon.dev.writeback-boot-test
```

These three lines specify the regression handoff shape, not a command to run
literally: 5b/5c write the concrete commands for their admitted staged snapshot
in `commands.sh`, with cwd/source root and all arguments. After they finish,
that same replay must explicitly init/start the new boot root from staged
source, perform the supported-MCP function/schema/new-test probes and down it,
recording process exit. This operator drill is mandatory even when all three
test requests reuse green; their exit codes cannot create its observation.
Named cold scope may contain additional namespace members; the required
reaching union remains explicit in gate-evidence. Never widen the runtime
merge gate to this disk policy.

Every phase artifact records task/agent/namespace, store/branch/commit,
program/input/toolchain identity, tested and recording bases separately,
exact request/outcome and elapsed time. Keep `opening-source.clj`,
`opening.txt`, `selection.edn`, `actions.clj`, `results.edn`,
`acceptances.edn`, `conflicts.edn`, `writeback.edn`, `commands.sh` and a
manifest of UTF-8 sizes/SHA-256/token estimates under the named research
artifact directory. Store actual source bytes, not only hashes. Render
observations may include HTML/browser evidence, but HTTP success never
substitutes for paint and none substitutes for declaration/test facts.
A final table names every required phase as passed, failed or unavailable,
with links and exact missing evidence. Do not claim the full demonstration
while destination commit or fresh-boot is still owed.

## 7. Held paths, estimates and explicit exclusions

The inspected tree has concurrent edits in publication (`bin/test`,
`script/seon/fresh_operator.clj`, `src/seon/cluster.clj`,
`src/seon/cluster/source.clj`, cache), bridge (`src/seon/schema.clj`,
`src/seon/schema/internal.cljc`, `src/seon/schema/datahike.clj`,
`src/seon/schema/edn.clj`, parity tests),
plan and test evidence owners. Those were preserved. This is a dated
observation, not permanent ownership. Refresh git status and the actual
orchestrator handover at launch. A clean landed predecessor releases a path;
an actively edited path does not become available because a spec names it.

5a holds program/edit/indexer/resource-reader owners through its coherent
landing; 5b then holds effect/operator/publication/test staging seams; 5c
owns proof files and artifacts only. Schema and loaded consumer changes
publish together. The orchestrator records any hook pause/re-enable and
reset batch in the working edge. A lane never resumes/messages a foreign
session or fixes foreign dirty hunks. HEAD-plus-owned-path fast iteration
is the isolation path; no lane worktrees, cold gates or default restarts.
Snapshot refusal names its exact path and verification boundary; independent
work continues. Preserve the source-load proof before any public retirement.

Estimate **3.5–5.5 serial implementation lane-days plus 1–2 demonstration
lane-days**, excluding gate/reset/held-path queues. The early 2.5–3-day plan
priced a projection and disk gate, not schema-entry provenance, staged
prewrite host proof, durable partial outcomes and a full conflict replay.
These are estimates; no new timings or test tallies were measured here.

**What wave 5 must NOT build:**

- No file-level merge tool, formatter, pretty-print rewrite of accepted
  functions/tests, second indexer, schema compiler or publication lineage.
- No new Git workflow framework, worktrees, automatic reset/rollback,
  branch switch, merge-to-main or push without the owner.
- No second test selector/runner/result cache, fake green facts, waived
  fresh boot, copied default database or shell gate inside an agent turn.
- No export job registry, scheduler, retry daemon, permanent root worker,
  template entity/type, alternate task family or source-local EID transport.
- No byte normalization that hides source mismatch, interface-digest-only
  disk guard, fabricated provenance, guessed destinations, silent missing
  schema/test coverage, or claim of multi-file atomicity.
- No automatic conflicted reapply, privileged root merge, publication before
  the stronger gate, presentation clipping or migration of stored data.
- No remaining contract campaign, broad production repair or paid-provider
  benchmark as part of the demonstration.

## 8. Design-lane evidence and landing boundary

Read end to end: namespace-agents plan (including wave 5, §§3/6/8 and final
D13); isolation-merge-writeback research; wave-4 spec; wave-3a task spec;
publication-dissolution spec; `src/seon/fn.clj`, `src/seon/fn/analyzer.clj`,
`src/seon/cluster/source.clj`, and `bin/seon-hook`. Read wave-3bc's complete
3c section, AGENTS §§1–3/5 and rules 11–16, roadmap/working-edge entries,
and the specialized source/dependency/skill slices cited above.

Dated checkout observation during this design: branch `steward-platform`,
HEAD `6b48335291c9737f825a2b1f664d7842e0b0e817`; predecessor history includes
`3ac00fb8e` (publication lineage/loaded producer guard), `08ce441a6`
(artifact memoization), `8edfae1b7` (declaration/toolchain invalidation).
Gitlinks: Datahike `e11845bac78e1241bca0766ddc07d978bd63d74a`, SCI
`fcbd8862800e638dc0f8f5521111f999279cbcd2`, rewrite-clj
`60782e501aaf312cb90c9ff0bee05d5da5125563`. Shared-tree anchors may include
uncommitted predecessor work; refresh them rather than treating them as
runtime proof or attributing an unrun failure to another lane.

This design lane writes **exactly this document**. No source/test/schema,
other document, configuration or skill edit; no JVM launch, prepl evaluation,
cluster operation, worktree, agent launch, test/gate or browser proof.
The explicit read-only/no-worktree assignment overrides the generic fallback
paragraph. Static review checks links, source owners, acceptance inheritance,
phase coverage and whitespace. All regressions, source-load, live adoption,
cold/platform, fresh-boot and actual export/commit proofs above belong to the
future lanes/orchestrator. The design itself is committed path-limited and
stops; nothing is pushed.
