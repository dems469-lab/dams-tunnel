# Laboratoire Multipath — DAMS MULTIPATH ENGINE

> Module expérimental **indépendant**. Il ne remplace ni le VPN classique, ni
> Configs, ni Reconfiguration, ni IP Finder. En mode normal, son code est inerte :
> le seul point d'entrée dans le moteur existant est un pont fichier à jeton,
> absent tant que le laboratoire n'est pas activé.

## 1. Ce que fait le laboratoire

- Trois chemins Xray indépendants dans **un seul** `CoreVpnService` et **un seul**
  TUN : `path-a` (principal), `path-b` et `path-c` (secondaires).
- Un balancer Xray `multipath-main` en `leastPing`, avec `fallbackTag = path-a`,
  qui répartit les **nouvelles** connexions sur les chemins sains.
- Un `observatory` Xray qui sonde les trois chemins toutes les 10 s et permet à
  Xray de basculer seul, y compris écran éteint.
- Une surveillance Kotlin complémentaire (latence, pertes, disponibilité,
  stabilité, qualité, indice de débit estimé) exécutée **uniquement quand
  l'écran du laboratoire est visible**.
- Une bascule « intelligente » du sélecteur (hystérésis, cooldown, réintégration)
  qui ne recharge le moteur que si le sous-ensemble routé change réellement.
- Un stockage totalement séparé : base SQLite `dams_laboratory.db` et fichier
  `dams_laboratory_prefs`. La réinitialisation ne touche jamais aux données
  normales.

## 2. Ce que le laboratoire ne fait pas

- Il ne migre **jamais** une session TCP existante : Xray applique le nouveau
  chemin aux connexions suivantes. L'interface le dit explicitement.
- Il ne crée pas de second service VPN, de second TUN, ni de troisième moteur.
- Il ne modifie pas la sélection de serveur de production
  (`MmkvManager.setSelectServer` n'est jamais appelé par le laboratoire).
- Il ne lit ni n'écrit les préférences classiques, les abonnements ou les
  données IP Finder.
- Il ne propose que les protocoles que le moteur épinglé sait valider comme
  proxy : **VLESS, VMess, Trojan**. Les groupes, chaînes et profils custom sont
  exclus.

## 3. Activation et arrêt

Séquence imposée par `MultipathController.activate` :

1. `PREPARING` — construit la configuration complète du serveur principal avec
   `CoreConfigManager`, extrait les outbounds secondaires, injecte les trois
   chemins, valide la configuration. **Aucun tunnel n'est touché.**
2. `STOPPING_CLASSIC` — envoie `MSG_STATE_STOP` en **broadcast ordonné** et
   attend l'accusé de réception du démon, puis `MSG_STATE_STOP_SUCCESS`
   (délai par défaut 20 s, réglable de 5 à 60 s via
   `LaboratoryPreferences.stopTimeoutSeconds`). Un second envoi a lieu après
   1,2 s si le premier n'a pas été acquitté (processus recréé, service en
   cours de création) ; les arrêts ne se chevauchent jamais.
3. `STARTING` — publie la session expérimentale dans
   `files/laboratory/active_session.json` **avec un jeton de démarrage**, puis
   démarre `CoreVpnService` par `startForegroundService` (jamais via
   `LauncherManager`).
4. Le moteur lit la session uniquement si `SettingsManager.isVpnMode()` est vrai
   et si le jeton est présent ; le jeton est consommé immédiatement.
5. `ACTIVE` n'est annoncé qu'après `MSG_STATE_START_SUCCESS`, émis par le moteur
   après vérification réelle du trafic. `CONNECTING` et `RECONNECTING` sont des
   états intermédiaires : ils n'autorisent ni la réussite ni l'abandon.
6. Une passe de sondes réelles vérifie ensuite les trois chemins avant de
   confirmer l'activation ; si aucun ne répond, le moteur est arrêté et
   l'échec est remonté (`no_path_available`, `verification_failed`).

### Accusés de réception et motifs d'échec

| Message | Émis quand | Effet côté laboratoire |
| --- | --- | --- |
| `MSG_REGISTER_CLIENT` | interrogé à l'ouverture | `RESULT_OK` si un démon répond ; sinon l'état est « arrêté », jamais « inconnu » |
| `MSG_STATE_STOP` | ordre d'arrêt | `RESULT_OK` dès réception ; l'état final arrive ensuite |
| `MSG_STATE_STOP_SUCCESS` | moteur arrêté **et** TUN libéré | arrêt confirmé |
| `MSG_STATE_STOP_FAILURE` | arrêt impossible | motif : `native_stop_error`, `native_stop_timeout`, `core_still_running` — le tunnel peut encore tourner, l'état « arrêté » n'est pas affiché |
| `MSG_STATE_START_FAILURE` | démarrage impossible | motif : `vpn_permission_required`, `vpn_configure_failed`, `tun_start_failed`, `core_start_failed`, `config_invalid` |

Le démon publie aussi son état autoritaire dans
`files/laboratory/tunnel_state.json` (`RUNNING`, `STOPPING`, `STOPPED`,
`STOP_FAILED`, `START_FAILED`, mode, motif, horodatage, PID). C'est une seconde
source de vérité, indépendante des broadcasts.

En cas d'échec à n'importe quelle étape : la session et le jeton sont effacés,
le service est arrêté si nécessaire, l'état passe à `FAILED` et aucun écran
n'affiche « connecté ». Un arrêt non confirmé conserve au contraire l'état
`ACTIVE` et la surveillance : le moteur tourne encore réellement.

### Causes exactes de l'échec corrigées en 1.9.3

1. `CoreLoopLifecycleGate.stop` bornait tout l'arrêt à **5 s** alors que
   `coreController.stopLoop()` est un appel natif **bloquant** : au-delà de 5 s
   le bloc était annulé avant `onCoreStopped()`, la fermeture du TUN,
   `MSG_STATE_STOP_SUCCESS`, `LaboratoryTunnelBridge.clear` et `stopSelf()`.
   Le service restait donc à moitié arrêté et muet.
2. `error("Core loop still running after stopLoop returned")` levait une
   exception lorsque le drapeau natif se mettait à jour avec un léger retard :
   aucun message n'était publié (`runCatching` dans `performStop`).
3. `getService() == null` retournait `false` sans aucun broadcast.
4. Le récepteur du démon n'était branché qu'au démarrage du cœur
   (`doStartCoreLoop`) : un arrêt ou une interrogation d'état sans cœur actif
   ne recevait **aucune réponse** → « état inconnu ».
5. Le laboratoire considérait le premier état intermédiaire (`CONNECTING`,
   envoyé systématiquement au démarrage du cœur) comme un échec : l'activation
   était annulée alors que le moteur vérifiait encore le trafic.
6. Les échecs de `setupVpnService()` (permission, configuration, TUN)
   n'étaient jamais publiés : l'interface attendait puis affichait un échec
   générique.
7. La surveillance par défaut était fixée à 45 s (minimum 20 s), incompatible
   avec un relais anticipé.

Unités de temps du démon : porte de cycle de vie 25 s, appel natif borné à
20 s, grâce de 2 s sur `isRunning`, arrêt du laboratoire 20 s par défaut.

Quitter le laboratoire (`Arrêter`, `Retour au VPN classique`, retour système)
arrête le moteur, libère le TUN, nettoie la session, puis permet de relancer le
VPN classique avec le profil de production toujours sélectionné.

### Pont à jeton — garantie d'isolation

`LaboratoryTunnelBridge.write` écrit la session puis un fichier
`active_start.permit`. `CoreServiceManager.launchCore` utilise
`readForStart` : sans jeton, la session est considérée périmée, supprimée, et
le mode normal démarre normalement. Ce jeton est aussi réécrit avant chaque
`MSG_LAB_RELOAD`. Conséquence : une session laissée par un incident ne peut
jamais détourner un démarrage classique ultérieur.

## 4. Configuration Xray injectée

Partant de la configuration complète du serveur principal (inbounds, DNS,
fakeDNS, transports, règles utilisateur) :

- renommage de l'outbound `proxy` en `path-a` et réécriture de toutes ses
  références (règles, balancers, `dialerProxy`) ;
- ajout de `path-b` et `path-c` (outbounds autonomes extraits du moteur) ;
- balancer `multipath-main` (`leastPing`, `fallbackTag = path-a`, sélecteur) ;
- `observatory` (`probeUrl`, `probeInterval = 10s`, `enableConcurrency`) ;
- règle attrape-tout `tcp,udp` → balancer, ajoutée **après** les règles
  utilisateur qui restent prioritaires.

Le mode hybride (défaut) route les trois chemins. Le mode strict ne route que
`path-a` ; en cas de panne du principal, les deux meilleurs chemins sains
prennent le relais afin qu'une seconde panne ne coupe pas le trafic.

## 5. Surveillance, bascule et statistiques

- `PathHealthMonitor` mesure réellement chaque chemin via
  `CoreNativeManager.measureOutboundDelay` sur un outbound autonome temporaire
  (aucun autre outbound dans la configuration de sonde : une sonde `path-a` ne
  peut pas passer par `path-b` ou `path-c`), séquentiellement (350 ms d'écart)
  et jamais en parallèle du cycle précédent. La requête de test est une vraie
  requête HTTP ; un ping ICMP n'est jamais utilisé comme preuve.
- Cadence : **3 s** en surveillance active (réglable 1–30 s), **1 s** dès qu'un
  chemin échoue ou se dégrade, et `observatory.probeInterval = 5s` côté démon.
  La surveillance de l'interface s'arrête quand l'écran disparaît ; la bascule
  continue alors dans le démon grâce à l'observatoire Xray (elle ne dépend donc
  pas de la visibilité de l'écran). Xray ne teste pas à la seconde : la valeur
  de 5 s est la cadence réellement configurée, pas une promesse.
- `ServerQualityAnalyzer` calcule disponibilité, pertes, latence moyenne,
  stabilité (gigue) et score de qualité sur une fenêtre glissante de 10 mesures.
  L'« indice de débit » est une estimation dérivée de la qualité, jamais un
  débit mesuré en octets/seconde. Zéro mesure et zéro perte sont distingués :
  sans échantillon, la perte est affichée « — ».
- `InstantFailoverManager` décide du sélecteur : bascule immédiate si le chemin
  préféré meurt, conservation du routage pendant le cooldown (30 s) si le chemin
  préféré reste sain, réintégration automatique.
- `SmartTrafficScheduler` évite les rechargements inutiles : Xray gère déjà
  l'exclusion des chemins morts en hybride. Un relais n'est enregistré
  **qu'après** republication de l'état `RUNNING` par le démon (le moteur a
  réellement redémarré avec la nouvelle sélection) : aucun relais affiché sans
  changement effectif de routage.
- `MultipathStatistics` agrège des échantillons réels ; l'historique des relais
  et les journaux sont stockés dans la base du laboratoire. Les messages sont
  expurgés (UUID, secrets, troncature à 240 caractères) avant écriture.
  Le nombre de « connexions » par chemin n'est pas exposé par l'API Xray
  épinglée : il n'est donc pas inventé ; les compteurs affichés sont les
  sessions, les échantillons et les relais réellement mesurés.

## 6. Batterie, réseau et limites assumées

- La surveillance Kotlin ne tourne que si l'écran est visible ; en arrière-plan,
  la bascule repose sur l'observatoire Xray (intervalle **5 s**), qui vit dans le
  démon et continue écran éteint.
- Les sondes Kotlin démarrent un core temporaire par mesure : c'est le mécanisme
  déjà utilisé par Reconfiguration, mais il coûte de l'énergie. Cadence 3 s en
  régime normal, 1 s en cas de suspicion, réglable de 1 à 30 s. Une mesure qui
  échoue peut durer plusieurs secondes (délai TCP) : la boucle enchaîne alors
  sans chevaucher les cycles.
- Les sondes tournent dans le processus de l'interface. L'application est
  exclue du VPN par défaut (`addDisallowedApplication`), donc leurs sockets ne
  traversent pas le tunnel : la mesure est bien celle du chemin seul. **Limite
  documentée** : si l'utilisateur active le proxy par application en mode
  « proxy » et y inclut Dams Tunnel, les sondes passent par le tunnel.
- Aucun verrou d'écran n'est pris par le laboratoire ; Android peut suspendre le
  processus, ce qui n'arrête pas le tunnel mais suspend la surveillance fine.
- Le laboratoire n'est pas intégré au « VPN toujours actif » du système : si le
  démon redémarre seul, le jeton ayant été consommé, le mode normal reprend la
  main (comportement sûr, documenté).
- Une panne de session TCP en cours n'est pas migrée ; seules les nouvelles
  connexions bénéficient de la bascule.
- Si le `stopLoop` natif se bloque au-delà de 20 s, l'arrêt est déclaré en échec
  (`native_stop_timeout`) : le tunnel peut encore tourner, l'application le dit
  et propose une nouvelle tentative ; un redémarrage du processus du démon est
  le remède de dernier recours.

## 7. Fichiers

### Nouveaux

| Fichier | Rôle |
| --- | --- |
| `core/LaboratoryTunnelBridge.kt` | Pont fichier session + jeton, écriture atomique, validation, nettoyage. |
| `laboratory/multipath/MultipathModels.kt` | Rôles, états, métriques, décisions, événements. |
| `laboratory/multipath/MultipathSettings.kt` | Réglages + normalisation. |
| `laboratory/multipath/ServerQualityAnalyzer.kt` | Analyse pure des mesures. |
| `laboratory/multipath/InstantFailoverManager.kt` | Décision de sélection avec cooldown. |
| `laboratory/multipath/SmartTrafficScheduler.kt` | Anti-rechargements inutiles. |
| `laboratory/multipath/XrayMultipathConfig.kt` | Injection pure des trois chemins. |
| `laboratory/multipath/MultipathEngine.kt` | Préparation de la configuration et des cibles de sonde. |
| `laboratory/multipath/PathHealthMonitor.kt` | Sondes réelles séquentielles. |
| `laboratory/multipath/MultipathController.kt` | Machine à états, surveillance, bascule. |
| `laboratory/multipath/MultipathStatistics.kt` | Statistiques de session. |
| `laboratory/multipath/MultipathEventLogger.kt` | Journalisation expurgée. |
| `laboratory/multipath/MultipathConfigRepository.kt` | Lecture en lecture seule des profils de production. |
| `laboratory/multipath/TunnelGateway.kt` | Contrat d'orchestration testable. |
| `laboratory/data/*` | Base SQLite, préférences, modèles, dépôt. |
| `laboratory/service/LaboratoryTunnelGateway.kt` | Adaptateur service/broadcasts. |
| `laboratory/ui/LaboratoryActivity.kt` | Permission VPN, cycle de vie, dialogue de sortie. |
| `laboratory/ui/LaboratoryScreen.kt` | Écran Compose premium. |
| `laboratory/ui/LaboratoryUiState.kt` | État d'affichage. |
| `laboratory/ui/LaboratoryViewModel.kt` | Orchestration UI, monitoring, erreurs. |
| `res/drawable/ic_laboratory_24dp.xml` | Icône du menu. |
| `app/src/test/.../laboratory/multipath/*` | Tests JVM (7 fichiers). |

### Modifiés (adaptateur minimal)

| Fichier | Justification |
| --- | --- |
| `AppConfig.kt` | constantes `MSG_LAB_RELOAD = 9` et `MSG_STATE_STOP_FAILURE = 42` (valeurs libres, sans collision ; ignorées par l'interface classique). |
| `core/CoreServiceManager.kt` | lecture de la session **autorisée** dans `launchCore`, remarks `LAB ·`, arrêt fiabilisé (motifs `native_stop_error`, `native_stop_timeout`, `core_still_running`), accusés de réception, récepteur branché dès `onCreate`, état publié, handler de rechargement. Le mode normal garde exactement le même chemin. |
| `service/CoreVpnService.kt` | récepteur attaché dans `onCreate` / détaché dans `onDestroy`, échecs de préparation publiés avec motif (`vpn_permission_required`, `vpn_configure_failed`, `tun_start_failed`). |
| `helper/MessageHelper.kt` | extra `reason` (code court, non sensible) sur les messages d'échec. |
| `ui/main/MainDrawer.kt` + `MainActivity.kt` | entrée de navigation « Laboratoire ». |
| `AndroidManifest.xml` | déclaration de `LaboratoryActivity` (`exported=false`). |
| `values/strings.xml`, `values-fr/strings.xml` | 112 chaînes FR/EN. |
| `.github/workflows/dams-build.yml` | filtre de tests `com.v2ray.ang.laboratory.*`. |

## 8. Tests

Tests JVM purs (sans Android) :

- `XrayMultipathConfigTest` : 11 cas (injection, réécriture des références,
  balancer, observatoire, sélecteur restreint, refus de doublon, validation).
- `ServerQualityAnalyzerTest` : agrégats, pertes, gigue, états.
- `InstantFailoverManagerTest` : premier choix, hybride, cooldown, panne du
  préféré, mode strict, réinitialisation.
- `SmartTrafficSchedulerTest` : anti-rechargement.
- `MultipathStatisticsTest` : moyennes pondérées, relais, remise à zéro.
- `MultipathLogSanitizerTest` : masquage UUID/secrets, troncature.
- `MultipathSettingsTest` : bornage intervalle/cooldown, URL de sonde, cadence
  adaptative (3 s / 1 s / observatoire 5 s).

La CI exécute ces tests avec le reste de la suite (`Dams CI`) et construit les
APK debug/release (`Dams APK`).

## 9. Validation sur appareil

À vérifier manuellement après installation :

1. Ouvrir Laboratoire : rien ne démarre ; l'état du service est interrogé
   (accusé de réception) et affiché « arrêté », jamais « inconnu ».
2. Activer avec un VPN classique connecté : dialogue, arrêt **confirmé**,
   démarrage du moteur, puis état « Actif » seulement après vérification réelle.
3. Activer alors que le VPN classique est déjà arrêté : transition immédiate,
   sans attente inutile ni double arrêt.
4. Un seul des trois serveurs valide : activation possible sur ce chemin, les
   deux autres affichent « indisponible » avec leur motif ; jamais 3/3.
5. Les trois serveurs en échec : le moteur est arrêté, l'erreur
   `no_path_available` est affichée, aucune statistique n'est inventée.
6. Hybride : les trois chemins vérifiés portent le trafic ; couper PATH A
   déclenche le relais vers PATH B, la modification de routage est confirmée
   par le démon avant d'être comptée.
7. Interruption Wi-Fi ↔ mobile : les chemins se dégradent puis se rétablissent,
   la surveillance passe à 1 s pendant la dégradation.
8. Arrêter puis revenir au VPN classique : le TUN est libéré et le profil de
   production se reconnecte.
9. Réinitialiser le laboratoire : les serveurs, abonnements, réglages et
   données IP Finder restent intacts.
