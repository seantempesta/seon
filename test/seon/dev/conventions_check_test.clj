(ns seon.dev.conventions-check-test
  "Phase 3 convention meta-test.

   Asserts that the namespaces touched by the datahike migration follow
   the project's `:malli/schema` + map-in conventions. Framework callbacks
   with externally-fixed signatures (Integrant `init!`, clj-reload
   `after-ns-reload`, core.async.flow process step-fns) are allowlisted —
   they cannot be map-in by contract.

   When a violation appears here, the right move is usually to fix the
   namespace, not extend the allowlist. Add to the allowlist only when
   the function's shape is dictated by an external framework."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.dev.compliance :as compliance]))

(def ^:private covered-namespaces
  "Namespaces created or substantially rewritten during Phase 3 of the
   datahike migration. These are the surfaces we want to keep clean."
  '[seon.session
    seon.flow.status
    seon.db.relay])

(def ^:private framework-callback-allowlist
  "Function names whose signatures are dictated by an external framework
   and therefore cannot follow the map-in convention."
  #{;; Integrant lifecycle: receives positional args + & {:keys [...]}
    "init!"
    ;; clj-reload reload hook: zero-args callback
    "after-ns-reload"
    "before-ns-unload"
    ;; core.async.flow step-fn: multi-arity (describe / init / transition / transform)
    "collector-step"
    ;; nREPL wire-shaped entry point: bin/mcp-server emits a literal
    ;; positional form `(with-agent-load-string "<id>" "<code>")` over the
    ;; nREPL channel for the :seon.agent/<id> eval route. The two-positional
    ;; signature is dictated by that wire form (a string body that must be
    ;; load-string'd inside the binding scope, so it can't be a map arg).
    "with-agent-load-string"})

(defn- non-allowlisted-violations
  "Filter compliance violations to drop those for allowlisted framework
   callbacks. Returns a vector of violation maps."
  [violations]
  (vec (remove (fn [v]
                 (contains? framework-callback-allowlist
                            (::compliance/fn-name v)))
               violations)))

(deftest phase-3-namespaces-follow-conventions
  (testing "every Phase-3 namespace passes compliance after allowlisting framework callbacks"
    (doseq [ns-sym covered-namespaces]
      (testing (str ns-sym)
        (let [result (compliance/analyze-namespace
                      {::compliance/namespace ns-sym})
              all-violations (::compliance/violations result)
              real-violations (non-allowlisted-violations all-violations)]
          (is (empty? real-violations)
              (str ns-sym " has unallowed convention violations: "
                   (pr-str (mapv (juxt ::compliance/fn-name
                                       ::compliance/violation-type)
                                 real-violations)))))))))

(def ^:private unnamespaced-schema-allowlist
  "Schema keys registered without a namespace that are intentional or
   pre-existing. New registrations should always be namespaced.

   - `:inst` is registered by seon.schema itself as a built-in keyword
     type alias for `inst?`.
   - `:age` and `:name` come from `seon.db-test` fixtures — convention
     violation in test code; flagged as a smell but not in step-12 scope."
  #{:inst :age :name})

(deftest schema-keys-are-namespaced
  (testing "every key registered via seon.schema/register! is namespaced"
    (require 'seon.schema)
    (let [registered ((requiring-resolve 'seon.schema/registered-schemas))
          unnamespaced (filterv (fn [k]
                                  (and (keyword? k)
                                       (nil? (namespace k))
                                       (not (contains? unnamespaced-schema-allowlist k))))
                                (keys registered))]
      (is (empty? unnamespaced)
          (str "Found unnamespaced schema keys not on the allowlist: "
               (pr-str unnamespaced))))))

(deftest no-new-defonce-atoms-in-migrated-namespaces
  (testing "namespaces that completed the atoms→flow migration carry no defonce atoms"
    (let [migrated->files
          {'seon.flow.status            "src/seon/flow/status.clj"
           'seon.session                "src/seon/session.clj"}
          ;; Allowlist patterns that are NOT app state (per-JVM caches,
          ;; resource maps, etc.). Keep this list short and justified.
          allowed-defonces
          {'seon.session              #{"reserved-ports"   ; port-allocation cache
                                        "live-sessions"    ; per-JVM JVM handles
                                        "checkpoint-scheduler" ; daemon executor
                                        "live-state"        ; per-JVM JVM handles
                                        "agent-pool"}       ; Integrant ref
           'seon.flow.status          #{}}]
      (doseq [[ns-sym path] migrated->files]
        (testing (str ns-sym)
          (let [src      (slurp path)
                ;; Find every (defonce <name> ...) form
                defonces (->> (re-seq #"\(defonce\s+(?:\^[^\s]+\s+)*([A-Za-z*!?-][^\s]*)"
                                      src)
                              (mapv second))
                allowed  (get allowed-defonces ns-sym #{})
                surplus  (vec (remove allowed defonces))]
            (is (empty? surplus)
                (str ns-sym " has new defonces not on the allowlist: "
                     (pr-str surplus)))))))))
