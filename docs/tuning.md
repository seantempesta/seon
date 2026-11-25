# Context Tuning & Supervisor Rollback Field Guide

Everything in Seon is schema-validated Datahike data. When an agent misses context, searches repeatedly, or hits an error, a **Pause-and-Fork Context Fixer** fixes the **general class** (never hardcoding task/var/ns literals), verifies the updated prompt live in `<target-context-preview>`, rolls the agent back to before the error via `@user/ctx`, and resumes it.

## 1. The Supervisor Rollback-Fix-Resume Cadence

1. **Diagnose from the forked prefix + preview**: Read the forked agent's turns above (inherited verbatim), `<target-context-preview>`, `(user/simulate-context <target-aid> {:render-text? true})`, and `(user/coverage {:include-corpus? true})`.
2. **Classify the miss**:
   - **Recall miss** (right entity never entered candidates): add or refine a general `:seon.rule/*` Datalog rule or `seon.ctx.graph` walker.
   - **Precision / budget miss** (right entity dropped or bloated): add a compact pure `:seon.ui/hiccup` renderer or a stratum-20 `:rank/boost` / `:rank/suppress` rule.
   - **Schema / ref miss** (missing graph edge or policy): add `:db.type/ref` or `:seon.schema/context-policy` via `(user/register-schema! ...)`.
3. **Iterate with live feedback**: After each edit, inspect `<target-context-preview>` on your next turn (it shows exact `+added / -removed` tiles, tokens, and a rendered excerpt for `<target-aid>`). Run `(user/check-corpus)` to verify `12/12` corpus pass, `0` `:generality-issues`, and `0` regressions.
4. **Roll back the forked agent to right before the error via core Clojure `@user/ctx`**:
   ```clojure
   ;; Keep only turns strictly before `err-turn` (retracts bad turns cleanly in Datahike):
   (swap! user/ctx update-in [:seon.agent/forked-from :seon.agent.eval/_agent-ref]
          #(filterv (fn [e] (< (long (:seon.agent.eval/turn e 0)) err-turn)) %))
   ```
5. **Prove green & resume**: Run `(user/run-tests!)` + `(user/health)` (0 errors, every ns < 8k tok), then call `(user/finish-review! "Summary of general class fixed, corpus delta, rollback turn, and tests verified")`.

## 2. Recall vs Precision: General Datalog Rules

Transact `:seon.rule/*` entities directly as data (`user/transact!`). Rules are validated by Malli and checked by `seon.health.coverage/lint-rule-generality` (ZERO hardcoded agent, task, var, or ns literals in `:where`):

```clojure
;; Example Recall rule (stratum 16, projects graph neighbors into :workspace):
(user/transact!
 [{:seon.rule/id :rule.ctx/my-general-recall
   :seon.rule/category :context
   :seon.rule/stratum 16
   :seon.rule/target :any
   :seon.rule/enabled? true
   :seon.rule/doc "Surface general structural neighbors."
   :seon.rule/query '[:find ?e ?tx :in $main $branch ?agent
                      :where ...]
   :seon.rule/assert '[{:entity ?e :pull [*] :zone :workspace :priority 85 :tx ?tx}]}])
```

Available `:in` bindings: `$`, `$main`, `$branch`, `$code`, `$task`, `$wm`, `?agent`, `?agent-eid`, `?focus`, `?focus-eid`, `?hierarchy`, `%`.
Use `seon.ctx.graph` pure indexed helpers (`d/datoms :avet/:eavt`, never full scans in loops) for multi-hop graph walks.
For **Precision**, assert `:params {:rank/boost 1.4}` or `:params {:rank/suppress 0.3}` to prioritize high-signal entities over noisy ones without hard truncation caps.

## 3. Multi-Footprint Pure Hiccup Renderers

The Upgrade Ladder (`seon.render.fit`) automatically chooses the richest pure renderer that fits the token/viewport budget. Define BOTH a compact chip (`<= 60` tok, `:data-min-width <= 240`) and an expanded card using standard Clojure:

```clojure
(in-ns 'seon.render.entities.work)

(defn render-thing-chip
  "Compact chip for `:seon.thing/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.thing/entity :seon.thing/entity]]]
                      [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 500000}
  [{{:seon.thing/keys [id label]} :seon.thing/entity}]
  {:seon.ui/hiccup [:span {:class "ent-chip" :data-min-width 180} (str (or label id))]})
```

## 4. Schemas, Refs & Core-Clojure @user/ctx Data Recipes

`@user/ctx` is a live bidirectional Datahike-backed Clojure atom. Edit data using standard Clojure (`get`, `filter`, `remove`, `assoc`, `conj`, `swap!`) — every write is checked against Malli schemas:

- **Pin an entity onto the forked target**:
  `(swap! user/ctx update-in [:seon.agent/forked-from :seon.agent/pinned] (fnil conj #{}) [:seon.code.var/name 'ns/v])`
- **Prune specific noisy turns**:
  `(swap! user/ctx update-in [:seon.agent/forked-from :seon.agent.eval/_agent-ref] #(remove (fn [e] (#{2 3} (:seon.agent.eval/turn e))) %))`
- **Update the forked agent's task plan/summary**:
  `(swap! user/ctx update-in [:seon.agent/forked-from :seon.agent/tasks] #(mapv (fn [t] (assoc t :seon.task/plan "...")) %))`
- **Register/update a Malli schema or context policy**:
  `(user/register-schema! :seon.foo/entity [:map {:seon.db/attributes true :seon.schema/context-policy :default} ...] {:doc "..."})`
