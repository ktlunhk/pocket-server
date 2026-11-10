Place your ARM64 Android binaries here before building:

  server/bin/lighttpd
  server/bin/php-cgi
  server/bin/mariadbd
  server/bin/mariadb-install-db   (optional, first-run database setup)
  server/bin/mariadb-admin        (optional, clean shutdown)
  server/lib/                     (shared libraries they need)
  server/lib/php/                 (php extensions such as mysqli.so)
  server/share/                   (MariaDB share folder: charsets, english/errmsg.sys)

The binaries must be built for Android (Bionic libc), for example with the
Termux build recipes. Binaries from desktop Linux will not run.
