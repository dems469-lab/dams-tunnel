# Journal des modifications — DAMS TUNNEL

Un seul fichier d'historique. Chaque entrée décrit une intervention terminée,
les fichiers touchés, ce qui a été vérifié et ce qui reste à vérifier.

## 1.10.0-lab (770) — correctifs issus de l'audit complet

**Contexte** : audit statique complet de la 1.9.9-lab (rapport :
`RAPPORT-AUDIT-2026-11-14.md`). Les constats de sévérité élevée/moyenne sont
corrigés ci-dessous. Les points « non appliqués » sont listés explicitement en
fin d'entrée, avec leur raison.

**Correctifs**
- `core/CoreLoopLifecycleGate.kt` : le délai borne désormais aussi l'attente du
  verrou. Un arrêt en cours bloqué dans le moteur natif (JNI non
  interruptible) ne suspend plus indéfiniment l'arrêt demandé ; `withLock`
  garantit la libération du verrou.
- `core/CoreServiceManager.kt` : `reloadCore` borne `coreEngine.stop()`
  (20 s) et abandonne le rechargement si le stop natif pend, au lieu de
  lancer un second cœur par-dessus l'ancien. Les bascules annulées sont
  tracées dans la console (`Failover rollback`, `Failover aborted`).
- `service/CoreProxyOnlyService.kt`, `service/CoreRootService.kt` : ajout de
  `detachServiceReceiver` + `clearServiceControl` dans `onDestroy`, comme
  `CoreVpnService`. Corrige le récepteur laissé sur un service détruit et le
  refus de rattachement du service suivant.
- `service/CoreVpnService.kt` (`isTunnelRelayRunning`) : fail-closed. Une
  exception du relais n'est plus interprétée comme « relais vivant ».
- `service/CoreVpnService.kt`, `CoreProxyOnlyService.kt`, `CoreRootService.kt` :
  `CoroutineExceptionHandler` sur les trois scopes de service. Une exception
  qui s'échappe d'une coroutine ne tue plus le processus `:daemon` (crash
  FATAL) : elle est journalisée et la surveillance poursuit la récupération.
- `core/CoreOutboundBuilder.kt` : ports VMess/VLESS/Trojan/SOCKS/HTTP/
  Hysteria2 via `toIntOrNull()` avec `ConfigValidationException("Port serveur
  invalide (...)")` ; mux et `reserved` WireGuard protégés de la même façon.
- `handler/SettingsManager.kt` : MTU bornée à 68..65535 (valeur aberrante
  refusée par `VpnService.Builder`) et port SOCKS hors 1..65535 ramené au
  défaut.
- `ui/main/MainViewModel.kt` : fenêtre de grâce de 1 s après
  `MeasureConfigFinish` et second flush avant `onTestsFinished` : un résultat
  arrivé après l'annonce de fin n'est plus perdu (broadcasts non ordonnés).
- `ui/UrlSchemeActivity.kt` : import exécuté dans une portée détachée du
  cycle de vie. `finish()` (immédiat) n'annule plus l'import d'abonnement en
  cours ; l'échec s'affiche en toast d'erreur.
- `ui/backup/BackupActivity.kt` : verrou d'application appliqué comme dans
  `MainActivity` (prompt clavier/biométrie avant tout accès). Ferme le
  contournement de `PREF_APP_LOCK` par l'activité exportée.
- `res/xml/cache_paths.xml` : le FileProvider n'expose plus tout le cache
  (`path="/"`) mais seulement `shared_backups/` et `shared_logs/`.
  `ui/backup/BackupViewModel.kt` écrit l'archive de partage dans
  `shared_backups/` ; `ui/backup/BackupTempFiles.kt` nettoie aussi ce
  sous-dossier.
- `receiver/TaskerReceiver.kt` + réglage `PREF_TASKER_CONTROL` (défaut
  activé) : le récepteur reste exporté pour Tasker, mais l'utilisateur peut
  couper le pilotage externe du VPN. Nouvelle ligne dans les réglages
  (`title_tasker_control` / `summary_tasker_control`).
- Version `1.10.0-lab`, `versionCode` 770.

**Tests unitaires ajoutés/mis à jour**
- `CoreLoopLifecycleGateTest` : un `stop` échoue vite (au lieu de suspendre)
  quand une autre opération détient la porte.
- `BackupTempFilesTest` : le nettoyage couvre `shared_backups/`.

**Vérifications réellement exécutées**
- CI `dams-ci` (run 43624606715) : `testPlaystoreDebugUnitTest lintPlaystoreDebug`
  + builds debug/release + vérification de l'isolation Premium →
  **succès** (13 min 18 s, artefact `dams-premium-test-apk` produit).
- CI `dams-ci` (run 43639493269, commit de documentation) : **succès**.
- CI `dams-build` (run 43640442989) : APK signés produits → **succès**.
- CI `dams-publish` (run 43642978980) : dépôt des APK sur la release →
  **succès**.
- Release `v1.10.0-lab` (tag sur `5eac407`, Latest) : arm64
  `DamsTunnel_1.10.0-lab_arm64-v8a.apk` (33 501 126 o, SHA-256
  `8a80c4476994fb524e9b803b0f6692de5f30315b8cec85b04e90dcd071c5f85d`) et
  universel `DamsTunnel_1.10.0-lab_universal.apk` (73 127 759 o, SHA-256
  `55669ad907bec26394c0768b6fb32d7f6a69d1656887f63ab181315e2b75a60d`).
- Aucun autre test n'a pu être exécuté ici (pas de JDK/SDK/appareil).

**Non appliqué (décision explicite)**
- **Configs CUSTOM non assainies** (`buildV2rayCustomConfig` laisse les
  inbounds du JSON tels quels) : non modifié sur votre demande (« sans
  toucher aux configs custom »). Risque documenté : un JSON importé peut
  ouvrir un inbound sur le LAN.
- **MMKV non chiffré** : non modifié. La migration vers `cryptKey` (Keystore)
  touche les storages multi-processus et les sauvegardes ; elle exige une
  procédure de migration testable sur appareil, à planifier séparément.
- **Confirmations d'import** (`v2rayng://install-config|install-sub`) : non
  ajoutées (choix « sans confirmation ») ; seule la course `finish()` est
  corrigée.
- Points faibles/informatifs du rapport (F1-F11 : `QUERY_ALL_PACKAGES`,
  `isMinifyEnabled=false`, FileProvider, `currentTimeMillis` pour les débits,
  etc.) : inchangés.

**Non vérifié**
- Aucun test terrain sur téléphone (poignée de main réelle, TCP/UDP, DNS,
  débits, reconnexion, app-lock sur appareil, partage de sauvegarde).
- Le comportement du stop natif lorsqu'il pend réellement n'a pas été
  reproduit ; le correctif borne l'attente et évite le double démarrage.

## 1.9.9-lab (766) — correctif : profils TLS/REALITY et service de test

**Contexte** : après la migration 1.9.8-lab (Xray-core v26.11.9), les profils
TLS/REALITY ne démarraient plus :
`Failed to build TLS config > common/errors: The feature "allowInsecure" has
been removed and migrated to "pinnedPeerCertSha256"(pcs) and
"verifyPeerCertByName"(vcn)`. Le moteur refuse désormais la configuration dès
que `allowInsecure` vaut `true` (`false` reste accepté mais n'a plus de sens).
L'application l'émettait quand `profileItem.insecure == true` (valeur que l'UI
met par défaut sur `true` pour un profil réenregistré), et `XrayJsonConfig`
l'émettait en dur pour le test réel et la bascule.

**Correctif**
- `dto/V2rayConfig.kt` : `allowInsecure` retiré de `TlsSettingsBean` ; le champ
  n'est plus jamais sérialisé dans le JSON runtime.
- `core/CoreOutboundBuilder.kt` : plus aucune émission de `allowInsecure`. Les
  remplacements officiels restent alimentés (`pinnedCA256` →
  `pinnedPeerCertSha256`, `verifyPeerCertByName`). Un avertissement journalise
  les profils qui comptent sur `insecure` sans épingle : la vérification du
  certificat est maintenant obligatoire.
- `core/XrayJsonConfig.kt` : `allowInsecure: true` supprimé du JSON généré
  (test réel, bascule), avec `pinnedPeerCertSha256`/`verifyPeerCertByName`
  repris du profil.
- `helper/MessageHelper.kt` : l'annulation d'une campagne de latence n'utilise
  plus `startForegroundService` (un simple `startService`). Ce démarrage au
  premier plan, déclenché aussi à la fermeture de l'écran, démarrait à froid
  le processus `:tasks` et pouvait dépasser le délai système
  (`ForegroundServiceDidNotStartInTimeException`).
- `service/CoreTestService.kt` : le moteur natif n'est plus chargé dans
  `onCreate` mais au début d'une campagne réelle. Une annulation ne charge
  donc plus la bibliothèque Go, et le thread principal reste libre pour les
  démarrages suivants.

**Limite connue** : un serveur à certificat auto-signé doit désormais fournir
un `pinnedCA256` (ou `verifyPeerCertByName`) ; la case « autoriser les
certificats non vérifiés » n'a plus d'effet, le moteur n'offrant plus cette
possibilité.

**Vérifications réellement exécutées**
- Binaire officiel Xray v26.11.9 (`xray run -test`) : VLESS+TLS avec
  `pinnedPeerCertSha256` + `verifyPeerCertByName`, Trojan+TLS et REALITY sur
  RAW → `Configuration OK` ; `allowInsecure: true` → erreur reproduite ;
  champ absent → OK.
- Tests unitaires ajoutés/mis à jour : absence de `allowInsecure` dans le TLS
  et le REALITY générés (builder et `XrayJsonConfig`), présence des champs de
  remplacement.
- CI `dams-ci` (run 43551095432) : 516 tests unitaires + lint
  **BUILD SUCCESSFUL**.
- CI `dams-build` (run 43552056170) : APK signés produits et vérifiés.
- Release `v1.9.9-lab` (tag sur `0ca6661`, Latest) : arm64
  `DamsTunnel_1.9.9-lab_arm64-v8a.apk` (33 499 030 o, SHA-256
  `086785453a74f17c826973619b343297c2109a13fb2c839d2303cdb74288612f`) et
  universel `DamsTunnel_1.9.9-lab_universal.apk` (73 125 659 o, SHA-256
  `899e7a16e392544e7e8a2ec61fc2639ac080c13873feb43a0c88c4dadc8bb6e0`).

**Non vérifié**
- Aucun test terrain sur téléphone (poignée de main réelle, trafic TCP/UDP,
  DNS, débits, reconnexion) : la validation finale doit être faite sur l'APK.
- Le délai `:tasks` n'a pas pu être reproduit ici ; le correctif supprime le
  démarrage au premier plan inutile, sans mesure sur appareil.

## 1.9.8-lab (765) — migration du moteur vers Xray-core v26.11.9

**Contexte** : l'audit a montré un moteur officiel (Xray-core) mais figé à
janvier 2026 (`v26.1.23`, module `v1.260123.0`, Go 1.25.6, wrapper « Lib v34 »),
soit 22 releases de retard et six API manquantes dans l'AAR.

**Moteur** : `2dust/AndroidLibXrayLite` **v26.11.9** (AAR `libv2ray.aar`,
SHA-256 `cdfef146218233a31a8b52c8b1016557730d3b63fc121d4c84cacea50e669155`,
60 437 926 o), qui embarque **XTLS/Xray-core v26.11.9** (commit `676eaf66f8f0`,
module `v1.260327.1-0.20261109052709-676eaf66f8f0`, Go 1.27.3, wrapper
« Lib v40 »). Aucun patch du cœur ; l'AAR est épinglée par URL + SHA-256 dans
les trois workflows.

**Façade** : `core/CoreEngine.kt` (contrat : démarrer, arrêter, état, latence,
compteurs, version) et `core/Libv2rayEngine.kt` (seule implémentation, avec
`CoreNativeManager`, qui importe `libv2ray`). `CoreServiceManager` ne manipule
plus le type `CoreController`.

**API remplacée** : `CoreController.queryStats(tag, sens)` (supprimée) →
`queryAllOutboundTrafficStats()` (format `tag,direction,value;`, une seule
traversée JNI, remise à zéro globale). `util/TrafficStatsParser.kt` est enfin
branché ; les tags suivis absents de la réponse sont réémis à zéro pour ne pas
transformer une absence de trafic en indisponibilité. `startLoop`, `stopLoop`,
`measureDelay`, `measureOutboundDelay`, `initCoreEnv` et les rappels sont
inchangés.

**À noter** : `StopLoop()` appelle désormais le rappel `shutdown()` (journalisé
seulement) ; `TrafficStatsParser` ne voit que les compteurs non nuls ; le
dialogue navigateur natif reste volontairement inerte.

**Vérifications réellement exécutées**
- CI `dams-ci` (run 43543501811) : tests unitaires + lint **BUILD SUCCESSFUL**,
  puis builds debug/release et APK Premium isolé.
- CI `dams-build` (run 43544463920) : APK signés produits ; signature V2
  `c691d764…d518`. APK arm64 `DamsTunnel_1.9.8-lab_arm64-v8a.apk`,
  33 498 922 o, SHA-256 `d98068516e5e8f22d789112c3a55bbcde8c5b1463f740d3b310585c51ad60621`.
- Binaire embarqué : `libgojni.so` porte bien `xray-core
  v1.260327.1-0.20261109052709-676eaf66f8f0`, Go 1.27.3, « Lib v40 » ;
  segments `p_align 0x4000` (pages 16 Ko).
- Schémas JSON validés avec le binaire officiel Xray 26.11.9 (`xray run -test`) :
  gabarit d'application, gabarit avec `tun`, VLESS+REALITY (vision),
  VMess+WS+TLS, Trojan+TLS, VLESS+XHTTP+TLS, Hysteria2, WireGuard, et
  configuration du laboratoire (balancer `leastPing` + `observatory`) →
  « Configuration OK » dans les neuf cas.

**Non exécuté (nécessite l'appareil)** : poignée de main réelle REALITY/VMess/
Trojan, TCP et UDP réels, DNS et navigation, téléchargement/téléversement,
reconnexion et stabilité, absence de crash, comparaison de performance. Aucun
résultat de terrain n'est affirmé.

**Retour arrière** : `git revert` du commit de migration (AAR épinglée par URL,
sous-module, façade) ou reconstruction depuis le tag
`backup/pre-xray-26.11.9` (836d1ff, 1.9.7-lab 764).

## 1.9.7-lab (764) — réparation du trafic, du DNS/TUN et du crash FGS

**Contexte** : après la phase C, le VPN affichait « connecté » alors que le
trafic des applications ne passait plus (téléchargements lents ou impossibles),
avec `ForegroundServiceDidNotStartInTimeException` dans le processus `:tasks`
et des échecs de résolution (`Unable to resolve latency-test host`,
`socketConnectTime IOException`, `failed to connect` port 443).

**Cause 1 — le tunnel était rechargé pour une seule sonde perdue.**
La sonde de santé tournait toutes les secondes et un unique échec déclenchait
`reloadCore()` : `stopLoop()` puis `startLoop()` coupent **toutes** les
connexions TCP en cours. Un téléchargement en cours était donc interrompu en
boucle, et le processeur était réveillé en permanence par deux requêtes HTTPS
par seconde (URL primaire + URL de secours). Correctif : sonde toutes les 10 s,
**deux échecs consécutifs** avant toute récupération, et décision extraite dans
`CoreRecoveryPolicy` (testée sur JVM).

**Cause 2 — état « connecté » non prouvé sur le chemin réel.**
La vérification utilisait `CoreController.measureDelay()`, qui traverse le
moteur Xray sans passer par le TUN : un relais `hev-socks5-tunnel` mort
laissait donc publier « connecté ». Correctif : l'état connecté exige la sonde
réelle **et** un cœur vivant **et** un relais TUN vivant
(`ServiceControl.isTunnelRelayRunning()`, `Tun2SocksControl.isRunning()` via
`TProxyIsRunning()` ; toute erreur JNI est traitée comme « vivant » pour ne
jamais casser un tunnel sain).

**Cause 3 — reconstruction du TUN avec un relais encore accroché.**
Un redémarrage reconstruisait l'interface sans arrêter le relais précédent,
puis lançait un second `TProxyStartService` (échec `tun_start_failed` ou relais
sur un descripteur fermé). Correctif : le relais est arrêté **avant** la
fermeture de l'interface dans `configureVpnService()` ; un second démarrage ne
reconstruit que si le chemin des paquets est réellement inutilisable, et il
arrête alors le cœur restant d'abord (jamais deux instances, jamais deux
relais).

**Cause 4 — le redémarrage manuel était annulé par l'arrêt qu'il demandait.**
`stopCoreLoop()` annule les enfants de `connectionTestScope`, or la séquence de
redémarrage manuel et la boucle de reconnexion y tournaient : l'arrêt annulait
la relance. Correctif : portée dédiée `lifecycleScope` pour la relance
automatique et le redémarrage manuel.

**Cause 5 — crash FGS du processus `:tasks`.**
`CoreTestService` et `SubscriptionUpdateService` appelaient
`CoreNativeManager.initCoreEnv()` (chargement du moteur Go) dans `onCreate()`
puis `startForeground()` seulement dans `onStartCommand()` : sur un téléphone
sous pression mémoire, le délai système expirait →
`ForegroundServiceDidNotStartInTimeException`. Correctif : `startForeground()`
est appelé **en tête de `onCreate()`** (idempotent, rappelé dans
`onStartCommand`), avant toute initialisation lourde ; un échec d'initialisation
est journalisé en entier et arrête proprement le service au lieu de tuer le
processus.

**DNS** : repli explicite sur `AppConfig.DNS_VPN` lorsqu'aucune adresse DNS
exploitable n'est configurée, au lieu d'établir un TUN sans résolveur.

**Fichiers** : `core/CoreRecoveryPolicy.kt` (nouveau),
`core/CoreServiceManager.kt`, `service/CoreVpnService.kt`,
`service/TProxyService.kt`, `service/CoreTestService.kt`,
`service/SubscriptionUpdateService.kt`, `contracts/ServiceControl.kt`,
`contracts/Tun2SocksControl.kt`,
`app/src/test/java/com/v2ray/ang/core/CoreRecoveryPolicyTest.kt` (nouveau,
13 tests), `app/build.gradle.kts` (764 / 1.9.7-lab).

**Vérifié** : compilation et tests unitaires par CI (voir la CI du commit).
**Non vérifié** : aucun test terrain (débit réel, bascule Wi-Fi/mobile, écran
éteint, téléchargement long) n'a été exécuté — il n'y a ni terminal Android ni
appareil accessible depuis cet environnement. Aucun résultat de débit n'est
annoncé.
