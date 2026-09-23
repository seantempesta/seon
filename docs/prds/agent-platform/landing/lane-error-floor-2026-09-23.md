---
type: landing
status: E1-E3 implemented; see Verification for what is live and what waits for a restart
created: 2026-09-23
lane: error-floor (Opus 5.5)
spec: docs/research/agent-platform/error-floor-investigation-2026-09-23.md (was plan/lane-errors-fail-alone.md, 8af2c7d18); folded into B3 §2a render row
---

# Lane error-floor — the floor is a function of the Throwable

**What the system and its dependencies already do.** `clojure.main/ex-str` +
`ex-triage` give bounded text, but only of the root link; core `print-method`
dereferences an atom and prints strings whole; Malli's `-exception`/`-fail!`
(`reference-code/malli/src/malli/core.cljc:203-207`, pin 8725a8cb) only put
`{:type :message :data}` into ex-data, and `humanize` (`error.cljc:374-390`)
reads explain data, never a Throwable. REPL form (MCP JVM, session
`error-floor`, 4 ms):
`(let [t (ex-info "outer" {:v #'clojure.core/map :a (atom 1)} (IllegalStateException. "root")) m (Throwable->map t)] {:ex-str (clojure.main/ex-str (clojure.main/ex-triage m)) :atom-printed (binding [*print-length* 8] (pr-str (atom (range))))})`
→ `"Execution error (IllegalStateException) at user/eval… root"` (outer message and
ex-data gone) and `"#object[clojure.lang.Atom 0x… {:status :ready, :val (0 1 2 …)}]"` (deref'd).

**The smallest composition that improves it.** Small functions in the value
renderer's own namespace, `seon.render.value` (one clipping spot; its profile
renderer cannot serve here — non-core objects go through `admit/admit-value`,
which needs the projection, and `stable-entries` sorts whole sets by `pr-str`):
`datum` (any value → bounded clojure.core data; non-core objects → class + identity hash),
`bounded-text` (print `datum` through a writer that refuses past its limit),
`causes` (cycle-safe, capped cause list) and `link-lines` (one link's lines);
`floor` composes them. The renderer's own failure node uses `floor`.

## Final shape (for B3 §2a, render row)

- The floor is a FUNCTION, `seon.render.value/floor` : Throwable → string. It is
  never a key: no `:seon.error/floor` on stored facts or declared error values;
  `:seon.error/chain` stays the one data form and `:seon.error/shown` the one shown text.
- `seon.render.value/bounded-text` : any value (, limit) → string is the
  projection-free total core of the value renderer. Rules: (1) only clojure.core
  data prints (nil, booleans, numbers, strings, chars, keywords, symbols, UUIDs,
  `java.util.Date`, Vars as `#'ns/name`), `clojure.lang` collections walked to 8 members and 3 levels (a lazy
  seq realizes one chunk at most); (2) every other object prints as
  `#object[Class 0xidentity-hash]` — never its print-method, toString or deref;
  (3) a writer refuses output past the limit.
- Used only where a declared value cannot be rendered: the prepl out-fn when the
  projector or print throws (reply = declared `:print-eval-result` error, as
  `clojure.core.server/io-prepl` answers a throwing valf, `server.clj:275-296`),
  `boot/request!`'s catch (core-fault message), `readable-response`'s failure,
  and emergency stderr in `boot.clj`. The renderer's `value-node` failure node
  shows `floor` of its failure instead of the bare message.
- Ordinary armed contracts (owner ruling: no arming exemption).

Caps (compiled, `text-limits`): 400 chars per value, 8 items, depth 3, 12 ex-data
entries, 8 links, 6 frames, 3 suppressed, 8,192 chars total.

## Braids

Removed: boot's wire display ↔ the AI renderer and render profile (a85bf004b's
`render-ai` calls); prepl reply ↔ a projector that may throw (the out-fn is total,
and the exit check runs before printing, so a throwing check no longer prints twice);
operator/MCP client text ↔ the EDN reader (raw text kept beside the reader error).
Added: none; the floor depends on clojure.core and `refusal/first-party-frame?`.
Each part fails alone: a value that cannot print becomes `#unprintable[…]`; a link
whose accessors throw prints its class; a projector failure becomes a declared reply.

## Evidence

Commits: `0d6eef939` (E1), `380647a56` (E2), E3 = the commit carrying this note.

| Slice | src +/− (net) | test +/− |
|---|---|---|
| E1 floor (`render/value.clj`, `error/refusal.clj`) | +109 / −5 (**+104**; estimate was +50) | +88 |
| E2 wire (`prepl.clj` resource, `operator.clj`, `dev/mcp.clj` scripts) | +86 / −39 (**+47**; estimate +20) | +48 / −1 |
| E3 boot (`cluster/boot.clj`) | +17 / −21 (**−4**) | +25 / −5 |

E1 grows src. What deletes next: E5 (`cluster/exception-summary`, `first-seon-frame`,
`nil-deref?`, the `mcp-projection-error` fallback family, ≈ −45) once m4-n1 releases
`cluster.clj`; E6 `runner/throwable-text`.

Any-value regression cases (`test/seon/render/floor_test.clj`, all green in run
`c01df6e6bdc6`): a 1e5 vector, `(range)` realized ≤ one chunk (≤ 32), a 1e6-char
string, a Var (`#'clojure.core/map`, not dereferenced), a `deftype` whose toString
and print-method throw (never called), an unrealized delay (stays unrealized), an
IDeref whose deref throws (never called), an atom holding a hostile object, a
self-containing `ArrayList`, a 4-deep map (cut at depth 3), a StackOverflowError in
ex-data, frames without a file name, host frames dropped, a suppressed throwable, a
two-link cause cycle (each link once), a 10,000-link chain (8 shown + "… further
causes"), a Throwable whose getMessage throws (`#unprintable[…]`), an
OutOfMemoryError with a nil message; output < 8,300 chars and reads back through
`edn/read-string`. E2: a throwing `:valf` yields a declared `:print-eval-result`
error with both failures, and the session answers the next form exactly once
(`prepl_retention_test`). E3: a refusal whose evidence holds a hostile object reads
back whole and its print-method never runs (`boot_reply_test`).

Caps and cost (armed, default pid 94821, MCP session `error-floor-2`): `floor` of a
three-link chain holding a 1e6-char string, a 1e5 vector, `(range)`, an atom and a
4-deep map: 385 µs, 803 chars; the same chain with `{:a 1}` costs the same
(446–557 µs across seven value shapes), so the cost follows frames (≈ 2.5 µs per
armed `first-party-frame?` call × stack depth), never the value. A 100,000-link
chain: 1.0 ms. Contracts compiled against the packaged projection
(`schema/build-projection (schema.edn/packaged-forms)`, 227 ms): all three true.

## Verification boundary

- Live: default was restarted from the checkout by the orchestrator (pid 94821), so
  E1–E3 are loaded, armed (`floor`, `bounded-text`, `datum` armed? true) and the new
  prepl entry serves NEW sessions. One test request, run `c01df6e6bdc6`
  (`--ns` floor, boot-reply, prepl-retention, mcp-bridge, refusal; 39 tests, 9.4 s:
  over one second because mcp-bridge starts real io-prepl servers per test): my 8
  tests green; 8 mcp-bridge reds pre-date this lane (`operator-private` was deleted in
  `076827cc9`; tool descriptions changed by other lanes) — retired assumptions, not
  this change.
- The operator client (`script/seon/operator.clj`) takes effect on the next `bin/seon`
  invocation; the MCP bridge (`script/seon/dev/mcp.clj`) on the next MCP server restart.
- Explicit adoption was refused five times by other lanes' dirty files (issue note
  `a-db-adoption-refuses-while-another-lane-edits-a-dependent.md`, extended). Attempt 2
  was run while leak-fix-2 held the adoption token (my `mkdir` of the token failed and
  the command continued): a lane-discipline error, reported here. It adopted in 18.6 s.
- Over ten seconds (defects, filed in that issue note): refused adoptions 13.5 s and
  15.0 s, successful adoption 18.6 s, each dominated by `full-source-refresh!` (whole
  program) before the refusal check.
- Not done: bounding `refusal/chain`'s ex-data (ruled out: `chain` unchanged); E4–E7.
- Swallowing catches touched: none added; the prepl `failure-text` catch names both
  classes (its declared case, the last guard of a total out-fn).
