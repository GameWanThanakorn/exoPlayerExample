/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.main;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ErrorMessageProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSchemeDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider;
import androidx.media3.exoplayer.drm.ExoMediaDrm;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback;
import androidx.media3.exoplayer.drm.UnsupportedDrmException;
import androidx.media3.exoplayer.ima.ImaAdsLoader;
import com.google.common.primitives.Ints;
import java.util.Map;
import java.util.UUID;

import androidx.media3.exoplayer.ima.ImaServerSideAdInsertionMediaSource;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.exoplayer.util.DebugTextViewHelper;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** An activity that plays media using {@link ExoPlayer}. */
public class PlayerActivity extends AppCompatActivity
    implements OnClickListener, PlayerView.ControllerVisibilityListener {

  // Saved instance state keys.

  private static final String KEY_TRACK_SELECTION_PARAMETERS = "track_selection_parameters";
  private static final String KEY_SERVER_SIDE_ADS_LOADER_STATE = "server_side_ads_loader_state";
  private static final String KEY_ITEM_INDEX = "item_index";
  private static final String KEY_POSITION = "position";
  private static final String KEY_AUTO_PLAY = "auto_play";

  protected PlayerView playerView;
  protected LinearLayout debugRootView;
  protected TextView debugTextView;
  protected @Nullable ExoPlayer player;

  private boolean isShowingTrackSelectionDialog;
  private boolean useL3Fallback = false;
  private Button selectTracksButton;
  private DataSource.Factory dataSourceFactory;
  private List<MediaItem> mediaItems;
  private TrackSelectionParameters trackSelectionParameters;
  private DebugTextViewHelper debugViewHelper;
  private Tracks lastSeenTracks;
  private boolean startAutoPlay;
  private int startItemIndex;
  private long startPosition;

  // For ad playback only.

  @Nullable private AdsLoader clientSideAdsLoader;

  @Nullable private ImaServerSideAdInsertionMediaSource.AdsLoader serverSideAdsLoader;

  private ImaServerSideAdInsertionMediaSource.AdsLoader.@MonotonicNonNull State
      serverSideAdsLoaderState;

  // Activity lifecycle.

  @OptIn(markerClass = UnstableApi.class)
  private static boolean hasBrokenMtkSecureDecoder() {
    try {
      List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> decoderInfos =
          androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(
              "video/avc", /* secure= */ true, /* tunneling= */ false);
      for (androidx.media3.exoplayer.mediacodec.MediaCodecInfo info : decoderInfos) {
        if ("c2.mtk.avc.decoder.secure".equals(info.name)) {
          Log.w("PlayerActivity", "Detected broken MTK secure decoder, will use L3 fallback");
          return true;
        }
      }
    } catch (Exception e) {
      Log.w("PlayerActivity", "Could not query decoders for MTK detection", e);
    }
    return false;
  }

  /**
   * Mark the video SurfaceView as secure so that screen capture shows black only on
   * the video layer while player controls and debug info remain visible normally.
   */
  @OptIn(markerClass = UnstableApi.class)
  private void applySecureWindowFlag(boolean secure) {
    android.view.View videoSurface = playerView.getVideoSurfaceView();
    if (videoSurface instanceof android.view.SurfaceView) {
      ((android.view.SurfaceView) videoSurface).setSecure(secure);
      Log.d("PlayerActivity", "SurfaceView.setSecure(" + secure + ")");
    }
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    dataSourceFactory = DemoUtil.getDataSourceFactory(/* context= */ this);
    if (!useL3Fallback && hasBrokenMtkSecureDecoder()) {
      useL3Fallback = true;
    }

    setContentView();
    debugRootView = findViewById(R.id.controls_root);
    debugTextView = findViewById(R.id.debug_text_view);
    selectTracksButton = findViewById(R.id.select_tracks_button);
    selectTracksButton.setOnClickListener(this);

    playerView = findViewById(R.id.player_view);
    playerView.setControllerVisibilityListener(this);
    playerView.setErrorMessageProvider(new PlayerErrorMessageProvider());
    playerView.requestFocus();
    // Secure only the video surface layer so screen capture shows black on the video
    // while player controls and debug info remain visible on the receiver side.
    applySecureWindowFlag(useL3Fallback);

    if (savedInstanceState != null) {
      trackSelectionParameters =
          TrackSelectionParameters.fromBundle(
              savedInstanceState.getBundle(KEY_TRACK_SELECTION_PARAMETERS));
      startAutoPlay = savedInstanceState.getBoolean(KEY_AUTO_PLAY);
      startItemIndex = savedInstanceState.getInt(KEY_ITEM_INDEX);
      startPosition = savedInstanceState.getLong(KEY_POSITION);
      restoreServerSideAdsLoaderState(savedInstanceState);
    } else {
      trackSelectionParameters = new TrackSelectionParameters.Builder().build();
      clearStartPosition();
    }
  }

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    releasePlayer();
    releaseClientSideAdsLoader();
    clearStartPosition();
    setIntent(intent);
  }

  @Override
  public void onStart() {
    super.onStart();
    if (Build.VERSION.SDK_INT > 23) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    if (Build.VERSION.SDK_INT == 23 || player == null) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (Build.VERSION.SDK_INT == 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    if (Build.VERSION.SDK_INT > 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    releaseClientSideAdsLoader();
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (grantResults.length == 0) {
      // Empty results are triggered if a permission is requested while another request was already
      // pending and can be safely ignored in this case.
      return;
    }
    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      initializePlayer();
    } else {
      showToast(R.string.storage_permission_denied);
      finish();
    }
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    updateTrackSelectorParameters();
    updateStartPosition();
    outState.putBundle(KEY_TRACK_SELECTION_PARAMETERS, trackSelectionParameters.toBundle());
    outState.putBoolean(KEY_AUTO_PLAY, startAutoPlay);
    outState.putInt(KEY_ITEM_INDEX, startItemIndex);
    outState.putLong(KEY_POSITION, startPosition);
    saveServerSideAdsLoaderState(outState);
  }

  // Activity input

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    // See whether the player view wants to handle media or DPAD keys events.
    return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
  }

  // OnClickListener methods

  @Override
  public void onClick(View view) {
    if (view == selectTracksButton
        && !isShowingTrackSelectionDialog
        && TrackSelectionDialog.willHaveContent(player)) {
      isShowingTrackSelectionDialog = true;
      TrackSelectionDialog trackSelectionDialog =
          TrackSelectionDialog.createForPlayer(
              player,
              /* onDismissListener= */ dismissedDialog -> isShowingTrackSelectionDialog = false);
      trackSelectionDialog.show(getSupportFragmentManager(), /* tag= */ null);
    }
  }

  // PlayerView.ControllerVisibilityListener implementation

  @Override
  public void onVisibilityChanged(int visibility) {
    debugRootView.setVisibility(visibility);
  }

  // Internal methods

  protected void setContentView() {
    setContentView(R.layout.player_activity);
  }

  /**
   * @return Whether initialization was successful.
   */
  protected boolean initializePlayer() {
    Intent intent = getIntent();
    if (player == null) {

      mediaItems = createMediaItems(intent);
      if (mediaItems.isEmpty()) {
        return false;
      }

      lastSeenTracks = Tracks.EMPTY;
      ExoPlayer.Builder playerBuilder =
          new ExoPlayer.Builder(/* context= */ this)
              .setMediaSourceFactory(createMediaSourceFactory());
      setRenderersFactory(
          playerBuilder, intent.getBooleanExtra(IntentUtil.PREFER_EXTENSION_DECODERS_EXTRA, false));
      player = playerBuilder.build();
      player.setTrackSelectionParameters(trackSelectionParameters);
      player.addListener(new PlayerEventListener());
      player.addAnalyticsListener(new EventLogger());
      player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
      player.setPlayWhenReady(startAutoPlay);
      playerView.setPlayer(player);
      configurePlayerWithServerSideAdsLoader();
      debugViewHelper = new DebugTextViewHelper(player, debugTextView);
      debugViewHelper.start();
    }
    boolean haveStartPosition = startItemIndex != C.INDEX_UNSET;
    if (haveStartPosition) {
      player.seekTo(startItemIndex, startPosition);
    }
    player.setMediaItems(mediaItems, /* resetPosition= */ !haveStartPosition);
    player.prepare();
    String repeatModeExtra = intent.getStringExtra(IntentUtil.REPEAT_MODE_EXTRA);
    if (repeatModeExtra != null) {
      player.setRepeatMode(IntentUtil.parseRepeatModeExtra(repeatModeExtra));
    }
    updateButtonVisibility();
    return true;
  }

  @OptIn(markerClass = UnstableApi.class) // DRM configuration
  private MediaSource.Factory createMediaSourceFactory() {
    ImaServerSideAdInsertionMediaSource.AdsLoader.Builder serverSideAdLoaderBuilder =
        new ImaServerSideAdInsertionMediaSource.AdsLoader.Builder(/* context= */ this, playerView);
    if (serverSideAdsLoaderState != null) {
      serverSideAdLoaderBuilder.setAdsLoaderState(serverSideAdsLoaderState);
    }
    serverSideAdsLoader = serverSideAdLoaderBuilder.build();
    ImaServerSideAdInsertionMediaSource.Factory imaServerSideAdInsertionMediaSourceFactory =
        new ImaServerSideAdInsertionMediaSource.Factory(
            serverSideAdsLoader,
            new DefaultMediaSourceFactory(/* context= */ this)
                .setDataSourceFactory(dataSourceFactory));

    DefaultMediaSourceFactory mediaSourceFactory =
        new DefaultMediaSourceFactory(/* context= */ this)
            .setDataSourceFactory(dataSourceFactory)
            .setLocalAdInsertionComponents(
                this::getClientSideAdsLoader, /* adViewProvider= */ playerView)
            .setServerSideAdInsertionMediaSourceFactory(imaServerSideAdInsertionMediaSourceFactory);

    if (useL3Fallback) {
      // L3 fallback: force Widevine L3 to avoid c2.mtk.avc.decoder.secure issues
      Log.d("PlayerActivity", "DRM: Using L3 fallback provider");
      DataSource.Factory httpDataSourceFactory = DemoUtil.getHttpDataSourceFactory(/* context= */ this);
      androidx.media3.exoplayer.drm.DrmSessionManagerProvider l3DrmProvider =
          mediaItem -> {
            MediaItem.DrmConfiguration drmConfiguration = mediaItem.localConfiguration != null
                ? mediaItem.localConfiguration.drmConfiguration
                : null;
            if (drmConfiguration == null) {
              return androidx.media3.exoplayer.drm.DrmSessionManager.DRM_UNSUPPORTED;
            }
            try {
              HttpMediaDrmCallback drmCallback = new HttpMediaDrmCallback(
                  drmConfiguration.licenseUri == null ? null : drmConfiguration.licenseUri.toString(),
                  httpDataSourceFactory);
              for (Map.Entry<String, String> entry : drmConfiguration.licenseRequestHeaders.entrySet()) {
                drmCallback.setKeyRequestProperty(entry.getKey(), entry.getValue());
              }
              ExoMediaDrm.Provider l3MediaProvider = uuid -> {
                try {
                  ExoMediaDrm mediaDrm = FrameworkMediaDrm.newInstance(uuid);
                  mediaDrm.setPropertyString("securityLevel", "L3");
                  Log.d("PlayerActivity", "DRM: Forced L3 security level for fallback playback");
                  return mediaDrm;
                } catch (UnsupportedDrmException e) {
                  throw new RuntimeException("Failed to create MediaDrm", e);
                }
              };
              DefaultDrmSessionManager drmSessionManager =
                  new DefaultDrmSessionManager.Builder()
                      .setUuidAndExoMediaDrmProvider(drmConfiguration.scheme, l3MediaProvider)
                      .setMultiSession(drmConfiguration.multiSession)
                      .build(drmCallback);
              drmSessionManager.setMode(DefaultDrmSessionManager.MODE_PLAYBACK,
                  drmConfiguration.getKeySetId());
              return drmSessionManager;
            } catch (Exception e) {
              Log.e("PlayerActivity", "DRM L3 setup failed", e);
              return androidx.media3.exoplayer.drm.DrmSessionManager.DRM_UNSUPPORTED;
            }
          };
      mediaSourceFactory.setDrmSessionManagerProvider(l3DrmProvider);
    }

    return mediaSourceFactory;
  }

  @OptIn(markerClass = UnstableApi.class)
  private boolean isSecureDecoderError(PlaybackException error) {
    Throwable cause = error.getCause();
    // Case 1: Decoder failed to initialize (secureDecoderRequired flag is set)
    if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        && cause instanceof DecoderInitializationException
        && ((DecoderInitializationException) cause).secureDecoderRequired) {
      return true;
    }
    // Case 2: Decoder crashed at runtime (c2.mtk.avc.decoder.secure fails during decode)
    if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) {
      while (cause != null) {
        if (cause instanceof androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException) {
          androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException decoderEx =
              (androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException) cause;
          if (decoderEx.codecInfo != null
              && decoderEx.codecInfo.name != null
              && decoderEx.codecInfo.name.contains("secure")) {
            return true;
          }
        }
        cause = cause.getCause();
      }
    }
    return false;
  }

  @OptIn(markerClass = UnstableApi.class)
  private void setRenderersFactory(
      ExoPlayer.Builder playerBuilder, boolean preferExtensionDecoders) {
    RenderersFactory renderersFactory =
        DemoUtil.buildRenderersFactory(/* context= */ this, preferExtensionDecoders, useL3Fallback);
    playerBuilder.setRenderersFactory(renderersFactory);
  }

  private void configurePlayerWithServerSideAdsLoader() {
    serverSideAdsLoader.setPlayer(player);
  }

  private List<MediaItem> createMediaItems(Intent intent) {
    String action = intent.getAction();
    boolean actionIsListView = IntentUtil.ACTION_VIEW_LIST.equals(action);
    if (!actionIsListView && !IntentUtil.ACTION_VIEW.equals(action)) {
      showToast(getString(R.string.unexpected_intent_action, action));
      finish();
      return Collections.emptyList();
    }

    List<MediaItem> mediaItems =
        createMediaItems(intent, DemoUtil.getDownloadTracker(/* context= */ this));
    for (int i = 0; i < mediaItems.size(); i++) {
      MediaItem mediaItem = mediaItems.get(i);

      if (!Util.checkCleartextTrafficPermitted(mediaItem)) {
        showToast(R.string.error_cleartext_not_permitted);
        finish();
        return Collections.emptyList();
      }
      if (Util.maybeRequestReadStoragePermission(/* activity= */ this, mediaItem)) {
        // The player will be reinitialized if the permission is granted.
        return Collections.emptyList();
      }

      MediaItem.DrmConfiguration drmConfiguration = mediaItem.localConfiguration.drmConfiguration;
      if (drmConfiguration != null) {
        if (!FrameworkMediaDrm.isCryptoSchemeSupported(drmConfiguration.scheme)) {
          showToast(R.string.error_drm_unsupported_scheme);
          finish();
          return Collections.emptyList();
        }
      }
    }
    return mediaItems;
  }

  private AdsLoader getClientSideAdsLoader(MediaItem.AdsConfiguration adsConfiguration) {
    // The ads loader is reused for multiple playbacks, so that ad playback can resume.
    if (clientSideAdsLoader == null) {
      clientSideAdsLoader = new ImaAdsLoader.Builder(/* context= */ this).build();
    }
    clientSideAdsLoader.setPlayer(player);
    return clientSideAdsLoader;
  }

  protected void releasePlayer() {
    if (player != null) {
      updateTrackSelectorParameters();
      updateStartPosition();
      releaseServerSideAdsLoader();
      debugViewHelper.stop();
      debugViewHelper = null;
      player.release();
      player = null;
      playerView.setPlayer(/* player= */ null);
      mediaItems = Collections.emptyList();
    }
    if (clientSideAdsLoader != null) {
      clientSideAdsLoader.setPlayer(null);
    } else {
      playerView.getAdViewGroup().removeAllViews();
    }
  }

  private void releaseServerSideAdsLoader() {
    serverSideAdsLoaderState = serverSideAdsLoader.release();
    serverSideAdsLoader = null;
  }

  private void releaseClientSideAdsLoader() {
    if (clientSideAdsLoader != null) {
      clientSideAdsLoader.release();
      clientSideAdsLoader = null;
      playerView.getAdViewGroup().removeAllViews();
    }
  }

  private void saveServerSideAdsLoaderState(Bundle outState) {
    if (serverSideAdsLoaderState != null) {
      outState.putBundle(KEY_SERVER_SIDE_ADS_LOADER_STATE, serverSideAdsLoaderState.toBundle());
    }
  }

  private void restoreServerSideAdsLoaderState(Bundle savedInstanceState) {
    Bundle adsLoaderStateBundle = savedInstanceState.getBundle(KEY_SERVER_SIDE_ADS_LOADER_STATE);
    if (adsLoaderStateBundle != null) {
      serverSideAdsLoaderState =
          ImaServerSideAdInsertionMediaSource.AdsLoader.State.fromBundle(adsLoaderStateBundle);
    }
  }

  private void updateTrackSelectorParameters() {
    if (player != null) {
      trackSelectionParameters = player.getTrackSelectionParameters();
    }
  }

  private void updateStartPosition() {
    if (player != null) {
      startAutoPlay = player.getPlayWhenReady();
      startItemIndex = player.getCurrentMediaItemIndex();
      startPosition = Math.max(0, player.getContentPosition());
    }
  }

  protected void clearStartPosition() {
    startAutoPlay = true;
    startItemIndex = C.INDEX_UNSET;
    startPosition = C.TIME_UNSET;
  }

  // User controls

  private void updateButtonVisibility() {
    selectTracksButton.setEnabled(player != null && TrackSelectionDialog.willHaveContent(player));
  }

  private void showControls() {
    debugRootView.setVisibility(View.VISIBLE);
  }

  private void showToast(int messageId) {
    showToast(getString(messageId));
  }

  private void showToast(String message) {
    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
  }

  private class PlayerEventListener implements Player.Listener {

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      if (playbackState == Player.STATE_ENDED) {
        showControls();
      }
      updateButtonVisibility();
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      Log.e("PlayerActivity", "Player Error: " + error.getMessage(), error);
      if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
        player.seekToDefaultPosition();
        player.prepare();
      } else if (!useL3Fallback && isSecureDecoderError(error)) {
        Log.w("PlayerActivity", "Secure decoder failed (c2.mtk.avc.decoder.secure), retrying with Widevine L3");
        useL3Fallback = true;
        applySecureWindowFlag(/* secure= */ true);
        updateStartPosition();
        releasePlayer();
        initializePlayer();
      } else {
        updateButtonVisibility();
        showControls();
      }
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void onTracksChanged(Tracks tracks) {
      updateButtonVisibility();
      if (tracks == lastSeenTracks) {
        return;
      }
      if (tracks.containsType(C.TRACK_TYPE_VIDEO)
          && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, /* allowExceedsCapabilities= */ true)) {
        showToast(R.string.error_unsupported_video);
      }
      if (tracks.containsType(C.TRACK_TYPE_AUDIO)
          && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO, /* allowExceedsCapabilities= */ true)) {
        showToast(R.string.error_unsupported_audio);
      }
      lastSeenTracks = tracks;
    }

    @OptIn(markerClass = UnstableApi.class) // For PlayerView.setTimeBarScrubbingEnabled
    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
      if (playerView == null) {
        return;
      }
      if (mediaItem == null) {
        playerView.setTimeBarScrubbingEnabled(false);
        return;
      }
      String uriScheme = mediaItem.localConfiguration.uri.getScheme();
      playerView.setTimeBarScrubbingEnabled(
          TextUtils.isEmpty(uriScheme)
              || uriScheme.equals(ContentResolver.SCHEME_FILE)
              || uriScheme.equals("asset")
              || uriScheme.equals(DataSchemeDataSource.SCHEME_DATA)
              || uriScheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE));
    }
  }

  private class PlayerErrorMessageProvider implements ErrorMessageProvider<PlaybackException> {

    @OptIn(markerClass = UnstableApi.class) // Using decoder exceptions
    @Override
    public Pair<Integer, String> getErrorMessage(PlaybackException e) {
      String errorString = getString(R.string.error_generic);
      Throwable cause = e.getCause();
      if (cause instanceof DecoderInitializationException) {
        // Special case for decoder initialization failures.
        DecoderInitializationException decoderInitializationException =
            (DecoderInitializationException) cause;
        if (decoderInitializationException.codecInfo == null) {
          if (decoderInitializationException.getCause() instanceof DecoderQueryException) {
            errorString = getString(R.string.error_querying_decoders);
          } else if (decoderInitializationException.secureDecoderRequired) {
            errorString =
                getString(
                    R.string.error_no_secure_decoder, decoderInitializationException.mimeType);
          } else {
            errorString =
                getString(R.string.error_no_decoder, decoderInitializationException.mimeType);
          }
        } else {
          errorString =
              getString(
                  R.string.error_instantiating_decoder,
                  decoderInitializationException.codecInfo.name);
        }
      }
      return Pair.create(0, errorString);
    }
  }

  private static List<MediaItem> createMediaItems(Intent intent, DownloadTracker downloadTracker) {
    List<MediaItem> mediaItems = new ArrayList<>();
    for (MediaItem item : IntentUtil.createMediaItemsFromIntent(intent)) {
      mediaItems.add(
          maybeSetDownloadProperties(
              item, downloadTracker.getDownloadRequest(item.localConfiguration.uri)));
    }
    return mediaItems;
  }

  @OptIn(markerClass = UnstableApi.class) // Using Download API
  private static MediaItem maybeSetDownloadProperties(
      MediaItem item, @Nullable DownloadRequest downloadRequest) {
    if (downloadRequest == null) {
      return item;
    }
    MediaItem.Builder builder = item.buildUpon();
    builder
        .setMediaId(downloadRequest.id)
        .setUri(downloadRequest.uri)
        .setCustomCacheKey(downloadRequest.customCacheKey)
        .setMimeType(downloadRequest.mimeType)
        .setStreamKeys(downloadRequest.streamKeys);
    @Nullable
    MediaItem.DrmConfiguration drmConfiguration = item.localConfiguration.drmConfiguration;
    if (drmConfiguration != null) {
      builder.setDrmConfiguration(
          drmConfiguration.buildUpon().setKeySetId(downloadRequest.keySetId).build());
    }
    return builder.build();
  }
}
