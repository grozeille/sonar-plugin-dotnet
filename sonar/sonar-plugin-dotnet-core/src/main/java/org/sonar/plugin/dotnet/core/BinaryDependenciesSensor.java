/*
 * Maven and Sonar plugin for .Net
 * Copyright (C) 2010 Jose Chillan and Alexandre Victoor
 * mailto: jose.chillan@codehaus.org or alexvictoor@codehaus.org
 *
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */

package org.sonar.plugin.dotnet.core;

import java.util.List;

import org.apache.maven.dotnet.commons.project.BinaryReference;
import org.apache.maven.dotnet.commons.project.DotNetProjectException;
import org.apache.maven.dotnet.commons.project.VisualStudioProject;
import org.apache.maven.dotnet.commons.project.VisualStudioSolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.database.DatabaseSession;
import org.sonar.api.database.model.Snapshot;
import org.sonar.api.design.Dependency;
import org.sonar.api.design.DependencyDto;
import org.sonar.api.resources.Library;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.batch.index.ResourcePersister;
import org.sonar.plugin.dotnet.core.project.VisualUtils;

public class BinaryDependenciesSensor implements Sensor {

	private final static Logger log = LoggerFactory
			.getLogger(BinaryDependenciesSensor.class);

	private final ResourcePersister resourcePersister;
	
	private final DatabaseSession session;
	
	public BinaryDependenciesSensor(ResourcePersister resourcePersister, DatabaseSession session) {
		  this.resourcePersister = resourcePersister;
		  this.session = session;
	}

	@Override
	public void analyse(Project project, SensorContext context) {

		try {
			VisualStudioSolution solution = VisualUtils.getSolution(project);
			List<VisualStudioProject> projects = solution.getProjects();
			for (VisualStudioProject vsProject : projects) {
				
				// find the referenced project in the Maven modules
				Resource<?> subProject = VisualUtils.getProjectFromName(project,
						vsProject.getAssemblyName());
				
				// TODO find a way to get dependencies associated to assemblies
				// CLRAssembly assembly = new CLRAssembly(vsProject);
				// Resource<?> savedAssembly = context.getResource(assembly);
				List<BinaryReference> binaryReferences = vsProject
						.getBinaryReferences();
				
				// save dependencies to external assemblies
				for (BinaryReference binaryReference : binaryReferences) {
					Resource<?> lib = new Library(binaryReference
							.getAssemblyName(), binaryReference.getVersion());

					// save the assembly if needed
					Resource<?> savedLib = context.getResource(lib);
					if (savedLib == null) {
						context.saveResource(lib);
						savedLib = context.getResource(lib);
					}
					
					// save the dependency
					Dependency dependency = new Dependency(subProject, savedLib);
					dependency.setUsage("compile");
					dependency.setWeight(1);
					context.saveDependency(dependency);
					
					// TODO: I know, it's a very ugly trick, but here we need to "attach" the dependency to the module, 
					// instead of the parent Project, so we are doing a Hardcode Update on the Database  				
					DependencyDto dependencyDto = session.getEntity(DependencyDto.class, dependency.getId());
					Snapshot snapshot = resourcePersister.getSnapshot(subProject);
					dependencyDto.setProjectSnapshotId(snapshot.getId());
					session.save(dependencyDto);
					session.commit();
				/*
					String json = "[{\"i\":"+savedLib.getId()+
						",\"n\":\""+savedLib.getKey()+
						"\",\"q\":\""+savedLib.getQualifier()+"\",\"v\":[{}]";
				
					Measure measure = new Measure(CoreMetrics.DEPENDENCY_MATRIX, json);
					measure.setPersistenceMode(PersistenceMode.DATABASE);
					context.saveMeasure(measure);			    */
				}
			}

		} catch (DotNetProjectException e) {
			log.error("Error during binary dependency analysis", e);
		}

	}

	@Override
	public boolean shouldExecuteOnProject(Project project) {
		String packaging = project.getPackaging();
		// We only accept the "sln" packaging
		return "sln".equals(packaging);
	}

}
