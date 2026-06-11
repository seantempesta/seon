(ns seon.agent.todo-test
  "Envelope-contract tests for seon.agent.todo — the exemplar store/retrieve ns.
   Covers add!/complete!/reopen! happy + failure paths, owner scoping (ALS
   default + explicit), the resume property (open items persist; every list
   re-derives from the conn), and the pure open-todos-block view. All on a
   FRESH :memory conn seeded like the pod boots — never the live agent conn."
  (:require
    [cljs.test :refer [deftest is async]]
    [datahike.api :as d]
    [seon.client :as client]
    [seon.db :as db]
    [seon.agent.todo :as todo]))

(def ^:private a-id "todotest-agent-a")
(def ^:private b-id "todotest-agent-b")
(def ^:private a-ref [:seon.agent/id a-id])
(def ^:private b-ref [:seon.agent/id b-id])

(defn- fresh-conn
  "Promise of a fresh :memory conn with the pod's boot schema + the user
   entity + agents A and B (the same rows seon.client seeds at boot)."
  []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (d/transact!
                       conn
                       {:tx-data (into (db/malli->datahike-schema
                                         client/agent-bootstrap-attrs)
                                       (db/tx-meta-datahike-schema))})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data [{:seon.user/id "user"}
                                           {:seon.agent/id a-id
                                            :seon.agent/state :idle}
                                           {:seon.agent/id b-id
                                            :seon.agent/state :idle}]})))
                     (.then (fn [_] conn))))))))

(defn- with-conn
  "Fresh seeded conn, `set!` as the ROOT db/*conn* for `body` (conn →
   Promise), prior root restored after. Root set!, not `binding` — CLJS
   dynamic bindings pop at the first microtask boundary inside ^:async
   bodies (verified live; see seon.agent.message-test)."
  [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- open-titles [env]
  (mapv :seon.agent.todo/title (:seon.agent.todo/todos env)))

(deftest add-stores-a-fully-formed-open-todo
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (todo/add! {:seon.agent.todo/title "audit the schemas"
                            :seon.agent.todo/description "all of them"
                            :seon.agent.todo/owner a-ref
                            :seon.agent.todo/from  [:seon.user/id "user"]})
                (.then
                  (fn [{ok? :seon.agent.todo/ok? id :seon.agent.todo/id}]
                    (is (true? ok?))
                    (is (string? id) "response carries the durable id")
                    (let [t (d/pull @conn
                                    '[* {:seon.agent.todo/owner [:seon.agent/id]
                                         :seon.agent.todo/from  [:seon.user/id]}]
                                    [:seon.agent.todo/id id])]
                      (is (= "audit the schemas" (:seon.agent.todo/title t)))
                      (is (= "all of them" (:seon.agent.todo/description t)))
                      (is (= :open (:seon.agent.todo/status t)))
                      (is (inst? (:seon.agent.todo/created-at t)))
                      (is (= a-id (get-in t [:seon.agent.todo/owner :seon.agent/id])))
                      (is (= "user" (get-in t [:seon.agent.todo/from :seon.user/id])))
                      (is (nil? (:seon.agent.todo/completed-at t))
                          "open item: completed-at ABSENT, never nil")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest add-defaults-owner-from-agent-scope
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (db/with-agent a-id
                  (fn [] (todo/add! {:seon.agent.todo/title "note to self"})))
                (.then
                  (fn [{ok? :seon.agent.todo/ok? id :seon.agent.todo/id}]
                    (is (true? ok?))
                    (is (= a-id
                           (get-in (d/pull @conn
                                           '[{:seon.agent.todo/owner [:seon.agent/id]}]
                                           [:seon.agent.todo/id id])
                                   [:seon.agent.todo/owner :seon.agent/id]))
                        "owner defaulted to the ALS agent"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest add-guards-blank-title-and-missing-owner
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (todo/add! {:seon.agent.todo/title "  " :seon.agent.todo/owner a-ref})
                (.then (fn [{ok? :seon.agent.todo/ok? error :seon.agent.todo/error}]
                         (is (false? ok?))
                         (is (re-find #"blank" error))
                         (todo/add! {:seon.agent.todo/title "orphan"}))) ; no scope
                (.then (fn [{ok? :seon.agent.todo/ok? error :seon.agent.todo/error}]
                         (is (false? ok?))
                         (is (re-find #"with-agent" error) "names the fix")
                         (is (empty? (d/q '[:find ?t :where [?t :seon.agent.todo/id _]]
                                          @conn))
                             "nothing stored on either failure"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest the-store-retrieve-arc-with-resume
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (todo/add! {:seon.agent.todo/title "first (oldest)" :seon.agent.todo/owner a-ref})
                ;; backdate "first" so oldest-first ordering is deterministic
                (.then (fn [{id :seon.agent.todo/id}]
                         (d/transact! conn
                           {:tx-data [{:seon.agent.todo/id id
                                       :seon.agent.todo/created-at
                                       (js/Date. (- (js/Date.now) 120000))}]})))
                (.then (fn [_] (todo/add! {:seon.agent.todo/title "second"
                                           :seon.agent.todo/owner a-ref})))
                (.then (fn [_] (todo/add! {:seon.agent.todo/title "b's item"
                                           :seon.agent.todo/owner b-ref})))
                (.then
                  (fn [{ok? :seon.agent.todo/ok?}]
                    (is (true? ok?))
                    ;; RESUME: everything below re-derives from the conn —
                    ;; no in-memory state survives from the adds.
                    (is (= ["first (oldest)" "second"]
                           (open-titles (todo/list-open {:seon.agent.todo/owner a-ref})))
                        "owner-scoped, oldest first, b's item excluded")
                    (is (= ["first (oldest)" "second" "b's item"]
                           (open-titles (todo/list-open {:seon.agent.todo/all? true})))
                        "all? widens across owners")
                    (let [block (todo/open-todos-block @conn a-ref)]
                      (is (re-find #"complete!" block) "block teaches the verb")
                      (is (re-find #"(?m)^\S+ \[2m\] first \(oldest\)$" block)
                          "line = <id> [<age>] <title>, oldest first"))
                    (let [id (-> (todo/list-open {:seon.agent.todo/owner a-ref})
                                 :seon.agent.todo/todos first :seon.agent.todo/id)]
                      (-> (todo/complete! {:seon.agent.todo/id id})
                          (.then (fn [{ok? :seon.agent.todo/ok?}]
                                   (is (true? ok?))
                                   (is (inst? (:seon.agent.todo/completed-at
                                                (d/pull @conn '[*] [:seon.agent.todo/id id])))
                                       "completed-at stamped")
                                   (is (= ["second"]
                                          (open-titles
                                            (todo/list-open {:seon.agent.todo/owner a-ref})))
                                       "done item left the derived view")
                                   (todo/complete! {:seon.agent.todo/id id})))
                          (.then (fn [{ok? :seon.agent.todo/ok?}]
                                   (is (true? ok?) "already-done is idempotent")
                                   (todo/reopen! {:seon.agent.todo/id id})))
                          (.then (fn [{ok? :seon.agent.todo/ok?}]
                                   (is (true? ok?))
                                   (is (nil? (:seon.agent.todo/completed-at
                                               (d/pull @conn '[*] [:seon.agent.todo/id id])))
                                       "reopen! RETRACTED completed-at")
                                   (is (= ["first (oldest)" "second"]
                                          (open-titles
                                            (todo/list-open {:seon.agent.todo/owner a-ref})))
                                       "reopened item is back, still oldest first"))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest complete-unknown-id-is-an-envelope
  (async done
    (-> (with-conn
          (fn [_]
            (-> (todo/complete! {:seon.agent.todo/id "zzz-0000000000"})
                (.then (fn [{ok? :seon.agent.todo/ok? error :seon.agent.todo/error}]
                         (is (false? ok?))
                         (is (re-find #"list-open" error) "points at the fix"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest block-is-empty-when-no-open-work
  (async done
    (-> (with-conn
          (fn [conn]
            (is (= "" (todo/open-todos-block @conn a-ref))
                "no open items → empty block, the section vanishes")
            (is (= "" (todo/open-todos-block @conn [:seon.agent/id "ghost"]))
                "unknown owner → empty block, not a throw")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest section-tolerates-absent-db
  ;; The composer-input contract: `:seon.db/db` is the render snapshot
  ;; when present, and ABSENT db defaults to the current conn — the
  ;; same convention as every other substrate section fn. Regression
  ;; for the [open-todos] render-failed crash-loop (C-14 smell 1,
  ;; 2026-06-11): nil db reached open-todos-block's instrumented
  ;; :catn slot and every render printed :malli.core/invalid-input.
  (async done
    (-> (with-conn
          (fn [conn]
            (-> (todo/add! {:seon.agent.todo/title "live item"
                            :seon.agent.todo/owner a-ref})
                (.then
                  (fn [_]
                    (is (re-find #"live item"
                                 (todo/open-todos-section
                                   {:seon.db/db @conn :seon.agent/id a-id}))
                        "db present → renders against that snapshot")
                    (is (re-find #"live item"
                                 (todo/open-todos-section
                                   {:seon.agent/id a-id}))
                        "db absent → defaults to the current conn, no throw")
                    (is (= "" (todo/open-todos-section
                                {:seon.agent/id b-id}))
                        "other agent, no items → empty, section vanishes"))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
