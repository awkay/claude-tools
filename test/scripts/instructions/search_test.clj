(ns scripts.instructions.search-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [scripts.instructions.db :as idb]
   [scripts.instructions.search :as search]))

(def ^:dynamic *db* nil)

(defn- tmp-db-path []
  (str (System/getProperty "java.io.tmpdir") "/instructions-test-"
       (System/currentTimeMillis) "-" (rand-int 100000) ".db"))

(defn- fresh-db [f]
  (let [path (tmp-db-path)]
    (try
      (idb/open path)
      (binding [*db* path] (f))
      (finally
        (doseq [suffix ["" "-wal" "-shm"]]
          (io/delete-file (str path suffix) true))))))

(use-fixtures :each fresh-db)

(defn- seed! []
  (idb/upsert-doc! *db* {:path "fulcro/mutations.md" :sha "1" :root-dir "."
                         :title "Fulcro Mutations"
                         :summary "How to write Fulcro mutations"
                         :explains ["write a fulcro mutation"]
                         :tags ["fulcro" "mutation"]
                         :body "writing mutations" :model "haiku"})
  (idb/upsert-doc! *db* {:path "fulcro/basics.md" :sha "2" :root-dir "."
                         :title "Fulcro Basics"
                         :summary "Fulcro fundamentals"
                         :explains ["understand fulcro state"]
                         :tags ["fulcro"]
                         :body "fulcro basics text" :model "haiku"})
  (idb/upsert-doc! *db* {:path "clojure/repl.md" :sha "3" :root-dir "."
                         :title "Clojure REPL"
                         :summary "Working with the REPL"
                         :explains ["start a clojure repl"]
                         :tags ["clojure" "repl"]
                         :body "repl content" :model "haiku"})
  (idb/replace-edges! *db* "fulcro/mutations.md"
                      [{:dst "fulcro/basics.md" :kind "prereq"    :confidence 0.9}
                       {:dst "clojure/repl.md"  :kind "companion" :confidence 0.6}]))

(deftest grouped-output
  (seed!)
  (let [out (with-out-str (search/search! "write mutation" {:db *db* :limit 5 :mode :and}))]
    (testing "shows matches section with direct hit"
      (is (str/includes? out "matches:"))
      (is (str/includes? out "fulcro/mutations.md")))
    (testing "shows prereqs section with the prereq edge target"
      (is (str/includes? out "you may also need"))
      (is (str/includes? out "fulcro/basics.md"))
      (is (str/includes? out "prereq of fulcro/mutations.md")))
    (testing "shows see-also for companion"
      (is (str/includes? out "see also"))
      (is (str/includes? out "clojure/repl.md")))))

(deftest show-output
  (seed!)
  (let [out (with-out-str (search/show! "fulcro/mutations.md" {:db *db*}))]
    (is (str/includes? out "fulcro/mutations.md"))
    (is (str/includes? out "Fulcro Mutations"))
    (is (str/includes? out "prereq"))
    (is (str/includes? out "fulcro/basics.md"))
    (is (str/includes? out "write a fulcro mutation"))))

(deftest no-matches
  (let [out (with-out-str (search/search! "nothing here at all" {:db *db*}))]
    (is (str/includes? out "(no matches)"))))
