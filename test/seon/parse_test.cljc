(ns seon.parse-test
  "Corpus tests for `seon.parse/parse-forms`. CLJC so both JVM
   (`bin/test seon.parse-test`) and the CLJS pod can exercise it
   from the same file.

   Test design: each `def` below holds a vector of `{:in :expected
   :note}` maps. The `deftest`s `doseq` over their corpus + `is`-
   compare extraction. New shapes the agent's LLM produces in the
   wild should add entries here as bugs surface — the corpus is
   the contract."
  (:require
    #?(:clj  [clojure.test :as t :refer [deftest is testing]]
       :cljs [cljs.test    :as t :refer [deftest is testing]])
    [seon.parse :as parse]))

;; ============================================================
;; Basic shapes — happy path
;; ============================================================

(def basic-cases
  [{:in "(+ 1 2)"
    :expected [{:kind :form
                :narration ""
                :source "(+ 1 2)"
                :form '(+ 1 2)}]
    :note "single bare form"}

   {:in ";; narration\n(+ 1 2)"
    :expected [{:kind :form
                :narration "narration"
                :source "(+ 1 2)"
                :form '(+ 1 2)}]
    :note "comment attaches to following form"}

   {:in ";; line 1\n;; line 2\n(foo)"
    :expected [{:kind :form
                :narration "line 1\nline 2"
                :source "(foo)"
                :form '(foo)}]
    :note "consecutive comments accumulate"}

   {:in "(+ 1 2)\n(+ 3 4)"
    :expected [{:kind :form :narration "" :source "(+ 1 2)" :form '(+ 1 2)}
               {:kind :form :narration "" :source "(+ 3 4)" :form '(+ 3 4)}]
    :note "multiple forms, no narration"}

   {:in ";; first\n(a)\n;; second\n(b)"
    :expected [{:kind :form :narration "first"  :source "(a)" :form '(a)}
               {:kind :form :narration "second" :source "(b)" :form '(b)}]
    :note "per-form narration"}

   {:in ""
    :expected []
    :note "empty text"}

   {:in ";; trailing comment with no form"
    :expected []
    :note "comments without trailing form are dropped"}])

(deftest basic-shapes
  (doseq [{:keys [in expected note]} basic-cases]
    (testing (str note " — " (pr-str in))
      (is (= expected (parse/parse-forms in))))))

;; ============================================================
;; Byte-faithful :source — load-bearing for resume re-eval
;; ============================================================

(def byte-faithful-cases
  [{:in "(defn foo [x] x)"
    :expected-source "(defn foo [x] x)"
    :note "canonical defn"}

   {:in "(defn  foo  [x]\n  x)"
    :expected-source "(defn  foo  [x]\n  x)"
    :note "preserves multi-line + extra whitespace"}

   {:in "(seon.db/transact!\n  {:seon.db/tx-data\n   [{:foo/bar 1}]})"
    :expected-source "(seon.db/transact!\n  {:seon.db/tx-data\n   [{:foo/bar 1}]})"
    :note "preserves indentation across multi-line maps"}

   {:in "#{:a :b :c}"
    :expected-source "#{:a :b :c}"
    :note "set literal"}

   {:in "@!atom-ref"
    :expected-source "@!atom-ref"
    :note "reader macro"}

   {:in "(let [x #(+ % 1)] (x 41))"
    :expected-source "(let [x #(+ % 1)] (x 41))"
    :note "fn literal"}])

(deftest source-is-byte-faithful
  (doseq [{:keys [in expected-source note]} byte-faithful-cases]
    (testing (str note " — " (pr-str in))
      (let [entries (parse/parse-forms in)]
        (is (= 1 (count entries)))
        (is (= expected-source (:source (first entries))))))))

;; ============================================================
;; Prose-symbol filter — LLM emits bare symbols when it forgets it's
;; emitting code. Reader tokenizes them; we drop them at parse time.
;; ============================================================

(def prose-cases
  [{:in "Let me think (+ 1 2)"
    :note "prose before form — bare symbols dropped, form kept"
    :form-count 1
    :first-form '(+ 1 2)}

   {:in "thinking thinking (+ 1 2)"
    :note "multiple bare symbols all dropped"
    :form-count 1
    :first-form '(+ 1 2)}

   {:in "(+ 1 2)\nokay\n(+ 3 4)"
    :note "bare symbol between forms — both real forms kept"
    :form-count 2}

   {:in ";; thinking\nokay (+ 1 2)"
    :note "narration survives bare symbol — attaches to next form"
    :form-count 1
    :first-narration "thinking"}])

(deftest prose-symbols-dropped
  (doseq [{:keys [in note form-count first-form first-narration]} prose-cases]
    (testing (str note " — " (pr-str in))
      (let [entries (parse/parse-forms in)
            forms   (filter #(= :form (:kind %)) entries)]
        (is (= form-count (count forms))
            (str "form-count mismatch for " (pr-str in)))
        (when first-form
          (is (= first-form (:form (first forms)))))
        (when first-narration
          (is (= first-narration (:narration (first forms)))))))))

;; ============================================================
;; Read-error recovery — bad form becomes a :read entry; subsequent
;; forms still parse.
;; ============================================================

(def recovery-cases
  [{:in "(unbalanced\n(good)"
    :note "unbalanced paren — bad span recorded, recovery to next form"
    :expected-kinds [:read :form]}

   {:in "(good)\n(unbalanced"
    :note "good form first, bad form last — both recorded"
    :expected-kinds [:form :read]}

   {:in "(a)\n(broken\n(b)"
    :note "bad form in the middle — forms before AND after kept"
    :expected-kinds [:form :read :form]}

   {:in "\"unterminated"
    :note "unterminated string — single :read entry, no forms"
    :expected-kinds [:read]}

   {:in "(a)\n#unknown-tag value\n(b)"
    :note "unknown reader tag — recovers to next column-0 form"
    :expected-kinds-contain [:form :form]}])

(deftest read-failures-isolated
  (doseq [{:keys [in note expected-kinds expected-kinds-contain]} recovery-cases]
    (testing (str note " — " (pr-str in))
      (let [entries (parse/parse-forms in)
            kinds   (mapv :kind entries)]
        (when expected-kinds
          (is (= expected-kinds kinds)
              (str "kinds mismatch: got " (pr-str kinds))))
        (when expected-kinds-contain
          (is (every? (set kinds) expected-kinds-contain)
              (str "expected kinds " (pr-str expected-kinds-contain)
                   " all present, got " (pr-str kinds))))
        ;; Every :read entry must have :ok? false + non-blank :source + :error
        (doseq [e entries :when (= :read (:kind e))]
          (is (false? (:ok? e)))
          (is (string? (:source e)))
          (is (string? (:error e))))))))

;; ============================================================
;; Narration semantics on recovery — narration accumulated before a
;; bad form attaches to the :read entry, not to the next good form.
;; ============================================================

(deftest narration-attaches-to-failure-not-next-good
  (let [entries (parse/parse-forms ";; about-to-fail\n(unbalanced\n;; about-next-good\n(good)")
        read-entry (first (filter #(= :read (:kind %)) entries))
        form-entry (first (filter #(= :form (:kind %)) entries))]
    (is (some? read-entry))
    (is (= "about-to-fail" (:narration read-entry)))
    (is (some? form-entry))
    (is (= "about-next-good" (:narration form-entry)))))
