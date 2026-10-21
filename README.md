# Dams Tunnel

[![Dams CI](https://github.com/dems469-lab/dams-tunnel/actions/workflows/dams-ci.yml/badge.svg)](https://github.com/dems469-lab/dams-tunnel/actions/workflows/dams-ci.yml)
[![Dams APK](https://github.com/dems469-lab/dams-tunnel/actions/workflows/dams-build.yml/badge.svg)](https://github.com/dems469-lab/dams-tunnel/actions/workflows/dams-build.yml)

**Dams Tunnel** est une application Android de tunnel VPN construite autour du cœur Xray. Le projet conserve les formats et composants open source dont il est dérivé, tout en développant progressivement une identité, une interface et des fonctions propres à Dams Tunnel.

> Le VPN ne garantit ni une vitesse précise, ni l'accès à tous les sites ou réseaux. Les performances dépendent notamment du serveur choisi, de l'opérateur, du réseau, du protocole et de la configuration. N'utilisez que des serveurs et profils auxquels vous faites confiance.

## Langues

- [Français](#français)
- [English](#english)

## État du projet

- Version Android déclarée dans le projet : **2.3.11**.
- Le code Android se trouve dans `V2rayNG/` ; les workflows GitHub construisent et vérifient le projet.
- Les interfaces de production sont en cours de personnalisation. Certaines fonctions, dont **IP Finder**, sont expérimentales et peuvent dépendre des réglages Android et du fabricant du téléphone.
- Une compilation réussie ne prouve pas le fonctionnement sur tous les appareils. Les tests de service VPN, de reconnexion en arrière-plan et de bascule du mode avion doivent aussi être effectués sur téléphone.

## Fonctions

- Gestion de profils et de liens de configuration compatibles avec les formats pris en charge par le cœur intégré.
- Tunnel VPN Android et service en arrière-plan avec notification persistante.
- Statistiques de trafic du tunnel et suivi séparé de la consommation mobile quotidienne, lorsque les informations nécessaires sont disponibles.
- Sauvegarde et restauration de configurations, dont le format Dams protégé par mot de passe.
- **IP Finder (bêta)** : outil expérimental de recherche d'adresse IP cellulaire, soumis au rôle d'assistant Android et aux restrictions du constructeur. Son fonctionnement et ses limites sont décrits dans l'écran de la fonction.
- Journal local et outils de diagnostic.

Les protocoles, transports et options réellement disponibles dépendent de la version du cœur Xray et du type de profil. Dams Tunnel ne fournit pas de serveurs VPN : l'utilisateur doit apporter ses propres profils ou abonnements.

## Télécharger

Les versions officielles de Dams Tunnel sont publiées sur la [page Releases du dépôt](https://github.com/dems469-lab/dams-tunnel/releases). N'installez pas un APK provenant d'une autre page ou d'un autre projet : une signature différente empêchera normalement la mise à jour directe et peut présenter un risque de sécurité.

Les APK de test publiés par GitHub Actions sont des artefacts de workflow et ne constituent pas automatiquement une version stable. Vérifiez le nom de la branche, le résultat du workflow et l'empreinte de certificat avant installation.

## Compilation et tests

### Prérequis

- Git avec prise en charge des sous-modules.
- Android Studio ou un JDK compatible avec le wrapper Gradle du dépôt.
- Android SDK et NDK correspondant aux versions déclarées dans `.github/workflows/dams-ci.yml` et `.github/workflows/dams-build.yml`.

Les versions d'AGP, Kotlin, Gradle, SDK, NDK et du cœur Xray évoluent avec le dépôt : consultez les fichiers de build et les workflows plutôt que de recopier des versions anciennes dans vos scripts.

### Récupérer le code

```bash
git clone --recurse-submodules https://github.com/dems469-lab/dams-tunnel.git
cd dams-tunnel
```

Si le dépôt est déjà cloné sans ses sous-modules :

```bash
git submodule update --init --recursive
```

### Construire localement

Le workflow CI prépare les bibliothèques natives et télécharge un AAR Xray épinglé avec vérification SHA-256. Reproduisez ces étapes à partir de `.github/workflows/dams-ci.yml`, puis lancez Gradle depuis `V2rayNG/` :

```bash
cd V2rayNG
./gradlew testPlaystoreDebugUnitTest lintPlaystoreDebug
./gradlew assemblePlaystoreDebug
```

Sous Windows, utilisez `gradlew.bat`. La compilation de publication signée est réalisée par le workflow **Dams APK** ; ne remplacez pas la clé de publication par une clé de débogage.

### Intégration continue

- **Dams CI** s'exécute sur les changements de branches et les demandes de fusion. Il prépare les bibliothèques natives, vérifie les bibliothèques attendues, lance les tests unitaires et lint.
- **Dams APK** produit des APK Play Store signés sur les branches `phase-*` ou sur lancement manuel. L'artefact porte le nom `dams-release-apks` ; le workflow affiche aussi l'empreinte SHA-256 du certificat.
- La signature de publication utilise les secrets GitHub `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` et `KEY_PASSWORD`. Leurs valeurs ne doivent jamais être ajoutées au dépôt, au README ou aux journaux.
- Les étapes exactes et leurs résultats sont consultables dans l'onglet **Actions** du dépôt.

## Organisation du dépôt

| Chemin | Rôle |
| --- | --- |
| `V2rayNG/` | Application Android Kotlin, interface Compose, ressources, tests et configuration Gradle. |
| `V2rayNG/app/src/main/java/com/v2ray/ang/core/` | Assemblage de configuration et cycle de vie du cœur réseau. |
| `V2rayNG/app/src/main/java/com/v2ray/ang/service/` | Services Android associés au tunnel VPN. |
| `V2rayNG/app/src/main/java/com/v2ray/ang/ipfinder/` | Fonction expérimentale IP Finder, isolée du stockage des profils. |
| `V2rayNG/app/src/test/` | Tests unitaires. |
| `hev-socks5-tunnel/` | Sous-module du composant de tunnel HEV. |
| `AndroidLibXrayLite/` | Sous-module associé au cœur Android Xray. |
| `.github/workflows/` | Workflows CI et production des APK. |

Cette carte est indicative : la structure réelle du code est la référence.

## Sécurité et confidentialité

- Les profils VPN peuvent contenir des identifiants, clés ou adresses privés. Ne les partagez pas publiquement.
- Ne commitez jamais de keystore, mot de passe, jeton, clé privée ou fichier de configuration réel.
- Le format `.dams` protégé doit être exporté avec un mot de passe robuste ; conservez ce mot de passe séparément. La perte du mot de passe peut rendre la sauvegarde irrécupérable.
- Les mesures de trafic et journaux peuvent différer selon le cœur, le profil et l'appareil. Les journaux sont destinés au diagnostic et ne doivent pas contenir de secrets.
- Un VPN ne rend pas automatiquement anonyme et ne protège pas contre un appareil compromis, un serveur malveillant ou les données que l'utilisateur choisit de transmettre.
- IP Finder peut demander à Android le rôle d'assistant pour son test expérimental du mode avion. Lisez son explication et les confirmations système avant activation ; les réglages du constructeur peuvent limiter son fonctionnement en arrière-plan.
- Les rapports de vulnérabilité ne doivent pas inclure de profils, mots de passe, IP personnelles ou journaux contenant des données sensibles. Ouvrez un rapport privé auprès du mainteneur du dépôt.

## Contribution

Les changements doivent rester ciblés et accompagnés de tests lorsque c'est possible. Avant une demande de fusion :

1. Consultez les consignes `AGENTS.md` applicables au chemin modifié.
2. Ajoutez ou mettez à jour les tests correspondant au comportement changé.
3. Lancez les tests et lint définis dans le workflow **Dams CI**.
4. Indiquez les appareils et versions Android testés, ainsi que les limites connues.
5. Ne joignez jamais de secret ou de profil VPN réel.

Les changements du service VPN, des permissions et des callbacks Android doivent être vérifiés sur appareil en plus de la compilation.

## Origine, propriété et licences

Dams Tunnel est développé dans le dépôt `dems469-lab/dams-tunnel` à partir d'une base open source dérivée de [v2rayNG](https://github.com/2dust/v2rayNG). Les commits de cette base et les composants tiers ne deviennent pas la propriété exclusive du mainteneur de Dams Tunnel par le seul fait de les modifier ou de les renommer.

Le dépôt conserve la licence **GNU GPL version 3** dans `LICENSE`. Les modifications couvertes par cette licence et distribuées doivent respecter ses conditions, notamment la conservation des avis applicables et la mise à disposition du code source correspondant selon les modalités de la GPL. Les sous-modules, bibliothèques et ressources peuvent avoir des licences distinctes : consultez leurs fichiers `LICENSE`, leurs avis et leurs dépôts respectifs.

Le nom, le logo, les éléments graphiques originaux et les contributions créés spécifiquement pour Dams Tunnel peuvent avoir des titulaires de droits différents du code dérivé. Leur statut ne change pas les licences du code tiers incorporé. Ce README ne constitue pas un avis juridique.

## Remerciements

Merci aux mainteneurs de v2rayNG, Xray, HEV et aux auteurs des bibliothèques et ressources open source utilisées par le projet. Les crédits et licences propres à chaque composant priment ; cette liste n'est pas exhaustive.

---

## English

**Dams Tunnel** is an Android VPN tunnelling application built around the Xray core. It retains open-source formats and components from its upstream codebase while gradually developing a Dams Tunnel identity, interface and project-specific features.

> A VPN cannot guarantee a particular speed or access to every website or network. Performance depends on the selected server, carrier, network conditions, protocol and configuration. Use only servers and profiles you trust.

### Project status

- Android version declared by the project: **2.3.11**.
- Android source lives in `V2rayNG/`; GitHub workflows build and verify the project.
- Production interfaces are being customized. Some features, including **IP Finder**, are experimental and may depend on Android settings and device-vendor restrictions.
- A successful build does not prove operation on every device. VPN service, background reconnection and airplane-mode behavior also require on-device testing.

### Features

- VPN profile management and configuration links supported by the bundled core.
- Android VPN tunnel service with a persistent notification.
- Tunnel traffic statistics and separate daily mobile-data usage tracking when the required information is available.
- Configuration backup and restore, including password-protected Dams files.
- **IP Finder (beta)**: an experimental cellular-IP search tool subject to Android assistant-role requirements and device-vendor restrictions. Its screen explains its behavior and limitations.
- Local logs and diagnostic tools.

Available protocols, transports and options depend on the bundled Xray core and profile type. Dams Tunnel does not provide VPN servers; users must supply their own profiles or subscriptions.

### Downloads

Official Dams Tunnel releases are published on this repository's [Releases page](https://github.com/dems469-lab/dams-tunnel/releases). Do not install APKs from unrelated pages or projects: a different signing certificate will normally prevent an in-place update and may be a security risk.

GitHub Actions APK artifacts are workflow outputs and are not automatically stable releases. Check the branch, workflow result and signing-certificate fingerprint before installing.

### Build and test

Requirements include Git with submodule support, a compatible JDK/Android Studio, and the Android SDK/NDK versions declared by the workflows.

```bash
git clone --recurse-submodules https://github.com/dems469-lab/dams-tunnel.git
cd dams-tunnel
```

If the repository was cloned without submodules:

```bash
git submodule update --init --recursive
```

The CI workflow builds native libraries and downloads a pinned Xray AAR with a SHA-256 check. Follow `.github/workflows/dams-ci.yml` for those preparation steps, then run Gradle from `V2rayNG/`:

```bash
cd V2rayNG
./gradlew testPlaystoreDebugUnitTest lintPlaystoreDebug
./gradlew assemblePlaystoreDebug
```

On Windows, use `gradlew.bat`. Signed release APKs are produced by **Dams APK**; never substitute a debug key for the release key.

### Continuous integration and release signing

- **Dams CI** runs on branch changes and pull requests. It prepares native libraries, checks expected libraries, runs unit tests and lint.
- **Dams APK** builds signed Play Store APKs on `phase-*` branches or by manual dispatch. Its artifact is named `dams-release-apks`; the workflow also prints the certificate SHA-256 fingerprint.
- Release signing uses GitHub secrets named `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Never commit their values or print them in logs.
- Check the repository's **Actions** tab for exact steps and results.

### Repository layout

| Path | Purpose |
| --- | --- |
| `V2rayNG/` | Kotlin Android app, Compose UI, resources, tests and Gradle configuration. |
| `V2rayNG/app/src/main/java/com/v2ray/ang/core/` | Core configuration assembly and lifecycle. |
| `V2rayNG/app/src/main/java/com/v2ray/ang/service/` | Android services associated with the VPN tunnel. |
| `V2rayNG/app/src/main/java/com/v2ray/ang/ipfinder/` | Experimental IP Finder, isolated from VPN-profile storage. |
| `V2rayNG/app/src/test/` | Unit tests. |
| `hev-socks5-tunnel/` | HEV tunnel submodule. |
| `AndroidLibXrayLite/` | Submodule associated with the Android Xray core. |
| `.github/workflows/` | CI and APK build workflows. |

This map is illustrative; the source tree is authoritative.

### Security and privacy

- VPN profiles may contain credentials, keys or private addresses. Do not publish them.
- Never commit a keystore, password, token, private key or real VPN configuration.
- Use a strong password for protected `.dams` exports and store it separately. Losing it may make the backup unrecoverable.
- Traffic statistics and logs can vary by core, profile and device. Logs are diagnostic data and must not contain secrets.
- A VPN does not automatically provide anonymity or protect against a compromised device, malicious server or information the user chooses to disclose.
- IP Finder may request Android's assistant role for its experimental airplane-mode test. Read its explanation and system prompts; vendor settings may restrict background operation.
- Do not include profiles, passwords, personal IP addresses or sensitive logs in vulnerability reports. Contact the repository maintainer privately.

### Contributing

Keep changes focused and add tests where practical. Before opening a pull request:

1. Read the applicable `AGENTS.md` instructions.
2. Add or update tests for changed behavior.
3. Run the tests and lint tasks used by **Dams CI**.
4. State which devices and Android versions were tested, and list known limitations.
5. Never attach secrets or real VPN profiles.

VPN-service, permission and Android-callback changes require on-device testing in addition to compilation.

### Upstream origin, ownership and licenses

Dams Tunnel is developed in `dems469-lab/dams-tunnel` from an open-source codebase derived from [v2rayNG](https://github.com/2dust/v2rayNG). Modifying or renaming upstream code does not make that code or third-party components exclusively owned by the Dams Tunnel maintainer.

The repository retains the **GNU GPL version 3** in `LICENSE`. Distributed modifications covered by that license must comply with its terms, including applicable notices and providing the corresponding source under the GPL as required. Submodules, libraries and data may carry separate licenses; consult their license files, notices and upstream repositories.

The Dams Tunnel name, logo, original visual assets and project-specific contributions may have rights holders distinct from the derived code. That does not change the licenses of incorporated third-party code. This README is not legal advice.

### Acknowledgements

Thanks to the maintainers of v2rayNG, Xray and HEV, and to the authors of the open-source libraries and data used by this project. Component-specific notices and licenses take precedence; this list is not exhaustive.
