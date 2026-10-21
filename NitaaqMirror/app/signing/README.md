`nitaaq-test.jks` is a STABLE TEST signing key (PKCS12, password `nitaaqmirror`), committed on purpose so
every build — controller and full, every version — is signed identically and installs as an update over the
previous one. It protects nothing secret. Anyone can read it; do not use it for a real published app.

For a private build, set env vars NITAAQ_KEYSTORE (PKCS12 path), NITAAQ_KEYSTORE_PASS, NITAAQ_KEY_ALIAS;
the build then uses that key instead. In CI, set repo secrets NITAAQ_KEYSTORE_B64 and NITAAQ_KEYSTORE_PASS.
