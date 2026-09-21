---
type: plan
status: first pass (Fable, 2026-09-21) for astra review; clean write follows
created: 2026-09-21
tags: [agent-platform, lane-a2, datahike, konserve, seon.db, read-currency, db.type/any, pull, validator]
---

# Lane A2 — three answers become one (the Datahike / Konserve seam)

Owned: `src/seon/db.clj` (4,638), `src/seon/schema/datahike.clj` (555),
`src/seon/cluster/store.clj` (577), `src/seon/cluster/registry.clj` (667),
`src/seon/blob.clj` (430), `resources/seon/schemas/seon.db.edn` (329) — **7,196
lines**; plus `reference-code/datahike` and `reference-code/konserve` (our forks,
both at `origin/main`, nothing unpushed). Seam with A1: `seon.db/carried-projection`
(`db.clj:1219`). Inputs read end to end: the brief, data pack A2, the Datahike
deletion audit, the synthesis, the rulings note, packs B1–B4 where named below,
and the fork source at the current gitlinks (`datahike @ 006e634a`, `konserve @
07377c2`). Three read-only evaluations were spent on `default`; §1 records each.

## 0. For the owner: what was dumb, and the simpler way

**What the code does today.** Before every agent turn Seon asks, for each read the
agent's context retains, "is this still current?". `db.clj:1102-1144` answers it
three ways in sequence: replay the read's index patterns against history, compare
a revision map copied out of Datahike's cache context, and — when neither decides
— RE-RUN THE READ and compare a digest of its result. Then `turn.clj:2138-2170`
re-runs every read not proven unchanged anyway and compares the shown text. So a
stale read is executed twice and a fresh one is proven fresh by up to three
mechanisms. To store a map-shaped value the schema bridge prints it to an EDN
string, and every read walks the result decoding strings back and re-printing
each one to check it was canonical (`schema/datahike.clj:543-545`) — the write's
own work, redone per read, with 260 lines of walkers and a "declaration
population" carried into each of them only so they know which attributes to
decode. Every pull rewrites its own selector to escape Datahike's silent 1,000-row
cut. And every transaction that touches the program runs two queries over the
WHOLE program (65,764 call-arity edges, 3,337 schema forms on `default` today) to
validate the handful of datoms it wrote.

**Why that was the wrong shape.** Each is a mirror beside an authority that
already holds the fact. Datahike's writer stamps, per commit, which attributes
changed (`writer.cljc:242-246`) — a read is current when the attributes it named
have not changed, one map comparison. Datahike already has an arbitrary-value type
(`schema.cljc:87`) and validates every value at write (`transaction.cljc:49`) — a
value read back from a datom needs no decoding and no re-validation. The pull limit
is one constant in our own fork. The transaction report already lists exactly the
datoms that changed — the only ones a validator can have anything to say about.

**The simpler way, as data flow.** A read's evidence is the attribute set its
parsed plan names and the commit ids those attributes were last changed at; both
come from Datahike, both ride the evaluation entity, and "current?" is equality
against the connection's current cache context — proportional to the attributes
the read names, never to the database. When the plan is `:all` the answer is
"changed on any commit", and the turn's existing shown-text comparison is the one
replay, at the authority that renders. Values are stored as values: `:db.type/any`
in the fork, no codec, no walkers, no population plumbing. A pull returns
everything or refuses under the resource bound our fork already enforces — never a
shorter list. The validator reads the report's own datoms, grouped by entity, and
checks each changed edge against indexed facts — proportional to the transaction.

## 1. Goal and the numbers that prove it

| measure | before (measured on `default`, 2026-09-21) | after (target) | how measured |
|---|---|---|---|
| retained reads on `default` | **435**; `:all` plans **413** (95 %); index-arm decided 21; revision-equal 22; would reach replay 413 | same count; `:all` share is the probe in §6.1 | eval 3 (§4) |
| `read-evidence-current?` over all 435 | **114.9 ms**, returned `false` | ≤ 5 ms: 435 map lookups, no history walk, no replay | eval 3 |
| EDN-encoded attributes | **23** of 1,191; all `:db.cardinality/one`; none indexed/unique/identity; 7 hold datoms (`read-request` 429, `revision` 435, `dependency-plan` 435, `render/ai` 334, `render/html` 329, `render/form` 11, `print/default` 5) | 0 (the type is native) | eval 1 |
| raw `d/pull` of an EDN attribute | returns the **string** (eval 2 refused: "expected `:all`, got a string") | returns the value | eval 2 |
| validator work per program write | Datalog over **65,764** `:seon.fn/call-arities` datoms + **1,684** `:seon.fn.arity/min` rows + `edn/read-string` of **3,337** `:seon.schema/form` rows | ∝ report datoms (a docstring edit: tens) | eval 3; §2 (d) |
| `with-fresh-database` | 4,647.8 ms per requesting test (B4 §5: `fork-database` copies every konserve key) | branch: 37 ms p50 (B4, `with-branched-database`) | B4 |
| owned lines | 7,196 | **5,000** (floor 5,456; §7) | `wc -l` |

Anything above ~2 s in this area is explained by one of: whole-store copy
(fixture), whole-program query (validator), or program indexing inside a fixture
(§7 time escapes). None survives this spec.

## 2. The data flow, per mechanism

| mechanism | data | computed when | carried where | recomputation trigger | work ∝ |
|---|---|---|---|---|---|
| **(a) read currency** | per read: `{:datahike.read/attributes #{…}\|:all, :datahike.cache/attribute-revisions {attr→commit-id}, conservative-revision, connection-id, generation}` = `dependency-revision` (`db.clj:875-903`) | at evaluation, from the plan Datahike attaches (`pull_api.cljc:564-585`, `query.cljc:2877-2902`) and the value's `:cache-context` | the evaluation entity (`:datahike.read/revision`, `/dependency-plan`), as native values | none: the revision is immutable evidence | attributes named by the read |
| — the current context | `:cache-context` on every committed DB value: `advance-query-cache-context` (`query.cljc:2568-2590`) associates the commit id ONLY for attributes the batch changed (`writing.cljc:597-602`); schema-touching or unknown batches bump `conservative-revision` | in the writer, per commit (`writer.cljc:242-246`) | the connection's value; as-of values reach it through `revision-source` (`db.clj:852-873`) | every commit | attributes changed by the commit |
| — the comparison | `(= retained (dependency-revision db plan pos))` — the same predicate as Datahike's own `source-context-unchanged?` (`query.cljc:2963-2976`) | per system turn | nowhere | — | retained reads × their attributes |
| — an `:all` plan | revision = commit id; any commit makes it "changed" | — | — | — | the turn's shown-text comparison (`turn.clj:2138-2170`) is the ONE replay |
| deleted | `index-evidence-current` (`:1084`, walks `(d/since (d/history db) basis)` per read), `replay-read` + `stable-value`/`read-result-digest` (`:530-595`, `:971-1052`), `query-index-patterns`/`pull-index-patterns` (`:596-763`, re-executes the query at `:640` to bind patterns), `read-evidence-changes`/`index-pattern-change` (`:1038-1083`; its output `:seon.turn/changes` at `turn.clj:2030` has no consumer by grep — B2 confirms) | | | | |
| **(b) values** | the value itself | at write, validated by `ds/value-valid?` (`transaction.cljc:49`) against `:db.type/any` = `any?` (`schema.cljc:87`) | the datom | — | 0 on read |
| deleted | bridge union→string fallback (`schema/datahike.clj:118-124`), `edn-encoded-attr-in?` (`:283-289`), codec (`:370-460`, `:505-555`); `db.clj` `edn-encoded?`/`decode-attribute-*` (`:1312-1356`), `encode-query-request` (`:1611`), `decode-query-*` (`:1646-1725`), `decode-pull-*` (`:1736-1779`), `datom->data`/`decode-index-page` (`:2542-2566`), `read-declarations`/`relation-only-declarations`/`with-declarations`/`ask-declarations` (`:1226-1310`, ten sites); `wake.clj:422` (B2) becomes identity | | | | |
| **(c) pull** | complete collections | — | — | — | the resource bound of fork `1e78cb9c` (`pull_api.cljc:319-320` `charge-work!`/`charge-value!`) refuses typed; Seon reports that refusal as an elision naming the bound |
| deleted | `total-pull-selector` family (`db.clj:2080-2149`) | | | | |
| **(d) validator** | `(:tx-data report)` grouped by `:e`; datoms with `:a ∈ #{:seon.fn/call-arities}` added; arity rows touched; `:seon.schema/form` added; `:seon.fn/sym` retracted | inside `validate-report` (`transaction.cljc:1207-1216`, fork `73afe782`) | the report | every commit | report datoms + callers of changed callees |
| — arity | for each added `:seon.fn/call-arities` datom `[caller [callee n]]`: `n` within the callee's bounds (`[?f :seon.fn/sym callee] [?f :seon.fn/arities ?a] [?a :seon.fn.arity/min ?min]` — identity lookup + ref walk); for each arity row touched: its function via `[?f :seon.fn/arities ?a]` (reverse ref, indexed), then callers via `[?c :seon.fn/calls sym]` and their edges to that callee | same | — | — | changed edges + callers of changed callees. **Requires `:seon.fn/calls` `:db/index true`** (AVET seek on a symbol set) — verify in `seon.fn.edn`; if absent, one schema edit (B1 file) and RESET |
| — render targets | for each added `:seon.schema/form`: its two render symbols resolve on `:db-after` (`entid [:seon.fn/sym s]`); for each retracted `:seon.fn/sym s`: `[?schema :seon.schema/render-ai s]`/`render-html` on `:db-after` | same | the render symbols as **facts on the schema row** (`:seon.schema/render-ai`, `/render-html`, `:qualified-symbol`, indexed), written where the form is written | — | forms in this tx + one indexed seek per retracted symbol. Today the symbols live only inside the printed form (`db.clj:3910-3920` parses 3,337 forms per write): §2.2 "declare it at the one declaration seam". The attribute lives in `seon.schema.edn` (A1's file) — seam named in §8 |
| **(e) diff** | `value-changes`/`apply-diff` (editscript) and the map arity `turn.clj:2224` calls | — | — | — | deleted: `external-sink-reach-rules` … `callee-symbol` (`db.clj:2797-3134`, `:3175-3223`), a recursive whole-call-graph rule per call, zero production callers |
| **(f) pulled form** | none at read: the write validated the datoms; a read of them is valid by construction | — | — | — | deleted: `pulled-entity-schema-key`, `validate-pulled-value`, `validate-pulled-result` (`db.clj:2150-2331`; a `d/datoms :eavt` scan per pulled entity per pull). A caller wanting the entity schema names it |
| **(g) liveness** | `connections/active-connection` (`connections.cljc:5-9`, count-aware) | at `open-branch!` | — | — | `store.clj:564` `contains?` on `*connections*` is true during the whole release drain (`delete-connection!` runs last, `:123-127`) and during `:opening?` — a legitimate reopen is refused |
| **(h) branch head** | `k/get-in store [branch :meta :datahike/commit-id]` — the library's own spelling (`gc.cljc:24`) | on demand | — | — | one konserve read; `registry.clj:126-152` becomes a fork export |
| **(i) load cycle** | none | — | — | — | the eight `defonce`+`delay` holders (`db.clj:52-68`) resolve `seon.error/{explain-problem,problem-sentence,scalar-text,render-ai,render-html}` and `seon.call-preparation/{snapshot,plan-for,prepared-arities}`. `db.clj` renders nothing (D12: it returns error VALUES carrying Malli's explain data; the error render pair renders) → five holders die. The three call-preparation holders serve the arity check's supplied defaults; with (d) the effective bounds are FACTS on the arity row written by the indexer (B1) → three die |
| **(j) fixture** | a branch off the held base (`d/branch!`, `versioning.cljc:212`, pointer) | per test | worker store | — | O(1). `with-fresh-database` (`test_support.clj:921-944`) uses `fork-database` (`versioning.cljc:550`: "copies every konserve key") = 4,648 ms. The four store-scoped tests (B4 §5) build an EMPTY memory store (`d/create-database`) + the projection's schema transaction: ∝ 1,191 attributes, measured in §4 |

Consumers outside A2 of what changes: `read-evidence-current?` ← `render.clj` (3),
`render/walk.clj` (1), `render/web.clj` (4), `turn.clj` (2) — signature unchanged.
`render.clj:701 program-evidence-current?` (B2 pack §1 row 55) should call it
rather than keep a second answer. `transaction-result` (`db.clj:4547`, 9
production callers in `cluster.clj`/`config.clj`/`source.clj`) stays; its per-datom
`d/datoms :eavt` becomes one seek per DISTINCT `:e` (§5 c8).

## 3. Reading list (read before editing; each line = what it guarantees)

| block | guarantee |
|---|---|
| `datahike/query.cljc:2568-2590` `advance-query-cache-context` | per commit, ONLY changed attributes get the commit id; schema-touching/unknown batches bump `conservative-revision`; rows never inspected |
| `query.cljc:2963-2976` `source-context-unchanged?` | current ⇔ conservative equal AND every named attribute's revision equal; `:all` is never unchanged |
| `query.cljc:2877-2902` `query-dependency-plan`; `pull_api.cljc:107-113`, `:162-177`, `:564-585` | attribute-granularity plan without executing; any throw ⇒ `:all` |
| `writer.cljc:225-250`; `writing.cljc:597-602` | where the context advances, from `batch-cache-revision-attributes` |
| `connector.cljc:355-368`, `:85-96`; `versioning.cljc:69-95`, `:469-488` | context birth at connect; deref refresh; as-of/commit values keep the connection's identity |
| `schema.cljc:35-55`, `:87`, `:105`, `:228-234`; `transaction.cljc:40-52` | `:db.type/any` exists, is used, is ABSENT from `:db.type/value`; values validated at write with `s/valid?` |
| `datom.cljc:262-299`, `:309-323`; `index/persistent_set.cljc:56-80` | index comparators use `cmp-nil`→`safe-compare`→`compare`; on a throw two values compare by CLASS NAME (two maps = 0). Safe for cardinality-one; NOT for cardinality-many, `:db/index`, `:db/unique` |
| `pull_api.cljc:16`, `:315-322` | `+default-limit+ 1000`; `(if limit (take limit) identity)` — nil = complete; `charge-work!`/`charge-value!` = the resource bound |
| `transaction.cljc:1207-1226` (fork `73afe782`) | the final-report validator: nil accepts, any value rejects the whole transaction |
| `versioning.cljc:182-189`, `:212-252`, `:279-320`, `:323`, `:457-461`, `:550-560` | roster; `branch!` refuses duplicates; `delete-branch!` refuses main and active connections (typed); `force-branch!` has NO active check; `commit-id`; `fork-database` = whole-store copy |
| `connections.cljc:5-9`, `:123-127` | count-aware liveness; map entry removed at the end of the drain |
| `connector.cljc:144-181`, `:183-200` | stored-config consistency refuses typed with `:diff`; create-time keys adopted from the store |
| `gc.cljc:22-30`, `:83-168`; `gc_guard.cljc:36-42`; `konserve/gc.cljc:8-30`; `konserve/core.cljc:690-696` | head read; mark then `sweep!`; one-JVM assumption; the batch callback contract; `k/keys` metadata (`:key`, `:last-write`, no size) |
| first-party idioms | `cluster/source.clj` (every branch mutation through `datahike.versioning`); `blob.clj` on `bassoc`/`bget`/`bget-range`; `store.clj:306-356` `acquire-flock!` (no library counterpart) |
| `turn.clj:2008-2044`, `:2138-2170` | the consumer: status from `read-evidence-current?`; every non-unchanged read re-evaluated and compared by shown text |

## 4. REPL protocol (`eval_clj`, mode `jvm`, `read_only` true, cluster `default`)

| question | form | before (measured) | after (expected) |
|---|---|---|---|
| currency census | eval 3's form (data pack style; `#'seon.db/dependency-revision`, `#'seon.db/index-evidence-current` over every `:datahike.read/revision` entity through `seon.db/pull`) | 435 / `:all` 413 / index-true 21 / revision-equal 22 / 114.9 ms | same census with `index-*` removed; ≤ 5 ms |
| why `:all` | `(frequencies (map (juxt :seon.db/read-operation (comp #(= :all %) :datahike.read/attributes :datahike.read/revision)) reads))` | not measured (the third evaluation was spent) | decides §6.1 |
| codec live | `(datahike.api/pull db '[:datahike.read/dependency-plan] ev)` | a string | the map |
| value type | `(get (datahike.db.interface/-schema db) :seon.render/ai)` | `:db.type/string` | `:db.type/any` |
| pull cut | `(count (:seon.fn/calls (seon.db/pull db '[:seon.fn/calls] [:seon.fn/sym 'seon.turn/step])))` vs `(count (datahike.api/datoms db :eavt eid :seon.fn/calls))` | equal only because `total-pull-selector` rewrote the selector | equal with the raw selector |
| validator cost | `(time (seon.db/transact! conn [{:seon.fn/sym 'x/y :seon.fn/doc "edit"}]))` (a fixture branch) | dominated by the two whole-program queries | ∝ the report |
| datoms contract | `(seon.db/datoms db :eavt eid)` armed → vector of 5-key maps (eval 3); B3 §10 row 11 saw a VECTOR member refused | reproduce with the index-argument-map arity `(seon.db/datoms db {:index :eavt :components [eid]})` — hypothesis, unverified | one output shape |
| fixture | `(time (with-database …))` for a store-scoped test | 4,648 ms | tens of ms; the empty-store+schema number recorded |
| liveness | `bin/seon status` and `mcp__seon__runtime_status` after each commit | — | alive; the named debug probe per commit is in §5 |

Recovery when a commit breaks `default` anyway: `bin/seon reset --force` (loses
nothing durable; database data is disposable). A lane never stops or reforks
`default`; RESET NEEDED is recorded in the landing note.

## 5. The work, ordered as commits (each leaves HEAD loadable; fork commits pushed before the deletion they enable)

| # | repo | change | deletes / enables | RESET | debug probe after |
|---|---|---|---|---|---|
| f1 | datahike | `schema.cljc:35-55` add `:db.type/any` to `:db.type/value`; in schema validation (`find-invalid-schema-updates`/install path) refuse `:db.type/any` combined with `:db/index`, `:db/unique`, or `:db.cardinality/many` (comparator has no total order for those, `datom.cljc:279-285`); test | enables c2 | — | fork tests |
| f2 | datahike | `pull_api.cljc:16` `+default-limit+` → `nil`; an explicit `:limit` still honoured at `:315`; test that a 1,001-member collection pulls complete and that the resource bound (`1e78cb9c`) still refuses typed | enables c4 | — | fork tests |
| f3 | datahike | `versioning.cljc` beside `branches` (`:182`): `(defn branch-commit-id [conn-or-store branch])` = `(k/get-in store [branch :meta :datahike/commit-id])` | enables c9 | — | — |
| f4 | datahike | `gc.cljc:120-168`: option `:datahike.gc/plan-only?` returns `{:datahike.gc/reachable … :datahike.gc/candidates …}` after the mark and before `sweep!` | enables c10 (kills the token exception) | — | — |
| f5 | konserve | `filestore.clj` `-keys`: add `:size` (file length) to each key's metadata; public `store-base` accessor for the physical root | enables c10, c11 | — | — |
| f6 | datahike | probe first (§6.4): if Datahike stores `Integer` under `:db.type/long`, coerce at `transaction.cljc:49` | enables c7 | — | — |
| c1 | seon | **read currency = one mechanism.** `read-evidence-current?` = `every?` of `(= revision (dependency-revision db plan pos))` (keep `revision-source` `:852-873` for as-of). Delete `:530-595`, `:596-763`, `:971-1052`, `:1038-1101`, `read-evidence`'s `:seon.db/read-request`/`read-result`/`read-result-digest` arms (`:904-957`), `seon.db.edn` `:66-83`, `:109-111`, `:139-155`, the `read-index-pattern*`/`read-basis-t`/`read-request`/`read-result*`/`captured-read` keys. Callers unchanged (signature kept). `turn.clj:2030` drops `:seon.turn/changes` (B2; one line — held-file rule §8) | −405 src | RESET (attributes removed) | census form (§4) ≤ 5 ms; a turn on `root` renders |
| c2 | seon | **`:db.type/any`.** Bridge: an `:or` of mixed types → `{::value-type :db.type/any}` (`schema/datahike.clj:118-124`); delete `::edn?`, `edn-encoded-attr-in?`, codec `:370-460`, `:505-555`. `db.clj`: delete every decode/encode walker and the declaration population (§2 b); `wake.clj:422` → identity (B2, one line) | −260 db, −150 bridge | RESET (23 attributes change type) | `(datahike.api/pull …)` returns the map; a page renders a `:seon.render/ai` value |
| c3 | seon | delete the multi-arity `diff` family; keep `value-changes`, `apply-diff`, the map arity; `seon.db.edn` diff keys pruned | −330 | — | `turn.clj:2224` path: a system turn with a changed read |
| c4 | seon | delete `total-pull-selector` family; pull refusals from the resource bound surface as `:seon.print/elision` naming `:datahike.resource/…` | −70 | — | pull-cut form (§4) |
| c5 | seon | delete pulled-form inference and read-side validation (`:2150-2331`); callers that need the entity schema pass it | −182 | — | `seon.db/pull` on an agent row |
| c6 | seon | **validator narrowed** (§2 d): arity from report datoms + indexed callers; render targets from added forms + the schema-row render facts. Requires `seon.schema.edn` `:seon.schema/render-ai`/`render-html` (A1 file) and `:seon.fn/calls` index (B1 file) — if either is not landed, STOP per §8 with the two-line schema diff in the note. `seon.fn/arity-mismatches` re-export deleted; `db/arity-mismatches` stays a query | −40 net; kills `error_write_timing_test` | RESET if the two attributes are new | `(time (transact! …))` docstring edit |
| c7 | seon | delete `jdk-integers->long` (`:3235-3258`, a `postwalk` per write) once f6 settles | −24 | — | a transact of an `(int 1)` |
| c8 | seon | the 56 inline error-shape checks → B3's constructor/predicate per D12 (after B3 lands; else STOP); `transaction-result` seeks once per distinct `:e`; `*receipt*` → current vocabulary; the `datoms` output contract made one shape (§4 probe) | −110 | — | `(seon.db/datoms db :eavt eid)` armed |
| c9 | seon | `registry.clj:126-152` → `versioning/branch-commit-id`; delete `::cannot-retire-main`, `::cluster-connected` on `retire-branch!` (`:343-351`; the library refuses both typed, `versioning.cljc:286-288`, `:310-315`); KEEP the `reset-cluster!` check before `force-branch!`; fix the four drifted docstring citations | −45 | — | `bin/seon status`; a scratch branch retire |
| c10 | seon | `dry-run!`/`dry-run-complete?`/token (`:510-573`) → `:datahike.gc/plan-only?`; `physical-filestore-inventory` (`:446-509`) → `k/keys` `:size` | −110 | — | `collect!` dry run on a scratch root |
| c11 | seon | `blob.clj`: one `payload-bytes` (delete `:224-229`, fold `:131`); `(:backing :base)` → `store-base` | −25 | — | blob round-trip form |
| c12 | seon | `store.clj`: `:564` → `active-connection`; delete `file-lock-generator` + `fresh-file-lock` (`:98-124`, an OS flock under `tmp/` never released; the fence is proven by the child-JVM tests); delete `open-configuration` (`:194-197`, `:499-501`; `adopt-create-time-fixed` does it) and `stored-main-keep-history?` + `::keep-history-mismatch` (`:368-373`, `:470-483`; the library's `:config-does-not-match-stored-db` carries `:diff`); `canonical-path` → `seon.operator.state/canonical-path` | −60 | — | reopen a branch during a release drain (the §6.3 probe) |
| c13 | seon | eight holders (`:52-68`) deleted per §2 (i): error rendering leaves `db.clj`; arity defaults become facts (B1 seam) | −20 | — | `clojure -M -e "(require 'seon.db)"` |
| t1–t4 | seon | tests per §7, interleaved with c1–c6 | −≈1,470 | | |

Every commit: `git commit --only -- <owned paths>`; `clojure -M -e "(require
'seon.db 'seon.schema.datahike 'seon.cluster.store 'seon.cluster.registry
'seon.blob)"`; `require :reload` of the touched namespaces on `default` and the
named probe. Fork commits are pushed to `origin/main` and the gitlink bumped in
the same Seon commit that first depends on them.

## 6. Better than the floor — probes the lane runs first

| # | candidate | probe that decides | if yes |
|---|---|---|---|
| 6.1 | **Make plans not `:all`.** 413 of 435 retained reads carry `:all`, so the ONE mechanism degenerates to "re-evaluate after any commit" for 95 % of reads. Sources of `:all`: `datoms` without an attribute (`db.clj:2611`), `index-page` (`:2657`, hard-coded), any planner throw (`query.cljc:2902`), wildcard/component pulls (`pull_api.cljc:110`) | the "why `:all`" form (§4) grouped by read operation and by the calling function (`:seon.eval/source`) | fix the top source at its seam (e.g. `index-page` derives attributes from its options; a `datoms` call with an entity but no attribute names the entity's attributes from its EAVT seek — still ∝ the read) |
| 6.2 | **No Seon-side revision at all**: retain the Datahike plan + the context's `commit-id` only and ask the fork `source-context-unchanged?` (make it public, ~3 lines) | count of lines `dependency-revision` + `revision-source` keep after c1 (est. ~45) | delete them; the fork owns the predicate end to end |
| 6.3 | **Delete the `::branch-already-open` refusal** (`store.clj:559-566`): Datahike's `connect` on an open id returns the same connection with its count incremented (`connections.cljc:10-30`), so "already open" may be a legitimate share | open the same branch twice in one JVM; release once; confirm the second holder still reads | delete the check, not just fix it |
| 6.4 | **`Integer` never reaches the writer**: the only producers are JDK interop (`.size`, `.length`) at named sites | `(d/transact conn [{… :seon.x/n (int 1)}])` then `(class (:v (first (d/datoms …))))` on a fixture branch; also after reconnect (konserve round trip) | if Datahike already stores a `Long` or `=` holds through reload, f6 and c7 are a pure deletion with no fork change |
| 6.5 | **Store-scoped fixture without a copy**: an empty memory store + the projection's schema transaction | `(time (d/create-database {:store {:backend :memory} …}) (d/transact conn schema-tx))` | replaces `fork-database`; if > 1 s, instead branch the base and treat "fresh store" tests as branch tests (B4 says only 4 exist) |

## 7. Tests

Before: the sixteen namespaces in this area total **5,357** lines (`db_test`
2,291; `store_test` 645; `schema/datahike_test` 619; `registry_test` 561; …).

| disposition | deftests | reason |
|---|---|---|
| die with c1 | `db_test.clj:669 :691 :724 :763 :808 :822 :845 :2183`; `read_evidence_test.clj:67 :70 :73` | index patterns, digests, replay |
| die with c2 | `db_test.clj:389 :449 :2114 :2152`; `store_transact_test.clj:162 :176`; `schema/datahike_test.clj:477` | the codec; ONE new regression: a map value round-trips through `:db.type/any` and `d/pull` returns it unchanged |
| become fork tests | `db_test.clj:967 :2054` | pull completeness lives beside `+default-limit+` |
| die with c3 | `db_test.clj:1425 :1471 :1482` | `repl_grammar_test.clj:24` keeps the value-diff grammar |
| die with c5 | `db_test.clj:2200` | pulled-form inference |
| collapse to one (c6) | `db_test.clj:1964`, `fn_test.clj:2314 :2374`, `publication_validation_test.clj` (×2), `error_write_timing_test.clj:112` → one deftest: a call-arity write refuses; **`error_write_timing_test.clj` deleted whole** (182 lines redefining 30 Vars to time a step no longer on the path; its 60,000 ms bound goes with it) | one class, one regression |
| collapse (audit §5.3) | declaration population ×3 → 0 (mechanism gone); blob byte-exact ×5 → 1; two clusters never share ×3 → 1; `transact!` shape ×3 → 1 (`db_transact_shape_test.clj:46`) | |
| time escapes | `db/declaration_population_test.clj:16` 180,000 → file deleted; `schema/datahike_test.clj:360` 120,000 → converted: three declarations on a branch, default 5 s; `:243` 25,000 keeps (80 generative cases, number stated); `store_test.clj:498 :568` 60,000 keep (child JVM proves the OS `fcntl` fence; number stated); `error_write_timing_test.clj:80` deleted | every survivor declares reason AND number |
| new | one regression per class: currency = revision equality (an unrelated attribute's write leaves a read current; a named attribute's write does not); validator ∝ report (a docstring edit performs no whole-program query — assert by counting `d/q` calls through a fixture-bound var, not by time); liveness during a drain; fresh-store fixture time | |

The lane runs only the tests reaching its change, in-process (`seon.test/check`
over `run-owned`), never a suite.

## 8. Done, landing note, stop rules

**Done** = §1's after-column measured on `default` (or the fresh-boot baseline where
`default` cannot show it), `wc -l` of the six owned files ≤ 5,000 with the gap to
the floor explained (§0/§2: three arms → one predicate ≈ −405 not −300; population
plumbing goes whole; diff family whole; fork exports replace registry reads), every
fork commit on `origin/main`, every RESET recorded, `default` alive after every
commit. Landing note: `docs/prds/agent-platform/landing/lane-a2.md` with the exact
forms and numbers of §4 before/after, the `:all` census by source, and the fork
commit ids.

**Stop** (three options in the note, simplest first, recommendation marked):
- at a held file: `turn.clj:2030` and `wake.clj:422` (B2), `seon.schema.edn` (A1),
  `seon.fn.edn` (B1), the B3 error constructor for c8 — a one-line edit in a foreign
  file is proposed in the note, never made while the file is held;
- at an unsettled design: the render-symbol facts (c6) if A1 rules the schema row
  shape differently; the `:all` remedy (6.1) if the top source is a B2 read;
- at a seam not landed: B3's constructor (c8), B1's arity-bound facts (c13).

**Size target:** 7,196 → **5,000** lines across the six owned files (floor from the
audit 5,456). The reasoning for the gap: the audit counted spans; the population
plumbing, the read-evidence writer arms, the read-side validation and the eight
holders each dissolve the code AROUND the spans too (contracts, docstrings, the
`seon.db.edn` keys, the `::read-projection` delay). Not claimed: the 56 guards'
replacement shape (B3's), and whether 6.2 lands (another ~45).

**What this first pass could not settle:** why 413 reads plan `:all` (the third
evaluation was spent before the grouping); the exact vector member that violated
the `datoms` contract (B3's sighting, not reproduced here); whether `Integer`
values ever reach the writer (6.4); whether the arity supplied-default bounds
become facts (B1) or a leaf namespace (A1); the `:seon.fn/calls` index state.
