(ns zandy.architecture.contract
  "Reads api-contract.md so tests can compare the route table with it."
  (:require [clojure.string :as str]))

(defn contract-text
  "The text of api-contract.md, read from the project root (tests run from there)."
  [] (slurp "api-contract.md"))

(defn- normalize-path [p]
  (-> p (str/replace "/media/{photoId}-{variant}.webp" "/media/:param") (str/replace #"\{[^}]+\}" ":param")))

(defn endpoints
  "Every `### METHOD /path` heading: {:method :path :access}. Access comes from the section's
  `Access:` line, else from the path (admin staff endpoints are owner-only, other admin ones staff)."
  []
  (let [text (contract-text)
        sections (str/split text #"(?m)^### ")]
    (vec
     (for [sec (rest sections)
           :let [[_ method path] (re-find #"^([A-Z]+) (/\S+)" sec)]
           :when method
           :let [declared (some-> (re-find #"Access: (public|staff|owner)" sec) second keyword)
                 access (or declared
                            (cond (str/starts-with? path "/api/admin/staff") :owner
                                  (str/starts-with? path "/api/admin/") :staff
                                  :else :public))]]
       {:method (keyword (str/lower-case method)) :path path :norm (normalize-path path) :access access}))))

(defn route-norm
  "Normalises a route path for comparison with the contract: every \":name\" segment becomes \":param\"."
  [path] (str/replace path #":[A-Za-z-]+" ":param"))
