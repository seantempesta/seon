---
type: notes
status: landed with the S2a commit
created: 2026-10-07
tags: [debug, deletion]
---

# S2a code notes (lane s2a-code)

Design: `design-notes.md` v5. This note records the design check, measurements and findings from the cut.

## Happy-path answer

Serves the **context** step: the debug page now only displays the turn's own context function
(`seon.cluster.prompt/prompt`) through `seon.render.value/html`, computed when the reader opens it. It adds no
mechanism; it deletes the ledger, its problem panel, the turn session, the outline, the walk graph page and
`::transcript/ledger?` from the prompt path.

## Design check

The design held against the code. Differences found while cutting:

- Line numbers had moved (s5a-reply); re-located by name. The 59 transcript Vars deleted as listed, plus `session-id`
  (its last caller was the deleted `render-session-loading`) and the toggle in `session-header`.
- Two requires beyond the design's list became orphans: `malli.core` in transcript.clj and `seon.ai.tokens` in
  web.clj. Lint confirmed.
- `error_test.clj` called `fault-problems` twice (occurrence count asserted by `pr-str`); both assertions deleted with
  the function and the task `fault-problems-counts-occurrences-in-prose` dissolved with it (removed from
  `resources/seon/tasks/seon.render.transcript.edn`).
- Task EDN fallout: `seon.render.web.edn` (`must-read` re-pointed, the cache task's intent corrected),
  `seon.render.edn` (`landing-reds-2-render-bow-outs`), `seon.instrument.edn` (a repro that named the deleted
  `ledger-effects`).
- CSS: the design's dead-class census (`deadclasses2.txt`) was a substring census and listed live classes; redone with
  exact-token matching (95 dead classes + `seon-session-toggle`, which the cut itself orphaned). Also removed
  `.seon-graph-via`, `.seon-graph-kinds*` and `.seon-graph-prompt`, which only the deleted pages and the old `[:pre]`
  used. `input.css` 2389 -> 2171 lines. `bin/css` regenerated `output.css`; it also carries the unrelated drift
  recorded in `seon.render.web/the-built-stylesheet-is-stale-against-its-source`.
- `resources/public/js/seon-graph.js` still reads `--seon-graph-level-N` (walk-model node levels); no surviving page
  declares them, so that branch of the script is dead. The script is not claimed by this slice.
- `turn-details-use-the-loop-opening-and-exact-segments` was already red on the parent (a failed "strip accounts for
  every byte" assertion and 6.8 s over the 5 s bound). Converted to the assembly assertions only and its first
  virtual-submit cycle dropped; the byte-strip assertion was the ledger's.

## Measurements (same probe, parent vs change)

Fixture (`web-test/with-server`, root with no history), thread-allocated bytes process-wide, four loops each:

| Request | Parent cold / warm | Change cold / warm |
|---|---|---|
| `/agent/root/debug` page load | 251 ms, 92 MB / 12.6-16.7 ms, 6.2 MB | 61-72 ms, 11 MB / 4.9-7.3 ms, 2.0 MB |
| `?sent=true` (datastar) | 22 ms, 8.5 MB / 9.7-11 ms, 5.2 MB | 48-56 ms, 42 MB / 9.3-13 ms, 6.6 MB (25 ms once) |

Default (root, 113+ evaluations; HEAD code, curl, `127.0.0.1:7994`): page 6.4 s first, 1.0-1.15 s warm, 158 KB;
`?sent=true` 0.51 s then 9.5 ms. The change's page does not call the assembly (armed `prompt/prompt` count 0).

The sent view got more expensive on the fixture (the composed map through the HTML face instead of a `[:pre]`):
first call 48-56 ms and 42 MB, warm about 6.6 MB. Root ruled this accepted until S4 (design §2, gaps 1 and 2).

## Findings left

- RESET NEEDED: no. Root restarts default after landing to serve the new page.
- Pre-existing reds in the named namespaces, identical on the parent (`bin/seon check` on HEAD):
  `transcript-test/the-transcript-is-whole-and-the-ai-boundary-elides-it` (a `tuple-node` refusal on
  `requery-id`), `web-context-test/{context-and-page-do-not-demand-the-render-proc,
  root-page-blocks-read-attribute-bounded, unrelated-adoption-preserves-pages-with-an-unchanged-sci-snapshot}` and
  `turn-test/{a-definition-changed-since-the-run-read-it-is-refused-at-the-writer,
  a-form-whose-facts-are-refused-says-it-was-not-admitted}`. The commit names the green vars of those namespaces.
- Runtime tasks `a9a7bb6e6f29`, `ec70b6017334` and `17390920f38d` live only in the database (no task EDN holds them);
  their producing functions are deleted. Closing them is a runtime write for root.
