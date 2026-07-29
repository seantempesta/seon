(ns seon.cluster.reply-test
  "Sealed acceptance draft for reply splitting (N3, C4).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). The implementation
  lane makes these green by implementing `seon.cluster.reply` ONLY.

  The property is a ROUND TRIP over generated forms: print a vector of
  forms, split the printed text, and the sources must read back as
  equivalent forms in the same order. That is what makes this a codec
  rather than a regex. Markdown fences are presentation and never reach
  the plan; surrounding explanation survives as source comments.

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

(defn- sources
  "The plan forms' source strings, or the flat error value unchanged.
  A plan form is a source PLUS the namespace it was written under; the
  splitting assertions below are about the source half, and
  `attribution-follows-the-one-reader` is about the other."
  [text]
  (let [forms (reply/sources text)]
    (if (vector? forms)
      (mapv :seon.cluster.run.form/source forms)
      forms)))

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
                 sources (sources text)]
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
             (let [sources (sources fenced)]
               (and (= forms (mapv read-back sources))
                    (not-any? #(str/includes? % "```") sources)))))
         :seed 20260727)]
    (is (true? (:result check))
        (str "fenced input differed: " (pr-str check)))))

;;; ---------------------------------------------------------------------------
;;; Call shapes, as documentation
;;; ---------------------------------------------------------------------------

(deftest the-source-is-exactly-what-the-agent-wrote
  (testing "including the comment that precedes a form"
    (is (= [";; a note\n(def a 1)" "(inc a)"]
           (sources ";; a note\n(def a 1)\n(inc a)"))))
  (testing "and nesting, whitespace and strings containing parens"
    (is (= ["(println \"a ) b\")" "(inc 1)"]
           (sources "(println \"a ) b\")\n(inc 1)")))
    (is (= ["(defn f [x]\n  (let [y (* x 2)]\n    {:y y}))"]
           (sources "(defn f [x]\n  (let [y (* x 2)]\n    {:y y}))"))))
  (testing "parenthesized code mentioned in prose is still prose"
    (is (= ["; I will run (+ 1 2) now.\n(+ 1 2)"]
           (sources "I will run (+ 1 2) now.\n(+ 1 2)")))))

;;; ---------------------------------------------------------------------------
;;; Forms and prose — comments record text, only forms run
;;; ---------------------------------------------------------------------------

(deftest forms-run-and-prose-becomes-source-comments
  (testing "pure code keeps its exact ordered top-level sources"
    (let [text (str "(def widgets (map inc (range 3)))\n"
                    "widgets\n"
                    "(my.run/complete \"counted 6\")")]
      (is (= ["(def widgets (map inc (range 3)))"
              "widgets"
              "(my.run/complete \"counted 6\")"]
             (sources text)))))

  (testing "pure prose is one recorded comment source, never a form"
    (let [text "I explained what I had done. The result was fifty-five."
          result (sources text)]
      (is (= ["; I explained what I had done. The result was fifty-five."]
             result))
      (is (nil? (read-back (first result)))
          "SCI reads a comment-only source as nil; no prose token resolves")))

  (testing "mixed prose attaches to the next form and trailing prose survives"
    (let [text (str "First I will add the values.\n"
                    "(+ 1 2)\n"
                    "Then I will finish.\n"
                    "(my.run/complete \"3\")\n"
                    "That is all.")]
      (is (= ["; First I will add the values.\n(+ 1 2)"
              "; Then I will finish.\n(my.run/complete \"3\")"
              "; That is all."]
             (sources text)))))

  (testing "the live word-salad reply freezes one form, not its 22 prose tokens"
    (let [text (str "I defined a function to sum integers from 1 to n, "
                    "called it with 10 to get 55, and reported the action.\n"
                    "(my.run/complete \"reported\")")
          result (sources text)]
      (is (= [(str "; I defined a function to sum integers from 1 to n, "
                   "called it with 10 to get 55, and reported the action.\n"
                   "(my.run/complete \"reported\")")]
             result))
      (is (= '(my.run/complete "reported") (read-back (first result))))
      (is (not-any? #{"I" "defined" "1" "10" "get" "55"} result)
          "none of the live prose tokens becomes its own plan source")))

  (testing "invalid prose tokens are comments while a same-line form survives"
    (is (= ["; denied /etc/hosts now.\n(my.run/complete \"denied\")"]
           (sources
            "denied /etc/hosts now.(my.run/complete \"denied\")")))))

;;; ---------------------------------------------------------------------------
;;; Attribution — the reader's namespace-in-effect, projected verbatim
;;; ---------------------------------------------------------------------------

(deftest attribution-follows-the-one-reader
  (testing "REPL semantics: each form carries the ns it was written under"
    (is (= [{:seon.cluster.run.form/source "(ns my.gen.alpha)"
             :seon.ns/name 'user}
            {:seon.cluster.run.form/source "(defn f [x] (inc x))"
             :seon.ns/name 'my.gen.alpha}
            {:seon.cluster.run.form/source "(ns my.gen.beta)"
             :seon.ns/name 'my.gen.alpha}
            {:seon.cluster.run.form/source "(defn g [x] (* 2 x))"
             :seon.ns/name 'my.gen.beta}]
           (reply/sources
            (str "(ns my.gen.alpha)\n(defn f [x] (inc x))\n"
                 "(ns my.gen.beta)\n(defn g [x] (* 2 x))")))))

  (testing "a malformed declaration yields ABSENCE, never inheritance"
    (let [forms (reply/sources "(ns my.gen.alpha)\n(ns)\n(defn f [x] x)")]
      (is (= 'my.gen.alpha (:seon.ns/name (nth forms 1)))
          "the malformed form itself was still read under alpha")
      (is (not (contains? (nth forms 2) :seon.ns/name))
          "everything after it is unattributed, so its red form routes
           to the author rather than to a guessed owner")))

  (testing "prose carried into a form does not disturb attribution"
    (is (= [{:seon.cluster.run.form/source "(ns my.gen.alpha)"
             :seon.ns/name 'user}
            {:seon.cluster.run.form/source "; Now the function.\n(defn f [] 1)"
             :seon.ns/name 'my.gen.alpha}]
           (reply/sources
            "(ns my.gen.alpha)\nNow the function.\n(defn f [] 1)"))))

  (testing "a trailing prose-only source is not a form and has no namespace"
    (is (= {:seon.cluster.run.form/source "; That is all."}
           (last (reply/sources "(ns my.gen.alpha)\n(def a 1)\nThat is all."))))))

;;; ---------------------------------------------------------------------------
;;; Refusals — flat values, never throws
;;; ---------------------------------------------------------------------------

(deftest every-refusal-is-a-value
  (testing "unbalanced input refuses with a position, and does not hang"
    (let [refused (sources "(defn f [x]\n  (+ x 1)")]
      (is (error? refused))
      (is (= :seon.cluster.reply/unreadable (:seon.error/kind refused)))
      (is (str/includes? (:seon.error/message refused) "1")
          "the reader's own position reaches the agent")))
  (testing "an invalid token inside a structured form is malformed code"
    (let [refused (sources "(+ 1\n  80s)")]
      (is (error? refused))
      (is (= :seon.cluster.reply/unreadable (:seon.error/kind refused)))))
  (testing "read-eval is refused by the reader, not by a blocklist"
    (let [refused (sources "#=(System/exit 1)")]
      (is (error? refused))
      (is (contains? #{:seon.cluster.reply/refused-tag
                       :seon.cluster.reply/unreadable}
                     (:seon.error/kind refused)))))
  (testing "an unknown reader tag is refused by name"
    (is (error? (sources "#foo/bar [1 2]"))))
  (testing "an empty reply has neither a form nor a prose note"
    (is (= :seon.cluster.reply/no-forms
           (:seon.error/kind (sources "   \n\n  "))))))

(deftest a-fenced-reply-retains-surrounding-prose-as-comments
  (is (= ["; Sure — here is the plan.\n\n(def a 1)"
          "(my.run/complete \"done\")"
          "; Let me know if that works."]
         (sources
          (str "Sure — here is the plan.\n\n"
               "```clojure\n(def a 1)\n(my.run/complete \"done\")\n```\n\n"
               "Let me know if that works.")))))

(deftest tilde-fences-have-the-same-presentation-semantics
  (is (= ["; Here:\n(+ 1 2)" "; Done."]
         (sources "Here:\n~~~clojure\n(+ 1 2)\n~~~\nDone."))))
