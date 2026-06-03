---
type: research
status: active
tags: [research, schema, agent]
---

# Schema, state, and identity audit — CLJS pod (2026-05-23)

## 1. TL;DR

The Seon CLJS pod is on the cusp of a clean, repeatable schema architecture
— most of the load-bearing pieces are already there. The in-flight Platform
refactor solved 80% of the "shape duplication" problem (`:seon.db/id` canonical
- `[:and {:seon.db/identity true} :seon.db/id]` reference pattern), but the
agent-identity change papered over the actual question rather than answering
it. The right shape for v1: **agent identity lives in the DB; there is no
`default-id`; every "the current agent" call-site is rewritten to either (a)
take an explicit id, (b) accept "the most-recent `:seon.agent` entity" as a
DB query, or (c) get its agent-id from a render-time bound context map.**
The `defonce default-id (db/new-id!)` change should be reverted — it
relocates the smell rather than removes it.

**The five highest-leverage cleanups, ordered by impact:**

1. **Delete `default-id` / `default-ns`.** Replace the 7 `:or {id default-id}`
   destructures in inspector fns with an explicit `current-agent-id` accessor
   that queries the DB (`(d/q '[:find ?id . :where [_ :seon.agent/id ?id]] @*conn*)`
   for v0's "exactly one agent" assumption; the same accessor becomes the
   v1 hook for multi-agent). Replace the two `(or (query-param req "agent") "seon")`
   web handlers with the same accessor. (§2, §5, §8.)

2. **`:any` is still in the schema layer in 6 spots that fight the rest of
   the codebase's "no `:any`" rule.** `seon.db/{tx-data,opts,conn,query,db,…}`,
   `seon.fs/mtime`, `seon.log/data`, `seon.render/db`. Three are genuinely
   opaque (datahike conn, db value, mtime as js/Date); three are accident
   (`::tx-data [:vector :any]` could be `[:vector [:or :map :vector]]`;
   `::query :any` could be a tagged query schema; `::opts :map` could be
   typed). (§3, §6.)

3. **`:seon.db/handler-input` carries five `:any`-typed fields that ARE
   typeable** (db value, db-before, tx-report). They're opaque to validation
   today, which means a handler that destructures the wrong shape crashes
   inside a tx-listener — exactly where errors are hardest to attribute.
   Type them via `:fn` predicates or a marker schema. (§3, §6.)

4. **The detect-and-tee / `:default/fn` opportunity.** The codebase already
   does manual "fill in derived values at write time" (e.g. `:seon.eval/at`,
   `:seon.eval/duration-ms`, `:seon.message/at`). Malli's
   `mt/default-value-transformer` + `:default/fn` properties on the schemas
   would let `db/transact!` decode tx-data and stamp defaults automatically.
   The user's `project_namespace_bootstrap.md` memory already calls this
   out as the system's "decode IS dispatch" pattern. Today only inlined in
   eval-batch and chat handler. (§6.)

5. **Process-state inventory is small and clean** — 11 defonces across
   pod CLJS, of which 3 are legitimately opaque (`als-instance`,
   `timeout-sentinel`, `id-letters`), 5 are legitimate caches with
   invalidation discipline (`!compile-state`, `!init-version`, `!agent-conn`,
   `!server`, `!last-rendered`), 2 are config knobs with override paths
   (`!timeout-ms`, `!next-budget-ms`), and 1 — `default-id` — is the smell.
   `!sse-connections` is the only legitimately stateful registry that
   isn't a cache or a knob; it could move to the DB as `:seon.web/conn`
   entities, with retract on disconnect, and gain free history of who
   was watching — interesting but probably not worth it for v1. (§4.)

The architecture isn't broken. The architecture is at the moment where one
honest pattern (`default-id` → DB accessor) makes the rest of the
inconsistencies legible.

---

## 2. The agent-id question — verdict

**The smell:** Sean correctly identified that `(defonce default-id (db/new-id!))`
is the same architectural mistake as the prior hardcoded `"seon"`. Both
formats are "the agent identity, baked into the language layer instead of
read from the system of record." `defonce` survives hot-reload of
`seon.agent` and survives within one pod boot — but does NOT survive a pod
restart (it mints a fresh id on every `node out/client/main.js`), and does
NOT survive multiple agents in one pod. It is process-global mutable state
masquerading as a constant.

**The agent-id is legitimately special** in one narrow sense: at boot,
before any agent entity exists in the DB, the pod has to mint an id and
write the entity. That's a one-time write, not a persistent process
identity. After that write the DB is the truth.

### Verdict

Revert the `defonce default-id (db/new-id!)` change. The replacement model:

- **`seon.agent/create!`** (line 352-359) already takes `:seon.agent/id`
  as input and is idempotent. Have `seon.client/start-agent!` mint the id
  exactly once (`let [id (db/new-id!)]`), pass it to `create!`, and pass
  it to `setup-agent-ns!` + `boot!`. No process-global needed.
- **`seon.agent/current-agent-id`** (new) — reads from the DB. For v0's
  one-agent assumption: `(:seon.agent/id (first-agent-by-most-recent-tx db))`.
  This is the single accessor that replaces every `:or {id default-id}`.
- **`seon.agent/home-ns`** stays as-is — `(home-ns "<id>")` is a pure
  function; that's fine.
- **`seon.agent/default-ns`** deleted; one call-site (`start-agent!`'s
  `setup-agent-ns!` call) inlines `(home-ns id)`.

### Why this is repeatable

The same pattern applies to every other place where "the current X"
might want to be a process-global: it isn't. It's a DB query. The shape
is **always**:

```clojure
;; WRONG — bakes "the current X" into the code
(defonce current-x (db/new-id!))
(defn doit [{:keys [x] :or {x current-x}}] …)

;; RIGHT — current X is a query
(defn current-x [] (db/query …))
(defn doit [{:keys [x] :or {x (current-x)}}] …)

```

The `:or` default becomes a function call, not an atom deref. The
function reads the DB. v0's "exactly one agent" simplification lives
inside `current-agent-id`, not in the language. When v1 multi-agent
ships, `current-agent-id` becomes "explicit `:seon.agent/id` required;
no default"; every call-site fails fast with a clear error rather than
silently routing to the wrong agent.

### Concrete migration

| File:line | Old | New |
|---|---|---|
| `src/seon/agent.cljs:369-377` | `(defonce default-id (db/new-id!))` + `default-ns` | Delete both |
| `src/seon/agent.cljs:750,766,777,787,801,819,845` | `:or {id default-id}` (7 sites in inspector fns) | `:or {id (current-agent-id)}` |
| `src/seon/agent.cljs:393-396` | `boot!` does `(create! {:seon.agent/id default-id})` then derives `agent-ns` | `boot!` takes `{:seon.agent/id id}` as input — caller mints |
| `src/seon/client.cljs:533` | `:agent-id agent/default-id` | `:agent-id id` (id minted at top of `start-agent!`) |
| `src/seon/client.cljs:542-543` | `agent/default-ns` + `agent/default-id` | `(agent/home-ns id)` + `id` |
| `src/seon/web/serve.cljs:224,273` | `(or (query-param req "agent") "seon")` | `(or (query-param req "agent") (agent/current-agent-id))` |

`/chat?agent=<id>` URLs that bookmark "seon" break, but they already broke
the moment `default-id` flipped to `(db/new-id!)`. No `/chat` URL outside
the substrate's own docstrings hardcodes "seon".

---

## 3. Schema layer audit

### 3.1 Inventory — all `(schema/register! …)` sites in the pod CLJS lane

129 total registrations across the pod (CLJS + .cljc). Distribution:

| File | Count | Concerns |
|---|---|---|
| `src/seon/db.cljs` | 22 | 5 are `:any`-typed (tx-data, opts, conn, query-request slots) |
| `src/seon/agent.cljs` | 30 | Five `[:and {:seon.db/identity true} :seon.db/id]` follow the new pattern; the other 25 are well-typed |
| `src/seon/render.cljs` | 7 | `:seon.db/db :any` — opaque-by-design but flagged |
| `src/seon/fs.cljs` | 30 | One `:seon.fs/mtime :any` — js/Date sidestep |
| `src/seon/log.cljs` | 8 | `:seon.log/data :any` — genuinely polymorphic payload |
| `src/seon/ai/deepseek.cljs` | 11 | Clean |
| `src/seon/test/runner.cljs` | 10 | Clean |
| `src/seon/schema.cljc` | 6 | Canonical shapes (`:inst`, `:seon.flow/dynamic`, `:seon.db/{namespace,lookup-ref-value,ref,id}`) |

### 3.2 Shape-duplication candidates (after `:seon.db/id` landed)

The Platform refactor turned 9 duplicated `[:string {:min 14 :max 14}]`
registrations into one canonical `:seon.db/id` + 5 `[:and …]` wraps + 4
bare references. That's the precedent. Other candidates:

**3.2.1 Identity-attr properties pattern** — five identity attrs in
`seon.agent` now use `[:and {:seon.db/identity true} :seon.db/id]`. Three
identity attrs DON'T (and shouldn't, because their value-shape isn't an
id-string):

- `:seon.ns/name [:keyword {:seon.db/identity true}]` — keyword-typed
- `:seon.fn/sym [:string {:seon.db/identity true}]` — fn-sym is a string
  pair `"ns/name"`, not the id-shape
- `:seon.schema/key [:keyword {:seon.db/identity true}]`
- `:seon.test/sym [:string {:seon.db/identity true}]`

These are fine as-is. The bridge reads `{:seon.db/identity true}` from
the outer-form properties whether it's wrapping `:and` or wrapping `:string`
directly. No cleanup needed; the asymmetry reflects real type variation.

**3.2.2 Component-many ref-vector pattern** — six attrs share the exact
shape `[:vector {:seon.db/component true} :seon.db/ref]`:
`:seon.session/turns`, `:seon.turn/messages`, `:seon.turn/evals`,
`:seon.agent/sessions`, `:seon.agent/ctx`. **Candidate for canonicalization:**

```clojure
;; In seon.schema:
(schema/register! :seon.db/component-many
                  [:vector {:seon.db/component true} :seon.db/ref])

;; In seon.agent:
(schema/register! :seon.session/turns :seon.db/component-many)

```

**Verdict:** marginal. The pattern is short enough to inline; the
"is this a `:db/isComponent` vector" intent is more visible inlined than
hidden behind an alias. **Skip** unless we add a 7th site.

**3.2.3 Tx-meta scalar references** — `:seon.db/{agent-id,session-id,turn-id,eval-id}`
all register as bare `:seon.db/id` references. Clean precedent for any
future "the same shape as the id" use cases.

**3.2.4 Map-in/map-out request/response wrapping** — `seon.db.cljs` defines
6 request/response schemas (`::transact-request`, `::query-request`, etc.)
that each carry `:seon.db/conn {:optional true} ::conn`. The `::conn :any`
indirection is a single point of fixup but it IS shared. Fine.

### 3.3 The bridge — `malli->datahike-attr`

The bridge is in good shape. It handles:

- `:string`, `:int`, `:keyword`, `:boolean`, `:inst`, `:uuid`, `:symbol` —
  direct map to `:db.type/*`
- `:enum` (keyword-valued only) — `:db.type/keyword`
- `:and` — recurses on first child; **outer-form props read for marker
  detection** (this is the key insight that makes `[:and {:seon.db/identity
  true} :seon.db/id]` Just Work without bridge changes — verified in the
  prior research)
- `:or` (homogeneous types only) — recurses on first alt
- `:vector` / `:set` / `:sequential` → `:db.cardinality/many` + recurse on
  child
- Keyword-indirection via the registry — walks until it hits a built-in or
  a non-form value
- `:seon.db/ref` — special-cased to `:db.type/ref` instead of following its
  `[:or :int :string [:tuple :keyword :seon.db/lookup-ref-value]]`
  registration

**Marker props honored:** `{:seon.db/identity true}` → `:db/unique`;
`{:seon.db/component true}` → `:db/isComponent`. No others.

**Capabilities the bridge does NOT use that the Malli registry has:**

- **`:db/index`** — datahike supports this for performance. Not exposed
  via marker. v1 doesn't need it; flag for v2 if query perf matters.
- **`:db/doc`** — datahike persists docstrings on schema entities;
  agents could read them via `(d/pull db [:db/doc] :seon.agent/id)`.
  Currently dropped. **Easy win**: have the bridge read `{:malli/doc "…"}`
  (or just `:description` per Malli convention) and emit `:db/doc`. The
  agent's "what shape is this?" introspection gets free docstrings.
- **`:db/noHistory`** — would let us mark high-churn attrs (heartbeats,
  cache state) as not-persisted-to-history. v1 doesn't have any
  `:keep-history? true` attrs that we'd want to exclude — but if/when
  we add `:seon.agent/state` churn worry, this is the lever.
- **`:db/tupleAttrs`** — composite-key indexing. Not needed for v1.

### 3.4 `:any` audit (Sean's recurring memory: "No `:any` in Malli — code smell")

Eight active `:any` registrations in pod CLJS:

| Site | Use | Verdict |
|---|---|---|
| `seon.db/tx-data [:vector :any]` | Catch-all for tx-data shapes | **Typeable** as `[:vector [:or :map :vector :keyword]]` or a tagged union |
| `seon.db/opts :map` | Datahike's opts pass-through | Borderline — could be tightened to known opts |
| `seon.db/conn :any` | Datahike conn (opaque IAtom) | **Legit-opaque** (JS object) |
| `seon.db/query :any` | Datalog query | **Typeable** as `[:or :vector :map]` (datalog supports both list and map syntax) |
| `seon.db/handler-input ::db/{tx-report,db,db-before} :any` (3) | Tx-listener input | **Legit-opaque** but should be marker schemas |
| `seon.render/db :any` | Datahike db value | **Legit-opaque** (Java/JS object) — keep |
| `seon.fs/mtime :any` | js/Date sidestep | **Typeable** as `:inst` (the comment says "varies across CLJS reader registries" — the bridge accepts `:inst` for js/Date elsewhere) |
| `seon.log/data :any` | Polymorphic payload | **Legit** — but should be carved out into `:seon.log/payload-summary :string` (already done; `data` registered but not in `agent-bootstrap-attrs`) |
| `seon.flow/dynamic` (in schema.cljc) | Wire protocol dynamic field | **Legit** — has `:type-properties :gen/schema` for generative testing |

### 3.5 Compatibility shim survey

The CLJS lane's schemas have:

- `:seon.agent/id` accepts `[:and {…} :seon.db/id]` — was loose ("v0
  hardcoded `default-id = 'seon'` violates min/max"). The `:and`-wrap
  refactor tightened this; the in-flight Platform refactor's
  `(defonce default-id (db/new-id!))` was the corresponding tightening,
  hence the smell.
- `seon.eval` shadows `clojure.core/eval` — deliberate.

No other compatibility shims worth flagging.

---

## 4. Special-state audit

Comprehensive walk of every `defonce` and `def` holding mutable state in
the CLJS pod.

| # | File:line | Name | Holds | Verdict | Rationale |
|---|---|---|---|---|---|
| 1 | `schema.cljc:38` | `*schemas` | Atom: keyword → Malli schema form | **Keep** | Global Malli registry. Only one in the pod. |
| 2 | `schema.cljc:42,50,59,74,79,92,112` | `_registry-init`, `_inst-type`, `_dynamic-type`, `_db-namespace-type`, `_lookup-ref-value-type`, `_ref-type`, `_id-type` | defonces of swap! return values | **Keep** | Idiomatic "run-once side-effect at ns load." |
| 3 | `db.cljs:340` | `id-letters` | Constant string | **Keep** (const, not state) |
| 4 | `db.cljs:388` | `tx-meta-attrs` | Set of keywords | **Keep** (const) |
| 5 | `db.cljs:401` | `*conn*` | Dynamic var, set by `start-agent!` | **Keep — but consider replacing with ALS scope** | `set!` at root; not fiber-local. Same hazard as eval-batch's warning-handler global (research/eval-batch-fragility). Fine for V0 single-agent; multi-agent v1 wants ALS-style scoping just like `with-tx-context` already does. |
| 6 | `db.cljs:443` | `als-instance` | Node `AsyncLocalStorage` | **Keep** (opaque JS primitive; the fiber-local backbone) |
| 7 | `eval.cljs:60` | `!timeout-ms` | Atom: int | **Keep (process knob)** | Override via `set-timeout-ms!` or `budget`. Multi-agent: same hazard pattern; deferred. |
| 8 | `eval.cljs:70` | `!next-budget-ms` | Atom: one-shot int override | **Move to ALS bucket (deferred)** | Cross-fiber race in multi-agent; works in V0. Bundle with the ALS warning-handler patch. |
| 9 | `eval.cljs:99` | `timeout-sentinel` | JS object marker | **Keep** (identity-checked sentinel) |
| 10 | `eval.cljs:170` | `init-version` | Gensym (def, not defonce — rotates on reload) | **Keep** (hot-reload version stamp) |
| 11 | `eval.cljs:427` | `results-key-prefix` | Constant string | **Keep** (const) |
| 12 | `client.cljs:68` | `!state` | Atom: {boot-at, reload-count, heartbeat-id} | **Move to DB?** | Process metadata. `:seon.pod/{boot-at,reload-count}` is meaningful for the agent's awareness ("I rebooted 3 times since you last talked to me"). Heartbeat-id is a JS handle — has to stay in-memory. **Split**: heartbeat-id stays in atom; boot-at + reload-count move to a `:seon.pod` entity. Low priority but cleanly applies the framework. |
| 13 | `client.cljs:186` | `!agent-conn` | Atom: datahike conn | **Keep** | JS object (the conn). Idempotency cache for `start-agent!`. |
| 14 | `client.cljs:187` | `!compile-state` | Atom: cljs.js state | **Keep** | Opaque JS state; rebuilt on `init-version` mismatch. Duplicate of `seon.repl/!compile-state` — see #18 |
| 15 | `agent.cljs:121` | `default-turns-cap` | Constant int 20 | **Keep** (const, fallback for absent `:seon.agent/turns-cap`) |
| 16 | `agent.cljs:369-377` | `default-id`, `default-ns` | defonce id + derived ns | **DELETE** | The trigger smell. See §2. |
| 17 | `agent.cljs:858` | `slow-eval-threshold-ms` | Constant int 500 | **Move to DB** | Could be a `:seon.agent/slow-eval-threshold-ms` attr alongside `:seon.agent/turns-cap`. Pattern is identical. Marginal; not blocking. |
| 18 | `repl.cljs:76,83,85` | `!compile-state`, `!init-version`, `!conn` | Atoms (duplicate of client's!) | **De-duplicate** | `seon.repl/!compile-state` and `seon.client/!compile-state` are DIFFERENT atoms. `ensure-bootstrap!` lives in `seon.repl` and operates on `seon.repl/!compile-state`; `start-agent!` calls it but then doesn't `reset!` `seon.client/!compile-state`. So `client/!compile-state` is **dead code** — defined but never written. Quick win: delete `client/!compile-state` and `client/!agent-conn` references that are duplicated. (Note `start-agent!` line 507 reads `@!agent-conn` — that one IS live; the dup concern is only `!compile-state`.) |
| 19 | `web/serve.cljs:55` | `!server` | Atom: HTTP server | **Keep** (opaque JS object) |
| 20 | `web/serve.cljs:62` | `!sse-connections` | Atom: vector of `{id, res, opened-at}` | **Keep, marginal "could be DB" candidate** | Each conn IS a real entity. But the `res` field is an opaque Node ServerResponse — can't persist. Stays in-memory. Could move metadata (id, opened-at) to DB for history-of-watchers, with a parallel atom for the live res — but that splits state across two places. Skip. |
| 21 | `web/broadcast.cljs:46` | `!last-rendered` | Atom: agent-id → HTML string | **Keep (diff cache)** | Pure derived state; cleared on hot-reload. |
| 22 | `fs.cljs:183` | `!config` | Atom: {allowed-roots, read-only?} | **Move to DB** | Per CLAUDE.md, fs allowlist is THE security model for the substrate. Belongs in `:seon.fs/config` so the agent can introspect what it's allowed to do AND so config changes ride history. Today reset via `configure!`. Migration: `:seon.fs/allowed-roots [:vector :string]`, `:seon.fs/read-only? :boolean` on a singleton `:seon.fs/config` entity. |

### 4.1 Framework — when is in-memory state legitimate?

After walking 22 sites, the rule that emerges:

- **Opaque JS handle.** Datahike conn, http.Server, ServerResponse, Node
  ALS instance, cljs.js compile-state — these CAN'T be serialized;
  they're raw runtime resources. **Atoms are the right shape.**
- **Process-lifetime sentinel / identity marker.** `timeout-sentinel`,
  `init-version`. **Defonce/def constants are the right shape.**
- **Diff cache / memoization with clear invalidation rule.**
  `!last-rendered` (clear on hot-reload + recompute on next tx). **Atoms
  are the right shape**; the invalidation rule is the contract.
- **Config knob with override path.** `!timeout-ms`, `!next-budget-ms`.
  **Borderline.** v1-single-agent: atoms are fine; v1-multi-agent: must
  become ALS-scoped or DB-typed (per-agent overrides) — already flagged.
- **Idempotency cache** (cache-the-first-call). `!agent-conn`,
  `!compile-state`. **Atoms are right** — invalidated by `nil-then-restart`.
- **Anything else** — should be in the DB. Process metadata, allowlists,
  "the current X", agent state, config that the agent should see.

The smells are atoms that hold values that ARE persistent identity (`default-id`,
`!warning-predicates`), values that ARE config the agent could read
(`slow-eval-threshold-ms`, `!fs-config`), or values that ARE process facts
the agent should see (`boot-at`, `reload-count`).

The cleanup pattern: **every atom either (a) holds a JS-opaque value,
(b) is a sentinel/const, (c) is a cache with a documented invalidation
rule, or (d) gets migrated to a DB entity.** Anything else is moving in
the wrong direction.

---

## 5. Identity / fallback audit

### 5.1 `:or {id default-id}` destructures (7 sites, all in agent.cljs)

All in inspector / read-side fns; all replaced by `(current-agent-id)`
accessor under the §2 plan:

- `root-pull` (line 750), `messages` (766), `current-turn` (777),
  `evals` (787), `current-ns` (801), `turns-since-user` (819),
  `ctx-entities` (845)

Each is the "agent inspects itself from inside its own REPL" pattern.
The `(current-agent-id)` accessor preserves the "no arg = ask about
the obvious agent" UX while removing the process-global.

### 5.2 Hardcoded `"seon"` sentinels

- `seon.web/serve.cljs:224` `(or (query-param req "agent") "seon")` — `/clear`
- `seon.web/serve.cljs:273` same — `/chat`
- `seon.client.cljs:474-489` — stub-LLM source string contains
  `:seon.agent/id (session-id)` (the agent's home-ns helper) — that's
  fine; reads from the runtime atom, not from the substrate's `default-id`
- `seon.client.cljs:225-489` various comments referencing `"seon"` in
  the stub-LLM scaffolding — cosmetic
- `seon.agent.cljs:54` docstring `default-id "seon"` — stale, predates the
  in-flight refactor

### 5.3 Stub-LLM emits `(session-id)`

The stub-LLM transacts `:seon.agent/id (session-id)`. `session-id` is set
up by `setup-agent-ns!` (eval.cljs:467) as the agent's home-ns accessor
for `@!session-id`. Under the §2 plan, `setup-agent-ns!` still gets `agent-id`
as an arg — so this stays working unchanged. The atom `!session-id` in
the agent's home ns is a per-agent eval-time constant, not a process
global. **This is the legitimate "agent-id is special" case Sean alluded
to** — at eval time, the agent needs to know its own id, and an atom in
its home ns is the right shape because eval has no other context-passing
mechanism. Not a smell.

---

## 6. Malli capabilities the codebase doesn't use

Cross-referenced against `reference-code/malli/` source and the user's
`project_namespace_bootstrap.md` memory.

### 6.1 `:default/fn` + `mt/default-value-transformer`

The user's memory: *"`m/decode` + `mt/default-value-transformer` IS the
injection layer. `:default/fn` on schemas provides values when keys missing
— built into Malli, not custom code."*

The pod's CLJS schemas register **zero** `:default/fn` properties today.
The eval-batch and chat handlers manually stamp `:seon.eval/at (js/Date.)`,
`:seon.message/at (js/Date.)`, `:seon.eval/id (db/new-id!)`. That code
should be one call to `m/decode` in `db/transact!`.

Concrete win: register

```clojure
(schema/register! :seon.eval/at [:inst {:default/fn (fn [_ _] (js/Date.))}])
(schema/register! :seon.eval/id [:and {:seon.db/identity true
                                       :default/fn (fn [_ _] (db/new-id!))}
                                  :seon.db/id])

```

Then `db/transact!` decodes tx-data with the default-value-transformer
before validation. Every entity-map auto-fills `:seon.eval/at`,
`:seon.eval/id`, etc. The five call-sites (eval-batch, chat, with-turn!,
start-session!, ensure-session!) stop manually computing them.

**Caveat:** the transformer pass needs to run AFTER user-supplied keys
land (only fill absent keys). Malli's default transformer does that
correctly.

**Apply to:** `:seon.eval/{id,at,duration-ms,narration}`,
`:seon.message/{id,at}`, `:seon.turn/{id,at,status}`,
`:seon.session/{id,at}`. ~12 sites collapse.

### 6.2 Schema docstrings → `:db/doc`

Bridge gain (see §3.3). Register schemas with `{:description "…"}` or a
seon-specific `{:seon/doc "…"}`, have the bridge emit `:db/doc`. Agent
introspection grows.

### 6.3 Generative-testing surface

Memory: *"Generative tests for type boundaries — use Malli generators
- property-based tests at system boundaries."*

The pod has **zero** generative tests in CLJS. `seon.flow/dynamic` has a
`:type-properties {:gen/schema …}` registered but nothing in CLJS lane
uses it. The JVM side has more. Worth a separate task: generate sample
tx-data via `mg/generate` against the registered schemas, round-trip
through `db/transact!`, assert idempotency / pull-pattern equality.

### 6.4 Function instrumentation

Memory + CLAUDE.md: *"All public functions with `:malli/schema` metadata
are instrumented at runtime. Every call is validated."*

Spot-checked the pod: instrumentation is NOT installed in CLJS. The
JVM side uses `:seon.dev/instrumentation` Integrant component. The pod
has no equivalent. Public fns carry `:malli/schema` metadata but it's
documentation only.

This is a sizable gap. The pod could install instrumentation via
`malli.dev/start!` (mainline Malli) at boot. Once on, every `db/transact!`
/ `db/query` / `agent/chat` / `seval/eval` call validates inputs against
its registered request schema. Errors would surface at the boundary
instead of deep inside datahike.

Recommend: add `(malli.dev/start!)` to `seon.client/start-agent!`
behind a `SEON_INSTRUMENT` env var (off by default for perf; on by
default in dev). Most schemas are already shaped correctly for this.

### 6.5 `m/walk` for schema introspection

The pod has no `m/walk` calls. Useful for:

- Auto-generating UI from schemas
- Dumping the "what shapes does this agent know about" map
- Building the `current-ns-section` render (currently does a `db/pull`
  with reverse refs; `m/walk` over schemas registered under the current
  ns would let the renderer enumerate types)

Low priority.

### 6.6 `mr/composite-registry` for per-agent schemas

The pod uses one global mutable registry. Multi-agent v1 might want
per-agent registries (so agent A can register `:agent-a/foo` without
agent B seeing it). `mr/composite-registry` supports this. Deferred.

---

## 7. Datahike capabilities the codebase doesn't use

(Augments `research/datahike-capabilities-2026-05-22.md`. Re-cross-checked
against pod usage.)

### 7.1 Already-used

- `:tx-meta` + `:keep-history? true` — fully wired via `with-tx-context`
  - ALS + `merge-tx-context-into-opts` in `db/transact!`. Excellent.
- `:db/isComponent` — used on `:seon.agent/sessions`, `:seon.session/turns`,
  `:seon.turn/{messages,evals}`, `:seon.agent/ctx`. Cascade-retract works.
- `:db/unique :db.unique/identity` — used via `{:seon.db/identity true}`
  marker. Upsert behavior verified.
- Reverse-ref pull (`:seon.fn/_ns`, `:seon.schema/_ns`) — used in
  `current-ns-section` (agent.cljs:931).
- `d/listen!` — used for kick handler + broadcast.
- `d/history` query path — used by `replay-program-graph!` (against
  current-db, correctly).

### 7.2 Not-used but valuable

- **`:db.unique/value`** (vs `:db.unique/identity`). The codebase only
  uses `identity` (upsert-on-collision). For attrs where collision should
  THROW (e.g., test-ns sym), `value` is the right marker. Not blocking.
- **`d/as-of` / `d/since` for the replay path.** The current
  `replay-program-graph!` queries `@conn` for current sources. For
  resume-from-arbitrary-tx (v2 "rewind"), `d/as-of` + `:tx-meta` time
  filtering becomes the primitive.
- **`d/pull` with `*`-wildcard.** Used in `agent.cljs:621-624` for the
  turn snapshot. Could be used more broadly — `(d/pull @conn '[*]
  [:seon.agent/id id])` is the "show me everything I know about this
  entity" call.
- **`d/datoms` for streaming over an attribute index.** Not used. Could
  replace some of the `db/query`-based attribute scans in the warnings
  section.
- **Composite refs / lookup-refs everywhere.** Already used heavily.
- **`{:keep-history? false}` per-attribute (`:db/noHistory`).** Could
  flag `:seon.agent/state` (high-churn, the `:idle`/`:running` flip per
  turn) as no-history to save storage. Worth measuring before optimizing.

---

## 8. The repeatable cleanup checklist

Patterns + migration list, ordered for sequential landing. Each pattern
states the rule, the why, an example from THIS codebase, and the sites
that need migrating.

### P1 — "Current X" is a DB query, not a process global

**Rule:** Any "default X" that represents identity-or-state-in-the-system
must read from the DB at call time. `defonce` is appropriate for opaque
JS handles and constants; never for identity values.

**Why:** Identity values change across pod restarts, multi-agent boots,
and resume-from-history. A process-global locks the call sites to one
identity forever.

**Before/after:** §2.

**Sites to migrate (this PR):**

1. Delete `seon.agent/default-id` + `seon.agent/default-ns`
2. Add `seon.agent/current-agent-id` (DB query)
3. Update 7 `:or {id default-id}` destructures in `seon.agent.cljs`
4. Update `seon.client/start-agent!` to mint id locally and pass through
5. Update 2 web-handler defaults in `seon.web/serve.cljs`

### P2 — Shape duplication → canonical registration + reference

**Rule:** Any value-shape repeated across 3+ `register!` calls becomes
a canonical entry in `seon.schema` and is referenced bare (non-identity)
or `:and`-wrapped (with marker props).

**Why:** One source of truth. Changing the shape changes every call site
on reload. The bridge already supports this for free.

**Example:** the in-flight `:seon.db/id` refactor.

**Open candidates:** none above the threshold today. `:seon.db/component-many`
is at 5 sites (one below). Reassess if a 7th lands.

### P3 — Defaults live on schemas, not in `:or` destructures

**Rule:** Auto-fill values (timestamps, generated ids, status flags)
register with `:default/fn` properties. The transact boundary decodes
tx-data via Malli's default-value-transformer before validation.

**Why:** Today the codebase manually computes `:seon.eval/at (js/Date.)`
at every call site. Forgetting it produces validation errors deep in the
write path. Schema-as-source-of-truth means the writer can't forget.

**Example (proposed):** §6.1.

**Sites to migrate:**

1. Add `:default/fn` to `:seon.eval/{id,at,duration-ms,narration}`,
   `:seon.message/{id,at}`, `:seon.turn/{id,at,status}`,
   `:seon.session/{id,at}`
2. Add `m/decode` call before `validate-values!` in `db/transact!`
3. Delete manual `(js/Date.)` / `(db/new-id!)` stamps at: eval-batch
   (eval.cljs:546), chat (agent.cljs:414), start-session! (483),
   with-turn! (524), ask-and-eval! (568)

### P4 — `:any` is a code smell to eliminate

**Rule:** `:any` is only legitimate for genuinely opaque JS objects
(datahike conn, db value). Everywhere else, type it — even if loosely
(`[:or :map :vector]` is better than `:any`).

**Why:** `:any` defeats validation, instrumentation, and generative
testing. The errors land far from the cause.

**Sites to fix:**

1. `seon.db/tx-data [:vector :any]` → `[:vector [:or :map :vector]]`
2. `seon.db/opts :map` → defined opts schema
3. `seon.db/query :any` → `[:or :vector :map]`
4. `seon.db/handler-input` — 3 `:any` fields → `:fn` predicates or
   marker schemas
5. `seon.fs/mtime :any` → `:inst`
6. Keep: `seon.db/conn`, `seon.render/db` (legit opaque)

### P5 — Process-state defaults to DB

**Rule:** Process metadata that the agent can usefully see (boot-at,
reload-count, capability config) lives in the DB. The agent reads it
via the same `db/pull` API it uses for everything else.

**Why:** Three-tier storage rule. The DB IS the projection layer; if
the agent should see it, it lives there.

**Sites to migrate:**

1. `seon.client/!state` split — heartbeat-id stays in-memory; boot-at +
   reload-count move to `:seon.pod` entity
2. `seon.fs/!config` → `:seon.fs/config` entity
3. `seon.agent/slow-eval-threshold-ms` → `:seon.agent/slow-eval-threshold-ms`
   attr (mirrors `:seon.agent/turns-cap` pattern)

### P6 — Function instrumentation

**Rule:** Public fns with `:malli/schema` metadata are instrumented at
boot. The pod's CLJS lane should match the JVM's `seon.dev/instrumentation`
component.

**Sites:**

1. Add `(malli.dev/start!)` invocation in `seon.client/start-agent!`
   behind `SEON_INSTRUMENT` env var
2. Audit `:malli/schema` annotations across public fns (most are present;
   a sweep would catch missing ones)

### Ordering for sequential landing

P1 standalone (small, surgical, blocks nothing).

P2 — no work needed today.

P3 (defaults via `:default/fn`) needs careful testing — it changes the
transact boundary's behavior. Can land after P1.

P4 (`:any` audit) can run incrementally; each site is independent.

P5 splits into three independent moves; do `!state` first (smallest),
`!fs-config` second, `slow-eval-threshold` last.

P6 (instrumentation) is the riskiest — turn it on in dev and chase down
schema mismatches. Defer until P3 lands (the defaults work means fewer
validation failures during instrumentation bring-up).

---

## 9. Open questions / decisions Sean needs to make

1. **`current-agent-id` semantics for v0's single-agent case.** Proposed:
   `(d/q '[:find ?id . :where [_ :seon.agent/id ?id]] @*conn*)`. Returns
   one id, or nil if no agent yet (handler should throw — caller is
   asking before boot). Multi-agent v1 needs an explicit context (URL
   param? bound dynamic? request-scoped ALS bucket?). **Decide the
   v0 shape now; commit to "explicit-arg-required" as v1 contract.**

2. **`:default/fn` and validation order.** Today
   `db/transact!` validates THEN writes. If we decode first to fill
   defaults, the validation runs on the post-default value. That's
   correct. But it changes the error messages users see (missing key
   → schema mismatch on filled-in default value if the default-fn is
   wrong). Acceptable tradeoff?

3. **Instrumentation in CLJS — perf overhead.** Malli's instrumentation
   isn't free in CLJS (every call validates). Sean's JVM stance is
   "instrumentation is always on." For the CLJS pod, with the warmup
   cost of bootstrap-CLJS already non-trivial, this could be felt.
   Worth measuring before committing.

4. **`!fs-config` migration ordering.** Moving allowlist to DB means
   the bootstrap path needs the conn already open before `seon.fs`
   loads. Currently `seon.fs` reads env vars at ns-load time. Either
   keep env-bootstrap as the "first boot" path and overlay DB on top,
   or require the conn at `start-agent!` time before any fs op fires.

5. **Should the bridge auto-emit `:db/doc` from schema properties?**
   Free win, but requires every schema author to add `:description`
   strings. Make it opt-in via marker prop (`{:seon.db/doc "…"}`)?

6. **Per-agent registries (multi-agent v1).** When two agents define
   `:agent-a/foo` and `:agent-b/foo`, do they SHARE the same
   `:agent-a/foo` symbol space (today's behavior — global registry),
   or do they get per-agent registries? Affects schema key naming
   conventions. Probably global-is-fine because keys are already
   namespaced — but worth deciding before someone hits the conflict.

7. **The "current-ns" pattern repeats for other "current X" attributes.**
   `seon.agent/current-ns` is derived from latest successful eval —
   reactive. `seon.agent/current-session` is derived from latest
   `:seon.session/at`. `seon.agent/current-turn` is derived from latest
   turn in latest session. Pattern is healthy. But: the proposed
   `current-agent-id` is the SAME pattern. Should there be a generalized
   `seon.db/latest-of` helper, or are these one-off enough that
   inlining is clearer? (Recommendation: inline. The pattern is two
   lines and the variant logic per "current X" matters.)

---

## Appendix A — full schema inventory

129 register! sites across pod CLJS/CLJC. Reachable via:

```bash
grep -rn "schema/register!" src/seon/ --include="*.cljs" --include="*.cljc"

```

## Appendix B — full defonce inventory

22 defonces in pod CLJS, enumerated in §4. Reachable via:

```bash
grep -rn "^(defonce\|^(def \^" src/seon/ --include="*.cljs" --include="*.cljc"

```

## Appendix C — files read for this audit

- `src/seon/{agent,client,db,eval,log,repl,render,fs}.cljs`
- `src/seon/web/{serve,broadcast}.cljs`
- `src/seon/{schema,parse,code}.cljc`
- `src/seon/render.cljs`
- `src/seon/test/runner.cljs`
- `docs/prds/agent-runtime/research/{id-generator-design,malli-schema-references,eval-batch-fragility,derive-not-store,datahike-capabilities,resume-findings}-2026-05-{22,23}.md`

Reference-code spot checks (paths only — read prior research that
extracted the relevant excerpts):

- `reference-code/malli/src/malli/core.cljc` (schema reference semantics)
- `reference-code/datahike/src/datahike/db/transaction.cljc` (tx-meta + flush)
- `reference-code/datahike/test/datahike/test/pull_api_test.cljc` (reverse refs, recursion)
