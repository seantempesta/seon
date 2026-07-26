(ns seon.sci.ctx
  "Build one shared SCI base and fork it for each evaluation."
  (:require [clojure.string :as str]
            [sci.core :as sci]
            [sci.interrupt :as sci.interrupt]
            [seon.agent.lifecycle :as lifecycle]
            [seon.effect :as effect]
            [seon.schema :as schema]))

(schema/register! ::ctx 'some?)
(schema/register! ::base-ctx 'some?)
(schema/register! ::fork-request
                  [:map {:closed true}
                   [::base-ctx {:optional true} ::base-ctx]
                   [:interrupt-fn 'fn?]])

(def ^:private core-base
  "The process-shared SCI context without database-derived tools."
  (delay
    (let [lifecycle-ns (sci/create-ns 'seon.agent.lifecycle)]
      (sci/init
       {:namespaces
        {'clojure.core sci.interrupt/clojure-core
         'clojure.string sci.interrupt/clojure-string
         'seon.agent.lifecycle
         {'wait (sci/copy-var lifecycle/wait lifecycle-ns)
          'complete (sci/copy-var lifecycle/complete lifecycle-ns)
          'pause (sci/copy-var lifecycle/pause lifecycle-ns)
          'resume (sci/copy-var lifecycle/resume lifecycle-ns)
          'terminate (sci/copy-var lifecycle/terminate lifecycle-ns)}}
        ;; SCI already exposes Exception. Keep the additional JVM surface to
        ;; the two broad roots instead of enumerating exception subclasses.
        :classes
        {'Throwable Throwable
         'java.lang.Throwable Throwable
         'Error Error
         'java.lang.Error Error}}))))

(defn- my-function-symbol?
  [function-symbol]
  (let [namespace-name (some-> function-symbol symbol namespace)]
    (and namespace-name
         (str/starts-with? namespace-name "my."))))

(defn- tool-binding
  [request-context function-symbol]
  (when-let [host-var (requiring-resolve (symbol function-symbol))]
    (with-meta
      (fn [& args]
        (binding [effect/*request-context* request-context]
          (apply @host-var args)))
      (meta host-var))))

(defn- tool-namespaces
  [program-functions request-context]
  (reduce
   (fn [namespaces function-symbol]
     (if (my-function-symbol? function-symbol)
       (if-let [binding (tool-binding request-context function-symbol)]
         (let [qualified (symbol function-symbol)
               namespace-symbol (symbol (namespace qualified))
               function-name (symbol (name qualified))]
           (assoc-in namespaces
                     [namespace-symbol function-name]
                     binding))
         namespaces)
       namespaces))
   {}
   program-functions))

(defn base
  "Build the SCI base from current program-graph function facts."
  [{::keys [program-functions request-context]}]
  (sci/merge-opts
   (sci/fork @core-base)
   {:namespaces (tool-namespaces program-functions request-context)}))

(defn fork
  "Fork the shared `ctx` and install one evaluation's `:interrupt-fn`."
  {:malli/schema [:=> [:cat ::fork-request] ::ctx]}
  [{::keys [base-ctx] :keys [interrupt-fn]}]
  (assoc (sci/fork (or base-ctx
                       (base {::program-functions []
                              ::request-context {}})))
         :interrupt-fn interrupt-fn))
