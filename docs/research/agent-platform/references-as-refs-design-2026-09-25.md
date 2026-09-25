---
type: design
status: "Opus first pass (lane design-references-as-refs-opus); awaiting Astra review; no source edited"
created: 2026-09-25
scope: Which program relations become Datahike refs with a named target schema, produced at the one analysis producer (var-row and its siblings), validated at the writer and followed by the render walk; which stay values and why; the render index
source-pin: HEAD e7b9e8791 (C4 step A; 0bd3a38b4 reverted by e00a3ccda); REPL on default at pid 59566 before root replaced the JVM at 21:53Z
aligned-with: parser-proves-the-spec-2026-09-25.md; language-requirements-2026-09-24.md §§1.9-1.15, 5a, 7, 11, 12; task-context-install-design-2026-09-25.md (revision 4, §4.1 interim value edges); datahike-deletion-and-the-program-graph-2026-09-16.md
---

# References as refs: one producer names every link

**What exists.** clj-kondo analysis already carries every link (calls, references, keywords, `:seon/tasks`,
`:seon/must-read`, `:seon/see-also`, seeds, contract keys). `seon.fn/var-row` (`src/seon/fn.clj:683`),
`namespace-row` (`:226`), `task-links` (`:616`) and `seon.schema/canonical-schema-rows` (`src/seon/schema.clj:3700`)
turn it into rows. The writer stores them, and the walk and the deletion check read them back.
Today "this attribute names that identity" is written down in six places, by hand or by guessing (§1c).

**The principal Clojure approach.** Every attribute that names another entity declares its target identity once, as
data in its schema: a single `:seon.db/reference-to` property. The storage type is chosen per relation. It is a ref
when the fact is a statement about a program row that must exist when the fact is written. It stays a value when the
fact observes a token that may name nothing: an external Var, a function not yet written, or evidence that outlives
its target. The deletion obligations, the walk's edge families, the issue citation index and the render-target check
all derive from that one declaration.

## 0. Broken things first

- **The default JVM was replaced mid-lane.** Root replaced it at 21:53:18Z (new pid 75756). Its boot had not
  published `:seon.boot/cluster-connection` after more than 10 minutes. Probes P1–P9 ran on the old pid 59566. The
  remaining counts (P10) could not run and are marked unmeasured. The replacement is not this lane's doing: every
  probe was read-only or ran under `datahike.api/with`.
- **The same fact is recorded in six ways.** Four hand lists or guesses say which attribute names which identity:
  - the deletion obligations in `removed-definition-error` (`src/seon/db.clj:4077-4094`);
  - `write-render-target-error`, which re-reads every schema form with `edn/read-string` (`:4021-4059`);
  - the declared-edge rules (`src/seon/fn.clj:1690-1705`, `src/seon/test.clj:384-392`);
  - the walk's interim table (task-context §4.1).

  Two schema properties say it as well: `:seon.fn/reference-to` (`resources/seon/schemas/seon.fn.edn:17`) and
  `:seon.issue/cites` (`seon.issue.edn:8-25`). They disagree already: `:seon.test/subject` has no obligation, and
  `:seon.fn/must-read` and `see-also` are in no obligation list, so a must-read target can be deleted with no refusal.
- **56 first-party callee names have no program row.** On default, 56 distinct `seon.*`/`my.*` callee symbols in
  `:seon.fn/calls` resolve to no `:seon.fn/sym` row. Examples:
  - `seon.render/same-call-cache-evidence?` is a `def` alias (`src/seon/render.clj:897`) and has no function row;
  - `seon.dev.docstring/format-findings` lives under `script/`, outside the indexed roots;
  - `seon.sci.eval/absent-intern` is a `def` of an `Object`.

  The Var population is not the row population. Any design that makes callees refs has to face this first (§3a).
- **A tempid that names nothing is admitted silently** (P7). `{:db/id "a" … :probe/calls #{"nobody"}}` stored
  `[123838 :probe/calls 123839]`, and 123839 has 0 datoms. A producer that emits refs through tempids must define every
  tempid it names. The writer must refuse a ref target with no identity datom. Datahike does not check this
  (`reference-code/datahike/src/datahike/db/transaction.cljc:34-53`).

## 1. Inventory: attributes that name another entity

### 1a. Program attributes, measured on default (P1–P3, pid 59566)

A value "resolves" when an identity row carries it. The Why column gives the stated reason, with its source.

| Attribute | Schema (HEAD) | Stored | Datoms / distinct / resolve | Why a value today | Verdict |
|---|---|---|---|---|---|
| `:seon.fn/calls` | `seon.fn.edn:49` `[:set {:seon.db/index true} :seon.program/edge-symbol]` | symbol, many, indexed | 65,935 / 5,032 / 4,299 (22,202 datoms resolve, 34%) | 0e8f7d323 retyped it from `[:set :seon.db/ref]` (deletion study D1–D6). `retractEntity` on a callee silently swept every caller edge. A dangling ref was invisible. Redefinition minted a new eid that the old refs did not follow | **value** (§3a) |
| `:seon.fn/references` | `seon.fn.edn:70` | symbol, many, indexed | 33,419 / 2,673 / 1,529 | same commit: "Names survive target retraction; selection follows them conservatively" | **value** |
| `:seon.fn/unresolved-references` | `seon.fn.edn:71` | symbol, many | 2,414 / 564 / 262 | unresolved by definition | **value** |
| `:seon.fn/keywords` | `seon.fn.edn:124` | keyword, many | 42,894 / 5,930 / 2,303 | literal mentions. 3,212 first-party keywords (`:seon.db/result`, test-local keys) are not schemas | **value, not a reference** |
| `:seon.fn/writes` | `seon.fn.edn:72` | keyword, many | 1,107 / unmeasured (P10) | 0e8f7d323: "Values survive schema-row retraction" | **value** (analysis over-reports helpers) |
| `:seon.fn/must-read`, `:seon.fn/see-also` | `seon.fn.edn:68-69` | symbol, many, indexed | 0 on default (task-links has not been republished) | written by `task-links` (`fn.clj:616-633`) as resolved symbols | **ref** (§2, §3b) |
| `:seon.fn.arity/input-refs`, `output-refs`, `guard-refs` | `seon.fn.arity.edn:2-10` | keyword, many, indexed | 3,038 / 572 / **572**; 2,270 / 586 / **586**; guard unmeasured | 0e8f7d323 retyped them from `[:set :seon.db/ref]` in the same sweep, with no separate reason recorded | **ref** |
| `:seon.schema/references` | `seon.schema.edn:82` | keyword, many | 5,841 / 2,479 / **2,479** | the same sweep. `canonical-schema-rows` already refuses a missing target ("Missing targets still refuse", `schema.clj:3702`) | **ref** |
| `:seon.ns/requires` | `seon.ns.edn:35` `[:set {:seon.db/index true} :symbol]` | symbol, many | 4,334 / 217 / 111 | 106 required namespaces are external (`datahike.pull-api`, `sci.impl.opts`) | **value** |
| `:seon.fn/defined-by` | `seon.fn.edn:35` | symbol | 4,928 / 8 / 0 | the defining macro (`clojure.core/defn`), a grammar token | **value** |
| `:seon.test/subject` | `seon.test.edn:10` | symbol, indexed | 0 datoms | test-first: a test may name a function not written yet (7.6 raised → specified) | **value** |
| `:seon.test/reach` | `seon.test.edn:2` | symbol, many | 0 datoms | execution evidence of the tested closure (5a.7). It must outlive a deleted function | **value** |
| `:seon.task/tasks` | `seon.task.edn:11` | string handle, many, indexed | 8 / 3 / 3 | ruled: "stored as keyword VALUES, not refs, so tasks never ride program merge" (7.2; parser R2) | **value** |
| `:seon.task/must-read` | `seon.task.edn:9` | symbol, many | 7 / 7 / 6 | the dangling one, `seon.sci.eval/definition-rows`, is a function the task asks for. 7.4 allows EDN to name a subject that does not exist yet | **value** |
| `:seon.fn/ns`, `:seon.test/ns`, `:seon.fn/file` | `seon.fn.edn`, `seon.test.edn` | **ref** | 4,928 / 2,205 / 7,589 | already refs, written as lookup refs `[:seon.ns/name n]` | installed |
| `:seon.fn.arity/input-schema`, `return-schema` | C4 step A made them optional (e7b9e8791) | **ref** → shape | 2,321 each | the shape mirror, which C4 retires | retiring (not this lane) |
| `:seon.render/ai`, `:seon.render/html` on schema rows | `seon.render.edn:19` `[:or :qualified-symbol :string]` | **EDN-string codec** | 334 / 50 renderers / 50 have rows | the `:or` falls to the string codec (`src/seon/schema/datahike.clj:113-121`). The same attribute also holds rendered text | **ref**, under a new name (§4) |
| `:seon.schedule.task/function` | `seon.schedule.task.edn:3` | **ref**, `:seon.fn/reference-to :seon.fn/sym` | — | the installed precedent: a ref with its target named | installed; rename the property |
| `:seon.issue/functions`, `keys`, `namespaces`, `tests`, `errors`, `runs`, `issues` | `seon.issue.edn:8-25` | **ref**, `:seon.issue/cites [...]` | — | the installed precedent. Unresolved tokens are kept as values in `:seon.issue/unresolved` (`issue.clj:419`) | installed; rename the property |
| `:seon.error/operation`, `:seon.instrument/fn`, `:seon.eval/renderer` | `seon.error.edn:377` etc. | symbol, indexed | 133 / 133 / 11 | the guide (§3): "A fault must retain the identity it observed even if the function is later deleted" | **value**. Conflicts with 12.2, see §7 Q3 |

Error-member attributes that only report an offending name (`:seon.call-preparation/unresolved-symbol`,
`:seon.flow/unsupported-method`, and about 60 more in `seon.effect`, `seon.env`, `seon.config`, `seon.issue` and
`seon.db`) observe a token inside a refusal. They stay values. They are not relations the walk or the deletion check
follows.

### 1b. The history, quoted

- **0e8f7d323** (2026-09-16), "Retype program edges and enforce final-state deletion obligations." It moved `calls`,
  `references`, `writes`, arity `input`/`output`/`guard-refs` and `reach` from refs to values, deleted the `seon.fn.ast`
  family, and deleted `:seon.fn/pending-calls`. That attribute's description was "Analyzer-observed call targets whose
  program identity is not yet admitted. The declaration writer resolves them when that identity arrives." It was the
  forward-reference machinery the ref model needed.
- The reason is the deletion study's defect table (`datahike-deletion-and-the-program-graph-2026-09-16.md` §1):
  - D1: `retractEntity` silently retracts every caller's ref;
  - D2 and D3: the tombstones that avoided D1 looked like live functions and needed a second validator;
  - D4: a ref to an eid with no datoms is admitted and then vanishes from joins;
  - D5: redefinition mints a new eid and callers keep the dead one;
  - D6: "`:seon.fn/calls` … declare **a relation between two entities** where the program actually has **a name
    mentioned in source text**."

  Option B was recommended because "it deletes three mechanisms (tombstones, identity minting, the second validator)
  rather than adding one."
- **The dissolved invariant.** Ruling 47, as quoted in the study §4: "every name the SCI context can resolve has a
  program row, minted where the context learns it, so call edges cannot dangle by construction." Making callees refs
  again brings it back.
- **The contract keys were swept along without a reason of their own.** They resolve 100% (P3). Malli already
  refuses an unregistered key when compiling the contract (parser T11). The deletion obligation already names them
  (`db.clj:4091-4093`). None of D1–D6 applies to a relation whose target cannot be missing at write time.

### 1c. The six places that record "attribute → identity"

| Place | What it holds | Fate |
|---|---|---|
| `removed-definition-error` obligations, `src/seon/db.clj:4077-4094` | hand list: calls, references, subject, capability → fn; requires → ns; writes, schema refs, arity refs → schema | derived from `:seon.db/reference-to` (S0) |
| `write-render-target-error`, `db.clj:4021-4059` | `edn/read-string` of every schema form, then renderer symbols without rows | deleted: the render pair becomes a ref (S3) |
| `declared-reference-rules`, `fn.clj:1690-1705`; `test.clj:384-392` | Datalog over `:seon.fn/reference-to` rows | read the one property (S0) |
| `:seon.issue/cites`, `issue.clj:189-217` | issue attribute → cited identity attributes | the same property (S0) |
| `:seon.fn/reference-to`, `seon.fn.edn:17` | one declaration, on `:seon.schedule.task/function` | renamed `:seon.db/reference-to` (S0) |
| the walk's interim value-edge table, task-context §4.1 | eight `[holder identity]` pairs | derived. The three contract/schema rows become forward refs (S2) |

## 2. The model

### 2a. One declaration per relation

```clojure
;; resources/seon/schemas/seon.db.edn: one property, whose value is the set of target identity attributes
:seon.db/reference-to [:set {:min 1} :qualified-keyword]

;; a statement: a ref, target named, required-ness decides refuse or sweep
:seon.fn.arity/inputs [:set {:seon.db/reference-to #{:seon.schema/key}} :seon.db/ref]

;; a token: the target's own identity schema as the member, so the value validates as that identity's shape
:seon.fn/calls [:set {:seon.db/index true :seon.db/reference-to #{:seon.fn/sym}} :seon.program/edge-symbol]
```

- The **projection derives** a `{attribute {:targets #{identity…} :storage (:ref | :value)}}` map once per projection
  identity. The source is `m/properties` on the registry entries (the same read `issue.clj:204-209` does), plus the
  bridge's value type. It is O(schema): 1,176 attributes are declared and about 40 carry the property. That one value
  feeds:
  - the deletion obligations, replacing the hand list in `db.clj:4077-4094`;
  - the walk's edge families, replacing the §4.1 interim constant;
  - issue citation;
  - the declared-edge rules for test reach (`:seon.fn/invokes` stays the invocation marker it already is, so
    `reference-to` no longer implies reach).
- **A ref validates twice.**
  - The producer resolves each target through its identity. A value with no row refuses by holder, attribute and
    value, as `task-links` already refuses malformed annotations (`fn.clj:627-628`).
  - The final writer checks that every asserted ref datom's target has one of the declared identity datoms. This is
    one AEVT probe per distinct target and closes the tempid trap (§0).
- **A value validates as its identity's shape.** Whether it resolves is a query: one `not-join`.
- **Rendering follows a ref.** An EAVT seek reaches the target entity, and its identity attribute's
  `:seon.program/row-schema` names the schema whose pair renders it. A value edge is followed by one AVET seek on the
  declared identity. With the target named, the render key is exact, never guessed.

### 2b. Per relation: storage, cardinality, lifecycle

| Relation | Storage | Card. | Component | Required | Lifecycle when the target goes | Named key |
|---|---|---|---|---|---|---|
| arity → input / output / guard schema | **ref** | many | no | optional member of the arity row | refuse while a contract names the key (derived obligation). The sweep never lands | new names `:seon.fn.arity/inputs`, `outputs`, `guards` (a value-type change cannot be adopted in place; see e00a3ccda) |
| schema → schema it references | **ref** | many | no | optional | refuse (derived obligation) | `:seon.schema/uses` |
| schema → its AI / HTML renderer | **ref** | one each | no | optional | refuse while a schema declares the pair | `:seon.schema/render-ai`, `:seon.schema/render-html` |
| fn / ns → must-read, see-also, docstring links | **ref** | many | no | optional | refuse (derived obligation) | `:seon.fn/must-read`, `:seon.fn/see-also` (retyped before any datom exists: 0 on default) |
| fn / test → callees, references | value | many | — | — | none; a dangling name is a `not-join` | `:seon.fn/calls`, `:seon.fn/references` + `reference-to` |
| ns → required namespace | value | many | — | — | none | `:seon.ns/requires` + `reference-to #{:seon.ns/name}` |
| test → subject; test → reach | value | one / many | — | — | none | + `reference-to #{:seon.fn/sym}` |
| code → task; task → must-read | value | many | — | — | none. Tasks are data (7.13) | + `reference-to #{:seon.task/id}`, `#{:seon.fn/sym}` |
| agent → task | **ref** | one | no | optional | sweep. Both are data, and a settled task is closed, not deleted | `:seon.agent/task` (task-context §4.1 proposed a handle value) |
| fault → operation | value | one | — | — | none | see §7 Q3 |

Refs are cardinality-many sets with no order. Contract order that matters lives on the arity row, as it does today
(`:seon.fn.arity/order`).

## 3. The blocking reasons, answered per reason

### 3a. External dependency Vars have no rows

- **Measured (P2–P4).** 43,733 of 65,935 call datoms (66%) name a Var outside the program: 677 distinct names, led by
  `clojure.core` 304, `datahike.api` 36, `malli.core` 31 and `sci.core` 28. 106 of 217 required namespaces are
  external. No `:seon.ns/name` row exists for `clojure.core`, `datahike.api`, `malli.core` or `sci.core`.
- **Can clj-kondo supply rows?** `seon.fn.analyzer/analyze-dependencies!` lints the JVM's classpath minus the
  checkout's roots into the clj-kondo cache (AGENTS.md; about 6 s warm). That cache holds a var definition per
  dependency Var, so a producer *could* emit one row per external Var that some first-party function names:
  - about 700 rows, or tens of thousands for every dependency Var;
  - in a new non-program partition, since dependency Vars are neither merged nor written back.
- **The principled answer is no.**
  - A call to `clojure.core/map` observes a token of a pinned dependency. The dependency owns that identity; Seon
    does not.
  - Rows for it bring back ruling 47's population invariant ("every name … has a program row, minted where the
    context learns it"), which 0e8f7d323 dissolved. AGENTS.md forbids it: "never invent placeholder
    functions/tests".
  - The 56 first-party dangling names (§0) show the invariant would refuse today's program on day one.
- **Splitting calls into two attributes also fails.** A ref for first-party callees beside a value for external ones
  puts one relation in two encodings, the smell the data-modeling skill names ("a sibling attribute storing the same
  name as a value beside the ref").
- **Verdict.** Calls, references and requires stay values, with the target declared. The render walk gains nothing
  measurable from refs here (§5).

### 3b. Forward references to functions not yet defined

- **Within one publication, tempids resolve cycles and forward names** (P6). Mutual calls `a ↔ b` plus an upsert of
  existing `seon.db/q` stored `#{[a b] [a q] [b a]}`, and `q` kept its eid. So one transaction handles every intra-file
  and cross-file forward reference, as long as the producer emits tempids keyed by the target identity value and
  defines each one.
- **Lookup refs refuse forward names** (P6). `[:seon.fn/sym tmp.p/z]` earlier in the same tx than `z`'s own entity
  failed with "Nothing found for entity id". A missing external name refuses the same way. The producer uses tempids
  for program targets. A lookup ref is the loud existence check for a target that must already be installed
  (schemas, the renderer's function row).
- **Across evaluations.** SCI and Clojure refuse an unresolved symbol at analysis. `declare` creates a Var, and
  whether the SCI entrance mints a row for a declared Var is **unverified** (probe S1). A function body therefore
  cannot name a first-party function that exists nowhere. Tokens that genuinely name the future are:
  - task must-reads;
  - test subjects in test-first work;
  - evidence.

  Those are values. The ref relations of §2b are authored links (must-read, see-also) and contract or schema keys.
  An author who links to a function that does not exist has made a mistake, so the producer refuses by name. This
  follows "never invent placeholder functions", rather than a pending-calls queue, which is not revived.

### 3c. Deletion: sweep, refuse and the unbreakable graph

- **Optional refs sweep. Required refs refuse only for a whole-row requirement.** A cardinality-many set that loses
  one member still validates, so required-ness cannot protect a set member.
- The owner's rule, "Retraction shouldn't be allowed until a fix is also proffered", therefore needs the obligation
  check whichever storage is used. For refs it reads the incoming datoms in the database before the write, because the
  sweep has already removed them from the database after (`db/transaction.cljc:999-1016`). Deletion is refused while a
  surviving referrer still names the target, unless the same transaction repairs it: the referrer is re-analyzed, so
  its new definition digest no longer names the target.
- Once the obligation map is derived (S0), every declared relation is covered, including must-read, see-also, subject
  and render pairs, which the hand list misses today.
- **Refs change the failure mode, not the guarantee.** If a check ever misses, a value edge survives as a visible
  dangling name. A ref edge is swept and the evidence is gone. That is why values remain the right storage for
  high-volume, cross-owner or historical relations. Refs are used where Malli or the producer already makes a missing
  target impossible.

### 3d. Merge across branches: are eids stable?

- **No, after divergence** (P6). Two `d/with` from the same base value allocated the same eid 123838 to different new
  entities. Two branches forked from one commit therefore allocate colliding eids for different rows. Eids from the
  common ancestor stay equal: `branch!` shares index roots (`versioning.cljc:224-284`).
- Merge (`seon.cluster.source/prepare-merge!`/`accept-merge!`, `src/seon/cluster/source.clj:780-880`) transfers rows by
  identity through `program/digest-map` and `replacement`. For ref attributes, the replacement must emit the target
  as a lookup ref by its identity: the pattern `seon.issue/adopt-tx` already uses for citations
  (`issue.clj:1143-1175`). It must never replay the tested branch's eid.
- Definition digests stay branch-independent, because the producer computes `program/definition-digest` over the
  analysis value (symbols and keywords) before storage (`fn.clj:822-827`, `schema.clj:3726`).
- Cost: one identity lookup per ref datom in the delta.

## 4. The render index

There are three sources, matching 11.1's order. The value's own key needs no index.

1. **Declared pairs** are schema → renderer. Today they live as Malli properties in the projection (read per value by
   `declared-producers`, `src/seon/render.clj:470-516`) and as the codec string on 334 schema rows.
   - The index: `{schema-key {:ai sym :html sym}}`, memoized on projection identity, which is the value
     `render-declarations-in` (`schema.clj:1852-1868`) already computes for admission.
   - Stored as the refs `:seon.schema/render-ai`/`render-html`, "which schemas does `f` render" becomes one AVET seek
     on the ref. That replaces `my.program/render-referrers` (`src/my/program.clj:228`; 17.7 ms in parser T15).
   - `write-render-target-error` becomes the ordinary missing-target refusal.
   - **P8:** `[?s :seon.render/ai ?r] [?s :seon.schema/key ?k]` returned 334 rows over 50 renderers in **0.54 ms cold**.
     All 50 renderers have fn rows.
2. **The viewing namespace's render functions.** `namespace-stage` (`render.clj:622-646`) scans every public function
   of the namespace through `sci.kernel/public-functions-in`. It runs `function-accepts-and-returns-in?` on each, per
   value rendered.
   - The index: arities whose outputs name a face schema, joined to their namespace, over the existing arity attributes:

     ```clojure
     [:find ?f ?in :in $ ?ns [?face ...]
      :where [?n :seon.ns/name ?ns] [?f :seon.fn/ns ?n] [?f :seon.fn/arities ?ar]
             [?ar :seon.fn.arity/output-refs ?face] [?ar :seon.fn.arity/input-refs ?in]]
     ```

   - **P8:** over all namespaces, 108 arities in 84 functions return `:seon.render/ai` (9), `:seon.render/hiccup` (90)
     or `:seon.render/rendered` (9), in **1.31 ms cold**. 43 of them accept the generic `:seon.render/unit`, so the
     accepted schema does not narrow the candidates. The contract check stays, over the few face-returning functions
     instead of every public.
3. **The floor** needs no index.

**The walk's join.** The structure the walk memoizes on projection identity (task-context §4.2) gains two maps:
- identity attribute → row schema → pair, from source 1;
- namespace → face functions, from source 2 on the committed value.

A frontier node's pair is then a map lookup by its identity attribute. The walk's "render presence" query family
(task-context §2.2 family 5) becomes that lookup.

## 5. Cost: refs against value joins, measured

Probe P5 built `:probe/calls` (a ref attribute) inside `datahike.api/with` on default's value. It asserted the 22,202
call datoms whose callee has a row, which took 166 ms and wrote nothing. Timings are from `datahike.api/q` and
`datoms`. "Cold" is the first query on a fresh `with` value; the others average five warm runs.

| Walk | Value join `[?e :seon.fn/calls ?v] [?t :seon.fn/sym ?v]` | Ref `[?e :probe/calls ?t]` |
|---|---:|---:|
| forward, frontier 1 (`seon.sci.eval/evaluate`, 58 edges) | 0.21 ms | 0.04 ms |
| forward, frontier 60 random fns (96 edges) | 0.93 ms | 0.33 ms |
| forward, frontier 500 (902–968 edges), warm / cold | 2.27 / 2.99 ms | 0.85 / 1.18 ms |
| reverse, 50 targets (150 callers) | 0.98 ms | 0.55 ms |
| full callee closure of `evaluate` (919 nodes) | 20.0 ms | 3.9 ms |
| `sym → eid` map for all 4,928 fns (removes the value join's extra seek, per operation) | 0.38 ms | — |

Caveats:
- The probe's ref datoms sat in the unflushed `with` tail, which favours the ref column.
- The committed store was not measured. That is S2's clock row.

**What the walk gains.**
- **Forward joins run 2.5–5× faster, on sub-millisecond queries.** The walk's measured cost (14–100 ms per render,
  task-context §2.2) is dominated by plan compilation (53 of 71 ms), not by edge seeks.
- **Reverse edges are not free through a VAET index.** Datahike has no VAET
  (`reference-code/datahike/src/datahike/db.cljc:310`). A reverse ref lookup is an AVET seek, because refs are indexed
  automatically (`db/utils.cljc:307-313`). The symbol edges already declare `:seon.db/index true`, so reverse lookup
  is the same AVET shape, and the measured gain is 1.8×. Task-context §3's "VAET reverse" should read "AVET on the ref".
- **Native pull.** Only refs let `pull` follow a link: `{:seon.fn/arities [{:seon.fn.arity/inputs [:seon.schema/key]}]}`.
  The walk's contract family (task-context family 3, a three-clause join through the arity component) becomes part
  of the forward-ref family. Five query families per level become four.
- **The main gain is correctness, not speed.** A named target means an exact render key, a derived obligation and a
  missing target refused at write time.

## 6. Migration in loadable slices

Each slice edits its owner in place, converts callers and ends with its REPL probe repeated on the adopted program.
Schema changes are proven by incremental adoption on a branch of the live store. e00a3ccda shows why: a retirement
the stored row schemas still require cannot adopt, so every retype is a **new attribute name**, adopted in two steps
(add and write, then retire the old name), as C4 now does.

| Slice | Change | Deletions (lines) | Added (est.) | Proof |
|---|---|---|---|---|
| **S0: declare targets** | `:seon.db/reference-to` in `seon.db.edn`. Declare it on each attribute of §2b. Derive the relation map in `seon.program` (memoized on projection). Convert `removed-definition-error` obligations, `declared-reference-rules`, `test.clj:384-392` and `issue/citation-attributes` to read it. Delete `:seon.fn/reference-to` and `:seon.issue/cites` | hand obligation list −18; `:seon.fn/reference-to` decl and reader −6; `cites` decl reads −8; walk interim constant −10 | +25 | the derived obligations equal today's list plus subject, must-read, see-also and capability; deleting a must-read target refuses (new regression, one class) |
| **S1: one producer emits links** | `var-row`, `namespace-row`, `task-links`, `canonical-schema-rows` emit every link through one `links` step: tasks, must-read and see-also (already in `task-links`), docstring `[[x]]` links resolved in the holder's namespace by `seed-symbol` (`fn.clj:487`), and seeds (installed). Stage schemas as in parser §2b | the file row's misfiled `:seon.fn/unresolved-references` for ns-level links (parser T4); `write-render-target-error`'s EDN re-read moves to S3 | +20 (docstring links), schemas +10 | rows of the parser prototype's sources, validated at each stage; the 50 docstring `[[…]]` occurrences in `src/` (0 qualified) resolve or refuse by name |
| **S2: contract and schema keys become refs** | new `:seon.fn.arity/inputs`, `outputs`, `guards`, `:seon.schema/uses` (`[:set {:seon.db/reference-to #{:seon.schema/key}} :seon.db/ref]`). `arity-row` (`program.cljc:1007-1081`) and `canonical-schema-rows` emit lookup refs (schema rows precede: already dependency-first, `schema.clj:3715-3724`). Merge `replacement` normalizes by identity. Readers: `my.program/reads-key`, the walk's family 3, `contract-facts`. Retire the four keyword attributes in the second adoption | the walk's family 3 (≈12); interim rows −3; the four keyword attributes | +15 | P5's forward and reverse timings on the adopted store; `reads-key` unchanged output |
| **S3: render pairs become refs** | `:seon.schema/render-ai`/`render-html` refs from `canonical-schema-rows`; the render index of §4 | `write-render-target-error` (`db.clj:4021-4059`, −39); `my.program/render-referrers` form scan | +12 | the 334-row index at P8's cost; deleting a renderer with a declaring schema refuses |
| **S4 (option): must-read/see-also refs** | retype `:seon.fn/must-read`/`see-also` (0 datoms, so no adoption conflict) to refs targeting fn, schema and file identities, resolved by value type (symbol → fn or test, keyword → schema, string → file path), as `seon.issue` already resolves citations | interim rows −2 | +8 | a must-read to a missing function refuses at `var-row`; one regression |

Totals, estimated before review: about +90 added against about −100 deleted, plus the retired attribute
declarations. Every slice sits under 100 added lines, and each names what it deletes.

**Risks.**
- S2 and S3 change hot writer paths. Each needs a clock row on parent against change for full reindex and for a
  one-file publication, and must fix regressions over 20% or 50 ms.
- The derived obligation map widens refusals. Deletions that pass today (a must-read target, a test subject) will
  refuse, which is intended. Expect reds in deletion tests that relied on the gap.
- Merge normalization is new work on `replacement`. If it is missed, it silently cross-links eids after divergence.
  That class needs one regression: two branches each adding a schema key, merged.
- Docstring `[[x]]` extraction is a text scan. AGENTS.md requires owner permission for a production regex. The
  existing prose tokenizer is `seon.issue/words` (`issue.clj:44-61`), which drops brackets. See §7 Q2.
- Only 50 wikilinks exist, all unqualified, and at least one (`[[sym]]`) is likely a code example that would refuse.

**Priced options** (simplest first; each gives its guarantee, cost and sacrifice).

1. **Values everywhere, targets declared (S0 + S1).** About 2 hours.
   - Guarantee: one declared relation map drives obligations, the walk, citations and the render index; links are
     ingested at the one producer; every deletion obligation is derived.
   - Sacrifice: no native pull through contract keys; dangling authored links are found by query rather than refused
     at write time.
2. **Recommended: refs where the target is program-partition and already required to exist (S0–S3, S4 optional).**
   About 6 hours across four adoptions.
   - Guarantee: contract keys, schema uses, render pairs and authored links are refs, validated at the producer and the
     writer. The walk follows them natively and dead render targets refuse.
   - Sacrifice: merge normalization work; two-step adoption per retype.
   - Tokens (calls, references, requires, tasks, subjects, reach, evidence) stay values.
3. **Refs for every relation, including calls and requires, with dependency rows from clj-kondo's dependency cache.**
   Several days.
   - Guarantee: a fully ref-typed graph.
   - Sacrifice: about 700+ external rows in a new partition, and ruling 47's population invariant reinstated. It
     refuses 56 current first-party names and brings back a pending-calls queue for forward names. Merge must
     normalize 66k edges. Deleted-callee evidence is lost on any missed check.
   - Not recommended.

## 7. Requirement rows closed

| Row | Requirement | Becomes | Closed by |
|---|---|---|---|
| 1.9 | every reference followable both ways | `:seon.db/reference-to` names the target for every relation. Forward is EAVT (ref) or one AVET seek (value); reverse is one AVET seek on the ref or the indexed value | S0 (declaration), S2–S4 (refs) |
| 1.10 | REPL authoring; one deletion authority | `ns-unmap!`/`remove-ns!` retract through the writer, whose derived obligations refuse a surviving referrer on every declared relation | S0 |
| 1.12 | contracts may be derived | derived input keys stored as the same `:seon.fn.arity/inputs` refs, so derived and explicit contracts share one relation | S2 (storage); the derivation stays with derived-contracts |
| 1.14 | canonical authoring | links are read from the canonical head (attribute map, docstring) by the one producer; the digest covers them | S1 |
| 1.15 | every stage specified; nothing silently dropped | stage schemas at `analyze`, the link step and the writer; a ref to a missing target refuses; a tempid without an identity refuses | S1 + writer check (S0) |
| 5a.2–5a.3 | hop 1 content, hop 2 addresses | the walk follows declared relations; the named target selects the pair | S0 + §4 |
| 5a.6 | must-read/see-also not ingested | installed in `task-links`; S4 makes them refs | S1/S4 |
| 7.2, 7.3 | `:seon/tasks` one key, holder decides role, values | unchanged: values with `reference-to #{:seon.task/id}` | S0 (declaration only) |
| 7.5 | dangling id refuses at the gate | `not-join` over the declared relation (installed as `seon.issue/candidate-dangling`) | installed; S0 derives its attribute set |
| 7.9, 7.9b | ingestion of links and seeds | the one `links` step in `var-row` and siblings | S1 |
| 7.13 | tasks are data, never program | task relations stay values; `:seon.agent/task` is a data→data ref | S0 |
| 11.1, 11.2 | per-face selection; functions known by face role | the render index of §4 (declared pairs; face-returning namespace functions by `outputs`) | S3 + §4 |
| 12.2 | error members referring to another entity use refs | **conflict**, see Q3 | open |

### Questions for root

1. **Q1: the property name.** `:seon.db/reference-to` (recommended; the database owner's namespace, one key for refs
   and values), versus keeping `:seon.fn/reference-to` widened (task-context §10 Q1 option A), which Astra ruled
   carries eid and reach meaning.
2. **Q2: docstring links.** (a) Extend `seon.issue/words` with a bracket mode and move it to a shared owner, about
   8 lines (recommended; one prose tokenizer). (b) A production regex, which needs owner permission. (c) Links only in
   `:seon/see-also` metadata, with docstrings as prose.
3. **Q3: error members.** Language row 12.2 says an error member naming another entity is a ref. The data-modeling
   guide §3 says a fault retains the observed identity after the function is deleted. An optional ref from
   `:seon.error/operation` to a fn row would sweep on deletion, and the fault would lose its subject. Recommended: keep
   the value with `reference-to #{:seon.fn/sym}`, and amend 12.2 to read "names the entity through a declared
   reference".

## 8. Probe log (default, JVM mode, read-only; `tmp.refs-probe` namespace)

| # | Form (abridged) | Result |
|---|---|---|
| P1 | installed `:schema` grouped by value type | 260 ref, 94 symbol, 174 keyword attributes |
| P2 | per attribute: datoms, distinct values, values carried by an identity row | §1a table |
| P3 | first-party split of unresolved calls | 56 first-party and 677 external callee names; `:seon.fn/keywords` 3,212 first-party unresolved |
| P4 | `:seon.ns/name` rows for four dependency namespaces | 0 |
| P5 | `(d/with db (keep … :probe/calls …))` over resolved call datoms | 22,202 datoms, 166 ms; §5 timings |
| P6 | tempid cycle, forward lookup ref, missing lookup ref, dangling tempid | `#{[a b] [a q] [b a]}`, q eid unchanged; "Nothing found for entity id" twice; dangling tempid admitted with 0 target datoms (123838 → 123839) |
| P7 | holders of `:seon.render/ai` | 334 schema rows, codec strings such as `"seon.background/render-ai"` |
| P8 | render index queries, cold on a fresh `with` value | pairs 0.54 ms / 334; face functions 1.31 ms / 108 arities, 84 fns |
| P9 | `[[…]]` in `src/seon`, `src/my` (rg) | 50 occurrences, 0 qualified |
| P10 | `:seon.fn/writes`/`guard-refs` resolution; schema and fn eids across history | **not run**: the replacement JVM had not acquired its connection |
