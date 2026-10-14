(ns seon.design-prototype-test
  "The Design stage's handoff checks, run on one ordinary task before the chain platform exists
  (`docs/seon/schedule/cards/TASK-CHAIN.md#design-seonstepdesign`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.ref :as ref]
            [seon.test]))

(defn- sections
  "Each heading anchor of Markdown `text` (`seon.ref/document`) with its section's text up to the next heading."
  {:malli/schema [:=> [:cat :string] [:map-of :seon.ref/anchor :string]]}
  [text]
  (let [lines (vec (str/split-lines text))
        headings (:seon.ref/headings (ref/document text))
        ends (concat (map :seon.ref/line (rest headings)) [(inc (count lines))])]
    (into {} (map (fn [{:seon.ref/keys [anchor line]} end] [anchor (str/join "\n" (subvec lines line (dec end)))]) headings ends))))

(deftest ^{:seon/tasks #{'seon.ref/design-a-pathless-reference-section}} a-pathless-reference-section-is-designed
  (let [{{intent :seon.task/plan} :seon.test/task} (seon.test/task)
        intent (or intent "")
        present (sections intent)
        references (ref/references (db/db) {:seon.ref/text intent})]
    (is (= [] (filterv #(str/blank? (get present % "")) ["problem" "what-exists" "approach"]))
        "Problem, What exists and Approach have content")
    (is (= [] (filterv :seon.ref/problem-kind references)) "every reference in the intent resolves")
    (is (some #(and (qualified-keyword? (:seon.ref/name %)) (not (:seon.ref/problem-kind %))) references)
        "the intent names a schema the problem involves")))
