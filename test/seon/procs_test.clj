(ns seon.procs-test
  "Every proc's step-fn, called as a plain function (no graph, no Flow), checked against
  its `:malli/schema` (seon.step/step-schema) and against what each arity must do.
  Run: bin/test seon.procs-test (proto/flow f2-final test/proto/procs_test.clj)"
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [malli.core :as m] [seon.contract]
            [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.step :as step] [seon.db :as pdb] [seon.sock :as sock]
            [seon.writer :as writer] [seon.reconcile :as reconcile] [seon.fork-server :as server]
            [seon.agent-proc :as ap] [seon.root :as root] [seon.repl :as repl]
            [taoensso.trove :as trove]))

(trove/set-log-fn! nil)

(def ^:private describe-ok? (m/validator (seon.contract/schema :seon.step/describe)))
(def ^:private step-out-ok? (m/validator (seon.contract/schema [:tuple :map :seon.step/outputs])))

(defn- arity-0 [v] (let [d (v)] (is (describe-ok? d) (str v " describe")) d))
(defn- step [v st in msg] (let [r (v st in msg)] (is (step-out-ok? r) (str v " transform " in)) r))

(deftest every-step-fn-carries-the-contract
  (doseq [v [#'writer/writer-proc #'reconcile/reconciler-proc #'server/fork-server-proc #'ap/agent-proc #'root/api-proc
             #'server/server-step #'repl/agent-step]]
    (is (= :seon.step/step-fn (:malli/schema (meta v))) (str v))
    (arity-0 v)))

(deftest writer
  (let [[cfg conn] (pdb/create! 100)
        in (a/chan 16)
        st (#'writer/writer-proc {:conn conn :cfg cfg :recovered [] :boot-main @conn :durable? false :to-writer in :events (a/chan 16)})
        t0 (:max-tx @conn)]
    (testing "a main transaction: committed once, its report to the reconciler, its commit to the fork server, its answer by dest"
      (let [[st outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "a"}] :dest {:rid 1}})]
        (is (= (inc t0) (:max-tx @conn)))
        (is (= [:tx] (map :op (:to-fork-server outs))))
        (is (= [(inc t0)] (map :t (:main outs))))
        (is (= {:rid 1} (:dest (first (:to-api outs)))))
        (is (true? (:ok (:r (first (:to-api outs))))))
        (testing "group commit: three queued transactions, one step, one commit"
          (a/>!! in {:op :tx :branch :db :tx-data [{:note/text "c"}] :dest {:rid 3}})
          (a/>!! in {:op :tx :branch :db :tx-data [{:no/such 1}] :dest {:rid 4}})
          (let [[st2 outs] (step #'writer/writer-proc st :in {:op :tx :branch :db :tx-data [{:note/text "b"}] :dest {:rid 2}})
                answers (into {} (keep (fn [x] (when-let [r (get-in x [:dest :rid])] [r (:r x)]))) (:to-api outs))]
            (is (= 1 (- (:commits st2) (:commits st))))
            (is (every? :ok (map answers [2 3])))
            (is (false? (:ok (answers 4))) "a refused transaction answers its error, the others commit")))))
    (testing "a closed in-port: drained"
      (let [[_ outs] (step #'writer/writer-proc st :in nil)]
        (is (= :drained (:op (first (:to-api outs)))))))
    (testing "the stop transition releases the branch connections"
      (is (map? (#'writer/writer-proc st ::flow/stop))))))

(deftest reconciler
  (let [seen (atom nil)
        st (#'reconcile/reconciler-proc {:on-main #(reset! seen (:t %))})
        [st outs] (step #'reconcile/reconciler-proc st :main {:t 7 :ops [] :db nil :ns (sock/now-ns)})]
    (is (= 7 @seen))
    (is (= 1 (:seen st)))
    (is (= :main-seen-us (:k (first (:to-api outs)))))))

(deftest fork-server-events
  (let [st {:waiters (conj clojure.lang.PersistentQueue/EMPTY {:rid 5 :req {:op :stats}}) :pid 1 :log []}]
    (testing "a stats event answers the oldest waiter, by rid"
      (let [[st outs] (step #'server/fork-server-proc st :ev {:ev :stats :t 3})]
        (is (empty? (:waiters st)))
        (is (= {:rid 5} (:dest (first (:to-api outs)))))))
    (testing "an event about an agent goes on to the api"
      (let [[_ outs] (step #'server/fork-server-proc st :ev {:ev :exit :pid 9 :status {:exit 0}})]
        (is (= [9] (map :pid (:to-api outs))))))
    (testing "EOF of an older server is ignored"
      (let [[st2 outs] (step #'server/fork-server-proc st :ev {:ev :eof :pid 2})]
        (is (= st st2)) (is (nil? outs))))))

(deftest agent-stand-in
  (let [[r a] (sock/socketpair)
        st (#'ap/agent-proc {:id :a1 :fd r :branch :agent-1 :from (a/chan 4) :to-writer (a/chan 4) :back (a/chan 4) :events (a/chan 4)})
        st (assoc st :pid 42 :inflight {7 {:kind :eval}})]
    (testing "a reply answers its request by id and reports the agent idle"
      (let [[st outs] (step #'ap/agent-proc st :from {:msg {:op :reply :id 7 :items [{:printed "3"}] :t 1}})]
        (is (= {:rid 7} (:dest (first (:to-api outs)))))
        (is (empty? (:inflight st)))
        (is (some #(= :idle (get-in % [:set :status])) (:to-api outs)))))
    (testing "the agent's write goes to the writer, its answer addressed back to this agent"
      (let [[_ outs] (step #'ap/agent-proc st :from {:msg {:op :tx :k 1 :tx-data [{:note/text "x"}]}})]
        (is (= {:agent :a1 :op :tx-reply :k 1} (:dest (first (:to-writer outs)))))))
    (testing ":dead ends the agent, naming the requests it leaves unanswered"
      (let [[st outs] (step #'ap/agent-proc st :from {:msg {:op :dead}})]
        (is (:dead? st))
        (is (= [:ended] (map :op (filter :op (:to-api outs)))))
        (is (= [7] (:inflight (first (filter #(= :ended (:op %)) (:to-api outs))))))))
    (sock/close! a)))

(deftest api-entrance
  (let [st (#'root/api-proc {:ext (a/chan 4) :back (a/chan 4) :to-writer (a/chan 4) :events (a/chan 4)
                          :view {:agents {}} :graph (promise) :root-procs (promise) :root-conns []})]
    (testing "the view is answered to its caller's promise"
      (let [p (promise)
            [_ outs] (step #'root/api-proc st :ext {:op :state :p p})]
        (is (= {:agents {}} @p)) (is (= {} outs))))
    (testing "a request for no live agent answers at once"
      (let [p (promise)]
        (step #'root/api-proc st :ext {:op :eval :id :nope :forms "1" :p p})
        (is (re-find #"no live agent" (:printed (first @p))))))
    (testing "a writer request carries its rid and is answered by it"
      (let [p (promise)
            [st outs] (step #'root/api-proc st :ext {:op :write :req {:op :tx :branch :db :tx-data []} :p p})
            rid (get-in (first (:to-writer outs)) [:dest :rid])
            [_ _] (step #'root/api-proc st :from-writer {:dest {:rid rid} :r {:ok true}})]
        (is (= {:ok true} @p))))))

(deftest the-step-loop-runs-flows-contract-in-one-thread
  ;; a counter step-fn run by seon.step/run-loop over an in-memory transport
  (let [step-fn (fn ([] {:workload :io :ping-map-fn (fn [s] {:n (:n s)})})
                  ([_] {:n 0 :log []})
                  ([s tr] (update s :log conj tr))
                  ([s _ m] [(update s :n inc) {:out [[:got m]]}]))
        inbox (atom [[:in 1] [:in {:op :pause}] [:in 2] [:in {:op :ping}] [:in {:op :resume}] [:in 3] [:in {:op :stop}] [:in 4]])
        sent (atom [])
        final (step/run-loop step-fn nil
                             (fn [] (let [[x & more] @inbox] (reset! inbox more) x))
                             (fn [port m] (swap! sent conj [port m])))]
    (is (= [1 2 3] (keep (fn [[p m]] (when (= p :out) (second m))) @sent)) "held while paused, then in order")
    (is (= {:op :pong :status :paused :count 1 :held 1 :state {:n 1}} (second (first (filter (fn [[p m]] (= :pong (:op m))) @sent)))))
    (is (= [::flow/pause ::flow/resume ::flow/stop] (:log final)))
    (is (= [[:in 4]] @inbox) "nothing is taken after stop")))

(deftest the-fork-server-step
  (let [[_ conn] (pdb/create! 100)
        db @conn
        st (#'server/server-step {:db db :conn nil})]
    (testing "a main commit is applied to its replica"
      (let [rep (d/with db [{:note/text "z"}])
            ops (pdb/report->tx rep)
            [st outs] (step #'server/server-step st :cmd {:op :tx :t (:max-tx (:db-after rep)) :ns 0 :tx-data ops})]
        (is (= (:max-tx (:db-after rep)) (:max-tx (:db st))))
        (is (= [:applied] (map :ev (:ev outs))))
        (testing "and a commit it already holds is skipped"
          (is (= [st nil] (#'server/server-step st :cmd {:op :tx :t (:max-tx (:db st)) :tx-data ops}))))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'seon.procs-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
