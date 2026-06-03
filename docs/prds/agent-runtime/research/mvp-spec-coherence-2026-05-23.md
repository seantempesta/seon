---
type: research
status: completed
tags: [research, prd, agent]
---

# MVP spec coherence audit — 2026-05-23

Audit of `docs/prds/agent-runtime/v1.md` against the live MVP code on
`feature/agent-runtime` (commit `abc236c`). All citations are
`file:line`; live REPL probes are quoted verbatim where applicable.

## TL;DR

- **Risk 3 (intra-tx lookup-refs) passes at datahike but is blocked by a
  seon.db validator false-positive.** Datahike resolves
  `:seon.fn/ns [:seon.ns/name …]` inside the same tx that creates the
  ns — verified live. But `seon.db/validate-entity-values!`
  (`src/seon/db.cljs:551–602`) classifies any `sequential?` value on a
  ref-typed attr as "many-card mixed shorthand" and recurses into the
  lookup-ref tuple as if it were a vector of children. Single-card
  ref attrs that legitimately hold lookup-ref tuples die at the
  validator. **PLATFORM-FLAG** — bootstrap.edn cannot land as a single
  tx until this is fixed.
- **`run-turn!` is close to v1 spec but diverges on six things**, three
  cosmetic (`:tx-meta` plumbing deferred to Platform, log-symbol shape,
  composer interface), three load-bearing (eval-batch return shape
  doesn't carry success counts, `:seon.eval/agent`+numeric-`turn` schema
  is V0 not v1 `:seon.turn/evals` component-ref, assistant message
  written outside the same tx as turn-close). Closing the load-bearing
  three requires changes to `eval-batch!`'s return shape — which is
  Platform-owned and being patched. Coordinate.
- **The six-section composer doesn't exist.** `assemble-ctx`,
  `system-section`, `messages-section`, `current-ns-section`,
  `warnings-section`, `recent-evals-section`, `prompt-section`,
  `registered-warning-predicates`, `register-warning!`,
  `seon.agent/messages`, `seon.agent/evals`, `seon.agent/current-turn`,
  `seon.agent/root-pull`, `seon.agent/reset-ctx!`, `seon.agent/ctx`
  (composer entry), `seon.agent/truncate-edn`, `seon.agent/host-timezone`
  — none exist. `run-turn!` currently calls `seon.render/ai-render`
  pointed at the monolithic `seon.render.default/ctx` composer
  (`src/seon/render/default.cljs:426–444`). MVP work: build the
  six section fns and the composer in `seon.agent`, keep
  `seon.render.default/ctx` as a fallback while migrating.
- **`default-id "seon"` is referenced in three places** outside
  `agent.cljs`: `src/seon/web/serve.cljs:224, 273` (URL query-param
  default), `src/seon/client.cljs:367` (`agent/default-id` passed to
  `setup-agent-ns!`). Flip to strict-12-char requires URL refactor +
  one bootstrap entity that records "the default agent's id". V1-
  deferrable; v1.md §9 acceptance criteria don't depend on id length.
- **Self-correction signal sufficiency is thin.** Failed evals land
  as `:seon.eval/ok? false` (`src/seon/eval.cljs:564–589`) and surface
  via the V0 `recent-evals-block` (`src/seon/render/default.cljs:374`)
  with `:error` payload visible. No dedicated `failed-eval-warning`,
  no resume-replay surfacing path, no severity-tiered rollup. v1 ships
  `slow-eval-warning` only per §5.2; recommend adding a
  `recent-eval-errors` predicate at the same time.

## Q1 — `run-turn!` / `run-agentic-loop!` divergence

`src/seon/agent.cljs:485–623`.

| # | Spec (v1.md §6.1/§6.2) | Code today | Acceptance impact | Additive vs removing | Blocked on Platform? |
|---|---|---|---|---|---|
| 1 | Step 1 transacts session + turn with explicit `:tx-meta {:seon.db/origin :system …}` on the call (v1.md:1055–1058) | `agent.cljs:506–518` transacts session+turn with **no** `:seon.db/opts` `:tx-meta` | None for behavior — but causality lineage is lost until Phase 2.5 item 4 lands. The auto-merge from `current-tx-context` (db.cljs:395–437) already exists; `run-turn!` just isn't entering a scope to populate it. | Additive — wrap each transact in `db/with-tx-context` or pass `:seon.db/opts {:tx-meta …}` explicitly | No — `db/with-tx-context` shipped (`db.cljs:409`). MVP can use it today. The agent.cljs:448 comment claiming this is deferred is **stale**. |
| 2 | Step 2 calls the composer as `(seon.agent/ctx {:seon.db/db @!conn :seon.agent/id id})` returning `{:seon.render/text … :seon.turn/prompt-text …}` (v1.md:887–908) | `agent.cljs:520–523` calls `render/ai-render` (substrate-default) with a symbol-table input from `ai-render-input` (agent.cljs:273–281). Composer returns `{:seon.render/text …}` only — no `:seon.turn/prompt-text` slot. | None — `run-turn!` already persists `:seon.turn/prompt-text` itself in step 3. Composer-side double-write would be redundant. | Spec says the composer attaches the prompt-text key but **§5.3 ¶ after the code-block says** "The harness attaches `:seon.turn/prompt-text` to the open turn (§6.1)." — internally contradictory in v1.md. **Recommendation: drop the composer-side `:seon.turn/prompt-text` key from the spec**; keep the persistence in `run-turn!`. | No |
| 3 | Step 3 persists `:seon.turn/prompt-text` to the open turn | `agent.cljs:528–532` — matches | — | — | — |
| 4 | Step 4 ask LLM | `agent.cljs:534` — matches | — | — | — |
| 5 | Step 5 records assistant message as a turn component (`:seon.turn/messages [{…}]`) (v1.md:1078–1083) | `agent.cljs:542–550` — matches the component shape (no `:seon.message/agent` on assistant messages — correct per agent.cljs:142–148 docstring) | — | — | — |
| 6 | Step 6 `eval-batch!` writes evals as `:seon.turn/evals` components carrying `:seon.eval/id` (v1.md:220–222, `:seon.eval/turn` is **not** on the spec'd eval entity schema — v1.md:230–242 has no turn ref) | `agent.cljs:557–558` calls `seval/eval-batch! compile-state parsed (home-ns id) id turn-idx`. eval-batch writes `:seon.eval/agent [:seon.agent/id …]` + `:seon.eval/turn <int>` (V0 schema, `eval.cljs:569–584`). **Evals never land on `:seon.turn/evals`.** | **Load-bearing.** v1.md §9 acceptance criterion 11 ("one pull on a `:seon.turn/id` returns prompt-text + messages + evals") is broken — the turn pull won't see evals because they're not components of it. | **Removing** behavior: `:seon.eval/agent` + `:seon.eval/turn :int` must go; eval-batch must append eval-id refs to `:seon.turn/evals` instead. | **Yes** — `eval-batch!` is Platform-owned (Phase 2.5 item 4-adjacent + the in-flight Patch 1/2). Surface as PLATFORM-FLAG below. |
| 7 | Step 7 closes the turn with `:seon.turn/status :done` + flips agent to `:idle` | `agent.cljs:561–564` — matches | — | — | — |
| 8 | Returns the closed `:seon.turn` entity via full pull | `agent.cljs:566–568` pulls with `[*]` only — no nested component pull, so `:seon.turn/messages` and `:seon.turn/evals` come back as eids, not inlined entities | Cosmetic — caller can re-pull if they want detail | Additive (replace `'[*]` with the nested pull from v1.md:262–272) | No |
| 9 | `run-turn!` should establish `(seon.db/with-tx-context {…} (fn [] …))` once at the top, so all six transacts get the bundle (v1.md:621–636, "The per-form work runs inside a with-tx-context scope") | Not wrapped. agent.cljs:448–453 explicit comment says "intentionally absent — Platform's Phase 3a `*tx-context*` auto-merge will tag every tx." | None until Phase 2.5 item 4 (auto-merge of `current-tx-context` into transact tx-meta) lands — which is **already shipped** (`db.cljs:655–670, 713`). The dynvar plumbing works; `run-turn!` just isn't entering a scope. | Additive — wrap the let-body in `db/with-tx-context` | No — same as #1, the comment is stale |
| 10 | `run-turn!`'s input map shape `{:seon.agent/id … :seon.harness/llm-fn …}` per v1.md:1041 | Code uses `:seon.agent/llm-fn` (agent.cljs:496). Different keyword namespace. | Minor — keyword is internal | Pick one and update the spec or the code | No |
| 11 | `run-agentic-loop!` stop policies: zero forms; turns-since-user ≥ cap; user message arrival (v1.md §6.2) | `agent.cljs:600–622` implements: zero forms ✓; turns-since-user cap ✓; **error result** (extra policy); **user-message arrival is delegated to the kick handler's `:running` guard** (agent.cljs:308). | None — the user-message arrival policy semantics are equivalent (the handler skips when `:running`, picks up next time). Error stop is additive. | — | — |

### What I'd land in this patch (no Platform dependency):

- Wrap `run-turn!`'s body in `(db/with-tx-context {:seon.db/origin :system :seon.db/agent-id id :seon.db/session-id session-id :seon.db/turn-id turn-id} (fn [] …))`. Delete the agent.cljs:448–453 "intentionally absent" comment. Auto-merge into tx-meta already works (`db.cljs:655–670`).
- Replace the agent.cljs:566 `'[*]` pull with the nested pattern from v1.md:262–272.
- Drop the composer's contract requirement to attach `:seon.turn/prompt-text` (or rewrite v1.md §5.3 to remove the dual write).

## Q2 — Six-section composer coherence

### Helper inventory

| Helper called by spec | Exists? | Where |
|---|---|---|
| `seon.agent/system-section` | ✘ | not in `agent.cljs` |
| `seon.agent/messages-section` | ✘ | — |
| `seon.agent/current-ns-section` | ✘ | — |
| `seon.agent/warnings-section` | ✘ | — |
| `seon.agent/recent-evals-section` | ✘ | — |
| `seon.agent/prompt-section` | ✘ | — |
| `seon.agent/assemble-ctx` (composer entry) | ✘ | — |
| `seon.agent/ctx` (composer alias from v1.md:1063) | ✘ | — |
| `seon.agent/home-ns` | ✓ | `agent.cljs:238–242` |
| `seon.agent/messages` | ✘ | — |
| `seon.agent/evals` | ✘ | — |
| `seon.agent/current-turn` | ✘ | — |
| `seon.agent/current-session` | ✓ | `agent.cljs:456–461` |
| `seon.agent/root-pull` | ✘ | — |
| `seon.agent/reset-ctx!` | ✘ | — |
| `seon.agent/update-ctx!` | ✘ | — |
| `seon.agent/truncate-edn` | ✘ | — |
| `seon.agent/register-warning!` / `registered-warning-predicates` | ✘ | no registry mechanism |
| `format-message-row` / `format-eval-row` | ✘ | — |
| `host-timezone` | ✘ | — |
| `seon.agent/new-id!` | ✓ | `agent.cljs:100–104` |
| `seon.agent/id` (dynvar accessor — v1.md:766) | ✘ | only the `default-id` def exists |
| `seon.agent/*id*` dynvar | ✘ | not declared (agent.cljs:632 of v1 spec, also referenced in §3) |

**The composer is greenfield.** Only `home-ns`, `current-session`,
and `new-id!` are reusable. Existing V0 helpers in
`seon.render.default` (`recent-messages`, `recent-evals`,
`recent-errors`, `pretty-ai`) are independent of `seon.agent` —
they live under `seon.render.default` and can either be moved to
`seon.agent` or called from the section fns there. Spec says
"All section fns live in seon.agent" (v1.md:751) — moving is cleanest.

### Input-map shape uniformity

v1.md §5.3 ¶4: "Each section fn receives the section entity as
`:seon.agent/ctx-entity` in the input map, so it can read any
agent-attached config keys (e.g. `:seon.agent/n` for sizing)."

v1.md §5.2 spec fns have **inconsistent destructuring**:

- `system-section` (v1.md:756): `{:seon.db/keys [db] :seon.agent/keys [id]}` — no `ctx-entity`. ✘
- `messages-section` (v1.md:784): `{:seon.db/keys [db] :seon.agent/keys [id ctx-entity]}` — ✓
- `current-ns-section` (v1.md:796): `{:seon.db/keys [db] :seon.agent/keys [id]}` — no `ctx-entity`. ✘
- `warnings-section` (v1.md:818): `[input]` — opaque, doesn't matter
- `recent-evals-section` (v1.md:845): `{:seon.agent/keys [id ctx-entity]}` — no `db`, has `ctx-entity` ✓
- `prompt-section` (v1.md:856): `{:seon.db/keys [db] :seon.agent/keys [id]}` — no `ctx-entity` ✘

**Recommendation:** standardize all six on the §5.3 contract
`{:seon.db/db, :seon.agent/id, :seon.agent/ctx-entity}` with
`ctx-entity` allowed to be nil for sections that don't read entity
config. Section authors don't have to destructure it, but the
composer always passes it.

### Override priority

v1.md §1 "Map args + smart defaults": call-time arg > entity-data >
spec-default. The pattern in the spec (v1.md:785): `(or
(:seon.agent/n ctx-entity) 50)` only honors entity-data > default.
There's no call-time arg threading — section fns are called by the
composer with a fixed map. v1.md:786 then has
`(seon.agent/messages {:seon.agent/n n :seon.agent/id id})` which IS
the call-time arg path — but only for the agent calling
`seon.agent/messages` directly, not for the composer's call-time
override of a section. **Net effect:** in v1, call-time override of
a section's display defaults means *the agent calls
`seon.agent/messages` interactively with a custom `:n`*, NOT *the
composer overrides the section fn's call*. Spec is consistent; just
two different surfaces.

### Warning-predicate registry mechanism

v1.md:819 calls `(registered-warning-predicates)`, v1.md:832 mentions
`(seon.agent/register-warning! 'my.ns/pred)`. **Neither is specified
beyond mention.** Open design question:

- An atom in `seon.agent` (e.g. `(defonce !warning-preds (atom #{}))`)? Simple, but doesn't survive restart — every fresh boot has only the substrate-registered `slow-eval-warning`.
- A DB entity (`:seon.warning/pred` carrying `:seon.warning/sym`)? Survives restart, follows the "everything in DB" pattern, but is heavier.
- A reverse-search of `:seon.fn/sym` entities tagged with some marker on `:seon.fn/source`? Requires the program graph (v1 ships it) plus a tag mechanism not designed.

**Recommendation:** atom + a one-line "registered preds reset on
restart; re-add yours in your home ns" line in the system-section
cheat-sheet. Cheapest. The warnings tile is a usability nudge, not a
durable contract — the predicate function IS persisted (it's in
`:seon.fn/source` after the agent defined it), the registration list
is volatile. v2 graduates to a DB entity when the warning surface
matures.

### `assemble-ctx` write-to-DB contract

v1.md §5.3 code block (line 904): `assemble-ctx` returns `{:seon.render/text text :seon.turn/prompt-text text}`. v1.md:908: "The harness attaches `:seon.turn/prompt-text` to the open turn (§6.1)." v1.md §6.1 step 3 (line 1062–1071): `run-turn!` reads `prompt-text` from the composer return and writes it. **Both sides write the same value to the same key — the composer-side key is unread.** Recommendation: drop `:seon.turn/prompt-text` from `assemble-ctx`'s return map; the composer returns `{:seon.render/text …}` (matching `:seon.render/ai-response` schema at `src/seon/render.cljs:68`); `run-turn!` owns persistence. Update v1.md §5.3 to match.

### §5.4 retract-then-add — validator collision

The example transacts `[:db/retractEntity old-id]` + an entity map in one tx-data. Per `src/seon/db.cljs:593–602`, `validate-values!` only validates entity-map datums and explicitly skips vector tuples — so this **will** flow through. The cascade behavior depends on `:db/isComponent`; `:seon.agent/ctx` IS declared component (agent.cljs:208), so retracting old ctx entities cascades correctly. ✓ — no probe-driven blocker.

### §5.5 `(reset-ctx!)` retract-pattern

The example uses `[:db/retract [:seon.agent/id agent-id] :seon.agent/ctx]` — a 3-tuple retract that retracts the whole component-many ref. Datahike's cardinality-many retract semantics in datahike-cljs need verification (probe pending — non-blocking for the audit, raise if it bites). Recommend the safer pattern from §5.4: `[:db/retractEntity <each-old-ctx-eid>]` + add new defaults. One extra pull but no surprises.

## Q3 — Risk 3 probe (intra-tx lookup-ref resolution)

### REPL transcript (live pod, `:memory` conn, 2026-05-23 11:51 UTC)

**Probe 1 — via `seon.db/transact!`**:

```clojure
(ns user-r3 (:require [seon.db :as db]))
(defn ^:async go []
  (let [r (await (db/transact!
                   {:seon.db/tx-data
                    [{:seon.ns/name :seon.r3probe
                      :seon.ns/source "(ns seon.r3probe)"}
                     {:seon.fn/sym "seon.r3probe/foo"
                      :seon.fn/ns [:seon.ns/name :seon.r3probe]
                      :seon.fn/source "(defn foo [] :ok)"}]}))
        pulled (db/pull {:seon.db/pull-pattern '[:seon.fn/sym {:seon.fn/ns [*]}]
                         :seon.db/ref [:seon.fn/sym "seon.r3probe/foo"]})]
    …))

```

Result (from `logs/pod.log`):

```
ERROR [seon.client] unhandled promise rejection #error
  {:message "Malli validation failed for :seon.fn/ns child:
             expected map or :seon.db/ref, got :seon.ns/name",
   :data {:seon.db/error :seon.db/invalid-ref-child,
          :seon.db/attr :seon.fn/ns,
          :seon.db/actual-value :seon.ns/name,
          :seon.db/malli-explanation
            {:schema :seon.db/ref,
             :value :seon.ns/name,
             :errors ({:path [0 0], :schema :int, :value :seon.ns/name}
                      {:path [0 1], :schema :string, :value :seon.ns/name}
                      {:path [0 2],
                       :schema [:tuple :keyword :seon.db/lookup-ref-value],
                       :value :seon.ns/name,
                       :type :malli.core/invalid-type})}}}
  at seon$db$validate_ref_child_BANG_ (src/seon/db.cljs:543)
  at seon$db$validate_entity_values_BANG_ (src/seon/db.cljs:578)

```

**Probe 2 — bypass validator, call `datahike.api/transact!` directly**:

```clojure
(ns user-r3c (:require [datahike.api :as d] [seon.client :as client]))
(defn ^:async go []
  (let [conn @client/!agent-conn
        _ (await (d/transact! conn
                   {:tx-data
                    [{:seon.ns/name :seon.r3probe3
                      :seon.ns/source "(ns seon.r3probe3)"}
                     {:seon.fn/sym "seon.r3probe3/foo"
                      :seon.fn/ns [:seon.ns/name :seon.r3probe3]
                      :seon.fn/source "(defn foo [] :ok)"}]}))
        pulled (d/pull @conn
                 '[:seon.fn/sym {:seon.fn/ns [:seon.ns/name :seon.ns/source]}]
                 [:seon.fn/sym "seon.r3probe3/foo"])]
    …))

```

Result (from `logs/pod.log`):

```
:trace datahike.writer :datahike/commit-time data: {:duration-ms 27}
R3-OK: {:seon.fn/sym "seon.r3probe3/foo",
        :seon.fn/ns {:seon.ns/name :seon.r3probe3,
                     :seon.ns/source "(ns seon.r3probe3)"}}

```

### Verdict

- **Datahike intra-tx lookup-ref resolution works.** Risk 3's
  hypothesis ("the lookup-ref `:seon.fn/ns [:seon.ns/name
  :seon.r3probe]` resolves against the ns entity transacted in the
  SAME tx") is confirmed against the version we're running. The pull
  inlines the ns entity via `:seon.fn/ns`. Bootstrap.edn as a single
  tx is technically viable.
- **But seon.db's validator gate currently blocks it.** The first
  probe died inside `seon.db/validate-entity-values!` before reaching
  datahike. The validator's "Many-card ref with mixed shorthand"
  branch at `db.cljs:576–579` matches **any** `sequential?` value on
  a ref-typed attr — including a literal lookup-ref tuple
  `[:seon.ns/name :seon.r3probe]`, which is a single ref, not a
  vector-of-refs. The validator then iterates the tuple's elements
  and tries to validate each as a `:seon.db/ref`, failing on the
  bare keyword `:seon.ns/name`.

### Downstream implications

- Bootstrap.edn as a single `transact!` is the spec's design (v1.md §7.3, line 1213) — the emitter doesn't need to split by entity kind.
- But until the validator is fixed, **every** transact that puts a lookup-ref on a single-card ref attr crashes. Detect-and-tee will produce these every time the agent writes `(defn …)`. This is a current blocker for the entire program-graph mechanic. **PLATFORM-FLAG.**

## Q4 — `default-id "seon"` → strict 12-char

### Current usages (grep across `src/`)

| File:line | Usage |
|---|---|
| `src/seon/agent.cljs:361–366` | `(def default-id "seon")`, `(def default-ns (home-ns default-id))` → `'seon.agent.seon` |
| `src/seon/agent.cljs:384` | `(create! {:seon.agent/id default-id})` in `boot!` |
| `src/seon/client.cljs:367` | `(seval/setup-agent-ns! compile-state agent/default-ns agent/default-id)` |
| `src/seon/web/serve.cljs:160` | Comment: "in the query string (defaults to "seon")." |
| `src/seon/web/serve.cljs:224, 273` | `(or (query-param req "agent") "seon")` — two HTTP handlers default to the literal `"seon"` |

`grep` for `'seon.agent.seon` only hits the docstrings/comments and `agent.cljs:366` itself. Nothing `(require 'seon.agent.seon)` directly — the home ns is built dynamically by `setup-agent-ns!`. Safe to flip.

### Options

| Option | Storage | Discovery | Tradeoff |
|---|---|---|---|
| **A. Generate at first boot, store on a singleton entity** (e.g. `{:seon.runtime/key :default-agent :seon.runtime/agent-id "<12char>"}`) | DB | `(:seon.runtime/agent-id (d/pull db '[*] [:seon.runtime/key :default-agent]))` | Survives restart; tied to that DB. Fresh DB → fresh id (acceptable). Adds 2 schema regs. |
| **B. Environment variable** `SEON_DEFAULT_AGENT_ID` | env | Read at boot; generate + warn if absent | Cross-DB stable; user/operator concern. Cluttered for one config value. |
| **C. Project-local file** `tmp/seon-default-agent` | filesystem | Read at boot; generate + write on first run | Mirrors `tmp/seon-port`. Works regardless of DB reset. |
| **D. Tighten only the schema; let `default-id` stay loose** | n/a | Use a guard branch: skip strict validation when id == `"seon"` | Keeps the spec gap explicit. Worst of both worlds. |

**Recommendation: A.** Closest to the project's "everything in DB"
ethos. Bootstrap-on-empty (v1.md §7.3) is the natural seam — when
the bootstrap phase runs, generate the default id and seed the
`:seon.runtime/key :default-agent` entity. Read it at `boot!` /
`start-agent!`. URL handlers (`serve.cljs:224, 273`) read the same
entity instead of hardcoded `"seon"`. One DB lookup per HTTP request
is cheap.

### V1-blocking?

**No.** v1.md §9 acceptance criteria 1–11 don't reference id length.
The "Default-agent transitional note" at v1.md:551–557 explicitly
documents this as a deferred refactor. Leave it loose for v1; tighten
in the bootstrap-emit ship.

## Q5 — Self-correction signal sufficiency

### What surfaces failed evals to the agent today

1. Eval records `:seon.eval/ok? false` with `:seon.eval/error <pr-str of error-map>` (`src/seon/eval.cljs:582–584`).
2. `seon.render.default/recent-evals-block` displays the error in the prompt (`src/seon/render/default.cljs:374–391`). The agent sees the failure inline in next-turn context.

### Gaps

- **Resume-phase replay failures.** v1.md §7.4 says replay records `:ok? false :replay? true`. Currently no resume phase exists. When it lands, those evals will be visible in `recent-evals-section` like any other failed eval, but without higher-severity surfacing they're easy to miss — the agent might not realize the prior session's defns failed to come back.
- **Live-value failures at call time.** A defn that succeeds at eval time but throws when called from a later turn produces an `:ok? false` eval entry for the **call** form, with the call's source as `:seon.eval/source`. The failing `:seon.fn/source` (the defn body) is unchanged — the agent has to correlate the call failure to the function definition manually.
- **Pattern detection across recent evals.** No predicate scans for "5 of last 10 evals failed in the same ns" or "two defns redefined → still failing." The slow-eval predicate at v1.md:834 is the only warning shipped in v1.
- **Severity rollup.** Failed evals just appear in the eval log mixed with successes. No "you have 3 unresolved errors in your home ns" header.

### Recommendation: ship a second predicate in v1

`recent-eval-errors`:

```clojure
(defn recent-eval-errors
  "Failed evals in the current session."
  [_input]
  (for [e (seon.agent/evals {:seon.agent/n 20})
        :when (false? (:seon.eval/ok? e))]
    {:seon.warning/severity :warn
     :seon.warning/text
     (str "failed eval " (:seon.eval/id e)
          " — " (subs (or (:seon.eval/error e) "") 0 120))}))

```

Costs essentially nothing — same query shape as `slow-eval-warning`,
runs at section-render time. The agent now sees a warnings-tile
section that says "3 of your last 20 evals failed" before scanning
the full eval log for them. Tightens the falsification loop.

Resume-phase replay failures are auto-covered by the `recent-eval-errors`
predicate because resume writes new `:seon.eval` entries with `:ok?
false :replay? true` per v1.md:1265–1269. The predicate filters by
`:ok? false` and surfaces them naturally. Add `(when
(:seon.db/replay? e) " [replay]")` to the text so the agent sees the
provenance.

## PLATFORM-FLAGs

### PLATFORM-FLAG 1 — `validate-entity-values!` false-positive on single-card ref lookup-refs

**What I'm trying to do:** Land `(detect-and-tee)` from v1.md §2.2 in
the per-form loop, and emit the `bootstrap.edn` substrate seed as a
single tx. Both rely on `[:seon.ns/name <kw>]` lookup-refs flowing
through `seon.db/transact!` as values of single-card ref attrs like
`:seon.fn/ns`.

**What blocks me:** `src/seon/db.cljs:576–579` —

```clojure
;; Many-card ref with mixed shorthand:
(and (ref-typed-attr-form? schema-form)
     (sequential? val))
(doseq [child val]
  (validate-ref-child! attr child))

```

`sequential?` matches a literal lookup-ref tuple `[:seon.ns/name
:seon.r3probe]` (it's a 2-element vector). The validator then
iterates the tuple and tries to validate each element as a
`:seon.db/ref`, failing on the bare keyword.

**What I think Platform needs to change:** the "many-card" branch
should fire only when the registered schema's resolved form is a
*container* of refs (`[:vector :seon.db/ref]` or `[:set :seon.db/ref]`).
A bare `:seon.db/ref` registration should validate the value as a
single ref — including the case where the value is the lookup-ref
tuple `[<ident-attr> <ident-value>]`. `:seon.db/ref` already accepts
that tuple per the registered schema (the `m/explain` output names
the failing alternative as `[:tuple :keyword :seon.db/lookup-ref-value]`).

Suggested guard:

```clojure
(cond
  ;; ... existing nested-map shorthand cases ...
  (and (ref-typed-attr-form? schema-form)
       (= :seon.db/ref (resolve-malli-form schema-form))  ; single-card
       (m/validate :seon.db/ref val))
  nil  ; already valid as a single ref (eid OR lookup-ref tuple)

  ;; ... continue to the many-card branch only when the registered
  ;; schema's HEAD is :vector/:set ...
)

```

**File:line refs:** `src/seon/db.cljs:551–602` (the validator),
`src/seon/db.cljs:503–525` (`ref-typed-attr-form?`),
`src/seon/agent.cljs:225` (`:seon.fn/ns` reg uses bare `:seon.db/ref`).

REPL evidence: probe transcript in Q3 above.

### PLATFORM-FLAG 2 — `eval-batch!` return shape doesn't carry success counts; assistant evals don't land on `:seon.turn/evals`

**What I'm trying to do:** Satisfy v1.md §9 acceptance criterion 11
("one pull on a `:seon.turn/id` returns prompt-text + messages +
evals"). Also satisfy `run-agentic-loop!`'s zero-forms stop policy
without re-querying the DB.

**What blocks me:** `eval-batch!` (`src/seon/eval.cljs:591–654`)
writes evals using the V0 schema: `:seon.eval/agent` (ref to
`:seon.agent`) + `:seon.eval/turn :int`. v1 says `:seon.eval`
entities are component-many on `:seon.turn/evals` (v1.md:222). The
turn entity has no way to surface "its" evals.

`eval-batch!` returns `@eids` (the ordered vector of eval-id
strings). `run-turn!` uses `(count eids)` for the cap policy
(agent.cljs:568) and `(count eids)` for the zero-forms policy
(agent.cljs:599 via `:seon.agent/eval-count`). The success-count
distinction isn't visible — a batch where every form failed still
returns 10 eids, and the loop thinks "10 forms = keep going" instead
of "10 failures = stop."

**What I think Platform needs to change:**

1. In `record-eval!` (`eval.cljs:564`), instead of writing `:seon.eval/agent` + `:seon.eval/turn`, append the new eval-id to `:seon.turn/evals` on the current turn entity. Requires passing `turn-id` (12-char string) into `eval-batch!` instead of (or in addition to) `turn-n` (int).
2. Return a richer shape — `{:seon.eval/ids […] :seon.eval/n-ok <int> :seon.eval/n-fail <int>}` — so `run-turn!`'s `:seon.agent/eval-count` derives from `n-ok` and the loop's stop policy sees the right thing.
3. Delete `:seon.eval/agent` and `:seon.eval/turn` schema regs in `agent.cljs:152, 154` once eval-batch is migrated.

**File:line refs:** `src/seon/eval.cljs:564–589` (record-eval),
`src/seon/eval.cljs:591–654` (eval-batch loop),
`src/seon/agent.cljs:151–159` (current eval-entity schema regs),
`src/seon/agent.cljs:191–192` (`:seon.turn/evals` component schema).

Coordinate with the in-flight Patch 1/2 work on `eval-batch!` —
Platform owns this change.

### PLATFORM-FLAG 3 — stale comment in `agent.cljs:448–453` claims `with-tx-context` plumbing is "intentionally absent"

**What I'm trying to do:** Wrap `run-turn!` in a `with-tx-context`
scope so every tx auto-merges the causality bundle, per v1.md §6.1.

**What blocks me:** Nothing — `seon.db/with-tx-context` /
`current-tx-context` are shipped (`src/seon/db.cljs:395–437`), and
`seon.db/transact!`'s auto-merge through `merge-tx-context-into-opts`
is also live (`db.cljs:655–670, 713`). The comment at
agent.cljs:448–453 saying "Phase 3a will tag every tx" is stale —
Phase 2.5 item 4 already shipped (per STATUS.md:55–73, Phase 2.6
landed 2026-05-23 and built on top of the tx-meta auto-merge).

**Recommendation:** MVP track removes the comment, wraps `run-turn!`
in `with-tx-context`. No Platform change needed — just flagging that
the documentation Platform left says "deferred" when the code is
live.

## Open questions back to Sean

1. **Composer return shape contradicts itself in v1.md §5.3.** Lines 904 and 908. Do you want `assemble-ctx` to return `{:seon.render/text … :seon.turn/prompt-text …}` (composer-side persistence hint) or just `{:seon.render/text …}` (matching `:seon.render/ai-response`)? My recommendation is the latter — `run-turn!` already owns the persistence (v1.md §6.1 step 3) and writing the same value twice is just a bug surface. Confirm before I rewrite the spec.

2. **Warning-predicate registry — atom or DB entity?** Atom is cheaper and matches the "registrations don't survive restart; the predicate fn DOES because it's in `:seon.fn/source`" model. DB entity is more uniform with everything else in v1. I lean atom for v1 (volatile registration, durable predicate), DB-entity in v2. Confirm.

3. **`recent-eval-errors` predicate in v1's warnings tile?** v1.md §5.2 says "ships only `slow-eval-warning`" but Q5 above argues the failed-eval surface is the *only* self-correction signal the agent has, and slow-eval is a perf nudge — not a correctness signal. Shipping both costs ~10 lines. Add to v1?

4. **Default-id refactor in v1 or defer to v2?** My recommendation in Q4 is option A (DB-stored 12-char id, generated at bootstrap). Adds ~30 lines (schema reg + bootstrap-phase generate + 3 callers updated). Doesn't block any v1 acceptance criterion. Your call whether to bundle now or defer.
