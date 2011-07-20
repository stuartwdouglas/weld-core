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
package org.jboss.weld.bootstrap;

import static com.google.common.collect.Collections2.filter;
import static com.google.common.collect.Collections2.transform;
import static java.util.Collections.emptyList;
import static org.jboss.weld.logging.Category.BOOTSTRAP;
import static org.jboss.weld.logging.LoggerFactory.loggerFactory;
import static org.jboss.weld.logging.messages.BootstrapMessage.ENABLED_ALTERNATIVES;
import static org.jboss.weld.logging.messages.BootstrapMessage.ENABLED_DECORATORS;
import static org.jboss.weld.logging.messages.BootstrapMessage.ENABLED_INTERCEPTORS;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.enterprise.context.spi.Context;
import javax.enterprise.inject.spi.Bean;

import org.jboss.weld.bean.RIBean;
import org.jboss.weld.bean.builtin.BeanManagerBean;
import org.jboss.weld.bean.builtin.ContextBean;
import org.jboss.weld.bean.builtin.ConversationBean;
import org.jboss.weld.bean.builtin.EventBean;
import org.jboss.weld.bean.builtin.InjectionPointBean;
import org.jboss.weld.bean.builtin.InstanceBean;
import org.jboss.weld.bean.builtin.ee.DefaultValidatorBean;
import org.jboss.weld.bean.builtin.ee.DefaultValidatorFactoryBean;
import org.jboss.weld.bean.builtin.ee.PrincipalBean;
import org.jboss.weld.bean.builtin.ee.UserTransactionBean;
import org.jboss.weld.bootstrap.api.Environment;
import org.jboss.weld.bootstrap.api.ServiceRegistry;
import org.jboss.weld.bootstrap.api.helpers.SimpleServiceRegistry;
import org.jboss.weld.bootstrap.spi.BeanDeploymentArchive;
import org.jboss.weld.bootstrap.spi.Filter;
import org.jboss.weld.bootstrap.spi.Metadata;
import org.jboss.weld.ejb.EJBApiAbstraction;
import org.jboss.weld.ejb.EjbDescriptors;
import org.jboss.weld.ejb.spi.EjbServices;
import org.jboss.weld.jsf.JsfApiAbstraction;
import org.jboss.weld.manager.BeanManagerImpl;
import org.jboss.weld.manager.Enabled;
import org.jboss.weld.manager.InjectionTargetValidator;
import org.jboss.weld.manager.api.ExecutorServices;
import org.jboss.weld.metadata.FilterPredicate;
import org.jboss.weld.metadata.ScanningPredicate;
import org.jboss.weld.persistence.PersistenceApiAbstraction;
import org.jboss.weld.resources.DefaultResourceLoader;
import org.jboss.weld.resources.spi.ResourceLoader;
import org.jboss.weld.security.spi.SecurityServices;
import org.jboss.weld.transaction.spi.TransactionServices;
import org.jboss.weld.validation.spi.ValidationServices;
import org.jboss.weld.ws.WSApiAbstraction;
import org.slf4j.cal10n.LocLogger;

import com.google.common.base.Function;
import com.google.common.base.Predicate;

/**
 * @author pmuir
 *
 */
public class BeanDeployment
{

   private static final LocLogger log = loggerFactory().getLogger(BOOTSTRAP);

   private final BeanDeploymentArchive beanDeploymentArchive;
   private final BeanManagerImpl beanManager;
   private final BeanDeployer beanDeployer;
   private final Collection<ContextHolder<? extends Context>> contexts;

   public BeanDeployment(BeanDeploymentArchive beanDeploymentArchive, BeanManagerImpl deploymentManager, ServiceRegistry deploymentServices, Collection<ContextHolder<? extends Context>> contexts)
   {
      this.beanDeploymentArchive = beanDeploymentArchive;
      EjbDescriptors ejbDescriptors = new EjbDescriptors();
      beanDeploymentArchive.getServices().add(EjbDescriptors.class, ejbDescriptors);
      if (beanDeploymentArchive.getServices().get(ResourceLoader.class) == null)
      {
         beanDeploymentArchive.getServices().add(ResourceLoader.class, DefaultResourceLoader.INSTANCE);
      }
      ServiceRegistry services = new SimpleServiceRegistry();
      services.addAll(deploymentServices.entrySet());
      services.addAll(beanDeploymentArchive.getServices().entrySet());

      services.add(EJBApiAbstraction.class, new EJBApiAbstraction(beanDeploymentArchive.getServices().get(ResourceLoader.class)));
      services.add(JsfApiAbstraction.class, new JsfApiAbstraction(beanDeploymentArchive.getServices().get(ResourceLoader.class)));
      services.add(PersistenceApiAbstraction.class, new PersistenceApiAbstraction(beanDeploymentArchive.getServices().get(ResourceLoader.class)));
      services.add(WSApiAbstraction.class, new WSApiAbstraction(beanDeploymentArchive.getServices().get(ResourceLoader.class)));
      this.beanManager = BeanManagerImpl.newManager(deploymentManager, beanDeploymentArchive.getId(), services, Enabled.of(beanDeploymentArchive.getBeansXml(), beanDeploymentArchive.getServices().get(ResourceLoader.class)));
      services.add(InjectionTargetValidator.class, new InjectionTargetValidator(beanManager));
      log.debug(ENABLED_ALTERNATIVES, this.beanManager, beanManager.getEnabled().getAlternativeClasses(), beanManager.getEnabled().getAlternativeStereotypes());
      log.debug(ENABLED_DECORATORS, this.beanManager, beanManager.getEnabled().getDecorators());
      log.debug(ENABLED_INTERCEPTORS, this.beanManager, beanManager.getEnabled().getInterceptors());
      if (beanManager.getServices().contains(EjbServices.class))
      {
         // Must populate EJB cache first, as we need it to detect whether a
         // bean is an EJB!
         ejbDescriptors.addAll(beanDeploymentArchive.getEjbs());
      }
      beanDeployer = new BeanDeployer(beanManager, ejbDescriptors, deploymentServices);
      
      // Must at the Manager bean straight away, as it can be injected during startup!
      beanManager.addBean(new BeanManagerBean(beanManager));
      this.contexts = contexts;
   }

   public BeanManagerImpl getBeanManager()
   {
      return beanManager;
   }
   
   public BeanDeployer getBeanDeployer()
   {
      return beanDeployer;
   }
   
   public BeanDeploymentArchive getBeanDeploymentArchive()
   {
      return beanDeploymentArchive;
   }
   
   protected Iterable<String> loadClasses()
   {
      Function<Metadata<Filter>, Predicate<String>> filterToPredicateFunction = new Function<Metadata<Filter>, Predicate<String>>()
      {
         
         ResourceLoader resourceLoader = getBeanManager().getServices().get(ResourceLoader.class);
         
         public Predicate<String> apply(Metadata<Filter> from)
         {
            return new FilterPredicate(from, resourceLoader);
         }
         
      };
      Collection<String> classNames;
      if (getBeanDeploymentArchive().getBeansXml() != null && getBeanDeploymentArchive().getBeansXml().getScanning() != null)
      {
         Collection<Metadata<Filter>> includeFilters;
         if (getBeanDeploymentArchive().getBeansXml().getScanning().getIncludes() != null)
         {
            includeFilters = getBeanDeploymentArchive().getBeansXml().getScanning().getIncludes();
         }
         else
         {
            includeFilters = emptyList();
         }
         Collection<Metadata<Filter>> excludeFilters;
         if (getBeanDeploymentArchive().getBeansXml().getScanning().getExcludes() != null)
         {
            excludeFilters = getBeanDeploymentArchive().getBeansXml().getScanning().getExcludes();
         }
         else
         {
            excludeFilters = emptyList();
         }
      
         /*
          * Take a copy of the transformed collection, this means that the
          * filter predicate is only built once per filter predicte
          */
         Collection<Predicate<String>> includes = new ArrayList<Predicate<String>>(transform(includeFilters, filterToPredicateFunction));
         Collection<Predicate<String>> excludes = new ArrayList<Predicate<String>>(transform(excludeFilters, filterToPredicateFunction));
         Predicate<String> filters = new ScanningPredicate<String>(includes, excludes);
         classNames = filter(beanDeploymentArchive.getBeanClasses(), filters);
      }
      else
      {
         classNames = beanDeploymentArchive.getBeanClasses();
      }
      return classNames;
   }

   // TODO -- OK?
   public void createBeans(Environment environment)
   {
      beanDeployer.addClasses(loadClasses(), beanManager.getServices().get(ExecutorServices.class));
      beanDeployer.getEnvironment().addBuiltInBean(new InjectionPointBean(beanManager));
      beanDeployer.getEnvironment().addBuiltInBean(new EventBean(beanManager));
      beanDeployer.getEnvironment().addBuiltInBean(new InstanceBean(beanManager));
      beanDeployer.getEnvironment().addBuiltInBean(new ConversationBean(beanManager));
      if (beanManager.getServices().contains(TransactionServices.class))
      {
         beanDeployer.getEnvironment().addBuiltInBean(new UserTransactionBean(beanManager));
      }
      if (beanManager.getServices().contains(SecurityServices.class))
      {
         beanDeployer.getEnvironment().addBuiltInBean(new PrincipalBean(beanManager));
      }
      if (beanManager.getServices().contains(ValidationServices.class))
      {
         beanDeployer.getEnvironment().addBuiltInBean(new DefaultValidatorBean(beanManager));
         beanDeployer.getEnvironment().addBuiltInBean(new DefaultValidatorFactoryBean(beanManager));
      }
      // Register the context beans
      for (ContextHolder<? extends Context> context : contexts)
      {
         beanDeployer.getEnvironment().addBuiltInBean(ContextBean.of(context, beanManager));
      }
      // TODO Register the context beans
      beanDeployer.createBeans();
   }

   public void deploySpecialized(Environment environment)
   {
      beanDeployer.deploySpecialized();
   }

   // TODO Move class stuff into startContainer phase
   // TODO read EJB descriptors after reading classes
   public void deployBeans(Environment environment)
   {
      beanDeployer.deploy();
   }
   
   public void afterBeanDiscovery(Environment environment)
   {
      doAfterBeanDiscovery(beanManager.getBeans());
      doAfterBeanDiscovery(beanManager.getDecorators());
      doAfterBeanDiscovery(beanManager.getInterceptors());
   }

   private void doAfterBeanDiscovery(List<? extends Bean<?>> beanList)
   {
      for (Bean<?> bean : beanList)
      {
         if (bean instanceof RIBean<?>)
         {
            ((RIBean<?>) bean).initializeAfterBeanDiscovery();
         }
      }
   }
}
