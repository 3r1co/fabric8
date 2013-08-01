/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fusesource.fabric.watcher.blueprint.web;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.aries.blueprint.container.BlueprintContainerImpl;
import org.fusesource.common.util.XmlHelper;
import org.fusesource.fabric.watcher.PathHelper;
import org.fusesource.fabric.watcher.Processor;
import org.fusesource.fabric.watcher.file.FileWatcher;
import org.osgi.service.blueprint.container.BlueprintContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link org.fusesource.fabric.watcher.file.FileWatcher} which finds OSGi Blueprint XML files
 * using the given file patterns and loads them up and starts them automatically
 */
public class WatcherBlueprintContainer extends FileWatcher {
    private static final transient Logger LOG = LoggerFactory.getLogger(WatcherBlueprintContainer.class);
    public static final String BLUEPRINT_NAMESPACE_URI = "http://www.osgi.org/xmlns/blueprint/v1.0.0";

    private ConcurrentHashMap<URL, BlueprintContainer> containerMap
            = new ConcurrentHashMap<URL, BlueprintContainer>();
    private ClassLoader classLoader;
    private Map<String, String> properties = new HashMap<String, String>();
    private AtomicBoolean closing = new AtomicBoolean(false);

    public WatcherBlueprintContainer() {
        this.classLoader = getClass().getClassLoader();
        setFileMatchPattern("glob:**.xml");
        setProcessor(new Processor() {
            public void process(Path path) {
                if (!closing.get()) {
                    addPath(path);
                }
            }

            public void onRemove(Path path) {
                if (!closing.get()) {
                    removePath(path);
                }
            }
        });
    }

    public void init() throws IOException {
        super.init();
        LOG.info("Watching directory " + getRoot() + " for Blueprint XML files to load");
    }

    public void destroy() {
        if (closing.compareAndSet(false, true)) {
            Set<Map.Entry<URL, BlueprintContainer>> entries = containerMap.entrySet();
            for (Map.Entry<URL, BlueprintContainer> entry : entries) {
                URL url = entry.getKey();
                BlueprintContainer container = entry.getValue();
                closeContext(url, container);
            }
        }
        super.destroy();
    }

    // Properties
    //-------------------------------------------------------------------------

    public Set<URL> getContainerURLs() {
        return new HashSet<URL>(containerMap.keySet());
    }

    public BlueprintContainer getApplicationContext(URL url) {
        return containerMap.get(url);
    }

    public ClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    // Implementation
    //-------------------------------------------------------------------------
    protected void addPath(Path path) {
        URL url = toUrl(path);
        if (url != null) {
            BlueprintContainer container = containerMap.get(url);
            if (container != null) {
                // There is no refresh API so lets close and restart
                closeContext(url, container);
            }

            try {
                container = createContext(path, url);
                if (container != null) {
                    containerMap.put(url, container);
                }
            } catch (Exception e) {
                LOG.info("Failed to create container at " + url + ". " + e, e);
            }
        }
    }


    protected void removePath(Path path) {
        URL url = toUrl(path);
        if (url != null) {
            BlueprintContainer context = containerMap.remove(url);
            closeContext(url, context);
        }
    }

    protected void closeContext(URL url, BlueprintContainer context) {
        if (context instanceof BlueprintContainerImpl) {
            BlueprintContainerImpl impl = (BlueprintContainerImpl)context;
            try {
                LOG.info("Closing context at path " + url + " context " + context);
                impl.destroy();
            } catch (Exception e) {
                LOG.info("Failed to close at " + url + " context " + context + ". " + e, e);
            }
        }
    }

    protected BlueprintContainer createContext(Path path, URL url) throws Exception {
        if (!XmlHelper.hasNamespace(path, BLUEPRINT_NAMESPACE_URI)) {
            LOG.info("Ignoring XML file " + path + " which is not a blueprint XML");
            return null;
        }
        List<URL> locations = Arrays.asList(url);
        return new BlueprintContainerImpl(classLoader, locations, properties, true);
    }

    protected URL toUrl(Path path) {
        URL url = null;
        try {
            url = PathHelper.toURL(path);
        } catch (MalformedURLException e) {
            LOG.warn("Ignored path " + path + " due to: " + e, e);
        }
        return url;
    }

}
