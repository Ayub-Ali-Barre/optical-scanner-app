# Optical Scanner (Android Prototyp)

En bare-bones Android-prototyp i Kotlin och Jetpack Compose med CameraX för optisk signalavläsning och anpassad UI-design.

---

## Förutsättningar installerade på din PC

- **Java:** Microsoft OpenJDK 17 (`C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot`)
- **Android SDK:** Platform 34, Build-Tools 34.0.0
- **Android Emulator & Systemavbildning:** Pixel 7 AVD (`Pixel_Scanner`) med Android 14 x86_64
- **Kamera-integration:** Kopplad till din dators **HP Wide Vision HD Camera** som bakkamera

---


## Projektstruktur

- `app/src/main/java/com/standby/opticalscanner/MainActivity.kt`: Huvudskärmen och kamerabehörighet.
- `app/src/main/java/com/standby/opticalscanner/ui/CameraPreview.kt`: CameraX levande videoström.
- `app/src/main/java/com/standby/opticalscanner/ui/StandbyBottomBar.kt`: Den blå baren med den böjda formen, knappsatsen, den upphöjda scanner-knappen `[ — ]` och uppdatera-knappen.
