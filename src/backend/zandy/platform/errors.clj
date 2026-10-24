(ns zandy.platform.errors
  "The only place that builds error responses (contract 1.3 and 1.4).")

(def statuses
  {:malformedRequest 400 :missingIdempotencyKey 400
   :unauthenticated 401 :invalidCredentials 401
   :forbidden 403 :csrfInvalid 403 :passwordChangeRequired 403
   :notFound 404 :orderNotFound 404 :methodNotAllowed 405
   :cartOutdated 409 :agreementVersionOutdated 409 :idempotencyKeyReused 409
   :versionConflict 409 :invalidStatusTransition 409 :deliveryFeeRequired 409
   :emailAlreadyInUse 409 :cannotModifyOwner 409
   :fileTooLarge 413 :unsupportedMediaType 415 :validationFailed 422
   :rateLimited 429 :internalError 500 :serviceUnavailable 503})

(defn error-response
  "A complete ring response map in the one error shape. `details` is only meant for
  validationFailed, cartOutdated and agreementVersionOutdated."
  ([code message] (error-response code message nil nil))
  ([code message details] (error-response code message details nil))
  ([code message details headers]
   {:status (get statuses code 500)
    :headers (merge {"Cache-Control" "no-store"} headers)
    :body {:error (cond-> {:code (name code) :message message}
                    (seq details) (assoc :details (vec details)))}}))

(defn fail!
  "Throws an ex-info that the error-handling interceptor turns into an error response."
  ([code message] (fail! code message nil nil))
  ([code message details] (fail! code message details nil))
  ([code message details headers]
   (throw (ex-info message {:zandy/error {:code code :message message :details details :headers headers}}))))

(defn error-data [^Throwable ex]
  (loop [e ex]
    (when e
      (or (:zandy/error (ex-data e)) (recur (ex-cause e))))))

(defn ->response
  "Turns a thrown exception into an error response, or nil when it is not one of ours."
  [ex]
  (when-let [{:keys [code message details headers]} (error-data ex)]
    (error-response code message details headers)))

(defn validation-failed
  ([details] (validation-failed "Please check the highlighted fields." details))
  ([message details] (fail! :validationFailed message details)))

(defn field-error [field code message]
  {:field field :code code :message message})
