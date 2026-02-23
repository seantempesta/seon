(ns seon.graph.extract-test
  "Tests for seon.graph.extract - unified code graph extraction pipeline."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.graph.extract :as extract]))

;;; ---------------------------------------------------------------------------
;;; Basic Extraction
;;; ---------------------------------------------------------------------------

(deftest extract-graph-basic-test
  (testing "extracts all entity types from a simple source string"
    (let [source "(ns seon.example
  (:require [seon.schema :as schema]))

(schema/register! ::foo-request
                  [:map [::bar :string]])

(schema/register! ::foo-response
                  [:map [::result :int]])

(def my-const 42)

(defn foo
  \"Does foo things.\"
  [{::keys [bar]}]
  {:seon.example/result (count bar)})

(defn- helper [x] (inc x))"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      ;; Namespace
      (is (= "seon.example" (::extract/ns-name graph)))
      (is (seq (::extract/namespaces graph)))

      ;; Functions from clj-kondo
      (let [fns (::extract/functions graph)
            foo-fn (first (filter #(= "foo" (:seon.fn/name %)) fns))
            helper-fn (first (filter #(= "helper" (:seon.fn/name %)) fns))]
        (is (>= (count fns) 2) "Should find at least foo and helper")
        (is (some? foo-fn))
        (is (= "seon.example/foo" (:seon.fn/qualified-name foo-fn)))
        (is (= "seon.example" (:seon.fn/namespace foo-fn)))
        (is (string? (:seon.fn/arglists foo-fn)))
        (is (= "Does foo things." (:seon.fn/doc foo-fn)))
        (is (false? (:seon.fn/private foo-fn)))
        (is (inst? (:seon.fn/updated-at foo-fn)))

        (is (some? helper-fn))
        (is (true? (:seon.fn/private helper-fn))))

      ;; Specs from edamame
      (let [specs (::extract/specs graph)]
        (is (= 2 (count specs)))
        (is (some #(= :seon.example/foo-request (:seon.spec/key %)) specs))
        (is (some #(= :seon.example/foo-response (:seon.spec/key %)) specs)))

      ;; Vars from edamame (authoritative for value type)
      (let [vars (::extract/vars graph)
            const-var (first (filter #(= "my-const" (:seon.var/name %)) vars))]
        (is (some? const-var))
        (is (= :number (:seon.var/value-type const-var))))

      ;; Call edges from clj-kondo
      (is (vector? (::extract/call-edges graph)))

      ;; NS deps
      (is (vector? (::extract/ns-deps graph)))
      (is (some #(= "seon.schema" (:seon.ns.dep/to-ns %))
                (::extract/ns-deps graph))))))

;;; ---------------------------------------------------------------------------
;;; Spec Linking
;;; ---------------------------------------------------------------------------

(deftest extract-graph-spec-linking-test
  (testing "links functions to matching request/response specs"
    (let [source "(ns seon.example
  (:require [seon.schema :as schema]))

(schema/register! ::process-request
                  [:map [::input :string]])

(schema/register! ::process-response
                  [:map [::output :int]])

(defn process
  \"Process input.\"
  [{::keys [input]}]
  {::output (count input)})"
          graph (extract/extract-graph {::extract/source source})
          process-fn (first (filter #(= "process" (:seon.fn/name %))
                                    (::extract/functions graph)))]
      (is (some? process-fn))
      (is (= [:seon.spec/key :seon.example/process-request]
             (:seon.fn/input-spec process-fn)))
      (is (= [:seon.spec/key :seon.example/process-response]
             (:seon.fn/output-spec process-fn))))))

;;; ---------------------------------------------------------------------------
;;; Page Renderer Detection
;;; ---------------------------------------------------------------------------

(deftest extract-graph-page-renderer-test
  (testing "detects page renderers via *ctx* in input and render keys in output"
    (let [source "(ns seon.example.render
  (:require [seon.schema :as schema]
            [seon.health.workout :as workout]))

(schema/register! ::my-page-request
                  [:map [::workout/*ctx* :any]])

(schema/register! ::my-page-response
                  [:map [:seon.render/html :string]
                        [:seon.render/ai :string]])

(defn my-page
  \"Render my page.\"
  [{::keys [ctx]}]
  {:seon.render/html \"<div>hi</div>\"
   :seon.render/ai \"page content\"})"
          graph (extract/extract-graph {::extract/source source})
          fn-entity (first (filter #(= "my-page" (:seon.fn/name %))
                                   (::extract/functions graph)))]
      (is (some? fn-entity))
      (is (true? (:seon.fn/page-renderer? fn-entity)))
      (is (true? (:seon.fn/needs-ctx? fn-entity)))
      (is (nil? (:seon.fn/needs-conn? fn-entity)))
      (is (seq (:seon.fn/render-input-keys fn-entity))))))

;;; ---------------------------------------------------------------------------
;;; Var Extraction
;;; ---------------------------------------------------------------------------

(deftest extract-graph-vars-test
  (testing "extracts def vars with value types from edamame"
    (let [source "(ns seon.example
  (:require [seon.schema :as schema]))

(def my-vec [1 2 3])
(def my-map {:a 1})
(def my-str \"hello\")"
          graph (extract/extract-graph {::extract/source source})
          vars (::extract/vars graph)
          by-name (into {} (map (juxt :seon.var/name identity)) vars)]
      (is (= :vector (:seon.var/value-type (get by-name "my-vec"))))
      (is (= :map (:seon.var/value-type (get by-name "my-map"))))
      (is (= :string (:seon.var/value-type (get by-name "my-str")))))))

;;; ---------------------------------------------------------------------------
;;; Spec Cross-References
;;; ---------------------------------------------------------------------------

(deftest extract-graph-spec-references-test
  (testing "specs get cross-reference keywords extracted"
    (let [source "(ns seon.example
  (:require [seon.schema :as schema]))

(schema/register! ::my-spec
                  [:map [:seon.other/foo :string]
                        [:seon.other/bar :int]])"
          graph (extract/extract-graph {::extract/source source})
          spec (first (::extract/specs graph))]
      (is (some? spec))
      ;; :seon.other/foo and :seon.other/bar are cross-references
      ;; (not in contains-keys since they're the map keys themselves,
      ;;  but they ARE in references since they're qualified keywords in the form)
      ;; The references should include qualified keywords found in the definition
      ;; that aren't the spec's own key
      (is (vector? (:seon.spec/references spec))))))

;;; ---------------------------------------------------------------------------
;;; File-Based Extraction
;;; ---------------------------------------------------------------------------

(deftest extract-graph-from-file-test
  (testing "extracts graph from workout/render.clj on disk"
    (let [graph (extract/extract-graph-from-file
                 {::extract/file-path "src/seon/health/workout/render.clj"})]
      (is (= "seon.health.workout.render" (::extract/ns-name graph)))
      (is (seq (::extract/functions graph)))
      (is (seq (::extract/specs graph)))
      (is (seq (::extract/call-edges graph)))
      (is (seq (::extract/ns-deps graph)))

      ;; Should have page-render function with spec links
      (let [page-fn (first (filter #(= "page-render" (:seon.fn/name %))
                                   (::extract/functions graph)))]
        (is (some? page-fn) "Should find page-render")
        (is (true? (:seon.fn/page-renderer? page-fn)))
        (is (some? (:seon.fn/input-spec page-fn)))
        (is (some? (:seon.fn/output-spec page-fn)))))))

;;; ---------------------------------------------------------------------------
;;; Cross-Namespace Resolution
;;; ---------------------------------------------------------------------------

(deftest extract-graph-cross-ns-test
  (testing "clj-kondo resolves cross-namespace calls and keywords"
    (let [source "(ns seon.example.consumer
  (:require [seon.health.workout :as workout]))

(defn my-fn [{::keys [data]}]
  (str ::workout/exercise))"
          graph (extract/extract-graph {::extract/source source
                                        ::extract/file-path "<test>"})]
      ;; NS deps should include seon.health.workout
      (is (some #(= "seon.health.workout" (:seon.ns.dep/to-ns %))
                (::extract/ns-deps graph)))

      ;; Call edges should reference resolved namespaces
      (is (seq (::extract/call-edges graph))))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.graph.extract-test)
  nil)
