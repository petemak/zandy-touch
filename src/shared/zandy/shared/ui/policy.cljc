(ns zandy.shared.ui.policy
  "PolicyDocument and the AgreementPanel used on the legal pages (P12 to P14,
  P17) and at checkout (P6). Pure hiccup functions, shared code (.cljc)."
  (:require [zandy.shared.format :as fmt]
            [zandy.shared.ui.core :as ui]))

(defn blocks
  "Renders the policy bodyBlocks, a seq of {:type \"heading\"|\"paragraph\"|\"list\"
  :text or :items}. Text is escaped by the renderer, never inserted as HTML;
  unknown block types render nothing. Returns a seq of hiccup."
  [bodyBlocks]
  (for [[i b] (map-indexed vector bodyBlocks)]
    ^{:key i}
    (case (:type b)
      "heading" [:h3 (:text b)]
      "paragraph" [:p (:text b)]
      "list" [:ul (for [[j it] (map-indexed vector (:items b))] ^{:key j} [:li it])]
      nil)))

(defn policy-document
  "PolicyDocument. Argument: the GET /api/policies/{type} body {:title :version
  :effectiveFrom :reviewStatus :summary :bodyBlocks}. Renders the page's only
  h1, version and effective date (Kampala date), the \"In short\" summary and
  the body; a draft notice shows while reviewStatus is \"placeholder\". Returns
  hiccup."
  [{:keys [title version effectiveFrom reviewStatus summary bodyBlocks]}]
  [:article.policy
   (when (= "placeholder" reviewStatus) (ui/draft-notice))
   [:h1.policy__title title]
   [:p.policy__meta (str "Version " version ", effective " (fmt/iso->date effectiveFrom))]
   (when (seq summary)
     [:section.policy__summary {:aria-label "Summary"}
      [:h2 "In short"]
      [:ul (for [[i s] (map-indexed vector summary)] ^{:key i} [:li s])]])
   [:div.policy__body (blocks bodyBlocks)]])

(defn agreement-panel
  "AgreementPanel on checkout. Argument map: {:policy (order terms body)
  :status (:loading, :error or :ready) :accepted? :error}. :error offers
  [:agreement/load] to retry; :ready shows the summary, the scrollable full
  text, a link to the page and the acceptance checkbox, which dispatches
  [:checkout/accept checked?]. Returns hiccup."
  [{:keys [policy status accepted? error]}]
  [:section.agreement {:aria-labelledby "agreement-title"}
   [:h2#agreement-title "Review and accept the order terms"]
   (case status
     :loading (ui/spinner-text "Loading the order terms")
     :error (ui/notice {:kind :error}
                       "We could not load the order terms. "
                       (ui/button {:variant :secondary :on-click [[:agreement/load]]} "Try again"))
     (let [{:keys [version summary bodyBlocks reviewStatus]} policy]
       [:div
        (when (= "placeholder" reviewStatus) (ui/draft-notice))
        (when (seq summary)
          [:ul.agreement__summary (for [[i s] (map-indexed vector summary)] ^{:key i} [:li s])])
        [:div.agreement__text {:tabindex "0" :role "region" :aria-label "Full order terms"}
         (blocks bodyBlocks)]
        [:p (ui/link "/legal/order-terms" {:class "text-link" :target "_blank" :rel "noopener"} "Open the order terms on their own page")]
        (ui/checkbox {:id "accept-terms"
                      :label (str "I have read and accept the order terms (version " version ")")
                      :checked? accepted?
                      :error error
                      :on-change [:checkout/accept [:event/target.checked]]})]))])
