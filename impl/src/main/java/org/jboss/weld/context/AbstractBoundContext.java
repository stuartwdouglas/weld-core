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
package org.jboss.weld.context;

import org.jboss.weld.context.beanstore.BoundBeanStore;

/**
 * Base class for contexts using a thread local to store a bound bean context
 * 
 * @author Pete Muir
 */
public abstract class AbstractBoundContext<S> extends AbstractManagedContext implements BoundContext<S>
{

   private final ThreadLocal<BoundBeanStore> beanStore;
   
   public AbstractBoundContext(String contextId, boolean multithreaded)
   {
      super(contextId, multithreaded);
      this.beanStore = new ThreadLocal<BoundBeanStore>();
   }

   /**
    * Gets the bean store
    * 
    * @returns The bean store
    */
   protected BoundBeanStore getBeanStore()
   {
      return beanStore.get();
   }

   /**
    * Sets the bean store
    * 
    * @param beanStore The bean store
    */
   protected void setBeanStore(BoundBeanStore beanStore)
   {
      if (beanStore == null)
      {
         this.beanStore.remove();
      }
      else
      {
         this.beanStore.set(beanStore);
      }
   }
   
   @Override
   public void cleanup()
   {
      super.cleanup();
      beanStore.remove();
   }
   
   @Override
   public void activate()
   {
      super.activate();
      getBeanStore().attach();
   }
   
   @Override
   public void deactivate()
   {
      getBeanStore().detach();
      super.deactivate();
   }

}