(ns probe-sci-fork-parallel
  "Surface: `sci/fork` as the platform's per-turn / per-environment isolation
   mechanism, exercised CONCURRENTLY rather than serially.

   The copy-on-write fork claim was verified serially on 2026-08-04. This probe
   checks the parallel case: N forks of one base context each defining and
   mutating the same symbols at the same time, then asserting that no fork sees
   another fork's value and that the parent context is unchanged."
  (:require [sci.core :as sci]))

(set! *warn-on-reflection* true)

(defn run
  "Fork one base context N times in parallel and check for definition leakage."
  [{:keys [forks iterations] :or {forks 16 iterations 200}}]
  (let [base (sci/init {:namespaces {'user {}}})
        _ (sci/eval-string* base "(def shared :base)")
        wrong (atom [])
        fork-body
        (fn [index]
          (fn []
            (let [ctx (sci/fork base)
                  mine (keyword (str "fork-" index))]
              (dotimes [i iterations]
                (sci/eval-string* ctx (str "(def shared " mine ")"))
                (sci/eval-string* ctx (str "(def only-" index " " mine ")"))
                (let [seen (sci/eval-string* ctx "shared")]
                  (when-not (= seen mine)
                    (swap! wrong conj {:probe/fork index :probe/iteration i
                                       :probe/saw seen
                                       :probe/expected mine})))))))
        _ (run! deref (mapv #(future ((fork-body %))) (range forks)))
        parent-value (sci/eval-string* base "shared")
        parent-leak
        (try (sci/eval-string* base "only-0")
             (catch Throwable _ :probe/absent))]
    {:probe/name 'probe-sci-fork-parallel
     :probe/surface "sci/fork copy-on-write Vars under parallel forks"
     :probe/verdict (if (and (empty? @wrong)
                             (= :base parent-value)
                             (= :probe/absent parent-leak))
                      :pass :fail)
     :probe/forks forks
     :probe/violations (count @wrong)
     :probe/first-violations (vec (take 5 @wrong))
     :probe/parent-shared parent-value
     :probe/parent-sees-fork-definition parent-leak}))
