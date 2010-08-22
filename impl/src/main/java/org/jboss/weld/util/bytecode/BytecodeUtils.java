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

import javassist.bytecode.Bytecode;
import javassist.bytecode.Opcode;

public class BytecodeUtils
{

   /**
    * Push the this pointer and parameters onto the stack
    */
   public static int loadParameters(Bytecode b, String descriptor)
   {
      String[] params = DescriptorUtils.descriptorStringToParameterArray(descriptor);
      // local variables is the number of parameters +1 for this
      // if some of the parameters are wide this may go up.
      int localVariableCount = params.length + 1;
      b.addAload(0); // push this
      int variablePosition = 1; // stores the current position to load from
      for (int i = 0; i < params.length; ++i)
      {
         String param = params[i];
         addLoadInstruction(b, param, variablePosition);
         if (DescriptorUtils.isWide(param))
         {
            variablePosition = variablePosition + 2;
            localVariableCount++;
         }
         else
         {
            variablePosition++;
         }
      }
      return localVariableCount;
   }

   public static void addLoadInstruction(Bytecode code, String type, int variable)
   {
      char tp = type.charAt(0);
      if (tp != 'L' && tp != '[')
      {
         // we have a primitive type
         switch (tp)
         {
         case 'J':
            code.addLload(variable);
            break;
         case 'D':
            code.addDload(variable);
            break;
         case 'F':
            code.addFload(variable);
            break;
         default:
            code.addIload(variable);
         }
      }
      else
      {
         code.addAload(variable);
      }
   }

   public static void addReturnInstruction(Bytecode code, Class<?> type)
   {
      addReturnInstruction(code, DescriptorUtils.classToStringRepresentation(type));
   }
   /**
    * Adds a return instruction given a type in JVM format
    */
   public static void addReturnInstruction(Bytecode code, String type)
   {
      char tp = type.charAt(0);
      if (tp != 'L' && tp != '[')
      {
         // we have a primitive type
         switch (tp)
         {
         case 'V':
            code.add(Opcode.RETURN);
            break;
         case 'J':
            code.add(Opcode.LRETURN);
            break;
         case 'D':
            code.add(Opcode.DRETURN);
            break;
         case 'F':
            code.add(Opcode.FRETURN);
            break;
         default:
            code.add(Opcode.IRETURN);
         }
      }
      else
      {
         code.add(Opcode.ARETURN);
      }
   }

   /**
    * pushes a class type onto the stack from the string representation
    * 
    */
   public static void pushClassType(Bytecode b, String classType)
   {
      if (classType.length() != 1)
      {
         if (classType.startsWith("L") && classType.endsWith(";"))
         {
            classType = classType.substring(1, classType.length() - 1);
         }
         int cpIndex = b.getConstPool().addClassInfo(classType);
         b.addLdc(cpIndex);
      }
      else
      {
         char type = classType.charAt(0);
         switch (type)
         {
         case 'I':
            b.addGetstatic(Integer.class.getName(), "TYPE", "Ljava/lang/Class;");
            break;
         case 'J':
            b.addGetstatic(Long.class.getName(), "TYPE", "Ljava/lang/Class;");
            break;
         case 'S':
            b.addGetstatic(Short.class.getName(), "TYPE", "Ljava/lang/Class;");
            break;
         case 'F':
            b.addGetstatic(Float.class.getName(), "TYPE", "Ljava/lang/Class;");
            break;
         case 'D':
            b.addGetstatic(Double.class.getName(), "TYPE", "Ljava/lang/Class;");
            break;
         case 'B':
            b.addGetstatic(Byte.class.getName(), "TYPE", "Ljava/lang/Class;");
            break;
         case 'C':
            b.addGetstatic(Character.class.getName(), "TYPE", "Ljava/lang/Class;");
            break;
         case 'Z':
            b.addGetstatic(Boolean.class.getName(), "TYPE", "Ljava/lang/Class;");
            break;
         }
      }
   }

   /**
    * inserts a 16 bit offset into the bytecode
    * 
    * @param b
    * @param value
    */
   public static void add16bit(Bytecode b, int value)
   {
      value = value % 65536;
      b.add(value >> 8);
      b.add(value % 256);
   }
}
