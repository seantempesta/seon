(ns seon.pod-disk-conn-test
  "Targeted coverage for the V0 pod's persistent on-disk agent conn
   (Track A1, 2026-06-09). The pod conn moved off `:backend :memory`
   onto datahike's konserve `:file` backend so agent runs survive a
   `bin/seon restart pod` and past runs stay reviewable.

   These tests exercise the production helpers in `seon.client`
   directly:

     - `open-disk-conn!` creates the database on first open of a dir
       and connects-only on subsequent opens of the SAME dir
       (idempotent create-vs-connect);
     - data transacted through one conn is readable from an
       independent reopen of the same dir (it's genuinely on disk);
     - the run-id is a human-readable, filesystem-safe timestamp.

   Each test uses a `__test__`-prefixed run-id under the gitignored
   `data/seon-pod/` base and removes its dir afterward so the suite is
   self-cleaning (production runs are persisted; test runs are not).

   Run interactively via the in-pod runner:
     (seon.test.runner/run! {:seon.test.runner/ns 'seon.pod-disk-conn-test})"
  (:require
    [clojure.string :as str]
    [cljs.test :as t :refer [deftest is async]]
    [datahike.api :as d]
    [seon.client :as client]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- unique-test-rid
  "A `__test__`-prefixed run-id, unique per call so concurrent or
   repeated runs never collide on a dir."
  []
  (str "__test__-" (.toISOString (js/Date.)) "-" (rand-int 1000000)))

(defn- rm-rf! [rid]
  (.rmSync fs (.join path client/pod-store-base rid)
           #js {:recursive true :force true}))

;; ---------------------------------------------------------------------------
;; create-vs-connect: first open CREATES, second open of the same dir
;; CONNECTS (no clobber), and the transacted datom survives the reopen.
;; ---------------------------------------------------------------------------

(deftest open-disk-conn-creates-then-connects-and-persists
  (let [rid (unique-test-rid)]
    (async done
      (-> (client/open-disk-conn! rid)
          (.then
            (fn [conn]
              ;; Minimal schema + one identity-keyed datom.
              (-> (d/transact! conn [{:db/ident       :pod-disk-test/marker
                                      :db/valueType   :db.type/string
                                      :db/cardinality :db.cardinality/one
                                      :db/unique      :db.unique/identity}])
                  (.then (fn [_] (d/transact! conn [{:pod-disk-test/marker "alive"}])))
                  (.then (fn [_]
                           (is (= #{["alive"]}
                                  (d/q '[:find ?m :where [_ :pod-disk-test/marker ?m]] @conn))
                               "datom queryable on the original conn")))
                  (.then (fn [_] (d/release conn))))))
          ;; Reopen the SAME dir: open-disk-conn! must take the
          ;; connect-only path (the store already exists) and the datom
          ;; must still be there — proving it's on disk.
          (.then (fn [_] (client/open-disk-conn! rid)))
          (.then (fn [conn2]
                   (is (= #{["alive"]}
                          (d/q '[:find ?m :where [_ :pod-disk-test/marker ?m]] @conn2))
                       "datom survives an independent reopen of the same dir")
                   (is (.existsSync fs (.join path client/pod-store-base rid))
                       "the session directory exists on disk")))
          (.then (fn [_] (rm-rf! rid) (done)))
          (.catch (fn [e]
                    (rm-rf! rid)
                    (is false (str "threw — " (.-message e)))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; The conn satisfies the substrate's boot precondition (:keep-history?
;; must be on — assert-preconditions! re-asserts it at start-agent!).
;; ---------------------------------------------------------------------------

(deftest open-disk-conn-keeps-history
  (let [rid (unique-test-rid)]
    (async done
      (-> (client/open-disk-conn! rid)
          (.then (fn [conn]
                   (is (true? (-> @conn :config :keep-history?))
                       ":keep-history? is on (boot precondition)")))
          (.then (fn [_] (rm-rf! rid) (done)))
          (.catch (fn [e]
                    (rm-rf! rid)
                    (is false (str "threw — " (.-message e)))
                    (done)))))))

;; ---------------------------------------------------------------------------
;; run-id shape: human-readable, filesystem-safe (no ':' or '.').
;; ---------------------------------------------------------------------------

(deftest run-id-is-human-readable-and-fs-safe
  (let [rid (#'client/run-id)]
    (is (string? rid) "run-id is a string")
    (is (not (str/includes? rid ":")) "no ':' (invalid in dir names on some OSes)")
    (is (not (str/includes? rid ".")) "no '.' from the ISO millis fraction")
    (is (re-find #"^\d{4}-\d{2}-\d{2}T" rid) "starts with a readable UTC date")))
