# Gateway Server 개선 및 보안 강화 상세 보고서

본 문서는 Gateway Server의 보안성, 안정성, 및 관측성 향상을 위해 진행된 주요 개선 사항 및 기술적 해결 방안을 상세히 기록합니다.

---

## 1. 필터 아키텍처 개편: Servlet-based Filter 도입

### 배경 및 문제점
Spring Cloud Gateway MVC 환경에서 표준 `HandlerFilterFunction`을 사용할 경우, 필터의 적용 여부가 라우팅 설정(YAML)에 의존하게 됩니다. 원격 Config 서버를 사용하거나 복잡한 라우팅 환경에서는 설정 실수로 인해 특정 경로에서 인증 필터가 누락될 수 있는 보안 허점이 존재했습니다.

### 개선 사항
- **`OncePerRequestFilter` 채택**: 서블릿 컨테이너(Tomcat) 레벨에서 동작하는 필터 방식을 도입하여, 게이트웨이 엔진의 라우팅 설정과 무관하게 모든 HTTP 요청에 대해 필터 실행을 강제했습니다.
- **최상위 우선순위 (`Ordered.HIGHEST_PRECEDENCE + 1`)**: 로깅 필터(`MdcLoggingFilter`) 직후에 실행되도록 보장하여, 보안 검사 이전에 추적 컨텍스트를 완벽히 준비했습니다.
- **Fail-Fast 보안 정책**: 유효하지 않은 모든 토큰(위조, 만료, 블랙리스트 등)에 대해 즉시 401 응답을 반환하고 요청을 종료하여 하위 서비스 자원을 보호합니다.

---

## 2. 최적화된 이중 검증 시스템 (Performance Optimized)

리소스 소모를 최소화하기 위해 검증 순서를 비용 효율적으로 재설계했습니다.

1.  **로컬 검증 (Local Validation - 1차)**: `TokenProvider`를 통해 JWT의 서명 위조 및 만료 여부를 게이트웨이 메모리 내에서 즉시 확인합니다. (가장 비용이 낮음)
2.  **원격 검증 (Remote Verification - 2차)**: 로컬 검증을 통과한 유효한 토큰에 한해서만 유저 서비스 API 또는 로컬 캐시를 통해 블랙리스트 여부를 확인합니다.
3.  **효율적 캐싱**: `AuthProviderImpl` 내부에 짧은 TTL(10~30s)의 캐시를 적용하여 실시간성과 성능 사이의 균형을 맞췄습니다.

---

## 3. 분산 추적 및 관측성 (Distributed Tracing)

### Trace ID 동기화 전략 (Zipkin Readiness)
분산 환경에서 로그의 정합성을 100% 보장하기 위해 **"단일 소스 원칙(Single Source of Truth)"**을 적용했습니다.
1. **Tracer 우선순위**: Zipkin(`Tracer`)이 생성한 실제 Trace ID를 최우선으로 가져옵니다.
2. **MDC 동기화**: 결정된 진짜 Trace ID를 `MDC.put("traceId", traceId)`를 통해 로그 시스템에 강제 동기화합니다. 이는 `MdcLoggingFilter`가 생성한 임시 ID를 진짜 ID로 교체하는 역할을 합니다.
3. **전구간 전파**: 동기화된 ID를 하위 서비스로 전달되는 `X-Trace-Id` 헤더에 주입하여 전체 트랜잭션을 하나의 ID로 연결합니다.

---

## 4. 에러 핸들링 표준화

### 공통 에러 응답 (`ErrorResponse`) 적용
- **에러 처리 일원화**: 필터 내부의 개별 응답 로직을 제거하고 `CustomAuthenticationEntryPoint`로 에러 처리를 위임하여 모든 인증 실패 응답 형식을 통일했습니다.
- **Trace ID 포함**: 모든 에러 응답 본문에 Trace ID를 포함시켜 장애 발생 시 로그 추적의 편의성을 극대화했습니다.

---

## 5. 의존성 격리 및 최적화 (JPA Dependency Isolation)

### 기술적 해결 방안
- **명시적 자동 설정 제외**: `GatewayApplication`에서 `@ImportAutoConfiguration(exclude = AppCtx.class)`를 사용하여 불필요한 JPA 관련 설정을 완벽히 차단했습니다.
- **맞춤형 컨텍스트 구성 (`GatewayAppCtx`)**: 게이트웨이에 꼭 필요한 공통 기능(Feign, JSON, Error Properties 등)만 선택적으로 로드하여 컨텍스트를 경량화했습니다.

---

## 6. 시스템 내결함성 및 통합 테스트

### Feign Client Fallback
- `AuthClientFallbackFactory`를 구현하여 인증 서비스 장애 시에도 시스템 전체가 마비되지 않도록 Fail-Safe 로직을 강화했습니다.

### 통합 테스트 성공
- **로그인/로그아웃/재발급**: 모든 핵심 인증 시나리오에 대해 Trace ID 추적과 함께 정상 동작 및 즉시 차단(Fail-Fast) 기능을 완벽히 검증했습니다.

---