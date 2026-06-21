# PGSG Gateway Server

89-49(팔구사구) MSA 기반 P2P 거래 플랫폼(내부 코드네임: PGSG)의 강력한 보안 입구이자 통합 관측성(Observability) 허브 역할을 수행하는 게이트웨이 서버입니다.

## 🌟 핵심 기능 (Core Capabilities)

### 1. 보안 입구 정책 (Entry Gate Security)
- **Reactive WebFlux 기반 설계**: `GlobalFilter` 기반의 비동기 논블로킹 아키텍처를 채택하여 고성능 요청 처리를 보장하며, 모든 라우팅 경로에 대해 보안 검사를 강제합니다.
- **헤더 스푸핑(Spoofing) 원천 차단**: 요청 진입 시점에 외부 유입 헤더(`x-user-*`)를 즉시 제거(Sanitize)하고, 검증된 인증 데이터만 다시 주입하는 선제적 보안 시스템을 갖추고 있습니다.
- **실시간 이중 검증 하이브리드 인증**: 게이트웨이의 **로컬 JWT 서명 검증**과 유저 서비스의 **원격 블랙리스트 확인**을 결합하여 보안성을 극대화했습니다.
- **인증 성능 최적화 (Dual Caching)**: Caffeine Cache를 활용하여 토큰 검증 결과와 파싱된 Claims 정보를 각각 로컬 캐싱(TTL 30s)함으로써 원격 서비스 호출 부하를 획기적으로 낮췄습니다.

### 2. 정밀한 분산 추적 (Distributed Tracing)
- **Trace ID 동기화 아키텍처**: Micrometer Tracing(Brave)이 생성한 표준 ID를 로그 및 요청 헤더(`X-Trace-Id`)와 100% 동기화합니다.
- **전 구간 가시성**: 게이트웨이부터 하위 마이크로서비스까지 하나의 고유 ID(Single Source of Truth)로 모든 실행 로그를 연결하여 복잡한 분산 환경에서의 장애 추적 시간을 단축했습니다.

### 3. 표준화된 장애 및 에러 대응
- **통합 에러 핸들링**: `JwtGatewayFilter` 내에서 발생하는 모든 인증 실패 및 예외 상황에 대해 공통 모듈의 `CommonResponse` 규격에 맞는 정교한 JSON 응답을 반환합니다.
- **비동기 장애 내성 (Resilience)**: `WebClient`에 커넥션 풀 및 Read/Write 타임아웃 설정을 적용하고, 원격 서비스 장애 시 패닉 없이 안전한 에러 응답을 반환(Fail-Safe)하도록 설계되었습니다.

### 4. 시스템 최적화 (System Optimization)
- **의존성 격리 및 경량화**: DB를 사용하지 않는 게이트웨이 특성에 맞춰 JPA/DB 관련 자동 설정을 제외하고, 비동기 기반의 가벼운 실행 컨텍스트를 유지합니다.

### 5. 인프라 자동화 및 관측성 (Infrastructure & Observability)
- **CI/CD 파이프라인**: GitHub Actions를 통해 Docker 이미지 빌드부터 GCP Artifact Registry 푸시, VM 배포까지 일련의 과정이 구축되어 있습니다.
- **안전한 배포 및 롤백**: 배포 후 Actuator 헬스체크 실패 시, 즉시 이전의 안정적인(`stable`) 태그 이미지로 자동 롤백하는 Fail-Safe 로직을 갖추고 있습니다.
- **유연한 스케일링 (Manual Trigger)**: GitHub Actions 워크플로우(`_scale.yaml`)를 통해 필요시 수동으로 게이트웨이 서버(2, 3번 노드)를 Scale-In / Scale-Out 할 수 있는 자동화 스크립트를 제공합니다.
- **통합 로그 수집 및 로드밸런싱**: Docker Compose를 통해 Promtail을 함께 배포하여 Loki로 로그를 중앙 집중화하며, Nginx(`least_conn`)를 활용해 다중 게이트웨이 인스턴스로 트래픽을 효율적으로 분산합니다.

### 6. 테스트 및 검증 (Testing & Verification)

- **통합 테스트 기반 검증**: 게이트웨이 인증 필터의 핵심 시나리오(토큰 검증, 블랙리스트 차단, 화이트리스트 통과)에 대한 통합 테스트를 작성하여 안정성을 확보했습니다. ([PR #7](https://github.com/89-49/gateway-server/pull/7))
- **아키텍처 전환 후 동작 재검증**: 게이트웨이를 서블릿 기반에서 WebFlux 기반으로 전환하는 과정에서, 기존 통합 테스트 코드를 WebTestClient 및 WireMock 기반으로 업데이트하여 토큰 검증·블랙리스트 차단·화이트리스트 통과 시나리오가 리팩토링 이후에도 동일하게 정상 동작함을 재확인했습니다. ([PR #28](https://github.com/89-49/gateway-server/pull/28))

## 🛠 기술 스택
- **Runtime**: Java 21 / Spring Boot 3.5.13
- **Gateway**: Spring Cloud Gateway (WebFlux)
- **Security**: Spring Security 6.x (Reactive)
- **Tracing**: Micrometer Tracing (Brave)
- **Client**: Spring WebFlux WebClient
- **Cache**: Caffeine Cache

## 📂 주요 문서
- [상세 개선 보고서](./docs/gateway-server-improvement-summary.md): 기술적 해결 방안 및 리팩토링 상세 내역
- [설정 및 작업 이력](./docs/gateway-server-setup-summary.md): Phase별 구축 과정 및 최종 검증 결과

