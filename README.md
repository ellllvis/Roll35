# Roll35

Application Android personnelle : un appareil photo qui simule des pellicules
(simulations de film du Fujifilm X-E5, CineStill, Pro 400H) en direct dans le viseur.

## Avancement

1. ✅ Recherche des pellicules et de leurs réglages
2. ✅ App de base : viseur plein capteur (4:3), déclencheur, changement de caméra,
   mise au point au toucher, photos enregistrées dans la galerie (`Pictures/Roll35`)
3. ✅ Sélecteur de pellicule en direct (bande de pastilles ou glisser sur le viseur),
   filtre GPU appliqué au viseur et aux photos : table de couleurs 3D, grain, halo rouge
4. ✅ Installation de l'APK sur le téléphone (voir ci-dessous)

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

## Installer l'app sur le téléphone

1. Sur le téléphone, ouvrir https://github.com/ellllvis/Roll35/releases/latest
2. Toucher **Roll35.apk** dans « Assets » pour le télécharger.
3. Ouvrir le fichier téléchargé (notification ou application **Fichiers**, dossier Téléchargements).
4. Si Android bloque : toucher **Paramètres**, activer **Autoriser cette source**, puis revenir.
5. Toucher **Installer**. Si Play Protect avertit d'une app inconnue : **Installer quand même**
   (l'app vient de ce dépôt, elle n'est pas sur le Play Store).
6. Ouvrir Roll35 et autoriser la caméra.

Pour une nouvelle version : refaire les étapes 1 à 5, l'app se met à jour sans perdre de réglages.
