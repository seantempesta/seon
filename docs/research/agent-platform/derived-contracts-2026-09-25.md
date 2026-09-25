---
type: design
status: "Opus first pass, awaiting Astra review"
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
declaration has no `:malli/schema`, its producer fills `:seon.fn/spec` from Malli's derivation of the
arglist and from a small reading of the body's tail maps. Nothing downstream learns that a contract was derived.

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
`:as unit` changes nothing on input; it lets output derivation name the input map (§2).

**Why not clj-kondo.** Its keyword analysis marks `:keys-destructuring`
(`reference-code/clj-kondo/src/clj_kondo/impl/analysis.clj:176-186`), and it can report locals
(`reg-local!`, `:158`), although Seon enables neither locals nor local usages
(`src/seon/fn/analyzer.clj:30-39`). It does not return the key → local → `:or` → nesting structure that the
contract needs. Seon's own parser and Malli's parser both already do.

---

## 2. Outputs from returned map keys

**Decidable statically** (v1, on the authored, unexpanded source): the body's tail positions.

| Tail | Derived |
|---|---|
| map literal whose keys are all qualified keywords | `[:map k1 k2 …]`, each a reference entry. The value's schema is the key's registered schema, not inferred from the value expression. |
| `(assoc base k v …)`, literal qualified `k`s | base's map plus required `k`s |
| `(cond-> base t1 (assoc k v) …)`, every clause an `assoc` with literal qualified keys | base's map plus those keys as optional |
| `(merge m1 m2 …)`, every `mi` decidable | union of entries; required if required in any |
| base is the `:as` local of a derived input map | that input map, so "returns its input plus" |
| `let`/`do`/`when-let`/`if-let` | the last body form |
| `if`/`cond`/`case`/`when` | each branch. `when` adds `:nil`. Distinct results become `[:or …]`; `throw` branches contribute nothing. |

**Undecidable:**
- any other call (constructors, error helpers, `sorted-map`, `into`);
- a symbol not bound to a derived input map;
- a computed or unqualified key;
- macros other than those listed (user macros and `->`);
- any branch containing one of the above.

When a tail is undecidable, the refusal names its path, for example `[:if :else]`.

Most outputs are undecidable. Only 47 of the 196 map-input functions end in a map or `assoc` form, and
branch tails that return declared errors are always undecidable (P10).

**When the output cannot be derived.** The law is "contract every admitted function", and a typed-unknown
output would admit a function with no output contract. That is a new cross-owner decision, so here are
three priced options:

1. **Simplest viable: a full `:malli/schema` is required.** No new key. Short form reaches at most the
   ~47 functions with decidable tails; the input shortening is lost everywhere else.
2. **Recommended: one authored key, `:seon.fn/returns <schema>`, in the attribute map.** Inputs are
   derived; the output is declared. It covers every derivable-input function (196 today plus new unit-map
   code). Cost: one metadata key (the registry has no existing `returns` key; searched
   `resources/seon/schemas`), about 6 lines in `declared-contract`, and one advice rule (§3). The price is
   a Seon-specific spelling; Malli's own output syntax, `malli.experimental/defn`'s `:-` at
   `experimental.cljc:21-24`, is not plain Clojure. Multi-arity functions whose arities return different
   shapes still write a full `:malli/schema`.
3. **Typed unknown output** (`:seon.fn/output-unknown` diagnostic), admitted only under `:warn` entry and
   never merged. Cheapest to write, but it contradicts "Contract every admitted function" and "Every check
   must pass before merge". Rejected unless the owner rules otherwise.

Under option 2 the preference order is: explicit `:malli/schema`, then derived inputs with
`:seon.fn/returns`, then derived inputs with a derived output. If none applies, the refusal is the existing
`missing-contract` (`src/seon/sci/eval.clj:1763`), with its message extended to name the underivable
argument or tail and to show the short form.

---

## 3. Explicit and derived contracts

- **An explicit `:malli/schema` wins**, and so does an explicit `:seon.fn/returns` for the output. The
  derivation still runs, but only as a check.
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

All three call `signature/declared-contract`. The reader-side producer duplicates the indexer; that is an
out-of-scope finding (§9).

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

There are three problems, and each has one answer:

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

The contracts below are what the rules in §1–§2 produce. Registry facts come from the default projection.

**7.1 `seon.turn/provider-targets`** (`src/seon/turn.clj:3930`; uncontracted today, P9). Source unchanged:

```clojure
(defn- provider-targets
  "Resolved provider targets, settings, and finite schedule for one turn."
  [{db :seon.db/db cluster-name :seon.cluster/name agent-id :seon.agent/id}]
  … {:seon.ai/primary primary :seon.ai/backup backup
     :seon.ai/settings settings :seon.turn.loop/schedule …})
```

The derived contract, `[:=> [:cat [:map :seon.db/db :seon.cluster/name :seon.agent/id]] [:map :seon.ai/primary :seon.ai/backup :seon.ai/settings :seon.turn.loop/schedule]]`,
**refuses** because `:seon.turn.loop/schedule` is unregistered (P5). This function is its producer, so the
refusal asks for its schema.

The derivation also exposes a real defect. `:seon.ai/backup` resolves to `:seon.ai/target`, which cannot be
nil, yet the body returns `backup` even when it is nil. That breaks "absence is no key". The fix is
`(cond-> {…} backup (assoc :seon.ai/backup backup))`, which derives `[:seon.ai/backup {:optional true}]`.

**7.2 `seon.render.ns/compact-function-value`** (`src/seon/render/ns.clj:488`; uncontracted today):

```clojure
(defn- compact-function-value
  [{:seon.fn/keys [sym doc spec] :or {doc nil spec nil}}]
  (cond-> {:seon.fn/sym sym}
    (not (str/blank? spec)) (assoc :seon.fn/spec spec)
    (first-doc-line doc) (assoc :seon.fn/doc (first-doc-line doc))))
```

Derived contract:
`[:=> [:cat [:map :seon.fn/sym [:seon.fn/doc {:optional true}] [:seon.fn/spec {:optional true}]]] [:map :seon.fn/sym [:seon.fn/spec {:optional true}] [:seon.fn/doc {:optional true}]]]`.
The only authored addition is `:or`.

**7.3 `seon.turn/attempt-request`** (`src/seon/turn.clj:3912`; uncontracted today). Inputs:
`:keys [:seon.ai/target :seon.ai/settings :seon.ai.attempt/ordinal …]`, with
`:or {value nil failover-from nil delay-ms nil}` added. They derive
`[:map :seon.ai/target :seon.ai/settings :seon.ai.attempt/ordinal :seon.turn/id :seon.agent/id :seon.turn.loop/attempt-evidence [:seon.error/value {:optional true}] [:seon.ai.attempt/failover-from {:optional true}] [:seon.ai.attempt/delay-ms {:optional true}]]`.

The input refuses because `:seon.turn.loop/attempt-evidence` is unregistered. The output tail is
`(cond-> (merge {…} evidence) …)`, and `merge` with a non-literal argument is undecidable in v1. Under
option 2 the author adds `:seon.fn/returns :seon.ai.attempt/request`, or whichever schema the recorder
declares. An existing registered name must be verified before writing it.

**7.4 `seon.render/unknown-stable-evidence`** (`src/seon/render.clj:972`; uncontracted today). The input is
already written with explicit renames, so the derived input is
`[:map :seon.render.unknown/reason [:seon.render.unknown/producer {:optional true}] [:seon.render/output {:optional true}] [:seon.render.call/id {:optional true}] [:seon.error/value {:optional true}]]`.
This needs `:or` for the four conditional keys.

The output `(cond-> (sorted-map …) …)` is undecidable: `sorted-map` is a call. Two ways out: rewrite the
base as a literal, which derives
`[:map :seon.render.unknown/reason [:seon.render.unknown/producer {:optional true}] … [:seon.render.unknown/call {:optional true}]]`,
or declare `:seon.fn/returns`. The rewrite drops the sorted order, which the caller would need to not depend
on; that is unverified.

**7.5 `seon.fn/stored-namespace-context`** (`src/seon/fn.clj:258`; uncontracted today). The input
`{:seon.ns/keys [requires aliases refers imports]}` derives
`[:map :seon.ns/requires :seon.ns/aliases :seon.ns/refers :seon.ns/imports]`; all four keys are registered.

The output is a map literal with **unqualified** keys (`:requires :aliases …`), so it derives nothing. It
needs `:seon.fn/returns`, or qualified return keys, which is the better data model.

A negative example: `seon.flow/with-submission-filter` (`src/seon/flow.clj:476`) derives its input from
`::keys` → `:seon.flow/io-parallelism` and `:seon.flow/io-active-count`. Both are unregistered, so it
refuses by name. Its output, `assoc` on `:as state` of `::flow/input-filter`, would reference the
dependency key `:clojure.core.async.flow/input-filter`, which Seon does not register. Declaring
dependency-owned keys is a data-modeling decision for root, not something to derive.

---

## 8. Priced slices

Each slice is one loadable change with its regression.

| Slice | Change | Added (est.) | Deletes / enables | Needs |
|---|---|---:|---|---|
| S0 | `canonical-map-entry` (`schema_shape.clj:28-36`) accepts Malli naked `k` and `[k props]` entries, preferably by reading `m/children` `[k props schema]` | ~5 | fixes a latent crash (P7); it is a step of cut C4, not a new path | — |
| S1 | `signature/declared-contract`: `md/parse`, the three normalizations and the underivable refusal; `:seon.fn/returns`; convert the three producers | ~45 | — | S0 |
| S2 | JVM arming selects candidates from projection function-contracts, not Var metadata (`instrument.clj:82-104`, `:815-825`, `:906-918`) | ~8 | removes the Var-metadata authority (~8 lines) | — |
| S3 | tail-map output derivation (§2 table) | ~45 | shortens up to ~47 functions today | S1 |
| S4 | explicit-vs-derived advice + census, then promote to refusal | ~20 + 1 schema | — | S1 |
| S5 | one scripted sweep: delete explicit input schemas that S1 derives equally; add `:or`/`:seon.fn/returns` to uncontracted map-input functions | net negative | upper bound 808 pprinted schema lines across 155 functions (the input halves are deletable); the 41 uncontracted map-input functions gain contracts at a cost of a few authored words each | S1, S2 |

- **S0 + S1 + S2 ≈ 58 added lines** is the minimal viable installation, under the ~100-line bar.
- **With S3 and S4 it is ≈ 125.** Per duplicate-paths-audit §11 that total needs deletions to justify it,
  and S5 is the only source. So the order is: S0–S2, then the S5 sweep with measured net lines, then S3
  only if the sweep shows enough decidable tails to pay for its 45 lines.
- **Cost profile:**
  - derivation runs once per changed declaration, keyed by source digest, alongside existing indexing;
  - `md/parse` costs ~0.3 ms per arglist (P1);
  - compile costs ~0.1 ms (P6);
  - no per-call work.

**Unproven** (Astra, falsify these):
1. Call preparation matches a naked reference entry `:seon.db/db` against the supplied-default rows by
   key and shape fingerprint (`supplied-map-entries`, `call_preparation.clj:864-900`). P8 used `[k k]`
   entries.
2. `md/parse` on arglists read with `*reader-resolver*` in CLJS (`signature.cljc:29` reads CLJS without
   aliases): `::alias/k` in `.cljc` sources.
3. Explicit rename entries in `md/-keys` (`destructure.cljc:80`) behave identically for symbol-keyed
   renames under `:syms`.
4. 1.11's closure → analysed-row join by form position (§6.1).
5. Whether the face needs a stored "derived" fact (§3).
6. The census of explicit contracts that S4 would flag.
7. P10 is an edamame scan, not the program graph: counts are approximate. P9 is the authoritative
   uncontracted count.

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
