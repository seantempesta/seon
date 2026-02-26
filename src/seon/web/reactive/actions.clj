(ns seon.web.reactive.actions
  "Action endpoint for reactive UI.

   Handles POST /action/:namespace/:function requests from Datastar.

   Flow:
   1. Extract namespace and function from URL path
   2. Extract signals from request body (Datastar sends JSON)
   3. Resolve and call the function with signals map
   4. Return 200 (ctx watch handles SSE push separately)

   Security: Only allows calling functions in namespaces under seon.*
   that are explicitly marked as reactive actions."
  (:require [clojure.string :as str]
            [seon.web.reactive.encoding :as encoding]
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
;;; Signal Extraction
;;; ---------------------------------------------------------------------------

(defn extract-signals
  "Extract signals from Datastar request body.

   Delegates to seon.web.reactive.encoding/decode-signals which handles:
   - Nested JSON from dot-notation signals (preserves full keyword identity)
   - Flat camelCase keys (legacy format)

   Example:
     {\"seon\" {\"gettingStarted\" {\"exercise\" \"Pull-up\"}}}
     → {:seon.getting-started/exercise \"Pull-up\"}"
  {:malli/schema [:=> [:cat [:maybe :map]] [:map-of :keyword :any]]}
  [body]
  (log/debug "extract-signals input" {:body body :type (type body)})
  (let [result (encoding/decode-signals body)]
    (log/debug "extract-signals output" {:result result})
    result))

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

;;; ---------------------------------------------------------------------------
;;; Request Handling
;;; ---------------------------------------------------------------------------

(defn parse-action-path
  "Parse /action/:namespace/:function path into components.

   Returns map with :seon.reactive/namespace and :seon.reactive/function
   or nil if path is invalid."
  {:malli/schema [:=> [:cat :string] [:maybe ActionRequest]]}
  [path]
  (when-let [[_ ns-str fn-str] (re-matches #"/action/([^/]+)/([^/]+)" path)]
    {:seon.reactive/namespace (symbol ns-str)
     :seon.reactive/function (symbol fn-str)}))

(defn handle-action
  "Handle an action request.

   request - Ring request map with:
     :uri - Path like /action/seon.trading/create-order
     :body - Parsed JSON body with signals

   Returns Ring response map."
  {:malli/schema [:=> [:cat :map] :map]}
  [request]
  (let [path (:uri request)
        body (:body request)]
    (log/info "ACTION REQUEST" {:path path :body body :body-type (type body)})
    (if-let [{:seon.reactive/keys [namespace function]} (parse-action-path path)]
      (if-let [action-fn (resolve-action namespace function)]
        (try
          (let [signals (extract-signals body)]
            (log/info "Executing action" {:ns namespace :fn function :signals signals})
            (action-fn signals)
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body "{\"success\":true}"})
          (catch Exception e
            (log/error e "Action execution failed" {:ns namespace :fn function})
            {:status 500
             :headers {"Content-Type" "application/json"}
             :body (str "{\"success\":false,\"error\":\"" (.getMessage e) "\"}")}))
        (do
          (log/warn "Action not found" {:ns namespace :fn function})
          {:status 404
           :headers {"Content-Type" "application/json"}
           :body "{\"success\":false,\"error\":\"Action not found\"}"}))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body "{\"success\":false,\"error\":\"Invalid action path\"}"})))

;;; ---------------------------------------------------------------------------
;;; Ring Handler
;;; ---------------------------------------------------------------------------

(defn action-handler
  "Ring handler for action routes.

   Matches POST /action/:namespace/:function requests."
  {:malli/schema [:=> [:cat :map] [:maybe :map]]}
  [request]
  (when (and (= :post (:request-method request))
             (str/starts-with? (or (:uri request) "") "/action/"))
    (handle-action request)))

(comment
  ;; Test parsing
  (parse-action-path "/action/seon.trading/create-order")
  ;; => {:seon.reactive/namespace seon.trading, :seon.reactive/function create-order}

  ;; Test signal extraction
  (extract-signals {"symbol" "AAPL" "quantity" "100"})
  ;; => {:symbol "AAPL", :quantity "100"}
  )
