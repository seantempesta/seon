---
title: "Card RP-11a: One reading cursor for MCP code"
type: reference
status: active
created: 2026-10-08
---

# RP-11a One reading cursor for MCP code

Ledger row RP-11a (P7, CUR; group B). Design: `redo/pass-and-recording.md` §1 "Today's MCP path" (the text is read
twice: `forgive/segments` pre-segments, then `seon.turn/read-row` reads each segment again), §2 READ, §5 Reading.
Lane: Sonnet. Cited at HEAD `e7f120672`. Two lettered slices: **RP-11a1** reads at the live cursor inside
`evaluate-sources`; **RP-11a2** stops the MCP submission from segmenting.

## What exists
- `seon.cluster.agent/submit-source-in-projection` (`src/seon/cluster/agent.clj:690`) segments the text with the
  `:seon.agent/segmenter` (`seon.turn/code-sources`, `src/seon/turn.clj:3061` → `segmented-sources` `:3017` →
  `seon.sci.forgive/segments` `src/seon/sci/forgive.clj:440`), and its run request carries `:seon.turn/sources` and
  `:seon.turn/reply-size (count (pr-str sources))` (`agent.clj:774`).
- `seon.turn/evaluate-sources` (`turn.clj:4918`) reads rows with `turn-rows-state`/`read-row` (`:4722-4758`), which
  read each pre-segmented source again with `forgive/next-form` in `:code` mode.
- The live cursor already exists and is used by `seon.turn.batch/code-request` (`src/seon/turn/batch.clj:885`):
  `:seon.batch/read-state forgive/read-state` (`forgive.clj:338`) + `:seon.batch/read-next seon.sci.batch/read-next`
  (`src/seon/sci/batch.clj:25`), one `sci/parse-next` per form at the cursor.

## RP-11a1: `evaluate-sources` reads at the cursor

### Files
`src/seon/turn.clj`, `resources/seon/schemas/seon.turn.loop.edn` (the `evaluate-sources` request), `test/seon/repl_differential_p7_test.clj`.

### Functions
- CHANGE `seon.turn/evaluate-sources` (`:4918`): the request takes `:seon.batch/text` and `:seon.batch/mode`
  (`[:enum :reply :code]`) instead of `:seon.cluster.reply/sources`; the batch request uses
  `:seon.batch/text text :seon.batch/read-state forgive/read-state :seon.batch/read-next sci.batch/read-next
  :seon.batch/mode mode`; `rows` and the eager `handles` map (`:4957-4961`) go: the frame mints the handle from
  `(admit/result-handle (receipt-identity run-id ordinal))` per ordinal when the form is read. Each answered
  evaluated source's `:seon.turn.loop/admitted-form` comes from the entry (`:seon.batch/source`, its comment and
  repairs) instead of the row. `:seon.turn.loop/evaluate-sources-request` in `seon.turn.loop.edn` changes with it.
- CHANGE `seon.turn/resume-turn` (`:5132`): passes the submission's `:seon.batch/text`/`:seon.batch/mode` when the
  submission holds text (RP-11a2, RP-11b), else today's sources (the generated opening, until S4-02b deletes it).
  Pick by key presence.

### Proof
`seon.repl-differential-p7-test/an-alias-required-by-one-form-resolves-in-the-next` (marked existing in the ledger: it
exists in `test/seon/repl_differential_p7_test.clj`; it must stay green) and the new
`seon.repl-differential-p7-test/a-discard-of-an-empty-conditional-matches-plain-sci`: the eval request
`"#_ #?(:cljs 1) 2 3"` answers one envelope with value `3` (plain SCI discards `2` with the conditional); on HEAD the
segmenter splits differently (ledger §2 LR-READ row). The reader-fix rows of `seon.repl-differential-reader-test` stay
green.

### Commit
`bin/lane commit <lane> -m "RP-11a1: evaluate-sources reads its forms at the live cursor" --test seon.repl-differential-p7-test --test seon.repl-differential-reader-test --test seon.turn-test --test seon.cluster.evaluate-source-test src/seon/turn.clj resources/seon/schemas/seon.turn.loop.edn test/seon/repl_differential_p7_test.clj`

## RP-11a2: the MCP submission carries text

### Files
`src/seon/cluster/agent.clj`, `src/seon/turn.clj` (`system-run-plan`, the step's submission map), `src/seon/mcp.clj`
(`sci-run` passes no segmenter), `resources/seon/schemas/seon.agent.edn` (submission request), tests below.

### Functions
- CHANGE `seon.cluster.agent/submit-source-in-projection` (`:690`): no `segment` call; the run request carries
  `:seon.batch/text text :seon.batch/mode :code :seon.turn/reply-size (count text)` (the `pr-str` reply size is
  deleted); no `:seon.turn/sources`. The "no forms" refusal here goes: empty code is RP-13's (answers `nil`).
- CHANGE `seon.turn/system-run-plan` (`:703`): `sources` is optional; with none it writes the open row only (no
  `source-rows`): the evaluation rows are written by the settlement from the entries. `::reply-size` defaults to
  `(count (or ::reply ""))`.
- CHANGE the proc step's submission map (`turn.clj:6003-6007`): `:seon.batch/text`/`:seon.batch/mode` from the run
  request instead of `:seon.turn/sources`.
- DELETE `seon.cluster.agent/segmenter` (`:644`), the `:seon.agent/segmenter` key (`mcp.clj` `sci-run` passes
  `'seon.turn/code-sources`) and its schema member; `seon.turn/code-sources` when no caller remains
  (`rg -n 'code-sources' src test`: preview and generated openings may still call it; then it stays for RP-11c).

### Proof
`seon.cluster.evaluate-source-test/code-evaluates-as-one-ordinary-run-of-its-agent` (existing; nine assertions red on
HEAD before this, RED-3: classify by the commit that changed them, do not mass-fix) and a new
`seon.mcp-entrance-test/an-mcp-eval-request-reads-its-text-once`: the run row's `:seon.turn/reply-size` equals
`(count code)` for `"(+ 1 2) (+ 3 4)"`, and both values answer.

### Commit
`bin/lane commit <lane> -m "RP-11a2: an MCP submission carries its text; the segmenter is gone from the MCP path" --test seon.cluster.evaluate-source-test --test seon.mcp-entrance-test --test seon.cluster.mcp-test --test seon.turn-test src/seon/cluster/agent.clj src/seon/turn.clj src/seon/mcp.clj resources/seon/schemas/seon.agent.edn test/seon/mcp_entrance_test.clj`

## Cost
Expected: one scan plus one read per form (redesign §6: 28.6 KB read in 10.5 ms / 4 MB today, read twice; target read
once, ≤ 3 ms). Report the O probe's MCP SCI rows.

## Dependencies
RP-10 (ledger), and EN-8a (MCP SCI records through `settle`, whose `record-facts` builds sources from entries: without
it the settlement still needs pre-segmented sources). Group B; `cluster/agent.clj` was led-cacheb's (released when it
landed `ead65e695`; check `bin/lane status`).

## Reset needs
None.
