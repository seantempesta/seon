---
type: design
status: "Opus first pass revised for the owner output ruling (kondo return tags); awaiting Astra review"
created: 2026-09-25
scope: Contracts derived from destructured qualified keys and returned map keys (rows 1.12, 1.13 output half)
source-pin: 19e9fe5f4
---

# Derived contracts: destructured keys in, returned keys out (first pass, 2026-09-25)

Lane design-derived-contracts-opus. First-pass design for Astra review; no production code changed.

**What exists and the approach.** Source arglists are already parsed twice: once into binding facts
(`seon.program/map-binding-entries`, `src/seon/program.cljc:733`), and once by Malli, which derives a
`:cat`/`:map` input schema from a destructuring vector (`malli.destructure/parse`,
`reference-code/malli/src/malli/destructure.cljc:125-162`). The data flow stays as it is:
source → `:seon.fn/spec` on the row → `contract-facts`/`arity-row` → arity rows with input/output refs →
projection → arming, call preparation, `reads-key`, reach. The design adds one step to it: when a
declaration has no `:malli/schema`, its producer fills `:seon.fn/spec`. Inputs come from Malli's derivation
of the arglist. Outputs come from clj-kondo's inferred return tag for each arity: the fork already computes
it (`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:877-899`) but does not yet export it (§2).
This follows the owner's ruling that there are two output sources, an explicit `:malli/schema` or parsed
return keys, and one parser per concern: Malli for destructuring, kondo for return keys. Seon has no body
walker of its own. Nothing downstream learns that a contract was derived.

**Division of ownership.**
- This document owns requirement rows **1.12** and the output half of **1.13**: the derivation rules; how
  explicit and derived contracts combine; storage; links; unregistered keys; how inline functions get
  their contract.
- `design-function-face-opus` owns **1.14** and the face: how the stored contract is shown, whether an
  explicit contract equal to the derived one is canonicalized away, and how "derived" is labelled. It cites
  this document for every derivation rule and adds no rules of its own.
- `docs/research/agent-platform/function-face-and-canonical-authoring-2026-09-25.md` did not exist when
  this was written.

Pinned revisions: seon `19e9fe5f4`; malli `befcd302a8e626a67d84b80aabee0cd1b38dadb6`;
sci `fcbd8862800e638dc0f8f5521111f999279cbcd2`; clj-kondo `57252e07975710aa579b24f0d1b2b1e04195caa2`.

---

## 0. Evidence (REPL and scans)

Every probe ran against `default` (pid 35446), was finite and read-only, and used a private io-prepl session.
The SCI probes used the disposable branch `derived-contracts-opus`, which was retired afterwards.

| # | Form (abridged) | Observed |
|---|---|---|
| P1 | `(md/parse arglist {::md/inline-schemas false ::md/sequential-maps false ::md/required-keys true})` over six arglists | 1.65 ms for all six. `[{:keys [seon.db/db seon.agent/id] :as unit} x]` → `[:cat [:map :seon.db/db :seon.agent/id] :any]`. `[{:seon.db/keys [db] :keys [seon.render/value]}]` → `[:cat [:map :seon.render/value :seon.db/db]]`. Nested `[{{:keys [seon.x/a]} :seon.y/b c :c}]` → `[:cat [:map :seon.y/b [:c :any]]]`: the outer key stays a reference, the inner key is dropped, and **the unqualified key becomes required `:any`**. `:or {profile {}}` is **ignored**, so `:seon.render/profile` is still required. `[a & {:keys [seon.db/db]}]` → kwargs `[:orn [:map …] [:args …]]`. |
| P2 | `(md/parse '[{:keys [seon.db/db]}])` with default options | `[:cat [:orn [:map [:map [:seon.db/db {:optional true}]]] [:args …]]]`. By default every key is optional and a map argument also accepts kwargs, so we must set the options explicitly. |
| P3 | SCI: `(meta (fn [{:keys [seon.db/db] :as unit}] {:seon.render/value 1}))` | keys `(:sci.impl/root-data)`; `:arglists` nil |
| P4 | SCI: `(:sci.root/form (:sci.impl/root-data (meta f)))` | `(fn* ([p__4612329] (clojure.core/let [{:as unit, :keys [seon.db/db]} p__4612329] #:seon.render{:value 1})))`. This is the **post-expansion** form: the destructuring has moved into a `let` over a gensym. |
| P5 | `(m/schema [:map :seon.turn.loop/schedule] {:registry projection-registry})` | throws `:malli.core/invalid-schema`, data `{:form :seon.turn.loop/schedule}` |
| P6 | `(m/function-schema [:=> [:cat [:map :seon.db/db :seon.cluster/name :seon.agent/id]] [:map …]] opts)` | compiles in 0.10 ms |
| P7 | `seon.program/contract-facts` with a spec that uses Malli **naked reference entries** `[:map :seon.db/db …]` | **throws** `UnsupportedOperationException: nth not supported on this type: Keyword` at `seon.fn.schema_shape$canonical_map_entry` (`src/seon/fn/schema_shape.clj:28-36`) |
| P8 | The same call with `[:map [:seon.db/db :seon.db/db] …]` | 7.2 ms; `input-refs #{:seon.cluster/name :seon.agent/id :seon.db/db}`, `output-refs #{:seon.ai/primary :seon.ai/settings}`; binding entries stored with `key-keyword` |
| P9 | Query `:seon.fn/sym` rows vs `:seon.fn/spec` in default | **4,867 function rows, 2,099 with a contract; 2,768 without one.** `seon.turn/provider-targets` has no projection contract. |
| P10 | `bb` scan of `src/**/*.clj{,c}` with edamame (committed nowhere; `tmp`-class probe) | 3,354 `defn`s; 1,919 carry an attribute-map `:malli/schema`. 196 take only maps with a qualified key in every argument: 155 of those have explicit schemas (808 pprinted schema lines), 41 have none. Tails of those 196: 28 map literal, 19 `assoc`/`merge`/`cond->`, 49 branch forms, 100 other. |
| P11 | `(clj-kondo.core/run! {:lint [probe.clj] :cache true :cache-dir scratch})` on the fork; read `v1/clj/probe.ret.transit.json` | 15.5 ms. The resolved per-arity `:ret` for 13 probe tails is in the §2.1 table. |
| P12 | the same run over `/Users/sean/src/seon/src` with `:skip-lint true` | 2,198 ms elapsed (other agents' work was concurrent: the profile names custody/transact). 106 cache files. Real tags for the five §7 functions are in §2.1. |
| P13 | `bb` classifier over P12's cache files | 1,904 arities with a `:ret`: 163 closed and 5 open qualified maps, 40 unions made only of qualified maps, 29 unqualified maps, 51 empty maps, 303 other unions, 861 scalar or collection keywords. 403 `cond->` forms in `src`. |

What the probes established:
- Malli already owns input derivation. P1 and P2 tell us which options to pass (below).
- The runtime closure form cannot be the source for derivation (P4).
- An unregistered key already refuses with a named form (P5).
- Seon's shape codec cannot store Malli's shortest map form, the naked key reference (P7). This is a
  prerequisite defect, and fixing it is part of cut C4.
- Derived refs populate the existing link relation once they are stored (P8).

---

## 1. Inputs from destructuring

**Seam.** Add one pure function in `seon.fn.signature` (cljc, `src/seon/fn/signature.cljc`). It already reads
the declaration with the namespace's aliases (`read-source`, `:17-31`) and splits the arglists
(`declaration-parts`, `:62`). The new function, `declared-contract`, returns one of three things:
- the authored `:malli/schema`, when present;
- a derived form;
- a declared refusal that names the arity, argument and reason.

For each arglist it calls Malli:

```clojure
(md/parse arglist {::md/inline-schemas false    ; plain Clojure only (1.3): no `x :- s`
                   ::md/sequential-maps false   ; a map argument is a map, not kwargs
                   ::md/required-keys true})    ; bare reference entries; :or decides optional
```

Multi-arity declarations become `[:function [:=> …] …]`, as in `md/-function-schema`
(`destructure.cljc:115-119`).

**Normalization.** Malli's output needs three small corrections, each tied to a probe:

1. **Unqualified keys derive nothing.** Remove entries whose key is not a qualified keyword. P1 shows that
   Malli otherwise makes `[:c :any]` *required*. `:strs` and `:syms` entries are removed by the same rule.
2. **`:or` makes a key optional.** A key whose local appears in the binding's `:or` becomes
   `[k {:optional true}]`. Malli ignores `:or` (P1), but the local-to-default table is already computed in
   `map-binding-entries` (`program.cljc:733-773`, `:seon.fn.binding.entry/default-edn`). A key with no
   `:or` is **required**. Required is what call preparation supplies as a default: `supplied-map-entries`
   uses only required top-level entries (`call_preparation.clj:864-869`). It is also what makes an omitted
   input a named refusal rather than a nil deep in the body. A key that is truly optional with no default
   is written `:or {k nil}`, because a nil default means the key is absent.
3. **An argument with no qualified key after step 1 is underivable.**

**Accepted spellings.** All of these resolve before Malli sees them:
- `{:keys [seon.db/db]}` and `{:keys [:seon.db/db]}`;
- `{:seon.db/keys [db]}` (`md/-qualified-keys`, `destructure.cljc:70-74`; P1);
- `{:keys [::alias/k]}` and `{:keys [::k]}`, resolved at read by the alias resolver (`signature.cljc:17-27`);
- explicit renames `{db :seon.db/db}`, which become reference entries (P1 case 3; P8).

**Nested destructuring.** `{{:keys [seon.x/a]} :seon.y/b}` contributes only `:seon.y/b` as a reference
(P1). The registered schema of `:seon.y/b` owns its shape. The inner key is still stored as a binding entry
fact (`binding-entry-row`, `program.cljc:775-792`), which gives it a queryable "destructures" edge (§4). If
the registered `:seon.y/b` schema does not declare `:seon.x/a`, that is **advice** (§3), found with
`m/deref` + `m/entries` (`malli/core.cljc:2782`).

**Positional arguments.** A symbol or vector argument gets a schema only when one is explicitly declared.
The arity is then underivable: the function writes a full `:malli/schema`, exactly as today. Rest
arguments (`& {:keys …}` kwargs, `& xs`) are underivable in v1. So is any arity mixing a qualified map
with a positional argument.

The rule is all-or-nothing per function: we do not merge half-derived arities with half-explicit ones.
`^{:malli/schema s} x` on an arglist symbol is legal Clojure and a possible later accretion. It is unproven
through clj-kondo, the SCI reader and `:arglists`, so v1 does not use it.

**Result for the owner's example.** `(defn f [{:keys [seon.db/db seon.agent/id] :as unit} x] …)` is
underivable because of `x`. Without `x`, the derived input is `[:cat [:map :seon.db/db :seon.agent/id]]`.
`:as unit` changes nothing on input. With fork slice F3 it would let kondo tag `(assoc unit …)` as the
input's keys plus the new key (§2.4).

**Why not clj-kondo.** Its keyword analysis marks `:keys-destructuring`
(`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:176-186`), and it can report locals
(`reg-local!`, `:158`), although Seon enables neither locals nor local usages
(`src/seon/fn/analyzer.clj:30-39`). It does not return the key → local → `:or` → nesting structure that the
contract needs. Seon's own parser and Malli's parser both already do.

---

## 2. Outputs from returned map keys: clj-kondo's inferred return tag

**Owner ruling (2026-09-25).** There are two sources for a function's output, and no third:
- an explicit `:malli/schema`, which names inputs and outputs and wins;
- otherwise, the return keys parsed by clj-kondo, which is the one output parser.

There is no `:seon.fn/returns` key, and Seon does no tail-position walk of its own (the first pass had one).

### 2.1 What the fork infers today (verified)

The fork already infers a return tag for every `defn` arity:
- `analyze-fn-body` tags the body's last expression (`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:877-892`).
- If that expression is a call, the tag is the call's `:ret` from `calls-by-id`. If it is a map literal, the
  tag is `types/map->tag` (`types.clj:349-382`), and the map registers itself as a return (`analyzer.clj:4040-4044`).
- The tag is stored as the arity's `:ret` (`:895-899`, `extract-arity-info` `:913-930`).
- It reaches `namespace/reg-var!` inside `:arities` (`analyzer.clj:1956`).
- It is resolved across namespaces when the cache syncs: `cache/update-defs` → `types/resolve-types` →
  `resolve-arity` (`cache.clj:146-169`, `types.clj:882-935`). This happens even with no cache directory
  (`cache.clj:210-215`).
- `analysis/reg-var!` (`analysis.clj:87-100`) keeps only a fixed attribute list, so **no return tag is exported**.

**Probes.** P11 and P12 ran `clj-kondo.core/run!` in default's JVM on the fork itself (the `deps.edn`
`:local/root`) and read the resolved arities back from the cache file it wrote. That cache holds the same
`:ret` values that `update-defs` resolves.

| Tail (P11 probe file) | Resolved `:ret` |
|---|---|
| `{:seon.ai/primary 1 :seon.ai/backup "x"}` | `{:type :map :val {:seon.ai/primary {:tag :pos-int} :seon.ai/backup {:tag :string}}}`: closed, every key known |
| `(assoc {:seon.a/b 1} :seon.a/c "s")` | closed map with both keys (the `assoc` `:fn` spec, `types/clojure/core.clj:132-158`) |
| `(assoc unit :seon.ai/primary 1)` where `unit` is the `:as` of a destructured argument | `:associative`: **keys lost**, because the argument has no map tag |
| `(merge {:seon.a/b 1} {:seon.a/c 2})` | `:nilable/map`: **keys lost** (`merge` has only a `:ret`, `core.clj:666-668`) |
| `(cond-> {:seon.a/b 1} x (assoc :seon.a/c 2))` | **no `:ret`**; the arity is dropped as empty. The `expand-cond->` rewrite (`macroexpand.clj:55-85`) loses it; cause not diagnosed. |
| `(let [y 1] {:seon.a/b y})` | closed map |
| `(if x {:seon.a/b 1} {:seon.a/c 2})` | set of the two map tags (a union) |
| `(when x {:seon.a/b 1})` | `#{:nil <map>}` |
| `(sorted-map :seon.a/b 1)` | `:sorted-map`: keys lost |
| `{:seon.a/b {:seon.a/c 1}}` | nested map tags |
| `(lit x)`, a user `defn` in the same run | **lit's map tag**, resolved through the deferred `{:call …}` (`types.clj:448-476`) |
| `(:seon.ai/primary (lit x))` | `:pos-int`, via the keyword call on the callee's map (`:kw-calls`) |
| multi-arity `([x] {…}) ([x y] "s")` | one `:ret` per arity: map / `:string` |

On real Seon code (P12, the whole-`src` run):
- **`seon.turn/provider-targets`** → a closed map `{:seon.ai/primary … :seon.ai/backup … :seon.ai/settings …
  :seon.turn.loop/schedule …}`. Value tags stay deferred `{:call {:resolved-ns seon.ai :name targets
  :kw-calls [:seon.ai/primary]}}`, so the resolved cache keeps callee names below the top level.
- **`seon.fn/stored-namespace-context`** → a map with unqualified keys.
- **`seon.flow/with-submission-filter`** → `:associative`.
- **`seon.turn/attempt-request`**, **`seon.render.ns/compact-function-value`**, **`seon.render/unknown-stable-evidence`**,
  **`seon.program/contract-facts`** → no `:ret`. All of them end in `cond->`.

**Census (P13).** Seon source has 403 `cond->` forms. Of the 1,904 `src` arities that carry a resolved `:ret`:

| Resolved `:ret` | Arities |
|---|---:|
| closed map, all keys qualified | 163 |
| open map, all keys qualified | 5 |
| union made only of qualified maps (and `:nil`) | 40 |
| map with unqualified keys | 29 |
| empty map | 51 |
| mixed map | 2 |
| other union | 303 |
| scalar or collection keyword (`:vector` 269, `:boolean` 155, `:string` 114, `:associative` 62, `:nilable/map` 41, …) | 861 |

A further 450 arities have `:args` only. So about 208 arities derive a qualified-key output from kondo's
tag today. More would derive once named callee resolution applies (§2.3); that number is unmeasured.

### 2.2 The smallest fork change (F1)

Export each arity's raw return tag through the var-definition analysis that `seon.fn.analyzer` already reads:

- `clj_kondo/core.clj:146-216`: read a new option,
  `(get-in analysis-cfg [:var-definitions :arity-returns])`, into ctx as `:analyze-arity-returns?`. About 2 lines.
- `clj_kondo/impl/analysis.clj:87` `reg-var!`: when the flag is set, add
  `:arity-returns {arity-key tag}` from `(:arities raw-attrs)`. The arity key is a fixed-arity integer or
  `:varargs`, as in `extract-arity-info`. Each tag goes through `type-utils/strip-positions`
  (`types/utils.clj:273-286`), and each `{:call …}` is reduced to `:resolved-ns :name :arity :kw-calls`, so
  the analysis stays serializable (the issue #2165 note at `types.clj:370`). About 10 lines.
- Export the **raw** tag, not the cache-resolved one. A raw tag keeps a user call as a name,
  `{:call {:resolved-ns … :name … :arity …}}`, and Seon resolves that name against the callee's declared
  contract (§2.3). That way an explicit callee contract wins over kondo's structural guess, and a declared
  error alternative stays a named schema (AGENTS.md "Declare errors as flat data"). The resolved tag would
  replace names with the callee's structure.
- One fork test in `test/clj_kondo/analysis_test.clj` covering a map literal, an `if` union and a user call.
  About 20 lines.
- Cost is zero when the flag is off. When on, it serializes one small value per arity inside a lint Seon
  already runs. The added time is unmeasured, and F1 must report it.

The Seon side of F1 is three lines:
- enable `:arity-returns true` in the analysis config at `src/seon/fn/analyzer.clj:30-39`;
- add `:arity-returns` to `var-definition`'s key list (`:124-132`);
- add one optional entry to the analyzer's output contract (`:683`).

### 2.3 Seon's translation, a function of the tag (S3)

`seon.fn/var-row` (`fn.clj:644`) already holds the analyzer entry, so it passes `::analyzer/arity-returns`
to `declared-contract`. The translation from tag to Malli form is total, and each tag has one rule:

| Tag | Malli |
|---|---|
| `{:type :map :val m}` with at least one qualified keyword key | `[:map k …]` over the qualified keys, each a naked reference, required. Unqualified keys derive nothing. `:open` adds nothing, since Malli maps are open. Value tags are ignored: the registered schema owns the value. |
| map with no qualified key | underivable (`:unqualified-return-keys`) |
| set (union) | union members are grouped into components by shared keys. Each component collapses into one `[:map]` whose keys present in every member are required and the rest optional. More than one component becomes `[:or …]`, and `:nil` adds a `:nil` alternative. This avoids the 2ⁿ member explosion that `cond->` chains would produce once F2 exists, while keeping a success map and an error map as separate alternatives, since they share no key. |
| `{:call {:resolved-ns n :name f :arity a}}`, no `:kw-calls` | the callee's output for arity `a`, from its explicit contract in the projection or batch, or from its own derived output (recursive, with a visited set, over the same indexing batch). An uncontracted, underivable or cyclic callee makes this output underivable. |
| anything else (`:associative`, `:nilable/map`, `:sorted-map`, scalar tags, `{:call …}` with `:kw-calls`, a missing `:ret`) | underivable, and the refusal names the tag |

About 40 lines, including the callee resolution and the component rule.

Scalar tags (`:string`, `:boolean`, `:vector`) could map to Malli types later. That would accrete a
bounded kondo-to-Malli table; it is not v1, because the ruling covers return keys.

### 2.4 What stays underivable, and what the author writes

Underivable outputs today:
- tails of `cond->` and `merge`, until F2;
- `assoc` on a destructured input, until F3;
- `sorted-map`, `into`, `reduce` and other built-ins whose `:ret` has no keys;
- keyword access on a call's result;
- maps with only unqualified keys;
- calls into dependencies or to uncontracted Seon functions;
- any union containing one of the above.

**For the owner to rule on:** such a function needs **an explicit `:malli/schema`** (recommended), or its
output is **unknown-typed**.
- *Recommended, explicit required.* The entry gate's existing `missing-contract` refusal
  (`src/seon/sci/eval.clj:1763`) names the underivable tag and its arity, and shows the explicit form.
  Guarantee: every admitted function is contracted. Cost: the author writes the whole contract, and inputs
  are not shortened for these functions. Sacrifice: short form only where kondo can see the keys.
- *Unknown-typed.* This would admit an `:any` output with a typed diagnostic. It contradicts "Contract every
  admitted function" and "Every check must pass before merge". It could exist only as a `:warn` entry
  experiment, and `:warn` is not installed (`missing-contract` docstring, `eval.clj:1766-1769`). Not
  recommended.

**Fork slices that shrink the underivable set**, priced separately and each proven by a fork test:
- **F2 — keep `cond->` and `merge` tails.**
  - `cond->`: find why the `expand-cond->` let-rebind loses the tag. That would turn Seon's 403 `cond->`
    forms, the idiomatic "absence is no key" builder, into unions of maps. Estimate ~15 lines, unproven.
  - `merge`: a `:fn` spec that merges the maps' `:val` entries, like `assoc`'s. About 10 lines.
- **F3 — tag a destructured argument's `:as` local as an open map of its destructured keys.** Then
  `(assoc unit :k v)` returns the input's keys plus `:k`. This extends the binding extraction that
  `destructured-key-tag` already reads (`types.clj:384-399`). Estimate ~10 lines, unproven.

Nothing here adds a Seon parser. An output kondo cannot see becomes visible only through a fork change
with its own test.

## 3. Explicit and derived contracts

- **An explicit `:malli/schema` wins**, for inputs and outputs together (owner ruling). The derivation
  still runs, but only as a check.
- **Disagreement is advice, not a refusal (v1).** The check reports a destructured qualified key that the
  explicit input neither lists nor reaches through a named schema (`m/deref` + `m/entries`). It also
  reports a derived output key missing from an explicit output.
  - Why advice: an explicit contract may legitimately be wider, narrower or named, e.g. a whole
    `:seon.render/unit`. The number of such disagreements in today's 155 explicit map-input contracts is
    unmeasured, so a refusal would put an unknown number of existing functions red.
  - The advice is declared data, returned with the evaluation's result and reported by publication. It is
    never stored as a fault.
  - It becomes a named refusal (`:seon.program/destructured-key-undeclared`) once its census is zero.
  - One case refuses now: the explicit map is `{:closed true}` and lacks a destructured key, so the read
    can never succeed. Source/contract mismatches already refuse in `arity-row` (`program.cljc:1004-1030`).
- **Storage is unchanged.** The derived form is written as `:seon.fn/spec` through the same
  `schema/canonical-definition` call as an authored one (`src/seon/fn.clj:738-744`). The following then
  work unchanged, because they read only `:seon.fn/spec` or rows derived from it:
  - `with-contract-facts` → `contract-facts` → `arity-row` → arity rows (`program.cljc:1004-1115`), with
    their input/output refs;
  - the projection's `:seon.schema.projection/function-contracts` (`fn.clj:2895`, `:3084`);
  - SCI arming (`install-function-contract!`, `src/seon/sci/eval.clj:767`);
  - the entry gate (`refuse-uncontracted-definitions!` checks `:seon.fn/spec`, `eval.clj:470-474`);
  - call preparation (it reads the arity/argument rows, `call_preparation.clj:989`);
  - `reads-key` (`src/my/program.clj:211`);
  - reach.
- **Whether a contract was derived** is itself derived: the declaration has no `:malli/schema`
  (`signature/declaration-metadata`, `signature.cljc:39`). No new stored attribute. Whether the face needs
  a stored boolean is the face owner's call.
- **Write-back and files** keep the short source. `:seon.fn/spec` is never written into a file, just as
  today it is only read from one.

**Producers to convert** (every caller converted, per AGENTS.md §5):
- `seon.fn/var-row` (`fn.clj:644`, branch `:738-744`). The SCI path's analysed row comes from this
  producer via `source-rows` (`eval.clj:489-512`).
- The SCI override at `eval.clj:514-516`.
- The reader-side `function-declaration` (`src/seon/sci/reader.cljc:251`, `:285`, `:302`).

All three call `signature/declared-contract`. Only the indexer path has the kondo entry, and with it the
output tag. So the reader-side producer can carry only an explicit contract, and its derived-contract row
comes from the indexer. Whether a reader-side row without `:seon.fn/spec` reaches the gate before the
indexer row does is unproven (§8). That producer duplicates the indexer, which is an out-of-scope
finding (§9).

**One defect blocks installation.** JVM arming selects candidates by Var metadata (`mi/-schema`):
`armable` (`src/seon/instrument.clj:82-104`), `collect-contracts!` (`:815-825`) and `apply!` (`:906-918`).
A compiled Var whose contract was derived has no `:malli/schema` metadata, so it would **never arm**.
`compiled-wrapper` already prefers the projection's contract over the authored one (`instrument.clj:627`).
The fix is to select candidates from `:seon.schema.projection/function-contracts` rather than from Var
metadata. That makes rows the single authority ("Decide at the authority"), and it replaces lines rather
than adding a path.

---

## 4. Links

- **Reads and returns.** Once a derived contract is stored, every derived key is an existing queryable
  edge: `:seon.fn.arity/input-refs` ("the function reads") and `:seon.fn.arity/output-refs` ("the function
  returns"). They are computed by `schema-reference-keys` over the compiled contract (`program.cljc:890-907`;
  proven in P8). `reads-key` already groups them as `:seon.program/contract-refs`. No keyword edge is added
  (5a.5).
- **Destructures.** Nested inner keys are already stored as `:seon.fn.binding.entry/*` with
  `:seon.schema.map-entry/key-keyword` (`typed-key-facts`, `src/seon/fn/schema_shape.clj:220`). An optional
  slice adds one `reads-key` group, `:seon.program/destructured-by`, as one query over those facts.
- **Unregistered qualified keys refuse by name.** They are not offered as schema tasks. Malli already
  refuses the compile with `{:form k}` (P5). The refusal wraps that and names function, arity, direction and
  key. Its fix text follows "Declare facts at their producer":
  - for an **output** key, the function is the producer, so it names the schema file the key's namespace
    maps to;
  - for an **input** key, it names the key and asks the author to declare it at its producer or not
    destructure it.
  - Why not offer tasks: tasks are root's schedule, and the refusal is already the data an agent repairs
    from.

---

## 5. Presentation

The stored contract is data: `:seon.fn/spec` plus arity rows. The function face renders it with its own
choices (short form, derived label, canonical authoring). This design specifies no face.

One constraint for the face owner: naked reference entries (`[:map :seon.db/db]`) are Malli's shortest
form, and `m/form` preserves them (`-parse-entry`, `malli/core.cljc:487-535`; `:map` defaults to
`:naked-keys true`, `:1210-1212`). The codec defect in P7 must be fixed so they can be stored (slice S0).
Otherwise derivation would have to emit `[k k]`, which would put an ugly spelling in both storage and face.

---

## 6. Inline functions and first render

There are three problems, and each has one answer. Inline functions get the kondo output tag the same
way: the evaluation's analysis is a kondo run over the evaluated source (`seon.fn/source-rows`,
`eval.clj:489-512`), and an anonymous `fn` is one of its arities. Whether kondo reports that arity through
the parent var or not at all is 1.11's question, and it is unproven.

1. **The closure's form is the expanded `fn*`** (P4). Derivation never reads runtime metadata. An inline
   `(fn …)` is derived from the **authored evaluation source**, by the same `declared-contract` over the
   same reader, at analysis time, before execution.
   - The result is memoized by the digest of the authored `(fn …)` source, because it is a function of an
     immutable value.
   - Linking the closure to its analysed row belongs to requirement 1.11's owner. The candidate join is
     the form's reader position against the analysed span; that is unproven, since P4 did not print form
     metadata.
2. **`plan-for` plans only from committed rows.** It reads arity rows by contract transaction
   (`call_preparation.clj:989-1010`). The kernel skips preparation for function values
   (`src/seon/sci/kernel.clj:633-640`), and a callee with no identity passes through
   (`call_preparation.clj:1538-1541`).
   - An inline function used in its own evaluation, such as an inline renderer at first render, has no
     committed row.
   - The answer is not a second planner. Preparation of a function value must consume the **compiled
     contract value**: `m/-function-schema-arities` (`malli/core.cljc:91`) and `m/entries`. That is the
     audit's C3 retained-contract seam (`duplicate-paths-audit-2026-09-25.md` §8.1).
   - Until C3 lands, inline functions are armed but receive no supplied defaults. This is a named gap,
     not a fallback.
3. **`:seon.fn/sym` rows are program.** An inline function's derived contract never creates a
   `:seon.fn/sym` row. It is carried by 1.11's evaluation-owned function entity, and that owner names the
   identity attribute; contract attributes are not identity.
   - `contract-facts` keys its component ids by function symbol (`component-id`, `program.cljc:694-696`),
     so an inline function needs its generated unique name first. That is 1.11's `fn__N`-style name.

---

## 7. Five real functions in the short form

Inputs follow §1. Outputs use kondo's real resolved tag for each function (P12). Registry facts come from the
default projection.

**7.1 `seon.turn/provider-targets`** (`src/seon/turn.clj:3930`; uncontracted today, P9). Source unchanged:

```clojure
(defn- provider-targets
  "Resolved provider targets, settings, and finite schedule for one turn."
  [{db :seon.db/db cluster-name :seon.cluster/name agent-id :seon.agent/id}]
  (let [...] {:seon.ai/primary primary :seon.ai/backup backup
              :seon.ai/settings settings :seon.turn.loop/schedule ...}))
```

kondo's tag is a closed map of four qualified keys, so the derived contract is
`[:=> [:cat [:map :seon.db/db :seon.cluster/name :seon.agent/id]] [:map :seon.ai/primary :seon.ai/backup :seon.ai/settings :seon.turn.loop/schedule]]`.
It **refuses** because `:seon.turn.loop/schedule` is unregistered (P5). This function is the key's
producer, so the refusal asks for its schema.

It also exposes a real defect. `:seon.ai/backup` is `:seon.ai/target`, which does not allow nil, and the
body returns `backup` even when it is nil. That breaks "absence is no key". The natural fix,
`(cond-> {…} backup (assoc …))`, makes the output underivable until F2.

**7.2 `seon.render.ns/compact-function-value`** (`src/seon/render/ns.clj:488`; uncontracted today).
- Input with `:or {doc nil spec nil}` added: `[:map :seon.fn/sym [:seon.fn/doc {:optional true}] [:seon.fn/spec {:optional true}]]`.
- Output: the tail is `cond->`, and kondo has **no `:ret`** (P12). So v1 requires an explicit
  `:malli/schema`.
- With F2 the tag would be a union of `{:seon.fn/sym}`, `{:seon.fn/sym :seon.fn/spec}`, … . The component
  rule collapses it to `[:map :seon.fn/sym [:seon.fn/spec {:optional true}] [:seon.fn/doc {:optional true}]]`.

**7.3 `seon.turn/attempt-request`** (`src/seon/turn.clj:3912`; uncontracted today).
- Input, with `:or {value nil failover-from nil delay-ms nil}` added, derives
  `[:map :seon.ai/target :seon.ai/settings :seon.ai.attempt/ordinal :seon.turn/id :seon.agent/id :seon.turn.loop/attempt-evidence [:seon.error/value {:optional true}] [:seon.ai.attempt/failover-from {:optional true}] [:seon.ai.attempt/delay-ms {:optional true}]]`.
- That input refuses: `:seon.turn.loop/attempt-evidence` is unregistered.
- Output: the tail is `(cond-> (merge {…} evidence) …)`, and kondo has no `:ret`. It stays underivable even
  with F2, because `merge` with a non-literal `evidence` has no keys. So this function needs an explicit
  `:malli/schema`.

**7.4 `seon.render/unknown-stable-evidence`** (`src/seon/render.clj:972`; uncontracted today).
- The input is already explicit renames. With `:or` on the four conditional keys it derives
  `[:map :seon.render.unknown/reason [:seon.render.unknown/producer {:optional true}] [:seon.render/output {:optional true}] [:seon.render.call/id {:optional true}] [:seon.error/value {:optional true}]]`.
- Output: `(cond-> (sorted-map …) …)` has no `:ret`, and even `sorted-map` alone gives `:sorted-map` with no
  keys. So an explicit `:malli/schema` is required, unless the base is rewritten as a literal and F2 lands.

**7.5 `seon.fn/stored-namespace-context`** (`src/seon/fn.clj:258`; uncontracted today).
- Input `{:seon.ns/keys [requires aliases refers imports]}` derives
  `[:map :seon.ns/requires :seon.ns/aliases :seon.ns/refers :seon.ns/imports]`; all four keys are registered.
- Output: kondo sees a closed map whose keys are all unqualified (`:requires :aliases …`, P12), so it is
  underivable. Either an explicit `:malli/schema`, or qualified return keys, which is the better data model.

A negative example: `seon.flow/with-submission-filter` (`src/seon/flow.clj:476`).
- Input: `::keys` → `:seon.flow/io-parallelism` and `:seon.flow/io-active-count`, both unregistered, so it
  refuses by name.
- Output: `assoc` on its destructured `:as state` gives kondo `:associative` (P12), which is underivable
  until F3.
- Even then it would reference the dependency key `:clojure.core.async.flow/input-filter`. Whether Seon
  registers dependency-owned keys is root's data-modeling decision.

For this small sample, the ruling means that without F2 only one of the five derives its output
(`provider-targets`). All five derive their inputs.

---

## 8. Priced slices

Each slice is one loadable change with its regression. The fork slices are priced separately: they change
`reference-code/clj-kondo`, the owner's fork (remote `seantempesta/clj-kondo`); root commits and pushes them.

**Seon slices**

| Slice | Change | Added (est.) | Deletes / enables | Needs |
|---|---|---:|---|---|
| S0 | `canonical-map-entry` (`schema_shape.clj:28-36`) accepts Malli naked `k` and `[k props]` entries, preferably by reading `m/children` `[k props schema]` | ~5 | fixes a latent crash (P7); a step of cut C4, not a new path | — |
| S1 | `signature/declared-contract`: `md/parse`, the three normalizations and the underivable refusal; convert the three producers | ~35 | — | S0 |
| S2 | JVM arming selects candidates from projection function-contracts, not Var metadata (`instrument.clj:82-104`, `:815-825`, `:906-918`) | ~8 | removes the Var-metadata authority (~8 lines) | — |
| S3 | read `::analyzer/arity-returns` (3 analyzer lines) and the §2.3 translation: map, union components, named callee resolution | ~43 | — | S1, F1 |
| S4 | explicit-vs-derived advice + census, then promote to refusal | ~20 + 1 schema | — | S1 |
| S5 | one scripted sweep: delete explicit schemas whose inputs and outputs S1+S3 derive equally; add `:or` to uncontracted map-input functions | net negative | at most 808 pprinted schema lines across the 155 explicit map-input functions (P10). Only functions whose output also derives lose their whole schema; the count is measured by the sweep. | S1–S3 |

**Fork slices**

| Slice | Change | Added (est.) | Proof |
|---|---|---:|---|
| F1 | export raw per-arity return tags: `:var-definitions {:arity-returns true}` (`core.clj`, ~2 lines) and `reg-var!` `:arity-returns` (`analysis.clj:87`, ~10 lines) | ~12 src + ~20 test | a fork `analysis_test` deftest (map literal, `if` union, user call) |
| F2 | keep `cond->` tails' `:ret` (`expand-cond->`, `macroexpand.clj:55-85`; cause undiagnosed); `merge` `:fn` spec combining `:val` | ~25, unproven | fork type tests; then P13's census rerun |
| F3 | destructured `:as` local tagged as an open map of its keys (next to `destructured-key-tag`, `types.clj:384-399`) | ~10, unproven | fork type test: `(assoc unit :k v)` keeps the input keys |

- **Minimal viable installation: S0 + S1 + S2 + S3 + F1**, about 91 Seon lines plus the fork's ~12.
  This is under the ~100-line bar, but not by much.
- **S4 would take the total to about 111.** Per duplicate-paths-audit §11 it needs the deletions measured in
  S5 first. S4 runs only after S5.
- **Order:** S0, S2, S1, F1, S3, S5; then F2, whose payoff P13 can measure, and S4.
- **Cost profile:**
  - derivation runs once per changed declaration, inside indexing that already runs kondo; no per-call work;
  - `md/parse` costs ~0.3 ms per arglist (P1);
  - Malli compile costs ~0.1 ms (P6);
  - F1's added lint time is unmeasured, and F1 must report the whole-`src` delta with the flag on and off.
    P12's 2.2 s is the flag-off baseline, but other work was running concurrently, so it needs a quiet rerun.

**Unproven** (Astra, falsify these):
1. Call preparation matches a naked reference entry `:seon.db/db` against the supplied-default rows by key
   and shape fingerprint (`supplied-map-entries`, `call_preparation.clj:864-900`). P8 used `[k k]` entries.
2. The raw export's exact shape. P11 read cache-resolved tags. Raw `{:call …}` tags at `reg-var!` time are
   inferred from `ret-tag-from-call` (`types.clj:448-476`) and from P12's nested deferred calls. F1's test
   must pin them down.
3. Cross-namespace callee resolution happens in one Seon indexing run, because every namespace is linted
   together. The SCI single-evaluation analysis sees only the evaluated source, so a call into another
   namespace there resolves only through the projection's contracts.
4. `md/parse` on arglists read in CLJS without aliases (`signature.cljc:29`): `::alias/k` in `.cljc` sources.
5. The reader-side row versus the indexer row ordering at the gate (§3).
6. 1.11's join from closure to analysed row by form position (§6.1).
7. Whether the face needs a stored "derived" fact (§3).
8. The census that S4 would flag.
9. P10 and P13 are scans, not the program graph. P9 is the authoritative uncontracted count.
10. The cause of the lost `cond->` tag (F2).

---

## 9. Findings outside this design

- **2,768 of 4,867 loaded function rows carry no contract** (P9). The sample includes
  `seon.turn/provider-targets`, `seon.turn/attempt-request` and `seon.fn/add-contract-facts`. That breaks
  "Contract every admitted function, including private ones". Derived contracts cover a slice of it, but
  the admission path that accepts these rows is a separate defect for root to record; this lane owns no
  issue file.
- **Three `:seon.fn/spec` producers:**
  - `fn.clj:738`;
  - `sci/eval.clj:514`;
  - the reader's `function-declaration`, `sci/reader.cljc:285`.

  This design converts all three to one function; the reader-side row family may be a duplicate path for
  the audit.
- **Ugly output** (AGENTS.md "Render ordinary values totally"). The MCP JVM printer elided depth-8 vectors
  inside P1/P2's result, and each two-item elision carried about 600 characters of requery metadata, more
  than the value it elided. Reported for the floor-printer owner.
- **Cost:** the first SCI evaluation on a fresh branch took 1,396 ms elapsed, 250 ms evaluation and 441 MB
  allocated (P3). That is over one second, and the profile named `seon.db/call-with-custody` and
  `acquire-context!`. Acquisition cost belongs to the branch-acquisition owner.
