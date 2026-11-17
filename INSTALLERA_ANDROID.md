# Installera Optical Scanner på Android (steg för steg)

**Direktlänk till APK (GitHub Release):**
[https://github.com/Ayub-Ali-Barre/optical-scanner-app/releases/download/v0.2-debug/app-debug.apk](https://github.com/Ayub-Ali-Barre/optical-scanner-app/releases/download/v0.2-debug/app-debug.apk)

**Release-sida:**
[https://github.com/Ayub-Ali-Barre/optical-scanner-app/releases/tag/v0.2-debug](https://github.com/Ayub-Ali-Barre/optical-scanner-app/releases/tag/v0.2-debug)

---

## Metod 1: Direkt på Android-mobilen (enklast, utan sladd)

1. **Ladda ner APK-filen på mobilen**
   Öppna länken ovan i mobilens webbläsare för att ladda ner `app-debug.apk` (eller skicka filen från datorn via Google Drive/Discord).
2. **Öppna filen**
   Tryck på den nedladdade `app-debug.apk` i aviseringen eller i appen **Filer** → **Nedladdningar**.
3. **Tillåt okända appar**
   Om Android visar en varning om att appen inte får installera okända appar:
   - Tryck på **Inställningar**
   - Slå på **Tillåt från denna källa**
   - Gå tillbaka ett steg och tryck på **Installera**
4. **Hantera eventuell Google Play Protect-varning**
   Eftersom detta är en egenbyggd test-APK utanför Google Play kan Play Protect visa en varning. Tryck då på **Fler detaljer** → **Installera ändå**.
5. **Starta appen**
   Öppna appen och tryck **Tillåt** när den frågar om kameratillstånd.

---

## Metod 2: Via USB-kabel från datorn (`adb`)

Passar bra om du har mobilen inkopplad i datorn och vill installera snabbt under utveckling.

1. **Aktivera utvecklaralternativ på mobilen**
   Gå till **Inställningar → Om telefonen** och tryck **7 gånger** på **Byggnummer** (*Build number*).
2. **Slå på USB-felsökning**
   Gå till **Inställningar → System → Utvecklaralternativ** och aktivera **USB-felsökning** (*USB debugging*).
3. **Anslut med USB-kabel**
   Koppla mobilen till datorn och godkänn dialogen **"Tillåt USB-felsökning?"** på mobilens skärm.
4. **Installera via PowerShell på datorn**
   ```powershell
   adb install -r "C:\Users\ayuba\Desktop\agent\optical-scanner-app\app\build\outputs\apk\debug\app-debug.apk"
   ```

---

## Testa appen direkt efter installation

1. Öppna webbsimulatorn på en dator eller annan skärm:
   [https://ayub-ali-barre.github.io/optical-address-bus/](https://ayub-ali-barre.github.io/optical-address-bus/)
2. Välj valfri adress (`1`–`15`), tryck **Starta kontinuerlig sändning** och gärna **Kameraläge (Helskärm)**.
3. Öppna Android-appen, rikta siktrutan i mitten så att hela lampan plus lite mörk kant syns i rutan och tryck på skannerknappen i mitten.
4. Om skanningen har svårt att hänga med på `50 ms`, prova `100 ms` eller `200 ms` på både webbsidan och i appens vänstra meny.
