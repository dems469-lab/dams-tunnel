# VPN classique — transfert, tests réels et bascule automatique

- Branche : `feature/laboratory-multipath`
- Version livrée : 1.9.4-lab (`versionCode` 761)
- Validation : `Dams CI` (tests unitaires + lint + builds de contrôle) puis `Dams APK`
- Le laboratoire DAMS MULTIPATH et le moteur Xray ne sont pas modifiés.

## 1. Audit (phase 1)

Défauts constatés avant correction, avec leurs preuves dans le code :

| # | Défaut | Preuve |
|---|---|---|
| 1 | Le transfert vers Configs réécrivait `subscriptionId` et pouvait renvoyer un serveur dans son abonnement d'origine, donc hors de l'onglet Configs. | `ConfigsTransferManager.transfer` utilisait le profil décodé tel quel. |
| 2 | L'empreinte de doublon ignorait `flow`, `alpn`, `fingerprint`, `publicKey`, `shortId` : deux configurations réellement différentes étaient fusionnées. | `ReconfigurationFingerprint.of(listOf(...))` sur 10 champs seulement. |
| 3 | Un doublon « déjà présent » était supprimé de la Reconfiguration sans avoir été transféré par cette action. | `removable += server.id` dans la branche `alreadyPresent`. |
| 4 | Après écriture, aucun contrôle ne vérifiait que le profil enregistré était identique à celui préparé. | `encodeServerConfig` puis `added++` sans relecture. |
| 5 | La bascule automatique choisissait `firstOrNull()` : aucun classement, aucun seuil, aucune vérification réelle du nouveau serveur. | Ancien `CoreServiceManager.tryFailover`. |
| 6 | La préférence de bascule était désactivée par défaut. | `decodeSettingsBool(..., false)` et `rememberMmkvBool(..., false)`. |
| 7 | Les tests réels ne conservaient ni la nature de la mesure, ni son horodatage, ni les pertes ; les tests TCP et tunnel partageaient le même champ. | `MmkvManager.encodeServerTestDelayMillis` unique. |

## 2. Transfert Reconfiguration → Configs (phase 2)

- La destination est **toujours** l'onglet Configs : `subscriptionId = ""` et
  `policyGroupSubscriptionId = null` avant l'écriture, quel que soit le groupe
  d'origine du profil reconfiguré.
- L'empreinte (`ConfigFingerprint`) est calculée sur l'**outbound Xray normalisé**
  (clés triées, SHA-256). Elle couvre protocole, adresse, port, identifiant,
  `flow`, transport, TLS/Reality, SNI, Host, chemin, `alpn`, `fingerprint`,
  `publicKey`, `shortId`. Un simple renommage ne crée pas de doublon ; un
  changement de `flow` ou d'`alpn` en crée un.
- Les doublons sont **conservés** dans Reconfiguration (`duplicateIds`), seuls
  les ajouts réellement enregistrés sont retirés (`removableIds`).
- Après écriture, le profil est relu et son empreinte doit être identique ;
  sinon la ligne compte comme un échec et reste dans Reconfiguration.
- Le message de résultat distingue ajoutés / déjà présents conservés / invalides
  / échecs (anglais et français).

Tests : `ConfigFingerprintTest` (7 cas).

## 3. Écran Tester (phases 3 et 4)

Nouvel écran accessible depuis le tiroir principal.

- **Deux listes cloisonnées** : `Configs` (profils locaux, hors abonnements
  `dams-public-*`) et `Serveurs gratuits` (abonnements `dams-public-*`). Aucun
  mélange, aucune action d'une liste ne touche l'autre.
- **Deux mesures distinctes**, jamais confondues :
  - *Test de tunnel* : le cœur Xray est démarré sur un profil temporaire et une
    requête réelle traverse le tunnel (`CoreTestService`, non TCP) ;
  - *Ping réseau (TCP)* : simple connexion TCP, qui ne prouve pas le tunnel.
  Les deux valeurs sont affichées séparément, avec leur horodatage.
- **File dédiée** de 5 à 10 tests en parallèle (réglage propre à l'écran,
  borné), annulable, reprenable : chaque résultat est persisté par serveur.
- **Tri** par rapidité pénalisée par les pertes mesurées, puis stabilité ;
  les serveurs non mesurés restent en fin de liste.
- **Débit** : affiché « — ». L'AAR Xray épinglée n'expose pas de débit par
  serveur ; rien n'est inventé.

Tests : `TesterRankingTest` (4 cas).

## 4. Bascule automatique (phases 5 et 6)

Moteur : `AutoFailoverPolicy` (décision pure) + `ServerHealthStore` (mesures
persistées) + `CoreServiceManager` (application réelle).

- **Candidats** : uniquement les serveurs du groupe du profil actif, hors
  bibliothèque publique (`dams-public-*`), hors groupes. Le laboratoire est
  explicitement exclu (`LaboratoryTunnelBridge.read(service) != null`).
- **Score qualité** `/100` = disponibilité mesurée (65 %) + latence mesurée
  (35 %). Aucune mesure ⇒ score 0 et serveur jamais candidat.
- **Règles de décision** :
  - serveur actif sain (disponibilité ≥ 90 %) : bascule seulement si le gain
    de score ≥ 18 points ;
  - serveur actif dégradé : bascule dès qu'un candidat mesuré fait mieux ;
  - mesures de plus de 10 minutes ignorées ;
  - refroidissement de 3 minutes après une bascule (ne bloque pas un sauvetage
    d'un serveur dégradé).
- **Vérification réelle** : le candidat est réellement appliqué
  (`setSelectServer` + rechargement du cœur), puis le moteur attend la
  vérification du trafic. Un simple redémarrage du cœur ne suffit pas.
- **Retour arrière** : si le candidat ne porte pas de trafic, le serveur
  précédent est restauré, revérifié, et l'échec est enregistré.
- **Historique** : 20 derniers événements (de → vers, motif, confirmée ou
  annulée, horodatage), consultables dans l'écran « Bascule automatique ».
- **Activation** : activée par défaut, désactivable (accueil et écran dédié).
- **Mesures** : alimentées par les tests réels de l'écran Tester, la sonde de
  santé du tunnel (1 s, écriture limitée à 1/5 s) et la vérification de
  connexion. Aucune valeur simulée.

Tests : `AutoFailoverPolicyTest` (9 cas).

## 5. Écran Bascule automatique (phase 5, partie visible)

- activation ;
- serveur actif (nom, adresse, score, latence, pertes mesurées) ;
- meilleur serveur mesuré ;
- dernière mesure réelle et dernière bascule ;
- historique des bascules.

L'écran ne déclenche aucune bascule : il n'affiche que des mesures réelles.

## 6. Non-régression (phase 7)

- Le laboratoire n'est ni modifié ni piloté par la bascule classique ; ses
  tests unitaires (`laboratory/multipath`) passent toujours.
- Un seul `VpnService` : aucun service VPN supplémentaire n'a été créé.
- L'écran Configs, la Reconfiguration, les abonnements et les profils
  utilisateur ne sont pas modifiés hors transfert explicite.
- `Dams CI` exécute `testPlaystoreDebugUnitTest lintPlaystoreDebug` puis les
  builds de contrôle (standard, premium test).

## 7. Limites assumées

- Le débit réel par serveur n'est pas exposé par l'AAR épinglée : il reste
  affiché « — ».
- Le rechargement du cœur lors d'une bascule interrompt les sessions TCP en
  cours (comportement Xray) ; c'est pour cela qu'une bascule n'est faite que
  lorsqu'elle est justifiée par les mesures.
- Un serveur sans mesure récente n'est jamais choisi automatiquement : aucun
  classement n'est inventé.
- L'écran principal peut afficher l'ancien serveur sélectionné jusqu'au
  prochain rafraîchissement ; la notification et l'écran de bascule reflètent
  l'état réel.

## 8. Scénarios imposés et couverture

| Scénario | Couverture |
|---|---|
| 1. Transfert d'un serveur reconfiguré vers Configs | `ConfigsTransferManager` + test d'empreinte |
| 2. Profil d'origine non modifié | copie avant écriture, original jamais réécrit |
| 3. Doublon conservé dans Reconfiguration | `duplicateIds`, message dédié |
| 4. Paramètre réellement différent non fusionné | `ConfigFingerprintTest` (`flow`, `alpn`, `fingerprint`) |
| 5. Renommage seul non dupliqué | `ConfigFingerprintTest` |
| 6. Relecture d'intégrité après écriture | `storedIntact` |
| 7. Test Configs séparé des gratuits | listes `TesterScope` |
| 8. Test tunnel distingué du ping TCP | `TesterKind` + stockage typé |
| 9. Résultats horodatés | `tunnelAt` / `tcpAt` |
| 10. File 5–10 réglable | `PREF_TESTER_CONCURRENCY` borné |
| 11. Annulation et reprise | `cancelTest`, résultats persistés |
| 12. Classement rapidité + stabilité | `TesterRankingTest` |
| 13. Bascule vers un serveur mesuré meilleur | `AutoFailoverPolicyTest` |
| 14. Pas de bascule pour un gain marginal | `insufficient_gain` |
| 15. Serveur dégradé remplacé | `active_degraded` |
| 16. Échec du candidat → retour arrière | `applyFailover` + historique `rollback` |
| 17. Laboratoire et bibliothèque publique exclus | gardes dans `tryFailover` |
