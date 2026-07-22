(ns seon.internal-boundary-test
  "The `.internal` boundary — the framework's `*.internal` namespaces hold
   private machinery. The structural `included-ns?` rule excludes every
   `.internal` namespace from rendered namespace sections while including its
   public parent. Stored full source is a separate contract:
   `full-source-ns?` rejects non-`my.*` internals, while `my.*` internals keep
   stored source for SCI lexical-alias reconstruction without prompt rendering."
  (:require
    ["node:fs" :as fs]
    ["node:path" :as np]
    [cljs.test :refer [deftest is]]
    [seon.agent.authorization :as authorization]
    [seon.agent.ctx.ns-name :as ns-name]
    [seon.agent.ctx.namespaces :as ns]
    [seon.config :as config]
    [seon.test.source-scan :as source-scan]))

(defn- declared-ns
  [source]
  (some->> (source-scan/sanitized-ns-form source)
           (re-find #"^\(ns\s+([^\s()]+)")
           second
           symbol))

(def ^:private internal-nses
  (->> (source-scan/source-files (.join np (.cwd js/process) "src"))
       (keep #(declared-ns (.readFileSync fs % "utf-8")))
       (filter #(re-find #"\.internal$" (str %)))
       sort
       vec))

(defn- parent-ns
  [internal-ns]
  (symbol (subs (str internal-ns) 0 (- (count (str internal-ns))
                                        (count ".internal")))))

(def ^:private configuration
  (config/resolve-config-singleton {}))

(deftest internal-nses-store-source-only-when-sci-reconstruction-needs-it
  (doseq [internal internal-nses]
    (if (ns/my-ns-name? internal)
      (do
        (is (true? (ns/full-source-ns? configuration internal))
            (str internal " keeps source for SCI lexical-alias reconstruction"))
        (is (false? (ns-name/included-ns? internal))
            (str internal " keeps source without entering rendered sections")))
      (do
        (is (false? (ns/full-source-ns? configuration internal))
            (str internal " does not store full source"))
        (is (false? (ns/full-source-ns? configuration (str (name internal))))
            (str "full-source-ns? rejects the string form of " internal " too"))))))

(deftest included-ns-excludes-internal-keeps-the-public-parent
  ;; The structural agent-prompt selection rule: .internal is filtered out by
  ;; the suffix alone, while the public parent renders. Falsifies a hollow
  ;; "always false" check by asserting the parent IS included.
  (doseq [internal internal-nses
          :let [parent (parent-ns internal)]]
    (is (false? (ns-name/included-ns? internal))
        (str internal " is excluded from the agent prompt (.internal suffix)"))
    (is (true? (ns-name/included-ns? parent))
        (str "the PUBLIC parent " parent " IS included — the boundary is the "
             "suffix, not a blanket exclusion")))
  ;; The source-string boundary and canonical symbol agree.
  (is (false? (ns-name/included-ns? "seon.db.internal")))
  (is (false? (ns-name/included-ns? 'seon.db.internal))))

(deftest agent-management-is-one-pure-rule-over-a-pulled-parent-tree
  (let [tree {:seon.agent/id "child"
              :seon.agent/parent
              {:seon.agent/id "parent"
               :seon.agent/parent {:seon.agent/id "root"}}}]
    (is (authorization/manages? "child" tree))
    (is (authorization/manages? "parent" tree))
    (is (authorization/manages? "root" tree))
    (is (not (authorization/manages? "other" tree)))
    (is (not (authorization/manages? nil tree)))
    (is (not (authorization/manages? "root" nil)))
    (is (not (authorization/manages?
              "other"
              {:seon.agent/id "cycle-a"
               :seon.agent/parent
               {:seon.agent/id "cycle-a"}})))
    (is (string?
         (:seon.error/message (authorization/no-agent-error "pause"))))
    (is (string?
         (:seon.error/message
          (authorization/unauthorized-target-error
           "pause" "child" "other"))))))
