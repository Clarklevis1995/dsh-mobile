<div align="center">

[English](../../README.md) · [简体中文](../../README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · **Español** · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português (Brasil)](README.pt-BR.md)

<img src="../../Design/whale-girl-ios-app-promo-16x9-v3.png" alt="Presentación de la aplicación Whaleness para iOS" width="100%">

# Whaleness — Cliente móvil de terceros para DeepSeek Harness

**Aplicaciones nativas para Android e iOS con lógica principal compartida mediante Kotlin Multiplatform.**

Conéctate a DeepSeek Harness mediante Mobile Gateway para acceder a espacios de trabajo, conversaciones en tiempo real, trazas del Agent, plugins, tareas programadas y archivos desde tu teléfono, tableta o dispositivo plegable.

Sitio web: [Whaleness](https://clarklevis1995.github.io/dsh-mobile/)

<p>
  <a href="https://testflight.apple.com/join/1Euwby6r"><img src="../images/badges/testflight.svg" alt="Participar en la beta de iOS en TestFlight" width="180" height="60"></a>
  &nbsp;&nbsp;
  <a href="https://github.com/Clarklevis1995/dsh-mobile/releases"><img src="../images/badges/android-github.svg" alt="Descargar el APK de Android desde GitHub Releases" width="180" height="60"></a>
</p>

![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![iOS 16.1+](https://img.shields.io/badge/iOS-16.1%2B-111827?logo=apple&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
![SwiftUI](https://img.shields.io/badge/iOS-SwiftUI-F05138?logo=swift&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-Realtime-2563EB)

</div>

## Descripción general

Whaleness es un cliente móvil nativo de terceros para DeepSeek Harness, desarrollado de forma independiente. Las aplicaciones de Android e iOS utilizan Kotlin Multiplatform (KMP):

- `shared` es la única fuente de verdad del estado de negocio compartido: protocolos, máquinas de estados, reductores, proyecciones y sincronización.
- `androidApp` implementa la aplicación Android con Kotlin, Jetpack Compose y OkHttp.
- `DeepSeekHarnessMobile` construye la interfaz iOS con SwiftUI/UIKit y accede a la lógica compartida mediante un framework de Kotlin/Native.

Ambas aplicaciones se conectan a Harness por WebSocket a través de [`dsh-plugin-mobile-gateway`](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway). La red, el almacenamiento seguro, los archivos, las imágenes, el ciclo de vida y la ejecución en segundo plano utilizan las API nativas de cada plataforma. Las reglas de negocio se concentran en `shared/commonMain` siempre que resulta práctico.

## Descargas y requisitos previos

- **iOS**: [Participar en la beta de TestFlight](https://testflight.apple.com/join/1Euwby6r). Compatible con iPhone y iPad con iOS / iPadOS 16.1 o posterior.
- **Android**: [Descargar un APK desde GitHub Releases](https://github.com/Clarklevis1995/dsh-mobile/releases). Requiere Android 7.0 (API 24) o posterior. La búsqueda de actualizaciones prioriza la fuente de descarga acelerada y verifica los paquetes descargados.
- **Host de escritorio**: ejecuta DeepSeek Harness con **`dsh-plugin-mobile-gateway` 0.11.1 o posterior** instalado y habilitado. [`AppRelease/release-info.json`](../../AppRelease/release-info.json) define la versión mínima requerida.
- **Entrada de voz**: habilita el plugin oficial de entrada de voz en la sección de plugins del host, completa la descarga e inicialización del modelo y concede acceso al micrófono en el dispositivo móvil.

## Comunidad de la beta

Únete al grupo 2 de WeChat de la beta de Whaleness para compartir experiencias, enviar comentarios y obtener ayuda. Escanea con WeChat o guarda la imagen para reconocerla en WeChat.

<p align="center">
  <a href="../../website/assets/support-wechat-2026-10-28.png"><img src="../../website/assets/support-wechat-2026-10-28.png" alt="Código QR del grupo 2 de WeChat de la beta de Whaleness, válido antes del 4 de noviembre de 2026" width="320"></a>
</p>

> Actualizado el 28 de octubre de 2026. La imagen original indica que el código es válido antes del 4 de noviembre de 2026. Si ha caducado, consulta el código más reciente en la [página del producto](https://clarklevis1995.github.io/dsh-mobile/).

## Estado de las plataformas

| Plataforma / módulo | Estado | Componentes principales |
| --- | --- | --- |
| KMP `shared` | Utilizado por Android e iOS | Protocolo Gateway, stores/reductores, proyecciones de conversación y trazas, sincronización del historial, preguntas/aprobaciones, control de sesiones, estado de archivos del espacio de trabajo y vista previa de código |
| Android | Cliente nativo disponible | Jetpack Compose, OkHttp WebSocket, DataStore para ajustes no sensibles, Keystore para credenciales, escaneo QR con CameraX/ML Kit, servicio en primer plano, caché de adjuntos, descargas y vistas previas |
| iOS | Cliente nativo disponible | SwiftUI/UIKit, URLSession WebSocket, UserDefaults para ajustes no sensibles, Keychain para credenciales, emparejamiento QR, ejecución en segundo plano, adjuntos y archivos del espacio de trabajo |

> Android e iOS tienen sus propias interfaces nativas e integraciones de plataforma. Las funciones e interacciones se están armonizando, aunque algunos comportamientos del sistema difieren entre plataformas.

## Funciones

### Novedades recientes

- **Equipos de Agents**: consulta miembros, alterna entre las conversaciones del líder y los miembros y revisa tareas compartidas con estado, responsable y enlace a la conversación correspondiente.
- **Subida de archivos**: elige archivos en la conversación, sigue el progreso, elimina adjuntos o reintenta subidas fallidas. Los archivos enviados y generados aparecen como tarjetas que puedes abrir o descargar.
- **Selección de texto y tablas**: usa una pantalla específica para seleccionar, seleccionar todo y copiar sin perder la posición de la conversación. Abre tablas Markdown a pantalla completa, gíralas, cópialas o exporta CSV; las tablas estrechas se ajustan al ancho y las anchas permiten desplazamiento horizontal.
- **Historial y almacenamiento**: guarda conversaciones en caché local y busca conversaciones habituales sin conexión. Consulta el espacio ocupado por imágenes, historial, vistas previas y archivos temporales y limpia la caché local por categoría. Restaura el historial al volver a una sesión y carga progresivamente los mensajes antiguos.
- **iPhone Duo y pantallas anchas**: adapta columnas de inicio, áreas seguras, editor y fondo animado al plegar, desplegar y girar. Las conversaciones anchas ofrecen una barra lateral permanente con sesiones agrupadas por espacio de trabajo; navegación y controles de tareas se ajustan al espacio disponible.
- **Compatibilidad con iOS 16.1**: la versión mínima es ahora iOS 16.1, con interfaces compatibles para sistemas antiguos. Los efectos y funciones del sistema dependen de la versión de iOS y del dispositivo.
- **Interacciones nativas mejoradas**: mejoras en menús de cristal esmerilado, cambio de nombre de sesiones y navegación por gestos en Android, así como reconexión tras segundo plano, exploración de archivos y compatibilidad de imágenes en iOS.

### Conversaciones y ejecución del Agent

- **Conversaciones nativas en tiempo real**: procesa eventos incrementales por WebSocket con Markdown, código, razonamiento, llamadas a herramientas, resultados e imágenes adjuntas.
- **Entrada de voz y texto**: mantén pulsada el área específica para hablar; al soltar se transcribe a un borrador editable y al deslizar hacia arriba se cancela. La transcripción utiliza la capacidad de entrada de voz del host.
- **Historial, colas y comandos**: combina el historial paginado con los eventos recientes en tiempo real y elimina duplicados. Permite consultar y editar mensajes en cola y usar los comandos con barra del host. En iOS se mantiene la posición de lectura al cargar mensajes anteriores.
- **Trazas del Agent**: consulta eventos User, Assistant y Tool, incluidos argumentos, resultados, esquemas, consumo de tokens y tiempos.
- **Flujos interactivos del Agent**: responde a Human Questions y solicitudes de aprobación de herramientas mediante envío, cancelación, rechazo o aprobación por una sola vez.

### Hosts, espacios de trabajo y extensiones

- **Varios hosts y sesiones**: guarda y cambia entre hosts Gateway con credenciales independientes. Explora, crea y cambia espacios de trabajo; busca, crea, cambia y archiva sesiones.
- **Tarjetas de archivo y vistas previas**: abre tarjetas directamente desde la conversación, explora directorios remotos y descarga archivos por bloques verificados. Previsualiza PDF, imágenes y código, o comparte y abre archivos con aplicaciones del sistema.
- **Código fuente / vista previa de Markdown**: consulta código con resaltado de sintaxis, números de línea y desplazamiento horizontal para líneas largas. Alterna entre fuente y renderizado de Markdown conservando la posición de lectura de cada vista.
- **Gestión de plugins**: consulta plugins oficiales e instalados, sus detalles y estado de ejecución. Instala, habilita, deshabilita y configura según las capacidades del host.
- **Tareas programadas**: consulta tareas creadas en conversaciones, busca y filtra, despliega reglas, próxima ejecución e información de entrega, y edita o elimina tareas.
- **Ajustes de sesión y predeterminados**: gestiona preajustes del Agent, proveedores/modelos, nivel de razonamiento y permisos de Harness Agent: `read-only`, `workspace-write` y `danger-full-access`. Controlan el Agent del host, no el sistema de archivos local del teléfono.

### Experiencia nativa

- **Tabletas y plegables**: el diseño se adapta al tamaño de ventana disponible en iPad, tabletas Android y plegables. El inicio ancho usa columnas, las conversaciones mantienen un ancho cómodo y las vistas previas se ajustan a la ventana. Android tiene en cuenta el área de la bisagra.
- **Temas y tamaño del texto**: apariencia clara y oscura, tamaño de texto según el sistema o ajustado manualmente, con la identidad visual de océano, cuadrícula y ballena de Harness.
- **Actividades en directo y notificaciones de iOS**: sigue el progreso, las aprobaciones y la finalización desde la pantalla bloqueada o Dynamic Island en dispositivos compatibles, con notificaciones del sistema para novedades importantes.
- **Emparejamiento seguro**: escanea el QR de WebUI o introduce los datos manualmente. Las credenciales persistentes se guardan en el almacenamiento seguro de la plataforma.
- **Ciclo de vida móvil**: ninguna aplicación cierra deliberadamente una conexión sana al pasar a segundo plano. Tras una desconexión, se recupera al volver al primer plano; las desconexiones manuales no activan la reconexión automática. Android usa un servicio en primer plano solo durante turnos activos del Agent. iOS utiliza el tiempo limitado de ejecución en segundo plano que concede el sistema y no garantiza un WebSocket abierto indefinidamente.

## Capturas de pantalla

> Capturas recientes de iOS: las imágenes de 1.7.10 se tomaron el 22–23 de octubre de 2026; las de iPhone Duo, el 26 de octubre de 2026. La interfaz está en chino. [Fuentes del producto](../images/screenshots/2026-10-23/SOURCES.md) · [Fuentes de Duo](../images/screenshots/2026-10-26-duo/SOURCES.md).

### iPhone Duo: orientación y navegación adaptables

<table>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-home.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-home.webp" alt="Inicio desplegado en horizontal" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-home-alt.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-home-alt.webp" alt="Fondo del inicio horizontal" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Inicio desplegado en horizontal</strong></td>
    <td align="center"><strong>Fondo del inicio horizontal</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-settings.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-settings.webp" alt="Ajustes en horizontal" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-conversation.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-conversation.webp" alt="Conversación en horizontal" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Ajustes en horizontal</strong></td>
    <td align="center"><strong>Conversación en horizontal</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-navigation.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-navigation.webp" alt="Barra lateral de navegación abierta" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/portrait-home.webp"><img src="../images/screenshots/2026-10-26-duo/portrait-home.webp" alt="Inicio desplegado en vertical" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Barra lateral de navegación abierta</strong></td>
    <td align="center"><strong>Inicio desplegado en vertical</strong></td>
  </tr>
</table>

### Equipos, adjuntos y almacenamiento local

<table>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/team-members.webp"><img src="../images/screenshots/2026-10-23/team-members.webp" alt="Miembros del equipo de Agents" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/team-tasks.webp"><img src="../images/screenshots/2026-10-23/team-tasks.webp" alt="Tareas compartidas del equipo" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/attachment-picker.webp"><img src="../images/screenshots/2026-10-23/attachment-picker.webp" alt="Selector de archivos e imágenes" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Miembros del equipo de Agents</strong></td>
    <td align="center"><strong>Tareas compartidas del equipo</strong></td>
    <td align="center"><strong>Selector de archivos e imágenes</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/text-selection.webp"><img src="../images/screenshots/2026-10-23/text-selection.webp" alt="Selección de texto independiente" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/conversation-files.webp"><img src="../images/screenshots/2026-10-23/conversation-files.webp" alt="Archivos en una conversación" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/release-notes.webp"><img src="../images/screenshots/2026-10-23/release-notes.webp" alt="Notas de versión incluidas" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Selección de texto independiente</strong></td>
    <td align="center"><strong>Archivos en una conversación</strong></td>
    <td align="center"><strong>Notas de versión incluidas</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/storage.webp"><img src="../images/screenshots/2026-10-23/storage.webp" alt="Almacenamiento local y categorías de caché" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/voice-input.webp"><img src="../images/screenshots/2026-10-23/voice-input.webp" alt="Entrada de voz al mantener pulsado" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/file-upload.webp"><img src="../images/screenshots/2026-10-23/file-upload.webp" alt="Archivo adjunto en el editor" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Almacenamiento local y categorías de caché</strong></td>
    <td align="center"><strong>Entrada de voz al mantener pulsado</strong></td>
    <td align="center"><strong>Archivo adjunto en el editor</strong></td>
  </tr>
</table>

<details>
<summary>Capturas anteriores: iPad, vistas previas, ajustes y emparejamiento</summary>

> Las capturas muestran la aplicación iOS con interfaz en chino; Android implementa la estructura y los flujos correspondientes con Jetpack Compose. Las capturas de voz, archivos, tamaño de texto e iPad se tomaron el 16 de octubre de 2026 con la versión 1.7.6. Consulta las [fuentes de las capturas](../images/screenshots/2026-10/SOURCES.md).

### Entrada de voz, vistas previas y tamaño del texto

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/voice-input.png" alt="Superposición de grabación al mantener pulsado en iPhone" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/pdf-preview.png" alt="Vista previa de PDF abierta desde una tarjeta de archivo de la conversación" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/font-size.png" alt="Vista previa del tamaño de texto y control deslizante" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Mantener pulsado para hablar</strong><br>Soltar para transcribir, deslizar arriba para cancelar</td>
    <td align="center"><strong>Vista previa de PDF</strong><br>Abrir archivos directamente desde una conversación</td>
    <td align="center"><strong>Tamaño del texto</strong><br>Seguir el sistema o ajustar manualmente</td>
  </tr>
</table>

[Ver el estado al deslizar hacia arriba para cancelar](../images/screenshots/2026-10/voice-cancel.png)

### iPad y pantallas grandes

<table>
  <tr>
    <td width="50%" align="center"><img src="../images/screenshots/2026-10/ipad-home.png" alt="Inicio de iPad en dos columnas con espacios de trabajo y sesiones recientes" width="100%"></td>
    <td width="50%" align="center"><img src="../images/screenshots/2026-10/ipad-conversation.png" alt="Conversación en iPad horizontal con tarjeta de archivo" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Inicio en pantalla ancha</strong><br>Espacios de trabajo y sesiones en paralelo</td>
    <td align="center"><strong>Conversaciones en tableta</strong><br>Ancho de lectura y área de escritura cómodos</td>
  </tr>
  <tr>
    <td align="center"><img src="../images/screenshots/2026-10/ipad-pdf-preview.png" alt="Ventana de vista previa de PDF sobre una conversación en iPad" width="100%"></td>
    <td align="center"><img src="../images/screenshots/2026-10/ipad-voice-input.png" alt="Superposición de grabación adaptada al iPad horizontal" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Vistas previas de archivos</strong><br>Consultar archivos sin perder el contexto</td>
    <td align="center"><strong>Entrada de voz en iPad</strong><br>Indicadores de grabación adaptados a la ventana</td>
  </tr>
</table>

### Flujos principales

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/home.png" alt="Inicio de espacios de trabajo en iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/file-card.png" alt="Conversación iOS con tarjeta de archivo y control de estadísticas de ejecución" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/trajectory-light.png" alt="Traza del Agent en iOS con tema claro" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Inicio</strong><br>Espacios de trabajo y sesiones recientes</td>
    <td align="center"><strong>Conversación</strong><br>Markdown, código y resultados de herramientas</td>
    <td align="center"><strong>Traza del Agent</strong><br>Resumen de tiempos y cronología de eventos</td>
  </tr>
</table>

### Ajustes predeterminados

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/settings.png" alt="Ajustes de la aplicación iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/agent-presets.png" alt="Preajustes del Agent en iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/default-model.png" alt="Ajustes del modelo predeterminado en iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Ajustes</strong><br>Valores de despliegue y estado de Gateway</td>
    <td align="center"><strong>Preajustes del Agent</strong><br>Herramientas, instrucciones y capacidades</td>
    <td align="center"><strong>Modelo predeterminado</strong><br>Proveedor, modelo y nivel de razonamiento</td>
  </tr>
</table>

### Plugins y tareas programadas

<table>
  <tr>
    <td width="50%" align="center"><img src="../../website/assets/plugins.webp" alt="Lista de plugins oficiales e instalados en iOS" width="100%"></td>
    <td width="50%" align="center"><img src="../../website/assets/tasks.webp" alt="Reglas y detalles de tareas programadas en iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Gestión de plugins</strong><br>Consultar detalles y estado de habilitación</td>
    <td align="center"><strong>Tareas programadas</strong><br>Consultar horarios y estado de ejecución</td>
  </tr>
</table>

<details>
<summary>Más pantallas: espacios de trabajo, detalles de trazas y emparejamiento</summary>

### Acciones del espacio de trabajo y detalles de trazas

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/workspace-switcher.png" alt="Selector de espacios de trabajo en iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/workspace-directory-picker.png" alt="Selector de directorios del espacio de trabajo en iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/trajectory-detail-light.png" alt="Panel de detalles de trazas en iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Cambiar de espacio de trabajo</strong><br>Acceder a distintos proyectos</td>
    <td align="center"><strong>Elegir un directorio</strong><br>Explorar y crear espacios de trabajo</td>
    <td align="center"><strong>Detalles de la traza</strong><br>Resumen, tokens y vista previa del contenido</td>
  </tr>
</table>

### Temas claro y oscuro y emparejamiento

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/pairing-light.png" alt="Emparejamiento de dispositivos en iOS con tema claro" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/pairing-dark.png" alt="Emparejamiento de dispositivos en iOS con tema oscuro" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/conversation-dark.png" alt="Conversación en iOS con tema oscuro" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Emparejamiento con tema claro</strong><br>Escanear un QR o introducir los datos</td>
    <td align="center"><strong>Emparejamiento con tema oscuro</strong><br>Apariencia oscura nativa</td>
    <td align="center"><strong>Conversación con tema oscuro</strong><br>Lectura, código y área de escritura</td>
  </tr>
</table>

</details>

</details>

## Arquitectura multiplataforma

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

### Responsabilidades del módulo compartido

- `protocol/`: DTO de Gateway, representaciones JSON y decodificación de datos del protocolo.
- `gateway/`: interfaces de transporte de plataforma, emparejamiento, autenticación, correlación de solicitudes, reconexión y contrapresión de eventos. Android usa todo el entorno de ejecución compartido, con OkHttp como transporte subyacente.
- `domain/`: reductores de sesiones, preguntas, aprobaciones y control de sesiones.
- `projection/`: proyecciones incrementales de conversaciones y trazas.
- `sync/`: paginación del historial, combinación de eventos recientes en tiempo real y eliminación de duplicados.
- `facade/`: stores, intenciones y eventos de alto nivel para adaptadores Swift/Kotlin; rutas del espacio de trabajo, orden de bloques, progreso y verificación SHA-256.

El módulo compartido no depende de Compose, SwiftUI, Android Framework ni los frameworks de UI de Apple. iOS reutiliza stores, reductores, proyecciones y máquinas de estados de archivos. Su `GatewayClient` de Swift sigue gestionando las conexiones WebSocket y la reconexión y envía eventos al módulo compartido mediante un adaptador; iOS aún no utiliza todo KMP Gateway Runtime.

Cada plataforma gestiona el almacenamiento seguro, imágenes, archivos temporales, apertura/compartición del sistema y ciclo de vida. El módulo compartido controla el protocolo de archivos remotos, las transiciones de estado, la coherencia de bloques y las comprobaciones de integridad sin acceder directamente al sistema de archivos de la plataforma.

## Estructura del repositorio

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

## Requisitos de desarrollo

### Comunes

- Java 17.
- DeepSeek Harness con `dsh-plugin-mobile-gateway` 0.11.1 o posterior habilitado.
- Un simulador, emulador o dispositivo físico con acceso a Mobile Gateway.

### Android

- Android Studio o las herramientas de línea de comandos de Android SDK.
- Android SDK Platform 36 (`compileSdk` / `targetSdk`).
- Un dispositivo o emulador con Android 7.0 (API 24) o posterior.

### iOS

- macOS y Xcode 26 o posterior con iOS 26 SDK para compilar API como Liquid Glass. La versión mínima de despliegue de la aplicación es iOS 16.1.
- Un simulador o dispositivo físico con iOS 16.1 o posterior.
- Android SDK: la fase de compilación de Xcode invoca Gradle para compilar el framework KMP.
- Una cadena de herramientas Metal disponible, que la fase de compilación detecta automáticamente.

## Ejecutar Android

1. Configura Java 17 y apunta `ANDROID_HOME` o `ANDROID_SDK_ROOT` al Android SDK.
2. Compila un APK Debug desde la raíz del repositorio:

   ```bash
   ./gradlew :androidApp:assembleDebug
   ```

3. Instálalo en un dispositivo conectado o emulador:

   ```bash
   ./gradlew :androidApp:installDebug
   ```

4. Abre `DshMobile` y escanea el QR de emparejamiento de WebUI o introduce los datos manualmente.

El APK Debug se genera en:

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Para que un emulador Android acceda a `127.0.0.1:3080` del host, ejecuta primero:

```bash
adb reverse tcp:3080 tcp:3080
```

## Ejecutar iOS

1. Configura Java 17 y Android SDK.
2. Abre `DeepSeekHarnessMobile.xcodeproj` en Xcode.
3. Selecciona el esquema `DeepSeekHarnessMobile` y el dispositivo de destino.
4. Compila y ejecuta. La fase `Build KMP Framework` selecciona y compila automáticamente el framework Kotlin/Native adecuado.
5. Escanea en la aplicación el QR de emparejamiento de WebUI o introduce los datos manualmente.

No necesitas compilar primero el framework de forma manual. Para compilar únicamente el framework del simulador iOS de Apple Silicon:

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## Conexión y emparejamiento

1. Instala o actualiza `dsh-plugin-mobile-gateway` en DeepSeek Harness (mínimo 0.11.1), cierra por completo `dsh web` y reinícialo:

   ```bash
   dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
   ```

2. Abre el panel de dispositivos móviles de WebUI y habilita las conexiones móviles y la autenticación de dispositivos.
3. Comprueba que el dispositivo móvil o simulador pueda acceder al endpoint WebSocket.
4. Genera un QR/token de emparejamiento de un solo uso y autentícate en la aplicación Android o iOS.
5. Vuelve al inicio, confirma que Gateway está conectado y elige un espacio de trabajo y una sesión.

Endpoints habituales:

| Entorno | Endpoint |
| --- | --- |
| Simulador iOS | `ws://127.0.0.1:3080/ws/mobile` |
| Emulador Android con `adb reverse` | `ws://127.0.0.1:3080/ws/mobile` |
| Dispositivo físico en la misma LAN | `ws://<HOST-LAN-IP>:3081/ws/mobile` |
| Despliegue público | `wss://<your-domain>/ws/mobile` |

> [!IMPORTANT]
> Un teléfono físico no puede acceder al ordenador mediante `127.0.0.1`. Los ejemplos `ws://` son solo para desarrollo local en un entorno de confianza. En redes públicas o no confiables usa `wss://` con TLS y autenticación de dispositivos correctamente configurados. No incluyas tokens de emparejamiento de un solo uso en registros, issues ni mensajes de chat.

> [!NOTE]
> Los dispositivos físicos en LAN utilizan el listener independiente del puerto `3081` de `dsh-plugin-mobile-gateway`. Solo atiende solicitudes autenticadas a `/ws/mobile`; otras rutas devuelven 404. El puerto `3080` pertenece a DSH WebUI y escucha únicamente en loopback, por lo que no admite conexiones desde dispositivos físicos.

## Compilación y pruebas

Ejecuta las pruebas unitarias de Android, lint y la compilación del APK:

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug
```

Ejecuta las pruebas compartidas de Kotlin/Native en macOS con Apple Silicon:

```bash
./gradlew :shared:iosSimulatorArm64Test
```

Con un dispositivo o emulador Android disponible, ejecuta las pruebas en dispositivo:

```bash
./gradlew :androidApp:connectedDebugAndroidTest
```

Para probar iOS, selecciona el esquema `DeepSeekHarnessMobile` en Xcode y **Product → Test**. La fase de compilación enlaza automáticamente `DeepSeekHarnessShared.framework` para la arquitectura seleccionada.

## Documentación relacionada

Algunos documentos técnicos enlazados solo están disponibles en chino por el momento.

- [Investigación de funciones y protocolo de Harness / WebUI](../exploration.md)
- [Especificación del diseño móvil](../../Design/design-spec.md)
- [Avisos de terceros](../third-party-notices.md)

Los siguientes documentos describen la migración a KMP y la implementación Android. Para conocer el estado actual, consulta este README y el código fuente:

- [Guía de desarrollo KMP](../kmp-development.md)
- [Validación de Android Gateway, fase 12](../kmp-stage12-android-gateway-verification.md)
- [Validación de Android UI, fase 13](../kmp-stage13-android-ui-verification.md)

## Estado del proyecto

Whaleness está en desarrollo activo. El módulo compartido KMP y las aplicaciones nativas Android e iOS están implementados. El soporte del protocolo, la coherencia entre plataformas y la experiencia móvil siguen evolucionando junto con DeepSeek Harness y Mobile Gateway.

Este es un cliente de terceros para DeepSeek Harness, desarrollado de forma independiente.

[![Incluido en dsh-plugin.org](https://dsh-plugin.org/badges/listed.svg)](https://dsh-plugin.org/plugins/clarklevis1995/dsh-plugin-mobile-gateway)

### Notas de versión incluidas

Las pantallas de información de ambas aplicaciones utilizan `AppRelease/release-info.json` y `AppRelease/release-notes.md`. El campo JSON `version` debe coincidir con la versión de la aplicación; `minimumGatewayVersion` indica la versión mínima de Mobile Gateway. El Markdown contiene las notas de esa versión. Las aplicaciones leen estos recursos localmente, sin descargar el texto de la red.

La skill de publicación dual KMM escribe las notas revisadas y sincroniza la versión de los recursos antes de compilar. Verifica que los recursos del APK, AAB y paquete iOS coincidan con los archivos fuente y restaura los recursos y archivos de versión si falla el empaquetado. Para cambiar las notas incluidas hay que recompilar las aplicaciones; editar solo el directorio de salida no las actualiza.

### Compilación de Metal para iOS desde Android Studio

La fase `Build Metal Library` ejecuta `scripts/build_metal_library.sh`, localiza una cadena de herramientas Metal instalada y escribe `default.metallib` en el paquete de la aplicación. Elimina `TOOLCHAINS` y `SDKROOT` inyectados por Android Studio solo en el subproceso, conservando explícitamente el SDK iOS y el destino de despliegue actuales. Así evita errores falsos de ausencia de herramientas Metal causados por forzar `XcodeDefault`.

### Fuentes de actualización de Android

Android consulta primero `https://download.whaleness.cc/latest.json` y recurre a GitHub Releases si falla la solicitud o la configuración no es válida. La configuración `schemaVersion: 1` incluye `version`, `versionCode`, `packageName`, `downloadUrl`, `size` y `sha256`, con `githubDownloadUrl` y `releaseNotes` opcionales. Solo se permiten los dominios de descarga acelerada configurados como confiables y las URL GitHub de este proyecto. Si falla la fuente acelerada, cambia a la alternativa y verifica tamaño, SHA-256, versión, nombre de paquete y firma con la misma configuración.

La publicación sube primero un APK versionado e inmutable y después actualiza `latest.json` en la raíz. Los manifiestos de actualización deben evitar la caché CDN; los APK pueden almacenarse en caché. Si no hay una compilación más reciente, no se consulta GitHub. Las comprobaciones al inicio son silenciosas; las manuales desde Ajustes muestran el resultado.

### Publicación automática en R2

Ejecuta `python3 scripts/configure_r2_release.py` para guardar las credenciales de lectura/escritura de objetos S3 del bucket configurado en `~/.config/whaleness/r2-release.json`, con permisos `600`. `sh scripts/r2_release.sh --check` solo comprueba la conectividad. `sh scripts/r2_release.sh --prepare <release-directory>` genera un manifiesto; `--publish <release-directory>` realiza la publicación remota. El SDK S3 se instala en el entorno aislado `build/r2-tools`.

Si existe configuración local, la skill de publicación dual KMM publica automáticamente: valida los paquetes locales, sube un APK inmutable cuyo nombre incluye el número de compilación, verifica el objeto remoto y la descarga pública y actualiza `latest.json` de forma condicional. Si falla la subida, se puede reintentar con el mismo directorio sin recompilar. Establece `R2_UPLOAD=0` para empaquetar solo en local. La verificación completa puede generar solicitudes de lectura en R2; la comprobación inicial de conexión no modifica objetos remotos.
