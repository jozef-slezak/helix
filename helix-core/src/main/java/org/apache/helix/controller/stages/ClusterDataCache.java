package org.apache.helix.controller.stages;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.PropertyKey;
import org.apache.helix.PropertyKey.Builder;
import org.apache.helix.ZNRecord;
import org.apache.helix.common.caches.CurrentStateCache;
import org.apache.helix.common.caches.InstanceMessagesCache;
import org.apache.helix.common.caches.TaskDataCache;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.ClusterConstraints;
import org.apache.helix.model.ClusterConstraints.ConstraintType;
import org.apache.helix.model.CurrentState;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.model.LiveInstance;
import org.apache.helix.model.MaintenanceSignal;
import org.apache.helix.model.Message;
import org.apache.helix.model.ParticipantHistory;
import org.apache.helix.model.ResourceAssignment;
import org.apache.helix.model.ResourceConfig;
import org.apache.helix.model.StateModelDefinition;
import org.apache.helix.task.JobConfig;
import org.apache.helix.task.JobContext;
import org.apache.helix.task.TaskConstants;
import org.apache.helix.task.TaskPartitionState;
import org.apache.helix.task.WorkflowConfig;
import org.apache.helix.task.WorkflowContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.helix.HelixConstants.ChangeType;

/**
 * Reads the data from the cluster using data accessor. This output ClusterData which
 * provides useful methods to search/lookup properties
 */
public class ClusterDataCache {
  private static final Logger LOG = LoggerFactory.getLogger(ClusterDataCache.class.getName());
  private static final String NAME = "NAME";

  private ClusterConfig _clusterConfig;
  private Map<String, LiveInstance> _liveInstanceMap;
  private Map<String, LiveInstance> _liveInstanceCacheMap;
  private Map<String, IdealState> _idealStateMap;
  private Map<String, IdealState> _idealStateCacheMap;
  private Map<String, StateModelDefinition> _stateModelDefMap;
  private Map<String, InstanceConfig> _instanceConfigMap;
  private Map<String, InstanceConfig> _instanceConfigCacheMap;
  private Map<String, Long> _instanceOfflineTimeMap;
  private Map<String, ResourceConfig> _resourceConfigMap;
  private Map<String, ResourceConfig> _resourceConfigCacheMap;
  private Map<String, ClusterConstraints> _constraintMap;
  private Map<String, Map<String, String>> _idealStateRuleMap;
  private Map<String, Map<String, Long>> _missingTopStateMap = new HashMap<>();
  private Map<String, ExternalView> _targetExternalViewMap = new HashMap<>();
  private Map<String, Map<String, Set<String>>> _disabledInstanceForPartitionMap = new HashMap<>();
  private Set<String> _disabledInstanceSet = new HashSet<>();

  private CurrentStateCache _currentStateCache;
  private TaskDataCache _taskDataCache;
  private InstanceMessagesCache _instanceMessagesCache;

  // maintain a cache of bestPossible assignment across pipeline runs
  // TODO: this is only for customRebalancer, remove it and merge it with _idealMappingCache.
  private Map<String, ResourceAssignment>  _resourceAssignmentCache = new HashMap<>();


  // maintain a cache of idealmapping (preference list) for full-auto resource across pipeline runs
  private Map<String, ZNRecord>  _idealMappingCache = new HashMap<>();

  private Map<ChangeType, Boolean> _propertyDataChangedMap;

  private Map<String, Integer> _participantActiveTaskCount = new HashMap<>();


  private ExecutorService _asyncTasksThreadPool;

  boolean _updateInstanceOfflineTime = true;
  boolean _isTaskCache;
  boolean _isMaintenanceModeEnabled;

  private String _clusterName;

  public ClusterDataCache () {
    this(null);
  }

  public ClusterDataCache(String clusterName) {
    _propertyDataChangedMap = new ConcurrentHashMap<>();
    for (ChangeType type : ChangeType.values()) {
      _propertyDataChangedMap.put(type, Boolean.valueOf(true));
    }
    _clusterName = clusterName;
    _currentStateCache = new CurrentStateCache(_clusterName);
    _taskDataCache = new TaskDataCache(_clusterName);
    _instanceMessagesCache = new InstanceMessagesCache(_clusterName);
  }

  /**
   * This refreshes the cluster data by re-fetching the data from zookeeper in
   * an efficient way
   * @param accessor
   * @return
   */
  public synchronized boolean refresh(HelixDataAccessor accessor) {
    LOG.info("START: ClusterDataCache.refresh()");
    long startTime = System.currentTimeMillis();
    Builder keyBuilder = accessor.keyBuilder();

    if (_propertyDataChangedMap.get(ChangeType.IDEAL_STATE)) {
      long start = System.currentTimeMillis();
      _propertyDataChangedMap.put(ChangeType.IDEAL_STATE, Boolean.valueOf(false));
      clearCachedResourceAssignments();
      _idealStateCacheMap = accessor.getChildValuesMap(keyBuilder.idealStates(), true);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Reload IdealStates: " + _idealStateCacheMap.keySet() + ". Takes " + (
            System.currentTimeMillis() - start) + " ms");
      }
    }

    if (_propertyDataChangedMap.get(ChangeType.LIVE_INSTANCE)) {
      _propertyDataChangedMap.put(ChangeType.LIVE_INSTANCE, Boolean.valueOf(false));
      clearCachedResourceAssignments();
      _liveInstanceCacheMap = accessor.getChildValuesMap(keyBuilder.liveInstances(), true);
      _updateInstanceOfflineTime = true;
      LOG.debug("Reload LiveInstances: " + _liveInstanceCacheMap.keySet());
    }

    if (_propertyDataChangedMap.get(ChangeType.INSTANCE_CONFIG)) {
      _propertyDataChangedMap.put(ChangeType.INSTANCE_CONFIG, Boolean.valueOf(false));
      clearCachedResourceAssignments();
      _instanceConfigCacheMap = accessor.getChildValuesMap(keyBuilder.instanceConfigs(), true);
      LOG.debug("Reload InstanceConfig: " + _instanceConfigCacheMap.keySet());
    }

    if (_propertyDataChangedMap.get(ChangeType.RESOURCE_CONFIG)) {
      _propertyDataChangedMap.put(ChangeType.RESOURCE_CONFIG, Boolean.valueOf(false));
      clearCachedResourceAssignments();
      _resourceConfigCacheMap = accessor.getChildValuesMap(accessor.keyBuilder().resourceConfigs(), true);
      LOG.debug("Reload ResourceConfigs: " + _resourceConfigCacheMap.size());
    }

    _idealStateMap = new HashMap<>(_idealStateCacheMap);
    _liveInstanceMap = new HashMap(_liveInstanceCacheMap);
    _instanceConfigMap = new ConcurrentHashMap<>(_instanceConfigCacheMap);
    _resourceConfigMap = new HashMap(_resourceConfigCacheMap);

    if (_updateInstanceOfflineTime) {
      updateOfflineInstanceHistory(accessor);
    }

    if (_isTaskCache) {
      _taskDataCache.refresh(accessor, _resourceConfigMap);
    }

    Map<String, StateModelDefinition> stateDefMap =
        accessor.getChildValuesMap(keyBuilder.stateModelDefs(), true);
    _stateModelDefMap = new ConcurrentHashMap<>(stateDefMap);
    _constraintMap = accessor.getChildValuesMap(keyBuilder.constraints(), true);
    _clusterConfig = accessor.getProperty(keyBuilder.clusterConfig());


    _instanceMessagesCache
        .refresh(accessor, _liveInstanceMap);
    _currentStateCache.refresh(accessor, _liveInstanceMap);

    // current state must be refreshed before refreshing relay messages
    // because we need to use current state to validate all relay messages.
    _instanceMessagesCache
        .updateRelayMessages(_liveInstanceMap, _currentStateCache.getCurrentStatesMap());

    if (_clusterConfig != null) {
      _idealStateRuleMap = _clusterConfig.getIdealStateRules();
    } else {
      _idealStateRuleMap = new HashMap();
      LOG.warn("Cluster config is null!");
    }

    MaintenanceSignal maintenanceSignal = accessor.getProperty(keyBuilder.maintenance());
    _isMaintenanceModeEnabled = (maintenanceSignal != null) ? true : false;

    updateDisabledInstances();

    long endTime = System.currentTimeMillis();
    LOG.info(
        "END: ClusterDataCache.refresh() for cluster " + getClusterName() + ", took " + (endTime
            - startTime) + " ms");

    if (LOG.isDebugEnabled()) {
      LOG.debug("# of StateModelDefinition read from zk: " + _stateModelDefMap.size());
      LOG.debug("# of ConstraintMap read from zk: " + _constraintMap.size());
      LOG.debug("LiveInstances: " + _liveInstanceMap.keySet());
        for (LiveInstance instance : _liveInstanceMap.values()) {
          LOG.debug("live instance: " + instance.getInstanceName() + " " + instance.getSessionId());
        }
      LOG.debug("ResourceConfigs: " + _resourceConfigMap.keySet());
      LOG.debug("InstanceConfigs: " + _instanceConfigMap.keySet());
      LOG.debug("ClusterConfigs: " + _clusterConfig);
      LOG.debug("JobContexts: " + _taskDataCache.getContexts().keySet());
    }

    if (LOG.isTraceEnabled()) {
      LOG.trace("Cache content: " + toString());
    }

    return true;
  }

  private void updateDisabledInstances() {
    // Move the calculating disabled instances to refresh
    _disabledInstanceForPartitionMap.clear();
    _disabledInstanceSet.clear();
    for (InstanceConfig config : _instanceConfigMap.values()) {
      Map<String, List<String>> disabledPartitionMap = config.getDisabledPartitionsMap();
      if (!config.getInstanceEnabled()) {
        _disabledInstanceSet.add(config.getInstanceName());
      }
      for (String resource : disabledPartitionMap.keySet()) {
        if (!_disabledInstanceForPartitionMap.containsKey(resource)) {
          _disabledInstanceForPartitionMap.put(resource, new HashMap<String, Set<String>>());
        }
        for (String partition : disabledPartitionMap.get(resource)) {
          if (!_disabledInstanceForPartitionMap.get(resource).containsKey(partition)) {
            _disabledInstanceForPartitionMap.get(resource).put(partition, new HashSet<String>());
          }
          _disabledInstanceForPartitionMap.get(resource).get(partition)
              .add(config.getInstanceName());
        }
      }
    }
    if (_clusterConfig.getDisabledInstances() != null) {
      _disabledInstanceSet.addAll(_clusterConfig.getDisabledInstances().keySet());
    }
  }

  private void updateOfflineInstanceHistory(HelixDataAccessor accessor) {
    List<String> offlineNodes = new ArrayList<>(_instanceConfigMap.keySet());
    offlineNodes.removeAll(_liveInstanceMap.keySet());
    _instanceOfflineTimeMap = new HashMap<>();

    for (String instance : offlineNodes) {
      Builder keyBuilder = accessor.keyBuilder();
      PropertyKey propertyKey = keyBuilder.participantHistory(instance);
      ParticipantHistory history = accessor.getProperty(propertyKey);
      if (history == null) {
        history = new ParticipantHistory(instance);
      }
      if (history.getLastOfflineTime() == ParticipantHistory.ONLINE) {
        history.reportOffline();
        // persist history back to ZK.
        if (!accessor.setProperty(propertyKey, history)) {
          LOG.error("Fails to persist participant online history back to ZK!");
        }
      }
      _instanceOfflineTimeMap.put(instance, history.getLastOfflineTime());
    }
    _updateInstanceOfflineTime = false;
  }

  public ClusterConfig getClusterConfig() {
    return _clusterConfig;
  }

  public void setClusterConfig(ClusterConfig clusterConfig) {
    _clusterConfig = clusterConfig;
  }

  public String getClusterName() {
    return _clusterConfig != null ? _clusterConfig.getClusterName() : _clusterName;
  }

  /**
   * Return the last offline time map for all offline instances.
   *
   * @return
   */
  public Map<String, Long> getInstanceOfflineTimeMap() {
    return _instanceOfflineTimeMap;
  }

  /**
   * Retrieves the idealstates for all resources
   * @return
   */
  public Map<String, IdealState> getIdealStates() {
    return _idealStateMap;
  }

  public synchronized void setIdealStates(List<IdealState> idealStates) {
    Map<String, IdealState> idealStateMap = new HashMap();
    for (IdealState idealState : idealStates) {
      idealStateMap.put(idealState.getId(), idealState);
    }
    _idealStateCacheMap = idealStateMap;
  }

  public Map<String, Map<String, String>> getIdealStateRules() {
    return _idealStateRuleMap;
  }

  /**
   * Returns the LiveInstances for each of the instances that are curretnly up and running
   * @return
   */
  public Map<String, LiveInstance> getLiveInstances() {
    return _liveInstanceMap;
  }


  /**
   * Return the set of all instances names.
   */
  public Set<String> getAllInstances() {
    return new HashSet<>(_instanceConfigMap.keySet());
  }

  /**
   * Return all the live nodes that are enabled
   *
   * @return A new set contains live instance name and that are marked enabled
   */
  public Set<String> getEnabledLiveInstances() {
    Set<String> enabledLiveInstances = new HashSet<>(getLiveInstances().keySet());
    enabledLiveInstances.removeAll(getDisabledInstances());

    return enabledLiveInstances;
  }

  /**
   * Return all nodes that are enabled.
   *
   * @return
   */
  public Set<String> getEnabledInstances() {
    Set<String> enabledNodes = new HashSet<>(getInstanceConfigMap().keySet());
    enabledNodes.removeAll(getDisabledInstances());

    return enabledNodes;
  }

  /**
   * Return all the live nodes that are enabled and tagged with given instanceTag.
   *
   * @param instanceTag The instance group tag.
   * @return A new set contains live instance name and that are marked enabled and have the specified
   * tag.
   */
  public Set<String> getEnabledLiveInstancesWithTag(String instanceTag) {
    Set<String> enabledLiveInstancesWithTag = new HashSet<>(getLiveInstances().keySet());
    Set<String> instancesWithTag = getInstancesWithTag(instanceTag);
    enabledLiveInstancesWithTag.retainAll(instancesWithTag);
    enabledLiveInstancesWithTag.removeAll(getDisabledInstances());

    return enabledLiveInstancesWithTag;
  }

  /**
   * Return all the nodes that are tagged with given instance tag.
   *
   * @param instanceTag The instance group tag.
   */
  public Set<String> getInstancesWithTag(String instanceTag) {
    Set<String> taggedInstances = new HashSet<>();
    for (String instance : _instanceConfigMap.keySet()) {
      InstanceConfig instanceConfig = _instanceConfigMap.get(instance);
      if (instanceConfig != null && instanceConfig.containsTag(instanceTag)) {
        taggedInstances.add(instance);
      }
    }

    return taggedInstances;
  }


  public synchronized void setLiveInstances(List<LiveInstance> liveInstances) {
    Map<String, LiveInstance> liveInstanceMap = new HashMap();
    for (LiveInstance liveInstance : liveInstances) {
      liveInstanceMap.put(liveInstance.getId(), liveInstance);
    }
    _liveInstanceCacheMap = liveInstanceMap;
    _updateInstanceOfflineTime = true;
  }

  /**
   * Provides the current state of the node for a given session id, the sessionid can be got from
   * LiveInstance
   *
   * @param instanceName
   * @param clientSessionId
   *
   * @return
   */
  public Map<String, CurrentState> getCurrentState(String instanceName, String clientSessionId) {
    return _currentStateCache.getCurrentState(instanceName, clientSessionId);
  }

  /**
   * Provides a list of current outstanding transitions on a given instance.
   * @param instanceName
   * @return
   */
  public Map<String, Message> getMessages(String instanceName) {
    return _instanceMessagesCache.getMessages(instanceName);
  }

  public void cacheMessages(List<Message> messages) {
    _instanceMessagesCache.cacheMessages(messages);
  }

  /**
   * Provides the state model definition for a given state model
   * @param stateModelDefRef
   * @return
   */
  public StateModelDefinition getStateModelDef(String stateModelDefRef) {
    if (stateModelDefRef == null) {
      return null;
    }
    return _stateModelDefMap.get(stateModelDefRef);
  }

  /**
   * Provides all state model definitions
   * @return state model definition map
   */
  public Map<String, StateModelDefinition> getStateModelDefMap() {
    return _stateModelDefMap;
  }

  /**
   * Provides the idealstate for a given resource
   * @param resourceName
   * @return
   */
  public IdealState getIdealState(String resourceName) {
    return _idealStateMap.get(resourceName);
  }

  /**
   * Returns the instance config map
   * @return
   */
  public Map<String, InstanceConfig> getInstanceConfigMap() {
    return _instanceConfigMap;
  }

  /**
   * Set the instance config map
   * @param instanceConfigMap
   */
  public void setInstanceConfigMap(Map<String, InstanceConfig> instanceConfigMap) {
    _instanceConfigMap = instanceConfigMap;
  }

  /**
   * Returns the resource config map
   *
   * @return
   */
  public Map<String, ResourceConfig> getResourceConfigMap() {
    return _resourceConfigMap;
  }

  /**
   * Notify the cache that some part of the cluster data has been changed.
   */
  public void notifyDataChange(ChangeType changeType) {
    _propertyDataChangedMap.put(changeType, Boolean.valueOf(true));
  }

  /**
   * Notify the cache that some part of the cluster data has been changed.
   */
  public void notifyDataChange(ChangeType changeType, String pathChanged) {
    notifyDataChange(changeType);
  }

  /**
   * Returns the instance config map
   *
   * @return
   */
  public ResourceConfig getResourceConfig(String resource) {
    return _resourceConfigMap.get(resource);
  }

  /**
   * Returns job config map
   * @return
   */
  public Map<String, JobConfig> getJobConfigMap() {
    return _taskDataCache.getJobConfigMap();
  }

  /**
   * Returns job config
   * @param resource
   * @return
   */
  public JobConfig getJobConfig(String resource) {
    return _taskDataCache.getJobConfig(resource);
  }

  /**
   * Returns workflow config map
   * @return
   */
  public Map<String, WorkflowConfig> getWorkflowConfigMap() {
    return _taskDataCache.getWorkflowConfigMap();
  }

  /**
   * Returns workflow config
   * @param resource
   * @return
   */
  public WorkflowConfig getWorkflowConfig(String resource) {
    return _taskDataCache.getWorkflowConfig(resource);
  }


  public synchronized void setInstanceConfigs(List<InstanceConfig> instanceConfigs) {
    Map<String, InstanceConfig> instanceConfigMap = new HashMap();
    for (InstanceConfig instanceConfig : instanceConfigs) {
      instanceConfigMap.put(instanceConfig.getId(), instanceConfig);
    }
    _instanceConfigCacheMap = instanceConfigMap;
  }

  /**
   * Some partitions might be disabled on specific nodes.
   * This method allows one to fetch the set of nodes where a given partition is disabled
   * @param partition
   * @return
   */
  public Set<String> getDisabledInstancesForPartition(String resource, String partition) {
    Set<String> disabledInstancesForPartition = new HashSet<>(_disabledInstanceSet);
    if (_disabledInstanceForPartitionMap.containsKey(resource) && _disabledInstanceForPartitionMap
        .get(resource).containsKey(partition)) {
      disabledInstancesForPartition
          .addAll(_disabledInstanceForPartitionMap.get(resource).get(partition));
    }

    return disabledInstancesForPartition;
  }

  /**
   * This method allows one to fetch the set of nodes that are disabled
   *
   * @return
   */
  public Set<String> getDisabledInstances() {
    return Collections.unmodifiableSet(_disabledInstanceSet);
  }

  /**
   * Returns the number of replicas for a given resource.
   * @param resourceName
   * @return
   */
  public int getReplicas(String resourceName) {
    int replicas = -1;

    if (_idealStateMap.containsKey(resourceName)) {
      String replicasStr = _idealStateMap.get(resourceName).getReplicas();

      if (replicasStr != null) {
        if (replicasStr.equals(IdealState.IdealStateConstants.ANY_LIVEINSTANCE.toString())) {
          replicas = _liveInstanceMap.size();
        } else {
          try {
            replicas = Integer.parseInt(replicasStr);
          } catch (Exception e) {
            LOG.error("invalid replicas string: " + replicasStr);
          }
        }
      } else {
        LOG.error("idealState for resource: " + resourceName + " does NOT have replicas");
      }
    }
    return replicas;
  }

  /**
   * Returns the ClusterConstraints for a given constraintType
   * @param type
   * @return
   */
  public ClusterConstraints getConstraint(ConstraintType type) {
    if (_constraintMap != null) {
      return _constraintMap.get(type.toString());
    }
    return null;
  }

  public Map<String, Map<String, Long>> getMissingTopStateMap() {
    return _missingTopStateMap;
  }

  public Integer getParticipantActiveTaskCount(String instance) {
    return _participantActiveTaskCount.get(instance);
  }

  public void setParticipantActiveTaskCount(String instance, int taskCount) {
    _participantActiveTaskCount.put(instance, taskCount);
  }

  /**
   * Reset RUNNING/INIT tasks count in JobRebalancer
   */
  public void resetActiveTaskCount(CurrentStateOutput currentStateOutput) {
    // init participant map
    for (String liveInstance : getLiveInstances().keySet()) {
      _participantActiveTaskCount.put(liveInstance, 0);
    }
    // Active task == init and running tasks
    fillActiveTaskCount(currentStateOutput.getPartitionCountWithPendingState(TaskConstants.STATE_MODEL_NAME,
        TaskPartitionState.INIT.name()), _participantActiveTaskCount);
    fillActiveTaskCount(currentStateOutput.getPartitionCountWithPendingState(TaskConstants.STATE_MODEL_NAME,
        TaskPartitionState.RUNNING.name()), _participantActiveTaskCount);
    fillActiveTaskCount(currentStateOutput.getPartitionCountWithCurrentState(TaskConstants.STATE_MODEL_NAME,
        TaskPartitionState.INIT.name()), _participantActiveTaskCount);
    fillActiveTaskCount(currentStateOutput
        .getPartitionCountWithCurrentState(TaskConstants.STATE_MODEL_NAME,
            TaskPartitionState.RUNNING.name()), _participantActiveTaskCount);
  }

  private void fillActiveTaskCount(Map<String, Integer> additionPartitionMap,
      Map<String, Integer> partitionMap) {
    for (String participant : additionPartitionMap.keySet()) {
      partitionMap.put(participant, partitionMap.get(participant) + additionPartitionMap.get(participant));
    }
  }

  /**
   * Return the JobContext by resource name
   * @param resourceName
   * @return
   */
  public JobContext getJobContext(String resourceName) {
    return _taskDataCache.getJobContext(resourceName);
  }

  /**
   * Return the WorkflowContext by resource name
   * @param resourceName
   * @return
   */
  public WorkflowContext getWorkflowContext(String resourceName) {
    return _taskDataCache.getWorkflowContext(resourceName);
  }

  /**
   * Update context of the Job
   */
  public void updateJobContext(String resourceName, JobContext jobContext,
      HelixDataAccessor accessor) {
    _taskDataCache.updateJobContext(resourceName, jobContext, accessor);
  }

  /**
   * Update context of the Workflow
   */
  public void updateWorkflowContext(String resourceName, WorkflowContext workflowContext,
      HelixDataAccessor accessor) {
    _taskDataCache.updateWorkflowContext(resourceName, workflowContext, accessor);
  }

  /**
   * Return map of WorkflowContexts or JobContexts
   * @return
   */
  public Map<String, ZNRecord> getContexts() {
    return _taskDataCache.getContexts();
  }

  public ExternalView getTargetExternalView(String resourceName) {
    return _targetExternalViewMap.get(resourceName);
  }

  public void updateTargetExternalView(String resourceName, ExternalView targetExternalView) {
    _targetExternalViewMap.put(resourceName, targetExternalView);
  }

  /**
   * Indicate that a full read should be done on the next refresh
   */
  public synchronized void requireFullRefresh() {
    for(ChangeType type : ChangeType.values()) {
      _propertyDataChangedMap.put(type, Boolean.valueOf(true));
    }
  }

  /**
   * Get async update thread pool
   * @return
   */
  public ExecutorService getAsyncTasksThreadPool() {
    return _asyncTasksThreadPool;
  }

  /**
   * Get cached resourceAssignment (bestPossible mapping) for a resource
   *
   * @param resource
   *
   * @return
   */
  public ResourceAssignment getCachedResourceAssignment(String resource) {
    return _resourceAssignmentCache.get(resource);
  }

  /**
   * Get cached resourceAssignments
   *
   * @return
   */
  public Map<String, ResourceAssignment> getCachedResourceAssignments() {
    return Collections.unmodifiableMap(_resourceAssignmentCache);
  }

  /**
   * Cache resourceAssignment (bestPossible mapping) for a resource
   *
   * @param resource
   *
   * @return
   */
  public void setCachedResourceAssignment(String resource, ResourceAssignment resourceAssignment) {
    _resourceAssignmentCache.put(resource, resourceAssignment);
  }


  /**
   * Get cached resourceAssignment (ideal mapping) for a resource
   *
   * @param resource
   *
   * @return
   */
  public ZNRecord getCachedIdealMapping(String resource) {
    return _idealMappingCache.get(resource);
  }

  /**
   * Get cached idealmapping
   *
   * @return
   */
  public Map<String, ZNRecord> getCachedIdealMapping() {
    return Collections.unmodifiableMap(_idealMappingCache);
  }

  /**
   * Cache resourceAssignment (ideal mapping) for a resource
   *
   * @param resource
   *
   * @return
   */
  public void setCachedIdealMapping(String resource, ZNRecord mapping) {
    _idealMappingCache.put(resource, mapping);
  }


  protected void clearCachedResourceAssignments() {
    _resourceAssignmentCache.clear();
    _idealMappingCache.clear();
  }

  /**
   * Set async update thread pool
   * @param asyncTasksThreadPool
   */
  public void setAsyncTasksThreadPool(ExecutorService asyncTasksThreadPool) {
    _asyncTasksThreadPool = asyncTasksThreadPool;
  }

  /**
   * Set the cache is serving for Task pipleline or not
   * @param taskCache
   */
  public void setTaskCache(boolean taskCache) {
    _isTaskCache = taskCache;
  }

  /**
   * Get the cache is serving for Task pipleline or not
   * @return
   */
  public boolean isTaskCache() {
    return _isTaskCache;
  }

  public boolean isMaintenanceModeEnabled() {
    return _isMaintenanceModeEnabled;
  }

  public void clearMonitoringRecords() {
    _missingTopStateMap.clear();
  }

  /**
   * toString method to print the entire cluster state
   */
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("liveInstaceMap:" + _liveInstanceMap).append("\n");
    sb.append("idealStateMap:" + _idealStateMap).append("\n");
    sb.append("stateModelDefMap:" + _stateModelDefMap).append("\n");
    sb.append("instanceConfigMap:" + _instanceConfigMap).append("\n");
    sb.append("resourceConfigMap:" + _resourceConfigMap).append("\n");
    sb.append("taskDataCache:" + _taskDataCache).append("\n");
    sb.append("messageCache:" + _instanceMessagesCache).append("\n");
    sb.append("currentStateCache:" + _currentStateCache).append("\n");
    sb.append("clusterConfig:" + _clusterConfig).append("\n");

    return sb.toString();
  }
}
