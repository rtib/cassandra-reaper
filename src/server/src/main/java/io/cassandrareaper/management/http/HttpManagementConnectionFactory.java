/*
 * Copyright 2020-2020 The Last Pickle Ltd
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cassandrareaper.management.http;

import io.cassandrareaper.AppContext;
import io.cassandrareaper.ReaperException;
import io.cassandrareaper.core.Node;
import io.cassandrareaper.management.HostConnectionCounters;
import io.cassandrareaper.management.ICassandraManagementProxy;
import io.cassandrareaper.management.IManagementConnectionFactory;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import javax.ws.rs.core.Response;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.InstrumentedScheduledExecutorService;
import com.codahale.metrics.MetricRegistry;
import com.datastax.mgmtapi.client.api.DefaultApi;
import com.datastax.mgmtapi.client.invoker.ApiClient;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpManagementConnectionFactory implements IManagementConnectionFactory {
  private static final Logger LOG = LoggerFactory.getLogger(HttpManagementConnectionFactory.class);
  private static final ConcurrentMap<String, HttpCassandraManagementProxy> HTTP_CONNECTIONS = Maps.newConcurrentMap();
  private final MetricRegistry metricRegistry;
  private final HostConnectionCounters hostConnectionCounters;
  private final int metricsPort;

  private final ScheduledExecutorService jobStatusPollerExecutor;

  private final Set<String> accessibleDatacenters = Sets.newHashSet();

  // Constructor for HttpManagementConnectionFactory
  public HttpManagementConnectionFactory(AppContext context, ScheduledExecutorService jobStatusPollerExecutor) {
    this.metricRegistry
        = context.metricRegistry == null ? new MetricRegistry() : context.metricRegistry;
    hostConnectionCounters = new HostConnectionCounters(metricRegistry);
    this.metricsPort = context.config.getMgmtApiMetricsPort();
    registerConnectionsGauge();
    this.jobStatusPollerExecutor = jobStatusPollerExecutor;
  }

  @Override
  public ICassandraManagementProxy connectAny(Collection<Node> nodes) throws ReaperException {
    Preconditions.checkArgument(
        null != nodes && !nodes.isEmpty(), "no hosts provided to connectAny");
    List<Node> nodeList = new ArrayList<>(nodes);
    Collections.shuffle(nodeList);
    for (int i = 0; i < 2; i++) {
      for (Node node : nodeList) {
        // First loop, we try the most accessible nodes, then second loop we try all nodes
        if (getHostConnectionCounters().getSuccessfulConnections(node.getHostname()) >= 0 || 1 == i) {
          try {
            LOG.debug("Trying to connect to node {} with {} successful connections with i = {}",
                node.getHostname(), getHostConnectionCounters().getSuccessfulConnections(node.getHostname()), i);
            ICassandraManagementProxy cassandraManagementProxy = connectImpl(node);
            getHostConnectionCounters().incrementSuccessfulConnections(node.getHostname());
            if (getHostConnectionCounters().getSuccessfulConnections(node.getHostname()) > 0) {
              accessibleDatacenters.add(
                  cassandraManagementProxy.getDatacenter(cassandraManagementProxy.getUntranslatedHost()));
            }
            return cassandraManagementProxy;
          } catch (ReaperException | RuntimeException | UnknownHostException e) {
            getHostConnectionCounters().decrementSuccessfulConnections(node.getHostname());
            LOG.info("Unreachable host: {}", node.getHostname(), e);
          } catch (InterruptedException expected) {
            LOG.trace("Expected exception", expected);
          }
        }
      }
    }
    throw new ReaperException("no host could be reached through HTTP");
  }

  @Override
  public HostConnectionCounters getHostConnectionCounters() {
    return hostConnectionCounters;
  }

  private void registerConnectionsGauge() {
    try {
      if (!this.metricRegistry
          .getGauges()
          .containsKey(MetricRegistry.name(HttpManagementConnectionFactory.class, "openHttpManagementConnections"))) {
        this.metricRegistry.register(
            MetricRegistry.name(HttpManagementConnectionFactory.class, "openHttpManagementConnections"),
            (Gauge<Integer>) () -> HTTP_CONNECTIONS.size());
      }
    } catch (IllegalArgumentException e) {
      LOG.warn("Cannot create openHttoManagementConnections metric gauge", e);
    }
  }

  private ICassandraManagementProxy connectImpl(Node node)
      throws ReaperException, InterruptedException {
    Integer managementPort = 8080; // TODO - get this from the config.
    String rootPath = ""; // TODO - get this from the config.
    Response pidResponse = getPid(node);
    if (pidResponse.getStatus() != 200) {
      throw new ReaperException("Could not get PID for node " + node.getHostname());
    }

    String host = node.getHostname();

    HTTP_CONNECTIONS.computeIfAbsent(host, new Function<String, HttpCassandraManagementProxy>() {
      @Nullable
      @Override
      public HttpCassandraManagementProxy apply(@Nullable String hostName) {
        DefaultApi apiClient = new DefaultApi(
            new ApiClient().setBasePath("http://" + hostName + ":" + managementPort + rootPath));

        InstrumentedScheduledExecutorService statusTracker = new InstrumentedScheduledExecutorService(
            jobStatusPollerExecutor, metricRegistry);
        return new HttpCassandraManagementProxy(
            metricRegistry,
            rootPath,
            new InetSocketAddress(node.getHostname(), managementPort),
            statusTracker,
            apiClient,
            metricsPort,
            node
        );
      }
    });
    return HTTP_CONNECTIONS.get(host);
  }

  private Response getPid(Node node) {
    //TODO - implement me.
    return Response.ok().build();
  }

  @Override
  public final Set<String> getAccessibleDatacenters() {
    return accessibleDatacenters;
  }
}
