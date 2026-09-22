---
type: reference
status: research; every citation read at the cited revision
created: 2026-09-23
revision: 4aeaab1d7
lane: A1 (step 1.4 / A1-3, A1-4, A1-12)
tags: [agent-platform, projection, schema, ambient-transport, site-table]
---

# The projection is a read — the complete site table for step 1.4

**Summary (ten lines).**
1. The ambient transport is four private dynamic Vars in `seon.schema`
   (`*projection*`, `*projection-state*`, `*packaged-forms*`,
   `*candidate-forms-overlay*`, `src/seon/schema.clj:892-896`), two binders
   (`call-with-projection` `:1138`, `call-with-projection-state` `:1146`) and
   three readers (`active-projection` `:1150`, `handed-projection` `:3345`,
   `current-projection` `:3339`).
2. **Real count: 72 call sites in 24 `src` files** (the owner's "~75" is right).
   Breakdown: 47 `call-with-projection`/`-state` wraps, 21 `handed-projection`
   reads, 4 `current-projection` reads. (`rg` finds 73 lines; the 73rd,
   `src/seon/render.clj:150`, is a keyword in an error map, not a call.)
3. **58 of 72 rows are mechanical** — the value that carries the projection is
   already bound in the same `let`, already an argument, or already reachable
   through `db/carried-projection` on a db the function already holds.
4. **9 rows need a contract change** — a function gains a `projection`
   argument, or a request map gains `:seon.schema/projection`.
5. **5 rows need an owner decision** — the two `projection-executor` reifies
   (`cluster.clj:2812`, `flow.clj:1123`), `effect.clj:458` `with-request-context`,
   `instrument.clj:548` `supplied-projection`, and `schema/edn.clj:607` `admit`.
6. Test side: **509 occurrences of `handed-projection` in 86 test files** and
   **~50 `call-with-projection*` wraps in 30 test files**. Almost all are
   `(schema/handed-projection)` used as "give me the fixture's projection" and
   convert to `(db/carried-projection (db/db connection))`.
7. The legitimate post-1.4 route: the projection rides the database value's
   metadata (`db.clj:1214-1219` `carried-projection`, written by
   `carry-projection-state` `:253-274` and `carry-derived-projection` `:276-296`),
   or is handed as an argument. Cold constructors alone call `load-projection`.
8. `load-projection` does not exist yet (0 hits in `src`): A1-3 creates it from
   `projection-from-rows` (`schema.clj:2571-2736`).
9. The decoder-by-argument change is one line in `db.clj:1236-1237` plus its
   `relation-only-declarations` twin `:1257`; the writer-side pattern already
   exists correctly at `fn.clj:3325-3332`.
10. The regression that proves 1.4: a db value from branch A, used while B's
    projection is the ambient one, must answer A's. After 1.4 there is no
    ambient one, so the assertion becomes an identity check on the carried value.

---

## 1. The transport today

| Var / fn | `file:line` | Contract | What it reads |
|---|---|---|---|
| `*projection*` | `src/seon/schema.clj:893` | `^:dynamic ^:private`, nil root | nothing; a thread-local slot |
| `*projection-state*` | `src/seon/schema.clj:894` | `^:dynamic ^:private`, nil root | nothing; holds an atom |
| `*packaged-forms*` | `src/seon/schema.clj:895` | `^:dynamic ^:private` | the packaged `{schema-key form}` map |
| `*candidate-forms-overlay*` | `src/seon/schema.clj:892` | `^:dynamic ^:private` | the registration delta's overlay |
| `call-with-projection` | `src/seon/schema.clj:1138-1144` | `[:=> [:cat :map [:fn ifn?]] …]` | binds `*projection*` to its argument and **clears** `*projection-state*` |
| `call-with-projection-state` | `src/seon/schema.clj:1146-1153` | `[:=> [:cat [:fn deref] [:fn ifn?]] …]` | binds `*projection-state*`, clears `*projection*` and `*packaged-forms*` |
| `active-projection` | `src/seon/schema.clj:1150` (private) | none | `*projection*` |
| `current-projection` | `src/seon/schema.clj:3339-3343` | `[:=> [:cat] [:maybe :map]]` | `(active-projection)` — i.e. `*projection*` only |
| `handed-projection` | `src/seon/schema.clj:3345-3350` | `[:=> [:cat] [:maybe :seon.schema/projection]]` | `(or *projection* (some-> *projection-state* deref :seon.schema/projection))` |
| `shape-projection` | `src/seon/schema.clj:3701-3708` (private) | throws when absent | `(handed-projection)`; the sole reader of the four one-arg shape Vars `:3776, :3846, :3875, :3898` |
| `activate-projection!` | `src/seon/schema.clj:3318-3326` | `[:=> [:catn [::projection :map]] :map]` | **identity already** — returns its argument; a no-op left from the old global |
| registration delta | `src/seon/schema.clj:3366-3480` region; `begin-registration-delta` `:3392-3410`, `delta-over` `:3412` | 0/1-arity | `(candidate-forms)` when no projection is handed |
| `db/carried-projection` | `src/seon/db.clj:1214-1219` | `[:=> [:cat :seon.db/database-value] [:maybe :seon.schema/projection]]` | `(:seon.schema/projection (meta (schema-database database)))` — **the database value itself** |
| `db/projection-fallback` | `src/seon/db.clj:1195-1213` | `[:=> [:cat :qualified-symbol] :seon.schema/validation-refusal]` | nothing; the typed refusal |
| `schema/projection-from-database` | `src/seon/schema.clj:2805-2826` (1- and 2-arity) | `[:function [:=> [:catn [:seon.schema/database-value :map]] ::projection] …]` | re-queries and recompiles the whole population (`derive-projection-from-database` `:2788-2803` → `projection-from-rows` `:2571`) |
| `schema/declaration-projection` | `src/seon/schema.clj:1234-1252` | 0- and 1-arity | the 0-arity hits `declaration-population` (the classpath fallback A1-4 deletes) |

**How a caller obtains the projection legitimately after 1.4.** It is carried on
the database value. `db/carry-projection-state` (`db.clj:253-274`) stamps
`:seon.schema/projection` into the value's metadata at acquisition, and
`db/carried-projection` (`db.clj:1214-1219`) reads it back after walking a
temporal value to its schema origin; `db/carry-derived-projection`
(`db.clj:276-296`) does the same for a commit value with no live connection.
Every function that holds a db value therefore already holds its projection.
A function that holds only a request map reads `(:seon.schema/projection request)`
or `(some-> (:seon.db/db request) db/carried-projection)` — the pattern
`render.clj:123-126` and `render/walk.clj:379-383` already use. A function that
holds an SCI ctx reads `(sci.kernel/context-projection ctx)`. Only the **cold
constructors** build one: cluster boot (`cluster/boot.clj:160-162`), reset, the
source base (`cluster.clj:1613-1614`), the packaged test projection
(`test/arm.clj`, `test/runner.clj:2059`) and `db/carry-derived-projection`.
Those call `load-projection` — the ≈40-line row loader A1-3 makes out of
`projection-from-rows` (`schema.clj:2571-2736`, keeping `projection-rows` `:2539`
and `projection-admissions` `:2556` as its reads). `load-projection` does not
exist at this revision (`rg -c load-projection src` = 0).

---

## 2. THE SITE TABLE

Risk key: **M** mechanical · **C** needs a contract change (a new argument or a
new declared request member) · **O** needs an owner decision.

Replacement shorthand: **delete-wrap** = remove the `call-with-projection*` form
and call the body directly (the body's own `projection` binding already carries
it); **drop-or** = delete the `(schema/handed-projection)` arm of an `or`.

### `src/seon/schema.clj` — the owner (not counted in the 73; deleted, not converted)

| `file:line` | fn | Today | Replacement | Risk |
|---|---|---|---|---|
| `:892-896` | — | the four dynamic Vars | deleted | M |
| `:1138-1153` | `call-with-projection`, `call-with-projection-state` | the binders | deleted | M |
| `:1150`, `:3339`, `:3345` | `active-projection`, `current-projection`, `handed-projection` | the readers | deleted | M |
| `:3701-3708` | `shape-projection` | `(handed-projection)` or throw | deleted with the four one-arg shape Vars `:3776, :3846, :3875, :3898` (no first-party `src` caller; the `-in` twins `:3762, :3838, :3849, :3878` are what everyone calls) | M |
| `:3318-3326` | `activate-projection!` | already identity | deleted | M |

### `src/seon/db.clj` — 7 sites

| `file:line` | fn | Reads today | Value already held | Replacement | Risk |
|---|---|---|---|---|---|
| `:307` | `resolve-database-value` `:298` | `handed-projection` when the fresh `d/db` has no carried projection | `connection`, and `connection-projection-state` `:221` was just consulted at `:302-303` | drop-or: when `carry-projection-state` supplied none, the value genuinely has none — return it and let the reader refuse | M |
| `:1237` | `read-declarations` `:1221` (**the decoder**) | `(or (carried-projection origin) (schema/handed-projection) …)` inside the `::read-projection` delay | `origin` (the schema database) | drop-or → `(or (carried-projection origin) (throw …projection-fallback))`; see §4 | M |
| `:1257` | `relation-only-declarations` `:1250` | `::read-projection (delay (schema/handed-projection))` | nothing — it is a `def` constant | make it a 1-arg fn `(relation-only-declarations projection)`; its one caller is `with-declarations` `:1259` and passes the read's projection | **C** |
| `:1301` | `ask-declarations` `:1288` | `(schema/call-with-projection projection #(question projection))` | `projection`, from `@(::read-projection declarations)` at `:1300` | delete-wrap → `(question projection)`. The docstring's justification (`schema/malli-form?` "builds its own registry and cannot take an argument") is stale: `malli-form?` `:1304-1320` compiles against its own `structural-registry`, never the ambient one. Probe at the REPL before deleting | M |
| `:3740` | `arity-mismatches` `:3728` | `(or (carried-projection database) (schema/handed-projection))` | `database` (the only argument) | drop-or | M |
| `:3961` | `write-report-validator` `:3955` | `(schema/call-with-projection projection (fn [report] …))` | `projection` (the only argument), used at `:3968-3969` | delete-wrap | M |
| `:4136` | `transact-call` `:4121` | third `or` arm after `carried-state` and `carried-projection database` | `database`, `connection` (whose state `:4132` already deref'd) | drop-or | M |

### `src/seon/cluster.clj` — 12 sites

| `file:line` | fn | Reads today | Value already held | Replacement | Risk |
|---|---|---|---|---|---|
| `:315` | `mcp-effective` `:303` | `call-with-projection-state projection-state #(config/effective (db/db connection) cluster-name)` | `projection-state` (bound `:310-312`), `connection` | `(config/effective (db/carry-projection-state (db/db connection) projection-state) cluster-name)` — `config/effective` `:739` already reads the carried value | M |
| `:652` | `mcp-runtime-observation` `:630` | same wrap around `oversight/flow-status` | `projection-state` `:636-638`, `connection` | same: carry the state onto the db value passed in | M |
| `:1319` | `require-admissible-branch!` `:1313` | `(or (schema/handed-projection) (schema/declaration-projection forms))` | `database` (argument) | `(or (db/carried-projection database) (load-projection database))` | M |
| `:1323` | `require-admissible-branch!` | `call-with-projection` around `declaration-changes database projection cluster-name` | `projection` (bound `:1319`), already passed to the body | delete-wrap | M |
| `:1356` | `accrete-schema-population!` `:1329` | `(or (schema/handed-projection) (schema/declaration-projection forms))` | `connection` | `(or (db/carried-projection (db/db connection)) (load-projection …))` | M |
| `:1360` | `accrete-schema-population!` | `call-with-projection` around `declaration-changes (db/db connection) projection …` | `projection` `:1356`, passed to the body | delete-wrap | M |
| `:1424-1426` | `populate-source!` `:1391` | `call-with-projection (or (schema/handed-projection) (schema/declaration-projection forms))` | `connection` (destructured) | bind `projection` in the `let` at `:1420` from `(db/carried-projection (db/db connection))`, delete the wrap, pass `projection` down | **C** (the inner body `:1433-1470` reads it ambiently through `accrete-schema-population!` and `seon.fn/index!`) |
| `:1459` | `populate-source!` | `:seon.schema/projection (schema/handed-projection)` in the `index!` request | the same `projection` binding added above | `:seon.schema/projection projection` | M |
| `:1617` | `source-base!` `:1597` | `call-with-projection-state projection-state #(vector (db/q …) (sci.eval/base-ctx …))` | `projection-state` `:1615`, `projection` `:1614`, `database` `:1613` | `(db/carry-projection-state database projection-state)` once at `:1613`, then delete-wrap | M |
| `:2066` | `development-source-refresh!` `:2024` | `call-with-projection published-projection #(seon.fn/index! {… :seon.schema/projection published-projection …})` | `published-projection`, already a request member | delete-wrap | M |
| `:2104` | `development-source-refresh!` | `call-with-projection projection (fn [] (config/effective database cluster-name) …)` | `projection` `:2082`, `database` `:2081` | `(let [database (db/carry-projection-state database projection-state)] …)` then delete-wrap; `config/effective` reads the carried value | M |
| `:2194-2195` | `refresh-source!` `:2151` | `call-with-projection (schema/declaration-projection (schema.edn/packaged-forms))` | nothing — this is a **cold constructor** | `(load-projection …)` bound in the `let`, handed to `full-source-refresh!` and `development-source-refresh!` as an argument | **C** |
| `:2825` | `projection-executor` `:2812` | wraps every submitted `Runnable` in `call-with-projection-state` | `projection-state` (argument) | **O** — a Runnable carries no world. Either the submitting caller closes over its projection (delete this executor), or the executor stays as the one sanctioned frame carrier. See §7 |

### `src/seon/cluster/boot.clj` — 3 sites (all cold-constructor threading; see §3)

| `file:line` | fn | Reads today | Replacement | Risk |
|---|---|---|---|---|
| `:34` | `stand-cluster-runtime!` `:26` | wraps the whole layer stand in `call-with-projection-state` | delete-wrap; the layer calls below already receive `connection`, whose value carries the state via `db/carry-connection-projection-state!` | M |
| `:165` | `stand-boot-layers!` `:96` | wraps `require-coherent-program!` + `accrete-schema-population!` | delete-wrap after `(db/carry-connection-projection-state! provisional-connection initial-projection-state)` | M |
| `:180` | `stand-boot-layers!` | wraps `store/open-branch!` | delete-wrap; `open-branch!` takes the store and branch name and needs no projection | M |

### `src/seon/render/web.clj` — 7 sites (every one already holds `(sci.kernel/context-projection …)`)

| `file:line` | fn | Reads today | Value already held | Replacement | Risk |
|---|---|---|---|---|---|
| `:683` | `declared-entity-units` `:679` | wrap around a body that uses `projection` at `:689, :693, :696, :698` | `projection` (first argument) | delete-wrap | M |
| `:2186` | `current-page` `:2176` | wrap around `config/effective` + `derive-page!` | `projection` `:2179`, `database` (argument) | `(db/carry-projection-state database …)` is unnecessary — pass `database` with its own carried projection; delete-wrap, add `:seon.render/profile`/`:seon.schema/projection` to `handle` | **C** |
| `:2322-2324` | `render-pass` `:2309` | wrap around the whole 3-arity body | `state`, whose `:seon.turn.loop/cluster` holds the ctx | delete-wrap; bind `projection` in the `let` at `:2326` and pass it to `derive-page!` | **C** |
| `:2472-2473` | `derive-context!` `:2466` | wrap around `change-context` / `request-profile` | `request` | `(assoc request :seon.schema/projection (sci.kernel/context-projection …))`, delete-wrap — `render/request-profile` `:123-126` already reads that member first | M |
| `:3250` | `debug-response` `:3179` | wrap around `config/effective db …` | `projection` `:3248`, `db` `:3241` | delete-wrap; `config/effective` reads `db`'s carried projection | M |
| `:3432` | `data-response` `:3379` | wrap around `config/effective db …` | `projection` `:3430`, `db` | delete-wrap | M |
| `:3535` | `handler` `:3510` | wraps EVERY request through the Ring handler | `service` | delete-wrap: each route handler already receives `service` and reads `(sci.kernel/context-projection (:seon.sci.eval/ctx service))` itself (`:2179, :3248, :3430, :3429`) | M |

### `src/seon/render.clj` — 4 sites

| `file:line` | fn | Reads today | Value already held | Replacement | Risk |
|---|---|---|---|---|---|
| `:126` | `request-profile` `:118` | third `or` arm | `request` `:124`, `(:seon.db/db request)` `:125` | drop-or | M |
| `:137` | `request-profile` | wrap around `config/effective database cluster-name` | `projection` `:124-126`, `database` `:127` | delete-wrap | M |
| `:150` | `request-profile` | `:seon.error/expected [:or :seon.render/profile :seon.schema/handed-projection]` — **a keyword, not a call** | rename to `:seon.schema/projection` | M |
| `:340` | `schema-producers` `:338` | wrap around a body using `projection` at `:345-346` | `projection` (first argument) | delete-wrap | M |
| `:1030` | `invoke-selected` `:1005` | wrap around `sci.kernel/invoke` | `projection` `:1020` (from `request-projection`) | delete-wrap; the invoke request already carries `:seon.db/db` and the ctx | M |

### `src/seon/render/walk.clj` — 5 sites

| `file:line` | fn | Reads today | Value already held | Replacement | Risk |
|---|---|---|---|---|---|
| `:383` | `root-pull-plan` `:375` | fourth `or` arm (`current-projection`) | `request`, `database`, `ctx` — the first three arms | drop-or | M |
| `:551` | `root-acquisition` `:535` | fourth `or` arm | same three arms `:548-550` | drop-or | M |
| `:552` | `root-acquisition` | wrap around the whole body | `projection` `:548` | delete-wrap; pass `(assoc request :seon.schema/projection projection)` into `root-pull-plan` | M |
| `:763` | `neighborhood` `:751` | fourth `or` arm | same three arms `:760-762` | drop-or | M |
| `:764` | `neighborhood` | wrap around the whole body; body uses `projection` explicitly at `:770` | `projection` `:760` | delete-wrap; thread `projection` into `root-acquisition`'s request | M |

### `src/seon/test/runner.clj` — 5 sites

| `file:line` | fn | Reads today | Value already held | Replacement | Risk |
|---|---|---|---|---|---|
| `:131` | `report-options` `:124` (0-arity) | `:seon.schema/projection (schema/handed-projection)` | nothing | the 0-arity is a cold default: take the projection as an argument from the 1-arity's `supplied` | **C** |
| `:1992` | `serve-worker-commands!` `:1975` | wrap around the recursive call | `(::projection arming)` — already passed at `:1993` | delete-wrap; `arming` is threaded through the recursion | M |
| `:2063` | `worker-command-loop!` `:2056` | wrap around `@seon.test-support/database-base` | `projection` `:2059` (`packaged-test-projection`) | **C** — `database-base` is a delay that today reads `handed-projection` (`test_support.clj:478`); it gains a projection argument |
| `:2409` | `program-digest` `:2369` | `(or (db/carried-projection database) (schema/handed-projection))` | `database` `:2403` | drop-or | M |
| `:4916` | `coordinator-main!` `:4913` | wrap around `run-coordinator!` | `projection` `:4915` | **C** — `run-coordinator!` gains a projection argument (it is a cold constructor entry: `packaged-test-projection` → `load-projection`) |

### `src/seon/test.clj` — 3 sites

| `file:line` | fn | Reads today | Value already held | Replacement | Risk |
|---|---|---|---|---|---|
| `:443-444` | `execute-admitted!` `:400` | `call-with-projection (db/carried-projection database)` | `database` `:420` | delete-wrap: `bounded-result` receives `:seon.db/connection`/`:seon.sci.eval/ctx`, both of which carry the world | M |
| `:1564` | `resolve-test` `:1515` | wrap around `sci/resolve` / `requiring-resolve` | `projection` (in scope) | delete-wrap; neither resolver reads a projection | M |
| `:2066` | `check-request` `:2052` | wrap around `config/effective` and the body | `projection` `:2065`, `database` `:2064` | delete-wrap | M |

### Single- and double-site files — 22 sites

| `file:line` | fn | Reads today | Value already held | Replacement | Risk |
|---|---|---|---|---|---|
| `src/seon/turn.clj:5265` | `turn` `:5193` | `call-with-projection-state` around `pass` | `(:seon.sci.eval/projection-state cluster)` `:5264`; `cluster` is the handle | delete-wrap; `pass` `:5242` already rebuilds `request` from `cluster` and uses `(db/db (:seon.db/connection cluster))`, which carries the state | M |
| `src/seon/bootstrap.clj:737` | `next-entry` `:731` | `(or (schema/handed-projection) (sci.kernel/context-projection (:seon.sci.eval/ctx request)))` | `request` | drop-or | M |
| `src/seon/bootstrap.clj:739` | `next-entry` | wrap around `next-entry-in` | `projection` `:736` | delete-wrap; pass `(assoc request :seon.schema/projection projection)` | M |
| `src/seon/config.clj:663` | `apply-compiled!` `:655` | second `or` arm | `database` `:661` | drop-or | M |
| `src/seon/config.clj:747` | `effective` `:739` | second `or` arm | `db` (first argument) | drop-or | M |
| `src/seon/problems.clj:386` | `problems` `:367` | `(or (db/carried-projection db) (schema/handed-projection))` guard | `db` (first argument) | drop-or | M |
| `src/seon/effect.clj:487` | `with-request-context` `:458` | `call-with-projection-state` on `(:seon.sci.eval/projection-state context)` | `context` | **O/C** — this is the flow-thread frame rebuild. Its own docstring `:479-482` says it goes when a handler takes its environment as an argument. Convert with `cluster.clj:2825` / `flow.clj:1123` as one decision |
| `src/seon/fn.clj:3318` | `index!` `:3228` | `(schema/handed-projection)` | the request already declares `:seon.schema/projection` (`cluster.clj:1459`, `:2070` pass it) | `(:seon.schema/projection request)`, refusing when absent | M |
| `src/seon/fn.clj:3328` | `index!` | `call-with-projection` **inside the `[:db.fn/call …]` writer body** `:3325-3332` | `projection` (closed over), and the body already does `(vary-meta database assoc :seon.schema/projection projection)` at `:3331` | delete-wrap — the carried stamp at `:3331` is already the correct transport. **This is the template for §4** | M |
| `src/seon/instrument.clj:548` | `supplied-projection` `:534` | `((mi/-f->original schema/handed-projection))` as the last fallback of an argument scan | the arguments of the armed call | **O** — the armed wrapper scans arguments for a projection. Deleting the fallback means a call with no projection-bearing argument validates against the wrapper's captured contract only. A1 §1 already targets this scan (11 per-call items) |
| `src/seon/instrument.clj:822` | `apply!` `:783` | `(or supplied-projection (schema/handed-projection))` | `:seon.schema/projection` request member `:799` | drop-or; the refusal at `:823-831` already names the missing member | M |
| `src/seon/reconcile.cljc:315` | `plan` `:299` | second `or` arm | `db` (first argument) | drop-or | M |
| `src/seon/reconcile.cljc:319` | `plan` | wrap around `plan-transaction-data projection db request` | `projection` `:314`, already the body's first argument | delete-wrap | M |
| `src/seon/flow.clj:1133` | `projection-executor` `:1123` | wraps every submitted `Runnable` | `projection` (argument) | **O** — same decision as `cluster.clj:2825` |
| `src/seon/cluster/registry.clj:231` | `record-fork!` `:222` | third `or` arm | `supplied-projection` (argument), `database` `:229` | drop-or; the last arm `:232` becomes `(load-projection database)` | M |
| `src/seon/cluster/registry.clj:233` | `record-fork!` | wrap around `config/compile-manifest` + `db/transact!` | `projection` `:230`, `connection` `:227` | delete-wrap; carry the projection onto the connection first | M |
| `src/seon/cluster/source.clj:311` | `record-results-at-head!` `:283` | wrap around the recording transaction | `projection` `:297`, `connection` `:295` | delete-wrap; `db/transact!` reads `carried-projection` off `connection`'s value (`db.clj:4132-4136`) | M |
| `src/seon/cluster/agent.clj:583` | `submit-source!` `:568` | `call-with-projection-state` on the handle's state | `(:seon.sci.eval/projection-state handle)` `:582` | delete-wrap; `submit-source-in-projection` `:588` receives `handle`, which carries the state. **Keep the 728 → 147 ms note `:580-581` — re-measure it** | M |
| `src/seon/schema/edn.clj:607` | `admit` `:569` | `(if-let [projection (schema/current-projection)] …)` | nothing — `admit` takes `{:seon.schema/forms …}` | **O** — `admit` is the runtime registration choke point; it gains an explicit `:seon.schema/projection` request member, and its callers (`schema.clj:3330` `activate!`) must supply it. The bootstrap branch `:611-616` stays | **O** |
| `src/seon/test/arm.clj:246` | `initialize-contracts!` `:239` | wrap around `(doseq [ns namespaces] (require ns))` | `projection` (argument `:243`) | delete-wrap — `require` reads no projection; the comment `:244-245` describes packaged *acquisition*, which happens in `packaged-test-projection` before this | M |
| `src/seon/test/fast.clj:73` | `-main` `:52` | wrap around the whole run | `(:seon.test.runner/projection arming)` `:74` | **C** — `runner/record-snapshot!` and the run entry gain a projection argument (cold-constructor entry) |
| `src/seon/sci/eval.clj:1816` | `acquire-program!` `:1794` | wrap around the acquisition body | `projection` `:1807-1809`, and `db` is re-stamped at `:1810-1812` | delete-wrap — the stamp at `:1811` is already the carried transport | M |
| `src/seon/sci/eval.clj:2266` | `base-ctx` `:2249` | wrap around ctx construction | `projection` `:2263`, put on the ctx at `:2271` | delete-wrap | M |
| `src/seon/sci/eval.clj:2935` | `evaluate` `:2812` | `call-with-projection-state` around the whole evaluation | `projection-state` `:2930-2934` | delete-wrap — `:2941-2945` already binds `db/*read-database*` to a value carrying this state, which the comment names as the reason | M |

**Totals.** 72 sites in 24 `src` files: **58 M, 9 C, 5 O**.
Per file (from `rg -n 'schema/handed-projection|schema/current-projection|schema/call-with-projection' src`):
`cluster.clj` 14, `render/web.clj` 7, `db.clj` 7, `test/runner.clj` 5,
`render/walk.clj` 5, `render.clj` 5 (one of which, `:150`, is the keyword —
4 real), `test.clj` 3, `sci/eval.clj` 3, `cluster/boot.clj` 3, `bootstrap.clj` 2,
`config.clj` 2, `fn.clj` 2, `instrument.clj` 2, `reconcile.cljc` 2,
`cluster/registry.clj` 2, and one each in `turn.clj`, `problems.clj`,
`effect.clj`, `flow.clj`, `cluster/source.clj`, `cluster/agent.clj`,
`schema/edn.clj`, `test/arm.clj`, `test/fast.clj`. 73 lines − 1 keyword = 72.

---

## 3. Boot-site one-liners

| `file:line` | The one-line change | The value it threads |
|---|---|---|
| `cluster/boot.clj:160-162` | `(or (:seon.schema/projection source-base) (schema/load-projection initial-database))` | the cold construction; `initial-database` is `@provisional-connection` `:153` |
| `cluster/boot.clj:163-164` + new | add `(db/carry-connection-projection-state! provisional-connection initial-projection-state)` immediately after `:164` | every later `(db/db provisional-connection)` carries it |
| `cluster/boot.clj:165-171` | delete the `call-with-projection-state` wrap; call `require-coherent-program!` and `accrete-schema-population!` directly | they read the connection's carried value |
| `cluster/boot.clj:173-176` | `(schema/load-projection database)` in place of `projection-from-database` | the second, post-accretion basis |
| `cluster/boot.clj:180-182` | delete the wrap; `(store/open-branch! store (:seon.store/branch forked))` then `(db/carry-connection-projection-state! connection projection-state)` | the real cluster connection |
| `cluster/boot.clj:34-36` | delete the `call-with-projection-state` wrap in `stand-cluster-runtime!`; `connection` already carries the state | the layer stand |
| `bootstrap.clj:736-741` | `(let [projection (sci.kernel/context-projection (:seon.sci.eval/ctx request))] (next-entry-in (assoc request :seon.schema/projection projection) turn-id))` | the request |
| `cluster.clj:1613-1618` (`source-base!`) | `(let [database (db/carry-projection-state (db/db connection) projection-state)] …)`, delete the wrap | the source-base db value, already a declared return member `:1606-1607` |
| `cluster.clj:2194-2195` (`refresh-source!`) | `(let [projection (schema/load-projection …)] …)` and pass it to `full-source-refresh!` / `development-source-refresh!` | the publication's cold projection |
| `test_support.clj:478-487` (the fixture base) | `database-base` takes the packaged projection as an argument instead of reading `handed-projection` | the fixture base db value, which `:731-732` already stamps |
| `test/arm.clj:242` | `packaged-test-projection` becomes the named cold constructor calling `load-projection` | the arming value returned at `:251` |

---

## 4. The decoder by argument

§7 of the plan ruled (2026-09-22, after the §6.2 comparator proof failed) that
**the codec stays**; 1.4 removes the projection *binding*, not the codec.

**Where the in-writer decode binds a projection today.**

- `db.clj:1221-1248` `read-declarations` — builds the read's declaration table.
  Its `::read-projection` is a `delay` whose second `or` arm is
  `(schema/handed-projection)` (`:1237`). Inside a Datahike transaction function
  the `db-before` value Datahike hands the fn has **no** carried metadata, so
  this arm is what the decode lands on; that is why the `call-with-projection-state`
  frames exist at all.
- `db.clj:1250-1257` `relation-only-declarations` — a `def` constant whose
  `::read-projection` is `(delay (schema/handed-projection))` with nothing else.
- `db.clj:1288-1303` `ask-declarations` — re-binds the ambient var *and* passes
  the projection to `question`.
- Consumers: `edn-encoded?` `:1305-1317`, and the decode sites `:1333, :1574,
  :1598, :1609, :1741, :1745, :2398`.
- The compiled decode itself already takes the projection explicitly:
  `write-attribute-plan` `:3408-3427` closes over
  `#(schema.datahike/decode-attribute-value-in projection attribute %)` at `:3418`.

**The one change.** The writer that invokes the transaction function already
holds the projection, and the correct pattern is already written at
`fn.clj:3325-3332`: inside `[:db.fn/call (fn [database] …)]` it does
`(vary-meta database assoc :seon.schema/projection projection)` before reading.
Make that the rule at the seam instead of at each call site — stamp the
projection onto the writer-supplied `database` once, in `transact-call`
(`db.clj:4121-4139`, which already resolves `projection` at `:4133-4138`), so
every transaction function receives a carrying value. Then:

1. `db.clj:1236-1239` → `(or (carried-projection origin) (let [failure (projection-fallback operation)] (throw (ex-info (:seon.error/message failure) failure))))`.
2. `db.clj:1250-1257` → `(defn- relation-only-declarations [projection] {::installed-schema {} ::read-projection (delay projection)})`, called from `with-declarations` `:1259`.
3. `db.clj:1301-1303` → `(question projection)`.
4. `fn.clj:3328-3332` → delete the wrap; keep the `vary-meta` stamp.

**After it, no global remains in the decode path.** `edn-encoded?`,
`decode-attribute-value-in`, `write-attribute-plan`, `write-report-error`
(`db.clj:3884-3889`) and `write-report-validator` (`:3955`) all take
`projection` as their first argument at this revision; the only readers of the
ambient var in `db.clj` are the seven rows in §2, and all seven convert there.

---

## 5. Contracts

| Function | Current Malli arity | New arity | Callers that must change |
|---|---|---|---|
| `db/relation-only-declarations` `db.clj:1250` | a `def`, no contract | `[:=> [:cat :seon.schema/projection] :map]` | `with-declarations` `db.clj:1259` |
| `schema/load-projection` (new) | — | `[:=> [:catn [:seon.schema/database-value :map]] :seon.schema/projection]` | the cold constructors in §3 |
| `schema/projection-from-database` `schema.clj:2805` | `[:function [:=> [:catn [:seon.schema/database-value :map]] ::projection] [:=> [:catn … [::projection ::projection]] ::projection]]` | A1-3: returns `(db/carried-projection db)` and refuses when absent | `cluster/boot.clj:162, :176`, `cluster.clj:1614, :2082`, `cluster/source.clj:297`, `cluster/registry.clj:232`, `sci/eval.clj:2264-2265`, `test_support.clj:176, :399, :732` |
| `seon.fn/index!` `fn.clj:3228` | request map | `:seon.schema/projection` becomes **required** | `cluster.clj:1457-1461`, `cluster.clj:2068-2073` (both already pass it) |
| `seon.schema.edn/admit` `edn.clj:569` | `{:seon.schema/forms …}` | add `[:seon.schema/projection {:optional true} …]` | `schema.clj:3330` (`activate!`), `cluster.clj:1320, :1357, :1421` |
| `runner/report-options` `test/runner.clj:124` | 0- and 1-arity | 0-arity deleted; the 1-arity supplies the projection | every `report-options` caller in `test/runner.clj` |
| `runner/run-coordinator!` | — | gains `projection` | `coordinator-main!` `test/runner.clj:4913` |
| `test-support/database-base` `test_support.clj:590` | a delay | a 1-arg acquisition taking the packaged projection | `test/runner.clj:2063-2067`, `test_support.clj:634` |
| `render/request-profile` `render.clj:118` | `[:=> [:cat :map] …]` | unchanged; only the `or` chain and the `:seon.error/expected` keyword `:150` change | none |
| `instrument/apply!` `instrument.clj:783` | `:seon.instrument/request` | `:seon.schema/projection` becomes required | `test/arm.clj`, `cluster.clj:2104` region, `registry_isolation_test.clj:82, :95` (all already pass it) |
| `schema/{identity-only-projection, candidate-shapes, matching-shapes, explain-shape}` | 1-arg (value) | deleted; the `-in` twins are the API | `test/seon/db_test.clj:1143`, `test/seon/schema_test.clj:824` |

**Tests: which question.** (README §4 row 1.4 / §6, `plan/README.md:264-281`.)

- **Question 1 — deleted machinery, delete the test.**
  `test/seon/registry_isolation_test.clj:66-74` (innermost binding wins between
  `call-with-projection` and `call-with-projection-state` — that *is* the
  transport); `test/seon/schema/projection_acquisition_test.clj:36-60` (the
  `projection-from-rows` whole-population comparison, already named for deletion
  in lane-a1 `:284`); `test/seon/schema_redeclare_test.clj:26, :44`;
  `test/seon/schema_test.clj:824` (the one-arg `identity-only-projection` throw).
- **Question 2 — retired assumption, fix the expectation.** The bulk: **509
  `handed-projection` occurrences in 86 test files**, overwhelmingly
  `(schema/handed-projection)` standing in for "the fixture's projection"
  (`ai_test.clj:79, :80, :99, :108, :125` is the shape; 79 occurrences in that
  file alone, 65 in `sci/eval_test.clj`, 38 in `instrument_test.clj`, 35 in
  `db_test.clj`, 22 in `error_test.clj`). Each becomes
  `(db/carried-projection (db/db connection))` — the fixture already carries it
  (`test_support.clj:176-179, :398-402, :933, :956, :1012`). Plus ~50
  `call-with-projection*` wraps across 30 test files, which delete.
- **Question 3 — wanted behavior of a surviving seam.**
  `test/seon/registry_isolation_test.clj:60-104` as a whole (two clusters, two
  projections, no cross-talk) survives with its assertions rewritten onto
  carried values — this is the §6 regression.

---

## 6. Proof for the lane

**The regression.** Today `registry_isolation_test.clj:66-74` asserts that the
innermost *binding* wins. After 1.4 there is no binding, so the same question is
asked of the carried value:

```clojure
;; two fixture branches, each with its own projection
(let [a (db/carried-projection (db/db left))
      b (db/carried-projection (db/db right))]
  (is (not (identical? a b)))
  ;; the projection USED is the one carried by the value PASSED
  (is (identical? a (db/carried-projection (db/db left))))
  (is (= :int    (get (:seon.schema.projection/forms a) ::left-only)))
  (is (nil?      (get (:seon.schema.projection/forms b) ::left-only)))
  ;; a value with no carriage refuses BY NAME, never falls back
  (let [bare (datahike.api/db left)]
    (is (nil? (db/carried-projection bare)))
    (is (= :seon.schema/projection
           (:seon.error/layer (problems/problems bare {}))))))
```

It fails before 1.4 (a `handed-projection` binding from the surrounding fixture
satisfies the bare-value case) and passes after (no global exists to satisfy it).
This is also A1-12's stated acceptance: *"`grep -c 'call-with-projection\|handed-projection\|current-projection' src` = 0; one regression: a raw `datahike.api/db` value refuses ordinary use by name"* (`lane-a1-projection-carried.md:230`).

**The re-measurement.** Commit 1's 1,658.341 ms acquisition
(`landing/lane-realities-commit-1-2026-09-22.md:137`, flagged at `:208-215` as
including projection reconstruction) is produced by the `measured` form at
`test/seon/sci/branch_execution_test.clj:92-93`:

```clojure
(measured #(eval/acquire! {:seon.sci.eval/ctx a-ctx :seon.db/db (db/db a)}))
```

Re-run exactly that form after 1.4 and record the new number in the landing
note. The path it exercises is `eval/acquire!` `sci/eval.clj:2296` →
`acquire-program!` `:1794`, whose `:1807-1809` `or` chain and `:1816` wrap are
rows in §2. The fixture-base figure (3,996 ms of 4,648 ms, README §5) is the
other half and is B4's measurement, not this one. A publication-path slice also
owes its clock row from
`docs/prds/steward-platform/research/measure-publication-path-2026-09-22.sh`.

---

## 7. Commit order (HEAD loads at every step)

The transport stays until the last caller converts; the deletion is the final
commit. Seven commits, 72 sites:

| # | Commit | Sites | Why here |
|---|---|---|---|
| 1 | **A1-3: `load-projection` + `projection-from-database` returns the carried value.** Add `schema/load-projection` (from `projection-from-rows` `schema.clj:2571-2736`); convert the 10 `projection-from-database` callers in §5 | 0 transport sites; 10 constructor callers | Everything below needs a carried value to read. The transport is untouched, so HEAD loads |
| 2 | **Boot and cold constructors thread the projection** (`cluster/boot.clj:34, :165, :180`; `cluster.clj:1617, :2194`; plus the non-transport threading in `test/arm.clj` and `test_support.clj`) | **5** | §3's one-liners; after this every live connection carries its state |
| 3 | **The decoder by argument** (`db.clj` §4, `fn.clj:3328`) | **8** (the 7 `db.clj` rows + `fn.clj:3328`) | Unblocks deleting `call-with-projection-state`: the writer frames existed only for this |
| 4 | **drop-or sweep** — every `(or … (schema/handed-projection) …)` arm | **14** (`bootstrap.clj:737`, `config.clj:663, :747`, `problems.clj:386`, `reconcile.cljc:315`, `render.clj:126, :150`, `render/walk.clj:383, :551, :763`, `cluster/registry.clj:231`, `instrument.clj:822`, `test/runner.clj:2409`, `cluster.clj:1319, :1356`) | Pure deletions of dead arms; each function already holds the value |
| 5 | **delete-wrap sweep, render and eval** (`render/web.clj` ×7, `render.clj` ×3, `render/walk.clj` ×2, `sci/eval.clj` ×3, `test.clj` ×3) | **18** | File-disjoint from commit 6; the bodies already bind `projection` |
| 6 | **delete-wrap sweep, cluster and runner** (`cluster.clj:315, :652, :1323, :1360, :1424-1426, :1459, :2066, :2104` = 9 sites; `cluster/registry.clj:233`, `cluster/source.clj:311`, `cluster/agent.clj:583`, `turn.clj:5265`, `test/runner.clj:131, :1992, :2063, :4916`, `test/fast.clj:73`, `test/arm.clj:246`, `reconcile.cljc:319`, `bootstrap.clj:739`, `fn.clj:3318`) | **22** | The remaining wraps |
| 7 | **A1-12: delete the transport** — the four dynamic Vars, both binders, all three readers, `shape-projection` and the four one-arg shape Vars, `activate-projection!`, the registration delta; plus the 5 **O** rows decided (`cluster.clj:2825`, `flow.clj:1133`, `effect.clj:487`, `instrument.clj:548`, `schema/edn.clj:607`) and the 86 test files converted | **5** + ≈350 deleted lines | Nothing reads them; `grep -c` = 0 is the gate |

**Three of the five O rows are one owner question** and should be put before
the owner at commit 7's start, not decided by the lane: the two
`projection-executor` reifies (`cluster.clj:2812-2827`, `flow.clj:1123-1135`)
and `effect.clj:458-488` all exist because a `Runnable` handed to an executor
carries no world, and `effect.clj:479-482` already records that they leave
together "when a handler takes its environment as an argument". Options, simplest
first: (a) the submitting caller closes over its projection and the executors
become the plain `:io` executor — smallest, and the submission site already holds
the world; (b) keep one sanctioned frame carrier that stamps the *value*, not a
binding; (c) defer all four to cut 2 with `seon.env` Phase 3.
`instrument.clj:548` and `schema/edn.clj:607` are separable and can go either way
in the same conversation.
