(ns seon.agent.ctx.menu-test
  "Behavior tests for the typeahead menu block family
   (`seon.agent.ctx.menu` — diffusion-typeahead P3a).

   Covers: empty-world suppression (both sections return \"\" and vanish),
   the recent-verbs derivation (eval-log ranking, alias resolution via
   stored require-edges, private/failed-eval exclusion, glyph numbering),
   the plan ledger (active ▶ first, open ☐ oldest-first, DONE items
   absent from the render), and the `:seon.typeahead/policy` override
   row (menu-cap respected by both sections).

   Fresh `:memory` conn seeded like the pod boots, set! as the root
   db/*conn* so `db/transact!` targets it (lazy-installs the domain
   schema); both section fns are PURE reads of the passed db value."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent.ctx.menu :as menu]
    [seon.client :as client]
    [seon.db :as db]
    [my.plan]))   ; load-order: registers :my.plan/* attr schemas

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
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         client/agent-bootstrap-attrs)
                                       (db/tx-meta-datahike-schema))})
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

;; The recent-verbs seed: two public program-graph fns + one private one,
;; a home ns whose STORED require-edges alias `plan` → `my.plan`, and
;; four eval rows — done! called twice (once aliased), step! once, the
;; private fn once, plus a FAILED eval calling drop! (must not rank).
(defn- seed-verbs!
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

;; The plan-ledger seed: one :active, two :open, one :done, one :blocked.
(defn- seed-plan!
  []
  (db/transact!
    {:seon.db/tx-data
     [{:my.plan/id "menuledgerstA1" :my.plan/title "design the schema"
       :my.plan/status :open :my.plan/agent [:seon.agent/id a-id]
       :my.plan/created-at (js/Date. 1000)}
      {:my.plan/id "menuledgerstB2" :my.plan/title "store the seed rows"
       :my.plan/status :active :my.plan/agent [:seon.agent/id a-id]
       :my.plan/created-at (js/Date. 2000)}
      {:my.plan/id "menuledgerstC3" :my.plan/title "render the summary tile"
       :my.plan/status :open :my.plan/agent [:seon.agent/id a-id]
       :my.plan/created-at (js/Date. 3000)}
      {:my.plan/id "menuledgerstD4" :my.plan/title "already shipped setup"
       :my.plan/status :done :my.plan/agent [:seon.agent/id a-id]
       :my.plan/created-at (js/Date. 500)
       :my.plan/completed-at (js/Date. 900)}
      {:my.plan/id "menuledgerstE5" :my.plan/title "waiting on the human"
       :my.plan/status :blocked :my.plan/agent [:seon.agent/id a-id]
       :my.plan/created-at (js/Date. 400)}]}))

(deftest empty-world-both-sections-vanish
  ;; A fresh world has no eval log and no plan — both sections must return
  ;; "" (the composer drops them; the reactive vanish costs zero).
  (async done
    (-> (with-conn
          (fn [conn]
            (let [req {:seon.db/db @conn :seon.agent/id a-id}]
              (is (= "" (menu/recent-verbs-block req))
                  "no eval history → no recent-verbs section")
              (is (= "" (menu/plan-ledger-block req))
                  "no plan steps → no plan-ledger section"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest recent-verbs-ranked-glyph-numbered-and-filtered
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-verbs!)
                (.then
                  (fn [env]
                    (ok! env)
                    (let [out (menu/recent-verbs-block
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

(deftest plan-ledger-current-first-open-next-done-absent
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-plan!)
                (.then
                  (fn [env]
                    (ok! env)
                    (let [out (menu/plan-ledger-block
                                {:seon.db/db @conn :seon.agent/id a-id})]
                      (is (str/includes? out "outputting its glyph alone")
                          "the optionality teaching is colocated with the block")
                      (is (str/includes? out "; ▶ ① store the seed rows")
                          "the :active step renders first as ▶ ①")
                      (is (str/includes? out "; ☐ ② design the schema")
                          "open steps follow oldest-first as ☐")
                      (is (str/includes? out "; ☐ ③ render the summary tile")
                          "…numbered in render order")
                      (is (not (str/includes? out "already shipped setup"))
                          "a DONE step is dropped from the render entirely")
                      (is (not (str/includes? out "waiting on the human"))
                          "a blocked step is not selectable work — off the menu"))))))
          )
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest policy-row-menu-cap-overrides-both-sections
  ;; The [:seon.typeahead/id "policy"] singleton row overrides the code
  ;; default per knob; menu-cap 1 truncates both menus to ONE glyph.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (seed-verbs!)
                (.then (fn [env] (ok! env) (seed-plan!)))
                (.then (fn [env]
                         (ok! env)
                         (db/transact!
                           {:seon.db/tx-data
                            [{:seon.typeahead/id       "policy"
                              :seon.typeahead/menu-cap 1}]})))
                (.then
                  (fn [env]
                    (ok! env)
                    (let [req   {:seon.db/db @conn :seon.agent/id a-id}
                          verbs (menu/recent-verbs-block req)
                          plan  (menu/plan-ledger-block req)]
                      (is (= 1 (:seon.typeahead/menu-cap (menu/policy @conn)))
                          "the policy row overrides the code default")
                      (is (str/includes? verbs "① (my.plan/done!")
                          "the top verb still renders")
                      (is (not (str/includes? verbs "②"))
                          "menu-cap 1 → no second verb entry")
                      (is (str/includes? plan "▶ ① store the seed rows")
                          "the active step still renders")
                      (is (not (str/includes? plan "②"))
                          "menu-cap 1 → no second ledger entry")
                      (is (str/includes? plan "and 2 more open")
                          "the overflow renders one honest … and N more line"))))))
          )
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
