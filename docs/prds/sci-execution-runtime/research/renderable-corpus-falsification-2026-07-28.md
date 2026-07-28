---
type: research
status: active
tags: [research, agent, architecture]
---

# Renderable corpus falsification review

## Verdict

**Not seal-ready.** The plan becomes seal-ready only after it is re-centered on
the later `:seon.render/distance` rulings; replaces the single-name
`input-schema`/`output-schema` projection with a contract representation that
can preserve every Malli function arm while explicitly defining renderer
eligibility; either reconciles or deliberately migrates the existing render
output contracts instead of assuming the unused `*-output` suffix; makes
schema choice, owner choice, collision, fallback, privacy, and projection
provenance total; designs a care-graph walker that shares `expand`'s budget
discipline without pretending the HTML slot walker already traverses
namespaces; and adds queryable failure-to-owner facts plus test-result
ingestion and namespace assignment before claiming delegation is a query. The
indexer and falsifier corrections below should then be folded into the sealed
contracts.

## Result summary

| severity | count |
|---|---:|
| SEAL-BLOCKING | 6 |
| REVISION | 3 |
| NOTE | 1 |
| **total** | **10** |

## Scope and dependency ledger

The reviewed design is
`renderable-corpus-plan-2026-07-28.md` at its introducing commit
`97d9919c6`. The review also applies the two later owner rulings in
`plan/README.md` (`7ca3fbc99`, `f7115aaf2`), because a contract sealed now must
obey the current ruling authority rather than the older research snapshot.
Unrelated shared-checkout edits were not read as N5 evidence and were not
modified.

| dependency | revision | source read |
|---|---|---|
| Seon | `f7115aaf29bd` at the decisive source probes | `src/`, `test/`, `bin/test`, current plan rulings |
| Malli | `80138076960e` | function-schema behavior exercised through real var metadata |
| Datahike | `9a7a9ef10a95` | queryability assessed from installed fact shapes and Datalog joins |
| core.async | `dc35f3e0d7bc` | only the already-landed flow/fault fact boundary was relevant |
| quarry program indexer | repository source | `src-old/seon/db/program.clj` |
| quarry edge indexer | repository source | `src-old/seon/program/edge.cljc` |

The cheapest decisive JVM probe was:

```clojure
(let [extract
      (fn [schema]
        (when (and (vector? schema) (= :=> (first schema)))
          (let [[_ input output] schema
                tag (first input)
                slot (second input)
                candidate (if (= :catn tag) (second slot) slot)]
            (when (and (#{:cat :catn} tag)
                       (= 2 (count input))
                       (keyword? candidate)
                       (keyword? output)
                       (seon.schema/registered? candidate)
                       (seon.schema/registered? output))
              [candidate output]))))]
  (into {}
        (map (fn [[label schema]]
               [label (extract schema)]))
        {:real-inline-output
         (:malli/schema (meta #'seon.context/identity-ai))
         :real-or-output
         (:malli/schema (meta #'seon.render/render))
         :real-multi-arity
         (:malli/schema (meta #'seon.cluster.agent/mailbox-step))
         :real-variadic
         (:malli/schema (meta #'seon.schema/register-all!))
         :constructed-or-input
         [:=> [:cat [:or :seon.render/unit :seon.error/value]]
          :seon.render/hiccup]}))
```

Run with `clojure -M:dev`, it returned `nil` for all five. The complete
observations are recorded under finding SB-2.

## Citation audit by plan section

| plan section | citation result |
|---|---|
| §0 dependency ledger | The router, block census, `select`, generic HTML panel, sealed N5 seams, and context auto-run citations say substantially what the plan claims. The edge citation truncates an eight-member uncertainty enum to three; the program schema has a require-edge collection but not its target attributes; the error-routing citation is a target ruling, not landed facts; and the architecture says render outputs are `:seon.render/ai` / `:seon.render/html`, not `*-output`. |
| §1 corpus indexer | The existing program-row attributes at `schema.cljc:512-536` are real. The plan says “four” additions but lists five. Quarry identity, completeness, ordering, and stale-row logic exist, but `unchanged-row?` does not compare tests. `:seon.ns/require-edges` alone cannot expose an edge target in the fresh tree. |
| §2 renderer discovery | Registration properties really exist at `schema.cljc:541-575`, and `entity-unit` really pulls an entity at `block.clj:433-458`. The two-clause query is valid Datalog only for already-projected names; the claimed suffix schemas do not exist, current render functions do not use them, and `entity-unit` currently enriches nothing from schema registrations. |
| §3 scoped selection | The installed/derived block-name refusal at `block.clj:234-261` is real but is a different key. AI capture records a qualified projection symbol. A successful HTML surface does not record one, unqualified entity-schema keys have no owner ref, multiple matching entity schemas are unresolved, and a failed selected lens does not fall through to the generic rung. |
| §4 care-graph | `expand` has the cited deterministic node/depth accounting. It is an HTML hiccup slot/ref walker with a closed expansion request, not a namespace/kind walker. Neither `unit-request` nor `unit` carries the plan's `depth`; the current ruling now requires `distance` with different semantics. |
| §5 `seon.data` | The generic panel and `/data` drill boundaries cited are real. The “24 used / 0 registered” `:seon.ai.attempt/*` scar is false in the fresh tree: all seven distinct used attributes are registered in `src/seon/schema/ai.edn`. |
| §6 N5 order | The named seams exist, but the N5.3 test is already non-vacuous through `with-redefs`; it does not exercise corpus discovery. The missing-handler oracle expects three symbols although the filed issue and source name six. |
| §7 delegation | `my.message` and durable error facts are landed. Queryable namespace/var/schema/test/call-path provenance and `:seon.agent/namespace` are not landed in the fresh schema. The two required failure walks fail before ownership is derivable. |
| §8 owner decisions | Decisions 1, 2, 5, 7, 9, 10, and 12 depend on contracts falsified below. The later distance ruling supersedes decision 5's vocabulary and default. |
| §9 riskiest point | The stated risk is real, but one advertised mitigation is not: selected-renderer failure returns an error value/card and never invokes rung 4. HTML success also lacks the claimed exact projection provenance. |

## SEAL-BLOCKING findings

### SB-1 — the current owner ruling supersedes the plan's hop contract

**Claim attacked.** Plan §4.2 and decisions §8.5 specify
`:seon.render/depth`, nil at hop 0 meaning “full detail,” and a projection
reading one integer (`renderable-corpus-plan:399-430,689-691`).

**Falsifier.** The later ruling authority says:

- the key is `:seon.render/distance`, optional with default 1
  (`plan/README.md:535-540`);
- context is `render(namespace, distance N)` (`:541-545,555-560`);
- distance is an argument to the renderer, deeper composition is the
  renderer's act, expansion decrements it, and distance 0 is **name only**
  (`:560-565`);
- a slot can carry an explicit projection override and that choice must be
  captured (`:564-566`).

Those are not spelling-only changes. Nil/full-detail versus default-1/name-only
changes observable bytes and the recursion contract. Explicit slot projection
also changes the provenance and selection inputs. The ruling itself directs
the N5 plan to re-center after falsification (`:567-570`).

**Required revision.** Replace the depth section and every downstream
falsifier with the current distance contract. Name the exact request, unit,
slot, capture, decrement, distance-0, and default-1 schemas before sealing.

### SB-2 — the two stored schema names cannot represent the fresh function-contract population

**Claim attacked.** §1.1 stores one cardinality-one input name and output name
only for `[:=> [:cat S] T]` or one-slot `:catn`, and says this is merely the
existing naming rule rather than a new tax (`renderable-corpus-plan:66-96`).
§2 then treats the two attributes as complete renderer discovery
(`:185-199`).

**Actual contracts.** A `clojure -M:dev` probe lifted real var metadata:

```clojure
:real-inline-output
[:=> [:cat :seon.render/unit] [:maybe :string]]
=> nil

:real-or-output
[:=> [:cat :seon.render/request]
 [:or :seon.render/rendered :seon.error/value]]
=> nil

:real-multi-arity
[:function
 [:=> [:cat] [:map]]
 [:=> [:cat :map] :map]
 [:=> [:cat :map :keyword] :map]
 [:=> [:cat :map :keyword :any]
  [:tuple :map [:maybe [:map-of :keyword [:vector :some]]]]]]
=> nil

:real-variadic
[:=> [:catn [:seon.schema/kvs [:* :any]]] [:set :keyword]]
=> nil

:constructed-or-input
[:=> [:cat [:or :seon.render/unit :seon.error/value]]
 :seon.render/hiccup]
=> nil
```

The real sources are `context.clj:55-65`, `render.clj:117-136`,
`cluster/agent.clj:81-105`, and `schema.cljc:1822-1836`. Malli and Seon's own
completeness walker explicitly support both `:=>` and every arm of
`:function` (`schema.cljc:777-802`), so completeness does not imply the
plan's narrow shape. A variadic `:catn` slot is named, but its schema is
`[:* :any]`, not a keyword `S`; a multi-arity contract may name several
different `(S,T)` pairs, which two cardinality-one attributes cannot preserve.
An anonymous `[:or ...]` input is a complete contract but intentionally has no
single `S`.

**Why seal-blocking.** A contract based on this row shape silently discards
valid contract arms and conflates “complete function” with “discoverable
single-schema renderer.” It cannot later accrete multi-arity support without
changing database cardinality and query semantics.

**Required revision.** Store contract arms as data (component rows or a
whole-value tuple/vector once its representation is settled), preserving
arity, input form/name, and output form/name. Define renderer eligibility as a
separate pure projection over those arms. If renderers must have exactly one
named unary request and one named output, enforce that at renderer admission
and call it an N5 renderer contract; do not present it as the general
`:seon.fn` projection.

### SB-3 — the kind-to-output suffix discovers none of the real render surface

**Claim attacked.** §2.1 derives `:seon.render/ai-output` and
`:seon.render/html-output` and says a new kind becomes discoverable by
registering its suffix (`renderable-corpus-plan:201-219`).

**Tree evidence.**

- Neither suffix key exists anywhere under `src/`.
- AI projections such as `seon.context/identity-ai` declare
  `[:maybe :string]` (`context.clj:55-65`).
- HTML projections such as `seon.render.data/drill-html` declare
  `:seon.render/hiccup` (`render/data.clj:170-178`).
- Error AI/log projections declare `[:string {:min 1}]`
  (`error.clj:436-494,556-569`).
- The three quarried handler families declare `[:maybe :string]` and
  `[:maybe :seon.render.canvas/hiccup]`, not the suffix names
  (`src-old/seon/render/handlers/{fn,ns,schema}.cljc`).
- The cited architecture says auto-run selects functions whose output schema
  is `:seon.render/ai` or `:seon.render/html`
  (`architecture/context.md:388-395`). It does not state the suffix rule.

Even a renderer with an otherwise ideal named input is invisible when its
output is an inline `[:maybe ...]` or the current `:seon.render/hiccup` key.
The plan does not name a migration of every current and quarried renderer, so
the two-clause query would initially return no existing renderers.

**Required revision.** Pick and document one canonical output contract,
reconcile the architecture, and include the explicit source migration in N5.
The least surprising data-oriented option is a renderer-contract marker or
named contract-arm fact derived at admission; if the suffix is retained, every
existing renderer and handler must reference it and the generic kind grammar
must validate the same key.

### SB-4 — `(S,K,V)` is not a total or auditable selection contract

**Claim attacked.** §3 calls selection a total four-rung lookup with one
same-namespace refusal (`renderable-corpus-plan:303-325`), and §9 says exact
projection capture plus an unbreakable generic floor makes the risky viewer
lens legible (`:729-750`).

**Ambiguity 1: there may be no owner `O`.** Canonical schema rows add
`:seon.schema/ns` only when `(namespace schema-key)` is non-nil
(`schema.cljc:1861-1872`). The three program entity schemas used as §2's
primary examples are unqualified keywords: `:seon.fn`, `:seon.ns`, and
`:seon.schema` (`schema.cljc:541-575`). A JVM probe of
`canonical-schema-rows` returned:

```clojure
#:seon.schema{:key :seon.fn}
#:seon.schema{:key :seon.ns}
#:seon.schema{:key :seon.schema}
```

None had `:seon.schema/ns`. Rung 2 and namespace-grouped completeness cannot
derive an owner for the very first three entity schemas.

**Ambiguity 2: a value may answer to several `S` values.** Entities are open
bags of attributes. An entity carrying the required entries of two declared
entity map schemas matches both; if both registrations carry a declaration
for `K`, §5's “merge their render declarations” has two different values for
one key. The plan defines collision only for two functions after one `S` is
chosen. It defines neither schema selection, multi-schema composition, nor a
refusal/order for this earlier conflict.

**Surprising precedence.** The discovery query does not filter
`:seon.fn/private?`. A private helper with the same `(S,K,V)` participates in
selection or collision. A discovered owner function also outranks an explicit
registration property without the two being considered a collision. Neither
case is justified by the “scope is the defn namespace” rule.

**The floor is not a fallback.** `seon.render/render` catches a selected
projection throw and returns `::projection-failed`
(`render.clj:153-169`); `block/surface` preserves that as an error
(`render/block.clj:358-387`). It never retries rung 4. Further,
`seon.error/ai-prose` accepts `:seon.error/notice`, not an arbitrary render
unit (`error.clj:465-495`), so it is not the generic AI floor described in
§3.1. Selection can always return a symbol, but rendering does not therefore
fall through to legible generic data.

**Provenance is asymmetric.** AI contributions record a qualified declaration
symbol (`cluster/prompt.cljc:116-127`). Successful HTML surfaces contain name,
id, kind, and output, but no projection (`render/block.clj:354-387`).
Projection is present only in some failure data. Thus §3.3's statement that
the HTML audit is already answerable is false, and the later ruling's
per-slot override cannot yet be captured.

**Required revision.** Define value-to-schema composition before `(S,K,V)`;
make missing `O`, multiple matching schemas, private functions, and
registration-property/discovered-function overlap explicit cases; create a
real generic AI projection or state plainly that failure becomes an error
surface rather than falling back; and record the exact selected symbol for
both successful consumers and explicit slot overrides.

### SB-5 — `expand` supplies a discipline, not the care-graph implementation

**Claim attacked.** §4 says the care-graph “is not a new walk,” is `expand`
with require edges, and needs only one line to thread hop depth
(`renderable-corpus-plan:376-438`).

**Actual landed boundary.**

- `expand` accepts **hiccup** plus
  `{:seon.render/surfaces ... :seon.sci.admit/caps ... :seon.db/db ...}`
  and returns hiccup (`render/block.clj:477-526`;
  `schema/block.edn:180-188`).
- It recognizes only vectors with `:data-slot` or `:data-ref`
  (`render/block.clj:567-626`) and hardcodes ref-following to
  `:seon.render/html` (`:610-621`).
- The AI prompt never calls `expand`; it separately reduces AI-declaring
  membership (`cluster/prompt.cljc:133-180`).
- `unit-request` is closed and has no depth/distance key
  (`schema/block.edn:170-178`); `block/unit` selects a fixed key set that also
  omits it (`render/block.clj:313-321`).
- The fresh schema registers `:seon.ns/require-edges` only. It does not
  register the quarry's target-bearing child attributes
  `:seon.ns.require/target`, `/alias`, `/refers`, `/refer-all?`, or
  `/as-alias?`; those exist only in `src-old/seon/ns/source.cljc:19-31`.
  A component ref set without a target attribute cannot be walked to the next
  namespace.

The reusable asset is the deterministic DFS, per-path visited set, and shared
node/depth cap **discipline** (`render/block.clj:500-660`). Reusing the
function would either couple AI composition to hiccup or turn `expand` into a
new generic graph engine with a different input/output contract—substantial
surgery, not one line.

**Required revision.** Specify one pure namespace/distance traversal that
returns ordinary render requests/slots for both kinds, with registered edge
facts, deterministic edge order, budget state, cycle/elision values, distance
decrement, and explicit projection overrides. It may extract/reuse a generic
budgeted-walk helper from `expand`; the existing HTML function remains one
consumer of that discipline.

### SB-6 — §7 ownership is not computable from the current failure facts

**Claim attacked.** §7 says three landed mechanisms already make delegation a
query and that only `:seon.test` rows are missing
(`renderable-corpus-plan:572-619`).

**Current fact mismatch.**

- The durable error entity has queryable kind, message, process/proc/op/cid,
  basis, run, agent, and instrumentation fields, but no namespace, var,
  schema key, test symbol, or call path (`schema/error.edn:43-106`).
- The normalizer admits the whole source and stores it as
  `:seon.error/data-edn`, a string (`error.clj:270-347`). A schema key or stack
  frame printed inside that string is not joinable by Datalog.
- Instrumentation violations may lift `:seon.instrument/fn`, but general flow
  faults do not. `:seon.fn/calls` cannot reconstruct which runtime call path
  was active from a fact that names no root var.
- The fresh schema has `:seon.cluster.agent/id`; it has no
  `:seon.agent/namespace` or equivalent namespace-assignment ref. The
  architecture describes that target at `data-model.md:102-111`, but it is not
  a landed join.
- Current `error/commit-tx` routes only to an already attributed agent and/or
  the configured escalation recipient (`error.clj:719-765`). It does not
  derive namespace owners.

**Broken-test walk.**

1. `bin/test` derives namespaces from filenames, calls
   `clojure.test/run-tests`, and exits from `(:fail result)+(:error result)`
   (`bin/test:15-44`).
2. It transacts no result, failure, assertion, file/line, or
   `:seon.test/sym`. Therefore there is no failure fact to join to a future
   test catalog row.
3. Adding `:seon.test` source rows is necessary but insufficient. N5 also
   needs a test-result ingestion boundary and a durable failure shape
   referencing the exact test row (plus the global attempt/batch whose vision
   delegation carries).
4. The quarry does not make this “small” automatically:
   `unchanged-row?` compares functions, namespaces, and schemas only
   (`src-old/seon/db/program.clj:215-226`). Test rows are always emitted as
   changed, so adopting them unchanged violates N5.1's zero-datom reindex
   falsifier.
5. Even after those facts exist, the final test namespace → assigned agent
   join is absent.

**Schema-conflict walk.**

1. A real duplicate schema-file conflict throws ex-data containing
   `:seon.schema.edn/attribute` and the two files
   (`schema/edn.clj:148-167`; sealed test at
   `test/seon/schema/edn_test.clj:39-49`). It does not carry
   `:seon.schema/key` as §7's table claims.
2. That build/load refusal is not automatically committed through
   `seon.error`.
3. If it were normalized unchanged, the attribute would be inside
   `:seon.error/data-edn`, not a queryable `:seon.schema/key` attribute.
4. If the key were lifted, qualified canonical schema rows can derive
   `:seon.schema/ns` (`schema.cljc:1861-1872`), but the namespace → assigned
   agent fact is still absent.

**Additional missing contract.** A `my.message` can reference a durable error
through `:seon.cluster.message/about`, but §7's “vision plus refs” does not
define an ordinary multi-failure/batch reference shape or idempotency fence.
Ending the global episode after “every failure has an owner and a message”
also requires durable evidence that each message committed; deriving only
ownership does not establish delivery.

**Required revision.** Design failure provenance as queryable refs/attributes,
not parsable text: exact function/test/schema rows, active call root/path
evidence where promised, attempt/batch identity, and namespace assignment.
Add test-result ingestion. Define one pure owner query and one transaction that
commits idempotent delegation messages (or durable message refs) before the
global episode ends. Until those rows exist, §7 is target architecture, not
“already landed.”

## REVISION findings

### R-1 — the indexer ledger drops source facts and overclaims quarry reuse

**Evidence.**

- §1.1 and N5.1 say “four” new `:seon.fn` attributes but list five:
  calls, uncertainties, workload, input-schema, output-schema
  (`renderable-corpus-plan:66-80,488-489`).
- The cited quarry uncertainty enum has eight members:
  `:constructed-keyword`, `:dynamic-call`, `:dynamic-read-attributes`,
  `:dynamic-written-attributes`, `:macro-expansion`,
  `:open-higher-order`, `:unresolved-symbol`, and
  `:value-passed-pattern` (`src-old/seon/program/edge.cljc:15-25`).
  The plan and classification research say the source names exactly three.
  Dropping the other five without a disposition can turn a quarry uncertainty
  into an apparently certain call graph.
- The plan calls `:seon.ns` rows “already sufficient,” but the fresh tree lacks
  the target-bearing require-edge attributes documented under SB-5.
- The quarry's `unchanged-row?` omits tests, as shown under SB-6.

**Required revision.** Enumerate every new attribute and every quarry field
with an adopt/delete/translate disposition. Add the require-edge schema and
test-row diff logic to the N5.1 contract. The workload classifier must treat
every retained unresolved analysis state as fail-closed or explicitly prove
why a state no longer exists in the new analyzer.

### R-2 — the N5.3 “non-vacuity” falsifier is already synthetic and cannot prove discovery

**Claim attacked.** N5.3 says `membership-collision-property` “stops being
vacuous” when `derived` becomes non-empty (`renderable-corpus-plan:503-509`).

**Actual test.** The sealed property generates distinct installed and derived
names, constructs derived rows, and replaces `block/derived` with them via
`with-redefs` (`test/seon/context_test.clj:536-582`). It is already
non-vacuous and proves membership collision/write-freedom. It proves nothing
about the corpus query, contract-arm projection, namespace scope, or selected
renderer.

**Required revision.** Keep the existing property for the membership seam.
Add a separate N5 discovery property backed by actual `:seon.fn`,
`:seon.schema`, and `:seon.ns` facts, covering named unary, excluded/accepted
multi-arity and variadic cases, private functions, same-scope collisions,
missing schema owner, and both kinds.

### R-3 — two advertised known-bad census oracles have stale expected answers

**Evidence.**

- N5.1 expects “exactly the three `seon.render.handlers.*` symbols”
  (`renderable-corpus-plan:493-496`). The registrations name **six**
  projections—AI and HTML for function, schema, and namespace
  (`schema.cljc:541-575`)—and the filed issue explicitly says six
  (`issues/program-graph-render-declarations-name-absent-functions.md:19-25`).
- §5 calls `:seon.ai.attempt/*` “24 used / 0 registered”
  (`renderable-corpus-plan:456-460`). `rg` finds seven distinct used
  attributes, and all seven are registered at
  `schema/ai.edn:112-138`: id, run, ordinal, at, error, failover-from, and
  delay-ms. Twenty-four is the occurrence count, not the attribute count.

**Required revision.** Make expected census answers derive from independent
sets of symbols/attributes rather than copied counts. The honest current
known-bad handler answer is six; the attempt-registration answer is no gap.

## NOTE

### N-1 — the core render/block citations mostly withstand falsification

The following cited statements are accurate and should survive the rewrite:

- `render/declaration?`, `kinds`, and total late resolution are at
  `render.clj:81-174`;
- pre-N5 `derived` is honestly `[]`, and installed/derived block-name
  collisions refuse at `render/block.clj:211-261`;
- `expand` really is deterministic DFS with per-path cycle detection plus
  shared node/depth caps at `render/block.clj:477-660`;
- `select` really is producer-side first-matching-specialist selection at
  `render/block.clj:744-791`;
- registration render properties really are preserved in entity-map schema
  forms at `schema.cljc:541-575`;
- the sealed context contract really names derived membership and the SCI
  invocation half as N5 dependencies
  (`context-blocks-contracts-2026-07-28.md:335-363,512-522,741-754`).

These sources establish useful seams and disciplines. They do not establish
the additional discovery, care-graph, scoped-selection, or delegation claims
the plan layers onto them.
