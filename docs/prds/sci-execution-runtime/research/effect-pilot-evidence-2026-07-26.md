---
type: research
status: active
tags: [research, agent, runtime, database, testing]
---

# Effect-identity pilot — live kill/resume evidence

The owner-ruled pilot ("go experiment and come back with helpful data"):
prove the revised effect identity (`effect-identity-contract-2026-07-26.md`)
against the LIVE default-cluster writer with a real process death, before
any architecture prose is written. Implemented at top level per the ruled
pilot exception.

## The falsifier and its verdict

Two OS processes on the source classpath, each opening its own UDS
session to the live writer (`tmp/seon-cluster-default-req.sock`) — the
same topology the cluster JVM host uses. Phase 1 executes one simulated
form — `message!` → `transact!` → `message!` — under
`effect/*request-context*` coordinates `(run-id, form-ordinal 0)`, then
DIES (`System/exit 137`) with nothing settled. Phase 2 is a NEW process
re-executing the identical coordinates.

Verdict (run `pilot-1785101927`, 2026-07-26):

```
PHASE1 m1 id u2hxan2cyt7t op-id ["pilot-1785101927" 0 0]
       t1                 op-id ["pilot-1785101927" 0 1]
       m2 id z18c4rbic4v0 op-id ["pilot-1785101927" 0 2]
PHASE2 identical ids, identical op-ids, every effect replayed? true
VERDICT {:same-message-ids? true, :three-distinct-op-ids? true,
         :all-replayed? true, :first-message-count 1}
```

- **Crash-walk rows 2–3 hold live.** Process death after commit, before
  settlement: re-execution replays; exactly one message in the world.
- **Both blocker issues' core acceptance met**:
  [[../../../seon/issues/effect-operation-id-collides-within-one-form]]
  (three distinct intra-form identities, each committed once) and
  [[../../../seon/issues/effect-operation-id-changes-on-run-recovery]]
  (identity carries no epoch; a new process derives the same one).
- The fixture's own agent allocation replayed too: phase 2 recovered the
  SAME generated id (`tall-liger-51`, `replayed? true`) through
  `allocate!`'s new recovery path.
- Unit layer: `bin/test-writer seon.effect-contract-test
  seon.sci.computed-binding-test seon.sci.eval-test` → 20 tests / 58
  assertions / 0 failures.

## Defects the pilot caught (each found by running, not reviewing)

1. **`allocation-recovery` queried a wire database value with `d/q` and
   got silently empty** — a descriptor is not an index; the pre-check
   missed every committed allocation and surfaced the writer's conflict
   instead of recovering. Fixed to query through the one `seon.db/query`
   facade (`fd0dab43c`→ this commit). Absence-of-signal class — the
   third instance found today.
2. **Generated identities cannot be asserted directly** — the writer
   refuses non-allocated `:seon.agent/id` values (good guard, learned
   live); fixtures must allocate.
3. **Provenance chicken-and-egg** — a transaction creating an agent
   cannot carry that agent as its own provenance; fixture setup needs an
   agentless context.
4. **The writer AOT closure and AOT+CDS publication go stale on every
   JVM source edit** — two rebuild cycles (~45 s each) were paid inside
   this pilot's loop, and `bin/test-writer` refuses rather than
   rebuilds. This is the velocity tax the standing ruling names a
   development incident; the template-store / indexer stage owns it.
5. **The `:client` build has been broken since pod-cut group 4**
   (`9ebd05588` deleted namespaces `client.cljs` still requires), which
   blocks `bin/seon up` at the watcher — and the client build hooks are
   still the pages producer, so it must compile until the JVM indexer
   replaces it. A lane (`client-require-cut`) is cutting the 19 dead
   requires.

## The weirdness count (owner acceptance: minimum weirdness)

The agent-visible form is standard Clojure — direct calls to the owning
APIs, no envelope, no identity argument, no effect vocabulary, no
annotation:

```clojure
(message/message! {:seon.agent.message/content "..."
                   :seon.agent.message/to [[:seon.agent/id "root"]]})
(db/transact! {:seon.db/tx-data [...]})
```

**Agent-visible non-standard constructs beyond `:malli/schema`: 0.**
The entire machinery is system-side: one dynamic context bound at the
tool-call boundary, one counter, owner-side identity derivation, and
`allocate!` recovery.

## Mechanism inventory (what the pilot actually needed)

- `seon.effect` (≈110 lines): request-context + positional op-id +
  admission predicate. No envelope, no dispatcher, no enum — all three
  died during owner review before implementation.
- Owner-side derivation: one `(or explicit (effect/next-op-id!) uuid)`
  line each in `seon.db/transact!` and `seon.agent.message/message!`.
- `seon.db.id/allocate!`: recovery-by-identity before candidate
  generation, plus the post-error re-check.
- The computed SCI surface: program-function facts filtered by the
  agent's home-require policy (`seon.sci.eval/base`), invocation
  bindings established at tool-call time on the eval thread.

## Honestly not yet proven (coverage debt, named)

- **The generated writer-suite properties** (message replay, transact
  replay as `test.check` properties over the real writer fixture) are
  NOT yet in `bin/test-writer` — the live falsifier ran twice tonight
  and is reproduced below, but a proof without a recurring surface
  counts as NOT COVERED. Port target: the
  `seon.db.request-receipt-test` fixture idiom.
- **The driver-integrated path** (run loop → plan → `execute-form!` with
  the new `form-evaluate!` wiring) has unit coverage but no live
  end-to-end drive yet — blocked on the client build for a full
  `bin/seon up`.
- **`my.fs` + receipt-before-dispatch + `redispatch-on-crash`** (the
  ledgerless half of the contract) — deliberately deferred; crash-walk
  rows 4–6 are design-proven only.
- Claim-epoch fencing still increments and fences (issue acceptance
  item) — existing driver fence tests cover the fence; the pilot proves
  identity independence from it.

## Reproduction

The falsifier lives at `tmp/effect-pilot/{falsifier.clj,run.sh}`
(project-local scratch; reproduced here in full so the evidence
survives scratch cleanup — the recurring port is the writer-suite
property above). Run: `bash tmp/effect-pilot/run.sh` against a live
default cluster. Phase 1 must exit 137; phase 2 exit 0 is the verdict.
