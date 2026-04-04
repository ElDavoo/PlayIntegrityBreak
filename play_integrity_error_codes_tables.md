# Play Integrity API Error Codes (with descriptions)

## IntegrityErrorCode

| Source | Error Code | Name | Retryable | Description | Possible resolution |
|---|---:|---|---|---|---|
| Java (classic) | -1 | API_NOT_AVAILABLE | No | Play Store may be too old, or Play Integrity API is not enabled in Play Console. | Enable Integrity API in Play Console and ask the user to update Play Store. |
| Java (classic) | -2 | PLAY_STORE_NOT_FOUND | No | No official Play Store app was found on the device. | Ask the user to install or enable Google Play Store. |
| Java (classic) | -3 | NETWORK_ERROR | Yes | Network problem between device and Play systems. | Use remediation dialog (GET_INTEGRITY / GET_STRONG_INTEGRITY), check connectivity, retry with backoff. |
| Java (classic) | -4 | PLAY_STORE_ACCOUNT_NOT_FOUND | No | Classic-only on older Play Store versions; no Play Store account found. | Ask the user to update and sign in to Google Play Store. |
| Java (classic) | -5 | APP_NOT_INSTALLED | No | Calling app is not installed (possible tampering/attack). | Non-actionable; treat as failed integrity checks. |
| Java (classic) | -6 | PLAY_SERVICES_NOT_FOUND | No | Google Play services unavailable or outdated. | Use remediation dialog and ask user to install/update/enable Play services. |
| Java (classic) | -7 | APP_UID_MISMATCH | No | Calling app UID does not match Package Manager UID. | Non-actionable; treat as failed integrity checks. |
| Java (classic) | -8 | TOO_MANY_REQUESTS | Yes | App is throttled due to too many requests or quota exceeded. | Retry with exponential backoff; request higher daily quota if needed. |
| Java (classic) | -9 | CANNOT_BIND_TO_SERVICE | No | Binding to Play Store service failed, often due to old Play Store. | Ask the user to update Google Play Store. |
| Java (classic) | -10 | NONCE_TOO_SHORT | No | Nonce is shorter than minimum 16 bytes before base64 encoding. | Retry with a longer nonce. |
| Java (classic) | -11 | NONCE_TOO_LONG | No | Nonce exceeds 500 bytes before base64 encoding. | Retry with a shorter nonce. |
| Java (classic) | -12 | GOOGLE_SERVER_UNAVAILABLE | Yes | Unknown internal Google server error. | Retry with exponential backoff; file a bug if persistent. |
| Java (classic) | -13 | NONCE_IS_NOT_BASE64 | No | Nonce is not base64 web-safe no-wrap encoded. | Retry with correctly formatted nonce. |
| Java (classic) | -14 | PLAY_STORE_VERSION_OUTDATED | No | Google Play Store needs update. | Ask the user to update Google Play Store. |
| Java (classic) | -15 | PLAY_SERVICES_VERSION_OUTDATED | No | Google Play services need update. | Use remediation dialog and ask user to update Google Play services. |
| Java (classic) | -16 | CLOUD_PROJECT_NUMBER_IS_INVALID | No | Provided cloud project number is invalid. | Use the cloud project number where Play Integrity API is enabled. |
| Java (classic) | -17 | CLIENT_TRANSIENT_ERROR | Yes | Transient client-side error in classic requests. | Retry with exponential backoff. |
| Java (classic) | -100 | INTERNAL_ERROR | Yes | Unknown internal error. | Retry with exponential backoff; file a bug if persistent. |
| Native (classic) | -100 | INTEGRITY_INTERNAL_ERROR | Yes | Unknown internal error. | Retry with exponential backoff; file a bug if persistent. |
| Native (classic) | -101 | INTEGRITY_INITIALIZATION_NEEDED | No | Integrity manager is not initialized. | Initialize the manager first. |
| Native (classic) | -102 | INTEGRITY_INITIALIZATION_FAILED | Yes | Error initializing Integrity API. | Retry with exponential backoff; file a bug if persistent. |
| Native (classic) | -103 | INTEGRITY_INVALID_ARGUMENT | No | Invalid argument passed to the Integrity API. | Retry with the correct argument. |

## StandardIntegrityErrorCode

| Source | Error Code | Name | Retryable | Description | Possible resolution |
|---|---:|---|---|---|---|
| Java (standard) | -1 | API_NOT_AVAILABLE | No | Play Store may be too old, or Play Integrity API is not enabled in Play Console. | Enable Integrity API in Play Console and ask the user to update Play Store. |
| Java (standard) | -2 | PLAY_STORE_NOT_FOUND | No | No official Play Store app was found on the device. | Ask the user to install or enable Google Play Store. |
| Java (standard) | -3 | NETWORK_ERROR | Yes | Network problem between device and Play systems. | Use remediation dialog (GET_INTEGRITY / GET_STRONG_INTEGRITY), check connectivity, retry with backoff. |
| Java (standard) | -5 | APP_NOT_INSTALLED | No | Calling app is not installed (possible tampering/attack). | Non-actionable; treat as failed integrity checks. |
| Java (standard) | -6 | PLAY_SERVICES_NOT_FOUND | No | Google Play services unavailable or outdated. | Use remediation dialog and ask user to install/update/enable Play services. |
| Java (standard) | -7 | APP_UID_MISMATCH | No | Calling app UID does not match Package Manager UID. | Non-actionable; treat as failed integrity checks. |
| Java (standard) | -8 | TOO_MANY_REQUESTS | Yes | App is throttled due to too many requests or quota exceeded. | Retry with exponential backoff; request higher daily quota if needed. |
| Java (standard) | -9 | CANNOT_BIND_TO_SERVICE | No | Binding to Play Store service failed, often due to old Play Store. | Ask the user to update Google Play Store. |
| Java (standard) | -12 | GOOGLE_SERVER_UNAVAILABLE | Yes | Unknown internal Google server error. | Retry with exponential backoff; file a bug if persistent. |
| Java (standard) | -14 | PLAY_STORE_VERSION_OUTDATED | No | Google Play Store needs update. | Ask the user to update Google Play Store. |
| Java (standard) | -15 | PLAY_SERVICES_VERSION_OUTDATED | No | Google Play services need update. | Use remediation dialog and ask user to update Google Play services. |
| Java (standard) | -16 | CLOUD_PROJECT_NUMBER_IS_INVALID | No | Provided cloud project number is invalid. | Use the cloud project number where Play Integrity API is enabled. |
| Java (standard) | -17 | REQUEST_HASH_TOO_LONG | No | requestHash is too long (must be <500 chars). | Retry with a shorter requestHash. |
| Java (standard) | -18 | CLIENT_TRANSIENT_ERROR | Yes | Standard-only transient client-side error (supported in newer library/plugin/SDK versions). | Retry with exponential backoff. |
| Java (standard) | -19 | INTEGRITY_TOKEN_PROVIDER_INVALID | No | Token provider expired/invalid (for example, Play Store data cleared). | Request a new integrity token provider. |
| Java (standard) | -100 | INTERNAL_ERROR | Yes | Unknown internal error. | Retry with exponential backoff; file a bug if persistent. |
| Native (standard) | -100 | STANDARD_INTEGRITY_INTERNAL_ERROR | Yes | Unknown internal error. | Retry with exponential backoff; file a bug if persistent. |
| Native (standard) | -101 | STANDARD_INTEGRITY_INITIALIZATION_NEEDED | No | StandardIntegrityManager is not initialized. | Call StandardIntegrityManager_init() first. |
| Native (standard) | -102 | STANDARD_INTEGRITY_INITIALIZATION_FAILED | Yes | Error initializing the Standard Integrity API. | Retry with exponential backoff; file a bug if persistent. |
| Native (standard) | -103 | STANDARD_INTEGRITY_INVALID_ARGUMENT | No | Invalid argument passed to Standard Integrity API. | Retry with the correct argument. |
