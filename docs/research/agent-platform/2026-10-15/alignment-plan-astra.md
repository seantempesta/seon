---
title: "One committed database value; explicit execution and observation"
type: plan
status: independent proposal; not the hybrid; no implementation authorized by this document
created: 2026-10-15
lane: align-astra
basis: Seon 2724ef32d; default ed9eb4f99; Datahike 1a149891; SCI eb3290d5; Malli dd13ff81
---

# The commit is the one point in time

Seon already derives a frozen SCI program from database content and forks it for execution. Finish that composition:
hold the immutable database value at each read entrance, derive its program and projection together, and keep the
connection solely as the branch's write authority. Delete alternative selectors of the same program.

**Verdict: the thesis is right for committed database state, too strong for execution.** A commit identifies a stored
snapshot, not an agent's private objects, a speculative database, an external effect, or all branch heads at once.
Commit identity is also not content equality: two commits can contain equal programs. Cache by the content a
derivation reads, using commit identities to acquire and compare values. Do not introduce a universal world object,
another registry, or a commit comparison around every call.

## 1. Evidence and corrections to the brief

Authorities read: [alignment-dig](alignment-dig.md), [Datahike internals](datahike-internals.md),
[one merge base](one-merge-base.md), [branch rulings](branch-model-rulings.md),
[reach experiment](exp-c-reach-by-content.md) including §8 and its [patch](reach-maps.patch),
[overlay memory](overlay-mem-fix.md), [merge cost](merge-cost.md), [leaf profile](leaf-hot.md),
[replay cost](replay-impact.md), [TASK-CHAIN](../../../seon/schedule/cards/TASK-CHAIN.md),
[MERGE-STEP](../../../seon/schedule/cards/MERGE-STEP.md), AGENTS.md and the Clojure, REPL, Datahike and testing skills.
No other independent plan was read. The inventory is historical evidence, not a list of 58 reproduced defects.

Own probes used MCP `eval_clj`, agent root, cluster default, JVM mode; only reads and local values, no definitions,
transactions, reloads, branch creation or cache eviction. MCP itself records its requests. Default was reachable but
`:faulted`: 25 open faults, 309 errored receipts, heap live 1.107 GB, maximum 4.295 GB; readiness 50,703 ms.
No live acquisition or test run was invoked: acquisition currently writes declarations, and tests record results.

| Probe | Observed result | Cost, calling thread unless marked request |
|---|---|---|
| Two `seon.sci.eval/program-context` calls on one held database | Both warm; no database read entrances | 0.34 / 0.38 ms; 156,416 / 152,208 allocated bytes; whole request 241.9 ms / 53.3 MB |
| Program contexts of a head and its parent, unchanged program | `identical?` true; frozen environment is a map | Head lookup 0.23 ms / 152,208 bytes; whole request 45.2 ms / 13.9 MB |
| Two `sci.core/fork` calls on that frozen value | Distinct environment atoms; identical namespace maps | One fork 0.01 ms / 640 bytes; this is not the full agent acquisition cost |
| `datahike.api/with` of the held head and `[]` | Basis 536873224 → 536873225; both commit-id APIs still return the parent's UUID; committed-value-identity is nil | 0.23 ms / 35,608 bytes; whole request 85.8 ms / 7.6 MB |
| `seon.test/world-base` | Selects loaded commit `6ad07b08-ff07-5e90-95c7-13bb0084a813`, not current head | 0.29 ms / 57,416 bytes; whole request 79.8 ms / 7.6 MB |
| Installed schema membership | `:seon.agent/answered-t` absent | Included in 86.1 ms / 7.2 MB metadata request |

Reproduction forms, with no retained defs (measurements are single observations on a shared JVM):

```clojure
(let [db (seon.db/db (seon.cluster.boot/connection "default"))
      before (seon.db/commit-database db (first (datahike.api/parent-commit-ids db)))
      x (seon.sci.eval/program-context before)
      measured (seon.profile/measure #(seon.sci.eval/program-context db))
      y (:seon.profile/value measured) a (sci.core/fork y) b (sci.core/fork y)]
  [(identical? x y) (map? (:env y)) (not (identical? (:env a) (:env b)))
   (identical? (:namespaces @(:env a)) (:namespaces @(:env b)))
   (select-keys measured [:seon.profile/ms :seon.profile/alloc-bytes])])
(let [db (seon.db/db (seon.cluster.boot/connection "default"))
      measured (seon.profile/measure #(datahike.api/with db []))
      after (:db-after (:seon.profile/value measured))]
  [(seon.db/commit-id db) (datahike.api/commit-id after) (seon.db/commit-id after)
   (datahike.api/committed-value-identity after)
   (seon.db/basis-t db) (seon.db/basis-t after)
   (select-keys measured [:seon.profile/ms :seon.profile/alloc-bytes])])
```

The second probe falsifies “a UUID returned by commit-id proves this value is committed,” not a reproduced cache
collision. `datahike.core/with` detaches committed cache identity; `datahike.versioning/commit-id` merely reads metadata.
`datahike.writer/create-thread` can commit several transactions together and gives their reports the same committed
db-after. Thus a commit is neither one transaction nor a globally comparable t. Preserve transaction identity for
events. The dig's C7 wording about entity ids is also stale: current writer allocation is store-wide; speculative
allocation and older histories still require identity-based joins when comparing different values.

Already landed since older notes: ed9eb4f99 shares graph-record reads by commit; 1a149891 includes the writing branch
in graph records and the uncopied/shared-cache overlay fix. `seon.test.runner/reach-entries` already finds a prior by
store and content. Attribute-map predecessors still use branch-local t. Fork 771a0c4b adds index diff but is not the
gitlink at this basis. Do not price these completed changes again.

Closing checkout update: f4b7aab32 has now pinned 771a0c4b; 7e84ba763 completed MERGE-STEP B/D/E, including
`seon.task/branch-attempts`, task-named branch claims and root's end reader. These are completed dependencies below,
not new alignment work; no claim here that default loaded them. a6157c86c also removes history/merge-base work from
unchanged requests. Its recorded local read gains (5.81 → 1.31 ms; 99.8 → 4.4 ms for a larger window) supersede the
older selection baselines for those paths. Preserve that changed-input economy when replacing t with index diff.

## 2. Model and interfaces

**Point in time.** Within a store, a committed database is named by its Datahike commit id; across stores carry the
store identity as well. Materialize through `seon.db/commit-database`. A branch is Datahike's mutable head pointer;
a connection is its counted writer/listener resource. Neither is a point in time. The immutable db value already
holds the indexes: passing it avoids reopening its commit. A commit pointer does not keep its bytes alive; retained
branches and the existing GC reachable extension own retention, including a kept merge branch.

**Check out.** Select the task's branch, acquire its connection, dereference once at the operation boundary, and
derive program and schema projection from that held value. A read-only checkout of an old commit needs no writable
branch; writing from it uses `datahike.versioning/branch!`. Never infer checkout from an agent id or cluster name.

**Reacquire.** Between evaluations, read the branch's new head. Equal relevant content reuses the frozen program;
changed definitions and their affected callers are installed through SCI's existing Var operations in that agent's
context, preserving private objects. Do not rebuild private state from database facts or share an agent's live context.
Within an operation all read helpers receive the same value. A subsequent write/read operation obtains db-after or
dereferences the connection again: do not bind a database for the entire request and thereby hide its own writes.

**Program.** `seon.sci.eval/program-context` remains the owner. Its inputs are branch program content, the loaded
program's content, projection and instrumentation config. Its frozen result uses `sci.core/freeze`; execution uses
`sci.core/fork`. Those SCI seams exist in the maintained fork, not as claims about unmodified upstream SCI. Classify
host-bound declarations from what SCI can interpret, as now. Compiled callers affected by an interpreted change must
be interpreted too; capturing one JVM function root does not freeze the Vars that its compiled body later dereferences.
SCI fork does not deep-copy objects inside bindings. Shared host services keep their declared process ownership;
an agent's evaluated private defs stay in its execution context. A frozen environment map alone is not proof that
every object it reaches is immutable. P2b's proof must cover this boundary, not only map/atom identity at the root.

**Speculation.** A `d/with` value is an immutable value without a new stored commit. Hold it directly for the request;
do not label it with its inherited UUID. An as-of value is its committed origin plus Datahike's temporal operator and
time point. Different origins with the same t need not agree, even after an origin's no-history data changes. Share
derived content when proven equal. Do not use `[base-id authored-tx-digest]` as a universal speculative identity:
transaction functions, generated ids and implicit transaction time can affect the result. First derive the effective
value once; a writable/testable snapshot can then be committed in the existing disposable overlay.
The existing `seon.db/committed-value-identity` already distinguishes attached committed raw values; its connection
and generation members describe process ownership, not a portable content key. Use its commit member with store
identity where a stored snapshot is required. This observation warrants no new identity wrapper or speculative
cache-bug fix: no collision was reproduced. A request's memoized evaluated facts may remain a request-local input.

**Test world.** Retain the clean fixture contract. Define its prepared database W from the candidate's entire program
content P(C), declared fixture inputs and config. The existing canonical writers compose these into one commit W;
derive the context from W, and fork W for each member. Candidate task/branch inputs remain explicit, captured after
earlier members record, as TASK-CHAIN requires. A fixture is not the agent's private SCI layer or every live fact on C.
Preparation may reuse a clean published base, but its program delta is against that base's program, not against the
candidate's merge base. This prevents silently running newer main definitions that the candidate never held.

**Results and renders.** A result is an observation, not a pure computation promised by a commit. Reuse requires
the test's reached definitions, actual relevant host implementation, explicit fixture/task inputs and read evidence.
Keep evaluator mode out of semantic identity; include the code that changes semantics. Timeout/load-dependent failures
are observations, not reusable proof of a deterministic red. Render immutable input with its selected renderer,
profile and held database(s); cache successful output with complete positive/negative read dependencies. A private
renderer or mutable result object is not globally shareable merely because its printed text or commit agrees.

| Derivation | Semantic key; recomputation event | Cost target |
|---|---|---|
| Schema projection and Malli validators | Declared forms/contracts plus predicate-definition closure; changed content | Changed schemas and dependents; `malli.core/validator` reuses compiled schema, `malli.core/-instrument` owns wrappers |
| Frozen program | Loaded content + interpret-plan digest + projection/config content | Cold reached declarations; warm lookup; change follows affected caller closure |
| Reach attribute map / graph | Store + attribute content revisions / normalized definition-and-edge content | Index diff then changed entities; reverse closure for SCC digests |
| Prepared fixture | Candidate program + fixture producer/content + declared config | One preparation per content while retained; member is branch/fork cost |
| Test evidence | Test identity + reach digest + input/read evidence + relevant host content | Lookup or one real execution; C6/D6 keep their existing owner |
| Render / block section | Renderer reach + immutable input + profile/selection inputs + read evidence | Changed read patterns and shown content; no branch/generation in semantic key |
| Merge base / fork point | Store + two commit ids / head and target lineage | Graph traversal with shared immutable graph records; no t conversion |

Attribute revisions are conservative reuse evidence, not canonical content hashes: equal revisions prove agreement,
unequal ones can still describe equal data. Use index diff/content comparison on a miss. A “latest held map” may be a
cache hint, never an authority; any predecessor must produce the same answer as a full derivation. Cache eviction
must affect cost only. Keep all Seon derived caches in `seon.cache`; no per-branch invalidation or second cache budget.

**Loaded program and reload: a real owner decision (§6).** Recommend one compiled program for a JVM's lifetime.
Ordinary publication moves branch program facts; SCI reacquisition realizes their changes. Platform changes become
loaded through the existing batched process replacement. The loaded commit is then an immutable boot input and an
adoption record is historical evidence, never an agent's program selector. This changes today's hot-reload rule and
needs owner approval in the hybrid. A captured commit cannot make concurrent `require :reload` atomic. Do not delete
`call-detecting-program-change` while retaining that mutation, or add another lock, retry or agreement check.

Root reads several independent branch heads. Capture the map of observed branch→commit values once for one pass;
it is an observation of several snapshots, not an atomic store-wide snapshot. Each writer makes its own decision
against its branch's value using the existing transaction function/CAS. No root-wide consistency stamp is necessary.

## 3. Complete inventory disposition

R = derive from the named immutable input (already-correct derivations stay); X = delete that mechanism with its
last caller; S = retain actual effect/resource/observation state for the reason given. These are target dispositions,
not permission to remove functioning machinery before its replacement proof. Mixed rows name the retained part.

| Row | Class | Disposition |
|---|---|---|
| A1 | S | Loaded compiled program is a physical JVM fact; under §6 make it immutable boot data, delete its separate mutable atom. |
| A2 | X | Delete compare-and-answer and expectation stack only in P7 with the mutable reload path they compensate for. |
| A3 | X | Delete `loaded-source` and loaded-connection following; pass the process's loaded value. |
| A4 | S | Adoption completion is historical evidence of an effect, not a mirror required to equal a moving branch head. |
| A5 | X | Delete `adopt-cluster-declarations!`; acquisition must not write main's declarations onto a branch. |
| A6 | S | Registry retains agent identity, private objects, custody and actual exit; program selection derives from its captured db. |
| A7 | R | Shared frozen program by P(C), then one SCI fork per agent; remove held-branch choice of program authority. |
| A8 | R | Keep content-derived `program-context`; correct the dig's obsolete description of its key using §2. |
| A9 | R | Installed declaration data derives from frozen program plus accepted SCI definitions; keep it in the SCI owner's state, delete two mirror atoms. |
| A10 | S | Connection remains write authority and next-operation head source; one captured value within each read operation. |
| B1 | S | t remains a local transaction coordinate for history/event order; never a cross-branch program identity. |
| B2 | R | Committed snapshot identity from committed origin; distinguish inherited UUID metadata on speculation, proven above. |
| B3 | R | Materialization from store and retained commit; pass an already-held db instead of reopening it. |
| B4 | R | Datahike graph merge base; preserve newly landed graph-record cache and scope materialization by store. |
| B5 | R | Fork point from graph/target lineage; delete base-t as its second representation, not the fork-point concept. |
| B6 | R | Changes from two db values, then canonical identity/digest comparison; delete mixed t/UUID base selection. |
| B7 | R | Changed reach from tested content versus current content; no as-of reconstruction from a foreign run's t. |
| B8 | R | Run evidence names tested commit/content and inputs; retain completion transaction as event, not comparison basis. |
| B9 | X | Delete unchanged-through-t shortcut; content-valid evidence already answers reuse. |
| B10 | R | Selection from the two captured values' attribute diffs, including retractions. |
| B11 | S | Error occurrence transaction is historical observation; local since query stays local, cross-branch queries name origins. |
| B12 | R | Current results by reach/input; assignment history by its owning origin plus local transaction, not max t across branches. |
| B13 | S | Transaction tempid names the recording event; writer resolution is Datahike's mechanism, not a snapshot defect. |
| B14 | R | No installed attribute to delete; proposed answered commit below replaces the planned naked-t meaning. |
| B15 | R | Call reach from program content; temporal input names origin commit plus temporal operation, never connection generation. |
| B16 | R | Render's observed origin/content is evidence; transaction remains only if a consumer needs exact event order. |
| C1 | S | Overlay isolates writes and resource lifetime; keep read-through sharing already fixed, do not replace it with a store copy. |
| C2 | R | Projection/context from W; delete `carry-world-projection!` when the database owner supplies its own projection. |
| C3 | X | Delete world-base's loaded/recorded fallback ladder; the caller supplies candidate and fixture inputs. |
| C4 | R | Request view from the held effective speculative value; if tests write, materialize it once in their overlay. |
| C5 | R | Program replacement compares fixture base with all P(C); remove adoption/merge-base arithmetic, retain canonical reconcile. |
| C6 | R | Keep content evidence lookup; branch scans discover records, never define their validity. |
| C7 | X | Delete remaining `reach-owner`; graph prior is already store-scoped, attribute maps convert in reach-maps. |
| C8 | R | Changed attributes/entities from both immutable index values; resolve identity on both sides. |
| C9 | R | Destructive reach is program content, including temporal views; preserve a654ad995's behavior until P1/P5 replace its key. |
| C10 | S | Fixture/config writes and effect ordinals are effects; one owned setup before member execution, not a pure cache of effects. |
| C11 | X | Delete loaded-source-drift veto with execution-consistent evidence in P4/P7; host provenance remains explicit. |
| D1 | S | Bounded Caffeine cache is a performance resource; values derive, eviction changes no meaning. |
| D2 | X | Delete branch-scope and render cache scope atom after invocation keys/read evidence use content. |
| D3 | R | Request-local held effective value; no semantic key based on inherited commit metadata plus max-tx. |
| D4 | R | Effective config from its declared facts and schema content; cluster name only where it selects real input. |
| D5 | X | TASK-CHAIN already deletes `basis-valid?`'s obsolete obligation path; no replacement validity cache. |
| D6 | R | Result cache by full semantic evidence; recorded red is reusable only for the same deterministic behavior. |
| D7 | S | Replaced-root health measures live JVM mutation; under fixed loaded program it becomes boot evidence, not a content stamp. |
| D8 | S | Profile/fallback/error occurrence counts observe real events, not a db derivation; their durability/state ownership stays with those owners. |
| D9 | S | Launched source digest set records actual boot bytes; derive its value once from the committed archive. |
| E1 | S | Counted connection reference for an action is required to write/listen and release correctly. |
| E2 | X | Delete ad hoc borrowing: every holder obtains/releases its own reference through Datahike's existing counted connect. |
| E3 | X | Delete exclusive open monitor/refusal after callers use counted acquire; graph uniqueness belongs to existing execution ownership. |
| E4 | X | Delete connection-metadata projection state when reads derive projection from their db and execution passes services explicitly. |
| E5 | S | One execution registry remains for nonserializable contexts/graphs; no content key can make their mutable identity shareable. |
| E6 | R | Branch pointer and graph record are authoritative; fork entity keeps target policy only, not a duplicate time coordinate. |
| E7 | S | Root's explicit config writes remain; independent branch config is intentional divergence. |
| E8 | R | Environment's derived members come from captured db/program; live services stay in the one execution owner, no replaced mirror atom. |
| E9 | X | Replace scan-local heads atom with the fold returning the captured heads map. |
| E10 | S | Flow owns active work and actual exit; immutable program data cannot replace executing threads/channels. |
| E11 | R | Namespace cursor is SCI's current namespace in the evaluation fold; delete the second volatile mirror. |
| E12 | R | Keep lazy derivation from held db values; a local delay is evaluation sharing, not a second point in time. |

## 4. Consequences for branch work already underway

**Merge base and fork point both survive as different derivations.** Program entities move forward, task facts do
not. Replacing their two bases with one graph answer would overwrite task changes. Keep phase 2b's split and the
ordinary once-at-end chain merge. Use `datahike.versioning/commit-graph-record` for `seon.cluster.registry/commit-step`
instead of full commit records; the branch field is already available. A returned error about criss-cross remains
Datahike's current API behavior, not an invitation to silently select an arbitrary base. Do not infer a merge base
stays unchanged merely because both children have one parent: ancestry can cross through earlier merges.

**Merge step.** Keep the task's explicit branch selection and kept candidate. Read that attempt's end from the
branch its attempt names; share that reader between root's status, advancement and finish paths. Do not copy the end
back, and do not repoint the whole chain to conceal the ownership difference. Root's pass holds both commits as
observed values. Boot/GC claims traverse task branch references, so a kept candidate survives restart. The repaired
candidate goes through the ordinary gate against newly captured main. A commit gives identity, not permission to
publish a stale candidate; the existing writer decides touched-definition agreement. MERGE-STEP owns these edits.

**Phase 3 / reach maps.** Use the now-pinned 771a0c4b in the existing phase 3/reach-maps work. Diff each
attribute range separately; a single wide range traverses unrelated data. Datoms compare e/a/v/tx, so normalize to
program identity and digest before calling a definition changed. Support additions, deletions and retractions from
both sides. Unshared trees can cost a full scan; promise work on differing subtrees, not O(number of changed names).
The fork implementation reads raw DB indexes directly: temporal wrappers are not proven supported. For an as-of
view, retain its origin/operator and use Datahike's view reads; never diff its origin and silently ignore the view.
Keep the schema-derived program partition. Incremental SCC work uses both old/new dependency edges so a removed
edge, new cycle or broken cycle cannot leave a stale component. Full derivation is the equality oracle, not a second
production runner. Store-scoped “last map” hints may churn under interleaved agents; first use a held parent result
when supplied, without scanning history to find an ideal predecessor.

**Overlays.** Keep private writes over shared backend nodes; random overlay identity names a resource, not program
content. An overlay-only commit cannot be reopened from the backend or after release. Cache immutable results by
content, never a materialized overlay database beyond its lifetime. Durable proof provenance must name retained
candidate/fixture inputs, not only the vanished overlay commit. No new per-agent node caches or copies.

**Adoption.** Remove adoption-record following from branch acquisition and test fixture selection independently of
the host-lifetime decision. Branch schemas resolve against branch definitions at validation. Existing platform
contracts still require the loaded host schema/callable provenance where those host calls actually execute; do not
declare a host change tested as candidate code merely because its source exists on the branch.

**Answered basis.** Amend TASK-CHAIN/agent-loop before implementing a new field: proposed `:seon.agent/answered-commit`
names the captured input commit the agent answered, written with its evaluation's recording, not the newly created
recording commit (which is not yet known). Derive unanswered relevant wakes from that origin to the current head;
its own bookkeeping commit does not itself wake the agent again. On failure before recording the prior answer stays.
The branch is the agent's compartment; branch replacement starts a new agent. Root's multi-branch observations carry
their own origins. No `answered-t` migration is needed because it is absent; existing event transactions remain local.

## 5. Loadable commits and proofs

Phases below are proposed except the explicitly landed dependency P6a. “New” proof names are proposed deftests in existing test namespaces; they
must resolve and run red before implementation, never fail just because a symbol is missing. Reuse/extend named
existing tests where the behavior fits. Each row is one loadable commit; resource/schema changes publish with their
consumers. Split only at a complete interface with no duplicate authority left behind. P7 is conditional on §6.
Numbers from earlier notes are attributed baselines, not my remeasurements; after-costs marked derived require the
same end-to-end `seon.profile/measure` probe on parent and change before a performance commit.

| Phase | Owner/interface, deletion with last caller | Red proof and assertions | Before → after cost |
|---|---|---|---|
| P1a | Existing one-merge-base phase 3 uses the landed fork bump: `changed-identities` and all base-t callers switch together to two db values. Delete base-t. | Extend existing `seon.merge-forward-test/after-a-forward-merge-the-branch-changed-only-its-own-declarations` with equal-t siblings, retract/re-add and deletion rows; diff equals identity-normalized full comparison. | Old changed selection 56–128 ms / 98–151 MB (notes, partly superseded above); diff primitive 2.8 ms / 9 MB nearby, 100–130 ms / 250 MB long-diverged. Full selection gain must be measured. |
| P1b | Existing reach-maps slice follows P1a; attribute-map predecessor is a held commit/content revision. Delete `reach-owner`, `reach-changed-entities`, `retire-replaced-revision!` with their callers. | Patch's proposed `seon.test.reach-test/a-new-branchs-first-attribute-map-costs-what-it-changed`; add interleaved equal-t branches and changed/deleted cycle edge; compare complete graph/reach to full derivation. | Old full calls map 288–322 ms / 300–309 MB; derived changed-index traversal plus affected reverse closure. Reuse current store-scoped graph lookup. |
| P1c | Test selection/run provenance carries tested origin/content; delete unchanged-through-t reuse. Retain event t. Temporal/read consumers remain for P5, so no premature helper deletion here. | New `seon.test-test/equal-local-transactions-do-not-share-different-test-evidence`: same t, different definition/input; unchanged content reuses across branches and bookkeeping. | Before branch-local reconstruction/scans; after content lookup plus actual changed read sets (derived), measure full `seon.test/run` with recording. |
| P2a | `seon.cluster.agent/acquire-context!` selects branch schemas without `adopt-cluster-declarations!`; delete that write path and its callers. Existing projection carrier remains until P2b. | Extend `seon.sci.eval-test/acquired-source-context-forks-have-branch-custody-and-private-defs`: main schema changes, reacquisition leaves branch commit unchanged, its predicate still works. | Removes one declaration-adoption operation per acquisition; warm program lookup 0.23–0.38 ms measured. Full acquisition before/after still required. |
| P2b | Db projection owner and SCI owner derive together from held value; convert all E4/E8/A9 consumers, including test worlds. Delete projection metadata state, `carry-world-projection!` and two kernel mirror cells. Keep private runtime identity. | Extend `seon.sci.eval-test/acquired-source-context-forks-own-their-lazy-program-state`: divergent schema/definition forks, private atom and closure survive reacquisition; one branch cannot change another's projection/private object. | Before mirror maintenance on acquisition; after content lookup plus changed caller closure/owned bindings (derived). Fork primitive measured 640 bytes; not full handle cost. |
| P3a | Connection callers use `seon.cluster.store/acquire-branch!`; delete exclusive monitor/open refusal and active-connection borrowing in this commit. | Convert `seon.cluster.store-test/open-branch-refuses-what-the-branches-refute` for counted acquisition; new `seon.cluster.store-test/two-holders-share-a-writer-and-release-their-own-references` proves one release leaves other usable, final release retires. | Prior internals: connect 2.8 ms / 2.2 MB, release 0.5 ms; counted reuse O(1) (derived). No asserted speed gain. |
| P3b | Root heads accumulation becomes a fold; delete E9's accumulator atom with its only scan callers. | New `seon.cluster.db-flow-test/a-root-scan-reads-each-named-branch-once`: supplied branch values change only between scans; each scan's decisions use its held map. | Before/after O(named branches); deletes one mutable accumulation place, no performance claim. |
| P3c | Evaluation fold uses SCI's namespace cursor; delete E11's second volatile and its readers/writers together. | Extend `seon.turn-test/a-run-records-its-starting-namespace` with in-ns then a throwing form; recorded starting/current namespaces follow the actual SCI execution. | Before two cursor updates per transition, after one; O(forms) unchanged (derived). |
| P4 | `seon.test/clean-request-handle` and `program-overlay-tx`: prepare W from full P(C), fixture inputs; context and evidence use W. Delete `world-base` and merge-base/adoption selection inside this path; P2b already removed projection carrying. | Convert `seon.test-test/a-branch-test-world-runs-the-jvm-loaded-program-not-its-fork-point-record` in this commit to assert the branch-held declaration remains; add removal and changed-schema rows. Existing task-input proof proves fixtures see recorded predecessor results. | Derive once per distinct P(C)/fixture, member branch+fork only. Historical cold reach 470 MB is not world creation cost; no fabricated world timing. Overlay fix already measured +1 MB for four worlds versus +1,061 MB before. |
| P5a | Read evidence records origin/operator/content, consuming P1c provenance. Convert remaining `basis-revisions`/`commit-at-t` callers and delete both helpers here; all temporal semantics remain. | New `seon.db-test/read-evidence-distinguishes-temporal-and-speculative-values`: same local t on siblings, negative query insertion, no-history retraction and d/with; validity agrees with querying each held value. | Before temporal reconstruction and place stamps; after relevant raw-index diff or actual temporal-view read (derived). Empty native with measured 0.23 ms; no invented temporal speed claim. |
| P5b | `seon.render/invocation-cache-key` uses P5a evidence and actual renderer reach; delete branch-scope and scope atom with all users. Config projection keys its relevant content. | New `seon.render-cache-test/equal-inputs-render-once-across-branches`: equal reads reuse, an insertion into an empty query changes output, private renderer inputs never leak. Extend existing re-render test for section selection. | Old current-state 221 ms / 309 MB warm (leaf-hot); derived changed section/read patterns, warm lookup otherwise. Measure full block. |
| P6a (landed dependency) | MERGE-STEP 7e84ba763: one attempt-branch end reader and kept-branch retention; reuse its owners, no new commit. | Existing `seon.cluster.db-flow-test/a-task-naming-a-branch-starts-its-attempt-there`, `seon.cluster.registry-test/a-chain-fact-keeps-its-branch-and-base-through-retirement`, `seon.step.merge-test/gate`; rerun only for a subsequent relevant change. | Root pass O(named branches + linked attempts); extra reads proportional to merge steps. Historical 20-chain pass 9–14 ms; new end-to-end timing not measured here. |
| P6b | Amend TASK-CHAIN/agent-loop and schema with explicit answered origin, then all wake/recording consumers in this commit. No migration for absent answered-t. | New `seon.turn-test/recording-an-answer-does-not-answer-a-later-wake`: wake between capture and record, bookkeeping-only advance, crash before record. | Adds one small fact to the same recording transaction; wake selection traverses changed relevant inputs (derived). |
| P6c | Existing TASK-CHAIN obligation-path retirement deletes `basis-valid?` and D5 cache with its last callers; no alignment replacement. | New `seon.cluster.db-flow-test/task-completion-comes-from-current-proofs`: green proof completes; changed reached definition requires new proof without a separate basis-valid stamp. | Before second validity derivation; after existing done-refusals and proof lookup (derived). |
| P7 | If owner chooses fixed loaded JVM, process boot/publication/test entrances convert together: immutable loaded input, ordinary code interpreted after publication, platform at batch replacement. Delete A2/A3 and C11, mutable identity advancement and hot-reload agreement protocol with every caller. | New platform `seon.sci.program-change-test/a-request-keeps-one-loaded-program-through-publication`: two cooperating executions observe old host for whole request, next process observes new; ordinary branch code advances without changing host. Replace stamp-mutation tests, retain crash/exit proofs. | Removes per-call identity comparison (old doc 0.8 μs); larger benefit correctness and deletion. Process replacement readiness observed 50.7 s; no claim of faster platform publication or preservation of private objects. |

P1 and P6a/P6c reuse existing work rather than launching competing lanes. P2/P4/P5/P7 are serial in shared owners;
MERGE-STEP proceeds in its own lane. A proposed red that is already green is not a task: retain its proof and delete
the unnecessary phase; never manufacture a failure. Graph-record `commit-step` conversion belongs to one-merge-base's existing cleanup, with its fork-point
proof, not a new alignment subsystem. The old 8.8 s / 26.8 GB collection figure predates ed9eb4f99: remeasure before
proposing another graph optimization. No leaf format or cache-budget change is justified by this plan.

Counted removal candidates at this basis: **13 named functions**: `seon.cluster.agent/adopt-cluster-declarations!`,
`seon.cluster.agent/basis-valid?`, `seon.sci.eval/loaded-source`, `seon.sci.eval/loaded-basis`,
`seon.sci.eval/loaded-program-current?`, `seon.test/world-base`, `seon.test/carry-world-projection!`,
`seon.test.runner/reach-owner`, `seon.test.runner/reach-changed-entities`,
`seon.test.runner/retire-replaced-revision!`, `seon.db/basis-revisions`, `seon.db/commit-at-t`,
`seon.render/branch-scope`. P7 additionally deletes `seon.sci.eval/call-detecting-program-change`,
`seon.sci.eval/advance-loaded-identity!`, `seon.cluster/call-with-program-change` and `seon.sci.eval/identity-name`:
**17 functions total**, plus mirror cells and call-site plumbing. Counts are targets, not a claimed net-line saving.
Keep canonical reconcile, the one test runner, Caffeine, SCI fork, Datahike branch/connect and the overlay store.

## 6. Simplest alternative and owner decisions

**Alternative: key everything by commit UUID and rebuild on each new head.** Smallest superficial patch, rejected:
the measured with value aliases its parent UUID; bookkeeping creates new commits without program changes; private
objects and compiled Vars are not snapshots; fixture semantics still differ. It would discard sharing already
measured here. The smaller viable change is retaining current content derivations and removing their extra selectors.
Interpreting every declaration from scratch per agent is also rejected: it repeats whole-program work and cannot
interpret host-bound code. No new algorithm for concepts already owned by SCI, Datahike, Malli or git.

Prices below are engineering estimates, excluding existing phase 3/MERGE-STEP work, not measured implementation times.

| Question | Recommended option first | Alternative(s), guarantee and sacrifice |
|---|---|---|
| Can loaded JVM code remain fixed until a restart batch? | **Yes: P7, roughly 2–4 engineer-days including platform proofs.** One immutable loaded program; normal branch edits use SCI; no comparison fence. Sacrifice immediate host reload and private state at replacement; current startup observation 50.7 s. | **Retain concurrent host reload:** requires versioned callable/class ownership through every compiled transitive call, not just capturing roots; at least 1–2 weeks investigation, memory and lifecycle cost unmeasured. No honest one-commit deletion of A2 exists under that requirement. |
| Which program should clean test fixtures execute? | **Entire candidate program, P4, 1–2 days.** Same interpreted semantics as checkout, explicit clean fixtures; convert the named old test. | **Full agent commit as fixture base, 1 day plus test conversions:** simplest pointer sharing, but inherits live agents/messages and changes fixture contract. **Loaded program plus own delta, no work:** retains current mismatch; reject under branch ruling 1. |
| What does answered basis name? | **Captured input commit, P6b, about 1 day plus restart/wake proofs.** Durable replayable origin and no cross-branch t; relevant-wake selection ignores bookkeeping. | **Origin commit plus local t, 1–2 days:** also names an exact transaction within a batched commit; more data if that distinction is actually needed. A naked t has no cross-lineage guarantee and is not offered. |

These questions do not block docs or the already-owned phase 3. P7 must not be implied by agreeing that “everything
is a commit.” Its changed reload guarantee must be an explicit owner ruling before implementation.

## 7. Verification and handoff

This change is documentation only; no runtime/test definition changes or reaching tests. All proposed red proofs
above still need to be authored/run by their implementation owners. The live probes establish sharing and the
speculation distinction, not end-to-end world isolation, reload correctness, retention or acquisition performance.
Existing live faults were observed, not repaired or re-filed on default (writes forbidden in this assignment).

Full working-tree lint: 5 errors, 1,768 warnings, 6,181 ms; foreign in-flight `seon.task/attempt-rows` arity/privacy
changes in `seon.task` and `seon.cluster.agent`. Isolated `git archive` of 2724ef32d, linking only ignored analyzer
config as bin/lane does: `clj-kondo --lint src script test --cache false --parallel`, **0 errors**, 1,763 warnings,
7,186 ms (exit 2 for warnings). The temporary archive was removed. After the foreign boundary landed, final full
working-tree lint at a6157c86c: **0 errors**, 1,768 warnings, 6,174 ms; no source/script/test working diff. No behavioral
tests were run; no reds are claimed accepted. The docs-only bin/lane path uses `seon.test.cache/gate-input?` and must
not publish or widen test selection.
