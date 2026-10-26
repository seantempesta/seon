# Live baselines, 2026-10-26

The numbers each slice of the one derivation (the page and the model's context as one render of ctx) is measured against, on the same requests: deepseek-flash, max-turns 30, thinking off unless said. Data beside this note in `notes/live/`.

## Before the fixes (2026-10-25 evening, notes/live-2026-10-25.md)

- Repeats (a model turn whose calls equal the previous turn's): 39 in 1,163 model turns, 3.4 per 100 (rounds 1-9 and pipeline 1, read from the kept stores). Causes: 10 errors that named nothing to change, 9 answers folded to an earlier record's form, 14 copied calls with empty prose, 5 nil or [] answers, 1 other.
- Pipeline 1 (the five fresh requests, design → implement → merge): 2/5 merged; implement 24-31% cached against design's 58-94%; an implement run about $0.13, a design about $0.013.

## Partial ten-request run (jolt/repeat-causes at main e47fd2d44: A1 live and F4, not F3; partial-ten-2026-10-26.edn)

Stopped after 343 model turns, $0.136, before any merge. The ten requests are seon.fixtures/fit-requests and the five fresh ones.

- Designs: 7/10 green (a-summary 29 turns, a-proof-never-ran 30, a-long-name 30, a-message 16, the-help-docstring 13, a-mixed-collection 26, a-namespaced-map 27); 3 at the cap (the-transport-failure, a-map-first-entry, a-short-history).
- Implement: 84-92% cached; the first implement turn's prefix served (e.g. 23,424 of 25,968 tokens); a run $0.001-0.016.
- Design tokens per turn: about 17-21k by turn 30; design cache 87-94%.
- Repeats with the rule deleted: 24 in 343 turns, all one run (a-short-history's design, turns 5-28, an identical reply and call each turn); the other 14 runs none in 319.

## The short-history design, thinking off and on (main cff9d6b9f; short-history-thinking-*.edn)

Three copies each (-a -b -c), designs only.

| Arm | Green | Turns | USD each | Cached | Tokens a turn | Repeats |
|---|---|---|---|---|---|---|
| thinking off | 0/3 (cap) | 30 | 0.010-0.014 | 94% | 17-21k | 0 |
| thinking on (max-tokens 65,536, reasoning replayed) | 3/3 | 28 | 0.026-0.049 | 88-92% | 25-39k | 0 |

The loop of the partial run did not recur in three runs; its cause there was the screen: once the full screen fell under the history cut, the diffs said `:unchanged [[:proofs] [:refused]]`, so the open reason (no proof registered) was nowhere in the request (root's rule for agent view: unchanged only for what is still readable above, the task's open reasons always whole). Thinking separates the second cause: the same design, which thinking off never finished, went green each time with thinking on, at about 3x the dollars.
