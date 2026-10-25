(ns zandy.frontend.help-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [zandy.frontend.admin.core :as admin]
            [zandy.frontend.admin.help :as help]
            [zandy.frontend.admin.help-data :as data]
            [zandy.frontend.state :as s]
            [zandy.shared.routes :as routes]))

(defn- user [role] {:user {:id "u1" :name "Sam" :email "s@example.test" :role role :mustChangePassword false} :csrf "x"})

(defn- st [role url & {:as extra}]
  (merge (s/initial-state)
         {:route (routes/match-url url) :page {:status :ok}
          :admin {:session (user role) :data {} :ui {}}}
         extra))

(defn- text-of
  "Every string in a hiccup tree, joined."
  [tree]
  (let [acc (atom [])]
    (walk/postwalk (fn [x] (when (string? x) (swap! acc conj x)) x) tree)
    (str/join " " @acc)))

(defn- hrefs-of [tree]
  (let [acc (atom [])]
    (walk/postwalk (fn [x] (when (and (map? x) (:href x)) (swap! acc conj (:href x))) x) tree)
    @acc))

(deftest help-is-in-the-menu-for-everyone
  (let [help-item (last admin/nav-items)]
    (is (= "Help" (nth help-item 2)))
    (is (nil? (nth help-item 4 nil)) "no role restriction")
    (is (= ["Orders" "Products" "Import" "Inbox" "Staff" "My account" "Help"] (map #(nth % 2) admin/nav-items)))
    (is (= #{:owner} (set (keep #(nth % 4 nil) admin/nav-items))) "only Staff is owner-only")))

(deftest role-filtering-of-the-list
  (testing "the owner sees every topic"
    (is (= (count data/topics) (count (help/visible-topics "owner")))))
  (testing "support never gets the staff-accounts topic"
    (let [ids (set (map :id (help/visible-topics "support")))]
      (is (not (contains? ids "staff-accounts")))
      (is (contains? ids "orders"))
      (is (= (dec (count data/topics)) (count ids)))))
  (testing "the rendered list for support has no staff-accounts link, summary or title"
    (let [view (help/list-view "support")
          t (help/find-topic "staff-accounts")]
      (is (not-any? #(str/ends-with? % "/staff-accounts") (hrefs-of view)))
      (is (not (str/includes? (text-of view) (:title t))))
      (is (not (str/includes? (text-of view) (:summary t))))))
  (testing "the rendered list for the owner links to it"
    (is (some #(str/ends-with? % "/staff-accounts") (hrefs-of (help/list-view "owner")))))
  (testing "topics are grouped in the file's group order"
    (is (= data/groups (map first (help/grouped "owner"))))))

(deftest direct-link-to-an-owner-only-topic
  (let [t (help/find-topic "staff-accounts")
        view (help/topic-view "support" "staff-accounts")
        text (text-of view)]
    (is (str/includes? text "This topic is for the shop owner."))
    (is (some #{"/admin/help"} (hrefs-of view)) "a link back to Help")
    (is (not (str/includes? text (:title t))))
    (is (not (str/includes? text (:summary t))))
    (is (not (str/includes? text "temporary password")))
    (is (= 1 (count (filter #(and (vector? %) (= :h1.page-title (first %))) (tree-seq vector? seq view)))) "one h1")
    (testing "also through the whole admin view, as support"
      (let [page (text-of (admin/view (st "support" "/admin/help/staff-accounts")))]
        (is (str/includes? page "This topic is for the shop owner."))
        (is (not (str/includes? page "Add a support employee")))))
    (testing "the owner reads it"
      (is (str/includes? (text-of (help/topic-view "owner" "staff-accounts")) "Add a support employee")))
    (testing "an unknown topic is a notice, not a crash"
      (is (str/includes? (text-of (help/topic-view "owner" "nope")) "could not find")))))

(deftest topic-page-shape
  (let [view (help/topic-view "owner" "orders")]
    (is (str/includes? (text-of view) "Shop owner and support staff"))
    (is (str/includes? (text-of view) "Handling orders"))
    (is (some #{"/admin/help/stock"} (hrefs-of view)) "help: links go to topics")
    (is (some #{"/admin"} (hrefs-of view)) "page: links go to the admin page")
    (is (some #(str/includes? (text-of %) "Print") (filter vector? (tree-seq vector? seq view))))))

(deftest every-admin-page-has-a-help-link-target
  (doseq [page-id ["A4" "A5" "A6" "A7" "A8" "A9" "A10" "A11" "A12"]
          :let [route (get-in data/pages [page-id :route])]]
    (testing page-id
      (is (some? route))
      (is (some? (help/topic-for-route route "owner")) (str page-id " has a topic for the owner"))
      (let [link (help/how-do-i route "owner")]
        (is (some? link))
        (is (str/starts-with? (:href (second link)) "/admin/help/"))
        (is (some? (help/find-topic (subs (:href (second link)) (count "/admin/help/")))))))
    (testing "support"
      (if (= "A12" page-id)
        (is (nil? (help/how-do-i :admin-staff "support")) "no link to an owner-only topic")
        (is (some? (help/how-do-i (get-in data/pages [page-id :route]) "support")))))))

(deftest how-do-i-opens-the-right-topic
  (is (= "orders" (:id (help/topic-for-route :admin-order "support"))))
  (is (= "stock" (:id (help/topic-for-route :admin-products "owner"))))
  (is (= "products" (:id (help/topic-for-route :admin-new-product "owner"))))
  (is (= "products" (:id (help/topic-for-route :admin-product "owner"))))
  (is (= "bulk-import" (:id (help/topic-for-route :admin-product-import "owner"))))
  (is (= "messages" (:id (help/topic-for-route :admin-inbox "owner"))))
  (is (= "password" (:id (help/topic-for-route :admin-account "owner"))))
  (is (= "staff-accounts" (:id (help/topic-for-route :admin-staff "owner")))))

(deftest how-do-i-link-is-on-the-rendered-pages
  (testing "pages whose title does not wait for data"
    (doseq [[url topic] [["/admin" "orders"] ["/admin/products" "stock"] ["/admin/new-product" "products"]
                         ["/admin/product-import" "bulk-import"] ["/admin/inbox" "messages"]
                         ["/admin/account" "password"] ["/admin/staff" "staff-accounts"]]]
      (let [view (admin/view (st "owner" url))]
        (is (some #{(str "/admin/help/" topic)} (hrefs-of view)) url)
        (is (str/includes? (text-of view) "How do I…?") url))))
  (testing "the order page and the product page show the title after loading"
    (let [order (admin/view (st "owner" "/admin/orders/o1"
                                :admin {:session (user "owner") :ui {}
                                        :data {:order {:status :ok :body {:order {:orderNumber "ZT-1" :status "placed" :items [] :customer {} :delivery {}} :history []}}}}))
          product (admin/view (st "owner" "/admin/products/p1"
                                  :admin {:session (user "owner") :ui {}
                                          :data {:product {:status :ok :body {:id "p1" :name "Serum" :stockQuantity 3 :images []}}}}))]
      (is (some #{"/admin/help/orders"} (hrefs-of order)))
      (is (some #{"/admin/help/products"} (hrefs-of product)))))
  (testing "support has none on the staff page, and none while a temporary password is pending"
    (is (not-any? #(str/starts-with? % "/admin/help/") (hrefs-of (admin/view (st "support" "/admin/staff")))))
    (let [locked (assoc-in (st "support" "/admin/account") [:admin :session :user :mustChangePassword] true)]
      (is (not-any? #(str/starts-with? % "/admin/help/") (hrefs-of (admin/view locked)))))))

(deftest help-data-matches-the-routes
  (testing "every page maps to a real route"
    (doseq [[page-id {:keys [route path]}] data/pages]
      (is (some #(= route (:name (second %))) routes/routes) page-id)
      (is (some? (routes/match-url path)) (str page-id " path " path))))
  (testing "every link in every topic opens a route, and help: links an existing topic"
    (doseq [t data/topics
            href (hrefs-of (:body t))]
      (is (some? (routes/match-url href)) href)
      (when (str/starts-with? href "/admin/help/")
        (is (some? (help/find-topic (subs href (count "/admin/help/")))) href))))
  (testing "A4 to A12 are all covered"
    (let [mapped (set (mapcat :pages data/topics))]
      (is (every? mapped ["A4" "A5" "A6" "A7" "A8" "A9" "A10" "A11" "A12"])))))

(deftest entering-a-topic-sets-the-page-title-only-when-allowed
  (let [enter (fn [role url] (s/handle (st role url) [:admin/enter]))]
    (is (= "Handling orders" (get-in (enter "owner" "/admin/help/orders") [:state :admin :help-title])))
    (is (nil? (get-in (enter "support" "/admin/help/staff-accounts") [:state :admin :help-title])))
    (is (= :fx/head (ffirst (:fx (enter "owner" "/admin/help")))))))

(deftest print-is-an-action-not-a-side-effect-in-the-view
  (is (= [[:fx/print]] (:fx (s/handle (st "owner" "/admin/help/orders") [:help/print])))))
