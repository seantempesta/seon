---
type: research
status: current
tags: [research, audit, quality, render, flow, agent, docs]
---

# Fresh-tree quality audit, 2026-07-29

An independent read of `src/`, `test/` and `script/` after the 2026-07-28
day — the agents-are-flows rebuild (F1/F2), the custody revision, the context
blocks wave, the render walk and distance parameter, the UI wave, and the
day's fixes. Roughly forty commits. Nothing was trusted because a lane report
claimed it; every finding below carries file:line evidence, and the two
blockers were falsified in the REPL.

The owner's charge was "make sure we aren't repeating the mistakes of the past
and the source code and quality is being maintained." The short answer: the
code is in genuinely good shape, better than the tree it replaced by every
mechanical measure taken here, and the two real defects are both at the F2
transport seam rather than in the design. They are narrow and fixable.

Sanity check for this audit: `bin/test` — 429 tests, 1699 assertions, 0
failures, 0 errors. (`bin/test` and `src/seon/cluster/reply.cljc` carried
uncommitted changes from a lane in flight at audit time; the gate result
includes them.)

## Counts

| Rank | Count |
|---|---|
| Blocker | 2 |
| Debt | 5 |
| Polish | 4 |

Issue notes filed: five, one per blocker/debt finding, at
`docs/seon/issues/`.

## Blockers

### B1 — a lost stream clear paints a stale reply forever

Every agent streams onto ONE shared `(sliding-buffer 1)` conn per cluster
(`src/seon/cluster.clj:736`). The end-of-turn CLEAR is an ordinary entry on
that same conn (`src/seon/cluster/loop.cljc:509-512`), so any other agent's
partial silently replaces it. The render proc removes a snapshot ONLY on that
clear (`src/seon/render/web.clj:391-395`), and `text-html` renders from
`:seon.ai/partial` with no fact gate (`src/seon/render/root.clj:179-192`). The
agent's page then paints its last partial reply, cursor and all, forever.

Falsified directly:

```clojure
(let [ch (a/chan (a/sliding-buffer 1))]
  (a/offer! ch {:seon.cluster.agent/id "a"})                                  ; A's clear
  (a/offer! ch {:seon.cluster.agent/id "b" :seon.ai/partial {:seon.ai/text "hi"}})
  (a/poll! ch))
;; => B's partial. A's clear is gone.
```

This is the transport law applied where its own precondition fails. Loss is
free only when the value is re-derivable from facts or superseded by a newer
complete value; a clear is neither, and the value that supersedes it belongs to
a different agent. The right repair deletes the clear message and derives
staleness from the database value in `render-pass` — presence of a live
streaming run is the state — which also removes a channel message that carries
semantics nothing can reconstruct.

Uncovered. `test/seon/render/web_test.clj:494-546` proves reconnect-is-repaint
from facts and that no partial ROW can exist; it never exercises a retained
in-process `::streams` entry.

Note: [[a-lost-stream-clear-paints-a-stale-reply-forever]]

### B2 — fleet oversight throws, and throws a keyword as ex-data

`src/seon/oversight.clj:266-274` converts the render router's flat error value
into a `throw`, on the root page's render path, inside the cluster graph's
render proc — undoing the totality `seon.render/render` exists to provide. And
the throw cannot work: it passes `(:seon.error/kind rendered)`, a KEYWORD, as
`ex-info`'s data map.

```clojure
(try (throw (ex-info "boom" :seon.render/unroutable))
     (catch Throwable t (class t)))
;; => java.lang.ClassCastException — Keyword cannot be cast to IPersistentMap
```

So a failing fleet block becomes a confusing `ClassCastException` core fault
rather than a rendered error card, and the actual failure is never reported.
Both projections are seeded into root's live block set
(`src/seon/render/root.clj:236-240`). Latent — it only fires when the fleet
projection fails — but it is three lines of code that cannot behave correctly,
and `seon.render.block/surface` already shows the right shape.

Note: [[fleet-oversight-throws-a-keyword-as-ex-data]]

## Debt

### D1 — boot arms agents twice, and primes before the listener

`src/seon/cluster.clj:814-819` re-implements the derive-and-arm that
`armer-step` performs at `src/seon/cluster/agent.clj:415-427`. That is a second
mechanism for one job. It also primes each mailbox BEFORE `wake/route!`
registers at `src/seon/cluster.clj:822`, which contradicts the same function's
own docstring at lines 725-732 ("the armer prime comes LAST, after the
listener"). A message committed in that window is never routed, and nothing
re-primes: `armer-step` arms only unarmed agents, and `arm!` returns early for
an armed one (`src/seon/cluster/agent.clj:313`). The agent parks until an
unrelated wake arrives.

Note: [[boot-arms-agents-twice-and-primes-before-the-listener]]

### D2 — five namespaces claim they await implementation

`src/seon/render.clj:8`, `src/seon/render/block.clj:5`,
`src/seon/render/hiccup.clj:7`, `src/seon/cluster/registry.clj:8` and
`src/seon/cluster/export.clj:7` all still say every body throws `awaits
implementation`. All five are implemented and under test. Docstrings render
into agent context, so an agent reading the render router is told the router
does not work. `registry.clj` contradicts itself inside one sentence, recording
both its implementing commit and its own non-existence.

Note: [[five-namespaces-claim-they-await-implementation]]

### D3 — an ordinary agent's block set has no production caller

`seon.render.agent/seed-tx` (`src/seon/render/agent.clj:220-234`) installs the
identity / execution / peers / namespace block set the whole context wave was
built around. Its only callers are `test/seon/context_pilot_test.clj:62` and
`:364`. Boot seeds root alone (`src/seon/cluster.clj:537-543`), and the fresh
tree exposes no agent-creation route, so no agent in a running cluster has
received this set. The wave's proof is a pilot test — the class the house rule
names as NOT COVERED. Separately, `root/seed-tx` and `agent/seed-tx` are
byte-identical convergence wrappers around `block/install-tx`; the convergence
rule belongs in `install-tx`, once.

Note: [[an-ordinary-agents-block-set-has-no-production-caller]]

### D4 — a resolved issue is still open at severity blocker

`docs/seon/issues/prompt-assembly-bypasses-the-render-router.md` describes
prompt assembly bypassing `seon.render/render`. Today's context-blocks wave
landed exactly that: `src/seon/cluster/prompt.cljc:191` calls `render/render`,
and the namespace docstring now opens "THE PROMPT FORMATTER IS A RENDER-UNIT
APPLICATION, never a parallel system." The note is still `status: open,
severity: blocker`, so it heads the generated index and misinforms any agent
reading it about the system's most central seam. The house rule is to close and
archive with the commit plus proof in the same unit.

Not filed as a new note — the existing note is the record. The owning lane
should verify the remaining acceptance bullets and archive it.

### D5 — model output repaired by regex on the reader's error message

`src/seon/cluster/reply.cljc:186-231` matches the READER'S EXCEPTION MESSAGE
with `#"^Invalid (?:number|symbol|keyword|token)"`, then rewrites the model's
reply — commenting a line out, guessing a code suffix by character class in
`code-line?`. This is symptom-side patching of model output, pinned to
Clojure's reader message wording and its `ex-data` key names.

Recorded rather than filed: the owner already ruled the replacement on
2026-07-28 (commit f6797cee7 — "one general parser, classification at the
parse, condense the primitives"), and a lane held `reply.cljc` uncommitted with
258 changed lines at audit time. Worth confirming that the landed parser
removes these heuristics rather than relocating them; a general parser that
still consults the reader's message string would be the ported defect, not the
conversion.

## Polish

- `src/seon/render/web.clj:105` — `not-yet`, a hand-maintained "what this
  namespace does not do" list in source. A status diary in code; it will go
  stale exactly the way the `awaits implementation` docstrings did.
- `src/seon/oversight.clj:104,133,146` — `:seon.oversight/state` is a derived
  enum (`:parked` / `:mid-turn` / `:responsive` / `:mid-pass`). Derived and
  never committed, so the state-is-presence ruling holds; but the same story is
  expressible by presence (`:seon.cluster.run/id` present, ping reply absent)
  without introducing enum words a renderer must know.
- `CLAUDE.md` §4 attributes "~8.5 KB" to a parked agent;
  `src/seon/cluster/agent.clj:10` measures it per PROC, and each agent has two.
  The same section lists "schedule fires" among the shared per-cluster plumbing
  graphs; no such graph exists in `src/`.
- `script/seon/dev/config.clj:57,65` — "claimant" survives in two docstrings.
  The vocabulary table bans the word in favour of
  `:seon.agent.run/process`. Fully purged from `src/` and `test/`.

## What is actually in good shape

This section exists for calibration. Most of what was checked came back clean,
and several checks came back better than the standing rules require.

- **Schema discipline is near-total.** Reading every form in `src/`, only 16 of
  roughly 190 public `defn`s lack `:malli/schema`, and every one is principled:
  `connection?`, `file-lock?`, `interrupt-fn?`, `ctx?`, `mult?` and friends are
  predicates registered through `register-core-predicate!`;
  `seon/schema/datahike.cljc` is the Malli→Datahike bridge itself, where a
  schema would be bootstrap-circular; and `-main`.
- **No dead code.** A sweep for zero-caller private functions across `src/` and
  `script/` found none, after a day of deletion waves. The great deletion is
  leaving clean edges.
- **No shape-only tests.** Zero occurrences of `(is (map? …))`,
  `(is (some? …))`, `(is (vector? …))` and the rest across all of `test/`.
  Assertion density on the new sealed suites runs 5-10 per deftest
  (`armed_test` 3/31, `agent_test` 10/67, `context_test` 14/64,
  `oversight_test` 2/25, `block_test` 31/68). `web_test.clj:494-546` is
  wire-level: real sockets, counted Datastar patches, and an as-of walk over
  every real basis in the window. These are behavioral tests, not green paint.
- **`seon.render/render` is genuinely total** (`src/seon/render.clj:117-174`):
  every failure a flat value, resolution late and var-backed so a re-evaluated
  `defn` changes the next render, the kind set COMPUTED from the unit rather
  than registered. This is the no-hand-maintained-lists rule applied at the one
  place a registry was the obvious design, and it was not taken.
- **The render walk has no off-by-one and its elision is stable.** Root renders
  at the requested distance, each neighbour at `(dec hops)`, and `(pos? hops)`
  gates following — so distance 1 is root plus one hop and distance 0 follows
  nothing (`src/seon/render/walk.clj:401-412`). `refs`
  (`src/seon/render/walk.clj:289-315`) is explicitly ordered — forward then
  reverse, each group by attribute name — and deduplicated by target, with the
  equality-suppression reason stated in the docstring. Two derivations of one
  database value are the same value, so elision is stable and the node budget
  is a shared volatile rather than a per-branch guess.
- **Magic numbers are justified where they exist.** `port-floor` / `port-ceiling`
  (`src/seon/render/web.clj:620-636`) carry IANA and ephemeral-range reasoning;
  `ping-timeout-ms 20` (`src/seon/oversight.clj:34-39`) guards a genuine
  unobservable — a running transform cannot answer a ping — and says so; the
  one `Thread/sleep` in the retry arm (`src/seon/cluster/loop.cljc:681`) is a
  finite provider backoff schedule derived once. The render proc's coalesce
  floor is a floor over an observed commit, never a poll.
- **The purge held.** `epoch`, `lease`, `heartbeat` and `claimant` appear
  nowhere in `src/` or `test/`. Custody-is-presence went all the way through.
- **No per-tab memory growth.** The watched registration is a refcount that
  removes an agent at zero (`src/seon/render/web.clj:417-431`), and `::produced`
  is replaced wholesale each pass from the current watched set — so a
  long-lived tab and a departed tab both cost nothing durable.
- **The capture-before-provider ordering is right.** `src/seon/cluster/loop.cljc:576-612`
  commits the exact prompt bytes and contribution rows BEFORE the unobservable
  remote call, refuses the call outright when that capture is refused
  (`refused!` → `(report :error 0)`), and reuses the one capture across failover
  and backoff. There is no path where a capture commits and custody is lost, and
  none where a paid call fires without its durable evidence.
- **Orderly stop joins real events, not clocks.** Both cluster-graph procs and
  every agent graph publish their own completion from the stop transition, and
  `disarm-agents!` (`src/seon/cluster.clj:845-899`) joins each before releasing
  the branch connection. The render proc refuses to be constructed without its
  ports (`src/seon/render/web.clj:353-370`) precisely because a nil in-port
  would turn into a shutdown hang — an interface changed to express its
  dependencies, which is the ruling working as intended.

## Fixable by parallel lanes vs needing owner taste

Safely delegable, no design judgment required:

- B2 (oversight throw) — three lines, and `block/surface` shows the shape.
- D1 (boot arming) — call the armer's derivation; move the prime after the
  listener; add the window regression.
- D2 (stale docstrings) — mechanical, plus the `rg` check as the acceptance.
- D4 (stale issue) — verify the acceptance bullets, close and archive,
  regenerate the index.
- The four polish items, including the `CLAUDE.md` §4 corrections.

Needs owner taste before a lane touches it:

- **B1 (the lost clear).** The fix is small but the decision is not: deleting
  the clear message and deriving staleness from facts is a change to the F2
  streaming contract sealed yesterday, and it touches the transport law's
  wording ("loss is free" needs the precondition stated). Recommend the owner
  rule on derive-from-facts versus a per-agent conn before implementation.
- **D3 (the unseeded block set).** Whether ordinary-agent blocks are seeded at
  creation, and whether agent creation lands now, is roadmap sequencing rather
  than a defect to patch. The duplicate `seed-tx` collapse inside it is
  delegable once that is ruled.
- **D5 (the reply parser).** Already ruled and in flight; the owner should
  confirm at seal that the general parser removed the reader-message heuristics
  rather than relocating them.
