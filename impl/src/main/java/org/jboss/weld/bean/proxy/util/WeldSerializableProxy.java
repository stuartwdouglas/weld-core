/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat, Inc. and/or its affiliates, and individual contributors
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

package org.jboss.weld.bean.proxy.util;

import static org.jboss.weld.logging.messages.BeanMessage.BEAN_NOT_PASIVATION_CAPABLE_IN_SERIALIZATION;
import static org.jboss.weld.logging.messages.BeanMessage.PROXY_DESERIALIZATION_FAILURE;

import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;

import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.PassivationCapable;

import org.jboss.invocation.proxy.ProxyFactory;
import org.jboss.weld.Container;
import org.jboss.weld.bean.proxy.ClientProxyFactory;
import org.jboss.weld.bean.proxy.ProxyFactoryImpl;
import org.jboss.weld.exceptions.WeldException;
import org.jboss.weld.serialization.spi.ContextualStore;

/**
 * A wrapper mostly for client proxies which provides header information useful
 * to generate the client proxy class in a VM before the proxy object is
 * deserialized. Only client proxies really need this extra step for
 * serialization and deserialization since the other proxy classes are generated
 * during bean archive deployment.
 * 
 * @author David Allen
 */
public class WeldSerializableProxy implements Serializable
{

   private static final long serialVersionUID = -7682006876434447753L;

   // Information required to generate proxy classes
   private final String proxyClassName;
   private final String beanId;
   private final InvocationHandler handler;

   public WeldSerializableProxy(Object proxyInstance, Bean<?> bean)
   {
      if (bean instanceof PassivationCapable)
      {
         beanId = ((PassivationCapable) bean).getId();
      }
      else
      {
         throw new WeldException(BEAN_NOT_PASIVATION_CAPABLE_IN_SERIALIZATION, bean);
      }
      this.proxyClassName = proxyInstance.getClass().getName();
      this.handler = ProxyFactory.getInvocationHandlerStatic(proxyInstance);
   }

   /**
    * Resolve the serialized proxy to a real instance.
    * 
    * @return the resolved instance
    * @throws ObjectStreamException if an error occurs
    */
   protected Object readResolve() throws ObjectStreamException
   {
      Bean<?> bean = (Bean<?>) Container.instance().services().get(ContextualStore.class).<Bean<Object>, Object> getContextual(beanId);
      if (proxyClassName.endsWith(ClientProxyFactory.CLIENT_PROXY_SUFFIX))
      {
         return Container.instance().deploymentManager().getClientProxyProvider().getClientProxy(bean);
      }
      else
      {
         // All other proxy classes always exist where a Weld container was
         // deployed
         try
         {
            Class<?> proxyClass = ProxyFactoryImpl.resolveClassLoaderForBeanProxy(bean).loadClass(proxyClassName);
            Object instance = proxyClass.newInstance();
            ProxyFactory.setInvocationHandlerStatic(instance, handler);
            return instance;
         }
         catch (Exception e)
         {
            throw new WeldException(PROXY_DESERIALIZATION_FAILURE, e);
         }
      }
   }
}
