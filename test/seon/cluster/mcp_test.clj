(ns seon.cluster.mcp-test
  "The MCP surface shares Seon's admitted print-node value chain."
  (:require [clojure.core.async :as async]
            [seon.ai.tokens :as tokens]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fault :as fault]
            [seon.operator.runtime :refer [running-instances]]
            [seon.oversight :as oversight]
            [seon.print :as print]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest missing-artifacts-have-a-real-marker-under-armed-admission
  (let [missing {:seon.sci.admit/reason :over-bound :seon.sci.admit/bytes 123}
        artifact (render.value/artifact missing)]
    (is (= missing (render.value/artifact-value artifact)))
    (is (some? (:seon.sci.admit/print-node artifact)))
    (let [refusal (support/refusal-data
                   #(admit/admit-value
                     {:seon.sci.admit/value missing
                      :seon.sci.admit/caps {}
                      :seon.sci.admit/interrupt-fn (fn [])}))]
      (is ((schema/projection-validator (schema/handed-projection)
                                        :seon.instrument/contract-error) refusal))
      (is (= 'seon.sci.admit/admit-value
             (:seon.instrument/fn refusal))
          "bounded admission still requires its declared limits"))))

(defn- projected
  [cluster-name effective value & [evaluation? exception?]]
  (cluster/project-next-prepl-value! (boolean evaluation?))
  (edn/read-string (cluster/mcp-valf cluster-name effective value (boolean exception?))))

(defn- utf8-size
  [value]
  (alength (.getBytes ^String (pr-str value) "UTF-8")))

(defn- listening-instance
  "A declared `:seon.boot/instance` for a fixture cluster, built by boot's own
  constructor over this JVM's live listener and bootstrap config (renamed), with
  the fixture's connection as its database layer."
  [connection cluster-name]
  (let [live (some #(when (:seon.boot/prepl-server %) %) (vals @running-instances))]
    (assert live "a fixture instance stands beside this JVM's live instance")
    (assoc (boot/listening-instance (assoc (:seon.boot/config live) :seon.boot/cluster-name cluster-name)
                                    (:seon.boot/prepl-server live))
           :seon.boot/cluster-connection connection)))

(defn- running-instance
  [connection cluster-name]
  (let [projection (schema/projection-from-database (db/db connection))
        environment
        (env/environment
         {:seon.boot/cluster-name cluster-name
          :seon.db/connection connection
          :seon.db/basis-t (db/basis-t (db/db connection))
          :seon.schema/projection projection})]
    (assoc (listening-instance connection cluster-name)
     :seon.turn.loop/cluster (env/carry-state {} (env/environment-state environment))
     :seon.sci.eval/ctx
     (env/carry-state {} (env/environment-state environment)))))

(defn- sci-evaluation
  [effective source]
  (support/with-database
    (fn [connection]
      (sci.eval/evaluate
       {:seon.cluster.eval/source source
        :seon.cluster.eval/ns [:seon.ns/name 'user]
        :seon.db/db (db/db connection)
        :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
        :seon.sci.admit/caps (config/result-caps effective)
        :seon.render/profile (render/agent-render-profile effective)
        :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms effective)}))))

(defn- observed-query-shape
  []
  (let [text (apply str (repeat 36 \x))]
    (mapv (fn [row]
            (into {:db/id row}
                  (map (fn [field]
                         [(keyword "seon.fn" (str "field-" field)) text]))
                  (range 7)))
          (range 116))))

(deftest mcp-config-reads-receive-the-running-cluster-projection
  (support/with-database
   (fn [connection]
     (let [cluster-name "mcp-projection-test"
           database (db/db connection)
           projection (db/carried-projection database)
           projection-state (:seon.sci.eval/projection-state (meta database))
           observed (atom nil)]
       (swap! running-instances assoc cluster-name
              (assoc (listening-instance connection cluster-name)
                     :seon.sci.eval/ctx (env/carry-state {} projection-state)))
       (try
         (with-redefs [config/effective
                       (fn [value _]
                         (reset! observed
                                 (identical? projection (db/carried-projection value)))
                         config/defaults)]
           (is (= 42 (get-in (projected cluster-name config/defaults 42)
                            [:seon.dev.mcp/value])))
           (is (true? @observed)
               "the config read receives the projection carried by its database"))
         (finally
           (swap! running-instances dissoc cluster-name)))))))

(deftest nested-bulk-is-bounded-by-the-shared-value-window
  (let [cluster-name "mcp-nested-window-test"
        effective config/defaults
        oversized-string
        (apply str
               (repeat (inc (:seon.config.eval.result/max-string effective))
                       \x))
        nested-value {:rows (vec (range 50000)) :text oversized-string}
        result (projected cluster-name effective nested-value)]
    ;; A raw value is bounded by the profile's token budget (owner
    ;; 2026-09-26: the budget is chosen by provenance; raw values are tight).
    ;; The retired 8,192-byte window predates the one budget.
    (is (<= (tokens/estimate (pr-str (:seon.dev.mcp/value result)))
            (:seon.config.render.agent/token-budget effective))
        "nested collection and string bulk cannot escape the profile's budget")
    (is (< (utf8-size result) (utf8-size nested-value)))
    (is (true? (:seon.dev.mcp/windowed? result)))
    (is (string? (:seon.blob/digest result)))))

(deftest sci-evaluations-project-the-repl-text-face
  (let [cluster-name "mcp-text-face-test"
        effective config/defaults
        evaluation (sci-evaluation effective "(vec (range 50000))")
        result (projected cluster-name effective evaluation true false)
        face (:seon.dev.mcp/value result)]
    (is (string? (:seon.dev.mcp/text face))
        "an SCI evaluation projects the printed REPL face")
    (is (str/starts-with? (:seon.dev.mcp/text face) "[0 1 2")
        "the text face reads like a REPL value")
    (is (= (:seon.eval/shown evaluation) (:seon.dev.mcp/text face))
        "the saved observation is not rendered again")
    (is (not (contains? face :seon.sci.admit/value))
        "the live result does not ride beside its shown text")
    (is (< (utf8-size result) 8192))))

(deftest sci-top-level-strings-use-the-shared-value-window
  (let [cluster-name "mcp-top-level-string-window-test"
        effective config/defaults
        evaluation (sci-evaluation effective
                                    "(apply str (repeat 1048576 \\x))")
        result (projected cluster-name effective evaluation true false)
        text (get-in result [:seon.dev.mcp/value :seon.dev.mcp/text])]
    (is (<= (tokens/estimate text) (:seon.config.render.agent/token-budget effective))
        "a scalar face is bounded by the same profile budget as structural values")
    (is (= (:seon.eval/shown evaluation) text))
    (is (false? (:seon.dev.mcp/windowed? result)))
    (is (not (contains? result :seon.blob/digest))
        "SCI results retain their live objects, not serialized result blobs")))

(deftest ordinary-mcp-results-always-use-the-explicit-mcp-profile
  (let [cluster-name "mcp-unconditional-fit-test"
        ;; A budget the 116 rows exceed, so members of nested rows are cut
        ;; too; at the default budget 32 whole rows fit (13,506 characters).
        effective (assoc config/defaults
                         :seon.config.eval.result/blob-threshold 1000000
                         :seon.config.render.agent/token-budget 1000)
        text (apply str (repeat 36 \x))
        result (projected cluster-name effective (observed-query-shape))
        face (:seon.dev.mcp/value result)
        rendered (pr-str face)
        elisions (filter #(and (map? %) (:seon.print/omitted %))
                         (tree-seq coll? seq face))]
    (is (str/includes? rendered (pr-str text))
        "the observed 36-character strings remain readable")
    (is (str/includes? rendered ":seon.render.profile/mcp")
        "the below-blob-threshold result still carries the MCP fit profile")
    (is (some #(= [] (:seon.render.data/path %)) elisions)
        "collection elisions retain their coordinates")
    (is (some #(seq (:seon.render.data/path %)) elisions)
        "nested map-member elisions retain their coordinates too")
    (is (every? #(and (pos-int? (:seon.print/omitted %))
                     (string? (:seon.print/requery-refusal %))) elisions)
        "a cut without a stored artifact names why it cannot be requeried")
    (is (false? (:seon.dev.mcp/windowed? result))
        "presentation fitting does not invent a durable artifact")))

(deftest sci-artifact-size-ignores-evaluation-envelope-bulk
  (let [cluster-name "mcp-small-sci-value-test"
        effective config/defaults
        evaluation
        (assoc (sci-evaluation effective "42")
               :seon.sci.eval/internal-detail (apply str (repeat 5000 \x)))
        result (projected cluster-name effective evaluation true false)
        face (:seon.dev.mcp/value result)]
    (is (= "42" (:seon.dev.mcp/text face)))
    (is (= (:seon.sci.admit/record evaluation)
           (:seon.sci.admit/record face))
        "evaluation diagnostics stay inline beside the text face")
    (is (false? (:seon.dev.mcp/windowed? result)))
    (is (not (contains? result :seon.blob/digest)))))

(defn- jvm-exceptions-keep-the-whole-cause
  ;; An exception is an ordinary value on the one REPL path: prepl's
  ;; `Throwable->map` projects like any other value, every cause link with
  ;; its message and ex-data, and an oversized one pages by digest.
  [connection]
  (let [cluster-name "mcp-jvm-exception-face-test"
        effective config/defaults
        inline-ceiling (:seon.config.eval.result/blob-threshold effective)
        frame ['seon.cluster$mcp_io_prepl 'invokeStatic "cluster.clj" 336]
        root-message "The contract value was wrong."
        oversized-message (apply str (repeat (inc inline-ceiling) \x))
        envelope
        {:via [{:type 'clojure.lang.ExceptionInfo
                :message "The wrapper."
                :data {:seon.probe/member 1}
                :at frame}
               {:type 'java.lang.IllegalArgumentException
                :message root-message
                :at frame}]
         :trace [frame]
         :cause root-message
         :phase :execution}]
    (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name cluster-name})
    (support/seed-cluster! connection cluster-name)
    (support/transacted! connection
                         [{:seon.ns/name 'seon.cluster.mcp-test
                           :seon.ns/source "(ns seon.cluster.mcp-test)"}])
    (swap! running-instances assoc cluster-name
           (running-instance connection cluster-name))
    (try
      (let [result (projected cluster-name effective envelope false true)
            whole (:seon.dev.mcp/value result)
            shown (pr-str result)
            oversized-result
            (projected cluster-name effective
                       (assoc envelope :cause oversized-message) false true)
            retained-message
            (cluster/mcp-get-value
             cluster-name (:seon.blob/digest oversized-result) [:cause] 0)]
        ;; `pr-str` of the reply lifts a one-namespace map to `#:seon.probe{…}`,
        ;; so the member is searched by its namespace; its data is asserted below.
        (doseq [part ["The wrapper." root-message "seon.probe"
                      "clojure.lang.ExceptionInfo" "java.lang.IllegalArgumentException"]]
          (is (str/includes? shown part)
              (str "the reply keeps every cause link: " part)))
        (is (false? (:seon.dev.mcp/windowed? result)) "a small exception stays inline")
        (is (= [{:seon.probe/member 1} nil] (mapv :data (:via whole)))
            "each link keeps its own ex-data")
        (is (= root-message (:cause whole)) "the root cause is the value's own member")
        (is (true? (:seon.dev.mcp/windowed? oversized-result)))
        (is (true? (:seon.dev.mcp/retrievable? oversized-result)))
        ;; A page is the floor's selection at the path (one-render-path §3g).
        ;; The page opens the string literal with its first characters; a
        ;; closed 16-character literal would claim the string ends there.
        (is (str/starts-with? (:seon.dev.mcp/text retained-message)
                              (str "\"" (subs oversized-message 0 16)))
            "an oversized cause pages by digest")
        (is (= (count oversized-message)
               (:seon.render.data/total retained-message))))
      (finally
        (swap! running-instances dissoc cluster-name)))))


(deftest runtime-observation-is-whole-and-its-ai-face-renders-less
  ;; The producer returns every open fault whole, newest first; the value
  ;; printer is the one clipper. Its declared AI face, which `bin/seon status`
  ;; prints, names counts and the newest five by operation, never a message.
  ;; Problem families are counts; the readiness value omits their facts.
  (support/with-database
   (fn [connection]
     (let [cluster-name (str "mcp-observation-bound-" (random-uuid))
           _ (support/apply-config! connection cluster-name {})
           environment (env/environment {:seon.boot/cluster-name cluster-name :seon.db/connection connection})
           record! (fn [i message]
                     (fault/fault! environment (IllegalStateException. ^String message)
                                   {:seon.error/layer ::observation-bound
                                    :seon.error/operation (symbol "seon.cluster.mcp-test" (str "fault-" i))
                                    :seon.db.process/id "mcp-test"}))
           receipts (mapv #(record! % "bounded fault") (range 50))
           long-receipt (record! 50 (apply str (repeat 1000 \x)))
           _ (problems/resolve! connection (:seon.error/signature (first receipts)))]
       (swap! running-instances assoc cluster-name (listening-instance connection cluster-name))
       (try
         (let [result (cluster/mcp-runtime-observation cluster-name)
               shown (:seon.dev.mcp/open-faults result)
               status (cluster/runtime-observation-ai {:seon.render/value result})
               status-faults (:seon.dev.mcp/open-faults status)]
           ;; the fixture branch also carries its parent's open and resolved faults
           (is (<= 50 (:seon.dev.mcp/open-fault-count result)))
           (is (= (:seon.dev.mcp/open-fault-count result) (count shown)) "every open fault, whole")
           (is (<= 1 (:seon.dev.mcp/resolved-fault-count result)))
           (is (not-any? #{(:seon.error/signature (first receipts))} (map :seon.error/signature shown)))
           (is (= (apply str (repeat 1000 \x))
                  (some #(when (= (:seon.error/signature long-receipt) (:seon.error/signature %))
                           (:seon.error/message %))
                        shown))
               "the producer keeps the whole message")
           (is (<= 50 (get-in result [:seon.dev.mcp/problem-counts :seon.problems/error-signatures]))
               "each problem family is a count, never its facts")
           (is (not (contains? (:seon.dev.mcp/readiness result) :seon.problems/problems)))
           (testing "the declared AI face renders less"
             (is (= :seon.dev.mcp/runtime-observation
                    (last (:malli/schema (meta #'cluster/mcp-runtime-observation)))))
             (is (= (count shown) (:seon.dev.mcp/count status-faults)))
             (is (= 3 (count (:seon.dev.mcp/top status-faults))))
             (is (= (- (count shown) 3) (:seon.dev.mcp/omitted status-faults)))
             (is (not-any? :seon.error/message (:seon.dev.mcp/top status-faults))
                 "a fault is named by operation, never by its unbounded message")
             ;; It fits the 1,000-token agent budget (~3,200 characters) whole,
             ;; so the MCP reply is the face, never a blob handle.
             (is (< (count (pr-str status)) 3200))))
         (finally (swap! running-instances dissoc cluster-name)))))))

(deftest ^{:seon.test/fixture-observation
           "Observes that flow health uses an ordinary canonical branch; the fresh-store owner is referenced only to assert zero acquisitions."}
  live-runtime-observation-hands-its-projection-to-flow-health
  (let [cluster-name "mcp-runtime-projection-test"
        fresh-stores (atom 0)
        acquire @#'support/with-fresh-database]
    (with-redefs-fn
      {#'support/with-fresh-database
       (fn [& args] (swap! fresh-stores inc) (apply acquire args))}
      (fn []
        (support/with-database
         (fn [connection]
           (config/apply! {:seon.db/connection connection
                          :seon.boot/cluster-name cluster-name})
           (support/seed-cluster! connection cluster-name)
           (swap! running-instances assoc cluster-name
                  (assoc (running-instance connection cluster-name)
                         :seon.flow/graph ::graph))
           (try
             (with-redefs
              [boot/readiness
               (fn [_] {:seon.boot/cluster-name cluster-name})
               oversight/flow-status
               (fn [database _]
                 (let [effective (config/effective database cluster-name)]
                   (if (or (:seon.config/missing-effective effective) (:seon.config/error-key effective) (:seon.db/invalid-read effective) (:seon.schema/expected-value effective))
                     effective
                     {:seon.oversight/agents []
                      :seon.oversight/plumbing []})))]
               (let [result (cluster/mcp-runtime-observation cluster-name)]
                 (is (= :observed (:seon.dev.mcp/health result)))
                 (is (= {:seon.oversight/agents []
                         :seon.oversight/plumbing []}
                        (:seon.dev.mcp/flow result)))
                 (is (= :observed (:seon.dev.mcp/health result)))))
             (finally
               (swap! running-instances dissoc cluster-name)))))))
    (is (zero? @fresh-stores)
        "Flow-health projection needs only an ordinary canonical branch.")))

(defn- oversized-values-share-one-digest-across-storeless-and-stored-modes
  [connection]
  (let [cluster-name "mcp-value-test"
        value (vec (range 2000))
        effective config/defaults
        storeless (projected cluster-name effective value)]
    (is (true? (:seon.dev.mcp/windowed? storeless)))
    (is (false? (:seon.dev.mcp/retrievable? storeless)))
    (is (str/includes? (:seon.dev.mcp/remainder storeless)
                       "not retrievable"))
    (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name cluster-name})
    (support/seed-cluster! connection cluster-name)
    (swap! running-instances assoc cluster-name
           (running-instance connection cluster-name))
    (try
      (let [stored (projected cluster-name effective value)
            content-digest (:seon.blob/digest stored)
            drilled (cluster/mcp-get-value
                     cluster-name content-digest [] 7)
            past-end (cluster/mcp-get-value
                      cluster-name content-digest [] 9000)]
        (is (= (:seon.blob/digest storeless) content-digest))
        (is (true? (:seon.dev.mcp/retrievable? stored)))
        (is (str/starts-with? (:seon.dev.mcp/text drilled) "[7 8 9 10 11 12 13 14")
            (:seon.dev.mcp/text drilled))
        (is (< 7 (:seon.render.data/next-offset drilled) 2000)
            "the page names its absolute continuation")
        (is (= 9000 (:seon.render.data/offset past-end)))
        (is (= 2000 (:seon.render.data/total past-end)))
        (is (true? (:seon.render.value/beyond-end? past-end))))
      (finally
        (swap! running-instances dissoc cluster-name)))))

(defn- stored-strings-page-by-character-offset
  [connection]
  (let [cluster-name "mcp-string-page-test"
        effective config/defaults
        value (apply str (take 4975 (cycle "abcdefghijklmnopqrstuvwxyz")))
        page-size (:seon.print/width (print/default-options))]
    (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name cluster-name})
    (support/seed-cluster! connection cluster-name)
    (swap! running-instances assoc cluster-name
           (running-instance connection cluster-name))
    (try
      (let [stored (projected cluster-name effective value)
            digest (:seon.blob/digest stored)
            first-page (cluster/mcp-get-value cluster-name digest [] 0)
            next-page
            (cluster/mcp-get-value cluster-name digest [] page-size)
            past-end
            (cluster/mcp-get-value cluster-name digest [] (count value))]
        ;; A string pages by absolute character offset: each page is the
        ;; floor's selection of the characters from that offset.
        (is (str/starts-with? (:seon.dev.mcp/text first-page) (str "\"" (subs value 0 page-size))))
        (is (str/starts-with? (:seon.dev.mcp/text next-page)
                              (str "\"" (subs value page-size (* 2 page-size)))))
        (is (= (count value) (:seon.render.data/total first-page)))
        (is (= "\"\"" (:seon.dev.mcp/text past-end))
            "past the end the page is the empty remainder, named beyond the end")
        (is (true? (:seon.render.value/beyond-end? past-end))))
      (finally
        (swap! running-instances dissoc cluster-name)))))

(defn- retrievable-artifacts-commit-no-transaction
  {:malli/schema [:=> [:cat :seon.db/connection] :boolean]}
  [connection]
  (let [cluster-name "mcp-durable-artifact-test"
        effective config/defaults
        value (vec (range 3000))]
    (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name cluster-name})
    (support/seed-cluster! connection cluster-name)
    (swap! running-instances assoc cluster-name
           (running-instance connection cluster-name))
    (try
      (let [before (db/commit-id (db/db connection))
            stored (projected cluster-name effective value)
            content-digest (:seon.blob/digest stored)]
        (is (= before (db/commit-id (db/db connection)))
            "a windowed result stores its blob and commits no transaction")
        (is (true? (:seon.dev.mcp/retrievable? stored))
            "the stored blob is retrievable by its digest")
        (let [requery (some :seon.print/requery-form
                            (tree-seq coll? seq (:seon.dev.mcp/value stored)))]
          (is (seq requery) "a clipped value supplies an executable requery")
          (is (= value (eval requery))
              "the advertised requery reads the complete stored value"))
        (let [absent (apply str (repeat 64 "0"))]
          (is (= [:gone [:seon.blob/digest absent]]
                 ((juxt :seon.render.walk/continuation-reason :seon.render.walk/continuation-node)
                  (cluster/mcp-get-value cluster-name absent [] 0)))
              "an unknown digest is the one continuation owner's :gone refusal"))
        (is (not= content-digest (apply str (repeat 64 "0")))))
      (finally
        (swap! running-instances dissoc cluster-name)))))

(defn- ordinary-value-artifacts-drill-from-the-result-root
  [connection]
  (let [cluster-name "mcp-sci-value-test"
        effective config/defaults
        value (assoc (vec (range 2000)) 1999 2999)
        nested-value {:alpha value :omega 42}]
    (config/apply! {:seon.db/connection connection
                    :seon.boot/cluster-name cluster-name})
    (support/seed-cluster! connection cluster-name)
    (swap! running-instances assoc cluster-name
           (running-instance connection cluster-name))
    (try
      (let [stored (projected cluster-name effective value)
            content-digest (:seon.blob/digest stored)
            nested-stored (projected cluster-name effective nested-value)
            root (cluster/mcp-get-value
                  cluster-name content-digest [] 0)
            nested (cluster/mcp-get-value
                    cluster-name (:seon.blob/digest nested-stored)
                    [:alpha] 7)
            projected-root (projected cluster-name effective root)]
        (is (str/starts-with? (:seon.dev.mcp/text root) "[0 1 2 3 4 5 6 7") (:seon.dev.mcp/text root))
        (is (str/starts-with? (:seon.dev.mcp/text nested) "[7 8 9 10 11 12 13 14") (:seon.dev.mcp/text nested))
        (is (false? (:seon.dev.mcp/windowed? projected-root))
            "reading the result root must not mint another artifact")
        (is (not (contains? projected-root :seon.blob/digest))))
      (finally
        (swap! running-instances dissoc cluster-name)))))

(deftest artifact-lifecycle-preserves-identity-paging-and-retraction
  ;; Every scenario configures and seeds its cluster, which a program-less
  ;; fresh store cannot take (config resolves the functions it names to program
  ;; rows), so they run on the member's branched world. No assertion needs a
  ;; private store: digests are content identities, the zero-transaction check
  ;; reads this branch's commit, and the absent digest is absent everywhere.
  (let [scenarios
        [[:exception jvm-exceptions-keep-the-whole-cause]
         [:identity oversized-values-share-one-digest-across-storeless-and-stored-modes]
         [:string stored-strings-page-by-character-offset]
         [:nested ordinary-value-artifacts-drill-from-the-result-root]
         [:blob-only retrievable-artifacts-commit-no-transaction]]
        executed (support/with-database
                  (fn [connection]
                    (into [] (map (fn [[scenario verify!]] (verify! connection) scenario))
                          scenarios)))]
    (is (= (mapv first scenarios) executed)
        "All five artifact cases execute, with the blob-only case last.")))

(deftest only-an-evaluation-that-changed-loaded-code-drops-cached-pages
  ;; 24z: the page-cache signal follows evidence after the evaluation
  ;; (Var/rev and namespace mappings), not the read_only flag alone.
  (let [signal (fn [body]
                 (let [channel (async/chan 1)]
                   (cluster/project-next-prepl-value! {:seon.dev.mcp/read-only? false})
                   (cluster/mcp-valf "mcp-signal-test" config/defaults (body) false [channel])
                   (some? (async/poll! channel))))
        probe (symbol (str "signal-probe-" (random-uuid)))
        created (atom nil)]
    (try
      (is (false? (signal #(+ 1 2))) "a form that changes no code keeps cached pages")
      (is (true? (signal #(reset! created (intern 'seon.cluster.mcp-test probe 1)))))
      (is (true? (signal #(alter-var-root @created inc))))
      (is (true? (signal #(ns-unmap 'seon.cluster.mcp-test probe))))
      (finally (ns-unmap 'seon.cluster.mcp-test probe)))))
