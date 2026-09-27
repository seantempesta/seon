(ns seon.error.refusal-test
  "Cause-chain readers preserve producer errors and ordinary exception data.
  Their error-handling contracts use the base; each producer owns its named output."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
       (is (= (assoc value :seon.error/exception-class 'java.lang.Exception
                          :seon.error/message "specific cause"
                          :seon.error/chain [{:seon.error/throwable-class "java.lang.Exception"
                                              :seon.error/message "specific cause"}])
              returned)
           "A host class's frame is nothing an agent can edit: no frame, and none on the link.")
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
  ;; Frames name loaded namespaces, since first party is read from where each
  ;; namespace's source is: this test's own and `my.program`'s are authored,
  ;; `clojure.core`'s is in the Clojure jar, `seon.error` is fault machinery.
  (delay
   (let [root (framed (ex-info "Keyword cannot be cast to Number" {:seon.probe/leaf 1})
                      [["clojure.lang.Numbers" "ops" "Numbers.java" 1095]
                       ["clojure.core$inc" "invokeStatic" "core.clj" 929]
                       ["seon.error.refusal_test$inner" "invokeStatic" "refusal_test.clj" 4]
                       ["seon.error$prepare" "invokeStatic" "error.clj" 9]])]
     (framed (ex-info "wrapper" {:seon.probe/outer 2} root)
             [["seon.error.refusal_test$outer" "invokeStatic" "refusal_test.clj" 5]
              ["my.program$run" "invoke" "program.clj" 7]]))))

(def ^:private observation
  {:seon.error/at (java.util.Date. 0)
   :seon.error/layer :x/y
   :seon.error/operation 'a/b})

(deftest a-wrapped-cause-is-recorded-whole
  (doseq [construct constructors
          :let [returned (construct (assoc observation :seon.error/throwable @wrapped))]]
    (is (= (assoc observation
                  :seon.error/frame '[seon.error.refusal_test$inner invokeStatic "refusal_test.clj" 4]
                  :seon.error/exception-class 'clojure.lang.ExceptionInfo
                  :seon.error/message "Keyword cannot be cast to Number"
                  :seon.error/chain [{:seon.error/throwable-class "clojure.lang.ExceptionInfo"
             :seon.error/message "wrapper"
             :seon.error/data {:seon.probe/outer 2}
             :seon.error/frames '[[seon.error.refusal_test$outer invokeStatic "refusal_test.clj" 5]
                                  [my.program$run invoke "program.clj" 7]]}
            {:seon.error/throwable-class "clojure.lang.ExceptionInfo"
             :seon.error/message "Keyword cannot be cast to Number"
             :seon.error/data {:seon.probe/leaf 1}
             :seon.error/frames '[[seon.error.refusal_test$inner invokeStatic "refusal_test.clj" 4]]}])
           returned)
        "Every link keeps class, message, ex-data and only its first-party frames.")
    (is (= "Keyword cannot be cast to Number" (:seon.error/message returned))
        "The root message is what the reader is told.")
    (is (= 'clojure.lang.ExceptionInfo (:seon.error/exception-class returned)))
    (is ((schema/projection-validator (schema/handed-projection) :seon.error/base) returned)
        "The chain validates as the declared base member.")))

(deftest the-frame-comes-from-the-root-cause
  (is (= '[seon.error.refusal_test$inner invokeStatic "refusal_test.clj" 4]
         (:seon.error/frame (error.refusal/diagnostic
                             (assoc observation :seon.error/throwable @wrapped))))
      "The wrapper's frame is the catch site; the root's first-party frame is where it broke.")
  (is (= '[seon.error.refusal_test$inner invokeStatic "refusal_test.clj" 4]
         (error.refusal/root-frame @wrapped))))

(defn probe-var
  "A Var for `frame-function` to name."
  {:malli/schema [:=> [:cat] :nil]}
  [])

(deftest the-frame-is-the-first-one-the-caller-wrote
  ;; printer-fixes-opus 2026-09-27: `(clojure.string/replace nil ...)` in an
  ;; eval_clj form reported `[clojure.string$replace ...]`, and a form evaluated
  ;; in `user` was never first party. A dependency's frame is where it broke,
  ;; not anything to edit; the caller's form is.
  (testing "a form evaluated in a namespace with no source file"
    (let [failure (try (binding [*ns* (the-ns 'user)]
                         (eval '(clojure.string/replace nil "a" "b")))
                       (catch Throwable t t))
          frame (error.refusal/root-frame failure)]
      (is (instance? NullPointerException failure))
      (is (= "clojure.string$replace" (.getClassName ^StackTraceElement (first (.getStackTrace ^Throwable failure))))
          "the innermost frame is the dependency's")
      (is (str/starts-with? (str (first frame)) "user$eval") (pr-str frame))
      (is (error.refusal/first-party-frame? frame))))
  (testing "a dependency, the host and the fault machinery are not first party"
    (doseq [frame '[[clojure.string$replace invokeStatic "string.clj" 101]
                    [malli.core$_fail_BANG_ invokeStatic "core.cljc" 138]
                    [clojure.lang.Util runtimeException "Util.java" 221]
                    [seon.error.refusal$chain invokeStatic "refusal.clj" 1]]]
      (is (not (error.refusal/first-party-frame? frame)) (pr-str frame))))
  (testing "the prepared-call closure is contract machinery, not the caller"
    (let [failure (framed (ex-info "refused" {})
                          [["seon.call_preparation$preparing$fn__6652" "doInvoke" "call_preparation.clj" 1033]
                           ["seon.error.refusal_test$caller" "invoke" "refusal_test.clj" 9]])]
      (is (= '[seon.error.refusal_test$caller invoke "refusal_test.clj" 9]
             (error.refusal/root-frame failure)))))
  (testing "a first-party frame names its function only when its Var exists"
    (is (= 'seon.error.refusal-test/probe-var
           (error.refusal/frame-function 'seon.error.refusal_test$probe_var)))
    (is (nil? (error.refusal/frame-function 'user$eval479$fn__480)))))

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
  megabytes (a projection past 2 GB), so printing stops at the bound.
  `pr` prints every argument to `*out*`, so the writer is bound there;
  `(pr value writer)` printed the world into the harness's captured output."
  [value bound]
  (let [n (volatile! 0)
        over (ex-info "over the bound" {::over true})
        count! (fn [k] (when (> (vswap! n + k) bound) (throw over)))
        writer (proxy [java.io.Writer] []
                 (write ([x] (count! (if (string? x) (count x) 1)))
                        ([_ _ length] (count! length)))
                 (flush []) (close []))]
    (try (binding [*out* writer *print-length* nil *print-level* nil] (pr value)) @n
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
    (is (every? #(str/starts-with? % "#object[")
                (map (:seon.error/offending error) [:seon.agent/routing :seon.probe/memo]))
        "each place is its #object name, as a string a reader reads back")))

(deftest a-large-argument-is-held-by-name-in-the-chain-and-live-in-the-refusal
  ;; value-hygiene 2026-09-27: an eval_clj call refused on argument 0 of
  ;; `seon.schema/projection-with-declarations` stored a 2.8 MB blob: the
  ;; refusal's offending arguments held argument 2, a projection's 3,576
  ;; forms, and the error value's chain copied it. The live refusal keeps the
  ;; caller's actual arguments; the chain, the error as data, holds each value
  ;; past the evidence bound by its class, identity and size.
  (let [projection (schema/handed-projection)
        caps (config/result-caps (test-support/effective-config))
        forms (:seon.schema.projection/forms projection)
        contracted (instrument/wrap-interpreted
                    'seon.probe/f "[:=> [:cat :qualified-symbol :map] :int]" projection caps (fn [_ _] 1))
        failure (try (contracted 'unqualified forms) (catch clojure.lang.ExceptionInfo e e))
        error (error.refusal/diagnostic (assoc base :seon.error/throwable failure))
        link-data (get-in error [:seon.error/chain 0 :seon.error/data])
        held (get-in link-data [:seon.error/offending 1])]
    (is (< 1000 (count forms)) "the subject: a large argument")
    (is (identical? forms (get-in (ex-data failure) [:seon.error/offending 1]))
        "the live refusal keeps the actual argument")
    (is (= 'unqualified (get-in link-data [:seon.error/offending 0])) "the refused argument stays")
    (is (= (str "#object[" (.getName (class forms)) " 0x"
                (Integer/toHexString (System/identityHashCode forms)) " {:count " (count forms) "}]")
           held)
        "the large one is its class, identity and size")
    (is (printed-bytes error 16384) "the error prints in kilobytes")
    (is (error.refusal/within-evidence-bound? link-data))
    (is (not (error.refusal/within-evidence-bound? (ex-data failure)))
        "the declared contract refuses the copy")
    (testing "the recorder's chain keeps the whole value for its data blob"
      (let [recorded (error.refusal/recorded-chain failure projection)]
        (is (identical? forms (get-in recorded [0 :seon.error/data :seon.error/offending 1]))
            "the recorder admits the actual argument, not its name")
        (is (error.refusal/within-evidence-bound-chain? (:seon.error/chain error)))
        (is (not (error.refusal/within-evidence-bound-chain? recorded)))
        (is (= (mapv #(dissoc % :seon.error/data) (:seon.error/chain error))
               (mapv #(dissoc % :seon.error/data) recorded))
            "only the data differs: frames and relations are the chain's")))))

(deftest the-recorders-chain-still-names-places
  ;; A place's state is never evidence, whole chain or bounded: the roster
  ;; atom printed 2 GB (a-place-in-an-error-is-named-never-printed-through).
  (let [roster (atom (vec (range 100000)))
        small {:seon.probe/n 1}
        failure (ex-info "refused" {:seon.probe/roster roster :seon.probe/small small})
        recorded (error.refusal/recorded-chain failure nil)]
    (is (str/starts-with? (get-in recorded [0 :seon.error/data :seon.probe/roster]) "#object[clojure.lang.Atom"))
    (is (identical? small (get-in recorded [0 :seon.error/data :seon.probe/small])) "small data stays identical")
    (is (printed-bytes recorded 16384))))

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
