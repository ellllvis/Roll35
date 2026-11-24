# Roll35

Application Android personnelle : un appareil photo qui simule des pellicules
(simulations de film du Fujifilm X-E5, CineStill, Pro 400H) en direct dans le viseur.

## Avancement

1. ✅ Recherche des pellicules et de leurs réglages
2. ✅ App de base : viseur plein capteur (4:3), déclencheur, changement de caméra,
   mise au point au toucher, photos enregistrées dans la galerie (`Pictures/Roll35`)
3. ✅ Sélecteur de pellicule en direct (bande de pastilles ou glisser sur le viseur),
   filtre GPU appliqué au viseur et aux photos : table de couleurs 3D, grain, halo rouge
4. ⏳ Installation de l'APK sur le téléphone

## Technique

- Kotlin, CameraX 1.4, Android 10 minimum.
- `Film.kt` : la liste des pellicules et leurs réglages (à retoucher ici après essais).
- `LutBuilder.kt` : transforme ces réglages en table de couleurs 3D 33×33×33.
- `FilmShader.kt` : le shader OpenGL (table de couleurs, halo, grain), commun au viseur
  et aux photos.
- `FilmProcessor.kt` : filtre du viseur, branché à CameraX par un `CameraEffect`.
- `PhotoDeveloper.kt` : développe la photo pleine résolution (≈ 12 Mpx) et l'enregistre.
- `FilmCamera.kt` regroupe la caméra.
- Chaque envoi sur `main` compile l'APK automatiquement (onglet **Actions** du dépôt,
  fichier `roll35-apk`).
