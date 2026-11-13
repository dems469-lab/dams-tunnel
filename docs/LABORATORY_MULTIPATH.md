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
2. `STOPPING_CLASSIC` — si un VPN classique tourne, envoie `MSG_STATE_STOP` et
   attend `MSG_STATE_STOP_SUCCESS` (12 s), puis laisse 700 ms au service pour
   libérer le TUN et ses sockets.
3. `STARTING` — publie la session expérimentale dans
   `files/laboratory/active_session.json` **avec un jeton de démarrage**, puis
   démarre `CoreVpnService` par `startForegroundService` (jamais via
   `LauncherManager`).
4. Le moteur lit la session uniquement si `SettingsManager.isVpnMode()` est vrai
   et si le jeton est présent ; le jeton est consommé immédiatement.
5. `ACTIVE` n'est annoncé qu'après `MSG_STATE_START_SUCCESS`, émis par le moteur
   après vérification réelle du trafic.

En cas d'échec à n'importe quelle étape : la session et le jeton sont effacés,
le service est arrêté si nécessaire, l'état passe à `FAILED` et aucun écran
n'affiche « connecté ».

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
  `CoreNativeManager.measureOutboundDelay` sur un outbound autonome temporaire,
  séquentiellement (350 ms d'écart) et jamais en parallèle du cycle précédent.
- `ServerQualityAnalyzer` calcule disponibilité, pertes, latence moyenne,
  stabilité (gigue) et score de qualité sur une fenêtre glissante de 10 mesures.
  L'« indice de débit » est une estimation dérivée de la qualité, jamais un
  débit mesuré en octets/seconde.
- `InstantFailoverManager` décide du sélecteur : bascule immédiate si le chemin
  préféré meurt, conservation du routage pendant 45 s (cooldown) si le chemin
  préféré reste sain, réintégration automatique.
- `SmartTrafficScheduler` évite les rechargements inutiles : Xray gère déjà
  l'exclusion des chemins morts en hybride.
- `MultipathStatistics` agrège des échantillons réels ; l'historique des relais
  et les journaux sont stockés dans la base du laboratoire. Les messages sont
  expurgés (UUID, secrets, troncature à 240 caractères) avant écriture.

## 6. Batterie, réseau et limites assumées

- La surveillance Kotlin ne tourne que si l'écran est visible ; en arrière-plan,
  la bascule repose sur l'observatoire Xray (intervalle 10 s).
- Les sondes Kotlin démarrent un core temporaire par mesure : c'est le mécanisme
  déjà utilisé par Reconfiguration, mais il coûte de l'énergie. L'intervalle est
  réglable de 20 à 300 s (45 s par défaut).
- Aucun verrou d'écran n'est pris par le laboratoire ; Android peut suspendre le
  processus, ce qui n'arrête pas le tunnel mais suspend la surveillance fine.
- Le laboratoire n'est pas intégré au « VPN toujours actif » du système : si le
  démon redémarre seul, le jeton ayant été consommé, le mode normal reprend la
  main (comportement sûr, documenté).
- Une panne de session TCP en cours n'est pas migrée ; seules les nouvelles
  connexions bénéficient de la bascule.

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
| `AppConfig.kt` | constante `MSG_LAB_RELOAD = 9` (valeur libre, sans collision). |
| `core/CoreServiceManager.kt` | lecture de la session **autorisée** dans `launchCore`, remarks `LAB ·`, nettoyage dans `stopCoreLoop`, handler de rechargement. Le mode normal garde exactement le même chemin. |
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
- `MultipathSettingsTest` : bornage intervalle/cooldown, URL de sonde.

La CI exécute ces tests avec le reste de la suite (`Dams CI`) et construit les
APK debug/release (`Dams APK`).

## 9. Validation sur appareil

À vérifier manuellement après installation :

1. Ouvrir Laboratoire : rien ne démarre, l'écran affiche « Prêt ».
2. Activer avec un VPN classique en cours : confirmation, arrêt vérifié,
   démarrage, l'état n'affiche « Actif » qu'après vérification réelle.
3. Couper le Wi-Fi / passer en mobile : les chemins se dégradent, la bascule
   apparaît dans l'historique, le trafic continue sur les chemins sains.
4. Arrêter puis revenir au VPN classique : le TUN est libéré et le profil de
   production se reconnecte.
5. Réinitialiser le laboratoire : les serveurs, abonnements, réglages et
   données IP Finder restent intacts.
