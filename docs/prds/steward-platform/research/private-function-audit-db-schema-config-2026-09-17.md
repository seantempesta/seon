---
type: research
status: active
created: 2026-09-17
tags: [research, steward, program-facts, contracts, malli, private-functions, db, schema, config, error-model, class/absence-as-health]
---

# Private-function contract audit — `seon.db`, `seon.schema*`, `seon.config`, `seon.cluster.store`

Read-only fact-finding for program-facts PRD
[§1j](../plan/program-facts-are-the-runtime-prd-2026-09-17.md) ("every
function carries a contract, private included", owner 2026-09-17 16:30Z).
No source was edited. One read-only MCP evaluation was made against the
`default` cluster (pid 94566, the same JVM §1j's own evidence used); every
other number below is derived from the working tree at `7cec8cb57` and
cross-checked against that query.

**Concurrency note.** The astra `private-contracts` lane writes contracts in
these same files. At the time of this audit `git status` was clean and
`git log --since=3.hours -- src/seon/db.clj src/seon/schema.clj
src/seon/schema/ src/seon/config.clj src/seon/cluster/store.clj` returned
**nothing**: the newest commit touching this domain is `fbb4a205b`
(2026-09-17 00:41). **No contract in this domain has landed since the
ruling.** Every row below therefore reads `no contract` as of `7cec8cb57`;
a reader at a later commit must re-run that `git log` before trusting the
third column.

---

## 1. The census

### The exact form

One `mcp__seon__eval_clj` call, `mode: "jvm"`, `read_only: true`, explicit
custody:

```clojure
(let [conn (seon.operator/connection "default")
      db (seon.db/db conn)
      nses '[seon.db seon.schema seon.schema.admission seon.schema.datahike
             seon.schema.edn seon.schema.form seon.schema.internal
             seon.config seon.cluster.store]
      reads #{"seon.db/pull" "seon.db/q" "seon.db/entity" "seon.db/pull-many"
              "seon.db/transact!" "seon.db/datoms"}
      base (seon.db/q db
             '[:find ?sym ?nsname ?priv ?spec
               :in $ [?nsname ...]
               :where
               [?n :seon.ns/name ?nsname]
               [?f :seon.fn/ns ?n]
               [?f :seon.fn/sym ?sym]
               [(get-else $ ?f :seon.fn/private? false) ?priv]
               [(get-else $ ?f :seon.fn/spec "-none-") ?spec]]
             nses)
      calls (seon.db/q db
              '[:find ?sym ?csym
                :in $ [?nsname ...]
                :where
                [?n :seon.ns/name ?nsname] [?f :seon.fn/ns ?n] [?f :seon.fn/sym ?sym]
                [?f :seon.fn/calls ?c] [?c :seon.fn/sym ?csym]]
              nses)
      callers (seon.db/q db
                '[:find ?sym ?csym
                  :in $ [?nsname ...]
                  :where
                  [?n :seon.ns/name ?nsname] [?f :seon.fn/ns ?n] [?f :seon.fn/sym ?sym]
                  [?c :seon.fn/calls ?f] [?c :seon.fn/sym ?csym]]
                nses)
      call-idx (reduce (fn [m [s c]] (update m s (fnil conj #{}) c)) {} calls)
      caller-idx (reduce (fn [m [s c]] (update m s (fnil conj #{}) c)) {} callers)
      by-ns (group-by second base)
      totals (into (sorted-map)
               (for [[n rs] by-ns]
                 [n {:total (count rs)
                     :private (count (filter #(true? (nth % 2)) rs))
                     :priv-no-spec (count (filter #(and (true? (nth % 2)) (= "-none-" (nth % 3))) rs))
                     :priv-no-spec-db (count (filter #(and (true? (nth % 2)) (= "-none-" (nth % 3))
                                                           (seq (clojure.set/intersection reads (get call-idx (first %) #{}))))
                                                     rs))}]))
      targets (sort (map first (filter #(and (true? (nth % 2)) (= "-none-" (nth % 3))) base)))]
  {:totals totals
   :grand {:rows (count base)
           :private (count (filter #(true? (nth % 2)) base))
           :priv-no-spec (count targets)
           :public-no-spec (count (filter #(and (not (true? (nth % 2))) (= "-none-" (nth % 3))) base))}
   :rows (vec (for [s targets]
                [s
                 (vec (sort (clojure.set/intersection reads (get call-idx s #{}))))
                 (vec (sort (get caller-idx s #{})))]))})
```

Returned in 162 ms; the `:rows` member exceeded
`seon.render.profile/max-children` and was stored as blob
`213ba72e390bdcb712c0a9c7e820ed06a30f8334b7a25fb70f79dcc8dd677f4e`
(121,551 bytes), 32 of 300 shown.

### Totals

| namespace | fn rows | private | private with a contract | private with no contract | of those, calling a `seon.db` read directly |
|---|---:|---:|---:|---:|---:|
| `seon.db` | 181 | 135 | **0** | 135 | 2 |
| `seon.schema` | 151 | 70 | **0** | 70 | 0 |
| `seon.schema.datahike` | 43 | 18 | **0** | 18 | 0 |
| `seon.schema.edn` | 30 | 21 | **0** | 21 | 0 |
| `seon.config` | 29 | 17 | **0** | 17 | 2 |
| `seon.schema.admission` | 25 | 23 | **0** | 23 | 0 |
| `seon.cluster.store` | 25 | 10 | **0** | 10 | 0 |
| `seon.schema.internal` | 13 | 5 | **0** | 5 | 0 |
| `seon.schema.form` | 11 | 1 | **0** | 1 | 0 |
| **total** | **508** | **300** | **0** | **300** | **4** |

Also from the same query: **10 public functions in these namespaces carry
no contract either** (508 rows, 300 private, 198 public of which 188
contracted).

**Zero of 300.** This domain — the database authority, the schema
authority, the configuration authority and the store owner — has no
private contract at all. It is the largest uncontracted private block §1j's
first mined class will meet.

### Why "4" understates the database surface, and the honest count

`:seon.fn/calls` to the six public read names finds only 4, because
`seon.db`'s own privates do not call `seon.db`'s public API — they call
Datahike directly, which is exactly the custody `seon.db` is allowed
(AGENTS §3: "Direct `datahike.api` calls survive only inside `seon.db`…").
Deriving the same population from the working tree gives the honest figure:

| classification | count of the 300 | how derived |
|---|---:|---|
| declares `:malli/schema` | **0** | literal search of each declaration's span |
| calls a public `seon.db` read | 5 | `(db/pull|q|entity|pull-many|datoms|transact!` in span |
| calls `datahike.api` directly (`d/…`) | 23 | `(d/[a-z]` or `datahike.api/` in span |
| constructs or carries an `:seon.error` value | 58 | `error-value`, `diagnostic`, `error.refusal/`, `:seon.error/kind`, `:seon.error/message` |
| branches on an error shape | 53 | `error-value?` or a literal `:seon.error/kind` test |
| **throws** | 32 | `(throw` or `refuse!` in span |

The source-derived population was validated against the graph: the local
walk found **exactly 300 private function declarations, with the per-namespace
counts identical to the Datalog census** (135 / 70 / 23 / 18 / 21 / 1 / 5 /
17 / 10). The two derivations agree member-for-member, so the classifications
above may be read as facts about the same 300 rows.

The 32 throwers are the material finding of the census: in a domain whose
stated law is "Failures return flat `:seon.error` values" (`src/seon/db.clj:5`),
one private function in nine throws. Per namespace: `seon.config` 8,
`seon.schema.edn` 7, `seon.schema` 6, `seon.cluster.store` 4, `seon.db` 4,
`seon.schema.admission` 1, `seon.schema.datahike` 1, `seon.schema.internal` 1.

---

## 2. Critical functions, in full

Selected by the assignment's rule — touches the database, or can return an
error. `file:line` is the declaration's first line at `7cec8cb57`. Callers
are exact: a private function's callers are confined to its own namespace,
and the same-namespace call sets below were cross-checked against the
census's `:seon.fn/_calls` rows.

### 2.1 `seon.db` — the error predicate and its 50 dependents

#### `seon.db/error-value?` — `src/seon/db.clj:160`

```clojure
(defn- error-value?
  [value]
  (and (map? value)
       (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))
```

- **Consumes**: one argument, genuinely any value — a database value, a
  connection, a pull result, a transaction report, a query argument. Real
  call site: `src/seon/db.clj:1706`, `(if (error-value? query-or-database)
  query-or-database …)` inside public `q`.
- **Returns**: `true` / `false`. One arm, no error arm, never nil
  (`and` of three predicates, the first of which is `map?`).
- **Read result before the check**: n/a — it *is* the check.
- **Flat error**: no.
- **Contract candidate**: `[:=> [:cat [:any {:seon.schema.admission/exemption
  :seon.schema.admission/polymorphic-boundary :seon.schema.admission/reason
  "A total predicate accepts arbitrary objects…"}]] :boolean]` — the exact
  shape `seon.error/error?` already declares at `src/seon/error.clj:1645`.
  Registered keys used: `:seon.schema.admission/exemption`. No new key needed.
- **Callers**: 50 sites inside `seon.db` (`:212 :277 :345 :371 :981 :1520
  :1588 :1632 :1649 :1674 :1690 :1706 :1730 :1847 :1895 :1896 :1929 :1980
  :2053 :2101 :2113 :2129 :2150 :2173 :2303 :2304 :2312 :2318 :2325 :2342
  :2544 :2547 :2561 :2566 :2570 :2571 :2640 :2652 :2667 :3141 :3143 :3144
  :3151 :3158 :3281 :3413 :3450 :3589 :3644`). Arming the contract as
  declared breaks none of them — it is total.
- **Risk**: **blocker**, but for its *semantics*, not its contract. See
  finding F1.

#### `seon.db/pull-call` — `src/seon/db.clj:1845`

- **Consumes**: `[database arguments operation operation-key result-key
  public-operation]` — a database value **or an error value** (guarded at
  `:1847`); a vector of the caller's raw pull arguments; the Datahike
  function to apply; a keyword in `#{:pull :pull-many}`; the response key
  (`:datahike.pull/result` / `:datahike.pull-many/result`); and a qualified
  **symbol** naming the public entry. Real call site: public `pull`
  (`src/seon/db.clj:1901`).
- **Returns**, every arm: the error value handed in (`:1847`); a selector
  refusal from `missing-pull-selector-error` (`:1849`); an `::invalid-read`
  diagnostic (`:1859`); a lookup-ref refusal (`:1875`); the decoded pull
  result, which for `pull` is a map **or `nil`** when nothing matches, and
  for `pull-many` a vector; a `dependency-error` from the `catch` (`:1888`).
  So: `[:or :nil :map [:vector :seon.schema/value] :seon.error/value]`.
- **Read result before the check**: `(read-declarations database
  public-operation)` at `:1879` is consumed as the declarations map by
  `decode-pull-result` with no error branch. `read-declarations` reads the
  database's projection; when it refuses, the refusal map is walked as if
  it were the declaration table.
- **Flat error**: yes, four distinct arms.
- **Contract candidate**: `[:=> [:cat [:or :seon.db/database-value
  :seon.error/value] [:vector :seon.schema/value] :seon.schema/value
  [:enum :pull :pull-many] :qualified-keyword :qualified-symbol]
  [:or :nil :map [:vector :seon.schema/value] :seon.error/value]]`.
  Registered: `:seon.db/database-value` (`resources/seon/schemas/seon.db.edn:157`),
  `:seon.error/value`, `:seon.schema/value`. The `operation` argument is a
  raw Clojure function — no registered key, and none should be minted:
  `:fn?` is the honest declaration.
- **Callers**: `seon.db/pull` (`:1901`), `seon.db/pull-many` (`:1950`),
  `seon.db/entity`'s lookup path. All pass a database or an error first, so
  the declared input arms hold.
- **Risk**: **blocker** — it is the read path all three overnight instances
  went through, and `total-pull-arguments` (`:1878`), the function whose
  hot-reload regression triggered them, is called here with no guard.

#### `seon.db/transact-call` — `src/seon/db.clj:3411`

- **Consumes**: `[connection transaction]` — a connection **or an error
  value** (guarded `:3413`); transaction data as a vector, or a map
  carrying `:tx-data` (normalised at `:3429`). Real call site: public
  `transact!` (`src/seon/db.clj:3613`).
- **Returns**, every arm: the error value handed in; a `write-error`
  refusal (`:3435`); the Datahike transaction report with `:db-before` /
  `:db-after` re-carrying projection state (`:3444`); from the `catch`
  (`:3446`) — a validation refusal verbatim (`:3450`), a Seon refusal
  verbatim (`:3452`), a `rejected-value` (`:3456`), or a
  `:seon.db/unknown-failure` value (`:3459`). It also **throws**: `:3422`
  when no projection can be derived, and `:3467` when
  `panic-on-core-error?`.
- **Read result before the check**: `(d/db connection)` at `:3416` is bound
  as `database` and immediately used by `carried-projection` and
  `(:branch (:config database))` at `:3442` with no error branch —
  legitimate here only because `d/db` throws rather than returning a value,
  which the `catch` covers.
- **Flat error**: yes, four arms; and it throws two ways.
- **Contract candidate**: `[:=> [:cat [:or :seon.db/connection
  :seon.error/value] [:or :seon.db/tx-data :map]]
  [:or :seon.db/transaction-report :seon.error/value]]`. Registered:
  `:seon.db/connection` (`seon.db.edn:141`), `:seon.db/tx-data`
  (`seon.db.edn`), `:seon.error/value`. `:seon.db/transaction-report` —
  **check before declaring**: the report shape is consumed as a raw
  Datahike map at `:3444`; if no key is registered this needs
  `:seon.db/transaction-report` minted at the one place, not a bare `:map`.
- **Callers**: `seon.db/transact!` (`:3613`) only.
- **Risk**: **blocker** — see finding F2; this is the one write path and it
  currently reclassifies a precise refusal as "outcome unknown".

#### `seon.db/replay-read` — `src/seon/db.clj:875`

- **Consumes**: `[database request]` — a database value; a read-evidence
  request map keyed by `:seon.db/read-operation`, carrying
  `:seon.db/query-request`, `:seon.db/pull-arguments` or
  `:seon.db/index-page-options`.
- **Returns**: a decoded query result, a decoded pull result, a decoded
  pull-many result, or a decoded index page — **and, for any fifth
  operation value, throws `IllegalArgumentException: No matching clause`**
  (`case` at `:877` has no default arm).
- **Read result before the check**: `(read-declarations database
  'seon.db/replay-read)` is consumed as declarations at `:887`, `:898`,
  `:906`, `:910` — four sites, no error branch.
- **Flat error**: no — and that is the defect (F3).
- **Contract candidate**: `[:=> [:cat :seon.db/database-value
  :seon.db/read-evidence] [:or :seon.schema/value :seon.error/value]]`.
  Registered: `:seon.db/read-evidence`, `:seon.db/read-operation`
  (`resources/seon/schemas/seon.db.edn`). Arming the *input* contract
  converts the `No matching clause` throw into a typed refusal naming
  `:seon.db/read-operation` — which is most of the repair.
- **Callers**: the since-diff path in `seon.db` read-evidence replay.
- **Risk**: **friction** to contract, **blocker** to leave: an
  unrecognised operation currently produces a Java exception naming
  nothing, into the turn loop's since-diff.

### 2.2 `seon.config` — the two named overnight instances

#### `seon.config/effective-in` — `src/seon/config.clj:586`

- **Consumes**: `[db cluster-name projection]` — a database value; the
  cluster name as a string; a schema projection whose
  `:seon.schema.projection/forms` it reads. Real call site:
  `seon.config/effective` (`:575`).
- **Returns**: the effective dial map (`select-keys` of the pulled row); a
  `::missing-effective` error value (`:606`); **or the pull's own refusal
  unchanged** (`:592`).
- **Read result before the check**: **correct here, and the repair from
  `761408a17` is visible** — the pull at `:589` is checked at `:591` before
  any key is read. But the check is a literal `(:seon.error/kind row)`, not
  `error-value?` (which this namespace cannot reach: it is private to
  `seon.db`) and not `seon.error/error?`. See F1.
  **A second, unrepaired read is one branch below**: `(db/q '[…] db)` at
  `:611` is handed straight to `sort`/`vec` at `:610`. A refused query is a
  map; `sort` over a map yields its entries, so `available` silently becomes
  a vector of map entries printed into the refusal prose.
- **Flat error**: yes, two arms.
- **Contract candidate**: `[:=> [:cat [:or :seon.db/database-value
  :seon.error/value] :seon.config/cluster :seon.schema/projection]
  [:or :seon.config/settings :seon.error/value]]`. Registered:
  `:seon.config/cluster` (`resources/seon/schemas/seon.config.edn`),
  `:seon.schema/projection` (`resources/seon/schemas/seon.schema.edn`),
  `:seon.config/settings`, `:seon.config/missing-effective-error`.
- **Callers**: `seon.config/effective` (`:575`).
- **Risk**: **blocker** — the whole cluster's configuration reads through it.

#### `seon.config/population-transaction-data` — `src/seon/config.clj:450`

- **Consumes**: `[forms database desired]` — the projection's forms map; a
  database value; a vector of desired config row maps.
- **Returns**: a vector of transaction-data maps — **or throws** via
  `refuse! ::read-refused` (`:462`) when a pull refuses.
- **Read result before the check**: **correct** — `(db/pull database
  [:db/id] identity)` at `:459` is checked at `:461` before `(:db/id
  pulled)` is read at `:466`. Again by literal `:seon.error/kind`.
- **Flat error**: **no — it throws**, in a namespace whose sibling
  `effective-in` returns one. Two error transports for one failure class in
  one file.
- **Contract candidate**: `[:=> [:cat :seon.schema.projection/forms
  [:or :seon.db/database-value :seon.error/value]
  [:vector :seon.config/settings]]
  [:or [:vector :map] :seon.error/value]]` — the output's error arm is the
  target shape; declaring it is what forces the `refuse!` throw to become a
  value.
- **Callers**: `seon.config/apply-compiled!` (`:487`).
- **Risk**: **blocker** — it mints tempids for identities the database
  already holds when it guesses wrong, which is a conflicting upsert.

#### `seon.config/refuse!` — `src/seon/config.clj:202`

- **Consumes**: `[rule data cause]` — a qualified keyword rule; a map of
  evidence; a `Throwable` or `nil`.
- **Returns**: nothing — it always throws `ex-info`.
- **Flat error**: no. Its `ex-data` carries `:seon.error/kind` and
  `:seon.config/refused` but **no `:seon.error/message`**, so the thrown
  data is not an `:seon.error` value by either `seon.db/error-value?` or
  `seon.error/error?`.
- **Contract candidate**: a function that never returns normally has no
  honest output schema; the accurate declaration is that it is replaced by
  a value-returning constructor. There is a registered class for exactly
  this: `:seon.config/refused-error`
  (`resources/seon/schemas/seon.config.edn`).
- **Callers**: 9 — `admit-initialization`, `admit-initialization-rows`,
  `apply-compiled!`, `compile-manifest`, `compile-settings`,
  `population-transaction-data`, `read-edn-map`, `validate-default-decisions`,
  `validate-layer` (from the census's `:seon.fn/_calls`).
- **Risk**: **blocker** — it is the single point through which every
  configuration failure in the domain leaves as an exception rather than a
  value, and `seon.config/apply!` is agent-reachable.

### 2.3 `seon.cluster.store` and `seon.schema`

#### `seon.cluster.store/refuse!` — `src/seon/cluster/store.clj:203`

- **Consumes**: `[rule message data]` — a qualified keyword; a message
  string; an evidence map. **Returns**: nothing; throws `ex-info` whose
  `ex-data` carries `:seon.error/kind`, `::refused` and `::rule` but no
  `:seon.error/message` (the message rides the exception, not the data).
- **Callers**: `create-store!`, `open-branch!`, `open-store!`.
- **Risk**: **friction**. Unlike `seon.config`, this is boot code, where a
  loud throw before the REPL opens is defensible (AGENTS §1: boot opens the
  REPL before store acquisition). The defect is the *shape*: the ex-data is
  one key short of being a legal error value, so a caller that catches and
  inspects it gets `false` from every error predicate.

#### `seon.cluster.store/create-store!` — `src/seon/cluster/store.clj:385`

- **Consumes**: a store configuration map. **Returns**: the created store,
  or throws through `refuse!`; deletes its own incomplete genesis (it is
  the function `:seon.fn/destroys`' docstring in
  `resources/seon/schemas/seon.fn.edn` names as the deliberate non-owner).
- **Risk**: **friction** — contract it for the argument shape; its
  destructive branch is already the most carefully reasoned code in the
  domain.

#### `seon.schema/derive-projection-from-database` — `src/seon/schema.clj:2536`

- The only private in the 151-row `seon.schema` namespace that touches
  Datahike directly. Consumes a database value, returns a projection.
- **Risk**: **blocker** — it is the fallback the whole "values carry their
  world" law (§2.1) depends on, and it is the function whose refusal, when
  read as a projection, makes every downstream `storable-attribute-in?`
  answer from an empty table. Contract it with `:seon.schema/projection`
  on the output and the `:or :seon.error/value` arm the callers must branch on.

---
## 3. The remaining 300, one line each

Every private function in the census, grouped by namespace, critical
namespaces first. **All 300 carry no contract**, so the third column
records what each one *does* rather than repeating that fact. Flags:

- `DBREAD` — calls a public `seon.db` read (`pull` / `q` / `entity` /
  `pull-many` / `datoms` / `transact!`);
- `DH` — calls `datahike.api` directly;
- `ERR` — constructs or carries an `:seon.error` value, so its output
  contract needs an `:seon.error/value` arm;
- `GUARD` — branches on an error shape, so its *input* contract needs one;
- `THROW` — throws instead of returning a value (the §2.4 breach);
- `NIL?` — has a `when` / `when-let` / `some->` tail, so `:nil` is a real
  output arm and `[:maybe X]` is the lazy declaration to avoid (AGENTS §3:
  absent = no key).

A row with no flag consumes and returns ordinary data; its contract is a
mechanical read of the one call site and the one return expression, which
is why the triage order in §5 puts them last.

### `seon.db` — 135 private, 0 contracted
| symbol | file:line | what it does |
|---|---|---|
| `seon.db/fresh-connection` | src/seon/db.clj:97 | DH |
| `seon.db/error-value` | src/seon/db.clj:137 | ERR, GUARD |
| `seon.db/diagnostic` | src/seon/db.clj:144 | — |
| `seon.db/dependency-error` | src/seon/db.clj:148 | ERR, GUARD |
| `seon.db/error-value?` | src/seon/db.clj:160 | ERR, GUARD |
| `seon.db/connection-projection-state` | src/seon/db.clj:166 | NIL? |
| `seon.db/resolve-database-value` | src/seon/db.clj:219 | DH |
| `seon.db/missing-connection-error` | src/seon/db.clj:234 | DBREAD, ERR |
| `seon.db/current-database-value` | src/seon/db.clj:248 | — |
| `seon.db/current-connection` | src/seon/db.clj:255 | — |
| `seon.db/connection-id` | src/seon/db.clj:261 | — |
| `seon.db/connection-branch` | src/seon/db.clj:375 | — |
| `seon.db/foreign-connection-error` | src/seon/db.clj:379 | ERR, NIL? |
| `seon.db/append-read-evidence!` | src/seon/db.clj:407 | NIL? |
| `seon.db/append-database-evidence!` | src/seon/db.clj:413 | NIL? |
| `seon.db/bounded-read-request?` | src/seon/db.clj:421 | — |
| `seon.db/stable-value` | src/seon/db.clj:435 | — |
| `seon.db/stable-read-result` | src/seon/db.clj:482 | — |
| `seon.db/read-result-digest` | src/seon/db.clj:488 | THROW, NIL? |
| `seon.db/query-index-patterns` | src/seon/db.clj:503 | DH, NIL? |
| `seon.db/pull-index-patterns` | src/seon/db.clj:618 | DH, NIL? |
| `seon.db/append-query-evidence!` | src/seon/db.clj:669 | NIL? |
| `seon.db/append-pull-evidence!` | src/seon/db.clj:713 | NIL? |
| `seon.db/revision-source` | src/seon/db.clj:757 | NIL? |
| `seon.db/dependency-revision` | src/seon/db.clj:780 | DH |
| `seon.db/pull-plan-with-evidence` | src/seon/db.clj:865 | — |
| `seon.db/pull-many-plan-with-evidence` | src/seon/db.clj:870 | — |
| `seon.db/replay-read` | src/seon/db.clj:875 | DH |
| `seon.db/index-pattern-change` | src/seon/db.clj:912 | NIL? |
| `seon.db/index-evidence-current` | src/seon/db.clj:957 | DH, NIL? |
| `seon.db/read-declarations` | src/seon/db.clj:1087 | ERR, THROW, NIL? |
| `seon.db/ask-declarations` | src/seon/db.clj:1097 | — |
| `seon.db/edn-encoded?` | src/seon/db.clj:1114 | — |
| `seon.db/decode-attribute-value` | src/seon/db.clj:1128 | — |
| `seon.db/decode-attribute-maps` | src/seon/db.clj:1134 | — |
| `seon.db/parsed-nodes` | src/seon/db.clj:1159 | — |
| `seon.db/installed-attribute-declarations` | src/seon/db.clj:1168 | NIL? |
| `seon.db/registered-attribute-candidates` | src/seon/db.clj:1188 | NIL? |
| `seon.db/attribute-observation` | src/seon/db.clj:1196 | — |
| `seon.db/unknown-attribute-error` | src/seon/db.clj:1204 | ERR, GUARD |
| `seon.db/lookup-ref-error` | src/seon/db.clj:1222 | ERR, GUARD, NIL? |
| `seon.db/query-input-bindings` | src/seon/db.clj:1269 | NIL? |
| `seon.db/query-patterns` | src/seon/db.clj:1279 | — |
| `seon.db/parsed-node-value` | src/seon/db.clj:1283 | — |
| `seon.db/parsed-pattern-value` | src/seon/db.clj:1290 | — |
| `seon.db/malformed-query-pattern-error` | src/seon/db.clj:1296 | ERR, GUARD, NIL? |
| `seon.db/query-source-databases` | src/seon/db.clj:1314 | DH, NIL? |
| `seon.db/query-attribute-error` | src/seon/db.clj:1324 | NIL? |
| `seon.db/query-variable-attributes` | src/seon/db.clj:1351 | — |
| `seon.db/query-find-attributes` | src/seon/db.clj:1369 | NIL? |
| `seon.db/decode-query-field` | src/seon/db.clj:1383 | — |
| `seon.db/decode-query-tuple` | src/seon/db.clj:1389 | — |
| `seon.db/query-return-map-keys` | src/seon/db.clj:1393 | — |
| `seon.db/decode-query-return-maps` | src/seon/db.clj:1402 | — |
| `seon.db/decode-query-result` | src/seon/db.clj:1418 | NIL? |
| `seon.db/pull-output-options` | src/seon/db.clj:1451 | — |
| `seon.db/decode-pull-child` | src/seon/db.clj:1461 | — |
| `seon.db/decode-pull-entity` | src/seon/db.clj:1471 | — |
| `seon.db/decode-pull-result` | src/seon/db.clj:1498 | NIL? |
| `seon.db/unsupplied-custody-error` | src/seon/db.clj:1535 | DBREAD, ERR |
| `seon.db/source-argument-error` | src/seon/db.clj:1582 | ERR, GUARD, NIL? |
| `seon.db/query-argument-message` | src/seon/db.clj:1592 | DBREAD |
| `seon.db/query-input-shape-error` | src/seon/db.clj:1601 | ERR, GUARD |
| `seon.db/query-input-position` | src/seon/db.clj:1615 | DH, ERR, GUARD |
| `seon.db/aligned-query-arguments` | src/seon/db.clj:1640 | DH, ERR, GUARD |
| `seon.db/missing-query-error` | src/seon/db.clj:1654 | ERR, GUARD, NIL? |
| `seon.db/query-call-valid?` | src/seon/db.clj:1671 | ERR, GUARD |
| `seon.db/query-guard-message` | src/seon/db.clj:1687 | ERR, GUARD, NIL? |
| `seon.db/missing-pull-selector-error` | src/seon/db.clj:1750 | ERR, GUARD, NIL? |
| `seon.db/pull-entity-id` | src/seon/db.clj:1769 | — |
| `seon.db/limit-bearing-expression?` | src/seon/db.clj:1777 | — |
| `seon.db/total-attribute-expression` | src/seon/db.clj:1785 | — |
| `seon.db/total-pull-selector` | src/seon/db.clj:1798 | — |
| `seon.db/total-pull-arguments` | src/seon/db.clj:1826 | — |
| `seon.db/pull-call` | src/seon/db.clj:1845 | ERR, GUARD, NIL? |
| `seon.db/pull-call-valid?` | src/seon/db.clj:1892 | ERR, GUARD |
| `seon.db/entity-call` | src/seon/db.clj:2001 | — |
| `seon.db/datom->data` | src/seon/db.clj:2026 | — |
| `seon.db/decode-index-page` | src/seon/db.clj:2040 | — |
| `seon.db/datoms-call` | src/seon/db.clj:2051 | ERR, GUARD, NIL? |
| `seon.db/datoms-call-valid?` | src/seon/db.clj:2096 | ERR, GUARD |
| `seon.db/database-view` | src/seon/db.clj:2147 | ERR, GUARD |
| `seon.db/database-identity` | src/seon/db.clj:2171 | ERR, GUARD |
| `seon.db/diff-refusal` | src/seon/db.clj:2265 | ERR, GUARD |
| `seon.db/callee-symbol` | src/seon/db.clj:2279 | NIL? |
| `seon.db/external-sinks` | src/seon/db.clj:2285 | ERR, GUARD |
| `seon.db/diff-plan` | src/seon/db.clj:2307 | ERR, GUARD, NIL? |
| `seon.db/output-schema-refs` | src/seon/db.clj:2381 | — |
| `seon.db/terminal-schema-key` | src/seon/db.clj:2393 | — |
| `seon.db/collection-entry-schemas` | src/seon/db.clj:2403 | — |
| `seon.db/row-identity-attribute` | src/seon/db.clj:2425 | NIL? |
| `seon.db/result-identity-attribute` | src/seon/db.clj:2449 | — |
| `seon.db/insert-database-argument` | src/seon/db.clj:2460 | — |
| `seon.db/invoke-diff-function` | src/seon/db.clj:2466 | — |
| `seon.db/identity-diff` | src/seon/db.clj:2480 | — |
| `seon.db/perform-diff` | src/seon/db.clj:2538 | ERR, GUARD, NIL? |
| `seon.db/value-changes` | src/seon/db.clj:2590 | NIL? |
| `seon.db/panic-on-core-error?` | src/seon/db.clj:2676 | DH |
| `seon.db/jdk-integers->long` | src/seon/db.clj:2683 | — |
| `seon.db/entity-identity` | src/seon/db.clj:2700 | DH, NIL? |
| `seon.db/conflict-value` | src/seon/db.clj:2707 | — |
| `seon.db/unique-conflict` | src/seon/db.clj:2713 | DH, NIL? |
| `seon.db/rejection-message` | src/seon/db.clj:2730 | — |
| `seon.db/rejected-value` | src/seon/db.clj:2744 | DH, ERR, GUARD |
| `seon.db/stamp-receipt` | src/seon/db.clj:2768 | — |
| `seon.db/write-entity-schemas` | src/seon/db.clj:2778 | — |
| `seon.db/write-validator` | src/seon/db.clj:2799 | — |
| `seon.db/invalid-write` | src/seon/db.clj:2805 | ERR, GUARD |
| `seon.db/write-ref-error` | src/seon/db.clj:2848 | — |
| `seon.db/write-many-values` | src/seon/db.clj:2857 | — |
| `seon.db/write-value` | src/seon/db.clj:2868 | — |
| `seon.db/write-attribute-error` | src/seon/db.clj:2883 | NIL? |
| `seon.db/write-key-candidates` | src/seon/db.clj:2930 | NIL? |
| `seon.db/write-map-error` | src/seon/db.clj:2946 | NIL? |
| `seon.db/write-error` | src/seon/db.clj:2977 | — |
| `seon.db/write-attribute-plan` | src/seon/db.clj:2989 | NIL? |
| `seon.db/write-entity-value` | src/seon/db.clj:3010 | DH |
| `seon.db/write-error-identity` | src/seon/db.clj:3025 | ERR |
| `seon.db/write-tombstone-validator` | src/seon/db.clj:3031 | NIL? |
| `seon.db/write-entity-error` | src/seon/db.clj:3060 | DH, NIL? |
| `seon.db/declared-arity-bounds` | src/seon/db.clj:3107 | ERR, GUARD |
| `seon.db/arity-admitted?` | src/seon/db.clj:3126 | — |
| `seon.db/arity-mismatches-with` | src/seon/db.clj:3134 | ERR, GUARD, NIL? |
| `seon.db/write-render-target-error` | src/seon/db.clj:3195 | DH, ERR, GUARD, NIL? |
| `seon.db/write-report-error` | src/seon/db.clj:3228 | DH, ERR, GUARD, NIL? |
| `seon.db/write-report-validator` | src/seon/db.clj:3297 | — |
| `seon.db/retention-rules` | src/seon/db.clj:3306 | NIL? |
| `seon.db/retention-snapshot` | src/seon/db.clj:3319 | DH, NIL? |
| `seon.db/retention-check` | src/seon/db.clj:3357 | ERR, GUARD, THROW, NIL? |
| `seon.db/retain-transaction` | src/seon/db.clj:3396 | NIL? |
| `seon.db/transact-call` | src/seon/db.clj:3411 | DH, ERR, GUARD, THROW, NIL? |
| `seon.db/missing-transaction-data-error` | src/seon/db.clj:3475 | ERR, GUARD, NIL? |
| `seon.db/rendered-value` | src/seon/db.clj:3492 | — |
| `seon.db/transaction-value-html` | src/seon/db.clj:3523 | — |
| `seon.db/transaction-result` | src/seon/db.clj:3587 | DH, ERR, GUARD, NIL? |

### `seon.config` — 17 private, 0 contracted
| symbol | file:line | what it does |
|---|---|---|
| `seon.config/short-digest` | src/seon/config.clj:47 | NIL? |
| `seon.config/required-dial-attributes` | src/seon/config.clj:174 | NIL? |
| `seon.config/registration-defaults` | src/seon/config.clj:186 | — |
| `seon.config/refuse!` | src/seon/config.clj:202 | ERR, GUARD, THROW |
| `seon.config/read-edn-map` | src/seon/config.clj:212 | THROW, NIL? |
| `seon.config/validate-layer` | src/seon/config.clj:229 | THROW, NIL? |
| `seon.config/row-identity` | src/seon/config.clj:247 | NIL? |
| `seon.config/admit-initialization-rows` | src/seon/config.clj:258 | THROW, NIL? |
| `seon.config/admit-initialization` | src/seon/config.clj:294 | THROW, NIL? |
| `seon.config/default-document` | src/seon/config.clj:308 | — |
| `seon.config/admitted-default-document` | src/seon/config.clj:314 | — |
| `seon.config/validate-default-decisions` | src/seon/config.clj:328 | THROW, NIL? |
| `seon.config/resolve-smart-decision` | src/seon/config.clj:372 | — |
| `seon.config/compile-settings` | src/seon/config.clj:379 | THROW, NIL? |
| `seon.config/desired-tempid` | src/seon/config.clj:446 | — |
| `seon.config/population-transaction-data` | src/seon/config.clj:450 | DBREAD, ERR, GUARD, THROW, NIL? |
| `seon.config/effective-in` | src/seon/config.clj:586 | DBREAD, ERR, GUARD, NIL? |

### `seon.cluster.store` — 10 private, 0 contracted
| symbol | file:line | what it does |
|---|---|---|
| `seon.cluster.store/fresh-file-lock` | src/seon/cluster/store.clj:98 | ERR, GUARD, THROW |
| `seon.cluster.store/canonical-path` | src/seon/cluster/store.clj:137 | — |
| `seon.cluster.store/open-configuration` | src/seon/cluster/store.clj:194 | — |
| `seon.cluster.store/refuse!` | src/seon/cluster/store.clj:203 | ERR, GUARD, THROW |
| `seon.cluster.store/acquire-flock!` | src/seon/cluster/store.clj:306 | THROW, NIL? |
| `seon.cluster.store/release-flock!` | src/seon/cluster/store.clj:342 | NIL? |
| `seon.cluster.store/genesis-complete?` | src/seon/cluster/store.clj:362 | — |
| `seon.cluster.store/stored-main-keep-history?` | src/seon/cluster/store.clj:368 | — |
| `seon.cluster.store/complete-store?` | src/seon/cluster/store.clj:375 | — |
| `seon.cluster.store/create-store!` | src/seon/cluster/store.clj:385 | DH, THROW, NIL? |

### `seon.schema` — 70 private, 0 contracted
| symbol | file:line | what it does |
|---|---|---|
| `seon.schema/direct-references*` | src/seon/schema.clj:46 | NIL? |
| `seon.schema/reference-cycle` | src/seon/schema.clj:66 | NIL? |
| `seon.schema/assert-acyclic-references!` | src/seon/schema.clj:93 | ERR, GUARD, THROW, NIL? |
| `seon.schema/predicate-symbols-in` | src/seon/schema.clj:114 | — |
| `seon.schema/runtime-predicate` | src/seon/schema.clj:130 | NIL? |
| `seon.schema/loaded-predicate-var` | src/seon/schema.clj:151 | NIL? |
| `seon.schema/converged-predicate-var` | src/seon/schema.clj:169 | NIL? |
| `seon.schema/compiled-function-arities` | src/seon/schema.clj:291 | — |
| `seon.schema/with-compiled-cache` | src/seon/schema.clj:297 | — |
| `seon.schema/projection-cache` | src/seon/schema.clj:326 | — |
| `seon.schema/bound-forms` | src/seon/schema.clj:392 | — |
| `seon.schema/projection-registry` | src/seon/schema.clj:395 | NIL? |
| `seon.schema/reference-registry` | src/seon/schema.clj:516 | — |
| `seon.schema/canonical-reference-graph` | src/seon/schema.clj:540 | — |
| `seon.schema/reference-candidate-keys` | src/seon/schema.clj:574 | — |
| `seon.schema/direct-reference-keys-in` | src/seon/schema.clj:583 | — |
| `seon.schema/portable-string-hash` | src/seon/schema.clj:594 | — |
| `seon.schema/framed` | src/seon/schema.clj:603 | — |
| `seon.schema/canonical-coll-string` | src/seon/schema.clj:606 | — |
| `seon.schema/canonical-value-string` | src/seon/schema.clj:609 | ERR, GUARD, THROW |
| `seon.schema/projection-fingerprint` | src/seon/schema.clj:670 | — |
| `seon.schema/replace-fingerprint-entry` | src/seon/schema.clj:704 | — |
| `seon.schema/frame-namespace` | src/seon/schema.clj:844 | — |
| `seon.schema/canonical-directory` | src/seon/schema.clj:868 | NIL? |
| `seon.schema/frame-source-path` | src/seon/schema.clj:895 | NIL? |
| `seon.schema/first-party-frame?` | src/seon/schema.clj:909 | NIL? |
| `seon.schema/frame-description` | src/seon/schema.clj:917 | — |
| `seon.schema/fallback-caller` | src/seon/schema.clj:921 | NIL? |
| `seon.schema/decade?` | src/seon/schema.clj:952 | — |
| `seon.schema/warn-classpath-fallback!` | src/seon/schema.clj:962 | NIL? |
| `seon.schema/packaged-forms` | src/seon/schema.clj:982 | — |
| `seon.schema/candidate-forms` | src/seon/schema.clj:986 | NIL? |
| `seon.schema/active-projection` | src/seon/schema.clj:1046 | — |
| `seon.schema/update-candidate-forms!` | src/seon/schema.clj:1092 | ERR, GUARD, THROW |
| `seon.schema/candidate-registry` | src/seon/schema.clj:1102 | NIL? |
| `seon.schema/fold-contract-validations` | src/seon/schema.clj:1355 | — |
| `seon.schema/render-declarations-in` | src/seon/schema.clj:1506 | NIL? |
| `seon.schema/map-shaped-schema?` | src/seon/schema.clj:1525 | — |
| `seon.schema/required-map-entries` | src/seon/schema.clj:1534 | NIL? |
| `seon.schema/map-schema-accepts-schema?` | src/seon/schema.clj:1552 | NIL? |
| `seon.schema/schema-accepts-schema?` | src/seon/schema.clj:1561 | — |
| `seon.schema/function-arities` | src/seon/schema.clj:1599 | — |
| `seon.schema/arity-render-input-form` | src/seon/schema.clj:1606 | — |
| `seon.schema/render-contract-observation` | src/seon/schema.clj:1618 | NIL? |
| `seon.schema/render-contract-refusal!` | src/seon/schema.clj:1667 | ERR, GUARD, THROW |
| `seon.schema/assert-render-contracts!` | src/seon/schema.clj:1694 | — |
| `seon.schema/schemas-rendered-by` | src/seon/schema.clj:1707 | — |
| `seon.schema/shape-row-in` | src/seon/schema.clj:1717 | NIL? |
| `seon.schema/projection-fingerprint-from-data` | src/seon/schema.clj:1993 | — |
| `seon.schema/reusable-projection-fingerprint` | src/seon/schema.clj:2004 | — |
| `seon.schema/reverse-dependencies` | src/seon/schema.clj:2011 | — |
| `seon.schema/shape-projections` | src/seon/schema.clj:2022 | — |
| `seon.schema/predicate-functions-with` | src/seon/schema.clj:2255 | — |
| `seon.schema/validate-one-contract!` | src/seon/schema.clj:2270 | — |
| `seon.schema/replace-reverse-dependencies` | src/seon/schema.clj:2305 | — |
| `seon.schema/replace-shape-rows` | src/seon/schema.clj:2322 | — |
| `seon.schema/function-dependents-of` | src/seon/schema.clj:2332 | — |
| `seon.schema/derive-projection-from-database` | src/seon/schema.clj:2536 | DH |
| `seon.schema/delta-over` | src/seon/schema.clj:2880 | — |
| `seon.schema/changed-candidate-keys` | src/seon/schema.clj:2907 | NIL? |
| `seon.schema/dependency-first-schema-keys` | src/seon/schema.clj:2983 | — |
| `seon.schema/function-arities-in` | src/seon/schema.clj:3147 | — |
| `seon.schema/shape-projection` | src/seon/schema.clj:3233 | ERR, GUARD, THROW |
| `seon.schema/identity-only-descriptors-in` | src/seon/schema.clj:3240 | ERR, GUARD, THROW, NIL? |
| `seon.schema/identity-only-descriptors` | src/seon/schema.clj:3276 | — |
| `seon.schema/cached-compiler-in!` | src/seon/schema.clj:3311 | — |
| `seon.schema/shape-rank` | src/seon/schema.clj:3328 | — |
| `seon.schema/complete-present-attrs` | src/seon/schema.clj:3332 | NIL? |
| `seon.schema/diagnostic-present-attrs` | src/seon/schema.clj:3336 | NIL? |
| `seon.schema/diagnostic-schema-keys` | src/seon/schema.clj:3344 | — |

### `seon.schema.datahike` — 18 private, 0 contracted
| symbol | file:line | what it does |
|---|---|---|
| `seon.schema.datahike/packaged-forms` | src/seon/schema/datahike.clj:13 | — |
| `seon.schema.datahike/registration-form` | src/seon/schema/datahike.clj:100 | — |
| `seon.schema.datahike/literal->datahike-value-type` | src/seon/schema/datahike.clj:106 | — |
| `seon.schema.datahike/form->cardinality-in` | src/seon/schema/datahike.clj:205 | — |
| `seon.schema.datahike/form->child-form-in` | src/seon/schema/datahike.clj:224 | — |
| `seon.schema.datahike/refuse-slot!` | src/seon/schema/datahike.clj:370 | ERR, GUARD, THROW |
| `seon.schema.datahike/validate-logical-slot-in!` | src/seon/schema/datahike.clj:381 | NIL? |
| `seon.schema.datahike/encoded-operation?` | src/seon/schema/datahike.clj:391 | — |
| `seon.schema.datahike/mark-encoded` | src/seon/schema/datahike.clj:395 | — |
| `seon.schema.datahike/canonical-print-string` | src/seon/schema/datahike.clj:407 | — |
| `seon.schema.datahike/storage-compare` | src/seon/schema/datahike.clj:417 | — |
| `seon.schema.datahike/reader-round-trips?` | src/seon/schema/datahike.clj:422 | — |
| `seon.schema.datahike/storage-data` | src/seon/schema/datahike.clj:428 | — |
| `seon.schema.datahike/storage-string` | src/seon/schema/datahike.clj:451 | — |
| `seon.schema.datahike/encode-value-in` | src/seon/schema/datahike.clj:455 | — |
| `seon.schema.datahike/encode-entity-in` | src/seon/schema/datahike.clj:475 | — |
| `seon.schema.datahike/encode-call-output-in` | src/seon/schema/datahike.clj:483 | — |
| `seon.schema.datahike/encode-transaction-data-in` | src/seon/schema/datahike.clj:490 | — |

### `seon.schema.admission` — 23 private, 0 contracted
| symbol | file:line | what it does |
|---|---|---|
| `seon.schema.admission/finding` | src/seon/schema/admission.clj:23 | — |
| `seon.schema.admission/parse-source` | src/seon/schema/admission.clj:33 | NIL? |
| `seon.schema.admission/schema-file?` | src/seon/schema/admission.clj:71 | — |
| `seon.schema.admission/schema-files` | src/seon/schema/admission.clj:76 | — |
| `seon.schema.admission/request-units` | src/seon/schema/admission.clj:86 | NIL? |
| `seon.schema.admission/parse-units` | src/seon/schema/admission.clj:105 | — |
| `seon.schema.admission/default-schema-directory` | src/seon/schema/admission.clj:117 | NIL? |
| `seon.schema.admission/canonical-path` | src/seon/schema/admission.clj:121 | NIL? |
| `seon.schema.admission/read-declarations` | src/seon/schema/admission.clj:125 | THROW |
| `seon.schema.admission/default-registry-excluding` | src/seon/schema/admission.clj:135 | — |
| `seon.schema.admission/form-properties` | src/seon/schema/admission.clj:159 | NIL? |
| `seon.schema.admission/form-body` | src/seon/schema/admission.clj:164 | — |
| `seon.schema.admission/schema-children` | src/seon/schema/admission.clj:169 | NIL? |
| `seon.schema.admission/schema-nodes` | src/seon/schema/admission.clj:180 | — |
| `seon.schema.admission/recorded-polymorphism-exemption?` | src/seon/schema/admission.clj:184 | — |
| `seon.schema.admission/polymorphic-form?` | src/seon/schema/admission.clj:193 | — |
| `seon.schema.admission/direct-database-ref?` | src/seon/schema/admission.clj:200 | — |
| `seon.schema.admission/file-namespace` | src/seon/schema/admission.clj:205 | NIL? |
| `seon.schema.admission/house-findings` | src/seon/schema/admission.clj:212 | NIL? |
| `seon.schema.admission/collision-findings` | src/seon/schema/admission.clj:283 | NIL? |
| `seon.schema.admission/exact-reuse-findings` | src/seon/schema/admission.clj:297 | NIL? |
| `seon.schema.admission/name-overlap-findings` | src/seon/schema/admission.clj:327 | NIL? |
| `seon.schema.admission/compilation-finding` | src/seon/schema/admission.clj:351 | — |

### `seon.schema.edn` — 21 private, 0 contracted
| symbol | file:line | what it does |
|---|---|---|
| `seon.schema.edn/config-dial?` | src/seon/schema/edn.clj:31 | — |
| `seon.schema.edn/config-dial-entries` | src/seon/schema/edn.clj:38 | NIL? |
| `seon.schema.edn/config-map-entry` | src/seon/schema/edn.clj:56 | — |
| `seon.schema.edn/duplicate-attribute` | src/seon/schema/edn.clj:120 | NIL? |
| `seon.schema.edn/unreadable-file!` | src/seon/schema/edn.clj:127 | ERR, GUARD, THROW |
| `seon.schema.edn/filesystem-safe-namespace?` | src/seon/schema/edn.clj:137 | — |
| `seon.schema.edn/directory-resource?` | src/seon/schema/edn.clj:145 | — |
| `seon.schema.edn/directory-resource-paths` | src/seon/schema/edn.clj:153 | ERR, GUARD, THROW, NIL? |
| `seon.schema.edn/schema-resource-paths` | src/seon/schema/edn.clj:198 | — |
| `seon.schema.edn/read-schema-resource` | src/seon/schema/edn.clj:207 | ERR, GUARD, THROW, NIL? |
| `seon.schema.edn/resource-filename` | src/seon/schema/edn.clj:254 | — |
| `seon.schema.edn/validate-resource-placement!` | src/seon/schema/edn.clj:259 | ERR, GUARD, THROW, NIL? |
| `seon.schema.edn/merge-schema-resources` | src/seon/schema/edn.clj:305 | ERR, GUARD, THROW, NIL? |
| `seon.schema.edn/resource-population` | src/seon/schema/edn.clj:328 | NIL? |
| `seon.schema.edn/packaged-population` | src/seon/schema/edn.clj:369 | — |
| `seon.schema.edn/predicate-declarations` | src/seon/schema/edn.clj:435 | NIL? |
| `seon.schema.edn/refusal!` | src/seon/schema/edn.clj:464 | ERR, GUARD, THROW, NIL? |
| `seon.schema.edn/predicate-registered?` | src/seon/schema/edn.clj:487 | — |
| `seon.schema.edn/assert-predicates!` | src/seon/schema/edn.clj:505 | NIL? |
| `seon.schema.edn/unresolved-candidate-reference` | src/seon/schema/edn.clj:519 | NIL? |
| `seon.schema.edn/admit-changed-identities` | src/seon/schema/edn.clj:530 | THROW |

### `seon.schema.internal` — 5 private, 0 contracted
| symbol | file:line | what it does |
|---|---|---|
| `seon.schema.internal/contract-error!` | src/seon/schema/internal.cljc:81 | ERR, GUARD, THROW |
| `seon.schema.internal/guarded-predicate-symbol` | src/seon/schema/internal.cljc:95 | — |
| `seon.schema.internal/guarded-predicate-properties-complete?` | src/seon/schema/internal.cljc:103 | — |
| `seon.schema.internal/map-value-maybe?` | src/seon/schema/internal.cljc:112 | NIL? |
| `seon.schema.internal/missing-schema-reference` | src/seon/schema/internal.cljc:273 | NIL? |

### `seon.schema.form` — 1 private, 0 contracted
| symbol | file:line | what it does |
|---|---|---|
| `seon.schema.form/widened-component-child` | src/seon/schema/form.cljc:99 | — |

---

## 4. Findings

Every finding was checked against `docs/seon/issues/` (404 notes) before
being written. Where an open note already owns the class, this section
extends it rather than proposing a second note; §1j's instruction is one
lane per class, and the ledger — not this report — is the launch authority.

### F1 — `seon.db`'s error predicate reads a mirror the error model is deleting — **blocker**

`src/seon/db.clj:160`:

```clojure
(defn- error-value?
  [value]
  (and (map? value)
       (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))
```

`src/seon/error.clj:1645`, the declared authority, disagrees:

```clojure
(if (schema/current-projection)
  (boolean (seq (matched-error-classes value)))
  (and (map? value)
       (contains? value :seon.error/message)))
```

`seon.error/error?` asks the projection which declared classes the value
matches. `seon.db/error-value?` asks whether a **`:seon.error/kind`
keyword** is present. The schema resources say how far apart those are:
**338 declarations carry `:seon.error/class true` across 65 files, and only
10 of those files mention `:seon.error/kind` at all.** The great majority
of Seon's declared error classes are marker-attribute classes —
`:seon.fn/index-refused-error` is `[:map {:seon.error/class true …}
[:seon.fn/index-refused …] [:seon.error/message …]]`, with no `kind` key
(`resources/seon/schemas/seon.fn.edn`). For every one of them
`seon.db/error-value?` answers **`false`**, and the value flows on as an
ordinary map.

That predicate is the guard at **50 sites inside `seon.db`**, including
the input guard of public `q` (`:1706`), `pull-call` (`:1847`),
`datoms-call` (`:2053`), `transact-call` (`:3413`) and `transact!`
(`:3644`).

**The wrong understanding it reveals.** The open blocker
[`a-database-reads-error-value-is-read-as-a-row-by-its-caller`](../../../seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md)
states the class as *callers not checking*, and its §1j resolution is
"contracted consumers" plus `error-value?`. But a consumer that checks
correctly is still not protected, because the check itself is a
hand-maintained mirror of an attribute the error model is in the middle of
removing: the open note
[`error-class-catalog-and-renderers-disagree`](../../../seon/issues/error-class-catalog-and-renderers-disagree.md)
records that "a green derived renderer check does not establish deletion of
`:seon.error/kind`" — the deletion is in flight. Contracting the 352
private read-consumers on top of this predicate hardens a mechanism against
its own normal operation (AGENTS "Prefer dissolution to addition"); it is
the derive-or-die law applied to a predicate.

**Note the mechanism is not forced by the load cycle.** `src/seon/db.clj:44-60`
already resolves six `seon.error` vars lazily
(`(defonce ^:private error-diagnostic (delay (requiring-resolve
'seon.error/diagnostic)))`) precisely to cross that cycle. `error?` can
travel the same way; the duplicate predicate is a choice, not a constraint
(AGENTS §2.5, one mechanism).

**No issue note owns this.** It belongs as a section on the existing open
blocker rather than a new note, because it is the same class one layer down.

### F2 — the one write path degrades a precise refusal to "outcome unknown" — **blocker**

`src/seon/db.clj:3446-3471`, inside `transact-call`'s `catch`:

```clojure
;; A Seon transition refusal returns its own value verbatim.
(some? (:seon.error/kind data))
data
…
:else
(let [failure
      {:seon.error/kind :seon.db/unknown-failure
       :seon.error/message (or (ex-message throwable) (.getName (class throwable)))
       :seon.error/data (or data {}) :seon.db/transaction-outcome-unknown true}]
  …)
```

A refusal thrown through the writer as a marker-class value — which, per
F1, is most of them — has no `:seon.error/kind`, misses the verbatim arm at
`:3452`, and is relabelled `:seon.db/unknown-failure` with
`:seon.db/transaction-outcome-unknown true`.

**The wrong understanding.** This is precisely the project's named
recurring class — *a check that reads absence of signal as health*, in its
worse form: absence of the `kind` mirror is reported as **"we do not know
whether the transaction happened"**, when the throwing code knew exactly
what it refused and said so in a declared class. An agent reading
`transaction-outcome-unknown` cannot retry and cannot diagnose; the
information was destroyed six lines above.

The same literal test appears three more times in the domain, each a
separate hand-written copy of the same mirror: `src/seon/config.clj:591`
(`effective-in`), `src/seon/config.clj:461`
(`population-transaction-data`), `src/seon/db.clj:3450`. Four inline
copies plus `seon.db/error-value?` plus `seon.error/error?` is six
spellings of "is this an error" in one domain.

No issue note covers `transaction-outcome-unknown` as a *misclassification*
(the two notes mentioning the string are fixture notes,
`in-process-runner-rethrows-failed-fixture-delay` and
`in-process-test-runs-poison-the-shared-fixture-base`); the archived
[`transaction-refusal-loses-its-ex-data`](../../../seon/issues/archive/transaction-refusal-loses-its-ex-data.md)
is the same family, resolved for a different arm.

### F3 — `replay-read`'s `case` has no default, so an unknown read operation throws a Java exception naming nothing — **friction**

`src/seon/db.clj:875-909`. The `case` dispatches
`:seon.db/read-operation` over `:q`, `:pull`, `:pull-many`, `:index-page`
and stops. A fifth value — or `nil`, which is what an evidence map missing
the key supplies — throws `IllegalArgumentException: No matching clause`
into the since-diff that runs before every agent turn.

**The wrong understanding**: that the operation set is closed because the
writer is `seon.db`. AGENTS §2.4 requires the typed unknown, never silence
*or* a dependency's exception; the archived note
[`an-unmatched-print-face-throws-no-matching-clause-and-names-nothing`](../../../seon/issues/archive/an-unmatched-print-face-throws-no-matching-clause-and-names-nothing.md)
is the identical defect already fixed once in the print seam, which makes
this a recurrence of a closed class rather than a new one. An input
contract naming `:seon.db/read-operation` is most of the repair.

### F4 — `read-declarations` results are consumed as declaration tables with no error branch — **friction**

`src/seon/db.clj:1087` returns the read's declaration table. Its result is
consumed unchecked at `src/seon/db.clj:1879` (`pull-call`), and at `:887`,
`:898`, `:906`, `:910` (`replay-read`). If it refuses, the refusal map is
walked as the declarations — an attribute lookup against an error value
returns `nil`, so every decoded value silently loses its declared decoding.
This is F1's class at a seam the existing blocker note does not name (it
names `effective-in`, `population-transaction-data`, `record-tx`).

### F5 — two error transports for one failure class inside `seon.config` — **blocker**

`seon.config/effective-in` (`:586`) **returns** a
`:seon.config/missing-effective` value; `seon.config/population-transaction-data`
(`:450`) **throws** through `refuse!` (`:202`) for the same class of cause
(a refused read). Nine private functions call `refuse!`. `seon.config/apply!`
(`:546`) is reachable from an agent evaluation, so the throw crosses an
agent boundary — the exact thing AGENTS §2.4 forbids ("nothing throws into
the loop").

Additionally, `refuse!`'s `ex-info` data carries `:seon.error/kind` and
`:seon.config/refused` but **no `:seon.error/message`** — the message rides
the exception object. So the ex-data a catcher recovers is not a legal
`:seon.error` value under *either* predicate, while a registered class for
it already exists (`:seon.config/refused-error`,
`resources/seon/schemas/seon.config.edn`). `seon.cluster.store/refuse!`
(`:203`) has the same shape defect with a weaker case for repair, since it
is boot code.

No issue note covers this; `publication-issue-indexing-refuses-in-test-runner-fixtures`
is about a different `refuse!`.

### F6 — a second unchecked read inside the function that fixed the class — **friction**

`src/seon/config.clj:608-612`, in `effective-in`'s missing-facts branch:

```clojure
available
(vec (sort (db/q '[:find [?available ...]
                   :where [_ :seon.config/cluster ?available]]
                 db)))
```

The pull two lines above is guarded (the `761408a17` repair); this query is
not. A refused `q` returns a map, `sort` over a map yields its entries, and
the refusal prose then reports the error's own keys as "available clusters".
It fails the way the original bug failed, inside the repair. It is an
instance of the existing open blocker, worth adding to that note's
instance list rather than filing separately.

### F7 — `:seon.fn/sym` is a string while `:seon.ns/name` is a symbol — **cleanup, already owned**

`resources/seon/schemas/seon.fn.edn` declares `:sym [:string {:min 1} …]`;
`resources/seon/schemas/seon.ns.edn:6` declares `:name [:symbol …]`. The
owner's 2026-09-17 ruling is that anything that *is* a symbol is stored as
a symbol. This is already inventoried in
[`symbols-everywhere-inventory-2026-09-17.md`](symbols-everywhere-inventory-2026-09-17.md);
recorded here only because it is why the census query above compares
`?sym` against strings and `?nsname` against symbols in the same `:where`
clause, which will silently stop matching when the attribute type changes
at the next reset.

---

## 5. Triage order — the ten to contract first

The order is not "most calls". It is: **dissolve the predicate before
arming 300 contracts on top of it**, then the three seams where a wrong
answer is a durable database effect, then the reads.

1. **`seon.db/error-value?`** (`src/seon/db.clj:160`) — not to contract it
   (its contract is trivial) but because **every other contract in this
   domain is only as honest as this predicate**. Arming 352 private
   read-consumers against a predicate that returns `false` for ~97% of
   declared error classes buys a green suite and no protection. Dissolve it
   into `seon.error/error?` through the existing lazy-resolve seam
   (`src/seon/db.clj:44-60`) first. One deletion, 50 sites corrected.
2. **`seon.db/transact-call`** (`:3411`) — the one write path, and the one
   place where a precise refusal is currently converted into
   "outcome unknown" (F2). Its output contract's error arm is what forces
   the `:else` branch to stop inventing a classification.
3. **`seon.config/population-transaction-data`** (`:450`) — a wrong read
   here mints a tempid for an existing identity and writes a conflicting
   upsert. It is the only function in the census whose misreading *changes
   the database*, and it is already a named instance of the open blocker.
4. **`seon.config/effective-in`** (`:586`) — every dial the cluster runs on
   reads through it, it holds the second unguarded read (F6), and it is the
   other named instance.
5. **`seon.db/pull-call`** (`:1845`) — the read path all three overnight
   instances traversed; contracting it pins the four distinct error arms and
   the real `:nil` arm that callers currently guess at.
6. **`seon.schema/derive-projection-from-database`** (`src/seon/schema.clj:2536`)
   — the projection fallback the "values carry their world" law rests on. Its
   refusal, read as a projection, makes every downstream storability question
   answer from an empty table: absence read as health, at the schema authority.
7. **`seon.db/read-declarations`** (`:1087`) — five unchecked consumption
   sites (F4). Contract the output with its error arm and the five callers
   are forced to branch.
8. **`seon.config/refuse!`** (`:202`) — nine callers, one of them on an
   agent-reachable path, and a registered class
   (`:seon.config/refused-error`) already waiting for it. Converting this
   one function from a throw to a value removes 8 of the domain's 32
   throwers at a stroke.
9. **`seon.db/replay-read`** (`:875`) — runs before every agent turn, and
   its input contract converts a `No matching clause` throw into a typed
   refusal naming `:seon.db/read-operation` (F3).
10. **`seon.db/write-report-error`** (`:3228`) and its neighbours
    `write-render-target-error` (`:3195`), `rejected-value` (`:2744`),
    `transaction-result` (`:3587`) — the four `DH,ERR,GUARD` writer
    diagnostics. They are the functions that *build* the values items 1–2
    are about; contracting them last, once the predicate is settled, means
    their output schemas can name real registered classes instead of `:map`.

**Expect breakage, and read each red as the finding it is** (§1j). The
domain's shape predicts where: 32 throwers whose output contracts cannot be
written honestly without becoming values, and 53 guard sites whose input
contracts will start refusing the marker-class errors that F1 has been
letting through unnoticed.

## 6. What this audit did not establish

- No test was run and no contract was armed; every claim above is a read of
  source at `7cec8cb57` plus one Datalog census.
- The count of *public* functions with a bare `:map` output (§1j's third
  class, 69 system-wide) was not broken down for this domain.
- `:seon.db/transaction-report` was not confirmed to exist as a registered
  key; the §2.1 contract candidate for `transact-call` flags it as a check
  the implementing lane must make before declaring it.
- Whether `seon.error/error?` is safe to call from `seon.db` under the
  projection-free boot window was not probed; F1's repair must establish it.
