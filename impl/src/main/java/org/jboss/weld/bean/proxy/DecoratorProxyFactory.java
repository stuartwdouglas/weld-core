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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Set;

import javassist.NotFoundException;
import javassist.util.proxy.MethodHandler;

import javax.enterprise.inject.spi.Bean;

import org.jboss.classfilewriter.AccessFlag;
import org.jboss.classfilewriter.ClassFile;
import org.jboss.classfilewriter.ClassMethod;
import org.jboss.classfilewriter.code.CodeAttribute;
import org.jboss.classfilewriter.util.DescriptorUtils;
import org.jboss.interceptor.util.proxy.TargetInstanceProxy;
import org.jboss.weld.exceptions.WeldException;
import org.jboss.weld.injection.FieldInjectionPoint;
import org.jboss.weld.injection.ParameterInjectionPoint;
import org.jboss.weld.injection.WeldInjectionPoint;
import org.jboss.weld.util.bytecode.MethodInformation;
import org.jboss.weld.util.bytecode.RuntimeMethodInformation;
import org.jboss.weld.util.bytecode.StaticMethodInformation;

/**
 * This special proxy factory is mostly used for abstract decorators. When a
 * delegate field is injected, the abstract methods directly invoke the
 * corresponding method on the delegate. All other cases forward the calls to
 * the {@link BeanInstance} for further processing.
 * 
 * @author David Allen
 * @author Stuart Douglas
 */
public class DecoratorProxyFactory<T> extends ProxyFactory<T>
{
   public static final String PROXY_SUFFIX = "DecoratorProxy";
   private final WeldInjectionPoint<?, ?> delegateInjectionPoint;
   private final Field delegateField;

   public DecoratorProxyFactory(Class<T> proxyType, WeldInjectionPoint<?, ?> delegateInjectionPoint, Bean<?> bean)
   {
      super(proxyType, Collections.<Type>emptySet(), bean);
      this.delegateInjectionPoint = delegateInjectionPoint;
      if (delegateInjectionPoint instanceof FieldInjectionPoint<?, ?>)
      {
         delegateField = ((FieldInjectionPoint<?, ?>) delegateInjectionPoint).getJavaMember();
      }
      else
      {
         delegateField = null;
      }
   }

   @Override
   protected void addAdditionalInterfaces(Set<Class<?>> interfaces)
   {
      interfaces.add(DecoratorProxy.class);
   }

   /**
    * calls _initMH on the method handler and then stores the result in the
    * methodHandler field as then new methodHandler
    * 
    */
   private void createMethodHandlerInitializer(ClassFile proxyClassType)
   {
      ClassMethod method = proxyClassType.addMethod(AccessFlag.PUBLIC, "_initMH", "V", "Ljava/lang/Object;");
      CodeAttribute ca = method.getCodeAttribute();
      ca.aload(0);
      StaticMethodInformation methodInfo = new StaticMethodInformation("_initMH", new Class[] { Object.class }, void.class, proxyClassType.getName());
      invokeMethodHandler(proxyClassType, ca, methodInfo, false, DEFAULT_METHOD_RESOLVER);
      ca.checkcast("javassist/util/proxy/MethodHandler");
      ca.putfield(proxyClassType.getName(), "methodHandler", DescriptorUtils.makeDescriptor(MethodHandler.class));
      ca.returnInstruction();
      log.trace("Created MH initializer body for decorator proxy:  " + getBeanType());
   }

   @Override
   protected void addMethodsFromClass(ClassFile proxyClassType)
   {
      Method initializerMethod = null;
      int delegateParameterPosition = -1;
      if (delegateInjectionPoint instanceof ParameterInjectionPoint<?, ?>)
      {
         ParameterInjectionPoint<?, ?> parameterIP = (ParameterInjectionPoint<?, ?>) delegateInjectionPoint;
         if (parameterIP.getMember() instanceof Method)
         {
            initializerMethod = ((Method) parameterIP.getMember());
            delegateParameterPosition = parameterIP.getPosition();
         }
      }
      try
      {
         if (delegateParameterPosition >= 0)
         {
            createMethodHandlerInitializer(proxyClassType);
         }
         Class<?> cls = getBeanType();
         while (cls != null)
         {
            for (Method method : cls.getDeclaredMethods())
            {
               MethodInformation methodInfo = new RuntimeMethodInformation(method);
               if (!method.getDeclaringClass().getName().equals("java.lang.Object") || method.getName().equals("toString"))
               {
                  if ((delegateParameterPosition >= 0) && (initializerMethod.equals(method)))
                  {
                     ClassMethod classMethod = addMethod(proxyClassType, AccessFlag.PUBLIC, methodInfo);
                     createDelegateInitializerCode(proxyClassType, classMethod.getCodeAttribute(), methodInfo, delegateParameterPosition);
                  }
                  if (Modifier.isAbstract(method.getModifiers()))
                  {
                     ClassMethod classMethod = addMethod(proxyClassType, AccessFlag.PUBLIC, methodInfo);
                     createAbstractMethodCode(proxyClassType, classMethod.getCodeAttribute(), methodInfo);
                  }

               }
            }
            cls = cls.getSuperclass();
         }
      }
      catch (Exception e)
      {
         throw new WeldException(e);
      }
   }

   @Override
   protected String getProxyNameSuffix()
   {
      return PROXY_SUFFIX;
   }

   private void createAbstractMethodCode(ClassFile file, CodeAttribute ca, MethodInformation method) throws NotFoundException
   {
      if ((delegateField != null) && (!Modifier.isPrivate(delegateField.getModifiers())))
      {
         // Call the corresponding method directly on the delegate
         // load the delegate field
         ca.aload(0);
         ca.getfield(file.getName(), delegateField.getName(), DescriptorUtils.makeDescriptor(delegateField.getType()));
         // load the parameters
         ca.loadMethodParameters();
         // invoke the delegate method
         ca.invokeinterface(delegateField.getType().getName(), method.getName(), method.getDescriptor());
         // return the value if applicable
         ca.returnInstruction();
      }
      else
      {
         if (!Modifier.isPrivate(method.getMethod().getModifiers()))
         {
            // if it is a parameter injection point we need to initalize the
            // injection point then handle the method with the method handler
            createAbstractMethodHandler(file, ca, method);
         }
         else
         {
            // if the delegate is private we need to use the method handler
            createInterceptorBody(file, ca, method);
         }
      }
   }

   private void createAbstractMethodHandler(ClassFile file, CodeAttribute ca, MethodInformation methodInfo)
   {
      // this is slightly different to a normal method handler call, as we pass
      // in a TargetInstanceBytecodeMethodResolver. This resolver uses the
      // method handler to call getTargetClass to get the correct class type to
      // resolve the method with, and then resolves this method
      invokeMethodHandler(file, ca, methodInfo, true, TargetInstanceBytecodeMethodResolver.INSTANCE);
   }

   /**
    * When creates the delegate initializer code when the delegate is injected
    * into a method.
    * 
    * super initializer method is called first, and then _initMH is called
    * 
    * @param file
    * @param initializerName
    * @param delegateParameterPosition
    */
   private void createDelegateInitializerCode(ClassFile file, CodeAttribute ca, MethodInformation intializerMethodInfo, int delegateParameterPosition)
   {
      // we need to push all the pareters on the stack to call the corresponding
      // superclass arguments
      ca.aload(0); // load this
      int localVariables = 1;
      int actualDelegateParamterPosition = 0;
      for (int i = 0; i < intializerMethodInfo.getMethod().getParameterTypes().length; ++i)
      {
         if (i == delegateParameterPosition)
         {
            // figure out the actual position of the delegate in the local
            // variables
            actualDelegateParamterPosition = localVariables;
         }
         Class<?> type = intializerMethodInfo.getMethod().getParameterTypes()[i];
         ca.load(type, localVariables);
         if (type == long.class || type == double.class)
         {
            localVariables = localVariables + 2;
         }
         else
         {
            localVariables++;
         }
      }
      ca.invokespecial(file.getSuperclass(), intializerMethodInfo.getName(), intializerMethodInfo.getDescriptor());
      // if this method returns a value it is now sitting on top of the stack
      // we will leave it there are return it later

      // now we need to call _initMH
      ca.aload(0); // load this
      ca.aload(actualDelegateParamterPosition); // load the delegate
      ca.invokevirtual(file.getName(), "_initMH", "(Ljava/lang/Object;)V");
      // return the object from the top of the stack that we got from calling
      // the superclass method earlier
      ca.returnInstruction();

   }

   protected static class TargetInstanceBytecodeMethodResolver implements BytecodeMethodResolver
   {
      public void getDeclaredMethod(ClassFile proxyClassType, CodeAttribute ca, MethodInformation methodInformation)
      {
         // get the correct class type to use to resolve the method
         MethodInformation targetMethodInfo = new StaticMethodInformation("getTargetClass", methodInformation.getParameterTypes(), "Ljava/lang/Class;", TargetInstanceProxy.class.getName());
         invokeMethodHandler(proxyClassType, ca, targetMethodInfo, false, DEFAULT_METHOD_RESOLVER);
         ca.checkcast("java/lang/Class");
         // now we have the class on the stack
         ca.ldc(methodInformation.getName());
         // now we need to load the parameter types into an array
         ca.iconst(methodInformation.getParameterTypes().length);
         ca.anewarray("java.lang.Class");
         for (int i = 0; i < methodInformation.getParameterTypes().length; ++i)
         {
            ca.dup(); // duplicate the array reference
            ca.iconst(i);
            // now load the class object
            String type = methodInformation.getParameterTypes()[i];
            ca.loadType(type);
            // and store it in the array
            ca.aastore();
         }
         ca.invokevirtual("java.lang.Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
      }


      static final TargetInstanceBytecodeMethodResolver INSTANCE = new TargetInstanceBytecodeMethodResolver();
   }

}
