---
type: research
lane: laws-without-proofs
date: 2026-10-10
status: research only; no src or test edits
---

# Laws without proofs

Research lane `laws-without-proofs`, 2026-10-10, against HEAD `4027882d4`. Default runs archive `e25afdf6e`.
Method: each checkable law in AGENTS.md was matched to tests by `rg` over `test/`, `script/seon`, `:seon/tasks` tags
and `.clj-kondo/config.edn`. A test status here comes from reading test names and bodies, not from running them. A
probe is a form sent through MCP `eval_clj` as agent `laws-audit` (SCI, or JVM read-only); none transacted on main.

Scope covered: "The system", "Data and contracts", "Failure and execution", "Presentation", "Proof and performance",
and the mechanical parts of "How to think", "How to work" and "Operating". Skipped as guidance or lane process:
"The goal", "How we design", "Words" (a rename census would be a separate lane), "Plan before implementing", "Launch
lanes", "Restart in batches", "Clean up what you own". The git-index law holds now (`git diff --cached` is empty) and
`seon.lane-test` covers claim and release.

## Counts

About 58 checkable laws were extracted. Violated: 9. Unproven (no test, no census): 12. Partly proven: 15. Proven: 22.
Of the 9 violated, one already has a red tagged proof (node cache) and one holds at HEAD but not on the running JVM.

## Violated laws, ranked by risk

| # | Law (quote) | Section | Status | Evidence |
|---|---|---|---|---|
| 1 | "never `alter-var-root`, `with-redefs`, `load-file` or `intern` over any Var" | Proof: Run tests as agents run | **Violated, unproven** | `rg -o with-redefs test script` = 163 hits in 60 files (`cluster/turn_test.clj` 45, `fn_test.clj` 7, `db_test.clj` 6); `alter-var-root` 26 in tests, `intern` about 12 (`instrument_test.clj`). No lint (`.clj-kondo/config.edn` has no `with-redefs` entry) and no census test. The testing skill §2.7 states the same rule. |
| 2 | "Mutable state is at most one validated state map per process and one per cluster, each an atom whose `set-validator!`..."; "a lint checks this (ledger §3.19)" | How to think: Prefer values to places | **Violated, unproven** | `rg -o '\(atom[ )]' src` = 61 sites in 17 files (`sci/eval.clj` 18, `render/web.clj` 8, `flow.clj` 8). `rg set-validator! src` = 0 hits. No `atom` entry in `.clj-kondo/config.edn`. Ledger rows AT-1..AT-7 (3.19) plan the fix and are not landed; AT-7 is the lint. |
| 3 | "There is exactly ONE printer... no `pr-str` into a message, log or page"; "an error message is short fixed text with its values as data keys" | Presentation | **Violated, partly proven** | Lint `:discouraged-var` lists the printers and hook `hooks/is_message.clj` bans printing in `is` messages (error level). `discouraged-var` itself has no `:level`, so it warns. Live count: 128 `(pr-str`/`(prn-str` calls in `src` outside `seon.render.value` and `seon.print`, e.g. `fs.clj:115-152` builds error messages with `(pr-str root)`, `config.clj:603` builds an identity string. Some are canonical EDN (`fs.clj:25` spit); nobody has classified them. |
| 4 | "`merge!` commits only when every test on the merge branch is green (the proofs, the tests reaching the change...)"; "a test already red on main stops it for root" | The system: Merge is a root task | **Violated (spec and code disagree), partly proven** | `seon.cluster.source/accept-merge!` refuses unless every `:seon.task/proofs` member is green and each replaced function reaches a green member; its own docstring says the other reaching members' verdicts "are shown by prepare, not gated". They land only as `:seon.source/red` (source.clj, `prepare-merge!`). `merge_test/a-candidate-merges-only-its-tested-green-program` covers a red proof only. No test for a red reaching test or red-on-main. |
| 5 | "No dates, measurements, history or `file:line` in source" | Data: Documentation is data | **Violated, unproven** | `rg` over `src`: 214 lines with an ISO date, 57 `file.clj:NNN` citations (`error/refusal.clj:121,231,258,394`, `effect.clj:448`, `fn/analyzer.clj:148,309,686`). `seon.dev.docstring-test` checks only first-line form. |
| 6 | "A hardcoded policy number in code is a defect"; "Every size, count, byte budget, heap cap, timeout and threshold is a dial" | The system: Boot and configuration | **Violated, unproven** | `cluster/process.clj:111,113`: `(def ^:private subprocess-cleanup-ms 10000)`, `subprocess-grace-ms 1000`; `health.clj:583,588`: `(or deadline-ms 900)`. `config_application_test/every-config-entry-has-an-honest-application-contract` proves dials are applied, not that no policy number sits outside a dial. |
| 7 | "Datahike's node cache byte-bounded (replacing its soft-referenced retention)" | The system: Boot and configuration | **Violated; red proof exists** | Task `seon.cluster.store/the-node-cache-counts-nodes-not-bytes` is tagged on `test/seon/node_cache_test.clj`. `store_test/the-node-cache-size-is-the-configured-dial` proves the dial, not the unit. |
| 8 | "Evidence never points into gitignored `tmp/`" | Data: Write tasks as data | **Violated, unproven** | `resources/seon/tasks/seon.turn.edn:78`: `:evidence #{"tmp/waste-rank/large-values.edn" ...}`. Many `:intent` strings also cite `tmp/` (`seon.cluster.store.edn`, `my.program.edn:30`). `task_ingestion_test` checks malformed files and dangling tags, not evidence paths. |
| 9 | "Every JVM runs a committed archive... never the working tree" and the sealed archive and pins | Operating: Operate through bin/seon | **Holds at HEAD, violated on default until restart** | Probe (jvm, read-only): `user.dir` = `data/source/e25afdf6e...`; `src/seon/id.clj` is `r--r--r--` (sealed by `9cd874d94`); but `reference-code/datahike/src/datahike/api.cljc` `.canWrite` = true (pin seal `4027882d4` is newer than the running archive). Tests: `operator_outcome_test/a-start-runs-the-committed-archive-never-the-checkout`, `operator_test/#'seon.operator/an-extracted-archive-is-read-only`. No test or health unit checks the running JVM. |

## Unproven or partly proven, ranked

| # | Law (quote) | Section | Status | Evidence or probe |
|---|---|---|---|---|
| 10 | "Direct `datahike.api` calls belong only in `seon.db`, store/registry, classified branch-custody owners and system listeners" | Data: Use the database owner | Unproven | 14 `src` files require `datahike.api` (`eval/drive.clj`, `schedule.clj`, `turn.clj`, `call_preparation.clj`, `maintenance.clj`, `cluster/wake.clj`, `cluster.clj` 24 call sites...). `rg datahike.api test` hits 58 files (tests may). No allowlist test. "classified" owners are not listed anywhere executable. |
| 11 | "Production regex needs owner permission" | Data: Declare facts | Unproven | 10 `src` files use regex (`ref.clj` 5, `render/web.clj` 4, `print.cljc` 3, `test/platform.clj` 2). No allowlist census. |
| 12 | "A form is parsed once and evaluated once" | Perspective: one REPL path | Partly proven | Evaluated once: `sci/eval_test/evaluate-invokes-eval-form-exactly-once-on-every-path`. Parsed once: `run_test/a-forms-position-costs-the-text-once` is about cost, not a parse counter. |
| 13 | "Everything uses it: product agents, outside agents, tests, render forms, schema registration, gates and merge" (one path) | Perspective | Partly proven | Entrants individually tested (`mcp_entrance_test`, `test_support`, `turn_test` render). No census that no second reader or evaluator exists: `rg 'eval-string|sci/eval-form' src` finds none, `edn/read-string` is used for stored forms in 20+ places (`fn.clj:3202-3453`, `turn.clj:1040`), which is data, but stored contracts are strings re-read by every consumer, against "symbols stay Datahike symbols, not strings converted at each reader". |
| 14 | "An eval request... writes its recording transaction as ONE synchronous transaction, durable before it answers, under 100 ms" | Failure: Recover facts | Partly proven | One transaction: `request_cost_test/a-request-counts-its-own-calls-reads-and-transactions`, `a-repeated-form-writes-...-no-extra-transactions`; durable before answering: `db_read_your_writes_test/a-write-is-on-the-connection-when-it-answers`. The 100 ms bound has no assertion. Probe: 6 consecutive replies showed `:commit :cpu-ms` 11 to 25 and stage `:settle` 28 to 82 ms. |
| 15 | "Contract every admitted function, private ones included" | Data: Contract | Partly proven | `public_contract_test/every-fresh-public-function-has-a-complete-contract` covers fresh public functions; `contract_coverage_test` and `contracts_compile_test/a-new-or-changed-function-needs-a-complete-contract` cover new or changed ones. Existing uncontracted privates are tasks (`seon.contracts/task`), by design. Merge refusal: `source/accept-merge!` has an `uncontracted` check, no test named for it. |
| 16 | "A render... is READ-ONLY: it gets a database value, and no connection or writer" | Presentation | Unproven | `rg` over test finds `render_coverage_test/render-without-a-carried-profile...` and `renderer-invocation-is-sci-only...` but no test that a render cannot write or reach a connection. |
| 17 | "A render is cached by the value's content digest, so nothing renders twice" | Presentation | Partly proven | `render_cache_test` (branch root), `render_simplification_test/shared-invocation-cache-reuses-identical-entity-inputs-across-call-ids`, `web_context_test/a-new-message-does-not-reinvoke-the-identity-pair`. No count assertion over one repeated value. |
| 18 | "Absence is no key, never stored nil" | Data: Preserve native values | Partly proven; holds | Probe: `(seon.db/transact! {:tx-data [{:db/id -1 :seon.agent/id "laws-audit-nil-probe" :seon.agent/namespace nil}]})` returned `:seon.db.write/validation-refusal` ("expected an integer or a string or a tuple... got nil" at `[0 :seon.agent/namespace]`). Tests only for hiccup and `eval/drive_test`. |
| 19 | "Stored-text attributes carry the registered bounded schema, so the checked writer refuses an oversized value" | Data: Three places | Partly proven | `error_result_test/shown-text-over-threshold-is-staged-with-the-fact`, `error_test/a-fifty-thousand-entry-offending-value-stores-bounded-text`; no test that the writer refuses an oversized stored-text attribute. |
| 20 | "Printed text is asserted only by the value printer's own tests and a few declared golden transcripts" | Proof: Test the real system | Unproven | Only the `is`-message hook exists. No scan of `str/includes?` or `re-find` over printed results in tests. |
| 21 | "Tests fail beyond their declared bound, default five seconds" | Proof: Make bounds honest | Proven | `test/duration_test/overrun-is-captured-and-reported-as-an-assertion-failure`, `duration-bound-requires-both-the-allowance-and-its-reason`. |
| 22 | "Only root's merge advances main"; "the agent's branch is deleted when its merge request closes" | The system: Merge | Partly proven | `my.program/accept-merge!` lands on "the branch my context holds" and retires the branches as io work (`program.clj`). `host_test/a-host-retires-after-it-has-run` and `retirement-closes-admission...` cover retirement, not "closes with the merge". No test that a non-root accept cannot advance main (custody is cooperative by law, so likely by convention). |
| 23 | "Storage I/O on virtual threads"; "4,096 bytes inline", "1 KB blob compression floor", "512 MB seon.cache", "4 GB heap cap" | The system: Boot | Partly proven | Heap cap: `operator_test/a-launched-jvm-carries-the-configured-heap-cap` and probe `:heap-max 4294967296`. Cache: probe `:max-bytes 512000000`. Inline threshold: `blob_threshold_test/text-at-threshold-is-inline-over-is-staged`. Compression floor: config attributes exist (`blob_test.clj` fixture) but no assertion of 1024. Virtual threads: no test. |
| 24 | "Source comments use `;` for prose, `;;` above forms, `;;;` for runtime structure" | Data: Use the agreed words | Unproven | No lint. |
| 25 | "Every restriction names the failure it prevents" | How to think | Unproven | Not mechanical; the `.clj-kondo` bans do name theirs. Skip. |
| 26 | "Boot opens the REPL before store acquisition, then builds process, store, facts, flow, publishing readiness at each layer" | The system: Boot | Partly proven | `boot_timeline_test/each-published-boot-layer-carries-its-cumulative-milliseconds`; order itself is not asserted. |
| 27 | "Health is a positive observation: silence, a missing subject or a stale observation reads as unknown" | How to think | Partly proven | `health_test`, `fault_test`; `runtime_status` is `:faulted` now with 3 open faults (`seon.flow/var-process` x2, `seon.cluster.source/held-digests`) and 17 errored receipts: they are surfaced, so the law holds. |
| 28 | "A recorded error without its task is a finding" | How to work: Probe | Partly proven | Errors are grouped by signature (`problems_test/errors-are-grouped-by-signature...`); the task link is Target (errors cut). |
| 29 | "Agents' own values are never clipped" with "ordinary function's source prints whole; only a real monster is cut" | Presentation | Partly proven | `printer_elision_test`, `value_test/ordinary-values-print-whole-at-the-agent-budget`; `my.program/program_test/source-shows-a-function-whole` (fixed `e25afdf6e`, 250 lines). Probe: `(my.program/source 'seon.sci.eval/evaluate)` (369 lines, 6,706 tokens) returns its `:source` whole as a value, and printing `(:source ...)` through the printer cuts at 1,985 tokens with `(subs result/e69f... 6309)`. The monster threshold is not stated or tested. |
| 30 | "state the size of anything an agent sees in estimated tokens" | Presentation | Proven | `value_test/an-agent-facing-cut-reports-its-size-in-estimated-tokens`; probe: `:seon.eval/value-tokens 6706`, `:shown-tokens 1985`. |
| 31 | "A selected render must be one whose contract fits the value" | Presentation | Unproven, unconfirmed | Probe: a map carrying only `:seon.error/type :seon.db.write/validation-refusal` plus `:seon.error/message` selected `seon.db/render-rejection-ai`, whose input contract refused it; the reply carried a 90-line diagnostic. The map was my own `select-keys` artifact, so this may be correct fallback behavior; kept as a low-priority question for the render owner. |

## Proven laws (test names, from reading)

| Law | Test |
|---|---|
| "Every JVM runs a committed archive", archive and pins read-only | `operator_outcome_test/a-start-runs-the-committed-archive-never-the-checkout`; `operator_test/...an-extracted-archive-is-read-only` (platform) |
| Bound admission: bare blocking forms only in `seon.await` | `await_owner_census_test`, `bounded_boundary_census_test/every-foreign-subprocess-boundary-declares-a-bound`, lint `discouraged-var` |
| "Timeout... is not termination: retain ownership" | `test/one_request_test/a-body-outliving-its-bound-is-released-only-after-it-exits`, `host_test/an-unsettled-host-run-keeps-ownership-until-it-exits`, `sci/eval_test/a-blocking-host-call-is-NOT-stopped-by-the-time-limit` |
| One writer, read-your-writes | `db_read_your_writes_test`, `db_test/a-merge-shares-the-write-fence...` |
| Inline-or-stage decided once | `blob_threshold_test`, `error_storage_bytes_test/one-recorded-error-stores-its-shown-text-and-no-blob` |
| Compiled code cannot be redefined by an agent | `sci/eval_test/compiled-runtime-roots-cannot-be-redefined-by-agent-code`, `...metadata-cannot-be-changed-by-agent-code` |
| Branch never changes the platform / sibling forks invisible | `sci/fork_isolation_test/sibling-forks-of-one-base-are-mutually-invisible`, `sci/eval_test/a-branch-change-leaves-its-compiled-callers-compiled` |
| The path is total (a failing stage keeps the value) | `run_test/item-1-a-stage-throw-is-a-diagnostic-beside-each-value`, `repl_test/a-response-carries-errors-as-data-and-late-diagnostics-beside-the-value` |
| Nothing crashes at entrants: web, MCP, schedule, boot, Flow, runner | `render/web_test/a-request-that-throws-answers-its-error-value-never-a-stack-trace`, `mcp_entrance_test/a-thrown-call-carries-its-whole-request`, `schedule_test/returned-and-thrown-handler-errors-record-one-fault-for-root`, `cluster/uncaught_handler_test`, `flow_test/a-proc-throw-during-work-is-one-occurrence-never-dropped`, `branch_schema_check_test/a-request-that-throws-answers-an-unknown-error-with-its-cause` |
| Errors recorded once per signature, repeats count | `error_test/the-signature-ignores-the-message`, `recurrence-counting-does-not-require-a-notification-threshold`, `the-storm-is-bounded-by-the-signature-count` |
| Errors hold references, not copies | `error/refusal_test/error-data-of-any-shape-is-referenced-without-the-stack-or-its-leaves`, `a-place-in-an-error-is-named-never-printed-through` |
| Render failure falls back with one diagnostic | `render/value_test/a-failing-renderer-never-replaces-the-value`, `a-renderer-that-throws-still-prints-through-the-floor-and-says-large-value` |
| Elision forms are plain clojure.core | `printer_elision_test/elision-forms-name-only-clojure-core`, `value_test/elision-forms-execute-in-the-real-sci-context` |
| Supply defaults by schema name; caller arguments win; arity match never prefixed | `call_preparation_test/a-call-matching-a-declared-arity-is-never-prefixed`, `an-optional-world-member-is-supplied-and-a-required-one-refuses`, `two-clusters-supply-their-own-custody` |
| Admitted contracts are armed, invalid input refuses, invalid output refuses | `instrument_test/*`, `repl_differential_p4_test/a-valid-contract-refuses-a-violating-result`, `adoption_contract_freshness_test` |
| Missing contract is warned at the REPL, never refused | `uncontracted_warning_test`, `cluster/agent_test/a-function-without-a-contract-is-admitted-and-stored` |
| Task assignment needs non-green proofs | `task_ingestion_test/assignment-refuses-a-task-without-proofs`, `assignment-requires-a-non-green-proof` |
| Metadata after the test name refused | `task_ingestion_test/annotation-placement-is-positional-and-body-usage-stays-legal` |
| Test with no assertion fails | `test_runner_test` (`seon.test.runner/assertionless-failure`) |
| Documentation edits never widen a gate | `test_cache_test/a-documentation-edit-never-widens-a-gate`, `test/selection_test/documentation-only-input-changes-leave-the-input-evidence-unchanged` |
| Every cache is a kind in `seon.cache`, bounded, evicts | `cache_test/the-byte-budget-evicts-and-status-reports-it`, `cache_calibration_test/every-kind-is-charged-what-it-was-measured-to-retain` |
| One lock holder per store; lock file | `cluster/store_test/one-holder-per-store-in-one-process`, `cluster/boot_test` (real second OS process) |
| Branch deletion by retention, not cleanup | `cluster/registry_test/retiring-one-cluster-reclaims-only-its-own-tail`, `boot-retires-only-branches-no-fact-claims` |
| Nothing left staged / claims | `lane_test`; probe: `git diff --cached --name-only` is empty |
| Never silently truncate pulls | `db_test/pull-budget-refusal-is-an-explicit-named-error` |

## Probes run (all read-only, agent `laws-audit`)

1. `runtime_status`: cluster `default` `:faulted`; 11 untested hot warnings, 17 errored receipts, 3 open faults, heap-max 4,294,967,296, cache max 512,000,000, ready in 9,622 ms.
2. SCI `(seon.context/current-state)`: reply `:ms` 12, `:alloc-mb` 20.6, `:cost :transactions 1`.
3. JVM `(datahike.api/branches (seon.cluster.boot/connection "default"))`: 10 branches, none stale beyond 6 agent branches and a checkpoint.
4. JVM file-permission form (law 9): `src/seon/id.clj` not writable, pinned `reference-code/datahike/.../api.cljc` writable, cwd the `e25afdf6e` archive.
5. SCI `(my.program/source 'seon.sci.eval/evaluate)` (law 29).
6. SCI nil transact (law 18).
7. SCI `(seon.problems/open-faults (seon.db/db))`: operations `seon.flow/var-process` x2, `seon.cluster.source/held-digests`.

## Top 10 proposed red proofs

Each is a deftest for root to assign; none exists. "Non-vacuous" means the first assertion shows the scan saw work.

1. **`seon.test-census-test/no-test-redefines-a-var`** (new ns `test/seon/test_census_test.clj`; law 1). Use clj-kondo analysis through `seon.fn.analyzer` var-usages over `test/` and `script/`; assert `(= #{} (set (map (juxt :ns :var) hits)))` for `clojure.core/with-redefs`, `alter-var-root`, `intern`, `load-file`, after asserting `(> (count scanned-files) 100)`. Red today with about 60 files.
2. **`seon.cluster.state-test/an-invalid-write-is-refused-and-the-state-is-unchanged`** (AT-1's own name; law 2) plus **`seon.state-census-test/every-atom-in-src-is-a-declared-cell`**: var-usages of `clojure.core/atom` and `volatile!` in `src/`, minus an allowlist data map `{ns-var reason}` for the two state maps and the counters; assert `(= #{} unlisted)` and that each state-map atom has a non-nil `(.getValidator atom)`. Red: 61 sites, 0 validators.
3. **`seon.print-census-test/no-value-is-printed-outside-the-value-printer`** (law 3). Var-usages of `pr-str`, `prn-str`, `print-str`, `println-str`, `pprint` outside `seon.render.value`, `seon.print`, `seon.id`; assert `(= {} unclassified)` where a `{site reason}` allowlist data map holds canonical-EDN sites. Red: 128 sites.
4. **`seon.cluster.merge-test/a-red-reaching-test-refuses-acceptance`** (law 4). Fixture: a candidate whose task proofs are green and one other reaching test is red; assert `(= :seon.source/merge (:seon.error/layer (source/accept-merge! target store proposal)))` and `(= basis-before (db/basis-t (db/db target-conn)))`. A twin row: a test red on the target before the candidate refuses with its symbol in `:seon.error/offending`.
5. **`seon.dev.docstring-test/source-text-carries-no-date-or-file-line`** (law 5). Scan `src/` docstrings and `;` comments for ISO dates and `<name>.clj:<n>` citations; assert `(= [] findings)`. Red: 214 dates, 57 citations. This is a regex over source text for a test, not production code.
6. **`seon.config-application-test/no-timeout-or-limit-is-a-hardcoded-number`** (law 6). Analysis rows: private `def`s whose value is a numeric literal and whose name ends in `-ms`, `-bytes`, `-limit`, `-seconds`, or a `(or x <literal>)` default on a `deadline`/`limit` argument; assert empty against an allowlist of non-policy constants. Red: `cluster/process.clj` `subprocess-cleanup-ms`, `subprocess-grace-ms`, `health.clj` 900.
7. **`seon.task-ingestion-test/a-task-evidence-path-names-no-gitignored-file`** (law 8). For each `resources/seon/tasks/*.edn` entry, assert no `:seon.task/evidence` path has first segment `tmp`; check with `git check-ignore` data for any other path. Red: `seon.turn.edn:78`. Keep `:intent` prose out of scope.
8. **`seon.node-cache-test/...`** (law 7): already red and tagged; ask root to confirm it is on the schedule.
9. **`seon.cluster.status-test/the-running-archive-and-its-pins-are-read-only`** (law 9). Using `java.nio` on `user.dir` and `reference-code/*`, assert no regular file under `src/` and the pins holds a write bit; also surface it as a health unit so `runtime_status` reports it positively. Red on default until `start --head`.
10. **`seon.db-census-test/datahike-api-calls-stay-in-their-declared-owners`** (law 10). Var-usages of `datahike.api/*` in `src/` grouped by namespace; assert `(= allowed-namespaces actual-namespaces)` where the allowlist is data naming each owner and its class (writer, store, registry, branch custody, listener). Red or green decides whether `eval/drive`, `schedule`, `turn`, `call_preparation` and `maintenance` are classified owners. A sibling `seon.regex-census-test/production-regex-sites-are-owner-approved` handles law 11 the same way.

Also worth a proof but ranked below ten: `seon.request-cost-test/a-recording-transaction-commits-under-its-bound` (law 14: assert the reply's `:seon.cost/commit` time under the 100 ms dial on a 30-datom eval), and `render read-only` (law 16: a render given a connection-bearing world must see only a database value).

## Out-of-scope findings

None recorded as tasks by this lane (the brief forbids writing code or tests). Observations for root: default is `:faulted` with three open faults named in probe 7; the issue index is the owner's, so this lane did not edit it.
