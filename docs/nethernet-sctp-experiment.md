# NetherNet SCTP 설정과 끊김 진단 로그

2026-10-29. iOS 플레이어가 NetherNet 접속 중 `disconnect.timeout`(libdatachannel `SCTP_TIMEOUT`)으로 자주 끊기는 현상을
조사하기 위한 패치와, 테스트 프록시에서 검증하는 절차. 이 문서의 어떤 단계도 운영 프록시(proxy-new `/root/cproxy`)를
건드리지 않는다. 운영 반영은 별도 점검 때 사용자가 정한다.

## 1. 변경 내용

### 1.1 SCTP 타이머 시스템 프로퍼티

network 라이브러리의 `NetherNetNative` 는 `minRetransmitTimeoutMs=400` 하나만 지정하고, 나머지는 libdatachannel 기본값이다.
RakNet 은 `network_settings.session_timeout`(운영 30초)으로 끊김을 버티지만 NetherNet 에는 같은 완충이 없다.
아래 프로퍼티는 **지정한 것만** 덮어쓴다. 하나도 지정하지 않으면 네이티브 설정을 건드리지 않아 현재 동작과 같다.

| 프로퍼티 | SctpSettings 항목 | 단위 |
|---|---|---|
| `waterdog.nethernetSctpMaxRetransmit` | maxRetransmitAttempts | 회 |
| `waterdog.nethernetSctpHeartbeat` | heartbeatIntervalMs | ms |
| `waterdog.nethernetSctpMinRto` | minRetransmitTimeoutMs (기본 400 유지) | ms |
| `waterdog.nethernetSctpMaxRto` | maxRetransmitTimeoutMs | ms |
| `waterdog.nethernetSctpInitialRto` | initialRetransmitTimeoutMs | ms |

- 양의 정수가 아니면 에러 로그를 남기고 그 항목만 무시한다.
- 적용 시점은 NetherNet 시작(`NetherNetInterface.init`)이다. `NetherNetNative.initialize()` 를 먼저 부른 뒤
  `LibDataChannel.setSctpSettings` 를 부른다. network 라이브러리가 첫 채널 생성 때 자기 설정으로 덮어쓰기 때문에 순서가 바뀌면 무효가 된다.
- 설정은 네이티브 라이브러리 전역이라 프록시→백엔드 NetherNet 연결에도 적용된다.
- 적용되면 시작 로그에 `NetherNet SCTP settings set from system properties: SctpSettings{...}` 가 한 줄 나온다. 이 줄이 없으면 적용되지 않은 것이다.
- 지정하지 않았을 때의 값(libdatachannel master `sctptransport.cpp` `SetSettings` 의 `value_or`, 10-29 확인. 번들된 네이티브 빌드와 같은지는 미검증):

  | 항목 | 기본값 |
  |---|---|
  | maxRetransmitAttempts | 5 (init·assoc·path 세 sysctl 에 같은 값) |
  | heartbeatInterval | 10000 ms |
  | minRetransmitTimeout | 200 ms (이 프록시는 network 라이브러리가 400 으로 지정) |
  | maxRetransmitTimeout | 10000 ms |
  | initialRetransmitTimeout | 1000 ms |

  RTO 가 지수로 늘어나므로 응답이 전혀 없을 때 연관이 포기하기까지는 대략 13~25초 안팎이다(400ms 시작 시 0.4+0.8+1.6+3.2+6.4 ≈ 12.4초에 5번째 재전송 만료).
- 미확인: Java 바인딩이 0 을 「미지정」으로 바꿔 넘기는지(network 라이브러리가 현재 같은 방식으로 0 을 넘기고 있어 동작은 하고 있다).

### 1.2 끊김 진단 로그

`NetherNetDisconnectLogger` 가 NetherNet 업스트림(플레이어↔프록시) 파이프라인 맨 앞에 붙어, 전송 계층이 연결을 끝냈다고 알릴 때 INFO 한 줄을 남긴다.

```
[/1.2.3.4:5678|Steve] <-> NetherNet upstream ended by transport: reason=TIMED_OUT (sctp_timeout) connected=734s server=island1 sinceSwitch=42s sctp[rtt=35ms rto=400ms cwnd=4380 peerRwnd=65535 unacked=3 pending=7 dataTimeouts=2]
```

| reason | 의미 (libdatachannel CloseReason) |
|---|---|
| `TIMED_OUT` (sctp_timeout) | SCTP_TIMEOUT. `disconnect.timeout` 의 원인 |
| `CONNECTION_LOST` (ice_failed) | ICE_FAILED |
| `CLOSED_BY_REMOTE_PEER` (remote_shutdown) | REMOTE_SHUTDOWN. 클라이언트가 정상 종료 |
| `ABORTED_BY_REMOTE_PEER` (remote_abort) | REMOTE_ABORT |
| `TRANSPORT_FAILED` (transport_failed) | TRANSPORT_FAILED |

- `connected`: 채널이 열린 뒤 경과. `sinceSwitch`: 그 플레이어의 활성 다운스트림이 마지막으로 바뀐 뒤 경과(첫 접속 포함, `ProxiedPlayer.getLastServerSwitchMillis`). `server`: 끊길 때의 서브서버.
- 플레이어를 못 찾으면(로그인 전) `server`·`sinceSwitch` 는 빠진다. SCTP 통계를 못 읽으면 `sctp=unavailable`.
- 통계는 피어를 닫기 전에 읽는다. 다만 SCTP 연관이 이미 실패한 뒤에 네이티브가 값을 주는지는 **실기기에서 처음 확인해야 한다**(`unavailable` 이 계속 나오면 통계 방식은 실패한 것이고, 끊김 직전 주기 표집이 필요하다).
- 프록시가 직접 내보낸 연결(킥, 서버 이동 실패 등)은 전송 계층 이벤트가 없어 이 줄이 나오지 않는다. 그 경우는 기존 `-> Upstream has disconnected: ...` 줄만 있다.
- 다운스트림(프록시→백엔드) 연결은 대상이 아니다.

## 2. 빌드·테스트

```bash
cd WDPE_STOCK
./gradlew.bat --offline cleanTest build
```

- 전체 테스트 219개 통과(기존 209 + 신규 10: `NetherNetSctpTuningTest` 5, `NetherNetDisconnectLoggerTest` 5).
- 임시 스모크: 실제 네이티브 라이브러리를 올려 `NetherNetSctpTuning.apply` 를 두 번 불러 예외 없이 적용되는 것을 확인(Windows). 스모크 테스트는 레포에 남기지 않았다.
- jar: `build/libs/Waterdog.jar`. md5 는 커밋 후 아래 6절의 값으로 확인한다.

## 3. 테스트 프록시에 올리기

현재 WSL `bunny-test` 의 `/root/cproxy/Waterdog.jar` 는 0996022 판(md5 `ec141301…`)이다. 이 jar 를 바꾸기 전에 확인할 것:

1. 접속자가 없는지(`wsl -d bunny-test -u root -- bash -lc 'tail -20 /root/cproxy/logs/server.log'` 등). 자동 반영 스케줄러(`BunnyTestSync`)가 같은 환경을 건드리므로 먼저 `bash scripts/testenv/bunny-testenv.sh hold 120`(bf-mx/게임 레포의 `scripts/testenv`).
2. `config.yml` 의 `listener.transport`. 지금 WSL 사본은 `raknet` 이라 **NetherNet 이 꺼져 있다.** NetherNet 을 시험하려면 `both` 로 바꿔야 한다(`pull-config` 를 다시 돌리면 운영 사본으로 되돌아간다).
3. 같은 사본의 `network_settings.session_timeout: 10`(운영은 30). RakNet 비교 실험에서는 값을 같이 기록한다.

교체 (프로퍼티 없이 로그만 보려는 경우):

```bash
# PC(Git Bash). jar 를 WSL 로 복사하고 백업
wsl -d bunny-test -u root -- bash -lc 'cp -a /root/cproxy/Waterdog.jar /root/cproxy/Waterdog.jar.bak-pre-sctp-20261029'
wsl -d bunny-test -u root -- bash -lc 'cp "/mnt/c/Users/User/Documents/_bunny-project/WDPE_STOCK/build/libs/Waterdog.jar" /root/cproxy/Waterdog.jar.new && mv /root/cproxy/Waterdog.jar.new /root/cproxy/Waterdog.jar'
# screen "cproxy" 안 루프가 java 를 1초 뒤 다시 띄운다. 콘솔에 end 를 보낸다
wsl -d bunny-test -u root -- bash -lc '/root/tcmd.sh cproxy end'
```

SCTP 프로퍼티를 줄 때는 루프의 `java` 명령을 바꿔야 하므로 screen 을 다시 만든다.

```bash
wsl -d bunny-test -u root -- bash -lc '
screen -S cproxy -X quit
cd /root/cproxy
screen -dmS cproxy bash -c "cd /root/cproxy && while true; do java -Xmx2G \
  -Dwaterdog.nethernetSctpMaxRetransmit=30 \
  -Dwaterdog.nethernetSctpHeartbeat=5000 \
  -jar Waterdog.jar; echo restart-in-1s; sleep 1; done"
'
```

되돌리기: `Waterdog.jar.bak-pre-sctp-20261029` 를 `Waterdog.jar` 로 되돌리고, screen 을 프로퍼티 없는 원래 명령
(`cd /root/cproxy && while true; do java -Xmx2G -jar Waterdog.jar; echo '1초 뒤 다시 켭니다'; sleep 1; done`)으로 다시 만든다.

시작 후 확인:

```bash
wsl -d bunny-test -u root -- bash -lc 'grep -a -E "NetherNet (SCTP|signaling)|Invalid waterdog" /root/cproxy/logs/server.log | tail'
```

## 4. 접속 경로 (실기기 전에 정해야 할 것)

WSL2 기본(NAT)에서 `bunny-test` 프록시(`<WSL IP>:19132`)는 **이 PC 밖(iPhone)에서 접속할 수 없다.** NetherNet 은 시그널링(TCP)과 미디어(UDP `webrtc_port`)가 따로여서 TCP 만 포트포워딩해서는 안 된다. 선택지:

| 방법 | 장단점 |
|---|---|
| A. proxy-new 의 `cproxy-test`(19133) 에 같은 jar 를 올림 | 공인 주소라 iPhone 이 바로 붙는다(과거 실기기 시험에 쓴 경로). 운영 프록시와 같은 호스트이므로 **사용자 승인이 필요**하다. 운영 `cproxy`(19132)는 건드리지 않는다. `transport`·`webrtc_port` 가 운영과 겹치지 않는지 먼저 확인한다 |
| B. WSL mirrored 네트워크(`.wslconfig` `networkingMode=mirrored`) + 윈도 방화벽 규칙 | 같은 Wi-Fi 의 iPhone 이 PC 주소로 붙는다. `wsl --shutdown` 이 필요해 다른 WSL 배포판이 같이 내려간다 |

실험 기록에는 어느 경로였는지, iPhone 의 회선(같은 Wi-Fi / LTE)을 적는다. 회선이 다르면 결과를 비교하지 않는다.

## 5. 실기기(iPhone) 실험 절차

목적: 앱이 백그라운드에 있는 시간과 SCTP 끊김의 관계, 그리고 SCTP 설정으로 그 한계가 늘어나는지 본다.
iOS 는 홈 화면으로 나가면 곧 앱을 멈추므로 클라이언트가 SCTP 응답·heartbeat 에 답하지 못한다. 서버 쪽 SCTP 가 몇 초 만에 포기하는지가 관찰 대상이다.

### 준비

- 같은 iPhone, 같은 회선, 같은 계정. 접속 방식은 NetherNet 이어야 한다(프록시 로그의 `Upstream has connected` 줄과 `PackPhase` 줄로 전송 방식을 확인한다).
- 대조군 A: SCTP 프로퍼티 없음(로그 줄만 있는 jar). 실험군 B: 프로퍼티 지정. 한 번에 한 항목만 바꾼다(예: B1 `MaxRetransmit=30`, B2 `Heartbeat=5000`, B3 둘 다).
- 한 설정당 전환 시간 10·20·30초를 각각 **3회**. 순서는 섞는다(10→30→20→…). 회차 사이에 서버에서 완전히 나왔다가 다시 접속한다.
- 각 설정 시작 전에 프록시 시작 로그에서 SCTP 줄(`SCTP settings set …` 또는 없음)을 기록한다.

### 한 회차

1. 접속해서 스폰에 가만히 서 있는다. 접속 후 60초 이상 지난 상태로 시작한다(첫 접속 직후 상태와 섞이지 않게). 시각(초 단위)을 적는다.
2. 홈 버튼/제스처로 앱을 나간다. 타이머로 N초(10/20/30)를 잰다. 그 사이 iPhone 을 잠그지 않는다.
3. 앱으로 돌아온다. 결과를 기록한다: 계속 접속 중 / 「연결 끊김」 화면 / 재접속 필요.
4. 프록시 로그에서 해당 닉의 줄을 본다.

```bash
wsl -d bunny-test -u root -- bash -lc 'grep -a -E "<닉>" /root/cproxy/logs/server.log | grep -a -E "NetherNet upstream ended|Upstream has (connected|disconnected)" | tail -6'
```

(방법 A 면 `ssh root@<proxy-new> 'grep -a ... /root/cproxy-test/logs/server.log'` 로 같은 줄을 본다.)

### 서버 이동 직후 변형

가설 (b)(서브서버 이동 직후 대량 전송이 정체한다)를 보려면 위 1 단계 대신 **서버 이동 직후**(5~10초 안)에 홈으로 나가는 회차를 같은 횟수만 더한다. 로그의 `sinceSwitch` 로 이동 후 경과를 확인한다.

### 기록표

| 설정 | N초 | 회차 | 복귀 결과 | 로그 reason | connected | sinceSwitch | rtt/rto | unacked/pending | dataTimeouts |
|---|---|---|---|---|---|---|---|---|---|

### 해석

- 끊긴 회차의 reason 이 `TIMED_OUT` 이고 N 이 작을수록 끊긴다면 SCTP 가 짧은 무응답을 못 버틴다는 뜻(가설 a). 실험군에서 한계 N 이 늘면 프로퍼티가 효과가 있다.
- `unacked`·`pending` 이 크고 `sinceSwitch` 가 작으면 이동 직후 전송 정체(가설 b)를 의심한다.
- `unacked=0 pending=0` 인데 `TIMED_OUT` 이면 heartbeat 무응답으로 포기한 것이다. `Heartbeat` 쪽을 본다.
- `sctp=unavailable` 이 반복되면 끊김 시점의 통계는 얻을 수 없는 것이다. 그 경우 연결 중 주기 표집을 별도 패치로 넣는다.
- `CONNECTION_LOST`(ICE_FAILED)가 나오면 SCTP 가 아니라 ICE 경로 문제다. 이 실험의 범위 밖이므로 따로 기록한다.
- 같은 조건을 RakNet 으로 한 번 더 해 보면(클라이언트가 RakNet 으로 붙는 서버 항목) NetherNet 만의 문제인지 가려진다. 이때 `session_timeout` 값을 기록한다.

## 6. 커밋·jar

커밋 해시와 jar md5 는 이 문서를 넣은 커밋 이후 `git log -1` 과 `md5sum build/libs/Waterdog.jar` 로 확인한다. 같은 소스를 다시 빌드하면 md5 가 달라질 수 있다(빌드 시각 속성).
