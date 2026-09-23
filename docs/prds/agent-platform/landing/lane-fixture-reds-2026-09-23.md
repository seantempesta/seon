---
type: landing
status: landed; three src reds filed
created: 2026-09-23
tags: [agent-platform, testing, fixture, definition-digest]
---

# Lane fixture-reds — 2026-09-23

Stability track. The reds come from fixtures that hand-write namespace, schema and
agent rows without keys the schemas now require (`:seon.program/definition-digest`,
`:seon.agent/branch`). Each red got the three questions from plan README §6.
Default pid 90963 runs from the checkout. HEAD was 7e5cdea9e when the lane
started. Other lanes kept committing during the lane.

## Changed paths

- `test/seon/test_support.clj`: new `namespace-row`, which builds the row an agent's
  `(ns name)` writes through `program/declaration-row`, and that owner derives the
  digest. New `agent-tx`, which calls `seon.cluster.agent/creation-tx` in the
  fixture's one cluster, so the agent gets `:seon.agent/branch`, a namespace and
  components from the owner. `program-row` now passes `namespace-row` to
  `seon.fn/source-rows`, as `seon.sci.eval/definition-row` does
  (`src/seon/sci/eval.clj:425-428`). This fixes the open note
  `docs/seon/issues/fixture-namespace-rows-lack-the-required-definition-digest.md`
  at the helper. The orchestrator can close that note.
- `test/seon/program_test.clj`: five hand-written `{:seon.ns/name ... :seon.ns/source ...}`
  rows and one bare resolver-context row now use `namespace-row`. This was one
  script. The synthetic schema row gets its digest from `program/definition-digest`
  (`with-definition-digest`). `digest-map-compares-two-fixture-branches-from-one-commit`
  now nests two `with-database` fixtures, which are two isolated branches of the
  member's commit. It used to call `d/branch!` and `d/connect` directly, and the
  writer refused that write because it named a branch outside the writing cluster.
  Retired assumption: the expected
  `:seon.turn/rule :seon.turn/run-opening-basis-unreadable` left src at `da73fcd28`.
  `seon.turn/opening-db` now answers `:seon.turn/missing-opening-datom true`
  (`src/seon/turn.clj:264-284`), and the expectation now checks that key.
- `test/seon/render/transcript_test.clj`: twelve hand-written `{:seon.agent/id ...}`
  transaction rows now use `agents-tx`, which calls `support/agent-tx`. This was
  one script plus one site with a namespace done by hand. Retired assumption: an
  agent with no namespace prompted `user=>`. Every created agent now has its
  namespace, so eight expectations use `agent-prompt`, which is
  `my.agents.transcript-agent=> `.

Net test lines: +127 / −97. No src edits.

## Verification (default JVM hot path)

| operation | run id | wall | result |
|---|---|---:|---|
| `bin/test-check default --policy named --ns seon.program-test`, baseline | e1012003427e | 18.0 s | 8 errors |
| `bin/test-check default --policy named --ns seon.render.transcript-test`, baseline | 82a15ec45cc2 | 31.6 s | 1 fail, 15 errors |
| program-test after slice 1 (`seon.test/run` via eval_clj, same form test-check sends) | 3cc834ea6b90 | 60.2 s | 1 fail, 2 errors |
| program-test final (`bin/test-check`) | e2bfb847b456 | 86.1 s | 384 pass, 1 error (analyzer, below) |
| transcript-test after slice 1 | 911d57d52fe3 | 83.2 s | 12 fail, 2 errors (prompt expectations) |
| transcript-test final (`bin/test-check`) | f430406e6816 | 84.9 s | 165 pass; one-reply (src) and reasoning (src) red; populated-history over its bound at 22.8 s because of concurrent load |
| transcript-test final (eval_clj) | 3d30c0558fa5 | 62.4 s | same two src reds; two "Worker-global state changed" drifts from other lanes' reloads during the run |
| `digest-map-compares-...` alone | 3b45dc9be7c2 | 43.6 s wall, 1.9 s body | pass |
| `bin/seon init --dev default --changed <3 test paths>` | — | 29.2 s | adopted; the profile shows a concurrent `refresh-source!` and two `seon.test/run` calls from other lanes |
| `bin/seon init --dev default --changed program_test.clj` | — | 8.8 s | adopted; `full-source-refresh!` 6.1 s, `fn/index!` 5.9 s |
| `bin/seon init --dev default --changed transcript_test.clj` | — | 9.9 s | adopted; `full-source-refresh!` 6.4 s |

Probe forms, all in JVM mode in the throwaway namespace `tmp.fixture-reds`:

- `(seon.program/declaration-row p {:seon.ns/name 'sample :seon.ns/source "(ns sample)"} :all :agent)`
  took 2.3 ms and returned the digested row.
- Both new `test_support` contracts compile against
  `(schema/build-projection (seon.schema.edn/packaged-forms) {})`, giving `[true true]`.

## Remaining reds and their owners (src, not edited)

1. `seon.program-test/indexed-and-evaluated-declarations-are-the-same-entities`
   fails in `seon.fn.analyzer/invoke-kondo`, which refuses its own linted
   `tmp/` file as a foreign kondo entry (`src/seon/fn/analyzer.clj:307-310, :370`).
   Reproduced in 49 ms. The fixture writing under `tmp/` is `test/seon/fn_test.clj:1821`.
   New note: `docs/seon/issues/analyzer-refuses-a-checkout-file-outside-source-roots-as-a-foreign-kondo-entry.md`.
2. `seon.render.transcript-test/one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt`
   is refused because `seon.turn` upserts a bare `{:seon.ns/name my.agents.probe}` row
   (`src/seon/turn.clj:782, :817, :1459-1466`). New note:
   `docs/seon/issues/turn-writers-upsert-bare-namespace-rows-without-a-definition-digest.md`.
   Its body also measured 5.6–7.9 s, over the 5 s bound.
3. `seon.render.transcript-test/reasoning-is-html-only-and-inline-blob-history-has-one-disclosure`
   fails because the first SCI render on a fresh fixture branch exceeds the test's
   1000 ms evaluation limit. `render-identity-ai` answers `:seon.render.unknown`
   with `:reason :time-limit`. The next render of the same database takes
   280–290 ms and succeeds, so `before` and `after` differ. It is deterministic
   over three probes: 1781 ms, then 289 ms, then 279 ms. The first call's time
   goes to `seon.sci.kernel/ensure-function!`,
   `seon.sci.eval/acquire-function-from-database!` and `acquire!`, which took
   1066 ms, of which `derive-base-ctx` and `acquire-program!` took 860 ms. The
   agent's creation writes a namespace program row, so the base-context key's
   attribute revisions (`src/seon/sci/eval.clj:2447-2463`) miss, and the whole
   program is re-acquired. That class is in the existing
   `docs/seon/issues/a-data-only-commit-rebuilds-the-whole-sci-program.md`.
   The orchestrator folds these rows into it. Warming the context in the test
   would be priming, so it was not added.

## Where the time goes

Profile: `seon.profile/explain` over each `seon.test/run` (the C1 directive that
test-check's run records per member) plus the per-member `:seon.test/timings`.
The counts include concurrent work from other lanes.

- **Request level, per run, independent of member count:** `seon.test/admit-run`
  runs `seon.test.runner/program-digest`, which calls `derive-program-digest`,
  for 5.6–7.4 s per request. That digest covers the whole program. Then
  `seon.test/select` and `selection-admission` take 2.6–9.0 s, and
  `seon.test.runner/commit-results!` takes about 0.7 s per member (22.3 s over
  31 members in 3cc834ea6b90), with `reach-digests` taking 13.9 s. A one-member
  request costs about 10.4 s of overhead around a 0.7 s body (c55c8bc1da2d).
  This is src (`seon.test`, `seon.test.runner`) and outside this lane. It is the
  largest share of the 63 s and 35 s the orchestrator observed.
- **Member level:** acquire is about 45 ms and release about 10 ms per member.
  Bodies are 0.07–4.5 s. Per `with-database`, the branch costs about 350 ms,
  `agent-tx` about 400 ms (`namespace-seed-call` about 60 ms per agent), a
  one-message transaction 180–280 ms, and one `render-ai` 390–440 ms
  (probe: three `with-database` rounds at 1566, 1476 and 1499 ms). Most bodies
  do little else, so they land at 1.0–1.7 s. Members over 1 s:
  `typed-cross-namespace-deletion...` 4.5 s (seed-cluster, creation-tx, source
  analysis and three turn transactions), `function-contract-redefinition...`
  2.6 s, `program-partition...` 2.5 s, `changed-runtime-redeclaration...` 2.5 s,
  `declaring-an-attribute...` 2.4 s, the reasoning test 3.3 s, and
  `the-transcript-is-whole...` 2.5 s. None repeats whole-program work per
  member in test code. The per-transaction writer cost and the first SCI
  acquisition are src owner costs, so nothing in these files could be hoisted to
  the namespace level.
- **`every-generated-history-is-ordered-and-total` (66.7 s):** it runs 40
  quick-check trials. Each trial needs its own fresh branch, and a nested fixture
  never sees its parent's writes, so agent creation cannot be hoisted. Each trial
  pays branch plus agents plus one transaction plus two renders, about 1.6 s,
  which gives 40 × 1.6 s ≈ 66 s. The canonical `agent-tx` adds about 0.2 s per
  trial over the old bare rows. The member declares `:seon.test/long` with no
  numeric `:seon.test/long-ms`, so an `--include-long` run fails at the 5 s
  default. I did not run it (over 10 s). It stays a defect: it needs a
  `long-ms` measured by the owner, or a per-trial cost cut in the writer and
  acquisition owners above.

## Other findings

- 273 hand-written `{:seon.agent/id x}` or `{:seon.ns/name 'x ...}` literals remain
  in 91 other test files (one `rg -c`; it also counts lookup maps, so it is an
  upper bound). The top files are `db_test` 27, `effect_test` 14, `turn_test` 12,
  `fn_test` 11, `turn_loop_test` 9, `web_debug_test` 9 and `loop_proof_test` 9. I
  did not edit them.
- `transcript_test.clj` redefines default's Vars. It uses `with-redefs-fn` over
  `#'db/q` and `#'render/render-call` in `durable-history-entries-never-invent-executions`,
  `with-redefs` over `blob/get` in the reasoning test, and `with-redefs` over
  `sci.eval/evaluate` in the one-reply test. That is against the no-redefs rule,
  and I did not change it in this lane.

RESET NEEDED: no.

Commit: `090601ae1` (fixtures, this note, two issue notes).
