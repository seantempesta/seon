# error-frames lane notes (2026-10-07)

Task: seon.sci.kernel/a-failure-keeps-and-classifies-every-frame-of-every-link.

## Reproduced (jvm mode, failure-value called directly on SCI-thrown throwables)
- 1,000-link ex-info chain thrown from SCI: failure-value 187 ms / 757 MB thread-allocated; chain keeps 1,000
  links x 37 first-party frames = 37,000 frames; each link's JVM trace is 209 frames, all converted to vectors
  (`stack-frames`) and classified (`first-party-in`) per link.
- StackOverflowError in a 3-frame SCI recursion: failure-value 8 ms itself, but `:seon.sci.eval/stacktrace`
  holds 8,126 entries (a 3-frame cycle); downstream print (print-into 151 ms), value storage (semantic-leaf 24k
  calls, store +390 KB) and printer walks (node-child? 160k armed calls) all scale with it.

## Cause
1. `walk-links` converts and classifies every link's WHOLE stack trace, though a cause's trace repeats its
   enclosing trace except for a short prefix: links x frames. `Throwable.printStackTrace` prints only the frames
   a link does not share with its enclosing trace ("... n more", `printEnclosedTrace`).
2. `kernel/stacktrace` keeps SCI's callstack whole, so recursion depth (bounded only by the JVM stack) becomes
   value size.

## Fix (smallest correct, toward errors.md §1; no shape change)
1. `stack-frames` gains an `enclosing` arity: a link's frames are its trace less the suffix it shares with its
   enclosing throwable's trace (the JDK's frames-in-common rule), compared on StackTraceElement arrays before any
   conversion. `walk-links` passes each link's enclosing throwable (previous link of its cause chain, or the
   throwable that suppressed it). The outermost link keeps its whole first-party trace, so every distinct
   first-party frame is still present exactly once in the chain; `diagnostic`'s root-most-first frame selection
   is unchanged in all cases where the location frame is in the root's own prefix. `root-frame` reads the
   same own frames (root outward), so it no longer converts every link's whole trace either.
2. `kernel/stacktrace` keeps each distinct `[where line column]` once, at its outermost position (so `peek` stays
   the outermost frame readers use): a recursion's repeated run collapses to its distinct frames; a
   non-recursive trace is unchanged. Same schema (`:seon.sci.eval/stacktrace` vector of triples).

Cost: chain work O(total distinct frames + links), not links x frames; stacktrace O(depth) to read once,
O(distinct frames) kept.

## Not doing
- No repeat count / collapse count keys (would change the error map shape; errors spec has not decided them).
- No clipping of link count: a 1,000-link chain keeps 1,000 links (class/message/data references), each now
  frame-light. Link data (`bounded-data`) unchanged.
- Not migrating links to the spec's `{:type :message :data :at}` shape (spec slice, not this lane).
- The defn installation cost inside the original stack-overflow measurement (install-evaluated-rows! 482 ms) is
  not this producer.

## Added on coordinator request (2026-10-07)
- Deep value (deep-value lane): `(throw (ex-info "deep" {:v deep}))` with a 10,000-deep vector overflowed in
  `refusal/referenced` and `bounded-cost` (both recursed per level; bounded-cost through its armed wrapper).
  Both now fold over one explicit-stack `post-order` (heap, not stack); same results, one armed call per walk.
- kernel `memory-pressure-error`: fixed message; pool/bytes/bounds/in-flight stay as the pressure's data keys
  (no pr-str of in-flight runs).
- kernel `execution-error-text`: `Throwable->map` (every link, every root frame converted) replaced by
  `triage-input`: the three links ex-triage reads and the root trace converted lazily.
- Not done here (other owners / spec slice): second-clipper deletion (`bounded-cost`, `chain` vs
  `recorded-chain`, `:seon.error/bounded-chain`) needs src/seon/error.clj (held by printers-errors) and the
  schema EDN; stack-trace sites in schema.clj, operator.clj (printers-errors), test/runner.clj (reach-review).
