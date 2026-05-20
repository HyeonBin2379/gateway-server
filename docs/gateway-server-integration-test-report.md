# 게이트웨이 서버 통합 테스트 구현 보고서

이 문서는 게이트웨이 서버(`gateway-server`)의 핵심 로직인 인증 필터(`JwtGatewayFilter`) 및 보안 정책에 대한 통합 테스트 구현 내역을 정리합니다.

## 1. 개요
조만간 예정된 **WebFlux 기반 게이트웨이로의 리팩토링**을 대비하여, 현재 서블릿 기반 환경에서의 비즈니스 정합성(인증, 헤더 주입, 보안 등)을 보장하기 위한 통합 테스트를 작성했습니다.

## 2. 테스트 환경 및 전략
- **도구**: `JUnit 5`, `MockMvc`, `Mockito`
- **전략**: 
  - 외부 서비스(`user-service`) 호출은 `FeignClient`를 `@MockBean`으로 처리하여 격리된 테스트 수행.
  - 게이트웨이가 하위 서비스로 전달하는 헤더를 검증하기 위해 테스트 내부 전용 `TestDownstreamController`를 정의.
  - 나중에 WebFlux로 전환 시 테스트 코드 수정을 최소화할 수 있도록 비즈니스 로직(결과 헤더 검증) 위주로 구성.

## 3. 테스트 시나리오
작성된 `JwtGatewayIntegrationTest`는 다음 4가지 핵심 시나리오를 검증합니다.

| 시나리오 | 검증 내용 | 결과 |
| :--- | :--- | :---: |
| **인증 성공 및 헤더 주입** | 유효한 토큰 요청 시 `x-user-id`, `x-user-roles` 헤더가 정상 주입되는지 확인 | **PASS** |
| **블랙리스트 차단** | 로그아웃된 토큰 요청 시 인증 서비스(`user-service`) 연동을 통해 401 응답 확인 | **PASS** |
| **화이트리스트 통과** | 로그인, 회원가입 등 인증 제외 경로가 토큰 없이 정상 동작하는지 확인 | **PASS** |
| **헤더 스푸핑 방지** | 클라이언트가 보낸 임의의 `x-user-` 헤더가 무시되고 인증 정보로 덮어써지는지 확인 | **PASS** |

## 4. 기술적 이슈 및 해결 내역

### 4.1 TokenType 매칭 오류 (401 Unauthorized)
- **문제**: 테스트 코드에서 `tokenType`을 `"ACCESS"`로 주입했으나 필터에서 검증 실패.
- **원인**: 공통 모듈의 `TokenType` enum이 내부 필드 `value`를 기준으로 `"access"` (소문자)와 매칭하도록 구현되어 있었음.
- **해결**: Claims 생성 시 `TokenType.ACCESS.getValue()` 값인 `"access"`를 사용하도록 수정.

### 4.2 응답 구조 불일치 (PathNotFoundException)
- **문제**: `$.userId` 경로로 JSON 결과를 찾지 못해 테스트 실패.
- **원인**: 프로젝트의 `CommonResponseAdvice`가 적용되어 모든 응답이 `{"success":..., "data":{...}}` 구조로 감싸짐.
- **해결**: JSON Path를 `$.data.userId`, `$.data.roles`로 수정하여 실제 데이터 영역을 검증하도록 변경.

### 4.3 WebTestClient 의존성 이슈
- **문제**: WebFlux 전환을 고려해 `WebTestClient`를 쓰려 했으나, MVC 환경에서 이를 사용하려면 `spring-webflux` 라이브러리가 테스트 클래스패스에 추가되어야 함.
- **결정**: 현재 프로젝트의 순수성을 유지하기 위해 추가 의존성 없이 `MockMvc`를 사용하되, 테스트 로직을 단순화하여 나중에 교체가 쉽도록 구현함.

## 5. 향후 WebFlux 리팩토링 시 가이드
현재 작성된 테스트 코드는 비즈니스 로직 검증에 집중되어 있으므로, WebFlux 마이그레이션 시 다음 부분만 수정하면 됩니다.

1. **테스트 클라이언트 변경**: `MockMvc` 대신 `WebTestClient` 사용 (이때 `spring-boot-starter-webflux` 의존성 필요).
2. **바인딩 방식 수정**: `MockMvcWebTestClient` 대신 WebFlux용 `WebTestClient.bindToApplicationContext()` 사용.
3. **결과 검증**: 현재 `andExpect(jsonPath(...))` 문법은 `WebTestClient`에서도 거의 동일하게 지원하므로 로직 재사용 가능.

---
**작성일**: 2026-05-08  
**작성자**: Gemini CLI
