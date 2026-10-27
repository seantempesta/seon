(let [d* datahike.api/datoms ent datahike.api/entity
      db @(:conn sys)
      out "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/export/"
      es (mapv :e (d* db :aevt :seon.vector/f16))
      vname (fn [s] (str s))
      info (fn [e] (let [x (ent db e)]
                     (cond (:form/src x) (let [vs (:var/_form x) v (first vs)]
                                           {:kind "form" :name (if v (str (:var/name v)) (str "form:" e)) :vk (some-> v :var/kind name)
                                            :names (mapv (comp str :var/name) vs)
                                            :ns (some-> (:form/ns x) :ns/name str)
                                            :doc (some-> v :var/doc) :text (:form/src x)
                                            :refs (mapv str (:form/refs x)) :quoted (mapv str (:form/quoted x))
                                            :keywords (mapv str (:form/keywords x))})
                           (:seon.section/text x) {:kind "section" :name (:seon.section/address x) :text (:seon.section/text x)
                                                   :heading (:seon.section/heading x) :lead (:seon.section/lead x)
                                                   :names (mapv str (:seon.section/names x))
                                                   :sec-refs (mapv :seon.section/address (:seon.section/references x))
                                                   :parent (some-> (:seon.section/parent x) :seon.section/address)}
                           (:schema/form x) {:kind "schema" :name (str (:schema/key x)) :text (pr-str (:schema/form x))}
                           (:seon.task/plan x) {:kind "task" :name (str (:seon.task/id x)) :text (:seon.task/plan x)
                                                :task-refs (mapv str (:seon.task/references x))
                                                :task-secs (mapv str (:seon.task/sections x))}
                           :else {:kind "other" :name (str e)})))]
  (spit (str out "items.edn")
        (with-out-str (doseq [e es] (prn (assoc (info e) :e e)))))
  (spit (str out "vecs.edn")
        (with-out-str (doseq [e es] (prn (vec (:v (first (d* db :eavt e :seon.vector/f16))))))))
  (count es))
