(ns seon.agent.ctx.menu-test
  "Behavior tests for the typeahead menu block family
   (`seon.agent.ctx.menu` — diffusion-typeahead P3a).

   Covers: empty-history suppression (the section returns \"\" and
   vanishes), the function-menu derivation (eval-log ranking, alias
   resolution via stored require-edges, private/failed-eval exclusion,
   glyph numbering), and the `:seon.typeahead/policy` override row
   (menu-cap respected). The former `:plan-ledger` behaviors (▶ active
   first, ☐ open, done dropped) live on `my.plan.internal/plan-block`
   now — tested in `my.plan-test`.

   Fresh `:memory` conn seeded like the pod boots, set! as the root
   db/*conn* so `db/transact!` targets it (lazy-installs the domain
   schema); the section fn is a PURE read of the passed db value."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent.ctx.menu :as menu]
    [seon.client :as client]
    [seon.db :as db]))

(def ^:private a-id "menutestagentA")   ; 14 chars — the :seon.db/id shape

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + one agent."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data (into (db/malli->datahike-schema
                                                  client/agent-bootstrap-attrs)
                                                (db/tx-meta-datahike-schema))})))
                     (.then (fn [_]
                              (d/transact! conn {:tx-data [{:seon.agent/id a-id}]})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh seeded conn set! as the root db/*conn* for `body` (conn → Promise),
   prior root restored after — root set!, not binding (CLJS dynamic bindings
   pop at the first await boundary)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- ok!
  "Assert a `db/transact!` envelope succeeded (shared seed helper)."
  [{ok? :seon.db/ok? err :seon.db/error}]
  (is (true? ok?) (str "seed transacted — " (pr-str err))))

;; The function-menu seed: two public program-graph fns + one private one,
;; a home ns whose STORED require-edges alias `plan` → `my.plan`, and
;; four eval rows — done! called twice (once aliased), step! once, the
;; private fn once, plus a FAILED eval calling drop! (must not rank).
(defn- seed-functions!
  []
  (db/transact!
    {:seon.db/tx-data
     [{:seon.fn/sym      "my.plan/done!"
       :seon.fn/fn-var?  true
       :seon.fn/arglists "([{:my.plan/keys [id]}])"
       :seon.fn/doc      "Mark a step done; may unblock its dependents next turn.\n\n   Longer prose that must NOT render in the menu."}
      {:seon.fn/sym      "my.plan/step!"
       :seon.fn/fn-var?  true
       :seon.fn/arglists "([request])"
       :seon.fn/doc      "Mint one OPEN plan step (agent = caller; blank title refused)."}
      {:seon.fn/sym      "my.plan/secret-helper"
       :seon.fn/fn-var?  true
       :seon.fn/private? true
       :seon.fn/arglists "([x])"}
      {:seon.fn/sym      "my.plan/drop!"
       :seon.fn/fn-var?  true
       :seon.fn/arglists "([{:my.plan/keys [id]}])"
       :seon.fn/doc      "Retract a step AND its whole subtree."}
      {:seon.ns/name :my.agent.menutestagent
       :seon.ns/require-edges
       [{:seon.ns.require/target :my.plan
         :seon.ns.require/alias  'plan}]}
      {:seon.eval/agent  [:seon.agent/id a-id]
       :seon.eval/at     (js/Date. 1000)
       :seon.eval/ok?    true
       :seon.eval/ns     :my.agent.menutestagent
       :seon.eval/source "(my.plan/step! {:my.plan/title \"a\"})"}
      {:seon.eval/agent  [:seon.agent/id a-id]
       :seon.eval/at     (js/Date. 2000)
       :seon.eval/ok?    true
       :seon.eval/ns     :my.agent.menutestagent
       :seon.eval/source "(plan/done! {:my.plan/id \"x\"})"}
      {:seon.eval/agent  [:seon.agent/id a-id]
       :seon.eval/at     (js/Date. 3000)
       :seon.eval/ok?    true
       :seon.eval/ns     :my.agent.menutestagent
       :seon.eval/source "(plan/done! {:my.plan/id \"y\"})\n(my.plan/secret-helper 1)"}
      {:seon.eval/agent  [:seon.agent/id a-id]
       :seon.eval/at     (js/Date. 4000)
       :seon.eval/ok?    false
       :seon.eval/ns     :my.agent.menutestagent
       :seon.eval/source "(my.plan/drop! {:my.plan/id \"nope\"})"}]}))

(deftest empty-history-menu-vanishes
  ;; A fresh store has no eval log — the section must return "" (the
  ;; composer drops it; the reactive vanish costs zero).
  (async done
    (-> (with-conn
          (fn [conn]
            (let [req {:seon.db/db @conn :seon.agent/id a-id}]
              (is (= "" (menu/function-menu-block req))
                  "no eval history → no function-menu section"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest function-menu-ranked-glyph-numbered-and-filtered
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-functions!)
                (.then
                  (fn [env]
                    (ok! env)
                    (let [out (menu/function-menu-block
                                {:seon.db/db @conn :seon.agent/id a-id})]
                      (is (str/includes? out "select an entry by outputting its glyph")
                          "the optionality teaching is colocated with the block")
                      (is (str/includes? out "① (my.plan/done! [{:my.plan/keys [id]}] …)")
                          "most-called fn is glyph ①, aliased calls resolved via stored require-edges")
                      (is (str/includes? out "② (my.plan/step! [request] …)")
                          "second-ranked fn is glyph ②")
                      (is (str/includes?
                            out "Mark a step done; may unblock its dependents next turn.")
                          "docstring line 1 renders on the entry")
                      (is (not (str/includes? out "Longer prose"))
                          "only docstring line 1 renders — never the body prose")
                      (is (not (str/includes? out "secret-helper"))
                          "a private fn never becomes a menu entry")
                      (is (not (str/includes? out "drop!"))
                          "a fn seen only in a FAILED eval never ranks"))))))
          )
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; The toolkit-group seed (P6): the agent's HOME ns (`my.agent.<id>` —
;; current-ns falls back to it when no turn-linked eval exists) requires
;; my.plan; my.plan carries one SPECCED public fn the agent never called
;; (plan!), one specced fn it did call (done! — must dedup against the
;; recency group), and one spec-LESS public fn (never a toolkit entry).
(defn- seed-toolkit!
  []
  (db/transact!
    {:seon.db/tx-data
     [{:seon.ns/name :my.plan}
      {:seon.ns/name (keyword (str "my.agent." a-id))
       :seon.ns/require-edges
       [{:seon.ns.require/target :my.plan
         :seon.ns.require/alias  'plan}]}
      {:seon.fn/sym      "my.plan/plan!"
       :seon.fn/fn-var?  true
       :seon.fn/ns       [:seon.ns/name :my.plan]
       :seon.fn/spec     "[:=> [:cat :my.plan/plan-request] :my.plan/plan-response]"
       :seon.fn/arglists "([request])"
       :seon.fn/doc      "Author a WHOLE plan in ONE transact — goal, pace, steps."}
      {:seon.fn/sym     "my.plan/done!"        ; upsert: spec + ns onto the
       :seon.fn/ns      [:seon.ns/name :my.plan] ; recency-seeded row
       :seon.fn/spec    "[:=> [:cat :my.plan/id-request] :my.plan/write-response]"}
      {:seon.fn/sym      "my.plan/no-spec-fn"
       :seon.fn/fn-var?  true
       :seon.fn/ns       [:seon.ns/name :my.plan]
       :seon.fn/arglists "([x])"
       :seon.fn/doc      "Public but unspecced — not a toolkit entry."}]}))

(deftest toolkit-group-one-numbering-dedup-and-cap
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-functions!)
                (.then (fn [env] (ok! env) (seed-toolkit!)))
                (.then
                  (fn [env]
                    (ok! env)
                    (let [req {:seon.db/db @conn :seon.agent/id a-id}
                          out (menu/function-menu-block req)
                          offers (menu/function-offers @conn a-id)]
                      (is (str/includes? out "① (my.plan/done!")
                          "recency group still leads the numbering")
                      (is (str/includes? out "; toolkit — more functions")
                          "the toolkit group renders under its divider")
                      (is (str/includes? out "③ (my.plan/plan! [request] …)")
                          "an uncalled specced toolkit function gets the NEXT glyph (one numbering)")
                      (is (= 1 (count (re-seq #"my\.plan/done!" out)))
                          "a function already in the recency group is never duplicated")
                      (is (not (str/includes? out "no-spec-fn"))
                          "a spec-less public fn is not a toolkit entry")
                      (is (= ["my.plan/done!" "my.plan/step!" "my.plan/plan!"]
                             (mapv #(first (str/split (:seon.typeahead/label %) #" "))
                                   offers))
                          "wire offers mirror the rendered concatenation")
                      (is (= ["①" "②" "③"]
                             (mapv :seon.typeahead/glyph offers))
                          "offer glyphs are the one continuous numbering"))))
                (.then (fn [_]
                         (db/transact!
                           {:seon.db/tx-data
                            [{:seon.typeahead/id          "policy"
                              :seon.typeahead/toolkit-cap 0}]})))
                (.then
                  (fn [env]
                    (ok! env)
                    (let [out (menu/function-menu-block
                                {:seon.db/db @conn :seon.agent/id a-id})]
                      (is (not (str/includes? out "toolkit"))
                          "toolkit-cap 0 removes the toolkit group")
                      (is (not (str/includes? out "plan!"))
                          "…and its entries")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest policy-row-menu-cap-overrides-the-menu
  ;; The [:seon.typeahead/id "policy"] singleton row overrides the code
  ;; default per knob; menu-cap 1 truncates the function menu to ONE glyph.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-functions!)
                (.then (fn [env]
                         (ok! env)
                         (db/transact!
                           {:seon.db/tx-data
                            [{:seon.typeahead/id       "policy"
                              :seon.typeahead/menu-cap 1}]})))
                (.then
                  (fn [env]
                    (ok! env)
                    (let [req  {:seon.db/db @conn :seon.agent/id a-id}
                          out  (menu/function-menu-block req)]
                      (is (= 1 (:seon.typeahead/menu-cap (menu/policy @conn)))
                          "the policy row overrides the code default")
                      (is (str/includes? out "① (my.plan/done!")
                          "the top function still renders")
                      (is (not (str/includes? out "②"))
                          "menu-cap 1 → no second menu entry"))))))
          )
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
