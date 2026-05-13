(ns scripts.lib.embeddings-test
  "Pure-function tests for the embeddings layer: encoding round-trip, dot
   product, RRF fusion. Network and SQLite paths are exercised end-to-end
   from the CLI, not here."
  (:require
   [clojure.test :refer [deftest is testing]]
   [scripts.lib.embeddings :as emb]))

(deftest blob-round-trip
  (testing "base64 round-trip preserves a normalized vector to ~float32 precision"
    (let [v       [1.0 2.0 2.0 1.0]
          encoded (emb/vec->blob-str v)
          decoded (emb/blob-str->floats encoded)]
      (is (= 4 (alength decoded)))
      ;; The stored vector is L2-normalized: 1+4+4+1 = 10, norm = sqrt 10
      (let [n (Math/sqrt 10.0)
            expect (mapv #(/ % n) v)]
        (dotimes [i 4]
          (is (< (Math/abs (- (aget decoded i) (double (nth expect i)))) 1e-6)))))))

(deftest dot-is-cosine-for-normalized
  (let [a (-> (emb/vec->blob-str [1.0 0.0 0.0]) emb/blob-str->floats)
        b (-> (emb/vec->blob-str [0.0 1.0 0.0]) emb/blob-str->floats)
        c (-> (emb/vec->blob-str [1.0 1.0 0.0]) emb/blob-str->floats)]
    (is (< (Math/abs (emb/dot a b)) 1e-6) "orthogonal vectors")
    (is (< (Math/abs (- (emb/dot a a) 1.0)) 1e-6) "self-similarity is 1")
    (is (< (Math/abs (- (emb/dot a c) (/ 1.0 (Math/sqrt 2.0)))) 1e-6))))

(deftest rrf-fuses-lists
  (testing "RRF favors items that rank highly in BOTH lists"
    (let [lex [:a :b :c :d]
          sem [:c :b :a :e]
          fused (emb/rrf-merge [lex sem])]
      ;; :b ranks 2nd in both; :a is 1st-then-3rd; :c is 3rd-then-1st
      ;; with k=60 their scores are close; we only assert the items unique to
      ;; one list rank below items in both.
      (is (= #{:a :b :c} (set (take 3 fused))))
      (is (#{:d :e} (last fused))))))

(deftest fn-row->embed-text-uses-llm-fields
  (let [t (emb/fn-row->embed-text
           {:qualified_name "my.ns/foo"
            :arglists_edn   "([x])"
            :docstring      "doc"
            :description_llm "what it does"
            :return_description_llm "a number"
            :tags_llm       "[\"a\",\"b\"]"
            :domain_signals_llm nil
            :arg_descriptions_llm "[{\"name\":\"x\",\"desc\":\"the x\"}]"})]
    (is (re-find #"my\.ns/foo" t))
    (is (re-find #"what it does" t))
    (is (re-find #"tags: a, b" t))
    (is (re-find #"x: the x" t))))

(deftest content-sha-changes-with-model
  (is (not= (emb/content-sha "m1" "hello")
            (emb/content-sha "m2" "hello"))))

(deftest top-k-pack-finds-closest
  (testing "dot-product over a packed corpus ranks the most-similar vector first"
    (let [;; three orthogonal-ish vectors, all L2-normalized
          v1 (-> (emb/vec->blob-str [1.0 0.0 0.0]) emb/blob-str->floats)
          v2 (-> (emb/vec->blob-str [0.0 1.0 0.0]) emb/blob-str->floats)
          v3 (-> (emb/vec->blob-str [0.0 0.0 1.0]) emb/blob-str->floats)
          ;; pack manually: ids [10 20 30], vecs concatenated
          all (float-array 9)
          _ (dotimes [i 3] (aset all i (aget v1 i)))
          _ (dotimes [i 3] (aset all (+ 3 i) (aget v2 i)))
          _ (dotimes [i 3] (aset all (+ 6 i) (aget v3 i)))
          pack {:ids (long-array [10 20 30]) :vecs all :dim 3 :n 3}
          ;; query closer to v2
          q   (-> (emb/vec->blob-str [0.1 0.9 0.0]) emb/blob-str->floats)
          hits (emb/top-k-pack q pack 3)]
      (is (= 20 (:function_id (first hits))) "closest is v2")
      (is (= [20 10 30] (mapv :function_id hits))))))
