(ns main
  (:require [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string]))


(def invoice-url "https://muskaan-fkp-invoice.glitch.me")

(defn params->query-string [m]
  (clojure.string/join "&"
                       (for [[k v] m]
                         (str (name k) "=" (clojure.string/replace (str v) #" " "%20")))))

(defn bash [command]
  (shell/sh "bash" "-c" command))

(defn gen-pdf [id params]
  (let [uri (str invoice-url "?" (params->query-string params))]
    (shell/sh "wkhtmltopdf" uri (str "./pdfs/" id ".pdf"))))

(defn get-user-details [token user-ids]
  (let [fields ["Name" "Phone" "Address" "Email"]
        fields-qs (clojure.string/join "&" (map #(str "fields[]=" %) fields))
        ;; formula docs: https://community.airtable.com/t/return-multiple-records-for-linked-table/5954/5
        formula-qs (str "filterByFormula=OR(" (clojure.string/join "," (map #(str "RECORD_ID()='" % "'") user-ids)) ")")]
    (-> (curl/get (str "https://api.airtable.com/v0/appx6DLouO74VEgkD/People?maxRecords=3&view=Grid" "&" formula-qs "&" fields-qs)
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
  (first (get-in r ["fields" "From"])))

(defn user-by-id [users-res id]
  (->> (get users-res "records" [])
       (filter #(= id (get % "id")))
       first))

(defn donations->pdf-params
  "Convert donation and user data to a map that can be send to gen-pdf function"
  [donations-res users-res]
  (map (fn [donation]
         (let [user (user-by-id users-res (-> donation (get-in ["fields" "From"]) first))]
           [(get-in donation ["id"])
            {"amount" (str "Rs. " (get-in donation ["fields" "Amount"]) "/-")
             "date" (get-in donation ["fields" "Date"])
             "txId" (get-in donation ["fields" "Transaction Id"] "")
             "name" (get-in user ["fields" "Name"])
             "address" (get-in user ["fields" "Address"])
             "email" (get-in user ["fields" "Email"])
             "invoiceNumber" (get-in donation ["fields" "id"])}]))
       (get donations-res "records" [])))

(defn donations-res->patch-res [donations-res]
  (->> (get donations-res "records" [])
       (map (fn [donation]
              (assoc donation
                     "fields"
                     {"Status" "Receipt generated"
                      "Receipt" [{:url (str "https://res.cloudinary.com/muskaangc/image/upload/v1589871654/receipts/pdfs/" (get donation "id") ".pdf")
                                  :filename (str (get donation "id") ".pdf")}]})))
       (map #(dissoc % "createdTime"))))

(defn upload-receipts [token donations-res]
  (let [body (json/encode
              {:records (donations-res->patch-res donations-res)})]
    (println "Upload receipts to Cloudinary")
    (shell/sh "cld" "upload_dir" "-f" "receipts" "-o" "overwrite" "true" "pdfs")

    (println "Update airtable to include receipts uploaded to Cloudinary")
    (try
      (curl/patch "https://api.airtable.com/v0/appx6DLouO74VEgkD/Receipts"
                  {:headers {"Authorization" (str "Bearer " token)
                             "Content-Type" "application/json"}
                   :body body})

      (catch Exception e
          (println e)))

    (println "Delete the receipts on cloudinary")
    ;; (shell/sh "cld" "admin" "delete_resources" "all" "true")
    ))

(defn main [token]
  (let [donations-res (get-pending-donations token)
        from-user-ids (map record->from-id (get donations-res "records" []))
        users-res (get-user-details token from-user-ids)]
    (if (pos? (count (get donations-res "records")))
      (do (println "Generating receipts")
          (doall (pmap #(apply gen-pdf %) (donations->pdf-params donations-res users-res)))

          (println "Uploading receipts")
          (upload-receipts token donations-res))
      (println "No new records"))
    ))

(when *command-line-args*
  (let [[token] *command-line-args*]
    (when (or (empty? token))
      (println "Usage: <token>")
      (System/exit 1))
    (main token)))
