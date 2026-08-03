(ns seon.error-class-schema-test
  "Census and invariant proofs for the declared error-class vocabulary."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.schema.form :as schema.form]))

(def ^:private catalog-markers
  {"my.background" '[invalid-call invalid-result missing-result]
   "my.edit" '[no-match ambiguous-match parse-refused lossless-check-failed stale-source not-utf8]
   "my.fs" '[not-found not-directory not-regular-file already-exists path-refused read-failed write-failed read-limit write-limit stale-digest changed-during-read invalid-utf8-window atomic-write-unsupported glob-failed blob-unavailable invalid-glob]
   "my.message" '[no-recipient no-content no-about no-reason]
   "my.run" '[blank-note blank-result]
   "seon.ai" '[unparseable-body provider-error timeout transport-failure no-credential token-starvation invalid-extra-body extra-body-conflict rate-limited provider-server-failure credential-failure authentication-failure authorization-failure model-failure transport-before-send-failure request-failure response-failure transport-outcome-unknown]
   "seon.fn" '[index-transaction-refused source-checkout-required source-span-absent capability-graph-malformed analysis-failed source-file-invalid manifest-absent duplicate-program-identity population-incomplete schema-declaration-invalid scratch-not-fresh]
   "seon.instrument" '[contract-violated]
   "seon.schema" '[cyclic-reference unresolved-predicate noncanonical-definition noncanonical-projection-data unproved-predicate-purity unreadable-form non-round-tripping-form unregister-outside-delta malformed-projection-row malformed-projection-form duplicate-projection-row malformed-projection-identity malformed-artifact-export schema-in-use unknown-shape undefined-contract incomplete-predicate-contract nilable-map-value nilable-return invalid-schema nilable-value-schema single-segment-namespace]
   "seon.schema.edn" '[unreadable-file duplicate-attribute not-a-map unsafe-namespace misplaced-attribute dishonest-generator unregistered-predicate unresolved-reference]
   "seon.schema.datahike" '[literal-not-storable enum-not-storable nilable-attribute value-type-unavailable attribute-absent invalid-secondary-attribute schema-invalid storage-not-string malformed-edn noncanonical-edn]
   "seon.sci.eval" '[install-mismatch missing-function-row namespace-binding-cycle reader-event-count schema-refused session-blob-unavailable time-limit evaluation-failed]
   "seon.sci.kernel" '[already-armed missing-function-installer missing-interrupt-guard unresolved-invocation failure-admission-failed time-limit invocation-failed]
   "seon.sci.admit" '[projection-failed]
   "seon.sci.reader" '[keyword]
   "seon.print" '[unknown-face]
   "seon.program" '[declaration-refused]
   "seon.problems" '[unbound-var evaluation-failed]
   "seon.blob" '[invalid-threshold store-root-absent stored-content-mismatch input-stalled content-digest-mismatch]
   "seon.cluster.store" '[branch-absent branch-already-open held-elsewhere initialization-incomplete refused file-lock-generator-failed]
   "seon.cluster.source" '[root-absent invalid-source-seal populate-unresolvable publish-readback-failed stale-publication unsafe-incremental-rows refused]
   "seon.cluster.registry" '[cannot-retire-main cluster-connected source-absent refused]
   "seon.cluster.export" '[clone-unsupported export-exists genesis-incomplete no-branch-head refused]
   "seon.cluster.message" '[unknown-recipient unknown-about ambiguous-about blank-content content-too-large chain-limit no-limit]
   "seon.cluster.run" '[refused]
   "seon.cluster.loop" '[lint-rejected prompt-failed terminal-refusal-settlement-refused]
   "seon.cluster.agent" '[no-such-agent turn-completion-backstop turn-completion-undeliverable]
   "seon.cluster.wake" '[undeliverable-wake]
   "seon.cluster.process" '[start-instant-unavailable]
   "seon.config" '[refused manifest-unreadable reconcile-refused required-absent unknown-key]
   "seon.reconcile" '[refused no-identity two-identities duplicate-identity identity-outside-scope]
   "seon.bootstrap" '[resource-absent resource-invalid population-conflict plan-absent invalid-ordinals agent-plan-absent]
   "seon.boot" '[refused]
   "seon.db" '[transaction-refused transaction-outcome-unknown]
   "seon.flow" '[submission-capacity launcher-stopped time-limit configuration timeout]
   "seon.render.walk" '[elided no-such-entity connections-failed]
   "seon.render" '[ambiguous invalid-output]
   "seon.render.hiccup" '[unparseable-tag]
   "seon.render.data" '[no-such-path]
   "seon.render.value" '[window-failed]
   "seon.render.web" '[missing-port owner-not-ensured value-not-found value-unreadable]
   "seon.dev.mcp" '[cluster-degraded value-not-found remainder-not-retrievable]
   "seon.cluster.reply" '[refused-tag unreadable no-forms]
   "seon.test.runner" '[invalid-silence-seconds invalid-long-reason long-test-ns-hook default-cluster-refused invalid-selection-mode]
   "seon.artifact" '[refused]
   "seon.operator" '[failed]
   "seon.eval.drive" '[absent]
   "seon.cluster.prompt" '[no-trigger refused]
   "seon.error" '[unclassified]})

(def ^:private expected-class-schema-keys
  (into #{}
        (mapcat (fn [[ns-part markers]]
                  (map #(keyword ns-part (str (name %) "-error")) markers)))
        catalog-markers))

(def ^:private refusal-class-schema-keys
  #{:seon.config/refused-error
    :seon.reconcile/refused-error
    :seon.artifact/refused-error
    :seon.cluster.store/refused-error
    :seon.cluster.source/refused-error
    :seon.cluster.registry/refused-error
    :seon.cluster.prompt/refused-error
    :seon.cluster.export/refused-error
    :seon.cluster.run/refused-error
    :seon.boot/refused-error})

(defn- marker-key [class-schema-key]
  (let [n (name class-schema-key)
        suffix "-error"]
    (keyword (namespace class-schema-key)
             (subs n 0 (- (count n) (count suffix))))))

(defn- class-properties [form]
  (schema.form/namespaced-properties form))

(defn- expected-ai-renderer [class-schema-key]
  (cond
    (= "seon.ai" (namespace class-schema-key)) 'seon.error/ai-prose
    (= class-schema-key :seon.instrument/contract-violated-error)
    'seon.error/instrumentation-prose
    (= class-schema-key :seon.cluster.run/refused-error)
    'seon.error/refusal-prose
    :else 'seon.error/render-ai))

(deftest catalog-class-schemas-are-complete-and-declared
  (schema.edn/load! {})
  (let [forms (schema.edn/packaged-forms)
        registry (:seon.schema.projection/registry
                  (schema/build-projection (schema/registered-schemas)))
        class-forms (into {}
                          (filter (fn [[_ form]]
                                    (true? (:seon.error/class
                                            (class-properties form)))))
                          forms)
        catalog-namespaces (set (keys catalog-markers))
        actual (into #{}
                     (comp (filter #(contains? catalog-namespaces
                                               (namespace %))))
                     (keys class-forms))]
    (is (= 218 (count expected-class-schema-keys)))
    (is (= expected-class-schema-keys actual))
    (doseq [class-schema-key (sort expected-class-schema-keys)]
      (let [form (get class-forms class-schema-key)
            properties (class-properties form)
            marker (marker-key class-schema-key)]
        (testing (str class-schema-key)
          (is (= true (:seon.error/class properties)))
          (is (= (expected-ai-renderer class-schema-key)
                 (:seon.render/ai properties)))
          (is (= 'seon.error/render-html (:seon.render/html properties)))
          (is (not (str/blank? (:error/message properties))))
          (is (contains? forms marker))
          (is (m/schema class-schema-key {:registry registry})))))))

(deftest refusal-classes-reference-one-shared-value-shape
  (schema.edn/load! {})
  (let [forms (schema.edn/packaged-forms)
        registry (:seon.schema.projection/registry
                  (schema/build-projection (schema/registered-schemas)))]
    (is (= :map (first (:seon.error/refusal-value forms))))
    (doseq [class-schema-key refusal-class-schema-keys]
      (let [form (get forms class-schema-key)
            properties (class-properties form)]
        (is (= true (:seon.error/refusal properties)))
        (is (= :seon.error/refusal-value
               (:seon.error/refusal-shape properties)))
        (is (some #{:seon.error/refusal-value} form))))
    (is (m/validate
         (m/schema :seon.cluster.run/refused-error {:registry registry})
         {:seon.cluster.run/refused :transition-not-eligible
          :seon.error/message "The run transition was refused."}))))

(deftest catalog-classes-generate-values-with-required-markers-and-messages
  (schema.edn/load! {})
  (let [forms (schema.edn/packaged-forms)
        registry (:seon.schema.projection/registry
                  (schema/build-projection (schema/registered-schemas)))
        generatable
        [:my.message/no-recipient-error
         :my.fs/not-found-error
         :seon.ai/provider-error-error]]
    (doseq [[ordinal class-schema-key]
            (map-indexed vector generatable)]
      (let [compiled (m/schema class-schema-key {:registry registry})
            value (mg/generate compiled {:seed (+ 2026080300 ordinal)
                                         :size 8})]
        (is (m/validate compiled value) (str class-schema-key))
        (is (contains? value (marker-key class-schema-key)))
        (is (not (str/blank? (:seon.error/message value))))))))

(deftest class-markers-project-as-queryable-schema-row-attributes
  (schema.edn/load! {})
  (let [rows (schema/canonical-schema-rows (schema.edn/packaged-forms))
        queried (into #{}
                      (comp (filter #(= true (:seon.error/class %)))
                            (map :seon.schema/key))
                      rows)]
    (is (= expected-class-schema-keys
           (set/intersection expected-class-schema-keys queried)))))
