/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
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

package org.jboss.weld.bean.proxy;

import static org.jboss.weld.logging.messages.BeanMessage.PROXY_INSTANTIATION_BEAN_ACCESS_FAILED;
import static org.jboss.weld.logging.messages.BeanMessage.PROXY_INSTANTIATION_FAILED;

import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.Decorator;
import javax.enterprise.inject.spi.InjectionPoint;

import org.jboss.weld.context.SerializableContextualInstanceImpl;
import org.jboss.weld.exceptions.WeldException;
import org.jboss.weld.manager.BeanManagerImpl;
import org.jboss.weld.serialization.spi.ContextualStore;
import org.jboss.weld.util.reflection.Reflections;
import org.jboss.weld.util.reflection.SecureReflections;

/**
 * @author Marius Bogoevici
 */
public class DecorationHelper<T>
{
   private static ThreadLocal<Stack<DecorationHelper<?>>> helperStackHolder = new ThreadLocal<Stack<DecorationHelper<?>>>()
   {
      @Override protected Stack<DecorationHelper<?>> initialValue()
      {
         return new Stack<DecorationHelper<?>>();
      }
   };

   private final Class<T> proxyClassForDecorator;

   private final TargetBeanInstance targetBeanInstance;

   private T originalInstance;

   private T previousDelegate;

   private int counter;

   private BeanManagerImpl beanManager;
   private final ContextualStore contextualStore;
   private final Bean<?> bean;
   private final String contextId;

   List<Decorator<?>> decorators;

   public DecorationHelper(String contextId, TargetBeanInstance originalInstance, Bean<?> bean, Class<T> proxyClassForDecorator, BeanManagerImpl beanManager, ContextualStore contextualStore, List<Decorator<?>> decorators)
   {
      this.originalInstance = Reflections.<T>cast(originalInstance.getInstance());
      this.targetBeanInstance = originalInstance;
      this.beanManager = beanManager;
      this.contextualStore = contextualStore;
      this.decorators = new LinkedList<Decorator<?>>(decorators);
      this.proxyClassForDecorator = proxyClassForDecorator;
      this.bean = bean;
      this.contextId = contextId;
      counter = 0;
   }

   public static Stack<DecorationHelper<?>> getHelperStack()
   {
      return helperStackHolder.get();
   }

   public DecoratorProxyMethodHandler createMethodHandler(InjectionPoint injectionPoint, CreationalContext<?> creationalContext, Decorator<Object> decorator)
   {
      Object decoratorInstance = beanManager.getReference(injectionPoint, decorator, creationalContext);
      SerializableContextualInstanceImpl<Decorator<Object>, Object> serializableContextualInstance = new SerializableContextualInstanceImpl<Decorator<Object>, Object>(contextId, decorator, decoratorInstance, null, contextualStore);
      return new DecoratorProxyMethodHandler(serializableContextualInstance, previousDelegate);
   }

   public T getNextDelegate(InjectionPoint injectionPoint, CreationalContext<?> creationalContext)
   {
      if (counter == decorators.size())
      {
         previousDelegate = originalInstance;
         return originalInstance;
      }
      else
      {
         try
         {
            T proxy = SecureReflections.newInstance(proxyClassForDecorator);
            TargetBeanInstance newTargetBeanInstance = new TargetBeanInstance(targetBeanInstance);
            newTargetBeanInstance.setInterceptorsHandler(createMethodHandler(injectionPoint, creationalContext, Reflections.<Decorator<Object>>cast(decorators.get(counter++))));
            ProxyFactory.setBeanInstance(contextId, proxy, newTargetBeanInstance, bean);
            previousDelegate = proxy;
            return proxy;
         }
         catch (InstantiationException e)
         {
            throw new WeldException(PROXY_INSTANTIATION_FAILED, e, this);
         }
         catch (IllegalAccessException e)
         {
            throw new WeldException(PROXY_INSTANTIATION_BEAN_ACCESS_FAILED, e, this);
         }

      }
   }

}
