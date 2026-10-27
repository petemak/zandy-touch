(ns zandy.frontend.admin.help
  "Staff help (design-spec.md A13). The topics are data generated from
  admin-help.md (zandy.frontend.admin.help-data). Everything here is pure: the
  role filter, the page-to-topic mapping and the two views (topic list, one
  topic). A topic a role may not see is never rendered, not even by a direct link."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [zandy.frontend.admin.help-data :as data]
            [zandy.shared.ui.core :as ui]))

(def list-path
  "URL path of the help topic list (A13)."
  "/admin/help")

(defn topic-path
  "URL path of the help topic `id`."
  [id] (str list-path "/" id))

;; ---------------------------------------------------------------------------
;; Role filter and lookups

(defn can-see?
  "May a person with `role` (\"owner\" or \"support\") read `topic`?"
  [topic role]
  (contains? (:roles topic) role))

(defn visible-topics
  "Topics `role` may read, in file order. Argument: \"owner\" or \"support\". This
  filter is a UI convenience, not a security control: the help text ships
  inside the app bundle. It keeps staff from reading guidance for things they
  cannot do; real access to data and actions is enforced by the server."
  [role]
  (filterv #(can-see? % role) data/topics))

(defn find-topic
  "The topic with `id`, or nil. Does not check the role; use can-see? or
  topic-view."
  [id]
  (first (filter #(= id (:id %)) data/topics)))

(defn grouped
  "[[group-name [topic ...]] ...] for `role`, in the file's group order. A
  group with nothing for this role is left out."
  [role]
  (let [visible (visible-topics role)]
    (vec (for [g data/groups
               :let [ts (filterv #(= g (:group %)) visible)]
               :when (seq ts)]
           [g ts]))))

(defn title-for
  "Topic title when `role` may see the topic, else nil (used for the page title)."
  [role id]
  (let [t (find-topic id)]
    (when (and t (can-see? t role)) (:title t))))

;; ---------------------------------------------------------------------------
;; Page to topic ("How do I...?")

(defn page-id-for-route
  "Admin route name -> design-spec page id (\"A4\"), or nil."
  [route-name]
  (first (for [[page-id {:keys [route]}] data/pages :when (= route route-name)] page-id)))

(defn topic-for-route
  "The first topic (file order) whose `pages:` include the page of `route-name`
  and that `role` may read."
  [route-name role]
  (when-let [page-id (page-id-for-route route-name)]
    (first (filter #(and (some #{page-id} (:pages %)) (can-see? % role)) data/topics))))

(defn how-do-i
  "The small \"How do I...?\" link for an admin page, or nil when no readable
  topic maps to it."
  [route-name role]
  (when-let [t (topic-for-route route-name role)]
    (ui/link (topic-path (:id t)) {:class "how-do-i"} "How do I…?")))

;; ---------------------------------------------------------------------------
;; Related links

(defn- help-hrefs [body]
  (let [found (atom [])]
    (walk/postwalk
     (fn [x]
       (when (and (vector? x) (= :a (first x)) (map? (second x)))
         (swap! found conj (:href (second x))))
       x)
     body)
    @found))

(defn related-topics
  "Topics linked from the body of `topic` that `role` may read, in order of first mention."
  [topic role]
  (let [prefix (str list-path "/")]
    (->> (help-hrefs (:body topic))
         (filter #(str/starts-with? % prefix))
         (map #(subs % (count prefix)))
         distinct
         (keep find-topic)
         (filter #(and (not= (:id %) (:id topic)) (can-see? % role))))))

(def ^:private required-page-ids
  "Admin pages that get a \"Related pages\" link from topics (A4 to A12)."
  #{"A4" "A5" "A6" "A7" "A8" "A9" "A10" "A11" "A12"})

(defn related-pages
  "The staff pages a topic is about as [label path], one per path."
  [topic]
  (->> (:pages topic)
       (filter required-page-ids)
       (map data/pages)
       (map (juxt :label :path))
       (reduce (fn [acc [label path]] (if (some #(= path (second %)) acc) acc (conj acc [label path]))) [])))

(defn audience
  "Label for a topic: \"Shop owner only\" or \"Shop owner and support staff\"."
  [topic]
  (if (= #{"owner"} (:roles topic)) "Shop owner only" "Shop owner and support staff"))

;; ---------------------------------------------------------------------------
;; Views

(defn- body-node
  "Internal links in the generated body become router links."
  [x]
  (if (and (vector? x) (= :a (first x)) (map? (second x)))
    (apply ui/link (:href (second x)) {} (drop 2 x))
    x))

(defn- render-body [body]
  (into [:div.help-body] (map #(walk/postwalk body-node %)) body))

(defn- back-link []
  (ui/link-button list-path {:variant :secondary} "Back to Help"))

(defn list-view
  "A13 topic list for `role`."
  [role]
  [:div.help
   [:h1.page-title "Help"]
   [:p.help-lead "Step-by-step guides for the staff area. Choose what you want to do."]
   (for [[i [group topics]] (map-indexed vector (grouped role))
         :let [heading-id (str "help-group-" i)]]
     ^{:key group}
     [:section.help-group {:aria-labelledby heading-id}
      [:h2 {:id heading-id} group]
      [:ul.help-topics
       (for [t topics]
         ^{:key (:id t)}
         [:li.help-topics__item
          [:h3 (ui/link (topic-path (:id t)) {} (:title t))]
          [:p (:summary t)]])]])])

(defn not-allowed-view
  "Shown for a topic the role may not read. Never carries the topic's text."
  []
  [:div.help
   [:h1.page-title "Help"]
   (ui/notice {:kind :info} "This topic is for the shop owner.")
   [:p (back-link)]])

(defn not-found-view
  "A13 view for a topic id that does not exist: a short notice and a Back to
  Help button. Returns hiccup."
  []
  [:div.help
   [:h1.page-title "Help"]
   (ui/notice {:kind :info} "We could not find that help topic.")
   [:p (back-link)]])

;; Role check on a direct link: a topic the role may not read shows a short
;; notice (not-allowed-view) and none of its text is rendered, so a pasted
;; link cannot show owner-only guidance to support staff in the UI.
(defn topic-view
  "A13 for one topic. Only the topic's own data is read after the role check."
  [role topic-id]
  (let [topic (find-topic topic-id)]
    (cond
      (nil? topic) (not-found-view)
      (not (can-see? topic role)) (not-allowed-view)
      :else
      (let [topics (related-topics topic role)
            pages (related-pages topic)]
        [:article.help.help-topic
         (ui/breadcrumb [["Help" list-path] [(:title topic) nil]])
         [:h1.page-title (:title topic)]
         [:p.help-audience [:strong "Who this is for: "] (audience topic)]
         [:p.help-lead (:summary topic)]
         [:div.help-actions
          (ui/button {:variant :secondary :on-click [[:help/print]]} "Print this topic")]
         (render-body (:body topic))
         (when (or (seq topics) (seq pages))
           [:section.help-related {:aria-labelledby "help-related-title"}
            [:h2#help-related-title "Related"]
            [:ul
             (for [t topics]
               ^{:key (:id t)} [:li (ui/link (topic-path (:id t)) {} (:title t))])
             (for [[label path] pages]
               ^{:key path} [:li (ui/link path {} (str "Open " label))])]])
         [:p.help-actions (back-link)]]))))
