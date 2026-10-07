---
title: forgive-fix — the reply read made linear
type: landing
status: landed (pending root restart)
date: 2026-10-07
---

# forgive-fix: the reply read made linear (2026-10-07)

Lane `forgive-fix`. Evidence: `plan-research/r-read-notes.md` §4 (findings 1-2). Default ran `722f110df`, then root
restarted it on `da0ae05dd` mid-lane; `src/seon/sci/forgive.clj` is identical in both. Pinned edamame `196ea5e4`,
sci `ce0aa53b`.

What exists and the approach: `seon.turn/planned-sources` and `code-sources` call `seon.sci.forgive/segments`, which
loops `forms` → `next-form`, one cursor over a text version; a model reply (`:reply`) is repaired (prose → `;`
comment, prompt marker removed, Parinfer for delimiters), code is read as written. The fix keeps that one stepper
and makes every step cost its own line or form: options normalized as edamame requires, Parinfer only where it can
change the outcome and only over the form's lines, prose and marker repairs recorded as edits applied once.

## Which step of the one path this serves

The ONE read of the agent's reply (text in → forms). No new owner, mechanism or path: the same `next-form`, the
same repairs, the same segments. What it deletes: `first-form-structural?`, `comment-rest-of-line`, the per-repair
text rebuild and the per-version line index (`:seon.batch/line-starts`, removed from `seon.batch.edn`).

## Cause, confirmed at the REPL

Probes ran as agent `forgive-fix`, JVM mode, on unarmed local copies of the source (each `def` a `let` binding and
each `defn` a `letfn` fn, compiled in `seon.sci.forgive`'s alias scope, nothing interned; the copy of the original
reproduces the installed numbers: 9.5 KB 58 ms / 217 MB against installed 48 ms / 219 MB). Corpus: the 11 live-run
replies and 2 loop-pass replies; `rr-big` = live 01, 05-08, loop 1-2 joined (9,551 chars), repeated k times.

1. `structural-options` was passed raw to `edamame.core/parse-next`, whose docstring requires normalized options
   (`reference-code/edamame/src/edamame/core.cljc:126-128`); `:all true` is expanded only in `normalize-opts`
   (`edamame/impl/parser.cljc:960-967`). So `#'`, `#(`, `@`, `#"…"` and syntax-quote failed every trial.
   Normalizing alone: 9.5 KB 219 → 41 MB, 28.6 KB 5,147 → 326 MB.
2. A failed trial ran Parinfer over the rest of the reply. For an invalid token (prose such as `Note:`, `left:`)
   that repair is always refused: Parinfer only moves closers, the token survives in the candidate, and the
   candidate must read. Gating those out (v2): 28.6 KB 314 MB, 76 KB 2.1 GB, still quadratic.
3. Every prose and marker repair rebuilt the text (`str` of prefix + comment + rest) and a new SCI reader read
   char by char to the cursor; the line index was rebuilt as boxed `keep-indexed`. With an O(1) reader (v3) the
   text copies remained: 28.6 KB 60 MB, 76 KB 355 MB, of which `comment-rest-of-line` 47 of 62 MB.
4. With repairs as edits (v4), a reply of unclosed forms showed two more quadratic terms: the structural trial of
   an unclosed form read to the end of the reply, and `balanced-end`'s loop boxed every index (`open` was an
   Object). An unbounded probe of that class (27 KB of `(see below` lines) ran about 3 minutes in default's JVM
   before finishing; my mistake, recorded here, no restart needed.

## The fix (`src/seon/sci/forgive.clj`, `src/seon/turn.clj`, `resources/seon/schemas/seon.batch.edn`)

- `structural-options` = `(edamame/normalize-opts {...})`. A code suffix on a prose line keeps today's rule (plain
  collections only) through `suffix-options`, normalized without `:all`, so its behavior is unchanged.
- `structural-failure?` replaces `first-form-structural?`: a collection is read only to its matching closer
  (`balanced-end`, primitive, allocation-free); an unclosed one is a structural failure without a read; a plain
  token start can fail only as a token and is not read; an invalid-token failure never runs Parinfer.
- `delimiter-repair` runs Parinfer over the form's lines only: up to the next line outside a string that starts at
  column 0 with neither a closer nor a comment (`form-lines-end`), where indent mode closes every open form, and
  accepts when those lines then read as at least one form. A failure at no collection (a stray closer) is judged
  with the next form's lines too (a fence ends them), so a closer is dropped only before a form.
- Prose and marker repairs are edits `{:seon.batch/at :seon.batch/through}` (new optional key
  `:seon.batch/through`); the cursor moves past them in the same text (a new O(1) version), `line-from` carries
  the line break the repaired text has there, and `forms` applies the edits once (`repair-edits`,
  `apply-edits`) and moves the spans into the repaired text. `forms` answers what it answered.
- `version` starts the reader at `pos` (`StringReader.skip`), so it is O(1); `read-one` derives the end from the
  reader's line and column deltas, scanning only the form's own lines.
- `seon.turn/segmented-sources` enforces `max-source`: a text over the bound is not segmented but planned as one
  source, which `read-row` refuses with the reader's bound message ("This form is N characters; the reader's bound
  is M."), so the agent sees it; never a cut.
- Docstrings: the `## Costs` sections with dates and measurements are deleted (CLAUDE.md: no measurements in source).

## Behavior

EQUIVALENT (preconditions checked): the same `segments` on all 13 real replies in both modes, on rr-big ×3 against
the installed code, on rr-big ×8 against the v3 copy (same semantics as installed, proven at ×1), and on every
edge case below except the listed changes. `forms` returns the same sources, spans, repaired text and unread
offset; its repairs now carry `:seon.batch/at`/`:through`.

BEHAVIOR CHANGES (1-2 in `seon.sci.forgive-test/a-delimiter-repair-is-judged-on-the-forms-own-lines`, 3 in
`seon.sci.forgive-test/a-reply-over-the-source-bound-is-one-refused-source`, 4 held by
`seon.ai-test/captured-run4-replies-stop-before-fabricated-responses`):

1. A form holding `#'`, `#(`, `@`, `#"…"` or syntax-quote that lacks a closer is repaired, as any other form is:
   `"(meta #'x\n(+ 1 2)"` → `(meta #'x)`, `(+ 1 2)` (was: the whole text one unread source). The old refusal was
   the bug.
2. A delimiter repair is judged on the form's lines, not on the whole rest: later prose no longer refuses it.
   `"(defn f [x]\n  (inc x)\n\nNote: done."` → `(defn f [x]\n  (inc x))` (was: one unread source because `Note:`
   is no token). `"(see below\nmore prose: here"` now evaluates `(see below)`; before, the rest was one unread
   source. On 150 seeded line mixes of the real replies, 72 of 300 mode-runs differ: 71 where the new read accepts
   1-4 delimiter repairs the old refused, 1 where it refuses one the old accepted (behavior 4). Exact equivalence
   would need Parinfer over the whole rest at every structural failure: about 2 KB per character, 150 MB for a
   30 KB reply, against the 20 MB budget.
3. A text over `max-source` is one refused source instead of being segmented.
4. A stray closer with no form after it in the next form's lines is not dropped: `"(+ 1 2))"` reads `(+ 1 2)` and
   leaves `)` as the unread rest, whose read error the agent sees (was: silently dropped). `"(+ 1 2))\n(+ 3 4)"`
   still drops it. This keeps the recorded run4 reply `d3597309628b` (a lone `)` in a fence, sent on purpose) a
   source, as `seon.ai-test/captured-run4-replies-stop-before-fabricated-responses` requires; dropping it left the
   reply with no form, which the turn then evaluates as prose (task `eda2e37d4785`).
5. Not reachable by a production entrant (only `seon.turn.batch/turn-request`, which has no caller, reads `:reply`
   through the fold): there, the version's text is the reply with delimiter repairs only (prose edits are listed
   in the repairs), and a form read after a delimiter repair carries reader line metadata counted from the repair.

## Numbers (same probe, parent vs change, unarmed local copies, median of 7-11 runs)

| Probe | Parent | Change |
|---|---|---|
| rr-big 9,551 chars `:reply` | 48-58 ms, 218 MB | 5.9 ms, 5.1 MB |
| rr-big ×3 28,653 `:reply` | 525-666 ms, 5,147 MB | 14.4 ms, 15.4 MB |
| rr-big ×8 76,408 `:reply` | 4,099 ms, 93.9 GB (r-read) | 36.9 ms, 40.7 MB |
| rr-big 9,551 `:code` | 1.3 ms, 2.2 MB | 1.3 ms, 2.1 MB |
| 1,000 two-line defns, 23 KB, `:reply` | 14.3 ms, 32.7 MB | 12.3 ms, 22.0 MB |
| same, `:code` (the MCP path) | 10.1 ms, 14.5 MB | 8.6 ms, 14.0 MB |
| ten defns `:reply` | 0.12 ms, 209 KB | 0.15 ms, 221 KB (+6 %) |
| ten defns `:code` | 0.08 ms, 141 KB | 0.08 ms, 139 KB |
| test unit ×40 → ×160 (6 KB → 24 KB) | 81 MB → 1.71 GB at ×10 → ×40 | 7.5 → 30.2 MB |

Timings were taken at a load average near 10; a later rerun at load 50 (other lanes' check JVMs) gave 65-212 ms for
the same probes with identical allocation, so allocation is the robust number. Also checked equal to the parent:
the 6 run4 replies (whole and stopped before `#:seon.repl`) and the 3 `reader_fences.edn` replies, both modes.

Growth is linear (×3 length → ×3.0 bytes; ×8 → ×8.0). Targets met for the real-reply mix: 14.4 ms and 15.4 MB at
28.6 KB. Armed calls were not counted: the probes are unarmed copies, and installed arming showed no measurable
allocation on the parent (installed 219 MB vs copy 217 MB). Commits and store bytes: the read writes nothing.

## Residuals, filed

- BROKEN, still: `seon.sci.forgive/each-accepted-delimiter-repair-copies-the-whole-reply` (`34dd39492de9`): each
  accepted delimiter repair copies the text, and Parinfer costs about 2 KB per character of the form's lines. A
  reply where nearly every form needs one is quadratic: unclosed defns 9 KB 35 MB, 27 KB 140 MB; `(+ 1 2))\n(+ 3 4)`
  repeated 9 KB 72 MB, 27 KB 320 MB (1.1 s at load 50). The parent is worse on both (a whole-rest Parinfer per
  repair). No recorded reply has more than one such repair.
- `seon.sci.forgive/each-prose-line-costs-a-thrown-reader-error` (`d3b4dd5938dd`): a prose line is recognized by a
  thrown SCI read error; `ex-info` materializes the stack trace: 69 KB per prose line at stack depth 192, about 12
  of the 15 MB at 28.6 KB. Plan v2's one read.
- `seon.batch/line-column-rescans-the-text-before-every-form` (`f14033845551`): `seon.batch/source-of` copies and
  scans the text before every form. 92 KB of 4,000 forms: 8.2 s, 25.7 GB (measured by me in default; a probe I
  should have started small). Quadratic for JVM-mode MCP and `seon.test-support/evaluate` on long texts.
- `seon.batch/allocation-is-unmeasured-on-a-virtual-thread` (`ae0364590441`): SCI evaluations and test bodies run
  on virtual threads, where `ThreadMXBean` answers -1, so `seon.batch/allocated-bytes` reads 0 and allocation
  assertions in tests pass vacuously. My allocation test first read `(< 0 0)`; it now measures on a joined platform
  thread and asserts a positive reading first.
- Red on HEAD da0ae05dd alone (`bin/seon check`, run `804c549e58bf`), not this slice:
  `seon.cluster.evaluate-sources-test/ordered-evaluation-is-red-on-head` (`6880c0f7c22b`) and
  `seon.sci.render-source-test/error-pairs-render-without-their-reads-on-head` (`b700fe9e93ec`).
- `seon.test-support/evaluate-answers-no-value-for-an-unreadable-source` (`19180cd1866c`):
  `seon.ai-test/captured-run4-replies-stop-before-fabricated-responses` is red on HEAD alone at ai_test.clj:529
  (run `1d9de8ab6cbd`); with this slice its line-519 assertion (forms retained) passes and the same line 529 fails
  (run `a7033fff7105`), so it is not named in the commit.
- `seon.test/a-changed-namespace-makes-the-next-turn-test-pay-seconds` (`9bad29c4b79b`):
  `seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent` passes every assertion with this slice
  but exceeds its 5 s bound (5.4-10 s); a docstring-only edit of `forgive.clj` does the same (8.8 s, run
  `aa5472877999`) while HEAD passes (run `5ea633867819`). The seconds are derivation for the changed namespace, not
  the read; the test is not named in the commit for that reason.
- `seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent` is red on HEAD alone (run
  `caf519b597ab`: a var shown as `{:seon.render.var/symbol …}` where `#'…` is expected); same class as
  `b700fe9e93ec`.
- `segments` still recurses on `(subs repaired end)` after each balanced unreadable form (an odd map): O(rest) per
  such form. Not measured as a problem; noted.
- Proven tasks: `seon.sci.forgive/structural-trials-refuse-var-quote-anonymous-fn-deref-regex-and-syntax-quote`
  (`56dc5f9cb734`) and `seon.sci.forgive/a-prose-repair-replays-the-whole-reply` (`61c1346b6ef2`), named by the
  new tests.

## Design check

The design held for the cost: one stepper, edits instead of rewrites, and trials bounded by the form. Three things
were wrong or awkward on the way:

- The first edit design advanced the old reader past a repair, but `read-one` had already consumed the prose
  token, so the cursor drifted; a version is now O(1), so an edit simply starts a fresh reader at its end.
- A token gate is exact only under whole-rest acceptance. Once acceptance is per form-lines window, a later token
  no longer vetoes a repair, so the trial had to stop reading past the form's closer (`balanced-end`), which also
  removed the O(rest) trial of unclosed forms. That window is the one deliberate semantic change (behavior 2).
- The first check (run `78de2c94a081`) refused the slice: the window dropped the lone `)` of run4 reply
  `d3597309628b` (normalized options read the fence after it, `` ``` ``, as a syntax-quoted form at end of input),
  and the allocation test was vacuous on a virtual thread. Both fixed: a repair must leave a form, a stray closer is
  judged with the next form's lines up to a fence, and the test measures on a platform thread.
- `forgive.clj` grew from 376 to 480 lines (edits, the bounded trial, form-lines). The simplest viable alternative,
  normalization plus the token gate (+15 lines), leaves 314 MB at 28.6 KB. What this lets go later: once plan v2's
  one read lands, `code-suffix`, `suffix-options` and the prose throw path go with the prose rules.

Root must restart default to load the change (compiled host code and a schema resource, `:seon.batch/through`
added, `:seon.batch/line-starts` removed). No RESET NEEDED.
