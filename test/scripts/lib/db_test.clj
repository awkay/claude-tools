(ns scripts.lib.db-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [scripts.lib.db :as db]))

(def ^:dynamic *db* nil)

(defn- tmp-db-path []
  (str (System/getProperty "java.io.tmpdir") "/code-intel-test-"
       (System/currentTimeMillis) "-" (rand-int 100000) ".db"))

(defn- fresh-db-fixture [f]
  (let [path (tmp-db-path)]
    (try
      (db/open path)
      (binding [*db* path] (f))
      (finally
        (doseq [suffix ["" "-wal" "-shm"]]
          (io/delete-file (str path suffix) true))))))

(use-fixtures :each fresh-db-fixture)

;; -----------------------------------------------------------------------------
;; schema apply
;; -----------------------------------------------------------------------------

(deftest schema-apply-test
  (testing "open creates tables, indexes, and FTS triggers"
    (let [names (->> (db/query *db*
                               "SELECT name FROM sqlite_master ORDER BY name")
                     (map :name)
                     set)]
      (is (contains? names "functions"))
      (is (contains? names "files"))
      (is (contains? names "functions_fts"))
      (is (contains? names "functions_ai"))
      (is (contains? names "functions_ad"))
      (is (contains? names "functions_au"))
      (is (contains? names "idx_functions_qname_lang")))))

;; -----------------------------------------------------------------------------
;; upsert
;; -----------------------------------------------------------------------------

(def sample-record
  {:ns 'foo :name 'bar :qualified-name "foo/bar" :lang "clj"
   :sha "abc123"
   :filename "foo.clj" :line-start 1 :line-end 5 :col-start 1 :col-end 30
   :arglists-edn "([x])" :arities {:fixed [1] :varargs nil}
   :defined-by 'clojure.core/defn :private? false
   :docstring "Returns x."
   :pure-heuristic? true :pure-heuristic-reasons []
   :types-edn nil
   :caller-count 0 :caller-namespaces [] :callee-namespaces [] :example-callers []
   :description-llm "A test fn." :arg-descriptions-llm [{:name "x" :desc "value"}]
   :return-description-llm "x" :tags-llm ["util"] :domain-signals-llm []
   :general-purpose-score 0.9 :confidence 0.95 :analyzed-by-model "haiku"})

(deftest upsert-test
  (testing "insert"
    (is (= :inserted (db/upsert-function! *db* sample-record)))
    (is (= 1 (-> (db/query *db* "SELECT COUNT(*) AS n FROM functions") first :n))))

  (testing "second call with same sha is :unchanged"
    (db/upsert-function! *db* sample-record)
    (is (= :unchanged (db/upsert-function! *db* sample-record))))

  (testing "second call with different sha is :updated"
    (db/upsert-function! *db* sample-record)
    (is (= :updated (db/upsert-function! *db* (assoc sample-record :sha "newhash"))))
    (is (= 1 (-> (db/query *db* "SELECT COUNT(*) AS n FROM functions") first :n)))
    (is (= "newhash"
           (-> (db/query *db* "SELECT sha FROM functions WHERE qualified_name = 'foo/bar'")
               first :sha)))))

(deftest cljc-clj-cljs-coexist-test
  (testing "rows with same qualified_name but different lang coexist"
    (db/upsert-function! *db* (assoc sample-record :lang "clj"  :sha "h-clj"))
    (db/upsert-function! *db* (assoc sample-record :lang "cljs" :sha "h-cljs"))
    (is (= 2 (-> (db/query *db* "SELECT COUNT(*) AS n FROM functions") first :n)))))

;; -----------------------------------------------------------------------------
;; file-sha cache
;; -----------------------------------------------------------------------------

(deftest file-sha-cache-test
  (testing "unknown file returns nil"
    (is (nil? (db/file-sha-stored *db* "/no/such/file"))))
  (testing "record + lookup"
    (db/record-file-sha! *db* "/some/file.clj" "abc")
    (is (= "abc" (db/file-sha-stored *db* "/some/file.clj"))))
  (testing "re-record updates"
    (db/record-file-sha! *db* "/some/file.clj" "abc")
    (db/record-file-sha! *db* "/some/file.clj" "xyz")
    (is (= "xyz" (db/file-sha-stored *db* "/some/file.clj"))))
  (testing "file-unchanged?"
    (db/record-file-sha! *db* "/some/file.clj" "abc")
    (is (true?  (db/file-unchanged? *db* "/some/file.clj" "abc")))
    (is (false? (db/file-unchanged? *db* "/some/file.clj" "xyz")))))

;; -----------------------------------------------------------------------------
;; FTS5 query builder + search
;; -----------------------------------------------------------------------------

(deftest fts-search-test
  (db/upsert-function! *db*
                       (assoc sample-record :qualified-name "x/serialize-profile"
                              :description-llm "Serializes profile data to an EDN string."
                              :tags-llm ["serialize" "edn"]))
  (db/upsert-function! *db*
                       (assoc sample-record :qualified-name "x/jwt-middleware"
                              :sha "sha2"
                              :description-llm "Wraps a request handler to inject a JWT token."
                              :tags-llm ["middleware" "jwt"]))

  (testing "or-mode matches when any token hits"
    (let [hits (db/fts-search *db* "serialize profile or whatever")]
      (is (some #(= "x/serialize-profile" (:qualified_name %)) hits))))

  (testing "natural-language query with stopwords still returns hits"
    (let [hits (db/fts-search *db* "how do I add JWT to requests")]
      (is (some #(= "x/jwt-middleware" (:qualified_name %)) hits))))

  (testing "and-mode requires all tokens"
    (let [no-hits (db/fts-search *db* "serialize jwt" {:mode :and})]
      (is (empty? no-hits))))

  (testing "limit option"
    (let [hits (db/fts-search *db* "edn jwt middleware" {:limit 1})]
      (is (= 1 (count hits)))))

  (testing "empty/whitespace query returns []"
    (is (= [] (db/fts-search *db* "")))
    (is (= [] (db/fts-search *db* "the a an of")))))

(deftest fts-search-filter-and-boost-test
  ;; Same description across rows so BM25 is roughly equal; ns and caller_count differ.
  (let [base (assoc sample-record :description-llm "frobnicate the widgets"
                    :tags-llm ["widget"])]
    (db/upsert-function! *db* (assoc base :ns 'my.app.core
                                     :qualified-name "my.app.core/frob-a"
                                     :sha "h1" :caller-count 0))
    (db/upsert-function! *db* (assoc base :ns 'my.lib.util
                                     :qualified-name "my.lib.util/frob-b"
                                     :sha "h2" :caller-count 0))
    (db/upsert-function! *db* (assoc base :ns 'random.ns
                                     :qualified-name "random.ns/frob-c"
                                     :sha "h3" :caller-count 50)))

  (testing "ns-filter hard-restricts results"
    (let [hits (db/fts-search *db* "frobnicate widgets" {:ns-filter ["lib"]})]
      (is (every? #(str/includes? (:ns %) "lib") hits))
      (is (some #(= "my.lib.util/frob-b" (:qualified_name %)) hits))
      (is (not-any? #(= "my.app.core/frob-a" (:qualified_name %)) hits))))

  (testing "ns-boost ranks matching ns ahead"
    (let [hits (db/fts-search *db* "frobnicate widgets"
                              {:ns-boost ["lib"] :caller-boost? false})]
      (is (= "my.lib.util/frob-b" (:qualified_name (first hits))))))

  (testing "ns-exclude drops matching rows"
    (let [hits (db/fts-search *db* "frobnicate widgets" {:ns-exclude ["lib" "random"]})]
      (is (not-any? #(str/includes? (:ns %) "lib") hits))
      (is (not-any? #(str/includes? (:ns %) "random") hits))
      (is (some #(= "my.app.core/frob-a" (:qualified_name %)) hits))))

  (testing "caller boost lifts high-caller_count rows"
    (let [hits (db/fts-search *db* "frobnicate widgets"
                              {:ns-boost [] :caller-boost? true})]
      ;; frob-c has 50 callers vs 0 on the others — should come first.
      (is (= "random.ns/frob-c" (:qualified_name (first hits)))))))
