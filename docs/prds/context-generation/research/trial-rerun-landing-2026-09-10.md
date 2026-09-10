---
type: research
status: active
tags: [research, ai, agent, test]
---

# Help trial rerun — 2026-09-10

One paid `seon.ai/complete` call returned a real answer with thinking disabled:
**12/12**, versus the previous **10/12**. No paid retry or second paid call.
The reply was scored without evaluating its forms.

| Evidence | Previous baseline | This trial |
|---|---|---|
| Model | deepseek-v4-flash | deepseek-flash (DeepSeek-V4.1-Flash) |
| Score | 10/12 | 12/12 |
| Input / cached / output tokens | 2,196 / 0 / 415 | 2,533 / 0 / 289 |
| Total tokens | 2,611 | 2,822 |
| Provider latency | See original evidence | 2,142 ms |
| Price estimate | $0.00042364 at historical rates | **$0.00055335** at verified current rates |

Original evidence: [help_trial_2026_09_09.edn](help_trial_2026_09_09.edn).
New immutable prompt, expected generated sources, model candidates, raw reply,
usage, and all twelve checks: [help_trial_2026_09_10.edn](help_trial_2026_09_10.edn).
The new file retains the actual captured database prices and their computed
$0.00043554 estimate. Those were still the V4 prices in the live database;
the corrected cost above uses the published V4.1 rates, not those stale facts.

Capture time: `2026-09-10T20:03:42.541342Z`; basis transaction `536872590`.
Opening: 7,866 UTF-8 bytes; complete request: 8,417 bytes.
Help source SHA-256: `90c7ce5275051687165e812c91169477ef56b69964a4e1d7d131ff7754f69d9e`.
The copied exact reply below was checked against the saved completion string.

## Exact reply

```clojure
;; 1. Thinking comments (;; ...) before a form.
;; 2. A #:seon.repl map with :value or :error, :out, :result; I see it in my NEXT turn.
;; 3. result/e... is a live symbol bound to the value — evaluate it, pass it as an argument, or dig in with get-in/keys.
;; 4. Inspect first: (dir ...) for functions/schemas, (doc ...) for docstrings/contracts.
;; 5. Query the orders: read order ids, customers, and amounts. Done when I have read the order ids, customers, and amounts.
;; 6. A turn ends after one reply; a session ends with (my.agent/done); (seon.turn/turns-left) said 20 turns left.
;; 7. No — I act on a result only after seeing it in the next turn.

;; The first step is "Query the orders" — read order ids, customers, and amounts.
(seon.db/q
  '[:find ?order ?customer ?amount
    :where
    [?e :example/order ?order]
    [?e :example/customer ?customer]
    [?e :example/amount ?amount]])
;; I expect rows of [order customer amount]; I will read them next turn before aggregating.
```

All twelve checks passed: thinking, response, reuse, documentation,
instruction, session, deferred completion, right function, argument shapes,
no prompt marker, no premature completion, and syntax. This is the unchanged
12-point rubric, not a claim of general model quality. In particular, the
rubric checks the turn count but does not check the reply's attribution of it
to `seon.turn/turns-left`; the prompt actually read effective settings.

## Provider verification

Read [LLM settings](../../../seon/reference/llm-adapters.md) and the
[previous pricing note](../../sci-execution-runtime/research/model-registry-deepseek-pricing-2026-08-03.md)
end to end. Retrieved the official
[Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing/) page
on 2026-09-10. Firecrawl was unauthenticated and the web fetch timed out;
bounded `curl` retrieved the provider HTML successfully.

| DeepSeek-V4.1-Flash, USD per million tokens | Off-peak | Peak |
|---|---:|---:|
| Cache-miss input | 0.15 | 0.30 |
| Cache-hit input | 0.003 | 0.006 |
| Output | 0.60 | 1.20 |

Peak is Monday–Friday, 01:00–04:00 and 06:00–10:00 UTC. This call was
outside those hours. Cost: `(2533 * 0.15 + 289 * 0.60) / 1000000`.
The provider identifies `deepseek-flash` as DeepSeek-V4.1-Flash and says
legacy Flash IDs route to that model at its current price.

`config/default.edn` now carries the verified off-peak prices and active
schedule status. The old daily off-peak refs were removed from this row:
they cannot express the provider's weekend rule. No time-dependent billing
implementation is claimed by this config correction.

The official [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)
page confirms thinking defaults to enabled/high and accepts
`{"thinking":{"type":"disabled"}}`. The supported default-JVM probe
followed `config/effective` → `ai/targets` → `ai/request-body` and returned
that exact disabled field for `deepseek-flash`, with no `reasoning_effort`.
The existing owner already behaved correctly; no production `seon.ai` edit
was needed. The added regression resolves the actual model row in the
canonical database under armed contracts, for disabled and high settings.

## Harness and live boundary

Read AGENTS.md's lane rules (including numbered rule 10), PRD §10 and
§§18a–18b, and the complete committed harness end to end. AGENTS.md has no
heading named section 10; its opening lane rules explicitly copy PRD §10.

Dependency ledger: Datahike immutable values and explicit `seon.db` queries;
SCI context acquisition (`reference-code/sci/src/sci/core.cljc`, `init` and
`fork`); production `seon.turn/declared-sources` and `system-plan` (also used
by `seon.loop-proof-test`); the shared `context_blocks_fixture.clj` installer;
and `seon.ai/targets`, `wire-settings`, and `request-body`, whose field
declarations live in `resources/seon/schemas/seon.config.ai.edn`.
Reference revisions:

- `reference-code/datahike`: `cdcb5792db8bd599487f099437265d18a31164a5`.
- `reference-code/sci`: `fcbd8862800e638dc0f8f5521111f999279cbcd2`.

The preflight now derives the exact ordered opening through the production
generator and its source deduplication, rather than a source roster. It reads
the `:seon.message/inbox` edge, including incomplete messages, and requires
the fixture's one root instruction, exact orders, one system opening, no
evaluation errors, no ordinary turns, and matching shown/live turns left.
Preparation captures one immutable database value and hands that same value
to both preflight and provider-prompt acquisition. The initial request record
is saved before sending; the completion is saved before scoring.

Reseed used the existing fixture in default's running JVM. A first direct
`install!`/opening attempt encountered `agent-already-running`. The shared
`install-running!` armer barrier completed; Juniper was disarmed, its setup
history cleared through the fixture, and a fresh system opening stored.
Default PID 23557 was never stopped, reforked, or restarted. Juniper was
disarmed for capture, with its no-provider fixture setting installed.
This is a supported JVM/fixture proof, not a browser or full autonomous-loop
proof. No renderer files were edited.

One pre-call refusal exposed duplicate runtime sources in the raw walk.
Using the production `system-plan` deduplication fixed the expected opening;
the refusal occurred before the result file or any provider request existed.

MCP runtime health timed out while direct JVM evaluation worked. This is
recorded in [the existing MCP issue](../../../seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md).
Foreign uncommitted renderer, REPL, CSS, tests, and documentation edits were
preserved and excluded from the selected-path gates.
Adjacent stale provider references, sampling semantics, and weekday pricing
representation are filed in [one provider issue](../../../seon/issues/provider-reference-and-price-schedule-drift.md).

Closing live boundary, after the paid capture: the supported read found
14 saved evaluations, two ordinary turns, an empty inbox, zero evaluation
errors, and Juniper armed again. The generator still declared nine distinct
sources; there were no added or removed distinct source strings. Preflight
correctly refused this advanced fixture. The actor that advanced/rearmed it
was not established; this is not attributed to another lane. No further
reseed, provider call, or process operation followed. The immutable captured
opening and paid result above remain the trial evidence.

## Verification

Initial fast iteration: 54 tests, 253 assertions, zero failures/errors.
Final harness iteration: 2 tests, 22 assertions, zero failures/errors.
Selected-path isolated gate: **55 tests, 263 assertions, zero failures/errors**;
coordinator/tests 67 seconds, published-base preparation 48 seconds. Its
successful disposable root `run.dPOj6q` was removed by the runner.
Explicit selected-path platform gate: **84 tests, 505 assertions, zero
failures/errors**, 118 seconds for coordinator/tests. Its successful root
`run.KC33MF` was removed by the runner. Both gates snapshot HEAD
`57d7ec005cfbaa337576b0c49189afd0451f3e1e` plus only the selected paths.
The first new fixture test omitted its required fault channel; after fixing
that setup, its incomplete message write was refused by schema admission.
The final test inserts an admissible senderless message and asserts the write
succeeded before expecting pollution refusal. It also checks altered orders,
altered opening source, and immutability across a connection advance.

The isolated gate runs `seon.ai-test seon.help-trial-test` with
`SEON_TEST_WORKERS=2` (two pool workers plus serial), followed by the explicit
`--platform` gate. Both use `--paths` with exactly the owned paths listed
below. No `--all` or `--full` was run. A gate started before the fixture repair
was interrupted during preparation and its owned disposable root cleaned.

Owned paths:

- `config/default.edn`
- `docs/prds/context-generation/research/help_trial_2026_09_09.clj`
- `docs/prds/context-generation/research/help_trial_2026_09_10.edn`
- `docs/prds/context-generation/research/trial-rerun-landing-2026-09-10.md`
- `docs/seon/issues/dev-mcp-envelopes-misdirect-errors-and-sprawl-status.md`
- `docs/seon/issues/provider-reference-and-price-schedule-drift.md`
- `test/seon/ai_test.clj`
- `test/seon/help_trial_test.clj`
