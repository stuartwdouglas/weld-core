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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Set;

import javassist.bytecode.AccessFlag;

import javax.enterprise.inject.spi.Bean;

import org.jboss.classfilewriter.ClassMethod;
import org.jboss.classfilewriter.code.BranchEnd;
import org.jboss.classfilewriter.code.CodeAttribute;
import org.jboss.interceptor.proxy.LifecycleMixin;
import org.jboss.interceptor.util.proxy.TargetInstanceProxy;
import org.jboss.invocation.proxy.MethodBodyCreator;
import org.jboss.invocation.proxy.ProxyFactory;
import org.jboss.weld.introspector.MethodSignature;
import org.jboss.weld.introspector.jlr.MethodSignatureImpl;
import org.jboss.weld.util.bytecode.DescriptorUtils;

/**
 * Factory for producing subclasses that are used by the combined interceptors and decorators stack.
 *
 * @author Marius Bogoevici
 */
public class InterceptedSubclassFactory<T> extends ProxyFactoryImpl<T>
{
   // Default proxy class name suffix
   public static final String PROXY_SUFFIX = "Subclass";

   private static final String SUPER_DELEGATE_SUFFIX = "$$super";

   private final Set<MethodSignature> enhancedMethodSignatures;

   public InterceptedSubclassFactory(Class<?> proxiedBeanType, Bean<?> bean, Set<MethodSignature> enhancedMethodSignatures)
   {
      this(proxiedBeanType, getProxyName(proxiedBeanType, bean), bean, enhancedMethodSignatures);
   }


   /**
    * Creates a new proxy factory when the name of the proxy class is already
    * known, such as during de-serialization
    *
    * @param proxiedBeanType the super-class for this proxy class
    * @param typeClosure the bean types of the bean
    * @param enhancedMethodSignatures a restricted set of methods that need to be intercepted
    *
    */
   public InterceptedSubclassFactory(Class<?> proxiedBeanType, String proxyName, Bean<?> bean, Set<MethodSignature> enhancedMethodSignatures)
   {
      super(ProxyFactoryImpl.proxyConfig(proxiedBeanType, proxyName, bean, PROXY_SUFFIX), bean);
      this.enhancedMethodSignatures = enhancedMethodSignatures;
   }

   protected void generateClass()
   {
      addInterface(new LifecycleMethodBodyCreator(), LifecycleMixin.class);
      addInterface(new TargetInstanceMethodBodyCreator(), TargetInstanceProxy.class);
      super.generateClass();
   };

   @Override
   public MethodBodyCreator getDefaultMethodOverride()
   {
      return new SubclasssesMethodBodyCreator();
   }

   class SubclasssesMethodBodyCreator implements MethodBodyCreator
   {

      public void overrideMethod(ClassMethod method, Method superClassMethod)
      {
         CodeAttribute ca = method.getCodeAttribute();
         if (enhancedMethodSignatures.contains(new MethodSignatureImpl(superClassMethod)))
         {
               ClassMethod superMethod = method.getClassFile().addMethod(AccessFlag.PUBLIC, method.getName() + SUPER_DELEGATE_SUFFIX, method.getReturnType(), method.getParameters());
               superMethod.getCodeAttribute().aload(0);
               superMethod.getCodeAttribute().loadMethodParameters();
               superMethod.getCodeAttribute().invokespecial(superClassMethod);
               superMethod.getCodeAttribute().returnInstruction();
            invokeMethodHandler(method, superClassMethod, true, true);
         }
         else
         {
            ca.aload(0);
            ca.loadMethodParameters();
            ca.invokespecial(superClassMethod);
            ca.returnInstruction();
         }

      }
   }

   class LifecycleMethodBodyCreator implements MethodBodyCreator
   {

      public void overrideMethod(ClassMethod method, Method superClassMethod)
      {
         invokeMethodHandler(method, superClassMethod, true, false);
      }

   }

   private class TargetInstanceMethodBodyCreator implements MethodBodyCreator
   {

      public void overrideMethod(ClassMethod method, Method superclassMethod)
      {
         final CodeAttribute ca = method.getCodeAttribute();
         if (method.getName().equals("getTargetInstance"))
         {
            ca.aload(0);
            ca.returnInstruction();
         }
         else if (method.getName().equals("getTargetClass"))
         {
            ca.loadClass(getClassName());
            ca.returnInstruction();
         }
         else
         {
            throw new RuntimeException("Unkmown method on " + TargetInstanceProxy.class + " " + superclassMethod);
         }

      }

   }

   /**
    * calls methodHandler.invoke for a given method
    *
    */
   protected void invokeMethodHandler(ClassMethod classMethod, Method superClassMethod, boolean addReturnInstruction, boolean addProceed)
   {
      // now we need to build the bytecode. The order we do this in is as
      // follows:
      // load methodHandler
      // dup the methodhandler
      // invoke isDisabledHandler on the method handler to figure out of this is
      // a self invocation.

      // load this
      // load the method object
      // load the proceed method that invokes the superclass version of the
      // current method
      // create a new array the same size as the number of parameters
      // push our parameter values into the array
      // invokeinterface the invoke method
      // add checkcast to cast the result to the return type, or unbox if
      // primitive
      // add an appropriate return instruction
      final CodeAttribute ca = classMethod.getCodeAttribute();
      ca.aload(0);
      ca.getfield(classMethod.getClassFile().getName(), ProxyFactory.INVOCATION_HANDLER_FIELD, DescriptorUtils.classToStringRepresentation(InvocationHandler.class));

      // this is a self invocation optimisation
      // test to see if this is a self invocation, and if so invokespecial the
      // superclass method directly
      if (addProceed)
      {
         ca.dup();
         ca.checkcast("org.jboss.weld.bean.proxy.CombinedInterceptorAndDecoratorStackMethodHandler");
         ca.invokevirtual("org.jboss.weld.bean.proxy.CombinedInterceptorAndDecoratorStackMethodHandler", "isDisabledHandler", "()Z");
         BranchEnd invokeSuperDirectly = ca.ifeq();
         // now build the bytecode that invokes the super class method
         ca.aload(0);
         // create the method invocation
         ca.loadMethodParameters();
         ca.invokespecial(superClassMethod);
         ca.returnInstruction();
         ca.branchEnd(invokeSuperDirectly);
      }
      ca.aload(0);
      getDeclaredMethod(ca, superClassMethod.getDeclaringClass().getName(), classMethod.getName(), classMethod.getParameters());
      /*
       * if (addProceed) { getDeclaredMethod(ca, getClassName(),
       * classMethod.getName() + SUPER_DELEGATE_SUFFIX,
       * classMethod.getParameters()); } else { ca.aconstNull(); }
       */
      ca.iconst(classMethod.getParameters().length);
      ca.anewarray("java.lang.Object");

      int localVariableCount = 1;

      for (int i = 0; i < classMethod.getParameters().length; ++i)
      {
         String typeString = classMethod.getParameters()[i];
         ca.dup(); // duplicate the array reference
         ca.iconst(i);
         // load the parameter value
         ca.load(typeString, localVariableCount);
         // box the parameter if nessesary
         org.jboss.classfilewriter.util.Boxing.boxIfNessesary(ca, typeString);
         // and store it in the array
         ca.aastore();
         if (DescriptorUtils.isWide(typeString))
         {
            localVariableCount = localVariableCount + 2;
         }
         else
         {
            localVariableCount++;
         }
      }
      // now we have all our arguments on the stack
      // lets invoke the method
      ca.invokeinterface(InvocationHandler.class.getName(), "invoke", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;");
      if (addReturnInstruction)
      {
         // now we need to return the appropriate type
         if (classMethod.getReturnType().equals("V"))
         {
            ca.returnInstruction();
         }
         else if (superClassMethod.getReturnType().isPrimitive())
         {
            org.jboss.classfilewriter.util.Boxing.unbox(ca, classMethod.getReturnType());
            ca.returnInstruction();
         }
         else
         {
            String castType = classMethod.getReturnType();
            if (!castType.startsWith("["))
            {
               castType = castType.substring(1).substring(0, castType.length() - 2);
            }
            ca.checkcast(castType);
            ca.returnInstruction();
         }
      }
   }

   /**
    * Loads the specified Method object onto the stack
    */
   private static void getDeclaredMethod(CodeAttribute ca, String declaringClass, String methodName, String[] parameterTypes)
   {
      ca.loadClass(declaringClass);
      // now we have the class on the stack
      ca.ldc(methodName);
      // now we need to load the parameter types into an array
      ca.iconst(parameterTypes.length);
      ca.anewarray("java.lang.Class");
      for (int i = 0; i < parameterTypes.length; ++i)
      {
         ca.dup(); // duplicate the array reference
         ca.iconst(i);
         // now load the class object
         String type = parameterTypes[i];
         ca.loadType(type);
         // and store it in the array
         ca.aastore();
      }
      ca.invokevirtual("java.lang.Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
   }

}
