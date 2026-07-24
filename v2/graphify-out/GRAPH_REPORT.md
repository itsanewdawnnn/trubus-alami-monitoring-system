# Graph Report - v2  (2026-07-24)

## Corpus Check
- 99 files · ~125,107 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 670 nodes · 1103 edges · 80 communities (71 shown, 9 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 25 edges (avg confidence: 0.74)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- ApiResponse
- MainViewModel
- MemberLocationService
- MainAppScreen.kt
- TAMS Web
- MemberRepository
- UpdateManager
- AddressSearchResult
- outlet.js
- FakeOfflineLocationDao
- functions.php
- MemberRepositoryTest
- app.js
- OutletScreen.kt
- TrackingHealthTest
- members.js
- .download
- live_tracking.js
- history.js
- ota_update.js
- api.php
- OsmMap
- map.js
- .onCreate
- UnitJsonAdapter
- WibTime
- ShadowWorkManagerInitializer
- ReverseGeocodingService
- .getCurrentLocation
- TrackingHealth
- member_log.js
- remote_management.js
- outlet_functions.php
- gradlew
- authenticateUser

## God Nodes (most connected - your core abstractions)
1. `MainViewModel` - 60 edges
2. `ApiResponse` - 39 edges
3. `MemberRepository` - 36 edges
4. `MemberLocationService` - 29 edges
5. `ApiService` - 23 edges
6. `UnsupportedApiService` - 21 edges
7. `MemberRepositoryTest` - 19 edges
8. `UserDto` - 16 edges
9. `UpdateManager` - 16 edges
10. `AddressSearchResult` - 15 edges

## Surprising Connections (you probably didn't know these)
- `MainAppScreen()` --calls--> `SplashScreen()`  [INFERRED]
  android/app/src/main/java/com/trubus/tams/ui/screens/MainAppScreen.kt → android/app/src/main/java/com/trubus/tams/ui/screens/SplashScreen.kt
- `MainAppScreen()` --calls--> `UpdateDialog()`  [INFERRED]
  android/app/src/main/java/com/trubus/tams/ui/screens/MainAppScreen.kt → android/app/src/main/java/com/trubus/tams/ui/screens/UpdateDialog.kt
- `MemberRootScreen()` --calls--> `MemberOutletScreen()`  [INFERRED]
  android/app/src/main/java/com/trubus/tams/ui/screens/MainAppScreen.kt → android/app/src/main/java/com/trubus/tams/ui/screens/OutletScreen.kt
- `AdminMapScreen()` --calls--> `MapMarkerData`  [INFERRED]
  android/app/src/main/java/com/trubus/tams/ui/screens/MainAppScreen.kt → android/app/src/main/java/com/trubus/tams/ui/screens/OsmMap.kt
- `AdminMapScreen()` --calls--> `OsmMap()`  [INFERRED]
  android/app/src/main/java/com/trubus/tams/ui/screens/MainAppScreen.kt → android/app/src/main/java/com/trubus/tams/ui/screens/OsmMap.kt

## Import Cycles
- None detected.

## Communities (80 total, 9 thin omitted)

### Community 0 - "ApiResponse"
Cohesion: 0.11
Nodes (11): ApiService, create(), Response, ApiResponse, HistoryDatesResponseDto, LocationStatusDto, LocationUpdateResponse, LoginData (+3 more)

### Community 1 - "MainViewModel"
Cohesion: 0.06
Nodes (8): TrackedLocationSnapshot, Intent, Job, StateFlow, MainViewModel, AndroidViewModel, message, success

### Community 2 - "MemberLocationService"
Cohesion: 0.06
Nodes (21): AlarmManager, android, ActivityLogRepository, SharedPreferences, RemoteConfigRepository, Intent, Job, Location (+13 more)

### Community 3 - "MainAppScreen.kt"
Cohesion: 0.11
Nodes (40): HistoryPointDto, HistoryResponseDto, MemberCurrentLocationDto, ActiveMemberListItem(), ActiveMemberScreen(), AdminDashboard(), AdminHistoryScreen(), AdminMapScreen() (+32 more)

### Community 4 - "TAMS Web"
Cohesion: 0.05
Nodes (36): App workflow, Architecture & Reliability Principles, Background Tracking Reliability, Building & Running, Folder structure, Package layout (`app/src/main/java/com/trubus/tams/`), Remote Management, TAMS Android (+28 more)

### Community 5 - "MemberRepository"
Cohesion: 0.12
Nodes (13): UserDto, Response, Result, SharedPreferences, MemberRepository, SessionInvalidException, TrackingNotAllowedException, BootCompletedReceiver (+5 more)

### Community 6 - "UpdateManager"
Cohesion: 0.11
Nodes (18): VersionInfoDto, Result, UpdateRepository, Available, DownloadFailed, Downloading, Intent, SharedPreferences (+10 more)

### Community 7 - "AddressSearchResult"
Cohesion: 0.12
Nodes (9): AddressSearchService, CacheEntry, ForwardGeocodingProvider, AddressSearchResult, NominatimSearchService, PhotonSearchService, AddressSearchServiceTest, FakeProvider (+1 more)

### Community 8 - "outlet.js"
Cohesion: 0.11
Nodes (24): applySearchNow(), approveOutlet(), assignedMemberText(), capitalize(), clearErrors(), ensurePickerMap(), formatDistance(), formatDwell() (+16 more)

### Community 9 - "FakeOfflineLocationDao"
Cohesion: 0.12
Nodes (7): AppDatabase, getDatabase(), Context, OfflineLocationDao, OfflineLocation, FakeOfflineLocationDao, RoomDatabase

### Community 10 - "functions.php"
Cohesion: 0.13
Nodes (14): e(), json_response(), PDO, redirect(), remote_management_definitions(), remote_management_values(), is_logged_in(), require_login() (+6 more)

### Community 11 - "MemberRepositoryTest"
Cohesion: 0.18
Nodes (3): Response, MemberRepositoryTest, okhttp3

### Community 12 - "app.js"
Cohesion: 0.22
Nodes (11): csrfToken(), formatDate(), formatDateTime(), getJson(), getToastStack(), handleResponse(), postForm(), postJson() (+3 more)

### Community 13 - "OutletScreen.kt"
Cohesion: 0.27
Nodes (13): OutletDto, Color, GeoPoint, Modifier, MapRecenterRequest, MemberOutletScreen(), OutletCard(), OutletFormScreen() (+5 more)

### Community 15 - "members.js"
Cohesion: 0.24
Nodes (9): capitalize(), clearErrors(), loadMembers(), openAddModal(), openEditModal(), renderPagination(), renderRows(), showErrors() (+1 more)

### Community 16 - ".download"
Cohesion: 0.33
Nodes (6): ApkDownloadManager, DownloadProgress, Failed, InProgress, Success, Flow

### Community 17 - "live_tracking.js"
Cohesion: 0.40
Nodes (10): ensureMap(), movementSnippet(), pollOnce(), populateDetailCard(), renderActiveList(), renderMarkers(), renderSummary(), selectMember() (+2 more)

### Community 18 - "history.js"
Cohesion: 0.38
Nodes (8): ensureMap(), fetchHistory(), loadAvailableDatesAndRender(), openCalendar(), pad(), renderCalendarGrid(), renderHistory(), showPointDetail()

### Community 20 - "api.php"
Cohesion: 0.22
Nodes (3): confirmOutletVisit(), getRemoteManagementInt(), PDO

### Community 21 - "OsmMap"
Cohesion: 0.47
Nodes (8): gapRoutePolyline(), GeoPoint, Modifier, MapMarkerData, nearestRoutePointIndex(), normalRoutePolyline(), OsmMap(), Polyline

### Community 23 - ".onCreate"
Cohesion: 0.29
Nodes (4): MainActivity, MyApplicationTheme(), Bundle, ComponentActivity

### Community 24 - "UnitJsonAdapter"
Cohesion: 0.33
Nodes (4): UnitJsonAdapter, JsonAdapter, JsonReader, JsonWriter

### Community 26 - "ShadowWorkManagerInitializer"
Cohesion: 0.33
Nodes (3): Context, ShadowWorkManagerInitializer, WorkManager

### Community 28 - ".getCurrentLocation"
Cohesion: 0.40
Nodes (3): Context, Location, OneShotLocationProvider

### Community 30 - "member_log.js"
Cohesion: 0.70
Nodes (4): detailText(), loadLogs(), renderPagination(), renderRows()

### Community 33 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **31 isolated node(s):** `None`, `Overview`, `Project structure`, `How Android and Web relate`, `Technology stack` (+26 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **9 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MemberRepository` connect `MemberRepository` to `ApiResponse`, `MainViewModel`, `MemberLocationService`, `FakeOfflineLocationDao`, `MemberRepositoryTest`?**
  _High betweenness centrality (0.119) - this node is a cross-community bridge._
- **Why does `MainViewModel` connect `MainViewModel` to `MemberRepository`, `MainAppScreen.kt`, `OutletScreen.kt`, `UpdateManager`?**
  _High betweenness centrality (0.112) - this node is a cross-community bridge._
- **Why does `UserDto` connect `MemberRepository` to `ApiResponse`, `MainViewModel`, `MainAppScreen.kt`?**
  _High betweenness centrality (0.049) - this node is a cross-community bridge._
- **What connects `None`, `Overview`, `Project structure` to the rest of the system?**
  _31 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `ApiResponse` be split into smaller, more focused modules?**
  _Cohesion score 0.11312217194570136 - nodes in this community are weakly interconnected._
- **Should `MainViewModel` be split into smaller, more focused modules?**
  _Cohesion score 0.06292517006802721 - nodes in this community are weakly interconnected._
- **Should `MemberLocationService` be split into smaller, more focused modules?**
  _Cohesion score 0.06382978723404255 - nodes in this community are weakly interconnected._