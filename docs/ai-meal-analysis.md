# AI meal analysis

Spring Boot authenticates users, checks meal ownership, validates inputs, and owns all persistence.
The frontend calls the backend; only the backend calls the internal AI service.
The AI service receives only an image URL, and needs no access to the application database.

## Configuration

```dotenv
AI_SERVICE_BASE_URL=http://localhost:8000
AI_SERVICE_CONNECT_TIMEOUT=3s
AI_SERVICE_RESPONSE_TIMEOUT=75s
```

These values have the defaults above and are documented in `.env.example`.
Use the internal AI service address in deployment. Inside Docker, `localhost` refers to the
backend container; set the base URL to the AI container's service name instead.
The URL must be HTTP(S), without credentials, query parameters or fragments. Both timeouts
must be positive. Backend startup does not require the AI service to be online.
The client does not forward the user's Bearer token and does not retry or follow redirects.
The response timeout bounds waits while reading headers or body data; it is a socket read
timeout, rather than a total deadline for a continuously streaming response. It uses Spring
`RestClient` with a `SimpleClientHttpRequestFactory`, without additional dependencies.
The AI service must be reachable through the application's internal network and be able to
fetch the stored Cloudinary image URL.

## Analyze

`POST /api/meals/{mealId}/analyze`, with `Authorization: Bearer <accessToken>` and no request body.

The backend finds the current user's meal and requires a nonblank stored image URL.
It calls `POST {AI_SERVICE_BASE_URL}/api/v1/meals/analyze` with JSON:

```json
{
  "imageUrl": "https://res.cloudinary.com/example/image/upload/meal.jpg"
}
```

The AI endpoint returns `{"items":[...]}` directly, without a `BaseResponse` wrapper or meal ID.
Spring validates the items, then attaches the owned path meal ID to the frontend response.
The service-to-service contract does not send identity or persistence metadata to the model.
Every item must include a nonblank name, positive estimated grams, nonnegative
nutrition values, and confidence between 0 and 1. Missing fields, null items, malformed JSON,
are rejected. AI rounds portion/nutrition values to two decimal places before returning them;
confidence retains its precision. An empty item list returns a separate no-detection error.

The frontend receives the existing `BaseResponse` envelope:

```json
{
  "success": true,
  "code": 200,
  "message": "Success",
  "data": {
    "mealId": 123,
    "items": [
      {
        "name": "Grilled chicken breast",
        "estimatedGrams": 150,
        "calories": 248,
        "protein": 46.5,
        "carbohydrate": 0,
        "fat": 5.4,
        "confidence": 0.91
      }
    ]
  }
}
```

Analysis never creates, changes, or deletes meal items. Predictions are not stored in the
database or a server-side draft cache. No database transaction is held while waiting for AI.
Any body-supplied meal ID or image URL is ignored; the path and the owned meal determine the request.

## Confirm

`POST /api/meals/{mealId}/confirm-analysis`, with Bearer authentication and JSON:

```json
{
  "items": [
    {
      "name": "Chicken breast",
      "quantityGrams": 180,
      "calories": 298,
      "protein": 55.8,
      "carbohydrate": 0,
      "fat": 6.5
    }
  ]
}
```

The list must be nonempty and may not contain null items. Names must be nonblank and at most
255 characters. Quantity must be positive with at most 8 integer digits and 2 decimal places.
Nutrition values must be nonnegative with at most 2 decimal places; the existing upper bound
of 2147483647 remains. All values are required and represent totals for the supplied quantity,
not per 100 grams.

Confirmation locks the owned meal and replaces **all** its existing items in one transaction.
Any persistence failure rolls back the replacement. Concurrent confirmations are serialized
for the meal, so the final state contains one complete confirmed list. Repeating confirmation
replaces the list again and generates new item IDs; it does not append.

`name` maps to the existing `inputName`, and `protein`, `carbohydrate`, and `fat` map to the
existing `proteinGrams`, `carbohydrateGrams`, and `fatGrams` fields. Meal items use
`inputName` as their only food name; no confidence or AI metadata is stored.

The response envelope's `data` contains `id`, `mealType`, `mealDate`, `imageUrl`, and `items`.
Items use the existing `MealItemResponse` field names, including their database `id` and `mealId`.
Existing meal detail/list responses retain their current shape.

The frontend may submit edited values without a previous analysis call or an image; ownership
and validation are required. There is no server-side draft ID to verify. A body-supplied `mealId`
never changes the target meal. The backend does not recalculate or cross-check user-entered
nutrition against the AI prediction.

## Errors

Errors use the existing `BaseResponse` format with `success: false`, `code`, and `message`.

| Condition | HTTP status | Error code |
| --- | --- | --- |
| Missing/invalid authentication | 401 | Existing security/authentication codes |
| Meal missing or belongs to another user | 404 | 14000 |
| Missing meal image on analysis | 400 | 16000 |
| Connection failure, redirects, unavailable AI/provider or unexpected error status | 503 | 16001 |
| Connection/response timeout, including delayed body reads | 504 | 16002 |
| Malformed/incomplete/invalid AI prediction, AI 502 or unknown validation error | 502 | 16003 |
| No food detected (`no_food_detected`), or empty successful items | 422 | 16004 |
| Image cannot be downloaded/decoded (`invalid_image`) | 422 | 16005 |
| Invalid confirmation JSON or field values | 400 | 400 |

Ownership uses the existing not-found convention to avoid exposing other users' meals.
AI error response bodies are not forwarded to the frontend. AI 504 maps to 16002;
AI 502 maps to 16003. For AI 422, Spring reads only the bounded machine-readable `code`,
not provider messages. FastAPI's ordinary request-validation 422 is a contract failure (16003).

## Storage and compatibility

`V9__meal_item_decimal_nutrition.sql` converts existing nutrition columns from integers to
`NUMERIC(12, 2)` while retaining values and existing nonnegative constraints. Entity, request,
response, and mapper types use `BigDecimal`; no draft table or duplicate meal-item model is added.
The existing item CRUD API also accepts and returns decimals under its existing field names.
Frontend consumers should treat those JSON values as numbers, which can now include fractions.

## Changed files

- Added: `AiServiceConfig`, `AiServiceProperties`, `MealAnalysisController`, `MealAnalysisClient`,
  `RestMealAnalysisClient`, `ConfirmMealAnalysisRequest`, `MealAnalysisResponse`, `MealDetailsResponse`,
  migration V9, `AiServiceClientTest`, and `MealAnalysisIntegrationTest`.
- Extended: `MealService`, `MealServiceImpl`, `MealMapper`, `MealItemMapper`, `ErrorCode`, and
  `GlobalExceptionHandler`.
- Decimal nutrition: `MealItem`, `MealItemRequest`, `MealItemResponse`, and `MealItemIntegrationTest`.
- Configuration/documentation: `.env.example`, `application.yml`, and this document.

## Verification

Client tests use a local fake HTTP service and cover the JSON contract, malformed predictions,
connection failure, connection timeout, delayed headers, delayed response bodies, and upstream
error statuses. Database/API tests cover ownership, validation, draft non-persistence, decimal
storage, replacement, rollback, concurrent confirmation, migration preservation, and Swagger.
No external AI model is required for these tests.

```sh
./gradlew test
./gradlew build
```

The existing real-HTTP oversized multipart test can fail with a broken pipe under the default
Tomcat configuration because the rejected request body exceeds the amount Tomcat will swallow.
See the [Tomcat connector documentation](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html)
and [Spring Boot property reference](https://docs.spring.io/spring-boot/appendix/application-properties/)
for `maxSwallowSize` / `server.tomcat.max-swallow-size`.
In this workspace, verification uses the following test-process override so that the client
can read the expected 413 JSON response; no application configuration is changed:

```sh
env SERVER_TOMCAT_MAX_SWALLOW_SIZE=8MB ./gradlew build
```

For local Compose and VPS deployment, see [deployment.md](deployment.md).
Runtime checks from previous workspaces are historical; run the commands above for this revision.

Frontend integration, field mappings and user flow are documented in
[meal-analysis-frontend.md](meal-analysis-frontend.md).
The shared JSON fixtures in `src/test/resources/contracts/meal-analysis-contract.json`
and the AI repository test both sides of the service contract without calling a paid model.

## Meal item name

V10 removes the unused `normalized_name` column from `meal_items`. Item CRUD
requests/responses use `inputName` only; analysis and confirmation still use
`name`, which maps to `inputName` when saved. Existing item names and nutrition
values are preserved. Historical migrations V1–V9 remain unchanged.
