---
type: research
status: active
tags: [research, agent, flow]
---

# Live delegation RE-DRIVE — parent→spawn→delegate→report→synthesize (2026-06-28)

Second end-to-end LIVE drive of multi-agent DELEGATION on the freshly-reset
default pod (7890, reset ~02:18 — toolkit/night-batch indexed), re-running the
exact flow that FAILED end-to-end in [[delegation-drive-2026-06-28]] now that #72
(spawn-contract: `start!` arms the child in-process + returns a usable id + spawn
verbs in the signature whitelist) and #73 (report=DATA / message=POINTER system
text + done→report tie, `complete` carries a pointer) have landed and gone live.
Every claim below is a live observation (verbatim evals preserved), not inference.

## TL;DR

**Delegation NOW COMPLETES END TO END — the prior drive's #1 question is YES.**
A real DeepSeek parent spawned workers, each worker did real research, STORED its
findings as schema'd `my.kb.*` data, reported back via a SHORT pointer + `complete`
(no truncation dead-end), the parent QUERIED both stored datasets and synthesized
a genuine, data-grounded recommendation to the human (a markdown comparison table,
counts read from the real datoms — no fabrication in the synthesis). This is a
categorical improvement over the prior drive, which never delivered anything.

**BUT it required one human nudge to finish, because of a NEW decisive bug: the
hop-cap exhausts on the SECOND round of delegation.** When a worker's `complete`
wakes the parent, the parent's *next* outbound inherits the climbing hop count;
the re-spawned SQLite worker got its task at hops 3, so ITS report-back was hops 4
= AT `hop-cap` (4) → **the wake gate silently REFUSED it, the parent never woke,
the synthesis stalled.** A human "any update?" message (hops 0, resets the chain)
un-stuck it and the parent synthesized immediately. The first round (DuckDB,
report at hops 2) worked with zero intervention.

Two of the prior drive's fixes are clean wins (#3 arm-in-process, #4 spawn
discoverable, #6 worker-reports-via-complete, #1 report-truncation). One regressed
in a NEW form: the docstring's recommended `(let [c (start! …)] (message (:id c)))`
recipe is BROKEN because `start!` is `^:async` → returns a Promise, so `(:id c)`
is `nil` — which cascaded into 5 orphan children for 2 needed AND a fabricated
ghost id (the prior drive's #2/#7 reincarnated, now caused by the async trap).

Drive shape: root minted parent `XeG-2606282241` (purpose = "recommend DuckDB vs
SQLite for an embedded analytics app by spawning one researcher per option, each
stores findings as data, then synthesize + report"), `start!` armed it in-process,
human kickoff sent. Parent spawned (eventually) `EIP` (DuckDB, worked) + `SOv`
(SQLite, worked) plus 4 orphans. All 7 test agents terminated at the end.

## Did the prior 6 findings get fixed?

| # | Prior finding | Status this drive | Evidence |
|---|---|---|---|
| 1 | Report-back truncation dead-end | **FIXED** | Both workers reported via a SHORT pointer (`message/agent` + `complete` with id + 1-line summary + the query to run). Nothing truncated. |
| 2 | Within-turn child-id fabrication | **PARTLY FIXED → NEW VARIANT** | `start!` now returns the real id (#72) and the standalone-spawn→literal-message path works. But the docstring's `let`-bind recipe yields `nil` (async Promise) → orphans + the parent STILL fabricated a ghost id `BgY`. |
| 3 | Unarmed minted child (#30) | **FIXED** | `start!` armed the parent + both workers in-process; every worker woke on the parent's message with NO out-of-band rearm. `armable-agent-ids` showed the child armed immediately after spawn. |
| 4 | Spawn verb undiscoverable | **FIXED** | `verb-signature-whitelist` now has `:seon.agent #{create! start!}`; the parent discovered and used `agent/start!` unaided. |
| 5 | `message!` invalid-schema on agent-`from` (instrumented) | **NOT RE-TESTED** | Agent path is uninstrumented so the drive can't exercise it; left open. |
| 6 | Worker peters out without reporting | **FIXED** | Both workers called `(complete …)` (which messages the parent). The #73 system text ("if you were spawned by another agent, finishing MEANS reporting back") + the done→report tie did the job. |
| 7 | Honesty gap in coordination narration | **RECURRED** | The parent told the human "SQLite → BgY-2606282242" — a fabricated ghost id (real one was never validly messaged). Self-corrected later via an `entity` lookup. |

## What actually happened (timeline)

- **Spawn-contract proven up front.** `start!` (called as root) returned
  `{:seon.agent/id "XeG-2606282241"}` and the child was immediately in
  `armable-agent-ids` — #72 arm-in-process confirmed before any drive.
- **Parent round 1 — plan + spawn.** Planned via `todo/plan!` (one `:after`
  validation miss, self-corrected), told the human it was delegating, then
  attempted the docstring recipe
  `(let [c (agent/start! {…})] (message/agent (:seon.agent/id c) …))` for BOTH
  options → both failed `:seon.db/invalid-ref-child … got [:seon.agent/id nil]`
  (the async-Promise bug). Each failed `let` STILL spawned a child (start! ran),
  so orphans `jsy`/`gUB` were born unmessaged.
- **Parent self-corrected (partially).** It re-spawned DuckDB STANDALONE
  (`(seon.agent/start! {…})` → rendered `{:seon.agent/id "EIP-2606282242"}`),
  then in a SEPARATE form `(message/agent "EIP-2606282242" …)` → `ok? true` — the
  working two-form pattern. For SQLite it tried `(def sqlite-child (agent/start! …))`
  (stored the Promise), `(message/agent (:seon.agent/id sqlite-child) …)` → nil →
  fail again; then **fabricated** `BgY-2606282242`, messaged the ghost
  (`Nothing found for entity id … BgY`), and reported the ghost to the human.
- **DuckDB worker `EIP` — full loop, zero intervention.** Designed its own
  `my.kb.duckdb.source/*` + `…finding/*` schema, stored 6 sources / 25 findings
  (one oversized transact truncated → recovered next turn), then reported a SHORT
  pointer (`message/agent` hops 2) AND `(complete "DuckDB research stored as 6 …
  Query: (db/query …)")`. Parent woke from the report (hops 2 < cap), queried
  EIP's data, found SQLite empty + the `BgY` entity `nil`, **re-spawned a real
  SQLite worker `SOv-2606282246`** and messaged it correctly — at **hops 3**.
- **SQLite worker `SOv` — full loop, report REFUSED.** Stored 6 sources / 19
  findings (it LEARNED from a first truncation and switched to small incremental
  transacts), then `(complete …)` with a clean pointer — at **hops 4**. The wake
  gate refused it; the parent stayed idle. **Delegation stalled at the 2nd round.**
- **Human nudge → synthesis.** A human "any update?" (hops 0) woke the parent; it
  queried BOTH `my.kb.duckdb.*` and `my.kb.sqlite.*` via pull and delivered a
  data-grounded markdown recommendation (table + "25 DuckDB findings + 19 SQLite
  findings" — the REAL datom counts) to the human. End-to-end complete.

## Ranked NEW frictions / bugs (these feed the next loop)

### 1. `agent/start!` is `^:async`; the docstring's `let`-bind recipe yields `nil` — HIGH

`start!` returns a `js/Promise`, so reading the id in the SAME form gives `nil`:

```clojure
;; what the parent did (straight from the docstring's "let-bind it" guidance):
(let [duckdb-child (agent/start! {…})]
  (message/agent (:seon.agent/id duckdb-child) "…"))
;; => {:seon.db/ok? false … "expected map or :seon.db/ref, got [:seon.agent/id nil]"}
```

Live-confirmed in isolation:
`(let [r (agent/start! {…})] (instance? js/Promise r)) => true`,
`(:seon.agent/id r) => nil`. The `maybe-await-value` auto-await only fires on the
eval's FINAL returned value, never on an intermediate `let`/`def` binding — so the
Promise hasn't resolved when the id is read. This single bug caused the WHOLE
cascade: 5 children spawned for 2 needed (`jsy gUB EIP kgZ KvB`), the SQLite
fabrication, and the honesty gap (#7). The ONLY reliable path is two separate
forms (spawn alone → read the rendered literal id → message it), which the agent
found by luck for DuckDB but not SQLite.

- Site: `seon.agent/start!` docstring (agent.cljs:495-517) + the ctx system text —
  both SAY "let-bind it / RETURNS {:seon.agent/id …}" which is FALSE on the async
  path.
- Fix shape (pick one): (a) make the docstring + system text teach the TWO-FORM
  pattern explicitly (spawn → copy the rendered id → message next form) and warn
  that `(:seon.agent/id (start! …))` in one form is nil; OR (b) provide a
  `spawn-and-message!` combinator that internally `await`s; OR (c) teach
  `(let [c (await (start! …))] …)` (works in an `^:async` agent form) — but agents
  don't reach for `await` and the always-on text discourages bare awaits, so (a)/(b)
  are safer.

### 2. Hop-cap exhausts on multi-round delegation — HIGH (decisive end-to-end blocker)

The hop count accumulates across the WAKE CHAIN, conflating "delegation depth" with
"ping-pong depth":

```
human → parent          hops 0   (kickoff)
parent → EIP            hops 1
EIP → parent (report)   hops 2   ✓ parent wakes
parent → SOv            hops 3   (re-spawn, parent woken at hops 2 → +1)
SOv → parent (report)   hops 4   ✗ REFUSED (>= hop-cap 4)
```

Verbatim log proof:

```
seon.agent.loop: WAKE REFUSED for agent XeG-2606282241 — message jdG-2606282251
hops=4 reached hop-cap 4 (agent↔agent ping-pong guard). A human message resets
the chain (hops 0).
```

`SOv`'s `complete` returned `:idle` (looked successful) and the report row sits in
the DB at hops 4; the parent has NO inbound, NO error — a SILENT deadlock. It is
only recoverable by an external human message (which I sent; the parent then
synthesized instantly). A delegation tree just two rounds deep — entirely normal
for "spawn workers, collect, maybe re-spawn one" — hits this. The hop-cap is a
ping-pong guard; a parent↔child task/report exchange is NOT a ping-pong and
shouldn't consume the same budget.

- Sites: hop accounting `waking-hops` (message/internal.cljs:39-65), enforcement
  at wake (loop.cljs:322-339), cap `seon.warn/hop-cap` = 4.
- Fix shape: don't inherit wake-chain hops across a parent→child DELEGATION edge —
  either reset hops for a message whose `to` is the caller's own child (or whose
  `from` is the recipient's parent), or count delegation depth separately from
  ping-pong depth. At minimum, surface the refusal back to the sender (dead-letter)
  so `complete` returns an error instead of a false `:idle` (ties to the survey's
  "no dead-letter for hop-cap refusals" gap).

### 3. Oversized STORE transact truncates — MEDIUM

The truncation problem the report-back guidance solved RESURFACES on the STORE
path. Both workers packed all findings for an area into ONE `db/transact!` form
that exceeded the output budget and truncated mid-string (`ok? false`, empty
result). EIP recovered by retrying; SOv recovered by switching to small
incremental transacts (genuinely good adaptation). Net: ~24% of SOv's evals failed
(8/33), mostly truncated stores. The "store as data first" guidance teaches the
PATTERN but doesn't warn that a SINGLE store form ALSO has the output-budget limit
— store incrementally / one source per transact.

- Fix shape: add to the report=DATA guidance "store in small transacts — one
  source/finding per form; a giant single transact truncates just like a giant
  message." Same root cause as friction #1 of the prior drive, different surface.

### 4. Fabrication recurred under confusion — MEDIUM (was prior #2/#7)

Caused by friction #1: once the `let`/`def` paths returned `nil` ids, the parent
invented a plausible `BgY-2606282242`, messaged the ghost, AND reported it to the
human as fact. It self-corrected only because its context later showed
`(seon.db/entity [:seon.agent/id "BgY-…"]) => nil`. Un-nudged, the honesty gap
reaches the human. Fixing #1 removes most of the pressure to fabricate.

### 5. Bare agent-id symbol evals — LOW (eval noise)

The parent repeatedly evaluated bare ids as forms — `(EIP-2606282242)`,
`(BgY-2606282242)`, `(6 sources, 17 findings)` — all `ok? false` (an id/number is
not callable). It was trying to "inspect a worker" or narrate, with no obvious
verb to do so. Minor wasted evals; an `(agent-status "<id>")`-style inspect verb
would absorb the instinct.

### 6. `todo/plan!` `:after` first-attempt miss — LOW (recurring, self-correcting)

Both parent and EIP failed their first `plan!` because a node referenced by
`:after` had no matching `:ref` (the implicit label, e.g. `"synthesize"`). The
error message is excellent and both fixed it next form. A one-line example in the
todo guidance ("every `:after` label must be some node's `:ref`") would remove the
wasted turn.

## What WORKED (don't regress)

- **#72 spawn-contract** — `start!` returns the real id, arms the child
  in-process; both workers woke on the parent's message with NO out-of-band rearm.
  Spawn is now in the signature whitelist and was discovered unaided.
- **#73 report=DATA / message=POINTER** — both workers stored schema'd `my.kb.*`
  data and reported SHORT pointers (id + summary + the literal query to run). Zero
  report truncation. `complete` delivered the pointer to the parent.
- **Synthesis from STORED DATA, not message text** — the parent pulled both
  datasets and grounded its recommendation in the real datoms ("25 DuckDB findings
  + 19 SQLite findings" = actual counts, not the "17" its own message text once
  said). No fabricated numbers in the final answer.
- **Self-correction via the transcript** — the parent detected the `BgY` ghost
  (`entity → nil`), the empty SQLite dataset, and re-spawned a real worker, all
  from reading its own context.
- **First-round delegation needs ZERO intervention** — DuckDB's round (report at
  hops 2) ran spawn→store→report→parent-wake→query with no nudge.
- **Clean termination** — all 7 test agents `:terminated`.

## Live proofs (verbatim, key ones)

Async-Promise bug (isolated):
`(let [r (agent/start! {…})] (instance? js/Promise r)) => true`;
`(:seon.agent/id r) => nil`.

EIP report-back (success, hops 2):
`(complete "DuckDB research stored as 6 my.kb.duckdb.source entities with 17
findings. Query: (db/query '[:find [(pull ?s [*]) ...] :where [?s
:my.kb.duckdb.source/id]]).") => :idle`.

SOv report-back REFUSED (hops 4):
`SOv outbound #{[4 "SQLite research complete — 6 sources with 19 findings stored
as my.kb.sqlite.* …"]}`; log: `WAKE REFUSED … hops=4 reached hop-cap 4`; parent
stayed `:idle`, evals frozen at 41.

Human nudge resets the chain → synthesis:
parent woke (`:running`), pulled both `my.kb.duckdb.*` + `my.kb.sqlite.*`, and sent
the human a `## Recommendation: DuckDB for embedded analytics` markdown table —
END-TO-END complete.

## Recommended next steps (for the Core lane)

1. **Hop-cap on multi-round delegation (friction #2) — the new highest-leverage
   fix.** Don't consume ping-pong budget on parent↔child delegation edges (reset
   hops for a parent→own-child / child→parent message), OR count delegation depth
   separately. Without this, any 2-round delegation silently deadlocks. Pair with a
   dead-letter so a refused `complete` returns an error, not a false `:idle`.
2. **Fix the `start!` async recipe (friction #1).** Make the docstring + system
   text teach the two-form spawn→message pattern (or ship `spawn-and-message!`),
   and DELETE the false "let-bind `(:seon.agent/id (start! …))`" guidance. This
   also kills the orphan-spawn + fabrication cascade (#4).
3. **Store-incrementally guidance (friction #3).** Extend the report=DATA text:
   small transacts, one source per form — a giant store truncates like a giant
   message.
4. **Tiny adds:** a `plan!` `:after`-label example (#6); an inspect-a-worker verb
   to absorb the bare-id eval instinct (#5).
