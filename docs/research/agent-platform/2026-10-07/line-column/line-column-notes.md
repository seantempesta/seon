---
title: line-column — a form's position costs the text once; allocation measured where it is defined
type: landing
status: slice 1 landed; slice 2 needs an owner decision (options below)
date: 2026-10-07
---

# line-column (2026-10-07)

Lane `line-column`. Evidence: `forgive-fix/forgive-fix-notes.md` residuals, tasks `f14033845551`
(`seon.batch/line-column-rescans-the-text-before-every-form`) and `ae0364590441`
(`seon.batch/allocation-is-unmeasured-on-a-virtual-thread`). Default ran `18a68b248` (HEAD), JDK 26.0.1.

What exists and the approach: `seon.batch/evaluate-batch` folds read → evaluate → record over one text;
`read-step` takes each form's span from the entrant's read stage, and `evaluate-step` built the entry's
`:seon.batch/line`/`:seon.batch/column` with `line-column`, which copied `(subs text 0 start)` and counted its
newlines with a lazy `filter`. The fix carries a line cursor on the batch: each form's position is scanned on
from the previous form's, so a text's positions cost the text once.

## Which step of the one path this serves

The ONE read of every entrant (MCP code and JVM runs, test bodies, turns): the source position the entry carries.
No new owner or path. Deleted: `line-column` and the per-form text copies (`(subs text 0 start)` in `line-column`,
`(subs text pos)` in `seon.turn.batch/jvm-read-next`).

## Probe state

- `bin/seon status`: default `:faulted` on an open fault with task `3d2fb1b41dc8` (settle wrote another agent's
  branch), over-bound warnings on `seon.mcp/eval-clj`, `changed-identities`, `adopt-entities!`, all with tasks.
- `bin/seon check --help` answers "Unknown option." as a whole printed error map (ugly; `bin/seon help` works).
- An armed refusal I caused (nil request to `jvm-read-next`) printed a generated example of the whole request
  schema: existing task `cde56239621e` (`seon.print.value/a-contract-refusal-prints-a-generated-example-of-the-whole-schema`).
- Lint on touched files shows two pre-existing printer warnings in `src/seon/turn/batch.clj` (lines 272 and 435,
  `pr-str`-class findings), not in this slice's hunk.

## Cause 1, confirmed at the REPL

`line-column` (installed, JVM mode, platform thread, on a text of two-line defns):

| Text | Forms | ms | Allocated |
|---|---|---|---|
| 0.9 KB | 40 | 0.9 | 2.5 MB |
| 9.2 KB | 400 | 68 | 257 MB |
| 36.8 KB | 1,600 | 1,126 | 4.1 GB |

4x forms -> 16x bytes: quadratic. A second quadratic copy on the same path: `jvm-read-next` built its reader
over `(subs text pos)`, copying the rest of the text before every form.

## Fix 1 (`src/seon/batch.clj`, `src/seon/turn/batch.clj`, `resources/seon/schemas/seon.batch.edn`)

- `line-cursor` (replaces `line-column`): from the batch's `:seon.batch/line-cursor` (new registered key: text,
  pos, line, column) when it is in the identical text at or before `start`, else from 0; one primitive loop, no
  copy. `read-step` advances it for every in-bounds start; `source-of` uses it when it is this form's, else
  scans fresh, so it is correct whatever the read stage answers.
- `jvm-read-next`: `(doto (StringReader. text) (.skip pos))` (O(1)) instead of `(subs text pos)`.

EQUIVALENT: same line and column on 80 starts of a mixed text, at 0 of "", and mid-line; same JVM read steps on
six texts (comments, reader conditionals, fences, unclosed forms, syntax quote), both checked at the REPL against
the installed code. A text change between forms (a delimiter repair, a turn row) rescans that text from 0, as
before.

## Numbers (unarmed `evaluate-batch`, throwaway JVM, HEAD vs change; `fold.clj` in the lane scratchpad)

| Text | Forms | Parent | Change |
|---|---|---|---|
| 1 KB | 45 | 2 ms, 5 MB | 1 ms, 2 MB |
| 10 KB | 450 | 97 ms, 344 MB | 12 ms, 17 MB |
| 41 KB | 1,800 | 1,344 ms, 5.3 GB | 48 ms, 67 MB |
| 92 KB | 4,000 | 5,462 ms, 26.0 GB | 75 ms, 148 MB |

Linear: 37 bytes per character, the fold's own per-form work. Same last line and column at every size.
`line-cursor` alone: 152 KB, 8,000 starts, 3.0 ms, 1.0 MB. Commits and store bytes: none (nothing recorded).

Regression: `seon.run-test/a-forms-position-costs-the-text-once` (40 forms, 250 vs 2,000 newlines between them;
asserts every position and that the added 70,000 characters cost under 4 MB, measured on a joined platform
thread). Red on the parent: 233,314,576 bytes (check run `66975dde5e50`); green on the change (run
`b111a9bfae43`, where `d8-a-test-obtains-a-forms-declaration-row-from-the-entrys-change` is red on the parent too,
run `d393236dd286`, a documented D8 red).

## Finding: the armed fold is quadratic in its form count (task `08034d6f4d2b`)

With fix 1 the unarmed fold is linear, but default's ARMED fold is not: the private steps take and return the whole
batch, whose `:seon.batch/entries` every armed call validates. Measured on default (one-character forms, nothing
recorded): 50 forms 47 ms 15 MB, 100 forms 133 ms 47 MB, 200 forms 545 ms 163 MB; 450 two-line defns 8.3 s
1.17 GB (unarmed: 12 ms). Stack samples put 30 of 52 in Malli map validation under `form-step` and `evaluate-step`.
This is the larger cost of a long MCP run and the reason the 92 KB evidence was 8.2 s. Filed as
`seon.batch/each-fold-step-validates-every-entry-so-far` with the fix (per-form steps take the form's own state; the
fold conjoins the entry) and its proof. It reshapes the one-path fold, so it is not patched here.

## Design check

The design held: the batch fold is the owner of a form's position, the cursor is a value on the batch, and every
read stage keeps answering only its span. Awkward: the read stages already know line and column (SCI's
`source-reader`, the host `LineNumberingPushbackReader`), but count them from where each reader started (a version
after a delimiter repair, `.skip`), so reusing them would make each stage answer absolute positions; a cursor in
the fold is one place instead of four.
