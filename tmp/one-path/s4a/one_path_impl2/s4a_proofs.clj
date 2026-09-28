(ns one-path-impl2.s4a-proofs
  "S4a's proofs, run in a JVM that has the cut loaded (the rehearsal root's,
  then default's after the restart). Each proof answers {:pass? ...}; `run!`
  answers the map of named proofs and writes it beside this file.

  Entrants: the agent entrant is `seon.turn/virtual-turn!` (one ordinary run
  of the agent proc with a scripted reply, planned by `planned-sources`); the
  MCP entrant's run is the same `seon.cluster.agent/evaluate-source!` with the
  code segmenter. Both settle through `seon.turn/resume-turn`."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [sci.core :as sci]
            [seon.batch :as batch]
            [seon.cluster.boot :as boot]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.sci.forgive :as forgive]
            [seon.sci.kernel :as kernel]
            [seon.turn :as turn]
            [seon.turn.world :as world]))

(defonce ^:private registry (atom (sorted-map)))

(defmacro defproof [id claim & body]
  `(swap! registry assoc ~id {:claim ~claim :run (fn [] ~@body)}))

(defn- ms-since [t] (Math/round (/ (- (System/nanoTime) t) 1e6)))

(defn- handle []
  (:seon.turn.loop/cluster (get @@(requiring-resolve 'seon.operator.runtime/running-instances) "default")))

(defn- run-as
  "One run of root with `text`: :agent through the proc's scripted reply
  (planned-sources), :mcp through the code segmenter (as the MCP tool)."
  [entrant text]
  (let [h (handle)
        request (cond-> {:seon.turn.loop/cluster h :seon.agent/routing (:seon.agent/routing h)
                         :seon.agent/id "root" :seon.cluster.reply/text text}
                  (= :mcp entrant) (assoc :seon.agent/segmenter 'seon.turn/code-sources))
        result (if (= :agent entrant)
                 (turn/virtual-turn! request)
                 ((requiring-resolve 'seon.cluster.agent/evaluate-source!) request))
        ;; the MCP entrant answers once applied; its durability follows
        _ (some-> (:seon.db/durable result) (deref 10000 nil))]
    (or (:seon.turn/id result) (throw (ex-info "run refused" {:result result})))))

(def ^:private evaluation-keys
  [:seon.cluster.eval/ordinal :seon.cluster.eval/source :seon.eval/shown :seon.cluster.eval/output
   :seon.cluster.eval/error :seon.cluster.eval/executed :seon.cluster.eval/code-evidence :seon.cluster.eval/id
   :seon.sci.eval/ending-ns :seon.eval/duration-ms :seon.eval/allocated-bytes
   {:seon.cluster.eval/ns [:seon.ns/name]} {:seon.cluster.eval/read-evidence [:db/id]}])

(defn- evaluations [run-id]
  (->> (db/q [:find [(list 'pull '?e evaluation-keys) '...] :in '$ '?id
              :where ['?r :seon.turn/id '?id] ['?e :seon.cluster.eval/run '?r]]
             (db/db (boot/connection "default")) run-id)
       (sort-by :seon.cluster.eval/ordinal)
       (mapv #(update % :seon.cluster.eval/read-evidence count))))

(defn- stored [run-id] (mapv :seon.eval/shown (evaluations run-id)))

(defproof :held-reader-form
  "A1: each form is read once, at the live cursor: an alias a prior form of the same reply required resolves, through both entrants"
  (let [ids (mapv #(run-as % "(require '[clojure.string :as s4ar])\n::s4ar/k") [:agent :mcp])
        shown (mapv stored ids)]
    {:pass? (every? #(= [":clojure.string/k"] (subvec % 1)) shown) :shown shown}))

(defproof :settlement-field-parity
  "A2: the frame's evaluation reaches the settlement whole (output, integer cost, namespace, ending namespace, shown), the same through both entrants"
  (let [[a m] (mapv #(evaluations (run-as % "(do (println \"kept\") 42)")) [:agent :mcp])
        strip (fn [es] (mapv #(dissoc % :seon.cluster.eval/id :seon.eval/duration-ms :seon.eval/allocated-bytes) es))
        e (first a)]
    {:pass? (and (= (strip a) (strip m)) (= "kept\n" (:seon.cluster.eval/output e)) (= "42" (:seon.eval/shown e))
                 (int? (:seon.eval/duration-ms e))
                 ;; a form that allocated nothing records no allocation (absence, never 0)
                 (let [a (:seon.eval/allocated-bytes e)] (or (nil? a) (pos-int? a)))
                 (symbol? (:seon.sci.eval/ending-ns e)) (some? (get-in e [:seon.cluster.eval/ns :seon.ns/name])))
     :agent (first (strip a)) :mcp (first (strip m))}))

(defproof :early-done-replacement
  "A2: an early (my.agent/done {}) is refused on its form, its value kept beside the refusal, and the next form still runs"
  (let [es (evaluations (run-as :mcp "(my.agent/done {})\n(+ 40 2)"))]
    {:pass? (and (= 2 (count es)) (str/includes? (str (:seon.cluster.eval/error (first es))) "last form")
                 (= "42" (:seon.eval/shown (second es))))
     :forms (mapv #(select-keys % [:seon.eval/shown :seon.cluster.eval/error]) es)}))

(defproof :frame-post-evaluation-failure-keeps-value
  "R2-1: a failure after SCI returned (the loaded program changed while the form ran) keeps the value beside it as untrusted; its facts are refused"
  (let [h (handle)
        d (db/db (:seon.db/connection h))
        ctx (sci.eval/fork-cluster-ctx (:seon.sci.eval/ctx h) d (:seon.db/connection h))
        loaded (atom @@#'sci.eval/loaded-identity)
        advanced (if (map? @loaded) (assoc @loaded :seon.source/commit-id (java.util.UUID/randomUUID)) (Object.))
        _ (sci/intern ctx (sci/create-ns 'review) 'advance (fn [] (reset! loaded advanced) nil))
        ev (sci.eval/evaluate {:seon.sci.eval/ctx ctx :seon.db/db d :seon.cluster.eval/source "(do (review/advance) 42)"
                               :seon.sci.admit/caps (:seon.sci.admit/caps h) :seon.sci.eval/time-limit-ms 30000
                               :seon.cluster.eval/ns [:seon.ns/name 'user]
                               :seon.sci.eval/loaded-identity loaded
                               :seon.sci.eval/form '(do (review/advance) 42)})]
    {:pass? (and (= 42 (:seon.sci.admit/value ev)) (some? (:seon.sci.eval/untrusted ev))
                 (nil? (:seon.cluster.eval/error ev)))
     :value (:seon.sci.admit/value ev) :untrusted (:seon.error/message (:seon.sci.eval/untrusted ev))}))

(defproof :late-form-evidence
  "A3/R2-2: a form whose future has not exited records :pending code evidence; the refresh reports it and never runs it again until the work exits"
  (let [run-id (run-as :mcp (str "(def s4a-gate (promise))\n"
                                 "(do (future (deref s4a-gate) (seon.db/q '[:find ?id . :where [_ :seon.agent/id ?id]])) :started)\n"
                                 "(+ 1 1)"))
        es (evaluations run-id)
        id (:seon.cluster.eval/id (second es))
        pending-before (kernel/pending? id)
        _ (run-as :mcp "(deliver s4a-gate :go)")
        _ (loop [i 0] (when (and (< i 100) (kernel/pending? id)) (Thread/sleep 10) (recur (inc i))))]
    {:pass? (and (= :pending (:seon.cluster.eval/code-evidence (second es)))
                 (not= :pending (:seon.cluster.eval/code-evidence (nth es 2)))
                 pending-before (not (kernel/pending? id)))
     :evidence (mapv :seon.cluster.eval/code-evidence es) :pending-before pending-before}))

(defproof :pmap-work-is-owned
  "R2-3: an agent's pmap runs under the kernel's future-call: work it still holds leaves the form's evidence :pending, never :complete"
  (let [run-id (run-as :mcp (str "(def s4a-pmap-gate (promise))\n"
                                 "(first (pmap (fn [i] (when (= i 1) (deref s4a-pmap-gate)) i) (list 0 1)))"))
        es (evaluations run-id)]
    (run-as :mcp "(deliver s4a-pmap-gate :go)")
    {:pass? (and (= "0" (:seon.eval/shown (second es))) (= :pending (:seon.cluster.eval/code-evidence (second es))))
     :evidence (mapv :seon.cluster.eval/code-evidence es)}))

(defproof :executed-digest-evidence
  "A4: an acquired definition's call records its symbol and digest"
  (let [_ (run-as :mcp "(defn s4a-e {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
        e (first (evaluations (run-as :mcp "(s4a-e 1)")))]
    {:pass? (boolean (some #(= 'my.agents.root/s4a-e (first %)) (:seon.cluster.eval/executed e)))
     :executed (:seon.cluster.eval/executed e) :evidence (:seon.cluster.eval/code-evidence e)}))

(defproof :saved-digestless-callable-is-not-current-write
  "R2-4: a saved callable of an earlier definition, called after the name's one write in this batch, is not complete evidence, never repaired to the new write's digest"
  (let [_ (run-as :mcp "(defn s4a-s {:malli/schema [:=> [:cat] :keyword]} [] :old)\n(def s4a-saved s4a-s)")
        es (evaluations (run-as :mcp "(defn s4a-s {:malli/schema [:=> [:cat] :keyword]} [] :new)\n(s4a-saved)"))]
    {:pass? (and (= ":old" (:seon.eval/shown (second es))) (not= :complete (:seon.cluster.eval/code-evidence (second es))))
     :shown (mapv :seon.eval/shown es) :evidence (mapv :seon.cluster.eval/code-evidence es)
     :executed (:seon.cluster.eval/executed (second es))}))

(defn- stub-request
  [world stages]
  (merge {:seon.batch/world world :seon.batch/text "42 (+ 1 2)"
          :seon.batch/read-state (fn [t] {:seon.batch/version {:seon.batch/text t :seon.batch/pos 0} :seon.batch/repairs []})
          :seon.batch/read-next (fn [_ {v :seon.batch/version}]
                                  (let [t (:seon.batch/text v) p (forgive/skip-to t (:seon.batch/pos v))
                                        r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. (subs t p)))
                                        [f s] (read+string {:eof ::eof} r)]
                                    (if (= ::eof f) {:seon.batch/eof true :seon.batch/version v :seon.batch/repairs []}
                                        {:seon.batch/form f :seon.batch/start p :seon.batch/end (+ p (count s))
                                         :seon.batch/version (assoc v :seon.batch/pos (+ p (count s))) :seon.batch/repairs []})))
          :seon.batch/evaluate (fn [_ f _] {:seon.batch/value (eval f)})
          :seon.batch/ns-name (fn [_] 'user)}
         stages))

(defproof :settlement-preserves-published-entries
  "A5: a settlement answering no entries, or naming an ordinal the batch never published, loses no published value"
  (let [w (world/jvm-world "default" (boot/connection "default") {})
        b (batch/evaluate-batch (stub-request w {:seon.batch/settle (fn [_ _] {:seon.batch/applied false :seon.batch/tx-count 0
                                                                              :seon.batch/settled {:seon.batch/entries [{:seon.batch/ordinal 7}]}})}))]
    {:pass? (boolean (and (= [42 3] (mapv :seon.batch/value (:seon.batch/entries b)))
                          (some #(str/includes? (:seon.error/message %) "never published") (:seon.batch/diagnostics b))))
     :values (mapv :seon.batch/value (:seon.batch/entries b))}))

(defproof :publish-stage-totality
  "A publish stage that throws, or answers no entry of the form, is a diagnostic: the published entry stands and reading continues"
  (let [w (world/jvm-world "default" (boot/connection "default") {})
        throwing (batch/evaluate-batch (stub-request w {:seon.batch/publish (fn [_ _ _] (throw (ex-info "publish broke" {})))}))
        malformed (batch/evaluate-batch (stub-request w {:seon.batch/publish (fn [_ _ _] {:seon.batch/entry {:x 1}})}))]
    {:pass? (and (= [42 3] (mapv :seon.batch/value (:seon.batch/entries throwing)))
                 (= [42 3] (mapv :seon.batch/value (:seon.batch/entries malformed)))
                 (= 2 (count (:seon.batch/diagnostics throwing))) (= 2 (count (:seon.batch/diagnostics malformed))))
     :diagnostics (mapv :seon.error/message (concat (:seon.batch/diagnostics throwing) (:seon.batch/diagnostics malformed)))}))

(defproof :fold-facts-commit
  "A defn, a def, a require and a schema registration commit through the fold's facts in the turn's one settlement"
  (let [run-id (run-as :mcp (str "(defn s4a-c {:malli/schema [:=> [:cat :int] :int]} [x] x)\n(def s4a-v 1)\n"
                                 "(require '[clojure.set :as s4aset])\n(seon.schema/register! :s4a.proof/n :int)"))
        d (db/db (boot/connection "default"))]
    {:pass? (boolean (and (:seon.fn/sym (db/pull d [:seon.fn/sym] [:seon.fn/sym 'my.agents.root/s4a-c]))
                          (:seon.schema/key (db/pull d [:seon.schema/key] [:seon.schema/key :s4a.proof/n]))
                          (every? #(nil? (:seon.cluster.eval/error %)) (evaluations run-id))))
     :shown (stored run-id)}))

(defproof :refused-facts-are-form-by-form
  "OQ1: a form whose facts the writer refuses loses only its facts, logged as its error beside its kept value: as the last form (validated at the writer, then revalidated form by form) and as a middle form (validated before the next form reads)"
  (let [bad "(defn s4a-bad {:malli/schema [:=> [:cat :no.such/key] :int]} [x] x)"
        last-id (run-as :mcp (str "(defn s4a-ok1 {:malli/schema [:=> [:cat :int] :int]} [x] x)\n" bad))
        middle-id (run-as :mcp (str bad "\n(defn s4a-ok2 {:malli/schema [:=> [:cat :int] :int]} [x] x)"))
        d (db/db (boot/connection "default"))
        stored? (fn [sym] (some? (:seon.fn/sym (db/pull d [:seon.fn/sym] [:seon.fn/sym sym]))))
        [l m] [(evaluations last-id) (evaluations middle-id)]]
    {:pass? (and (stored? 'my.agents.root/s4a-ok1) (stored? 'my.agents.root/s4a-ok2) (not (stored? 'my.agents.root/s4a-bad))
                 (some? (:seon.cluster.eval/error (second l))) (some? (:seon.cluster.eval/error (first m)))
                 (nil? (:seon.cluster.eval/error (first l))) (nil? (:seon.cluster.eval/error (second m))))
     :last (mapv #(select-keys % [:seon.eval/shown :seon.cluster.eval/error]) l)
     :middle (mapv #(select-keys % [:seon.eval/shown :seon.cluster.eval/error]) m)}))

(defn- responses
  "The reply each evaluation of `run-id` answers, as both entrants read it
  (`seon.cluster.agent/run-observation`: the history's printed value)."
  [run-id]
  (let [c (boot/connection "default")]
    (mapv :seon.repl/response
          ((requiring-resolve 'seon.cluster.agent/run-observation)
           {:seon.turn/id run-id :seon.db/db (db/db c)} "root" "default"))))

(defproof :call-profile-in-reply
  "Owner: every evaluation's reply carries its timing and its call profile as data, the same through both entrants: its armed calls, and each function called more than 100 times with calls and ms"
  (let [_ (run-as :mcp "(defn s4a-rep {:malli/schema [:=> [:cat :int] :int]} [x] x)")
        replies (mapv #(responses (run-as % "(dotimes [i 300] (s4a-rep i))")) [:agent :mcp])
        text (mapv first replies)]
    ;; the reply prints as a #:seon.repl{...} namespaced map
    {:pass? (every? #(and (string? %) (str/includes? % ":calls 300") (str/includes? % "s4a-rep 300")
                          (str/includes? % ":ms "))
                    text)
     :replies text}))

(defn run-proofs!
  "Every proof (or those named in `only`), each timed; the map of named proofs,
  written to s4a-proof-results-<where>.edn."
  [where only]
  (let [results (into (sorted-map)
                      (keep (fn [[id {:keys [claim run]}]]
                              (when (or (nil? only) (contains? only id))
                                (let [t (System/nanoTime)
                                      r (try (run) (catch Throwable x {:pass? false :threw (str (class x) ": " (ex-message x))}))]
                                  [id (assoc r :claim claim :ms (ms-since t))]))))
                      @registry)]
    (spit (str "/Users/sean/src/seon/tmp/one-path/s4a/s4a-proof-results-" where ".edn")
          (with-out-str (pprint/pprint results)))
    (into (sorted-map) (map (fn [[k v]] [k (select-keys v [:pass? :ms :threw])])) results)))
