# Quick Schedule Creation Roadmap

Last verified: 2026-06-26 KST

## Goal

빠른 일정 생성은 NoLate의 일정 등록 진입 비용을 줄이는 핵심 기능으로 둔다. 다음 버전에서는 입력 채널을 늘리되, 모든 채널이 같은 일정 추출 엔진과 확인/수정 화면을 사용하도록 확장한다.

## Current Status

- 텍스트 기반 빠른 일정 생성이 `POST /api/schedules/parse`로 구현되어 있다.
- 백엔드는 규칙 파서를 먼저 실행하고, 필수 값이 부족할 때 Groq 기반 AI 파서로 보완한다.
- 프론트는 새 일정 바텀시트에서 분석 결과를 제목, 날짜, 시간, 출발지, 목적지, 메모 필드에 적용한다.
- 저장 전 확인/수정 흐름은 이미 일정 생성 폼이 담당한다.

## Next Version Scope

### Phase 1: Quick Creation Hub

- 새 일정 바텀시트의 빠른 생성 영역을 `대화`, `사진`, `공유` 입력 채널 허브로 정리한다.
- 파서 API 요청에 `inputType`을 추가해 서버 로그/분석/향후 분기 기준을 마련한다.
- MVP에서는 `CONVERSATION`을 실제 동작 채널로 두고, `IMAGE_OCR`, `SHARE_TEXT`는 UI 진입점과 API 계약만 먼저 열어둔다.

### Phase 2: Photo To Schedule

- 이미지 선택/촬영 진입점을 추가한다.
- OCR 결과 텍스트를 기존 `POST /api/schedules/parse`로 전달한다.
- OCR confidence가 낮거나 날짜/시간/목적지가 누락되면 저장 전 확인 화면에서 검토를 요구한다.
- 원본 이미지는 저장하지 않는 것을 기본 정책으로 한다.

### Phase 3: Share To Schedule

- iOS Share Extension 또는 React Native 공유 인입 방식을 검증한다.
- 공유된 텍스트, URL 제목/본문, 이미지 OCR 결과를 같은 파서 엔진으로 전달한다.
- 카카오톡/문자/메일/웹페이지 공유를 우선 acceptance 시나리오로 둔다.

### Phase 4: Conversational Repair

- 첫 분석 결과의 `missingFields`를 기반으로 부족한 값을 한 번 더 묻는 흐름을 추가한다.
- 예: 시간이 없으면 시간만, 목적지가 없으면 장소만 입력받아 기존 분석 결과와 병합한다.
- 대화 상태는 저장 전 임시 세션으로만 유지하고 일정 저장 전에는 DB에 남기지 않는다.

## API Contract

`POST /api/schedules/parse`

```json
{
  "text": "금요일 오후 7시 강남역에서 저녁",
  "inputType": "CONVERSATION",
  "referenceDate": "2026-06-26",
  "defaultDurationMinutes": 60
}
```

Supported `inputType` values:

- `TEXT`
- `CONVERSATION`
- `IMAGE_OCR`
- `SHARE_TEXT`

## Acceptance Criteria

- 사용자는 빠른 생성 영역에서 대화/사진/공유 입력 채널을 인지할 수 있다.
- 대화 입력은 기존처럼 일정 생성 폼에 분석 결과를 적용한다.
- 사진/공유는 실제 OCR/공유 확장 연결 전까지 준비 상태를 명확히 안내한다.
- 파서 API는 `inputType`이 포함된 요청을 역직렬화할 수 있고, 기존 `inputType` 없는 요청도 계속 동작한다.
- 저장 전 확인/수정 화면은 모든 입력 채널에서 동일하게 재사용된다.

## Suggested Implementation Order

1. 빠른 생성 UI를 입력 채널 허브로 변경한다.
2. `ParseScheduleTextRequest.inputType`과 FE API type을 추가한다.
3. API wrapper 테스트로 `inputType` 전달을 보호한다.
4. 이미지 선택/OCR 라이브러리 후보를 결정한다.
5. OCR 결과를 기존 파서로 연결한다.
6. iOS 공유 인입 기술 검증을 별도 브랜치에서 진행한다.

