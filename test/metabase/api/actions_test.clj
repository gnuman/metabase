(ns metabase.api.actions-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metabase.actions.test-util :as actions.test-util]
   [metabase.api.actions :as api.actions]
   [metabase.models.database :refer [Database]]
   [metabase.query-processor :as qp]
   [metabase.test :as mt]))
(require '[clojure.pprint :refer [pprint]])

(comment api.actions/keep-me)

(defn- mock-requests []
  [{:action       "actions/row/create"
    :request-body (assoc (mt/mbql-query categories) :create_row {:name "created_row"})
    :expect-fn    (fn [result]
                    (is (= "created_row" (get-in result [:created-row :name]))))}
   {:action       "actions/row/update"
    :request-body (assoc (mt/mbql-query categories {:filter [:= $id 1]})
                         :update_row {:name "updated_row"})
    :expected     {:rows-updated [1]}}
   {:action       "actions/row/delete"
    :request-body (mt/mbql-query categories {:filter [:= $id 1]})
    :expected     {:rows-deleted [1]}}])

(defn- row-action? [action]
  (str/starts-with? action "actions/row"))

(deftest happy-path-test
  (testing "Make sure it's possible to use known actions end-to-end if preconditions are satisfied"
    (actions.test-util/with-actions-test-data
      (mt/with-temporary-setting-values [experimental-enable-actions true]
        (mt/with-temp-vals-in-db Database (mt/id) {:settings {:database-enable-actions true}}
          (doseq [{:keys [action request-body expected expect-fn]} (mock-requests)]
            (testing action
              (let [result (mt/user-http-request :crowberto :post 200 action request-body)]
                (when expected (is (= expected result)))
                (when expect-fn (expect-fn result))))))))))

(deftest create-update-delete-test
  (testing "Make sure actions are acting on rows."
    (actions.test-util/with-actions-test-data
      (mt/with-temporary-setting-values [experimental-enable-actions true]
        (mt/with-temp-vals-in-db Database (mt/id) {:settings {:database-enable-actions true}}
          (let [[create update delete] (mock-requests)
                {created-id :id :as created-row}
                (:created-row (mt/user-http-request :crowberto :post 200 (:action create) (:request-body create)))]
            (is (= [:id :name] (keys created-row))
                "Create should return the entire row")
            (is (= "created_row" (:name created-row))
                "Create should return the correct value for name")
            (is (= "created_row" (-> (mt/rows (mt/run-mbql-query categories {:filter [:= $id created-id]})) last last))
                "The record at created-id should now have its name set to \"created_row\"")
            (is (= (:expected update) (mt/user-http-request :crowberto :post 200 (:action update)
                                                            (assoc (mt/mbql-query categories {:filter [:= $id created-id]})
                                                                   :update_row {:name "updated_row"})))
                "Update should return the right shape")
            (is (= "updated_row" (-> (mt/rows (mt/run-mbql-query categories {:filter [:= $id created-id]})) last last))
                "The row should actually be updated")
            (is (= (:expected delete)
                   (mt/user-http-request :crowberto :post 200 (:action delete) (mt/mbql-query categories {:filter [:= $id created-id]})))
                "Delete should return the right shape")
            (is (= [] (mt/rows (mt/run-mbql-query categories {:filter [:= $id created-id]})))
                "Selecting for deleted rows should return an empty result")))))))

;; TODO: update test for this when we get something other than categories
#_(deftest row-delete-row-with-constraint-fails-test
    (mt/with-temporary-setting-values [experimental-enable-actions true]
      (mt/with-temp-vals-in-db Database (mt/id) {:settings {:database-enable-actions true}}
        (testing "Should return a 400 when deleting the row violates a foreign key constraint"
          (let [request-body (mt/mbql-query categories {:filter [:= $id 22]})]
            (mt/user-http-request :crowberto :post 400 "actions/row/delete" request-body))))))

(deftest feature-flags-test
  (testing "Disable endpoints unless both global and Database feature flags are enabled"
    (doseq [{:keys [action request-body]} (mock-requests)
            enable-global-feature-flag?   [true false]
            enable-database-feature-flag? [true false]]
      (testing action
        (mt/with-temporary-setting-values [experimental-enable-actions enable-global-feature-flag?]
          (mt/with-temp-vals-in-db Database (mt/id) {:settings {:database-enable-actions enable-database-feature-flag?}}
            (cond
              (not enable-global-feature-flag?)
              (testing "Should return a 400 if global feature flag is disabled"
                (is (= "Actions are not enabled."
                       (mt/user-http-request :crowberto :post 400 action request-body))))

              (not enable-database-feature-flag?)
              (testing "Should return a 400 if Database feature flag is disabled."
                (is (re= #"^Actions are not enabled for Database [\d,]+\.$"
                         (mt/user-http-request :crowberto :post 400 action request-body)))))))))))

(deftest validation-test
  (mt/with-temporary-setting-values [experimental-enable-actions true]
    (mt/with-temp-vals-in-db Database (mt/id) {:settings {:database-enable-actions true}}
      (doseq [{:keys [action request-body]} (mock-requests)
              k [:query :type]]
        (testing (str action " without " k)
          (when (row-action? action)
            (is (re= #"Value does not match schema:.*"
                     (:message (mt/user-http-request :crowberto :post 400 action (dissoc request-body k)))))))))))

(deftest row-delete-action-gives-400-when-matching-more-than-one
  (mt/with-temporary-setting-values [experimental-enable-actions true]
    (mt/with-temp-vals-in-db Database (mt/id) {:settings {:database-enable-actions true}}
      (let [query-that-returns-more-than-one (assoc (mt/mbql-query venues {:filter [:> $id -10]}) :update_row {:existing-col "new-value"})]
        (is (< 1 (count (mt/rows (qp/process-query query-that-returns-more-than-one)))))
        (doseq [{:keys [action]} (mock-requests)
                :when (not= action "actions/row/create")] ;; the query in create is not used to select values to act upopn.
          (is (re= #"Sorry, this would affect \d+ rows, but you can only act on 1"
                   (:message (mt/user-http-request :crowberto :post 400 action query-that-returns-more-than-one)))))))))

(deftest unknown-row-action-gives-404
  (mt/with-temporary-setting-values [experimental-enable-actions true]
    (mt/with-temp-vals-in-db Database (mt/id) {:settings {:database-enable-actions true}}
      (testing "404 for unknown Row action"
        (is (= "Unknown row action \"fake\"."
               (:message (mt/user-http-request :crowberto :post 404 "actions/row/fake" (mt/mbql-query categories {:filter [:= $id 1]})))))))))

(deftest four-oh-four-test
  (mt/with-temporary-setting-values [experimental-enable-actions true]
    (mt/with-temp-vals-in-db Database (mt/id) {:settings {:database-enable-actions true}}
      (doseq [{:keys [action request-body]} (mock-requests)]
        (testing action
          (testing "404 for unknown Table"
            (is (= "Failed to fetch Table 2,147,483,647: Table does not exist, or belongs to a different Database."
                   (:message (mt/user-http-request :crowberto :post 404 action
                                                   (assoc-in request-body [:query :source-table] Integer/MAX_VALUE)))))))))))
