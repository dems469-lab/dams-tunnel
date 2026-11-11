# Historique des versions — DAMS TUNNEL

Toute version publiée est figée : on n'y revient jamais pour la modifier.
Chaque nouveau build validé incrémente `versionName` et `versionCode`.

| Version | versionCode | Commit | Base | Contenu |
|---------|-------------|--------|------|---------|
| 1.7.0 | 752 | `e816a24` | BUILD #170 (`7925714`) | Restauration exacte du BUILD #170 sous le nom DAMS TUNNEL, package `com.v2ray.ang`. |
| 1.7.1 | 753 | `2ed6a1b` | 1.7.0 | Robustesse du cycle de vie du moteur : plus de crash du daemon depuis la boucle de reconnexion, plus de `closed pipe` au rechargement, notification null-safe. Aucun changement de protocole, de moteur, d'UI ni de DNS. |
| 1.7.2 | 754 | _(branche `fix/shadowsocks-connection`)_ | 1.7.1 | Shadowsocks : validation préalable des profils (méthode, port, clés `2022-*` base64 de la bonne taille) et arrêt de la reconnexion en boucle sur une configuration que le moteur refuse, avec motif remonté à l'interface. Second chemin de notification null-safe. Tests unitaires exécutés en CI. |
| 1.8.0 | 755 | _(branche `feature/design-1.8.0`)_ | 1.7.2 | Refonte visuelle de l'accueil : fond bleu nuit, carte du monde numérique, bouton Power à anneaux lumineux, cartes cyan (état, serveur, débits, latence, qualité, consommation, durée, basculement, IP Finder), barre de navigation et en-tête restylés. Mesures réelles uniquement, alias de serveur masqué, aucune modification du moteur, des protocoles, des paramètres ni des données. |
| 1.8.1 | 756 | _(branche `feature/design-1.8.0`)_ | 1.8.0 | Ping et qualité de connexion réels : la sonde de santé du tunnel (déjà exécutée chaque seconde par le moteur) publie sa latence à l'interface, qui en déduit moyenne, jitter, perte, continuité et stabilité des débits. Aucune requête supplémentaire, aucun changement du moteur, de l'interface ou des réglages. |
