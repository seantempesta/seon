(ns seon.dev.docstring-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.agent.ctx.ns-name :as ns-name]
            [seon.dev.docstring :as d]))

(defn- rules
  "The set of ::rule keywords in a check-source response's findings."
  [source]
  (->> (d/check-source {::d/source source})
       ::d/findings
       (map ::d/rule)
       set))

;;; ---------------------------------------------------------------------------
;;; Compliant docstrings — no findings
;;; ---------------------------------------------------------------------------

(deftest compliant-first-line-test
  (testing "a complete <=78-char sentence ending in a period is clean"
    (let [r (d/check-source {::d/source "(ns foo)\n(defn bar \"Store one thing.\" [x] x)"})]
      (is (true? (::d/clean? r)))
      (is (false? (::d/skipped? r)))
      (is (empty? (::d/findings r)))))

  (testing "question mark and bang are terminal punctuation too"
    (is (empty? (rules "(ns foo)\n(defn q \"Is it armable?\" [x] x)")))
    (is (empty? (rules "(ns foo)\n(defn b \"Do it now!\" [x] x)"))))

  (testing "multiline docstring — only line 1 matters"
    (is (empty? (rules (str "(ns foo)\n(defn bar\n  \"Store one thing.\n\n"
                            "   The body prose can be as long and rambling as the author likes"
                            " with no cap whatsoever.\"\n  [x] x)"))))))

;;; ---------------------------------------------------------------------------
;;; The three rules
;;; ---------------------------------------------------------------------------

(deftest missing-docstring-test
  (testing "public fn with no docstring is flagged"
    (is (= #{:missing-docstring} (rules "(ns foo)\n(defn bar [x] x)"))))

  (testing "attr-map-first (no docstring) reads as missing, not present"
    (is (= #{:missing-docstring}
           (rules "(ns foo)\n(defn bar {:malli/schema :any} [x] x)"))))

  (testing "multi-arity fn with no docstring is flagged"
    (is (= #{:missing-docstring}
           (rules "(ns foo)\n(defn bar ([x] x) ([x y] y))")))))

(deftest too-long-test
  (testing "line 1 over the 78 hard cap is flagged"
    (let [line (str (apply str (repeat 85 \a)) ".")
          r (d/check-source {::d/source (str "(ns foo)\n(defn bar \"" line "\" [x] x)")})
          f (first (::d/findings r))]
      (is (= :first-line-too-long (::d/rule f)))
      (is (str/includes? (::d/message f) "86 chars"))))

  (testing "exactly 78 chars is allowed (cap is inclusive)"
    (let [line (str (apply str (repeat 77 \a)) ".")] ; 78 total
      (is (= 78 (count line)))
      (is (empty? (rules (str "(ns foo)\n(defn bar \"" line "\" [x] x)")))))))

(deftest no-terminal-punctuation-test
  (testing "line 1 without terminal punctuation is flagged"
    (is (= #{:no-terminal-punctuation}
           (rules "(ns foo)\n(defn bar \"Store one thing\" [x] x)"))))

  (testing "a mid-sentence hard wrap reads as missing terminal punctuation"
    (is (= #{:no-terminal-punctuation}
           (rules "(ns foo)\n(defn bar \"Snapshot of all keys. Used by\" [x] x)")))))

(deftest blank-first-line-test
  (testing "a docstring whose first line is blank is flagged"
    (is (= #{:blank-first-line}
           (rules "(ns foo)\n(defn bar \"\n  Real content on line 2.\" [x] x)")))))

;;; ---------------------------------------------------------------------------
;;; Reserved-glyph-literal rule (INVERTED, transcript-render redesign) — a
;;; docstring must carry NO reserved runtime result-grammar glyph (⟹ ⟸ ⋘ ⋙ ❯);
;;; static agent-facing text shows the CALL and describes the return in PROSE.
;;; ---------------------------------------------------------------------------

(deftest reserved-glyph-literal-test
  (testing "a result-open ⟹ echo in the docstring body is flagged"
    (is (contains?
         (rules (str "(ns foo)\n(defn bar\n  \"Store one thing.\n\n"
                     "     (bar 1)\n     ; ⟹ 1\"\n  [x] x)"))
         :reserved-glyph-literal)))

  (testing "a result-close ⟸ literal is flagged"
    (is (contains?
         (rules (str "(ns foo)\n(defn bar\n  \"Store one thing.\n\n"
                     "     (bar 1) ⟹ 1 ⟸ result/x\"\n  [x] x)"))
         :reserved-glyph-literal)))

  (testing "a status/prompt glyph (⋘ ⋙ ❯) literal is flagged"
    (doseq [g ["⋘" "⋙" "❯"]]
      (is (contains?
           (rules (str "(ns foo)\n(defn bar\n  \"Store one thing.\n\n"
                       "     a " g " glyph here.\"\n  [x] x)"))
           :reserved-glyph-literal)
          (str g " is a reserved glyph"))))

  (testing "a stale `;; =>` echo is NO LONGER flagged (rule inverted)"
    (is (empty?
         (rules (str "(ns foo)\n(defn bar\n  \"Store one thing.\n\n"
                     "     (bar 1)\n     ;; => 1\"\n  [x] x)")))))

  (testing "prose showing the CALL and describing the return is CLEAN"
    (is (empty?
         (rules (str "(ns foo)\n(defn bar\n  \"Store one thing.\n\n"
                     "     (bar 1)  ; returns 1\"\n  [x] x)")))))

  (testing "the value-VOCABULARY glyphs («» ⟨⟩ ‹›) are NOT reserved, not flagged"
    (is (empty?
         (rules (str "(ns foo)\n(defn bar\n  \"Store one thing.\n\n"
                     "     (bar 1)  ; returns «map: ok» ⟨812 tok⟩\"\n  [x] x)")))))

  (testing "a `:malli/schema [:=> …]` line in prose is not flagged"
    (is (empty?
         (rules (str "(ns foo)\n(defn bar\n  \"Store one thing.\n\n"
                     "     the schema is [:=> [:cat ::req] ::resp] here.\"\n  [x] x)"))))))

;;; ---------------------------------------------------------------------------
;;; Skips
;;; ---------------------------------------------------------------------------

(deftest private-fns-skipped-test
  (testing "defn- and ^:private are never linted"
    (is (empty? (rules "(ns foo)\n(defn- bar [x] x)")))
    (is (empty? (rules "(ns foo)\n(defn ^:private bar [x] x)")))))

(deftest non-defn-forms-skipped-test
  (testing "def, defmacro, defmethod, comments are ignored"
    (is (empty? (rules "(ns foo)\n(def x 1)\n(defmacro m [x] x)\n(defmethod f :k [x] x)")))))

(deftest test-and-internal-ns-skipped-test
  (testing "the shared leaf owns the complete structural policy"
    (is (true? (ns-name/hidden-ns-name? 'foo.internal.child)))
    (is (true? (ns-name/test-ns-name? "foo.bar-test")))
    (is (false? (ns-name/included-ns? 'foo.internal.child)))
    (is (false? (ns-name/included-ns? "foo.bar-test")))
    (is (true? (ns-name/included-ns? 'foo.bar))))

  (testing "*-test namespaces are skipped wholesale"
    (let [r (d/check-source {::d/source "(ns foo-test)\n(defn bar [x] x)"})]
      (is (true? (::d/skipped? r)))
      (is (empty? (::d/findings r)))))

  (testing "*.internal namespaces are skipped wholesale"
    (let [r (d/check-source {::d/source "(ns foo.internal)\n(defn bar [x] x)"})]
      (is (true? (::d/skipped? r)))
      (is (empty? (::d/findings r)))))

  (testing "explicit ::ns-name overrides parsing"
    (let [r (d/check-source {::d/source "(defn bar [x] x)" ::d/ns-name "foo.bar-test"})]
      (is (true? (::d/skipped? r))))))

;;; ---------------------------------------------------------------------------
;;; Robustness — CLJS constructs must not choke the parser
;;; ---------------------------------------------------------------------------

(deftest cljs-constructs-test
  (testing "#js literals parse without error"
    (is (= #{:missing-docstring}
           (rules "(ns foo)\n(defn bar [x] #js {:a x})"))))

  (testing "reader conditionals parse without error"
    (is (empty? (rules "(ns foo)\n(defn bar \"Ok.\" [x] #?(:cljs (js/foo x) :clj x))")))))

;;; ---------------------------------------------------------------------------
;;; Formatting + scan aggregation
;;; ---------------------------------------------------------------------------

(deftest format-findings-test
  (testing "formats a WARN header and per-finding lines"
    (let [findings (::d/findings (d/check-source {::d/source "(ns foo)\n(defn bar [x] x)"}))
          out (::d/formatted (d/format-findings {::d/findings findings}))]
      (is (str/includes? out "WARN"))
      (is (str/includes? out "non-blocking"))
      (is (str/includes? out "bar"))))

  (testing "truncates to max-length"
    (let [findings (vec (repeat 50 {::d/fn-name "x" ::d/rule :missing-docstring
                                    ::d/line 1 ::d/message "x: public fn has no docstring"}))
          out (::d/formatted (d/format-findings {::d/findings findings ::d/max-length 120}))]
      (is (<= (count out) 120))
      (is (str/includes? out "truncated")))))

(deftest scan-test
  (testing "scan aggregates counts and annotates findings with the path"
    (let [dir (java.io.File. "tmp/docstring-scan-test")
          _ (.mkdirs dir)
          clean (str dir "/clean.clj")
          dirty (str dir "/dirty.clj")
          skip (str dir "/thing_test.clj")]
      (spit clean "(ns clean-src)\n(defn a \"Ok.\" [x] x)")
      (spit dirty "(ns dirty-src)\n(defn b [x] x)\n(defn c \"no dot\" [x] x)")
      (spit skip "(ns thing-test)\n(defn d [x] x)")
      (let [r (d/scan {::d/file-paths [clean dirty skip]})]
        (is (= 2 (::d/file-count r)) "test ns skipped")
        (is (= 3 (::d/fn-count r)))
        (is (= 2 (::d/finding-count r)))
        (is (= {:missing-docstring 1 :no-terminal-punctuation 1} (::d/by-rule r)))
        (is (every? #(str/includes? (::d/file-path %) "dirty")
                    (::d/findings r)))))))

;;; ---------------------------------------------------------------------------
;;; The corpus is real — proves it runs on live source
;;; ---------------------------------------------------------------------------

(deftest lints-its-own-source-clean-test
  (testing "this linter's own namespace passes its own rules"
    (let [r (d/check-file {::d/file-path "src/seon/dev/docstring.clj"})]
      (is (true? (::d/clean? r))
          (str "self-lint findings: " (pr-str (::d/findings r)))))))
