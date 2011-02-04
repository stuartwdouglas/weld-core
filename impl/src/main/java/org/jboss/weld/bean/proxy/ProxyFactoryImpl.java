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

import static org.jboss.weld.logging.Category.BEAN;
import static org.jboss.weld.logging.LoggerFactory.loggerFactory;
import static org.jboss.weld.logging.messages.BeanMessage.PROXY_INSTANTIATION_BEAN_ACCESS_FAILED;
import static org.jboss.weld.logging.messages.BeanMessage.PROXY_INSTANTIATION_FAILED;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

import javassist.bytecode.AccessFlag;
import javassist.bytecode.Bytecode;
import javassist.bytecode.ClassFile;
import javassist.bytecode.DuplicateMemberException;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.Opcode;
import javassist.util.proxy.MethodHandler;

import javax.enterprise.inject.spi.Bean;

import org.jboss.classfilewriter.ClassMethod;
import org.jboss.classfilewriter.code.CodeAttribute;
import org.jboss.interceptor.proxy.LifecycleMixin;
import org.jboss.interceptor.util.proxy.TargetInstanceProxy;
import org.jboss.invocation.proxy.MethodBodyCreator;
import org.jboss.invocation.proxy.MethodIdentifier;
import org.jboss.invocation.proxy.ProxyFactory;
import org.jboss.weld.Container;
import org.jboss.weld.bean.proxy.util.WeldSerializableProxy;
import org.jboss.weld.exceptions.DefinitionException;
import org.jboss.weld.exceptions.WeldException;
import org.jboss.weld.serialization.spi.ContextualStore;
import org.jboss.weld.serialization.spi.ProxyServices;
import org.jboss.weld.util.Proxies.TypeInfo;
import org.jboss.weld.util.bytecode.Boxing;
import org.jboss.weld.util.bytecode.BytecodeUtils;
import org.jboss.weld.util.bytecode.DescriptorUtils;
import org.jboss.weld.util.bytecode.MethodInformation;
import org.jboss.weld.util.reflection.Reflections;
import org.slf4j.cal10n.LocLogger;

/**
 * Main factory to produce proxy classes and instances for Weld beans. This
 * implementation creates proxies which forward non-static method invocations to
 * a {@link BeanInstance}. All proxies implement the {@link Proxy} interface.
 *
 * @author David Allen
 * @author Stuart Douglas
 * @author Marius Bogoevici
 */
public class ProxyFactoryImpl<T> extends ProxyFactory<T>
{

   /**
    * Generates the writeReplace method
    * 
    * @author Stuart Douglas
    * 
    */
   protected class WriteReplaceBodyCreator implements MethodBodyCreator
   {

      /**
       * Generate the writeReplace method body.
       * 
       * @param method the method to populate
       * @param superclassMethod the method to override
       */
      public void overrideMethod(ClassMethod method, Method superclassMethod)
      {
         // superClassMethod will be null
         CodeAttribute ca = method.getCodeAttribute();
         ca.newInstruction(WeldSerializableProxy.class.getName());
         ca.dup();
         ca.aload(0);
         ca.getstatic(getClassName(), BEAN_FIELD, Bean.class);
         ca.invokespecial(WeldSerializableProxy.class.getName(), "<init>", "(Ljava/lang/Object;Ljavax/enterprise/inject/spi/Bean;)V");
         ca.returnInstruction();
      }
   }

   // The log provider
   protected static final LocLogger log = loggerFactory().getLogger(BEAN);

   // Default proxy class name suffix
   public static final String PROXY_SUFFIX = "Proxy";
   public static final String DEFAULT_PROXY_PACKAGE = "org.jboss.weld.proxies";
   public static final String BEAN_FIELD = "bean$$holder";

   private static final Class<?>[] EMPTY_CLASS_ARRAY = new Class[0];

   private final Bean<?> bean;

   /**
    * created a new proxy factory from a bean instance. The proxy name is
    * generated from the bean id
    *
    * @param proxiedBeanType
    * @param businessInterfaces
    * @param bean
    */
   public ProxyFactoryImpl(Class<?> proxiedBeanType, Bean<?> bean)
   {
      this(proxiedBeanType, getProxyName(proxiedBeanType, bean), bean);
   }

   /**
    * Creates a new proxy factory when the name of the proxy class is already
    * known, such as during de-serialization
    *
    * @param proxiedBeanType the super-class for this proxy class
    * @param typeClosure the bean types of the bean
    * @param the name of the proxy class
    *
    */
   @SuppressWarnings("unchecked")
   public ProxyFactoryImpl(Class<?> proxiedBeanType, String proxyName, Bean<?> bean)
   {
      this(proxyConfig(proxiedBeanType, proxyName, bean, PROXY_SUFFIX), bean);
   }

   public ProxyFactoryImpl(ProxyConfig<T> c, Bean<?> bean)
   {
      super(c.name, c.superClass, c.classLoader, c.protectionDomain, c.interfaces);
      this.bean = bean;
   }

   protected void generateClass()
   {
      addInterface(super.getDefaultMethodOverride(), LifecycleMixin.class);
      addInterface(super.getDefaultMethodOverride(), TargetInstanceProxy.class);
      overrideMethod(classFile.addMethod(AccessFlag.PRIVATE, "writeReplace", "Ljava/lang/Object;"), MethodIdentifier.getIdentifier("java.lang.Object", "writeReplace"), new WriteReplaceBodyCreator());
      classFile.addField(AccessFlag.PROTECTED | AccessFlag.STATIC, BEAN_FIELD, Bean.class);
      super.generateClass();
   };

   /**
    * Sets bean field
    */
   @Override
   public void afterClassLoad(Class<?> clazz)
   {
      super.afterClassLoad(clazz);
      try
      {
         final Field field = clazz.getDeclaredField(BEAN_FIELD);
         AccessController.doPrivileged(new PrivilegedAction<Void>()
         {

            public Void run()
            {
               field.setAccessible(true);
               return null;
            }
         });
         field.set(null, bean);
      }
      catch (Exception e)
      {
         throw new WeldException(e);
      }
   }

   /**
    * Method to create a new proxy that wraps the bean instance.
    * 
    * @return a new proxy object
    */
   public T create(BeanInstance beanInstance)
   {
      try
      {
         return newInstance(new InvocationHandlerAdaptor(new ProxyMethodHandler(beanInstance, bean)));
      }
      catch (InstantiationException e)
      {
         throw new DefinitionException(PROXY_INSTANTIATION_FAILED, e, this);
      }
      catch (IllegalAccessException e)
      {
         throw new DefinitionException(PROXY_INSTANTIATION_BEAN_ACCESS_FAILED, e, this);
      }
   }

   /**
    * Convenience method to set the underlying bean instance for a proxy.
    *
    * @param proxy the proxy instance
    * @param beanInstance the instance of the bean
    */
   public static <T> void setBeanInstance(T proxy, BeanInstance beanInstance, Bean<?> bean)
   {
      ProxyFactory.setInvocationHandlerStatic(proxy, new InvocationHandlerAdaptor(new ProxyMethodHandler(beanInstance, bean)));
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
    *           the method invocation
    */
   protected static void invokeMethodHandler(ClassFile file, Bytecode b, MethodInformation method, boolean addReturnInstruction, BytecodeMethodResolver bytecodeMethodResolver)
   {
      // now we need to build the bytecode. The order we do this in is as
      // follows:
      // load methodHandler
      // load this
      // load the method object
      // load null
      // create a new array the same size as the number of parameters
      // push our parameter values into the array
      // invokeinterface the invoke method
      // add checkcast to cast the result to the return type, or unbox if
      // primitive
      // add an appropriate return instruction
      b.add(Opcode.ALOAD_0);
      b.addGetfield(file.getName(), "methodHandler", DescriptorUtils.classToStringRepresentation(MethodHandler.class));
      b.add(Opcode.ALOAD_0);
      bytecodeMethodResolver.getDeclaredMethod(file, b, method.getDeclaringClass(), method.getName(), method.getParameterTypes());
      b.add(Opcode.ACONST_NULL);

      b.addIconst(method.getParameterTypes().length);
      b.addAnewarray("java.lang.Object");

      int localVariableCount = 1;

      for (int i = 0; i < method.getParameterTypes().length; ++i)
      {
         String typeString = method.getParameterTypes()[i];
         b.add(Opcode.DUP); // duplicate the array reference
         b.addIconst(i);
         // load the parameter value
         BytecodeUtils.addLoadInstruction(b, typeString, localVariableCount);
         // box the parameter if nessesary
         Boxing.boxIfNessesary(b, typeString);
         // and store it in the array
         b.add(Opcode.AASTORE);
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
      b.addInvokeinterface(MethodHandler.class.getName(), "invoke", "(Ljava/lang/Object;Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", 5);
      if (addReturnInstruction)
      {
         // now we need to return the appropriate type
         if (method.getReturnType().equals("V"))
         {
            b.add(Opcode.RETURN);
         }
         else if (DescriptorUtils.isPrimitive(method.getReturnType()))
         {
            Boxing.unbox(b, method.getReturnType());
            if (method.getReturnType().equals("D"))
            {
               b.add(Opcode.DRETURN);
            }
            else if (method.getReturnType().equals("F"))
            {
               b.add(Opcode.FRETURN);
            }
            else if (method.getReturnType().equals("J"))
            {
               b.add(Opcode.LRETURN);
            }
            else
            {
               b.add(Opcode.IRETURN);
            }
         }
         else
         {
            String castType = method.getReturnType();
            if (!method.getReturnType().startsWith("["))
            {
               castType = method.getReturnType().substring(1).substring(0, method.getReturnType().length() - 2);
            }
            b.addCheckcast(castType);
            b.add(Opcode.ARETURN);
         }
         if (b.getMaxLocals() < localVariableCount)
         {
            b.setMaxLocals(localVariableCount);
         }
      }
   }

   /**
    * Adds two constructors to the class that call each other in order to bypass
    * the JVM class file verifier.
    *
    * This would result in a stack overflow if they were actually called,
    * however the proxy is directly created without calling the constructor
    *
    */
   private void addConstructorsForBeanWithPrivateConstructors(ClassFile proxyClassType)
   {
      try
      {
         MethodInfo ctor = new MethodInfo(proxyClassType.getConstPool(), "<init>", "(Ljava/lang/Byte;)V");
         Bytecode b = new Bytecode(proxyClassType.getConstPool(), 3, 3);
         b.add(Opcode.ALOAD_0);
         b.add(Opcode.ACONST_NULL);
         b.add(Opcode.ACONST_NULL);
         b.addInvokespecial(proxyClassType.getName(), "<init>", "(Ljava/lang/Byte;Ljava/lang/Byte;)V");
         b.add(Opcode.RETURN);
         ctor.setCodeAttribute(b.toCodeAttribute());
         ctor.setAccessFlags(AccessFlag.PUBLIC);
         proxyClassType.addMethod(ctor);

         ctor = new MethodInfo(proxyClassType.getConstPool(), "<init>", "(Ljava/lang/Byte;Ljava/lang/Byte;)V");
         b = new Bytecode(proxyClassType.getConstPool(), 3, 3);
         b.add(Opcode.ALOAD_0);
         b.add(Opcode.ACONST_NULL);
         b.addInvokespecial(proxyClassType.getName(), "<init>", "(Ljava/lang/Byte;)V");
         b.add(Opcode.RETURN);
         ctor.setCodeAttribute(b.toCodeAttribute());
         ctor.setAccessFlags(AccessFlag.PUBLIC);
         proxyClassType.addMethod(ctor);
      }
      catch (DuplicateMemberException e)
      {
         throw new RuntimeException(e);
      }
   }

   public Class<T> getProxyClass()
   {
      return (Class<T>) defineClass();
   }

   /**
    * Gets a name for the proxy. The name is base of the bean id, so every bean
    * gets a unique proxy class.
    */
   protected static String getProxyName(Class<?> proxiedBeanType, Bean<?> bean)
   {
      TypeInfo typeInfo = TypeInfo.of(bean.getTypes());
      String proxyPackage;
      if (proxiedBeanType.equals(Object.class))
      {
         Class<?> superInterface = typeInfo.getSuperInterface();
         if (superInterface == null)
         {
            throw new IllegalArgumentException("Proxied bean type cannot be java.lang.Object without an interface");
         }
         else
         {
            proxyPackage = DEFAULT_PROXY_PACKAGE;
         }
      }
      else
      {
         if (proxiedBeanType.getPackage() == null)
         {
            proxyPackage = DEFAULT_PROXY_PACKAGE;
         }
         else
         {
            proxyPackage = proxiedBeanType.getPackage().getName();
         }
      }
      String beanId = Container.instance().services().get(ContextualStore.class).putIfAbsent(bean);
      String className = beanId.replace('.', '$').replace(' ', '_').replace('/', '$').replace(';', '$');
      return proxyPackage + '.' + className;
   }

   @SuppressWarnings("unchecked")
   protected static <T> ProxyConfig proxyConfig(Class<?> proxiedBeanType, String proxyName, Bean<?> bean, String proxyNameSuffix)
   {
      Set<Class<?>> interfaces = new HashSet<Class<?>>();
      interfaces.add(Serializable.class);
      for (Type type : bean.getTypes())
      {
         Class<?> c = Reflections.getRawType(type);
         // Ignore no-interface views, they are dealt with proxiedBeanType
         // (pending redesign)
         if (c.isInterface())
         {
            interfaces.add(c);
         }
      }
      TypeInfo typeInfo = TypeInfo.of(bean.getTypes());
      Class<T> beanType = (Class<T>) typeInfo.getSuperClass();
      Class<T> superClass = (Class<T>) (beanType == null ? Object.class : beanType);
      // TODO: change the ProxyServices SPI to allow the container to figure out
      // which PD to use
      ProtectionDomain domain = beanType.getProtectionDomain();
      if (beanType.isInterface() || beanType.equals(Object.class))
      {
         domain = ProxyFactoryImpl.class.getProtectionDomain();
      }
      ClassLoader classLoader = resolveClassLoaderForBeanProxy(bean, typeInfo);

      // TODO: change name generation
      String suffix = "_$$_Weld" + proxyNameSuffix;
      String proxyClassName = proxyName;
      if (!proxyClassName.endsWith(suffix))
      {
         proxyClassName = proxyClassName + suffix;
      }
      if (proxyClassName.startsWith("java"))
      {
         proxyClassName = proxyClassName.replaceFirst("java", "org.jboss.weld");
      }

      return new ProxyConfig(proxyClassName, superClass, classLoader, domain, interfaces.toArray(EMPTY_CLASS_ARRAY));
   }

   /**
    * Figures out the correct class loader to use for a proxy for a given bean
    * 
    */
   public static ClassLoader resolveClassLoaderForBeanProxy(Bean<?> bean, TypeInfo typeInfo)
   {
      Class<?> superClass = typeInfo.getSuperClass();
      if (superClass.getName().startsWith("java"))
      {
         ClassLoader cl = Container.instance().services().get(ProxyServices.class).getClassLoader(bean.getBeanClass());
         if (cl == null)
         {
            cl = Thread.currentThread().getContextClassLoader();
         }
         return cl;
      }
      return Container.instance().services().get(ProxyServices.class).getClassLoader(superClass);
   }

   public static ClassLoader resolveClassLoaderForBeanProxy(Bean<?> bean)
   {
      return resolveClassLoaderForBeanProxy(bean, TypeInfo.of(bean.getTypes()));
   }

   private static class ProxyConfig<T>
   {

      public ProxyConfig(String name, Class<T> superClass, ClassLoader classLoader, ProtectionDomain protectionDomain, Class<?>[] interfaces)
      {
         this.name = name;
         this.superClass = superClass;
         this.interfaces = interfaces;
         this.classLoader = classLoader;
         this.protectionDomain = protectionDomain;
      }

      private final String name;
      private final Class<T> superClass;
      private final Class<?>[] interfaces;
      private final ClassLoader classLoader;
      private final ProtectionDomain protectionDomain;
   }


}
