# 바니팜 WaterdogPE 포크

운영 프록시(proxy-new `/root/cproxy/Waterdog.jar`)를 빌드하는 트리. 로컬 `Documents\_bunny-project\WDPE_STOCK`.

## 리모트

| 이름 | 주소 | 용도 |
|---|---|---|
| `origin` | `bunny-project/waterdogpe` | 이 포크. 푸시는 여기로 |
| `upstream` | `WaterdogPE/WaterdogPE` | 업스트림. 가져오기만 한다(PR 내지 않음) |
| `personal` | `ahnsunggwan45/WaterdogPE` | 옛 개인 포크. 2026-10-26 이전 기록 |

## 브랜치·태그

| 이름 | 내용 |
|---|---|
| `bunny/transfer-queue-100k` | 운영 라인(기본 브랜치). 업스트림 병합과 우리 패치가 여기 쌓인다 |
| `master` | 업스트림 `master` 그대로 |
| 태그 `prod-YYYY-MM-DD` | 그날 운영에 올린 jar 의 커밋 |
| `bunny/eventfix-0909b80` | 2026-10-21 최소 수정판(0909b80 + EventManager 수정). 운영에 쓰지 않음 |
| `backup/*` | 히스토리 정리 전 백업 |

운영 jar 와 브랜치 끝은 다를 수 있다. 지금 운영이 어느 커밋인지는 가장 최근 `prod-*` 태그를 본다.

## 업스트림 반영

```
git fetch upstream
git switch -c bunny/upstream-<해시> bunny/transfer-queue-100k
git merge --no-ff upstream/master
gradlew.bat build          # 테스트 포함
git switch bunny/transfer-queue-100k && git merge --ff-only bunny/upstream-<해시>
git push origin bunny/transfer-queue-100k master
```

jar: `build/libs/Waterdog.jar`. 운영 교체는 프록시 재시작이 필요해 점검 때만 한다.

## 우리 패치(업스트림 위)

- 이동 대기열 한도 8,000 → 100,000 패킷
- RakNet disconnect 시 다운스트림 UDP 소켓 대신 세션 종료
- 리소스팩 청크 요청 범위 검사, 청크 크기 설정(`pack_chunk_size`, 기본 100 KiB)
- 팩 zip 최상위 폴더 하나 안에 든 경우 로드
- 팩 CDN 폴백 로그·단계별 시간
- `network_settings.session_timeout` 을 클라이언트 RakNet 세션에 적용
- EventManager 핸들러 캐시 동시성 수정(ConcurrentHashMap + copy-on-write)
- 다운스트림 로그인을 서버 이동 때마다 새로 서명(첫 접속 때 만든 토큰을 재사용하면 24시간 뒤 이동이 `invalidSession.tooLate` 로 거절됨)
