`nitaaq-test.p12` is a **test-only** signing key (password `nitaaq-test`), committed so that every build
(local or CI) produces APKs with the same signature and can be installed over each other.
It protects nothing secret. For a private distribution build, set `NITAAQ_KEYSTORE`, `NITAAQ_KEYSTORE_PASS`
and `NITAAQ_KEY_ALIAS`; the build then uses that key instead.
