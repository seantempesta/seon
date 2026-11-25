(ns seon.git.message
  "Pure merge commit scope inference and conventional commit message rendering for `seon.git`."
  (:require [clojure.string :as str]
            [datahike.api :as d]))

(defn- clean-one-line [s]
  (when (string? s)
    (let [t (str/trim (str/replace s #"\s+" " "))]
      (when-not (str/blank? t) t))))

(defn- safe-bucket [b]
  (let [v (fn [k] (let [x (when (map? b) (get b k))]
                    (if (and (coll? x) (not (map? x))) (vec x) [])))]
    {:added (v :added) :modified (v :modified) :deleted (v :deleted)}))

(defn- format-bucket-counts [{:keys [added modified deleted]} labels?]
  (let [na (count added) nm (count modified) nd (count deleted)]
    (when (pos? (+ na nm nd))
      (if labels?
        (str/join ", " (remove nil? [(when (pos? na) (str "+" na " added"))
                                      (when (pos? nm) (str "~" nm " modified"))
                                      (when (pos? nd) (str "-" nd " removed"))]))
        (str/join " / " (remove nil? [(when (pos? na) (str "+" na))
                                      (when (pos? nm) (str "~" nm))
                                      (when (pos? nd) (str "-" nd))]))))))

(defn- infer-commit-scope [nss paths schemas rules routes docs branch]
  (let [strip-seon (fn [s] (str/replace s #"^seon\." ""))
        clean-sc (fn [s]
                   (when (string? s)
                     (let [t (-> s str/trim (str/replace #"[^a-zA-Z0-9._/-]" "") strip-seon)]
                       (when-not (str/blank? t)
                         (if (> (count t) 18) (subs t 0 18) t)))))
        non-test-nss (remove #(str/ends-with? (name %) "-test") nss)
        eff-nss (if (seq non-test-nss) non-test-nss nss)]
    (or (when (seq eff-nss)
          (let [uniq (vec (distinct eff-nss))]
            (if (= 1 (count uniq))
              (clean-sc (str/replace (name (first uniq)) #"-test$" ""))
              (let [top-seg (fn [n] (first (str/split (strip-seon (name n)) #"\.")))
                    [best _] (apply max-key val (frequencies (map top-seg eff-nss)))]
                (clean-sc best)))))
        (when (pos? (+ (count (:added schemas)) (count (:modified schemas)) (count (:deleted schemas)))) "schemas")
        (when (pos? (+ (count (:added rules)) (count (:modified rules)) (count (:deleted rules)))) "rules")
        (when (pos? (+ (count (:added routes)) (count (:modified routes)) (count (:deleted routes)))) "routes")
        (when (seq docs)
          (if (= 1 (count docs))
            (clean-sc (str/replace (last (str/split (first docs) #"/")) #"\.md$" ""))
            "docs"))
        (when (seq paths)
          (let [p (first (sort paths))]
            (cond
              (str/starts-with? p "test/") "test"
              (str/starts-with? p "bench/") "bench"
              (str/starts-with? p "bin/") "bin"
              (str/starts-with? p "resources/seon/") (clean-sc (str/replace (last (str/split p #"/")) #"\.edn$" ""))
              :else (clean-sc (first (str/split p #"/"))))))
        (when (keyword? branch) (clean-sc (name branch))))))

(defn render-merge-commit-message
  "Pure Conventional-Commit formatter over a structured merge summary map.
   Guarantees a single-line subject `<= 72` chars plus structured non-empty body sections."
  {:malli/schema [:=> [:cat :map] :string]
   :cost/ticks 2000000}
  [summary]
  (let [branch (:branch summary)
        max-tx (when (int? (:max-tx summary)) (:max-tx summary))
        agent-id (when (keyword? (:agent-id summary)) (:agent-id summary))
        task (when (map? (:task summary)) (:task summary))
        seq-of (fn [pred x] (if (and (coll? x) (not (map? x))) (filterv pred x) []))
        fixes (seq-of map? (:fixes summary))
        schemas (safe-bucket (:schemas summary))
        rules (safe-bucket (:rules summary))
        routes (safe-bucket (:routes summary))
        vars (safe-bucket (:vars summary))
        var-nss (seq-of symbol? (when (map? (:vars summary)) (:namespaces (:vars summary))))
        all-vars (concat (:added vars) (:modified vars) (:deleted vars))
        inferred-nss (if (seq var-nss)
                       var-nss
                       (vec (keep #(when (and (symbol? %) (namespace %)) (symbol (namespace %))) all-vars)))
        docs (vec (distinct (seq-of string? (:docs summary))))
        paths (vec (distinct (sort (seq-of string? (:touched-paths summary)))))
        doc-path? #(or (= % "AGENTS.md") (str/starts-with? % "docs/"))
        test-path? #(str/starts-with? % "test/")
        ctype (cond
                (seq fixes) "fix"
                (or (and (seq paths) (every? doc-path? paths))
                    (and (seq docs) (empty? paths) (empty? all-vars)
                         (nil? (format-bucket-counts schemas false))
                         (nil? (format-bucket-counts rules false))
                         (nil? (format-bucket-counts routes false)))) "docs"
                (or (and (seq paths) (every? test-path? paths))
                    (and (seq all-vars)
                         (every? #(and (symbol? %) (namespace %) (str/ends-with? (namespace %) "-test")) all-vars)
                         (nil? (format-bucket-counts schemas false))
                         (nil? (format-bucket-counts rules false))
                         (nil? (format-bucket-counts routes false)))) "test"
                (or (seq (:added vars)) (seq (:added schemas)) (seq (:added rules)) (seq (:added routes))) "feat"
                (or (seq (:modified vars)) (seq (:deleted vars)) (some #(str/starts-with? % "src/") paths)) "refactor"
                :else "chore")
        scope (infer-commit-scope inferred-nss paths schemas rules routes docs branch)
        pl (fn [verb xs noun] (when (seq xs) (str verb " " (count xs) " " noun (when (> (count xs) 1) "s"))))
        mech-parts (remove nil?
                           [(pl "add" (:added schemas) "schema")
                            (pl "update" (:modified schemas) "schema")
                            (pl "add" (:added rules) "rule")
                            (pl "update" (:modified rules) "rule")
                            (pl "add" (:added routes) "route")
                            (pl "add" (:added vars) "var")
                            (pl "update" (:modified vars) "var")
                            (pl "remove" (:deleted vars) "var")
                            (pl "update" docs "doc")])
        mech-sum (or (when (seq mech-parts) (str/join ", " (take 3 mech-parts)))
                     (when (seq paths) (str "update " (count paths) " file" (when (> (count paths) 1) "s")))
                     (str "merge " (if (keyword? branch) (name branch) "branch")))
        sum-text (or (some #(clean-one-line (:change-summary %)) fixes)
                     (clean-one-line (:title task))
                     mech-sum)
        prefix (if scope (str ctype "(" scope "): ") (str ctype ": "))
        raw-subj (str prefix sum-text)
        subject (if (<= (count raw-subj) 72)
                  raw-subj
                  (str (subs raw-subj 0 (max 1 (- 72 3))) "..."))
        br-meta (str/join ", " (remove nil? [(when max-tx (str "tx " max-tx))
                                            (when agent-id (str "agent " agent-id))]))
        sch-line (when-let [c (format-bucket-counts schemas false)]
                   (let [ks (take 5 (concat (:added schemas) (:modified schemas) (:deleted schemas)))]
                     (str "Schemas: " c (when (seq ks) (str " (" (str/join ", " ks) ")")))))
        rule-line (when-let [c (format-bucket-counts rules false)] (str "Rules: " c))
        route-line (when-let [c (format-bucket-counts routes false)] (str "Routes: " c))
        var-line (when-let [c (format-bucket-counts vars true)]
                   (let [top-ns (mapv key (take 3 (sort-by (comp - val) (frequencies inferred-nss))))]
                     (str "Vars: " c (when (seq top-ns) (str " (" (str/join ", " top-ns) ")")))))
        doc-line (when (seq docs) (str "Docs: " (str/join ", " (take 4 docs))))
        task-line (when (or (:id task) (clean-one-line (:title task)))
                    (str "Task: " (or (:id task) "")
                         (when-let [t (clean-one-line (:title task))]
                           (str (when (:id task) " — ") t))))
        task-sum-line (when-let [ts (clean-one-line (:summary task))] (str "Summary: " ts))
        fix-lines (keep (fn [fx]
                          (let [ec (:error-class fx)
                                ba (or (clean-one-line (:before-after fx)) (clean-one-line (:change-summary fx)))]
                            (when (or ec ba)
                              (str "Fix: " (or ec :fix) (when ba (str " — " ba))))))
                        fixes)
        body (vec (concat
                   (when branch [(str "Branch: " branch (when-not (str/blank? br-meta) (str " (" br-meta ")")))])
                   (when task-line [task-line])
                   (when task-sum-line [task-sum-line])
                   fix-lines
                   (remove nil? [sch-line rule-line route-line var-line doc-line
                                 (when (seq paths) (str "Files: " (count paths) " touched"))])))]
    (if (seq body)
      (str subject "\n\n" (str/join "\n" body))
      subject)))

(defn format-merge-commit-message
  "Synthesizes a Conventional-Commit Git message mechanically from Datahike `db`, `branch`, `plan`,
   and optional `opts` (`:agent-id`, `:task-id`, `:task`, `:max-tx`) with zero LLM calls."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :keyword :map] :string]
                  [:=> [:cat [:maybe :seon.host/db] :keyword :map [:maybe :map]] :string]]
   :cost/ticks 5000000}
  ([db branch plan] (format-merge-commit-message db branch plan nil))
  ([db branch plan opts]
   (let [sm (when (map? db) (:schema db))
         diffs (or (:entity-diffs plan) [])
         tx-maps (filterv map? (:tx-data plan))
         by-kind (fn [attr]
                   (reduce (fn [acc {:keys [entity status]}]
                             (if (and (vector? entity) (= attr (first entity)))
                               (let [k (case status :added :added :deleted :deleted :modified)]
                                 (update acc k conj (second entity)))
                               acc))
                           {:added [] :modified [] :deleted []}
                           diffs))
         ag (when (and db (contains? sm :seon.agent/branch))
              (or (when-let [aid (:agent-id opts)]
                    (when (first (d/datoms db :avet :seon.agent/id aid))
                      (d/pull db '[:seon.agent/id {:seon.agent/tasks [:seon.task/id]} :seon.agent/archived?] [:seon.agent/id aid])))
                  (let [dts (if (get-in sm [:seon.agent/branch :db/index])
                              (d/datoms db :avet :seon.agent/branch branch)
                              (filter #(= branch (:v %)) (d/datoms db :aevt :seon.agent/branch)))]
                    (->> dts
                         (map #(d/pull db '[:seon.agent/id {:seon.agent/tasks [:seon.task/id]} :seon.agent/archived?] (:e %)))
                         (remove :seon.agent/archived?)
                         first))))
         agent-id (or (:agent-id opts) (:seon.agent/id ag))
         task-id (or (:task-id opts)
                     (first (keep :seon.task/id (:seon.agent/tasks ag)))
                     (some (fn [{:keys [entity status]}]
                             (when (and (vector? entity) (= :seon.task/id (first entity)) (not= status :deleted))
                               (second entity)))
                           diffs))
         task-ent (or (:task opts)
                      (when task-id
                        (let [from-db (when (and db (contains? sm :seon.task/id)
                                                 (first (d/datoms db :avet :seon.task/id task-id)))
                                        (d/pull db '[:seon.task/id :seon.task/title :seon.task/summary]
                                                [:seon.task/id task-id]))
                              from-tx (some #(when (= task-id (:seon.task/id %)) %) tx-maps)
                              m (merge from-db from-tx)]
                          (when (seq m)
                            {:id task-id
                             :title (:seon.task/title m)
                             :summary (:seon.task/summary m)}))))
         fix-ids (keep (fn [{:keys [entity status]}]
                         (when (and (vector? entity) (= :seon.fix/id (first entity)) (not= status :deleted))
                           (second entity)))
                       diffs)
         fixes (mapv (fn [fid]
                       (let [from-db (when (and db (contains? sm :seon.fix/id)
                                                (first (d/datoms db :avet :seon.fix/id fid)))
                                       (d/pull db '[:seon.fix/error-class :seon.fix/change-summary :seon.fix/before-after]
                                               [:seon.fix/id fid]))
                             from-tx (some #(when (= fid (:seon.fix/id %)) %) tx-maps)
                             m (merge from-db from-tx)]
                         {:id fid
                          :error-class (:seon.fix/error-class m)
                          :change-summary (:seon.fix/change-summary m)
                          :before-after (:seon.fix/before-after m)}))
                     fix-ids)
         vars-b (by-kind :seon.code.var/name)
         extra-nss (keep (fn [{:keys [entity]}]
                           (when (vector? entity)
                             (case (first entity)
                               :seon.code.ns/name (second entity)
                               :seon.code.form/key (let [s (str (second entity))
                                                         p (first (str/split s #"[#/]" 2))]
                                                     (when-not (str/blank? p) (symbol p)))
                               nil)))
                         diffs)
         var-nss (vec (concat (keep #(when (and (symbol? %) (namespace %)) (symbol (namespace %)))
                                    (concat (:added vars-b) (:modified vars-b) (:deleted vars-b)))
                              extra-nss))
         paths (vec (sort (or (:touched-paths plan) [])))
         docs (vec (distinct (concat (filter #(or (= % "AGENTS.md") (str/starts-with? % "docs/")) paths)
                                     (keep #(when (and (vector? (:entity %)) (= :seon.code.doc/id (first (:entity %))) (:path %))
                                              (:path %))
                                           diffs))))]
     (render-merge-commit-message
      {:branch branch
       :max-tx (or (:max-tx opts) (when (map? db) (:max-tx db)))
       :agent-id agent-id
       :task task-ent
       :fixes fixes
       :schemas (by-kind :seon.schema/key)
       :rules (by-kind :seon.rule/id)
       :routes (by-kind :seon.route/name)
       :vars (assoc vars-b :namespaces var-nss)
       :docs docs
       :touched-paths paths}))))