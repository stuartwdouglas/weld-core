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

/**
 * This class is responsible for generating bytecode fragments to box/unbox
 * whatever happens to be on the top of the stack.
 * 
 * It is the calling codes responsibility to make sure that the correct type is
 * on the stack
 * 
 * @author Stuart Douglas
 * 
 */
public class Boxing
{

   static public void boxIfNessesary(Bytecode b, String desc)
   {
      if (desc.length() == 1)
      {
         char type = desc.charAt(0);
         switch (type)
         {
         case 'I':
            boxInt(b);
            break;
         case 'J':
            boxLong(b);
            break;
         case 'S':
            boxShort(b);
            break;
         case 'F':
            boxFloat(b);
            break;
         case 'D':
            boxDouble(b);
            break;
         case 'B':
            boxByte(b);
            break;
         case 'C':
            boxChar(b);
            break;
         case 'Z':
            boxBoolean(b);
            break;
         default:
            throw new RuntimeException("Cannot box unkown primitive type: " + type);
         }
      }
   }

   static public Bytecode unbox(Bytecode b, String desc)
   {
      char type = desc.charAt(0);
      switch (type)
      {
      case 'I':
         return unboxInt(b);
      case 'J':
         return unboxLong(b);
      case 'S':
         return unboxShort(b);
      case 'F':
         return unboxFloat(b);
      case 'D':
         return unboxDouble(b);
      case 'B':
         return unboxByte(b);
      case 'C':
         return unboxChar(b);
      case 'Z':
         return unboxBoolean(b);
      }
      throw new RuntimeException("Cannot unbox unkown primitive type: " + type);
   }

   static public void boxInt(Bytecode bc)
   {
      bc.addInvokestatic("java.lang.Integer", "valueOf", "(I)Ljava/lang/Integer;");
   }

   static public void boxLong(Bytecode bc)
   {
      bc.addInvokestatic("java.lang.Long", "valueOf", "(J)Ljava/lang/Long;");
   }

   static public void boxShort(Bytecode bc)
   {
      bc.addInvokestatic("java.lang.Short", "valueOf", "(S)Ljava/lang/Short;");
   }

   static public void boxByte(Bytecode bc)
   {
      bc.addInvokestatic("java.lang.Byte", "valueOf", "(B)Ljava/lang/Byte;");
   }

   static public void boxFloat(Bytecode bc)
   {
      bc.addInvokestatic("java.lang.Float", "valueOf", "(F)Ljava/lang/Float;");
   }

   static public void boxDouble(Bytecode bc)
   {
      bc.addInvokestatic("java.lang.Double", "valueOf", "(D)Ljava/lang/Double;");
   }

   static public void boxChar(Bytecode bc)
   {
      bc.addInvokestatic("java.lang.Character", "valueOf", "(C)Ljava/lang/Character;");
   }

   static public void boxBoolean(Bytecode bc)
   {
      bc.addInvokestatic("java.lang.Boolean", "valueOf", "(Z)Ljava/lang/Boolean;");
   }

   // unboxing

   static public Bytecode unboxInt(Bytecode bc)
   {
      bc.addCheckcast("java.lang.Number");
      bc.addInvokevirtual("java.lang.Number", "intValue", "()I");
      return bc;
   }

   static public Bytecode unboxLong(Bytecode bc)
   {
      bc.addCheckcast("java.lang.Number");
      bc.addInvokevirtual("java.lang.Number", "longValue", "()J");
      return bc;
   }

   static public Bytecode unboxShort(Bytecode bc)
   {
      bc.addCheckcast("java.lang.Number");
      bc.addInvokevirtual("java.lang.Number", "shortValue", "()S");
      return bc;
   }

   static public Bytecode unboxByte(Bytecode bc)
   {
      bc.addCheckcast("java.lang.Number");
      bc.addInvokevirtual("java.lang.Number", "byteValue", "()B");
      return bc;
   }

   static public Bytecode unboxFloat(Bytecode bc)
   {
      bc.addCheckcast("java.lang.Number");
      bc.addInvokevirtual("java.lang.Number", "floatValue", "()F");
      return bc;
   }

   static public Bytecode unboxDouble(Bytecode bc)
   {
      bc.addCheckcast("java.lang.Number");
      bc.addInvokevirtual("java.lang.Number", "doubleValue", "()D");
      return bc;
   }

   static public Bytecode unboxChar(Bytecode bc)
   {
      bc.addCheckcast("java.lang.Character");
      bc.addInvokevirtual("java.lang.Character", "charValue", "()C");
      return bc;
   }

   static public Bytecode unboxBoolean(Bytecode bc)
   {
      bc.addCheckcast("java.lang.Boolean");
      bc.addInvokevirtual("java.lang.Boolean", "booleanValue", "()Z");
      return bc;
   }

}
