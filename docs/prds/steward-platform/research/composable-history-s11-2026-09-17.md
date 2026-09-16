---
type: research
status: review
created: 2026-09-17
tags: [render, prompt, history, print, landing-note]
---

# S11 landing note — composable history: select whole units, delete the re-fit

Implementation of
[program-facts-are-the-runtime PRD §1e F3 / §4 S11](../plan/program-facts-are-the-runtime-prd-2026-09-17.md),
research Option A
([composable-history-2026-09-17.md](composable-history-2026-09-17.md)).

## 1. What was read end to end, before any edit

| document / source | why |
|---|---|
| [`docs/prds/steward-platform/plan/program-facts-are-the-runtime-prd-2026-09-17.md`](../plan/program-facts-are-the-runtime-prd-2026-09-17.md) §1e F3, §4 S11, §4b | the assignment and the lane rules |
| [`docs/prds/steward-platform/research/composable-history-2026-09-17.md`](composable-history-2026-09-17.md) (all 506 lines) | the seam evidence, the measured Juniper numbers, Option A |
| `AGENTS.md` §0–§5, §7, and §2.4 in particular | the one clipping spot, elision values, shown text |
| `tmp/orchestrator/wave2/repl-rule.txt` (all 17 lines) | the in-process run rules |
| `src/seon/cluster/prompt.clj` (243 lines) | the caller being changed |
| `src/seon/render/walk.clj:891-928` (`history`) | the unit producer |
| `src/seon/render/web.clj:2381-2481` (`history-segments`, `derive-context!`) | where units become segments and text |
| `src/seon/render/transcript.clj:412-473` (`floor-text`, `bounded-scalar`, `rendered-family`, `message-text`) | the re-fit being deleted |
| `src/seon/repl.clj:200-250, 400-474` (`response`, `input-text`, `text`, `render-ai`, `render-html`) | the REPL grammar and the HTML pair |
| `src/seon/print.cljc:442-500, 960-1060, 1212-1237` (`requery-form`, `render-elision-ai`, `elision`, `elision-node`, `fit-text`) | the elision constructor and the cut being avoided |
| `src/seon/render.clj:1530-1583` (`captured-history`, `acquire-context!`) | the basis and the capture check |
| `src/seon/ai/tokens.cljc:130-210` (`estimate`, `estimate-of-characters`, `budget-report`) | the pricing and the verdict |
| `resources/seon/schemas/seon.print.edn`, `seon.render.edn`, `seon.render.walk.edn`, `seon.cluster.prompt.edn`, `seon.ai.tokens.edn` | the declared shapes the change had to fit |
| `test/seon/cluster/prompt_test.clj`, `test/seon/repl_test.clj`, `test/seon/render/transcript_test.clj`, `test/seon/render/transcript_run_test.clj`, `test/seon/render/web_debug_test.clj`, `test/seon/concurrency_independence_test.clj:460-507` | the regressions and the batch-70 assertion class |
| `src/seon/test.clj:180-420` (`destroyers`, `destructive-reach`, `host`, `run`) | read second, to establish that the in-process refusal in §5 is correct behaviour and not something to work around |

## 2. The diff

Owned paths, all of them mine alone at HEAD:

| path | change |
|---|---|
| `src/seon/cluster/prompt.clj` | added `select` (public, pure) and `compose` (public, pure) plus private `priced-unit`, `retained-count`, `dropped-elision`, `selection-of`, `selection-segments`; `acquire-context-report` composes a SELECTION instead of re-joining `:seon.cluster.prompt/text`, and refuses by name when acquisition hands it text with no units |
| `src/seon/render/transcript.clj` | **deleted** `bounded-scalar`; `rendered-family` returns the declared renderer's bytes unchanged; `message-text` hands the message its own content. `floor-text` kept for genuinely un-rendered values (a refusal map, the `extra` map) |
| `src/seon/repl.clj` | added private `thinking-text`; `input-text` gained a two-argument arity that omits the comment; `render-html` emits `[:div {:class "seon-eval-thinking"} …]` as its own block. The AI grammar (`text`) is byte-for-byte unchanged |
| `resources/seon/schemas/seon.render.history.edn` | NEW: `:seon.render.history/bytes`, `/unit`, `/priced-unit`, `/units`, `/selection` |
| `resources/seon/schemas/seon.print.edn` | `:seon.print/elision-unit` enum accretes `:evaluations` (widening an enum is accretion) |
| `resources/seon/schemas/seon.ai.tokens.edn` | declares `:seon.ai.tokens/estimate` (already emitted by `seon.print/render-elision-ai`, never declared) |
| `test/seon/cluster/prompt_test.clj` | four new regressions; the stale `prompt-budget-is-informational-and-does-not-compact` replaced by `the-prompt-budget-selects-and-still-reports-its-verdict`; `the-history-path-never-refits-a-rendered-unit` REWRITTEN from a reach assertion to a behavioural one (§4 says why the reach form is not expressible) |
| `test/seon/repl_test.clj` | `the-comment-is-its-own-thinking-block-in-html` |
| `test/seon/render/web_debug_test.clj` | `the-agent-page-shows-a-comment-as-thinking-and-a-result-through-its-pair` — acceptance (c) asserted on the PAGE path, where `render/render-call` SELECTS the evaluation schema's declared pair rather than the test calling `seon.repl/render-html` directly |

Nothing changed in `src/seon/render/walk.clj` or `src/seon/render/web.clj`: the
walk already emits one unit per evaluation and `derive-context!` already
publishes them as `:seon.render.history/entries`. The estimate is derived in
`select`, at composition, so the shared render cache never holds a number that
the model calibration can make stale (research §3.2's "derive it on the unit at
render time; do not store it").

### Two design decisions worth the orchestrator's eye

1. **"The oldest surviving ordinal" is `:seon.render.data/next-offset`.** The
   dropped units are the oldest, so the retained history resumes at that index
   of the sequence the requery form returns
   (`(seon.print/value-at (seon.eval/of-agent (seon.db/db) "juniper") [])`).
   That is a declared field `seon.print/render-elision-ai` already prints; a
   bare `:seon.cluster.eval/ordinal` key would have ridden the node silently
   and never reached the agent.
2. **The budget bounds the COMPOSED result, elision included.** Naming the
   omission costs bytes, so `select` re-judges its first count against
   `(compose selection)` and releases one more unit until it fits — or until
   only the newest unit is left, because a cut never omits its whole subject
   (`seon.print/fit-text` states the same law for characters).

## 3. Measured, before and after, on `default` (pid 53320), agent `juniper`

All through `mcp__seon__eval_clj`, jvm mode, explicit custody
(`(seon.operator/connection "default")`), projection bound from the live SCI
context. No transaction.

| measurement | before | after |
|---|---|---|
| history units (`:seon.render.history/entries`) | 16 | 16 |
| unit characters, in order | `[3275 388 2116 662 313 331 424 355 780 649 134 299 304 186 159 207]` | unchanged |
| acquired join (`:seon.cluster.prompt/text`) | 10,612 chars | 10,612 chars |
| `compose` of the selection at the shipped 1,000,000-token budget | — | 10,612 chars, **byte-identical** to the join |
| whole prompt (`prompt/prompt`, history + frame) | 10,634 chars | 10,634 chars, **byte-identical** |
| `budget-report` | `{:estimated 3323 :verdict :within :chars-per-token 3.2 :basis :shipped-prior :budget 1000000}` | identical |
| contributions | 17, decomposition exact | 17, decomposition exact |
| per-unit `:seon.ai.tokens/estimate` (derived, never stored) | absent | `[1032 122 667 208 98 104 133 111 245 204 42 94 95 58 50 65]` |

Selection under a budget smaller than the history, same 16 real units,
calibration 3.2 chars/token:

| budget (tokens) | units kept | elision | composed estimate |
|---|---|---|---|
| 1,000,000 | 16 | none | 3,317 |
| 300 | 3 (the newest) | `omitted 13`, `next-offset 13`, `total 16`, `elision-unit :evaluations`, `bound-by :seon.config.ai/prompt-token-budget`, requery form present | 272 — within |
| 1 | 1 (the newest) | `omitted 15` | over, and honestly so: the newest unit is never dropped |

Composed text at budget 300 contains no `:seon.print/prefix` (fit-text's
signature field), exactly one `:seon.print/elision-unit :evaluations`, and each
retained unit appears whole.

The transcript path, same JVM, same agent:

| measurement | before | after |
|---|---|---|
| `transcript/render-ai` characters | (re-fit path) | 10,990 (re-measured 2026-09-16 20:18Z) |
| `:seon.print/prefix` in that output | the batch-70 signature | **absent** |
| `:seon.print/omitted` occurrences | — | 0 |

Rendering that transcript also logs, three times, an unrelated smell from
Datahike — `Expected number or lookup ref for entity id, got "65f46c0efa7f"`
(`:error :entity-id/syntax`) — i.e. somewhere on the transcript path an id
STRING is handed where an entity id belongs. It predates this slice, it is
outside these owned paths, and it is reported to the orchestrator rather than
chased here.

The evaluation entity's HTML pair, on a real Juniper evaluation carrying
`:seon.cluster.eval/comment ";; I should understand how this REPL works before I act."`:

```clojure
[:article {:class "seon-family-entry seon-eval-entry"}
 [:div {:class "seon-eval-thinking"} ";; I should understand how this REPL works before I act."]
 [:pre [:code {:class "seon-eval-prompt"} "my.agents.juniper=> (help)"]]
 [:small {:class "seon-eval-renderer"} "AI: seon.bootstrap/render-help-ai · HTML: seon.repl/render-html · saved text; live value unavailable"]
 [:ul {:class "seon-eval-lines"} …]]
```

## 4. Acceptance (d) — and why it is NOT expressible as reach

Transitive reach on `default`'s published program graph, measured 2026-09-16
20:05Z through `mcp__seon__eval_clj` (jvm mode, explicit custody). Note
`:seon.fn/sym` currently holds STRINGS on this cluster (the symbols-everywhere
lane's in-flight state), so the query binds strings.

| start | functions reached | reaches `seon.print/fit-text`? |
|---|---|---|
| `seon.cluster.prompt/acquire-context-report` | 244 | **no** |
| `seon.render.transcript/rendered-family` | 1,008 | **yes** |

**Correction to this note's first draft, and to the shape of acceptance (d).**
The first draft recorded `rendered-family`'s reach as "yes before; the re-fit is
what made it so" and shipped a regression asserting it is now "no". That is
false, and the measurement above is the falsification: `rendered-family` calls
`seon.render/render-call`, and the program graph records an edge from
`render-call` to EVERY declared AI producer — including
`seon.render.value/render-ai`, which owns the one legitimate clipping spot. So
`fit-text` is reachable from any render call BY CONSTRUCTION, and a reach
assertion would either be vacuous (an empty fixture graph, absence read as
health) or forbid the one cut that is supposed to happen.

Two further readings of the same measurement:

- `acquire-context-report`'s 244-function reach does **not** contain
  `seon.render.walk/history` either: `render/acquire-context!` reaches
  `render.web/derive-context!` dynamically, so the static edge does not exist.
  The first draft's positive guard for the reach test would have failed too.
- The published graph still carries the deleted `bounded-scalar` as a callee of
  `rendered-family`, because publication on this cluster is stale — see §5.

What (d) actually forbids is a SECOND fit of a string a renderer already
produced, and that is OBSERVABLE: a character cut leaves `fit-text`'s own
fields in the bytes. `the-history-path-never-refits-a-rendered-unit`
(`test/seon/cluster/prompt_test.clj`) was rewritten to assert exactly that — a
4,000-character rendered unit crosses composition whole under a budget of 1,
with no `:seon.print/prefix` and no `:seon.print/elision-unit :characters`. The
same signature is asserted on real data in §3 and by the concurrency payload
assertions the cold gate runs.

## 5. In-process runs on `default` — BLOCKED, and not by this slice

**No in-process `seon.test/run` was possible.** Every attempt is refused before
executing anything:

```
#:seon.error{:kind :seon.test/unknown,
 :message "No function in this program declares :seon.fn/destroys, so an
  in-process run cannot tell whether a test deletes a filesystem path it did
  not create. Republish the program (bin/seon init --dev default), or declare
  the attribute in the owner's own metadata at its definition."}
```

That refusal is correct behaviour (`seon.test/destroyers`,
`src/seon/test.clj:188-218`: a program in which nothing declares the attribute
is the typed unknown, never an empty set). The cause is upstream:
`src/seon/operator.clj:311` declares `:seon.fn/destroys` and the indexer reads
it (`src/seon/fn.clj:592`, `:688`), but `default`'s published program carries
**0** rows for it, and the remedy the refusal names is itself refused:

```
bin/seon init --dev default
● current-src: program population compiled: 39458 entities, 19504 identities
:datahike/write-rejected {:kind :transaction/validation-rejected}
✗ Program indexing transaction was refused.
seon.db/transact! refused transaction data at
  [4501 :seon.fn/keywords #{:seon.agent/id :seon.db/db}]:
  expected a set, got a keyword.  Entity: #:seon.fn{:sym "my.agent/identity"}
```

A cardinality-many attribute validated one expanded member at a time against
its whole-value schema. The whole transaction aborts, so nothing was written
and `default` kept its program — no harm, no reset. Filed as
[complete-program-publication-is-refused-on-a-cardinality-many-set](../../../seon/issues/complete-program-publication-is-refused-on-a-cardinality-many-set.md);
it belongs to the write-admission (F2) work, not to S11, and it blocks the
in-process loop for EVERY lane, not just this one. Bypassing the destructive
check to run tests anyway is explicitly forbidden by the repl rule, so it was
not done.

What was proven in process instead, all on the live `default` JVM (pid 53320)
with the cluster's own SCI context:

| proof | how |
|---|---|
| `select` / `compose` on 16 REAL Juniper units | `render/acquire-context!` with the cluster instance's `:seon.sci.eval/ctx`, then both functions — §3's numbers |
| the whole prompt end to end | `seon.cluster.prompt/prompt` on the same request: 10,634 chars, `budget-report` `:within` |
| acceptance (b) on production data | acquired join vs `compose` of the unlimited selection: `=` is **true** |
| acceptance (a) shape on production data | budget 300 → 3 newest whole units, one elision, no `:seon.print/prefix`; kept units `=` `(take-last 3 units)` |
| acceptance (c) through the declared pair | `render/render-call` `:seon.render/html` and `:seon.render/ai` on a real Juniper evaluation carrying a comment — the hiccup in §3 |
| acceptance (d) | the reach measurement above, and the whole-unit composition |

## 6. Boundary, honestly

- Everything in §3 and §4 is an IN-PROCESS proof on the live `default` JVM,
  function-level and acquisition-level. **No `seon.test/run` executed** (§5).
  The cold gate is the proof of record and is requested in
  `tmp/orchestrator/gate-requests/composable-history.txt`; it is the FIRST
  execution of these regressions.
- **`seon.render/captured-history` now compares a selected capture against an
  unselected join.** It is inert at the shipped 1,000,000-token budget (nothing
  is ever dropped) and arms itself the moment the budget is lowered.
  `src/seon/render.clj` was outside this lane's owned paths, so the defect is
  filed rather than fixed:
  [a-selected-prompt-no-longer-reconstructs-from-the-full-join](../../../seon/issues/a-selected-prompt-no-longer-reconstructs-from-the-full-join.md).
- No RESET NEEDED. No stored fact changed: the two schema resources accrete
  (an enum value, a declared key) and the new resource declares value shapes
  only — no attribute, no identity, no migration.
- Option B (turn-granular selection) was NOT implemented, per research §3.4: it
  is a later argument to `select`, not a different design.
- F4's `my.message/reply` and the per-turn reply status are not in S11.
