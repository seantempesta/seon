(ns seon.operator.prepl
  "Core-only PREPL lifecycle handoff for the launched operator JVM."
  (:require [clojure.core.server :as server]
            [clojure.main :as main]))

(defn- failure-text
  "`throwable` as bounded text: the floor once `seon.render.value` is loaded,
  Clojure's own triage line before. A text that itself fails names both classes."
  [^Throwable throwable]
  (try
    (if-let [floor (some-> (find-ns 'seon.render.value) (ns-resolve 'floor))]
      (floor throwable)
      (main/ex-str (main/ex-triage (Throwable->map throwable))))
    (catch Throwable failure
      (str (.getName (class throwable)) " (its text failed: " (.getName (class failure)) ")"))))

(defn- reply
  "The printed line for `event` and whether it asks for a process exit. Total:
  when projecting or printing throws, `:val` becomes a declared error value
  with `:exception true`, phase `:print-eval-result`, as
  `clojure.core.server/io-prepl` answers a throwing valf (server.clj:275-296)."
  [event failure valf cluster-name]
  (binding [*print-length* nil *print-level* nil *print-meta* false *print-readably* true]
    (try
      (let [value (:val event)
            project (or valf (when-let [project (some-> (find-ns 'seon.cluster) (ns-resolve 'mcp-valf))]
                               #(project cluster-name (deref (ns-resolve 'seon.config 'defaults)) %1 %2)))
            exit? (and (= :ret (:tag event)) (map? value) (not (sorted? value))
                       (true? (get value :seon.operator/process-exit?)))]
        [(pr-str (if (#{:ret :tap} (:tag event))
                   ;; A thrown evaluation's projection receives the Throwable
                   ;; itself, not prepl's `Throwable->map` of it, so it can keep
                   ;; the whole cause in the one error shape.
                   (assoc event :val (cond project (project (or failure value) (true? (:exception event)))
                                           failure (pr-str (failure-text failure))
                                           :else (pr-str value)))
                   event))
         exit?])
      (catch Throwable layer
        [(pr-str (assoc (select-keys event [:tag :ns :ms :form])
                        :exception true
                        :val (pr-str {:seon.error/at (java.util.Date.)
                                      :seon.error/layer :seon.operator/prepl
                                      :seon.error/operation 'seon.operator.prepl/io-prepl
                                      :clojure.error/phase :print-eval-result
                                      :seon.error/message
                                      (str "The reply could not be printed.\n" (failure-text layer)
                                           (when failure
                                             (str "\nwhile printing the evaluation's failure:\n"
                                                  (failure-text failure))))})))
         false]))))

(defn evaluate
  "Read `source` with the one reader (`seon.sci.reader/read`) in
  `namespace-symbol`, with that namespace's aliases, Vars, classes and data
  readers, and evaluate its one form there, leaving the session in it. A
  reader refusal is thrown as the reader's own; more or fewer than one form
  refuses with where the second starts.

  The MCP bridge calls this from this namespace (`(do (in-ns
  'seon.operator.prepl) (evaluate …))`, `seon.dev.mcp/jvm-evaluation-form`),
  so every frame between the prepl and the caller's form is the prepl
  wire's, which a fault never names: a compile error has no first-party
  frame and a runtime error's is the caller's form. The bridge's wrapper
  evaluated in `user` named itself (`user$eval… NO_SOURCE_FILE 61`)."
  {:malli/schema [:=> [:cat [:map [:seon.dev.mcp/source :string]
                             [:seon.dev.mcp/namespace :symbol]
                             [:seon.dev.mcp/read-only? :boolean]]]
                  :any]}
  [{source :seon.dev.mcp/source namespace-symbol :seon.dev.mcp/namespace read-only? :seon.dev.mcp/read-only?}]
  ((requiring-resolve 'seon.cluster/project-next-prepl-value!) {:seon.dev.mcp/read-only? read-only?})
  (in-ns namespace-symbol)
  (refer 'clojure.core)
  (let [target *ns*
        events ((requiring-resolve 'seon.sci.reader/read)
                {:seon.sci.reader/text source
                 :seon.sci.reader/ns namespace-symbol
                 :seon.sci.reader/aliases (into {} (map (fn [[alias-name aliased]] [alias-name (ns-name aliased)]))
                                                (ns-aliases target))
                 :seon.sci.reader/refers (into {} (keep (fn [[s v]] (cond (var? v) [s (symbol v)]
                                                                          (class? v) [s (symbol (.getName ^Class v))])))
                                               (ns-map target))
                 :seon.sci.reader/tags *data-readers*
                 :seon.config.eval.result/max-source (count source)})
        failure (if (map? events) events (some :seon.sci.reader/error events))
        second-form (second events)]
    (cond
      failure (throw (ex-info (:seon.error/message failure) failure))
      (= 1 (count events)) (eval (:seon.sci.reader/form (first events)))
      :else (throw (ex-info (if second-form
                              (str "Evaluation accepts exactly one form; the second form starts at line "
                                   (:seon.sci.reader/line second-form) ", column "
                                   (:seon.sci.reader/column second-form) ". Wrap sequences in `(do ...)`.")
                              "Your reply had no form; only comments/prose. Send a form.")
                            (select-keys second-form [:seon.sci.reader/line :seon.sci.reader/column
                                                      :seon.sci.reader/source]))))))

(defn io-prepl
  "Print PREPL events, retaining no evaluation result in *1/*2/*3/*e, then
  perform a requested process exit after the flush. The out-fn never throws:
  `valf` (value, exception?) -> string defaults to the cluster's projection."
  [& {:keys [cluster-name valf]}]
  (let [out *out*
        lock (Object.)]
    (server/prepl
     *in*
     (fn [event]
       (let [ret? (= :ret (:tag event))
             [line exit?] (reply event (when (and ret? (:exception event)) *e) valf cluster-name)]
         ;; Our functions name what they return (`seon.sci.admit/result-handle`,
         ;; blob digests), so no session keeps a result: `prepl` has just
         ;; `set!` *1/*2/*3 or *e on this thread (clojure/core/server.clj:236-238,
         ;; 251, 257) and these clear its `with-bindings` frame again.
         (when ret?
           (set! *1 nil) (set! *2 nil) (set! *3 nil) (set! *e nil))
         (binding [*out* out *flush-on-newline* true]
           (locking lock
             (println line)
             (flush)
             (when exit?
               (.halt (Runtime/getRuntime) 0)))))))))
