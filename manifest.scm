;; Guix dev environment for building Antique Atlas (MC 1.7.10) with RetroFuturaGradle.
;;
;; Gradle 8.8 runs on OpenJDK 21. RFG compiles Minecraft and the mod against a Java 8
;; toolchain (icedtea = OpenJDK 8), which rfg-build.sh supplies by store path — both
;; JDKs come from Guix, because a JDK auto-downloaded by Gradle is a generic-Linux
;; binary that won't run on Guix System.
;;
;; Recommended use:   ./rfg-build.sh build
;; Interactive shell: guix shell -m manifest.scm
;;
;; (icedtea is intentionally NOT listed here: two JDKs in one profile would both ship
;;  bin/java and collide. rfg-build.sh hands its path to Gradle directly instead.)
(specifications->manifest
 (list "openjdk@21.0.2:jdk"        ; runs Gradle
       ;; POSIX tooling so ./gradlew works even in a pure shell
       "bash" "coreutils" "findutils" "grep" "sed" "gawk"
       "which" "gzip" "unzip" "git" "nss-certs"))
