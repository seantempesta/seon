---
type: research
status: current
created: 2026-09-17
owner: read-only audit lane (my.* / render / operator / ai / schedule / search)
tags: [research, steward, contracts, private-functions, program-graph, error-model, class/absence-as-health]
---

# Private function audit — `my.*`, render, operator, ai, schedule, search

Grounding read end to end before any probe: [AGENTS.md](../../../../AGENTS.md)
§0–§7 and
[program facts are the runtime PRD](../plan/program-facts-are-the-runtime-prd-2026-09-17.md)
§1j (every function carries a contract, private included; errors stay values;
private read-consumers without a contract are the first mined issue class).

This lane records facts. It edited no source, ran no tests, and performed no
lifecycle operation. The concurrent `private-contracts` lane has landed
**nothing** in this domain: `git log --since=6.hours -- <every domain path>`
is empty at `247bb115b`, and the live cluster still reports 16 contracted
private functions repository-wide, the same number §1j measured.

## Scope

`src/my/*.clj`, `src/seon/render.clj`, `src/seon/render/*.clj`,
`src/seon/repl.clj`, `src/seon/print.cljc`, `src/seon/operator.clj`,
`resources/seon/operator/state.clj`, `script/seon/fresh_operator.clj`,
`src/seon/fs.clj`, `src/seon/ai.clj`, `src/seon/ai/tokens.cljc`,
`src/seon/schedule.clj`, `src/seon/maintenance.clj`, `src/seon/search.clj`,
`script/seon/dev/*.clj`.

Two assignment paths do not exist as written and were resolved to the real
files: there is no `src/seon/dev/` (the development scripts are
`script/seon/dev/*.clj`, namespaces `seon.dev.*`), and `src/seon/ai/` holds
only `tokens.cljc`, not a `.clj`.

## Method, and one honest deviation

The assignment allowed one read-only MCP evaluation. It took five. The first
census returned a column that contradicted the source (zero database-reading
private functions, in namespaces whose source plainly calls `seon.db/q`), and
publishing a census I believed to be wrong was the worse outcome. Evaluations
two through five were read-only, bounded, and diagnostic; they found a
platform defect in `seon.db/q` (finding 1) that is the reason the column was
empty. Every evaluation ran in `jvm` mode with explicit custody,
`read_only: true`, against `default` (pid 94566).

Because that defect makes the call-edge column of the census untrustworthy,
the per-function classification below is derived from **source**, by the
committed script recorded at the end of this note, and cross-checked against
the graph where the graph can be trusted.

## 1. The census

The exact form evaluated (MCP `eval_clj`, `jvm`, `read_only: true`,
namespace `user`, cluster `default`):

```clojure
(let [conn (seon.operator/connection "default")
      db (seon.db/db conn)
      nses '[my.agent my.background my.edit my.fs my.issue my.message my.note
             my.plan my.program my.shell my.test my.turn my.web
             seon.render seon.render.agent seon.render.block seon.render.data
             seon.render.hiccup seon.render.lint seon.render.ns
             seon.render.route seon.render.test seon.render.transcript
             seon.render.value seon.render.walk seon.render.web
             seon.repl seon.print seon.operator seon.fs seon.ai
             seon.ai.tokens seon.schedule seon.maintenance seon.search
             seon.operator.state seon.dev.changed-test seon.dev.clj-kondo
             seon.dev.docstring seon.dev.issues seon.dev.markdown
             seon.dev.mcp seon.dev.state seon.dev.test-roots]
      reads '#{seon.db/pull seon.db/q seon.db/entity seon.db/pull-many
               seon.db/transact! seon.db/datoms}
      all (seon.db/q db '[:find ?nsname ?sym ?priv ?spec
                          :in $ [?nsname ...]
                          :where
                          [?n :seon.ns/name ?nsname]
                          [?f :seon.fn/ns ?n]
                          [?f :seon.fn/sym ?sym]
                          [(get-else $ ?f :seon.fn/private? false) ?priv]
                          [(get-else $ ?f :seon.fn/spec "NONE") ?spec]]
                nses)
      targets (set (for [[_ sym priv spec] all
                         :when (and (true? priv) (= "NONE" spec))] sym))
      out (seon.db/q db '[:find ?sym ?csym
                          :in $ [?sym ...]
                          :where [?f :seon.fn/sym ?sym]
                                 [?f :seon.fn/calls ?c]
                                 [?c :seon.fn/sym ?csym]]
            (vec targets))
      in (seon.db/q db '[:find ?sym ?callersym
                         :in $ [?sym ...]
                         :where [?f :seon.fn/sym ?sym]
                                [?caller :seon.fn/calls ?f]
                                [?caller :seon.fn/sym ?callersym]]
           (vec targets))]
  ;; totals, per-namespace counts, and one row per target with its
  ;; db-read callees and its callers
  )
```

### Totals

| measure | value |
|---|---|
| functions in the indexed domain namespaces | **991** |
| private | **630** |
| private with no `:seon.fn/spec` | **630** (100%) |
| public with no `:seon.fn/spec` | **58** |
| private db-readers reported by the graph | **0 — the number is wrong; see finding 1** |
| repository-wide private | 3,161 |
| repository-wide private with no `:seon.fn/spec` | 3,145 (16 contracted, unchanged since §1j) |

**Not one private function in this domain carries a contract.** Three source
hits for `:malli/schema` inside a `defn-` body — `seon.render.ns/signature-form`
(`src/seon/render/ns.clj:269`), `seon.render.ns/empty-comment` (`:440`),
`seon.repl/def-note` (`src/seon/repl.clj:118`) — are the keyword appearing as
*data* in a renderer or a note, not as the function's own contract.

### Source-derived counts (authoritative for classification)

930 private functions across the 46 domain files; 287 carry at least one
triage flag (`db` = calls a `seon.db` read or write; `effect` = an fs, shell,
http, or process crossing; `error` = constructs or names a `:seon.error`
value), 220 of them in `src/`. The per-file table is in appendix A, the
flagged inventory in appendix B.

### Graph-versus-source drift found by the census

- `seon.operator.state` (`resources/seon/operator/state.clj`): the graph holds
  **32 functions, 0 private**; the source has **80 `defn`/`defn-`, 33 private**.
  The operator's protected lifecycle records are indexed less than half.
- `seon.fresh-operator` (`script/seon/fresh_operator.clj`, 136 functions, 134
  private) is **absent from the program graph entirely**, as are
  `seon.dev.changed-test`, `seon.dev.mcp` and `seon.dev.test-roots`. Nothing
  that starts, stops, resets or reforks a cluster can be contracted, armed, or
  found by `my.program/breaks` today.

## 2. The critical private functions, in full

Each entry: what it consumes (shape from the code, with one real call site),
what it returns on every arm, whether it binds a `seon.db` read and what it
does with that value **before** checking `:seon.error/kind`, whether it can
itself return a flat error, the honest contract candidate against the
**registered** registry, the callers that would break the moment it is armed,
and the risk.

Registry keys named below were confirmed present under
`resources/seon/schemas/`: `:seon.db/database-value`, `:seon.db/connection`,
`:seon.db/entity-id`, `:seon.db/transaction-report`, `:seon.error/value`,
`:seon.schema/value`, `:seon.render/profile`, `:seon.render/unit`,
`:seon.render.profile/profile`, `:seon.print/node`, `:seon.fn/sym`,
`:seon.ns/name`, `:seon.schema/key`, `:seon.agent/id`,
`:my.background/invalid-call-error`, `:seon.search/field`,
`:seon.schedule.task/id`.

### `my.program/checked` — `src/my/program.clj:16`

- **Consumes** one value of any shape, the return of a `seon.db` read. Real
  call site: `my.program/locate`, `src/my/program.clj:53`.
- **Returns** the value unchanged, or **throws** `ex-info` carrying the error
  value (`:17-18`).
- **Error handling**: this IS the check — `(:seon.error/kind value)` on entry.
  Correct order.
- **Can return a flat error**: no; it converts one into an exception, which
  `my.program/read-result` (`:21`) converts back into a value.
- **Contract candidate**: none registered, and an honest one is
  *unwritable as declared*: the function's whole purpose is to accept an
  error value and not return. `[:=> [:cat :seon.schema/value] :seon.schema/value]`
  would be a lie on the throwing arm. The right repair is to name the
  intent — `[:=> [:cat [:or :seon.error/value :seon.schema/value]] :seon.schema/value]`
  with the throw documented, or dissolve `checked`/`read-result` into the
  error-value propagation the wrapper already performs (§1j: "a contracted
  consumer refuses it by shape").
- **Breaks when armed**: all 13 of its callers in `my.program` are
  `checked`-wrapped reads; none break.
- **Risk: friction.** It is the one place in `my.*` that turns a value into a
  throw. It works, but it is a second error mechanism inside the agent-facing
  namespace (§2.5).

### `my.program/stored-name` — `src/my/program.clj:38`

- **Consumes** `database` (a database value), `attribute` (a qualified
  keyword), `subject` (a symbol or keyword). Real call site:
  `caller-data`, `:105`.
- **Returns** `(str subject)` when the installed `:db/valueType` is
  `:db.type/string`, else `subject` — a **string or a symbol**, decided per
  database.
- **Error handling**: binds `(db/schema-database database)` and immediately
  `get-in`s `[:schema attribute :db/valueType]` out of it (`:41`) with no
  kind check. On a failed read the `get-in` yields `nil`, which is not
  `:db.type/string`, so the symbol arm is taken silently.
- **Can return a flat error**: no — it returns a *wrong identity value*
  instead, which is worse.
- **Contract candidate**: none registered.
  `[:=> [:cat :seon.db/database-value :qualified-keyword [:or :symbol :qualified-keyword]] [:or :symbol :string :qualified-keyword]]`.
  Its docstring already says it exists only "until the symbols-everywhere
  reset"; the owner's 2026-09-17 ruling retires it.
- **Breaks when armed**: `locate` (`:52`), `caller-data` (`:105`) —
  both already pass a database value.
- **Risk: friction** now, **cleanup** after the reset: this function should be
  deleted, not contracted.

### `my.program/render-referrers` — `src/my/program.clj:202`

- **Consumes** `database`, `subject` (a qualified symbol naming a render
  function). Real call site: `breaks`, `:420` region.
- **Returns** a set of `{:seon.schema/key … :seon.schema/property …}` maps;
  `#{}` when nothing refers.
- **Error handling**: `checked` wraps the `db/q` **before** `keep` consumes it
  (`:209`). Correct.
- **Can return a flat error**: no (it throws through `checked`).
- **Contract candidate**: `[:=> [:cat :seon.db/database-value :qualified-symbol] [:set [:map [:seon.schema/key :seon.schema/key] [:seon.schema/property :qualified-keyword]]]]`.
- **Risk: cleanup.** This is the model the rest of the domain should copy.
- **Note**: it queries `:seon.render/ai` and `:seon.render/html` through a
  **collection binding** — the exact shape finding 1 shows returns storage
  strings rather than symbols. The `(= subject renderer)` comparison at `:207`
  is therefore suspect on the same database that produced finding 1.

### `my.background/invalid-call` — `src/my/background.clj:10`

- **Consumes** `forms`, the raw `&forms` of the `background` macro (a seq of
  unevaluated forms). Real call site: `my.background/background`, `:47` region.
- **Returns** exactly one shape: a flat error with
  `:seon.error/kind ::invalid-call` and `:my.background/invalid-call true`.
- **Error handling**: no database read.
- **Can return a flat error**: yes — it returns *only* that.
- **Contract candidate**: **registered already**:
  `[:=> [:cat :seon.schema/value] :my.background/invalid-call-error]`.
- **Breaks when armed**: one caller.
- **Risk: cleanup.** This is the single easiest contract in the domain.

### `seon.render/ambient-database-value` — `src/seon/render.clj:1661`

- **Consumes** nothing; reads `*walk-context*` and falls back to `(db/db)`.
- **Returns** a database value, **or the flat error `(db/db)` returns when no
  custody is bound**.
- **Error handling**: none. `or` treats the error map as a successful value
  because it is truthy (`:1662-1663`).
- **Can return a flat error**: yes, silently, typed as a database value.
- **Contract candidate**: `[:=> :cat [:or :seon.db/database-value :seon.error/value]]`
  is the truthful one; `[:=> :cat :seon.db/database-value]` is the one we
  *want*, and arming it turns a silent wrong render into a named refusal.
- **Breaks when armed**: every walk entry that renders without custody — which
  is the point. `seon.render/transaction-shape` (`:211`) documents this exact
  class having already produced a 500 on `/data`.
- **Risk: blocker.** An error value flowing onward as `:seon.db/db` is how a
  render reads a refusal as a world.

### `seon.render/namespace-owner` — `src/seon/render.clj:1590`

- **Consumes** `database`, `namespace-name` (a symbol). Real call site: the
  namespace page's owner resolution in `seon.render`.
- **Returns** the agent id (a string), `nil` when no agent owns the namespace,
  **or the error map** when the read fails.
- **Error handling**: the `db/q` result is returned directly (`:1591-1598`);
  nothing checks `:seon.error/kind`.
- **Can return a flat error**: yes, in the position where a caller expects an
  agent id.
- **Contract candidate**: `[:=> [:cat :seon.db/database-value :seon.ns/name] [:maybe :seon.agent/id]]`
  — and the `:maybe` is honest here only because "no owner" is an ordinary
  state; per AGENTS §3 the better shape is absence, i.e. the caller uses
  `find`/`when-let`, which it already does.
- **Risk: friction** (a wrong owner label on a page), **blocker** if the id is
  ever used for custody.

### `seon.render/custody-cluster-name` — `src/seon/render.clj:1666`

- **Consumes** `database`. **Returns** a cluster name, `nil`, or the error map.
- **Error handling**: none (`:1667-1669`).
- **Second defect, independent of the contract**: the query is
  `[_ :seon.cluster/name ?cluster-name]` with a `.` find spec — with more than
  one cluster row in one database value it answers an **arbitrary** one. One
  JVM may host many cluster instances (AGENTS §1.2: "nothing may assume 'the'
  cluster").
- **Contract candidate**: `[:=> [:cat :seon.db/database-value] [:maybe :seon.cluster/name]]`.
- **Risk: blocker** — it is a silent fallback that is right until the second
  cluster (§2.4).

### `seon.render/repl-state` — `src/seon/render.clj:1671`

- **Consumes** `db`, `agent-id`. **Returns** a map of basis and namespace name.
- **Error handling**: binds `(db/basis-t db)` and a `db/q` result and composes
  them into the returned map with no check (`:1672-1680`).
- **Contract candidate**: `[:=> [:cat :seon.db/database-value :seon.agent/id] [:map [:seon.db/basis-t :seon.db/basis-t] [:seon.ns/name {:optional true} :seon.ns/name]]]`.
- **Risk: friction.**

### `seon.render/bounded-error-node` — `src/seon/render.clj:1079`

- **Consumes** `request` (a render request map) and `error` (a flat error
  value). **Returns** `(:seon.sci.admit/print-node …)` — a print node, or
  `nil` if admission returned no node.
- **Error handling**: it is the error path; the `nil` arm is the concern —
  a nil print node reaching the render terminal is an unrendered refusal.
- **Contract candidate**: `[:=> [:cat :seon.render/call-request :seon.error/value] :seon.print/node]`,
  with the nil arm treated as the defect it is.
- **Risk: friction**, rising to blocker if the nil arm is reachable — worth a
  probe by the lane that arms it.

### `seon.render.ns/schema-row` — `src/seon/render/ns.clj:104`

- **Consumes** `db`, `bounds` (a pull-bounds map), `schema-key`. Real call
  site: `cached-schema-row`, `:115`.
- **Returns** the pulled schema row, `nil` when `db` is absent or the key is
  not a qualified keyword, **or the pull's error map**.
- **Error handling**: the `db/pull` result is returned directly (`:107-109`)
  and `cached-schema-row` caches it (`:115`) — so one failed read is
  **memoised** and rendered as a schema row for the rest of the page.
- **Can return a flat error**: yes.
- **Feeds `:seon.render/ai`**: yes — through `render-data` (`:372`) into
  `seon.render.ns/render-ai`, a declared AI render function
  (`resources/seon/schemas/seon.ns.edn:13`). A wrong value here is a wrong
  sentence in an agent's namespace page.
- **Contract candidate**: `[:=> [:cat [:maybe :seon.db/database-value] :seon.db/pull-options :seon.schema/key] [:maybe :seon.schema/projection-row]]`.
- **Risk: blocker** (agent-facing, cached).

### `seon.ai/model-details` and `seon.ai/rendered-model` — `src/seon/ai.clj:169`, `:175`

- **Consumes** `database`, `model-id` / a render `unit`. Real call site:
  `seon.ai/model-ai`, `:197`, the declared AI render function for
  `:seon.ai.model/entity` (`resources/seon/schemas/seon.ai.model.edn:70`).
- **Returns** the pulled model row with `:seon.ai.model/thinking-dials`
  coerced to a set; `nil` when the pull yields nothing.
- **Error handling**: `some->` (`:171`) treats the pull's **error map as a
  present row** — it is truthy — and `update … set` then runs on it,
  producing an error map carrying `:seon.ai.model/thinking-dials #{}`.
  `rendered-model` (`:178-181`) returns that map as "the model".
- **Can return a flat error**: yes, **shaped to look like a model row**.
- **Contract candidate**: `[:=> [:cat :seon.db/database-value :seon.ai.model/id] [:maybe :seon.ai.model/entity]]`.
- **Risk: blocker.** This is `:seon.render/ai` output: an agent reads a
  database refusal as its model's configuration.

### `seon.ai/attempt-without-private-provider-data` — `src/seon/ai.clj:94`

- **Consumes** a render `unit`. **Returns** the unit with private provider
  data removed. Real call sites: `attempt-ai` (`:113`) and `attempt-html`
  (`:119`).
- **Error handling**: no database read.
- **Feeds `:seon.render/ai`**: yes, directly.
- **Contract candidate**: `[:=> [:cat :seon.render/unit] :seon.render/unit]`.
- **Risk: friction**, but it is the redaction seam for provider credentials —
  a contract here is cheap insurance and it has exactly two callers.

### `seon.search/document-specs` — `src/seon/search.clj:180`

- **Consumes** `database`. Real call site: `search-roster`, `:191`.
- **Returns** a vector of `{:seon.search/field … :seon.search/index …}`.
- **Error handling**: `(->> (db/q …) (map (fn [[field mode]] …)))` at
  `:182-184`. On a failed read the error **map** is threaded into `map`, whose
  destructuring reads each `MapEntry` as `[field mode]` — so the index spec
  becomes `{:seon.search/field :seon.error/kind, :seon.search/index :seon.db/invalid-read}`
  and friends. No check anywhere.
- **Can return a flat error**: no — it returns *fabricated specs*.
- **Contract candidate**: `[:=> [:cat :seon.db/database-value] [:vector [:map [:seon.search/field :seon.search/field] [:seon.search/index :seon.search/index]]]]`.
- **Risk: blocker.** `rebuild!` (`:276`) calls `.deleteAll` on the Lucene
  writer and then rebuilds from this roster: a failed read empties the search
  index and refills it with garbage, and the only signal is that search stops
  answering.

### `seon.search/declared-entity-ids` — `src/seon/search.clj:255`

- **Consumes** `database`, `roster`. **Returns** a set of entity ids.
- **Error handling**: `mapcat` over each field's `db/q` (`:259-263`) with no
  check; an error map contributes its MapEntries as "entity ids".
- **Contract candidate**: `[:=> [:cat :seon.db/database-value :seon.search/roster] [:set :seon.db/entity-id]]`
  — `:seon.search/roster` is **not registered**; needs `:seon.search/roster`
  declared beside the existing `:seon.search/families`/`field`/`index`.
- **Risk: blocker** (same rebuild path).

### `seon.search/entity-documents` — `src/seon/search.clj:237`

- **Consumes** `database`, `roster`, `eid`. **Returns** a vector of Lucene
  documents; `[]` when the row has none of the roster's identity attributes.
- **Error handling**: `(db/pull database '[*] eid)` bound as `row` at `:239`
  and immediately consumed by `(get row family)` at `:243` — an error map has
  no identity attribute, so the function answers "this entity has nothing to
  index". Absence read as health, exactly §2.4's named class.
- **Contract candidate**: `[:=> [:cat :seon.db/database-value :seon.search/roster :seon.db/entity-id] [:vector :seon.search/document]]`
  — `:seon.search/document` not registered either.
- **Risk: blocker.**

### `seon.schedule/task-rows` — `src/seon/schedule.clj:205`

- **Consumes** `database`, `agent-id`. **Returns** a sorted seq of task maps.
- **Error handling**: `(->> (db/q …) (map (fn [[task task-id function expression zone-id]] …)))`
  at `:206-219`. An error map destructures into five names through its
  `MapEntry`s; a failed read becomes a short list of malformed tasks, or an
  empty one.
- **Contract candidate**: `[:=> [:cat :seon.db/database-value :seon.agent/id] [:sequential [:map [:seon.schedule.task/id :seon.schedule.task/id] [:seon.fn/sym :seon.fn/sym]]]]`.
- **Risk: blocker** — scheduled work silently stops being derived.

### `seon.maintenance/task-rows` and `seon.maintenance/latest-receipt` — `src/seon/maintenance.clj:239`, `:252`

- **Consumes** `database` (and `task-eid`). **Returns** sorted rows / the
  latest receipt pull, or `nil`.
- **Error handling**: `task-rows` sorts the `db/q` result (`:249`) with no
  check; `latest-receipt` takes `last` of it and destructures `[receipt-eid]`
  (`:253-265`) — on an error value `receipt-eid` is a **keyword**, which is
  then handed to `db/pull` as an entity id, producing a second error.
- **Contract candidate**: `[:=> [:cat :seon.db/database-value] [:sequential [:map [:seon.schedule.task/id :seon.schedule.task/id]]]]`
  and `[:=> [:cat :seon.db/database-value :seon.db/entity-id] [:maybe :seon.maintenance.receipt/receipt]]`.
- **Risk: blocker** — the root maintenance portfolio reports "no receipts",
  which reads as healthy.

### `seon.render.walk/installed-attributes` — `src/seon/render/walk.clj:65`

- **Consumes** `database`. **Returns** a sorted map of installed attributes to
  their Datahike properties.
- **Error handling**: `(:schema (db/schema-database database))` at `:70` — an
  error map has no `:schema`, so the walk receives `{}` and renders an entity
  with **no attributes**, indistinguishable from an empty entity.
- **Contract candidate**: `[:=> [:cat :seon.db/database-value] [:map-of :qualified-keyword :map]]`.
- **Risk: blocker** — this feeds both the AI walk and the HTML page.

### `seon.render.value/reference-identity` — `src/seon/render/value.clj:252`

- **Consumes** `database`, `value` (an entity id or a map). **Returns** an
  `[attribute value]` identity pair, `[:db/id eid]`, or the input value.
- **Error handling**: reads `(:schema database)` directly (`:258`) and binds
  `(db/pull …)` as `entity` at `:261`, then `find`s attributes on it (`:264`)
  with no check — a failed pull falls through to "no identity", returning the
  raw value, so the rendered reference loses its requery identity silently.
- **Contract candidate**: `[:=> [:cat :seon.db/database-value :seon.schema/value] [:or :seon.print/requery-id :seon.schema/value]]`.
- **Risk: blocker** — the AI projection's requery identity is the agent's only
  way back to an elided value (§2.4).

### `seon.render.web/agent-exists?` and `namespace-exists?` — `src/seon/render/web.clj:2867`, `:2952`

- **Consumes** `db`, `agent-id` / `namespace-name`. **Returns** a boolean.
- **Error handling**: `(some? (db/q …))` at `:2869`. **An error value is
  `some?`**, so a failed read answers **true** — "the agent exists". Its one
  caller `inbound-tx-meta` (`:2875-2878`) then attaches
  `:seon.db/user [:seon.agent/id user-id]` to the inbound message
  transaction for an agent that may not exist.
- **Can return a flat error**: no — it returns a **wrong boolean**, which
  AGENTS §3 names directly: a boolean is legitimate only when someone
  genuinely asserts the false.
- **Contract candidate**: `[:=> [:cat :seon.db/database-value :seon.agent/id] :boolean]`
  — the contract does not fix it; the repair is `(boolean (db/q …))` guarded
  by an error check, or deriving from `find` on a pull.
- **Risk: blocker** — an existence pre-read the writer re-decides (AGENTS §2,
  owner law 2026-08-29), landing in a transaction.

### `seon.schedule/transact-result!` — `src/seon/schedule.clj:481`

- **Consumes** `connection`, `tx-data`, `refusal-kind`, `refusal-data`.
- **Returns** the transaction report; **throws** `ex-info` when the write
  returns an error (`:484-487`).
- **Error handling**: it checks `:seon.error/kind` first (`:484`). Correct
  order, wrong disposition under §1j ("errors stay values… no throwing"),
  though it runs inside a proc where the fault committer catches it.
- **Contract candidate**: `[:=> [:cat :seon.db/connection :seon.db/tx-data :qualified-keyword :map] :seon.db/transaction-report]`.
- **Risk: friction.**

### `seon.schedule/flat-error?` — `src/seon/schedule.clj:489`

- A hand-rolled second error predicate (`map?` + `:seon.error/kind` +
  `:seon.error/message`) beside `seon.error`'s own. §2.5: one mechanism.
- **Contract candidate**: `[:=> [:cat :seon.schema/value] :boolean]` — but the
  correct change is deletion in favour of the registered `:seon.error/value`
  schema.
- **Risk: cleanup.**

### `seon.operator/branch-digests` — `src/seon/operator.clj:688`

- **Consumes** `operation-store`, `branch`. **Returns** a set of digests.
- **Error handling**: binds `(db/history database)` and **checks
  `error-value?` before use** (`:692-694`), falling back to the non-history
  database value. Correct, and the only such check found in the domain outside
  `my.program`.
- **Contract candidate**: `[:=> [:cat :seon.store/store :string] [:set :string]]`.
- **Risk: cleanup.** Record it as the reference pattern for the repair lanes.

### `seon.fs/delete-recursively-impl!` — `src/seon/fs.clj:220`

- **Consumes** `root`, `target`, `progress!`, `progress-backstop-ms`.
- **Returns** `nil`; **throws** `ex-info` on an out-of-root target (`:229`),
  an intermediate symlink (`:236`), or an entry escaping the root (`:246`).
- **Error handling**: no database read. The symlink guard honours AGENTS §6
  ("recursive deletion NEVER follows symlinks").
- **Contract candidate**: `[:=> [:cat :seon.fs/path :seon.fs/path [:maybe :seon.fn/sym] :int] :nil]`
  — `:seon.fs/path` is **not registered** (`seon.config.fs.edn` holds only
  bounds); needs `:seon.fs/path`.
- **Risk: friction** for the contract, but the function is the most
  destructive private function in the domain: it deserves its contract first
  among the effect crossings.

### `seon.print/requery-form` — `src/seon/print.cljc:442`

- **Consumes** `identity` (a symbol, seq, vector, or int) and `path` (a
  vector). **Returns** a `seon.print/value-at` form, or `nil` when `identity`
  is none of those shapes.
- **Error handling**: no database read — it *emits* a `seon.db/pull` form as
  data. (The audit's own detector flagged it on that literal; recorded here so
  the next reader does not repeat the mistake.)
- **Contract candidate**: `[:=> [:cat [:or :symbol :seq :vector :int] [:vector :seon.schema/value]] [:maybe :seon.print/requery-form]]`
  — `:seon.print/requery-form` **is registered**.
- **Risk: cleanup**, and one of the easiest honest contracts in the domain.

## 3. Findings

### Finding 1 (blocker) — `seon.db/q` answers symbol-valued attributes with a refusal or with storage strings, and the census read the refusal as rows

Two separate behaviours, one owner (`seon.db` query codec):

1. A **literal or scalar symbol** in a value position refuses. Evaluated on
   `default`: `[:find [?csym ...] :in $ ?sym :where [?f :seon.fn/sym ?sym] [?f :seon.fn/calls ?c] [?c :seon.fn/sym ?csym]]`
   with `'my.program/key-data` returned
   `{:seon.error/kind :seon.db/invalid-read, :seon.db/operation seon.db/q,
     :seon.db/exception-class java.lang.ClassCastException,
     :seon.error/message "class clojure.lang.Symbol cannot be cast to class java.lang.String"}`.
   `[?c :seon.fn/sym seon.db/q]` as a ground literal refuses identically.
2. A **collection binding** of the same attribute answers with the **storage
   string** instead of the symbol. This is already filed —
   [collection-attribute-query-bindings-leak-edn-storage-values](../../../seon/issues/collection-attribute-query-bindings-leak-edn-storage-values.md),
   status open, severity **friction** — with `src/seon/db.clj`
   `query-variable-attributes` / `query-find-attributes` /
   `decode-query-field` named as the seam.

Together they are why the census's db-read column was zero: the callee symbols
came back as strings, so intersecting them with a set of symbols matched
nothing, and the corrective probes refused outright. **The existing note's
severity is wrong.** This is not friction: it silently returns *a different
answer* for the program graph, which is the substrate of the entire AI-first
mission (PRD §0a), and it made a census answer 0 where the true number is
113. The refusal on literal symbol values is a **new instance** not recorded
in that note — the note asserts literal and scalar bindings are the supported
path. Both belong in it, and the severity belongs at blocker.

This finding also demonstrates the class §1j is about, in the auditor's own
probe: the first census bound the error map and threaded it through
`reduce`/`map` destructuring, producing empty call sets with no signal.

### Finding 2 (blocker) — 69 private functions bind a `seon.db` read and use it before any kind check

Derived from source across the domain; the full list is appendix B's `db`
rows minus the guarded ones. The class note exists —
[a-database-reads-error-value-is-read-as-a-row-by-its-caller](../../../seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md),
status open, severity blocker, with three instances recorded. This audit adds
**69 more in one domain**, with four distinct consumption shapes worth naming
because each needs a different repair:

1. **Threaded through `map`/`mapcat` destructuring** — the error's `MapEntry`s
   become rows. `seon.search/document-specs` (`:182`),
   `seon.search/declared-entity-ids` (`:259`), `seon.schedule/task-rows`
   (`:206`), `seon.maintenance/task-rows` (`:240`).
2. **Keyed into** — `(get row family)`, `(:schema …)` — absence answers "this
   entity has nothing". `seon.search/entity-documents` (`:243`),
   `seon.render.walk/installed-attributes` (`:70`),
   `my.program/stored-name` (`:41`).
3. **Truthiness** — `some->`, `or`, `some?` treat the error as present.
   `seon.ai/model-details` (`:171`), `seon.render/ambient-database-value`
   (`:1662`), `seon.render.web/agent-exists?` (`:2869`).
4. **Returned as the answer** — `seon.render/namespace-owner` (`:1591`),
   `seon.render/custody-cluster-name` (`:1667`),
   `seon.render.ns/schema-row` (`:107`, then **memoised** at `:115`).

### Finding 3 (blocker) — the operator supervisor is outside the program graph

`script/seon/fresh_operator.clj` (`seon.fresh-operator`, 136 functions, 134
private) has **no rows** in the graph, and `resources/seon/operator/state.clj`
(`seon.operator.state`) is indexed at 32 of its 80 functions with **0 of its
33 private functions marked private**. Consequences, in order of severity:
no contract on any of them can ever be armed (instrumentation arms from the
graph); `my.program/breaks` cannot see a caller in the operator, so the
unbreakable-connection guarantee of PRD §0a has a hole exactly where cluster
lifecycle lives; and `bin/test`'s changed-since-green selection (derived from
`:seon.fn/calls`) cannot reach a test through an operator change. An issue
note exists for a neighbouring symptom —
[output-sink-query-excludes-operator-and-mcp-scripts](../../../seon/issues/output-sink-query-excludes-operator-and-mcp-scripts.md)
— but it is scoped to the output-sink query, not to indexing. This needs its
own note.

### Finding 4 (friction) — `seon.render/custody-cluster-name` assumes one cluster per database value

`src/seon/render.clj:1667`: `[_ :seon.cluster/name ?cluster-name]` with a `.`
find spec answers an arbitrary row. AGENTS §1.2 states one JVM may host many
cluster instances and nothing may assume "the" cluster. No issue note found.

### Finding 5 (friction) — a second error predicate in `seon.schedule`

`seon.schedule/flat-error?` (`src/seon/schedule.clj:489`) re-implements the
error-value test beside the registered `:seon.error/value` schema and
`seon.db`'s `error-value?`. §2.5. No issue note found.

### Finding 6 (friction) — `my.program/checked` converts a value into a throw inside the agent-facing namespace

`src/my/program.clj:16-19` throws, and `read-result` (`:21-36`) converts back.
Under §1j's "errors stay values… a contracted consumer refuses it by shape",
this pair is the mechanism the wrapper's buried-error propagation is meant to
dissolve. No issue note found. Recorded, not scheduled — the contracts land
first and may remove the need.

### Finding 7 (cleanup) — 58 public functions in this domain still have no contract

The public-only rule was never fully satisfied here either. They are cheaper
to write than the private ones and they are what other namespaces call.

## 4. Triage order — the ten to contract first

Ordered by what a wrong value costs, not by how easy the contract is. Each
names why it is above the next.

1. **`seon.render/ambient-database-value`** (`src/seon/render.clj:1661`) — an
   error value flowing on as `:seon.db/db` poisons every downstream read in
   the walk. One function, one `or`.
2. **`seon.ai/model-details`** + **`seon.ai/rendered-model`**
   (`src/seon/ai.clj:169`, `:175`) — the only pair in the domain whose wrong
   value is rendered into agent context as configuration through a declared
   `:seon.render/ai` function.
3. **`seon.render.walk/installed-attributes`** (`src/seon/render/walk.clj:65`)
   — a failed read renders an entity with no attributes, in both projections.
4. **`seon.search/document-specs`** (`src/seon/search.clj:180`) — `rebuild!`
   deletes the whole index before rebuilding from it.
5. **`seon.search/declared-entity-ids`** and **`entity-documents`** (`:255`,
   `:237`) — the other two halves of that same rebuild.
6. **`seon.render.web/agent-exists?`** / **`namespace-exists?`** (`:2867`,
   `:2952`) — a wrong boolean reaching a transaction's provenance; also the
   clearest existence-pre-read instance in the domain.
7. **`seon.render.ns/schema-row`** (`src/seon/render/ns.clj:104`) — agent-facing
   and **memoised**, so one failure persists for the page.
8. **`seon.schedule/task-rows`** (`src/seon/schedule.clj:205`) and
   **`seon.maintenance/task-rows`** (`src/seon/maintenance.clj:239`) —
   scheduled and maintenance work silently ceasing to be derived is the
   project's named failure class.
9. **`seon.render.value/reference-identity`** (`src/seon/render/value.clj:252`)
   — losing requery identity strands an agent at an elision.
10. **`seon.fs/delete-recursively-impl!`** (`src/seon/fs.clj:220`) — the most
    destructive private function here; its guards are correct today and a
    contract is what keeps them correct.

Before any of these land, **finding 1 should be fixed or the repair lanes
should be told not to trust `seon.db/q` over symbol-valued attributes** —
otherwise a lane probing the program graph to find its callers gets an empty
answer and concludes the function is unused.

## 5. Easy first issues for live agents

Private functions whose contract is obvious from one call site, with few
callers and existing reaching tests. Each is a complete task.

| function | file:line | why it is easy |
|---|---|---|
| `my.background/invalid-call` | `src/my/background.clj:10` | one caller (the `background` macro); the output schema `:my.background/invalid-call-error` is **already registered**; returns exactly one shape |
| `seon.print/requery-form` | `src/seon/print.cljc:442` | pure, no database, output schema `:seon.print/requery-form` already registered; the only judgement is the `nil` arm |
| `my.program/symbols` | `src/my/program.clj:82` | `[:=> [:cat [:sequential [:or :symbol :string]]] [:set :symbol]]`; three callers, all in one namespace |
| `my.program/present-groups` | `src/my/program.clj:84` | drops empty-valued entries; `[:=> [:cat :map] :map]`; four callers |
| `my.program/subject-identities` | `src/my/program.clj:44` | total over three input shapes, returns a vector of qualified keywords; one caller |
| `my.program/observation` | `src/my/program.clj:89` | builds a fixed map; the only read is `db/basis-t`, whose contract is already declared |
| `seon.ai.tokens/rounded` | `src/seon/ai/tokens.cljc` | numeric, one caller (`report-sentence`) |
| `seon.ai.tokens/observation-usable?` | `src/seon/ai/tokens.cljc` | predicate over a recorded usage row, two callers |
| `seon.ai/attempt-without-private-provider-data` | `src/seon/ai.clj:94` | `:seon.render/unit` in and out; two callers, both declared render functions |
| `seon.render/transaction-shape` | `src/seon/render.clj:211` | its docstring already states the contract and the defect it repaired |

Each of these has its callers inside one namespace, so the arming blast radius
is that namespace's tests. An agent taking one of them should confirm the
reaching tests with `seon.fn/gate-set` rather than a name search — and should
read finding 1 first, because that query is exactly the one that lies.

## 6. How to regenerate

The source-derived inventory came from one script over the 46 domain files:
top-level `defn`/`defn-` forms, private detected by `defn-` or `:private true`
within the declaration head, flags by literal scan of the form's body for
`seon.db` reads, effect crossings, and `:seon.error` construction. It is a
point-in-time record at `247bb115b`, not a maintained mirror (AGENTS §2.2,
derive-or-die): the durable derivation is the census query above, once
finding 1 is repaired and the call-edge column can be trusted.

## Appendix A — private functions per file

| file | `defn`/`defn-` | private | private flagged db / effect / error |
|---|---|---|---|
| `src/my/agent.clj` | 4 | 0 | 0 / 0 / 0 |
| `src/my/background.clj` | 3 | 1 | 0 / 0 / 1 |
| `src/my/edit.clj` | 3 | 0 | 0 / 0 / 0 |
| `src/my/fs.clj` | 4 | 0 | 0 / 0 / 0 |
| `src/my/issue.clj` | 3 | 0 | 0 / 0 / 0 |
| `src/my/message.clj` | 4 | 0 | 0 / 0 / 0 |
| `src/my/note.clj` | 3 | 0 | 0 / 0 / 0 |
| `src/my/plan.clj` | 11 | 0 | 0 / 0 / 0 |
| `src/my/program.clj` | 30 | 19 | 10 / 0 / 5 |
| `src/my/shell.clj` | 1 | 0 | 0 / 0 / 0 |
| `src/my/test.clj` | 1 | 0 | 0 / 0 / 0 |
| `src/my/turn.clj` | 4 | 0 | 0 / 0 / 0 |
| `src/my/web.clj` | 2 | 0 | 0 / 0 / 0 |
| `src/seon/render.clj` | 87 | 58 | 10 / 1 / 12 |
| `src/seon/render/agent.clj` | 2 | 0 | 0 / 0 / 0 |
| `src/seon/render/block.clj` | 1 | 0 | 0 / 0 / 0 |
| `src/seon/render/data.clj` | 9 | 5 | 0 / 0 / 3 |
| `src/seon/render/hiccup.clj` | 16 | 10 | 0 / 0 / 1 |
| `src/seon/render/lint.clj` | 28 | 23 | 0 / 0 / 3 |
| `src/seon/render/ns.clj` | 62 | 50 | 5 / 1 / 17 |
| `src/seon/render/route.clj` | 1 | 0 | 0 / 0 / 0 |
| `src/seon/render/test.clj` | 7 | 5 | 1 / 0 / 1 |
| `src/seon/render/transcript.clj` | 119 | 94 | 29 / 1 / 22 |
| `src/seon/render/value.clj` | 29 | 16 | 4 / 0 / 2 |
| `src/seon/render/walk.clj` | 36 | 25 | 3 / 0 / 3 |
| `src/seon/render/web.clj` | 132 | 118 | 34 / 4 / 21 |
| `src/seon/repl.clj` | 23 | 8 | 0 / 0 / 0 |
| `src/seon/print.cljc` | 65 | 46 | 1 / 0 / 1 |
| `src/seon/operator.clj` | 56 | 38 | 1 / 6 / 12 |
| `resources/seon/operator/state.clj` | 80 | 33 | 0 / 4 / 4 |
| `script/seon/fresh_operator.clj` | 136 | 134 | 0 / 33 / 24 |
| `src/seon/fs.clj` | 18 | 10 | 0 / 6 / 1 |
| `src/seon/ai.clj` | 63 | 32 | 2 / 7 / 6 |
| `src/seon/ai/tokens.cljc` | 10 | 2 | 0 / 0 / 0 |
| `src/seon/schedule.clj` | 36 | 27 | 5 / 4 / 7 |
| `src/seon/maintenance.clj` | 30 | 21 | 3 / 1 / 5 |
| `src/seon/search.clj` | 25 | 17 | 5 / 0 / 1 |
| `script/seon/dev/changed_test.clj` | 19 | 11 | 0 / 1 / 1 |
| `script/seon/dev/clj_kondo.clj` | 5 | 4 | 0 / 1 / 0 |
| `script/seon/dev/docstring.clj` | 12 | 8 | 0 / 0 / 0 |
| `script/seon/dev/issues.clj` | 3 | 0 | 0 / 0 / 0 |
| `script/seon/dev/markdown.clj` | 53 | 47 | 0 / 4 / 0 |
| `script/seon/dev/markdown_test.clj` | 5 | 5 | 0 / 1 / 0 |
| `script/seon/dev/mcp.clj` | 54 | 53 | 0 / 6 / 1 |
| `script/seon/dev/state.clj` | 1 | 0 | 0 / 0 / 0 |
| `script/seon/dev/test_roots.clj` | 15 | 10 | 0 / 2 / 0 |

## Appendix B — the 287 flagged private functions

The remaining 643 private functions in the domain carry no flag: they are
pure helpers over values already in hand. They still need contracts under
§1j; they are not triage-critical.

| symbol | file:line | flags |
|---|---|---|
| `graceful-stop!` | `resources/seon/operator/state.clj:220` | effect |
| `declared-lock-timeout` | `resources/seon/operator/state.clj:436` | error |
| `await-lock-held-transition!` | `resources/seon/operator/state.clj:466` | error |
| `invalid-claim-error` | `resources/seon/operator/state.clj:835` | error |
| `responsive-advertisement?` | `resources/seon/operator/state.clj:1049` | effect |
| `declared-managed-root` | `resources/seon/operator/state.clj:1328` | effect, error |
| `managed-data-paths` | `resources/seon/operator/state.clj:1352` | effect |
| `run-command!` | `script/seon/dev/changed_test.clj:193` | effect, error |
| `input-digest` | `script/seon/dev/clj_kondo.clj:30` | effect |
| `bare-url-links` | `script/seon/dev/markdown.clj:180` | effect |
| `rule-list-style` | `script/seon/dev/markdown.clj:460` | effect |
| `find-in-vault` | `script/seon/dev/markdown.clj:567` | effect |
| `run-rules` | `script/seon/dev/markdown.clj:811` | effect |
| `repository-prd-authorities` | `script/seon/dev/markdown_test.clj:80` | effect |
| `canonical-root` | `script/seon/dev/mcp.clj:87` | effect |
| `operator-private` | `script/seon/dev/mcp.clj:91` | effect |
| `read-clj-endpoint` | `script/seon/dev/mcp.clj:261` | effect |
| `require-single-clj-form!` | `script/seon/dev/mcp.clj:404` | effect |
| `one-shot-events!` | `script/seon/dev/mcp.clj:548` | effect |
| `sci-evaluation-form` | `script/seon/dev/mcp.clj:603` | error |
| `execute-clj-eval` | `script/seon/dev/mcp.clj:642` | effect |
| `files-below` | `script/seon/dev/test_roots.clj:12` | effect |
| `below?` | `script/seon/dev/test_roots.clj:48` | effect |
| `repository-root` | `script/seon/fresh_operator.clj:27` | effect |
| `ephemeral-owner` | `script/seon/fresh_operator.clj:37` | effect, error |
| `operator-silence-backstop-ms` | `script/seon/fresh_operator.clj:81` | error |
| `parse-root` | `script/seon/fresh_operator.clj:101` | effect |
| `read-process-records` | `script/seon/fresh_operator.clj:156` | effect, error |
| `write-process-record!` | `script/seon/fresh_operator.clj:186` | effect |
| `publication-bound-ms` | `script/seon/fresh_operator.clj:246` | effect |
| `phase!` | `script/seon/fresh_operator.clj:257` | effect |
| `syntax-preflight!` | `script/seon/fresh_operator.clj:293` | effect |
| `dotenv` | `script/seon/fresh_operator.clj:383` | effect |
| `child-jvm-command` | `script/seon/fresh_operator.clj:395` | effect |
| `run-child-jvm!` | `script/seon/fresh_operator.clj:434` | effect |
| `sparse-manifest` | `script/seon/fresh_operator.clj:632` | effect |
| `jvm-snapshot-form` | `script/seon/fresh_operator.clj:701` | effect |
| `test-status-reader-form` | `script/seon/fresh_operator.clj:832` | error |
| `offline-test-status` | `script/seon/fresh_operator.clj:881` | error |
| `derive-namespace-test-statuses` | `script/seon/fresh_operator.clj:913` | error |
| `test-status-observation!` | `script/seon/fresh_operator.clj:978` | error |
| `read-prepl-reply` | `script/seon/fresh_operator.clj:1067` | error |
| `source-observations` | `script/seon/fresh_operator.clj:1129` | effect, error |
| `derive-cluster-truth` | `script/seon/fresh_operator.clj:1246` | effect |
| `cluster-truth` | `script/seon/fresh_operator.clj:1390` | effect, error |
| `apply-repair!` | `script/seon/fresh_operator.clj:1628` | effect |
| `prepl-eval!` | `script/seon/fresh_operator.clj:1707` | effect, error |
| `effective-form` | `script/seon/fresh_operator.clj:1802` | error |
| `instrument-form` | `script/seon/fresh_operator.clj:1818` | error |
| `start-cluster-form` | `script/seon/fresh_operator.clj:1865` | error |
| `launch-form` | `script/seon/fresh_operator.clj:1890` | effect, error |
| `add-form` | `script/seon/fresh_operator.clj:1962` | effect, error |
| `create-log!` | `script/seon/fresh_operator.clj:1994` | effect |
| `record-launched-process!` | `script/seon/fresh_operator.clj:2038` | effect, error |
| `await-advertisement!` | `script/seon/fresh_operator.clj:2140` | effect, error |
| `start!` | `script/seon/fresh_operator.clj:2265` | effect, error |
| `export-destination` | `script/seon/fresh_operator.clj:2415` | effect |
| `init-form` | `script/seon/fresh_operator.clj:2518` | error |
| `with-test-classpath-form` | `script/seon/fresh_operator.clj:2650` | effect, error |
| `init!` | `script/seon/fresh_operator.clj:2696` | error |
| `init-result!` | `script/seon/fresh_operator.clj:2806` | effect, error |
| `status!` | `script/seon/fresh_operator.clj:2866` | effect, error |
| `stop!` | `script/seon/fresh_operator.clj:3093` | effect |
| `print-process-record-census!` | `script/seon/fresh_operator.clj:3152` | effect |
| `with-exclusive-store-flock-probe!` | `script/seon/fresh_operator.clj:3182` | effect |
| `reset!` | `script/seon/fresh_operator.clj:3308` | effect |
| `help!` | `script/seon/fresh_operator.clj:3362` | effect |
| `invalid-call` | `src/my/background.clj:9` | error |
| `checked` | `src/my/program.clj:16` | error |
| `read-result` | `src/my/program.clj:21` | error |
| `stored-name` | `src/my/program.clj:38` | db |
| `locate` | `src/my/program.clj:51` | db, error |
| `names-through` | `src/my/program.clj:77` | db |
| `observation` | `src/my/program.clj:89` | db |
| `caller-data` | `src/my/program.clj:98` | db |
| `key-data` | `src/my/program.clj:163` | db |
| `render-referrers` | `src/my/program.clj:202` | db |
| `entity-facts` | `src/my/program.clj:306` | db |
| `refusal` | `src/my/program.clj:389` | error |
| `deletion-report` | `src/my/program.clj:419` | db |
| `retract-operation!` | `src/my/program.clj:446` | db, error |
| `model-details` | `src/seon/ai.clj:169` | db |
| `rendered-model` | `src/seon/ai.clj:175` | db |
| `extra-body` | `src/seon/ai.clj:643` | error |
| `unreadable-stream-data` | `src/seon/ai.clj:721` | error |
| `stream-chunk-error` | `src/seon/ai.clj:730` | effect |
| `parsed-completion` | `src/seon/ai.clj:878` | error |
| `transport-before-send?` | `src/seon/ai.clj:1050` | effect |
| `cause-chain` | `src/seon/ai.clj:1124` | effect |
| `truncation` | `src/seon/ai.clj:1171` | effect, error |
| `streamed-completion` | `src/seon/ai.clj:1246` | effect, error |
| `http-request-data` | `src/seon/ai.clj:1295` | effect |
| `send-request` | `src/seon/ai.clj:1325` | effect, error |
| `destructive-canonical-path` | `src/seon/fs.clj:15` | effect |
| `refuse-deletion!` | `src/seon/fs.clj:18` | error |
| `path-string` | `src/seon/fs.clj:24` | effect |
| `under-path?` | `src/seon/fs.clj:30` | effect |
| `normalized-path` | `src/seon/fs.clj:184` | effect |
| `attributes` | `src/seon/fs.clj:197` | effect |
| `delete-recursively-impl!` | `src/seon/fs.clj:220` | effect |
| `error-value` | `src/seon/maintenance.clj:12` | error |
| `claim-error` | `src/seon/maintenance.clj:106` | error |
| `collection-component` | `src/seon/maintenance.clj:200` | error |
| `task-rows` | `src/seon/maintenance.clj:239` | db |
| `latest-receipt` | `src/seon/maintenance.clj:252` | db |
| `last-collection-in` | `src/seon/maintenance.clj:313` | db, error |
| `operation-name` | `src/seon/maintenance.clj:422` | effect |
| `attention-detail` | `src/seon/maintenance.clj:436` | error |
| `flat-error` | `src/seon/operator.clj:36` | error |
| `error-value?` | `src/seon/operator.clj:54` | error |
| `custody-selection-error` | `src/seon/operator.clj:103` | error |
| `selected-connection` | `src/seon/operator.clj:147` | error |
| `refusal` | `src/seon/operator.clj:312` | error |
| `claim-error-id` | `src/seon/operator.clj:318` | effect |
| `claim-error-refusal` | `src/seon/operator.clj:333` | error |
| `store-dir` | `src/seon/operator.clj:522` | effect |
| `quiesce-cluster-under-lock!` | `src/seon/operator.clj:545` | effect, error |
| `finish-cluster-cleanup!` | `src/seon/operator.clj:596` | effect, error |
| `branch-digests` | `src/seon/operator.clj:688` | db, error |
| `digest-reads?` | `src/seon/operator.clj:734` | effect |
| `incomplete-collection!` | `src/seon/operator.clj:809` | error |
| `collect-store!` | `src/seon/operator.clj:890` | error |
| `refuse-misspelled-options!` | `src/seon/operator.clj:992` | error |
| `refork-under-lock!` | `src/seon/operator.clj:1076` | effect |
| `requery-form` | `src/seon/print.cljc:442` | db |
| `elision-node` | `src/seon/print.cljc:1013` | error |
| `target-profile` | `src/seon/render.clj:150` | db |
| `transaction-shape` | `src/seon/render.clj:211` | db |
| `source-return?` | `src/seon/render.clj:252` | error |
| `ambiguity` | `src/seon/render.clj:306` | error |
| `render-invocation-argument` | `src/seon/render.clj:401` | db |
| `render-program-evidence` | `src/seon/render.clj:685` | db, error |
| `invocation-cache-key` | `src/seon/render.clj:771` | effect |
| `unknown-stable-evidence` | `src/seon/render.clj:885` | error |
| `invoke-selected` | `src/seon/render.clj:990` | db, error |
| `invocation-unknown` | `src/seon/render.clj:1030` | error |
| `valid-projection?` | `src/seon/render.clj:1053` | error |
| `bounded-error-node` | `src/seon/render.clj:1079` | db |
| `project-node*` | `src/seon/render.clj:1091` | error |
| `invoke-producer` | `src/seon/render.clj:1207` | error |
| `raw-output` | `src/seon/render.clj:1215` | error |
| `source-provenance-error` | `src/seon/render.clj:1308` | error |
| `namespace-owner` | `src/seon/render.clj:1590` | db |
| `walk-error` | `src/seon/render.clj:1655` | error |
| `ambient-database-value` | `src/seon/render.clj:1661` | db |
| `custody-cluster-name` | `src/seon/render.clj:1666` | db |
| `repl-state` | `src/seon/render.clj:1671` | db |
| `observation-error` | `src/seon/render/data.clj:93` | error |
| `outgoing-page` | `src/seon/render/data.clj:107` | error |
| `incoming-page` | `src/seon/render/data.clj:122` | error |
| `append-element!` | `src/seon/render/hiccup.clj:419` | error |
| `tag-of` | `src/seon/render/lint.clj:89` | error |
| `node-classes` | `src/seon/render/lint.clj:122` | error |
| `node-id` | `src/seon/render/lint.clj:140` | error |
| `error-value?` | `src/seon/render/ns.clj:54` | error |
| `namespace-row` | `src/seon/render/ns.clj:58` | db, error |
| `function-rows` | `src/seon/render/ns.clj:69` | db, error |
| `own-schema-rows` | `src/seon/render/ns.clj:85` | db, error |
| `schema-row` | `src/seon/render/ns.clj:104` | db |
| `referenced-schema-closure` | `src/seon/render/ns.clj:173` | error |
| `referenced-schema-summary` | `src/seon/render/ns.clj:243` | error |
| `render-data` | `src/seon/render/ns.clj:372` | db, error |
| `empty-comment` | `src/seon/render/ns.clj:440` | effect, error |
| `compact-schema-value` | `src/seon/render/ns.clj:493` | error |
| `referenced-schema-ai-section` | `src/seon/render/ns.clj:509` | error |
| `full-ai-text` | `src/seon/render/ns.clj:530` | error |
| `compact-ai-items` | `src/seon/render/ns.clj:557` | error |
| `ai-text` | `src/seon/render/ns.clj:590` | error |
| `budgeted-ai` | `src/seon/render/ns.clj:614` | error |
| `referenced-schema-html` | `src/seon/render/ns.clj:650` | error |
| `full-html-view` | `src/seon/render/ns.clj:679` | error |
| `compact-html-view` | `src/seon/render/ns.clj:733` | error |
| `evidence` | `src/seon/render/test.clj:19` | db, error |
| `recent-message-rows` | `src/seon/render/transcript.clj:89` | db |
| `recent-receipt-rows` | `src/seon/render/transcript.clj:106` | db |
| `pinned-receipt-ids` | `src/seon/render/transcript.clj:123` | db |
| `bootstrap-task-message-eid` | `src/seon/render/transcript.clj:133` | db |
| `selected-run-entity-ids` | `src/seon/render/transcript.clj:177` | db |
| `pulled-many` | `src/seon/render/transcript.clj:197` | db |
| `message-order-facts` | `src/seon/render/transcript.clj:212` | db |
| `receipt-entry` | `src/seon/render/transcript.clj:248` | error |
| `message-text` | `src/seon/render/transcript.clj:382` | db |
| `reasoning-attempts` | `src/seon/render/transcript.clj:510` | db |
| `candidate-history` | `src/seon/render/transcript.clj:600` | db |
| `projection` | `src/seon/render/transcript.clj:655` | db |
| `selected-run-identities` | `src/seon/render/transcript.clj:678` | db, error |
| `missing-selected-run` | `src/seon/render/transcript.clj:696` | db, error |
| `entry-basis` | `src/seon/render/transcript.clj:746` | db |
| `turn-header` | `src/seon/render/transcript.clj:804` | db, error |
| `agent-config` | `src/seon/render/transcript.clj:876` | db, error |
| `runtime-owner` | `src/seon/render/transcript.clj:1004` | db, error |
| `turn-rows` | `src/seon/render/transcript.clj:1129` | db, error |
| `turn-kind` | `src/seon/render/transcript.clj:1140` | db |
| `session-stall` | `src/seon/render/transcript.clj:1211` | db, error |
| `session-header` | `src/seon/render/transcript.clj:1240` | db |
| `emission-label` | `src/seon/render/transcript.clj:1466` | db |
| `outline-calibration` | `src/seon/render/transcript.clj:1515` | error |
| `outline-unit` | `src/seon/render/transcript.clj:1542` | error |
| `outline-everything` | `src/seon/render/transcript.clj:1582` | db, error |
| `ledger-acquisition` | `src/seon/render/transcript.clj:1685` | db, error |
| `ledger-evaluations` | `src/seon/render/transcript.clj:1700` | error |
| `ledger-effects` | `src/seon/render/transcript.clj:1740` | db, error |
| `turn-effects` | `src/seon/render/transcript.clj:1770` | error |
| `ledger-rows` | `src/seon/render/transcript.clj:1790` | error |
| `ledger-turn-body` | `src/seon/render/transcript.clj:1827` | db, error |
| `evaluation-match` | `src/seon/render/transcript.clj:1949` | error |
| `fabricated-problem` | `src/seon/render/transcript.clj:1963` | error |
| `fault-problems` | `src/seon/render/transcript.clj:2014` | db, error |
| `session-budget` | `src/seon/render/transcript.clj:2083` | db, error |
| `session-problems` | `src/seon/render/transcript.clj:2127` | db, effect, error |
| `identity-address` | `src/seon/render/value.clj:96` | db |
| `reference-identity` | `src/seon/render/value.clj:252` | db |
| `attribute-value` | `src/seon/render/value.clj:272` | db |
| `value-node*` | `src/seon/render/value.clj:311` | db |
| `value-node` | `src/seon/render/value.clj:426` | error |
| `render-prepared` | `src/seon/render/value.clj:578` | error |
| `installed-attributes` | `src/seon/render/walk.clj:65` | db |
| `connection-observation` | `src/seon/render/walk.clj:194` | error |
| `acquire-entity` | `src/seon/render/walk.clj:378` | db, error |
| `acquired-tree` | `src/seon/render/walk.clj:428` | db |
| `distance-cap-unit` | `src/seon/render/walk.clj:610` | error |
| `page-result` | `src/seon/render/web.clj:420` | db, error |
| `direct-attribute` | `src/seon/render/web.clj:569` | db |
| `generic-entity` | `src/seon/render/web.clj:581` | db, error |
| `debug-diagnostic` | `src/seon/render/web.clj:694` | error |
| `turn-function-result` | `src/seon/render/web.clj:708` | effect, error |
| `debug-turn-request` | `src/seon/render/web.clj:719` | db |
| `debug-value-html` | `src/seon/render/web.clj:751` | error |
| `debug-program-identity` | `src/seon/render/web.clj:790` | db |
| `graph-model` | `src/seon/render/web.clj:886` | error |
| `debug-preview-html` | `src/seon/render/web.clj:993` | error |
| `experiment-preview-html` | `src/seon/render/web.clj:1058` | error |
| `debug-selected-renderer-details` | `src/seon/render/web.clj:1084` | error |
| `referenced-identities` | `src/seon/render/web.clj:1118` | db |
| `debug-found-value` | `src/seon/render/web.clj:1243` | db |
| `debug-found-values-html` | `src/seon/render/web.clj:1322` | db, error |
| `assigned-agent-namespace` | `src/seon/render/web.clj:1383` | db |
| `render-source-call` | `src/seon/render/web.clj:1390` | db, error |
| `debug-render-experiment` | `src/seon/render/web.clj:1475` | db |
| `refresh-retained-read-evidence` | `src/seon/render/web.clj:1566` | db |
| `acquire-debug-data` | `src/seon/render/web.clj:1581` | db, error |
| `debug-page-result` | `src/seon/render/web.clj:1727` | db, error |
| `acquire-root` | `src/seon/render/web.clj:1942` | db |
| `coalesce-floor` | `src/seon/render/web.clj:1999` | db |
| `unsettled-stream?` | `src/seon/render/web.clj:2008` | db |
| `derive-page` | `src/seon/render/web.clj:2023` | db |
| `derive-page!` | `src/seon/render/web.clj:2106` | db |
| `invalidate-runtime-derived-state` | `src/seon/render/web.clj:2164` | effect |
| `failed-page-result` | `src/seon/render/web.clj:2193` | error |
| `render-pass` | `src/seon/render/web.clj:2273` | db |
| `change-context` | `src/seon/render/web.clj:2397` | db, error |
| `write-package!` | `src/seon/render/web.clj:2641` | effect, error |
| `await-feed-package!` | `src/seon/render/web.clj:2686` | error |
| `decode-form` | `src/seon/render/web.clj:2851` | effect |
| `agent-exists?` | `src/seon/render/web.clj:2867` | db |
| `namespace-exists?` | `src/seon/render/web.clj:2952` | db |
| `agent-namespace` | `src/seon/render/web.clj:2960` | db |
| `current-cluster-name` | `src/seon/render/web.clj:2970` | db |
| `ensure-namespace-owner!` | `src/seon/render/web.clj:2976` | db, error |
| `walk-request` | `src/seon/render/web.clj:3000` | db |
| `page-response` | `src/seon/render/web.clj:3019` | db |
| `debug-turn-response` | `src/seon/render/web.clj:3046` | db |
| `debug-outline-response` | `src/seon/render/web.clj:3073` | db |
| `debug-response` | `src/seon/render/web.clj:3090` | db |
| `canonical-namespace-response` | `src/seon/render/web.clj:3207` | db, error |
| `agent-alias-response` | `src/seon/render/web.clj:3224` | db |
| `context-response` | `src/seon/render/web.clj:3250` | error |
| `data-response` | `src/seon/render/web.clj:3282` | db, error |
| `overlap-twin` | `src/seon/schedule.clj:146` | effect |
| `task-rows` | `src/seon/schedule.clj:205` | db |
| `task-created-at` | `src/seon/schedule.clj:227` | db, effect, error |
| `latest-fire-at` | `src/seon/schedule.clj:247` | db |
| `terminal-receipt` | `src/seon/schedule.clj:400` | db |
| `settle-call` | `src/seon/schedule.clj:419` | error |
| `transact-result!` | `src/seon/schedule.clj:481` | db, error |
| `flat-error?` | `src/seon/schedule.clj:491` | error |
| `canonical-path` | `src/seon/schedule.clj:506` | effect |
| `execution-context` | `src/seon/schedule.clj:510` | effect |
| `error-request` | `src/seon/schedule.clj:532` | error |
| `settle!` | `src/seon/schedule.clj:548` | error |
| `invoke-handler` | `src/seon/schedule.clj:579` | error |
| `document-specs` | `src/seon/search.clj:180` | db |
| `search-roster` | `src/seon/search.clj:190` | db |
| `entity-documents` | `src/seon/search.clj:237` | db |
| `declared-entity-ids` | `src/seon/search.clj:255` | db |
| `rebuild!` | `src/seon/search.clj:276` | db |
| `search-owner` | `src/seon/search.clj:425` | error |