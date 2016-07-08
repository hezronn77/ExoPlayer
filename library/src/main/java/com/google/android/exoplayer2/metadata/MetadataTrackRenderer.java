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
package com.google.android.exoplayer2.metadata;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DecoderInputBuffer;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.TrackRenderer;
import com.google.android.exoplayer2.TrackStream;
import com.google.android.exoplayer2.util.Assertions;

import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Looper;
import android.os.Message;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * A {@link TrackRenderer} for metadata embedded in a media stream.
 *
 * @param <T> The type of the metadata.
 */
public final class MetadataTrackRenderer<T> extends TrackRenderer implements Callback {

  /**
   * An interface for components that process metadata.
   *
   * @param <T> The type of the metadata.
   */
  public interface MetadataRenderer<T> {

    /**
     * Invoked each time there is a metadata associated with current playback time.
     *
     * @param metadata The metadata to process.
     */
    void onMetadata(T metadata);

  }

  private static final int MSG_INVOKE_RENDERER = 0;

  private final MetadataParser<T> metadataParser;
  private final MetadataRenderer<T> metadataRenderer;
  private final Handler metadataHandler;
  private final FormatHolder formatHolder;
  private final DecoderInputBuffer buffer;

  private boolean inputStreamEnded;
  private long pendingMetadataTimestamp;
  private T pendingMetadata;

  /**
   * @param metadataParser A parser for parsing the metadata.
   * @param metadataRenderer The metadata renderer to receive the parsed metadata.
   * @param metadataRendererLooper The looper associated with the thread on which metadataRenderer
   *     should be invoked. If the renderer makes use of standard Android UI components, then this
   *     should normally be the looper associated with the applications' main thread, which can be
   *     obtained using {@link android.app.Activity#getMainLooper()}. Null may be passed if the
   *     renderer should be invoked directly on the player's internal rendering thread.
   */
  public MetadataTrackRenderer(MetadataParser<T> metadataParser,
      MetadataRenderer<T> metadataRenderer, Looper metadataRendererLooper) {
    this.metadataParser = Assertions.checkNotNull(metadataParser);
    this.metadataRenderer = Assertions.checkNotNull(metadataRenderer);
    this.metadataHandler = metadataRendererLooper == null ? null
        : new Handler(metadataRendererLooper, this);
    formatHolder = new FormatHolder();
    buffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
  }

  @Override
  public int getTrackType() {
    return C.TRACK_TYPE_METADATA;
  }

  @Override
  protected int supportsFormat(Format format) {
    return metadataParser.canParse(format.sampleMimeType) ? TrackRenderer.FORMAT_HANDLED
        : TrackRenderer.FORMAT_UNSUPPORTED_TYPE;
  }

  @Override
  protected void onReset(long positionUs, boolean joining) {
    pendingMetadata = null;
    inputStreamEnded = false;
  }

  @Override
  protected void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    if (!inputStreamEnded && pendingMetadata == null) {
      buffer.clear();
      int result = readSource(formatHolder, buffer);
      if (result == TrackStream.BUFFER_READ) {
        if (buffer.isEndOfStream()) {
          inputStreamEnded = true;
        } else {
          pendingMetadataTimestamp = buffer.timeUs;
          try {
            buffer.flip();
            ByteBuffer bufferData = buffer.data;
            pendingMetadata = metadataParser.parse(bufferData.array(), bufferData.limit());
          } catch (IOException e) {
            throw ExoPlaybackException.createForRenderer(e, getIndex());
          }
        }
      }
    }

    if (pendingMetadata != null && pendingMetadataTimestamp <= positionUs) {
      invokeRenderer(pendingMetadata);
      pendingMetadata = null;
    }
  }

  @Override
  protected void onDisabled() {
    pendingMetadata = null;
    super.onDisabled();
  }

  @Override
  protected boolean isEnded() {
    return inputStreamEnded;
  }

  @Override
  protected boolean isReady() {
    return true;
  }

  private void invokeRenderer(T metadata) {
    if (metadataHandler != null) {
      metadataHandler.obtainMessage(MSG_INVOKE_RENDERER, metadata).sendToTarget();
    } else {
      invokeRendererInternal(metadata);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public boolean handleMessage(Message msg) {
    switch (msg.what) {
      case MSG_INVOKE_RENDERER:
        invokeRendererInternal((T) msg.obj);
        return true;
    }
    return false;
  }

  private void invokeRendererInternal(T metadata) {
    metadataRenderer.onMetadata(metadata);
  }

}