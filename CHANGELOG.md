# Journal des modifications — DAMS TUNNEL

Un seul fichier d'historique. Chaque entrée décrit une intervention terminée,
les fichiers touchés, ce qui a été vérifié et ce qui reste à vérifier.

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
