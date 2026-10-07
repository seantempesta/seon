# Batch C review (opus), 2026-10-07

Commits: 7a6878374 + 8df768c25 (error frames, stack-trace audit), bd5e0e8c9 (root bindings; its message is
7a6878374's, the right text is in heap-retained-notes.md), fdc1fe9e9 (printers-errors).

Limit: default runs code from before this batch (`stack-frames` has one arity, `current-frames` and no
`call-with-root-bindings` are loaded, checked at the REPL), so finding 1 was reproduced by running the
commit's own algorithm (the copied `stack-frames` loop plus refusal's `first-party-in`/`authored-prefixes`)
as locals in a JVM-mode probe, not by calling the new Vars.

## Findings, by severity

1. MEDIUM, reproduced: `diagnostic`'s `:seon.error/frame` and the producer now disagree, and the frame can name
   the wrapper instead of the call site. refusal.clj `diagnostic` takes "the first first-party frame of each
   link's own frames, root outward". Since 7a6878374 a link's own frames are trimmed to those it does not share
   with its enclosing link. `root-frame` (which `producer` uses) still reads the root's whole trace first.
   Probe: `f` calls `(clojure.string/upper-case nil)` and catches; helper `g` builds the wrapper
   `(ex-info "wrapped" {} e)`. The root's own frames are only `clojure.string$upper_case`, so the new
   diagnostic frame is `g` (the wrap helper). `root-frame` names `f`, the line an agent must edit, and so did
   diagnostic before the change. Fix: `diagnostic` uses `(root-frame throwable)`: one root-trace conversion,
   O(depth) on the failure path. Add a regression with this helper-wrap shape that asserts
   `:seon.error/frame` = `root-frame` = the call site. **refusal.clj is held by printers-round2; for root.**
2. MEDIUM, by reading: 8df768c25 makes `seon.sci.admit` admit a throwable value as `refusal/chain`. That
   is the evidence-bounded chain (`bounded-data`), so an agent's own throwable value, e.g. the result of
   `(try … (catch Exception e e))`, has `ex-data` over 16 KB printed held by name and not retrievable. That
   breaks "agents' values never clipped": `Throwable->map` kept the data whole for the value printer.
   printers-round2's uncommitted refusal.clj removes `bounded-cost`/`bounded-data`, and that removes this
   clipping. When it lands, `chain` must keep data whole. Add an admit regression: a 20 KB string in
   `ex-data` survives admission whole. **Held (refusal.clj and the admit regression's subject); for root /
   printers-round2.**
3. LOW, fixed here: the leak class had no regression at the graph site. The store test covers
   `open-branch!` and the flow test covers only the helper. Added
   `seon.flow-test/a-graph-started-inside-an-evaluation-keeps-none-of-its-bindings`: `start-graph!` inside
   a sink binding, where a join (the frame Flow's mult/tap go-loops start in) sees no sink. It fails on the
   parent, where joins ran in the caller's frame.
4. LOW, fixed here: test_support's refusal message named its key as `::offending-entity` (shorthand that
   resolves only in that namespace). It now names `:seon.test-support/offending-entity`.
5. LOW, UNVERIFIED: transaction functions on branch writers no longer see the opener's projection binding.
   Before, they saw an evaluation's projection that might already be dead. Now their armed contracts check
   against `checking-projection`'s packaged fallback. The old behaviour was accidental and unsafe, but a
   branch whose tx-fn contracts need branch-only schemas would now check against packaged. Probe once
   default runs this batch: transact an armed `:db.fn/call` on an agent branch that holds a branch-only
   schema.
6. LOW, fixed here (Astra R3, in part): `open-store!` opened the main connection in the caller's frame, and
   it is public and called inside evaluations (tests, `acquire-root-store!`). Its `d/connect` now goes through
   `call-with-root-bindings`. Regression:
   `seon.cluster.store-test/a-store-writer-never-keeps-the-opening-evaluations-bindings`. The rest of R3
   is held by printers-rest, for root: `db.clj` `connection-generator`'s `fresh-connection` and `render/web.clj`'s
   `generator-mult` delay. Both are generator resources: a sample drawn inside an evaluation starts a writer
   or mult go-loop under that frame. Wrap them, or drop the process-held mult.
7. INFO: bd5e0e8c9's message is 7a6878374's (already known; history cannot be amended in a shared
   tree). The right text is in heap-retained-notes.md.

## Astra's review (review-batch-c-review-astra-batch-c.md), verified

- R1 (admitted throwable data clipped) = finding 2: confirmed, held, for root/printers-round2.
- R2 (compression loses the root frame) = finding 1: confirmed by probe. Astra's further point stands: a
  link's own frames no longer locate that link's failure on their own. A root whose trace is wholly a
  suffix of its wrapper keeps no frames. Using `root-frame` fixes the principal frame. Per-link
  locatability needs a shared-tail marker on the link schema, an errors-spec decision. Held, for root.
- R3 (uncovered long-lived starts): `open-store!` fixed here (finding 6). The two generator sites are
  held (printers-rest), for root.
- R4 (set rebuild rehashes a deep rebuilt member): accepted by reading (`(conj (disj held original) r)`,
  refusal.clj `referenced` :step), not executed. A pre-existing class that the explicit stack does not
  cover. Held (refusal.clj), for root.
- R5 (cause cycle hangs in `fault/carried-receipt` and in `refusal/refusal`'s loop before the safe walk):
  accepted as pre-existing, with existing task 465ff60ccda0. fault.clj is held (printers-rest). No
  duplicate task filed.
- Complexity correction: accepted. error-frames-notes.md now states the O(L x D) suffix comparison.
- Rejected: none.

## Owner design point: are errors in a reply diggable by handle?

Partly. Probed through MCP SCI on agent `review-batch-c-probe`:
- An evaluation's own failure (`:seon.repl/error`) carries the evaluation's `:result` handle, and the
  handle is the error map. `(throw (ex-info "probe" {:a 1}))` gave `result/e9849de104820`, whose keys
  are `:seon.error/at`, `layer`, `operation`, `message`, `world`, `exception-class`, `chain` and
  `:seon.sci.eval/stacktrace`. In a multi-form run, each failing form gets its own handle
  (`(/ 1 0)` gave `result/e1f87b6de4c21`).
- NOT diggable: `:seon.repl/errors` (the branch's new errors/faults) and `:seon.repl/diagnostics` (a later
  stage's failure beside a value). `repl.clj` `response-entries` takes both as already-printed text
  (`:seon.repl/errors (:seon.repl/errors emission)`, "ALREADY TEXT"). `render/transcript.clj` (~:615-623)
  floor-prints each found error to a string. Neither gets a result handle, and the `:result` handle
  names the value, not these. The errors spec revision must give each such error its own handle,
  data until the value printer. Not implemented here. Also noted: that transcript site throws
  `(ex-info (str message) text)` on a non-string render, a throw on the render path.

## Verified holding

- Frames: `stack-frames` follows the JDK frames-in-common rule (suffix compare on `StackTraceElement`
  before conversion). By induction, the outermost link holds its whole trace, so every distinct
  first-party frame stays present once in the chain. `walk-links` passes enclosing = the previous
  primary link or the suppressor (`printEnclosedTrace`'s rule). Cycles still end at `seen` /
  `cause-links`. Each link keeps class, message and referenced data.
- `kernel/stacktrace`: `(into ())` → `distinct` → `(into ())` keeps each frame at its outermost position,
  innermost-first order, and `peek` is the outermost. A throw site is distinct from its recursive call
  sites, so the location is kept.
- Depth safety: `referenced` and `bounded-cost` both fold through `post-order` (explicit stack). No other
  recursive walk was added in these files. The vector-by-index rule (map entries) is preserved in
  `entries`.
- 8df768c25: `first-frame-answer` callers keep their semantics (`false` stops; a file-less frame is
  skipped where `current-frames` omitted it). The instrument and fs caller lookups are unchanged except
  that they are lazy.
- Root-binding coverage: searched src for go/go-loop/thread/future/Executors/d/connect/d/listen/
  open-branch!/open-overlay!/start-graph!/mult/tap. Every long-lived core.async start is a Flow graph
  (`start-graph!`, joins included, e.g. cluster.clj's pages mult) or a Datahike writer (`open-branch!`,
  `open-overlay!`). `d/listen` callbacks run on the writer and capture no frame. `Thread/ofVirtual` and
  `startVirtualThread` do not inherit `Var.dvals`. The only uncovered site is `open-store!`'s
  `d/connect` (boot, called from root bindings), which the notes declare. `graph-executor` hands
  projection explicitly per task, so resetting the frame loses no needed binding there.
- printers-errors (13 sites): each fixed message names a key that is present in the error or its cause.
  Checked: schema `:seon.schema/cycle-path`, `:seon.schema/value`, `:seon.schema/definition` and the
  added `:seon.schema/base-definition`. register!, arm and operator pass the original as cause, so its
  message stays in the chain. source.clj's moved identities are in `:expected`/`:offending`. The
  agent.clj trigger is a `[:seon.message/id id]` lookup ref (bootstrap.clj:156-158), so `(second
  trigger)` is the id. problems' unknown-test rows are maps rendered by `render.value/render-ai`, never
  stringified. edit.clj: the exact no-match case had zero positions, so `lines []` is unchanged, and no
  reader of `candidates-complete?`/`lines-complete?` remains in src, test or resources.
