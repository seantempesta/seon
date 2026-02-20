(ns seon.flow.harness.proxy
  "Proxy namespace generation for transparent cross-namespace calls.

   Creates proxy namespaces in the agent JVM so that agent code can call
   remote functions with normal Clojure syntax:

     (require '[seon.health.nutrition :as nutrition])
     (nutrition/metabolic-rate)  ; transparently routed to remote namespace

   The proxy functions use `bridge/remote-call!` to send requests through
   the reverse channel to the orchestrator, which routes them to the
   target namespace."
  (:require [clojure.tools.logging :as log]
            [seon.flow.harness.bridge :as bridge]
            [seon.flow.msg :as msg]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::target-ns
  [:string {:min 1 :description "Target namespace to create proxy for"}])

(schema/register! ::from-ns
  [:string {:min 1 :description "This agent's namespace (caller side)"}])

(schema/register! ::request-ch
  [:any {:description "core.async channel for sending reverse requests"}])

(schema/register! ::fn-name
  [:string {:min 1 :description "Function name in the target namespace"}])

(schema/register! ::fn-meta
  [:map {:description "Metadata about the remote function"}
   [::arglists {:optional true} :any]
   [::doc {:optional true} :string]
   [::remote-fn {:optional true} [:string {:min 1
                                           :description "Override fully-qualified fn name for remote call"}]]])

(schema/register! ::functions
  [:map-of :string ::fn-meta
   {:description "Map of function-name -> metadata for the remote namespace"}])

;;; ---------------------------------------------------------------------------
;;; Proxy Function Construction
;;; ---------------------------------------------------------------------------

(defn proxy-fn
  "Create a proxy function that routes calls through the reverse channel.

   Request keys:
     ::request-ch - Channel for sending requests to orchestrator
     ::from-ns    - This agent's namespace (from-ns)
     ::target-ns  - Target remote namespace
     ::fn-name    - Function name in target namespace

   Returns a function that, when called, sends a remote-call! request
   and blocks until the reply arrives."
  [{::keys [request-ch from-ns target-ns fn-name fn-meta]}]
  (let [from-ns (or from-ns "unknown")
        fq-fn   (or (::remote-fn fn-meta) (str target-ns "/" fn-name))]
    (fn proxy-call [& args]
      (log/debug "Proxy call" {:fn fq-fn :from-ns from-ns :to-ns target-ns :args-count (count args) :event :start})
      (let [start-ms (System/currentTimeMillis)
            result (bridge/remote-call!
                    {::bridge/request-ch request-ch
                     ::bridge/remote-call-timeout-ms 10000
                     ::msg/to-ns   target-ns
                     ::msg/fn      fq-fn
                     ::msg/args    (vec args)
                     ::msg/from-ns from-ns})
            elapsed (- (System/currentTimeMillis) start-ms)]
        (log/debug "Proxy call ok" {:fn fq-fn :from-ns from-ns :to-ns target-ns :elapsed-ms elapsed :event :end})
        result))))

(defn proxy-ns!
  "Create a proxy namespace in this JVM with proxy functions for all listed fns.

   Request keys:
     ::target-ns  - Target namespace string to create proxy for
     ::functions  - Map of fn-name-string -> metadata map
     ::request-ch - Channel for sending requests to orchestrator
     ::from-ns    - This agent's namespace string (from-ns)

   Creates the namespace if it doesn't exist, then interns a var for each
   function that delegates to remote-call! through the reverse channel.

   Returns the created namespace object."
  [{::keys [target-ns functions request-ch from-ns]}]
  (log/info "Creating proxy namespace" {:target-ns target-ns :from-ns from-ns :fn-count (count functions)})
  (let [ns-sym  (symbol target-ns)
        the-ns  (create-ns ns-sym)]
    (doseq [[fn-name-str fn-meta] functions]
      (let [fn-sym   (symbol fn-name-str)
            pfn      (proxy-fn {::request-ch request-ch
                                ::from-ns    from-ns
                                ::target-ns  target-ns
                                ::fn-name    fn-name-str
                                ::fn-meta    fn-meta})
            var-meta (cond-> {:name fn-sym
                              :ns the-ns
                              ::proxy? true}
                       (::doc fn-meta)
                       (assoc :doc (str "[proxy] " (::doc fn-meta)))
                       (::arglists fn-meta)
                       (assoc :arglists (::arglists fn-meta)))]
        (intern the-ns (with-meta fn-sym var-meta) pfn)))
    the-ns))
