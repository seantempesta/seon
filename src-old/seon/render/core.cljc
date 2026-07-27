(ns seon.render.core
  "Immutable compiled renderer functions shared by both runtimes.

   Every entry is an ordinary require plus a literal symbol-to-function
  association. This map resolves compiled functions; it does not classify
  trust. The active schema projection derives trust from source transaction
  provenance and exact artifact exports."
  (:require #?(:cljs [goog.object :as gobj])
            [seon.render.canvas :as canvas]
            [seon.render.handlers.eval :as handler-eval]
            [seon.render.handlers.fn :as handler-fn]
            [seon.render.handlers.message :as handler-message]
            [seon.render.handlers.ns :as handler-ns]
            [seon.render.handlers.schema :as handler-schema]
            [seon.render.handlers.test :as handler-test]))

(defn resolve-compiled
  "Resolve one compiled function symbol through the platform's language owner."
  [sym]
  #?(:clj
     (when (qualified-symbol? sym)
       (requiring-resolve sym))
     :cljs
     (when (qualified-symbol? sym)
       (when-let [namespace-object
                  (cljs.core/find-ns-obj (symbol (namespace sym)))]
         (gobj/get namespace-object (cljs.core/munge (name sym)))))))

(def renderer-functions
  "Compiled render-core symbol-to-function resolution."
  {'seon.render.canvas/welcome
   (fn [input] (canvas/welcome input nil))

   'seon.render.handlers.eval/render-ai handler-eval/render-ai
   'seon.render.handlers.eval/render-html handler-eval/render-html
   'seon.render.handlers.fn/render-ai handler-fn/render-ai
   'seon.render.handlers.fn/render-html handler-fn/render-html
   'seon.render.handlers.message/render-ai handler-message/render-ai
   'seon.render.handlers.message/render-html handler-message/render-html
   'seon.render.handlers.ns/render-ai handler-ns/render-ai
   'seon.render.handlers.ns/render-html handler-ns/render-html
   'seon.render.handlers.schema/render-ai handler-schema/render-ai
   'seon.render.handlers.schema/render-html handler-schema/render-html
   'seon.render.handlers.test/render-ai handler-test/render-ai
   'seon.render.handlers.test/render-html handler-test/render-html})
