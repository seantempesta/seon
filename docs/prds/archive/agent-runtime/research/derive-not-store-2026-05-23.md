---
type: research
status: completed
tags: [research, agent]
---

# Derive-not-store survey + detect-and-tee wire pattern — 2026-05-23

Single research pass answering the orchestrator's seven questions
before the detect-and-tee implementation lands. All claims verified
against the live pod (`pid=42687`, `bin/seon status`: pod up,
cljs-watch up) on `feature/agent-runtime` @ `abc236c` + uncommitted
`src/seon/agent.cljs` work. REPL session: `default`.

## TL;DR

- **The stored `:seon.session/turns-since-user` is wrong RIGHT NOW.**
  Live derivation says 2, stored value says 4 on the live agent's
  session `0VKRcO4p2HZQ`. Two independent writers (kick handler
  resets to 0, `with-turn!` increments) racing across `setTimeout`
  - Promise chains. Delete the attr; derive at read time. Cost: 6ms
  vs 3.5ms for the stored read — within noise.
- **`:seon.agent/current-ns` is a dead attr.** Registered at
  `agent.cljs:233`, read in three section fns, NEVER WRITTEN by any
  code on disk. Today the agent's "current ns" lives entirely in
  the in-eval atom `(@seon.agent.seon/!current-ns)`, set by
  `seon.eval/update-current-ns!`. Delete the DB attr; replace with
  a derived helper that reads from the eval log.
- **`!current-ns` (the eval-time atom inside the agent's home ns)
  costs two extra `cljs.js/eval-str` round-trips per form** (one
  `read-current-ns`, one `update-current-ns!`) — `eval.cljs:684,702`.
  Both can go away: thread the current ns as a per-batch fold
  accumulator. The eval result's `:ns` key IS the truth; record it
  on the eval entity (`:seon.eval/ns`), derive across batches.
- **`:seon.eval/ns` is in the v1 spec (v1.md:236) but does not
  exist on disk** — not registered, never written, no datoms.
  Detect-and-tee must add it. Once added, the per-form fold knows
  the next form's ns AT WRITE TIME without re-reading anything.
- **Combined transact (eval entity + ns + fn entities) works in one
  shot.** REPL-verified: single `db/transact!` carrying a turn
  component child + tee entities, with intra-tx lookup-ref
  `:seon.fn/ns [:seon.ns/name :probe.foo]` resolving to a
  same-tx-created `:seon.ns` entity. Upsert by identity attr also
  works — re-tee of the same `:seon.fn/sym` replaces source, no
  duplication.
- **`!warning-predicates` (atom in `seon.agent`) is process-global
  shared state across agents.** When v3 multi-agent ships this
  becomes a leak. Migrate to a per-agent DB attr now —
  `:seon.agent/warning-predicates [:vector :symbol]`. No atom, no
  cross-agent contamination, free persistence, free history.
- **The eval-time atoms in eval.cljs that look stateful but ARE
  legit**: `!timeout-ms` (process-wide budget knob; per-call
  override via `:timeout-ms` opt), `als-instance` (Node primitive,
  not state), `timeout-sentinel` (identity-checked marker, not
  state), `init-version` (compile-state version stamp).
- **`!next-budget-ms` is the same hazard pattern as warning-handler
  globals** (Platform's A2 in `eval-batch-fragility-2026-05-23.md`).
  Lives between `(budget …)` call site and the next auto-await. In
  single-agent v1 it works; in multi-agent v2 it'll cross-contaminate.
  Bundle with Platform's ALS patch — out of MVP scope for this
  cleanup.
- **Detect-and-tee wire pattern: one transact per form, two entities
  merged.** The per-form loop's fold accumulator carries
  `current-ns`. After eval, the loop reads the result's `:ns` key
  (truth — emitted by `cljs.js/eval-str`). Then it constructs ONE
  tx-data vector with: (a) `{:seon.turn/id … :seon.turn/evals
  [<eval-entity, including :seon.eval/ns next-ns>]}` and (b) the
  tee entity (`:seon.ns` / `:seon.fn` / `:seon.schema` if matched).
  Fold accumulator's next value = the new ns. Spec says "merge into
  the same tx-data" — confirmed feasible. No second tx, no
  read-back-the-eval-entity round trip.
- **STATUS.md (b) rule needs a small reframe.** It says "read the
  post-eval ns from `:seon.eval/ns`, not from `!current-ns`." That
  remains correct for cross-batch derivation. But within a single
  batch, the per-form fold accumulator (carrying the `:ns` returned
  by the just-completed `eval-str`) IS the source of truth — it's
  what writes `:seon.eval/ns` and what informs the tee. No
  read-back-from-DB needed in-batch.
- **Schema cleanup is small**: 2 attrs from `seon.agent` schema regs
  to delete (`:seon.session/turns-since-user`,
  `:seon.agent/current-ns`); 1 atom to delete
  (`!warning-predicates`); 1 attr to add (`:seon.agent/warning-predicates`);
  1 attr to add (`:seon.eval/ns` — already in spec); 2 atoms in
  eval.cljs to delete (the `!current-ns` setup in
  `setup-agent-ns!` becomes unused; the helper fns
  `read-current-ns` + `update-current-ns!` go with it).

## Q1 — stored-but-derivable survey

Comprehensive walk of `agent.cljs`, `eval.cljs`, `db.cljs`,
`client.cljs`, `repl.cljs`. Atoms grouped by classification.

### Defonce atoms found

| # | File:line | Atom / state | Verdict | Notes |
|---|-----------|--------------|---------|-------|
| 1 | `agent.cljs:869` | `!warning-predicates` (atom of set of symbols) | **Move to DB attr** | Process-global. Cross-agent leak in multi-agent. See Q4. |
| 2 | `eval.cljs:60` | `!timeout-ms` (default 10000) | **Legit (process default)** | Knob; per-call override exists. Multi-agent: same pattern hazard as warning handlers, defer to Platform ALS patch. |
| 3 | `eval.cljs:70` | `!next-budget-ms` (one-shot override) | **Hazard, Platform fix pending** | Same as warning-handler globals (Platform's A2). Cross-fiber race in multi-agent. Don't touch in this MVP cleanup — Platform's ALS patch bundles it. |
| 4 | `eval.cljs:99` | `timeout-sentinel` | **Legit (sentinel object, not state)** | Identity marker; never mutated after defonce. |
| 5 | `eval.cljs:170` | `init-version` (gensym; `def` not `defonce`) | **Legit (version stamp)** | Rotates on hot-reload of eval.cljs, drives compile-state cache invalidation. |
| 6 | `db.cljs:391` | `als-instance` (Node `AsyncLocalStorage`) | **Legit (Node primitive)** | Not seon state — runtime fiber-local storage. |
| 7 | `client.cljs:68` | `!state` (Integrant-style) | Out of scope here | Owns conn+compile-state; legit. |
| 8 | `client.cljs:186` | `!agent-conn` (atom, nil-or-conn) | **Legit (cache for the singleton)** | Set once. |
| 9 | `client.cljs:187` | `!compile-state` (cache) | **Legit (boot cache)** | Pairs with version stamp. |
| 10 | `repl.cljs:76` | `!compile-state` (canonical) | **Legit (boot cache)** | KI-2/KI-5 fix already collapsed dual atoms to this. |
| 11 | `repl.cljs:83` | `!init-version` (paired with #10) | **Legit (version stamp)** | Already shipped, KI-2 fix. |
| 12 | `repl.cljs:85` | `!conn` (cache for the singleton) | **Legit (boot cache)** | Pairs with #10. |
| 13 | Agent home ns: `!current-ns` (atom of symbol, set up by `setup-agent-ns!`) | `eval.cljs:465` writes the source `(def !current-ns (atom '...))` into the agent's eval'd ns | **Derivable from eval log** | See Q2. Cost of round-trip: 2 extra `eval-str` calls per form. |
| 14 | Agent home ns: `!session-id` | `eval.cljs:464` | **Derivable but irrelevant — read-only mirror of agent-id** | Just sugar for `(session-id)`. Reads `agent-id`. Keep as user-facing sugar; not "state" in the dangerous sense. (Optional cleanup: turn into a plain `def` of the agent-id string; no reason to wrap in an atom.) |
| 15 | Agent home ns: `__seon_results_*` globalThis keys | `eval.cljs:432-442` | **Legit (per-tier-3 storage rule)** | Volatile live values; explicitly out of scope per `project_seon_three_tier_storage.md`. |

### DB attrs that are stored-but-derivable

| Attr | Current writers | Derivable from | Verdict |
|------|----------------|----------------|---------|
| `:seon.session/turns-since-user` | `with-turn!` increments, kick handler resets | Count of `:seon.turn` after latest `:user` message's `:at` for the agent | **Delete.** REPL evidence: stored=4, derived=2 on live session right now (Q3). Two writers race; derivation cost is 6ms. |
| `:seon.agent/current-ns` | NONE — registered at `agent.cljs:233`, never transacted anywhere | Latest `(ns …)`-source eval's `:seon.eval/ns` (or `home-ns`) | **Delete.** Dead attr already; agent.cljs reads three times, always falls through `or` to `(home-ns id)` because the read is always nil. |

### Stored-but-OK (genuinely state, can't be derived from log)

| Attr | Why keep |
|------|----------|
| `:seon.agent/state` (`:idle`/`:running`) | The agent's mutex; what the kick handler reads to skip enqueue-during-turn. Could be derived from "is there a `:seon.turn` with `:running` status on the latest session" but the kick handler runs in the tx listener BEFORE the open-turn tx is committed (it's listening on the user-msg tx) — so there's no in-DB representation of "I'm about to start a turn" until `with-turn!`'s open-tx lands. Keep as DB state; simpler. |
| `:seon.agent/turns-cap` | Configured cap; not derived. |
| `:seon.turn/status` | `:running` / `:done` / `:error` — terminal state of the turn lifecycle; `with-turn!` transitions it. Could derive from "did anything throw" but the close-tx is the natural place. |
| `:seon.ns/source`, `:seon.fn/source`, `:seon.schema/source` | Code-as-data. The tee writes these; resume replays them. Source IS the truth. |

## Q2 — Current-namespace model

### Status quo (3 places)

1. **`!current-ns` atom INSIDE the agent's home ns.** Created by
   `setup-agent-ns!` (`eval.cljs:465`); read by `read-current-ns`
   (`eval.cljs:511-517`) before each form's eval; written by
   `update-current-ns!` (`eval.cljs:519-526`) after each form's
   eval. **Cost: 2 extra `cljs.js/eval-str` round-trips per form
   to manipulate this atom.** Worse, both helpers swallow failures
   ("soft-fail"), so on any failure path the atom silently drifts
   from the recorded eval ns. Platform's A5 (validated in
   `resume-findings-2026-05-23.md`) recommends elimination —
   confirmed.
2. **`:seon.agent/current-ns` DB attr** registered at
   `agent.cljs:233`, read in `system-section`, `current-ns-section`,
   `prompt-section`. **Never written.** Dead.
3. **`:seon.eval/ns` per-eval attr.** Specced at `v1.md:236`. **Not
   registered, not written, no datoms exist.** The MVP's job to add
   it.

### Truth model

The eval log is the source of truth. `cljs.js/eval-str` returns the
ending ns of the form (the `:ns` key in its callback map) — this is
what `eval.cljs:354` captures and what `eval.cljs:702` writes back
into the atom today. **That value belongs on the eval entity as
`:seon.eval/ns`, full stop.** Then:

- **Within a batch**: the per-form loop is already a reduce over
  the parsed entries. The accumulator carries `current-ns`, which
  is "the ns the next form should eval in." Start value: agent's
  home-ns (or, on resume, derived from the log — see below). After
  each successful form, accumulator = result's `:ns`. After a
  failed form, accumulator unchanged. Pure fold, no atom.
- **Across batches** (e.g. resume after pod restart, or fresh
  `run-turn!` after a quiet period): query for the most-recent
  successful eval's `:seon.eval/ns` for the agent. If none exists,
  use `home-ns`.

### REPL-verified derivation query

Probe on the live agent (`agent-id "seon"`):

```clojure
(defn derive-current-ns [db agent-id]
  (let [evals (->> (d/q '[:find ?at ?source ?ns ?ok?
                          :in $ ?aid
                          :where
                          [?a :seon.agent/id ?aid]
                          [?a :seon.agent/sessions ?s]
                          [?s :seon.session/turns ?t]
                          [?t :seon.turn/evals ?e]
                          [?e :seon.eval/at ?at]
                          [?e :seon.eval/source ?source]
                          [?e :seon.eval/ok? ?ok?]
                          [(get-else $ ?e :seon.eval/ns :__none__) ?ns]]
                        db agent-id)
                   (sort-by first))]
    (some (fn [[_ _ ns ok?]] (when (and ok? (not= ns :__none__)) ns))
          (reverse evals))))

;; Live result (no :seon.eval/ns datoms yet — attr does not exist):
;; => {:n-evals 12, :latest-ns nil, :home-ns "seon.agent.seon"}
;; Cost of the full 12-eval query: 1.7ms.

```

12 evals scanned in 1.7ms. Adding `:seon.eval/ns` indexing will
make this a single bound-attr lookup (`(d/q '[:find ?ns :where
[?e :seon.eval/ns ?ns]]…)` with sort-by `:seon.eval/at` desc, take
1) — fractional ms.

### Edge cases

- **Zero evals**: `current-ns` returns nil → caller falls back to
  `(home-ns id)`. Today's three section fns already do
  `(or … (home-ns id))`, so behavior unchanged.
- **`(in-ns 'foo)`**: bootstrap CLJS doesn't support `in-ns`
  (`eval.cljs:34` docstring). Agents use `(ns foo)`. No special
  case.
- **`(ns foo)` mid-batch then `(defn bar [])`**: per-form fold
  accumulator picks up `:foo` from form 1's `:ns`; form 2's eval
  runs with `:ns 'foo`; tee writes `:seon.fn/sym "foo/bar"`. Works
  by construction.
- **Failed `(ns foo)`**: result `:ok? false`, accumulator
  unchanged. Form 2 runs in prior ns. Matches today's behavior
  (`eval.cljs:701`'s `(when (and (:ok result) (:ns raw-result))`
  guard).

### Agent-facing helper

```clojure
(defn current-ns
  "Latest ns the agent's evals have left it in. Derived from
   `:seon.eval/ns` on the most-recent successful eval; falls back
   to the agent's home-ns on a fresh agent.  Sync, sub-ms."
  ([] (current-ns {}))
  ([{:seon.agent/keys [id] :or {id default-id}}]
   (or (->> (d/q '[:find ?at ?ns
                   :in $ ?aid
                   :where
                   [?a :seon.agent/id ?aid]
                   [?a :seon.agent/sessions ?s]
                   [?s :seon.session/turns ?t]
                   [?t :seon.turn/evals ?e]
                   [?e :seon.eval/ok? true]
                   [?e :seon.eval/at ?at]
                   [?e :seon.eval/ns ?ns]]
                 @db/*conn* id)
            (sort-by first)
            last
            second)
       (home-ns id))))

```

### Recommendation

1. **Delete** `:seon.agent/current-ns` schema reg
   (`agent.cljs:233`) and the entry from
   `seon.client/agent-bootstrap-attrs` (`client.cljs:210`).
2. **Add** `:seon.eval/ns :keyword` schema reg in `seon.agent` (the
   eval schemas live with the agent ns; matches `v1.md:236`). Add
   to `agent-bootstrap-attrs`.
3. **Delete** `setup-agent-ns!`'s `!current-ns` def
   (`eval.cljs:465`); delete `read-current-ns`
   (`eval.cljs:511-517`) and `update-current-ns!`
   (`eval.cljs:519-526`).
4. **Rewrite** `eval-batch!` to carry `current-ns` as a `loop`/
   `reduce` accumulator. Start at `agent-ns-sym` (caller passes
   `home-ns`-as-default OR a resume-derived ns); after each form,
   accumulator = `(or (:ns raw-result) accumulator)`; write
   `:seon.eval/ns accumulator-after-this-form` onto the eval
   entity (so the entity records the ns the form LEFT in, per v1
   spec).
5. **Add** `seon.agent/current-ns` derived helper (above).
6. **Update** `system-section`, `current-ns-section`,
   `prompt-section` to call `(current-ns {:seon.agent/id id})`
   instead of reading the dead DB attr.

## Q3 — turns-since-user derivation

### REPL evidence on live agent

```clojure
(let [db @seon.db/*conn*
      sid "0VKRcO4p2HZQ"]
  {:stored (:seon.session/turns-since-user
             (d/pull db '[*] [:seon.session/id sid]))
   :derived (derive-tsu db sid)
   :n-turns 8
   :n-user-msgs 3
   :user-msg-times [#inst "2026-05-23T13:28:29.447"
                    #inst "2026-05-23T13:30:13.206"
                    #inst "2026-05-23T13:30:39.352"]
   :derive-ms 6.0
   :stored-read-ms 3.5})
;; => stored=4, derived=2.   STORED IS WRONG by 2.

```

The bug the orchestrator was chasing IS REAL and IS PERSISTED
on the live conn right now. Two writers (kick-handler reset to 0,
`with-turn!` inc) racing across `setTimeout` boundaries.

### Derivation query

```clojure
(defn turns-since-user
  "Count of :seon.turn entries on the agent's current session whose
   :seon.turn/at is strictly after the latest :user message's :at.
   When the agent has no user messages yet, every turn counts."
  ([] (turns-since-user {}))
  ([{:seon.agent/keys [id] :or {id default-id}}]
   (let [db   @db/*conn*
         session (current-session id)
         turns   (:seon.session/turns session)
         last-user-at
         (->> (d/q '[:find ?at
                     :in $ ?aid
                     :where
                     [?a :seon.agent/id ?aid]
                     [?m :seon.message/agent ?a]
                     [?m :seon.message/role :user]
                     [?m :seon.message/at ?at]]
                   db id)
              (map first)
              sort
              last)]
     (if (nil? last-user-at)
       (count turns)
       (count (filter #(pos? (compare (:seon.turn/at %) last-user-at))
                      turns))))))

```

Cost: 6ms on the live conn vs 3.5ms for stored. Both noise — sub-form-eval-cost by 2 orders of magnitude.

### Edge cases (all handled by the derivation)

- **No user messages yet**: every turn counts (degenerate but
  correct; the agent loop's cap policy still terminates).
- **Two user messages in one batch**: only the latest matters.
- **User message during a turn**: the kick handler still flips
  `:running` semantics (no state machine change needed); next
  iteration's derivation auto-includes the new user message in
  "latest-user-at" → resets the count, just as the kick handler's
  explicit reset does today, only correct.

### Recommendation

1. **Delete** `:seon.session/turns-since-user` schema reg
   (`agent.cljs:220`) and entry in `agent-bootstrap-attrs`
   (`client.cljs:227`).
2. **Delete** the `with-turn!` increment block
   (`agent.cljs:561-571`). The session shorthand inside the open-tx
   becomes just `{:seon.session/id … :seon.session/turns [{…}]}`.
3. **Delete** the kick handler's reset block
   (`agent.cljs:354-364`). The `let [reset-promise …]` wrap goes
   away; the handler becomes `(when-not (= :running state)
   (js/setTimeout (fn [] (run-agentic-loop! input)) 0))` directly.
4. **Replace** the read at `agent.cljs:693` with a call to
   `(turns-since-user {:seon.agent/id id})`.
5. **Add** `seon.agent/turns-since-user` derived helper (above).

Two bugs (race + persisted-wrong-state) both vanish.

## Q4 — `!warning-predicates` registry

### Today's shape

`agent.cljs:869` — `(defonce !warning-predicates (atom #{}))`.
- Mutated by `register-warning!` / `unregister-warning!`
  (`agent.cljs:871-885`).
- Read by `registered-warning-predicates` (`agent.cljs:887-891`)
  which is read by `warnings-section` (`agent.cljs:1023-1037`).
- Auto-populated on ns-load with the substrate defaults
  (`agent.cljs:933-934`).

### State or code?

Both — the atom IS state (lives across turns within a pod run),
but its CONTENTS are pure symbols that name functions. Across pod
restarts the atom resets to empty + re-runs `register-warning!`
calls during ns-load → substrate defaults restored automatically.
Agent-added predicates DON'T survive pod restart by themselves —
but the agent's `register-warning!` source IS captured as
`:seon.fn/source` (or for non-defn calls, `:seon.eval/source`)
on its eval log, and resume re-evals those, which re-fires the
`register-warning!` call, which repopulates the atom. So
durability is "code-as-data" + "re-eval-on-resume", same as the
agent's other code.

### Problem 1: cross-agent leak

Process-global. Multi-agent v2 (per CLAUDE.md "Multi-agent in one
pod: supported by architecture") means agent A's
`(register-warning! 'A.foo/pred)` lands in the same atom that
agent B reads from. B's `warnings-section` calls A's predicate.
Even if the predicate is harmless, A is leaking implementation
details into B's prompt. Worse: if A's predicate `nil`-checks
`(seon.agent/evals)`, that fn — when called from B's render scope
— pulls B's evals. Confused predicate, wrong warnings. Either A's
predicate sees B's data and gives nonsense, or B's user sees A's
predicate's nonsense. Either way: contamination.

### Problem 2: structural inconsistency

Every other piece of agent configuration is per-agent and lives
on the entity (`:seon.agent/turns-cap`, `:seon.agent/ctx`,
`:seon.render/ai`, `:seon.render/html`). The registry is the
only thing that doesn't follow this rule. The asymmetry isn't
load-bearing — there's no architectural reason it can't be a DB
attr.

### Recommendation: per-agent DB attr

```clojure
(schema/register! :seon.agent/warning-predicates [:vector :symbol])

```

```clojure
(defn ^:async register-warning!
  "Append `sym` to this agent's `:seon.agent/warning-predicates`.
   Idempotent — duplicates are dropped at read time."
  ([sym] (register-warning! {:seon.agent/id default-id} sym))
  ([{:seon.agent/keys [id]} sym]
   (let [current (or (:seon.agent/warning-predicates
                       (db/entity {:seon.db/ref [:seon.agent/id id]}))
                     [])]
     (when-not (some #{sym} current)
       (await (db/transact!
                {:seon.db/tx-data
                 [{:seon.agent/id id
                   :seon.agent/warning-predicates (conj current sym)}]}))))))

```

(`unregister-warning!` mirrors via `(remove #{sym} current)`.)

`registered-warning-predicates` becomes:

```clojure
(defn registered-warning-predicates
  [agent-id]
  (->> (or (:seon.agent/warning-predicates
             (db/entity {:seon.db/ref [:seon.agent/id agent-id]}))
           [])
       distinct
       (keep seval/lookup-value)))

```

`warnings-section` already receives `:seon.agent/id` in its
input map (`agent.cljs:1028`) — pass it through.

### Substrate defaults

Move the auto-registration from ns-load-time (`agent.cljs:933-934`)
to **`boot!` time** — `create!` initializes the agent entity with
the two defaults already in `:seon.agent/warning-predicates`. No
runtime side effects from ns loading; deterministic per-agent
default. On resume the attr is already on disk; nothing to repop.

### Cost of read on every render

`(db/entity …)` is O(1) entity lookup, then a vector deref. The
`:vector :symbol` payload is tiny (2 entries by default). Less
cost than the current atom deref + global cross-fiber visibility
hazard. Trivial.

### Bonus: agents own their warning slate

A nice corollary — the agent can re-shape its warning predicate
list from inside its own eval (just transact a new vector). No
hidden global to mutate; layout-editing-via-transact is the same
pattern as `update-ctx!`.

## Q5 — Other state hazards

Brief findings beyond Q1's table:

- **`!next-budget-ms`** (`eval.cljs:70`): Real hazard, exactly the
  same shape as Platform's A1 (warning handlers) and A2. The
  `(budget …)` call writes the atom; the next `maybe-await-value`
  reads-and-clears. Across fibers, agent A's `(budget 60000 …)`
  could be consumed by agent B's next form. **In single-agent v1
  this works.** Multi-agent fix: ALS-bucket per fiber, same patch
  bundle as warning-handler A1. **Don't touch in this MVP cleanup
  pass** — Platform owns it.

- **`!warning-predicates`**: same hazard class. Solved at root by
  Q4's move to DB attr (per-agent storage, no cross-fiber visibility).

- **`!session-id` in agent home ns** (`eval.cljs:464`): this is
  an atom holding a string that NEVER changes (agent's id, set
  once at `setup-agent-ns!`). It's dressed up as state but isn't.
  Cleanup opportunity (turn into plain `def`), but harmless. NOT
  Q1-classification "derivable" since the value IS the id, and
  the id is already a load-bearing constant. Cosmetic only.

- **No other defonce-atom-with-multiple-writers found** in
  `agent.cljs`, `eval.cljs`, `db.cljs`, `repl.cljs`, `client.cljs`.

## Q6 — Detect-and-tee wire pattern

### Question 1: merge into ONE tx, or two?

**One.** Verified live (`TEE3` probe — see appendix). Single
`db/transact!` carrying:

```clojure
[;; (a) eval entity attached as turn component
 {:seon.turn/id TURN-ID
  :seon.turn/evals
  [{:seon.eval/id      EVAL-ID
    :seon.eval/at      AT
    :seon.eval/ok?     true
    :seon.eval/ns      NEXT-NS         ;; <-- folded in from result :ns
    :seon.eval/source  SOURCE
    :seon.eval/duration-ms DUR
    ;; ... narration / result-edn / error
    }]}
 ;; (b) tee entity, only if extractor matched
 ;; for defn → :seon.fn  ;; for schema/register! → :seon.schema ;; for ns → :seon.ns
 {:seon.fn/sym    "alice.foo/bar"
  :seon.fn/ns     [:seon.ns/name :alice.foo]
  :seon.fn/source "(defn bar [...] ...)"}]

```

Worked end-to-end. Intra-tx lookup-ref `[:seon.ns/name :alice.foo]`
resolves correctly when there's a matching `{:seon.ns/name …}` in
the same tx-data (the upsert by identity attr happens at the
tx-prep stage; refs resolve in a second pass). Verified at
`TEE1`/`TEE2`/`TEE3` probes below.

### Question 2: location of the tee step

Inside `eval-batch!`'s per-form body, **AFTER** the form returns
(so we know `current-ns`, the next ns, the success state, the
result-edn or error), **BEFORE** the call to a renamed
`record-eval!` — except now `record-eval!` takes BOTH the eval
map AND the optional tee map and produces ONE tx. Sketch:

```clojure
(defn ^:async record-eval-and-tee!
  "Persist one eval entity (as turn component) and optionally a
   program-graph entity (`:seon.fn` / `:seon.schema` / `:seon.ns`)
   in the SAME transact. Idempotent via identity attrs."
  [{:keys [eval-id turn-id at narration source result duration-ms ns tee]}]
  (let [eval-map (cond-> {:seon.eval/id          eval-id
                          :seon.eval/at          at
                          :seon.eval/duration-ms (or duration-ms 0)
                          :seon.eval/narration   (or narration "")
                          :seon.eval/source      source
                          :seon.eval/ok?         (boolean (:ok result))
                          :seon.eval/ns          ns}
                   (:ok result)        (assoc :seon.eval/result-edn …)
                   (not (:ok result))  (assoc :seon.eval/error …))
        tx-data  (cond-> [{:seon.turn/id turn-id
                           :seon.turn/evals [eval-map]}]
                   tee (conj tee))]
    (await (db/transact! {:seon.db/tx-data tx-data}))))

```

### Question 3: where do the extractors run?

In `eval-batch!`'s per-form body, after the form's result is known
and after we've computed `next-ns` from `(:ns raw-result)`. The
extractors run on `source` (the form text) PLUS `current-ns` (the
ns the form was about to be eval'd in, NOT the next-ns — because
`(defn foo …)` defines `foo` in the ns it was eval'd in, not in
whatever ns the form might `(ns …)`-switch to before defining).
The `(ns …)` form is the special case: tee uses the form's own ns
target (the keyword the extractor returns), not the current-ns,
because the new ns entity records the SWITCHED-TO ns.

Sketch:

```clojure
(let [defn-name    (code/extract-defn-name source)        ;; nil or string
      schema-key   (code/extract-schema-key source current-ns)   ;; nil or kw
      ns-name      (code/extract-ns-name source)            ;; nil or kw
      tee (cond
            (and defn-name (:ok result))
            {:seon.fn/sym    (str (name current-ns) "/" defn-name)
             :seon.fn/ns     [:seon.ns/name (keyword (name current-ns))]
             :seon.fn/source source}

            (and schema-key (:ok result))
            {:seon.schema/key    schema-key
             :seon.schema/ns     [:seon.ns/name (keyword (namespace schema-key))]
             :seon.schema/source source}

            (and ns-name (:ok result))
            {:seon.ns/name   ns-name
             :seon.ns/source source}

            :else nil)]
  ...)

```

Notes:
- Tee only on `(:ok result)`. A failed eval doesn't define anything;
  no program-graph write.
- `:seon.fn/ns` and `:seon.schema/ns` need a `:seon.ns` entity to
  point at. The `{:seon.ns/name kw}` shorthand UPSERTS — if it
  already exists, no-op; if not, creates a minimal one. **Adding
  this minimal-ns-upsert to every defn/schema tee is fine** because
  the identity attr deduplicates. But: it'd leave many ns entities
  with no `:seon.ns/source` if the agent typed `(defn foo …)`
  without ever typing `(ns alice.foo …)` first. That's correct
  per the spec — the agent's home-ns may have no source the agent
  typed; the tee captures only what the agent typed. The renderer
  (current-ns-section) tolerates missing `:seon.ns/source` (just
  shows fns/schemas without a header).

  **Recommend**: emit the minimal upsert too:

  ```clojure
  (when tee
    (let [ns-target (or (and ns-name tee)
                        ;; for fn/schema, infer ns from the lookup-ref
                        (when-let [ref (or (:seon.fn/ns tee)
                                           (:seon.schema/ns tee))]
                          {:seon.ns/name (second ref)}))]
      [eval-tx tee ns-target]))    ;; ;; 3-entry tx-data when ns wasn't already explicit

  ```

  Datahike will upsert-by-identity if `:seon.ns/name` already
  exists, no-op the second one.

### Question 4: STATUS.md (b) rule — re-read from entity or use fold accumulator?

STATUS.md (b) says read `:seon.eval/ns` from the just-written
eval entity. The motivation was "the entity is the source of
truth" — but that framing assumes a SEPARATE write-then-read flow,
which the one-tx approach makes moot.

**The fold accumulator IS the source of truth in-batch.** It holds
`(:ns raw-result)` from `cljs.js/eval-str` — same value that
gets written to `:seon.eval/ns`. There's no daylight between them.
Reading from the just-written entity would just be an extra DB
round-trip to get back what we have in scope.

**Reframe of (b) for the implementation**: "The form's ending ns
flows from `cljs.js/eval-str`'s `:ns` to the in-batch fold
accumulator to the `:seon.eval/ns` attr to (cross-batch)
`seon.agent/current-ns` derivation. The atom `!current-ns` is
deleted; the cross-batch derivation reads `:seon.eval/ns` on the
latest successful eval."

Platform's STATUS.md sense (b) is preserved: the eval entity IS
the source of truth — for queries from outside the batch (resume,
section fns, agent helpers). Within the batch, the fold
accumulator is the entity's value before it's persisted.

### Question 5: idempotency

Verified via `TEE2` probe (see appendix). Re-evaling
`(defn bar [] 100)` after `(defn bar [] 42)` produces ONE
`:seon.fn` entity (identity on `:seon.fn/sym`); `:seon.fn/source`
field replaced; datahike history retains the prior value.
`(d/q '[:find ?e :where [?e :seon.fn/sym "probe.foo/bar"]] db)`
returns count 1.

### Question 6: validation

The current validator (`db.cljs:554-602`) treats `:seon.fn/ns
[:seon.ns/name :foo]` as a lookup tuple correctly (Item 1 in
`STATUS.md` recent ships — `615a120`). REPL-verified: the
3-entity tx (eval + ns + fn) passes validation cleanly. No
PLATFORM-FLAG needed for the combined tx shape.

### Wire pattern — final sketch

```clojure
;; Inside eval-batch!'s per-form body (already wrapped in with-tx-context):

(let [eval-id    (new-eval-id)
      tx-context {:seon.db/agent-id agent-id
                  :seon.db/eval-id  eval-id
                  :seon.db/origin   :agent}
      ;; current-ns is the LOOP/REDUCE accumulator threaded through:
      current-ns current-ns-accumulator]
  (await
    (db/with-tx-context tx-context
      (fn ^:async run-one-entry! []
        (cond
          (read-failure? entry)
          (do (await (record-eval-and-tee! {... :ns current-ns :tee nil}))
              [:fail current-ns])     ;; accumulator unchanged

          :else
          (let [{:keys [narration source]} entry
                start-ms   (.now js/Date)
                at         (js/Date.)
                raw-result (await (eval compile-state source
                                        {:ns current-ns :analyze-deps? false}))
                result     (compute-result raw-result)
                duration   (- (.now js/Date) start-ms)
                next-ns    (or (and (:ok result) (:ns raw-result))
                               current-ns)
                tee        (when (:ok result)
                             (compute-tee source current-ns))]
            (when (:ok result)
              (stash-result-raw! eval-id (:value result)))
            (await (record-eval-and-tee!
                     {:eval-id eval-id :turn-id turn-id :at at
                      :narration narration :source source
                      :result result :duration-ms duration
                      :ns next-ns
                      :tee tee}))
            [(if (:ok result) :ok :fail) next-ns]))))))

```

The `doseq` over `parsed` becomes a `reduce` accumulating
`{:current-ns … :n-ok … :n-fail … :eids …}`. Initial
`:current-ns` = `agent-ns-sym` on fresh batch; on resume, callers
pass the derived current-ns.

## Q7 — implementation plan

Order matters to keep the pod working during the cutover.

1. **[Schema add]** Register `:seon.eval/ns :keyword` in
   `seon.agent` (next to other `:seon.eval/*` regs at
   `agent.cljs:183-194`). Add to `agent-bootstrap-attrs`
   (`client.cljs:206-282`). Restart pod (`bin/seon restart pod`)
   to pick up the new schema; verify with
   `(boolean (:seon.eval/ns (d/schema @seon.db/*conn*)))`.

2. **[Schema add]** Register `:seon.agent/warning-predicates
   [:vector :symbol]` in `seon.agent`. Add to
   `agent-bootstrap-attrs`. Same pod restart works for both.

3. **[Wire detect-and-tee]** Rewrite `eval-batch!`
   (`eval.cljs:603-722`) as a `reduce` over `parsed` carrying
   `{:current-ns :n-ok :n-fail :eids}`. Add `record-eval-and-tee!`
   (or extend `record-eval!`'s arg map) to take an optional
   `:tee` map and produce the merged tx. Call
   `code/extract-defn-name`, `extract-ns-name`,
   `extract-schema-key` against `source` + `current-ns` to compute
   `:tee` (only when `(:ok result)`). Delete
   `read-current-ns` and `update-current-ns!`. Delete the
   `(def !current-ns …)` line from `setup-agent-ns!`'s
   `setup-src` string.

4. **[Delete dead DB attr]** Remove `:seon.agent/current-ns`
   schema reg (`agent.cljs:233`). Remove the entry from
   `agent-bootstrap-attrs` (`client.cljs:210`). Update
   `system-section`, `current-ns-section`, `prompt-section` to
   call `(current-ns {:seon.agent/id id})` instead of reading
   the dead attr.

5. **[Add derived helper]** Add `seon.agent/current-ns` per Q2's
   sketch. Sub-ms query over the eval log.

6. **[Delete stored counter]** Remove `:seon.session/turns-since-user`
   schema reg (`agent.cljs:220`) + entry from
   `agent-bootstrap-attrs` (`client.cljs:227`). Delete the
   increment block in `with-turn!` (`agent.cljs:561-571`) and the
   reset block in `user-message-handler` (`agent.cljs:354-364` —
   collapse `let [reset-promise …]` into the direct
   `js/setTimeout` call).

7. **[Add derived helper]** Add `seon.agent/turns-since-user` per
   Q3's sketch.

8. **[Wire the cap policy]** In `run-agentic-loop!`
   (`agent.cljs:689-719`), replace
   `(:seon.session/turns-since-user session)` (line 693) with
   `(turns-since-user {:seon.agent/id id})`. Drop the now-unused
   `session` binding (line 692).

9. **[Migrate warning registry to DB attr]** Replace the
   `defonce !warning-predicates` atom and its register/unregister
   fns (`agent.cljs:869-891`) with the DB-attr-backed versions
   per Q4's sketch. Move the substrate-default registration from
   ns-load-time (`agent.cljs:933-934`) to `boot!`/`create!`:
   `create!`'s entity tx-data carries
   `:seon.agent/warning-predicates ['seon.agent/slow-eval-warning
   'seon.agent/recent-eval-errors]`. Update `warnings-section`
   (`agent.cljs:1023-1037`) to pass `agent-id` to
   `registered-warning-predicates`.

10. **[Run the suite]** `bin/seon tail pod` while exercising
    `(seon.agent/chat agent-id "...")` and confirm:
    - `:seon.eval/ns` populates on each eval entity.
    - `(seon.agent/current-ns)` returns the live ns.
    - `(seon.agent/turns-since-user)` returns the right count;
      the cap policy still terminates at `turns-cap`.
    - `warnings-section` renders the default predicates' output.
    - Re-eval of an existing `(defn foo …)` produces one
      `:seon.fn` entity, source replaced.

Item count: 10. No PLATFORM coordination needed for any item —
all live on the MVP track.

## PLATFORM-FLAGs

None for this cleanup. Items already known and tracked:

- **PLATFORM-FLAG A1/A2/A5** (warning-handler global, budget-ms
  global, atomic-current-ns elimination) — bundled in Platform's
  ALS patch per `eval-batch-fragility-2026-05-23.md`. This
  research's Q2 recommendation is the MVP-side execution of A5;
  Platform's ALS patch and our `!current-ns` deletion are
  independent — both can land in parallel since they target
  different code paths.

## Open questions back to Sean

Two minor calls worth pinging on:

1. **Substrate-default warning predicates: storage location.**
   Q4 recommends storing as DB attr on the agent. The two
   substrate defaults (`'seon.agent/slow-eval-warning`,
   `'seon.agent/recent-eval-errors`) become part of `create!`'s
   initial tx. Alternative: keep ns-load auto-registration, but
   key it on a per-agent atom (still process-shared by ns, just
   gated). Recommending the cleaner DB-attr path. Confirm before
   landing.

2. **`:seon.eval/ns` on read-failure entries.** When the form
   never eval'd (parse failure), there's no `:ns` from the
   compiler. Use the current-ns accumulator (the ns it WOULD
   have run in) — this preserves "the agent's ns at the time of
   the failed read." Alternative: omit the attr on read-failure
   entries. Optional-attr semantics work either way. Recommending
   "always populate with current-ns" so the derivation never has
   to special-case `nil`. Confirm before landing.

## Appendix — REPL probes (live pod, default session)

### Probe TEE1 — intra-tx lookup-ref resolution

```clojure
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.ns/name :probe.foo
     :seon.ns/source "(ns probe.foo)"}
    {:seon.fn/sym    "probe.foo/bar"
     :seon.fn/ns     [:seon.ns/name :probe.foo]
     :seon.fn/source "(defn bar [] 42)"}]})
;; pulled => {:seon.fn/sym "probe.foo/bar"
;;            :seon.fn/source "(defn bar [] 42)"
;;            :seon.fn/ns {:seon.ns/name :probe.foo
;;                          :seon.ns/source "(ns probe.foo)"}}
;; tx-ok? true

```

### Probe TEE2 — upsert idempotency

```clojure
;; re-tee with updated source
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.ns/name :probe.foo
     :seon.ns/source "(ns probe.foo) ; updated v2"}
    {:seon.fn/sym    "probe.foo/bar"
     :seon.fn/ns     [:seon.ns/name :probe.foo]
     :seon.fn/source "(defn bar [] 100)"}]})
;; n-fn-entities 1   n-ns-entities 1
;; :seon.fn/source replaced; old retained in history.

```

### Probe TEE3 — combined eval entity + tee in ONE tx

```clojure
(seon.db/transact!
  {:seon.db/tx-data
   [{:seon.turn/id "<existing-turn>"
     :seon.turn/evals
     [{:seon.eval/id "TEEPROBE0001"
       :seon.eval/at (js/Date.)
       :seon.eval/duration-ms 1
       :seon.eval/source "(defn baz [] :baz)"
       :seon.eval/ok? true
       :seon.eval/result-edn "#'probe.foo/baz"}]}
    {:seon.ns/name :probe.foo
     :seon.ns/source "(ns probe.foo)"}
    {:seon.fn/sym    "probe.foo/baz"
     :seon.fn/ns     [:seon.ns/name :probe.foo]
     :seon.fn/source "(defn baz [] :baz)"}]})
;; tx OK; eval entity, ns entity, fn entity all queryable post-tx.

```

### Probe TSU — derive vs stored on live agent

```clojure
{:stored  4    ;; (:seon.session/turns-since-user (d/pull …))
 :derived 2    ;; count of turns after latest :user msg
 :n-turns 8
 :n-user-msgs 3
 :derive-ms 6.0
 :stored-read-ms 3.5}
;; Two independent writers race; the stored value drifted to +2.

```

### Probe DERIVE-NS — current-ns query cost (no :seon.eval/ns datoms yet)

```clojure
{:n-ok-evals 7 :query-ms 1.7}
;; 12-eval scan, no index on :seon.eval/ns (attr not in schema yet).
;; Once the attr is registered datahike indexes it; lookup becomes O(1).

```

## Cross-references

- `src/seon/agent.cljs:220` — `:seon.session/turns-since-user` (delete)
- `src/seon/agent.cljs:233` — `:seon.agent/current-ns` (delete; dead read-only)
- `src/seon/agent.cljs:354-364` — kick handler reset block (delete)
- `src/seon/agent.cljs:561-571` — `with-turn!` TSU increment block (delete)
- `src/seon/agent.cljs:693` — `run-agentic-loop!` reads stored TSU (replace with derived helper)
- `src/seon/agent.cljs:869` — `!warning-predicates` atom (replace with DB attr)
- `src/seon/eval.cljs:464-465` — `setup-agent-ns!` writes `!current-ns` atom string (delete that line)
- `src/seon/eval.cljs:511-526` — `read-current-ns` / `update-current-ns!` (delete)
- `src/seon/eval.cljs:603-722` — `eval-batch!` rewrite (reduce-accumulating-current-ns + tee)
- `src/seon/code.cljc:204-273` — extractors (already shipped, no changes)
- `src/seon/client.cljs:210, 227` — `agent-bootstrap-attrs` entries to delete + 2 to add
- `docs/prds/agent-runtime/v1.md:236` — `:seon.eval/ns` spec entry (add to code)
- `docs/prds/agent-runtime/STATUS.md` §"(b)" — reframe note above
- `docs/prds/agent-runtime/research/eval-batch-fragility-2026-05-23.md` §A5 — confirms `!current-ns` elimination is correct
- `docs/prds/agent-runtime/research/mvp-spec-coherence-2026-05-23.md` Q3 — intra-tx lookup-refs verified
