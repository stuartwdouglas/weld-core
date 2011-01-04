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
package org.jboss.weld.tests.extensions.interceptors.spi;

import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.inject.Inject;

import org.jboss.arquillian.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.BeanArchive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.weld.bean.ManagedBean;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 
 * @author Stuart Douglas
 * 
 */
@RunWith(Arquillian.class)
public class SpiInterceptorTest
{

   @Deployment
   public static Archive<?> deploy()
   {
      return ShrinkWrap.create(BeanArchive.class).intercept(BallInterceptor.class).addPackage(SpiInterceptorTest.class.getPackage()).addServiceProvider(Extension.class, SpiInterceptorExtension.class);
   }

   @Inject
   private FootBall ball;

   @Inject
   private BeanManager bm;

   @Test
   public void testInterceptorRun()
   {
      ManagedBean<FootBall> bean = (ManagedBean<FootBall>) bm.resolve(bm.getBeans("footBall"));
      Assert.assertTrue(bean.hasInterceptors());
      ball.kick();
      Assert.assertTrue(BallInterceptor.isInterceptorRun());
   }

}
