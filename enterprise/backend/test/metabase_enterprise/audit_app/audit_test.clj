(ns metabase-enterprise.audit-app.audit-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [metabase-enterprise.audit-app.audit :as ee-audit]
   [metabase-enterprise.serialization.cmd :as serialization.cmd]
   [metabase-enterprise.serialization.v2.backfill-ids :as serdes.backfill]
   [metabase.audit :as audit]
   [metabase.core.core :as mbc]
   [metabase.models.serialization :as serdes]
   [metabase.permissions.models.data-permissions :as data-perms]
   [metabase.permissions.models.permissions-group :as perms-group]
   [metabase.plugins :as plugins]
   [metabase.sync.task.sync-databases :as task.sync-databases]
   [metabase.task :as task]
   [metabase.test :as mt]
   [metabase.test.fixtures :as fixtures]
   [metabase.util :as u]
   [toucan2.core :as t2]))

(use-fixtures :once (fixtures/initialize :db :plugins))

(defmacro with-audit-db-restoration [& body]
  "Calls `ensure-audit-db-installed!` before and after `body` to ensure that the audit DB is installed and then
  restored if necessary. Also disables audit content loading if it is already loaded."
  `(let [audit-collection-exists?# (t2/exists? :model/Collection :type "instance-analytics")]
     (mt/with-temp-env-var-value! [mb-load-analytics-content (not audit-collection-exists?#)]
       (mbc/ensure-audit-db-installed!)
       (try
         ~@body
         (finally
           (mbc/ensure-audit-db-installed!))))))

(deftest audit-db-installation-test
  (mt/test-drivers #{:postgres :h2 :mysql}
    (testing "Audit DB content is not installed when it is not found"
      (t2/delete! :model/Database :is_audit true)
      ;; reset checksum
      (audit/last-analytics-checksum! 0)
      (with-redefs [ee-audit/analytics-dir-resource nil]
        (is (nil? @#'ee-audit/analytics-dir-resource))
        (is (= ::ee-audit/installed (ee-audit/ensure-audit-db-installed!)))
        (is (= audit/audit-db-id (t2/select-one-fn :id 'Database {:where [:= :is_audit true]}))
            "Audit DB is installed.")
        (is (= 0 (t2/count :model/Card {:where [:= :database_id audit/audit-db-id]}))
            "No cards created for Audit DB."))
      (t2/delete! :model/Database :is_audit true)
      (audit/last-analytics-checksum! 0))

    (testing "Audit DB content is installed when it is found"
      (is (= ::ee-audit/installed (ee-audit/ensure-audit-db-installed!)))
      (is (= audit/audit-db-id (t2/select-one-fn :id 'Database {:where [:= :is_audit true]}))
          "Audit DB is installed.")
      (is (some? (io/resource "instance_analytics")))
      (is (not= 0 (t2/count :model/Card {:where [:= :database_id audit/audit-db-id]}))
          "Cards should be created for Audit DB when the content is there."))

    (testing "Audit DB starts with no permissions for all users"
      (is (= {:perms/manage-database       :no
              :perms/download-results      :one-million-rows
              :perms/manage-table-metadata :no
              :perms/view-data             :unrestricted
              :perms/create-queries        :no}
             (-> (data-perms/data-permissions-graph :db-id audit/audit-db-id :audit? true)
                 (get-in [(u/the-id (perms-group/all-users)) audit/audit-db-id])))))

    (testing "Audit DB does not have scheduled syncs"
      (let [db-has-sync-job-trigger? (fn [db-id]
                                       (contains?
                                        (set (map #(-> % :data (get "db-id"))
                                                  (task/job-info "metabase.task.sync-and-analyze.job")))
                                        db-id))]
        (is (not (db-has-sync-job-trigger? audit/audit-db-id)))))

    (testing "Audit DB doesn't get re-installed unless the engine changes"
      (with-redefs [ee-audit/load-analytics-content (constantly nil)]
        (is (= ::ee-audit/no-op (ee-audit/ensure-audit-db-installed!)))
        (t2/update! :model/Database :is_audit true {:engine "datomic"})
        (is (= ::ee-audit/updated (ee-audit/ensure-audit-db-installed!)))
        (is (= ::ee-audit/no-op (ee-audit/ensure-audit-db-installed!)))
        (t2/update! :model/Database :is_audit true {:engine "h2"})))))

(def ^:private audit-table-eids
  {"v_alerts"        "qTuDhH4a4sSqVzZ47jJ8R"
   "v_audit_log"     "tSCO5aQmgbiUf-SLSQVpi"
   "v_content"       "p53BD8eTrMM1neH9_4eZx"
   "v_dashboardcard" "S3ZF9KyTHqp7zj_N8x0KI"
   "v_databases"     "Blz8U_EUpr3TKzZ__T3Wt"
   "v_fields"        "IuKLQLGZin2wvJeZWlOam"
   "v_group_members" "uGm59moVbiP8uHLIII-JN"
   "v_query_log"     "5RKjPrEoH_ojdWK2ItOJa"
   "v_subscriptions" "MXMK0SDjKjLlXKCGt4fei"
   "v_tables"        "YvPPmCigBjeBH5QLSXzuH"
   "v_tasks"         "t4AxH0GHZpxw6QNUIwwHd"
   "v_users"         "VnUplpevdFkZyG2I9brb5"
   "v_view_log"      "-G_OzovvQZlIm6xoipIL2"})

(deftest audit-db-entity-ids-test
  (mt/test-drivers #{:postgres :h2 :mysql}
    (testing "Audit DB content has the hard-coded `:entity_id`s"
      (testing "when freshly loaded:"
        (t2/delete! :model/Database :is_audit true)
        (audit/last-analytics-checksum! 0)
        (is (= ::ee-audit/installed (ee-audit/ensure-audit-db-installed!)))
        ;; Fresh install now complete.
        (is (= [audit/audit-db-id] (t2/select-fn-vec :id 'Database {:where [:= :is_audit true]}))
            "Audit DB is installed and unique.")
        (testing "database has hard-coded audit/audit-db-entity-id"
          (is (= "audit__rP75CiURKZ-0pq" (t2/select-one-fn :entity_id 'Database :is_audit true))))
        (testing "tables have entity_ids derived from the fixed database entity_id"
          (is (= audit-table-eids
                 (->> (t2/reducible-select 'Table :db_id audit/audit-db-id)
                      (into {} (map (juxt (comp u/lower-case-en :name) :entity_id)))))))
        (testing "tables have entity_ids derived from the fixed database entity_id"
          (doseq [table (t2/select ['Table :id :entity_id :name] :db_id audit/audit-db-id)
                  field (t2/select ['Field :entity_id :name] :table_id (:id table))]
            (is (= (#'ee-audit/entity-id-for-field (:entity_id table) field)
                   (:entity_id field))))))

      (testing "pre-existing with no entity_ids"
        (t2/update! :model/Database audit/audit-db-id {:entity_id nil})
        (let [table-ids (t2/update-returning-pks! :model/Table
                                                  {:db_id audit/audit-db-id}
                                                  {:entity_id nil})]
          (is (= (count audit-table-eids)
                 (count table-ids)))
          (t2/update! :model/Field {:table_id [:in table-ids]}
                      {:entity_id nil})
          (is (= [nil] (t2/select-fn-vec :entity_id :model/Database :is_audit true))
              "Audit DB is installed and unique, but its entity_id was removed")
          (is (= #{nil} (t2/select-fn-set :entity_id :model/Table :db_id audit/audit-db-id)))
          (is (= #{nil} (t2/select-fn-set :entity_id :model/Field :table_id [:in table-ids]))))

        ;; Installing the DB is a no-op, since it's already installed.
        (is (= ::ee-audit/no-op (ee-audit/ensure-audit-db-installed!)))
        ;; Audit DB has not been duplicated, but all the entity_ids are back.
        (is (= [audit/audit-db-id] (t2/select-fn-vec :id 'Database {:where [:= :is_audit true]}))
            "Audit DB is installed and unique.")
        (testing "database has hard-coded audit/audit-db-entity-id"
          (is (= ["audit__rP75CiURKZ-0pq"] (t2/select-fn-vec :entity_id 'Database :is_audit true))))
        (testing "tables have entity_ids derived from the fixed database entity_id"
          (is (= audit-table-eids
                 (->> (t2/reducible-select 'Table :db_id audit/audit-db-id)
                      (into {} (map (juxt (comp u/lower-case-en :name) :entity_id)))))))
        (testing "tables have entity_ids derived from the fixed database entity_id"
          (doseq [table (t2/select ['Table :id :entity_id :name] :db_id audit/audit-db-id)
                  field (t2/select ['Field :entity_id :name] :table_id (:id table))]
            (is (= (#'ee-audit/entity-id-for-field (:entity_id table) field)
                   (:entity_id field)))))))))

(deftest instance-analytics-content-is-copied-to-mb-plugins-dir-test
  (mt/with-temp-env-var-value! [mb-plugins-dir "card_catalogue_dir"]
    (try
      (let [plugins-dir (plugins/plugins-dir)]
        (fs/create-dirs plugins-dir)
        (#'ee-audit/ia-content->plugins plugins-dir)
        (doseq [top-level-plugin-dir (map (comp str fs/absolutize)
                                          (fs/list-dir (fs/path plugins-dir "instance_analytics")))]
          (testing (str top-level-plugin-dir " starts with plugins value")
            (is (str/starts-with? top-level-plugin-dir (str (fs/absolutize plugins-dir)))))))
      (finally
        (fs/delete-tree (plugins/plugins-dir))))))

(deftest all-instance-analytics-content-is-copied-from-mb-plugins-dir-test
  (mt/with-temp-env-var-value! [mb-plugins-dir "card_catalogue_dir"]
    (try
      (#'ee-audit/ia-content->plugins (plugins/plugins-dir))
      (is (= (count (file-seq (io/file (str (fs/path (plugins/plugins-dir) "instance_analytics")))))
             (count (file-seq (io/file (io/resource "instance_analytics"))))))
      (finally
        (fs/delete-tree (plugins/plugins-dir))))))

(defn- get-audit-db-trigger-keys []
  (let [trigger-keys (->> (task/scheduler-info) :jobs (mapcat :triggers) (map :key))
        audit-db? #(str/includes? % (str audit/audit-db-id))]
    (filter audit-db? trigger-keys)))

(deftest no-sync-tasks-for-audit-db
  (with-audit-db-restoration
    (ee-audit/ensure-audit-db-installed!)
    (is (= 0 (count (get-audit-db-trigger-keys))) "no sync scheduled after installation")

    (with-redefs [task.sync-databases/job-context->database-id (constantly audit/audit-db-id)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot sync Database: It is the audit db."
           (#'task.sync-databases/sync-and-analyze-database! "job-context"))))
    (is (= 0 (count (get-audit-db-trigger-keys))) "no sync occured even when called directly for audit db.")))

(deftest no-backfill-occurs-when-loading-analytics-content-test
  (mt/with-model-cleanup [:model/Collection]
    (let [c1-instance (t2/insert-returning-instance! :model/Collection
                                                     {:entity_id nil,
                                                      :name      "My Duped Collection",
                                                      :location  "/"})]
      ;; fill in the entity_id for c1:
      (serdes.backfill/backfill-ids-for! :model/Collection)
      ;; insert c2, which will have the same entity id:
      (let [c2-instance (t2/insert-returning-instance! :model/Collection (dissoc c1-instance :id))]
        (testing "c1 and c2 hash to the same entity id."
          (is (= (u/generate-nano-id (serdes/identity-hash c1-instance))
                 (u/generate-nano-id (serdes/identity-hash c2-instance)))))
        (testing "A backfill with 'duplicate' rows (with different ids)."
          (is (thrown? Exception
                       (serdes.backfill/backfill-ids-for! :model/Collection))))
        (testing "No exception is thrown when db has 'duplicate' entries."
          (is (= ::ee-audit/no-op
                 (ee-audit/ensure-audit-db-installed!))))))))

(deftest checksum-not-recorded-when-load-fails-test
  (mt/test-drivers #{:postgres :h2 :mysql}
    (t2/delete! :model/Database :is_audit true)
    (testing "If audit content loading throws an exception, the checksum should not be stored"
      (audit/last-analytics-checksum! 0)
      (with-redefs [serialization.cmd/v2-load-internal! (fn [& _] (throw (Exception. "Audit loading failed")))]
        (is (thrown-with-msg? Exception
                              #"Audit loading failed"
                              (ee-audit/ensure-audit-db-installed!)))
        (is (= 0 (audit/last-analytics-checksum)))))))

(deftest should-load-audit?-test
  (testing "load-analytics-content + checksums dont match => load"
    (is (#'ee-audit/should-load-audit? true 1 3)))
  (testing "load-analytics-content + last-checksum is -1 => load (even if current-checksum is also -1)"
    (is (#'ee-audit/should-load-audit? true -1 -1)))
  (testing "checksums are the same => do not load"
    (is (not (#'ee-audit/should-load-audit? true 3 3))))
  (testing "load-analytics-content false => do not load"
    (is (not (#'ee-audit/should-load-audit? false 3 5))))
  (testing "load-analytics-content is false + checksums do not match  => do not load"
    (is (not (#'ee-audit/should-load-audit? false 1 3)))))
