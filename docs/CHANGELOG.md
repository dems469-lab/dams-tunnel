# Changelog — Dams Tunnel

Toutes les versions notables de l’application sont documentées ici.
Format : `MAJEUR.MINEUR.CORRECTIF`.

## Règles de versionnement

- **CORRECTIF** (`x.y.Z`) : correction de bug, sans nouvelle fonction ni changement de comportement attendu.
- **MINEUR** (`x.Y.0`) : nouvelle fonctionnalité compatible avec l’existant.
- **MAJEUR** (`X.0.0`) : changement important, rupture de compatibilité ou refonte.

`versionName` est défini à un seul endroit, `V2rayNG/gradle.properties` (`damsVersionName`), puis
complété par `+<hash7>` sur le CI et `+local` en build local.
`versionCode` vient de `GITHUB_RUN_NUMBER` sur le CI, sinon de `git rev-list --count HEAD`.

## 1.0.0 — 2026-11-07

- Première version Dams Tunnel, séparée de la version de v2rayNG (base amont 2.3.11).
- Écran « À propos » : version de l’application, version du cœur Xray lue dynamiquement, base v2rayNG.
- Journal : première ligne `DAMS TUNNEL <version> / Xray <version>` à chaque démarrage.
- APK nommé `DamsTunnel_<versionName>_<abi>.apk`, artefacts CI au même nom.
- `fix(core)` : arrêt explicite quand le DNS de bootstrap d’un outbound proxy échoue,
  pour éviter le deadlock DoH.
