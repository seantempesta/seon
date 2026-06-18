(ns seon.repl.internal-test
  "Corpus tests for `seon.repl.internal/parse-forms`. CLJC so both JVM
   (`bin/test seon.repl.internal-test`) and the CLJS pod can exercise it
   from the same file.

   Test design: each `def` below holds a vector of `{:in :expected
   :note}` maps. The `deftest`s `doseq` over their corpus + `is`-
   compare extraction. New shapes the agent's LLM produces in the
   wild should add entries here as bugs surface — the corpus is
   the contract."
  (:require
    #?(:clj  [clojure.test :as t :refer [deftest is testing]]
       :cljs [cljs.test    :as t :refer [deftest is testing]])
    [seon.repl.internal :as parse]))

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
;; Narration-atom filter — the format contract: a form is `(...)`,
;; `[...]`, `{...}`, or a reader-macro form. Top-level bare atoms
;; (symbols, numbers, strings, keywords) are LLM prose tokenized by
;; the reader — NARRATION, never evaluated. Shapes below include the
;; exact mangles observed live (context-blind-spots-2026-06-11):
;; `24`, `88.`, the `", felt good…"` quote-fragment string, prose
;; sentences, echoed-prompt symbol lines.
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
    :first-narration "thinking"}

   {:in "24"
    :note "bare top-level number is narration, not an eval (observed: s21 sweep-3)"
    :form-count 0}

   {:in "88."
    :note "number with trailing dot is narration (observed: s32 sweep-1)"
    :form-count 0}

   {:in "\", felt good. Before I design a schema, I need to check whether a workout schema already exists\""
    :note "quote-fragment swallowed into a string literal is narration (the eaten consult intent)"
    :form-count 0}

   {:in "I ran this morning - 24 minutes, felt good."
    :note "whole prose sentence — symbols + number + comma all narration, NO entries at all"
    :form-count 0
    :entry-count 0}

   {:in ":ok"
    :note "bare top-level keyword is narration"
    :form-count 0}

   {:in "do it now"
    :note "special symbols (`do`) are atoms too — bare `do` is the English word"
    :form-count 0}

   {:in "my.agent.RnA-2606111546=>"
    :note "echoed prompt line tokenizes to a symbol — narration"
    :form-count 0}

   {:in "The plan:\n(+ 1 2)\nThat should work"
    :note "legitimate form sandwiched between prose lines — exactly one eval"
    :form-count 1
    :first-form '(+ 1 2)}

   {:in "{:seon.eval/ok? true, :seon.eval/result 3}"
    :note "echoed result map is a legal `{...}` form — still evals (harmless identity)"
    :form-count 1
    :first-form {:seon.eval/ok? true, :seon.eval/result 3}}])

(deftest narration-atoms-dropped
  (doseq [{:keys [in note form-count entry-count
                  first-form first-narration]} prose-cases]
    (testing (str note " — " (pr-str in))
      (let [entries (parse/parse-forms in)
            forms   (filter #(= :form (:kind %)) entries)]
        (is (= form-count (count forms))
            (str "form-count mismatch for " (pr-str in)))
        (when entry-count
          (is (= entry-count (count entries))
              (str "entry-count mismatch (a :read entry leaked?) for "
                   (pr-str in))))
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
    :expected-kinds-contain [:form :form]}

   {:in "(a)\nshe said \"felt good\n(b)"
    :note "odd quote in prose opens an unterminated string — reader error mid-text must not poison adjacent forms"
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

;; ============================================================
;; A.1 — prose-vs-code classification. A reader THROW on a prose token
;; (`80s`, `to:`, `detail:`, `v1.0`) must be DROPPED as narration, NOT
;; recorded as a `:read` failure — UNLESS the failing span has a
;; collection opener at its START (a genuinely broken FORM like
;; `(+ 1 3x)`). The opener-at-START rule is what keeps inline-code
;; prose ("I'll use (subs …) to format") classified as prose while
;; keeping a real broken form as broken code.
;; ============================================================

(def prose-token-cases
  [{:in "80s arcade/start screen."
    :note "Invalid number `80s` in prose — dropped, NO :read failure (FHb)"
    :entry-count 0}

   {:in "to:\n1.  Register the schema."
    :note "Invalid symbol `to:` in prose — dropped, NO :read failure (SpO)"
    :form-count 0
    :no-read? true}

   {:in "detail: The user said \"have the interface update\"."
    :note "Invalid symbol `detail:` in prose — dropped, NO :read failure (ZyJ)"
    :no-read? true}

   {:in "Version v1.0 shipped."
    :note "Invalid token `v1.0` mid-prose — dropped, NO :read failure"
    :no-read? true}

   {:in "I'll use (subs (str (js/Date.)) 11 19) to format the time."
    :note "(b) parenthetical-prose: opener MID-sentence → prose, NOT a :read failure [critique-flagged]"
    :no-read? true}

   {:in "(+ 1 3x)"
    :note "opener AT START + Invalid number `3x` → genuinely broken CODE, recorded as :read"
    :expected-kinds [:read]}

   {:in "80s arcade/start screen.\nThis should include:\n- Neon colors.\n;; Define the tile\n(defn my-tile [_] {:seon.render/hiccup [:div]})"
    :note "multi-line prose preamble dropped; comment narration kept; defn parses (episode turn-2)"
    :form-count 1
    :no-read? true
    :first-narration "Define the tile"}])

(deftest prose-tokens-dropped-not-read-failures
  (doseq [{:keys [in note entry-count form-count no-read?
                  expected-kinds first-narration]} prose-token-cases]
    (testing (str note " — " (pr-str in))
      (let [entries (parse/parse-forms in)
            kinds   (mapv :kind entries)
            forms   (filter #(= :form (:kind %)) entries)]
        (when expected-kinds
          (is (= expected-kinds kinds)
              (str "kinds mismatch: got " (pr-str kinds))))
        (when no-read?
          (is (not-any? #(= :read %) kinds)
              (str "a :read failure leaked for prose: " (pr-str kinds))))
        (when entry-count
          (is (= entry-count (count entries))
              (str "entry-count mismatch: " (pr-str entries))))
        (when form-count
          (is (= form-count (count forms))
              (str "form-count mismatch: " (pr-str kinds))))
        (when first-narration
          (is (= first-narration (:narration (first forms)))))))))
