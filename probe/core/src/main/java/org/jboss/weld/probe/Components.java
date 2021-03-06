/*
 * JBoss, Home of Professional Open Source
 * Copyright 2014, Red Hat, Inc., and individual contributors
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
package org.jboss.weld.probe;

import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.ContextNotActiveException;
import javax.enterprise.context.ConversationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.context.SessionScoped;
import javax.enterprise.context.spi.Context;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Decorator;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.Interceptor;
import javax.enterprise.inject.spi.SessionBeanType;

import org.jboss.weld.bean.ForwardingBean;
import org.jboss.weld.bean.ManagedBean;
import org.jboss.weld.bean.ProducerField;
import org.jboss.weld.bean.ProducerMethod;
import org.jboss.weld.bean.SessionBean;
import org.jboss.weld.bean.builtin.AbstractBuiltInBean;
import org.jboss.weld.bean.builtin.ExtensionBean;
import org.jboss.weld.bean.builtin.ee.EEResourceProducerField;
import org.jboss.weld.ejb.spi.EjbDescriptor;
import org.jboss.weld.exceptions.IllegalStateException;
import org.jboss.weld.manager.BeanManagerImpl;
import org.jboss.weld.serialization.spi.BeanIdentifier;
import org.jboss.weld.util.collections.ImmutableMap;

/**
 * A few utility methods and classes related to CDI components.
 *
 * @author Martin Kouba
 */
final class Components {

    static final Map<String, Class<? extends Annotation>> INSPECTABLE_SCOPES = ImmutableMap.<String, Class<? extends Annotation>> builder()
            .put("application", ApplicationScoped.class).put("session", SessionScoped.class).put("conversation", ConversationScoped.class).build();

    static final Comparator<Class<?>> PROBE_COMPONENT_CLASS_COMPARATOR = new Comparator<Class<?>>() {

        @Override
        public int compare(Class<?> o1, Class<?> o2) {
            // Probe components should have the lowest priority when sorting
            int result = Boolean.compare(isProbeComponent(o1), isProbeComponent(o2));
            // Unless decided compare the class names lexicographically
            return result == 0 ? o1.getName().compareTo(o2.getName()) : result;
        }
    };

    private Components() {
    }

    /**
     *
     * @param bean
     * @param beanManager
     * @return
     */
    static Object findContextualInstance(Bean<?> bean, BeanManagerImpl beanManager) {
        Context context;
        try {
            context = beanManager.getContext(bean.getScope());
        } catch (ContextNotActiveException e) {
            return null;
        }
        return context.get(bean);
    }

    /**
     *
     * @param beanIdentifier
     * @return a generated id for the given beanIdentifier
     */
    static String getId(BeanIdentifier beanIdentifier) {
        return getId(beanIdentifier.asString());
    }

    /**
     *
     * @param identifier
     * @return a generated id for the given string identifier
     */
    static String getId(String identifier) {
        try {
            // TODO find a better algorithm, UUID is using MD5
            return UUID.nameUUIDFromBytes(identifier.getBytes("UTF-8")).toString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     *
     * @param builtinBean
     * @return a generated id for the given built-in bean
     */
    static String getBuiltinBeanId(AbstractBuiltInBean<?> builtinBean) {
        return getId(builtinBean.getTypes().toString());
    }

    /**
     *
     * @param bean
     * @param probe
     * @return the set of dependents
     */
    static Set<Dependency> getDependents(Bean<?> bean, Probe probe) {
        Set<Dependency> dependents = new HashSet<Dependency>();
        for (Bean<?> candidate : probe.getBeans()) {
            if (candidate.equals(bean)) {
                continue;
            }
            BeanManager beanManager = probe.getBeanManager(candidate);
            if (beanManager == null) {
                // Don't process built-in beans
                continue;
            }
            Set<InjectionPoint> injectionPoints = candidate.getInjectionPoints();
            if (injectionPoints != null && !injectionPoints.isEmpty()) {
                for (InjectionPoint injectionPoint : injectionPoints) {

                    // At this point unsatisfied or ambiguous dependency should not exits
                    Bean<?> candidateDependency = beanManager.resolve(beanManager.getBeans(injectionPoint.getType(),
                            injectionPoint.getQualifiers().toArray(new Annotation[injectionPoint.getQualifiers().size()])));
                    boolean satisfies = false;

                    if (isBuiltinBeanButNotExtension(candidateDependency)) {
                        satisfies = bean.equals(probe.getBean(Components.getBuiltinBeanId((AbstractBuiltInBean<?>) candidateDependency)));
                    } else {
                        satisfies = bean.equals(candidateDependency);
                    }
                    if (satisfies) {
                        dependents.add(new Dependency(candidate, injectionPoint));
                    }
                }
            }
        }
        return dependents;
    }

    /**
     *
     * @param bean
     * @param beanManager
     * @param probe
     * @return the set of dependencies
     */
    static Set<Dependency> getDependencies(Bean<?> bean, BeanManager beanManager, Probe probe) {
        Set<Dependency> dependencies = new HashSet<Dependency>();
        Set<InjectionPoint> injectionPoints = bean.getInjectionPoints();
        if (injectionPoints != null && !injectionPoints.isEmpty()) {
            for (InjectionPoint injectionPoint : injectionPoints) {
                // At this point unsatisfied or ambiguous dependency should not exits
                Bean<?> dependency = beanManager.resolve(beanManager.getBeans(injectionPoint.getType(),
                        injectionPoint.getQualifiers().toArray(new Annotation[injectionPoint.getQualifiers().size()])));
                if (isBuiltinBeanButNotExtension(dependency)) {
                    dependency = probe.getBean(Components.getBuiltinBeanId((AbstractBuiltInBean<?>) dependency));
                }
                dependencies.add(new Dependency(dependency, injectionPoint));
            }
        }
        return dependencies;
    }

    static boolean isBuiltinScope(Class<? extends Annotation> scope) {
        return Dependent.class.equals(scope) || RequestScoped.class.equals(scope) || ApplicationScoped.class.equals(scope) || SessionScoped.class.equals(scope)
                || ConversationScoped.class.equals(scope);
    }

    static boolean isInspectableScope(String id) {
        return INSPECTABLE_SCOPES.containsKey(id);
    }

    static boolean isInspectableScope(Class<? extends Annotation> scope) {
        return INSPECTABLE_SCOPES.containsValue(scope);
    }

    static String getInspectableScopeId(Class<? extends Annotation> scope) {
        for (Entry<String, Class<? extends Annotation>> inspectable : INSPECTABLE_SCOPES.entrySet()) {
            if (inspectable.getValue().equals(scope)) {
                return inspectable.getKey();
            }
        }
        return null;
    }

    static SessionBeanType getSessionBeanType(EjbDescriptor<?> ejbDescriptor) {
        if (ejbDescriptor.isStateless()) {
            return SessionBeanType.STATELESS;
        } else if (ejbDescriptor.isStateful()) {
            return SessionBeanType.STATEFUL;
        } else if (ejbDescriptor.isSingleton()) {
            return SessionBeanType.SINGLETON;
        }
        throw new IllegalStateException("Not a session bean");
    }

    /**
     * Built-in beans require a special treatment.
     *
     * @param bean
     * @return <code>true</code> if the bean is a built-in bean but not an extension, <code>false</code> otherwise
     */
    static boolean isBuiltinBeanButNotExtension(Bean<?> bean) {
        return bean instanceof AbstractBuiltInBean<?> && !(bean instanceof ExtensionBean);
    }

    /**
     *
     * @param manager
     * @return the number of enabled beans, without built-in, extensions, interceptors, decorators
     */
    static Integer getNumberOfEnabledBeans(BeanManagerImpl manager) {
        List<Bean<?>> enabledBeans = manager.getBeans();
        int count = 0;
        for (Bean<?> bean : enabledBeans) {
            if (!Components.isBuiltinBeanButNotExtension(bean)) {
                count++;
            }
        }
        return count;
    }

    /**
     *
     * @param clazz
     * @return <code>true</code> if the given class is a probe component class, <code>false</code> otherwise
     */
    static boolean isProbeComponent(Class<?> clazz) {
        return clazz.getName().startsWith(Probe.class.getPackage().getName());
    }

    /**
     *
     * @param candidates
     * @return the sorted list of classes, where the probe component class have the lowest priority
     */
    static <T> List<Class<? extends T>> getSortedProbeComponetCandidates(Collection<Class<? extends T>> candidates) {
        List<Class<? extends T>> result = new ArrayList<Class<? extends T>>(candidates);
        Collections.sort(result, Components.PROBE_COMPONENT_CLASS_COMPARATOR);
        return result;
    }

    /**
     *
     * @author Martin Kouba
     */
    static class Dependency {

        private final Bean<?> bean;

        private final InjectionPoint injectionPoint;

        public Dependency(Bean<?> resolvedBean, InjectionPoint injectionPoint) {
            this.bean = resolvedBean;
            this.injectionPoint = injectionPoint;
        }

        public Bean<?> getBean() {
            return bean;
        }

        public InjectionPoint getInjectionPoint() {
            return injectionPoint;
        }

    }

    /**
     *
     * @author Martin Kouba
     */
    static enum BeanKind {

        MANAGED, SESSION, PRODUCER_METHOD, PRODUCER_FIELD, RESOURCE, SYNTHETIC, INTERCEPTOR, DECORATOR, EXTENSION, BUILT_IN;

        static BeanKind from(Bean<?> bean) {
            if (bean == null) {
                return null;
            }
            if (bean instanceof ForwardingBean) {
                // Unwrap the forwarding implementation
                ForwardingBean<?> forwarding = (ForwardingBean<?>) bean;
                bean = forwarding.delegate();
            }

            if (bean instanceof SessionBean) {
                return SESSION;
            } else if (bean instanceof ManagedBean) {
                if (bean instanceof Decorator) {
                    return DECORATOR;
                } else if (bean instanceof Interceptor) {
                    return INTERCEPTOR;
                } else {
                    return MANAGED;
                }
            } else if (bean instanceof EEResourceProducerField) {
                return RESOURCE;
            } else if (bean instanceof ProducerField) {
                return PRODUCER_FIELD;
            } else if (bean instanceof ProducerMethod) {
                return PRODUCER_METHOD;
            } else if (bean instanceof ExtensionBean) {
                return EXTENSION;
            } else if (bean instanceof AbstractBuiltInBean<?>) {
                return BUILT_IN;
            } else {
                return SYNTHETIC;
            }
        }

        static BeanKind from(String value) {
            try {
                return valueOf(value);
            } catch (Exception e) {
                return null;
            }
        }

    }

    /**
     * Priority ranges, as defined in {@link javax.interceptor.Interceptor.PRIORITY}
     *
     * @author Jozef Hartinger
     *
     */
    static enum PriorityRange {
        PLATFORM_BEFORE, LIBRARY_BEFORE, APPLICATION, LIBRARY_AFTER, PLATFORM_AFTER, UNKNOWN;

        @SuppressWarnings("magicnumber")
        static PriorityRange of(int priority) {
            if (priority < 0 || priority >= 5000) {
                return UNKNOWN;
            }
            if (priority >= 4000) {
                return PLATFORM_AFTER;
            }
            if (priority >= 3000) {
                return LIBRARY_AFTER;
            }
            if (priority >= 2000) {
                return APPLICATION;
            }
            if (priority >= 1000) {
                return LIBRARY_BEFORE;
            }
            return PLATFORM_BEFORE;
        }
    }

}
