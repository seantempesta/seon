---
type: research
status: active
tags: [research, agent, flow]
---

# Live delegation drive — parent→child spawn/message/complete (2026-06-28)

First end-to-end LIVE drive of the multi-agent DELEGATION flow on the shared
default pod (7890), driving a real DeepSeek parent that was told to delegate a
sub-task to spawned workers. The coordination survey
([[coordination-primitives-state-2026-06-28]]) says this flow WORKS in code; this
drive exercises it with a real LLM and surfaces the frictions that only appear
when an actual agent tries to coordinate. Every claim below is a live observation
(verbatim evals preserved), not inference.

## TL;DR

**Parent→child delegation did NOT complete end-to-end.** The parent successfully
spawned two real workers and (after a self-correction) routed the right task to
each; both workers ran and did real research. But **neither worker delivered its
report back to the parent**, so the parent never synthesized and the human never
got a recommendation. The flow broke at the LAST hop (report-back), and along the
way hit the known #30 gap plus several NEW frictions. The spawn/message/wake
machinery itself is sound — the failures are in the AGENT-FACING contract around
it: opaque minted ids, no spawn-verb discoverability, no size budget on the
report message, and no forcing function tying "done" to "report to parent."

Drive shape: root minted parent `QmO-2606282048` (purpose = "coordinate a
Bash-vs-Python recommendation by spawning one worker per option, collect their
findings, report the best to the human"), armed it, sent a human kickoff message.
Parent spawned `jBI` (Bash) + `oYD` (Python). All four test agents terminated at
the end.

## What actually happened (the timeline)

- **Parent turn 1** — `(db/store-inventory)`, then three `todo/add!` plan items
  (spawn Bash worker / spawn Python worker / collect+synthesize). Clean.
- **Parent turn 2** — spawned BOTH workers for real
  (`(agent/start! {:seon.agent/id (db/new-id!) :seon.agent/purpose … :default-turn-limit 12})`
  → real ids `jBI-2606282050`, `oYD-2606282050`), then **fabricated** the child
  ids as `abc-2606282051` / `def-2606282051` in its narration and messaged THOSE
  ghosts → `:seon.db/ok? false … "Nothing found for entity id …abc-2606282051"`.
  It then told the human it had dispatched workers `abc`/`def` (fabricated), and
  called `(wait …)` → idle. **At this point the delegation is dead-locked**: the
  real workers were never messaged; the parent waits forever for replies from
  ghosts.
- **[I sent a human "ping" message]** — gave the parent a turn 3 it would not
  otherwise have had.
- **Parent turn 3 — SELF-CORRECTED.** Its context now showed the real children +
  the failed sends. Narration: *"(`jBI-2606282050` and `oYD-2606282050`), but the
  messages were sent to the wrong ids (`abc-2606282051` and `def-2606282051`).
  Those ids don't exist, so the messages failed. I need to message the actual
  workers."* It re-sent to `jBI`/`oYD` (both `ok? true`), told the human it
  corrected, `(wait …)`.
- **Workers ran** — both received their briefs and researched (jBI 9 turns / 35
  evals; oYD 8 turns). They WERE armed and woke (see #30 note — out-of-band).
- **Report-back FAILED for both** (the decisive break, below). Parent received
  nothing; stayed idle. Human got no recommendation.

## Ranked frictions / bugs (NEW unless tagged)

### 1. Report-back truncation dead-end — HIGH (new)

The worker reports back by stuffing a multi-thousand-token markdown report into a
SINGLE form: `(message/agent "QmO-2606282048" (str "# Python for CLI Tools …"
…hundreds of lines…))`. The LLM output cap truncated the response mid-string
(the literal ends `"…startup latency must be <10ms (tab completions,` with no
closing quote/paren) → the form is unparseable → **`:seon.eval/ok? false`, the
message is NEVER sent**, and the worker has no signal it failed. The parent's
inbound-from-children set is `#{}`.

This is the single biggest coordination failure: even with spawn + arming + task
routing all working, the report-back collapses because nothing tells the agent
"a message is a single eval'd form and must fit the output budget — put the long
artifact in the DB and send a short pointer." Verbatim result of the send eval:
`{:ok false :result nil}` over a clearly-truncated `(str …)` source.

- Site: `seon.agent.message/agent` / `message!` (message.cljs:145-240, 264-279) —
  no size guard, no chunking, no "report = data, message = pointer" guidance.
- Compounding: the eval segmenter/parser silently drops the truncated form as a
  failed eval; the agent reads its own narration as if it sent (honesty gap).
- Fix shape (agent-facing): teach the report-back pattern — write the report as
  schema'd rows (or a blob) and `(message/agent parent "report ready: <ref>")`;
  OR have `message!` reject/echo when content exceeds a token budget so the
  failure is loud instead of a truncated-form swallow.

### 2. Within-turn child-id fabrication — HIGH (new)

`agent/start!` returns a SERVER-minted opaque 14-char id
(`{:seon.agent/id "jBI-2606282050"}`), and the agent **cannot choose a readable
id** — the schema rejects anything but `"root"` or a `:seon.db/id`
(`(start! {:seon.agent/id "PARENT-deleg-test"})` →
`Malli validation failed for :seon.agent/id: expected …[:= "root"] :seon.db/id…`).
The agent inlined `(db/new-id!)` inside `start!` (so it never bound the id), then
in the SAME turn it FABRICATED the ids it would message. Spawn-then-message in one
turn has no safe path unless the agent knows to `(let [cid (db/new-id!)] (agent/start!
{:seon.agent/id cid …}) (message/agent cid …))` — and nothing teaches that.

- Sites: `start!` mints via `(or id (db/new-id!))` (agent.cljs:497); id schema
  (message.cljs/agent.cljs `:seon.agent/id` = `[:or [:= "root"] :seon.db/id]`).
- Natural failure mode: fabricate → message ghost → `wait` → **permanent
  deadlock** (recovered here ONLY because I externally pinged the parent into a
  3rd turn; un-nudged it waits forever for ghost replies).
- Fix shape: a worked spawn→message recipe in context (let-bind the id), OR have
  `start!` surface the minted id more prominently, OR allow a caller-chosen
  readable child label as an alias attr the agent can address.

### 3. Unarmed minted child (#30) — CONFIRMED, MEDIUM→HIGH in practice

Controlled probe: minted `ETF-2606282057` as root (idle, NO
`[:seon.agent/user-message-trigger …]` listener), sent it a valid human waking
message (`{ok? true :hops 0}`). Result: **child stays `:idle`, turn-count 0, the
message sits as 1 unhandled inbound.** The send SUCCEEDS silently and the child
never wakes — exactly #30, and it presents to a coordinator as "I messaged my
worker and nothing happened, with no error."

Two extra facets the survey doesn't call out:

- **Reactive-only, no catch-up.** After arming `ETF` (`rearm-wake-triggers!`,
  trigger now present), it STILL didn't wake — the pre-arm message had already
  committed; triggers fire on NEW txs only (loop.cljs:505-529, db.cljs:1056). A
  fresh post-arm message woke it instantly (`:running`, turn 1). So the
  ordering is load-bearing: **arm BEFORE the first message, or that message is
  stranded forever.**
- **Non-determinism on the live pod.** In the actual drive the workers DID get
  armed and ran — not via my `rearm` calls (which returned without them) but via
  an out-of-band `after-reload → rearm-wake-triggers!` sweep (cljs-watch on the
  shared tree arms ALL idle agents). So delegation "worked" by accident of a
  reload. A spawn-and-arm primitive (task #30) would make this deterministic.

Code is correct-as-designed: `start!`/`create!` explicitly do NOT arm
(agent.cljs:490-491); only `boot-one-agent!` (client.cljs:1998-2033) and the
private `rearm-wake-triggers!` (client.cljs:1936) install triggers.

### 4. Spawn verb is undiscoverable — MEDIUM (new)

The agent's full rendered context (8945 tok) contains **no `start!` / `create!` /
`spawn` / `delegate`** anywhere except the words in its own purpose text. The
`:namespaces` block renders signatures for `seon.agent.lifecycle` (complete,
pause, resume, terminate, wait) and `seon.agent.message` (user, agent) — because
`verb-signature-whitelist = #{:seon.agent.message :seon.agent.lifecycle}`
(namespaces.cljs:156). The spawn verbs live in `seon.agent` (agent.cljs:414/482),
which is aliased into the home ns as `agent/` (eval.cljs:1203) but is in NEITHER
whitelist, so **no arglist renders**. The whitelist docstring itself says "a bare
alias is undiscoverable — the agent must SEE the arglist" — and spawn is exactly
the case it misses.

**Asymmetry worth flagging:** an agent can discover how to KILL a child
(`terminate`, rendered) but not how to SPAWN one (`start!`, hidden). The DeepSeek
parent guessed `(agent/start! {:seon.agent/purpose … :default-turn-limit 12})`
correctly from training — but that is luck, and a weaker model would dead-end.
Fix: add `:seon.agent` (or the spawn verbs) to `verb-signature-whitelist`.

### 5. `message!` throws `:malli.core/invalid-schema` on agent-`from` (instrumented path) — code smell, LOW for agents

Reproducible: `(message! {…:from [:seon.agent/id "QmO…"] …})` →
`{:seon.db/ok? false :seon.db/error {:seon.error/message ":malli.core/invalid-schema"
:seon.error/data {… :schema :seon.agent.message/id :form :seon.agent.message/id
:seon.error/kind :core-bug}}}`, while the IDENTICAL call with
`:from seon.agent.message/user-ref` succeeds (`ok? true`). `:seon.agent.message/id`
IS registered (message.cljs:31), so this is a schema-RESOLUTION failure on a
branch that only the agent-`from` path hits (the `waking-hops`/non-user branch,
message.cljs:199-206) — surfacing only under instrumentation. Agents dodge it
(their `^:async` home-ns path is uninstrumented — that's why the live parent's
`message/agent` reached the DB and failed only on the ghost-entity lookup), but
ANY instrumented caller (MCP REPL, tests, a JVM-track caller) hits it. Worth a
focused look — flagged, not fixed (outside this drive's scope; root cause unclear
without reading the bridge).

### 6. Worker peters out without reporting — MEDIUM (new)

`jBI` did 35 evals across 9 turns, even created a todo *"Compile and deliver final
report to QmO-2606282048 … message it back"* — then produced **empty turns** and
went `:idle` having NEVER called `(message/agent "QmO…")` or `(complete …)` (grep
of all its sources: zero `complete`, zero `message/agent`). The run ended (idle)
before the report, below its 12-turn limit. Nothing ties "I finished the research"
to the obligatory "report to my parent" — the worker narrated completion and
stopped. A worker that completes a delegated task should be steered to
`complete`/report (the `complete` verb already messages the parent —
lifecycle.cljs:70-94 — it just was never called).

### 7. Honesty gap in coordination narration — MEDIUM (recurring)

In turn 2 the parent (a) wrote fabricated `=> {…}` result lines for evals it
hadn't seen, (b) reported fabricated worker ids `abc`/`def` to the human, and (c)
claimed the dispatch succeeded while the actual sends returned
`{:seon.db/ok? false}`. Same message↔stored decoupling the UI lane already
tracks, now seen in the SPAWN/dispatch path specifically. (Self-corrected in turn
3 once the real state was in context.)

## What WORKED (don't regress)

- **Spawn** (`agent/start!`) — minted real children, wrote `:seon.agent/parent`
  correctly (`jBI`/`oYD` both `:parent "QmO-2606282048"`).
- **Wake gate + arming** — an ARMED idle child wakes instantly on a valid inbound
  (ETF post-arm: `:running`, turn 1). Hop accounting fine (parent→worker sends
  `:hops 1`).
- **Self-correction via the transcript** — the parent read the real child ids and
  the failed-send envelopes out of its own context and fixed its dispatch. The
  errors-as-values + transcript-feedback loop is the reason recovery was possible
  at all. The gap is that recovery needed an EXTERNAL nudge (the parent had
  already `wait`-ed into idle).
- **`terminate`** — clean shutdown of all four test agents (`:terminated` each).

## Live proofs (verbatim, key ones)

Spawn (turn 2): `(agent/start! {:seon.agent/id (db/new-id!) :seon.agent/purpose
"Research Bash …" :seon.agent/default-turn-limit 12})` ⇒ `{:seon.agent/id
"jBI-2606282050"}` — but narrated result was the fabricated `{:seon.agent/ok?
true :seon.agent/id "abc-2606282051"}`.

Ghost send (turn 2): `(message/agent "abc-2606282051" "Your task: Research BASH …")`
⇒ `{:seon.db/ok? false :seon.db/error {:seon.error/message "wire transact failed:
… Nothing found for entity id [:seon.agent/id \"abc-2606282051\"] …"}}`.

#30 probe: minted `ETF-2606282057` (no trigger) + human send `{ok? true :hops 0}`
⇒ `{:state :idle :has-trigger? false :turn-count 0 :pending-inbound 1}`; after
`rearm` (trigger present) still `:idle` turn 0; after a NEW send ⇒ `:running`
turn 1.

Report-back fail (oYD): `(message/agent "QmO-2606282048" (str "# Python for CLI
Tools — Research Report\n\n…"))` truncated mid-literal ⇒ `{:ok false :result
nil}`; parent inbound-from-children `#{}`.

## Recommended next steps (for the Core lane)

1. **Report-back contract** (friction #1) — the highest-leverage fix. Either a
   loud size-reject in `message!`, or teach (in always-on context) "long artifact
   → DB rows + short pointer message." Without this, delegation cannot complete.
2. **Spawn-and-arm + a worked spawn→message recipe** (#2, #3) — close task #30 so
   a minted child is wakeable in-process and deterministically, and put the
   `(let [cid (db/new-id!)] (agent/start! {:seon.agent/id cid …}) (message/agent
   cid …))` recipe where the agent sees it.
3. **Add the spawn verbs to `verb-signature-whitelist`** (#4) — one-line
   namespaces.cljs edit; remove the kill-but-not-spawn asymmetry.
4. **Tie task-done → report-to-parent** (#6) — steer a worker to `complete`
   (which already notifies the parent).
5. **Investigate the agent-`from` `:malli.core/invalid-schema`** (#5) — a focused
   read of the `waking-hops` branch + the message-response schema bridge.
