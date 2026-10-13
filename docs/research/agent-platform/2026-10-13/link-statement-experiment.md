# Link-statement experiment: matching a plain statement's words to references

Lane `link-statement`, 2026-10-13. Agent `linker`, SCI mode, no source changes. Tools used and nothing else:
`my.program/search`, `seon.ref/references`, `docs/seon/architecture/vocabulary.md`, `seon.ai.tokens/estimate`.
Caveat: I read the task intent for statement 1 before writing its plain version, so its result is an upper bound.
Token counts are `seon.ai.tokens/estimate` over `pr-str` of the resolved vector, a proxy used only inside this experiment.

## Result

| statement | refs before | refs after | problems | resolved tokens (before / after) | `seon.ref/references` ms (before / after) |
|---|---|---|---|---|---|
| 1 send inside a do form | 0 | 5 (+24 nested) | 0 | 0 / 3,492 | 1 / 4 |
| 2 stale bodies after a merge | 0 | 4 (+31 nested) | 0 | 0 / 4,912 | 1 / 6 |
| 3 context too big, irrelevant tests | 0 | 4 (+23 nested) | 0 | 0 / 4,813 | 0 / 5 |

The statement text itself grows from 49 to 109, 25 to 60, 21 to 61 tokens. Resolving costs 1-6 ms; the search is the cost
(about 100 ms and 50 MB allocated per `my.program/search`, 1.0-1.2 s for a batch of ten).

## Statement 1 (the plain version of `seon.turn/a-send-inside-a-do-form-leaves-a-message`)

Before: "A message sent from inside a do form gets dropped: the turn still finishes but no message is recorded. Find where
the send is staged and fix whatever drops it."

Content words and searches (count of hits, top matches):

- "message/send" 59: `seon.cluster.message/send-value`, `my.message/send`, `seon.cluster.message/send`. The qualified form found the API.
- "send-call" 5: `seon.cluster.message/send-call` first, `seon.cluster.message/send!` third. Exact and good.
- ":seon.message/id" (keyword) 191: top five are tests and a render (`seon.data-shapes-test/message-runtime-probe`); the schema key itself is never offered, only its users.
- "message-tx" 13: `seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes` is 5th; this names the failing test.
- "a-turn-delivers" 1: the test, exact.
- "message" 1523 and "send" 184: noise (`seon.cluster.message/delivery`, `seon.ai/send-request`).
- "stage" 237 / "staged" 94: noise (`seon.blob/stage-binary!`, `seon.effect/staged-result`); the word the statement means (send staging) is not found by it.
- "recorded" 453: noise (`seon.dev.dependency-digest/recorded-dependency-pins`).
- "do form", "eval path", "effect of its evaluation": 0 hits. These are prose, not names.

After: "A message sent with `my.message/send` from inside a `do` form gets dropped: the turn still finishes
(`seon.cluster.turn-test/a-turn-delivers-what-a-form-asks-to-send-and-still-finishes`) but no `:seon.message/id` fact is
recorded. Find where `seon.cluster.message/send-call` and `seon.cluster.message/send!` stage the send and fix whatever
drops it."

References found: 5, problems 0, 3,492 tokens, 4 ms. This is exactly the reference set of the real intent (same 3,492
tokens). The failing test is 1,732 of them (12 nested refs); `seon.cluster.message/send!` is 930.

## Statement 2: "agents sometimes see stale function bodies after a merge until the cluster reloads"

- "stale" 136: `seon.sci.eval/stale-reach`, `seon.test/stale`, `seon.problems/stale-var-ai`. Relevant-looking but about test staleness and absent Vars, not stale bodies; none is certain.
- "function body" 4: only tests. "after a merge", "cluster reload": 0.
- "merge!" 55: `seon.cluster.source/accept-merge!`, `my.root/merge!`, `seon.merge/merge!`. Good; the owner words are right.
- "reload" 87: `seon.cluster/reload-identities`, `seon.cluster/declaration-reload-rule`, `seon.cluster/reload-order`. Good.
- "program-context" 38 (a guess from vocabulary: "program context ... keyed by program content"): `seon.sci.eval/program-context` first. This is the key hit and came from the vocabulary table, not from the statement's words.
- "source" 2212 and "agents" 373: noise.

After: "Agents sometimes see stale function bodies (`seon.sci.eval/program-context`) after a merge (`seon.merge/merge!`,
`my.root/merge!`) until the cluster reloads (`seon.cluster/reload-identities`)."

References 4, problems 0, 4,912 tokens, 6 ms. Doubtful: whether `reload-identities` (private, `defn-`) is the right anchor; I chose it for the word, not for evidence.

## Statement 3: "the context an agent sees is too big and has irrelevant tests in it"

- "context" 991: top is `seon.sci.eval/regenerate-agent-context!`, then `seon.context/step-section`. Too broad.
- "current-state" 25: `seon.context/current-state`, `current-state-ai`, tests. Good, and it came from the vocabulary word "current-state block".
- "seon.context/current-state" 16: ranks the function first, as wanted.
- "render-situation" 2, "assemble" 15 (`seon.context/model-context` fifth), "tokens" 158 (`seon.ai.tokens/prior-calibration` first, a wrong lead), "budget" 149 (printer budgets).
- "tests-reaching" 35: `my.program/tests-reaching`, `seon.fn/tests-reaching`. Good; how tests enter a context is `seon.context/step-section`, found by reading docs of `seon.context/task-sections`, not by search.
- "irrelevant" 2, "too big" 1: nothing relevant.

After: "The context an agent sees (`seon.context/model-context`, its `seon.context/current-state` block) is too big and has
irrelevant tests in it (`seon.context/step-section`, `my.program/tests-reaching`)."

References 4, problems 0, 4,813 tokens, 5 ms. Another sign it works: `seon.context-cost-test/one-agents-current-state-costs-only-its-attachments` appeared in the "current-state" results and would be the natural proof.

## Findings

1. A rewrite is cheap to check: `seon.ref/references` returned 0 problems on all three and took 4-6 ms. A wrong guess is caught (I confirmed an unresolved symbol and a missing `#anchor` each come back as a problem with a message). An existing but wrong symbol is not caught.
2. Search is the weak stage. Whole-word queries on common words ("message", "context", "source", "stage", "recorded") return hundreds to thousands of hits ranked by name match, and tests are mixed in with owners. Hyphenated or qualified fragments ("send-call", "current-state", "message-tx") are sharp.
3. Words with no good match: "do form", "eval path", "after a merge", "cluster reload", "function body", "irrelevant", "too big". They are prose; the thing they mean is named differently (`program-context`, `step-section`).
4. Vocabulary was the best bridge for statements 2 and 3 ("program context", "current-state block", "merge", "wake") but search does not read it; I carried the hit over by hand.
5. Resolved context is heavy: 3.5-4.9k tokens for four or five refs because each includes full source plus nested references (a test is 1,732 tokens alone). A linker that adds five refs adds roughly 3,000 tokens per statement.
6. The first-stage agent would need, to do this well: (a) vocabulary hits as candidates (the word list indexed by term and synonym); (b) ranking by recorded use (callers, tests reaching, recently evaluated, tasks referencing) instead of name-match plus hit count; (c) search over schema keys that returns the key and its producer, not its users (`:seon.message/id` returned tests first); (d) search that excludes or groups tests, so owners come first; (e) token cost per candidate shown before pick, so it can choose a doc or docstring over a whole function.
7. Platform observations to file: `my.program/search` costs about 100 ms and 50 MB per call, so a ten-word batch takes over a second and trips the `:slow` and `:large-allocation` findings; its result is a map `{:count :matches :more}` and returning `(first result)` raises a ClassCastException in `seon.render.value/seq-cells` at the printer (the reply still returned its value as `:count 5`, with the error recorded in `:diagnostics`); `:limit` is respected by `:matches` but `:count` reports all hits.
