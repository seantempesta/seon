---
type: research
status: audit complete; accepted review integrated; ordered implementation pending
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

## Accepted design after independent review

The orchestrator accepts every finding in
[the independent review](astra-review-kondo-fidelity-2026-09-23.md), commit
`8cdcb5710`. **Choice 1 is ruled: declaration identities and symbolic reach.**
Occurrence components and full-export storage are out of scope, not pending
implementation alternatives. The census and field ledger remain historical
observations; the implementation contract below supersedes the original six
unordered design changes and the narrower def-body owner-repair design.

Kondo supplies values; the existing row constructors and writer compose them.
Additional projection is O(changed definitions + usages + keywords), retaining
O(declarations + distinct edges). Repeated uses coalesce under declared set
semantics. No per-call analysis, second lint pass, cache, namespace pseudo-function
or raw-export database is introduced. Existing repeated span searches and global
selection setup remain separately measured costs, not claimed incremental work.

### Ownership, selection and exceptional records

Ordinary def/defonce initializer ownership comes directly from `:from` and
`:from-var`. Retain these declaration identities without requiring arglists.
Do not turn a declaration into an armable function, variadic analysis stub,
callable UI entry or interpretable initializer. Make `:seon.fn/arglists` optional
in its schema in the same loadable slice as its producer and affected consumers.
Preserve honest binding/contract checks: a contracted callable factory result
without supported source bindings must reach an explicit signature refusal,
not manufactured arity evidence (`program.cljc:1066–1073`). Preserve loaded
unchanged reuse; affected unsafe reconstruction refuses through the existing
host-bound boundary without repeating initializer/defonce effects.

One row represents one var identity, not each definition record. Coalesce a
`declare` with its completing definition without changing the row identity or
losing attached data. Competing bodies refuse by name. Generated constructor,
protocol and type identities are classified by kondo's defining-form facts.
A:443–482 also attributes quotes, Java and method/protocol bodies: keep existing
dispatch reach until equivalence is proven. Choice 1 does **not** preserve both
raw per-usage owner provenance and dispatch provenance. That would require the
rejected occurrence-storage scope. Ordinary raw-owner retention must not erase
method-body reach.

Unknown-namespace records are not all unresolved application vars. Use kondo's
actual analysis families, fields and findings to separate Java/special-form/
generated non-var records from unresolved vars. Name and count deliberate
non-var exclusions. Preserve unresolved names in their diagnostics; genuinely
unsupported completed admission refuses. Do not qualify unknown names, infer
from spelling, add a parser, or blanket-refuse every record without a finding.
Locationless/generated and method/protocol examples are required controls.

**First consumer boundary: refuse incomplete selection.** At each reached target,
seek incoming unresolved file/ns dependencies. If their dependent set is not
proven, return the existing selection boundary's `:seon.test/selection-error`
with `:seon.test/selection-refusal :seon.test/coverage-unknown`; never return a
smaller successful vector. The reverse-closure boundary must carry a declared
unknown through F `gate-set-in`/`gate-sets-in`/`gate-sets` and `seon.test/select`,
not disguise it as `:seon.db/invalid-read-error`. A small optional error arm in
`resources/seon/schemas/seon.fn.edn`'s reverse-closure/result contracts and the
existing `resources/seon/schemas/seon.test.edn` selection error are the schema
owners; convert all callers that currently assume a successful closure. The
`:seon.test/reach-unknown` stored result marker is not itself a selection result.

Remove the same-file fallback from positive Datalog `tested`/test-first evidence
and execution-selection joins together with that refusal. Complete graphs retain
all real old paths and add initializer paths; incomplete graphs refuse rather
than silently excluding formerly selected tests. “Selection widens, never
excludes” does not require keeping fabricated positive coverage. No test ×
unresolved-target cross product and no observed-reach shortcut. More selected
tests are an acceptable correctness cost; measure edge count, selected identities,
selection time/memory and execution cost separately.

Namespace-owned edges eventually attach to the existing namespace entity, but
F:1532/1565's walker admits qualified fn/test symbols, whereas namespace names
are often unqualified. **The first slice does not widen that node contract.**
It detects a reached file/ns unknown at the boundary above. The namespace slice
stores existing symbolic relations on `seon.ns` and uses existing program identity
pairs for internal owner lookups, preserving fn/test closure contracts. It still
refuses where namespace dependents are unproven. Full namespace traversal is not
required to land honest refusal. `:as-alias` remains a no-load binding.

`:seon.ns/source` stores the ns form, not every top-level expression. Namespace
edges do not supply replayable top-level source, and an unchanged namespace
digest does not prove unchanged top-level effects. Unknown executable dependencies
refuse unsafe loaded reuse through B2's existing boundary.

### Reanalysis, identities and evidence transition (P1-1)

Changing F/A's projection does not automatically reanalyze unchanged files:
`cluster/source.clj:178–207` widens for analyzer configuration, not arbitrary
producer edits. Publish producer/schema consumers, then **explicitly reproject
the affected captured population on a branch of the live store through canonical
publication**, reconcile complete rows, lint required callers, advance the
published head and adopt through the normal target writer. Record the selected
paths and authority: the canonical declared source roots/path population of that
publication, captured with its bytes/config/resolution inputs. The old index
cannot enumerate declarations it omitted, so one full declared population may
be necessary. No arbitrary repository walk, touched-file trick or reset.

Before full-population migration, the small fixture must prove the installed
publication entrance can force reanalysis despite equal file digests. If it
cannot, fix that owner first (slice 4 below); do not invent an installed flag.
Keep selective acceptance closed on unproven old analysis until migration and
expanded-evidence validation complete. Use existing analysis/input evidence and
the selection refusal, not a new durable migration stamp. Price elapsed time and
memory using the committed B1 measurement script and selected input size; obtain
owner authorization before an operation estimated above ten seconds. Reuse valid
kondo resolution cache entries. This is a one-time analysis-semantics migration,
never the steady-state edit path.

Three identities must remain separate:

| Population/change | Row identity | `program/definition-digest` expectation | Separate file signature / evidence |
|---|---|---|---|
| Unchanged callable/test with added calls/references/keywords | same | same: graph facts excluded | current expanded reach obligations must be compared with tested reach; own-digest equality cannot reuse insufficient green |
| Unchanged literal with its existing semantic fields retained | same | same | existing controls stay stable |
| Existing literal/defmulti whose manufactured `arglists "()"` is removed | same | intentionally changes | enumerate affected class and revalidate signature consumers |
| Newly retained nonliteral def/defonce or generated declaration | new identity only where absent | new digest | new public symbols change namespace member in F:1378's per-file signature fingerprint |
| Declare plus completing definition | same var identity, data preserved | digest of completing semantic definition; no artificial declare body | deterministic coalescing; competing bodies refuse |
| Namespace with only added dependency edges | same | same if source/resolver meaning unchanged | public-symbol population may change file signature despite same ns digest; top-level replay remains unproven |
| Resolver context or effective metadata correction | same | intentionally changes | current callers and tests revalidated |
| Schema rows making arglists optional / adding ns edges or result arms | same schema-key identity | schema definition changes | participate in schema-reference invalidation and adoption |

These are acceptance expectations, not measured results. Implementation records
actual parent/slice digest pairs for each class and any exception before landing.
`program.cljc:358–396` excludes calls, references, call-arities, keywords, writes,
host-bound and analyzed-source facts. Do not add graph observations to its digest
to force invalidation. Preserve file/agent equality from `3cbf5a135`.
F:1378's per-file `declaration-digests` fingerprint is a different value; do not
conflate it with definition identity. `seon.test/select`'s current reach/input
comparison (including `runner/reach-digests`) must reject older insufficient
green even when the test's own digest is unchanged; prove this, do not assume it.
Schema adoption is a transaction on a live-store branch preserving rows/private
data, not a from-zero boot. B1's historical RESET wording does not apply.

### Ordered loadable slices and failing regressions

Order is **S1/S4 integration prerequisite → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8**.
Slices 6–7 are conditional deletions: a disproved equivalence closes that deletion
candidate with evidence and retained owner, not a workaround. Every code slice
has a **100-added-line ceiling including its regressions**; the allocations below
are design budgets, not claims of measured diffs. Report actual added src, added
test and net lines. If a coherent boundary does not fit, stop before production
edits and return a smaller existing-owner composition; never split a schema from
its consumer or publish a knowingly unsafe intermediate state. Fixture coverage
is added in its slice, not deferred to a final green.

| Slice | Complete loadable change / intended owners | Budget src+tests | Regression that fails without the change |
|---|---|---:|---|
| 1 — ordinary owners and honest refusal | F row admission/file-agent projection; fn optional-arglists and closure result schema; existing program/analyzer stub, SCI host-bound/callability consumers; test selection unknown boundary. Keep exceptional declaration categories explicitly incomplete; no namespace traversal or parser deletion. | 55+45 ≤100 | Canonical three-file F/memoized-g/test retains g and its F reference, selects real test but not unrelated test on complete graph; separate-file ownerless dependency returns coverage-unknown and cannot provide test-first coverage. Cases for direct initializer, defonce/delay/map, stable accepted controls, file/agent parity, unchanged loaded reuse and affected unsafe replay/signature refusal. Exact owner/edge assertions prevent all-test widening from passing. |
| 2 — exceptional declaration identities | F coalescing/eligibility and existing safety consumers, same schemas; leave implementation reach equivalent | 45+55 ≤100 | Declare+definition retains one identity and attached data; competing definitions refuse. Type/record constructors, protocol var/methods and defmulti retain expected identities without fabricated callability. Defmethod/protocol-body and locationless/generated controls keep their existing reach; non-var unknowns remain valid while unresolved application vars stay diagnosed. |
| 3 — namespace relation ownership | F namespace row projection, seon.ns schema and same closure boundary; identity-pair lookup only for namespace owners | 45+55 ≤100 | A top-level resolved usage is queryable on the namespace; reached uncertain dependency still refuses selection rather than disappearing. No qualified pseudo-function. Existing ordinary paths survive; alias-only binding has no load edge, and unknown top-level executable dependency cannot justify unsafe reuse. |
| 4 — explicit migration and evidence | Existing source publication/reconciliation entrance if equal-digest force is missing; current selection/reach evidence owner only if proof exposes a gap | 50+50 ≤100 | On a live-store fixture branch, unchanged source bytes gain missing declarations/edges; old identities and private/agent data survive. Digest table expectations hold. An old green over the smaller graph is rejected despite equal test digest. Repeat with no changed inputs reuses valid work. This supplies the safe migration entrance; do not run the whole population yet. |
| 5 — signature-scoped caller validation | F `signature-attributes`/`caller-files` and existing publication query; no new lint scheduler | 40+60 ≤100 | Initializer direct-call arity change finds its caller file; reference-only privacy change revalidates its holder; namespace-owned usage is included when its target signature matters. Body/doc/edge-only edits lint zero dependent files. Assert changed-file and caller-file identities separately. |
| 6 — symbol-scan parity/deletion | F:2314 mentioned/known-symbol producer, using adapted usages plus separate schema-value inputs | 35+65 ≤100 | Before deleting scan, fixture expected resolved known-symbol sets agree for ordinary and alias-qualified quoted data, unresolved names and schema values. After deletion same expected set and publication result; a missing quoted/schema target fails independently of the removed implementation. If sets differ, retain scan and record semantic decision, no guessed replacement. |
| 7 — metadata parity/deletion | F:1402 indexer digest input only; leave other signature consumers intact | 35+65 ≤100 | Effective metadata and file signature fingerprint agree for form/head/name metadata, leading/trailing attrs, private/doc/arglists overrides, namespaced keywords and reader branches. Then assert expected fingerprints after deletion. Equality or an explicitly reviewed semantic correction is required; field-name similarity is insufficient. |
| 8 — migrate and prove adoption | Canonical publication operation from slice 4, no new production mechanism; owning landing evidence | 0+≤60 if a remaining adoption regression is needed | Authorized captured population reprojected, required callers linted, head advanced, target writer adopts; independently observe loaded behavior/arming and expanded test evidence. Exact removal/replacement retracts old initializer edge and adds new; unchanged files reuse valid inputs. No reset and no claim that code landing alone backfilled old rows. |

Slice 1 is intentionally the coherent first boundary requested by the review,
not a promise that its allocation has already been demonstrated. If inspection
finds more than 55 source lines plus 45 test lines are needed, implementation
must stop and reprice this boundary; reducing tests or deferring unsafe consumers
is not an acceptable way to meet the ceiling. Slice 2 completes the remaining
declaration families; choice 1 is complete only after namespace ownership,
migration, caller validation and end-to-end evidence, not at slice 1's first green.

Caller-lint dependency policy for slice 5: retain `b0d047021`'s transaction-report
signature trigger. Direct calls read identity, actual/display arity, relevant
contract and macro/private/inline/defining-form/constant status already selected
by F:2344. Reference-only uses need target existence/name resolution, privacy and
macro/semantic status where kondo consumes it; do not add argument-arity lint to
an ordinary non-invocation reference. Test the actual kondo findings. Namespace
resolution changes revisit affected bindings/usages through the existing owner.
Extend calls-only file lookup to the necessary references/ns holders for these
signature facts, not for every graph/body/docstring change. Missing/corrected
bindings and target deletion remain explicit admission work.

Retain A:496 literal/var-quote parsing, F:229 namespace binding parsing, method/
protocol attribution, source binding extraction and Malli contract decomposition.
Fixed/variadic counts cannot replace argument names, destructuring or the
binding-to-contract join (`program.cljc:968,999,1066`). The field ledger's
“re-derivation” labels are candidates for proof, not permission to delete these.

### Combining slice 1 with the stopped b1-adoption remainder

Read-only diff inspection confirms the held F remainder adds `reconcile-call`
and `adoption-tx` near :3524, routes `index!` through the former, and removes
request-supplied transaction-data concatenation. It does not fix admission at
:332/665 or usage projection. `tmp/orchestrator/file-ownership.md` still holds
F, cluster/issue consumers and adoption tests for the stopped b1-adoption lane.
This document neither releases those paths nor resumes/messages that lane.

**Orchestrator integrates and proves S1/S4 as its own prior slice.** Use its full
held-file change and required regression, not a partial-hunk commit of F. Prove
writer-head reconciliation, exact retractions, first adoption across changed
adoption arity, and rows→load→arm→record failure semantics. Its current uncommitted
compatibility is static evidence only, not a runtime pass. Price that prerequisite's
whole added-src/test diff under the same small-slice rule before integration.

Then transfer exact file ownership and base fidelity slice 1 on that landed
commit. Its complete desired declaration/edge rows flow into `reconcile-call`;
`adoption-tx` copies the published identities through the target's writer.
Preserve expected-head guards, current-head reconciliation, source/agent data
separation and exact removal. Do not restore the removed transaction hook,
introduce another adoption writer or revert the stopped remainder. Combining
means this producer→existing writer data flow and shared regression evidence,
not concatenating two unproved dirty changes into one claimed green. Slice 1's
publication fixture must exercise that integrated path and preserve unrelated
agent/private state. Any S1/S4 failure belongs to that prerequisite owner.

### Prior review rulings and proof boundary

Pending-test admission remains D1: durable visibly pending tests may precede their
callees, but admission/reacquisition must not compile or execute their bodies.
Ordinary functions and completed publication remain strict. Pending tests cannot
merge before resolution, arity/privacy and ordinary checks pass; typos remain
visible. Preserve prior-basis test-first evidence and the same-batch negative case.
No stub, new test-first detector, blanket `:warn`, or observed-reach replacement.

Per implementation slice use canonical small fixtures, real SCI, armed contracts
and one focused `seon.test/run` request via `bin/test-check CLUSTER --ns ...`.
Root owns cold/platform proof. Compare the committed B1 measurement probe on
parent/slice: files analyzed, caller files, added distinct edges, selected tests,
selection latency/memory and execution time. Reverse reach follows reached edges
plus installed global setup; any future all-test widening costs the eligible
suite and cannot masquerade as positive coverage. Investigate >1 s; fix unexplained
>20% or 50 ms regression. >10 s needs issue evidence and explicit authorization.
No fixture rebuilds the entire source tree merely to prove one behavior.

This update is documentation-only static integration of an accepted review.
Foreign dirty src/test/dependency files are the implementation boundary; none was
edited, tested, loaded, adopted, messaged or resumed. No new census, JVM, scratch
store, worktree, test gate or push. Historical runtime/census measurements above
remain historical. Local reads/checks and the document edit completed below one
second per shell tool call; no runtime performance or implementation pass claimed.

### Accepted finding → design change

| Finding | Changed part |
|---|---|
| P1-1 | Added explicit equal-file-digest reanalysis/migration, three-identity digest table, schema-reference invalidation and old-green rejection; slices 4 and 8 and slice 1's optional-arglists conversion. |
| P1-2 | Named coverage-unknown selection error and closure consumers; first slice refuses incomplete file/ns reach without broadening qualified-symbol nodes; slice 3 uses real namespace owners. Preserved conservative selection and top-level replay limits. |
| P1-3 | Ruled choice 1 only; ordinary then exceptional owners in slices 1/2, no provenance components; retained dispatch reach, classified non-var unknowns, and added honest factory-signature/callability/replay boundaries. |
| P1-4 | Signature-scoped caller matrix and slice 5; parity-gated deletions in slices 6/7; explicitly retained namespace/literal/quote parsing and binding/Malli analysis. |
| Accepted prior findings | Kept three-file/unrelated-test controls, file/agent parity, unsafe replay refusal and D1 pending-test completion/test-first boundaries. |
| First-slice/S1–S4 finding | Added ordered ≤100-line budgets including regressions, prerequisite integration/proof and exact desired-rows→reconcile-call→adoption-tx composition; no release of stopped lane's files. |


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

## Exact paragraph to fold into B1

Destination: `docs/prds/agent-platform/plan/lane-b1-one-publication-path.md`,
**“Graph fidelity before selective test execution”**, after its final paragraph
and before §6. Add the paragraph below; it supersedes the narrower def-body
owner-repair design without changing that section's declared-dispatch rules.
This assignment does not edit B1 itself.

> **Kondo declaration/usage fidelity (accepted review `8cdcb5710`; supersedes the def-body owner-repair design):** Implement choice 1 in the ordered, independently loadable, at-most-about-100-added-line slices (regressions included) in [the fidelity design](../../../research/agent-platform/kondo-analysis-fidelity-2026-09-23.md#ordered-loadable-slices-and-failing-regressions): first integrate and prove the stopped b1-adoption S1/S4 writer remainder; then (1) retain ordinary def/defonce owners with optional arglists, file/agent parity, honest signature/callability and unsafe-replay refusal, and coverage-unknown at the existing selection boundary instead of false same-file coverage; (2) coalesce declare/definition identities and retain generated families without losing method/protocol reach; (3) retain real namespace-owned symbolic relations, refusing unproven dependents rather than inventing qualified namespace functions; (4) prove explicit equal-file-digest reanalysis and expanded-evidence invalidation on a live-store branch; (5) extend signature-triggered caller validation only for the direct/reference/namespace dependencies those signatures affect; (6–7) delete symbol and metadata rereads only after semantic/fingerprint parity; and (8) perform the priced, authorized one-time captured-population migration through canonical publication, required caller lint, head advancement and normal adoption, observing load and arming separately. Preserve existing row identities and attached data; graph-only changes do not alter definition digests, while the separate file signature and current expanded test obligations may change, so an older insufficient green cannot be reused from equal test digest alone. Use desired rows with the integrated reconcile-call/adoption-tx writer path, never a second writer or the removed request transaction hook. Unknown namespace reach refuses successful narrowing and never provides positive tested/test-first evidence; namespace edges do not supply replayable top-level source. Keep binding/Malli analysis, literal/var-quote/ns-binding parsing and implementation attribution until equivalence is proved; classify kondo non-var/generated records rather than blanket-refusing unknown-namespace entries. No occurrence store, full-export database, new cache, extra initializer walk, reset or whole-program analysis on ordinary edits. The separate D1 visibly pending-test completion and prior-basis test-first rules remain binding; complete publication and merge remain strict.

Co-Authored-By: gpt-6-astra (Codex)
