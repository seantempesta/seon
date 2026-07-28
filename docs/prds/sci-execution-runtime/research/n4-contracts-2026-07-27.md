---
type: research
status: active
tags: [research, ui, runtime, render]
---

# N4 contracts — package map, adoptions, and the seams still open

Companion to `n4-plan-2026-07-27.md`. That file is the reviewed PLAN; this
one records what package 1 SEALED, what the owner's 2026-07-27 night rulings
changed in it, and the exact boundary of each later package. It sequences
nothing — `plan/README.md` orders the program.

## 1. What the owner's rulings changed in the reviewed plan

The plan was written before the rendering rulings and the performance bar. Six
of its conclusions move.

| Plan said | Now | Why |
|---|---|---|
| A generic four-key unit identity (`unit/id`, `route`, `slot`, `agent`) — §4.3 | **The unit is a BLOCK.** Its address is (agent, block name), and `surface-id` derives the DOM id | The owner's ruling: root and agent views are one mechanism and that mechanism is blocks. The quarry agrees — `/` and `/agent/{id}` already shared one shim, one feed and one render entry, differing only in one block's data |
| Streaming is dead scope; decision 6 recommends staying non-streaming | **Decision 6 → Option B.** Streaming is a `seon.ai` option and a named seal-side revision | Owner: streaming as an option, with two named exercise goals |
| Blocks not mentioned; decision 2 registers at most the canvas pin | **The block family is durable**: `:seon.block/{name,priority}`, `:seon.cluster.agent/blocks`, plus the two render keys | An agent owns its complete block set in the database (`ui.md`), manifest-seeded and agent-installable. A pinned canvas is one block, so `:seon.render.canvas/content` is no longer a separate attribute at all |
| Nothing about frame cost | **16 ms is a design input**, measured by a committed harness | Owner: "no N=1 attempts… like 60fps fast" |
| Tailwind unmentioned | **Ported and live-proven** (`bin/css`, 65 ms) | Owner: port the tailwind-CSS build system |
| Whole-page morph inherited implicitly | **The morph target is the block** | See §3 — the single largest performance decision in the rung |

### Adoptions of the plan's eight owner decisions, one line each

1. **Decision 1 — Option A, adopted.** Landed owners stay; N4 adds only a small
   render family. Package 1 is `seon.render.hiccup` + `seon.render.block`; the
   landed `seon.render` router is composed with, not replaced.
2. **Decision 2 — Option A, adopted with the ruling's delta.** Process-local
   shapes stay process-local; the DURABLE registration is the block family
   rather than a bare canvas pin, because the owner made blocks the mechanism.
   A pinned canvas is a block whose html render is the pinned symbol.
3. **Decision 3 — Option A, adopted.** Exact complete database identity, never
   `>= :max-tx`. Package 2 owns the registration that holds it; package 1's
   `unit` already carries the exact immutable value under `:seon.db/db`, and a
   sealed test proves two database values render two pages.
4. **Decision 4 — Option A, adopted.** One fixed Flow graph per tab; the writer
   tag is measured before it is sealed. The benchmark's churn section names the
   measurement; package 2 seals the tag with the number.
5. **Decision 5 — Option A, adopted.** A pinned canvas is demand. Under the
   block model this needs no special case: a block is demand while it is in an
   agent's set, and a tab is never a prerequisite.
6. **Decision 6 — Option B, adopted (owner override).** `seon.ai` reopens as the
   ONE prefix producer. See §5.
7. **Decision 7 — Option A, adopted.** Upstream http-kit; the paused-read
   measurement is a named benchmark row; fork only on evidence.
8. **Decision 8 — Option A, adopted.** `src-old/seon/ui/html.cljc` is quarried
   rather than adopted — see §2 for the three things that change and why a
   `git mv` was the wrong answer.

## 2. Package 1, sealed

New files, all pure. Drafted as contracts, then implemented to green in the
same rung. Full gate **297 tests / 1227 assertions / 0 failures / 0 errors**,
from a 246/1095/0 baseline, with the two new suites contributing 48 tests and
110 assertions.

- `src/seon/schema/block.edn` — the block family, the `:seon.render/html` kind,
  the surface/page shapes. A new file rather than an edit to sealed `render.edn`,
  because the loader is one global population and file boundaries are editorial.
- `src/seon/render/hiccup.clj` — the grammar (`hiccup?`, real, a registered core
  predicate with an honest generator) and the serializer (stub).
- `src/seon/render/block.clj` — `surface-id`, `slot`, `blocks`, `unit`,
  `surface`, `surfaces`, `expand`, `page`, `install-tx`, and the selection
  shape `select` + `data-panel` (all stubs).
- `test/seon/render/hiccup_test.clj`, `test/seon/render/block_test.clj` — sealed
  suites, fixed seeds `202607280101`–`202607280202`, one fresh in-memory
  database per test.
- `bench/seon/render_bench.clj` — the harness. `clojure -M:test bench/seon/render_bench.clj`.
- `bin/css` — the Tailwind port. Live-proven: 65 ms, 42,589-byte output.

Live-verified beyond the suites: the block family installs through
`canonical-database-attributes` and round-trips a real transaction with both
render symbols stored natively as `:db.type/symbol`
(`tmp/n4_install_probe.clj`). That kills the quarry's `pr-str` EDN codec for
the two slots (`src-old/seon/agent/ctx.cljc:1606-1617`) — the old `:or` of
string-or-symbol-or-hiccup had no single Datahike value type, and restricting
the DURABLE slot to a qualified symbol removes an encode/decode pair from every
read.

### Three deliberate improvements on the quarry's serializer

1. **StringBuilder.** `src-old/seon/ui/html.cljc`'s own docstring says it avoided
   `StringBuilder` only for CLJS and that a JVM hot path would want one. The
   CLJS build is off and the budget is 16 ms.
2. **A strict grammar with a total serializer.** The old one silently elided a
   bare map child — its own comment flags this as a backstop for a check that
   should have happened earlier. Silent swallowing is the banned failure class:
   now `hiccup?` refuses, the block gets an error card with its name on it, and
   the page still renders.
3. **No throws.** The old `parse-tag` threw on an unparseable tag, on a path
   carrying agent values.

## 3. The morph target is the block

The quarry's live update morphs **one element: the entire page**. Any relevant
datom re-rendered every block, re-serialized the whole `<main id="app-view">`
tree and re-sent it (`src-old/seon/web/datastar.cljs:127-141,175-190`;
`src-old/seon/agent/ctx/driver.cljs:205-338`). A one-token transcript append
cost a whole-page render, serialization and write.

Measured, implemented, on this machine (`bench/seon/render_bench.clj
--trials 2000`, JDK 26.0.1, warmup 600):

| what | serialize p50 | serialize p99 | admit p50 | bytes |
|---|---|---|---|---|
| one activity row | 0.004 ms | 0.026 ms | — | 287 |
| transcript, 25 events | 0.043 ms | 0.100 ms | 0.008 ms | 8,221 |
| transcript, 250 events | 0.414 ms | 0.781 ms | — | 81,683 |
| whole page, 25-event transcript | 0.056 ms | 0.112 ms | — | 9,431 |
| whole page, 250-event transcript | 0.460 ms | 0.804 ms | 0.012 ms | 82,893 |

**The thesis in one row pair:** the same one-row change costs 287 bytes and
0.004 ms as a block morph, or 82,893 bytes and 0.460 ms as the whole-page morph
the quarry actually sent — **289× the bytes and 115× the CPU**. Bytes are the
half that decides survivability, because every morph is also a write and
http-kit's pending queue is unbounded.

Honest correction to the draft that preceded implementation: it reported
admission at 7.5 ms p50 for a whole page and argued the block thesis from CPU.
That number was real but it was measuring a BAD PREDICATE, not an inherent
cost — see §3a. The thesis survives on bytes and on a 115× CPU ratio; the
original framing did not, and is retracted rather than quietly restated.

This costs one function (`surface-id`) and changes the shape of everything
downstream: interest, registration memory and equality suppression are all keyed
by block, and the 32-tab falsifier's "one evaluation" is one evaluation of one
block. Two consequences the seal must carry:

- the quarry's `#morph`-scoped CSS animations
  (`resources/public/css/input.css:227-298`) were already dead against
  `#app-view` and must be re-pointed at surface ids;
- Datastar's client-side pane signal (`$selected`, `data-signals__ifmissing`)
  survives unchanged and is now more valuable, not less.

## 3a. The fused-walk experiment, answered: NO

The draft proposed fusing admission into serialization — one walk instead of
two — on the strength of admission costing 7.5 ms against 0.45 ms to serialize
the same tree. It was flagged as a contract delta to measure, never to assume.
Measuring it killed it, and the way it died is the useful part.

The 7.5 ms was not the cost of walking a tree. `hiccup?`'s first draft tested
nine cheap predicates before `vector?` — so every element paid nine misses —
and destructured with `[head & body]`, allocating a seq per element. The
serializer, written for the budget from the start, indexed with `nth`/`subvec`
and tested the hot shapes first. Same tree, same walk, 18× apart for reasons
that had nothing to do with how many walks there were.

Reordering the branches and indexing instead of destructuring:

| `hiccup?` over a whole page, 250 events | p50 | p99 |
|---|---|---|
| before | 8.066 ms | 9.253 ms |
| after | 0.012 ms | 0.037 ms |

**~670×, implementation only — no contract change, no schema change, both
suites unchanged and green.** Admission is now 2.6% of serialization, so fusing
would save 2% of one walk in exchange for entangling the grammar with the
emitter and giving `surface` a string where it needs a hiccup VALUE for slot
expansion. Rejected on the measurement.

The general lesson, recorded because it will recur: *an expensive-looking stage
is a reason to read the stage, not a reason to restructure the pipeline around
it.* The restructuring was the interesting answer and the boring answer was
correct.

One defect the harness itself had, caught by adding the byte column: the "one
activity row" scenario indexed the transcript's ATTRIBUTE MAP, which the
grammar rightly refuses, so it had been timing a refusal and reporting
0.000 ms. The timing column read that as "very fast"; only the 0-byte reading
made it obvious. A benchmark is a check, and a check that reports health when
its subject is absent is worse than no check.

## 3b. Generic default + specialist, named as a reusable shape

The owner's architectural ruling (2026-07-27 night, error rendering) asked N4's
block contract to name this. It is `seon.render.block/select` plus
`:seon.render/selection`, and the two halves of the ruling turn out to be one
design rather than two requirements.

**Every kind has a generic default; a producer that knows more points the key at
a specialist, chosen where the unit is built, from the value's own attributes.**
`select` takes the value and the producer's ordered rules and returns a symbol.
First accepting rule wins — ordering is the producer's judgement and nothing
scores specificity. No specialists is the ordinary case and needs no code.

**Why the consumer side then disappears.** Because the key ON the unit is the
whole answer, a consumer hands the unit to `seon.render/render` and takes what
comes back — there is no second call site to reach for, because the
specialist's name never leaves its producer. That is what makes "consumers
reach an error's renderings only through the router" a property of the code
rather than a rule people have to remember. `seon.error`'s steering prose stops
being a function anybody calls and becomes the DEFAULT that `:seon.render/ai`
points at; the malli-violation explanation becomes a specialist the error
producer selects from `:seon.error/kind`.

**It is a producer's rules, not a registry.** The vector belongs to the one
owner of the family, is built from that family's own attributes, and is never
consulted by anybody rendering. A second family's rules are invisible to it.

**Totality, because selection runs on the error path.** A rule that throws or
does not resolve is treated as not accepting and the next is tried, so a broken
rule costs its own specialist and never the render. Both halves are late-resolved
symbols, so re-evaluating a rule or a renderer changes the next render exactly as
the default does.

`data-panel` is N4's instance: `:seon.render/html`'s generic default, any value
as a readable panel, bounded by the same `:seon.sci.admit/caps` the eval door
carries rather than a second set of size dials. It is the lowest-fidelity
renderer on purpose — a clever floor would compete with the specialists instead
of backstopping them.

## 4. Later packages — boundaries

**Package 2 — the live pipeline and the problems page served.** Prerequisites
that are NAMED revisions, not guesses:

- `deps.edn`: promote `dev.data-star.clojure/sdk` and `.../http-kit` out of the
  old-facing `:host` alias (`deps.edn:133-138`) into the default deps, and add
  `resources` to `:paths` so `/css/output.css` serves off the classpath the way
  `src-old/seon/web/server.clj:235-237` already does.
- `seon.render` + `src/seon/schema/render.edn` + `test/seon/render_test.clj`:
  the literal accretion the router's own docstring scoped — one `:or` in
  `:seon.render/projection` and one branch, so a non-symbol declaration is its
  own output. `render_test`'s `a-literal-declaration-is-not-yet-routable-and-says-so`
  is the test that changes first, exactly as its comment anticipated.
- ~~`seon.problems`: add `:seon.render/html`~~ **DONE** — see §4a.
- `seon.cluster` boot: seed the root agent's block set. Root already exists at
  boot (armed-idle, zero token cost); its blocks are manifest data.
- `config/default.edn`: the seeded block set — THE defaults document.

Then the pipeline itself: the render-interest listener (separate from
`seon.cluster.wake`, matching interests BEFORE coalescing), per-block
registration memory, equality suppression, `mult`, per-tab fixed Flow graph,
one SSE per tab, and the Datastar shell. Plan §4–§7 and contracts C2/C3/C4/C6/C8
carry over unchanged except that "unit" now reads "block".

## 4a. The problems page, landed

The owner's ordering put this first because it is the fastest browsable proof,
and it turned out to be the cheapest thing in the rung: **one key on the value
and two functions**, with no router change, no registration and no
page-specific machinery.

- `seon.problems/problems` gained `:seon.render/html`, beside the
  `:seon.render/log` and `:seon.render/ai` it already declared.
- `html-report` is the third projection of the one derivation — groups by
  family, worst-recurring first, one row per problem with the recurrence count
  ON the row.
- `block` is the block projection: it derives `problems` at the unit's own
  exact database value, so the surface is a pure function of the database and a
  reconnect is a repaint. It renders the healthy case, because only a block
  knows its surface must occupy space either way.

Two things it refuses to default, both for the same reason. `live-processes`
is the one input a database cannot answer, so an absent set gets a legible card
rather than a guess — `#{}` would invent problems (every held run reads as
wedged) and "assume alive" would hide them. And the healthy state is rendered
by the block, never by `problems`, which still declares no projection for `{}`.

**The html twin coalesces exactly as the ai twin does**, and that is a sealed
test rather than a note: the quarry's transcript coalesced repeated failures
for the agent and not for the human, so a thrash burst was one line in the
prompt and a hundred rows in the page. Five occurrences of one signature are
one row with a count on it.

Its absence property caught the new key immediately and correctly — the suite
enumerates the exact key set a problems value may carry, so adding a projection
is a deliberate edit rather than a drift.

The CSS landed with it (`resources/public/css/input.css`), in SEMANTIC classes
rather than utility strings. A block's html render is authored in Clojure and
may be authored by an AGENT; utility soup in that position is unreviewable and
cannot be restyled without editing every renderer. `bin/css` compiles it in
63 ms.

## 4b. Recursive unit rendering — and the hole it exposed in package 1

Owner direction, 2026-07-27 night: a unit's rendered form embeds its REFS as
units, the expander follows connections asking the router per node, bounded by
depth/node budget plus a visited set — the entity graph can cycle where value
trees could not. And "the `/data` browser is ESPECIALLY that."

**Does package 1's `expand` already do this?** Same shape, strictly narrower
case, and — as shipped — NOT BOUNDED. It resolves slots by block name within one
agent's finite set rather than following refs, and it had a visited set and no
budget.

That gap is real and I found it by trying to falsify the claim rather than
asserting it. A visited set is per PATH: it refuses cycles and permits fan-out.
Twenty-two blocks with **no cycle anywhere**, each slotting the next twice,
expanded to millions of nodes and **OOM'd the JVM** (`tmp/n4_expand_blowup.clj`).
The same input now completes in 7.8 ms at 8,212 nodes.

So the recursion property needed naming NOW, not in package 2 — not because of
ref-following, but because the boundedness discipline ref-following will reuse
did not exist in the function that is supposed to own it. Resealing later would
have meant resealing after consumers existed.

What landed:

- `expand` takes `:seon.sci.admit/caps` — the SAME four dials, because a graph
  that fans out and cycles is the problem the admission codec already solved and
  a second set of size dials would drift from the first;
- node budget and depth budget are separate, because a long thin chain and a
  wide DAG are different ways to be too big, and each gets its own legible note
  in the hole that stopped;
- the walk is depth-first, left to right, so one input always elides the same
  holes — equality suppression is meaningless otherwise;
- `page` takes `:seon.render/page-request` carrying the caps.

A second real bug fell out of the totality test for a starved budget: `walk` was
mapping over the element's TAG and attribute map as if they were children, so an
exhausted budget could replace a `:div` with an elision span. The grammar caught
it, which is what the grammar is for. Elements now keep their head and only
children are walked.

**Package 2 owns ref-following and `/data`.** Following a connection is the same
act as filling a slot — ask the router for a node, descend, count it, stop at
the budget or at a node already on the path — so it is a new owner (it needs the
database value and a per-node router call) reusing this discipline, not a new
discipline. Its recursive falsifier is the owner's own example: an error notice
→ its message → the run → the receipts, as one expanded page. `/data` is the
same mechanism with a path cursor: the quarry's drill is paged `get-in`
navigation into any nested value, so its pager IS bounded expansion where the
cursor says where to resume rather than eliding.

## 4c. Package 2, landed so far

Three slices, each with its own sealed suite. The my.message lane held
`config/default.edn`, `ui.md`, `loop.cljc` and the message schemas throughout,
so root's seeded block set and any further `ui.md` accretion are sequenced
after them, not skipped.

**The literal accretion, and the split it forced.** A literal declaration is now
its own output — a verbatim string for a prose kind, a vector for a hiccup kind
— so a block that just says a fixed thing needs no function. Widening
`:seon.render/projection` to admit literals BROKE every block transaction: the
bridge derived `:db.type/string` from the union's first branch. That is the
durable/runtime split proven rather than argued. The schema stays a symbol
because the schema IS the durable side; `seon.render/declaration?` is the
code-level contract that admits both. The admissible shapes stay narrow because
`kinds` derives kinds from the unit, so admitting numbers would silently turn
`:seon.render/priority 3` into a kind — now pinned by a test.

**Ref-following**, per §4b, with task #11's falsifier sealed over real facts.

**The page on a socket.** Measured on a real http-kit server, ephemeral loopback
port, real SSE:

| event | patches | bytes |
|---|---|---|
| initial paint, two blocks | 2 | 218 |
| commit changing one block | 1 | 102 |
| commit changing no projection | 0 | 0 |

The middle row is the rung's thesis on the wire: the block that reads nothing is
never re-serialized and never re-sent. `seon.render.web/not-yet` enumerates what
this slice does NOT do — interest matching, the shared registration, the per-tab
Flow graph, the isolated sink — because a feed that quietly did less than the
design says would be the absence-read-as-health class on the most visible
surface in the system.

Three bugs the instruments caught, all mine and all worth keeping:

- the first live page came back 15 bytes, just the doctype: `page` returns a
  VECTOR OF ELEMENTS, which is not hiccup as a child, and the grammar refused
  the whole document rather than emitting something plausible. It needed `seq`.
  A serializer that guessed would have shipped a subtly wrong page;
- the SSE proof reported zero events for a feed that was working —
  `BodyHandlers/ofLines` does not yield on a stream that never ends;
- then it reported the PREVIOUS paint as the current one, because one `.read`
  returns one chunk. That briefly looked like a suppression bug in the server.
  Reading SSE correctly is now part of the test, both traps written down.

**Package 3 — the interaction model.** The message box does not exist in
`src-old/` at HEAD; it was deleted in `9d9e870bd`, and the quarry agent
recovered its exact code (`git show 9d9e870bd^:src/seon/web/datastar.cljs:1142-1172`)
— a static `<form>` OUTSIDE the morph target, `data-on:submit` running
`@post(url, {contentType:'form'}); $text=''`, `data-bind="text"`, `required`,
autofocus. Restore that shape against the landed trigger: `POST /message`
commits one `:seon.cluster.message` fact, `seon.cluster.wake` fires, the loop
opens a run. No new door, no `/chat` handler, no reply channel — the reply
appears because the transcript block re-renders from committed facts.

The existing `/agent/{id}/call` door (`src-old/seon/web/reactive/call.cljs`)
is a separate, later package: it commits an interaction fact after a capability
check, and it has no core-owned control, so a message box built on it would need
a blessed handler. The message fact is the simpler and already-landed path.

**Package 4 — the streaming exercises.** §5.

**Package 5 — the transcript, redesigned.** The owner's "kind of hard to
follow" has seven concrete causes in the quarry, each an acceptance row:

1. no turn grouping at all — the block's own docstring says turn boundaries are
   not containers, so eight evals from one turn are eight unrelated siblings;
2. three visually incommensurate row types (bubble, activity row, usage line)
   interleaved by timestamp with no shared gutter or container;
3. eval rows carry no result and no code, and no way to get either — the
   detail renderer exists but only on the debug surface;
4. two independent truncation regimes with one italic line between them;
5. exactly one timestamp in the whole HTML transcript (none on messages, none
   on the streaming partial, a duration on evals);
6. human-vs-agent is signalled only by `ml-auto`/`mr-auto` and a tint; peer and
   system messages have no treatment at all in the live path;
7. the AI twin coalesces repeated failures (10× one signature → one line) and
   the HTML twin does not, so a thrash burst is ten identical rows.

`src-old/seon/render/chat.cljc` has the nicer bubbles and timestamps and is
DEAD (zero callers) — quarry it, do not wire it.

**Package 6 — canvas.** Unified: any agent function returning hiccup, evaluated
through N3's guarded door, so the old infinite-loop special-casing comes free. A
pinned canvas is a block whose html render is the pinned symbol; the quarry's
separate structural checker (`src-old/seon/render/canvas.cljc:187-190,289`) is
superseded by `hiccup?` plus admission — two validators is one too many.

## 5. The streaming seam, named exactly

The design exists; nothing here invents it.

**Where it is written.** `docs/seon/reference/llm-adapters.md:545-563` separates
two independent facts: `:seon.ai/wire-stream?` chooses streaming transport, and
`:seon.ai/reply-evaluation` chooses `:first-form` versus `:batch`. Batch
evaluation reads the stream to natural EOF, retains provider usage, parses once
and evaluates every form. Its explicit sentence — *"Partial display is
presentation-only and cannot affect transport, parsing, usage, or evaluation"* —
is the invariant the exercise goals must not break.

**The wire.** Two cores, `:openai-compat` and `:anthropic`, descriptor rows per
provider (`llm-adapters.md:149-160`). For the default DeepSeek row: `data:` SSE
framing, `[DONE]`, `stream_options.include_usage`, and the portable fold retains
the newest usage map independently of choices (the Gemini qualification proved
cumulative usage can ride content chunks).

**The fresh seams it lands on.**

- `src/seon/ai.cljc` is the ONE producer. Today `request-body` sends
  `"stream" false` (`:189`) and `complete` does one synchronous
  `HttpResponse$BodyHandlers/ofString`. The revision is a streaming branch on
  the same function: `BodyHandlers/ofLines` over the JDK client already in use
  — no new dependency — folding SSE lines into (a) accumulated text and (b) the
  newest usage map. **Compose, do not collide**: the failover work
  (`cd9f41fb3`, `4f93d6587`) owns `targets`, `disposition`, attempt facts and
  the transport-phase evidence in the same file, and the streaming branch must
  keep every one of them. Two specific interactions to settle in that
  contract: `response-started?` becomes observable EARLIER under streaming
  (the first chunk proves it), which makes the disposition strictly better
  informed, and a mid-stream failure is a transmitted request — the no-retry
  ruling applies unchanged.
- **Partials land on a no-history churn attribute.** The pattern in root
  `AGENTS.md` names streamed reply partials as its own example:
  cardinality-one, unindexed, `:seon.db/no-history?` (the bridge already
  supports the facet, `src/seon/schema/datahike.cljc:161`), COALESCED
  complete-value snapshots at a config cadence, published by an isolated
  non-blocking sink, retracted at the terminal in the same transaction that
  settles the real fact. Presentation may lag or drop; it may never slow the
  model call. The quarry's own attribute
  (`src-old/seon/ai/attempt.cljc::partial-text`) and its settle dial are the
  shape to translate, not the code to port.
- **Live token counts derive from the same stream.** No second mechanism and no
  counter entity: the usage map the fold already retains is the count, and the
  count's block is an ordinary block whose html render reads it. This is the
  cheapest possible proof that the block mechanism handles high churn, which is
  why it is the first exercise.
- **Streaming tokens to the interface** is then the same block reading the
  partial attribute instead of the usage map. One churn attribute, two blocks,
  two morph targets — and because the morph target is the block, a streaming
  reply repaints the reply and nothing else. That is the whole exercise.

**The genuine delta to flag rather than choose:** the plan's O14-era assumption
was that partials would be process-local render-unit inputs
(`plan/README.md:148-158`, `ui.md:480-490`), while root `AGENTS.md:624-642`
requires a coalesced no-history database attribute. Both are owner-blessed
text. The owner's instruction here names the no-history attribute explicitly,
so package 4 takes the database attribute — and the reconciliation of
`AGENTS.md` and `ui.md` that finding #19 of the plan review already demanded
must land with it, so the two authorities stop teaching opposite defaults.

## 6. Tailwind — ported

`bin/css`, one file, no config to find (v4 is CSS-first). What the port
DECOUPLED: the same argv lived in `script/seon/dev/artifact.clj:1249-1253`
inside `build-source!`, with `output.css` a required artifact output folded into
`css-digest` → `application-digest` (`artifact.clj:725,1463-1476`) — so a
stylesheet edit invalidated the application artifact. Nothing of that survives.

The input already scans the FRESH tree (`resources/public/css/input.css:13-15`
globs `src/**`), so the port changed no scanning. Live: 65 ms, 42,589 bytes.

Three things the seal should note:

- the runtime **safelist** (`input.css:17-52`) is what keeps agent-emitted
  classes alive, and it cannot be replaced by scanning: agent code lives in the
  database, not on disk. It is not a hand-maintained exception list in the
  banned sense — it is a published contract, and the quarry enforced exactly
  that with a test asserting the canvas docstring's vocabulary is a subset of
  the safelist. Re-adopt that test with the fresh canvas owner;
- `@source` still points only at `src/**`; the moment a block's classes live
  anywhere else, that glob is the thing to fix;
- **JetBrains Mono has no `@font-face` and no bundled file** anywhere in the
  tree — the stack is a fallback chain, so the design language renders as
  `SF Mono` on any machine without it locally installed. An owner-taste call:
  bundle the woff2 (a few hundred KB, offline, exact) or accept the fallback.

## 7. Owner-taste surfaced, not baked

1. **The block namespace.** `:seon.block/*` + `:seon.cluster.agent/blocks` here;
   `ui.md` still says `:seon.agent.ctx/*` + `:seon.agent/ctx`. The fresh tree
   already renamed `seon.agent.*` → `seon.cluster.agent/*`, and "block" is the
   settled noun, so the architecture wants the same rename at seal.
   Recommendation: keep `:seon.block/*`, accrete `ui.md`.
2. **The durable html slot is a qualified symbol only** — no literal hiccup and
   no verbatim string in the DATABASE, which is what kills the pr-str codec.
   Literals remain available on runtime units once the router accretion lands.
   Recommendation: keep it; the loss is a convenience the manifest never used.
3. **Top-level is derived** (a block no other block slots) rather than a stored
   `:layout` flag. Recommendation: keep; it is the `layout-vs-surface is a role`
   rule made executable.
4. **Fusing admission into serialization** — measured, not assumed. The number
   above says it is worth trying.
5. **The font.**
6. **`token-cap`/`cap-keep`** — the quarry's block carried two prompt-budget
   dials this contract omits. They belong to the context/prompt owner, not to
   the render mechanism. Recommendation: leave omitted until the prompt owner
   asks.

## 8. Concurrency note for whoever seals this

A lane was actively editing `src/seon/error.clj`, `src/seon/problems.clj`,
`src/seon/cluster/loop.cljc`, `src/seon/schema/{error,problems,loop,run,instrument}.edn`
and their suites while this package was drafted — the error-rendering
correction. Nothing here touches any of those files. Two sequencing
consequences:

- the `seon.problems` html revision named in §4 must land AFTER that lane, not
  beside it;
- `:seon.render/html`, `:seon.render/hiccup`, `:seon.render/selection` and
  `:seon.render/specialist` are declared in `src/seon/schema/block.edn`, and the
  loader is one global population that REFUSES a duplicate across files. If the
  error lane needs the html kind, it references these rather than declaring
  them.

Observed while measuring: a mid-edit checkout of that lane produced three
deterministic `seon.error-test` failures that vanished on its next save. Those
were not caused by this package — verified by removing these files and
reproducing the same three — and are recorded only so a reader of the gate log
is not misled.

## 9. Filed while drafting

- `docs/seon/issues/a-self-referential-schema-overflows-the-stack.md` — a
  recursive registered schema `StackOverflowError`s in
  `seon.schema/bind-predicates` with no message naming the declaration, and the
  failure lands on the next unrelated caller rather than on the producer.
  Does not block N4 (hiccup is a named predicate, which is the better answer
  for a grammar anyway), but it is a booby trap for the next producer.
