# Rules of Thumb: Data Model, Rules, Renderers & `@ctx`

Seon aims for **Distributed Understanding**: any agent working with any entity — built-in or brand-new custom domain data — should be able to attach or pull that data and automatically get its schemas, compatible functions, citing docs, clean multi-scale rendering, and direct Clojure/Datahike manipulation without bespoke one-off plumbing.

Use these five rules of thumb when deciding *where* a change belongs.

## 1. Fix the Data Model First

Always shape the Datahike schema before writing rules, renderers, or REPL helpers:

- **Use true `:db.type/ref` edges (`:seon.db/to [...] :seon.db/index true`), not raw keyword/string IDs.** Every entity linked by `:db.type/ref` automatically enters the two-index graph (`:eavt` forward + `:avet` reverse), appears in `@ctx` with zero whitelists, and can be joined directly by any Datalog rule (for example `:seon.agent.eval/agent-ref`, `:seon.agent/forked-from`, `:seon.agent/tasks`, `:seon.agent/target`). Never persist derived read-sets (`:seon.agent.eval/touched` / `:touched-refs`) into LMDB — `seon.ctx.touches/derived-agent-touches` derives uncapped touches cleanly from live in-memory `datahike.reads` and `d/changes`.
- **Put data on entities the way you want to pull it back out and process it.** Keep all real datoms intact on the entity (including telemetry like `:ticks`, `:bytes`, `:ms`). Never mask or strip fields in pull whitelists — address visual noise in renderers and presentation rules so agents can still inspect, filter, or update any field with plain Clojure.
- **Make things data; never encode process if you can derive it.** Store what is wanted and what was observed, and derive state from current facts (`docs/decisions.md`). Example: retracting dead/failing evals from `:seon.agent.eval/_agent-ref` removes them from the branch head (while keeping them in `d/history`), so pure Datalog rules over branch evals automatically stop firing with zero `:compacted-through-turn` process flags.

## 2. When to Write a Declarative Rule (`:seon.rule/*`)

Write a declarative `:seon.rule/*` entity in Datahike (`resources/seon/rules.edn`) whenever you want to **derive facts, connect graph neighborhoods, gate actions, or set presentation policy** across `$main`, `$branch`, and `$wm`:

- **Distributed Understanding (`:category :context`):** Surface schemas of connected or eval-touched entities (`seon.rule/entity-facets`), functions consuming those schemas (`compatible-fn`), docs referencing them (`:seon.code.doc/references`), callers of edited vars, or error neighborhoods.
- **Detection & Gating (`:category :health` / `:context`):** Express proof gates (`:rule.gate/*`), stall/error-streak signals (`:agent/needs-review`), or live `:ephemeral-tail` course-correction blocks directly as Datalog queries over real datoms — no bespoke Clojure counter structs or hardcoded magic-number loops.
- **Presentation Policy (`:category :present`):** Control depth-to-layout mapping (`:expanded-card` -> `:compact-row` -> `:ref-pill`), collection grouping, and large-collection slicing (`:rule.preview/*`).
- **Situational Emphasis:** Emit `:rank/boost`, `:rank/suppress`, `:zone`, and renderer `:params` when a rule fires.

## 3. When to Make Multiple Pure `defn` Renderers

Write **multiple pure 1-arg `ns-map -> ns-map` renderers** (`[:map [:my/entity :my/entity]] -> [:map [:seon.ui/hiccup :seon.ui/hiccup]]`) for the same schema when you need **distinct structural form factors and footprints**. Both the Upgrade Ladder (`seon.render.fit/upgrade-ladder`) and the 4-stage self-printer (`seon.render.print`) discover all pure renderers via reverse `:avet` schema edges and select among them automatically:

| Suffix / Role | Typical Width | Selected Automatically When |
|---|---|---|
| `render-<x>-chip` / `-badge` | `~160–180px` | Depth 2+ (`:ref-pill`), tight token/pixel budgets, inline summaries |
| `render-<x>-row` / `-snippet` | `~200–240px` | Depth 1 (`:compact-row`), items inside collections (e.g. `(:seon.agent.eval/_agent-ref @ctx)`) |
| `render-<x>-card` | `~360–460px` | Depth 0 (`:expanded-card`), default schema `:seon.render/hiccup` view |
| `render-<x>-full` | `~520–560px` | Primary focus when budget permits upgrading past the summary card |

Keep noisy telemetry tucked cleanly into `-card`/`-full` footers and omitted from `-chip`/`-row` so collections of 30+ real entities render in ~2 KB while preserving 100% of the underlying data in `@ctx` and Datahike.

## 4. When to Have a Rule Configure One Renderer via `:params`

Use **rule-supplied `:params`** instead of creating another `defn` when the entity keeps the **same card structure**, and only the **detail level or situational highlight** changes because of *why* the rule fired:

- Expanding a section: `:seon.ui/expand-section :source` or `:report` or `:plan`
- Toggling contextual facets: `:show-source? true`, `:show-diff? true`, `:seon.ui/show-callers? true`
- Highlighting matched terms: `:highlight-terms ["token1" "token2"]`
- Demoting a broad scope tile: `:seon.ui/summary-strip? true`

Declare optional param keys in the renderer's `:malli/schema` (recorded as `:seon.code.var/optional-input`) so `seon.ctx.project/validate-projection-params` validates rule `:params` mechanically.

**Quick test:** *Different size class across budgets or nesting depth -> multiple `defn` renderers. Same card, situational emphasis from a firing rule -> optional `:params` from the rule.*

## 5. REPL `@ctx` & Datahike vs Pure Functions

- **Pure functions & renderers** never touch global atoms: they receive immutable values (`db`, `{:my/entity ...}`, `{:seon.ctx/snapshot ...}`) as arguments, and only transact changes through Datahike when explicitly performing write work.
- **`@ctx` is a REPL-only reactive `CtxAtom`**: it is literally Datahike's two indexes (`:eavt` forward + `:avet` reverse across all `:db.type/ref` attrs) around your agent entity converted into a Clojure tree, with `O(|Δ|)` `datahike.reads` refresh. Plain Clojure `(swap! ctx ...)` writes diffs back through Malli validation — including `[:db/retractEntity eid]` when you remove an element from a reverse-ref vector like `:seon.agent.eval/_agent-ref`, setting `:seon.agent/ready? true` to signal completion, or editing a reviewed agent via `[:seon.agent/target ...]`.
- **Use wrapped Datahike tools whenever `@ctx` is awkward** or you are querying/transacting across other entities: `(user/db)`, `(user/main-db)`, `(d/q ...)`, `(d/pull db pat ref)`, `(d/datoms db :avet/:aevt ...)`, `(d/history db)`, `(d/as-of db tx)`, `(seon.vector/near db ref k {:of :seon.code.var/name})`, and `(user/transact! tx-data)`.
