---
type: research
status: current
created: 2026-09-23
scope: Malli schema stories, instrumentation explanations, and Seon result handles
---

# Malli as the story-teller for data and errors

## 1. Answer first

Seon already carries compiled Malli schemas in a projection and uses Malli to
validate, explain, and discover references. The smallest composition is a query
of that projection followed by Malli inspection and the existing render pair.
**VERIFIED:** `projection-explainer`, `src/seon/schema.clj:4012-4025`;
`direct-references*`, `src/seon/schema.clj:61-79`.

1. **VERIFIED — Preserve the native explanation before rendering it.**
   `m/explainer` returns the root schema, checked value, and complete error
   sequence (`reference-code/malli/src/malli/core.cljc:2647-2662`). Seon's
   `violation` and `boundary-refusal` each call `m/explain`, then construct
   different representations (`src/seon/instrument.clj:287-304,609-636`).
   Neither returns that original explanation. **UNVERIFIED proposal:** compute
   it once and retain it in the live refusal; derive humanization and links.
2. **VERIFIED — Structural schema links already build themselves.**
   Malli distinguishes a named reference from a keyword literal through
   `RefSchema`, `m/-ref-schema?`, and `m/-ref`
   (`reference-code/malli/src/malli/core.cljc:67-69,102,2029-2031,2101-2103`).
   Seon's `direct-references*` already composes this with `m/walk`.
   **UNVERIFIED proposal:** reuse it rather than indexing all keyword tokens
   as schema dependencies or adding a second schema graph.
3. **VERIFIED — A description is prose, not a reference index.**
   `m/properties` returns properties; the descriptor consumes `:description`
   as text and does not extract wikilinks or keyword mentions
   (`reference-code/malli/src/malli/core.cljc:2586-2591`;
   `experimental/describe.cljc:241-249`). **UNVERIFIED proposal:** make
   descriptions the primary explanation, structural references the primary
   automatic links, and wikilinks optional authored cross-references.
4. **VERIFIED — English descriptions and JSON export exist, with limits.**
   `describe` prefers an authored description; unknown types default to an
   empty string. Named wrappers can describe their name rather than their
   target (`reference-code/malli/src/malli/experimental/describe.cljc:52-66,241-265`).
   `json-schema/transform` preserves selected documentation and emits
   definitions, but its default type method returns `{}`
   (`reference-code/malli/src/malli/json_schema.cljc:17-43,201-222`).
   **UNVERIFIED proposal:** neither replaces the authoritative Malli form.
5. **VERIFIED — Input refusal is a reporter policy, not a return value.**
   `m/-instrument` defaults to a throwing reporter; input reports contain
   `:input`, `:args`, and `:schema`, without `:errors`
   (`reference-code/malli/src/malli/core.cljc:2201-2227,3125-3146`). A reporter
   that merely returns allows the function body to run. Seon throws its
   constructed refusal and can return it after recording under `:record`
   (`src/seon/instrument.clj:693-713`). Preserve that policy when simplifying.
6. **VERIFIED, source only — The handle retains the constructed refusal,
   not a hidden original Malli explanation.** The failure path preserves an
   existing refusal, `shown-result` returns the live value, and the turn binds
   that value (`src/seon/sci/kernel.clj:526-553`;
   `src/seon/sci/eval.clj:2965-3007,3336-3360`;
   `src/seon/turn.clj:4770-4771`). **UNVERIFIED:** actual installation and
   successful access in the running agent; no JVM probes were performed.
7. **VERIFIED — Malli already offers a data-only explanation adapter.**
   `mu/data-explainer` and `mu/explain-data` replace root/leaf schema objects
   with forms (`reference-code/malli/src/malli/util.cljc:209-232`). They do
   not sanitize arbitrary checked values. **UNVERIFIED proposal:** use this
   seam for an explicitly chosen serialization boundary, never as a reason
   to serialize every live result or discard registry context.
8. **VERIFIED — The clearest duplication is prose construction, not schema
   walking.** Seon's `schema-expectation` is 23 physical lines of schema-type
   dispatch (`src/seon/error.clj:768-790`); Malli supplies `describe` and
   `error-message`. Existing Seon reference walks already use Malli.
   **UNVERIFIED estimate:** replace generic prose and duplicated explanation
   construction with 45–80 production lines total; exact net deletion needs
   behavioral comparison, especially runtime-supplied arguments and guards.

## 2. Evidence and limits

### Scope and reproducibility

**VERIFIED:** Read-only source inspection on `refactor/agent-platform`;
initial Seon HEAD `c8dc92833fc1d2259edfded3fbb18002aab7b7b2`.
The working tree was already dirty, including `src/seon/instrument.clj`.
Its behavior below describes inspected bytes, not just HEAD.
`deps.edn:15` points to the local Malli root. HEAD's gitlink was
`8725a8cbd9d595f4a970ce53a2eefdbe7211b96d`; the inspected, clean Malli
checkout was `56394c54e34415a333d6281c4bfddf7122d02bd5`.
This is a maintained fork; fork behavior is not automatically upstream behavior.

**VERIFIED:** Recent history inspected with `git log -3 --oneline --
src/seon/instrument.clj src/seon/error.clj`: `bab838388` (error constructors),
`283ce58c9` (compile each contract once), `c9870081f` (projection/callables).
Malli HEAD adds `::invalid-schema-at-call` reporting for throwing validators;
its previous generator change `25710a67` resolves symbolic `:gen/gen`.

**VERIFIED:** SHA-256 fingerprints captured with `shasum -a 256`:

| Inspected file | SHA-256 |
|---|---|
| `src/seon/instrument.clj` | `717425eed653c48060109368f724749c6501a18c47e539e47ca4b73ab2f29f4b` |
| `src/seon/error.clj` | `224a64dbd18864cb76e2e186a5db6183cefe472373ab807b4f02833de62a5117` |
| `src/seon/schema.clj` | `9092278925e47203413ab35970e353af73f63c9ab1ff403fc9dd484fa4077f67` |
| `src/seon/sci/eval.clj` | `c03744820c91198cf3be646efc2c7b24de3f0696ec4b0bdb9bb23cace99bda82` |
| `src/seon/turn.clj` | `1396ddc71bd9291da9aa4c64c46003d593c9b6979c9a9d7aa55e0d99372c66cb` |

**VERIFIED:** Read `AGENTS.md`, the data-oriented Clojure and data-modeling
skills, the data-modeling guide, and the turn PRD §§13–15. No schemas are
proposed for installation here. The illustrative keys below are transient
research notation, not admitted declarations. Malli's own unqualified keys
remain native dependency values; any Seon declaration needs its normal review.

**VERIFIED:** One stale skill citation: data-oriented skill lines 38–39 points
`packaged-forms` to `schema/edn.clj:415`; the function is at
`src/seon/schema/edn.clj:397-401`. Left unchanged under the explicit skill freeze.

**UNVERIFIED:** Loaded Vars, arming, registry population, live result access,
latency, memory, and example outputs. No REPL forms were executed, no runtime
status requested, no tests run, and no default-system operations performed.
Source inspection is not installed proof. All sketches below are unexecuted.

### Malli inventory: exact functions and guarantees

All paths in this table start with `reference-code/malli/src/malli/`.
Each row is **VERIFIED by source inspection**, not by execution.

| Need | Function and evidence | Guarantee / qualification |
|---|---|---|
| Resolve a key | `core.cljc:2555-2577`, `schema`; `registry.cljc:97-104`, `schema`, `schemas` | A qualified key is resolved through the supplied registry. A compiled Schema is returned unchanged; passing new options does not recompile it. |
| Registry composition | `registry.cljc:54-59`, `composite-registry` | Earlier registry wins; keep the acquired projection explicit. |
| Metadata | `core.cljc:2522-2527,2586-2591`, `type-properties`, `properties` | Properties remain maps, including custom keys. A key creates a pointer: read wrapper and dereferenced target properties separately. |
| Map-entry documentation | `core.cljc:2600-2607`, `children` | Entry schemas expose `[key properties child]`; entry properties are not identical to child properties. |
| Reference identity | `core.cljc:67-69,102`, `RefSchema`, `-ref-schema?`; `2823-2837`, `deref`, `deref-all` | Guard `-ref` with the protocol predicate. `deref` follows one top-level reference; `deref-all` follows top-level aliases, not the whole tree. |
| Traverse | `core.cljc:2616-2629`, `walk`; `2008-2015,2084-2088`, reference walkers | Postwalk callback receives schema, path, walked children, options. Enable both `::m/walk-refs` and `::m/walk-schema-refs` for transitive traversal. |
| Locate schemas | `util.cljc:168-181`, `subschemas`; `189-207`, `path->in`, `in->paths`; `342-358`, `get-in` | `subschemas` gives schema/path/value-path records. Its default follows named schema references and top-level explicit refs, not every nested explicit ref. |
| English | `experimental/describe.cljc:241-265`, `-descriptor-walker`, `describe` | Schema properties override type properties; `:description` wins over generated shape prose. `:title` support is in `-titled`, line 10. |
| JSON | `json_schema.cljc:17-43,201-222`, `-ref`, `select`, `-json-schema-walker`, `transform` | Selects title/description/default, handles recursive definitions, permits `:json-schema` and namespaced overrides. Unknown types produce `{}`; arbitrary Clojure predicates are not faithfully translated. |
| Examples | `generator.cljc:494-524`, `-create`, `generator`, `generate` | `:seed` and `:size` reach test.check generation. Default size is 30; same seed alone is not a cross-version guarantee. Overrides can return invalid values; validate the example. |
| Explanation | `core.cljc:2647-2669`, `explainer`, `explain`; `impl/util.cljc:19-21`, `-error` | Invalid value gives `{:schema s :value v :errors errors}`; each ordinary error has `:path :in :schema :value`, optionally `:type`. Valid values yield nil. |
| Humanization | `error.cljc:288-305,332-337,374-390`, `error-message`, `with-error-messages`, `humanize` | Message lookup includes schema/type properties, error type, locale/default locale. Humanize arranges messages along value paths; it is a view, not the original explanation. |
| Root messages | `error.cljc:310-324`, `-resolve-root-error` | An existing no-doc resolver climbs schema paths and considers entry properties; compare it before retaining Seon's described-union rewrite. |
| Serialization adapter | `util.cljc:209-232`, `data-explainer`, `explain-data` | Converts schema objects to forms, retains other problem data. Arbitrary `:value` objects and registry dependencies still need an explicit policy. |
| Pretty reporting | `dev/pretty.cljc:20-24,51-58,164-179`, `-errors`, invalid-input formatter, `reporter`, `thrower` | Computes an explanation and messages; reporter prints, thrower throws. Terminal formatting is not the AI/HTML render contract. |
| Development mode | `dev.clj:11-23,29-64`, `-capture-fail!`, `stop!`, `start!` | Alters roots, manages instrumentation/watchers and clj-kondo output. It is not a pure story-query mechanism. |

**VERIFIED:** Explicit-ref walking uses `::walked-refs` keyed by the reference
name on a traversal path (`core.cljc:2008-2015`), whereas fork validator cycle
identity includes registry scope (`core.cljc:1943-1949`). It is not a global
visited set. **UNVERIFIED:** completeness for arbitrary locally shadowed recursive
names. A bare set of keywords loses scope and path evidence; retain occurrences
when inspecting local registries. Shared subgraphs may be visited repeatedly.
`deref-recursive` explicitly does not expand `:ref` (`core.cljc:2839-2851`).

**VERIFIED web cross-check:** upstream also has `subschemas`, `data-explainer`,
and `explain-data`; this confirms these are dependency seams, not Seon inventions.
[Upstream Malli util source](https://raw.githubusercontent.com/metosin/malli/master/src/malli/util.cljc).
The fetched source is not the pinned fork; line citations above are authoritative
for this review. **UNVERIFIED web coverage:** cljdoc describe and raw describe
retrieval failed; local describe source was available and read.

### Instrumented refusal: what is kept and lost

**VERIFIED:** `m/-instrument` defaults `:scope` to input/output/guard and
`:report` to `m/-fail!` (`core.cljc:3141-3146`). `-fail!` throws `-exception`,
whose ex-data is `{:type type :message type :data data}` (`core.cljc:203-207`).
For invalid input, the report arguments are:

```clojure
:malli.core/invalid-input
{:input compiled-input-schema
 :args argument-vector
 :schema compiled-function-schema}
```

**VERIFIED:** This is a source-derived shape, not an observed probe result.
`malli.instrument/-strument!` adds `:fn-name` when its report option is supplied
(`reference-code/malli/src/malli/instrument.clj:18-35`); Seon's
`compiled-wrapper` uses `m/-instrument` directly and adds that name itself
(`src/seon/instrument.clj:681-682,693-701`). Invalid arity reports additionally
carry arity/arities; invalid output and guard carry their checked schema/value
(`reference-code/malli/src/malli/core.cljc:2216-2225`).

**VERIFIED:** `violation` keeps the whole checked value in
`:seon.error/offending`, a form in `:seon.error/expected`, function/arm,
argument labels, problems and paths (`src/seon/instrument.clj:305-392`).
It rewrites described unions, removes some competing problems, and deduplicates
before translating them (`:288-304`). `explain-problem` converts schemas to forms
and derives expected/actual/fix prose (`src/seon/error.clj:811-861`).
This is useful teaching data but is not the original error sequence.

**VERIFIED:** `boundary-refusal` recomputes the explanation and retains every
original problem as an ordinal item with schema/value locations, expected-shape
fingerprint and projected actual value (`src/seon/instrument.clj:609-636`).
`observation-location` projects individual path segments (`:569-584`);
`project-observation` applies admission caps (`src/seon/error.clj:398-406`).
This second representation does not preserve compiled schemas or `:type`,
and always writes a humanization-unavailable string (`instrument.clj:617-625`).
The schema already allows a problem type and humanized evidence
(`resources/seon/schemas/seon.instrument.explanation.edn:22-33,46-57`).
Thus absence of humanization here does not prove Malli failed to humanize.

**VERIFIED:** The live result path is:

1. `compiled-wrapper` throws the constructed value as ex-data; record mode
   returns it only after committed recording (`instrument.clj:695-713`).
2. `failure-value` preserves a structural refusal and accretes evaluation
   evidence (`src/seon/sci/kernel.clj:538-553`).
3. The SCI failure handler passes this through `shown-result`
   (`src/seon/sci/eval.clj:3336-3360`). That function can add function docs and
   a separate error result reference, then returns the value (`:2965-3007`).
4. The turn binds `:seon.sci.admit/value`; `bind-result!` interns the actual
   object into SCI's `result` namespace (`src/seon/turn.clj:4770-4771`;
   `src/seon/sci/eval.clj:543-560`).

**VERIFIED:** The extra error result reference is distinct: `prepare-result`
chooses an offending leaf, whole offending value, or source; it renders and
binds that value (`src/seon/error.clj:478-495`). It does not bind the original
Malli explanation. **UNVERIFIED:** no live handle was read in this assignment.

**UNVERIFIED proposal:** the missing bridge is an unprojected explanation with
its original root schema and registry context. For each `:path`, inspect its
prefixes with `mu/get-in`; retain `m/-ref` at reference nodes before dereferencing.
Then read target properties and follow the structural reference closure.
Use `:in` to identify the offending value, not to navigate the schema tree.
Malli errors often point at a leaf whose own schema no longer names its parent;
root/path evidence is essential (explainer/ref paths in `core.cljc:1999-2001,2079`).

### Duplication audit and physical line counts

**VERIFIED:** Counts below are inclusive physical source spans, including
contracts/comments/blanks inside the span; they are not promised deletion totals.
**UNVERIFIED:** replacement equivalence until focused regressions run.

| Seon span | Lines | Assessment |
|---|---:|---|
| `src/seon/error.clj:768-790`, `schema-expectation` | 23 | Generic schema description dispatch overlaps `describe`/`error-message`; strongest replacement candidate. |
| `src/seon/error.clj:792-809`, `collection-member-problem` | 18 | Revalidates members to find a failing leaf; compare native explanation paths before retaining. Not proven redundant for every check/guard shape. |
| `src/seon/error.clj:811-861`, `explain-problem` | 51 | Mixes humanization with Seon fixes, missing-key parent selection, and function-check evidence. Replace generic portion; retain demonstrated domain semantics. |
| `src/seon/instrument.clj:288-304`, described-union/problem rewrite | 17 | Compare Malli root-message resolver; not a verified drop-in replacement. |
| `src/seon/instrument.clj:586-636`, `boundary-refusal` | 51 | Duplicate explanation acquisition and custom evidence projection, plus necessary Seon boundary metadata. Do not count the entire function as expendable. |
| `src/seon/schema.clj:61-79`, `direct-references*` | 19 | Already a Malli composition; no replacement walker needed. |
| `src/seon/instrument.clj:715-736`, `contract-definitions` | 22 | Closure over existing dependency facts; useful content-validity responsibility, not a schema parser. |
| `src/seon/schema/datahike.clj:77-139`, `compiled-storage` | 63 | Malli fold interpreting Seon storage semantics; Malli does not supply this Datahike policy. |
| `src/seon/call_preparation.clj:348-370`, body of required-map-entry derivation | 23 | Uses `m/deref-all`/`m/children`, matching supplied defaults by schema identity; not humanization duplication. |

**VERIFIED:** `projection-explainer` delegates to `m/explainer`
(`src/seon/schema.clj:4012-4025`), and completeness admission uses `m/walk`
(`src/seon/schema/internal.cljc:291-334`). These are library reuse.
The inspected error/schema/call-preparation owners contain no generic
story-example generator to retire. Existing test generation uses `mg/generator`
(`src/seon/test/accretion.clj:141-155`), and its error prose already calls
`me/humanize` (`:133-138`). **UNVERIFIED:** repository-wide absence of other
bespoke example/description mechanisms; this audit is bounded to the named owners.

## 3. Smallest composition

**UNVERIFIED design sketch, not production code or an installed API:**
Pass an immutable registry/projection, key, and rendering request. Derive data
once, use one existing AI/HTML pair, and retain the attribute-map fallback.
Do not store stories, generated descriptions, graph mirrors, or entity-kind stamps.
Observed schema keywords and wikilink symbols remain values; resolving one to a
living entity is a separate query. Malli reference identity is not a Datahike
entity ref. Keep prose in ordinary docstrings and schema descriptions, with no
filler definitions. Cross-namespace renderer inheritance/override resolution
belongs to Seon's render owner; this study establishes no Malli API for that policy.

The following uses only Clojure and Malli. `m`, `mu`, `md`, `mj`, `mg` denote
`malli.core`, `malli.util`, `malli.experimental.describe`, `malli.json-schema`,
and `malli.generator`. The illustrative output keys require normal declaration
review before any production use. It deliberately has no example generation on
ordinary reads and is not claimed total for malformed or shadowed registries.

```clojure
(fn [registry key]
  (let [s (m/schema key {:registry registry})
        target (m/deref-all s)
        occurrences (mu/subschemas s {::m/walk-refs true
                                      ::m/walk-schema-refs true})]
    {:research.story/key key
     :research.story/form (m/form target)
     :research.story/properties (or (m/properties target) {})
     :research.story/references
     (into #{}
           (keep (fn [{:keys [schema]}]
                   (when (m/-ref-schema? schema) (m/-ref schema))))
           occurrences)
     :research.story/description (md/describe target)}))
```

**UNVERIFIED sketch qualification:** this includes the root key and reference
names of any Malli-supported identity type, not merely keywords. Retain path
and registry scope instead of collapsing to a set when local names can shadow.
For production canonical-key closure, prefer Seon's existing direct-reference
query and dependency facts; it stops at canonical boundaries (`schema.clj:61-79`).

**UNVERIFIED optional operations:** `(mj/transform s)` exports a documentation
view. `(let [v (mg/generate s {:seed 42 :size 5})]
{:research.story/example v :research.story/valid? (m/validate s v)})`
provides a reproducible candidate under fixed dependencies and generator behavior.
Generators may execute declared functions or fail; do not invoke them to render
arbitrary schema pages. No example output is asserted here.

**UNVERIFIED implementation estimate and retirement order:**

1. Reuse the acquired compiled schema and its Malli explainer. Keep one native
   explanation on the live refusal; preserve Seon's throw/record policy.
   About 10–20 changed/new production lines plus the reviewed field contract.
2. Derive key properties and reference occurrences with existing owners/Malli;
   about 15–25 lines. Add no dependency registry or stored links.
3. Render authored description, native form, and `with-error-messages` or
   `humanize` output through the existing pair; about 20–35 lines. Replace
   `schema-expectation` and the generic part of `explain-problem`; remove the
   second explanation computation. Keep domain fixes only where evidence requires.
4. Expected total: 45–80 production lines, target net reduction; unmeasured.
   Durable component/blob retirement is excluded until the owner settles what
   evidence must survive a lost subject/JVM. Do not claim those lines as savings.

**UNVERIFIED cost model:** metadata lookup is constant work after acquisition;
a story walk costs visited occurrences plus schema-path construction, not the
entire registry. Shared paths can repeat, so it is not automatically O(unique
nodes + edges). Native explanation is proportional to validation work and errors;
humanization adds path construction. Generation has schema-dependent rejection
cost and no general sub-second guarantee. Cache only by held immutable inputs or
existing content-valid projection dependencies; unrelated transactions must not
invalidate stories. No new stamp or atom beside a connection.

**UNVERIFIED acceptance requirements:** compare nested refs, aliases, local
shadowing, recursive refs, missing keys, unions with root messages, wrong arity,
runtime-supplied args, output/guard failures, opaque values, and unavailable
symbols. Prove native explanation traversal through an actual result handle;
verify AI limits apply once and HTML remains complete. Measure warm latency,
acquisition, retained memory, and changed-input recomputation. None is measured here.

## 4. Owner decisions

All choices and estimates below are **UNVERIFIED proposals**, not permissions
requested by this read-only assignment.

- **Automatic links:** (A) structural Malli refs plus authored descriptions
  **(recommended; 15–25 lines)**, complete for admitted canonical dependencies,
  sacrifices implicit prose-only links; (B) add the existing documentation
  wikilink resolver if another lane proves it, integration cost unknown;
  (C) parse arbitrary keyword mentions, new grammar/ambiguity cost unknown,
  risks treating examples as relationships. Malli itself provides no text parser.
- **Error evidence:** (A) retain native explanation only in the live refusal
  **(recommended first slice; 10–20 lines plus declaration)**, directly traversable,
  unavailable after restart; (B) additionally persist selected observation data
  via Malli's form conversion and existing storage owner, migration cost unknown,
  sacrifices simplicity and still needs the original registry basis;
  (C) recompute against current schemas, smallest payload but cannot guarantee
  an accurate historical explanation. Do not label C original evidence.
- **Description fallback:** (A) authored description plus native form
  **(recommended initial default; within 20–35 render lines)**, predictable,
  sacrifices generated prose; (B) also `md/describe` the dereferenced target,
  small call-site cost but unknown/custom types need explicit fallback;
  (C) JSON Schema export, small call-site cost but loses Clojure-specific semantics.
- **Examples:** (A) explicit seeded generation with validation
  **(recommended; optional later work)**, can fail visibly and keeps ordinary
  reads cheap; (B) authored examples in properties, low runtime cost but require
  validation against current schemas; (C) generate on every render, minimal
  call-site code but unpredictable cost and possible declared generator effects.
