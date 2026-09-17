---
type: research
status: active
created: 2026-09-17
tags: [contracts, private-functions, error-model, db-reads, class/absence-as-health, triage]
---

# Private-function audit — turn, cluster, sci, env, effect, plan (2026-09-17)

Read-only audit for program-facts PRD
[§1j](../plan/program-facts-are-the-runtime-prd-2026-09-17.md) ("every
function carries a contract, private included"). No source was edited. The
`private-contracts` lane was writing contracts concurrently; `git log
--since=3.hours --` over every file in this domain shows **no commit** —
nothing in this audit has been landed on since the census was taken.

Domain: `src/seon/turn.clj`, `src/seon/cluster.clj`, `src/seon/cluster/*.clj`,
`src/seon/sci/*.clj`, `src/seon/env.clj`, `src/seon/effect.clj`,
`src/seon/plan.clj`.

## 1. The census

One read-only MCP evaluation, `mode: jvm`, explicit custody, on `default`
(pid 94566, the same process §1j cites):

```clojure
(let [conn (seon.operator/connection "default")
      db (seon.db/db conn)
      domain #{"seon.turn" "seon.cluster" "seon.cluster.agent" "seon.cluster.message"
               "seon.cluster.wake" "seon.cluster.source" "seon.cluster.prompt"
               "seon.cluster.process" "seon.cluster.registry" "seon.cluster.reply"
               "seon.cluster.status" "seon.cluster.store" "seon.cluster.export"
               "seon.cluster.instruction" "seon.sci.eval" "seon.sci.admit"
               "seon.sci.kernel" "seon.env" "seon.effect" "seon.plan"}
      reads #{"seon.db/pull" "seon.db/q" "seon.db/entity" "seon.db/pull-many"
              "seon.db/transact!" "seon.db/datoms"}
      rows (seon.db/q db
             '[:find (pull ?f [:seon.fn/sym :seon.fn/private? :seon.fn/spec
                               {:seon.fn/ns [:seon.ns/name]}
                               {:seon.fn/calls [:seon.fn/sym]}])
               :where [?f :seon.fn/sym _]])
      rows (map first rows)
      in-domain (filter #(domain (str (get-in % [:seon.fn/ns :seon.ns/name]))) rows)
      priv (filter :seon.fn/private? in-domain)
      priv-nospec (remove :seon.fn/spec priv)
      callers-of (reduce (fn [m r]
                           (reduce (fn [m c] (update m (str (:seon.fn/sym c)) (fnil conj #{}) (str (:seon.fn/sym r))))
                                   m (:seon.fn/calls r)))
                         {} rows)
      entry (fn [r]
              (let [s (str (:seon.fn/sym r))
                    rd (sort (filter reads (map #(str (:seon.fn/sym %)) (:seon.fn/calls r))))]
                [s (vec rd) (vec (sort (callers-of s)))]))]
  {:totals {...} :census (vec (sort-by first (map entry priv-nospec)))})
```

### Totals (exact, from that one query)

| Measure | Count |
|---|---|
| `:seon.fn` rows on `default` | 5,191 |
| Functions in this domain | 800 |
| Private (`:seon.fn/private? true`) in this domain | 470 |
| Private **with** `:seon.fn/spec` | **1** |
| Private **without** a spec | **469** |
| …of those, calling a `seon.db` read/write directly | **112** |

`domain-private-with-spec` is 1 of 470 — 0.2 %. §1j's global figure is 16 of
3,161 (0.5 %), so this domain is contracted *below* the system average while
owning the writer, the turn loop, acquisition, and the effect boundary.

### Private, spec-less, per namespace

| Namespace | Count | | Namespace | Count |
|---|---|---|---|---|
| `seon.turn` | 107 | | `seon.cluster.message` | 16 |
| `seon.cluster` | 85 | | `seon.cluster.prompt` | 14 |
| `seon.sci.eval` | 66 | | `seon.cluster.registry` | 14 |
| `seon.plan` | 52 | | `seon.cluster.reply` | 14 |
| `seon.effect` | 24 | | `seon.cluster.store` | 10 |
| `seon.sci.admit` | 22 | | `seon.cluster.export` | 7 |
| `seon.cluster.source` | 18 | | `seon.sci.kernel` | 7 |
| `seon.cluster.status` | 6 | | `seon.env` | 3 |
| `seon.cluster.agent` | 2 | | `seon.cluster.wake` | 2 |

`seon.cluster.process` and `seon.cluster.instruction` declare no private
functions at all.

### Method note, stated honestly

The graph query is the authority for the totals and for the first 32 census
rows the MCP profile showed. **The remaining 437 rows could not be retrieved**
(finding F1 below), so the per-function `file:line`, read set and caller set
in the tables were re-derived statically from the checked-out source at
`7cec8cb57`: `^\(defn- ` at top level, `\b(db|seon\.db)/(pull|pull-many|q|entity|datoms|transact!)\b`
inside the declaration's span, and callers by enclosing-`def` attribution over
`src/**/*.clj[c]`. The static extraction finds **470** private declarations
and **113** direct read-consumers against the graph's 470/112 — the one-row
difference is `seon.sci.eval`'s single already-contracted private, which the
graph excludes and the text scan includes. Caller sets are therefore
*advisory static evidence*, not `:seon.fn/calls` edges, for every row past the
first 32.

## 2. Critical rows — functions touching the database, the SCI context, the turn transitions, or able to return an error

Contract candidates cite `resources/seon/schemas/` keys verified present:
`:seon.db/database-value`, `:seon.db/connection`, `:seon.db/entity-id`,
`:seon.agent/id`, `:seon.turn/id`, `:seon.cluster.eval/id`,
`:seon.cluster.eval/ordinal`, `:seon.ns/name`, `:seon.error/value`,
`:seon.turn.work/episode-runs`, `:seon.fn/sym`, `:seon.effect/id`.

### 2.1 `seon.turn` — the writer and the turn loop

| Symbol | `file:line` | Consumes (real call site) | Returns (every arm) | Read result used before any `:seon.error/kind` check | Can return a flat error | Honest contract candidate | Callers armed contract would break | Risk |
|---|---|---|---|---|---|---|---|---|
| `current-run` | `src/seon/turn.clj:310` | `db` = mid-transaction database value; `id` = `:seon.turn/id` string. Call site `require-open-run` `:314`. | the pulled turn map, `nil`, **or `seon.db/pull`'s error map** | **YES — `:315`** binds it and `:317`/`:318` branch on `(nil? turn)` then `(open? turn)`; the error map is never inspected | yes, by pass-through | `[:=> [:cat :seon.db/database-value :seon.turn/id] [:or :nil :seon.turn/turn :seon.error/value]]` | `require-open-run` `:314`, `receipt-run` `:809`, `agent-run` `:2365`, `stored-record-content` `:1488`, `open-run-tx-call`, `failure-replacement-tx` | **blocker** — see F2 |
| `running-receipts` | `:326` | `db`, `run-eid` (long). Call site `interrupt-stamps` `:340`. | seq of receipt maps; an error map from `db/q` flows into `(map #(db/pull …))` | **YES — `:333`** `(map #(db/pull db '[*] %))` maps over an error map, producing MapEntry eids | no — throws instead | `[:=> [:cat :seon.db/database-value :seon.db/entity-id] [:or [:sequential :map] :seon.error/value]]` | `interrupt-stamps` `:337` (boot recovery) | **blocker** |
| `current-receipt` | `:800` | `db`, `id`, `ordinal` | receipt map, `nil`, or error map | yes — callers take `(:db/id …)` | pass-through | `[:=> [:cat :seon.db/database-value :seon.turn/id :seon.cluster.eval/ordinal] [:or :nil :map :seon.error/value]]` | `receipt-start-call`, `receipt-settle-call`, `generated-run-tx` | blocker |
| `settlement-form` | `:888` | `database`, `request` map | `nil` when `(:db/id form)` absent, else a 3-key map | **YES — `:894`** `(when (:db/id form))`; an error map has no `:db/id`, so a failed read is reported as "no such form" | no | `[:=> [:cat :seon.db/database-value :map] [:or :nil :map]]` — *and the `:or` must gain `:seon.error/value` once the read is checked* | `analyze-settlement` `:900` | blocker |
| `analyze-settlement` | `:898` | `database`, `request` | form-facts map or `nil` | **YES — `:906`** `(:db/id (db/pull database [:db/id] subject))` decides `subject-present?`; a read error deletes `:seon.test/subject` from the declaration | no | `[:=> [:cat :seon.db/database-value :map] [:or :nil :map :seon.error/value]]` | `receipt-settle-tx` | **blocker** — silently drops a program fact |
| `resolve-namespace-name` | `:528` | `db`, `namespace-ref` (vector lookup-ref or eid) | the symbol, `nil`, or `nil` from an error map | **YES — `:533`** `(:seon.ns/name (db/pull …))` | no | `[:=> [:cat :seon.db/database-value [:or :seon.db/entity-id :vector]] [:or :nil :seon.ns/name :seon.error/value]]` | `plan-call`, `record-evaluated-call`, `record-evaluated-tx` | blocker |
| `current-transaction-instant` | `:537` | `db` | `:db/txInstant` or `nil` | **YES — `:540`** `(:db/txInstant (db/pull db … (inc (db/basis-t db))))`; `basis-t` can itself return an error, and `(inc error)` throws | throws | `[:=> [:cat :seon.db/database-value] [:or :nil inst? :seon.error/value]]` | `plan-call`, `system-run-call` | blocker |
| `max-episode-runs` | `:2727` | `database`, `agent-id` | budget long, override, cluster default, `nil` — **or the first query's error map, because `or` treats it as truthy** (`:2730`) | **YES — `:2730`** | yes, accidentally | `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :int :seon.error/value]]` | `turns-left` `:2741` (checks), `opening-deferred?` `:2761` (**does not**) | **blocker** — see F3 |
| `opening-deferred?` | `:2748` | `database`, `agent-id` | boolean | **YES — `:2761`** `(nil? limit)` then `(>= (episode-runs db agent-id) limit)`; an error map as `limit` throws `ClassCastException` **into the turn loop** | no — throws | `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :boolean :seon.error/value]]` | `next-agent-work` `:2753`, `deferred-triggers` | **blocker** |
| `continuing-reply?` | `:2824` | `database`, `agent-id` | boolean or `nil` | **YES — `:2828`** `when-let` on `(max ?t)`; an error map is truthy and is passed as the `?latest-t` **query input** to the next `db/q` | no | `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :boolean :seon.error/value]]` | `next-agent-work`, `resume-or-generate` | blocker |
| `agent-run` | `:2362` | `database`, `agent-id` | turn map, `nil`, error map | yes — returned raw to `next-agent-work` | pass-through | `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :seon.turn/turn :seon.error/value]]` | `next-agent-work`, `virtual-turn!` | blocker |
| `stored-record-content` | `:1486` | `database`, `id` | `{::recorded-run … }` | **YES — `:1494`** `(:db/id run)` from `current-run`; an error map yields `nil`, the query then binds `?run` to `nil` and the record is written **with zero evaluations** | no | `[:=> [:cat :seon.db/database-value :seon.turn/id] [:or :map :seon.error/value]]` | `record-evaluated-call`, `recorded-run` | **blocker** — silent data loss |
| `result-blob-threshold` | `:64` | `db` | long or error map | **YES — `:66`**, returned raw to `stage-reply!`'s size comparison | pass-through | `[:=> [:cat :seon.db/database-value] [:or :nil :int :seon.error/value]]` | `stage-reply!` `:71` (contracted public) | blocker |
| `evaluation-entity-id` | `:4450` | `database`, `evaluation-id` | eid or `nil` | **YES — `:4457`**; docstring says *"Absence is the whole answer"* — an error is also read as absence | no | `[:=> [:cat :seon.db/database-value :seon.cluster.eval/id] [:or :nil :seon.db/entity-id :seon.error/value]]` | `call-turn`, `evaluate-sources` | blocker |
| `next-ordinal` | `:2375` | `database`, run facts | ordinal long or `nil` | yes | no | `[:=> [:cat :seon.db/database-value :seon.turn/id] [:or :nil :seon.cluster.eval/ordinal :seon.error/value]]` | `fold-or-close`, `resume-or-generate` | blocker |
| `declaration-written-by-run?` | `:1121` | `db`, identity attribute + value, `run-id`; queries `(db/history db)` | boolean — `(boolean error-map)` is **`true`** | **YES — `:1128`** `boolean` wraps the raw `db/q` result | no | `[:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value :seon.turn/id] [:or :boolean :seon.error/value]]` | `declaration-diverged-since-open?` `:1140`, `declared-content` | **blocker** — a failed read means "the run wrote it", which suppresses the divergence refusal |
| `row-tx` | `:1228` | `db`, `request`, reader-produced `row` | tx-data vector or a refusal | yes, via `declaration-projection` | yes | `[:=> [:cat :seon.db/database-value :map :map] [:or :vector :seon.error/value]]` | `receipt-settle-call` | blocker |
| `pending-relation-resolution-tx` | `:1183` | `db`, identity attribute/value, `existing` | tx-data vector | **YES — `:1192`/`:1198`** `mapcat` over a `db/q` result; an error map yields retract/add pairs built from MapEntries | no | `[:=> [:cat :seon.db/database-value :qualified-keyword :seon.schema/value [:maybe :map]] [:or :vector :seon.error/value]]` | `row-tx`, `relation-assertions` | blocker |
| `identity-ref` | `:1078` | `db`, `value` (vector / map / eid) | `[identity-attribute value]`, or `value` unchanged | **YES — `:1085`** `(get (db/pull …) identity-attribute)` inside `some` | no | `[:=> [:cat :seon.db/database-value :seon.schema/value] :seon.schema/value]` | `declared-one`, `identity-tombstone-rows`, `preserved-evidence-tx`, `data-link` | friction |
| `current-schema-data-attributes` | `:1004` | `db`, projection, schema keys | vector of attributes | **YES — `:1012`** `(seq (db/datoms db :aevt %))`; an error map is `seq`-able and non-empty, so an unreadable index reports **data present** and refuses the schema change | no | `[:=> [:cat :seon.db/database-value :seon.schema/projection [:set :qualified-keyword]] [:or [:vector :qualified-keyword] :seon.error/value]]` | `assert-schema-data-unused!` `:1018` | friction (fails closed) |
| `latest-evaluations` | `:1980` | `database`, `agent-id` | map keyed by source-key | **YES — `:1999`** `(sort-by (juxt first second) (db/q …))` over an error map | no | `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :map :seon.error/value]]` | `system-turn`, `declared-sources` | blocker (context generation) |
| `read-only-evaluation?` | `:2014` | `database`, pulled evaluation | boolean | **YES — `:2022`/`:2026`** `(nil? (db/q …))`; an error map is not nil, so a failed read says "not read-only", which suppresses the since-diff | no | `[:=> [:cat :seon.db/database-value :map] [:or :boolean :seon.error/value]]` | `latest-evaluations`, `system-plan` | blocker |
| `issue-origin-read?` | `:2072` | `database`, `source` map | boolean or `nil` | **YES — `:2074`** `(some? (:seon.issue/agent (db/pull …)))` | no | `[:=> [:cat :seon.db/database-value :map] [:or :nil :boolean :seon.error/value]]` | `system-turn`, `system-plan`, `generated-read-fault` | friction |
| `refuse!` | `:301` | `transition`, `rule`, `request` | **never returns — throws `ex-info`** carrying `:seon.error/kind ::refused` | n/a | no — throws by design (aborts the serial writer's transaction) | `[:=> [:cat :keyword :keyword :map] :nil]` with the docstring stating it never returns | every `*-call` writer function | cleanup — contract it last; the throw is the mechanism |

`seon.turn`'s remaining spec-less privates that read the database —
`run-receipts` `:1788`, `attempts` `:3772`, `form-run-id` `:2507`,
`assignment-facts` `:2516`, `agent-eid` `:2630`, `fold-evaluations` `:3997`,
`fold-namespace` `:4030`, `evaluation-terminal-data` `:3416`,
`record-attempt!` `:3870`, `settle-batch!` `:3531`,
`settle-batch-refusal!` `:3649`, `open-turn` `:4071`, `call-turn` `:4136`,
`close-turn` `:4791`, `generate-turn` `:4805`, `declaration-diverged-since-open?` `:1140` —
repeat the same two shapes: a raw `db/q`/`db/pull` result bound and consumed,
or a `db/transact!` report bound without checking `:seon.error/kind`. Their
contract candidates are mechanical variants of the rows above.

### 2.2 `seon.cluster` — boot, activation, adoption, fault commit

| Symbol | `file:line` | Consumes | Returns | Read result used before an error check | Flat error | Contract candidate | Callers at risk | Risk |
|---|---|---|---|---|---|---|---|---|
| `missing-process-rows` | `src/seon/cluster.clj:1073` | `db` | vector of `{:seon.db.process/id …}` | **YES — `:1076`** `(into #{} (db/q …))`; an error MAP into a set becomes a set of `MapEntry`, so **every** process identity reads as missing and is re-minted | no | `[:=> [:cat :seon.db/database-value] [:or [:vector :map] :seon.error/value]]` | `declaration-changes`, boot population | **blocker** |
| `closure-fact-missing` | `:1458` | `database`, activation closure, lookup rows | vector of missing facts | **YES — `:1469`/`:1475`** two `(into #{} (db/q …))`; a failed read makes every schema key and every symbol read as absent, so activation refuses with a fabricated diff. Also reads `(:schema database)` directly `:1473` | no | `[:=> [:cat :seon.db/database-value :map [:vector :map]] [:or [:vector :map] :seon.error/value]]` | `require-activation!` | **blocker** |
| `transact-initialization!` | `:1247` | `connection`, `rows` | `nil` or a refusal | **YES — `:1256`** `(:db/id (db/pull database [:db/id] [attribute value]))` decides readiness; a read error makes every row "not ready" and the loop refuses *"Initialization lookup refs do not resolve"* — the exact mis-report class of the open issue note | yes (via `refused!`) | `[:=> [:cat :seon.db/connection [:vector :map]] [:or :nil :seon.error/value]]` | `populate-source!` | **blocker** |
| `stored-activation` | `:1434` | `database` | `{:seon.activation/closure … :seon.activation/lookup-rows …}` or `nil` | **YES — `:1436`** `when-let` on `db/q`, then `db/pull` on an error map as the eid | no | `[:=> [:cat :seon.db/database-value] [:or :nil :map :seon.error/value]]` | `require-activation!` | blocker |
| `schema-row-changes` | `:1144` | `db`, forms | vector of desired rows | `some->` at `:1155` guards `nil` but **not** an error map; `(dissoc error :db/id)` becomes the "current" row and the convergence comparison is against a refusal | no | `[:=> [:cat :seon.db/database-value [:sequential :map]] [:or [:vector :map] :seon.error/value]]` | `accrete-schema-population!` | blocker |
| `program-currentness` | `:1852` | `db` | coherence map | **YES — `:1857`** `(into #{} (db/q …))` of digests; also `(contains? (:schema db) …)` direct | no | `[:=> [:cat :seon.db/database-value] [:or :map :seon.error/value]]` | `require-coherent-program!` | blocker |
| `recover-runs!` | `:2518` | `connection` | `nil` or the committed report | **YES — `:2523`** `open-runs` from `db/q` is `mapcat`'d at `:2537`; boot recovery over an error map recovers **nothing** and reports success | yes via `require-committed!` | `[:=> [:cat :seon.db/connection] [:or :nil :map :seon.error/value]]` | `stand-cluster-runtime!` | **blocker** — a silent recovery no-op is the "absence as health" law verbatim |
| `tagged-run` | `:2816` | `db`, `agent-id` | turn id or `nil` | **YES — `:2823`**, returned raw to `commit-fault!` | no | `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :seon.turn/id :seon.error/value]]` | `commit-fault!` | blocker — an unattributed fault |
| `commit-fault!` | `:2841` | `connection`, fault value | transaction report | binds `db/transact!` report | yes | `[:=> [:cat :seon.db/connection :map] [:or :map :seon.error/value]]` | `acquire!`, `arm-agents!`, `serve!` | blocker |
| `ref-identity` | `:159` | `database`, `ref` map, `attribute` | attribute value or `nil` | **YES — `:162`** `(get (db/pull …) attribute)` | no | `[:=> [:cat [:maybe :seon.db/database-value] [:maybe :map] :qualified-keyword] [:or :nil :seon.schema/value]]` | `format-ai`, `render-html`, `shallow-entity` | friction — **and see F4: byte-identical twin in `seon.effect`** |
| `development-source-refresh!` | `:2280` | `connection`, changed paths | adoption result | binds pull/q/transact! results | yes | `[:=> [:cat :seon.db/connection [:vector :string]] [:or :map :seon.error/value]]` | `refresh-source!` | blocker (edit-hook path) |
| `accrete-schema-population!` | `:1558` | `connection`, projection | transaction report or refusal | binds `db/transact!` | yes | `[:=> [:cat :seon.db/connection :map] [:or :map :seon.error/value]]` | `stand-boot-layers!`, `require-admissible-branch!` | blocker |
| `count-installed` | `:1840` | `db`, attribute | long | error map counted as a row count | yes | `[:=> [:cat :seon.db/database-value :qualified-keyword] [:or :int :seon.error/value]]` | `program-currentness` | friction |
| `seed-root-agent!` | `:2720`, `instruction-row-changes` `:1171`, `activation-requirements` `:1185`, `current-publication` `:1951`, `namespace-requires` `:2216`, `previously-reported-fault-signature?` `:2833`, `mcp-project` `:346` | — | — | same two shapes | — | mechanical | — | blocker/friction |

### 2.3 `seon.sci.eval`, `seon.sci.admit`, `seon.sci.kernel` — the SCI context

| Symbol | `file:line` | Consumes | Returns | Read result used before an error check | Flat error | Contract candidate | Callers at risk | Risk |
|---|---|---|---|---|---|---|---|---|
| `remaining-definition-facts` | `src/seon/sci/eval.clj:721` | `db`, `[identity-attribute identity-value]` | `{:seon.program/identity … :seon.program/definition-attributes …}` or `nil` | **YES — `:722`** `when-let` on `db/pull`; an error map is truthy, `(dissoc row :db/id :seon.schema.admission/source)` then yields a "definition" made of `:seon.error/*` keys which `program/changed-attributes` treats as attributes | no | `[:=> [:cat :seon.db/database-value [:tuple :qualified-keyword :seon.schema/value]] [:or :nil :map :seon.error/value]]` | retraction/redefinition path | **blocker** — a read refusal becomes program facts |
| `acquire-program!` | `:1606` | `database`, options | the acquired context or a refusal | binds pull + q results | yes | `[:=> [:cat :seon.db/database-value :map] [:or :map :seon.error/value]]` | `base-ctx` `:1709` | **blocker** — acquisition is the boot gate |
| `load-core-namespaces!` | `:1189` | `database` | `nil` (side-effecting) | **YES — `:1193`** `(doseq [namespace-name (sort-by str (db/q …))])`; an error map iterates as MapEntries and `host-namespace!` is called on each | no — throws | `[:=> [:cat :seon.db/database-value] [:or :nil :seon.error/value]]` | `cluster-ctx*` | **blocker** |
| `run-candidate-test!` | `:2975` | `ctx`, `database`, `request`, `test-symbol` | evaluation result map | **YES — `:2977`** `(:seon.test/source row)` and `(get-in row [:seon.test/ns :seon.ns/name])`; a read error evaluates `nil` source in a `nil` namespace | yes (evaluation error) | `[:=> [:cat :seon.sci.eval/ctx :seon.db/database-value :map :seon.fn/sym] [:or :map :seon.error/value]]` | `evaluate-candidate` | blocker |
| `latest-print-fact` | `:1882` | `db`, `agent-id`, `attribute` | the value or `nil` | **YES — `:1885`** `(seq (db/q …))` then `(second (last (sort-by first rows)))`; an error map is a non-empty seq | no | `[:=> [:cat :seon.db/database-value :seon.agent/id :qualified-keyword] [:or :nil :seon.schema/value :seon.error/value]]` | `session-print-options` | friction |
| `program-documentation` | `:1285` | `db`, symbol | doc rows | `db/q` result consumed directly; feeds `doc`/`dir` agent output | no | `[:=> [:cat :seon.db/database-value :seon.fn/sym] [:or [:sequential :map] :seon.error/value]]` | `directory-value` | friction |
| `record-acquisition-refusals!` | `:1555` | `connection`, refusals | transaction report | binds `db/transact!` | yes | `[:=> [:cat :seon.db/connection [:vector :map]] [:or :map :seon.error/value]]` | `acquire!`, `cluster-ctx*` | **blocker** — the refusal recorder failing silently is the worst possible silence |
| `shown-result` | `:2315` | `database`, evaluation | shown text | binds `db/pull` | yes | `[:=> [:cat :seon.db/database-value :map] [:or :string :seon.error/value]]` | `evaluate`, `failure-text` | blocker |
| `seon.sci.kernel/own-arm` `:230`, `current-thread-arm` `:267`, `same-interpreter?` `:272`, `new-guard` `:57`, `new-armed` `:211`, `record` `:188`, `allocated-bytes` `:43` | — | guard/arm state, thread-local | boolean / guard record / long | no database read; these are the **bounded-execution** seam (`:interrupt-fn`, `time-limit`) | no | `[:=> [:cat :seon.sci.kernel/guard] :boolean]` etc. — needs `resources/seon/schemas/seon.sci.kernel.edn` keys, which exist | `evaluate`, `interrupt!` | friction — contract these: a wrong guard identity is an unbounded evaluation |
| `seon.sci.admit`'s 22 privates (`admit*` `:695`, `admit-walk` `:712`, `project` `:424`, `leaf!` `:182`, `over-bound?` `:139`, `failed-node!` `:389`, `rethrow-or-degrade!` `:400`, `missing-bound-refusal` `:652`, …) | `src/seon/sci/admit.clj` | walk frames, bound config, the value being admitted | nodes, elisions, refusals | no database read | `missing-bound-refusal` `:652` and `failed-node!` `:389` **do** return flat errors | `[:=> [:cat :seon.sci.admit/frame …] [:or :seon.sci.admit/node :seon.error/value]]` | `admit` | friction — this is the bounded-output seam; `over-bound?` `:139` returning a wrong boolean is an unbounded admission |

### 2.4 `seon.effect` — the capability boundary

| Symbol | `file:line` | Consumes | Returns | Read result used before an error check | Flat error | Contract candidate | Callers at risk | Risk |
|---|---|---|---|---|---|---|---|---|
| `evaluation-eid` | `src/seon/effect.clj:232` | `database`, effect receipt | eid or `nil` | **YES — `:242`** `(:seon.turn/id (db/pull …))` then `:245` `(:db/id (db/pull …))`; both errors read as "made outside a recorded evaluation" — the docstring's benign case | no | `[:=> [:cat :seon.db/database-value :map] [:or :nil :seon.db/entity-id :seon.error/value]]` | `open-call`, `declared-datoms` | **blocker** — provenance silently dropped |
| `capability-fn-eid` | `:249` | `database`, receipt | eid or `nil` | **YES — `:252`** `some->` guards `nil` only | no | `[:=> [:cat :seon.db/database-value :map] [:or :nil :seon.db/entity-id :seon.error/value]]` | `open-call` | blocker |
| `write-back-adds` | `:280` | `database`, receipt | tx-data | binds pull + q | no | `[:=> [:cat :seon.db/database-value :map] [:or :vector :seon.error/value]]` | `open-call`, `settle-call` | blocker |
| `settle-value!` | `:536` | `connection`, effect id, value | `{:seon.effect/value … :seon.effect/transaction …}` | binds `db/transact!` report as `:seon.effect/transaction` **unchecked** `:539` | yes, inside the map | `[:=> [:cat :seon.db/connection :seon.effect/id :seon.schema/value] [:or :map :seon.error/value]]` | `request*`, `settle-background-terminal!` | **blocker** — a lost settlement leaves an effect pending forever |
| `interrupt!` | `:579` | `connection`, effect id, optional value | same shape | same — `:590` | yes | same as above with an optional third argument | `request*`, `settle-value!` | blocker |
| `ref-attribute` | `:45` | `database`, `ref`, `attribute` | value or `nil` | **YES — `:48`** | no | `[:=> [:cat [:maybe :seon.db/database-value] [:maybe :map] :qualified-keyword] [:or :nil :seon.schema/value]]` | `receipt-identities` | friction — **F4 twin** |
| `handler-failure` `:596`, `receipt-state` `:50`, `payload-face` `:56` | — | flat values | error value / keyword / string | n/a | `handler-failure` yes | mechanical | — | cleanup |

### 2.5 `seon.plan` — the agent-facing plan writer

| Symbol | `file:line` | Consumes | Returns | Read result used before an error check | Flat error | Contract candidate | Callers at risk | Risk |
|---|---|---|---|---|---|---|---|---|
| `next-position` | `src/seon/plan.clj:520` | `database`, `owner` eid, `attribute` | long | **YES — `:522`** `(inc (long (or (db/q …) -1)))`; an error map is truthy through `or`, and `(long <map>)` **throws `ClassCastException`** on an agent's `my.plan` write | no — throws | `[:=> [:cat :seon.db/database-value :seon.db/entity-id :qualified-keyword] [:or :int :seon.error/value]]` | `add-step-call`, `add-note-call` | **blocker** |
| `agent-eid` `:104`, `plan-eid` `:111`, `step-eid` `:117`, `subject-eid` `:128` | `:104`–`:135` | `database` + one identity value | eid or `nil` | **YES** — each returns `db/q` raw; every caller treats non-`nil` as an eid and passes it as a query input or a ref in tx-data | no | `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :nil :seon.db/entity-id :seon.error/value]]` (variants per identity) | every `*-call` in the namespace | **blocker** — an error map as a ref in transaction data |
| `ref-eid` | `:124` | `database`, reference | eid or `nil` | `some->` on `db/entity` — `db/entity` on a bad ref can error | no | `[:=> [:cat :seon.db/database-value :seon.schema/value] [:or :nil :seon.db/entity-id :seon.error/value]]` | `owned-step-eid!` `:504` | blocker |
| `transact-plan!` | `:495` | `connection`, `agent-id`, tx-data | transaction report | returns `db/transact!` raw — **correct**, the caller is agent-facing | yes | `[:=> [:cat :seon.db/connection :seon.agent/id [:vector :any]] [:or :map :seon.error/value]]` | every `my.plan` write | friction |
| `owned-step-eid!` | `:502` | `database`, agent eid, reference, member | eid — **or throws** via `refuse!` `:88` | **YES — `:509`** `(db/q … rules agent-entity step)`; an error map is truthy, so an unreadable database reports the step as **owned** | no — throws | `[:=> [:cat :seon.db/database-value :seon.db/entity-id :seon.schema/value :qualified-keyword] [:or :seon.db/entity-id :seon.error/value]]` | `complete-step-call`, `start-step-call` | **blocker** — ownership fails *open* |
| `completion-tx` `:714`, `complete-step-call` `:748`, `start-step-call` `:823`, `scalar-retractions` `:945`, `query-deadline` `:577` | — | — | tx-data / booleans | same shapes | some | mechanical | — | blocker/friction |

### 2.6 `seon.env`, `seon.cluster.wake`, `seon.cluster.message`, `seon.cluster.prompt`, `seon.cluster.source`

| Symbol | `file:line` | Consumes | Returns | Notes | Contract candidate | Risk |
|---|---|---|---|---|---|---|
| `seon.env/construct` | `src/seon/env.clj:204` | `supplied` map, `boot?` boolean | the environment value **or** one of two flat errors (`::invalid-member` `:207`, `::incomplete-environment` via `:194`) | Law 2.1's constructor. No database read. **`resources/seon/schemas/seon.env.edn` registers neither error shape** — no `:seon.env/incomplete-environment` or `:seon.env/invalid-member` key exists, unlike every `seon.fn/*-error` in `seon.fn.edn` | **none registered — needs `:seon.env/incomplete-environment-error` and `:seon.env/invalid-member-error`** declared as `{:seon.error/class true}` maps, then `[:=> [:cat :map :boolean] [:or :seon.env/environment :seon.error/value]]` | **blocker** — the one constructor every layer's refusal flows through has no declared refusal shape |
| `seon.env/absent-member-error` | `:194` | entry map, `supplied` | a flat error | pure error constructor | `[:=> [:cat :map :map] :seon.error/value]` | friction |
| `seon.env/map-entries` | `:129` | a Malli definition vector | seq of entries or `nil` | pure; recursive `some` at `:137` | `[:=> [:cat :any] [:or :nil [:sequential :vector]]]` — a genuine Malli-form boundary, so `:any` is justified here and must say why | cleanup |
| `seon.cluster.wake/deliver!` `:382`, `wake-matchers` `:402` | `src/seon/cluster/wake.clj` | notification value, declarations | delivery result / matcher map | no direct `seon.db` read; `deliver!` is the wake delivery seam | `[:=> [:cat :map] [:or :nil :seon.error/value]]` | friction |
| `seon.cluster.message/error-value?` | `src/seon/cluster/message.clj:486` | any value | boolean | `(and (map? value) (keyword? (:seon.error/kind value)))` — the **seventh** private copy of this predicate in `src/` (see F5). It is the predicate §1j's "a contracted consumer refuses it by shape" depends on, and it is private everywhere | `[:=> [:cat :any] :boolean]` — *but the repair is one public owner, not seven contracts; see F5* | friction (F5 is blocker) |
| `seon.cluster.message/agent-exists?` `:140`, `caused-by` `:37`, `agent-reference-id` `:224`, `identity-reference` `:244`, `message-instant` `:276`, `recipient-eid` `:523`, `inbox-message-eids` `:530`, `inbox*` `:538`, `send-call` `:644` | `src/seon/cluster/message.clj` | database value + one identity | eids / instants / maps | `agent-exists?` `:141` is the sharpest: a `db/q` error map is truthy, so **an unreadable database says the recipient exists** and the message is delivered to nothing | `[:=> [:cat :seon.db/database-value :seon.agent/id] [:or :boolean :seon.error/value]]` etc. | **blocker** for `agent-exists?`, blocker for the eid readers |
| `seon.cluster.prompt/config-cluster-name` `:37`, `calibration-for` `:66`, `capture-mismatch` `:312` | `src/seon/cluster/prompt.clj` | database value, model/agent id | names, calibration maps | prompt assembly reading raw `db/q` results; a failed calibration read silently changes what a **paid** model call is sized against | `[:=> [:cat :seon.db/database-value] [:or :nil :string :seon.error/value]]` etc. | blocker — spends money |
| `seon.cluster.source/identity-rows` | `src/seon/cluster/source.clj:339` | `database-value`, identity vectors | `{identity row}` map — **or throws** | **The one correct example in this audit**: `:342` checks `(:seon.error/kind rows)` on the `db/pull-many` result before using it. It throws `ex-info` rather than returning the value, which §1j's "errors stay values" would revisit | `[:=> [:cat :seon.db/database-value [:sequential :seon.program/identity]] [:or :map :seon.error/value]]` | cleanup — the check is right, the throw is the open question |
| `seon.cluster.source/activation-seal-tx` `:222`, `result-preservation-tx` `:396` | `src/seon/cluster/source.clj` | database value, identity vectors | tx-data | Unchecked `db/pull`/`db/q`/`db/pull-many` results. `result-preservation-tx` `:396` pulls many — **and Datahike's pull silently truncates a cardinality-many result at 1,000 members** (`reference-code/datahike/src/datahike/pull_api.cljc:16`, `:315`, `:323`), which a publication identity set can exceed | `[:=> [:cat :seon.db/database-value [:vector :vector]] [:or [:vector :map] :seon.error/value]]` | **blocker** |

## 3. The remaining private functions, one line each

357 private, spec-less functions in this domain call no `seon.db` read
directly. They are still in scope under §1j; none is a blocker on its own.

- **`seon.cluster`** (65): `report-source-progress!`&nbsp;`:86`, `report-analysis-warnings!`&nbsp;`:99`, `boot-phase`&nbsp;`:226`, `consume-mcp-projection!`&nbsp;`:265`, `mcp-instance`&nbsp;`:271`, `mcp-effective`&nbsp;`:275`, `nil-deref?`&nbsp;`:302`, `exception-summary`&nbsp;`:306`, `mcp-projection-error`&nbsp;`:337`, `refused!`&nbsp;`:600`, `source-change-phase`&nbsp;`:621`, `retrying-source-change`&nbsp;`:633`, `require-candidate-value`&nbsp;`:664`, `server-name`&nbsp;`:757`, `reserve-cluster!`&nbsp;`:761`, `release-reservation!`&nbsp;`:773`, `create-directories!`&nbsp;`:781`, `require-cluster-target!`&nbsp;`:787`, `operator-root`&nbsp;`:799`, `warn-low-space!`&nbsp;`:837`, `write-advertisement!`&nbsp;`:872`, `root-store-key`&nbsp;`:885`, `acquire-root-store!`&nbsp;`:890`, `release-root-store!`&nbsp;`:925`, `incompatible-declaration-message`&nbsp;`:951`, `accretive-property-change?`&nbsp;`:965`, `declaration-property-changes`&nbsp;`:1000`, `declaration-changes`&nbsp;`:1029`, `schema-lookup-ref?`&nbsp;`:1088`, `schema-reference-valued?`&nbsp;`:1094`, `as-schema-lookup-refs`&nbsp;`:1099`, `store-comparable-value`&nbsp;`:1113`, `schema-row-converged?`&nbsp;`:1127`, `lookup-refs-in`&nbsp;`:1223`, `activation-lookup-row`&nbsp;`:1238`, `pulled-closure`&nbsp;`:1423`, `require-admissible-branch!`&nbsp;`:1521`, `publication-roots`&nbsp;`:1703`, `current-source-snapshot`&nbsp;`:1773`, `publish-current-source!`&nbsp;`:1777`, `current-source!`&nbsp;`:1789`, `require-coherent-program!`&nbsp;`:1875`, `read-source-artifact`&nbsp;`:1913`, `write-source-artifact!`&nbsp;`:1923`, `source-artifact`&nbsp;`:1944`, `valid-source-manifest?`&nbsp;`:1976`, `stable-manifest`&nbsp;`:1983`, `full-source-refresh!`&nbsp;`:2018`, `changed-source-paths`&nbsp;`:2032`, `incremental-source-refresh!`&nbsp;`:2041`, `reloadable-namespace?`&nbsp;`:2233`, `acquire-development!`&nbsp;`:2243`, `adoption-identities`&nbsp;`:2271`, `require-committed!`&nbsp;`:2559`, `serve!`&nbsp;`:2740`, `single-line-fault-text`&nbsp;`:2936`, `emit-core-fault!`&nbsp;`:2942`, `loop-handle`&nbsp;`:2967`, `cluster-graph-definition`&nbsp;`:3037`, `arm-agents!`&nbsp;`:3072`, `disarm-agents!`&nbsp;`:3229`, `stand-cluster-runtime!`&nbsp;`:3318`, `stand-boot-layers!`&nbsp;`:3394`, `active-instance?`&nbsp;`:3609`, `claim-stop!`&nbsp;`:3615`
- **`seon.cluster.agent`** (1): `await-turn-completion!`&nbsp;`:767`
- **`seon.cluster.export`** (7): `refuse!`&nbsp;`:94`, `warn!`&nbsp;`:103`, `clone-command`&nbsp;`:121`, `clone!`&nbsp;`:127`, `retransact!`&nbsp;`:146`, `copy-store!`&nbsp;`:200`, `reidentify-at!`&nbsp;`:239`
- **`seon.cluster.message`** (7): `data-link`&nbsp;`:268`, `message-order`&nbsp;`:429`, `error-value?`&nbsp;`:486`, `endpoint-id`&nbsp;`:490`, `admitted-message`&nbsp;`:494`, `listing-entry`&nbsp;`:514`, `send-value`&nbsp;`:596`
- **`seon.cluster.prompt`** (11): `effective-ai-settings`&nbsp;`:44`, `refuse!`&nbsp;`:136`, `missing-required-keys`&nbsp;`:144`, `validate-request!`&nbsp;`:153`, `history-contributions`&nbsp;`:165`, `priced-unit`&nbsp;`:204`, `retained-count`&nbsp;`:209`, `dropped-elision`&nbsp;`:230`, `selection-segments`&nbsp;`:249`, `selection-of`&nbsp;`:272`, `acquire-context-report`&nbsp;`:336`
- **`seon.cluster.registry`** (14): `refuse!`&nbsp;`:80`, `konserve-store`&nbsp;`:124`, `head-record`&nbsp;`:127`, `commit-present?`&nbsp;`:148`, `branch-connected?`&nbsp;`:166`, `blob-digest-attributes`&nbsp;`:324`, `branch-blobs`&nbsp;`:353`, `branch-heads`&nbsp;`:387`, `elapsed-ms`&nbsp;`:403`, `physical-filestore-inventory`&nbsp;`:407`, `refuse-missing-candidates!`&nbsp;`:458`, `dry-run-complete?`&nbsp;`:471`, `dry-run!`&nbsp;`:481`, `collect-and-inventory!`&nbsp;`:536`
- **`seon.cluster.reply`** (14): `prose-line`&nbsp;`:36`, `refused`&nbsp;`:45`, `parsed-events`&nbsp;`:55`, `standalone-symbol?`&nbsp;`:89`, `form-start`&nbsp;`:100`, `strip-prompt-markers`&nbsp;`:115`, `structured-code-indexes`&nbsp;`:131`, `code-event-indexes`&nbsp;`:156`, `comment-source`&nbsp;`:169`, `plan-sources`&nbsp;`:177`, `code-line?`&nbsp;`:231`, `readable-code-suffix`&nbsp;`:236`, `top-level-failure?`&nbsp;`:254`, `comment-prose-failure`&nbsp;`:266`
- **`seon.cluster.source`** (15): `refuse!`&nbsp;`:79`, `require-committed!`&nbsp;`:87`, `source-file?`&nbsp;`:94`, `scratch-branch`&nbsp;`:195`, `resolve-population`&nbsp;`:202`, `resolve-activation`&nbsp;`:212`, `retire-scratch!`&nbsp;`:291`, `identity-tempid`&nbsp;`:298`, `identity-namespace-tempid`&nbsp;`:301`, `mintable-identity`&nbsp;`:304`, `evidence-identities`&nbsp;`:451`, `preserved-evidence-tx`&nbsp;`:462`, `record-results-at-head!`&nbsp;`:510`, `index-issues!`&nbsp;`:580`, `assert-scalar-rows!`&nbsp;`:697`
- **`seon.cluster.status`** (6): `unknown`&nbsp;`:13`, `boot-time`&nbsp;`:17`, `utf8-size`&nbsp;`:20`, `thread-counts`&nbsp;`:23`, `usage`&nbsp;`:89`, `sum-known`&nbsp;`:94`
- **`seon.cluster.store`** (10): `fresh-file-lock`&nbsp;`:98`, `canonical-path`&nbsp;`:137`, `open-configuration`&nbsp;`:194`, `refuse!`&nbsp;`:203`, `acquire-flock!`&nbsp;`:306`, `release-flock!`&nbsp;`:342`, `genesis-complete?`&nbsp;`:362`, `stored-main-keep-history?`&nbsp;`:368`, `complete-store?`&nbsp;`:375`, `create-store!`&nbsp;`:385`
- **`seon.cluster.wake`** (2): `deliver!`&nbsp;`:382`, `wake-matchers`&nbsp;`:402`
- **`seon.effect`** (17): `receipt-state`&nbsp;`:50`, `payload-face`&nbsp;`:57`, `receipt-identities`&nbsp;`:61`, `flat-error`&nbsp;`:166`, `owner-symbol`&nbsp;`:173`, `accepts-request?`&nbsp;`:186`, `admission`&nbsp;`:196`, `admitted-value`&nbsp;`:204`, `declared-datoms`&nbsp;`:211`, `dispatching-environment`&nbsp;`:420`, `with-request-context`&nbsp;`:438`, `dispatch`&nbsp;`:470`, `staged-result`&nbsp;`:514`, `handler-failure`&nbsp;`:595`, `background-settlement-request`&nbsp;`:601`, `settle-background-terminal!`&nbsp;`:616`, `background-time-limit`&nbsp;`:633`
- **`seon.env`** (3): `map-entries`&nbsp;`:129`, `absent-member-error`&nbsp;`:194`, `construct`&nbsp;`:204`
- **`seon.plan`** (32): `error-value?`&nbsp;`:84`, `refuse!`&nbsp;`:88`, `flat-refusal`&nbsp;`:97`, `resolve-subject!`&nbsp;`:149`, `tree-nodes`&nbsp;`:173`, `open-work?`&nbsp;`:181`, `derived-frontier`&nbsp;`:211`, `sibling-order`&nbsp;`:249`, `stable-reference`&nbsp;`:253`, `step-state`&nbsp;`:257`, `derived-steps`&nbsp;`:267`, `completion-view`&nbsp;`:298`, `step-summary`&nbsp;`:420`, `add-step-call`&nbsp;`:528`, `query-satisfied?`&nbsp;`:710`, `changed-item`&nbsp;`:784`, `update-step-call`&nbsp;`:849`, `input-entries`&nbsp;`:876`, `refuse-duplicate-identities!`&nbsp;`:896`, `refuse-duplicate-positions!`&nbsp;`:905`, `refuse-dependency-cycle!`&nbsp;`:918`, `entry-tx-map`&nbsp;`:940`, `document-reference-id`&nbsp;`:964`, `comparable`&nbsp;`:984`, `entry-comparable`&nbsp;`:1026`, `outline-numbers`&nbsp;`:1222`, `state-word`&nbsp;`:1237`, `step-line`&nbsp;`:1246`, `refusal-line`&nbsp;`:1259`, `item-html`&nbsp;`:1288`, `update-example`&nbsp;`:1355`, `current-title`&nbsp;`:1374`
- **`seon.sci.admit`** (22): `utf8-length`&nbsp;`:97`, `write!`&nbsp;`:118`, `over-bound?`&nbsp;`:139`, `value-node`&nbsp;`:147`, `sci-named`&nbsp;`:152`, `class-name`&nbsp;`:164`, `object-node`&nbsp;`:171`, `leaf!`&nbsp;`:182`, `items-frame`&nbsp;`:195`, `open-node`&nbsp;`:201`, `frame-deliver`&nbsp;`:319`, `frame-advance`&nbsp;`:332`, `failed-node!`&nbsp;`:389`, `rethrow-or-degrade!`&nbsp;`:400`, `project`&nbsp;`:424`, `semantic-leaf`&nbsp;`:479`, `semantic-parts`&nbsp;`:499`, `semantic-combine`&nbsp;`:523`, `unserializable-root?`&nbsp;`:594`, `missing-bound-refusal`&nbsp;`:652`, `admit*`&nbsp;`:695`, `admit-walk`&nbsp;`:712`
- **`seon.sci.eval`** (56): `evaluation-output`&nbsp;`:288`, `row`&nbsp;`:299`, `removed-program-identities`&nbsp;`:316`, `intern-values`&nbsp;`:334`, `turn-interns`&nbsp;`:353`, `turn-intern-values`&nbsp;`:382`, `same-intern-value?`&nbsp;`:388`, `definition-row`&nbsp;`:397`, `bindings`&nbsp;`:444`, `deleted-schema-key`&nbsp;`:457`, `reader-context`&nbsp;`:464`, `one-event`&nbsp;`:481`, `binding-rows`&nbsp;`:534`, `row-bindings`&nbsp;`:567`, `program-row-identity`&nbsp;`:588`, `namespace-context-row`&nbsp;`:597`, `context-projection`&nbsp;`:613`, `evaluation-projection`&nbsp;`:619`, `advance-context-projection!`&nbsp;`:628`, `instrumentation-config`&nbsp;`:660`, `install-function-contract!`&nbsp;`:675`, `declaration-source-value`&nbsp;`:738`, `same-declaration-source?`&nbsp;`:749`, `install-jvm-root!`&nbsp;`:795`, `transfer-evaluated-roots!`&nbsp;`:953`, `classpath-locatable?`&nbsp;`:1110`, `host-namespace!`&nbsp;`:1149`, `install-first-party-namespaces!`&nbsp;`:1205`, `install-host-namespace!`&nbsp;`:1267`, `documentation-unavailable`&nbsp;`:1296`, `declaration-statement`&nbsp;`:1302`, `declaration-absent`&nbsp;`:1307`, `documentation-schemas`&nbsp;`:1330`, `documentation-contract`&nbsp;`:1338`, `agent-documentation-contract`&nbsp;`:1358`, `function-doc-map`&nbsp;`:1387`, `program-doc-var`&nbsp;`:1463`, `program-dir-var`&nbsp;`:1478`, `install-program-doc!`&nbsp;`:1489`, `install-declared-classes!`&nbsp;`:1501`, `acquisition-refusal`&nbsp;`:1515`, `acquisition-refusal-id`&nbsp;`:1546`, `session-print-options`&nbsp;`:1895`, `base-bindings`&nbsp;`:1912`, `same-program-root?`&nbsp;`:1927`, `regenerate-agent-context!`&nbsp;`:1933`, `cluster-ctx*`&nbsp;`:2093`, `declared-row`&nbsp;`:2155`, `unmap-row`&nbsp;`:2230`, `failure-text`&nbsp;`:2288`, `success-evaluation`&nbsp;`:2345`, `failed-evaluation`&nbsp;`:2386`, `arity-exception`&nbsp;`:2422`, `interpreted-arity-message`&nbsp;`:2430`, `install-candidate-function!`&nbsp;`:2861`, `candidate-test-result`&nbsp;`:2936`
- **`seon.sci.kernel`** (7): `allocated-bytes`&nbsp;`:43`, `new-guard`&nbsp;`:57`, `record`&nbsp;`:188`, `new-armed`&nbsp;`:211`, `own-arm`&nbsp;`:230`, `current-thread-arm`&nbsp;`:267`, `same-interpreter?`&nbsp;`:272`
- **`seon.turn`** (68): `refuse!`&nbsp;`:301`, `require-open-run`&nbsp;`:317`, `interrupt-stamps`&nbsp;`:339`, `plan-tx-for-author`&nbsp;`:472`, `source-rows`&nbsp;`:542`, `receipt-run`&nbsp;`:809`, `receipt-row`&nbsp;`:839`, `receipt-settle-tx*`&nbsp;`:930`, `affected-schema-attributes`&nbsp;`:997`, `assert-schema-data-unused!`&nbsp;`:1015`, `schema-attribute-change-tx`&nbsp;`:1030`, `cardinality-many?`&nbsp;`:1062`, `component-ref?`&nbsp;`:1067`, `ref-attribute?`&nbsp;`:1071`, `declared-one`&nbsp;`:1092`, `declared-value`&nbsp;`:1099`, `declared-map`&nbsp;`:1105`, `declared-content`&nbsp;`:1113`, `relation-assertions`&nbsp;`:1168`, `declaration-projection`&nbsp;`:1203`, `receipt-gate-test-assertions`&nbsp;`:1425`, `receipt-terminal-assertions`&nbsp;`:1431`, `receipt-read-evidence-tx`&nbsp;`:1441`, `recorded-evaluation`&nbsp;`:1460`, `recorded-run`&nbsp;`:1477`, `receipt-value`&nbsp;`:1795`, `unfinished-warning`&nbsp;`:1802`, `source-events`&nbsp;`:1925`, `source-key`&nbsp;`:1931`, `declared-sources`&nbsp;`:1938`, `system-plan`&nbsp;`:2032`, `generated-read-fault`&nbsp;`:2077`, `evaluable-source?`&nbsp;`:2368`, `terminal-receipt?`&nbsp;`:2499`, `wake-attribute-set`&nbsp;`:2640`, `fold-or-close`&nbsp;`:2797`, `resume-or-generate`&nbsp;`:2812`, `read-source`&nbsp;`:3031`, `clean-source?`&nbsp;`:3039`, `repairable-delimiter-error?`&nbsp;`:3046`, `repaired-span`&nbsp;`:3054`, `repair-source`&nbsp;`:3070`, `repair-sources`&nbsp;`:3092`, `append-output`&nbsp;`:3191`, `gate-function-install`&nbsp;`:3200`, `submission-time-limit-evaluation`&nbsp;`:3283`, `submit-evaluation!!`&nbsp;`:3305`, `error-tx`&nbsp;`:3325`, `asked-value`&nbsp;`:3353`, `delivery-rows`&nbsp;`:3368`, `phase`&nbsp;`:3395`, `closing-settlement?`&nbsp;`:3406`, `refusal-terminal-data`&nbsp;`:3615`, `attempt-id`&nbsp;`:3767`, `attempt-evidence`&nbsp;`:3806`, `attempt-request`&nbsp;`:3836`, `provider-targets`&nbsp;`:3854`, `fold-source`&nbsp;`:4019`, `evaluation-request`&nbsp;`:4045`, `provider-wait-ms`&nbsp;`:4123`, `await-turn-part!`&nbsp;`:4130`, `disposition-rule-error`&nbsp;`:4462`, `resume-turn`&nbsp;`:4635`, `turn-completion-backstop-failure`&nbsp;`:5012`, `await-turn-permit!`&nbsp;`:5017`, `offer-write-refusal-fault!`&nbsp;`:5128`, `offer-turn-backstop-fault!`&nbsp;`:5153`, `arm-turn-completion-backstop!`&nbsp;`:5176`

## 4. Findings

Every issue-note search below was run against `docs/seon/issues/` (405 notes)
before the finding was written.

### F1 — An elided MCP value's requery form names a path its own retrieval tool refuses

**Severity: blocker. No existing issue note** covers this exact seam
(`the-issue-ai-render-no-longer-teaches-its-requery-form.md`,
`a-value-larger-than-the-budget-is-elided-to-nothing.md` and
`dir-of-a-namespace-returns-an-elision-with-nothing-shown.md` are adjacent but
describe different failures).

The census result was elided at 32 of 469 children under
`seon.render.profile/mcp`. The elision value taught this requery identity:

```clojure
[:seon.print/value-at
 [:seon.render.value/artifact-value
  [:seon.render.value/read-artifact
   [:seon.blob/get [:seon.operator/connection "default"]
    "683207a14d4a926118912bfb21289330e05c66827e8bb1dab7a665fa66b62f39"]]]
 ["census"]]
```

`mcp__seon__get_value` with that digest and `path ["census"]` returns

```clojure
{:seon.error/kind :seon.render.data/no-such-path
 :seon.error/message "There is nothing at \"census\" in this value."
 :seon.error/data {:seon.render.data/step "\"census\""}}
```

although `census` is a top-level key of the stored value. With `path []` the
same digest returns the value wrapped in a `seon.render.value/window`
envelope — and re-elided at the same 32 of 469, under a **new** digest
(`1597e016…`). That new digest then refuses **both** `["census"]` and
`["seon.render.value/window" "census"]`. Measured: four `get_value` calls, four
refusals or re-elisions, **437 of 469 rows unreachable**.

The wrong understanding: the elision emits a path relative to the *stored
value*, while `get_value` resolves the path against the *rendered envelope* it
is about to return. One of the two is wrong, and because each retrieval mints
a fresh digest, no amount of paging converges. This is the project's named
failure class inside the retrieval tool itself — a requery form that reports a
path when its subject is present and a `no-such-path` refusal when it is
present too, so the reader cannot tell "wrong path" from "wrong tool". It is
also why §1 of this note had to fall back to static extraction.

### F2 — A database read's error value reads as an **open turn** at the writer's own eligibility check

**Severity: blocker. An issue note exists and is open**:
[`a-database-reads-error-value-is-read-as-a-row-by-its-caller.md`](../../../seon/issues/a-database-reads-error-value-is-read-as-a-row-by-its-caller.md)
(status `open`, severity `blocker`). Its three recorded instances are
`seon.config/effective-in`, `seon.config/population-transaction-data` and
`seon.test.runner/record-tx` — **none in this domain**, and none as severe as
this one. This audit adds the writer.

`seon.turn/current-run` (`src/seon/turn.clj:310`) returns `(db/pull db '[*]
[::id id])`, whose declared output is `[:or :nil :map :seon.error/value]`
(`src/seon/db.clj:1903`). `require-open-run` (`:314`) consumes it:

```clojure
(let [turn (current-run database (::id request))]
  (cond
    (nil? turn) (refuse! operation ::no-such-run request)
    (not (open? turn)) (refuse! operation ::run-closed request)
    :else turn))
```

`open?` (`:214`) is `(not (contains? run ::closed-tx))`. A flat
`:seon.error` map is not `nil` and contains no `::closed-tx`, **so a failed
read is classified as an open turn** and returned as the turn the writer then
transacts against. The docstring at `:311` states the opposite understanding
outright — *"a missing lookup ref pulls to nil rather than throwing, so
absence is an ordinary value"* — which is true of a missing ref and false of a
refused read.

`open?` already carries a contract, and it does not help: its input schema is
`[:map [::closed-tx {:optional true} ::closed-tx]]`, an open map under AGENTS
§2.5, which a `:seon.error` map satisfies. **Arming more contracts of this
shape will not catch this class** — the input schema has to name the turn's
required identity, not an optional absent key.

AGENTS §7 records the orchestrator reading `no-such-run` rejections in the
writer log on 2026-09-17 and attributing them elsewhere. This is a mechanism
that produces exactly that line from a read failure.

### F3 — One function's error value is checked by its public caller and ignored by its private sibling twenty lines away

**Severity: blocker. Same class as F2's note; this instance is not recorded there.**

`seon.turn/max-episode-runs` (`src/seon/turn.clj:2727`) is private and
uncontracted. Its body is `(or <db/q> <overlay> <db/q>)` — an error map from
the first query is truthy, so **the refusal is returned as the budget**.

Its two callers sit 12 lines apart:

- `turns-left` (`:2739`, public, contracted, output `[:or :my.agent/turns-left
  :seon.error/value]`) does
  `(or (some #(when (:seon.error/kind %) %) [limit spent]) …)` — correct.
- `opening-deferred?` (`:2748`, private, uncontracted) does
  `(or (nil? limit) … (>= (episode-runs db agent-id) limit))` — an error map as
  `limit` is not `nil`, so control reaches `>=` and **throws
  `ClassCastException` into the turn loop**, violating AGENTS §1's "nothing
  throws into the loop".

`episode-runs` (`:2693`) is public, contracted, and *declares*
`[:or :seon.turn.work/episode-runs :seon.error/value]` — the error arm is
documented, propagated, and then dropped by the one consumer that decides
whether an agent may open a turn at all. `opening-deferred?`'s own docstring
says it is "FAIL-CLOSED IN BOTH DIRECTIONS"; on a read failure it fails by
throwing, which is neither direction.

### F4 — Two byte-identical private helpers for one job in two namespaces

**Severity: friction. No issue note found** (searched `dual-code-paths`,
`ref-identity`, `ref-attribute`; `docs/seon/issues/archive/dual-code-paths-registry.md`
is a closed, unrelated registry note).

`seon.cluster/ref-identity` (`src/seon/cluster.clj:159`) and
`seon.effect/ref-attribute` (`src/seon/effect.clj:45`) are the same three
lines with different names:

```clojure
[database ref attribute]
(when (and database (:db/id ref))
  (get (db/pull database [attribute] (:db/id ref)) attribute))
```

AGENTS §2.5 forbids a second owner for one mechanism. Writing two contracts
here would make the duplication *durable* — the contract wave is the moment to
delete one, not to bless both. Both also swallow a read error as `nil`.

### F5 — The predicate §1j's repair depends on is private in seven namespaces and public in none

**Severity: blocker. No issue note found** (searched `error-value?`,
`error-model`, `class-kill`; the open note in F2 names the missing check but
not that the checker is unreachable).

§1j rules that "a contracted consumer refuses it by shape". The shape test is
`(and (map? value) (keyword? (:seon.error/kind value)))`, and it exists as a
**private** `defn-` seven times:

| `src/seon/db.clj:160` | `src/seon/operator.clj:54` | `src/seon/plan.clj:84` |
|---|---|---|
| `src/seon/call_preparation.clj:116` | `src/seon/note.clj:30` | `src/seon/cluster/message.clj:486` |
| `src/seon/render/ns.clj:54` | | |

Four of them (`plan`, `message`, `note`, `render/ns`) are character-identical.
`seon.db` — the one database namespace — keeps its copy private, so the 112
private read-consumers in this domain have **no supported way to ask the
question** the ruling tells them to ask. Every one of them either re-declares
the predicate, writes `(:seon.error/kind x)` inline, or skips the check. The
third is what 73 of them do.

This is the derive-or-die law applied to code rather than to data: seven
hand-maintained copies of one rule. The contract wave should not begin before
one public owner exists, or it will mint a hundred more inline
`(:seon.error/kind …)` checks with no owner to fix when the shape changes.

### F6 — 73 of the 113 database-touching private functions never mention an error at all

**Severity: blocker (the class). Covered by F2's open note as a class; this is the domain measurement it lacks.**

Across the domain, 113 private functions call `seon.db/pull|q|entity|pull-many|datoms|transact!`
directly. **73 contain no `:seon.error`, `error-value?`, `error?` or
`diagnostic` token anywhere in their body** — they cannot be checking. The
recurring sub-shapes, each a distinct wrong understanding:

| Shape | Consequence when the read fails | Instances (examples) |
|---|---|---|
| `(into #{} (db/q …))` | the error map becomes a set of `MapEntry`, so **every** member reads as absent | `seon.cluster/missing-process-rows:1076`, `closure-fact-missing:1469`, `:1475`, `program-currentness:1857` |
| `(when-let [x (db/pull …)])` / `(if-let …)` | truthy error map proceeds as "the row" | `seon.sci.eval/remaining-definition-facts:722`, `seon.turn/continuing-reply?:2828`, `seon.cluster/stored-activation:1436` |
| `(:some-key (db/pull …))` | `nil`, reported as "absent", never as "unreadable" | `seon.turn/resolve-namespace-name:533`, `current-transaction-instant:540`, `seon.effect/evaluation-eid:242` |
| `(nil? (db/q …))` as a health test | non-`nil` error map answers "present"/"not read-only"/"exists" | `seon.turn/read-only-evaluation?:2022`, `seon.cluster.message/agent-exists?:141`, `seon.plan/owned-step-eid!:509` |
| `(boolean (db/q …))` / `(seq (db/datoms …))` | a refusal is `true` | `seon.turn/declaration-written-by-run?:1128`, `current-schema-data-attributes:1012` |
| `(long (or (db/q …) -1))` / `(>= … error)` | `ClassCastException` **into the loop** | `seon.plan/next-position:522`, `seon.turn/opening-deferred?:2761` |
| binding a `db/transact!` report unchecked | a lost write reported as success | `seon.effect/settle-value!:539`, `interrupt!:590`, `seon.cluster/recover-runs!:2540` |

Four of these fail *open* on a security- or correctness-relevant question —
`agent-exists?` (deliver to a nonexistent recipient), `owned-step-eid!` (a
step the agent does not own reads as owned), `declaration-written-by-run?`
(suppresses the divergence refusal), `current-schema-data-attributes` (fails
closed, the only one of the four that is safe).

### F7 — Arming a private contract in this domain *throws* by default, and most of these callers are not behind the SCI boundary

**Severity: blocker (sequencing). Partly recorded** in the 2026-09-17 update to
F2's issue note, which proves the flat error survives the private wrapper but
"DOES NOT prove a raw host call returns it without throwing".

`seon.instrument/throwing-report` (`src/seon/instrument.clj:449`) is the
`:panic` reporter: a contract violation becomes `(throw (ex-info …))`.
AGENTS §1's dial is "dev panics, prod degrades", and `default` — the
development cluster every lane verifies on — is the panicking one. The guarded
SCI boundary recovers the value (`src/seon/sci/kernel.clj:462`), but the
functions in §2.1–§2.4 of this audit are reached from **boot**
(`recover-runs!`, `accrete-schema-population!`, `load-core-namespaces!`), from
**the serial writer** (`current-run`, `row-tx`, `analyze-settlement`), and
from **flow procs** (`next-agent-work`, `opening-deferred?`) — none of which is
behind that boundary.

The consequence for triage is concrete and not obviously bad: arming
`current-run`'s contract turns F2's silent open-turn into a thrown refusal that
aborts the transaction, which is the *correct* outcome and is exactly what
`refuse!` (`src/seon/turn.clj:301`) already does deliberately. But arming a
contract on a boot-path function turns a degraded boot into a failed one. The
order in §5 puts writer-path functions before boot-path functions for that
reason, and every arming in this domain should be done with the working
`bin/test-fast` namespace at hand, expecting reds — which §1j says to welcome.

### F8 — `seon.env`'s two refusal shapes are not registered schemas

**Severity: friction. No issue note found** (searched `seon.env`,
`incomplete-environment`, `invalid-member`).

`seon.env/construct` (`src/seon/env.clj:204`) returns
`{:seon.error/kind ::invalid-member …}` at `:207` and
`{:seon.error/kind ::incomplete-environment …}` via `absent-member-error`
(`:194`). Neither `:seon.env/invalid-member` nor
`:seon.env/incomplete-environment` appears in
`resources/seon/schemas/seon.env.edn`, which declares `:seon.env/layer` and
`:seon.env/environment` and stops. Compare `resources/seon/schemas/seon.fn.edn`,
which registers eleven `*-error` classes with `{:seon.error/class true}` and a
render pair each. Law 2.1's own constructor — the one every boot layer's
refusal passes through — therefore has no declared refusal shape to name in a
contract, and no AI/HTML render pair. This is a prerequisite for contracting
`construct`, not a consequence of it.

### F9 — `seon.sci.admit`'s bound checks are private, uncontracted, and are the bounded-execution seam

**Severity: friction. No issue note found** for the admit-side bounds
specifically.

`over-bound?` (`src/seon/sci/admit.clj:139`), `missing-bound-refusal` (`:652`),
`failed-node!` (`:389`) and `rethrow-or-degrade!` (`:400`) implement AGENTS
§2.3's "every execution surface carries its declared bound, enforced at the
seam that admits the work" for value admission. All four are private and
uncontracted. A wrong boolean out of `over-bound?` is an unbounded admission,
which §2.3 says should be unconstructable. The same applies to
`seon.sci.kernel/own-arm` (`:230`) and `same-interpreter?` (`:272`) for
evaluation guards. These carry no database read and so did not surface in the
112, but they are the highest-value *non*-database privates in the domain.

## 5. Triage order — the ten to contract first

Ordered by (blast radius of a silent wrong answer) × (how cheap the contract
is to state honestly). F5 and F8 are listed as prerequisites because
contracting without them produces either seven more inline checks or a contract
that cannot name its own refusal.

**Prerequisite P0 — make one `error-value?` public** (F5). Promote
`src/seon/db.clj:160` to a contracted public function, delete the six copies,
and every row below can state its check in one call. Nothing else on this list
should land first.

**Prerequisite P1 — register `seon.env`'s two refusal shapes** (F8), so
`construct` can declare an output.

| # | Function | `file:line` | Why first |
|---|---|---|---|
| 1 | `seon.turn/current-run` | `src/seon/turn.clj:310` | F2. A read refusal is currently classified as an **open turn** by the serial writer's own eligibility check. Every other turn transition reads through it. Its contract also forces `open?`'s input schema to name the turn identity instead of an optional absent key — which is the repair, not the contract alone. |
| 2 | `seon.turn/opening-deferred?` + `max-episode-runs` | `:2748`, `:2727` | F3. Decides whether an agent opens a turn and therefore whether a **paid** model call happens; throws `ClassCastException` into the turn loop on a read failure, next to a public sibling that checks correctly. One pair, one contract each, one regression. |
| 3 | `seon.cluster/recover-runs!` | `src/seon/cluster.clj:2518` | Boot recovery. An error map as `open-runs` recovers **nothing** and reports success — AGENTS' "a check that reads ABSENCE OF SIGNAL as health" in the function whose entire job is to notice interrupted work. |
| 4 | `seon.sci.eval/remaining-definition-facts` | `src/seon/sci/eval.clj:721` | A read refusal is `dissoc`'d and published as a declaration's **definition attributes**, putting `:seon.error/*` keys into the program graph. Error-as-a-row reaching durable facts is worse than error-as-a-row reaching a boolean. |
| 5 | `seon.plan/owned-step-eid!` + `agent-eid`/`plan-eid`/`step-eid`/`ref-eid` | `src/seon/plan.clj:502`, `:104`–`:135` | Ownership **fails open**: an unreadable database reports another agent's step as owned. The four eid readers feed error maps into transaction data as refs. Agent-facing, so the refusal must be a value, not a throw. |
| 6 | `seon.effect/settle-value!` + `interrupt!` | `src/seon/effect.clj:536`, `:579` | The effect settlement writes bind the `db/transact!` report into `:seon.effect/transaction` unchecked. A lost settlement leaves an effect pending with no terminal fact and nothing to notice it. |
| 7 | `seon.cluster.message/agent-exists?` | `src/seon/cluster/message.clj:141` | Fails open: an unreadable database says the recipient exists, and the message is delivered to nothing. One line, one contract, immediate. |
| 8 | `seon.turn/declaration-written-by-run?` | `src/seon/turn.clj:1121` | `(boolean <error map>)` is `true`, so a failed history read asserts "this run wrote it" and **suppresses the concurrent-divergence refusal** on a declaration write. |
| 9 | `seon.cluster/missing-process-rows` + `closure-fact-missing` | `src/seon/cluster.clj:1073`, `:1458` | The `(into #{} (db/q …))` shape: an error map becomes a set of `MapEntry`, so every identity reads as missing. One produces re-minted process rows; the other refuses activation with a fabricated missing-facts list — the same mis-report the F2 issue note records for `seon.config/effective-in`. |
| 10 | `seon.sci.eval/acquire-program!` + `load-core-namespaces!` + `record-acquisition-refusals!` | `src/seon/sci/eval.clj:1606`, `:1189`, `:1555` | Acquisition is the boot gate for every agent's SCI context, and `record-acquisition-refusals!` failing silently means the refusals themselves are lost. Last of the ten because of F7: these are boot-path, and arming them under the `:panic` dial converts a degraded boot into a failed one — do this one with a scratch cluster, not on `default`. |

After these ten, the rest of the 112 database consumers divide cleanly by the
sub-shapes in F6's table; each shape is one mechanical sweep with one class
regression, which is cheaper than 112 individual lanes. The 357 non-database
privates in §3 follow, and `seon.sci.admit`/`seon.sci.kernel`'s bound checks
(F9) should be promoted ahead of them because an unbounded admission is a
§2.3 violation rather than a §1j debt.

## 6. Appendix — every private, spec-less function in this domain that touches the database

Static derivation at `7cec8cb57` (113 rows; the graph counts 112, excluding
`seon.sci.eval`'s one already-contracted private). Callers are advisory static
evidence, capped at five.

| Symbol | `file:line` | `seon.db` calls | Callers (static) |
|---|---|---|---|
| `seon.cluster/ref-identity` | `src/seon/cluster.clj:159` | `pull` | `format-ai`, `pulled-values`, `render-html`, `shallow-entity`, `socket-server-generator` |
| `seon.cluster/mcp-project` | `src/seon/cluster.clj:346` | `transact!` | `mcp-projection-error`, `mcp-valf` |
| `seon.cluster/missing-process-rows` | `src/seon/cluster.clj:1073` | `q` | `accrete-schema-population!`, `declaration-changes` |
| `seon.cluster/schema-row-changes` | `src/seon/cluster.clj:1144` | `pull` | `accrete-schema-population!`, `schema-row-converged?` |
| `seon.cluster/instruction-row-changes` | `src/seon/cluster.clj:1171` | `q` | `populate-source!`, `schema-row-changes` |
| `seon.cluster/activation-requirements` | `src/seon/cluster.clj:1185` | `q` | `activation-missing`, `derive-activation`, `instruction-row-changes` |
| `seon.cluster/transact-initialization!` | `src/seon/cluster.clj:1247` | `pull`, `transact!` | `activation-lookup-row`, `populate-source!` |
| `seon.cluster/stored-activation` | `src/seon/cluster.clj:1434` | `pull`, `q` | `pulled-closure`, `require-activation!` |
| `seon.cluster/closure-fact-missing` | `src/seon/cluster.clj:1458` | `pull`, `q` | `require-activation!`, `stored-activation` |
| `seon.cluster/accrete-schema-population!` | `src/seon/cluster.clj:1558` | `transact!` | `populate-source!`, `require-admissible-branch!`, `stand-boot-layers!` |
| `seon.cluster/count-installed` | `src/seon/cluster.clj:1840` | `q` | `program-currentness`, `source-base!` |
| `seon.cluster/program-currentness` | `src/seon/cluster.clj:1852` | `q` | `count-installed`, `require-coherent-program!` |
| `seon.cluster/current-publication` | `src/seon/cluster.clj:1951` | `q` | `full-source-refresh!`, `incremental-source-refresh!`, `source-artifact` |
| `seon.cluster/namespace-requires` | `src/seon/cluster.clj:2216` | `q` | `development-source-refresh!`, `reload-order` |
| `seon.cluster/development-source-refresh!` | `src/seon/cluster.clj:2280` | `pull`, `q`, `transact!` | `adoption-identities`, `refresh-source!` |
| `seon.cluster/recover-runs!` | `src/seon/cluster.clj:2518` | `q`, `transact!` | `process-identity`, `stand-cluster-runtime!` |
| `seon.cluster/seed-root-agent!` | `src/seon/cluster.clj:2720` | `transact!` | `ensure-entity!`, `stand-cluster-runtime!` |
| `seon.cluster/tagged-run` | `src/seon/cluster.clj:2816` | `q` | `commit-fault!`, `serve!` |
| `seon.cluster/previously-reported-fault-signature?` | `src/seon/cluster.clj:2833` | `q` | `commit-fault!`, `tagged-run` |
| `seon.cluster/commit-fault!` | `src/seon/cluster.clj:2841` | `transact!` | `acquire!`, `acquire-development!`, `acquire-program!`, `arm-agents!`, `fault-committer-step` +4 |
| `seon.cluster.agent/submit-source-in-projection` | `src/seon/cluster/agent.clj:530` | `pull`, `q`, `transact!` | `armed`, `submit-source!` |
| `seon.cluster.message/caused-by` | `src/seon/cluster/message.clj:37` | `q` | `chain-depth`, `form-problem`, `planner-scoped-attempt?`, `reply`, `trigger` |
| `seon.cluster.message/agent-exists?` | `src/seon/cluster/message.clj:140` | `q` | `commit-call`, `decode-form`, `delivery`, `fact-tempid`, `inbound-tx` +2 |
| `seon.cluster.message/agent-reference-id` | `src/seon/cluster/message.clj:224` | `q` | `delivery`, `format-ai`, `render-html` |
| `seon.cluster.message/identity-reference` | `src/seon/cluster/message.clj:244` | `pull` | `agent-reference-id`, `render-inbox-html` |
| `seon.cluster.message/message-instant` | `src/seon/cluster/message.clj:276` | `q` | `data-link`, `listing-entry`, `message-order`, `render-html` |
| `seon.cluster.message/recipient-eid` | `src/seon/cluster/message.clj:523` | `q` | `inbox*`, `listing-entry` |
| `seon.cluster.message/inbox-message-eids` | `src/seon/cluster/message.clj:530` | `q` | `inbox*`, `recipient-eid` |
| `seon.cluster.message/inbox*` | `src/seon/cluster/message.clj:538` | `pull` | `inbox`, `inbox-message-eids` |
| `seon.cluster.message/send-call` | `src/seon/cluster/message.clj:644` | `q` | `send`, `send!` |
| `seon.cluster.prompt/config-cluster-name` | `src/seon/cluster/prompt.clj:37` | `q` | `default-depth`, `effective-ai-settings` |
| `seon.cluster.prompt/calibration-for` | `src/seon/cluster/prompt.clj:66` | `q` | `effective-ai-settings`, `model-calibration` |
| `seon.cluster.prompt/capture-mismatch` | `src/seon/cluster/prompt.clj:312` | `q` | `acquire-context-report`, `select` |
| `seon.cluster.source/activation-seal-tx` | `src/seon/cluster/source.clj:222` | `pull`, `q` | `publish!`, `resolve-activation`, `upsert!` |
| `seon.cluster.source/identity-rows` | `src/seon/cluster/source.clj:339` | `pull-many` | `absent-program-identities`, `adopt!`, `citation-pattern`, `mintable-identity`, `preserved-evidence-tx` |
| `seon.cluster.source/result-preservation-tx` | `src/seon/cluster/source.clj:396` | `pull-many`, `q` | `identity-ref`, `publish!` |
| `seon.effect/ref-attribute` | `src/seon/effect.clj:45` | `pull` | `*request-context*`, `receipt-identities` |
| `seon.effect/evaluation-eid` | `src/seon/effect.clj:232` | `pull` | `assignment-facts`, `declared-datoms`, `open-call` |
| `seon.effect/capability-fn-eid` | `src/seon/effect.clj:249` | `pull` | `evaluation-eid`, `open-call` |
| `seon.effect/write-back-adds` | `src/seon/effect.clj:280` | `pull`, `q` | `open-call`, `settle-call` |
| `seon.effect/settle-value!` | `src/seon/effect.clj:536` | `transact!` | `request*`, `settle-background-terminal!`, `staged-result` |
| `seon.effect/interrupt!` | `src/seon/effect.clj:579` | `transact!` | `request*`, `settle-background-terminal!`, `settle-value!` |
| `seon.effect/request*` | `src/seon/effect.clj:671` | `pull`, `transact!` | `background-time-limit`, `request!` |
| `seon.plan/agent-eid` | `src/seon/plan.clj:104` | `q` | `add-note-call`, `add-step-call`, `agent-wake-datoms`, `arm-agents!`, `compact-call` +16 |
| `seon.plan/plan-eid` | `src/seon/plan.clj:111` | `q` | `add-step-call`, `agent-eid`, `compile-tree`, `complete-step-call`, `settle-call` +1 |
| `seon.plan/step-eid` | `src/seon/plan.clj:117` | `q` | `add-step-call`, `compile-tree`, `complete-step-call`, `foreign-open-work`, `item` +3 |
| `seon.plan/ref-eid` | `src/seon/plan.clj:124` | `entity` | `add-step-call`, `compile-tree`, `entry-comparable`, `owned-step-eid!`, `step-eid` |
| `seon.plan/subject-eid` | `src/seon/plan.clj:128` | `q` | `ref-eid`, `resolve-subject!` |
| `seon.plan/owned-ids` | `src/seon/plan.clj:164` | `q` | `compile-tree`, `owned-ids-query`, `start-step-call`, `update-step-call` |
| `seon.plan/foreign-open-work` | `src/seon/plan.clj:193` | `pull` | `derived-frontier`, `open-work?` |
| `seon.plan/agent-plan-pull` | `src/seon/plan.clj:310` | `pull` | `compile-tree`, `completion-view`, `plan` |
| `seon.plan/transact-plan!` | `src/seon/plan.clj:495` | `transact!` | `add!`, `complete!`, `ready-subjects`, `start!`, `update!` |
| `seon.plan/owned-step-eid!` | `src/seon/plan.clj:502` | `q` | `add-step-call`, `transact-plan!` |
| `seon.plan/next-position` | `src/seon/plan.clj:520` | `q` | `add-step-call`, `owned-step-eid!` |
| `seon.plan/query-deadline` | `src/seon/plan.clj:577` | `q` | `add-step-call`, `complete-step-call`, `run-issue-tests!`, `settle-call` |
| `seon.plan/stale-issue-tests` | `src/seon/plan.clj:597` | `pull`, `q` | `issue-done-query`, `run-issue-tests!` |
| `seon.plan/done-query-result` | `src/seon/plan.clj:692` | `pull`, `q` | `complete-step-call`, `run-issue-tests!`, `settle-call` |
| `seon.plan/completion-tx` | `src/seon/plan.clj:714` | `q` | `complete-step-call`, `query-satisfied?`, `settle-call` |
| `seon.plan/complete-step-call` | `src/seon/plan.clj:748` | `pull`, `q` | `complete!`, `settle-call` |
| `seon.plan/start-step-call` | `src/seon/plan.clj:823` | `q` | `complete!`, `start!` |
| `seon.plan/scalar-retractions` | `src/seon/plan.clj:945` | `q` | `compile-tree`, `entry-tx-map` |
| `seon.plan/stored-comparables` | `src/seon/plan.clj:997` | `pull-many` | `comparable`, `compile-tree` |
| `seon.plan/compile-tree` | `src/seon/plan.clj:1040` | `q` | `entry-comparable`, `plan!` |
| `seon.sci.eval/database-effective-config` | `src/seon/sci/eval.clj:635` | `q` | `advance-context-projection!`, `instrumentation-config`, `record-acquisition-refusals!` |
| `seon.sci.eval/install-function-from-database!` | `src/seon/sci/eval.clj:690` | `pull` | `base-ctx`, `install-function-contract!`, `install-row!` |
| `seon.sci.eval/remaining-definition-facts` | `src/seon/sci/eval.clj:721` | `pull` | `committed-row?`, `install-row!`, `namespace-reference-attributes` |
| `seon.sci.eval/installation-covers-program-change?` | `src/seon/sci/eval.clj:989` | `pull-many`, `q` | `acquired-program`, `install-evaluated-rows!` |
| `seon.sci.eval/load-core-namespaces!` | `src/seon/sci/eval.clj:1189` | `q` | `acquire!`, `cluster-ctx*`, `host-namespace!` |
| `seon.sci.eval/program-documentation` | `src/seon/sci/eval.clj:1285` | `q` | `directory-value`, `program-documentation-selector` |
| `seon.sci.eval/record-acquisition-refusals!` | `src/seon/sci/eval.clj:1555` | `transact!` | `acquire!`, `acquire-program!`, `acquisition-refusal-id`, `cluster-ctx*` |
| `seon.sci.eval/acquire-program!` | `src/seon/sci/eval.clj:1606` | `pull`, `q` | `base-ctx`, `record-acquisition-refusals!` |
| `seon.sci.eval/latest-print-fact` | `src/seon/sci/eval.clj:1882` | `q` | `acquire-program!`, `session-print-options` |
| `seon.sci.eval/shown-result` | `src/seon/sci/eval.clj:2315` | `pull` | `evaluate`, `failure-text`, `refuse-install` |
| `seon.sci.eval/run-candidate-test!` | `src/seon/sci/eval.clj:2975` | `pull` | `evaluate-candidate`, `run-test` |
| `seon.turn/result-blob-threshold` | `src/seon/turn.clj:64` | `q` | `stage-reply!` |
| `seon.turn/current-run` | `src/seon/turn.clj:310` | `pull` | `failure-replacement-tx`, `open-call`, `open-run-tx-call`, `receipt-run`, `record-evaluated-call` +4 |
| `seon.turn/running-receipts` | `src/seon/turn.clj:326` | `pull`, `q` | `interrupt-stamps`, `require-open-run` |
| `seon.turn/resolve-namespace-name` | `src/seon/turn.clj:528` | `pull` | `plan-call`, `receipt-identity`, `record-evaluated-call`, `record-evaluated-tx`, `stored-record-content` +1 |
| `seon.turn/current-transaction-instant` | `src/seon/turn.clj:537` | `pull` | `plan-call`, `resolve-namespace-name`, `system-run-call` |
| `seon.turn/current-receipt` | `src/seon/turn.clj:800` | `pull` | `generated-run-tx`, `receipt-settle-call`, `receipt-start-call`, `resolve-namespace-name`, `source-rows` |
| `seon.turn/settlement-form` | `src/seon/turn.clj:888` | `pull` | `analyze-settlement`, `receipt-start-call` |
| `seon.turn/analyze-settlement` | `src/seon/turn.clj:898` | `pull` | `receipt-settle-tx`, `settlement-form` |
| `seon.turn/current-schema-data-attributes` | `src/seon/turn.clj:1004` | `datoms` | `affected-schema-attributes`, `assert-schema-data-unused!` |
| `seon.turn/identity-ref` | `src/seon/turn.clj:1078` | `pull` | `data-link`, `declared-one`, `identity-tombstone-rows`, `preserved-evidence-tx`, `ref-attribute?` |
| `seon.turn/declaration-written-by-run?` | `src/seon/turn.clj:1121` | `q` | `declaration-diverged-since-open?`, `declared-content` |
| `seon.turn/declaration-diverged-since-open?` | `src/seon/turn.clj:1140` | `pull` | `declaration-written-by-run?`, `row-tx` |
| `seon.turn/pending-relation-resolution-tx` | `src/seon/turn.clj:1183` | `q` | `relation-assertions`, `row-tx` |
| `seon.turn/row-tx` | `src/seon/turn.clj:1228` | `pull` | `declaration-projection`, `receipt-settle-call` |
| `seon.turn/stored-record-content` | `src/seon/turn.clj:1486` | `q` | `record-evaluated-call`, `recorded-run` |
| `seon.turn/run-receipts` | `src/seon/turn.clj:1788` | `q` | `read-result`, `recover-call`, `render-ai`, `run-episode!`, `terminal-state` |
| `seon.turn/latest-evaluations` | `src/seon/turn.clj:1980` | `q` | `declared-sources`, `system-turn` |
| `seon.turn/read-only-evaluation?` | `src/seon/turn.clj:2014` | `q` | `latest-evaluations`, `system-plan` |
| `seon.turn/issue-origin-read?` | `src/seon/turn.clj:2072` | `pull` | `generated-read-fault`, `system-plan`, `system-turn` |
| `seon.turn/agent-run` | `src/seon/turn.clj:2362` | `pull` | `next-agent-work`, `virtual-turn!` |
| `seon.turn/next-ordinal` | `src/seon/turn.clj:2375` | `q` | `evaluable-source?`, `fold-or-close`, `resume-or-generate` |
| `seon.turn/form-run-id` | `src/seon/turn.clj:2507` | `q` | `form-settlement`, `terminal-receipt?` |
| `seon.turn/assignment-facts` | `src/seon/turn.clj:2516` | `q` | `form-run-id`, `form-settlement` |
| `seon.turn/agent-eid` | `src/seon/turn.clj:2630` | `q` | `add-note-call`, `add-step-call`, `agent-wake-datoms`, `arm-agents!`, `compact-call` +16 |
| `seon.turn/max-episode-runs` | `src/seon/turn.clj:2727` | `q` | `episode-runs`, `opening-deferred?`, `turns-left` |
| `seon.turn/opening-deferred?` | `src/seon/turn.clj:2748` | `q` | `deferred-triggers`, `next-agent-work`, `turns-left` |
| `seon.turn/continuing-reply?` | `src/seon/turn.clj:2824` | `q` | `next-agent-work`, `resume-or-generate` |
| `seon.turn/evaluation-terminal-data` | `src/seon/turn.clj:3416` | `q` | `closing-settlement?`, `settle!`, `settle-batch!` |
| `seon.turn/settle-batch!` | `src/seon/turn.clj:3531` | `transact!` | `evaluation-terminal-data`, `resume-turn` |
| `seon.turn/settle-batch-refusal!` | `src/seon/turn.clj:3649` | `transact!` | `evaluation-terminal-data`, `refusal-terminal-data`, `settle-batch!` |
| `seon.turn/attempts` | `src/seon/turn.clj:3772` | `q` | `agents`, `attempt-id`, `call-turn`, `episode-runs`, `ledger-rows` +5 |
| `seon.turn/record-attempt!` | `src/seon/turn.clj:3870` | `pull`, `q`, `transact!` | `call-turn`, `provider-targets` |
| `seon.turn/fold-evaluations` | `src/seon/turn.clj:3997` | `q` | `record-attempt!`, `resume-turn` |
| `seon.turn/fold-namespace` | `src/seon/turn.clj:4030` | `q` | `fold-source`, `resume-turn` |
| `seon.turn/open-turn` | `src/seon/turn.clj:4071` | `transact!` | `evaluation-request`, `start-tx`, `turn` |
| `seon.turn/call-turn` | `src/seon/turn.clj:4136` | `transact!` | `await-turn-part!`, `turn` |
| `seon.turn/evaluation-entity-id` | `src/seon/turn.clj:4450` | `q` | `call-turn`, `evaluate-sources` |
| `seon.turn/close-turn` | `src/seon/turn.clj:4791` | `transact!` | `resume-turn`, `turn` |
| `seon.turn/generate-turn` | `src/seon/turn.clj:4805` | `q`, `transact!` | `close-turn`, `turn` |

---

Read-only audit. No source file, issue note or plan document was modified.
