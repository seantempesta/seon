(ns my.skills-test
  "my.skills contracts, on a fresh :memory conn seeded with the pod's boot
   schema (never the live agent conn):

     1. SCHEMA — the three `:my.skills/*` attrs are the shapes the design
        locks (identity keyword name, non-blank description, non-blank inline
        body).
     2. CORPUS SCAN — `seed-skills-tx-data` reads the REAL shipped corpus
        (`config/skills-dir`, manifest-owned) end-to-end (frontmatter scanner
        + dir walk), so the scanner can't bit-rot: the shipped skills appear
        as rows with name + file-path + verbatim description.
     3. LOAD / UNLOAD / LIST — load installs ONE `:skill/<name>` block, unload
        removes it, and the catalog's `::loaded?` is DERIVED from the agent's
        own blocks (no stored flag).
     4. RENDER — catalog-block renders a `;`-line per skill (and \"\" when the
        store has none); skill-block renders the file body (frontmatter
        stripped, `;`-commented) + the derived token-cost footer.

   The functions read `db/*conn*` AMBIENTLY exactly as the live pod does, so the
   test installs the conn on the ROOT `db/*conn*` and RE-PINS it (via `pinned`)
   right before each ambient read — a `binding` would pop at the first async
   hop (CLJS dynamic bindings don't survive `await`)."
  (:require
    [cljs.test :refer [deftest is async]]
    [clojure.string :as str]
    [datahike.api :as d]
    [seon.client :as client]
    [seon.config :as config]
    [seon.db :as db]
    [seon.schema :as schema]
    [my.skills :as skills]))

;; Derived from the SAME source of truth the scanner uses (the manifest's
;; :seon.config/dirs, else env, else the default) — never a pinned literal,
;; so a corpus move can't stale this test.
(def datahike-skill-path (str (config/skills-dir) "/datahike/SKILL.md"))

;; A valid `:seon.db/id` agent id — exactly 14 chars ("root" or a 14-char id
;; are the only shapes the `:seon.agent/id` schema accepts).
(def test-agent-id "tst-2606280000")

(defn- fresh-conn
  "Promise of a fresh :memory conn carrying the pod's boot schema (agent +
   ctx attrs). `:my.skills/*` and `:seon.agent.ctx/file-path` are NOT
   pre-installed — `db/transact!` lazy-installs them on first write."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data (into (db/malli->datahike-schema
                                                  client/agent-bootstrap-attrs)
                                                (db/tx-meta-datahike-schema))})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh conn `set!` as the ROOT db/*conn* for `body` (conn → Promise),
   prior root restored after. `body` is handed the conn so it can re-pin."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- pinned
  "Wrap a `.then` callback so it RE-PINS `conn` as the root db/*conn* before
   running — every ambient read in `f` then resolves against THIS test's conn."
  [conn f]
  (fn [x] (set! db/*conn* conn) (f x)))

(defn- block-names
  "The set of ctx-block names on agent `id` in `conn`."
  [conn id]
  (set (db/query '[:find [?n ...]
                   :in $ ?aid
                   :where
                   [?a :seon.agent/id ?aid]
                   [?a :seon.agent/ctx ?b]
                   [?b :seon.agent.ctx/name ?n]]
                 @conn id)))

;;; ───────────────────────────────────────────────────────────────────────
;;; 1. SCHEMA — the three :my.skills/* shapes.
;;; ───────────────────────────────────────────────────────────────────────

(deftest schema-shapes-are-registered
  (is (= [:keyword {:seon.db/identity true}] (schema/schema-definition :my.skills/name))
      "name is the identity keyword — the catalog key AND load/unload handle")
  (is (= [:string {:min 1}] (schema/schema-definition :my.skills/description))
      "description is a non-blank string (the catalog line / trigger text)")
  (is (= [:string {:min 1}] (schema/schema-definition :my.skills/body))
      "inline body is a non-blank string (agent-authored skills only)"))

;;; ───────────────────────────────────────────────────────────────────────
;;; 2. CORPUS SCAN — the real shipped corpus dir, end to end.
;;; ───────────────────────────────────────────────────────────────────────

(deftest seed-scan-reads-the-shipped-skill-corpus
  (let [rows (skills/seed-skills-tx-data)
        by-name (into {} (map (juxt :my.skills/name identity)) rows)]
    (is (every? (fn [r] (and (keyword? (:my.skills/name r))
                             (string? (:my.skills/description r))
                             (not (str/blank? (:my.skills/description r)))
                             (str/ends-with? (:seon.agent.ctx/file-path r) "SKILL.md")))
                rows)
        "every scanned row is name(kw) + non-blank description + a SKILL.md path")
    (is (contains? by-name :datahike) "the shipped datahike skill is found")
    (is (contains? by-name :clojurescript) "the shipped clojurescript skill is found")
    (is (contains? by-name :data-oriented-clojure)
        "a later-authored skill is picked up by the scan, no hardcoding")
    (is (= datahike-skill-path (:seon.agent.ctx/file-path (by-name :datahike)))
        "the row stores the SKILL.md PATH — the body stays in the file")
    (is (str/starts-with? (:my.skills/description (by-name :datahike))
                          "Seon database patterns.")
        "description is the frontmatter value VERBATIM (quotes stripped)")))

(deftest seed-scan-is-empty-for-an-absent-dir
  (is (= [] (skills/seed-skills-tx-data "/no/such/skills/dir"))
      "an absent/unreadable dir yields no rows (no skills, no crash)"))

;;; ───────────────────────────────────────────────────────────────────────
;;; 3. LOAD / UNLOAD / LIST — install!/remove! + derived loaded?.
;;; ───────────────────────────────────────────────────────────────────────

(deftest load-installs-block-unload-removes-it-list-derives-loaded
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.agent/id test-agent-id}
                    {:my.skills/name :datahike
                     :my.skills/description "DB patterns."
                     :seon.agent.ctx/file-path datahike-skill-path}]})
                ;; nothing loaded yet — list marks it ○, no block installed
                (.then (pinned conn
                         (fn [_]
                           (is (= [false]
                                  (mapv :my.skills/loaded?
                                        (db/with-agent test-agent-id #(skills/list))))
                               "before load: the row exists, loaded? false")
                           (is (not (contains? (block-names conn test-agent-id) :skill/datahike))
                               "no :skill/datahike block yet"))))
                ;; load → ONE :skill/datahike block, list flips loaded? true
                (.then (pinned conn (fn [_] (db/with-agent test-agent-id #(skills/load :datahike)))))
                (.then (pinned conn
                         (fn [res]
                           (is (true? (:my.skills/ok? res)) "load is ok")
                           (is (contains? (block-names conn test-agent-id) :skill/datahike)
                               "load installed the :skill/datahike block")
                           (is (= [true]
                                  (mapv :my.skills/loaded?
                                        (db/with-agent test-agent-id #(skills/list))))
                               "loaded? is DERIVED from the agent's own block — now true"))))
                ;; load again → idempotent upsert (still exactly one block)
                (.then (pinned conn (fn [_] (db/with-agent test-agent-id #(skills/load :datahike)))))
                (.then (pinned conn
                         (fn [_]
                           (is (= 1 (db/query '[:find (count ?b) .
                                                :in $ ?aid
                                                :where
                                                [?a :seon.agent/id ?aid]
                                                [?a :seon.agent/ctx ?b]]
                                              @conn test-agent-id))
                               "re-loading replaces in place — ag1 still has exactly one block"))))
                ;; unload → block gone, loaded? back to false
                (.then (pinned conn (fn [_] (db/with-agent test-agent-id #(skills/unload :datahike)))))
                (.then (pinned conn
                         (fn [res]
                           (is (true? (:my.skills/ok? res)) "unload is ok")
                           (is (not (contains? (block-names conn test-agent-id) :skill/datahike))
                               "unload removed the block")
                           (is (= [false]
                                  (mapv :my.skills/loaded?
                                        (db/with-agent test-agent-id #(skills/list))))
                               "loaded? derived false again — nothing stored to clear")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest load-of-an-absent-skill-is-an-error-value
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact! {:seon.db/tx-data [{:seon.agent/id test-agent-id}
                                                 {:my.skills/name :real
                                                  :my.skills/description "x."
                                                  :my.skills/body "the body"}]})
                (.then (pinned conn (fn [_] (db/with-agent test-agent-id #(skills/load :nope)))))
                (.then (pinned conn
                         (fn [res]
                           (is (false? (:my.skills/ok? res))
                               "loading a non-existent skill returns an error VALUE, never throws")
                           (is (str/includes? (:my.skills/message res) "no skill")
                               "the message names the miss")
                           (is (not (contains? (block-names conn test-agent-id) :skill/nope))
                               "no block installed for a miss")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;;; ───────────────────────────────────────────────────────────────────────
;;; 4. RENDER — catalog (L0) + loaded body+footer (L2).
;;; ───────────────────────────────────────────────────────────────────────

(deftest catalog-block-renders-a-line-per-skill-and-empties-out
  (async done
    (-> (with-conn
          (fn [conn]
            (is (= "" (skills/catalog-block {:seon.db/db @conn :seon.agent/id test-agent-id}))
                "no skill rows → \"\" → the section drops (reactive)")
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.agent/id test-agent-id}
                    {:my.skills/name :datahike :my.skills/description "DB patterns."
                     :seon.agent.ctx/file-path datahike-skill-path}]})
                (.then (pinned conn
                         (fn [_]
                           (let [out (skills/catalog-block
                                       {:seon.db/db @conn :seon.agent/id test-agent-id})]
                             (is (str/includes? out "SKILLS — loadable knowledge")
                                 "the prose header explains skills cost nothing until loaded")
                             (is (str/includes? out "; - :datahike  ○ — DB patterns.")
                                 "one ;-line per skill: name ○ (not loaded) — description"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest skill-block-renders-the-file-body-quoted-with-the-cost-footer
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:my.skills/name :datahike :my.skills/description "DB patterns."
                     :seon.agent.ctx/file-path datahike-skill-path}]})
                (.then (pinned conn
                         (fn [_]
                           (let [out (skills/skill-block
                                       {:seon.db/db @conn
                                        :seon.render/node {:seon.agent.ctx/name :skill/datahike}})]
                             (is (str/includes? out "; # Datahike")
                                 "the file body renders, frontmatter stripped (starts at H1)")
                             (is (not (str/includes? out "name: datahike"))
                                 "the YAML frontmatter is stripped from the rendered body")
                             (is (every? #(or (str/blank? %) (str/starts-with? % ";"))
                                         (str/split-lines out))
                                 "every line is a ;-comment — the prompt stays eval-valid")
                             (is (str/includes? out "datahike skill · ~")
                                 "the derived token-cost footer is appended")
                             (is (str/includes? out "(my.skills/unload :datahike)")
                                 "the footer always carries the explicit unload hint"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest skill-block-drops-when-the-row-is-gone
  (async done
    (-> (with-conn
          (fn [conn]
            ;; an installed block whose skill row was never seeded → blank → drops
            (-> (db/transact! {:seon.db/tx-data
                               [{:my.skills/name :real :my.skills/description "x."
                                 :my.skills/body "body"}]})
                (.then (pinned conn
                         (fn [_]
                           (is (= "" (skills/skill-block
                                       {:seon.db/db @conn
                                        :seon.render/node {:seon.agent.ctx/name :skill/ghost}}))
                               "no matching row → \"\" → the block drops (reactive)")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
