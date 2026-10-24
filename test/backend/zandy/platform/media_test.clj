(ns zandy.platform.media-test
  "Fitness check B17: the image pipeline. Tests that need libvips are skipped, with a message, when
  vipsthumbnail is not installed."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zandy.platform.media :as media]
            [zandy.test-helpers :as t])
  (:import (java.io File)
           (java.nio.file Files)))

(defn- temp-file ^File [^bytes bs]
  (let [f (File/createTempFile "media-test" ".bin")]
    (Files/write (.toPath f) bs (make-array java.nio.file.OpenOption 0))
    f))

(defn- store [] (media/local-store (str (Files/createTempDirectory "zandy-media" (make-array java.nio.file.attribute.FileAttribute 0)))))

(deftest type-detection-uses-content-not-names
  (is (= "image/jpeg" (media/detect-type (temp-file (t/jpeg-bytes)))))
  (is (nil? (media/detect-type (temp-file (.getBytes "this is really a text file renamed .jpg")))))
  (is (nil? (media/detect-type (temp-file (byte-array 0)))))
  (is (= "image/png" (media/detect-type (temp-file (byte-array (concat (map unchecked-byte [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]) (repeat 8 0)))))))
  (is (= "image/webp" (media/detect-type (temp-file (.getBytes "RIFF\u0000\u0000\u0000\u0000WEBPVP8 "))))))

(deftest rejections-use-the-contract-codes
  (let [s (store) id (random-uuid)]
    (testing "text renamed .jpg"
      (is (= "unsupportedMediaType" (get-in (media/process-upload! s :stub id (temp-file (.getBytes "not an image at all"))) [:error :code]))))
    (testing "5.1 MB"
      (let [big (byte-array (+ (* 5 1024 1024) 100000))]
        (aset-byte big 0 (unchecked-byte 0xFF)) (aset-byte big 1 (unchecked-byte 0xD8)) (aset-byte big 2 (unchecked-byte 0xFF))
        (is (= "fileTooLarge" (get-in (media/process-upload! s :stub id (temp-file big)) [:error :code])))))
    (testing "6001 px on a side"
      (is (= "dimensionsTooLarge" (get-in (media/process-upload! s :stub id (temp-file (t/jpeg-bytes 6001 8))) [:error :code]))))
    (testing "a corrupt jpeg"
      (is (= "unreadableImage" (get-in (media/process-upload! s :stub id (temp-file (byte-array (concat (map unchecked-byte [0xFF 0xD8 0xFF]) (repeat 40 7))))) [:error :code]))))))

(deftest stores-three-variants-with-the-stub-processor
  (let [s (store) id (random-uuid)
        r (media/process-upload! s :stub id (temp-file (t/jpeg-bytes 100 50)))]
    (is (= {:content-type "image/jpeg" :width 100 :height 50} (:ok r)))
    (is (every? #(some? (media/open s id %)) [:thumb :medium :large]))
    (media/delete-photo! s id)
    (is (nil? (media/open s id :thumb)))))

(deftest vips-pipeline
  (if-not (media/vips-available?)
    (println "SKIPPED zandy.platform.media-test/vips-pipeline: vipsthumbnail is not installed on this machine")
    (let [s (store) id (random-uuid)
          r (media/process-upload! s :vips id (temp-file (t/jpeg-bytes 1600 1000)))
          width (fn [v] (parse-long (str/trim (:out (shell/sh "vipsheader" "-f" "width" (.getPath (media/open s id v)))))))
          fields (fn [v] (:out (shell/sh "vipsheader" "-a" (.getPath (media/open s id v)))))]
      (is (some? (:ok r)))
      (is (= [480 800 1200] (map width [:thumb :medium :large])))
      (is (not (re-find #"exif-" (fields :large))))
      (testing "never enlarges"
        (let [id2 (random-uuid)]
          (media/process-upload! s :vips id2 (temp-file (t/jpeg-bytes 300 200)))
          (is (= 300 (parse-long (str/trim (:out (shell/sh "vipsheader" "-f" "width" (.getPath (media/open s id2 :large)))))))))))))
