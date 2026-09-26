(ns seon.error.refusal-test
  "Cause-chain readers preserve producer errors and ordinary exception data.
  Their error-handling contracts use the base; each producer owns its named output."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.instrument :as mi]
            [seon.config :as config]
            [my.program :as program]
            [seon.context :as context]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error :as error]
            [seon.error.refusal :as error.refusal]
            [seon.instrument :as instrument]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private entries [#'error.refusal/refusal #'error/refusal])

(def ^:private base
  {:seon.error/at #inst "2026-09-18T00:00:00Z"
   :seon.error/layer :seon.error.refusal-test/probe
   :seon.error/operation 'seon.error.refusal-test/probe})

(def ^:private domain
  (assoc base :seon.agent/error-agent-id "refusal-agent"))

(deftest refusal-readers-declare-the-error-handling-base
  (doseq [entry entries]
    (is (= [:or :nil :map :seon.error/base]
           (last (:malli/schema (meta entry)))))))

(deftest armed-refusal-returns-every-declared-family-without-refusing
  (test-support/preserving-instrumentation-state
   (fn []
     (test-support/with-database
      (fn [connection]
        (let [projection (schema/projection-from-database (db/db connection))
              caps (config/result-caps (test-support/effective-config))
              buried (fn [data]
                       (RuntimeException. "wrapper" (ex-info "buried" data)))]
          (doseq [entry entries]
            (let [authored (:malli/schema (meta entry))
                  policy {}]
              (#'instrument/arm-var! entry authored projection
                                     (#'instrument/compiled-wrapper
                                      projection (symbol entry) authored
                                      (mi/-f->original @entry) caps policy)
                                     policy)))
          (doseq [entry entries
                  [label data] [[:base base]
                                [:declared-schema domain]
                                [:unclassified {:rule :ordinary-ex-data}]]]
            (testing (str entry " " label)
              (let [returned (@entry (buried data))]
                (is (= (cond-> data
                         (:seon.error/at data) (assoc :seon.error/message "buried")) returned)
                    "The armed wrapper admits the value the cause chain carried."))))
          (testing "each classified family validates against a declared alternative"
            (doseq [[value declared-schema] [[base nil]
                                   [domain :seon.agent/error]]]
              (is ((schema/projection-validator projection :seon.error/base) value)
                  (pr-str value))
              (when declared-schema
                (is ((seon.schema/projection-validator projection declared-schema) value)
                    (pr-str value)))))
          (doseq [entry entries]
            (testing (str entry " carries no data anywhere in the chain")
              (is (nil? (@entry (RuntimeException. "no data"))))
              (is (nil? (@entry nil)))))))))))

(def ^:private constructors
  [#'error.refusal/diagnostic
   (fn [{:seon.error/keys [at layer operation] :as value}]
     (error.refusal/diagnostic at layer operation
       (dissoc value :seon.error/at :seon.error/layer :seon.error/operation)))])

(deftest constructor-preserves-open-domain-maps
  (doseq [construct constructors
          value [base {:seon.error/at (java.util.Date. 0)
                    :seon.error/layer :x/y
                    :seon.error/operation 'a/b
                    :x/member 1}
                   (sorted-map :seon.error/at (java.util.Date. 0)
                               :seon.error/layer :x/y
                               :seon.error/operation 'a/b
                               :x/member 1)]]
       (let [returned (construct value)]
         (is (= value returned))
         (is (identical? (:seon.error/at value) (:seon.error/at returned))))))

(deftest constructor-consumes-only-the-throwable-input
  (doseq [construct constructors
          :let [failure (doto (Exception. "specific cause")
                     (.setStackTrace
                      (into-array StackTraceElement
                                  [(StackTraceElement. "example.Failure" "run" "failure.clj" 42)])))
           value {:seon.error/at (java.util.Date. 0)
                  :seon.error/layer :x/y
                  :seon.error/operation 'a/b
                  :seon.error/cause [:seon.error/id "recorded-cause"]
                  :x/member {:x/detail "retained"}}
           returned (construct (assoc value :seon.error/throwable failure))]]
       (is (= (assoc value :seon.error/frame '[example.Failure run "failure.clj" 42]
                          :seon.error/exception-class 'java.lang.Exception
                          :seon.error/message "specific cause"
                          :seon.error/chain [{:seon.error/throwable-class "java.lang.Exception"
                                              :seon.error/message "specific cause"}])
              returned)
           "A host frame is the frame, but it is not a first-party frame of the link.")
       (.setStackTrace failure (make-array StackTraceElement 0))
       (is (= (assoc value :seon.error/exception-class 'java.lang.Exception
                     :seon.error/message "specific cause"
                     :seon.error/chain [{:seon.error/throwable-class "java.lang.Exception"
                                         :seon.error/message "specific cause"}])
              (construct (assoc value :seon.error/throwable failure))))
       (is (= "stated" (:seon.error/message
                        (construct
                         (assoc value :seon.error/message "stated"
                                :seon.error/throwable failure))))
           "A stated message is preserved; only an absent one derives from the root.")))

(defn- framed
  "A throwable whose stack is exactly `frames`."
  [^Throwable throwable frames]
  (doto throwable
    (.setStackTrace (into-array StackTraceElement
                                (map (fn [[class-name method file line]]
                                       (StackTraceElement. class-name method file (int line)))
                                     frames)))))

(def ^:private wrapped
  ;; The census F0 shape: a wrapper ex-info around the ex-info that broke.
  (delay
   (let [root (framed (ex-info "Keyword cannot be cast to Number" {:seon.probe/leaf 1})
                      [["clojure.lang.Numbers" "ops" "Numbers.java" 1095]
                       ["seon.probe$inner" "invokeStatic" "probe.clj" 4]
                       ["seon.error$prepare" "invokeStatic" "error.clj" 9]])]
     (framed (ex-info "wrapper" {:seon.probe/outer 2} root)
             [["seon.probe$outer" "invokeStatic" "probe.clj" 5]
              ["my.work$run" "invoke" "work.clj" 7]]))))

(def ^:private observation
  {:seon.error/at (java.util.Date. 0)
   :seon.error/layer :x/y
   :seon.error/operation 'a/b})

(deftest a-wrapped-cause-is-recorded-whole
  (doseq [construct constructors
          :let [returned (construct (assoc observation :seon.error/throwable @wrapped))]]
    (is (= (assoc observation
                  :seon.error/frame '[clojure.lang.Numbers ops "Numbers.java" 1095]
                  :seon.error/exception-class 'clojure.lang.ExceptionInfo
                  :seon.error/message "Keyword cannot be cast to Number"
                  :seon.error/chain [{:seon.error/throwable-class "clojure.lang.ExceptionInfo"
             :seon.error/message "wrapper"
             :seon.error/data {:seon.probe/outer 2}
             :seon.error/frames '[[seon.probe$outer invokeStatic "probe.clj" 5]
                                  [my.work$run invoke "work.clj" 7]]}
            {:seon.error/throwable-class "clojure.lang.ExceptionInfo"
             :seon.error/message "Keyword cannot be cast to Number"
             :seon.error/data {:seon.probe/leaf 1}
             :seon.error/frames '[[seon.probe$inner invokeStatic "probe.clj" 4]]}])
           returned)
        "Every link keeps class, message, ex-data and only its first-party frames.")
    (is (= "Keyword cannot be cast to Number" (:seon.error/message returned))
        "The root message is what the reader is told.")
    (is (= 'clojure.lang.ExceptionInfo (:seon.error/exception-class returned)))
    (is ((schema/projection-validator (schema/handed-projection) :seon.error/base) returned)
        "The chain validates as the declared base member.")))

(deftest the-frame-comes-from-the-root-cause
  (is (= '[clojure.lang.Numbers ops "Numbers.java" 1095]
         (:seon.error/frame (error.refusal/diagnostic
                             (assoc observation :seon.error/throwable @wrapped))))
      "The wrapper's frame is the catch site; the root's is where it broke.")
  (is (= '[clojure.lang.Numbers ops "Numbers.java" 1095]
         (error.refusal/root-frame @wrapped))))

(deftest ^{:seon/tasks #{"4c559398c4be"}} a-cleanup-failure-survives-beside-the-primary
  (let [primary (ex-info "primary" {:seon.probe/step :work} (ex-info "root" {:seon.probe/step :leaf}))
        cleanup (ex-info "cleanup" {:seon.probe/step :release} (IllegalStateException. "closed"))
        _ (.addSuppressed ^Throwable primary cleanup)
        links (error.refusal/chain primary)]
    (is (= [{:seon.error/throwable-class "clojure.lang.ExceptionInfo" :seon.error/message "primary"
             :seon.error/data {:seon.probe/step :work}}
            {:seon.error/throwable-class "clojure.lang.ExceptionInfo" :seon.error/message "root"
             :seon.error/data {:seon.probe/step :leaf}}
            {:seon.error/throwable-class "clojure.lang.ExceptionInfo" :seon.error/message "cleanup"
             :seon.error/data {:seon.probe/step :release} :seon.error/suppressed-by 0}
            {:seon.error/throwable-class "java.lang.IllegalStateException" :seon.error/message "closed"
             :seon.error/cause-of 2}]
           (mapv #(dissoc % :seon.error/frames) links))
        "the primary chain first; the suppressed cleanup and its cause follow as flat links naming their relation")
    (let [diagnosed (error.refusal/diagnostic (assoc observation :seon.error/throwable primary))]
      (is (= "root" (:seon.error/message diagnosed)) "the root message is the primary chain's root")
      (is ((schema/projection-validator (schema/handed-projection) :seon.error/base) diagnosed)))
    (is (= [[0 "primary" nil] [1 "cleanup" 0] [2 "closed" nil]]
           (map-indexed (fn [i link] [i (:seon.error/message link)
                                      (:seon.error/suppressed-by link)])
                        (error.refusal/without-links links #(= "root" (:seon.error/message %)))))
        "dropping a link renumbers every relation to the kept positions"))
  (testing "a cause cycle ends at its first repeat"
    (let [a (Exception. "a") b (Exception. "b" a)]
      (.initCause a b)
      (is (= ["b" "a"] (mapv :seon.error/message (error.refusal/chain b))))
      (is (vector? (error.refusal/root-frame b))))))

(defn- printed-bytes
  "Printed size of `value`, or nil past `bound`: a copied world prints
  megabytes (a projection past 2 GB), so printing stops at the bound."
  [value bound]
  (let [n (volatile! 0)
        over (ex-info "over the bound" {::over true})
        count! (fn [k] (when (> (vswap! n + k) bound) (throw over)))
        writer (proxy [java.io.Writer] []
                 (write ([x] (count! (if (string? x) (count x) 1)))
                        ([_ _ length] (count! length)))
                 (flush []) (close []))]
    (try (binding [*print-length* nil *print-level* nil] (pr value writer)) @n
         (catch clojure.lang.ExceptionInfo e (when-not (identical? e over) (throw e))))))

(deftest ^{:seon/tasks #{"480378b94c9b"}} every-producer-references-the-worlds-it-was-handed
  (test-support/with-database
   (fn [connection]
     (let [database (db/db connection)
           projection (schema/handed-projection)
           environment (env/environment {:seon.boot/cluster-name "refs"})
           world? (some-fn db/database-value? db/connection-object? schema/projection-value?
                           env/environment?)
           worlds {:seon.db/db database :seon.db/connection connection
                   :seon.schema/projection projection :seon.probe/environment environment}
           carrier (assoc worlds :seon.probe/member 1)
           caps (config/result-caps (test-support/effective-config))
           contracted (instrument/wrap-interpreted
                       'seon.probe/f "[:=> [:cat :map :int] :int]" projection caps (fn [_ n] n))
           failure (doto (ex-info "outer" {:seon.probe/request carrier}
                                  (ex-info "inner" {:seon.db/db database}))
                     (.addSuppressed (ex-info "cleanup" {:seon.db/connection connection})))
           produced
           {:diagnostic (error.refusal/diagnostic
                         (assoc observation :seon.error/offending carrier
                                :seon.error/throwable failure))
            :contract-violation (test-support/refusal-data #(contracted carrier "not an int"))
            :require-environment (env/require-environment carrier :seon.probe/boundary)
            :scope (test-support/refusal-data #(env/scope carrier {}))
            :program-read (#'program/read-result
                           {:seon.db/db database :seon.program/subject 'sample/missing}
                           'my.program/callers
                           #(throw (ex-info "The read failed." carrier)))
            :context-capture (test-support/refusal-data
                              #(context/capture-tx {:seon.turn/id "refs" :seon.db/db database}))}]
       (is (nil? (printed-bytes worlds 16384)) "a copied world is over the bound")
       (doseq [[producer error] produced]
         (testing (name producer)
           (is (map? error))
           (is (not-any? world? (tree-seq coll? seq error))
               "no world anywhere in the error: each is its identity")
           (is (printed-bytes error 16384) "the error prints in kilobytes")))
       (is (= {:db-name (:db-name (db/database-value-identity database))
               :t (db/basis-t database)}
              (select-keys (get-in produced [:diagnostic :seon.error/chain 1 :seon.error/data :seon.db/db])
                           [:db-name :t]))
           "a cause link's database is its name and basis")))))

(deftest ^{:seon/tasks #{"480378b94c9b"}} a-place-in-an-error-is-named-never-printed-through
  ;; Default OOMed (2026-09-26, heap dump java_pid12212): an instrument
  ;; refusal's offending web request held the agent roster atom, whose state
  ;; is every context's projection; http-kit printed the ex-data to 2 GB.
  (let [state (vec (range 100000))
        roster (atom {:seon.agent/contexts {"a" state}})
        realized (doto (delay state) deref)
        carrier {:seon.agent/routing roster :seon.probe/memo realized
                 :seon.probe/var #'error.refusal/diagnostic}
        error (error.refusal/diagnostic
               (assoc base :seon.error/offending carrier
                      :seon.error/throwable (ex-info "refused" {:seon.probe/request carrier})))]
    (is (nil? (printed-bytes carrier 16384)) "the place's state is over the bound")
    (is (printed-bytes error 16384) "the error prints in kilobytes")
    (is (= #'error.refusal/diagnostic (get-in error [:seon.error/offending :seon.probe/var]))
        "a Var is its name already and stays")
    (is (every? symbol? (map (:seon.error/offending error) [:seon.agent/routing :seon.probe/memo]))
        "each place is its #object name")))

(deftest constructor-output-keeps-the-producing-contract
  (let [projection (-> (schema/handed-projection)
                          (schema/projection-with-schema :x/member :int
                           {:seon.schema.admission/source :core})
                          (schema/projection-with-schema
                           :x/y-error [:and :seon.error/base [:map [:x/member [:= 1]]]]
                           {:seon.schema.admission/source :core})
                          (schema/projection-with-schema
                           :x/z-error [:and :seon.error/base [:map [:x/member [:= 2]]]]
                           {:seon.schema.admission/source :core}))
           caps (config/result-caps (test-support/effective-config))
           value {:seon.error/at (java.util.Date. 0) :seon.error/layer :x/y
                  :seon.error/operation 'a/b :x/member 1}
           diagnostic (instrument/wrap-interpreted
                       'seon.error.refusal/diagnostic
                       (pr-str (:malli/schema (meta #'error.refusal/diagnostic)))
                       projection caps error.refusal/diagnostic)
           accepted (instrument/wrap-interpreted
                     'x/accepted "[:function [:=> [:cat :map] :x/y-error] [:=> [:cat :inst :qualified-keyword :qualified-symbol :map] :x/y-error]]"
                     projection caps diagnostic)
           refused (instrument/wrap-interpreted
                    'x/refused "[:function [:=> [:cat :map] :x/z-error] [:=> [:cat :inst :qualified-keyword :qualified-symbol :map] :x/z-error]]"
                    projection caps diagnostic)]
       (doseq [args [[value] [(:seon.error/at value) :x/y 'a/b {:x/member 1}]]]
        (is (= value (apply accepted args)))
        (let [result (test-support/refusal-data #(apply refused args))]
         (is ((schema/projection-validator projection :seon.instrument/contract-error) result))
         (is (= 'x/refused (:seon.error/operation result)))))))

(deftest positional-construction-evaluates-each-input-once-in-order
  (let [seen (atom []) at (java.util.Date. 0)
        input (fn [k value] (swap! seen conj k) value)
        value (error.refusal/diagnostic (input :at at) (input :layer :x/y)
                (input :operation 'a/b) (input :members {:x/member 1}))
        cause (ex-info "cause" {}) error (ex-info "outer" value cause)]
    (is (= [:at :layer :operation :members] @seen))
    (is (= {:seon.error/at at :seon.error/layer :x/y
            :seon.error/operation 'a/b :x/member 1} (ex-data error)))
    (is (identical? at (:seon.error/at value)))
    (is (identical? cause (ex-cause error)))))
