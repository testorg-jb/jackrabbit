/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.version;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.ActivityViolationException;

import org.apache.commons.collections.map.ReferenceMap;
import org.apache.jackrabbit.core.ItemId;
import org.apache.jackrabbit.core.NodeId;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.PropertyId;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.cluster.UpdateEventChannel;
import org.apache.jackrabbit.core.cluster.UpdateEventListener;
import org.apache.jackrabbit.core.fs.FileSystem;
import org.apache.jackrabbit.core.nodetype.NodeTypeRegistry;
import org.apache.jackrabbit.core.observation.DelegatingObservationDispatcher;
import org.apache.jackrabbit.core.observation.EventStateCollection;
import org.apache.jackrabbit.core.observation.EventStateCollectionFactory;
import org.apache.jackrabbit.core.persistence.PersistenceManager;
import org.apache.jackrabbit.core.state.ChangeLog;
import org.apache.jackrabbit.core.state.ISMLocking;
import org.apache.jackrabbit.core.state.ISMLocking.ReadLock;
import org.apache.jackrabbit.core.state.ItemState;
import org.apache.jackrabbit.core.state.ItemStateCacheFactory;
import org.apache.jackrabbit.core.state.ItemStateException;
import org.apache.jackrabbit.core.state.ItemStateListener;
import org.apache.jackrabbit.core.state.LocalItemStateManager;
import org.apache.jackrabbit.core.state.NodeReferencesId;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.state.PropertyState;
import org.apache.jackrabbit.core.state.SharedItemStateManager;
import org.apache.jackrabbit.core.state.NodeReferences;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.core.virtual.VirtualItemStateProvider;
import org.apache.jackrabbit.spi.Name;
import org.apache.jackrabbit.spi.Path;
import org.apache.jackrabbit.spi.commons.conversion.MalformedPathException;
import org.apache.jackrabbit.spi.commons.name.NameConstants;
import org.apache.jackrabbit.spi.commons.name.PathBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This Class implements a VersionManager.
 */
public class VersionManagerImpl extends AbstractVersionManager
        implements ItemStateListener, UpdateEventListener {

    /**
     * the default logger
     */
    private static Logger log = LoggerFactory.getLogger(VersionManager.class);

    /**
     * The path to the version storage: /jcr:system/jcr:versionStorage
     */
    private static final Path VERSION_STORAGE_PATH;

    /**
     * The path to the version storage: /jcr:system/jcr:versionStorage/jcr:activities
     */
    private static final Path ACTIVITIES_PATH;

    static {
        try {
            PathBuilder builder = new PathBuilder();
            builder.addRoot();
            builder.addLast(NameConstants.JCR_SYSTEM);
            builder.addLast(NameConstants.JCR_VERSIONSTORAGE);
            VERSION_STORAGE_PATH = builder.getPath();

            builder = new PathBuilder();
            builder.addRoot();
            builder.addLast(NameConstants.JCR_SYSTEM);
            builder.addLast(NameConstants.JCR_VERSIONSTORAGE);
            builder.addLast(NameConstants.JCR_ACTIVITIES);
            ACTIVITIES_PATH = builder.getPath();
        } catch (MalformedPathException e) {
            // will not happen. path is always valid
            throw new InternalError("Cannot initialize path");
        }
    }

    /**
     * The persistence manager for the versions
     */
    private final PersistenceManager pMgr;

    /**
     * The file system for this version manager
     */
    private final FileSystem fs;

    /**
     * the version state manager for the version storage
     */
    private VersionItemStateManager sharedStateMgr;

    /**
     * the virtual item state provider that exposes the version storage
     */
    private final VersionItemStateProvider versProvider;

    /**
     * the dynamic event state collection factory
     */
    private final DynamicESCFactory escFactory;

    /**
     * Map of returned items. this is kept for invalidating
     */
    private final Map<ItemId, InternalVersionItem> versionItems =
            new ReferenceMap(ReferenceMap.HARD, ReferenceMap.WEAK);

    /**
     * Creates a new version manager
     *
     */
    public VersionManagerImpl(PersistenceManager pMgr, FileSystem fs,
                              NodeTypeRegistry ntReg,
                              DelegatingObservationDispatcher obsMgr,
                              NodeId rootParentId,
                              NodeId storageId,
                              NodeId activitiesId,
                              ItemStateCacheFactory cacheFactory,
                              ISMLocking ismLocking) throws RepositoryException {
        super(ntReg);
        try {
            this.pMgr = pMgr;
            this.fs = fs;
            this.escFactory = new DynamicESCFactory(obsMgr);

            // need to store the version storage root directly into the persistence manager
            if (!pMgr.exists(storageId)) {
                NodeState root = pMgr.createNew(storageId);
                root.setParentId(rootParentId);
                root.setDefinitionId(ntReg.getEffectiveNodeType(NameConstants.REP_SYSTEM).getApplicableChildNodeDef(
                        NameConstants.JCR_VERSIONSTORAGE, NameConstants.REP_VERSIONSTORAGE, ntReg).getId());
                root.setNodeTypeName(NameConstants.REP_VERSIONSTORAGE);
                PropertyState pt = pMgr.createNew(new PropertyId(storageId, NameConstants.JCR_PRIMARYTYPE));
                pt.setDefinitionId(ntReg.getEffectiveNodeType(NameConstants.REP_SYSTEM).getApplicablePropertyDef(
                        NameConstants.JCR_PRIMARYTYPE, PropertyType.NAME, false).getId());
                pt.setMultiValued(false);
                pt.setType(PropertyType.NAME);
                pt.setValues(new InternalValue[]{InternalValue.create(NameConstants.REP_VERSIONSTORAGE)});
                root.addPropertyName(pt.getName());
                ChangeLog cl = new ChangeLog();
                cl.added(root);
                cl.added(pt);
                pMgr.store(cl);
            }

            // check for jcr:activities
            if (!pMgr.exists(activitiesId)) {
                NodeState root = pMgr.createNew(activitiesId);
                root.setParentId(storageId);
                root.setDefinitionId(ntReg.getEffectiveNodeType(NameConstants.REP_VERSIONSTORAGE).getApplicableChildNodeDef(
                        NameConstants.JCR_ACTIVITIES, NameConstants.REP_ACTIVITIES, ntReg).getId());
                root.setNodeTypeName(NameConstants.REP_ACTIVITIES);
                PropertyState pt = pMgr.createNew(new PropertyId(activitiesId, NameConstants.JCR_PRIMARYTYPE));
                pt.setDefinitionId(ntReg.getEffectiveNodeType(NameConstants.REP_ACTIVITIES).getApplicablePropertyDef(
                        NameConstants.JCR_PRIMARYTYPE, PropertyType.NAME, false).getId());
                pt.setMultiValued(false);
                pt.setType(PropertyType.NAME);
                pt.setValues(new InternalValue[]{InternalValue.create(NameConstants.REP_ACTIVITIES)});
                root.addPropertyName(pt.getName());

                // add activities as child
                NodeState historyState = pMgr.load(storageId);
                historyState.addChildNodeEntry(NameConstants.JCR_ACTIVITIES, activitiesId);
                                
                ChangeLog cl = new ChangeLog();
                cl.added(root);
                cl.added(pt);
                cl.modified(historyState);
                pMgr.store(cl);
            }

            sharedStateMgr = createItemStateManager(pMgr, storageId, ntReg, cacheFactory, ismLocking);

            stateMgr = LocalItemStateManager.createInstance(sharedStateMgr, escFactory, cacheFactory);
            stateMgr.addListener(this);

            NodeState nodeState = (NodeState) stateMgr.getItemState(storageId);
            historyRoot = new NodeStateEx(stateMgr, ntReg, nodeState, NameConstants.JCR_VERSIONSTORAGE);

            nodeState = (NodeState) stateMgr.getItemState(activitiesId);
            activitiesRoot =  new NodeStateEx(stateMgr, ntReg, nodeState, NameConstants.JCR_ACTIVITIES);

            // create the virtual item state provider
            versProvider = new VersionItemStateProvider(
                    getHistoryRootId(), sharedStateMgr);

        } catch (ItemStateException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    public VirtualItemStateProvider getVirtualItemStateProvider() {
        return versProvider;
    }

    /**
     * Return the persistence manager.
     *
     * @return the persistence manager
     */
    public PersistenceManager getPersistenceManager() {
        return pMgr;
    }

    /**
     * {@inheritDoc}
     */
    public void close() throws Exception {
        pMgr.close();
        fs.close();
    }

    /**
     * Returns the event state collection factory.
     * @return the event state collection factory.
     */
    public DynamicESCFactory getEscFactory() {
        return escFactory;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method must not be synchronized since it could cause deadlocks with
     * item-reading listeners in the observation thread.
     */
    protected VersionHistoryInfo createVersionHistory(Session session,
                  final NodeState node, final NodeId copiedFrom)
            throws RepositoryException {
        NodeStateEx state = (NodeStateEx)
                escFactory.doSourced((SessionImpl) session, new SourcedTarget() {
            public Object run() throws RepositoryException {
                return internalCreateVersionHistory(node, copiedFrom);
            }
        });

        if (state == null) {
            throw new VersionException("History already exists for node " + node.getNodeId());
        }
        Name root = NameConstants.JCR_ROOTVERSION;
        return new VersionHistoryInfo(
                state.getNodeId(),
                state.getState().getChildNodeEntry(root, 1).getId());
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method must not be synchronized since it could cause deadlocks with
     * item-reading listeners in the observation thread.
     */
    public NodeId createActivity(Session session, final String title) throws RepositoryException {
        NodeStateEx state = (NodeStateEx)
                escFactory.doSourced((SessionImpl) session, new SourcedTarget() {
            public Object run() throws RepositoryException {
                return internalCreateActivity(title);
            }
        });
        return state.getNodeId();
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method must not be synchronized since it could cause deadlocks with
     * item-reading listeners in the observation thread.
     */
    public void removeActivity(Session session, final NodeId nodeId)
            throws RepositoryException {
        escFactory.doSourced((SessionImpl) session, new SourcedTarget() {
            public Object run() throws RepositoryException {
                InternalActivityImpl act = (InternalActivityImpl) getItem(nodeId);
                internalRemoveActivity(act);
                return null;
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasItem(NodeId id) {
        ReadLock lock = acquireReadLock();
        try {
            return stateMgr.hasItemState(id);
        } finally {
            lock.release();
        }
    }

    /**
     * {@inheritDoc}
     */
    protected InternalVersionItem getItem(NodeId id)
            throws RepositoryException {

        if (id.equals(getHistoryRootId())) {
            return null;
        }
        ReadLock lock = acquireReadLock();
        try {
            synchronized (versionItems) {
                InternalVersionItem item = versionItems.get(id);
                if (item == null) {
                    item = createInternalVersionItem(id);
                    if (item != null) {
                        versionItems.put(id, item);
                    } else {
                        return null;
                    }
                }
                return item;
            }
        } finally {
            lock.release();
        }
    }
    /**
     * {@inheritDoc}
     *
     * this method currently does no modifications to the persistence and just
     * checks if the checkout is valid in respect to a possible activity set on
     * the session
     */
    public Version checkout(NodeImpl node) throws RepositoryException {
        NodeId baseId = NodeId.valueOf(node.getProperty(NameConstants.JCR_BASEVERSION).getString());
        NodeImpl activity = (NodeImpl) node.getSession().getWorkspace().getVersionManager().getActivity();
        if (activity != null) {
            // If there exists another workspace with node N' where N' also has version
            // history H, N' is checked out and the jcr:activity property of N'
            // references A, then the checkout fails with an
            // ActivityViolationException indicating which workspace currently has
            // the checkout.

            // we're currently leverage the fact, that only references to "real"
            // workspaces are recorded.
            NodeId nodeId = activity.getNodeId();
            NodeReferencesId refId = new NodeReferencesId(nodeId);
            if (stateMgr.hasNodeReferences(refId)) {
                try {
                    NodeReferences refs = stateMgr.getNodeReferences(refId);
                    if (refs.hasReferences()) {
                        throw new ActivityViolationException("Unable to checkout. " +
                                "Activity is already used for the same node in " +
                                "another workspace.");
                    }
                } catch (ItemStateException e) {
                    throw new RepositoryException("Error during checkout.", e);
                }
            }

            // TODO:
            // If there is a version in H that is not an eventual predecessor of N but
            // whose jcr:activity references A, then the checkout fails with an
            // ActivityViolationException
            InternalActivityImpl a = (InternalActivityImpl) getItem(nodeId);
            NodeId historyId = NodeId.valueOf(node.getProperty(NameConstants.JCR_VERSIONHISTORY).getString());
            InternalVersionHistory history = (InternalVersionHistory) getItem(historyId);
            InternalVersion version = a.getLatestVersion(history);
            if (version != null) {
                InternalVersion baseVersion = (InternalVersion) getItem(baseId);
                while (baseVersion != null && !baseVersion.getId().equals(version.getId())) {
                    baseVersion = baseVersion.getLinearPredecessor();
                }
                if (baseVersion == null) {
                    throw new ActivityViolationException("Unable to checkout. " +
                            "Activity is used by another version on a different branch: " + version.getName());
                }
            }
        }
        return (VersionImpl)
                ((SessionImpl) node.getSession()).getNodeById(baseId);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method must not be synchronized since it could cause deadlocks with
     * item-reading listeners in the observation thread.
     */
    public Version checkin(final NodeImpl node) throws RepositoryException {
        InternalVersion version = (InternalVersion)
                escFactory.doSourced((SessionImpl) node.getSession(), new SourcedTarget() {
            public Object run() throws RepositoryException {
                InternalVersionHistory vh;
                if (node.isNodeType(NameConstants.MIX_VERSIONABLE)) {
                    // in full versioning, the history id can be retrieved via
                    // the property
                    String histUUID = node.getProperty(NameConstants.JCR_VERSIONHISTORY).getString();
                    vh = getVersionHistory(NodeId.valueOf(histUUID));
                    return internalCheckin((InternalVersionHistoryImpl) vh, node, false);
                } else {
                    // in simple versioning the history id needs to be calculated
                    vh = getVersionHistoryOfNode(node.getNodeId());
                    return internalCheckin((InternalVersionHistoryImpl) vh, node, true);
                }
            }
        });

        return (VersionImpl)
                ((SessionImpl) node.getSession()).getNodeById(version.getId());
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method must not be synchronized since it could cause deadlocks with
     * item-reading listeners in the observation thread.
     */
    public void removeVersion(VersionHistory history, final Name name)
            throws VersionException, RepositoryException {

        final VersionHistoryImpl historyImpl = (VersionHistoryImpl) history;
        if (!historyImpl.hasNode(name)) {
            throw new VersionException("Version with name " + name.toString()
                    + " does not exist in this VersionHistory");
        }

        escFactory.doSourced((SessionImpl) history.getSession(), new SourcedTarget() {
            public Object run() throws RepositoryException {
                InternalVersionHistoryImpl vh = (InternalVersionHistoryImpl)
                        historyImpl.getInternalVersionHistory();
                internalRemoveVersion(vh, name);
                return null;
            }
        });
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This method must not be synchronized since it could cause deadlocks with
     * item-reading listeners in the observation thread.
     */
    public Version setVersionLabel(final VersionHistory history,
                                   final Name version, final Name label,
                                   final boolean move)
            throws RepositoryException {

        InternalVersion v = (InternalVersion)
                escFactory.doSourced((SessionImpl) history.getSession(), new SourcedTarget() {
            public Object run() throws RepositoryException {
                InternalVersionHistoryImpl vh = (InternalVersionHistoryImpl)
                        ((VersionHistoryImpl) history).getInternalVersionHistory();
                return setVersionLabel(vh, version, label, move);
            }
        });

        if (v == null) {
            return null;
        } else {
            return (Version)
                    ((SessionImpl) history.getSession()).getNodeByUUID(v.getId().getUUID());
        }
    }

    /**
     * Invoked by some external source to indicate that some items in the
     * versions tree were updated. Version histories are reloaded if possible.
     * Matching items are removed from the cache.
     *
     * @param items items updated
     */
    public void itemsUpdated(Collection<InternalVersionItem> items) {
        ReadLock lock = acquireReadLock();
        try {
            synchronized (versionItems) {
                for (InternalVersionItem item : items) {
                    InternalVersionItem cached = versionItems.remove(item.getId());
                    if (cached != null) {
                        if (cached instanceof InternalVersionHistoryImpl) {
                            InternalVersionHistoryImpl vh = (InternalVersionHistoryImpl) cached;
                            try {
                                vh.reload();
                                versionItems.put(vh.getId(), vh);
                            } catch (RepositoryException e) {
                                log.warn("Unable to update version history: " + e.toString());
                            }
                        }
                    }
                }
            }
        } finally {
            lock.release();
        }
    }

    /**
     * Set an event channel to inform about updates.
     *
     * @param eventChannel event channel
     */
    public void setEventChannel(UpdateEventChannel eventChannel) {
        sharedStateMgr.setEventChannel(eventChannel);
        eventChannel.setListener(this);
    }

    /**
     * {@inheritDoc}
     */
    protected void itemDiscarded(InternalVersionItem item) {
        // evict removed item from cache
        ReadLock lock = acquireReadLock();
        try {
            versionItems.remove(item.getId());
        } finally {
            lock.release();
        }
    }

    /**
     * {@inheritDoc}
     */
    protected boolean hasItemReferences(NodeId id)
            throws RepositoryException {
        return stateMgr.hasNodeReferences(new NodeReferencesId(id));
    }

    /**
     * {@inheritDoc}
     */
    protected NodeStateEx getNodeStateEx(NodeId parentNodeId)
            throws RepositoryException {
        try {
            NodeState state = (NodeState) stateMgr.getItemState(parentNodeId);
            return new NodeStateEx(stateMgr, ntReg, state, null);
        } catch (ItemStateException e) {
            throw new RepositoryException(e);
        }
    }

    /**
     * returns the id of the version history root node
     *
     * @return the id of the version history root node
     */
    NodeId getHistoryRootId() {
        return historyRoot.getState().getNodeId();
    }

    /**
     * Returns the shared item state manager.
     * @return the shared item state manager.
     */
    protected SharedItemStateManager getSharedStateMgr() {
        return sharedStateMgr;
    }

    /**
     * Creates a <code>VersionItemStateManager</code> or derivative.
     *
     * @param pMgr          persistence manager
     * @param rootId        root node id
     * @param ntReg         node type registry
     * @param cacheFactory  cache factory
     * @param ismLocking    the ISM locking implementation
     * @return item state manager
     * @throws ItemStateException if an error occurs
     */
    protected VersionItemStateManager createItemStateManager(PersistenceManager pMgr,
                                                             NodeId rootId,
                                                             NodeTypeRegistry ntReg,
                                                             ItemStateCacheFactory cacheFactory,
                                                             ISMLocking ismLocking)
            throws ItemStateException {
        return new VersionItemStateManager(pMgr, rootId, ntReg, cacheFactory, ismLocking);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Not used.
     */
    public void stateCreated(ItemState created) {
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Not used.
     */
    public void stateModified(ItemState modified) {
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Remove item from cache on removal.
     */
    public void stateDestroyed(ItemState destroyed) {
        // evict removed item from cache
        ReadLock lock = acquireReadLock();
        try {
            versionItems.remove(destroyed.getId());
        } finally {
            lock.release();
        }
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Not used.
     */
    public void stateDiscarded(ItemState discarded) {
    }

    //--------------------------------------------------< UpdateEventListener >

    /**
     * {@inheritDoc}
     */
    public void externalUpdate(ChangeLog changes, List events,
                               long timestamp, String userData)
            throws RepositoryException {
        EventStateCollection esc = getEscFactory().createEventStateCollection(null);
        esc.addAll(events);
        esc.setTimestamp(timestamp);
        esc.setUserData(userData);

        sharedStateMgr.externalUpdate(changes, esc);
    }

    //--------------------------------------------------------< inner classes >

    public static final class DynamicESCFactory implements EventStateCollectionFactory {

        /**
         * the observation manager
         */
        private DelegatingObservationDispatcher obsMgr;

        /**
         * the current event source
         */
        private SessionImpl source;


        /**
         * Creates a new event state collection factory
         * @param obsMgr dispatcher
         */
        public DynamicESCFactory(DelegatingObservationDispatcher obsMgr) {
            this.obsMgr = obsMgr;
        }

        /**
         * {@inheritDoc}
         * <p/>
         * This object uses one instance of a <code>LocalItemStateManager</code>
         * to update data on behalf of many sessions. In order to maintain the
         * association between update operation and session who actually invoked
         * the update, an internal event source is used.
         */
        public synchronized EventStateCollection createEventStateCollection()
                throws RepositoryException {
            if (source == null) {
                throw new RepositoryException("Unknown event source.");
            }
            return createEventStateCollection(source);
        }

        /**
         * {@inheritDoc}
         * <p/>
         * This object uses one instance of a <code>LocalItemStateManager</code>
         * to update data on behalf of many sessions. In order to maintain the
         * association between update operation and session who actually invoked
         * the update, an internal event source is used.
         */
        public EventStateCollection createEventStateCollection(SessionImpl source) {
            return obsMgr.createEventStateCollection(source, VERSION_STORAGE_PATH);
        }

        /**
         * Executes the given runnable using the given event source.
         *
         * @param eventSource event source
         * @param runnable the runnable to execute
         * @return the return value of the executed runnable
         * @throws RepositoryException if an error occurs
         */
        public synchronized Object doSourced(SessionImpl eventSource, SourcedTarget runnable)
                throws RepositoryException {
            this.source = eventSource;
            try {
                return runnable.run();
            } finally {
                this.source = null;
            }
        }
    }

    private abstract class SourcedTarget {
        public abstract Object run() throws RepositoryException;
    }
}
