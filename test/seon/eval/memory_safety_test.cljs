(ns seon.eval.memory-safety-test
  "Store-time memory-safety caps for the agent eval/turn datoms.

   An agent eval once returned a 9.7M-char `pull [*]` result that was
   stored verbatim as `:seon.eval/result-edn`; a later whole-DB
   `[?e ?a ?v]` scan materialized every bloated datom at once and
   OOM-killed the Node pod (losing the in-RAM `:memory` DB). These tests
   pin the store-time complement to the render cap:

   - `cap-edn` bounds any pr-str'd string persisted as a datom
     (`:seon.eval/result-edn`, `:seon.eval/error`). (The turn prompt no
     longer flows through cap-edn — it persists WHOLE as a
     logs/prompts/<agent>/<turn>.txt blob with chars/file datom
     projections, 2026-06-09.)
   - the FULL value still lives in the globalThis live-result stash that
     backs the `result/<id>` var, so the un-capped value stays available
     in-session even when the persisted datom is bounded.
   - normal small results are stored verbatim (no spurious truncation).

   Run interactively via MCP eval:

     (require 'seon.eval.memory-safety-test :reload)
     (cljs.test/run-tests 'seon.eval.memory-safety-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.eval :as seval]))

;; ---------------------------------------------------------------------------
;; cap-edn — the store-time chokepoint for eval datoms (record-eval!'s
;; :seon.eval/result-edn + :seon.eval/error). Turn prompts no longer
;; route through it (blob file + projection datoms since 2026-06-09).
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
;; datom must NOT break the `result/<id>` var, whose runtime value is the
;; raw object read off globalThis (written by stash-result-raw!).
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
;; render-result-edn — the agent-facing text. Delegates to
;; `seon.render.value/render-ai`: a DEPTH- and BREADTH-bounded skeleton, so
;; a broad query returning thousands of tuples never becomes a char-clipped
;; giant blob. We pin the memory-safety MECHANISM (bounded, partial-view
;; hint, names result/<id>), not exact skeleton strings.
;; ---------------------------------------------------------------------------

(deftest small-collection-renders-verbatim-no-partial-hint
  (let [edn (seval/render-result-edn "ev00000001" (vec (range 5)))]
    (is (= "[0 1 2 3 4]" edn) "a small coll renders verbatim")
    (is (not (str/includes? edn "partial view")) "no false-positive hint")))

(deftest scalar-map-renders-verbatim-no-partial-hint
  (let [edn (seval/render-result-edn "ev00000002" {:seon.demo/x 1 :seon.demo/y 2})]
    (is (not (str/includes? edn "partial view")))
    (is (str/includes? edn ":seon.demo/x"))))

(deftest many-row-result-is-bounded-and-names-the-live-var
  (let [eval-id "ev00000003"
        total   5000
        edn     (seval/render-result-edn eval-id (vec (range total)))]
    (testing "output is BOUNDED — never a stringified 5000-element vector"
      (is (< (count edn) 2000))
      ;; the LAST shown element is far below the total — breadth-bounded
      (is (not (str/includes? edn (str (dec total))))))
    (testing "partial-view hint points the agent at the full live value"
      (is (str/includes? edn "partial view"))
      (is (str/includes? edn (str "result/" eval-id)))
      (is (str/includes? edn "get-in")))))

;; A datahike DB value is a record carrying :max-tx; stand in with one.
(defrecord FakeStoreDB [max-tx max-eid])

(deftest opaque-handle-result-is-a-compact-marker-not-a-blob
  ;; A datahike-shaped handle returned by an eval must render as a compact
  ;; marker, never a multi-KB index dump.
  (let [edn (seval/render-result-edn "ev00000005" {:seon.demo/db (->FakeStoreDB 42 99)})]
    (is (< (count edn) 400) "marker is compact")
    (is (str/includes? edn "seon.demo/db"))
    (is (str/includes? edn "datahike/DB"))))

(deftest bounded-result-survives-the-store-cap
  ;; render-result-edn output still flows through cap-edn at the write site;
  ;; the bounded skeleton + its hint must survive that too (a no-op here,
  ;; since render-ai already keeps the output well under store-edn-cap).
  (let [eval-id "ev00000004"
        edn     (seval/render-result-edn eval-id (vec (range 9000)))
        capped  (seval/cap-edn edn)]
    (is (= edn capped) "bounded render is already under the store cap")
    (is (str/includes? capped (str "result/" eval-id)))))

;; (The old `huge-prompt-is-stored-capped` test is RETIRED with the
;; :seon.agent.turn/prompt-text datom itself — prompts now persist whole as
;; logs/prompts blobs; only the int char-count + file path are datoms,
;; so there is nothing to cap on that path anymore.)
