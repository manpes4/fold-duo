# FoldFX

Transition façon iPhone Duo pour Galaxy Z Fold : quand tu ouvres ou fermes le téléphone, le fond d'écran se floute, glisse depuis la charnière et se remet au point, au lieu de la bascule sèche entre les deux écrans.

`apercu.gif` montre le rendu du shader (ouverture, fermeture, puis mise au point sur l'écran externe).

## Comment ça marche

Un service au premier plan écoute le capteur d'angle de charnière (`Sensor.TYPE_HINGE_ANGLE`, exposé par Android sur les Fold). Dès que l'angle bouge, il affiche par-dessus l'écran une fenêtre transparente aux touches qui dessine ton fond d'écran à travers un shader AGSL (flou de mise au point, flou de mouvement, front de dissolution depuis la charnière). La progression suit l'angle en temps réel. Une fois le téléphone complètement ouvert, l'effet se fond et disparaît ; une fois fermé, l'écran externe fait une courte mise au point.

Le code n'a aucune dépendance externe : SDK Android + Kotlin uniquement.

## Compiler l'APK

### Option A : GitHub, sans rien installer (faisable depuis un PC ou un Mac)

1. Crée un dépôt privé sur GitHub et envoie-y le contenu de ce dossier (glisser-déposer dans l'interface web, ou `git push`).
2. Onglet **Actions** → le workflow **Build APK** se lance tout seul (sinon « Run workflow »).
3. Au bout de 3 à 5 minutes, télécharge l'artefact **FoldFX-apk** (un zip contenant `app-debug.apk`).

### Option B : Android Studio

1. Ouvre le dossier `FoldFX` dans Android Studio (version récente, JDK 17).
2. Laisse la synchronisation Gradle se faire. Si Android Studio propose de mettre à jour AGP/Gradle, tu peux accepter.
3. Branche le Fold en débogage USB et clique sur ▶, ou *Build → Build APK(s)*.

## Installer et configurer

1. Copie l'APK sur le Fold et ouvre-le (autorise « Installer des applis inconnues » pour ton gestionnaire de fichiers).
2. Dans FoldFX :
   - **Autoriser l'affichage par-dessus les apps** (obligatoire).
   - **Ne jamais mettre en veille FoldFX** (fortement conseillé sur One UI, sinon Samsung tue le service).
   - Choisis l'image de l'écran interne et celle de l'écran externe. Le plus réaliste : une capture d'écran de ton écran d'accueil (sans icônes si tu préfères un effet plus pur).
   - Active **Activer la transition**.
3. Règle ensuite l'intensité du flou et le côté qui bouge. L'aperçu en bas de l'écran peut être piloté par un curseur ou directement par la charnière.

Dans les réglages Samsung, pense aussi à Paramètres → Écran → « Apps sur l'écran externe » (le nom exact varie selon la version de One UI) pour que les apps continuent quand tu fermes le téléphone ; sinon il se verrouille et l'effet de fermeture ne se voit pas.

## Limites (à connaître)

- **Ce n'est pas une intégration système.** Samsung ne donne pas aux apps tierces l'accès à l'animation de bascule d'écran. FoldFX dessine *par-dessus* : il ne peut pas déformer le vrai contenu des apps, il affiche ton fond d'écran pendant le mouvement puis s'efface.
- **Pas sur l'écran de verrouillage.** Android place l'écran de verrouillage au-dessus des overlays, donc l'effet ne joue que quand le téléphone est déverrouillé (typiquement : tu utilises l'écran externe puis tu ouvres).
- **Mode Flex :** si tu t'arrêtes à mi-ouverture plus de 0,65 s, l'effet se retire pour ne pas bloquer l'écran.
- Pendant l'animation (quelques centaines de ms), les touches ne passent pas.
- La bascule matérielle entre les deux dalles reste gérée par One UI ; selon la version, il peut y avoir une ou deux images noires que FoldFX ne peut pas masquer.

## Réglages avancés

Les seuils sont en haut de `FoldFxService.kt` :

| Constante | Rôle | Défaut |
|---|---|---|
| `FULL_OPEN` | angle considéré comme ouvert | 166° |
| `FULL_CLOSED` | angle considéré comme fermé | 8° |
| `TRIGGER_DELTA` | mouvement mini pour déclencher | 9° |
| `PROG_START` / `PROG_END` | plage d'angle mappée sur l'animation | 12° → 162° |
| `STALL_MS` | délai d'immobilité avant retrait | 650 ms |

L'apparence de l'effet se modifie dans `FoldShader.kt` (rayon de flou, longueur du flou de mouvement, vitesse du front, liseré lumineux).

L'affichage de l'angle en direct dans l'app permet de vérifier les valeurs que renvoie ton Fold et d'ajuster ces seuils.
