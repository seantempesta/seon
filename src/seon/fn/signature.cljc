(ns seon.fn.signature
  "Exact source signatures for the P12 source-to-contract join."
  #?(:cljs (:require [cljs.reader :as reader]))
  #?(:clj (:import (clojure.lang LispReader$Resolver)
                   (java.io PushbackReader StringReader))))

(defn- refused!
  [reason data]
  (throw
   (ex-info "Function source and Malli contract do not join bijectively."
            (merge {:seon.error/kind :seon.fn/signature-refused
                    :seon.fn.signature/reason reason :seon.fn/signature-refused true}
                   data))))

#?(:clj
   (defn- read-source
     [source namespace-name aliases]
     (binding [*read-eval* false
               *reader-resolver*
               (reify LispReader$Resolver
                 (currentNS [_] (or namespace-name 'user))
                 (resolveClass [_ sym] sym)
                 (resolveAlias [_ sym] (get aliases sym sym))
                 (resolveVar [_ sym] sym))]
       (read {:read-cond :allow :features #{:clj}}
             (PushbackReader. (StringReader. source)))))
   :cljs
   (defn- read-source
     [source _namespace-name _aliases]
     (reader/read-string source)))

(defn- core-defn?
  [operation]
  (and (symbol? operation)
       (contains? #{"defn" "defn-"} (name operation))
       (contains? #{nil "clojure.core"} (namespace operation))))

(defn- declaration-parts
  [form]
  (when-not (and (seq? form)
                 (core-defn? (first form))
                 (symbol? (second form)))
    (refused! :unsupported-declaration
              {:seon.fn.signature/form (pr-str form)}))
  (let [after-name (drop 2 form)
        after-doc (if (string? (first after-name)) (next after-name) after-name)
        attributes (if (map? (first after-doc)) (first after-doc) {})
        declarations (if (map? (first after-doc)) (next after-doc) after-doc)
        arglists
        (cond
          (vector? (first declarations)) [(first declarations)]
          (and (seq declarations)
               (every? #(and (seq? %) (vector? (first %))) declarations))
          (mapv first declarations)
          :else
          (refused! :unsupported-declaration
                    {:seon.fn.signature/form (pr-str form)}))
        metadata (merge (meta form) (meta (first form)) (meta (second form))
                        attributes)]
    {:seon.fn.signature/arglists arglists
     :seon.fn.signature/arglists-override?
     (contains? metadata :arglists)}))

(defn- source-signature
  [order bindings]
  (let [ampersands (into [] (keep-indexed #(when (= '& %2) %1)) bindings)]
    (when (> (count ampersands) 1)
      (refused! :multiple-rest-bindings
                {:seon.fn.signature/arglist bindings}))
    (if-let [rest-index (first ampersands)]
      (do
        (when-not (= (+ rest-index 2) (count bindings))
          (refused! :malformed-rest-binding
                    {:seon.fn.signature/arglist bindings}))
        {:seon.fn.signature/order (long order)
         :seon.fn.signature/variadic? true
         :seon.fn.signature/min (long rest-index)
         :seon.fn.signature/rest-index (long rest-index)
         :seon.fn.signature/bindings
         (into (subvec bindings 0 rest-index)
               [(nth bindings (inc rest-index))])})
      {:seon.fn.signature/order (long order)
       :seon.fn.signature/variadic? false
       :seon.fn.signature/min (long (count bindings))
       :seon.fn.signature/max (long (count bindings))
       :seon.fn.signature/bindings bindings})))

(defn function-signatures
  "Exact source signatures with analyzer disagreement refusal."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.fn/source :string]
               [:seon.fn/arglists {:optional true} :string]]]
    :map]}
  [{source :seon.fn/source
    analyzed-arglists :seon.fn/arglists
    namespace-name :seon.fn.signature/namespace
    aliases :seon.fn.signature/aliases}]
  (let [{source-arglists :seon.fn.signature/arglists
         override? :seon.fn.signature/arglists-override?}
        (declaration-parts (read-source source namespace-name aliases))
        analyzed (when analyzed-arglists
                   (read-source analyzed-arglists namespace-name aliases))]
    (when (and analyzed (not= (seq source-arglists) (seq analyzed))
               (not override?))
      (refused! :analyzer-disagreement
                {:seon.fn.signature/source-arglists source-arglists
                 :seon.fn.signature/analyzed-arglists analyzed}))
    {:seon.fn.signature/arglists-override? override?
     :seon.fn.signature/signatures
     (mapv source-signature (range) source-arglists)}))
