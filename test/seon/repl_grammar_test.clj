(ns seon.repl-grammar-test
  (:require [clojure.edn :as edn]
            [clojure.test.check :as check]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.repl :as repl]
            [seon.test-support :as support]))

(deftest shown-value-diffs-are-deterministic-readable-snapshot-paths
  (support/assert-check!
   (check/quick-check
    100
    (prop/for-all [before gen/any-printable-equatable
                  after gen/any-printable-equatable]
      (let [request {:seon.db.diff/before before :seon.db.diff/after after}
            changes (db/diff request)
            text (pr-str changes)]
        (and (= after (db/apply-diff before changes))
             (= changes (edn/read-string text))
             (= text (pr-str (db/diff request))))))
    :seed 20260914)))

(deftest prompt-precedes-exact-multiline-agent-input
  (let [emission {:seon.ns/name 'my.agents.juniper
                  :seon.cluster.eval/comment ";; I should inspect the entities.\n;; Then I can choose."
                  :seon.cluster.eval/source "(->> entities\n     (map :total))"
                  :seon.eval/shown "[40 60]"}
        expected ";; I should inspect the entities.\n;; Then I can choose.\n(->> entities\n     (map :total))\n#:seon.repl{:value [40 60]}"
        html (repl/render-html emission)]
    (is (= expected (repl/text emission)))
    ;; The HTML pair gives the agent's comment its own block (`input-text`).
    (is (= ";; I should inspect the entities.\n;; Then I can choose." (get-in html [2 2])))
    (is (= "(->> entities\n     (map :total))"
           (get-in html [3 1 2])))))

(deftest changed-read-marker-does-not-rewrite-agent-input
  (let [emission {:seon.ns/name 'my.agent
                  :seon.cluster.eval/comment ";; Read the current value."
                  :seon.cluster.eval/source "(my.task/current {})"
                  :seon.eval/shown "{}"}
        original (repl/text emission)
        changed (assoc emission :seon.repl/changed-since? true)]
    (is (= ";; Read the current value.\n(my.task/current {})\n#:seon.repl{:value {}}"
           original))
    (is (= ";; changed since your last turn\n(my.task/current {})\n#:seon.repl{:value {}}"
           (repl/text changed)))
    (is (= original (repl/text emission)))))
