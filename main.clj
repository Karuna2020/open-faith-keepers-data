(ns main
  (:require [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string]))


(def invoice-url "https://muskaan-fkp-invoice.glitch.me")

(defn template->html [path vars]
  (let [m (atom (slurp path))]
    (doseq [[k v] vars]
      (reset! m (clojure.string/replace @m (re-pattern (str k)) (str v))))
    @m))


(defn params->query-string [m]
  (clojure.string/join "&"
                       (for [[k v] m]
                         (str (name k) "=" (clojure.string/replace (str v) #" " "%20")))))

(defn bash [command]
  (shell/sh "bash" "-c" command))

(defn gen-pdf [id params]
  (let [html (template->html "invoice.html.template" params)
        _ (spit (str "./pdfs/" id ".html") html)]
    (bash (str "cat ./pdfs/" id ".html | wkhtmltopdf --image-dpi 300  - ./pdfs/" id ".pdf"))))

(defn get-user-details [token user-ids]
  (let [fields ["Name" "Phone" "Address" "Email"]
        fields-qs (clojure.string/join "&" (map #(str "fields[]=" %) fields))
        ;; formula docs: https://community.airtable.com/t/return-multiple-records-for-linked-table/5954/5
        formula-qs (str "filterByFormula=OR(" (clojure.string/join "," (map #(str "RECORD_ID()='" % "'") user-ids)) ")")
        uri (str "https://api.airtable.com/v0/appx6DLouO74VEgkD/People?maxRecords=100&view=Grid" "&" formula-qs "&" fields-qs)]
    (-> (curl/get uri
                  {:headers {"Authorization" (str "Bearer " token)}})
        :body
        json/decode)))

(defn get-pending-donations
  "Fetch all donations for which an invoice needs to be generated"
  [token]
  (-> (curl/get "https://api.airtable.com/v0/appx6DLouO74VEgkD/Receipts?maxRecords=100&view=ready-for-generating-receipt"
                {:headers {"Authorization" (str "Bearer " token)}})
      :body
      json/decode))

(defn record->from-id [r]
  (first (get-in r ["fields" "From Person"])))

(defn user-by-id [users-res id]
  (->> (get users-res "records" [])
       (filter #(= id (get % "id")))
       first))

(defn donations->pdf-params
  "Convert donation and user data to a map that can be send to gen-pdf function"
  [donations-res users-res]
  (map (fn [donation]
         (let [user (user-by-id users-res (record->from-id donation))]
           [(get-in donation ["id"])
            {:amount (str "Rs. " (get-in donation ["fields" "Amount"]) "/-")
             :date (get-in donation ["fields" "Date"])
             :txId (get-in donation ["fields" "Transaction Id"] "")
             :name (get-in user ["fields" "Name"])
             :address (get-in user ["fields" "Address"])
             :email (get-in user ["fields" "Email"])
             :phone (get-in user ["fields" "Phone"])
             :invoiceNumber (get-in donation ["fields" "id"])}]))
       (get donations-res "records" [])))

(defn donations-res->patch-res [donations-res]
  (->> (get donations-res "records" [])
       (map (fn [donation]
              (assoc donation
                     "fields"
                     {"Status" "Receipt generated"
                      "Receipt" [{:url (str "https://res.cloudinary.com/muskaangc/image/upload/v1589871654/receipts-backup/pdfs/" (get donation "id") ".pdf")
                                  :filename (str (get donation "id") ".pdf")}]})))
       (map #(dissoc % "createdTime"))))

(defn upload-receipts [token donations-res]
  (let [updates (partition-all 8 (donations-res->patch-res donations-res))]
    (doseq [records updates]
      (let [body (json/encode
                  {:records records})]
        (println "Upload receipts to Cloudinary")
        (shell/sh "cld" "upload_dir" "-f" "receipts-backup" "-o" "overwrite" "true" "pdfs")

        (println "Update airtable to include receipts uploaded to Cloudinary")
        (try
          (curl/patch "https://api.airtable.com/v0/appx6DLouO74VEgkD/Receipts"
                      {:headers {"Authorization" (str "Bearer " token)
                                 "Content-Type" "application/json"}
                       :body body})

          (catch Exception e
            (println e)))

        ;; (println "Delete the receipts on cloudinary")
        ;; (shell/sh "cld" "admin" "delete_resources" "all" "true")
        ))))

(defn main [token]
  (let [donations-res (get-pending-donations token)
        from-user-ids (map record->from-id (get donations-res "records" []))
        users-res (get-user-details token from-user-ids)]
    (if (pos? (count (get donations-res "records")))
      (do (println "Generating receipts")
          (doall (pmap #(apply gen-pdf %) (donations->pdf-params donations-res users-res)))

          (println "Uploading receipts")
          (upload-receipts token donations-res)
          :done)
      (println "No new records"))
    ))

(comment
  (let [token (slurp ".airtable-token")
        donations-res (get-pending-donations token)
        from-user-ids (map record->from-id (get donations-res "records" []))
        users-res (get-user-details token from-user-ids)]
    (donations-res->patch-res donations-res)
    ;;(donations->pdf-params donations-res users-res)
    )

  (partition-all 10 (range 28))
  (main (slurp ".airtable-token"))
  (-> ".airtable-token"
      slurp
      get-pending-donations
      (get "records" [])
      (->> (map record->from-id)
           (get-user-details (slurp ".airtable-token")))
      )
  )

(when *command-line-args*
  (let [[token] *command-line-args*]
    (when (or (empty? token))
      (println "Usage: <token>")
      (System/exit 1))
    (main token)))
