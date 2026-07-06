(ns seon.agent.fs.match-test
  "The deterministic matching cascade — the falsification battery.

   The invariant under test: smart matching may FIND candidates, but only
   DETERMINISTIC matching MUTATES. Every ambiguity must REFUSE with
   line-numbered candidates rather than guess a location — the slice-3
   failure mode (a botched edit landing mid-function) is exactly what a
   wrong-place mutation would reproduce. `.cljc` so the JVM gold-patch
   replay harness drives the same pure code."
  (:require
    [seon.agent.fs.match :as match]
    [malli.core :as m]
    #?(:clj  [clojure.test :refer [deftest is testing]]
       :cljs [cljs.test :refer [deftest is testing]])))

(defn- decide [m] (match/decide m))

;; ============================================================
;; Stage 1 — exact, unique.
;; ============================================================

(deftest exact-unique-match-applies
  (let [d (decide {:seon.agent.fs.match/content "aaa\nbbb\nccc\n"
                   :seon.agent.fs.match/find    "bbb"
                   :seon.agent.fs.match/replace "BBB"})]
    (is (= :apply (:seon.agent.fs.match/action d)))
    (is (= :seon.agent.fs.match/exact (:seon.agent.fs.match/stage d)))
    (is (= [[2 2]] (:seon.agent.fs.match/ranges d)))
    (is (= "aaa\nBBB\nccc\n" (:seon.agent.fs.match/new-content d))
        "only the matched line changed; every other byte preserved")
    (is (= [2 2] (:seon.agent.fs.match/range-after d)))
    (is (= 1 (:seon.agent.fs.match/lines-added d)))
    (is (= 1 (:seon.agent.fs.match/lines-removed d)))
    (is (= [] (:seon.agent.fs.match/normalizations d)))))

(deftest exact-multiline-find-preserves-surrounding-bytes
  (let [d (decide {:seon.agent.fs.match/content "def f():\n    return 1\n\nx = 2\n"
                   :seon.agent.fs.match/find    "def f():\n    return 1"
                   :seon.agent.fs.match/replace "def f():\n    return 42"})]
    (is (= :apply (:seon.agent.fs.match/action d)))
    (is (= "def f():\n    return 42\n\nx = 2\n"
           (:seon.agent.fs.match/new-content d)))
    (is (= [1 2] (:seon.agent.fs.match/range-after d)))))

;; ============================================================
;; Stage 4 — multi-match REFUSAL with candidates + previews.
;; ============================================================

(deftest multi-match-refuses-with-line-numbered-candidates
  (let [d (decide {:seon.agent.fs.match/content "x\nsame\ny\nsame\nz\nsame\n"
                   :seon.agent.fs.match/find    "same"})]
    (is (= :fail (:seon.agent.fs.match/action d)))
    (is (= :seon.agent.fs.match/ambiguous (:seon.agent.fs.match/reason d)))
    (is (nil? (:seon.agent.fs.match/new-content d)) "REFUSED — nothing mutated")
    (let [cands (:seon.agent.fs.match/candidates d)]
      (is (= 3 (count cands)) "every occurrence is offered")
      (is (= [[2 2] [4 4] [6 6]] (mapv :seon.agent.fs.match/range cands)))
      (is (every? #(re-find #"\d+\t" (:seon.agent.fs.match/preview %)) cands)
          "each candidate carries a line-numbered preview (N<tab>…)")
      (is (re-find #"2\tsame" (:seon.agent.fs.match/preview (first cands)))
          "the preview line-numbers the actual matched line"))))

;; ============================================================
;; Stage 2 — the ::near window narrows an otherwise-ambiguous find.
;; ============================================================

(deftest near-window-rescues-an-ambiguous-find
  (let [content "same\na\nsame\nb\nsame\n"
        d (decide {:seon.agent.fs.match/content content
                   :seon.agent.fs.match/find    "same"
                   :seon.agent.fs.match/replace "DONE"
                   :seon.agent.fs.match/near    [3 3]})]
    (is (= :apply (:seon.agent.fs.match/action d)))
    (is (= :seon.agent.fs.match/exact-near (:seon.agent.fs.match/stage d)))
    (is (= [[3 3]] (:seon.agent.fs.match/ranges d)) "only the windowed one")
    (is (= "same\na\nDONE\nb\nsame\n" (:seon.agent.fs.match/new-content d)))))

(deftest near-window-that-still-ambiguous-refuses
  (let [d (decide {:seon.agent.fs.match/content "same\nsame\nx\nsame\n"
                   :seon.agent.fs.match/find    "same"
                   :seon.agent.fs.match/near    [1 2]})]
    (is (= :fail (:seon.agent.fs.match/action d))
        "two matches inside the window → still refuse, never pick one")
    (is (= :seon.agent.fs.match/ambiguous (:seon.agent.fs.match/reason d)))))

;; ============================================================
;; expected-count — change ALL N occurrences deterministically.
;; ============================================================

(deftest expected-count-two-changes-both
  (let [d (decide {:seon.agent.fs.match/content "same\nx\nsame\n"
                   :seon.agent.fs.match/find    "same"
                   :seon.agent.fs.match/replace "DONE"
                   :seon.agent.fs.match/expected-count 2})]
    (is (= :apply (:seon.agent.fs.match/action d)))
    (is (= [[1 1] [3 3]] (:seon.agent.fs.match/ranges d)))
    (is (= "DONE\nx\nDONE\n" (:seon.agent.fs.match/new-content d)))
    (is (= 2 (:seon.agent.fs.match/lines-added d)))
    (is (= 2 (:seon.agent.fs.match/lines-removed d)))))

(deftest expected-count-mismatch-refuses
  (let [d (decide {:seon.agent.fs.match/content "same\nsame\nsame\n"
                   :seon.agent.fs.match/find    "same"
                   :seon.agent.fs.match/expected-count 2})]
    (is (= :fail (:seon.agent.fs.match/action d))
        "3 present but 2 expected → refuse; never partial-apply")))

;; ============================================================
;; Stage 3 — conservative normalization ONLY. Indentation is sacred.
;; ============================================================

(deftest crlf-only-normalization-hits-and-is-flagged
  ;; the file is CRLF, the agent's find uses LF — a multi-line anchor so it
  ;; is NOT an exact byte-substring (a clean single word always would be).
  (let [d (decide {:seon.agent.fs.match/content "alpha\r\nbeta\r\ngamma\r\n"
                   :seon.agent.fs.match/find    "alpha\nbeta"
                   :seon.agent.fs.match/replace "A\nB"})]
    (is (= :apply (:seon.agent.fs.match/action d)))
    (is (= :seon.agent.fs.match/normalized (:seon.agent.fs.match/stage d)))
    (is (= [[1 2]] (:seon.agent.fs.match/ranges d)))
    (is (some #{:seon.agent.fs.match/crlf} (:seon.agent.fs.match/normalizations d))
        "the CRLF normalization is reported, not hidden")))

(deftest trailing-whitespace-normalization-hits-and-is-flagged
  ;; the FIND carries trailing spaces the file line lacks (so it is not an
  ;; exact substring); only trailing-ws normalization bridges the gap.
  (let [d (decide {:seon.agent.fs.match/content "alpha\nbeta\ngamma\n"
                   :seon.agent.fs.match/find    "beta   "
                   :seon.agent.fs.match/replace "BETA"})]
    (is (= :apply (:seon.agent.fs.match/action d)))
    (is (= :seon.agent.fs.match/normalized (:seon.agent.fs.match/stage d)))
    (is (some #{:seon.agent.fs.match/trailing-ws}
              (:seon.agent.fs.match/normalizations d)))))

(deftest indentation-difference-is-REFUSED-not-normalized
  ;; the load-bearing safety property: LEADING whitespace is meaning in
  ;; Python/YAML. The file is TAB-indented, the find SPACE-indented — not
  ;; a byte-substring, and the line-based normalizer strips only TRAILING
  ;; whitespace, so nothing bridges the gap. It must REFUSE.
  (let [d (decide {:seon.agent.fs.match/content "def f():\n\treturn 1\n"
                   :seon.agent.fs.match/find    "    return 1"   ; spaces vs a tab
                   :seon.agent.fs.match/replace "    return 2"})]
    (is (= :fail (:seon.agent.fs.match/action d))
        "leading indentation is never normalized away — REFUSE")
    (is (nil? (:seon.agent.fs.match/new-content d)))))

;; ============================================================
;; not-found — no candidates when there is genuinely nothing close.
;; ============================================================

(deftest not-found-is-a-clean-refusal
  (let [d (decide {:seon.agent.fs.match/content "alpha\nbeta\n"
                   :seon.agent.fs.match/find    "nowhere"})]
    (is (= :fail (:seon.agent.fs.match/action d)))
    (is (= :seon.agent.fs.match/not-found (:seon.agent.fs.match/reason d)))
    (is (= [] (:seon.agent.fs.match/candidates d)))))

(deftest not-found-surfaces-normalization-near-misses-only
  ;; the find carries trailing spaces the file line lacks, so it is NOT an
  ;; exact substring; with expected-count 2 the single normalized near-miss
  ;; can't satisfy the count → not-found, offering that near-miss to copy.
  (let [d (decide {:seon.agent.fs.match/content "alpha\nbeta\n"
                   :seon.agent.fs.match/find    "alpha  "
                   :seon.agent.fs.match/expected-count 2})]
    (is (= :fail (:seon.agent.fs.match/action d)))
    (is (= :seon.agent.fs.match/not-found (:seon.agent.fs.match/reason d)))
    (is (= 1 (count (:seon.agent.fs.match/candidates d)))
        "the trailing-ws near-miss is offered, nothing fuzzy")))

;; ============================================================
;; empty find — rejected by the request schema (never a silent match).
;; ============================================================

(deftest empty-find-is-rejected-by-schema
  (is (false? (m/validate :seon.agent.fs.match/decide-request
                          {:seon.agent.fs.match/content "x\n"
                           :seon.agent.fs.match/find    ""}))
      "::find is [:string {:min 1}] — an empty anchor is a schema violation")
  (is (true? (m/validate :seon.agent.fs.match/decide-request
                         {:seon.agent.fs.match/content "x\n"
                          :seon.agent.fs.match/find    "x"}))))

;; ============================================================
;; number-lines — the ONE shared formatter (view + previews).
;; ============================================================

(deftest number-lines-right-aligns-and-tabs
  (is (= " 9\tnine\n10\tten"
         (match/number-lines ["nine" "ten"] 9))
      "widths align to the largest line number; N<tab> prefix")
  (is (= "" (match/number-lines [] 1) )
      "an empty window numbers to the empty string"))
