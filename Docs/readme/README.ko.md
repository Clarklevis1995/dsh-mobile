<div align="center">

[English](../../README.md) · [简体中文](../../README.zh-CN.md) · [日本語](README.ja.md) · **한국어** · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português (Brasil)](README.pt-BR.md)

<img src="../../Design/whale-girl-ios-app-promo-16x9-v3.png" alt="Whaleness iOS 앱 소개" width="100%">

# Whaleness — DeepSeek Harness 서드파티 모바일 클라이언트

**Kotlin Multiplatform으로 핵심 로직을 공유하는 Android 및 iOS 네이티브 앱입니다.**

Mobile Gateway를 통해 DeepSeek Harness에 연결하여 휴대전화, 태블릿, 폴더블 기기에서 작업 공간, 실시간 대화, Agent 실행 기록, 플러그인, 예약 작업, 파일에 접근하세요.

웹사이트: [Whaleness](https://clarklevis1995.github.io/dsh-mobile/)

<p>
  <a href="https://testflight.apple.com/join/1Euwby6r"><img src="../images/badges/testflight.svg" alt="iOS TestFlight 베타 참여" width="180" height="60"></a>
  &nbsp;&nbsp;
  <a href="https://github.com/Clarklevis1995/dsh-mobile/releases"><img src="../images/badges/android-github.svg" alt="GitHub Releases에서 Android APK 다운로드" width="180" height="60"></a>
</p>

![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![iOS 16.1+](https://img.shields.io/badge/iOS-16.1%2B-111827?logo=apple&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
![SwiftUI](https://img.shields.io/badge/iOS-SwiftUI-F05138?logo=swift&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-Realtime-2563EB)

</div>

## 개요

Whaleness는 독립적으로 개발된 DeepSeek Harness 서드파티 네이티브 모바일 클라이언트입니다. Android와 iOS 앱 모두 Kotlin Multiplatform(KMP)을 사용합니다.

- `shared`는 공유 비즈니스 상태의 단일 진실 공급원으로, 프로토콜 처리, 상태 머신, 리듀서, 프로젝션, 동기화를 담당합니다.
- `androidApp`은 Kotlin, Jetpack Compose, OkHttp로 Android 앱을 구현합니다.
- `DeepSeekHarnessMobile`은 SwiftUI/UIKit으로 iOS UI를 구성하고 Kotlin/Native 프레임워크를 통해 공유 로직을 사용합니다.

두 앱은 [`dsh-plugin-mobile-gateway`](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway)를 거쳐 WebSocket으로 Harness에 연결합니다. 네트워크, 보안 저장소, 파일, 이미지, 수명 주기, 백그라운드 실행은 각 플랫폼의 네이티브 API를 사용하며, 비즈니스 규칙은 가능한 한 `shared/commonMain`으로 통합합니다.

## 다운로드 및 사전 요구 사항

- **iOS**: [TestFlight 베타 참여](https://testflight.apple.com/join/1Euwby6r). iOS / iPadOS 16.1 이상을 실행하는 iPhone과 iPad를 지원합니다.
- **Android**: [GitHub Releases에서 APK 다운로드](https://github.com/Clarklevis1995/dsh-mobile/releases). Android 7.0(API 24) 이상이 필요합니다. 앱 내 업데이트 확인은 가속 다운로드 소스를 우선 사용하며 다운로드한 패키지를 검증합니다.
- **데스크톱 호스트**: DeepSeek Harness를 실행하고 **`dsh-plugin-mobile-gateway` 0.11.1 이상**을 설치하여 활성화하세요. 최소 버전은 [`AppRelease/release-info.json`](../../AppRelease/release-info.json)에 정의되어 있습니다.
- **음성 입력**: 호스트의 플러그인 화면에서 공식 음성 입력 플러그인을 활성화하고 모델 다운로드와 초기화를 완료한 후 모바일 기기의 마이크 접근을 허용하세요.

## 베타 커뮤니티

Whaleness WeChat 베타 그룹 2에서 사용 경험을 나누고 문제를 제보하며 지원을 받을 수 있습니다. WeChat으로 스캔하거나 이미지를 저장한 뒤 WeChat에서 인식하세요.

<p align="center">
  <a href="../../website/assets/support-wechat-2026-10-28.png"><img src="../../website/assets/support-wechat-2026-10-28.png" alt="Whaleness 베타 WeChat 그룹 2 QR 코드, 원본에 2026년 11월 4일 이전까지 유효하다고 표시됨" width="320"></a>
</p>

> 2026년 10월 28일에 갱신되었습니다. 원본 이미지에는 2026년 11월 4일 이전까지 유효하다고 명시되어 있습니다. 만료되었다면 [제품 페이지](https://clarklevis1995.github.io/dsh-mobile/)에서 최신 그룹 QR 코드를 확인하세요.

## 플랫폼 현황

| 플랫폼 / 모듈 | 상태 | 주요 구현 |
| --- | --- | --- |
| KMP `shared` | Android와 iOS에서 사용 | Gateway 프로토콜, 스토어/리듀서, 대화 및 실행 기록 프로젝션, 기록 동기화, 질문/승인, 세션 제어, 작업 공간 파일 상태, 코드 미리보기 지원 |
| Android | 네이티브 클라이언트 제공 | Jetpack Compose, OkHttp WebSocket, DataStore(민감하지 않은 설정), Keystore(인증 정보), CameraX/ML Kit QR 스캔, 포그라운드 서비스, 첨부 파일 캐시, 파일 다운로드 및 미리보기 |
| iOS | 네이티브 클라이언트 제공 | SwiftUI/UIKit, URLSession WebSocket, UserDefaults(민감하지 않은 설정), Keychain(인증 정보), QR 페어링, 백그라운드 실행, 첨부 파일 및 작업 공간 파일 |

> Android와 iOS는 각각의 네이티브 UI와 플랫폼 기능을 사용합니다. 기능과 상호작용을 맞춰 가고 있지만 일부 시스템 동작은 플랫폼에 따라 다릅니다.

## 주요 기능

### 최근 추가 및 개선

- **Agent 팀**: 멤버를 확인하고 Lead와 멤버 대화를 전환합니다. 공유 작업의 상태, 담당자, 관련 대화 링크를 확인할 수 있습니다.
- **파일 업로드**: 대화에서 파일을 선택하고 업로드 진행률을 확인하며 첨부를 제거하거나 실패한 업로드를 재시도합니다. 전송 및 생성된 파일은 열거나 다운로드할 수 있는 카드로 표시됩니다.
- **문자 선택과 표 읽기**: 전용 화면에서 선택, 전체 선택, 복사를 수행하고 닫은 뒤에도 대화 읽기 위치를 유지합니다. Markdown 표는 전체 화면, 회전, 복사, CSV 내보내기를 지원합니다. 열이 적은 표는 가용 너비에 맞추고 넓은 표는 가로로 스크롤합니다.
- **대화 기록과 저장 공간**: 대화를 로컬에 캐시하고 자주 쓰는 대화를 오프라인으로 검색합니다. 이미지, 대화 기록, 파일 미리보기, 임시 파일의 사용량을 확인하고 종류별로 로컬 캐시를 정리할 수 있습니다. 대화에 다시 진입하면 기록을 복원하고 이전 메시지를 점진적으로 불러옵니다.
- **iPhone Duo와 넓은 화면**: 펼치기, 접기, 회전에 맞춰 홈의 열 구성, 안전 영역, 입력란, 애니메이션 배경을 조정합니다. 넓은 대화 화면에는 작업 공간별 세션을 묶은 상시 사이드바를 제공하고 탐색 및 작업 컨트롤도 가용 공간에 맞춥니다.
- **iOS 16.1 호환성**: 최소 지원 버전을 iOS 16.1로 확대하고 이전 시스템에 호환 UI를 제공합니다. 시스템에 의존하는 효과와 기능은 iOS 버전 및 기기 성능에 따라 달라집니다.
- **네이티브 상호작용 개선**: Android 반투명 유리 메뉴, 세션 이름 변경, 스와이프 탐색과 iOS 백그라운드 연결 복구, 파일 탐색, 이미지 호환성을 개선했습니다.

### 대화 및 Agent 실행

- **네이티브 실시간 대화**: WebSocket 증분 이벤트를 처리하여 Markdown, 코드, 추론, 도구 호출, 도구 결과, 이미지 첨부를 표시합니다.
- **음성 및 텍스트 입력**: 전용 영역을 길게 눌러 녹음하고 손을 떼면 편집 가능한 초안으로 변환합니다. 위로 밀면 취소됩니다. 음성 변환에는 호스트의 음성 입력 기능을 사용합니다.
- **기록, 대기열, 명령**: 페이지별 기록을 실시간 끝부분과 병합하고 중복 이벤트를 제거합니다. 대기 중인 메시지를 확인·편집하고 호스트가 제공하는 슬래시 명령을 사용합니다. iOS에서는 이전 기록을 불러와도 읽던 위치를 유지합니다.
- **Agent 실행 기록**: User, Assistant, Tool 이벤트와 인수, 결과, 스키마, 토큰 사용량, 소요 시간을 확인합니다.
- **대화형 Agent 흐름**: Human Question 및 도구 승인 요청에 대해 제출, 취소, 거부, 한 번만 허용을 수행합니다.

### 호스트, 작업 공간, 확장 기능

- **여러 호스트와 세션**: 인증 정보를 각각 관리하며 Gateway 호스트를 저장·전환합니다. 작업 공간을 탐색·생성·전환하고 세션을 검색·생성·전환·보관할 수 있습니다.
- **파일 카드와 미리보기**: 대화의 파일 카드를 바로 열고 원격 디렉터리를 탐색하며 검증된 청크 단위로 다운로드합니다. PDF, 이미지, 코드를 미리 보거나 시스템 앱으로 공유하고 열 수 있습니다.
- **Markdown 소스 / 미리보기**: 구문 강조, 줄 번호, 긴 줄의 가로 스크롤을 지원합니다. 소스와 렌더링 보기를 전환하며 각각의 읽기 위치를 유지합니다.
- **플러그인 관리**: 공식 및 설치된 플러그인의 상세 정보와 실행 상태를 확인합니다. 호스트가 지원하는 범위에서 설치, 활성화, 비활성화, 설정을 수행합니다.
- **예약 작업**: 대화에서 생성한 작업을 조회·검색·필터링하고 규칙, 다음 실행 시각, 전달 정보를 펼쳐 보며 작업을 편집하거나 삭제합니다.
- **세션 및 기본 설정**: Agent 프리셋, 제공자/모델, 추론 수준, Harness Agent 권한인 `read-only`, `workspace-write`, `danger-full-access`를 관리합니다. 이는 휴대전화의 로컬 파일 시스템이 아닌 호스트의 Agent를 제어합니다.

### 네이티브 기기 경험

- **태블릿과 폴더블**: iPad, Android 태블릿, 폴더블의 가용 창 크기에 맞춰 레이아웃을 조정합니다. 넓은 홈 화면은 다단 구성을 사용하고 대화는 읽기 좋은 너비를 유지하며 파일 미리보기도 창에 맞춰 조정됩니다. Android는 접이식 힌지 영역을 고려합니다.
- **테마와 글자 크기**: 라이트·다크 테마와 시스템 연동 또는 수동 글자 크기 조절을 지원하며 Harness의 바다, 격자, 고래 시각 요소를 계승합니다.
- **iOS 실시간 현황과 알림**: 잠금 화면과 지원되는 Dynamic Island 기기에서 작업 진행, 승인, 완료를 확인하고 중요한 업데이트는 시스템 알림으로 받습니다.
- **안전한 기기 페어링**: WebUI QR 코드를 스캔하거나 페어링 정보를 직접 입력합니다. 장기 인증 정보는 플랫폼의 보안 저장소에 보관됩니다.
- **모바일 수명 주기 처리**: 두 앱은 백그라운드 진입 시 정상 연결을 의도적으로 닫지 않습니다. 연결이 끊기면 포그라운드 복귀 시 복구하고, 수동 연결 해제 시에는 자동으로 재연결하지 않습니다. Android는 Agent 턴이 실행 중일 때만 포그라운드 서비스를 사용합니다. iOS는 시스템이 허용한 제한된 백그라운드 실행 시간을 사용하며 WebSocket 무기한 유지를 보장하지 않습니다.

## 스크린샷

> 최신 iOS 제품 이미지: 1.7.10 자료는 2026년 10월 22~23일, iPhone Duo 이미지는 2026년 10월 26일 촬영했습니다. UI는 중국어입니다. [제품 이미지 출처](../images/screenshots/2026-10-23/SOURCES.md) · [Duo 이미지 출처](../images/screenshots/2026-10-26-duo/SOURCES.md).

### iPhone Duo: 가로, 세로, 탐색 레이아웃

<table>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-home.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-home.webp" alt="펼친 가로 모드 홈" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-home-alt.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-home-alt.webp" alt="가로 모드 홈 배경" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>펼친 가로 모드 홈</strong></td>
    <td align="center"><strong>가로 모드 홈 배경</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-settings.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-settings.webp" alt="가로 모드 설정" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-conversation.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-conversation.webp" alt="가로 모드 대화" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>가로 모드 설정</strong></td>
    <td align="center"><strong>가로 모드 대화</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-navigation.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-navigation.webp" alt="열린 탐색 사이드바" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/portrait-home.webp"><img src="../images/screenshots/2026-10-26-duo/portrait-home.webp" alt="펼친 세로 모드 홈" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>열린 탐색 사이드바</strong></td>
    <td align="center"><strong>펼친 세로 모드 홈</strong></td>
  </tr>
</table>

### 팀, 첨부 파일, 로컬 저장 공간

<table>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/team-members.webp"><img src="../images/screenshots/2026-10-23/team-members.webp" alt="Agent 팀 멤버" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/team-tasks.webp"><img src="../images/screenshots/2026-10-23/team-tasks.webp" alt="팀 공유 작업" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/attachment-picker.webp"><img src="../images/screenshots/2026-10-23/attachment-picker.webp" alt="파일 및 이미지 선택" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Agent 팀 멤버</strong></td>
    <td align="center"><strong>팀 공유 작업</strong></td>
    <td align="center"><strong>파일 및 이미지 선택</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/text-selection.webp"><img src="../images/screenshots/2026-10-23/text-selection.webp" alt="독립 문자 선택 화면" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/conversation-files.webp"><img src="../images/screenshots/2026-10-23/conversation-files.webp" alt="대화의 파일 카드" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/release-notes.webp"><img src="../images/screenshots/2026-10-23/release-notes.webp" alt="앱에 포함된 릴리스 노트" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>독립 문자 선택 화면</strong></td>
    <td align="center"><strong>대화의 파일 카드</strong></td>
    <td align="center"><strong>앱에 포함된 릴리스 노트</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/storage.webp"><img src="../images/screenshots/2026-10-23/storage.webp" alt="로컬 저장 공간과 캐시 분류" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/voice-input.webp"><img src="../images/screenshots/2026-10-23/voice-input.webp" alt="길게 눌러 음성 입력" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/file-upload.webp"><img src="../images/screenshots/2026-10-23/file-upload.webp" alt="입력란의 파일 첨부" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>로컬 저장 공간과 캐시 분류</strong></td>
    <td align="center"><strong>길게 눌러 음성 입력</strong></td>
    <td align="center"><strong>입력란의 파일 첨부</strong></td>
  </tr>
</table>

<details>
<summary>이전 이미지: iPad, 미리보기, 설정, 페어링</summary>

> 스크린샷은 중국어 UI의 iOS 앱입니다. Android는 Jetpack Compose로 해당 정보 구조와 흐름을 구현합니다. 이 모음의 음성, 파일, 글자 크기, iPad 이미지는 2026년 10월 16일 버전 1.7.6에서 촬영했습니다. [이미지 출처](../images/screenshots/2026-10/SOURCES.md)를 참고하세요.

### 음성 입력, 파일 미리보기, 글자 크기

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/voice-input.png" alt="iPhone에서 길게 눌러 녹음 중인 음성 입력 오버레이" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/pdf-preview.png" alt="대화 파일 카드에서 연 PDF 미리보기" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/font-size.png" alt="앱 글자 크기 미리보기와 슬라이더" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>길게 눌러 말하기</strong><br>놓으면 텍스트 변환, 위로 밀면 취소</td>
    <td align="center"><strong>PDF 미리보기</strong><br>대화에서 바로 파일 열기</td>
    <td align="center"><strong>글자 크기</strong><br>시스템 연동 또는 수동 조절</td>
  </tr>
</table>

[위로 밀어 취소하는 상태 보기](../images/screenshots/2026-10/voice-cancel.png)

### iPad와 큰 화면

<table>
  <tr>
    <td width="50%" align="center"><img src="../images/screenshots/2026-10/ipad-home.png" alt="작업 공간과 최근 세션을 두 열로 표시하는 iPad 홈" width="100%"></td>
    <td width="50%" align="center"><img src="../images/screenshots/2026-10/ipad-conversation.png" alt="파일 카드가 있는 iPad 가로 모드 대화" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>넓은 홈 화면</strong><br>작업 공간과 세션을 나란히 표시</td>
    <td align="center"><strong>태블릿 대화</strong><br>읽기 편한 본문 너비와 입력 영역</td>
  </tr>
  <tr>
    <td align="center"><img src="../images/screenshots/2026-10/ipad-pdf-preview.png" alt="iPad 대화 위에 표시되는 PDF 미리보기 창" width="100%"></td>
    <td align="center"><img src="../images/screenshots/2026-10/ipad-voice-input.png" alt="iPad 가로 화면에 맞춘 녹음 오버레이" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>파일 미리보기</strong><br>대화 맥락을 유지하며 확인</td>
    <td align="center"><strong>iPad 음성 입력</strong><br>창 크기에 맞춰지는 녹음 표시</td>
  </tr>
</table>

### 핵심 흐름

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/home.png" alt="iOS 작업 공간 홈 화면" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/file-card.png" alt="파일 카드와 실행 통계 컨트롤이 있는 iOS 대화" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/trajectory-light.png" alt="iOS 라이트 모드의 Agent 실행 기록" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>홈</strong><br>작업 공간과 최근 세션</td>
    <td align="center"><strong>대화</strong><br>Markdown, 코드, 도구 결과</td>
    <td align="center"><strong>Agent 실행 기록</strong><br>소요 시간 개요와 이벤트 타임라인</td>
  </tr>
</table>

### 기본 설정

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/settings.png" alt="iOS 앱 설정" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/agent-presets.png" alt="iOS Agent 프리셋" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/default-model.png" alt="iOS 기본 모델 설정" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>설정</strong><br>배포 기본값과 Gateway 상태</td>
    <td align="center"><strong>Agent 프리셋</strong><br>도구, 프롬프트, 기능</td>
    <td align="center"><strong>기본 모델</strong><br>제공자, 모델, 추론 수준</td>
  </tr>
</table>

### 플러그인과 예약 작업

<table>
  <tr>
    <td width="50%" align="center"><img src="../../website/assets/plugins.webp" alt="iOS 공식 및 설치된 플러그인 목록" width="100%"></td>
    <td width="50%" align="center"><img src="../../website/assets/tasks.webp" alt="iOS 예약 작업의 규칙과 상세 정보" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>플러그인 관리</strong><br>상세 정보와 활성화 상태 확인</td>
    <td align="center"><strong>예약 작업</strong><br>일정과 실행 상태 확인</td>
  </tr>
</table>

<details>
<summary>더 많은 화면: 작업 공간, 실행 기록 상세 정보, 기기 페어링</summary>

### 작업 공간 동작과 실행 기록 상세 정보

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/workspace-switcher.png" alt="iOS 작업 공간 전환기" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/workspace-directory-picker.png" alt="iOS 작업 공간 디렉터리 선택기" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/trajectory-detail-light.png" alt="iOS 실행 기록 상세 패널" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>작업 공간 전환</strong><br>다른 프로젝트에 접근</td>
    <td align="center"><strong>디렉터리 선택</strong><br>작업 공간 탐색 및 생성</td>
    <td align="center"><strong>실행 기록 상세 정보</strong><br>요약, 토큰, 내용 미리보기</td>
  </tr>
</table>

### 라이트 모드, 다크 모드, 페어링

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/pairing-light.png" alt="iOS 라이트 모드 기기 페어링" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/pairing-dark.png" alt="iOS 다크 모드 기기 페어링" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/conversation-dark.png" alt="iOS 다크 모드 대화" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>라이트 모드 페어링</strong><br>QR 코드 스캔 또는 정보 직접 입력</td>
    <td align="center"><strong>다크 모드 페어링</strong><br>네이티브 다크 테마</td>
    <td align="center"><strong>다크 모드 대화</strong><br>읽기, 코드, 입력 영역</td>
  </tr>
</table>

</details>

</details>

## 크로스 플랫폼 아키텍처

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

### 공유 모듈의 역할

- `protocol/`: Gateway DTO, JSON 표현, 통신 데이터 디코딩.
- `gateway/`: 플랫폼 전송 인터페이스, 페어링, 인증, 요청 상관관계, 재연결, 이벤트 백프레셔. Android는 전체 공유 런타임을 사용하고 OkHttp가 하위 전송을 제공합니다.
- `domain/`: 세션, 질문, 승인, 세션 제어 리듀서.
- `projection/`: 대화 및 실행 기록의 증분 프로젝션.
- `sync/`: 기록 페이지 처리, 실시간 끝부분 병합, 중복 제거.
- `facade/`: Swift/Kotlin 어댑터용 상위 단위 스토어, 인텐트, 이벤트와 작업 공간 경로, 청크 순서, 진행률, SHA-256 검증.

공유 모듈은 Compose, SwiftUI, Android Framework, Apple UI 프레임워크에 의존하지 않습니다. iOS는 공유 스토어, 리듀서, 프로젝션, 파일 상태 머신을 재사용합니다. Swift의 `GatewayClient`가 여전히 WebSocket 연결과 재연결을 담당하고 어댑터를 통해 공유 모듈로 이벤트를 전달합니다. iOS는 아직 전체 KMP Gateway Runtime을 사용하지 않습니다.

각 플랫폼은 보안 저장소, 이미지 처리, 임시 파일, 시스템 공유/열기, 앱 수명 주기를 담당합니다. 공유 모듈은 플랫폼 파일 시스템에 직접 접근하지 않고 원격 파일 프로토콜, 상태 전이, 청크 일관성, 무결성 검사를 담당합니다.

## 저장소 구조

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

## 개발 요구 사항

### 공통

- Java 17.
- `dsh-plugin-mobile-gateway` 0.11.1 이상이 활성화된 DeepSeek Harness.
- Mobile Gateway에 접근할 수 있는 시뮬레이터, 에뮬레이터 또는 실제 기기.

### Android

- Android Studio 또는 Android SDK 명령줄 도구.
- Android SDK Platform 36(`compileSdk` / `targetSdk`).
- Android 7.0(API 24) 이상의 기기 또는 에뮬레이터.

### iOS

- macOS와 Xcode 26 이상, iOS 26 SDK. Liquid Glass 등의 API 컴파일에 필요하며 앱의 최소 지원 버전은 iOS 16.1입니다.
- iOS 16.1 이상의 시뮬레이터 또는 실제 기기.
- Android SDK: Xcode 빌드 단계에서 Gradle을 호출하여 KMP 프레임워크를 컴파일합니다.
- 사용 가능한 Metal 도구 모음. 빌드 단계에서 자동으로 찾습니다.

## Android 실행

1. Java 17을 설정하고 `ANDROID_HOME` 또는 `ANDROID_SDK_ROOT`를 Android SDK 경로로 지정합니다.
2. 저장소 루트에서 Debug APK를 빌드합니다.

   ```bash
   ./gradlew :androidApp:assembleDebug
   ```

3. 연결된 기기나 에뮬레이터에 설치합니다.

   ```bash
   ./gradlew :androidApp:installDebug
   ```

4. `DshMobile`을 실행하고 WebUI의 페어링 QR 코드를 스캔하거나 페어링 정보를 직접 입력합니다.

Debug APK 생성 위치:

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Android 에뮬레이터에서 호스트의 `127.0.0.1:3080`에 접근하려면 먼저 다음을 실행합니다.

```bash
adb reverse tcp:3080 tcp:3080
```

## iOS 실행

1. Java 17과 Android SDK를 설정합니다.
2. Xcode에서 `DeepSeekHarnessMobile.xcodeproj`를 엽니다.
3. `DeepSeekHarnessMobile` 스킴과 대상 기기를 선택합니다.
4. 빌드하여 실행합니다. `Build KMP Framework` 단계가 적합한 Kotlin/Native 프레임워크를 자동 선택하고 컴파일합니다.
5. 앱에서 WebUI의 페어링 QR 코드를 스캔하거나 정보를 직접 입력합니다.

프레임워크를 미리 수동 빌드할 필요는 없습니다. Apple Silicon iOS 시뮬레이터용 프레임워크만 빌드하려면:

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## 연결 및 페어링

1. DeepSeek Harness에서 `dsh-plugin-mobile-gateway`를 설치 또는 업그레이드하고(최소 0.11.1), `dsh web`을 완전히 종료한 뒤 다시 실행합니다.

   ```bash
   dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
   ```

2. WebUI의 모바일 기기 패널을 열어 모바일 연결과 기기 인증을 활성화합니다.
3. 모바일 기기 또는 시뮬레이터에서 WebSocket 엔드포인트에 접근할 수 있는지 확인합니다.
4. 일회용 페어링 QR 코드/토큰을 생성하고 Android 또는 iOS 앱에서 인증합니다.
5. 홈 화면으로 돌아가 Gateway 연결 상태를 확인한 후 작업 공간과 세션을 선택합니다.

일반적인 엔드포인트:

| 환경 | 엔드포인트 |
| --- | --- |
| iOS 시뮬레이터 | `ws://127.0.0.1:3080/ws/mobile` |
| `adb reverse`를 사용하는 Android 에뮬레이터 | `ws://127.0.0.1:3080/ws/mobile` |
| 같은 LAN의 실제 기기 | `ws://<HOST-LAN-IP>:3081/ws/mobile` |
| 공개 배포 | `wss://<your-domain>/ws/mobile` |

> [!IMPORTANT]
> 실제 휴대전화는 `127.0.0.1`로 컴퓨터에 연결할 수 없습니다. `ws://` 예시는 신뢰할 수 있는 로컬 개발 환경 전용입니다. 공개 네트워크나 신뢰할 수 없는 네트워크에서는 TLS와 기기 인증이 올바르게 설정된 `wss://`를 사용하세요. 일회용 페어링 토큰을 로그, 이슈, 채팅 메시지에 포함하지 마세요.

> [!NOTE]
> LAN의 실제 기기는 `dsh-plugin-mobile-gateway`가 제공하는 별도의 `3081` 포트 리스너를 사용합니다. 인증된 `/ws/mobile` 요청만 처리하고 다른 경로에는 404를 반환합니다. `3080` 포트는 DSH WebUI용이며 루프백에서만 수신하므로 실제 기기에서는 연결할 수 없습니다.

## 빌드 및 테스트

Android 단위 테스트, lint, APK 빌드를 실행합니다.

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug
```

Apple Silicon macOS에서 공유 Kotlin/Native 테스트를 실행합니다.

```bash
./gradlew :shared:iosSimulatorArm64Test
```

Android 기기나 에뮬레이터가 있으면 기기 테스트를 실행합니다.

```bash
./gradlew :androidApp:connectedDebugAndroidTest
```

iOS 테스트는 Xcode에서 `DeepSeekHarnessMobile` 스킴을 선택하고 **Product → Test**를 실행합니다. 빌드 단계가 대상 아키텍처의 `DeepSeekHarnessShared.framework`를 자동 링크합니다.

## 관련 문서

일부 기술 문서는 현재 중국어로만 제공됩니다.

- [Harness / WebUI 기능 및 프로토콜 조사](../exploration.md)
- [모바일 디자인 사양](../../Design/design-spec.md)
- [서드파티 고지](../third-party-notices.md)

다음 문서는 KMP 이전과 Android 구현 기록입니다. 현재 상태는 이 README와 소스 코드를 참고하세요.

- [KMP 개발 가이드](../kmp-development.md)
- [Android Gateway 12단계 검증](../kmp-stage12-android-gateway-verification.md)
- [Android UI 13단계 검증](../kmp-stage13-android-ui-verification.md)

## 프로젝트 현황

Whaleness는 지속적으로 개발 중입니다. KMP 공유 모듈과 Android 및 iOS 네이티브 앱이 구현되어 있으며, 프로토콜 지원, 플랫폼 간 일관성, 모바일 경험은 DeepSeek Harness 및 Mobile Gateway와 함께 개선됩니다.

이 프로젝트는 독립적으로 개발된 DeepSeek Harness 서드파티 클라이언트입니다.

[![dsh-plugin.org에 등록됨](https://dsh-plugin.org/badges/listed.svg)](https://dsh-plugin.org/plugins/clarklevis1995/dsh-plugin-mobile-gateway)

### 앱에 포함된 릴리스 노트

두 앱의 정보 화면은 `AppRelease/release-info.json`과 `AppRelease/release-notes.md`를 사용합니다. JSON의 `version`은 앱 버전과 일치해야 하며 `minimumGatewayVersion`은 Mobile Gateway 플러그인의 최소 버전을 지정합니다. Markdown에는 해당 버전의 릴리스 노트가 들어 있습니다. 앱은 이 리소스를 로컬에서 읽으며 텍스트를 네트워크로 가져오지 않습니다.

KMM 양 플랫폼 릴리스 스킬은 컴파일 전에 검토한 릴리스 노트를 기록하고 리소스 버전을 동기화합니다. APK, AAB, iOS 번들의 리소스가 소스와 일치하는지 확인하고 패키징 실패 시 리소스와 버전 파일을 복원합니다. 릴리스 노트를 바꾸려면 앱을 다시 빌드해야 하며 출력 디렉터리만 수정해도 포함된 내용은 갱신되지 않습니다.

### Android Studio에서 iOS Metal 컴파일

`Build Metal Library` 단계는 `scripts/build_metal_library.sh`를 실행하여 설치된 Metal 도구 모음을 찾고 앱 번들에 `default.metallib`를 기록합니다. 하위 프로세스에서만 Android Studio가 주입한 `TOOLCHAINS`와 `SDKROOT`를 제거하고 현재 iOS SDK와 배포 대상을 명시적으로 유지합니다. 이를 통해 `XcodeDefault` 강제로 발생하는 잘못된 Metal 도구 모음 누락 오류를 방지합니다.

### Android 업데이트 소스

Android는 먼저 `https://download.whaleness.cc/latest.json`을 확인하고 요청 실패 또는 잘못된 설정이 있으면 GitHub Releases로 전환합니다. `schemaVersion: 1` 설정에는 `version`, `versionCode`, `packageName`, `downloadUrl`, `size`, `sha256`이 포함되며 `githubDownloadUrl`과 `releaseNotes`는 선택 사항입니다. 다운로드는 설정된 신뢰 가능한 가속 다운로드 도메인과 이 프로젝트의 GitHub URL만 허용합니다. 가속 소스가 실패하면 대체 소스로 전환하면서 같은 설정을 기준으로 크기, SHA-256, 버전, 패키지 이름, 서명을 검증합니다.

게시할 때는 변경 불가능한 버전별 APK를 먼저 업로드하고 루트 `latest.json`을 갱신합니다. 업데이트 매니페스트는 CDN 캐시를 우회해야 하며 APK는 캐시할 수 있습니다. 새 빌드가 없으면 GitHub 요청을 보내지 않습니다. 시작 시 확인은 조용히 수행하고 설정에서 수동으로 확인하면 결과를 표시합니다.

### R2 자동 게시

`python3 scripts/configure_r2_release.py`를 실행하면 지정한 버킷의 S3 객체 읽기/쓰기 인증 정보를 `~/.config/whaleness/r2-release.json`에 권한 `600`으로 저장합니다. `sh scripts/r2_release.sh --check`는 연결만 확인합니다. `sh scripts/r2_release.sh --prepare <release-directory>`는 매니페스트를 생성하고 `--publish <release-directory>`는 원격 게시를 수행합니다. S3 SDK는 격리된 `build/r2-tools` 환경에 설치됩니다.

로컬 설정이 있으면 KMM 양 플랫폼 릴리스 스킬이 자동 게시합니다. 로컬 패키지를 검증하고 빌드 번호를 파일명에 포함한 변경 불가능한 APK를 업로드한 뒤 원격 객체와 공개 다운로드를 확인하고 조건부로 `latest.json`을 갱신합니다. 업로드 실패 시 재빌드 없이 같은 릴리스 디렉터리로 재시도할 수 있습니다. 로컬 패키징만 하려면 `R2_UPLOAD=0`을 설정하세요. 전체 업로드 검증은 R2 읽기 요청을 발생시킬 수 있지만 최초 연결 확인은 원격 객체를 수정하지 않습니다.
