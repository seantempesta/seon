(ns seon.run-test
  "The run fold (`seon.batch/evaluate-batch`) behaviors of docs/seon/specs/run.md
  section 2 that no other test covers, asserted on the batch's data. Each
  test drives the public entry with stage values passed as data (one symbol
  per form, each form's value is itself); the ids are the spec's item numbers."
  (:require [clojure.test :refer [deftest is]]
            [seon.batch :as batch]
            [seon.db :as db]
            [seon.id :as id]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]
            [seon.turn]
            [seon.turn.batch :as turn.batch]
            [seon.turn.world :as world]))

(defn- read-symbols
  "A read stage over `text` whose forms are single characters read as symbols,
  a form every two characters (\"a b\" reads a then b)."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/read-state] :seon.batch/read-step]}
  [_ {v :seon.batch/version}]
  (let [text (:seon.batch/text v) p (:seon.batch/pos v)]
    (if (< p (count text))
      {:seon.batch/form (symbol (subs text p (inc p))) :seon.batch/start p :seon.batch/end (inc p)
       :seon.batch/version (assoc v :seon.batch/pos (min (count text) (+ p 2))) :seon.batch/repairs []}
      {:seon.batch/eof true :seon.batch/version v :seon.batch/repairs []})))

(defn- run-with
  "The fold over \"a b\" from a database `basis`, the stages in `stages`
  added to a minimal request of pure stage values."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] :seon.batch/batch]}
  [basis stages]
  (batch/evaluate-batch
   (merge {:seon.batch/text "a b"
           :seon.batch/basis basis
           :seon.batch/world {:seon.boot/cluster-name "default" :seon.batch/sink (batch/sink)}
           :seon.batch/read-state (fn [text] {:seon.batch/version {:seon.batch/text text :seon.batch/pos 0}
                                              :seon.batch/repairs []})
           :seon.batch/read-next read-symbols
           :seon.batch/evaluate (fn [_ form _] {:seon.batch/value form})
           :seon.batch/ns-name (fn [_] 'user)}
          stages)))

(defn- run
  "`run-with` on the test database's current value."
  {:malli/schema [:=> [:cat :map] :seon.batch/batch]}
  [stages]
  (support/with-database (fn [connection] (run-with (db/db connection) stages))))

(defn- stages-of
  "The stages of `diagnostics`, as a set."
  {:malli/schema [:=> [:cat [:vector :map]] [:set :keyword]]}
  [diagnostics]
  (into #{} (map :seon.batch/stage) diagnostics))

(defn- values [b] (mapv :seon.batch/value (:seon.batch/entries b)))

(deftest item-1-a-stage-throw-is-a-diagnostic-beside-each-value
  ;; Each optional stage that runs per form or after the loop throws once; the
  ;; published values survive and the diagnostic names the stage.
  (doseq [[stage stages]
          {:show {:seon.batch/show (fn [_ _] (throw (ex-info "show boom" {})))}
           :publish {:seon.batch/publish (fn [_ _ _] (throw (ex-info "publish boom" {})))}
           :settle {:seon.batch/settle (fn [_ _] (throw (ex-info "settle boom" {})))}
           :ns-name {:seon.batch/ns-name (fn [_] (throw (ex-info "ns boom" {})))}}
          :let [b (run stages)]]
    (is (= '[a b] (values b)) (str stage ": both values are kept"))
    (is (contains? (stages-of (:seon.batch/diagnostics b)) (if (= stage :ns-name) :ns-name stage))
        (str stage ": the diagnostic names the stage"))))

(deftest item-8-a-throwing-read-stage-ends-reading-with-read-stage-failed
  (let [b (run {:seon.batch/read-next (fn [_ _] (throw (ex-info "read boom" {})))})]
    (is (= [] (:seon.batch/entries b)))
    (is (contains? (stages-of (:seon.batch/diagnostics b)) :read))
    (is (= :read-stage-failed (get-in b [:seon.batch/unread :seon.batch/reason])))))

(deftest item-8-a-span-outside-its-text-is-a-diagnostic-and-the-value-is-kept
  (let [b (run {:seon.batch/read-next
                (fn [_ {v :seon.batch/version}]
                  (if (zero? (:seon.batch/pos v))
                    {:seon.batch/form 'a :seon.batch/start 0 :seon.batch/end 99
                     :seon.batch/version (assoc v :seon.batch/pos 3) :seon.batch/repairs []}
                    {:seon.batch/eof true :seon.batch/version v :seon.batch/repairs []}))})]
    (is (= '[a] (values b)) "the form is still evaluated")
    (is (= [:read] (mapv :seon.batch/stage (:seon.batch/diagnostics b))))
    (is (= 0 (:seon.batch/ordinal (first (:seon.batch/diagnostics b)))))))

(deftest item-16-the-reply-is-one-key-set
  (let [b (run {:seon.batch/show (fn [_ {v :seon.batch/value}] (str v))
                :seon.batch/evaluate (fn [_ form _] (if (= 'a form)
                                                      {:seon.batch/value form}
                                                      {:seon.batch/error (ex-info "b failed" {})}))})
        reply (turn.batch/reply b)]
    (is (= #{:seon.batch/evaluations :seon.batch/diagnostics :seon.batch/repairs} (set (keys reply))))
    (is (= [#{:seon.batch/ordinal :seon.batch/shown :seon.batch/duration-ms}
            #{:seon.batch/ordinal :seon.batch/shown :seon.batch/duration-ms :seon.batch/error?}]
           (mapv (comp set keys) (:seon.batch/evaluations reply))))))

(deftest item-18-work-in-flight-makes-evidence-pending-and-the-value-is-published
  (let [b (run {:seon.batch/evaluate
                (fn [request form _]
                  (when (= 'a form)
                    (.incrementAndGet ^java.util.concurrent.atomic.AtomicLong
                                      (:seon.batch/in-flight (:seon.batch/world request))))
                  {:seon.batch/value form})})
        [a b'] (:seon.batch/entries b)]
    (is (= [:pending 1 'a] [(:seon.batch/evidence a) (:seon.batch/in-flight a) (:seon.batch/value a)]))
    (is (= [:complete 'b] [(:seon.batch/evidence b') (:seon.batch/value b')]))))

(deftest item-19-a-write-after-the-observation-closed-is-refused-to-its-writer
  (let [late (volatile! nil)
        b (run {:seon.batch/observe (fn [_ _ on-write thunk] (vswap! late #(or % on-write)) (thunk))})
        refused (try (@late {:seon.batch/op :unmap :seon.batch/sym 'x/y}) nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= '[a b] (values b)))
    (is (= {:seon.batch/op :unmap :seon.batch/sym 'x/y} (:seon.batch/write refused)))
    (is (= 0 (:seon.batch/ordinal refused)) "the first form's observation, the one captured, is closed")))

(deftest item-20-malformed-observed-writes-are-dropped-with-one-diagnostic
  (let [good {:seon.batch/op :unmap :seon.batch/sym 'x/y}
        b (run {:seon.batch/text "a"
                :seon.batch/observe (fn [_ _ on-write thunk] (on-write 42) (on-write good) (on-write nil) (thunk))})
        [diagnostic :as diagnostics] (:seon.batch/diagnostics b)]
    (is (= 1 (count diagnostics)))
    (is (= :observe (:seon.batch/stage diagnostic)))
    (is (= [42 nil] (:seon.error/offending diagnostic)) "the dropped values themselves, never printed copies")
    (is (= [(assoc good :seon.batch/ordinal 0)] (:seon.batch/writes b)) "only the good write is recorded")
    (is (= '[a] (values b)))))

(deftest item-21-a-function-called-more-than-the-threshold-is-named-once-repeated
  (let [call! (fn [n] (fn [request form _]
                        (.put ^java.util.concurrent.ConcurrentHashMap (:seon.batch/form-sink (:seon.batch/world request))
                              ['x/f nil] (java.util.concurrent.atomic.AtomicLongArray. (long-array [n 0 0 0 0])))
                        {:seon.batch/value form}))
        at-threshold (:seon.batch/call-profile (first (:seon.batch/entries (run {:seon.batch/evaluate (call! batch/repeated-calls)}))))
        over (:seon.batch/call-profile (first (:seon.batch/entries (run {:seon.batch/evaluate (call! (inc batch/repeated-calls))}))))]
    (is (= {:seon.batch/call-count batch/repeated-calls} at-threshold) "exactly the threshold is not repeated")
    (is (= [['x/f (inc batch/repeated-calls) 0]] (:seon.batch/repeated over)))))

(deftest item-21-each-form-sink-joins-the-batch-sink
  (let [sink (batch/sink)
        _ (run {:seon.batch/world {:seon.boot/cluster-name "default" :seon.batch/sink sink}
                :seon.batch/evaluate (fn [request form _]
                                       (.put ^java.util.concurrent.ConcurrentHashMap (:seon.batch/form-sink (:seon.batch/world request))
                                             ['x/f nil] (java.util.concurrent.atomic.AtomicLongArray. (long-array [3 0 0 0 0])))
                                       {:seon.batch/value form})})]
    (is (= 6 (get (batch/calls sink) 'x/f)) "two forms of three calls")))

(deftest item-22-an-around-failure-keeps-the-last-published-batch
  (let [b (run {:seon.batch/around (fn [_ body] (body) (throw (ex-info "around boom" {})))})]
    (is (= '[a b] (values b)))
    (is (contains? (stages-of (:seon.batch/diagnostics b)) :around))))

(deftest item-24-an-untrusted-execution-keeps-its-value-and-proposes-no-facts
  (let [derived (volatile! 0)
        b (run {:seon.batch/evaluate (fn [_ form _] {:seon.batch/value form
                                                      :seon.batch/facts-refused {:seon.error/message "the loaded program changed"}})
                :seon.batch/derive (fn [_ state _ _ _]
                                     (vswap! derived inc)
                                     {:seon.batch/change {:seon.batch/entities [{:seon.fn/sym 'x/f}]} :seon.batch/derive-state state})})
        [entry] (:seon.batch/entries b)]
    (is (= '[a b] (values b)))
    (is (zero? @derived) "derive never runs for it")
    (is (= "the loaded program changed" (get-in entry [:seon.batch/facts-error :seon.error/message])))
    (is (not (contains? entry :seon.batch/change)))
    (is (= {} (:seon.batch/program b)))))

(deftest d8-a-test-obtains-a-forms-declaration-row-from-the-entrys-change
  ;; RED today (D8): `support/code-request` sets derive but not observe, so the
  ;; write queue is empty and derive proposes no entity. Target: the fold
  ;; observes whenever it derives.
  (support/with-database
    (fn [connection]
      (let [basis (db/db connection)
            request (support/code-request
                     {:seon.batch/world (world/agent-world {:seon.db/connection connection :seon.cluster/name "default"
                                                            :seon.sci.eval/base-ctx (sci.eval/base-ctx basis)}
                                                           {:seon.agent/id "d8-row"})
                      :seon.batch/start-ns 'user
                      :seon.batch/text "(defn d8-f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"})
            entry (first (:seon.batch/entries (batch/evaluate-batch (dissoc request :seon.batch/settle))))]
        (is (= 'user/d8-f (get-in entry [:seon.batch/change :seon.batch/entities 0 :seon.fn/sym])))))))

(deftest a-settlement-inside-another-custody-records-on-its-own-branch
  ;; A JVM run started from inside another branch's custody (an agent's JVM form
  ;; calling `seon.mcp/call` for a second agent) settles on its own world's
  ;; branch; the writer refused it as a foreign-branch write before.
  (let [handle (support/execution-handle nil)
        connection (:seon.db/connection handle)
        agent-id (str "settle-custody-" (id/id))
        _ (support/transacted! connection (support/agent-tx @connection agent-id))
        world (world/jvm-world (:seon.cluster/name handle) connection {:seon.agent/id agent-id :seon.turn/id (id/id)})
        batch (support/with-database
               (fn [_] (batch/evaluate-batch
                        (turn.batch/jvm-request {:seon.batch/world world :seon.batch/text "(+ 1 2)"
                                                 :seon.turn.batch/record-call #'seon.turn/record-evaluated-call}))))]
    (is (true? (:seon.batch/applied (:seon.batch/commit batch))))))

(defn- read-spaced
  "A read stage whose forms are the text's non-whitespace characters, read as
  symbols, whatever whitespace separates them."
  {:malli/schema [:=> [:cat :seon.batch/request :seon.batch/read-state] :seon.batch/read-step]}
  [_ {v :seon.batch/version}]
  (let [^String text (:seon.batch/text v)
        p (loop [p (:seon.batch/pos v)]
            (if (and (< p (count text)) (Character/isWhitespace (.charAt text (int p)))) (recur (inc p)) p))]
    (if (< p (count text))
      {:seon.batch/form (symbol (subs text p (inc p))) :seon.batch/start p :seon.batch/end (inc p)
       :seon.batch/version (assoc v :seon.batch/pos (inc p)) :seon.batch/repairs []}
      {:seon.batch/eof true :seon.batch/version (assoc v :seon.batch/pos p) :seon.batch/repairs []})))

(defn- platform-allocation
  "The bytes `f` allocates, run on a joined platform thread with this thread's
  bindings: HotSpot counts no allocation for a virtual thread, where test
  bodies run (`seon.batch/allocation-is-unmeasured-on-a-virtual-thread`)."
  {:malli/schema [:=> [:cat [:=> [:cat] :any]] :int]}
  [f]
  (let [threads ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
        allocated (volatile! -1)
        thread (Thread. ^Runnable (bound-fn [] (let [before (.getCurrentThreadAllocatedBytes threads)]
                                                 (f)
                                                 (vreset! allocated (- (.getCurrentThreadAllocatedBytes threads) before)))))]
    ;; the join is the exit: the volatile is read after it
    (.start thread)
    (.join thread)
    @allocated))

(deftest a-forms-position-costs-the-text-once
  ;; Each form's line and column are scanned on from the previous form's, so
  ;; a longer text of the same forms costs its added length once, not once
  ;; per form.
  (support/with-database
   (fn [connection]
     (let [forms 40
           text (fn [gap] (apply str (repeat forms (str "a" (apply str (repeat gap \newline))))))
           fold #(run-with (db/db connection) {:seon.batch/text (text %) :seon.batch/read-next read-spaced})
           allocation (fn [gap] (fold gap) (platform-allocation #(fold gap)))
           short-text (allocation 250)
           long-text (allocation 2000)]
       (is (= (mapv (fn [k] [(inc (* k 2000)) 1]) (range forms))
              (mapv (juxt :seon.batch/line :seon.batch/column) (:seon.batch/entries (fold 2000)))))
       (is (pos? short-text) "the platform thread's allocation counter measures the fold")
       (is (< (- long-text short-text) (* 4 1024 1024))
           "70,000 more characters of text cost the fold their length, not 40 times it")))))
