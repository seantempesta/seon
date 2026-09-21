---
type: research
status: draft
created: 2026-09-21
tags: [deletion-audit, datahike, konserve, seon.db, duplicate-mechanism, algorithmic-analysis, fork]
---

# Deletion audit — the Datahike / Konserve seam

Read-only audit of `src/seon/db.clj` (4,638 lines), `src/seon/schema/datahike.clj`
(555), `src/seon/cluster/{store,source,registry}.clj`, `src/seon/blob.clj` and
`resources/seon/schemas/seon.db.edn` (329) against the vendored fork at
`reference-code/datahike` and `reference-code/konserve`. Every row below was read
on both sides. No JVM was run; no measurement is claimed that a script did not
produce.

The fork is not a black box: `reference-code/datahike` carries OUR commits for
bounded index pages (`34365046`), bounded committed reports (`5e82166f`), query
and pull resource bounds (`1e78cb9c`), query single-flight (`f8192962`), and the
final-report validator hook (`73afe782`). The seam is therefore already the
right shape in places — and every remaining re-implementation in `src/` is a
choice to keep a worse copy of something the fork can own.

## 1. The three findings that dominate

**F1 — Three independent mechanisms answer one question: "is this retained read
still current?"** `src/seon/db.clj:1102-1144` tries them in order: (a) an index
pattern replay (`index-evidence-current`, `:1084`), (b) a revision comparison
against Datahike's own cache context (`dependency-revision`, `:875`), (c) a FULL
RE-EXECUTION of the read with a digest comparison (`replay-read`, `:971`;
`stable-value`/`read-result-digest`, `:530-595`). (b) is exactly the answer
Datahike's query cache already computes — `datahike.cache/attribute-revisions`
and `conservative-revision` on `:cache-context`
(`reference-code/datahike/src/datahike/query.cljc:2658-2671`,
`:2983-3045`). (a) and (c) are Seon-only refinements of the same predicate. §2.5
admits one mechanism. (c) is also the worst algorithm available: it re-runs the
query to decide whether the query needs re-running.

**F2 — The EDN-string attribute codec.** The bridge falls back to
`:db.type/string` plus a canonical-EDN codec for any heterogeneous union
(`src/seon/schema/datahike.clj:118-124`, `edn-encoded-attr-in?` `:283-289`), and
that one decision creates the entire decode-walker layer in `db.clj`
(`edn-encoded?` `:1312`, `decode-attribute-*` `:1326-1356`,
`decode-query-*` `:1646-1725`, `decode-pull-*` `:1726-1779`,
`decode-index-page` `:2556`, `encode-query-request` `:1611`), plus the
declaration-population plumbing built only to carry it
(`read-declarations` `:1226`, `relation-only-declarations` `:1257`,
`with-declarations` `:1266` — ten call sites, `ask-declarations` `:1295`).
Datahike already HAS a native arbitrary-value type: `:db.type/any`
(`reference-code/datahike/src/datahike/schema.cljc:87`), already in production
use for `:db.secondary/config` (`:105`). The decode path additionally re-prints
every decoded value to check canonicity on EVERY read
(`src/seon/schema/datahike.clj:543-545`) — the write's own work, redone per read.

**F3 — Two O(program) steps on the per-transaction write path.**
`write-report-error` (`src/seon/db.clj:4050`) runs, inside the final-report
validator of every commit:
- `write-render-target-error` (`:3904-3936`) — a Datalog query over EVERY
  `:seon.schema/form` row, `edn/read-string` on each, and an `entid` lookup per
  render symbol. O(all schemas) per write.
- `arity-mismatches-with` (`:3833-3902`) — two Datalog queries over EVERY
  `:seon.fn/call-arities` edge and EVERY `:seon.fn.arity/min` in the program.
  O(all call sites) per write.

Both are gated on the transaction touching a program attribute
(`:4091-4103`) — which is exactly what a publication transaction does, every
time. The cost of these two is what
[`test/seon/error_write_timing_test.clj`](../../../../test/seon/error_write_timing_test.clj)
exists to police: 182 lines that redefine 30 internal Vars to time them, and
[`test/seon/publication_validation_test.clj:10`](../../../../test/seon/publication_validation_test.clj)
names `#'db/arity-mismatches-with` and `#'db/write-render-target-error`
directly. A test that guards a mechanism against its own normal operation is the
report that the mechanism is on the wrong path (AGENTS.md, "Prefer dissolution to
addition"). Both answers are derivable from the report's OWN datoms: the changed
callers/callees, and the schema forms this transaction wrote.

## 2. Table — re-implementations and duplicate paths

| src span | lines | duplicates / library seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| `db.clj:530-595` `stable-value`, `stable-read-result`, `read-result-digest` | 66 | nothing in the library; feeds (c) of F1 only | delete | `dependency-revision` `:875` on Datahike's cache context | none once (c) goes |
| `db.clj:596-763` `query-index-patterns`, `pull-index-patterns` | 168 | `datahike.query/query-dependency-plan` `query.cljc:2877-2901` and `pull-api/pull-dependency-plan` `pull_api.cljc:162-186` already walk the same parsed query/selector, at attribute granularity | move to fork beside `query-dependency-plan` | the finer EAV granularity survives as a datahike dependency-plan option | fork API addition; it uses `query/resolve-ins`, `query/collect`, `parser.impl/get-source` — all datahike-internal already |
| `db.clj:640-645` the `d/q` inside `resolve-bindings` | — | — | delete with the move | — | evidence collection currently RE-EXECUTES the query to resolve pattern bindings; the planner has the bindings already |
| `db.clj:971-1052` `replay-read` + `:1102-1144` its arm of `read-evidence-current?` | 82 + ~25 | third answer to F1's one question | delete | (a) and (b) | a read with no committed identity (`:885-891`) loses its currency answer — it should report the typed unknown, not replay |
| `db.clj:1038-1101` `index-pattern-change`, `read-evidence-changes`, `index-evidence-current` | 64 | same walk as datahike's cache invalidation | move to fork with the patterns | `seon.turn:2031` keeps calling one function | — |
| `db.clj:1226-1356`, `:1611-1779`, `:2542-2566` declaration population + every decode/encode walker | ~260 | `:db.type/any` (`datahike/schema.cljc:87`, live at `:105`) | move: teach the bridge to emit `:db.type/any`, then delete | values stored natively; no decode on read | `:db.type/any` is absent from the `:db.type/value` set (`schema.cljc:35-55`); the fork must admit it and refuse it for `:db/index`/`:db/unique` attributes (no total comparator) |
| `schema/datahike.clj:283-289, 370-460, 505-555` EDN codec | ~150 | same | delete with the above | `encode-transaction-in` becomes identity for these attributes | the canonical round-trip check `:543-545` currently catches storage corruption; datahike's own value validation replaces it |
| `db.clj:2080-2149` `total-pull-selector`, `total-attribute-expression`, `total-pull-arguments` | 70 | works around `+default-limit+ 1000` in `pull_api.cljc:16`, applied at `:315` | move to fork: default the limit to nil, or report the cut as an elision | Seon stops rewriting every selector | changing a datahike default; our fork, our call |
| `db.clj:2150-2331` `pulled-entity-schema-key`, `validate-pulled-value`, `validate-pulled-result` | 182 | not a library duplicate — a per-pull INFERENCE | delete the inference arm (`:2150-2215`), keep caller-supplied `:schema-key` | callers carry `:schema-key`; the named follow-up in `research/pulled-form-derivation-2026-09-17.md` | §2.2: guessing the row schema from the entity's attribute set is a reconstruction where a declared fact belongs; it also scans `d/datoms :eavt` per pulled entity per pull |
| `db.clj:2797-3223` the whole multi-arity `diff` family (`external-sink-reach-rules`, `external-sinks`, `diff-plan`, `output-schema-refs`, `terminal-schema-key`, `collection-entry-schemas`, `row-identity-attribute`, `result-identity-attribute`, `insert-database-argument`, `invoke-diff-function`, `identity-diff`, `perform-diff`, `render-diff-ai`, `diff-refusal`, `callee-symbol`) | ~330 | no src caller: the only production use is the ONE-ARG values arity at `src/seon/turn.clj:2224` | delete | `value-changes` `:3135` + `apply-diff` `:3162` (editscript) stay; `turn.clj:1968` and `:2224` keep working | `test/seon/db_test.clj:1439-1489` is its only other caller; it goes with it. `external-sinks` `:2830` runs a RECURSIVE whole-call-graph Datalog rule per call |
| `db.clj:3806-3902` `declared-arity-bounds`, `arity-admitted?`, `arity-mismatches-with` on the write path | 98 | — | keep the query, remove it from the write path | narrow to the report's changed callers/callees; `seon.fn/arity-mismatches` stays a query | see F3 |
| `db.clj:3904-3936` `write-render-target-error` | 33 | — | same: check only the schema forms this transaction wrote and the `:seon.fn/sym` rows it retracted | the refusal survives, O(edit) | see F3 |
| `fn.clj:1799-1805` `seon.fn/arity-mismatches` | 7 | pure re-export of `db/arity-mismatches` | delete; callers call one owner | `test/seon/fn_test.clj:2314` moves | §2.5 second path for one noun |
| `db.clj:3235-3258` `jdk-integers->long` | 24 | Integer→Long coercion Datahike's own transaction validation should do (`datahike/db/transaction.cljc`) | move to fork | — | a `postwalk` over all tx-data on every write |
| `db.clj:4547-4576` `transaction-result` | 30 | — | keep, memoize nothing — but note it does `d/datoms :eavt` per report datom with no sharing | narrow to distinct entities | O(tx datoms × attributes per entity) |
| `db.clj` inlined `(and (map? x) (inst? (:seon.error/at x)) (qualified-keyword? …) (qualified-symbol? …))` | 56 occurrences ≈ 110 lines (62 repo-wide) | one predicate, copied | delete: declare it once in `seon.error.refusal` (already required at `db.clj:31`) and call it | identical behaviour | trivial; it is pure shape, no load cycle |

## 3. Duplicate caches and diverged pairs

| pair | evidence | judgement |
|---|---|---|
| `projection-cache-value` used as a general memoize | `db.clj:1320, 3356, 3377, 3570, 3629, 4061, 4127, 4141` (8 distinct keys in `db.clj`; 22 call sites repo-wide); implementation `schema.clj:397-416` is an unbounded atom map | rides the projection, so §2.1-legal — but `[::write-validator compiled]` keys on a compiled Malli schema object. The malli-native-bridge PRD already rules this: "Duplicate validator/explainer caches \| 40–100 \| Products Malli does not cache" (`plan/malli-native-bridge-prd-2026-09-20.md:370`, `:418`). Do not double-count; that lane owns it. |
| elided vs explicit `transact!` arities return DIFFERENT types | `db.clj:4605` (elided → `transaction-result`, i.e. `::tx` + `::datoms`) vs `:4607` (explicit → the Datahike report) | a documented ruling, but it is two output contracts on one name; every caller must know which arity it called |
| `seon.db/arity-mismatches` and `seon.fn/arity-mismatches` | `db.clj:3890`, `fn.clj:1799` | second path, one noun — delete the re-export |
| `db.clj:2726 commit-id` / `:2739 committed-value-identity` / `:2756 history` / `:2769 as-of` / `:2783 since` | each a two-arity wrapper over `d/*` with error passthrough | genuinely thin and correct; keep |
| `read-evidence` written twice: `:seon.db/read-result` (the inlined payload) and `:seon.db/read-result-digest` | `db.clj:904-957`; schema `resources/seon/schemas/seon.db.edn:109-111, 139-155` | two retained forms of one thing; both die with F1(c), with ~60 lines of `seon.db.edn` (`:66-83`, `:109-111`, `:139-155`) |

## 4. `defonce` / `delay` / atoms in this area

| site | holds | judgement |
|---|---|---|
| `db.clj:52-68` eight `defonce`+`delay` `requiring-resolve` holders | var resolution across a load cycle (`seon.error` and `seon.call-preparation` require `seon.db`) | the load cycle is the defect; the holders are the symptom. Breaking the cycle (move the error-shape predicate and the diagnostic constructor into a leaf) deletes all eight |
| `db.clj:146-163` four dynamic vars (`*conn*`, `*read-database*`, `*receipt*`, `*read-evidence-sink*`) | ambient custody and the evidence sink | `*conn*`/`*read-database*` are the ruled agent elision; `*read-evidence-sink*` is an invocation-local atom — correct. `*receipt*` uses the retired "receipt" vocabulary (AGENTS §3 vocabulary table) |
| `db.clj:1226-1256` `::read-projection` `delay` per read operation | one projection resolution per read | correct and deliberate (the 2026-08-07 84,664-file-read incident); operation-local, dies with the call |
| `schema.clj:397-416` `projection-cache` atom | see §3 | owned by the malli-native-bridge lane |

## 5. Tests

### 5.1 Tests that die with the mechanisms above

| deftest (file:line) | mechanism |
|---|---|
| `db_test.clj:389`, `:449`, `:2114`, `:2152`; `cluster/store_transact_test.clj:162`, `:176`; `schema/datahike_test.clj:477` | EDN codec (F2) — 7 deftests across 3 namespaces asserting one pair of rules |
| `db_test.clj:724`, `:763`, `:808`, `:822`, `:2183` | read-result digests and semantic replay (F1c) |
| `db_test.clj:669`, `:691`, `:845`; `read_evidence_test.clj:67`, `:70`, `:73` | index-pattern read evidence (F1a) — move with the mechanism into the fork's test suite |
| `db_test.clj:967`, `:2054` | `total-pull-selector` and the 1,000-member pull limit — become fork tests |
| `db_test.clj:1425`, `:1471`, `:1482` | the multi-arity `diff` family — delete. `repl_grammar_test.clj:24` keeps the value-diff grammar and stays |
| `db_test.clj:1964`; `publication_validation_test.clj` (both); `error_write_timing_test.clj:112`; `fn_test.clj:2314`, `:2374` | arity-mismatch validation — one class, five namespaces (§5.3) |
| `db_test.clj:2200` | pulled-form inference (`pulled-entity-schema-key`) |

`query-index-patterns`, `pull-index-patterns`, `decode-query-result`,
`decode-pull-result` and `validate-pulled-result` have ZERO direct references
under `test/` — they are reached only indirectly. A 168-line query walker with no
test naming it is its own finding.

### 5.2 `:seon.test/long-ms` in this area, classified

| file:line | bound | classification |
|---|---|---|
| `cluster/source_nochange_test.clj:7` | **600,000 ms** | **algorithm defect.** The test's own subject is "an unchanged digest performs zero transactions"; AGENTS.md states the answer is "two commit ids compared: milliseconds". The ten minutes are the FULL initial publication the fixture builds first. The seam that makes it O(edit) is the digest comparison itself plus the fork's `parent-commit-ids` / branch-head pointer (`datahike/api/specification.cljc:1083`); a branch fork is a pointer copy. Owned by `plan/one-jvm-publication-redesign-2026-09-22.md`. |
| `schema_redeclare_test.clj:72` | 300,000 | algorithm defect + genuinely long: it boots a child JVM (cold boot is an authorized slow operation) but the seeding inside it re-indexes the program. |
| `db/declaration_population_test.clj:16` | 180,000 | algorithm defect: "first canonical-fixture acquisition indexes the complete program before any assertion". O(program) setup for an O(1) assertion about projection carriage. |
| `schema/datahike_test.clj:360` | 120,000 | algorithm defect: publishing three renderer contracts should be bounded by three declarations. |
| `cluster/store_test.clj:498`, `:568` | 60,000 each | **genuinely long.** Both spawn a cold child JVM to prove the OS `fcntl` fence across processes — a cold JVM boot is one of the authorized slow operations, and the fence cannot be observed in one process. Keep. |
| `error_write_timing_test.clj:80` | 60,000 | **deletable with F3.** It exists to assert `seon.db/arity-mismatches-with` is NOT on a trace. Remove the O(program) step from the write path and the assertion has no subject. |
| `schema/datahike_test.clj:243` | 25,000 | genuinely long: 80 generative storage cases. Keep. |
| `cluster/source_test.clj:305`; `source_evidence_test.clj:13`; `source_lineage_test.clj:19`, `:148`; `schema/projection_acquisition_test.clj:37`, `:94`; `test/declared_reference_test.clj:7` | 10,000 each | algorithm defect in aggregate: each says "the canonical fixture acquisition costs most of this". Seven tests declaring the same 10 s because one shared construction is O(program). One shared fixture that indexes once is the seam; the same redesign owns it. |

### 5.3 Duplicate test classes (one invariant, several namespaces)

| invariant | namespaces |
|---|---|
| a call-arity write refuses with `:seon.fn/arity-mismatches` | `db_test.clj:1964`, `fn_test.clj:2314`/`:2374`, `publication_validation_test.clj` (×2), `error_write_timing_test.clj:112` — **6 deftests, 4 namespaces** |
| EDN codec round-trip + invalid-storage refusal | `db_test.clj:389`/`:449`, `store_transact_test.clj:162`/`:176`, `schema/datahike_test.clj:477` |
| "a handed declaration population is not resolved again" | `schema/declaration_population_test.clj:63`/`:82`, `sci/admit/declaration_population_test.clj:80`/`:118`, `db/declaration_population_test.clj:16` |
| a depended-attribute write invalidates retained evidence | `db_test.clj:669`/`:691`/`:845`, `read_evidence_test.clj:67`/`:70` |
| blob content is byte-exact across the inline threshold | `blob_test.clj:82`/`:109`/`:216`, `background_blob_test.clj:81`, `blob_threshold_test.clj:11` |
| an unchanged digest changes nothing | `source_nochange_test.clj:7`, `source_test.clj:446`, `source_lineage_test.clj:222` |
| two clusters in one JVM never share declarations | `registry_test.clj:233`, `registry_isolation_test.clj:51`, `store_test.clj:358` |
| `transact!` return shape / refusal preservation | `db_transact_shape_test.clj:46`, `transaction_result_test.clj:8`, `db_test.clj:304` |
| a silent stale-read refresh emits nothing | `rereads_test.clj:64`, `rereads_panel_test.clj:28` |

One regression per class is the rule (AGENTS §5). Collapsing these nine groups to
one member each retires roughly 20 deftests without losing a class.

## 6. Store, source, registry, blob

Verified on both sides. The headline is that this half of the seam is mostly
RIGHT: `source.clj` routes every mutation through `datahike.versioning`
(`branch!` `versioning.cljc:212-278`, `force-branch!` `:323-455`, `commit-as-db`
`:469-488`, `delete-branch!` `:279-320`) and re-implements no fork/merge/head;
`blob.clj` sits directly on `konserve.core/bassoc` (`core.cljc:673`), `bget`
(`:634`), `bget-range` (`:658`) and the fork's `gc_guard` permits
(`gc_guard.cljc:190`, `:281`) with reachability contributed through the
sanctioned `:datahike.gc/reachable-extension` seam (`gc.cljc:152`); the `flock`
in `store.clj:306-356` is genuinely ours — there is no `FileLock`/`tryLock`
anywhere in either vendored library, and `gc_guard.cljc:36-42` states Datahike
assumes one JVM. None of that should be deleted.

| src span | lines | duplicates / library seam | recommendation | what preserves the function | risk |
|---|---|---|---|---|---|
| `store.clj:194-197`, `:499-501` `open-configuration` | ~10 | `connector.cljc:197-237` `adopt-create-time-fixed` already adopts `:branching-factor`/`:diff-buf-size` (`:183-187`) and `:fuse-index-roots?`/`:commit-graph?` (`:189-196`) from the stored config | delete | the fork adopts them | none — fully redundant policy |
| `store.clj:368-373`, `:470-483` `stored-main-keep-history?` + `::keep-history-mismatch` | ~25 | `connector.cljc:144-181` `ensure-stored-config-consistency` already refuses a `:keep-history?` divergence | move to fork: give that function a typed `:keep-history-mismatch` | the refusal, one seam earlier | a pre-read the authority re-decides (AGENTS owner law 2026-08-29) |
| `store.clj:564` `(contains? @connections/*connections* connection-id)` | 1 | `connections.cljc:5-9` `active-connection` is count-aware; `delete-connection!` (`:124-127`) runs only at the END of the release drain, so `contains?` is true throughout it and during `:opening?`, where `active-connection` is nil | fix now | `registry.clj:165` already uses `active-connection` | **two different liveness answers to one question** — the §2.5 defect, and the wrong one refuses a legitimate reopen during a drain |
| `store.clj:137-140` `canonical-path` | 4 | `seon.operator.state/canonical-path` `state.clj:214-216`, already used by `store-lock-path` `:218-222` which `store.clj:142-151` delegates to | delete, alias | identical | none |
| `store.clj:121-124` `file-lock-generator` | 4 | — | delete or make it release | — | **sampling this generator acquires a real OS flock under `tmp/schema-generator/` and never releases it** (`fresh-file-lock` `:98-118`) |
| `registry.clj:126-152` `konserve-store`/`head-record`/`branch-commit-id`/`connection-branch-commit-id`/`commit-present?` | ~27 | the fork spells this exact read itself: `k/get-in store [branch :meta :datahike/commit-id]` at `gc.cljc:24`; `versioning.cljc:457-461` `commit-id` | move to fork: export `versioning/branch-commit-id` | one owner for "what commit is this branch at" | low; it is a pointer read, milliseconds |
| `registry.clj:347-351` active-connection pre-check on the retire path | ~8 | `versioning.cljc:293-302` `delete-branch!` ALREADY refuses `:branch-has-active-connection` | delete | the library's typed error, already handled at `registry.clj:356` | pre-read the authority re-decides. The `reset-cluster!` check at `:310-313` guards `force-branch!`, which has no such check — KEEP that one |
| `registry.clj:343-346` `::cannot-retire-main` | ~4 | `versioning.cljc:283-285` raises `:cannot-delete-main-db-branch` first | delete | same | same class |
| `registry.clj:446-495` `physical-filestore-inventory` | ~50 | walks `*.ksv` via `Files/newDirectoryStream`, hardcoding `konserve.filestore`'s private on-disk naming; `konserve/core.cljc:690-696` `k/keys` already yields per-key metadata (`:key`, `:last-write`), which `konserve/gc.cljc:22-30` consumes | move to fork: add size to konserve's `k/keys` metadata | the byte/file report, backend-independent | a directory walk is O(store), and it is the one place a second backend would break silently |
| `registry.clj:510-573` `dry-run!` + `dry-run-complete?` + the token | ~60 | throws out of `:konserve.gc/batch-issued` to abort the sweep. The callback contract sanctions it (`konserve/gc.cljc:8-17`: "throwing prevents the backing store from seeing it"), but there is no first-class dry run | move to fork: `:datahike.gc/dry-run?` in `gc.cljc:120-168` / `konserve.gc/sweep!` | a real dry run, no control-flow exception | a missing option standing in as an exception is the shape §2.3 warns about |
| `blob.clj:125-147` vs `:224-229` two byte-array-or-`InputStream` unwrappers | ~20 | internal | delete one | one `payload-bytes` | `:131` re-handles the `{:input-stream …}` map that `:405` destructures |
| `blob.clj:94` reaches `(:backing :base)` of the konserve store record | 1 | `konserve.filestore`'s private record shape | move to fork: a `konserve.protocols` accessor for the physical root | same value | a layering leak that breaks on any konserve refactor |

Incidental, in scope to fix when touched: several `registry.clj` docstring
citations no longer point at what they claim (`versioning.cljc:206-214` for the
roster — actual `branches` is `:182-189`; `:233-235` for `:branch-already-exists`
— actual `:246-248`; `:261-289` for `delete-branch!` — actual `:279-320`;
`:279-288` for the active-connection refusal — actual `:293-302`). A skill-grade
`file:line` claim that has drifted is a defect on sight.

## 7. What this area genuinely provides, and how each survives

| goal | how it survives the deletions |
|---|---|
| One synchronous, schema-checked write boundary for the whole system | untouched: `transact!` `db.clj:4578`, the final-report validator handed to the fork's own hook (`datahike/db/transaction.cljc:1207-1226`, fork commit `73afe782`) |
| A write that severs a connection the graph knows REFUSES, with the repair as data | untouched: `write-deletion-error`, `removed-definition-error` `db.clj:3937`, `write-owned-values-error` `:3647` — all bounded by the report's own datoms |
| Whole-entity and owned-component validation on the resulting datoms, not the submission | untouched (`:3647-3805`); it is already O(edit) |
| "Has this retained read gone stale?" before every agent turn | kept, with ONE mechanism: the revision comparison over Datahike's cache context (`db.clj:875`), with the fork owning finer index patterns |
| Rich heterogeneous values stored and read back exactly | `:db.type/any` natively (`datahike/schema.cljc:87`), no codec |
| A pull that never reports a cut collection as a complete one | the fork's limit default, plus the existing elision report |
| Agent-elided custody (`*conn*`, `*read-database*`) with a loud refusal when absent | untouched (`db.clj:365-412`) |
| Temporal views, commit identity, basis | untouched thin wrappers over `d/history`/`as-of`/`since`/`commit-id` |
| One cross-process store fence | untouched: the `flock` is ours and has no library counterpart |
| Content-addressed blobs with digest verification, tiering, and GC reachability | untouched: konserve has no digest verification and no content addressing |
| Branch-per-cluster publication where a fork is a pointer | untouched: every mutation already goes through `datahike.versioning` |

## 8. Estimate

| area | deletable / relocatable lines |
|---|---|
| `src/seon/db.clj` — F1 (read-currency, three mechanisms → one) | ~405 |
| `src/seon/db.clj` — F2 (declaration population + every decode/encode walker) | ~260 |
| `src/seon/db.clj` — the multi-arity `diff` family (no production caller) | ~330 |
| `src/seon/db.clj` — pulled-form inference, `total-pull-selector`, `jdk-integers->long`, the 56 inlined error-shape checks, the 8 load-cycle `defonce` holders | ~305 |
| `src/seon/db.clj` — F3 narrowing (arity + render-target to the report's datoms) | ~40 net |
| `src/seon/schema/datahike.clj` — the EDN codec | ~150 |
| `resources/seon/schemas/seon.db.edn` — replay/digest/pattern keys | ~60 |
| `src/seon/cluster/store.clj` | ~40 |
| `src/seon/cluster/registry.clj` | ~125 |
| `src/seon/blob.clj`, `src/seon/fn.clj` | ~27 |
| **src subtotal** | **~1,740** |
| tests that die with the mechanisms (§5.1) | ~900 |
| the nine duplicate classes collapsed to one member each (§5.3) | ~350 |
| `error_write_timing_test.clj` + `publication_validation_test.clj` (die with F3) | ~220 |
| **test subtotal** | **~1,470** |
| **total** | **~3,200 lines** |

`db.clj` goes from 4,638 to roughly 3,300 lines; about 29% of it is the three
findings.

## 9. Not verified

- Whether any attribute the bridge EDN-encodes also declares `:seon.db/index`,
  `:seon.db/identity` or `:seon.db/unique`. The bridge does not forbid the
  combination (`schema/datahike.clj:176-178` sets those facets independently of
  `::edn?` at `:122`), and `:db.type/any` has no total value comparator for the
  AVET index. Deciding F2 requires that enumeration; it needs the compiled
  projection, which needs a JVM.
- All timing claims. No number here was measured in this session; the `:long-ms`
  bounds in §5.2 are the tests' own declarations, and the measured writer numbers
  are quoted from `plan/malli-native-bridge-prd-2026-09-20.md:36-40`, `:392`.
- The `gc.cljc` / `writing.cljc` line citations inside `registry.clj`'s
  docstrings; the surrounding ones had drifted, so those should be re-checked
  when the file is next touched.
- Whether `datahike.query`'s cache alone (F1b) is sufficient for every retained
  read the turn loop holds. The three mechanisms were added in sequence and the
  order in `read-evidence-current?` (`db.clj:1102`) implies (b) was found
  insufficient at least once; the evidence for that is not in the file.
