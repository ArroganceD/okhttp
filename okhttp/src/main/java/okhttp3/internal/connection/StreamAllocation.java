/*
 * Copyright (C) 2015 Square, Inc.
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
package okhttp3.internal.connection;

import java.io.IOException;
import java.lang.ref.Reference;
import java.net.Socket;
import java.util.List;
import javax.annotation.Nullable;
import okhttp3.Address;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Route;
import okhttp3.internal.Internal;
import okhttp3.internal.Transmitter;
import okhttp3.internal.Transmitter.TransmitterReference;
import okhttp3.internal.Util;
import okhttp3.internal.http.HttpCodec;

import static okhttp3.internal.Util.closeQuietly;

/**
 * This class coordinates the relationship between three entities:
 *
 * <ul>
 *     <li><strong>Connections:</strong> physical socket connections to remote servers. These are
 *         potentially slow to establish so it is necessary to be able to cancel a connection
 *         currently being connected.
 *     <li><strong>Streams:</strong> logical HTTP request/response pairs that are layered on
 *         connections. Each connection has its own allocation limit, which defines how many
 *         concurrent streams that connection can carry. HTTP/1.x connections can carry 1 stream
 *         at a time, HTTP/2 typically carry multiple.
 *     <li><strong>Transmitters:</strong> a logical sequence of streams, typically an initial
 *         request and its follow up requests. We prefer to keep all streams of a single {@link
 *         Call} on the same connection for better behavior and locality.
 * </ul>
 *
 * <p>Instances of this class act on behalf of a single transmitter, using one or more streams over
 * one or more connections. This class has APIs to release each of the above resources:
 *
 * <ul>
 *     <li>{@link RealConnection#noNewStreams} prevents the connection from being used for new
 *         streams in the future. Use this after a {@code Connection: close} header, or when the
 *         connection may be inconsistent.
 *     <li>{@link #responseBodyComplete} releases the active stream from this allocation.
 *         Note that only one stream may be active at a given time, so it is necessary to call
 *         it before creating a subsequent stream with {@link #newStream}.
 *     <li>{@link #transmitterReleaseConnection} removes the transmitter's hold on the connection.
 *         Note that this won't immediately free the connection if there is a stream still
 *         lingering. That happens when a call is complete but its response body has yet to be fully
 *         consumed.
 * </ul>
 *
 * <p>This class supports {@linkplain #cancel asynchronous canceling}. This is intended to have the
 * smallest blast radius possible. If an HTTP/2 stream is active, canceling will cancel that stream
 * but not the other streams sharing its connection. But if the TLS handshake is still in progress
 * then canceling may break the entire connection.
 */
public final class StreamAllocation {
  public final Transmitter transmitter;
  public final Address address;
  private final RealConnectionPool connectionPool;
  public final Call call;
  public final EventListener eventListener;
  private final Object callStackTrace;

  private RouteSelector.Selection routeSelection;

  // State guarded by connectionPool.
  private final RouteSelector routeSelector;
  private RealConnection connectingConnection;
  private RealConnection connection;
  private boolean released;
  private boolean canceled;
  private boolean hasStreamFailure;
  private HttpCodec codec;

  public StreamAllocation(Transmitter transmitter, RealConnectionPool connectionPool,
      Address address, Call call, EventListener eventListener, Object callStackTrace) {
    this.transmitter = transmitter;
    this.connectionPool = connectionPool;
    this.address = address;
    this.call = call;
    this.eventListener = eventListener;
    this.routeSelector = new RouteSelector(
        address, connectionPool.routeDatabase, call, eventListener);
    this.callStackTrace = callStackTrace;
  }

  public HttpCodec newStream(
      OkHttpClient client, Interceptor.Chain chain, boolean doExtensiveHealthChecks) {
    int connectTimeout = chain.connectTimeoutMillis();
    int readTimeout = chain.readTimeoutMillis();
    int writeTimeout = chain.writeTimeoutMillis();
    int pingIntervalMillis = client.pingIntervalMillis();
    boolean connectionRetryEnabled = client.retryOnConnectionFailure();

    try {
      RealConnection resultConnection = findHealthyConnection(connectTimeout, readTimeout,
          writeTimeout, pingIntervalMillis, connectionRetryEnabled, doExtensiveHealthChecks);
      HttpCodec resultCodec = resultConnection.newCodec(client, chain);

      synchronized (connectionPool) {
        codec = resultCodec;
        return resultCodec;
      }
    } catch (RouteException e) {
      streamFailed(e.getLastConnectException());
      throw e;
    } catch (IOException e) {
      streamFailed(e);
      throw new RouteException(e);
    }
  }

  /**
   * Finds a connection and returns it if it is healthy. If it is unhealthy the process is repeated
   * until a healthy connection is found.
   */
  private RealConnection findHealthyConnection(int connectTimeout, int readTimeout,
      int writeTimeout, int pingIntervalMillis, boolean connectionRetryEnabled,
      boolean doExtensiveHealthChecks) throws IOException {
    while (true) {
      RealConnection candidate = findConnection(connectTimeout, readTimeout, writeTimeout,
          pingIntervalMillis, connectionRetryEnabled);

      // If this is a brand new connection, we can skip the extensive health checks.
      synchronized (connectionPool) {
        if (candidate.successCount == 0) {
          return candidate;
        }
      }

      // Do a (potentially slow) check to confirm that the pooled connection is still good. If it
      // isn't, take it out of the pool and start again.
      if (!candidate.isHealthy(doExtensiveHealthChecks)) {
        candidate.noNewStreams();
        continue;
      }

      return candidate;
    }
  }

  /**
   * Returns a connection to host a new stream. This prefers the existing connection if it exists,
   * then the pool, finally building a new connection.
   */
  private RealConnection findConnection(int connectTimeout, int readTimeout, int writeTimeout,
      int pingIntervalMillis, boolean connectionRetryEnabled) throws IOException {
    boolean foundPooledConnection = false;
    RealConnection result = null;
    Route selectedRoute = null;
    RealConnection releasedConnection;
    Socket toClose;
    synchronized (connectionPool) {
      if (released) throw new IllegalStateException("released");
      if (codec != null) throw new IllegalStateException("codec != null");
      if (canceled) throw new IOException("Canceled");

      Route previousRoute = retryCurrentRoute()
          ? connection.route()
          : null;

      // Attempt to use an already-allocated connection. We need to be careful here because our
      // already-allocated connection may have been restricted from creating new streams.
      releasedConnection = connection;
      toClose = connection != null && connection.noNewStreams
          ? transmitterReleaseConnection()
          : null;

      if (connection != null) {
        // We had an already-allocated connection and it's good.
        result = connection;
        releasedConnection = null;
      }

      if (result == null) {
        // Attempt to get a connection from the pool.
        if (connectionPool.transmitterAcquirePooledConnection(address, transmitter, null, false)) {
          foundPooledConnection = true;
          result = connection;
        } else {
          selectedRoute = previousRoute;
        }
      }
    }
    closeQuietly(toClose);

    if (releasedConnection != null) {
      eventListener.connectionReleased(call, releasedConnection);
    }
    if (foundPooledConnection) {
      eventListener.connectionAcquired(call, result);
    }
    if (result != null) {
      // If we found an already-allocated or pooled connection, we're done.
      return result;
    }

    // If we need a route selection, make one. This is a blocking operation.
    boolean newRouteSelection = false;
    if (selectedRoute == null && (routeSelection == null || !routeSelection.hasNext())) {
      newRouteSelection = true;
      routeSelection = routeSelector.next();
    }

    List<Route> routes = null;
    synchronized (connectionPool) {
      if (canceled) throw new IOException("Canceled");

      if (newRouteSelection) {
        // Now that we have a set of IP addresses, make another attempt at getting a connection from
        // the pool. This could match due to connection coalescing.
        routes = routeSelection.getAll();
        if (connectionPool.transmitterAcquirePooledConnection(address, transmitter, routes, false)) {
          foundPooledConnection = true;
          result = connection;
        }
      }

      if (!foundPooledConnection) {
        if (selectedRoute == null) {
          selectedRoute = routeSelection.next();
        }

        // Create a connection and assign it to this allocation immediately. This makes it possible
        // for an asynchronous cancel() to interrupt the handshake we're about to do.
        hasStreamFailure = false;
        result = new RealConnection(connectionPool, selectedRoute);
        connectingConnection = result;
      }
    }

    // If we found a pooled connection on the 2nd time around, we're done.
    if (foundPooledConnection) {
      eventListener.connectionAcquired(call, result);
      return result;
    }

    // Do TCP + TLS handshakes. This is a blocking operation.
    result.connect(connectTimeout, readTimeout, writeTimeout, pingIntervalMillis,
        connectionRetryEnabled, call, eventListener);
    connectionPool.routeDatabase.connected(result.route());

    Socket socket = null;
    synchronized (connectionPool) {
      connectingConnection = null;
      // Last attempt at connection coalescing, which only occurs if we attempted multiple
      // concurrent connections to the same host.
      if (connectionPool.transmitterAcquirePooledConnection(address, transmitter, routes, true)) {
        // We lost the race! Close the connection we created and return the pooled connection.
        result.noNewStreams = true;
        socket = result.socket();
        result = connection;
      } else {
        connectionPool.put(result);
        transmitterAcquireConnection(result);
      }
    }
    closeQuietly(socket);

    eventListener.connectionAcquired(call, result);
    return result;
  }

  public void responseBodyComplete(long bytesRead, IOException e) {
    eventListener.responseBodyEnd(call, bytesRead);

    Socket socket;
    Connection releasedConnection;
    boolean callEnd;
    synchronized (connectionPool) {
      if (codec == null) throw new IllegalStateException("codec == null");
      connection.successCount++;
      this.codec = null;
      releasedConnection = connection;
      socket = this.released
          ? transmitterReleaseConnection()
          : null;
      if (connection != null) releasedConnection = null;
      callEnd = this.released;
    }
    closeQuietly(socket);
    if (releasedConnection != null) {
      eventListener.connectionReleased(call, releasedConnection);
    }

    if (callEnd) {
      e = Internal.instance.timeoutExit(call, e);
    }
    if (e != null) {
      eventListener.callFailed(call, e);
    } else if (callEnd) {
      eventListener.callEnd(call);
    }
  }

  public HttpCodec codec() {
    synchronized (connectionPool) {
      return codec;
    }
  }

  public Route route() {
    synchronized (connectionPool) {
      return connection != null ? connection.route() : null;
    }
  }

  public RealConnection connection() {
    synchronized (connectionPool) {
      return connection;
    }
  }

  public void transmitterReleaseConnection(boolean callEnd) {
    Socket socket;
    Connection releasedConnection;
    synchronized (connectionPool) {
      releasedConnection = connection;
      this.released = true;
      socket = connection != null && this.codec == null
          ? transmitterReleaseConnection()
          : null;
      if (connection != null) releasedConnection = null;
    }
    closeQuietly(socket);
    if (releasedConnection != null) {
      if (callEnd) {
        Internal.instance.timeoutExit(call, null);
      }
      eventListener.connectionReleased(call, releasedConnection);
      if (callEnd) {
        eventListener.callEnd(call);
      }
    }
  }

  public void cancel() {
    HttpCodec codecToCancel;
    RealConnection connectionToCancel;
    synchronized (connectionPool) {
      canceled = true;
      codecToCancel = codec;
      connectionToCancel = connectingConnection != null ? connectingConnection : connection;
    }
    if (codecToCancel != null) {
      codecToCancel.cancel();
    } else if (connectionToCancel != null) {
      connectionToCancel.cancel();
    }
  }

  public void releaseStreamForException() {
    synchronized (connectionPool) {
      if (released) throw new IllegalStateException();
      this.codec = null;
    }
  }

  public void streamFailed(IOException e) {
    synchronized (connectionPool) {
      hasStreamFailure = true;
      if (connection != null) {
        connection.trackFailure(e);
      }
    }
  }

  /**
   * Use this allocation to hold {@code connection}. Each use of this must be paired with a call to
   * {@link #transmitterReleaseConnection} on the same connection.
   */
  public void transmitterAcquireConnection(RealConnection connection) {
    assert (Thread.holdsLock(connectionPool));
    if (this.connection != null) throw new IllegalStateException();

    this.connection = connection;
    connection.transmitters.add(new TransmitterReference(transmitter, callStackTrace));
  }

  /**
   * Remove the transmitter from the connection's list of allocations. Returns a socket that the
   * caller should close.
   */
  private @Nullable Socket transmitterReleaseConnection() {
    int index = -1;
    for (int i = 0, size = connection.transmitters.size(); i < size; i++) {
      Reference<Transmitter> reference = connection.transmitters.get(i);
      if (reference.get() == transmitter) {
        index = i;
        break;
      }
    }

    if (index == -1) throw new IllegalStateException();

    RealConnection released = this.connection;
    released.transmitters.remove(index);
    this.connection = null;

    if (released.transmitters.isEmpty()) {
      released.idleAtNanos = System.nanoTime();
      if (connectionPool.connectionBecameIdle(released)) {
        return released.socket();
      }
    }

    return null;
  }

  public boolean canRetry() {
    synchronized (connectionPool) {
      // Don't try if the failure wasn't our fault!
      if (!hasStreamFailure) return false;

      return retryCurrentRoute()
          || (routeSelection != null && routeSelection.hasNext())
          || routeSelector.hasNext();
    }
  }

  /**
   * Return true if the route used for the current connection should be retried, even if the
   * connection itself is unhealthy. The biggest gotcha here is that we shouldn't reuse routes from
   * coalesced connections.
   */
  private boolean retryCurrentRoute() {
    return connection != null
        && connection.routeFailureCount == 0
        && Util.sameConnection(connection.route().address().url(), address.url());
  }

  @Override public String toString() {
    RealConnection connection = connection();
    return connection != null ? connection.toString() : address.toString();
  }
}
