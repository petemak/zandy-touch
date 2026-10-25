(ns zandy.frontend.help-build-test
  "The help build step (scripts/build/help.clj, namespace `help`): the parser,
  each error case, and the committed data namespace being up to date. Runs on
  the JVM from ssr/ (clojure -M:test)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [help]))

(def root
  (first (filter #(.exists (io/file % "admin-help.md")) ["." ".." "../.."])))

(defn- header [& {:keys [id title roles pages group] :or {id "orders" title "Orders" roles "owner, support" pages "A4, A5" group "Orders"}}]
  (str "## " id " | " title " | roles: " roles " | pages: " pages " | group: " group))

(def rest-pages "A6, A7, A8, A9, A10, A11, A12")

(defn- doc
  "A minimal good document: one topic that maps every page A4 to A12, plus extra lines."
  [& extra-lines]
  (str/join "\n" (concat ["# Test help"
                          (header :pages (str "A4, A5, " rest-pages))
                          "A one-line summary."]
                         extra-lines
                         [""])))

(defn- fails-with
  "The message of the ex-info thrown when parsing `text`, or nil."
  [text]
  (try (help/parse text) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest parses-good-input
  (let [{:keys [title groups topics]}
        (help/parse (str "# My help\n"
                         (header :id "a-topic" :title "A topic" :roles "owner" :pages (str "A4, A5, " rest-pages) :group "First") "\n"
                         "The summary.\n"
                         "An intro with **bold** and [Other](help:b-topic) and [Orders](page:A4).\n"
                         "\n"
                         "### Steps\n"
                         "1. One\n2. Two **here**\n"
                         "\n"
                         "- bullet a\n- bullet b\n"
                         "\n"
                         "> Good to know: a note.\n"
                         "> Plain note.\n"
                         "\n"
                         (header :id "b-topic" :title "B topic" :pages "A1" :group "Second") "\n"
                         "B summary.\n"
                         "Text over\ntwo lines.\n"))]
    (is (= "My help" title))
    (is (= ["First" "Second"] groups))
    (is (= ["a-topic" "b-topic"] (map :id topics)))
    (let [[a b] topics]
      (is (= ["owner"] (:roles a)))
      (is (= ["owner" "support"] (:roles b)))
      (is (= "The summary." (:summary a)))
      (is (= ["A1"] (:pages b)))
      (is (= [[:p "An intro with " [:strong "bold"] " and " [:a {:href "/admin/help/b-topic"} "Other"] " and " [:a {:href "/admin"} "Orders"] "."]
              [:h2 "Steps"]
              [:ol [:li "One"] [:li "Two " [:strong "here"]]]
              [:ul [:li "bullet a"] [:li "bullet b"]]
              [:p.help-note [:strong "Good to know:"] " " "a note."]
              [:p.help-note "Plain note."]]
             (:body a)))
      (is (= [[:p "Text over two lines."]] (:body b))))))

(deftest the-minimal-document-is-good
  (is (nil? (fails-with (doc)))))

(deftest a-paragraph-may-start-with-bold
  (is (nil? (fails-with (doc "**What happened** says which it was:"))))
  (is (= [[:p [:strong "What happened"] " says which it was:"]]
         (:body (first (:topics (help/parse (doc "**What happened** says which it was:"))))))))

(deftest rejects-unknown-syntax
  (doseq [[what line] [["backticks" "Use `code` here."]
                       ["single asterisk emphasis" "Some *emphasis* here."]
                       ["unmatched bold" "Some **bold here."]
                       ["html" "A <b>tag</b> here."]
                       ["image" "![alt](x.png)"]
                       ["a plain link" "See [text](https://example.com)."]
                       ["a table row" "| a | b |"]
                       ["an indented line" "    indented"]
                       ["a level-two heading" "## not-a-topic"]
                       ["a level-four heading" "#### Deep"]
                       ["an asterisk bullet" "* item"]
                       ["a bullet without a space" "-item"]
                       ["a backslash" "Escaped \\* here"]]]
    (testing what
      (let [msg (fails-with (doc "" line))]
        ;; "## not-a-topic" is a (malformed) topic header, the others are body syntax
        (is (some? msg) what)
        (is (re-find #"admin-help\.md:\d+:" msg) (str what ": the message names the line"))))))

(deftest rejects-broken-lists-and-notes
  (is (re-find #"expected 1" (fails-with (doc "" "2. Starts at two"))))
  (is (re-find #"expected 2" (fails-with (doc "" "1. One" "3. Three"))))
  (is (re-find #"blank line" (fails-with (doc "" "1. One" "Text right after"))))
  (is (re-find #"blank line" (fails-with (doc "" "- One" "1. Step right after"))))
  (is (re-find #"note" (fails-with (doc "" ">no space")))))

(deftest rejects-a-malformed-file
  (testing "no title"
    (is (re-find #"must start with" (fails-with (str (header) "\nSummary.\n")))))
  (testing "two titles"
    (is (re-find #"only one" (fails-with (str "# One\n# Two\n" (header) "\nS.\n")))))
  (testing "text before the first topic"
    (is (re-find #"before the first topic" (fails-with (str "# T\nstray text\n" (header) "\nS.\n")))))
  (testing "no topics"
    (is (re-find #"no topics" (fails-with "# T\n"))))
  (testing "no summary"
    (is (re-find #"no summary" (fails-with (str "# T\n" (header) "\n")))))
  (testing "summary that is markup"
    (is (re-find #"summary" (fails-with (str "# T\n" (header) "\n- bullet\n"))))
    (is (re-find #"plain text" (fails-with (str "# T\n" (header) "\nA **bold** summary.\n"))))))

(deftest rejects-bad-headers
  (doseq [[what bad] [["too few fields" "## orders | Orders | roles: owner | pages: A4"]
                      ["a missing id" (header :id "")]
                      ["a bad id" (header :id "Bad_Id")]
                      ["a missing title" (header :title "")]
                      ["a missing group" (header :group "")]
                      ["a missing roles value" (header :roles "")]
                      ["an unknown role" (header :roles "owner, admin")]
                      ["a role twice" (header :roles "owner, owner")]
                      ["an unknown page" (header :pages "A4, A99")]
                      ["a malformed page" (header :pages "page4")]
                      ["an empty page list" (header :pages "")]
                      ["a swapped label" "## orders | Orders | pages: A4 | roles: owner | group: Orders"]]]
    (testing what
      (is (some? (fails-with (str "# T\n" bad "\nSummary.\n"))) what))))

(deftest rejects-duplicate-ids
  (let [t (fn [pages] (str (header :pages pages) "\nSummary.\n"))]
    (is (re-find #"duplicate topic id \"orders\""
                 (fails-with (str "# T\n" (t (str "A4, A5, " rest-pages)) "\n" (t "A1")))))))

(deftest rejects-links-to-unknown-things
  (is (re-find #"unknown topic id \"nope\"" (fails-with (doc "" "See [x](help:nope)."))))
  (is (re-find #"unknown page id \"A99\"" (fails-with (doc "" "See [x](page:A99)."))))
  (is (re-find #"unknown page id" (fails-with (doc "" "See [x](page:orders)."))))
  (testing "a topic for support may not link to an owner-only topic or page"
    (let [owner-topic (str (header :id "secret" :title "Secret" :roles "owner" :pages "A1" :group "G") "\nSecret summary.\n")]
      (is (re-find #"only for owner" (fails-with (str (doc "" "See [x](help:secret).") "\n" owner-topic))))
      (is (re-find #"owner-only page A12" (fails-with (doc "" "See [x](page:A12)."))))))
  (testing "an owner-only topic may link to owner-only things"
    (let [owner-topic (str (header :id "secret" :title "Secret" :roles "owner" :pages "A1" :group "G") "\nSecret summary.\n[Back](help:orders)\n")]
      (is (nil? (fails-with (str (doc) "\n" (str/replace owner-topic "[Back](help:orders)" "See [x](page:A12) and [y](help:orders).") "\n")))))))

(deftest rejects-an-unmapped-admin-page
  (doseq [p ["A4" "A5" "A6" "A7" "A8" "A9" "A10" "A11" "A12"]]
    (let [pages (str/join ", " (remove #{p} (concat ["A4" "A5"] (str/split rest-pages #", "))))
          msg (fails-with (str "# T\n" (header :pages pages) "\nSummary.\n"))]
      (is (re-find (re-pattern (str "no topic maps to admin page " p)) msg) p)))
  (testing "A1 to A3 and A13 may stay unmapped"
    (is (nil? (fails-with (doc))))))

(deftest the-real-file-parses
  (let [{:keys [topics groups]} (help/parse (slurp (io/file root "admin-help.md")))]
    (is (seq topics))
    (is (= ["Getting started" "Orders" "Products and stock" "Bulk import" "Messages" "Staff and account"] groups))
    (is (= ["owner"] (:roles (first (filter #(= "staff-accounts" (:id %)) topics)))))))

(deftest data-namespace-is-up-to-date
  (is (help/up-to-date? root)
      "src/frontend/zandy/frontend/admin/help_data.cljs is out of date; run `bb scripts/build/help.clj`"))

(deftest generated-namespace-is-plain-readable-data
  (let [forms (let [r (java.io.PushbackReader. (io/reader (io/file root help/target-file)))]
                (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} r))))
        topics (some (fn [f] (when (and (seq? f) (= 'def (first f)) (= 'topics (second f))) (last f))) forms)]
    (is (= 'ns (first (first forms))))
    (is (vector? topics))
    (is (every? #(and (:id %) (:title %) (set? (:roles %)) (vector? (:pages %)) (:group %) (:summary %) (vector? (:body %))) topics))))
