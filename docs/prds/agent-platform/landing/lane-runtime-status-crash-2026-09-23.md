# Lane runtime-status-crash (must-fix M7), 2026-09-23

Source: `docs/research/agent-platform/flow-fact-pack-and-triage-2026-09-23.md` M7.

## Defect and cause

MCP `runtime_status` on default (PID 70720) answered state `unknown` with
`java.lang.UnsupportedOperationException: count not supported on this type: Date`
at `seon.cluster/mcp-runtime-observation` (`cluster.clj:629`).
`seon.cluster.boot/readiness` carries `(problems/problems database {})`, whose
declared output is `[:or :seon.problems/problems :seon.test/execution-error
:seon.db/invalid-read-error :seon.schema/missing-projection-error]`
(`src/seon/problems.clj:379-384`). The observation counted every entry as a
family, so a refusal value (`:seon.error/at` is a Date) threw.

The failing read on default: `seon.test.runner/latest-results`
(`src/seon/test/runner.clj:1693-1728`) refuses `:seon.test/population-unknown`
because named run `7365f44c382f` (`seon.schedule-test`, 22:06:55Z) left 4 of 7
members without `:seon.test.member/completed-tx`; `failed-tests` returns the
refusal and `problems` answers it for the whole derivation
(`problems.clj:407`). Filed: `docs/seon/issues/an-unfinished-test-run-hides-every-problem-family.md`.

## Change

`src/seon/cluster.clj` `mcp-runtime-observation`: a problems value carrying
`:seon.error/at` is shown whole as `:seon.dev.mcp/problems-unavailable`, and
`:seon.dev.mcp/problem-counts` is absent (never an empty map that reads as
healthy). The family-map case counts as before. With no readiness, counts are
absent (previously `{}`).

Regression: `test/seon/cluster/mcp_test.clj`
`runtime-observation-shows-unavailable-problems-with-their-cause`, beside the
existing `runtime-observation-counts-problems-without-embedding-facts`.

## Evidence (default PID 70720, MCP eval_clj JVM mode)

| operation | wall | result |
|---|---|---|
| `runtime_status default` before | <1 s | refused, `count not supported on this type: Date`, frame `cluster.clj:629` |
| `(seon.problems/problems (seon.db/db (seon.cluster.boot/connection "default")) {})` | 1167 ms cold | refusal map above (`carried-projection` present) |
| run `7365f44c382f` member pull/query | 1.75 ms | 7 members, 4 with no `completed-tx` |
| hot `load-file` of the edited defn into `seon.cluster` | 1.3 ms | Var replaced |
| `(seon.cluster/mcp-runtime-observation "default")` after | 39 ms | keys cluster, health, flow, problems-unavailable, readiness |
| `runtime_status default` after | <1 s | state `alive`, flow procs all `:reply`, `problems-unavailable` with the runner refusal |
| `bin/seon status` after | 96 ms | same `:problems-unavailable` |
| `load-file test/seon/cluster/mcp_test.clj` | 29 ms | loaded |
| both regressions, HEAD defn | 1.2 ms | 3 pass, 1 error (`count not supported on this type: Date`) — falsifies |
| both regressions, edited defn | 0.7 ms | 2 tests, 6 pass, 0 fail, 0 error |

clj-kondo on both files: 0 errors (26 pre-existing warnings in cluster.clj).

## Verification limits

- The edit is live on default only by a hot `load-file` of the one defn (no
  adoption, no publication; lanes do not adopt default). The replaced root is
  not Malli-instrumented until the next reload/adoption re-arms it. Indexed
  program facts still describe the HEAD defn until publication.
- Tests ran by `clojure.test/test-vars` in the default JVM, not through
  `seon.test/run`: the new test is not indexed, so a runner request could not
  select it without adoption. `with-redefs` on `boot/readiness` was global for
  ~1 ms.
- No schema resource changed. Not changed (outside this lane's paths):
  `resources/seon/schemas/seon.boot.edn:114` still declares readiness problems
  as the family map only; the observation output schema is `:map` with its
  members undeclared in `seon.dev.mcp.edn`.
- `docs/seon/issues/cluster-status-renders-unknown-observations-as-raw-error-maps.md`
  is the browser/HTML presentation of unknown values; this change does not
  cover it and it stays open.
- Default reports `:seon.boot/ready-ms 106787` (107 s boot) — over the 10 s
  bound; not measured or changed here.

RESET NEEDED: no.

Commit: 24c42427a (cluster.clj, mcp_test.clj, this note, three issue notes).
HEAD load: the edited defn compiled in `seon.cluster` on default; a whole-namespace load of HEAD was not run separately.
