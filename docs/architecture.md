# Architecture: Static Flow, Malli-Derived Datahike Schemas, Co-Located Scopes, and COW Runtimes

Seon runs one static 8-proc `clojure.core.async.flow` graph over a durable multi-process Datahike LMDB store, multiplexing scoped agents and isolated `clojure.test` + Malli generative runs across a bounded pool of Copy-on-Write (`fork`) OS processes.

## 1. Malli as the Single Authority -> Automatic Datahike Derivation (`seon.schema.datahike`)

- **Zero duplicate attribute tables:** Every Datahike attribute (`:db/ident`, `:db/valueType`, `:db/cardinality`, `:db/unique`, `:db/index`, `:db/noHistory`, `:db/contentId`) is derived automatically from Malli schemas (`resources/seon/schemas/seon.*.edn` + runtime `:seon.schema/key` entities).
- **Native `:db.type/data` & `:db/contentId`:** Vectors, maps, Malli forms (`:seon.schema/form`, `:seon.code.var/schema`), and arglists (`:seon.code.var/arglists`) store natively via FASL (`:db.type/data`) with zero stringified EDN. Identity keys carry `:seon.db/content-id true` (`:db/contentId true`), so two independent branches creating the same var, schema, or task get the exact same `:db/id`.
- **Co-located multi-segment naming (`seon.<area>/*`):** Shallow `one/thing` schema names are rejected at registration. Schemas live under the namespace prefix of the code that owns them (`seon.code.*`, `seon.schema/*`, `seon.store/*`, `seon.config/*`, `seon.git/*`, `seon.merge/*`, `seon.task/*`, `seon.agent/*`, `seon.test/*`, `seon.web/*`), enabling hierarchical agent scoping (`seon.code/scope-view`) where parallel subtrees work and merge without file or entity collisions.
- **Runtime attribute installation:** Calling `(user/register-schema! :seon.billing.invoice/amount [:double {:seon.db/index true :min 0.0}])` validates its `seed = 42` generator, derives `{:db/ident :seon.billing.invoice/amount :db/valueType :db.type/double :db/index true}`, and installs it into Datahike immediately—and `seon.merge/three-way` carries new `:db/ident` declarations onto `:main` automatically on merge.

## 2. Process, Memory & GC Topology (`seon.fork`, `seon.agents`, `seon.main`)

1. **System Fork Parent (`seon.fork`):** Spawned at line 1 of `seon.main/-main` after `(jolt.host/gc-generations! 6)` and `(System/gc)` promotion (`ceiling = 5`). Forked children inherit `collect-ceiling = 4`, leaving inherited `:main` pages untouched across child GC cycles.
2. **Static 8-Proc Flow Graph:** `:server`, `:transactor`, `:reconciler`, `:agents`, `:model`, `:test`, `:git`, `:web` on 3 OS carrier threads, with zero `flow/update!` calls.
3. **Persistent Multi-Turn & Multi-Tool Agent Runtimes:** Active agents keep their forked OS process alive across turns (`0` re-forks between turns) and execute multi-tool `eval` batches (`seon.code/eval-tool-calls`), continuing past individual tool errors. Idle processes are LRU-evicted when `max-os-procs` is needed and released to `0` OS PIDs once `:merged`.

## 3. Normal Callable Clojure Forms in Web Hiccup (`seon.web`)

- **No `:ui/op` switch tables:** Hiccup interactive elements carry normal Clojure call forms (`{:seon/call '(seon.web/select-var! seon.store seon.store/open!)}`). `POST /call` resolves the symbol, enforces `^:seon/callable`, invokes the function directly, and streams `datastar-patch-elements` over SSE (`resources/public/datastar.js`).

## 4. The Structural Anti-Bloat Rails

1. **One schema authority:** Malli defines storage, write-seam validation, `seed = 42` generative checks (`100` samples on reach change), `50 ns` function instrumentation, and generic attribute-level 3-way merge.
2. **The `O(Δ)` Law:** Runtime transitions (`load-delta!`, `three-way`, `update-reach-graph`, `reconciler`) read `d/changes`, enforced by `seon.core-test/scale-invariance-rail-test` (`N = 100` vs `N = 1,000` `< 1.6x` ticks).
3. **Tick-bounded evals (`jolt.host/run-interruptible`):** Un-swallowable tick & byte ceilings stop infinite loops cleanly as tool errors.
4. **Cold-boot reach & `--take-over PORT`:** Static reach flags `:boot-affected?` changes without manual labels, verified via `--self-test` and swapped live via `--take-over`.
