# Support

| Line | Branch | Status |
|---|---|---|
| 2.x | `main` (tags `v2.*`) | **Active**: features and fixes. |
| 1.x | [`release/1.x`](https://github.com/absmartly/java-sdk/tree/release/1.x) (tags `v1.*`) | **Bug fixes until <2.0 release date + 12 months> on release/1.x**, including contract changes. Includes `core-api-java6`. |

2.x: active. 1.x: bug fixes until <2.0 release date + 12 months> on release/1.x.

Fixes land on `main` first and are cherry-picked to `release/1.x` while 1.x is supported; a bug that only exists in
1.x is fixed on `release/1.x` directly. Both lines are published to Maven Central as `com.absmartly.sdk:core-api`, so
pin the major you use. To move to 2.x, see [MIGRATING.md](MIGRATING.md).
