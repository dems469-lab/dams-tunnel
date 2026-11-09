# Historique des versions — DAMS TUNNEL

Toute version publiée est figée : on n'y revient jamais pour la modifier.
Chaque nouveau build validé incrémente `versionName` et `versionCode`.

| Version | versionCode | Commit | Base | Contenu |
|---------|-------------|--------|------|---------|
| 1.7.0 | 752 | `e816a24` | BUILD #170 (`7925714`) | Restauration exacte du BUILD #170 sous le nom DAMS TUNNEL, package `com.v2ray.ang`. |
| 1.7.1 | 753 | _(branche `fix/reconnect-lifecycle`)_ | 1.7.0 | Robustesse du cycle de vie du moteur : plus de crash du daemon depuis la boucle de reconnexion, plus de `closed pipe` au rechargement, notification null-safe. Aucun changement de protocole, de moteur, d'UI ni de DNS. |
