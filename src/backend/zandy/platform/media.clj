(ns zandy.platform.media
  "Photo storage and the image pipeline. MediaStore keeps files (local directory at launch, an
  object store later); the pipeline checks the file type by content, reads dimensions and produces
  the 480/800/1200 px WebP variants with the libvips command line tools."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [zandy.platform.log :as log]
            [zandy.platform.util :as util])
  (:import (java.io File)
           (java.nio.file Files)
           (javax.imageio ImageIO)))

(def variants {:thumb 480 :medium 800 :large 1200})
(def max-bytes (* 5 1024 1024))
(def max-side 6000)

(def urls util/media-urls)

(defprotocol MediaStore
  (put! [store photo-id variant ^File file] "Stores a variant file.")
  (open [store photo-id variant] "Returns a File for the variant, or nil.")
  (delete-photo! [store photo-id] "Removes every variant of the photo."))

(defrecord LocalStore [dir]
  MediaStore
  (put! [_ photo-id variant file]
    (let [target (io/file dir "public" (str photo-id "-" (name variant) ".webp"))]
      (io/make-parents target)
      (Files/copy (.toPath ^File file) (.toPath target)
                  (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      target))
  (open [_ photo-id variant]
    (let [f (io/file dir "public" (str photo-id "-" (name variant) ".webp"))]
      (when (.isFile f) f)))
  (delete-photo! [_ photo-id]
    (doseq [v (keys variants)]
      (io/delete-file (io/file dir "public" (str photo-id "-" (name v) ".webp")) true))))

(defn local-store [dir] (->LocalStore dir))

;; ---- type detection ---------------------------------------------------------------------------

(defn detect-type
  "Detects JPEG, PNG or WebP from the first bytes, never from the file name. Returns the MIME type."
  [^File file]
  (with-open [in (io/input-stream file)]
    (let [head (byte-array 12)
          n (.read in head)
          b (fn [i] (bit-and (aget head i) 0xff))]
      (cond
        (< n 12) nil
        (and (= (b 0) 0xFF) (= (b 1) 0xD8) (= (b 2) 0xFF)) "image/jpeg"
        (and (= (b 0) 0x89) (= (b 1) 0x50) (= (b 2) 0x4E) (= (b 3) 0x47)) "image/png"
        (and (= (b 0) 0x52) (= (b 1) 0x49) (= (b 2) 0x46) (= (b 3) 0x46)
             (= (b 8) 0x57) (= (b 9) 0x45) (= (b 10) 0x42) (= (b 11) 0x50)) "image/webp"
        :else nil))))

;; ---- processors -------------------------------------------------------------------------------

(defn vips-available? []
  (try (zero? (:exit (shell/sh "vipsthumbnail" "--version"))) (catch Throwable _ false)))

(defn- vips-dimensions [^File file]
  (let [w (shell/sh "vipsheader" "-f" "width" (.getPath file))
        h (shell/sh "vipsheader" "-f" "height" (.getPath file))]
    (when (and (zero? (:exit w)) (zero? (:exit h)))
      {:width (parse-long (.trim ^String (:out w))) :height (parse-long (.trim ^String (:out h)))})))

(defn- stub-dimensions [^File file]
  (when-let [img (try (ImageIO/read file) (catch Throwable _ nil))]
    {:width (.getWidth img) :height (.getHeight img)}))

(defn dimensions
  "Reads the pixel size. Returns nil when the file cannot be read as an image."
  [processor file]
  (try (case processor :vips (vips-dimensions file) :stub (stub-dimensions file))
       (catch Throwable _ nil)))

(defn- vips-variant! [^File in ^File out target-width]
  (let [{:keys [exit err]} (shell/sh "vipsthumbnail" (.getPath in)
                                     "--size" (str target-width "x")
                                     "--rotate"
                                     "-o" (str (.getPath out) "[Q=80,strip]"))]
    (when-not (zero? exit)
      (throw (ex-info "vipsthumbnail failed" {:exit exit :err err})))))

(defn- make-variants!
  "Writes the three WebP variants into tmp-dir and returns {variant File}. The :stub processor
  (dev and tests only, rejected in prod) copies the source bytes instead of converting."
  [processor ^File in {:keys [width]} ^File tmp-dir]
  (into {}
        (for [[variant target] variants]
          (let [out (File. tmp-dir (str (name variant) ".webp"))]
            (case processor
              :vips (vips-variant! in out (min target width)) ;; never enlarge
              :stub (Files/copy (.toPath in) (.toPath out)
                                (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))
            [variant out]))))

(defn process-upload!
  "Validates one uploaded file and stores its variants. Returns {:ok {...}} or
  {:error {:code :message}} using the contract's per-file error codes. The upload itself is never kept."
  [store processor photo-id ^File file]
  (let [size (.length file)]
    (cond
      (> size max-bytes) {:error {:code "fileTooLarge" :message "This file is larger than 5 MB."}}
      (nil? (detect-type file)) {:error {:code "unsupportedMediaType" :message "Only JPEG, PNG and WebP images are accepted."}}
      :else
      (if-let [{:keys [width height] :as dims} (dimensions processor file)]
        (if (or (> width max-side) (> height max-side))
          {:error {:code "dimensionsTooLarge" :message "This image is larger than 6000 pixels on a side."}}
          (let [tmp (Files/createTempDirectory "zandy-img" (make-array java.nio.file.attribute.FileAttribute 0))
                tmp-dir (.toFile tmp)]
            (try
              (let [made (make-variants! processor file dims tmp-dir)]
                (doseq [[variant f] made] (put! store photo-id variant f))
                {:ok {:content-type (detect-type file) :width width :height height}})
              (catch Throwable ex
                (log/error :image-processing-failed (log/exception-data ex))
                (delete-photo! store photo-id)
                {:error {:code "unreadableImage" :message "This image could not be read."}})
              (finally
                (doseq [f (.listFiles tmp-dir)] (io/delete-file f true))
                (io/delete-file tmp-dir true)))))
        {:error {:code "unreadableImage" :message "This image could not be read."}}))))
