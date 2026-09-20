---
type: prd
status: reviewed against source; binding
created: 2026-09-20
tags: [prd, schema, malli, datahike, bridge, projection, dissolution]
---

# The Malli-native bridge: one carried generation of compiled schemas

Owner (2026-09-20): "stop fighting malli and use its internals to make
everything fast and simple. However you think we can improve our
applications and definitions to the database schema sketch it out and run
everything by astra with links to malli source so we stop reinventing the
wheel." Earlier: "we should be able to specify a composite error without
causing all these problems. The errors are just data right?"

**Binding direction:** one admitted generation retains compiled Malli
schemas and travels with the database value and environment. Keep the
existing owners and final-state refusal. Section 6 preserves the three
owner rulings; section 7 is the first lane's launch specification.

**Review boundary:** the original PRD was read end to end and reviewed
against vendored source. Every original verification marker is resolved.
Binding describes the approved design, not an implementation or a green
gate. This follow-up changed only this PRD. Canonical armed regression,
cold-gate, population-transaction and live-adoption proofs remain the
implementation owners' acceptance work.

## 0. Evidence and corrected attribution

The accepted [bridge review](../research/bridge-dissolution-review-2026-09-20.md)
(e1368902d) owns the complete inventory and historical measurements.

| Evidence | What it establishes |
|---|---|
| [Adoption silence](../research/adoption-silence-diagnosis-2026-09-19.md):29–78 | Sampled thread CPU reached 127.8–193 s during compilation work. This is not an isolated validator duration or proof that one helper caused all of it. |
| [Projection stall](../research/projection-compile-stall-2026-09-20.md):100–110 | The four-line Malli core scope repair changed 100 candidate validations from 644.447 to 22.735 ms and enumerations from 2,000 to zero. It still constructed 100 candidate registries; retained compiled roots did not produce this result. |
| Same note; [composition review](../research/error-composition-review-2026-09-20.md):107–118 | The 320 s event was not reproduced as a fold stall. Per-declaration registry copying and discarded compiles are independently verified costs, not a demonstrated cause of the entire event. |
| [Publication repair](../research/publication-projection-repair-2026-09-19.md):26–96 | Fresh publication selected a zero-schema DB instead of its supplied 3,208-form construction world. Normal JVM arming's per-wrapper defaults acquisition was also repaired. |
| [Writer diagnosis](../research/writer-hang-root-cause-2026-09-18.md):128–151 | Population application 26,014.335 ms, including final validation 11,438.038 ms; separate commit 7,254.573 ms. Owned checks 9,683.007 ms, but entity-validator calls only 650.491 ms. The residual is not all compilation. |
| Accepted bridge review, census | At that snapshot: schema.clj 3,873 lines / 159 functions; five helpers 2,402 lines. Counts are dated, not independent behavioral targets. |

The later 74,366-operation adoption transaction and the detailed writer
probe's 1,304,168 attempted / 419,043 effective datoms are different runs
and units. The separate lost-listener-completion defect does not establish
that the completing writer probe hung.

Composite error data stays a conjunction: Malli compiles each :and child
and validates all children
(reference-code/malli/src/malli/core.cljc:825–869).
Default malli.util/merge uses the later overlapping schema and requiredness;
it is not conjunction
(reference-code/malli/src/malli/util.cljc:53–89).

## 1. Design laws

- **Values carry their world:** one immutable semantic generation is
  carried by acquired DB values and the environment. Malli's internal
  memoization does not make declarations mutable. Ordinary reads do not
  reconstruct the generation.
- **One mechanism, accreted in place:** modify seon.schema, its Datahike
  bridge and the existing acquisition owners. No second registry service,
  compiler family, writer or projection authority.
- **Dissolution:** retain compiled objects. Compile once means once per
  admitted named declaration and generation, with reuse thereafter. It
  does not mean Malli allocates no internal ref wrappers or child schemas.
- **Facts over inference:** canonical declarations, provenance, program
  edges and the new population stamp remain facts. Derived plans live on
  their generation; no cache-valid flag or entity-kind stamp.
- **Unbreakable graph:** final-state refusal includes swept, removed,
  idempotently asserted and indirectly affected rows, not just submitted
  maps.

m/schema returns an existing Schema unchanged but constructs a pointer for
a named reference (reference-code/malli/src/malli/core.cljc:2550–2573).
Retain resolved Schema objects, not merely a registry of raw forms.

## 2. Target design, verified against source

### 2.1 Construction, sealing and cache ownership

The projection remains the existing Seon value: retained schema and
function-contract objects, canonical forms/provenance/dependencies,
storage plans and a population digest. A bare Malli Registry supplies
only lookup/enumeration (reference-code/malli/src/malli/registry.cljc:11–13).
The population digest is neither the current 32-bit projection fingerprint
(src/seon/schema.clj:701–733) nor automatically the source-file digest.

~~~clojure
{:seon.schema.projection/registry registry
 :seon.schema.projection/forms canonical-forms
 ;; Keep existing admission/dependency/contract fields.
 ;; Step 3 declares the population stamp; storage plans derive here.
}
~~~

| Registry | Exact behavior and cost | Role |
|---|---|---|
| mr/registry | Returns a Registry unchanged; wraps a map with simple-registry. Does not compile or seal (reference-code/malli/src/malli/registry.cljc:24–34). | Normalization. |
| mr/simple-registry | Lookup uses the immutable map; enumeration returns it unchanged (reference-code/malli/src/malli/registry.cljc:24–28). | Valid immutable scope. |
| mr/fast-registry | JVM construction copies S entries into a private HashMap once, O(S); lookup uses it; enumeration returns the original map without merging, O(1) before consumers traverse/hash it (reference-code/malli/src/malli/registry.cljc:17–22). | Final exported registry over a persistent table of defaults and compiled declarations. |
| mr/composite-registry | First matching registry wins; enumeration reverse-merges all registries on each call (reference-code/malli/src/malli/registry.cljc:54–59). | Construction composition, not the final enumeration surface. |
| mr/lazy-registry | Memoizes provider results; defaults precede the provider; enumeration includes only realized provider entries and goes through a composite merge. Concurrent misses can duplicate work (reference-code/malli/src/malli/registry.cljc:81–95). | Serial construction against fixed inputs; realize the full admitted canonical key set explicitly. |
| mr/mutable-registry | Dereferences its holder on every lookup/enumeration; there is no seal operation (reference-code/malli/src/malli/registry.cljc:61–65). | Excluded from running generations. |

**Step-1 choice:** use Malli's lazy registry with a provider closed over
the admitted immutable definitions and predicate bindings. Existing
graph/cycle admission runs first. Resolve every canonical schema/contract
serially through that provider, then export one fast-registry over the
complete persistent table. Preserve existing lookup precedence, local
recursive/property registries and missing-reference diagnostics. Keep
existing function-contract indexing where needed; no incomplete
enumeration is admitted as the population.

**Sealing does not retarget captured scopes.** Compiled objects retain
options/reference closures
(reference-code/malli/src/malli/core.cljc:1952–1977,2587–2593).
An exported fast snapshot does not rewrite those objects. Any retained
construction provider must close only over immutable generation inputs,
never registration overlays, the current DB, environment state or a
mutable-registry. Its remaining mutation is memoization of the same
answers. Probe A confirms the original construction registry is retained.

Thus exported enumeration is O(1), but not necessarily every internal
enumeration. Core ref-cycle identity no longer enumerates
(reference-code/malli/src/malli/core.cljc:1943–1950); the generator's copied
helper still does (reference-code/malli/src/malli/generator.cljc:299–310).
Generator scope optimization is separate work, not a hidden step-1 promise.

Use **mr/schema registry k**, not get: Registry is not an associative map
(reference-code/malli/src/malli/registry.cljc:97–104; Probe B).
Missing keys produce a typed Seon diagnostic, not absence-as-success.

| Product | Cache owner |
|---|---|
| Schema cache | -create-cache makes an atom; -cached uses Cached or computes directly for non-Cached schemas. Concurrent misses are not exactly-once (reference-code/malli/src/malli/core.cljc:345–361). |
| Validator | Retained Schema, :validator (reference-code/malli/src/malli/core.cljc:2626–2640). |
| Explainer | Retained Schema's underlying :explainer; public acquisition can return a fresh outer function (reference-code/malli/src/malli/core.cljc:2642–2657). |
| Generator | Retained Schema, :generator. The cache key does not contain all supplied options: acquire under consistent options for that object (reference-code/malli/src/malli/generator.cljc:496–503). |
| Seon additions | Codec/attribute and whole-value assembly plans, selector-derived schemas, function arity descriptors, policy-specific wrapper identities. Plain entity validators are Malli-owned, not an additional Seon cache. |

Our holder retains only additional products. A selector-derived Schema
uses its own Malli cache after acquisition. A callback assembling/decoding a
DB entity is additional Seon work; the m/validator it calls is not.

valid-candidate-value? and explain-candidate-value take **exactly**
[projection schema-key value], resolve the retained object and call Malli.
Delete both two-argument arities and convert all three-argument forms-map
callers in the same coherent slice. No dual-mode map heuristic or
compatibility arity.

Also retire the ambient candidate-validator and candidate-explainer
acquisition helpers (src/seon/schema.clj:3858–3876). Their source/test
caller search found one call, test/seon/fn_test.clj:1526: replace it with
projection-validator and the already carried fixture projection. Keeping
these helpers would leave per-call registry construction after the value
API migration. Refresh the full caller search before retirement.

### 2.2 Compiled traversal and Datahike attributes

Malli supplies navigation; Seon supplies storage interpretation.

| API | Verified behavior |
|---|---|
| m/type, m/properties, m/children | Node type/properties; entry-schema children are [key properties compiled-child] triples (reference-code/malli/src/malli/core.cljc:2510,2581–2601). |
| m/walk | Postorder callback [schema path walked-children options]; explicit and named schema refs have separate walk options and visited-ref handling (reference-code/malli/src/malli/core.cljc:2611–2624,2003–2015). |
| m/entries | EntrySchema nodes only; values are :malli.core/val wrappers with entry properties. ::m/walk-entry-vals true exposes these wrappers (reference-code/malli/src/malli/core.cljc:2771–2798,1168–1195). |
| :and | Independently compiled children. Parent properties do not automatically become child/entry properties (reference-code/malli/src/malli/core.cljc:825–837). |
| m/deref, m/deref-all | Follow top-level RefSchema targets in their captured scope. Preserve the named :seon.db/ref token before dereferencing its logical union; RefSchema exposes identity (reference-code/malli/src/malli/core.cljc:67–69,102,1952–1977,2818–2832). |
| m/deref-recursive | Reconstructs children, follows schema references, deliberately does not walk explicit :ref nodes. Not needed on each validator acquisition (reference-code/malli/src/malli/core.cljc:2834–2848). |

Keep a small compiled-node storage interpreter in seon.schema.datahike.
Delete schema/form.cljc only after converting every caller and preserving
its non-navigation behavior, especially the derived ref-or-owned-map
component transaction grammar (src/seon/schema/form.cljc:163–200).
Deleting that widening would change accepted values.

For entity discovery, follow entity/base references and conjunctive map
arms; read entries and requiredness; resolve the declared attribute
schemas. Do not collect arbitrary maps inside ref transaction grammar,
enum literals, generator data or unrelated nested values. :or branches
are alternatives, not a union of required members. Conflicting inherited
entries still refuse. The APIs expose enough structure; they do not
infer these policies.

| Meaning | Compiled evidence | Native output |
|---|---|---|
| Primitive / symbol | Node type; current :inst resolves to the inst? predicate type | Existing table; :symbol and :qualified-symbol → :db.type/symbol. Normalize the known :inst alias, not arbitrary predicates. |
| Collection | :set, :vector or :sequential and its child | Many; scalar one. Native many is an unordered set, even for an authored vector. |
| Ref | Named :seon.db/ref before dereference | :db.type/ref; its logical union is transaction grammar, not string storage. |
| Component | Attribute property and declared child target/ref semantics | :db/isComponent true; preserve owned-map widening but derive native ref storage. |
| Identity / unique | :seon.db/identity / :seon.db/unique | :db.unique/identity / :db.unique/value. Preserve explicit property precedence and refuse incompatible declarations. |
| Index / no-history | Attribute properties | :db/index / :db/noHistory. |
| Tuple / secondary-only | Tuple children; :db.secondary/only | Existing :db/tupleTypes and secondary-only float/double tuple mapping. A primitive-only table is incomplete. |
| Enum / literal / heterogeneous union | Values at enum/literal children; distinct union branches | Existing admitted native mapping or explicit EDN codec. Do not silently turn a renderer symbol into opaque string storage. |

Grounding: src/seon/schema/datahike.clj:56–69,124–186,233–276;
reference-code/datahike/src/datahike/schema.cljc:11–83; compiled APIs above.
Malli allows arbitrary properties; their presence does not validate Seon
storage-property names. Delete invented :seon.db/cardinality :many;
preserve legitimate declarations and their admission.

**Probe A:** three real roots produced **52 attribute occurrences, zero
native-output mismatches**: seon.ns/ns, seon.error.occurrence/occurrence,
and my.fs/error, whose actual declaration is [:and base [:map ...]].
Sources: resources/seon/schemas/seon.ns.edn:10;
resources/seon/schemas/seon.error.occurrence.edn:16;
resources/seon/schemas/my.fs.edn:154–169.
Type/cardinality/ref/component/identity/index/symbol were exercised.
The resource search found no :seon.db/unique value-uniqueness declaration;
that mapping is source-grounded through ordinary properties/native grammar,
not falsely described as an exercised real declaration. Tuple,
secondary-only and complete declaration parity remain step-2 tests.

Native schema rows are ordinary :db/ident entity transactions on the
selected connection. The transactor updates schema indexes
(reference-code/datahike/src/datahike/db/transaction.cljc:91–134,924–973).
Not every property is immutable: find-invalid-schema-updates constrains
changes, permits doc/noHistory/isComponent updates, monotonic index
addition, and conditional cardinality/unique changes
(reference-code/datahike/src/datahike/schema.cljc:257–301).
Map updates call check-schema-update; update-schema separately gates direct
index changes. Do not assert identical update checks for every operation
spelling. Seon's reset rule is the chosen way to change incompatible
stored semantics, not a claim Datahike forbids all changes.

### 2.3 One acquired generation; runtime metadata and durable stamp

| Input / carrier | Target |
|---|---|
| Packaged forms | Publication/bootstrap input. Ordinary cluster consumers do not select files over their carried generation. |
| Persisted rows | Durable program; one explicit boot/branch-acquisition loader verified against the stamp. Delete ordinary running-code reconstruction. |
| Declaration constructor | One constructor per complete/candidate generation; reuse the carried generation when definitions are unchanged. |
| DB and environment | Same acquired generation and digest; no per-read choice among three sources. |

**A candidate/base composite does not rebind compiled parents.** Lookup
precedence changes, but a compiled base map retains its old child.
Probe B confirms this. Recompile each changed declaration and its reverse
dependency closure, including function contracts; reuse only unaffected
objects (reference-code/malli/src/malli/registry.cljc:54–59;
reference-code/malli/src/malli/core.cljc:2550–2573,1952–1977).

Datahike's DB is a Clojure record
(reference-code/datahike/src/datahike/db.cljc:96–106,307).
with-meta/vary-meta attach **runtime Clojure metadata**. The record also
has a separate **:meta field**, which Datahike serializes for commit/version
information (reference-code/datahike/src/datahike/writing.cljc:56–58,161–179).
Never put compiled closures or cache atoms in that durable field.

Use existing seon.db/carry-projection-state and carried-projection,
not a new DB wrapper (src/seon/db.clj:232–253,1168).
Connection metadata exposes its wrapped atom's metadata, a runtime owner
pointer, not a durable branch stamp
(reference-code/datahike/src/datahike/connector.cljc:41–46,102).
Acquisition snapshots the generation; a held old DB must not select the
new environment's projection. Temporal wrappers retain an origin
(reference-code/datahike/src/datahike/db.cljc:501,567,633).
Keep current origin-based interpretation; historical schema-at-t semantics
are not introduced here.

**Stamp:** add one non-identity, cardinality-one population digest attribute
in the source family at the reset. Store it on the existing branch-local
source population row, already selected/updated by
src/seon/cluster/source.clj:239–305. No new singleton identity or branch
metadata service. Publication/adoption and subsequent accepted declaration
changes stamp the accepted population. A fork inherits the datom.
Datahike persists the commit and advances its branch head after referenced
content (reference-code/datahike/src/datahike/writing.cljc:477–528).
“Stamp the branch” means a fact in its committed DB, not Clojure metadata
on the branch name.

Digest the exact canonical generation inputs: schemas, function contracts,
admission/source provenance and program identities determining predicate
behavior. Use the existing seon.id owner and canonical ordering.
:seon.source/digest has the digest format but currently denotes source input
inventory and carries identity semantics; do not silently reuse its value
or uniqueness (resources/seon/schemas/seon.source.edn:10;
src/seon/cluster/source.clj:104–152).
The 32-bit projection fingerprint is a reuse aid, not this durable digest.

Publication builds/admit-checks once, derives native attributes/facts, and
submits the candidate with its expected base. The writer checks base and
final graph, records accepted population/stamp, and the owner carries
the accepted generation. Development adoption still loads definitions,
arms contracts and acquires SCI before recording successful adoption.
Do not stamp adoption success early or promise atomic JVM Var replacement
(src/seon/cluster.clj:2440–2525).

Bootstrap is the explicit zero-to-one exception: an empty DB receives its
construction projection before the stamp exists. Ordinary running reads
cannot use that exception. At restart a correctly stamped branch is loaded
once from its durable program, digest-checked, compiled and carried;
unstamped/mismatched ordinary acquisition refuses. Datahike restores stored
DB data, not compiled objects
(reference-code/datahike/src/datahike/writing.cljc:240–276).

In-transaction declarations must advance the candidate for subsequent
declarations, and final validation must see the final world
(src/seon/turn.clj:1206–1230). A stale carried value or digest alone does
not establish that guarantee.

Current predicate binding retains live Vars (src/seon/schema.clj:143–162).
The stamp identifies declaration/program inputs, not physically immutable
callable roots during reload. Step 1 preserves this behavior; step 3 keeps
predicate changes in invalidation and adoption checks. Capturing old roots
would change hot reload and is not silently authorized here.

### 2.4 Writer cost and proof boundary

Section 6(2) is the precise split. Acquire declaration compilation,
storage/component plans and compiled entity validators once per generation
(and installed native schema where relevant). Required-member validation
itself remains final-state work; only its compilation moves earlier.

The sketch's **one seek per touched owner** promise is removed. Child-only
edits, reparenting, swept refs and identity removal reach other owners;
complete values may need many indexed seeks. Keep bounded before/after
reach and measure empty seeks, successful edges, assembly and validator
execution before changing selection (src/seon/db.clj:3513–3657).
No owner mirror or implicitly capped pull.

Historical final validation was 43.97% of application and 34.38% of
application+commit. Removing all measured entity-validator calls would
save at most 650.491 ms / 1.96% of the latter; removing every final check
would cap savings at 11.438 s but break the mission. A hypothetical
25–50% reduction of owned-check time saves 2.42–4.84 s, not a measured
delta (accepted review, writer-share table).

This review ran no canonical population commit or deletion regressions.
Step 4 must measure comparable before/after data and pass canonical armed
deletion/component/relational tests **before removing any check**.
That implementation proof is an acceptance condition, not an unresolved
dependency-source claim.

### 2.5 Instrumentation

malli.instrument/instrument! selects Vars from supplied :data (default:
m/function-schemas), replaces roots through m/-instrument and retains
originals (reference-code/malli/src/malli/instrument.clj:21–43,152–162).
The actual source is **.clj**, not .cljc.

m/function-schemas is global state; m/=> registers into it. collect! reads
:malli/schema metadata; default CLJ collection walks public Vars
(reference-code/malli/src/malli/core.cljc:3060–3116;
reference-code/malli/src/malli/instrument.clj:47–61,136–150).
This global registry cannot own independent cluster generations.

Keep existing JVM tooling integration/restoration, but pass the generation's
retained function schema explicitly to m/-instrument. It accepts compiled
schemas and defaults to input/output/guard scopes
(reference-code/malli/src/malli/core.cljc:3118–3143,2550–2563).
Seon's wrapper retains typed refusal, evidence caps, facet policy,
recording/caller evidence, private collection and reload-safe restoration.
Ordinary validation already delegates to Malli
(src/seon/instrument.clj:735–818,899–936); normal arming already acquires
policy/defaults once (:971–1003).
Do not add a second wrapper or move per-cluster authority into global
function-schemas. Preserve refusal control flow: merely returning a map
from a Malli :report callback is not equivalent to preventing execution.

## 3. Deletion budget and retained responsibilities

| Mechanism | Estimated owner LOC removed | Retain |
|---|---:|---|
| Raw walker | Up to 216 after all callers/non-navigation semantics move | Compiled storage policy, conflicts, component widening and distinct transaction/stored/pulled grammars |
| Per-call candidate construction/ambient arities | 80–180 plus caller simplification | Explicit projection and retained roots |
| Per-declaration allocation/discarded compile | 40–100 | Admission diagnostics/cycle/role/exemption policy |
| Repeated dependency traversal | 30–80 | Existing graph and distinct role-aware visits; fold-contract-validations is a reducer, not the recursion to delete wholesale |
| Runtime reconstruction/fallback | 250–450 | One explicit durable acquisition loader and transaction candidate advance |
| Duplicate validator/explainer caches | 40–100 | Products Malli does not cache |

Spans overlap. The former schema.clj-under-2,000 target is an aspiration,
not a supported estimate or permission to remove contracts. One compilation
owner does not mean one textual m/schema occurrence: selector schemas and
function contracts remain legitimate acquired products.

## 4. Migration order and re-costing

Estimates exclude queue time and test data. File counts include tests.
Steps sharing owners are serial; these estimates are not parallel grants.

| Step | Cost | HEAD-loading sequence and proof |
|---|---|---|
| **1 Registry: compile once, seal, carry** | Delete 100–220 LOC, add 120–250; **2–4 lane-days**, approximately 46 files including the 44-file candidate value API census, schema/internal.cljc and fn_test.clj. Most caller edits are mechanical. | Retain roots in existing projection keys, use existing carriers, convert every two-argument and forms-map caller, then remove old arities/providers in the same coherent slice. Require changed production namespaces before commit; canonical armed schema/config/instrument and affected caller tests. No stamp resource change. |
| **2 Compiled walker** | Delete 350–600, add 180–350; **2–4 lane-days**, roughly 8–15 files after caller inventory. | Move non-navigation behavior and convert every schema.form caller before deleting that file in the same slice. Verify all active type/property combinations, aliases, local refs, conjunction conflicts, component, tuple/secondary-only and codec parity. |
| **3 Carry only; durable stamp** | Delete 300–550, add 180–350; **3–5 lane-days**, roughly 12–20 files plus stamp declaration. RESET NEEDED. | Declare stamp with loaded writer/reader consumers in one publication; explicit construction remains until reset; then require stamp for ordinary acquisition. Convert runtime rebuild callers before retirement; retain durable loader. Prove fork/restart/sequential declarations/stale candidate refusal/temporal origin/adoption. |
| **4 Remove duplicate native write checks** | Delete 80–180, add 40–120; **2–3 lane-days**, roughly 3–6 files. | Measure and prove native/final-refusal parity first; every removal names its surviving authority. Keep logical and tuple checks. Require DB owners and run canonical final-state regressions. |
| **5 Instrument retained contracts** | Delete 40–100, add 30–80; **1–2 lane-days**, roughly 3–5 files. | Feed existing wrapper compiled contracts, convert all callers before removing old compilation/caches; preserve private arming, refusal-before-body, SCI parity and restoration. |

**Step 3 research (2026-09-20, `ea98f6b52`):** the edit specification, seam census (125 owner spans, 32 files), stamp survival/restart measurements and the seven-part adoption proof are in [step3-carried-projection-2026-09-20.md](../research/step3-carried-projection-2026-09-20.md) (sections "Exact production changes required by step 3" and "Adoption proof the implementation must pass"). Two findings bind the step-3 lane: metadata-only selection is falsified (`with` can retain stale projection metadata, so the stamp resolves at the origin, never from retained metadata), and a replacement candidate recompiles its compiled dependents. Live evidence the same day: with publication paused, `bin/seon init` derived its projection from the OLD current-src rows (`projection-from-rows`) and refused a stricter admission rule than the stored population satisfied — the rebuild-from-rows dead-end step 3 retires.

**Step 4 research (2026-09-20, `d701b750a`):** the Datahike-vs-ours refusal inventory (38 native cases; 22 admitted reports with matching final diagnostics), the measured cost split and a conservative diet prototype are in [step4-validator-measurement-2026-09-20.md](../research/step4-validator-measurement-2026-09-20.md). Medians on the canonical population: application 36,112 → 15,711 ms, validator 29,009 → 9,843 ms, owner discovery 24,374 → 3,821 ms — but small and agent writes SLOWED under the bulk owner scan, so the scan is NOT the universal writer; the step-4 lane keeps selective indexed discovery for small writes and first probes Datahike's indexed `dbi/search` `[nil attribute child-id]` form (source-grounded, unmeasured) in place of the public `d/datoms` reverse lookup. Two native gaps are kept as ours and never silently widened: a missing numeric peer target passes both validators today, and inconsistent native component declarations pass this path; a general peer-existence rule is a separate scope decision. Its exact production slice and gate are in the note's "Exact production slice and gates".

Total: **10–18 lane-days**, gross deletions **870–1,650**, additions
**550–1,150**, before tests. The first step is larger than the original
estimate because value-arity removal reaches 44 files, plus the acquisition-helper caller and internal owner.

Lanes run bin/test-fast with their explicit paths and affected namespaces,
never cold gates. The orchestrator supplies path-limited cold and platform
proof and observes relevant live adoption. Derive arming counts from the
canonical population; **1,365 is not a maintained roster**.
Remove the unsupported full-publication-under-30-s target: the historical
population application plus commit was 33.269 s. Measure phases and preserve bounded progress/
liveness rather than raising bounds to conceal work.

## 5. Review disposition

Malli pin: 606083c5c5b388e84d169c7080af33ed3ec242ae.
Datahike pin: e11845bac78e1241bca0766ddc07d978bd63d74a.
Working HEAD observed: fc23a08b8ba365a653ae42ae5f4d3da807724151.
Foreign dirty render/test files were preserved; no foreign breakage blocked
the successful scratch loads. No worktree or cluster was used.

| Original claim | Resolution |
|---|---|
| Lazy/mutable registry can simply be sealed | Fixed-input lazy acquisition; complete realization; final fast snapshot; captured scopes remain. Mutable-registry excluded. Sources 2.1; Probe A. |
| get registry k | mr/schema; Registry is not a map. Registry source :97; Probe B. |
| Our cache keeps entity validators | Plain validators are Malli-owned; Seon keeps assembly/plans. Core :345,:2626; generator :496; Probe B. |
| Walk APIs eliminate all Seon interpretation | Navigation is provided; storage roles/property precedence/component widening remain. Core :825,:1168,:2611,:2771; Probe A. |
| Parent :and properties reach entries | No automatic inheritance; inspect parent and children. Core :825–837; Probe A. |
| Every schema property immutable | Replaced with conditional update rules. Datahike schema :257; transaction :91,:924. |
| DB :meta means runtime metadata | Clojure metadata differs from durable record field. DB :307; writing :161; Probe B. |
| Candidate/base composite rebinds compiled parents | Recompile affected dependency closure. Core :2550; registry :54; Probe B. |
| No acquisition from rows after restart | Explicit stamped boot/branch loader retained; running fallback deleted. Writing :240–276 restores facts, not closures. |
| Datahike handles all ref/type/cardinality constraints | Precise table in 6(2), including native/logical boundary and numeric-ref/tuple limitations. Probe B. |
| malli.instrument replaces wrapper | Existing m/-instrument composition; no second wrapper/global cluster authority. Sources 2.5. |
| Fold attribution, 74k denominator, 30-s target | Unsupported claims removed; comparable historical measures retained. Production delta/parity remain step-4 acceptance. |

No original verification marker remains. Source review is complete;
implementation proof is explicitly not claimed.

## 6. Owner decisions — RULED 2026-09-20 ~10:15 UTC

### (1) Population identity

The population digest is stamped on the branch's existing source population
row at publication/adoption (one new non-identity attribute at the reset)
and carried by every acquired ordinary DB value. Missing/mismatched stamp
means typed refusal, not fallback to files or a caller's different world.
A correctly stamped branch can be reconstructed once at boot; that is
construction, not a running read fallback. See 2.3.

### (2) Precise validation split

Trust Datahike's native :schema-flexibility :write guarantees. Preserve
what it does not establish. This table refines the ruling without turning
stronger logical constraints into alleged duplicate type checks.

| Check | Datahike guarantee / source | Seon final responsibility |
|---|---|---|
| Unknown attribute | validate-attr-ident refuses uninstalled user attrs, with explicit system/meta/schema exceptions (reference-code/datahike/src/datahike/db/utils.cljc:177–194); map expansion and normalized add call it (reference-code/datahike/src/datahike/db/transaction.cljc:745,786). | Remove duplicate native-installed checks only after operation-path parity; keep storage admission and typed error normalization. |
| Native scalar type / nil | validate-val refuses nil; :write uses value-valid? against installed/implicit native spec (reference-code/datahike/src/datahike/db/transaction.cljc:33–52; reference-code/datahike/src/datahike/schema.cljc:11–61,228–235). | Trust native type; keep bounds, enums, qualified-symbol constraints, predicates, EDN-decoded shape and other Malli refinements. |
| Cardinality | Many expands individual assertions; one replaces a prior value, many accumulates a set. Multiple supplied values do not universally refuse (reference-code/datahike/src/datahike/db/transaction.cljc:718–777,786–811,539–583). | No duplicate native cardinality mechanism. Keep required presence, logical collection bounds/order/relations; empty-many emits no member datom. |
| Identity / uniqueness | Native checks and upsert (reference-code/datahike/src/datahike/db/transaction.cljc:26–31,530–538,641–716). | Do not reimplement uniqueness; keep whole-entity selection and ownership policy. |
| Ref resolution | Lookup refs and keyword identities resolve; numeric IDs pass entid without existence query (reference-code/datahike/src/datahike/db/utils.cljc:109–148; reference-code/datahike/src/datahike/db/transaction.cljc:792–794). | Keep required target/shape and final relations. Numeric ref is not a foreign-key proof; presence is not existence. |
| Tuple members | Native tuple type is vector. Heterogeneous check compares validity booleans for equality, so all-false passes (reference-code/datahike/src/datahike/schema.cljc:33; reference-code/datahike/src/datahike/db/transaction.cljc:1017–1044). Probe B confirms. | **Keep logical tuple validation** until dependency member checking is repaired and verified. This is an observed limit, not hypothetical hardening. |
| Components | Native cascade and incoming-ref sweep (reference-code/datahike/src/datahike/db/transaction.cljc:831–836,998–1015). | Complete bounded owning values, single ownership, no orphan/cycle/missing child, target schema, before/after reach. |
| Required members / deletion dial | Explicit :db/ensure expands checks but does not automatically validate all swept referrers at final state (reference-code/datahike/src/datahike/db/transaction.cljc:768–777,1132–1148). | Validate final roots, children and swept survivors; required refs refuse, optional refs may sweep; consider same-transaction repairs together. |
| Program relations | Native storage has no Seon call/ref, prepared-arity or renderer-target model. | Keep deletion blockers, arity/render relations and all declared final invariants; include affected dependents. |
| Atomic final refusal / attempted values | validate-report runs after full processing; non-nil refuses before commit; callback sees effective plus attempted data (reference-code/datahike/src/datahike/db/transaction.cljc:1206–1216,1257–1276). | Keep this callback; include tx-function output and idempotent attempted values. Verify candidate generation/base at the writer. |

Before removing any check: name its surviving authority, measure the
delta, and pass canonical armed deletion/component/relational parity.
Standalone dependency probes do not supply that proof.

### (3) Order

Bridge steps **1–2 first**. Remaining wave-1 families (test evidence,
agent/namespace/turn, deletion-dial sweep, relational validator) declare
once on the new bridge afterwards. The running config/plan lane finishes
as is. The orchestrator coordinates overlapping paths before launch;
this PRD does not authorize overwriting foreign edits.

## 7. First lane — launch verbatim on astra low

> Implement **step 1 only: registry value, compile once, seal, carry** in
> /Users/sean/src/seon, branch steward-platform.
> Read this PRD end to end, the accepted bridge review, AGENTS §§1–5 and
> the data-oriented-clojure, data-modeling, datahike, repl and
> clojure-testing skills. Owner approved option 2 and ordered steps 1–2
> ahead of remaining wave-1 families.
>
> You are not alone in the codebase. Preserve unrelated edits; do not
> revert others' work. Adjust to landed changes. The orchestrator must
> release/coordinate overlapping paths below before this slice starts.
> Do not operate another lane's session.
>
> **Own** src/seon/schema.clj, src/seon/schema/internal.cljc, the candidate
> API caller files listed below, test/seon/fn_test.clj and one step-1 landing note under
> docs/prds/steward-platform/research/. Refresh the caller inventory
> before public arity removal. Include discovered callers in the same
> conversion; name a concurrently held path to the orchestrator before
> editing it. No stamp resource, Datahike fork, writer policy or raw-walker
> change in this step.
>
> **Implement one generation-local construction path** as §2.1 specifies:
> fixed immutable provider inputs, serial canonical realization, final
> fast registry. Retain scalar schemas already compiled during admission
> and existing function contracts. Make assert-compilable-schema! use its
> supplied complete registry without copying the full population per
> declaration. Preserve diagnostics, missing-reference evidence, cycle,
> role and exemption policy. Build/materialize/incremental paths converge
> on this owner; no parallel compiled cache.
>
> Captured construction scopes may retain Malli memoization but never
> read mutable definitions, environment state or files. Use mr/schema,
> not get. Recompile changed declarations and their dependent closure;
> a candidate/base overlay does not rebind compiled roots. Preserve
> property-local recursive registries.
>
> projection-validator and projection-explainer use retained schemas and
> Malli caches. Delete per-use recursive reconstruction only with
> explanation-path parity. Our holder keeps additional derived products,
> not duplicate plain validators. Retire candidate-validator and
> candidate-explainer; convert the current fn_test caller to
> projection-validator using its carried fixture projection. Refresh the
> caller search before removal. No global cache or new public family.
>
> Both candidate value APIs become **only**
> [projection schema-key value]. Convert every two-argument caller and
> every three-argument forms-map caller before removing old arities, all
> in one coherent slice. Acquire once at construction/operation boundaries,
> never inside a per-value loop. Running code takes existing request,
> environment or DB custody; construction explicitly supplies its
> population. No compatibility arity or map-shape dispatch.
>
> **Carry** means retained objects stay in the existing projection passed
> through current DB/environment/request holders. Stamp and ordinary
> reconstruction retirement are step 3. No new metadata mechanism,
> schema attribute or reset. Preserve live-Var predicate/reload behavior.
>
> **Prove on the canonical armed fixture:** two simultaneous disagreeing
> generations; changed leaf with dependent map/function contract; missing
> declaration refusal; local-ref shadowing; open conjunction requiredness;
> explanation paths; component value acceptance; fresh/derived isolation.
> Repeated validation/explanation must not invoke the named-declaration
> compiler or allocate full registries. Count provider calls and copied
> entries rather than impose scratch timings in CI. Derive population/
> armed counts from the fixture, not a 1,365-member constant.
>
> Convert/load every production caller before retiring arities. Before
> the coherent commit run:
>
> clojure -M -e "(require 'seon.schema 'seon.schema.internal 'seon.config
> 'seon.maintenance 'seon.cluster 'seon.turn 'seon.render
> 'seon.cluster.prompt)"
>
> Iterate with bin/test-fast --paths <all owned changed paths> --
> seon.schema-test seon.schema.edn-test seon.config-test
> seon.instrument-test seon.db-test seon.fn-test, plus affected caller namespaces
> selected from the graph/current inventory. No cold bin/test, --all or
> --full. The orchestrator owns cold/platform proof.
>
> Follow repository live verification and report hot reload vs in-place
> adoption vs an owned scratch fork. Never restart default. At foreign
> failure name the exact path/evidence and continue independent work;
> never repair its lane. No retirement commit leaves callers unconverted.
>
> **Deliver:** path-limited coherent commit, complete changed-path list,
> before/after compiler/allocation counts, canonical fast tally,
> generation/explanation evidence and exact cold/live proof still owed.
> Estimate 2–4 lane-days; most of the 44-file API census is mechanical.
> Stop before step 2 or any change to storage/validation guarantees.

### Step-1 explicit caller path inventory

Dated source search, not a permanent roster. Includes test aliases and
comment matches as well as calls; inspect before editing. Add
src/seon/schema/internal.cljc, test/seon/fn_test.clj (the acquisition-helper
caller) and the lane's landing note to this ownership.
Refresh before removing arities.

~~~text
src/seon/cluster.clj
src/seon/cluster/prompt.clj
src/seon/config.clj
src/seon/maintenance.clj
src/seon/render.clj
src/seon/schema.clj
src/seon/turn.clj
test/my/background_test.clj
test/my/edit_test.clj
test/my/fs_test.clj
test/my/message_test.clj
test/my/note_test.clj
test/my/plan_test.clj
test/my/turn_test.clj
test/my/web_test.clj
test/seon/ai_test.clj
test/seon/blob_test.clj
test/seon/bootstrap_test.clj
test/seon/cluster/armed_test.clj
test/seon/cluster/boot_test.clj
test/seon/cluster/cohost_boot_test.clj
test/seon/cluster/message_test.clj
test/seon/cluster/problem_routing_test.clj
test/seon/cluster/store_test.clj
test/seon/config_test.clj
test/seon/db_test.clj
test/seon/error_test.clj
test/seon/instrument_test.clj
test/seon/maintenance_schema_test.clj
test/seon/maintenance_test.clj
test/seon/problems_test.clj
test/seon/program_test.clj
test/seon/render/data_test.clj
test/seon/render/hiccup_test.clj
test/seon/render/root_pull_test.clj
test/seon/render/web_test.clj
test/seon/schema/edn_test.clj
test/seon/schema/program_test.clj
test/seon/schema_test.clj
test/seon/sci/documentation_test.clj
test/seon/sci/eval_test.clj
test/seon/test/accretion_test.clj
test/seon/turn_test.clj
test/seon/turn_work_test.clj
~~~

Inventory command: rg -l 'valid-candidate-value\?|explain-candidate-value'
src test (then sort). This census is not permission to edit matching
comments unnecessarily.

## 8. Reproducible scratch probes and measured results

Both complete forms below were passed as the argument to **clojure -M -e**
in a single scratch JVM at a time, with a 90-second external process bound.
No cluster, connection, file-backed store, test gate or production edit.
Probe A loaded current Seon source; Probe B used storeless Datahike values.
They verify the dependency assumptions and can falsify the proposed
interpretation; they do not substitute for canonical armed fixtures.

The command runner was equivalent to:

~~~python
import subprocess, time
# code is the complete Clojure form quoted in A or B below.
started = time.monotonic()
result = subprocess.run(["clojure", "-M", "-e", code],
                        text=True, capture_output=True, timeout=90)
print(result.stdout)
print(result.stderr)
print("EXIT", result.returncode, "WALL", time.monotonic() - started)
~~~

### Probe A — compiled traversal over three real declarations

The small interpreter deliberately covers these declarations, not all
storage encodings. The allow-set controls traversal of entity/base refs;
it is probe input, not a proposed production roster. Component widening
comes from the current compiler; preserving it is an explicit step-2
obligation. A first exploratory run exposed the compiled inst? alias;
the corrected interpreter below normalizes it. That failed run's generated
error report was removed.

~~~clojure

(require '[malli.core :as m] '[malli.registry :as mr]
         '[seon.schema :as s] '[seon.schema.edn :as edn]
         '[seon.schema.datahike :as bridge] '[clojure.set :as set])
(let [forms (edn/packaged-forms)
      calls (atom {})
      registry (mr/lazy-registry (m/default-schemas)
                 (fn [k r] (when-let [f (get forms k)]
                             (swap! calls update k (fnil inc 0))
                             (m/schema (s/compilable-form f {}) {:registry r}))))
      roots [:seon.ns/ns :seon.error.occurrence/occurrence :my.fs/error]
      compiled (into {} (map (fn [k] [k (mr/schema registry k)])) roots)
      allowed (conj (set roots) :seon.error/base)
      entries (fn [schema]
                (let [found (atom {})]
                  (m/walk schema
                    (fn [node _ _ _]
                      (when (= :map (m/type node))
                        (doseq [[k v] (m/entries node)]
                          (swap! found assoc k
                            {:optional (:optional (m/properties v))
                             :schema (first (m/children v))})))
                      node)
                    {::m/walk-schema-refs #(contains? allowed %)
                     ::m/walk-refs #(contains? allowed %)
                     ::m/walk-entry-vals true})
                  @found))
      unwrap (fn [node]
               (loop [node node props {}]
                 (let [props (merge (m/properties node) props)]
                   (if (m/-ref-schema? node)
                     (if (= :seon.db/ref (m/-ref node))
                       {:node node :props props :ref? true}
                       (recur (m/deref node) props))
                     (if (= :and (m/type node))
                       (recur (first (m/children node)) props)
                       {:node node :props props})))))
      value-type (fn value-type [schema]
                   (let [{:keys [node ref? props]} (unwrap schema)
                         t (m/type node)]
                     (cond ref? :db.type/ref
                           (= t :or) (or (:seon.db/value-type props)
                                        (let [ts (set (map #(try (value-type %) (catch Exception _ :unsupported))
                                                          (m/children node)))]
                                          (when (and (= 1 (count ts)) (not (ts :unsupported))) (first ts)))
                                        :db.type/string)
                           (= t :enum) (if (every? keyword? (m/children node)) :db.type/keyword
                                          (throw (ex-info "unsupported enum" {})))
                           :else (or ((assoc bridge/malli-type->datahike-type 'inst? :db.type/instant) t)
                                     (throw (ex-info "unsupported type" {:type t}))))))
      derive (fn [k]
               (let [{:keys [node props ref?]} (unwrap (mr/schema registry k))
                     many? (contains? #{:set :vector :sequential} (m/type node))
                     child (if many? (first (m/children node)) node)
                     vt (if (or ref? (:seon.db/component props)) :db.type/ref (value-type child))]
                 (cond-> {:db/ident k :db/valueType vt
                          :db/cardinality (if many? :db.cardinality/many :db.cardinality/one)}
                   (:seon.db/identity props) (assoc :db/unique :db.unique/identity)
                   (:seon.db/unique props) (assoc :db/unique :db.unique/value)
                   (:seon.db/component props) (assoc :db/isComponent true)
                   (:seon.db/index props) (assoc :db/index true)
                   (:seon.db/no-history? props) (assoc :db/noHistory true))))
      p {:seon.schema.projection/forms forms}]
  (doseq [k roots]
    (let [es (entries (compiled k))
          expected (into {} (map (fn [a] [a (bridge/malli->datahike-attr-in p a)])) (keys es))
          actual (into {} (map (fn [a] [a (derive a)])) (keys es))
          errors (into {} (filter (fn [[a v]] (not= v (expected a)))) actual)]
      (println :root k :type (m/type (compiled k)) :attributes (count es) :mismatches errors)
      (assert (empty? errors))
      (when (= k :my.fs/error)
        (assert (contains? es :seon.error/message))
        (assert (contains? es :my.fs/error-path))
        (println :facet-required-message (not (:optional (es :seon.error/message)))
                 :facet-required-path (not (:optional (es :my.fs/error-path)))
                 :root-properties (select-keys (m/properties (compiled k)) [:seon.db/attributes])
                 :child-types (mapv m/type (m/children (compiled k)))))))
  (doseq [k [:seon.ns/name :seon.ns/requires :seon.ns/aliases :seon.error/operation :my.fs/io-observation]]
    (println :attribute (derive k)))
  (println :provider-max-calls (apply max (vals @calls)) :provider-keys (count @calls))
  (let [resolved (mr/schemas registry)
        sealed (mr/fast-registry resolved)
        before @calls]
    (assert (identical? resolved (mr/schemas sealed)))
    (doseq [k roots] (dotimes [_ 10] (m/validator (mr/schema sealed k)) (m/explainer (mr/schema sealed k))))
    (println :sealed-enumeration-identical true :provider-counts-unchanged (= before @calls)
             :nested-options-retain-builder (identical? registry (:registry (m/options (mr/schema sealed :my.fs/error)))))))
(shutdown-agents)

~~~

Observed stable stdout (exit 0; total process wall **24.738 s**, including
JVM/dependency load, not a validation microbenchmark):

~~~text
:root :seon.ns/ns :type :map :attributes 10 :mismatches {}
:root :seon.error.occurrence/occurrence :type :map :attributes 26 :mismatches {}
:root :my.fs/error :type :and :attributes 16 :mismatches {}
:facet-required-message false :facet-required-path true :root-properties #:seon.db{:attributes true} :child-types [:malli.core/schema :map]
:attribute #:db{:ident :seon.ns/name, :valueType :db.type/symbol, :cardinality :db.cardinality/one, :unique :db.unique/identity}
:attribute #:db{:ident :seon.ns/requires, :valueType :db.type/symbol, :cardinality :db.cardinality/many, :index true}
:attribute #:db{:ident :seon.ns/aliases, :valueType :db.type/ref, :cardinality :db.cardinality/many, :isComponent true}
:attribute #:db{:ident :seon.error/operation, :valueType :db.type/symbol, :cardinality :db.cardinality/one, :index true}
:attribute #:db{:ident :my.fs/io-observation, :valueType :db.type/ref, :cardinality :db.cardinality/one, :isComponent true}
:provider-max-calls 1 :provider-keys 61
:sealed-enumeration-identical true :provider-counts-unchanged true :nested-options-retain-builder true
~~~

This realizes **61 requested keys**, not the complete population. Full
canonical realization is step 1's acceptance condition. Ten repeated
validator/explainer acquisitions for each of the three retained roots did
not call the provider again. The facet's optional message stays optional
and its path stays required; the probe does not invent stronger
requiredness.

### Probe B — registry, cache, metadata and native refusal boundaries

~~~clojure

(require '[malli.core :as m] '[malli.registry :as mr] '[malli.generator :as mg]
         '[datahike.db :as db] '[datahike.api :as d])
(let [r (mr/fast-registry (merge (m/default-schemas) {:probe/leaf :int}))
      base (m/schema [:map [:probe/x :probe/leaf]] {:registry r})
      overlay (mr/composite-registry {:probe/leaf :string :probe/base base} r)]
  (println :compiled-base-rebound? (m/validate :probe/base {:probe/x "x"} {:registry overlay})
           :base-still-int? (m/validate :probe/base {:probe/x 1} {:registry overlay})))
(let [s (m/schema :int) r (mr/fast-registry {:probe/x s})]
  (m/validator s) (m/explainer s) (mg/generator s)
  (println :malli-cache-keys (sort (keys @(m/-cache s))))
  (println :registry-is-map (map? r) :registry-get (get r :probe/x) :registry-schema-identical (identical? s (mr/schema r :probe/x))))
(let [schema {:probe/id {:db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
              :probe/generation {:db/valueType :db.type/string :db/cardinality :db.cardinality/one}
              :probe/ref {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
              :probe/n {:db/valueType :db.type/long :db/cardinality :db.cardinality/one}
              :probe/t {:db/valueType :db.type/tuple :db/cardinality :db.cardinality/one :db/tupleTypes [:db.type/string :db.type/long]}}
      raw (db/empty-db schema {:schema-flexibility :write :attribute-refs? false})
      carried (with-meta raw {:probe/compiled :opaque-runtime-object})
      attempt (fn [tx] (try (d/with carried tx) :accepted (catch Exception _ :refused)))
      after (:db-after (d/with carried [{:probe/id "population" :probe/generation "generation-1"}]))]
  (println :record-metadata (meta after) :persistent-meta-field (:meta after))
  (println :stamp-query (d/q '[:find ?g . :where [?e :probe/id "population"] [?e :probe/generation ?g]] after))
  (println :unknown-attribute (attempt [{:probe/unknown 1}])
           :wrong-native-type (attempt [{:probe/n "wrong"}])
           :missing-numeric-target (attempt [{:probe/ref 999999}])
           :all-wrong-tuple-members (attempt [{:probe/t [42 "wrong"]}])))
(shutdown-agents)

~~~

Selected stable stdout (exit 0; total process wall **22.069 s**):

~~~text
:compiled-base-rebound? false :base-still-int? true
:malli-cache-keys (:explainer :generator :validator)
:registry-is-map false :registry-get nil :registry-schema-identical true
:record-metadata #:probe{:compiled :opaque-runtime-object}
:stamp-query generation-1
:unknown-attribute :refused :wrong-native-type :refused :missing-numeric-target :accepted :all-wrong-tuple-members :accepted
~~~

The metadata line also printed the separate persistent :meta field with
Datahike version/id/creation facts; volatile UUID/time and expected native
refusal logger lines are omitted here. Compiled metadata was not in that
field. Querying generation-1 verifies the proposed stamp as a datom in
the resulting DB; persistence/fork ordering is established by the cited
writing source, **not** by this storeless probe.

The numeric-ref and heterogeneous-tuple results verify concrete limits
of native checks. They are recorded here within this lane's only writable
artifact; this review neither fixes the dependency nor removes the Seon
checks that cover them.

### Landing boundary

Only this PRD was edited. No authored scratch files, worktrees, stores or
probe JVMs remain. Source/skill authorities read for the accepted review
remain its grounding; this follow-up read the complete original PRD and
the cited dependency seams. Foreign dirty files included
src/seon/schema.clj, src/seon/cluster/source.clj, src/seon/render.clj,
resources/seon/schemas/seon.source.edn and render/config/test work; they
were read as evidence, never edited or restored. No foreign load failure
occurred in the successful probes, so no fallback snapshot was needed.
Source lines and the caller census describe this review's working snapshot;
refresh them before implementation in the moving shared tree.

Documentation diff/marker/citation checks are the landing verification.
No bin/test or bin/test-fast was run for this documentation-only review.
Canonical armed parity, a measured population delta, the cold gate,
platform proof and live adoption are still owed by implementation and
the orchestrator in the order specified above.
