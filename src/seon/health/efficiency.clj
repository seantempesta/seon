(defn ns-in-scope?
  "True when namespace symbol `nsym` belongs to `scope` (exact match or descendant `scope.*`,
   with `'seon` matching all `seon.*` namespaces)."
  [scope nsym]
  (when (and scope nsym)
    (let [sc (name scope)
          ns-s (name nsym)]
      (or (= ns-s sc)
          (str/starts-with? ns-s (str sc "."))))))

(defn- full-db-scan-form? [x]
  (and (seq? x) (symbol? (first x))
       (let [nm (name (first x))]
         (or (and (= "datoms" nm) (= :aevt (nth x 2 nil)))
             (= "q" nm)))))

(defn- nested-db-scan-in-form?
  "True when a form executes `d/datoms :aevt` or `d/q` inside a loop body (`O(N * M)`)."
  ([x] (nested-db-scan-in-form? x 0))
  ([x depth]
   (cond
     (full-db-scan-form? x) (pos? depth)
     (and (seq? x) (symbol? (first x)))
     (let [nm (name (first x))]
       (cond
         (contains? #{"for" "doseq"} nm)
         (let [binds (second x)
               body (nnext x)
               bind-hit (when (vector? binds)
                          (loop [bs (seq binds) d depth]
                            (when bs
                              (let [k (first bs) v (second bs)]
                                (if (contains? #{:let :when :while} k)
                                  (or (nested-db-scan-in-form? v d) (recur (nnext bs) d))
                                  (or (nested-db-scan-in-form? v d) (recur (nnext bs) (inc d))))))))]
           (or bind-hit (boolean (some #(nested-db-scan-in-form? % (inc depth)) body))))
         (contains? #{"map" "mapv" "mapcat" "keep" "reduce" "run!" "filter" "filterv" "remove"} nm)
         (or (nested-db-scan-in-form? (second x) (inc depth))
             (boolean (some #(nested-db-scan-in-form? % depth) (nnext x))))
         :else
         (boolean (some #(nested-db-scan-in-form? % depth) (rest x)))))
     (coll? x) (boolean (some #(nested-db-scan-in-form? % depth) x))
     :else false)))

(defn static-efficiency-warnings
  "Scans non-test `:seon.code.form/src` (using indexed `:avet :seon.code.form/ns` when restricted to
   `scope` subtree!) to flag:
   1. `:oversized-namespace` (`> 8000` estimated tokens — split into cohesive child namespaces).
   2. `:nested-db-scan` (`d/datoms :aevt` or `d/q` inside a loop body — classic `O(N * M)` waste)."
  ([db] (static-efficiency-warnings db nil))
  ([db scope]
   (if-not (and db (contains? (:schema db) :seon.code.form/src))
     []
     (let [br (get-in db [:config :branch] :main)
           chars-by-ns (volatile! {})
           nested (volatile! [])
           check-one! (fn [n k s]
                        (vswap! chars-by-ns update n (fnil + 0) (count s))
                        (when (or (str/includes? s ":aevt") (str/includes? s "/q "))
                          (let [form (try (read-string s) (catch Throwable _ nil))]
                            (when (and k form (not= k "seon.health/schema-violations") (nested-db-scan-in-form? form 0))
                              (vswap! nested conj
                                      (cond-> {:class :nested-db-scan
                                               :severity :warning
                                               :kind :static
                                               :ns n
                                               :subject k
                                               :branch br
                                               :message (str "Nested full DB scan (`d/datoms :aevt` or `d/q`) inside a loop in " k
                                                             ". Hoist the scan outside the loop or use an indexed `:avet` lookup.")}
                                        (not (str/includes? k "#")) (assoc :var (symbol k))))))))]
       (if scope
         (doseq [nsym (->> (d/datoms db :aevt :seon.code.ns/name)
                           (map :v)
                           (filter #(and (not (str/ends-with? (name %) "-test")) (ns-in-scope? scope %))))
                 dt (d/datoms db :avet :seon.code.form/ns nsym)
                 :let [p (d/pull db '[:seon.code.form/key :seon.code.form/src] (:e dt))]]
           (check-one! nsym (:seon.code.form/key p) (str (:seon.code.form/src p))))
         (let [ns-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/ns))
               key-by-e (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))]
           (doseq [dt (d/datoms db :aevt :seon.code.form/src)
                   :let [e (:e dt)
                         n (get ns-by-e e)]
                   :when (and n (not (str/ends-with? (name n) "-test")))]
             (check-one! n (get key-by-e e) (str (:v dt))))))
       (let [oversized (->> @chars-by-ns
                            (keep (fn [[n ch]]
                                    (let [toks (quot ch 4)]
                                      (when (> toks 8000)
                                        {:class :oversized-namespace
                                         :severity :warning
                                         :kind :static
                                         :ns n
                                         :subject (str n)
                                         :branch br
                                         :message (str "Namespace " n " is ~" toks " tokens (> 8k cap). "
                                                       "Split into cohesive 2k-6k tok child namespaces so specialists load cleanly.")}))))
                            (sort-by :subject))]
         (vec (concat oversized (sort-by :subject @nested))))))))
