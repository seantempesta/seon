(ns seon.agent.ctx.transcript-test
  "Pure transcript formatting and database-value acquisition."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [seon.agent.ctx.transcript :as transcript]
    [seon.db :as db]
    [seon.db.protocol :as protocol]))

(def ^:private database {:datahike/commit-id "transcript" :max-tx 7})

(def acquired-empty
  {:seon.agent/id "agent"
   :seon.agent/entity {:db/id 1 :seon.agent/id "agent"}
   :seon.render/node
   {:seon.agent.ctx.transcript/readline? false}
   :seon.config/repl-mode :batch
   :seon.derive/state :idle
   :seon.eval/ns 'my.agent.agent
   :seon.agent.ctx.transcript/turn-count 0
   :seon.agent.ctx.transcript/turns []
   :seon.agent.ctx.transcript/messages []
   :seon.agent.run/turn-count 0
   :seon.agent.run/form-count 0})

(deftest eval-events-are-independent-of-process-local-result-membership
  (let [event ((deref #'transcript/eval->event)
               0 {:seon.eval/id "stable-result"
                  :seon.eval/at (js/Date. 1)
                  :seon.eval/ok? true
                  :seon.eval/source "(+ 1 1)"
                  :seon.eval/result-edn "2"})]
    (is (false? (:seon.agent.ctx.transcript/result-live? event))
        "ordinary context derives handle absence from stored data only")))

(deftest live-readline-is-confined-to-the-root-tail
  (is (= "" (transcript/readline-block
              (assoc acquired-empty :seon.agent/id "worker"))))
  (is (str/includes?
       (transcript/readline-block
        (assoc acquired-empty :seon.agent/id "root"))
       "root")))

(defn- member-result [result]
  {:seon.db.protocol/success? true
   :datahike.query/result result})

(defn- execute-many-result [results]
  {::db/results (mapv member-result results)})

(defn- transcript-responder
  ([requests turn-count eval-row]
   (transcript-responder requests turn-count eval-row
                         (mapv (fn [turn] [turn turn])
                               (range 1 (inc turn-count)))))
  ([requests turn-count eval-row eval-pairs]
   (fn [request]
     (swap! requests conj request)
     (let [members (::db/members request)
           query-text (some-> members first ::protocol/query-form pr-str)]
       (js/Promise.resolve
         (cond
           (> (count members) 2)
           (execute-many-result
             [turn-count
              (mapv (fn [turn]
                      [turn (js/Date. turn) false (+ 1000 turn) (str "run-" turn)])
                    (range 1 (inc turn-count)))
              nil])

           (and (str/includes? query-text "seon.eval")
                (not (str/includes? query-text "pull")))
           (execute-many-result
             [(mapv (fn [[turn eval-id]] [turn eval-id (js/Date. eval-id)])
                    eval-pairs)])

           (str/includes? query-text "seon.eval")
           (let [page (-> members first ::protocol/arguments first)]
             (execute-many-result
               [(mapv (fn [[turn eval-id]] [turn (eval-row eval-id)]) page)]))

           :else
           (execute-many-result (repeat (count members) []))))))))

(deftest acquired-formatting-does-no-database-io
  (let [original-execute-many db/execute-many
        touched (atom false)]
    (try
      (set! db/execute-many
            (fn [& _]
              (reset! touched true)
              (throw (js/Error. "unexpected database read"))))
      (let [text (@#'transcript/format-transcript-block acquired-empty)]
        (is (str/includes? text "; seon · my.agent.agent · live REPL"))
        (is (false? @touched)))
      (finally
        (set! db/execute-many original-execute-many)))))

(deftest transcript-windows-rotate-in-complete-chunks
  (is (= 0 (transcript/turn-window-cutoff 49 50 25)))
  (is (= 25 (transcript/turn-window-cutoff 50 50 25)))
  (is (= 25 (transcript/turn-window-cutoff 74 50 25)))
  (is (= 50 (transcript/turn-window-cutoff 75 50 25))))

(deftest current-run-cause-survives-transcript-window-rotation
  (let [cause-eid 300
        turns (mapv (fn [turn-idx]
                      {:seon.agent.ctx.transcript/turn-idx turn-idx
                       :seon.agent.turn/at (js/Date. (+ 1000 turn-idx))
                       :seon.agent.turn/evals []})
                    (range 25 50))
        cause {:db/id cause-eid
               :seon.agent.message/id "current-request"
               :seon.agent.message/content "Do the current work"
               :seon.agent.message/at (js/Date. 1)
               :seon.agent.message/hops 0
               :seon.agent.message/origin :human
               :seon.agent.message/from {:db/id 2 :seon.user/id "user"}
               :seon.agent.message/to [{:db/id 1 :seon.agent/id "agent"}]}
        input {:seon.agent/id "agent"
               :seon.agent/entity
               {:db/id 1
                :seon.agent/id "agent"
                :seon.agent/run
                {:seon.agent.run/id "current-run"
                 :seon.agent.run/status :open
                 :seon.agent.run/cause {:db/id cause-eid}}}
               :seon.agent.ctx.transcript/turn-count 50
               :seon.agent.ctx.transcript/turns turns
               :seon.agent.ctx.transcript/messages [cause]}
        events (@#'transcript/ordered-events input)
        retained (transcript/clip-events-by-turn-window 50 50 25 events)]
    (is (= ["current-request"]
           (mapv :seon.agent.ctx.transcript/id retained)))
    (is (= 49
           (:seon.agent.ctx.transcript/turn-idx (first retained))))))

(deftest recent-html-window-bounds-message-only-history
  (let [events [{:seon.agent.ctx.transcript/at (js/Date. 100)}
                {:seon.agent.ctx.transcript/at (js/Date. 200)}
                {:seon.agent.ctx.transcript/at (js/Date. 300)}]]
    (is (= [200 300]
           (mapv #(.getTime ^js (:seon.agent.ctx.transcript/at %))
                 (transcript/recent-html-events [] 2 events))))))

(deftest current-database-value-is-acquired-once-for-both-stages
  (async done
    (let [requests (atom [])
          original-execute db/execute-many]
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
                {::db/results
                 (mapv (fn [result]
                         {:seon.db.protocol/success? true
                          :datahike.query/result result})
                       (if (= 1 (count @requests))
                         [1 [[101 (js/Date. 1) false 201 "run-1"]] nil]
                         [[] []]))})))
      (-> (transcript/transcript-block
            {:seon.agent/id "agent"
             :seon.agent.run/id "run-1"
             ::db/db database} nil)
          (.then (fn [_]
                   (is (every? #(identical? database (::db/db %)) @requests))
                   (is (every?
                        (fn [request]
                          (every? #(identical? database
                                               (::db/db %))
                                  (::db/members request)))
                        @requests)
                       "every grouped member names its database source")
                   (is (= ["agent"]
                          (-> @requests first ::db/members first
                              ::protocol/arguments))
                       "the database value is not a Datalog :in argument")
                   (is (some #(= [[101]] (::protocol/arguments %))
                             (mapcat ::db/members @requests))
                       "a collection binding remains one Datalog argument")
                   (let [message-member
                         (->> @requests
                              (mapcat ::db/members)
                              (filter #(str/includes?
                                        (pr-str (::protocol/query-form %))
                                        ":seon.agent.message/content"))
                              first)]
                     (is (= ["agent" "run-1" (js/Date. 1)]
                            (::protocol/arguments message-member)))
                     (is (str/includes?
                           (pr-str (::protocol/query-form message-member))
                           ":seon.agent.run/cause")
                         "the bounded transcript always includes the message that opened the current run"))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (set! db/execute-many original-execute)
                      (done)))))))

(deftest html-transcript-omits-ai-only-eval-payloads
  (async done
    (let [requests (atom [])
          original-execute-many db/execute-many]
      (set! db/execute-many
            (transcript-responder
              requests 1
              (fn [turn]
                {:db/id turn
                 :seon.eval/id (str "eval-" turn)
                 :seon.eval/at (js/Date. turn)
                 :seon.eval/source "(db/query request)"
                 :seon.eval/narration "Read current facts"
                 :seon.eval/ok? true
                 :seon.eval/duration-ms 3
                 :seon.eval/ns 'my.agent.test})))
      (-> (transcript/transcript-block-html
            {:seon.agent/id "agent"
             :seon.agent/entity {:db/id 1 :seon.agent/id "agent"}
             :seon.render/node {:seon.agent.ctx.transcript/readline? false}
             ::db/db database}
            nil)
          (.then
            (fn [hiccup]
              (is (vector? hiccup))
              (let [turn-query (-> @requests first ::db/members second
                                   ::protocol/query-form pr-str)
                    eval-query (->> @requests
                                    (mapcat ::db/members)
                                    (map ::protocol/query-form)
                                    (filter #(and (str/includes? (pr-str %) "seon.eval/id")
                                                  (str/includes? (pr-str %) "pull")))
                                    first
                                    pr-str)]
                (is (str/includes? turn-query "seon.agent.turn/llm-usage"))
                (is (str/includes? turn-query
                                   "seon.agent.turn/usage-estimated?"))
                (is (str/includes? eval-query "seon.eval/duration-ms"))
                (is (not (str/includes? eval-query "seon.eval/source")))
                (is (not (str/includes? eval-query "seon.eval/output")))
                (is (not (str/includes? eval-query "seon.eval/result-edn")))
                (is (not (str/includes? eval-query "seon.eval/error")))
                (is (not (str/includes? eval-query "seon.eval/error-data"))))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (set! db/execute-many original-execute-many)
                      (done)))))))

(deftest html-transcript-renders-actual-and-estimated-usage-honestly
  (let [actual {:seon.agent.ctx.transcript/turn-idx 0
                :seon.agent.turn/at (js/Date. 1)
                :seon.agent.turn/evals []
                :seon.agent.turn/llm-usage
                (pr-str {:prompt_tokens 9000
                         :completion_tokens 200
                         :prompt_tokens_details {:cached_tokens 8400}})}
        estimated {:seon.agent.ctx.transcript/turn-idx 1
                   :seon.agent.turn/at (js/Date. 2)
                   :seon.agent.turn/evals []
                   :seon.agent.turn/llm-usage
                   (pr-str {:prompt_tokens 120 :completion_tokens 8})
                   :seon.agent.turn/usage-estimated? true}
        hiccup (@#'transcript/format-transcript-html
                 (assoc acquired-empty
                        :seon.agent.ctx.transcript/turn-count 2
                        :seon.agent.ctx.transcript/turns [actual estimated]
                        :seon.agent.ctx.transcript/events []))
        rendered (pr-str hiccup)]
    (is (str/includes? rendered
                       "usage · total 9000 · cached 8400 · output 200"))
    (is (str/includes? rendered "est. (stream abort)"))
    (is (str/includes? rendered "no cache data"))))

(deftest html-transcript-omits-usage-when-the-turn-has-none
  (let [turn {:seon.agent.ctx.transcript/turn-idx 0
              :seon.agent.turn/at (js/Date. 1)
              :seon.agent.turn/evals []}
        hiccup (@#'transcript/format-transcript-html
                 (assoc acquired-empty
                        :seon.agent.ctx.transcript/turn-count 1
                        :seon.agent.ctx.transcript/turns [turn]
                        :seon.agent.ctx.transcript/events []))]
    (is (not (str/includes? (pr-str hiccup) "usage ·")))))

(deftest max-content-evals-are-read-in-bounded-cacheable-pages
  (async done
    (let [requests (atom [])
          original-execute-many db/execute-many
          maximum-projection (apply str (repeat 16384 "x"))
          eval-pairs (mapv (fn [eval-id] [1 eval-id]) (range 1 18))]
      (set! db/execute-many
            (transcript-responder
              requests 1
              (fn [turn]
                {:db/id turn
                 :seon.eval/id (str "eval-" turn)
                 :seon.eval/at (js/Date. turn)
                 :seon.eval/source maximum-projection
                 :seon.eval/output maximum-projection
                 :seon.eval/ok? true
                 :seon.eval/result-edn maximum-projection
                 :seon.eval/ns 'my.agent.test})
              eval-pairs))
      (-> (transcript/transcript-block
            {:seon.agent/id "agent"
             :seon.agent/entity {:db/id 1 :seon.agent/id "agent"}
             :seon.render/node {:seon.agent.ctx.transcript/readline? false}
             ::db/db database}
            nil)
          (.then
            (fn [text]
              (is (not (str/includes? text "render failed")))
              (let [eval-requests
                    (->> @requests
                         (filter
                           (fn [request]
                             (let [query-text (some-> request ::db/members first
                                                      ::protocol/query-form pr-str)]
                               (and (str/includes? query-text "seon.eval/id")
                                    (str/includes? query-text "pull"))))))]
                (is (= [1 4 4 4 4]
                       (sort (map #(count (-> % ::db/members first
                                             ::protocol/arguments first))
                                  eval-requests))))
                (is (every? #(<= (* 3 16384
                                  (count (-> % ::db/members first
                                             ::protocol/arguments first)))
                                524288)
                            eval-requests)
                    "each page keeps multiple maximum stored projections bounded")
                (is (every? #(identical? database (::db/db %)) eval-requests))
                (is (every? protocol/ordinary-wire-value? eval-requests)
                    "every paged query is an eager database wire value")
                (is (apply = (map #(-> % ::db/members first
                                      ::protocol/query-form)
                                  eval-requests))
                    "identical page queries share the database query cache"))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (set! db/execute-many original-execute-many)
                      (done)))))))

(deftest host-telemetry-remains-bounded
  (is (str/starts-with? (transcript/host-telemetry) "; host · load ")))
