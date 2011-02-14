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
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Set;

import javassist.NotFoundException;

import javax.enterprise.inject.spi.Bean;

import org.jboss.classfilewriter.AccessFlag;
import org.jboss.classfilewriter.ClassFile;
import org.jboss.classfilewriter.ClassMethod;
import org.jboss.classfilewriter.DuplicateMemberException;
import org.jboss.classfilewriter.code.BranchEnd;
import org.jboss.classfilewriter.code.CodeAttribute;
import org.jboss.classfilewriter.util.Boxing;
import org.jboss.classfilewriter.util.DescriptorUtils;
import org.jboss.interceptor.proxy.LifecycleMixin;
import org.jboss.weld.exceptions.WeldException;
import org.jboss.weld.introspector.MethodSignature;
import org.jboss.weld.introspector.jlr.MethodSignatureImpl;
import org.jboss.weld.util.bytecode.MethodInformation;
import org.jboss.weld.util.bytecode.RuntimeMethodInformation;
import org.jboss.weld.util.bytecode.StaticMethodInformation;

/**
 * Factory for producing subclasses that are used by the combined interceptors and decorators stack.
 *
 * @author Marius Bogoevici
 */
public class InterceptedSubclassFactory<T> extends ProxyFactory<T>
{
   // Default proxy class name suffix
   public static final String PROXY_SUFFIX = "Subclass";

   private static final String SUPER_DELEGATE_SUFFIX = "$$super";

   private final Set<MethodSignature> enhancedMethodSignatures;

   public InterceptedSubclassFactory(Class<?> proxiedBeanType, Set<? extends Type> typeClosure, Bean<?> bean, Set<MethodSignature> enhancedMethodSignatures)
   {
      this(proxiedBeanType, typeClosure, getProxyName(proxiedBeanType, typeClosure, bean), bean, enhancedMethodSignatures);
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

   public InterceptedSubclassFactory(Class<?> proxiedBeanType, Set<? extends Type> typeClosure, String proxyName, Bean<?> bean, Set<MethodSignature> enhancedMethodSignatures)
   {
      super(proxiedBeanType, typeClosure, proxyName, bean);
      this.enhancedMethodSignatures = enhancedMethodSignatures;
   }

   /**
    * Returns a suffix to append to the name of the proxy class. The name
    * already consists of <class-name>_$$_Weld, to which the suffix is added.
    * This allows the creation of different types of proxies for the same class.
    * 
    * @return a name suffix
    */
   protected String getProxyNameSuffix()
   {
      return PROXY_SUFFIX;
   }

   protected void addMethods(ClassFile proxyClassType)
   {
      // Add all class methods for interception
      addMethodsFromClass(proxyClassType);

      // Add special proxy methods
      addSpecialMethods(proxyClassType);

   }

   protected void addMethodsFromClass(ClassFile proxyClassType)
   {
      try
      {
         // Add all methods from the class heirachy
         Class<?> cls = getBeanType();
         while (cls != null)
         {
            for (Method method : cls.getDeclaredMethods())
            {
               if (!Modifier.isFinal(method.getModifiers()) && enhancedMethodSignatures.contains(new MethodSignatureImpl(method)))
               {
                  try
                  {
                     MethodInformation methodInfo = new RuntimeMethodInformation(method);
                     createDelegateToSuper(proxyClassType, methodInfo);

                     ClassMethod classMethod = addMethod(proxyClassType, AccessFlag.PUBLIC, methodInfo);
                     createForwardingMethodBody(proxyClassType, classMethod.getCodeAttribute(), methodInfo);
                     log.trace("Adding method " + method);
                  }
                  catch (DuplicateMemberException e)
                  {
                     // do nothing. This will happen if superclass methods have
                     // been overridden
                  }
               }
            }
            cls = cls.getSuperclass();
         }
         for (Class<?> c : getAdditionalInterfaces())
         {
            for (Method method : c.getMethods())
            {
               try
               {
                  MethodInformation methodInformation = new RuntimeMethodInformation(method);
                  ClassMethod classMethod = addMethod(proxyClassType, AccessFlag.PUBLIC, methodInformation);
                  createSpecialMethodBody(proxyClassType, methodInformation, classMethod.getCodeAttribute());
                  log.trace("Adding method " + method);
               }
               catch (DuplicateMemberException e)
               {
               }
            }
         }
      }
      catch (Exception e)
      {
         throw new WeldException(e);
      }
   }

   @Override
   protected void createForwardingMethodBody(ClassFile proxyClassType, CodeAttribute ca, MethodInformation method)
   {
      createInterceptorBody(proxyClassType, method, ca, true);
   }

   /**
    * Creates the given method on the proxy class where the implementation
    * forwards the call directly to the method handler.
    *
    * the generated bytecode is equivalent to:
    *
    * return (RetType) methodHandler.invoke(this,param1,param2);
    *
    */
   protected void createInterceptorBody(ClassFile file, MethodInformation methodInfo, CodeAttribute ca, boolean delegateToSuper)
   {
      invokeMethodHandler(file, ca, methodInfo, true, DEFAULT_METHOD_RESOLVER, delegateToSuper);
   }

   private void createDelegateToSuper(ClassFile file, MethodInformation method) throws NotFoundException
   {
      ClassMethod classMethod = file.addMethod(AccessFlag.PUBLIC, method.getName() + SUPER_DELEGATE_SUFFIX, method.getReturnType(), method.getParameterTypes());
      CodeAttribute ca = classMethod.getCodeAttribute();
      // first generate the invokespecial call to the super class method
      ca.aload(0);
      ca.loadMethodParameters();
      ca.invokespecial(file.getSuperclass(), method.getName(), method.getDescriptor());
      ca.returnInstruction();
   }

   /**
    * calls methodHandler.invoke for a given method
    *
    * @param file the current class file
    * @param b the bytecode to add the methodHandler.invoke call to
    * @param declaringClass declaring class of the method
    * @param methodName the name of the method to invoke
    * @param methodParameters method paramters in internal JVM format
    * @param returnType return type in internal format
    * @param addReturnInstruction set to true you want to return the result of
    * @param addProceed
    */
   protected static void invokeMethodHandler(ClassFile file, CodeAttribute ca, MethodInformation methodInfo, boolean addReturnInstruction, BytecodeMethodResolver bytecodeMethodResolver, boolean addProceed)
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
      ca.aload(0);
      ca.getfield(file.getName(), METHOD_HANDLER_FIELD_NAME, DescriptorUtils.makeDescriptor(InvocationHandler.class));

      // this is a self invocation optimisation
      // test to see if this is a self invocation, and if so invokespecial the
      // superclass method directly
      if (addProceed)
      {
         ca.dup();
         ca.checkcast("org.jboss.weld.bean.proxy.CombinedInterceptorAndDecoratorStackMethodHandler");
         ca.invokevirtual("org.jboss.weld.bean.proxy.CombinedInterceptorAndDecoratorStackMethodHandler", "isDisabledHandler", "()Z");
         ca.iconst(0);

         BranchEnd invokeSuperDirectly = ca.ifIcmpeq();
         // now build the bytecode that invokes the super class method
         ca.aload(0);
         // create the method invocation
         ca.loadMethodParameters();
         ca.invokespecial(methodInfo.getDeclaringClass(), methodInfo.getName(), methodInfo.getDescriptor());
         ca.returnInstruction();
         ca.branchEnd(invokeSuperDirectly);
      }
      ca.aload(0);
      bytecodeMethodResolver.getDeclaredMethod(file, ca, methodInfo);

      ca.iconst(methodInfo.getParameterTypes().length + 1);
      ca.anewarray("java.lang.Object");
      ca.dup();
      ca.iconst(0);

      if (addProceed)
      {
         StaticMethodInformation superMethodInfo = new StaticMethodInformation(methodInfo.getName() + SUPER_DELEGATE_SUFFIX, methodInfo.getParameterTypes(), methodInfo.getReturnType(), file.getName());
         bytecodeMethodResolver.getDeclaredMethod(file, ca, superMethodInfo);
      }
      else
      {
         ca.aconstNull();
      }

      ca.aastore();

      int localVariableCount = 1;

      for (int i = 0; i < methodInfo.getParameterTypes().length; ++i)
      {
         String typeString = methodInfo.getParameterTypes()[i];
         ca.dup(); // duplicate the array reference
         ca.iconst(i + 1);
         // load the parameter value
         ca.load(typeString, localVariableCount);
         // box the parameter if nessesary
         Boxing.boxIfNessesary(ca, typeString);
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
         if (methodInfo.getReturnType().equals("V"))
         {
            ca.returnInstruction();
         }
         else if (DescriptorUtils.isPrimitive(methodInfo.getReturnType()))
         {
            Boxing.unbox(ca, methodInfo.getReturnType());
            ca.returnInstruction();
         }
         else
         {
            String castType = methodInfo.getReturnType();
            if (!methodInfo.getReturnType().startsWith("["))
            {
               castType = methodInfo.getReturnType().substring(1).substring(0, methodInfo.getReturnType().length() - 2);
            }
            ca.checkcast(castType);
            ca.returnInstruction();
         }
      }
   }

   /**
    * Adds methods requiring special implementations rather than just
    * delegation.
    * 
    * @param proxyClassType the Javassist class description for the proxy type
    */
   protected void addSpecialMethods(ClassFile proxyClassType)
   {
      try
      {
         // Add special methods for interceptors
         for (Method method : LifecycleMixin.class.getDeclaredMethods())
         {
            log.trace("Adding method " + method);
            MethodInformation methodInfo = new RuntimeMethodInformation(method);
            ClassMethod classMethod = addMethod(proxyClassType, AccessFlag.PUBLIC, methodInfo);
            createInterceptorBody(proxyClassType, methodInfo, classMethod.getCodeAttribute(), false);
         }
         generateGetTargetInstance(proxyClassType);
         generateGetTargetClass(proxyClassType);

      }
      catch (Exception e)
      {
         throw new WeldException(e);
      }
   }

   private static void generateGetTargetInstance(ClassFile proxyClassType)
   {
      ClassMethod classMethod = proxyClassType.addMethod(AccessFlag.PUBLIC, "getTargetInstance", "Ljava/lang/Object;");
      CodeAttribute ca = classMethod.getCodeAttribute();
      ca.aload(0);
      ca.returnInstruction();
   }

   private static void generateGetTargetClass(ClassFile proxyClassType)
   {
      ClassMethod classMethod = proxyClassType.addMethod(AccessFlag.PUBLIC, "getTargetClass", "Ljava/lang/Class;");
      CodeAttribute ca = classMethod.getCodeAttribute();
      ca.loadClass(proxyClassType.getName());
      ca.returnInstruction();
   }


}
