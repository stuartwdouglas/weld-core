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

import org.jboss.classfilewriter.ClassFile;
import org.jboss.classfilewriter.code.CodeAttribute;
import org.jboss.weld.util.bytecode.MethodInformation;

/**
 * A {@link BytecodeMethodResolver} that looks up the method using the
 * reflection API.
 * <p>
 * TODO: cache the result somehow
 * 
 * @author Stuart Douglas
 * 
 */
public class DefaultBytecodeMethodResolver implements BytecodeMethodResolver
{

   public void getDeclaredMethod(ClassFile file, CodeAttribute ca, MethodInformation methodInfomation)
   {
      ca.loadType(methodInfomation.getDeclaringClass());
      // now we have the class on the stack
      ca.ldc(methodInfomation.getName());
      // now we need to load the parameter types into an array
      ca.iconst(methodInfomation.getParameterTypes().length);
      ca.anewarray("java.lang.Class");
      for (int i = 0; i < methodInfomation.getParameterTypes().length; ++i)
      {
         ca.dup(); // duplicate the array reference
         ca.iconst(i);
         // now load the class object
         String type = methodInfomation.getParameterTypes()[i];
         ca.loadType(type);
         // and store it in the array
         ca.aastore();
      }
      ca.invokevirtual("java.lang.Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
   }

}
