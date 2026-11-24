# Roll35

Application Android personnelle : un appareil photo qui simule des pellicules
(simulations de film du Fujifilm X-E5, CineStill, Pro 400H) en direct dans le viseur.

## Avancement

1. ✅ Recherche des pellicules et de leurs réglages
2. ✅ App de base : viseur plein capteur (4:3), déclencheur, changement de caméra,
   mise au point au toucher, photos enregistrées dans la galerie (`Pictures/Roll35`)
3. ⏳ Sélecteur de pellicule en direct + filtres GPU
4. ⏳ Installation de l'APK sur le téléphone

## Technique

- Kotlin, CameraX 1.4, Android 10 minimum.
- `FilmCamera.kt` regroupe la caméra. Les filtres de l'étape 3 s'y branchent via un
  `CameraEffect` (OpenGL), appliqué au viseur et aux photos enregistrées.
- Chaque envoi sur `main` compile l'APK automatiquement (onglet **Actions** du dépôt,
  fichier `roll35-apk`).
