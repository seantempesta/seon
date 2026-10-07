---
title: printer-fix, fix forward of a423b8b29
type: research
status: landed
lane: printer-fix
---

# printer-fix

Fixes forward the landed printer slice a423b8b29 (+13b850d30), per
[Opus review](../printer-code/review-diff-opus-notes.md) and [Astra review](../printer-code/review-diff-astra-notes.md).
Which loop step it serves: print (the value printer and the reply composer). What it deletes:
`render-prepared`, a whole-hiccup output validation per AI/HTML render, a binding frame per printed
symbol, and the reply composer's `pr-str`s.

## Broken or not done

1. **JVM-mode `*print-length*` is not in this commit.** The show stages that build the print request
   live in `seon.turn/show-row` and `seon.turn.batch/jvm-show`, held by lane s2b1-code. The patch is
   [entrant.patch](entrant.patch) (against HEAD ed0ba47ef; it also carries the one-line
   `turn_test.clj:791` golden for the new reply order). Proven in the probe JVM with the patch loaded:
   after `(set! *print-length* 2)` in MCP JVM mode, `(vec (range 12))` → `[0 1 …10 (drop 2 result/…)]`,
   the map and set show 2, `(range 40)` → `(0 1 …38 …)`. Without it JVM mode still prints whole.
   **Until s2b1 applies it, `seon.turn-test/evaluation-ai-is-only-the-repl-session` is red** (its golden
   expects `:value` first).
2. **Dig form for render text of a value that cannot carry metadata: not done** (design question).
   `seon.print/full` marks by metadata; a Var (the only non-IObj value a declared render reaches) cannot
   carry it. No shipped renderer returns text for a Var (the Var render returns data), so the case is
   unreachable today; the cut keeps its requery refusal. Options: (a) `full` returns a marked box the
   printer unwraps at the root (changes what the handle holds); (b) a cut's dig form re-renders through a
   public render entry with the full opt-in passed as a unit option (needs a `my.*` entry); (c) leave it
   until a renderer returns text for a Var. Recommend (c). Recorded in task
   `seon.render.value/render-text-past-the-budget-is-cut-mid-line-with-no-dig-form`.
3. `seon.repl-grammar-test/stored-help-keeps-its-renderer-and-bare-response` is red on HEAD (6 fails,
   same on HEAD and candidate); `seon.turn-test/a-definition-changed-since-the-run-read-it-is-refused-at-the-writer`
   and `.../a-form-whose-facts-are-refused-says-it-was-not-admitted` error on HEAD and candidate;
   `seon.turn-test/transitions-agree-with-the-model` failed on HEAD only (flaky). Not caused here.
4. The composed map's armed selection still allocates 8.4 MB (AI) / 9.4 MB (HTML): under the 10 MB line,
   but the per-admission `settle` (node maps re-assoc'd along the chain, ~1 MB) and ~10,000 armed calls to
   tiny private helpers (`more?` 2,678, `extras` 1,323, `flat-width` 902, `nest` 818; ~3 MB of wrapper
   allocation) remain. Task `seon.render.value/printing-thirty-two-numbers-allocates-megabytes` stays open.
5. `seon.sci.eval/shown-result` still falls back to the fork's `::print-session` place when the request
   carries no options; with the entrant patch every entrant passes the evaluation's own options and the
   fallback can be deleted.

## Fixes and their regressions

| # | Fix | Regression (data) |
|---|---|---|
| 1 | The core prints through `project`, whose contract is the new `:seon.render.value/unit` (no database declared, so none is supplied or required); `prepare` keeps `:seon.render/unit` for supplied callers. `node-id` and `declared` take the same unit (they read a database only when present). | `value-test/a-diagnostic-printed-off-custody-shows-its-path-and-message` (bare thread: `[:a 1]` and `boom here` shown) |
| 2 | Session `:length`/`:level` are hard bounds in the selection: `admit-member` refuses past them (so `complete-tail` cannot pass them), a tuple is admitted whole only inside them, `(max 1 …)` deleted so 0 shows none, the cut names `:seon.print/length`/`:seon.print/level`. | `value-test/a-session-print-bound-is-hard` (table: vector, map, set, 3-tuple at 2; zero; level 0; members and `bound-by` from the tree); `value-test/a-session-print-length-bounds-the-shown-members` now prints with a result handle and covers vector, tuple, map, set and zero |
| 3 | Reply order: composer keys first (`:findings :interrupted :result :out :note :ns :ms :calls`), then spliced printer text (`:repeated :errors :diagnostics :value :error`). Task intent corrected; `:seon.repl/response` schema order matches. | `repl-test/the-reply-is-one-edn-map-whatever-the-value` (`Steps: 1) open it\n2) say "hi`: everything before `:value` reads back as `{:result … :ms 12}`) |
| 4 | Comment-ending check only on spliced keys (`spliced` set). `#!` not added: a second scan cost 37% on the 109 KB dense reply. | same test, "only spliced printer text is ended before the next key" |
| 5 | `response-entries` contract `[:=> [:cat :seon.repl/emission] [:vector [:tuple :qualified-keyword :string]]]`. | contract compile in the check |
| 6 | Render text (`:raw?`) cuts at its last line end anywhere in the shown prefix, shrinking the chunk; `fill-string` runs even with no room left, and cannot loop. | `value-test/a-render-text-…-at-a-line-end-…`: rows `:sparse-lines` (10×1000) and `:short-first-line` |
| 7 | `:findings` and `:interrupted` through `core-text`, `:out` and `:note` through `print/string-literal` (line breaks kept), the `;; full value:` form through `print/form-text`. `printed-copies` task inventory corrected (39 → 35). | goldens updated (`repl-test`, `transcript-test`, `drive-test`, `web-debug-test`) |
| 8 | Composed map over 10 MB: the clear cause was `seon.print/literal` (a 5-Var `binding` frame per printed symbol, called by `form-text` for every cut's dig form at every `settle`: 4.1 MB per AI render); it now binds only when the caller's print Vars differ (EQUIVALENT). `hiccup?`'s walk tested `raw?` through the armed Var per node (3,451 armed calls); it uses `instance? Raw`. `render-prepared` deleted: `render-ai`/`render-html` read the projection's key instead of a second whole-projection validation. | covered by the printer and hiccup tests; numbers below |

## Numbers

Throwaway operator root (`bin/seon --root <scratchpad>/printer-fix/root start probe`, HEAD ed0ba47ef),
HEAD and changed files load-filed alternately, re-armed with `seon.instrument/apply!` (2,593 Vars) against
the packaged projection, one platform thread, batch sink, 20 warmups, median of 5. Saved composed/context
inputs from the printer-code scratchpad. Default's JVM ran no prototype code.

| Probe | HEAD ms / bytes / calls | change ms / bytes / calls |
|---|---|---|
| composed map AI | 7.67 / 11,892,832 / 9,616 | 5.76 / 8,380,552 / 8,489 |
| composed map HTML (+serialize) | 8.71 / 10,505,376 / 14,857 | 6.10 / 9,415,320 / 11,213 |
| context string AI | 1.75 / 894,824 / 278 | 1.04 / 719,008 / 262 |
| context string HTML | 1.38 / 507,400 / 264 | 1.16 / 501,144 / 235 |
| `(help)` reply | 0.025 / 43,208 / 14 | 0.010 / 35,784 / 12 |
| 109 KB EDN reply | 0.20 / 2,185,112 / 13 | 0.10 / 1,749,216 / 12 |
| 109 KB dense reply | 0.22 / 1,095,912 / 13 | 0.14 / 877,840 / 12 |
| prepare 140,000-char string | 0.71 / 818,296 / 257 | 0.59 / 702,080 / 245 |
| `(vec (range 12))`, length 2, handle | 1.60 / 716,784 / 707 (whole) | 0.15 / 153,856 / 168 |
| core-text `(vec (range 100))`, bare thread | 3.39 / 424,496 / 8 (refused, last resort) | 0.91 / 1,049,864 / 1,325 (selection) |
| core-text of a diagnostic, bare thread | 0.93 / 339,416 / 8 (path and message lost) | 0.70 / 862,848 / 980 |

The core-text rows cost more because they now do the work they were refused before. Output sizes
(estimated tokens, 3.2 chars/token): composed map HTML ~8,365; context AI ~1,484; dense reply ~34,043.
MCP SCI pass of the 5-form print-length probe in the probe root: 125 ms. The 100-form MCP pass was not
remeasured. Commits: 0; store bytes: probe root only.

Tests: `bin/seon check` on the candidate with seon.render.value-test, seon.repl-test,
seon.render.transcript-test, seon.eval.drive-test, seon.render.web-debug-test, seon.print-test,
seon.render.hiccup-test, seon.returned-error-test, seon.render.floor-test: green apart from the HEAD-red
repl-grammar test (1,092 pass / 6 fail, all in that test). seon.pass-cost-test, seon.mcp-entrance-test,
seon.loop-proof-test, seon.sci.reader-test, seon.context-test green; seon.turn-test: the golden in item 1
plus the HEAD errors.

## Design check

- The design held for 1, 3, 4, 6 and 7. For 1 the first cut (relaxing only `render-ai`'s caller) was
  wrong: `node-id` and `declared` also demanded the database through their contracts; both read it only
  when present, so they take the database-free unit.
- 2 held at the printer; the "evaluation's own options" half needs the entrant files (s2b1's), so it
  ships as a patch rather than through the shared fork place.
- Adding a contract to `render-prepared` added a whole hiccup validation per render (+500 armed calls,
  +290 KB); deleting the function instead was simpler and removed one more validation than HEAD had.
- Awkward: armed private helpers in the selection's inner loop cost ~3 MB per large render; that is the
  contract system's per-call cost, not this slice's to change.

No restart or reset needed for this lane's evidence; root's batched restart adopts the commit.
