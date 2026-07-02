(ns seon.agent.ctx.transcript-test
  "Transcript age-banding (config-init CP-5, #62) — the `::tiers` +
   `::turns-retained` window over the event stream. Pure-data tests on
   [[clip-events-by-tiers]] / [[tier-cap-for-turn]]: empty tiers = render-all
   (byte-parity); a configured schedule evicts old evals past their tier budget
   while keeping the retained window verbatim and messages always.

   Run: bin/test-cljs, or (cljs.test/run-tests 'seon.agent.ctx.transcript-test)."
  (:require
    [cljs.test :refer [deftest is testing]]
    [seon.agent.ctx.transcript :as t]))

(defn- ev
  ([turn kind] (ev turn kind nil))
  ([turn kind edn]
   (cond-> {::t/turn-idx turn ::t/kind kind}
     edn (assoc ::t/entity {:seon.eval/result-edn edn}))))

(def ^:private stream
  ;; 6 turns (0..5): a big old eval, a message, and recent evals.
  [(ev 0 :eval "old-eval-turn0-body-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
   (ev 1 :message)
   (ev 1 :eval "e1")
   (ev 4 :eval "e4")
   (ev 5 :eval "e5-recent")])

(deftest empty-tiers-render-all-byte-parity
  (testing "no tiers → the event stream is returned UNCHANGED (today's :none)"
    (is (= stream (t/clip-events-by-tiers [] 8 stream)))
    (is (= stream (t/clip-events-by-tiers [] 0 stream)))))

(deftest tier-cap-for-turn-selects-the-covering-band
  (let [tiers [{::t/from-turn 2 ::t/to-turn 4 ::t/token-cap 100}
               {::t/from-turn 5 ::t/token-cap 20}]]
    (is (nil? (t/tier-cap-for-turn tiers 0)) "offset below all tiers → nil (evict)")
    (is (= 100 (t/tier-cap-for-turn tiers 3)) "offset in the closed band")
    (is (= 20 (t/tier-cap-for-turn tiers 9)) "offset in the open-ended band")))

(deftest banding-evicts-old-over-budget-keeps-window-and-messages
  (let [;; retained=2 → offsets 0,1 (turns 5,4) verbatim; older subject to the
        ;; tier. A TINY cap (3 tokens) for offset>=2 evicts the big turn-0 eval
        ;; but keeps the small turn-1 eval and the message.
        out (t/clip-events-by-tiers
              [{::t/from-turn 2 ::t/token-cap 3}] 2 stream)
        keep (set (map (juxt ::t/turn-idx ::t/kind) out))]
    (testing "the retained window renders verbatim"
      (is (contains? keep [5 :eval]))
      (is (contains? keep [4 :eval])))
    (testing "a message is never evicted, regardless of age"
      (is (contains? keep [1 :message])))
    (testing "an old eval OVER its tier budget is evicted"
      (is (not (contains? keep [0 :eval]))))
    (testing "output stays oldest-first (render order preserved)"
      (is (= (vec (sort (map ::t/turn-idx out))) (mapv ::t/turn-idx out))))))

(deftest decay-cap-single-level-default-is-parity
  (testing "the v1 single-level [0→16384] default caps every offset at 16384"
    (let [levels [{::t/from-turn-offset 0 ::t/token-cap 16384}]]
      (is (= 16384 (t/decay-cap-for-offset levels 0 16384)))
      (is (= 16384 (t/decay-cap-for-offset levels 99 16384))))
    (testing "absent levels → the default-cap"
      (is (= 16384 (t/decay-cap-for-offset [] 5 16384))))))
