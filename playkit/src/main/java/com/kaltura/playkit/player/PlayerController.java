/*
 * ============================================================================
 * Copyright (C) 2017 Kaltura Inc.
 *
 * Licensed under the AGPLv3 license, unless a different license for a
 * particular library is specified in the applicable library path.
 *
 * You may obtain a copy of the License at
 * https://www.gnu.org/licenses/agpl-3.0.html
 * ============================================================================
 */

package com.kaltura.playkit.player;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.ViewGroup;

import com.kaltura.playkit.AdsListener;
import com.kaltura.playkit.Assert;
import com.kaltura.playkit.PKController;
import com.kaltura.playkit.PKError;
import com.kaltura.playkit.PKEvent;
import com.kaltura.playkit.PKLog;
import com.kaltura.playkit.PKMediaConfig;
import com.kaltura.playkit.PKMediaEntry;
import com.kaltura.playkit.PKMediaFormat;
import com.kaltura.playkit.PKMediaSource;
import com.kaltura.playkit.Player;
import com.kaltura.playkit.PlayerEvent;
import com.kaltura.playkit.PlayerListener;
import com.kaltura.playkit.Post;
import com.kaltura.playkit.player.metadata.PKMetadata;
import com.kaltura.playkit.player.vr.VRPKMediaEntry;
import com.kaltura.playkit.utils.Consts;

import java.util.List;
import java.util.UUID;

import static com.kaltura.playkit.PKMediaFormat.wvm;
import static com.kaltura.playkit.utils.Consts.MILLISECONDS_MULTIPLIER;

/**
 * @hide
 */

public class PlayerController implements Player {

    private static final PKLog log = PKLog.get("PlayerController");
    private Context context;
    private PKMediaConfig mediaConfig;
    private PKMediaSourceConfig sourceConfig;
    private PlayerSettings playerSettings = new PlayerSettings();
    private final Runnable updateProgressAction = initProgressAction();

    private PlayerEngine player;
    private PlayerEngineType currentPlayerType = PlayerEngineType.Unknown;

    private PlayerView rootPlayerView;
    private PlayerView playerEngineView;

    private String sessionId;
    private UUID playerSessionId = UUID.randomUUID();

    private long targetSeekPosition;
    private boolean isNewEntry = true;
    private boolean isPlayerStopped;


    private PKEvent.Listener eventListener;
    @NonNull private Post.Target postTarget;
    private PlayerEngine.EventListener eventTrigger = initEventListener();
    private PlayerEngine.StateChangedListener stateChangedListener = initStateChangeListener();

    public PlayerController(Context context, @NonNull Post.Target postTarget) {
        this.context = context;
        this.postTarget = postTarget;
        initializeRootPlayerView();
    }

    private void initializeRootPlayerView() {
        this.rootPlayerView = new PlayerView(context) {
            @Override
            public void hideVideoSurface() {
                setVideoSurfaceVisibility(false);
            }

            @Override
            public void showVideoSurface() {
                setVideoSurfaceVisibility(true);
            }

            @Override
            public void hideVideoSubtitles() {
                setVideoSubtitlesVisibility(false);

            }

            @Override
            public void showVideoSubtitles() {
                setVideoSubtitlesVisibility(true);

            }
        };
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        this.rootPlayerView.setLayoutParams(lp);
    }

    private void setVideoSurfaceVisibility(boolean isVisible) {
        String visibilityFunction = "showVideoSurface";
        if (!isVisible) {
            visibilityFunction = "hideVideoSurface";
        }

        if (player == null) {
            log.w("Error in " + visibilityFunction + " player is null");
            return;
        }

        PlayerView playerView = player.getView();
        if (playerView != null) {
            if (isVisible) {
                playerView.showVideoSurface();
            } else {
                playerView.hideVideoSurface();
            }
        } else {
            log.w("Error in " + visibilityFunction + " playerView is null");
        }
    }

    private void setVideoSubtitlesVisibility(boolean isVisible) {
        String visibilityFunction = "showVideoSubtitles";
        if (!isVisible) {
            visibilityFunction = "hideVideoSubtitles";
        }

        if (player == null) {
            log.w("Error in " + visibilityFunction + " player is null");
            return;
        }

        PlayerView playerView = player.getView();
        if (playerView != null) {
            if (isVisible) {
                playerView.showVideoSubtitles();
            } else {
                playerView.hideVideoSubtitles();
            }
        } else {
            log.w("Error in " + visibilityFunction + " playerView is null");
        }
    }

    @Override
    public Player.Settings getSettings() {
        return playerSettings;
    }

    public void prepare(@NonNull PKMediaConfig mediaConfig) {
        if (sourceConfig == null) {
            log.e("source config not found. Can not prepare source.");
            return;
        }

        boolean is360Supported = mediaConfig.getMediaEntry() instanceof VRPKMediaEntry && playerSettings.isVRPlayerEnabled();
        PlayerEngineType incomingPlayerType = PlayerEngineFactory.selectPlayerType(sourceConfig.mediaSource.getMediaFormat(), is360Supported);

        switchPlayersIfRequired(incomingPlayerType);

        if (assertPlayerIsNotNull("prepare()")) {
            player.load(sourceConfig);
        }
    }

    /**
     * Responsible for preparing source configurations before loading it to actual player.
     *
     * @param mediaConfig - the mediaConfig that holds necessary initial data.
     * @return - true if managed to create valid sourceConfiguration object,
     * otherwise will return false and notify user with the error that happened.
     */
    public boolean setMedia(PKMediaConfig mediaConfig) {
        log.v("setMedia");
        if (!isNewEntry) {
            isNewEntry = true;
            stop();
        }

        sessionId = generateSessionId();
        if (playerSettings.getContentRequestAdapter() != null) {
            playerSettings.getContentRequestAdapter().updateParams(this);
        }

        this.mediaConfig = mediaConfig;
        PKMediaSource source = SourceSelector.selectSource(mediaConfig.getMediaEntry(), playerSettings.getPreferredMediaFormat());

        if (source == null) {
            sendErrorMessage(PKPlayerErrorType.SOURCE_SELECTION_FAILED, "No playable source found for entry", null);
            return false;
        }

        initSourceConfig(mediaConfig.getMediaEntry(), source);

        post(L -> L.onSourceSelected(sourceConfig.mediaSource));

        return true;
    }

    private void initSourceConfig(PKMediaEntry mediaEntry, PKMediaSource source) {
        if (mediaEntry instanceof VRPKMediaEntry) {
            VRPKMediaEntry vrEntry = (VRPKMediaEntry) mediaEntry;
            this.sourceConfig = new PKMediaSourceConfig(mediaConfig, source, playerSettings, vrEntry.getVrSettings());
        } else {
            this.sourceConfig = new PKMediaSourceConfig(mediaConfig, source, playerSettings);
        }
    }

    private String generateSessionId() {
        UUID mediaSessionId = UUID.randomUUID();
        String newSessionId = playerSessionId.toString();
        newSessionId += ":";
        newSessionId += mediaSessionId.toString();
        return newSessionId;
    }

    private void switchPlayersIfRequired(PlayerEngineType incomingPlayerType) {

        //If incomingPlayerType of the same type as current - we are good, so do nothing.
        if (currentPlayerType == incomingPlayerType) {
            return;
        }

        //Clear previous PlayerEngine.
        if (currentPlayerType != PlayerEngineType.Unknown) {
            removePlayerView();
            player.destroy();
        }

        //Initialize new PlayerEngine.
        try {
            player = PlayerEngineFactory.initializePlayerEngine(context, incomingPlayerType, playerSettings);
        } catch (PlayerEngineFactory.PlayerInitializationException e) {
            log.e(e.getMessage());
            sendErrorMessage(PKPlayerErrorType.FAILED_TO_INITIALIZE_PLAYER, e.getMessage(), e);
            if (incomingPlayerType == PlayerEngineType.VRPlayer) {
                player = new ExoPlayerWrapper(context, playerSettings);
            } else {
                return;
            }
        }
        //IMA workaround. In order to prevent flickering of the first frame
        //with ExoplayerEngine we should addPlayerView here for all playerEngines except Exoplayer.
        if (incomingPlayerType == PlayerEngineType.MediaPlayer) {
            addPlayerView();
        }
        togglePlayerListeners(true);
        currentPlayerType = incomingPlayerType;
    }

    private void addPlayerView() {
        if (playerEngineView != null) {
            return;
        }

        playerEngineView = player.getView();
        //always place playerView as first layer in view hierarchy.
        rootPlayerView.addView(playerEngineView, 0);
    }

    @Override
    public void destroy() {
        log.v("destroy");
        if (assertPlayerIsNotNull("destroy()")) {
            if (playerEngineView != null) {
                rootPlayerView.removeView(playerEngineView);
            }
            player.destroy();
            togglePlayerListeners(false);
        }
        player = null;
        mediaConfig = null;
        eventListener = null;
        currentPlayerType = PlayerEngineType.Unknown;
    }

    @Override
    public void stop() {
        log.v("stop");
        if (eventListener != null && !isPlayerStopped) {
            post(PlayerListener::onStopped);
            cancelUpdateProgress();
            isPlayerStopped = true;
            log.d("sending STOPPED event ");
            if (assertPlayerIsNotNull("stop()")) {
                player.stop();
            }
        }
    }

    private void startPlaybackFrom(long startPosition) {
        log.v("startPlaybackFrom " + startPosition);
        if (assertPlayerIsNotNull("startPlaybackFrom()")) {
            if (startPosition <= getDuration()) {
                player.startFrom(startPosition);
            } else {
                log.w("The start position is grater then duration of the video! Start position " + startPosition + ", duration " + mediaConfig.getMediaEntry().getDuration());
            }
        }
    }

    public PlayerView getView() {
        return rootPlayerView;
    }

    @Override
    public <T extends PKController> T getController(Class<T> type) {
        log.v("getController");
        if (assertPlayerIsNotNull("getController()")) {
            return player.getController(type);
        }
        return null;
    }

    public long getDuration() {
        log.v("getDuration");
        if (assertPlayerIsNotNull("getDuration()")) {
            return player.getDuration();
        }
        return Consts.TIME_UNSET;
    }

    public long getCurrentPosition() {
        log.v("getCurrentPosition");
        if (assertPlayerIsNotNull("getCurrentPosition()")) {
            return player.getCurrentPosition();
        }
        return Consts.POSITION_UNSET;
    }

    public long getBufferedPosition() {
        log.v("getBufferedPosition");
        if (assertPlayerIsNotNull("getBufferedPosition()")) {
            return player.getBufferedPosition();
        }
        return Consts.POSITION_UNSET;

    }

    public void seekTo(long position) {
        log.v("seek to " + position);
        if (assertPlayerIsNotNull("seekTo()")) {
            targetSeekPosition = position;
            player.seekTo(position);
        }
    }

    public void play() {
        log.v("play");
        if (assertPlayerIsNotNull("play()")) {
            addPlayerView();
            player.play();
        }
    }

    public void pause() {
        log.v("pause");
        if (assertPlayerIsNotNull("pause()")) {
            player.pause();
        }
    }

    @Override
    public void replay() {
        log.v("replay");
        if (assertPlayerIsNotNull("replay()")) {
            player.replay();
        }
    }

    @Override
    public void setVolume(float volume) {
        log.v("setVolume");
        if (assertPlayerIsNotNull("setVolume()")) {
            player.setVolume(volume);
        }
    }

    @Override
    public boolean isPlaying() {
        log.v("isPlaying");
        if (assertPlayerIsNotNull("isPlaying()")) {
            return player.isPlaying();
        }
        return false;
    }

    private void togglePlayerListeners(boolean enable) {
        log.v("togglePlayerListeners");
        if (assertPlayerIsNotNull("togglePlayerListeners()")) {
            if (enable) {
                player.setEventListener(eventTrigger);
                player.setStateChangedListener(stateChangedListener);
            } else {
                player.setEventListener(null);
                player.setStateChangedListener(null);
            }
        }
    }

    @Override
    public PKEvent.Listener addEventListener(@NonNull PKEvent.Listener listener, Enum... events) {
        Assert.shouldNeverHappen();
        return null;
    }

    @Override
    public void removeEventListener(@NonNull PKEvent.Listener listener, Enum... events) {
        Assert.shouldNeverHappen();
    }

    @Override
    public PKEvent.Listener addStateChangeListener(@NonNull PKEvent.Listener listener) {
        Assert.shouldNeverHappen();
        return null;
    }

    @Override
    public void removeStateChangeListener(@NonNull PKEvent.Listener listener) {
        Assert.shouldNeverHappen();
    }

    @Override
    public void removeListener(@NonNull PKEvent.Listener listener) {
        Assert.shouldNeverHappen();
    }

    @Override
    public void updatePluginConfig(@NonNull String pluginName, @Nullable Object pluginConfig) {
        Assert.shouldNeverHappen();
    }

    @Override
    public void onApplicationPaused() {
        log.v("onApplicationPaused");
        if (assertPlayerIsNotNull("onApplicationPaused()")) {
            if (player.isPlaying()) {
                player.pause();
            }
            cancelUpdateProgress();
            player.release();
            togglePlayerListeners(false);
        }
    }

    @Override
    public void onApplicationResumed() {
        log.v("onApplicationResumed");
        if (assertPlayerIsNotNull("onApplicationResumed()")) {
            player.restore();
            updateProgress();
        }
        togglePlayerListeners(true);
        prepare(mediaConfig);
    }

    @Override
    public void onOrientationChanged() {
        log.v("onOrientationChanged");
        if (assertPlayerIsNotNull("onOrientationChanged()")) {
            player.onOrientationChanged();
        }
    }

    @Override
    public void changeTrack(String uniqueId) {
        log.v("changeTrack");
        if (assertPlayerIsNotNull("changeTrack()")) {
            player.changeTrack(uniqueId);
        }
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public boolean isLive() {
        log.v("isLive");
        if (assertPlayerIsNotNull("isLive()")) {
            return player.isLive();
        }
        return false;
    }

    @Override
    public PKMediaFormat getMediaFormat() {
        if (sourceConfig != null) {
            return sourceConfig.mediaSource.getMediaFormat();
        }
        return null;
    }

    @Override
    public void setPlaybackRate(float rate) {
        log.v("setPlaybackRate");
        if (assertPlayerIsNotNull("setPlaybackRate()")) {
            player.setPlaybackRate(rate);
        }
    }

    @Override
    public float getPlaybackRate() {
        log.v("getPlaybackRate");
        if (assertPlayerIsNotNull("getPlaybackRate()")) {
            return player.getPlaybackRate();
        }
        return Consts.PLAYBACK_SPEED_RATE_UNKNOWN;
    }


    @Override
    public void updateSubtitleStyle(SubtitleStyleSettings subtitleStyleSettings) {
        log.v("updateSubtitleStyle");
        if (assertPlayerIsNotNull("updateSubtitleStyle")) {
            player.updateSubtitleStyle(subtitleStyleSettings);
        }
    }

    @Override
    public PlayerListener addPlayerListener(PlayerListener playerListener) {
        Assert.shouldNeverHappen();
        return null;
    }

    @Override
    public AdsListener addAdsListener(AdsListener adsListener) {
        Assert.shouldNeverHappen();
        return null;
    }

    private boolean assertPlayerIsNotNull(String methodName) {
        if (player != null) {
            return true;
        }
        String nullPlayerMsgFormat = "Attempt to invoke '%s' on null instance of the player engine";
        log.w(String.format(nullPlayerMsgFormat, methodName));
        return false;
    }

    private boolean shouldSwitchBetweenPlayers(PKMediaSource newSource) {

        PKMediaFormat currentMediaFormat = newSource.getMediaFormat();
        return currentMediaFormat != wvm && player instanceof MediaPlayerWrapper ||
                currentMediaFormat == wvm && player instanceof ExoPlayerWrapper;

    }

    private void removePlayerView() {
        togglePlayerListeners(false);
        rootPlayerView.removeView(playerEngineView);
        playerEngineView = null;
    }

    private void sendErrorMessage(Enum errorType, String errorMessage, @Nullable Exception exception) {
        log.e(errorMessage);

        post(L->L.onError(new PKError(errorType, errorMessage, exception)));
    }

    private void updateProgress() {

        long position;
        long duration;

        if (player == null || player.getView() == null) {
            return;
        }

        position = player.getCurrentPosition();
        duration = player.getDuration();

        if (position > 0 && duration > 0) {
            post(L->L.onPlayheadUpdated(position, duration));
        }

        // Cancel any pending updates and schedule a new one if necessary.
        player.getView().removeCallbacks(updateProgressAction);
        player.getView().postDelayed(updateProgressAction, Consts.DEFAULT_PLAYHEAD_UPDATE_MILI);

    }

    private Runnable initProgressAction() {
        return new Runnable() {
            @Override
            public void run() {
                updateProgress();
            }
        };
    }

    private void cancelUpdateProgress() {
        if (player != null && player.getView() != null) {
            player.getView().removeCallbacks(updateProgressAction);
        }
    }

    public void setEventListener(PKEvent.Listener eventListener) {
        this.eventListener = eventListener;
    }

    private void post(Post<PlayerListener> post) {
        postTarget.postPlayerEvent(post);
    }

    private PlayerEngine.EventListener initEventListener() {
        return eventType -> {
            if (eventListener != null) {

                switch (eventType) {
                    case PLAYING:
                        updateProgress();
                        post(PlayerListener::onPlaying);
                        break;
                    case PAUSE:
                        post(PlayerListener::onPause);
                        cancelUpdateProgress();
                        break;
                    case ENDED:
                        post(PlayerListener::onEnded);
                        cancelUpdateProgress();
                        break;
                    case CAN_PLAY:
                        post(PlayerListener::onCanPlay);
                        break;
                    case DURATION_CHANGE:
                        post(L->L.onDurationChanged(getDuration()));
                        if (getDuration() != Consts.TIME_UNSET && isNewEntry) {
                            if (mediaConfig.getStartPosition() != null &&
                                    ((isLiveMediaWithDvr() && mediaConfig.getStartPosition() == 0) ||
                                            mediaConfig.getStartPosition() > 0)) {
                                startPlaybackFrom(mediaConfig.getStartPosition() * MILLISECONDS_MULTIPLIER);
                            }
                            isNewEntry = false;
                            isPlayerStopped = false;
                        }
                        break;
                    case TRACKS_AVAILABLE:
                        post(L->L.onTracksAvailable(player.getPKTracks()));
                        break;
                    case VOLUME_CHANGED:
                        post(L->L.onVolumeChanged(player.getVolume()));
                        break;
                    case PLAYBACK_INFO_UPDATED:
                        post(L->L.onPlaybackInfoUpdated(player.getPlaybackInfo()));
                        break;
                    case ERROR: {
                        final PKError currentError = player.getCurrentError();
                        if (currentError == null) {
                            log.e("can not send error event");
                            return;
                        }
                        post(L -> L.onError(currentError));
                        cancelUpdateProgress();
                        break;
                    }
                    case METADATA_AVAILABLE: {
                        final List<PKMetadata> metadata = player.getMetadata();
                        if (metadata == null || metadata.isEmpty()) {
                            log.w("METADATA_AVAILABLE event received, but player engine has no metadata.");
                            return;
                        }
                        post(L -> L.onMetadataAvailable(metadata));
                        break;
                    }
                    case SEEKING:
                        post(L -> L.onSeeking(targetSeekPosition));
                        break;
                    case VIDEO_TRACK_CHANGED:
                        post(L -> L.onVideoTrackChanged(((VideoTrack) player.getLastSelectedTrack(Consts.TRACK_TYPE_VIDEO))));
                        break;
                    case AUDIO_TRACK_CHANGED:
                        post(L -> L.onAudioTrackChanged((AudioTrack) player.getLastSelectedTrack(Consts.TRACK_TYPE_AUDIO)));
                        break;
                    case TEXT_TRACK_CHANGED:
                        post(L -> L.onTextTrackChanged((TextTrack) player.getLastSelectedTrack(Consts.TRACK_TYPE_TEXT)));
                        break;
                    case PLAYBACK_RATE_CHANGED:
                        post(L -> L.onPlaybackRateChanged(player.getPlaybackRate()));
                        break;
                    case SUBTITLE_STYLE_CHANGED:
                        post(L -> L.onSubtitlesStyleChanged(playerSettings.getSubtitleStyleSettings().getStyleName()));
                        break;
                    case LOADED_METADATA:
                        post(PlayerListener::onLoadedMetadata);
                        break;
                    case PLAY:
                        post(PlayerListener::onPlay);
                        break;
                    case SEEKED:
                        post(PlayerListener::onSeeked);
                        break;
                    case REPLAY:
                        post(PlayerListener::onReplay);
                        break;
                }
            }
        };
    }

    private boolean isLiveMediaWithDvr() {
        return (PKMediaEntry.MediaEntryType.DvrLive == sourceConfig.mediaEntryType);
    }

    private PlayerEngine.StateChangedListener initStateChangeListener() {
        return (oldState, newState) -> {
            postTarget.postPlayerEvent(L -> L.onPlayerStateChanged(newState, oldState));
        };
    }
}
