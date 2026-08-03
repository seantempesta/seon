(ns seon.bootstrap-drive-test
  "The maintained bootstrap drive, ending-commit fork, and O1 grader."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.ai :as ai]
            [seon.bootstrap-drive :as drive]))

(def ^:private contracted-o1-reply
  (str
   "(defn total-by-label\n"
   "  \"Total each label's amounts.\"\n"
   "  {:malli/schema [:=> [:cat [:sequential [:map {:closed true} [:label :string] [:amount :int]]]]\n"
   "                  [:map-of :string :int]]}\n"
   "  [rows]\n"
   "  (reduce (fn [totals {:keys [label amount]}]\n"
   "            (update totals label (fnil + 0) amount))\n"
   "          {} rows))\n"
   "(total-by-label [{:label \"user\" :amount 0}\n"
   "                 {:label \"user\" :amount 1}\n"
   "                 {:label \"agent\" :amount 77}])\n"
   "(my.run/complete \"{\\\"user\\\" 1, \\\"agent\\\" 77}\")"))

(deftest objective-catalog-is-the-five-ruled-fact-space-cases
  (is (= #{:o1 :o2 :o3 :o4 :o5} (set (keys drive/objectives))))
  (is (= 2 (get-in drive/objectives [:o4 :seon.bootstrap-drive/agents])))
  (is (every? #(not-empty (:seon.bootstrap-drive/objective %))
              (vals drive/objectives))))

(deftest ^{:seon.test/long "Boots a real cluster graph and grades its ending commit."}
  one-fake-o1-drive-grades-on-its-ending-commit
  (testing "the real graph executes bootstrap, objective, fork, and held-out call"
    (let [run-drives! (ns-resolve 'seon.bootstrap-drive 'run-drives!)
          reports
          (with-redefs [ai/complete
                        (fn [_]
                          {:seon.ai/text contracted-o1-reply})]
            (run-drives!
             {:seon.bootstrap-drive/objective :o1
              :seon.bootstrap-drive/runs 1
              :seon.bootstrap-drive/run-cap 2
              :seon.bootstrap-drive/remote-timeout-ms 120000}))
          report (first reports)]
      (is (= 1 (count reports)))
      (is (= :completed
             (get-in report [:seon.bootstrap-drive/terminal
                             :seon.bootstrap-drive/outcome])))
      (is (= {:p1a true :p1b true :p1c true}
             (select-keys (:seon.bootstrap-drive/grade report)
                          [:p1a :p1b :p1c])))
      (is (= "deepseek-v4-flash"
             (:seon.bootstrap-drive/model report)))
      (is (= :disabled (:seon.bootstrap-drive/thinking report)))
      (is (re-find #"\(help\)"
                   (:seon.bootstrap-drive/transcript report)))
      (is (re-find #"total-by-label"
                   (:seon.bootstrap-drive/transcript report)))
      (is (.isFile (java.io.File.
                    (:seon.bootstrap-drive/report-path report)))))))
