/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.weld.osgi.tests.lifecycle;

import org.osgi.framework.BundleEvent;
import org.osgi.framework.SynchronousBundleListener;

public class SynchronousListener implements SynchronousBundleListener
{
   Timer timer = new Timer();

   @Override
   public void bundleChanged(BundleEvent event)
   {
      if (event.getType() == BundleEvent.STARTED)
      {
         FlagFarm.synchronousStartedEntrance = FlagFarm.currentRank++;
         try
         {
            timer.process(500);
         }
         catch(InterruptedException ex)
         {
            throw new RuntimeException();
         }
         FlagFarm.synchronousStartedExit = FlagFarm.currentRank++;
      } else if (event.getType() == BundleEvent.STOPPING) {
         FlagFarm.synchronousStoppingEntrance = FlagFarm.currentRank++;
         try
         {
            timer.process(500);
         }
         catch(InterruptedException ex)
         {
            throw new RuntimeException();
         }
         FlagFarm.synchronousStoppingExit = FlagFarm.currentRank++;
      }
   }

}
