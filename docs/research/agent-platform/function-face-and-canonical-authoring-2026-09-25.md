---
type: design
status: "Opus first pass (lane design-function-face-opus); awaiting Astra review (AGENTS.md step 4); no production code"
created: 2026-09-25
scope: one function face (AI + HTML), canonical authoring persisted through the gated merge route, one rule source for hooks and linters, the face's needs from derived contracts, and the inline-function blockers root forwarded
source-pin: 19e9fe5f4 (requirements 1.9–1.14); code citations valid at 48ceb9cad/19e9fe5f4 (no source change between them in the cited files)
requirements: language-requirements-2026-09-24.md rows 1.9, 1.11, 1.12, 1.13, 1.14
depends-on: query-render-building-block-2026-09-25.md (1273211e8, `seon.render/units`); derived-contracts-2026-09-25.md (lane design-derived-contracts-opus); one-repl-language-2026-09-25.md §3.3 (write-back)
---

# The function face and canonical authoring

**What exists.** A function is a program row (`:seon.fn/fn`, `resources/seon/schemas/seon.fn.edn:127-131`).
The row carries its exact source, its docstring, `:seon.fn/spec` and `:seon.fn/arglists`. The spec is already
canonical EDN, produced by `seon.schema/canonical-definition` (`src/seon/schema.clj:642-758`) at indexing
(`src/seon/fn.clj:726-731`); the arglists are a `pr-str` string. The row also has arity components with
contract→schema refs (`src/seon/program.cljc:1007-1081`), binding rows parsed from destructuring
(`program.cljc:733-773, 842-872`), call edges, and reach queries (`my.program/callers`, `my.program/tests-reaching`,
`src/my/program.clj:144-177`). The schema's declared AI/HTML pair is `seon.render.ns/function-ai` /
`function-html` (`src/seon/render/ns.clj:744-787`). Malli owns forms (`m/form`, `malli.core:2585`), arity info
(`m/-function-schema-arities`), the `:->`→`:=>` proxy (`malli.core:2376-2385`) and destructuring-derived
input schemas (`malli.destructure/parse`, `reference-code/malli/src/malli/destructure.cljc:135-163`); pin
`reference-code/malli` at `befcd302`.

**The principal approach.** The face is not a new renderer. It is the `:seon.fn/fn` declared pair, rewritten to
render the row's own data, and reached through the one building block `seon.render/units` with a pull of the
row as its query source. Canonical authoring is one pure transformation of a declaration's **head** (name,
docstring, attribute map). The same function produces the face's "stored as" preview, the hook's advisory,
and the text that the gated merge route reindexes and writes back. So grep agents and REPL agents read the
same bytes.

## 0. Broken things first

All probes: MCP `eval_clj`, JVM mode, root `/Users/sean/src/seon`, cluster `default`, `read_only` true, pid 35446
(start `2026-09-25T14:24:44Z`, health **faulted**, 21 open faults, including "JVM RESTART NEEDED: loaded
dependencies changed"). They are read-only queries over finite stored values; no new code ran.

1. **Today's function face shows no contract, no docstring and no source.** `(seon.render.ns/function-ai {:seon.db/db db
   :seon.render/value {:seon.fn/sym 'seon.program/definition-digest}})` returned, in 1 ms, five comment lines of
   unevaluated queries and the form `(doc seon.program/definition-digest)`. The row itself holds a two-arity
   `:seon.fn/spec` and a five-line docstring. The agent is told to go and read what the renderer already had.
   `function-html` embeds that same text in a `<pre>`, beside the raw spec string.
2. **Requirement 1.14's automatic substitution is refuted by the data.** Build an inverse index from each
   registered form to its keys over the carried projection (3,394 forms, 2,132 distinct, 133 forms shared
   by several keys; 3 ms). Then scan every vector subform of all 2,093 stored specs (14 ms). The result:
   **369 distinct (function, inline piece) pairs** structurally equal a registered schema. Of these, **297 are
   ambiguous**: `[:string {:min 1}]` equals 40+ attribute keys, and `[:int {:min 0}]` equals 60+. The unique matches
   are mostly coincidences: `[:map]` ≡ `:seon.problems/request` inside `seon.cluster.store/datahike-configuration`,
   and `[:or :symbol :string]` ≡ `:seon.sci.reader/refused-token` inside `seon.error/problem-sentence`. Limit the
   candidates to the function's own and required namespaces and **56 unique matches** remain. They are still mixed:
   `[:vector :seon.test/sym]` ≡ `:seon.fn/tests` in `seon.test/reaching` is plausible, while `[:set :string]` ≡
   `:seon.test/adoption-inputs` in `seon.issue/index-tx` is wrong. AGENTS.md already says it: "structural resemblance
   is not identity." Replacing a piece with a name binds the function to that identity's future changes. That is a
   semantic decision, not a notation change (§2.3 prices the options).
3. **`:seon.fn/arglists` is not native data.** It is a `pr-str` string with reader-dependent tokens, and
   `clojure.edn/read-string` fails on it (`Invalid token: ::keys`; stored for e.g. functions destructuring
   `{::keys […]}`). The same holds for `:seon.fn/spec` (a string, requirement 5a.5 family). Reading it back needs
   `*ns*` bound to the defining namespace. This breaks "Preserve native values". The face must not reparse it.
   Its owner is the indexer (`fn.clj`). Recorded here; no issue file exists for it; the one-repl design §0
   names the spec-as-string gap.
4. **The namespace view recomputes contract references by reparsing strings and swallowing failures.**
   `render/ns.clj:121-253` compiles each stored spec against a private registry that maps every qualified
   keyword to `:any` (`schema-ref-registry`, `:123-129`). `read-edn` and `schema-form-refs` use
   `(catch Throwable _ nil)` (`:131-153`). The same references are already stored per arity as
   `:seon.fn.arity/input-refs`, `output-refs` and `guard-refs` (`program.cljc:1073-1081`). This is a duplicate path
   that hides causes. `seon.schema/direct-references*` (`schema.clj:61-79`) is the correct Malli walk.
5. **`:seon.schema/ns` is empty for every schema key** (the probe's join over `:seon.schema/key` →
   `:seon.schema/ns` → `:seon.ns/name` returned 0 rows). Ownership by namespace therefore falls back to the keyword's
   namespace, which is what the file layout already does (§1.5).
6. `(doc f)` in SCI resolves to `seon.bootstrap/doc` → `clojure.repl/doc` (`src/seon/bootstrap.clj:172-175`,
   `src/seon/sci/eval.clj:293-306, 1967`). That prints to `*out*`, so it bypasses the renderer. Requirement 1.9
   says `(doc f)` shows the handles. §1.4 routes it through the face. **Not probed in SCI** (no SCI evaluation in
   this lane).

## 1. The function face

### 1.1 Data flow

```
(seon.render/units {:seon.render/query {:seon.db/read-operation :pull
                                        :seon.db/pull-arguments {:selector face-selector
                                                                 :eid [:seon.fn/sym f]}}
                    :seon.render/output :seon.render/ai …})
  → one pull of the row (+ arity refs, binding rows, link metadata)       ; the building block's read
  → declared pair of :seon.fn/fn → seon.render.ns/function-ai (rewritten) ; selection stage (c)
  → child units, one per declared query: callers, reaching tests, tasks   ; building block §3d, addresses only
  → schema-definition child units for referenced keys not already known   ; §1.3
```

The renderer does no graph work (building block §0.3). Every read is either the unit's pull or a child unit's
declared query. The same unit tree feeds both faces (AGENTS.md "two consumers on one pipeline").

### 1.2 What the AI face shows, in order

It is valid Clojure: comments are prose, and forms are forms. An example over the installed row of
`seon.program/definition-digest`, whose two arities are real (`program.cljc:391-409`):

```clojure
;; seon.program/definition-digest — Digest one declaration's identity and effective meaning.
(defn definition-digest
  "Digest one declaration's identity and effective meaning.

   The digest includes authored definition, resolver context and effective
   semantic metadata. Storage provenance, position and derived graph
   observations are deliberately excluded."
  {:malli/schema
   [:function
    [:=> [:cat :map] :seon.program/definition-digest]
    [:=> [:cat :map [:maybe :map]] :seon.program/definition-digest]]}
  ([row] (definition-digest row nil))
  ([row resolver-context] …))                  ; exact stored source, canonical head (§2)
;; contract by arity (authored):
;;   (definition-digest row)                   row :map                                   → :seon.program/definition-digest
;;   (definition-digest row resolver-context)  row :map, resolver-context [:maybe :map]   → :seon.program/definition-digest
;; schemas not yet shown in this context:
(seon.schema/register! :seon.program/definition-digest
  [:string {:description "Canonical identity of one declaration's authored definition, resolver context and effective semantic metadata." :max 64 :min 64}])
;; handles (run to expand):
;;   callers 15        (my.program/callers {:seon.program/subject 'seon.program/definition-digest})
;;   reaching tests 30 (my.program/tests-reaching {:seon.program/subject 'seon.program/definition-digest})
;;   tasks             not indexed (requirement 5a.6)
```

The callers count (15) and schema form are the installed values (probe §7); the test count is illustrative. The call forms are the installed signatures
(`my/program.clj:144-177`), not the shorter `(callers 'f)` of requirement 1.9. The shorter spelling is that
row's owner's change.

| Part | Source of truth | Rule |
|---|---|---|
| name, one-line summary | `:seon.fn/sym`, the docstring's first line | the first-line rule is §3's one function; there is no second `first-doc-line` (`render/ns.clj:257-259` retires) |
| docstring, source | `:seon.fn/source` exact bytes, with the head shown **as it will be stored** (§2) | when the canonical head differs from the authored one, one line follows: `;; will be stored as above; authored: <the differing head node>` (REPL, before merge) or `;; stored as above` (after merge) |
| contract | `:seon.fn/spec`, compiled against the carried projection | one canonical notation (§1.3); an arity table aligns each **binding** (from the stored binding rows, never reparsed arglists) with its input slot and the return schema. A derived contract is labelled `(derived from destructuring)` (§4) |
| equivalences | the registry inverse index (§2.3) | shown **as advice**, per the option root rules: `;; [:vector :seon.test/sym] at arity 0 input 1 equals :seon.fn/tests (declared in a required namespace); write the name if you mean that identity` |
| referenced schemas | the stored `*-refs` of the arities, closed over `:seon.schema/references` | the definition is shown only when not already in the context (§1.3); otherwise the name stays and a comment gives the fetch form `(doc :k)` |
| handles | child declared queries | ids plus the call that expands them, never the expansion (requirement 1.9). Unknown or unindexed relations are typed unknown, never silence |

### 1.3 Canonical contract notation, and "already in the context"

**Notation.** The contract is always printed as Malli's own form of each arity. Per arity, take
`m/-function-schema-arities` of the compiled `:seon.fn/spec` and print `[:=> <m/form input> <m/form output>]`
(adding the guard when present). One arity prints bare; several print as `[:function …]`, ordered by
`:min`. This is the same compilation `contract-facts` performs (`program.cljc:1053-1081`). Because Malli
compiles `:->` to `:=>` with `:cat` (`malli.core:2376-2385`), an authored `:->` and an authored `:=>` show
identically. `:catn` labels stay, since they are data the arity rows keep (`program.cljc:949-953, 966-971`).
Property maps print in sorted key order.

**Referenced schemas.** They come from the stored `:seon.fn.arity/input-refs`, `output-refs` and `guard-refs`,
then the transitive `:seon.schema/references` edges. That is one Datalog read and no spec reparse. It replaces
`referenced-schema-closure` and its private `:any` registry (§0.4).

**Already in the context** is decided when the evaluation's shown text is saved. Shown text is rendered once, at
evaluation time, and stored exactly (AGENTS.md "Persist exact shown text"; prompt selection keeps whole units,
`src/seon/cluster/prompt.clj:301-333`). The known set is therefore a fact about earlier saved units, not about the
future selection:

- Each saved unit already has a call id and a subject lookup (`seon.render.history` units are open maps
  carrying "the call id, the subject lookup and the basis transaction", `seon.render.history.edn:2-7`).
  The face emits each shown definition as a child unit whose subject is `[:seon.schema/key k]`.
- The evaluation recorder stores the set of subjects whose content its shown text contains, as one attribute
  on the evaluation (`:seon.eval/shown-subjects`, a set of lookup refs; the registry has no such key today). This
  also covers an agent's own `(doc :k)`, whose unit subject is the schema.
- Known set `K(agent)`: the union of `:seon.eval/shown-subjects` over the agent's evaluations since its
  current opening (compaction regenerates the opening and so resets `K`). One indexed query, supplied to the
  face request by the `units` entrance as an explicit input.
- If prompt selection later drops the older unit that showed a definition, the name and its `(doc :k)` form
  remain. The selection's one elision already names the dropped units and the retrieving form. No re-render
  happens and no text is clipped a second time.

The HTML page has no prompt, so it shows every referenced definition, collapsed (§1.6).

### 1.4 `(doc f)` is the face

`doc` of a function symbol evaluates to the row value and prints through the one REPL path, so `(doc f)` and the
face are the same text. Today `doc` writes to `*out*` (§0.6). The change is in `seon.bootstrap/doc`, whose owner is
render-dispatch-opus. It is named here, not designed.

### 1.5 Handles that grep and the REPL both follow (requirement 1.9)

Every id the face shows is written literally, fully qualified, in a file that its namespace names:

| Id | Definition file | REPL call |
|---|---|---|
| function `a.b/f` | `src/a/b.clj` (Clojure munging) | `(my.program/callers {:seon.program/subject 'a.b/f})` |
| test `a.b-test/t` | `test/a/b_test.clj` | `(my.program/tests-reaching …)`, `(my.test/check {:seon.test/changed ['a.b/f]})` |
| schema `:a.b/k`, including errors | `resources/seon/schemas/a.b.edn` (today's layout; 211 files) | `(doc :a.b/k)`, `(my.program/reads-key {:seon.schema/key :a.b/k})` |
| task `:a.b.task/x` | `resources/seon/tasks/a.b.edn` (decoration v0 §2; the task store is not installed: v0 files retired 6780e926a) | `(task :a.b.task/x)`, the name from requirement 1.9, not installed |

The canonical head writes ids in link metadata (`:seon/tasks`, `:seon/must-read`, `:seon/see-also`) and inside
`:malli/schema` fully qualified. It never writes `::alias/k` there, because `rg :a.b/k` does not find `::b/k`
(one-repl §4.1 G1). Bodies are not rewritten (§2.1), so an auto-resolved keyword in a body stays a spelling that
only the index answers (G3).

### 1.6 The HTML face

It comes from the same unit tree:

```clojure
[:section {:class "seon-family-entry seon-function" :id (block/surface-id (keyword sym))}
 [:h3 [:a {:href namespace-route} [:code sym]]]
 [:p first-line]
 [:table {:class "seon-function-contract"} ; one row per arity: binding · schema link · return link
  …]
 [:aside {:class "seon-function-equivalences"} …]         ; the same advice text as the AI face
 [:details {:class "seon-function-schemas"} [:summary "referenced schemas"] …] ; always present, collapsed
 [:details {:open true :class "seon-function-source"} [:summary "source (stored form)"] [:pre [:code source]]]
 [:ul {:class "seon-function-handles"} …]]  ; each handle: route link + the REPL call text, like the AI face
```

Schema names link to their schema block anchors. There is no "already in context" on the page.

### 1.7 Cost

- One pull, plus one refs read, plus one `K` query. Each child handle is its own bounded declared query
  (`callers` and `tests-reaching` exist). `tests-reaching` costs 1.9 s and 6.5 GB for a saturated core function
  (requirements, §"dogfood", line 195). So the face shows the **count** from a bounded query and the expanding
  call. It never runs the full reach inside the render. Owner of that cost: `seon.fn/tests-reaching`.
- Target: face ≤ 20 ms warm for a 3-arity function. UNMEASURED; the probe of today's face was 1 ms, because it
  reads nothing.

## 2. Canonical authoring: persist better versions

### 2.1 What is canonicalized: the declaration head, nothing else

Code is parsed data. Rewriting only the head nodes through rewrite-clj keeps the body's bytes, comments and
formatting exactly. `seon.edit` already owns pure lossless top-level form transforms (`src/seon/edit.clj:1-8,
314`) and loads in the hook while Seon is down.

| Rule | Canonical form | Authority |
|---|---|---|
| H1 contract place | `:malli/schema` inside the defn attribute map, after the docstring; `^{:malli/schema …}` on the name moves there | Seon convention (every indexed contract; `fn.clj:726`) |
| H2 arrow | per arity `[:=> [:cat …] out]`; one arity is bare, not `[:function x]`; several are `[:function …]` ordered by `:min` | `malli.core:2376-2385` (`:->` proxies to `:=>`/`:cat`) |
| H3 qualification | predicate symbols qualified, `::alias/k` resolved to `:ns/k` in the head | existing `qualify-schema-symbols` (`fn.clj:199`) + `canonical-definition` (`schema.clj:642`) |
| H4 order | attribute-map keys: `:malli/schema` first, then the rest in sorted order; property maps inside the contract in sorted key order | printing rule only |
| H5 ids | link metadata values are literal qualified keywords and symbols, never strings (§1.5) | requirement 1.1, 1.9 |
| H6 docstring | **never rewritten**: prose is never parsed (requirement 1.4). The first-line rule (present, ≤ 78 chars, terminal punctuation) is reported as advice | `script/seon/dev/docstring.clj:1-45` rule, relocated (§3) |
| H7 names | per §2.3's ruled option; under option A, nothing is substituted | probe §0.2 |
| H8 derived contract | the head keeps the destructuring and does **not** gain a written-out `:malli/schema` input; the stored `:seon.fn/spec` is the effective contract (§4) | derived-contracts doc |

### 2.2 "Equal" and how it is checked

A canonical rewrite is admitted only if all three checks hold. Otherwise the original text is kept and the
refusal is shown as a finding naming the rule and the differing part.

1. **Body equal.** The bytes after the head are identical (a string comparison of the rewrite-clj node text).
   The analyzer's edges (`:seon.fn/calls`, `:references`, `:writes`, `:call-arities`) are equal, and
   `:seon.fn/keywords` differ only by keywords that occur in the head.
2. **Contract equal.** Compile both contracts against the same carried projection. Per arity, from
   `m/-function-schema-arities`, the `:min`, `:max` and `:arity` must be equal, and `m/form` of `:input`, `:output`
   and `:guard` must be equal. That is `mu/equals` (`malli/util.cljc:8-11`, which is form equality) applied per arity,
   after Malli's own `:->` expansion. `mu/equals` is marked `^:no-doc`, so the design uses `m/form` equality
   directly and cites `mu/equals` only as the same definition. Under option A no name is substituted, so this is
   pure notation equality.
3. **Digest recomputed.** The canonical text is reindexed by the ordinary indexer. `:seon.fn/source` is part of
   `definition-digest` (`program.cljc:375-409`; only position, provenance and derived edges are excluded), so the
   digest changes, as it must.

### 2.3 Name substitution: three priced options (a genuinely new decision; AGENTS.md step 4)

| Option | Guarantee | Cost | Sacrifice |
|---|---|---|---|
| **A. Advise, never substitute (recommended).** The face and the hook show unique equivalences whose named schema lies in the function's own or required namespaces (56 today). The agent adopts one by writing the name; its next landing then stores it | no identity is invented; every stored name was written by an author | ~+20 (an inverse index memoized on the projection value, plus a finding shape) | requirement 1.14's clause "become that name" is not automatic; root rewords 1.14 to "are shown as that name's equivalent" |
| B. Substitute at merge, root accepts | the same in-reach unique matches are substituted in `prepare-merge!` and listed in the proposal; root's `accept-merge!` is the identity decision | ~+40 | coincidental bindings (`:seon.edit/line-count` for any `[:int {:min 1}]`) reach root. Root must read every substitution, and a missed one silently couples the function to a foreign schema's future changes |
| C. 1.14 as written: every structurally equal piece becomes a name | none: §0.2 shows 297/369 ambiguous and wrong unique matches | ~+30 | refuted by the probe; this breaks "structural resemblance is not identity" |

### 2.4 Where it happens: inside the gated merge route, before the gate tests

The owner's constraint is "at write-back, not at evaluation". Write-back writes exactly what `accept-merge!`
accepted (one-repl §3.3), and test evidence is keyed by definition digest. Canonicalizing *after* the accept
would write a digest that no test exercised, so step 5 of write-back (published digest = tested digest) would
refuse by construction. So canonicalization happens at the start of the same gated route:

1. `prepare-merge!` (`src/seon/cluster/source.clj:747-795`) computes the delta, then calls `replacement`
   (`:739-745`), which re-derives the rows through `fn/published-index-rows`. Canonicalize each delta row's source
   first (the pure head transform, §2.1), and apply §2.2's checks. The reindex then produces the canonical rows
   and their new digests.
2. The candidate gate tests those canonical rows. The run's evidence is keyed to the canonical digests.
3. `accept-merge!` accepts them; `write-back!` splices the same canonical source into the file and proves the
   published digest map equal (one-repl §3.3 step 5).

Evaluation never rewrites: the REPL row keeps the agent's text. The face shows the preview "will be stored as"
from the same pure function (§1.2), and after the merge it shows "stored as". Files and rendered context end
up with identical bytes, because write-back writes the row's source and the face prints the row's source.

**Files written by shell agents** reach the program through publication, not merge. There the canonical head is
advice from the edit hook (§3). The file stays as written until its next REPL-path landing or an explicit
`my.edit/form!` of the advised text. No background rewrite of files happens.

## 3. One rule source for the face, the merge, the hook and the linters

- **Pure head rules** (H1–H5, and the H6 check): one pure function in `seon.edit`, `(declaration-head source
  namespace-context)` → `{canonical-text, findings}`. It is schema-free, so the bb hook loads it directly, like
  `seon.edit/exact` today (`bin/seon-hook` `reconstruct-exact-edit`). The docstring rule functions move from
  `script/seon/dev/docstring.clj` into `src` unchanged (a relocation, zero credit), so the face and merge do not
  depend on `script/`.
- **Registry-dependent advice** (H7 equivalences, and contract compilation): the edit hook already asks the
  live root JVM to compile a changed file's contracts against the packaged projection
  (`seon.contracts-compile-test/check`, via `run-contract-compile`, `bin/seon-hook:547-620`). That same call
  also returns the equivalence findings from the same function the face calls. When no live JVM answers, the
  result is `:unavailable`, never a pass (existing behaviour, `:604-607`).
- **clj-kondo stays the analyzer** of rows and edges (one-repl §2.2). Canonical rules are **not** re-encoded as
  kondo hooks: a kondo hook cannot see the registry, and a second encoding of H1–H5 would drift. The
  malli-exported kondo type configs (`.clj-kondo/metosin`) derive from stored contracts. They are a consumer,
  not a rule source.
- **One feedback text.** The hook prints the same finding values the face renders (the REPL agent sees
  `;; will be stored as …`, the shell agent sees `ADVISORY — canonical head: …` with the exact replacement
  text). Both come from one finding schema rendered by its declared pair, not from two formatters.

## 4. Derived contracts (requirement 1.12): what the face and the canonical form need

The derivation rules belong to [derived-contracts-2026-09-25.md](derived-contracts-2026-09-25.md) (lane
design-derived-contracts-opus). This design needs only four things from it:

1. **One stored effective contract.** Whether authored or derived, `:seon.fn/spec` holds the complete effective
   contract, so `contract-facts`, arity refs, `reads-key` and call preparation's supplied defaults
   (`call_preparation.clj:358-386` matches a map entry whose child form equals its key) keep consuming one value.
   The probe confirms that `malli.destructure/parse` with `{::md/required-keys true ::md/sequential-maps false
   ::md/inline-schemas false}` returns `[:cat [:map :seon.store/store :seon.store/branch]]` for a qualified
   `:keys` binding. That is exactly the self-named ref-entry shape the matcher accepts.
2. **Provenance per part.** The face labels which parts were derived and which were authored. This needs one
   declared fact on the row, named by that doc, not a re-derivation at render time.
3. **No write-out.** The canonical head never materializes a derived input as `:malli/schema` text (H8).
   Otherwise the file holds two declarations of one contract.
4. **Measured baseline** for that lane: 159 single-arity functions today destructure only qualified keys in every
   argument (of 2,049 contracted functions with arguments; 3,879 of 4,104 arguments are plain symbols; 81 ms query).
   **None** of their explicit input contracts is form-equal to the derived one; most name a request schema
   (`:seon.render/call-request`) or `:map`. **35** of 200 checked map arguments destructure a qualified key that
   the explicit (dereferenced) input map does not declare. For example, `seon.cluster.process/process-identity-alive?`
   destructures `:seon.boot/pid` and `:seon.boot/start-instant` under a `[:cat :map]` contract. These are its
   conflict-rule census, not a ruling.

## 5. Inline functions (requirement 1.11): the two blockers root forwarded

render-dispatch-opus implements 1.11. Root asked for the smallest correct semantics.

**Blocker 2 (the first shown render precedes the row).** Chosen: **render against the post-settlement value
before committing, then commit settlement and shown text in one transaction.** Settlement builds its tx-data
(the evaluation plus its evaluation-owned function rows). The shown text renders against `(d/db-with db-before
tx-data)`, which Datahike specifies as referentially transparent and immutable
(`reference-code/datahike/src/datahike/api/specification.cljc:483-493`, pin `4157916b`). The one transaction
then carries the tx-data plus `:seon.eval/shown`. Why this and not two ordered transactions:

- a listener never sees a settled evaluation without its shown text (wakes derive from listened datoms);
- the render still writes nothing; it reads a value (pass the world explicitly);
- recovery keeps today's invariant.

Requirements:

- `plan-for` (`call_preparation.clj:1523-1527`) must plan from the request's supplied database value, not from
  the connection's committed one. That is call preparation's owner, held by render-dispatch-opus.
- `db-with` gets a `seon.db` wrapper, since direct `datahike.api` calls belong only in `seon.db`.
- The shown text must not print speculative entity ids. Generated names must be deterministic from
  (evaluation id, ordinal).
- The cost is one extra index application of a small transaction. UNMEASURED.

**Blocker 3 (`:seon.fn/sym` makes any row program).** Chosen: **a distinct identity attribute** for evaluation-owned
function rows, on an entity schema declared `:seon.program/partition :seon.data` (the partition key already
declared on entity schemas). The rows are components of their evaluation, so compaction and retraction cascade.
They reuse the non-identity attributes (`:seon.fn/spec`, `:seon.fn/arities`, `:seon.fn/source`), so contract
compilation and preparation stay one path. `plan-for` gains one `or` join over the two identity attributes.
Why not reuse `:seon.fn/sym` behind a partition flag: a lookup `[:seon.fn/sym x]` must never resolve to data, and
merge candidates derive from identity. **Risk to audit:** queries that select by a non-identity `:seon.fn/*`
attribute without joining `:seon.fn/sym` would start seeing data rows. `error.clj:937` joins `:seon.fn/sym`;
the contract-report query near `fn.clj:1864-1872` ("every function row participates") must be checked.

## 6. Priced slices

The added lines are justified by deletions in the same cut. **None of these groups is in the audit ledger**
(`duplicate-paths-audit-2026-09-25.md` §11). The deletions below are measured here at `19e9fe5f4`, and root may
choose not to book them. The face adds no consumer to ledger group **C4** (`fn/schema_shape.clj`, 385 lines,
awaiting A1-13): it reads `:seon.fn/spec` through Malli and the stored `*-refs`, never shape rows, so C4's
retirement is not blocked. All added counts are UNVERIFIED estimates.

| # | Slice | Owner files | Added | Deleted (measured, not in ledger) | Net | Proof root runs |
|---|---|---|---:|---:|---:|---|
| F1 | face as the `:seon.fn/fn` pair over the pulled row; AI + HTML; handles as child declared queries | `src/seon/render/ns.clj`, `seon.fn.edn` (pair unchanged) | +80 | `function-ai` + `function-html` 744-787: **44** | +36 | the §1.2 example renders from default's row with contract, source and handle forms; HTML has the same parts |
| F2 | stored refs replace the reparsing closure in namespace views | `src/seon/render/ns.clj` | +15 | `referenced-schema-cap` … `referenced-schema-summary` 121-253: **133**; `first-doc-line` 257-259: **3** | −121 | namespace distance-1 view lists the same keys as before for three namespaces; no `catch Throwable` remains |
| F3 | pure `declaration-head` (H1–H6) + §2.2 equality checks | `src/seon/edit.clj`, relocated docstring rules | +55 | 0 | +55 | `:->`, reader-meta and `[:function x]` heads canonicalize with identical body bytes and equal arity forms; a body change refuses |
| F4 | canonical rows at `prepare-merge!` before the gate | `src/seon/cluster/source.clj` (after one-repl S5 lands) | +12 | 0 | +12 | a merged replacement's file bytes, row source and tested digest are equal |
| F5 | known-in-context: `:seon.eval/shown-subjects` + the `K` query | evaluation schema, recorder, `units` entrance | +20 | 0 | +20 | a second face in one context omits a definition the first showed and gives `(doc :k)` |
| F6 | hook + JVM advice from the same functions (option A equivalences) | `bin/seon-hook`, `test/seon/contracts_compile_test.clj` | +30 | 0 | +30 | an edit with an in-reach equivalence and a `:->` head prints both advisories; JVM down → `:unavailable` |
| **total** | | | **+212** | **−180** | **+32** | |

The order is F2 (a pure deletion win) → F1 → F3 → F6 → F5 → F4 (F4 waits on one-repl S5). The slices are
file-disjoint from render-dispatch-opus except `src/seon/render/ns.clj` and the schema EDN. Check the
ownership ledger before launch.

**Why the growth is justified.** F3–F6 add ~+117 lines with no deletion. They are the owner's requirements 1.9
and 1.14, which no existing function performs. The smaller alternative, advice only with no persisted canonical
form, drops F4 (−12) and leaves files and rows diverging from what the face previews. That breaks 1.14's
"identical in files and in rendered context". F4 costs little because merge already re-derives rows.

## 7. Unproven, and verification limits

- **Not installed and not run.** Default is faulted and awaits a restart; HEAD's render selection is not the
  installed one (building block §0.1). Nothing here is installed evidence of rendering.
- `seon.render/units` does not exist yet. The face depends on its query source and child units (1273211e8, under
  review).
- `K` ("already in the context") needs the new evaluation attribute and the recorder to write it. Whether saved
  unit subjects are recoverable at record time depends on the building block's unit ids. UNPROVEN.
- §2.2's equality: rewrite-clj head edits preserving body bytes is asserted from `seon.edit`'s lossless design
  and was not probed for reader-conditional or `#_`-commented heads.
- Option A's in-reach rule uses the keyword namespace as owner, because `:seon.schema/ns` is empty (§0.5).
  Multi-segment schema files (`seon.fn.arity.edn` for `:seon.fn.arity/*`) match that rule; schemas registered
  under a foreign namespace would not.
- `(doc f)` printing through `*out*` is inferred from source, not observed in SCI.
- Face cost (≤ 20 ms), `db-with` cost, and the reach-count query bound are UNMEASURED.
- Out-of-scope findings recorded here, with no issue file created (the issue index is root's): §0.3 (arglists and
  spec stored as strings: indexer owner) and §0.4 (`catch Throwable` in `render/ns.clj`, retired by F2).

**Probe record** (all read-only, JVM, default):

- today's face: 1 ms;
- inverse form index: 3,394 forms → 2,132 distinct, 3 ms;
- subform scan over 2,093 specs: 369 matches / 297 ambiguous, 14 ms;
- in-reach filter: 169 / 56 unique;
- binding census: 4,104 arguments, 81 ms;
- `malli.destructure/parse` comparison over 159 functions: 0 form-equal, 35 of 200 map arguments destructure
  undeclared keys, 48 ms.
