# Agent-identity and context-selection reds from batch 70

Date: 2026-09-17. Lane: agent-identity/dials. Branch `steward-platform`.
Batch 70 was cold on `ca9a8b0e8`
(`tmp/orchestrator/gate-results/batch-70/named.md`, root `tmp/test-runs/run.1jtfxl`).
Commits: `62110d9b0`, `f3ac3be86`.

Both families were assigned as "not elision, not fixture-refusal". One of them
is a fixture refusal; the attribution below is what the evidence actually says.

## 1. `seon.cluster.agent-identity-test/identity-map-and-omitted-arguments-use-the-same-function` (5 F)

Not a rename defect. `seon.agent/identity` (`src/seon/agent.clj:11`) reads the
renamed `:seon.agent/*` names correctly and still projects
`:my.agent/id` / `:my.agent/namespace` / `:my.agent/steward`. The evaluated
value the test inspects never comes from that function any more.

Two independent expectations went stale against two deliberate changes:

| Assertion | Ruling commit | What the render emits now |
|---|---|---|
| `(:my.agent/id value)` and its two siblings (lines 113-115) | `d6377ac39` "Emit raw identity pulls and inspect empty agent namespaces", 2026-09-09 15:55 | `seon.cluster.agent/identity-form` emits `(seon.db/pull '[:seon.agent/id {:seon.agent/namespace [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}] [:seon.agent/id …])`. `e4372b061` had briefly made it `(my.agent/identity)`; `d6377ac39` reverted that on purpose and updated `test/seon/help_test.clj` and `test/seon/render/ns_test.clj` in the SAME commit — `agent_identity_test` was the one it missed. |
| `:my.agent/turns-left` in `(my.agent/settings)` and in the settings block (lines 120, 124) | `0dca8534e` "Reject self-dependent generated context reads", 2026-09-14 | The commit message says it: "remove turn accounting from opening reads". `settings` returned to a bare `ai/agent-overlay`, `effective-settings` dropped `remaining`, and `render-settings-ai` became `(seon.agent/settings)`. `resources/seon/schemas/my.agent.edn` declares `:my.agent/settings` as `:seon.config/agent-overlay`, which has no `:my.agent/turns-left` member. `97d1f69e0` is not in this path. |

Fix: the test. Identity assertions return to the pull shape they had before
`e4372b061`; the settings assertions drop `turns-left`, and one added assertion
states its absence so the removed rule cannot silently return. `turns-left`
remains derivable on demand (`seon.turn/turns-left`, `src/seon/turn.clj:2742`,
read by `src/seon/repl.clj:43` and `src/seon/render/transcript.clj:1275`).

## 2. `seon.context-selection-test/selection-references-terminal-evaluations-in-writer-decided-order` (15 F)

This IS a fixture-refusal red, contrary to the assignment's premise. The first
assertion in the test (`context_selection_test.clj:54`) failed with
`:seon.db/invalid-write`: the ONE seed transaction was refused whole, so the
`{:seon.agent/id "selection-a"}` rows never landed and every later
`context/append-tx` answered `:seon.context/no-such-agent`. The ten downstream
failures, including the six `(not= :seon.context/no-such-run
:seon.context/no-such-agent)` mismatches, are all that one refusal.

Refused rows, reproduced against default's own database and projection with
`(#'seon.db/write-error db projection tx-data)` (LIVE-PROOF VALIDATION RULE):

1. `seon.db/transact! refused transaction data at [2 :seon.turn/closed-tx]:
   expected a value satisfying unknown error, got an instance of
   java.util.Date.` — `:seon.turn/closed-tx` became a REF to the closing
   transaction in `ae0e54841`; the seed wrote `#inst`. Its required sibling
   `:seon.turn/opened-tx` was absent too.
2. `:seon.cluster.eval/at` is required on a receipt row
   (`resources/seon/schemas/seon.cluster.eval.edn:36`) and the frozen evaluation
   rows had no time.

Both are the classes `4d181533d` and `55a0abae0` fixed elsewhere today; this
test's first `deftest` was not in either slice (`55a0abae0` fixed only
`compact-replaces-only-observed-evaluation-refs` in the same file).

Fix: the test's own seed, not a canonical helper. `seed-cluster!` is not
involved — this fixture seeds no cluster — and `agent/creation-tx` is not used
here; the bare `{:seon.agent/id …}` rows are admissible as written and were only
lost to the atomic refusal. Closed turns now name `"datomic.tx"`, matching
`seon.cluster.problem-routing-test` since `4d181533d`; every seeded turn carries
`:seon.turn/opened-tx`; evaluation rows carry `:seon.cluster.eval/at`.

No `src/seon/context.clj` change was needed: with the seed admitted, each
declared rule refuses with its own cause — `no-such-run`, `foreign-run`,
`run-open`, `no-evaluations`, `unfinished-evaluation`, `contribution-conflict`,
`foreign-contribution` — observed in the writer log of the green run.

## In-process proof (default, pid 74930, start-instant 2026-09-16T13:07:52Z)

`seon.test-support/database-base` was constructed first on a bare daemon future
(BASE CONSTRUCTION RULE), realized to a `PersistentHashMap`. Each test then ran
on its own daemon future through
`(seon.test/run (#'seon.test/resolve-test 'sym) connection
{:seon.test.run/provenance (seon.test.runner/provenance db)
 :seon.test/remaining-ms 100000})`, with the namespace reloaded first through
`(#'seon.test/with-test-loader #(require 'ns :reload))` (IN-PROCESS TEST RELOAD
RULE).

| Test | pass | fail | error |
|---|---|---|---|
| `seon.context-selection-test/selection-references-terminal-evaluations-in-writer-decided-order` | 42 | 0 | 0 |
| `seon.context-selection-test/compact-replaces-only-observed-evaluation-refs` | 14 | 0 | 0 |
| `seon.cluster.agent-identity-test/identity-map-and-omitted-arguments-use-the-same-function` | 17 | 0 | 0 |
| `seon.cluster.agent-identity-test/identity-renders-from-current-database-facts` | 5 | 0 | 0 |
| `seon.cluster.agent-identity-test/identity-rendering-keeps-partial-data-and-read-errors-visible` | 3 | 0 | 0 |

Every `deftest` in both namespaces is covered (2 and 3 respectively).

## Verification boundary

In-process runs on the shared development JVM only. No test JVM was launched,
`default` was never restarted, and no `src/` file was changed by this lane. The
cold gate is the proof: `tmp/orchestrator/gate-requests/agent-identity.txt`
requests `seon.context-selection-test`, `seon.cluster.agent-identity-test`, and
`--platform`. Nothing here speaks to the other batch-70 families (the 199-failure
`concurrency-independence` block, `schema-usage-guard`, `turn-loop`,
`turn-work`), which other lanes hold.
