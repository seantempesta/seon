(ns seon.agent.ctx.transcript-test
  "Transcript age-banding (config-init CP-5, #62) — the `::tiers` +
   `::turns-retained` window over the event stream. Pure-data tests on
   [[clip-events-by-tiers]] / [[tier-cap-for-turn]]: empty tiers = render-all
   (byte-parity); a configured schedule evicts old evals past their tier budget
   while keeping the retained window verbatim and messages always.

   Run: bin/test-cljs, or (cljs.test/run-tests 'seon.agent.ctx.transcript-test)."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is testing]]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.transcript :as t]
    [seon.eval :as seval]
    [seon.handlers.eval :as eval-handler]))

(defn- ev
  ([turn kind] (ev turn kind nil))
  ([turn kind edn]
   (cond-> {::t/turn-idx turn ::t/kind kind}
     edn (assoc ::t/entity {:seon.eval/result-edn edn}))))

(deftest transcript-handles-follow-exact-result-cache-membership
  (let [prior-results (js/Reflect.get js/globalThis
                                      (str seval/result-ns-sym))
        compile-state (atom {:cljs.analyzer/namespaces {}})
        cap           (deref #'seval/result-vars-cap)
        ids           (mapv #(str "runtime-result-" %) (range (inc cap)))
        evicted-id    (first ids)
        current-id    (last ids)
        prior-id      "prior-process-result"
        row           (fn [id millis]
                        {:seon.eval/id id
                         :seon.eval/at (js/Date. millis)
                         :seon.eval/source (str "(identity " (pr-str id) ")")
                         :seon.eval/ok? true
                         :seon.eval/result-edn (pr-str id)
                         :seon.agent.ctx/escape-clipping? false})]
    (try
      ;; Isolate the process-global bounded cache, then cross its real cap.
      ;; The first id is evicted by bind-result-var! itself; `prior-id` models
      ;; a durable successful eval from a process whose runtime is gone.
      (js/Reflect.set js/globalThis (str seval/result-ns-sym)
                      (js/Object.create nil))
      (doseq [id ids]
        (seval/bind-result-var! compile-state id id))
      (let [same-run {:seon.agent.run/id "one-run-for-all-three-results"}
            turns    [{:seon.agent.turn/run same-run
                       :seon.agent.turn/evals [(row evicted-id 100)
                                               (row prior-id 200)
                                               (row current-id 300)]}]
            events   (with-redefs [ctx/agent-turns (fn
                                                     ([_] turns)
                                                     ([_ _] turns))]
                       ((deref #'t/eval-events) nil "agent"))
            by-id    (into {} (map (juxt #(get-in % [::t/entity :seon.eval/id])
                                         identity)) events)
            rendered (fn [id]
                       (t/eval->renderable
                         {:seon.render/node (get by-id id)}))]
        (testing "the runtime cache itself is the liveness authority"
          (is (false? (seval/result-live? evicted-id))
              "crossing the cap evicts the oldest exact member")
          (is (false? (seval/result-live? prior-id))
              "a prior-process id has no runtime member")
          (is (true? (seval/result-live? current-id))
              "the newest exact member remains live"))
        (testing "transcript events derive handles from membership, not run"
          (is (false? (::t/result-live? (get by-id evicted-id))))
          (is (false? (::t/result-live? (get by-id prior-id))))
          (is (true? (::t/result-live? (get by-id current-id))))
          (is (not (str/includes? (rendered evicted-id)
                                  (str "result/" evicted-id)))
              "an evicted result in the same run never advertises a dead handle")
          (is (not (str/includes? (rendered prior-id)
                                  (str "result/" prior-id)))
              "a prior-process result never advertises a dead handle")
          (is (str/includes? (rendered current-id)
                             (str ctx/result-close " result/" current-id))
              "the exact live member keeps its reusable handle")))
      (finally
        (if prior-results
          (js/Reflect.set js/globalThis (str seval/result-ns-sym)
                          prior-results)
          (js/Reflect.deleteProperty js/globalThis
                                     (str seval/result-ns-sym)))))))

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

(defn- at [millis]
  (js/Date. millis))

(defn- html-ev [event-id millis kind & [turn-idx]]
  (cond-> {::event-id event-id ::t/at (at millis) ::t/kind kind}
    (some? turn-idx) (assoc ::t/turn-idx turn-idx)))

(deftest recent-html-window-is-turn-bounded
  (let [turn-ats [(at 100) (at 200) (at 300) (at 400)]
        events [(html-ev :old-message 150 :message)
                (html-ev :preceding-message 250 :message)
                (html-ev :old-eval 260 :eval 1)
                (html-ev :recent-eval 310 :eval 2)
                (html-ev :recent-message 350 :message)
                (html-ev :latest-eval 410 :eval 3)]
        out (t/recent-html-events turn-ats 2 events)]
    (testing "the last two turns' evals and messages remain"
      (is (= [:preceding-message :recent-eval :recent-message :latest-eval]
             (mapv ::event-id out))))
    (testing "only one message before the cutoff is retained for context"
      (is (not-any? #(= :old-message (::event-id %)) out)))
    (testing "a zero-sized configured window renders no historical DOM"
      (is (empty? (t/recent-html-events turn-ats 0 events))))))

(deftest recent-html-window-bounds-a-message-only-agent
  (let [events [(html-ev :one 100 :message)
                (html-ev :two 200 :message)
                (html-ev :three 300 :message)]]
    (is (= [:two :three]
           (mapv ::event-id (t/recent-html-events [] 2 events))))))

(defn- hiccup-tags [hiccup]
  (->> (tree-seq coll? seq hiccup)
       (filter keyword?)
       set))

(deftest normal-eval-activity-row-does-not-embed-technical-payloads
  (let [source-sentinel "SOURCE-PAYLOAD-SENTINEL"
        result-sentinel "RESULT-PAYLOAD-SENTINEL"
        error-sentinel  "ERROR-PAYLOAD-SENTINEL"
        ok-row (eval-handler/render-activity-html
                 {:seon.render/node
                  {:seon.eval/id "ok-eval"
                   :seon.eval/source (apply str (repeat 1000 source-sentinel))
                   :seon.eval/result-edn (apply str (repeat 1000 result-sentinel))
                   :seon.eval/ok? true
                   :seon.eval/duration-ms 12}})
        failed-row (eval-handler/render-activity-html
                     {:seon.render/node
                      {:seon.eval/id "failed-eval"
                       :seon.eval/source source-sentinel
                       :seon.eval/error (apply str (repeat 1000 error-sentinel))
                       :seon.eval/ok? false}})
        rendered (str ok-row failed-row)]
    (testing "normal rows have no disclosure subtree"
      (is (not (contains? (hiccup-tags ok-row) :details)))
      (is (not (contains? (hiccup-tags failed-row) :details))))
    (testing "source, result, and error bodies never enter the normal DOM"
      (is (not (re-find (re-pattern source-sentinel) rendered)))
      (is (not (re-find (re-pattern result-sentinel) rendered)))
      (is (not (re-find (re-pattern error-sentinel) rendered))))))
