(ns seon.eval.memory-safety-test
  "Store-time memory-safety caps for the agent eval/turn datoms.

   An agent eval once returned a 9.7M-char `pull [*]` result that was
   stored verbatim as `:seon.eval/result-edn`; a later whole-DB
   `[?e ?a ?v]` scan materialized every bloated datom at once and
   OOM-killed the Node pod (losing the in-RAM `:memory` DB). These tests
   pin the store-time complement to the render cap:

   - `cap-edn` bounds any pr-str'd string persisted as a datom
     (`:seon.eval/result-edn`, `:seon.eval/error`, `:seon.turn/prompt-text`).
   - the FULL value still lives in the globalThis live-result stash, so
     `(result <id>)` keeps returning the un-capped value in-session.
   - normal small results are stored verbatim (no spurious truncation).

   Run interactively via MCP eval:

     (require 'seon.eval.memory-safety-test :reload)
     (cljs.test/run-tests 'seon.eval.memory-safety-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing]]
    [seon.eval :as seval]))

;; ---------------------------------------------------------------------------
;; cap-edn — the single store-time chokepoint both write sites call
;; (record-eval! for :seon.eval/result-edn + :seon.eval/error,
;;  with-turn! for :seon.turn/prompt-text).
;; ---------------------------------------------------------------------------

(deftest store-edn-cap-is-a-sane-positive-bound
  ;; ~10x the render cap (1500), ~600x below the 9.7M blob that OOM'd.
  (is (= 16384 seval/store-edn-cap))
  (is (pos? seval/store-edn-cap)))

(deftest cap-edn-truncates-a-huge-string-with-an-elision-marker
  (let [huge   (apply str (repeat (* 5 1024 1024) "x")) ; 5 MB string
        capped (seval/cap-edn huge)]
    (testing "stored string is bounded — never the multi-MB original"
      (is (<= (count capped)
              (+ seval/store-edn-cap 64))
          "capped length is the cap plus a short elision marker"))
    (testing "the kept prefix is the head of the original value"
      (is (= (subs huge 0 seval/store-edn-cap)
             (subs capped 0 seval/store-edn-cap))))
    (testing "elision marker reports the dropped count"
      (is (re-find #"⟨\d+ chars elided⟩" capped))
      (is (re-find (re-pattern (str "⟨"
                                    (- (count huge) seval/store-edn-cap)
                                    " chars elided⟩"))
                   capped)))))

(deftest cap-edn-leaves-a-normal-small-result-verbatim
  ;; Regression: the cap must not truncate ordinary results.
  (let [small (pr-str {:seon.demo/total 42 :seon.demo/rows [1 2 3]})]
    (is (< (count small) seval/store-edn-cap))
    (is (= small (seval/cap-edn small))
        "small string passes through unchanged — no marker, no truncation")
    (is (not (re-find #"chars elided" (seval/cap-edn small))))))

(deftest cap-edn-is-nil-safe-and-stringifies
  ;; record-eval!'s pr-str fallback can hand cap-edn non-strings.
  (is (= "" (seval/cap-edn nil)))
  (is (= "123" (seval/cap-edn 123))))

(deftest cap-edn-honours-an-explicit-limit
  (is (= "abc …⟨2 chars elided⟩" (seval/cap-edn "abcde" 3)))
  (is (= "abc" (seval/cap-edn "abc" 3))))

;; ---------------------------------------------------------------------------
;; The live-result stash is SEPARATE from the persisted datom. Capping the
;; datom must NOT break `(result <id>)`, which reads the raw value off
;; globalThis via stash-result-raw!.
;; ---------------------------------------------------------------------------

(deftest live-stash-returns-the-full-value-even-when-the-datom-would-be-capped
  (let [eval-id   "memsafe0001"
        big-value (apply str (repeat (* 5 1024 1024) "y")) ; 5 MB raw value
        ;; what record-eval! WOULD persist for this value:
        persisted (seval/cap-edn (pr-str big-value))]
    ;; stash the raw value the way eval-batch! does (before record-eval!)
    (seval/stash-result-raw! eval-id big-value)
    (try
      (testing "persisted datom is bounded"
        (is (<= (count persisted) (+ seval/store-edn-cap 64)))
        (is (< (count persisted) (count big-value))))
      (testing "live stash still holds the FULL, un-capped value"
        (let [stashed (js/Reflect.get
                        js/globalThis
                        (str "__seon_results_" eval-id))]
          (is (= big-value stashed))
          (is (= (count big-value) (count stashed)))))
      (finally
        (js/Reflect.deleteProperty
          js/globalThis (str "__seon_results_" eval-id))))))

;; ---------------------------------------------------------------------------
;; prompt-text path — with-turn! now passes prompt-text through cap-edn.
;; ---------------------------------------------------------------------------

(deftest huge-prompt-is-stored-capped
  (let [huge-prompt (apply str (repeat (* 2 1024 1024) "p")) ; 2 MB prompt
        stored      (seval/cap-edn huge-prompt)]
    (is (<= (count stored) (+ seval/store-edn-cap 64)))
    (is (re-find #"chars elided" stored))
    (is (< (count stored) (count huge-prompt)))))
