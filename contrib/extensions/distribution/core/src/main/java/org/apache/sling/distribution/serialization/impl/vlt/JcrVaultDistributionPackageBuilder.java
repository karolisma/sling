/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.distribution.serialization.impl.vlt;


import javax.annotation.Nonnull;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.jackrabbit.vault.fs.api.ImportMode;
import org.apache.jackrabbit.vault.fs.api.WorkspaceFilter;
import org.apache.jackrabbit.vault.fs.io.AccessControlHandling;
import org.apache.jackrabbit.vault.fs.io.ImportOptions;
import org.apache.jackrabbit.vault.packaging.ExportOptions;
import org.apache.jackrabbit.vault.packaging.JcrPackage;
import org.apache.jackrabbit.vault.packaging.JcrPackageDefinition;
import org.apache.jackrabbit.vault.packaging.JcrPackageManager;
import org.apache.jackrabbit.vault.packaging.PackageId;
import org.apache.jackrabbit.vault.packaging.Packaging;
import org.apache.jackrabbit.vault.packaging.VaultPackage;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.distribution.DistributionRequest;
import org.apache.sling.distribution.common.DistributionException;
import org.apache.sling.distribution.serialization.DistributionPackage;
import org.apache.sling.distribution.serialization.DistributionPackageBuilder;
import org.apache.sling.distribution.serialization.impl.AbstractDistributionPackage;
import org.apache.sling.distribution.serialization.impl.AbstractDistributionPackageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * a {@link org.apache.sling.distribution.serialization.DistributionPackageBuilder} based on Apache Jackrabbit FileVault.
 * <p/>
 * Each {@link DistributionPackage} created by {@link JcrVaultDistributionPackageBuilder} is
 * backed by a {@link org.apache.jackrabbit.vault.packaging.JcrPackage}. 
 */
public class JcrVaultDistributionPackageBuilder extends AbstractDistributionPackageBuilder implements
        DistributionPackageBuilder {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final String VERSION = "0.0.1";
    private static final String PACKAGE_GROUP = "sling/distribution";

    private final Packaging packaging;
    private final ImportMode importMode;
    private final AccessControlHandling aclHandling;
    private final String[] packageRoots;
    private final int autosaveThreshold;
    private final String tempPackagesNode;
    private final File tempDirectory;
    private final TreeMap<String, List<String>> filters;
    private final boolean useBinaryReferences;

    private final Object repolock = new Object();

    public JcrVaultDistributionPackageBuilder(String type, Packaging packaging, ImportMode importMode, AccessControlHandling aclHandling,
                                              String[] packageRoots, String[] filterRules, String tempFilesFolder, boolean useBinaryReferences, int autosaveThreshold) {
        super(type);

        this.packaging = packaging;

        this.importMode = importMode;
        this.aclHandling = aclHandling;
        this.packageRoots = packageRoots;
        this.autosaveThreshold = autosaveThreshold;
        this.tempPackagesNode = type + "/data";

        this.tempDirectory = VltUtils.getTempFolder(tempFilesFolder);
        this.filters = VltUtils.parseFilters(filterRules);
        this.useBinaryReferences = useBinaryReferences;
    }

    @Override
    protected DistributionPackage createPackageForAdd(@Nonnull ResourceResolver resourceResolver, @Nonnull DistributionRequest request) throws DistributionException {
        Session session = null;
        VaultPackage vaultPackage = null;
        JcrPackage jcrPackage = null;

        try {
            session = getSession(resourceResolver);

            String packageGroup = PACKAGE_GROUP;
            String packageName = getType() + "_" + System.currentTimeMillis() + "_" + UUID.randomUUID();

            WorkspaceFilter filter = VltUtils.createFilter(request, filters);
            ExportOptions opts = VltUtils.getExportOptions(filter, packageRoots, packageGroup, packageName, VERSION, useBinaryReferences);

            log.debug("assembling package {} user {}", packageGroup + '/' + packageName + "-" + VERSION, resourceResolver.getUserID());

            vaultPackage = VltUtils.createPackage(packaging.getPackageManager(), session, opts, tempDirectory);

            jcrPackage = uploadPackage(session, vaultPackage);

            return new JcrVaultDistributionPackage(getType(), jcrPackage, session);
        } catch (Throwable e) {
            VltUtils.deletePackage(jcrPackage);
            throw new DistributionException(e);
        } finally {
            ungetSession(session);
            VltUtils.deletePackage(vaultPackage);
        }
    }

    @Override
    protected DistributionPackage readPackageInternal(@Nonnull ResourceResolver resourceResolver, @Nonnull InputStream stream) throws DistributionException {
        Session session = null;
        VaultPackage vaultPackage = null;
        JcrPackage jcrPackage = null;

        try {
            session = getSession(resourceResolver);
            vaultPackage = VltUtils.readPackage(packaging.getPackageManager(), stream, tempDirectory);

            jcrPackage = uploadPackage(session, vaultPackage);

            return new JcrVaultDistributionPackage(getType(), jcrPackage, session);
        } catch (Throwable e) {
            VltUtils.deletePackage(jcrPackage);
            throw new DistributionException(e);
        } finally {
            ungetSession(session);
            VltUtils.deletePackage(vaultPackage);
        }
    }

    @Override
    protected boolean installPackageInternal(@Nonnull ResourceResolver resourceResolver, @Nonnull DistributionPackage distributionPackage) throws DistributionException {
        Session session = null;
        VaultPackage vaultPackage = null;
        try {
            session = getSession(resourceResolver);

            InputStream stream = distributionPackage.createInputStream();
            vaultPackage = VltUtils.readPackage(packaging.getPackageManager(), stream, tempDirectory);

            ImportOptions importOptions = VltUtils.getImportOptions(aclHandling, importMode, autosaveThreshold);
            vaultPackage.extract(session, importOptions);

            return true;
        } catch (Exception e) {
            throw new DistributionException(e);
        } finally {
            VltUtils.deletePackage(vaultPackage);
            ungetSession(session);
        }
    }

    @Override
    protected DistributionPackage getPackageInternal(@Nonnull ResourceResolver resourceResolver, @Nonnull String id) {
        Session session = null;
        try {
            session = getSession(resourceResolver);

            JcrPackage jcrPackage = openPackage(session, id);

            return new JcrVaultDistributionPackage(getType(), jcrPackage, session);
        } catch (RepositoryException e) {
            log.error("cannot ge package with id {}", id, e);
            return null;
        } finally {
            ungetSession(session);
        }
    }

    private JcrPackage uploadPackage(Session session, VaultPackage pack) throws IOException, RepositoryException {
        JcrPackageManager packageManager = packaging.getPackageManager(session);

        Node packageRoot = getPackageRoot(session);
        PackageId packageId = getPackageId(pack);

        InputStream in = FileUtils.openInputStream(pack.getFile());

        try {
            String packageName = packageId.getDownloadName();
            if (packageRoot.hasNode(packageName)) {
                packageRoot.getNode(packageName).remove();
            }

            JcrPackage jcrPackage = packageManager.create(packageRoot, packageName);
            Property data = jcrPackage.getData();
            data.setValue(in);
            JcrPackageDefinition def = jcrPackage.getDefinition();
            def.unwrap(pack, true, false);

            log.debug("package uploaded to {}", jcrPackage.getNode().getPath());

            return jcrPackage;
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    private JcrPackage openPackage(Session session, String packageName) throws RepositoryException {
        JcrPackageManager packageManager = packaging.getPackageManager(session);

        Node packageRoot = getPackageRoot(session);
        PackageId packageId = new PackageId(PACKAGE_GROUP, packageName, VERSION);

        Node packageNode = packageRoot.getNode(packageId.getDownloadName());
        return packageManager.open(packageNode);
    }

    private PackageId getPackageId(VaultPackage vaultPackage) {
        Properties props = vaultPackage.getMetaInf().getProperties();

        String version = props.getProperty(VaultPackage.NAME_VERSION);
        String group = props.getProperty(VaultPackage.NAME_GROUP);
        String name = props.getProperty(VaultPackage.NAME_NAME);

        return new PackageId(group, name, version);
    }

    private Node getPackageRoot(Session session) throws RepositoryException {
        Node packageRoot = JcrUtils.getNodeIfExists(AbstractDistributionPackage.PACKAGES_ROOT + "/" + tempPackagesNode, session);

        if (packageRoot != null) {
            return packageRoot;
        }

        synchronized (repolock) {
            session.refresh(false);

            Node tempRoot = JcrUtils.getNodeIfExists(AbstractDistributionPackage.PACKAGES_ROOT, session);

            if (tempRoot == null) {
                tempRoot = JcrUtils.getOrCreateByPath(AbstractDistributionPackage.PACKAGES_ROOT, "sling:Folder", "sling:Folder", session, true);
            }

            packageRoot = JcrUtils.getOrCreateByPath(tempRoot, tempPackagesNode, false, "sling:Folder", "sling:Folder", true);

        }

        return packageRoot;
    }
}