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
package org.jboss.weld.util.bytecode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import javassist.bytecode.BadBytecode;
import javassist.bytecode.Bytecode;
import javassist.bytecode.ClassFile;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.ExceptionsAttribute;
import javassist.bytecode.MethodInfo;

public class MethodUtils
{
   public static MethodInfo makeMethod(int modifiers, Class<?> returnType, String mname, Class<?>[] parameters, Class<?>[] exceptions, Bytecode body, ClassFile file)
   {
      StringBuilder desc = new StringBuilder("(");
      if (parameters != null)
      {
         for (Class<?> p : parameters)
         {
            desc.append(DescriptorUtils.classToStringRepresentation(p));
         }
      }
      desc.append(")");
      desc.append(DescriptorUtils.classToStringRepresentation(returnType));
      MethodInfo meth = new MethodInfo(file.getConstPool(), mname, desc.toString());
      meth.setAccessFlags(modifiers);
      String[] ex = new String[exceptions.length];
      for (int i = 0; i < exceptions.length; ++i)
      {
         ex[i] = exceptions[i].getName().replace('.', '/');
      }
      ExceptionsAttribute exAt = new ExceptionsAttribute(file.getConstPool());
      exAt.setExceptions(ex);
      meth.setExceptionsAttribute(exAt);
      CodeAttribute ca = body.toCodeAttribute();
      meth.setCodeAttribute(ca);
      try
      {
         ca.computeMaxStack();
      }
      catch (BadBytecode e)
      {
         throw new RuntimeException(e);
      }
      return meth;
   }

   /**
    * Calculates maxLocals required to hold all paramters and this
    * 
    * @return
    */
   public static int calculateMaxLocals(Method method)
   {
      int ret = 0;
      if ((method.getModifiers() & Modifier.STATIC) == 0)
      {
         ret = 1;
      }
      ret += method.getParameterTypes().length;
      for (Class<?> i : method.getParameterTypes())
      {
         if (i == double.class || i == long.class)
         {
            ret++;
         }
      }
      return ret;
   }
}
