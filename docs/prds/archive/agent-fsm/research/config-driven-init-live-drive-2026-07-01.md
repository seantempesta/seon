---
type: research
status: completed
tags: [research, agent, config, flow, milestone]
---

# Config-driven agent-init — LIVE DeepSeek closeout drive

THE closeout gate: a real DeepSeek agent booted + ran multi-turn + resumed across
a restart on the config-driven system. Default cluster, DeepSeek adapter (live,
key-configured). 2026-07-01.

## TL;DR — PASS, with one honest rough edge (not in config-init scope)

A fresh `cluster reset default` booted root via the new `init-agent!` path; root
ran **14 turns** of real DeepSeek eval, designed a schema, and a `bin/seon
restart pod` mid-task **resumed root + 2 spawned children from the store** with
every config datom intact. The config-driven init + all CP-4/5 dials work
end-to-end live. The one rough edge (agent-authored `schema/register!` reports
`:ok` but the attr isn't installed in the wire DB, so the stored fact isn't
queryable back) is in the **agent-authored-schema → wire-server persistence
path**, NOT the config-driven-init work.

## Init (via the new init-agent! path) — VERIFIED

- root agent entity seeded; **10 context blocks** (the full default tree).
- config datoms present: `:skill/repl` block (from `:my.skills/load [:repl]`),
  `live-tile content = seon.render.system/system-view` (config-driven root
  canvas), transcript `::result-decay` = `[{0 16384}{2 1500}{5 200}]` reified as
  3 `::decay-level` entities.
- provider `:deepseek`, adapter live (key-configured — real LLM calls).

## Multi-turn — VERIFIED

- **14 turns** ran; **30 evals** executed; run closed cleanly on `:no-forms`
  (the FSM empty-turn streak limit fired correctly after the agent stopped
  producing forms). ctx ~10k tokens/turn.
- verb tools worked: the agent used `todo` (created a plan item), `schema/register!`
  (7 successful registers designing `:my.kb.datastructure` + `.operation`),
  `seon.db/transact!`, `db/store-inventory`.
- the agent AUTHORED new namespaces/schemas live (`:my.kb.datastructure`,
  `:my.kb.datastructure.operation`) with a provenance schema
  (`:my.kb/source` + `:my.kb/confidence [:enum :verified :inferred :uncertain]`).

## Configs driving behavior — OBSERVED

- **`:my.skills/load`** → the `:skill/repl` block seeded + rendered.
- **eval-result decay** → the 3-level schedule seeded on the transcript block
  (reified entities). (The gym scenarios + this drive are too short to age an
  eval past offset 2, so the shrink is inert here by design; the bounding is
  proven separately in [[cp5-balloon-measurement-2026-07-01]].)
- **escape-clipping / render** → ctx rendered full each turn (~10k tokens),
  blocks rendered.
- **live-tile content** → root's canvas = `system-view` (config-driven, the
  deleted hardcoded branch's replacement).

## Memory store→retrieve — PARTIAL (rough edge surfaced)

The agent designed the schema + issued the store transact (`:ok true` in
session), BUT the fact is NOT queryable back:

- `:my.kb.datastructure/name` is in the pod's Malli registry (`register!`
  returned `:ok`) but **NOT in the DB installed-schema**, and the stored row
  returns 0 on query-back.
- ROOT CAUSE (hypothesis, confirmed by the split registry/DB state): a
  agent-authored `schema/register!` registers in the in-memory Malli registry,
  but the subsequent `transact!` did not INSTALL the new attr's datahike schema
  into the wire-server store — so the datom silently didn't persist queryably
  even though the eval reported `:ok`. This is the **agent-authored-schema →
  wire persistence path**, a real rough edge to fix, but SEPARATE from the
  config-driven-init work.
- Also two agent-OWN errors (correctly surfaced by the error render, not system
  bugs): `:O(1)`/`:O(log-n)` as EDN keywords are unreadable (`:O(1)` breaks the
  reader → "did not parse, DEFINED NOTHING"); a later transact referenced an
  undefined `log32-n` symbol ("ran NOTHING").

### Task #92 follow-up (2026-07-02) — mechanism PROVEN sound; test coverage hole closed

Investigated the "store `:ok` but retrieve fails" rough edge. The runtime
register→install→transact→query path is CORRECT and was re-proven live end-to-end
against the REAL wire store (not just the pod-local view): a fresh
`schema/register!` of a new attr, then `db/transact!`, then `db/query` returns the
row; `seon.server.wire` (JVM socket REPL 7891) confirmed both the attr's datahike
schema (`:db.type/string`) AND the datom landed in the store — inside a
`with-agent` scope too. `seon.db.internal/transact!*` awaits
`ensure-datahike-attrs!` (which derives the datahike attr-decl via the Malli
bridge and forwards it as a schema-tx over the `:seon-wire` PWriter, schema-before-
data) BEFORE the data tx, so the wire-server always installs schema ahead of data.

The drive symptom did NOT reproduce on a clean pod (the drive's store was since
reset, and the drive interleaved reader-error evals — `:O(1)` etc.). The REAL,
fixable finding: the db suite had a COVERAGE HOLE — every `db_test.cljs` transact
runs against a `fresh-conn` that PRE-installs its attrs via a hardcoded
`smoke-schema`, so NOTHING exercised the runtime installer
(`ensure-datahike-attrs!`). Closed with a regression test
(`transact!-installs-runtime-registered-attr-then-queries-back`) that registers a
brand-new attr, asserts it is NOT pre-installed, transacts, and asserts the attr
becomes installed AND the datom queries back — the exact split-state the drive saw.

### Task #92 code-read — `discard-registrations!` rollback hypothesis REJECTED (2026-07-02)

The remaining suspect was: a same-batch error (e.g. the reader-error `:O(1)`)
triggers the eval error-rollback `discard-registrations!` and UNDOES a
successful earlier `schema/register!` — producing exactly the drive's
register-`:ok`-but-not-installed split state. **Read the eval path; that
hypothesis does NOT hold.** Root cause of the split state is elsewhere (and the
normal path is sound — see the follow-up above). Evidence, file:line:

**1. `discard-registrations!` is PER-FORM, scoped to THIS form's own new keys.**
`src/seon/eval.cljs:3059-3069` (`eval-form-entry!`): the rollback fires only
`(when-not (:ok result))` for the *same form's* result, and removes only
`(set/difference (schema/current-keys) schemas-before)` where `schemas-before`
was snapshotted at the START of THAT form (`:2988`). A `register!` in an
EARLIER, successful form is not in that diff, so it is never dropped. The
docstring at `src/seon/schema.cljc:288-291` states the same contract ("`ks` is
the keys NEWLY registered during the failed eval … a pre-existing key is never
in `ks`").

**2. The batch is a sequential per-form fold, one `with-tx-context` + `eval-id`
per form, each `await`ed to completion before the next.** `eval-batch!`'s
`doseq` (`src/seon/eval.cljs:3452-3457`) mints a fresh `eval-id` and opens a
per-form `db/with-tx-context` per entry; `dispatch-eval-entry!` →
`eval-form-entry!` → `record-eval!` are all `await`ed. So form N's register +
its DB tee complete before form N+1 (the error form) even parses. There is no
per-BATCH rollback of prior forms.

**3. The register! DB write is committed per-form, not deferrable by a later
error.** Inside a batch the eager self-tee is intentionally DEFERRED (the
tx-context carries an `:seon.db/eval-id`, so `tee-registered-schema!` no-ops —
`src/seon/eval.cljs:2320-2329`); the durable write is done by the GATED
detect-and-tee (`build-tee-entities` in `record-eval!`) which runs **only on a
successful eval** (`:3127`) and rides that form's own atomic `record-eval!` tx
(`:3177-3186`). A successful `register!`+`transact!` form therefore commits its
`:seon.schema` row and its data in its own tx, which is fully awaited before the
later error form runs. The datahike attr install (`ensure-datahike-attrs!`,
schema-before-data) likewise already ran during that successful form.

**4. A reader error does not reach the rollback at all.** `discard-
registrations!` lives ONLY in `eval-form-entry!`. A reader error (`:O(1)`) is
segmented into its OWN isolated `:kind :read` entry — parse-forms records the
bad span alone and "Forms BEFORE and AFTER the failure still parse"
(`src/seon/repl/internal.cljc:70-76`). A `:read` entry routes to the repair /
sharpened-read-error path in the `doseq`, NEVER to `eval-form-entry!` unless
repaired — so it triggers no `discard-registrations!` and cannot touch a prior
form's registry keys or DB rows.

**Verdict:** `discard-registrations!` is correctly scoped (per-form, this-form's
own new keys, in-memory Malli registry only — it never touches datahike, so
there is no split-state it could even create). It is NOT the culprit. The drive
symptom is explained by the follow-up above (the drive's store was since reset;
the normal register→install→transact→query path re-proves sound end-to-end
against the real wire store; the real fix was the closed test-coverage hole).
The interleaved reader-errors in that drive are agent-OWN errors surfaced
correctly, not a mechanism that reverts good registrations.

**Repro to run LATER (after the benchmark frees the pod)** — the exact sequence
this task named, to CONFIRM no split state. Run against a fresh child agent via
`mcp__seon_cljs__eval`; the three forms in ONE batch (one reply / one
`eval-batch!` call):

```clojure
;; form 1 — succeeds
(seon.schema/register! :my.probe92/name :string)
;; form 2 — succeeds (installs the datahike attr + lands the datom)
(seon.db/transact! {:seon.db/tx-data [{:my.probe92/name "hello-92"}]})
;; form 3 — reader error in the SAME batch (`:O(1)` breaks the reader)
(count :O(1))
```

Then, in a LATER eval (so the earlier tx is fully committed), assert the good
registration SURVIVED the same-batch reader error:

```clojure
;; in-memory Malli registry still has the attr
(contains? (seon.schema/current-keys) :my.probe92/name)   ; => true expected
;; datahike attr installed + datom queryable back
(seon.db/query '[:find ?n :where [_ :my.probe92/name ?n]]
               @seon.db/*conn*)                            ; => #{["hello-92"]}
```

Confirm on the JVM wire REPL (7891, `nc`) that `:my.probe92/name` has a
`:db.type/string` schema AND the datom is in the store. Expected: BOTH present —
the reader error in form 3 leaves form 1/2 fully intact. If (contrary to this
read) the attr is missing after the reader error, that would reopen the
rollback-scope question; the code says it will be present.

## Planning + RESUME across restart — VERIFIED (the strongest result)

`bin/seon restart pod` mid-task → roster `:resumed ["djy-…" "kXL-…" "root"]`,
`:minted []`. After restart:

- root resumed (entity + 10 blocks intact);
- **every config datom survived**: 3 decay levels, `:skill/repl` block,
  live-tile `system-view`;
- **open todos survived** — incl. "Design schema for data-structure facts";
- the agent-authored `:my.kb.datastructure` ns survived.

So init + config + the agent's plan + its authored code all persist across a
restart — the agent can resume from its open plan items. Continuity proven.

## Verdict

The config-driven agent-init system (CP-4 → CP-5.5) is PROVEN live: boot,
multi-turn agentic loop, config-driven context, and resume-across-restart all
work end-to-end on a real DeepSeek agent. The memory store→retrieve rough edge is
a real find in the agent-authored-schema wire-persistence path (flag for a
follow-on), not a config-init regression.

## Operational note (for the next driver)

The message-wake trigger did NOT fire the loop from the fresh-boot inbound (a
boot-time `tx-feed pump failed (wire rpc timeout) — re-subscribing` dropped the
tx subscription the wake listener rides). Worked around by opening a run +
`seon.agent.loop/drive-run!` directly. If a fresh agent won't wake on a message,
check the pod's tx-feed subscription health, not the wake trigger.
