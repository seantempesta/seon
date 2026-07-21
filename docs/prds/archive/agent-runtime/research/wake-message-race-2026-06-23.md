---
type: research
status: active
tags: [research, agent, flow]
---

# Wake/message write→visibility race (#9 + #17)

## TL;DR

- **#9 (seed-race) is a REAL production bug.** `start-agent!` runs
  `bootstrap-turn!` (which calls `(message/user "hello")`) BEFORE
  `boot-seed!` (which creates the `[:seon.user/id "user"]` entity). The
  user-ref the message resolves against does not exist yet ⇒
  `:seon.db/ok? false — Nothing found for entity id [:seon.user/id "user"]`.
  This is a **boot-ordering bug, not a visibility lag** — the seed
  transact has not been *issued*, let alone committed.
- **#17 (wake-race / greet-instead-of-work) is NOT a production bug on the
  normal inbound path** — it is an artifact of a drive harness doing a
  manual `transact!`-then-`fresh-wake!` that bypasses the proper delivery
  ordering. On the real path the wake handler reads the **post-commit
  `:db-after` snapshot carried by the tx-report**, which provably contains
  the just-landed message. The harness instead minted a wake whose loop
  re-derefs `@*conn*`, and a fresh re-deref of the cluster store can lag
  the just-acked write by a few ms.
- One-line cause of each: **#9 = `message/user` called before the user
  entity is seeded; #17 = harness wakes off a re-derefed `@*conn*` instead
  of the committed report snapshot.** They are *adjacent symptoms of the
  same write-then-read discipline gap* but have **distinct root causes and
  distinct fixes**.

## The write→visibility path

The pod does NOT embed datahike. It is a DIS peer on the JVM wire-server's
file store. The write path and what "visible" means:

### 1. `transact!` resolution — `seon.store.wire/SeonWireWriter`

`src/seon/store/wire.cljs:225-273` — `-dispatch!`:

- Forwards the tx over the UDS wire to the JVM (the sole writer),
  recording its `request-id` in `!own-request-ids`
  (`store/wire.cljs:240`).
- On the ack it calls **`ryow-deref! conn basis-t`**
  (`store/wire.cljs:196-205`, invoked at `253`): loops re-dereffing
  `@conn` until `(:max-tx db) >= basis-t`, throwing only after 10 spins.
- Builds the report with **`:db-after` = that materialized post-tx db
  value** (`store/wire.cljs:257-264`).
- `(-streaming? [_] false)` (`store/wire.cljs:273`).

### 2. What `@*conn*` reads — `deref-conn` follow-the-store mode

`reference-code/datahike/src/datahike/connector.cljc:69-79` — because the
writer reports `streaming? false`, **every `@conn` does a FRESH
`k/get` of the branch root from konserve and reconstitutes a db value**
(lines 74-78). The pod holds no in-memory "current db"; each deref
re-reads the store.

So a fresh `@*conn*` is only as current as the **last store flush the
wire-server has performed**. The wire-server flushes-before-ack
(`writer.cljc:108-134`, noted at `store/wire.cljs:191-194`), which is why
`ryow-deref!` succeeds on attempt #1 in the common case — but it is a
*loop with retries*, i.e. the codebase itself documents that a fresh
re-deref **can momentarily lag** an acked write.

### Q1 — is the datom visible to the very next read after `(await transact!)`?

**For the awaiting caller in the same async context: YES, via the report;
NOT guaranteed via a fresh independent `@*conn*` re-deref.**

- The success envelope's tx is at `:max-tx (:db-after report)`
  (`db/internal.cljs:1308`), and `:db-after` came from `ryow-deref!`, so
  any code that reads **the report's `:db-after`** sees the write.
- BUT `seon.db/transact!` returns the **compact envelope**
  (`{:seon.db/ok? true :tx … :tempids …}` — `db/internal.cljs:1306-1311`),
  which does **NOT** include `:db-after` unless the caller passes
  `:seon.db/return-report? true`. The next `(db/query …)` / `(db/entity
  …)` re-derefs `@*conn*` fresh (`db.cljs:516,528,884`). Because
  `ryow-deref!` already advanced `@conn` past `basis-t` *before* the
  promise resolved, in practice the immediately-following re-deref is
  also at/past that tx — but this rests on the store flush having
  happened, and the bounded-retry loop is exactly the admission that it
  is not a hard, instantaneous guarantee.

The robust contract is: **read back through the report's `:db-after`, or
re-read `@*conn*` knowing it follows-the-store with a possible sub-ms
catch-up window.**

## The wake/delivery path

### Production inbound (own write — human message via HTTP /chat)

1. `seon.agent.message/message!` (`agent/message.cljs:161-239`) builds the
   row and `(await (db/transact! …))` (line 231).
2. That is the pod's OWN write → `d/transact!` → `datahike.writer/transact!`
   (`reference-code/.../writer.cljc:237-261`).
3. `dispatch!` → `SeonWireWriter` → `ryow-deref!` → report with `:db-after`
   = post-commit snapshot.
4. **writer.cljc:258-259 then SYNCHRONOUSLY fires the conn's native
   listeners with that tx-report** — *after* the report (with the
   committed `:db-after`) exists.
5. `seon.db/listen!`'s wrapper (`db/internal.cljs:1448-1459`) projects
   `:seon.db/db` := `(:db-after raw-tx-report)` into the handler input.
6. `seon.agent.fsm/wake-handler` (`agent/fsm.cljs:236-291`) reads `db`
   from that input, finds the inbound via `attr-index`
   (`:seon.agent.message/to`), confirms wakeability **against that same
   `:db-after`** (line 263-265), mints `fresh-wake!`, flips `:active`, and
   starts `run-loop!`.

**On this path the message is provably in the snapshot the wake gate and
the loop key off** — delivery is commit-THEN-wake, and the snapshot used
is the committed one. No race.

The one subtlety: the wake fires via `js/setTimeout … 0`
(`agent/fsm.cljs:278`) so it re-enters the ALS scope; the loop's first
turn then re-derefs `@*conn*` in `open-turn!` / `render-prompt`. Since the
listener only fired *after* `ryow-deref!` advanced `@conn`, the loop's
fresh re-deref is at/past that tx. (Foreign writes — peer-agent messages —
arrive via the feed adapter `handle-feed-event!`, `store/wire.cljs:314-334`,
which ALSO `ryow-deref!`s to `basis-t` before firing listeners, so the same
guarantee holds.)

### The harness path (the reproduced bug)

The drive harness did a manual `transact!` of the message **and then
called `fresh-wake!` directly** (outside the listener-fired delivery). The
problems:

- `fresh-wake!` (`agent/fsm.cljs:283` / `agent.cljs:359-369`) and the loop
  read `@*conn*` **freshly re-derefed**, NOT the report's `:db-after`. If
  the manual wake runs in the tiny window before the store-followed
  re-deref catches up, the loop's first `render-prompt` sees an inbox that
  is still empty → the agent renders the generic onboarding/greeting
  context and `(agent/wait)`s.
- The harness had to insert an "await-inbound poll" between the manual
  transact and `fresh-wake!` precisely to close that window — i.e. it
  hand-rolled the ordering that the real listener path gets for free.

So #17 reproduces only because the harness **skips the
listener-delivery** (which carries the committed snapshot) and substitutes
a manual wake keyed off a re-deref.

## #9 vs #17 — same or distinct?

**Distinct root causes; same underlying discipline ("don't read before the
write is committed-and-visible").**

- **#9 is pure boot ORDERING.** `start-agent!`
  (`client.cljs:2154-2298`) runs, in this order inside one `with-agent`
  scope:
  1. `prune-core-ghosts!`
  2. `replay-program-graph!`
  3. `boot-one-agent!` for each agent **then `bootstrap-turn!` for minted
     agents** (`client.cljs:2239-2259`) — `bootstrap-turn!` evals
     `hello-source` = `(message/user "Hi …")` (`client.cljs:2074-2081`,
     `2256`).
  4. `web.serve/start!`
  5. **`boot-seed!`** (`client.cljs:2282`) — which calls `seed-core!`
     (`client.cljs:817-834`) to transact `{:seon.user/id "user"}`.

  `message/user` → `message!` defaults `to` := `[user-ref]` =
  `[[:seon.user/id "user"]]` (`agent/message.cljs:64-67,190-197`). At
  step 3 the user entity is not seeded until step 5, so the message tx's
  ref cannot resolve → `Nothing found for entity id [:seon.user/id
  "user"]`. This is **not** a visibility lag — the seed write has not
  even been issued yet. The fix is to **seed before turn 0**, not to wait
  for visibility.

- **#17 is wake/snapshot SOURCING** (harness-only, above): the wake is
  fired off a re-derefed `@*conn*` instead of the committed report
  snapshot.

They feel like one bug because both surface as "the agent does the wrong
generic thing right after a message," but the cures are different: #9 =
reorder boot; #17 = either fix the harness or harden delivery.

## Production impact

- **#9: real and user-visible at every fresh mint.** A newly minted
  agent's turn-0 greeting fails with the error envelope. Per
  `message!`'s own doc (`agent/message.cljs:182-185`) "errors are values
  … the agent may over-claim," and turn 0 then parks via `agent/wait`.
  Net effect: the very first hello to the human silently fails on a fresh
  cluster (`cluster reset` / first boot). Resumed agents are unaffected
  (no turn 0, and the user entity already exists from a prior boot).
- **#17: NOT reproducible on the normal inbound path.** A real human or
  peer message is delivered via the native/feed listener, which fires with
  the committed `:db-after`; the wake gate and loop key off that snapshot.
  An agent cannot miss a message that arrived through `message!` + the
  listener bus. The "empty inbox → greeting" behavior requires the manual
  transact-then-wake the harness used. Risk it becomes real: any FUTURE
  code path that mints a wake **without** going through the listener
  delivery (e.g. a "nudge this agent now" REPL/admin verb that transacts
  then calls `fresh-wake!`) would hit the same window.

## Recommended minimal fix (ranked)

### Fix A (REQUIRED, #9) — seed the core BEFORE turn 0

Move `boot-seed!` so the user entity exists before any agent eval.
`bootstrap-turn!` (`client.cljs:2256`) runs inside the `results` doseq at
`client.cljs:2239-2259`; `boot-seed!` is at `client.cljs:2282`, AFTER it.
**Reorder so `(await (boot-seed! {:seon.db/conn conn}))` runs before the
per-agent `boot-one-agent!`/`bootstrap-turn!` doseq** (it only needs
`conn`, which is bound at `client.cljs:2179`, and it manages its own
`*conn*` pin). Lowest-risk, single-move, no contract change. Live proof:
after the move, a `cluster reset default` boot's turn-0 transcript shows
`(message/user …)` returning `{:seon.agent.message/ok? true …}` and the
user row resolvable via `(db/entity {:seon.db/ref [:seon.user/id "user"]})`.

Note also the dependency: `boot-seed!` runs `prune-core-ghosts!`? — no;
but `replay-program-graph!` (step 2) and `prune-core-ghosts!` (step 1) do
not depend on the seed, so moving the seed to run **right after the conn +
preconditions + compile-state (≈ `client.cljs:2194`) and before the boot
loop** is safe. Verify the `:core-seed` provenance tagging still holds
(`boot-seed!` establishes its own `{:seon.db/origin :core-seed}` context,
`client.cljs:2016`).

### Fix B (RECOMMENDED, #17 hardening) — re-read the inbox at turn start, not from a pre-captured snapshot

The loop/turn already re-derefs `@*conn*` fresh at each turn
(`render-prompt` → `db/entity … @db/*conn*`, `turn.cljs:197-204`). The
hardening is to make the **wake decision and the first render robust to a
sub-ms store-follow lag** rather than depend on a snapshot captured at
wake time. Two sub-options, smallest first:

- **B1 (cheapest): make any manual wake path await visibility.** If a
  non-listener wake path exists/will exist, have it `ryow`-style confirm
  the message is visible (`(db/entity {:seon.db/ref [:seon.agent.message/id
  id]})` non-nil) before `fresh-wake!`. This is exactly the harness's
  "await-inbound poll," promoted into the one place that mints wakes
  outside delivery. No core change if no such path ships.
- **B2: in `run-loop!`, gate the empty-streak halt on a re-derefed inbox.**
  The loop already tolerates two empty turns (`empty-streak < 2`,
  `agent/fsm.cljs:198-202`). That thinking-mode guard ALSO absorbs a
  one-turn visibility lag: by the second turn the fresh `@*conn*` re-deref
  has caught up and the inbox renders. This is already in place and is why
  even the harness mostly worked — but it is implicit. No code change
  needed; document that the empty-streak guard doubles as the
  visibility-lag absorber.

### Fix C (only if B1 path ships) — make `transact!` resolve on visible-locally

Have `seon.db/transact!` (or the wire writer) guarantee the immediately
following fresh `@*conn*` re-deref is at/past the tx. **`ryow-deref!`
already does this for the report's `:db-after`** (`store/wire.cljs:253`),
and because it advances `@conn` (the same wrapped-atom the next deref
re-reads through the store), the next re-deref is at/past `basis-t` in the
common case. So C is largely **already true**; the only gap is the
documented bounded-retry window. Not worth a change unless B1 proves
insufficient.

**Recommendation: ship Fix A now (real bug, trivial reorder). Treat #17 as
harness-only — adopt B1 as a guard ONLY if/when a non-listener wake path
is introduced; otherwise B2 already covers it.**

## Open questions

- Is `boot-seed!`'s placement after the boot loop load-bearing for any
  reason not visible in source (e.g. the gym's `seed-scenario-world!`
  ordering, which shares `boot-seed!`)? Confirm the gym path seeds before
  it drives a turn-0, or the same #9 reproduces there.
- After reordering, does `prune-core-ghosts!` /
  `replay-program-graph!` interact with the freshly-seeded `:core-seed`
  rows (they classify by `core-ns-set` / bootstrap provenance)? Expected
  no — replay loads agent corpus, seed writes core rows — but verify the
  `bootstrap-row-ids` provenance scan still cleanly separates them.
- Does any live drive harness still carry the "await-inbound poll" hack?
  If so, after Fix A + confirming delivery ordering, that poll can be
  deleted (it masks the now-fixed #9 and the never-real #17).
- Quantify the `ryow-deref!` catch-up window on the live wire-server: how
  many spins does attempt-1 take under load? If always 1, Fix C is moot;
  if it ever spins, B1's await-visibility becomes more valuable for any
  future manual-wake path.
