(ns seon.render.transcript-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.ai.tokens :as tokens]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.render.transcript :as transcript]
            [seon.test-support :as support])
  (:import [java.io PushbackReader StringReader]))

(def ^:private property-seed 2026073104)
(def ^:private agent-id "transcript-agent")
(def ^:private peer-id "transcript-peer")

(defn- at
  [offset]
  (java.util.Date. (long (+ 1785500000000 offset))))

(defn- unit
  [db token-budget]
  {:seon.db/db db
   :seon.cluster.agent/id agent-id
   :seon.render.transcript/token-budget token-budget})

(defn- reader-valid?
  [text]
  (try
    (with-open [reader (PushbackReader. (StringReader. text))]
      (loop []
        (let [form (read {:eof ::eof
                          :read-cond :allow
                          :features #{:clj}}
                         reader)]
          (when-not (= ::eof form)
            (recur)))))
    true
    (catch Throwable _
      false)))

(defn- nodes
  [hiccup]
  (filter vector? (tree-seq sequential? seq hiccup)))

(defn- html-entries
  [rendered]
  (into
   []
   (keep
    (fn [node]
      (let [attributes (when (map? (nth node 1 nil)) (nth node 1))]
        (when (= "seon-transcript-entry" (:class attributes))
          {:id (:data-transcript-id attributes)
           :kind (keyword (:data-transcript-kind attributes))
           :detail (keyword (:data-transcript-detail attributes))
           :dom-id (:id attributes)}))))
   (nodes rendered)))

(defn- html-elided
  [rendered]
  (or
   (some
    (fn [node]
      (let [attributes (when (map? (nth node 1 nil)) (nth node 1))]
        (some-> (:data-transcript-elided attributes) parse-long)))
    (nodes rendered))
   0))

(defn- ai-entries
  [rendered]
  (into
   []
   (map (fn [[_ kind id detail]]
          {:id id :kind (keyword kind) :detail (keyword detail)}))
   (re-seq
    #"(?m)^;; transcript/entry :(message|eval) \"([^\"]+)\" :(full|summary)$"
    rendered)))

(defn- ai-elided
  [rendered]
  (or (some-> (re-find #"(?m)^;; transcript/elided (\d+)$" rendered)
              second
              parse-long)
      0))

(defn- seed-populated-history!
  [connection]
  (d/transact
   connection
   [{:seon.cluster.agent/id agent-id}
    {:seon.cluster.agent/id peer-id}
    {:seon.problems/id "problem-transcript"}
    {:seon.cluster.message/id "outside-0"
     :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/content "Start with the failed deployment."
     :seon.cluster.message/at (at 0)}
    {:seon.cluster.message/id "peer-1"
     :seon.cluster.message/from [:seon.cluster.agent/id peer-id]
     :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/about [:seon.problems/id "problem-transcript"]
     :seon.cluster.message/content "Repair the owning namespace."
     :seon.cluster.message/at (at 1000)}
    {:seon.cluster.run/id "run-result"
     :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
     :seon.cluster.run/opened-at (at 1500)}
    {:seon.cluster.run.form/id "form-result"
     :seon.cluster.run.form/run [:seon.cluster.run/id "run-result"]
     :seon.cluster.run.form/ordinal 0
     :seon.cluster.run.form/source "(+ 20 22)"}
    {:seon.cluster.eval/id "eval-result"
     :seon.cluster.eval/run [:seon.cluster.run/id "run-result"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 2000)
     :seon.cluster.eval/result-edn "42"}
    {:seon.cluster.message/id "send-2"
     :seon.cluster.message/from [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/to [:seon.cluster.agent/id peer-id]
     :seon.cluster.message/content "Check the repaired namespace."
     :seon.cluster.message/at (at 3000)}
    {:seon.cluster.run/id "run-wait"
     :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
     :seon.cluster.run/opened-at (at 3250)}
    {:seon.cluster.run.form/id "form-wait"
     :seon.cluster.run.form/run [:seon.cluster.run/id "run-wait"]
     :seon.cluster.run.form/ordinal 0
     :seon.cluster.run.form/source
     "(my.run/wait \"waiting for the peer review\")"}
    {:seon.cluster.eval/id "eval-wait"
     :seon.cluster.eval/run [:seon.cluster.run/id "run-wait"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 3500)
     :seon.cluster.eval/result-edn
     "{:my.run/disposition :wait :my.run/note \"waiting for the peer review\"}"}
    {:seon.cluster.message/id "decline-3"
     :seon.cluster.message/from [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/to [:seon.cluster.agent/id peer-id]
     :seon.cluster.message/about [:seon.problems/id "problem-transcript"]
     :seon.cluster.message/content "The namespace is not mine."
     :my.message/reason "The namespace is not mine."
     :seon.cluster.message/at (at 4000)}
    {:seon.cluster.run/id "run-error"
     :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
     :seon.cluster.run/opened-at (at 4250)}
    {:seon.cluster.run.form/id "form-error"
     :seon.cluster.run.form/run [:seon.cluster.run/id "run-error"]
     :seon.cluster.run.form/ordinal 0
     :seon.cluster.run.form/source "(missing.function/call)"}
    {:seon.cluster.eval/id "eval-error"
     :seon.cluster.eval/run [:seon.cluster.run/id "run-error"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 4500)
     :seon.cluster.eval/result-edn
     "{:seon.error/kind :seon.sci.eval/refused}"
     :seon.cluster.eval/error "No such namespace: missing.function"
     :seon.error/kind :seon.sci.eval/refused
     :seon.problems/id "problem-eval-error"
     :seon.cluster.eval/interrupted-at (at 4501)}
    {:seon.cluster.message/id "self-4"
     :seon.cluster.message/from [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/content "A self-addressed continuity note."
     :seon.cluster.message/at (at 5000)}]))

(deftest populated-history-restores-the-repl-fidelity-checklist
  (support/with-database
    (fn [connection]
      (seed-populated-history! connection)
      (let [request (unit @connection 100000)
            ai (transcript/render-ai request)
            html-value (transcript/render-html request)
            html (hiccup/->string html-value)
            ai-rows (ai-entries ai)
            html-rows (html-entries html-value)]
        (testing "messages and eval receipts interleave by their stored time"
          (is (= ["outside-0" "peer-1" "eval-result" "send-2"
                  "eval-wait" "decline-3" "eval-error" "self-4"]
                 (mapv :id ai-rows)))
          (is (= (mapv #(select-keys % [:id :kind :detail]) html-rows)
                 ai-rows)))
        (testing "recent entries are full reader-valid REPL history"
          (is (reader-valid? ai))
          (is (str/includes?
               ai
               "(my.message/send \"transcript-agent\" \"Repair the owning namespace.\" \"problem-transcript\")"))
          (is (str/includes?
               ai
               "(my.message/decline \"transcript-peer\" \"problem-transcript\" \"The namespace is not mine.\")"))
          (is (str/includes? ai "(+ 20 22)\n;; =>\n42"))
          (is (str/includes? ai "waiting for the peer review"))
          (is (str/includes? ai "No such namespace: missing.function"))
          (is (str/includes? ai ":seon.sci.eval/refused"))
          (is (str/includes? ai "problem-eval-error"))
          (is (str/includes? ai "Its effect may have happened")))
        (testing "old entries age only in the projection"
          (is (= [:summary :summary :full :full :full :full :full :full]
                 (mapv :detail ai-rows))))
        (testing "HTML is the same structure with stable entry ids"
          (is (= (block/surface-id :transcript) (get-in html-value [1 :id])))
          (doseq [{:keys [id kind dom-id]} html-rows]
            (is (= (block/surface-id
                    (keyword (str "seon.transcript." (name kind)) id))
                   dom-id)))
          (is (str/includes? html "waiting for the peer review")))))))

(deftest a-tight-budget-degrades-then-elides-loudly
  (support/with-database
    (fn [connection]
      (seed-populated-history! connection)
      (let [db @connection
            floor (transcript/minimum-token-budget (unit db 0))
            budget (+ floor 180)
            request (unit db budget)
            ai (transcript/render-ai request)
            html-value (transcript/render-html request)
            html (hiccup/->string html-value)
            visible (ai-entries ai)
            elided (ai-elided ai)]
        (is (pos? floor))
        (is (pos? elided))
        (is (= 8 (+ elided (count visible))))
        (is (= elided (html-elided html-value)))
        (is (= (mapv :id visible) (mapv :id (html-entries html-value))))
        (is (str/includes? ai (str elided " older transcript entr")))
        (is (str/includes? html (str elided " older transcript entr")))
        (is (<= (tokens/estimate ai) budget))
        (is (<= (tokens/estimate html) budget))
        (is (reader-valid? ai))))))

(deftest malformed-receipt-bytes-and-any-unique-about-stay-replayable
  (support/with-database
    (fn [connection]
      (d/transact
       connection
       [{:seon.cluster.agent/id agent-id}
        {:seon.cluster.agent/id peer-id}
        {:seon.test/sym "target-fact"}
        {:seon.cluster.message/id "about-test"
         :seon.cluster.message/from [:seon.cluster.agent/id agent-id]
         :seon.cluster.message/to [:seon.cluster.agent/id peer-id]
         :seon.cluster.message/about [:seon.test/sym "target-fact"]
         :seon.cluster.message/content "Inspect the test fact."
         :seon.cluster.message/at (at 0)}
        {:seon.cluster.run/id "run-malformed"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/opened-at (at 500)}
        {:seon.cluster.run.form/id "form-malformed"
         :seon.cluster.run.form/run [:seon.cluster.run/id "run-malformed"]
         :seon.cluster.run.form/ordinal 0
         :seon.cluster.run.form/source "("}
        {:seon.cluster.eval/id "eval-malformed"
         :seon.cluster.eval/run [:seon.cluster.run/id "run-malformed"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at (at 1000)
         :seon.cluster.eval/result-edn "{"}])
      (let [ai (transcript/render-ai (unit @connection 100000))]
        (is (reader-valid? ai))
        (is (str/includes?
             ai
             "(my.message/send \"transcript-peer\" \"Inspect the test fact.\" \"target-fact\")"))
        (is (str/includes? ai ":seon.cluster.run.form/source \"(\""))
        (is (str/includes? ai ":seon.cluster.eval/result-edn \"{\""))
        (is (= ["about-test" "eval-malformed"]
               (mapv :id (ai-entries ai))))))))

(deftest tight-budgets-pull-only-a-budget-derived-newest-candidate-set
  (support/with-database
    (fn [connection]
      (d/transact
       connection
       (into [{:seon.cluster.agent/id agent-id}]
             (map (fn [index]
                    {:seon.cluster.message/id (str "bounded-" index)
                     :seon.cluster.message/to
                     [:seon.cluster.agent/id agent-id]
                     :seon.cluster.message/content (str "message " index)
                     :seon.cluster.message/at (at index)}))
             (range 100)))
      (let [db @connection
            floor (transcript/minimum-token-budget (unit db 0))
            pulled (atom [])
            pull-many d/pull-many
            ai (with-redefs [d/pull-many
                             (fn [database selector entity-ids]
                               (swap! pulled conj (count entity-ids))
                               (pull-many database selector entity-ids))]
                 (transcript/render-ai (unit db floor)))]
        (is (= 100 (ai-elided ai)))
        (is (every? #(<= % (max 6 floor)) @pulled))
        (is (<= (tokens/estimate ai) floor))))))

(def ^:private message-event-kinds
  #{:message-in :message-out :message-self :message-about :message-decline})

(def ^:private history-generator
  (gen/vector
   (gen/tuple
    (gen/elements [:message-in :message-out :message-self
                   :message-about :message-decline
                   :receipt-result :receipt-error
                   :receipt-interrupted :receipt-running :receipt-wait
                   :receipt-invalid :receipt-mixed])
    (gen/choose 0 8)
    (gen/fmap #(if (str/blank? %) "x" %) gen/string-alphanumeric))
   0 18))

(defn- generated-event
  [index [event-kind instant-offset content]]
  (let [id (str "event-" index)
        event-at (at (* 1000 instant-offset))
        message? (contains? message-event-kinds event-kind)]
    {:source-index index
     :event-kind event-kind
     :id id
     :kind (if message? :message :eval)
     :at event-at
     :content content}))

(defn- generated-rows
  [{:keys [source-index event-kind id content]
    event-at :at}]
  (if (contains? message-event-kinds event-kind)
    [(cond-> {:seon.cluster.message/id id
              :seon.cluster.message/to
              [:seon.cluster.agent/id
               (if (= :message-out event-kind) peer-id agent-id)]
              :seon.cluster.message/content content
              :seon.cluster.message/at event-at}
       (not= :message-in event-kind)
       (assoc :seon.cluster.message/from
              [:seon.cluster.agent/id agent-id])
       (= :message-self event-kind)
       (assoc :seon.cluster.message/to [:seon.cluster.agent/id agent-id])
       (contains? #{:message-about :message-decline} event-kind)
       (assoc :seon.cluster.message/about [:seon.test/sym "generated-target"])
       (= :message-decline event-kind)
       (assoc :my.message/reason content))]
    (let [run-id (str "run-" id)
          form-id (str "form-" id)
          source (if (= :receipt-invalid event-kind)
                   "("
                   (str "(identity " source-index ")"))]
      [{:seon.cluster.run/id run-id
        :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
        :seon.cluster.run/opened-at event-at}
       {:seon.cluster.run.form/id form-id
        :seon.cluster.run.form/run [:seon.cluster.run/id run-id]
        :seon.cluster.run.form/ordinal 0
        :seon.cluster.run.form/source source}
       (cond-> {:seon.cluster.eval/id id
                :seon.cluster.eval/run [:seon.cluster.run/id run-id]
                :seon.cluster.eval/ordinal 0
                :seon.cluster.eval/at event-at}
         (= :receipt-result event-kind)
         (assoc :seon.cluster.eval/result-edn (pr-str source-index))
         (= :receipt-error event-kind)
         (assoc :seon.cluster.eval/error content)
         (= :receipt-interrupted event-kind)
         (assoc :seon.cluster.eval/interrupted-at event-at)
         (= :receipt-wait event-kind)
         (assoc :seon.cluster.eval/result-edn
                (pr-str {:my.run/disposition :wait
                         :my.run/note content}))
         (= :receipt-invalid event-kind)
         (assoc :seon.cluster.eval/result-edn "{")
         (= :receipt-mixed event-kind)
         (assoc :seon.cluster.eval/result-edn
                (pr-str {:seon.error/kind :generated/refusal})
                :seon.cluster.eval/error content
                :seon.error/kind :generated/refusal
                :seon.problems/id (str "problem-" id)
                :seon.cluster.eval/interrupted-at event-at
                :seon.cluster.eval/output content))])))

(defn- expected-order
  [events]
  (sort-by
   (fn [{:keys [kind id] event-at :at}]
     [(.getTime ^java.util.Date event-at)
      (case kind :message 0 :eval 1)
      id])
   events))

(deftest every-generated-history-is-ordered-total-and-token-bounded
  (let [check
        (tc/quick-check
         40
         (prop/for-all
          [history history-generator
           extra-budget (gen/choose 0 800)]
          (support/with-database
            (fn [connection]
              (let [events (mapv generated-event (range) history)
                    rows (into [{:seon.cluster.agent/id agent-id}
                                {:seon.cluster.agent/id peer-id}
                                {:seon.test/sym "generated-target"}]
                               (mapcat generated-rows)
                               events)]
                (d/transact connection rows)
                (let [db @connection
                      floor (transcript/minimum-token-budget (unit db 0))
                      budget (+ floor extra-budget)
                      request (unit db budget)
                      ai (transcript/render-ai request)
                      html-value (transcript/render-html request)
                      html (hiccup/->string html-value)
                      ai-rows (ai-entries ai)
                      html-rows (html-entries html-value)
                      visible-ids (mapv :id ai-rows)
                      ordered-ids (mapv :id (expected-order events))
                      elided (ai-elided ai)]
                  (and
                   (= ai-rows
                      (mapv #(select-keys % [:id :kind :detail]) html-rows))
                   (= elided (html-elided html-value))
                   (= (count events) (+ elided (count ai-rows)))
                   (= visible-ids (subvec ordered-ids elided))
                   (= (count visible-ids) (count (distinct visible-ids)))
                   (or (zero? elided)
                       (and (str/includes? ai "older transcript entr")
                            (str/includes? html "older transcript entr")))
                   (<= (tokens/estimate ai) budget)
                   (<= (tokens/estimate html) budget)
                   (reader-valid? ai)))))))
         :seed property-seed)]
    (support/assert-check!
     check
     "Every transcript must preserve time order, totality, and its budget.")))
