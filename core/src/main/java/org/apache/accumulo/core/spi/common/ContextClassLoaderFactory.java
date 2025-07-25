/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.core.spi.common;

/**
 * The ContextClassLoaderFactory provides a mechanism for various Accumulo components to use a
 * custom ClassLoader for specific contexts, such as loading table iterators. This factory is
 * initialized at startup and supplies ClassLoaders when provided a context.
 *
 * <p>
 * This factory can be configured using the <code>general.context.class.loader.factory</code>
 * property. All implementations of this factory must have a default (no-argument) public
 * constructor.
 *
 * <p>
 * A default implementation is provided for Accumulo 2.x to retain existing context class loader
 * behavior based on per-table configuration. However, after Accumulo 2.x, the default is expected
 * to change to a simpler implementation, and users will need to provide their own implementation to
 * support advanced context class loading features. Some implementations may be maintained by the
 * Accumulo developers in a separate package. Check the Accumulo website or contact the developers
 * for more details on the status of these implementations.
 *
 * <p>
 * Because this factory is expected to be instantiated early in the application startup process,
 * configuration is expected to be provided within the environment (such as in Java system
 * properties or process environment variables), and is implementation-specific. However, some
 * limited environment is also available so implementations can have access to Accumulo's own system
 * configuration.
 *
 * @since 2.1.0
 */
public interface ContextClassLoaderFactory {

  class ContextClassLoaderException extends Exception {

    private static final long serialVersionUID = 1L;
    private static final String msg = "Error getting classloader for context: ";

    public ContextClassLoaderException(String context, Throwable cause, boolean enableSuppression,
        boolean writableStackTrace) {
      super(msg + context, cause, enableSuppression, writableStackTrace);
    }

    public ContextClassLoaderException(String context, Throwable cause) {
      super(msg + context, cause);
    }

    public ContextClassLoaderException(String context) {
      super(msg + context);
    }

  }

  /**
   * Pass the service environment to allow for additional class loader configuration
   *
   * @param env the class loader environment
   */
  default void init(ContextClassLoaderEnvironment env) {}

  /**
   * Get the class loader for the given context. Callers should not cache the ClassLoader result as
   * it may change if/when the ClassLoader reloads. Implementations should throw a
   * ContextClassLoaderException if the provided contextName is not supported or fails to be
   * constructed.
   *
   * @param context the name of the context that represents a class loader that is managed by this
   *        factory. Currently, Accumulo will only call this method for non-null and non-empty
   *        context. For empty or null context, Accumulo will use the system classloader without
   *        consulting this plugin.
   * @return the class loader for the given context
   */
  ClassLoader getClassLoader(String context) throws ContextClassLoaderException;
}
