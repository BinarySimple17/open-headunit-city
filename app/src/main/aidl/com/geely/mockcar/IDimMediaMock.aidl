package com.geely.mockcar;

// Cross-app AIDL surface for the media-card stand-in (F014). This file is byte-identical in
// :app (app/src/main/aidl/...) and :mockcar — keep them in sync (same rule as ICarFunctionMock).
//
// The bundle keys are the IPlaybackInfo getter names; the stand-in reads exactly what the
// real cluster would pull through the proxy:
//   getTitle / getArtist / getAlbum            — String
//   getArtwork / getNextArtwork / getPreviousArtwork / getLyric / getMediaPath — String (uri)
//   getUUID                                    — String
//   getDuration                                — Long
//   getPlaybackStatus                          — Int (1 = playing)
//   getSourceType / getFavoriteState / getLoopMode / getRadioMode /
//       getPlayingItemPositionInQueue          — Int
//   getRadioFrequency / getRadioStationName / getLyricContent / getCurrentLyricSentence — String
// A bundle without getTitle is the empty card — "clear the display".
interface IDimMediaMock {
    /** Stores a full card, or the empty one = clear; true when accepted. */
    boolean updatePlaybackInfo(in Bundle card);

    /** Position bar only — same-track ticks never re-send the whole card. */
    void updateCurrentProgress(long positionMs);

    /** Liveness probe. */
    boolean isAlive();
}
