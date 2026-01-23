(ns seon.ns.introspect
  "Generic namespace introspection at runtime.

   All introspection is RUNTIME - nothing is hardcoded. Discovers:
   - Functions (public fns with arglists)
   - Vars (non-fn, non-atom public vars)
   - Atoms (clojure.lang.IAtom instances)
   - Multimethods (MultiFn instances with dispatch info)
   - Requires (namespace aliases)

   Example:
     (introspect 'seon.ai.claude)
     ;; => {:ns-name seon.ai.claude
     ;;     :doc \"Claude Code provider...\"
     ;;     :functions [{:name launch-agent! :arglists ([{...}]) :doc \"...\"}]
     ;;     :vars [{:name default-model :value \"claude-opus-4-5\"}]
     ;;     :atoms [{:name agent-registry :atom #<Atom@...>}]
     ;;     :requires {ai seon.ai, log taoensso.timbre}}"
  (:require [clojure.string :as str]))

(defn introspect
  "Introspect ANY loaded namespace at runtime.

   Returns nil if namespace not found, otherwise a map with:
   - :ns-name - The namespace symbol
   - :doc - Namespace docstring (if any)
   - :functions - Vector of public functions with metadata
   - :vars - Vector of non-fn, non-atom public vars
   - :atoms - Vector of IAtom instances
   - :multimethods - Vector of MultiFn instances with dispatch info
   - :requires - Map of alias -> namespace"
  [ns-sym]
  (when-let [ns (find-ns ns-sym)]
    (let [publics (ns-publics ns)
          bound-publics (filter (fn [[_ v]] (bound? v)) publics)]
      {:ns-name ns-sym
       :doc (-> ns meta :doc)
       :functions (->> bound-publics
                       (filter (fn [[_ v]]
                                 (let [val (var-get v)]
                                   (and (fn? val)
                                        (not (instance? clojure.lang.MultiFn val))))))
                       (map (fn [[k v]]
                              {:name k
                               :arglists (:arglists (meta v))
                               :doc (:doc (meta v))
                               :private? (:private (meta v))}))
                       (sort-by :name)
                       vec)
       :multimethods (->> bound-publics
                          (filter (fn [[_ v]]
                                    (instance? clojure.lang.MultiFn (var-get v))))
                          (map (fn [[k v]]
                                 (let [mm (var-get v)]
                                   {:name k
                                    :doc (:doc (meta v))
                                    :dispatch-fn (str (.dispatchFn mm))
                                    :method-count (count (methods mm))})))
                          (sort-by :name)
                          vec)
       :vars (->> bound-publics
                  (filter (fn [[_ v]]
                            (let [val (var-get v)]
                              (and (not (fn? val))
                                   (not (instance? clojure.lang.IAtom val))))))
                  (map (fn [[k v]]
                         {:name k
                          :value (try
                                   (var-get v)
                                   (catch Exception e
                                     (str "<error: " (.getMessage e) ">")))}))
                  (sort-by :name)
                  vec)
       :atoms (->> bound-publics
                   (filter (fn [[_ v]]
                             (instance? clojure.lang.IAtom (var-get v))))
                   (map (fn [[k v]]
                          {:name k
                           :atom v}))
                   (sort-by :name)
                   vec)
       :requires (into {} (ns-aliases ns))})))

(defn list-seon-namespaces
  "List all loaded seon.* namespaces, sorted alphabetically."
  []
  (->> (all-ns)
       (filter #(str/starts-with? (str (ns-name %)) "seon."))
       (map ns-name)
       (sort)
       (vec)))

(comment
  ;; REPL exploration

  (introspect 'seon.ai.claude)
  ;; => {:ns-name seon.ai.claude
  ;;     :functions [{:name agents ...} {:name interrupt! ...} ...]
  ;;     :multimethods []
  ;;     :vars [{:name agent-instructions-path ...}]
  ;;     :atoms []
  ;;     :requires {ai seon.ai ...}}

  (introspect 'seon.ai.agent)
  ;; Shows agent-registry atom

  (introspect 'nonexistent.ns)
  ;; => nil

  (list-seon-namespaces)
  ;; => [seon.ai seon.ai.agent seon.ai.claude ...]

  nil)
