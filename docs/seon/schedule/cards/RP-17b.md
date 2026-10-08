---
title: "Card RP-17b: Delete the old source reader"
type: reference
status: active
created: 2026-10-08
---

# RP-17b Delete the old source reader

Ledger row RP-17a-b (P13). Delete `seon.sci.reader/read` and every private helper only it reaches in
`src/seon/sci/reader.cljc` (887 lines at HEAD; `declaration-facts` `:363` and its recovery helpers are UL-1d's
deletion: if UL-1d has landed they are gone; if not, they go here under the same ruling). Keep what
`seon.sci.forgive` still requires (`reader/reader-error`, `forgive.clj:379,391`, and the `:seon.sci.reader/*` error
schema keys its error values use); move `reader-error` into `seon.sci.forgive` if it is the only survivor, converting
its two call sites.

Proof: D: `rg -n 'seon.sci.reader/read\b|reader/read \{' src test` answers nothing; `seon.sci.reader-test` keeps only
tests of surviving functions (delete the rest with the machinery); `seon.repl-differential-reader-test` green.

Commit: `bin/lane commit <lane> -m "RP-17b: the old source reader is deleted" --test seon.sci.reader-test --test seon.repl-differential-reader-test --test seon.sci.forgive-test src/seon/sci/reader.cljc src/seon/sci/forgive.clj test/seon/sci/reader_test.clj`

Dependencies: RP-17a; UL-1d (coordinate: whichever lands second deletes what is left). Reset: none.
