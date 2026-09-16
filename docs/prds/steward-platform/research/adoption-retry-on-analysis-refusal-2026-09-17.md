---
type: research
date: 2026-09-17
tags: [publication, adoption, seon.cluster, seon.fn, bounded-boundaries]
---

# The analysis-time source change takes the same single retry as the adoption compare

Follow-on to `50a7110b7` ("fn: a span the captured source cannot hold is the
typed refusal"), which left the boundary named in the issue's closing section:
the refusal it introduced never reached the one adoption retry.

## What was wrong

Two seams observe the same event — a first-party file edited under a running
publication:

- **analysis**, `seon.fn/span-refused!` (`src/seon/fn.clj:146`), raised when a
  declaration's analyzer span does not fit the text `source-contexts` captured;
  cause `:seon.fn/source-changed-during-analysis`, kind `:seon.fn/index-refused`;
- **adoption**, the post-publication digest compare
  (`src/seon/cluster.clj:2169` before this change); cause
  `:seon.cluster/source-changed-during-adoption`, raised through `refused!` so
  its cause rides `:seon.boot/offense`.

Only the second was recognised. The retry predicate compared one literal cause
under `[:seon.boot/offense :seon.error/diagnostic-cause]`, so an analysis-time
change fell through to the incremental catch (`src/seon/cluster.clj:1904`),
which reads `:seon.fn/index-refused` and answers `:full-rebuild` — a complete
re-analysis of the same moving tree, with no retry and no convergence on the
next edit.

## The change

`src/seon/cluster.clj`, one declaration and one predicate next to `refused!`:

- `source-change-phases` maps each declared source-change cause to the phase it
  names (`:adoption`, `:analysis`). It is the single place the two seams' causes
  are declared together; no second list exists.
- `source-change-phase` derives the phase from a failure's declared cause at
  either position (offense or top-level ex-data), or nil.
- `retrying-source-change` replaces the inline retry loop in `refresh-source!`:
  one attempt, one retry when `source-change-phase` names a phase, and a refusal
  that survives the retry carries `:seon.source/change-phase` plus the original
  typed cause. The progress report names the phase that changed.
- The incremental catch now falls back to a complete rebuild only for an
  `:seon.fn/index-refused` that is **not** a source change: a moving tree is not
  a rebuild reason.

## Evidence (in process, pid 17352, hot-reloaded Vars)

- The pre-change loaded `exact-source` behaviour and the new refusal were both
  read live. With the peer's fix loaded, a context captured from a 30-byte file
  and sliced with a span from the grown file yields
  `clojure.lang.ExceptionInfo` "Source changed during analysis; a declaration
  span does not fit the analyzed text.", cause
  `:seon.fn/source-changed-during-analysis`.
- `source-change-phase` returns `:analysis` for that refusal, `:adoption` for the
  digest-compare refusal, and `nil` for a plain `:seon.fn/index-refused`.
- `retrying-source-change` with a thunk that refuses once then succeeds returns
  the published map after exactly two attempts and reports
  `"source changed during analysis; retrying publication once"`; a thunk that
  refuses twice raises `:seon.boot/refused` whose offense carries
  `:seon.source/change-phase :analysis` and the original cause.
- The committed forms themselves (read from the file, `load-string` under
  `*ns* seon.cluster`) were defined into pid 17352 and the regression re-run
  against them.

Regression: `seon.cluster-test/a-source-change-during-analysis-takes-the-one-publication-retry`
— in-process `seon.test/run` on a daemon thread: **10 passes, 0 failures,
0 errors** (run recorded on `current-src`).

## Verification boundary

- No test JVM was launched; no `bin/test` run. The batched gate is the proof
  (`tmp/orchestrator/gate-requests/adoption-retry.txt`: `seon.cluster-test` plus
  `--platform`).
- The edit was written through the shell, so it is **not adopted** in `default`;
  adoption there is refused by an unrelated foreign cause (test-identity
  tombstone, another lane). The proof above is hot-reloaded Vars carrying the
  committed text, not an adopted cluster.
- The incremental-catch guard (`src/seon/cluster.clj:1954`) is proven only
  through the predicate it calls; no live incremental publication was driven.
- `refresh-source!`'s own end-to-end retry was not driven against a real store;
  the retry mechanism is proven at `retrying-source-change`, the function
  `refresh-source!` now calls.

## Left in place deliberately

Two further seams refuse with "Source changed while … was being analyzed"
(`src/seon/cluster.clj:1860` and `:1953`) — snapshot-before/after compares that
carry no `:seon.error/diagnostic-cause` and therefore get no retry, although
their own messages say "retry". Giving them the declared cause would fold them
into the same single retry; it also changes the full-refresh path's behaviour,
which is outside this slice. Filed as a follow-up in the issue note.
