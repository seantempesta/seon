(ns seon.reader-test
  "The forgiving reader (next-review #4, idea 8): a model's reply with prose and no fence is
  source whose prose is comments; delimiters are repaired; and its two laws hold (T5's
  forgive tasks, Seon's spec): forgiving forgiven text changes nothing, and the repairs
  account for every change."
  (:require [clojure.test :refer [deftest is are testing]]
            [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.reader :as reader]))

(defn- src [reply] (:text (reader/forgive reply)))

(defn- forms
  "What the REPL reads from source: every form (Jolt's reader)."
  [s]
  (let [r (java.io.PushbackReader. (java.io.StringReader. s))]
    (loop [acc []] (let [f (read {:eof ::eof} r)] (if (= ::eof f) acc (recur (conj acc f)))))))

(deftest prose-without-a-fence-is-a-comment
  ;; the review's reproduction: the prose went to the REPL as code
  (is (= ";; I'll fix add now.\n(defn add [a b] (+ a b))" (src "I'll fix add now.\n(defn add [a b] (+ a b))")))
  (is (= '[(defn add [a b] (+ a b))] (forms (src "I'll fix add now.\n(defn add [a b] (+ a b))\nThat should do it.")))))

(deftest a-fenced-reply-reads-only-its-code
  (let [reply "Squaring doubles?\n```clojure\n(in-ns 'app.calc)\n(defn square [x]\n  x)\n```\nDone, I think.\n"]
    (is (= '[(in-ns 'app.calc) (defn square [x] x)] (forms (src reply))))
    (is (str/includes? (src reply) "(defn square [x]\n  x)") "the code as written"))
  (testing "another tag's body is prose; an untagged fence's body goes by the line rule"
    (is (= [] (forms (src "```text\n(not code)\n```"))))
    (is (= '[(+ 1 2)] (forms (src "```\nRun this:\n(+ 1 2)\n```")))))
  (testing "a line that is one literal or qualified symbol is code; a list item is not"
    (is (= '[:k 42 app.calc/add] (forms (src ":k\n42\napp.calc/add\n1. Fix add\n# Heading"))))))

(deftest delimiters-are-repaired-each-change-a-repair
  (are [reply text] (= text (src reply))
    "(+ 1 2))" "(+ 1 2),"
    "(a]a)" "(a,a)"
    "(let [x 1) x" "(let [x 1]) x"
    "(defn f [x]\n  (inc x)\n(defn g [] 2)" "(defn f [x]\n  (inc x))\n(defn g [] 2)"
    "(str \"a" "(str \"a\")")
  (is (= [{:at 7 :dropped ")" :inserted ","}] (:repairs (reader/forgive "(+ 1 2))")))))

(def texts
  "Replies the laws are checked over: prose, fences, strings, characters, comments and
  every way a delimiter goes wrong."
  ["" "a]a" "(+ 1 2)" "(+ 1 2))" "((" "))" "(let [x 1) x" "(a ; note\nb" "('" "(str \"a" "\"\\" "(\\) \\]"
   "I'll fix add now.\n(defn add [a b] (+ a b))"
   "Text\n```clojure\n(defn f [x]\n  (inc x)\n```\nMore text (with parens\n```clojure\n(f 1))\n```"
   "```\nRun this:\n(+ 1 2\n```\n" "```text\n(not code\n```" "  (indented\nprose at col 0\n  more)"
   "(defn f \"doc\nwith a line starting ; and (\" [x] x)" ";; a comment (\n(+ 1 2)" ":k\n42\n1. item\n# Heading\n## Sub"])

(defn- laws-hold? [t]
  (let [r (reader/forgive t)]
    (and (= t (reader/unforgive r))
         (= {:text (:text r) :repairs []} (reader/forgive (:text r))))))

(deftest forgiving-forgiven-text-changes-nothing
  (doseq [t texts]
    (let [r (reader/forgive t)]
      (is (= {:text (:text r) :repairs []} (reader/forgive (:text r))) (pr-str t)))))

(deftest the-repairs-account-for-every-change
  (doseq [t texts]
    (is (= t (reader/unforgive (reader/forgive t))) (pr-str t))))

(deftest the-laws-hold-over-generated-replies
  (let [piece (gen/elements ["(" ")" "[" "]" "{" "}" "\"" "\\" ";" "\n" " " "  " "a" "x/y" "1" "#" "'" ":k" "I'll "
                             "```" "```clojure\n" "```text\n" "\n```\n" "#{" "#(" "`" "~" "@" "^" ","])
        r (tc/quick-check 300 (prop/for-all [ps (gen/vector piece 0 40)] (laws-hold? (apply str ps))) :seed 42)]
    (is (:pass? r) (pr-str (get-in r [:shrunk :smallest])))))
