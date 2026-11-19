(ns seon.fork.worker-support
  "Pure test runner & agent worker turn-history/Hiccup helpers."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :as t]
            [datahike.api :as d]
            [jolt.async.remote :as remote]
            [jolt.ffi :as ffi]
            [jolt.host]
            [jolt.io-poller :as poller]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.health :as health]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.fork.helpers :as helpers :refer [*agent-ctx*]]))

(defn apply-form-overlay!
  ([overlay] (code/apply-overlay! nil overlay))
  ([db overlay] (code/apply-overlay! db overlay)))

(defn run-single-test-var [test-sym digest]
  (let [ns-sym (symbol (namespace test-sym))
        sw (java.io.StringWriter.)
        counts (volatile! {:pass 0 :fail 0 :error 0 :messages []})
        measured
        (binding [*out* sw
                  *err* sw
                  t/*test-out* sw
                  t/report (fn [m]
                             (case (:type m)
                               :pass (vswap! counts update :pass inc)
                               :fail (vswap! counts #(-> % (update :fail inc)
                                                         (update :messages conj (str "FAIL: " (:message m "") " expected=" (pr-str (:expected m)) " actual=" (pr-str (:actual m))))))
                               :error (vswap! counts #(-> % (update :error inc)
                                                          (update :messages conj (str "ERROR: " (:message m "") " actual=" (pr-str (:actual m))))))
                               nil))]
          (code/measure-call
           (fn []
             (try
               (when-not (find-ns ns-sym) (require ns-sym))
               (if-let [v (resolve test-sym)]
                 (do (t/test-var v) v)
                 (do (vswap! counts #(-> % (update :error inc)
                                         (update :messages conj (str "Test var not found: " test-sym))))
                     nil))
               (catch Throwable ex
                 (vswap! counts #(-> % (update :error inc)
                                     (update :messages conj (str "UNCAUGHT: " (.getMessage ex)))))
                 nil)))))
        v (:val measured)
        vmeta (when (var? v) (meta v))
        ticks (:ticks measured)
        bytes (:bytes measured)
        ms (:ms measured)
        ticks-budget (or (:cost/ticks vmeta) (get-in vmeta [:seon.test/estimate :ticks]))
        bytes-budget (or (:cost/bytes vmeta) (get-in vmeta [:seon.test/estimate :bytes]))
        _ (when (and ticks-budget (> ticks ticks-budget))
            (vswap! counts #(-> % (update :fail inc)
                                (update :messages conj
                                        (str "COST OVERRUN on " test-sym ": actual " ticks " ticks > estimated "
                                             ticks-budget " ticks (:cost/ticks). Optimize or update estimate.")))))
        _ (when (and bytes-budget (> bytes bytes-budget))
            (vswap! counts #(-> % (update :fail inc)
                                (update :messages conj
                                        (str "BYTES OVERRUN on " test-sym ": actual " bytes " bytes > estimated "
                                             bytes-budget " bytes (:cost/bytes). Optimize or update estimate.")))))
        {:keys [fail error messages]} @counts
        pass? (zero? (+ fail error))]
    {:seon.test/digest digest
     :seon.test/var test-sym
     :seon.test/kind :deftest
     :seon.test/pass? pass?
     :seon.test/ms (double ms)
     :seon.test/ticks (long ticks)
     :seon.test/bytes (long bytes)
     :seon.test/report (if pass?
                         (let [s (str/trim (str sw))] (if (str/blank? s) "PASS" s))
                         (str/join "\n" (cond-> messages (not (str/blank? (str sw))) (conj (str/trim (str sw))))))}))

(defn unread-messages-for [main-db branch-db agent-id]
  (let [seen (store/agent-seen-message-ids branch-db agent-id)]
    (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
         (map #(d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq
                                 :seon.agent.message/from :seon.agent.message/to
                                 :seon.agent.message/type :seon.agent.message/body] (:e %)))
         (remove #(contains? seen (:seon.agent.message/id %)))
         (sort-by :seon.agent.message/seq)
         vec)))

(defn agent-history-turns [branch-db agent-id]
  (->> (d/datoms branch-db :avet :seon.agent.eval/agent agent-id)
       (map #(d/pull branch-db '[:seon.agent.eval/turn :seon.agent.eval/call-index
                                 :seon.agent.eval/call-id :seon.agent.eval/handle
                                 :seon.agent.eval/result-schema :seon.agent.eval/thought
                                 :seon.agent.eval/reasoning :seon.agent.eval/intent
                                 :seon.agent.eval/code :seon.agent.eval/value
                                 :seon.agent.eval/out :seon.agent.eval/error
                                 :seon.agent.eval/ms :seon.agent.eval/ticks] (:e %)))
       (group-by :seon.agent.eval/turn)
       (sort-by key)
       (mapv (fn [[turn es]]
               (let [ordered (sort-by #(or (:seon.agent.eval/call-index %) 0) es)
                     first-e (first ordered)]
                 {:turn turn
                  :thought (:seon.agent.eval/thought first-e)
                  :reasoning (:seon.agent.eval/reasoning first-e)
                  :calls (mapv (fn [e]
                                 {:id (or (:seon.agent.eval/call-id e) (str "t" turn "-c" (or (:seon.agent.eval/call-index e) 0)))
                                  :handle (:seon.agent.eval/handle e)
                                  :result-schema (:seon.agent.eval/result-schema e)
                                  :intent (:seon.agent.eval/intent e)
                                  :code (:seon.agent.eval/code e)
                                  :value (:seon.agent.eval/value e)
                                  :out (:seon.agent.eval/out e)
                                  :error (:seon.agent.eval/error e)
                                  :ms (:seon.agent.eval/ms e)
                                  :ticks (:seon.agent.eval/ticks e)})
                               ordered)})))))

(defn sanitize-edn-hiccup
  "Walks Hiccup returned by a branch-local render fn so it is guaranteed pure EDN for IPC transport."
  [x]
  (cond
    (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x)) x
    (vector? x) (mapv sanitize-edn-hiccup x)
    (map? x) (into {} (map (fn [[k v]] [(sanitize-edn-hiccup k) (sanitize-edn-hiccup v)])) x)
    (list? x) (apply list (map sanitize-edn-hiccup x))
    (seq? x) (mapv sanitize-edn-hiccup x)
    (set? x) (into #{} (map sanitize-edn-hiccup) x)
    :else (pr-str x)))
