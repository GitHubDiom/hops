/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hops.transaction.context;

import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;
import io.hops.exception.LockUpgradeException;
import io.hops.exception.StorageException;
import io.hops.exception.TransactionContextException;
import io.hops.metadata.common.FinderType;
import io.hops.metadata.hdfs.dal.INodeDataAccess;
import io.hops.transaction.EntityManager;
import io.hops.transaction.lock.BaseINodeLock;
import io.hops.transaction.lock.Lock;
import io.hops.transaction.lock.TransactionLockTypes;
import io.hops.transaction.lock.TransactionLocks;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hdfs.protocol.HdfsConstantsClient;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.INodeDirectory;
import org.apache.hadoop.hdfs.server.namenode.ServerlessNameNode;
import org.apache.hadoop.hdfs.serverless.cache.InMemoryINodeCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class INodeContext extends BaseEntityContext<Long, INode> {

  protected final static Log LOG = LogFactory.getLog(INodeContext .class);

  private final INodeDataAccess<INode> dataAccess;

  private final Map<String, INode> inodesNameParentIndex =
      new HashMap<>();
  private final Map<Long, List<INode>> inodesParentIndex =
      new HashMap<>();
  private final List<INode> renamedInodes = new ArrayList<>();

  public INodeContext(INodeDataAccess dataAccess) {
    this.dataAccess = dataAccess;
  }

  private InMemoryINodeCache getMetadataCache() {
    ServerlessNameNode instance = ServerlessNameNode.tryGetNameNodeInstance(false);

    if (instance == null) {
      return null;
    }

    return instance.getNamesystem().getMetadataCacheManager().getINodeCache();
  }

  /**
   * Check the local metadata cache for the specified INode.
   * @param id The ID of the desired INode.
   * @return The INode with the specified ID, or null if the INode is not in the cache.
   */
  private INode checkCache(long id) {
    if (!EntityContext.areMetadataCacheReadsEnabled()) return null;

    InMemoryINodeCache metadataCache = getMetadataCache();
    if (metadataCache == null) {
      LOG.warn("Cannot check local, in-memory metadata cache bc Serverless NN instance is null.");
      return null;
    }
    return metadataCache.getByINodeId(id);
  }

  /**
   * Check the local metadata cache for the specified INode.
   * @param path The fully-qualified path of the desired INode.
   * @return The INode for the file/directory at the specified path, or null if the INode is not in the cache.
   */
  private INode checkCache(String path) {
    if (!EntityContext.areMetadataCacheReadsEnabled()) return null;

    InMemoryINodeCache metadataCache = getMetadataCache();
    if (metadataCache == null) {
      LOG.warn("Cannot check local, in-memory metadata cache bc Serverless NN instance is null.");
      return null;
    }
    return metadataCache.getByPath(path);
  }

  /**
   * Check the local metadata cache for the specified INode.
   * @param localName The local name of the desired INode.
   * @param parentId The ID of the parent INode.
   * @return The desired INode if it is was in the cache, otherwise null.
   */
  private INode checkCache(String localName, long parentId) {
    if (!EntityContext.areMetadataCacheReadsEnabled()) return null;

    InMemoryINodeCache metadataCache = getMetadataCache();
    if (metadataCache == null) {
      LOG.warn("Cannot check local, in-memory metadata cache bc Serverless NN instance is null.");
      return null;
    }
    return metadataCache.getByParentINodeIdAndLocalName(parentId, localName);
  }

  /**
   * Attempt to add an INode to the cache, if it is non-null. If the INode parameter is null, this just returns.
   *
   * This will fail to add the INode to the cache of the full path name cannot be retrieved using data
   * already contained locally (i.e., going to NDB is not an option here).
   *
   * @param node The INode to add to the cache.
   */
  private void tryUpdateCache(INode node) throws TransactionContextException, StorageException {
    if (node == null || !EntityContext.areMetadataCacheWritesEnabled()) {
      return;
    }

    InMemoryINodeCache metadataCache = getMetadataCache();
    if (metadataCache == null) return;

    String fullPathName = node.getFullPathName();
    metadataCache.put(fullPathName, node.getId(), node);
  }

  @Override
  public void clear() throws TransactionContextException {
    super.clear();
    inodesNameParentIndex.clear();
    inodesParentIndex.clear();
    renamedInodes.clear();
  }

  @Override
  public INode find(FinderType<INode> finder, Object... params)
      throws TransactionContextException, StorageException {
    INode.Finder iFinder = (INode.Finder) finder;
    switch (iFinder) {
      case ByINodeIdFTIS:
        return findByInodeIdFTIS(iFinder, params);
      case ByNameParentIdAndPartitionId:
        return findByNameParentIdAndPartitionIdPK(iFinder, params);
    }
    throw new RuntimeException(UNSUPPORTED_FINDER);
  }

  @Override
  public Collection<INode> findList(FinderType<INode> finder, Object... params)
      throws TransactionContextException, StorageException {
    INode.Finder iFinder = (INode.Finder) finder;
    switch (iFinder) {
      case ByParentIdFTIS: // TODO: Add support for this case for local, in-memory metadata cache.
        return findByParentIdFTIS(iFinder, params);
      case ByParentIdAndPartitionId:
        return findByParentIdAndPartitionIdPPIS(iFinder,params);
      case ByNamesParentIdsAndPartitionIds:
        return findBatch(iFinder, params);
      case ByNamesParentIdsAndPartitionIdsCheckLocal:
        return findBatchWithLocalCacheCheck(iFinder, params);
    }
    throw new RuntimeException(UNSUPPORTED_FINDER);
  }

  @Override
  public void remove(INode iNode) throws TransactionContextException {
    super.remove(iNode);
    inodesNameParentIndex.remove(iNode.nameParentKey());
    if (isLogTraceEnabled()) {
      log("removed-inode", "id", iNode.getId(), "name", iNode.getLocalName(), "parent_id", iNode.getParentId(),
              "partition_id", iNode.getPartitionId());
    }
  }

  @Override
  public void update(INode iNode) throws TransactionContextException {
    super.update(iNode);
    inodesNameParentIndex.put(iNode.nameParentKey(), iNode);
    if(isLogTraceEnabled()) {
      log("updated-inode", "id", iNode.getId(), "name", iNode.getLocalName(), "parent_id", iNode.getParentId(),
              "partition_id", iNode.getPartitionId());
    }
  }

  /**
   * Return a collection containing all INodes to be removed/deleted from intermediate storage.
   * @return
   */
  public Collection<INode> getRemovedINodes() {
    return getRemoved();
  }

  /**
   * Return a collection containing all the new INodes to be persisted to intermediate storage.
   * This collection includes renamed INodes.
   */
  public Collection<INode> getAddedINodes() {
    Collection<INode> added = new ArrayList<INode>(getAdded());
    added.addAll(renamedInodes);

    return added;
  }

  /**
   * Return a collection of all INodes that exist already in intermediate storage and will be
   * updated in some way by the upcoming transactional commit.
   */
  public Collection<INode> getUpdatedINodes() {
    return getModified();
  }

  /**
   * Return a collection of INodes that will be invalidated by the upcoming transactional commit.
   * That means that any Serverless NameNodes that are caching these INodes will need to invalidate
   * their caches.
   *
   * Specifically, this includes any removed and modified INodes. This does NOT include any added INodes
   * (i.e., INodes that do not already exist in intermediate storage).
   */
  public Collection<INode> getInvalidatedINodes() {
    Collection<INode> removed = getRemoved();
    Collection<INode> modified = getModified();
    Collection<INode> invalidated = new ArrayList<INode>(removed);
    invalidated.addAll(modified);

    if (removed.size() > 0 || modified.size() > 0) {
      // We try to print their full path names here if we can. But if not, then print local names.
      // If we're running in a thread from the FSNamesystem's executor service for subtree operations,
      // then we'll probably not be able to resolve the full path names. This is due to the transaction
      // context being local to each thread, so we don't have the ability to access storage right now.
      List<String> removedToStr = new ArrayList<>();
      List<String> modifiedToStr = new ArrayList<>();

      try {
        for (INode removedNode : removed) {
          String fullPath = removedNode.getFullPathName();
          removedToStr.add(fullPath);
        }

        for (INode modifiedNode : modified) {
          String fullPath = modifiedNode.getFullPathName();
          modifiedToStr.add(fullPath);
        }
      } catch (RuntimeException ex) {
        removedToStr.clear();
        modifiedToStr.clear();

        // This can happen if we're a thread from an Executor Service during a subtree operation.
        for (INode removedNode : removed) {
          String fullPath = removedNode.getLocalName();
          removedToStr.add(fullPath);
        }

        for (INode modifiedNode : modified) {
          String fullPath = modifiedNode.getLocalName();
          modifiedToStr.add(fullPath);
        }
      } catch (TransactionContextException | StorageException ex) {
        ex.printStackTrace();
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug("Transaction will REMOVE the following INodes (" + removed.size() + "): " +
                StringUtils.join(removedToStr, ", "));
        LOG.debug("Transaction will MODIFY the following INodes (" + modified.size() + "): " +
                StringUtils.join(modifiedToStr, ", "));
        LOG.debug("Transaction will alter (i.e., remove or modify) a total of " + invalidated.size() + " INodes.");
      }
    }
    return invalidated;
  }

  @Override
  public void prepare(TransactionLocks lks)
      throws TransactionContextException, StorageException {

    // if the list is not empty then check for the lock types
    // lock type is checked after when list length is checked
    // because sometimes in the tx handler, the 'acquire lock'
    // function is empty and in that case tlm will throw
    // null pointer exceptions
    Collection<INode> removed = getRemoved();
    Collection<INode> added = new ArrayList<>(getAdded());
    added.addAll(renamedInodes);
    Collection<INode> modified = getModified();

    if (lks.containsLock(Lock.Type.INode)) {
      BaseINodeLock hlk = (BaseINodeLock) lks.getLock(Lock.Type.INode);
      if (!removed.isEmpty()) {
        for (INode inode : removed) {
          TransactionLockTypes.INodeLockType lock =
              hlk.getLockedINodeLockType(inode);
          if (lock != null &&
              lock != TransactionLockTypes.INodeLockType.WRITE && lock !=
              TransactionLockTypes.INodeLockType.WRITE_ON_TARGET_AND_PARENT) {
            throw new LockUpgradeException(
                "Trying to remove inode " + inode.getLocalName() + ", id=" + inode.getId() +
                        " acquired lock was " + lock);
          }
        }
      }

      if (!modified.isEmpty()) {
        for (INode inode : modified) {
          TransactionLockTypes.INodeLockType lock =
              hlk.getLockedINodeLockType(inode);
          if (lock != null &&
              lock != TransactionLockTypes.INodeLockType.WRITE && lock !=
              TransactionLockTypes.INodeLockType.WRITE_ON_TARGET_AND_PARENT) {
            throw new LockUpgradeException(
                "Trying to update inode " + inode.getLocalName() + ", id=" + inode.getId() +
                    " acquired lock was " + lock);
          }
        }
      }
    }
    
    final boolean refreshRootCache = RootINodeCache.isRootInCache() &&
        (modified.stream().filter(inode -> inode.isRoot()).count() > 0
        || added.stream().filter(inode -> inode.isRoot()).count() > 0);
    if(refreshRootCache){
      LOG.trace("Root Inode has been updated, force RootINodeCache to " +
          "refresh");
      RootINodeCache.forceRefresh();
    }
    
    dataAccess.prepare(removed, added, modified);
  }

  @Override
  public void snapshotMaintenance(TransactionContextMaintenanceCmds cmds,
      Object... params) throws TransactionContextException {
    HdfsTransactionContextMaintenanceCmds hopCmds =
        (HdfsTransactionContextMaintenanceCmds) cmds;
    switch (hopCmds) {
      case INodePKChanged:
        //delete the previous row from db
        INode inodeBeforeChange = (INode) params[0];
        INode inodeAfterChange = (INode) params[1];
        super.remove(inodeBeforeChange);
        try {
          inodeAfterChange.setPartitionIdNoPersistance(INode.calculatePartitionId(inodeAfterChange.getParentId(),inodeAfterChange
              .getLocalName(), inodeAfterChange.myDepth()));
        } catch (StorageException e) {
          throw new TransactionContextException(e);
        }
        renamedInodes.add(inodeAfterChange);
        if (isLogTraceEnabled()) {
          log("removed-inode-snapshot-maintenance", "id", inodeBeforeChange.getId(), "name",
                  inodeBeforeChange.getLocalName(), "parent_id", inodeBeforeChange.getParentId(), "partition_id", inodeBeforeChange
                          .getPartitionId());
          log("added-inode-snapshot-maintenance", "id",
                  inodeAfterChange.getId(), "name", inodeAfterChange.getLocalName(),
                  "parent_id", inodeAfterChange.getParentId(), "partition_id", inodeAfterChange.getPartitionId());
        }
        break;
      case Concat:
        // do nothing
        // why? files y and z are merged into file x.
        // all the blocks will be added to file x and the inodes y and z will be deleted.
        // Inode deletion is handled by the concat function
        break;
    }
  }

  @Override
  Long getKey(INode iNode) {
    return iNode.getId();
  }


  private INode findByInodeIdFTIS(INode.Finder inodeFinder, Object[] params)
      throws TransactionContextException, StorageException {
    INode result = null;
    final Long inodeId = (Long) params[0];

    // First, check the local, in-memory metadata cache.
    result = checkCache(inodeId);
    if (result != null) {
      if (LOG.isTraceEnabled()) LOG.trace("Successfully retrieved INode '" + result.getLocalName() + "' (ID=" +
              inodeId + ") from local metadata cache.");
      return result;
    }

    if (contains(inodeId)) {
      result = get(inodeId);
      if(result!=null) {
        if (LOG.isTraceEnabled()) LOG.trace("Successfully retrieved INode '" + result.getLocalName() + "' (ID=" +
                inodeId + ") from transaction context.");

        hit(inodeFinder, result, "id", inodeId, "name", result.getLocalName(), "parent_id", result.getParentId(),
            "partition_id", result.getPartitionId());
      } else{
        if (LOG.isTraceEnabled()) LOG.trace("INode ID=" +
                inodeId + " was in transaction context as null...");
        hit(inodeFinder, result, "id", inodeId);
      }
    } else {
      if (LOG.isTraceEnabled()) LOG.trace("Retrieving INode ID=" + inodeId + " from intermediate storage.");
      aboutToAccessStorage(inodeFinder, params);
      result = dataAccess.findInodeByIdFTIS(inodeId);
      gotFromDB(inodeId, result);
      if (result != null) {
        if (LOG.isTraceEnabled()) LOG.trace("Successfully retrieved INode ID=" + inodeId + " from intermediate storage.");
        inodesNameParentIndex.put(result.nameParentKey(), result);
        miss(inodeFinder, result, "id", inodeId, "name", result.getLocalName(), "parent_id", result.getParentId(),
          "partition_id", result.getPartitionId());
        tryUpdateCache(result);
      } else {
        if (LOG.isTraceEnabled()) LOG.trace("Failed to retrieve INode ID=" + inodeId + " from intermediate storage.");
        miss(inodeFinder, result, "id");
      }
    }
    return result;
  }

  private INode findByNameParentIdAndPartitionIdPK(INode.Finder inodeFinder, Object[] params)
      throws TransactionContextException, StorageException {
    INode result = null;
    final String name = (String) params[0];
    final Long parentId = (Long) params[1];
    final Long partitionId = (Long) params[2];
    Long possibleInodeId = null;
    if (params.length == 4) {
      possibleInodeId = (Long) params[3];
    }
    boolean canCheckCache = true;
    if (params.length == 5) {
      canCheckCache = (boolean) params[4];
    }
    final String nameParentKey = INode.nameParentKey(parentId, name);

    if (canCheckCache) {
      result = checkCache(name, parentId);
      if (result != null) {
        if (LOG.isTraceEnabled()) LOG.trace("Retrieved INode '" + name + "', parentID=" + parentId + " from local metadata cache.");
        return result;
      }
    }

    if (inodesNameParentIndex.containsKey(nameParentKey)) {
      result = inodesNameParentIndex.get(nameParentKey);
      if (!preventStorageCalls() &&
          (currentLockMode.get() == LockMode.WRITE_LOCK)) {
        if (LOG.isTraceEnabled()) LOG.trace("Re-reading INode " + name + " from NDB to upgrade the lock.");
        //trying to upgrade lock. re-read the row from DB
        aboutToAccessStorage(inodeFinder, params);

        result = dataAccess.findInodeByNameParentIdAndPartitionIdPK(name, parentId, partitionId);
        gotFromDBWithPossibleInodeId(result, possibleInodeId);
        inodesNameParentIndex.put(nameParentKey, result);
        missUpgrade(inodeFinder, result, "name", name, "parent_id", parentId, "partition_id", partitionId);
        tryUpdateCache(result);
      } else {
        if (LOG.isTraceEnabled()) LOG.trace("Successfully retrieved INode '" + name + "', parentID=" + parentId + " from INode Hint Cache.");
        hit(inodeFinder, result, "name", name, "parent_id", parentId, "partition_id", partitionId);
      }
    } else {
      if (!isNewlyAdded(parentId) && !containsRemoved(parentId, name)) {
        if (canReadCachedRootINode(name, parentId)) {
          result = RootINodeCache.getRootINode();
          LOG.trace("Reading root inode from the RootINodeCache: " + result);
       } else {
          if (LOG.isTraceEnabled()) LOG.trace("Cannot resolve INode '" + name + "', parentID=" + parentId +
                  " from either cache. Reading from NDB instead.");
          aboutToAccessStorage(inodeFinder, params);

          result = dataAccess.findInodeByNameParentIdAndPartitionIdPK(name, parentId, partitionId);
        }
        gotFromDBWithPossibleInodeId(result, possibleInodeId);
        inodesNameParentIndex.put(nameParentKey, result);
        miss(inodeFinder, result, "name", name, "parent_id", parentId, "partition_id", partitionId,
            "possible_inode_id",possibleInodeId);
        tryUpdateCache(result);
      }
    }
    return result;
  }

  private List<INode> findByParentIdFTIS(INode.Finder inodeFinder, Object[] params)
      throws TransactionContextException, StorageException {
    final Long parentId = (Long) params[0];
    List<INode> result = null;
    if (inodesParentIndex.containsKey(parentId)) {
      result = inodesParentIndex.get(parentId);
      hit(inodeFinder, result, "parent_id", parentId );
    } else {
      aboutToAccessStorage(inodeFinder, params);
      result = syncInodeInstances(
          dataAccess.findInodesByParentIdFTIS(parentId));
      inodesParentIndex.put(parentId, result);
      miss(inodeFinder, result, "parent_id", parentId);
    }
    return result;
  }

  private List<INode> findByParentIdAndPartitionIdPPIS(INode.Finder inodeFinder, Object[] params)
          throws TransactionContextException, StorageException {
    final Long parentId = (Long) params[0];
    final Long partitionId = (Long) params[1];
    List<INode> result = null;
    if (inodesParentIndex.containsKey(parentId)) {
      result = inodesParentIndex.get(parentId);
      hit(inodeFinder, result, "parent_id", parentId, "partition_id",partitionId);
    } else {
      aboutToAccessStorage(inodeFinder, params);
      result = syncInodeInstances(
              dataAccess.findInodesByParentIdAndPartitionIdPPIS(parentId, partitionId));
      inodesParentIndex.put(parentId, result);
      miss(inodeFinder, result, "parent_id", parentId, "partition_id",partitionId);
    }
    return result;
  }

  private List<INode> findBatch(INode.Finder inodeFinder, Object[] params)
      throws TransactionContextException, StorageException {
    final String[] names = (String[]) params[0];
    final long[] parentIds = (long[]) params[1];
    final long[] partitionIds = (long[]) params[2];
    return findBatch(inodeFinder, names, parentIds, partitionIds);
  }

  private List<INode> findBatchWithLocalCacheCheck(INode.Finder inodeFinder,
      Object[] params)
      throws TransactionContextException, StorageException {
    final String[] names = (String[]) params[0];
    final long[] parentIds = (long[]) params[1];
    final long[] partitionIds = (long[]) params[2];
    boolean canUseLocalCache = (boolean) params[3];

    List<String> namesRest = Lists.newArrayList();
    List<Long> parentIdsRest = Lists.newArrayList();
    List<Long> partitionIdsRest = Lists.newArrayList();
    List<Integer> unpopulatedIndeces = Lists.newArrayList();

    List<INode> result = new ArrayList<>(Collections.<INode>nCopies(names
        .length, null));

    for(int i=0; i<names.length; i++){
      final String nameParentKey = INode.nameParentKey(parentIds[i], names[i]);

      INode node;

      // First, check in-memory cache, if we're not doing a write operation.
      if (canUseLocalCache) {
        node = checkCache(names[i], parentIds[i]);
        if (node != null) {
          result.set(i, node);
          continue;
        }
      }

      // Next, try INode Hint Cache.
      node = inodesNameParentIndex.get(nameParentKey);

      if (node != null) {
        result.set(i, node);
        hit(inodeFinder, node, "name", names[i], "parent_id", parentIds[i], "partition_id", partitionIds[i]);
      } else {namesRest.add(names[i]);
        parentIdsRest.add(parentIds[i]);
        partitionIdsRest.add(partitionIds[i]);
        unpopulatedIndeces.add(i);
      }
    }

    if(unpopulatedIndeces.isEmpty()){
      return result;
    }

    if(unpopulatedIndeces.size() == names.length){
      return findBatch(inodeFinder, names, parentIds, partitionIds);
    }else{
      List<INode> batch = findBatch(inodeFinder,
              namesRest.toArray(new String[namesRest.size()]),
              Longs.toArray(parentIdsRest),
              Longs.toArray(partitionIdsRest));
      Iterator<INode> batchIterator = batch.listIterator();
      for(Integer i : unpopulatedIndeces){
        if(batchIterator.hasNext()){
          result.set(i, batchIterator.next());
        }
      }
      return result;
    }
  }

  private List<INode> findBatch(INode.Finder inodeFinder, String[] names,
                                long[] parentIds, long[] partitionIds) throws StorageException, TransactionContextException {
    INode rootINode = null;
    boolean addCachedRootInode = false;
    if (canReadCachedRootINode(names[0], parentIds[0])) {
      rootINode = RootINodeCache.getRootINode();
      if (rootINode != null) {
        if(names[0].equals(INodeDirectory.ROOT_NAME) && parentIds[0] == HdfsConstantsClient.GRANDFATHER_INODE_ID){
          LOG.trace("Reading root inode from the RootINodeCache "+rootINode);
          //remove root from the batch operation. Cached root inode will be added later to the results
          names = Arrays.copyOfRange(names, 1, names.length);
          parentIds = Arrays.copyOfRange(parentIds, 1, parentIds.length);
          partitionIds = Arrays.copyOfRange(partitionIds, 1, partitionIds.length);
          addCachedRootInode=true;
        }
      }
    }

    List<INode> batch = dataAccess.getINodesPkBatched(names, parentIds, partitionIds);
    miss(inodeFinder, batch, "names", Arrays.toString(names), "parent_ids",
            Arrays.toString(parentIds), "partition_ids", Arrays.toString(partitionIds));
    if (rootINode != null && addCachedRootInode) {
      batch.add(0, rootINode);
    }
    return syncInodeInstances(batch);
  }

  private List<INode> syncInodeInstances(List<INode> newInodes) throws TransactionContextException, StorageException {
    List<INode> finalList = new ArrayList<>(newInodes.size());

    if (LOG.isTraceEnabled()) LOG.trace("Retrieved batch of INodes from NDB: " + StringUtils.join(newInodes, ", "));
    
    for (INode inode : newInodes) {
      if (isRemoved(inode.getId())) {
        continue;
      }

      gotFromDB(inode);
      finalList.add(inode);

      String key = inode.nameParentKey();
      if (inodesNameParentIndex.containsKey(key)) {
        if (inodesNameParentIndex.get(key) == null) {
          inodesNameParentIndex.put(key, inode);
        }
      } else {
        inodesNameParentIndex.put(key, inode);
      }

      tryUpdateCache(inode);
    }
    Collections.sort(finalList, INode.Order.ByName);
    return finalList;
  }

  private boolean containsRemoved(final Long parentId, final String name) {
    return contains(new Predicate<ContextEntity>() {
      @Override
      public boolean apply(ContextEntity input) {
        INode iNode = input.getEntity();
        return input.getState() == State.REMOVED &&
            iNode.getParentId() == parentId &&
            iNode.getLocalName().equals(name);
      }
    });
  }

  private void gotFromDBWithPossibleInodeId(INode result,
                                            Long possibleInodeId) {

    if (result != null) {
      gotFromDB(result);
    }

    //Inode does not exists
    if ((result == null && possibleInodeId != null) ||
       //Special Case
       //Note: Inode PK consists of name, pid, and partID.
       //Each Inode also has ID (candidate PK).
       //Here in this fn we are trying to update
       //the ID -> INode Mapping in the cache.
       //The INode is read using PK operation, that is using, name, pid, and partID.
       //Assume INodeIdentifier {ID:1, PID:0, Name:"file", PARTID:1}
       //This INodeIdentifier object is created in the setup phase
       //out side the transaction, and without any locking.
       //There is a special case that the file is overwritten by a new
       //file with the same name. In that case the PK of the file
       //will remain the same but INode ID will change.
       //So we might end up in a situation like following
       //INode{ID:2, PID:0, Name:"file", PARTID:1}
       //possibleInodeId:1
       (result != null && possibleInodeId != null &&
               result.getId() != possibleInodeId)) {
      gotFromDB(possibleInodeId, null);
    }
  }

  private boolean canReadCachedRootINode(String name, long parentId) {
    if (name.equals(INodeDirectory.ROOT_NAME) && parentId == HdfsConstantsClient.GRANDFATHER_INODE_ID) {
      if (RootINodeCache.isRootInCache() && currentLockMode.get() == LockMode.READ_COMMITTED) {
        return true;
      } else {
        return false;
      }
    }
    return false;
  }
}
