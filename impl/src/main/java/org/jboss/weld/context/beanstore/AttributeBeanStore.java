/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.context.beanstore;

import java.util.Collection;
import java.util.Iterator;

import org.jboss.weld.context.api.ContextualInstance;
import org.slf4j.cal10n.LocLogger;

import static org.jboss.weld.logging.Category.CONTEXT;
import static org.jboss.weld.logging.LoggerFactory.loggerFactory;
import static org.jboss.weld.logging.messages.ContextMessage.CONTEXTUAL_INSTANCE_ADDED;
import static org.jboss.weld.logging.messages.ContextMessage.CONTEXTUAL_INSTANCE_FOUND;
import static org.jboss.weld.logging.messages.ContextMessage.CONTEXTUAL_INSTANCE_REMOVED;
import static org.jboss.weld.logging.messages.ContextMessage.CONTEXT_CLEARED;

/**
 * <p>
 * A bound bean store backed by attributes. This bean store is "write-through" -
 * if attached it will write any modifications to the backing store immediately.
 * If detached modifications will not be written through. If the bean store is
 * reattched, then any local modifications will be written to the underlying
 * store.
 * </p>
 * <p/>
 * <p>
 * This construct is not thread safe.
 * </p>
 *
 * @author Pete Muir
 * @author Nicklas Karlsson
 * @author David Allen
 */
public abstract class AttributeBeanStore implements BoundBeanStore {

    private static final long serialVersionUID = 8923580660774253916L;
    private static final LocLogger log = loggerFactory().getLogger(CONTEXT);
    private transient volatile LockStore lockStore = new LockStore();

    private final HashMapBeanStore beanStore;
    private final NamingScheme namingScheme;

    private boolean attached;

    public AttributeBeanStore(NamingScheme namingScheme) {
        this.namingScheme = namingScheme;
        this.beanStore = new HashMapBeanStore();
    }

    /**
     * Detach the bean store, causing updates to longer be written through to the
     * underlying store.
     */
    public boolean detach() {
        if (attached) {
            attached = false;
            log.trace("Bean store " + this + " is detached");
            return true;
        } else {
            return false;
        }
    }

    /**
     * <p>
     * Attach the bean store, any updates from now on will be written through to
     * the underlying store.
     * </p>
     * <p/>
     * <p>
     * When the bean store is attached, the detached state is assumed to be
     * authoritative if there are any conflicts.
     * </p>
     */
    public boolean attach() {
        if (!attached) {
            attached = true;
            // beanStore is authoritative, so copy everything to the backing store
            for (String id : beanStore) {
                ContextualInstance<?> instance = beanStore.get(id);
                String prefixedId = getNamingScheme().prefix(id);
                log.trace("Updating underlying store with contextual " + instance + " under ID " + id);
                setAttribute(prefixedId, instance);
            }

            /*
            * Additionally copy anything not in the bean store but in the session
            * into the bean store
            */
            for (String prefixedId : getPrefixedAttributeNames()) {
                String id = getNamingScheme().deprefix(prefixedId);
                if (!beanStore.contains(id)) {
                    ContextualInstance<?> instance = (ContextualInstance<?>) getAttribute(prefixedId);
                    beanStore.put(id, instance);
                    log.trace("Adding detached contextual " + instance + " under ID " + id);
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public boolean isAttached() {
        return attached;
    }

    public <T> ContextualInstance<T> get(String id) {
        ContextualInstance<T> instance = beanStore.get(id);
        log.trace(CONTEXTUAL_INSTANCE_FOUND, id, instance, this);
        return instance;
    }

    public <T> void put(String id, ContextualInstance<T> instance) {
        beanStore.put(id, instance); // moved due to WELD-892
        if (isAttached()) {
            String prefixedId = namingScheme.prefix(id);
            setAttribute(prefixedId, instance);
        }
        log.trace(CONTEXTUAL_INSTANCE_ADDED, instance.getContextual(), id, this);
    }

    public void clear() {
        Iterator<String> it = iterator();
        while (it.hasNext()) {
            String id = it.next();
            if (isAttached()) {
                String prefixedId = namingScheme.prefix(id);
                removeAttribute(prefixedId);
            }
            it.remove();
            log.trace(CONTEXTUAL_INSTANCE_REMOVED, id, this);
        }
        log.trace(CONTEXT_CLEARED, this);
    }

    public boolean contains(String id) {
        return get(id) != null;
    }

    protected NamingScheme getNamingScheme() {
        return namingScheme;
    }

    public Iterator<String> iterator() {
        return beanStore.iterator();
    }

    /**
     * Gets an attribute from the underlying storage
     *
     * @param prefixedId The (prefixed) id of the attribute
     * @return The data
     */
    protected abstract Object getAttribute(String prefixedId);

    /**
     * Removes an attribute from the underlying storage
     *
     * @param prefixedId The (prefixed) id of the attribute to remove
     */
    protected abstract void removeAttribute(String prefixedId);

    /**
     * Gets an enumeration of the attribute names present in the underlying
     * storage
     *
     * @return The attribute names
     */
    protected abstract Collection<String> getAttributeNames();

    /**
     * Gets an enumeration of the attribute names present in the underlying
     * storage
     *
     * @return The attribute names
     */
    protected Collection<String> getPrefixedAttributeNames() {
        return getNamingScheme().filterIds(getAttributeNames());
    }

    /**
     * Sets an instance under a key in the underlying storage
     *
     * @param prefixedId The (prefixed) id of the attribute to set
     * @param instance   The instance
     */
    protected abstract void setAttribute(String prefixedId, Object instance);

    public LockedBean lock(final String id) {
        LockStore lockStore = this.lockStore;
        if(lockStore == null) {
            synchronized (this) {
                lockStore = this.lockStore;
                if(lockStore == null) {
                    this.lockStore = lockStore = new LockStore();
                }
            }
        }
        return lockStore.lock(id);
    }
}
