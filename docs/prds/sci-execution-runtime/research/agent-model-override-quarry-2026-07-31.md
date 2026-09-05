---
type: research
status: active
tags: [research, ai, config, schema]
---

# Per-agent model overrides — quarry and the runtime-default design (2026-07-31)

Owner ask: a cluster-wide DEFAULT model, overridable PER AGENT the way a chat
application lets you pick a different model per chat — model choice plus model
options (thinking budgets and friends).

**Read `research/percall-llm-config-2026-07-29.md` first.** That document is the
settled quarry and the settled design for the TARGET vocabulary, the three
localities, the provider descriptor row, and the per-provider thinking controls.
It is not superseded and nothing here re-decides it.

This document is the DELTA the owner's ask adds on top of it, and it exists
because the July 29 design deliberately left one thing out — a *user-changeable
cluster default* — and because four rulings landed after it (2026-07-31 #13
one-walk context, #14 one schema file, #15 `:my/*` names, #16 the two-pane debug
view) that decide where the fact lives and how an agent sees it.

## 1. Quarry — confirmed, with the two corrections the earlier read missed

The quarry lane re-mined `src-old/`, deleted files through `git show`, and
`docs/seon/reference/llm-adapters.md`. It confirms
`percall-llm-config-2026-07-29.md` §"Quarry" and adds two findings worth
recording.

### 1.1 Per-agent overrides existed and were the PRIMARY mechanism

Fourteen attributes registered on the agent entity at
`src-old/seon/ai/core.cljc:66-80`: `:seon.ai/agent-provider`, `/agent-model`,
`/agent-temperature`, `/agent-max-tokens`, `/agent-completion-limit-field`,
`/agent-thinking`, `/agent-timeout-ms`, `/agent-base-url`, `/agent-api-key-env`,
`/agent-dg-backend`, `/agent-extra-body-edn`, `/agent-max-retries`,
`/agent-attempt-timeout-ms`, `/agent-fallback-variant`.

The override map pairing each agent attribute with the effective attribute it
overrides is `core.cljc:323-334`; the pull pattern is `core.cljc:342-352`. Only
PRESENT attributes became overrides (`core.cljc:442-450`):

```clojure
(defn- agent-row-override-values [agent]
  (reduce-kv (fn [values agent-attr config-attr]
               (if (contains? agent agent-attr)
                 (assoc values config-attr (get agent agent-attr))
                 values))
             {} agent-override-attrs))
```

The resolution order is explicit at `src-old/seon/ai/core.cljc:470-503`:

```clojure
(defn- resolve-config-values [shipped-defaults row-config overrides transport-caps]
  (let [pick (fn [key defaults]
               (cond
                 (contains? overrides key)   [(get overrides key) :agent-override]
                 (contains? row-config key)  [(get row-config key) :config-row]
                 (contains? defaults key)    [(get defaults key) :default]))
        [provider provider-source] (or (pick :seon.ai/provider {}) [:deepseek :default])
        defaults (get shipped-defaults provider {})
        …
```

Agent override → cluster config row → provider default → `:deepseek` as the
last-resort provider, with per-key provenance stamped as
`:agent-override` / `:config-row` / `:default` (`core.cljc:119-120`). The
provider is picked FIRST and its default table then supplies everything else —
the reason a per-agent override cannot be a bare model string.

`:inherit` was killed on 2026-07-22 (`da8a4fce4`, "the `:inherit` sentinel is
dead — absence means inherit, natively typed"); the manifest leaf still refuses
a declared `:inherit` at `src-old/seon/config/resolve.cljc:733-781`.

### 1.2 CORRECTION — per-agent BACKUP existed (`:seon.ai/agent-fallback-variant`)

`src-old/seon/ai/core.cljc:585-592` resolves the fallback as a SECOND COMPLETE
resolution rather than smearing overrides onto the primary:

```clojure
primary (attach-descriptor (provider-resolution agent-row))
fallback-variant (:seon.ai/agent-fallback-variant agent-row)
fallback-row (get (model-variants config-row) fallback-variant)]
(cond-> primary (and (keyword? fallback-variant) (map? fallback-row))
  (assoc :seon.ai/fallback-variant fallback-variant
         :seon.ai/fallback-config-resolution
         (attach-descriptor (provider-resolution fallback-row))))
```

That is the same rule `percall-llm-config-2026-07-29.md` §"Call-site
integration" arrives at independently ("primary agent/call overrides must not
automatically overlay the backup"). It is a quarried lesson, not a new idea, and
it means a per-agent BACKUP is cheaper than the earlier read assumed.

### 1.3 CORRECTION — per-CALL config was only half-real in the old system

The agent turn loop could NOT vary its model per call. The pod's request schema
was closed (`git show 3f1895b04^:src/seon/ai.cljs:554-560`):

```clojure
[:map {:closed true}
 [::ctx ::ctx] [::system-prompt {:optional true} ::system-prompt]
 [::stream? {:optional true} ::stream?] [::abort-signal {:optional true} ::abort-signal]
 [::config-resolution ::config-resolution]]
```

and dispatch invoked the adapter with no option map
(`3f1895b04^:src/seon/ai/dispatch.cljs:87-113`). Direct (non-loop) adapter calls
DID accept model/temperature/max-tokens/tools/tool-choice/extra-body
(`3f1895b04^:src/seon/ai/openai_compat.cljs:68-82`; the surviving portable
projections still honor it at `src-old/seon/ai/openai_compat/core.cljc:15-38`
and `src-old/seon/ai/anthropic/core.cljc:22-32`). `llm-adapters.md:522-538`
states the gap outright: the per-call `:seon.ai/extra-body` is "**Unreachable
from the agent turn loop** (it builds the adapter with no opts)."

Consequence for us: **the per-call locality in the July 29 design is genuinely
NEW work, not a port.** Thinking was a string on the resolved config
(`core.cljc:401-409`), never a per-call value, and there were no token budgets,
no top-p, and no top-k anywhere in the old tree.

### 1.4 The bug history the design must not repeat

- `9b4a819e2` → owner correction `560a5f226`: the resolved config had been
  stamped as per-turn datoms; reverted to derive-don't-store.
- `3ecc9eb18`: an attempt re-read mutable config mid-assembly; fixed by
  freezing one resolution.
- `df78bb8d2` (issue `turn-retries-reread-provider-inputs.md`): retries could
  see newer model facts; fixed by reusing the one frozen resolution.

All three say the same thing: **resolve ONCE per turn from ONE database value,
freeze it, and let every attempt in that turn reuse it.** §3.3 is where that
lands in the fresh loop.

## 2. What the fresh tree does today

`resources/seon/schema/config.edn` registers five primary dials
(`:seon.config.ai/endpoint`, `/model`, `/max-tokens`, `/api-key-variable`,
`/timeout-ms`, plus `:seon.config.ai/no-auth`) and four optional
`:seon.config.ai.backup/*` overrides. `config/default.edn` ships DeepSeek.
`seon.ai/targets` (`src/seon/ai.cljc:93-137`) is the one pure projection from
dials to `{:seon.ai/primary … :seon.ai/backup …}`. There is no thinking dial, no
temperature dial, and no per-agent anything.

`config.edn`'s own comment on the backup row already names today as the trigger:

> Two roles do not need a join table; **when a third role or per-agent overrides
> arrive, THAT is the change that earns the entity rows.**

### 2.1 The target is frozen at ARM time — the blocking defect

`src/seon/cluster.clj:1004-1017`, `loop-handle`:

```clojure
[connection cluster-name process wake-channel stream-channel completion]
(let [dials (config/effective @connection cluster-name)]
  (cond-> (merge (ai/targets dials) {:seon.store/branch-connection connection …})))
```

`src/seon/cluster/loop.cljc:864,877` then reads `(:seon.ai/backup cluster)` and
`(:seon.ai/primary cluster)` off that frozen handle. So the target is fixed when
the agent's graph is ARMED, for the agent's whole life. A per-agent override
resolved there would be unchangeable without a restart — the opposite of "pick a
different model for this chat". The old system read per call with no cache
(`llm-adapters.md:60-69`); the fresh loop must resolve per TURN (§3.3).

## 3. The delta this document contributes

### 3.1 THE FINDING — a user-changeable default cannot live on the config singleton

`seon.config/apply!` (`src/seon/config.cljc:236-251`) compiles the manifest and
hands the desired row to `seon.reconcile/reconcile!`.
`seon.reconcile/entity-exact-tx` (`src/seon/reconcile.cljc:261-302`) unions the
CURRENT and DESIRED attribute sets and retracts every attribute whose presence
differs:

```clojure
attrs (-> (set/union (set (keys current)) (set (keys desired)))
          (disj :db/id identity-attr))
changed (->> attrs (filter (fn [attr]
            (let [current? (contains? current attr)
                  desired? (contains? desired attr)]
              (or (not= current? desired?) …)))))
retracts (… [:db.fn/retractAttribute identity attr] …)
```

An attribute written onto the config entity at runtime is present-in-current and
absent-in-desired, therefore **retracted by the next `bin/seon config apply`**.
The config singleton is exactly manifest-owned, as the ruling says, and that is
enforced by code rather than by convention.

`percall-llm-config-2026-07-29.md` is consistent with this — it says
"neither belongs in `:seon.config/effective`" and treats the cluster primary as
a role DEFAULT. What it does not answer is the owner's new ask: **a default a
user changes at runtime, that survives a restart and a manifest apply.** That
fact needs a home outside the config singleton.

### 3.2 THE HOME — the cluster entity, which is already an accretion surface

`seon.cluster/ensure-cluster-entity!` (`src/seon/cluster.clj:791-868`) converges
a NAMED BASE SET only — `:seon.cluster/name`, `:seon.cluster/config`,
`:seon.cluster/instructions`, `:seon.cluster/toolkit` — and retracts exactly two
attributes, both boot-derived (`src/seon/cluster.clj:856-864`):

```clojure
(cond-> []
  current (conj [:db/retract [:seon.cluster/name cluster-name] :seon.cluster/instructions]
                [:db/retract [:seon.cluster/name cluster-name] :seon.cluster/toolkit])
  true (conj desired))
```

Every other attribute on the cluster entity survives every boot untouched. That
is "the cluster entity owning cluster-wide accreted facts", and it is the honest
home for a runtime default. Proposed:

```clojure
;; the catalog of defined descriptor rows — same shape as the two sets already
;; on this entity (:seon.cluster/instructions, :seon.cluster/toolkit)
:seon.cluster/models [:set :seon.db/ref]
;; the runtime cluster default; ABSENCE means "the shipped dials"
:seon.cluster/model  :seon.db/ref
```

**Load-bearing rule.** Neither attribute may join `ensure-cluster-entity!`'s
converged base set, and neither may be retracted by it. Boot's only jobs are
create-if-absent — ensure the shipped descriptor row exists (derived from the
dials, id `:default`) and is a member of `:seon.cluster/models`. Boot NEVER
overwrites `:seon.cluster/model`: an operator's runtime choice outranks a
restart. Same discipline `ensure-entity-call` already uses for the root agent
(`src/seon/cluster.clj:870-880`).

A second, independent guarantee if descriptor rows are ever manifest-seeded:
`reconcile`'s managed slice is provenance-scoped
(`src/seon/reconcile.cljc:342-355`) — an entity is managed only when its
identity's first assertion carried the MANAGING process identity, so rows
created at runtime are never retracted as stale. Noted; not relied on (§5.4).

**The catalog earns its keep twice.** Because `:seon.cluster/models` is a set of
refs on the cluster entity, and every agent refs its cluster, the agent's
ordinary walk REACHES every defined descriptor. An agent knows what it can
switch to because it can SEE the rows — no `list-models` capability, no hand
list, no enumeration code.

### 3.3 The resolution chain and where it runs

Four localities, resolved by presence, with no sentinel and no kinds:

```text
call/situation map  →  agent facts  →  cluster runtime default  →  shipped dials
```

The first two are `percall-llm-config-2026-07-29.md`'s design unchanged. The
third is new (§3.2). The fourth is `seon.ai/targets` as it stands.

Where each layer is written, and by whom:

| Layer | Home | Writer | Runtime-changeable |
|---|---|---|---|
| shipped default | `:seon.config.ai/*` on the config singleton | the manifest, via `apply!` | no — exact-reconciled (§3.1) |
| cluster default | `:seon.cluster/model` ref on the cluster entity | operator / UI / agent | yes |
| per-agent | `:my/model` ref + sparse `:seon.ai/*` facts on the agent entity | user or the agent itself | yes |
| per-call | the argument map | the loop, from the situation | per turn |

**Resolve once per TURN, from the turn's own database value, then freeze** —
the §1.4 bug history, arrived at three separate times in the old tree. The
concrete change: `loop-handle` (`src/seon/cluster.clj:1004`) stops merging
`(ai/targets dials)` for the primary, and the `:call` branch
(`src/seon/cluster/loop.cljc:877`) resolves from the `db` it already holds for
the prompt render, before the first attempt. Backoff retries and the failover
attempt reuse that frozen value, exactly as they reuse the one prompt capture
today (`src/seon/cluster/loop.cljc:820-838`).

### 3.4 The per-agent attribute, in post-rename names

Ruling 2026-07-31 #15 (`plan/README.md:1558-1575`) renames the attributes stored
on the agent entity to `:my/*`. Write this feature in those names from the
start:

```clojure
:my/model :seon.db/ref     ; → one :seon.ai.provider/descriptor row
```

A ref, not an id string: a dangling choice becomes unrepresentable, and the walk
reaches the row so the agent sees the actual settings rather than a bare
keyword. Cardinality one. Absent = inherit; to return to the cluster default you
RETRACT it, per the quarry's own settled `:inherit`-is-dead rule (§1.1).

```clojure
:my/agent
[:map {:seon.db/entity true
       :seon.render/ai seon.render.agent/agent-ai
       :seon.render/html seon.render.agent/agent-html}
 [:my/id        :my/id]
 [:my/namespace {:optional true} :my/namespace]
 [:my/run       {:optional true} :my/run]
 [:my/cluster   :my/cluster]
 [:my/model     {:optional true} :my/model]]
```

**Routing is selected; generation is overlaid.** The ref carries the coherent
routing bundle (adapter core, endpoint, credential variable, thinking policy) —
a caller must never have to reconstruct authentication policy
(`percall-llm-config-2026-07-29.md` §"One target vocabulary, three localities").
The sparse per-agent GENERATION facts (`:seon.ai/max-tokens`, `/temperature`,
`/reasoning-effort`, `/think`, `/thinking-budget-tokens`) overlay it. That split
is the old system's own documented one (`llm-adapters.md:72-77`: generation
fields resolve request → agent → row → default; routing/transport fields are
database-owned and "not arbitrary per-call options"), and it dissolves the
fourteen-attribute mirror: the ref replaces `agent-provider`/`agent-model`/
`agent-base-url`/`agent-api-key-env`/`agent-completion-limit-field` in one fact.

Naming tension to rule on: those generation facts sit on the agent entity, and
ruling #15 says agent-entity attributes are `:my/*`. See §5.1.

### 3.5 Render — the agent SEES what it runs on, with no new mechanism

Ruling #13 makes context ONE `seon.render/walk` from the agent's own entity, so
everything reachable by ref renders with no block and no hand list:

- **override present** — the walk reaches the descriptor at depth 1 through
  `:my/model`;
- **inherited** — the agent has no `/model` datom, and the walk reaches
  `:seon.cluster/model` at depth 2 through `:my/cluster`. The inherited case
  renders as a CLUSTER fact, which is exactly what it is;
- **the catalog** — `:seon.cluster/models` renders beside it, so "what else
  could I run on" is answered by the same walk.

The descriptor entity map therefore needs the family lens pair
(`:seon.render/ai` / `:seon.render/html`) declared on it, like every other family
(`resources/seon/schema/run.edn:14-23` is the model to copy). One sentence in the
possessive voice, options nil-punned: *Model deepseek-chat at api.deepseek.com —
reasoning effort high, 8192 output tokens, 60s deadline.* The html twin serves
the namespace page and the right pane of the two-pane debug view (ruling #16).

Explicitly rejected: a derived "you are running on X" context block. It would be
a second mechanism against ruling #13, and the fact is already reachable.

**Measure, do not assume**: the default walk depth must actually reach depth 2
for the inherited case to render. If it does not, the fix is the walk's depth
(§5.5).

### 3.6 The `my.*` surface — a value the driver commits

Changing your model is a durable FACT, so it is the third agent-facing shape,
constructed exactly like `my.message/send`: a pure constructor returning a
value; the LOOP commits it in its terminal transaction. Nothing about model
selection happens inside an eval, and no capability request handler is involved.

```clojure
(ns my.model
  "Which model you run on. Pure constructors; the loop commits the fact.")

(defn use
  "Run on this model from your next run onward. `target` is one of the ids you
  can see on your cluster. Returns a value, commits nothing."
  {:malli/schema [:=> [:cat :seon.ai.provider/id]
                  [:or :my.model/use :seon.error/value]]}
  [target] …)   ; → {:my.model/target :qwen-local}

(defn inherit
  "Stop overriding: run on your cluster's default. The loop RETRACTS your
  override — absence IS inheritance, there is no inherit value to store."
  {:malli/schema [:=> [:cat] :my.model/inherit]}
  [] …)         ; → {:my.model/inherit true}
```

The loop translates `{:my.model/target id}` into an assert of `:my/model` →
`[:seon.ai.provider/id id]`, and `{:my.model/inherit true}` into
`[:db.fn/retractAttribute <agent> :my/model]`. Whether the named id EXISTS is a
fact about the database, so — exactly as in `my.message` — the constructor stays
pure and the DRIVER refuses with a flat error value the agent reads next run.

Cluster-default and catalog changes are operator/UI actions in v1: a `bin/seon`
command and one form on the agent's namespace page (ruling #17), posting a
target id on the path the message form already takes. That UI unit is separate;
this document settles the data model it writes.

## 4. What is deliberately NOT carried over

- The fourteen `:seon.ai/agent-*` mirrored attributes — replaced by ONE ref plus
  sparse generation facts (§3.4).
- `:seon.config/model-variants` as a config map (`llm-adapters.md:424-441`) —
  the variant IS a descriptor row now, which is the same discovery taken to its
  data-oriented conclusion.
- `extra-body-edn` as a durable attribute. An opaque EDN string is not a data
  model; if a real pass-through need appears it comes back with evidence, not as
  a default escape hatch.
- Env-var seeding (`SEON_AI_*` / `sync!`). Fresh Seon reads the database; the
  manifest is the file-side writer.
- Stamping the resolved configuration as per-turn datoms (`9b4a819e2`, reverted
  by `560a5f226`). The attempt row already records the resolved endpoint/model;
  everything else is derived.
- Any `:kind`/`:provider-type` stamp. A row is what its attributes say; the
  adapter core and thinking policy are declared facts on the row, not a taxonomy.

## 5. Open decisions

Ranked. Nothing should be implemented before the owner rules on 1 and 2.

1. **Do the per-agent GENERATION facts get `:my/*` names?** Ruling #15 says
   attributes stored on the agent entity are `:my/*`; the overlay design wants
   the SAME attribute vocabulary at every locality so the resolver needs no
   translation table (`percall-llm-config-2026-07-29.md` §"One target vocabulary,
   three localities" concedes agent facts need distinct names only because they
   coexist with the effective vocabulary on one entity).
   (a) **[RECOMMENDED]** Ship the REF ONLY in v1 — `:my/model` and nothing else
   on the agent. It delivers the owner's literal ask (choose a model per agent),
   needs zero renaming, zero overlay code, and zero translation table. Sparse
   per-agent generation facts arrive with the agent-modes unit, when the
   situation layer that would use them exists.
   (b) `:my/max-tokens`, `:my/reasoning-effort`, … — obeys #15, costs a
   rename map in the resolver.
   (c) `:seon.ai/*` directly on the agent entity — no translation, breaks #15's
   letter. Only viable if the owner rules that #15 governs identity/lifecycle
   attributes rather than every datom on the entity.

2. **Where the runtime default lives.**
   (a) **[RECOMMENDED]** `:seon.cluster/model` on the cluster entity (§3.2) —
   survives boot and manifest applies, reachable by the agent's walk, mirrors two
   attributes already on that entity.
   (b) Leave the cluster default in the config manifest only. Simplest, and it
   is what `percall-llm-config-2026-07-29.md` assumed — but it does NOT satisfy
   the ask: a runtime change is destroyed by the next apply (§3.1), so the
   default would only be changeable by editing a file and restarting.
   (c) A new runtime-settings singleton entity. A second mechanism beside the
   cluster entity for one attribute. Reject.

3. **Per-agent BACKUP.** The quarry HAD it (§1.2) and resolved it as an
   independent second resolution.
   (a) **[RECOMMENDED]** `:my/model-backup` as a second ref in the same unit —
   the resolution rule is already written down in both this quarry and the July
   29 design ("primary overrides must not overlay the backup"), so the marginal
   cost is one attribute and one call.
   (b) Defer. Costs a second pass over the same call site later.

4. **Who defines descriptor rows.**
   (a) **[RECOMMENDED]** Runtime only in v1: boot ensures the ONE shipped row
   from the dials; everything else is created by operator/UI. The manifest keeps
   exactly one job.
   (b) Manifest-seeded rows through `reconcile!`. Technically safe (§3.2's
   provenance scoping), but it needs the config gate, the desired-row identity
   rule, and the ref-candidate form changed together — precisely what
   `config.edn`'s own comment warns about. Defer until a cluster needs a fleet
   of rows at birth.

5. **Walk depth for the inherited case.** Measure whether the agent's default
   walk reaches `:seon.cluster/model` at depth 2. If not: (a) **[RECOMMENDED]**
   raise/verify the walk depth — the fact belongs where it is; (b) reject a
   special-case context block (second mechanism, against #13).

6. **Descriptor entity shape.** The July 29 orchestrator review note already
   ruled this: NOT an `[:or]` of two entity-tagged maps — `register!` derives
   `:seon.entity/id-attr` from the TOP-LEVEL form only. One top-level entity map
   with both authentication keys optional, and the "exactly one of them"
   invariant enforced by the projection function returning a flat error value,
   since Datahike cannot express it structurally. Carried forward as a
   constraint, not reopened.

7. **Sequencing.** Ruling #15's rename is ONE atomic wave scheduled after the
   context-mvp lane; ruling #14 consolidates the schema EDN files.
   **[RECOMMENDED]** land this feature after both, writing `:my/model` from the
   start into whatever single schema resource #14 has produced. Landing before
   means writing `:seon.cluster.agent/model` and renaming it days later.

8. **`my.model/use` naming.** `use` reads well and shadows nothing agent-facing;
   `switch`/`select` are the alternatives. Minor; recommendation is `use`.

## 6. Acceptance evidence this unit adds

`percall-llm-config-2026-07-29.md` §"Acceptance evidence" stands. Add:

- a runtime `:seon.cluster/model` change survives `bin/seon config apply` AND a
  cluster restart — the direct falsifier for §3.1, and the one that would have
  caught putting the default on the config singleton;
- boot does not overwrite an operator-set `:seon.cluster/model`, and does not
  retract `:seon.cluster/models` (a second `ensure-cluster-entity!` run leaves
  both intact);
- an agent's `my.model/use` on turn N changes the model actually sent on turn
  N+1, with NO restart and no re-arm — the falsifier for the arm-time freeze
  (§2.1);
- the agent's own walk output NAMES the model it is running on in both cases:
  override present (depth 1) and inherited (depth 2);
- `my.model/use` naming an id no row carries answers with a flat error value the
  agent reads on its next run, and commits nothing.
