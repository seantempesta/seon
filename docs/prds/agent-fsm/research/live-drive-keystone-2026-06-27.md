---
type: research
status: active
tags: [research, agent]
---

# Live-drive verification — keystone (seed-copy ctx) + rename + slot primitive

Date: 2026-06-27
Agent driven: `dgS-2606271925` (auto-booted, DeepSeek) on the live default-cluster pod (`http://127.0.0.1:7890`), backed by wire-server.
Method: real `POST /chat` user messages; observation via `mcp__seon_cljs__eval` (session `default`). No code edited.

## TL;DR — VERDICT

The newly-landed system is **LOAD-BEARING and ROBUST.** All three pieces verified against the running pod:

1. **Phase 1 rename** (`seon.ctx` → `seon.agent.ctx`): complete on the pod track. `src/seon/ctx.cljs` is GONE (renamed to `src/seon/agent/ctx.cljs`); no `seon.ctx` refs remain in pod `.cljs`. The prompt renders without rename leftovers. (The `seon.ctx` `.clj` files under `src/seon/` are the PAUSED JVM lane — a separate sibling, not a leftover.)
2. **Phase 2 keystone** (seed-copied `:seon.agent/ctx` blocks, no render-merge): a fresh agent owns 9 seed-copied blocks at creation; `install!`/`remove!` let the agent shape its OWN context (verified live, full cycle).
3. **`(slot :name)` primitive**: places a named block's HTML render into a layout hole, guarded (missing/throwing block → error tile, never-crash).

The agent **did build a working workout tracker end-to-end** driven by the new seed-copied context — registered a schema, stored data, wrote a correct summary fn, and reported the right total.

**Honest caveat:** the build succeeded on the SECOND user message. The FIRST attempt fizzled because DeepSeek **parroted its own prompt** (re-emitted the boot greeting + the boot `(wait)` verbatim), which parked the run before it built anything. This is a MODEL-quality failure, NOT a system robustness failure — the system caught every malformed form cleanly and never crashed. See "First run" below.

## End-to-end build — YES (second run, evidence)

User message (run `fml-2606271955`, ~10 turns, ctx 53k→56k chars ≈ 13k→14k tokens, halted via the wait verb):

- **Schema registered** — `installed-schema` now carries, with correct types:
  - `:my.workout/id` → `:db.unique/identity`, `:db.type/string`
  - `:my.workout/date` → `:db.type/instant`
  - `:my.workout/exercise` → `:db.type/string`
  - `:my.workout/reps` → `:db.type/long` (registered `:int`)
- **Data stored** — one example workout: `["w1" #inst "2026-06-27T23:55:54Z" "squat" 45]`.
- **Working fn** — `my.agent.dgS-2606271925/workout-summary`, defined in the agent's OWN home namespace, source:

```clojure
(defn workout-summary
  "Sum total reps across all workouts."
  {:malli/schema [:=> [:cat] :int]}
  []
  (or (db/query '[:find (sum ?r) .
                  :where [?e :my.workout/reps ?r]]) 0))
```

- **Correct result** — the agent's own `(workout-summary)` eval (`GGy-2606271956`): `:seon.eval/result-edn "45"`, `:ok? true`.
- **Accurate user reply** (`AMV-2606271956`): described the schema, "Example logged: 1 squat workout, 45 reps", "Summary: (workout-summary) → 45 total reps", plus a copy-paste template to log more and a sensible follow-up question. The number is correct (one workout, 45 reps → 45 total).

This is the WHOLE stack exercised: `schema/register!` → `db/transact!` → `defn` into the program graph → `db/query` aggregate → `message/user`.

## Robustness — the system never crashed; every malformed form surfaced loudly

DeepSeek emitted several hallucination artifacts across both runs. The system handled all of them as values — no crash, no broken assembly:

- **Stray `}` forms** (`mAu-2606271953`, `EGz-2606271956`, `:ok? false`) → rendered the guarded READ ERROR block: `"✗ READ ERROR — this form did not parse, so it DEFINED NOTHING … Do NOT call or wire anything that depended on this form …"`. Loud, self-healing.
- **Prose-as-form** (`lNK-2606271956`, `:ok? false`) → the model wrote a narration sentence as a form; caught as a failed eval, not a crash.
- **Faked `;=> …` result lines** → the transcript renderer rewrote them to `;; [unverified narration — not a real result]` (`neutralize-result-claims`). The model cannot smuggle fake results into the log. Working as designed.

No `"could not be rendered"`, no instrumentation/malli `invalid-input`/`invalid-output`, no render failures attributable to the new ctx path appeared in `logs/pod.log` during the drive.

## `install!` / `remove!` side-check — agent shapes its own context (verified)

In `(seon.db/with-agent "dgS-2606271925" …)` scope:

- `install!` of `{:seon.agent.ctx/name :note :seon.agent.ctx/priority 50 :seon.render/ai "test note …"}` → block count 9 → 10; `:note` present in `:seon.agent/ctx`; renders in the prompt bracketed as `;;; ┌─ note ─ … ;;; └─ end note ─`, placed in priority order (after open-todos:45, before inventory:97).
- `remove! :note` → block count 10 → 9; `:note` gone from `:seon.agent/ctx` AND from the rendered prompt (component child cascade-retracted).

## `slot` primitive — verified (web/canvas layout side)

`(seon.render/slot {:seon.db/db @conn :seon.agent/id "dgS-…"} block-name)`:

- Existing block `:transcript` → `[:div {:id "tile-transcript" :data-slot "transcript"} <real-html-body>]`.
- Missing block `:note` (after remove!) → `[:div {:id "tile-note" …} <guarded error tile>]` ("no block named …"), NOT a crash. never-crash-always-surface confirmed.

## Context quality (seed-copy in practice) — coherent

Baseline prompt ≈ 10k tokens (40,024 chars), 9 seeded blocks. Reads cleanly:

- The stable→volatile cache boundary lands correctly (soul/namespaces/shared-instructions above; live-tile/transcript below).
- Empty blocks (warnings, open-todos, relevant-source, inventory, shared-instructions when empty) correctly VANISH — reactive, no empty headers.
- No overlap/repetition between blocks; `my.kb` / `my.kb.shared` / `seon.agent.todo` render in full as the worked manual; the agent's own (empty) home ns renders as a one-line workspace stub.
- No stale `:seon.ctx`-rename text in the agent-facing output.

## First run — the parrot fizzle (model issue, not system)

Run `OBS-2606271953` (the first workout message) closed `:waited` after 1 turn having built nothing. The transcript shows DeepSeek's turn-1 completion **regurgitated its own prompt**: it reproduced the transcript header, the boot greeting comment, then re-emitted `(message/user "Hi — I'm up …")` (sent a duplicate greeting, msg `mon-2606271953`) and re-emitted `(wait "awaiting first task")` — the parroted `(wait)` parked the run. Its tail DID show real understanding ("Plan: three todos — schema, log fn, summary fn …"), but the earlier parroted `(wait)` had already doomed the turn. A second, more directive message ("build it now, do NOT call (wait), do NOT re-send your greeting …") produced the full successful build above.

Root cause: weak-model prompt-echo. The boot **greet-then-park** exemplar sitting at the head of the transcript is "sticky" for a weak model — a context-design observation worth noting (a stronger model would not parrot it), but not a system defect.

## Flagged smell (pre-existing, NOT this work, did not disrupt the drive)

`logs/pod.log` carries a RECURRING error (381 occurrences, ~once per `seon.client` heartbeat / minute):

```
:error datahike.db.utils  Nothing found for entity id [:seon.agent/id "UyL-2606262234"]
:error datahike.db.utils  Nothing found for entity id [:seon.agent/id "dVB-2606270309"]
  data: {:error :entity-id/missing, :entity-id [:seon.agent/id "dVB-2606270309"]}
```

Two STALE agent ids (`UyL-2606262234`, `dVB-2606270309`) — neither is the driven agent — are repeatedly resolved by the `seon.client` heartbeat and fail `:entity-id/missing`. They don't exist in the live store (likely a prior cluster state / runtime roster the heartbeat still iterates). This is background noise + wasted lookups every heartbeat, unrelated to the seed-copy/install/rename/slot work; the drive's agent (`dgS`) was unaffected. Worth a follow-up task: the heartbeat should drop agent ids whose entities are gone instead of re-resolving dead refs each beat.

## Concurrent hot-reload

No cljs-watch hot-reload disrupted the drive (`cljs-watch` was running; no rebuild landed mid-drive). The pod stayed healthy throughout; no restart was needed.

## Leads to investigate — make the right thing intuitive / corrective

Ordered by leverage on the "perfect agent experience" goal. None blocked this drive; all are real and worth a focused fix agent.

### L1 — `(wait)` parks the run from ANYWHERE in a turn, even when parroted (HIGH)

`src/seon/agent/loop.cljs` (the `:wait` verb path; `:waited → :wait`, lines ~147, ~229, ~274) + the wait verb itself. A `(wait …)` call closes the run **synchronously inside the turn**, regardless of position. In run `OBS-2606271953` the model emitted, in order: re-greeting, `}`, `(wait …)`, `(seon.agent.todo/complete! …)`, `""` — the `(wait)` at position 3 closed the run while two real forms still followed it. So one accidental/echoed `(wait)` anywhere in a block silently kills the whole run. This is the single biggest "the system punished an honest mistake" finding.
Corrective options to weigh: (a) only honor `(wait)` if it is the LAST actionable form in the turn (forms after it signal the model didn't actually mean to park); (b) if `(wait)` is followed by more forms, surface a loud readline note next turn ("you called (wait) but kept working — the run parked; send a message or re-trigger to continue") instead of going silent; (c) make park require an explicit terminal sentinel. Investigate which is most intuitive.

### L2 — boot greet-then-park exemplar is "sticky"; a fresh inbound doesn't visibly re-orient a weak model (HIGH)

`src/seon/agent/ctx/transcript.cljs` (transcript head render) + the readline/turn-header composition (`seon.agent.ctx` `format-eval-row` / readline). The transcript opens with the boot "I'm up. Saying hello … parking until they have work" + `(message/user "Hi…")` + `(wait …)`. On the first workout message DeepSeek **continued that pattern** — re-sent the identical greeting and re-emitted `(wait)` — instead of acting on the new `◀ from user` message. The fresh waking-inbound line is present but not emphasized enough to break a weak model's echo.
Corrective options: when the turn opens on a fresh waking inbound, add a short, prominent readline directive ("a NEW message arrived above — respond to IT; do not repeat earlier turns"); and/or de-weight the boot greet/park exemplar once real work exists; and/or detect a duplicate outgoing `message/user` identical to a recent one and warn. The fix should make "respond to the new ask" the path of least resistance.

### L3 — `seon.client` heartbeat re-resolves dead agent ids every beat (MEDIUM)

`src/seon/client.cljs` (heartbeat) + wherever its agent roster originates. 381 `:error datahike.db.utils Nothing found for entity id [:seon.agent/id "UyL-2606262234" | "dVB-2606270309"]` lines, ~once/minute, for two stale ids that don't exist in the store. Background noise + wasted lookups + real `:error`-level log spam that masks genuine errors. The heartbeat should drop ids whose entities are gone (or stop persisting a roster across cluster resets) rather than re-resolving missing refs each beat. Pre-existing, unrelated to the keystone work.

### L4 — instrumentation input errors at the `seon.db` boundary are opaque (MEDIUM)

`src/seon/db.cljs` — map-in `pull` expects `:seon.db/pull-pattern` + `:seon.db/ref` (`::pull-request`, lines 188-194). Calling it with the wrong keys (`:seon.db/pattern` / `:seon.db/eid`) throws a bare `:malli.core/invalid-input` with NO hint about which key is wrong or what was expected. Contrast the EXCELLENT `db/query`/`db/pull` attr-guard error ("Query names attribute(s) [:x] that this database has never seen … Most likely a typo: check spelling … (seon.schema/register! …)") — that is the gold standard. The instrument boundary should humanize the malli explain (name the offending key + expected keys / "did you mean") so an agent that fat-fingers a request key self-corrects. This is squarely the "humanized message + explain-map" design from the data-model doc not yet reaching the instrumentation path.

### L5 — blank-form evals count as `:ok?` rows (LOW)

The model emitted empty-source forms (`qsA-2606271953`, `eND-2606271956`, `:seon.eval/source ""`, `:ok? true`). They no-op but persist as eval rows and render in the transcript. Minor log/ctx noise. Consider dropping a whitespace-only form before it becomes an eval, or rendering it inertly.

### Positive patterns to PRESERVE (the corrective experience already done right)

- READ ERROR guard on unparseable forms (the `}` case) — loud, names the consequence ("DEFINED NOTHING … Do NOT call anything that depended on this form"), self-heals. Keep.
- `neutralize-result-claims` rewriting faked `;=> …` lines to `;; [unverified narration — not a real result]` — the model cannot fake results. Keep.
- The `db/query` / `db/pull` never-seen-attr guard message — the model for L4. Keep and extend.
- `slot` + render guards → `:seon/error` tiles instead of crashing siblings. Keep.
