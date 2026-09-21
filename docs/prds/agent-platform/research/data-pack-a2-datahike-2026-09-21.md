---
type: research
status: data pack (verification only — no recommendations)
created: 2026-09-21
tags: [data-pack, datahike, konserve, seon.db, verification, lane-a2]
---

# Data pack A2 — the Datahike / Konserve seam, verified

Verification pass over
[deletion-audit-datahike-2026-09-21.md](deletion-audit-datahike-2026-09-21.md)
against the working tree at `steward-platform` and the current gitlinks
`reference-code/datahike @ 006e634a` and `reference-code/konserve @ 07377c2`.
Every row below was re-read this session. One REPL evaluation was spent (§4).
This document states what IS, not what to do; §6 is the decision surface.

## 1. Audit citations verified at HEAD

`src/seon/db.clj` is **4,638** lines; `src/seon/schema/datahike.clj` **555**;
`src/seon/cluster/store.clj` **577**; `src/seon/cluster/registry.clj` **667**;
`src/seon/blob.clj` **430**; `resources/seon/schemas/seon.db.edn` **329**.

### 1a. `src/` citations — verified exactly unless a correction is given

| audit cites | verified | correction |
|---|---|---|
| `db.clj:52-68` eight `defonce`+`delay` holders | ✅ exactly 8, at `:52 :54 :56 :58 :60 :62 :64 :67` | — |
| `db.clj:146-163` four dynamic vars | ✅ `:146` `*conn*` | — |
| `db.clj:530-595` `stable-value` … `read-result-digest` | ✅ `:530` `stable-value` | — |
| `db.clj:596-763` index-pattern walkers | ✅ `:596` `(declare pull-index-patterns)` | — |
| `db.clj:640-645` the `d/q` inside `resolve-bindings` | ✅ `:640` `:where (mapv parser.impl/get-source clauses)` | — |
| `db.clj:875` `dependency-revision` | ✅ | — |
| `db.clj:904-957` `read-evidence` | ✅ `:904` | — |
| `db.clj:971-1052` `replay-read` | ✅ `:971` | — |
| `db.clj:1038` `index-pattern-change`, `:1084` `index-evidence-current`, `:1102` `read-evidence-current?` | ✅ all three | — |
| `db.clj:1168` `carried-projection` | ❌ | **`db.clj:1219`**. `:1168` is `(recur origin)))`. This is the A1/A2 seam, so the number matters |
| `db.clj:1226` `read-declarations`, `:1257` `relation-only-declarations`, `:1266` `with-declarations`, `:1295` `ask-declarations` | ✅ all four | — |
| `db.clj:1312` `edn-encoded?`, `:1326` `decode-attribute-value` | ✅ (`:1332` `decode-attribute-maps`) | — |
| `db.clj:1611` `encode-query-request` | ✅ | — |
| `db.clj:1646-1725` `decode-query-*` | ✅ `:1646 :1659 :1674 :1693` | — |
| `db.clj:1726-1779` `decode-pull-*` | ⚠️ `:1726` is `pull-output-options`; the decoders are **`:1736 :1746 :1773`** | span start off by 10 |
| `db.clj:2080-2149` `total-pull-selector` family | ✅ `:2080` `pull-limit-operators` | — |
| `db.clj:2150` `pulled-entity-schema-key` | ✅ | — |
| `db.clj:2542` `datom->data`, `:2556` `decode-index-page` | ✅ | — |
| `db.clj:2726` `commit-id` … `:2783` `since` | ✅ `:2726` | — |
| `db.clj:2797-3223` the multi-arity `diff` family | ✅ `:2797` section banner, `:2830` `external-sinks`, `:3135` `value-changes`, `:3162` `apply-diff`, `:3175` `defn diff`, `:3218` end of `perform-diff` call | — |
| `db.clj:3235` `jdk-integers->long` | ✅ | — |
| `db.clj:3647` `write-owned-values-error` | ✅ | — |
| `db.clj:3806` `declared-arity-bounds`, `:3833` `arity-mismatches-with`, `:3890` `arity-mismatches`, `:3904` `write-render-target-error` | ✅ all four | — |
| `db.clj:4050` `write-report-error`, `:4091-4103` the program-attribute gate | ✅ | — |
| `db.clj:4547` `transaction-result`, `:4578` `transact!`, `:4605`/`:4607` the two return contracts | ✅ | — |
| **56** inlined `(and (map? x) (inst? (:seon.error/at x)) …)` in `db.clj` | ✅ **exactly 56** (`grep -c "inst? (:seon.error/at"`); 63 total `:seon.error/at` mentions | — |
| `projection-cache-value` at `db.clj:1320, 3356, 3377, 3570, 3629, 4061, 4127, 4141` (malli audit §2 cross-ref) | ❌ **off by one each**: `:1319 :3355 :3376 :3569 :3628 :4060 :4126 :4140` | 8 sites confirmed |
| `schema/datahike.clj:118-124` union→string fallback, `:176-178` facets set independently, `:283-289` `edn-encoded-attr-in?`, `:543-545` per-read canonicity re-print | ✅ all four | — |
| `store.clj:121-124` `file-lock-generator`, `:137-140` `canonical-path`, `:194-197` `open-configuration`, `:306-356` `acquire-flock!`, `:368-373` `stored-main-keep-history?`, `:499-501`, `:564` `contains? @connections/*connections*` | ✅ all seven | — |
| `registry.clj:126` `konserve-store`, `:165` `connections/active-connection`, `:310-313` `reset-cluster!` guard, `:343-346` `::cannot-retire-main`, `:347-351` `::cluster-connected`, `:446` `physical-filestore-inventory`, `:510` `dry-run-complete?` | ✅ all seven | — |
| `blob.clj:94` `(:backing :base)`, `:125-147` / `:224-229` the two unwrappers | ✅ `:94`, `:125` `stored-digest-and-size`, `:224` `read-octets` | — |
| `fn.clj:1799-1805` `arity-mismatches` re-export | ✅ body is exactly `(db/arity-mismatches database)` | — |
| `operator/state.clj:214-216` `canonical-path`, `:218-222` `store-lock-path` | ✅ | — |

### 1b. `test/` citations — all verified exactly

`db_test.clj` `:389` `edn-backed-reads-return-distinguishable-logical-values` ·
`:724` `durable-pull-digests-replay-without-retaining-read-results` ·
`:967` `pull-many-preserves-input-alignment-with-one-shared-plan` ·
`:1425` `diff-replays-one-read-by-derived-identity` ·
`:1964` `arity-components-and-callers-are-checked-in-the-final-state` ·
`:2200` `pull-validates-its-result-against-the-derived-pulled-form`.
`read_evidence_test.clj:67` · `schema/datahike_test.clj:477` ·
`publication_validation_test.clj:10` names `#'db/arity-mismatches-with` and
`#'db/write-render-target-error` literally · `store_test.clj:498` ·
`error_write_timing_test.clj:80`.

**Every `:seon.test/long-ms` bound in §5.2 is exact**, read from the reader-map
metadata: `source_nochange_test.clj:7` **600000** · `schema_redeclare_test.clj:72`
**300000** · `db/declaration_population_test.clj:16` **180000** ·
`schema/datahike_test.clj:360` **120000** · `store_test.clj:498` and `:568`
**60000** each · `error_write_timing_test.clj:80` **60000** ·
`schema/datahike_test.clj:243` **25000**.

## 2. Reference-code seams — file:line and what each GUARANTEES

Read from source at the current gitlinks.

| seam | verified file:line | what the source guarantees |
|---|---|---|
| query cache advance | `datahike/query.cljc:2568-2590` `advance-query-cache-context` | Per committed transaction it associates a commit id ONLY for the attributes that changed; an unknown, unsafe, or schema-touching transaction instead advances `:datahike.cache/conservative-revision`. Cached result rows are never inspected or copied. |
| cache comparison | `…/query.cljc:2963-2976` `source-context-unchanged?` | Returns true only when the conservative revisions are equal AND every named attribute's revision is equal; returns false whenever the attribute set is `:all`. |
| query dependency plan | `…/query.cljc:2877-2902` `query-dependency-plan` | Parses query and rule inputs and returns attribute-granularity dependencies per parsed source, WITHOUT executing the query or retaining any database value; any throw yields `:all`. |
| pull dependency plan | `…/pull_api.cljc:107-113` `pull-spec-attribute-dependencies`, `:162-177` `pull-dependency-plan`, consumed at `:564`, `:569`, `:585` | The same attribute-granularity guarantee for a pull selector, attached to the pull result as `:datahike.read/dependency-plan`. |
| pull limit | `…/pull_api.cljc:16` `+default-limit+ 1000`; only other use `:315` `(get opts :limit +default-limit+)` | A cardinality-many frame is truncated to the limit with no marker and no refusal. `:315` already branches on a nil limit (`(if limit (take limit) identity)`), so nil means "no truncation". |
| arbitrary value type | `…/schema.cljc:87` `(s/def :db.type/any any?)`; in production at `:105` `:db.secondary/config` | The type exists and is used; it is **absent** from the `:db.type/value` set at `:35-55`, so it cannot be declared by an ordinary attribute today. |
| stored-config consistency | `…/connector.cljc:144-181` `ensure-stored-config-consistency`, called at `:361` | Refuses a connect whose supplied config diverges from the stored one, `:keep-history?` included. |
| create-time adoption | `…/connector.cljc:187` `#{:branching-factor :diff-buf-size}`, `:195` `#{:fuse-index-roots? :commit-graph?}`, `:197` `adopt-create-time-fixed`, applied at `:350` | On a fresh connect, those four keys are taken from the stored config, so a caller's values for them cannot diverge. |
| branch roster | `…/versioning.cljc:182-189` `branches` | Returns the set of known branch keywords from `k/get store :branches`. |
| branch create | `…/versioning.cljc:212`, raise at `:249` | Refuses `:branch-already-exists` when the roster already holds the name; secondary indices are CoW-branched. |
| branch delete | `…/versioning.cljc:279`, raises at `:287` `:cannot-delete-main-db-branch` and `:313` `:branch-has-active-connection` | Refuses main deletion and refuses while any connection to the branch is active, listing the connections. Bytes survive until the next GC. |
| force / commit / fork / merge | `…/versioning.cljc:323` `force-branch!`, `:457` `commit-id`, `:469` `commit-as-db`, `:550` `fork-database`, `:734` `merge!` | `force-branch!` carries **no** active-connection check; the others are the pointer operations Seon's `source.clj` already routes through. |
| connection liveness | `…/connections.cljc:5-9` `active-connection`, `:123-127` `delete-connection!` | `active-connection` returns a connection only when its reference count is positive; `delete-connection!` removes the map entry at the END of the release drain. A `contains?` on `*connections*` therefore stays true throughout a drain and during `:opening?`. |
| GC head read | `…/gc.cljc:24` `(k/get-in store [branch :meta :datahike/commit-id])` | The library spells "the commit this branch is at" itself. |
| GC reachability | `…/gc.cljc:83` `gc-storage!`, `:152` `:datahike.gc/reachable-extension`, `:171` `start-background-gc!` | A caller-supplied extension contributes reachable keys; there is no dry-run option in this signature. |
| GC guard | `…/gc_guard.cljc:36-42`, `:77` `permit`, `:108-150` request/grant | States that Datahike assumes a single JVM; the permit is process-local. No `FileLock`/`tryLock` exists anywhere in either vendored library (grep, both trees). |
| konserve blobs | `konserve/core.cljc:634 bget`, `:658 bget-range`, `:673 bassoc`, `:690 keys`; `konserve/gc.cljc:8-30` | `keys` yields per-key metadata (`:key`, `:last-write`) — no size. The GC callback contract states that throwing out of it prevents the backing store from seeing the batch. |
| our fork commits (all on `origin/main`) | `34365046` (2026-07-16) bounded native index pages · `5e82166f` (07-15) bounded committed report source · `1e78cb9c` (07-14) `feat(query): bound query and pull resources` · `f8192962` (07-15) exact query single-flight · `73afe782` (2026-09-16) optional final transaction report validation | All five verified present. Remotes: datahike `origin git@github.com:seantempesta/datahike.git`, upstream `replikativ/datahike`; konserve `origin https://github.com/seantempesta/konserve.git`. Both on `main`, tracking `origin/main`. |
| first-party idiom already right | `src/seon/cluster/source.clj` (every branch mutation through `datahike.versioning`); `src/seon/blob.clj` (konserve `bassoc`/`bget`/`bget-range` directly); `src/seon/cluster/store.clj:306-356` `acquire-flock!` | The `flock` has no library counterpart; content addressing and digest verification have none in konserve. |

## 3. Consumer counts, by file and kind

Counted by `grep -rn` over `src/` and `test/` at HEAD. "Refs" are occurrences,
not distinct call sites.

| mechanism | `db.clj` refs | other `src/` (production) | `test/` | note |
|---|---|---|---|---|
| `read-evidence-current?` | 4 | **11** — `render.clj` 3, `render/walk.clj` 1, `render/web.clj` 4, `turn.clj` 2 | **38** across 11 namespaces (`db_test` 11, `read_evidence_test` 6, `call_preparation_test` 4, `help_test` 4, + 7 more) | the public entry; its three arms are internal |
| `replay-read` (F1c) | 5 | **0** | 2 (`db_test.clj`) | |
| `index-evidence-current` (F1a) | 2 | **0** | **0** | |
| `query-index-patterns` | 2 | **0** | **0** | |
| `pull-index-patterns` | 4 | **0** | **0** | |
| `dependency-revision` (F1b) | 3 | **0** | 0 | |
| `stable-value` / `read-result-digest` | 3 / 6 | `schema.clj:731` and `operator.clj:836` are **docstring/comment mentions only**, not calls | `db_test` 6, `read_evidence_test` 3, `operator_test` 1, `schema/datahike_parity.edn` 1 | |
| `with-declarations` | **10** | 0 | 2 (`db_test.clj`) | the ten call sites the audit names |
| `edn-encoded*` | 15 | `schema/datahike.clj` 4 | 1 (`db_test.clj`) | |
| `decode-query-result` / `decode-pull-result` / `validate-pulled-result` | 4 / 5 / 2 | 0 | **0 / 0 / 0** | audit's "zero direct test references" ✅ |
| `pulled-entity-schema-key` | 4 | 0 | 2 (`db_test.clj`) | |
| `total-pull-selector` | 5 | 0 | 1 (`db_test.clj`) | |
| multi-arity `db/diff` `[basis function-var & arguments]` (`:3175`) | — | **0** — `turn.clj:2224` calls the ONE-ARG map arity `{:seon.db.diff/before … :seon.db.diff/after …}` | `db_test.clj:1439, 1477, 1487, 1488, 1489`; `repl_grammar_test.clj:31, 35` use the **map** arity | audit ✅ |
| `arity-mismatches` | 9 | `fn.clj` 2 (the re-export) | `error_write_timing_test` 4, `publication_validation_test` 1, `fn_test` 2 | |
| `write-render-target-error` | 2 | 0 | 1 (`publication_validation_test`) | |
| `transaction-result` | 4 | **9** — `cluster.clj` 4, `config.clj` 4, `cluster/source.clj` 1 (B1) | `transaction_result_test`, `db_transact_shape_test` | on the hot write path |
| `jdk-integers->long` | 2 | 0 | 0 | |
| `physical-filestore-inventory` | — | 0 outside `registry.clj` | **0** | |
| `dry-run` (registry) | — | `registry.clj` only | **13** — `registry_test.clj` 5, `operator_test.clj` 8, `datahike_parity.edn` 1 | the largest test attachment in `registry.clj` |
| `diagnostic-*` keys in `db.clj` | `-operation` 26, `-cause` 25, `-evidence` 25, `-layer` 25, `-expected` 24, `-member` 24, `-offending` 24 | — | — | B3 owns the constructor (`error/refusal.clj:37-74`) |

## 4. The one REPL evaluation

Spent against the live `default` cluster, `mode: "jvm"`, `read_only: true`,
`root: /Users/sean/src/seon`, `cluster: "default"`. Returned in **216 ms**.

```clojure
(let [conn (seon.operator/connection "default")
      db (seon.db/db conn)
      proj (seon.db/carried-projection db)
      attrs (seon.schema.datahike/database-attributes-in proj)
      edn? (filterv #(seon.schema.datahike/edn-encoded-attr-in? proj %) attrs)
      facets (into (sorted-map)
                   (keep (fn [a]
                           (let [m (try (seon.schema.datahike/malli->datahike-attr-in proj a)
                                        (catch Throwable _ nil))]
                             (when (and m (or (:db/index m) (:db/unique m)))
                               [a (select-keys m [:db/index :db/unique :db/valueType :db/cardinality])])))
                         edn?))]
  {:attributes-total (count attrs)
   :edn-encoded-total (count edn?)
   :edn-encoded-with-index-or-unique facets
   :edn-encoded-sample (vec (take 25 edn?))})
```

Returned value:

```clojure
{:attributes-total 1191
 :edn-encoded-total 23
 :edn-encoded-with-index-or-unique {}          ; ← the audit's §9 open question
 :edn-encoded-sample
 [datahike.read/dependency-plan datahike.read/revision my.plan.item/about
  my.plan.item/done-query seon.db/read-request seon.error.evidence/value
  seon.error.key/scalar seon.error/member seon.flow/missing-launcher-submission
  seon.flow/submission-id seon.listen/value seon.print/default
  seon.program/blocked-subject seon.program/not-found
  seon.render.walk/continuation-subject seon.render.walk/missing-lookup
  seon.render/ai seon.render/form seon.render/html
  seon.sci.eval/acquisition-member seon.sci.eval/installation-member
  seon.sci.eval/row-member seon.sci.reader/refused-token]}
```

(Keywords printed unqualified-colon by the MCP projection; all 23 members are in
the sample, the `take 25` did not truncate.)

**Reading:** 23 of 1,191 attributes use the EDN-string codec, and **none** of
them declares `:db/index`, `:db/unique` or `:db.unique/identity`. The AVET
total-comparator objection to `:db.type/any` has no instance in the current
population. It says nothing about a future attribute.

## 5. Corrections to the audit

| # | audit claim | evidence | status |
|---|---|---|---|
| 1 | `carried-projection` at `db.clj:1168` | `:1219` is the `defn`; `:1168` is `(recur origin)))` | **wrong line** — and it is the A1/A2 seam |
| 2 | `query.cljc:2658-2671` and `:2983-3045` for `attribute-revisions`/`conservative-revision` | actual: `advance-query-cache-context` `:2568-2590`; the comparison `source-context-unchanged?` `:2963-2976` | **drifted ~90 and ~40 lines** |
| 3 | `versioning.cljc:233-235` `:branch-already-exists` | actual raise at `:246-250` | drifted |
| 4 | `versioning.cljc:283-285` `:cannot-delete-main-db-branch`; `:293-302` `:branch-has-active-connection` | actual `:286-288` and `:310-315` | drifted |
| 5 | `versioning.cljc:261-289` for `delete-branch!` | actual `:279-320` | drifted (the audit says so itself, §6 tail) |
| 6 | `versioning.cljc:206-214` cited **inside `registry.clj:111`** as the branch roster | `:206-214` is the tail of a reachability `recur`; `branches` is `:182-189` | confirmed drift **in production source**, not only in the audit |
| 7 | `projection-cache-value` at `db.clj:1320, 3356, …` | every one is off by one: `:1319, 3355, 3376, 3569, 3628, 4060, 4126, 4140` | 8 sites confirmed, numbers shifted |
| 8 | `db.clj:1726-1779` `decode-pull-*` | `:1726` is `pull-output-options`; decoders at `:1736`, `:1746`, `:1773` | span start off by 10 |
| 9 | §9 "not verified": do any EDN-encoded attributes declare index/identity/unique? | §4 above | **now answered: zero of 23** |
| 10 | §9 "not verified": is the cache context alone (F1b) sufficient for every retained read? | still unverified; §3 shows the two refinement arms have **zero** production callers and **zero** test references outside `db.clj`, so no test would detect their removal | **still open** — and now known to be untested either way |
| 11 | §9 "all timing claims" | still unmeasured. The only number taken this session is the 216 ms of the §4 probe | unchanged |
| 12 | `registry.clj` docstrings citing `gc.cljc` / `writing.cljc` | `writing.cljc` is 893 lines so `:503-552` exists; `gc.cljc:26` is inside a `let` binding (`visited #{}`), not the head read at `:24` | one drifted, the rest plausible but each needs its own read |
| 13 | `store.clj:564` "two liveness answers" | confirmed: `store.clj:564` uses `(contains? @connections/*connections* connection-id)`, `registry.clj:165` uses `connections/active-connection`; `connections.cljc:5-9` vs `:123-127` explains the divergence | **confirmed** |
| 14 | `blob.clj:131` re-handles the `{:input-stream …}` map | confirmed: `:131` `(if (map? binary) (:input-stream binary) binary)`; `:224-229` `read-octets` is the second unwrapper | confirmed |

## 6. Open questions for a spec author

Stated neutrally, with the evidence on both sides. No recommendation is made.

| # | question | evidence for | evidence against |
|---|---|---|---|
| 1 | Is Datahike's cache context (F1b, `db.clj:875`) sufficient alone, or does a retained read need EAV granularity? | `source-context-unchanged?` (`query.cljc:2963-2976`) already compares conservative + per-attribute revisions; `dependency-revision` has 3 refs, all inside `db.clj` | The ORDER in `read-evidence-current?` (`db.clj:1102-1144`) puts the index-pattern arm first, implying (b) was once found insufficient; that evidence is not in the file. Nothing in `test/` names `index-evidence-current`, so removal would be undetected by the suite either way |
| 2 | If EAV granularity is needed, does it belong in the fork beside `query-dependency-plan`, or in `db.clj`? | `db.clj:596-763` uses `query/resolve-ins`, `query/collect`, `parser.impl/get-source` — all datahike-internal already | It is a public API addition to a fork we maintain against an upstream we do not send PRs to; `:640` also re-executes the query to resolve bindings the planner holds, so the fork version would not be a straight move |
| 3 | Does `:db.type/any` remove the need for the declaration-population plumbing (`db.clj:1226-1310`, ten `with-declarations` sites) entirely? | The plumbing has 10 refs in `db.clj` and 0 elsewhere in `src/`; its stated purpose is carrying the projection into the decode walkers | The ten sites have not been individually classified as codec / non-codec this session |
| 4 | Changing `+default-limit+` (`pull_api.cljc:16`) to nil versus reporting an elision at `:315` | `:315` already honours a nil limit with no other change; a nil default makes a pull complete by construction | An unbounded pull removes a resource bound our own fork commit `1e78cb9c` deliberately added; the two decisions interact |
| 5 | Whether `:db.type/any` should be refused for `:db/index`/`:db/unique` in the fork | `:db.type/any` has no total value comparator for AVET | §4 shows zero current instances, so the refusal would be dormant; whether a dormant refusal is worth a fork divergence is a judgement |
| 6 | Whether the two `transact!` arities (`db.clj:4605` elided → `transaction-result`, `:4607` explicit → the Datahike report) stay two contracts | it is a documented ruling; `transaction-result` has 9 production refs in `cluster.clj`/`config.clj`/`source.clj` | two output shapes on one name means every caller must know which arity it called; `transaction_result_test` and `db_transact_shape_test` both police it |
| 7 | Whether `registry.clj`'s `dry-run!` (`:510-573`) moves to a fork GC option | 13 test references are attached to it (`registry_test` 5, `operator_test` 8); the control-flow exception is sanctioned by `konserve/gc.cljc:8-17` | those 13 references move or die with it; `gc-storage!` (`gc.cljc:83-168`) has no dry-run parameter today |
| 8 | Whether `store.clj`'s `stored-main-keep-history?` pre-read becomes a typed `:keep-history-mismatch` in `connector.cljc:144-181`, or is simply deleted | the library already refuses the divergence, untyped | the Seon caller reports it by name today; a fork type change is a divergence from upstream error shapes |
| 9 | Ownership boundary on the 56 inlined error-shape checks | D12 rules out a general predicate; B3 owns `error/refusal.clj` | the 56 sites are in A2's file; whether A2 converts before or after B3's constructor lands is unsettled |
| 10 | Which member of each of the nine duplicate test classes (audit §5.3) survives | §5.3 lists the members | three classes span files this lane does not own (`source_*` → B1, `schema/*` → A1) |
| 11 | The `:seon.test/long-ms` bounds in this area | `store_test.clj:498`/`:568` (60000 each) spawn a cold child JVM to prove the OS `fcntl` fence, unobservable in one process; `schema/datahike_test.clj:243` (25000) is 80 generative storage cases | `db/declaration_population_test.clj:16` (180000), `schema/datahike_test.clj:360` (120000) and `error_write_timing_test.clj:80` (60000) each declare a bound for setup the audit classes as an algorithm defect |
| 12 | Whether `transaction-result`'s per-datom `d/datoms :eavt` (`db.clj:4547-4576`) and the F3 validator narrowing are one change | both want the report's datoms grouped by `:e` | `transaction-result` has 9 production callers; the validator has none outside `write-report-error` |

## 7. How a Codex lane reaches a REPL — verified

Confirmed as another agent reported, with the chain read end to end.

| step | file:line | fact |
|---|---|---|
| 1 | `.codex/config.toml:3-4` | `[mcp_servers.seon]` with `command = "bin/mcp-server"`. The file's only other line is `project_doc_max_bytes = 131072`. |
| 2 | `bin/mcp-server` (6 lines) | `exec bb --classpath "$SEON_ROOT/script:$SEON_ROOT/src:$SEON_ROOT/resources" -m seon.dev.mcp "$@"`, with `SEON_PROJECT_ROOT` exported. A **babashka** process, not the cluster JVM. |
| 3 | `script/seon/dev/mcp.clj:848-859` | tool **`eval_clj`**: `code` (required, exactly one form), `root`, `cluster`, `namespace`, `read_only`, `mode` (`"jvm"` \| `"sci"`, default `jvm`), `session_id` (default `"default"`), `timeout_ms` (1–120000). Also `runtime_status` (`:860`) and `get_value`. |
| 4 | `script/seon/dev/mcp.clj:637-640` | `jvm` → `jvm-evaluation-form` (`:592`); `sci` → `sci-evaluation-form` (`:604`). `read_only` is threaded at `:674` as `:seon.dev.mcp/read-only?`. |
| 5 | `script/seon/dev/mcp.clj:93-114` | the MCP process `require`s `seon.fresh-operator` and resolves advertisements from it; an advertisement must carry `:seon.boot/prepl-host`, `:seon.boot/prepl-port`, `:seon.boot/pid`, `:seon.boot/start-instant` and match the requested cluster. |
| 6 | `script/seon/dev/mcp.clj:9, :30` | `[java.net InetSocketAddress Socket SocketTimeoutException]`; one stateful io-prepl socket per `[root cluster session-id]`. |
| 7 | `script/seon/fresh_operator.clj:1820-1870` `prepl-eval!` | the **operator's own** socket path — connects to `(:seon.boot/prepl-host, :seon.boot/prepl-port)` of an advertisement, writes one form, reads `edn` events until `:ret`, and fails typed on silence (`:seon.fresh-operator/prepl-connection-silent`, bounded by `:seon.config.operator/event-silence-backstop-ms`). This is what `bin/seon` uses; a lane does **not** call it directly. |

**Declared semantics of the two modes** (`script/seon/dev/mcp.clj:848`, verbatim
intent): `jvm` uses the live io-prepl and **binds no cluster custody** — at a
development REPL `(seon.operator/connection "default")` supplies the explicit
connection to pass to `seon.db`. `sci` evaluates through `seon.sci.eval/evaluate`
with the cluster's live **shared** SCI ctx and **mutates it**, so a debug `def`
enters the agents' world; it creates no run or evaluation facts.

**Isolated roots.** `bin/seon:7-9`: `--root` **requires an existing directory** —
`mkdir -p tmp/<name>-root` precedes `bin/seon --root tmp/<name>-root start <name>`.
`eval_clj` then takes `root: "tmp/<name>-root"`, `cluster: "<name>"`.
`bin/codex-agent:386` exports `SEON_CODEX_LANE=<name>`, which `bin/test` uses to
refuse cold gates.
