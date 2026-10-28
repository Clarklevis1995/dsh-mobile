<div align="center">

[English](../../README.md) · [简体中文](../../README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · **Português (Brasil)**

<img src="../../Design/whale-girl-ios-app-promo-16x9-v3.png" alt="Apresentação do aplicativo Whaleness para iOS" width="100%">

# Whaleness — Cliente móvel de terceiros para DeepSeek Harness

**Aplicativos nativos para Android e iOS com lógica principal compartilhada por Kotlin Multiplatform.**

Conecte-se ao DeepSeek Harness pelo Mobile Gateway para acessar espaços de trabalho, conversas em tempo real, rastros do Agent, plugins, tarefas agendadas e arquivos pelo celular, tablet ou dispositivo dobrável.

Site: [Whaleness](https://clarklevis1995.github.io/dsh-mobile/)

<p>
  <a href="https://testflight.apple.com/join/1Euwby6r"><img src="../images/badges/testflight.svg" alt="Participar do beta de iOS no TestFlight" width="180" height="60"></a>
  &nbsp;&nbsp;
  <a href="https://github.com/Clarklevis1995/dsh-mobile/releases"><img src="../images/badges/android-github.svg" alt="Baixar o APK Android pelo GitHub Releases" width="180" height="60"></a>
</p>

![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![iOS 16.1+](https://img.shields.io/badge/iOS-16.1%2B-111827?logo=apple&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
![SwiftUI](https://img.shields.io/badge/iOS-SwiftUI-F05138?logo=swift&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-Realtime-2563EB)

</div>

## Visão geral

Whaleness é um cliente móvel nativo de terceiros para DeepSeek Harness, desenvolvido de forma independente. Os aplicativos Android e iOS usam Kotlin Multiplatform (KMP):

- `shared` é a fonte única de verdade do estado de negócio compartilhado, incluindo protocolos, máquinas de estado, reducers, projeções e sincronização.
- `androidApp` implementa o aplicativo Android com Kotlin, Jetpack Compose e OkHttp.
- `DeepSeekHarnessMobile` constrói a interface iOS com SwiftUI/UIKit e acessa a lógica compartilhada por um framework Kotlin/Native.

Ambos se conectam ao Harness por WebSocket via [`dsh-plugin-mobile-gateway`](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway). Rede, armazenamento seguro, arquivos, imagens, ciclo de vida e execução em segundo plano usam APIs nativas de cada plataforma. As regras de negócio ficam concentradas em `shared/commonMain` sempre que viável.

## Downloads e pré-requisitos

- **iOS**: [Participar do beta no TestFlight](https://testflight.apple.com/join/1Euwby6r). Compatível com iPhone e iPad com iOS / iPadOS 16.1 ou posterior.
- **Android**: [Baixar um APK pelo GitHub Releases](https://github.com/Clarklevis1995/dsh-mobile/releases). Requer Android 7.0 (API 24) ou posterior. A busca de atualizações prioriza a fonte de download acelerada e verifica os pacotes baixados.
- **Host de desktop**: execute o DeepSeek Harness com **`dsh-plugin-mobile-gateway` 0.11.1 ou posterior** instalado e ativado. [`AppRelease/release-info.json`](../../AppRelease/release-info.json) define a versão mínima exigida.
- **Entrada de voz**: ative o plugin oficial de entrada de voz na seção de plugins do host, conclua o download e a inicialização do modelo e permita acesso ao microfone no dispositivo móvel.

## Comunidade beta

Entre no grupo 2 do WeChat do beta do Whaleness para compartilhar experiências, enviar comentários e obter suporte. Escaneie com o WeChat ou salve a imagem e reconheça o código no WeChat.

<p align="center">
  <a href="../../website/assets/support-wechat-2026-10-28.png"><img src="../../website/assets/support-wechat-2026-10-28.png" alt="Código QR do grupo 2 do WeChat do beta do Whaleness, válido antes de 4 de novembro de 2026" width="320"></a>
</p>

> Atualizado em 28 de outubro de 2026. A imagem original informa validade anterior a 4 de novembro de 2026. Se expirou, confira o código mais recente na [página do produto](https://clarklevis1995.github.io/dsh-mobile/).

## Estado das plataformas

| Plataforma / módulo | Estado | Principais componentes |
| --- | --- | --- |
| KMP `shared` | Usado por Android e iOS | Protocolo Gateway, stores/reducers, projeções de conversas e rastros, sincronização do histórico, perguntas/aprovações, controle de sessões, estado de arquivos e prévia de código |
| Android | Cliente nativo disponível | Jetpack Compose, OkHttp WebSocket, DataStore para configurações não sensíveis, Keystore para credenciais, leitura QR com CameraX/ML Kit, serviço em primeiro plano, cache de anexos, downloads e prévias |
| iOS | Cliente nativo disponível | SwiftUI/UIKit, URLSession WebSocket, UserDefaults para configurações não sensíveis, Keychain para credenciais, pareamento QR, execução em segundo plano, anexos e arquivos do espaço de trabalho |

> Android e iOS têm interfaces nativas e integrações próprias. Recursos e interações estão sendo alinhados, mas alguns comportamentos do sistema variam entre plataformas.

## Recursos

### Novidades e melhorias recentes

- **Equipes de Agents**: veja membros, alterne entre conversas do líder e dos membros e acompanhe tarefas compartilhadas com estado, responsável e links para a conversa correspondente.
- **Envio de arquivos**: escolha arquivos na conversa, acompanhe o progresso, remova anexos ou repita envios com falha. Arquivos enviados e gerados aparecem como cartões que podem ser abertos ou baixados.
- **Seleção de texto e tabelas**: use uma tela dedicada para selecionar, selecionar tudo e copiar sem perder a posição na conversa. Abra tabelas Markdown em tela cheia, gire, copie ou exporte CSV; tabelas estreitas se ajustam à largura e as largas permitem rolagem horizontal.
- **Histórico e armazenamento**: mantenha conversas em cache local e pesquise conversas frequentes offline. Veja o espaço usado por imagens, histórico, prévias e arquivos temporários e limpe o cache local por categoria. Restaure o histórico ao voltar à sessão e carregue mensagens antigas gradualmente.
- **iPhone Duo e telas largas**: colunas do início, áreas seguras, editor e fundo animado se adaptam ao dobrar, desdobrar e girar. Conversas largas oferecem barra lateral permanente com sessões agrupadas por espaço de trabalho; navegação e controles de tarefas se ajustam ao espaço disponível.
- **Compatibilidade com iOS 16.1**: a versão mínima agora é iOS 16.1, com interfaces compatíveis para sistemas antigos. Efeitos e recursos dependentes do sistema variam conforme a versão do iOS e as capacidades do aparelho.
- **Interações nativas refinadas**: melhorias nos menus de vidro fosco, renomeação de sessões e navegação por gestos no Android, além de reconexão após segundo plano, navegação de arquivos e compatibilidade de imagens no iOS.

### Conversas e execução do Agent

- **Conversas nativas em tempo real**: processa eventos incrementais por WebSocket com Markdown, código, raciocínio, chamadas de ferramentas, resultados e imagens anexadas.
- **Entrada de voz e texto**: pressione e segure a área dedicada para falar; solte para transcrever em um rascunho editável ou deslize para cima para cancelar. A transcrição usa a capacidade de entrada de voz do host.
- **Histórico, filas e comandos**: combina o histórico paginado com os eventos recentes em tempo real e elimina duplicatas. Permite visualizar e editar mensagens na fila e usar comandos de barra do host. No iOS, carregar mensagens antigas mantém a posição de leitura.
- **Rastros do Agent**: inspecione eventos User, Assistant e Tool, com argumentos, resultados, esquemas, consumo de tokens e tempos.
- **Fluxos interativos do Agent**: responda a Human Questions e pedidos de aprovação de ferramentas, com envio, cancelamento, recusa e autorização única.

### Hosts, espaços de trabalho e extensões

- **Vários hosts e sessões**: salve e alterne hosts Gateway com credenciais separadas. Navegue, crie e alterne espaços de trabalho; pesquise, crie, alterne e arquive sessões.
- **Cartões de arquivo e prévias**: abra cartões diretamente nas conversas, navegue por diretórios remotos e baixe arquivos em blocos verificados. Visualize PDF, imagens e código ou compartilhe e abra arquivos com aplicativos do sistema.
- **Fonte / prévia de Markdown**: navegue pelo código com realce de sintaxe, números de linha e rolagem horizontal em linhas longas. Alterne entre fonte e renderização do Markdown mantendo a posição de leitura de cada visualização.
- **Gerenciamento de plugins**: consulte plugins oficiais e instalados, detalhes e estado de execução. Instale, ative, desative e configure conforme as capacidades do host.
- **Tarefas agendadas**: consulte, pesquise e filtre tarefas criadas em conversas, expanda regras, próxima execução e informações de entrega, além de editar ou excluir tarefas.
- **Configurações de sessão e padrões**: gerencie predefinições do Agent, provedores/modelos, nível de raciocínio e permissões do Harness Agent: `read-only`, `workspace-write` e `danger-full-access`. Elas controlam o Agent no host, não o sistema de arquivos local do celular.

### Experiência nativa nos dispositivos

- **Tablets e dobráveis**: o layout se adapta ao tamanho da janela em iPad, tablets Android e dobráveis. A tela inicial ampla usa colunas, as conversas mantêm uma largura de leitura confortável e as prévias se ajustam à janela. No Android, o layout considera a região da dobradiça.
- **Temas e tamanho do texto**: modos claro e escuro, tamanho do texto conforme o sistema ou ajustado manualmente, com a identidade visual de oceano, grade e baleia do Harness.
- **Atividades ao Vivo e notificações no iOS**: acompanhe progresso, aprovações e conclusão na tela bloqueada e em dispositivos com Dynamic Island compatível, com notificações do sistema para atualizações importantes.
- **Pareamento seguro**: escaneie o QR do WebUI ou informe os dados manualmente. Credenciais persistentes são guardadas no armazenamento seguro da plataforma.
- **Ciclo de vida móvel**: nenhum aplicativo fecha intencionalmente uma conexão saudável ao entrar em segundo plano. Após uma desconexão, a conexão é recuperada ao voltar ao primeiro plano; desconexões manuais não acionam reconexão automática. O Android usa serviço em primeiro plano apenas durante turnos ativos do Agent. O iOS usa o tempo limitado de execução em segundo plano concedido pelo sistema e não garante um WebSocket aberto indefinidamente.

## Capturas de tela

> Capturas recentes de iOS: o conjunto 1.7.10 foi capturado em 22–23 de outubro de 2026; o conjunto iPhone Duo, em 26 de outubro de 2026. A interface está em chinês. [Fontes do produto](../images/screenshots/2026-10-23/SOURCES.md) · [Fontes Duo](../images/screenshots/2026-10-26-duo/SOURCES.md).

### iPhone Duo: paisagem, retrato e navegação

<table>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-home.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-home.webp" alt="Início desdobrado em paisagem" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-home-alt.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-home-alt.webp" alt="Fundo do início em paisagem" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Início desdobrado em paisagem</strong></td>
    <td align="center"><strong>Fundo do início em paisagem</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-settings.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-settings.webp" alt="Configurações em paisagem" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-conversation.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-conversation.webp" alt="Conversa em paisagem" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Configurações em paisagem</strong></td>
    <td align="center"><strong>Conversa em paisagem</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-navigation.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-navigation.webp" alt="Barra lateral de navegação aberta" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/portrait-home.webp"><img src="../images/screenshots/2026-10-26-duo/portrait-home.webp" alt="Início desdobrado em retrato" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Barra lateral de navegação aberta</strong></td>
    <td align="center"><strong>Início desdobrado em retrato</strong></td>
  </tr>
</table>

### Equipes, anexos e armazenamento local

<table>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/team-members.webp"><img src="../images/screenshots/2026-10-23/team-members.webp" alt="Membros da equipe de Agents" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/team-tasks.webp"><img src="../images/screenshots/2026-10-23/team-tasks.webp" alt="Tarefas compartilhadas da equipe" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/attachment-picker.webp"><img src="../images/screenshots/2026-10-23/attachment-picker.webp" alt="Seletor de arquivos e imagens" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Membros da equipe de Agents</strong></td>
    <td align="center"><strong>Tarefas compartilhadas da equipe</strong></td>
    <td align="center"><strong>Seletor de arquivos e imagens</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/text-selection.webp"><img src="../images/screenshots/2026-10-23/text-selection.webp" alt="Tela dedicada de seleção de texto" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/conversation-files.webp"><img src="../images/screenshots/2026-10-23/conversation-files.webp" alt="Arquivos em uma conversa" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/release-notes.webp"><img src="../images/screenshots/2026-10-23/release-notes.webp" alt="Notas de versão incluídas" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Tela dedicada de seleção de texto</strong></td>
    <td align="center"><strong>Arquivos em uma conversa</strong></td>
    <td align="center"><strong>Notas de versão incluídas</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/storage.webp"><img src="../images/screenshots/2026-10-23/storage.webp" alt="Armazenamento local e categorias de cache" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/voice-input.webp"><img src="../images/screenshots/2026-10-23/voice-input.webp" alt="Entrada de voz ao segurar" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/file-upload.webp"><img src="../images/screenshots/2026-10-23/file-upload.webp" alt="Arquivo anexado no editor" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Armazenamento local e categorias de cache</strong></td>
    <td align="center"><strong>Entrada de voz ao segurar</strong></td>
    <td align="center"><strong>Arquivo anexado no editor</strong></td>
  </tr>
</table>

<details>
<summary>Capturas anteriores: iPad, prévias, configurações e pareamento</summary>

> As capturas mostram o aplicativo iOS com interface em chinês; o Android implementa a estrutura e os fluxos correspondentes com Jetpack Compose. As capturas de voz, arquivos, tamanho do texto e iPad foram feitas em 16 de outubro de 2026, na versão 1.7.6. Veja as [fontes das capturas](../images/screenshots/2026-10/SOURCES.md).

### Entrada de voz, prévias e tamanho do texto

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/voice-input.png" alt="Sobreposição de gravação ao pressionar e segurar no iPhone" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/pdf-preview.png" alt="Prévia de PDF aberta por um cartão de arquivo da conversa" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/font-size.png" alt="Prévia do tamanho de texto e controle deslizante" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Segurar para falar</strong><br>Soltar para transcrever, deslizar para cima para cancelar</td>
    <td align="center"><strong>Prévia de PDF</strong><br>Abrir arquivos diretamente na conversa</td>
    <td align="center"><strong>Tamanho do texto</strong><br>Seguir o sistema ou ajustar manualmente</td>
  </tr>
</table>

[Ver o estado de cancelamento ao deslizar para cima](../images/screenshots/2026-10/voice-cancel.png)

### iPad e telas grandes

<table>
  <tr>
    <td width="50%" align="center"><img src="../images/screenshots/2026-10/ipad-home.png" alt="Tela inicial do iPad em duas colunas com espaços de trabalho e sessões recentes" width="100%"></td>
    <td width="50%" align="center"><img src="../images/screenshots/2026-10/ipad-conversation.png" alt="Conversa no iPad em paisagem com cartão de arquivo" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Tela inicial ampla</strong><br>Espaços de trabalho e sessões lado a lado</td>
    <td align="center"><strong>Conversas no tablet</strong><br>Largura de leitura e área de escrita confortáveis</td>
  </tr>
  <tr>
    <td align="center"><img src="../images/screenshots/2026-10/ipad-pdf-preview.png" alt="Janela de prévia de PDF sobre uma conversa no iPad" width="100%"></td>
    <td align="center"><img src="../images/screenshots/2026-10/ipad-voice-input.png" alt="Sobreposição de gravação adaptada ao iPad em paisagem" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Prévias de arquivos</strong><br>Consulte sem perder o contexto da conversa</td>
    <td align="center"><strong>Entrada de voz no iPad</strong><br>Indicadores de gravação adaptados à janela</td>
  </tr>
</table>

### Fluxos principais

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/home.png" alt="Tela inicial dos espaços de trabalho no iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/file-card.png" alt="Conversa iOS com cartão de arquivo e controle de estatísticas de execução" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/trajectory-light.png" alt="Rastro do Agent no tema claro do iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Início</strong><br>Espaços de trabalho e sessões recentes</td>
    <td align="center"><strong>Conversa</strong><br>Markdown, código e resultados de ferramentas</td>
    <td align="center"><strong>Rastro do Agent</strong><br>Resumo de tempos e linha do tempo de eventos</td>
  </tr>
</table>

### Configurações padrão

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/settings.png" alt="Configurações do aplicativo iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/agent-presets.png" alt="Predefinições do Agent no iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/default-model.png" alt="Configurações do modelo padrão no iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Configurações</strong><br>Padrões de implantação e estado do Gateway</td>
    <td align="center"><strong>Predefinições do Agent</strong><br>Ferramentas, instruções e capacidades</td>
    <td align="center"><strong>Modelo padrão</strong><br>Provedor, modelo e nível de raciocínio</td>
  </tr>
</table>

### Plugins e tarefas agendadas

<table>
  <tr>
    <td width="50%" align="center"><img src="../../website/assets/plugins.webp" alt="Lista de plugins oficiais e instalados no iOS" width="100%"></td>
    <td width="50%" align="center"><img src="../../website/assets/tasks.webp" alt="Regras e detalhes das tarefas agendadas no iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Gerenciamento de plugins</strong><br>Consultar detalhes e estado de ativação</td>
    <td align="center"><strong>Tarefas agendadas</strong><br>Consultar agendamento e estado de execução</td>
  </tr>
</table>

<details>
<summary>Mais telas: espaços de trabalho, detalhes dos rastros e pareamento</summary>

### Ações do espaço de trabalho e detalhes dos rastros

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/workspace-switcher.png" alt="Seletor de espaços de trabalho no iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/workspace-directory-picker.png" alt="Seletor de diretórios do espaço de trabalho no iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/trajectory-detail-light.png" alt="Painel de detalhes dos rastros no iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Alternar espaços de trabalho</strong><br>Acessar diferentes projetos</td>
    <td align="center"><strong>Escolher um diretório</strong><br>Navegar e criar espaços de trabalho</td>
    <td align="center"><strong>Detalhes do rastro</strong><br>Resumo, tokens e prévia do conteúdo</td>
  </tr>
</table>

### Modos claro e escuro e pareamento

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/pairing-light.png" alt="Pareamento de dispositivo no tema claro do iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/pairing-dark.png" alt="Pareamento de dispositivo no tema escuro do iOS" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/conversation-dark.png" alt="Conversa no tema escuro do iOS" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Pareamento no tema claro</strong><br>Escanear QR ou informar os dados</td>
    <td align="center"><strong>Pareamento no tema escuro</strong><br>Aparência escura nativa</td>
    <td align="center"><strong>Conversa no tema escuro</strong><br>Leitura, código e área de escrita</td>
  </tr>
</table>

</details>

</details>

## Arquitetura multiplataforma

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

### Responsabilidades do módulo compartilhado

- `protocol/`: DTOs do Gateway, representações JSON e decodificação dos dados do protocolo.
- `gateway/`: interfaces de transporte da plataforma, pareamento, autenticação, correlação de solicitações, reconexão e contrapressão de eventos. O Android usa todo o runtime compartilhado, com OkHttp como transporte subjacente.
- `domain/`: reducers para sessões, perguntas, aprovações e controle de sessões.
- `projection/`: projeções incrementais de conversas e rastros.
- `sync/`: paginação do histórico, combinação de eventos recentes em tempo real e deduplicação.
- `facade/`: stores, intenções e eventos de alto nível para adaptadores Swift/Kotlin, além de caminhos do espaço de trabalho, ordem de blocos, progresso e verificação SHA-256.

O módulo compartilhado não depende de Compose, SwiftUI, Android Framework nem dos frameworks de UI da Apple. O iOS reutiliza stores, reducers, projeções e máquinas de estado de arquivos. Seu `GatewayClient` em Swift ainda controla conexões WebSocket e reconexões, encaminhando eventos por um adaptador ao módulo compartilhado; o iOS ainda não usa todo o KMP Gateway Runtime.

Cada plataforma cuida de armazenamento seguro, imagens, arquivos temporários, compartilhamento/abertura do sistema e ciclo de vida. O módulo compartilhado controla o protocolo de arquivos remotos, transições de estado, consistência de blocos e verificações de integridade, sem acessar diretamente os sistemas de arquivos das plataformas.

## Estrutura do repositório

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

## Requisitos de desenvolvimento

### Comuns

- Java 17.
- DeepSeek Harness com `dsh-plugin-mobile-gateway` 0.11.1 ou posterior ativado.
- Um simulador, emulador ou dispositivo físico com acesso ao Mobile Gateway.

### Android

- Android Studio ou ferramentas de linha de comando do Android SDK.
- Android SDK Platform 36 (`compileSdk` / `targetSdk`).
- Um dispositivo ou emulador com Android 7.0 (API 24) ou posterior.

### iOS

- macOS e Xcode 26 ou posterior com iOS 26 SDK para compilar APIs como Liquid Glass. O destino mínimo de implantação é iOS 16.1.
- Um simulador ou dispositivo físico com iOS 16.1 ou posterior.
- Android SDK: a etapa de compilação do Xcode chama o Gradle para compilar o framework KMP.
- Uma cadeia de ferramentas Metal disponível, localizada automaticamente pela etapa de compilação.

## Executar Android

1. Configure Java 17 e aponte `ANDROID_HOME` ou `ANDROID_SDK_ROOT` para o Android SDK.
2. Compile um APK Debug na raiz do repositório:

   ```bash
   ./gradlew :androidApp:assembleDebug
   ```

3. Instale em um dispositivo conectado ou emulador:

   ```bash
   ./gradlew :androidApp:installDebug
   ```

4. Abra `DshMobile` e escaneie o QR de pareamento do WebUI ou informe os dados manualmente.

O APK Debug é gerado em:

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Para permitir que um emulador Android acesse `127.0.0.1:3080` do host, execute primeiro:

```bash
adb reverse tcp:3080 tcp:3080
```

## Executar iOS

1. Configure Java 17 e o Android SDK.
2. Abra `DeepSeekHarnessMobile.xcodeproj` no Xcode.
3. Selecione o esquema `DeepSeekHarnessMobile` e o dispositivo de destino.
4. Compile e execute. A etapa `Build KMP Framework` seleciona e compila automaticamente o framework Kotlin/Native adequado.
5. Escaneie o QR de pareamento do WebUI no aplicativo ou informe os dados manualmente.

Não é preciso compilar o framework manualmente antes. Para compilar somente o framework do simulador iOS no Apple Silicon:

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## Conexão e pareamento

1. Instale ou atualize `dsh-plugin-mobile-gateway` no DeepSeek Harness (mínimo 0.11.1), encerre completamente `dsh web` e reinicie:

   ```bash
   dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
   ```

2. Abra o painel de dispositivos móveis do WebUI e ative conexões móveis e autenticação de dispositivos.
3. Confirme que o dispositivo móvel ou simulador consegue acessar o endpoint WebSocket.
4. Gere um QR/token de pareamento de uso único e autentique-se no aplicativo Android ou iOS.
5. Volte ao início, confirme a conexão do Gateway e escolha um espaço de trabalho e uma sessão.

Endpoints comuns:

| Ambiente | Endpoint |
| --- | --- |
| Simulador iOS | `ws://127.0.0.1:3080/ws/mobile` |
| Emulador Android com `adb reverse` | `ws://127.0.0.1:3080/ws/mobile` |
| Dispositivo físico na mesma LAN | `ws://<HOST-LAN-IP>:3081/ws/mobile` |
| Implantação pública | `wss://<your-domain>/ws/mobile` |

> [!IMPORTANT]
> Um celular físico não consegue acessar o computador por `127.0.0.1`. Os exemplos `ws://` são apenas para desenvolvimento local confiável. Em redes públicas ou não confiáveis, use `wss://` com TLS e autenticação de dispositivos corretamente configurados. Não inclua tokens de pareamento de uso único em logs, issues ou mensagens.

> [!NOTE]
> Dispositivos físicos na LAN usam o listener separado da porta `3081` fornecido por `dsh-plugin-mobile-gateway`. Ele atende apenas solicitações autenticadas a `/ws/mobile`; outras rotas retornam 404. A porta `3080` pertence ao DSH WebUI e escuta apenas em loopback, portanto dispositivos físicos não conseguem se conectar por ela.

## Compilação e testes

Execute os testes unitários Android, lint e a compilação do APK:

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug
```

Execute os testes compartilhados de Kotlin/Native no macOS com Apple Silicon:

```bash
./gradlew :shared:iosSimulatorArm64Test
```

Com um dispositivo ou emulador Android disponível, execute os testes no dispositivo:

```bash
./gradlew :androidApp:connectedDebugAndroidTest
```

Para testes iOS, selecione o esquema `DeepSeekHarnessMobile` no Xcode e **Product → Test**. A etapa de compilação vincula automaticamente `DeepSeekHarnessShared.framework` para a arquitetura escolhida.

## Documentação relacionada

Alguns documentos técnicos vinculados estão disponíveis apenas em chinês no momento.

- [Pesquisa de recursos e protocolo do Harness / WebUI](../exploration.md)
- [Especificação de design móvel](../../Design/design-spec.md)
- [Avisos de terceiros](../third-party-notices.md)

Os documentos a seguir registram a migração KMP e a implementação Android. Para o estado atual, consulte este README e o código-fonte:

- [Guia de desenvolvimento KMP](../kmp-development.md)
- [Validação Android Gateway, etapa 12](../kmp-stage12-android-gateway-verification.md)
- [Validação Android UI, etapa 13](../kmp-stage13-android-ui-verification.md)

## Estado do projeto

Whaleness está em desenvolvimento ativo. O módulo compartilhado KMP e os aplicativos nativos Android e iOS estão implementados. O suporte ao protocolo, a consistência entre plataformas e a experiência móvel continuam evoluindo com DeepSeek Harness e Mobile Gateway.

Este é um cliente de terceiros para DeepSeek Harness, desenvolvido de forma independente.

[![Listado no dsh-plugin.org](https://dsh-plugin.org/badges/listed.svg)](https://dsh-plugin.org/plugins/clarklevis1995/dsh-plugin-mobile-gateway)

### Notas de versão incluídas

As telas de informações dos dois aplicativos usam `AppRelease/release-info.json` e `AppRelease/release-notes.md`. O campo JSON `version` deve corresponder à versão do aplicativo; `minimumGatewayVersion` especifica a versão mínima do plugin Mobile Gateway. O Markdown contém as notas daquela versão. Os aplicativos carregam esses recursos localmente, sem buscar o texto na rede.

A skill de lançamento duplo KMM grava as notas revisadas e sincroniza a versão dos recursos antes da compilação. Ela verifica se os recursos no APK, AAB e pacote iOS correspondem aos arquivos-fonte e restaura recursos e arquivos de versão caso o empacotamento falhe. Alterar as notas exige recompilar os aplicativos; editar apenas o diretório de saída não atualiza o conteúdo incluído.

### Compilação de Metal para iOS pelo Android Studio

A etapa `Build Metal Library` executa `scripts/build_metal_library.sh`, localiza uma cadeia de ferramentas Metal instalada e grava `default.metallib` no pacote do aplicativo. Ela remove `TOOLCHAINS` e `SDKROOT` injetados pelo Android Studio apenas no subprocesso, preservando explicitamente o SDK iOS e o destino de implantação atuais. Isso evita erros falsos de ferramentas Metal ausentes causados por forçar `XcodeDefault`.

### Fontes de atualização do Android

O Android consulta primeiro `https://download.whaleness.cc/latest.json` e recorre ao GitHub Releases se a solicitação falhar ou a configuração for inválida. A configuração `schemaVersion: 1` inclui `version`, `versionCode`, `packageName`, `downloadUrl`, `size` e `sha256`, com `githubDownloadUrl` e `releaseNotes` opcionais. Os downloads aceitam somente domínios acelerados confiáveis configurados e URLs GitHub deste projeto. Se a fonte acelerada falhar, o aplicativo muda para a alternativa e verifica tamanho, SHA-256, versão, nome do pacote e assinatura usando a mesma configuração.

A publicação envia primeiro um APK versionado imutável e depois atualiza o `latest.json` da raiz. Manifestos de atualização devem evitar o cache CDN; APKs podem ser armazenados em cache. Se não houver uma compilação mais recente, nenhuma solicitação ao GitHub é feita. A verificação na inicialização é silenciosa; verificações manuais em Configurações exibem o resultado.

### Publicação automática no R2

Execute `python3 scripts/configure_r2_release.py` para salvar credenciais de leitura/gravação de objetos S3 do bucket configurado em `~/.config/whaleness/r2-release.json`, com permissões `600`. `sh scripts/r2_release.sh --check` verifica apenas a conexão. `sh scripts/r2_release.sh --prepare <release-directory>` gera um manifesto; `--publish <release-directory>` faz a publicação remota. O SDK S3 é instalado no ambiente isolado `build/r2-tools`.

Quando há configuração local, a skill de lançamento duplo KMM publica automaticamente: valida pacotes locais, envia um APK imutável com o número de compilação no nome, verifica o objeto remoto e o download público e atualiza `latest.json` de forma condicional. Uma falha no envio pode ser repetida com o mesmo diretório sem recompilar. Defina `R2_UPLOAD=0` para empacotar apenas localmente. A verificação completa pode gerar leituras no R2; a verificação inicial de conexão não modifica objetos remotos.
