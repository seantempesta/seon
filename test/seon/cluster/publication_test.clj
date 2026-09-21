(ns seon.cluster.publication-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.fs :as fs]
            [seon.test-support :as support]))

(deftest previous-file-artifacts-derive-from-published-facts
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           manifest (functions/database-manifest database (fs/source-directory)
                                                 ["src" "test"] ["src/my/note.clj"])
           artifacts (:seon.fn.manifest/artifacts manifest)
           declarations (filter :seon.fn/sym (:seon.fn.file/rows (first artifacts)))]
       (is (= ["src/my/note.clj"] (mapv :seon.fn.file/relative-path artifacts)))
       (is (= #{'my.note/add! 'my.note/forget! 'my.note/notes}
              (set (map :seon.fn/sym declarations))))
       (is (every? #(not (contains? % :seon.fn/arities)) declarations)
           "authored declaration comparison does not acquire compiled arities")
       (doseq [row declarations]
         (is (= (:seon.fn/source row)
                (:seon.fn/source (db/pull database [:seon.fn/source]
                                         [:seon.fn/sym (:seon.fn/sym row)])))))
       (is (empty? (:seon.fn.manifest/declaration-digests manifest))
           "an ordinary source edit does not hash every schema declaration")))))
