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

import static org.jboss.weld.logging.LoggerFactory.loggerFactory;
import static org.jboss.weld.logging.messages.BeanMessage.FAILED_TO_SET_THREAD_LOCAL_ON_PROXY;
import static org.jboss.weld.logging.messages.BeanMessage.PROXY_INSTANTIATION_BEAN_ACCESS_FAILED;
import static org.jboss.weld.logging.messages.BeanMessage.PROXY_INSTANTIATION_FAILED;
import static org.jboss.weld.util.reflection.Reflections.cast;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javassist.NotFoundException;
import javassist.util.proxy.MethodHandler;

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
import org.jboss.interceptor.util.proxy.TargetInstanceProxy;
import org.jboss.weld.Container;
import org.jboss.weld.exceptions.DefinitionException;
import org.jboss.weld.exceptions.WeldException;
import org.jboss.weld.logging.Category;
import org.jboss.weld.serialization.spi.ContextualStore;
import org.jboss.weld.serialization.spi.ProxyServices;
import org.jboss.weld.util.Proxies.TypeInfo;
import org.jboss.weld.util.bytecode.MethodInformation;
import org.jboss.weld.util.bytecode.RuntimeMethodInformation;
import org.jboss.weld.util.bytecode.StaticMethodInformation;
import org.jboss.weld.util.collections.ArraySet;
import org.jboss.weld.util.reflection.Reflections;
import org.jboss.weld.util.reflection.SecureReflections;
import org.jboss.weld.util.reflection.instantiation.InstantiatorFactory;
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
public class ProxyFactory<T>
{
   // The log provider
   protected static final LocLogger log = loggerFactory().getLogger(Category.BEAN);
   // Default proxy class name suffix
   public static final String PROXY_SUFFIX = "Proxy";
   public static final String DEFAULT_PROXY_PACKAGE = "org.jboss.weld.proxies";

   private final Class<?> beanType;
   private final Set<Class<?>> additionalInterfaces = new HashSet<Class<?>>();
   private final ClassLoader classLoader;
   private final String baseProxyName;
   private final Bean<?> bean;

   protected static final String FIRST_SERIALIZATION_PHASE_COMPLETE_FIELD_NAME = "firstSerializationPhaseComplete";

   protected static final String METHOD_HANDLER_FIELD_NAME = "methodHandler";

   protected static final String CONSTRUCTED_FLAG_NAME = "constructed";


   protected static final BytecodeMethodResolver DEFAULT_METHOD_RESOLVER = new DefaultBytecodeMethodResolver();

   /**
    * created a new proxy factory from a bean instance. The proxy name is
    * generated from the bean id
    *
    * @param proxiedBeanType
    * @param businessInterfaces
    * @param bean
    */
   public ProxyFactory(Class<?> proxiedBeanType, Set<? extends Type> typeClosure, Bean<?> bean)
   {
      this(proxiedBeanType, typeClosure, getProxyName(proxiedBeanType, typeClosure, bean), bean);
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
   public ProxyFactory(Class<?> proxiedBeanType, Set<? extends Type> typeClosure, String proxyName, Bean<?> bean)
   {
      this.bean = bean;
      for (Type type : typeClosure)
      {
         Class<?> c = Reflections.getRawType(type);
         // Ignore no-interface views, they are dealt with proxiedBeanType
         // (pending redesign)
         if (c.isInterface())
         {
            addInterface(c);
         }
      }
      TypeInfo typeInfo = TypeInfo.of(typeClosure);
      Class<?> superClass = typeInfo.getSuperClass();
      superClass = superClass == null ? Object.class : superClass;
      if (superClass.equals(Object.class) && additionalInterfaces.isEmpty())
      {
            // No interface beans must use the bean impl as superclass
            superClass = proxiedBeanType;
      }
      this.beanType = superClass;
      addDefaultAdditionalInterfaces();
      baseProxyName = proxyName;
      this.classLoader = resolveClassLoaderForBeanProxy(bean, typeInfo);
   }

   static String getProxyName(Class<?> proxiedBeanType, Set<? extends Type> typeClosure, Bean<?> bean)
   {
      TypeInfo typeInfo = TypeInfo.of(typeClosure);
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
            proxyPackage=DEFAULT_PROXY_PACKAGE;
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

   /**
    * Adds an additional interface that the proxy should implement. The default
    * implementation will be to forward invocations to the bean instance.
    *
    * @param newInterface an interface
    */
   public void addInterface(Class<?> newInterface)
   {
      if (!newInterface.isInterface())
      {
         throw new IllegalArgumentException(newInterface + " is not an interface");
      }
      additionalInterfaces.add(newInterface);
   }

   /**
    * Method to create a new proxy that wraps the bean instance.
    *
    * @return a new proxy object
    */

   public T create(BeanInstance beanInstance)
   {
      T proxy = null;
      Class<T> proxyClass = getProxyClass();
      try
      {
         if (InstantiatorFactory.useInstantiators())
         {
            proxy = SecureReflections.newUnsafeInstance(proxyClass);
            //we need to inialize the ThreadLocal via reflection
            // TODO: there is probably a better way to to this
            try
            {
               Field sfield = proxyClass.getDeclaredField(FIRST_SERIALIZATION_PHASE_COMPLETE_FIELD_NAME);
               sfield.setAccessible(true);

               @SuppressWarnings("rawtypes")
               ThreadLocal threadLocal = new ThreadLocal();

               sfield.set(proxy, threadLocal);
            }
            catch(Exception e)
            {
               throw new DefinitionException(FAILED_TO_SET_THREAD_LOCAL_ON_PROXY, e, this);
            }
         }
         else
         {
            proxy = SecureReflections.newInstance(proxyClass);
         }
      }
      catch (InstantiationException e)
      {
         throw new DefinitionException(PROXY_INSTANTIATION_FAILED, e, this);
      }
      catch (IllegalAccessException e)
      {
         throw new DefinitionException(PROXY_INSTANTIATION_BEAN_ACCESS_FAILED, e, this);
      }
      setInvocationHandlerStatic(proxy, new ProxyMethodHandler(beanInstance, bean));
      return proxy;
   }

   /**
    * Produces or returns the existing proxy class.
    *
    * @return always the class of the proxy
    */
   public Class<T> getProxyClass()
   {
      String suffix = "_$$_Weld" + getProxyNameSuffix();
      String proxyClassName = getBaseProxyName();
      if (!proxyClassName.endsWith(suffix))
      {
         proxyClassName = proxyClassName + suffix;
      }
      if (proxyClassName.startsWith("java"))
      {
         proxyClassName = proxyClassName.replaceFirst("java", "org.jboss.weld");
      }
      Class<T> proxyClass = null;
      log.trace("Retrieving/generating proxy class " + proxyClassName);
      try
      {
         // First check to see if we already have this proxy class
         proxyClass = cast(classLoader.loadClass(proxyClassName));
      }
      catch (ClassNotFoundException e)
      {
         // Create the proxy class for this instance
         try
         {
            proxyClass = createProxyClass(proxyClassName);
         }
         catch (Exception e1)
         {
            throw new WeldException(e1);
         }
      }
      return proxyClass;
   }

   /**
    * Returns the package and base name for the proxy class.
    *
    * @return base name without suffixes
    */
   protected String getBaseProxyName()
   {
      return baseProxyName;
   }

   /**
    * Convenience method to set the underlying bean instance for a proxy.
    * 
    * @param proxy the proxy instance
    * @param beanInstance the instance of the bean
    */
   public static <T> void setBeanInstance(T proxy, BeanInstance beanInstance, Bean<?> bean)
   {
      setInvocationHandlerStatic(proxy, new ProxyMethodHandler(beanInstance, bean));
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

   private void addDefaultAdditionalInterfaces()
   {
      additionalInterfaces.add(Serializable.class);
   }

   /**
    * Sub classes may override to specify additional interfaces the proxy should
    * implement
    */
   protected void addAdditionalInterfaces(Set<Class<?>> interfaces)
   {

   }

   private Class<T> createProxyClass(String proxyClassName) throws Exception
   {
      ArraySet<Class<?>> specialInterfaces = new ArraySet<Class<?>>(3);
      specialInterfaces.add(LifecycleMixin.class);
      specialInterfaces.add(TargetInstanceProxy.class);
      addAdditionalInterfaces(specialInterfaces);
      // Remove special interfaces from main set (deserialization scenario)
      additionalInterfaces.removeAll(specialInterfaces);

      ClassFile proxyClassType = null;
      if (beanType.isInterface())
      {
         proxyClassType = new ClassFile(proxyClassName, Object.class.getName());
         proxyClassType.addInterface(beanType.getName());
      }
      else
      {
         proxyClassType = new ClassFile(proxyClassName, beanType.getName());
      }
      // Add interfaces which require method generation
      for (Class<?> clazz : additionalInterfaces)
      {
         proxyClassType.addInterface(clazz.getName());
      }

      addFields(proxyClassType);
      addConstructors(proxyClassType);
      addMethods(proxyClassType);

      // Additional interfaces whose methods require special handling
      for (Class<?> specialInterface : specialInterfaces)
      {
         proxyClassType.addInterface(specialInterface.getName());
      }
      // TODO: change the ProxyServices SPI to allow the container to figure out
      // which PD to use
      ProtectionDomain domain = beanType.getProtectionDomain();
      if (beanType.isInterface() || beanType.equals(Object.class))
      {
         domain = ProxyFactory.class.getProtectionDomain();
      }

      Class<T> proxyClass = cast(proxyClassType.define(classLoader, domain));
      log.trace("Created Proxy class of type " + proxyClass + " supporting interfaces " + Arrays.toString(proxyClass.getInterfaces()));
      return proxyClass;
   }

   /**
    * Adds a constructor for the proxy for each constructor declared by the base
    * bean type.
    *
    * @param proxyClassType the Javassist class for the proxy
    * @param initialValueBytecode
    */
   protected void addConstructors(ClassFile proxyClassType)
   {
      try
      {
         if (beanType.isInterface())
         {
            addDefaultConstructor(proxyClassType);
         }
         else
         {
            boolean constructorFound = false;
            for (Constructor<?> constructor : beanType.getDeclaredConstructors())
            {
               if ((constructor.getModifiers() & Modifier.PRIVATE) == 0)
               {
                  constructorFound = true;
                  String[] exceptions = new String[constructor.getExceptionTypes().length];
                  for (int i = 0; i < exceptions.length; ++i)
                  {
                     exceptions[i] = constructor.getExceptionTypes()[i].getName();
                  }
                  addConstructor(DescriptorUtils.parameterDescriptors(constructor.getParameterTypes()), exceptions, proxyClassType);
               }
            }
            if (!constructorFound)
            {
               // the bean only has private constructors, we need to generate
               // two fake constructors that call each other
               addConstructorsForBeanWithPrivateConstructors(proxyClassType);
            }
         }
      }
      catch (Exception e)
      {
         throw new WeldException(e);
      }
   }

   protected void addFields(ClassFile proxyClassType)
   {
      try
      {
         // The field representing the underlying instance or special method
         // handling
         proxyClassType.addField(AccessFlag.PRIVATE, METHOD_HANDLER_FIELD_NAME, "Ljavassist/util/proxy/MethodHandler;");
         // Special field used during serialization of a proxy
         proxyClassType.addField(AccessFlag.TRANSIENT | AccessFlag.PRIVATE, FIRST_SERIALIZATION_PHASE_COMPLETE_FIELD_NAME, "Ljava/lang/ThreadLocal;");
         // field used to indicate that super() has been called
         proxyClassType.addField(AccessFlag.PRIVATE,CONSTRUCTED_FLAG_NAME, "Z");
         // we need to initialize this to a new ThreadLocal
      }
      catch (Exception e)
      {
         throw new WeldException(e);
      }
   }

   private void createInitialValueBytecode(ClassFile proxyClassType, CodeAttribute ca)
   {
      ca.aload(0);
      ca.newInstruction("java/lang/ThreadLocal");
      ca.dup();
      ca.invokespecial("java.lang.ThreadLocal", "<init>", "()V");
      ca.putfield(proxyClassType.getName(), FIRST_SERIALIZATION_PHASE_COMPLETE_FIELD_NAME, "Ljava/lang/ThreadLocal;");

   }

   protected void addMethods(ClassFile proxyClassType)
   {
      // Add all class methods for interception
      addMethodsFromClass(proxyClassType);

      // Add special proxy methods
      addSpecialMethods(proxyClassType);

      // Add serialization support methods
      addSerializationSupport(proxyClassType);
   }

   /**
    * Adds special serialization code by providing a writeReplace() method on
    * the proxy. This method when first called will substitute the proxy object
    * with an instance of {@link org.jboss.weld.proxy.util.SerializableProxy}.
    * The next call will receive the proxy object itself permitting the
    * substitute object to serialize the proxy.
    *
    * @param proxyClassType the Javassist class for the proxy class
    */
   protected void addSerializationSupport(ClassFile proxyClassType)
   {
      try
      {
         // Create a two phase writeReplace where the first call uses a
         // replacement object and the subsequent call get the proxy object.
         Class<? extends Exception>[] exceptions = new Class[] { ObjectStreamException.class };
         MethodInformation writeReplaceInfo = new StaticMethodInformation("writeReplace", new Class[] {}, Object.class, proxyClassType.getName());
         ClassMethod method = addMethod(proxyClassType, AccessFlag.PUBLIC, writeReplaceInfo);
         method.addCheckedExceptions(exceptions);
         createWriteReplaceBody(method, writeReplaceInfo);

         // Also add a static method that can be used to deserialize a proxy
         // object.
         // This causes the OO input stream to use the class loader from this
         // class.
         exceptions = new Class[] { ClassNotFoundException.class, IOException.class };
         MethodInformation deserializeProxy = new StaticMethodInformation("deserializeProxy", new Class[] { ObjectInputStream.class }, Object.class, proxyClassType.getName());
         method = addMethod(proxyClassType, AccessFlag.PUBLIC | AccessFlag.STATIC, deserializeProxy);
         method.addCheckedExceptions(exceptions);
         createDeserializeProxyBody(proxyClassType, method.getCodeAttribute());
      }
      catch (Exception e)
      {
         throw new WeldException(e);
      }

   }

   /**
    * creates a bytecode fragment that returns $1.readObject()
    *
    */
   private void createDeserializeProxyBody(ClassFile file, CodeAttribute ca)
   {
      ca.aload(0);
      ca.invokevirtual("java.io.ObjectInputStream", "readObject", "()Ljava/lang/Object;");
      // initialize the transient threadlocal
      ca.dup();
      ca.checkcast(file.getName());
      ca.newInstruction("java/lang/ThreadLocal");
      ca.dup();
      ca.invokespecial("java.lang.ThreadLocal", "<init>", "()V");
      ca.putfield(file.getName(), FIRST_SERIALIZATION_PHASE_COMPLETE_FIELD_NAME, "Ljava/lang/ThreadLocal;");
      ca.returnInstruction();
   }

   /**
    * creates serialization code. In java this code looks like:
    *
    * <pre>
    *  Boolean value = firstSerializationPhaseComplete.get();
    *  if (firstSerializationPhaseComplete!=null) {
    *   firstSerializationPhaseComplete.remove();\n");
    *   return $0;
    *  } else {
    *    firstSerializationPhaseComplete.set(Boolean.TRUE);
    *    return methodHandler.invoke($0,$proxyClassTypeName.class.getMethod("writeReplace", null), null, $args);
    *  }
    * }
    * </pre>
    *
    * the use TRUE,null rather than TRUE,FALSE to avoid the need to subclass
    * ThreadLocal, which would be problematic
    */
   private void createWriteReplaceBody(ClassMethod method, MethodInformation methodInfo)
   {
      CodeAttribute ca = method.getCodeAttribute();
      ca.aload(0);
      ca.getfield(methodInfo.getDeclaringClass(), FIRST_SERIALIZATION_PHASE_COMPLETE_FIELD_NAME, "Ljava/lang/ThreadLocal;");
      ca.invokevirtual("java.lang.ThreadLocal", "get", "()Ljava/lang/Object;");
      BranchEnd runSecondPhase = ca.ifnull();
      // this bytecode is run if firstSerializationPhaseComplete=true
      // set firstSerializationPhaseComplete=false
      ca.aload(0);
      ca.getfield(methodInfo.getDeclaringClass(), FIRST_SERIALIZATION_PHASE_COMPLETE_FIELD_NAME, "Ljava/lang/ThreadLocal;");
      ca.invokevirtual("java.lang.ThreadLocal", "remove", "()V");
      // return this
      ca.aload(0);
      ca.returnInstruction();
      ca.branchEnd(runSecondPhase);

      // now create the rest of the bytecode
      // set firstSerializationPhaseComplete=true
      ca.aload(0);
      ca.getfield(methodInfo.getDeclaringClass(), FIRST_SERIALIZATION_PHASE_COMPLETE_FIELD_NAME, "Ljava/lang/ThreadLocal;");
      ca.getstatic("java.lang.Boolean", "TRUE", "Ljava/lang/Boolean;");
      ca.invokevirtual("java.lang.ThreadLocal", "set", "(Ljava/lang/Object;)V");

      ca.aload(0);
      ca.getfield(methodInfo.getDeclaringClass(), METHOD_HANDLER_FIELD_NAME, DescriptorUtils.makeDescriptor(MethodHandler.class));
      ca.aload(0);
      DEFAULT_METHOD_RESOLVER.getDeclaredMethod(method.getClassFile(), ca, methodInfo);
      ca.aconstNull();

      ca.iconst(0);
      ca.anewarray("java.lang.Object");
      // now we have all our arguments on the stack
      // lets invoke the method
      ca.invokeinterface(MethodHandler.class.getName(), "invoke", "(Ljava/lang/Object;Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;");
      ca.returnInstruction();
   }

   protected void addMethodsFromClass(ClassFile proxyClassType)
   {
      try
      {
         // Add all methods from the class heirachy
         Class<?> cls = beanType;
         // first add equals/hashCode methods if required
         generateEqualsMethod(proxyClassType);
         generateHashCodeMethod(proxyClassType);

         while (cls != null)
         {
            for (Method method : cls.getDeclaredMethods())
            {
               if (!Modifier.isStatic(method.getModifiers()) && !Modifier.isFinal(method.getModifiers()) && (method.getDeclaringClass() != Object.class || method.getName().equals("toString")))
               {
                  try
                  {
                     MethodInformation methodInfo = new RuntimeMethodInformation(method);
                     ClassMethod classMethod = addMethod(proxyClassType, AccessFlag.PUBLIC, methodInfo);
                     addConstructedGuardToMethodBody(proxyClassType, classMethod.getCodeAttribute(), methodInfo);
                     createForwardingMethodBody(proxyClassType, classMethod.getCodeAttribute(), methodInfo);
                     log.trace("Adding method " + method);
                  }
                  catch (DuplicateMemberException e)
                  {
                     // do nothing. This will happen if superclass methods
                     // have been overridden

                     // FIXME: change this so that we test if the method has
                     // been added first
                  }
               }
            }
            cls = cls.getSuperclass();
         }
         for (Class<?> c : additionalInterfaces)
         {
            for (Method method : c.getMethods())
            {
               try
               {
                  MethodInformation methodInfo = new RuntimeMethodInformation(method);
                  ClassMethod classMethod = addMethod(proxyClassType, AccessFlag.PUBLIC, methodInfo);
                  createSpecialMethodBody(proxyClassType, methodInfo, classMethod.getCodeAttribute());
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

   /**
    * Generate the body of the proxies hashCode method.
    * <p>
    * If this method returns null, the method will not be added, and the
    * hashCode on the superclass will be used as per normal virtual method
    * resolution rules
    *
    */
   protected void generateHashCodeMethod(ClassFile proxyClassType)
   {

   }

   /**
    * Generate the body of the proxies equals method.
    * <p>
    * If this method returns null, the method will not be added, and the
    * hashCode on the superclass will be used as per normal virtual method
    * resolution rules
    *
    */
   protected void generateEqualsMethod(ClassFile proxyClassType)
   {

   }

   protected void createSpecialMethodBody(ClassFile proxyClassType, MethodInformation method, CodeAttribute ca) throws NotFoundException
   {
      createInterceptorBody(proxyClassType, ca, method);
   }

   /**
    * Adds the following code to a delegating method:
    * <p>
    * <code>
    * if(!this.constructed) return super.thisMethod()
    * </code>
    * <p>
    * This means that the proxy will not start to delegate to the underlying
    * bean instance until after the constructor has finished.
    *
    */
   protected void addConstructedGuardToMethodBody(ClassFile proxyClassType, CodeAttribute ca, MethodInformation method)
   {
      String methodDescriptor = method.getDescriptor();

      // now create the conditional
      ca.aload(0);
      ca.getfield(proxyClassType.getName(), CONSTRUCTED_FLAG_NAME, "Z");

      // jump if the proxy constructor has finished
      BranchEnd invokeSpecial = ca.ifne();
      // generate the invokespecial call to the super class method
      // this is run when the proxy is being constructed
      ca.aload(0);
      ca.loadMethodParameters();
      ca.invokespecial(proxyClassType.getSuperclass(), method.getName(), methodDescriptor);
      ca.returnInstruction();
      ca.branchEnd(invokeSpecial);
   }

   protected void createForwardingMethodBody(ClassFile proxyClassType, CodeAttribute ca, MethodInformation method)
   {
      createInterceptorBody(proxyClassType, ca, method);
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
   protected void createInterceptorBody(ClassFile file, CodeAttribute ca, MethodInformation method)
   {
      invokeMethodHandler(file, ca, method, true, DEFAULT_METHOD_RESOLVER);
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
   protected static void invokeMethodHandler(ClassFile file, CodeAttribute ca, MethodInformation method, boolean addReturnInstruction, BytecodeMethodResolver bytecodeMethodResolver)
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
      ca.aload(0);
      ca.getfield(file.getName(), METHOD_HANDLER_FIELD_NAME, DescriptorUtils.makeDescriptor(MethodHandler.class));
      ca.aload(0);
      bytecodeMethodResolver.getDeclaredMethod(file, ca, method);
      ca.aconstNull();

      ca.iconst(method.getParameterTypes().length);
      ca.anewarray("java.lang.Object");

      int localVariableCount = 1;

      for (int i = 0; i < method.getParameterTypes().length; ++i)
      {
         String typeString = method.getParameterTypes()[i];
         ca.dup(); // duplicate the array reference
         ca.iconst(i);
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
      ca.invokeinterface(MethodHandler.class.getName(), "invoke", "(Ljava/lang/Object;Ljava/lang/reflect/Method;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;");
      if (addReturnInstruction)
      {
         // now we need to return the appropriate type
         if (method.getReturnType().equals("V"))
         {
            ca.returnInstruction();
         }
         else if (DescriptorUtils.isPrimitive(method.getReturnType()))
         {
            Boxing.unbox(ca, method.getReturnType());
            ca.returnInstruction();
         }
         else
         {
            String castType = method.getReturnType();
            if (!method.getReturnType().startsWith("["))
            {
               castType = method.getReturnType().substring(1).substring(0, method.getReturnType().length() - 2);
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
            createInterceptorBody(proxyClassType, classMethod.getCodeAttribute(), methodInfo);
         }
         Method getInstanceMethod = TargetInstanceProxy.class.getDeclaredMethod("getTargetInstance");
         Method getInstanceClassMethod = TargetInstanceProxy.class.getDeclaredMethod("getTargetClass");

         MethodInformation getInstanceMethodInfo = new RuntimeMethodInformation(getInstanceMethod);
         createInterceptorBody(proxyClassType, addMethod(proxyClassType, AccessFlag.PUBLIC, getInstanceMethodInfo).getCodeAttribute(), getInstanceMethodInfo);

         MethodInformation getInstanceClassMethodInfo = new RuntimeMethodInformation(getInstanceClassMethod);
         createInterceptorBody(proxyClassType, addMethod(proxyClassType, AccessFlag.PUBLIC, getInstanceClassMethodInfo).getCodeAttribute(), getInstanceClassMethodInfo);
      }
      catch (Exception e)
      {
         throw new WeldException(e);
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
         ClassMethod ctor = proxyClassType.addMethod(AccessFlag.PUBLIC, "<init>", "V", "Ljava/lang/Byte");
         CodeAttribute ca = ctor.getCodeAttribute();
         ca.aload(0);
         ca.aconstNull();
         ca.aconstNull();
         ca.invokespecial(proxyClassType.getName(), "<init>", "(Ljava/lang/Byte;Ljava/lang/Byte;)V");
         ca.returnInstruction();

         ctor = proxyClassType.addMethod(AccessFlag.PUBLIC, "<init>", "V", "Ljava/lang/Byte", "Ljava/lang/Byte");
         ca = ctor.getCodeAttribute();
         ca.aload(0);
         ca.aconstNull();
         ca.invokespecial(proxyClassType.getName(), "<init>", "(Ljava/lang/Byte;)V");
         ca.returnInstruction();
      }
      catch (DuplicateMemberException e)
      {
         throw new RuntimeException(e);
      }
   }


   protected ClassMethod addMethod(ClassFile proxyClassType, int accessFlags, MethodInformation methodInformation)
   {
      return proxyClassType.addMethod(accessFlags, methodInformation.getName(), methodInformation.getReturnType(), methodInformation.getParameterTypes());
   }

   /**
    * adds a constructor that calls super()
    */
   protected void addDefaultConstructor(ClassFile file)
   {
      addConstructor(new String[0], new String[0], file);
   }

   /**
    * Adds a constructor that delegates to a super constructor with the same
    * descriptor. The bytecode in inialValueBytecode will be executed at the
    * start of the constructor and can be used to inialize fields to a default
    * value. As the object is not properly constructed at this point this
    * bytecode may not reference this (i.e. the variable at location 0)
    *
    * @param descriptor the constructor descriptor
    * @param exceptions any exceptions that are thrown
    * @param file the classfile to add the constructor to
    * @param initialValueBytecode bytecode that can be used to set inial values
    */
   protected void addConstructor(String[] parameters, String[] exceptions, ClassFile proxyClassType)
   {
         ClassMethod ctor = proxyClassType.addMethod(AccessFlag.PUBLIC, "<init>", "V", parameters);
         ctor.addCheckedExceptions(exceptions);
         CodeAttribute ca = ctor.getCodeAttribute();
         this.createInitialValueBytecode(proxyClassType, ca);
         // we need to generate a constructor with a single invokespecial call
         // to the super constructor
         // to do this we need to push all the arguments on the stack first
         // local variables is the number of parameters +1 for this
         // if some of the parameters are wide this may go up.
         ca.aload(0);
         ca.loadMethodParameters();
         // now we have the parameters on the stack
         ca.invokespecial(proxyClassType.getSuperclass(), "<init>", "V", parameters);
         // now set constructed to true
         ca.aload(0);
         ca.iconst(1);
         ca.putfield(proxyClassType.getName(), ProxyFactory.CONSTRUCTED_FLAG_NAME, "Z");
         ca.returnInstruction();
   }

   public Class<?> getBeanType()
   {
      return beanType;
   }

   public Set<Class<?>> getAdditionalInterfaces()
   {
      return additionalInterfaces;
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

   /**
    * Sets the invocation handler for a proxy. This method is less efficient
    * than {@link #setInvocationHandler(Object, InvocationHandler)}, however it
    * will work on any proxy, not just proxies from a specific factory.
    *
    * @param proxy the proxy to modify
    * @param handler the handler to use
    */
   public static void setInvocationHandlerStatic(Object proxy, MethodHandler handler)
   {
      try
      {
         final Field field = proxy.getClass().getDeclaredField(METHOD_HANDLER_FIELD_NAME);
         AccessController.doPrivileged(new SetAccessiblePrivilege(field));
         field.set(proxy, handler);
      }
      catch (NoSuchFieldException e)
      {
         throw new RuntimeException("Could not find invocation handler on generated proxy", e);
      }
      catch (IllegalArgumentException e)
      {
         throw new RuntimeException(e);
      }
      catch (IllegalAccessException e)
      {
         throw new RuntimeException(e);
      }
   }

   /**
    * Gets the {@link InvocationHandler} for a given proxy instance. This method
    * is less efficient than {@link #getInvocationHandler(Object)}, however it
    * will work for any proxy, not just proxies from a specific factory
    * instance.
    *
    * @param proxy the proxy
    * @return the invocation handler
    */
   public static MethodHandler getInvocationHandler(Object proxy)
   {
      try
      {
         final Field field = proxy.getClass().getDeclaredField(METHOD_HANDLER_FIELD_NAME);
         AccessController.doPrivileged(new SetAccessiblePrivilege(field));
         return (MethodHandler) field.get(proxy);
      }
      catch (NoSuchFieldException e)
      {
         throw new RuntimeException("Could not find invocation handler on generated proxy", e);
      }
      catch (IllegalArgumentException e)
      {
         throw new RuntimeException("Object is not a proxy of correct type", e);
      }
      catch (IllegalAccessException e)
      {
         throw new RuntimeException(e);
      }
   }

   private static class SetAccessiblePrivilege implements PrivilegedAction<Void>
   {
      private final AccessibleObject object;

      public SetAccessiblePrivilege(final AccessibleObject object)
      {
         this.object = object;
      }

      public Void run()
      {
         object.setAccessible(true);
         return null;
      }
   }
}
