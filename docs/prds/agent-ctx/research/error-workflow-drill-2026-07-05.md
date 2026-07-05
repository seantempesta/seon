---
type: research
status: active
tags: [research, agent]
---

# Error-workflow acceptance drill — planted core bug, uncoached drive, full loop

**Verdict: the shipped find → triage → rewind → fix loop works END TO END with
zero coaching and zero manual log archaeology.** Every link ran on the shipped
tools alone; the one gap found (the crashed turn's reply blob stranded without
its ref) was fixed in the same session (registry C51). Owner-ruled drill,
executed 2026-07-05 on the default cluster (pod 7890, dial
`:seon.config/on-core-error :crash`).

## The planted bug (never committed; reverted as the fix)

`src/seon/render/value.cljs` `truncated?` — the realistic incomplete-context
slip: "simplify" the predicate by dropping the `(map? %)` guard, not knowing
`tree-seq` yields LEAF nodes too, so `(keys <scalar>)` throws
`"<x> is not ISeqable"` on the first eval value whose skeleton contains any
non-map node. Applied via patch (drill machinery — deliberately outside the
Edit-hook flow), picked up by cljs-watch hot reload, pod stayed up.

```diff
   (boolean
-    (some #(and (map? %)
-                (some #{:seon.render.value/elided :seon.render.value/elided-keys
-                        :seon.render.value/pruned :seon.eval/opaque
-                        :seon.eval/datom :seon.render.value/string-len}
-                      (keys %)))
+    (some #(some #{:seon.render.value/elided :seon.render.value/elided-keys
+                   :seon.render.value/pruned :seon.eval/opaque
+                   :seon.eval/datom :seon.render.value/string-len}
+                 (keys %))
           (tree-seq coll? #(if (map? %) (vals %) (seq %)) skel))))
```

Why this site: `truncated?` runs inside `seon.render.value/render-ai`, whose
caller `seon.eval/render-result-edn` carries the ALREADY-DESIGNED catch that
`record!`s fault `:core` (eval.cljs:3028) — so the classification link is the
shipped rule, not drill scaffolding. It fires on the value-persist step of the
FIRST agent eval (i.e. AFTER a successful prompt render + LLM call), never at
boot (this world records zero boot evals — verified 0 `:seon.eval/id` rows
pre-drive), and none of my probes walk it.

## The uncoached drive (trip at turn 0's first eval)

`bin/seon watch-faults` armed in the background, then a NORMAL
planning+db-memory task via `POST /chat?agent=root` (book-notes schema → store
3 books → query back the highest-rated; no mention of errors/rendering/values).
DeepSeek live. Turn 0: prompt rendered (17,445 tokens, blob-captured), LLM
replied, first real form was `(db/store-inventory)` — its inventory value's
skeleton walk hit the planted predicate:

```
2026-07-05T20:15:16.727Z INFO [seon.web.serve] POST /chat {:agent "root", :tokens 171}
2026-07-05T20:15:17.401Z INFO [seon.agent.turn/root] turn 0 ▸ open ["SXd-2607051615" "+" 12671 "ctx-tokens"]
SEON-CORE-FAULT :vector is not ISeqable @t=536870972
```

The watch fired and exited 0 with the marker + 20 context lines + the triage
recipe (verbatim tail):

```
✗ CORE FAULT detected in logs/pod.log:
  SEON-CORE-FAULT :vector is not ISeqable @t=536870972
...
triage: (seon.agent.inspect/errors) → (seon.agent.inspect/error {:seon.agent.inspect/eid N}) → (seon.agent.inspect/repro {:seon.agent.inspect/eid N})
(dev dial is :crash — the pod exited after persisting the fault datom; restart: bin/seon restart pod)
```

Pod dead (designed). Turns until trip: 1 (turn 0, mid-eval-batch — after
prompt+reply capture, before any eval row for the failing form persisted).

## Triage — the chain alone, no log digging

Restart (`bin/seon restart pod`, 8s ready; boot log shows the designed
`crash recovery: closed 1 orphaned run(s) :crashed` — nothing wedged), then:

- **`(seon.agent.inspect/errors)`** → one row:
  `{eid 2353, fault :core, message ":vector is not ISeqable", at 536870972, agent "root"}`.
- **`(inspect/error {::eid 2353})`** → full envelope: fault `:core`, `at`
  536870972, frames table pointing at the PLANTED file —
  `seon/render/value.cljs:306` (`pred`, the planted lambda) →
  `truncated?` (line 303) → `render-ai` (line 446) — plus the turn join:
  `:seon.agent.turn/id "SXd-2607051615"`, turn-eid 2349,
  `rendered-as-of 536870963`.
- **`(inspect/turn {:seon.agent.turn/id "SXd-2607051615"})`** → the
  byte-exact prompt read back from its blob: 17,445 tokens, carries the drive
  message verbatim, ends at the `my.agent.root=>` readline; `::txs`
  [536870968 … 536870973]. (Reply: see the C51 gap below.)
- **`(inspect/repro {::eid 2353})`** → the bundle: live as-of db
  (`(seon.db/as-of 536870972)` as `repro-expr`), the honest
  `::note` "no captured fn/args on this error (non-malli path…) — work from
  the frozen db + the linked turn's eval forms", the turn join, and
  `::fork-hint "bin/seon cluster fork default 536870972"`.

## Fork + reproduction (the owner's core demand)

Ran the fork-hint VERBATIM → `fork-default-536870972` booted (own store, own
pod on :55525, wire registry entry). Assertions inside the fork:

- **error datom ABSENT** (`:seon.error/fault` query → `#{}`) — the exact
  designed at-semantics: the fork is the world the failure AROSE from, not the
  world that records it;
- the drive message present, turn `SXd-2607051615` still `:running` — the
  pre-failure moment;
- the reply blob (captured pre-crash, tx < at) readable via
  `my.blob/get` → recovered the agent's actual reply and the failing form
  `(db/store-inventory)`.

Reproduction through the same machinery in the frozen world:
`(seon.eval/render-result-edn "REPRO-1" (await (seon.db/store-inventory)))` →
the fork pod printed the IDENTICAL marker and died under the dial:

```
SEON-CORE-FAULT :vector is not ISeqable @t=536870980
seon.error/record!: on-core-error :crash — exiting after persisting the fault datom
```

Source cluster untouched (fault count in `default` stayed 1).

## Fix + close the loop

Reverted the plant (file byte-identical to HEAD; hot reload).
**Verified in the FORK first**: restarted the fork pod, re-ran the same repro
expression → clean bounded skeleton string, pod alive. Then
`bin/seon cluster destroy fork-default-536870972` — store dir + blobs removed,
registry entry deleted, no residue.

Re-ran the SAME drive task on the default pod under a fresh
`bin/seon watch-faults`:

- ran to completion (8 turns, `halt verb — complete`), correct answer
  ("Thinking, Fast and Slow… rating 5… two systems of thought"), durable
  `my.plan` items, `my.books` schema + rows in the db;
- ZERO new `SEON-CORE-FAULT` markers; the watch stayed silent through the
  whole drive;
- `core-faults-block` renders **""** (blank) — the fault datom still exists
  (derive-don't-store: the window moved past it with the new user message; no
  acknowledgement state);
- full `bin/test-cljs` green with the gate clean (counts in the verdict
  table).

Cleanup: drill datoms cleared via `bin/seon cluster reset default` (the world
held nothing but drill artifacts — pre-drive state was 1 agent, 0 messages,
0 errors).

## Verdict table

| Link | Verdict | Evidence (one line) |
|---|---|---|
| Plant (boot-clean, probe-clean) | PASS | hot reload picked it up; pod up; 0 fault datoms until the drive |
| Organic trip (uncoached drive) | PASS | normal book-notes task; first eval's value render threw; no coaching, no replant |
| Crash + persist | PASS | marker `@t=536870972` printed, datom persisted, pod exited (dial `:crash`); restart closed the orphaned run `:crashed` |
| Watch firing | PASS | background `watch-faults` exited 0 with marker + context + triage recipe |
| Inspect chain (`errors`→`error`→`repro`) | PASS | fault `:core`, `at`, frames at `render/value.cljs:306`, turn join, fork-hint — all from the datoms |
| Turn replay (`inspect/turn`) | PASS | byte-exact 17,445-token prompt from the blob; drive message verbatim; `rendered-as-of` 536870963 |
| Fork boot (verbatim fork-hint) | PASS | own pod/store at t=536870972; error datom absent inside its own fork |
| Reproduction in fork | PASS | same form + same machinery → identical marker, fork pod died; source cluster untouched |
| Fix (verified in fork first) | PASS | revert → fork re-run returns the bounded string; fork destroyed clean |
| Re-drive green | PASS | same task to completion, correct answer, faults section blank, watch silent |
| Gates green | PASS | full `bin/test-cljs` green, 0 un-expected markers (counts in the roadmap note) |

## Gaps found

1. **Crashed turn strands its reply blob (FIXED, registry C51).** The reply
   blob is captured BEFORE the eval batch, but its ref was attached only at
   `close-turn!` — a mid-eval `:core` crash leaves the blob unreachable from
   `inspect/turn` (`::reply` empty on the very turn you most want to read).
   The drill recovered it by querying `:my.blob/hash` rows by timestamp —
   out-of-band knowledge, so scored as the one imperfection inside the
   turn-replay link. Fix: `ask-and-eval-reply!` now transacts the
   `:seon.agent.turn/reply-blob` ref eagerly at capture time (best-effort;
   `close-turn!`'s later merge is an idempotent re-assert).
2. **Non-malli-path repro is form-level, not args-level (BY DESIGN, no
   action).** The bundle's honest `::note` says so and points at the turn's
   eval forms; recovering the failing form worked through the linked turn +
   blob. Honest, not fabricated — as specced.
3. **`watch-faults` false-alarms on stream EOF (FIXED, registry C52).**
   Found while closing the drill: stopping the re-drive's SILENT watch
   (TaskStop → TERM) killed the tail, the fifo read loop fell through with
   an empty `marker`, and the script still printed "✗ CORE FAULT detected"
   (blank marker line) and exited 0 — a ghost alarm a background-task
   harness would triage. (The re-drive watch verdict stands: zero markers in
   the log, heartbeat-only context, no exit before the TaskStop — the "fire"
   was the stop artifact.) Fix: the empty-marker fallthrough now prints
   "○ watch ended without a fault marker … NO core fault detected" and exits
   3; exit 0 is reserved for a real marker. Shell-tested both ways on a
   scratch `--cluster` log (injected marker → alarm with text; TERM → honest
   line, exit 3).

## Residue check

Fork cluster destroyed (dir + registry). Drill fault datom + probe entity +
drive rows wiped by `cluster reset default`. The plant never entered git
(`git diff` on `render/value.cljs` empty). The only kept change is the C51
fix in `src/seon/agent/turn.cljs` (committed with explicit pathspec).
