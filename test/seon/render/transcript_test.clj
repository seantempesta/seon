(ns seon.render.transcript-test
  (:require [clojure.main :as main]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.ai.tokens :as tokens]
            [seon.blob :as blob]
            [seon.bootstrap :as bootstrap]
            [seon.render.block :as block]
            [seon.render.hiccup :as hiccup]
            [seon.render.transcript :as transcript]
            [seon.render.walk :as walk]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(def ^:private property-seed 2026073104)
(def ^:private agent-id "transcript-agent")
(def ^:private peer-id "transcript-peer")
(def ^:private caps
  {:seon.config.eval.result/max-depth 12
   :seon.config.eval.result/max-collection 64
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 4096})

(defn- at
  [offset]
  (java.util.Date. (long (+ 1785500000000 offset))))

(defn- unit
  ([db token-budget]
   (unit db token-budget caps))
  ([db token-budget render-caps]
   {:seon.db/db db
    :seon.sci.eval/ctx (sci.eval/cluster-ctx db)
    :seon.sci.eval/time-limit-ms 1000
    :seon.config/on-core-error :record
    :seon.cluster.agent/id agent-id
    :seon.render.transcript/token-budget token-budget
    :seon.sci.admit/caps render-caps}))

(defn- full-agent-ai
  [db]
  (walk/prose
   db
   (walk/neighborhood
    {:seon.db/db db
     :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
     :seon.render/output :seon.render/ai
     :seon.render/distance 2
     :seon.sci.eval/ctx (sci.eval/cluster-ctx db)
     :seon.sci.eval/time-limit-ms 1000
     :seon.config/on-core-error :record
     :seon.sci.admit/caps caps})))

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

(defn- html-entry-node
  [rendered entry-id]
  (some
   (fn [node]
     (let [attributes (when (map? (nth node 1 nil)) (nth node 1))]
       (when (= entry-id (:data-transcript-id attributes))
         node)))
   (nodes rendered)))

(def ^:private forbidden-session-narration
  ["Form 0 returned"
   "Form 0 failed"
   "It printed:"
   "(comment "
   ";; transcript/entry"
   ";; transcript/elided"
   "is still running"
   "was interrupted"])

(defn- assert-no-session-narration
  [rendered]
  (doseq [forbidden forbidden-session-narration]
    (is (not (str/includes? rendered forbidden))
        (str "session contains invented display grammar: " forbidden))))

(defn- arithmetic-triage-edn
  []
  (try
    (/ 1 0)
    (catch Throwable throwable
      (pr-str (main/ex-triage (Throwable->map throwable))))))

(defn- seed-populated-history!
  [connection]
  (db/transact!
   connection
   [{:seon.ns/name 'my.agents.transcript}
    {:seon.cluster.agent/id agent-id
     :seon.cluster.agent/namespace [:seon.ns/name 'my.agents.transcript]}
    {:seon.cluster.agent/id peer-id}
    {:seon.problems/id "problem-transcript"}
    {:seon.cluster.message/id "outside-0"
     :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/content "Start with the failed deployment."
     :my.message/reason "An external observation, not this agent's decline."
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
     :seon.cluster.run.form/source
     ";; calculate the answer\n(do (println \"side effect\") (+ 20 22))"
     :seon.cluster.run.form/ns [:seon.ns/name 'my.agents.transcript]}
    {:seon.cluster.eval/id "eval-result"
     :seon.cluster.eval/run [:seon.cluster.run/id "run-result"]
     :seon.cluster.eval/ordinal 0
     :seon.cluster.eval/at (at 2000)
     :seon.cluster.eval/ns [:seon.ns/name 'my.agents.transcript]
     :seon.cluster.eval/output "side effect\n"
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
     :seon.cluster.message/content "I cannot make the requested edit."
     :my.message/reason "The namespace is owned by another agent."
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
     :seon.cluster.eval/triage-edn (arithmetic-triage-edn)
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
            html-rows (html-entries html-value)]
        (testing "messages and eval receipts interleave by their stored time"
          (is (= ["outside-0" "peer-1" "eval-result" "send-2"
                  "eval-wait" "decline-3" "eval-error" "self-4"]
                 (mapv :id html-rows))))
        (testing "recent receipts reproduce prompt, input, output, and result"
          (is (str/includes?
               ai
               "Agent transcript-peer said to transcript-agent: Repair the owning namespace."))
          (is (str/includes?
               ai
               "Agent transcript-agent said to transcript-peer: I cannot make the requested edit."))
          (is (str/includes? ai "The namespace is owned by another agent."))
          (is (str/includes? ai "An external observation, not this agent's decline."))
          (is (str/includes? ai "From outside this cluster to transcript-agent"))
          (is (not (str/includes? ai
                                  "Agent transcript-agent said to transcript-agent: Start with")))
          (is (str/includes?
               ai
               (str "my.agents.transcript=> ;; calculate the answer\n"
                    "(do (println \"side effect\") (+ 20 22))\n"
                    "side effect\n42")))
          (is (str/includes? ai "waiting for the peer review"))
          (is (str/includes? ai "Execution error (ArithmeticException) at"))
          (is (str/includes? ai "Divide by zero"))
          (assert-no-session-narration ai))
        (testing "old entries age only in the projection"
          (is (= [:summary :summary :full :full :full :full :full :full]
                 (mapv :detail html-rows))))
        (testing "HTML is the same structure with stable entry ids"
          (is (= (block/surface-id :transcript) (get-in html-value [1 :id])))
          (doseq [{:keys [id kind dom-id]} html-rows]
            (is (= (block/surface-id
                    (keyword (str "seon.transcript." (name kind)) id))
                   dom-id)))
          (is (str/includes? html "waiting for the peer review")))))))

(deftest error-receipt-without-triage-has-an-execution-error-face
  (support/with-database
    (fn [connection]
      (db/transact!
       connection
       [{:seon.cluster.agent/id agent-id}
        {:seon.cluster.run/id "run-error-without-triage"
         :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
         :seon.cluster.run/opened-at (at 0)}
        {:seon.cluster.run.form/id "form-error-without-triage"
         :seon.cluster.run.form/run
         [:seon.cluster.run/id "run-error-without-triage"]
         :seon.cluster.run.form/ordinal 0
         :seon.cluster.run.form/source "(missing.function/call)"}
        {:seon.cluster.eval/id "eval-error-without-triage"
         :seon.cluster.eval/run
         [:seon.cluster.run/id "run-error-without-triage"]
         :seon.cluster.eval/ordinal 0
         :seon.cluster.eval/at (at 1000)
         :seon.cluster.eval/error "No such namespace: missing.function"}])
      (let [request (unit @connection 100000)
            ai (transcript/render-ai request)
            html-value (transcript/render-html request)
            html-entry (html-entry-node html-value
                                        "eval-error-without-triage")
            html-text (get-in html-entry [2 1 1])]
        (testing "the AI projection presents the form and an execution error"
          (is (str/includes? ai "user=> (missing.function/call)"))
          (is (some #(str/starts-with? % "Execution error")
                    (str/split-lines ai)))
          (is (some #{"No such namespace: missing.function"}
                    (str/split-lines ai))))
        (testing "the HTML entry structurally identifies the same error face"
          (is (= "true" (get-in html-entry [1 :data-transcript-error])))
          (is (some #(str/starts-with? % "Execution error")
                    (str/split-lines html-text)))
          (is (some #{"No such namespace: missing.function"}
                    (str/split-lines html-text))))))))

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
            visible (html-entries html-value)
            elided (html-elided html-value)]
        (is (pos? floor))
        (is (pos? elided))
        (is (= 8 (+ elided (count visible))))
        (is (str/includes? ai (str elided " older transcript entr")))
        (is (str/includes? html (str elided " older transcript entr")))
        (is (<= (tokens/estimate ai) budget))
        (is (<= (tokens/estimate html) budget))
        (assert-no-session-narration ai)))))

(defn- seed-pinned-bootstrap-history!
  [connection]
  (let [bootstrap-run-id (bootstrap/run-id agent-id)
        bootstrap-receipts
        (mapcat
         (fn [ordinal]
           (let [row-id (pr-str [bootstrap-run-id ordinal])]
             [{:seon.cluster.run.form/id row-id
               :seon.cluster.run.form/run
               [:seon.cluster.run/id bootstrap-run-id]
               :seon.cluster.run.form/ordinal ordinal
               :seon.cluster.run.form/source (str "(identity " ordinal ")")}
              {:seon.cluster.eval/id row-id
               :seon.cluster.eval/run
               [:seon.cluster.run/id bootstrap-run-id]
               :seon.cluster.eval/ordinal ordinal
               :seon.cluster.eval/at (at 0)
               :seon.cluster.eval/result-edn (pr-str ordinal)}]))
         (range 13))
        messages
        (concat
         (map (fn [index]
                {:seon.cluster.message/id (str "middle-" index)
                 :seon.cluster.message/to
                 [:seon.cluster.agent/id agent-id]
                 :seon.cluster.message/content
                 (str "middle history " index " " (apply str (repeat 80 "x")))
                 :seon.cluster.message/at (at (+ 100 index))})
              (range 40))
         (map (fn [index]
                {:seon.cluster.message/id (str "newest-" index)
                 :seon.cluster.message/to
                 [:seon.cluster.agent/id agent-id]
                 :seon.cluster.message/content (str "newest history " index)
                 :seon.cluster.message/at (at (+ 1000 index))})
              (range 6)))]
    (db/transact!
     connection
     (into [{:seon.cluster.agent/id agent-id}
            {:seon.cluster.run/id bootstrap-run-id
             :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
             :seon.cluster.run/opened-at (at 0)}]
           cat
           [bootstrap-receipts messages]))))

(deftest same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order
  (support/with-database
    (fn [connection]
      (seed-pinned-bootstrap-history! connection)
      (let [db @connection
            floor (transcript/minimum-token-budget (unit db 0))
            budget (+ floor 1000)
            ai (transcript/render-ai (unit db budget))
            html-value (transcript/render-html (unit db budget))
            html (hiccup/->string html-value)
            html-rows (html-entries html-value)
            bootstrap-run-id (bootstrap/run-id agent-id)
            pinned-ids (mapv #(pr-str [bootstrap-run-id %]) (range 13))
            newest-ids (mapv #(str "newest-" %) (range 6))
            visible-ids (mapv :id html-rows)
            ai-positions
            (mapv #(.indexOf ai (str "user=> (identity " % ")\n" %))
                  (range 13))
            pinned-end (.indexOf ai "user=> (identity 12)\n12")
            marker-start (.indexOf ai "middle transcript entries elided")
            newest-start (.indexOf ai "newest history 0")]
        (is (pos? (html-elided html-value)))
        (is (= pinned-ids (subvec visible-ids 0 13)))
        (is (every? #(<= 0 %) ai-positions))
        (is (apply < ai-positions))
        (is (every? #(= :full (:detail %)) (take 13 html-rows)))
        (is (= newest-ids (subvec visible-ids (- (count visible-ids) 6))))
        (is (< pinned-end marker-start newest-start))
        (is (str/includes? ai "middle transcript entries elided"))
        (is (< (.indexOf html (last pinned-ids))
               (.indexOf html "seon-transcript-elision")
               (.indexOf html (first newest-ids))))
        (is (<= (tokens/estimate ai) budget))
        (is (<= (tokens/estimate html) budget))
        (assert-no-session-narration ai)))))

(deftest supersession-chains-vanish-before-token-accounting
  (support/with-database
    (fn [connection]
      (let [bootstrap-run-id (bootstrap/run-id agent-id)]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id}
          {:seon.cluster.run/id bootstrap-run-id
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 0)}
          {:seon.cluster.run.form/id "bootstrap-form"
           :seon.cluster.run.form/run
           [:seon.cluster.run/id bootstrap-run-id]
           :seon.cluster.run.form/ordinal 0
           :seon.cluster.run.form/source "(identity :bootstrap)"}
          {:seon.cluster.eval/id "bootstrap-receipt"
           :seon.cluster.eval/run [:seon.cluster.run/id bootstrap-run-id]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 1)
           :seon.cluster.eval/result-edn ":bootstrap"}

          {:seon.cluster.run/id "original"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 100)}
          {:seon.cluster.run.form/id "original-receipt-form"
           :seon.cluster.run.form/run [:seon.cluster.run/id "original"]
           :seon.cluster.run.form/ordinal 0
           :seon.cluster.run.form/source "(identity :original)"}
          {:seon.cluster.eval/id "original-receipt"
           :seon.cluster.eval/run [:seon.cluster.run/id "original"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 101)
           :seon.cluster.eval/result-edn ":original"}
          {:seon.cluster.run.form/id "original-comment"
           :seon.cluster.run.form/run [:seon.cluster.run/id "original"]
           :seon.cluster.run.form/ordinal 1
           :seon.cluster.run.form/source "; original comment"}

          {:seon.cluster.run/id "curated"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 200)
           :seon.cluster.run/supersedes
           [[:seon.cluster.run/id "original"]]}
          {:seon.cluster.run.form/id "curated-form"
           :seon.cluster.run.form/run [:seon.cluster.run/id "curated"]
           :seon.cluster.run.form/ordinal 0
           :seon.cluster.run.form/source "(identity :curated)"}
          {:seon.cluster.eval/id "curated-receipt"
           :seon.cluster.eval/run [:seon.cluster.run/id "curated"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 201)
           :seon.cluster.eval/result-edn ":curated"}

          {:seon.cluster.run/id "proof"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 300)
           :seon.cluster.run/supersedes
           [[:seon.cluster.run/id "curated"]]}
          {:seon.cluster.run.form/id "proof-receipt-form"
           :seon.cluster.run.form/run [:seon.cluster.run/id "proof"]
           :seon.cluster.run.form/ordinal 0
           :seon.cluster.run.form/source "(identity :proof)"}
          {:seon.cluster.eval/id "proof-receipt"
           :seon.cluster.eval/run [:seon.cluster.run/id "proof"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 301)
           :seon.cluster.eval/result-edn ":proof"}
          {:seon.cluster.run.form/id "proof-comment"
           :seon.cluster.run.form/run [:seon.cluster.run/id "proof"]
           :seon.cluster.run.form/ordinal 1
           :seon.cluster.run.form/source "; proof comment"}])
        (let [db @connection
              floor (transcript/minimum-token-budget (unit db 0))
              at-floor (transcript/render-html (unit db floor))
              full (transcript/render-html (unit db 100000))
              visible (mapv :id (html-entries full))]
          (is (= 2 (html-elided at-floor))
              "only the active proof receipt and comment are budget-elided")
          (is (= "bootstrap-receipt" (first visible)))
          (is (= #{"bootstrap-receipt" "proof-receipt" "proof-comment"}
                 (set visible)))
          (is (db/pull db '[*]
                       [:seon.cluster.eval/id "original-receipt"]))
          (is (db/pull db '[*]
                       [:seon.cluster.eval/id "curated-receipt"])))))))

(deftest malformed-receipt-bytes-and-any-unique-about-stay-replayable
  (support/with-database
    (fn [connection]
      (db/transact!
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
        (is (str/includes?
             ai
             "Agent transcript-agent said to transcript-peer: Inspect the test fact."))
        (is (str/includes? ai "user=> ("))
        (is (str/includes? ai ":seon.cluster.eval/result-edn \"{\""))
        (assert-no-session-narration ai)))))

(deftest receipt-content-enters-the-shared-capped-floor
  (support/with-database
    (fn [connection]
      (let [result (into {}
                         (map (fn [index]
                                [(keyword "audit" (str "field-" index))
                                 (str "long-value-" index)]))
                         (range 40))
            narrow-caps (assoc caps
                               :seon.config.eval.result/max-collection 3
                               :seon.config.eval.result/max-string 8)]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id}
          {:seon.cluster.run/id "run-capped"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 0)}
          {:seon.cluster.run.form/id "form-capped"
           :seon.cluster.run.form/run [:seon.cluster.run/id "run-capped"]
           :seon.cluster.run.form/ordinal 0
           :seon.cluster.run.form/source "(identity result)"}
          {:seon.cluster.eval/id "eval-capped"
           :seon.cluster.eval/run [:seon.cluster.run/id "run-capped"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 1000)
           :seon.cluster.eval/result-edn (pr-str result)}])
        (let [ai (transcript/render-ai
                  (unit @connection 100000 narrow-caps))]
          (is (str/includes? ai "…"))
          (is (str/includes? ai "elided"))
          (is (not (str/includes? ai ":audit/field-39")))
          (assert-no-session-narration ai))))))

(deftest capped-state-is-derived-from-receipt-size-without-a-boolean
  (support/with-database
    (fn [connection]
      (let [stored "[0 1 :seon.sci.admit/elided]"
            digest (apply str (repeat 64 "b"))]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id}
          {:seon.cluster.run/id "run-blobbed"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 0)}
          {:seon.cluster.run.form/id "form-blobbed"
           :seon.cluster.run.form/run [:seon.cluster.run/id "run-blobbed"]
           :seon.cluster.run.form/ordinal 0
           :seon.cluster.run.form/source "(range 100000)"}
          {:seon.cluster.eval/id "eval-blobbed"
           :seon.cluster.eval/run [:seon.cluster.run/id "run-blobbed"]
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at (at 1000)
           :seon.cluster.eval/result-edn stored
           :seon.cluster.eval/result-blob digest
           :seon.cluster.eval/result-size 189000}])
        (let [receipt (db/pull @connection '[*]
                              [:seon.cluster.eval/id "eval-blobbed"])]
          (is (transcript/capped-result? receipt))
          (is (not (contains? receipt :seon.sci.admit/capped?)))
          (let [ai (transcript/render-ai (unit @connection 100000))]
            (is (str/includes? ai stored))
            (is (not (str/includes? ai "CAPPED:")))
            (is (not (str/includes? ai digest)))
            (assert-no-session-narration ai)))))))

(deftest reasoning-is-html-only-and-inline-blob-history-has-one-disclosure
  (support/with-database
    (fn [connection]
      (let [reasoning "First line of thought\nThen the detail."
            digest (apply str (repeat 64 "d"))
            base-attempt
            {:seon.ai.attempt/run [:seon.cluster.run/id "run-reasoning"]
             :seon.ai.attempt/at (at 500)
             :seon.ai/endpoint "https://provider.invalid"
             :seon.ai/model "fixture-thinking"
             :seon.ai.attempt/settings-edn "{}"}]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id}
          {:seon.cluster.run/id "run-reasoning"
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/opened-at (at 0)}
          (assoc base-attempt
                 :seon.ai.attempt/id "reasoning-inline"
                 :seon.ai.attempt/ordinal 0)])
        (let [before (full-agent-ai @connection)]
          (db/transact!
           connection
           [[:db/add [:seon.ai.attempt/id "reasoning-inline"]
             :seon.ai.attempt/reasoning reasoning]])
          (let [after (full-agent-ai @connection)
                without-basis #(str/replace % #"basis=\d+" "basis=<same>")]
            (is (= (without-basis before) (without-basis after))
                "the complete agent projection is byte-identical when reasoning appears")
            (is (not (str/includes? after reasoning)))
            (is (not (str/includes? after digest)))))
        (db/transact!
         connection
         [(assoc base-attempt
                 :seon.ai.attempt/id "reasoning-blob"
                 :seon.ai.attempt/ordinal 1
                 :seon.ai.attempt/at (at 600)
                 :seon.ai.attempt/reasoning-blob digest
                 :seon.ai.attempt/reasoning-size (long (count reasoning)))])
        (let [request (assoc (unit @connection 100000)
                             :seon.db/connection connection)
              rendered
              (with-redefs [blob/get (fn [actual-connection actual-digest]
                                       (is (identical? connection actual-connection))
                                       (is (= digest actual-digest))
                                       reasoning)]
                (transcript/render-html request))
              disclosures
              (into []
                    (filter (fn [node]
                              (= "seon-attempt-reasoning"
                                 (get-in node [1 :class]))))
                    (nodes rendered))]
          (is (= 2 (count disclosures)))
          (is (= (first disclosures) (second disclosures))
              "inline and blob-backed history use the same disclosure block")
          (doseq [disclosure disclosures]
            (is (not (contains? (second disclosure) :open))
                "the disclosure is collapsed by default")
            (is (= "First line of thought…" (get-in disclosure [2 1 1])))
            (is (= reasoning (get-in disclosure [3 2 1 1])))))))))

(deftest tight-budgets-pull-only-a-budget-derived-newest-candidate-set
  (support/with-database
    (fn [connection]
      (db/transact!
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
            pull-many db/pull-many
            ai (with-redefs [db/pull-many
                             (fn [database selector entity-ids]
                               (swap! pulled conj (count entity-ids))
                               (pull-many database selector entity-ids))]
                 (transcript/render-ai (unit db floor)))]
        (is (str/includes? ai "100 older transcript entries elided"))
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
              :seon.cluster.message/ordinal source-index
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
       (assoc :my.message/reason (str "declined: " content)))]
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
   (fn [{:keys [kind id source-index] event-at :at}]
     [(.getTime ^java.util.Date event-at)
      (case kind :message 0 :eval 1)
      (if (= :message kind) source-index id)])
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
                (db/transact! connection rows)
                (let [db @connection
                      floor (transcript/minimum-token-budget (unit db 0))
                      budget (+ floor extra-budget)
                      request (unit db budget)
                      ai (transcript/render-ai request)
                      html-value (transcript/render-html request)
                      html (hiccup/->string html-value)
                      html-rows (html-entries html-value)
                      visible-ids (mapv :id html-rows)
                      ordered-ids (mapv :id (expected-order events))
                      elided (html-elided html-value)
                      visible-id-set (set visible-ids)
                      events-by-id (into {} (map (juxt :id identity)) events)
                      visible-declines
                      (filter #(and (= :message-decline (:event-kind %))
                                    (contains? visible-id-set (:id %)))
                              events)]
                  (and
                   (= (count events) (+ elided (count html-rows)))
                   (= visible-ids (subvec ordered-ids elided))
                   (= (count visible-ids) (count (distinct visible-ids)))
                   (or (zero? elided)
                       (and (str/includes? ai "older transcript entr")
                            (str/includes? html "older transcript entr")))
                   (every? (fn [{:keys [content]}]
                             (and (str/includes? ai content)
                                  (str/includes? ai
                                                 (str "declined: " content))))
                           visible-declines)
                   (every?
                    (fn [id]
                      (let [{:keys [kind content source-index]}
                            (get events-by-id id)]
                        (if (= :message kind)
                          (str/includes? ai content)
                          (str/includes? ai
                                         (if (= :receipt-invalid
                                                (:event-kind
                                                 (get events-by-id id)))
                                           "user=> ("
                                           (str "(identity " source-index ")"))))))
                    visible-ids)
                   (<= (tokens/estimate ai) budget)
                   (<= (tokens/estimate html) budget)
                   (not-any? #(str/includes? ai %)
                             forbidden-session-narration)))))))
         :seed property-seed)]
    (support/assert-check!
     check
     "Every transcript must preserve time order, totality, and its budget.")))
