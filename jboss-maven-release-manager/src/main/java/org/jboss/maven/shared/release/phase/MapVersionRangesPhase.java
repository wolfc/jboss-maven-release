/*
 * JBoss, Home of Professional Open Source.
 * Copyright (c) 2013, Red Hat, Inc., and individual contributors
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
package org.jboss.maven.shared.release.phase;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.artifact.resolver.AbstractArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.InvalidDependencyVersionException;
import org.apache.maven.shared.release.ReleaseExecutionException;
import org.apache.maven.shared.release.ReleaseFailureException;
import org.apache.maven.shared.release.ReleaseResult;
import org.apache.maven.shared.release.config.ReleaseDescriptor;
import org.apache.maven.shared.release.env.ReleaseEnvironment;
import org.apache.maven.shared.release.phase.AbstractReleasePhase;

import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * @author <a href="mailto:cdewolf@redhat.com">Carlo de Wolf</a>
 * @plexus.component role="org.apache.maven.shared.release.phase.ReleasePhase" role-hint="map-version-ranges"
 */
public class MapVersionRangesPhase extends AbstractReleasePhase {
    /**
     * Component used to create artifacts
     *
     * @plexus.requirement
     */
    private ArtifactFactory artifactFactory;

    /**
     * @plexus.requirement
     */
    private ArtifactMetadataSource artifactMetadataSource;

//    /**
//     * @plexus.component role-hint="default"
//     */
//    private ArtifactRepositoryLayout artifactRepositoryLayout;

    /**
     * Component used to resolve artifacts
     *
     * @plexus.requirement
     */
    private ArtifactResolver artifactResolver;

    private ArtifactRepository localRepository;

    private static <T> Set<T> asSet(final T... a) {
        return new AbstractSet<T>() {
            @Override
            public Iterator<T> iterator() {
                return new Iterator<T>() {
                    private int cursor = 0;

                    public boolean hasNext() {
                        return cursor != a.length;
                    }

                    public T next() {
                        if (cursor < a.length)
                            return a[cursor++];
                        throw new NoSuchElementException();
                    }

                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }

            @Override
            public int size() {
                return a.length;
            }
        };
    }

    private void checkArtifact(final MavenProject project, Artifact artifact, Map<String, String> originalVersions, Map<String, Artifact> artifactMapByVersionlessId, ReleaseDescriptor releaseDescriptor) throws ReleaseExecutionException {
        Artifact checkArtifact = getArtifactFromMap(artifact, artifactMapByVersionlessId);

        checkArtifact(project, checkArtifact, originalVersions, releaseDescriptor);
    }

    private void checkArtifact(final MavenProject project, Artifact artifact, Map<String, String> originalVersions, ReleaseDescriptor releaseDescriptor) throws ReleaseExecutionException {
        String versionlessArtifactKey = ArtifactUtils.versionlessKey(artifact.getGroupId(), artifact.getArtifactId());

        // TODO: needed?
        // We are only looking at dependencies external to the project - ignore anything found in the reactor as
        // it's version will be updated
        if (artifact.getBaseVersion() != null && artifact.getBaseVersion().equals(originalVersions.get(versionlessArtifactKey)))
            return;

        // if it is a version range (wild guess)
        if (artifact.getVersion() == null && artifact.getVersionRange() != null) {
            final String versionRange = artifact.getVersionRange().toString();
            resolveVersionRange(artifact, project);
            releaseDescriptor.mapReleaseVersion(versionlessArtifactKey, artifact.getVersion());

            /*
            // a hack to ensure AbstractRewritePomsPhase detects it as a change
            final Map<String, String> versionMap = new HashMap<String, String>();
            versionMap.put(ReleaseDescriptor.ORIGINAL_VERSION, versionRange);
            versionMap.put(ReleaseDescriptor.RELEASE_KEY, "n/a");
            versionMap.put(ReleaseDescriptor.DEVELOPMENT_KEY, "n/a");
            releaseDescriptor.getResolvedSnapshotDependencies().put(versionlessArtifactKey, versionMap);
            */
            originalVersions.put(versionlessArtifactKey, versionRange);
        }
    }

    private void checkDependencies(final MavenProject project, Map<String, String> originalVersions, ReleaseDescriptor releaseDescriptor,
                                   Map<String, Artifact> artifactMap, Set<Artifact> dependencyArtifacts) throws ReleaseExecutionException {
        for (Artifact artifact : dependencyArtifacts) {
            checkArtifact(project, artifact, originalVersions, artifactMap, releaseDescriptor);
        }
    }

    private void checkProject(final MavenProject project, final Map<String, String> originalVersions, final ReleaseDescriptor releaseDescriptor) throws ReleaseExecutionException {
        Map<String, Artifact> artifactMap = ArtifactUtils.artifactMapByVersionlessId(project.getArtifacts());
        try {
            Set<Artifact> dependencyArtifacts = project.createArtifacts(artifactFactory, null, null);
            checkDependencies(project, originalVersions, releaseDescriptor, artifactMap, dependencyArtifacts);
        } catch (InvalidDependencyVersionException e) {
            throw new ReleaseExecutionException("Failed to create dependency artifacts", e);
        }
    }

    private static Artifact getArtifactFromMap(Artifact artifact, Map<String, Artifact> artifactMapByVersionlessId) {
        String versionlessId = ArtifactUtils.versionlessKey(artifact);
        Artifact checkArtifact = artifactMapByVersionlessId.get(versionlessId);

        if (checkArtifact == null) {
            checkArtifact = artifact;
        }
        return checkArtifact;
    }

    public ReleaseResult execute(final ReleaseDescriptor releaseDescriptor, final ReleaseEnvironment releaseEnvironment, final List<MavenProject> reactorProjects) throws ReleaseExecutionException, ReleaseFailureException {
        // without localRepository, artifactResolver will crash if the artifact can not be found
        final String url = "file://" + releaseEnvironment.getLocalRepositoryDirectory().getAbsolutePath();
        // TODO: injection of artifactRepositoryLayout does not work
        this.localRepository = new DefaultArtifactRepository("local", url, new DefaultRepositoryLayout());

        final ReleaseResult result = new ReleaseResult();

        Map<String, String> originalVersions = releaseDescriptor.getOriginalVersions(reactorProjects);

        for (MavenProject project : reactorProjects) {
            checkProject(project, originalVersions, releaseDescriptor);
        }
        result.setResultCode(ReleaseResult.SUCCESS);

        return result;
    }

    private Artifact resolveVersionRange(final Artifact artifact, final MavenProject project) throws ReleaseExecutionException {
        final Set<Artifact> artifacts = asSet(artifact);
        final ArtifactResolutionResult result;
        try {
            result = artifactResolver.resolveTransitively(artifacts, project.getArtifact(), project.getRemoteArtifactRepositories(), localRepository, artifactMetadataSource);
            return (Artifact) result.getArtifacts().iterator().next();
        } catch (AbstractArtifactResolutionException e) {
            throw new ReleaseExecutionException("Failed to resolve artifact " + artifact, e);
        }
    }

    public ReleaseResult simulate(final ReleaseDescriptor releaseDescriptor, final ReleaseEnvironment releaseEnvironment, final List<MavenProject> reactorProjects) throws ReleaseExecutionException, ReleaseFailureException {
        return execute(releaseDescriptor, releaseEnvironment, reactorProjects);
    }
}
