#!/usr/bin/env bb
;; Staff help build step (architecture.md 2.4 and 4.1).
;;
;; Reads admin-help.md (written by ux-designer, never edited here) and writes
;; src/frontend/zandy/frontend/admin/help_data.cljs: the topics as plain data,
;; with the body as hiccup-ready blocks. There is no Markdown parser at run
;; time, no library and no backend endpoint.
;;
;;   bb scripts/build/help.clj           regenerate help_data.cljs
;;   bb scripts/build/help.clj --check   exit 1 when help_data.cljs is out of date
;;
;; The parser fails loudly (exit 1, message with the line number) on anything
;; outside the format described in admin-help.md: unknown syntax, a duplicate
;; topic id, a missing field, a link to an unknown topic or page id, or an admin
;; page A4 to A12 that no topic maps to.
;;
;; This file is plain Clojure, so the JVM test (test/frontend/zandy/frontend/
;; help_build_test.clj) loads it as namespace `help` and exercises `parse`.
(ns help
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Fixed vocabulary

(def roles #{"owner" "support"})

(def pages
  "Admin page id (design-spec.md section 1) -> route name, the path a `page:`
  link opens, and the link text. A5 and A8 need a record id, so a link opens
  their list page."
  (array-map
   "A1" {:route :admin-login :path "/admin/login" :label "Staff sign in"}
   "A2" {:route :admin-forgot-password :path "/admin/forgot-password" :label "Forgot password"}
   "A3" {:route :admin-reset-password :path "/admin/reset-password" :label "Choose a new password"}
   "A4" {:route :admin-orders :path "/admin" :label "Orders"}
   "A5" {:route :admin-order :path "/admin" :label "Orders"}
   "A6" {:route :admin-products :path "/admin/products" :label "Products"}
   "A7" {:route :admin-new-product :path "/admin/new-product" :label "New product"}
   "A8" {:route :admin-product :path "/admin/products" :label "Products"}
   "A9" {:route :admin-product-import :path "/admin/product-import" :label "Import"}
   "A10" {:route :admin-inbox :path "/admin/inbox" :label "Inbox"}
   "A11" {:route :admin-account :path "/admin/account" :label "My account"}
   "A12" {:route :admin-staff :path "/admin/staff" :label "Staff"}
   "A13" {:route :admin-help :path "/admin/help" :label "Help"}))

(def required-pages ["A4" "A5" "A6" "A7" "A8" "A9" "A10" "A11" "A12"])

(def owner-only-pages #{"A12"})

(def help-path "/admin/help")

;; ---------------------------------------------------------------------------
;; Errors

(defn- fail
  ([msg] (throw (ex-info msg {})))
  ([line msg] (throw (ex-info (str "admin-help.md:" line ": " msg) {:line line}))))

;; ---------------------------------------------------------------------------
;; Inline text: plain text, **bold**, [text](help:id), [text](page:A4)

(def ^:private inline-re
  #"\*\*([^*\[\]]+)\*\*|\[([^\[\]*]+)\]\((help|page):([^()\s]+)\)")

(defn- check-plain
  "Plain text may not contain Markdown or HTML that this format does not allow."
  [line s]
  (when-let [bad (re-find #"[*`\[\]\\]|<[A-Za-z/!]" s)]
    (fail line (str "unknown syntax " (pr-str bad) " in " (pr-str s)))))

(defn parse-inline
  "Text -> vector of strings and inline items: [:strong s], [:help-link id s],
  [:page-link page-id s]. Links are resolved once all topics are known."
  [line s]
  (let [m (re-matcher inline-re s)]
    (loop [pos 0 out []]
      (if (.find m)
        (let [pre (subs s pos (.start m))
              _ (check-plain line pre)
              item (if (.group m 1)
                     [:strong (.group m 1)]
                     [(if (= "help" (.group m 3)) :help-link :page-link) (.group m 4) (.group m 2)])]
          (recur (.end m) (cond-> out (seq pre) (conj pre) true (conj item))))
        (let [tail (subs s pos)]
          (check-plain line tail)
          (cond-> out (seq tail) (conj tail)))))))

;; ---------------------------------------------------------------------------
;; Blocks

(defn- flush-block [blocks cur]
  (cond-> blocks
    cur (conj (case (:type cur)
                :p (into [:p] (parse-inline (:n cur) (:text cur)))
                :ol (into [:ol] (:items cur))
                :ul (into [:ul] (:items cur))))))

(defn- note-block [line text]
  (if-let [[_ rest-text] (re-matches #"Good to know:\s*(\S.*)" text)]
    (into [:p.help-note [:strong "Good to know:"] " "] (parse-inline line rest-text))
    (into [:p.help-note] (parse-inline line text))))

(defn- paragraph-line-problem [s]
  (cond
    (re-find #"^\s" s) "leading spaces are not allowed (no nested lists or code blocks)"
    (re-find #"^#" s) "headings other than `### ` are not allowed in a topic"
    (re-find #"^(\*(?!\*)|[+|])" s) "unknown syntax at the start of the line (use `- ` for bullets)"
    (re-find #"^-" s) "unknown syntax at the start of the line (a bullet is `- ` followed by text)"
    (re-find #"^\d+[.)]" s) "unknown syntax at the start of the line (a step is `1. ` followed by text)"
    (re-find #"^=+$" s) "unknown syntax"
    :else nil))

(defn parse-blocks
  "Body lines [[line-no text]...] -> vector of hiccup-ready blocks:
  [:p ...] [:h2 ...] [:ol [:li ...]...] [:ul [:li ...]...] [:p.help-note ...]."
  [lines]
  (loop [ls lines blocks [] cur nil]
    (if-let [[n s] (first ls)]
      (let [more (rest ls)]
        (cond
          (str/blank? s) (recur more (flush-block blocks cur) nil)

          (str/starts-with? s "### ")
          (let [t (str/trim (subs s 4))]
            (when (str/blank? t) (fail n "empty heading"))
            (recur more (conj (flush-block blocks cur) (into [:h2] (parse-inline n t))) nil))

          (str/starts-with? s ">")
          (let [m (re-matches #"> (\S.*)" s)]
            (when-not m (fail n "a note is `> ` followed by text"))
            (recur more (conj (flush-block blocks cur) (note-block n (second m))) nil))

          (re-find #"^\d+\." s)
          (let [[_ num text] (or (re-matches #"(\d+)\. (\S.*)" s)
                                 (fail n "a step is `1. ` followed by text"))
                num (parse-long num)
                cont? (= :ol (:type cur))
                expected (if cont? (inc (count (:items cur))) 1)]
            (when (not= num expected)
              (fail n (str "numbered steps must count 1, 2, 3 in order; expected " expected ", found " num)))
            (when (= :ul (:type cur)) (fail n "a numbered step directly after a bullet list; leave a blank line"))
            (let [item (into [:li] (parse-inline n text))]
              (if cont?
                (recur more blocks (update cur :items conj item))
                (recur more (flush-block blocks cur) {:type :ol :items [item]}))))

          (str/starts-with? s "- ")
          (let [[_ text] (or (re-matches #"- (\S.*)" s)
                             (fail n "a bullet is `- ` followed by text"))
                _ (when (= :ol (:type cur)) (fail n "a bullet directly after numbered steps; leave a blank line"))
                item (into [:li] (parse-inline n text))]
            (if (= :ul (:type cur))
              (recur more blocks (update cur :items conj item))
              (recur more (flush-block blocks cur) {:type :ul :items [item]})))

          :else
          (do
            (when-let [problem (paragraph-line-problem s)] (fail n problem))
            (when (#{:ol :ul} (:type cur))
              (fail n "text directly after a list; leave a blank line"))
            (if (= :p (:type cur))
              (recur more blocks (update cur :text str " " s))
              (recur more (flush-block blocks cur) {:type :p :n n :text s})))))
      (flush-block blocks cur))))

;; ---------------------------------------------------------------------------
;; Topic header: ## id | Title | roles: owner, support | pages: A4, A5 | group: Name

(defn- csv-field [line label s]
  (let [prefix (str label ":")]
    (when-not (str/starts-with? s prefix)
      (fail line (str "expected `" label ": ...` but found " (pr-str s))))
    (let [items (mapv str/trim (str/split (subs s (count prefix)) #","))]
      (when (some str/blank? items) (fail line (str label " has an empty value")))
      (when (not= (count items) (count (set items))) (fail line (str label " lists a value twice")))
      items)))

(defn parse-header [line s]
  (let [parts (mapv str/trim (str/split s #"\|" -1))]
    (when (not= 5 (count parts))
      (fail line (str "a topic header has 5 fields (id | title | roles: | pages: | group:), found " (count parts))))
    (let [[id title roles-s pages-s group-s] parts
          topic-roles (csv-field line "roles" roles-s)
          topic-pages (csv-field line "pages" pages-s)
          group (if (str/starts-with? group-s "group:") (str/trim (subs group-s 6)) (fail line "expected `group: ...`"))]
      (when-not (re-matches #"[a-z][a-z0-9]*(-[a-z0-9]+)*" id)
        (fail line (str "topic id must be lower-case words joined by hyphens, found " (pr-str id))))
      (when (str/blank? title) (fail line "missing topic title"))
      (when (str/blank? group) (fail line "missing group"))
      (check-plain line title)
      (check-plain line group)
      (doseq [r topic-roles] (when-not (roles r) (fail line (str "unknown role " (pr-str r) " (owner or support)"))))
      (doseq [p topic-pages] (when-not (contains? pages p) (fail line (str "unknown page id " (pr-str p)))))
      {:id id :title title :roles (vec (sort topic-roles)) :pages topic-pages :group group})))

;; ---------------------------------------------------------------------------
;; Whole file

(defn- plain-text
  "Summary text: plain, no links or bold."
  [line s]
  (let [items (parse-inline line s)]
    (when-not (every? string? items) (fail line "the summary must be plain text (no bold or links)"))
    (apply str items)))

(defn- split-sections
  "-> {:title :sections [{:line :header :lines [[n s]...]}]}"
  [text]
  (let [lines (map-indexed (fn [i s] [(inc i) s]) (str/split text #"\r?\n" -1))]
    (loop [ls lines title nil sections []]
      (if-let [[n s] (first ls)]
        (cond
          (str/starts-with? s "## ")
          (recur (rest ls) title (conj sections {:line n :header (subs s 3) :lines []}))

          (str/starts-with? s "# ")
          (do (when (or title (seq sections)) (fail n "only one `# ` title, at the top of the file"))
              (let [t (str/trim (subs s 2))]
                (when (str/blank? t) (fail n "empty title"))
                (recur (rest ls) t sections)))

          (str/blank? s) (recur (rest ls) title (cond-> sections (seq sections) (update-in [(dec (count sections)) :lines] conj [n s])))

          (empty? sections) (fail n (str "text before the first topic: " (pr-str s)))

          :else (recur (rest ls) title (update-in sections [(dec (count sections)) :lines] conj [n s])))
        (do (when-not title (fail 1 "the file must start with `# <help title>`"))
            (when (empty? sections) (fail 1 "no topics found"))
            {:title title :sections sections})))))

(defn- parse-section [{:keys [line header lines]}]
  (let [head (parse-header line header)
        [[sn ss] & body-lines] (drop-while (comp str/blank? second) lines)]
    (when-not (and sn (not (str/blank? ss)))
      (fail line (str "topic " (:id head) " has no summary line")))
    (when (re-find #"^(#|>|-|\d+\.|\s)" ss)
      (fail sn "the line after the topic header must be a one-line plain-text summary"))
    (assoc head :summary (plain-text sn ss) :body (parse-blocks body-lines))))

;; ---------------------------------------------------------------------------
;; Link resolution and whole-file checks

(defn- walk-inline [f x]
  (cond
    (and (vector? x) (#{:help-link :page-link} (first x))) (f x)
    (vector? x) (mapv #(walk-inline f %) x)
    :else x))

(defn- resolve-links [topics-by-id topic]
  (let [mine (set (:roles topic))]
    (update topic :body
            (fn [body]
              (walk-inline
               (fn [[kind target text]]
                 (case kind
                   :help-link
                   (let [t (or (get topics-by-id target)
                               (fail (str "topic " (:id topic) " links to unknown topic id " (pr-str target))))]
                     (when-not (every? (set (:roles t)) mine)
                       (fail (str "topic " (:id topic) " is visible to " (str/join ", " (:roles topic))
                                  " but links to " target ", which is only for " (str/join ", " (:roles t)))))
                     [:a {:href (str help-path "/" target)} text])
                   :page-link
                   (let [pg (or (get pages target)
                                (fail (str "topic " (:id topic) " links to unknown page id " (pr-str target))))]
                     (when (and (owner-only-pages target) (contains? mine "support"))
                       (fail (str "topic " (:id topic) " is visible to support but links to owner-only page " target)))
                     [:a {:href (:path pg)} text])))
               body)))))

(defn parse
  "admin-help.md text -> {:title :groups :topics}. Throws ex-info on any problem."
  [text]
  (let [{:keys [title sections]} (split-sections text)
        topics (mapv parse-section sections)
        ids (map :id topics)
        dup (first (keep (fn [[id n]] (when (> n 1) id)) (frequencies ids)))]
    (when dup (fail (str "duplicate topic id " (pr-str dup))))
    (let [by-id (into {} (map (juxt :id identity)) topics)
          topics (mapv #(resolve-links by-id %) topics)
          mapped (set (mapcat :pages topics))
          missing (remove mapped required-pages)]
      (when (seq missing)
        (fail (str "no topic maps to admin page " (str/join ", " missing) " (pages: A4 to A12 each need a topic)")))
      {:title title
       :groups (vec (distinct (map :group topics)))
       :topics topics})))

;; ---------------------------------------------------------------------------
;; Output: a committed ClojureScript namespace, one block per line so diffs read well

(def target-file "src/frontend/zandy/frontend/admin/help_data.cljs")
(def source-file "admin-help.md")

(defn- pr-block [b] (pr-str b))

(defn- render-topic [t]
  (str "   {:id " (pr-str (:id t)) "\n"
       "    :title " (pr-str (:title t)) "\n"
       "    :roles #{" (str/join " " (map pr-str (sort (:roles t)))) "}\n"
       "    :pages " (pr-str (:pages t)) "\n"
       "    :group " (pr-str (:group t)) "\n"
       "    :summary " (pr-str (:summary t)) "\n"
       "    :body\n"
       "    [" (str/join "\n     " (map pr-block (:body t))) "]}"))

(defn render
  "Parsed help -> source text of the data namespace."
  [{:keys [title groups topics]}]
  (str ";; GENERATED by scripts/build/help.clj from admin-help.md. Do not edit.\n"
       ";; Regenerated by scripts/build/build.sh; checked by the help build test.\n"
       "(ns zandy.frontend.admin.help-data\n"
       "  \"Staff help topics (design-spec.md A13) as plain data. Body blocks are\n"
       "  hiccup: [:p ...] [:h2 ...] [:ol [:li ...]] [:ul [:li ...]] [:p.help-note ...];\n"
       "  links are already [:a {:href path} text].\")\n\n"
       "(def title " (pr-str title) ")\n\n"
       "(def groups\n  " (pr-str groups) ")\n\n"
       "(def pages\n"
       "  \"Admin page id -> route name, the path a link opens, and its link text.\"\n"
       "  {" (str/join "\n   " (map (fn [[k v]] (str (pr-str k) " {:route " (:route v) " :path " (pr-str (:path v)) " :label " (pr-str (:label v)) "}")) pages)) "})\n\n"
       "(def topics\n"
       "  [" (str/join "\n" (let [ts (map render-topic topics)] (cons (str/triml (first ts)) (rest ts)))) "])\n"))

(defn generate
  "Read admin-help.md under `root` and return the data namespace text."
  [root]
  (render (parse (slurp (io/file root source-file)))))

(defn up-to-date?
  [root]
  (let [f (io/file root target-file)]
    (and (.exists f) (= (generate root) (slurp f)))))

(defn -main [& args]
  (let [root (-> (io/file *file*) .getAbsoluteFile .getParentFile .getParentFile .getParent)]
    (try
      (if (= ["--check"] args)
        (if (up-to-date? root)
          (println "help: help_data.cljs is up to date")
          (do (println "help: help_data.cljs is out of date; run `bb scripts/build/help.clj`") (System/exit 1)))
        (let [out (io/file root target-file)
              text (generate root)]
          (io/make-parents out)
          (spit out text)
          (println (str "help: wrote " target-file))))
      (catch clojure.lang.ExceptionInfo e
        (binding [*out* *err*] (println (str "help: " (ex-message e))))
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
