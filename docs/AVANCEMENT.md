# Avancement Dams Tunnel

- Phase : 2 — améliorations fonctionnelles
- Branche : `feature/premium`
- Base : v2rayNG 2.3.11 (`e1b73f2fa8487dfc303da52d109719c1e32386b4`)
- Dernière vérification : sous-modules initialisés ; AAR Xray vérifié
- Compilation locale : impossible sur l'hôte ARM64 (JDK/SDK absents, NDK 30 en x86_64 uniquement)
- Validation : GitHub Actions — `Dams CI` (tests unitaires + lint) et `Dams APK` (APK signé)
- Prochaine étape : chantiers ci-dessous, un commit par chantier

## Chantiers

| # | Chantier | État |
|---|---|---|
| 1 | Partage de connexion (hotspot / LAN) | à faire |
| 2 | Refonte de l'interface (animations, transparence, menu ⋮ à gauche) | à faire |
| 3 | Gestion des serveurs (serveur public → « Mes serveurs ») | fait |
| 4 | Réorganisation des menus (Fournisseurs, IP Finder, Journal, sauvegarde) | fait |
| 5 | Console Xray (journal de diagnostic) | fait |
| 6 | Nouveaux paramètres (notifications, TLS global, taille de police, sauvegarde) | fait |
| 7 | Vérifications et performance | à faire |

## Journal des modifications

- Renommage de la catégorie par défaut `DEFAULT` en « Mes serveurs » (libellé traduit,
  valeur stockée inchangée) et de « Abonnements » en « Fournisseurs ».
- Onglets : Accueil, Serveurs, IP Finder, Paramètres. Le Journal passe dans le menu ⋮,
  qui est désormais à gauche ; « Sauvegarde et restauration » passe dans les paramètres.
- Serveurs publics : action « Ajouter à mes serveurs » qui copie le profil choisi dans la
  catégorie par défaut, avec un nouvel identifiant, sans toucher au fournisseur public.
- Paramètres : taille de texte (5 niveaux), retour de connexion (silencieux / vibration / son
  pour la connexion, la déconnexion et les notifications) et interrupteur TLS global limité
  aux profils compatibles.
- Console Xray : tampon mémoire borné alimenté par l'application et par le flux logcat du
  cœur, filtres par niveau, recherche, copie totale ou sélectionnée, export `.txt` et
  effacement confirmé.
