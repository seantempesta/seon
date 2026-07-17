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
   - the live `result/<id>` store admits only bounded immutable values;
     overweight, lazy, and opaque values become compact descriptors.
   - normal small results are stored verbatim (no spurious truncation).

   Run interactively via MCP eval:

     (require 'seon.eval.memory-safety-test :reload)
     (cljs.test/run-tests 'seon.eval.memory-safety-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing]]
    [clojure.string :as str]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.eval :as seval]))

(def configuration (config/resolve-config-singleton {}))
(def database-edn-cap (config/database-edn-cap configuration))

(defn- reported-elision-tokens
  "The token estimate carried by a generated cap marker, or nil."
  [s]
  (some-> (re-find #"⟨(\d+) tokens elided⟩" s)
          second
          (js/parseInt 10)))

(defn- raw-text-size-unit?
  "True when generated reporting text exposes a numeric raw text-size unit."
  [s]
  (boolean (re-find #"(?i)\d+\s*(?:chars?|characters?|bytes?|[kmg]b)\b" s)))

;; ---------------------------------------------------------------------------
;; cap-edn — the store-time chokepoint for eval datoms (record-eval!'s
;; :seon.eval/result-edn + :seon.eval/error). Turn prompts no longer
;; route through it (blob file + projection datoms since 2026-06-09).
;; ---------------------------------------------------------------------------

(deftest database-edn-cap-is-a-sane-positive-bound
  ;; ~10x the render cap (1500), ~600x below the 9.7M blob that OOM'd.
  (is (= 16384 database-edn-cap))
  (is (pos? database-edn-cap)))

(deftest cap-edn-truncates-a-huge-string-with-an-elision-marker
  (let [huge   (apply str (repeat (* 5 1024 1024) "x")) ; 5 MB string
        capped (seval/cap-edn huge database-edn-cap)
        marker (subs capped database-edn-cap)]
    (testing "stored string is bounded — never the multi-MB original"
      (is (<= (count capped)
              (+ database-edn-cap 64))
          "capped length is the cap plus a short elision marker"))
    (testing "the kept prefix is the head of the original value"
      (is (= (subs huge 0 database-edn-cap)
             (subs capped 0 database-edn-cap))))
    (testing "the generated marker reports the canonical omitted-token estimate"
      (is (= (tokens/chars->tokens (- (count huge) database-edn-cap))
             (reported-elision-tokens marker)))
      (is (false? (raw-text-size-unit? marker))))))

(deftest cap-edn-leaves-a-normal-small-result-verbatim
  ;; Regression: the cap must not truncate ordinary results.
  (let [small (pr-str {:seon.demo/total 42 :seon.demo/rows [1 2 3]})]
    (is (< (count small) database-edn-cap))
    (is (= small (seval/cap-edn small database-edn-cap))
        "small string passes through unchanged — no marker, no truncation")
    (is (nil? (reported-elision-tokens
                (seval/cap-edn small database-edn-cap))))))

(deftest cap-edn-is-nil-safe-and-stringifies
  ;; record-eval!'s pr-str fallback can hand cap-edn non-strings.
  (is (= "" (seval/cap-edn nil database-edn-cap)))
  (is (= "123" (seval/cap-edn 123 database-edn-cap))))

(deftest cap-edn-honours-an-explicit-limit
  (let [source "abcdefghijk"
        capped (seval/cap-edn source 3)]
    (is (str/starts-with? capped "abc"))
    (is (= (tokens/chars->tokens (- (count source) 3))
           (reported-elision-tokens (subs capped 3))))
    (is (false? (raw-text-size-unit? (subs capped 3)))))
  (is (= "abc" (seval/cap-edn "abc" 3))))

;; ---------------------------------------------------------------------------
;; The live-result store is SEPARATE from the persisted datom, but it has its
;; own structural admission budget. Small values round-trip identically;
;; overweight values leave only a bounded descriptor in `result/<id>`.
;; ---------------------------------------------------------------------------

(deftest one-live-result-slot-rejects-an-overweight-value
  (let [eval-id       "mem-safe-0001"
        big-value     (apply str (repeat (* 5 1024 1024) "y"))
        compile-state (atom {:cljs.analyzer/namespaces {}})
        prior-results (js/Reflect.get js/globalThis
                                      (str seval/result-ns-sym))
        legacy-key    (str "__seon_results_" eval-id)
        ;; what record-eval! WOULD persist for this value:
        persisted (seval/cap-edn (pr-str big-value) database-edn-cap)]
    ;; Isolate the reserved runtime namespace without a parallel key mirror. Its
    ;; enumerable properties are the live-result authority and eviction order.
    (js/Reflect.set js/globalThis (str seval/result-ns-sym)
                    (js/Object.create nil))
    (seval/bind-result-var! compile-state eval-id big-value)
    (try
      (testing "persisted datom is bounded"
        (is (<= (count persisted) (+ database-edn-cap 64)))
        (is (< (count persisted) (count big-value))))
      (testing "the public var and internal lookup share one bounded descriptor"
        (let [retained (seval/lookup-result eval-id)]
          (is (false? (:seon.eval/retained? retained)))
          (is (= :seon.eval/weight-cap-exceeded
                 (:seon.eval/retained-reason retained)))
          (is (< (count (pr-str retained)) 1000))
          (is (= retained (seval/lookup-result (keyword eval-id)))))
        (is (contains?
              (get-in @compile-state
                      [:cljs.analyzer/namespaces seval/result-ns-sym :defs])
              (symbol eval-id))))
      (testing "the retired unbounded property is not recreated"
        (is (false? (js/Reflect.has js/globalThis legacy-key))))
      (finally
        ((deref #'seval/unbind-result-var!) compile-state eval-id)
        (if prior-results
          (js/Reflect.set js/globalThis (str seval/result-ns-sym)
                          prior-results)
          (js/Reflect.deleteProperty js/globalThis
                                     (str seval/result-ns-sym)))))))

(deftest small-live-result-round-trips-identically
  (let [value {:seon.demo/total 42
               :seon.demo/rows [1 2 3]}]
    (is (identical? value (seval/admit-result-value value)))))

(deftest structural-admission-stops-before-unbounded-work
  (testing "an already-wide value is rejected by node count"
    (is (= :seon.eval/node-cap-exceeded
           (:seon.eval/retained-reason
             (seval/admit-result-value (vec (range 5000)))))))
  (testing "a lazy sequence is rejected without walking its tail"
    (is (= :seon.eval/unbounded-collection
           (:seon.eval/retained-reason
             (seval/admit-result-value (iterate inc 0)))))))

;; ---------------------------------------------------------------------------
;; render-result-edn — the agent-facing text. Delegates to
;; `seon.render.value/render-ai`: a DEPTH- and BREADTH-bounded skeleton, so
;; a broad query returning thousands of tuples never becomes a char-clipped
;; giant blob. We pin the memory-safety MECHANISM (bounded, partial-view
;; hint, names result/<id>), not exact skeleton strings.
;; ---------------------------------------------------------------------------

(deftest small-collection-renders-verbatim-no-partial-hint
  (let [edn (seval/render-result-edn configuration "ev00000001"
                                      (vec (range 5)))]
    (is (= "[0 1 2 3 4]" edn) "a small coll renders verbatim")
    (is (not (str/includes? edn "partial view")) "no false-positive hint")))

(deftest scalar-map-renders-verbatim-no-partial-hint
  (let [edn (seval/render-result-edn configuration "ev00000002"
                                      {:seon.demo/x 1 :seon.demo/y 2})]
    (is (not (str/includes? edn "partial view")))
    (is (str/includes? edn ":seon.demo/x"))))

(deftest many-row-result-is-bounded-and-names-the-live-var
  (let [eval-id "ev00000003"
        total   5000
        edn     (seval/render-result-edn configuration eval-id
                                          (vec (range total)))]
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
  (let [edn (seval/render-result-edn configuration "ev00000005"
                                      {:seon.demo/db (->FakeStoreDB 42 99)})]
    (is (< (count edn) 400) "marker is compact")
    (is (str/includes? edn "seon.demo/db"))
    (is (str/includes? edn "datahike/DB"))))

(deftest bounded-result-survives-the-store-cap
  ;; render-result-edn output still flows through cap-edn at the write site;
  ;; the bounded skeleton + its hint must survive that too (a no-op here,
  ;; since render-ai already keeps the output well under database-edn-cap).
  (let [eval-id "ev00000004"
        edn     (seval/render-result-edn configuration eval-id
                                          (vec (range 9000)))
        capped  (seval/cap-edn edn database-edn-cap)]
    (is (= edn capped) "bounded render is already under the store cap")
    (is (str/includes? capped (str "result/" eval-id)))))

;; (The old `huge-prompt-is-stored-capped` test is RETIRED with the
;; :seon.agent.turn/prompt-text datom itself — prompts now persist whole as
;; logs/prompts blobs; only the int char-count + file path are datoms,
;; so there is nothing to cap on that path anymore.)
