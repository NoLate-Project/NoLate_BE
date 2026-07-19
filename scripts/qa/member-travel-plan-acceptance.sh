#!/usr/bin/env bash

set -euo pipefail

# Local acceptance scenario for per-member travel plans. Credentials are required
# through environment variables so this script never persists a real password or JWT.
BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
: "${OWNER_EMAIL:?OWNER_EMAIL is required}"
: "${OWNER_PASSWORD:?OWNER_PASSWORD is required}"

QA_PASSWORD="${QA_PASSWORD:-Travel!2823}"
STAMP="$(date +%s)"
read -r START_AT END_AT OWNER_DEPART_AT <<<"$(ruby -rtime -e '
  start_at = Time.now.utc + 86_400
  puts [start_at.iso8601, (start_at + 3_600).iso8601, (start_at - 1_800).iso8601].join(" ")
')"

api() {
  local method="$1"
  local path="$2"
  local token="${3:-}"
  local body="${4:-}"
  local args=(-sS -X "$method" -H "Content-Type: application/json")

  if [[ -n "$token" ]]; then
    args+=(-H "Authorization: Bearer $token")
  fi
  if [[ -n "$body" ]]; then
    args+=(-d "$body")
  fi
  curl "${args[@]}" "$BASE_URL$path"
}

assert_success() {
  jq -e '.success == true' >/dev/null <<<"$1"
}

owner_login="$(api POST /api/member/auth/login "" "$(
  jq -nc --arg email "$OWNER_EMAIL" --arg password "$OWNER_PASSWORD" \
    '{email:$email,password:$password}'
)")"
assert_success "$owner_login"
owner_token="$(jq -r '.data.accessToken' <<<"$owner_login")"
owner_id="$(jq -r '.data.id' <<<"$owner_login")"

categories="$(api GET /api/schedule-categories "$owner_token")"
assert_success "$categories"
if [[ "$(jq '.data | length' <<<"$categories")" -eq 0 ]]; then
  category_response="$(api POST /api/schedule-categories "$owner_token" \
    '{"title":"공유 QA","color":"#1A73E8","iconKey":"calendar"}')"
  assert_success "$category_response"
  category="$(jq -c '.data' <<<"$category_response")"
else
  category="$(jq -c '.data[0]' <<<"$categories")"
fi
category_id="$(jq -r '.id' <<<"$category")"
category_title="$(jq -r '.title' <<<"$category")"
category_color="$(jq -r '.color' <<<"$category")"

declare -a member_ids member_tokens member_emails
for i in 1 2 3; do
  email="travel-plan-qa-${STAMP}-${i}@example.com"
  signup_body="$(jq -nc \
    --arg email "$email" \
    --arg password "$QA_PASSWORD" \
    --arg name "이동계획 QA ${i}" \
    '{
      email:$email,
      password:$password,
      name:$name,
      consents:{
        termsVersion:"2026.07.16",
        privacyCollectionVersion:"2026.07.16",
        termsAgreed:true,
        privacyCollectionAgreed:true
      }
    }')"
  signup="$(api POST /api/member/auth/sign-up "" "$signup_body")"
  assert_success "$signup"

  login="$(api POST /api/member/auth/login "" "$(
    jq -nc --arg email "$email" --arg password "$QA_PASSWORD" \
      '{email:$email,password:$password}'
  )")"
  assert_success "$login"
  member_ids[$i]="$(jq -r '.data.id' <<<"$login")"
  member_tokens[$i]="$(jq -r '.data.accessToken' <<<"$login")"
  member_emails[$i]="$email"
done

schedule_body="$(jq -nc \
  --arg title "개인 이동계획 공유 QA ${STAMP}" \
  --arg startAt "$START_AT" \
  --arg endAt "$END_AT" \
  --arg departAt "$OWNER_DEPART_AT" \
  --arg categoryId "$category_id" \
  --arg categoryTitle "$category_title" \
  --arg categoryColor "$category_color" \
  '{
    title:$title,
    startAt:$startAt,
    endAt:$endAt,
    hasEndTime:true,
    allDay:false,
    travelMinutes:30,
    departAt:$departAt,
    travelMode:"CAR",
    origin:{
      name:"오너 출발지 · 서울역",
      address:"서울 중구 한강대로 405",
      lat:37.5547,
      lng:126.9707
    },
    destination:{
      name:"NoLate 테스트룸",
      address:"서울 강남구 강남대로 396",
      lat:37.4979,
      lng:127.0276
    },
    locationName:"NoLate 테스트룸",
    category:{id:$categoryId,title:$categoryTitle,color:$categoryColor},
    notes:"오너와 참여자 3명의 개인 이동 계획 권한 QA",
    routeSetupRequired:false,
    route:{
      id:"owner-route",
      mode:"CAR",
      minutes:30,
      pathCoords:[
        {lat:37.5547,lng:126.9707},
        {lat:37.5251,lng:127.0032},
        {lat:37.4979,lng:127.0276}
      ]
    },
    notificationEnabled:false
  }')"
schedule_response="$(api POST /api/schedules "$owner_token" "$schedule_body")"
assert_success "$schedule_response"
schedule_id="$(jq -r '.data.id' <<<"$schedule_response")"

# Use both recipient lookup keys and both sharing surfaces in one scenario.
share_editor="$(api POST "/api/schedules/${schedule_id}/shares" "$owner_token" "$(
  jq -nc --arg email "${member_emails[1]}" '{targetEmail:$email,permission:"EDITOR"}'
)")"
share_viewer="$(api POST "/api/schedules/${schedule_id}/shares" "$owner_token" "$(
  jq -nc --argjson appId "${member_ids[2]}" '{targetAppId:$appId,permission:"VIEWER"}'
)")"
share_category="$(api POST "/api/schedule-categories/${category_id}/shares" "$owner_token" "$(
  jq -nc --argjson appId "${member_ids[3]}" '{targetAppId:$appId,permission:"VIEWER"}'
)")"
assert_success "$share_editor"
assert_success "$share_viewer"
assert_success "$share_category"

origins=("판교역" "홍대입구역" "잠실역")
lats=("37.3948" "37.5572" "37.5133")
lngs=("127.1112" "126.9254" "127.1002")
minutes=("42" "35" "28")
modes=("TRANSIT" "CAR" "BIKE")

for i in 1 2 3; do
  index=$((i - 1))
  depart_at="$(ruby -rtime -e \
    "puts (Time.parse('$START_AT') - ${minutes[$index]} * 60).iso8601")"
  plan_body="$(jq -nc \
    --arg origin "${origins[$index]}" \
    --arg mode "${modes[$index]}" \
    --argjson lat "${lats[$index]}" \
    --argjson lng "${lngs[$index]}" \
    --argjson minutes "${minutes[$index]}" \
    --arg departAt "$depart_at" \
    --arg routeId "member-${member_ids[$i]}-route" \
    '{
      travelMinutes:$minutes,
      departAt:$departAt,
      travelMode:$mode,
      origin:{name:$origin,address:($origin + " 개인 출발지"),lat:$lat,lng:$lng},
      route:{
        id:$routeId,
        mode:$mode,
        minutes:$minutes,
        pathCoords:[
          {lat:$lat,lng:$lng},
          {lat:(($lat + 37.4979) / 2),lng:(($lng + 127.0276) / 2)},
          {lat:37.4979,lng:127.0276}
        ]
      },
      notificationEnabled:false
    }')"
  plan_response="$(api PUT "/api/schedules/${schedule_id}/travel-plans/my" \
    "${member_tokens[$i]}" "$plan_body")"
  jq -e \
    --argjson memberId "${member_ids[$i]}" \
    --arg origin "${origins[$index]}" \
    '.success == true and .data.memberId == $memberId and
      .data.status == "READY" and .data.origin.name == $origin' \
    >/dev/null <<<"$plan_response"
done

owner_overview="$(api GET "/api/schedules/${schedule_id}/travel-plans" "$owner_token")"
editor_overview="$(api GET "/api/schedules/${schedule_id}/travel-plans" "${member_tokens[1]}")"
viewer_overview="$(api GET "/api/schedules/${schedule_id}/travel-plans" "${member_tokens[2]}")"
category_viewer_overview="$(api GET "/api/schedules/${schedule_id}/travel-plans" "${member_tokens[3]}")"

jq -e \
  --argjson ownerId "$owner_id" \
  --argjson editorId "${member_ids[1]}" \
  --argjson viewerId "${member_ids[2]}" \
  --argjson categoryViewerId "${member_ids[3]}" \
  '.success == true and .data.canViewAllTravelPlans == true and
    ([.data.participants[].memberId] | contains([$ownerId,$editorId,$viewerId,$categoryViewerId])) and
    ([.data.participants[] |
      select(.memberId == $editorId or .memberId == $viewerId or .memberId == $categoryViewerId) |
      .status] | all(. == "READY"))' \
  >/dev/null <<<"$owner_overview"

jq -e --argjson viewerId "${member_ids[2]}" \
  '.success == true and .data.canViewAllTravelPlans == true and
    ([.data.participants[] | select(.memberId == $viewerId)][0].canViewDetails == true)' \
  >/dev/null <<<"$editor_overview"

jq -e --argjson ownerId "$owner_id" --argjson viewerId "${member_ids[2]}" \
  '.success == true and .data.canViewAllTravelPlans == false and
    ([.data.participants[] | select(.memberId == $ownerId)][0].canViewDetails == false) and
    ([.data.participants[] | select(.memberId == $ownerId)][0].originName == null) and
    ([.data.participants[] | select(.memberId == $viewerId)][0].canViewDetails == true)' \
  >/dev/null <<<"$viewer_overview"

jq -e --argjson categoryViewerId "${member_ids[3]}" \
  '.success == true and .data.canViewAllTravelPlans == false and
    ([.data.participants[] | select(.memberId == $categoryViewerId)][0].canViewDetails == true)' \
  >/dev/null <<<"$category_viewer_overview"

owner_reads_viewer="$(api GET \
  "/api/schedules/${schedule_id}/travel-plans/${member_ids[2]}" "$owner_token")"
editor_reads_viewer="$(api GET \
  "/api/schedules/${schedule_id}/travel-plans/${member_ids[2]}" "${member_tokens[1]}")"
viewer_reads_editor="$(api GET \
  "/api/schedules/${schedule_id}/travel-plans/${member_ids[1]}" "${member_tokens[2]}")"
jq -e '.success == true and .data.origin.name == "홍대입구역"' \
  >/dev/null <<<"$owner_reads_viewer"
jq -e '.success == true and .data.origin.name == "홍대입구역"' \
  >/dev/null <<<"$editor_reads_viewer"
jq -e '.success == false and .errorCode == "A002"' \
  >/dev/null <<<"$viewer_reads_editor"

viewer_schedule="$(api GET "/api/schedules/${schedule_id}" "${member_tokens[2]}")"
category_viewer_schedule="$(api GET "/api/schedules/${schedule_id}" "${member_tokens[3]}")"
jq -e '.success == true and .data.origin.name == "홍대입구역" and
  .data.destination.name == "NoLate 테스트룸" and
  .data.origin.name != "오너 출발지 · 서울역"' \
  >/dev/null <<<"$viewer_schedule"
jq -e '.success == true and .data.origin.name == "잠실역" and
  .data.destination.name == "NoLate 테스트룸"' \
  >/dev/null <<<"$category_viewer_schedule"

editor_inbox="$(api GET /api/shares/inbox "${member_tokens[1]}")"
viewer_inbox="$(api GET /api/shares/inbox "${member_tokens[2]}")"
category_inbox="$(api GET /api/shares/inbox "${member_tokens[3]}")"
jq -e --arg scheduleId "$schedule_id" \
  '.success == true and ([.data.receivedShares[] |
    select(.resourceType == "SCHEDULE") | .resourceId] | index($scheduleId) != null)' \
  >/dev/null <<<"$editor_inbox"
jq -e --arg scheduleId "$schedule_id" \
  '.success == true and ([.data.receivedShares[] |
    select(.resourceType == "SCHEDULE") | .resourceId] | index($scheduleId) != null)' \
  >/dev/null <<<"$viewer_inbox"
jq -e --arg categoryId "$category_id" \
  '.success == true and ([.data.receivedShares[] |
    select(.resourceType == "CATEGORY") | .resourceId] | index($categoryId) != null)' \
  >/dev/null <<<"$category_inbox"

jq -nc \
  --argjson scheduleId "$schedule_id" \
  --argjson ownerId "$owner_id" \
  --argjson editorId "${member_ids[1]}" \
  --argjson viewerId "${member_ids[2]}" \
  --argjson categoryViewerId "${member_ids[3]}" \
  --argjson participantCount "$(jq '.data.participants | length' <<<"$owner_overview")" \
  '{
    result:"PASS",
    scheduleId:$scheduleId,
    ownerId:$ownerId,
    participantIds:[$editorId,$viewerId,$categoryViewerId],
    participantCount:$participantCount,
    sharing:["schedule/email/EDITOR","schedule/appId/VIEWER","category/appId/VIEWER"],
    travelPlans:["owner","editor","viewer","category-viewer"],
    permissions:{
      ownerReadsAll:true,
      editorReadsAll:true,
      viewerReadsOnlySelf:true,
      ownerRouteHiddenFromRecipients:true
    },
    inboxVerified:true
  }'
