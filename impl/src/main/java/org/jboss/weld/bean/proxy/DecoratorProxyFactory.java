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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javassist.NotFoundException;

import javax.enterprise.inject.spi.Bean;

import org.jboss.classfilewriter.ClassMethod;
import org.jboss.classfilewriter.code.CodeAttribute;
import org.jboss.classfilewriter.util.Boxing;
import org.jboss.interceptor.proxy.LifecycleMixin;
import org.jboss.interceptor.util.proxy.TargetInstanceProxy;
import org.jboss.invocation.proxy.MethodBodyCreator;
import org.jboss.invocation.proxy.ProxyFactory;
import org.jboss.weld.exceptions.WeldException;
import org.jboss.weld.injection.FieldInjectionPoint;
import org.jboss.weld.injection.ParameterInjectionPoint;
import org.jboss.weld.injection.WeldInjectionPoint;
import org.jboss.weld.util.bytecode.DescriptorUtils;

/**
 * This special proxy factory is mostly used for abstract decorators. When a
 * delegate field is injected, the abstract methods directly invoke the
 * corresponding method on the delegate. All other cases forward the calls to
 * the {@link BeanInstance} for further processing.
 * 
 * @author David Allen
 * @author Stuart Douglas
 */
public class DecoratorProxyFactory<T> extends ProxyFactoryImpl<T>
{
   public static final String PROXY_SUFFIX = "DecoratorProxy";
   private final WeldInjectionPoint<?, ?> delegateInjectionPoint;
   private final Field delegateField;

   public DecoratorProxyFactory(Class<T> proxyType, WeldInjectionPoint<?, ?> delegateInjectionPoint, Bean<?> bean)
   {
      super(proxyType, bean);
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

   protected void generateClass()
   {
      addInterface(super.getDefaultMethodOverride(), LifecycleMixin.class);
      addInterface(super.getDefaultMethodOverride(), TargetInstanceProxy.class);
      addInterface(new MethodHandlerInitializerBodyCreator(), DecoratorProxy.class);
      super.generateClass();
   };

   @Override
   public MethodBodyCreator getDefaultMethodOverride()
   {
      return new DecoratorProxyBodyCreator();
   }

   /**
    * calls _initMH on the method handler and then stores the result in the
    * methodHandler field as then new methodHandler
    * 
    */
   class MethodHandlerInitializerBodyCreator implements MethodBodyCreator
   {

      public void overrideMethod(ClassMethod method, Method superclassMethod)
      {
         final CodeAttribute ca = method.getCodeAttribute();
         ca.newInstruction(InvocationHandlerAdaptor.class.getName());
         ca.dup();
         ca.dup();
         loadMethodIdentifier(superclassMethod, method);
         ca.aload(0);
         ca.iconst(1);
         ca.anewarray(Object.class.getName());
         ca.dup();
         ca.iconst(0);
         ca.aload(1);
         ca.aastore();
         ca.invokevirtual(Method.class.getName(), "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
         ca.checkcast("javassist/util/proxy/MethodHandler");

         ca.invokespecial(InvocationHandlerAdaptor.class.getName(), "<init>", "(Ljavassist/util/proxy/MethodHandler;)V");
         ca.aload(0);
         ca.swap();
         ca.putfield(method.getClassFile().getName(), ProxyFactory.INVOCATION_HANDLER_FIELD, InvocationHandler.class);
         ca.returnInstruction();
         log.trace("Created MH initializer body for decorator proxy:  " + method.getClassFile().getName());
      }

   }

   class DecoratorProxyBodyCreator implements MethodBodyCreator
   {

      public void overrideMethod(ClassMethod cmeth, Method superclassMethod)
      {
         final CodeAttribute ca = cmeth.getCodeAttribute();
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
            if (!superclassMethod.getDeclaringClass().getName().equals("java.lang.Object") || superclassMethod.getName().equals("toString"))
            {
               if ((delegateParameterPosition >= 0) && (initializerMethod.equals(superclassMethod)))
               {
                  createDelegateInitializerCode(ca, superclassMethod, delegateParameterPosition);
               }
               else if (Modifier.isAbstract(superclassMethod.getModifiers()))
               {
                  createAbstractMethodCode(cmeth, superclassMethod);
               }
               else
               {
                  ca.aload(0);
                  ca.loadMethodParameters();
                  ca.invokespecial(superclassMethod);
                  ca.returnInstruction();
               }
            }
         }
         catch (Exception e)
         {
            throw new WeldException(e);
         }
      }

   }

   private void createAbstractMethodCode(ClassMethod classMethod, Method method) throws NotFoundException
   {
      final CodeAttribute ca = classMethod.getCodeAttribute();
      if ((delegateField != null) && (!Modifier.isPrivate(delegateField.getModifiers())))
      {
         // Call the corresponding method directly on the delegate
         // load the delegate field
         ca.aload(0);
         ca.getfield(getClassName(), delegateField.getName(), DescriptorUtils.classToStringRepresentation(delegateField.getType()));
         // load the parameters
         ca.loadMethodParameters();
         // invoke the delegate method
         ca.invokeinterface(delegateField.getType().getName(), method.getName(), org.jboss.classfilewriter.util.DescriptorUtils.methodDescriptor(method));
         // return the value if applicable
         ca.returnInstruction();
      }
      else
      {
         if (!Modifier.isPrivate(method.getModifiers()))
         {
            // if it is a parameter injection point we need to initalize the
            // injection point then handle the method with the method handler
            createAbstractMethodHandler(classMethod, method);
         }
         else
         {
            // if the delegate is private we need to use the method handler
            super.getDefaultMethodOverride().overrideMethod(classMethod, method);
         }
      }
   }

   private void createAbstractMethodHandler(ClassMethod classMethod, Method method)
   {
      CodeAttribute ca = classMethod.getCodeAttribute();
      ca.aload(0);
      ca.getfield(getClassName(), INVOCATION_HANDLER_FIELD, InvocationHandler.class);
      ca.aload(0);
      // this is slightly different to a normal method handler call, as we pass
      // in a TargetInstanceBytecodeMethodResolver. This resolver uses the
      // method handler to call getTargetClass to get the correct class type to
      // resolve the method with, and then resolves this method
      // get the correct class type to use to resolve the method
      ca.aload(0);
      try
      {
         loadMethodIdentifier(TargetInstanceProxy.class.getMethod("getTargetClass"), classMethod);
      }
      catch (Exception e)
      {
         throw new RuntimeException(e);
      }
      ca.invokevirtual(Method.class.getName(), "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
      ca.checkcast("java/lang/Class");
      // now we have the class on the stack
      ca.ldc(classMethod.getName());
      // now we need to load the parameter types into an array
      ca.iconst(method.getParameterTypes().length);
      ca.anewarray("java.lang.Class");
      for (int i = 0; i < method.getParameterTypes().length; ++i)
      {
         ca.dup(); // duplicate the array reference
         ca.iconst(i);
         // now load the class object
         ca.loadType(classMethod.getParameters()[i]);
         // and store it in the array
         ca.aastore();
      }
      ca.invokevirtual("java.lang.Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
      // now we need to stick the parameters into an array, boxing if nessesary
      String[] params = classMethod.getParameters();
      ca.iconst(params.length);
      ca.anewarray("java/lang/Object");
      int loadPosition = 1;
      for (int i = 0; i < params.length; ++i)
      {
         ca.dup();
         ca.iconst(i);
         String type = params[i];
         if (type.length() == 1)
         { // primitive
            char typeChar = type.charAt(0);
            switch (typeChar)
            {
            case 'I':
               ca.iload(loadPosition);
               Boxing.boxInt(ca);
               break;
            case 'S':
               ca.iload(loadPosition);
               Boxing.boxShort(ca);
               break;
            case 'B':
               ca.iload(loadPosition);
               Boxing.boxByte(ca);
               break;
            case 'Z':
               ca.iload(loadPosition);
               Boxing.boxBoolean(ca);
               break;
            case 'C':
               ca.iload(loadPosition);
               Boxing.boxChar(ca);
               break;
            case 'D':
               ca.dload(loadPosition);
               Boxing.boxDouble(ca);
               loadPosition++;
               break;
            case 'J':
               ca.lload(loadPosition);
               Boxing.boxLong(ca);
               loadPosition++;
               break;
            case 'F':
               ca.fload(loadPosition);
               Boxing.boxFloat(ca);
               break;
            default:
               throw new RuntimeException("Unknown primitive type descriptor: " + typeChar);
            }
         }
         else
         {
            ca.aload(loadPosition);
         }
         ca.aastore();
         loadPosition++;
      }
      ca.invokeinterface(InvocationHandler.class.getName(), "invoke", "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;");

      if (method.getReturnType() != void.class)
      {
         if (method.getReturnType().isPrimitive())
         {
            Boxing.unbox(ca, classMethod.getReturnType());
         }
         else
         {
            ca.checkcast(method.getReturnType().getName());
         }
      }
      ca.returnInstruction();
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
   private void createDelegateInitializerCode(CodeAttribute ca, Method method, int delegateParameterPosition)
   {
      // we need to push all the pareters on the stack to call the corresponding
      // superclass arguments
      ca.aload(0);
      int localVariables = 1;
      int actualDelegateParamterPosition = 0;
      for (int i = 0; i < method.getParameterTypes().length; ++i)
      {
         if (i == delegateParameterPosition)
         {
            // figure out the actual position of the delegate in the local
            // variables
            actualDelegateParamterPosition = localVariables;
         }
         Class<?> type = method.getParameterTypes()[i];
         if (type == long.class || type == double.class)
         {
            localVariables = localVariables + 2;
         }
         else
         {
            localVariables++;
         }
      }
      ca.loadMethodParameters();
      ca.invokespecial(getSuperClassName(), method.getName(), org.jboss.classfilewriter.util.DescriptorUtils.methodDescriptor(method));
      // if this method returns a value it is now sitting on top of the stack
      // we will leave it there are return it later

      // now we need to call _initMH
      ca.aload(0); // load this
      ca.aload(actualDelegateParamterPosition); // load the delegate
      ca.invokevirtual(getClassName(), "_initMH", "(Ljava/lang/Object;)V");
      // return the object from the top of the stack that we got from calling
      // the superclass method earlier
      ca.returnInstruction();

   }

}
