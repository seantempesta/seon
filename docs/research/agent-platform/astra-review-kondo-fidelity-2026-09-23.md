---
type: review
status: ready after fixes
created: 2026-09-23
tags: [agent-platform, clj-kondo, analysis, publication, review]
---

# Independent review: kondo analysis fidelity

Kondo already supplies declaration identities and initializer ownership; Seon loses
those facts while constructing rows. Preserve the existing values and compose the
existing writer and reach queries; do not build another analyzer or infer callability.

**Verdict: ready after fixes.** Accept choice 1 (declaration identities and symbolic
reach), not occurrence storage or the full analysis export. The diagnosis and census
method support the repair. Resolve P1-1 through P1-4 below before implementation;
the proposed six numbered changes are not yet six independently loadable slices.

Reviewed [proposal](kondo-analysis-fidelity-2026-09-23.md) at `0d1d02ac7`;
its current bytes match that commit (SHA-256
`f23b69fb1b595ab79ef6c0309f134fcd477cdec6d8bbff500713ceb25a065c6c`).
Review checkout: `14a8bb1bb`, branch `refactor/agent-platform`, with foreign dirty
files preserved. Source anchors below refer to that inspected tree. `F` means
`src/seon/fn.clj`, `A` means `src/seon/fn/analyzer.clj`, and `K` means
`reference-code/clj-kondo/src/clj_kondo/impl/`.

Authorities read: AGENTS.md; agent-platform plan README §3 (the requested
“Selection is conservative” paragraph is at its lines 102–106, not root README);
[B1](../../prds/agent-platform/plan/lane-b1-one-publication-path.md);
D1 ruling `eb709fcb8`; the [narrow design](def-body-index-gap-2026-09-23.md)
and [prior review](astra-review-def-body-index-2026-09-23.md).
Applied data-oriented-clojure and clojure-testing review guidance. This is a static
review, not a runtime, publication, arming or performance pass.

## What is established

The gitlink pins clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`, loaded via
`deps.edn:16`'s local root. Comparing upstream base `794a508d` with that pin shows
metadata-related changes, not a new initializer-owner mechanism.
`K/analyzer.clj:1889` binds `:in-def` before initializer analysis;
`K/analysis.clj:19–58` exports `:from`, `:from-var`, target and optional arity.
`reg-var!` (`K/analysis.clj:87`) exports definitions independently of arglists.
These are upstream seams. The fork's metadata keyword improvements are not upstream
claims. A:119–138 preserves these fields; F:332,351,665 applies the lossy predicate.
No new initializer traversal is necessary.

Inputs are captured source, kondo configuration and its namespace resolution
context; recomputation follows changed source or analysis semantics. The additional
projection can be O(changed definitions + usages + keywords), storing declaration
rows and distinct edges. This bound does not cover A:443–482's repeated span searches
or F:1565's global identity/relation acquisition. Preserve the dependency cache;
no per-call analysis, second lint pass, new cache or raw-export database is justified.

### Census: sound category, bounded proof

I inspected `tmp/kondo-fidelity/count.clj`, `missing.clj`, `missing.edn`, the captured
producer sources and their predicates. I verified all **434 captured source hashes**
against `source-hashes.json` (zero mismatches), and independently hashed
`analysis.edn`: `f95767338984bf3f347fcb0aef66e0c4b42ee476a15ca20fe5c320875391f301`.
The combined Python hash check took **0.090 s**. I did not rerun kondo or either
Clojure reduction.

* **400** is distinct `[namespace name]` identities with no accepted JVM definition,
  not 528 rejected records. The completing definition of a declared var removes
  that identity from the missing set. The saved missing population is 401 records:
  308 def + 78 defonce + 9 deftype + 1 defprotocol + 2 declare + 3 defrecord.
* **386** is rejected def/defonce records (308 + 78), not 386 missed calls, tests
  or necessarily distinct runtime functions. Literal defonce is not recognized by
  A:512–518's literal-def predicate. The accepted 365 literal defs and arglisted
  controls explain why “every initializer is lost” would be false.
* **1,597** joins JVM raw usages to the missing **identity set**, using `:from` and
  `:from-var`. This correctly avoids the 4,796 rejected-record join overcount.
  It counts usage records, including references/macros, not lost distinct edges or
  demonstrated selection failures. The 1,598 no-arglist-def usage count is a
  different population and must remain separately labelled.

This repairs the prior review's principal reproducibility objection: classification
reads the captured bytes, not later live source. One limit remains: literal
classification uses rewrite-clj while production uses kondo's parser. Their def-head,
shape and `literal-value?` rules agree by inspection, but parser parity (especially
reader conditionals/metadata) was not executed here. Call these saved, source-backed
census results, not an independently rerun production transaction. Cache-disabled
external resolution and raw versus adapted usages also prevent interpreting 1,597
as an exact post-fix edge delta. The document already states those limits adequately.

## Findings that must change the implementation plan

### P1-1 — Declare the reanalysis and evidence transition; no reset

“Incremental publication” alone does not backfill previously omitted rows.
`src/seon/cluster/source.clj:178–207` selects all sources for analyzer **configuration**
changes, but a change to the projection in F/A is ordinarily just a selected source
change. Unchanged file bytes can therefore retain incomplete rows indefinitely.

Add a concrete migration step: publish the changed producer and schema consumers,
then explicitly reproject the affected captured source population on a branch of
the existing store through canonical publication, reconcile complete rows, lint
required callers, advance the published head, and adopt through the normal writer.
Record the selected file population and its authority. If the installed entry cannot
force this reanalysis when file digests match, fix that publication owner before
claiming adoption; do not touch files to trick the digest or reset the database.
This is one analysis-semantics migration, not a whole-program scan on every edit.
A full population may be needed once because the old index cannot enumerate what
it omitted. Price/time that operation and obtain the required authorization if it
will exceed ten seconds; reuse valid upstream resolution work.

Separate three identities explicitly:

1. Existing `[identity attribute, symbol]` row identities remain stable. Newly
   retained declarations add identities; declare/definition coalescing must not
   replace an existing var's identity or erase its attached data.
2. `program/definition-digest` (`src/seon/program.cljc:358–396`) excludes calls,
   references, call-arities, keywords, writes, host-bound and analyzed-source facts.
   Adding only those edges does **not** change an existing declaration digest.
   Removing manufactured `:seon.fn/arglists "()"`, adding semantic fields, or
   changing resolver context **does**. Make a before/after digest table for unchanged
   callable/literal/test/ns rows and every intentional changed class. Retain file/agent
   parity established by `3cbf5a135`; do not add graph observations to this digest
   to force invalidation.
3. F:1378's `declaration-digests` is a separate per-file signature fingerprint.
   Its namespace member includes public symbols, so new public declarations alter
   it even when the namespace definition digest is stable. Test evidence must use
   the current expanded dependency obligations. An old green over a smaller graph
   cannot be reused merely because the test's own digest stayed equal.

The function schema currently **requires** arglists
(`resources/seon/schemas/seon.fn.edn:143`); make them optional in the same slice
as the producer/consumer conversion. Namespace edge attributes and any honest
unknown result belong in their existing schemas. Optional additions need no new
storage identity. Their schema rows and digests do change and must participate in
schema-reference invalidation. Prove schema adoption by transaction on a live-store
branch, preserving existing rows/private data; no from-zero boot or RESET is part
of this fix. B1's older RESET-labelled table entries do not override AGENTS.md's
incremental-schema rule.

### P1-2 — Give namespace reach a concrete, conservative consumer boundary

The design correctly removes fabricated positive coverage. But “broaden consumers”
is not a complete conversion: F:1532's reverse walker contracts identities and
closure nodes as qualified symbols; F:1565 selects only fn/test identities.
Namespace names are ordinarily unqualified symbols. Adding ns-owned relation
attributes alone leaves their edges invisible to that walker. Datalog coverage
and execution selection also serve different purposes (F:1476–1505).

State the first boundary precisely: a reverse lookup reaching an unresolved
file/ns dependency without a proven dependent set returns the existing selection
boundary's declared unknown/refusal, never a smaller successful test vector.
Keep this separate from positive `tested`/test-first evidence. A later complete
namespace traversal may use existing program identity pairs internally; do not
invent qualified namespace functions or mix unqualified names into qualified-symbol
contracts. Name the affected result schemas and consumers before changing them.

Removing fallback edges and restoring real owners must land with this behavior.
For a complete graph, all real pre-existing dependency paths survive and new
initializer paths can select more tests. For an incomplete graph, removal of a
fabricated same-file path must produce explicit unknown/widening, never silently
exclude that test. “Selection widens, never excludes” is the safety rule; it does
not require perpetuating a false edge as positive coverage.

More selected tests are an acceptable correctness cost, not a regression to hide
with past observed reach. Measure added distinct edges, selected test identities,
selection time/memory, and execution cost separately. Reverse traversal costs
reached edges plus the installed global setup; worst-case all-test widening and
execution follow the eligible suite. Never materialize test × unresolved-target
edges. Namespace load dependencies must also retain `:as-alias`'s no-load meaning.

Namespace ownership does not supply replayable top-level source: F:299 stores the
ns form as `:seon.ns/source`, not every top-level expression. Do not claim these new
edges authorize isolated re-execution or make an unchanged namespace digest proof
that its top-level effects are unchanged. Unknown executable dependencies refuse
unsafe loaded reuse under the existing B2 boundary.

### P1-3 — Keep choice 1 genuinely small and define exceptional ownership

“One row per var identity” is correct; “one row per var-definition record” is not.
The proposal already coalesces declare + body and refuses competing bodies. Retain
that wording and explicit tests for generated constructor/protocol/type identities.
A declaration row must not automatically become an armable function, variadic
analysis stub, callable UI entry or interpretable initializer. F:916 currently
returns false for many nonliteral initializers; that producer and file/agent
consumers must preserve loaded reuse and explicitly refuse unsafe affected replay.
A callable factory result with a contract must also reach an honest signature
boundary: `program.cljc:1066–1073` calls `signature/function-signatures`, which
expects supported source bindings. Missing arglists cannot manufacture that proof.

Two promises exceed choice 1's currently specified storage:

* A:470–478 overwrites raw owners for method/protocol implementations. Retaining
  raw ownership **and** dispatch attribution requires an explicit relation policy;
  a flat target set cannot preserve both provenances. Keep the existing dispatch
  reach until equivalence is proven; never replace it with raw ownership alone and
  lose method-body reach. If exact per-usage provenance is wanted, that is choice 2
  and requires a separately priced decision. Do not smuggle occurrence components
  into the small fix under “file/span provenance.”
* Unknown-namespace usage is not synonymous with an unresolved application var.
  F:355's current explanation includes Java methods, special forms and gensyms.
  Classify from kondo's actual families/fields/findings; document excluded non-var
  records and refuse genuinely unsupported completed admission. “No finding, so
  refuse” applied indiscriminately risks rejecting valid source. Preserve the raw
  name in its diagnostic when unresolved; never synthesize a qualified target.

Regressions must exercise locationless/generated records and defmethod/protocol
bodies, not just ordinary defs. No inference from symbol spelling or second parser.

### P1-4 — Caller lint and parser deletions need semantic parity, not field similarity

Keep `b0d047021`'s signature-only caller-lint rule. F:2344–2407 derives changed
signatures from the transaction report and locates caller files through calls.
Restoring initializer calls adds legitimate caller files; an edge-only/body/docstring
edit must not relint every dependent. Reference-only usages can also need privacy,
macro or resolution revalidation: enumerate which signature facts their analysis
reads and extend the existing query only as required, including namespace-owned
usages. Prove direct initializer-call arity errors and reference privacy changes;
report changed-file and caller-file counts separately. Graph breadth is not itself
permission to broaden every publication lint.

Deletion ledger for this cut:

| Candidate | Decision and required equivalence |
|---|---|
| F:332/351/665 eligibility and lost-owner routing | Delete the arglist test as declaration admission now; keep actual callability/safety policy distinct. Replace fallback consumers with P1-2 in the same loadable cut. |
| Ordinary def owner reconstruction | Kondo `:from-var` is equivalent for ordinary initializer ownership. Trust it. A:443–482 also handles quotes, Java and implementation spans; those jobs are not all redundant. |
| F:2314 full source symbol walk | Candidate deletion after parity. A's adapted usages include qualified quoted symbols (`:symbols`); compare the actual resolved `known` symbol set, including alias-qualified quoted data and schema forms. Raw textual symbols and resolved usage targets are not automatically the same set. Keep the separate schema-value input. |
| F:1402 declaration-metadata reread | Not yet proven duplicate. `signature.cljc:39–62` merges form/head/name metadata, leading attrs and trailing multi-arity attrs; `K/analysis.clj:112–118` exports merged `:user-meta`. Compare effective values and the resulting signature fingerprint, including form/head metadata, namespaced keywords, private/doc/arglists overrides and reader branches. Delete only this indexer reread after equality or an explicit semantic correction. |
| A:496 parsed-facts; F:229 namespace-context | Retain for now. The export has no equivalent literal/var-quote flags or complete binding/load-policy map. Producer extensions, if needed, belong at kondo's existing parser owner and require their own parity cut. |
| Contract/signature analysis | Retain binding extraction and Malli decomposition. Fixed/variadic arities do not supply argument names, destructuring or the binding-to-contract join (`program.cljc:968,999,1066`). Kondo cannot replace all of `signature/function-signatures`. |

## Accepted prior findings remain binding

The proposal explicitly leaves pending-test admission in D1 and preserves symbolic
edges to absent qualified callees. That is correct, not an omission to solve by
weakening completed publication. Carry forward the prior review's exact completion
boundary: a durable, visibly pending test may precede its callee; its body is not
compiled/executed during admission or reacquisition; ordinary functions and completed
publication remain strict; pending tests cannot merge until resolution, arity/privacy
and other checks pass. A misspelling remains visible pending, not silently accepted.
Prior-basis test-first evidence and the same-batch negative case remain required.
No new test-first detector, automatic stub or blanket `:warn` default.

Likewise retain the separate-file F/g/test regression, unrelated-test control,
unsafe-initializer refusal, file/agent parity, and honest cost statements from the
prior review. P1-2 makes the remaining namespace transition precise; it does not
relax conservative selection or turn widening into coverage.

## First slice, combination with S1/S4, and completion proof

**First slice: restore ordinary def/defonce declaration owners with honest unknown
selection and unsafe-reconstruction refusal.** Keep pending-test admission, broad
occurrence storage and optional parser deletions out of it. Existing accepted rows
are controls. Declare/definition coalescing and generated families can follow as a
second bounded slice; until then report those remaining unsupported categories,
and do not claim choice 1 complete. No intermediate slice may silently introduce
unsafe callable/replay behavior or narrow successful selection.

Use existing F row construction and file/agent facts, the optional-arglists schema,
existing host-bound/refusal consumers, and the selection unknown boundary. Assign
exclusive exact paths only after consumer inspection; this review does not release
another lane's files. Budget about 100 added implementation lines per loadable slice,
with regressions included in the estimate/report rather than hidden. If even this
coherent first boundary exceeds that, return with a smaller existing-owner
composition before implementation; splitting a broken producer and its consumer
across commits is not a solution. No net source saving is established yet.

I inspected `git diff -- src/seon/fn.clj`. The stopped S1/S4 remainder adds
`reconcile-call` and `adoption-tx` near F:3524, routes `index!` through the former,
and removes request-supplied transaction data concatenation. It does not repair
F:332/665 or the edge projection. The fidelity fix produces complete desired rows
**for that writer-side reconciliation**, then uses `adoption-tx` to copy the published
rows through the target writer. Preserve current-head reconciliation, head guards,
exact retractions and source/agent data separation. Do not restore the removed
request transaction hook, add a second adoption writer, or revert the remainder.
The orchestrator must integrate and prove S1/S4 as its own slice before combining;
this static compatibility observation is not a pass for those uncommitted changes.

Minimum implementation proofs through canonical small fixtures and armed real SCI:

1. Three files: F, `(def g (memoize F))`, test calling g; retain g, assert its reference
   to F, assert selected test and unrelated-test exclusion for the complete graph.
   Cases cover direct calls, defonce/delay and map-held callbacks. The edge/owner
   assertions fail without this fix even if selection is naively widened to all.
2. Ownerless dependency in a separate file: explicit selection unknown/refusal or
   reasoned widening; unrelated prior test cannot satisfy positive coverage/test-first.
3. Replace/remove initializer reference through actual publication: old edge retracts,
   new edge appears, unaffected analysis reuses its valid inputs; file/agent rows agree.
   Loaded unchanged value reuse succeeds; affected unsafe initializer refuses without
   repeating effects. Assert actual diagnostics and retained state, not any exception.
4. Live-store schema/analysis migration: newly retained rows appear despite unchanged
   source bytes, old identities/data survive, digest changes are explained, expanded
   obligations cannot reuse an older insufficient green. Verify publication, adoption
   and arming separately. Second slice adds declare/generated/method attribution cases.
5. Caller-lint and parser-deletion parity cases from P1-4 accompany their respective
   cuts, with no whole-program fixture setup for one small behavior.

Implementation lanes use one focused `seon.test/run` request per cut via
`bin/test-check CLUSTER --ns ...`; root owns cold/platform proof. Measure the same
parent/slice publication probe (committed B1 measurement script), selected files,
caller files, edges, selection time and memory. Investigate every >1 s operation
and fix unexplained >20% or 50 ms regression; >10 s needs its issue and authorization.
No new JVM or scratch store is implied by these proposed proofs.

## Review landing boundary

Only this review document is changed by this assignment. No JVM, REPL request,
new census, test, bin/test gate, publication, adoption, reset, worktree or push ran.
No foreign session was contacted or resumed. Shared dirty production/schema/dependency
files are the foreign implementation boundary, not a reason to stop this docs review.
No runtime health or loadability result is claimed. Hash verification and static
source/history reads completed below one second per shell command as reported;
no Seon operation was timed. Net production source **0**, tests **0** lines.
The containing Git commit records this document's identity and documentation size.

Co-Authored-By: gpt-6-astra (Codex)
