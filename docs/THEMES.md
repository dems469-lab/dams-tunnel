# Thèmes Dams Tunnel

Les couleurs et paramètres visuels des palettes sont centralisés dans
`DamsThemeTokens` et `resolveDamsThemeTokens()` de
`app/src/main/java/com/v2ray/ang/ui/compose/Theme.kt`. Les écrans Compose doivent
utiliser `DamsTheme` ou `MaterialTheme.colorScheme`, jamais coder leurs couleurs ou
durées d'animation localement. Les couleurs passent par une transition de 400 ms.
Le token `avertissement` définit les alertes et l'état « partielle » avec un contraste
lisible en variantes claires et sombres. Les transitions des pastilles de protocole
utilisent `chipTransitionMillis` (200 ms) et sont désactivées avec les animations système.

| Choix | Style | Respiration connectée | Onde / état | Lueur | Particules |
| --- | --- | ---: | ---: | ---: | --- |
| Dams Cyan | néon | 3 000 ms | 850 ms | 0,82 | Oui, hors mode économie |
| Or nuit | doré | 4 000 ms | 1 500 ms | 0,46 | Poussière dorée, hors mode économie |
| Océan Pro | fluide | 3 600 ms | 1 400 ms | 0,66 | Oui, hors mode économie |
| Émeraude sécurisé | organique | 3 800 ms | 1 300 ms | 0,58 | Oui, hors mode économie |
| Arctique clair | cristal | 2 200 ms | 700 ms | 0,08–0,12 | Micro-brillances en clair seulement |

Le mode Clair/Sombre/Système continue de choisir les variantes lumineuse et sombre;
la couleur dynamique Android reste disponible pour Dams Cyan lorsque son réglage
est actif. L'échelle système d'animations désactivée supprime les transitions et
les animations en boucle. Les animations de l'accueil et les particules ne tournent
que lorsque l'accueil est au premier plan; le mode économie d'énergie coupe les
particules. L'écran Serveurs gratuits utilise les mêmes jetons pour les cartes,
les statuts, l'avertissement, les boutons et le fond dans les cinq palettes.

Pour ajouter un thème, ajouter son bloc `DamsThemeTokens` à
`resolveDamsThemeTokens()`, son identifiant/style à `damsPaletteStyleForId()`, puis
son libellé traduit et sa valeur stable aux ressources `dams_theme_palette` et
`dams_theme_palette_value` (anglais et français). Les tests de contraste et de
paramètres d'animation sont dans `DamsPaletteStyleTest`.
