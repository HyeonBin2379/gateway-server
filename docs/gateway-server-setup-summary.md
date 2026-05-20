# Gateway Server 구축 및 보안 아키텍처 작업 이력 (최종본)

## 1. 프로젝트 개요
본 프로젝트는 MSA 환경에서 요청의 진입점 역할을 수행하는 **Servlet 기반(WebMVC)의 Spring Cloud Gateway**입니다. 전역적인 보안 입구 컷, 실시간 블랙리스트 검증, 그리고 분산 추적(Tracing)을 핵심 아키텍처로 채택하고 있습니다.

## 2. 작업 이력 및 기술적 진화

### 2.1 [Phase 1] 초기 인프라 설정 및 Config Server 통합
- **DataSource 자동 설정 제외**: DB 미사용에 따른 기동 오류를 `DataSourceAutoConfiguration` 제외 설정을 통해 1차 해결.
- **Docker 컨테이너화**: Multi-stage 빌드 및 `.env`를 통한 환경 변수 주입 환경 구축.
- **Config Server 통합**: 원격 설정 서버로부터 라우팅 및 보안 설정을 동적으로 로드하도록 구성.

### 2.2 [Phase 2] 의존성 격리 및 컨테이너 최적화 (JPA Isolation)
- **문제**: 공통 모듈(`common`) 로드 시 JPA 및 QueryDSL 관련 Bean이 강제 주입되어 기동 실패 현상 발생.
- **해결**: `GatewayApplication`에서 `@ImportAutoConfiguration(exclude = AppCtx.class)`를 적용하여 공통 메인 설정을 제외하고, `GatewayAppCtx`를 통해 게이트웨이에 필요한 Bean만 선택적으로 수용하도록 최적화.

### 2.3 [Phase 3] 필터 아키텍처 확정 (Servlet-based Gatekeeping)
- **JwtGatewayFilter (OncePerRequestFilter)**: 라우팅 설정 의존성을 제거하고 보안 강제성을 확보하기 위해 서블릿 필터 방식을 최종 채택.
- **순서 조정**: `Ordered.HIGHEST_PRECEDENCE + 1`을 부여하여 로깅 준비(`MDC`) 후 즉시 보안 검사가 이루어지도록 순서 확정.
- **가독성 개선**: Guard Clauses 패턴을 적용하여 중첩 `if`문을 제거하고 로직을 평탄화.

### 2.4 [Phase 4] 실시간 블랙리스트 검증 및 내결함성 (Resilience)
- **이중 검증**: 게이트웨이 로컬 검증(JWT 서명)과 `AuthProvider`를 통한 원격 실시간 검증(블랙리스트 여부)을 연동.
- **캐싱 전략**: `AuthProviderImpl`에 10~30초 단위의 짧은 로컬 캐시를 적용하여 인증 서비스 부하 감소와 실시간성 사이의 균형 확보.
- **Fallback 구현**: `AuthClientFallbackFactory`를 통해 인증 서버 장애 시에도 시스템 전체가 마비되지 않도록 Fail-Safe 로직 구축.

### 2.5 [Phase 5] 관측성(Tracing) 및 에러 표준화
- **Trace ID 동기화**: `Tracer`(Zipkin)가 생성한 진짜 ID를 `MDC` 및 헤더에 강제 동기화하여 분산 환경에서의 로그 일관성 100% 확보.
- **통합 에러 핸들링**: `CustomAuthenticationEntryPoint`를 필터 내부에서 직접 호출하도록 연동하여, 모든 인증 실패 시 공통 모듈의 `ErrorResponse` 규격에 맞는 JSON 응답을 보장.

## 3. 핵심 클래스 현황
- `GatewayApplication.java`: 애플리케이션 엔트리 포인트 및 자동 설정 제외 관리.
- `JwtGatewayFilter.java`: 입구 보안 및 헤더 주입을 담당하는 핵심 필터.
- `GatewayAppCtx.java`: 게이트웨이 전용 최적화 컨텍스트 구성.
- `AuthProviderImpl.java`: 캐싱 기반 실시간 토큰 검증기.
- `AuthClient.java`: 유저 서비스 규격(DTO)에 맞춘 Feign 통신 인터페이스.
- `CustomAuthenticationEntryPoint.java`: 통합 에러 응답 처리기.

## 4. 최종 검증 결과
1. **로그인 성공**: 유효한 Access Token 발급 및 Trace ID 생성 확인.
2. **권한 통과**: 발급된 토큰을 통한 게이트웨이 → 하위 서비스 호출 및 데이터 수신 성공.
3. **로그아웃 및 차단**: 로그아웃된 토큰 사용 시 게이트웨이 필터 및 서비스 최종 방어에 의해 **401 Unauthorized** 차단 성공.
4. **일관성 확인**: 게이트웨이 로그와 서비스 응답 내의 Trace ID가 완벽하게 일치함을 검증.

## 5. 설계 원칙 (Design Principles)
- **보안**: 설정 실수로 인한 보안 구멍이 발생하지 않도록 서블릿 컨테이너 레벨에서 선제 방어.
- **관측성**: "Single Source of Truth(ID)" 원칙에 기반한 전 구간 추적 시스템 구축.
- **유연성**: 공통 모듈의 에러 규격 및 DTO를 완벽히 준수하여 프론트엔드 연동성을 극대화.
