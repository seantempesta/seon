;; review-dhfork2-opus counterexamples: hand-answered shapes, planner vs base. From the seon root, in a fork JVM:
;; java -cp FORK-CP clojure.main -e "(load-file \"docs/research/agent-platform/2026-10-07/dh-fix/review-dhfork2-opus-cases.clj\")"
(ns cx (:require [datahike.api :as d] [datahike.db :as db] [datahike.query :as q] [datahike.lru :as lru]))
(load-file "docs/research/agent-platform/2026-10-07/dh-fix/review-dhfork2-opus-matrix-lib.clj")
(defn run1 [database query args dis]
  (vreset! @#'q/plan-cache (lru/lru 100))
  (let [f (future (try (binding [q/*disable-planner* dis q/*query-result-cache?* false]
                         (let [r (apply d/q query database args)] (if (and (coll? r) (not (map? r))) (set r) r)))
                       (catch Throwable e (str "ERR " (.getMessage e)))))
        r (deref f 15000 ::timeout)]
    (when (= r ::timeout) (future-cancel f)) r))
(defn both [database query & args]
  (let [p (run1 database query args false) b (run1 database query args true)]
    {:same? (= p b) :planner p :base b}))
(def W (into {} (worlds 40)))
(def small (d/db-with (db/empty-db schema {:keep-history? true})
  [{:db/id 1 :p/id "a" :p/team :t1 :p/score 1 :p/age 1 :p/nums [1 2] :p/tags ["x"]}
   {:db/id 2 :p/id "b" :p/team :t1 :p/score 2 :p/age 5 :p/nums [3] :p/friend 1 :p/tags ["y" "z"]}
   {:db/id 3 :p/id "c" :p/team :t0 :p/score 3 :p/age 3 :p/nums [3 4] :p/friend 3}
   {:db/id 4 :p/id "d" :p/team :t0 :p/score 4 :p/friend 2 :p/nums [9]}]))
(in-ns 'cx)
(def fr-req '[[(fr [?e] ?x) [?e :p/friend ?x]]])
(def selfie '[[(selfie ?x) [?x :p/friend ?x]] [(friend-of ?x ?y) [?x :p/friend ?y]] [(same ?x ?y) [(= ?x ?y)]]])
(def rgroup '[[(r ?a ?b) [?a :p/nums ?v] [?a :p/id ?i] [?a :p/score ?v] [?a :p/friend ?b]] [(r ?a ?b) (r ?a ?c) [?c :p/friend ?b]]])
(def ngroup '[[(g ?a ?b) [?a :p/nums ?v] [?a :p/id ?i] [?a :p/score ?v] [?a :p/friend ?b]]])
(def nf '[[(nf ?e) [?x :p/id "a"] (not [?e :p/friend ?x])] [(edge ?e ?x) [?x :p/friend ?e]]])
(def cases1
  [[:A1-getelse-bound #{[1] [3]} '[:find ?e :where [?e :p/team ?t] [?e :p/score ?s] [(get-else $ ?e :p/age -1) ?s]]]
   [:A2-getelse-bound-id #{[1] [3]} '[:find ?e :where [?e :p/id ?i] [?e :p/score ?s] [(get-else $ ?e :p/age -1) ?s]]]
   [:A3-getelse-bound-many #{[1]} '[:find ?e :where [?e :p/team :t1] [?e :p/nums ?s] [(get-else $ ?e :p/age -1) ?s]]]
   [:A4-getelse-twice #{[1] [3] [4]} '[:find ?e :where [?e :p/id ?i] [(get-else $ ?e :p/age -1) ?s] [(get-else $ ?e :p/score -1) ?s2] [(get-else $ ?e :p/age ?s2) ?s]]]
   [:B1-anti-outer-var #{[4]} '[:find ?e :in $ ?id :where [?e :p/team :t0] [?x :p/id ?id] [?x :p/score ?s] (not [?e :p/score ?s])] "c"]
   [:B2-anti-input-coll #{[4]} '[:find ?e :in $ [?s ...] :where [?e :p/team :t0] (not [?e :p/score ?s])] [3]]
   [:B3-anti-outer-group #{} '[:find ?e :where [?x :p/id "c"] [?x :p/score ?s] [?e :p/team :t0] [?e :p/age ?a] (not [?e :p/score ?s])]]
   [:B4-anti-outer-group-age #{[3]} '[:find ?e :where [?x :p/id "a"] [?x :p/score ?s] [?e :p/team :t0] [?e :p/age ?a] (not [?e :p/score ?s])]]
   [:C1-not-self #{[1] [2] [4]} '[:find ?a :where [?a :p/id] (not [?a :p/friend ?a])]]
   [:C2-or-self #{[1] [3]} '[:find ?a :where [?a :p/id] (or [?a :p/friend ?a] [?a :p/score 1])]]
   [:C3-notjoin-self #{[1] [2] [4]} '[:find ?a :where [?a :p/id] (not-join [?a] [?a :p/friend ?a])]]
   [:C4-orjoin-self #{[1] [3]} '[:find ?a :where [?a :p/id] (or-join [?a] [?a :p/friend ?a] [?a :p/score 1])]]
   [:C5-rule-self #{[3]} '[:find ?a :in $ % :where (selfie ?a)] selfie]
   [:C6-rule-call-repeat #{[3]} '[:find ?a :in $ % :where (friend-of ?a ?a)] selfie]
   [:C6b-rule-call-repeat-bound #{[3]} '[:find ?a :in $ % :where [?a :p/id] (friend-of ?a ?a)] selfie]
   [:C6c-same-repeat #{[1] [2] [3] [4]} '[:find ?a :in $ % :where [?a :p/id] (same ?a ?a)] selfie]
   [:C7-self-target #{[3 3]} '[:find ?a ?b :where [?a :p/friend ?b] [?b :p/friend ?b]]]
   [:C8-tuple-repeat-diff #{} '[:find ?x :where [(vector 1 2) [?x ?x]]]]
   [:C8b-tuple-repeat-same #{[1]} '[:find ?x :where [(vector 1 1) [?x ?x]]]]
   [:C9-input-rel-repeat #{[1]} '[:find ?a :in $ [[?a ?a]] :where [?a :p/id]] [[1 1] [1 2]]]
   [:C10-count-self 1 '[:find (count ?a) . :where [?a :p/friend ?a]]]
   [:C12-self-group #{[3]} '[:find ?a :where [?a :p/friend ?a] [?a :p/score ?s]]]
   [:C13-history-repeat-in-not #{[1] [2] [4]} '[:find ?a :where [?a :p/id] (not [?a :p/friend ?b] [?b :p/friend ?b])]]
   [:C14-pull-self #{[{:p/id "c"}]} '[:find (pull ?a [:p/id]) :where [?a :p/friend ?a]]]
   [:C15-fn-out-bound-other-rel #{[1 1] [2 2] [3 3] [4 4] } '[:find ?e ?x :where [?e :p/score ?n] [?x :p/score ?v] [(identity ?n) ?v]]]
   [:C16-fn-out-bound-not #{[2] [4]} '[:find ?e :where [?e :p/score ?n] (not [?e :p/age ?a] [(identity ?n) ?a])]]
   [:D1-or-group #{[1] [3]} '[:find ?e :where [?e :p/team ?t] (or (and [?e :p/nums ?v] [?e :p/id ?i] [?e :p/score ?v]) [?e :p/age 99])]]
   [:D2-not-group #{[2] [4]} '[:find ?e :where [?e :p/team ?t] (not [?e :p/nums ?v] [?e :p/id ?i] [?e :p/score ?v])]]
   [:D3-rec-group #{[3 3]} '[:find ?a ?b :in $ % :where (r ?a ?b)] rgroup]
   [:D4-nonrec-group #{[3 3]} '[:find ?a ?b :in $ % :where (g ?a ?b)] ngroup]
   [:D5-three-shared #{[1]} '[:find ?e :where [?e :p/nums ?v] [?e :p/team ?t] [?e :p/score ?v] [?e :p/age ?v]]]
   [:D6-shared-v-tx #{} '[:find ?e :where [?e :p/score ?v] [?e :p/age ?w ?v]]]
   [:E1-req-rule-lit #{[1]} '[:find ?x :in $ % :where (fr [:p/id "b"] ?x)] fr-req]
   [:E1b-req-rule-input #{[1]} '[:find ?x :in $ % ?e :where (fr ?e ?x)] fr-req [:p/id "b"]]
   [:E1c-req-rule-coll #{[1] [2]} '[:find ?x :in $ % [?e ...] :where (fr ?e ?x)] fr-req [[:p/id "b"] [:p/id "d"]]]
   [:E2-getelse-lookup 5 '[:find ?v . :in $ ?e :where [(get-else $ ?e :p/age -1) ?v]] [:p/id "b"]]
   [:E3-missing-lookup #{[1] [2] [3] [4]} '[:find ?x :in $ ?e :where [?x :p/id] [(missing? $ ?e :p/friend)]] [:p/id "a"]]
   [:E4-pull-only-lookup #{["a" {:p/age 5}]} '[:find ?i (pull ?e [:p/age]) :in $ ?e :where [?x :p/id "a"] [?x :p/id ?i]] [:p/id "b"]]
   [:E6-attr-var-lookup :? '[:find ?e :in $ ?v :where [?e ?a ?v]] [:p/id "a"]]
   [:E7-orjoin-lookup #{[1] [4]} '[:find ?x :in $ ?e :where (or-join [?e ?x] [?e :p/friend ?x] [?x :p/friend ?e])] [:p/id "b"]]
   [:E8-rule-refv-coll #{[2]} '[:find ?x :in $ % [?e ...] :where (edge ?e ?x)] nf [[:p/id "a"]]]
   [:E9-lookup-find :? '[:find ?e ?x :in $ [?e ...] :where [?e :p/friend ?x]] [[:p/id "b"] [:p/id "c"]]]
   [:E10-rule-not-entity #{[1]} '[:find ?x :in $ % :where (nf [:p/id "c"]) [?x :p/id "a"]] nf]
   [:E10b-rule-not-entity #{} '[:find ?x :in $ % :where (nf [:p/id "b"]) [?x :p/id "a"]] nf]
   [:E10c-rule-not-entity-in #{} '[:find ?x :in $ % ?e :where (nf ?e) [?x :p/id "a"]] nf [:p/id "b"]]
   [:E11-getelse-lookup-coll #{[5] [-1]} '[:find ?v :in $ [?e ...] :where [(get-else $ ?e :p/age -1) ?v]] [[:p/id "b"] [:p/id "d"]]]
   [:E12-pred-lookup-and-entity #{} '[:find ?e :in $ ?e :where [?e :p/id "a"] [(vector? ?e)]] [:p/id "a"]]])
(doseq [[label expect query & args] cases1]
  (let [r (apply both small query args)]
    (prn label (cond (= :? expect) (if (:same? r) :same :DIFF)
                     (and (:same? r) (= expect (:planner r))) :ok
                     :else :BAD)
         (dissoc r :same?))))
