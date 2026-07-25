---
name: Lyrics Architecture
description: Synced lyrics implementation — provider chain, cache layers, state machine, UI features
---

## Rule
All lyrics logic follows strict layering: UI → ViewModel → LyricsRepository → {memory cache, Room, DataSources}.

**Why:** Enforces separation so a third provider can be added later without touching the ViewModel or UI.

## Provider priority (within LyricsRepository.getLyrics)
1. In-process memory cache (`companion object ConcurrentHashMap`)
2. Room disk cache (7-day TTL; 30-min negative TTL)
3. YTM timedLyricsRenderer (synced LRC)
4. Better Lyrics API (synced LRC)
5. LRCLIB (synced, with 3-attempt exponential back-off on 429/5xx)
6. YTM plain text
7. LRCLIB plain text

## LyricsResult sealed class (repository output)
Synced, Plain, NotFound, Instrumental, Offline

## LyricsScreenState sealed class (UI layer)
LoadingCache, LoadingSynced, Synced, Plain, Instrumental, NotFound, Offline, Error(retryable)

## Key behaviours
- **300 ms debounce** in `observeSongChanges()` before firing a fetch (absorbs rapid skip spam)
- **Memory cache peek** in ViewModel before launching any coroutine → instant re-render on revisit
- **onNetworkFetch callback** passed into `getLyrics()` so ViewModel can transition Loading→LoadingSynced
- **Exponential back-off**: `LyricsTransientException` thrown by LrcLibDataSource on 429/5xx; caught by `retryOnTransient()` in repository
- **Prefetch next track** via `schedulePrefetch()` after successful current-track load
- **Global offset** persisted via DataStore key `lyrics-global-offset` (±5000 ms range, ±100 ms steps)
- **NFKC normalisation** applied to title and artist before all API queries

## How to apply
When adding a new lyrics provider: implement a new DataSource class in `data/datasources/`, add it to the parallel async block in `LyricsRepository.getLyrics()`, and insert it at the appropriate priority position. No ViewModel or UI changes needed.
