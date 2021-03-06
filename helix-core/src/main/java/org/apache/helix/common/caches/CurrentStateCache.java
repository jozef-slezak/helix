package org.apache.helix.common.caches;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixProperty;
import org.apache.helix.PropertyKey;
import org.apache.helix.model.CurrentState;
import org.apache.helix.model.LiveInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache to hold all CurrentStates of a cluster.
 */
public class CurrentStateCache {
  private static final Logger LOG = LoggerFactory.getLogger(CurrentStateCache.class.getName());

  private Map<String, Map<String, Map<String, CurrentState>>> _currentStateMap;
  private Map<PropertyKey, CurrentState> _currentStateCache = Maps.newHashMap();

  private String _clusterName;

  public CurrentStateCache(String clusterName) {
    _clusterName = clusterName;
    _currentStateMap = Collections.emptyMap();
  }

  /**
   * This refreshes the CurrentStates data by re-fetching the data from zookeeper in an efficient
   * way
   *
   * @param accessor
   * @param liveInstanceMap map of all liveInstances in cluster
   *
   * @return
   */
  public boolean refresh(HelixDataAccessor accessor,
      Map<String, LiveInstance> liveInstanceMap) {
    LOG.info("START: CurrentStateCache.refresh()");
    long startTime = System.currentTimeMillis();

    refreshCurrentStatesCache(accessor, liveInstanceMap);

    Map<String, Map<String, Map<String, CurrentState>>> allCurStateMap = new HashMap<>();
    for (PropertyKey key : _currentStateCache.keySet()) {
      CurrentState currentState = _currentStateCache.get(key);
      String[] params = key.getParams();
      if (currentState != null && params.length >= 4) {
        String instanceName = params[1];
        String sessionId = params[2];
        String stateName = params[3];
        Map<String, Map<String, CurrentState>> instanceCurStateMap =
            allCurStateMap.get(instanceName);
        if (instanceCurStateMap == null) {
          instanceCurStateMap = Maps.newHashMap();
          allCurStateMap.put(instanceName, instanceCurStateMap);
        }
        Map<String, CurrentState> sessionCurStateMap = instanceCurStateMap.get(sessionId);
        if (sessionCurStateMap == null) {
          sessionCurStateMap = Maps.newHashMap();
          instanceCurStateMap.put(sessionId, sessionCurStateMap);
        }
        sessionCurStateMap.put(stateName, currentState);
      }
    }

    for (String instance : allCurStateMap.keySet()) {
      allCurStateMap.put(instance, Collections.unmodifiableMap(allCurStateMap.get(instance)));
    }
    _currentStateMap = Collections.unmodifiableMap(allCurStateMap);

    long endTime = System.currentTimeMillis();
    LOG.info("END: CurrentStateCache.refresh() for cluster " + _clusterName + ", took " + (endTime
        - startTime) + " ms");
    return true;
  }

  // reload current states that has been changed from zk to local cache.
  private void refreshCurrentStatesCache(HelixDataAccessor accessor,
      Map<String, LiveInstance> liveInstanceMap) {
    long start = System.currentTimeMillis();
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();

    List<PropertyKey> currentStateKeys = Lists.newLinkedList();
    for (String instanceName : liveInstanceMap.keySet()) {
      LiveInstance liveInstance = liveInstanceMap.get(instanceName);
      String sessionId = liveInstance.getSessionId();
      List<String> currentStateNames =
          accessor.getChildNames(keyBuilder.currentStates(instanceName, sessionId));
      for (String currentStateName : currentStateNames) {
        currentStateKeys.add(keyBuilder.currentState(instanceName, sessionId, currentStateName));
      }
    }

    // All new entries from zk not cached locally yet should be read from ZK.
    List<PropertyKey> reloadKeys = Lists.newLinkedList(currentStateKeys);
    reloadKeys.removeAll(_currentStateCache.keySet());

    List<PropertyKey> cachedKeys = Lists.newLinkedList(_currentStateCache.keySet());
    cachedKeys.retainAll(currentStateKeys);

    List<HelixProperty.Stat> stats = accessor.getPropertyStats(cachedKeys);
    Map<PropertyKey, CurrentState> currentStatesMap = Maps.newHashMap();
    for (int i = 0; i < cachedKeys.size(); i++) {
      PropertyKey key = cachedKeys.get(i);
      HelixProperty.Stat stat = stats.get(i);
      if (stat != null) {
        CurrentState property = _currentStateCache.get(key);
        if (property != null && property.getBucketSize() == 0 && property.getStat().equals(stat)) {
          currentStatesMap.put(key, property);
        } else {
          // need update from zk
          reloadKeys.add(key);
        }
      } else {
        LOG.warn("stat is null for key: " + key);
        reloadKeys.add(key);
      }
    }

    List<CurrentState> currentStates = accessor.getProperty(reloadKeys, true);
    Iterator<PropertyKey> csKeyIter = reloadKeys.iterator();
    for (CurrentState currentState : currentStates) {
      PropertyKey key = csKeyIter.next();
      if (currentState != null) {
        currentStatesMap.put(key, currentState);
      } else {
        LOG.warn("CurrentState null for key: " + key);
      }
    }

    _currentStateCache = Collections.unmodifiableMap(currentStatesMap);

    LOG.info("# of CurrentStates reload: " + reloadKeys.size() + ", skipped:" + (currentStateKeys.size()
        - reloadKeys.size()));
    LOG.info("Takes " + (System.currentTimeMillis() - start) + " ms to reload new current states for cluster: "
        + _clusterName);
  }

  /**
   * Return CurrentStates map for all instances.
   *
   * @return
   */
  public Map<String, Map<String, Map<String, CurrentState>>> getCurrentStatesMap() {
    return Collections.unmodifiableMap(_currentStateMap);
  }

  /**
   * Return all CurrentState on the given instance.
   *
   * @param instance
   *
   * @return
   */
  public Map<String, Map<String, CurrentState>> getCurrentStates(String instance) {
    if (!_currentStateMap.containsKey(instance)) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(_currentStateMap.get(instance));
  }

  /**
   * Provides the current state of the node for a given session id, the sessionid can be got from
   * LiveInstance
   *
   * @param instance
   * @param clientSessionId
   *
   * @return
   */
  public Map<String, CurrentState> getCurrentState(String instance, String clientSessionId) {
    if (!_currentStateMap.containsKey(instance) || !_currentStateMap.get(instance)
        .containsKey(clientSessionId)) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(_currentStateMap.get(instance).get(clientSessionId));
  }
}
