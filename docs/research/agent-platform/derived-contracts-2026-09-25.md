---
type: design
status: "Opus revision 4: tail calls resolved through the call graph (owner ruling 2026-09-25); review 511b948c6 fixes kept; awaiting Astra re-review"
created: 2026-09-25
scope: Contracts derived from destructured qualified keys (inputs) and clj-kondo return tags (outputs); rows 1.12, 1.13, 1.15
source-pin: 19e9fe5f4 (Seon), befcd302a8e6 (malli), 57252e0797 (clj-kondo fork)
review: derived-contracts-review-2026-09-25.md (511b948c6), verdict implement after fixes
---

# Derived contracts: destructured keys in, returned keys out

Lane design-derived-contracts-opus.
- Revision 3 fixes review 511b948c6 in place: P1-1 through P1-5, and P2-1/P2-2.
- It aligns with requirement rows **1.12**, **1.13** and **1.15** at `0e19055c5`.
- No production code has changed.

**What exists and the approach.** Two dependency parsers already hold every fact the contract needs:
- `malli.destructure/parse` derives an input schema from an arglist
  (`reference-code/malli/src/malli/destructure.cljc:125-162`);
- the clj-kondo fork infers each arity's return tag
  (`reference-code/clj-kondo/src/clj_kondo/impl/analyzer.clj:877-899`), but does not yet export it.

The data flow stays the same:
source → `:seon.fn/spec` on the row → `contract-facts`/`arity-row` → arity rows with input/output refs →
projection → arming, call preparation, `reads-key`, reach.

One pure function at the producer, `declared-contract`, returns one of two things:
- the **explicit** `:malli/schema` when one is present;
- otherwise a **complete derived** contract, or a named refusal.

These are the exactly two contract modes of row 1.12. Nothing downstream learns that a contract was derived.

**Ownership.**
- This document owns row **1.12**, the output half of row **1.13**, and the derived-contract stages of
  row **1.15**.
- `design-function-face-opus` owns **1.14** and the face. It cites this document for every derivation rule.
- Anonymous and generated functions (**1.11**) are a named prerequisite, not delivered here (§6).

---

## 0. Evidence

The author took these observations with finite, read-only probes on `default` (pid 35446), from a private
io-prepl session and the disposable branch `derived-contracts-opus` (retired). The review certified none of
them as installed proof. The scan scripts (P10, P13) were not committed. S5 commits a pinned census script
before any sweep (§8).

| # | Form (abridged) | Observed |
|---|---|---|
| P1 | `(md/parse arglist {::md/inline-schemas false ::md/sequential-maps false ::md/required-keys true})`, six arglists | 1.65 ms total. Examples below the table. |
| P2 | `md/parse` with default options | every key optional, and map arguments also accept kwargs; the options above are required |
| P3 | SCI: `(meta (fn [{:keys [seon.db/db] :as unit}] …))` | keys `(:sci.impl/root-data)`; no `:arglists` |
| P4 | SCI: `(:sci.root/form (:sci.impl/root-data (meta f)))` | `(fn* ([p__4612329] (clojure.core/let [{:as unit, :keys [seon.db/db]} p__4612329] …)))`: the post-expansion form |
| P5 | `(m/schema [:map :seon.turn.loop/schedule] {:registry projection-registry})` | `:malli.core/invalid-schema`, `{:form :seon.turn.loop/schedule}` |
| P6 | `m/function-schema` of a three-key derived contract | 0.10 ms |
| P7 | `seon.program/contract-facts` with naked entries `[:map :seon.db/db …]` | **throws** `UnsupportedOperationException: nth not supported on this type: Keyword` at `schema_shape.clj:28-36` |
| P8 | the same with `[:map [:seon.db/db :seon.db/db] …]` | 7.2 ms; `input-refs #{:seon.cluster/name :seon.agent/id :seon.db/db}`, `output-refs #{:seon.ai/primary :seon.ai/settings}` |
| P9 | `:seon.fn/sym` rows vs `:seon.fn/spec` (a historical live-row observation) | 4,867 rows; 2,099 with a contract, 2,768 without |
| P10 | edamame scan of `src` | 3,354 `defn`s, 1,919 with an attribute-map `:malli/schema`. 196 take only maps with a qualified key in every argument: 155 explicit (808 pprinted schema lines, an upper bound, not a diff) and 41 uncontracted |
| P11 | fork `run!` on a 13-function probe file; cache read back | **resolved** tags (§2.1). They are not the raw tags F1 exports (P1-1). |
| P12 | fork `run!` over `src`, `:skip-lint true` | 2,198 ms elapsed while other work ran concurrently, so it is not an F1 baseline. Real tags for §7. |
| P13 | classifier over P12's cache | 1,904 cached arity entries: **1,454 with a `:ret`**, 450 with `:args` only. Of the 1,454: 163 closed qualified maps, 5 open qualified maps, 40 unions made only of qualified maps, 29 unqualified, 51 empty, 2 mixed, 303 other unions, 861 scalar or collection keywords. Arities with neither `:ret` nor `:args` are dropped by `resolve-arity`, so the total arity count is not in this table. 403 `cond->` forms in `src`. |

P1 examples:
- `[{:keys [seon.db/db seon.agent/id] :as unit} x]` → `[:cat [:map :seon.db/db :seon.agent/id] :any]`.
- `[{:seon.db/keys [db] :keys [seon.render/value]}]` → `[:cat [:map :seon.render/value :seon.db/db]]`.
- The nested `[{{:keys [seon.x/a]} :seon.y/b c :c}]` → `[:cat [:map :seon.y/b [:c :any]]]`.
- `:or` is ignored.
- `[a & {:keys [seon.db/db]}]` → kwargs `[:orn …]`.

---

## 1. Inputs: Malli's parse, three corrections

`declared-contract` lives in `seon.fn.signature` (`src/seon/fn/signature.cljc`), which already reads each
declaration with its aliases (`read-source`, `:17-31`; JVM `#{:clj}` only) and splits the arglists
(`declaration-parts`, `:62-93`).

**Binding owner.** The pure local → source-key → default mapping, `map-binding-entries`, moves unchanged
from `seon.program` (`program.cljc:715-773`) down into `seon.fn.signature`. `program` already depends on
`signature`, so the move settles the dependency direction and leaves one binding parser. `program` calls it
from there. The move adds no net lines.

For each arglist, it calls
`md/parse arglist {::md/inline-schemas false ::md/sequential-maps false ::md/required-keys true}` and uses
the result's `:schema` and `:parsed`, never the result map itself.

**Argument kinds, all-or-nothing.** For each element of `:parsed`, the parsed `:arg` key must be `:map`.
- A symbol (`:sym`), a sequential binding (`:vec`) or a `:rest` makes that arity underivable.
- One underivable arity makes the whole function underivable, unless an explicit `:malli/schema` is present.
- A **zero-arity** arglist derives the input `[:cat]`, since it has no argument to reject; its output must
  still derive.

**Correction 1: unqualified keys derive nothing.** Filter map entries on the binding's actual source key,
which must be a `qualified-keyword?`. String and symbol keys are dropped too. Schema literals are not
walked. A map argument left with no qualified key is underivable.

**Correction 2: a key with a default is optional.**
- A key whose local has an `:or` entry becomes `[k {:optional true}]`. The test is presence
  (`find`, so nil and false defaults count), not truthiness.
- Explicit renames and qualified `:keys` shorthand give the same result.
- A default on a nested local does not make its outer key optional.
- Every other key is required. Required is what call preparation supplies by declared name
  (`supplied-map-entries`, `call_preparation.clj:864-869`; row 1.13).
- Optional means the key may be absent. It never means that a present nil satisfies a non-nil schema.

**Correction 3: nesting.** A nested map under a qualified key keeps only the outer key as a reference
(P1). Inner keys remain binding-entry facts (§4).

**Spellings accepted**, all resolved at read time:
- `{:keys [seon.db/db]}` and `{:keys [:seon.db/db]}`;
- `{:seon.db/keys [db]}` (`md/-qualified-keys`, `destructure.cljc:70-74`);
- `::alias/k` and `::k`;
- explicit renames `{db :seon.db/db}`.

**Declaration join (P1-4).**
- Supported: core `defn`/`defn-`, single or multi-arity.
- Fix at the owner: `declaration-parts` refuses a valid multi-arity *trailing* attribute map, because it
  requires every remaining declaration to be a list (`signature.cljc:81-93`), although
  `declaration-metadata` already reads that map (`:56-59`). Drop that trailing map before the arglist join.
  One line.
- Refused by name as `:unsupported-declaration`, unless explicit: `(def f (fn …))`, `defmulti`
  (its dispatch fn is not the Var's contract), and arbitrary macro invocations.

Owner's example: `(defn f [{:keys [seon.db/db seon.agent/id] :as unit} x] …)` is underivable because of
`x`. Without `x`, it derives `[:cat [:map :seon.db/db :seon.agent/id]]`.

---

## 2. Outputs: the clj-kondo fork's return tag

The owner ruled that there are two sources only: an explicit `:malli/schema`, or the return keys that the
one parser finds. There is no third metadata key and no Seon body walker.

### 2.1 What the fork infers

- `analyze-fn-body` tags the last body expression: the call's `:ret` from `calls-by-id`, or `expr->tag` for
  a literal (`impl/analyzer.clj:877-899`). The tag is stored per arity (`extract-arity-info`, `:913-930`)
  and handed to `namespace/reg-var!` inside `:arities` (`:1956`).
- Map literals keep their known keys (`impl/types.clj:349-382`). Dynamic or generated keys make the map open.
- `if`, `when`, `let` and `do` preserve their tails or union them (`impl/types/clojure/core.clj:71-78`,
  `:199-205`). `union-type` turns any unknown member into `:any` (`impl/types/utils.clj:62-80`).
- `assoc` extends a tagged map seed (`core.clj:132-160`). `merge` and `sorted-map` have key-less returns
  (`:666-668`, `:186-190`).
- User calls become deferred `{:call …}` tags (`types.clj:448-476`). Those are resolved only when the cache
  syncs (`cache.clj:146-169`; `types/utils.clj:178-255`).
- `analysis/reg-var!` (`analysis.clj:87-100`) exports none of this.

**Raw tags differ from resolved ones (P1-1).**
- Built-in returns and function-spec returns come back wrapped as `{:tag t}` (`types.clj:448-476`). The
  call record keeps that envelope (`analyzer.clj:3784-3788`), and `analyze-fn-body` takes it as is.
- A raw `assoc` tail is therefore `{:tag {:type :map …}}`, and `let`/`if` results may nest envelopes.
- P11's resolved cache hides this, because `resolve-arg-type` unwraps `:tag` recursively.
- `strip-positions` (`types/utils.clj:273-286`) walks sets and `:val` only: it neither unwraps a top-level
  `:tag` nor sanitizes a `:call`.

**Resolved tags observed** (P11 probe file):

| Tail | Resolved `:ret` |
|---|---|
| `{:seon.ai/primary 1 :seon.ai/backup "x"}` | closed map; value tags `:pos-int`/`:string` |
| `(assoc {:seon.a/b 1} :seon.a/c "s")` | closed map, both keys |
| `(assoc unit :k 1)` on a destructured `:as` | `:associative` |
| `(merge {…} {…})` | `:nilable/map` |
| `(cond-> {:seon.a/b 1} x (assoc :seon.a/c 2))` | **no `:ret`** (cause below) |
| `let` tail / `if` / `when` | map / set of both maps / `#{:nil map}` |
| `(sorted-map …)` | `:sorted-map` |
| user `defn` call, same run | the callee's map, resolved from `{:call …}` |
| `(:k (user-fn x))` | the value tag, through `:kw-calls` |
| multi-arity | one `:ret` per arity |

Real functions (P12):
- `seon.turn/provider-targets`: closed map of four qualified keys.
- `seon.fn/stored-namespace-context`: unqualified keys.
- `seon.flow/with-submission-filter`: `:associative`.
- `attempt-request`, `compact-function-value`, `unknown-stable-evidence` and `contract-facts`: no `:ret`,
  because they are `cond->` tails.

**Cause of the lost `cond->` return.** The review identified it; the fix is in F2.
- `analyze-fn-body` looks up the tail's return under the **original expression's `:id`**
  (`analyzer.clj:879-889`).
- The macro dispatch replaces the expression with a freshly built node: `expand-cond->` at
  `analyzer.clj:3431-3434` and `macroexpand.clj:55-85`, whose outer `let` node carries no `:id`.
- The same pattern applies to `->` (`:3406`), `->>` (`:3408`), `some->` (`:3410`), `doto` (`:3414`),
  `..` (`:3420`) and `do-template` (`:3309`).
- This diagnosis is from source, not from an executed run.

### 2.2 F1 — export normalized raw return tags (fork)

An opt-in analysis option, `:var-definitions {:arity-returns true}`:

- `clj_kondo/core.clj:146-216` reads it into ctx as `:analyze-arity-returns?`.
- `impl/analysis.clj:87` `reg-var!` adds `:arity-returns {arity-key tag}` from `(:arities raw-attrs)`.
  - `arity-key` is the fixed-arity integer or `:varargs`, as in `extract-arity-info`.
  - An arity whose `:ret` is missing gets **no entry**. Absence means "no return inferred", which is
    distinct from an inferred `:nil`. Seon joins the export against the declaration's complete arity set
    (`:fixed-arities`/`:varargs-min-arity`, already exported), so a missing return cannot silently vanish.
- **One export normalization** at the fork boundary, applied recursively:
  - unwrap `{:tag t}` envelopes;
  - keep bare map tags `{:type :map :val {k {:tag t}} :open? true}` with positions stripped;
  - keep union sets;
  - keep keyword tags, including `:any`;
  - reduce each deferred call to `{:call {:resolved-ns :name :arity}}` **plus `:kw-calls` when present**.
    Stripping `:kw-calls` would turn an extracted value into its callee's whole return map.

  This normalizes the fork's own data; it is not a second inference engine.
- **Acceptance tests** (fork `test/clj_kondo/analysis_test.clj`), one expected raw export each:
  - map literal;
  - `assoc` on a literal;
  - nested `let`/`if`;
  - direct user call;
  - call through a local binding;
  - a branch with an unknown value (`:any`);
  - `when` (`:nil` member);
  - multi-arity;
  - variadic;
  - a tail whose user call has a **configured** return (`types.clj:457-476` replaces the call with a
    structural tag; see §2.4).
- **Cost.** Traversal happens only when the flag is on, and is proportional to the number of exported tag
  nodes. Measure these under Seon's *publication* configuration (`src/seon/fn/analyzer.clj:45-57`) before
  claiming a number: whole-`src` time and allocation with the flag off and on, export bytes, and one
  changed declaration.
- **Size:** about 25 source lines and about 60 test lines.

**Seon side of F1:**
- enable `:arity-returns true` at `src/seon/fn/analyzer.clj:30-39`;
- keep it in `var-definition` (`:124-132`);
- declare it in the analyzer's output contract (`:666-690`) as `:seon.fn.analyzer/arity-returns`, a named
  recursive schema of the normalized grammar (row 1.15: the parser's output is specified data, validated
  where Seon receives it).
- **Size:** about 12 lines.

### 2.3 S3 — translate a tag into a Malli output (Seon)

The translation is a total function of the normalized tag:

| Tag | Result |
|---|---|
| map with at least one qualified keyword key | `[:map k …]`: naked references, required, unqualified keys dropped, value tags ignored (the registered schema owns values), `:open` adds nothing (maps stay open) |
| map with no qualified key | refuse `:unqualified-return-keys` |
| set | translate each member recursively, dedupe equal forms, sort deterministically, emit `[:or …]`. `:nil` stays a `:nil` alternative. **Any unsupported member refuses the whole derivation**; members are never filtered out, and there is no component collapse (P1-2). |
| `:nil` alone | `:nil` |
| `{:call {:resolved-ns n :name g :arity a}}` | g's **effective output** for arity `a` (§2.4), copied as a Malli form, so named schemas and named errors survive |
| `{:call … :kw-calls [k]}` (one step) | `k`'s registered schema when every map member of g's effective output requires `k`, else `[:maybe k]`. A non-map member of g's output refuses. |
| `:any`, other keywords (`:associative`, `:nilable/map`, `:sorted-map`, scalars), missing arity entry | refuse `:unsupported-return-tag` / `:missing-return`, naming the tag and arity |

- **Bound.** At most 64 union alternatives per arity. Beyond that the derivation refuses
  `:union-bound`, naming the arity and member count. 64 is a translation bound to be checked against the
  largest member count in S5's committed census; it does not claim to bound kondo's own union construction.
- **Size:** about 30 lines.

**Optional-key compression waits** (S3b, after F2). An `[:or]` of cond-> power sets is exact but ugly.
- One candidate equivalence rule: when the members' key sets are *exactly* `{B ∪ S : S ⊆ O}` over
  registered references, emit one `[:map B [o {:optional true}] …]`. The collapse is stricter than the
  union only for a present `o` whose value violates `o`'s registered schema. That is what "one schema per
  identity" already requires of `o`.
- Astra must rule on this rule before it lands. It is not in the first cut.

### 2.4 Tail calls: resolved through the call graph (owner ruling 2026-09-25)

The owner ruled: resolve tail calls. "I always want the system to know and understand what data is where."

**Rule.** When f's output tag is a call to g, or a union containing call and map tails, f's output is g's
**effective output contract**:
- g's explicit `:malli/schema` when present, which always wins;
- otherwise g's derived output.

kondo's cache-resolved structural tag for g is never consulted. F1 exports the raw `{:call …}` name, so
Seon resolves the name, not kondo's guess. The applicable arity of g is chosen by Malli bounds
(`m/-function-info` `:min`/`:max`) for the call's arity.

**Dependency facts.** Each of f's derived arities records:
- `:seon.fn.arity/output-from`: the set of qualified callee symbols whose effective output f's output
  copies. It is always a subset of f's existing `:seon.fn/calls` (validated at the producer), so it is a
  marker on an existing edge, not a new graph.
- `:seon.fn.arity/return-tag`: the normalized F1 tag as EDN, validated by `:seon.fn.analyzer/return-tag`
  (row 1.15). This is the parser observation for f's current source, replaced whenever f's row is. It lets
  the writer re-derive f without re-running kondo or reading f's file.

These are the only two new attributes. They are declared in `resources/seon/schemas/seon.fn.arity.edn`
next to `input-refs`/`output-refs`. Explicit contracts carry neither, because explicit ends the chain.

**Freshness without a stale window: one function, both writers.**
`seon.program/derive-outputs` is pure over the writer's transaction database value and the candidate rows:
1. **Changed set C:** candidate function rows whose effective output differs from the stored one, including
   deletions.
2. **Affected closure A:** repeatedly add every stored arity whose `:seon.fn.arity/output-from` names a
   member of C ∪ A. The query uses the existing callers index shape, the same Datalog as `caller-files`'
   `:seon.fn/calls` join (`fn.clj:2530-2536`), with `output-from` in place of `calls`.
3. **Dependency order:** a topological order over `output-from` restricted to C ∪ A (Kahn). Explicit
   contracts and callees outside C ∪ A are leaves, read from the transaction database's projection.
4. **Re-derive** each member of A from its stored `return-tag` and the now-current callee outputs, then emit
   its updated `:seon.fn/spec` and arity rows.
5. **Commit together.** All of these rows join the candidate's transaction; there is no second transaction
   and no stale window.

It runs where the basis cannot move:
- **File publication:** `index!` (`fn.clj:3835`) writes an unpublished scratch branch that the population
  owns ("nothing can change its database between this diff and write", `fn.clj:3918-3919`). Its
  `desired-rows` → `add-contract-facts` step (`:2882`) calls `derive-outputs` before the write.
- **SCI evaluation:** the settlement transaction function that admits the evaluation's program rows calls
  the same `derive-outputs` on the transaction's own database, as `:db.fn/call` provides
  (`reference-code/datahike/src/datahike/db/transaction.cljc:1171-1172`). Which function that is must be
  pinned by S6; the candidate is `seon.turn/receipt-settle-call` (`turn.clj:1584`), unverified.
- A branch's redefinition of g therefore re-derives f on that branch, in the same transaction.

**Refusals by name.** Each one reads "return depends on g, which …; declare :malli/schema":

| Case | `:seon.fn.derived/reason` |
|---|---|
| cycle in C ∪ A, including direct and mutual recursion | `:recursive-return` (names the cycle) |
| call kondo cannot resolve: `apply`, a call through a local or higher-order argument (`{:call}` with no `:resolved-ns`), protocol method, Java interop | `:unresolved-call` |
| g is a multimethod | `:multimethod-callee` |
| g has neither an explicit nor a derivable contract, e.g. a dependency function or an uncontracted Seon function | `:uncontracted-callee` |
| g is in no candidate row and not in the transaction database's projection (cross-namespace, not yet published) | `:unpublished-callee` |
| g was deleted in this transaction | `:unpublished-callee` |
| `:kw-calls` longer than one step, or g's output has a non-map member | `:unsupported-return-tag` |

**Explicit ends the chain.** A delegating function whose callee is explicit derives exactly that explicit
output form, including `[:or ok-schema :seon.x/error]`, so declared error alternatives stay named
(AGENTS.md "Declare errors as flat data").

**Cost.**
- Work is proportional to |C ∪ A| times tag size, plus one indexed query per closure round.
- It is not proportional to the whole program, and there is no per-call work.
- A body-only edit to g that leaves its effective output equal adds nothing to C, so it re-derives no one.

**Configured-return precondition.** A configured kondo return can replace a call with a structural tag
before F1 sees it (`types.clj:457-476`). Seon's generated Malli type config is `{}` today
(`.clj-kondo/metosin/malli-types-clj/config.edn`). F1's test and a committed check keep that true for
first-party namespaces, so Seon never mistakes kondo's structural guess for a name it could resolve.

**Render and call time: the actual value against the effective output.**
- **Call time:** the armed wrapper validates every return against f's effective output (S2 arms derived
  contracts; `instrument.clj:624-750`). A delegating f therefore validates the same value g already
  validated. That double check costs one cached validator call per return and must be measured in S6.
- **Render time:** `seon.render/selection` (`src/seon/render.clj:671`), rule 2 of the one-render-path
  design (`one-render-path-design-2026-09-25.md` §1, rule 2). For a top-level contracted call it validates
  the result against the head's declared output alternatives through `schema/function-matching-outputs-in`
  (`src/seon/schema.clj:3894`), reading `:seon.schema.projection/function-contracts`. Derived contracts are
  stored there like explicit ones, so rule 2 needs no change: one satisfied alternative selects that
  schema's renderer, and none diagnoses.

### 2.5 Underivable outputs

Row 1.12 rules this: the entry gate refuses by name, citing the tag it saw, e.g.
"return is :associative; declare :malli/schema". Every admitted function stays contracted. This revision
drops the earlier "unknown-typed" choice.

Underivable in v1:
- `cond->`, `->` and similar tails, until F2;
- `merge`, `sorted-map`, `into`;
- `assoc` on a destructured argument (the F3 question);
- tail calls that §2.4 refuses (unresolved, recursive, multimethod, uncontracted or unpublished callee);
- keyword access;
- unqualified-key maps;
- `:any` branches;
- missing returns;
- any union containing one of these.

---

## 3. Explicit contracts, storage and the producer contract

**Explicit wins, with a short-circuit (P1-5).**
- When the declaration's attribute map has `:malli/schema`, `declared-contract` returns it and derives
  nothing. There is no disagreement check and no new refusal. S4 is deleted from this design.
- Only `:malli/schema` counts as explicit. Other attribute-map keys, such as `:seon/tasks`, `:doc` and
  `:seon.workload`, never select a mode.
- Ordinary contract validity and source/arity compatibility still refuse, as today (`arity-row`,
  `program.cljc:1004-1030`).
- An explicit contract is validated and armed even when derivation would have failed.

**Stages as specified data (row 1.15).** Each producer validates the value it builds:

| Stage | Schema | Producer |
|---|---|---|
| parser output | `:seon.fn.analyzer/arity-returns` | `seon.fn.analyzer/analyze` output contract |
| derivation result | `[:or :seon.fn/spec :seon.fn.derived/underivable-error]` | `seon.fn.signature/declared-contract` |
| refusal | `:seon.fn.derived/underivable-error` | the same function |
| stored contract | `:seon.fn/spec`, then `contract-facts` compiles it with `m/function-schema` | the existing writer path |

`:seon.fn.derived/underivable-error` is one named flat error over `:seon.error/base`. It carries:
- `:seon.error/operation 'seon.fn.signature/declared-contract`;
- `:seon.error/member`, the function symbol;
- `:seon.fn.derived/arity` (the join key);
- `:seon.fn.derived/direction` `[:enum :input :output]`;
- `:seon.fn.derived/reason`, an enum over the bounded reasons in §1–§2:
  `:positional-argument :sequential-argument :rest-argument :no-qualified-key :unsupported-declaration
  :unqualified-return-keys :unsupported-return-tag :missing-return :union-bound :unregistered-key
  :recursive-return :unresolved-call :multimethod-callee :uncontracted-callee :unpublished-callee`;
  - the call-graph reasons also carry `:seon.fn.derived/callee` and, for cycles, the cycle path;
- `:seon.error/offending`, the tag or argument form;
- `:seon.error/expected :malli/schema`.

On success the row gets only `:seon.fn/spec`, a string, as for explicit contracts
(`schema/canonical-definition`, `fn.clj:738-744`). An error value is never serialized as a spec.

**Admission join, from source.**
- `definition-rows` builds rows from the indexer's `source-rows`, lays explicit runtime metadata over them,
  and materializes them **before** the gate and arming (`eval.clj:489-530`, `:3820-3840`). That order stays.
- `declared-contract` runs inside the indexer's `var-row` (`fn.clj:644`), where the analyzer entry
  (arglists and F1 tags) and the source are both present.
- A derivation error travels on the in-memory analysed row as `::underivable` (the error value), never as
  a stored attribute. `canonical-row-in` keeps only owned attributes, and S1 proves that.
- `refuse-uncontracted-definitions!` (`eval.clj:456-487`) passes it to `missing-contract`. That function
  gains the arity `[function-symbol underivable]` (`eval.clj:1763-1780`), and its declared error
  (`:seon.sci.eval/declaration-absent-error`) accretes an optional `:seon.fn.derived/underivable` member.
  The existing rollback then runs unchanged.
- The reader-side `function-declaration` (`sci/reader.cljc:251`, `:285`) has no F1 evidence. It keeps
  **explicit only** and never derives.
- The gate's known limit remains: code executed inside the defining form runs before the post-evaluation
  gate (`eval.clj:459-467`).
- File publication today admits uncontracted rows (P9; §9). There, a derivation error is reported in the
  publication result. Gating it is that owner's cut.

**Storage and consumers stay unchanged.** They read `:seon.fn/spec` or rows derived from it:
- `contract-facts` and arity rows with refs (`program.cljc:1004-1115`);
- the projection's function contracts (`fn.clj:2895`, `:3084`);
- SCI arming (`eval.clj:767`);
- call preparation (`call_preparation.clj:864-900`, `:989`);
- `reads-key` (`src/my/program.clj:211`);
- reach.

Whether a contract was derived is itself derived from the source: no `:malli/schema` there. Write-back
keeps the short source.

**Recompiled only when a dependency changes (1.12).**
- A derived contract's inputs are:
  - the declaration's source digest and aliases;
  - the registered schemas of its named keys;
  - the effective outputs of its `output-from` callees. §2.4's `derive-outputs` re-derives on those in the
    writer's transaction.
- Key-schema changes already propagate through `reference-fingerprints` (`schema_shape.clj:119-146`),
  `shape-dependent-rows` (`fn.clj:3116-3137`) and `current-wrapper?` (`instrument.clj:752-766`). The spec
  keeps the key names, so the spec string is unchanged while the validator recompiles.
- There is no source-only compiled-contract memo.

**Arming prerequisite (S2).**
- JVM arming filters candidates through Var metadata: `mi/-schema` in `armable` (`instrument.clj:82-104`),
  `collect-contracts!` (`:815-825`) and `apply!` (`:906-918`).
- A derived contract has no such metadata, so it would never arm.
- S2 selects candidates from the projection's `:seon.schema.projection/function-contracts`, which
  `compiled-wrapper` already prefers (`:627`). It keeps the bound and non-primitive checks, and passes the
  projection to `armable`'s parity caller.
- S2 proves full adoption, incremental adoption, contract removal and retained profiling.

---

## 4. Links

- **Reads and returns.** Derived keys become the existing `:seon.fn.arity/input-refs` and `output-refs`
  (`schema-reference-keys`, `program.cljc:890-907`; P8). `reads-key` groups them as contract refs.
  No keyword edge is added.
- **Destructures.** Nested inner keys are already binding-entry facts with `key-keyword`
  (`typed-key-facts`, `schema_shape.clj:220`). An optional `reads-key` group can come later.
- **Unregistered qualified keys refuse by name** as `:unregistered-key`, wrapping Malli's `{:form k}` (P5).
  - For an output key, this function is the key's producer, so the refusal says to declare its schema there.
  - For an input key, it names the key.
  - A refusal is the agent's repair data and creates no task. When root schedules the work, the task uses
    the one `:seon/tasks` key on the holder (row 1.9, `0e19055c5`).

---

## 5. Presentation

The stored contract is data. The face owner renders it.

Naked references `[:map :seon.db/db]` are Malli's shortest form, and `m/form` preserves them
(`-parse-entry`, `malli/core.cljc:487-535`; `:naked-keys` default at `:1210-1212`). S0 makes storage accept
all four of Malli's entry spellings (naked `k`, `[k]`, `[k props]`, explicit child), so derivation never has
to write `[k k]`.

---

## 6. Inline and generated functions: prerequisite 1.11

This design **does not deliver** contracts for anonymous functions.
- F1 exports var definitions, not anonymous `fn` occurrences.
- `declaration-parts` accepts `defn`/`defn-` only (§1).
- The closure's runtime form is expanded (P4), so derivation never reads it.

Until 1.11 supplies all three of the following, a changed interpreted function without an explicit
contract refuses by name as `:unsupported-declaration`:
- a generated name;
- a correspondence from changed Var to analysis row to authored binding;
- the authored source.

Nothing is inferred from a macro placeholder.

After 1.11, the same `declared-contract` applies to the authored `fn` source and its kondo arity. Supplied
defaults for function values still need audit cut C3's retained-contract preparation
(`call_preparation.clj:989-1010`, `:1538-1541`; `sci/kernel.clj:633-640`). An inline function's contract
never creates a `:seon.fn/sym` row.

---

## 7. Five real functions

These are the P12 tags and the §1–§2 rules, with registry facts from `default`.

**7.1 `seon.turn/provider-targets`** (`src/seon/turn.clj:3930`; uncontracted).
- Its inputs are explicit renames.
- Tag: a closed map of four qualified keys.
- Derived contract:
  `[:=> [:cat [:map :seon.db/db :seon.cluster/name :seon.agent/id]] [:map :seon.ai/primary :seon.ai/backup :seon.ai/settings :seon.turn.loop/schedule]]`.
- **Refuses** `:unregistered-key :seon.turn.loop/schedule`. This function is that key's producer.
- It also exposes a defect: it returns `:seon.ai/backup` as nil although `:seon.ai/target` does not allow
  nil. Fixing it with `cond->` makes the output underivable until F2.

**7.2 `seon.render.ns/compact-function-value`** (`src/seon/render/ns.clj:488`).
- Input, with `:or {doc nil spec nil}` added:
  `[:map :seon.fn/sym [:seon.fn/doc {:optional true}] [:seon.fn/spec {:optional true}]]`.
- Output: a `cond->` tail with no `:ret`, so it refuses `:missing-return` until F2.
- After F2 it is a four-member `[:or]` (S3b could compress it).

**7.3 `seon.turn/attempt-request`** (`src/seon/turn.clj:3912`).
- The input refuses `:unregistered-key :seon.turn.loop/attempt-evidence`.
- The output `(cond-> (merge {…} evidence) …)` stays underivable even with F2, because `merge` with a
  non-literal argument has no keys. It needs an explicit schema.

**7.4 `seon.render/unknown-stable-evidence`** (`src/seon/render.clj:972`).
- The input derives, with `:or` on four keys.
- The output `(cond-> (sorted-map …) …)` gives `:sorted-map` even after F2. It needs an explicit schema.

**7.5 `seon.fn/stored-namespace-context`** (`src/seon/fn.clj:258`).
- Input `[:map :seon.ns/requires :seon.ns/aliases :seon.ns/refers :seon.ns/imports]`.
- The output has unqualified keys, so it refuses `:unqualified-return-keys`. The fix is qualified return
  keys or an explicit schema.

A negative case: `seon.flow/with-submission-filter` (`src/seon/flow.clj:476`).
- Input keys `:seon.flow/io-parallelism` and `:seon.flow/io-active-count` are unregistered, so it refuses.
- Output: `:associative`.

In this sample:
- 4 of the 5 inputs derive (7.3's input refuses on an unregistered key), and all 5 would derive once
  their keys are registered.
- One output derives in shape (7.1), but it still refuses on an unregistered key.
- The short form therefore pays off mainly in new code written in the style, and after F2.

---

## 8. Priced slices

**Seon slices.** Each is one loadable change with its regression.

| Slice | Change | Added (est.) | Needs |
|---|---|---:|---|
| S0 | `schema_shape` accepts all four Malli entry spellings in `canonical-map-entry` (`:28-36`), `entry-row` (`:257`) and `read-form` (`:379`, whose decoder always appends a child). Proves encode/decode, reference identity and fingerprint equality across the spellings, and that call preparation reaches naked references | ~15 | — |
| S2 | arming from projection contracts (§3) | ~12 | — |
| S1 | `declared-contract` inputs: argument kinds, the three corrections, declaration join incl. trailing attr map; move `map-binding-entries` (net 0); the `underivable-error` schema | ~40 | S0 |
| F1 | fork export and normalization (§2.2) — **fork, priced separately** | ~25 src + ~60 test | — |
| S3 | Seon side of F1 (~12) + tag translation (~30) + gate integration: `::underivable` on the analysed row and the `missing-contract` arity (~12) | ~54 | S1, F1 |
| S6 | call-graph resolution (§2.4): `derive-outputs` (closure query, Kahn order, arity choice, one-step `:kw-calls`, the five refusal reasons) ~30; the two arity attributes with their schemas ~6; calls from `add-contract-facts` and from the settlement transaction function ~6; the subset validation of `output-from` against `:seon.fn/calls` ~3 | ~45 | S3 |
| S5 | commit a pinned census script (`tmp/` or `test/`). Report the intersection of all-arity input eligibility, output eligibility and registered keys, then one scripted sweep deleting only explicit contracts whose derived contract is **equal** as data (requiredness, named errors, guards). Measure the net lines | net negative, measured | S0–S3, S6 |

**Fork slices after the first cut**, each proven by fork tests plus a rerun of P13:

| Slice | Change | Added (est.) |
|---|---|---:|
| F2a | carry the original `:id` onto macro-expanded nodes at the seven dispatch sites (`analyzer.clj:3309-3432`), one `with-original-id` helper. Restores `cond->`/`->`/`some->`/`doto` tail returns | ~10 |
| F2b | `assoc`'s `:fn` (`core.clj:132-160`) maps over a union seed member by member, since a `cond->` chain's second clause sees a set seed today and returns `:associative`. Any kondo-side union bound is part of this slice | ~6 |
| F3 | a destructured `:as` local's tag. Destructuring does not prove key presence, and defaults populate locals, not the map. So only an **open** map with no required keys is sound, and whether that yields anything useful is unproven. Deferred | ? |
| S3b | optional-key compression rule (§2.3), after F2, pending Astra's ruling | ~8 |

`merge` inference is not proposed: overwrite order and unknown maps make naive `:val` merging unsound.

**First cut:** S0, S2, S1, F1, S3, S6, then S5.
- Seon adds about 166 lines, and the fork about 25 plus tests.
- That is **above the ~100-line bar**. The justification owed to root:
  - it lets go of every explicit contract that S5's census shows is derived equally;
  - it gives contracts to uncontracted map-input functions without new authored text;
  - it deletes nothing else.
- **S6's own justification.** Delegating functions are the class whose explicit output only restates a
  callee's contract. S6 makes their explicit contracts deletable, and it lets new delegating code omit
  them. S5's census must therefore report that class separately:
  - arities whose F1 tag is a call, or a union of calls and maps, onto contracted callees;
  - with their explicit contract lines;
  - with how many derive **equal** through S6.

  S6 lands only if that class's measured deletions, plus S5's other deletions, at least cover the ~166
  added lines. Otherwise the numbers go to the owner. The proofs S6 needs are listed under acceptance
  cases 7, 10 and 11 below.
- S5's measured net decides whether the cut pays.
- There is no smaller complete variant: row 1.12 forbids a derived input combined with an explicit output.
- If S5 shows no net deletion, report that to the owner with the number instead of landing an unpaid cut.

**Costs to measure.** None of these has been measured:
- F1 on/off time, allocation and export bytes;
- derivation per changed declaration;
- S0 fingerprint time.

Per-call work is zero, because all of this runs at declaration.

**Acceptance cases** (from the review), each asserting the wanted behaviour:
1. multi-arity joined by arity identity, with one missing return refusing the whole function;
2. variadic refused;
3. the trailing attribute map;
4. `.cljc` duplicates joined on the same `:clj` occurrence;
5. changing a derived key's schema with unchanged source re-arms, and deleting the key refuses;
6. explicit wins over an unsupported derivation;
7. f → g → h in one batch derives in dependency order. Changing only h's explicit output re-derives g and
   f **in the same transaction**, both at publication and through SCI settlement on a branch. An unrelated
   write re-derives nothing.
8. naked, optional and explicit entry spellings reach call preparation's required-entry facts;
9. an unregistered output key refuses with its producer named;
10. every §2.4 refusal: direct and mutual recursion, `apply`, a local function call, a multimethod, a
    dependency callee, an unpublished cross-namespace callee, and deletion of g with f surviving;
11. explicit g beats kondo's structural tag, and `[:or ok :seon.x/error]` flows to f with its names intact;
    render rule 2 selects the error renderer for f's error result.

**Unproven** (falsify these):
1. F1's exact raw shapes, until its tests pin them.
2. The F2a diagnosis (static only), and whether F2a plus F2b restores `cond->` chains.
3. The 64-alternative bound against the real maximum.
4. That `canonical-row-in` never stores `::underivable`.
5. That P13 counts transfer from resolved tags to F1 raw tags.
6. The 1.11 correspondence (§6).
7. Which SCI settlement transaction function admits program rows (§2.4). `receipt-settle-call` is a
   candidate from its name only.
8. The measured cost of `derive-outputs` per closure and of the double return validation (§2.4).

---

## 8a. Proposed row 1.12 wording (for root to integrate; this lane does not edit the requirements)

> **1.12 A contract may be derived.**
> - **Inputs** come from destructured fully qualified keys: `(fn [{:keys [seon.db/db seon.render/value]}] …)`
>   binds the locals and declares the input map from those keys' registered schemas. A key with an `:or`
>   default is optional. Unqualified keys, positional, sequential and rest arguments derive nothing, and they
>   require an explicit contract. Positional `:cat` contracts remain.
> - **Outputs** come from two sources only:
>   - an explicit `:malli/schema`, which names inputs and outputs in one place and always wins;
>   - otherwise, the return keys that the one parser (the clj-kondo fork's type inference, exported per arity)
>     finds. A tail call resolves through the call graph to the callee's effective output contract,
>     explicit or derived, so the system knows what data is where ahead of time. The writer re-derives
>     dependent callers in dependency order in the same transaction as the change, from the recorded
>     `output-from` facts.
> - There is no third metadata key. There are exactly two contract modes, complete explicit or complete
>   derived, with one effective contract value, recompiled only when a dependency changes (source, key
>   schemas, callee outputs).
> - When no output can be found, the entry gate refuses by name, citing what it saw: "return is
>   :associative; declare :malli/schema", or "return depends on g, which is recursive; declare
>   :malli/schema". Every admitted function stays contracted.
> - At call and render time, the actual value is checked against the effective output: the armed wrapper,
>   and render selection rule 2.
> - Design: `derived-contracts-2026-09-25.md`.

---

## 9. Findings outside this design

- **2,768 of 4,867 function rows had no contract** (P9, historical). File publication admits uncontracted
  rows, against "Contract every admitted function". Root must record this; this lane owns no issue file.
- **Three `:seon.fn/spec` producers:** `fn.clj:738`, `sci/eval.clj:514` and `sci/reader.cljc:285`.
  The reader one is a duplicate path for the audit.
- **Ugly output.** The MCP JVM printer spends about 600 characters of requery metadata eliding a two-item
  vector.
- **Cost.** A first SCI evaluation on a new branch took 1,396 ms and allocated 441 MB, in acquisition
  (P3). That is the acquisition owner's to fix.
