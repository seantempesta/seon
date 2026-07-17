(ns my.skills-test
  "Current `my.skills` schema, corpus, and database-value behavior."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [my.skills :as skills]
    [seon.agent.ctx :as ctx]
    [seon.config :as config]
    [seon.db :as db]
    [seon.schema :as schema]))

(defn- as-database-fn
  [f]
  (fn
    ([] (f))
    ([_] (f))))

(defn- as-query-fn
  [f]
  (fn
    ([request] (f request))
    ([query-form & inputs] (apply f query-form inputs))))

(defn- as-pull-fn
  [f]
  (fn
    ([request] (f request))
    ([selector eid] (f selector eid))
    ([database selector eid] (f database selector eid))))

(def ^:private configuration
  (config/resolve-config-singleton
   {:seon.config/skills {:seon.config/dirs ["seon-skills"]}}))

(def ^:private skills-dir
  (config/skills-dir configuration))

(def datahike-skill-path
  (str skills-dir "/datahike/SKILL.md"))

(defn- finish
  [promise done]
  (-> promise
      (.then (fn [_] (done)))
      (.catch (fn [error]
                (is false (str "threw — " error))
                (done)))))

(defn- with-list-fakes
  [{database-fn ::database-fn
    agent-id-fn ::agent-id-fn
    query-fn ::query-fn}
   body]
  (let [saved {::database-fn db/db
               ::agent-id-fn db/current-agent-id
               ::query-fn db/query}]
    (set! db/db (as-database-fn database-fn))
    (set! db/current-agent-id agent-id-fn)
    (set! db/query (as-query-fn query-fn))
    (-> (js/Promise.resolve (body))
        (.finally
          (fn []
            (set! db/db (::database-fn saved))
            (set! db/current-agent-id (::agent-id-fn saved))
            (set! db/query (::query-fn saved)))))))

(defn- with-skill-action-fakes
  [{query-fn ::query-fn
    install-fn ::install-fn
    remove-fn ::remove-fn}
   body]
  (let [saved {::query-fn db/query
               ::install-fn ctx/install!
               ::remove-fn ctx/remove!}]
    (set! db/query (as-query-fn query-fn))
    (set! ctx/install! install-fn)
    (set! ctx/remove! remove-fn)
    (-> (js/Promise.resolve (body))
        (.finally
          (fn []
            (set! db/query (::query-fn saved))
            (set! ctx/install! (::install-fn saved))
            (set! ctx/remove! (::remove-fn saved)))))))

(defn- with-skill-render-fakes
  [{query-fn ::query-fn
    pull-fn ::pull-fn
    read-file-fn ::read-file-fn}
   body]
  (let [saved {::query-fn db/query
               ::pull-fn db/pull
               ::read-file-fn ctx/read-file-text}]
    (set! db/query (as-query-fn query-fn))
    (set! db/pull (as-pull-fn pull-fn))
    (set! ctx/read-file-text read-file-fn)
    (try
      (body)
      (finally
        (set! db/query (::query-fn saved))
        (set! db/pull (::pull-fn saved))
        (set! ctx/read-file-text (::read-file-fn saved))))))

(deftest schema-shapes-are-registered
  (is (= [:keyword {:seon.db/identity true}]
         (schema/schema-definition :my.skills/name)))
  (is (= [:string {:min 1}]
         (schema/schema-definition :my.skills/description)))
  (is (= [:string {:min 1}]
         (schema/schema-definition :my.skills/body))))

(deftest seed-scan-reads-the-shipped-skill-corpus
  (let [rows (skills/seed-skills-tx-data skills-dir)
        by-name (into {} (map (juxt :my.skills/name identity)) rows)]
    (is (every? (fn [row]
                  (and (keyword? (:my.skills/name row))
                       (not (str/blank? (:my.skills/description row)))
                       (str/ends-with? (:seon.agent.ctx/file-path row)
                                       "SKILL.md")))
                rows))
    (is (contains? by-name :datahike))
    (is (= datahike-skill-path
           (:seon.agent.ctx/file-path (by-name :datahike))))))

(deftest seed-scan-is-empty-for-an-absent-dir
  (is (= [] (skills/seed-skills-tx-data "/no/such/skills/dir"))))

(deftest load-and-unload-use-one-stable-context-block-identity
  (async done
    (let [installs (atom [])
          removals (atom [])]
      (finish
        (-> (with-skill-action-fakes
              {::query-fn (fn [_] (js/Promise.resolve 101))
               ::install-fn
               (fn [request]
                 (swap! installs conj request)
                 (js/Promise.resolve {:seon.agent.ctx/ok? true}))
               ::remove-fn
               (fn [block-name]
                 (swap! removals conj block-name)
                 (js/Promise.resolve {:seon.agent.ctx/ok? true}))}
              #(-> (skills/load :datahike)
                   (.then
                     (fn [result]
                       (is (true? (:my.skills/ok? result)))
                       (skills/load :datahike)))
                   (.then
                     (fn [result]
                       (is (true? (:my.skills/ok? result)))
                       (skills/unload :datahike)))))
            (.then
              (fn [result]
                (is (true? (:my.skills/ok? result)))
                (is (= [:skill/datahike :skill/datahike]
                       (mapv :seon.agent.ctx/name @installs))
                    "reloading targets the same context-block identity")
                (is (= ['my.skills/skill-block 'my.skills/skill-block]
                       (mapv :seon.render/ai @installs)))
                (is (= [:skill/datahike] @removals)))))
        done))))

(deftest load-of-an-absent-skill-remains-an-error-value
  (async done
    (let [installs (atom 0)]
      (finish
        (-> (with-skill-action-fakes
              {::query-fn (fn [_] (js/Promise.resolve nil))
               ::install-fn
               (fn [_]
                 (swap! installs inc)
                 (js/Promise.resolve {:seon.agent.ctx/ok? true}))
               ::remove-fn
               (fn [_] (js/Promise.resolve {:seon.agent.ctx/ok? true}))}
              #(skills/load :missing))
            (.then
              (fn [result]
                (is (false? (:my.skills/ok? result)))
                (is (str/includes? (:my.skills/message result) "no skill"))
                (is (zero? @installs)))))
        done))))

(deftest skill-render-keeps-file-content-derived-and-drops-missing-rows
  (let [database {:db-name "default" :t 16}
        queries (atom [])
        pulls (atom [])
        rendered
        (with-skill-render-fakes
          {::query-fn
           (fn [& args]
             (swap! queries conj args)
             101)
           ::pull-fn
           (fn [request]
             (swap! pulls conj request)
             {:seon.agent.ctx/file-path datahike-skill-path})
           ::read-file-fn
           (fn [_]
             "---\nname: datahike\ndescription: DB patterns.\n---\n# Datahike\nUse immutable database values.")}
          #(skills/skill-block
             {:seon.db/db database
              :seon.render/node
              {:seon.agent.ctx/name :skill/datahike}}))]
    (is (str/includes? rendered "; # Datahike"))
    (is (not (str/includes? rendered "name: datahike"))
        "frontmatter is not duplicated into agent context")
    (is (every? #(or (str/blank? %) (str/starts-with? % ";"))
                (str/split-lines rendered))
        "the rendered body remains eval-safe comment data")
    (is (= 1 (count @queries)))
    (is (= 1 (count @pulls)))
    (is (identical? database (:seon.db/db (first @pulls))))
    (is (= ""
           (with-skill-render-fakes
             {::query-fn (fn [& _] nil)
              ::pull-fn (fn [_] (throw (js/Error. "must not pull")))
              ::read-file-fn (fn [_] nil)}
             #(skills/skill-block
                {:seon.db/db database
                 :seon.render/node
                 {:seon.agent.ctx/name :skill/missing}})))
        "a missing skill row omits the render")))

(deftest list-reuses-one-immutable-database-value
  (async done
    (let [database {:db-name "default" :t 12}
          database-calls (atom 0)
          requests (atom [])]
      (finish
        (-> (with-list-fakes
              {::database-fn
               (fn []
                 (swap! database-calls inc)
                 (js/Promise.resolve database))
               ::agent-id-fn (constantly "agent-a")
               ::query-fn
               (fn [request]
                 (swap! requests conj request)
                 (js/Promise.resolve
                   (if (empty? (:seon.db/args request))
                     #{[:datahike "Database patterns."]}
                     [:skill/datahike])))}
              skills/list)
            (.then
              (fn [result]
                (is (= 1 @database-calls))
                (is (= 2 (count @requests)))
                (is (every? #(identical? database (:seon.db/db %))
                            @requests))
                (is (= [{:my.skills/name :datahike
                         :my.skills/description "Database patterns."
                         :my.skills/loaded? true}]
                       result)))))
        done))))

(deftest list-database-error-passes-through-without-querying
  (async done
    (let [database-error {:seon.error/message "database unavailable"}
          queries (atom 0)]
      (finish
        (-> (with-list-fakes
              {::database-fn (fn [] (js/Promise.resolve database-error))
               ::agent-id-fn (constantly "agent-a")
               ::query-fn
               (fn [_] (swap! queries inc) (js/Promise.resolve []))}
              skills/list)
            (.then
              (fn [result]
                (is (identical? database-error result))
                (is (zero? @queries)))))
        done))))

(deftest list-query-error-passes-through-without-a-second-query
  (async done
    (let [database {:db-name "default" :t 13}
          query-error {:seon.error/message "catalog query failed"}
          queries (atom 0)]
      (finish
        (-> (with-list-fakes
              {::database-fn (fn [] (js/Promise.resolve database))
               ::agent-id-fn (constantly "agent-a")
               ::query-fn
               (fn [_]
                 (swap! queries inc)
                 (js/Promise.resolve query-error))}
              skills/list)
            (.then
              (fn [result]
                (is (identical? query-error result))
                (is (= 1 @queries)))))
        done))))
