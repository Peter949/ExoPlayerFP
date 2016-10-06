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
package com.google.android.exoplayer2.ext.cronet;

import android.net.Uri;
import android.os.ConditionVariable;
import android.text.TextUtils;
import android.util.Log;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSourceException;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Clock;
import com.google.android.exoplayer2.util.Predicate;
import com.google.android.exoplayer2.util.SystemClock;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.chromium.net.CronetEngine;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlRequest.Status;
import org.chromium.net.UrlRequestException;
import org.chromium.net.UrlResponseInfo;

/**
 * DataSource without intermediate buffer based on Cronet API set using UrlRequest.
 * <p>This class's methods are organized in the sequence of expected calls.
 */
public class CronetDataSource extends UrlRequest.Callback implements HttpDataSource {

  /**
   * Thrown when an error is encountered when trying to open a {@link CronetDataSource}.
   */
  public static final class OpenException extends HttpDataSourceException {

    /**
     * Returns the status of the connection establishment at the moment when the error occurred, as
     * defined by {@link UrlRequest.Status}.
     */
    public final int cronetConnectionStatus;

    public OpenException(IOException cause, DataSpec dataSpec, int cronetConnectionStatus) {
      super(cause, dataSpec, TYPE_OPEN);
      this.cronetConnectionStatus = cronetConnectionStatus;
    }

    public OpenException(String errorMessage, DataSpec dataSpec, int cronetConnectionStatus) {
      super(errorMessage, dataSpec, TYPE_OPEN);
      this.cronetConnectionStatus = cronetConnectionStatus;
    }

  }

  /**
   * The default connection timeout, in milliseconds.
   */
  public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 8 * 1000;
  /**
   * The default read timeout, in milliseconds.
   */
  public static final int DEFAULT_READ_TIMEOUT_MILLIS = 8 * 1000;

  private static final String TAG = "CronetDataSource";
  private static final String CONTENT_TYPE = "Content-Type";
  private static final Pattern CONTENT_RANGE_HEADER_PATTERN =
      Pattern.compile("^bytes (\\d+)-(\\d+)/(\\d+)$");
  // The size of read buffer passed to cronet UrlRequest.read().
  private static final int READ_BUFFER_SIZE_BYTES = 32 * 1024;

  /* package */ static final int IDLE_CONNECTION = 5;
  /* package */ static final int OPENING_CONNECTION = 2;
  /* package */ static final int CONNECTED_CONNECTION = 3;
  /* package */ static final int OPEN_CONNECTION = 4;

  private final CronetEngine cronetEngine;
  private final Executor executor;
  private final Predicate<String> contentTypePredicate;
  private final TransferListener<? super CronetDataSource> listener;
  private final int connectTimeoutMs;
  private final int readTimeoutMs;
  private final boolean resetTimeoutOnRedirects;
  private final Map<String, String> requestProperties;
  private final ConditionVariable operation;
  private final ByteBuffer readBuffer;
  private final Clock clock;

  private UrlRequest currentUrlRequest;
  private DataSpec currentDataSpec;
  private UrlResponseInfo responseInfo;

  /* package */ volatile int connectionState;
  private volatile long currentConnectTimeoutMs;
  private volatile HttpDataSourceException exception;
  private volatile long contentLength;
  private volatile AtomicLong expectedBytesRemainingToRead;
  private volatile boolean hasData;
  private volatile boolean responseFinished;

  /**
   * @param cronetEngine A CronetEngine.
   * @param executor The {@link java.util.concurrent.Executor} that will perform the requests.
   * @param contentTypePredicate An optional {@link Predicate}. If a content type is rejected by the
   *     predicate then an {@link InvalidContentTypeException} is thrown from
   *     {@link #open(DataSpec)}.
   * @param listener An optional listener.
   */
  public CronetDataSource(CronetEngine cronetEngine, Executor executor,
      Predicate<String> contentTypePredicate, TransferListener<? super CronetDataSource> listener) {
    this(cronetEngine, executor, contentTypePredicate, listener, DEFAULT_CONNECT_TIMEOUT_MILLIS,
        DEFAULT_READ_TIMEOUT_MILLIS, false);
  }

  /**
   * @param cronetEngine A CronetEngine.
   * @param executor The {@link java.util.concurrent.Executor} that will perform the requests.
   * @param contentTypePredicate An optional {@link Predicate}. If a content type is rejected by the
   *     predicate then an {@link InvalidContentTypeException} is thrown from
   *     {@link #open(DataSpec)}.
   * @param listener An optional listener.
   * @param connectTimeoutMs The connection timeout, in milliseconds.
   * @param readTimeoutMs The read timeout, in milliseconds.
   * @param resetTimeoutOnRedirects Whether the connect timeout is reset when a redirect occurs.
   */
  public CronetDataSource(CronetEngine cronetEngine, Executor executor,
      Predicate<String> contentTypePredicate, TransferListener<? super CronetDataSource> listener,
      int connectTimeoutMs, int readTimeoutMs, boolean resetTimeoutOnRedirects) {
    this(cronetEngine, executor, contentTypePredicate, listener, connectTimeoutMs,
        readTimeoutMs, resetTimeoutOnRedirects, new SystemClock());
  }

  /* package */ CronetDataSource(CronetEngine cronetEngine, Executor executor,
      Predicate<String> contentTypePredicate, TransferListener<? super CronetDataSource> listener,
      int connectTimeoutMs, int readTimeoutMs, boolean resetTimeoutOnRedirects, Clock clock) {
    this.cronetEngine = Assertions.checkNotNull(cronetEngine);
    this.executor = Assertions.checkNotNull(executor);
    this.contentTypePredicate = contentTypePredicate;
    this.listener = listener;
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
    this.resetTimeoutOnRedirects = resetTimeoutOnRedirects;
    this.clock = Assertions.checkNotNull(clock);
    readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE_BYTES);
    requestProperties = new HashMap<>();
    operation = new ConditionVariable();
    connectionState = IDLE_CONNECTION;
  }

  // HttpDataSource implementation.

  @Override
  public void setRequestProperty(String name, String value) {
    synchronized (requestProperties) {
      requestProperties.put(name, value);
    }
  }

  @Override
  public void clearRequestProperty(String name) {
    synchronized (requestProperties) {
      requestProperties.remove(name);
    }
  }

  @Override
  public void clearAllRequestProperties() {
    synchronized (requestProperties) {
      requestProperties.clear();
    }
  }

  @Override
  public Map<String, List<String>> getResponseHeaders() {
    return responseInfo == null ? null : responseInfo.getAllHeaders();
  }

  @Override
  public Uri getUri() {
    return responseInfo == null ? null : Uri.parse(responseInfo.getUrl());
  }

  @Override
  public long open(DataSpec dataSpec) throws HttpDataSourceException {
    Assertions.checkNotNull(dataSpec);
    synchronized (this) {
      Assertions.checkState(connectionState == IDLE_CONNECTION, "Connection already open");
      connectionState = OPENING_CONNECTION;
    }

    operation.close();
    resetConnectTimeout();
    currentDataSpec = dataSpec;
    currentUrlRequest = buildRequest(dataSpec);
    currentUrlRequest.start();
    boolean requestStarted = blockUntilConnectTimeout();

    if (exception != null) {
      // An error occurred opening the connection.
      throw exception;
    } else if (!requestStarted) {
      // The timeout was reached before the connection was opened.
      throw new OpenException(new SocketTimeoutException(), dataSpec, getStatus(currentUrlRequest));
    }

    // Connection was opened.
    if (listener != null) {
      listener.onTransferStart(this, dataSpec);
    }
    connectionState = OPEN_CONNECTION;
    return contentLength;
  }

  @Override
  public int read(byte[] buffer, int offset, int readLength) throws HttpDataSourceException {
    synchronized (this) {
      Assertions.checkState(connectionState == OPEN_CONNECTION);
    }

    if (readLength == 0) {
      return 0;
    }
    if (expectedBytesRemainingToRead != null && expectedBytesRemainingToRead.get() == 0) {
      return C.RESULT_END_OF_INPUT;
    }

    if (!hasData) {
      // Read more data from cronet.
      operation.close();
      readBuffer.clear();
      currentUrlRequest.read(readBuffer);
      if (!operation.block(readTimeoutMs)) {
        throw new HttpDataSourceException(
            new SocketTimeoutException(), currentDataSpec, HttpDataSourceException.TYPE_READ);
      }
      if (exception != null) {
        throw exception;
      }
      // The expected response length is unknown, but cronet has indicated that the request
      // already finished successfully.
      if (responseFinished) {
        return C.RESULT_END_OF_INPUT;
      }
    }

    int bytesRead = Math.min(readBuffer.remaining(), readLength);
    readBuffer.get(buffer, offset, bytesRead);
    if (!readBuffer.hasRemaining()) {
      hasData = false;
    }

    if (expectedBytesRemainingToRead != null) {
      expectedBytesRemainingToRead.addAndGet(-bytesRead);
    }
    if (listener != null) {
      listener.onBytesTransferred(this, bytesRead);
    }
    return bytesRead;
  }

  @Override
  public synchronized void close() {
    if (currentUrlRequest != null) {
      currentUrlRequest.cancel();
      currentUrlRequest = null;
    }
    currentDataSpec = null;
    exception = null;
    contentLength = 0;
    hasData = false;
    responseInfo = null;
    expectedBytesRemainingToRead = null;
    responseFinished = false;
    try {
      if (listener != null && connectionState == OPEN_CONNECTION) {
        listener.onTransferEnd(this);
      }
    } finally {
      connectionState = IDLE_CONNECTION;
    }
  }

  // UrlRequest.Callback implementation

  @Override
  public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
    if (request != currentUrlRequest) {
      return;
    }
    if (currentDataSpec.postBody != null) {
      int responseCode = info.getHttpStatusCode();
      // The industry standard is to disregard POST redirects when the status code is 307 or 308.
      // For other redirect response codes the POST request is converted to a GET request and the
      // redirect is followed.
      if (responseCode == 307 || responseCode == 308) {
        exception = new OpenException("POST request redirected with 307 or 308 response code",
            currentDataSpec, getStatus(request));
        operation.open();
        return;
      }
    }
    if (resetTimeoutOnRedirects) {
      resetConnectTimeout();
    }
    request.followRedirect();
  }

  @Override
  public synchronized void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
    if (request != currentUrlRequest) {
      return;
    }
    try {
      // Check for a valid response code.
      int responseCode = info.getHttpStatusCode();
      if (responseCode < 200 || responseCode > 299) {
        InvalidResponseCodeException exception = new InvalidResponseCodeException(
            responseCode, info.getAllHeaders(), currentDataSpec);
        if (responseCode == 416) {
          exception.initCause(new DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE));
        }
        throw exception;
      }
      // Check for a valid content type.
      if (contentTypePredicate != null) {
        List<String> contentTypeHeaders = info.getAllHeaders().get(CONTENT_TYPE);
        String contentType = contentTypeHeaders == null || contentTypeHeaders.isEmpty() ? null
            : contentTypeHeaders.get(0);
        if (!contentTypePredicate.evaluate(contentType)) {
          throw new InvalidContentTypeException(contentType, currentDataSpec);
        }
      }

      responseInfo = info;
      if (getIsCompressed(info)) {
        contentLength = currentDataSpec.length;
      } else {
        // Check content length.
        contentLength = getContentLength(info);
        // If a specific length is requested and a specific length is returned but the 2 don't match
        // it's an error.
        if (currentDataSpec.length != C.LENGTH_UNSET && contentLength != C.LENGTH_UNSET
            && currentDataSpec.length != contentLength) {
          throw new OpenException("Content length did not match requested length", currentDataSpec,
              getStatus(request));
        }
      }
      if (contentLength > 0) {
        expectedBytesRemainingToRead = new AtomicLong(contentLength);
      }

      connectionState = CONNECTED_CONNECTION;
    } catch (HttpDataSourceException e) {
      exception = e;
    } finally {
      operation.open();
    }
  }

  @Override
  public synchronized void onReadCompleted(UrlRequest request, UrlResponseInfo info,
      ByteBuffer buffer) {
    if (request != currentUrlRequest) {
      return;
    }
    readBuffer.flip();
    hasData = true;
    operation.open();
  }

  @Override
  public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
    if (request != currentUrlRequest) {
      return;
    }
    responseFinished = true;
    operation.open();
  }

  @Override
  public synchronized void onFailed(UrlRequest request, UrlResponseInfo info,
      UrlRequestException error) {
    if (request != currentUrlRequest) {
      return;
    }
    if (connectionState == OPENING_CONNECTION) {
      IOException cause = error.getErrorCode() == UrlRequestException.ERROR_HOSTNAME_NOT_RESOLVED
          ? new UnknownHostException() : error;
      exception = new OpenException(cause, currentDataSpec, getStatus(request));
    } else if (connectionState == OPEN_CONNECTION) {
      exception = new HttpDataSourceException(error, currentDataSpec,
          HttpDataSourceException.TYPE_READ);
    }
    operation.open();
  }

  // Internal methods.

  private UrlRequest buildRequest(DataSpec dataSpec) throws OpenException {
    UrlRequest.Builder requestBuilder = new UrlRequest.Builder(dataSpec.uri.toString(), this,
        executor, cronetEngine);
    // Set the headers.
    synchronized (requestProperties) {
      if (dataSpec.postBody != null && !requestProperties.containsKey(CONTENT_TYPE)) {
        throw new OpenException("POST request must set Content-Type", dataSpec, Status.IDLE);
      }
      for (Entry<String, String> headerEntry : requestProperties.entrySet()) {
        requestBuilder.addHeader(headerEntry.getKey(), headerEntry.getValue());
      }
    }
    // Set the Range header.
    if (currentDataSpec.position != 0 || currentDataSpec.length != C.LENGTH_UNSET) {
      StringBuilder rangeValue = new StringBuilder();
      rangeValue.append("bytes=");
      rangeValue.append(currentDataSpec.position);
      rangeValue.append("-");
      if (currentDataSpec.length != C.LENGTH_UNSET) {
        rangeValue.append(currentDataSpec.position + currentDataSpec.length - 1);
      }
      requestBuilder.addHeader("Range", rangeValue.toString());
    }
    // Set the body.
    if (dataSpec.postBody != null) {
      requestBuilder.setUploadDataProvider(new ByteArrayUploadDataProvider(dataSpec.postBody),
          executor);
    }
    return requestBuilder.build();
  }

  private boolean blockUntilConnectTimeout() {
    long now = clock.elapsedRealtime();
    boolean opened = false;
    while (!opened && now < currentConnectTimeoutMs) {
      opened = operation.block(currentConnectTimeoutMs - now + 5 /* fudge factor */);
      now = clock.elapsedRealtime();
    }
    return opened;
  }

  private void resetConnectTimeout() {
    currentConnectTimeoutMs = clock.elapsedRealtime() + connectTimeoutMs;
  }

  private static boolean getIsCompressed(UrlResponseInfo info) {
    for (Map.Entry<String, String> entry : info.getAllHeadersAsList()) {
      if (entry.getKey().equalsIgnoreCase("Content-Encoding")) {
        return !entry.getValue().equalsIgnoreCase("identity");
      }
    }
    return false;
  }

  private static long getContentLength(UrlResponseInfo info) {
    long contentLength = C.LENGTH_UNSET;
    Map<String, List<String>> headers = info.getAllHeaders();
    List<String> contentLengthHeaders = headers.get("Content-Length");
    String contentLengthHeader = null;
    if (contentLengthHeaders != null && !contentLengthHeaders.isEmpty()) {
      contentLengthHeader = contentLengthHeaders.get(0);
      if (!TextUtils.isEmpty(contentLengthHeader)) {
        try {
          contentLength = Long.parseLong(contentLengthHeader);
        } catch (NumberFormatException e) {
          Log.e(TAG, "Unexpected Content-Length [" + contentLengthHeader + "]");
        }
      }
    }
    List<String> contentRangeHeaders = headers.get("Content-Range");
    if (contentRangeHeaders != null && !contentRangeHeaders.isEmpty()) {
      String contentRangeHeader = contentRangeHeaders.get(0);
      Matcher matcher = CONTENT_RANGE_HEADER_PATTERN.matcher(contentRangeHeader);
      if (matcher.find()) {
        try {
          long contentLengthFromRange =
              Long.parseLong(matcher.group(2)) - Long.parseLong(matcher.group(1)) + 1;
          if (contentLength < 0) {
            // Some proxy servers strip the Content-Length header. Fall back to the length
            // calculated here in this case.
            contentLength = contentLengthFromRange;
          } else if (contentLength != contentLengthFromRange) {
            // If there is a discrepancy between the Content-Length and Content-Range headers,
            // assume the one with the larger value is correct. We have seen cases where carrier
            // change one of them to reduce the size of a request, but it is unlikely anybody
            // would increase it.
            Log.w(TAG, "Inconsistent headers [" + contentLengthHeader + "] [" + contentRangeHeader
                + "]");
            contentLength = Math.max(contentLength, contentLengthFromRange);
          }
        } catch (NumberFormatException e) {
          Log.e(TAG, "Unexpected Content-Range [" + contentRangeHeader + "]");
        }
      }
    }
    return contentLength;
  }

  private static int getStatus(UrlRequest request) {
    final ConditionVariable conditionVariable = new ConditionVariable();
    final int[] statusHolder = new int[1];
    request.getStatus(new UrlRequest.StatusListener() {
      @Override
      public void onStatus(int status) {
        statusHolder[0] = status;
        conditionVariable.open();
      }
    });
    conditionVariable.block();
    return statusHolder[0];
  }

}
