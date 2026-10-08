# S5-03j: prose with a quote before a def (admission design proof, notes only)

No production or test change. Probes ran on agent branch `s5-03j` (SCI mode and JVM mode on that branch's
connection) at HEAD `91b6b5706`. Case from spec §4.1 "prose-before-def". Proof the later implementation lands:
`seon.cluster.turn-test/a-pass-keeps-original-definition-spans-after-prose` (not written here).

Default's `runtime_status` is `:faulted` (open fault `seon.turn.world/record-fault`, task `3d2fb1b41dc8`, plus four
over-bound warnings). Outside this slice, already listed.

## Verdict

**Falsified as the plan states it: prose before a def breaks admission today in the text fold, silently.** A
definition read after prose (or after a `;` comment, blank lines, or earlier forms on the same line) gets no program
entity, no note and no error. The cause is one producer: the reader the stepper builds at a form's start counts lines and
columns from where it started, so the Var's write origin is reader-relative while the entry's line and column are
absolute. The fix is at that producer (a reader whose line and column are the text's), after which the S5-04 end-of-pass
seam needs nothing extra for prose: prose never enters analysis.

## What exists (pinned HEAD)

- Reply stepper: `seon.sci.forgive/reply-step` (`forgive.clj:268`) skips prose lines; a line whose first character is one
  of `( [ { ' @ ^` (or `#` plus a non-blank, non-`#`) is code to the end of every form on it (`form-start?:70`). Prose
  becomes the next form's `; ` comment (`comment-of:376`, `segments:389`).
- Two entrances read it:
  1. Production: the model-reply path and MCP both go through `seon.turn/segmented-sources` (`turn.clj:3013`) into
     ROWS; each row is its own text read by `read-row` (`turn.clj:4720`) at position 0. Every entry is line 1,
     column 1, and the origin is relative to the same text, so the join is consistent. Nothing stores a coordinate in
     the original reply (evaluations hold `:seon.cluster.eval/source` and `/comment` only; the program entity holds the
     exact definition text; no span attribute).
  2. The one-text fold: `seon.turn.batch/turn-request` (`turn/batch.clj:866`, mode `:reply`) and `code-request`
     read the whole text through `seon.sci.batch/read-next`; entries carry real `:seon.batch/line`/`column`
     (`source-of`, `batch.clj:249`). Only tests call it today (`seon.test-support/code-request`); it is the shape S6-03 and
     S5-04 move production to ("one live cursor", "original positions").
- `join-write` (`turn/batch.clj:121`) maps the write origin into the analysis through `synthetic` (`:99`):
  `[first-row + (origin-line - entry-line), ...]`. It assumes the origin is in the entry's coordinates.

## Probe results

All through `seon.batch/evaluate-batch` over `seon.test-support/code-request` on my branch's connection, settle stage
removed, so nothing was written. Entity column = the entry's `:seon.batch/change` entities.

| Text (mode) | Entry [line col] | Program entity |
|---|---|---|
| `(defn pa..)` `\n` `(defn pb..)` (reply, no prose) | pa [1 1], pb [2 1] | pa yes, pb yes |
| `Some prose.\n(defn pa [x] (inc x))` (reply) | [2 1] | **none** |
| `Some prose, it's "q".\n(defn pa [x] (inc x))` (reply) | [2 1] | **none** |
| `\n; note\n(defn pa..) (defn pb..)` (code) | pa [3 1], pb [3 23] | **none, none** |
| prose + `(def s "éé") (defn pb ..) (defn pc ..)` on one line (reply) | [6 1], [6 14], [6 50] | **none** for pb, pc |

No diagnostic, note, error or finding in any of the missing rows. Cause, measured on the second row: the write origin is
`{:line 1 :column 1 :end-line 1 :end-column 22}` while `entry-coords` is `{:first-row 2 :line 2 :column 1}`, so
`synthetic` gives row 1 and `join-write` answers `{:join :unanalyzed :reason :no-definition-at-origin}`. The first-form
case works because `reader-at` (`forgive.clj:129`) builds the reader at the region's start (position 0 when no prose or
fence precedes) and `advance` then moves it over newlines, so lines stay absolute. After prose, a comment, a fence or a
non-forward `at`, `reader-at` builds `(sci/reader (StringReader. (subs text pos bound)))`, which restarts at 1:1. The same
relative origin reaches user-visible positions: a symbol error on `'Tis a quote line` (text line 7, column 6) reported
`(REPL:2:6)`.

Other observations in the same probes (all by the documented line rule, `forgive.clj` ns doc; none is a new defect, but they are the
prose hazards the case names):

- **A prose line that starts with a quote is code.** `'Tis a quote line` after a def produced four forms (`'Tis`, `a`,
  `quote`, `line`), ordinals 3-6, three of them symbol-resolution errors. `segments` shows the same (`'Tis` and the words
  as sources). Prose with a quote, apostrophe or double quote INSIDE the line (`it's "quoted" and don't`) is prose and
  becomes the comment `; Here is the plan, it's "quoted" and don't break.` unchanged.
- **An indented def outside a fence is prose.** `  (defn pa [x]` (two leading spaces) is not `form-start?` (it needs the
  line's first character), so its line and its continuation became comment text; the later `(defn pb .. (pa x))` then
  failed with `Unable to resolve symbol: pa`. Silent loss of a definition.
- **Trailing prose is dropped** (`"Trailing prose \"end\""` appears in no source or comment). Existing task
  `seon.sci.forgive/prose-after-the-last-form-is-dropped` (S6-03 target stores it).
- Multi-byte prose (`é`, curly quotes) before a def changed nothing: comments hold it verbatim and analysis never sees
  prose. Offsets stay correct because `local-offset` converts to UTF-8 inside the form's own source.
- Production MCP SCI mode reads as code (`code-sources`): prose there is every word a form (probe: ten
  `Unable to resolve symbol` errors for one sentence). Prose only exists on the model-reply entrance.

## Proposed seam for this case (what root approves)

1. **Producer fix, `seon.sci.forgive/reader-at`** (EQUIVALENT for every first-line first-column reader; BEHAVIOR CHANGE
   for the rest, with the regression below): when it builds a reader at `pos`, prefix the substring with the newlines and
   spaces that put it at the text's own line and column, then advance past that pad. Probed in isolation: the padded reader
   returns `{:line 3 :column 3 :end-row 3 :end-col 24}` and `{:line 3 :column 25 ...}` for the two forms of a
   mid-line start where the plain reader answered `{:line 1 :column 1 ...}`. The line/column of `pos` come from
   `seon.batch/line-cursor` (the batch already scans once) or one count in `reader-at`.
   `read-one`'s `offset` already compensates for the reader starting at `(l0, c0)`, so no other stage changes.
2. **No second mapping.** Origins, entry coordinates, `synthetic`, error positions and `form-relative-expansions` become
   the same coordinates by construction; the alternative (entries recording a reader offset so `synthetic` subtracts it) would
   leave the user-visible error positions wrong and add a field to every entry.
3. **Admission refuses loudly meanwhile**: a final root write whose `join-write` is `:unanalyzed` with
   `:no-definition-at-origin` for a function with a complete origin should surface a note (S5-04's design, not this
   slice). Today it is dropped with nothing in the reply.
4. **Prose never reaches analysis.** The end-of-pass analysis takes each defining form's source slice and its captured
   namespace row and declared set (S5-03a seam); prose is only the next entry's `:seon.cluster.eval/comment`. Nothing to add
   for prose or quotes inside it.

Cost: the pad is `(line - 1) + (column - 1)` characters per reader BUILD, and a reader is built once per region (a fence, a
rebuild after a non-forward move), not per form. Measured: a form at line 1001 behind 1000 prose lines (33 KB text) costs
0.07 ms (median of 9 after 20 warmups) and 50 KB allocated for the build; a pass with one region pays it once. Today's
alternative of reading from 0 would be O(pos) per build.

## Proof specified (`test/seon/cluster/turn_test.clj`, lands with S6-03/S5-04, not authored here)

`seon.cluster.turn-test/a-pass-keeps-original-definition-spans-after-prose`, one pass over the one-text fold in reply
mode, asserting data:

| Assertion | Today |
|---|---|
| leading, interleaved and trailing prose (with `'`, `"`, multi-byte characters inside lines) before and between defs: one program entity per def, `:seon.fn/source` equal to the authored slice | **fails** (no entity after prose) |
| each entry's `[line column]` equals the reader's position of its form in the original text, including two forms on one line | passes (`source-of` is absolute) |
| each entity's `Var` origin equals its entry position | **fails** (relative to the rebuilt reader) |
| prose lines reach the next entry's comment only; text with no code after the last def leaves no extra entry | passes; the comment/trailing-prose store is S6-03 |
| a quote-first prose line | not asserted as admission: it is code by the documented rule; the regression for it is S6-03's reader law |

A reader-level regression belongs beside it in `seon.sci.forgive-test`: the form meta `:line`/`:column` of a form read after
prose equals its position in the text (the producer contract).

## Findings and tasks

- New (for root to file): `seon.sci.forgive/reader-at-restarts-line-and-column-numbering` (this note's cause and the table).
- New (for root to file): `seon.turn.batch/an-unjoined-definition-write-is-silent` (a function write whose origin joins no
  analyzed definition records nothing and says nothing; an unanalyzed note is owed).
- New (for root to file): `seon.sci.forgive/an-indented-definition-outside-a-fence-is-read-as-prose` (probe above; either
  the stepper accepts leading whitespace before a form start or the agent is told the line was prose).
- Existing: `seon.sci.forgive/prose-after-the-last-form-is-dropped` (still true at HEAD).
- A prose line starting with an apostrophe becoming four forms is the documented line rule, not a defect; root may want the
  S6-03 reader law to decide whether a lone quote token followed by words stays code.

## Design check

The design the plan sketched (original coordinates survive prose) held for the rows path and for entries, and failed at the
reader: positions are absolute everywhere except the metadata SCI stamps from a reader that restarted. The finding changes
S5-04's dependency order: the producer fix must land before (or with) the move from rows to the one-text fold, otherwise
every definition after the first form of a region disappears from admission. No new registry, cache, atom or printer is
needed. Probes added no Vars to default (the fold ran in SCI worlds forked from `base-ctx`); the agent ids `s5-03j-*`
created branches only. No RESET NEEDED.
