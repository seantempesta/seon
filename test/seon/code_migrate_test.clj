(ns seon.code-migrate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.migrate :as migrate]
            [seon.code.rewrite :as rw]
            [seon.schema :as schema]
            [seon.schema.datahike :as sdh]))

(deftest rewrite-clj-source-cst-fidelity-test
  (testing "namespaced maps rewrite qualifier and keys without corrupting unqualified values or comments"
    (let [src ";; leading comment :seon.code.var/name\n(def m #:seon.code.var{:name :local-val :seon.code.var/ns 'foo :other/k 1})\n(def s \"prose mentioning :seon.code.var/name\")\n"
          res (rw/rewrite-clj-source src {:attr-ns-renames {"seon.code.var" "seon.var"}})]
      (is (:ok? res))
      (is (:changed? res))
      (is (str/includes? (:src res) ";; leading comment :seon.code.var/name"))
      (is (str/includes? (:src res) "#:seon.var{:name :local-val :seon.var/ns 'foo :other/k 1}"))
      (is (str/includes? (:src res) "\"prose mentioning :seon.code.var/name\""))
      (is (= 1 (count (:string-mentions res))))))

  (testing "individual attr moving out of a namespaced map becomes explicitly qualified"
    (let [src "#:seon.old{:keep 1 :moved :keep-val}"
          res (rw/rewrite-clj-source src {:attr-renames {:seon.old/moved :seon.other/moved2}})]
      (is (:ok? res))
      (is (= "#:seon.old{:keep 1 :seon.other/moved2 :keep-val}" (:src res)))))

  (testing "aliases, refers, auto-resolved keywords, and qualified symbols rewrite accurately"
    (let [src "(ns app.core\n  (:require [seon.old :as o :refer [f]]))\n(defn run [x]\n  (let [a (o/f x)\n        b (f ::o/k :seon.old/k)]\n    [a b 'seon.old seon.old/f]))\n"
          res (rw/rewrite-clj-source
               src
               {:ns-renames {'seon.old 'seon.new}
                :var-renames {'seon.old/f 'seon.new/g}
                :attr-renames {:seon.old/k :seon.new/k2}})]
      (is (:ok? res))
      (is (str/includes? (:src res) "[seon.new :as o :refer [g]]"))
      (is (str/includes? (:src res) "(o/g x)"))
      (is (str/includes? (:src res) "(g ::o/k2 :seon.new/k2)"))
      (is (str/includes? (:src res) "'seon.new seon.new/g"))))

  (testing "opt-in :rewrite-strings? rewrites string literals cleanly and bad syntax never throws"
    (let [res (rw/rewrite-clj-source "(def d \"Uses :seon.old/a\")"
                                     {:attr-ns-renames {"seon.old" "seon.new"}
                                      :rewrite-strings? true})
          bad (rw/rewrite-clj-source "(defn broken [" {:ns-renames {'a 'b}})]
      (is (= "(def d \"Uses :seon.new/a\")" (:src res)))
      (is (false? (:ok? bad)))
      (is (= :parse-error (:error bad))))))

(deftest rewrite-doc-and-edn-test
  (let [plan {:ns-renames {'seon.agents 'seon.agent}
              :attr-ns-renames {"seon.code.var" "seon.var"}
              :doc-renames {"docs/old.md" "docs/new.md"}}
        md "See [old](docs/old.md): `:seon.code.var/name`, `:seon.code.var/*`, `seon.agents/run`, `seon.agents-extra`."
        out (rw/rewrite-doc-text md plan)
        edn-out (rw/rewrite-edn-data
                 {:rule [:db :e :seon.code.var/name '?v]
                  :fn 'seon.agents/run
                  :ns 'seon.agents}
                 plan)]
    (is (str/includes? out "docs/new.md"))
    (is (str/includes? out "`:seon.var/name`"))
    (is (str/includes? out "`:seon.var/*`"))
    (is (str/includes? out "`seon.agent/run`"))
    (is (str/includes? out "`seon.agents-extra`"))
    (is (= {:rule [:db :e :seon.var/name '?v]
            :fn 'seon.agent/run
            :ns 'seon.agent}
           edn-out))))

(defn- fresh-mem-conn []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? false}
        _ (d/create-database cfg)
        conn (d/connect cfg)
        reg (:registry @schema/base-registry)
        decls (sdh/declarations reg)]
    (d/transact conn {:tx-data decls})
    conn))

(deftest end-to-end-graph-rewrite-and-data-migration-test
  (let [conn (fresh-mem-conn)
        old-src "(ns seon.demo.old)\n(defn greet [m] (:seon.demo.old/label m))\n"
        caller-src "(ns seon.demo.caller\n  (:require [seon.demo.old :as old]))\n(defn call-it [m] (old/greet m))\n"
        doc-src "# Demo\nUses `seon.demo.old/greet` and `:seon.demo.old/label`.\n"]
    ;; Register synthetic old attribute schema & install its DH attr
    (let [{:keys [entity dh-decls]}
          (schema/validate-schema-registration!
           @conn {:key :seon.demo.old/label
                  :form [:string {:seon.db/type :db.type/string :seon.db/index true}]})]
      (when (seq dh-decls) (d/transact conn {:tx-data dh-decls}))
      (d/transact conn {:tx-data [entity]}))
    ;; Import code, docs, rule, task, agent with focus ref, and a live entity holding old attr
    (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/old.clj" old-src false)})
    (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/caller.clj" caller-src false)})
    (d/transact conn {:tx-data (code/file-import-tx @conn "docs/demo.md" doc-src false)})
    (d/transact conn {:tx-data [{:seon.rule/id :rule.demo/test
                                 :seon.rule/stratum 10
                                 :seon.rule/enabled? true
                                 :seon.rule/when [[:db '?e :seon.demo.old/label '?v]]
                                 :seon.rule/assert [[:fact '?e :seen '?v]]}
                                {:seon.task/id :seon.demo/t1
                                 :seon.task/title "Demo task"
                                 :seon.task/scope 'seon.demo.old
                                 :seon.task/tests #{'seon.demo.old/greet}}
                                {:seon.agent/id :agent/demo
                                 :seon.agent/scope 'seon.demo.old
                                 :seon.agent/focus [:seon.code.ns/name 'seon.demo.old]
                                 :seon.agent/render-fn 'seon.demo.old/greet}
                                {:seon.demo.old/label "hello-world"}]})
    ;; Collision check catches existing target collision
    (let [col (migrate/plan-rewrite @conn {:ns-renames {'seon.demo.old 'seon.demo.caller}})]
      (is (false? (:ok? col)))
      (is (= :ns-collision (:kind (first (:collisions col))))))
    ;; Execute full graph rewrite + live data migration (without polluting running JVM namespaces)
    (let [plan {:ns-renames {'seon.demo.old 'seon.demo.new}
                :attr-renames {:seon.demo.old/label :seon.demo.new/title}
                :migrate-data? true
                :reload? false}
          dry (migrate/apply-rewrite! conn (assoc plan :dry-run? true))
          res (migrate/apply-rewrite! conn plan)
          db @conn]
      (is (:dry-run? dry))
      (is (:ok? dry))
      (is (= 2 (get-in dry [:counts :namespaces])))
      (is (:ok? res))
      (is (= ['seon.demo.old] (:retracted-nss res)))
      (is (nil? (:seon.code.ns/name (d/pull db '[:seon.code.ns/name] [:seon.code.ns/name 'seon.demo.old]))))
      (is (= "src/seon/demo/new.clj" (:seon.code.ns/path (d/pull db '[:seon.code.ns/path] [:seon.code.ns/name 'seon.demo.new]))))
      (is (str/includes? (code/render-path db "src/seon/demo/caller.clj") "[seon.demo.new :as old]"))
      (is (str/includes? (code/render-path db "docs/demo.md") "seon.demo.new/greet"))
      (is (str/includes? (code/render-path db "docs/demo.md") ":seon.demo.new/title"))
      (is (= [[:db '?e :seon.demo.new/title '?v]]
             (:seon.rule/when (d/pull db '[:seon.rule/when] [:seon.rule/id :rule.demo/test]))))
      (is (= 'seon.demo.new
             (:seon.task/scope (d/pull db '[:seon.task/scope] [:seon.task/id :seon.demo/t1]))))
      (let [ag (d/pull db '[:seon.agent/scope :seon.agent/render-fn {:seon.agent/focus [:seon.code.ns/name]}]
                       [:seon.agent/id :agent/demo])]
        (is (= 'seon.demo.new (:seon.agent/scope ag)))
        (is (= 'seon.demo.new/greet (:seon.agent/render-fn ag)))
        (is (= 'seon.demo.new (get-in ag [:seon.agent/focus :seon.code.ns/name]))))
      ;; Live entity datom migrated from :seon.demo.old/label -> :seon.demo.new/title
      (is (= 1 (get-in res [:data-migration :migrated])))
      (is (= ["hello-world"] (mapv :v (d/datoms db :aevt :seon.demo.new/title))))
      (is (empty? (d/datoms db :aevt :seon.demo.old/label)))
      ;; Scalar -> :db.type/ref upgrade handles both valid and dangling targets cleanly
      (d/transact conn {:tx-data [{:db/ident :seon.demo.new/ns-sym
                                   :db/valueType :db.type/symbol
                                   :db/cardinality :db.cardinality/one}
                                  {:db/ident :seon.demo.new/ns-ref
                                   :db/valueType :db.type/ref
                                   :db/cardinality :db.cardinality/one}]})
      (d/transact conn {:tx-data [{:seon.demo.new/ns-sym 'seon.demo.new}
                                  {:seon.demo.new/ns-sym 'seon.does.not.exist}]})
      (let [rm (migrate/apply-migrate-data!
                conn
                {:ref-migrations {:seon.demo.new/ns-sym {:to :seon.demo.new/ns-ref
                                                         :lookup-attr :seon.code.ns/name}}})
            db2 @conn]
        (is (:ok? rm))
        (is (= 1 (:migrated rm)))
        (is (= 1 (count (:dangling rm))))
        (is (= 'seon.demo.new
               (:seon.code.ns/name (d/pull db2 '[:seon.code.ns/name] (:v (first (d/datoms db2 :aevt :seon.demo.new/ns-ref)))))))
        (let [val-res (migrate/validate-migration db2 {:check-health? false})]
          (is (:ok? val-res))
          (is (empty? (:missing-renderers val-res))))))))

(deftest ^{:cost/ticks 25000000} apply-overlay-namespace-rename-rebinds-aliases-test
  (testing "apply-overlay! clears stale aliases on #ns reload, rebinds caller fns, and removes retracted ns"
    (with-test-store [{:keys [conn]}]
      (try
        (let [tx1 (concat
                   (code/file-import-tx @conn "src/tmp/old_lib.clj" "(ns tmp.old-lib)\n(defn greet [x] (str \"old:\" x))" false)
                   (code/file-import-tx @conn "src/tmp/caller_lib.clj" "(ns tmp.caller-lib (:require [tmp.old-lib :as lib]))\n(defn call-it [x] (lib/greet x))" false))]
          (d/transact conn {:tx-data tx1})
          (code/apply-overlay! @conn {:upserts (vec (concat (code/ns-forms @conn 'tmp.old-lib)
                                                            (code/ns-forms @conn 'tmp.caller-lib)))
                                      :retracted-keys #{}} false)
          (is (= "old:hi" ((resolve 'tmp.caller-lib/call-it) "hi")))
          (let [res (mig/apply-rewrite! conn {:ns-renames {'tmp.old-lib 'tmp.new-lib} :reload? true})]
            (is (true? (:ok? res)))
            (is (empty? (:load-errors res)))
            (is (nil? (find-ns 'tmp.old-lib)))
            (is (some? (find-ns 'tmp.new-lib)))
            (is (= "old:hi" ((resolve 'tmp.caller-lib/call-it) "hi")))))
        (finally
          (doseq [n ['tmp.caller-lib 'tmp.new-lib 'tmp.old-lib]]
            (when (find-ns n) (remove-ns n))))))))
