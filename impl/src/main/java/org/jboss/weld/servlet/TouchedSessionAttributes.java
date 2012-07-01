/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.weld.servlet;

import java.util.HashSet;
import java.util.Set;

/**
 *
 * Tracks the attributes that have been touched in a request and calls {@link javax.servlet.http.HttpSession#setAttribute(String, Object)}
 *
 * @author Stuart Douglas
 */
public class TouchedSessionAttributes {

    private static final ThreadLocal<Set<String>> TOUCHED_ATTRIBUTES = new ThreadLocal<Set<String>>() {
        @Override
        protected Set<String> initialValue() {
            return new HashSet<String>();
        }
    };


    public static void markAttribute(final String attribute) {
        TOUCHED_ATTRIBUTES.get().add(attribute);
    }

    public static Set<String> getAndClearTouchedAttributes() {
        final Set<String> res = TOUCHED_ATTRIBUTES.get();
        TOUCHED_ATTRIBUTES.remove();
        return res;
    }

}
