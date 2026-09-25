---
type: independent-review
status: complete — implementation requires the fixes below
created: 2026-09-25
design: derived-contracts-2026-09-25.md
design-revision: fd3ffe68b
initial-checkout-head: 852f0d3f4849bd1afce918d65fc769f366a0eec5
verdict: implement after fixes
scope: static design and dependency-source review; no implementation or runtime proof
---

# Derived contracts — independent review

**Verdict: implement after fixes.** The principal composition is right: Malli owns
destructuring, the kondo fork owns return inference, and the producer writes the
ordinary `:seon.fn/spec`. Keep S0 and S2 as prerequisites. The blockers are the raw
tag translation, lossy union collapse, derived-callee freshness, and integration
of failures with the actual admission path. These have smaller fixes than a new
contract subsystem. No P0 is established by this static review; P1 findings below
block the proposed implementation as written.

The attractive part is what disappears: redundant authored contracts and any
Seon return-body walker. The shared-key component algorithm, speculative fork
inference improvements, and mandatory disagreement machinery make the first cut
larger and less trustworthy. Remove them from that cut.

## Evidence boundary

Reviewed the design at `fd3ffe68b`, the language requirements (especially 1.12 and
1.13), the named Seon sources and their first-party consumers, and the dependency
sources. The design file had no diff from the requested revision. The inspected
Seon source files likewise had no diff from that revision when checked. Other
lanes committed documentation during review; the initial checkout HEAD is recorded
in this review's metadata.

Dependency pins verified from the checkouts:

- `reference-code/malli`: `befcd302a8e626a67d84b80aabee0cd1b38dadb6`.
- `reference-code/clj-kondo`: `57252e07975710aa579b24f0d1b2b1e04195caa2`, origin
  `git@github.com:seantempesta/clj-kondo.git`.

All `file:line` citations below refer to those source versions. The requested
`src/seon/fn/analyzer.clj:877-899` does not exist: that file has 848 lines. Return
inference is at `reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:877-899`.
Seon's relevant export-consumer seams are `src/seon/fn/analyzer.clj:30`, `:124`,
and `:666`. This is a citation correction, not missing inference machinery.

Per the assignment, no tests, JVM probes, `bin/seon`, `bin/test-check`, or live
database queries were run. Counterexamples below are deductions from the named
algorithms, not claimed execution results. The design's P1–P13 measurements are
author-reported observations, not independent installed proof. No current runtime
health, timings, allocation, memory, or census is certified here.

## P1 findings and smallest fixes

### P1-1 — F1's raw tags do not match S3's input grammar

`types/ret-tag-from-call` returns `{:tag t}` for built-in/configured returns and
function specs (`reference-code/clj-kondo/src/clj_kondo/impl/types.clj:448-476`).
`analyze-fn-body` takes the call's `:ret` directly (`impl/analyzer.clj:877-899`);
the call registration preserves that value (`:3784-3788`). Consequently an
`assoc` tail can reach F1 as `{:tag {:type :map :val ...}}`, not the bare map tag
shown in P11's resolved-cache table. `let` and `if` also have function specs
(`impl/types/clojure/core.clj:71-78`), with argument-tag wrappers potentially
nested inside their results. S3 has no `:tag` case and would refuse these outputs.

The cache hides this difference: `types/resolve-arity` invokes
`type-utils/resolve-arg-type`, which recursively unwraps `:tag` before resolving
calls (`types.clj:882-909`; `types/utils.clj:178-191`). P11 is evidence for resolved
inference, not the proposed export. Further, `strip-positions` traverses sets and
`:val` entries only (`types/utils.clj:273-286`): it does not descend through a
top-level `:tag` or sanitize a `:call`. Calling it alone does not establish F1's
position-free output contract.

**Smallest fix:** give F1 one documented, opt-in export normalization in the fork:
recursively unwrap tag envelopes and sanitize deferred calls while retaining their
identity; keep bare map tags and union sets. This is normalization of the fork's
own data, not another inference engine. Preserve unknown/missing arities distinctly
from an inferred `:nil`. Specify whether missing returns are absent entries and
join against the declaration's complete arity set so they cannot disappear.
Alternatively S3 can unwrap envelopes, but then F1 must explicitly expose that
grammar and Seon owns more dependency-format knowledge. Prefer the fork boundary.

F1's acceptance examples must include raw exports for literal, `assoc`, nested
`let`/`if`, direct user call, call through a local, unknown branch, nil branch,
multi-arity and variadic declarations. The proposed three cases omit the exact
wrapper seam that breaks the design. Retain keyword-call markers and refuse them
under v1; stripping `:kw-calls` would falsely turn an extracted value into its
callee's whole return map.

### P1-2 — Shared-key components weaken unions

The disjoint success/error example correctly needs `[:or success error]`.
Shared keys do not establish that alternatives describe one product with optional
fields. Consider inferred key sets:

```clojure
#{:example/context :example/value}
#{:example/context :example/error}
```

S3 collapses them to a map requiring only `:example/context`. A context-only map
then satisfies the contract, although neither alternative permits it. The same
problem occurs for non-error alternatives. A transitive bridge can be worse:
`#{:a/x}`, `#{:a/x :b/y}`, `#{:b/y}` becomes one component with no required key,
admitting `{}`. The algorithm is therefore not an equivalence-preserving union
translation. Error maps may share context/subject keys with success maps; the
design's assertion that they share no key is not a guarantee.

**Smallest fix:** translate each supported union member recursively, deduplicate
equal forms, impose deterministic ordering, and emit `[:or ...]` without component
collapse. Preserve named callee outputs as named alternatives. Refuse the whole
derivation if any member is unsupported; never filter it out. Retain `:nil` as an
alternative when the tag contains it. Both maps remain open as ruled; this is not
exclusive-or and does not claim unknown extra keys are forbidden.

Optional-key compression can wait for an actual equivalence rule. It cannot
prevent kondo from constructing an exponential union earlier in the pipeline.
Bound traversal/output size and report the offending tag/arity when exhausted;
do not claim the component pass solves upstream inference cost.

### P1-3 — Copying a callee output creates a derivation dependency

Raw deferred calls are the correct choice when a callee contract must win.
`types/utils.clj:222-255` resolves calls to body-inferred cache returns; that
resolution loses the top-level callee identity and cannot enforce Seon's contract
authority. S3 should use the candidate batch's explicit contract first, then its
derived contract, then the held projection for a callee unchanged by that batch.
An old projection contract must not mask an edited or deleted candidate callee.
Use the callee's matching fixed arity or applicable variadic arity, preserving the
Malli output form and named error schemas. Explicit contracts terminate recursion;
a path-local visited set refuses genuinely unresolved cycles, not shared DAG nodes.

However, after copying `g`'s output into `f`'s ordinary spec, changing only `g`'s
contract must rederive `f` even when `f`'s source digest is unchanged. This is not
already accomplished by caller relinting. `src/seon/fn.clj:2484-2543` selects caller
files on contract changes, but `:3899-3920` analyzes them and retains **only
`:seon.lint/id` rows**, discarding their new function rows. It is not a contract
republication mechanism. The design's “once per changed declaration” cost model
and source-only inline memo key (§6.1) omit this dependency.

**Smallest fix:** resolve against one immutable candidate world in the existing
publication producer, propagate changed derived specs through the existing call
relation to the affected callers, and admit the resulting rows together. Reuse
work only when the raw tag, aliases/language and consulted callee contracts remain
content-valid. Memoize within that derivation so shared callees are not walked
repeatedly. Name the recomputation event for SCI redefinition as well as file
publication. Do not add a second dependency registry or a per-call resolver.

A configured kondo return can also replace a deferred call before F1 sees it
(`types.clj:457-476`). Demonstrate callee-contract precedence under the actual
analysis configuration, including user type hints/configured specs; do not claim
that every raw return necessarily retains a user call's name. An unsupported loss
must refuse rather than quietly prefer the structural guess.

### P1-4 — The refusal and declaration joins need a concrete producer contract

The existing `missing-contract` does **not** name an inferred tag or arity. Its
only argument is a function symbol (`src/seon/sci/eval.clj:1763-1780`).
`refuse-uncontracted-definitions!` collects missing symbols and constructs that
generic refusal (`:456-487`). S1 returning “form or refusal” without specifying
how consumers discriminate the two risks serializing an error as `:seon.fn/spec`,
dropping its evidence, or throwing before the existing rollback path.

The reader-order question in §8 can be narrowed from source: `definition-rows`
uses indexer `source-rows`, optionally overlays explicit runtime metadata, and is
materialized before the gate and arming (`eval.clj:489-530`, `:3820-3840`). The
gate consumes those rows, not the reader's partial declaration. Preserve that
ordering. Carry a derivation failure's function, arity, direction, tag and reason
to the same gate/rollback owner; ordinary success writes only `:seon.fn/spec`.
Do not derive in a reader that has no F1 evidence. Explicit metadata continues to
win without requiring a successful derived alternative.

There is also a concrete source-reader limitation. `signature/declaration-parts`
accepts core `defn`/`defn-` and a particular `defmulti` dispatch form, not anonymous
`fn`, `(def f (fn ...))`, or an arbitrary macro invocation
(`src/seon/fn/signature.cljc:62-93`). It rejects a valid multi-arity trailing
attribute map because every remaining declaration must be a list, although
`declaration-metadata` already recognizes that trailing map (`:39-60`). Thus §6's
“same declared-contract” for inline source is not established by F1, which exports
var definitions rather than anonymous function occurrences.

**Smallest fix:** specify the supported declaration join precisely, correct the
trailing-map case at its existing owner, and keep anonymous/generated-function
identity and source recovery as explicit 1.11 prerequisites. For unsupported
generated source, provide the named admission refusal or an explicit contract;
never infer from a macro placeholder or silently admit an uncontracted changed
Var. Do not count 1.11 as delivered by S1/F1. The boundary that executes a call
inside its own defining form before the post-evaluation gate remains a separately
named limit (`eval.clj:459-467`), not proof of pre-execution admission.

### P1-5 — Explicit wins; disagreement does not justify a new refusal

Keep explicit input and output together and authoritative. §3 nevertheless
proposes an immediate refusal for a closed explicit map missing a destructured
key, then promotion of all disagreement advice to refusal once the census is
zero. Neither follows from the owner's ruling. A zero census does not establish
that future disagreements are invalid. In particular a destructured key with an
`:or` default can be absent from a closed input and the function can still run;
an unused destructured local is another counterexample to “the read can never
succeed.” `program/arity-row` checks the **number of input slots**, not declared
map-key coverage (`src/seon/program.cljc:1004-1030`). It does not already enforce
the proposed rule.

**Smallest fix:** short-circuit to the explicit contract. Any later disagreement
check is advisory and must understand named schemas, unions and optional keys;
`m/deref` plus one `m/entries` call is not a general schema traversal. Defer S4.
Continue enforcing ordinary contract validity and source/arity compatibility.
Delete §2.4's pending “unknown-typed” choice: requirement 1.12 already rules that
an underivable output requires an explicit schema and otherwise refuses by name.

## Dependency claims: what survives source inspection

Paths in this table are relative to `reference-code/clj-kondo/src/clj_kondo/`.

| Claim | Static finding |
|---|---|
| Map literals retain known keys and value tags | Supported by `impl/types.clj:349-382`. Dynamic/inextractable or generated keys make the tag open. Known keys remain usable; unknown keys are not proof of absence. |
| `assoc` extends a known map | Supported for known tagged map seeds and statically captured key values by `impl/types/clojure/core.clj:132-160` and `impl/types.clj:578-596`. Do not generalize to every spelling: auto-resolved keyword argument values are deliberately not captured there. Raw output has envelopes (P1-1). |
| `let`, `if`, `when` preserve/union tails | The specs support this (`impl/types/clojure/core.clj:71-78`, `:199-205`); `union-type` propagates unknown as `:any` (`impl/types/utils.clj:62-80`). Preserve that refusal, not just known branches. |
| User-defn calls resolve in the cache | Supported by deferred call construction at `impl/types.clj:448-476`, resolution at `impl/types/utils.clj:222-255`, and `impl/cache.clj:146-169`. Cache-disabled sync still runs at `:210-215`. This is distinct from projection-contract resolution. |
| `merge` and `sorted-map` lose keys | Supported: fixed coarse return specs at `impl/types/clojure/core.clj:666-668` and `:186-190`. |
| Destructured argument `:as` plus `assoc` loses keys | Consistent with the fallback to `:associative`; `destructured-key-tag` (`impl/types.clj:384-399`) propagates knowledge from an initializer, not a Seon schema projection. F3 is new inference, not a present guarantee. |
| `cond->` loses its return | P11 reports it; static code identifies a concrete suspect: `impl/analyzer.clj:3431-3434` replaces the expression with `expand-cond->`, whose fresh outer let node at `impl/macroexpand.clj:55-85` does not copy the original expression's `:id`. `analyze-fn-body` looks up that original id. This supports investigating identity propagation before let-rebinding semantics; it is not an executed diagnosis here. |

F2/F3 are not needed to honor the refusal ruling. Keep them separate. A future
merge inference must handle unknown maps and overwrite order; blindly merging
`:val` facts is insufficient for value inference. F3 cannot mark every
destructured key present in `:as`: defaults populate locals, not the original map,
and Clojure destructuring itself does not require key presence. Any required-key
claim needs a valid authority. These are substantive proof obligations, not
established ten-line changes.

## Malli corrections and prerequisites

The three owner corrections are supported and necessary. `malli.destructure/parse`
returns a result map; use its `:schema` and `:parsed`, not that result map as a
contract (`reference-code/malli/src/malli/destructure.cljc:125-162`).

1. **Drop unqualified keys.** `-keys`/`-map` at `:76-97` otherwise emit required
   unqualified `:any` entries under the proposed options. Filter map entries by
   `qualified-keyword?` on their actual source key, dropping string/symbol keys
   as well. Do not walk arbitrary schema literals looking for keywords.
2. **`:or` keys are optional.** Malli parses `:or` at `:24` but `-keys`/`-map` do
   not apply it. Use the binding's local-to-source-key association; the existing
   `program/map-binding-entries` does that at `src/seon/program.cljc:733-773`.
   Detect default *presence*, including nil and false, rather than truthiness.
   Explicit renames and qualified `:keys` shorthand must yield the same result.
   Defaults on nested locals do not make their outer key optional. Optional
   means the key may be absent, not that a present nil satisfies a nonnil schema.
3. **Refuse positional/rest mixtures all-or-nothing.** Malli deliberately derives
   `:any` for a positional symbol, vector schemas for sequential destructuring,
   and kwargs for a rest map even with sequential maps disabled (`:99-108`,
   `:92-97`). Check parsed argument kinds/rest explicitly; a surviving qualified
   map elsewhere does not legitimize them. Every map argument must retain a
   qualified key, and one failing arity refuses the whole function unless an
   explicit schema is present. Specify zero-arity behavior explicitly; it has no
   positional/rest argument to reject.

Prefer `md/parse`'s already parsed structure plus the existing binding owner;
do not add a third destructuring parser. The local/default helper is private in
`seon.program`; putting derivation in `signature` cannot simply call it without
settling dependency direction (`program` already consumes `signature`). Move the
small pure binding mapping to a shared lower owner or use Malli's parsed data.
Do not duplicate it to satisfy the estimated S1 size.

**S0 is real and broader than a keyword crash.** `canonical-map-entry`
(`src/seon/fn/schema_shape.clj:28-36`) destructures every entry as a vector;
`[k {:optional true}]` also currently yields a nil child. Malli's `-parse-entry`
handles naked `k`, `[k]`, `[k properties]`, and explicit child forms
(`reference-code/malli/src/malli/core.cljc:487-535`). Use that interpretation.
Prove encode/decode, references, and fingerprint equality as well as compilation:
`entry-row` and `read-form` (`schema_shape.clj:257`, `:379`) also assume explicit
children. Merely reading `m/children` does not prove the shortest spelling survives
storage; the current decoder always appends a child. The naked, optional and
explicit spellings must not lose reference identity or create fingerprint drift.

**S2 is real.** `armable`, `collect-contracts!`, and the incremental `apply!` path
all filter through `mi/-schema` (`src/seon/instrument.clj:82-104`, `:815-825`,
`:906-918`). `compiled-wrapper` already prefers the projection (`:627`). Select
contracts from that same projection, preserve bound/nonprimitive checks, and
update the parity caller's inputs so `armable` knows which projection it audits.
Prove full and incremental adoption, removal of a contract, and retained profiling;
do not leave the old-wrapper path or parity calculation tied to metadata.

Storage in the ordinary spec is otherwise the right seam. `contract-facts`
compiles through `m/function-schema`, joins source and Malli arities, and stores
the normal input/output refs (`src/seon/program.cljc:1004-1115`). Call preparation
already queries required map entries by key and full shape fingerprint
(`src/seon/call_preparation.clj:864-900`). S0 must prove naked references reach
exactly those facts; no derivation-specific call path is warranted.

## Fragility and later implementation evidence

These are acceptance cases for the implementer, not tests run by this review.

| Boundary | Required behavior and source-based limit |
|---|---|
| Multi-arity | Join by arity identity, not iteration order; preserve every source arity including a missing return. Fix the trailing attribute-map reader case. Kondo's `extract-arity-info` has integer keys and `:varargs` (`impl/analyzer.clj:913-930`); Seon's existing join is at `program.cljc:910-924`, `:1085-1108`. |
| Variadic | A derived declaration containing any rest arity refuses as a whole. An explicit variadic callee can still supply a return to a fixed-arity caller; select its applicable arity with minimum bounds, not `get` on an integer alone. |
| Macro-generated / inline | Demonstrate actual changed-Var-to-analysis-row-to-authored-binding correspondence; a var-definition export alone cannot recover anonymous occurrences or arbitrary macro source. Require the 1.11 seam or refuse by name. |
| Reader conditionals | JVM source reading already selects `#{:clj}` (`signature.cljc:17-31`), and Seon's analyzer excludes `:cljs` entries (`analyzer.clj:177`). Keep tag and arglist from the same language/occurrence, including `.cljc` duplicate symbols and aliases. Fresh Seon is CLJ-only; do not price a CLJS runtime parser into this cut. |
| Schema change to a derived key | Keep the named key in the spec. Existing `reference-fingerprints` (`schema_shape.clj:119-146`), `shape-dependent-rows` (`fn.clj:3116-3137`) and `current-wrapper?` (`instrument.clj:752-766`) provide dependency-aware seams. Prove changing the key's schema with unchanged function source changes the shape/validator and re-arms consumers; deleting the key refuses. No source-only compiled-contract memo is valid. |
| Callee-contract change | Unlike a referenced key's schema update, copying another function's output needs spec regeneration. Prove the chain `f → g → h`, including same-batch edits, deletion, explicit cycle-breaking and unrelated-write reuse (P1-3). |
| Derived vs explicit | Explicit wins even when derivation is unsupported or disagrees; validate and arm that explicit contract. A named error alternative must retain its name through a delegating return. |

## Census and cost review

**P2-1 — the census cannot support the claimed savings yet.** P13's table totals
`163 + 5 + 40 + 29 + 51 + 2 + 303 + 861 = 1,454`, not 1,904. The difference is
exactly the separately reported 450 args-only arities. A plausible explanation
is that 1,904 counted all arities, but the review cannot choose that explanation
without the raw classifier output. Correct the denominator from evidence.

The candidate arithmetic `163 + 5 + 40 = 208` is correct. It is not a count of
admissible functions: outputs are resolved rather than F1 raw, inputs must also
qualify in every arity, keys must be registered, and union members must all be
supported. Mixed qualified/unqualified maps are allowed by S3 but sit outside
that subtotal. P10's `155 + 41 = 196` and `28 + 19 + 49 + 100 = 196` reconcile;
808 is a pprinted-schema-line upper bound, not a measured deletion diff. P9's
`2,099 + 2,768 = 4,867` reconciles but is a historical live-row observation, not
a source census or proof of every row's arming status.

P10 explicitly says its scanner was committed nowhere, and P11–P13 provide no
durable raw artifact paths sufficient to replay their exact classification.
The static evidence does not independently verify the 3,354 defns, 1,919 authored
contracts, 403 `cond->` occurrences, or 106 cache files. Before a shortening sweep,
retain a pinned, reproducible census reporting the **intersection** of input,
output, registry and all-arity eligibility, then the exact equality/deletion diff.
Do not remove a schema merely because it has the same keys: requiredness, named
error alternatives, guards and narrower explicit constraints also matter.

**P2-2 — reprice F1/S3 and the first cut after the fixes.** F1 runs at a real
export seam (`impl/analysis.clj:87-127`); there is no current `:arity-returns`
implementation in either fork or Seon. Its disabled path can skip traversal,
but “zero cost” is a target, not a measurement. Enabled cost is proportional to
the total exported tag nodes/bytes, not simply one small constant per arity.
Nested maps and unions need traversal and allocation. S3 adds callee resolution
and affected-caller regeneration; memoized traversal should be proportional to
the reached graph and tag sizes, not the whole program per declaration.

The ~91-line estimate omits the concrete fixes above and has no matching measured
deletions. A 2.2-second concurrent full-src run is neither an F1 delta nor a warm
changed-declaration bound; 15.5 ms for a tiny lint is not export overhead. Measure
flag-off/on time, allocations/retained bytes and export size under Seon's actual
publication configuration (`src/seon/fn/analyzer.clj:45-57` disables some inference
consumers), plus changed-declaration and affected-callee cases. Report missing
measurements as missing. Nothing in this review authorizes a long provider or
runtime run.

## Recommended hybrid and landing boundary

Keep Opus's parser choices, ordinary spec storage, mandatory underivable refusal,
and S0/S2. Replace shared-key collapse with direct union translation. Normalize
raw envelopes in F1 while preserving deferred callee names; resolve them against
the candidate contracts with content-valid dependency propagation. Apply the
three Malli corrections once at the producer. Let explicit contracts short-circuit.

Sequence the corrected design as S0/S2 prerequisite proofs, F1's documented export,
then one coherent producer/admission/publication slice for S1/S3, followed by the
measured equality sweep. Keep S4, F2 and F3 out of that slice. State the 1.11 join
as a dependency wherever inline/generated-function coverage is claimed. Preserve
the existing downstream call preparation, arming consumers, reads-key and reach.

This is small and coherent if the implementation reduces to parsing, normalization,
contract resolution and the existing writer. It is not yet priced or proven small
enough to land: the revised design must name the callee-freshness seam and measured
retirements rather than fit them into an optimistic line budget.

Review deliverable: this file only; source/test delta **0/0**. No code or skill
files changed, no runtime installation claimed, and no tests or benchmarks run.
The evidence is the pinned source analysis and explicit counterexamples above.
