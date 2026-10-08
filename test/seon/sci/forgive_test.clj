(ns seon.sci.forgive-test
  "The forgiving scan and reader stage: what they read from a model reply and
  from code, the repairs of an unbalanced text, and that cost follows the
  text's length."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [are deftest is]]
            [edamame.core :as edamame]
            [seon.sci.forgive :as forgive]
            [seon.turn :as turn]))

(deftest recorded-replies-read-into-the-same-segments
  ;; Recorded model replies: every form, failed read, comment and repair the scan finds.
  (doseq [{reply :text expected :segments} (edn/read-string (slurp "test/seon/sci/forgive_replies.edn"))]
    (is (= expected (forgive/segments reply)))))

(deftest ^{:seon/tasks #{'seon.turn/a-markdown-heading-in-a-reply-is-read-as-code
                         'seon.sci.forgive/an-indented-definition-outside-a-fence-is-read-as-prose}}
  a-markdown-reply-reads-only-its-forms
  ;; A recorded 7,446-character reply of Markdown prose, backticked spans and
  ;; fences (bench E2 H1): only its Clojure-fenced forms are read; every prose
  ;; word becomes comment text, never a symbol to evaluate.
  (let [sources (mapv :seon.batch/source (:seon.batch/segments (forgive/segments (slurp "test/seon/sci/markdown_reply.txt"))))]
    (is (= 13 (count sources)))
    (is (= #{"(in-ns 'seon.schema)" "(in-ns 'comp.order)" "(seon.schema/register! :comp.order/qty [:int {:min 1}])"
             "(defn double-qty {:malli/schema [:=> [:cat :comp.order/qty] :int]} [x] (* 2 x))"
             "(defn double-qty\n  {:malli/schema [:=> [:cat :comp.order/qty] :int]}\n  [x]\n  (* 2 x))"}
           (set sources)))))

(deftest valid-clojure-is-unchanged
  ;; Balanced code keeps every byte; only an unbalanced text is repaired.
  (doseq [text (cons (slurp "src/seon/sci/forgive.clj")
                     (for [{{segments :seon.batch/segments repairs :seon.batch/repairs} :segments}
                           (edn/read-string (slurp "test/seon/sci/forgive_replies.edn"))
                           :when (empty? repairs)
                           {source :seon.batch/source} segments]
                       source))]
    (let [forgiven (forgive/forgive text :code)]
      (is (= text (:seon.batch/text forgiven)))
      (is (not (find forgiven :seon.batch/repairs))))))

(deftest the-reader-reads-a-reply-by-its-line-rules
  (are [mode text sources] (= sources (mapv :seon.batch/source (:seon.batch/segments (forgive/segments text mode))))
    ;; a failed collection is one source; reading resumes after its closer
    :reply "```clojure\n[1 bad: (+ 2 3)]\n(+ 4 5)\n```"          ["[1 bad: (+ 2 3)]" "(+ 4 5)"]
    :reply "```clojure\n(defn f [x] (let [y #1] (inc y)))\n(+ 1 2)\n```" ["(defn f [x] (let [y #1] (inc y)))" "(+ 1 2)"]
    :reply "{:a 1 :b}\n(+ 1 2)"                                     ["{:a 1 :b}" "(+ 1 2)"]
    ;; a fence line inside a string is part of the string
    :reply "(str \"a (\n```\n) b\")"                                ["(str \"a (\n```\n) b\")"]
    ;; a non-code fence is prose, its tagged lines included; a fence closes only on its match
    :reply "```text\n(+ 1 2)\n```clj\n(+ 9 9)\n```\n```clj\n(+ 3 4)\n```" ["(+ 3 4)"]
    :reply "````clojure\n```\n(+ 1 2)\n````\nDone."                ["```\n(+ 1 2)"]
    ;; outside fences a line's first non-blank character decides, or the whole line is one literal
    :reply "#inst \"2026-01-01\"\n#'x\n#:a{:b 1}\n@a\n^:m []\n'x"    ["#inst \"2026-01-01\"" "#'x" "#:a{:b 1}" "@a" "^:m []" "'x"]
    :reply "##NaN\n`(+ 1 2)\n\\newline\n42\nresult/e1a2b3c4d5e6"     ["##NaN" "`(+ 1 2)" "\\newline" "42" "result/e1a2b3c4d5e6"]
    :reply "# Heading\n## h2\n`reply-map` joins\n1. First step\nDone.\nx\n  (+ 3 4)\n(+ 1 2)" ["(+ 3 4)" "(+ 1 2)"]
    :reply "1. Define it:\n   (defn pa [x] (inc x))\n2. Call it:\n   (pa 1)" ["(defn pa [x] (inc x))" "(pa 1)"]
    ;; an untagged fence's body is read by the same line rule
    :reply "```\nRead it as `x`.\n(+ 1 2)\n  (defn f [x]\n    x)\n```\nDone." ["(+ 1 2)" "(defn f [x]\n    x)"]
    :reply "```\ndefine `a/b` contracted `[:=> [:cat :int] :int]`\n```\nNo." []
    ;; a fence line is never a prefix's operand
    :reply "```clojure\n(+ 1 2) '\n```\nDone.\n(+ 3 4)"     ["(+ 1 2)" "'" "(+ 3 4)"]
    :reply "Done."                                                  []
    ;; after a form, the rest of its line is code only when it starts a form
    :reply "(+ 1 2) (+ 3 4) ; sum\nDone."                          ["(+ 1 2)" "(+ 3 4)"]
    :reply "[] [] [999]"                                            ["[]" "[]" "[999]"]
    :reply "(+ 1 2) Done."                                          ["(+ 1 2)"]
    :reply "(Note: this is prose) and more\n(+ 1 2)"               ["(Note: this is prose)" "(+ 1 2)"]
    :reply "#1 priority is (launch!)"                               ["#1"]
    ;; an unbalanced form closes before the next column-zero line; a stray closer goes
    :reply "(defn f [x]\n  (+ x 1)\nNow call it.\n(f 1)"          ["(defn f [x]\n  (+ x 1))" "(f 1)"]
    :reply "```clojure\n(+ 1 2))\n(+ 3 4)\n```"                   ["(+ 1 2)" "(+ 3 4)"]
    :reply "```clj\n(foo]\n(+ 1 2)\n```"                          ["(foo)" "(+ 1 2)"]
    :reply "```clj\n(str \"abc\n```\n(+ 1 2)"                     ["(str \"abc\n```\n(+ 1 2)\")"]
    ;; outside fences a bare true, false or nil line is code
    :reply "true\nfalse\nnil\n42"                               ["true" "false" "nil" "42"]
    :reply "trueish\nnil?"                                        []
    ;; a discard's operand may itself be a discard: each consumes one more form
    :code  "#_ #_ 1 2 3"                                          ["3"]
    :code  "#_ #_ (prn 1) #_ (prn 2) (prn 3) 4"                   ["4"]
    :code  "'#_ a b"                                              ["'#_ a b"]
    :reply "#_ #_ (+ 1 2)\n(+ 3 4)\nDone.\n(+ 5 6)"                ["(+ 5 6)"]
    ;; no-value constructs stay inside their region
    :reply "#_ (+ 1 2)\nDone.\n(+ 3 4)"                             ["(+ 3 4)"]
    :reply "```clj\n#_ (+ 1 2)\n```\nDone.\n(+ 3 4)"                ["(+ 3 4)"]
    :reply "```clj\n#_\n```\nDone.\n(+ 3 4)"                        ["(+ 3 4)"]
    :reply "#?(:cljs 1)\nDone.\n(+ 3 4)"                            ["(+ 3 4)"]
    ;; code is read as written, fence lines skipped, and a failed read continues there too
    :code  "(+ 1 1)\n(vector ::nope.alias/k)\n(+ 2 2)"              ["(+ 1 1)" "(vector ::nope.alias/k)" "(+ 2 2)"]
    :code  "Done.\n:k\n[1 bad: 2]\n42"                             ["Done." ":k" "[1 bad: 2]" "42"]
    :code  "```clojure\n(+ 1 2)\n```"                               ["(+ 1 2)"]
    :code  "(+ 1 2))"                                               ["(+ 1 2)"]
    :code  "(defn f [x]\n  (+ x 1)\n(f 1)"                          ["(defn f [x]\n  (+ x 1))" "(f 1)"]))

(deftest an-unbalanced-text-names-each-repair
  (are [mode text repairs] (= repairs (:seon.batch/repairs (forgive/segments text mode)))
    :code  "(+ 1 2))"                                      [{:seon.batch/line 1 :seon.batch/at 7 :seon.batch/dropped ")"}]
    :code  "(defn f [x]\n  (+ x 1)\n(f 1)"                 [{:seon.batch/line 2 :seon.batch/at 21 :seon.batch/inserted ")"}]
    :code  "(let [x 1) x"                                  [{:seon.batch/line 1 :seon.batch/at 9 :seon.batch/inserted "]"}]
    :reply "(defn f [x]\n  (+ x 1)\nNow call it.\n(f 1)" [{:seon.batch/line 2 :seon.batch/at 21 :seon.batch/inserted ")"}]
    :reply "(str \"abc"                                   [{:seon.batch/line 1 :seon.batch/at 9 :seon.batch/inserted "\""}
                                                            {:seon.batch/line 1 :seon.batch/at 10 :seon.batch/inserted ")"}]
    ;; closers inserted before a dropped closer move its offset
    :code  "(a ]\n  b\n(c)"                               [{:seon.batch/line 1 :seon.batch/at 3 :seon.batch/dropped "]"}
                                                            {:seon.batch/line 2 :seon.batch/at 7 :seon.batch/inserted ")"}]
    :code  "(+ 1 2)"                                       nil))

(deftest each-segment-carries-the-repairs-from-its-start-to-the-next
  (are [text repairs] (= repairs (mapv #(mapv (juxt :seon.batch/line :seon.batch/dropped :seon.batch/inserted) (:seon.batch/repairs %))
                                       (:seon.batch/segments (forgive/segments text :code))))
    "(+ 1 2))"                      [[[1 ")" nil]]]
    "(defn f [x]\n  (+ x 1)\n(f 1)" [[[2 nil ")"]] []]
    "(f 1)\n(g 2))"                 [[] [[2 ")" nil]]]
    ;; two forms on one line: the stray closers after the first are its own
    "(inc 1))) (inc 2)"             [[[1 ")" nil] [1 ")" nil]] []]
    "(inc 1) (inc 2))"              [[] [[1 ")" nil]]]
    "(+ 1 2)"                       [[]]))

(defn- allocated-reading
  "Bytes this thread allocates segmenting `reply` (-1 on a virtual thread,
  for which HotSpot keeps no count)."
  {:malli/schema [:=> [:cat :string] :int]}
  [reply]
  (let [threads ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        before (.getCurrentThreadAllocatedBytes threads)]
    (forgive/segments reply)
    (if (neg? before) -1 (- (.getCurrentThreadAllocatedBytes threads) before))))

(deftest a-reply-reads-in-allocation-linear-in-its-length
  ;; Prose words, prompt markers and dispatch forms once each re-read the
  ;; whole rest of the reply: four times the reply allocated twenty times more.
  (let [unit (str "Note: the value is read here.\n```clojure\nuser=> (meta #'x)\n(map #(inc %) [1 2])\n"
                  "(re-find #\"a\" @a)\n```\n`reply-map` joins entries; turns left: 6 of 12\n")
        reply (apply str (repeat 40 unit))
        longer (apply str (repeat 160 unit))
        _ (allocated-reading reply)
        once (allocated-reading reply)]
    (is (pos? once) "the allocation counter measures the read")
    (is (< (allocated-reading longer) (* 6 once))
        "four times the reply allocates at most six times as much")))

(deftest a-reply-over-the-source-bound-is-one-refused-source
  ;; The bound applies before segmentation, so the reader's own refusal answers it.
  (let [reply "(+ 1 2)\n(+ 3 4)"]
    (is (= [reply] (mapv :seon.cluster.eval/source (turn/planned-sources reply 'user 10))))
    (is (= [reply] (mapv :seon.cluster.eval/source (turn/code-sources reply 'user 10))))
    (is (= ["(+ 1 2)" "(+ 3 4)"] (mapv :seon.cluster.eval/source (turn/planned-sources reply 'user 100))))))

(def ^:private locating-stage
  "A read stage whose forms carry `:line` and `:column`."
  (let [opts (edamame/normalize-opts {:all true :eof ::eof :location? (constantly true)
                                      :row-key :line :col-key :column})]
    {:seon.batch/read (fn [rdr eof] (let [f (edamame/parse-next rdr opts)] (if (= ::eof f) eof f)))}))

(defn- positions
  "The line and column of each form `text` reads in `mode`, or of the failure."
  [text mode]
  (loop [state (forgive/read-state text) out []]
    (let [r (forgive/step locating-stage mode state)
          failure (:seon.batch/read-error r)]
      (if (:seon.batch/eof r)
        out
        (recur (assoc state :seon.batch/version (:seon.batch/version r) :seon.batch/last-code-end (:seon.batch/end r))
               (conj out (if failure
                           (select-keys (ex-data failure) [:line :column])
                           (-> r :seon.batch/form meta (select-keys [:line :column])))))))))

(deftest a-form-read-mid-text-has-the-texts-own-line-and-column
  (are [mode text expected] (= expected (positions text mode))
    :reply "Some prose.\n(defn pa [x] x)"                [{:line 2 :column 1}]
    :reply "Prose\n(def a 1)\nmore\n(def b 2)"            [{:line 2 :column 1} {:line 4 :column 1}]
    :code  "; note\n\n  (def a 1) (def b 2)"               [{:line 3 :column 3} {:line 3 :column 13}]
    :code  "(def a 1)\n(def b 2) (def c 3)"                [{:line 1 :column 1} {:line 2 :column 1} {:line 2 :column 11}]
    :reply "Prose\n(def a 1)\nmore\n(def b 2) (foo ]"      [{:line 2 :column 1} {:line 4 :column 1} {:line 4 :column 11}]
    :reply "(+ 1 2) ; x\n  (def c 3) (str \"a\" #\"b\")"   [{:line 1 :column 1} {:line 2 :column 3} {:line 2 :column 13}]))
