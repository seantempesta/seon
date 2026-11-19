(ns seon.code.io
  "Lossless Datahike <-> file import/export for Clojure, Markdown, schemas, configs, tasks, and agents."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.code.files :as files]
            [seon.code.parse :as parse :refer [clj-path? md-path? ns-parent-chain-tx parse-md-sections read-clj]]
            [seon.schema :as schema]
            [seon.store :as store]))

(defn doc-sections [db path]
  (->> (d/datoms db :avet :seon.code.doc/path path)
       (map #(d/pull db '[:seon.code.doc/id :seon.code.doc/path :seon.code.doc/order
                          :seon.code.doc/heading :seon.code.doc/body] (:e %)))
       (sort-by (juxt :seon.code.doc/order :seon.code.doc/id))
       vec))

(defn render-doc [db path]
  (let [secs (doc-sections db path)]
    (when (seq secs)
      (apply str (map (fn [s] (str (:seon.code.doc/heading s "") (:seon.code.doc/body s ""))) secs)))))

(defn ns-forms [db nsym]
  (->> (d/datoms db :avet :seon.code.form/ns nsym)
       (map #(d/pull db '[:db/id :seon.code.form/key :seon.code.form/ns :seon.code.form/order
                          :seon.code.form/lead :seon.code.form/src :seon.code.form/hash
                          :seon.code.form/deps :seon.code.form/test?] (:e %)))
       (sort-by (juxt :seon.code.form/order :seon.code.form/key))
       vec))

(defn ns-vars [db nsym]
  (->> (d/datoms db :avet :seon.code.var/ns nsym)
       (map #(d/pull db '[*] (:e %)))
       (sort-by #(str (:seon.code.var/name %)))
       vec))

(defn render-schema-file
  "Renders all `:seon.schema/key` entities whose owner path is `path` into a clean EDN map."
  [db path]
  (files/render-schema-file db path))

(defn retract-stale-many-ops
  "Retracts removed values from `:db.cardinality/many` set attributes (`attrs`) on updated entities
   so editing a function to remove a ref/dep/schema-ref actually clears the old Datahike datoms!"
  [db id-attr attrs ents]
  (when db
    (vec
     (mapcat
      (fn [ent]
        (let [id-val (get ent id-attr)
              eid (when (contains? (:schema db) id-attr)
                    (:e (first (d/datoms db :avet id-attr id-val))))]
          (when eid
            (mapcat
             (fn [a]
               (let [old-set (into #{} (map :v) (d/datoms db :eavt eid a))
                     new-set (set (get ent a #{}))]
                 (for [gone (set/difference old-set new-set)]
                   [:db/retract [id-attr id-val] a gone])))
             attrs))))
      ents))))

(defn file-import-tx [db path text exec?]
  (cond
    (clj-path? path)
    (let [{:keys [ns doc requires tail forms vars]} (read-clj path text)
          new-keys (into #{} (map :seon.code.form/key) forms)
          new-vars (into #{} (map :seon.code.var/name) vars)
          stale-forms (when db
                        (for [old (ns-forms db ns)
                              :when (not (contains? new-keys (:seon.code.form/key old)))]
                          [:db/retractEntity [:seon.code.form/key (:seon.code.form/key old)]]))
          stale-vars (when db
                       (for [old (ns-vars db ns)
                             :when (not (contains? new-vars (:seon.code.var/name old)))]
                         [:db/retractEntity [:seon.code.var/name (:seon.code.var/name old)]]))
          stale-form-deps (retract-stale-many-ops db :seon.code.form/key [:seon.code.form/deps] forms)
          stale-var-refs  (retract-stale-many-ops db :seon.code.var/name [:seon.code.var/refs :seon.code.var/schema-refs :seon.code.var/keywords] vars)
          ns-chain (ns-parent-chain-tx ns)
          ns-ent (cond-> (assoc (peek ns-chain) :seon.code.ns/path path :seon.code.ns/tail tail)
                   doc (assoc :seon.code.ns/doc doc)
                   (seq requires) (assoc :seon.code.ns/requires requires))]
      (vec (concat stale-forms stale-vars stale-form-deps stale-var-refs (pop ns-chain) [ns-ent] forms vars)))

    (md-path? path)
    (let [secs (parse-md-sections path text)
          new-ids (into #{} (map :seon.code.doc/id) secs)
          stale-docs (when db
                       (for [old (doc-sections db path)
                             :when (not (contains? new-ids (:seon.code.doc/id old)))]
                         [:db/retractEntity [:seon.code.doc/id (:seon.code.doc/id old)]]))]
      (vec (concat stale-docs secs)))

    (files/schema-file-path? path)
    (files/import-schema-file-tx path text)

    (files/config-file-path? path)
    [(store/read-aero-config-tx db text)
     {:seon.code.file/path path :seon.code.file/text text :seon.code.file/exec? false}]

    (files/task-file-path? path)
    (files/import-tasks-tx db path text)

    (files/agent-file-path? path)
    (files/import-agents-tx db path text)

    :else
    [{:seon.code.file/path path :seon.code.file/text text :seon.code.file/exec? (boolean exec?)}]))

(defn render-ns [db nsym]
  (when-let [ns-ent (d/pull db '[:seon.code.ns/name :seon.code.ns/tail] [:seon.code.ns/name nsym])]
    (when (:seon.code.ns/name ns-ent)
      (let [fs (ns-forms db nsym)]
        (apply str
               (concat (mapcat (fn [[i f]]
                                 (let [lead (:seon.code.form/lead f "")
                                       eff-lead (if (and (pos? i) (= "" lead)) "\n\n" lead)]
                                   [eff-lead (:seon.code.form/src f "")]))
                               (map-indexed vector fs))
                       [(:seon.code.ns/tail ns-ent "")]))))))

(defn render-path [db path]
  (cond
    (first (d/datoms db :avet :seon.code.ns/path path))
    (let [ns-dt (first (d/datoms db :avet :seon.code.ns/path path))]
      (render-ns db (:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:e ns-dt)))))

    (md-path? path)
    (or (render-doc db path)
        (:seon.code.file/text (d/pull db '[:seon.code.file/text] [:seon.code.file/path path])))

    (files/schema-file-path? path)
    (or (:seon.code.file/text (d/pull db '[:seon.code.file/text] [:seon.code.file/path path]))
        (files/render-schema-file db path))

    (files/config-file-path? path)
    (files/render-config-file db path)

    (files/task-file-path? path)
    (files/render-tasks-file db path)

    (files/agent-file-path? path)
    (files/render-agent-file db path)

    :else
    (:seon.code.file/text (d/pull db '[:seon.code.file/text] [:seon.code.file/path path]))))
