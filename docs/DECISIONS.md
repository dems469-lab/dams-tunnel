# Décisions Dams Tunnel

## 2026-10-13 — Base figée

La base de développement est v2rayNG 2.3.11, commit
`e1b73f2fa8487dfc303da52d109719c1e32386b4`. Aucun changement upstream ne sera
introduit avant la 1.0.0.

## 2026-10-13 — Compilation native

La machine de développement est ARM64. Le NDK 30.0.16248370 fourni par Google
contient uniquement les outils hôtes Linux x86_64 ; `compile-hevtun.sh` échoue donc
avec `Unknown host CPU architecture: aarch64`. La compilation native est déléguée
à GitHub Actions sur runner x86_64.

