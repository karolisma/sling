/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.validation.impl.annotationmodel;

import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.apache.commons.collections4.EnumerationUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.ValidationStrategy;
import org.apache.sling.validation.impl.annotationmodel.builders.AnnotationValidationModelBuilder;
import org.apache.sling.validation.model.ValidationModel;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.util.tracker.BundleTracker;
import org.osgi.util.tracker.BundleTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The Validation package bundle listener. It analyzes all the bundles upon their activation, finds Sling Models marked with
 * ValidationStrategy.REQUIRED or ValidationStrategy.OPTIONAL and registers/unregisters validation models. */
public class ValidationPackageBundleListener implements BundleTrackerCustomizer {

    private static final String PACKAGE_HEADER = "Sling-Model-Packages";
    private static final String CLASSES_HEADER = "Sling-Model-Classes";

    private static final Logger LOG = LoggerFactory.getLogger(ValidationPackageBundleListener.class);

    private final BundleTracker bundleTracker;

    private final ValidationModelRegistry validationModelRegistry;

    /** Instantiates a new Validation package bundle listener.
     *
     * @param bundleContext the bundle context
     * @param validationModelImplementation the validation model implementation */
    public ValidationPackageBundleListener(BundleContext bundleContext,
            ValidationModelRegistry validationModelImplementation) {
        this.validationModelRegistry = validationModelImplementation;
        this.bundleTracker = new BundleTracker(bundleContext, Bundle.ACTIVE, this);
        this.bundleTracker.open();
    }

    @Override
    public Object addingBundle(Bundle bundle, BundleEvent event) {
        Set<String> classNames = getBundleClasses(bundle);
        classNames.forEach(className -> analyzeClass(bundle, className));
        return bundle;
    }

    @Override
    public void removedBundle(Bundle bundle, BundleEvent event, Object object) {
        validationModelRegistry.removeValidationModels(bundle);

    }

    @Override
    public void modifiedBundle(Bundle bundle, BundleEvent event, Object object) {
    }

    /** Unregister all. */
    public synchronized void unregisterAll() {
        this.bundleTracker.close();
    }

    /** Extracts all the class names which are set in bundle's package headers or class headers properties.
     * 
     * @param bundle from which class names should be extracted
     * @return Set of class names */
    private Set<String> getBundleClasses(@Nonnull Bundle bundle) {
        Set<String> classNames = new HashSet<>();
        classNames.addAll(extractPackageHeaderClassNames(bundle));
        classNames.addAll(getClassHeaderClassNames(bundle.getHeaders()));
        return classNames;
    }

    /** Analyses class and registers validation models for the bundle provided
     * 
     * @param bundle for which validation models will be registered
     * @param className to be analysed. */
    private void analyzeClass(Bundle bundle, String className) {
        try {
            Class<?> implType = bundle.loadClass(className);
            if (!implType.isAnnotationPresent(Model.class)) {
                return;
            }

            Model model = implType.getAnnotation(Model.class);
            if (model.validation().equals(ValidationStrategy.DISABLED)) {
                return;
            }

            List<ValidationModel> validationModels = new AnnotationValidationModelBuilder().build(implType);
            validationModelRegistry.registerValidationModelsByBundle(bundle, validationModels);

        } catch (ClassNotFoundException e) {
            LOG.warn("Unable to load class", e);
        }
    }

    /** Extracts Class names from Class Header parameter.
     * 
     * @param headers properties, which contain Classes Header
     * @return Set of class names */
    private HashSet<String> getClassHeaderClassNames(@Nonnull Dictionary<String, String> headers) {
        return Optional.ofNullable(StringUtils.deleteWhitespace(headers.get(CLASSES_HEADER)))
                .map(classes -> classes.split(","))
                .map(Arrays::asList)
                .map(HashSet::new)
                .orElse(new HashSet<>());
    }

    /** Extracts Class names from Package Header property.
     * 
     * @param bundle for which classes are extracted
     * @return Set of class names. */
    private Set<String> extractPackageHeaderClassNames(@Nonnull Bundle bundle) {
        return Optional.ofNullable(StringUtils.deleteWhitespace(bundle.getHeaders().get(PACKAGE_HEADER)))
                .map(packages -> packages.split(","))
                .map(Arrays::asList)
                .map(list -> extractClassNamesFromPackages(bundle, list))
                .orElse(Collections.emptySet());
    }

    /** Extracts Class names from given bundle and packages.
     * 
     * @param bundle in which classes will be searched
     * @param packages packages to be checked
     * @return Set of Class Names. */
    private Set<String> extractClassNamesFromPackages(@Nonnull Bundle bundle, @Nonnull List<String> packages) {
        return packages.parallelStream()
                .map(singlePackage -> findEntries(bundle, singlePackage))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
    }

    /** Finds class names in a bundle from a given package.
     * 
     * @param bundle in which package to be searched
     * @param singlePackage package name
     * @return Set of class names. */
    private Set<String> findEntries(@Nonnull Bundle bundle, @Nonnull String singlePackage) {
        return Optional.ofNullable(bundle.findEntries("/" + singlePackage.replace('.', '/'), "*.class", true))
                .map(EnumerationUtils::toList)
                .map(this::getClassNamesFromUrls)
                .orElse(Collections.emptySet());
    }

    /** Converts List of class URLs to class names
     * 
     * @param urls to be converted
     * @return Set of class names */
    private Set<String> getClassNamesFromUrls(List<URL> urls) {
        return urls.parallelStream()
                .map(this::getClassNameFromUrl)
                .collect(Collectors.toSet());
    }

    /** Convert class URL to class name */
    private String getClassNameFromUrl(URL url) {
        final String file = url.getFile();
        final String className = file.substring(1, file.length() - ".class".length());
        return className.replace('/', '.');
    }

}