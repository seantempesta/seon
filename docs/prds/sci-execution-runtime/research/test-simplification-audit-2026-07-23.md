---
type: research
status: active
tags: [research, testing, runtime, simplification]
---

# Test-simplification migration audit — 2026-07-23

Read-only audit of branch `codex/runtime-reliability-refactor`. No files were edited, tests were not run, and the current uncommitted `test/seon/db/writer_test_support.clj` was treated as an in-flux preview of the owner-ruled mechanism.

## Executive verdict

The test surface contains 228 discovered `*_test.*` files and 2,296 source `deftest` definitions.

The highest-value migration is fixture genesis. Eight host integration namespaces contain 20 raw pre-subject transactions that construct cluster-like state outside production paged initialization. Five additional writer/database suites own six bespoke initialization producers plus eight repeated schema-install calls. Those should consume the shared writer fixture entry once it lands.

The fragile audit’s “56 exact-prose pins in 28 files” is reproducible, but the signature is lexical:

- 38 pins in 14 files are genuinely fragile steering, error, status, or production-doc prose and should migrate.
- 18 pins in 14 files are legitimate exact-output oracles and must remain exact.
- One adjacent four-word quiescence error at `test/seon/client_quiescence_test.cljs:207` belongs in the migration despite falling outside the reported 56.

The accepted C1 property supports a certain net reduction of 13 source deftests: six Transit point tests and eight duplicated protocol definitions replaced by one generative property. Consolidating the six duplicated render byte-parity tests into one table-driven cross-runtime regression saves another five. The hard, directly justified reduction is therefore about 18 source deftests; bounded follow-ons raise the practical estimate to 21–25.

The audit found only one clearly stale private-dispatch/call-count test: `my.plan`’s pre-U7 renderer interception. Other atoms in low-level transport, executor, resource, and concurrency tests generally record externally meaningful events and should remain.

## Governing dependency ledger

| Contract | Existing authority | Test migration dependency |
|---|---|---|
| Fixture genesis | `protocol/initialization-pages` at `src/seon/db/protocol.cljc:1624`; `ensure-database-request` at `:1689`; in-flight shared consumer at `test/seon/db/writer_test_support.clj:30-118` | Consume the landed shared entry. Do not copy its current signature or create another fixture initializer. |
| Paged initialization proof | `test/seon/db/writer_initialization_test.clj:151-233`; standalone artifact scar in `test/seon/writer_standalone_schema_test.clj` | These remain the authoritative initialization regressions. |
| Wire families | `::protocol/request`, `src/seon/db/protocol.cljc:830-856`; `::protocol/response`, `:1101-1132` | C1 property must cover every union arm and relevant optional branch before point deletion. |
| Generators | Malli `generate`/`sample`, `reference-code/malli/src/malli/generator.cljc:505-512`; `::protocol/ordinary-wire-value` generator at `src/seon/db/protocol.cljc:188-198` | Generated encoded values must satisfy `ordinary-wire-value?` and encode/decode equality. |
| Catalog-derived entity generation | `:seon.schema.projection/catalog`; accepted plan at `research/malli-root-enforcement-2026-07-23.md:321-325` | Replaces only literal value-acceptance examples, not lookup-ref, retraction, component, or concurrency behavior. |
| Portable database test seam | `db/bind-leaf`, `src/seon/db.cljc:201-210`; example at `test/seon/db/portable_test.cljc:328-345` | Surviving toolkit tests should stop process-wide `set! db/*` interception as touched. |

The earliest unsettled contract is acceptance of the shared complete-program paged fixture. The integrated proof that closes it is the migrated writer gate plus the retained crash/restart/order and standalone-artifact scars. The program’s final graduation gate remains the complete writer, CLJS, and operator checkpoints plus fresh-reset live proof; this audit does not replace that reset boundary.

## 1. Fixture and genesis inventory

### Counts and classification

The sweep found:

- 17 direct `d/create-database` sites in 10 files.
- 15 writer dependency declarations; three install data and 12 are no-op initializers.
- 20 raw pre-subject transaction sites across eight host integration namespaces.
- Six additional static bespoke producer sites across five writer/database files, plus eight repeated calls to one schema installer.

“Genesis” here means state installed solely to make an otherwise empty database resemble a booted cluster before the subject behavior starts. A transaction whose response, conflict, event, temporal cut, branch movement, CAS outcome, or schema derivation is the test subject is not genesis.

### Host integration fixtures

| Suite | Current genesis and seeded state | Migration | Difficulty |
|---|---|---|---|
| `test/seon/host_interrupt_writer_test.clj:135-143` | Raw canonical schema rows and value-sampling policy | Supply those rows through the shared paged fixture | Mechanical |
| `test/seon/host_instrument_writer_test.clj:19-30,271-299` | Private registry corpus alias; canonical schemas, policy, agent/process/turn, provenance, optional function attrs | Shared complete-program fixture plus initial rows; delete private alias | Mechanical |
| `test/seon/host_graduate_writer_test.clj:15-28,129-159` | Same private corpus dependency and two SCI genesis transactions | Shared paged fixture | Mechanical |
| `test/seon/host_authored_invocation_writer_test.clj:18-27,93-134` | Canonical base plus policy, agent/process/provenance, then authored function rows; returns a pre-functions database fence | Put base and authored fixture rows through shared initialization while preserving the returned fence used by the scenario | Needs thought |
| `test/seon/host_registry_writer_test.clj:167-182,206-213,412-443` | `register-runtime-schemas!`, `seed-schema-rows!`, generated identities, provenance, sampling policy, owning entities | Move all pre-subject rows/candidates into one shared initialization; retain later behavior transactions | Needs thought |
| `test/seon/program_plan_writer_test.clj:16-17,39-95` | Reaches into registry-test private vars; four raw fixture transactions install schemas, provenance and exact-set edge/function state | Direct shared fixture use; remove all cross-test private-var access | Needs thought |
| `test/seon/host_cancel_writer_test.clj:173-203` | Canonical base plus a test-local sentinel schema and physical probe row | Pass sentinel declaration and probe row as shared initial data | Needs thought |
| `test/seon/host_hostile_battery_writer_test.clj:256-288` | Same two-phase base plus hostile-battery sentinel, probe entities and optional function attrs | Shared initialization including the test-local sentinel | Needs thought |

Three `host_registry` fixtures already use the emerging shared path at `test/seon/host_registry_writer_test.clj:200-205,405-411,677-680`. They are the local migration pattern, subject to the helper’s in-flight API changing.

The first mechanical batch is `host_interrupt`, `host_instrument`, and `host_graduate`.

### Additional bespoke producers

| File | Current producer | Migration | Difficulty |
|---|---|---|---|
| `test/seon/db/remote_contract_test.clj:34-69` | `seed-database!` writer initializer installs five attrs and rank-dependent linked records | Shared paged fixture with rank-dependent initial rows; delete initializer | Mechanical |
| `test/seon/db/writer_read_ceiling_test.clj:23-44` | Initializer installs two attrs and 1,500 entities in three batches | Shared paged initial data; begin read measurement only after initialization | Needs thought |
| `test/seon/db/generated_id_transaction_test.clj:11-99` | Handwritten schema transaction and custom initializer install eight attrs and three generator-policy rows | Shared initialization before the first tested allocation | Needs thought |
| `test/seon/db/request_receipt_test.clj:88-94` | `install-schema!` installs `:receipt/value`; called at `:119,160,211,241,302,391,454,562` | Initialize each fixture through the shared path; delete helper and eight calls | Needs thought: initialization receipts must not be confused with the request receipt under test |
| `test/seon/db/executor_test.clj:1013-1023,1093-1103` | Two registry callbacks transact schema plus one row per database | Initialize databases before executor submission using the shared fixture | Needs thought: preserve the executor boundary without inventing a registry-local initializer |

### Direct Datahike fixtures needing bounded judgment

Seven direct-create sites are fixture preparation rather than the asserted dependency behavior:

- `test/seon/agent/turn_llm_writer_test.clj:23-53,93-121`: duplicated run/agent/turn/attempt/blob population.
- `test/seon/config_test.cljs:1584-1624`: derived config schema and stored config entity.
- `test/seon/db/id_test.cljc:225-249,425-436`: duplicated allocation schemas.
- `test/seon/db/restore_admin_test.clj:92-139`: schema and branch-sensitive restore populations.
- `test/seon/db/writer_interest_test.clj:802-819`: schema/entity population before 1,000 interests.

These are “needs thought,” especially the CLJS and branch-sensitive cases. The migration must use the shared writer boundary and must not create a second direct-Datahike approximation of paged boot.

The following direct databases are subject tests and should not be migrated onto the fixture helper:

- Temporal commit containment: `test/seon/db/branch_test.cljc:78-94`.
- Malli→Datahike mapping, rejection, no-history and native round-trip tests: `test/seon/db/datahike/schema_test.clj:198-675`.
- A genuinely empty database value: `test/seon/db/program_test.clj:104-116`.
- Rejection of an old unfused database: `test/seon/db/registry_test.clj:71-88`.
- Missing/incompatible protocol schema rejection: `test/seon/db/writer_integration_test.clj:468-501`.
- Empty interest-scope construction: `test/seon/db/writer_interest_test.clj:404-428`.

Moving these would make the proof circular or erase the behavior being tested.

### Named seed helpers that remain ordinary operations

These create scenario state after the cluster fixture exists:

- `seed-agent-turn!`, `test/seon/host_cancel_writer_test.clj:53-59`.
- `seed-agent!` and `seed-turn!`, `test/seon/host_hostile_battery_writer_test.clj:149-160`.

They should not become initialization pages. Their transactions are scenario stimuli, not alternate genesis.

## 2. Exact-prose audit

### Count reconciliation

The current tree reproduces 56 five-plus-word exact string equalities in 28 files.

| Classification | Count |
|---|---:|
| Fragile steering/error/status/doc pins | 38 in 14 files |
| Legitimate exact-output oracles | 18 in 14 files |
| Total lexical matches | 56 in 28 files |

### Migration targets by cluster

| Cluster | Pins | Stable replacement |
|---|---:|---|
| Database envelopes | `test/seon/db_remote_contract_test.cljs:282,891` | Invalid request: `:seon.error/kind :user-input`, request ID/error data, no transport. Mixed execute-many: `:core-bug`, exact `::db/member-databases`, no transport. |
| Planned quiescence | `test/seon/client_quiescence_test.cljs:142,184,228`; adjacent miss at `:207` | Assert `:core-bug` and injected nested failure; retained `::client/quiesced-run-ids`; or offending turn ID/status and final database. Keep no-close/no-pull effects where relevant. |
| Web child error | `test/seon/web/datastar_test.cljs:151` | Assert `:main`, `::db/read-evidence :all`, non-empty visible error containing only the governing `"query-results"` token. |
| Execution IPC/selection | `test/seon/execution_test.cljs:300,1017,1020,1306` | Assert `:core-bug` plus value path/type; distinguish absent function `:agent` from unloaded function `:core-bug` using `::execution/function-symbol`; retiring frame carries error message kind, invocation ID and poisoned/closed state. |
| JVM authored identity | `test/seon/host_authored_invocation_writer_test.clj:189,195,200` | Assert nested `:agent` kind, requested function symbol, and stale/current source-digest relationship. |
| Host conformance | `test/seon/host_conformance_writer_test.clj:516,1017,1028,1044,1062,1088,1099` | Assert correlated invocation ID, kind, and governing field: database selection, agent ID, deadline, active invocation, artifact digest, function symbol/source digest, or result byte limit. |
| Pod execution host | `test/seon/execution/host_test.cljs:389,579,1289` | Assert `:core-bug` plus cause/PID, exit code and correlation, or `::execution/child-retired?`; retain recovery behavior. |
| Operator lifecycle | `test/seon/dev/process_test.clj:1094,1105,1277,1695,2161` | Assert startup/unwind data, managed-process status and required transition, requested/result ID sets, or containment classification and retained record. |
| Agent run acquisition | `test/seon/agent/run_test.cljs:106` | Assert `:core-bug`, failed execute-many member envelope, and injected `"running turns unavailable"` token. |
| First-form narration | `test/seon/agent/turn_test.cljs:96` | Assert one retained form, exact retained source, two skipped forms, and only stable steering tokens `"first-form"`, `"2"`, `"resend"`. Migrate the nearby `str`-assembled singular case too. |
| Query-result framing | `test/seon/eval/receipt_test.cljs:76,79,85,88` | Assert the four classification keys `:scalar`, `:tuple`, `:collection`, `:relation`; keep one non-empty integration framing assertion. |
| Missing namespace | `test/seon/agent_render_namespace_test.cljs:83` | Assert namespace token `"pure.absent"` and stable absence marker `"(not in db)"`, without freezing punctuation. |
| Namespace docs | `test/seon/index_core_test.cljs:260,272` | Use a documented synthetic namespace or derive expectation from the same sidecar source. Assert `:seon.ns/name`, `:seon.ns/summary`, and `:seon.ns/doc`, not live production prose. |
| Public var docstring | `test/seon/db/id_test.cljc:583` | Assert symbol, non-empty `:doc`, and declared `:malli/schema`; use a synthetic var for exact metadata parity if needed. |

The mechanically safe first batch is the 29 error/status equalities in the first nine rows. Most already have the stable kind, token, offending key, or error data asserted immediately beside the prose.

The remaining nine migration targets require small behavioral rewrites rather than a blind equality replacement.

### Exact oracles that remain

These 18 matches should not be touched by the steering migration:

- Parser/source fidelity:
  `test/seon/repl/parse/repair_candidates_test.cljs:23`,
  `test/seon/repl/parse_test.cljc:196,999,1073`.
- Renderer byte contracts:
  `test/seon/render/value_test.cljs:901,1092,1098`,
  `test/seon/eval/memory_safety_test.cljs:175`.
- Filesystem edit results:
  `test/seon/agent/fs/match_test.cljc:40`,
  `test/seon/agent/fs_test.cljs:286,405`.
- Stream/text assembly:
  `test/seon/ai/typeahead_test.cljs:180`,
  `test/seon/ai/openai_compat_test.cljs:453`,
  `test/seon/ai/http_test.clj:178`.
- Parsed fixture or presentation contracts:
  `test/seon/config_test.cljs:1361`,
  `test/seon/agent/testrun_test.cljs:95`,
  `test/seon/agent/debug_test.cljs:110`.
- Stored schema serialization:
  `test/seon/db/writer_initialization_test.clj:423`.

## 3. C1 and other generative consolidation

### Six C1 point tests

Delete these only after the C1 property proves per-arm coverage:

| Point test | Lines | Generating schema |
|---|---:|---|
| `transit-roundtrip-preserves-native-protocol-values` | `test/seon/db/transport_uds_test.clj:356-377` | `::protocol/transaction-request` through `::protocol/request`; `:seon.db/db`, transaction data/meta |
| `transit-decodes-aggregate-query-lists-as-eager-protocol-data` | `:379-407` | `::protocol/query-request`, `::execute-many-request`, `::member`, `::query-form` |
| `database-acquisition-is-closed-correlated-and-transit-stable` | `:431-453` | Acquire request/response union arms |
| `transaction-branch-head-request-and-response-are-transit-stable` | `:455-480` | Resolve-transaction-branch-head request/response and branch-head schemas |
| `ensure-request-roundtrip-preserves-explicit-connection-id` | `:482-496` | Ensure request plus optional branch connection ID |
| `lifecycle-requests-are-closed-and-transit-stable` | `:498-537` | Create/release/delete branch request arms |

The replacement property is:

1. Generate from every registered request and response arm.
2. Apply the schema-selected wire projection.
3. Assert `ordinary-wire-value?`.
4. Assert `uds/encode → uds/decode → =`.
5. Record coverage of each union arm and relevant optional branch.

Random sampling of the top-level union without arm coverage is insufficient.

Retain:

- The constructor behavior in `execute-many-reuses-existing-read-shapes-with-one-public-identity`, `transport_uds_test.clj:409-429`; only its isolated round-trip equality is redundant.
- The real-session lifecycle test at `:539` onward.
- Every framing, capacity, concurrency, response-slot, partial-write, backpressure, close, and native-resource test.
- The real drill seed/session-survival regression at `test/seon/host_eval_wire_safety_writer_test.clj:28-75`.

### Protocol shape twins

Current source counts are 17 deftests in `protocol_test.clj` and nine in `protocol_test.cljs`.

Eight of the nine CLJS definitions duplicate portable families in the JVM suite:

- Tempid receipt alternatives: CLJS `:26-34`, JVM `:30-38`.
- Session opening: CLJS `:43-79`, JVM `:53-99`.
- Database values: CLJS `:81-87`, JVM `:101-110`.
- Query database placement: CLJS `:89-102`, JVM `:112-138`.
- Execute-many member databases: CLJS `:104-123`, JVM `:304-324`.
- Nested pull ordinary data: CLJS `:125-136`, covered by read-response/result families.
- Native transaction reports: CLJS `:138-162`, JVM `:326-373`.
- Generated candidates: CLJS `:184-204`, JVM `:450-469`.

Move portable assertions to one `.cljc` owner. Retain small platform leaves for:

- JVM lazy seq, future, promise, thread and throwable rejection at `protocol_test.clj:436-448`.
- CLJS Promise, Error and host-object rejection at `protocol_test.cljs:164-182`.

Structural generation does not replace semantic predicates outside Malli, including the one-temporal-bound/current-database checks and generated-candidate key uniqueness at `src/seon/db/protocol.cljc:1807-1840`.

### Other schema-generated families

| Family | Current points | Replacement and retained behavior |
|---|---|---|
| Execution IPC shapes | `test/seon/execution_test.cljs:108-125,135-161` | Generate from `:seon.execution/parent-message` and `:seon.execution/child-message`, `src/seon/execution.cljs:91-189`; retain million-segment bounded-work proof at `execution_test.cljs:120-133`. |
| Datahike native numeric overrides | `test/seon/db/datahike/schema_test.clj:527-564` | Catalog-derived entity property subsumes the whole literal test. |
| Datahike valid-value portions | `schema_test.clj:465-525,566-604,606-732` | Fold scalar/native acceptance into catalog generation; retain lookup-ref identity, invalid-type rejection, cardinality-many retraction, component acquisition/removal, optionality and replacement transitions. |
| `my.*` request-schema census | `test/my/request_schema_test.cljs:15-72` | Replace the handwritten 34-key set with the already-computed request-schema population, generate one valid request per schema, inject one typo, assert rejection. |
| Named predicate generator spot list | `test/seon/schema_projection_writer_test.clj:111-126` | Compute every registered predicate schema carrying `:gen/schema`; this becomes one generator-health property. |
| Items envelope points | `test/seon/items_test.cljs:24-35` | Generate valid empty/non-empty `:seon.items/envelope` values; include an invalid-mutation property before deleting the wrong-boolean point. |

Do not fold the Datahike bridge’s schema-shape mapping and rejection tests at `test/seon/db/datahike/schema_test.clj:27-463`. Entity-value generation cannot prove type mapping, cardinality, property translation, alias-cycle diagnostics, nested-map rejection, or component/ref derivation.

## 4. Duplicate and overlapping invariant suites

| Invariant | Duplicate surface | Single authoritative gate |
|---|---|---|
| Protocol wire totality | Six transport points, nine JVM protocol round-trip assertions, eight CLJS portable twins | One per-arm C1 generative property plus the retained real session-survival regression |
| Portable protocol shapes | `protocol_test.clj` and `.cljs` | One shared `.cljc` source; platform host-object rejection leaves remain |
| Render cross-runtime byte identity | JVM `test/seon/render/value_writer_test.clj:48-52,117-134`; CLJS `test/seon/render/value_test.cljs:269-302` | One table-driven `.cljc` byte/fingerprint regression covering ordinary values, schema-aware maps and nested schemas |
| Request-map closure | Hand-maintained 34-schema list in `test/my/request_schema_test.cljs:15-49` | Computed schema population plus generated typo mutation |
| Predicate generator health | Three-key spot list in `schema_projection_writer_test.clj:111-126` | Computed property over all registered predicate schemas with generators |
| Query-result classification narration | Four exact prose assertions in `eval/receipt_test.cljs:76-88` | One table-driven classification test over `:scalar/:tuple/:collection/:relation`, plus one framing integration assertion |

Important non-duplicates:

- `writer_initialization_test` and `writer_standalone_schema_test` cover runtime page recovery and packaged-artifact completeness respectively; both remain.
- Missing/stale authored-function tests in host-authored invocation and host conformance exercise database selection and wire framing at different boundaries.
- `db_session_test.cljs` initialization serialization and `db_remote_contract_test.cljs` reconnect/listener behavior overlap in vocabulary but not invariant.
- Low-level UDS CLJ and CLJS suites own JVM server and Bun socket/parser behavior respectively; they are not wholesale twins.

## 5. Atom internals and private-seam assertions

### Migrate

| Test cluster | Current seam assertion | Replacement |
|---|---|---|
| `test/my/plan_test.cljs:149-183` | `with-redefs` of compiled private renderers and `[1 1]` call counts | Invoke the production resolution path. Assert valid plan hiccup contains the root title, malformed data uses generic rendering, and an already projected value does not re-enter custom rendering. |
| `test/my/plan_test.cljs:110-130` | Counts one `db/execute-many` call | Seed plan rows through the shared database leaf and assert the rendered plan value. Acquisition count is not the contract. |
| `test/my/canvas_test.cljs:81-118` | Process-wide database redefinition and zero transaction count | Assert `:seon.error/kind :agent`, renderer symbol token, and unchanged canvas fact from the fixture database. |
| `test/my/kb_test.cljs:396-445,491-524` | Counts that later query/pull seams were not invoked | Assert the returned failure/success envelope and result items. Use a bound leaf or fixture database; short-circuit call count is not separately authoritative. |
| `test/my/skills_test.cljs:166-183,186-233,235-301` | Counts installs, queries, pulls and database acquisitions | Assert the absent-skill error envelope, omitted render, and final skill list/loaded state from database facts. |
| Remaining `my.plan` orchestration counters | Examples at `test/my/plan_test.cljs:701-744,784-932,1046-1153,1297-1475` | Run against a fixture database and assert plan/cause/step/message datoms and terminal envelopes. Keep a counter only where an externally ruled retry bound is itself the behavior. |
| Session private state | `test/seon/db_remote_contract_test.cljs:206,232,872,1021,1130`; `test/seon/db_session_test.cljs:135,156,250-257,354-372` | Stop reading/resetting `session/!session`. Assert public database values, `attached?`, listener replacement/unlisten results, delivered events, and reconnect envelopes. |

The current snapshot contains 53 CLJS test files with `set! db/`; the earlier fragile audit reported 55. This is an in-flight-tree count, not a new architectural conclusion. Most are pod suites whose survival is decided by U9, so a repository-wide pre-U9 rewrite is not justified. Apply `db/bind-leaf` to survivors as they are touched.

### Retain

Do not convert test-local atoms that record the public behavior of low-level mechanisms:

- UDS admissions, output slots, closes, partial writes and capacity.
- Executor completion, cancellation and maximum concurrency.
- Registry connection/release ownership.
- Writer interest delivery/coalescing/backpressure.
- Resource cleanup exactly once.
- Explicit retry-attempt ceilings.
- Host conformance’s “no eval after lost fence” checks.

These counts are the externally observable concurrency/resource contract. Replacing them with unrelated database facts would weaken coverage.

Writer-side tests already predominantly assert transaction reports, receipts, database values and envelopes. No general writer atom-internals migration is warranted.

## 6. Ranked migration worklist

### Rank 1 — mechanical fixture batch

Migrate:

- `host_interrupt_writer_test.clj`
- `host_instrument_writer_test.clj`
- `host_graduate_writer_test.clj`

Delete private corpus aliases and raw genesis transactions. Consume only the landed shared fixture entry.

Exit: focused host tests green; each scenario begins from the shared initialized database; initialization scar tests remain unchanged.

### Rank 2 — remaining fixture genesis

Migrate the remaining host fixtures, then:

- `db/remote_contract_test.clj`
- `db/writer_read_ceiling_test.clj`
- `db/generated_id_transaction_test.clj`
- `db/request_receipt_test.clj`
- the two executor initialization callbacks

Handle generated candidates, sentinel schemas, authored-source fences and receipt separation deliberately.

Exit: no suite-owned canonical-schema producer, no cross-test private fixture access, and no raw pre-subject schema installation in these suites.

### Rank 3 — mechanical steering pins

Replace the 29 error/status prose equalities with kind plus governing key/symbol/config token or durable lifecycle data. Include the four-word quiescence companion at `client_quiescence_test.cljs:207`.

Exit: humanize/spell-checking may change presentation without changing these tests.

### Rank 4 — behavioral prose and private seams

Migrate the nine non-error prose pins, the stale `my.plan` renderer dispatch test, and the identified session-private state reads. Move surviving database fakes to `db/bind-leaf` or the shared fixture.

Exit: production renderer resolution is tested by outcome, and session ownership is asserted through public values/events.

### Rank 5 — C1/property consolidation

After the C1 property exists and reports per-arm coverage:

- Delete six UDS point tests.
- Remove nine redundant protocol round-trip assertions.
- Merge eight portable CLJS protocol definitions into one `.cljc` owner.
- Retain platform-host rejection leaves and semantic invalid cases.

### Rank 6 — bounded follow-on consolidation

- Merge render byte-parity fixtures into one table-driven `.cljc` regression.
- Derive the request-schema closure census.
- Generalize predicate generator health.
- Fold execution IPC shape points and catalog-generated Datahike literal examples.

## 7. Deletion ledger

| Delete or fold | Replacing constraint |
|---|---|
| `register-runtime-schemas!` and `seed-schema-rows!`, `host_registry_writer_test.clj:167-182` | Shared complete-program paged initialization |
| Private `corpus-schema-rows` aliases/cross-test `var-get` access | Shared support is the sole fixture program producer |
| Host-authored `seed-database!`, `:93-134` | One paged initialization including authored fixture rows |
| `db/remote_contract_test.clj` and `writer_read_ceiling_test.clj` seed helpers | Domain rows supplied as paged initial data |
| Generated-ID schema transaction/custom initializer | Generator policies exist before tested allocation through shared initialization |
| `request_receipt_test.clj` `install-schema!` and eight calls | Receipt fixtures begin from the shared initialized database |
| Two executor initialization write callbacks | Databases initialized before executor behavior |
| 38 fragile prose equalities | Kind plus governing data/token, source-derived metadata, or stable classification behavior |
| `my.plan` renderer redefs and call counters | Production-path render outcome |
| Six UDS Transit point deftests | Per-arm C1 generative totality property |
| Eight duplicated CLJS protocol deftests | One portable `.cljc` source plus platform leaves |
| Nine isolated JVM protocol round-trip assertions | Same C1 property; semantic assertions remain |
| Six render byte-parity deftests folded to one | One table-driven cross-runtime byte/fingerprint gate |
| Handwritten 34-request-schema list | Computed request-schema population |
| Three-key predicate-generator spot list | Computed generator-health property |
| Numeric Datahike override point test | Catalog-derived entity install/transact property |
| Items envelope examples, once invalid mutation exists | Generated valid values plus one mutation property |

## 8. Estimated reduction

Hard, directly justified reduction:

- Six UDS Transit point deftests removed.
- Eight duplicated protocol definitions removed.
- One C1 property added.
- Six render parity deftests collapsed to one.

Net: approximately **18 fewer source deftest definitions**.

Bounded follow-ons:

- Execution IPC shape consolidation: roughly two fewer.
- Query-result framing four-to-one: three fewer.
- Numeric Datahike point: one fewer.
- Items envelope folding: zero or one fewer depending on property layout.

Practical total: **21–25 fewer source deftests**, roughly 0.9–1.1% of the current 2,296 definitions. The larger simplification is structural: removal of more than 26 raw/pre-subject fixture transaction sites, eight repeated schema installs, cross-test private fixture coupling, and dozens of presentation-sensitive equalities.

## 9. Bounded Sol implementation lanes

### Sol lane 1 — mechanical host fixture migration

Owned files:

- `test/seon/host_interrupt_writer_test.clj`
- `test/seon/host_instrument_writer_test.clj`
- `test/seon/host_graduate_writer_test.clj`

Dependency: accepted shared writer fixture helper.

Work: replace raw base seeding and private corpus aliases; no helper changes.

Proof: focused tests, then handoff for the frozen writer checkpoint.

### Sol lane 2 — complex host and writer fixtures

Owned files:

- `test/seon/host_authored_invocation_writer_test.clj`
- `test/seon/host_registry_writer_test.clj`
- `test/seon/program_plan_writer_test.clj`
- `test/seon/host_cancel_writer_test.clj`
- `test/seon/host_hostile_battery_writer_test.clj`
- `test/seon/db/remote_contract_test.clj`
- `test/seon/db/writer_read_ceiling_test.clj`
- `test/seon/db/generated_id_transaction_test.clj`
- `test/seon/db/request_receipt_test.clj`

Work: migrate all pre-subject cluster state to the shared entry while preserving authored-source fences, generated candidates, sentinel schemas, read-ceiling timing and receipt identity.

Stop condition: any need to modify `protocol/initialization-pages` or the shared helper mechanism returns to the owner rather than inventing another path.

### Sol lane 3 — steering and private-seam cleanup

Owned files: the 14 fragile-prose files, `test/my/{canvas,kb,plan,skills}_test.cljs`, `test/seon/db_session_test.cljs`, and non-overlapping portions of `db_remote_contract_test.cljs`.

Work:

- Convert 38 prose pins and the quiescence companion.
- Preserve the 18 exact oracles.
- Replace stale renderer/private-session assertions with production outcome, envelope, database-value and event assertions.
- Use `db/bind-leaf` only for surviving portable tests.

Proof: focused CLJS namespaces; no full-suite run while other source lanes are active.

### Sol lane 4 — property-driven deletion and duplicate consolidation

Dispatch only after the C1 property is accepted.

Owned files:

- `test/seon/db/transport_uds_test.clj`
- `test/seon/db/protocol_test.{clj,cljs}` and shared replacement
- `test/seon/execution_test.cljs`
- `test/seon/schema_projection_writer_test.clj`
- `test/my/request_schema_test.cljs`
- `test/seon/db/datahike/schema_test.clj`
- render byte-parity test files

Work: delete only coverage proven by the landed properties; retain platform objects, semantic predicates, hostile bounds, lookup refs, retractions, components, concurrency and real-session scars.

Proof: per-arm generator coverage evidence, both boundary runners, then orchestrator-owned frozen full checkpoint.

## Final graduation condition

This migration is complete when:

1. all cluster-like fixtures consume the one complete-program paged initializer;
2. the retained initialization recovery and standalone-artifact scars remain green;
3. fragile steering tests assert behavior and structured data while exact serializers remain byte-exact;
4. C1 generation covers every request/response arm before point deletion;
5. portable invariants have one shared source with only true platform leaves;
6. high-level suites no longer assert private renderer/session machinery; and
7. the frozen writer, CLJS and operator checkpoints plus fresh-reset live proof pass on one coherent source state.