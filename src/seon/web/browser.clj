(ns seon.web.browser
  "REPL-to-browser execution bridge.

   Execute JavaScript or ClojureScript in connected browsers and get results
   back as Clojure data. Useful for testing without burning context on
   Chrome automation tools.

   Architecture:
   1. REPL calls (browser/eval! 'seon.web.reactive.demo \"document.title\")
   2. Server sends custom SSE event to connected clients
   3. Browser executes JS, POSTs result back to /api/browser/result
   4. Server delivers result to waiting promise
   5. REPL receives structured result map

   Usage:
     (require '[seon.web.browser :as browser])

     ;; Execute JavaScript - returns structured result
     (browser/eval! 'seon.web.reactive.demo \"document.title\")
     ;; => {::browser/success true
     ;;     ::browser/value \"Reactive UI Demo\"
     ;;     ::browser/value-type :string
     ;;     ::browser/duration-ms 2
     ;;     ...}

     ;; Check connected clients
     (browser/connected? 'seon.web.reactive.demo)
     ;; => true"
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [org.httpkit.server :as hk]
            [seon.ctx :as ctx]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema
;;; ---------------------------------------------------------------------------

(def EvalResult
  "Schema for browser eval results."
  [:map
   [::success :boolean]
   [::exec-id :string]
   [::duration-ms :int]
   [::timestamp inst?]
   [::value {:optional true} :string]
   [::value-type {:optional true} [:enum :string :number :boolean :null :undefined :object :array]]
   [::error {:optional true} :string]
   [::error-type {:optional true} :string]])

;;; ---------------------------------------------------------------------------
;;; Pending Evals Registry
;;; ---------------------------------------------------------------------------

;; Map of exec-id -> {:promise p :started-at inst :ns-sym sym}
(defonce ^:private pending-evals (atom {}))

;; Cleanup old pending evals (after 60s they're definitely timed out)
(defn- cleanup-stale-evals!
  "Remove pending evals older than 60 seconds."
  []
  (let [now (System/currentTimeMillis)
        stale-threshold (* 60 1000)]
    (swap! pending-evals
           (fn [m]
             (into {}
                   (filter (fn [[_ {:keys [started-at]}]]
                             (< (- now started-at) stale-threshold)))
                   m)))))

;;; ---------------------------------------------------------------------------
;;; SSE Event Formatting
;;; ---------------------------------------------------------------------------

(defn- escape-js-string
  "Escape a string for inclusion in JavaScript string literal."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

(defn- format-eval-event
  "Format SSE event that injects a script element to execute code.

   Uses datastar-patch-elements to inject a hidden div containing a script.
   Datastar automatically executes scripts in patched content.

   The script:
   1. Executes the provided JavaScript code
   2. POSTs the result back to /api/browser/result with timing and type info
   3. Removes itself from the DOM"
  [exec-id js-code]
  (let [escaped-code (escape-js-string js-code)
        ;; Script that executes code and reports result with timing/type
        script-content (str
                        "(function(){"
                        "var execId=\"" exec-id "\";"
                        "var code=\"" escaped-code "\";"
                        "var payload;"
                        "var startTime=performance.now();"
                        "try{"
                        "var result=(1,eval)(code);"
                        "var endTime=performance.now();"
                        "var resultStr;"
                        "var resultType;"
                        "if(result===undefined){resultStr='undefined';resultType='undefined';}"
                        "else if(result===null){resultStr='null';resultType='null';}"
                        "else if(Array.isArray(result)){resultType='array';try{resultStr=JSON.stringify(result);}catch(e){resultStr=String(result);}}"
                        "else if(typeof result==='object'){resultType='object';try{resultStr=JSON.stringify(result);}catch(e){resultStr=String(result);}}"
                        "else{resultType=typeof result;resultStr=String(result);}"
                        "payload={id:execId,result:resultStr,type:resultType,durationMs:Math.round(endTime-startTime)};"
                        "}catch(e){"
                        "var endTime=performance.now();"
                        "payload={id:execId,error:e.message||String(e),errorType:e.name||'Error',durationMs:Math.round(endTime-startTime)};"
                        "}"
                        "fetch('/api/browser/result',{"
                        "method:'POST',"
                        "headers:{'Content-Type':'application/json'},"
                        "body:JSON.stringify(payload)"
                        "});"
                        "var el=document.getElementById('seon-eval-'+execId);"
                        "if(el)el.remove();"
                        "})();")
        ;; Hidden container with script
        html-content (str "<div id=\"seon-eval-" exec-id "\" style=\"display:none\">"
                          "<script>" script-content "</script>"
                          "</div>")]
    ;; Use datastar-patch-elements with append mode to body
    (str "event: datastar-patch-elements\n"
         "data: selector body\n"
         "data: mode append\n"
         "data: elements " (str/replace html-content "\n" "\ndata: elements ") "\n"
         "\n\n")))

(defn- send-to-client!
  "Send SSE event to a client channel.

   IMPORTANT: Send raw SSE string, not response map.
   Headers were sent when connection opened."
  [channel event-str]
  (try
    (when (hk/open? channel)
      (hk/send! channel event-str false)
      true)
    (catch Exception e
      (log/warn "Failed to send to client" {:error (.getMessage e)})
      false)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn connected?
  "Check if there are any connected browser clients for a namespace."
  [ns-sym]
  (pos? (ctx/client-count-for-namespace ns-sym)))

(defn clients
  "Get connected client channels for a namespace."
  [ns-sym]
  (ctx/clients-for-namespace ns-sym))

(defn- js-type->keyword
  "Convert JavaScript type string to keyword."
  [type-str]
  (case type-str
    "string" :string
    "number" :number
    "boolean" :boolean
    "null" :null
    "undefined" :undefined
    "object" :object
    "array" :array
    :unknown))

(defn eval!
  "Execute JavaScript in browser, return structured result.

   ns-sym      - Namespace symbol with connected clients (e.g., 'seon.web.reactive.demo)
   js-code     - JavaScript code string to execute

   Options:
     :timeout-ms - Max wait time in milliseconds (default 5000)
     :client     - Specific client channel (default: all connected clients)

   Returns: Map conforming to EvalResult schema:
     {::success true/false
      ::exec-id \"uuid\"
      ::duration-ms 5
      ::timestamp #inst \"...\"
      ::value \"result\"        ; on success
      ::value-type :string      ; :string :number :boolean :null :undefined :object :array
      ::error \"message\"       ; on error
      ::error-type \"TypeError\"} ; on error

   Throws: ExceptionInfo only on timeout or no connected clients.

   Example:
     (eval! 'seon.web.reactive.demo \"document.title\")
     ;; => {::success true
     ;;     ::value \"Reactive UI Demo\"
     ;;     ::value-type :string
     ;;     ::duration-ms 2
     ;;     ...}

     (eval! 'seon.web.reactive.demo \"badVar\")
     ;; => {::success false
     ;;     ::error \"badVar is not defined\"
     ;;     ::error-type \"ReferenceError\"
     ;;     ...}"
  [ns-sym js-code & {:keys [timeout-ms client] :or {timeout-ms 5000}}]
  (cleanup-stale-evals!)

  ;; Check for connected clients
  (let [channels (if client
                   #{client}
                   (or (clients ns-sym) #{}))]
    (when (empty? channels)
      (throw (ex-info "No connected browser clients"
                      {:ns ns-sym
                       :hint "Open the page in a browser first"})))

    ;; Create pending eval
    (let [exec-id (str (random-uuid))
          p (promise)
          timestamp (java.util.Date.)]
      (swap! pending-evals assoc exec-id
             {:promise p
              :started-at (System/currentTimeMillis)
              :ns-sym ns-sym})

      ;; Send to client(s)
      (let [event-str (format-eval-event exec-id js-code)
            sent-count (count (filter true? (map #(send-to-client! % event-str) channels)))]
        (log/debug "Sent eval request" {:exec-id exec-id :sent-to sent-count})

        (when (zero? sent-count)
          (swap! pending-evals dissoc exec-id)
          (throw (ex-info "Failed to send to any client"
                          {:ns ns-sym :channels (count channels)}))))

      ;; Wait for result
      (let [result (deref p timeout-ms ::timeout)]
        (swap! pending-evals dissoc exec-id)
        (cond
          (= result ::timeout)
          (throw (ex-info "Browser eval timeout"
                          {:ns ns-sym
                           :code (subs js-code 0 (min 100 (count js-code)))
                           :timeout-ms timeout-ms}))

          (:error result)
          {::success false
           ::exec-id exec-id
           ::duration-ms (or (:durationMs result) 0)
           ::timestamp timestamp
           ::error (:error result)
           ::error-type (or (:errorType result) "Error")}

          :else
          {::success true
           ::exec-id exec-id
           ::duration-ms (or (:durationMs result) 0)
           ::timestamp timestamp
           ::value (:result result)
           ::value-type (js-type->keyword (:type result))})))))

(defn cljs!
  "Execute ClojureScript in browser via Scittle, return structured result.

   ns-sym      - Namespace symbol with connected clients (e.g., 'seon.web.reactive.demo)
   cljs-form   - ClojureScript form to evaluate (will be converted to string via pr-str)

   Options:
     :timeout-ms - Max wait time in milliseconds (default 5000)
     :client     - Specific client channel (default: all connected clients)

   Returns: Map conforming to EvalResult schema with ::value containing
            the pr-str'd result (parseable as EDN).

   Throws: ExceptionInfo on timeout or no connected clients.

   Requires Scittle to be loaded in the browser (included in reactive demo pages).

   Example:
     (cljs! 'seon.web.reactive.demo '(+ 1 2 3))
     ;; => {::success true ::value \"6\" ...}

     (cljs! 'seon.web.reactive.demo '(mapv inc [1 2 3]))
     ;; => {::success true ::value \"[2 3 4]\" ...}  ; parseable as EDN"
  [ns-sym cljs-form & {:keys [timeout-ms] :or {timeout-ms 5000} :as opts}]
  (let [;; Wrap form in pr-str so we get EDN back
        wrapped-form `(pr-str ~cljs-form)
        cljs-str (pr-str wrapped-form)
        ;; Escape the ClojureScript string for embedding in JS string literal
        escaped-cljs (escape-js-string cljs-str)
        ;; Generate JS that calls Scittle's eval_string
        ;; scittle.core.eval_string is the global function exposed by Scittle
        js-code (str "scittle.core.eval_string(\"" escaped-cljs "\")")]
    (apply eval! ns-sym js-code (flatten (seq opts)))))

(defn eval!!
  "Execute JavaScript in browser, parse JSON result to Clojure data.

   Like eval! but extracts ::value and parses JSON:
   - JSON objects -> Clojure maps (with keyword keys)
   - JSON arrays -> Clojure vectors
   - JSON strings/numbers/booleans -> Clojure equivalents
   - 'undefined' -> nil
   - 'null' -> nil

   ns-sym      - Namespace symbol with connected clients
   js-code     - JavaScript code string to execute

   Options:
     :timeout-ms - Max wait time in milliseconds (default 5000)
     :client     - Specific client channel

   Returns: Parsed Clojure data, or nil on error
   Throws: ExceptionInfo on timeout or no clients

   Example:
     (eval!! 'seon.web.reactive.demo \"[1, 2, 3]\")
     ;; => [1 2 3]

     (eval!! 'seon.web.reactive.demo \"{foo: 'bar'}\")
     ;; => {:foo \"bar\"}"
  [ns-sym js-code & opts]
  (let [result (apply eval! ns-sym js-code opts)]
    (if (::success result)
      (let [result-str (::value result)]
        (cond
          (= result-str "undefined") nil
          (= result-str "null") nil
          :else
          (try
            (cheshire.core/parse-string result-str true)
            (catch Exception _
              ;; Not valid JSON - try parsing as primitive
              (cond
                ;; Try parsing as number
                (and result-str (re-matches #"-?\d+\.?\d*" result-str))
                (if (str/includes? result-str ".")
                  (Double/parseDouble result-str)
                  (Long/parseLong result-str))

                ;; Boolean literals
                (= result-str "true") true
                (= result-str "false") false

                ;; Plain string - return as-is
                :else result-str)))))
      ;; On error, return nil (error info is in the result map)
      nil)))

(defn cljs!!
  "Execute ClojureScript in browser via Scittle, parse EDN result.

   Like cljs! but extracts ::value and parses as EDN, returning native Clojure data.

   ns-sym      - Namespace symbol with connected clients
   cljs-form   - ClojureScript form to evaluate

   Options:
     :timeout-ms - Max wait time in milliseconds (default 5000)
     :client     - Specific client channel

   Returns: Parsed Clojure data (via clojure.edn/read-string), or nil on error
   Throws: ExceptionInfo on timeout or no clients

   Example:
     (cljs!! 'seon.web.reactive.demo '(+ 1 2 3))
     ;; => 6

     (cljs!! 'seon.web.reactive.demo '(mapv inc [1 2 3]))
     ;; => [2 3 4]"
  [ns-sym cljs-form & opts]
  (let [result (apply cljs! ns-sym cljs-form opts)]
    (if (::success result)
      (let [result-str (::value result)]
        (cond
          (= result-str "nil") nil
          (= result-str "\"nil\"") nil  ; pr-str wrapped nil
          (nil? result-str) nil
          :else
          (try
            (edn/read-string result-str)
            (catch Exception _
              ;; Not valid EDN - return as string
              result-str))))
      ;; On error, return nil (error info is in the result map)
      nil)))

;;; ---------------------------------------------------------------------------
;;; Error Tracking
;;; ---------------------------------------------------------------------------

(def ^:private error-tracking-js
  "JavaScript code to install error tracking in the browser.
   Captures window.onerror and console.error calls."
  "
(function() {
  if (window.SEON_ERROR_TRACKING_INSTALLED) return 'already_installed';

  window.SEON_ERRORS = [];
  window.SEON_ERROR_TRACKING_INSTALLED = true;

  // Capture uncaught errors
  var originalOnError = window.onerror;
  window.onerror = function(message, source, lineno, colno, error) {
    window.SEON_ERRORS.push({
      type: 'uncaught',
      message: message,
      source: source,
      line: lineno,
      column: colno,
      stack: error && error.stack,
      timestamp: Date.now()
    });
    if (originalOnError) {
      return originalOnError.apply(this, arguments);
    }
    return false;
  };

  // Capture console.error calls
  var originalConsoleError = console.error;
  console.error = function() {
    var args = Array.prototype.slice.call(arguments);
    window.SEON_ERRORS.push({
      type: 'console.error',
      message: args.map(function(a) {
        return typeof a === 'object' ? JSON.stringify(a) : String(a);
      }).join(' '),
      timestamp: Date.now()
    });
    return originalConsoleError.apply(console, arguments);
  };

  // Capture unhandled promise rejections
  window.addEventListener('unhandledrejection', function(event) {
    window.SEON_ERRORS.push({
      type: 'unhandled_rejection',
      message: event.reason ? (event.reason.message || String(event.reason)) : 'Unknown rejection',
      stack: event.reason && event.reason.stack,
      timestamp: Date.now()
    });
  });

  return 'installed';
})();
")

(defn- install-error-tracking!
  "Install error tracking in browser if not already installed.
   Returns true if installed, false if already was installed."
  [ns-sym & opts]
  (let [result (apply eval! ns-sym error-tracking-js opts)]
    (and (::success result)
         (= (::value result) "installed"))))

(defn errors
  "Get console errors from browser.

   Installs error tracking if not already installed, then returns
   all captured errors as a vector of maps.

   Each error map contains:
     :type      - 'uncaught', 'console.error', or 'unhandled_rejection'
     :message   - Error message string
     :timestamp - Unix timestamp in milliseconds
     :source    - (for uncaught) Source file URL
     :line      - (for uncaught) Line number
     :column    - (for uncaught) Column number
     :stack     - (when available) Stack trace string

   Example:
     (errors 'seon.web.reactive.demo)
     ;; => [{:type \"console.error\" :message \"Something went wrong\" :timestamp 1234567890}]"
  [ns-sym & opts]
  ;; Ensure tracking is installed
  (apply install-error-tracking! ns-sym opts)
  ;; Get errors array
  (apply eval!! ns-sym "window.SEON_ERRORS || []" opts))

(defn clear-errors!
  "Clear captured errors in browser.

   Returns the number of errors that were cleared."
  [ns-sym & opts]
  (let [count-before (count (apply errors ns-sym opts))]
    (apply eval! ns-sym "window.SEON_ERRORS = []; 'cleared'" opts)
    count-before))

;;; ---------------------------------------------------------------------------
;;; Result Delivery (called by HTTP handler)
;;; ---------------------------------------------------------------------------

(defn deliver-result!
  "Deliver a result to a waiting eval.

   Called by the /api/browser/result endpoint when browser POSTs back.

   exec-id - The execution ID from the seon-eval event
   result  - Map with either :result or :error key"
  [exec-id result]
  (if-let [{:keys [promise]} (get @pending-evals exec-id)]
    (do
      (deliver promise result)
      (log/debug "Delivered browser result" {:exec-id exec-id :has-error (:error result)})
      true)
    (do
      (log/warn "No pending eval for result" {:exec-id exec-id})
      false)))

;;; ---------------------------------------------------------------------------
;;; HTTP Handler
;;; ---------------------------------------------------------------------------

(defn result-handler
  "Ring handler for POST /api/browser/result.

   Receives results from browser eval execution.

   Request body (JSON, parsed to keywords by middleware):
     {:id \"exec-id\"
      :result \"value\"
      :type \"string\"
      :durationMs 5}       ; on success
   or
     {:id \"exec-id\"
      :error \"message\"
      :errorType \"TypeError\"
      :durationMs 2}       ; on error"
  [request]
  (let [body (:body request)]
    (log/debug "Browser result received" {:body body})
    (if-let [exec-id (:id body)]
      (let [;; Build result map, only including present keys
            base-map (if (:error body)
                       {:error (:error body)}
                       {:result (:result body)})
            ;; Add optional fields if present
            result-map (cond-> base-map
                         (:type body) (assoc :type (:type body))
                         (:errorType body) (assoc :errorType (:errorType body))
                         (:durationMs body) (assoc :durationMs (:durationMs body)))]
        (if (deliver-result! exec-id result-map)
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body "{\"success\":true}"}
          {:status 404
           :headers {"Content-Type" "application/json"}
           :body "{\"success\":false,\"error\":\"No pending eval\"}"}))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body "{\"success\":false,\"error\":\"Missing id\"}"})))

(comment
  ;; Test usage (requires browser connected to /reactive-demo)

  ;; Check if browser is connected
  (connected? 'seon.web.reactive.demo)

  ;; Get page title (JavaScript) - returns structured result
  (eval! 'seon.web.reactive.demo "document.title")
  ;; => {::success true
  ;;     ::value "Reactive UI Demo"
  ;;     ::value-type :string
  ;;     ::duration-ms 1
  ;;     ::exec-id "uuid..."
  ;;     ::timestamp #inst "..."}

  ;; Get counter value (JavaScript)
  (eval! 'seon.web.reactive.demo
         "document.querySelector('#span-count').textContent")

  ;; Get all item names as parsed JSON
  (eval!! 'seon.web.reactive.demo
          "Array.from(document.querySelectorAll('#list-items li span')).map(e => e.textContent)")
  ;; => ["Item 1" "Item 2" ...]

  ;; Test Scittle is loaded
  (eval! 'seon.web.reactive.demo "typeof scittle")
  ;; => {::success true ::value "object" ::value-type :string ...}

  ;; Execute ClojureScript (via Scittle) - returns structured result
  (cljs! 'seon.web.reactive.demo '(+ 1 2 3))
  ;; => {::success true ::value "6" ...}

  ;; ClojureScript with parsed EDN result
  (cljs!! 'seon.web.reactive.demo '(mapv inc [1 2 3]))
  ;; => [2 3 4]

  ;; DOM access via ClojureScript
  (cljs!! 'seon.web.reactive.demo
          '(.-textContent (js/document.querySelector "#span-count")))
  ;; => "5"

  ;; Error handling - no exception, just structured error
  (eval! 'seon.web.reactive.demo "unknownVar")
  ;; => {::success false
  ;;     ::error "unknownVar is not defined"
  ;;     ::error-type "ReferenceError"
  ;;     ::duration-ms 0
  ;;     ...}
  )

