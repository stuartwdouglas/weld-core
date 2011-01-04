/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, Red Hat, Inc., and individual contributors
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
package org.jboss.weld.tests.extensions.decorators.spi;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Set;

import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.spi.Decorator;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.util.AnnotationLiteral;

public class DecoratorImpl implements Decorator<BallDecorator>
{

   public Class<?> getBeanClass()
   {
      return BallDecorator.class;
   }

   public Set<InjectionPoint> getInjectionPoints()
   {
      return Collections.emptySet();
   }

   public String getName()
   {
      return null;
   }

   public Set<Annotation> getQualifiers()
   {
      return Collections.emptySet();
   }

   public Class<? extends Annotation> getScope()
   {
      return Dependent.class;
   }

   public Set<Class<? extends Annotation>> getStereotypes()
   {
      return Collections.emptySet();
   }

   public Set<Type> getTypes()
   {
      return Collections.emptySet();
   }

   public boolean isAlternative()
   {
      return false;
   }

   public boolean isNullable()
   {
      return false;
   }

   public BallDecorator create(CreationalContext<BallDecorator> creationalContext)
   {
      return new BallDecorator();
   }

   public void destroy(BallDecorator instance, CreationalContext<BallDecorator> creationalContext)
   {

   }

   public Set<Type> getDecoratedTypes()
   {
      return Collections.<Type> singleton(Ball.class);
   }

   public Set<Annotation> getDelegateQualifiers()
   {
      return Collections.<Annotation> singleton(new AnnotationLiteral<Default>()
      {
      });
   }

   public Type getDelegateType()
   {
      return Ball.class;
   }

}
