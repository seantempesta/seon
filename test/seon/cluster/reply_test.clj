(ns seon.cluster.reply-test
  "Sealed acceptance draft for reply splitting (N3, C4).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). The implementation
  lane makes these green by implementing `seon.cluster.reply` ONLY.

  The property is a ROUND TRIP over generated forms: print a vector of
  forms, split the printed text, and the sources must read back as
  equivalent forms in the same order. That is what makes this a codec
  rather than a regex — and the same property with fences wrapped
  around the text must produce the identical vector, because a fence is
  presentation and must not survive into a plan.

  The refusal cases are the ones probe C measured on real model-shaped
  text, and they are the reason nothing here calls
  `clojure.core/read-string`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [sci.core :as sci]
            [seon.cluster.reply :as reply]
            [seon.schema]))

(defn- read-back
  "Read one source string with a throwaway sci reader, as the evaluator
  will — never clojure.core/read-string, which is the scar."
  [source]
  (sci/parse-string (sci/init {}) source))

(defn- error? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- no-forms? [value]
  (= :seon.cluster.reply/no-forms (:seon.error/kind value)))

;;; ---------------------------------------------------------------------------
;;; The round trip
;;; ---------------------------------------------------------------------------

(def ^:private form-generator
  (gen/one-of
   [(gen/fmap (fn [n] (list 'inc n)) gen/small-integer)
    (gen/fmap (fn [k] {k [1 2 3]}) gen/keyword)
    (gen/fmap (fn [s] (list 'str s)) gen/string-alphanumeric)
    (gen/fmap (fn [n] (list 'defn 'f '[x] (list '+ 'x n))) gen/small-integer)
    (gen/return '(my.run/wait "still working"))
    (gen/return '(let [y (* 2 3)] {:y y}))]))

(deftest a-printed-plan-round-trips-through-the-splitter
  (let [check
        (tc/quick-check
         100
         (prop/for-all [forms (gen/vector form-generator 1 6)
                        separator (gen/elements ["\n" "\n\n" " " "\n;; note\n"])]
           (let [text (str/join separator (map pr-str forms))
                 sources (reply/sources text)]
             (and (vector? sources)
                  (= (count forms) (count sources))
                  (= forms (mapv read-back sources)))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "round trip failed: " (pr-str check)))))

(deftest fences-are-presentation-and-never-reach-the-plan
  (let [check
        (tc/quick-check
         50
         (prop/for-all [forms (gen/vector form-generator 1 4)
                        language (gen/elements ["" "clojure" "clj" "edn"])]
           (let [body (str/join "\n" (map pr-str forms))
                 fenced (str "Here you go:\n\n```" language "\n" body "\n```\n")]
             (= (reply/sources body) (reply/sources fenced))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "fenced input differed: " (pr-str check)))))

;;; ---------------------------------------------------------------------------
;;; Call shapes, as documentation
;;; ---------------------------------------------------------------------------

(deftest the-source-is-exactly-what-the-agent-wrote
  (testing "including the comment that precedes a form"
    (is (= [";; a note\n(def a 1)" "(inc a)"]
           (reply/sources ";; a note\n(def a 1)\n(inc a)"))))
  (testing "and nesting, whitespace and strings containing parens"
    (is (= ["(println \"a ) b\")" "(inc 1)"]
           (reply/sources "(println \"a ) b\")\n(inc 1)")))
    (is (= ["(defn f [x]\n  (let [y (* x 2)]\n    {:y y}))"]
           (reply/sources "(defn f [x]\n  (let [y (* x 2)]\n    {:y y}))")))))

;;; ---------------------------------------------------------------------------
;;; Code or text — the whole reply, never token salad
;;; ---------------------------------------------------------------------------

(deftest a-reply-is-entirely-code-or-entirely-text
  (testing "pure code keeps its exact ordered top-level sources"
    (let [text (str "(def widgets (map inc (range 3)))\n"
                    "widgets\n"
                    "(my.run/complete \"counted 6\")")]
      (is (= ["(def widgets (map inc (range 3)))"
              "widgets"
              "(my.run/complete \"counted 6\")"]
             (reply/sources text)))))

  (testing "pure prose stays exact reply text and yields no forms"
    (let [text "I explained what I had done. The result was fifty-five."
          result (reply/sources text)]
      (is (no-forms? result))
      (is (= text (get-in result
                          [:seon.error/data :seon.cluster.reply/text])))))

  (testing "the live word-salad reply freezes none of its 23 tokens"
    (let [text (str "I defined a function to sum integers from 1 to n, "
                    "called it with 10 to get 55, and reported the action.\n"
                    "(my.run/complete \"reported\")")
          result (reply/sources text)]
      (is (no-forms? result))
      (is (not (vector? result))
          "neither `get` nor the trailing list becomes a plan form")
      (is (= text (get-in result
                          [:seon.error/data :seon.cluster.reply/text]))))))

;;; ---------------------------------------------------------------------------
;;; Refusals — flat values, never throws
;;; ---------------------------------------------------------------------------

(deftest every-refusal-is-a-value
  (testing "unbalanced input refuses with a position, and does not hang"
    (let [refused (reply/sources "(defn f [x]\n  (+ x 1)")]
      (is (error? refused))
      (is (= :seon.cluster.reply/unreadable (:seon.error/kind refused)))
      (is (str/includes? (:seon.error/message refused) "1")
          "the reader's own position reaches the agent")))
  (testing "read-eval is refused by the reader, not by a blocklist"
    (let [refused (reply/sources "#=(System/exit 1)")]
      (is (error? refused))
      (is (contains? #{:seon.cluster.reply/refused-tag
                       :seon.cluster.reply/unreadable}
                     (:seon.error/kind refused)))))
  (testing "an unknown reader tag is refused by name"
    (is (error? (reply/sources "#foo/bar [1 2]"))))
  (testing "prose with no forms is its own outcome, not a parse failure"
    (let [refused (reply/sources "I think we should consider the options.")]
      (is (error? refused))
      (is (no-forms? refused))))
  (testing "an empty reply is the same outcome"
    (is (= :seon.cluster.reply/no-forms
           (:seon.error/kind (reply/sources "   \n\n  "))))))

(deftest a-fenced-reply-with-prose-around-it-still-splits
  (is (= ["(def a 1)" "(my.run/complete \"done\")"]
         (reply/sources
          (str "Sure — here is the plan.\n\n"
               "```clojure\n(def a 1)\n(my.run/complete \"done\")\n```\n\n"
               "Let me know if that works.")))))
