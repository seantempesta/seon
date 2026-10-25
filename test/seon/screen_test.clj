(ns seon.screen-test
  "The screen (seon.screen): each screen eval is sent as its diff from the one before it of the
  same form (the owner's ruling, docs/render.md#the-screen-is-sent-as-diffs)."
  (:require [clojure.test :refer [deftest is]] [seon.screen :as screen] [datahike.api :as d] [seon.test-store :as ts]))

(deftest a-diff-names-what-is-unchanged
  ;; task seon.screen/the-screen-is-sent-as-diffs
  (let [before {:t 10 :page [[:div "task"] [:div "proofs red"] [:div "changes"]] :status {:turn 1 :proofs {'a/t :fail 'a/u :pass}}}
        after {:t 11 :page [[:div "task"] [:div "proofs green"] [:div "changes"]] :status {:turn 2 :proofs {'a/t :pass 'a/u :pass}}}]
    (is (= {:changed {[:t] 11 [:page 1] [:div "proofs green"] [:status :proofs 'a/t] :pass [:status :turn] 2}
            :unchanged [[:page 0] [:page 2] [:status :proofs 'a/u]]}
           (screen/diff before after))
        "a map key by key, a page's tiles one by one, each tile whole; the unchanged named by path")
    (is (= {:unchanged [[]]} (screen/diff before before)) "an equal screen: nothing but its name")
    (is (= {:changed {[] :green}} (screen/diff :red :green)) "a value that is no collection, whole")
    (is (= {:changed {[:page] [[:div "task"]]}} (screen/diff {:page [[:div "a"] [:div "b"]]} {:page [[:div "task"]]}))
        "a vector of another count, whole")
    (is (= {:removed [[:gone]] :unchanged [[:kept]]} (screen/diff {:gone 1 :kept 2} {:kept 2})) "a key the screen dropped")))

(deftest show-since-reads-the-screen-at-t
  ;; (seon.screen/show {:since t}) diffs the page from the agent's screen eval at t: the newest of
  ;; its form at t or before, on its branch
  (let [ev (fn [id code v] {:seon.eval/id id :seon.eval/by "screen" :seon.eval/agent {:seon.agent/key "b/a1"}
                            :seon.eval/record {:code code :response {:handle (symbol "result" id) :value v}}})
        db (ts/test-db)
        db (d/db-with db [(ev "s1" "(seon.screen/show)" {:page 1})])
        t1 (:max-tx db)
        db (d/db-with db [(ev "s2" "(seon.screen/show)" {:page 2}) (ev "x2" "(my.task/status)" {:page 9})])
        last-screen #'screen/last-screen]
    (is (= {:page 1} (last-screen db "b/a1" "(seon.screen/show)" t1)) "the newest at t, not a later one")
    (is (= {:page 2} (last-screen db "b/a1" "(seon.screen/show)" (:max-tx db))) "and of its own form")
    (is (= ::screen/none (last-screen db "b/a1" "(seon.screen/show)" 0)) "none before: whole")
    (is (= ::screen/none (last-screen db "b/a2" "(seon.screen/show)" (:max-tx db))) "another agent's are not its")))
