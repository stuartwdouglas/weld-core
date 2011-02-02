package org.jboss.weld.bean.proxy;

import static org.jboss.weld.bean.proxy.InterceptionDecorationContext.endInterceptorContext;
import static org.jboss.weld.bean.proxy.InterceptionDecorationContext.startInterceptorContext;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

import javassist.util.proxy.MethodHandler;

import org.jboss.weld.util.reflection.SecureReflections;

/**
 * A method handler that wraps the invocation of interceptors and decorators.
 *
 * @author Marius Bogoevici
 */
public class CombinedInterceptorAndDecoratorStackMethodHandler implements MethodHandler, Serializable, InvocationHandler
{

   private MethodHandler interceptorMethodHandler;

   private Object outerDecorator;

   private final InvocationHandlerAdaptor adaptor;

   public CombinedInterceptorAndDecoratorStackMethodHandler()
   {
      adaptor = new InvocationHandlerAdaptor(this);
   }

   public void setInterceptorMethodHandler(MethodHandler interceptorMethodHandler)
   {
      this.interceptorMethodHandler = interceptorMethodHandler;
   }

   public void setOuterDecorator(Object outerDecorator)
   {
      this.outerDecorator = outerDecorator;
   }

   private Set<CombinedInterceptorAndDecoratorStackMethodHandler> getDisabledHandlers()
   {

      return InterceptionDecorationContext.peek();
   }

   public Object invoke(Object self, Method thisMethod, Method proceed, Object[] args) throws Throwable
   {
      boolean externalContext = false;

      try
      {
         if (InterceptionDecorationContext.empty())
         {
            externalContext = true;
            startInterceptorContext();
         }
         if (!getDisabledHandlers().contains(this))
         {
            try
            {

               getDisabledHandlers().add(this);
               if (interceptorMethodHandler != null)
               {
                  if (proceed != null)
                  {
                     return this.interceptorMethodHandler.invoke(outerDecorator != null ? outerDecorator : self, thisMethod, thisMethod, args);
                  }
                  else
                  {
                     return this.interceptorMethodHandler.invoke(self, thisMethod, proceed, args);
                  }
               }
               else
               {
                  if (outerDecorator != null)
                  {
                     return SecureReflections.invoke(outerDecorator, thisMethod, args);
                  }
               }
            }
            finally
            {
               this.getDisabledHandlers().remove(this);
            }
         }
         return SecureReflections.invoke(self, proceed, args);
      }
      catch (InvocationTargetException e)
      {
         throw e.getCause();
      }
      finally
      {
         if (externalContext)
         {
            endInterceptorContext();
         }
      }
   }

   public boolean isDisabledHandler()
   {
      if (InterceptionDecorationContext.empty())
      {
         return false;
      }
      return getDisabledHandlers().contains(this);
   }

   public Object invoke(Object proxy, Method method, Object[] args) throws Throwable
   {
      return adaptor.invoke(proxy, method, args);
   }

}