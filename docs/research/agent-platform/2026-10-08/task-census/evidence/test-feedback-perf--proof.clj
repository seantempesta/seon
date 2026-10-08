;; Test feedback performance: the workbench. Evaluated form by form over MCP eval_clj in JVM
;; mode against live default (read-only) and disposable branches (tfp-*). The candidate
;; functions below are the code that lands in seon.test.runner and seon.fn; `proofs` answers
;; one map of named proofs, each validated against its schema and timed armed on parent
;; (installed functions) and change (these functions), on a freshly committed head.
;; (Single-segment namespace: the hook's name check refuses dotted names in this directory.)
(ns proof
  (:require [clojure.set :as set]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.cache :as cache]
            [seon.cluster.boot :as boot]
            [seon.db :as db]
            [seon.error.refusal]
            [seon.fn]
            [seon.id :as id]
            [seon.schema]
            [seon.test.runner :as runner])
  (:import (java.util BitSet)))

(def checkout
  "The working tree the change is read from; the JVM runs a published archive."
  "/Users/sean/src/seon/")

(def ^:private bitset-class BitSet)

;;; Bootstrap: paste `load-named!`, then
;;; (load-named! (str checkout "tmp/test-feedback-perf/proof.clj") (constantly true)).

(defn load-named!
  "Evaluate the top-level def forms of `file` whose name `names` accepts, in this namespace."
  [file names]
  (let [reader (java.io.PushbackReader. (java.io.StringReader. (slurp file)))]
    (binding [*ns* (the-ns 'proof)]
      (loop [loaded []]
        (let [form (read {:eof ::eof :read-cond :allow} reader)]
          (cond (= ::eof form) loaded
                ;; Only this namespace's own ns form: never another file's.
                (and (seq? form) (= 'ns (first form)) (= 'proof (second form)))
                (do (eval form) (recur loaded))
                (and (seq? form) (#{'defn 'defn- 'def} (first form)) (names (second form)))
                (do (eval form) (recur (conj loaded (second form))))
                :else (recur loaded)))))))

(def landing-runner
  '#{reach-read! reach-keywords reach-canonical reach-row reach-bits reach-node reach-schema-node reach-weight
     reach-declarations reach-fixed-point reach-graph reach-successors reach-postorder reach-closures
     reach-entry reach-entries reach-digests reach-memberships latest-results failure-replacement-tx})

(def landing-fn '#{declared-callers gate-set-in gate-sets-in call-reach})

(defn load-landing!
  "The change's functions, from the working tree's exact text, beside the
  private owners they call."
  []
  (doseq [[a n] (concat (ns-aliases 'seon.fn) (ns-aliases 'seon.test.runner))]
    (when-not ((ns-aliases 'proof) a) (alias a (ns-name n))))
  (doseq [[local owner] {'execution-read #'runner/execution-read
                         'recorded-member-results #'runner/recorded-member-results
                         'result-read-error #'runner/result-read-error
                         'read-or-throw! #'seon.fn/read-or-throw!
                         'identity-names #'seon.fn/identity-names}]
    (binding [*ns* (the-ns 'proof)] (eval (list 'def local (list 'deref owner)))))
  (into (load-named! (str checkout "src/seon/test/runner.clj") landing-runner)
        (load-named! (str checkout "src/seon/fn.clj") landing-fn)))

(defn arm!
  "Wrap each named Var's function with its declared contract compiled in the
  live projection's registry, as armed code runs (Malli's -instrument)."
  [syms]
  (let [registry (:seon.schema.projection/registry (seon.schema/projection-from-database @(boot/connection "default")))]
    (doseq [s syms]
      (let [v (ns-resolve 'proof s)
            schema (:malli/schema (meta v))]
        (alter-meta! v assoc ::raw @v)
        (eval (list 'def s (list 'quote (m/-instrument {:schema schema
                                                        :report (fn [kind data]
                                                                  (throw (ex-info (str "contract " kind " " s)
                                                                                  (select-keys data [:value]))))}
                                                       @v {:registry (mr/composite-registry registry (mr/var-registry))}))))
        (alter-meta! v assoc :malli/schema schema)))))


;;; ---------------------------------------------------------------------------
;;; Proof harness
;;; ---------------------------------------------------------------------------

(defn ms [f]
  (let [t (System/nanoTime) v (f)] [(/ (Math/round (/ (- (System/nanoTime) t) 1e4)) 100.0) v]))

(def conn (boot/connection "default"))

(defn branch-db [b] (db/branch-database @conn b))

;;; ---------------------------------------------------------------------------
;;; Proofs. The candidate functions are evaluated into this namespace from the
;;; working tree by `load-named!` (the exact landing text) and wrapped with their
;;; declared contracts by `arm!` (Malli's -instrument over the live projection's
;;; registry); the parent functions are the installed, armed Vars.
;;; ---------------------------------------------------------------------------

(def status [:enum :pass :fail :stale :never-run :unknown])

(def schemas
  {:tests [:map [:seon.feedback/tests [:map-of :qualified-symbol status]]
           [:seon.feedback/counts [:map-of status nat-int?]]]
   :selection [:vector :qualified-symbol]
   :declared [:map-of :qualified-symbol [:set :int]]})

(def parent
  {:gate #(seon.fn/gate-sets {:seon.db/db %1 :seon.fn/seeds (set %2)})
   :latest runner/latest-results
   :digests runner/reach-digests
   :declared #((deref #'seon.fn/declared-callers) (deref #'seon.fn/read-or-throw!) %)})

(defn change []
  {:gate #((resolve 'proof/call-reach) %1 (vec %2))
   :latest (resolve 'proof/latest-results)
   :digests (resolve 'proof/reach-digests)
   :declared #((resolve 'proof/declared-callers) (deref #'seon.fn/read-or-throw!) %)})

(defn test-status
  [latest digests test-sym]
  (let [r (get latest test-sym)]
    (cond (nil? r) :never-run
          (:seon.test/result-unknown r) :unknown
          (not= (get digests test-sym) (:seon.test.member/reach-digest r)) :stale
          (pos? (+ (:seon.test/fail-count r 0) (:seon.test/error-count r 0))) :fail
          :else :pass)))

(defn reaching-results
  "Feedback (b): the latest result of each test reaching `changed`. Digests are
  derived only for tests with a recorded result: a test never run is never-run
  whatever its reach."
  [{:keys [gate latest digests]} database changed]
  (let [tests (vec (gate database changed))
        results (into {} (map (juxt (some-fn :seon.test/sym :seon.test/error-test-symbol) identity))
                      (latest database tests))
        reach (if (seq results) (digests database (vec (keys results))) {})
        statuses (into (sorted-map) (map (juxt identity (partial test-status results reach))) tests)]
    {:seon.feedback/tests statuses
     :seon.feedback/counts (frequencies (vals statuses))}))

(defn timed-proof
  [schema-key thunk]
  (let [[fresh value] (ms thunk)
        [warm _] (ms thunk)]
    {:valid? (m/validate (schemas schema-key) value) :fresh-ms fresh :warm-ms warm :value value}))

(defn proofs
  "One map of named proofs over `database` (the first reads of a freshly
  committed head) for `impl`, the parent's or the change's functions."
  [impl database seeds]
  {:b/reaching (timed-proof :tests #(reaching-results impl database seeds))
   :gate (timed-proof :selection #((:gate impl) database seeds))
   :declared (timed-proof :declared #((:declared impl) database))})
