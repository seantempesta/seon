---
type: research
status: active
tags: [research, runtime, testing]
---

# Pod test coverage at the JVM cut

## Question and verdict

The pod cut does lose coverage that the surviving system still needs. The loss
is not one undifferentiated CLJS suite:

- pod/self-host/Node execution tests can leave with the code they assert;
- 23 portable `.cljc` namespaces already run under `bin/test-writer`;
- one misleadingly named `.cljc` namespace, `my.blob-test`, is actually
  CLJS-only and is not discovered by `bin/test-writer`; and
- the remaining surviving and mixed CLJS namespaces contain invariant classes
  that need fresh JVM tests at the driver, database, schema, program, capability,
  and render owners.

The replacement rule is the program rule: claim the invariant on the surviving
mechanism. Do not port an old async fixture, Promise rail, ambient pod binding,
or child/session mock line by line.

## Inventory method and count reconciliation

`shadow-cljs.edn` configures the `:test` build with `:ns-regexp "-test$"`.
`bin/test-cljs` uses that full scan unless a focused namespace vector is
supplied. `script/seon/dev/test_roots.clj` independently reads namespace forms
and shows which `.clj`/`.cljc` files `bin/test-writer` retains.

At this checkout:

| inventory | namespaces | actual test forms | note |
|---|---:|---:|---|
| runner-visible `.cljs` | 99 | 1,100 | Namespace ends in `-test`; excludes nine fixtures/probes/preloads. |
| runner-visible `.cljc` | 24 | 211 | Shadow sees all 24. |
| `.cljc` already retained by `bin/test-writer` | 23 | 196 | Same namespace and test forms run with the `:clj` reader feature. |
| `.cljc` excluded from `bin/test-writer` | 1 | 15 | `my.blob-test` unconditionally requires `node:*` and `cljs.test`. |

The earlier 98 / 1,080 / 191 figures are stale or were not derived by the
current discovery rules. The prompt's 1,216 figure is reproducible as raw
`deftest` **tokens** in `.cljs` files, but it includes the `cljs.test` refer in
each namespace plus comments and prose. Counting actual `(deftest …)` forms in
the 99 runner-visible namespaces gives 1,100. The classification below is by
namespace as requested, so neither token-count convention changes the verdict.

Verdicts:

- **A — CLAIM:** a surviving mechanism lacks equivalent retained coverage;
  `bin/test-writer` must claim the named invariant class.
- **B — DELETE:** the namespace asserts a deleted pod, self-host, Node, child,
  or pod-only orchestration path.
- **C — MIXED:** delete the old path, but claim the named invariant class on its
  surviving owner.
- **D — COVERED:** an existing retained JVM namespace already claims the
  relevant surviving ground; the citation names it.

## Namespace classification

### Runner-visible `.cljs` namespaces

| namespace | verdict | surviving invariant or covering citation | evidence |
|---|---|---|---|
| `my.canvas-test` | C — MIXED | Claim one database-pinned canvas selection and a closed action request; discard Promise/Datastar call plumbing. | Requires `my.canvas`, `seon.db`, and pod `seon.web.reactive.transform`; asserts renderer validation, current namespace selection, one frozen database value, and remote pull awaiting. |
| `my.data-test` | B — DELETE | None. | Requires deleted `my.data`; assertions are its row/group/sum/max convenience surface and pod database envelopes. |
| `my.kb-test` | C — MIXED | Claim canonical knowledge transaction data, one-database-value recall, ranked bounded results, and errors as values; discard `my.kb.shared` pod acquisition. | Requires portable `my.kb` plus deleted `my.kb.shared`; assertions separate pure formatting/recipes from async shared-block acquisition. |
| `my.ns-test` | B — DELETE | None. | Requires deleted `my.ns` and pod context admin/namespaces functions; assertions drive the retired full/compact block mutation surface. |
| `my.plan-test` | A — CLAIM | Pure plan compilation/derivation, snapshot-fenced mutation, evidence-owned terminal transitions, idempotent message+plan commits, and cycle-safe reconciliation. | Requires portable `my.plan`, `my.plan.internal`, `seon.db`, `seon.db.id`, and `seon.agent.message`; 62 tests assert ordinary rows, CAS races, exact transaction data, and zero-write convergence. |
| `my.request-schema-test` | C — MIXED | Every surviving agent-facing request map is closed to unknown keys; drop the deleted `my.data`/`my.ns` members. | Requires `my.blob`, `my.canvas`, `my.data`, `my.kb`, `my.ns`, and `my.ui`; its sole assertion audits both surviving and deleted request schemas as one class. |
| `my.skills-test` | A — CLAIM | Skill rows and context blocks derive from database/file facts, use stable identities, omit missing facts, and preserve database errors. | Requires portable `my.skills`, `seon.db`, and context owners; assertions cover schema registration, corpus scan, load/unload identity, derived rendering, and one database value. |
| `my.ui-test` | A — CLAIM | Pure render helpers preserve identical AI/hiccup meaning and remain total on bounded ordinary inputs. | Requires portable `my.ui`; assertions cover status, table, badges, bullets, progress, and section composition without runtime effects. |
| `seon.agent.ctx.canvas-test` | A — CLAIM | Canvas context is pure over acquired rows, database-pinned, bounded, explicit about history, and returns selected-render errors as data. | Requires portable `seon.agent.ctx.canvas`, `seon.db.protocol`, and `seon.render.canvas`; assertions inspect read profiles, selected function calls, bare hiccup, caps, and failure data. |
| `seon.agent.ctx.driver-test` | C — MIXED | Claim prompt and human-view derivation from one acquisition profile and one database value; discard the pod-only driver dispatch ceremony. | Requires deleted `seon.agent.ctx.driver` plus surviving database/message/render owners; assertions distinguish acquisition/error/welcome projections from async authored-surface dispatch. |
| `seon.agent.ctx.menu-test` | A — CLAIM | Namespace menu acquisition is bounded, paged, database-pinned, and the same structured value feeds display and provider context. | Requires portable `seon.agent.ctx.menu` and `seon.db.protocol`; assertions name the acquisition and bound directly. |
| `seon.agent.ctx.namespaces-test` | A — CLAIM | Namespace visibility is structural, bounded, selection-scoped, error-preserving, and closes schema references without exposing internal/test namespaces. | Requires portable namespace/context owners; 18 tests assert public/full policy, paging, source pins, generated overlays, caps, and error propagation. |
| `seon.agent.ctx.render-fns-test` | A — CLAIM | Render twins are derived from acquired function rows, receive a frozen database value and agent id, and return bounded errors/data. | Requires portable `seon.agent.ctx.render-fns`; assertions cover twin-key selection, explicit pins, frozen inputs, failures, and output clipping. |
| `seon.agent.ctx.subagents-test` | A — CLAIM | Subagent context is a bounded pure derivation of child/run facts with truthful overflow/orphan handling and one database value. | Requires portable `seon.agent.ctx.subagents` and database protocol; assertions cover state, latest closed run, caps, childless acquisition, and member failures. |
| `seon.agent.ctx.transcript-test` | A — CLAIM | Transcript windows are bounded over durable message/turn/eval facts, preserve the current run cause, and never depend on process-local result membership. | Requires portable transcript, database protocol, and JVM-capable HTML; 19 tests assert chunk rotation, bounded pages, usage truth, partials, and host telemetry. |
| `seon.agent.ctx.typeahead-steps-test` | B — DELETE | None. | Requires deleted `seon.agent.ctx.typeahead-steps`; assertions are the pod typeahead step surface. |
| `seon.agent.ctx.usage-test` | A — CLAIM | Provider usage rows normalize into truthful actual/estimated token projections without inventing zeroes. | Requires portable `seon.agent.ctx.usage`; assertions cover OpenAI/Anthropic cache fields, estimates, and invalid input. |
| `seon.agent.ctx.warnings-test` | A — CLAIM | Warning blocks derive from current scoped facts, preserve database errors, and use count-distinct semantics. | Requires portable warning/context owners and database protocol; all assertions are ordinary-data/query invariants. |
| `seon.agent.ctx-teaching-test` | C — MIXED | Claim platform-neutral JVM teaching and one shared renderer; delete pod-driver wording paths. | Requires portable context plus deleted pod driver/admin owners; assertions explicitly separate platform-neutral guidance from the old prompt-render path. |
| `seon.agent.debug-test` | C — MIXED | Claim turn reconstruction, basis-transaction diffs, and structured error triage from one database value on the JVM render owner. | Requires deleted pod `seon.agent.debug` but assertions are database-value/transaction/error-data invariants needed by future JVM forensics. |
| `seon.agent.fs-test` | C — MIXED | Claim allowlist enforcement, bounded reads/walks, exact/unique/sha-fenced edits, and flat failures in the JVM fs leaf; delete Node fs ceremony. | Requires deleted pod fs leaf/internal plus portable match/code owners; 35 tests assert policy and edit semantics rather than a reusable Node implementation. |
| `seon.agent.home-test` | A — CLAIM | Home/current namespace and requires derive deterministically from one database value, with errors preserved and schema-owned shapes. | Requires portable `seon.agent.home`; assertions cover deterministic identity, precedence, persisted requires, and error values. |
| `seon.agent.message-test` | A — CLAIM | Message classification and transaction builders are pure; send/recent use one database value, bounded reads, verbatim lookup refs, and idempotent allocated identities. | Requires portable message/internal, db id, and protocol owners; assertions inspect closed request shapes and exact transaction data. |
| `seon.agent.multiagent-test` | C — MIXED | Claim atomic child+first-task creation, namespace assignment/reuse, authorization, one-database-value reads, and idempotent birth in the JVM driver. | Requires deleted pod `seon.agent` plus surviving message/db/schema owners; assertions are durable transaction and race invariants, while pod launch calls die. |
| `seon.agent.schedule-test` | C — MIXED | Claim pure cron parsing/due/next-fire rules and one fenced fire transaction; discard pod timer/process ownership. | Requires deleted `seon.agent.schedule`; four assertions are pure temporal rules and one asserts database-pinned firing. |
| `seon.agent.search-test` | C — MIXED | Claim bounded grep/graph results, allowlist/default-deny policy, honest caps, and errors as values in the JVM capability family; delete Node search execution. | Requires deleted search/fs leaves; 29 tests distinguish pure result/policy invariants from platform execution. |
| `seon.agent.shell-test` | C — MIXED | Claim closed shell requests, cwd/path policy, byte/time caps, process-tree termination, and ordinary result data in the JVM shell leaf. | Requires deleted pod shell/internal owners; tests assert policy, stream bounds, exit/timeout data, and mutation gate rather than a portable Promise rail. |
| `seon.agent.testrun-test` | B — DELETE | None. | Requires deleted `seon.agent.testrun` and `seon.test.runner`; assertions orchestrate the retired pod agent-test execution path. |
| `seon.agent.web-search-test` | C — MIXED | Claim bounded normalized search results, safe URLs/citations, and flat provider failures at the JVM web capability boundary. | Requires portable web core plus deleted pod web execution; assertions exercise result/policy shape and Node fetch plumbing together. |
| `seon.agent.web-test` | C — MIXED | Claim URL/SSRF policy, resource caps, redirects, decoding, and flat request/response errors in the JVM web leaf. | Requires `seon.agent.web` and pod leaf; assertions mix portable policy with fetch/Promise effects. |
| `seon.agent-render-namespace-test` | A — CLAIM | Namespace AI rendering is pure over eager rows, closes schema references once, caps the frontier, and performs zero database I/O. | Requires portable `seon.agent.ctx`; both tests replace database calls with failures and assert exact schema-closure/cap behavior. |
| `seon.ai.anthropic-test` | C — MIXED | Claim request building, response/refusal interpretation, bounded provider evidence, cacheable stable context, and flat failures in the JVM Anthropic wire core/HTTP leaf. | Requires deleted SDK adapter plus portable AI/context data; assertions mix pure wire shapes with streaming SDK/abort mechanics. |
| `seon.ai.dispatch-test` | D — COVERED | `seon.ai.provider-test` and `seon.ai.http-test`. | The deleted local adapter registry selects providers/backends and stubs; retained tests claim descriptor-row validity, wire selection, and JVM HTTP dispatch. |
| `seon.ai.generate-code-test` | C — MIXED | Claim atomic plan/message/CAS transaction data and evidence-owned terminalization; delete in-eval observers, schedulers, and pod-side lifecycle calls. | Requires deleted `seon.ai.generate-code` plus surviving `my.plan`, message, db-id, and reactive owners; assertions visibly separate pure builders/transactions from Promise observers. |
| `seon.ai.openai-compat-test` | C — MIXED | Claim request/response interpretation, descriptor-selected wire keys, bounded/redacted evidence, usage, and flat failures in the JVM OpenAI-compatible core/HTTP leaf. | Requires deleted SDK adapter and AI orchestration; 18 tests mix pure wire contracts with stream/abort/SDK mechanics. |
| `seon.ai.typeahead-test` | B — DELETE | None. | Requires deleted `seon.ai.typeahead`; assertions are the opt-in pod typeahead subsystem. |
| `seon.ai-test` | C — MIXED | Claim frozen provider-attempt facts, bounded completion/usage data, retry disposition, and errors as values in the JVM driver; discard pod streaming orchestration. | Requires deleted `seon.ai` with provider adapters and context; assertions cover both durable request/attempt shapes and CLJS async flow. |
| `seon.client.provider-routing-test` | D — COVERED | `seon.ai.provider-test` and `seon.ai.http-test`. | The assertion routes a provider through deleted `seon.client`; retained descriptor and HTTP tests own the surviving selection boundary. |
| `seon.client-advertisement-test` | B — DELETE | None. | Requires deleted `seon.client`; assertions attach/detach the pod's runtime advertisement and follow its process-local database listener. |
| `seon.config-test` | D — COVERED | `seon.config-resolve-test`, `seon.config-protective-limits-test`, `seon.config.resolve-web-render-test`, and `seon.dev.config-test`. | Requires duplicated deleted `seon.config.cljs`; retained tests exercise the surviving `seon.config.resolve` and operator manifest owners. |
| `seon.ctx-test` | A — CLAIM | Context selection/composition is pure over acquired blocks and one database value, with restart-stable bytes, explicit cache splits, and error-preserving rows. | Requires portable `seon.agent.ctx`; 13 tests assert no local database injection, stable hashes, block overrides, and native transaction classification. |
| `seon.db.protocol-test` | D — COVERED | `seon.db.protocol-test` (`.clj`) and `seon.db.codec-totality-test`. | Requires portable protocol and tests wire/database-value shapes; the retained same namespace plus generative codec test owns them. |
| `seon.db.restore-test` | D — COVERED | `seon.db.restore-admin-test`, `seon.dev.restore-test`, and `seon.db.branch-test`. | Assertions cover restore planning/heads and pod calls; retained JVM namespaces own branch and restore transaction behavior. |
| `seon.db.transport-uds-test` | D — COVERED | `seon.db.transport-uds-test` (`.clj`). | Same surviving UDS protocol owner; the retained namespace has the JVM transport/conformance coverage. |
| `seon.db.writer-read-decline-test` | D — COVERED | `seon.db.remote-contract-test`, `seon.db.writer-interest-test`, and `seon.db.host-interest-test`. | The single CLJS test declines advanced events through the pod session; retained writer/host tests claim interest negotiation. |
| `seon.db-remote-contract-test` | D — COVERED | `seon.db.remote-contract-test` and `seon.db.protocol-test` (`.clj`). | CLJS assertions exercise the remote facade envelopes; retained JVM tests own the same protocol contract. |
| `seon.db-session-test` | D — COVERED | `seon.db.host-interest-test`, `seon.db.remote-contract-test`, and `seon.db.transport-uds-test` (`.clj`). | Requires deleted pod `seon.db.session`; assertions are negotiation, initialization coalescing, and listener ownership now held by JVM host/web-render sessions. |
| `seon.derive-test` | C — MIXED | Claim the pure state projection and one-database-value derivation in the JVM render/driver owner; delete async pod acquisition. | Requires deleted `seon.derive` plus `seon.db`; one test is pure dominance, two are Promise-backed database calls. |
| `seon.dev.runtime-id-test` | D — COVERED | `seon.dev.process-test`, `seon.dev.cluster-test`, and `seon.dev.mcp-test`. | Requires portable runtime-id helpers; operator tests already claim cluster/runtime discovery and ambiguity behavior. |
| `seon.diffusion.gemma-test` | B — DELETE | None. | Requires deleted optional DiffusionGemma pod client; assertions cover its submit/poll/cancel protocol. |
| `seon.diffusion.oracle-test` | B — DELETE | None. | Requires deleted diffusion oracle and database leaf; assertions are its control/renoise phases. |
| `seon.diffusion.retrieval-test` | B — DELETE | None. | Requires deleted diffusion retrieval; assertions are symbol-candidate injection for that subsystem. |
| `seon.diffusion.scaffold-test` | B — DELETE | None. | Requires deleted diffusion scaffold; assertions are its infill frame/wire contract. |
| `seon.diffusion-fence-test` | B — DELETE | None. | The source-scan assertion only fences the deleted diffusion tree from the pod main system. |
| `seon.embed-test` | D — COVERED | `seon.embed-writer-test`. | CLJS assertions cover error/hit envelopes and one database value; the retained JVM namespace owns the surviving Vertex/database path. |
| `seon.error-record-test` | C — MIXED | Claim deepest-cause flattening, frame cleanup, classification, bounded fault facts, deduplication, and errors-as-values; discard AsyncLocalStorage/Promise rejection hooks. | Requires portable error/instrument owners plus deleted config/pod bindings; 19 tests clearly mix pure fault data with CLJS fiber persistence. |
| `seon.index-core-test` | C — MIXED | Claim corpus namespace/function/schema rows, real arglists, computed privacy/provenance, generator policy, and complete boot schema in `seon.db.program`/artifact tests; delete client analyzer bootstrap. | Requires deleted `seon.client` plus surviving db/schema; 21 assertions inspect produced rows and installed attributes, not just CLJS mechanics. |
| `seon.instrument-async-test` | B — DELETE | None. | The sole assertion is CLJS `AsyncFunction`/Promise wrapper validation and refresh, a self-host/pod-specific mechanism. |
| `seon.instrument-inject-test` | C — MIXED | Claim computed dependency selection, exact optional configuration injection, idempotence, and fail-loud missing dependencies at the JVM registry/dispatcher; delete old pod wrapper injection. | Requires portable instrument plus deleted config/db leaves; assertions are dependency-table invariants expressed through the old wrapper. |
| `seon.instrument-resilience-test` | A — CLAIM | A contract whose referenced schemas cannot resolve is rejected as one complete candidate. | Requires portable `seon.schema`; the assertion is independent of CLJS execution ceremony. |
| `seon.internal-boundary-test` | A — CLAIM | Internal visibility and agent-management authorization are computed from source/data shape, never name allowlists or mutable state. | Requires portable namespace/authorization owners; assertions cover structural inclusion and a pure pulled-parent-tree rule. |
| `seon.internal-require-boundary-test` | B — DELETE | None. | Uses Node source scanning to police the current CLJS `.internal` layout; the cut deletes most of that layout and operator program-inventory tests own future artifact structure. |
| `seon.items-test` | B — DELETE | None. | Requires deleted `seon.items` and `seon.result`; assertions only register and conform the retired pod result envelope. |
| `seon.launch-test` | A — CLAIM | Launch descriptors are closed ordinary data with isolated cluster paths, exact artifact digests, complete branch heads, and generation-fenced restore startup. | Requires portable `seon.launch`, branch, protocol, and schema owners; 12 tests assert descriptor bytes and restore evidence. |
| `seon.log-test` | B — DELETE | None. | Requires deleted pod `seon.log`; assertions cover its Node file sink, rotation, and simulated pod restart. |
| `seon.packages-test` | A — CLAIM | Package requests, ecosystem routing, manifests, ledger facts, corpus provenance, collisions, reconciliation, and removal are pure closed data. | Requires portable `seon.packages`; nine tests assert schemas and transaction plans for the future disposable leaves. |
| `seon.platform-test` | B — DELETE | None. | Requires deleted pod `seon.platform`; the sole assertion reads the pod artifact-path environment override. |
| `seon.reactive-test` | A — CLAIM | Database-interest rendering delivers first, suppresses equal values, retains latest pending data, isolates consumers, and closes races without a second channel. | Requires portable `seon.reactive` and database values; seven tests assert equality suppression, latest-wins, repair, latency, and release. |
| `seon.render.block-test` | A — CLAIM | JVM rendering is total, bounded, schema-directed, late-dispatched, safe on custom failures, and emits inert authorized controls only at the admitted boundary. | Requires portable render/value/ui owners; 28 tests assert pure AI/hiccup/error projections, bounds, handler precedence, and failure cards. |
| `seon.render.canvas-test` | A — CLAIM | Canvas content precedence, welcome/error rendering, hiccup structural admission, compact caps, and tutorial forms are pure and serializable. | Requires portable render canvas/parser/schema/HTML; 21 assertions contain no pod server dependency. |
| `seon.render.chat-test` | A — CLAIM | Chat rendering is server-side, scheme-guarded, ordered, validated, and total for human/agent/peer/system messages. | Requires portable chat/canvas/HTML; nine tests assert pure hiccup/HTML projections. |
| `seon.render.handlers.eval-test` | A — CLAIM | Eval rendering exposes only authorized retained values, keeps stored errors as data, and never invents live controls. | Requires portable eval handler/render; assertions are pure policy and bounded-detail invariants. |
| `seon.render.handlers.test-test` | A — CLAIM | Test facts render through the one handler with truthful pass/fail/none state, bounded inline source, and valid AI/hiccup twins. | Requires portable handlers/render/schema; 11 tests assert ordinary rows and handler output. |
| `seon.render.system-test` | C — MIXED | Claim fleet/system rendering as a pure serializable projection in the JVM renderer; delete the `.cljs` implementation rather than porting it. | Requires deleted render-system owner but both assertions are pure over ordinary authority rows. |
| `seon.render.value-test` | A — CLAIM | One JVM value-admission/projection boundary is total and bounded over hostile/lazy/opaque data, preserves exact navigation, and emits byte-stable AI/hiccup projections. | Requires portable `seon.render.value`; 71 tests assert work/depth/string caps, lazy realization, printer safety, schema status, parity bytes, and closed drill results. |
| `seon.render.view-unit-test` | A — CLAIM | Render-unit identity is stable and type-sensitive. | Requires portable `seon.render.view-unit`; the assertion is a pure identity invariant. |
| `seon.render-test` | A — CLAIM | One render dispatcher preserves twins, visible errors, bounded generic fallback, custom identity, and the single strict core-fault policy. | Requires portable render/value/schema/HTML owners; eight tests assert total projection and failure semantics. |
| `seon.repl.autocomplete-test` | B — DELETE | None. | Requires deleted pod autocomplete; its sole assertion is an async database rating call. |
| `seon.repl.parse.repair-candidates-test` | A — CLAIM | Symbol repair candidate parsing, edit-distance tiers, deterministic ranking, ambiguity refusal, and budgets are pure. | Requires portable `seon.repl.parse.repair`; nine tests directly assert the candidate algorithm not covered by the retained repair namespace. |
| `seon.retry-test` | A — CLAIM | Retry schedules, caps, classification, server hints, exhaustion, and flat failures are deterministic apart from an injected delay/random source. | Requires portable `seon.retry`; 14 tests assert pure backoff plus effect-boundary outcomes used by JVM HTTP. |
| `seon.route-test` | C — MIXED | Claim routes as database-derived qualified-symbol data with correct schema facets; delete the current pod route table/bridge implementation. | Requires deleted `seon.route` plus db/message owners; three tests assert seed contract, handler-symbol data, and derived facets. |
| `seon.runtime.recovery-test` | D — COVERED | `seon.agent.driver-test`, `seon.eval.receipt-test`, `seon.db.writer-mutation-recovery-test`, and `seon.db.request-receipt-test`. | Requires deleted pod recovery; retained JVM tests own run custody, receipt resume, fenced recovery, and replay/idempotence. |
| `seon.runtime.state-test` | D — COVERED | `seon.db.writer-initialization-test`, `seon.db.registry-test`, `seon.config-resolve-test`, and `seon.dev.config-test`. | Requires deleted pod reconcile owner; retained initialization/config tests own exact desired facts, installed schema, convergence, and provenance. |
| `seon.schema-test` | C — MIXED | Claim stable schema activation/projection, bounded diagnostics, dependency closure, canonical entity rows, collection semantics, and fail-loud invalid registrations; delete pod/test-runner coupling. | Requires portable schema plus pod agent/test-render owners; 23 tests mix core schema invariants with CLJS registry and deleted entity fixtures. |
| `seon.subprocess-test` | B — DELETE | None. | Requires deleted Node `seon.subprocess`; assertions cover Bun child streams, signals, IPC, and resource usage. Disposable leaves need fresh process containment tests when built. |
| `seon.test.async-fixture-test` | B — DELETE | None. | Requires deleted pod test runner and CLJS fixture probes; assertion is Promise-aware fixture ceremony. |
| `seon.test.async-test` | B — DELETE | None. | Requires deleted CLJS test helper/runner; assertions are Promise settle/timeout rails. |
| `seon.test.fixture-support-test` | B — DELETE | None. | Requires deleted pod test runner and fixture probes; assertion is its once/each ordering. |
| `seon.test.runner-test` | B — DELETE | None. | Requires deleted `seon.test.runner`; assertions discover/run pod vars and persist its runner summaries. |
| `seon.test.runner-timeout-test` | B — DELETE | None. | Requires deleted runner timeout probes; assertions are never-settling Promise and overlapping pod-run behavior. |
| `seon.ui.agent-view-test` | A — CLAIM | The JVM web renderer builds one complete agent view from ordinary projection data. | Requires render surface and portable HTML plus the deleted thin CLJS view; the sole assertion is the future renderer's pure boundary. |
| `seon.ui.header-test` | A — CLAIM | Header rendering is pure over ordinary projection data. | Requires portable HTML plus deleted thin header; the sole assertion contains no pod effect. |
| `seon.warn-test` | A — CLAIM | Warnings are computed from corpus/schema/runtime facts, isolate failed checks, use exact provenance, and disappear when clean. | Requires portable `seon.warn`; nine tests assert pure classifications and rendering. |
| `seon.web.brand-test` | C — MIXED | Claim effective brand and synchronization transaction as derived data in the JVM renderer; delete Node observation. | Requires deleted web brand; both assertions are pure row/transaction projections. |
| `seon.web.datastar-test` | C — MIXED | Claim JVM SSE gzip framing, visible database/render errors, one database-pinned render, shared equality suppression, latest-wins backpressure, and bounded measurements; discard JS Promise settling and pod child dispatch. | Requires deleted Datastar server plus surviving reactive/HTML/database owners; 17 tests explicitly mix those classes. |
| `seon.web.reactive.call-test` | C — MIXED | Claim capability authorization, closed decoded arguments, unavailable/error responses, one database value, and terminal acknowledgements at the JVM web-to-cluster boundary; delete direct pod submission. | Requires deleted web call/runtime admission plus interaction/db owners; nine tests assert both durable policy and pod call plumbing. |
| `seon.web.reactive.transform-test` | A — CLAIM | Datastar action rewriting/argument codecs are deterministic, quote-safe, namespace-preserving, and accept data only. | Requires deleted `.cljs` transform owner, but all 12 assertions are pure and needed by the JVM hiccup renderer. |
| `seon.web.router-test` | C — MIXED | Claim loopback-only operator controls, database-derived routes, acknowledged database values, and stale/detached query suppression in the JVM server; delete pod async router ownership. | Requires deleted router/admission plus db/route/message owners; ten tests separate route/security facts from Promise completion ordering. |
| `seon.web.serve-test` | C — MIXED | Claim closed value paths, authorization before acquisition, bounded database/forensic views, commit-driven run settlement, same-origin/loopback policy, readiness, and restore-head fencing in JVM web/driver tests; discard Bun request APIs and pod result registry. | Requires deleted serve/router/debug/system owners plus surviving db/render/error owners; 35 tests span both target invariants and dying interop. |

### Runner-visible `.cljc` namespaces

| namespace | verdict | surviving invariant or covering citation | evidence |
|---|---|---|---|
| `my.blob-test` | C — MIXED | Claim content-addressed idempotence, atomic publication/repair, bounded text, retained-set materialization, and digest fencing in JVM core/host-leaf tests; delete Node fs/Promise fixtures. | Despite `.cljc`, its namespace unconditionally requires `node:crypto`, `node:fs`, `node:path`, and `cljs.test`; `bin/test-writer` excludes it. `my.blob.host-leaf-test` covers only a thin leaf class. |
| `seon.agent.fs.match-test` | D — COVERED | Same namespace under `bin/test-writer`. | Reader-conditional test require; 23 pure exact/near/all/normalization/line-numbering assertions run with `:clj`. |
| `seon.agent.fs-portable-test` | D — COVERED | Same namespace under `bin/test-writer`. | Reader-conditional test require; portable request/response policy and public entry effects already run on the JVM. |
| `seon.agent.lifecycle-test` | D — COVERED | Same namespace under `bin/test-writer`. | Pure lifecycle dispositions and registered schemas run with `:clj`. |
| `seon.agent.message.portable-test` | D — COVERED | Same namespace under `bin/test-writer`. | The portable message/leaf contract already runs with the JVM reader feature. |
| `seon.agent.shell-portable-test` | D — COVERED | Same namespace under `bin/test-writer`. | Portable shell request builders/interpreters and public effects run with `:clj`. |
| `seon.agent.web-portable-test` | D — COVERED | Same namespace under `bin/test-writer`. | Reader-conditionals isolate environment/async ceremony; policy, caps, shapes, and flat errors run on the JVM. |
| `seon.ai.portable-test` | D — COVERED | Same namespace under `bin/test-writer`. | OpenAI/Anthropic wire data and shared failure vocabulary already run with `:clj`. |
| `seon.ai.provider-test` | D — COVERED | Same namespace under `bin/test-writer`. | Provider descriptor rows and fixed wire-core construction are ordinary portable data. |
| `seon.config-protective-limits-test` | D — COVERED | Same namespace under `bin/test-writer`. | Defaults, overrides, units, and governing config keys already run with `:clj`. |
| `seon.config-resolve-test` | D — COVERED | Same namespace under `bin/test-writer`. | Root context bytes and per-cluster writer resolution already run with `:clj`. |
| `seon.db.branch-test` | D — COVERED | Same namespace under `bin/test-writer`. | Branch-head closure, connection ID, lineage, and temporal cuts run with `:clj`. |
| `seon.db.codec-totality-test` | D — COVERED | Same namespace under `bin/test-writer`. | Generative registered-wire totality and vendored Datahike grammar checks run on the JVM. |
| `seon.db.id-test` | D — COVERED | Same namespace under `bin/test-writer`. | Its JVM branch already claims serialization, writer authority, collisions, reconnect, concurrency, and transaction reports. |
| `seon.db.portable-test` | D — COVERED | Same namespace under `bin/test-writer`. | Portable registration, read dependencies, and database contracts run with `:clj`. |
| `seon.error-classification-test` | D — COVERED | Same namespace under `bin/test-writer`. | Source-provenance/artifact-export classification is pure and retained. |
| `seon.eval.receipt-test` | D — COVERED | Same namespace under `bin/test-writer`. | Receipt schemas, frozen form position, and terminal-only resume run with `:clj`. |
| `seon.ns.source-test` | D — COVERED | Same namespace under `bin/test-writer`. | Structural require edges, documentation, failure values, and platform-branch selection run with `:clj`. |
| `seon.program-edge-test` | D — COVERED | Same namespace under `bin/test-writer`. | Direct edges, graph digest, and unresolved-symbol uncertainty run on the JVM. |
| `seon.render-portability-test` | D — COVERED | Same namespace under `bin/test-writer`. | Core block/eval-seam/context byte invariants are already retained; `seon.render-portability-writer-test` adds cross-artifact evidence. |
| `seon.repl.parse.repair-test` | D — COVERED | Same namespace under `bin/test-writer`. | Delimiter repair, byte preservation, idempotence, notes, and real forms run with `:clj`. |
| `seon.repl.parse-test` | D — COVERED | Same namespace under `bin/test-writer`. | All 47 parser, span, prose, error, heredoc, program, and property tests run with `:clj`. |
| `seon.runtime.lifecycle-test` | D — COVERED | Same namespace under `bin/test-writer`. | The closed portable quiesce response contract already runs on the JVM. |
| `seon.ui.html-test` | D — COVERED | Same namespace under `bin/test-writer`. | All 46 escaping, attributes, styles, raw, void, nesting, and realistic-card tests run on the JVM. |

## Summary by verdict

| verdict | `.cljs` namespaces | `.cljc` namespaces | total namespaces |
|---|---:|---:|---:|
| A — CLAIM | 34 | 0 | 34 |
| B — DELETE | 23 | 0 | 23 |
| C — MIXED | 29 | 1 | 30 |
| D — COVERED | 13 | 23 | 36 |
| **total** | **99** | **24** | **123** |

The actionable gap is therefore **64 namespaces** at namespace granularity:
34 wholly surviving classes plus the surviving portions of 30 mixed
namespaces. This is a planning count, not a request for 64 replacement
namespaces. The classes below should collapse into a much smaller number of
tests at the surviving choke points.

## JVM invariants to claim before the cut, ordered by risk

1. **Run custody, fold, receipts, and exactly-once durable effects.** One
   database value enters a step; its transaction report feeds the next step;
   `(run, ordinal, epoch)` fences terminal receipts, replies, messages, plan
   transitions, and recovery. Re-execution cannot duplicate a message or
   terminal effect. This absorbs the surviving portions of `my.plan-test`,
   message/multiagent/generate-code tests, and the old recovery suite.
2. **One database boundary with total values.** Database values, query/pull
   results, transaction reports, branch heads, IDs, restore evidence, and wire
   values are ordinary, closed, bounded data. One codec/admission operation
   realizes and rejects hostile/lazy/opaque output before the interrupt is
   disarmed. Existing protocol/codec/id tests cover much of this; the missing
   render-value totality class is the high-risk addition.
3. **Schema and corpus round trip.** Registrations fail loudly and
   deterministically; projection/dependency closure is bounded; complete
   `:seon.fn`/`:seon.ns`/`:seon.schema` facts are committed and acquired at one
   basis; computed binding/instrumentation tables reject unresolved contracts.
   This replaces client indexing and pod instrumentation tests with one JVM
   program-graph proof.
4. **Capability families through one dispatcher.** JVM fs, shell, web, blob,
   messaging, and hosted-LLM leaves enforce closed requests, allowlists/SSRF,
   byte/time/resource caps, idempotent operation IDs, and flat failures. Reuse
   portable core tests already retained; add leaf tests for the policy classes
   that only the large pod suites asserted.
5. **Pure bounded context and plan derivation.** Prompt, namespace, skills,
   warnings, subagents, transcript, usage, home namespace, and plan views are
   functions of one database value; paging/caps are truthful; errors remain
   visible; no render performs hidden database I/O.
6. **JVM render admission and reactive delivery.** Render twins, value
   projection, schema handlers, chat/canvas/system views, and action codecs are
   total serializable data. One cluster-side evaluation feeds any number of
   tabs; database interest suppresses equality; each connection is
   latest-wins; a failed render is visible and cannot wedge the server.
7. **Web security and readiness.** The JVM server authorizes actions and value
   paths before acquisition, keeps operator routes loopback-only, rejects stale
   query completions, binds readiness to observable database/process events,
   and fences restore state by the exact branch head.
8. **Portable package and utility policy.** Package ledger/manifests,
   ecosystem collision rules, retry classification, symbol repair ranking,
   warning checks, launch descriptors, and pure UI helpers retain their current
   data contracts. These are lower risk but otherwise disappear because their
   only full tests are `.cljs`.

The final cut gate is not a matching test count. It is a green
`bin/test-writer` in which these invariant classes are visible at their
surviving owners, followed by the step-6 reset-boundary proof with no pod
process and no fourth runner.
