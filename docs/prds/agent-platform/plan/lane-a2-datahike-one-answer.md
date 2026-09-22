---
type: plan
status: implementation specification; acceptance proofs pending
created: 2026-09-21
tags: [agent-platform, lane-a2, datahike, konserve, seon.db, read-currency, db.type/any, pull, validator]
---

# Lane A2 — one answer per question at the Datahike / konserve seam

Owned (`wc -l` at HEAD `209a6652a`, 2026-09-21): `src/seon/db.clj` 4,638 ·
`src/seon/schema/datahike.clj` 555 · `src/seon/cluster/store.clj` 577 ·
`src/seon/cluster/registry.clj` 667 · `src/seon/blob.clj` 430 ·
`resources/seon/schemas/seon.db.edn` 329 — **7,196 lines**; plus the forks
`reference-code/datahike` (gitlink `006e634a`) and `reference-code/konserve`
(`07377c27`), both equal to their recorded `origin/main`. Seam with A1:
`seon.db/carried-projection` (`src/seon/db.clj:1219-1225`). `DH/` below means
`reference-code/datahike/src/datahike/`, `K/` means `reference-code/konserve/src/konserve/`.
Historical read-only `default` evaluations dated 2026-09-21 supply §4 probes A–C. They are baseline observations, not current runtime or implementation proof. No execution is part of this specification revision.

Evidence: [A2 data pack](../research/data-pack-a2-datahike-2026-09-21.md) and [durable rulings](../research/durable-goals-and-rulings-2026-09-21.md) supply the dated counts and ruling citations below. Required behavior and proof gates are stated here.

## 0. For the owner: what was dumb, and the simpler way

**What the code does today.** Before each agent turn, for every read the
agent's context retains, `read-evidence-current?` (`db.clj:1102-1144`) asks
"is this still current?" three ways in order: walk historical datoms against
the read's index patterns (`index-evidence-current`, `:1084`), compare a
revision map copied from Datahike's cache context (`dependency-revision`,
`:875-903`), and, failing both, RE-RUN the read and compare a digest of its
result (`replay-read`, `:971-1052`). Then the system turn re-runs every read
not proven current and compares shown text anyway (`turn.clj:2173-2175`).
In the recorded `default` sample, 435 reads are retained; 413 carry the plan `:all` and are
therefore never provable current by any of the three arms; 407 of those are
ONE pull, `[* :seon.ns/requires {:seon.ns/aliases [*]} …]`, issued once per
namespace by SCI program acquisition (`src/seon/sci/eval.clj:1846-1852`) and
retained by two evaluations (§4 probe C). The currency machinery is 700 lines
answering a question that one selector made unanswerable.

Values of union type are printed to EDN strings at write and, on every read,
decoded and re-printed to check the write was canonical
(`schema/datahike.clj:543-545`) — the writer's own work redone per read, with
260 lines of walkers carrying a "declaration population" so they know which
attributes to decode. Every pull rewrites its selector to escape Datahike's
silent 1,000-member cut (`total-pull-selector`, `db.clj:2080-2149`) — a
constant in our own fork. And a program write that touches any identity
attribute runs `arity-mismatches-with` over every `:seon.fn/call-arities`
datom (65,764 on `default`) and parses every `:seon.schema/form` (3,337) to
find render targets (`db.clj:3833`, `:3904-3920`, invoked at `:4090-4103`).

**Why that was the wrong shape.** Each is a mirror beside an authority that
already holds the fact. Datahike's writer stamps, per commit, exactly which
attributes changed (`DH/writer.cljc:242-246` → `DH/query.cljc:2568-2590`), so
"current?" is one map comparison for a read that names its attributes — and a
read that names none (`:all`) is not "hard to check", it is a read whose
selector must be fixed at its site. Datahike validates every value at write
(`DH/db/transaction.cljc:45-52`) and already has a value type for arbitrary
data (`DH/schema.cljc:87`). The transaction report already lists the datoms
that changed and the writer's own before/after walk (`db.clj:4053-4103`)
already knows the affected roots; the two whole-program queries ignore that
and start over.

**The simpler way, as data flow.** A read retains the attribute plan Datahike
attaches to its result and the revisions of those attributes on the database
it read; before the next turn that evidence is compared with the same
projection of the current database — work proportional to the attributes the
retained reads name, never to the database or its history. A wildcard read is
reported as "changed" and re-evaluated once by the system turn, and the one
site that produced 407 of them is scoped to the twelve attributes it consumes
(§2 a′). Values are stored as values. A pull returns everything, or refuses
under the query-work bound our fork already charges. The validator starts from
the roots the report walk already found and follows indexed edges outward —
proportional to the changed declarations and their callers.

## 1. Goal and the numbers that prove it

| measure | before (measured on `default`, 2026-09-21) | after (target) | source |
|---|---|---|---|
| retained read-evidence entities | **435**: 413 `:all` (407 wildcard pulls of one selector, 6 without a retained request), 22 attribute-scoped queries | same population; **0 `:all`** from `sci/eval.clj` after §2 a′; every remaining `:all` names its site | probes A, C |
| distinct evaluations retaining them | **2** | the acquisition reads leave the evaluation's evidence (B2 decision, §2 a′) | probe C |
| 435 revision comparisons (`dependency-revision` equality) | **0.711 ms** | unchanged — this is already the cost of one mechanism; the deletion removes the other two arms and the per-turn replay | probe B |
| turn refresh: forms re-evaluated after an unrelated commit | not measured; a short-circuit comparison cannot measure complete refresh | measured at the implementation baseline as §4 D: selected forms, actual evaluations, total system-turn ms | — |
| EDN-encoded attributes | **23** of 1,191; none indexed/unique/identity; 7 hold datoms | 0 (blocked on §6.2) | pack A2 §4 |
| validator work per program write | Datalog over 65,764 `:seon.fn/call-arities` + `edn/read-string` of 3,337 forms | selected declarations + `avet` seeks on `:seon.fn/calls` for affected callees; a docstring edit selects **0** | pack A2 §1a; probe B (`calls-index` `:db/index true`) |
| owned lines | 7,196 | **≤ 5,600** by §8's itemized cuts; 5,000 is a stretch funded only by §6 | `wc -l` |

Investigate any operation above ~2 s. Known costs to distinguish are: a whole-store copy
(`fork-database`, `DH/versioning.cljc:550`, in the fixture — B4's file, A2
supplies the branch guarantee in §2 j), a whole-program query (validator), or
program indexing inside a fixture (§7 time escapes).

## 2. The data flow, per mechanism

| mechanism | data | computed when | carried where | recomputed on | work ∝ |
|---|---|---|---|---|---|
| **(a) read currency** | per read: the plan Datahike attaches (`DH/pull_api.cljc:162-186`, `DH/query.cljc:2877-2904`) and `dependency-revision` (`db.clj:875-903`): `{:datahike.read/attributes, :datahike.cache/attribute-revisions, conservative-revision, connection-id, generation}` or `{:datahike.read/cache-eligible? false}` | at evaluation | the evaluation's `:seon.cluster.eval/read-evidence` component (`resources/seon/schemas/seon.cluster.eval.edn:4-5`) as native values (after c2) | never; immutable evidence | attributes named |
| — the comparison | current ⇔ eligible AND `(= retained (dependency-revision db plan pos))` on the supplied value (same connection id and generation, as-of through `revision-source` `:852-873`); an error value is never true; `:all` is never current | per system turn | nowhere | — | retained reads × attributes |
| — the current context | `:cache-context` advanced per commit for changed attributes only (`DH/query.cljc:2568-2590`); schema-touching or unknown batches bump `conservative-revision` (`:2582-2584`); **`:db/txInstant` is dropped at `:2575`** — a read naming only it compares equal across commits (fork f7) | in the writer (`DH/writer.cljc:242-246`) | the connection's value | every commit | attributes changed |
| — deleted | `index-evidence-current` + walkers (`:596-763`, re-executes the query at `:640`; `:1038-1101`), `replay-read` + `stable-value`/`read-result-digest` (`:530-595`, `:971-1052`), the `:seon.db/read-result`/`read-result-digest` arms of `read-evidence` (`:904-957`); `:seon.db/captured-read` NARROWED (still named at `:912-914` and `render.clj:1047-1049`, `:1558`) | | | | |
| **(a′) the 407** | ONE selector at `src/seon/sci/eval.clj:1846-1852` (`acquire-program!`, per namespace) and `:857-864` (`install-row!`): `[* :seon.ns/requires {:seon.ns/aliases [*]} {:seon.ns/imports [*]} {:seon.ns/refers [*]}]`. A top-level `*` plans `:all` (`DH/pull_api.cljc:120`); nested `[*]` and automatic component expansion also plan `:all` (`:127-138`) | | | | |
| — scoped pull (B2's file; A2 supplies the form and the re-census) | `[:seon.ns/name :seon.ns/source :seon.schema.admission/source :seon.ns/requires {:seon.ns/aliases [:seon.ns.alias/local :seon.ns.alias/target-ns]} {:seon.ns/imports [:seon.ns.import/local :seon.ns.import/target-class]} {:seon.ns/refers [:seon.ns.refer/local :seon.ns.refer/target-ns :seon.ns.refer/target-name]}]` — the twelve attributes the consumer reads (`seon.ns.edn:1-33`, `seon.ns.{alias,import,refer}.edn:8-11`); plan = those twelve; an attribute absent today and added later is still covered because it is NAMED | | | | |
| — the deeper question (B2) | these are program-acquisition reads. B2 admits publication, agent writes, deletions and namespace resolver changes at execution boundaries, including retained idle contexts; an acquired generation carries the matching program value. Whether acquisition belongs under evaluation read capture is B2's decision; A2 supplies the census and scoped selector | | | | |
| — other wildcard sites (none retained on `default` in that sample; each plans `:all` when captured) | `error.clj:1579,2106` (B3); `fn.clj:3057,3176`, `cluster.clj:2095`, `reconcile.cljc:381` (B1); `problems.clj:113-181`, `ai.clj:125-130`, `render.clj:1398-1399` (the walk's `'[*]`), `turn.clj:328,360,831,1183,1267,1285,1463-1465` (B2). Listed for their owners; a wildcard is right where the consumer needs the whole entity (the walk) and wrong where it reads three keys | | | | |
| **(b) values** | the value | validated at write against `:db.type/any` = `any?` (`DH/schema.cljc:87`; `DH/db/transaction.cljc:45-52`); **Malli validation stays in the final-report hook** — `any?` proves nothing about shape | the datom | — | 0 on read |
| — blocked until §6.2 proves | EAVT/AEVT quick comparators call `compare-value` on `v` (`DH/datom.cljc:325-360`); on two maps it throws `ClassCastException` (probe B `:map-compare`); `cmp-nil`/`safe-compare` (`:294-304`) then compares class names, equating distinct maps; `persistent_set` `insert`/`upsert` use the quick comparators (`DH/index/persistent_set.cljc:133-150`); history keeps several values per (e,a) (`DH/db/transaction.cljc:478-481`) | | | | |
| — deleted after the proof | bridge union→string fallback (`schema/datahike.clj:118-124`), `edn-encoded-attr-in?` (`:283-289`), codec walkers (`:370-410`, `:505-555`); `db.clj` `edn-encoded?`/decode/encode (`:1312-1356`, `:1611`, `:1646-1725`, `:1736-1779`), declaration population (`:1226-1310`, ten `with-declarations` sites). KEPT: `explicit-value-members` (`schema/datahike.clj:411-450`, two-keyword cardinality-many grammar), `datom->data` as eager map builder (`db.clj:2542-2555`), wake's ref resolution (`wake.clj:422-427`, B2: codec calls become identity, `entid` stays) | | | | |
| **(c) pull** | complete collections | — | — | — | fork f2 makes the default limit nil; an explicit `:limit` stays a partial request; the budget of `1e78cb9c` charges only when Seon binds it (`DH/resource.cljc:22-31`, `:64-69`), so `seon.db/pull` keeps passing its existing budget; a refusal keeps `:datahike.budget/name`/`observed`/`allowed` (`:13-20`) and is reported as an elision naming the bound — never a fabricated count |
| — deleted | `total-pull-selector` family (`db.clj:2080-2149`) | | | | |
| **(d) validator** | the report's attempted + effective datoms and its before/after roots — already derived by `write-report-error` (`db.clj:4053-4103`: `affected`, `changed-identity-attributes`) | inside the final-report hook (`DH/db/transaction.cljc:1206-1216`, invoked at `:1276`) | the report | every commit | affected roots + their callers |
| — arity (replaces `:3833` whole-program Datalog) | select changed caller/test roots as well as affected function roots (changed `:seon.fn/sym`, arity component, shared shape, call-preparation key — the list at `:4100-4102`), resolving owners before AND after and including idempotent attempted assertions. Read final call tuples for changed callers; for each affected callee, select surviving callers by `(d/datoms db-after :avet :seon.fn/calls callee)` (`:seon.fn/calls` is `:db/index true`, `seon.fn.edn:33`, probe B); check each caller's final call tuples against final bounds. Missing final targets enter strict deletion refusal; simultaneous caller repairs are checked in the final database. Supplied-default bounds keep the existing `call-preparation-snapshot` acquisition until A1/B1 land a replacement (§2(d)’s supplied-default proof gate) | | | | changed roots + returned caller edges |
| — render targets (replaces `:3904-3920`) | a form ADDED/replaced in this tx: read its own row's `:seon.render/ai`/`html` property datoms (materialized by `storable-properties-in`, `schema.clj:3526`; 334 datoms on `default`) and resolve each symbol on `db-after`. A `:seon.fn/sym` RETRACTED in this tx: the whole-form scan survives for that case only, until the existing declared symbol-reference relation is proven complete for renderers or A1/B2 agree a declaration correction. The property is currently heterogeneous (`[:or :qualified-symbol :string]`, `seon.render.edn:19`); native-any cannot supply an indexed reverse lookup. Do not silently narrow a renderer-value contract to obtain an index. No new `:seon.schema/render-*` attribute: it would mirror a stored fact | | | | forms in this tx (+ one scan per symbol retraction) |
| — kept whole | the owning-value walk, required/optional deletion refusal, component validation (`:4053-4103`, `:3071`); Datahike supplies swept refs and cascades (`DH/db/transaction.cljc:998-1015`) | | | | |
| **(e) diff** | deleted: `external-sink-reach-rules` … the multi-arity `diff` (`db.clj:2797-3134`, `:3175-3223`); zero production callers (pack §3). Kept: `value-changes`, `apply-diff`, the map arity `turn.clj:2224` calls | | | | |
| **(f) pulled form** | deleted: per-entity schema GUESSING (`pulled-entity-schema-key`, `:2150`, a `d/datoms :eavt` scan per pulled entity). Kept until A1's boundary supplies it: the selector-derived output check for a caller-NAMED `:schema-key` (`:2284-2327`) — stored-entity validation does not cover `:as`, `:default`, expanded refs | | | | |
| **(g) liveness** | `store.clj:564` asks `contains?` on `*connections*`, true through a whole release drain (`DH/connections.cljc:123-127`) and during `:opening?`; Datahike's `connect` already shares, waits or refuses atomically (`reserve-connection-opening!`, `:37-92`) | | | | c12 deletes the pre-check after §6.3 |
| **(h) branch head** | `registry.clj:134-152` already reads `[:meta :datahike/commit-id]` — the library's own spelling (`DH/gc.cljc:24`). No fork export. `commit-present?` (`:150-152`) is a pre-read where `commit-as-db` (`DH/versioning.cljc:469`) already decides absence; delete where it precedes that call | | | | |
| **(i) reopen configuration** | Datahike adopts `store-fixed-record-keys` when the caller omits them and refuses an explicit conflict typed (`DH/connector.cljc:190-237`); `:keep-history?` is NOT in that set (`:195`), which is why `store.clj:368-373` opens a SECOND konserve store on every open to pre-read it | | | | fork f8 adds `:keep-history?` to the set; c12 supplies creation keys at creation only and omits them on reopen |
| **(i′) development snapshot retention — proposed 2026-09-22, lane a2-storage-retention** | `:seon.config.db/snapshot-window-ms` is an optional nonnegative millisecond config fact; development overlay proposes **0**, ordinary defaults leave it absent. Every extant cluster config row must declare its window before implicit collection; absence refuses. Current roster heads always survive. The cutoff is newest captured head timestamp minus the largest declared window; captured heads supply both timestamps and config facts | when `collect!` is called without an explicit cutoff | immutable cutoff passed to Datahike's existing GC; never a remembered census | each explicit collection request; no scheduler or adoption hook | roster heads + config rows to derive policy; dependency mark then scales with retained commit/index nodes. A later write cannot make this captured cutoff newer; Datahike independently marks current heads and fences sweeping. Temporal datoms in a retained head survive; no-history is a separate schema decision |
| **(j) fixture (B4's file; A2 states the guarantee)** | `d/branch!` (`DH/versioning.cljc:212-252`): under the roster permit, reads the source head, refuses a duplicate name (`:246-250`), writes a new head pointer and the roster, CoW-branches secondary indices. It ISOLATES: datoms, schema state, the branch's writes, its connection. It does NOT isolate: konserve blobs (`bassoc` keys are store-scoped), the `:branches` roster, GC reachability, the OS `flock`, JVM globals (`*connections*`, SCI Vars, loaded classes). A test that needs an independent store needs a store, not a branch | | | | |
| **(k) load cycle** | eight `defonce`+`delay` holders (`db.clj:52-68`): five `seon.error/*`, three `seon.call-preparation/*`. `seon.error` requires `seon.db` (B3 §2(d)’s supplied-default proof gate), so `db.clj` cannot require it back; the five leave only with B3's render/explain conversion; the three stay until (d)'s supplied-default replacement exists | | | | deferred; not counted in §8 |

Consumers outside A2 whose signature stays: `read-evidence-current?` ←
`render.clj` (3), `render/walk.clj` (1), `render/web.clj` (4), `turn.clj` (2)
(pack §3). `render.clj:701 program-evidence-current?` should call it (B2).
`transaction-result` (`db.clj:4547`; 9 production callers) stays, its
per-datom `d/datoms :eavt` becomes one seek per distinct `:e` (c8).

## 3. Reading list (each line = what the block guarantees; read before editing)

| block | guarantee |
|---|---|
| `DH/query.cljc:2568-2590` `advance-query-cache-context` | per commit, only changed attributes get the commit id; schema-touching/unknown batches bump `conservative-revision`; `:db/txInstant` is removed at `:2575` (f7) |
| `DH/query.cljc:2951-2998` `compatible-source-keys?`, `source-context-unchanged?` | the fork's own predicate: two contexts, conservative equal AND every named attribute equal; `:all` never unchanged; source-key compatibility is a separate check; this predicate alone is not the complete currency decision |
| `DH/query.cljc:2877-2904`; `DH/pull_api.cljc:107-141`, `:162-186`, `:564-585` | attribute-granularity plans without execution; wildcard, non-keyword attribute, automatic component expansion, or any throw ⇒ `:all` |
| `DH/writer.cljc:225-250`; `DH/writing.cljc:597-602` | where the context advances, from the batch's union of changed attributes |
| `DH/connector.cljc:144-181`, `:190-237`, `:349-368` | stored-config equality refuses typed with `:diff`; store-fixed keys adopted when omitted, refused when explicitly conflicting; context birth at connect |
| `DH/schema.cljc:35-55`, `:87`, `:105`, `:228-234`; `DH/db/transaction.cljc:45-52`, `:786-795` | `:db.type/any` exists and is used internally, absent from `:db.type/value`; values validated with `s/valid?` at write; `validate-val`'s return is ignored (a coercion cannot live there) |
| `DH/datom.cljc:262-304`, `:325-360`; `DH/index/persistent_set.cljc:130-150` | `compare-value` has no total order for maps; quick comparators compare `v` in EAVT/AEVT; `insert`/`upsert` use them |
| `DH/pull_api.cljc:16`, `:315-323`; `DH/resource.cljc:13-31`, `:64-76` | `+default-limit+ 1000`; nil limit = complete; charges only under a bound budget; refusal carries name/observed/allowed |
| `DH/db/transaction.cljc:998-1015`, `:1206-1216`, `:1270-1276` | `retractEntity` sweeps incoming refs and cascades components; the final-report validator sees attempted + effective datoms; nil accepts |
| `DH/versioning.cljc:69-99`, `:182-189`, `:212-252`, `:279-320`, `:323`, `:469`, `:550-585` | attached cache context; roster; `branch!` guarantees (§2 j); `delete-branch!` refuses main and active/opening connections typed; `force-branch!` has NO active check; `fork-database` copies every key and can tear under concurrent writes |
| `DH/connections.cljc:5-9`, `:37-92`, `:123-127` | count-aware liveness; atomic acquire/share/wait; map entry removed at the END of the drain |
| `DH/gc.cljc:22-30`, `:120-168`; `K/gc.cljc:8-48`; `K/core.cljc:690-696`; `K/filestore.clj:287-289`; `K/impl/defaults.cljc:372-415`, `:634-647` | head read; permit → cutoff → reachable → `sweep!` with `sweep-opts` passed through to konserve; konserve selects `to-delete` from `k/keys` metadata (`:key`, `:last-write`; no size) then deletes; `FileStore/-keys` returns names, `list-keys` builds metadata and skips unreadable files |
| first-party idioms | `cluster/source.clj` (branch mutation through `datahike.versioning`); `blob.clj:125-147` streaming digest verification inside `bget`'s callback, `:179-183` staging digest; `store.clj:306-356` `acquire-flock!` (no library counterpart) |
| consumers | `turn.clj:2008-2044`, `:2138-2175` (status from `read-evidence-current?`; re-evaluation and shown-text comparison); `sci/eval.clj:1771-1875` `acquire-program!` (the 407) |

## 4. REPL protocol (`eval_clj`, `mode jvm`, `read_only true`, root `/Users/sean/src/seon`, cluster `default`)

Custody is explicit: `(seon.operator/connection "default")`. Writes never run
read-only on `default`; they run on a fixture branch through the canonical fixture in the hosting JVM. Rows D and G are fixture scenarios: commit the complete bound request and counts with the regression before claiming a measured result. Historical C decodes the old EDN storage; after c2 it reads the admitted native value directly, with no compatibility codec.

| id | question | form | before (measured) | after |
|---|---|---|---|---|
| A | census | `(let [db (seon.db/db (seon.operator/connection "default")) ids (datahike.api/q '[:find [?e ...] :where [?e :datahike.read/revision]] db) rows (mapv #(seon.db/pull db '[:datahike.read/revision :datahike.read/dependency-plan :seon.db/read-request :seon.db/source-argument-position] %) ids)] {:count (count ids) :all (count (filter #(= :all (get-in % [:datahike.read/revision :datahike.read/attributes])) rows)) :groups (frequencies (map (fn [r] [(get-in r [:seon.db/read-request :seon.db/read-operation] :absent) (= :all (get-in r [:datahike.read/revision :datahike.read/attributes]))]) rows))})` | 435; `:all` 413; groups `[:pull true] 407`, `[:q false] 22`, `[:absent true] 6` (2026-09-21 sample, 119 ms) | `[:pull true]` 0 for new acquisition observations after a′; old evidence remains until the declared reset |
| B | comparison cost | `(let [db (seon.db/db (seon.operator/connection "default")) rows (mapv #(seon.db/pull db '[:datahike.read/revision :datahike.read/dependency-plan :seon.db/source-argument-position] %) (datahike.api/q '[:find [?e ...] :where [?e :datahike.read/revision]] db)) started (System/nanoTime) equal (count (filter true? (mapv (fn [r] (= (:datahike.read/revision r) (#'seon.db/dependency-revision db (:datahike.read/dependency-plan r) (:seon.db/source-argument-position r)))) rows)))] {:count (count rows) :revision-equal equal :ms (/ (- (System/nanoTime) started) 1e6) :calls-index (get (datahike.db.interface/-schema db) :seon.fn/calls)})` | 435; equal 22; **0.711 ms**; `:seon.fn/calls` `{:db/index true …}` (2026-09-21 sample) | ≤ 1 ms with the eligibility check |
| C | who issued the `:all` reads | `(let [db (seon.db/db (seon.operator/connection "default")) rows (datahike.api/q '[:find ?ev ?src ?req :where [?ev :seon.cluster.eval/read-evidence ?r] [?r :seon.db/read-request ?req] [?ev :seon.cluster.eval/source ?src]] db) parsed (mapv (fn [[ev src req]] (let [r (clojure.edn/read-string req) [sel ref] (:seon.db/pull-arguments r)] {:op (:seon.db/read-operation r) :sel (when (vector? sel) (mapv #(if (map? %) (into {} (map (fn [[k v]] [k (if (vector? v) (count v) v)])) %) %) sel)) :lookup (when (vector? ref) (first ref))})) rows)] {:count (count rows) :by-selector (frequencies (map (juxt :op :sel :lookup) parsed)) :distinct-evals (count (distinct (map first rows)))})` | 429 with a request; `[:pull [* [:seon.ns/requires :limit nil] {[:seon.ns/aliases :limit nil] 1} {[:seon.ns/imports :limit nil] 1} {[:seon.ns/refers :limit nil] 1}] :seon.ns/name] 407`; `[:q nil nil] 22`; **2 distinct evaluations** (2026-09-21 sample, 6 ms) | the selector shape absent from new acquisition evidence |
| D | refresh cost | on a fixture branch: one unrelated commit, then `(time (seon.turn/system-turn …))` recording forms selected, evaluations run, total ms; then a commit to a named attribute of one retained read | not measured | selected = reads whose attributes changed; the unrelated commit selects only `:all` reads |
| E | value type | `(let [db (seon.db/db (seon.operator/connection "default"))] (get (datahike.db.interface/-schema db) :seon.render/ai))` | `:db.type/string` | `:db.type/any` (after §6.2 and c2) |
| F | pull completeness | `(let [db (seon.db/db (seon.operator/connection "default")) eid (seon.db/q '[:find ?e . :where [?e :seon.fn/sym seon.turn/step]] db)] (if eid (= (count (:seon.fn/calls (seon.db/pull db [:seon.fn/calls] eid))) (count (datahike.api/datoms db :eavt eid :seon.fn/calls))) {:unavailable :missing-subject}))` | true only because `total-pull-selector` rewrote the selector | true with the raw selector (f2) |
| G | validator selection | fixture branch: a docstring edit through `seon.db/transact!` while counting `d/datoms :avet :seon.fn/calls` seeks and declarations selected (a fixture-bound counting var, not wall time) | whole-program Datalog + 3,337 form parses | 0 selected for a docstring edit; N callers × changed callees for an arity change |
| H | liveness after each commit | `mcp__seon__runtime_status`; `bin/seon status` | — | alive; plus the probe named per commit in §5 |

Recovery when a commit breaks `default` anyway: `bin/seon reset --force`.
What it loses: every stored turn, evaluation, error, task and history fact on
that store and every in-memory private object and result — data the ruling
calls disposable, not "nothing durable". A lane never resets `default`; it
records RESET NEEDED and continues on its scratch root.

## 5. The work, ordered as commits

Every commit: `git commit --only -- <owned paths>`; then
`clojure -M -e "(require 'seon.db 'seon.schema.datahike 'seon.cluster.store 'seon.cluster.registry 'seon.blob)"`;
`require :reload` of the touched namespaces on `default`; the named probe. A
retirement and every caller, contract, schema and test conversion are ONE
commit. Fork commits are pushed to `origin/main` and the gitlink bumped in the
Seon commit that first depends on them. RESET items land in the orchestrator's
one batched reset; the old code keeps running on `default` until then.

| # | repo | change | unblocks | RESET | probe after |
|---|---|---|---|---|---|
| f7 | datahike | `DH/query.cljc:2575`: keep `:db/txInstant` in `user-attrs` so its revision advances on every commit (one `disj` removed); a fork test: a read planning `#{:db/txInstant}` is not current after any commit | c1 | — | fork tests |
| f2 | datahike | `DH/pull_api.cljc:16` `+default-limit+` → `nil`; test: a 1,001-member attribute pulls complete by default; an explicit `:limit 5` still returns 5; the budget of `1e78cb9c` still refuses typed when bound | c4 | — | fork tests |
| f8 | datahike | `DH/connector.cljc:195` `store-fixed-record-keys` gains `:keep-history?`; test: omitted → adopted from the store; explicit equal → connects; explicit conflict → `:create-time-fixed-index-config-mismatch` with `:conflicts` | c12 | — | fork tests |
| f4 | konserve | `K/gc.cljc:20-48` `sweep!`: option `:konserve.gc/dry-run?` returns the selected `to-delete` keys without deleting (the same predicate, same batch), reached through Datahike's `:datahike.gc/sweep-opts` (`DH/gc.cljc:163-167`) — no second candidate algorithm, no second scan | c10 | — | fork tests |
| f1 | datahike | ONLY after §6.2 passes: `DH/schema.cljc:35-55` adds `:db.type/any` to `:db.type/value`; schema install refuses it with `:db/index`, `:db/unique` or `:db.cardinality/many`; the comparator correction §6.2 names, with its cost | c2 | — | fork tests incl. history/as-of |
| c1 | seon | **currency = one mechanism.** `read-evidence-current?` = `every?` of (eligible ∧ same connection/generation ∧ revision equality) over the supplied value; keep `revision-source` (`:852-873`); delete the index-pattern and replay arms and their helpers (`:530-595`, `:596-763`, `:971-1052`, `:1038-1101`), the `read-result`/`read-result-digest` arms of `read-evidence` (`:904-957`); NARROW `:seon.db/captured-read` (`seon.db.edn:139-155`) and delete `read-index-pattern*`, `read-basis-t`, `read-result*` keys (`:60-83`, `:109-111`); `turn.clj:2030` `:seon.turn/changes` (B2, consumer `turn_test.clj:688` only) proposed in the note, never edited while held | — | **RESET** (evidence attributes removed) | probes A, B; a turn on `root` renders |
| a′ | seon | the scoped selector at `sci/eval.clj:1846-1852` and `:857-864` — B2's file: A2 hands the twelve-attribute selector and probe C; lands in B2's slice | probe A's after-column | — | probe C: shape absent |
| c3 | seon | delete the multi-arity `diff` family (`:2797-3134`, `:3175-3223`), keep `value-changes`/`apply-diff`/the map arity; prune `seon.db.edn:328-329` | — | — | `turn.clj:2224` path: a system turn with one changed read |
| c4 | seon | delete `total-pull-selector` family (`:2080-2149`); a budget refusal surfaces as `:seon.print/elision` naming `:datahike.budget/name`, `observed`, `allowed` — no partial contents, no invented count | — | — | probe F |
| c5 | seon | delete `pulled-entity-schema-key` and the guessing path (`:2150-2283`); keep the caller-named `:schema-key` check (`:2284-2327`) with its contract | — | — | `seon.db/pull` on an agent row |
| c6 | seon | **validator narrowed** (§2 d): arity from affected roots + `avet :seon.fn/calls` seeks; render targets from the added forms' own property datoms; the whole-form scan survives only for a `:seon.fn/sym` retraction; `seon.fn/arity-mismatches` re-export (`fn.clj:1799-1805`, B1) proposed in the note | kills `error_write_timing_test` | — | probe G |
| c12 | seon | `store.clj`: creation keys supplied at creation only; reopen omits `:keep-history?` and the other store-fixed keys (f8 adopts them); delete `stored-main-keep-history?` + `::keep-history-mismatch` (`:368-373`, `:466-483`) — the library's typed refusal carries `:conflicts`; delete the `contains?` pre-check (`:559-570`) after §6.3; `file-lock-generator`/`fresh-file-lock` (`:98-124`) leave WITH `seon.store.edn:17`'s `:gen/gen` and `public_contract_test.clj:95,156-159` | — | — | `bin/seon status`; §6.3 drain probe |
| c9 | seon | `registry.clj`: delete `::cannot-retire-main`/`::cluster-connected` pre-reads (`:343-351`; `delete-branch!` refuses both typed, `DH/versioning.cljc:286-288`, `:310-315`) and translate its result once; KEEP the exclusivity check before `force-branch!`; delete `commit-present?` where `commit-as-db` decides; fix the drifted docstring citations (pack §5 rows 6, 12) | — | — | scratch-root branch retire |
| c10 | seon | `dry-run!`/`dry-run-complete?`/token (`:510-573`) → `:datahike.gc/sweep-opts {:konserve.gc/dry-run? true}`; delete `physical-filestore-inventory` (`:446-509`): the report carries konserve's logical key count and the dry-run's candidates; physical bytes stay the operator's directory size (`bin/seon status --verbose`) | — | — | `collect!` dry run on a scratch root: zero candidates, then one |
| c11 | seon | `blob.clj`: one stream-unwrapping helper used INSIDE `bget`'s callback by both `stored-digest-and-size` (`:125-147`) and `read-octets` (`:224-229`); streaming verification and range reads unchanged; no `store-base` accessor | — | — | blob round-trip incl. a digest mismatch |
| c8 | seon | after B3's constructor lands: the 56 inline `(and (map? x) (inst? (:seon.error/at x)) …)` checks → the callee's declared error union (D12: no predicate); `transaction-result` one seek per distinct `:e`; `*receipt*` (`:157`, `:3342-3350`) renamed WITH `sci/eval.clj:2908` (B2) in one commit; the `datoms` output contract made one shape after the §6.5 probe | — | — | armed `(seon.db/datoms db :eavt eid)`; a refused write renders |
| c2 | seon | after f1: bridge mixed-type `:or` → `{::value-type :db.type/any}`; delete the codec and population plumbing (§2 b); `wake.clj:422-427` codec calls → identity WITH ref resolution kept (B2, same commit or STOP) | — | **RESET** (23 attributes change type; evidence shapes) | probe E; a page renders a `:seon.render/ai` value |
| c7 | seon | after §6.4: delete `jdk-integers->long` (`:3235-3258`) if Integers never reach the writer, or move the coercion to where Seon builds the admitted value | — | — | transact `(int 1)` and reread after reconnect |
| c13 | seon | **the read seams stop re-deciding Datahike's parser** (deep review win 7): `query-call-valid?`/`query-guard-message` (`:1960-1993`), `query-input-shape-error`/`query-input-position`/`aligned-query-arguments`/`missing-query-error`/`malformed-query-pattern-error`/`query-attribute-error`/`query-variable-attributes`/`query-find-attributes` (`:1865-1993`, `:1493-1610`), `lookup-ref-error`/`unknown-attribute-error`/`attribute-observation`/`registered-attribute-candidates` (`:1386-1466`), `missing-pull-selector-error` `:2055`, `pull-call-valid?` `:2400-2412`, `datoms-call-valid?` `:2615-2627` — ≈ 450 lines. `normalize-q-input`, the parser and the attribute check refuse the same inputs with typed `ex-info`; `q` already ends in `(catch Throwable cause (dependency-error ::q cause))`, which becomes the ONE translation, carrying Datahike's `:type`/`:error` in `:seon.error/cause`. The `:fn` guards leave the Malli contracts of `q`/`pull`/`datoms`; shape is still refused by the wrapper. Probe first: a table of the eight pre-checked inputs — Datahike throws on each, or that pre-check stays and is named. The 2026-09-21 live probe already shows an uninstalled query attribute returns an empty relation from Datahike while Seon explicitly refuses it; preserve that unknown-attribute check. The ≈450-line figure is conditional, not an established deletion | — | — | the eight-input table; a refused query renders its operation, layer and Datahike's diagnostic |
| t1–t5 | seon | §7, interleaved with c1, c3–c6, c2 | | | |

## 6. Better than the floor — probes the lane runs first

| # | candidate | probe that decides | if yes |
|---|---|---|---|
| 6.1 | **Acquisition reads are not evaluation evidence — ruled (2026-09-21, second-perspective review; B2 §2a carries it).** The 407 exist because `acquire-program!` ran under an evaluation's read capture; program acquisition and row installation are B2's program loads, not the agent's reads, and run outside any evaluation's capture binding (`render.clj:1558` `captured-reads`) | probe C after B2's change: zero namespace reads retained per evaluation; the retained population is the agent's own reads | the a′ scoped selector stays for cost only (twelve attributes instead of `*`); currency never sees a program-acquisition read |
| 6.2 | **`:db.type/any` is sound in the fork** | on a fixture store with history: assert a map, replace it, assert two values for one (e,a) in one tx, `history`/`as-of` reads, exact-value retraction `[:db/retract e a v]`, reconnect and reread; through `persistent_set` (the configured index). Record which step throws or equates distinct maps | the smallest comparator correction (`DH/datom.cljc:262-304`: a deterministic total order for admitted non-`Comparable` values consistent with `=`, across current and temporal index implementations) with its line count, then f1/c2. If no small correction exists, retain the codec and present three options: fix native-value ordering (recommended if bounded and proven), retain the existing codec (preserves shape, keeps read cost), or redesign individually justified declarations (smaller admitted value language). Homogeneous tuples cannot represent arbitrary mixed or long values; no generic tuple conversion is authorized |
| 6.3 | **Delete the `::branch-already-open` pre-check** | two holders open one branch in one JVM; release one; the other still reads; reopen during a release drain must complete bounded; the 2026-09-22 fork probe instead confirms `DH/connector.cljc` refuses `:connection-is-being-released` immediately (the reservation carries completion, but connect does not await it) | proof failed 2026-09-22: keep the check; no new waiting/retry machinery is authorized. Seon's one-owner contract stays explicit where sharing is not admitted |
| 6.4 | **Integers never reach the writer** | `(d/transact conn [{… (int 1)}])`, a transaction-function output, a homogeneous tuple; `(class (:v …))` before and after reconnect | c7 is a pure deletion |
| 6.5 | **One `datoms` output shape** | The dated B3 database probe observed a vector member refusal; reproduce with the index-argument-map arity `(seon.db/datoms db {:index :eavt :components [eid]})` | one contract in c8 |

## 7. Tests

Before: the fifteen owned namespaces total **5,366** lines (`wc -l`, recorded 2026-09-21: `db_test` 2,291 · `store_test` 645 · `schema/datahike_test` 619 ·
`registry_test` 561 · `blob_test` 223 · `store_transact_test` 198 ·
`error_write_timing_test` 182 · `blob_publication_test` 182 ·
`db/declaration_population_test` 151 · `read_evidence_test` 143 ·
`db_transact_shape_test` 59 · `publication_validation_test` 39 ·
`transaction_result_test` 39 · `blob_error_test` 18 · `blob_threshold_test` 16).

| disposition | tests (deftest lines verified) | reason |
|---|---|---|
| die with c1 | `db_test.clj:724 :763 :808 :822 :2183`; `read_evidence_test.clj:73` | digests, replay, index patterns |
| collapse into one currency class (c1) | `db_test.clj:669 :691 :845`; `read_evidence_test.clj:67 :70` → one namespace: an unrelated attribute's write keeps a read current; a named attribute's write does not; an insertion into a previously empty result; a child-only change under a component-expanded pull (plans `:all` — asserted as NOT current); an as-of value; a reconnect; an ineligible revision is never current | the guarantees survive, the mechanisms do not |
| die with c2 (after f1) | `db_test.clj:389 :449 :2114 :2152`; `store_transact_test.clj:162 :176`; `schema/datahike_test.clj:477`; `db/declaration_population_test.clj` whole | the codec; ONE regression: a map round-trips through `:db.type/any`, `d/pull` returns it unchanged, `history` retains the replaced value |
| become fork tests (f2) | `db_test.clj:967 :2054` | completeness lives beside `+default-limit+`; Seon keeps pull-many alignment and the elision on refusal |
| die with c3 | `db_test.clj:1425 :1471 :1482` | `repl_grammar_test.clj:24` keeps the value-diff grammar |
| die with c5 | `db_test.clj:2200` | schema guessing; the caller-named check keeps one case |
| collapse (c6) | `db_test.clj:1964`, `fn_test.clj:2328` (B1's file, proposed), `publication_validation_test.clj:6 :27`, `error_write_timing_test.clj:112` → one class: a docstring edit selects nothing; an arity change refuses a caller; a same-transaction repair passes; a retracted target refuses; `error_write_timing_test.clj` deleted whole (`with-redefs-fn` over 30 roots to time a step no longer on the path) | selected declarations and seeks are the assertion, never wall time or a `d/q` count |
| collapse (audit §5.3) | blob byte-exact ×5 → 1; two clusters never share ×3 → 1; `transact!` shape ×3 → 1 (`db_transact_shape_test.clj:46`) | |
| time escapes | `db/declaration_population_test.clj:16` 180,000 → file dies with c2 · `schema/datahike_test.clj:360` 120,000 → converted: three declarations on a branch, default 5 s · `schema/datahike_test.clj:243` 25,000 → the fixture hoisted once per namespace, default 5 s (80 cases do not justify repeated setup) · `error_write_timing_test.clj:80` 60,000 → deleted · `store_test.clj:498 :568` 60,000 each: the OS `fcntl` fence needs a second process — B4 interface; move both proofs into B4’s existing isolated platform execution through the one run authority, retaining actual child-exit evidence | every survivor declares reason AND number |
| new | liveness during a drain and two holders (§6.3); reopen with omitted/matching/conflicting `:keep-history?` incl. a no-history store (f8/c12); dry run with zero candidates and with candidates changing under a write (c10) | one regression per class |

The lane runs only the tests reaching its change, in-process, never a suite.

## 8. Done, landing note, size, stop rules

**Size, itemized (disjoint spans; replacement code counted):**

| commit | deleted | replacement | note |
|---|---|---|---|
| c1 currency | −380 src | +15 | eligibility/identity checks kept |
| c1 schema keys | −60 | 0 | `seon.db.edn` |
| c3 diff | −330 | 0 | |
| c4 pull selector | −70 | +10 | elision surfacing |
| c5 pulled form | −120 | 0 | caller-named check kept |
| c6 validator | −90 | +50 | |
| c9 registry | −20 | 0 | head read already uses the dependency identity |
| c10 GC | −100 | +10 | |
| c11 blob | −10 | +5 | |
| c12 store | −45 | +5 | |
| c8 guards/receipt | −110 | 0 | after B3 |
| c2 codec (after §6.2) | −340 | +5 | −230 `db.clj`, −110 bridge |
| c7 integers (after §6.4) | −24 | 0 | |
| c13 parser pre-checks | −450 | +10 | conditional on the eight-input table |
| **total** | **−2,149** | **+110** | **7,196 → ≈ 5,150** |

**Target ≤ 5,150** across the six owned files, checkable with `wc -l`. The 5,000-line stretch target needs additional proven dissolution; it is not a reason to drop codec, validator or custody guarantees. Cross-owner moves count at their destination. Fork and
test line changes are reported separately in the landing note.

**Done** = §1's after-column measured (probes A–G; the fresh-boot baseline where
`default` cannot show it); `wc -l` ≤ 5,600; every fork commit on `origin/main`
with its gitlink bumped; every RESET recorded; `default` alive after every
commit. Landing note: `docs/prds/agent-platform/landing/lane-a2.md` with the
exact forms and values of §4 before/after, the §6 probe outcomes, the fork
commit ids, and the two questions handed to B2 (a′ selector; 6.1).

**Stop** (three options in the note, simplest first, recommendation marked):
at a held file — `sci/eval.clj` (a′, `*receipt*`), `turn.clj:2030`,
`wake.clj:422` (B2), `seon.render.edn:19` (B2/A1: the render property type),
`fn.clj:1799` (B1), B3's constructor (c8); at an unsettled design — §6.2's
comparator correction if no small sound correction is demonstrated, the render-property type;
at a seam not landed — B3's constructor (c8), A1/B1's supplied-default facts
(c6 keeps today's acquisition until then); at B4’s platform execution seam for the two child-JVM lock tests; preserve the proofs until their new host exists.

The final test API is B4’s `seon.test/run`, carrying the exact immutable program, projection and result custody. A branch does not isolate store-global blobs; A2/B1 must supply a coherent independent-store source before B4 removes that fixture. The five error delays in `db.clj:52-68` leave only with B3’s renderer/constructor conversion; the three call-preparation delays remain until supplied-default admission is proven. Environment readers at `db.clj:1834-1860,4388` convert with B3’s explicit environment inputs, not by introducing new ambient lookups. Raw import remains a non-equivalent diagnostic because it bypasses final-report admission.
