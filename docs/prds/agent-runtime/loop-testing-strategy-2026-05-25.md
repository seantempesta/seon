---
type: prd
status: draft
tags: [prd, agent, testing]
---

# Agent runtime loop — testing strategy

How the design in [unified-loop-v1.md](unified-loop-v1.md) is verified.
Five layers, from pure-fn unit tests to tx-log replay properties. Each
layer's tests live in `test/seon/...` under the pod's CLJS test
harness; runner is `(user/run-tests 'seon.<ns>-test)` for the JVM
fixtures and `bin/seon test pod` (or REPL-driven equivalent) for the
CLJS pod side.

The five layers correspond to the four scenarios in
`loop-walkthrough-2026-05-25.md` plus the resumability + replay
guarantees the event-sourcing model gives us for free.

## Layer 1 — handler unit tests

**Subject.** Each handler fn is a pure value:
`(db, tx-report, agent-id) -> {:tx [...] :effects [...]}`. No DB
writes inside the fn; no Promises; no globals beyond schema lookup.

**Pattern.** For each handler in `seon.runtime/*`:

1. Build a fixture DB via `seon.handler.test-utils/empty-db` (creates
   an in-memory datahike conn, registers all `seon.handler/*` and
   `seon.message/*` schemas).
2. Transact a known starting state.
3. Synthesize a tx-report (the real `db/listen!` shape — see
   `seon.db/listen!` in HEAD) using
   `seon.handler.test-utils/synth-tx-report`.
4. Call the handler directly. Assert returned `{:tx :effects}`.

```clojure
(deftest wake-on-message-to-emits-wake
  (let [db    (-> (empty-db) (with-agent "A-test"))
        report (synth-tx-report
                 {:added [[1 :seon.message/to [:seon.agent/id "A-test"]]]})
        result (seon.runtime/wake-on-message-to
                 {:seon.db/db db :seon.db/tx-report report
                  :seon.agent/id "A-test"})]
    (is (= [{:effect/type :wake :agent "A-test"}] (:effects result)))
    (is (empty? (:tx result)))))
```

**Generative variant.** Each handler's `:match` shape is a Malli
schema (see `:seon.handler/match` in §1 D3 of the design). Use
`malli.generator/generate` over `:seon.handler.match/value?` to
property-test that:

- For matching values → handler emits expected effect shape.
- For non-matching values → handler emits empty `{:tx :effects}`.

**Location.** `test/seon/runtime/<handler-name>_test.cljs` (one file
per handler).

## Layer 2 — dispatcher integration tests

**Subject.** The dispatcher chain: tx commits → `d/listen!` callback
walks handlers → matched handlers invoked → returned `:tx` applied →
returned `:effects` queued. Plus the origin-skip and depth-cap rules.

**Pattern.**

1. `with-test-pod` macro (defined below) gives a fresh datahike conn
   + the four substrate handlers loaded + `install-dispatcher!` run.
2. Transact a stimulus via `transact-and-tick!` which commits AND
   drains the dispatcher synchronously (uses a deterministic scheduler
   instead of `js/setTimeout 0`).
3. Assert on resulting datoms + recorded effect descriptors.

```clojure
(deftest user-message-wakes-stopped-agent
  (with-test-pod [pod]
    (transact-and-tick! pod
      [{:seon.message/id "msg-1" :seon.message/role :user
        :seon.message/to [[:seon.agent/id "A"]]
        :seon.message/content "hi" :seon.message/at (synth-inst)}])
    (is (= :running (agent-state pod "A")))
    (is (= [{:effect/type :wake :agent "A"}] (recorded-effects pod)))))
```

**Cycle-guard test.** Register a synthetic handler matching
`:test/echo` whose fn always returns `{:tx [{:test/echo 1}]}`. Transact
once with `origin :user`. Assert handler fired once and stopped (because
the follow-up tx is `origin :handler`, default on-origin skips). Then
re-register with `:on-origin #{:user :handler}`; assert depth-cap fires
at 16 and a `:seon.system/error/kind :depth-exceeded` entity exists.

**Effect-interpreter isolation.** Install a `:run-llm` interpreter that
throws. Assert an `:seon.async-result/ok? false` entity lands with
a structured error envelope. No exception escapes
`transact-and-tick!`.

**Location.** `test/seon/runtime/dispatcher_test.cljs`.

## Layer 3 — loop-level scenario tests

**Subject.** Each scenario in `loop-walkthrough-2026-05-25.md` becomes
one automated test.

**Pattern.**

1. Set up the initial state literally as the walkthrough specifies
   (the literal maps in the walkthrough copy-paste cleanly).
2. Apply the stimulus (one transact).
3. Drive the dispatcher to **fixpoint** via `tick-to-fixpoint!` —
   repeatedly drain the dispatcher queue + run scheduled effects until
   no more handlers match and no more effects queue.
4. Assert final state matches the walkthrough's "Final state" block.
5. Assert `(seon.render/assemble-ai-context {:seon.db/db @conn
   :seon.agent/id ...})` produces ctx entities in the expected order.

```clojure
(deftest scenario-1-single-user-message
  (with-test-pod [pod]
    (setup-agent! pod "A-abc123def456" {:max-steps 8})
    (stub-llm! pod (responses [";; addition\n(+ 2 2)" ";; 2+2 = 4"]))
    (transact-and-tick! pod (user-message "A-abc123def456" "what's 2+2"))
    (tick-to-fixpoint! pod)
    (is (= :stopped       (agent-state pod "A-abc123def456")))
    (is (= 3              (message-count pod "A-abc123def456")))
    (is (= 1              (eval-count pod "A-abc123def456")))
    (is (= [:stable :stable :stable :recent-eval :conversation]
           (ctx-order pod "A-abc123def456")))))
```

The four scenarios → four tests. The error-variant of Scenario 3 is a
fifth test (same setup, LLM stub rejects).

**Location.** `test/seon/runtime/scenarios_test.cljs`.

## Layer 4 — resumability tests

**Subject.** Pod restart preserves causal continuity.

**Pattern.**

1. With `with-test-pod`, build a state where an agent is `:running`
   mid-turn (start a turn, halt the test-pod scheduler before the
   close-tx fires).
2. Snapshot the LMDB store (or simulate restart by `unlisten!` +
   reset of all in-process state then re-opening the same conn).
3. Run `seon.runtime/boot!` (the post-restart entry point):
   `replay-program-graph!` → `install-dispatcher!` → interrupt-detect
   transacts a system message.
4. Assert: a `:seon.message/role :system` exists for the agent with
   content referencing the interrupt; `:seon.system/error/kind
   :interrupted-mid-turn` exists too; `wake-on-message-to` fired and
   the agent is back to `:running`.
5. Drive to fixpoint; assert the agent's next render includes the
   interrupt notice.

```clojure
(deftest restart-resumes-interrupted-agent
  (with-test-pod [pod]
    (setup-agent! pod "A" {:max-steps 8})
    (start-turn-but-halt! pod "A")            ; agent left :running
    (simulate-pod-restart! pod)
    (is (= :running (agent-state pod "A")))   ; wake fired
    (is (interrupt-message-present? pod "A"))
    (is (contains-ctx? pod "A" :seon.ctx.interrupt))))
```

**Timing.** Assert boot-to-wake under 200ms for an agent population of
10 (uses a deterministic clock, so the assertion is on tx count, not
wall time — but include a real-clock variant in a perf suite).

**Location.** `test/seon/runtime/resume_test.cljs`.

## Layer 5 — tx-log replay properties

**Subject.** The event-sourcing claim: re-running the tx log against a
fresh conn reproduces the same state (modulo non-deterministic
timestamps, which the test clock pins).

**Pattern.**

1. Pick (or generate) a sequence of `:seon.db/origin :user` stimuli.
2. Run them through one pod to fixpoint; capture `db1`.
3. Extract the full tx log (`d/tx-range`).
4. Replay every tx against a fresh conn, preserving `:tx-meta`
   (origin etc.). Replay does NOT re-run handlers — the events ARE
   the handler outputs from the first run.
5. Assert `db2 == db1` modulo eid mapping (compare via Datalog
   queries that don't rely on raw eids).

```clojure
(defspec tx-log-replay-reproduces-state 50
  (prop/for-all [stimuli (gen/vector (gen-user-message) 1 20)]
    (let [db1 (run-stimuli-to-fixpoint stimuli)
          db2 (replay-tx-log (tx-log db1))]
      (db-equal? db1 db2))))
```

A second property: **idempotence of handler-emitted tx** — replaying
a `:seon.db/origin :handler` tx is a no-op past the initial commit.
This catches handlers that accidentally do non-idempotent work in
`:tx` (the design forbids; the property test enforces).

**Location.** `test/seon/runtime/replay_test.cljs` using
`clojure.test.check` via the existing `test.check` setup.

## Substrate fixtures

The fixtures the layers above lean on:

### `with-test-pod` macro

```clojure
(defmacro with-test-pod [[pod-sym] & body]
  `(let [conn#  (datahike.api/connect "datahike:mem://test")
         clock# (atom (synth-inst-base))   ; deterministic
         sched# (atom [])                  ; in-place effect queue
         ~pod-sym {:conn conn# :clock clock# :sched sched#}]
     (seon.schema/load-all!)
     (seon.handler/register-substrate! ~pod-sym)
     (seon.runtime/install-dispatcher! ~pod-sym)
     (try ~@body
          (finally (datahike.api/release conn#)))))
```

Gives every test:

- A fresh in-memory datahike conn.
- A synthetic clock (`(synth-inst)` advances by a fixed delta).
- An effect scheduler that records (not executes) effects until
  `tick-to-fixpoint!` drains them — makes ordering deterministic.

### `transact-and-tick!`

Commits a tx, runs `d/listen!`'s callback synchronously, applies any
returned `:tx`, queues any returned `:effects`, then **does NOT**
recursively tick — the caller chooses.

### `tick-to-fixpoint!`

Runs scheduled effects (each may transact, which re-fires the
dispatcher), looping until both the effect queue and the listener
queue are empty. Bounded by 1000 ticks to catch runaway loops.

### `seon.handler.gen` (generators)

- `gen-handler-entity` — Malli generator over the
  `:seon.handler/register!-request` schema.
- `gen-tx-report` — synthesizes a tx-report with a controlled set of
  added/retracted datoms.
- `gen-user-message` — for Layer-5 properties.

### Stubs for external IO

- `stub-llm!` — replaces the `:run-llm` interpreter with a queue of
  canned responses.
- `stub-fs!` — same shape for `:fetch-url` / `:read-file` when those
  effect kinds land.

## REPL validation before committing

At least one test per layer is REPL-validated against current HEAD's
infrastructure before this strategy doc lands as `status: active`:

1. Layer-1: write `wake-on-message-to-emits-wake` (above) against the
   current `user-message-handler` (one-line wrap to match the new
   shape). Run via `(user/run-tests 'seon.runtime.wake-on-message-to-test)`.
2. Layer-2: write the cycle-guard test against the existing
   `setTimeout` re-entry. Run.
3. Layer-3: Scenario 1 is the closest match to current code; write
   that one with stubbed LLM.
4. Layer-4: write the restart test against the current
   `replay-program-graph!` path; this exists today, just needs the
   handler-interrupt hook.
5. Layer-5: build `replay-tx-log` against current datahike-cljs
   `d/tx-range`; assert one property.

Each REPL session produces a `(user/repl-<session>)` artifact that
lands in `tmp/test-replay/<layer>.edn` for the implementation agent to
pick up.

## What this strategy does NOT cover (yet)

- **Multi-pod replay.** Layer 5 assumes one pod. Cross-pod replay
  (relevant only once a sidecar exists) is out of scope.
- **Real wall-clock performance.** The 200ms resume target uses a
  synthetic clock; a separate perf suite (Layer 4½?) under
  `test/seon/perf/` runs the same scenarios against the real Node
  event loop.
- **WASM-host effect interpreters.** When `:fetch-url` / `:read-file`
  land via WIT imports, those interpreters need host-mock stubs. Add a
  `with-test-host` analogue to `with-test-pod` then.

## Cross-references

- [unified-loop-v1.md](unified-loop-v1.md) — design under test.
- [loop-walkthrough-2026-05-25.md](loop-walkthrough-2026-05-25.md) —
  Layer-3 scenarios.
- `docs/prds/agent-runtime/loop-design.md` §10 acceptance criteria —
  what these tests collectively prove.
- `CLAUDE.md` Testing section — how tests are run in the JVM REPL.
- `/clojure-testing` skill — fixture, generator, mock patterns.
