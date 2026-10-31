(ns seon.reader-test
  "The forgiving reader (next-review #4, idea 8): a model's reply with prose and no fence is
  source whose prose is comments; delimiters are repaired; and its two laws hold (T5's
  forgive tasks, Seon's spec): forgiving forgiven text changes nothing, and the repairs
  account for every change."
  (:require [clojure.test :refer [deftest is are testing]] [seon.proofs :as proofs]
            [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.reader :as reader] [seon.cost :as cost]))

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

(defn- unforgive
  "The reply forgive was given, from its answer: each repair undone, last first."
  [{:keys [text repairs]}]
  (reduce (fn [t {:keys [at dropped inserted]}] (str (subs t 0 at) dropped (subs t (+ at (count inserted)))))
          text (rseq repairs)))

(def texts
  "Replies the laws are checked over: prose, fences, strings, characters, comments and
  every way a delimiter goes wrong."
  ["" "a]a" "(+ 1 2)" "(+ 1 2))" "((" "))" "(let [x 1) x" "(a ; note\nb" "('" "(str \"a" "\"\\" "(\\) \\]"
   "I'll fix add now.\n(defn add [a b] (+ a b))"
   "Text\n```clojure\n(defn f [x]\n  (inc x)\n```\nMore text (with parens\n```clojure\n(f 1))\n```"
   "```\nRun this:\n(+ 1 2\n```\n" "```text\n(not code\n```" "  (indented\nprose at col 0\n  more)"
   "(defn f \"doc\nwith a line starting ; and (\" [x] x)" ";; a comment (\n(+ 1 2)" ":k\n42\n1. item\n# Heading\n## Sub"
   ;; a reseed's counterexample (seed 449380042768857075): a backslash inside a token starts a character, so `\( is
   ;; one form (syntax-quote of the character (), never an open paren to close
   (apply str ["`" "\\" "("])])

(defn- laws-hold? [t]
  (let [r (reader/forgive t)]
    (and (= t (unforgive r))
         (= {:text (:text r) :repairs []} (reader/forgive (:text r))))))

(deftest forgiving-forgiven-text-changes-nothing
  (doseq [t texts]
    (let [r (reader/forgive t)]
      (is (= {:text (:text r) :repairs []} (reader/forgive (:text r))) (pr-str t)))))

(deftest the-repairs-account-for-every-change
  (doseq [t texts]
    (is (= t (unforgive (reader/forgive t))) (pr-str t))))

(deftest the-laws-hold-over-generated-replies
  (let [piece (gen/elements ["(" ")" "[" "]" "{" "}" "\"" "\\" ";" "\n" " " "  " "a" "x/y" "1" "#" "'" ":k" "I'll "
                             "```" "```clojure\n" "```text\n" "\n```\n" "#{" "#(" "`" "~" "@" "^" ","])
        r (tc/quick-check 300 (prop/for-all [ps (gen/vector piece 0 40)] (laws-hold? (apply str ps))) :seed (proofs/test-seed))]
    (is (:pass? r) (pr-str (select-keys r [:seed]) (get-in r [:shrunk :smallest])))))

(deftest line-starts-are-each-lines-offset
  (is (= [0] (reader/line-starts "")) "empty text is one line, at 0")
  (is (= [0 4 5] (reader/line-starts "abc\n\nd")) "each line starts just past the newline before it")
  (is (= [0 3] (reader/line-starts "ab\n")) "a trailing newline starts an empty last line")
  (let [text "(ns a)\n(defn f [] 1)\n;; c\n"]
    (is (= (str/split-lines text) (map #(subs text %1 (dec %2)) (reader/line-starts text) (rest (reader/line-starts text))))
        "the text between two starts is the line, newline excluded")))

(deftest a-definitions-head-is-its-text-up-to-its-body
  ;; owner, 2026-10-25: a cited var is its definition's head as the file writes it, its body
  ;; left out and marked
  (is (= "(defn f\n  \"Doubles, \\\"exactly\\\".\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  …)"
         (reader/definition-head "(defn f\n  \"Doubles, \\\"exactly\\\".\"\n  {:malli/schema [:=> [:cat :int] :int]}\n  [x]\n  ;; twice\n  (* 2 x))" 'user)))
  (is (= "(defn g\n  \"Adds.\"\n  ([a] …)\n  ([a b] …))"
         (reader/definition-head "(defn g\n  \"Adds.\"\n  ([a] (g a 1))\n  ([a b] (+ a b)))" 'user)))
  (is (nil? (reader/definition-head "(def x 1)" 'user)) "a def has no arglist: no head")
  (is (nil? (reader/definition-head "(def x [1 2 3])" 'user)) "a def holding a vector is no function: no head")
  (is (nil? (reader/definition-head "(defmulti m \"Dispatch.\" [:kind])" 'user)) "nor is a defmulti"))


(deftest delimiters-are-read-as-the-reader-reads-them
  ;; md-scan bench-b15: a refused edit named its unpaired delimiters by line (seon.repl/edit!); what is no delimiter stays none
  (is (= {:open [] :stray [] :crossed []} (reader/delimiters "(str \\( \")\" #\"[(]\" ; ) ]\n x)")) "a character, a string, a regex and a comment hold none")
  (is (= {:open [[\( 0]] :stray [] :crossed []} (reader/delimiters "(a [b]")) "an opener left open, with its offset")
  (is (= {:open [] :stray [[\) 3]] :crossed []} (reader/delimiters "(a)) ")) "a closer with nothing open")
  (is (= {:open [] :stray [] :crossed [[\] 3 \( 0]]} (reader/delimiters "(a ]")) "a closer of another kind than its opener")
  (is (= {\( 1 \[ -1 \{ 0} (reader/balance "(a ]") (reader/delimiter-counts (reader/delimiters "(a ]")))
      "counted, a crossed pair leaves a ( opened and a ] closed: balance is the counts of the one scan"))

(defn code-text
  "A definition-sized code text of about n characters, for delimiters' cost proof."
  {:malli/schema [:=> [:cat pos-int?] [:tuple :string]]}
  [n]
  (let [unit "(let [a (inc x) s \"(]\" c \\) m {:k [1 2]}] ; a ) comment\n  (+ a 1))\n"]
    [(apply str (repeat (max 1 (quot n (count unit))) unit))]))

(deftest delimiters-cost-one-pass
  (cost/proof
    {:of 'seon.reader/delimiters :input 'seon.reader-test/code-text
     :n "characters of code text"
     :work "one pass by charAt, a vector conj per delimiter (a stack push or pop): ~20 ns and a few bytes a character"
     :growth :linear
     :per-n {:ns 60 :bytes 64 :calls 0.01}
     :fixed {:ns 20000 :bytes 20000 :calls 5}
     :sizes [120 250 500 1000]}))
