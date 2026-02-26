(ns seon.web.reactive.actions
  "Action resolution for reactive UI.

   Resolves namespace-qualified function symbols for action execution.
   Security: Only allows calling functions in namespaces under seon.*

   Note: This namespace uses positional arguments rather than map-in/map-out
   because it's a pure resolution library where (resolve-action ns fn)
   is more natural than (resolve-action {::ns ns ::fn fn})."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schemas
;;; ---------------------------------------------------------------------------

(def ActionRequest
  "Schema for incoming action requests."
  [:map
   [:seon.reactive/namespace :symbol]
   [:seon.reactive/function :symbol]
   [:seon.reactive/signals {:optional true} [:map-of :keyword :any]]])

(def ActionResponse
  "Schema for action responses."
  [:map
   [:seon.reactive/success :boolean]
   [:seon.reactive/error {:optional true} :string]])

;;; ---------------------------------------------------------------------------
;;; Function Resolution
;;; ---------------------------------------------------------------------------

(defn- valid-action-namespace?
  "Check if namespace is allowed for action execution.

   Only allows namespaces under seon.* to prevent arbitrary code execution."
  [ns-sym]
  (and (symbol? ns-sym)
       (str/starts-with? (str ns-sym) "seon.")))

(defn resolve-action
  "Resolve a function in a namespace for action execution.

   Returns the var if found and allowed, nil otherwise."
  {:malli/schema [:=> [:cat :symbol :symbol] [:maybe :any]]}
  [ns-sym fn-sym]
  (when (valid-action-namespace? ns-sym)
    (try
      (require ns-sym)
      (when-let [v (ns-resolve ns-sym fn-sym)]
        (when (and (var? v) (fn? @v))
          v))
      (catch Exception e
        (log/warn "Failed to resolve action" {:ns ns-sym :fn fn-sym :error (.getMessage e)})
        nil))))
