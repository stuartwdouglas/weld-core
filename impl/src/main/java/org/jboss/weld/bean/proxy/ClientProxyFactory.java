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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Set;

import javassist.util.proxy.MethodHandler;

import javax.enterprise.inject.spi.Bean;

import org.jboss.classfilewriter.AccessFlag;
import org.jboss.classfilewriter.ClassFile;
import org.jboss.classfilewriter.ClassMethod;
import org.jboss.classfilewriter.code.BranchEnd;
import org.jboss.classfilewriter.code.CodeAttribute;
import org.jboss.classfilewriter.code.ExceptionHandler;
import org.jboss.classfilewriter.util.DescriptorUtils;
import org.jboss.weld.util.bytecode.MethodInformation;

/**
 * Proxy factory that generates client proxies, it uses optimizations that
 * are not valid for other proxy types.
 *
 * @author Stuart Douglas
 * @author Marius Bogoevici
 */
public class ClientProxyFactory<T> extends ProxyFactory<T>
{

   public final static String CLIENT_PROXY_SUFFIX = "ClientProxy";

   public ClientProxyFactory(Class<?> proxiedBeanType, Set<? extends Type> typeClosure, Bean<?> bean)
   {
      super(proxiedBeanType, typeClosure, bean);
   }

   public ClientProxyFactory(Class<?> proxiedBeanType, Set<? extends Type> typeClosure, String proxyName, Bean<?> bean)
   {
      super(proxiedBeanType, typeClosure, proxyName, bean);
   }

   /**
    * Calls methodHandler.invoke with a null method parameter in order to
    * get the underlying instance. The invocation is then forwarded to
    * this instance with generated bytecode.
    *
    */
   @Override
   protected void createForwardingMethodBody(ClassFile proxyClassType, CodeAttribute ca, MethodInformation methodInfo)
   {
      Method method = methodInfo.getMethod();
      // we can only use bytecode based invocation for some methods
      // at the moment we restrict it solely to public methods with public
      // return and parameter types
      boolean bytecodeInvocationAllowed = Modifier.isPublic(method.getModifiers()) && Modifier.isPublic(method.getReturnType().getModifiers());
      for (Class<?> paramType : method.getParameterTypes())
      {
         if (!Modifier.isPublic(paramType.getModifiers()))
         {
            bytecodeInvocationAllowed = false;
            break;
         }
      }
      if (!bytecodeInvocationAllowed)
      {
         createInterceptorBody(proxyClassType, ca, methodInfo);
         return;
      }

      // create a new interceptor invocation context whenever we invoke a method on a client proxy
      // we use a try-catch block in order to make sure that endInterceptorContext() is invoked regardless whether
      // the method has succeeded or not
      ExceptionHandler exceptionHandler = ca.exceptionBlockStart("java/lang/Exception");
      ca.invokestatic("org.jboss.weld.bean.proxy.InterceptionDecorationContext", "startInterceptorContext", "()V");

      ca.aload(0);
      ca.getfield(proxyClassType.getName(), "methodHandler", DescriptorUtils.makeDescriptor(MethodHandler.class));
      //pass null arguments to methodHandler.invoke
      ca.aload(0);
      ca.aconstNull();
      ca.aconstNull();
      ca.aconstNull();

      // now we have all our arguments on the stack
      // lets invoke the method
      ca.invokeinterface(MethodHandler.class.getName(), "invoke", "(Ljava/lang/Object;Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;");

      ca.checkcast(methodInfo.getDeclaringClass());

      //now we should have the target bean instance on top of the stack
      // we need to dup it so we still have it to compare to the return value
      ca.dup();

      //lets create the method invocation
      String methodDescriptor = methodInfo.getDescriptor();
      ca.loadMethodParameters();
      if (method.getDeclaringClass().isInterface())
      {
         ca.invokeinterface(methodInfo.getDeclaringClass(), methodInfo.getName(), methodDescriptor);
      }
      else
      {
         ca.invokevirtual(methodInfo.getDeclaringClass(), methodInfo.getName(), methodDescriptor);
      }

      // end the interceptor context, everything was fine
      ca.invokestatic("org.jboss.weld.bean.proxy.InterceptionDecorationContext", "endInterceptorContext", "()V");

      // jump over the catch block
      BranchEnd gotoEnd = ca.gotoInstruction();

      // create catch block
      ca.exceptionBlockEnd(exceptionHandler);
      ca.exceptionHandlerStart(exceptionHandler);
      ca.invokestatic("org.jboss.weld.bean.proxy.InterceptionDecorationContext", "endInterceptorContext", "()V");
      ca.athrow();

      // update the correct address to jump over the catch block
      ca.branchEnd(gotoEnd);

      // if this method returns a primitive we just return
      if (method.getReturnType().isPrimitive())
      {
         ca.returnInstruction();
      }
      else
      {
         // otherwise we have to check that the proxy is not returning 'this;
         // now we need to check if the proxy has return 'this' and if so return
         // an
         // instance of the proxy.
         // currently we have result, beanInstance on the stack.
         ca.dupX1();
         // now we have result, beanInstance, result
         // we need to compare result and beanInstance

         // first we need to build up the inner conditional that just returns
         // the
         // result

         BranchEnd returnInstruction =ca.ifAcmpeq();
         ca.returnInstruction();
         ca.branchEnd(returnInstruction);

         // now add the case where the proxy returns 'this';
         ca.aload(0);
         ca.checkcast(methodInfo.getMethod().getReturnType().getName());
         ca.returnInstruction();
      }
   }

   /**
    * Client proxies use the following hashCode:
    * <code>MyProxyName.class.hashCode()</code>
    *
    */
   @Override
   protected void generateHashCodeMethod(ClassFile proxyClassType)
   {
      ClassMethod method = proxyClassType.addMethod(AccessFlag.PUBLIC, "hashCode", "I");
      CodeAttribute ca = method.getCodeAttribute();
      // MyProxyName.class.hashCode()
      ca.loadClass(proxyClassType.getName());
      // now we have the class object on top of the stack
      ca.invokevirtual("java.lang.Object", "hashCode", "()I");
      // now we have the hashCode
      ca.returnInstruction();
   }



   /**
    * Client proxies are equal to other client proxies for the same bean.
    * <p>
    * The corresponding java code: <code>
    * return other instanceof MyProxyClassType.class
    * </code>
    *
    */
   @Override
   protected void generateEqualsMethod(ClassFile proxyClassType)
   {
      ClassMethod method = proxyClassType.addMethod(AccessFlag.PUBLIC, "equals", "Z", "Ljava/lang/Object;");
      CodeAttribute ca = method.getCodeAttribute();
      ca.aload(1);
      ca.instanceofInstruction(proxyClassType.getName());
      ca.returnInstruction();
   }

   @Override
   protected String getProxyNameSuffix()
   {
      return CLIENT_PROXY_SUFFIX;
   }

}
