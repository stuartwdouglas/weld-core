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

package org.jboss.weld.bean.proxy;

import javax.enterprise.inject.spi.Bean;

/**
 * This factory produces client proxies specific for enterprise beans, in
 * particular session beans. It adds the interface
 * {@link EnterpriseBeanInstance} on the proxy.
 * 
 * @author David Allen
 * @author Stuart Douglas
 */
public class EnterpriseProxyFactory<T> extends ProxyFactoryImpl<T>
{
   /**
    * Produces a factory for a specific bean implementation.
    * 
    * @param proxiedBeanType the actual enterprise bean
    */
   public EnterpriseProxyFactory(Class<T> proxiedBeanType, Bean<T> bean)
   {
      super(proxiedBeanType, bean);
   }

   protected void generateClass()
   {
      addInterface(EnterpriseBeanInstance.class);
      super.generateClass();
   };
}
