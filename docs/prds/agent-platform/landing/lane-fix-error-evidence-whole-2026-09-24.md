# Lane fix-error-evidence-whole — 2026-09-24

## Broken, and why this lane stopped before editing

**The fix cannot be made inside `src/seon/error.clj` alone.** The whole
evidence is not lost where it is capped. It is lost when the prepared evidence
is returned to callers, and only one of the seven callers publishes it.

- `seon.error/prepare` (error.clj:510–668) already keeps the whole admitted
  evidence as `:seon.error/data-content` (admitted under the 8 MB result caps),
  apart from the fact's capped `:seon.error/data-edn`.
- `seon.error/recording` (error.clj:~1466) uses `prepare` but returns only
  `:seon.error/fact`, `:seon.error/value` and `:seon.db/tx-data`.
  `data-content` is dropped, and no blob is staged.
- Only `seon.fault/record!` (fault.clj:97–108) calls `prepare` itself. It
  stages `data-content` through `blob/stage!` and publishes it with
  `blob/with-publication!` alongside its commit.
- Every `error/recording` caller that is not `fault/record!` commits a capped
  fact with no blob. That covers turn.clj `refusal-terminal-data` (3670), the
  batch refusal (3745), `error-tx`/`commit-tx` (3356, 3410), the AI attempt
  (4061, 4080), and schedule.clj:484. Root's render fault took one of the turn
  paths.
- `recording` cannot publish by itself. It is pure over a database value, and
  a blob must be published under the reachability permit that
  `blob/with-publication!` holds through the commit
  (reference-code/datahike/src/datahike/gc_guard.cljc:190, 286–316). The
  callers own their commits, so each caller would have to publish the staged
  write.

## Evidence (default, read-only, JVM mode)

- The stored occurrence 46155 (`:seon.error.occurrence/id "38c7ba4a96f4"`,
  turn `38c5a9b4d625`, declared schema `:seon.render/request-error`) has:
  - `:seon.error/data-size 25879` and `:seon.error/capped? true`;
  - a `data-edn` of 1342 chars whose remainder is
    `{:seon.sci.admit/reason :over-bound :seon.sci.admit/bytes 7889}`;
  - **no `:seon.error.occurrence/data-blob`** and no `:seon.error/chain`;
  - no `:seon.render.unknown/reason`. The declared schema does not include that
    key, so the occurrence's selected source attributes cannot keep it either.
- Reproduced today's path: a `seon.render/unknown` observation (reason
  `:time-limit`) plus `:seon.render/refused-member`, with a 25 000-char
  offending message, run through `seon.error/prepare` under default's effective
  caps and `max-evidence-bytes 16384`:
  - it took 2.06 ms;
  - `data-content` was 27 860 bytes and contained the reason;
  - the fact's `data-edn` did not contain the reason;
  - `:seon.error/data-blob` was nil.
- Baseline for an ordinary under-cap fault: `prepare` averaged 0.967 ms warm
  over 100 calls, uncapped, with data-size 2565.

## Options (for root; recommendation marked)

1. **(Recommended) `recording` stages the whole evidence and callers publish
   it.**
   - What changes: when the fact is capped (or over the blob threshold),
     `recording` calls `blob/stage!` on `data-content`, sets
     `:seon.error/data-blob` to the digest, and returns
     `:seon.blob/staged-writes`. `commit-call` already writes the occurrence's
     `data-blob` ref (error.clj:1443, 1457).
   - Each caller passes those staged writes to the `with-publication!` it
     already runs, or wraps its transact in one:
     - turn.clj: four sites (turn already collects `:seon.blob/staged-writes`
       for its settlements);
     - schedule.clj:484;
     - fault.clj deletes its own staging (about −12 lines) and uses the
       recording's staged writes.
   - Cost: about +15 lines in error.clj, +10 in turn.clj and +3 in
     schedule.clj, with −12 in fault.clj. An under-cap fault adds one boolean
     test, about 0 ms.
   - Guarantee: every capped fault keeps its whole value in the blob tier,
     reachable through the digest, and published atomically with its root.
   - Sacrifice: needs ownership of turn.clj, schedule.clj and fault.clj.
2. **An eager `blob/put!` inside `prepare` when the request carries
   `:seon.db/connection`.**
   - This mirrors `prepare-result` (error.clj:463). The 6-arity `recording`
     would forward the cluster's connection. Only error.clj changes, about
     +8 lines.
   - Sacrifice: the blob is published outside the commit's permit. A sweep
     between `put!` and the commit can reclaim it and leave a dangling digest.
     That is the same race `prepare-result` already has, and it gives a
     function that is otherwise pure over a database value a side effect.
3. **Declare `:seon.render.unknown/*` on `:seon.render/request-error`.**
   - The occurrence would then keep the reason and the producer.
   - This fixes only this instance. It does not keep the whole cause.

## Changed paths, commits, verification limits

- Changed: this note only. There are no source or test edits, and the net
  source/test lines are 0.
- Regression not written: until a publishing path exists, "an over-cap
  evidence is recoverable in full from its blob" would be red on every caller
  except `fault/record!`.
- No `bin/test-check` was run, because no code changed. No branch was created,
  and no background shells were started.
