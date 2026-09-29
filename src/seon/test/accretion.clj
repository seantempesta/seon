(ns seon.test.accretion
  "Schema generatability, the derived fact schema admission accretes onto each
  schema row, and the renderers of stored install refusals (the retired REPL
  install gate's error family, kept while its stored errors are shown)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.registry :as mr]
            [seon.render.value :as render.value]))

(defn generatable?
  "True when Malli can construct a generator for `schema` with `options`."
  {:malli/schema
   [:function
    [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "Malli generator discovery accepts arbitrary candidate declarations and compiled schemas, including invalid inputs for which it returns false."
                         :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]
    [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary
                         :seon.schema.admission/reason "Malli generator discovery accepts arbitrary candidate declarations and compiled schemas, including invalid inputs for which it returns false."
                         :gen/elements [nil false 0 "" :k [] {}]}] :map] :boolean]]}
  ([schema]
   (generatable? schema {}))
  ([schema options]
   ;; Malli answers "cannot generate" with two typed ex-infos:
   ;; `::mg/no-generator` (reference-code/malli/src/malli/generator.cljc:501,
   ;; and `-create-from-gen` for a declaration yielding no generator) and
   ;; `::m/invalid-schema` (`malli.core/-fail!`). Anything else propagates.
   (try
     (boolean (mg/generator schema options))
     (catch clojure.lang.ExceptionInfo failure
       (if (#{::mg/no-generator ::m/invalid-schema} (:type (ex-data failure)))
         false
         (throw failure))))))

(defn schema-row
  "Accrete the derived generatability fact onto one canonical schema row.

  # Known issues
  Compiles every form the row reaches again per call; `schema-row-in` answers
  from a projection. Its last caller converts and this function is deleted
  (task 0dfdcc8e447e)."
  {:malli/schema [:=> [:cat :map :map] :map]}
  [forms row]
  (let [registry (mr/composite-registry (m/default-schemas)
                                        (mr/fast-registry forms))]
    (assoc row :seon.schema/generatable?
           (try
             (generatable? (m/schema (:seon.schema/key row)
                                     {:registry registry}))
             (catch clojure.lang.ExceptionInfo failure
               (if (= ::m/invalid-schema (:type (ex-data failure)))
                 false
                 (throw failure)))))))

(defn schema-row-in
  "Accrete the derived generatability fact onto one canonical schema row.

  Generates from `projection`'s own compiled schema for the row's key. The
  projection's registry retains each compiled schema, so a reference is
  compiled once and Malli's generator cache on that object serves every row
  reaching it; `schema-row` compiles every reachable form again for each row.
  Both answer the same fact (all 3,568 packaged keys, default 2026-09-27).

  # Costs
  Armed, default's REPL 2026-09-27: 86 rows 11.6 ms on a fresh projection,
  0.1 ms again; `schema-row` 400 ms for the same rows."
  {:malli/schema [:=> [:cat :seon.schema/projection :map] :map]}
  [projection row]
  (assoc row :seon.schema/generatable?
         (generatable? (mr/schema (:seon.schema.projection/registry projection)
                                  (:seon.schema/key row)))))

(defn- render-failure
  "One failure's text: a test's expected and actual, or an auto-check's seed,
  arguments and result, each value through the value printer."
  {:malli/schema [:=> [:cat [:map [:seon.test.accretion/failure-source :seon.test.accretion/failure-source]]] :string]}
  [failure]
  (case (:seon.test.accretion/failure-source failure)
    :test
    (str "Test " (:seon.test/sym failure) "\n"
         (:seon.test.accretion/expected-actual failure))

    :auto-check
    ;; generated arguments and a returned actual are arbitrary values: shown
    ;; through the value printer, never through a whole world
    (str "Auto-check seed " (:seon.test.accretion/seed failure)
         "\narguments: " (render.value/bounded-text (:seon.test.accretion/arguments failure))
         "\nexpected: " (:seon.test.accretion/expected failure)
         "\nactual: " (let [actual (:seon.test.accretion/actual failure)]
                          (if (:seon.error/operation actual)
                            (str (:seon.error/operation actual) " — " (:seon.error/message actual))
                            (render.value/bounded-text actual)))
         (when-let [explanation (:seon.test.accretion/explanation failure)]
           (str "\nwhy: " explanation)))))

(defn render-ai
  "Render the grouped teaching face of an install refusal."
  {:malli/schema
   [:=> [:cat :seon.test.accretion/install-refused-error]
    :string]}
  [unit]
  (when (false? (:seon.test.accretion/install? unit))
    (str/join
     "\n\n"
     (concat
      [(str (:seon.error/operation unit) "\n" (:seon.error/message unit))
       (:seon.test.accretion/orientation unit)]
      (mapcat
       (fn [group]
         (let [members (:seon.test.accretion/failures group)
               shown (take 3 members)]
           (concat
            [(str "Failure group (" (count members) ")")]
            (map render-failure shown)
            (when (< 3 (count members))
              [(str (- (count members) 3)
                    " more in the complete gate-report blob.")]))))
       (:seon.test.accretion/failure-groups unit))
      (when (seq (:seon.test.accretion/advisories unit))
        [(str "Advisories\n"
              (str/join "\n" (:seon.test.accretion/advisories unit)))])))))

(defn render-html
  "Render an install refusal as one HTML card."
  {:malli/schema
   [:=> [:cat :seon.test.accretion/install-refused-error]
    :seon.render/hiccup]}
  [unit]
  (when-let [text (render-ai unit)]
    [:article {:class "seon-family-entry seon-test-gate-refusal"}
     [:pre text]]))
