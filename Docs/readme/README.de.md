<div align="center">

[English](../../README.md) · [简体中文](../../README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · **Deutsch** · [Português (Brasil)](README.pt-BR.md)

<img src="../../Design/whale-girl-ios-app-promo-16x9-v3.png" alt="Whaleness-App für iOS" width="100%">

# Whaleness — Mobiler Drittanbieter-Client für DeepSeek Harness

**Native Android- und iOS-Apps mit gemeinsam genutzter Kernlogik auf Basis von Kotlin Multiplatform.**

Verbinde dich über Mobile Gateway mit DeepSeek Harness und greife vom Smartphone, Tablet oder Foldable auf Arbeitsbereiche, Live-Unterhaltungen, Agent-Ausführungsverläufe, Plugins, geplante Aufgaben und Dateien zu.

Website: [Whaleness](https://clarklevis1995.github.io/dsh-mobile/)

<p>
  <a href="https://testflight.apple.com/join/1Euwby6r"><img src="../images/badges/testflight.svg" alt="An der iOS-Beta über TestFlight teilnehmen" width="180" height="60"></a>
  &nbsp;&nbsp;
  <a href="https://github.com/Clarklevis1995/dsh-mobile/releases"><img src="../images/badges/android-github.svg" alt="Android-APK von GitHub Releases herunterladen" width="180" height="60"></a>
</p>

![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![iOS 16.1+](https://img.shields.io/badge/iOS-16.1%2B-111827?logo=apple&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
![SwiftUI](https://img.shields.io/badge/iOS-SwiftUI-F05138?logo=swift&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-Realtime-2563EB)

</div>

## Überblick

Whaleness ist ein unabhängig entwickelter nativer mobiler Drittanbieter-Client für DeepSeek Harness. Die Android- und iOS-Apps nutzen Kotlin Multiplatform (KMP):

- `shared` ist die maßgebliche Quelle für den gemeinsamen Geschäftsstatus: Protokollverarbeitung, Zustandsautomaten, Reducer, Projektionen und Synchronisierung.
- `androidApp` implementiert die Android-App mit Kotlin, Jetpack Compose und OkHttp.
- `DeepSeekHarnessMobile` erstellt die iOS-Oberfläche mit SwiftUI/UIKit und nutzt die gemeinsame Logik über ein Kotlin/Native-Framework.

Beide Apps verbinden sich über [`dsh-plugin-mobile-gateway`](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) per WebSocket mit Harness. Netzwerk, sichere Speicherung, Dateien, Bilder, Lebenszyklus und Hintergrundausführung verwenden native Plattform-APIs. Geschäftsregeln werden nach Möglichkeit in `shared/commonMain` zusammengeführt.

## Downloads und Voraussetzungen

- **iOS**: [An der TestFlight-Beta teilnehmen](https://testflight.apple.com/join/1Euwby6r). Unterstützt iPhone und iPad mit iOS / iPadOS 16.1 oder neuer.
- **Android**: [APK von GitHub Releases herunterladen](https://github.com/Clarklevis1995/dsh-mobile/releases). Erfordert Android 7.0 (API 24) oder neuer. Die Updateprüfung bevorzugt die beschleunigte Downloadquelle und überprüft heruntergeladene Pakete.
- **Desktop-Host**: DeepSeek Harness muss mit installiertem und aktiviertem **`dsh-plugin-mobile-gateway` ab Version 0.11.1** laufen. [`AppRelease/release-info.json`](../../AppRelease/release-info.json) legt die erforderliche Mindestversion fest.
- **Spracheingabe**: Aktiviere das offizielle Spracheingabe-Plugin im Plugin-Bereich des Hosts, schließe Modelldownload und Initialisierung ab und erlaube den Mikrofonzugriff auf dem Mobilgerät.

## Beta-Community

Tritt der Whaleness-Betagruppe 2 auf WeChat bei, um Erfahrungen zu teilen, Probleme zu melden und Hilfe zu erhalten. Scanne den Code mit WeChat oder speichere das Bild und lasse es in WeChat erkennen.

<p align="center">
  <a href="../../website/assets/support-wechat-2026-10-28.png"><img src="../../website/assets/support-wechat-2026-10-28.png" alt="QR-Code der Whaleness-Betagruppe 2 auf WeChat, gültig vor dem 4. November 2026" width="320"></a>
</p>

> Aktualisiert am 28. Oktober 2026. Laut Originalbild ist der Code vor dem 4. November 2026 gültig. Falls er abgelaufen ist, findest du den aktuellen Gruppen-QR-Code auf der [Produktseite](https://clarklevis1995.github.io/dsh-mobile/).

## Plattformstatus

| Plattform / Modul | Status | Hauptbestandteile |
| --- | --- | --- |
| KMP `shared` | Von Android und iOS genutzt | Gateway-Protokoll, Stores/Reducer, Unterhaltungs- und Verlaufsprojektionen, Verlaufssynchronisierung, Fragen/Genehmigungen, Sitzungssteuerung, Dateistatus des Arbeitsbereichs und Codevorschau |
| Android | Nativer Client verfügbar | Jetpack Compose, OkHttp WebSocket, DataStore für nicht vertrauliche Einstellungen, Keystore für Zugangsdaten, CameraX/ML Kit für QR-Codes, Vordergrunddienst, Anhang-Cache, Dateidownload und Vorschau |
| iOS | Nativer Client verfügbar | SwiftUI/UIKit, URLSession WebSocket, UserDefaults für nicht vertrauliche Einstellungen, Keychain für Zugangsdaten, QR-Kopplung, Hintergrundausführung, Anhänge und Arbeitsbereichsdateien |

> Android und iOS verwenden jeweils eigene native Oberflächen und Plattformfunktionen. Funktionen und Interaktionen werden angeglichen; einige systembedingte Verhaltensweisen unterscheiden sich weiterhin.

## Funktionen

### Neu hinzugekommen und verbessert

- **Agent-Teams**: Mitglieder anzeigen, zwischen Lead- und Mitglieder-Unterhaltungen wechseln und gemeinsame Aufgaben mit Status, Zuständigkeit und Link zur zugehörigen Unterhaltung prüfen.
- **Datei-Uploads**: Dateien in der Unterhaltung auswählen, Uploadfortschritt verfolgen, Anhänge entfernen oder fehlgeschlagene Uploads erneut versuchen. Gesendete und erzeugte Dateien erscheinen als Karten zum Öffnen oder Herunterladen.
- **Textauswahl und Tabellen**: Text in einer eigenen Ansicht auswählen, vollständig markieren und kopieren, ohne die Leseposition zu verlieren. Markdown-Tabellen im Vollbild öffnen, drehen, kopieren oder als CSV exportieren; schmale Tabellen passen sich der Breite an, breite lassen sich horizontal scrollen.
- **Verlauf und Speicher**: Unterhaltungen lokal zwischenspeichern und häufig verwendete Unterhaltungen offline durchsuchen. Speicherverbrauch von Bildern, Chatverlauf, Dateivorschauen und temporären Dateien prüfen und lokale Caches nach Kategorie leeren. Beim erneuten Öffnen einer Sitzung den Verlauf wiederherstellen und ältere Nachrichten schrittweise laden.
- **iPhone Duo und breite Fenster**: Startseitenspalten, sichere Bereiche, Eingabe und animierter Hintergrund passen sich dem Auf- und Zuklappen sowie der Drehung an. Breite Unterhaltungen zeigen eine dauerhafte Seitenleiste mit nach Arbeitsbereich gruppierten Sitzungen; Navigation und Aufgabensteuerung passen sich dem verfügbaren Platz an.
- **iOS-16.1-Kompatibilität**: Das Mindestbereitstellungsziel ist jetzt iOS 16.1, mit kompatiblen Oberflächen für ältere Systeme. Systemabhängige Effekte und Funktionen richten sich nach iOS-Version und Gerätefähigkeiten.
- **Verbesserte native Bedienung**: Überarbeitete Milchglasmenüs, Sitzungsumbenennung und Wischnavigation unter Android sowie Wiederverbindung nach Hintergrundbetrieb, Dateizugriff und Bildkompatibilität unter iOS.

### Unterhaltungen und Agent-Ausführung

- **Native Live-Unterhaltungen**: Inkrementelle WebSocket-Ereignisse mit Markdown, Code, Schlussfolgerungen, Werkzeugaufrufen, Ergebnissen und Bildanhängen anzeigen.
- **Sprach- und Texteingabe**: Im eigenen Eingabebereich zum Sprechen gedrückt halten, zum Transkribieren in einen bearbeitbaren Entwurf loslassen oder zum Abbrechen nach oben wischen. Die Transkription nutzt die Spracheingabe des Hosts.
- **Verlauf, Warteschlangen und Befehle**: Seitenweise geladenen Verlauf mit aktuellen Live-Ereignissen zusammenführen und Duplikate entfernen. Nachrichten in der Warteschlange anzeigen und bearbeiten sowie Slash-Befehle des Hosts verwenden. iOS behält beim Laden älterer Nachrichten die Leseposition bei.
- **Agent-Ausführungsverläufe**: User-, Assistant- und Tool-Ereignisse einschließlich Argumenten, Ergebnissen, Schemata, Tokenverbrauch und Laufzeiten prüfen.
- **Interaktive Agent-Abläufe**: Human Questions und Genehmigungsanfragen für Werkzeuge beantworten, absenden, abbrechen, ablehnen oder einmalig genehmigen.

### Hosts, Arbeitsbereiche und Erweiterungen

- **Mehrere Hosts und Sitzungen**: Gateway-Hosts mit getrennten Zugangsdaten speichern und wechseln. Arbeitsbereiche durchsuchen, erstellen und wechseln; Sitzungen suchen, erstellen, wechseln und archivieren.
- **Dateikarten und Vorschau**: Dateikarten direkt aus Unterhaltungen öffnen, entfernte Verzeichnisse durchsuchen und Dateien in überprüften Blöcken herunterladen. PDF, Bilder und Code als Vorschau anzeigen oder mit System-Apps teilen und öffnen.
- **Markdown-Quelltext / Vorschau**: Code mit Syntaxhervorhebung, Zeilennummern und horizontalem Scrollen für lange Zeilen anzeigen. Zwischen Markdown-Quelltext und gerenderter Ansicht wechseln; beide behalten ihre Leseposition.
- **Plugin-Verwaltung**: Offizielle und installierte Plugins, Details und Laufzeitstatus anzeigen. Nach Maßgabe des Hosts installieren, aktivieren, deaktivieren und konfigurieren.
- **Geplante Aufgaben**: In Unterhaltungen erstellte Aufgaben anzeigen, suchen und filtern, Regeln, nächste Ausführung und Zustellinformationen aufklappen sowie Aufgaben bearbeiten oder löschen.
- **Sitzungs- und Standardeinstellungen**: Agent-Voreinstellungen, Anbieter/Modelle, Denkaufwand und Harness-Agent-Berechtigungen `read-only`, `workspace-write` und `danger-full-access` verwalten. Sie gelten für den Agent auf dem Host, nicht für das lokale Dateisystem des Smartphones.

### Native Gerätefunktionen

- **Tablets und Foldables**: Die Darstellung passt sich der Fenstergröße auf iPad, Android-Tablets und Foldables an. Breite Startseiten nutzen Spalten, Unterhaltungen behalten eine angenehme Lesebreite und Dateivorschauen passen sich dem Fenster an. Android berücksichtigt den Scharnierbereich.
- **Design und Textgröße**: Helle und dunkle Darstellung sowie systemgesteuerte oder manuelle Textgröße, mit der Ozean-, Raster- und Walgestaltung von Harness.
- **iOS-Live-Aktivitäten und Mitteilungen**: Fortschritt, Genehmigungen und Abschluss auf dem Sperrbildschirm und unterstützten Dynamic-Island-Geräten verfolgen; wichtige Änderungen per Systemmitteilung erhalten.
- **Sichere Gerätekopplung**: Den WebUI-QR-Code scannen oder Kopplungsdaten manuell eingeben. Dauerhafte Zugangsdaten werden im sicheren Speicher der Plattform abgelegt.
- **Mobiler Lebenszyklus**: Keine App schließt beim Wechsel in den Hintergrund absichtlich eine intakte Verbindung. Nach einer Unterbrechung wird sie bei Rückkehr in den Vordergrund wiederhergestellt; manuelles Trennen löst keine automatische Wiederverbindung aus. Android nutzt einen Vordergrunddienst nur während aktiver Agent-Turns. iOS verwendet die vom System begrenzte Hintergrundlaufzeit und garantiert keine unbegrenzt offene WebSocket-Verbindung.

## Screenshots

> Aktuelle iOS-Produktbilder: Der Bildsatz 1.7.10 stammt vom 22.–23. Oktober 2026, die iPhone-Duo-Bilder vom 26. Oktober 2026. Die Oberfläche ist Chinesisch. [Produktbildquellen](../images/screenshots/2026-10-23/SOURCES.md) · [Duo-Bildquellen](../images/screenshots/2026-10-26-duo/SOURCES.md).

### iPhone Duo: Querformat, Hochformat und Navigation

<table>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-home.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-home.webp" alt="Aufgeklappte Startseite im Querformat" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-home-alt.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-home-alt.webp" alt="Startseitenhintergrund im Querformat" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Aufgeklappte Startseite im Querformat</strong></td>
    <td align="center"><strong>Startseitenhintergrund im Querformat</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-settings.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-settings.webp" alt="Einstellungen im Querformat" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-conversation.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-conversation.webp" alt="Unterhaltung im Querformat" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Einstellungen im Querformat</strong></td>
    <td align="center"><strong>Unterhaltung im Querformat</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-navigation.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-navigation.webp" alt="Geöffnete Navigationsseitenleiste" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/portrait-home.webp"><img src="../images/screenshots/2026-10-26-duo/portrait-home.webp" alt="Aufgeklappte Startseite im Hochformat" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Geöffnete Navigationsseitenleiste</strong></td>
    <td align="center"><strong>Aufgeklappte Startseite im Hochformat</strong></td>
  </tr>
</table>

### Teams, Anhänge und lokaler Speicher

<table>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/team-members.webp"><img src="../images/screenshots/2026-10-23/team-members.webp" alt="Mitglieder des Agent-Teams" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/team-tasks.webp"><img src="../images/screenshots/2026-10-23/team-tasks.webp" alt="Gemeinsame Teamaufgaben" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/attachment-picker.webp"><img src="../images/screenshots/2026-10-23/attachment-picker.webp" alt="Datei- und Bildauswahl" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Mitglieder des Agent-Teams</strong></td>
    <td align="center"><strong>Gemeinsame Teamaufgaben</strong></td>
    <td align="center"><strong>Datei- und Bildauswahl</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/text-selection.webp"><img src="../images/screenshots/2026-10-23/text-selection.webp" alt="Eigene Textauswahlansicht" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/conversation-files.webp"><img src="../images/screenshots/2026-10-23/conversation-files.webp" alt="Dateien in einer Unterhaltung" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/release-notes.webp"><img src="../images/screenshots/2026-10-23/release-notes.webp" alt="Mitgelieferte Versionshinweise" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Eigene Textauswahlansicht</strong></td>
    <td align="center"><strong>Dateien in einer Unterhaltung</strong></td>
    <td align="center"><strong>Mitgelieferte Versionshinweise</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/storage.webp"><img src="../images/screenshots/2026-10-23/storage.webp" alt="Lokaler Speicher und Cache-Kategorien" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/voice-input.webp"><img src="../images/screenshots/2026-10-23/voice-input.webp" alt="Spracheingabe durch Gedrückthalten" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/file-upload.webp"><img src="../images/screenshots/2026-10-23/file-upload.webp" alt="Dateianhang in der Eingabe" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Lokaler Speicher und Cache-Kategorien</strong></td>
    <td align="center"><strong>Spracheingabe durch Gedrückthalten</strong></td>
    <td align="center"><strong>Dateianhang in der Eingabe</strong></td>
  </tr>
</table>

<details>
<summary>Frühere Screenshots: iPad, Vorschauen, Einstellungen und Kopplung</summary>

> Die Screenshots zeigen die iOS-App mit chinesischer Oberfläche; Android implementiert die entsprechende Informationsstruktur und Abläufe mit Jetpack Compose. Die Aufnahmen zu Sprache, Dateien, Textgröße und iPad stammen vom 16. Oktober 2026, Version 1.7.6. Siehe [Bildquellen](../images/screenshots/2026-10/SOURCES.md).

### Spracheingabe, Dateivorschau und Textgröße

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/voice-input.png" alt="Aufnahme-Overlay beim Gedrückthalten auf dem iPhone" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/pdf-preview.png" alt="PDF-Vorschau aus einer Dateikarte der Unterhaltung" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/font-size.png" alt="Vorschau und Schieberegler für die App-Textgröße" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Zum Sprechen gedrückt halten</strong><br>Loslassen zum Transkribieren, hochwischen zum Abbrechen</td>
    <td align="center"><strong>PDF-Vorschau</strong><br>Dateien direkt aus einer Unterhaltung öffnen</td>
    <td align="center"><strong>Textgröße</strong><br>Dem System folgen oder manuell anpassen</td>
  </tr>
</table>

[Abbruchzustand beim Hochwischen anzeigen](../images/screenshots/2026-10/voice-cancel.png)

### iPad und große Bildschirme

<table>
  <tr>
    <td width="50%" align="center"><img src="../images/screenshots/2026-10/ipad-home.png" alt="Zweispaltige iPad-Startseite mit Arbeitsbereichen und letzten Sitzungen" width="100%"></td>
    <td width="50%" align="center"><img src="../images/screenshots/2026-10/ipad-conversation.png" alt="iPad-Unterhaltung im Querformat mit Dateikarte" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Breite Startseite</strong><br>Arbeitsbereiche und Sitzungen nebeneinander</td>
    <td align="center"><strong>Unterhaltungen auf dem Tablet</strong><br>Angenehme Lesebreite und Eingabe</td>
  </tr>
  <tr>
    <td align="center"><img src="../images/screenshots/2026-10/ipad-pdf-preview.png" alt="PDF-Vorschaufenster über einer iPad-Unterhaltung" width="100%"></td>
    <td align="center"><img src="../images/screenshots/2026-10/ipad-voice-input.png" alt="An das iPad-Querformat angepasstes Aufnahme-Overlay" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Dateivorschau</strong><br>Im Kontext der Unterhaltung bleiben</td>
    <td align="center"><strong>Spracheingabe auf dem iPad</strong><br>Aufnahmeanzeige passt sich dem Fenster an</td>
  </tr>
</table>

### Zentrale Abläufe

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/home.png" alt="Arbeitsbereichs-Startseite unter iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/file-card.png" alt="iOS-Unterhaltung mit Dateikarte und Steuerelement für Ausführungsstatistiken" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/trajectory-light.png" alt="Agent-Verlauf im hellen iOS-Design" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Startseite</strong><br>Arbeitsbereiche und letzte Sitzungen</td>
    <td align="center"><strong>Unterhaltung</strong><br>Markdown, Code und Werkzeugergebnisse</td>
    <td align="center"><strong>Agent-Verlauf</strong><br>Laufzeitübersicht und Ereigniszeitleiste</td>
  </tr>
</table>

### Standardeinstellungen

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/settings.png" alt="Einstellungen der iOS-App" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/agent-presets.png" alt="Agent-Voreinstellungen unter iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/default-model.png" alt="Standardmodelleinstellungen unter iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Einstellungen</strong><br>Bereitstellungsstandardwerte und Gateway-Status</td>
    <td align="center"><strong>Agent-Voreinstellungen</strong><br>Werkzeuge, Prompts und Fähigkeiten</td>
    <td align="center"><strong>Standardmodell</strong><br>Anbieter, Modell und Denkaufwand</td>
  </tr>
</table>

### Plugins und geplante Aufgaben

<table>
  <tr>
    <td width="50%" align="center"><img src="../../website/assets/plugins.webp" alt="Liste offizieller und installierter Plugins unter iOS" width="100%"></td>
    <td width="50%" align="center"><img src="../../website/assets/tasks.webp" alt="Regeln und Details geplanter Aufgaben unter iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Plugin-Verwaltung</strong><br>Details und Aktivierungsstatus anzeigen</td>
    <td align="center"><strong>Geplante Aufgaben</strong><br>Zeitpläne und Ausführungsstatus prüfen</td>
  </tr>
</table>

<details>
<summary>Weitere Ansichten: Arbeitsbereiche, Verlaufsdetails und Gerätekopplung</summary>

### Arbeitsbereichsaktionen und Verlaufsdetails

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/workspace-switcher.png" alt="Arbeitsbereichsumschalter unter iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/workspace-directory-picker.png" alt="Verzeichnisauswahl für Arbeitsbereiche unter iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/trajectory-detail-light.png" alt="Detailansicht des Verlaufs unter iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Arbeitsbereich wechseln</strong><br>Auf verschiedene Projekte zugreifen</td>
    <td align="center"><strong>Verzeichnis auswählen</strong><br>Arbeitsbereiche durchsuchen und erstellen</td>
    <td align="center"><strong>Verlaufsdetails</strong><br>Zusammenfassung, Tokens und Inhaltsvorschau</td>
  </tr>
</table>

### Helle und dunkle Darstellung und Kopplung

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/pairing-light.png" alt="Gerätekopplung im hellen iOS-Design" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/pairing-dark.png" alt="Gerätekopplung im dunklen iOS-Design" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/conversation-dark.png" alt="Unterhaltung im dunklen iOS-Design" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Kopplung im hellen Design</strong><br>QR-Code scannen oder Daten eingeben</td>
    <td align="center"><strong>Kopplung im dunklen Design</strong><br>Native dunkle Darstellung</td>
    <td align="center"><strong>Unterhaltung im dunklen Design</strong><br>Lesen, Code und Eingabe</td>
  </tr>
</table>

</details>

</details>

## Plattformübergreifende Architektur

```text
                         DeepSeek Harness
                                │
                  dsh-plugin-mobile-gateway
                                │
                             WebSocket
                ┌───────────────┴───────────────┐
                │                               │
     Android OkHttp Transport          iOS URLSession GatewayClient
                │                               │
       KMP Gateway Runtime             Swift Router / KMP Adapter
                │                               │
                └───────────────┬───────────────┘
                                │
                    KMP shared / commonMain
            Protocol · Store · Reducer · Projection
       History Sync · Question/Approval · Workspace Files
                ┌───────────────┴───────────────┐
                │                               │
       Jetpack Compose                     SwiftUI / UIKit
          Android UI                          iOS UI
```

### Aufgaben des gemeinsamen Moduls

- `protocol/`: Gateway-DTOs, JSON-Darstellungen und Dekodierung der Protokolldaten.
- `gateway/`: Plattform-Transportschnittstellen, Kopplung, Authentifizierung, Anfragenzuordnung, Wiederverbindung und Ereignis-Backpressure. Android verwendet die gesamte gemeinsame Laufzeit, mit OkHttp als zugrunde liegendem Transport.
- `domain/`: Reducer für Sitzungen, Fragen, Genehmigungen und Sitzungssteuerung.
- `projection/`: Inkrementelle Projektionen von Unterhaltungen und Ausführungsverläufen.
- `sync/`: Verlaufspaginierung, Zusammenführung aktueller Live-Ereignisse und Deduplizierung.
- `facade/`: Grobgranulare Stores, Intents und Ereignisse für Swift/Kotlin-Adapter sowie Arbeitsbereichspfade, Blockreihenfolge, Fortschritt und SHA-256-Prüfung.

Das gemeinsame Modul hängt weder von Compose noch von SwiftUI, Android Framework oder Apple-UI-Frameworks ab. iOS nutzt gemeinsame Stores, Reducer, Projektionen und Dateizustandsautomaten. Sein Swift-`GatewayClient` verwaltet weiterhin WebSocket-Verbindungen und Wiederverbindungen und reicht Ereignisse über einen Adapter an das gemeinsame Modul weiter; iOS verwendet noch nicht die gesamte KMP Gateway Runtime.

Jede Plattform übernimmt sichere Speicherung, Bildverarbeitung, temporäre Dateien, Teilen/Öffnen über das System und den App-Lebenszyklus. Das gemeinsame Modul verantwortet das entfernte Dateiprotokoll, Zustandsübergänge, Blockkonsistenz und Integritätsprüfungen, ohne direkt auf Plattform-Dateisysteme zuzugreifen.

## Repository-Struktur

```text
.
├── shared/                         # Kotlin Multiplatform business logic
│   └── src/
│       ├── commonMain/             # Protocol, state machines, projections, sync, facades
│       └── commonTest/             # Cross-platform unit tests
├── androidApp/                     # Native Android app (Jetpack Compose)
│   └── src/
│       ├── main/                   # App code and platform integrations
│       ├── test/                   # JVM tests
│       └── androidTest/            # Device tests
├── DeepSeekHarnessMobile/          # Native iOS app (SwiftUI/UIKit)
├── DeepSeekHarnessMobileTests/     # iOS XCTest tests
├── DeepSeekHarnessMobile.xcodeproj
├── Docs/                           # Research, KMP migration, and validation
└── Design/                         # Design specifications and artwork
```

## Entwicklungsanforderungen

### Gemeinsam

- Java 17.
- DeepSeek Harness mit aktiviertem `dsh-plugin-mobile-gateway` ab Version 0.11.1.
- Ein Simulator, Emulator oder physisches Gerät mit Zugriff auf Mobile Gateway.

### Android

- Android Studio oder Android SDK Command-line Tools.
- Android SDK Platform 36 (`compileSdk` / `targetSdk`).
- Ein Gerät oder Emulator mit Android 7.0 (API 24) oder neuer.

### iOS

- macOS und Xcode 26 oder neuer mit iOS 26 SDK, um APIs wie Liquid Glass zu kompilieren. Das Mindestbereitstellungsziel der App ist iOS 16.1.
- Ein Simulator oder physisches Gerät mit iOS 16.1 oder neuer.
- Android SDK: Die Xcode-Buildphase ruft Gradle zum Kompilieren des KMP-Frameworks auf.
- Eine verfügbare Metal-Toolchain, die von der Buildphase automatisch gefunden wird.

## Android ausführen

1. Java 17 einrichten und `ANDROID_HOME` oder `ANDROID_SDK_ROOT` auf das Android SDK verweisen lassen.
2. Im Repository-Stamm eine Debug-APK erstellen:

   ```bash
   ./gradlew :androidApp:assembleDebug
   ```

3. Auf einem verbundenen Gerät oder Emulator installieren:

   ```bash
   ./gradlew :androidApp:installDebug
   ```

4. `DshMobile` starten und den WebUI-Kopplungs-QR-Code scannen oder die Daten manuell eingeben.

Die Debug-APK wird hier erzeugt:

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Damit ein Android-Emulator auf `127.0.0.1:3080` des Hosts zugreifen kann, zuerst Folgendes ausführen:

```bash
adb reverse tcp:3080 tcp:3080
```

## iOS ausführen

1. Java 17 und das Android SDK einrichten.
2. `DeepSeekHarnessMobile.xcodeproj` in Xcode öffnen.
3. Das Schema `DeepSeekHarnessMobile` und das Zielgerät auswählen.
4. Erstellen und ausführen. Die Phase `Build KMP Framework` wählt das passende Kotlin/Native-Framework automatisch aus und kompiliert es.
5. Den WebUI-Kopplungs-QR-Code in der App scannen oder die Daten manuell eingeben.

Ein vorheriger manueller Framework-Build ist nicht nötig. Um nur das Framework für den iOS-Simulator auf Apple Silicon zu erstellen:

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## Verbindung und Kopplung

1. `dsh-plugin-mobile-gateway` in DeepSeek Harness installieren oder aktualisieren (mindestens 0.11.1), dann `dsh web` vollständig beenden und neu starten:

   ```bash
   dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
   ```

2. In WebUI den Bereich für Mobilgeräte öffnen und mobile Verbindungen sowie Geräteauthentifizierung aktivieren.
3. Sicherstellen, dass der WebSocket-Endpunkt vom Mobilgerät oder Simulator erreichbar ist.
4. Einen einmaligen Kopplungs-QR-Code/Token erzeugen und sich in der Android- oder iOS-App authentifizieren.
5. Zur Startseite zurückkehren, die Gateway-Verbindung prüfen und Arbeitsbereich sowie Sitzung auswählen.

Übliche Endpunkte:

| Umgebung | Endpunkt |
| --- | --- |
| iOS-Simulator | `ws://127.0.0.1:3080/ws/mobile` |
| Android-Emulator mit `adb reverse` | `ws://127.0.0.1:3080/ws/mobile` |
| Physisches Gerät im selben LAN | `ws://<HOST-LAN-IP>:3081/ws/mobile` |
| Öffentliche Bereitstellung | `wss://<your-domain>/ws/mobile` |

> [!IMPORTANT]
> Ein physisches Smartphone erreicht deinen Computer nicht über `127.0.0.1`. Die `ws://`-Beispiele sind nur für vertrauenswürdige lokale Entwicklungsumgebungen gedacht. Verwende in öffentlichen oder nicht vertrauenswürdigen Netzen `wss://` mit korrekt eingerichtetem TLS und Geräteauthentifizierung. Einmalige Kopplungstokens gehören nicht in Protokolle, Issues oder Chatnachrichten.

> [!NOTE]
> Physische Geräte im LAN verwenden den separaten Listener auf Port `3081` von `dsh-plugin-mobile-gateway`. Er verarbeitet nur authentifizierte `/ws/mobile`-Anfragen; andere Pfade liefern 404. Port `3080` gehört zu DSH WebUI und lauscht nur auf Loopback, sodass physische Geräte sich dort nicht verbinden können.

## Build und Tests

Android-Unit-Tests, Lint und APK-Build ausführen:

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug
```

Die gemeinsamen Kotlin/Native-Tests unter macOS auf Apple Silicon ausführen:

```bash
./gradlew :shared:iosSimulatorArm64Test
```

Bei verfügbarem Android-Gerät oder Emulator die Gerätetests ausführen:

```bash
./gradlew :androidApp:connectedDebugAndroidTest
```

Für iOS-Tests in Xcode das Schema `DeepSeekHarnessMobile` und **Product → Test** wählen. Die Buildphase bindet `DeepSeekHarnessShared.framework` für die gewählte Architektur automatisch ein.

## Weiterführende Dokumentation

Einige verlinkte technische Dokumente sind derzeit nur auf Chinesisch verfügbar.

- [Untersuchung der Harness-/WebUI-Funktionen und des Protokolls](../exploration.md)
- [Mobile Designspezifikation](../../Design/design-spec.md)
- [Hinweise zu Drittanbietern](../third-party-notices.md)

Die folgenden Dokumente beschreiben die KMP-Migration und Android-Implementierung. Den aktuellen Stand findest du in diesem README und im Quellcode:

- [KMP-Entwicklungsleitfaden](../kmp-development.md)
- [Android Gateway: Validierung der Phase 12](../kmp-stage12-android-gateway-verification.md)
- [Android UI: Validierung der Phase 13](../kmp-stage13-android-ui-verification.md)

## Projektstatus

Whaleness wird aktiv weiterentwickelt. Das gemeinsame KMP-Modul und die nativen Android- und iOS-Apps sind implementiert. Protokollunterstützung, Plattformkonsistenz und mobiles Nutzungserlebnis entwickeln sich mit DeepSeek Harness und Mobile Gateway weiter.

Dies ist ein unabhängig entwickelter Drittanbieter-Client für DeepSeek Harness.

[![Auf dsh-plugin.org gelistet](https://dsh-plugin.org/badges/listed.svg)](https://dsh-plugin.org/plugins/clarklevis1995/dsh-plugin-mobile-gateway)

### Mitgelieferte Versionshinweise

Die Infoansichten beider Apps verwenden `AppRelease/release-info.json` und `AppRelease/release-notes.md`. Das JSON-Feld `version` muss der App-Version entsprechen; `minimumGatewayVersion` legt die Mindestversion des Mobile-Gateway-Plugins fest. Markdown enthält die Hinweise dieser Version. Die Apps lesen diese mitgelieferten Ressourcen lokal und laden den Text nicht aus dem Netz.

Der KMM-Skill für beide Plattform-Releases schreibt geprüfte Versionshinweise und synchronisiert die Ressourcenversion vor der Kompilierung. Er prüft Ressourcen in APK, AAB und iOS-Bundle gegen die Quelldateien und stellt Ressourcen und Versionsdateien bei fehlgeschlagener Paketierung wieder her. Änderungen der Versionshinweise erfordern einen neuen App-Build; alleinige Änderungen im Ausgabeverzeichnis aktualisieren den eingebetteten Inhalt nicht.

### iOS-Metal-Kompilierung aus Android Studio

Die Phase `Build Metal Library` führt `scripts/build_metal_library.sh` aus, findet eine installierte Metal-Toolchain und schreibt `default.metallib` in das App-Bundle. Sie entfernt die von Android Studio gesetzten Variablen `TOOLCHAINS` und `SDKROOT` nur im Unterprozess und erhält dabei ausdrücklich das aktuelle iOS SDK und Bereitstellungsziel. Das vermeidet irrtümliche Meldungen über fehlende Metal-Tools durch erzwungenes `XcodeDefault`.

### Android-Updatequellen

Android prüft zuerst `https://download.whaleness.cc/latest.json` und greift bei fehlgeschlagener Anfrage oder ungültiger Konfiguration auf GitHub Releases zurück. Die Konfiguration `schemaVersion: 1` enthält `version`, `versionCode`, `packageName`, `downloadUrl`, `size` und `sha256` sowie optional `githubDownloadUrl` und `releaseNotes`. Downloads erlauben nur konfigurierte vertrauenswürdige beschleunigte Domains und GitHub-URLs dieses Projekts. Fällt die beschleunigte Quelle aus, nutzt die App die Ersatzquelle und prüft Größe, SHA-256, Version, Paketname und Signatur anhand derselben Konfiguration.

Bei der Veröffentlichung wird zuerst eine unveränderliche versionierte APK hochgeladen, danach die `latest.json` im Stamm aktualisiert. Update-Manifeste sollten den CDN-Cache umgehen; APK-Dateien dürfen zwischengespeichert werden. Ohne neueren Build gibt es keine GitHub-Anfrage. Prüfungen beim Start bleiben still, manuelle Prüfungen in den Einstellungen zeigen das Ergebnis.

### Automatische R2-Veröffentlichung

`python3 scripts/configure_r2_release.py` speichert S3-Zugangsdaten zum Lesen/Schreiben von Objekten des konfigurierten Buckets in `~/.config/whaleness/r2-release.json` mit Berechtigungen `600`. `sh scripts/r2_release.sh --check` prüft nur die Verbindung. `sh scripts/r2_release.sh --prepare <release-directory>` erzeugt ein Manifest; `--publish <release-directory>` veröffentlicht remote. Das S3 SDK wird isoliert in `build/r2-tools` installiert.

Mit vorhandener lokaler Konfiguration veröffentlicht der KMM-Skill für beide Plattform-Releases automatisch: lokale Pakete prüfen, eine unveränderliche APK mit Buildnummer im Dateinamen hochladen, Remote-Objekt und öffentlichen Download prüfen und dann `latest.json` bedingt aktualisieren. Fehlgeschlagene Uploads lassen sich mit demselben Release-Verzeichnis ohne Neubuild wiederholen. Für rein lokale Paketierung `R2_UPLOAD=0` setzen. Vollständige Uploadprüfungen können R2-Leseanfragen auslösen; die erste Verbindungsprüfung verändert keine Remote-Objekte.
