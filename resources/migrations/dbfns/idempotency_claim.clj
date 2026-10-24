;; Claims an idempotency key. The caller owns the key when the stored claim token equals its own.
;; A claim that stayed in progress past stale-before is taken over (the previous holder crashed).
{:params [db k body-hash token now stale-before]}
(let [e (datomic.api/entity db [:idempotency/scoped-key k])]
  (cond
    (nil? e)
    [{:idempotency/scoped-key k
      :idempotency/body-hash body-hash
      :idempotency/state :in-progress
      :idempotency/claim-token token
      :idempotency/created-at now}]

    (and (= :in-progress (:idempotency/state e))
         (< (.getTime (:idempotency/created-at e)) (.getTime stale-before)))
    [[:db/add (:db/id e) :idempotency/claim-token token]
     [:db/add (:db/id e) :idempotency/created-at now]
     [:db/add (:db/id e) :idempotency/body-hash body-hash]]

    :else []))
