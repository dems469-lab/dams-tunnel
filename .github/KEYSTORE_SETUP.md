# Configurer la signature Android de publication

Le workflow `Dams APK` produit des APK `PlaystoreRelease` signés avec une clé stable. Sans les quatre secrets ci-dessous, il s'arrête avec une erreur explicite. Il n'existe aucun repli vers une clé de debug ou une clé générée automatiquement.

## Créer la clé de publication

Le propriétaire du dépôt doit créer et conserver le keystore hors du dépôt, dans un emplacement sauvegardé et à accès restreint. Perdre cette clé peut empêcher la publication de mises à jour compatibles avec les APK déjà distribués.

Sur une machine sécurisée avec Java, créer une clé (remplacer l'alias si souhaité) :

```sh
keytool -genkeypair -v -keystore dams-release.jks -alias dams-release \
  -keyalg RSA -keysize 3072 -validity 10000
```

`keytool` demande les mots de passe de manière interactive. Le mot de passe du keystore et celui de la clé peuvent être différents. Ne pas partager le fichier ni inscrire les mots de passe dans une commande ou dans le dépôt.

## Ajouter les secrets GitHub

Dans le dépôt GitHub, ouvrir **Settings → Secrets and variables → Actions → New repository secret** et créer exactement :

- `KEYSTORE_BASE64` : contenu Base64 du fichier JKS, obtenu localement avec `base64 -w 0 dams-release.jks` sous Linux (sur macOS : `base64 < dams-release.jks | tr -d '\n'`).
- `KEYSTORE_PASSWORD` : mot de passe du keystore.
- `KEY_ALIAS` : alias créé, par exemple `dams-release`.
- `KEY_PASSWORD` : mot de passe de la clé.

Coller les valeurs directement dans l'interface GitHub sécurisée. Ne pas les afficher dans les logs, les passer comme arguments de commande, ni les enregistrer dans Git. Conserver une sauvegarde chiffrée du keystore et des mots de passe dans un gestionnaire de secrets sécurisé.

Après configuration, lancer **Actions → Dams APK → Run workflow**. Le résultat attendu est l'artefact `dams-release-apks`. Le workflow valide le keystore et l'alias, puis supprime la copie temporaire du runner à la fin de l'étape de compilation.
