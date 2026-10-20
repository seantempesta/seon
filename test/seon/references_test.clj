(ns seon.references-test
  "A plan's references by one rule (next-review #3, idea 7): edamame reads each backticked
  span; a keyword, a path#heading and prose are not vars, and a form references the vars it
  names."
  (:require [clojure.test :refer [deftest is are]]
            [seon.reconcile :as r]))

(deftest the-reviews-plan-references-one-var
  ;; the review's reproduction: three of the four spans were \"qualified symbols\" before
  (is (= '[app.calc/add] (r/references "`:seon.task/plan` `docs/DRAGONS.md` `app.calc/add`")))
  (is (= '[my.task/current app.calc/add] (r/references "`:seon.task/plan` `docs/DRAGONS.md` `(my.task/current)` `app.calc/add`"))
      "a form references the vars it names"))

(deftest a-span-is-classified-by-one-rule
  (are [span ref] (= ref (r/reference span))
    "app.calc/add" {:kind :symbol :symbol 'app.calc/add}
    ":seon.task/plan" {:kind :keyword :keyword :seon.task/plan}
    "docs/DRAGONS.md" {:kind :path :path "docs/DRAGONS.md"}
    "docs/DRAGONS.md#physics" {:kind :path :path "docs/DRAGONS.md" :anchor "physics"}
    "src/seon/repl.clj" {:kind :path :path "src/seon/repl.clj"}
    "(my.task/current)" {:kind :form :form '(my.task/current) :symbols '[my.task/current]}
    "add" nil
    "a b c" nil
    "3" nil
    "(unbalanced" nil))

(deftest fenced-code-and-prose-are-not-references
  (is (= '[app.calc/twice] (r/references "Use `app.calc/twice`.\n```clojure\n(app.calc/add `x` 1)\n```\nnot `prose with/slash`")))
  (is (= [] (r/references nil))))
