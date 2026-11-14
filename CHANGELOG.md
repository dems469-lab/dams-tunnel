# Journal des modifications — DAMS TUNNEL

Un seul fichier d'historique. Chaque entrée décrit une intervention terminée,
les fichiers touchés, ce qui a été vérifié et ce qui reste à vérifier.

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
