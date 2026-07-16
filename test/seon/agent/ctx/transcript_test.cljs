(ns seon.agent.ctx.transcript-test
  "Transcript age-banding (config-init CP-5, #62) — the `::tiers` +
   `::turns-retained` window over the event stream. Pure-data tests on
   [[clip-events-by-tiers]] / [[tier-cap-for-turn]]: empty tiers = render-all
   (byte-parity); a configured schedule evicts old evals past their tier budget
   while keeping the retained window verbatim and messages always.

   Run: bin/test-cljs, or (cljs.test/run-tests 'seon.agent.ctx.transcript-test)."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [async deftest is testing]]
    [datahike.api :as datahike]
    [datahike.db :as datahike-db]
    [datahike.query :as datahike-query]
    [malli.core :as m]
    [seon.ai.tokens :as tokens]
    [seon.agent.message :as message]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.transcript :as t]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.eval :as seval]
    [seon.handlers.eval :as eval-handler]))

(defn- ev
  ([turn kind] (ev turn kind nil))
  ([turn kind edn]
   (cond-> {::t/turn-idx turn ::t/kind kind}
     edn (assoc ::t/entity {:seon.eval/result-edn edn}))))

(def ^:private coordinate
  {:seon.db.coordinate/database-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   :seon.db.coordinate/branch :db
   :seon.db.coordinate/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   :seon.db.coordinate/t 42})

(defn- query-result [value]
  {::protocol/success? true :datahike.query/result value})

(defn- pull-result [value]
  {::protocol/success? true ::protocol/result value})

(deftest transcript-policy-defaults-come-from-current-declarations
  (testing "a stale activated registry cannot turn source-owned defaults nil"
    (is (= 25 (deref #'t/default-turns-retained)))
    (is (= 50 (deref #'t/default-turn-window-size)))
    (is (= 25 (deref #'t/default-turn-eviction-size)))
    (is (= 8192 (deref #'t/default-settled-token-cap)))))

(deftest transcript-window-has-an-explicit-authority-bound
  (is (m/validate ::t/turn-window-size 200))
  (is (not (m/validate ::t/turn-window-size 201))))

(deftest transcript-scalar-query-bounds-admit-grown-intermediate-relations
  (let [schema {:seon.agent/id {:db/unique :db.unique/identity}
                :seon.agent.run/agent {:db/valueType :db.type/ref}
                :seon.agent.run/id {:db/unique :db.unique/identity}
                :seon.agent.turn/run {:db/valueType :db.type/ref}
                :seon.agent.turn/evals
                {:db/valueType :db.type/ref
                 :db/cardinality :db.cardinality/many}
                :seon.eval/agent {:db/valueType :db.type/ref}
                :seon.eval/id {}
                :seon.eval/source {}
                :seon.eval/narration {}
                :seon.eval/output {}
                :seon.eval/result-edn {}
                :seon.eval/error {}
                :seon.eval/error-data {}
                :seon.render/full? {}
                :seon.agent.message/from {:db/valueType :db.type/ref}
                :seon.agent.message/to
                {:db/valueType :db.type/ref
                 :db/cardinality :db.cardinality/many}
                :seon.agent.message/id {}
                :seon.agent.message/content {}
                :seon.agent.message/hops {}
                :seon.agent.message/origin {}}
        rows (mapcat
               (fn [n]
                 (let [turn (+ 1000 n)
                       eval (+ 2000 n)
                       at (js/Date. n)]
                   [{:db/id turn
                     :seon.agent.turn/run 2
                     :seon.agent.turn/at at
                     :seon.agent.turn/scheduled? false
                     :seon.agent.turn/evals [eval]}
                    {:db/id eval
                     :seon.eval/agent 1
                     :seon.eval/id (str "eval-" n)
                     :seon.eval/at at
                     :seon.eval/ok? true
                     :seon.eval/source (str "(identity " n ")")
                     :seon.eval/result-edn (str n)
                     :seon.eval/ns 'my.agent.agent}
                    {:db/id (+ 3000 n)
                     :seon.agent.message/from 1
                     :seon.agent.message/to [1]
                     :seon.agent.message/id (str "message-" n)
                     :seon.agent.message/content (str "message " n)
                     :seon.agent.message/hops 1
                     :seon.agent.message/origin :user
                     :seon.agent.message/at at}]))
               (range 200))
        database
        (datahike/db-with
          (datahike-db/empty-db schema)
          (into [{:db/id 1 :seon.agent/id "agent"}
                 {:db/id 2
                  :seon.agent.run/agent 1
                  :seon.agent.run/id "run-1"
                  :seon.agent.run/status :open}
                 ;; Newer direct/core eval: not turn-linked, so neither current
                 ;; namespace nor last action may observe it.
                 {:db/id 9000
                  :seon.eval/agent 1
                  :seon.eval/id "direct"
                  :seon.eval/at (js/Date. 1000)
                  :seon.eval/ok? true
                  :seon.eval/ns 'my.agent.direct}]
                rows))
        evidence
        (fn [query arguments max-work max-result-weight]
          (datahike-query/q-with-evidence
            {:query query
             :args (into [database] arguments)
             :max-work max-work
             :max-results max-work
             :max-result-weight max-result-weight}))
        turn-ids (mapv #(+ 1000 %) (range 200))
        limits [[1000000 4096]
                [500000 8192]
                [500000 8192]
                [500000 4096]
                [500000 4096]
                [500000 8192]
                [1000000 65536]
                [1000000 524288]
                [1000000 262144]]
        results
        [(evidence (deref #'t/turn-count-query) ["agent"] 1000000 4096)
         (evidence (deref #'t/current-ns-query) ["agent"] 500000 8192)
         (evidence (deref #'t/last-action-query) ["agent"] 500000 8192)
         (evidence (deref #'t/run-turn-count-query) ["run-1"] 500000 4096)
         (evidence (deref #'t/run-form-count-query) ["run-1"] 500000 4096)
         (evidence ((deref #'t/previous-ns-query) (js/Date. 100))
                   ["agent" (js/Date. 100)] 500000 8192)
         (evidence ((deref #'t/turns-query) 200) ["agent"] 1000000 65536)
         (evidence (deref #'t/eval-rows-query) [turn-ids] 1000000 524288)
         (evidence ((deref #'t/messages-query) nil) ["agent"] 1000000 262144)]
        counts (mapv #(get-in % [:datahike.query/resource-evidence
                                 :datahike.resource/result-count])
                     results)
        weights (mapv #(get-in % [:datahike.query/resource-evidence
                                  :datahike.resource/result-weight])
                      results)]
    (is (= [200 'my.agent.agent 200 200 'my.agent.agent]
           [(get-in results [0 :datahike.query/result])
            (ffirst (get-in results [1 :datahike.query/result]))
            (get-in results [3 :datahike.query/result])
            (get-in results [4 :datahike.query/result])
            (ffirst (get-in results [5 :datahike.query/result]))]))
    (is (= 'my.agent.agent
           (ffirst (get-in results [1 :datahike.query/result])))
        "a newer eval outside a turn cannot become current namespace")
    (is (= 199 (.getTime ^js (get-in results [2 :datahike.query/result])))
        "a newer eval outside a turn cannot become the last action")
    (is (every? pos? counts))
    (is (every? pos? weights))
    (is (= [200 601 800 400 400 501 401 1600 2200] counts))
    (is (= [400 3607 3600 1600 1800 3407 3812 6470 8380] weights))
    (is (every? true?
                (map (fn [observed-count observed-weight
                          [max-results max-weight]]
                       (and (<= observed-count max-results)
                            (<= observed-weight max-weight)))
                     counts weights limits)))
    (is (> (nth counts 0) 8))
    (is (> (nth counts 1) 64))
    (is (> (nth counts 2) 8))
    (is (> (nth counts 3) 8))
    (is (> (nth counts 4) 8))
    (is (> (nth counts 5) 64))))

(deftest acquired-transcript-formatting-does-no-database-io
  (let [original-execute-many db/execute-many
        original-query db/query
        original-pull db/pull
        touched (atom [])
        fail-read (fn [& args]
                    (swap! touched conj args)
                    (throw (js/Error. "unexpected database read")))]
    (try
      (set! db/execute-many fail-read)
      (set! db/query fail-read)
      (set! db/pull fail-read)
      (let [rendered
            (t/transcript-block
              {:seon.agent/id "agent"
               :seon.agent/entity {:db/id 1 :seon.agent/id "agent"}
               :seon.render/node {::t/readline? false}
               :seon.config/repl-mode :batch
               :seon.derive/state :idle
               :seon.eval/ns 'my.agent.agent
               ::t/turn-count 0
               ::t/turns []
               ::t/messages []
               :seon.agent.run/turn-count 0
               :seon.agent.run/form-count 0})]
        (is (str/includes? rendered "; seon · my.agent.agent · live REPL"))
        (is (empty? @touched)))
      (finally
        (set! db/execute-many original-execute-many)
        (set! db/query original-query)
        (set! db/pull original-pull)))))

(deftest transcript-acquisition-is-two-coordinate-pinned-batches
  (async done
    (let [at (js/Date. 1000)
          eval-row {:db/id 401
                    :seon.eval/id "eval-1"
                    :seon.eval/at at
                    :seon.eval/source "(+ 1 1)"
                    :seon.eval/ok? true
                    :seon.eval/result-edn "2"
                    :seon.eval/ns 'my.agent.agent}
          merged-node {::t/readline? false
                       ::t/result-handles? false
                       ::t/turn-window-size 50
                       ::t/turn-eviction-size 25
                       ::t/settled-token-cap 123
                       ::t/result-decay
                       [{::t/from-turn-offset 0 ::t/token-cap 64}]}
          requests (atom [])
          responses
          (atom
            [{::db/coordinate coordinate
              ::db/results
              [(pull-result {})
               (query-result 1)
               (query-result [[301 at false 202 "run-1"]])
               (query-result [['my.agent.agent at 401]])
               (query-result nil)]}
             {::db/coordinate coordinate
              ::db/results
              [(query-result #{[301 eval-row]})
               (query-result [])]}])
          original-execute-many db/execute-many]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (let [response (first @responses)]
                (swap! responses subvec 1)
                (js/Promise.resolve response))))
      (-> (js/Promise.resolve nil)
          (.then
            (fn [_]
              ((deref #'t/acquire-transcript)
               {::db/coordinate coordinate
                :seon.agent/id "agent"
                :seon.agent/entity {:db/id 1 :seon.agent/id "agent"}
                :seon.render/node merged-node})))
          (.then
            (fn [acquired]
              (testing "both dependent stages retain the inherited coordinate"
                (is (= 2 (count @requests)))
                (is (every? #(= coordinate (::db/coordinate %)) @requests))
                (is (= [5 2] (mapv (comp count ::db/members) @requests)))
                (is (= [32 1000000 1000000 500000 500000]
                       (mapv :datahike.resource/max-results
                             (::db/members (first @requests)))))
                (is (= [4096 4096 65536 8192 8192]
                       (mapv :datahike.resource/max-result-weight
                             (::db/members (first @requests)))))
                (is (= [524288 262144]
                       (mapv :datahike.resource/max-result-weight
                             (::db/members (second @requests))))))
              (testing "prompt discovery's stored-plus-profile node is preserved"
                (is (= false (get-in acquired [:seon.render/node ::t/readline?])))
                (is (= 123 (get-in acquired
                                    [:seon.render/node ::t/settled-token-cap])))
                (is (= (::t/result-decay merged-node)
                       (get-in acquired [:seon.render/node ::t/result-decay]))))
              (testing "ordinary rows carry the exact absolute turn index"
                (is (= [0] (mapv ::t/turn-idx (::t/turns acquired))))
                (is (= ["eval-1"]
                       (mapv :seon.eval/id
                             (:seon.agent.turn/evals
                               (first (::t/turns acquired)))))))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally
            (fn []
              (set! db/execute-many original-execute-many)
              (done)))))))

(deftest host-telemetry-is-bounded-unix-load-line
  (let [line (t/host-telemetry)]
    (is (re-find #"^; host · load 1m/5m/15m [0-9.]+/[0-9.]+/[0-9.]+ · rss [0-9.]+ (MiB|GiB) · heap [0-9.]+ (MiB|GiB)$"
                 line))
    (is (<= (tokens/estimate line) 50))))

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

(deftest turn-window-rotates-only-in-complete-25-turn-chunks
  (testing "50/25 keeps an append-only window between rotation boundaries"
    (is (= 0 (t/turn-window-cutoff 49 50 25)))
    (is (= 25 (t/turn-window-cutoff 50 50 25)))
    (is (= 25 (t/turn-window-cutoff 74 50 25)))
    (is (= 50 (t/turn-window-cutoff 75 50 25))))
  (let [events (mapv #(ev % :eval (str "e" %)) (range 75))]
    (is (= (range 25 75)
           (map ::t/turn-idx (t/clip-events-by-turn-window 74 50 25 events))))
    (is (= (range 50 75)
           (map ::t/turn-idx (t/clip-events-by-turn-window 75 50 25 events))))))

(deftest settled-budget-charges-complete-rendered-event-newest-first
  (let [oldest {::t/event {::t/turn-idx 25} ::t/text (apply str (repeat 40 "a"))}
        newer  {::t/event {::t/turn-idx 49} ::t/text (apply str (repeat 40 "b"))}
        active {::t/event {::t/turn-idx 50} ::t/text (apply str (repeat 400 "c"))}
        cap    (tokens/estimate (::t/text newer))
        out    (t/clip-rendered-events-by-settled-budget 50 cap
                                                         [oldest newer active])]
    (is (= [newer active] out)
        "the newest settled row spends the shared cap; active rows are free")
    (is (> (tokens/estimate (::t/text active)) cap)
        "the active row survives even when it exceeds the settled budget")))

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

(deftest html-source-events-use-bounded-fact-owner-windows
  (let [requests (atom [])
        turn-ats [(at 100) (at 250)]
        message-row
        {:seon.agent.message/id "message-1"
         :seon.agent.message/at (at 200)
         :seon.agent.message/from {:db/id 1 :seon.user/id "user"}
         :seon.agent.message/to [{:db/id 10 :seon.agent/id "agent"}]
         :seon.agent.message/content "hello"
         :seon.agent.message/hops 0}
        eval-row
        {:seon.eval/id "eval-1"
         :seon.eval/at (at 300)
         :seon.eval/source "(+ 1 1)"
         :seon.eval/ok? true}
        events
        (with-redefs [message/recent
                      (fn [request]
                        (swap! requests conj request)
                        [message-row])
                      seval/recent
                      (fn [request]
                        (swap! requests conj request)
                        [eval-row])]
          ((deref #'t/recent-html-source-events)
           ::db "agent" 10 turn-ats))]
    (is (= [200 200]
           (mapv #(or (:seon.agent.message/recent-limit %)
                      (:seon.eval/recent-limit %))
                 @requests))
        "both fact owners are capped before transcript event materialization")
    (is (= [:message :eval] (mapv ::t/kind events)))
    (is (= 1 (::t/turn-idx (second events)))
        "bounded evals retain the same turn-window coordinate")))

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
