(ns zandy.frontend.scenes.help
  "Portfolio scenes for the staff help (A13): the topic list for each role, a
  topic page, a page with its \"How do I...?\" link, and the not-allowed notice."
  (:require [portfolio.replicant :refer-macros [defscene]]
            [zandy.frontend.admin.core :as admin]
            [zandy.frontend.scenes.fixtures :as fx]
            [zandy.shared.pages :as pages]
            [zandy.shared.routes :as routes]))

(defn- session [role]
  {:user {:id "u1" :name (if (= "owner" role) "Sandra" "Grace") :email "staff@example.test" :role role :mustChangePassword false} :csrf "x"})

(defn- st [role url data]
  (merge (pages/initial-state)
         {:route (routes/match-url url) :site fx/site :page {:status :ok}
          :admin {:session (session role) :data data :ui {}}}))

(defscene list-owner :title "Help / topic list, owner (all topics)"
  (admin/view (st "owner" "/admin/help" {})))

(defscene list-support :title "Help / topic list, support (no staff accounts)"
  (admin/view (st "support" "/admin/help" {})))

(defscene topic-orders :title "Help / topic page, with steps, notes and related links"
  (admin/view (st "support" "/admin/help/orders" {})))

(defscene topic-owner-only :title "Help / topic page, owner-only topic as the owner"
  (admin/view (st "owner" "/admin/help/staff-accounts" {})))

(defscene topic-not-allowed :title "Help / not allowed (support opens the staff-accounts link)"
  (admin/view (st "support" "/admin/help/staff-accounts" {})))

(defscene topic-not-found :title "Help / unknown topic"
  (admin/view (st "owner" "/admin/help/no-such-topic" {})))

(defscene page-with-how-do-i :title "Help / the \"How do I...?\" link beside a page title"
  (admin/view (st "support" "/admin/inbox" {:messages {:status :ok :body {:items []}}})))
