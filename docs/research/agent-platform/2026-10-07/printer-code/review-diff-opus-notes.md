---
title: review of a423b8b29 (printer-code), Opus
type: research
status: review
date: 2026-10-07
lane: review-printer-diff-opus
---

# Review of a423b8b29 (printer-text v2, landed)

Method: source and diff read against design v2 and the landing note; probes run in a throwaway operator root on
HEAD a423b8b29 (`bin/seon --root <scratchpad>/review-printer-diff-opus/root start probe`, pid 49731, armed), driven
over its prepl and through `seon.mcp/call`, on platform threads. Two forms ran on default (25bb94be0, the parent)
through MCP SCI as agent `review-printer-diff-opus`, to classify pre-existing behavior. No code ran in default's JVM
except task creation.

Verdict: **keep, fix forward**. Nothing is reverted; findings 1 and 2 are the fix-forward list, and task
`1b420c6cb408` should not close on this commit.

## Findings, by severity

1. **Diagnostics go blank when there is no custody** (medium). Task
   `seon.render.value/core-text-refused-outside-custody` (`6aee37f27538`).
   - Armed, `core-text` builds a unit without `:seon.db/db`, and `:seon.render/unit` requires one. Call preparation
     only supplies it under custody (`seon.db/supplied-database-value`).
   - On a bare thread, `(seon.render.value/core-text (vec (range 100)))` gives `"[0 1 2 3 4 5 6 7 ...]"`, which is the
     last resort. Binding `seon.batch/*world*` with the connection does not help.
   - Inside an MCP evaluation the same form shows 32 members and a cut, at 1,698 armed calls.
   - This slice routes every `prepare`, `text` and `html` diagnostic through `core-text` (`diagnostics-shown`).
     Without custody, a diagnostic therefore reads
     `{:seon.render.data/path ..., :seon.render.value/failure #object[clojure.lang.ExceptionInfo 0x77b0e12d]}`, with
     neither the path nor the message.
   - The deleted `diagnostic-line` printed `throwable-value`, so it kept the message.
   - The core-text defect is pre-existing; the regression is the new route through it.
   - `web.clj` calls no custody function, so the debug view probably degrades the same way. That is unverified: I
     ran no browser or handler probe.
   - Fix at the producer: give the core a unit schema that does not require the database (AGENTS.md, absent world
     member: a different schema name).
2. **The `*print-length*` fix is partial** (medium). Task
   `seon.render.value/session-print-length-loses-to-whole-admission` (`28ee1f9b9f5f`).
   - After `(set! *print-length* 2)` in an MCP SCI pass, `(vec (range 12))`, `{:a 1 :b 2 :c 3}` and `#{1 2 3 4}`
     print whole. `(vec (range 30))` is cut at 2 and `(range 10)` is cut correctly.
   - Cause: a collection is admitted whole when its cut, with its `(drop 2 result/…)` form, would cost more than the
     members it hides. The agent's explicit bound loses to that rule.
   - The proof test reaches `(vec (range 9))` only because `support/evaluate` prints without a result handle, which
     makes the cut short.
   - Item 4, the entrant gap: in MCP **JVM mode**, `(set! *print-length* 2)` succeeds and `(range 40)` still prints
     32 members. `shown-result` reads only the SCI fork's `::print-session` place. The evaluation's own
     `:seon.print/options` value (eval.clj success and failure results) is the correct source for every entrant.
   - Test entrants whose ctx is not a `fork-for-turn` fork have the same gap: the frame's own `(atom {})` carrier is
     never visible to `shown-result`. This was not probed separately.
   - `*print-length* 0` shows one member (`(max 1 length)`), where Clojure shows none.
3. **The rewritten task intent claims an invariant that does not hold** (low-medium).
   - `seon.repl/reply-map-is-not-edn-when-elided-or-rendered` now says `:result` and `:ms` "read back after any
     :value".
   - Probe: `{:x 1 :seon.render/ai "Steps: 1) open it\n2) say \"hi"}` replies
     `#:seon.repl{:value Steps: 1) open it\n2) say "hi, :result result/…, :ms 0}`. Read as EDN, `1)` is an unmatched
     delimiter and `"hi, :result …}` is an unterminated string.
   - The parent shows the same reply for a value's own `:seon.render/ai` string (checked on default). It is new for
     declared renderers, whose text used to be quoted by `data-text`.
   - Fix options:
     - **(a)** Emit the spliced entries (`:value`, a shown `:error`, `:diagnostics`) after the composer's own keys.
       The invariant then holds by construction, with no parsing.
     - **(b)** Correct the intent so it only claims what holds.
   - The agent reads text, not EDN, so (b) is acceptable and (a) is better.
4. **Contracts** (low). `seon.repl/response-entries` changed and has no `:malli/schema`.
   - Every other function the diff adds or changes carries one-line registry contracts: `elided-hiccup`,
     `diagnostics-shown`, `unavailable`, `shown-width`, `path-url`, `tuple-node` (now `:seon.print/requery-id`),
     `ends-in-comment?`, `plain-projection`, `fill-end` and `cut-node`.
   - `session-print-options` (eval.clj) is uncontracted, but it is pre-existing and was not touched.
5. **The render-text dig form needs an IObj value** (low). Render text cut on a non-IObj value (a Var, a string, a
   number) gets no continuation, only a refusal title.
   - No shipped renderer reaches this case today: the Var render returns data (probed with a 200-binding `defn`).
6. **`ends-in-comment?`** (item 6, low). This is not a correctness defect.
   - A line comment can only cover the next key if it starts on the last line, so a `;` test there has no false
     negative for `;` comments. A false positive costs one newline.
   - Theoretical false negative: `#!` is also a line comment in Clojure's reader (`LispReader` dispatch `!`). Only raw
     render text could end in it.
   - Simplest correct rule, without a lexer: decide by source, as the composer already does for quoting. Apply the
     check only to the spliced shown-text entries. The composer's own `pr-str`'d entries (`:findings`, `:out`,
     `:note`, `:interrupted`) never end in a comment. That removes the `:findings` false positive the landing note
     admits. To also cover `#!`, test for `;` or `#!`.
7. **Pre-existing behavior seen here.** `(lazy-seq (throw (ex-info "lazy boom" {})))` replies `:value ()` beside its
   diagnostic, on both the parent and the change. The shown text claims an empty list. Filed as
   `seon.render.value/throwing-lazy-seq-shows-as-empty` (`157b2e08e835`).

## Questions 1-8

1. **One printer: supported.**
   - The diff adds no new `pr-str`, `prn` or `println`. The one `pr-str` in a `+` line is the re-indented
     pre-existing `path-url` encoding of a path into a URL.
   - `data-text`, `one-form?`, `diagnostic-line`, `unavailable-line` and `kept`/`:ceiling` are gone.
   - The diagnostics terminal is acyclic. `diagnostics-shown` → `core-text` → `render-ai` runs with `structural?`,
     where `declared` returns nil, so it produces no failed nodes and no further diagnostics. A structural throw ends
     in `plain-projection`, which uses `bounded-text` only, and `core-text`'s own last resort is
     `bounded-text`/`opaque`. Its legibility off custody is finding 1.
2. **Splice: supported for quoting, contradicted for "keys read back".**
   - `:value`, a shown `:error` and `:diagnostics` are spliced as shown. A prose `:seon.cluster.eval/error` is quoted
     with `print/string-literal`.
   - No key is dropped from the entries: `when-not failed?` plus `when-some` gives the same presence as before.
   - The reply's keys stop being data after prose with unbalanced delimiters (finding 3).
3. **Render-text cut: supported.**
   - A 400-line, 21,889-char `:seon.render/ai` text was cut after line 87 at a line end: `…+17147` on its own line,
     then `(seon.print/full result/e25367442b2ce)`.
   - The count is exact: 21,889 − 4,742 = 17,147.
   - Running the dig form in the next pass printed all 400 lines.
4. **`*print-length*` for MCP and test entrants: contradicted.** See finding 2. SCI MCP honors it for seqs; JVM mode
   ignores it; small collections ignore it everywhere.
5. **`:repeated`: supported.**
   - `sort-by (juxt (comp - second) first)` is total. Rows come from `seon.batch` `by-sym` (one row per symbol), so
     no tie survives.
   - Whether the whole context is a function of its basis was not re-proven here. The design's as-of derivation
     still guards it.
6. **`ends-in-comment?`**: see finding 6. It is conservative and correct for `;`; the source-based rule is simpler and
   exact.
7. **Contracts**: see finding 4.
8. **Cost, armed, throwaway root, platform thread.** Five samples after three warmups. The probe store is fresh, so
   identity attributes and history are smaller than default's. The before numbers are the landing note's armed
   default numbers on the parent.

   | Probe | before ms / alloc / calls | after ms / alloc / calls | out chars |
   |---|---|---|---|
   | HTML of the context string (unit with db) | 2.3-3.0 / 484 KB / 317 | 0.75-0.77 / 513 KB / 268 | 5,281 |
   | AI text of the context string (unit with db) | 1.4-2.7 / 900 KB / 337 | 0.58-0.77 / 691 KB / 282 | 4,750 |
   | HTML of the composed map | 10.6-18.7 / 10.3 MB / 14,597 | 6.6-7.0 / **11.0 MB** / 14,862 | 26,765 |
   | `(help)` reply | 0.11-0.13 / 244 KB / 20 | 0.01 / 42 KB / 14 | 3,609 |
   | reply, 109 KB EDN entry | 1.5-7.7 / 7.4 MB / 16 | 0.11-0.14 / 2.1 MB / 13 | 108,937 |
   | reply, 109 KB dense entry | 41-43 / 96 MB / 16 | 0.09 / 1.0 MB / 13 | 108,937 |
   | 100-form SCI `seon.mcp/call` (different forms) | 443-487 ms; 560 MB process | 391-468 ms; 8-10 MB thread; **614-644 MB process** | 11,879 |

   **Over budget:**
   - The composed map's HTML is over 10 MB. Linked task `0d22b08e34c2`. It is about 7% more allocation and 265 more
     armed calls than the before number. The cut labels, links and whole-text links add 1,648 output characters; the
     stores differ, so the delta is only indicative.
   - The 100-form pass is over the 10 MB line process-wide. This is pre-existing, and the input differs from the
     landing note's.

   **Caution:** a unit without `:seon.db/db` on a bare thread measures the refused path, which gave 540/743 chars on
   my first run. The landing note's default probe relied on MCP custody to supply the database.

## Verification limits

- No browser paint and no web-handler probe, so finding 1 on the debug page is inferred from source.
- I ran no tests; the landing note's runs were not re-run.
- Default is still on 25bb94be0: **RESTART NEEDED** (root's batch) before the landing note's default-probe can be
  repeated on default's store.
- The probe root was stopped after the run.
