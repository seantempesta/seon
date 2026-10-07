# I-Q4: caller and test closure of the prompt, capture and walk-composition code

Measured at HEAD `cd8a481c2` (uncommitted tree edits in `turn.clj`, `turn/batch.clj` and tests belong to other lanes; the
cited lines of `prompt.clj`, `context.clj`, `render.clj` and `render/web.clj` are unmodified). Method: `rg` over `src`,
`resources`, `test` plus a per-`deftest` scan. Nothing was executed against default; this is a read-only inventory.
The plan's anchors still hold: `prompt.clj:171-402`, `context.clj:309-415`, `render.clj:1721`.

## 1. What the prompt path is today

One chain produces every prompt, and it runs through the web namespace:

`turn.clj:4554` (`@cluster-prompt-prompt`, delay at `:64`) → `seon.cluster.prompt/prompt:403` → `acquire-context-report:349`
→ `render/acquire-context!:1721` → `@render-web-derive-context!` (`render.clj:49,1733,1741`) →
`seon.render.web/derive-context!:2459` → `render.walk/history:840` (one `render/render-call` per saved evaluation) →
`history-segments:2418` → back in prompt: `select:284`, `compose:272`, `selection-segments:255`, `history-contributions:171`,
`capture-mismatch:318`, `repl/frame` (stays: it is the turn frame, not history).
Then `turn.clj:4568` calls `seon.context/capture-tx:359` (via the delay at `:65`) and commits it before the provider call.

`derive-context!` also owns the `::ai-calls` retained-call cache (`web.clj:2477,2509,2520`; read at `:1882`, merged at
`:2332`, dropped by `render-step` at `:2410`). `::ai-entries`, `::context-bases` and `::context-programs` (`:2410-2411`)
are only dissoc'd, never assoc'd anywhere: dead names.

## 2. Function-by-function closure (non-test callers)

| Function | Callers (outside its own namespace) | Note |
|---|---|---|
| `prompt/prompt:403` | `turn.clj:4554` (the agent loop); `web.clj:3188` (`sent-context`, requiring-resolve) | the only two |
| `prompt/model-calibration:106` | `prompt.clj:403` only (inside `prompt`) | reads `:seon.context.capture/run` and `:seon.ai.tokens/characters` at `:86-87` |
| `prompt/select:284`, `compose:272`, `selection-segments:255`, `selection-of:278`, `retained-count:215`, `dropped-elision:236`, `priced-unit:210`, `history-contributions:171` | `prompt.clj` only (`acquire-context-report`) | tests call `select`/`compose` directly |
| `prompt/capture-mismatch:318` | `prompt.clj:394` only | reads `:seon.context.capture/prompt` |
| `context/capture-tx:359` | `turn.clj:66` delay, called `:4568` | the only writer of capture rows |
| `context/contribution-entity:336` | `capture-tx` only | |
| `context/contribution-hash:415` | `prompt.clj:197` (`history-contributions`) and tests; not `capture-tx` | one digest seam |
| `context/capture-ai:309`, `capture-html:320` | named by symbol in `resources/seon/schemas/seon.context.capture.edn:5-6` only | the schema's render pair |
| `context/message-custody:257` | `render/transcript.clj:243` (`::custody` on a message entry) | **not capture**: transcript entry for a message. Lives in the capture section only by position; it must survive S4-07 or move |
| `render/acquire-context!:1721` | `prompt.clj` (`acquire-context-report`); `web.clj:3396` (`context-action-response`, the `:render/context-action` branch); `render/call_test`-style tests | `render.clj:1731-1733` routes `:seon.render/context-action` to `derive-context!` too |
| `web/derive-context!:2459` | `render.clj:1733,1741` only | two arms: `change-context` (`:2427`, actions `:system-turn :virtual-turn :compact`) and the history fold |
| `walk/history:840` | `web.clj:2497` only (inside `derive-context!`) | tests: see section 5 |
| `walk/neighborhood:675` | `web.clj:496` (agent/namespace page, HTML), `turn.clj:1728` (the refresh, S4-06) | the page caller survives |
| `walk/root-acquisition:502` | `web.clj:1939` and its own `neighborhood:692` | survives with the page |
| `walk/graph:1117`, `walk/related:1211`, `walk/with-related:1292` | `render/value.clj:74` (delay on `with-related`), schemas | survive: they print data cuts, not the prompt |
| `walk/continue:789` | `web.clj:3523`, `cluster.clj` `mcp-get-value` | survives (value paging) |

So **the walk's `history` has exactly one non-test caller and the walk's other entries are not prompt code**. `render.clj:1721` is not itself a walk entry: it is `acquire-context!`, a two-line router to `derive-context!`.

## 3. Facts that read the capture rows

| Reader | Where | What it reads |
|---|---|---|
| calibration | `prompt.clj:74-104` `calibration-for` | join `attempt → run → capture`, `:seon.ai.tokens/characters` |
| replay check | `prompt.clj:318-347` `capture-mismatch` | `:seon.context.capture/prompt` |
| `committed-attributes` | `turn.clj:3124-3125` | the capture and contribution entity names in the loop's write set |
| attempt-turn accounting | `turn.clj:2660` comment only | |
| schema | `seon.context.capture.edn`, `seon.context.contribution.edn`, `seon.context.edn:16-30` (`contribution` map) and `seon.cluster.prompt.edn:5-6` (`:seon.context/contributions` on `rendered-context`) | |

No web page, MCP tool or transcript render reads a stored capture row. `capture-html` is reachable only if a
capture entity is rendered by schema (an entity page); nothing links to one.

**Finding that changes S4-07's design:** the calibration dial reads `:seon.ai.tokens/characters` from the capture. That
attribute exists only on `seon.context.capture` (`seon.context.capture.edn:21`, `context.clj:400`). Deleting capture without
moving the character count to the attempt (or to the stored basis) silently turns the calibration into its fixed prior and
makes `seon.cluster.prompt-test/calibration-uses-the-agents-recent-attempts-and-config-prior` red. The plan's
`a-model-call-stores-basis-without-prompt-capture` does not name where the count goes. This needs a plan decision before
S4-07 is assignable.

`:seon.context.contribution` rows are **shared with a second feature**: `context/selection:76`, `append-tx:160` and
`compact-tx:206` (agent-owned selection and compaction, `:seon.context.contribution/agent`, `/evaluations`), and
`render/transcript.clj:617` reads `:seon.context.contribution/evaluations`. Those have no non-test caller in `src`
(`rg append-tx|compact-tx|context/selection` finds only `context.clj`; `turn/compact!:2297` uses its own `compact-call`).
They are not in the plan's S4-07 range (`context.clj:309-415`); the schema entity and `seon.context.edn` keep a user until
that is separately decided. Deleting `contribution-entity` frees only the capture-component use.

## 4. Web and debug callers: each survivor and its data entrance

| Surface | Today | Verdict | Data entrance after S4-04/07 |
|---|---|---|---|
| Debug page `?sent=true` Context disclosure (`web.clj:3185-3207` `sent-context`, `sent-context-html`, `:3246-3256` toggle, `debug-response:3223`) | calls `prompt/prompt` at `as-of` any basis, then `data/at` cursor paging | **survives**, the only AI-prompt web consumer; **not served by a held proc transcript** (the page has no proc). Needs a plan decision: (a) render the saved exchange text directly from `evaluation/of-agent` + shown text, which is what `derive-context!` already does by `walk/history`; or (b) read the transcript the proc holds. Option (a) keeps this and deletes `prompt`'s walk from the page | the saved exchanges: `evaluation/of-agent` (`walk.clj:866`) then each evaluation's `:seon.eval/shown` |
| `context-action-response` (`web.clj:3390`) POST `:system-turn / :virtual-turn / :compact` | goes through `render/acquire-context!` with `:seon.render/context-action` (`render.clj:1731`) into `change-context` (`web.clj:2427`) | **survives as an action**, but it is a turn operation, not context. It should call `change-context`'s three turn functions directly; routing it through `acquire-context!` is the only reason `render.clj:1731` has a second arm | `seon.turn/system-turn`, `virtual-turn!`, `compact!`, all existing |
| Agent page neighborhood (`web.clj:496`, `walk/neighborhood`) | HTML over data | survives untouched; not prompt code | existing |
| `?record=true` fragment, `current-page` | HTML over `walk` units | survives untouched | existing |
| `capture-html` on a capture entity | rendered only if the entity is rendered by schema | dies with the schema | none (no reader) |
| `render-step` `::ai-calls` merge (`:2332,:2410`) and `:1882` | retained prompt calls | dies with `derive-context!`'s history arm | none |

`docs/research/agent-platform/2026-10-07/walk-delete/*` describes a `seon.agent.loop/context` that does not exist; the
replacement is the held proc transcript (S4-04). The Sol packet lists those four passages for correction; they are not
touched here.

## 5. Test closure (deftest granularity; counts from `rg`, scan in the scratchpad)

**Tests that exist only to prove the deleted machinery (S4-07 deletes or converts):**

| File | Deftests |
|---|---|
| `test/seon/context_capture_test.clj` | `context-contributions-reference-existing-evaluations` (whole file) |
| `test/seon/context_capture_history_test.clj` | `retracted-capture-retains-the-exact-prompt-in-history` (whole file) |
| `test/seon/cluster/prompt_test.clj` | `prompt-prices-the-exact-retained-history:93` (contribution hashes), `a-budget-smaller-than-the-history-keeps-whole-newest-units:208`, `a-budget-larger-than-the-history-composes-the-same-bytes:260`, `every-unit-is-priced-from-its-own-shown-text:281`, `the-prompt-budget-selects-and-still-reports-its-verdict:293` (reads `:seon.context/contributions`), `the-history-path-never-refits-a-rendered-unit:329`, `a-replay-under-a-selecting-budget-reconstructs-its-own-capture:493` |
| `test/seon/turn_loop_test.clj` | `refused-generated-opening-captures-evidence-before-close:307` asserts the refusal capture row and `with-redefs` over `prompt/prompt`: converts to the refusal-without-capture proof |
| `test/seon/render/web_debug_test.clj` | `saved-history-preserves-shown-text-with-numeric-lookups:93` transacts a capture row and calls `web/derive-context!`, `walk/history`; convert, do not delete |

**Tests that survive and must be re-pointed (they use `prompt/prompt` or `acquire-context!` as a way to get prompt text):**

| File | Deftests |
|---|---|
| `test/seon/cluster/prompt_test.clj` | `unobserved-messages-do-not-rewrite-stored-history:122`, `identical-context-does-not-depend-on-a-retained-prompt-cache:134` (named by the plan), `later-evaluations-preserve-the-opening-history:147`, `basis-only-transactions-do-not-append-history:161`, `a-held-turn-can-render-without-a-message-trigger:176`, `the-prompt-reads-the-cluster-its-caller-names:420`, `provider-calibration-reports-over-budget-without-refusing:435` |
| calibration (keep, depends on the open question above) | `calibration-uses-the-agents-recent-attempts-and-config-prior:382`, `an-unread-usage-history-is-not-an-empty-one:546`; helper `recorded-usage-tx:354` writes capture rows |
| `test/seon/render/web_context_test.clj` | `context-and-page-do-not-demand-the-render-proc:18` (counts `walk/history` and `acquire-context!` calls), `the-context-view-is-computed-only-when-opened:185` (counts `prompt/prompt` through `?sent=true`), `an-agent-whose-prompt-refuses-still-shows-the-refusal-in-its-context-view:211`, `a-context-view-string-cut-links-to-its-next-page-with-the-basis-and-text-answers-plain:223` |
| `test/seon/render/web_debug_test.clj` | `turn-details-use-the-loop-opening-and-exact-segments:32` |
| `test/seon/render/retained_test.clj` | `adoption-of-an-unrelated-namespace-re-renders-zero-evaluations:137` |
| `test/seon/render/call_test.clj` | `declared-producer-refusals-stop-context-rendering:8` |
| `test/seon/render/transcript_test.clj` | the `walk/history` call at `:1217` and `selected-evaluations-project-only-their-stored-source-and-result:967` (compaction, not capture: survives) |
| `test/seon/loop_proof_test.clj` | long test at `:303` (`render/acquire-context!` at `:362,:583` and `prompt` text equality) |
| `test/seon/cluster/agent_test.clj` | `prompt-refusal-closes-without-answering-and-stops-at-the-agent-bound:596` and the long tests at `:843,:923,:1020` (`with-redefs` of `prompt/prompt`: outside the testing rules, already a defect to convert) |
| `test/seon/context_test.clj` | `message-custody-accepts-an-absent-run-when-instrumented:20` (survives; `message-custody` stays) |
| `test/seon/context_selection_test.clj` | all four deftests exercise `selection`/`append-tx`/`compact-tx`: not capture, not in S4-07's range |

**Walk tests that stay (page/value/related/graph code is not deleted):** `walk_test` (4 deftests), `walk_graph_test`,
`related_test`, `root_pull_test`, `render/history_test` (`form-is-the-third-output…:26`, `a-neighbour-the-walk-reached…:131`),
`entity_pairs_test`, `value_test` cut-continuation tests, `render_coverage_test`, `render_simplification_test`,
`schedule_test:159`.

## 6. Exact deletion set, with preconditions

If Q4 is closed as "keep HTML over saved exchanges", S4-07 deletes after S4-04/S4-06 land:
`prompt.clj` `history-contributions`, `unit-separator`, `priced-unit`, `retained-count`, `dropped-elision`,
`selection-segments`, `compose`, `selection-of`, `select`, `capture-mismatch`, and `acquire-context-report`'s
selection/capture body; `context.clj` `capture-ai`, `capture-html`, `contribution-entity`, `capture-tx`, and
`contribution-hash` (once `prompt.clj:197` is gone); the `turn.clj:4559-4580` capture transaction and the `:65-66`
delay; `render.clj:1721` `acquire-context!` and `:49-50`; `web.clj` `derive-context!` history arm, `history-segments`,
`root-call`, `::ai-calls`; `walk/history`; schemas `seon.context.capture.edn`, the capture-component use of
`seon.context.contribution`, `:seon.context/contributions` on `rendered-context`.

Must stay: `repl/frame`, `message-custody`, `change-context`, `neighborhood`, `root-acquisition`, `graph`, `related`,
`with-related`, `continue`, `prompt.clj` `model-calibration` and settings resolution, `transcript.clj` `history`.

Preconditions the plan does not yet state (decisions, not designed here):
1. Where `:seon.ai.tokens/characters` lives once the capture is gone (see section 3).
2. Which entrance the `?sent=true` disclosure uses (section 4, options (a) and (b)).
3. `context-action-response` should stop routing through `acquire-context!`; a one-line conversion that belongs in S4-07 or earlier.

## 7. Design check

The plan's S4-07 range matches HEAD, and one correction: `render.clj:1721` is the router, not a walk entry, and `walk/history`
has a single caller. The row's "tests named by I-Q4" is satisfied by section 5. Two plan gaps found (calibration character
count; `?sent=true`); neither is addressed by a design here. Not run: nothing in the JVM. No errors, timings or faults
were produced by this inventory. Existing task coverage for the gaps: none found (`rg` in `resources/seon/tasks/seon.cluster.prompt.edn`
names only two unrelated must-reads); the root should add the calibration gap to Q4/S4-07.
