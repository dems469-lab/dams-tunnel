# Rapport d'audit complet — Dams Tunnel v1.9.9-lab (766)

- **Branche** : `migration/xray-26.11.9` (HEAD `e2f375a`)
- **Méthode** : revue statique du code uniquement (aucun JDK/SDK/appareil local ; vérification dynamique ponctuelle du binaire Xray officiel `/tmp/opencode/xray269/xray` pour la matrice protocoles, déjà validée).
- **Périmètre exclu des tests** : poignées de main réelles, TCP/UDP terrain, DNS, débits, reconnexion réelle, crash sur device.
- **Aucun correctif appliqué** — ce rapport attend votre validation avant toute intervention.

Chemins relatifs à `V2rayNG/app/src/main/java/com/v2ray/ang/` sauf mention contraire.

---

## 1. Sévérité élevée

### E1 — Verrou du cycle de vie : un `stop()` natif qui pend bloque tout indéfiniment
- **Preuve** : `core/CoreLoopLifecycleGate.kt:15-18` — le timeout (`withTimeoutOrNull`) est posé **à l'intérieur** de `mutex.withLock`, donc l'attente du mutex elle-même n'est jamais limitée. `core/CoreServiceManager.kt:1340` — `reloadCore` appelle `withContext(Dispatchers.IO) { coreEngine.stop() }` **sans aucun timeout** (contrairement à `stopCoreLoop` qui en a un).
- **Impact** : si `coreEngine.stop()` (JNI bloquant) pend, la porte reste verrouillée pour toujours ; tout `stop` utilisateur ultérieur suspend indéfiniment sur le mutex (son timeout ne couvre que le bloc interne), aucun démarrage/rechargement n'est plus possible, l'UI reste figée sur « reconnexion », le service ne s'arrête plus (`stopSelf` jamais atteint).
- **Correctif proposé** : wrapper le `coreEngine.stop()` de `reloadCore` dans un `withTimeoutOrNull(NATIVE_STOP_TIMEOUT_MILLIS)` avec journalisation + continuation ; et déplacer le timeout de `CoreLoopLifecycleGate.stop` **autour** de l'acquisition du mutex (`withTimeoutOrNull { mutex.withLock { ... } }`).

### E2 — Profils CUSTOM : inbounds non assainis → proxy sans authentification possible sur le réseau local
- **Preuve** : `core/CoreConfigManager.kt:86-140` (`buildV2rayCustomConfig`) n'impose jamais `listen = 127.0.0.1` sur les inbounds du JSON brut (contrairement à `configureInbounds` pour les profils générés) ; en mode proxy (`!needTun()`), le JSON est rendu tel quel (:106-108).
- **Impact** : un profil « custom » — qui peut provenir d'un abonnement (`AngConfigManager.parseConfigViaSub` accepte un JSON avec `inbounds/outbounds`) — peut ouvrir un inbound socks/http sur `0.0.0.0` sans authentification → proxy ouvert sur le LAN/le réseau mobile, utilisable par n'importe qui.
- **Correctif proposé** : lors de la construction, forcer (ou au minimum avertir) `listen=127.0.0.1` et refuser les inbounds sans auth quand `PREF_PROXY_SHARING=false` ; sinon afficher un avertissement explicite à l'import.

### E3 — `UrlSchemeActivity` exportée : import automatique sans confirmation + course avec `finish()`
- **Preuve** : `AndroidManifest.xml:262-280` (`exported="true"`, `ACTION_SEND text/plain`, deeplink `v2rayng://install-config|install-sub`) ; `ui/UrlSchemeActivity.kt:40-56` import direct, puis `startActivity(MainActivity)` + `finish()` **avant** la fin de la coroutine `lifecycleScope.launch(Dispatchers.IO)` (:75-87).
- **Impact** :
  1. N'importe quelle application peut déclencher silencieusement l'import d'une config ou d'un abonnement arbitraire (aucune confirmation utilisateur, aucun contrôle app-lock).
  2. Le `finish()` détruit l'activité pendant l'import : `lifecycleScope` est annulé → import possiblement interrompu en cours d'écriture MMKV (import partiel), toast de résultat perdu.
- **Correctif proposé** : dialog de confirmation (domaine + type d'import) avant tout import ; déplacer l'import hors `lifecycleScope` (ex. `applicationScope`/`ProcessLifecycleOwner`) ou le terminer avant `finish()`.

### E4 — `TaskerReceiver` exporté sans permission : contrôle du VPN par n'importe quelle app
- **Preuve** : `AndroidManifest.xml:432-440` (`exported="true"`, aucune `android:permission`) ; `receiver/TaskerReceiver` accepte démarrage/arrêt/changement de profil avec un GUID arbitraire.
- **Impact** : toute application installée peut démarrer/arrêter le VPN ou changer de serveur. Sur Android 12+, le démarrage FGS depuis un broadcast en arrière-plan lève `ForegroundServiceStartNotAllowedException` (attrapé en aval) → l'automatisation Tasker échoue silencieusement.
- **Correctif proposé** : restreindre l'export (permission custom vérifiée par un secret partagé configuré dans Tasker, ou `signature`+bridge), et gérer explicitement le cas FGS-arrière-plan (notification de l'utilisateur plutôt qu'échec silencieux).

### E5 — `BackupActivity` exportée : contourne l'app-lock
- **Preuve** : `AndroidManifest.xml:284-295` (`exported="true"`, `grantUriPermissions="true"`, MIME `application/octet-stream`) ; le verrou `PREF_APP_LOCK` n'est appliqué que dans `ui/main/MainActivity.kt:151` (`onResume`) — aucun contrôle dans `BackupActivity` (aucune occurrence de `appUnlocked` hors `MainActivity`).
- **Impact** : un téléphone déverrouillé mais app-locké peut voir ses sauvegardes exportées/importées via `BackupActivity` lancée depuis une autre app (ou adb). Accessoirement, le compteur d'essais du mot de passe DAMS est indexé sur `uri.toString()` (`ui/backup/BackupActivity.kt:105,194-198`) : recopier le fichier sous un autre URI réinitialise le compteur — garde-fou UX, pas une sécurité réelle.
- **Correctif proposé** : appliquer la même vérification biometric/app-lock dans `BackupActivity.onCreate`, et (option) indexer les essais sur un digest du contenu du fichier plutôt que sur l'URI.

---

## 2. Sévérité moyenne

### M1 — Récepteur de diffusion fuété dans `CoreProxyOnlyService` et `CoreRootService`
- **Preuve** : `service/CoreProxyOnlyService.kt:62-73` et `service/CoreRootService.kt:67-83` — `onDestroy` n'appelle ni `CoreServiceManager.detachServiceReceiver(this)` ni `clearServiceControl(this)` (contrairement à `CoreVpnService.onDestroy`). Or `doStartCoreLoop` enregistre le récepteur sur ces services et `attachServiceReceiver` refuse de réenregistrer si `receiverService != null`.
- **Impact** : après bascule de mode (proxy → VPN), le récepteur reste enregistré sur le contexte du service détruit (fuite de l'instance), `receiverService` reste périmé donc jamais réattaché, et `serviceControl` pointe un temps vers un service détruit. Conséquences limitées (les broadcasts continuent d'arriver via le contexte applicatif) mais fuite durable et logique fragile.
- **Correctif proposé** : dupliquer la paire `detachServiceReceiver(this)` + `clearServiceControl(this)` de `CoreVpnService.onDestroy` dans les deux services.

### M2 — MMKV non chiffré
- **Preuve** : `handler/MmkvManager.kt:82-88` (`MMKV.mmkvWithID(..., MULTI_PROCESS_MODE)` sans cryptKey) et `:153-159` (`MMKV.initialize` sans clé).
- **Impact** : UUID, mots de passe, URLs d'abonnement, jetons stockés en clair dans les fichiers MMKV ; lisibles sur appareil rooté ou via une exfiltration du répertoire données (mitigé par `allowBackup=false`, mais pas suffisant sur root).
- **Correctif proposé** : clé dérivée d'Android Keystore passée en `cryptKey` (avec migration : ré-écrire les storages existants ou dériver une clé fixe + avertissement de perte si Keystore invalide). À valider soigneusement (multi-processus, sauvegarde/restauration chiffrée DAMS déjà existante).

### M3 — Ports non numériques → exception non protégée dans `CoreOutboundBuilder`
- **Preuve** : `core/CoreOutboundBuilder.kt:64,65,122,144,195,217,233,286,306` — `.orEmpty().toInt()` non protégé (vmess :122, vless :144, socks :217, http :233, trojan :306 ; mux :64-65 ; wireguard :286). Seul Shadowsocks valide via `toIntOrNull` (validateur dédié). Même motif dans `RealPingWorkerService` (:121,149).
- **Impact** : un profil avec port vide/non numérique (import manuel, partage de lien) lève `NumberFormatException` pendant la construction de config ; capté par le catch global de `getV2rayConfig` → échec de connexion avec un message illisible au lieu d'une erreur « port invalide ». Risque de crash si une voie d'appel n'est pas couverte par le catch.
- **Correctif proposé** : remplacer par `toIntOrNull()` avec échec explicite (message utilisateur clair), aligné sur le validateur Shadowsocks existant.

### M4 — Résultats de test de latence perdus après `MeasureConfigFinish`
- **Preuve** : `ui/main/MainViewModel.kt:224-256` — à `MeasureConfigFinish`, un **nouveau** job remplace `testResultFlushJob` après `cancelAndJoin` ; si un `MeasureConfigSuccess` arrive **ensuite** (broadcasts non ordonnés garantis intra-processus entre threads du `:daemon`), `queueTestResult` (:247-253) voit un job actif et n'écrit que dans `pendingTestResults`, mais le job de finition a déjà flushé → résultat jamais affiché. Aussi : annulation d'un `flushPendingTestResults` en cours dans `cacheMutex.withLock` peut drainer le cache sans mise à jour UI (:258-266).
- **Impact** : cosmétique/UX — un profil du test en masse peut rester « non testé ».
- **Correctif proposé** : fusionner (coalescer) les flush dans un canal unique ou re-flusher à la fin de la boucle de finition ; reporter la MAJ UI hors de l'annulation.

### M5 — `isTunnelRelayRunning` en fail-open
- **Preuve** : `service/CoreVpnService.kt:236-244` — `runCatching { relay.isRunning() }.getOrDefault(true)` : une exception du relais natif est interprétée comme « relais vivant ».
- **Impact** : le tunnel peut être déclaré sain (`KEEP_EXISTING`/`START_SUCCESS`) alors que le chemin des paquets est mort — c'est exactement le cas que ce contrôle veut éviter ( commentaire adjacent).
- **Correctif proposé** : `getOrDefault(false)` (fail-closed) + journalisation ; la récupération `REBUILD_TUNNEL` prend alors le relais.

### M6 — Réglages numériques sans validation (MTU, port socks) + crash potentiel du processus `:daemon`
- **Preuve** : `ui/settings/SettingsActivity.kt:163,192` (valeurs MMKV texte libre) ; champs `SettingsEditItem` :557-561 (MTU) et :646-650 (port socks) — `ui/compose/SettingsItem.kt:127-170` = `InputDialog` texte sans validation. Consommation : `CoreVpnService` `builder.setMtu(...)` (~:372, `configureNetworkSettings`) sans bornage.
- **Impact** : une MTU aberrante (ex. `10`, `0`) → `IllegalArgumentException` du `VpnService.Builder` ; `serviceScope` des trois services = `CoroutineScope(SupervisorJob() + Dispatchers.IO)` **sans CoroutineExceptionHandler** (`service/CoreVpnService.kt:44`, `CoreProxyOnlyService.kt:21`, `CoreRootService.kt:33`) → toute exception non captée dans un `launch` tue le processus `:daemon` (FATAL EXCEPTION). Port socks hors bornes → inbound Xray qui démarre pas / comportement confus.
- **Correctif proposé** : validation/clamping à la saisie (MTU 68..65535, port 1..65535) + clamp défensif côté `SettingsManager`, et ajouter un `CoroutineExceptionHandler` aux trois `serviceScope` (journaliser au lieu de tuer le processus).

### M7 — Failover : la sélection change avant vérification, rollback silencieux
- **Preuve** : `core/CoreServiceManager.kt` (`applyFailover`/`switchAndVerify`) — la sélection du profil est écrite avant la vérification ; si `switchAndVerify(fromGuid)` échoue, le rollback n'est pas remonté à l'UI (aucun message dédié).
- **Impact** : l'UI peut afficher un profil alors que le cœur en sert un autre (incohérence temporaire), et l'utilisateur ignore qu'un rollback a eu lieu.
- **Correctif proposé** : émettre un événement UI (toast/état « bascule annulée ») sur échec de vérification + re-synchroniser la sélection affichée depuis la source de vérité.

### M8 — FileProvider : exposition de tout le cache
- **Preuve** : `AndroidManifest.xml:460` + `res/xml/file_paths.xml` (`cache-path path="/"`, résumé de l'audit initial).
- **Impact** : faible en soi (exporté=false, grant par URI, FileProvider bloque `..`), mais tout fichier du cache peut être octroyé à une autre app par une seule code-path trop permissive.
- **Correctif proposé** : restreindre à un sous-dossier dédié (`cache-path path="share/"`).

---

## 3. Sévérité faible / informatif

- **F1** — `QUERY_ALL_PACKAGES` (`AndroidManifest.xml:27`) : risque politique Play (justifié pour le picker d'app ; envisager `<queries>` ou Play exemption).
- **F2** — `isMinifyEnabled=false` (aucune réduction/obfuscation R8) : APK plus gros, pas de nettoyage d'API internes.
- **F3** — `Utils.getDeviceIdForXUDPBaseKey` (`util/Utils.kt:363-372`) : ANDROID_ID padé à 32 zéros en Base64 — clé XUDP faible + identifiant d'appareil transmis au serveur (informationnel/privacy).
- **F4** — `BootReceiver` : branche `LOCKED_BOOT_COMPLETED` morte (récepteur non `directBootAware`, manifest :396-412).
- **F5** — `SubscriptionUpdater` : `intervalMinutes*60*1000L` — overflow théorique si l'intervalle saisi est gigantesque (bornage absent).
- **F6** — Statistiques de débit horodatées via `System.currentTimeMillis()` (`startTrafficStats`) : saut d'horloge NTP/utilisateur → débits faux ponctuellement (préférer `SystemClock.elapsedRealtime()`).
- **F7** — `IpFinderRecoveryStore` : la restauration après redémarrage dépend du rôle assistant vocal (limitation plateforme, documentée).
- **F8** — `DialerWebview` : JS activé sur `http://127.0.0.1` (autorisé par la NSC locale uniquement) sans restriction de navigation (`shouldOverrideUrlLoading`).
- **F9** — Abonnements `http://` : même avec « URL non fiable autorisée », la NSC bloque le cleartext → échec systématique ; l'option est trompeuse (incohérence réglage/réalité).
- **F10** — `MainRepository` (canal `UNLIMITED`) : sain tant que la collecte est active ; dépend du cycle de vie du ViewModel (pas de fuite constatée).
- **F11** — Build release : `dams-build.yml:104` lance `assemblePlaystoreRelease` **sans** `-PpremiumEnabled=true` → `PREMIUM_ENABLED=false` dans l'APK publié (semble voulu : aucune mention Premium dans CHANGELOG/README ; le buildType `premiumTest` force `true`). À confirmer comme intentionnel.

---

## 4. Points vérifiés NON-bugs (pour info)

- **HTTP sur port socks** : validé avec le vrai Xray 26.11.9 (inbound socks `127.0.0.1:18080`, `curl -x http://` → 200). Comportement normal de Xray, pas un bug.
- `CoreConfigContextBuilder` (`server!!`), `PremiumSubscriptionClient` (`text!!`), `InstantFailoverManager` (`previous!!`) : null-sécurité vérifiée, branches exclusives → sûrs.
- `util/ZipUtil.kt` : anti zip-slip (`resolveEntryTarget`), limites taille/entrées/ratio (:160-200) → robuste.
- `DamsFileCodec` (PBKDF2 210k itérations, AES-GCM) et `PremiumTokenStore` (Keystore) : solides.
- Tous les `PendingIntent` en `FLAG_IMMUTABLE` ; récepteurs internes en `RECEIVER_NOT_EXPORTED` (`Utils.receiverFlags()`).
- `WorkManagerInitializer` bien retiré du manifest (provider `InitializationProvider` avec `tools:node="remove"`) → `WorkManager.initialize` dans `AngApplication` ne crash pas.
- `VpnInterfaceAddressConfig.getConfigByIndex` borné (fallback OPTION_1).
- `ServerHealthStore.decay` : moyenne de latence approximativement conservée ; comportement acceptable.
- `scheduleReconnect` : boucle bornée par `CoreSupervisor` (limites/quota), pas de boucle infinie constatée.
- `ZipUtil`/`BackupRestoreTransaction` : restauration transactionnelle testée (`BackupRestoreTransactionTest`).
- Matrice protocoles Xray 26.11.9 (vmess-ws-tls, vless vision/grpc/kcp, ss-2022/classic, socks, http, TLS pcs/vcn, Trojan, REALITY) : `Configuration OK` avec le binaire officiel.

---

## 5. Non couvert par cet audit (limites)

- Aucun test d'instrumentation ni terrain : poignée de main réelle, TCP/UDP/DNS réel, débits, reconnexion réseau réelle, crash sur device — **non exécutés**, donc rien n'a été affirmé les concernant.
- Bibliothèques natives (libhevtun, libv2ray AAR 26.11.9) : non auditées (binaire).
- Vulnérabilités CVE des dépendances (OkHttp 5.5.0, Gson 2.14.0, etc.) : pas de vérification réseau locale ; versions récentes au moment de l'audit.
- Play Store / compatibilité fenêtrage / tablettes : hors périmètre.
- 100 fichiers de tests JVM couvrent la logique pure (recovery policy, gate, supervisor, multipath, fmt, ipfinder…) ; les chemins Android (VpnService, notifications, widgets, Tasker réel) ne sont pas automatisés.

---

## 6. Ordre de correction suggéré (à valider)

1. **E1** (gate/timeout) — robustesse cœur, risque utilisateur élevé.
2. **M1 + M5** (lifecycle services + fail-closed relais) — petits correctifs, fort bénéfice.
3. **E2/E3/E4/E5** (sécurité import/app-lock/Tasker/custom) — demandent des choix produit (confirmation UI, permission Tasker, politique custom) → besoin de votre arbitrage.
4. **M2 (MMKV chiffré)** — migration délicate (multi-processus + sauvegardes) → phase dédiée.
5. **M3/M4/M6/M7/M8 + F*** — par lots.

*Fin du rapport — aucune modification de code effectuée.*
