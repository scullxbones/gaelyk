/*
 * Copyright 2009-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package groovyx.gaelyk.routes

import java.util.concurrent.CopyOnWriteArrayList

import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.ServletResponse
import javax.servlet.ServletRequest
import javax.servlet.FilterConfig
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import groovy.servlet.AbstractHttpServlet
import groovyx.gaelyk.GaelykBindingEnhancer
import groovyx.gaelyk.plugins.PluginsHandler
import groovyx.gaelyk.cache.CacheHandler
import groovyx.gaelyk.logging.GroovyLogger

import com.google.appengine.api.utils.SystemProperty
import com.google.appengine.api.NamespaceManager

import org.codehaus.groovy.control.CompilerConfiguration
import groovy.transform.CompileStatic

/**
 * <code>RoutesFilter</code> is a Servlet Filter whose responsability is to define URL mappings for your
 * Gaelyk application. When the servlet filter is configured, a file named <code>routes.groovy</code>
 * will be loaded by the filter, defining the various routes a web request may follow.
 * <p>
 * It is possible to customize the location of the routes definition file by using the
 * <code>routes.location</code> init parameter in the declaration of the filter in <code>web.xml</code>.
 * <p>
 * In development mode, routes will be reloaded automatically on each request, but when the application
 * is deployed on the Google cloud, all the routes will be set in stone.
 *
 * @author Guillaume Laforge
 */
class RoutesFilter implements Filter {

    static final String ORIGINAL_URI = 'originalURI'

    /**
     * Location of the routes file definition
     */
    private String routesFileLocation
    private long lastRoutesFileModification = 0
    private List<Route> routes = []
    private List<Route> routesFromRoutesFile = []
    private FilterConfig filterConfig
    private GroovyLogger log

    @CompileStatic
    void init(FilterConfig filterConfig) {
        this.filterConfig = filterConfig
        this.routesFileLocation = filterConfig.getInitParameter("routes.location") ?: "WEB-INF/routes.groovy"
        this.log = new GroovyLogger('gaelyk.routesfilter')
        if (SystemProperty.environment.value() == SystemProperty.Environment.Value.Development) {
            routes = new CopyOnWriteArrayList<Route>()
            routesFromRoutesFile =  new CopyOnWriteArrayList<Route>()
        }
        loadRoutes()
    }

    /**
     * Load the routes configuration
     */
    synchronized void loadRoutes() {
        log.config "Loading routes configuration"
        routes.clear()
        def routesFile = new File(this.routesFileLocation)

        if (routesFile.exists()) {
            def lastModified = routesFile.lastModified()

            // if the file has changed since the last check, reload the routes
            if (lastModified > lastRoutesFileModification) {
                def config = new CompilerConfiguration()
                config.scriptBaseClass = RoutesBaseScript.class.name

                // define a binding for the routes definition,
                // and inject the Google services
                def binding = new Binding()
                GaelykBindingEnhancer.bind(binding)

                // adds three nouns for the XMPP support
                binding.setVariable('chat',         'chat')
                binding.setVariable('presence',     'presence')
                binding.setVariable('subscription', 'subscription')

                // evaluate the route definitions
                RoutesBaseScript script = (RoutesBaseScript) new GroovyShell(binding, config).parse(routesFile)

                script.run()

                List<Route> scriptRoutes = script.routes
                routes.addAll scriptRoutes
                routesFromRoutesFile.clear()
                routesFromRoutesFile.addAll scriptRoutes

                // update the last modified flag
                lastRoutesFileModification = lastModified
            } else {
                routes.addAll routesFromRoutesFile
            }
        }
        // add the routes defined by the plugins
        routes.addAll PluginsHandler.instance.routes
    }

    /**
     * Forward or redirects requests to another URL if a matching route is defined.
     * Otherwise, the normal filter chain and routing applies.
     */
    void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) {
        // reload the routes in local dev mode in case the routes definition has changed since the last request
        if (SystemProperty.environment.value() == SystemProperty.Environment.Value.Development) {
            loadRoutes()
        }

        HttpServletRequest request = (HttpServletRequest)servletRequest
        HttpServletResponse response = (HttpServletResponse)servletResponse

        if(!request.getAttribute(ORIGINAL_URI)){
            request.setAttribute(ORIGINAL_URI, getIncludeAwareUri(request))
        }

        def method = request.method

        boolean foundRoute = false
        for (Route route in routes) {
            // first, check that the HTTP methods are compatible
            if (route.method == HttpMethod.ALL || route.method.toString() == method) {
                def result = route.forUri(getIncludeAwareUri(request), request)
                if (result.matches) {
                    if (route.ignore) {
                        // skip out completely
                        break
                    }
                    if (route.redirectionType == RedirectionType.FORWARD) {
                        if (route.namespace) {
                            NamespaceManager.of(result.namespace) {
                                CacheHandler.serve(route, request, response)
                            }
                        } else {
                            CacheHandler.serve(route, request, response)
                        }
                    } else if (route.redirectionType == RedirectionType.REDIRECT301) {
                        response.setStatus(301)
                        response.setHeader("Location", result.destination)
                        response.setHeader("Connection", "close")
                    } else {
                        response.sendRedirect result.destination
                    }
                    foundRoute = true
                    break
                }
            }
        }

        if (!foundRoute) {
            filterChain.doFilter servletRequest, servletResponse
        }
    }

    @CompileStatic
    void destroy() { }

    /**
    * Returns the include-aware uri.
    *
    * @param request the http request to analyze
    * @return the include-aware uri either parsed from request attributes or
    *         hints provided by the servlet container
    */
    @CompileStatic
    static String getIncludeAwareUri(HttpServletRequest request) {
        String uri = null
        String info = null

        uri = request.getAttribute(AbstractHttpServlet.INC_SERVLET_PATH)
        if (uri != null) {
            info = request.getAttribute(AbstractHttpServlet.INC_PATH_INFO)
            if (info != null) {
                uri += info
            }
            return uri
        }

        uri = request.getServletPath()
        info = request.getPathInfo()
        if (info != null) {
            uri += info
        }
        return uri
    }
}
