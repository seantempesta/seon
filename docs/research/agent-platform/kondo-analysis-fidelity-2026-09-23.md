---
type: research
status: audit complete; fix design awaits independent review and implementation
created: 2026-09-23
tags: [agent-platform, clj-kondo, analysis, publication, fidelity]
---

# Kondo analysis fidelity: retain declarations before deriving reach

Kondo already resolves declaration ownership and usage targets. Preserve those
values at the existing indexer boundary; derive callability, execution policy and
reach separately. A missing arglist is not a missing declaration.

## Finding and scope

**The initializer hypothesis is confirmed. The stronger claim that the public
analysis export already contains every required semantic fact is false.**
The missing-owner defect is in Seon's projection, not a missing initializer walk
in kondo. Namespace binding details, literal-initializer safety, runtime invocation
through data and actual execution require more than the exported usage map.

This is the broader replacement for the owner-retention portion of
[def-body-index-gap-2026-09-23.md](def-body-index-gap-2026-09-23.md), incorporating
[its review](astra-review-def-body-index-2026-09-23.md). It does not replace the
separate pending-test admission proposal or claim B2 can replay arbitrary initializers.
B1's owning specification is
[one publication path](../../prds/agent-platform/plan/lane-b1-one-publication-path.md).
No src/test edit, publication, adoption, test execution, reset, new JVM, worktree,
provider request or push was performed.

Source citations below name the captured checkout, observed at HEAD
`98fb9b15d2ccdf18500df9b3828d75b6e1b9cfb3`, with unrelated dirty edits preserved.
`A` = `src/seon/fn/analyzer.clj`; `F` = `src/seon/fn.clj`;
`K` = `reference-code/clj-kondo/src/clj_kondo/impl/`.
The captured indexer dirty change is in reconciliation below the audited row
constructors; none of that lane's files or sessions was changed.

History read: `339573209` fixes `:as-alias` load edges; `b0d047021` restricts caller
lint to signature changes; `3cbf5a135` changes declaration digests; `7ba203acf`
changes cache ownership. The existing issue
[defined-by loss](../../seon/issues/the-indexer-drops-clj-kondos-defined-by-so-generated-declarations-look-authored.md)
still says open, but its claimed absence is no longer current: F:687 stores
`:seon.fn/defined-by`. This note extends the existing analysis-loss class; it does
not repeat that obsolete cause or edit another owner's issue authority.

## Evidence and reproducibility

The actual loaded dependency is the maintained fork at
`57252e07975710aa579b24f0d1b2b1e04195caa2` (deps.edn:16). Its upstream base is
`794a508d`, release 2026.07.24. The two fork commits change metadata keyword
resolution, not initializer ownership. `K/analyzer.clj:1852,1889,1899` binds
`:in-def` before analyzing the initializer; `K/analysis.clj:19,39` exports it as
`:from-var`. These seams exist upstream. Metadata fidelity added by the two fork
commits must not be advertised as an upstream guarantee.

The analysis was run **once**, in the existing JVM, using `clj-kondo.core/run!`.
Its resource URL pointed at this checkout's pinned dependency. Native CLI version
was inspected but not used for the census. Sources were copied before lint into
`tmp/kondo-fidelity/captured/{src,test}`; the census and literal classification
use those same bytes. `source-hashes.json` records every captured input digest.
These are captured file values, not a claim of an atomic Git snapshot.

The exact options were:

```clojure
{:lint ["/Users/sean/src/seon/tmp/kondo-fidelity/captured/src"
        "/Users/sean/src/seon/tmp/kondo-fidelity/captured/test"]
 :lang :clj :repro true :cache false
 :config-dir "/Users/sean/src/seon/.clj-kondo"
 :cache-dir "/Users/sean/src/seon/.clj-kondo/.cache"
 :config publication-config}
```

`publication-config` was derived from the captured A:23–56: arglists, usages,
Java usages, protocol implementations, symbols, keywords, deep var/namespace
analysis and full metadata; the same seven publication linters were disabled.
The repository config additionally enables locals and instance invocations.
Cache was disabled to avoid publishing captured scratch paths into the shared
namespace resolver cache, the policy A:572–582 uses for non-checkout inputs.
Thus **same analysis/linter configuration, isolated cache behavior**: this is not
an exact cached production-resolution benchmark. External resolution may differ;
no false-positive lint or runtime interpretation claim follows from these counts.
The in-batch initializer ownership finding does not depend on that difference.

Saved output: `tmp/kondo-fidelity/analysis.edn`, 116,688,337 bytes, SHA-256
`f95767338984bf3f347fcb0aef66e0c4b42ee476a15ca20fe5c320875391f301`.
Captured F SHA-256 `7d9150e3fa55e4c05c7e2def18b8b1aff1fd50014c3a20c7bb66e2234dfbf58f`;
A SHA-256 `2a1cf2ea7d234c3f939f4bca53567223ec07418f1c3199023be20913a2ec9dfe`.
Disposable reductions and full results: `count.clj`, `counts.edn`, `fields.md`,
`missing.clj`, `missing.edn`, and their stdout/stderr files in that directory.
The reduction evaluates only the captured pure projection helpers, not the
indexer or tests. Rewrite-clj supplies the literal/var-quote source classification;
that classifier is diagnostic, not a replacement production parser.

Timing: capture 28 ms; lint 3,833.95 ms (kondo summary 3,832 ms), complete MCP
expression including serializing the 116.7 MB result 5,825 ms. Summary: 434 files,
0 errors, 908 warnings, 11 infos; this is not a clean-warning result.
Whole-tree work is proportional to all captured bytes and occurrences, expressly
requested for this audit. **Do not repeat this as the incremental production
path; replace whole-program work with changed-input work.** The envelope's armed
profile named only profiling overhead: `seon.profile/cells` 11 ms,
`adder?` 7 ms, `accumulator?` 2 ms; it did not attribute kondo's internals.

**Measurement defect:** the first reduction failed on a delimiter after 7.03 s,
RSS 1,158,840,320 bytes; the corrected complete reduction took **12.09 s**, RSS
1,460,191,232 bytes. It reread a 116.7 MB EDN value, parsed all source again and
performed the adapter's repeated span searches. This exceeded the ten-second
rule; it is not excused as cold work. Do not rerun that reduction: retain the
result, narrow subsequent queries, and reuse captured reductions in future audits.
The narrower missing-identity correction took 7.34 s, RSS 1,176,780,800 bytes;
its remaining cost is reading the large export and classifying literal defs.
No runtime latency or heap-delta claim is made from these subprocess RSS values.
All owned subprocesses exited; evidence is retained, with no disposable store.

## Headline loss census

Counts are records unless explicitly called identities/edges. JVM excludes
`:lang :cljs`, as A:173 does. Protocol impl exports lack a language discriminator;
109 is the export count, not a independently proven JVM-only population.

| Observation | Raw, all language arms | JVM projection |
|---|---:|---:|
| var definitions | 7,652 | 7,391 |
| var usages | 155,824 | 151,623 |
| namespace definitions | 441 | 434 |
| namespace usages | 4,149 | 4,121 |
| definitions accepted by current row predicate | — | 6,863 |
| rejected definition records | — | **528** |
| distinct identities with no accepted definition | — | **400** |
| missing-owner definition records (including duplicate identity) | — | 401 |
| def/defonce records rejected | — | **386** |
| usages with an actually missing declaration owner | — | **1,597** |
| usages from def/defonce without arglists | — | **1,598** |
| usages without `:from-var` | 9,756 | **9,320** |
| macro usages | 47,808 | 46,563 |
| defmethod target usages | 47 | 29 |
| unknown-namespace target usages | — | 186 |

There are 895 JVM definitions without arglists, including 365 accepted literal
`def` records and two accepted defmultis. Do not equate no-arglists with omitted.
There are 751 distinct def/defonce identities without arglists.
The rejected 528 records comprise 308 def, 78 defonce, 129 declare, 9 deftype,
3 defrecord and 1 defprotocol. Of those declares, only **2** records lack a
retained definition of the same identity. A naive join against every rejected
record reports 4,796 usages; that is **not** the missing-owner count. The correct
identity-set difference yields 1,597. No top-level source-span inference was
needed to establish the raw initializer ownership.

Definition variants in the JVM export:

| `:defined-by` | Records | Current treatment |
|---|---:|---|
| def | 674 | 365 literal + 1 arglisted retained; 308 omitted |
| defonce | 78 | all omitted |
| defmulti | 2 | retained by explicit exception |
| defprotocol | 5 | four methods retained; protocol var omitted |
| deftype | 18 | nine constructors retained; nine type declarations omitted |
| defrecord | 9 | six constructors retained; three record declarations omitted |
| declare | 129 | record omitted; most identities also have a definition |
| deftest / defspec | 2,107 / 1 | test rows |
| defn / defn- / defmacro | 1,304 / 3,056 / 8 | retained |

`defmethod` is a **usage**, not another var-definition. The adapter associates
its implementation body with the multimethod using spans; the 109 protocol
implementation records similarly affect attribution. Their implementation
identities and dispatch detail do not survive as durable occurrence facts.

Using captured pure owner/edge functions plus equivalent literal/quote parsing,
the lexical projection produces **62,149 call pairs**, **31,712 reference pairs**,
**63,516 call/arity tuples**, **2,303 file/target fallback pairs**, and **41,871
qualified keyword owner pairs**. These are **deduplicated projected edges**, not
raw occurrence counts or a completed transaction. The adapter also adds quoted
symbols: 153,754 JVM adapted var usages versus 151,623 raw usages. These counts
exclude metadata/schema-derived invocation augmentation, capability edges,
contract facts and any existing database declarations outside the batch.

## Read-only default comparison

`bin/seon status`: PID 48902, start `2026-09-23T18:07:01.344Z`, source archive
`ce73846828a5cc32798ef630b5a574f777646f30`, hook publication off. MCP runtime_status
reported alive, 16 error signatures, 34 errored receipts and one failed run;
no claim that default is clean. Tools were available. No foreign repair attempted.

JVM probes used namespace `audit.kondo-fidelity`, explicit root
`/Users/sean/src/seon`, cluster `default`, `read_only true`, 2,000 ms bound and
one `@(seon.cluster.boot/connection "default")` per query. No default Var changed.
Stored rows: **4,752 fn**, **2,107 test**, **435 namespace**. Stored relation
holders: calls 6,365; references 6,854; fallback files 390; invokes 6.
Stored pairs: **62,148 calls**, **31,683 references**, **63,393 call-arities**,
**41,979 keywords**, **2,303 unresolved file references**, **8 invokes**.
Do not subtract these older-program edge totals from the checkout's raw usages.

The edge-count form (168.48 ms body / 171 ms envelope) was:

```clojure
(let [database @(seon.cluster.boot/connection "default")]
  (into {}
    (map (fn [a]
           [a (seon.db/q
               '[:find (count ?v) . :with ?e :in $ ?a
                 :where [?e ?a ?v]] database a)]))
    [:seon.fn/calls :seon.fn/references :seon.fn/keywords
     :seon.fn/call-arities :seon.fn/unresolved-references :seon.fn/invokes]))
```

The first probe used count without `:with ?e`; that counts distinct target
values, not edges, and its target totals are discarded. It correctly counted
holders and pulled the four examples (232.50 ms / 235 ms). Explicit pulls of
`seon.fn.analyzer/roots-of`, `seon.sci.kernel/process-guard`,
`seon.config/defaults`, `seon.print/node-generator` all returned nil. Captured
kondo definitions contain each identity and the initializer's attributed usages.
This corroborates missing rows in default; it does not prove checkout adoption.

## Where output becomes rows

A:111–156 projects selected fields, A:443–482 repairs attribution, A:608–636
filters languages and drops whole analysis families. F:332 and F:351 filter
owners. F:606–739 builds test/function rows; F:1251–1288 calls those constructors.
F:1411–1423 moves orphan references from namespace staging maps to file rows.
F:1488–1492 and F:1572–1575 connect those file fallbacks only to same-file tests.
A separate test file therefore cannot traverse a missing initializer owner.

| Analysis concern | Stored destination | Disposition |
|---|---|---|
| declaration namespace/name | `:seon.fn/sym`, `:seon.fn/ns` or `:seon.test/sym`, `/ns` | transformed; only accepted definitions |
| source coordinates/text | `:seon.fn/file`, `/form-span`, fn/test `/source` | transformed to byte span and exact source; name span dropped |
| arglists | `:seon.fn/arglists` | serialized strings; absent becomes `()` in admitted exceptional rows |
| metadata/doc/privacy/macro | `/spec`, `/doc`, `/private?`, `/macro?`, selected domain attributes | transformed; arbitrary metadata not stored as facts |
| real fixed/variadic arities | later contract/signature facts | adapter keeps, row constructor drops; do not confuse with overridden display arglists |
| owner + resolved target | `:seon.fn/calls`, `/references` | sets lose occurrence, location and usage details; rejected owner goes to file fallback |
| arity | `:seon.fn/call-arities` | target/arity set, nonmacro calls only |
| `:seon.fn/invokes` metadata | `:seon.fn/invokes`, augmented calls | explicit declared semantics; not kondo's instance-invocations |
| qualified keyword + owner | `:seon.fn/keywords`; span-derived `/writes` | sets; unqualified and ownerless keywords omitted |
| namespace usages | no direct row consumer | normalized then ignored; source reread builds requires/aliases/refers/imports |
| Java usages | `:seon.fn/host-bound?` | classification only, no durable class/method occurrences |
| protocol impls | usage owner repair | no implementation record survives |
| quoted symbols | reference candidates | category erased; quoted data is not evidence of execution |
| locals/local-usages/instance-invocations | none | DROPPED in adapter |

The appendix lists **every observed exported key** and count, including fields
lost in the adapter before row construction. Not emitted with this config:
java-class-definitions, java-member-definitions, var callstack and opt-in context.
Their absence is not a Seon storage-loss count. Findings are separate from
`:analysis`: A:42, F:1334 store type/level/message/row/col/file/optional owner as
`:seon.lint/*`; finding end positions and language detail are not stored there.

## Re-derivations and what can actually be deleted

| Owner | Repeated work | Replacement judgment |
|---|---|---|
| A:443–482 `attributed-usages` | scans/sorts spans per usage; fills absent owners, overwrites method/protocol owners; folds quoted symbols into usages | ordinary def ownership already supplied; trust nonnil `:from-var`. Keep method/implementation join until producer exports implementation ownership. Do not delete all attribution repair blindly. |
| A:496–519,592 `parsed-facts` | reparses files after lint for var quotes and literal defs | raw usages lack var-quote and literal-def flags. Cannot replace with an existing exported key. If still needed, emit these at kondo's existing parse/analysis owner in maintained fork, then delete second parse after producer regression. Literal safety remains distinct from admission. |
| F:229–282 `namespace-context` | reads ns form and reconstructs require/alias/refer/rename/import bindings | ordinary requires/aliases available in namespace-usages; imports have Java usage `:import`. Full refer-all/rename and alias load policy are not a complete plain exported binding map. Retain semantic parser until producer exposes those facts. |
| F:285–298 `qualify-schema-symbols` | postwalk resolves schema symbols using aliases/refers/core | kondo has resolved usage tokens, not a canonical Malli schema. Cannot substitute usages for arbitrary quoted schema data. Malli still owns canonicalization. |
| F:1230–1242 `analysis-rows-by-file` | parses all files to discover map-valued invocation targets | declared attribute→target association is not exported by keywords/symbols separately. Avoid this pass when no invoked attributes; do not infer actual invocation from a quoted symbol. |
| F:2314–2320 `analyzed-artifacts` | another full parse/walk for mentioned qualified symbols | ordinary/quoted symbol discovery can use var-usages + symbols; schema forms already handed in remain separate inputs. Delete the source parse for that purpose. |
| F:1402 → signature.cljc:39 | rereads each source to extract declaration metadata for digest | use analyzed `:meta` with explicit parity for trailing attr maps, symbol metadata and reader resolution. Pinned fork already improves metadata export. Delete indexer-only reread after parity proof; other signature consumers still need their own audit. |
| F:2789 → `program/with-contract-facts` | reads contracts/source signatures again | real arities supplied by kondo can replace repeated arity inference; Malli contract decomposition is not supplied by kondo. Display arglists may be overridden. No wholesale deletion justified. |
| A:757–770 and F:875 | pre-analysis source walk discovers qualified namespaces for stubs | needed before resolution; final analysis cannot bootstrap its own missing context. Prefer supplied namespace/program facts, but no second full lint to discover context. |
| A:637–754; F:741–825 | two require/prelude reconstruction paths and fn stubs | stored namespace binding facts should serve both. Current reconstruction turns aliases into `:as`, losing alias-only intent; new noncallable rows must never become variadic fn stubs. |
| A:324–377 `invoke-kondo` | initial lint, stale-cache source rebuild, then complete rerun | not guaranteed one pass: up to three run! calls. Cache invalidation is separate from data-loss repair; existing cache issue owns it. Do not add another pass for initializer ownership. |
| F:3666–3694 | caller lint after signature changes | deliberate changed-caller validation, not redundant analysis of unchanged valid evidence. Preserve B1's signature scope. |

No regex-based semantic analysis was found in these two owners. Source searches
used `rg`; there is no request to add production regex. Byte-span conversion
F:181–227 and source hashing are needed transformations, not competing analyzers.
Writes-by-writer F:480 is a join over exported spans; it only proves lexical
keyword presence within a transact call, not actual dynamic write behavior.

**`:as-alias` counterexample:** raw namespace usages have zero top-level
`:as-alias` keys, despite captured `seon.fault` and `seon.error` requiring flow
with `:as-alias`. Kondo's parser carries it in alias symbol metadata
(`K/analyzer/namespace.clj:243–262`); export at :718–729 passes alias/location,
not an explicit load-policy field. The saved EDN uses ordinary `pr-str`, so alias
symbol metadata does not survive serialization; the zero metadata count in the
reduction is not proof it was absent in memory. A plain export field would make
this boundary reliable. `339573209` correctly prevents a load edge by reading
source; replacing that parser with `:to` alone would reintroduce the defect.

## Smallest complete fix design

**Cost before code.** Additional declaration/usage projection should be
O(D_changed + U_changed + K_changed), plus exact changed source bytes; storage
O(changed declarations + distinct edges), or O(occurrences) for review choice 2. Resolver work uses kondo's existing dependency cache;
revalidation covers changed files and affected callers. Unrelated database writes
cause no analysis. The current adapter's nested span searches and gate-sets-in's
global identity/relation setup are *not* proven linear/incremental by this design.
The 12-second audit reduction is evidence against copying those scans.

**Recommended scope: graph fidelity with declared set semantics.** Every resolved
usage contributes an owner/target edge; repeated occurrences of the same edge
coalesce because Datahike cardinality-many is a set. This is a documented
transformation, not loss of reach. Every exported record must be accounted for as
retained, deliberately projected, or explicitly unresolved/unsupported. This does
not promise a lossless database copy of all kondo debugging fields.

Three concrete scope choices for the independent design review:

| Choice | Guarantee | Cost / what it gives up |
|---|---|---|
| **1. Retain declaration owners and complete symbolic reach (recommended)** | Every definition identity represented; every resolved var usage contributes a real var/test/ns-owned edge; unresolved input is visible | Smallest existing-row conversion; changed-input cost. Gives up per-occurrence navigation and a lossless copy of raw analysis. |
| 2. Add owned usage/declaration occurrence components | Also retains duplicate definitions, per-use positions, macro/dispatch and generated occurrence provenance | O(occurrences) extra storage and schema/consumer conversion; gives up full locals/Java tooling export unless separately admitted. Review before accepting that larger scope. |
| 3. Model every exported analysis family as queryable facts | Full configured export fidelity, including locals and instance invocations | Hundreds of thousands of occurrence records in this census, broader schema and retention work. No present reach consumer justifies this cost; gives up the small repair. |

Choice 1 fulfills the requested dependency-edge repair. Choice 2 is required only
if “faithful” additionally means every occurrence is independently queryable.
Neither choice may fabricate ownership, silently omit unknown usages, or call
quoted references execution. Implement reviewed, loadable cuts; do not build an
adapter framework to copy the entire export.

1. **Every definition contributes to its declaration identity.** Replace
   `function-definition?` as the row/owner admission condition in F:332,351,665.
   Retain noncallable defs, defonce, protocol/type declarations and uninitialized
   declarations. A declare plus later def contributes to ONE var identity, not
   duplicate identity rows rejected by the writer: retain the completing definition
   as its body and explicitly classify the earlier declaration as superseded.
   Multiple competing bodies refuse rather than picking one silently. Keep
   source/defining-form facts; distinguish generated declarations by `defined-by`,
   not names. Do not manufacture `()` as evidence of zero arity. Make arglist
   facts optional for declarations and keep actual arities separate from authored
   display arglists. Audit callable-only consumers before widening their input.

2. **Every usage has a real owner and an honest target.** Use raw `:from` +
   `:from-var` when present, with the retained var/test owner. For absent from-var,
   attach the usage to the existing `:seon.ns/name` entity selected by `:from`,
   retaining file/span provenance. Do not invent a callable `ns/init` Var.
   Retain unknown-namespace/unresolved usages with their original name and
   resolution diagnostic; they are not qualified resolved edges. Missing owning
   namespace or unexpected owner mismatch refuses completed admission by name.
   Explicitly retain raw ownership when implementation attribution also adds a
   dispatch/protocol relationship; never silently overwrite provenance.

3. **Project all usages through the existing relation owner.** Broaden the
   namespace row schema to admit the existing calls/references/call-arities
   attributes, and broaden their consumers to accept namespace identities.
   Reduce usages once into those sets; macro usages and non-call uses contribute
   references, arity-bearing nonmacro calls contribute calls and call-arities.
   Do not require the target declaration to exist before retaining its resolved
   symbolic name. Keep declared invocation augmentation distinct from observed
   lexical calls. Missing targets remain queryable and must satisfy the ordinary
   publication/admission rules. Existing lint rows retain diagnostics/source
   positions for unknown targets; an unresolvable usage without a diagnostic
   must produce a named refusal, never fall through `keep`. Report input,
   language-excluded, resolved, duplicate-edge and unresolved counts from this
   reduction. This is operation evidence, not another durable cache or stamp.
   Optional fields needed by real consumers (actual arities, defining form,
   namespace alias load policy) accrete into their owning schemas. Locals,
   instance invocations and other unused fields have an explicit exclusion policy
   from the ledger; no claim of lossless raw export storage. If choice 2 is later
   selected, use owned components, not occurrence UUIDs or a generic EDN blob;
   Datahike already cascades components (`db/transaction.cljc:832–836`).

4. **Namespace ownership is not positive test coverage.** Namespace-level
   dependency facts are queryable and feed load/invalidation dependency closure.
   They do not mean every function/test in that namespace executed a top-level
   expression. Remove the same-file-test fallback edges at F:1488 and F:1572;
   when a reached namespace-level dependency lacks a proven dependent set,
   coverage is unknown and admission refuses (smallest first option). Execution
   selection may conservatively widen only with an explicit reason and no new
   positive coverage edge. An unrelated test can never satisfy test-first through
   that widening. Carry namespace owners through both Datalog and reverse-walk
   consumers instead of converting them into fake functions.

5. **Convert file and agent producers together.** F:1290 `source-rows` and
   F:937 `analyzed-form` must retain the same eligible declaration ownership.
   A submitted program form with no var may have a real supplied namespace owner;
   otherwise refuse complete program admission. Disposable REPL evaluations
   remain disposable. Broader declarations do not grant runtime callability or
   safe reinitialization. F:916 and SCI consumers must mark/refuse unsafe affected
   nonliteral initializer reconstruction until B2 proves it. Existing loaded
   constant/function reuse must continue; do not replay defonce effects.

6. **Delete only proven duplicate work.** Remove arglists-based owner filtering,
   owner-loss routing and same-file fabricated coverage. Replace F:2314's source
   symbol scan and F:1402's metadata reread with exported facts after parity tests.
   Keep minimal literal/var-quote/ns-binding parsing until the dependency exports
   the missing fields at its existing producer; then remove those parses in the
   same cut. Kondo does not provide runtime data invocation semantics or Malli
   canonicalization, so those are not deletion claims. Keep schema resources and
   loaded consumers in one incremental publication, with no from-zero boot.

Proposed ownership for implementation: A, F, their canonical schema resources
(fn/test/ns and any occurrence schema only if choice 2 is selected), existing program/callability and SCI
consumers actually reached by widening, plus focused analyzer/indexer/selection
regressions. Resource/source publication and consumer conversion require an
independent review and exclusive file holds before implementation; this audit
launches no implementing lane. No net-src saving is promised before that review.
The owner-filter repair should be small; if lossless occurrence storage exceeds
about 100 added implementation lines, stop and price a smaller schema/producer
composition rather than build an adapter framework.

## Regressions and acceptance boundary

- Publish three small source/test files through the canonical fixture: F,
  `(def g (memoize F))`, and a separate test calling g. Assert retained owner,
  reference path, actual selection, and an unrelated test remaining unselected.
  Cover direct initializer calls, defonce/delay and map-held callbacks separately
  as cases; graph facts do not prove replay safety.
- Declare then define the same var; include protocol/type/record constructors,
  defmulti and defmethod. Assert one var identity, explicit declare/definition coalescing,
  correct defining-form/implementation attribution, no invented arglists, and
  no collision. Competing definitions must refuse by name.
- Two identical calls at different positions and two arities: input count two,
  documented deduplicated reach pair, correct arity tuples. Include macro usage,
  var quote, quoted data, unknown target and generated/locationless usage;
  each is retained/classified or explicitly refused, never dropped silently.
- Ownerless namespace expression in a separate file: queryable ownership,
  coverage unknown/refusal, no fabricated positive coverage from unrelated tests.
- Namespace binding parity: `:as` versus `:as-alias`, refer, rename, refer-all,
  imports, CLJC branches; alias-only creates no load edge. Metadata parity includes
  attr maps, trailing multi-arity attrs and namespaced metadata keywords.
- Replace/remove an initializer reference in changed-file publication: old edge
  retracts, new one appears, unchanged file analysis is reused.
  Positive source digest proves even empty analysis; no empty-set completion stamp.
- File/agent parity and SCI loaded-reuse versus unsafe affected-initializer refusal.
  Do not replace these with an indexer-only green or enable unresolved completed
  publication to make the test pass.

Implementation verification uses one focused `seon.test/run` request per cut on
its owned branch (`bin/test-check CLUSTER --ns ...`), real SCI, canonical fixtures,
armed contracts and exact publication/adoption evidence. Root owns the cold gate.
Measure parent/slice changed-file and changed-caller counts, elapsed time and
memory; investigate >1 s and refuse unexplained regression >20% or 50 ms.
This document provides static and read-only evidence only, not those future passes.

## Field-by-field export ledger

Counts below are **all-language key-presence counts**, including explicit nil
values. `T` means transformed/subsetted; `K` means semantic value kept on admitted
rows; `D` means no durable field. Source text retaining characters is not retention
of a queryable analysis fact. All fn/test projections are conditional on row
admission. Adapter-only retention is stated explicitly.

| Element | Key | Present | Seon destination / disposition |
|---|---|---:|---|
| `:instance-invocations` | `:filename` | 2514 | D — entire family removed by A:608–636 |
| `:instance-invocations` | `:lang` | 10 | D — entire family removed by A:608–636 |
| `:instance-invocations` | `:method-name` | 2514 | D — entire family removed by A:608–636 |
| `:instance-invocations` | `:name-col` | 2514 | D — entire family removed by A:608–636 |
| `:instance-invocations` | `:name-end-col` | 2514 | D — entire family removed by A:608–636 |
| `:instance-invocations` | `:name-end-row` | 2514 | D — entire family removed by A:608–636 |
| `:instance-invocations` | `:name-row` | 2514 | D — entire family removed by A:608–636 |
| `:java-class-usages` | `:branch` | 19 | D — removed by adapter |
| `:java-class-usages` | `:call` | 3844 | D — adapter keeps; no durable call flag |
| `:java-class-usages` | `:class` | 3844 | T — membership classifies :seon.fn/host-bound? |
| `:java-class-usages` | `:col` | 3810 | T — span ownership repair; occurrence location D |
| `:java-class-usages` | `:end-col` | 3810 | T — span ownership repair; occurrence location D |
| `:java-class-usages` | `:end-row` | 3810 | T — span ownership repair; occurrence location D |
| `:java-class-usages` | `:filename` | 3844 | T — span ownership repair; occurrence location D |
| `:java-class-usages` | `:import` | 394 | D — removed by adapter |
| `:java-class-usages` | `:lang` | 3844 | T — excludes cljs; discriminator D |
| `:java-class-usages` | `:method-name` | 973 | D — adapter keeps; no durable method fact |
| `:java-class-usages` | `:name-col` | 2899 | T — span ownership repair; occurrence location D |
| `:java-class-usages` | `:name-end-col` | 2899 | T — span ownership repair; occurrence location D |
| `:java-class-usages` | `:name-end-row` | 2899 | T — span ownership repair; occurrence location D |
| `:java-class-usages` | `:name-row` | 2899 | T — span ownership repair; occurrence location D |
| `:java-class-usages` | `:row` | 3810 | T — span ownership repair; occurrence location D |
| `:java-class-usages` | `:skip-analysis` | 991 | D — removed by adapter |
| `:java-class-usages` | `:tag` | 4 | D — removed by adapter |
| `:java-class-usages` | `:uri` | 3844 | D — removed by adapter |
| `:java-class-usages` | `:user-meta` | 4 | D — removed by adapter |
| `:java-class-usages` | `:clj-kondo/mark-used` | 12 | D — removed by adapter |
| `:keywords` | `:alias` | 1477 | D — adapter keeps; resolved keyword replaces spelling |
| `:keywords` | `:auto-resolved` | 7735 | D — adapter keeps; resolved keyword replaces spelling |
| `:keywords` | `:col` | 105784 | T — span join for /writes; occurrence location D |
| `:keywords` | `:end-col` | 105784 | T — span join for /writes; occurrence location D |
| `:keywords` | `:end-row` | 105784 | T — span join for /writes; occurrence location D |
| `:keywords` | `:filename` | 105784 | T — span join for /writes; occurrence location D |
| `:keywords` | `:from` | 105784 | T — keyword owner namespace |
| `:keywords` | `:from-var` | 80583 | T — keyword holder; ownerless omitted |
| `:keywords` | `:keys-destructuring` | 1095 | D — adapter keeps; no stored flag |
| `:keywords` | `:keys-destructuring-ns-modifier` | 406 | D — removed by adapter |
| `:keywords` | `:lang` | 4957 | T — excludes cljs; discriminator D |
| `:keywords` | `:name` | 105784 | T — qualified :seon.fn/keywords and /writes |
| `:keywords` | `:namespace-from-prefix` | 69 | D — removed by adapter |
| `:keywords` | `:ns` | 73197 | T — qualified :seon.fn/keywords and /writes; unqualified omitted |
| `:keywords` | `:row` | 105784 | T — span join for /writes; occurrence location D |
| `:local-usages` | `:col` | 96839 | D — entire family removed by A:608–636 |
| `:local-usages` | `:end-col` | 96839 | D — entire family removed by A:608–636 |
| `:local-usages` | `:end-row` | 96839 | D — entire family removed by A:608–636 |
| `:local-usages` | `:filename` | 96839 | D — entire family removed by A:608–636 |
| `:local-usages` | `:id` | 94300 | D — entire family removed by A:608–636 |
| `:local-usages` | `:lang` | 6696 | D — entire family removed by A:608–636 |
| `:local-usages` | `:name` | 94309 | D — entire family removed by A:608–636 |
| `:local-usages` | `:name-col` | 96839 | D — entire family removed by A:608–636 |
| `:local-usages` | `:name-end-col` | 96839 | D — entire family removed by A:608–636 |
| `:local-usages` | `:name-end-row` | 96839 | D — entire family removed by A:608–636 |
| `:local-usages` | `:name-row` | 96839 | D — entire family removed by A:608–636 |
| `:local-usages` | `:row` | 96839 | D — entire family removed by A:608–636 |
| `:locals` | `:col` | 41741 | D — entire family removed by A:608–636 |
| `:locals` | `:derived-location` | 41741 | D — entire family removed by A:608–636 |
| `:locals` | `:end-col` | 41741 | D — entire family removed by A:608–636 |
| `:locals` | `:end-row` | 41741 | D — entire family removed by A:608–636 |
| `:locals` | `:filename` | 41741 | D — entire family removed by A:608–636 |
| `:locals` | `:id` | 41741 | D — entire family removed by A:608–636 |
| `:locals` | `:lang` | 2978 | D — entire family removed by A:608–636 |
| `:locals` | `:name` | 41741 | D — entire family removed by A:608–636 |
| `:locals` | `:row` | 41741 | D — entire family removed by A:608–636 |
| `:locals` | `:scope-end-col` | 41741 | D — entire family removed by A:608–636 |
| `:locals` | `:scope-end-row` | 41741 | D — entire family removed by A:608–636 |
| `:locals` | `:str` | 41741 | D — entire family removed by A:608–636 |
| `:namespace-definitions` | `:col` | 441 | T — :seon.ns/source, :seon.fn/file; no ns span attribute |
| `:namespace-definitions` | `:doc` | 262 | K — :seon.ns/doc |
| `:namespace-definitions` | `:end-col` | 441 | T — :seon.ns/source, :seon.fn/file; no ns span attribute |
| `:namespace-definitions` | `:end-row` | 441 | T — :seon.ns/source, :seon.fn/file; no ns span attribute |
| `:namespace-definitions` | `:filename` | 441 | T — :seon.ns/source, :seon.fn/file; no ns span attribute |
| `:namespace-definitions` | `:lang` | 14 | T — excludes cljs; discriminator D |
| `:namespace-definitions` | `:meta` | 441 | T — context-relevant? and test markers; remainder D |
| `:namespace-definitions` | `:name` | 441 | K — :seon.ns/name |
| `:namespace-definitions` | `:name-col` | 441 | D — adapter keeps; namespace row omits |
| `:namespace-definitions` | `:name-end-col` | 441 | D — adapter keeps; namespace row omits |
| `:namespace-definitions` | `:name-end-row` | 441 | D — adapter keeps; namespace row omits |
| `:namespace-definitions` | `:name-row` | 441 | D — adapter keeps; namespace row omits |
| `:namespace-definitions` | `:row` | 441 | T — :seon.ns/source, :seon.fn/file; no ns span attribute |
| `:namespace-usages` | `:alias` | 3648 | D — no row consumer; requires/aliases rederived from source |
| `:namespace-usages` | `:alias-col` | 4149 | D — removed by adapter namespace-usage |
| `:namespace-usages` | `:alias-end-col` | 4149 | D — removed by adapter namespace-usage |
| `:namespace-usages` | `:alias-end-row` | 4149 | D — removed by adapter namespace-usage |
| `:namespace-usages` | `:alias-row` | 4149 | D — removed by adapter namespace-usage |
| `:namespace-usages` | `:col` | 4149 | D — no row consumer; requires/aliases rederived from source |
| `:namespace-usages` | `:filename` | 4149 | D — no row consumer; requires/aliases rederived from source |
| `:namespace-usages` | `:from` | 4149 | D — no row consumer; requires/aliases rederived from source |
| `:namespace-usages` | `:lang` | 57 | D — no row consumer; requires/aliases rederived from source |
| `:namespace-usages` | `:name-col` | 4149 | D — no row consumer; requires/aliases rederived from source |
| `:namespace-usages` | `:name-end-col` | 4149 | D — no row consumer; requires/aliases rederived from source |
| `:namespace-usages` | `:name-end-row` | 4149 | D — no row consumer; requires/aliases rederived from source |
| `:namespace-usages` | `:name-row` | 4149 | D — no row consumer; requires/aliases rederived from source |
| `:namespace-usages` | `:row` | 4149 | D — no row consumer; requires/aliases rederived from source |
| `:namespace-usages` | `:to` | 4149 | D — no row consumer; requires/aliases rederived from source |
| `:protocol-impls` | `:col` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:defined-by` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:defined-by->lint-as` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:derived-location` | 109 | D — removed by adapter |
| `:protocol-impls` | `:end-col` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:end-row` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:filename` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:impl-ns` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:method-name` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:name-col` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:name-end-col` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:name-end-row` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:name-row` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:protocol-name` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:protocol-ns` | 109 | T — implementation attribution; no durable implementation field |
| `:protocol-impls` | `:row` | 109 | T — implementation attribution; no durable implementation field |
| `:symbols` | `:col` | 2168 | T — span owner repair; symbol occurrence location D |
| `:symbols` | `:end-col` | 2168 | T — span owner repair; symbol occurrence location D |
| `:symbols` | `:end-row` | 2168 | T — span owner repair; symbol occurrence location D |
| `:symbols` | `:filename` | 2181 | T — span owner repair; symbol occurrence location D |
| `:symbols` | `:from` | 2181 | T — synthetic usage owner namespace |
| `:symbols` | `:lang` | 2181 | T — excludes cljs; discriminator D |
| `:symbols` | `:name` | 2181 | T — target name |
| `:symbols` | `:row` | 2168 | T — span owner repair; symbol occurrence location D |
| `:symbols` | `:symbol` | 2181 | T — qualified symbol folded into /references candidate |
| `:symbols` | `:to` | 1322 | T — resolved target or symbol namespace fallback |
| `:var-definitions` | `:arglist-strs` | 6720 | T — :seon.fn/arglists serialized |
| `:var-definitions` | `:col` | 7652 | T — exact /source, :seon.fn/file and byte /form-span |
| `:var-definitions` | `:defined-by` | 7652 | K — :seon.fn/defined-by (fn only) |
| `:var-definitions` | `:defined-by->lint-as` | 7652 | D — adapter keeps; used only for eligibility |
| `:var-definitions` | `:doc` | 2508 | K — :seon.fn/doc (fn only) |
| `:var-definitions` | `:end-col` | 7652 | T — exact /source, :seon.fn/file and byte /form-span |
| `:var-definitions` | `:end-row` | 7652 | T — exact /source, :seon.fn/file and byte /form-span |
| `:var-definitions` | `:filename` | 7652 | T — exact /source, :seon.fn/file and byte /form-span |
| `:var-definitions` | `:fixed-arities` | 6696 | D — normalized in adapter, not stored directly |
| `:var-definitions` | `:lang` | 528 | T — excludes cljs; discriminator D |
| `:var-definitions` | `:macro` | 8 | T — :seon.fn/macro? (fn only) |
| `:var-definitions` | `:meta` | 7652 | T — selected fn/test attributes and Malli /spec; remainder D |
| `:var-definitions` | `:name` | 7652 | T — fn/test qualified sym |
| `:var-definitions` | `:name-col` | 7652 | D — adapter keeps name coordinates; row omits |
| `:var-definitions` | `:name-end-col` | 7652 | D — adapter keeps name coordinates; row omits |
| `:var-definitions` | `:name-end-row` | 7652 | D — adapter keeps name coordinates; row omits |
| `:var-definitions` | `:name-row` | 7652 | D — adapter keeps name coordinates; row omits |
| `:var-definitions` | `:ns` | 7652 | T — fn/test namespace ref |
| `:var-definitions` | `:private` | 3850 | T — :seon.fn/private? boolean (fn only) |
| `:var-definitions` | `:protocol-name` | 8 | D — dropped by A:125 |
| `:var-definitions` | `:protocol-ns` | 8 | D — dropped by A:125 |
| `:var-definitions` | `:row` | 7652 | T — exact /source, :seon.fn/file and byte /form-span |
| `:var-definitions` | `:test` | 2108 | T — selects :seon.test/sym row |
| `:var-definitions` | `:varargs-min-arity` | 24 | D — adapter keeps, not stored directly |
| `:var-usages` | `:alias` | 24814 | D — adapter retains only; no durable occurrence field |
| `:var-usages` | `:arity` | 146597 | T — /calls eligibility and /call-arities set |
| `:var-usages` | `:col` | 151622 | T — used for attribution/diagnostics; usage coordinates D |
| `:var-usages` | `:defmethod` | 47 | T — implementation span attribution; flag D |
| `:var-usages` | `:dispatch-val-str` | 47 | D — adapter keeps; no durable dispatch occurrence |
| `:var-usages` | `:end-col` | 151622 | T — used for attribution/diagnostics; usage coordinates D |
| `:var-usages` | `:end-row` | 151622 | T — used for attribution/diagnostics; usage coordinates D |
| `:var-usages` | `:filename` | 155824 | T — used for attribution/diagnostics; usage coordinates D |
| `:var-usages` | `:fixed-arities` | 103718 | D — adapter retains only; no durable occurrence field |
| `:var-usages` | `:from` | 155824 | T — caller namespace in qualified owner |
| `:var-usages` | `:from-var` | 146068 | T — caller identity; omitted owner becomes file fallback |
| `:var-usages` | `:lang` | 8619 | T — excludes cljs; discriminator D |
| `:var-usages` | `:macro` | 47808 | T — routes to /references; macro occurrence flag D |
| `:var-usages` | `:name` | 155824 | T — qualified target in /calls or /references |
| `:var-usages` | `:name-col` | 142096 | T — used for attribution/diagnostics; usage coordinates D |
| `:var-usages` | `:name-end-col` | 142096 | T — used for attribution/diagnostics; usage coordinates D |
| `:var-usages` | `:name-end-row` | 142096 | T — used for attribution/diagnostics; usage coordinates D |
| `:var-usages` | `:name-row` | 142096 | T — used for attribution/diagnostics; usage coordinates D |
| `:var-usages` | `:private` | 12770 | D — adapter retains only; no durable occurrence field |
| `:var-usages` | `:refer` | 765 | D — adapter retains only; no durable occurrence field |
| `:var-usages` | `:row` | 151622 | T — used for attribution/diagnostics; usage coordinates D |
| `:var-usages` | `:to` | 155811 | T — qualified target in /calls or /references; unknown skipped |
| `:var-usages` | `:varargs-min-arity` | 59626 | D — adapter retains only; no durable occurrence field |

Net production source **0** lines; tests **0** lines. Only this document is the
landing artifact. Commit identity is supplied by the Git commit containing it.

Co-Authored-By: gpt-6-astra (Codex)
