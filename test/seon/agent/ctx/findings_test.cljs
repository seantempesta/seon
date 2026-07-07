(ns seon.agent.ctx.findings-test
  "Behavior test for the `:findings` context block
   (`seon.agent.ctx.findings/findings-block`).

   The findings section surfaces accumulated KNOWLEDGE (`my.kb` facts an
   agent consults before re-researching), NOT work-tracking. A live drive
   caught an `:open` `my.plan` step — titled in perfect tense (\"User has
   been told the value\") — rendering byte-identically to a settled fact
   under the \"stored findings — your accumulated knowledge\" header, which
   an agent then read as work already done. This pins the fix: a row
   carrying a lifecycle STATUS (`:my.plan/status`) is work-tracking and is
   swept OUT of findings; a genuine `my.kb` fact still renders.

   Fresh `:memory` conn seeded like the pod boots, set! as the root
   db/*conn* so `db/transact!` targets it (lazy-installs the domain schema);
   findings-block itself is a PURE read of the passed db value."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.agent.ctx.findings :as findings]
    [seon.client :as client]
    [seon.db :as db]
    [my.kb]      ; load-order: registers :my.kb/* attr schemas
    [my.plan]))  ; load-order: registers :my.plan/* attr schemas

(def ^:private a-id "findtestagentA")

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

(deftest open-plan-row-never-renders-as-a-settled-finding
  ;; The reproducing shape: an :open plan step whose title reads like a
  ;; completed fact, alongside a REAL my.kb fact. The plan row must NOT
  ;; appear in findings (it is work-tracking with a lifecycle, rendered by
  ;; the plan section); the kb fact MUST appear (it is settled knowledge).
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:my.kb/claim       "The sum of 1..100 is 5050."
                     :my.kb/source-path "scratch/gauss.md"
                     :my.kb/source-line 1}
                    {:my.plan/id         "findtestplan01"
                     :my.plan/title      "User has been told the exact sum value"
                     :my.plan/status     :open
                     :my.plan/agent      [:seon.agent/id a-id]
                     :my.plan/created-at (js/Date.)}]})
                (.then
                  (fn [{ok? :seon.db/ok? err :seon.db/error}]
                    (is (true? ok?) (str "seed transacted — " err))
                    (let [out (findings/findings-block {:seon.db/db @conn})]
                      (is (str/includes? out "The sum of 1..100 is 5050.")
                          "the genuine my.kb fact renders as a finding")
                      (is (str/includes? out "scratch/gauss.md")
                          "its :my.kb/source-* provenance renders")
                      (is (not (str/includes?
                                 out "User has been told the exact sum value"))
                          "the :open plan row is swept OUT — never reads as settled")
                      (is (not (str/includes? out "findtestplan01"))
                          "no trace of the work-tracking row's id either")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest done-plan-row-also-excluded-work-tracking-is-not-knowledge
  ;; A :done plan row carries the SAME lifecycle-status attr — it too is
  ;; work-tracking (the plan done-section recalls it), so it is excluded from
  ;; findings. Pins the discriminator as "carries a lifecycle status",
  ;; independent of the status VALUE (no terminal-value name list).
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:my.plan/id         "findtestplan02"
                     :my.plan/title      "compute the sum"
                     :my.plan/status     :done
                     :my.plan/agent      [:seon.agent/id a-id]
                     :my.plan/created-at (js/Date.)}]})
                (.then
                  (fn [{ok? :seon.db/ok?}]
                    (is (true? ok?))
                    (let [out (findings/findings-block {:seon.db/db @conn})]
                      (is (= "" out)
                          "a store of ONLY plan rows yields no findings — section vanishes")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
