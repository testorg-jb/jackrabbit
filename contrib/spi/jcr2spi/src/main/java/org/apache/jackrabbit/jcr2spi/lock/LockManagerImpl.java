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
package org.apache.jackrabbit.jcr2spi.lock;

import org.apache.jackrabbit.jcr2spi.ItemManager;
import org.apache.jackrabbit.jcr2spi.SessionListener;
import org.apache.jackrabbit.jcr2spi.WorkspaceManager;
import org.apache.jackrabbit.jcr2spi.config.CacheBehaviour;
import org.apache.jackrabbit.jcr2spi.hierarchy.NodeEntry;
import org.apache.jackrabbit.jcr2spi.hierarchy.HierarchyEntry;
import org.apache.jackrabbit.jcr2spi.operation.Operation;
import org.apache.jackrabbit.jcr2spi.operation.LockOperation;
import org.apache.jackrabbit.jcr2spi.operation.LockRelease;
import org.apache.jackrabbit.jcr2spi.operation.LockRefresh;
import org.apache.jackrabbit.jcr2spi.state.NodeState;
import org.apache.jackrabbit.jcr2spi.state.Status;
import org.apache.jackrabbit.jcr2spi.state.ItemStateException;
import org.apache.jackrabbit.jcr2spi.state.ItemStateLifeCycleListener;
import org.apache.jackrabbit.jcr2spi.state.ItemState;
import org.apache.jackrabbit.jcr2spi.state.PropertyState;
import org.apache.jackrabbit.spi.LockInfo;
import org.apache.jackrabbit.spi.NodeId;
import org.apache.jackrabbit.name.QName;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import javax.jcr.lock.Lock;
import javax.jcr.lock.LockException;
import javax.jcr.RepositoryException;
import javax.jcr.Node;
import javax.jcr.Item;
import javax.jcr.Session;

import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;

/**
 * <code>LockManagerImpl</code>...
 */
public class LockManagerImpl implements LockManager, SessionListener {

    private static Logger log = LoggerFactory.getLogger(LockManagerImpl.class);

    /**
     * WorkspaceManager used to apply and release locks as well as to retrieve
     * Lock information for a given NodeState.
     * NOTE: The workspace manager must not be used as ItemStateManager.
     */
    private final WorkspaceManager wspManager;
    private final ItemManager itemManager;
    private final CacheBehaviour cacheBehaviour;

    /**
     * Map holding all locks that where created by this <code>Session</code> upon
     * calls to {@link LockManager#lock(NodeState,boolean,boolean)} or to
     * {@link LockManager#getLock(NodeState)}. The map entries are removed
     * only if a lock ends his life by {@link Node#unlock()} or by implicit
     * unlock upon {@link Session#logout()}.
     */
    private final Map lockMap;

    public LockManagerImpl(WorkspaceManager wspManager, ItemManager itemManager,
                           CacheBehaviour cacheBehaviour) {
        this.wspManager = wspManager;
        this.itemManager = itemManager;
        this.cacheBehaviour = cacheBehaviour;
        // use hard references in order to make sure, that entries refering
        // to locks created by the current session are not removed.
        lockMap = new HashMap();
    }

    /**
     * @see LockManager#lock(NodeState,boolean,boolean)
     */
    public Lock lock(NodeState nodeState, boolean isDeep, boolean isSessionScoped) throws LockException, RepositoryException {
        nodeState.checkIsSessionState();
        // retrieve node first
        Node lhNode;
        // NOTE: Node must be retrieved from the given NodeState and not from
        // the overlayed workspace nodestate.
        Item item = itemManager.getItem(nodeState.getHierarchyEntry());
        if (item.isNode()) {
            lhNode = (Node) item;
        } else {
            throw new RepositoryException("Internal error: ItemManager returned Property from NodeState");
        }

        // execute the operation
        LockOperation op = LockOperation.create(nodeState, isDeep, isSessionScoped);
        wspManager.execute(op);

        Lock lock = new LockImpl(new LockState(nodeState, op.getLockInfo()), lhNode);
        return lock;
    }

    /**
     * @see LockManager#unlock(NodeState)
     * @param nodeState
     */
    public void unlock(NodeState nodeState) throws LockException, RepositoryException {
        // execute the operation. Note, that its possible that the session is
        // lock holder and still the lock was never accessed. thus the lockMap
        // does not provide sufficient and reliable information.
        Operation op = LockRelease.create(nodeState);
        wspManager.execute(op);

        // if unlock was successfull: clean up lock map and lock life cycle
        // in case the corresponding Lock object exists (and thus has been
        // added to the map.
        if (lockMap.containsKey(nodeState)) {
            LockImpl l = (LockImpl) lockMap.remove(nodeState);
            l.lockState.unlocked();
        }
    }

    /**
     * If the session created a lock on the node with the given state, we already
     * know the lock. Otherwise, the node state and its ancestores are searched
     * for properties indicating a lock.<br>
     * Note, that the flag indicating session-scoped lock cannot be retrieved
     * unless the current session is the lock holder.
     *
     * @see LockManager#getLock(NodeState)
     * @param nodeState
     */
    public Lock getLock(NodeState nodeState) throws LockException, RepositoryException {
        LockImpl l = getLockImpl(nodeState, false);
        // no-lock found or lock doesn't apply to this state -> throw
        if (l == null) {
            throw new LockException("Node with id '" + nodeState.getNodeId() + "' is not locked.");
        }

        // a lock exists either on the given node state or as deep lock inherited
        // from any of the ancestor states.
        return l;
    }

    /**
     * @see LockManager#isLocked(NodeState)
     * @param nodeState
     */
    public boolean isLocked(NodeState nodeState) throws RepositoryException {
        nodeState.checkIsSessionState();

        LockImpl l = getLockImpl(nodeState, false);
        return l != null;
    }

    /**
     * @see LockManager#checkLock(NodeState)
     * @param nodeState
     */
    public void checkLock(NodeState nodeState) throws LockException, RepositoryException {
        nodeState.checkIsSessionState();

        // shortcut: new status indicates that a new state was already added
        // thus, the parent state is not locked by foreign lock.
        if (nodeState.getStatus() == Status.NEW) {
            return;
        }

        LockImpl l = getLockImpl(nodeState, true);
        if (l != null && l.getLockToken() == null) {
            // lock is present and token is null -> session is not lock-holder.
            throw new LockException("Node with id '" + nodeState + "' is locked.");
        } // else: state is not locked at all || session is lock-holder
    }

    /**
     * Returns the lock tokens present on the <code>SessionInfo</code> this
     * manager has been created with.
     *
     * @see LockManager#getLockTokens()
     */
    public String[] getLockTokens() {
        return wspManager.getLockTokens();
    }

    /**
     * Delegates this call to {@link WorkspaceManager#addLockToken(String)}.
     * If this succeeds this method will inform all locks stored in the local
     * map in order to give them the chance to update their lock information.
     *
     * @see LockManager#addLockToken(String)
     */
    public void addLockToken(String lt) throws LockException, RepositoryException {
        wspManager.addLockToken(lt);
        notifyTokenAdded(lt);
    }

    /**
     * If the lock addressed by the token is session-scoped, this method will
     * throw a LockException, such as defined by JSR170 v.1.0.1 for
     * {@link Session#removeLockToken(String)}.<br>Otherwise the call is
     * delegated to {@link WorkspaceManager#removeLockToken(String)} and
     * all locks stored in the local lock map are notified by the removed
     * token in order to give them the chance to update their lock information.
     *
     * @see LockManager#removeLockToken(String)
     */
    public void removeLockToken(String lt) throws LockException, RepositoryException {
        // JSR170 v. 1.0.1 defines that the token of a session-scoped lock may
        // not be moved over to another session. thus removal ist not possible
        // and the lock is always present in the lock map.
        Iterator it = lockMap.values().iterator();
        boolean found = false;
        // loop over cached locks to determine if the token belongs to a session
        // scoped lock, in which case the removal must fail immediately.
        while (it.hasNext() && !found) {
            LockImpl l = (LockImpl) it.next();
            if (lt.equals(l.getLockToken())) {
                // break as soon as the lock associated with the given token was found.
                found = true;
                if (l.isSessionScoped()) {
                    throw new LockException("Cannot remove lock token associated with a session scoped lock.");
                }
            }
        }

        if (!found) {
            String msg = "Unable to remove lock token: lock is held by another session.";
            log.warn(msg);
            throw new RepositoryException(msg);
        }

        // remove lock token from sessionInfo
        wspManager.removeLockToken(lt);
        // inform about this lt being removed from this session
        notifyTokenRemoved(lt);
    }

    //----------------------------------------------------< SessionListener >---
    /**
     *
     * @param session
     * @see SessionListener#loggingOut(Session)
     */
    public void loggingOut(Session session) {
        // remove any session scoped locks:
        NodeState[] lhStates = (NodeState[]) lockMap.keySet().toArray(new NodeState[lockMap.size()]);
        for (int i = 0; i < lhStates.length; i++) {
            NodeState nState = lhStates[i];
            LockImpl l = (LockImpl) lockMap.get(nState);
            if (l.isSessionScoped() && l.getLockToken() != null) {
                try {
                    unlock(nState);
                } catch (RepositoryException e) {
                    log.error("Error while unlocking session scoped lock. Cleaning up local lock status.");
                    // at least clean up local lock map and the locks life cycle
                    l.lockState.unlocked();
                }
            }
        }
    }

    /**
     *
     * @param session
     * @see SessionListener#loggedOut(Session)
     */
    public void loggedOut(Session session) {
        // release all remaining locks without modifying their lock status
        LockImpl[] locks = (LockImpl[]) lockMap.values().toArray(new LockImpl[lockMap.size()]);
        for (int i = 0; i < locks.length; i++) {
            locks[i].lockState.release();
        }
    }

    //------------------------------------------------------------< private >---

    /**
     * Search nearest ancestor that is locked. Returns <code>null</code> if neither
     * the given state nor any of its ancestors is locked.
     * Note, that this methods does NOT check if the given node state would
     * be affected by the lock present on an ancestor state.
     * Note, that in certain cases it might not be possible to detect a lock
     * being present due to the fact that the hierarchy might be imcomplete or
     * not even readable completely.
     *
     * @param nodeState <code>NodeState</code> from which searching starts.
     * Note, that the given state must not have an overlayed state.
     * @return a state holding a lock or <code>null</code> if neither the
     * given state nor any of its ancestors is locked.
     */
    private NodeState getLockHoldingState(NodeState nodeState) {
        /**
         * TODO: should not only rely on existence of jcr:lockIsDeep property
         * but also verify that node.isNodeType("mix:lockable")==true;
         * this would have a negative impact on performance though...
         */
        NodeEntry entry = nodeState.getNodeEntry();
        while (!entry.hasPropertyEntry(QName.JCR_LOCKISDEEP)) {
            NodeEntry parent = entry.getParent();
            if (parent == null) {
                // reached root state without finding a locked node
                return null;
            }
            entry = parent;
        }
        try {
            return entry.getNodeState();
        } catch (ItemStateException e) {
            // may occur if the nodeState is not accessible
            // for this case, assume that no lock exists and delegate final
            // validation to the spi-implementation.
            log.warn("Error while accessing lock holding NodeState", e);
            return null;
        }
    }

    private LockState buildLockState(NodeState nodeState) throws RepositoryException {
        NodeState lockHoldingState = null;
        LockInfo lockInfo;
        try {
            lockInfo = wspManager.getLockInfo(nodeState.getNodeId());
        } catch (LockException e) {
            // no lock present
            return null;
        }

        NodeId lockNodeId = lockInfo.getNodeId();
        if (lockNodeId.equals(nodeState.getId())) {
            lockHoldingState = nodeState;
        } else {
            HierarchyEntry lockedEntry = wspManager.getHierarchyManager().getHierarchyEntry(lockNodeId);
            if (lockedEntry.denotesNode()) {
                try {
                    lockHoldingState = ((NodeEntry) lockedEntry).getNodeState();
                } catch (ItemStateException e) {
                    log.warn("Cannot build LockState");
                    throw new RepositoryException("Cannot build LockState", e);
                }
            } else {
                // should never occur
                throw new RepositoryException("Internal error.");
            }
        }

        if (lockHoldingState == null) {
            return null;
        } else {
            return new LockState(lockHoldingState, lockInfo);
        }
    }

    /**
     * Returns the Lock that applies to the given node state (directly or
     * by an inherited deep lock) or <code>null</code> if the state is not
     * locked at all.
     *
     * @param nodeState
     * @param lazyLockDiscovery If true, no extra check with the server is made in order to
     * determine, whether there is really no lock present. Otherwise, the server
     * is asked if a lock is present.
     * @return LockImpl that applies to the given state or <code>null</code>.
     * @throws RepositoryException
     */
    private LockImpl getLockImpl(NodeState nodeState, boolean lazyLockDiscovery) throws RepositoryException {
        nodeState.checkIsSessionState();

        // shortcut: check if a given state holds a lock, which has been
        // accessed before (thus is known to the manager) irrespective if the
        // current session is the lock holder or not.
        if (lockMap.containsKey(nodeState)) {
            return (LockImpl) lockMap.get(nodeState);
        }

        LockState lState;
        if (lazyLockDiscovery) {
            // try to retrieve a state (ev. a parent state) that holds a lock.
            NodeState lockHoldingState = getLockHoldingState(nodeState);
            if (lockHoldingState == null) {
                // no lock
                return null;
            } else {
                // check lockMap again with the lockholding state
                if (lockMap.containsKey(lockHoldingState)) {
                    return (LockImpl) lockMap.get(lockHoldingState);
                }
                lState = buildLockState(lockHoldingState);
            }
        } else {
            lState = buildLockState(nodeState);
        }

        // Lock has never been access -> build the lock object
        // retrieve lock holding node. note that this may fail if the session
        // does not have permission to see this node.
        if (lState != null && lState.appliesToNodeState(nodeState)) {
            Item lockHoldingNode = itemManager.getItem(lState.lockHoldingState.getHierarchyEntry());
            return new LockImpl(lState, (Node)lockHoldingNode);
        } else {
            // lock exists but does not apply to the given node state
            // passed to this method.
            return null;
        }
    }

    //----------------------------< Notification about modified lock-tokens >---
    /**
     * Notify all <code>Lock</code>s that have been accessed so far about the
     * new lock token present on the session and allow them to reload their
     * lock info.
     *
     * @param lt
     * @throws LockException
     * @throws RepositoryException
     */
    private void notifyTokenAdded(String lt) throws LockException, RepositoryException {
        LockTokenListener[] listeners = (LockTokenListener[]) lockMap.values().toArray(new LockTokenListener[lockMap.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].lockTokenAdded(lt);
        }
    }

    /**
     * Notify all <code>Lock</code>s that have been accessed so far about the
     * removed lock token and allow them to reload their lock info, if necessary.
     *
     * @param lt
     * @throws LockException
     * @throws RepositoryException
     */
    private void notifyTokenRemoved(String lt) throws LockException, RepositoryException {
        LockTokenListener[] listeners = (LockTokenListener[]) lockMap.values().toArray(new LockTokenListener[lockMap.size()]);
        for (int i = 0; i < listeners.length; i++) {
            listeners[i].lockTokenRemoved(lt);
        }
    }

    //--------------------------------------------------------------------------
    private class LockState implements ItemStateLifeCycleListener {

        private final NodeState lockHoldingState;

        private LockInfo lockInfo;
        private boolean isLive = true;

        private LockState(NodeState lockHoldingState, LockInfo lockInfo) {
            lockHoldingState.checkIsSessionState();

            this.lockHoldingState = lockHoldingState;
            this.lockInfo = lockInfo;
        }

        private void refresh() throws RepositoryException {
            // lock is still alive -> send refresh-lock operation.
            Operation op = LockRefresh.create(lockHoldingState);
            wspManager.execute(op);
        }

        /**
         * Returns true, if the given node state is the lockholding state of
         * this Lock object OR if this Lock is deep.
         * Note, that in the latter case this method does not assert, that the
         * given node state is a child state of the lockholding state.
         *
         * @param nodeState that must be the same or a child of the lock holding
         * state stored within this lock object.
         * @return true if this lock applies to the given node state.
         */
        private boolean appliesToNodeState(NodeState nodeState) {
            if (nodeState.getStatus() == Status.NEW) {
                return lockInfo.isDeep();
            } else {
                if (lockHoldingState == nodeState) {
                    return true;
                } else {
                    return lockInfo.isDeep();
                }
            }
        }

        /**
         * Reload the lockInfo from the server.
         *
         * @throws LockException
         * @throws RepositoryException
         */
        private void reloadLockInfo() throws LockException, RepositoryException {
            lockInfo = wspManager.getLockInfo(lockHoldingState.getNodeId());
        }

        /**
         * Release this lock by removing from the lock map and unregistering
         * it from event listening
         */
        private void release() {
            if (lockMap.containsKey(lockHoldingState)) {
                lockMap.remove(lockHoldingState);
            }
            stopListening();
        }

        /**
         * This lock has been removed by the current Session or by an external
         * unlock request. Since a lock will never come back to life after
         * unlocking, it is released an its status is reset accordingly.
         */
        private void unlocked() {
            if (isLive) {
                isLive = false;
                release();
            }
        }

        private void startListening() {
            if (cacheBehaviour == CacheBehaviour.OBSERVATION) {
                try {
                    PropertyState ps = lockHoldingState.getPropertyState(QName.JCR_LOCKISDEEP);
                    ps.addListener(this);
                } catch (ItemStateException e) {
                    log.warn("Internal error", e);
                }
            }
        }

        private void stopListening() {
            if (cacheBehaviour == CacheBehaviour.OBSERVATION) {
                try {
                    PropertyState ps = lockHoldingState.getPropertyState(QName.JCR_LOCKISDEEP);
                    ps.removeListener(this);
                } catch (ItemStateException e) {
                    log.warn("Internal error", e);
                }
            }
        }

        //-------------------------------------< ItemStateLifeCycleListener >---
        public void statusChanged(ItemState state, int previousStatus) {
            if (!isLive) {
                // since we only monitor the removal of the lock (by means
                // of deletion of the jcr:lockIsDeep property, we are not interested
                // if the lock is not active any more.
                return;
            }

            switch (state.getStatus()) {
                case Status.REMOVED:
                    // this lock has been release by someone else (and not by
                    // a call to LockManager#unlock -> clean up and set isLive
                    // flag to false.
                    unlocked();
               default:
                   // not interested (Todo correct?)
            }
        }
    }

    //---------------------------------------------------------------< Lock >---
    /**
     * Inner class implementing the {@link Lock} interface.
     */
    private class LockImpl implements Lock, LockTokenListener {

        private final LockState lockState;
        private final Node node;

        /**
         *
         * @param lockState
         * Note, that the given state must not have an overlayed state.
         * @param lockHoldingNode the lock holding <code>Node</code> itself.
         */
        public LockImpl(LockState lockState, Node lockHoldingNode) {
            this.lockState = lockState;
            this.node = lockHoldingNode;

            // if observation is supported OR if this is a session-scoped lock
            // holded by this session -> store lock in the map
            if (cacheBehaviour == CacheBehaviour.OBSERVATION) {
                lockMap.put(lockState.lockHoldingState, this);
                lockState.startListening();
            } else if (isSessionScoped() && isHoldBySession()) {
                lockMap.put(lockState.lockHoldingState, this);
            }
        }

        /**
         * @see Lock#getLockOwner()
         */
        public String getLockOwner() {
            return getLockInfo().getOwner();
        }

        /**
         * @see Lock#isDeep()
         */
        public boolean isDeep() {
            return getLockInfo().isDeep();
        }

        /**
         * @see Lock#getNode()
         */
        public Node getNode() {
            return node;
        }

        /**
         * @see Lock#getLockToken()
         */
        public String getLockToken() {
            return getLockInfo().getLockToken();
        }

        /**
         * @see Lock#isLive()
         */
        public boolean isLive() throws RepositoryException {
            return lockState.isLive;
        }

        /**
         * @see Lock#isSessionScoped()
         */
        public boolean isSessionScoped() {
            return getLockInfo().isSessionScoped();
        }

        /**
         * @see Lock#refresh()
         */
        public void refresh() throws LockException, RepositoryException {
            if (!isLive()) {
                throw new LockException("Lock is not alive any more.");
            }

            if (getLockToken() == null) {
                // shortcut, since lock is always updated if the session became
                // lock-holder of a foreign lock.
                throw new LockException("Session does not hold lock.");
            } else {
                lockState.refresh();
            }
        }

        //----------------------------------------------< LockTokenListener >---
        /**
         * A lock token as been added to the current Session. If this Lock
         * object is not yet hold by the Session (thus does not know whether
         * the new lock token belongs to it), it must reload the LockInfo
         * from the server.
         *
         * @param lockToken
         * @throws LockException
         * @throws RepositoryException
         * @see LockTokenListener#lockTokenAdded(String)
         */
        public void lockTokenAdded(String lockToken) throws LockException, RepositoryException {
            if (getLockToken() == null) {
                // could be that this affects this lock and session became
                // lock holder -> releoad info to assert.
                lockState.reloadLockInfo();
            }
        }

        /**
         *
         * @param lockToken
         * @throws LockException
         * @throws RepositoryException
         * @see LockTokenListener#lockTokenRemoved(String)
         */
        public void lockTokenRemoved(String lockToken) throws LockException, RepositoryException {
            // reload lock info, if session gave away its lock-holder status
            // for this lock.
            if (lockToken.equals(getLockToken())) {
                lockState.reloadLockInfo();
            }
        }

        //--------------------------------------------------------< private >---
        private LockInfo getLockInfo() {
            return lockState.lockInfo;
        }
        private boolean isHoldBySession() {
            return lockState.lockInfo.getLockToken() != null;
        }
    }

    //--------------------------------------------------< LockTokenListener >---
    /**
     *
     */
    private interface LockTokenListener {

        /**
         *
         * @param lockToken
         * @throws LockException
         * @throws RepositoryException
         */
        void lockTokenAdded(String lockToken) throws LockException, RepositoryException;

        /**
         *
         * @param lockToken
         * @throws LockException
         * @throws RepositoryException
         */
        void lockTokenRemoved(String lockToken) throws LockException, RepositoryException;
    }
}
