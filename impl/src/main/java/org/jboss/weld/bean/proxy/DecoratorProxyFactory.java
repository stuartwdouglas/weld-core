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
import java.util.Collections;

import javassist.NotFoundException;
import javassist.bytecode.AccessFlag;
import javassist.bytecode.Bytecode;
import javassist.bytecode.ClassFile;

import org.jboss.weld.exceptions.WeldException;
import org.jboss.weld.injection.FieldInjectionPoint;
import org.jboss.weld.injection.ParameterInjectionPoint;
import org.jboss.weld.injection.WeldInjectionPoint;
import org.jboss.weld.util.bytecode.BytecodeUtils;
import org.jboss.weld.util.bytecode.DescriptorUtils;
import org.jboss.weld.util.bytecode.MethodUtils;

/**
 * This special proxy factory is mostly used for abstract decorators. When a
 * delegate field is injected, the abstract methods directly invoke the
 * corresponding method on the delegate. All other cases forward the calls to
 * the {@link BeanInstance} for further processing.
 * 
 * @author David Allen
 */
public class DecoratorProxyFactory<T> extends ProxyFactory<T>
{
   public static final String             PROXY_SUFFIX = "DecoratorProxy";
   private final WeldInjectionPoint<?, ?> delegateInjectionPoint;
   private final Field                    delegateField;

   public DecoratorProxyFactory(Class<T> proxyType, WeldInjectionPoint<?, ?> delegateInjectionPoint)
   {
      super(proxyType, Collections.EMPTY_SET);
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

   private void addHandlerInitializerMethod(ClassFile proxyClassType) throws Exception
   {
      proxyClassType.addMethod(MethodUtils.makeMethod(Modifier.PRIVATE, void.class, "_initMH", new Class[] { Object.class }, new Class[] {}, createMethodHandlerInitializerBody(proxyClassType), proxyClassType));
   }

   private Bytecode createMethodHandlerInitializerBody(ClassFile proxyClassType)
   {
      Bytecode b = new Bytecode(proxyClassType.getConstPool(), 1, 2);
      invokeMethodHandler(proxyClassType, b, proxyClassType.getName(), "_initMH", new String[] { "Ljava/lang/Object;" }, "V");
      log.trace("Created MH initializer body for decorator proxy:  " + getBeanType());
      return b;
   }

   @Override
   protected void addMethodsFromClass(ClassFile proxyClassType)
   {
      String initializerMethod = null;
      int delegateParameterPosition = -1;
      if (delegateInjectionPoint instanceof ParameterInjectionPoint<?, ?>)
      {
         ParameterInjectionPoint<?, ?> parameterIP = (ParameterInjectionPoint<?, ?>) delegateInjectionPoint;
         if (parameterIP.getMember() instanceof Method)
         {
            initializerMethod = ((Method) parameterIP.getMember()).getName();
            delegateParameterPosition = parameterIP.getPosition();
         }
      }
      try
      {
         if (delegateParameterPosition >= 0)
         {
            addHandlerInitializerMethod(proxyClassType);
         }
         for (Method method : getBeanType().getMethods())
         {
            if (!method.getDeclaringClass().getName().equals("java.lang.Object") || method.getName().equals("toString"))
            {
               Bytecode methodBody = null;
               if ((delegateParameterPosition >= 0) && (initializerMethod.equals(method.getName())))
               {
                  methodBody = createDelegateInitializerCode(proxyClassType, method, delegateParameterPosition);
               }
               if (Modifier.isAbstract(method.getModifiers()))
               {
                  methodBody = createAbstractMethodCode(proxyClassType, method);
               }

               if (methodBody != null)
               {
                  log.trace("Adding method " + method);
                  proxyClassType.addMethod(MethodUtils.makeMethod(AccessFlag.PUBLIC, method.getReturnType(), method.getName(), method.getParameterTypes(), method.getExceptionTypes(), methodBody, proxyClassType));
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
   protected String getProxyNameSuffix()
   {
      return PROXY_SUFFIX;
   }

   private Bytecode createAbstractMethodCode(ClassFile file, Method method) throws NotFoundException
   {
      if ((delegateField != null) && (!Modifier.isPrivate(delegateField.getModifiers())))
      {
         // Call the corresponding method directly on the delegate
         Bytecode b = new Bytecode(file.getConstPool());
         b.setMaxLocals(MethodUtils.calculateMaxLocals(method));
         // load the delegate field
         b.addAload(0);
         b.addGetfield(file.getName(), delegateField.getName(), DescriptorUtils.classToStringRepresentation(delegateField.getType()));
         int localVariables = 1;
         String methodDescriptor = DescriptorUtils.getMethodDescriptor(method);
         for (int i = 0; i < method.getParameterTypes().length; ++i)
         {
            Class<?> type = method.getParameterTypes()[i];
            BytecodeUtils.addLoadInstruction(b, DescriptorUtils.classToStringRepresentation(type), localVariables);
            if (type == long.class || type == double.class)
            {
               localVariables = localVariables + 2;
            }
            else
            {
               localVariables++;
            }
         }
         if (delegateField.getType().isInterface())
         {
            b.addInvokeinterface(delegateField.getType().getName(), method.getName(), methodDescriptor, localVariables);
         }
         else
         {
            b.addInvokevirtual(delegateField.getType().getName(), method.getName(), methodDescriptor);
         }
         BytecodeUtils.addReturnInstruction(b, method.getReturnType());
         return b;
      }
      else
      {
         return createInterceptorBody(file, method);
      }
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
    * @return
    */
   private Bytecode createDelegateInitializerCode(ClassFile file, Method intializerMethod, int delegateParameterPosition)
   {
      Bytecode b = new Bytecode(file.getConstPool());
      // we need to push all the pareters on the stack to call the corresponding
      // superclass arguments
      b.addAload(0); // load this
      int localVariables = 1;
      int actualDelegateParamterPosition = 0;
      String methodDescriptor = DescriptorUtils.getMethodDescriptor(intializerMethod);
      for (int i = 0; i < intializerMethod.getParameterTypes().length; ++i)
      {
         if (i == delegateParameterPosition)
         {
            // figure out the actual position of the delegate in the local
            // variables
            actualDelegateParamterPosition = localVariables;
         }
         Class<?> type = intializerMethod.getParameterTypes()[i];
         BytecodeUtils.addLoadInstruction(b, DescriptorUtils.classToStringRepresentation(type), localVariables);
         if (type == long.class || type == double.class)
         {
            localVariables = localVariables + 2;
         }
         else
         {
            localVariables++;
         }
      }
      b.addInvokespecial(file.getSuperclass(), intializerMethod.getName(), methodDescriptor);
      // if this method returns a value it is now sitting on top of the stack
      // we will leave it there are return it later

      // now we need to call _initMH
      b.addAload(0); // load this
      b.addAload(actualDelegateParamterPosition); // load the delegate
      b.addInvokevirtual(file.getName(), "_initMH", "(Ljava/lang/Object;)V");
      // return the object from the top of the stack that we got from calling
      // the superclass method earlier
      BytecodeUtils.addReturnInstruction(b, intializerMethod.getReturnType());
      b.setMaxLocals(localVariables);
      return b;

   }

}
