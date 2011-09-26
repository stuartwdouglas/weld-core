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

public class FlagFarm {

    public static int currentRank = 0;

    public static int osgiStartEntrance = -1;
    public static int osgiStartExit = -1;
    public static int osgiStopEntrance = -1;
    public static int osgiStopExit = -1;

    public static int cdiStartEntrance = -1;
    public static int cdiStartExit = -1;
    public static int cdiStopEntrance = -1;
    public static int cdiStopExit = -1;

    public static int asynchronousStartedEntrance = -1;
    public static int asynchronousStartedExit = -1;

    public static int synchronousStartedEntrance = -1;
    public static int synchronousStartedExit = -1;
    public static int synchronousStoppingEntrance = -1;
    public static int synchronousStoppingExit = -1;

}
