(ns seon.code-migrate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.code.migrate :as migrate]
            [seon.code.rewrite :as rw]
            [seon.schema :as schema]
            [seon.schema.datahike :as sdh]
            [seon.test-fixture :refer [with-test-store]]
            [seon.merge :as smerge]
            [seon.store :as store]
            [seon.code.refactor :as refactor]))

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
(let [plan {:ns-renames {'demo.agents 'demo.agent}
            :attr-ns-renames {"seon.code.var" "seon.var"}
            :doc-renames {"docs/old.md" "docs/new.md"}}
      md "See [old](docs/old.md): `:seon.code.var/name`, `:seon.code.var/*`, `demo.agents/run`, `demo.agents-extra`."
      out (rw/rewrite-doc-text md plan)
      edn-out (rw/rewrite-edn-data
               {:rule [:db :e :seon.code.var/name '?v]
                :fn 'seon.agent/run
                :ns 'seon.agent}
               plan)]
  (is (str/includes? out "docs/new.md"))
  (is (str/includes? out "`:seon.var/name`"))
  (is (str/includes? out "`:seon.var/*`"))
  (is (str/includes? out "`demo.agent/run`"))
  (is (str/includes? out "`demo.agents-extra`"))
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
                                 :seon.rule/query [:find '?e '?v :where ['?e :seon.demo.old/label '?v]]
                                 :seon.rule/assert [[:fact '?e :seen '?v]]}
                                {:seon.task/id :seon.demo/t1
                                 :seon.task/title "Demo task"
                                 :seon.task/scope 'seon.demo.old
                                 :seon.task/tests #{'seon.demo.old/greet}}
                                {:seon.agent/id :agent/demo
                                 :seon.agent/scope 'seon.demo.old
                                 :seon.agent/focus [:seon.code.ns/name 'seon.demo.old]
                                 :seon.agent/pinned [[:seon.code.var/name 'seon.demo.old/greet]]}
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
      (is (= [:find '?e '?v :where ['?e :seon.demo.new/title '?v]]
      (:seon.rule/query (d/pull db '[:seon.rule/query] [:seon.rule/id :rule.demo/test]))))
      (is (= 'seon.demo.new
             (:seon.task/scope (d/pull db '[:seon.task/scope] [:seon.task/id :seon.demo/t1]))))
      (let [ag (d/pull db '[:seon.agent/scope :seon.agent/pinned {:seon.agent/focus [:seon.code.ns/name]}]
                       [:seon.agent/id :agent/demo])]
        (is (= 'seon.demo.new (:seon.agent/scope ag)))
        (is (= [[:seon.code.var/name 'seon.demo.new/greet]] (:seon.agent/pinned ag)))
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

(deftest ^{:cost/ticks 50000000} apply-overlay-namespace-rename-rebinds-aliases-test
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
          (let [res (migrate/apply-rewrite! conn {:ns-renames {'tmp.old-lib 'tmp.new-lib} :reload? true})]
            (is (true? (:ok? res)))
            (is (empty? (:load-errors res)))
            (is (nil? (find-ns 'tmp.old-lib)))
            (is (some? (find-ns 'tmp.new-lib)))
            (is (= "old:hi" ((resolve 'tmp.caller-lib/call-it) "hi")))))
        (finally
          (doseq [n ['tmp.caller-lib 'tmp.new-lib 'tmp.old-lib]]
            (when (find-ns n) (remove-ns n))))))))

(deftest ^{:cost/ticks 60000000} merge-orders-ref-retractions-before-entity-retraction-test
(testing "seon.merge/three-way retracts incoming doc refs before retracting a renamed namespace entity"
  (with-test-store [{:keys [conn cfg]}]
    (d/transact conn {:tx-data (concat
                                (code/file-import-tx @conn "src/tmp/m_old.clj" "(ns tmp.m-old)\n(defn f {:malli/schema [:=> [:cat :any] :any]} [x] x)" false)
                                [{:seon.code.doc/id "docs/m.md#s" :seon.code.doc/path "docs/m.md"
                                  :seon.code.doc/heading "M" :seon.code.doc/body "See `tmp.m-old`"
                                  :seon.code.doc/order 0
                                  :seon.code.doc/references #{[:seon.code.ns/name 'tmp.m-old]}}])})
    (store/branch! conn :main :branch/m-rename)
    (let [bconn (store/connect cfg :branch/m-rename)]
      (try
        (let [res (migrate/apply-rewrite! bconn {:ns-renames {'tmp.m-old 'tmp.m-new} :reload? false})]
          (is (true? (:ok? res)))
          (d/transact bconn {:tx-data [{:seon.code.doc/id "docs/m.md#s"
          :seon.code.doc/references #{[:seon.code.ns/name 'tmp.m-new]}}]})
          (let [plan (smerge/three-way conn cfg :branch/m-rename)]
            (is (true? (:ok? plan)))
            (d/transact conn {:tx-data (:tx-data plan)})
            (is (nil? (first (d/datoms @conn :avet :seon.code.ns/name 'tmp.m-old))))
            (is (some? (first (d/datoms @conn :avet :seon.code.ns/name 'tmp.m-new))))))
        (finally
          (d/release bconn)))))))

(deftest ^{:cost/ticks 120000000} move-vars-self-alias-stub-and-alias-remap-test
  (testing "move-vars! strips self-aliases, preserves existing from alias & :import, handles alias collisions, qualified :keys, for :let, named fn, and quoted ::kw"
    (with-test-store [{:keys [conn]}]
      (let [sub-src "(ns tmp.mv-sub
  (:require [clojure.set :as s]
            [clojure.string :refer [blank?]])
  (:import [java.util UUID]))

(defn helper [x] (if (blank? (str x)) \"\" (clojure.string/upper-case (str x))))
"
            core-src "(ns tmp.mv-core
  (:require [clojure.string :as s]
            [tmp.mv-sub :as sub]))

(defn helper [& args] (apply sub/helper args))

(defn call-sub [x] [(sub/helper x) #'sub/helper ::sub/tag ::local-kw #::sub{:a 1} (s/trim (str x)) '::sub/qtag '::qlocal])

(defn shadow-all [{:keys [:foo/helper]}] [(sub/helper helper) (for [i [1] :let [helper \"z\"]] (sub/helper helper)) ((fn helper [y] (if y (sub/helper y) :done)) \"k\")])

(defn call-stub [x] (helper x))
"]
        (d/transact conn {:tx-data (concat (code/file-import-tx @conn "src/tmp/mv_sub.clj" sub-src false)
                                           (code/file-import-tx @conn "src/tmp/mv_core.clj" core-src false))})
        (try
          (let [res (refactor/apply-move-vars! conn {:from 'tmp.mv-core :to 'tmp.mv-sub
                                                     :vars ['call-sub 'shadow-all 'call-stub]
                                                     :include-deps? true :reload? true})
                to-hdr (:seon.code.form/src (first (code/ns-forms @conn 'tmp.mv-sub)))]
            (is (true? (:ok? res)) (pr-str res))
            (is (empty? (:load-errors res)) (pr-str (:load-errors res)))
            (is (= 'sub (:alias res)))
            (is (not (some #{'helper} (:moved res))))
            ;; Preserved (:import [java.util UUID]) and avoided :as s collision with clojure.set!
            (is (str/includes? to-hdr "(:import [java.util UUID])"))
            (is (str/includes? to-hdr "[clojure.set :as s]"))
            ;; Live execution in tmp.mv-sub works with zero self-alias, quote-reader, or alias-collision errors!
            (let [out ((resolve 'tmp.mv-sub/call-sub) " hi ")]
              (is (= " HI " (first out)))
              (is (= (resolve 'tmp.mv-sub/helper) (second out)))
              (is (= :tmp.mv-sub/tag (nth out 2)))
              (is (= :tmp.mv-core/local-kw (nth out 3)))
              (is (= {:tmp.mv-sub/a 1} (nth out 4)))
              (is (= "hi" (nth out 5)))
              (is (= :tmp.mv-sub/qtag (nth out 6)))
              (is (= :tmp.mv-core/qlocal (nth out 7))))
            (is (= ["OK" ["Z"] "K"] ((resolve 'tmp.mv-sub/shadow-all) {:foo/helper "ok"})))
            (is (= "X" ((resolve 'tmp.mv-sub/call-stub) "x"))))
          (finally
            (doseq [n ['tmp.mv-core 'tmp.mv-sub]] (when (find-ns n) (remove-ns n)))))))))

(deftest ^{:cost/ticks 120000000} move-vars-existing-to-syms-and-auto-keep-private-test
  (testing "move-vars! handles existing defs & :refer in :to, rejects explicit dupes, skips existing helpers on :include-deps? true, and promotes private dynamic vars"
    (with-test-store [{:keys [conn]}]
      (let [to-src "(ns tmp.m2-sub (:require [clojure.string :refer [upper-case]]))
(defn- shared [x] (str \"[\" (upper-case (str x)) \"]\"))"
            from-src "(ns tmp.m2-core (:require [clojure.string :refer [upper-case]]))
(defn- shared [x] (str \"[\" (upper-case (str x)) \"]\"))
(def ^:private ^:dynamic *tag* \"!\")
(defn- internal-only [x] (str x *tag*))
(defn- used-by-both [x] (str x \"?\"))
(defn moved-fn [x] (str (shared x) (internal-only x) (used-by-both x) (upper-case x)))
(defn stays-fn [x] (str (used-by-both x) *tag*))"]
        (d/transact conn {:tx-data (concat (code/file-import-tx @conn "src/tmp/m2_sub.clj" to-src false)
                                           (code/file-import-tx @conn "src/tmp/m2_core.clj" from-src false))})
        (try
          ;; Explicit duplicate var in :to is cleanly rejected!
          (let [dup (refactor/move-vars-tx @conn {:from 'tmp.m2-core :to 'tmp.m2-sub :vars '[shared]})]
            (is (false? (:ok? dup)))
            (is (= :vars-already-in-target (:error dup))))
          ;; :include-deps? true pulls in internal-only, used-by-both, *tag*, skips existing `shared` in :to!
          (let [res (refactor/apply-move-vars! conn {:from 'tmp.m2-core :to 'tmp.m2-sub
                                                     :vars '[moved-fn] :include-deps? true :reload? true})
                to-rendered (code/render-path @conn "src/tmp/m2_sub.clj")]
            (is (true? (:ok? res)) (pr-str res))
            (is (empty? (:load-errors res)) (pr-str (:load-errors res)))
            (is (not (some #{'shared} (:moved res))) "existing def in :to must not be duplicated")
            (is (str/includes? to-rendered "(defn- internal-only") "unreferenced private helper stays defn-")
            (is (str/includes? to-rendered "(defn used-by-both") "from-referenced private helper promoted to defn")
            (is (= "[A]a!a?A" ((resolve 'tmp.m2-sub/moved-fn) "a")))
            (is (= "b?!" ((resolve 'tmp.m2-core/stays-fn) "b"))))
          (finally
            (doseq [n ['tmp.m2-core 'tmp.m2-sub]] (when (find-ns n) (remove-ns n)))))))))

(deftest ^{:cost/ticks 60000000} merge-drops-dangling-retracted-ref-set-entries-test
  (testing "three-way merge drops ref-set entries targeting transient entities deleted on :main (e.g. :seon.error/id in :touched-refs)"
    (with-test-store [{:keys [conn cfg]}]
      (let [err-id (random-uuid)
            ev-id (random-uuid)]
        (d/transact conn {:tx-data [{:seon.code.var/name 'demo.dref/f :seon.code.var/ns 'demo.dref :seon.code.var/form "demo.dref/f"}
                                    {:seon.agent/id :w/dref :seon.agent/branch :branch/w-dref :seon.agent/goal "test"}]})
        (store/branch! conn :main :branch/w-dref)
        ;; :main retracts transient error while branch records an eval touching it
        (d/transact conn {:tx-data [[:db/retractEntity [:seon.code.var/name 'demo.dref/f]]]})
        (let [bconn (store/connect cfg :branch/w-dref)]
          (try
            (let [ve (:e (first (d/datoms @bconn :avet :seon.code.var/name 'demo.dref/f)))
                ae (:e (first (d/datoms @bconn :avet :seon.agent/id :w/dref)))]
            (d/transact bconn {:tx-data [{:seon.agent.eval/id ev-id :seon.agent.eval/agent :w/dref :seon.agent.eval/agent-ref ae
                                          :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0 :seon.agent.eval/code "1"
                                          :seon.agent.eval/called-vars #{ve}}]}))
            (let [plan (seon.merge/three-way conn cfg :branch/w-dref)]
              (is (true? (:ok? plan)))
              ;; Transacting plan onto :main must NOT throw Nothing found for entity id [:seon.error/id ...]
              (is (some? (d/transact conn {:tx-data (:tx-data plan)})))
              (is (= :w/dref (:seon.agent.eval/agent (d/pull @conn '[:seon.agent.eval/agent] [:seon.agent.eval/id ev-id])))))
            (finally (d/release bconn))))))))

(deftest apply-overlay-defers-unmap-until-after-upserts-test
  (let [ns-a 'seon.tmp.overlay-order-a
        ns-b 'seon.tmp.overlay-order-b
        seen-bound? (atom nil)]
    (try
      (seon.code.overlay/apply-overlay!
        nil
        {:upserts [{:seon.code.form/key "seon.tmp.overlay-order-a#ns" :seon.code.form/ns ns-a :seon.code.form/order 0
                    :seon.code.form/src "(ns seon.tmp.overlay-order-a)"}
                   {:seon.code.form/key "seon.tmp.overlay-order-a/priv-helper" :seon.code.form/ns ns-a :seon.code.form/order 1
                    :seon.code.form/src "(defn- priv-helper [x] (+ x 10))"}
                   {:seon.code.form/key "seon.tmp.overlay-order-a/pub-fn" :seon.code.form/ns ns-a :seon.code.form/order 2
                    :seon.code.form/src "(defn pub-fn [x] (priv-helper x))"}]
         :retracted-keys #{}})
      (is (= 15 ((resolve 'seon.tmp.overlay-order-a/pub-fn) 5)))
      ;; Simulate move-vars!: priv-helper retracted from ns-a, moved to ns-b, pub-fn updated in ns-a;
      ;; forms-for-ns calls the STILL-LIVE pub-fn during collect-overlay-upserts!
      (seon.code.overlay/apply-overlay!
        nil
        {:upserts [{:seon.code.form/key "seon.tmp.overlay-order-b#ns" :seon.code.form/ns ns-b :seon.code.form/order 0
                    :seon.code.form/src "(ns seon.tmp.overlay-order-b)"}
                   {:seon.code.form/key "seon.tmp.overlay-order-b/priv-helper" :seon.code.form/ns ns-b :seon.code.form/order 1
                    :seon.code.form/src "(defn- priv-helper [x] (+ x 10))"}
                   {:seon.code.form/key "seon.tmp.overlay-order-b/pub-fn" :seon.code.form/ns ns-b :seon.code.form/order 2
                    :seon.code.form/src "(defn pub-fn [x] (priv-helper x))"}
                   {:seon.code.form/key "seon.tmp.overlay-order-a#ns" :seon.code.form/ns ns-a :seon.code.form/order 0
                   :seon.code.form/deps #{'seon.tmp.overlay-order-b}
                   :seon.code.form/src "(ns seon.tmp.overlay-order-a (:require [seon.tmp.overlay-order-b :as b]))"}
                   {:seon.code.form/key "seon.tmp.overlay-order-a/pub-fn" :seon.code.form/ns ns-a :seon.code.form/order 1
                   :seon.code.form/deps #{'seon.tmp.overlay-order-b/pub-fn}
                   :seon.code.form/src "(defn pub-fn [x] (b/pub-fn x))"}]
         :retracted-keys #{"seon.tmp.overlay-order-a/priv-helper"}}
        false
        (fn [_]
          (reset! seen-bound? ((resolve 'seon.tmp.overlay-order-a/pub-fn) 7))
          []))
      (is (= 17 @seen-bound?) "retracted private helper must remain bound while collect-overlay-upserts and upserts run")
      (is (= 15 ((resolve 'seon.tmp.overlay-order-a/pub-fn) 5)))
      (is (nil? (ns-resolve (find-ns ns-a) 'priv-helper)) "retracted private helper must be unmapped after upserts complete")
      (finally
        (when (find-ns ns-a) (remove-ns ns-a))
        (when (find-ns ns-b) (remove-ns ns-b))))))