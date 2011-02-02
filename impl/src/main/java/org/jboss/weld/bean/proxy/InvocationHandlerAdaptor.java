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
package org.jboss.weld.bean.proxy;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;

import javassist.util.proxy.MethodHandler;

/**
 * Bridge between a {@link InvocationHandler} and a javassist
 * {@link MethodHandler}
 * 
 * @author Stuart Douglas
 * 
 */
public class InvocationHandlerAdaptor implements InvocationHandler, Serializable
{

   private final MethodHandler methodHandler;

   public InvocationHandlerAdaptor(MethodHandler methodHandler)
   {
      this.methodHandler = methodHandler;
   }

   public Object invoke(Object proxy, Method proceed, Object[] args) throws Throwable
   {
      Method thisMethod = null;
      for (Method proxyMethod : proxy.getClass().getDeclaredMethods())
      {
         if (!proxyMethod.getName().equals(proceed.getName()))
         {
            continue;
         }
         if (!proxyMethod.getReturnType().equals(proceed.getReturnType()))
         {
            continue;
         }
         if (!Arrays.equals(proxyMethod.getParameterTypes(), proceed.getParameterTypes()))
         {
            continue;
         }
         thisMethod = proxyMethod;
         break;
      }
      return methodHandler.invoke(proxy, thisMethod, proceed, args);
   }
}
